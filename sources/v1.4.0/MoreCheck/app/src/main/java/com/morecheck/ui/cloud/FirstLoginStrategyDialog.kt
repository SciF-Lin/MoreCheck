package com.morecheck.ui.cloud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.cloud.FirstLoginStrategy

/**
 * 首次登录的数据合并策略弹窗（v3 云同步）。
 *
 * 需求：首次登录成功后询问一次「本地与云端怎么合并」，三选一：
 *
 * - **上传本地**（[FirstLoginStrategy.UPLOAD]）：把本机数据推上云端，按修改时间
 *   合并（LWW）——适合「先用了一段时间才登录」的用户，云端已有的更新行不会被
 *   本机旧数据盖掉；
 * - **下载并覆盖**（[FirstLoginStrategy.DOWNLOAD]）：清空本机任务与记录后用云端
 *   数据回填——适合「换机 / 重装」的用户，云端就是唯一事实来源；
 * - **暂不处理**（[FirstLoginStrategy.SKIP]）：只完成登录，不同步。
 *
 * ## 每个「账号生命周期」只问一次
 *
 * 弹窗的显隐由根骨架判定（`session != null && !firstLoginHandled`），
 * 用户做出**任一**选择（含「暂不处理」）后由宿主写入 `firstLoginHandled = true`；
 * **登出会重置该标志**——换账号登录时云端可能是另一份数据，必须重新问。
 * 点弹窗外（onDismiss）与「暂不处理」同义：不做任何同步，同样标记已问，
 * 避免每次冷启动都被同一个问题打断。
 *
 * ## 视觉
 *
 * 三个选项是**可点行**（图标 + 标题 + 说明）而不是三个按钮：说明文字较长，
 * 按钮装不下；行式布局也让三个选项天然等宽等高，不暗示优先级。
 */
@Composable
fun FirstLoginStrategyDialog(
    /** 用户点了某个策略（含「暂不处理」）。宿主负责执行并标记已问。 */
    onStrategy: (FirstLoginStrategy) -> Unit,
    /** 点弹窗外 / 系统返回。语义与「暂不处理」相同。 */
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("数据如何与云端合并？") },
        text = {
            Column {
                Text(
                    text = "这是本账号第一次在这台设备上登录。请选择本地数据与云端数据的处理方式（之后可在「云同步」页手动同步）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                StrategyRow(
                    icon = Icons.Filled.CloudUpload,
                    title = "上传本地数据",
                    description = "把这台设备的任务与打卡记录合并到云端（按修改时间取较新者）",
                    onClick = { onStrategy(FirstLoginStrategy.UPLOAD) }
                )
                StrategyRow(
                    icon = Icons.Filled.CloudDownload,
                    title = "下载云端数据",
                    description = "⚠️ 清空本机任务与打卡记录，用云端数据重新填充（适合换机）",
                    onClick = { onStrategy(FirstLoginStrategy.DOWNLOAD) }
                )
                StrategyRow(
                    icon = Icons.Filled.Schedule,
                    title = "暂不处理",
                    description = "只完成登录，不同步数据。之后可随时在「云同步」页手动同步",
                    onClick = { onStrategy(FirstLoginStrategy.SKIP) }
                )

                Spacer(Modifier.height(16.dp))

                // 「我知道了」（2026-10-22 需求）：参考设计稿，一个整宽的描边按钮
                // 收在三个选项下方。
                //
                // 语义 = 关掉弹窗、不做任何同步，也就是与「暂不处理」等价
                // （两者都会把 `firstLoginHandled` 置真，之后再进同步管理页手动同步）。
                // 放在**内容列**里而不是 AlertDialog 的 confirmButton 槽：那个槽是
                // 右对齐的单行按钮区，做不出设计稿里"整宽 + 粗描边"的形态。
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "我知道了",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {}
    )
}

/** 弹窗里的一行策略选项。 */
@Composable
private fun StrategyRow(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
