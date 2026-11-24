package com.morecheck.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 音乐相关确认弹窗共用的**页面内全屏覆盖层**（2026-11-14）。
 *
 * ## 为什么不再用 [androidx.compose.ui.window.Dialog]
 *
 * 进入音乐 / 退出音乐两个弹窗原来各自用 `Dialog` + `AnimatedVisibility` 的
 * `fadeIn + scaleIn(0.92)` / `fadeOut + scaleOut(0.94)`。在 swiftshader 软件渲染上
 * 有两个可见问题：
 *
 * 1. **卡顿**：`Dialog` 是独立窗口，默认带 `FLAG_DIM_BEHIND`；缩放等价于给整层
 *    套 `graphicsLayer` 变换，动画每一帧都要把「圆角裁剪 + 不透明卡片 + 整棵文字/按钮子树」
 *    重新光栅化一次，再与全屏半透明 dim 混合。
 * 2. **侧边残影**：`scaleOut` 让卡片边界逐帧内缩，独立窗口的透明脏区来不及失效，
 *    上一帧的圆角边缘像素留在屏幕两侧；退出动画播完窗口被同帧销毁时更明显。
 *
 * 现在弹窗直接画在页面内容层里（少一层窗口、少一层 dim），遮罩是**固定尺寸的单色层**，
 * 卡片只做 alpha 淡入淡出、**不做任何尺寸变化**，因此没有边界内缩，也不会留下残影。
 * 动画仍然有（用户上一轮明确要求过进出动画），只是缩短到 150/120ms 且只动透明度。
 *
 * ## 点击纪律
 *
 * 全屏覆盖层必须自己吞点击：根 `Box` 挂一个空 `clickable`，否则点遮罩会穿透到
 * 下层列表（历史坑：Compose 的 `Surface`/`Box` 默认不消费触摸）。返回键同样不放行，
 * 只有卡片上的按钮能离开 —— 由 `BackHandler` 吞掉，调用方不必再处理。
 *
 * @param scrimColor 遮罩颜色（带 alpha 的单色，避免多层半透明叠加）。
 * @param content 卡片内容；参数 `leave` 用于「先播完退出动画再执行回调」，
 *   直接调回调会让组合被同帧摘掉、退出动画根本没机会画。
 */
@Composable
internal fun MusicConfirmOverlay(
    scrimColor: Color,
    content: @Composable (leave: (() -> Unit) -> Unit) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }

    // 首帧先把组合挂上，下一帧再淡入：窗口创建与动画不再挤在同一帧。
    LaunchedEffect(Unit) { visible = true }
    BackHandler(enabled = true) { }

    val leave: (() -> Unit) -> Unit = { after ->
        if (!leaving) {
            leaving = true
            visible = false
            scope.launch {
                delay(MUSIC_CONFIRM_EXIT_MS.toLong())
                after()
            }
        }
    }

    // 2026-11-14 需求 4：遮罩与卡片**同步**淡入淡出。
    //
    // 原来遮罩是"立刻画上去"的（`background(scrimColor)` 直接生效），
    // 卡片却要淡入 150ms，观感就是「背景先变灰、弹窗随后才冒出来」。
    // 现在遮罩也走同一条 150/120ms 的透明度曲线：两者逐帧一起到位。
    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            if (visible) MUSIC_CONFIRM_ENTER_MS else MUSIC_CONFIRM_EXIT_MS
        ),
        label = "musicScrimAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor.copy(alpha = scrimColor.alpha * scrimAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MUSIC_CONFIRM_ENTER_MS)),
            exit = fadeOut(tween(MUSIC_CONFIRM_EXIT_MS))
        ) {
            Box(Modifier.padding(horizontal = 24.dp)) {
                content(leave)
            }
        }
    }
}

/** 进场淡入时长：比原来 220ms 短，视觉仍在但不给软件渲染留长尾。 */
internal const val MUSIC_CONFIRM_ENTER_MS = 150

/** 离场淡出时长；[MusicConfirmOverlay] 的 `leave` 按这个时长等待回调。 */
internal const val MUSIC_CONFIRM_EXIT_MS = 120
