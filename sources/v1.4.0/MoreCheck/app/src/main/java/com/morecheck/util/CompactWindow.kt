package com.morecheck.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * 当前是否处于**紧凑小窗**（2026-10-25 需求：优化小窗模式下的界面显示）。
 *
 * 判定 = 「处于多窗口模式（分屏 / 自由窗口 / MIUI 小窗）**且**窗口宽度不宽于
 * [COMPACT_WINDOW_MAX_WIDTH_DP]（420dp，约一台普通手机的宽度）。
 * 两个条件缺一不可：
 * - 只看宽度会把「竖屏手机全屏」也误判成小窗（手机本来就这个宽度）；
 * - 只看 multi-window 会在「分屏拉得很宽」时仍按小窗排版（那不挤，不需要紧凑化）。
 *
 * ## 哪些界面在消费它
 *
 * - 底部导航栏：紧凑模式下 `NavigationBarItem` 不显示文字、只留图标
 *   （矮一截的高度换给内容区，见 `MoreCheckApp` 底栏那段）。
 *
 * ## 响应窗口拖拽（宽度变化）的机制
 *
 * `isInMultiWindowMode` 不会主动通知 Compose；这里把 `LocalConfiguration` 的
 * 宽高读进 `remember` 的 key——拖动小窗边框会触发 Configuration 变化与重组，
 * 重组时重新读一次标记，紧凑态随之切换。普通全屏使用下它永远是 false。
 */
@Composable
fun rememberIsCompactWindow(): Boolean {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val configuration = LocalConfiguration.current
    return remember(activity, configuration.screenWidthDp, configuration.screenHeightDp) {
        activity?.isInMultiWindowMode == true &&
            configuration.screenWidthDp <= COMPACT_WINDOW_MAX_WIDTH_DP
    }
}

/** 紧凑小窗的宽度上限（dp）。 */
private const val COMPACT_WINDOW_MAX_WIDTH_DP = 420
