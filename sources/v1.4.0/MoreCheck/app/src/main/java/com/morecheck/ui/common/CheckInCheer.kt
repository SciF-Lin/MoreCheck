package com.morecheck.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val CHEER_VISIBLE_MS = 1600L

/**
 * 打卡成功后的正向反馈文案（2026-11-24）。
 *
 * 与 [ConfettiEffect] 共用同一个 `triggerKey`（即 HomeScreen 的 `celebrateKey`）——
 * 每次打卡 key 自增，这里就弹一句「连续打卡 N 天」的鼓励，短暂显示后淡出。
 *
 * 纯装饰：`streak <= 1` 时不显示（第一天还没有"连续"可言），避免打扰；
 * 不持有任何需要持久化的状态，关闭动画开关时不挂载即可完全消失。
 */
@Composable
fun CheckInCheer(
    triggerKey: Int,
    streak: Int,
    modifier: Modifier = Modifier
) {
    if (triggerKey <= 0 || streak <= 1) return

    // streak 由 StateFlow 驱动，可能比 celebrateKey 晚一帧落库更新；
    // 用 rememberUpdatedState 让文案在显示的这一千多毫秒里始终读到最新值。
    val currentStreak by rememberUpdatedState(streak)
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(triggerKey) {
        visible = true
        delay(CHEER_VISIBLE_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(
            animationSpec = tween(220),
            initialOffsetY = { it / 2 }
        ),
        exit = fadeOut(tween(320)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 与撒花的爆发中心（屏幕 38% 高度）对齐，文案压在其下方一点。
                .offset(y = (-40).dp)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "连续打卡 $currentStreak 天，继续保持",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.92f))
                    .padding(horizontal = 20.dp, vertical = 11.dp)
            )
        }
    }
}
