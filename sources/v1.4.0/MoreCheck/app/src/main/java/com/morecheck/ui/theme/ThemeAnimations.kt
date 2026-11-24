package com.morecheck.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color

/**
 * 会跟着深浅主题「当帧到位」的颜色状态（2026-11-20 需求 4）。
 *
 * ## 为什么不能直接全站用 [animateColorAsState]
 *
 * 深色模式切换时 `MaterialTheme.colorScheme` 当帧变成深色板，而各个卡片里的
 * `animateColorAsState` 仍从**旧方案的浅色值**出发补间 150~220ms。
 * 结果就是整页底色已经变暗、文字与图标的背景却还是白的——用户看到的"闪白"。
 *
 * 这里用 `key(colorScheme)` 把动画状态绑在主题实例上：主题一换，旧的 Animatable
 * 直接销毁、新 Animatable 以新目标色起步，不再有跨方案的浅色中间帧；
 * 同一主题内的选中态切换仍然照常补间，手感不变。
 */
@Composable
fun animateThemeColorAsState(
    targetValue: Color,
    animationSpec: AnimationSpec<Color> = spring(),
    label: String = "themeColor"
): State<Color> = key(MaterialTheme.colorScheme) {
    animateColorAsState(
        targetValue = targetValue,
        animationSpec = animationSpec,
        label = label
    )
}
