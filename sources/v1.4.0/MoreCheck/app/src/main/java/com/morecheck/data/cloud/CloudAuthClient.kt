package com.morecheck.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * 微信身份尚未关联手机号（v1.2.4 接缝）。
 *
 * 由 [CloudAuthClient.signInWithWechatCode] 按服务端错误码
 * `wechat_phone_not_linked` 抛出；界面捕获后进「绑定手机号」流程。
 * 见 [CloudAuthClient.signInWithWechatCode] 的 KDoc——票据透传是遗留接缝点。
 */
class WechatNotLinkedException(message: String) : Exception(message)

/**
 * 云端认证客户端：手机号验证码登录 / 注册、会话刷新、登出。
 *
 * ## ⭐ 账号的唯一主码是「手机号」（用户 2026-10-31 定下的约定）
 *
 * 一个云账号 = 一个手机号（服务端 `sub` / uid 只是内部句柄，界面上不给人看）。
 * **将来接入任何其它登录方式**（微信 / QQ / Apple / 邮箱 / 设备码 …）时，
 * 必须先判断该身份**是否已关联手机号**：
 * - 已关联 → 直接登录到那个手机号账号；
 * - 未关联 → 先走手机号绑定（收验证码），绑定成功才允许建会话。
 *
 * 不允许出现"没有手机号的账号"，也不允许一个自然人变成两份账号——
 * 云同步的水位线、备份目录（`users/<uid>/…`）全都按账号隔离，
 * 一旦分叉，用户会在两台设备上看到两套数据，且两边都"看起来正常"。
 * 这条约定在代码里的落点：本类只认 [OtpChallenge.phone]，会话里 `phone` 永远非空。
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

    /** 会话被服务端吊销（其它设备登录 / 令牌被刷）时置真；登录成功或主动登出后复位。 */
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    /** 过期落地动作，由 AppContainer 接到 [AuthRepository.markSessionExpired]（清界面登录态）。 */
    var onExpired: (() -> Unit)? = null

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
     * @param usage 用途标记；null = 普通登录 / 注册，「PASSWORD_RESET」=
     *   重置密码（找回密码页用，verify 后的令牌配套走 [resetPasswordByPhone]，
     *   服务端实测接受手机号的这个 usage，与官方 SDK 的 email 口径对齐）。
     * @throws CloudException 限流（429）时消息即服务端原文，界面照实展示。
     */
    suspend fun sendCode(phone: String, usage: String? = null): OtpChallenge {
        requireMainlandPhone(phone)
        val normalized = normalizePhone(phone)
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/verification",
            body = JSONObject().apply {
                put("phone_number", normalized)
                // target = ANY：服务端自行决定送达渠道（本项目只有短信）。
                put("target", "ANY")
                if (usage != null) put("usage", usage)
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
        _expired.value = false
        return session
    }

    /**
     * 只校验验证码是否正确，**不建立会话**（2026-11-01，锁屏「忘记密码」用）。
     *
     * ## 为什么不能用 [verifyCode]
     *
     * 那个方法验证码通过后会接着调 `signin` / `signup` 并**持久化会话**。
     * 而云端是**单会话制**（同一账号恒定只有一行会话，新登录覆盖旧行，见
     * `docs/pitfalls/06` 的 5.37）：为了解锁而登录，会把用户手机上其它设备全部踢下线。
     * 找回密码只需要一个"这个手机号确实是你的"的证据，不需要会话。
     *
     * ## 它做了什么、没做什么
     *
     * 只走第一步 `/v1/verification/verify` 拿一次性令牌，拿到就丢弃——
     * 令牌不落盘、不进任何状态，用完随对象回收。
     * **不 signin、不写 DataStore、不发 [session]。**
     *
     * @return 验证码正确且服务端返回了令牌 = true；其余一律 false。
     * @throws com.morecheck.data.cloud.CloudError 网络 / 服务端错误（与 [verifyCode] 同一套）。
     */
    suspend fun verifyCodeOnly(challenge: OtpChallenge, code: String): Boolean {
        if (code.isBlank()) return false
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/verification/verify",
            body = JSONObject().apply {
                put("verification_id", challenge.verificationId)
                put("verification_code", code)
            },
            requireAuth = false
        )
        return response.asJsonObject().optString("verification_token", "").isNotBlank()
    }

    // ---- 密码登录（2026-11-03：服务端契约实测支持） ---------------------------
    //
    // 与验证码登录**同一个端点** `/v1/signin`：请求体带 `password` 字段时服务端
    // 走密码校验（实测对未设密码的账号返回错误码 `password_not_set` /
    // error_code 4029）。`/v1/token` 网关只放行 refresh_token 授权，密码授权
    // 不在它上面（实测 401 invalid_grant）。
    //
    // 密码只是**同一个手机号账号**的另一种开门方式（主码约定不变）：登录成功
    // 拿到的会话与验证码登录完全同一套，云端数据不分叉。

    /**
     * 手机号 + 密码登录。
     *
     * @param phone 用户输入的 11 位手机号（不带 +86），方法内校验并规范化。
     * @param password 用户设置的登录密码（设置时要求 6–72 位，见 [updateUserPassword]）。
     * @return 登录后的会话（已持久化，[session] 同步发出）。
     * @throws CloudError 账号未设密码时错误码为 `password_not_set`——调用方
     *   可用 [isPasswordNotSetError] 识别，引导去「忘记密码」用验证码设置密码；
     *   密码错误时服务端返回 invalid_grant（与令牌失效同码），界面按「手机号或密码不正确」提示。
     */
    suspend fun signInWithPhonePassword(phone: String, password: String): CloudSession {
        requireMainlandPhone(phone)
        if (password.isBlank()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.INVALID_REQUEST,
                    message = "请输入密码",
                    code = null,
                    status = 0
                )
            )
        }
        val normalized = normalizePhone(phone)
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/signin",
            body = JSONObject().apply {
                put("username", normalized)
                put("password", password)
            },
            requireAuth = false
        )
        val session = parseSession(response.asJsonObject(), fallbackPhone = normalized)
        persist(session)
        _session.value = session
        _expired.value = false
        return session
    }

    /**
     * 修改**当前已登录**账号的密码（官方 SDK 的 `resetPasswordForOld` 同款两步）：
     *
     * 1. `POST /v1/user/sudo` `{password: 旧密码}`（带会话）→ 换一次性 `sudo_token`
     *    ——服务端强制用旧密码做「本人确认」，实测未设密码的账号传任何值都报
     *    `invalid_password`，所以**首次设密码不走这里**（走 [resetPasswordByPhone]）；
     * 2. `PATCH /v1/user/password` `{sudo_token, new_password}`。
     *
     * 修改成功当前会话保持有效（密码只影响下一次登录）。
     *
     * @throws CloudError 旧密码错误时错误码为 `invalid_password`——界面提示
     *   「旧密码不正确」，并告知从未设过密码的用户走「忘记密码」。
     */
    suspend fun updateUserPassword(oldPassword: String, newPassword: String) {
        if (newPassword.length < PASSWORD_MIN_LENGTH || newPassword.length > PASSWORD_MAX_LENGTH) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.INVALID_REQUEST,
                    message = "密码长度需在 $PASSWORD_MIN_LENGTH–$PASSWORD_MAX_LENGTH 位之间",
                    code = null,
                    status = 0
                )
            )
        }
        if (oldPassword.isBlank()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.INVALID_REQUEST,
                    message = "请输入旧密码",
                    code = null,
                    status = 0
                )
            )
        }
        val sudoResponse = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/user/sudo",
            body = JSONObject().put("password", oldPassword)
        )
        val sudoToken = sudoResponse.asJsonObject()
            .optString("sudo_token", "").ifBlank { null }
            ?: cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回身份确认凭据，请稍后重试",
                    code = null,
                    status = 200
                )
            )
        http.send(
            method = "PATCH",
            url = "${CloudConfig.AUTH_BASE}/v1/user/password",
            body = JSONObject().apply {
                put("sudo_token", sudoToken)
                put("new_password", newPassword)
            }
        )
    }

    /**
     * 重置密码（**不建会话**）：手机号验证码证明身份后直接设新密码。
     *
     * 服务端契约（2026-11-03 协议层实测）：发码必须带 `usage=PASSWORD_RESET`，
     * verify 换到的一次性令牌配套 `POST /v1/reset`（`phone_number` +
     * `new_password` + `verification_token`），成功返回空对象 200。
     *
     * ## 为什么不用「verifyCode 登录再改密」
     *
     * 最初实现走「verify 建会话 → PUT /v1/user 设密」，实测被网关拒绝
     * （标准 `PUT /v1/user` 404；改密码的唯一正路是 sudo 流程，而 sudo
     * 必须验证**旧密码**——未设过密码的账号无路可走）。`/v1/reset` 是
     * 唯一不需要旧密码的设密端点。
     *
     * @param verificationToken [verifyCodeOnly] 返回 true 对应的那次 verify
     *   换到的令牌——注意 [verifyCodeOnly] 目前不返回令牌本体，调用方改用
     *   [verifyForPasswordReset]。
     */
    suspend fun resetPasswordByPhone(
        phone: String,
        newPassword: String,
        verificationToken: String
    ) {
        if (newPassword.length < PASSWORD_MIN_LENGTH || newPassword.length > PASSWORD_MAX_LENGTH) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.INVALID_REQUEST,
                    message = "密码长度需在 $PASSWORD_MIN_LENGTH–$PASSWORD_MAX_LENGTH 位之间",
                    code = null,
                    status = 0
                )
            )
        }
        val normalized = normalizePhone(phone)
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/reset",
            body = JSONObject().apply {
                put("phone_number", normalized)
                put("new_password", newPassword)
                put("verification_token", verificationToken)
            },
            requireAuth = false
        )
        // 成功返回空对象；有内容说明契约变了，不静默吞掉。
        response // 200 即成功（非 2xx 已在 send 里抛出）
    }

    /**
     * 校验验证码并返回一次性令牌，**不建会话**——配套 [resetPasswordByPhone]。
     *
     * 与 [verifyCodeOnly] 的区别只在于返回值：那边只要 true/false（锁屏
     * 「忘记密码」的旧用途），找回密码页需要令牌本体去调 `/v1/reset`。
     * 两者都不 signin、不写 DataStore——验证身份不等于登录。
     */
    suspend fun verifyForPasswordReset(challenge: OtpChallenge, code: String): String {
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.AUTH_BASE}/v1/verification/verify",
            body = JSONObject().apply {
                put("verification_id", challenge.verificationId)
                put("verification_code", code)
            },
            requireAuth = false
        )
        return response.asJsonObject()
            .optString("verification_token", "").ifBlank { null }
            ?: cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回验证令牌，请稍后重试",
                    code = null,
                    status = 200
                )
            )
    }

    // ---- 微信登录（v1.2.4 接缝：客户端先行，服务端未实现） --------------------
    //
    // ⭐ 账号主码约定的微信分支：微信授权拿到 code 后走 [signInWithWechatCode]；
    // 服务端发现该微信身份没有关联手机号时（约定错误码 `wechat_phone_not_linked`），
    // 抛 [WechatNotLinkedException]，界面据此进 [bindWechatPhone] 的绑定流程。
    // 两条路径的终点都是**同一个手机号账号**的会话——绝不允许凭空多出一份
    // 没有手机号的账号（见本类头部的主码说明）。
    //
    // 端点与请求体的草案写在 `CloudConfig.WECHAT_SIGNIN_PATH` / `WECHAT_BIND_PHONE_PATH`
    // 的注释里。**服务端落地前这两个方法必然失败**（404 → NOT_FOUND），
    // 界面把这类失败如实转成「微信登录暂未开通」，不重试、不假装成功。

    /**
     * 用微信授权 code 换云会话（接缝）。
     *
     * @param wechatCode 微信 OpenSDK 授权回调返回的 code。AppID 未配置时
     *   客户端根本拿不到 code，调用方应先过 [WeChatAuthGateway.status]。
     * @return 已关联手机号时：登录后的会话（已持久化）。
     * @throws WechatNotLinkedException 该微信身份未关联手机号——界面应进绑定流程。
     *   ⚠️ 当前 [CloudError] 只携带 code/message/status，一次性登录票据
     *   （`login_ticket`）随错误体下发后无处安放；后端定稿时需要给
     *   `CloudHttp.parseError` 增加错误体 JSON 的透传（接缝点），届时本异常
     *   再带上票据字段。在此之前走不到绑定流程的服务端路径。
     * @throws CloudException 其余失败（服务端未实现时即 404 → NOT_FOUND）。
     */
    suspend fun signInWithWechatCode(wechatCode: String): CloudSession {
        if (CloudConfig.WECHAT_APPID.isBlank()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.UNIMPLEMENTED,
                    message = "微信登录暂未开通",
                    code = "wechat_not_configured",
                    status = 0
                )
            )
        }
        val response = try {
            http.send(
                method = "POST",
                url = CloudConfig.WECHAT_SIGNIN_PATH,
                body = JSONObject().apply {
                    put("wechat_code", wechatCode)
                    put("appid", CloudConfig.WECHAT_APPID)
                },
                requireAuth = false
            )
        } catch (e: CloudException) {
            if (e.error.code == "wechat_phone_not_linked") {
                throw WechatNotLinkedException(e.error.message)
            }
            throw e
        }
        val session = parseSession(response.asJsonObject(), fallbackPhone = null)
        persist(session)
        _session.value = session
        _expired.value = false
        return session
    }

    /**
     * 把已通过短信验证的微信身份**绑定**到手机号账号并登录（接缝）。
     *
     * 验证码校验由服务端在本端点内完成（请求体同时带 `verification_id` 与
     * `verification_code`），客户端不提前消耗验证会话——绑定成功即建会话，
     * 绑定失败时验证会话仍可用于重试。
     *
     * @param loginTicket [signInWithWechatCode] 抛 [WechatNotLinkedException] 时
     *   服务端下发的一次性票据；调试预览（服务端未上线）传空串即可，
     *   反正提交必然以 NOT_FOUND 告终。
     * @throws CloudException 服务端未实现（404）、验证码错误、限流等，如实上抛。
     */
    suspend fun bindWechatPhone(
        loginTicket: String,
        challenge: OtpChallenge,
        code: String
    ): CloudSession {
        val response = http.send(
            method = "POST",
            url = CloudConfig.WECHAT_BIND_PHONE_PATH,
            body = JSONObject().apply {
                put("login_ticket", loginTicket)
                put("verification_id", challenge.verificationId)
                put("verification_code", code)
                put("phone_number", challenge.phone)
                put("appid", CloudConfig.WECHAT_APPID)
            },
            requireAuth = false
        )
        val session = parseSession(response.asJsonObject(), fallbackPhone = challenge.phone)
        persist(session)
        _session.value = session
        _expired.value = false
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
            phone = json.optStringOrNull("phone_number") ?: json.optStringOrNull("phone"),
            name = json.optStringOrNull("name"),
            avatarUrl = json.optStringOrNull("avatar_url")
        )
    }

    /**
     * 当前会话（必要时先从磁盘恢复一次）。给内嵌网页的桥用。
     *
     * 与 [session] 的区别：那条 StateFlow 在进程刚启动时是 `null`（磁盘还没读），
     * 桥取会话时不能拿这个"假未登录"，所以这里显式走一次 [restoreOnce]。
     */
    suspend fun currentSession(): CloudSession? {
        restoreOnce()
        return _session.value
    }

    /**
     * 把内嵌网页自己登录拿到的会话**采纳**为本应用的会话（2026-10-29 账号合并）。
     *
     * ## 为什么需要反向接收
     *
     * 账号统一后，打卡与今日待办是**同一个云应用的同一个账号**。用户在待办页
     * 里输验证码登录成功时，网页已经拿到了有效会话；如果原生不接过来，
     * 就会出现"待办显示已登录、打卡还显示未登录"，下次打开待办又要重新登录。
     *
     * ## 只认结构，不校验来源
     *
     * 传进来的 JSON 由本地 assets 里的页面产出（页面本身没有第三方脚本），
     * 这里只做**结构校验**（accessToken / refreshToken / expiresAt 齐备），
     * 不做服务端校验——令牌是否有效由下一次真实请求决定，无效时
     * [refreshToken] 会按既有逻辑清空会话并广播登出。
     *
     * @return 采纳成功（结构合法且已落盘）为 true；JSON 不合法返回 false，不动现有会话。
     */
    suspend fun adoptSession(json: String): Boolean {
        val parsed = runCatching {
            val obj = JSONObject(json)
            val access = obj.optString("accessToken", "")
            val refresh = obj.optString("refreshToken", "")
            val expiresAt = obj.optLong("expiresAt", 0L)
            if (access.isBlank() || refresh.isBlank() || expiresAt <= 0L) return false
            val user = obj.optJSONObject("user")
            val userId = user?.optString("id", "").orEmpty()
                .ifBlank { user?.optJSONObject("raw")?.optString("sub", "").orEmpty() }
            val phone = user?.optString("phone", "").orEmpty()
                .ifBlank { user?.optJSONObject("raw")?.optString("phone_number", "").orEmpty() }
                .ifBlank { null }
            CloudSession(
                accessToken = access,
                refreshToken = refresh,
                expiresAt = expiresAt,
                userId = userId,
                phone = phone
            )
        }.getOrElse { return false }
        persist(parsed)
        _session.value = parsed
        return true
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
        _expired.value = false
    }

    /**
     * 会话被服务端吊销（其它设备登录挤掉 / 令牌被服务端刷掉）时调用：
     * 清本地会话、置「登录过期」态并回调界面落地。
     *
     * 与 [logout] 的区别：这是**被动**失效——本地资料不再可信，界面要明确
     * 告诉用户"登录过期"而不是安静变回未登录。
     */
    private val expiredScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    fun markSessionExpired() {
        if (_session.value == null && !_expired.value) return
        _session.value = null
        expiredScope.launch { clearPersisted() }
        _expired.value = true
        onExpired?.invoke()
    }

    /** 是否已登录（同步属性，供非挂起上下文快速判断）。 */    val isLoggedIn: Boolean get() = _session.value != null

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
                phone = json.optStringOrNull("phone")
            )
        }.getOrNull()
    }

    companion object {
        /** 密码长度下限（GoTrue 默认约束，客户端提前拦截给中文提示）。 */
        const val PASSWORD_MIN_LENGTH = 6

        /** 密码长度上限（GoTrue 硬约束 72 字节）。 */
        const val PASSWORD_MAX_LENGTH = 72

        /**
         * 该错误是否为「账号还没设置过密码」（服务端错误码 `password_not_set`）。
         *
         * 密码登录页据此把用户引去「忘记密码」页——那里用验证码证明身份后
         * 可以直接设置新密码，比让用户回验证码登录更短。
         */
        fun isPasswordNotSetError(e: CloudException): Boolean =
            e.error.code == "password_not_set" || e.error.code == "4029"

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
         * 把"会话里的手机号"还原成 **11 位裸号**，无法识别时返回 null。
         *
         * ## 为什么需要它（2026-11-03 锁屏「忘记密码」修复）
         *
         * [CloudSession.phone] 存的是 [normalizePhone] 的产物——`+86 13800138000`
         * （带区号与空格）。这个格式**只适合展示与再次规范化**，直接喂给
         * [requireMainlandPhone] 会被判成"不是 11 位手机号"：
         * 原锁屏短信找回路径就把会话里的号码原样送进 [sendCode]，
         * 于是"已登录账号也发不出验证码"（用户可见现象 = 点发码立刻报
         * 「请输入正确的 11 位手机号」）。
         *
         * 界面要预填输入框（只要 11 位数字）时统一走这里，避免各处自己
         * `replace(Regex("[^0-9]"))` 时漏掉 `+86` 前缀的两种写法
         * （`0086...` 与 `86...`，见 [normalizePhone] 的分支）。
         *
         * ⚠️ 这是**新增的内部工具**，不改变 [sendCode] / [requireMainlandPhone]
         * 等既有公共方法的契约：发码仍然要求调用方传 11 位裸号，
         * 规范化责任留在调用方（本次修的就是调用方漏了这一步）。
         * 声明为 `internal`：仅本模块（界面层）可用，不扩大对外 API。
         */
        internal fun phoneDigits(raw: String): String? {
            var digits = raw.replace(Regex("[^0-9]"), "")
            if (digits.startsWith("0086")) {
                digits = digits.substring(4)
            } else if (digits.startsWith("86") && digits.length == 13) {
                digits = digits.substring(2)
            }
            return digits.takeIf { MAINLAND_PHONE.matches(it) }
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

/**
 * `optString` 的安全版：**键不存在 / 值为 JSON null** 一律当作 `null` 返回。
 *
 * ⚠️ 踩过的坑（2026-10-31）：Android 的 `org.json` 里 `optString(name, fallback)`
 * 对 `JSONObject.NULL` **不会**回落到 fallback——它内部走的是
 * `JSON.toString(value)`，而那个函数把 `JSONObject.NULL` 转成字符串 `"null"`。
 * 于是"手机号为空"照样通过了 `ifBlank` 判空，同步管理页的「云账号」卡直接显示
 * 一个 `null`；`me()` 里的昵称 / 头像同理。凡是从服务端 JSON 取"可空字符串"，
 * 都应该走这里而不是 `optString(...).ifBlank { null }`。
 */
private fun JSONObject.optStringOrNull(key: String): String? =
    optString(key, "").takeIf { it.isNotBlank() && it != "null" }
