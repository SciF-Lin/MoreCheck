package com.morecheck.data.net

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * 全应用**唯一**的 HTTP 客户端（2026-10-27 接入 OkHttp 时新增）。
 *
 * ## 为什么集中在一个 object 里
 *
 * 接入前每个数据源各写一份 `HttpURLConnection`（热搜、腾讯热榜、GitHub、天气、云端），
 * 于是"超时多少秒""要不要带 UA""失败怎么判"散在五处，改一处漏四处。
 * 集中到此后：连接池与线程池只有一份（Coil 的网络抓取也复用这个 client），
 * 超时口径一致，特殊头通过 [getText] 的 `headers` 传，不再各写一遍。
 *
 * ## 超时为什么是 10 秒
 *
 * 与改造前 `HotSearchScreen.httpGetText` 的 10s 保持一致——那是实测过的值：
 * 腾讯热榜在弱网下要 4~6 秒才回第一个字节，5 秒会误判失败；而 10 秒以上
 * 用户已经开始怀疑点坏了。**不要为了"看起来更快"把它调小**。
 *
 * ## 为什么要禁掉自动重试以外的花活
 *
 * - 不装日志拦截器：release 包里不该有请求日志，debug 排查用 Android Studio 的
 *   Network Inspector 更直接；
 * - 不设 `retryOnConnectionFailure(true)` 之外的策略：GET 天然幂等，默认的重试
 *   已经够用药，再加应用层重试只会把"接口挂了"变成"卡很久才报错"。
 *
 * ## 这个 client 不做的事
 *
 * 不做 JSON 解析、不做鉴权、不做缓存。它只是一层"把 URL 变成字符串"的传输层，
 * 上层各自用 `org.json` 解析（项目既有做法，不为了接 OkHttp 顺手换成别的序列化库）。
 */
object AppHttp {

    /**
     * 共享 client。
     *
     * `lazy` + 默认线程安全：首次调用时创建，之后全进程复用（OkHttp 的连接池
     * 与 Dispatcher 都挂在 client 上，**每处 new 一个会让连接池各管各的**，
     * 等于白引这个库）。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 同步 GET，返回响应体文本（UTF-8）。
     *
     * ## 为什么是**同步**函数
     *
     * 它替换的是原来的 `HttpURLConnection` 同步实现，5 个调用点都在
     * `withContext(Dispatchers.IO)` 里调它。保持同步签名 = 调用点一行都不用改，
     * 也就不会把"在哪个线程上跑"这件事顺手改坏。
     *
     * @param headers 额外请求头。GitHub 必须给 `User-Agent`（缺了直接 403），
     *   云服务必须给 `Origin`（否则 signin 报 401）——这些由调用方按需传，
     *   本函数不预设任何业务头。
     * @throws IOException 网络层失败（连不上、超时）。
     * @throws IllegalStateException 状态码非 2xx，message 形如 `HTTP 403`。
     *   ⚠️ **保留这个格式**：上层（如 `fetchTrending`）靠 `message.contains("403")`
     *   分辨"限流"还是"接口挂了"，改成别的措辞会静默丢掉那几条友好提示。
     */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                // 默认 Accept：绝大多数接口返回 JSON。调用方仍可覆盖。
                header("Accept", "application/json")
                headers.forEach { (key, value) -> header(key, value) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return body
        }
    }

    /**
     * 长耗时抓取专用的 client（2026-11-10 新增，为「域名比价」）。
     *
     * 为什么不能用默认的 10 秒：域名比价抓的是注册商**官网整页**
     * （Name.com 76KB、阿里云 128KB、西部数码 182KB、Gandi 239KB、
     * Dynadot/Porkbun 600~800KB），跨境线路下首字节就要 2~4 秒，
     * 10 秒会把"慢"误判成"抓不到"，得出"该厂商无报价"的错误结论。
     *
     * 复用同一个 [client]（`newBuilder` 派生的实例共享连接池与线程池），
     * 因此这里只是换了一组超时，不是另起一套 HTTP 栈。
     */
    private val slowClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(SLOW_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 同步 GET，返回**原始字节**（编码由调用方决定）。
     *
     * 存在的唯一理由：西部数码的价格总览是 **GB18030** 页面，按 UTF-8 解码
     * 会把中文变成替换符（价格是 ASCII，但 GBK 的双字节尾字节可能落在
     * `0x5C`/`0x27` 上，进而破坏 JSON 字符串的转义边界）。
     *
     * @throws IOException 网络层失败。
     * @throws IllegalStateException 状态码非 2xx，message 形如 `HTTP 403`。
     */
    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = SLOW_TIMEOUT_SECONDS
    ): ByteArray = withResponse(
        Request.Builder().url(url).get().applyHeaders(headers).build(),
        timeoutSeconds
    ) { it.body?.bytes() ?: ByteArray(0) }

    /**
     * 同步 POST 一个 JSON 请求体，返回响应文本（UTF-8）。
     *
     * 用于 Porkbun 的公开价目表接口（`api.porkbun.com/api/json/v3/pricing/get`，
     * 只接受 POST，body 传 `{}` 即可，无需鉴权）。
     */
    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = SLOW_TIMEOUT_SECONDS
    ): String {
        val body = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(body).applyHeaders(headers).build()
        return withResponse(request, timeoutSeconds) { it.body?.string().orEmpty() }
    }

    /**
     * 只取状态码的 GET。
     *
     * RDAP 的 **404 是一个正常答案**（注册局无此记录 = 域名可能可注册），
     * 走 [getText] 会被 `check(isSuccessful)` 抛成异常，调用方再靠字符串
     * 匹配 "404" 判断，等于把状态码编码进错误文案。这里直接把码交出去。
     *
     * 网络层失败仍抛 [IOException]（那是真的"查不到"）。
     */
    fun getStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = READ_TIMEOUT_SECONDS
    ): Int = withResponse(
        Request.Builder().url(url).get().applyHeaders(headers).build(),
        timeoutSeconds,
        requireSuccess = false
    ) { it.code }

    /**
     * 打开连接 → 交给 [read] 把要的东西取出来 → **在同一个 `use` 块里**关闭。
     *
     * 顺序很要紧：先 close 再读 `body` 会拿到 "closed" 异常，
     * 所以取值必须发生在 `use` 内部，不能把 [Response] 交出去。
     */
    private fun <T> withResponse(
        request: Request,
        timeoutSeconds: Long,
        requireSuccess: Boolean = true,
        read: (Response) -> T
    ): T {
        val target = if (timeoutSeconds > READ_TIMEOUT_SECONDS) slowClient else client
        return target.newCall(request).execute().use { response ->
            if (requireSuccess) check(response.isSuccessful) { "HTTP ${response.code}" }
            read(response)
        }
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        header("Accept", "application/json")
        headers.forEach { (key, value) -> header(key, value) }
        return this
    }

    /** 连接超时（秒）：弱网下建连慢，5 秒会误判。 */
    private const val CONNECT_TIMEOUT_SECONDS = 10L

    /** 读写超时（秒）：与改造前的 `readTimeout` 同值（见类 KDoc）。 */
    private const val READ_TIMEOUT_SECONDS = 10L

    /** 抓整页官网用的读超时（秒）：跨境大页面实测 4~8 秒，留足余量。 */
    private const val SLOW_TIMEOUT_SECONDS = 30L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
