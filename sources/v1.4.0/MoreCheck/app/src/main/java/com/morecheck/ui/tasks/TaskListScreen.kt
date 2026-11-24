package com.morecheck.ui.tasks

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.CheckTask
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.common.pressScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: TasksViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    var deleteTarget by remember { mutableStateOf<CheckTask?>(null) }
    val context = LocalContext.current

    // 一次性提示沿用项目里的系统 Toast（lab / settings 也这么用），
    // 不额外引入 Snackbar 状态机；ViewModel 不持有 Context，提示留在界面层。
    val toast: (String) -> Unit = { message ->
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    // 筛选与分组都是纯判定（TaskOrganization.kt），这里只负责把结果画出来。
    val displayTasks = tasks.filterTasks(filter)
    val groups = displayTasks.groupTasks()
    val showHeaders = displayTasks.hasNamedGroups()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "添加任务")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TaskFilterRow(selected = filter, onSelect = { filter = it })

            if (displayTasks.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (tasks.isEmpty()) "还没有任务，点击右下角 + 添加"
                        else "当前筛选下没有任务",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 没有命名分组时 groups 只有一个「未分组」桶，不输出小标题即扁平列表。
                    groups.forEach { group ->
                        if (showHeaders) {
                            item(key = "header-${group.title}") { TaskGroupHeader(group.title) }
                        }
                        items(group.tasks, key = { it.id }) { task ->
                            TaskRow(
                                task = task,
                                onClick = { onEdit(task.id) },
                                onDelete = { deleteTarget = task },
                                onArchive = { target ->
                                    viewModel.setArchived(task, target)
                                    toast(
                                        if (target) "已归档「${task.name}」"
                                        else "已取消归档「${task.name}」"
                                    )
                                },
                                onPause = { target ->
                                    viewModel.setPaused(task, target)
                                    toast(
                                        if (target) "已暂停「${task.name}」"
                                        else "已恢复「${task.name}」"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${task.name}」") },
            text = {
                Column {
                    Text("请选择删除方式：")
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = { viewModel.deleteTask(task, deleteHistory = true); deleteTarget = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("删除任务及其历史记录") }
                    TextButton(
                        onClick = { viewModel.deleteTask(task, deleteHistory = false); deleteTarget = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("仅删除任务（保留记录）") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun TaskRow(
    task: CheckTask,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onPause: (Boolean) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable 的默认水波纹。
    Card(
        Modifier
            .fillMaxWidth()
            .pressScale(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(task.color)),
                contentAlignment = Alignment.Center
            ) { Text(task.icon, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.name, style = MaterialTheme.typography.titleMedium)
                if (task.reminderTime != null) {
                    Text(
                        "每天 ${task.reminderTime} 提醒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val tags = task.tagList()
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TagChipRow(tags)
                }
                StatusBadges(
                    archived = task.archived,
                    paused = task.paused,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
            }
            // 归档 / 暂停放溢出菜单：保留原有删除按钮与整行编辑，不挤占原本就窄的行宽。
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (task.archived) "取消归档" else "归档") },
                        onClick = {
                            menuExpanded = false
                            onArchive(!task.archived)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (task.paused) "恢复" else "暂停") },
                        onClick = {
                            menuExpanded = false
                            onPause(!task.paused)
                        }
                    )
                }
            }
            // 2026-10-30：整行本来就是「点进去编辑」，但之前右侧只有一个红色垃圾桶，
            // 看不出这行可点——用户反馈"任务管理里没法改任务"。
            // 补一个右箭头当可点提示（图标不做点击，点它等于点整行）。
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
