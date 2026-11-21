package com.morecheck.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.morecheck.ui.theme.MoreCheckSplashTheme

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
    MoreCheckSplashTheme {
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
            Text(
                text = "Hello World",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                // 青绿字 + 浅色底，保证与底色明显不同。
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.graphicsLayer {
                    alpha = progress.value
                    translationY = (1f - progress.value) * 24f
                }
            )
        }
    }
}
