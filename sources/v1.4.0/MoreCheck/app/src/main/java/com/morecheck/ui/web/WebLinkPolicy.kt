package com.morecheck.ui.web

/**
 * 内嵌网页的链接裁决策略（纯 Kotlin，无 Android 依赖，便于纯 JVM 单测）。
 *
 * ## 为什么要抽这一层
 *
 * 两个 WebView 宿主（「官网」与 B站播放页）原先各自在
 * `shouldOverrideUrlLoading` 里写「非 https 就弹一句提示」。问题是这个回调
 * 对**子资源导航**（iframe、重定向、脚本跳转）同样会触发，而正常网页里
 * `about:blank`、`data:`、`javascript:` 几乎每页都有 —— 于是用户浏览一个页面
 * 会被连续弹好几次「仅支持 https 网页」，真正需要提示的场景反而被淹没了。
 *
 * 现在把裁决收敛成四个明确动作，并区分「主框架」与「子资源」：
 * - 子资源：https 放行，其余静默拦截（不提示）；
 * - 主框架 http：主动升级成 https 再加载，而不是拦下弹字；
 * - 无害的内部协议：静默拦截；
 * - 真正想跳出应用的外部协议：才给一次提示（外层再做节流）。
 */
internal enum class WebLinkDecision {
    /** 交给 WebView 正常加载。 */
    ALLOW,

    /** 静默拦截：不发提示，避免刷屏。 */
    SILENT_BLOCK,

    /** 把 http 改写为 https 后重新加载（由宿主调用 [toHttps]）。 */
    UPGRADE_HTTPS,

    /** 拦截并提示一次：用户确实点了一个本应用不处理的外部协议。 */
    BLOCK_WITH_NOTICE,
}

internal object WebLinkPolicy {

    /**
     * 网页内部常用的伪协议，出现频率极高且不代表用户想离开应用。
     * 一律静默拦截，绝不弹提示。
     */
    private val SILENT_SCHEMES = setOf(
        "about", "data", "javascript", "blob", "file", "content"
    )

    /**
     * @param scheme URI 的协议名（大小写不敏感，允许 null）。
     * @param isMainFrame 是否为主框架导航；子资源一律不产生用户可见提示。
     */
    fun decide(scheme: String?, isMainFrame: Boolean): WebLinkDecision {
        if (!isMainFrame) {
            return if (scheme.equals("https", ignoreCase = true)) {
                WebLinkDecision.ALLOW
            } else {
                WebLinkDecision.SILENT_BLOCK
            }
        }
        return when (scheme?.lowercase()) {
            "https" -> WebLinkDecision.ALLOW
            "http" -> WebLinkDecision.UPGRADE_HTTPS
            in SILENT_SCHEMES -> WebLinkDecision.SILENT_BLOCK
            else -> WebLinkDecision.BLOCK_WITH_NOTICE
        }
    }

    /**
     * 把 http 地址改写成 https 地址。
     *
     * 只替换协议部分，保留后面的 host / path / query 原样；不是 http 时返回 null，
     * 让调用方自己决定怎么处理。大小写不敏感地识别前缀。
     */
    fun toHttps(url: String): String? {
        val separator = url.indexOf("://")
        if (separator < 0) return null
        if (!url.substring(0, separator).equals("http", ignoreCase = true)) return null
        return "https://" + url.substring(separator + 3)
    }
}

/**
 * 提示节流：同一句文案在 [windowMs] 内只放行一次。
 *
 * 即使策略已经把绝大多数误报挡掉，一个页面仍可能连续抛出多个外部协议链接
 * （例如 B站 H5 反复尝试唤起客户端）。没有节流的话 Snackbar 会一直刷新，
 * 观感仍是「频繁弹出」。
 */
internal class NoticeThrottle(private val windowMs: Long = 1_500L) {
    private var lastAt: Long = Long.MIN_VALUE
    private var lastText: String = ""

    fun shouldShow(text: String, now: Long = System.currentTimeMillis()): Boolean {
        if (text == lastText && now - lastAt < windowMs) return false
        lastAt = now
        lastText = text
        return true
    }
}
