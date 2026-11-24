package com.morecheck.ui.web.bili

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
import com.morecheck.ui.music.MusicConfirmOverlay
import kotlinx.coroutines.delay

/**
 * 「进入B站视频」前的温馨提示（2026-11-19 需求 3）。
 *
 * 样式与进入音乐的确认弹窗完全一致：共用 [MusicConfirmOverlay] 页面内覆盖层
 * （只淡入淡出、不缩放），5 秒倒计时、整行可点的「不再提示」、取消 / 我已知晓。
 * 门禁挂在导航层（TabNavHost），点确认才导航，取消留在发现页。
 *
 * 勾上再确认才落盘 `bili_ui_tip_dismissed`；想恢复弹窗走「B站视频 → 我的 →
 * 设置 → 进入B站时确认」。
 */
@Composable
fun BiliEntryConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(BILI_ENTRY_CONFIRM_SECONDS) }
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
                BiliEntryConfirmLine("本功能可能会申请系统权限（网络状态、存储等），仅用于视频浏览、缓存与下载。")
                BiliEntryConfirmLine("会访问外部网络接口（B 站公开接口），并上传 / 下载封面、视频、弹幕等数据。")
                BiliEntryConfirmLine("目前还在测试中：功能可能不完整，也可能存在 bug。")
                Text(
                    text = "确认后进入B站视频；如不想进入，可点「取消」留在当前页。",
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
                        .height(BILI_ENTRY_BUTTON_HEIGHT)
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
                        .height(BILI_ENTRY_BUTTON_HEIGHT)
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
private fun BiliEntryConfirmLine(text: String) {
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

private const val BILI_ENTRY_CONFIRM_SECONDS = 2
private val BILI_ENTRY_BUTTON_HEIGHT = 48.dp