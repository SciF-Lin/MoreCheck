package com.morecheck.ui.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.morecheck.ui.theme.MoreCheckSplashTheme
import com.morecheck.ui.theme.LocalAccentColor

/**
 * 朋友圈页。
 *
 * 需求：点开后显示 `Hello World`，**与开屏显示样式一致**，
 * 且交互逻辑与设置页一致（顶部标题栏 + 左上角返回箭头）。
 *
 * 因此这里复用开屏页的固定浅色主题与同样的淡入 + 上浮动画，
 * 保证两处的字体、颜色、动效完全一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(onBack: () -> Unit) {
    // 与开屏页同一套判断：在进入局部主题之前先看当前是深色还是浅色。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朋友圈") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        MoreCheckSplashTheme(accent = LocalAccentColor.current, dark = dark) {
            // 与开屏页相同的入场动画：透明度 0→1，同时 Y 轴 +24px→0。
            val progress = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 420)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Hello World",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
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
}
