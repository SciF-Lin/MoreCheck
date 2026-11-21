package com.morecheck.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 时钟刷新间隔：每秒一跳，保证秒位准点变化。 */
private const val CLOCK_TICK_MS = 1_000L

/**
 * 「我的」一级入口（任务3）。
 *
 * 结构自下而上：
 * 1. 顶部当前时间 `HH:mm:ss`（24 小时制，精确到秒，每秒刷新）；
 * 2. 头像区——点击进入「个人信息」；
 * 3. 功能列表——任务管理 / 打卡记录 / 设置 / 关于我们 + 两个占位条目。
 *
 * 标题栏已移入本页（不再由全局骨架绘制），避免进入二级页时骨架重排造成界面割裂。
 * 底部的「主页 / 日历 / 发现 / 我的」仍由全局骨架提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    onOpenPersonalInfo: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenPlaceholder: (String) -> Unit,
    maskedPhone: String?
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) }
    ) { padding ->
        MyContent(
            modifier = Modifier.padding(padding),
            onOpenPersonalInfo = onOpenPersonalInfo,
            onOpenTasks = onOpenTasks,
            onOpenRecords = onOpenRecords,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            onOpenBackup = onOpenBackup,
            onOpenPlaceholder = onOpenPlaceholder,
            maskedPhone = maskedPhone
        )
    }
}

@Composable
private fun MyContent(
    modifier: Modifier,
    onOpenPersonalInfo: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenPlaceholder: (String) -> Unit,
    maskedPhone: String?
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        CurrentTimeHeader()
        Spacer(Modifier.height(12.dp))
        ProfileCard(
            maskedPhone = maskedPhone,
            onClick = onOpenPersonalInfo
        )
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column {
                val entries = listOf(
                    MineEntry("tasks", "任务管理", "新建 / 编辑 / 删除任务与每日提醒", Icons.Filled.Checklist, onOpenTasks),
                    MineEntry("records", "打卡记录", "热力图与历史时间线", Icons.Filled.History, onOpenRecords),
                    MineEntry("settings", "设置", "深色模式与动态配色", Icons.Filled.Settings, onOpenSettings)
                )
                entries.forEachIndexed { index, entry ->
                    MineRow(entry)
                    if (index != entries.lastIndex) MineDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 两个占位条目：结构与样式与上面的列表完全一致，仅内容为空。
        Card(Modifier.fillMaxWidth()) {
            Column {
                val placeholders = listOf(
                    MineEntry("backup", "数据备份", "备份与恢复打卡数据", Icons.Filled.Backup) {
                        onOpenBackup()
                    },
                    MineEntry("feedback", "帮助与反馈", "常见问题与意见反馈", Icons.Filled.HelpOutline) {
                        onOpenPlaceholder("feedback")
                    }
                )
                placeholders.forEachIndexed { index, entry ->
                    MineRow(entry)
                    if (index != placeholders.lastIndex) MineDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            MineRow(
                MineEntry("about", "关于我们", "版本号、用户协议与隐私政策", Icons.Filled.Info, onOpenAbout)
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 顶部当前时间。
 *
 * 用 `LaunchedEffect` + `delay(1s)` 每秒重算 `LocalTime.now()`；
 * 每到整秒会重新赋值 state 从而触发重组，时间随之跳动。
 */
@Composable
private fun CurrentTimeHeader() {
    var now by remember { mutableStateOf(LocalTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(CLOCK_TICK_MS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = now.format(ClockFormatter),
            // 等宽字体：秒位跳动时数字宽度一致，不会左右抖动。
            fontFamily = FontFamily.Monospace,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "当前时间",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileCard(
    maskedPhone: String?,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(60.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = "头像",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = if (maskedPhone == null) "未登录" else "已登录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = maskedPhone ?: "点击查看个人信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class MineEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun MineRow(entry: MineEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MineDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
