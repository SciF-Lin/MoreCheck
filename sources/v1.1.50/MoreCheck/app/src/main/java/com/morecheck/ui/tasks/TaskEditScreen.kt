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
    var icon by remember { mutableStateOf(TaskIcons.all.first()) }
    var color by remember { mutableStateOf(TaskColors.all.first()) }
    var reminder by remember { mutableStateOf<String?>(null) }
    var showTimePicker by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(loaded) {
        loaded?.let {
            name = it.name
            icon = it.icon
            color = it.color
            reminder = it.reminderTime
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

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        saveError = null
                        viewModel.save(name, icon, color, reminder) { success, message ->
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
