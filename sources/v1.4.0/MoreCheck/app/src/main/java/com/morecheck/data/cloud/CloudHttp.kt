package com.morecheck.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * 会话提供者。
 *
 * [CloudHttp] 只认识这个接口，不关心会话存在哪（DataStore / 内存）：
 * 认证客户端实现它，数据库 / 存储客户端共用同一个 [CloudHttp]，
 * 于是「登录后自动带上令牌、401 自动刷新重试」对三个模块同时成立，
 * 且只有一份实现。
 */
interface SessionProvider {

    /**
     * 取一个**保证可用**的访问令牌。
     *
     * 未登录返回 null；临近过期（不足 [CloudConfig.REFRESH_WINDOW_MS]）时
     * 内部会先刷新再返回。调用方（数据 / 存储请求）拿不到 null——
     * 见 [CloudHttp.send] 的门闸。
     */
    suspend fun validToken(): String?

    /**
     * 强制刷新（收到 401 后的重试路径）。
     *
     * 刷新成功返回新令牌；刷新令牌本身已失效（服务端明确拒绝）时返回 null，
     * 此时上层应把用户登出、引导重新登录，而不是无限重试。
     * 网络类失败会**抛出** [CloudException]（会话保留，调用方以网络错误失败）。
     */
    suspend fun refreshToken(): String?
}

/**
 * 所有云端 HTTP 请求的唯一出口。
 *
 * ## 为什么不用 OkHttp / Retrofit
 *
 * 与 `WeatherRepository` 同一条项目纪律：平台自带的 `HttpURLConnection` +
 * `org.json` 在 android.jar 里，零新增依赖。本客户端只有十余个端点、
 * 全是小 JSON（最大是整包备份），引入一个网络库的收益抵不上依赖成本。
 *
 * ## 三个模块共用一个实例
 *
 * Auth / Database / Storage 的请求头规则完全一致（见下方 [send] 的说明），
 * 因此由本类统一注入，各模块客户端只关心 URL 与 body。
 *
 * ## 三种 body 形态
 *
 * - [send]：JSON（对象或数组）进、字符串出——认证与数据库端点；
 * - [sendBytes]：裸字节进（存储上传）、字符串出（响应是 JSON）；
 * - [fetchBytes]：无 body、裸字节出（存储下载，图片可能是二进制）。
 */
class CloudHttp(private val sessions: SessionProvider) {

    /**
     * 会话彻底失效（401 且刷新也失败）时的回调，由 AppContainer 接到
     * `CloudAuthClient.markSessionExpired()`：清本地会话与界面登录态，
     * 让「我的信息 / 我的」页进入「登录过期」展示，而不是留着假登录态。
     */
    var onUnauthenticated: (() -> Unit)? = null


    /**
     * 发一个 JSON 请求并返回响应体文本。
     *
     * ## 请求头规则（与云端数据平面的约定一致）
     *
     * - `x-wb-webapp-access-key`：应用标识，**每个请求都带**（未登录的探测请求
     *   也是这个头，服务端据此识别是哪个应用的数据平面）；
     * - `Content-Type: application/json`：有 body 时带；
     * - `Authorization: Bearer <token>`：[requireAuth] 为 true 时带。
     *   未登录时**直接抛** [CloudError.Kind.UNAUTHENTICATED]，不发请求——
     *   受保护的数据必须要有真实身份，这是服务端行级安全的前提，
     *   发一个注定被拒的请求只会浪费用户流量。
     *
     * ## body 的类型
     *
     * [JSONObject]（登录 / 更新这类单对象）或 [JSONArray]（PostgREST 的
     * 批量插入 / upsert 要求裸数组）。两者 `toString()` 都是合法 JSON。
     *
     * ## 401 自动刷新重试
     *
     * 令牌可能在「刚检查完有效」与「请求到达服务端」之间过期（时钟漂移、
     * 服务端提前失效）。此时服务端返回 401，本方法用 [SessionProvider.refreshToken]
     * 刷新后**重试一次**；重试仍 401 才抛 [CloudError.Kind.UNAUTHENTICATED]。
     * 刷新本身是单飞的（见 `CloudAuthClient` 的 mutex），并发请求不会各刷一次。
     *
     * @param requireAuth false 仅用于登录 / 验证码 / 刷新令牌这类
     *   「还没有会话」或「正在换会话」的端点。
     */
    suspend fun send(
        method: String,
        url: String,
        body: Any? = null,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        requireAuth: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        require(body == null || body is JSONObject || body is JSONArray) {
            "body 只能是 JSONObject / JSONArray / null"
        }
        val payload = body?.toString()?.toByteArray(Charsets.UTF_8)
        withRetry(requireAuth) { bearer ->
            execute(
                method = method,
                url = buildUrl(url, query),
                payload = payload,
                contentType = if (body == null) null else "application/json",
                bearer = bearer,
                extraHeaders = extraHeaders
            )
        }.asText()
    }

    /**
     * 上传裸字节（存储模块的整包备份 / 记事本图片）。
     *
     * 与 [send] 的区别只在 body：这里是调用方给好的字节与 MIME 类型，
     * 响应体仍是 JSON 文本（存储接口返回 `{Id, Key}`）。
     */
    suspend fun sendBytes(
        method: String,
        url: String,
        bytes: ByteArray,
        contentType: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        requireAuth: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        withRetry(requireAuth) { bearer ->
            execute(
                method = method,
                url = buildUrl(url, query),
                payload = bytes,
                contentType = contentType,
                bearer = bearer,
                extraHeaders = extraHeaders
            )
        }.asText()
    }

    /**
     * 下载为裸字节（存储模块取回备份 JSON / 图片）。
     *
     * 刻意不走 [send] 的文本通道：图片是二进制，按 UTF-8 解码再编码会损坏。
     */
    suspend fun fetchBytes(
        method: String,
        url: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        requireAuth: Boolean = true
    ): ByteArray = withContext(Dispatchers.IO) {
        withRetry(requireAuth) { bearer ->
            execute(
                method = method,
                url = buildUrl(url, query),
                payload = null,
                contentType = null,
                bearer = bearer,
                extraHeaders = extraHeaders
            )
        }
        // 状态码检查统一在 withRetry 里做（返回即 2xx），这里不再重复判一遍。
        .bytes
    }

    /**
     * 带**真实字节进度**的下载（2026-11-10 需求 4）。
     *
     * 原先 [fetchBytes] 一次性 `readBytes()` 才返回，调用方只能在"开始"与"结束"
     * 各回调一次，界面进度条必然从 0 直接跳到 100。这里按 16KB 分块读，
     * 每读一块就回调累计字节数，让按钮上的进度条能连贯推进。
     *
     * ⚠️ 回调发生在 IO 线程、且**只统计 2xx 响应体**：401 刷新重试那一次的错误体
     * 不算进进度，避免进度条先跑一段再退回 0。调用方自己负责切回主线程更新 UI。
     *
     * @param onBytesRead 累计已读字节数；不传等于不带进度的 [fetchBytes]。
     */
    suspend fun fetchBytesWithProgress(
        method: String,
        url: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
        requireAuth: Boolean = true,
        onBytesRead: (Long) -> Unit
    ): ByteArray = withContext(Dispatchers.IO) {
        withRetry(requireAuth) { bearer ->
            execute(
                method = method,
                url = buildUrl(url, query),
                payload = null,
                contentType = null,
                bearer = bearer,
                extraHeaders = extraHeaders,
                onBytesRead = onBytesRead
            )
        }.bytes
    }

    // ---- 内部：重试骨架 ------------------------------------------------------

    /**
     * 三个公开方法共用的「发请求 → 401 刷新重试一次」骨架。
     *
     * 重试逻辑（含刷新失败时的两种分支）是三段公共代码里最容易改漏的一段，
     * 只应存在一份。返回值固定是 [RawResponse]，要文本还是字节由调用方自己取
     * （[send] / [sendBytes] 取文本，[fetchBytes] 取字节）。
     *
     * ⚠️ [attempt] 必须是**最后一个参数**：三个调用点都写成
     * `withRetry(requireAuth) { bearer -> … }` 的尾随 lambda 形式，lambda
     * 只会绑到最后一个参数上。曾经它在中间、后面还跟一个带默认值的 `map`，
     * 于是 lambda 绑去了 `map`、`attempt` 报「没传参数」——报错信息还指向
     * 调用点第一行，肉眼很难看出是参数顺序问题。
     *
     * 保证：正常返回时状态码必在 2xx（否则已抛 [CloudException]）。
     */
    private suspend fun withRetry(
        requireAuth: Boolean,
        attempt: suspend (bearer: String?) -> RawResponse
    ): RawResponse {
        var token: String? = null
        if (requireAuth) {
            token = sessions.validToken()
            if (token.isNullOrEmpty()) {
                cloudFail(
                    CloudError(
                        kind = CloudError.Kind.UNAUTHENTICATED,
                        message = "请先登录后再使用云同步",
                        code = null,
                        status = 0
                    )
                )
            }
        }
        val first = attempt(token)
        if (first.code == HttpURLConnection.HTTP_UNAUTHORIZED && requireAuth) {
            val refreshed = sessions.refreshToken()
            if (refreshed.isNullOrEmpty()) {
                // 先置「登录过期」态（清会话 + 通知界面），再抛给调用方。
                onUnauthenticated?.invoke()
                cloudFail(
                    CloudError(
                        kind = CloudError.Kind.UNAUTHENTICATED,
                        message = "登录已过期，请重新登录",
                        code = null,
                        status = HttpURLConnection.HTTP_UNAUTHORIZED
                    )
                )
            }
            val retry = attempt(refreshed)
            // 刷新成功后重试仍失败：错误要按**重试**的响应报。曾经这里只判
            // 「是不是又是 401」，于是重试撞上 500 时，500 的错误体被当成
            // 成功响应返回——因为 first.code（401）不是 2xx 却进了上面的
            // if 分支，末尾的 2xx 检查被整段跳过。
            if (retry.code !in 200..299) cloudFail(mapError(retry))
            return retry
        }
        if (first.code !in 200..299) cloudFail(mapError(first))
        return first
    }

    /** 一次裸执行（不含重试逻辑）。 */
    private fun execute(
        method: String,
        url: String,
        payload: ByteArray?,
        contentType: String?,
        bearer: String?,
        extraHeaders: Map<String, String>,
        onBytesRead: ((Long) -> Unit)? = null
    ): RawResponse {
        // 用 URI.toURL() 而不是 URL(String) 构造器：后者在较新 JDK/Android 上已弃用
        // （与 WeatherRepository.readUrl 同一取值）。
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CloudConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = CloudConfig.READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            // 应用标识头：名字是云端数据平面的约定（不是常见的 apikey），
            // 已用未登录探测请求验证过服务端认这个头。
            connection.setRequestProperty("x-wb-webapp-access-key", CloudConfig.PUBLISHABLE_KEY)
            // 来源标识头：服务端把「会话属于哪个 client」绑定在请求来源上。
            // 浏览器里的官方 SDK 天然带 Origin 头，原生端 HttpURLConnection 不带，
            // 缺了它 signin 兑换会话时报「the session is invalid, expired or
            // issued for another client」（401）——实测带上应用自己的部署域
            // （与浏览器端一致的值）后，同一个请求即刻通过校验。
            connection.setRequestProperty("Origin", CloudConfig.ENDPOINT)
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType)
            }
            if (!bearer.isNullOrEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $bearer")
            }
            extraHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            if (payload != null) {
                // doOutput = true 会把方法隐式改成 POST；显式设回是防御性的
                // （PATCH / DELETE 也带 body）。
                connection.doOutput = true
                connection.outputStream.use { it.write(payload) }
            }

            val code = connection.responseCode
            // 先判状态码再读流：直接读 4xx/5xx 的 inputStream 会抛
            // FileNotFoundException，把服务端的错误说明全丢掉
            // （WeatherRepository 同一处踩过的坑）。
            val bytes = runCatching {
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                when {
                    stream == null -> ByteArray(0)
                    // 只有下载大文件（安装包）才传回调；普通 JSON / 图片仍走一次性读，
                    // 免得为每个请求都多一次缓冲拷贝。
                    onBytesRead == null || code !in 200..299 -> stream.use { it.readBytes() }
                    else -> stream.use { input ->
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            buffer.write(chunk, 0, read)
                            total += read
                            onBytesRead(total)
                        }
                        buffer.toByteArray()
                    }
                }
            }.getOrElse { ByteArray(0) }
            RawResponse(code, bytes)
        } catch (e: Throwable) {
            // 连接阶段的异常按网络错误分类，消息翻译成用户能看懂的一句
            // （与 WeatherRepository.networkReasonOf 同一组取舍）。
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.NETWORK,
                    message = when (e) {
                        is SocketTimeoutException -> "连接服务超时，请检查网络后重试"
                        is UnknownHostException -> "网络不可用，请检查网络连接后重试"
                        is IOException -> "网络异常，请检查网络后重试"
                        else -> "请求失败，请稍后重试"
                    },
                    code = null,
                    status = 0
                )
            )
        } finally {
            // 无论成功还是抛异常都要断开：HttpURLConnection 底层是连接池，
            // 异常路径上的连接会一直被挂着，反复同步容易耗尽。
            connection.disconnect()
        }
    }

    /** 把非 2xx 响应翻译成 [CloudError]。 */
    private fun mapError(response: RawResponse): CloudError {
        val text = response.asText()
        val payload = runCatching { JSONObject(text) }.getOrNull()
        // 服务端错误体字段名不统一（实测返回 error / error_code / error_description；
        // 官方 SDK 还兼容 message / msg），按优先级取第一个非空的。
        val code = payload?.optString("error", "")?.ifBlank { null }
            ?: payload?.optString("error_code", "")?.ifBlank { null }
        val message = payload?.optString("error_description", "")?.ifBlank { null }
            ?: payload?.optString("message", "")?.ifBlank { null }
            ?: payload?.optString("msg", "")?.ifBlank { null }
            ?: "服务返回错误（HTTP ${response.code}）"
        val kind = when {
            code == "invalid_grant" -> CloudError.Kind.UNAUTHENTICATED
            response.code == 400 -> CloudError.Kind.INVALID_REQUEST
            response.code == 401 -> CloudError.Kind.UNAUTHENTICATED
            response.code == 403 -> CloudError.Kind.PERMISSION_DENIED
            response.code == 404 -> CloudError.Kind.NOT_FOUND
            response.code == 429 -> CloudError.Kind.RATE_LIMITED
            response.code == 501 -> CloudError.Kind.UNIMPLEMENTED
            response.code >= 500 -> CloudError.Kind.BACKEND_UNAVAILABLE
            else -> CloudError.Kind.UNKNOWN
        }
        return CloudError(kind = kind, message = message, code = code, status = response.code)
    }

    /** 拼 URL：query 参数逐个编码（PostgREST 的过滤器值可能含逗号 / 空格 / 中文）。 */
    private fun buildUrl(base: String, query: Map<String, String>): String {
        if (query.isEmpty()) return base
        val joined = query.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        return if (base.contains('?')) "$base&$joined" else "$base?$joined"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private data class RawResponse(val code: Int, val bytes: ByteArray) {
        /** 按 UTF-8 解码响应体。错误路径上也用它取服务端的说明文本。 */
        fun asText(): String = String(bytes, Charsets.UTF_8)
    }
}

/** 把响应体按 JSON 对象解析；空体 / 非对象时抛 [CloudError.Kind.BACKEND_UNAVAILABLE]。 */
internal fun String.asJsonObject(): JSONObject =
    runCatching { JSONObject(this) }.getOrElse {
        cloudFail(
            CloudError(
                kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                message = "服务端返回了解析不了的数据",
                code = null,
                status = 200
            )
        )
    }

/** 把响应体按 JSON 数组解析；空体按空数组处理。 */
internal fun String.asJsonArray(): JSONArray {
    if (isBlank()) return JSONArray()
    return runCatching { JSONArray(this) }.getOrElse {
        // 有些端点在「0 行」时返回的是对象或空串，容错成空数组比抛错更符合调用方预期
        // （调用方本来就把「空列表」当作正常情况处理）。
        JSONArray()
    }
}

/** 取数组里每个元素作为 [JSONObject]（跳过非对象元素）。 */
internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
