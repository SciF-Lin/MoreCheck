package com.morecheck.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 云会话的持久化。
 *
 * ⚠️ 必须用**独立的 DataStore 文件**（`cloud_session`），不能并入 `settings`：
 * `NotebookStore` 的文件头注释记录了原因——同一个文件被两个 `preferencesDataStore`
 * 委托持有会直接抛 `IllegalStateException`（DataStore 的进程级唯一性校验）。
 *
 * 只存一个 JSON 字符串键：会话就五个字段，拆成多个键只会让「写一半」
 * （登录态写进去了、过期时间没写）这种中间态有机会被读出来。
 */
private val Context.cloudSessionStore by preferencesDataStore(name = "cloud_session")

private object SessionKeys {
    val JSON = stringPreferencesKey("session_json")
}

/**
 * 「发送验证码」的结果。
 *
 * @property verificationId 服务端返回的验证会话 ID。提交验证码时必须原样带回。
 * @property isExistingUser 该手机号是否已注册。**仅供内部决定走登录还是注册，
 *   不得展示给用户**（「这个号码有没有注册过」属于账号存在性泄露）。
 */
data class OtpChallenge(
    val phone: String,
    val verificationId: String,
    val isExistingUser: Boolean
)

/**
 * 云端认证客户端：手机号验证码登录 / 注册、会话刷新、登出。
 *
 * ## 登录流程（与官方 Web SDK 同一套闭环）
 *
 * ```text
 * sendCode   → POST /.cloud/auth/v1/verification        → {verification_id, is_user}
 * verifyCode → POST /.cloud/auth/v1/verification/verify  → {verification_token}
 *              → 老用户 POST /v1/signin  {username, verification_token}
 *              → 新用户 POST /v1/signup  {phone_number, verification_token}
 *              → 会话 {access_token, refresh_token, expires_in, sub}
 * ```
 *
 * 验证码只发一次、提交时不再发（提交错码重试同一 [OtpChallenge]），
 * 与官方 SDK 的约束一致：`sendOtp` / `verifyOtp` 必须绑定在
 * 「获取验证码」和「登录」两个独立动作上。
 *
 * ## 会话存放与刷新
 *
 * - 会话落在 DataStore `cloud_session`（见文件头），**刷新令牌也在里面**——
 *   它是长期手柄，与访问令牌同等级保管，不写日志、不进界面；
 * - [validToken] 保证返回的令牌距过期还有 [CloudConfig.REFRESH_WINDOW_MS] 以上；
 * - 刷新是**单飞**的（[refreshMutex]）：并发请求只会有一个真正发
 *   `POST /v1/token`，其余等它完成后共用新令牌；
 * - 刷新被服务端拒绝（`invalid_grant`）= 会话真正失效 → 清空并通知登出；
 *   网络类失败则**保留会话**（下次同步自然重试），与官方 SDK 的
 *   `SessionManager` 同一口径。
 */
class CloudAuthClient(private val context: Context) : SessionProvider {

    private val http = CloudHttp(this)

    private val _session = MutableStateFlow<CloudSession?>(null)

    /** 当前会话。未登录为 null。界面（「我的」页云账号卡片）订阅它。 */
    val session: StateFlow<CloudSession?> = _session.asStateFlow()

    private val restoreMutex = Mutex()
    private val refreshMutex = Mutex()

    @Volatile
    private var restored = false

    // ---- 登录 / 注册 --------------------------------------------------------

    /**
     * 发送短信验证码。
     *
     * @param phone 用户输入的 11 位手机号（不带 +86）。方法内做格式校验，
     *   不合法直接抛 [CloudError.Kind.INVALID_REQUEST]，**不发请求**。
     * @throws CloudException 限流（429）时消息即服务端原文，界面照实展示。
     */
    suspend fun sendCode(phone: String): OtpChallenge {
        requireMainlandPhone(phone)
        val normalized = normalizePhone(phone)
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/verification",
            body = JSONObject().apply {
                put("phone_number", normalized)
                // target = ANY：服务端自行决定送达渠道（本项目只有短信）。
                put("target", "ANY")
            },
            requireAuth = false
        )
        val json = response.asJsonObject()
        val verificationId = json.optString("verification_id", "").ifBlank { null }
            ?: cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回验证码会话，请稍后重试",
                    code = null,
                    status = 200
                )
            )
        return OtpChallenge(
            phone = normalized,
            verificationId = verificationId,
            // 字段名是 is_user（不是 isExistingUser）——服务端契约。
            isExistingUser = json.optBoolean("is_user", false)
        )
    }

    /**
     * 提交验证码并完成登录 / 注册。
     *
     * 新用户走注册（无需密码），老用户走登录——由 [OtpChallenge.isExistingUser]
     * 决定，调用方不判断、也不把这个信息展示给用户。
     *
     * @return 登录后的会话（已持久化，[session] 同步发出）。
     */
    suspend fun verifyCode(challenge: OtpChallenge, code: String): CloudSession {
        if (code.isBlank()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.INVALID_REQUEST,
                    message = "请输入验证码",
                    code = null,
                    status = 0
                )
            )
        }
        // 第一步：校验验证码，换一次性 verification_token
        val verifyResponse = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/verification/verify",
            body = JSONObject().apply {
                put("verification_id", challenge.verificationId)
                put("verification_code", code)
            },
            requireAuth = false
        )
        val verificationToken = verifyResponse.asJsonObject()
            .optString("verification_token", "").ifBlank { null }
            ?: cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回验证令牌，请稍后重试",
                    code = null,
                    status = 200
                )
            )

        // 第二步：老用户登录 / 新用户注册。两端点的参数字段名不同
        // （signin 收 username，signup 收 phone_number），照官方 SDK 的契约分别构造。
        val sessionResponse = if (challenge.isExistingUser) {
            http.send(
                method = "POST",
                url = "${CloudConfig.AUTH_BASE}/v1/signin",
                body = JSONObject().apply {
                    put("username", challenge.phone)
                    put("verification_token", verificationToken)
                },
                requireAuth = false
            )
        } else {
            http.send(
                method = "POST",
                url = "${CloudConfig.AUTH_BASE}/v1/signup",
                body = JSONObject().apply {
                    put("phone_number", challenge.phone)
                    put("verification_token", verificationToken)
                },
                requireAuth = false
            )
        }
        val session = parseSession(sessionResponse.asJsonObject(), fallbackPhone = challenge.phone)
        persist(session)
        _session.value = session
        return session
    }

    // ---- 会话查询 / 刷新 / 登出 ----------------------------------------------

    override suspend fun validToken(): String? {
        restoreOnce()
        val current = _session.value ?: return null
        if (current.expiresAt - System.currentTimeMillis() < CloudConfig.REFRESH_WINDOW_MS) {
            return refreshToken() ?: current.accessToken.takeIf {
                // 刷新失败但本地还没判失效：不把明显过期的令牌发出去
                current.expiresAt - System.currentTimeMillis() > 0
            }
        }
        return current.accessToken
    }

    override suspend fun refreshToken(): String? {
        restoreOnce()
        val current = _session.value ?: return null
        return refreshMutex.withLock {
            // 双检：等锁期间可能已被其它协程刷新过（单飞的意义正在于此）
            val latest = _session.value ?: return@withLock null
            if (latest.expiresAt > current.expiresAt) return@withLock latest.accessToken
            try {
                val refreshed = postRefresh(latest.refreshToken, keepPhone = latest.phone)
                persist(refreshed)
                _session.value = refreshed
                refreshed.accessToken
            } catch (e: CloudException) {
                if (e.error.kind == CloudError.Kind.UNAUTHENTICATED) {
                    // 刷新令牌被服务端拒绝 = 会话真正失效。清空并广播登出，
                    // 界面据此跳登录页。
                    clearPersisted()
                    _session.value = null
                    null
                } else {
                    // 网络 / 服务端暂时不可用：**不清会话**，把真实错误抛出去，
                    // 让这次请求以「网络异常」失败，下次同步自然重试。
                    throw e
                }
            }
        }
    }

    /** 当前用户信息（服务端权威）。界面「云账号」卡片刷新时用。 */
    suspend fun me(): CloudUser {
        val response = http.send(method = "GET", url = "${CloudConfig.AUTH_BASE}/v1/user/me")
        val json = response.asJsonObject()
        return CloudUser(
            id = json.optString("sub", "").ifBlank { json.optString("uid", "") },
            phone = json.optString("phone_number", "").ifBlank { null }
                ?: json.optString("phone", "").ifBlank { null },
            name = json.optString("name", "").ifBlank { null },
            avatarUrl = json.optString("avatar_url", "").ifBlank { null }
        )
    }

    /**
     * 登出。
     *
     * 服务端通知是**尽力而为**：失败了也照常清本地会话（用户点「退出登录」
     * 必须立即生效，不能因为一次网络抖动退不出去）。服务端那侧的会话
     * 由刷新令牌自然过期兜底。
     */
    suspend fun signOut() {
        val current = _session.value
        if (current != null) {
            runCatching {
                http.send(
                    method = "POST",
                    url = "${CloudConfig.AUTH_BASE}/v1/user/signout",
                    body = JSONObject(),
                    // 显式带当前令牌、且不让 send 再走一遍 validToken()：
                    // 退出这一刻用的就是手里这个令牌，刷新它没有意义
                    // （刷新成功反而说明旧令牌还能用，与服务端「本次登出」的语义不符）。
                    extraHeaders = mapOf("Authorization" to "Bearer ${current.accessToken}"),
                    requireAuth = false
                )
            }
        }
        clearPersisted()
        _session.value = null
    }

    /** 是否已登录（同步属性，供非挂起上下文快速判断）。 */
    val isLoggedIn: Boolean get() = _session.value != null

    /**
     * 把磁盘里的会话读进内存（进程内只真正执行一次，见 [restoreOnce]）。
     *
     * 存在的意义：[isLoggedIn] / [session] 在进程刚启动时都是"未登录"的**假值**——
     * 会话还在磁盘上没被读进来。启动链路（`MoreCheckApp.onCreate` 与登录门闸）
     * 调一次本方法，之后所有同步属性才是真值。已登录用户因此不会在开屏阶段
     * 被误判成"未登录"而弹登录引导。
     */
    suspend fun restore() {
        restoreOnce()
    }

    // ---- 内部 ---------------------------------------------------------------

    /** 把磁盘里的会话读进内存。整个进程只做一次（[restoreMutex] 保证）。 */
    private suspend fun restoreOnce() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return@withLock
            _session.value = readPersisted()
            restored = true
        }
    }

    private suspend fun postRefresh(refreshToken: String, keepPhone: String?): CloudSession {
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/token",
            body = JSONObject().apply {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
            },
            requireAuth = false
        )
        return parseSession(response.asJsonObject(), fallbackPhone = keepPhone)
    }

    /**
     * 解析登录 / 刷新响应为会话。
     *
     * 字段与服务端契约一致：`access_token` / `refresh_token` /
     * `expires_in`（**秒**）/ `sub`（用户 ID）。`expires_at` 由客户端
     * 用本地时钟换算成 epoch 毫秒，与 Room 的时间戳同基。
     */
    private fun parseSession(json: JSONObject, fallbackPhone: String?): CloudSession {
        val accessToken = json.optString("access_token", "")
        if (accessToken.isEmpty()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回访问令牌，请稍后重试",
                    code = null,
                    status = 200
                )
            )
        }
        val expiresIn = json.optLong("expires_in", 0L)
        return CloudSession(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token", ""),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            userId = json.optString("sub", ""),
            phone = fallbackPhone
        )
    }

    private suspend fun persist(session: CloudSession) {
        context.cloudSessionStore.edit { prefs ->
            prefs[SessionKeys.JSON] = JSONObject().apply {
                put("accessToken", session.accessToken)
                put("refreshToken", session.refreshToken)
                put("expiresAt", session.expiresAt)
                put("userId", session.userId)
                put("phone", session.phone ?: JSONObject.NULL)
            }.toString()
        }
    }

    private suspend fun clearPersisted() {
        context.cloudSessionStore.edit { it.remove(SessionKeys.JSON) }
    }

    private suspend fun readPersisted(): CloudSession? {
        val raw = context.cloudSessionStore.data.first()[SessionKeys.JSON] ?: return null
        return runCatching {
            val json = JSONObject(raw)
            CloudSession(
                accessToken = json.getString("accessToken"),
                refreshToken = json.getString("refreshToken"),
                expiresAt = json.getLong("expiresAt"),
                userId = json.optString("userId", ""),
                phone = json.optString("phone", "").ifBlank { null }
            )
        }.getOrNull()
    }

    companion object {
        /** 大陆手机号：11 位、1 开头。 */
        private val MAINLAND_PHONE = Regex("^1\\d{10}$")

        /**
         * 校验大陆手机号；不合法抛 [CloudError.Kind.INVALID_REQUEST]。
         *
         * 错误消息与登录页输入框下方的提示保持同一句话，用户在两处看到一致的说法。
         */
        fun requireMainlandPhone(phone: String) {
            if (!MAINLAND_PHONE.matches(phone.trim())) {
                cloudFail(
                    CloudError(
                        kind = CloudError.Kind.INVALID_REQUEST,
                        message = "请输入正确的 11 位手机号",
                        code = null,
                        status = 0
                    )
                )
            }
        }

        /**
         * 规范化手机号（与官方 SDK 的 `normalizePhone` 逐分支一致）。
         *
         * 大陆 11 位号 → `+86 <号>`；其它形式（已带区号等）原样返回。
         * 应用代码**不要**自己提前拼 `+86`——这里统一做，避免两处各拼一次
         * 拼出 `+86+86…`。
         */
        fun normalizePhone(raw: String): String {
            var digits = raw.replace(Regex("[^0-9]"), "")
            if (digits.startsWith("0086")) {
                digits = digits.substring(4)
            } else if (digits.startsWith("86") && digits.length == 13) {
                digits = digits.substring(2)
            }
            return if (MAINLAND_PHONE.matches(digits)) "+86 $digits" else raw
        }
    }
}
