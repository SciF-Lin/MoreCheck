package com.morecheck.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import com.morecheck.ui.common.SplashTextLabel
import com.morecheck.ui.common.rememberSplashTextStyle
import com.morecheck.ui.theme.MoreCheckSplashTheme
import com.morecheck.ui.theme.LocalAccentColor

/**
 * 启动页（任务2）。
 *
 * - 浅色为底：外层用 [MoreCheckSplashTheme] 固定浅色方案，
 *   即使应用处于深色模式，启动页也保持浅色；
 * - 界面中间显示 `Hello World`，颜色取浅色方案里的 `primary`（青绿），**与底色不同**；
 * - 自身带一次淡入 + 轻微上浮，避免「硬闪」；
 * - 停留时长由调用方控制，见 `MoreCheckRoot` 的 `SPLASH_DURATION_MS`。
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // 必须在进入局部主题**之前**判断深浅——MoreCheckSplashTheme 会把配色整个换掉。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 2026-10-27：开屏文字走「开发者选项 → 开屏显示文字」的统一设置
    // （默认仍是 34sp 的 Hello World）。读取放在主题外无所谓——它只用到 Context。
    val splashText = rememberSplashTextStyle()

    MoreCheckSplashTheme(accent = LocalAccentColor.current, dark = dark) {
        // 标题淡入 + 上浮：0 → 1 的进度同时驱动透明度与位移。
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 420)
            )
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            SplashTextLabel(
                style = splashText,
                // 深色模式：深底白字；浅色模式：浅底 + 主题色字。
                color = if (dark) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * 24f
                }
            )
        }
    }
}
