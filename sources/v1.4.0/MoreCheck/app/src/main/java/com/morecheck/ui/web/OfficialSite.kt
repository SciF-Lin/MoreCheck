package com.morecheck.ui.web

/**
 * 「官网」（2026-11-10 起由「搜一搜」更名）的默认首页与入口归一化。
 *
 * ## 为什么单独放一个纯 Kotlin 文件
 *
 * 默认地址原先是 [SearchWebScreen] 里的一个 `private const val`，改一个网址要顺手
 * 改 KDoc、改隐私政策、改交接文档，很容易漏；而「路由带进来的初始地址」又牵涉
 * 协议补齐、http 升级这些分支。把这些纯逻辑抽出来后，既能被单测覆盖，
 * 应用内其它入口（检查更新、外链在应用内打开）也能复用同一套口径。
 *
 * ⚠️ 只改这里的 [OFFICIAL_SITE_URL] 就能换首页；**不要**把地址散落到导航层或
 * 设置项里。路由名仍叫 `search`（老桌面快捷方式与 `IN_APP_LINK_ROUTE_PREFIX`
 * 依赖它），更名只体现在给用户看的文案上。
 */

/** 官网地址（带协议，WebView 只加载 https）。 */
internal const val OFFICIAL_SITE_URL = "https://www.scif.top/"

/** 发现页入口显示的名字。 */
internal const val OFFICIAL_SITE_LABEL = "官网"

/**
 * 把「打开内嵌网页时带进来的地址」归一化成可直接喂给 WebView 的 https 地址。
 *
 * - 空 / 空白 → [OFFICIAL_SITE_URL]（发现页入口走这条）；
 * - 没写协议（`www.scif.top`）→ 补 `https://`；
 * - 明文 `http://` → 升级为 `https://`，与 `WebLinkPolicy` 对页面内链接的处理一致；
 * - 其它协议（`javascript:` 等）→ 不当成网页地址，回落到官网，避免把伪协议灌进 WebView。
 */
internal fun officialHomeUrl(initial: String?): String {
    val raw = initial?.trim().orEmpty()
    if (raw.isEmpty()) return OFFICIAL_SITE_URL
    // ① 明文 http：统一升级，与页面内链接的策略一致。
    WebLinkPolicy.toHttps(raw)?.let { return it }
    // ② 带 :// 的地址只放行 https；file:// / intent:// 之类一概回落到官网。
    val separator = raw.indexOf("://")
    if (separator >= 0) {
        val scheme = raw.substring(0, separator).lowercase()
        return if (scheme == "https") raw else OFFICIAL_SITE_URL
    }
    // ③ 没有 :// 时还得防 `javascript:alert(1)` 这种伪协议：
    //    不能当主机名补成 https://javascript:alert(1)。
    //    只认已知的伪协议，其余（如 `localhost:8080`、`www.scif.top:443`）仍按主机:端口处理。
    val colon = raw.indexOf(':')
    if (colon > 0 && raw.substring(0, colon).lowercase() in NON_WEB_SCHEMES) {
        return OFFICIAL_SITE_URL
    }
    return "https://$raw"
}

/**
 * 两次「http → https 自动升级」之间的最短间隔（毫秒）。
 *
 * 服务端若把 https 又 3xx 回 http，升级后的 https 页照样会走 `onPageStarted`；
 * 用布尔量做「只允许一次」会被这次回调清零，于是来回重定向变成死循环。
 * 改成时间窗后，窗口内的第二次降级直接落页面内错误页。
 */
internal const val UPGRADE_LOOP_WINDOW_MS = 1500L

/**
 * 现在是否允许再做一次 http → https 升级。
 *
 * 抽成纯函数是为了能单测「同一次来回重定向不会被反复升级」这个不变量；
 * 时间戳一律用单调时钟（[android.os.SystemClock.elapsedRealtime]），
 * 不用墙上时间，避免用户改系统时间把这个保护绕过去。
 */
internal fun allowRedirectUpgrade(now: Long, lastUpgradeAt: Long): Boolean =
    now - lastUpgradeAt > UPGRADE_LOOP_WINDOW_MS

/** 会被网页用来"拉端 / 执行脚本"的伪协议；出现在入口地址里一律不加载。 */
private val NON_WEB_SCHEMES = setOf(
    "javascript", "data", "about", "blob", "file", "content",
    "intent", "market", "mailto", "tel", "sms", "weixin", "baiduboxapp", "alipays", "mqq"
)
