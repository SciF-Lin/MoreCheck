package com.morecheck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.util.TaskColors
import com.morecheck.util.TaskIcons
import java.time.LocalTime

/** 备注最大长度（字符数）。 */
private const val NOTE_MAX_LENGTH = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    taskId: Long,
    onDone: () -> Unit,
    viewModel: TaskEditViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    LaunchedEffect(taskId) { viewModel.load(taskId) }
    val loaded by viewModel.task.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(TaskIcons.all.first()) }
    var color by remember { mutableStateOf(TaskColors.all.first()) }
    var reminder by remember { mutableStateOf<String?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // v5 组织字段：旧数据这些值是 null / false，用可空转空串、布尔默认 false 承接。
    var groupName by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var archived by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val tagPreview = remember(tags) { previewTags(tags) }

    LaunchedEffect(loaded) {
        loaded?.let {
            name = it.name
            note = it.note.orEmpty()
            icon = it.icon
            color = it.color
            reminder = it.reminderTime
            groupName = it.groupName.orEmpty()
            tags = it.tags.orEmpty()
            archived = it.archived
            paused = it.paused
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (taskId < 0) "新建任务" else "编辑任务") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("图标", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TaskIcons.all) { candidate ->
                    val selected = candidate == icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (selected) Color(color).copy(alpha = 0.3f) else Color.Transparent)
                            .border(
                                width = if (selected) 2.dp else 0.dp,
                                color = if (selected) Color(color) else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { icon = candidate },
                        contentAlignment = Alignment.Center
                    ) { Text(candidate, fontSize = 22.sp) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("颜色", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TaskColors.all.chunked(8).forEach { rowColors ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowColors.forEach { candidate ->
                        val selected = candidate == color
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(candidate))
                                .border(
                                    width = if (selected) 3.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { color = candidate }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20); nameError = false },
                label = { Text("任务名称（必填，≤20字）") },
                isError = nameError,
                supportingText = {
                    if (nameError) {
                        Text("名称不能为空", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("${name.length}/20")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            // ---- 备注（选填）----
            // 内容会显示在「选择要打卡的任务」列表里，便于区分同名或相似任务。
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_MAX_LENGTH) },
                label = { Text("备注（选填）") },
                placeholder = { Text("例如：早起后先喝一杯温水") },
                supportingText = { Text("${note.length}/$NOTE_MAX_LENGTH") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每日提醒", style = MaterialTheme.typography.titleMedium)
                    Text(
                        reminder?.let { "每天 $it 提醒" } ?: "未设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (reminder != null) {
                    TextButton(onClick = { reminder = null }) { Text("清除") }
                }
                Button(onClick = { showTimePicker = true }) { Text(if (reminder == null) "设置" else "修改") }
            }

            // ---- v5：任务组织（分组 / 标签 / 归档 / 暂停）------------------------
            // 这四个字段都是本机组织信息，不进云同步；界面与「名称/颜色」等同步字段
            // 同屏编辑，保存路径由 TaskEditViewModel 按「是否改了同步字段」区分。
            Spacer(Modifier.height(24.dp))
            Text("任务组织", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it.take(20) },
                label = { Text("分组（选填）") },
                placeholder = { Text("例如：健康、工作") },
                supportingText = { Text("同名的任务会在任务管理页归到一组显示") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it.take(80) },
                label = { Text("标签（选填）") },
                placeholder = { Text("例如：习惯, 晨间, 重要") },
                supportingText = { Text("用英文 , 或中文 ， 分隔，自动去重") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (tagPreview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TagChipRow(tagPreview)
            }

            Spacer(Modifier.height(16.dp))
            OrganizationSwitch(
                label = "暂停此任务",
                description = "不提醒、不计入今日目标，但主页仍可见、可手动打卡",
                checked = paused,
                onCheckedChange = { paused = it }
            )
            Spacer(Modifier.height(8.dp))
            OrganizationSwitch(
                label = "归档此任务",
                description = "从主页与提醒中隐藏，仅在本页「已归档」里可见、可恢复",
                checked = archived,
                onCheckedChange = { archived = it }
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        saveError = null
                        viewModel.save(
                            name, icon, color, reminder, note,
                            groupName, tags, archived, paused
                        ) { success, message ->
                            if (success) {
                                onDone()
                            } else {
                                // 保存失败时留在本页并说明原因，避免「返回了但没存上」的错觉。
                                saveError = message ?: "未知错误"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }

            saveError?.let { reason ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "保存失败：$reason",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showTimePicker) {
        val initial = reminder?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(9, 0)
        ReminderTimePicker(
            initial = initial,
            onConfirm = { reminder = it; showTimePicker = false },
            onDismiss = { showTimePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePicker(
    initial: LocalTime,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置提醒时间") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                val hh = state.hour.toString().padStart(2, '0')
                val mm = state.minute.toString().padStart(2, '0')
                onConfirm("$hh:$mm")
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 带说明文字的开关行：标题 + 语义说明 + Switch，供暂停 / 归档复用。 */
@Composable
private fun OrganizationSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
