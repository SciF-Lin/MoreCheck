package com.morecheck.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 用系统默认浏览器打开一个网址。
 *
 * 原先这段逻辑以 `private` 形式散落在 `ui/scan/ScanScreen.kt` 与
 * `ui/web/SearchWebScreen.kt` 里各写了一份，`ui/settings/AboutScreen.kt` 的
 * 「检查更新」也要用同一套，于是提到这里统一维护。
 *
 * @param onMessage 失败时把原因交回调用方（走全局 Snackbar）。
 *   刻意**不弹 Toast**：本项目全程用 Snackbar 做反馈，混用两套反馈语言会显得割裂。
 * @return 是否真的把 Intent 发出去了。调用方一般不需要区分，
 *   但「扫码结果页」那种要顺带关闭页面的场景会用到。
 */
fun openInBrowser(context: Context, url: String, onMessage: (String) -> Unit = {}): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
        onMessage("链接为空")
        return false
    }

    val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || scheme != "http" && scheme != "https") {
        // 只放行 http/https：file:// 与自定义 scheme 可能指向应用私有数据，
        // 交给外部应用打开等于把它们暴露出去。
        onMessage("只支持 http/https 链接")
        return false
    }

    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse {
        // 设备上没有浏览器时 startActivity 会抛 ActivityNotFoundException。
        onMessage("没有可用的浏览器")
        false
    }
}
