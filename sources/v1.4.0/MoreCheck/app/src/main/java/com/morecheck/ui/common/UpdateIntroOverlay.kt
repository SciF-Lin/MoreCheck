package com.morecheck.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「更新介绍」浮层（2026-11-14 需求 1）。
 *
 * 版本升级后**第一次**打开应用时弹出，内容与该版本的更新日志一致
 * （数据源是 `ui/settings/ChangelogScreen.kt` 里的 `RELEASES`，两处永远同源，
 * 不需要维护第二份文案）。
 *
 * ## 为什么是页面内覆盖层而不是 AlertDialog
 *
 * 与 `MusicConfirmOverlay` 同一原因：`Dialog` 是独立窗口 + 缩放动画，在软件渲染上
 * 会出现整层重绘卡顿与卡片侧边残影。这里遮罩是固定单色层、卡片只做透明度淡入淡出。
 *
 * ## 点击纪律
 *
 * 根 `Box` 挂空 `clickable` 吞掉遮罩点击（Compose 容器默认不消费触摸），
 * 返回键也吞掉：更新介绍不是"确认才能继续"的拦截，但也不该被误触一次就关掉，
 * 明确的两个按钮才是出口。
 *
 * @param version 当前版本名（如 `1.3.9 beta`），显示在标题里。
 * @param date 更新日期，可为空（数据里没有就不显示）。
 * @param items 该版本的改动条目，与更新日志逐条一致。
 * @param onOpenDetails 「查看详情」：跳到安装包下载页。
 * @param onDismiss 「我知道了」：关闭并记住这个版本已经看过。
 */
@Composable
fun UpdateIntroOverlay(
    version: String,
    date: String?,
    items: List<String>,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) { visible = true }
    BackHandler(enabled = true) { }

    val leave: (() -> Unit) -> Unit = { after ->
        if (!leaving) {
            leaving = true
            visible = false
            scope.launch {
                delay(UPDATE_INTRO_EXIT_MS.toLong())
                after()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = interaction, indication = null, onClick = { }),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(UPDATE_INTRO_ENTER_MS)),
            exit = fadeOut(tween(UPDATE_INTRO_EXIT_MS))
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 26.dp)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                // ---- 标题 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "已更新到 $version",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = date?.takeIf { it.isNotBlank() }?.let { "更新日期 $it" }
                                ?: "本次更新内容",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- 条目 ----
                //
                // 条目多时最多占 42% 屏高，超出在卡片内部滚动：
                // 卡片本身不撑满屏，用户始终能看到底部两个按钮。
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    items.forEach { item ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(5.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = item,
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ---- 两个出口 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { leave(onOpenDetails) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("查看详情", fontSize = 14.sp)
                    }
                    Button(
                        onClick = { leave(onDismiss) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("我知道了", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** 进场淡入时长（与音乐弹窗同一档，软件渲染下短一点更稳）。 */
private const val UPDATE_INTRO_ENTER_MS = 160

/** 离场淡出时长；[UpdateIntroOverlay] 等这么久再执行回调。 */
private const val UPDATE_INTRO_EXIT_MS = 120
