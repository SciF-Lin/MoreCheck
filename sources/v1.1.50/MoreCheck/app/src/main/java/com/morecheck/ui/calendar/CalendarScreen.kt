package com.morecheck.ui.calendar

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.CheckTask
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.util.DayState
import com.morecheck.util.fullLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenScanner: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    viewModel: CalendarViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val recordsByDate by viewModel.recordsByDate.collectAsStateWithLifecycle()
    val today by DayState.today.collectAsStateWithLifecycle()

    val initialMonth = YearMonth.from(today)
    val pagerState = rememberPagerState(initialPage = 1200) { 2400 }
    val scope = rememberCoroutineScope()
    val currentMonth = initialMonth.plusMonths((pagerState.currentPage - 1200).toLong())

    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日历") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("扫一扫") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenScanner()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("打卡记录") },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenRecords()
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        CalendarBody(
            modifier = Modifier.padding(padding),
            currentMonth = currentMonth,
            pagerState = pagerState,
            scope = scope,
            initialMonth = initialMonth,
            today = today,
            tasks = tasks,
            recordsByDate = recordsByDate,
            onDayClick = { selectedDate = it }
        )
    }

    selectedDate?.let { date ->
        DayDetailDialog(
            date = date,
            today = today,
            tasks = tasks,
            recordsByDate = recordsByDate,
            onBackfill = { taskId -> viewModel.backfill(taskId, date) },
            onUndo = { taskId -> viewModel.undo(taskId, date) },
            onDismiss = { selectedDate = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarBody(
    modifier: Modifier,
    currentMonth: YearMonth,
    pagerState: PagerState,
    scope: CoroutineScope,
    initialMonth: YearMonth,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: Map<String, Set<Long>>,
    onDayClick: (LocalDate) -> Unit
) {
    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1, animationSpec = tween(220)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            Text(
                "${currentMonth.year}年${currentMonth.monthValue}月",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(220)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月")
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(
                    it,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            val month = initialMonth.plusMonths((page - 1200).toLong())
            MonthGrid(
                month = month,
                today = today,
                tasks = tasks,
                recordsByDate = recordsByDate,
                onDayClick = onDayClick
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: Map<String, Set<Long>>,
    onDayClick: (LocalDate) -> Unit
) {
    val firstDay = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val leadingBlank = firstDay.dayOfWeek.value - 1 // Monday == 1
    val totalCells = leadingBlank + daysInMonth
    val rows = (totalCells + 6) / 7

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val index = row * 7 + col
                    if (index < leadingBlank || index >= totalCells) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(index - leadingBlank + 1)
                        DayCell(
                            date = date,
                            today = today,
                            tasks = tasks,
                            recordsByDate = recordsByDate,
                            onClick = { onDayClick(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: Map<String, Set<Long>>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val checkedIds = recordsByDate[date.toString()].orEmpty()
    val isToday = date == today
    val zone = ZoneId.systemDefault()

    val relevant = tasks.filter { task ->
        val created = Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()
        !date.isBefore(created)
    }
    val colored = relevant.filter { it.id in checkedIds }
    val missed = relevant.filter { it.id !in checkedIds && date.isBefore(today) }

    val dotColors = colored.map { Color(it.color) } + missed.map { Color(0xFFC4C9C7) }
    val shownDots = dotColors.take(5)
    val overflow = dotColors.size - shownDots.size

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isToday) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(8.dp)
        ) {
            shownDots.forEach { color ->
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            }
            if (overflow > 0) {
                Text(
                    "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayDetailDialog(
    date: LocalDate,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: Map<String, Set<Long>>,
    onBackfill: (Long) -> Unit,
    onUndo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val checkedIds = recordsByDate[date.toString()].orEmpty()
    val zone = ZoneId.systemDefault()
    // 补打卡只允许最近 7 天内的过去日期。
    val backfillable = date.isBefore(today) && !date.isBefore(today.minusDays(7))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(date.fullLabel()) },
        text = {
            Column {
                if (tasks.isEmpty()) {
                    Text("暂无任务")
                } else {
                    tasks.forEach { task ->
                        val created = Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()
                        if (date.isBefore(created)) return@forEach
                        val checked = task.id in checkedIds
                        val (status, statusColor, dotColor) = when {
                            checked -> Triple("已打卡", MaterialTheme.colorScheme.primary, Color(task.color))
                            date.isBefore(today) -> Triple("缺卡", Color(0xFFE57373), Color(0xFFBDBDBD))
                            else -> Triple("未打卡", MaterialTheme.colorScheme.onSurfaceVariant, Color(0xFFE0E0E0))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${task.icon} ${task.name}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            when {
                                checked -> TextButton(onClick = { onUndo(task.id) }) { Text("撤销") }
                                backfillable -> TextButton(onClick = { onBackfill(task.id) }) { Text("补打卡") }
                                else -> Text(status, color = statusColor, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (backfillable) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "最近 7 天内缺卡的日期可补打卡",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}
