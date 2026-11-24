package com.morecheck.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.morecheck.MainActivity
import com.morecheck.ui.lab.ACTION_LAB_SHORTCUT
import com.morecheck.ui.lab.EXTRA_LAB_PAGE

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

    // 开发者选项的「一键设置」把全局开关打开后，所有走本函数的链接都改在
    // 应用内「官网」加载。复用桌面快捷方式那条已验证过的一次性事件通道，
    // 不新增 Activity / action / 导航枚举；值带查询参数，消费侧单独认这个前缀。
    if (LinkOpenGate.inApp) {
        return runCatching {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_LAB_SHORTCUT
                putExtra(EXTRA_LAB_PAGE, IN_APP_LINK_ROUTE_PREFIX + Uri.encode(trimmed))
                // 不带 CLEAR_TOP： Activity 是 singleTask，已在前台时这会走
                // onNewIntent；清栈反而会把用户当前所在的页面连同回退栈一起冲掉。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            true
        }.getOrElse {
            onMessage("无法在应用内打开该链接")
            false
        }
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
