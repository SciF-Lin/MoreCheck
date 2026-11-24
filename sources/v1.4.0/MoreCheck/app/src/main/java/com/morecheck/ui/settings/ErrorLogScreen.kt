package com.morecheck.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.ErrorLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误日志页（2026-11-14 需求 3）。
 *
 * ## 为什么给普通用户看这个
 *
 * 「App 崩了 / 某个操作报错了」是本项目最常见的反馈，但用户能提供的往往只有一句
 * "打不开"。本页把本机已经落盘的堆栈直接摆出来，并给一键复制——
 * 反馈时贴过来就能定位，不必再要求用户装 adb 抓 logcat。
 *
 * ## 内容口径
 *
 * - **只读本机**：`ErrorLogStore` 写在应用私有目录，不联网、不上传；
 * - 崩溃（CRASH）与已捕获的报错（ERROR）分开标色，前者是红点、后者是琥珀色；
 * - 空列表不是错误状态：新装的包本来就没有日志，页面直接说明"至今没有记录"，
 *   而不是给一个"加载失败"。
 *
 * ## 复制的是什么
 *
 * 复制的是**完整文本**（时间 + 级别 + 标题 + 堆栈），不是屏幕上截断的预览。
 * 预览为了排版只显示若干行，粘贴出去的必须是全文——否则用户以为给了全量，实际缺行。
 */
// ERROR 级固定琥珀色。不能用 colorScheme.tertiary：它被 withAccent 映射成用户
// 主题色 seed（默认绿），报错标签会绿得像成功态。
private val ErrorAmberLight = Color(0xFF9A6700)
private val ErrorAmberDark = Color(0xFFFFB74D)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val errorAccent =
        if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) ErrorAmberLight else ErrorAmberDark

    // 刷新计数：清空后 +1，重新从磁盘读一遍（页面不订阅文件变化，按需重读更简单）。
    var tick by remember { mutableStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val entries = remember(tick) { ErrorLogStore.entries(context) }
    val stamp = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA) }

    if (showClearConfirm) {
        ConfirmDialog(
            title = "清空错误日志？",
            text = "将删除本机保存的全部崩溃与报错记录（${entries.size} 条）。" +
                "已经反馈过的问题可以放心清理，不会影响任何使用数据。",
            confirmLabel = "清空",
            onConfirm = {
                showClearConfirm = false
                val removed = ErrorLogStore.clear(context)
                tick++
                onMessage(if (removed > 0) "已清空错误日志" else "没有可清理的日志")
            },
            onDismiss = { showClearConfirm = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("错误日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val text = ErrorLogStore.exportText(context)
                            if (text.isBlank()) {
                                onMessage("还没有日志可以复制")
                            } else {
                                clipboard.setText(AnnotatedString(text))
                                onMessage("已复制 ${entries.size} 条日志")
                            }
                        }
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(
                        onClick = { if (entries.isEmpty()) onMessage("还没有日志") else showClearConfirm = true }
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "清空")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            EmptyLogHint(Modifier.padding(innerPadding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "共 ${entries.size} 条 · 仅保存在本机，不会上传",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            items(entries, key = { it.time.toString() + it.title }) { entry ->
                val isCrash = entry.level == "CRASH"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (isCrash) MaterialTheme.colorScheme.error
                                        else errorAccent,
                                        RoundedCornerShape(50)
                                    )
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                text = stamp.format(Date(entry.time)),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isCrash) "崩溃" else "报错",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isCrash) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    errorAccent
                                }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = entry.title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.strongTextColor
                        )
                        if (entry.detail.isNotBlank()) {
                            var expanded by remember(entry.time) { mutableStateOf(false) }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = entry.detail.trimEnd(),
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                maxLines = if (expanded) Int.MAX_VALUE else 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { expanded = !expanded }
                            )
                            if (!expanded && entry.detail.lineSequence().count() > 6) {
                                Text(
                                    "点按堆栈可展开 / 收起",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 没有日志时的空状态：说明"没崩过"是好事，而不是加载失败。 */
@Composable
private fun EmptyLogHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.BugReport,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
            modifier = Modifier.size(46.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "还没有错误记录",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "应用崩溃或某次操作报错时，这里会自动留下记录。\n" +
                "遇到问题时可以把这里的日志复制给开发者。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
    }
}
