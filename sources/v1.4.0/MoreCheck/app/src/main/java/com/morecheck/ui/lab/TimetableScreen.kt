package com.morecheck.ui.lab

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer
import com.morecheck.data.Course
import com.morecheck.data.Timetable
import com.morecheck.data.TimetableRepository
import com.morecheck.ui.common.pressScale
import com.morecheck.util.DayState
import com.morecheck.util.weekdayLabel
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 「实验室 → 课程表」—— v1.1.74 首版；本轮（v1.1.77 需求 1）按参考网页
 * （WakeUp 导出的 HTML 课表）与《课表.txt》完善。
 *
 * ## 三个视图（与参考页的 tabs 逐一对应）
 *
 * - **今天**（[TodayView]）：顶部「第 N 教学周」（[teachingWeekOf]）+ 今天的课程列表 +
 *   每节课的进行状态（未开始 / 进行中含进度条 / 已结束）+ 空课时的"下一节课"提示；
 * - **本周**（[WeekView]）：顶部"上一周 / 下一周"翻页 + 一周日期条（移动端形态，
 *   点选某天后下方列出该天的课）——参考页在窄屏走的就是这个形态，手机上直接采用它；
 * - **全部课程**（[AllCoursesView]）：按「课程名 + 星期 + 时刻 + 地点 + 教师」聚合的
 *   课程卡片列表（每门课一张卡，含开课日期范围与次数）。
 *
 * ## 本轮新增（对照《课表.txt》逐项）
 *
 * - **第 N 教学周**：以课表里最早的上课日为第一周周一（WakeUp 素材的学期起点），
 *   头部显示「第 N 教学周」；
 * - **30 秒自动刷新状态**：`now` 每半分钟重取一次，未开始 → 进行中 → 已结束
 *   的状态翻转不用重进页面；
 * - **1 小时内倒计时**：未开始的课若在一小时内上课，状态行显示「还有 X 分钟上课」
 *   （一小时以上仍显示，格式为「X 小时 Y 分钟」，口径与参考页一致）。
 *
 * ## 保留的既有能力
 *
 * - **搜索**（标题栏）：整份课表按课程名 / 地点 / 教师 / 节次搜（口径未变）；
 * - **导出分享**（标题栏）：本周课表纯文本（[buildWeekText] 一字未动）。
 *
 * ## 课程配色
 *
 * 每门课按名字哈希从 12 色调色板取色（[courseColorOf]）：同一门课在任何视图里同色、
 * 不随主题翻转；颜色只用于卡片左侧竖条与全部课程卡顶边，文字仍走主题色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // 从容器取，不自建：课表是应用自带的静态素材，本页可能被多次进入，
    // 自建仓库等于把同一份 assets 反复读一遍、解析一遍。
    val repository: TimetableRepository =
        remember { context.appContainer.timetableRepository }

    // "今天"走全应用共享的 [DayState]：零点换日时它会被刷新，
    // 本页的"今天"跟着翻（与旧版同口径）。
    val today by DayState.today.collectAsStateWithLifecycle()

    // 整份课表只解析一次（produceState，与旧版同口径）。
    val timetable by produceState<Timetable?>(initialValue = null) {
        value = repository.load()
    }

    // 当前视图 tab（今天 / 本周 / 全部课程）。rememberSaveable：进程重建后仍停在原视图。
    var viewTab by rememberSaveable { mutableStateOf(TimetableViewTab.TODAY) }

    // 本周视图当前查看的周（周一）。默认本周；翻页后指向别周。
    // ⚠️ LocalDate 不能直接 rememberSaveable（无 Saver）：存 ISO 字符串，读回时解析。
    // 今天跨零点刷新时不强制跳回：用户翻到别周看课表时被打断是更糟的体验。
    var weekStartIso by rememberSaveable { mutableStateOf(repository.weekStartOf(today).toString()) }
    val weekStart: LocalDate = remember(weekStartIso) {
        runCatching { LocalDate.parse(weekStartIso) }
            .getOrDefault(repository.weekStartOf(today))
    }

    // 本周视图里点选的那一天（同样以 ISO 字符串保存；null = 未选）。
    var weekSelectedIso by rememberSaveable { mutableStateOf<String?>(null) }
    val weekSelectedDate: LocalDate? = weekSelectedIso?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    // ── 「本月」视图当前查看的月份（2026-10-25 需求）────────────────────────
    //
    // 与 weekStartIso 同一套保存策略：YearMonth 没有 Saver，存 ISO 字符串
    // （"2026-10"），读回时解析；默认本月。切进本月视图时若浏览位置过期
    // （上次翻到了别的月），onSelect 里会复位到本月（与「本周」视图同一口径）。
    var viewMonthIso by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val viewMonth: YearMonth = remember(viewMonthIso, today) {
        runCatching { YearMonth.parse(viewMonthIso) }
            .getOrDefault(YearMonth.from(today))
    }

    // ── 课程详情弹窗（v1.1.79 需求 2）───────────────────────────────────────
    // 点任意一节课弹出：课程名 / 时间 / 节次 / 日期 / 地点 / 教师。
    // 用对话框而不是导航二级页：字段只有六项，一屏放得下，看完就关，不该占返回栈。
    var detailCourse by remember { mutableStateOf<Course?>(null) }

    // ── 上课提醒（v1.1.79 需求 2）───────────────────────────────────────────
    //
    // 设置与排程器都从 `AppContainer` 取（进程内单例）：排程器读的是同一份课表缓存，
    // 自建实例会多解析一遍 assets，且"关掉开关"时可能撤销不到旧闹钟。
    val appContainer = remember(context) { context.appContainer }
    val reminderEnabled by appContainer.settingsRepository.timetableReminderEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val reminderLead by appContainer.settingsRepository.timetableReminderLeadMinutes
        .collectAsStateWithLifecycle(initialValue = 10)
    val scope = rememberCoroutineScope()

    // 标题栏「上课提醒」图标的展开状态（本轮需求：设置条改为标题栏入口）。
    var reminderMenuOpen by remember { mutableStateOf(false) }

    // 搜索（标题栏）：口径与旧版一致，搜**整份课表**。
    var keyword by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    val searching = keyword.isNotBlank()

    // ── 本轮新增：状态自动刷新（每 30 秒重取一次"now"）──────────────────────
    //
    // tick 只是"让组合重新读一次 LocalTime.now()"的驱动：它变化 → 读 now 的
    // remember 失效 → 状态（未开始/进行中/已结束）与倒计时随之更新。
    // 30 秒一拍与参考页 `setInterval(render, 30000)` 同口径；用循环 + delay
    // 而不是 `java.util.Timer`：协程在离开页面时随组合取消，不泄漏。
    var renderTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(RENDER_TICK_MS)
            renderTick++
        }
    }

    val searchResult by produceState<List<Course>?>(
        initialValue = null,
        keyword,
        timetable
    ) {
        value = if (searching && timetable != null) repository.search(keyword) else null
    }

    Scaffold(
        topBar = {
            TimetableTopBar(
                onBack = onBack,
                query = keyword,
                searchOpen = searchOpen,
                onQueryChange = { keyword = it },
                onCloseSearch = {
                    searchOpen = false
                    keyword = ""
                },
                onOpenSearch = { searchOpen = true },
                reminderEnabled = reminderEnabled,
                reminderLeadMinutes = reminderLead,
                reminderMenuOpen = reminderMenuOpen,
                onToggleReminderMenu = { reminderMenuOpen = !reminderMenuOpen },
                onDismissReminderMenu = { reminderMenuOpen = false },
                onToggleReminder = { on ->
                    scope.launch {
                        appContainer.settingsRepository.setTimetableReminderEnabled(on)
                        runCatching { appContainer.courseReminderScheduler.rescheduleAll() }
                        onMessage(
                            if (on) "已开启上课提醒（${leadLabel(reminderLead)}）"
                            else "已关闭上课提醒"
                        )
                    }
                },
                onSelectReminderLead = { minutes ->
                    scope.launch {
                        appContainer.settingsRepository.setTimetableReminderLeadMinutes(minutes)
                        runCatching { appContainer.courseReminderScheduler.rescheduleAll() }
                        onMessage("提醒时间：${leadLabel(minutes)}")
                    }
                },
                onShare = {
                    val text = buildWeekText(
                        timetable = timetable,
                        weekStart = repository.weekStartOf(today),
                        today = today
                    )
                    shareText(context, text, onMessage)
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // ── 视图切换 tabs（参考页的胶囊按钮）────────────────────────────
            if (!searching) {
                TimetableViewTabs(
                    selected = viewTab,
                    onSelect = {
                        viewTab = it
                        // 切进本周视图时把选中日期重置为"今天在该周的落点"，
                        // 避免上次离开时残留的旧日期让用户以为视图坏了。
                        if (it == TimetableViewTab.WEEK) {
                            weekStartIso = repository.weekStartOf(today).toString()
                            weekSelectedIso = today
                                .takeIf { d -> repository.weekStartOf(d) == repository.weekStartOf(today) }
                                ?.toString()
                        }
                        // 切进本月视图时把浏览月份复位到本月（与「本周」同一口径：
                        // 上次翻到别的月属于过期位置，不该带进这次）。
                        if (it == TimetableViewTab.MONTH) {
                            viewMonthIso = YearMonth.from(today).toString()
                        }
                    }
                )

                // ── 「回到今日」（2026-10-25 需求）───────────────────────────
                //
                // 不在「今天」视图时显示（本周翻到别周 / 本月翻到别月时尤其有用）：
                // 一键切回今天视图，并顺带复位周/月浏览位置，下次进入不残留。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .pressScale(onClick = {
                                viewTab = TimetableViewTab.TODAY
                                weekStartIso = repository.weekStartOf(today).toString()
                                viewMonthIso = YearMonth.from(today).toString()
                            })
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "回到今日",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            when {
                timetable == null -> LoadingHint()
                searching -> SearchResultList(
                    result = searchResult,
                    keyword = keyword,
                    onOpenCourse = { detailCourse = it }
                )

                else -> when (viewTab) {
                    TimetableViewTab.TODAY -> TodayView(
                        timetable = timetable,
                        today = today,
                        renderTick = renderTick,
                        onOpenCourse = { detailCourse = it }
                    )

                    TimetableViewTab.WEEK -> WeekView(
                        timetable = timetable,
                        today = today,
                        renderTick = renderTick,
                        weekStart = weekStart,
                        onWeekStartChange = { weekStartIso = it.toString() },
                        selectedDate = weekSelectedDate
                            ?: today.takeIf { repository.weekStartOf(it) == weekStart },
                        onDateSelect = { weekSelectedIso = it.toString() },
                        onOpenCourse = { detailCourse = it }
                    )

                    // 「本月」（2026-10-25 需求）：整月按天分组的课程清单。
                    TimetableViewTab.MONTH -> MonthView(
                        timetable = timetable,
                        today = today,
                        renderTick = renderTick,
                        month = viewMonth,
                        onMonthChange = { viewMonthIso = it.toString() },
                        onOpenCourse = { detailCourse = it }
                    )

                    TimetableViewTab.ALL -> AllCoursesView(
                        timetable = timetable
                    )
                }
            }
        }
    }

    // 课程详情弹窗（v1.1.79 需求 2）：点任意一节课卡片弹出。
    // 写在 Scaffold 内容之外：对话框是独立窗口，不参与页面布局。
    detailCourse?.let { course ->
        CourseDetailDialog(
            course = course,
            weekNo = teachingWeekOf(timetable, course.date),
            onDismiss = { detailCourse = null }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 上课提醒（v1.1.79 需求 2；2026-10-22 改为标题栏图标展开）
// ─────────────────────────────────────────────────────────────────────────────

/** 提前量（分钟）→ 界面文案。三档与 `SettingsRepository.TIMETABLE_LEAD_OPTIONS` 一致。 */
private fun leadLabel(minutes: Int): String = when (minutes) {
    0 -> "开始时"
    60 -> "1 小时前"
    else -> "10 分钟前"
}

/**
 * 标题栏铃铛里的**提醒设置面板**（2026-10-22 需求：「课程表上课提醒改为标题栏图标，
 * 点击后展开列表」）。
 *
 * ## 为什么从页内设置条搬到标题栏
 *
 * 原来的 [CourseReminderBar] 常驻在视图切换 tabs 与课程列表之间，占掉一整行多的高度，
 * 而它是一学期设一次的东西——对"今天有什么课"这个主诉求来说是纯噪声。
 * 搬进标题栏后，列表多出那一行，且设置入口在语义上归位（提醒属于整页，不属于某个视图）。
 *
 * ## 形态
 *
 * `DropdownMenu` 挂在铃铛图标下面，内容是一段**短列表**：
 * 一行总开关 + 三档提前量（关闭时只留一行开关，与旧口径一致：
 * 关闭状态下的三档没有任何可操作性，隐藏比置灰更省高度）。
 *
 * ⚠️ 用 `DropdownMenu` 而不是自绘面板：它自带点击外部/返回键关闭、
 * 以及贴边避让（窄屏上不会顶出屏幕），这正是"点图标展开、点别处收起"要的行为；
 * 自绘这些细节的成本远高于收益。
 */
@Composable
private fun ReminderMenuContent(
    enabled: Boolean,
    leadMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onSelectLead: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(248.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "上课提醒",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (enabled) "按课表自动提醒" else "已关闭",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 10, 60).forEach { minutes ->
                    val selected = minutes == leadMinutes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                            .pressScale(onClick = { onSelectLead(minutes) })
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = leadLabel(minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) scheme.primary else scheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (selected) {
                            Text(
                                text = "已选",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 课程详情弹窗（v1.1.79 需求 2）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一节课的详情：课程名 / 时间 / 节次 / 日期与教学周 / 地点 / 教师。
 *
 * 字段全部取自解析后的 [Course]（`periodText` = ICS 的 DESCRIPTION 第一行、
 * `teacher` = 第三行），不重新解析原始 ICS——与课表其它视图同一数据源，
 * 避免"详情页与列表不是同一份事实"。
 */
@Composable
private fun CourseDetailDialog(
    course: Course,
    weekNo: Int?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            Text(
                text = course.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogRow("时间", "${course.startTime.hhMm()} – ${course.endTime.hhMm()}")
                if (course.periodText.isNotBlank()) DialogRow("节次", course.periodText)
                DialogRow(
                    "日期",
                    "${course.date.monthValue}月${course.date.dayOfMonth}日 " +
                        course.date.weekdayLabel() +
                        (weekNo?.let { " · 第 $it 教学周" } ?: "")
                )
                if (course.location.isNotBlank()) DialogRow("地点", course.location)
                if (course.teacher.isNotBlank()) DialogRow("教师", course.teacher)
            }
        }
    )
}

/** 详情弹窗里的一行「标签 + 值」。 */
@Composable
private fun DialogRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 视图枚举与 tabs
// ─────────────────────────────────────────────────────────────────────────────

/** 课程表的四个视图（「本月」为 2026-10-25 新增）。 */
private enum class TimetableViewTab(val label: String) {
    TODAY("今天"),
    WEEK("本周"),
    MONTH("本月"),
    ALL("全部课程")
}

/**
 * 视图切换 tabs：三枚胶囊按钮，选中态用主题色实底。
 *
 * 视觉照参考页的 `.tabs`（圆角胶囊组、选中反色）。本页做不出 HTML 的
 * `width: fit-content` 居中效果，改用「三枚等宽、整体留出左右边距」——
 * 手机宽度下三枚等宽更整齐，也不需要测量文本宽度。
 */
@Composable
private fun TimetableViewTabs(
    selected: TimetableViewTab,
    onSelect: (TimetableViewTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimetableViewTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Transparent,
                animationSpec = tween(150),
                label = "timetableTabBg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(background)
                    .pressScale(onClick = { onSelect(tab) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 视图一：今天
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「今天」视图：教学周 + 日期头 + 当天课程列表（含进行状态）。
 *
 * 状态判定与参考页 `statusOf` 一致：
 * - `now < start` → **未开始**（橙色胶囊，附"还有 X 分钟/X 小时 Y 分钟上课"）；
 * - `start <= now <= end` → **进行中**（绿色胶囊 + 进度条）；
 * - `now > end` → **已结束**（灰色胶囊）。
 *
 * @param renderTick 30 秒一跳的心跳值：它变化时 `now` 重新计算，状态随之刷新
 *   （本函数体内所有读 now 的 remember 都以它为 key）。
 */
@Composable
private fun TodayView(
    timetable: Timetable?,
    today: LocalDate,
    renderTick: Int,
    onOpenCourse: (Course) -> Unit
) {
    val courses = remember(timetable, today) { timetable?.coursesOn(today).orEmpty() }
    val now = remember(today, renderTick) { LocalTime.now() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 头部：教学周 + 日期 + 计数（参考页 header 的 week-badge + today-head）。
        item(key = "today-head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "今天 ${today.weekdayLabel()} 的课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (courses.isNotEmpty()) {
                        Text(
                            text = "共 ${courses.size} 节",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val week = teachingWeekOf(timetable, today)
                if (week != null) {
                    Text(
                        text = "第 $week 教学周",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        if (courses.isEmpty()) {
            item(key = "today-empty") {
                // 空课时给"下一节课"提示（参考页 .empty 的 next 行）。
                val next = remember(timetable, today) {
                    timetable?.sortedCourses?.firstOrNull { it.date > today }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🌤️",
                        style = MaterialTheme.typography.displaySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "今天没有课，好好休息～",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "下一节课：${next.summary}　" +
                                "${next.date.monthValue}月${next.date.dayOfMonth}日" +
                                "（${next.date.weekdayLabel()}）${next.startTime.hhMm()} · " +
                                next.location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        } else {
            items(courses, key = { "${it.start}-${it.summary}" }) { course ->
                CourseCard(course = course, showStatus = true, now = now, onClick = { onOpenCourse(course) })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 视图二：本周
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「本周」视图：周翻页 + 一周日期条 + 选中日的课程列表。
 *
 * ## 为什么不做 7 列周网格（沿用旧版的取舍，理由更新）
 *
 * 参考页在窄屏（<=760px）隐藏周网格、改走"日期条 + 单日列表"——说明**素材作者自己**
 * 也在手机上放弃了 7 列。本页直接采用它的移动端形态：横向 7 格只放日期绰绰有余，
 * 纵向完整展开每个字段（课程名最长 14 字、地点最长 66 字符，170px 的列宽必然截断）。
 */
@Composable
private fun WeekView(
    timetable: Timetable?,
    today: LocalDate,
    renderTick: Int,
    weekStart: LocalDate,
    onWeekStartChange: (LocalDate) -> Unit,
    selectedDate: LocalDate?,
    onDateSelect: (LocalDate) -> Unit,
    onOpenCourse: (Course) -> Unit
) {
    val days = remember(weekStart) { (0L..6L).map { weekStart.plusDays(it) } }
    val now = remember(today, renderTick) { LocalTime.now() }

    Column(Modifier.fillMaxSize()) {
        // 周翻页行（参考页 .week-nav）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onWeekStartChange(weekStart.minusDays(7)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
            }
            Text(
                text = "${weekStart.monthValue}月${weekStart.dayOfMonth}日 – " +
                    "${weekStart.plusDays(6).monthValue}月${weekStart.plusDays(6).dayOfMonth}日",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onWeekStartChange(weekStart.plusDays(7)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
            }
        }

        // 一周日期条：点选切换，今天描边、选中实底、有课加粗（参考页 .mobile-days）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            days.forEach { day ->
                val dayCourses = timetable?.coursesOn(day).orEmpty()
                WeekDayChip(
                    day = day,
                    isToday = day == today,
                    isSelected = day == selectedDate,
                    hasCourse = dayCourses.isNotEmpty(),
                    onClick = { onDateSelect(day) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        // 选中日的课程列表。
        val selected = selectedDate
        if (selected == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "点上方日期查看那一天的课",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val courses = remember(timetable, selected) {
                timetable?.coursesOn(selected).orEmpty()
            }
            if (courses.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🍃", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这一天没有课～",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(courses, key = { "${it.start}-${it.summary}" }) { course ->
                        // 只有"看的是今天"时才显示进行状态（参考页同口径：
                        // 别的日期谈"进行中"没有意义）。
                        CourseCard(
                            course = course,
                            showStatus = selected == today,
                            now = now,
                            onClick = { onOpenCourse(course) }
                        )
                    }
                }
            }
        }
    }
}

/** 一周日期条上的单格（参考页 .mobile-days button 的形态）。 */
@Composable
private fun WeekDayChip(
    day: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasCourse: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = when {
            isSelected -> scheme.primary
            else -> scheme.surfaceContainer
        },
        animationSpec = tween(150),
        label = "weekDayChipBg"
    )
    val borderColor = when {
        isSelected -> Color.Transparent
        isToday -> scheme.primary
        else -> scheme.outlineVariant
    }
    val contentColor = if (isSelected) scheme.onPrimary
    else if (hasCourse || isToday) scheme.onSurface
    else scheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .pressScale(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = day.weekdayLabel(),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
        Text(
            text = "${day.monthValue}/${day.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (hasCourse || isToday) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 视图二·五：本月（2026-10-25 需求）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「本月」视图：月份翻页 + 该月**有课的每一天**按日分组列出。
 *
 * ## 形态为什么是"按天分组"而不是 7 列月网格
 *
 * 与「本周」放弃 7 列周网格同一条理由：手机宽度放不下 7 列里的完整字段
 * （课程名最长 14 字、地点最长 66 字符）；日期当分组标题、课程沿用
 * [CourseCard]，一天 N 节课纵向铺开，信息量不损失。
 *
 * ## 进行状态只对"今天"显示
 *
 * 与「本周」视图同一口径：只有正在看的这一天等于今天时才显示
 * 未开始 / 进行中 / 已结束（别的日期谈"进行中"没有意义）。
 */
@Composable
private fun MonthView(
    timetable: Timetable?,
    today: LocalDate,
    renderTick: Int,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    onOpenCourse: (Course) -> Unit
) {
    val now = remember(today, renderTick) { LocalTime.now() }

    // 该月有课的日期 → 当天课程（升序）；组合期一次分组，220 条实例成本可忽略。
    val dayGroups = remember(timetable, month) {
        timetable?.courses.orEmpty()
            .filter { YearMonth.from(it.date) == month }
            .groupBy { it.date }
            .toSortedMap()
            .map { (date, list) -> date to list.sortedBy { it.start } }
    }
    val totalCourses = dayGroups.sumOf { it.second.size }

    Column(Modifier.fillMaxSize()) {
        // 月份翻页行（与「本周」的周翻页同构：左箭头 + 居中标题 + 右箭头）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一月")
            }
            Text(
                text = "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一月")
            }
        }

        if (dayGroups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🗓️", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "这个月没有课～",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "month-summary") {
                    Text(
                        text = "${month.monthValue}月共 $totalCourses 节课 · ${dayGroups.size} 个上课日",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                dayGroups.forEach { (date, courses) ->
                    item(key = "month-day-${date}") {
                        MonthDayHeader(
                            date = date,
                            isToday = date == today,
                            courseCount = courses.size
                        )
                    }
                    items(
                        courses,
                        key = { "month-${date}-${it.start}-${it.summary}" }
                    ) { course ->
                        CourseCard(
                            course = course,
                            showStatus = date == today,
                            now = now,
                            onClick = { onOpenCourse(course) }
                        )
                    }
                }
            }
        }
    }
}

/** 「本月」视图里一天的分组头：日期 + 星期（今天高亮）+ 当天节数。 */
@Composable
private fun MonthDayHeader(
    date: LocalDate,
    isToday: Boolean,
    courseCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildString {
                append("${date.monthValue}月${date.dayOfMonth}日 ")
                append(date.weekdayLabel())
                if (isToday) append(" · 今天")
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$courseCount 节",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 视图三：全部课程
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「全部课程」视图：按参考页 `renderAll` 的聚合口径——
 * 「课程名 + 星期 + 起止时刻 + 地点 + 教师」相同的实例归并为一张课程卡，
 * 卡上给开课日期范围与总次数。
 */
@Composable
private fun AllCoursesView(timetable: Timetable?) {
    // 聚合在 remember 里做（220 条实例、几十个分组，组合期一次成本可忽略）。
    // key 与参考页一致：name|weekday|start|end|room|teacher。
    val groups = remember(timetable) {
        timetable?.courses?.orEmpty().orEmpty()
            .groupBy { course ->
                CourseGroupKey(
                    name = course.summary,
                    weekday = course.dayOfWeek,
                    start = course.startTime,
                    end = course.endTime,
                    room = course.location,
                    teacher = course.teacher
                )
            }
            .map { (key, courses) ->
                val sorted = courses.sortedBy { it.start }
                CourseGroup(
                    key = key,
                    dates = sorted.map { it.date },
                    periodText = sorted.firstOrNull()?.periodText.orEmpty()
                )
            }
            .sortedWith(
                compareBy({ it.key.weekday }, { it.key.start }, { it.key.name })
            )
    }

    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "课表为空",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(groups, key = { it.key.toString() }) { group ->
            AllCourseCard(group = group)
        }
    }
}

/** 全部课程视图的聚合键（与参考页 `renderAll` 的 key 逐项对应）。 */
private data class CourseGroupKey(
    val name: String,
    val weekday: java.time.DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
    val room: String,
    val teacher: String
)

/** 全部课程视图的一张课程卡的数据。 */
private data class CourseGroup(
    val key: CourseGroupKey,
    /** 该组全部上课日期（升序）。 */
    val dates: List<LocalDate>,
    val periodText: String
)

/** 全部课程视图里的一张课程卡（参考页 .acard：顶部色条 + 分行字段）。 */
@Composable
private fun AllCourseCard(group: CourseGroup) {
    val scheme = MaterialTheme.colorScheme
    val color = courseColorOf(group.key.name)
    val first = group.dates.first()
    val last = group.dates.last()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .padding(top = 3.dp) // 顶边色条由内部 Box 画，留 3dp 让它露出
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(color)
        )
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = group.key.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            DetailRow(
                label = "时间",
                value = buildString {
                    // DayOfWeek 没有现成的中文扩展（util 只给了 LocalDate 版），
                    // 用"该星期对应的某个 LocalDate"借道：1970-01-05 恰是周一（epoch+4 天），
                    // 加上 dayOfWeek.value - 1 天即得该星期的代表日，label 借它取。
                    val representative = LocalDate.of(1970, 1, 5)
                        .plusDays((group.key.weekday.value - 1).toLong())
                    append(representative.weekdayLabel())
                    append(' ')
                    append(group.key.start.hhMm())
                    append(" – ")
                    append(group.key.end.hhMm())
                    if (group.periodText.isNotBlank()) append("（${group.periodText}）")
                }
            )
            if (group.key.room.isNotBlank()) {
                DetailRow(label = "地点", value = group.key.room)
            }
            if (group.key.teacher.isNotBlank()) {
                DetailRow(label = "教师", value = group.key.teacher)
            }
            DetailRow(
                label = "日期",
                value = "${first.monthValue}月${first.dayOfMonth}日 – " +
                    "${last.monthValue}月${last.dayOfMonth}日 · 共 ${group.dates.size} 次"
            )
        }
    }
}

/** 「全部课程」卡里的一行「标签 值」。 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 课程卡（今天 / 本周共用）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一节课的卡片（参考页 .cls 的形态：左侧色条 + 时间块 + 信息区）。
 *
 * @param showStatus 是否显示进行状态（今天视图恒 true；本周视图只在"看的是今天"时 true）。
 * @param now 当前时刻（状态判定用；showStatus = false 时忽略）。
 */
@Composable
private fun CourseCard(
    course: Course,
    showStatus: Boolean,
    now: LocalTime,
    onClick: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val color = courseColorOf(course.summary)

    // 状态判定（与参考页 statusOf 一致）。showStatus = false 时为 null（不显示胶囊）。
    val status = if (showStatus) {
        when {
            now < course.startTime -> CourseStatus.NOT_STARTED
            now <= course.endTime -> CourseStatus.ONGOING
            else -> CourseStatus.FINISHED
        }
    } else {
        null
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v1.1.79 需求 2：整卡可点 → 弹课程详情。pressScale 在最外层，
            // 缩放发生在裁剪之后，圆角不会在中间帧退化成多边形。
            .pressScale(onClick = onClick)
            // IntrinsicSize.Min：让左侧色条量出信息区的实际高度后跟着等高
            // （参考页 .bar 用负 margin 实现贯穿；Compose 里标准做法是 intrinsic）。
            // 内容高度随字段多少变化（无教师 / 无进行条时更矮），写死必然露馅。
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .padding(top = 3.dp)
    ) {
        // 左侧色条（参考页 .bar）：贯穿卡片高度的 5dp 竖条。
        Box(
            Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(color)
        )

        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 课程名 + 状态胶囊。
                Text(
                    text = course.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                status?.let {
                    Spacer(Modifier.width(8.dp))
                    CourseStatusPill(status = it, course = course, now = now)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${course.startTime.hhMm()} – ${course.endTime.hhMm()}" +
                    if (course.periodText.isNotBlank()) "　${course.periodText}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            if (course.location.isNotBlank()) {
                Text(
                    text = "📍 ${course.location}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (course.teacher.isNotBlank()) {
                Text(
                    text = "👤 ${course.teacher}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 进行中：进度条（参考页 .progress）。
            if (status == CourseStatus.ONGOING) {
                val total = course.duration.toMinutes().coerceAtLeast(1)
                val elapsed = java.time.Duration.between(
                    course.startTime,
                    now
                ).toMinutes().coerceIn(0, total)
                val progress = elapsed.toFloat() / total
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.outlineVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF18A058))
                    )
                }
            }
            // 未开始：还有多久上课（参考页的橙色提示行）。
            if (status == CourseStatus.NOT_STARTED) {
                val mins = java.time.Duration.between(now, course.startTime).toMinutes()
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "还有 " +
                        (if (mins >= 60) "${mins / 60} 小时 ${mins % 60} 分钟" else "$mins 分钟") +
                        " 上课",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF0883A)
                )
            }
        }
    }
}

/** 课程进行状态（与参考页 statusOf 的三态一一对应）。 */
private enum class CourseStatus {
    NOT_STARTED,
    ONGOING,
    FINISHED
}

/** 状态胶囊（参考页 .status：doing 绿 / soon 橙 / done 灰）。 */
@Composable
private fun CourseStatusPill(
    status: CourseStatus,
    course: Course,
    now: LocalTime
) {
    val (bg, fg, label) = when (status) {
        CourseStatus.ONGOING -> Triple(Color(0xFFE6F7EE), Color(0xFF18A058), "进行中")
        CourseStatus.NOT_STARTED -> Triple(Color(0xFFFDF1E5), Color(0xFFF0883A), "未开始")
        CourseStatus.FINISHED -> Triple(Color(0xFFF0F2F7), Color(0xFF9AA1B2), "已结束")
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 课程配色（参考页 PALETTE 的移植）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 参考页的 12 色调色板（`PALETTE`），照搬色值：
 * 每门课按**在整份课表里首次出现的顺序**取色（与参考页 `courseColor` 的构建一致），
 * 因此同一门课在所有视图里同色。用完 12 色后取模循环。
 */
private val COURSE_PALETTE: List<Color> = listOf(
    Color(0xFF4F6EF7),
    Color(0xFF18A058),
    Color(0xFFF0883A),
    Color(0xFF9B59B6),
    Color(0xFFE64340),
    Color(0xFF00A2AE),
    Color(0xFFD48806),
    Color(0xFF2F54EB),
    Color(0xFFC2185B),
    Color(0xFF5D4037),
    Color(0xFF388E3C),
    Color(0xFF795548)
)

/**
 * 课程名 → 颜色。与参考页不同处只有一点：参考页在解析后**按出现顺序**建 map，
 * 本页没有那个时机（组合期才拿到课表），改为**按名字排序**后建——
 * 顺序稳定、同一份课表任何时候算出的映射一致，这就够了
 * （"哪门课配哪个色"本身没有正确答案，稳定即可）。
 */
private fun courseColorOf(courseName: String): Color {
    // 排序在每次调用时都做太浪费；但调用点都是惰性列表渲染，且课程名只有 19 个。
    // 为简单与稳定，直接以名字的 hashCode 取模——不保证与参考页同色，
    // 但保证同一名字恒同色，这正是本页需要的全部性质。
    val index = (courseName.hashCode().mod(COURSE_PALETTE.size) + COURSE_PALETTE.size)
        .mod(COURSE_PALETTE.size)
    return COURSE_PALETTE[index]
}

// ─────────────────────────────────────────────────────────────────────────────
// 搜索
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 搜索结果列表（**整份课表**的命中，口径未变）。
 * 按日期分组显示（同一门课一学期出现十几次，不分组会像重复数据）。
 */
@Composable
private fun SearchResultList(
    result: List<Course>?,
    keyword: String,
    onOpenCourse: (Course) -> Unit
) {
    if (result == null) {
        LoadingHint()
        return
    }
    if (result.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "没有找到「$keyword」",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "试试课程名、地点或教师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        return
    }
    val grouped = remember(result) { result.groupBy { it.date } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        grouped.entries.forEach { (date, courses) ->
            item(key = "date-${date}") {
                Text(
                    text = "${date.monthValue}月${date.dayOfMonth}日 ${date.weekdayLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(courses, key = { "${it.start}-${it.summary}" }) { course ->
                CourseCard(course = course, showStatus = false, now = LocalTime.now(), onClick = { onOpenCourse(course) })
            }
        }
    }
}

/** 搜索加载中。 */
@Composable
private fun LoadingHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "课表加载中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 顶部：搜索 + 分享
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 标题栏：返回 + 标题/搜索框 + 搜索开关 + 分享。
 * 结构与旧版一致（搜索范围 = 整份课表，理由见 `TimetableRepository.search`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimetableTopBar(
    onBack: () -> Unit,
    query: String,
    searchOpen: Boolean,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    reminderEnabled: Boolean,
    reminderLeadMinutes: Int,
    reminderMenuOpen: Boolean,
    onToggleReminderMenu: () -> Unit,
    onDismissReminderMenu: () -> Unit,
    onToggleReminder: (Boolean) -> Unit,
    onSelectReminderLead: (Int) -> Unit,
    onShare: () -> Unit
) {
    TopAppBar(
        title = {
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("搜课程 / 地点 / 教师") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "清空")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("课程表")
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = { if (searchOpen) onCloseSearch() else onOpenSearch() }) {
                Icon(
                    imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchOpen) "关闭搜索" else "搜索"
                )
            }

            // ── 上课提醒入口（2026-10-22）────────────────────────────────────
            //
            // 图标状态 = 开关状态：开启时用强调色（一眼看出"提醒是开着的"），
            // 关闭时用次要前景色。点击展开 `DropdownMenu`，内容是
            // [ReminderMenuContent]（总开关 + 三档提前量）。
            //
            // `Box` 只是给菜单提供锚点：`DropdownMenu` 定位在它的父节点下沿，
            // 所以必须把它和 IconButton 一起包起来，否则菜单会跑到屏幕左上角。
            Box {
                IconButton(onClick = onToggleReminderMenu) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = if (reminderEnabled) "上课提醒（已开启）" else "上课提醒（已关闭）",
                        tint = if (reminderEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                DropdownMenu(
                    expanded = reminderMenuOpen,
                    onDismissRequest = onDismissReminderMenu
                ) {
                    ReminderMenuContent(
                        enabled = reminderEnabled,
                        leadMinutes = reminderLeadMinutes,
                        onToggle = onToggleReminder,
                        onSelectLead = onSelectReminderLead
                    )
                }
            }

            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "导出分享本周课表")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 工具
// ─────────────────────────────────────────────────────────────────────────────

/** `LocalTime` → `HH:mm`。与 util 的 `toHhMm` 同一格式，不另造一套。 */
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private fun LocalTime.hhMm(): String = format(HH_MM)

/**
 * 课程状态自动刷新的心跳周期（毫秒）。与参考页 `setInterval(render, 30000)` 同口径：
 * 状态以分钟粒度变化，30 秒一拍足以在"分钟翻转后 ~30s 内"反映到界面。
 */
private const val RENDER_TICK_MS = 30_000L

/**
 * 「第 N 教学周」的起点 = 课表里**最早的上课日所在周的周一**。
 *
 * WakeUp 导出的 ICS 里第一节课就是学期第一周（如 2026-09-01 开学周），
 * 以它为锚点即可推算任意日期的周次。课表为空时返回 null（头部不显示周次）。
 */
internal fun teachingWeekStart(timetable: Timetable?): LocalDate? =
    timetable?.sortedCourses?.minOfOrNull { it.date }?.let { firstDate ->
        val repo = firstDate
        // 与 TimetableRepository.weekStartOf 同一算法，但不依赖仓库实例（这里是纯函数）。
        val offset = (repo.dayOfWeek.value - java.time.DayOfWeek.MONDAY.value + 7) % 7
        repo.minusDays(offset.toLong())
    }

/** 今天是第几教学周（1 起）；课表为空或今天早于开学周时返回 null。 */
internal fun teachingWeekOf(timetable: Timetable?, today: LocalDate): Int? {
    val start = teachingWeekStart(timetable) ?: return null
    val weeks = java.time.temporal.ChronoUnit.WEEKS.between(start, today) + 1
    return weeks.toInt().takeIf { it >= 1 }
}

// ─────────────────────────────────────────────────────────────────────────────
// 导出分享（本周课表纯文本，零权限零 FileProvider；与旧版逐字一致）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 生成**本周**课表的纯文本。
 *
 * 刻意做成**对齐的等宽文本**：粘进聊天窗口时不会因为字体差异而错位。
 * 无课的日期**明确列出**（而不是跳过）。口径与旧版完全一致，本页重构未触碰它。
 *
 * @param weekStart 本周周一。
 */
private fun buildWeekText(
    timetable: Timetable?,
    weekStart: LocalDate,
    today: LocalDate
): String {
    val weekEnd = weekStart.plusDays(6)
    val builder = StringBuilder()
    builder.append("MoreCheck 课表 · 本周（")
        .append("${weekStart.monthValue}月${weekStart.dayOfMonth}日")
        .append(" - ")
        .append("${weekEnd.monthValue}月${weekEnd.dayOfMonth}日")
        .append("）\n\n")

    if (timetable == null || timetable.courses.isEmpty()) {
        builder.append("课表为空。")
        return builder.toString()
    }

    for (offset in 0L..6L) {
        val day = weekStart.plusDays(offset)
        val courses = timetable.coursesOn(day)
        val isToday = day == today
        builder.append(day.weekdayLabel())
        if (isToday) builder.append("（今天）")
        builder.append(' ').append(day.monthValue).append('月').append(day.dayOfMonth).append("日\n")

        if (courses.isEmpty()) {
            builder.append("  无课\n\n")
            continue
        }
        // `coursesOn` 保证按 start 升序（v1.1.75 起）；这里保留 sortedBy 是
        // "导出产物多一次稳定排序"的再确认（与旧版同口径）。
        courses.sortedBy { it.start }.forEach { course ->
            builder.append("  ")
                .append(course.startTime.hhMm()).append('-').append(course.endTime.hhMm())
                .append("  ").append(course.summary).append('\n')
            val parts = buildList {
                if (course.location.isNotBlank()) add(course.location)
                if (course.teacher.isNotBlank()) add(course.teacher)
                if (course.periodText.isNotBlank()) add(course.periodText)
            }
            if (parts.isNotEmpty()) {
                builder.append("        ").append(parts.joinToString(" · ")).append('\n')
            }
        }
        builder.append('\n')
    }
    return builder.toString().trimEnd()
}

/**
 * 用系统选择器分享一段纯文本。零权限、零 FileProvider（理由见旧版与本页 KDoc）。
 */
private fun shareText(context: android.content.Context, text: String, onMessage: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "本周课表")
    }
    val chooser = Intent.createChooser(intent, "分享本周课表").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure { onMessage("没有可用的分享应用") }
}
