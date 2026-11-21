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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.CheckTask
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.records.RecordsContent
import com.morecheck.ui.records.RecordsViewModel
import com.morecheck.util.DayState
import com.morecheck.util.fullLabel
import com.morecheck.util.toHhMm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 「统计」入口标题栏里的两个面板：统计（月历）/ 记录（热力图 + 时间线）。
 *
 * ## 为什么是标题栏标签，而不是「标题 + 跳转按钮」（v1.1.69 需求 2）
 *
 * 上一版这里是「统计」标题 + 右侧一个「记录」文字按钮，点了**跳二级页**。
 * 需求要求「记录放在统计的标题栏、原来的跳转按钮删除」，于是改成与「主页」标题栏
 * （「打卡 / 计时」）完全同构的**标签切换**：入口仍在标题栏，但它是切面板而非推页面。
 *
 * 这样有两个实际好处：① 底部导航栏始终高亮「统计」，不会因为看记录而"跳到别处"；
 * ② 月历与记录之间来回切不再压两层页面栈（此前每点一次都往栈里推一页）。
 *
 * ⚠️ `TabDestination.Records` 二级页**仍然保留**：「我的」页与各「+」菜单里的
 * 「打卡记录」还在用它（需求只要求改标题栏这一个入口）。
 */
private enum class CalendarPanel(val label: String) {
    CALENDAR("统计"),
    RECORDS("记录")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenScanner: () -> Unit = {},
    /**
     * 打开打卡记录**二级页**。
     *
     * ⚠️ v1.1.69 起本页标题栏的「记录」入口**不再调用它**（改为切到页内的「记录」面板，
     * 见 [CalendarPanel]）。这个形参保留是因为「我的」页与各「+」菜单（含本页「更多」菜单里的
     * 「打卡记录」项）仍在用它跳二级页——本页只是不再从标题栏走那条路。
     */
    onOpenRecords: () -> Unit = {},
    onNewTask: () -> Unit = {},
    /** 点标题栏年月进入「全年打卡」二级页；带默认值，保证调用方未接线也能编译。 */
    onOpenYearOverview: () -> Unit = {},
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

    // 标题栏标签选中的面板（v1.1.69 需求 2：把「记录」从主页挪到统计页）。
    //
    // 为什么放在「统计」而不是「主页」（上一轮的位置）：打卡记录本质是**统计**的产物
    // ——它和月历、全年总览回答的是同一个问题「我打得怎么样」；
    // 而主页的主动线是「我要打卡 / 我在计时」，再塞一个只读面板会跟主动线抢位置。
    //
    // 用 rememberSaveable：进程重建后仍停在同一面板（与 HomeScreen 的面板切换同口径）。
    var panel by rememberSaveable { mutableStateOf(CalendarPanel.CALENDAR) }

    Scaffold(
        topBar = {
            TopAppBar(
                // 标题栏是「统计 / 记录」两个**可切换标签**（仿主页的「打卡 / 计时」），
                // 而不是「标题 + 跳转按钮」——这就是需求「记录放在统计的标题栏、
                // 原来的跳转按钮删除」的落地形态：入口还在标题栏，但它切面板、不跳页面。
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CalendarPanel.entries.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.width(6.dp))
                            Text(
                                text = item.label,
                                fontSize = 20.sp,
                                fontWeight = if (panel == item) FontWeight.Bold else FontWeight.Normal,
                                color = if (panel == item) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.clickable { panel = item }
                            )
                        }
                    }
                },
                actions = {
                    // 页面右上角不放「+」：新建任务等入口收进「更多」菜单。
                    // 这里只换触发图标，菜单本身保持原样，三个回调仍有入口。
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    // 菜单外观与其它入口统一（需求：加号列表改为圆角 + 浅灰，与背景区分）。
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建任务") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onNewTask()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("扫一扫") },
                            leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
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
        // 面板切换（v1.1.69 需求 2）：标题栏两个标签各渲染一块内容。
        // 「记录」直接复用记录页的正文 [RecordsContent]——与「主页」当时内联它的口径一致：
        // 记录页与这个面板显示的必须是**同一份数字**，因此共用同一个 [RecordsViewModel]
        // （`viewModel()` 命中同一 ViewModelStore 缓存，切面板不重建），
        // 而不是在这里再抄一遍热力图 / 时间线的 combine 逻辑（两份实现迟早漂移）。
        when (panel) {
            CalendarPanel.CALENDAR -> CalendarBody(
                modifier = Modifier.padding(padding),
                currentMonth = currentMonth,
                pagerState = pagerState,
                scope = scope,
                initialMonth = initialMonth,
                today = today,
                tasks = tasks,
                recordsByDate = recordsByDate,
                onDayClick = { selectedDate = it },
                onOpenYearOverview = onOpenYearOverview
            )

            CalendarPanel.RECORDS -> {
                val recordsViewModel: RecordsViewModel =
                    viewModel(factory = AppViewModelProvider.Factory)
                val heatmap by recordsViewModel.heatmap.collectAsStateWithLifecycle()
                val timeline by recordsViewModel.timeline.collectAsStateWithLifecycle()
                RecordsContent(
                    heatmap = heatmap,
                    timeline = timeline,
                    today = today,
                    // 传 `Modifier.padding(padding)`：本面板在 Scaffold 内容槽位里，
                    // 顶部要避开标题栏（与日历面板完全一致的内边距口径）。
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    selectedDate?.let { date ->
        DayDetailDialog(
            date = date,
            today = today,
            tasks = tasks,
            recordsByDate = recordsByDate,
            onBackfill = { taskId -> viewModel.backfill(taskId, date) },
            onUndo = { taskId -> viewModel.undo(taskId, date) },
            onOpenRecords = onOpenRecords,
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
    recordsByDate: CheckedAtByDate,
    onDayClick: (LocalDate) -> Unit,
    onOpenYearOverview: () -> Unit
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
            // 年月文字可点：进入「全年打卡」总览页。
            // 只给这个 Text 加 clickable（而不是整行 Row），左右两侧的翻月 IconButton
            // 有自己的点击区域，两边互不重叠，不会抢手势。
            Text(
                "${currentMonth.year}年${currentMonth.monthValue}月",
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenYearOverview),
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
    recordsByDate: CheckedAtByDate,
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
    recordsByDate: CheckedAtByDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 月历格只需要「当天是否打过卡」，时间戳用不到，这里只取 taskId 集合。
    val checkedIds = recordsByDate[date.toString()].orEmpty().keys
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
    recordsByDate: CheckedAtByDate,
    onBackfill: (Long) -> Unit,
    onUndo: (Long) -> Unit,
    /** 点击任务名跳转到「打卡记录」页；由 CalendarScreen 透传它的 onOpenRecords。 */
    onOpenRecords: () -> Unit,
    onDismiss: () -> Unit
) {
    // 这里同时要「有没有打卡」和「几点打的卡」，所以直接用 taskId -> checkedAt 这张表。
    val checkedAtByTask = recordsByDate[date.toString()].orEmpty()
    val checkedIds = checkedAtByTask.keys
    val zone = ZoneId.systemDefault()
    // 补打卡只允许最近 7 天内的过去日期。
    val backfillable = date.isBefore(today) && !date.isBefore(today.minusDays(7))
    // 「待确认撤销」的任务。remember(date) 而非 remember：日期一换就丢弃上一次的待确认状态，
    // 避免关掉详情后重开另一天时，残留的 pendingUndo 立刻弹出一个指向旧任务的确认框。
    var pendingUndo by remember(date) { mutableStateOf<CheckTask?>(null) }

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
                            // 只把「未打卡」调淡：它是三种状态里最不重要的一种，
                            // 弱化后让「已打卡 / 缺卡」两种需要关注的状态更突出。
                            // 「已打卡」仍用 primary、「缺卡」仍用红色，都不动。
                            else -> Triple("未打卡", MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f), Color(0xFFE0E0E0))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(10.dp))
                            // 点击「圆点 + 任务名」这一块跳打卡记录。
                            // 只把 clickable 放在这个 weight(1f) 的容器上（而不是整行 Row），
                            // 是因为右侧的「撤销 / 补打卡」按钮也在这行里：容器在这里提前结束，
                            // 按钮区域不重叠，两者不会抢手势，点按钮也绝不会误触发跳转。
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpenRecords),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "${task.icon} ${task.name}",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    // 仅已打卡的任务在任务名下方补一行打卡时刻；
                                    // 未打卡不显示这一行（避免出现空行或 "—" 噪声）。
                                    if (checked) {
                                        Text(
                                            checkedAtByTask.getValue(task.id).toHhMm(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            when {
                                checked -> TextButton(
                                    // 先只记下「待撤销的任务」，真正的撤销放到确认框里做。
                                    onClick = { pendingUndo = task },
                                    // 「撤销」是破坏性次要操作，用 onSurface 压低透明度（灰）而不是主题主色，
                                    // 主题色留给「补打卡」这类正向操作；语义色自动适配深浅色。
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                                    )
                                ) { Text("撤销") }
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

    // 二次确认：这里没有把 ConfirmDialog 套在上面的 AlertDialog 里面（那会同时叠出两个窗口，
    // 视觉上很怪，且点任意一处都会同时命中两层），而是作为**兄弟**节点挂在同一个 Composable 里。
    // 两者的显示时机互斥——详情弹层是你点了某一天才有的，而本确认框只在点了「撤销」之后才出现；
    // 确认框出现时详情弹层依然在下面，形成「弹层之上再问一句」的层级，与 HomeScreen 的做法一致。
    pendingUndo?.let { task ->
        ConfirmDialog(
            title = "撤销打卡",
            text = "确定要撤销「${date.fullLabel()}」的「${task.name}」打卡吗？",
            confirmLabel = "撤销",
            onConfirm = {
                // 只有走到这里（用户点了确认）才真正撤销。
                onUndo(task.id)
                pendingUndo = null
            },
            onDismiss = { pendingUndo = null }
        )
    }
}
