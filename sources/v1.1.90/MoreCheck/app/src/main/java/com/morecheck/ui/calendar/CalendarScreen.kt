package com.morecheck.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.morecheck.di.appContainer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.data.CheckTask
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.records.RecordsContent
import com.morecheck.ui.records.RecordsViewModel
import com.morecheck.util.DayState
import com.morecheck.util.fullLabelWith
import com.morecheck.util.toHhMm
import com.morecheck.ui.nav.TabSwipeClaim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 「统计」入口标题栏里的两个面板：日历（月历）/ 记录（热力图 + 时间线）。
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
 *
 * ## ⚠️ v1.1.72：第一个标签由「统计」改为「**日历**」，底栏仍叫「统计」
 *
 * 需求原文：「统计页标题栏的"统计"（"记录"左边）文字改为"日历"」。
 * **只改标题栏这一个标签**，底部导航栏那一项仍叫「统计」——这不是漏改，两边的
 * 改名代价完全不同：
 * - 标题栏标签只是**显示文字**，改它没有任何兼容代价；
 * - 底栏文字来自 `ui/nav/TabDef.kt` 的 `AppTab.CALENDAR.title`，而 `CALENDAR` 这个
 *   **枚举名与 route** 承担着桌面快捷方式与 `fromRouteOrNull` 的匹配
 *   （见 `TabDef.kt` 顶部注释）——改 route 会让历史快捷方式失联。
 *
 * 所以两边叫不同的名字是有意为之：底栏回答"这是哪一屏"，标题栏回答"这块面板画的是什么"。
 *
 * ## ⚠️ v1.1.74：月历格不再正方形；点击某天的详情改为**内联展开**（需求 10）
 *
 * 两处改动都在本页内部，不涉及导航层：
 * - **格子重叠**：月历格原来是 `weight(1f)` + `aspectRatio(1f)`（正方形），
 *   在窄屏/大字体下内容撑破格子且各行高不一致。已改为「宽度由 weight 平分、
 *   高度由一行统一的 `DAY_CELL_HEIGHT` 给定」，完整推导见 [MonthGrid] 的 KDoc；
 * - **当天详情**：原来是点某一天弹 `DayDetailDialog`（独立 `AlertDialog` 窗口），
 *   现在改为月历**下方内联展开**（见 [DayInlinePanel]），撤销/补打卡能力完整迁移。
 *
 * 上面「记录」面板的描述**未受影响**：它仍是热力图 + 时间线，
 * 与本次改动的「日历」面板内的当天详情是两回事——
 * 前者是"一段时间的统计"，后者是"某一天的明细"。
 */
private enum class CalendarPanel(val label: String) {
    CALENDAR("日历"),
    RECORDS("记录")
}

/**
 * 标题栏两个标签之间的水平间隔（v1.1.71）。
 *
 * 取 22.dp，**与 `HomeScreen` 标题栏的 `Spacer(Modifier.width(22.dp))` 取同一个值**——
 * 需求要的是「统计页标题栏的文字大小与间隔与主页的打卡/计时一致」，所以这个数字
 * 不是审美选择，而是"必须与主页相等"的约束。写成具名常量的目的是让这层对应关系
 * 在代码里可见：以后有人调主页的 22.dp，搜这个名字就能找到这里也要一起改。
 *
 * （改动前这里曾是 6.dp，与主页的 22.dp 差得很多，两个两字标签几乎贴在一起，
 * 与主页并排切换时能一眼看出"不是同一套排版"。）
 */
private val TITLE_LABEL_SPACING = 22.dp

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

    // ── 样式开关（2026-10-22 需求：深色今日 / 日历默认周视图）────────────────
    val context = LocalContext.current
    val styleRepository = remember(context) { context.appContainer.developerOptionsRepository }
    val solidToday by styleRepository.styleSolidTodayEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val weekDefault by styleRepository.styleCalendarWeekDefaultEnabled
        .collectAsStateWithLifecycle(initialValue = false)

    val initialMonth = YearMonth.from(today)
    // 当前查看的月份偏移（0 = 本月）。2026-10-22 起日历**不再用横向 Pager**：
    // 收起成周视图需要把整月上移再裁剪，而 `HorizontalPager`（LazyLayout）在
    // 被裁剪/位移时会整块不绘制（模拟器实测：收起后一片空白，试了三种挂法都不行）。
    // 换成普通 Column 后裁剪/位移都正常；翻月改由左右箭头 + 横滑手势驱动。
    var monthIndex by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val currentMonth = initialMonth.plusMonths(monthIndex.toLong())

    // 当前展开内联记录的日期；null = 内联区收起（需求 10）。
    //
    // 用 `remember` 而不是 `rememberSaveable`：它是一个**瞬时**的交互中间态，
    // 翻月 / 重建时收起反而是安全的行为（见下方翻月自动收起的那段说明）。
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    // 翻月自动收起内联区（需求 10：「切换月份时收起」）。
    //
    // ⚠️ 为什么必须在**这里**收，而不是交给 MonthGrid 的点击回调：
    // 翻月有两条路——标题栏的左右箭头（本文件 [CalendarBody] 里那两行）与
    // 月历区域的横滑 Pager——两条路都不会经过任何「点了某一天」的回调，
    // 也就是说不存在一个能顺带清 `selectedDate` 的中心点。
    // 唯一能同时覆盖两条路的观察点是 `currentMonth` 本身。
    //
    // 用 `LaunchedEffect(currentMonth)` 而不是 `if` 判断：翻月是一次**变化**，
    // effect 只在月份真的变了之后跑一次，不会在每次重组时重复清状态
    // （后者会把「用户刚展开、恰好触发了一次无关重组」也误判成翻月）。
    //
    // 为什么"翻月就收起"是对的而不是偷懒：内联区显示的是**某一天**的记录，
    // 而那个日期属于展开时的那个月。留着它跨月显示，用户会以为新月份的某一天
    // 有这些记录——那是一个真实的误解，不是单纯的观感问题。
    LaunchedEffect(currentMonth) {
        selectedDate = null
    }

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
                //
                // ⚠️ v1.1.71 起**字号与间隔改为与主页标题栏逐项对齐**（需求原文：
                // 「优化统计页标题栏文字大小与间隔显示，使其与主页的打卡与计时一致」）。
                // 改动前这里是 `fontSize = 20.sp` + `Spacer(6.dp)`，而主页是
                // `MaterialTheme.typography.titleLarge`（M3 默认 22sp）+ `Spacer(22.dp)`——
                // 两页并排切换时能明显看出统计页的字更小、挤得更紧，像是"没排版好"。
                //
                // 现在改成**与主页完全同源**：字号用 `typography.titleLarge` 而不是再写一个
                // 数字——写死 22.sp 会在主题字体缩放 / 以后换 Typography 时与主页脱节，
                // 而引用同一个语义 token 才能保证两边永远一致（这正是需求要的"一致"）。
                // 间隔同样取主页的 22.dp，两个两字标签之间不再局促。
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CalendarPanel.entries.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.width(TITLE_LABEL_SPACING))
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = if (panel == item) FontWeight.Bold else FontWeight.Normal,
                                color = if (panel == item) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                // v1.1.72：标题栏标签也改成统一按压反馈（缩放 0.96 + 变淡）。
                                // 它是本页最高频的点击目标之一（切「日历 / 记录」面板），
                                // 用裸 clickable 时只有默认水波纹，在浅色标题栏上几乎看不见。
                                modifier = Modifier.pressScale(onClick = { panel = item })
                            )
                        }
                    }
                },
                actions = {
                    // 页面右上角**刻意不用「+」**，而是「更多」(MoreVert)：本页的菜单里
                    // 是「新建任务 / 扫一扫 / 打卡记录」三个平行入口，用「更多」比「+」更准确。
                    // （v1.1.53 的需求就是「去掉右上角加号、改 MoreVert」，v1.1.71 保留该选择。）
                    //
                    // ⚠️ 但**菜单本体的外观规格与主页 / 发现三处统一**（v1.1.71 需求：
                    // 「三界面右上角加号统一文字、图标与圆角样式」）：
                    // 圆角 16dp + `surfaceContainerHigh` 容器色，触发按钮的无障碍文案统一为「更多」。
                    // 三处唯一的有意差异只是**触发图标**（本页 MoreVert，另两页 Add）——
                    // 这不是漏改，理由见上。
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
                        // 菜单项文案 / 顺序 / 图标与主页、发现**逐项一致**，改动时三处一起改。
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
                monthIndex = monthIndex,
                currentMonth = currentMonth,
                onPrevMonth = { monthIndex -= 1 },
                onNextMonth = { monthIndex += 1 },
                scope = scope,
                initialMonth = initialMonth,
                today = today,
                tasks = tasks,
                recordsByDate = recordsByDate,
                // 再次点同一天 = 收起（需求 10）。这个「切换」语义放在本页，
                // 而不是让 [DayInlinePanel] 自己判 `selectedDate`：
                // 内联区只负责展示，它的开合是「当前选中哪天」这一个状态决定的，
                // 放在一处判不会出现两套判等口径。
                onDayClick = { date ->
                    selectedDate = if (selectedDate == date) null else date
                },
                selectedDate = selectedDate,
                // 撤销 / 补打卡都作用在「内联区当前展开的那一天」上，
                // 所以日期由 [DayInlinePanel] 一起传回来，而不是在这里闭包捕获
                // `selectedDate`：内联区本来就是「按日期渲染」的一整块，
                // 让它把日期当参数传，签名比捕获更自洽。
                onBackfill = { date, taskId -> viewModel.backfill(taskId, date) },
                onUndo = { date, taskId -> viewModel.undo(taskId, date) },
                onOpenRecords = onOpenRecords,
                onOpenYearOverview = onOpenYearOverview,
                solidToday = solidToday,
                weekDefault = weekDefault
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

    // ⚠️ 原先这里的 `selectedDate?.let { DayDetailDialog(...) }` 已删除（需求 10）。
    //
    // 「当天记录」现在是月历**下方**的内联展开区（见 [DayInlinePanel]），
    // 与月历同处一个滚动上下文，**不再是独立窗口**。取舍理由：
    // ① 原弹窗要盖住整屏来看的内容，其实只有「那天有几条记录」——
    //    遮住月历看它自己展开的详情，代价大于收益；
    // ② 弹窗一打开就与月历脱节，无法"边看月历边对照"；
    // ③ 去掉弹窗后，「再点一次别的一天」不再需要先关窗再点，
    //    内联区天然支持同一天折叠、换一天原地切换。
    //
    // 弹窗原本承载的两种能力（撤销 / 补打卡）**全部迁移**进了内联区，一项没丢。
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarBody(
    modifier: Modifier,
    /** 当前月份偏移（翻月动画的 targetState；0 = 本月）。 */
    monthIndex: Int,
    currentMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    scope: CoroutineScope,
    initialMonth: YearMonth,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: CheckedAtByDate,
    onDayClick: (LocalDate) -> Unit,
    /** 当前展开内联记录的日期；null = 内联区收起（需求 10）。 */
    selectedDate: LocalDate?,
    onBackfill: (LocalDate, Long) -> Unit,
    onUndo: (LocalDate, Long) -> Unit,
    onOpenRecords: () -> Unit,
    onOpenYearOverview: () -> Unit,
    /** 「深色今日标记」：今天用实心主色块（否则回落淡色底）。 */
    solidToday: Boolean = true,
    /** 进页时默认收起为周视图（否则默认展开整月）。 */
    weekDefault: Boolean = false
) {
    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月")
            }
            // 年月文字可点：进入「全年打卡」总览页。
            // 只给这个 Text 加 clickable（而不是整行 Row），左右两侧的翻月 IconButton
            // 有自己的点击区域，两边互不重叠，不会抢手势。
            Text(
                "${currentMonth.year}年${currentMonth.monthValue}月",
                modifier = Modifier
                    .weight(1f)
                    // v1.1.72：统一按压反馈。注意 `pressScale` 在前、`clip` 在后
                    // （缩放层内侧），圆角才不会被缩放中间帧破坏。
                    .pressScale(onClick = onOpenYearOverview)
                    .clip(RoundedCornerShape(8.dp)),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onNextMonth) {
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

        // ---- 日历区：跟手拖动切换「周 / 月」视图（2026-10-22 需求 7）------------
        //
        // ## 形态
        //
        // 整块日历的高度随一个 0..1 的比例变化：1 = 展开整月（与改造前一致），
        // 0 = 只留「聚焦那一周」（今天或当前选中的日期所在行）。
        // 变化由下方那根**横条**驱动：按住上下拖 → 跟手；松手 → 就近吸附；
        // 轻点一下 → 直接切换。
        //
        // ## 为什么用「裁剪 + 上移」而不是"少画几行"
        //
        // 少画行会让横滑翻月时的行数与高度不同步（翻到 6 行的月份突然变高），
        // 也会让"聚焦行"的定位逻辑与网格脱节。这里始终画整月，
        // 再用外层 `height` + `graphicsLayer.translationY` 只露一段：
        // 收起时把聚焦行上移到可视区顶部——这样翻月 / 换选中日期都不需要重建网格。
        val layoutLeading = currentMonth.atDay(1).dayOfWeek.value - 1
        val layoutRows = (layoutLeading + currentMonth.lengthOfMonth() + 6) / 7
        val focusDate = selectedDate ?: today
        val focusRow = ((layoutLeading + focusDate.dayOfMonth - 1) / 7)
            .coerceIn(0, layoutRows - 1)

        // 1f = 展开（月），0f = 收起（周）。
        val expandFraction = remember { Animatable(1f) }
        // 「日历默认周视图」只应用一次：用户拖动之后不再被流重新覆盖。
        var defaultApplied by remember { mutableStateOf(false) }
        LaunchedEffect(weekDefault) {
            if (!defaultApplied) {
                defaultApplied = true
                expandFraction.snapTo(if (weekDefault) 0f else 1f)
            }
        }

        val density = LocalDensity.current
        val localView = LocalView.current

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 行高：与改造前同一套算法（6 行月份自动压到放得下），
            // 上限仍是 46dp，所以 5 行月份观感不变。
            val rowHeight = minOf(DAY_CELL_HEIGHT, maxHeight / layoutRows)
            val collapseRangePx = with(density) {
                (rowHeight * (layoutRows - 1)).toPx()
            }.coerceAtLeast(1f)
            // 高度：1 行（收起）↔ rows 行（展开）之间跟手插值。
            // 行窗口在 f >= 0.999 才切整月，其余时间只画聚焦行——
            // 于是"过半"那一段高度会超过一行（留白），但不会出现半截行。
            val areaHeight = rowHeight * (1 + (layoutRows - 1) * expandFraction.value)

            Column(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(areaHeight)
                        .clipToBounds()
                ) {
                    // 整月网格 / 周视图（2026-10-22 需求 7）。
                    //
                    // ## 为什么最后没用"裁剪 + 上移"而是"只画需要的行"
                    //
                    // 直觉方案是：内容始终画整月，用外层高度 + 位移只露一段。
                    // 实测（模拟器，试了三种挂法：位移挂 Pager / 挂外层 Box / 挂页内内容）
                    // 收起后都是**一片空白**——被裁到一行高的区域里什么都画不出来。
                    // 换成普通 Column 后同样空白，说明这条路在当前 Compose 版本上不可靠。
                    //
                    // 现在的做法：**按需画行**。收起时只画"今天（或选中日）所在那一行"，
                    // 展开时画整月；高度仍由 `expandFraction` 连续驱动，
                    // 因此拖动过程高度跟手，行的数量在过半时切换。
                    //
                    // 行窗口跟着拖动的进度**向上扩展**（从聚焦那一行往上加行），
                    // 外层 Box 用 `Alignment.BottomCenter` 对齐：收起时那一行就在底部，
                    // 往下拖动时新的一行从上方"顶"出来——与下拉一张纸的手感一致。
                    val extraRows = ((layoutRows - 1) * expandFraction.value).toInt()
                    val window: IntRange? =
                        if (expandFraction.value >= 0.999f) null
                        else (focusRow - extraRows).coerceAtLeast(0)..focusRow

                    // 翻月动画（2026-10-22 需求 12）：新旧月份**横向滑动**交接。
                    //
                    // 用 `AnimatedContent` 而不是自己维护两层内容：它天然保证
                    // "进入的与退出的各占一层、动画结束只剩一层"，
                    // 且 `targetState` 就是 `monthIndex`，不会出现两份月份状态。
                    //
                    // 位移取 1/3 屏宽 + 淡入淡出：整屏滑动在这种"局部网格"上会显得太猛
                    // （用户只是在看日历，不是在翻页），1/3 的错位 + 淡变足够表达方向。
                    AnimatedContent(
                        targetState = monthIndex,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (
                                    slideInHorizontally(tween(MONTH_SLIDE_MS)) { it / 3 } +
                                        fadeIn(tween(MONTH_SLIDE_MS))
                                    ) togetherWith (
                                    slideOutHorizontally(tween(MONTH_SLIDE_MS)) { -it / 3 } +
                                        fadeOut(tween(MONTH_SLIDE_MS / 2))
                                    )
                            } else {
                                (
                                    slideInHorizontally(tween(MONTH_SLIDE_MS)) { -it / 3 } +
                                        fadeIn(tween(MONTH_SLIDE_MS))
                                    ) togetherWith (
                                    slideOutHorizontally(tween(MONTH_SLIDE_MS)) { it / 3 } +
                                        fadeOut(tween(MONTH_SLIDE_MS / 2))
                                    )
                            }
                        },
                        label = "calendar-month"
                    ) { index ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 横滑翻月（手势落在网格上，不与上方的标题栏冲突）。
                                    .pointerInput(Unit) {
                                        var total = 0f
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (total < -48.dp.toPx()) onNextMonth()
                                                else if (total > 48.dp.toPx()) onPrevMonth()
                                                total = 0f
                                            },
                                            onHorizontalDrag = { change, dx ->
                                                change.consume()
                                                total += dx
                                            }
                                        )
                                    }
                            ) {
                                MonthGrid(
                                    month = initialMonth.plusMonths(index.toLong()),
                                    today = today,
                                    tasks = tasks,
                                    recordsByDate = recordsByDate,
                                    selectedDate = selectedDate,
                                    onDayClick = onDayClick,
                                    rowHeight = rowHeight,
                                    solidToday = solidToday,
                                    visibleRows = window
                                )
                            }
                        }
                    }
                }

                // ── 拖动横条 ──────────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CALENDAR_HANDLE_HEIGHT)
                        .pointerInput(collapseRangePx) {
                            detectVerticalDragGestures(
                                onDragStart = { },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    // 向上拖（dragAmount < 0）= 展开。
                                    val delta = -dragAmount / collapseRangePx
                                    scope.launch {
                                        expandFraction.snapTo(
                                            (expandFraction.value + delta).coerceIn(0f, 1f)
                                        )
                                    }
                                },
                                onDragEnd = {
                                    // 就近吸附：过半即换档，否则回原位。
                                    val target = if (expandFraction.value > 0.5f) 1f else 0f
                                    scope.launch {
                                        expandFraction.animateTo(
                                            target,
                                            animationSpec = tween(220)
                                        )
                                    }
                                },
                                onDragCancel = {
                                    val target = if (expandFraction.value > 0.5f) 1f else 0f
                                    scope.launch {
                                        expandFraction.animateTo(
                                            target,
                                            animationSpec = tween(220)
                                        )
                                    }
                                }
                            )
                        }
                        // 轻点 = 直接切换；**长按** = "抓住"横条（2026-10-22 需求 12：
                        // 「底部条应可以长按上下滑动」）。
                        //
                        // 长按给一次触感反馈，然后手指继续上下移动就由上面的
                        // `detectVerticalDragGestures` 接管（它本来就在跟手）。
                        // 也就是说：长按后拖动、直接拖动**两种都能用**——
                        // 长按只是让"我抓住了它"这件事有反馈，不是额外的门槛。
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    val target = if (expandFraction.value > 0.5f) 0f else 1f
                                    scope.launch {
                                        expandFraction.animateTo(target, animationSpec = tween(220))
                                    }
                                },
                                onLongPress = {
                                    // `View.performHapticFeedback` 收的是
                                    // `android.view.HapticFeedbackConstants` 的整数常量，
                                    // 不是 Compose 的 `HapticFeedbackType`。
                                    localView.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS
                                    )
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(CALENDAR_HANDLE_WIDTH)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    )
                }
            }
        }

        // ---- 下半部分：当天记录（内联）+ 功能栏目切换 --------------------------
        //
        // ⚠️ 为什么把内联区放在**下半**而不是月历与分界线之间（需求 10 要求"月历下方"）：
        // 本页是「上 = 月历 Pager(weight 1f) / 下 = 栏目 Pager(weight 1f)」的两半布局。
        // 若把内联区插在月历与分界线之间，它会在两个 `weight(1f)` **之间**抢占空间，
        // 而两个 weight 会把剩下的空间**平分**——于是内联区一展开，
        // 月历那半就被压到不足 6 行的高度（6 行 × 46dp = 276dp，根本放不下），
        // 出现"想看某天的记录、却把月历本身挤没了"——那是本末倒置。
        //
        // 放在下半则零成本：下半的内容按用户确认**本来就是留空的**
        // （见 [CalendarSectionPanels] 的 KDoc），展开内联区只是把那块空白用起来，
        // 月历那半的 `weight(1f)` 完全不受影响。
        // 位置上它仍在月历**正下方**（分界线之下），满足需求原文。
        Column(Modifier.weight(1f).fillMaxWidth()) {
            DayInlinePanel(
                date = selectedDate,
                today = today,
                tasks = tasks,
                recordsByDate = recordsByDate,
                onBackfill = onBackfill,
                onUndo = onUndo,
                onOpenRecords = onOpenRecords,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- 下半部分：功能栏目切换（横滑，动画连贯、可悬停）-----------------
            // `weight(1f)`：吃掉内联区没用完的剩余空间。收起时它占满下半，
            // 展开时它被压到最小——而它的内容本来就是空占位，压到最小不损失任何东西。
            CalendarSectionPanels(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * 统计页**下半部分**的左右功能栏目（本轮需求：「下半部分为左右功能栏目切换」，
 * 内容按用户确认**留空**、可左右切换；要求「动画连贯、可悬停」）。
 *
 * ## 为什么用 `HorizontalPager`（这里可以，主骨架那里不行）
 *
 * 「可悬停」= 手指拖到一半松手时，页面**停在最近的栏目上**（而不是强制跳回），
 * 且拖动过程中页面**跟随手指**。这正是 `HorizontalPager` 的原生行为：
 * 拖动跟手、松手 spring 吸附到最近页、中途可以停住——动画天然连贯。
 * 主骨架横滑切 tab 不能用 Pager（两个 NavHost 并存会干扰底栏测量，见
 * `TabNavHost.onTabSwipe` 的教训），但**这里是纯面板切换、没有导航栈**，
 * 两页内容都是轻量空占位，不存在那个问题——用 Pager 是最省事且手感最对的选择。
 *
 * ## 内容为什么留空
 *
 * 用户确认：「下半部分留空，可左右切换」。本组件因此只搭**结构**：
 * 两个空栏目 + 预留的栏目数常量，任何真实功能（如热力图、时间线）将来往
 * [CalendarSectionPage] 的分支里填即可——`when` 不写 `else`，新增栏目时
 * 编译器会强制补分支（与 `LabRealPage` 的防漏机制同一套）。
 *
 * ⚠️ `rememberPagerState` 的 pageCount 用 lambda 形式（Compose 1.7 起的稳定 API），
 * 与上面月历 Pager 的写法保持一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarSectionPanels(modifier: Modifier = Modifier) {
    val sectionPagerState = rememberPagerState(initialPage = 0) { CALENDAR_SECTION_COUNT }

    // ── 「本区域接管横向手势」的声明（本轮修复：横滑不再被切入口抢走）──────────
    //
    // 为什么需要它：外层 `TabRootScreen` 的「横滑切入口」手势挂在**整个内容区**上，
    // 而本 Pager 占据下半屏。若不声明，一次横滑会**同时**发生"翻栏目"与"切入口"，
    // 实测表现为"在最下方滑一下，整个人被踢到发现页"（真机复现）。
    //
    // 做法：在本区域按下期间把 `TabSwipeClaim` 置位，抬起 / 离开时复位。
    // 外层看到标记就放弃本次切页判定（见 `TabSwipeClaim` 的 KDoc）。
    // ⚠️ 必须在 `DisposableEffect` 的 `onDispose` 里也复位：手势中途整页被导航走时
    // `onDragEnd` 不会触发，只 reset 那里会让标记永久为 true、外层再也切不了入口。
    DisposableEffect(Unit) {
        onDispose { TabSwipeClaim.release() }
    }

    HorizontalPager(
        state = sectionPagerState,
        modifier = modifier.pointerInput(Unit) {
            // 用 `awaitPointerEventScope` 手工跟踪按下/抬起，**不消费**任何事件
            // （Pager 自己的拖动逻辑照常工作；我们只是"顺带声明这块区域归我"）。
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                TabSwipeClaim.claim()
                try {
                    // 一直跟到抬起或取消为止。
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { it.changedToUp() || !it.pressed }) break
                    }
                } finally {
                    TabSwipeClaim.release()
                }
            }
        }
    ) { page ->
        // 各栏目当前为空占位（用户确认留空）；分支覆盖 [CalendarSectionPage] 全部取值，
        // 新增栏目时漏分支会直接编译失败。
        when (CalendarSectionPage.entries[page]) {
            CalendarSectionPage.FIRST -> Box(Modifier.fillMaxSize())
            CalendarSectionPage.SECOND -> Box(Modifier.fillMaxSize())
        }
    }
}

/**
 * 下半部分功能栏目的**栏目清单**（当前两个空栏目）。
 *
 * 单列成枚举而不是直接写死数字：栏目将来要有内容时，每个栏目一个分支、
 * `when` 穷尽性检查防漏；`CALENDAR_SECTION_COUNT` 与 `entries.size` 同源，
 * 不会出现「页数写了 3、分支只写 2」的错位。
 */
private enum class CalendarSectionPage {
    FIRST,
    SECOND
}

/** 下半部分的功能栏目数（与 [CalendarSectionPage].entries 同源）。 */
private val CALENDAR_SECTION_COUNT = CalendarSectionPage.entries.size

/**
 * 一个月月份的日期宫格。
 *
 * ## 为什么每行是「等宽格子 + 固定行高」，而不是正方形（需求 10 修重叠）
 *
 * 原来的写法是每格 `Modifier.weight(1f)`（宽度平分）**再套** `Modifier.aspectRatio(1f)`
 * （高 = 宽），空白格也是 `Spacer(Modifier.weight(1f).aspectRatio(1f))`。
 * 重叠正是这两者叠加出来的：
 *
 * 1. `aspectRatio` 在拿到宽度后会**反过来给这一行定高**——它测的是"我这一格要
 *    多宽"然后强制"那我就要这么高"。于是一行的高度变成了 `max(各格内容高, 格宽)`，
 *    是个**由宽度推导出来**的值；
 * 2. 而行高又没有对 `Row` 里的其它行做任何约束，于是**各行高互不相同**
 *    （不同月份的 `rows` 数不同；同一个月里每行的 `totalCells` 边界不同，
 *    `leadingBlank` 还会让某些行少一两个真格子）。
 *    行与行之间没有任何东西保证等高；
 * 3. 更糟的是行高又和「格内内容高」互相打架：格内实际要
 *    `26dp 圆 + 3dp 间隔 + 8dp 圆点行 + 上下各 2dp padding ≈ 41dp`。
 *    手机上单格宽约 `(屏宽 - 16dp) / 7 ≈ 51dp`，`aspectRatio(1f)` 要求行高正好 51dp，
 *    两边勉强够；但**屏幕一窄**（分屏、小平板竖屏）或**字体放大**，
 *    圆点行里那个 `+N` 的 `Text` 变宽变高，格内内容就超过 51dp，
 *    内容反过来把这一行撑高——这正是用户看到的「有的行挤成一团、有的行被顶开、
 *    数字和圆点跨到隔壁行去」。
 *
 * 所以现在改成：**宽度由 `weight` 平分（这本来就是对的），高度由一行统一的常量决定**。
 * 格子内部改成 `Box` + `contentAlignment = Center`，内容不再参与定高，
 * 超出的部分由 `Box` 居中并允许裁切（`clip`）而不是反过来撑开父容器。
 *
 * ### 为什么固定 `dp` 行高比 `aspectRatio` 更稳（三个场景逐一对比）
 *
 * - **横屏**：横屏时屏宽变大、可用高度变小。`aspectRatio(1f)` 会让行高**跟着变宽**
 *   而顶高整页，把下半的功能栏目 Pager 挤到看不见；固定 dp 行高不变，
 *   月历块的高度是确定的，上下两半的比例稳定。
 * - **窄屏 / 分屏**：单格宽变小，`aspectRatio(1f)` 会让行高**跟着变矮**，
 *   而格内内容（26+3+8+padding）是固定 41dp 上下——内容必然溢出。这就是重叠的直接触发条件。
 * - **大字体**：日期数字用 `bodyMedium`，系统字体放大后数字变大，
 *   `aspectRatio` 给的行高不会跟着变，于是数字被裁切或撑破。
 *   固定 dp 同样不解决"数字变大"，但下面 [DayCell] 里把数字**容器**尺寸
 *   与字号解耦、并让容器用 `fillMaxWidth` 居中，大字体下只是数字在圆里变大、
 *   最多裁一点，不会撑开行——不会连带把整页布局顶坏。
 *
 * 取 [DAY_CELL_HEIGHT] 而不是让行"内容多高就多高"：月历是**等距网格**，
 * 一旦某行因内容变高、某行不变，六行就会高低不齐，那本身就是一种错位。
 * 宁可牺牲极端字体下的完整显示，也要保证**网格永远方正**。
 */
private val DAY_CELL_HEIGHT = 46.dp

/** 翻月动画时长（2026-10-22 需求 12）。与二级页推入同量级，不拖沓。 */
private const val MONTH_SLIDE_MS = 220

/** 日历与下方内容之间的**拖动横条**高度（2026-10-22 需求 7；需求 12 加高到更易长按）。 */
private val CALENDAR_HANDLE_HEIGHT = 28.dp

/** 横条本体的宽度（参考图里是一条短横向胶囊）。 */
private val CALENDAR_HANDLE_WIDTH = 44.dp

@Composable
private fun MonthGrid(
    month: YearMonth,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: CheckedAtByDate,
    /** 当前内联展开的日期（null = 未选中任何天）。它决定哪个格子画高亮描边。 */
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    /** 行高（由 [CalendarBody] 统一算好传入：它要同时控制"整个日历区的高度"）。 */
    rowHeight: androidx.compose.ui.unit.Dp,
    /** 「深色今日标记」样式开关（见 [DayCell]）。 */
    solidToday: Boolean = true,
    /**
     * 只画这几行（0 起，含两端）。null = 整月。
     *
     * 2026-10-22 周视图用：收起时只画"聚焦那一周"一行。
     */
    visibleRows: IntRange? = null
) {
    val firstDay = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val leadingBlank = firstDay.dayOfWeek.value - 1 // Monday == 1
    val totalCells = leadingBlank + daysInMonth
    val rows = (totalCells + 6) / 7

    // ⚠️ 行高自适应（v1.1.79 真机实测修复「月历显示不全」）：
    //
    // 月历这半是 `weight(1f)`，高度＝屏高一半减去标题栏 / 星期行等固定开销，
    // 而 `rows` 随月份变化（5 行或 6 行）。原先行高恒为 [DAY_CELL_HEIGHT]=46dp：
    // **6 行的月份**（如 2026 年 11 月：1 号是周日、共 30 天 → 6 行）
    // 需要 6×46=276dp，超出可用高度，最后一行被直接裁掉——
    // 用户看到的就是「11 月少了一行、日期显示不全」。
    //
    // 修法：用 `BoxWithConstraints` 拿到这半区的**实际可用高度**，
    // 行高取 `min(46dp, 可用高 / 行数)`。5 行月份仍是 46dp（观感不变），
    // 6 行月份自动压到能放下全部日期的高度，绝不裁行。
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val firstRow = visibleRows?.first?.coerceIn(0, rows - 1) ?: 0
            val lastRow = visibleRows?.last?.coerceIn(firstRow, rows - 1) ?: (rows - 1)
            repeat(lastRow - firstRow + 1) { offset ->
                val row = firstRow + offset
                // 行高**由这一行统一给死**，且七格共享：
                // Row 的默认高度是"子项最大高度"，这里显式 `height` 后，
                // 七格无论内容多少都占同一块，行与行之间也不可能再互相影响。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                ) {
                    repeat(7) { col ->
                        val index = row * 7 + col
                        if (index < leadingBlank || index >= totalCells) {
                            // 空白格：只占位、不画任何东西。
                            // ⚠️ 原来这里也有 `aspectRatio(1f)`，是重叠的另一半来源
                            // （真格与空白格争同一行的行高）。现在两者用同一个
                            // `weight(1f)` + 行高由父 Row 给定，高度天然对齐。
                            Spacer(Modifier.weight(1f))
                        } else {
                            val date = month.atDay(index - leadingBlank + 1)
                            DayCell(
                                date = date,
                                today = today,
                                tasks = tasks,
                                recordsByDate = recordsByDate,
                                isSelected = date == selectedDate,
                                solidToday = solidToday,
                                onClick = { onDayClick(date) },
                                // ⚠️ **不要**在这里再补 `fillMaxHeight()`：
                                // `weight(1f)` 只平分宽度，高度已经由父 Row 的
                                // `height(rowHeight)` 统一决定。
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
}

/**
 * 月历里的一个日期格。
 *
 * ## 为什么用固定高度的 `Box` 而不是原来的 `Column` + `aspectRatio`（需求 10）
 *
 * 原来这里在 `modifier`（来自 [MonthGrid] 的 `weight(1f)`）之后又链了一个
 * `.aspectRatio(1f)`，并用 `Column` 竖排「日期圆 + 圆点行」。两个问题：
 *
 * 1. `aspectRatio` 反过来给父 `Row` 定高，而行高又随格宽变化 →
 *    各行高低不齐（见 [MonthGrid] 的 KDoc 里完整的重叠推导）；
 * 2. `Column` 里内容的自然高度约 41dp，在窄屏/大字体下超过 `aspectRatio`
 *    允许的格子高度，内容溢出到隔壁行。
 *
 * 现在：高度由父 Row 的 `height(DAY_CELL_HEIGHT)` 统一给定，本函数**不再定高**，
 * 只用 `Box` + `contentAlignment = Center` 把内容居中，并用 `clip` 把超出部分裁掉
 * （月历格是"七列等距"的一维网格，宁可裁掉也不允许它反过来撑开父容器）。
 *
 * ⚠️ 裁切顺序必须在 `pressScale` **之后**：`pressScale` 内含 graphicsLayer，
 * `clip` 放在它内侧，圆角才不会在缩放的中间帧上退化成多边形
 * （与本页其它可点元素同一口径）。
 */
@Composable
private fun DayCell(
    date: LocalDate,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: CheckedAtByDate,
    /** 该格是否是「内联展开的那一天」（用户最后点中的）。选中格画主题色描边高亮。 */
    isSelected: Boolean,
    /** 今天是否用**实心主色块**（2026-10-22 需求 7；关闭则回落淡色底）。 */
    solidToday: Boolean = true,
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

    Box(
        modifier = modifier
            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable 的默认水波纹。
            //
            // ⚠️ 顺序：`pressScale` 内含 graphicsLayer，本行的 `clip` 在它**之后**
            // （缩放层内侧），圆角才不会在中间帧上退化成多边形。
            .pressScale(onClick = onClick)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            // 选中高亮（本轮需求）：内联展开的那一天画一层淡主题色底，
            // 与"今天"的既有淡底叠加（同一天两者同时为 true 时颜色更实，
            // 语义也正确——它既是今天又是被选中的一天）。
            // 底色（2026-10-22 需求 7 重做）：
            // - **今天**：实心主色块（参考图里的深色方块），文字转白；
            // - **选中的日期**：只画外框、**不填底**（参考图 1 的 22 就是纯描边）——
            //   原来选中是"淡色填充 + 描边"，两种标记叠在一起反而看不出谁是谁；
            // - 其余：透明（日期本身不带边框，网格靠间距而不是线来分隔）。
            .background(
                when {
                    isToday && solidToday -> MaterialTheme.colorScheme.primary
                    isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            // 选中描边：主题色 2dp，把"现在展开的是哪一天"在月历上钉住——
            // 内联区展开后用户的视线会在月历与下方记录之间来回，没有描边时
            // "是哪一天的记录"只能靠内联区头部的文字确认。
            //
            // ⚠️ 2026-10-22 需求「日期无边框」：这里必须**整个修饰符条件化**，
            // 不能写 `border(width = if (isSelected) 2.dp else 0.dp, ...)`——
            // 实测 `width = 0.dp` 的 `border` 仍会描一圈发丝线，
            // 于是所有日期都带上了浅色圆角外框（正是用户反馈的"有边框"）。
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // 内容竖排：日期圆 + 圆点行。外层再套一个 `height(DAY_CELL_HEIGHT)` 的
        // `Column`，让「上圆下点」这两段贴着格子居中，而不是被 Box 拉伸。
        // 固定行高后这两段的高度是确定的（26 + 3 + 8 = 37dp < 46dp），
        // 极端字体下溢出的部分由外层 Box 的 `clip` 裁掉。
        Column(
            modifier = Modifier.height(DAY_CELL_HEIGHT),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ⚠️ 这里**不再**给"今天"画那个 26dp 的主色圆圈：
            // 标记已经由外层格子的实心底色承担（参考图里的今天是整块深色方块，
            // 不是"方块里再套一个圆"）。留空容器只是为了维持内容居中。
            Box(
                modifier = Modifier.size(26.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    date.dayOfMonth.toString(),
                    // ⚠️ **不写死 fontSize**：用 `labelMedium` 这一档语义 token，
                    // 它随系统字体缩放，而外层圆容器尺寸固定，
                    // 于是大字体下只是数字在圆里变大（必要时被裁），
                    // 不会把整行撑开——这正是去掉 aspectRatio 之后能稳住网格的原因。
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    // 实心底上的白字只在"深色今日"样式下生效；关掉开关后今天仍是淡底，
                    // 白字会看不见，因此这里必须跟着 solidToday 走。
                    color = when {
                        isToday && solidToday -> Color.White
                        isToday -> MaterialTheme.colorScheme.primary
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * 「当天打卡记录」的内联展开区（需求 10 取代 [DayDetailDialog]）。
 *
 * ## 为什么是内联而不是弹窗
 *
 * 原先是点某一天弹一个 `AlertDialog`。它要遮住整屏来看的内容，
 * 其实只有"那天有几条记录、各自什么状态"——而月历就在旁边一屏之隔，
 * 遮住它反而让用户没法边看日历边对照。改成内联之后：
 * - 展开位置就在月历正下方，视线不用来回跳；
 * - 换一天 = 原地换内容（不必先关窗再点），同一天再点 = 收起；
 * - 不再是独立窗口，**系统的分屏 / 横屏 / 大字体下都不会被裁掉**。
 *
 * ## 撤销的二次确认为什么仍然保留
 *
 * 内联区不是弹窗，但这不改变「撤销打卡是破坏性操作」这件事。
 * [ConfirmDialog] 依旧作为**兄弟节点**挂在同一个 Composable 里：
 * 确认框只在点了「撤销」之后才出现，出现时内联区仍在下面，
 * 形成"区之上再问一句"的层级——与原弹窗方案、与 HomeScreen 的做法完全一致。
 */
@Composable
private fun DayInlinePanel(
    /** 要展示的日期；null = 整个内联区收起（需求 10）。 */
    date: LocalDate?,
    today: LocalDate,
    tasks: List<CheckTask>,
    recordsByDate: CheckedAtByDate,
    onBackfill: (LocalDate, Long) -> Unit,
    onUndo: (LocalDate, Long) -> Unit,
    /** 点击任务名跳转到「打卡记录」页；由 CalendarScreen 透传它的 onOpenRecords。 */
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 「待确认撤销」的任务。remember(date) 而非 remember：日期一换就丢弃上一次的
    // 待确认状态，避免「展开 A 天 → 展开 B 天」时残留的 pendingUndo
    // 立刻弹出一个指向旧任务的确认框。
    var pendingUndo by remember(date) { mutableStateOf<CheckTask?>(null) }

    // ⚠️ v1.1.74 需求 8：「日期显示格式」的生效值（内联区的标题用它）。
    //
    // 为什么在本组件里读、而不是由 [CalendarScreen] 逐层透传：
    // 透传要改 4 个函数签名（`CalendarScreen` → `CalendarPanel` → `DayInlinePanel`
    // → 确认框），而这个值与本组件的其它入参（date/tasks/recordsByDate）无关，
    // 是**全局偏好**不是**本面板的数据**。在本组件里直接订阅仓库更贴近语义，
    // 也少一层无意义的形参。（`MainActivity` 的 `LocalPressFeedbackEnabled`
    // 用的是另一种手法——那里是因为有 40+ 个调用点才需要 Local。）
    val appContext = LocalContext.current.applicationContext
    val developerOptions = remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
    val dateFormat by developerOptions.dateFormatSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DateDisplayFormat.CN_FULL)

    // ── 展开 / 收起动画 ────────────────────────────────────────────────────
    //
    // 需求原文只说"内联展开"，动画不是必需项；但不做的话开合是硬切，
    // 在"内容高度不定"（任务条数每天不同）的控件上会显得很跳。
    // 这里只做纵向的展开/收起 + 淡入淡出，**不做**滑动位移
    // ——月历格子在动，一块内容从侧面滑进来会与上方的点击源产生位置错位感。
    //
    // `expandVertically` 默认从顶部展开，正好是"从月历下沿长出来"的方向。
    AnimatedVisibility(
        visible = date != null,
        enter = fadeIn(tween(INLINE_PANEL_ANIM_MS)) +
            expandVertically(tween(INLINE_PANEL_ANIM_MS)),
        exit = fadeOut(tween(INLINE_PANEL_ANIM_MS)) +
            shrinkVertically(tween(INLINE_PANEL_ANIM_MS))
    ) {
        // ⚠️ 这里用 Elvis 兜底而不是 `date!!`：退出动画期间 content lambda
        // **仍在组合里**（那正是它要播完收起动画的原因），此时 date 已是 null。
        // 直接 `!!` 会在这几帧里崩掉整个页面。
        // 兜底时返回、不渲染任何内容——退出动画结束后自然不再组合。
        val shown = date ?: return@AnimatedVisibility

        // 这里同时要「有没有打卡」和「几点打的卡」，所以直接用 taskId -> checkedAt 这张表。
        val checkedAtByTask = recordsByDate[shown.toString()].orEmpty()
        val checkedIds = checkedAtByTask.keys
        val zone = ZoneId.systemDefault()
        // 补打卡只允许最近 7 天内的过去日期。
        val backfillable = shown.isBefore(today) && !shown.isBefore(today.minusDays(7))
        // 只列出「当天已经存在」的任务：当天创建的任务之前不存在，不该出现在补打卡列表里。
        val dayTasks = tasks.filter { task ->
            val created = Instant.ofEpochMilli(task.createdAt).atZone(zone).toLocalDate()
            !shown.isBefore(created)
        }

        // ── 限高 + 滚动（需求 10 第 3 条）─────────────────────────────────
        //
        // ⚠️ 为什么**限高**而不是让整页一起滚：
        // 内联区与月历共处一屏，若不限高、任务一多就会把月历顶到看不见，
        // 而月历正是"用户刚点的那个东西"，不能被自己的详情挤掉。
        //
        // 限高基准取**屏高的一半**而不是别的分档：本区住在下半（见 [CalendarBody]
        // 的布局说明），下半拿到的总空间本来就只有屏高的一半左右；
        // 取 1/2 屏高意味着"本区最多占满它能占的地方，超出就在区内滚"，
        // 而月历那半的 `weight(1f)` 完全不受影响。
        //
        // 为什么不是"让下半整体滚"：下半里还有功能栏目 Pager，它本身有横滑手势，
        // 竖向滚动与横滑 Pager 共存会让下半在被内联区内容顶高时挤掉栏目，
        // 而且两个 Pager 嵌套滚动的手势归属又开始打架（同 [TabSwipeClaim] 那类问题）。
        // 把滚动限制在**内联区自己这一块**是最小扰动的做法。
        // ⚠️ 屏高**不**从 `LocalDensity` 取：`Density` 只有 `density` / `fontScale` /
        // `toDp` / `toPx` 这几个成员，**没有 `maxHeight`**。屏幕尺寸的唯一来源是
        // `LocalConfiguration.screenHeightDp`（已经是 dp，不用再做像素换算）。
        //
        // 顺带一个坑：`screenHeightDp` 拿到的是**整屏**高度，**含**系统状态栏与导航栏。
        // 本页是 edge-to-edge（见 `MainActivity` 的 `enableEdgeToEdge`），所以两者一致；
        // 若将来本页改成非 edge-to-edge，这里要减去 insets 才准。
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val maxHeight = (MAX_INLINE_PANEL_FRACTION * screenHeightDp).dp

        Column(
            modifier = modifier
                .padding(horizontal = 12.dp)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                // 底色用 `surfaceContainer`：与页面底色（`background`）拉开一档，
                // 让它读作"从月历下方浮出来的一块内容"而不是月历的一部分。
                // 圆角 16dp 与本应用其它卡片一致（见 DiscoverScreen 的卡片口径）。
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // ── 头部：日期 ─────────────────────────────────────────────────
            //
            // ⚠️ v1.1.90 起不再有「收起」按钮（用户要求删掉）。
            // 收起入口保留两条，都在 CalendarScreen：
            // ① 再点一次同一天（`onDayClick` 的切换语义）；② 翻月自动收起。
            // 原先那个 TextButton 连同它的 onCollapse 回调链
            // （CalendarScreen → CalendarBody → DayInlinePanel）一并删除——
            // 留着零调用的回调链就是死代码。
            Text(
                // ⚠️ v1.1.74 需求 8：跟随「日期显示格式」开关。
                shown.fullLabelWith(dateFormat),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            if (dayTasks.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (tasks.isEmpty()) "暂无任务" else "当天没有可记录的任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                dayTasks.forEach { task ->
                    val checked = task.id in checkedIds
                    val (status, statusColor, dotColor) = when {
                        checked -> Triple("已打卡", MaterialTheme.colorScheme.primary, Color(task.color))
                        shown.isBefore(today) -> Triple("缺卡", Color(0xFFE57373), Color(0xFFBDBDBD))
                        // 只把「未打卡」调淡：它是三种状态里最不重要的一种，
                        // 弱化后让「已打卡 / 缺卡」两种需要关注的状态更突出。
                        else -> Triple(
                            "未打卡",
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                            Color(0xFFE0E0E0)
                        )
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
                                // v1.1.72：统一按压反馈（缩放 0.96 + 变淡）。
                                // `pressScale` 在前、`clip` 在后：缩放层内侧裁剪，
                                // 圆角在中间帧上不会退化成多边形。
                                .pressScale(onClick = onOpenRecords)
                                .clip(RoundedCornerShape(8.dp)),
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
                            backfillable -> TextButton(onClick = { onBackfill(shown, task.id) }) {
                                Text("补打卡")
                            }
                            else -> Text(status, color = statusColor, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                // ⚠️ v1.1.90 起"最近 7 天内缺卡的日期可补打卡"这句提示已删（用户要求）。
                // 「补打卡」按钮本身保留：缺卡且在 7 天内时它仍会出现，
                // 什么时候能点看按钮本身就够了，不需要一句常驻说明。
            }
        }
    }

    // 二次确认：作为**兄弟节点**挂在同一个 Composable 里，而不是嵌套在上面那个 Box 里。
    // 两者的显示时机互斥——内联区是点某一天才有的，而本确认框只在点了「撤销」之后才出现；
    // 确认框出现时内联区依然在下面，形成「区之上再问一句」的层级，与 HomeScreen 的做法一致。
    //
    // ⚠️ 这里用 `date`（可能为 null）而不是 `shown`：本节点在 AnimatedVisibility **之外**，
    // 拿不到 content lambda 里的 `shown`。date 为 null（正在收起）时整个确认框不显示，
    // 这是正确的——正在收起的过程中不该还挂着一个指向旧任务的确认框。
    date?.let { d ->
        pendingUndo?.let { task ->
            ConfirmDialog(
                title = "撤销打卡",
                // ⚠️ v1.1.74 需求 8：确认框里的日期同样跟随该开关，
                // 否则用户选了「2026-10-19」却在确认框看到「2026年10月19日」，
                // 同一个词条在同屏出现两种格式会显得是 bug。
                text = "确定要撤销「${d.fullLabelWith(dateFormat)}」的「${task.name}」打卡吗？",
                confirmLabel = "撤销",
                onConfirm = {
                    // 只有走到这里（用户点了确认）才真正撤销。
                    onUndo(d, task.id)
                    pendingUndo = null
                },
                onDismiss = { pendingUndo = null }
            )
        }
    }
}

/**
 * 内联区开合动画时长。
 *
 * 取 **220ms**：与本文件翻月 Pager 的 220ms（见 [CalendarBody] 里的
 * `animateScrollToPage(..., tween(220))`）一致——同一次交互里
 * 「月历跟着翻」与「详情跟着展开」节奏相同，不会一个快一个慢。
 */
private const val INLINE_PANEL_ANIM_MS = 220

/**
 * 内联区最大高度占屏幕高度的比例（需求 10 第 3 条「不要写死截断」）。
 *
 * 取 **1/2**：本区住在页面下半（见 [CalendarBody] 的布局说明），
 * 下半的总空间本身就只有屏高的一半左右——取满等于"本区最多占满它能占的地方"，
 * 超出部分在**区内**滚动。月历那半是 `weight(1f)`，不受这个值影响，
 * 因此不存在"内联区把月历挤掉"的可能。
 *
 * ⚠️ 这里**不写死截断、不丢数据**：`verticalScroll` 保证任务再多也能翻到。
 */
private const val MAX_INLINE_PANEL_FRACTION = 0.5f
