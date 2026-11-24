package com.morecheck.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.ui.common.pressScale
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「点热搜条目 → 看概况」（2026-10-30 需求 6；2026-11-03 按「官方 AI 总结优先」重做）。
 *
 * ## 为什么要有这个页面
 *
 * 原来点任意一条都直接跳系统浏览器：列表里只有一句标题，跳出去之前你不知道
 * 这条到底讲什么，回来就找不到位置了。这一页只解决"先看清楚是什么事"，
 * 真要读全文再点底部的「在浏览器中打开原文」。
 *
 * ## 概况的来源（2026-11-03 用户拍板：优先抓平台官方 AI 总结，没有再自行总结）
 *
 * 降级链路（从上到下，取到就停）：
 *
 * 1. **[BriefSource.OFFICIAL_AI] 平台官方 AI 总结** —— 直接读原网页里平台自己写的
 *    AI 总结区块。目前实测可得的只有 **百度搜索结果页的「AI 总结」**
 *    （HTML 里 `data-show="summary"` 的 `marklang` 区块，页脚写着「AI总结 N 篇结果生成」）。
 *    微博的官方总结（智搜 / AI 总结）**实测不可得**：`s.weibo.com` 一律回
 *    「Sina Visitor System」、带 visitor cookie 后跳登录页、`m.weibo.cn/api/…` 回 432、
 *    `weibo.com/ajax/…` 回 `{"ok":-100,…login.php}`，全部要登录态。详见交付说明。
 * 2. **[BriefSource.OFFICIAL] 平台官方摘要** —— 百度走热榜接口 `desc` 字段
 *    （`top.baidu.com/api/board?platform=pc&tab=realtime`，一次拿全榜，50 条里约 47 条有），
 *    其余平台退到页面的 `og:description` / `<meta name="description">` / 正文首段。
 * 3. **[BriefSource.AI] 自行总结** —— 官方都取不到时，把标题 + 已抓到的少量正文
 *    交给云端大模型写 2~3 句。
 * 4. **[BriefSource.NONE] 无法抓取** —— 连模型也失败时**明确显示「无法抓取」+ 原因 + 重试**，
 *    绝不留白（用户 2026-11-03 明确要求）。
 *
 * UI 上用**图标 + 文字**区分来源：抓取 = 云朵下载图标 +「抓取」，生成 = 魔棒图标 +「生成」，
 * 失败 = 断云图标 +「无法抓取」。**不只靠颜色**（色弱 / 深浅色都能分辨）。
 *
 * ## 为什么不读「官方 AI 总结」就用原来的 meta
 *
 * 百度搜索页的 meta description 实测是「来自百度的搜索结果」这类站点 slogan
 * （见 [sloganHints]），当概况展示等于没信息；而抓到的 AI 总结是真正的一句话答案。
 *
 * ⚠️ AI 通道借的是本仓库「去 AI 味」那个云应用（`deai-rewriter-64769`）的
 * `/.cloud/llm/chat/completions`：它是本项目唯一已开通 LLM 的云环境，密钥不落地，
 * 按 Origin 鉴权。**换环境时这两处（ENDPOINT / KEY）要一起换**，
 * 否则会 403 `access_denied: the request origin is not allowed for this client`。
 */
internal enum class BriefSource {
    /** 平台官方 AI 总结（抓取所得）。 */
    OFFICIAL_AI,

    /** 平台官方摘要：热榜 desc / meta description / 正文首段（抓取所得）。 */
    OFFICIAL,

    /** 本应用云端模型自行生成。 */
    AI,

    /** 官方与自行总结都失败 —— 页面显示「无法抓取」。 */
    NONE;

    /** 是否属于「抓取」而不是「生成」。 */
    val fetched: Boolean get() = this == OFFICIAL_AI || this == OFFICIAL
}

/**
 * 一条新闻概况。
 *
 * @param text 概况正文；[BriefSource.NONE] 时为空串。
 * @param source 来源，决定右上角徽标。
 * @param note 降级 / 失败说明（展示在概况下方的小字）。
 * @param origin 具体出处，展示在概况卡右上角（如「百度 · 官方 AI 总结」）。
 */
internal class NewsBrief(
    val text: String,
    val source: BriefSource,
    val note: String? = null,
    val origin: String = ""
)

/** 话题属于哪个平台 —— 决定走哪条「官方总结」通道。 */
private enum class Platform { BAIDU, WEIBO, OTHER }

internal object NewsBriefRepository {

    private const val LLM_ENDPOINT =
        "https://deai-rewriter-64769.app.workbuddy.host/.cloud/llm/chat/completions"
    private const val LLM_ORIGIN = "https://deai-rewriter-64769.app.workbuddy.host"
    private const val LLM_KEY =
        "wbpk_bX9XF0rkMz1SruPdhPMgHM_Huxhqo8K1NV8czyBi0A39KnO5XhxIBgg"

    /**
     * 概况用的模型：**快模型**。
     *
     * 概况是"一眼看完"的场景，等十几秒就失去意义；`deepseek-v4-flash` 实测
     * 首个字节约 1 秒、整段 2~4 秒，质量对"两三句话"完全够用。
     */
    private const val LLM_MODEL = "deepseek-v4-flash"

    /**
     * 百度热搜榜单接口（2026-11-03 实测：200，约 56KB，0.15s，无风控）。
     *
     * 每个条目带 `desc` —— 百度自己给这条热搜写的 1~3 句摘要（47/50 条有）。
     * 这条通道比逐条抓搜索页稳得多：搜索页在连续请求十几次后会被「百度安全验证」
     * 壳页拦掉（实测 200 + 1488 字节），而榜单接口一直正常。
     */
    private const val BAIDU_BOARD_ENDPOINT =
        "https://top.baidu.com/api/board?platform=pc&tab=realtime"
    private const val BAIDU_BOARD_REFERER = "https://top.baidu.com/board?tab=realtime"

    /**
     * 抓网页用的 UA：不少平台对非浏览器 UA 直接回 403 或空壳页。
     *
     * 百度**单独用桌面 UA**：实测 `www.baidu.com/s` 在移动 UA 下会切成 2.8MB 的
     * 移动版页面（AI 总结在 1.4MB 处、结构也不同），桌面 UA 拿到的是 1.03MB 的
     * PC 版（AI 总结在 745KB 处，`data-show="summary"` 结构稳定好解析）。
     */
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /**
     * 单页最多读这么多字节（非百度）。
     *
     * `meta description` 在 `<head>`、正文首段也在开头，200KB 足够覆盖；
     * 关键是**只读这么多就断开**——热搜链接大多是搜索页，动辄 1~2MB，
     * 整页读完既慢又容易超时（见 [readCapped]）。
     */
    private const val MAX_PAGE_BYTES = 200 * 1024

    /**
     * 百度搜索页的读取上限（2026-11-03 实测修订）。
     *
     * 老上限 200KB 是从「百度搜索页 1.9MB、读整页必超时」来的，但那个上限也
     * **一并把官方 AI 总结挡在了外面**：实测 PC 版整页 1.03MB，AI 总结区块在
     * 745KB~826KB（前面排了 10 张结果卡）。所以百度单独放宽到 1.2MB，
     * 并靠 [baiduAiSummarySeen] 做「读到答案就收线」——命中时实际只读约 830KB，
     * gzip 在线上的体积约 200KB。
     */
    private const val BAIDU_MAX_PAGE_BYTES = 1200 * 1024

    /** 缓存多久失效：百度榜单 5 分钟（更新很勤），失败 1 分钟内不重试。 */
    private const val BAIDU_BOARD_TTL_MS = 5 * 60 * 1000L
    private const val BAIDU_BOARD_ERROR_TTL_MS = 60 * 1000L

    /** 概况缓存：同一话题在本次进程内反复进入不必重复抓 / 重复计费。 */
    private val cache = HashMap<String, NewsBrief>()

    /**
     * 抓原文用：**自己手动跟跳转**（见 [fetchPageText]），所以关掉自动跟随；
     * 读超时 18 秒——抓不下来就交给 AI，没必要让用户干等。
     *
     * 2026-10-30：原先是 12 秒，但读的是整页（见 [readCapped]），
     * 大页面在移动网络下必超时；改成边读边截断后 12 秒其实够用，
     * 仍放到 18 秒是为了给「连接慢 + 服务端首字节慢」这种叠加情况留余量。
     * 2026-11-03：百度要读到 ~800KB（gzip 后约 200KB）才见到 AI 总结，
     * 18 秒仍是够的（实测整页 1 秒内返回），不改。
     */
    private val pageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 流式接口要长读超时：模型排队 + 冷启动时第一个字节可能十几秒才来。
     * 复用 AppHttp 的客户端不行——它的 readTimeout 是 10 秒（那是给普通 JSON 接口定的）。
     */
    private val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 百度榜单快照（标题 → 官方摘要）。@Synchronized 保证同一时刻只拉一次。 */
    private class BaiduBoard(
        val fetchedAt: Long,
        val summaries: Map<String, String>,
        val error: String?
    )

    @Volatile
    private var baiduBoard: BaiduBoard? = null

    /**
     * 取一条话题的概况。
     *
     * 顺序固定：官方 AI 总结 → 官方摘要 → 自行总结 → 无法抓取。**不并发**：
     * 上面几层通常几百毫秒到一两秒就回来，并发会白烧一次模型额度。
     */
    suspend fun brief(item: HotSearchItem): NewsBrief = withContext(Dispatchers.IO) {
        val key = item.title + "\u0000" + item.url
        // 只缓存**成功**的结果：失败（NONE）必须能靠「重试」重新抓，缓存住就点不动了。
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val result = buildBrief(item)
        if (result.source != BriefSource.NONE) {
            synchronized(cache) { cache[key] = result }
        }
        result
    }

    /** 真正的降级链路（同步，调用方已在 IO 线程）。 */
    private fun buildBrief(item: HotSearchItem): NewsBrief {
        val platform = platformOf(item)
        val hasUrl = item.url.startsWith("https://", ignoreCase = true)

        var pageHtml: String? = null
        var pageError: String? = null
        if (hasUrl) {
            val baidu = platform == Platform.BAIDU
            val stopWhen: ((String) -> Boolean)? =
                if (baidu) (::baiduAiSummarySeen) else null
            val referer = runCatching {
                val uri = java.net.URI(item.url)
                "${uri.scheme}://${uri.host}/"
            }.getOrNull()
            // 2026-11-09 需求 4：多候选重试。
            // 之前只用一种 UA、一次机会：微博 / 知乎 / 抖音对桌面 UA 直接回登录墙或空壳，
            // 换移动 UA + 站点 Referer 往往就能拿到正文；微博 PC 详情页几乎必然被拦，
            // 改走 m.weibo.cn/detail/<id> 镜像最稳。
            val candidates = buildList {
                add(Triple(item.url, if (baidu) DESKTOP_UA else MOBILE_UA, referer))
                if (!baidu) {
                    add(Triple(item.url, DESKTOP_UA, referer))
                    weiboMirror(item.url)?.let { mirror ->
                        add(Triple(mirror, MOBILE_UA, "https://m.weibo.cn/"))
                    }
                }
            }
            var lastError: Throwable? = null
            for ((pageUrl, ua, siteReferer) in candidates) {
                try {
                    val html = fetchPageText(
                        url = pageUrl,
                        maxBytes = if (baidu) BAIDU_MAX_PAGE_BYTES else MAX_PAGE_BYTES,
                        userAgent = ua,
                        stopWhen = stopWhen,
                        referer = siteReferer
                    )
                    // 登录墙 / 访客壳页：换下一个候选，而不是当场判死。
                    if (loginShellHints.any { html.contains(it) }) {
                        lastError = IllegalStateException("原网页需要登录，抓不到正文")
                        continue
                    }
                    pageHtml = html
                    pageError = null
                    break
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            if (pageHtml == null) {
                pageError = friendlyReason(lastError ?: IllegalStateException("抓取失败"))
            }
        }

        // ① 平台官方 AI 总结（目前只有百度实测可得）
        if (platform == Platform.BAIDU && pageHtml != null) {
            extractBaiduAiSummary(pageHtml)?.let {
                return NewsBrief(it, BriefSource.OFFICIAL_AI, null, "百度 · 官方 AI 总结")
            }
        }

        // ② 平台官方摘要：百度热搜榜的 desc 最稳，优先于页面 meta
        if (platform == Platform.BAIDU) {
            baiduBoardSummary(item)?.let {
                return NewsBrief(it, BriefSource.OFFICIAL, null, "百度热搜 · 官方摘要")
            }
        }

        // ③ 页面自带的官方简介（meta description / 正文首段）
        if (pageHtml != null) {
            extractSummary(pageHtml)?.let {
                return NewsBrief(it, BriefSource.OFFICIAL, null, "原文 · 官方简介")
            }
        }

        // ④ 自行总结
        val why = officialMissReason(platform, pageError, hasUrl)
        val context = pageHtml?.let { plainTextOf(it, 1200) }.orEmpty()
        return try {
            val text = aiSummary(item.title, item.sourceNames, context)
            NewsBrief(text, BriefSource.AI, "$why，以下概况由云端模型生成。", "云端模型 · 自行总结")
        } catch (e: Throwable) {
            // ⑤ 都失败：明确「无法抓取」+ 原因，交给页面渲染重试按钮。
            NewsBrief("", BriefSource.NONE, "$why；云端模型也没能生成（${friendlyAiReason(e)}）")
        }
    }

    /** 把 weibo.com/<uid>/<mid>（或带 query 的详情页）换成 m.weibo.cn/detail/<mid>。 */
    private fun weiboMirror(url: String): String? {
        if (!url.contains("weibo.com", ignoreCase = true)) return null
        val mid = Regex("/(?:detail/)?(\\d{8,})").find(url)
            ?.groupValues?.getOrNull(1) ?: return null
        return "https://m.weibo.cn/detail/$mid"
    }

    // ── 平台判定 ────────────────────────────────────────────────────────────

    /**
     * 话题属于哪个平台。
     *
     * **先看 URL 主机名，再看 sourceNames**：聚合榜里存在「来源写着百度、链接其实是微博」
     * 的条目，按主机名判定才不会拿百度的榜单摘要去套微博的话题。
     */
    private fun platformOf(item: HotSearchItem): Platform {
        val url = item.url.lowercase()
        val names = item.sourceNames
        return when {
            url.contains("baidu.com") -> Platform.BAIDU
            url.contains("weibo.com") || url.contains("weibo.cn") -> Platform.WEIBO
            url.isBlank() && names.contains("百度") -> Platform.BAIDU
            url.isBlank() && names.contains("微博") -> Platform.WEIBO
            else -> Platform.OTHER
        }
    }

    // ── 官方 AI 总结（百度）────────────────────────────────────────────────

    /** 百度 AI 总结区块的锚点（HTML 原文里是 `data-show="summary"`）。 */
    private const val BAIDU_AI_ANCHOR = "data-show=\"summary\""

    /**
     * 读到「答案已经到手」就收线。
     *
     * 判据：先出现 `data-show="summary"`，之后再出现该组件的页脚 `footer-contact`
     * （页脚文案是「AI总结 N 篇结果生成」）。两者都齐了说明 AI 总结区块已经读完，
     * 没必要继续把整页 1MB 拖下来。
     *
     * @param snapshot 已读字节的 ISO-8859-1（逐字节）视图——标记全是 ASCII，能直接命中。
     */
    private fun baiduAiSummarySeen(snapshot: String): Boolean {
        val anchor = snapshot.indexOf(BAIDU_AI_ANCHOR)
        if (anchor < 0) return false
        return snapshot.indexOf("footer-contact", anchor) > anchor
    }

    /**
     * 从百度搜索结果页里抽官方「AI 总结」。
     *
     * 结构（2026-11-03 实测）：
     * ```
     * <div class="ai-entry"><div data-show="summary" ...>
     *   <div class="marklang"><p class="marklang-paragraph">…答案…</p>…</div>
     * </div></div>
     * <div class="footer-container"><div class="footer-contact">AI总结6篇结果生成</div>
     * ```
     * 只取**第一段**（`marklang-paragraph`）：后面是「他具体怎么练 / 普通人能不能照着做」
     * 这类分节展开，放进"概况"太长也太像提纲。
     *
     * 取不到返回 null（交给下一层）。
     */
    private fun extractBaiduAiSummary(html: String): String? {
        val anchor = html.indexOf(BAIDU_AI_ANCHOR)
        if (anchor < 0) return null
        val blockStart = html.indexOf("<div class=\"marklang\">", anchor)
        if (blockStart < 0) return null
        val paraStart = html.indexOf("<p class=\"marklang-paragraph\">", blockStart)
        // 第一段的结构偶尔会变（比如整段用 div），兜底取到页脚为止的整块。
        val segment = if (paraStart >= 0) {
            val paraEnd = html.indexOf("</p>", paraStart)
            val blockEnd = html.indexOf("footer-contact", blockStart)
            val end = when {
                paraEnd > paraStart && (blockEnd < 0 || paraEnd < blockEnd) -> paraEnd + 4
                blockEnd > blockStart -> blockEnd
                else -> minOf(html.length, blockStart + 20_000)
            }
            html.substring(paraStart, end)
        } else {
            val blockEnd = html.indexOf("footer-contact", blockStart)
            val end = if (blockEnd > blockStart) blockEnd else minOf(html.length, blockStart + 20_000)
            html.substring(blockStart, end)
        }
        val text = cleanText(segment)
        if (text.length < 20) return null
        if (sloganHints.any { text.contains(it) }) return null
        if (looksLikeListJunk(text)) return null
        return text.take(600)
    }

    // ── 官方摘要（百度热搜榜 desc）────────────────────────────────────────

    /**
     * 从百度热搜榜单快照里按标题取官方摘要。
     *
     * 匹配口径：先拿 URL 里的 `wd`（最准），再退到标题；两边都做
     * [normalizeMatchKey]（去空白与标点）后**整串相等**——不做包含匹配，
     * 免得把相近话题的摘要套错。
     */
    private fun baiduBoardSummary(item: HotSearchItem): String? {
        val board = baiduBoardSnapshot() ?: return null
        for (key in candidateKeys(item)) {
            board.summaries[key]?.let { return it }
        }
        return null
    }

    private fun candidateKeys(item: HotSearchItem): List<String> {
        val keys = LinkedHashSet<String>()
        wdOf(item.url)?.let { keys.add(normalizeMatchKey(it)) }
        if (item.title.isNotBlank()) keys.add(normalizeMatchKey(item.title))
        return keys.filter { it.length >= 4 }
    }

    /** 拉一次百度热榜（带 TTL 缓存）；失败也会缓存 1 分钟，避免反复撞墙。 */
    @Synchronized
    private fun baiduBoardSnapshot(): BaiduBoard? {
        val now = System.currentTimeMillis()
        baiduBoard?.let { cached ->
            val ttl = if (cached.error == null) BAIDU_BOARD_TTL_MS else BAIDU_BOARD_ERROR_TTL_MS
            if (now - cached.fetchedAt < ttl) return if (cached.error == null) cached else null
        }
        val snapshot = try {
            val text = httpGetText(
                BAIDU_BOARD_ENDPOINT,
                mapOf(
                    "Referer" to BAIDU_BOARD_REFERER,
                    "User-Agent" to DESKTOP_UA
                )
            )
            BaiduBoard(now, parseBaiduBoard(text) ?: emptyMap(), null)
        } catch (e: Throwable) {
            BaiduBoard(now, emptyMap(), friendlyReason(e))
        }
        baiduBoard = snapshot
        return if (snapshot.error == null) snapshot else null
    }

    /**
     * 解析百度热榜 JSON：`{success:true, data:{cards:[{component:"hotList", content:[{desc, word, query, url}]}]}}`。
     *
     * 只关心 `desc`（官方摘要）。`wise` 平台那份是精简版、没有 `desc`，别换。
     */
    private fun parseBaiduBoard(text: String): Map<String, String>? = runCatching {
        val root = JSONObject(text)
        if (!root.optBoolean("success", false)) return@runCatching null
        val cards = root.optJSONObject("data")?.optJSONArray("cards") ?: return@runCatching null
        val summaries = HashMap<String, String>()
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i) ?: continue
            val content = card.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val obj = content.optJSONObject(j) ?: continue
                val desc = obj.optString("desc", "").trim()
                if (desc.length < 15) continue
                val keys = listOf(
                    obj.optString("word", ""),
                    obj.optString("query", ""),
                    wdOf(obj.optString("url", "")).orEmpty(),
                    wdOf(obj.optString("rawUrl", "")).orEmpty()
                )
                for (raw in keys) {
                    val key = normalizeMatchKey(raw)
                    if (key.length >= 4) summaries[key] = desc
                }
            }
        }
        if (summaries.isEmpty()) null else summaries
    }.getOrNull()

    /** 从 `https://www.baidu.com/s?wd=xxx&sa=...` 里取回 `wd`（解码后）。 */
    private fun wdOf(url: String): String? {
        if (url.isBlank()) return null
        val rawQuery = try {
            java.net.URI(url).rawQuery
        } catch (_: Throwable) {
            null
        } ?: return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx)
            if (name != "wd" && name != "word") continue
            return try {
                java.net.URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8.name())
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    /** 比对用的归一化：去掉空白与中英文标点，再统一小写。 */
    private fun normalizeMatchKey(raw: String): String {
        if (raw.isEmpty()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch.isWhitespace() -> Unit
                ch in MATCH_IGNORED_PUNCT -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    // ── 官方摘要（网页 meta / 正文首段）────────────────────────────────────

    /**
     * 抓原网页 HTML。
     *
     * **边读边数，够 [maxBytes] 就收线**，不是读完整个响应再截断。读到
     * [stopWhen] 判定"目标内容已到手"时更早停（见 [readCapped]）。
     *
     * 2026-10-30 实测（模拟器 + 移动网络）：百度搜索页正文 1.9MB，原来
     * `body.string()` 要把整页读完才算成功，12 秒读超时几乎必然踩到，
     * 于是**每条**热搜都退化成「网络异常」→ 只能拿 AI 编的概况。
     */
    private fun fetchPageText(
        url: String,
        maxBytes: Int,
        userAgent: String,
        stopWhen: ((String) -> Boolean)? = null,
        /** 站点自己的 Referer：国内几家门户没有它会被当成盗链直接回 403/空页。 */
        referer: String? = null
    ): String {
        var current = url
        // 手动跟最多 3 跳：平台链接常常 302 到 https 落地页（头条、B 站），
        // 但**不许**跳到明文 http（微博就是这么干的，系统会拦）。自己走跳转才能
        // 区分"https→https（可以抓）"和"https→http（抓不了，交给 AI）"。
        repeat(3) {
            val request = Request.Builder()
                .url(current)
                .get()
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("User-Agent", userAgent)
                .apply { if (referer != null) header("Referer", referer) }
                .build()
            pageClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location").orEmpty()
                    if (next.isBlank()) throw IllegalStateException("原文跳转到空地址")
                    val absolute = java.net.URL(java.net.URL(current), next).toString()
                    if (!absolute.startsWith("https://", ignoreCase = true)) {
                        throw IllegalStateException("原文跳转到非 https 页面")
                    }
                    current = absolute
                    return@use
                }
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("空响应")
                val html = readCapped(body, maxBytes, stopWhen)
                // 反爬 / 人机验证页：状态码是 200，但正文其实是"百度安全验证""环境异常"
                // 这种壳子。当成正文喂给模型只会污染概括（实测模型会照着念"页面显示
                // 验证提示"），所以直接判为抓取失败。
                if (antiBotHints.any { html.contains(it) }) {
                    throw IllegalStateException("原网页需要人机验证，抓不到正文")
                }
                return html
            }
        }
        throw IllegalStateException("原文跳转次数过多")
    }

    /**
     * 边读边数，够 [maxBytes] 就停；有 [stopWhen] 时，读到它说「够了」也停。
     *
     * 编码：先看响应头 `Content-Type` 的 charset；没有再从开头 2KB 里嗅
     * `charset=gb2312/gbk`（部分地方新闻网还是 GBK），最后回落 UTF-8。
     * 截断点可能砍在多字节字符中间，所以解码时把结尾那个替换字符去掉。
     *
     * [stopWhen] 的入参是「已读字节的 ISO-8859-1 视图」：判定标记全是 ASCII，
     * 逐字节映射后可以直接做 `indexOf`，不必每轮把缓冲区完整解码成中文。
     */
    private fun readCapped(
        body: ResponseBody,
        maxBytes: Int,
        stopWhen: ((String) -> Boolean)? = null
    ): String {
        val stream = body.byteStream()
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        try {
            while (buffer.size() < maxBytes) {
                val n = stream.read(chunk, 0, minOf(chunk.size, maxBytes - buffer.size()))
                if (n <= 0) break
                buffer.write(chunk, 0, n)
                // 前面几十 KB 不可能出现目标区块，先攒着，省掉一轮轮全量扫描。
                if (stopWhen != null && buffer.size() >= STOP_CHECK_AFTER_BYTES) {
                    val snapshot = buffer.toString(Charsets.ISO_8859_1.name())
                    if (stopWhen(snapshot)) break
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        val bytes = buffer.toByteArray()
        val headerCharset = runCatching { body.contentType()?.charset(Charsets.UTF_8) }.getOrNull()
        val charset = headerCharset ?: sniffCharset(bytes)
        return String(bytes, charset).removeSuffix("\uFFFD")
    }

    /** 从首 2KB 里嗅 `<meta charset=...>`；认不出就 UTF-8。 */
    private fun sniffCharset(bytes: ByteArray): java.nio.charset.Charset {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase()
        val name = Regex("""charset\s*=\s*["']?([a-z0-9_\-]+)""").find(head)?.groupValues?.getOrNull(1)
        return when {
            name == null -> Charsets.UTF_8
            name.startsWith("gb") -> Charset.forName("GBK")
            else -> runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        }
    }

    /**
     * 把底层异常翻成一句人话。
     *
     * 概况页的补充说明会直接展示这句话，所以不能把 OkHttp 的英文（
     * `CLEARTEXT communication to … not permitted`）原样甩给用户。
     * 反过来也不能笼统成「网络异常」——抓取失败最常见的原因其实是
     * **超时**和**反爬**，前者用户等一等就好，后者根本不该重试。
     */
    private fun friendlyReason(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("CLEARTEXT", ignoreCase = true) -> "原文只支持明文 http，系统不允许抓取"
            raw.startsWith("HTTP 4") -> "原网页拒绝访问（$raw）"
            raw.startsWith("HTTP 5") -> "原网页暂时不可用（$raw）"
            // 下面这几类是本模块自己抛的，文案已经是人话，原样带出。
            raw.contains("跳转") || raw.contains("人机验证") || raw.contains("登录") ||
                raw.contains("空响应") || raw.contains("次数过多") -> raw
            e is java.net.SocketTimeoutException -> "原网页太久没响应（抓取超时）"
            e is java.net.UnknownHostException -> "解析不到原网页域名（当前网络不可用）"
            e is javax.net.ssl.SSLException -> "与原网页建立加密连接失败"
            else -> "网络异常，原网页打不开"
        }
    }

    /** 云端模型失败的说明（展示在「无法抓取」的原因里）。 */
    private fun friendlyAiReason(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.startsWith("HTTP 4") -> "云端模型拒绝请求（$raw）"
            raw.startsWith("HTTP 5") -> "云端模型暂时不可用（$raw）"
            raw.contains("模型没有返回内容") || raw.contains("空响应") -> raw
            e is java.net.SocketTimeoutException -> "云端模型太久没响应（生成超时）"
            e is java.net.UnknownHostException -> "解析不到云端模型域名（当前网络不可用）"
            else -> raw.ifBlank { "原因未知" }
        }
    }

    /**
     * 官方层没取到时的说明（拼进 AI 概况的小字 / 「无法抓取」的原因）。
     *
     * 微博要单独写死文案：它的问题不是"网页打不开"，而是**官方总结全在登录态后面**
     * （实测 s.weibo.com 回 Sina Visitor System、API 回 432 / ok:-100）。
     * 这种情况把 OkHttp 的错误原文摆给用户毫无意义。
     */
    private fun officialMissReason(platform: Platform, pageError: String?, hasUrl: Boolean): String {
        if (!hasUrl) return "这条没有可打开的原文链接"
        return when (platform) {
            Platform.WEIBO -> "微博的官方总结要登录才能看，本机抓不到"
            Platform.BAIDU -> if (pageError != null) {
                "百度官方总结没取到（$pageError）"
            } else {
                "百度官方总结没取到（页面里没有 AI 总结 / 官方摘要）"
            }
            Platform.OTHER -> if (pageError != null) {
                "原文打不开（$pageError）"
            } else {
                "原文没有可用的官方简介"
            }
        }
    }

    private val metaPatterns = listOf(
        // 常规顺序：<meta property="og:description" content="…">
        Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["'](?:og:description|description)["'][^>]*?""" +
                """content\s*=\s*["']([^"']{10,})["']""",
            RegexOption.IGNORE_CASE
        ),
        // 属性顺序反过来的写法：<meta content="…" name="description">
        Regex(
            """<meta[^>]+content\s*=\s*["']([^"']{10,})["'][^>]*?""" +
                """(?:property|name)\s*=\s*["'](?:og:description|description)["']""",
            RegexOption.IGNORE_CASE
        ),
        // 微博等平台的分享描述
        Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["'](?:og:title|twitter:description)["'][^>]*?""" +
                """content\s*=\s*["']([^"']{10,})["']""",
            RegexOption.IGNORE_CASE
        )
    )

    /**
     * 反爬壳子页的特征片段（命中即认为"没抓到真正的正文"）。
     *
     * 状态码 200 但正文其实是"百度安全验证""环境异常"这种壳子时，当成正文喂给模型
     * 只会污染概括（实测模型会照着念"页面显示验证提示"）。
     * 百度在连续请求十几次后会开始回这种壳页（2026-11-03 实测：200 + 1488 字节），
     * 命中后本层直接判失败、退到百度热榜的官方摘要。
     */
    private val antiBotHints = listOf(
        "百度安全验证", "请输入验证码", "环境异常", "访问验证", "滑动验证",
        "Just a moment", "Enable JavaScript and cookies to continue"
    )

    /**
     * 登录墙 / 访客系统的特征片段（命中即认为正文是"请先登录"而不是新闻）。
     *
     * 实测：微博未登录时 `s.weibo.com/weibo?q=…` 返回 200，但正文是
     * 「Sina Visitor System」的指纹脚本页；带上 visitor cookie 后直接跳登录页。
     * 这类页面里的中文全是界面文案，喂给模型只会得到"页面提示需要登录"这种废概括。
     */
    private val loginShellHints = listOf(
        "Sina Visitor System",
        "passport.weibo.com/sso",
        "passport.weibo.com/visitor"
    )

    /**
     * 明显是"站点自我介绍"而不是"这条新闻"的句子。
     *
     * 实测：B 站搜索页的 description 是"bilibili 为您提供…相关的视频、番剧…"，
     * 百度搜索页是"来自百度的搜索结果"——这些字数够长，但跟具体新闻毫无关系，
     * 直接当概况展示比 AI 编的还差。命中这些特征就当作"没有简介"。
     */
    private val sloganHints = listOf(
        "为您提供", "搜索结果", "国内知名", "百度一下", "哔哩哔哩", "视频、番剧"
    )

    /**
     * 从 HTML 里取一句像样的简介；取不到返回 null（交给 AI）。
     *
     * 判定门槛：清洗后 25~400 字。太短（"百度一下，你就知道"这类站点 slogan）
     * 没有信息量，宁可去问模型；太长（整页塞进 meta）截断到 400 字。
     */
    private fun extractSummary(html: String): String? {
        for (p in metaPatterns) {
            val raw = p.find(html)?.groupValues?.getOrNull(1) ?: continue
            val text = cleanText(raw)
            if (text.length < 25) continue
            if (sloganHints.any { text.contains(it) }) continue
            if (looksLikeListJunk(text)) continue
            return text.take(400)
        }
        // 退路：正文首段（<p> 拼起来到 60 字就停）。
        val paragraphs = Regex("""<p[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html)
            .map { cleanText(it.groupValues[1]) }
            .filter { it.length >= 20 }
            .take(3)
            .joinToString(" ")
        if (paragraphs.length >= 25 && !looksLikeListJunk(paragraphs)) return paragraphs.take(400)
        return null
    }

    /**
     * 判断这段文字是不是"搜索结果列表的碎片"，而不是一句成句的简介。
     *
     * 实测 B 站搜索页：正文首段拼出来是
     * 「05:53 受权发布 | 中国共产党第 二十届 中央委员会第三次全体会议 … 阅读 23:56 …」，
     * 一堆时间戳 + 竖线拼在一起。这种文字当概况展示等于把列表原样搬过来，
     * 比让模型重写一句差得多。
     */
    private fun looksLikeListJunk(text: String): Boolean {
        val pipes = text.count { it == '|' }
        val timestamps = Regex("""\d{1,2}:\d{2}""").findAll(text).count()
        return pipes >= 2 || timestamps >= 2
    }

    /**
     * 去掉标签、脚本、样式、注释与多余空白，并把常见实体还原成字符。
     *
     * 2026-11-03 补三件事（都是为了让百度 AI 总结读起来是句人话）：
     * - 先剥 HTML 注释：百度 AI 总结里夹着 `<!--ml-data:{…citation…}-->` 的引用数据，
     *   里面可能自带 `>`，先按注释整体删掉，才不会把后面的正文一起吃掉；
     * - 去零宽字符：百度正文里有 `\u200c`(ZWNJ) 分隔强调，留着会显示成空格；
     * - 去中文标点前的空格：`<mark><strong>是真的</strong></mark>。` 拆完标签会变成
     *   「是真的 。」，补一步把「空格 + 中文标点」压掉。
     */
    private fun cleanText(raw: String): String {
        var s = raw
        s = s.replace(Regex("""<!--[\s\S]*?-->"""), " ")
        s = s.replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""<[^>]+>"""), " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[\\u200b-\\u200f\\u202a-\\u202e\\u2060\\ufeff\\u00ad]"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = Regex("\\s+([，。！？；：、）】》])").replace(s) { it.groupValues[1] }
        s = Regex("([（【《])\\s+").replace(s) { it.groupValues[1] }
        return s
    }

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        out = Regex("""&#(\d{2,5});""").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        }
        out = Regex("""&#x([0-9a-fA-F]{2,5});""").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: return@replace m.value
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        }
        return out
    }

    /** 给模型的参考正文：去掉标签后取前 N 字。 */
    private fun plainTextOf(html: String, max: Int): String = cleanText(html).take(max)

    // ── AI 兜底 ─────────────────────────────────────────────────────────────

    private const val SYSTEM_PROMPT =
        "你在为新闻聚合应用写「概况」。用中文回答：2~3 句话，不超过 110 字；" +
            "只陈述可核实的事实，不猜测原因、不评价、不加标题或列表符号；" +
            "信息不足时只说清楚这个话题是什么，不要编造细节。"

    /**
     * 调云端模型写概况（SSE 流式，边收边拼）。
     *
     * 为什么用流式：接口**只提供流式**；顺带的好处是首字一到就能先渲染，
     * 用户不会对着空白等整段。
     */
    private fun aiSummary(title: String, sources: String, context: String): String {
        val user = buildString {
            append("话题：").append(title)
            if (sources.isNotBlank()) append("\n来源：").append(sources)
            if (context.isNotBlank()) {
                append("\n页面正文（可能有噪音，仅供参考）：\n")
                append(context)
            } else {
                append("\n（没有抓到正文，请仅依据话题本身的公开含义作答，不要编造具体时间、数字、人名）")
            }
            append("\n请写出概况。")
        }
        val payload = JSONObject().apply {
            put("model", LLM_MODEL)
            put("stream", true)
            put(
                "messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", user))
                }
            )
        }
        val request = Request.Builder()
            .url(LLM_ENDPOINT)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Origin", LLM_ORIGIN)
            .header("Referer", "$LLM_ORIGIN/")
            .header("Accept", "text/event-stream")
            .header("x-wb-webapp-access-key", LLM_KEY)
            .build()

        streamClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // 403 基本就是 Origin 白名单 / 密钥不匹配，把状态码带出去便于定位。
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("空响应")
            val source = body.source()
            val sb = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val delta = runCatching {
                    JSONObject(data)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                }.getOrDefault("")
                if (delta.isNotEmpty()) sb.append(delta)
                // 概况只要一段，超出就停（省流量也省时间）。
                if (sb.length > 600) break
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) throw IllegalStateException("模型没有返回内容")
            return text
        }
    }

    /** 低于这个已读量不做「够了没」扫描（目标区块不可能这么靠前）。 */
    private const val STOP_CHECK_AFTER_BYTES = 256 * 1024

    /** 归一化时忽略的标点（中英文都要，百度标题里全角冒号很常见）。 */
    private const val MATCH_IGNORED_PUNCT =
        "：:，,。.！!？?、；“”\"'‘’（）()《》<>【】[]·…—-~～|｜/\\"
}

// ─────────────────────────────────────────────────────────────────────────────
// 平台跳转（2026-11-03 需求：同一条新闻可能同时在多个平台的热搜里）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 概况页「换个平台看这条」用的平台定义。
 *
 * ## 为什么按平台**现拼搜索地址**，而不是存每个平台的链接
 *
 * `HotSearchItem` 只有一条 `url`（榜单里那条的落地链接），但它同时带着
 * `sourceNames`（形如「微博 · B站 · 百度 · 头条」）——同一条热搜往往在好几个平台
 * 同时上榜，而接口里**没有**每个平台各自的条目链接。所以只能按平台规则拼搜索页
 * 地址（2026-11-03 宿主机实测可用性见交付说明）。
 *
 * @param name 中文平台名，必须与 [com.morecheck.ui.lab.platformName] 的输出一致
 *   （`sourceNames` 就是用它拼出来的）。
 * @param color 品牌色（只用来画小圆点 / 高亮描边，不做文字色）。
 * @param searchUrl 用主题词的纯文本拼出该平台的搜索地址（内部已做 URL 编码）。
 */
private class NewsPlatform(
    val name: String,
    val color: Color,
    val searchUrl: (String) -> String
)

/** URL 查询参数编码：`URLEncoder` 会把空格编成 `+`，统一换回 `%20`（路径段里 `+` 是字面加号）。 */
private fun encodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

/**
 * 支持跳转的平台白名单（顺序 = 胶囊的默认顺序）。
 *
 * **只收实测过地址形态的平台**：2026-11-03 宿主机逐个请求确认——
 * 百度 / 头条 / 腾讯新闻（`news.qq.com/search?query=`，页面标题回「…-搜索」）/ 抖音 直接 200；
 * 微博 / B站 对移动 UA 是 302 到各自的移动版搜索页（浏览器会跟），地址本身有效；
 * 知乎对脚本 403（反爬），但这是它标准的搜索地址，浏览器打开正常。
 * 小红书 的 `search_result` 实测回「页面不见了」，**故意不收**——宁可不显示也不给死链。
 */
private val HOME_PLATFORMS = listOf(
    NewsPlatform("百度", Color(0xFF2932E1)) { "https://www.baidu.com/s?wd=${encodeQuery(it)}" },
    NewsPlatform("微博", Color(0xFFE6162D)) { "https://s.weibo.com/weibo?q=${encodeQuery("#$it#")}" },
    NewsPlatform("B站", Color(0xFFFB7299)) { "https://search.bilibili.com/all?keyword=${encodeQuery(it)}" },
    NewsPlatform("头条", Color(0xFFED2024)) { "https://so.toutiao.com/search?keyword=${encodeQuery(it)}" },
    NewsPlatform("知乎", Color(0xFF0084FF)) { "https://www.zhihu.com/search?type=content&q=${encodeQuery(it)}" },
    NewsPlatform("腾讯新闻", Color(0xFF1570EF)) { "https://news.qq.com/search?query=${encodeQuery(it)}" },
    NewsPlatform("抖音", Color(0xFF161823)) { "https://www.douyin.com/search/${encodeQuery(it)}" },
    NewsPlatform("微信", Color(0xFF07C160)) {
        "https://weixin.sogou.com/weixin?type=2&query=${encodeQuery(it)}"
    }
)

/**
 * 由 URL 主机推断「这条的原文来自哪个平台」。
 *
 * 用来：① 高亮胶囊（当前来源）；② 单平台榜 `sourceNames` 为空时仍能显示那一项。
 * 顺序有讲究：`s.weibo.com` 先判 weibo，`news.qq.com` 用 `qq.com` 收口。
 */
private fun platformOfUrl(url: String): NewsPlatform? {
    val u = url.lowercase()
    return when {
        u.contains("baidu.com") -> HOME_PLATFORMS.first { it.name == "百度" }
        u.contains("weibo.com") || u.contains("weibo.cn") -> HOME_PLATFORMS.first { it.name == "微博" }
        u.contains("bilibili.com") -> HOME_PLATFORMS.first { it.name == "B站" }
        u.contains("toutiao.com") -> HOME_PLATFORMS.first { it.name == "头条" }
        u.contains("zhihu.com") -> HOME_PLATFORMS.first { it.name == "知乎" }
        u.contains("qq.com") -> HOME_PLATFORMS.first { it.name == "腾讯新闻" }
        u.contains("douyin.com") -> HOME_PLATFORMS.first { it.name == "抖音" }
        u.contains("weixin") || u.contains("sogou.com") -> HOME_PLATFORMS.first { it.name == "微信" }
        else -> null
    }
}

/**
 * 这条话题涉及的平台列表。
 *
 * - 聚合榜的 `sourceNames` 形如「微博 · B站 · 百度 · 头条」，按 `·`（兼容 `|` `、` `,`）切分；
 * - 单平台榜 `sourceNames` 为空 → 只按 URL 主机推断出那一个平台（需求 2）；
 * - URL 主机对应的平台**一定**排在最前并高亮，因为那是「在浏览器中打开原文」指向的那个；
 * - 不在白名单里的平台名直接跳过（不猜地址，避免跳死链）。
 */
private fun platformsFor(item: HotSearchItem): List<NewsPlatform> {
    val picked = LinkedHashSet<NewsPlatform>()
    platformOfUrl(item.url)?.let { picked.add(it) }
    item.sourceNames
        .split('·', '|', '/', '、', '，', ',', ' ')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { token ->
            HOME_PLATFORMS.firstOrNull { it.name == token }?.let { picked.add(it) }
        }
    return picked.toList()
}

/** 把颜色按比例朝白色混合（深色主题下给品牌色提亮）。 */
private fun lightenColor(color: Color, ratio: Float): Color = Color(
    red = color.red + (1f - color.red) * ratio,
    green = color.green + (1f - color.green) * ratio,
    blue = color.blue + (1f - color.blue) * ratio,
    alpha = color.alpha
)

/** 把颜色按比例朝黑色压暗（浅色主题下给品牌色做文字色）。 */
private fun darkenColor(color: Color, ratio: Float): Color = Color(
    red = color.red * (1f - ratio),
    green = color.green * (1f - ratio),
    blue = color.blue * (1f - ratio),
    alpha = color.alpha
)

/**
 * 「换个平台看这条」胶囊行（2026-11-03 需求）。
 *
 * 品牌色小圆点 + 平台名；**当前原文来源**用品牌色淡底 + 加粗 + 「当前」小字高亮，
 * 其余是中性底 + 细描边。点击沿用 `openTopic`（系统浏览器），与「在浏览器中打开原文」
 * 是同一个出口，不引入内嵌 WebView。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformJumpCard(
    item: HotSearchItem,
    platforms: List<NewsPlatform>,
    onOpenUrl: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() <= 0.5f
    val current = platformOfUrl(item.url)
    val multi = platforms.size >= 2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardSurface(scheme))
            .padding(16.dp)
    ) {
        Text(
            text = if (multi) "同一条也在这些平台" else "来源平台",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (multi) {
                "点平台名去对应热搜看看；高亮的是这条原文的来源。"
            } else {
                "点平台名去该平台搜索这条话题。"
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            platforms.forEach { platform ->
                PlatformJumpChip(
                    platform = platform,
                    highlighted = platform.name == current?.name,
                    dark = dark,
                    onClick = { onOpenUrl(platform.searchUrl(item.title)) }
                )
            }
        }
    }
}

/** 单个平台胶囊（品牌色圆点 + 名称；当前来源高亮并带「当前」小字）。 */
@Composable
private fun PlatformJumpChip(
    platform: NewsPlatform,
    highlighted: Boolean,
    dark: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val brand = platform.color
    val dot = if (dark) lightenColor(brand, 0.30f) else brand
    val textColor = when {
        !highlighted -> scheme.onSurface
        dark -> lightenColor(brand, 0.45f)
        else -> darkenColor(brand, 0.18f)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (highlighted) {
                    brand.copy(alpha = if (dark) 0.20f else 0.12f)
                } else {
                    scheme.surfaceContainer
                }
            )
            .border(
                width = if (highlighted) 1.2.dp else 1.dp,
                color = if (highlighted) {
                    brand.copy(alpha = if (dark) 0.55f else 0.45f)
                } else {
                    scheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(999.dp)
            )
            .pressScale(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (highlighted) 7.dp else 6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(dot)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = platform.name,
            fontSize = 12.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = textColor
        )
        if (highlighted) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "当前",
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.75f)
            )
        }
    }
}

/**
 * 「新闻概况」二级页（覆盖在热搜页上，带返回）。
 *
 * @param item 被点的那条话题；标题 / 来源 / 热度都直接用它，概况异步取。
 * @param onBack 返回一级页。
 * @param onOpenExternal 用系统浏览器打开原文。
 * @param onOpenPlatformUrl 用系统浏览器打开「指定平台搜索这条话题」的地址
 *   （2026-11-03 新增；由调用方统一走 `openTopic`，失败时给 toast）。
 */
@Composable
internal fun NewsBriefPage(
    item: HotSearchItem,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenPlatformUrl: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // 这条话题涉及哪些平台（用于「换个平台看这条」胶囊行）。
    // remember 的 key 用 url + sourceNames：同一页换成另一条时会重算。
    val jumpPlatforms = remember(item.url, item.sourceNames) { platformsFor(item) }
    var brief by remember(item.url, item.title) { mutableStateOf<NewsBrief?>(null) }
    var loading by remember(item.url, item.title) { mutableStateOf(true) }
    var retry by remember(item.url, item.title) { mutableIntStateOf(0) }

    LaunchedEffect(item.url, item.title, retry) {
        loading = true
        brief = null
        brief = try {
            NewsBriefRepository.brief(item)
        } catch (e: Throwable) {
            // 兜底：repostiory 内部已经把失败翻成 NONE，这里只在意外崩溃时不留白。
            NewsBrief("", BriefSource.NONE, e.message ?: "获取概况失败")
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "新闻概况",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.onSurface
                )
                Text(
                    text = "先看三五句，再决定要不要读全文",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
            }
            // 右上角来源徽标：图标 + 文字，一眼区分「抓取」与「生成」。
            val current = brief
            if (!loading && current != null) {
                BriefSourcePill(current.source)
            }
        }

        LazyColumn(
            // navigationBarsPadding：本页没有底部导航条，内容别被系统手势条压住。
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "title") {
                Text(
                    text = item.title,
                    fontSize = 19.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
            }
            item(key = "meta") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 多平台时这里不再重复列一遍来源：下面「换个平台看这条」的胶囊行
                    // 已经把它画成可点的品牌色圆点 + 平台名了，再写一行纯文字只是噪音。
                    if (item.sourceNames.isNotBlank() && jumpPlatforms.size < 2) {
                        Text(
                            text = item.sourceNames,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    if (item.tag.isNotBlank()) HotTag(item.tag)
                    if (item.heat > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "热度 " + formatHeat(item.heat),
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
            item(key = "brief") {
                val current = brief
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardSurface(scheme))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "概况",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (!loading && current != null && current.origin.isNotBlank()) {
                            Text(
                                text = current.origin,
                                fontSize = 10.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = HotAccent
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "正在整理概况…",
                                fontSize = 13.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }

                        current != null && current.source != BriefSource.NONE -> Column {
                            Text(
                                text = current.text,
                                fontSize = 15.sp,
                                lineHeight = 24.sp,
                                color = scheme.onSurface
                            )
                            val footnote = when (current.source) {
                                BriefSource.OFFICIAL_AI, BriefSource.OFFICIAL ->
                                    "内容抓取自「${current.origin}」，未经本应用改写。"
                                BriefSource.AI -> current.note
                                else -> null
                            }
                            if (!footnote.isNullOrBlank()) {
                                Text(
                                    text = footnote,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        else -> BriefUnavailable(
                            reason = current?.note,
                            onRetry = { retry++ }
                        )
                    }
                }
            }
            // 换个平台看这条（2026-11-03 需求 1）：聚合榜常常同一条在 4 个平台都上榜。
            // 空列表时整块不出现（LazyColumn 里放一个空 item 会白占 12dp 间距）。
            if (jumpPlatforms.isNotEmpty()) {
                item(key = "platforms") {
                    PlatformJumpCard(
                        item = item,
                        platforms = jumpPlatforms,
                        onOpenUrl = onOpenPlatformUrl
                    )
                }
            }
            item(key = "actions") {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(HotAccent)
                            .pressScale(onClick = onOpenExternal)
                            .padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "在浏览器中打开原文",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Text(
                        text = "概况来自平台官方总结 / 摘要或云端模型生成，仅供参考；以原文为准。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * 来源徽标（页面右上角）：「抓取」＝云端下载图标，「生成」＝魔棒图标，
 * 「无法抓取」＝断云图标。
 *
 * 刻意**不只用颜色**：图标形状与文字都要能独立表意（色弱、深色模式、灰度截图下都分得清）。
 */
@Composable
private fun BriefSourcePill(source: BriefSource) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > 0.5f
    val (icon, label, tint) = when (source) {
        BriefSource.OFFICIAL_AI, BriefSource.OFFICIAL -> Triple(
            Icons.Filled.CloudDownload,
            "抓取",
            if (light) Color(0xFF12A150) else Color(0xFF4ADE80)
        )

        BriefSource.AI -> Triple(
            Icons.Filled.AutoFixHigh,
            "生成",
            if (light) Color(0xFF6D5CE7) else Color(0xFFB7A6FF)
        )

        BriefSource.NONE -> Triple(Icons.Filled.CloudOff, "无法抓取", scheme.error)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.32f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 「无法抓取」空状态：图标 + 标题 + 原因 + 重试按钮（不能留白）。 */
@Composable
private fun BriefUnavailable(reason: String?, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = scheme.error.copy(alpha = 0.85f),
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "无法抓取这条概况",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface
        )
        if (!reason.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "原因：$reason",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(HotAccent)
                .pressScale(onClick = onRetry)
                .padding(horizontal = 26.dp, vertical = 9.dp)
        ) {
            Text(
                text = "重试",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
