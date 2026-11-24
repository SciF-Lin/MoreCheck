package com.morecheck.data.net

import android.util.Log
import com.morecheck.BuildConfig
import com.morecheck.data.music.KugouAccount
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * **登录态**下的酷狗取链（会员 / 高音质曲目）。
 *
 * ## 三步链路（复刻 `MakcRe/KuGouMusicApi` 的 song_auth → song_url_auth）
 *
 * ```
 * token ──user_verify──► auth ──authorization──► (auth, open_time) ──tracker/v5/url──► 直链
 * ```
 *
 * 1. `/v1/user_verify?module_id=51`：拿账号的"曲库授权码" `auth`（可缓存，短期有效）；
 * 2. `/v1/authorization?authorization=<auth>&hash=...`：针对**这一首歌**换一次性凭证
 *    `auth` + `open_time`；
 * 3. `/tracker/v5/url?...&auth=...&open_time=...`：换到真正的音频地址。
 *
 * ## 为什么全部走 gateway.kugou.com
 *
 * 上游注释写得很清楚：直连 `trackercdn*.kugou.com` 会绕过网关，而签名校验、CDN 调度、
 * 风控都在网关侧；网关靠 `x-router` 头决定"要哪个后端的服务"。所以 URL 一律是
 * `https://gateway.kugou.com/<path>`，后端由 `x-router` 指定。
 *
 * ## 失败时的行为
 *
 * 任何一步拿不到都返回 null，**不抛异常**——上层会回落到公开接口（免费曲目仍可播），
 * 再失败才提示"需要会员"。也就是说：登录态是**增强**，不是前置条件。
 */
object KugouAuthedApi {

    private const val GATEWAY = "https://gateway.kugou.com"

    /** 曲库服务的路由名（取 auth 与换直链都走它）。 */
    private const val TRACKER_ROUTER = "trackercdngz.kugou.com"

    /**
     * 用登录态取一首歌的播放地址。取不到返回 null（调用方回落）。
     *
     * @param hash 酷狗的音频 hash
     * @param albumAudioId 搜索接口返回的 `album_audio_id`（没有就传 0，部分歌会因此取不到）
     */
    suspend fun playUrl(
        hash: String,
        albumAudioId: Long,
        account: KugouAccount
    ): String? = withContext(Dispatchers.IO) {
        if (hash.isBlank() || account.token.isBlank()) return@withContext null

        // 第一跳：账号授权码。已有缓存就直接用（省一次往返）。
        val accountAuth = account.auth.takeIf { it.isNotBlank() }
            ?: runCatching { userVerify(account) }.getOrNull()
            ?: return@withContext null

        // 第二跳：针对当前这首歌的授权（auth + open_time）。
        val grant = runCatching { authorization(hash, albumAudioId, accountAuth, account) }
            .getOrNull() ?: return@withContext null
        val songAuth = grant.first
        val openTime = grant.second
        if (songAuth.isBlank()) return@withContext null

        // 第三跳：换直链。
        runCatching { trackerUrl(hash, albumAudioId, songAuth, openTime, account) }.getOrNull()
    }

    /**
     * 换账号授权码。成功时**顺手回写**到账号存储（由调用方决定是否持久化，
     * 这里只返回，不做 IO——[KugouAuthedApi] 保持无副作用）。
     */
    suspend fun userVerify(account: KugouAccount): String? = withContext(Dispatchers.IO) {
        val json = gatewayGet(
            path = "/v1/user_verify",
            router = TRACKER_ROUTER,
            business = linkedMapOf("module_id" to "51"),
            clientver = KugouSign.CLIENTVER,
            account = account
        ) ?: return@withContext null
        val auth = json.optJSONObject("data")?.optString("auth").orEmpty()
        // 只看"有没有拿到"，**不打印 auth 本身**（它等同短期授权凭证）。
        dbg("user_verify status=${json.optInt("status")} auth=${if (auth.isBlank()) "空" else "已获取"}")
        auth.takeIf { it.isNotBlank() }
    }

    /** 第二跳：`/v1/authorization` → (auth, open_time)。 */
    private fun authorization(
        hash: String,
        albumAudioId: Long,
        accountAuth: String,
        account: KugouAccount
    ): Pair<String, String>? {
        val json = gatewayGet(
            path = "/v1/authorization",
            router = TRACKER_ROUTER,
            business = linkedMapOf(
                "authorization" to accountAuth,
                "module_id" to "51",
                "album_audio_id" to albumAudioId.toString(),
                "clientver" to KugouSign.TRACKER_CLIENTVER,
                "hash" to hash.lowercase()
            ),
            clientver = KugouSign.CLIENTVER,
            account = account
        ) ?: return null
        val data = json.optJSONObject("data")
        if (data == null) {
            dbg("authorization 无 data：status=${json.optInt("status")} code=${json.optInt("error_code")}")
            return null
        }
        val songAuth = data.optString("auth")
        dbg("authorization status=${json.optInt("status")} auth=${if (songAuth.isBlank()) "空" else "有"}")
        return songAuth to data.optString("open_time", "0")
    }

    /** 第三跳：`/tracker/v5/url`。返回体里 `url` 可能是字符串，也可能是数组。 */
    private fun trackerUrl(
        hash: String,
        albumAudioId: Long,
        songAuth: String,
        openTime: String,
        account: KugouAccount
    ): String? {
        val business = linkedMapOf(
            "album_id" to "0",
            "area_code" to "1",
            "module" to "",
            "hash" to hash.lowercase(),
            "need_m" to "0",
            "ssa_flag" to "is_fromtrack",
            "version" to "11430",
            "open_time" to openTime,
            "ptype" to "0",
            "need_ogg" to "1",
            "page_id" to "151369488",
            "auth" to songAuth,
            "mtype" to "0",
            "quality" to "128",
            "album_audio_id" to albumAudioId.toString(),
            "behavior" to "play",
            "pid" to "2",
            "module_id" to "51",
            "cmd" to "26",
            "ppage_id" to "463467626,350369493,788954147",
            "clientver" to KugouSign.TRACKER_CLIENTVER,
            "pidversion" to "3001",
            "cdnBackup" to "1",
            // 少了这个 key，接口返回 `{"status":0,"error":"illegal key"}`。
            "key" to KugouSign.trackerKey(
                hash = hash,
                appid = KugouSign.APPID,
                mid = account.device.mid,
                userid = account.userid
            )
        )
        // /tracker/v5/url 在上游没有显式 x-router（路径已能路由到位）。
        // 这里两种都试一次：先按"不带路由"发，拿不到再补上路由头。
        val first = gatewayGet(
            path = "/tracker/v5/url",
            router = null,
            business = business,
            clientver = KugouSign.TRACKER_CLIENTVER,
            account = account
        )
        val url = first?.let { pickUrl(it) }
        if (url != null) return url

        val second = gatewayGet(
            path = "/tracker/v5/url",
            router = TRACKER_ROUTER,
            business = business,
            clientver = KugouSign.TRACKER_CLIENTVER,
            account = account
        )
        return second?.let { pickUrl(it) }
    }

    /** 从响应里挑出可播地址（`url` 字符串 / 数组 / `data.url` 三种写法都见过）。 */
    private fun pickUrl(json: JSONObject): String? {
        val data = json.optJSONObject("data")
        val candidates = listOfNotNull(
            json.optString("url").takeIf { it.startsWith("http") },
            data?.optString("url")?.takeIf { it.startsWith("http") },
            (json.opt("url") as? JSONArray)?.optString(0),
            (data?.opt("url") as? JSONArray)?.optString(0),
            // 主地址不可用时酷狗会给备份域名，同样要试。
            (json.opt("backupUrl") as? JSONArray)?.optString(0)
        )
        return candidates.firstOrNull { it.startsWith("http") }?.let(::forceHttps)
    }

    /**
     * 把音频直链从 http 升到 https。
     *
     * 酷狗返回的 CDN 地址是 `http://fsandroid.kugou.com/...`，而本 App 默认禁止明文流量，
     * ExoPlayer 会直接抛 `Cleartext HTTP traffic ... not permitted`。
     * 实测同一路径换成 https **返回同样的 206 与字节**，所以这里直接升级，
     * **不为了播放再开口子放行明文域名**。
     *
     * ⚠️ 如果哪天某个 CDN 不支持 https，表现是"这首歌播不了"——
     * 那时先在浏览器里试 https，不行再考虑把该域名加进 `network_security_config.xml`。
     */
    private fun forceHttps(url: String): String {
        if (!url.startsWith("http://")) return url
        return "https://" + url.removePrefix("http://")
    }

    /**
     * 查一次账号的会员状态（`kugouvip.kugou.com/v1/get_union_vip`）。
     *
     * ## 为什么需要它
     *
     * 扫码授权成功时返回体里**不一定带** `vip_type`（实测有时是 0 / 缺字段）。
     * 直接拿它当"你不是会员"会冤枉会员用户，所以另外查一次：
     * 查到了才更新界面，查不到就保持**中性文案**（不写"未检测到会员权益"）。
     *
     * 返回 null = 没查到（保持原值），不要当成"非会员"。
     */
    suspend fun fetchVipType(account: KugouAccount): Int? = withContext(Dispatchers.IO) {
        val json = requestJson(
            host = "https://kugouvip.kugou.com",
            path = "/v1/get_union_vip",
            router = null,
            business = linkedMapOf("busi_type" to "concept"),
            clientver = KugouSign.CLIENTVER,
            account = account
        ) ?: return@withContext null
        firstIntByKey(json, "vip_type")?.takeIf { it >= 0 }
    }

    /**
     * 只在 debug 包打链路日志。
     *
     * 取链失败时"哪一跳断了"从界面上看不出来（都表现为"无法播放"），
     * 而 release 包不该有请求日志，所以用 [BuildConfig.DEBUG] 夹住。
     * 过滤命令：`adb logcat -s KugouAuthedApi`。
     */
    private fun dbg(message: String) {
        if (BuildConfig.DEBUG) Log.d("KugouAuthedApi", message)
    }

    /** 在 JSON 里递查找第一个同名整数字段（各接口的层级不一样，只能这么找）。 */
    private fun firstIntByKey(json: JSONObject, key: String): Int? {
        if (json.has(key)) {
            val value = json.optInt(key, -1)
            if (value >= 0) return value
        }
        val names = json.keys()
        while (names.hasNext()) {
            val name = names.next()
            when (val child = json.opt(name)) {
                is JSONObject -> firstIntByKey(child, key)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until child.length()) {
                        val item = child.optJSONObject(index) ?: continue
                        firstIntByKey(item, key)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /**
     * 发一次网关请求（默认打 gateway.kugou.com）。
     *
     * 公共参数（`dfid/mid/uuid/appid/clientver/clienttime/signature`）与请求头
     * （`dfid/mid/clienttime`，必须与 query 一致）在这里统一拼，业务参数只管业务。
     */
    private fun gatewayGet(
        path: String,
        router: String?,
        business: LinkedHashMap<String, String>,
        clientver: String,
        account: KugouAccount
    ): JSONObject? = requestJson(
        host = GATEWAY,
        path = path,
        router = router,
        business = business,
        clientver = clientver,
        account = account
    )

    private fun requestJson(
        host: String,
        path: String,
        router: String?,
        business: LinkedHashMap<String, String>,
        clientver: String,
        account: KugouAccount
    ): JSONObject? {
        val device = account.device
        val clienttime = KugouSign.nowSeconds()
        val params = LinkedHashMap<String, String>()
        params.putAll(business)
        params["dfid"] = device.dfid
        params["mid"] = device.mid
        params["uuid"] = device.uuid
        params["appid"] = KugouSign.APPID
        params["clientver"] = clientver
        params["clienttime"] = clienttime.toString()
        // ⚠️ token / userid **必须同时作为 query 参数**，光放在 Cookie 里不够。
        // 少了它们所有带登录态的接口都返回 `35002 parameter incomplete`——
        // 这个错误读起来像"签名/参数写错了"，实际是缺身份。上游 request.js 里
        // 这两行是 `if (token) defaultParams['token'] = token` 这样的默认参数。
        params["token"] = account.token
        if (account.userid.isNotBlank() && account.userid != "0") {
            params["userid"] = account.userid
        }
        params["signature"] = KugouSign.android(params)

        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        val headers = linkedMapOf(
            "User-Agent" to KugouSign.ANDROID_UA,
            "dfid" to device.dfid,
            "mid" to device.mid,
            "clienttime" to clienttime.toString(),
            "kg-rc" to "1",
            "kg-thash" to "5d816a0",
            "kg-rec" to "1",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
            // 网关靠 Cookie 里的 token/userid 认人；少了它就会返回 35002 parameter incomplete。
            "Cookie" to "token=${account.token}; userid=${account.userid}; " +
                "dfid=${device.dfid}; mid=${device.mid}"
        )
        if (router != null) headers["x-router"] = router

        return runCatching {
            val text = AppHttp.getText("$host$path?$query", headers)
            JSONObject(text)
        }.getOrNull()
    }
}
