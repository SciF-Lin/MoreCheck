package com.morecheck.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.domain.StreakSummary

/**
 * 下半区第一页：连续打卡详情。
 *
 * 当前 / 最长两个大数字 + 近 30 天热力条。热力条用 Row 加一排小方块，
 * 方块宽度由 [BoxWithConstraints] 按可用宽度均分算出来，窄屏也不会挤爆一行；
 * 样式沿用项目纪律：1dp 边框 + 与页面拉开一档的底色，不用 shadow
 * （软渲染器上那层投影会糊成灰罩），颜色全部取自 MaterialTheme.colorScheme。
 */
@Composable
fun StreakDetailPage(summary: StreakSummary, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "连续打卡",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (summary.todayCompleted) "今天已达标" else "今天待打卡",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (summary.todayCompleted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StreakNumber("当前连续（天）", summary.current, Modifier.weight(1f))
                StreakNumber("最长纪录（天）", summary.longest, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "近 30 天",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            StreakHeatBar(summary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "30 天前",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "今天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 详情卡里的一列大数字 + 说明。 */
@Composable
private fun StreakNumber(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 近 30 天达标热力条：30 个小方块，达标用主色、未达标用淡中性色。
 *
 * 空摘要的 last30Days 是空表，这里兜底成 30 个 false，首帧也画满一行，
 * 不会出现"先 0 个方块再跳成 30 个"的闪动。
 */
@Composable
private fun StreakHeatBar(summary: StreakSummary) {
    val doneFlags: List<Boolean> = if (summary.last30Days.isEmpty()) {
        List(30) { false }
    } else {
        summary.last30Days.map { it.second }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 2.dp
        // 30 个方块 + 29 道间隙正好铺满可用宽度；下限 4dp 防极窄屏算出非正数。
        val cell = ((maxWidth.value - gap.value * 29) / 30f).coerceAtLeast(4f).dp
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            doneFlags.forEach { done ->
                Box(
                    Modifier
                        .size(cell)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                            }
                        )
                )
            }
        }
    }
}

/**
 * 下半区第二页：按任务的完成率排行。
 *
 * 每行是"近 30 天打卡天数 / 30"，由 [TaskRankingRow] 直接带上任务名与颜色，
 * 不必再回查任务表。排行已在 ViewModel 里按天数降序排好，这里只负责渲染。
 * 任务多时列表在卡片内滚动（Pager 手势已关闭，竖向滚动不会与横滑抢手势）。
 */
@Composable
fun TaskRankingPage(rows: List<TaskRankingRow>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "近 30 天完成率排行",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) {
                Text(
                    "还没有在册任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    rows.forEachIndexed { index, row ->
                        RankingRow(rank = index + 1, row = row)
                        if (index != rows.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 排行里的一行：名次 + 任务图标 + 名称 + 天数进度条 + 天数文本。 */
@Composable
private fun RankingRow(rank: Int, row: TaskRankingRow) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color(row.color)),
            contentAlignment = Alignment.Center
        ) {
            Text(row.icon, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            // 用两层 Box 画进度条而不是 LinearProgressIndicator：这里只是"天数 / 30"
            // 的静态比例，不需要 M3 进度条的 gap / stopIndicator / 端点一堆参数。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((row.days / 30f).coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "${row.days}/30",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
