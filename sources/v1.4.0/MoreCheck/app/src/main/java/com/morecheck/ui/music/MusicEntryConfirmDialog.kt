package com.morecheck.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 「进入音乐页」前的确认弹窗（2026-11-12，2026-11-14 改覆盖层）。
 *
 * ## 为什么弹窗在**发现页**、不在音乐页里
 *
 * 之前它挂在 [MusicScreen] 内部，导航已经发生、页面先渲染一层底色再盖弹窗，
 * 用户看到的就是「先进去了、背景还是黑的」——弹窗变成了事后解释。
 * 现在照「开发者选项 → 图标更改」那条链路做：父页面先把弹窗弹出来，
 * **只有点「我已知晓」才调 `onOpenMusic()` 导航**，取消则原样留在发现页。
 *
 * ## 5 秒倒计时
 *
 * 与图标更改的风险提醒同一口径：倒计时期间确认键不可点，秒数直接写在文案里
 * （`我已知晓(5s)` → `(4s)` …）。按钮由 `weight(1f) + height(48dp)` 钉死，
 * 跳字时不会变大变小。
 *
 * ## 「不再提示」的点击范围（2026-11-12 追加）
 *
 * 原来只有 `Checkbox` 自己那 48dp 能吃点击，点旁边的「不再提示」四个字没反应。
 * 现在整行（勾 + 文字 + 右侧留白）挂 `toggleable(role = Checkbox)`、
 * 最小 48dp 高、`fillMaxWidth()`，红框那一整块都是判定区；
 * `Checkbox` 传 `onCheckedChange = null` 只当视觉，避免和整行抢事件。
 *
 * ## 出现 / 消失动画与卡顿修复（2026-11-14）
 *
 * 原先是独立 `Dialog` 窗口 + `fade + scale` 动画，软件渲染下逐帧重绘整层并
 * 在卡片两侧留下缩放边缘的残影。现在统一走 [MusicConfirmOverlay]：
 * **页面内全屏覆盖层 + 只淡入淡出（150/120ms）、不缩放**，
 * 遮罩和返回键都不放行，仍然只有两个按钮能离开。
 *
 * ## 「不再提示」的落盘
 *
 * 勾上再确认才落盘 `music_ui_tip_dismissed`；不勾则下次点音乐还会弹。
 * 想恢复弹窗走「开发者选项 → 音乐 → 重新显示进音乐确认弹窗」。
 */
@Composable
fun MusicEntryConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(MUSIC_ENTRY_CONFIRM_SECONDS) }
    val toggleInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }

    MusicConfirmOverlay(scrimColor = Color.Black.copy(alpha = 0.32f)) { leave ->
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 340.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "温馨提示",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MusicEntryConfirmLine("本页是作者按个人喜好定制的界面风格，与 App 其它页面差异较大。")
                MusicEntryConfirmLine("搜索、播放、收藏、下载等功能均照常可用，不受影响。")
                Text(
                    text = "确认后进入音乐页；如不习惯，可点「取消」留在当前页。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 14.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .toggleable(
                        value = dontShowAgain,
                        role = Role.Checkbox,
                        interactionSource = toggleInteraction,
                        indication = null,
                        onValueChange = { dontShowAgain = it }
                    )
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = dontShowAgain,
                    // null = 纯粹视觉，整行已经接管了点击与语义。
                    onCheckedChange = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "不再提示",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { leave(onCancel) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    elevation = null,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(MUSIC_ENTRY_BUTTON_HEIGHT)
                ) {
                    Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = { leave { onConfirm(dontShowAgain) } },
                    enabled = remaining == 0,
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(MUSIC_ENTRY_BUTTON_HEIGHT)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (remaining > 0) "我已知晓(${remaining}s)" else "我已知晓",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MusicEntryConfirmLine(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "·",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = text,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private const val MUSIC_ENTRY_CONFIRM_SECONDS = 5
private val MUSIC_ENTRY_BUTTON_HEIGHT = 48.dp
