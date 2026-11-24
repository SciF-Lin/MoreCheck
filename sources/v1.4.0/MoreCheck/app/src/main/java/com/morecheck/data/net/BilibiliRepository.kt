package com.morecheck.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「B站视频」的数据层（2026-11-03 原生改造新增）。
 *
 * ## 为什么单独抽一个 Repository
 *
 * 改造前 `ui/web/BilibiliScreen.kt` 里把「WBI 签名 + playurl 解析 + 下载链路」
 * 全塞在一个 785 行的 Composable 文件里：列表、播放、下载三种职责混在一起，
 * 想给列表加「热门/搜索」就必须复制签名函数。这里把**纯网络/解析**收口到一处，
 * UI 只拿强类型对象，签名算法只有一份。
 *
 * 所有 HTTP 一律走 [AppHttp]（全应用唯一 OkHttpClient），不新开连接池。
 *
 * ## 实测过的接口（2026-11-03，宿主机直连）
 *
 * | 用途 | 端点 | 免登录可用 |
 * | --- | --- | --- |
 * | 日常推荐 | `x/web-interface/wbi/index/top/feed/rcmd`（WBI 签名） | ✅ 12 条/次，`fresh_idx` 递增可换一批 |
 * | 推荐兜底 | `x/web-interface/index/top/rcmd` | ✅ 但不带 WBI，连续刷会 `-412` 限流 |
 * | 热门榜单 | `x/web-interface/popular` | ✅ `pn` 分页、`no_more` 表示到底 |
 * | 搜索 | `x/web-interface/wbi/search/type`（WBI 签名） | ✅ `search_type=video&keyword=` |
 * | 视频详情 | `x/web-interface/view` | ✅ 标题/简介/UP/播放数/分 P 列表 |
 * | 播放地址（单文件） | `x/player/playurl?platform=html5` | ✅ 只回 720P/360P 两档，`durl[0]` 已带音轨 |
 * | 播放地址（DASH） | `x/player/wbi/playurl?fnval=16` | ✅ 但 `dash.video` 实际只有 480P/360P 两档 |
 * | 扫码登录 | `passport.bilibili.com/x/passport-login/web/qrcode/generate` + `…/poll` | ✅ 生成/轮询均可（未实装，见文末「登录研究」） |
 *
 * ## 为什么清晰度必须「以实际返回为准」
 *
 * 这是本轮**最容易写错**的一处。`playurl` 的 `accept_quality` 只表示
 * 「这个视频理论上有的清晰度」（实测未登录时回 `[120,112,80,64,32,16]`，
 * 即 4K/1080P+/1080P/720P/480P/360P 全都列出来），**不代表你拿得到**：
 * 未登录请求 `dash` 时，`dash.video` 实际只有 `id=32`（480P）与 `id=16`（360P）；
 * 而 html5 单文件接口反而能给 720P。所以本文件只把**真正出现在返回体里**的
 * quality id 列进可选档位（[BiliQuality]），绝不按 `accept_quality` 硬编档位。
 * 下载时若目标清晰度在重新请求时消失，直接抛 [BilibiliException] 明确提示，
 * **不会静默降级成低清晰度**（用户需求明确要求这一点）。
 *
 * ## 登录研究结论（不实装，只留接缝）
 *
 * 1. **扫码登录（官方 Web 端同一套）**
 *    - `GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header`
 *      → `data.url`（二维码内容，可直接用 ZXing 画）+ `data.qrcode_key`（轮询用）；
 *    - `GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=…&source=main-fe-header`
 *      → `data.code`：`86101` 未扫码 / `86090` 已扫码未确认 / `86038` 二维码失效 / `0` 成功；
 *      成功时 `data.url` 是 `https://passport.…/crossDomain?DedeUserID=…&SESSDATA=…&bili_jct=…`
 *      （三家 cookie 都在这串里），还有 `refresh_token`（可续期）；
 *    - 实测（2026-11-03）：generate 返回正常，立即 poll 得到 `86101 未扫码`——
 *      说明该链路对未登录客户端开放，**可行性成立**。
 * 2. **实装落点（将来）**：本仓库所有请求的 header 由 [biliHeaders] 统一生成，
 *    只需在这里注入 `Cookie: SESSDATA=…; bili_jct=…`，再配合 `CookieManager` 给
 *    WebView 播放页种 cookie，就能同时提升「DASH 清晰度上限」（登录后才有 1080P+）
 *    与「网页播放免二次登录」。UI 侧不用改。
 * 3. **风险（如实记录）**：
 *    - B 站服务端有风控（本次实测连续快速请求推荐接口会收到 `code=-412`），
 *      登录态下调用 `playurl` 也可能触发 `-352` 风控校验，需要 `w_rid` 之外的 `buvid` 等指纹；
 *    - 扫码登录拿到的是**用户账号凭证**，落到本应用本地意味着多一个失窃面
 *      （本应用无加密存储层，DataStore 明文），且 B 站单账号多端登录会让用户手机被踢；
 *    - B 站用户协议对「第三方客户端抓取播放地址」有约束，属于合规灰区；
 *    - 因此本轮**只做研究结论 + 代码接缝**，不实装登录，也不保存任何 cookie。
 */
object BilibiliRepository {

    // ---------------------------------------------------------------------
    // 常量
    // ---------------------------------------------------------------------

    /** 接口统一 UA：AppHttp 默认不带 UA，B 站接口缺 UA 会被拒。 */
    private const val BILI_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    private const val API_ROOT = "https://api.bilibili.com"

    private const val NAV_URL = "$API_ROOT/x/web-interface/nav"
    private const val FEED_RECOMMEND_URL = "$API_ROOT/x/web-interface/wbi/index/top/feed/rcmd"
    private const val INDEX_RECOMMEND_URL = "$API_ROOT/x/web-interface/index/top/rcmd"
    private const val POPULAR_URL = "$API_ROOT/x/web-interface/popular"
    private const val SEARCH_URL = "$API_ROOT/x/web-interface/wbi/search/type"
    private const val VIEW_URL = "$API_ROOT/x/web-interface/view"
    private const val PLAYURL_URL = "$API_ROOT/x/player/playurl"
    private const val WBI_PLAYURL_URL = "$API_ROOT/x/player/wbi/playurl"

    // ---- 2026-11-18 扩展：分区 / 历史 / 收藏 / 动态 / 直播 / 弹幕 ------------
    private const val REGION_FEED_URL = "$API_ROOT/x/web-interface/dynamic/region"
    private const val HISTORY_URL = "$API_ROOT/x/web-interface/history/cursor"
    private const val WATCH_LATER_URL = "$API_ROOT/x/web-interface/watchlater/list"
    private const val FAV_FOLDER_URL = "$API_ROOT/x/v2/fav/folder/list"
    private const val FAV_VIDEO_URL = "$API_ROOT/x/v2/fav/biz/forward"
    private const val DYNAMIC_FEED_URL = "$API_ROOT/x/polymer/web-dynamic/v1/feed/all"
    private const val DM_XML_URL = "$API_ROOT/x/v1/dm/list.so"
    private const val LIKE_URL = "$API_ROOT/x/web-interface/archive/like"
    private const val COIN_URL = "$API_ROOT/x/web-interface/coin/add"
    private const val RELATION_URL = "$API_ROOT/x/relation/modify"

    // ── 扫码登录（web 端二维码，2026-11-18）──────────────────────────────
    //
    // 走 passport 的 web 二维码链路：generate 拿 qrcode_key 与二维码内容，
    // 轮询 poll 直到用户用 B 站 App 确认。**不走网页登录**——网页登录在深色模式下
    // 还要给第三方页面注入样式，而二维码只依赖两个公开接口。
    private const val QR_GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    private const val QR_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
    private const val LIVE_API_ROOT = "https://api.live.bilibili.com"
    private const val LIVE_RECOMMEND_URL = "$LIVE_API_ROOT/room/v1/room/get_user_recommend"
    private const val LIVE_PLAY_URL = "$LIVE_API_ROOT/room/v1/Room/playUrl"

    /** WBI 密钥缓存时长：B 站按天轮换，取 6 小时足够且不会用到过期密钥。 */
    private const val WBI_KEY_TTL_MS = 6 * 60 * 60 * 1000L

    /** 播放直链缓存时长与容量上限（仅播放路径用，下载不缓存）。 */
    private const val PLAYBACK_TTL_MS = 10 * 60 * 1000L
    private const val PLAYBACK_CACHE_MAX = 32
    private val playbackCache = linkedMapOf<String, Pair<Long, BiliPlayback>>()

    /** WBI 签名的 mixin 重排表（B 站网页端公开算法，见旧 BilibiliScreen 实现）。 */
    private val WBI_MIXIN_TABLE = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62,
        11, 36, 20, 34, 44, 52
    )

    /**
     * 清晰度 id → 中文名。
     *
     * 只用于「接口没给 accept_description 时兜底显示名字」，**不用于判断可用性**：
     * 一个档位是否出现，只看它有没有真的出现在 playurl 返回的 `durl` / `dash.video` 里。
     * 取值是 B 站长期固定的 quality 定义（与网页播放器一致）。
     */
    private val QUALITY_NAMES = mapOf(
        127 to "8K 超高清",
        126 to "杜比视界",
        125 to "HDR 真彩",
        120 to "4K 超清",
        116 to "1080P 60帧",
        112 to "1080P 高码率",
        80 to "1080P 高清",
        74 to "720P 60帧",
        64 to "720P 高清",
        32 to "480P 清晰",
        16 to "360P 流畅",
        6 to "240P 极速"
    )

    /** 匹配搜索标题里的高亮标签（`<em class="keyword">…</em>`）。 */
    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    /** 文件名里不允许出现的字符（Windows/Android 都按这套处理最稳）。 */
    private val ILLEGAL_FILENAME_REGEX = Regex("[\\\\/:*?\"<>|\\r\\n]")

    // ---------------------------------------------------------------------
    // 对外数据模型
    // ---------------------------------------------------------------------

    /** 列表卡片所需的视频摘要（推荐 / 热门 / 搜索三处共用）。 */
    data class BiliVideoItem(
        val bvid: String,
        val title: String,
        val coverUrl: String,
        val upName: String,
        val viewCount: Int,
        val durationSeconds: Int,
        val pubdate: Long,
        /** UP 主 mid：关注接口 `x/relation/modify` 需要它；老接口可能不返回，缺省 0。 */
        val mid: Long = 0L
    )

    /** 分页结果：`hasMore=false` 时列表底部不再显示「加载更多」。 */
    data class BiliPageResult(
        val items: List<BiliVideoItem>,
        val hasMore: Boolean
    )

    /** 视频分 P（多 P 视频详情页要按 P 选择播放/下载）。 */
    data class BiliPage(
        val index: Int,
        val cid: Long,
        val title: String,
        val durationSeconds: Int
    )

    /** 视频详情（原生详情页用：简介、播放/点赞数、分 P 列表）。 */
    data class BiliVideoDetail(
        val bvid: String,
        /** 稿件 aid：评论接口 `x/v2/reply` 的 `oid` 参数需要它。 */
        val aid: Long,
        val title: String,
        val coverUrl: String,
        val upName: String,
        val viewCount: Int,
        val likeCount: Int,
        val danmakuCount: Int,
        val coinCount: Int,
        val favoriteCount: Int,
        val durationSeconds: Int,
        val pubdate: Long,
        val desc: String,
        val pages: List<BiliPage>,
        /** UP 主头像（`owner.face`）：播放页简介卡用它显示真实头像，点击可进空间。 */
        val upAvatar: String = "",
        /** UP 主 mid（`owner.mid`）：列表未必带，详情接口是权威来源。 */
        val mid: Long = 0L
    )

    /** 可用清晰度来自哪条链路，决定下载方式。 */
    enum class BiliQualitySource {
        /** html5 单文件 MP4（已带音轨），交给系统 DownloadManager，最快最稳。 */
        PROGRESSIVE,

        /** DASH 音视频分流，需要下载两路再本地重封装（MediaMuxer，零转码）。 */
        DASH
    }

    /** 一个**真实可用**的清晰度档位（2026-11-03 增强：补码率/体积，供底部弹层展示）。 */
    data class BiliQuality(
        val id: Int,
        val label: String,
        val source: BiliQualitySource,
        /** DASH 档位的带宽（bit/s）；单文件档位拿不到时为 0。 */
        val bandwidth: Long = 0L,
        /** 单文件档位的服务端体积（字节）；DASH 档位为 0（体积按带宽×时长估算）。 */
        val sizeBytes: Long = 0L
    )

    /**
     * 一条评论（`x/v2/reply?type=1&oid=<aid>` 的 replies 条目）。
     *
     * 实测（2026-11-03，未登录）：接口可用，但**匿名只返回 3 条/页**且 `pn>1` 直接回空
     * （page.num=0）；登录后才会按 `ps=20` 正常分页。所以 UI 用 `hasMore` 判断是否
     * 还有下一页，并在未登录时明确提示「登录后可查看更多」——不能假装还有数据。
     */
    data class BiliComment(
        val rpid: Long,
        val uname: String,
        val avatarUrl: String,
        val ctimeSeconds: Long,
        val likeCount: Int,
        val message: String,
        val replyCount: Int
    )

    /** 评论分页结果。 */
    data class BiliCommentPage(
        val comments: List<BiliComment>,
        val totalCount: Int,
        val hasMore: Boolean
    )

    /**
     * 当前 B 站账号登录态（`x/web-interface/nav`）。
     *
     * `isLogin=false` 时 `uname` 为空——UI 只显示「未登录」，不要去猜账号信息。
     */
    data class BiliNavState(
        val isLogin: Boolean,
        val uname: String,
        val avatarUrl: String,
        val mid: Long
    )

    /** 一次下载所需的全部信息（直链有时效，拿到即刻下载，不缓存）。 */
    data class BiliStreamPlan(
        val quality: BiliQuality,
        val title: String,
        val bvid: String,
        val pageIndex: Int,
        val progressiveUrl: String? = null,
        val dashVideoUrl: String? = null,
        val dashAudioUrl: String? = null,
        /**
         * 下载这些直链时要带的请求头（UA + 按视频构造的 Referer）。
         *
         * 必须跟着 plan 一起交给下载器原样带上：B 站 CDN 有防盗链，缺 Referer
         * 一律 403。放在 plan 里而不是让下载器自己再拼，是因为「用哪个 bvid 对应的
         * Referer」只有解析这一步知道。
         */
        val headers: Map<String, String> = emptyMap()
    )

    /**
     * 播放用的一次性取流结果（2026-11-18 P0 改造新增）。
     *
     * 与下载用的 [BiliStreamPlan] 分开：下载必须「选定后重新取链、不静默降级」，
     * 播放则允许短 TTL 缓存复用——起播前从 3 次串行 playurl 请求降到 1 次。
     */
    data class BiliPlayback(
        val quality: BiliQuality,
        val url: String,
        val headers: Map<String, String>,
        /**
         * 本次取流接口声明的**全部**可用档位（与 accept_quality / accept_description 同序）。
         * 播放页的「清晰度抽屉」用它列出可选项；缺省空时 UI 只显示当前一档（兼容短视频链路）。
         */
        val qualities: List<BiliQuality> = emptyList()
    )

    // ---- 2026-11-18 扩展模型：分区 / 动态 / 直播 / 弹幕 / 收藏 ----------------

    /** 视频分区（rid 与 B 站 web 端分区 id 一致，硬编码避免多一次网络往返）。 */
    data class BiliRegion(val rid: Int, val name: String, val emoji: String)

    /** 动态 feed 的一条；[video] 为 null 表示这是纯文字/图文动态。 */
    data class BiliDynamicItem(
        val id: String,
        val upName: String,
        val upAvatar: String,
        val text: String,
        val timeText: String,
        val video: BiliVideoItem?
    )

    /** 直播间摘要。 */
    data class BiliLiveRoom(
        val roomId: Long,
        val title: String,
        val userName: String,
        val coverUrl: String,
        val online: Int,
        val areaName: String
    )

    /**
     * 一条弹幕。
     *
     * [mode] 1=滚动 4=底部 5=顶部 6=逆向 8=高级；播放器目前只渲染 1/4/5。
     * [color] 是 0xRRGGBB 整数，[fontSize] 来自 XML 的 `fs` 属性。
     */
    data class BiliDanmaku(
        val timeSec: Float,
        val mode: Int,
        val fontSize: Int,
        val color: Int,
        val text: String
    )

    /** 收藏夹信息。 */
    data class BiliFavFolder(val id: Long, val name: String, val count: Int)

    /**
     * B 站接口/网络失败。
     *
     * message 已经是「用户能看懂的一句话」，UI 直接展示，不要再拼技术细节。
     */
    class BilibiliException(message: String) : Exception(message)

    // ---------------------------------------------------------------------
    // 网络状态
    // ---------------------------------------------------------------------

    /**
     * 是否有可用网络。
     *
     * 「无网络」与「接口出错」对用户是完全不同的两件事：前者要做的是开网络，
     * 后者只能等。所以页面在请求前先问这一句，把提示写成可行动的文案。
     * 取不到系统服务时按「有网」处理（宁可让请求真实失败，也不误报无网）。
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------------
    // 登录态：WebView 内登录 + Cookie 持久化（2026-11-03 用户同意后实装）
    // ---------------------------------------------------------------------

    /**
     * Cookie 存储用的 SharedPreferences 文件名（**明文**，见下方风险说明）。
     *
     * ⚠️ 隐私与风险（已按用户授权明确告知，写进代码注释避免后续维护者误解）：
     * - 存的是 B 站登录 Cookie（SESSDATA 等），**明文**放在应用私有 SharedPreferences 里，
     *   不上传、不共享给任何第三方；卸载应用即随私有目录一起删除；
     * - 拿得到它的人等于拿到用户 B 站账号的登录态（可发弹幕/点赞/看关注），
     *   所以「退出登录」会同时清 SharedPreferences 与 WebView 的 CookieManager；
     * - 本应用没有加密存储层（整个项目数据都在本地明文），这里与项目现状保持一致，
     *   不做半吊子「伪加密」；如需更高安全应整体引入 EncryptedSharedPreferences（本轮不新增依赖）。
     */
    private const val SESSION_PREFS = "bili_login_session"
    private const val SESSION_KEY_COOKIE = "cookie"

    /** 内存里的当前登录 Cookie 串（null = 未登录）。 */
    @Volatile
    private var sessionCookie: String? = null

    /** 当前会话 Cookie（供播放页往 WebView 的 CookieManager 里回注）。 */
    fun currentSessionCookie(): String? = sessionCookie?.takeIf { it.isNotBlank() }

    /** 是否已登录（有 Cookie 就算；真实有效性由 [fetchNavState] 验证）。 */
    fun isLoggedIn(): Boolean = !sessionCookie.isNullOrBlank()

    /** 进程启动后从磁盘恢复一次登录态（幂等）。 */
    fun restoreSession(context: Context) {
        if (sessionCookie != null) return
        val saved = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .getString(SESSION_KEY_COOKIE, null)
        if (!saved.isNullOrBlank()) sessionCookie = saved
    }

    /** 保存登录 Cookie（整串原样存，便于回注 CookieManager）。 */
    fun saveSessionCookie(context: Context, cookie: String?) {
        val normalized = cookie?.trim().orEmpty()
        if (normalized.isBlank()) {
            clearSession(context)
            return
        }
        sessionCookie = normalized
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SESSION_KEY_COOKIE, normalized)
            .apply()
    }

    /** 退出登录：清内存 + 清磁盘（WebView CookieManager 由 UI 层一并清理）。 */
    fun clearSession(context: Context) {
        sessionCookie = null
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(SESSION_KEY_COOKIE)
            .apply()
    }

    /**
     * 查登录态（`x/web-interface/nav`）。
     *
     * 未登录时接口 `code=-101`，但 `data.isLogin=false` 是可用的正常结果，
     * 所以这里**刻意不走 [ensureOk]**（否则未登录会被当成异常）。
     */
    suspend fun fetchNavState(): BiliNavState = ioCall {
        val json = JSONObject(AppHttp.getText(NAV_URL, biliHeaders()))
        val data = json.optJSONObject("data")
        BiliNavState(
            isLogin = data?.optBoolean("isLogin", false) ?: false,
            uname = data?.optString("uname").orEmpty(),
            avatarUrl = normalizeImageUrl(data?.optString("face").orEmpty()),
            mid = data?.optLong("mid", 0L) ?: 0L
        )
    }

    // ---------------------------------------------------------------------
    // 频道（分区排行榜）
    // ---------------------------------------------------------------------

    /**
     * 分区排行榜（频道 tab 用）。
     *
     * 实测（2026-11-03，未登录）：`x/web-interface/ranking/v2?rid=&type=all` 一次回
     * 100 条、`duration` 是秒数（与 popular 同字段），不需要分页。
     * 可用 rid 已逐个验证：0 总榜 / 1 动画 / 168 国创 / 177 纪录片 / 11 电视剧 /
     * 23 电影 / 181 影视 / 4 游戏 / 3 音乐 / 129 舞蹈 / 119 鬼畜 / 36 知识 /
     * 188 科技 / 160 生活 / 211 美食 / 234 运动 / 155 时尚 / 5 明星。
     * ⚠️ 13（番剧）与 254 在 `ranking/v2` 回 -400，未收录。
     * 连续快速请求会触发 `-352` 风控（实测），页面上以中文提示要求稍后重试。
     */
    suspend fun fetchRanking(rid: Int): List<BiliVideoItem> = ioCall {
        val url = "$API_ROOT/x/web-interface/ranking/v2?rid=$rid&type=all"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val list = json.optJSONObject("data")?.optJSONArray("list")
        parseVideoItems(list, onlyVideo = false).distinctBy { it.bvid }
    }

    // ---------------------------------------------------------------------
    // 评论（详情页）
    // ---------------------------------------------------------------------

    /**
     * 视频评论（公开接口，`oid` 传稿件 aid）。
     *
     * 实测（2026-11-03，未登录）：`x/v2/reply?type=1&oid=<aid>&pn=1&ps=20&sort=2`
     * 可用；但匿名只返回 **3 条**（`page.size` 名义 20、`page.count` 是总数），
     * 且 `pn>1` 直接回空（page.num=0）。因此：
     * - `hasMore` 按「本页条数是否达到 ps」判断，不按 count 猜；
     * - 未登录时 UI 明确提示「登录后可查看更多」（这属于服务端限制，不是 bug）。
     *
     * @param page 从 1 开始。
     */
    suspend fun fetchComments(aid: Long, page: Int): BiliCommentPage = ioCall {
        if (aid <= 0L) return@ioCall BiliCommentPage(emptyList(), 0, hasMore = false)
        val ps = 20
        val url = "$API_ROOT/x/v2/reply?type=1&oid=$aid&pn=${page.coerceAtLeast(1)}&ps=$ps&sort=2"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
        val totalCount = data?.optJSONObject("page")?.optInt("count", 0) ?: 0
        val replies = data?.optJSONArray("replies")
        val comments = mutableListOf<BiliComment>()
        if (replies != null) {
            for (i in 0 until replies.length()) {
                val obj = replies.optJSONObject(i) ?: continue
                val member = obj.optJSONObject("member")
                val content = obj.optJSONObject("content")
                val message = content?.optString("message").orEmpty().trim()
                if (message.isEmpty()) continue
                comments += BiliComment(
                    rpid = obj.optLong("rpid", 0L),
                    uname = member?.optString("uname").orEmpty().ifBlank { "匿名用户" },
                    avatarUrl = normalizeImageUrl(member?.optString("avatar").orEmpty()),
                    ctimeSeconds = obj.optLong("ctime", 0L),
                    likeCount = obj.optInt("like", 0),
                    message = message,
                    replyCount = obj.optInt("rcount", 0)
                )
            }
        }
        BiliCommentPage(
            comments = comments,
            totalCount = totalCount,
            hasMore = comments.size >= ps
        )
    }

    /**
     * 相关推荐（详情页底部的「相关推荐」列表）。
     *
     * `x/web-interface/archive/related?bvid=...` 免登录可用，返回 data 数组。
     * 失败时返回空列表而不是抛错：推荐位是次要信息，不该阻断播放。
     */
    suspend fun fetchRelated(bvid: String): List<BiliVideoItem> = ioCall {
        if (bvid.isBlank()) return@ioCall emptyList()
        val url = "$API_ROOT/x/web-interface/archive/related?bvid=${percentEncode(bvid)}"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        if (json.optInt("code", -1) != 0) return@ioCall emptyList()
        parseVideoItems(json.optJSONArray("data"), onlyVideo = false)
            .filterNot { it.bvid == bvid }
            .distinctBy { it.bvid }
    }

    // ---------------------------------------------------------------------
    // 搜索热词
    // ---------------------------------------------------------------------

    /**
     * 当前 B 站热搜词（搜索 tab 空输入时展示，点一下直接搜）。
     *
     * 实测（2026-11-03，未登录）两条都可用，优先官方 `s.search.bilibili.com/main/hotword`：
     * - 主：`s.search.bilibili.com/main/hotword?limit=15` → `list[].keyword/show_name`；
     * - 备：`x/web-interface/search/square?limit=10` → `data.trending.list[].keyword`。
     * 两条都失败才抛错（页面显示「热搜加载失败」但搜索本身仍可用）。
     */
    suspend fun fetchSearchHotWords(): List<String> = ioCall {
        val primary = runCatching {
            val json = JSONObject(
                AppHttp.getText("https://s.search.bilibili.com/main/hotword?limit=15", biliHeaders())
            )
            val list = json.optJSONArray("list") ?: JSONArray()
            (0 until list.length()).mapNotNull { i ->
                list.optJSONObject(i)?.optString("show_name")?.ifBlank {
                    list.optJSONObject(i)?.optString("keyword")
                }?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.getOrNull().orEmpty()

        val words = if (primary.isNotEmpty()) primary else {
            runCatching {
                val json = JSONObject(
                    AppHttp.getText(
                        "$API_ROOT/x/web-interface/search/square?limit=10",
                        biliHeaders()
                    )
                )
                val trending = json.optJSONObject("data")
                    ?.optJSONObject("trending")
                    ?.optJSONArray("list") ?: JSONArray()
                (0 until trending.length()).mapNotNull { i ->
                    trending.optJSONObject(i)?.optString("show_name")?.ifBlank {
                        trending.optJSONObject(i)?.optString("keyword")
                    }?.trim()?.takeIf { it.isNotEmpty() }
                }
            }.getOrNull().orEmpty()
        }
        words.distinct().take(12)
    }

    // ---------------------------------------------------------------------
    // 推荐
    // ---------------------------------------------------------------------

    /**
     * 日常推荐（首页默认 tab）。
     *
     * 主链路是网页版首页同款 `wbi/index/top/feed/rcmd`：
     * - 必须 WBI 签名，一次 12 条；
     * - `fresh_idx` 递增 = 「换一批」，刷新时传更大的值即可拿到新内容。
     *
     * 主链路失败时回退到老的 `index/top/rcmd`（免签名，但实测连续刷新会 `-412`，
     * 只适合当兜底）。两条都失败才向用户报错。
     *
     * @param freshIdx 刷新批次（从 1 开始递增）。
     */
    suspend fun fetchRecommend(freshIdx: Int): List<BiliVideoItem> = ioCall {
        var primaryItems: List<BiliVideoItem>? = null
        var primaryError: Exception? = null
        try {
            primaryItems = fetchFeedRecommend(freshIdx)
        } catch (e: Exception) {
            primaryError = e
        }
        primaryItems?.takeIf { it.isNotEmpty() }?.let { return@ioCall it }

        var fallbackItems: List<BiliVideoItem>? = null
        var fallbackError: Exception? = null
        try {
            fallbackItems = fetchIndexRecommend()
        } catch (e: Exception) {
            fallbackError = e
        }
        fallbackItems?.takeIf { it.isNotEmpty() }?.let { return@ioCall it }

        // 两条都空/失败：优先把主链路的原因抛出去（更接近真实原因），
        // 主链路若是「成功但空」则给出兜底链路的错误；都成功但空就返回空列表。
        primaryError?.let { throw toBiliException(it) }
        fallbackError?.let { throw toBiliException(it) }
        emptyList()
    }

    /** WBI 版首页推荐。 */
    private suspend fun fetchFeedRecommend(freshIdx: Int): List<BiliVideoItem> {
        val mixinKey = wbiMixinKey()
        val query = signWbi(
            mapOf(
                "web_location" to "1430650",
                "y_num" to "5",
                "fresh_type" to "4",
                "feed_version" to "V8",
                "fresh_idx" to freshIdx.coerceAtLeast(1).toString(),
                "fresh_idx_1h" to "1",
                "fetch_row" to "1",
                "homepage_ver" to "1",
                "ps" to "12",
                "brush" to "0"
            ),
            mixinKey
        )
        val json = JSONObject(AppHttp.getText(urlWithParams(FEED_RECOMMEND_URL, query), biliHeaders()))
        ensureOk(json)
        val items = parseVideoItems(json.optJSONObject("data")?.optJSONArray("item"), onlyVideo = true)
        return items.distinctBy { it.bvid }
    }

    /** 免签名兜底推荐（连续刷新会被限流，因此只当后备）。 */
    private suspend fun fetchIndexRecommend(): List<BiliVideoItem> {
        val url = "$INDEX_RECOMMEND_URL?ps=12&fresh_type=3&version=1"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        return parseVideoItems(json.optJSONObject("data")?.optJSONArray("item"), onlyVideo = true)
            .distinctBy { it.bvid }
    }

    // ---------------------------------------------------------------------
    // 热门
    // ---------------------------------------------------------------------

    /**
     * 热门榜单（`pn` 从 1 开始）。
     *
     * `no_more=true` 表示到底；接口一次 20 条，所以「加载更多」就是 `pn+1`。
     */
    suspend fun fetchPopular(page: Int): BiliPageResult = ioCall {
        val url = "$POPULAR_URL?ps=20&pn=${page.coerceAtLeast(1)}"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
        val items = parseVideoItems(data?.optJSONArray("list"), onlyVideo = false)
        BiliPageResult(
            items = items,
            hasMore = !(data?.optBoolean("no_more", true) ?: true) && items.isNotEmpty()
        )
    }

    // ---------------------------------------------------------------------
    // 搜索
    // ---------------------------------------------------------------------

    /**
     * 视频搜索（WBI 签名）。
     *
     * `search_type=video` 只回视频；无结果时 `data.result` 为 null，
     * 这里统一折成空列表（UI 显示「没有搜到」而不是报错）。
     */
    suspend fun searchVideos(keyword: String, page: Int): BiliPageResult = ioCall {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return@ioCall BiliPageResult(emptyList(), hasMore = false)

        val mixinKey = wbiMixinKey()
        val query = signWbi(
            mapOf(
                "search_type" to "video",
                "keyword" to trimmed,
                "page" to page.coerceAtLeast(1).toString(),
                "page_size" to "20"
            ),
            mixinKey
        )
        val json = JSONObject(AppHttp.getText(urlWithParams(SEARCH_URL, query), biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
        val totalPages = data?.optInt("numPages", 1) ?: 1
        val current = data?.optInt("page", page) ?: page
        val items = parseSearchItems(data?.optJSONArray("result"))
        BiliPageResult(
            items = items,
            hasMore = items.isNotEmpty() && current < totalPages
        )
    }

    // ---------------------------------------------------------------------
    // 视频详情
    // ---------------------------------------------------------------------

    /** 视频详情：简介、统计、分 P。失败时抛出带中文原因的 [BilibiliException]。 */
    suspend fun fetchDetail(bvid: String): BiliVideoDetail = ioCall {
        val json = JSONObject(AppHttp.getText("$VIEW_URL?bvid=$bvid", biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
            ?: throw BilibiliException("视频不存在或已下架")

        val owner = data.optJSONObject("owner")
        val stat = data.optJSONObject("stat")
        val pages = mutableListOf<BiliPage>()
        val pagesArray = data.optJSONArray("pages")
        if (pagesArray != null) {
            for (i in 0 until pagesArray.length()) {
                val obj = pagesArray.optJSONObject(i) ?: continue
                pages += BiliPage(
                    index = obj.optInt("page", i + 1),
                    cid = obj.optLong("cid", 0L),
                    title = obj.optString("part").ifBlank { "P${i + 1}" },
                    durationSeconds = obj.optInt("duration", 0)
                )
            }
        }
        if (pages.isEmpty()) {
            // 老接口个别稿件没有 pages，用顶层 cid 兜一个单 P，保证详情页始终能播放/下载。
            pages += BiliPage(
                index = 1,
                cid = data.optLong("cid", 0L),
                title = "正片",
                durationSeconds = data.optInt("duration", 0)
            )
        }
        BiliVideoDetail(
            bvid = data.optString("bvid", bvid),
            aid = data.optLong("aid", 0L),
            title = data.optString("title").ifBlank { "（无标题）" },
            coverUrl = normalizeImageUrl(data.optString("pic")),
            upName = owner?.optString("name").orEmpty().ifBlank { "未知UP主" },
            upAvatar = normalizeImageUrl(owner?.optString("face").orEmpty()),
            mid = owner?.optLong("mid", 0L) ?: 0L,
            viewCount = stat?.optInt("view", 0) ?: 0,
            likeCount = stat?.optInt("like", 0) ?: 0,
            danmakuCount = stat?.optInt("danmaku", 0) ?: 0,
            coinCount = stat?.optInt("coin", 0) ?: 0,
            favoriteCount = stat?.optInt("favorite", 0) ?: 0,
            durationSeconds = data.optInt("duration", 0),
            pubdate = data.optLong("pubdate", 0L),
            desc = data.optString("desc").ifBlank { "（该视频没有简介）" },
            pages = pages
        )
    }

    // ---------------------------------------------------------------------
    // 清晰度探测 + 下载地址解析
    // ---------------------------------------------------------------------

    /**
     * 探测「这个视频当前**真的**能下载哪些清晰度」。
     *
     * 两条链路各问一次，取并集：
     * 1. html5 单文件（`durl`）：免登录一般给 720P；
     * 2. WBI DASH（`fnval=16`）：免登录一般只给 480P/360P。
     *
     * 任何一个链路失败都不影响另一个（例如 DASH 被风控拒了，720P 仍可下）。
     * 两条都拿不到档位时抛错——UI 显示「当前拿不到可用清晰度」而不是空白对话框。
     */
    suspend fun fetchQualities(bvid: String, cid: Long): List<BiliQuality> = ioCall {
        // 两条链路各自 try/catch：一条失败不影响另一条（例如 DASH 被风控拒了，720P 仍可下）。
        val progressive = try {
            fetchProgressivePlayurl(bvid, cid)
        } catch (e: Exception) {
            null
        }
        val dash = try {
            fetchDashPlayurl(bvid, cid)
        } catch (e: Exception) {
            null
        }

        // id → 中文名：优先用接口的 accept_description（与 accept_quality 同序对应），
        // 接口没给时用固定的 quality 名称表兜底（只兜显示名，不兜可用性）。
        val labels = mutableMapOf<Int, String>()
        fun mergeAccept(info: PlayurlInfo?) {
            info ?: return
            info.acceptIds.forEachIndexed { index, id ->
                val name = info.acceptNames.getOrNull(index)
                if (!name.isNullOrBlank()) labels[id] = name
            }
        }
        mergeAccept(progressive)
        mergeAccept(dash)

        fun labelOf(id: Int): String =
            labels[id] ?: QUALITY_NAMES[id] ?: "${id}P"

        val qualities = linkedMapOf<Int, BiliQuality>()
        // 单文件优先：同一档位若单文件也有，就用单文件（系统下载器更稳、无需合流）。
        if (progressive != null && progressive.durlUrls.isNotEmpty() && progressive.quality > 0) {
            qualities[progressive.quality] = BiliQuality(
                id = progressive.quality,
                label = labelOf(progressive.quality),
                source = BiliQualitySource.PROGRESSIVE,
                sizeBytes = progressive.durlSizeBytes
            )
        }
        dash?.dashVideos?.groupBy { it.id }?.forEach { (id, videos) ->
            if (!qualities.containsKey(id)) {
                val bandwidth = videos.maxOfOrNull { it.bandwidth } ?: 0L
                qualities[id] = BiliQuality(
                    id = id,
                    label = labelOf(id),
                    source = BiliQualitySource.DASH,
                    bandwidth = bandwidth
                )
            }
        }
        if (qualities.isEmpty()) {
            throw BilibiliException("B 站没有返回可用清晰度，可能该视频已下架或被风控限制，请稍后重试")
        }
        qualities.values.sortedByDescending { it.id }
    }

    /**
     * 用户选定清晰度后，重新请求一次直链（直链带 `deadline` 时效，不能复用探测时的结果）。
     *
     * ⚠️ 刻意**不做静默降级**：选了 1080P 但接口这时只给 720P，直接抛错并说明
     * 「所选清晰度不可用」，绝不偷偷下个 720P 交差（用户需求明确要求）。
     */
    suspend fun resolveStreamPlan(
        bvid: String,
        cid: Long,
        title: String,
        pageIndex: Int,
        quality: BiliQuality
    ): BiliStreamPlan = ioCall {
        val headers = biliHeaders(referer = "https://www.bilibili.com/video/$bvid/")
        when (quality.source) {
            BiliQualitySource.PROGRESSIVE -> {
                val info = fetchProgressivePlayurl(bvid, cid)
                if (info.quality != quality.id || info.durlUrls.isEmpty()) {
                    throw BilibiliException(
                        "所选清晰度「${quality.label}」当前已不可用（可能需登录或视频已调整），" +
                            "未开始下载，请返回重新选择"
                    )
                }
                BiliStreamPlan(
                    quality = quality,
                    title = title,
                    bvid = bvid,
                    pageIndex = pageIndex,
                    progressiveUrl = info.durlUrls.first(),
                    headers = headers
                )
            }

            BiliQualitySource.DASH -> {
                val info = fetchDashPlayurl(bvid, cid)
                // 同一档位可能有多条（AVC/HEVC/AV1 编码各一条）：优先 AVC（codecid=7），
                // 兼容性最好（MediaMuxer 重封装与系统相册播放都不挑容器）。
                val candidates = info.dashVideos.filter { it.id == quality.id }
                val chosen = candidates.firstOrNull { it.codecid == 7 } ?: candidates.firstOrNull()
                    ?: throw BilibiliException(
                        "所选清晰度「${quality.label}」当前已不可用（可能需登录或视频已调整），" +
                            "未开始下载，请返回重新选择"
                    )
                val audio = info.dashAudios.maxByOrNull { it.id }
                    ?: throw BilibiliException("该视频没有可下载的音轨，暂不支持下载")
                BiliStreamPlan(
                    quality = quality,
                    title = title,
                    bvid = bvid,
                    pageIndex = pageIndex,
                    dashVideoUrl = chosen.url,
                    dashAudioUrl = audio.url,
                    headers = headers
                )
            }
        }
    }

    /**
     * 播放路径专用：一次 html5 取流即返回「档位 + 直链 + 请求头」。
     *
     * 改造前播放页走 fetchQualities（2 次请求）+ resolveStreamPlan（第 3 次），
     * 而播放只消费 PROGRESSIVE 一档——DASH 那次请求与重新取链都是浪费。
     * 这里只问 html5 一次，并按 `bvid|cid` 缓存 [PLAYBACK_TTL_MS]：
     * 直链 deadline 通常 ≥2h，10 分钟复用足够安全（参考实现 Cheers 用 15min）。
     *
     * ⚠️ 下载路径**不走这里**：resolveStreamPlan 的「不静默降级」口径保持不变。
     */
    suspend fun fetchPlayback(bvid: String, cid: Long, qn: Int = 0): BiliPlayback = ioCall {
        // 档位进缓存键：切清晰度必须重新取流，不能命中上一档的旧直链。
        val key = "$bvid|$cid|$qn"
        val now = System.currentTimeMillis()
        val cached = synchronized(playbackCache) {
            playbackCache[key]?.takeIf { now - it.first < PLAYBACK_TTL_MS }?.second
        }
        if (cached != null) return@ioCall cached

        val info = fetchProgressivePlayurl(bvid, cid, qn)
        val url = info.durlUrls.firstOrNull()
            ?: throw BilibiliException("播放地址为空，请稍后重试")
        val idx = info.acceptIds.indexOf(info.quality)
        val label = (if (idx >= 0) info.acceptNames.getOrNull(idx) else null)
            ?: QUALITY_NAMES[info.quality] ?: "${info.quality}P"
        // accept_quality / accept_description 是同序数组，拼成档位列表给播放页的选择抽屉。
        // 去重（个别视频会回重复 id）且保持服务端顺序（从高到低）。
        val options = info.acceptIds.mapIndexedNotNull { i, id ->
            if (id <= 0) null else BiliQuality(
                id = id,
                label = info.acceptNames.getOrNull(i)?.takeIf { it.isNotBlank() }
                    ?: QUALITY_NAMES[id] ?: "${id}P",
                source = BiliQualitySource.PROGRESSIVE
            )
        }.distinctBy { it.id }
        val playback = BiliPlayback(
            quality = BiliQuality(
                id = info.quality,
                label = label,
                source = BiliQualitySource.PROGRESSIVE,
                sizeBytes = info.durlSizeBytes
            ),
            url = url,
            headers = biliHeaders(referer = "https://www.bilibili.com/video/$bvid/"),
            qualities = options
        )
        synchronized(playbackCache) {
            playbackCache[key] = now to playback
            while (playbackCache.size > PLAYBACK_CACHE_MAX) {
                playbackCache.remove(playbackCache.keys.first())
            }
        }
        playback
    }

    /**
     * html5 单文件播放地址（`platform=html5` 是能拿到「带音轨 MP4」的关键参数）。
     *
     * @param qn 期望档位 id（0 = 默认 64/720P）。播放页清晰度抽屉选中某档后传它的 id，
     *   服务端会在 accept_quality 允许的范围内返回对应直链。
     */
    private suspend fun fetchProgressivePlayurl(bvid: String, cid: Long, qn: Int = 0): PlayurlInfo {
        val desiredQn = if (qn > 0) qn else 64
        val url = "$PLAYURL_URL?bvid=$bvid&cid=$cid&qn=$desiredQn&platform=html5&high_quality=1"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data") ?: throw BilibiliException("播放地址返回为空")
        val durl = data.optJSONArray("durl")
        val urls = mutableListOf<String>()
        var durlSizeBytes = 0L
        if (durl != null) {
            for (i in 0 until durl.length()) {
                val item = durl.optJSONObject(i) ?: continue
                val link = item.optString("url")
                if (link.isNotBlank()) urls += link
                if (i == 0) durlSizeBytes = item.optLong("size", 0L)
            }
        }
        return PlayurlInfo(
            quality = data.optInt("quality", 0),
            acceptIds = parseIntArray(data.optJSONArray("accept_quality")),
            acceptNames = parseStringArray(data.optJSONArray("accept_description")),
            durlUrls = urls,
            durlSizeBytes = durlSizeBytes
        )
    }

    /** WBI 签名的 DASH 播放地址（拿真实视频轨/音频轨列表）。 */
    private suspend fun fetchDashPlayurl(bvid: String, cid: Long): PlayurlInfo {
        val mixinKey = wbiMixinKey()
        val query = signWbi(
            mapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                // qn=127 表示「请求最高」，实际返回哪些档位仍由返回体决定（见类注释）。
                "qn" to "127",
                "fnval" to "16",
                "fourk" to "1",
                "platform" to "web"
            ),
            mixinKey
        )
        val json = JSONObject(AppHttp.getText(urlWithParams(WBI_PLAYURL_URL, query), biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data") ?: throw BilibiliException("DASH 播放地址返回为空")
        val dash = data.optJSONObject("dash")
            ?: throw BilibiliException("该视频没有 DASH 播放清单（可能是番剧/直播）")

        val videos = mutableListOf<DashVideo>()
        val videoArray = dash.optJSONArray("video")
        if (videoArray != null) {
            for (i in 0 until videoArray.length()) {
                val obj = videoArray.optJSONObject(i) ?: continue
                val url = dashBaseUrl(obj)
                if (url.isBlank()) continue
                videos += DashVideo(
                    id = obj.optInt("id", 0),
                    codecid = obj.optInt("codecid", 0),
                    url = url,
                    bandwidth = obj.optLong("bandwidth", 0L)
                )
            }
        }
        val audios = mutableListOf<DashAudio>()
        val audioArray = dash.optJSONArray("audio")
        if (audioArray != null) {
            for (i in 0 until audioArray.length()) {
                val obj = audioArray.optJSONObject(i) ?: continue
                val url = dashBaseUrl(obj)
                if (url.isBlank()) continue
                audios += DashAudio(id = obj.optInt("id", 0), url = url)
            }
        }
        return PlayurlInfo(
            quality = data.optInt("quality", 0),
            acceptIds = parseIntArray(data.optJSONArray("accept_quality")),
            acceptNames = parseStringArray(data.optJSONArray("accept_description")),
            durlUrls = emptyList(),
            dashVideos = videos,
            dashAudios = audios
        )
    }

    /** DASH 的直链字段在不同版本里叫 baseUrl / base_url，两个都认。 */
    private fun dashBaseUrl(obj: JSONObject): String =
        obj.optString("baseUrl").ifBlank { obj.optString("base_url") }

    // ---------------------------------------------------------------------
    // 解析辅助
    // ---------------------------------------------------------------------

    private class PlayurlInfo(
        val quality: Int,
        val acceptIds: List<Int>,
        val acceptNames: List<String>,
        val durlUrls: List<String>,
        /** 单文件 MP4 的服务端体积（字节，0 = 接口没给）。 */
        val durlSizeBytes: Long = 0L,
        val dashVideos: List<DashVideo> = emptyList(),
        val dashAudios: List<DashAudio> = emptyList()
    )

    private class DashVideo(
        val id: Int,
        val codecid: Int,
        val url: String,
        /** 视频轨带宽（bit/s），用于清晰度弹层里估算体积。 */
        val bandwidth: Long = 0L
    )

    private class DashAudio(val id: Int, val url: String)

    /** 推荐/热门/搜索共用的「视频数组 → 摘要列表」。 */
    private fun parseVideoItems(array: JSONArray?, onlyVideo: Boolean): List<BiliVideoItem> {
        if (array == null) return emptyList()
        val result = mutableListOf<BiliVideoItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid")
            if (bvid.isBlank()) continue
            // 推荐流里会混直播/广告卡片：非 av 的一律丢弃，避免点开是空白播放页。
            if (onlyVideo && obj.optString("goto").let { it.isNotBlank() && it != "av" }) continue
            val owner = obj.optJSONObject("owner")
            val stat = obj.optJSONObject("stat")
            result += BiliVideoItem(
                bvid = bvid,
                title = obj.optString("title").ifBlank { "（无标题）" },
                coverUrl = normalizeImageUrl(obj.optString("pic")),
                upName = owner?.optString("name").orEmpty().ifBlank { "未知UP主" },
                viewCount = stat?.optInt("view", 0) ?: 0,
                durationSeconds = obj.optInt("duration", 0),
                pubdate = obj.optLong("pubdate", 0L),
                mid = owner?.optLong("mid", 0L) ?: obj.optLong("mid", 0L)
            )
        }
        return result
    }

    /**
     * 搜索结果的字段名与推荐/热门不同：`author`/`play`/`duration`(字符串 `m:ss`)，
     * 标题里带 `<em class="keyword">` 高亮标签，需要剥掉。
     */
    private fun parseSearchItems(array: JSONArray?): List<BiliVideoItem> {
        if (array == null) return emptyList()
        val result = mutableListOf<BiliVideoItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid")
            if (bvid.isBlank()) continue
            result += BiliVideoItem(
                bvid = bvid,
                title = stripHtmlTags(obj.optString("title")).ifBlank { "（无标题）" },
                coverUrl = normalizeImageUrl(obj.optString("pic")),
                upName = obj.optString("author").ifBlank { "未知UP主" },
                viewCount = obj.optInt("play", 0),
                durationSeconds = parseDurationText(obj.optString("duration")),
                pubdate = obj.optLong("pubdate", 0L),
                mid = obj.optLong("mid", 0L)
            )
        }
        return result
    }

    private fun stripHtmlTags(text: String): String = text
        .replace(HTML_TAG_REGEX, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    /** `2:18` / `1:02:33` → 秒。 */
    internal fun parseDurationText(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(':').fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: 0) }
    }

    private fun parseIntArray(array: JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optInt(it, 0).takeIf { id -> id > 0 } }
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }
    }

    /**
     * 封面图统一转 https。
     *
     * 实测接口会给三种写法：`http://i1.hdslb.com/...`（热门）、
     * `//i1.hdslb.com/...`（搜索）、`https://...`（推荐）。
     * 本应用只走 https（manifest 未开 cleartext），http 直连会被系统拦成空白图，
     * 所以在这里一次性归一。
     */
    internal fun normalizeImageUrl(raw: String): String {
        val url = raw.trim()
        if (url.isEmpty()) return ""
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            else -> url
        }
    }

    /** 下载文件名：标题太长/带非法字符会写盘失败，这里统一裁剪。 */
    internal fun safeFileName(rawTitle: String, fallback: String): String {
        val cleaned = rawTitle
            .replace(ILLEGAL_FILENAME_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
        return cleaned.ifBlank { fallback }
    }

    // ---------------------------------------------------------------------
    // WBI 签名（B 站网页端算法）
    // ---------------------------------------------------------------------

    @Volatile
    private var cachedMixinKey: String? = null

    @Volatile
    private var cachedMixinAt: Long = 0L

    private val wbiKeyMutex = Mutex()

    /**
     * 取 WBI mixinKey，内存缓存 6 小时。
     *
     * 为什么从 `nav` 接口动态取而不是硬编码：B 站每天轮换密钥，硬编码会在换钥后
     * 让搜索/推荐/DASH 全线失效。nav 未登录时 code=-101，但 `data.wbi_img`
     * 仍会下发（实测），所以不需要登录也能签。
     */
    private suspend fun wbiMixinKey(): String {
        val cached = cachedMixinKey
        if (cached != null && System.currentTimeMillis() - cachedMixinAt < WBI_KEY_TTL_MS) {
            return cached
        }
        return wbiKeyMutex.withLock {
            val again = cachedMixinKey
            val now = System.currentTimeMillis()
            if (again != null && now - cachedMixinAt < WBI_KEY_TTL_MS) return@withLock again

            val nav = JSONObject(AppHttp.getText(NAV_URL, biliHeaders()))
            val wbi = nav.optJSONObject("data")?.optJSONObject("wbi_img")
                ?: throw BilibiliException("无法获取 B 站签名密钥，请稍后重试")
            val raw = wbiKeyPart(wbi.optString("img_url")) + wbiKeyPart(wbi.optString("sub_url"))
            if (raw.length < 64) throw BilibiliException("B 站签名密钥格式异常，请稍后重试")
            val mixin = buildString { WBI_MIXIN_TABLE.forEach { append(raw[it]) } }.substring(0, 32)
            cachedMixinKey = mixin
            cachedMixinAt = now
            mixin
        }
    }

    /** 从 `…/wbi/xxxx.png` 里抠出密钥段（去目录、去扩展名）。 */
    internal fun wbiKeyPart(url: String): String =
        url.substringBefore("?").substringAfterLast('/').substringBefore('.')

    internal fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 加 `wts` → 字典序 → urlencode 拼接 → 拼 mixinKey → MD5 成 `w_rid`。 */
    internal fun signWbi(params: Map<String, String>, mixinKey: String): Map<String, String> {
        val signed = params.toMutableMap()
        signed["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = signed.toSortedMap().entries.joinToString("&") { (key, value) ->
            percentEncode(key) + "=" + percentEncode(value)
        }
        signed["w_rid"] = md5Hex(query + mixinKey)
        return signed
    }

    private fun urlWithParams(base: String, params: Map<String, String>): String =
        base + "?" + params.entries.joinToString("&") { (key, value) ->
            percentEncode(key) + "=" + percentEncode(value)
        }

    /**
     * 与浏览器 `encodeURIComponent` **逐字节一致**的百分号编码。
     *
     * ⚠️ 为什么不用 `java.net.URLEncoder`：它把空格编成 `+`、又把 `! ' ( ) ~`
     * 这些字符也转义，与 B 站网页端计算 `w_rid` 时用的 `encodeURIComponent` 不一致。
     * 本仓库的 WBI 签名要覆盖**搜索关键词**（可能含空格/标点），签名串只要有一个
     * 字符编码不同，`w_rid` 就对不上，表现为搜索随机失败——所以这里自己实现。
     */
    internal fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val builder = StringBuilder(bytes.size * 3)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            val keep = (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '!' || ch == '~' ||
                ch == '*' || ch == '\'' || ch == '(' || ch == ')'
            if (keep) {
                builder.append(ch)
            } else {
                builder.append('%')
                builder.append(HEX_CHARS[code shr 4])
                builder.append(HEX_CHARS[code and 0x0F])
            }
        }
        return builder.toString()
    }

    /**
     * 百分号编码用的十六进制字符表。
     *
     * 2026-11-18 修复：原值是 `"0123456789ABCDEF"`——8 和 9 重复、缺 3 和 4，
     * 于是空格（0x20）会被编成 `%10`、字母 `s`（0x73）会被编成 `%89`，
     * WBI 签名用的查询串与 B 站服务端算出来的永远不一致。
     * 影响面：带空格/大写字母的搜索词随机失败、部分新接口 `-403`。
     * 这里是标准的 0-9A-F，顺序不能动。
     */
    internal val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    // ---------------------------------------------------------------------
    // 请求/错误处理
    // ---------------------------------------------------------------------

    /**
     * 统一请求头。
     *
     * ## 登录态怎么进来（2026-11-03 增强）
     *
     * 用户同意「WebView 内登录 + 持久化 Cookie」后，登录页把 `SESSDATA/bili_jct/`
     * `DedeUserID` 等 cookie 整串交给 [saveSessionCookie] 存进 SharedPreferences，
     * 这里统一拼成 `Cookie:` 头，**所有接口调用自动带登录态**，调用点一行都不用改。
     * 未登录时 `sessionCookie == null`，与改造前行为完全一致。
     */
    private fun biliHeaders(referer: String = "https://www.bilibili.com/"): Map<String, String> =
        buildMap {
            put("User-Agent", BILI_UA)
            put("Referer", referer)
            sessionCookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }

    /** B 站业务码非 0 时折成中文异常。 */
    private fun ensureOk(json: JSONObject) {
        val code = json.optInt("code", 0)
        if (code == 0) return
        val message = json.optString("message")
        throw BilibiliException(
            when (code) {
                -101 -> "需要登录后才能使用该功能"
                -400 -> "请求参数有误，请稍后重试"
                -403 -> "B 站拒绝了访问（可能被风控），请稍后再试"
                -404 -> "视频不存在或已下架"
                -412 -> "请求太频繁，被 B 站限流了，请稍后再试"
                -352 -> "B 站风控拦截了本次请求，请稍后再试"
                62002 -> "该视频不可见（可能已删除或设为私密）"
                87007, 87008 -> "该视频需要大会员或付费观看"
                else -> if (message.isNotBlank()) "B 站接口返回异常：$message" else "B 站接口返回异常（code=$code）"
            }
        )
    }

    /**
     * 统一 IO 调度 + 异常翻译。业务异常原样抛出，网络/解析异常折成中文。
     *
     * ⚠️ `block` 必须是 **suspend** lambda：内部会调用其它挂起函数（如 WBI 签名请求）。
     */
    private suspend fun <T> ioCall(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: BilibiliException) {
            throw e
        } catch (e: Exception) {
            throw toBiliException(e)
        }
    }

    private fun toBiliException(error: Throwable): BilibiliException {
        if (error is BilibiliException) return error
        val raw = error.message.orEmpty()
        return BilibiliException(
            when {
                error is IOException -> "网络连接失败，请检查网络后重试"
                raw.contains("HTTP 412") -> "请求太频繁，被 B 站限流了，请稍后再试"
                raw.contains("HTTP 403") -> "B 站拒绝了本次请求，请稍后再试"
                raw.contains("HTTP 404") -> "接口地址已失效（B 站可能调整了接口）"
                raw.contains("HTTP 5") -> "B 站服务暂时不可用，请稍后再试"
                raw.contains("JSON") || raw.contains("Unterminated") ->
                    "B 站返回的数据无法解析，请稍后重试"
                else -> "加载失败：${raw.ifBlank { "未知错误" }}"
            }
        )
    }

    // =====================================================================
    // 2026-11-18 扩展：分区 / 历史 / 稍后再看 / 收藏 / 动态 / 直播 / 弹幕 / 互动
    //
    // 全部复用本文件已有的 WBI 签名、Cookie 注入、统一请求头与异常翻译；
    // 新增功能不另起一套网络栈（见类注释「为什么单独抽一个 Repository」）。
    // =====================================================================

    /** 常用视频分区，rid 与 B 站 web 端一致。硬编码省一次请求，也避免接口改版整页空白。 */
    private val REGIONS = listOf(
        BiliRegion(0, "首页推荐", "★"),
        BiliRegion(1, "动画", "动"),
        BiliRegion(3, "音乐", "音"),
        BiliRegion(4, "游戏", "游"),
        BiliRegion(5, "娱乐", "娱"),
        BiliRegion(11, "电视剧", "剧"),
        BiliRegion(13, "番剧", "番"),
        BiliRegion(23, "电影", "影"),
        BiliRegion(36, "知识", "知"),
        BiliRegion(119, "鬼畜", "鬼"),
        BiliRegion(155, "时尚", "时"),
        BiliRegion(181, "生活", "生")
    )

    suspend fun fetchRegions(): List<BiliRegion> = ioCall { REGIONS }

    /**
     * 分区视频流（`x/web-interface/dynamic/region`）。
     *
     * 该接口免登录可用，字段与推荐流一致，直接复用 [parseVideoItems]。
     * `rid=0` 没有意义，调用方应走推荐接口，这里兜底回首页推荐。
     */
    suspend fun fetchRegionVideos(rid: Int, page: Int): BiliPageResult = ioCall {
        if (rid <= 0) return@ioCall fetchRecommend((page - 1).coerceAtLeast(0)).let {
            BiliPageResult(it, it.size >= 12)
        }
        val url = "$REGION_FEED_URL?ps=20&pn=${page.coerceAtLeast(1)}&rid=$rid"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
        val items = parseVideoItems(data?.optJSONArray("items"), onlyVideo = true)
            .distinctBy { it.bvid }
        BiliPageResult(items, hasMore = items.size >= 20)
    }

    /**
     * 观看历史（需登录）。`cursor.max` 是下一页游标，不是页码。
     *
     * 未登录时 `ensureOk` 会翻成「需要登录」——UI 必须捕获并展示登录引导，
     * 同时切到本地历史（见 `BiliLibraryStore`），不能把整页留白。
     */
    suspend fun fetchHistory(max: Long = 0L): Pair<List<BiliVideoItem>, Long> = ioCall {
        val url = "$HISTORY_URL?max=$max&ps=20&business=archive"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data") ?: return@ioCall emptyList<BiliVideoItem>() to 0L
        val items = data.optJSONArray("list").orEmptyEntries().mapNotNull { obj ->
            val history = obj.optJSONObject("history") ?: return@mapNotNull null
            val bvid = history.optString("bvid")
            if (bvid.isBlank()) return@mapNotNull null
            BiliVideoItem(
                bvid = bvid,
                title = history.optString("title").ifBlank { obj.optString("title") },
                coverUrl = normalizeImageUrl(history.optString("cover").ifBlank {
                    obj.optString("pic")
                }),
                upName = obj.optString("author_name").ifBlank { "未知UP主" },
                viewCount = obj.optInt("view_count", 0),
                durationSeconds = obj.optInt("duration", 0),
                pubdate = history.optLong("timestamp", 0L),
                mid = obj.optLong("mid", 0L)
            )
        }
        items to data.optLong("cursor").let { c ->
            if (c > 0) c else data.optJSONObject("cursor")?.optLong("max", 0L) ?: 0L
        }
    }

    /** 稍后再看（需登录），字段与推荐流一致。 */
    suspend fun fetchWatchLater(page: Int): BiliPageResult = ioCall {
        val url = "$WATCH_LATER_URL?pn=${page.coerceAtLeast(1)}&ps=20"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val data = json.optJSONObject("data")
        val items = parseVideoItems(data?.optJSONArray("list"), onlyVideo = true)
        BiliPageResult(items, hasMore = items.size >= 20)
    }

    /** 登录用户的收藏夹列表。@param mid 来自 [fetchNavState].mid。 */
    suspend fun fetchFavFolders(mid: Long): List<BiliFavFolder> = ioCall {
        if (mid <= 0L) return@ioCall emptyList()
        val json = JSONObject(AppHttp.getText("$FAV_FOLDER_URL?upmid=$mid", biliHeaders()))
        ensureOk(json)
        json.optJSONObject("data")?.optJSONArray("list").orEmptyEntries().map { obj ->
            BiliFavFolder(
                id = obj.optLong("id", obj.optLong("fid", 0L)),
                name = obj.optString("title").ifBlank { "未命名收藏夹" },
                count = obj.optInt("count", 0)
            )
        }.filter { it.id > 0L }
    }

    /** 某个收藏夹里的视频（需登录）。新旧接口字段不一致，两条都兼容。 */
    suspend fun fetchFavVideos(fid: Long, page: Int): BiliPageResult = ioCall {
        if (fid <= 0L) return@ioCall BiliPageResult(emptyList(), false)
        val url = "$FAV_VIDEO_URL?fid=$fid&biz_id=1&pn=${page.coerceAtLeast(1)}&ps=20"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        val arr = json.optJSONObject("data")?.optJSONArray("list")
        val items = mutableListOf<BiliVideoItem>()
        arr.orEmptyEntries().forEach { wrapper ->
            // 新接口每项包一层 `item`，老接口直接就是视频对象。
            val obj = wrapper.optJSONObject("item") ?: wrapper
            val bvid = obj.optString("bvid")
            if (bvid.isBlank()) return@forEach
            val owner = obj.optJSONObject("owner")
            items += BiliVideoItem(
                bvid = bvid,
                title = stripHtmlTags(obj.optString("title")).ifBlank { "（无标题）" },
                coverUrl = normalizeImageUrl(obj.optString("pic").ifBlank { obj.optString("cover") }),
                upName = owner?.optString("name").orEmpty().ifBlank { obj.optString("author") }
                    .ifBlank { "未知UP主" },
                viewCount = obj.optInt("view", obj.optInt("play", 0)),
                durationSeconds = obj.optInt("duration", 0),
                pubdate = obj.optLong("fav_time", obj.optLong("pubdate", 0L)),
                mid = owner?.optLong("mid", 0L) ?: 0L
            )
        }
        BiliPageResult(items.distinctBy { it.bvid }, hasMore = items.size >= 20)
    }

    /** `JSONArray?` → 非空 `List<JSONObject>`，省掉一堆 `0 until length()` 样板。 */
    private fun JSONArray?.orEmptyEntries(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ---- 动态 -------------------------------------------------------------

    /** 动态 feed。offset 是服务端游标，空串表示第一页。需要登录。 */
    suspend fun fetchDynamicFeed(offset: String = ""): Pair<List<BiliDynamicItem>, String> = ioCall {
        val params = mutableMapOf(
            "timezone_offset" to "-480",
            "type" to "all",
            "page" to "1"
        )
        if (offset.isNotBlank()) params["offset"] = offset
        val url = urlWithParams(DYNAMIC_FEED_URL, signWbi(params, wbiMixinKey()))
        val json = JSONObject(AppHttp.getText(url, biliHeaders("https://t.bilibili.com/")))
        ensureOk(json)
        val data = json.optJSONObject("data") ?: return@ioCall emptyList<BiliDynamicItem>() to ""
        val items = data.optJSONArray("items").orEmptyEntries().mapNotNull { obj ->
            val modules = obj.optJSONObject("modules") ?: return@mapNotNull null
            val author = modules.optJSONObject("module_author")
            val dynamic = modules.optJSONObject("module_dynamic")
            val archive = dynamic?.optJSONObject("major")?.optJSONObject("archive")
            val bvid = archive?.optString("bvid").orEmpty()
            val video = if (bvid.isNotBlank()) {
                BiliVideoItem(
                    bvid = bvid,
                    title = stripHtmlTags(archive?.optString("title").orEmpty())
                        .ifBlank { "（无标题）" },
                    coverUrl = normalizeImageUrl(archive?.optString("cover").orEmpty()),
                    upName = author?.optString("name").orEmpty().ifBlank { "未知UP主" },
                    viewCount = 0,
                    durationSeconds = 0,
                    pubdate = author?.optLong("pub_ts", 0L) ?: 0L,
                    mid = author?.optLong("mid", 0L) ?: 0L
                )
            } else {
                null
            }
            BiliDynamicItem(
                id = obj.optString("id_str").ifBlank { obj.optString("id") },
                upName = author?.optString("name").orEmpty().ifBlank { "未知UP主" },
                upAvatar = normalizeImageUrl(author?.optString("face").orEmpty()),
                text = dynamic?.optJSONObject("desc")?.optString("text").orEmpty()
                    .ifBlank { archive?.optString("title").orEmpty() },
                timeText = author?.optString("pub_time").orEmpty(),
                video = video
            )
        }
        items to data.optString("offset")
    }

    // ---- 直播 -------------------------------------------------------------

    /** 推荐直播间列表（免登录）。直播域名与 web API 不同，单独一个 root。 */
    suspend fun fetchLiveRooms(page: Int): List<BiliLiveRoom> = ioCall {
        val url = "$LIVE_RECOMMEND_URL?page=${page.coerceAtLeast(1)}&page_size=20"
        val json = JSONObject(AppHttp.getText(url, biliHeaders("https://live.bilibili.com/")))
        val data = json.optJSONObject("data") ?: return@ioCall emptyList()
        val arr = data.optJSONArray("list") ?: data.optJSONArray("room_list")
        arr.orEmptyEntries().mapNotNull { obj ->
            val roomId = obj.optLong("roomid", obj.optLong("room_id", 0L))
            if (roomId <= 0L) return@mapNotNull null
            BiliLiveRoom(
                roomId = roomId,
                title = obj.optString("title").ifBlank { "未命名直播间" },
                userName = obj.optString("uname").ifBlank { obj.optString("user_name") }
                    .ifBlank { "未知主播" },
                coverUrl = normalizeImageUrl(
                    obj.optString("user_cover").ifBlank { obj.optString("cover") }
                ),
                online = obj.optInt("online", obj.optInt("online_num", 0)),
                areaName = obj.optString("area_name").ifBlank { obj.optString("areaName") }
            )
        }
    }

    /** 直播 FLV/HLS 播放地址；返回 null 表示直播间未开播或接口没给地址。 */
    suspend fun fetchLivePlayUrl(roomId: Long): String? = ioCall {
        val url = "$LIVE_PLAY_URL?cid=$roomId&quality=4&platform=web"
        val json = JSONObject(AppHttp.getText(url, biliHeaders("https://live.bilibili.com/")))
        val data = json.optJSONObject("data") ?: return@ioCall null
        val durl = data.optJSONArray("durl")
        val first = durl?.optJSONObject(0)
        val urlValue = first?.optString("url").orEmpty()
            .ifBlank { data.optString("hls_url") }
        urlValue.takeIf { it.isNotBlank() }
    }

    // ---- 弹幕 -------------------------------------------------------------

    /** 拉取 XML 弹幕（`<d p="时间,模式,字号,颜色,...">文本</d>`）。 */
    suspend fun fetchDanmaku(cid: Long): List<BiliDanmaku> = ioCall {
        if (cid <= 0L) return@ioCall emptyList()
        val bytes = AppHttp.getBytes("$DM_XML_URL?oid=$cid", biliHeaders())
        parseDanmakuXml(decodeMaybeGzip(bytes))
    }

    /**
     * 弹幕接口有时回裸 XML、有时回 gzip 二进制（CDN 层决定），这里按 magic number 判断。
     * OkHttp 已自动解过一层 gzip，所以真遇到魔数说明是二次压缩，必须自己再解。
     */
    private fun decodeMaybeGzip(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            }.getOrDefault(String(bytes, Charsets.UTF_8))
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 用系统自带的 XmlPullParser 解析，不引入任何新依赖。 */
    private fun parseDanmakuXml(xml: String): List<BiliDanmaku> {
        if (xml.isBlank()) return emptyList()
        val result = mutableListOf<BiliDanmaku>()
        runCatching {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "d") {
                    val p = parser.getAttributeValue(null, "p").orEmpty().split(',')
                    val time = p.getOrNull(0)?.toFloatOrNull() ?: 0f
                    val mode = p.getOrNull(1)?.toIntOrNull() ?: 1
                    val size = p.getOrNull(2)?.toIntOrNull() ?: 25
                    val color = p.getOrNull(3)?.toIntOrNull() ?: 0xFFFFFF
                    val text = parser.nextText()
                    if (text.isNotBlank()) {
                        result += BiliDanmaku(time, mode, size, color, text)
                    }
                } else {
                    parser.next()
                }
                event = parser.eventType
            }
        }
        return result.sortedBy { it.timeSec }
    }

    // ---- 互动 -------------------------------------------------------------

    /** 从登录 Cookie 里取 CSRF token；未登录返回空串（接口会翻成「需要登录」）。 */
    private fun csrfToken(): String =
        sessionCookie?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("bili_jct=", ignoreCase = true) }
            ?.substringAfter('=').orEmpty()

    suspend fun actLike(bvid: String, like: Boolean): Boolean = ioCall {
        val url = "$LIKE_URL?bvid=${percentEncode(bvid)}&like=${if (like) 1 else 2}" +
            "&csrf=${csrfToken()}"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        true
    }

    /** 投 1 枚硬币；重复投币由 B 站接口自己拒绝并翻译成中文异常。 */
    suspend fun actCoin(bvid: String): Boolean = ioCall {
        val url = "$COIN_URL?bvid=${percentEncode(bvid)}&coin_num=1&coin_type=1" +
            "&section=0&csrf=${csrfToken()}"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        true
    }

    suspend fun actFollow(mid: Long, follow: Boolean): Boolean = ioCall {
        if (mid <= 0L) return@ioCall false
        val url = "$RELATION_URL?fid=$mid&act=${if (follow) 1 else 2}&re_src=11" +
            "&csrf=${csrfToken()}"
        val json = JSONObject(AppHttp.getText(url, biliHeaders()))
        ensureOk(json)
        true
    }

    /** 短视频流：先用推荐接口按短时长过滤，避免再找一个随时会改版的私有端点。 */
    suspend fun fetchShortVideos(freshIdx: Int): List<BiliVideoItem> = ioCall {
        fetchRecommend(freshIdx).filter { it.durationSeconds in 1..180 }
    }

    // -----------------------------------------------------------------------
    // 扫码登录（web 二维码链路）
    // -----------------------------------------------------------------------

    /** 二维码内容（给 App 扫）与轮询凭据，二者都不要在界面上直接展示 key。 */
    data class BiliLoginQr(val content: String, val key: String)

    /** 轮询状态。UI 只关心这四档，`message` 由 UI 自己给中文。 */
    enum class BiliQrState { WAITING, SCANNED, SUCCESS, EXPIRED }

    /**
     * 生成登录二维码。
     *
     * 用 passport 的公开接口而不是自己拼 `bilibili://` scheme：这个接口返回的
     * `url` 就是官方 App 认的内容，扫码后由 B 站服务端把登录态写进轮询结果。
     */
    suspend fun fetchLoginQrCode(): BiliLoginQr = ioCall {
        val json = JSONObject(AppHttp.getText(QR_GENERATE_URL, biliHeaders()))
        ensureOk(json)
        val data = json.getJSONObject("data")
        val qr = BiliLoginQr(
            content = data.optString("url"),
            key = data.optString("qrcode_key")
        )
        if (qr.content.isBlank() || qr.key.isBlank()) {
            throw BilibiliException("二维码生成失败，请稍后重试")
        }
        qr
    }

    /**
     * 轮询扫码结果：成功时第二个返回值是可直接保存的 Cookie 串。
     *
     * B 站的状态码：`86101` 未扫码、`86090` 已扫码待确认、`0` 成功、`86038` 已失效。
     * 注意**外层 `code` 只表示接口本身是否成功，扫码状态在内层 `data.code`**——
     * 把两者搞混会得到"一直显示未扫码"的假象。
     */
    suspend fun pollLoginQrCode(key: String): Pair<BiliQrState, String?> = ioCall {
        val json = JSONObject(
            AppHttp.getText("$QR_POLL_URL?qrcode_key=${percentEncode(key)}", biliHeaders())
        )
        ensureOk(json)
        val data = json.getJSONObject("data")
        when (data.optInt("code", -1)) {
            0 -> BiliQrState.SUCCESS to cookieFromLoginUrl(data.optString("url"))
            86101 -> BiliQrState.WAITING to null
            86090 -> BiliQrState.SCANNED to null
            86038 -> BiliQrState.EXPIRED to null
            else -> BiliQrState.WAITING to null
        }
    }

    /**
     * 从 `crossDomain?DedeUserID=…&SESSDATA=…&bili_jct=…` 里抠出 Cookie 串。
     *
     * 扫码成功后 B 站返回的是一个跳转 URL，登录态全在 query 里——这就是 WebView
     * 登录拿 Cookie 的等价物，所以直接复用 [saveSessionCookie] 的存储口径。
     * SESSDATA 里含 `%2C` 之类的转义，必须解码后再拼回 Cookie，否则接口会 401。
     */
    private fun cookieFromLoginUrl(url: String): String? {
        if (url.isBlank()) return null
        val query = url.substringAfter('?', "")
        if (query.isBlank()) return null
        val pairs = linkedMapOf<String, String>()
        query.split('&').forEach { item ->
            if (item.isBlank()) return@forEach
            val name = item.substringBefore('=')
            if (name.isBlank()) return@forEach
            val raw = item.substringAfter('=', "")
            pairs[name] = runCatching {
                java.net.URLDecoder.decode(raw, "UTF-8")
            }.getOrDefault(raw)
        }
        if (pairs["SESSDATA"].isNullOrBlank()) return null
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
