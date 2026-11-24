package com.morecheck.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.morecheck.util.findActivity

/**
 * 内嵌网页页面的「系统导航栏图标」口径（2026-11-11 需求 3）。
 *
 * ## 它修的是什么
 *
 * 应用整体按**主题**切换系统栏图标明暗（`isAppearanceLightNavigationBars = !深色`），
 * 但内嵌网页（官网、B 站、B 站登录）里显示的页面**永远是白底**——B 站与自家官网
 * 都没有深色版。深色主题下全局把图标设成浅色，压在白色网页上就"消失"了，
 * 用户看到的是一条没有按钮的空白导航栏，也就是报上来的"导航栏颜色不匹配"。
 *
 * ## 口径
 *
 * 只改**导航栏**（底部）：进页面时强制深色图标，离开时还原。状态栏不动——
 * 这几页的 TopAppBar 用的是应用主题色，图标该继续跟着主题走。
 *
 * 音乐页那种"自己的调色板"页面不用这个：它按 `MusicPalette.isDark` 整体切换，
 * 见 `MusicScreen` 里的 `DisposableEffect`。
 */
@Composable
fun WebPageNavBarIcons() {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previous = controller.isAppearanceLightNavigationBars
            // 网页底色恒定是浅色 → 深色图标（isAppearanceLight… = true）。
            controller.isAppearanceLightNavigationBars = true
            onDispose { controller.isAppearanceLightNavigationBars = previous }
        }
    }
}
