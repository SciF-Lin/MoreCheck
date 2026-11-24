package com.morecheck.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

/**
 * 单行文本输入弹窗（2026-11-09：收藏分组命名、实验室条目重命名共用）。
 *
 * [initial] 是初值（重命名时传原名，新建时传空串）。确认按钮在输入为空时**不做任何事**
 * （不关弹窗、不回调），避免产生空名字；调用方不需要自己再判空。
 *
 * [extraLabel] / [onExtra] 是"第三个动作"的钩子（分组弹窗用它放「删除分组」），
 * 不需要时留空即可。
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "保存",
    dismissLabel: String = "取消",
    extraLabel: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onExtra: (() -> Unit)? = null
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (extraLabel != null && onExtra != null) {
                    TextButton(onClick = onExtra) {
                        Text(extraLabel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = draft.trim()
                    if (value.isNotEmpty()) onConfirm(value)
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

/**
 * 单选列表弹窗（2026-11-09：把收藏移入哪个分组）。
 *
 * [options] 是「显示文案 → 值」，点哪一项就以该项的值回调并关闭。
 * 选项多时最多撑到 60% 屏高再滚动，避免把弹窗顶出屏幕。
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<String, T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (label, value) ->
                    TextButton(
                        onClick = { onPick(value) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}
