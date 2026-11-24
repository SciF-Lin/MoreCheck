package com.morecheck.ui.backup

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.CheckRecord
import com.morecheck.data.CheckTask
import com.morecheck.di.appContainer
import com.morecheck.domain.RecordInfo
import com.morecheck.domain.TaskInfo
import com.morecheck.domain.WeeklyReport
import java.time.LocalDate
import kotlin.math.roundToInt

// Room 实体 → 周报纯数据类的映射集中在这里。WeeklyReport 刻意不认识 Room，
// 测试才不必依赖 android.* 或 Room 实体。CSV 的映射走 CheckInCsv 的实体重载。
internal fun CheckTask.toTaskInfo(): TaskInfo = TaskInfo(
    id = id,
    name = name,
    groupName = groupName
)

internal fun CheckRecord.toRecordInfo(): RecordInfo = RecordInfo(
    taskId = taskId,
    date = date,
    checkedAt = checkedAt
)

/**
 * 备份页内的「本周打卡周报」分区。
 *
 * 不新增路由、不引入 ViewModel：数据量小，直接把仓库的两条 Flow 收成状态，
 * 在 Composable 里映射成纯数据类再交给 [WeeklyReport.build] 计算。这样统计逻辑
 * 全部留在纯 JVM 模块里可测，这一层只负责显示。
 */
@Composable
fun WeeklyReportSection(
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.taskRepository }
    // 显式写出元素类型：emptyList() 不带类型时 R 会被推成 List<Nothing>，
    // 后面 .map { it.toTaskInfo() } 就找不到扩展了。
    val activeTasks by repository.activeTasks.collectAsStateWithLifecycle(
        initialValue = emptyList<CheckTask>()
    )
    val allRecords by repository.allRecords.collectAsStateWithLifecycle(
        initialValue = emptyList<CheckRecord>()
    )

    val today = LocalDate.now()
    val report = remember(activeTasks, allRecords, today) {
        WeeklyReport.build(
            tasks = activeTasks.map { it.toTaskInfo() },
            records = allRecords.map { it.toRecordInfo() },
            today = today
        )
    }

    val scheme = MaterialTheme.colorScheme
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "本周打卡周报",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${report.weekStart} ~ ${report.today} · 共 ${report.totalTasks} 个任务",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            // 完成率作为主指标，用大号数字压住整张卡；下面三项做成并排的小指标。
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${(report.completionRate * 100).roundToInt()}%",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "本周完成率\n${report.achievedDays} / 7 天达标",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric(Modifier.weight(1f), "最佳任务", report.bestTaskName ?: "暂无")
                Metric(Modifier.weight(1f), "当前连续", "${report.currentStreak} 天")
                Metric(Modifier.weight(1f), "最长连续", "${report.longestStreak} 天")
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "最近 7 天（每日打卡任务数 / 任务总数）",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            WeekBars(report = report)

            Spacer(Modifier.height(16.dp))
            Text(
                text = "任务打卡天数排行",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            if (report.taskDayCounts.isEmpty()) {
                Text(
                    text = "本周还没有打卡记录，先去打个卡吧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            } else {
                report.taskDayCounts.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            text = item.taskName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${item.days} / 7 天",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { shareWeeklyReport(context, report.toShareText(), onMessage) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("分享周报")
            }
        }
    }
}

@Composable
private fun Metric(modifier: Modifier, label: String, value: String) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** 7 天文本柱状条：每天一根，高度按当天任务完成率，达标日上主色、未达标日上灰底。 */
@Composable
private fun WeekBars(report: WeeklyReport) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        report.days.forEach { day ->
            // 给未达标日也留 10dp 的短条，避免"没有数据"和"有 0 条"看起来一样。
            val barHeight = (10 + 64 * day.dailyRate).dp
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = day.checkedCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .height(barHeight)
                        .width(20.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (day.achieved) scheme.primary else scheme.surfaceVariant
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = weekdayLabel(day.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    text = "${day.date.monthValue}/${day.date.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = scheme.outline
                )
            }
        }
    }
}

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

/** 把周报拼成一段可直接分享的中文文本。 */
private fun WeeklyReport.toShareText(): String = buildString {
    append("MoreCheck 本周打卡周报（$weekStart ~ $today）\n")
    append("本周完成率：${(completionRate * 100).roundToInt()}%（$achievedDays / 7 天达标）\n")
    append("当前连续：$currentStreak 天 · 最长连续：$longestStreak 天\n")
    append("最佳任务：${bestTaskName ?: "暂无"}\n")
    append("\n最近 7 天：\n")
    days.forEach { day ->
        append("${day.date}（${weekdayLabel(day.date)}）：${day.checkedCount}/$totalTasks\n")
    }
    append("\n任务打卡天数排行：\n")
    if (taskDayCounts.isEmpty()) {
        append("本周暂无打卡记录\n")
    } else {
        taskDayCounts.forEachIndexed { index, item ->
            append("${index + 1}. ${item.taskName}：${item.days} 天\n")
        }
    }
}

/** 系统分享面板。参考项目里 `TimetableScreen` 的写法：零权限、零 FileProvider。 */
private fun shareWeeklyReport(context: Context, text: String, onMessage: (String) -> Unit) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "本周打卡周报")
    }
    val chooser = Intent.createChooser(send, "分享本周打卡周报").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { onMessage("没有可用的分享应用") }
}
