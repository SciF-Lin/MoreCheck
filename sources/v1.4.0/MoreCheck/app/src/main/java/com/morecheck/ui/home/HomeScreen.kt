package com.morecheck.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.data.CheckTask
import com.morecheck.data.SettingsRepository
import com.morecheck.domain.StreakSummary
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.common.AppMenuItem
import com.morecheck.ui.common.CheckInCheer
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.ConfettiEffect
import com.morecheck.ui.common.pressScale
// 说明：主页此前曾内联渲染记录页正文（`RecordsContent`），本轮按需求把「记录」
// 面板迁到「统计」页，因此这里不再引用 records 包下的任何符号。
import com.morecheck.util.fullLabelWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主页进度条「涨上去」的动画时长。
 *
 * 350ms 是试出来的折中：太短（<200ms）看不出增长、跟瞬间跳变没区别；
 * 太长（>500ms）打卡反馈会显得拖沓，与撒花/回弹的节奏也对不上。
 */
private const val PROGRESS_ANIM_MS = 350

/**
 * 打卡成功后跳转到「统计」页前的等待时长（v1.1.72）。
 *
 * 需求原文：「主页完成打卡后，待动画结束后，跳转到统计页」。
 *
 * 取 **650ms**，它必须覆盖打卡那一刻同时启动的**全部**动画，取最长的那个再加余量：
 * - 撒花 `ConfettiEffect`：`tween(550, FastOutSlowInEasing)`（`ui/common/Confetti.kt`）；
 * - 进度条增长：`PROGRESS_ANIM_MS` = 350ms；
 * - 任务圆钮回弹：`spring(MediumBouncy, StiffnessHigh)`，约 300~400ms。
 *
 * 所以 650ms = 550（撒花，最长的那个）+ 100ms 余量。
 * 余量的必要性：撒花跑到 100% 时 `ConfettiEffect` 直接 `return` 不再绘制，
 * 但**最后一帧到真正看不见还有一帧的间隔**；而且跳转本身也是一次界面切换，
 * 早 100ms 会让用户觉得"花还没散就没了"。
 *
 * ⚠️ 为什么不用 `onPageFinished` 式的"等回调"而是硬等一个时长：
 * 这三段动画各自由不同的 Composable 自己驱动（撒花在 `ConfettiEffect`、
 * 进度条在 `HomeContent`、回弹在 `TaskCircle`），**没有一个统一的"全部动画结束"信号**。
 * 要造这个信号得把三个动画的进度都提到本页来，为一个跳转时机引入三处状态提升，得不偿失。
 * 硬等一个覆盖全部动画的时长，是这个需求下代价最小的正确解。
 */
private const val CHECK_IN_NAVIGATE_DELAY_MS = 650L

/**
 * 标题栏里的两个面板：打卡 / 计时。
 *
 * 两者都是**主页内部**的面板，不是新的一级页面：底部导航栏始终高亮「主页」。
 * 枚举名沿用需求里的叫法（[HomePanel]）；早期版本叫 `HomePane`。
 *
 * ⚠️ 「记录」面板**曾短暂放在这里**（上一轮需求），本轮按新需求**移到了「统计」页**：
 * 打卡记录本质是"统计"这件事的一部分，与统计页的月历、全年总览同属一簇；
 * 放在主页标题栏（打卡 / 计时 / 记录三选一）会与"我要打卡"这条主动线抢位置。
 * 迁移后 `TabDestination.Records` 二级页路由**仍然保留**（「我的」页与各「+」菜单
 * 还在用它），只是统计页标题栏入口不再跳它、而是切到本页新增的「记录」面板。
 */
private enum class HomePanel(val label: String) {
    // 标题栏标签（需求：原「主页」改为「打卡」）。
    // 只改标题栏；底部导航栏那个一级入口仍叫「主页」，两者职责不同。
    MAIN("打卡"),
    TIMER("计时")
}

/**
 * 主页状态的**进程级缓存**（2026-10-25 修「切回主页任务图标闪动」，见
 * [HomeScreen] 里那段注释）。与 `AvatarStore` 的位图缓存同一模式：
 * 跨「入口组合销毁 / 重建」存活，让 state 的初值从缓存来。
 * 只在本进程内有效，进程被杀后回到空表初值（行为与首次冷启动一致）。
 */
internal object HomeStateCache {
    var tasks: List<CheckTask> = emptyList()
    var checkedToday: Set<Long> = emptySet()
    var doneCount: Int = 0
    // 与上面三项同一套进程级缓存口径：底栏切回主页会重建组合，
    // 首帧 initialValue 取缓存，避免"连续 0 天"闪一下再跳到真实值。
    var streakSummary: StreakSummary = StreakSummary.EMPTY
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenScanner: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onNewTask: () -> Unit = {},
    /**
     * 打卡成功且反馈动画播完后回调（v1.1.72 需求：完成后跳统计页）。
     *
     * **由调用方决定跳去哪**——本页不认识底部导航，也不认识"统计"是个一级入口
     * （那是 `MoreCheckApp` 的 `AppTab` 状态）。这个回调把"何时该跳"告诉外层，
     * "跳到哪"由外层决定，与本项目 `onOpenScanner` / `onOpenRecords` 的口径一致。
     *
     * 带默认值 `{}`：既有调用方不会因这次改动编译失败（与 `onOpenScanner` 同一条纪律）。
     */
    onCheckInCompleted: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    // ── 2026-10-25 修复「切回主页时任务图标闪动」────────────────────────────
    //
    // 根因与头像闪动（HANDOVER 5.33）同一型：底栏四入口各自 `key(selectedTab)`
    // 独立组合，切回主页时整个组合重建，`collectAsStateWithLifecycle` 的
    // **initialValue**（空列表）会先画一帧空态，StateFlow 的当前值下一帧才到——
    // 用户看到任务圆钮「闪一下」再出现。
    //
    // 修法照 `AvatarStore` 的进程级缓存模式：上次离开主页时的值缓存在进程里，
    // 本次进入的 initialValue 直接取缓存，首帧画的就是完整列表；
    // 缓存随每次重组同步回写，回到主页后的首次发射（哪怕数据真的变了）
    // 也会在下一帧修正——代价最多一帧旧值，收益是消灭可感知的闪动。
    val tasks by viewModel.tasks.collectAsStateWithLifecycle(
        initialValue = HomeStateCache.tasks
    )
    val today by viewModel.today.collectAsStateWithLifecycle()
    val checked by viewModel.checkedToday.collectAsStateWithLifecycle(
        initialValue = HomeStateCache.checkedToday
    )
    val doneCount by viewModel.doneCount.collectAsStateWithLifecycle(
        initialValue = HomeStateCache.doneCount
    )
    val streakSummary by viewModel.streakSummary.collectAsStateWithLifecycle(
        initialValue = HomeStateCache.streakSummary
    )
    SideEffect {
        HomeStateCache.tasks = tasks
        HomeStateCache.checkedToday = checked
        HomeStateCache.doneCount = doneCount
        HomeStateCache.streakSummary = streakSummary
    }

    var showPicker by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<CheckTask?>(null) }
    var pendingDelete by remember { mutableStateOf<CheckTask?>(null) }
    var celebrateKey by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    // ⚠️ v1.1.74 需求 8：「打卡完成动画」开关的**生效值**。
    //
    // 取值路径与项目其它页面逐字同款：
    // `applicationContext as MoreCheckApp` → `container.developerOptionsRepository`。
    // 必须经 `applicationContext`——`LocalContext.current` 在 Compose 里是本 Activity，
    // 直接 `as MoreCheckApp` 会抛 `ClassCastException`（本项目反复踩过的一条纪律）。
    //
    // initialValue 必须是 `true`（= 改造前的既有观感）：DataStore 首读要走磁盘，
    // 在它到达之前若就渲染，这个窗口里撒花会**短暂消失**再出现——那正是需求 8
    // 想让用户能关掉的那种闪动，默认配置下不该有。
    val checkinCelebrationEnabled by rememberCheckinCelebrationEnabled()

    // ⚠️ v1.1.74 需求 8：「日期显示格式」的生效值。
    //
    // 注意这一项**只有** `dateFormatSwitch`、没有成对的 `dateFormatEnabled`——
    // 它是三选一而不是布尔，"总开关关掉时该回落哪一档"没有唯一答案
    // （回落 CN_FULL 会让用户拨好的选择在关总开关时弹回去）。
    // 与既有的 `gameChromeModeSwitch` 同一口径，理由见那个属性的 KDoc。
    val dateFormat by rememberDateDisplayFormat()

    // 标题栏选中的面板。用 rememberSaveable：进程重建后仍停在同一面板。
    var pane by rememberSaveable { mutableStateOf(HomePanel.MAIN) }

    // ── 打卡成功后跳「统计」页（v1.1.72 需求）────────────────────────────────
    //
    // 需求原文：「主页完成打卡后，待动画结束后，跳转到统计页」。
    //
    // 为什么用 `celebrateKey` 做触发源：它就是"用户刚完成一次打卡"的那一个信号
    // （每次打卡都 `celebrateKey++`，撒花也是拿它当 key），不需要另造一个状态。
    // 首次进入页面时它是 0，下面的 `<= 0` 判断把首帧跳过，不会一进主页就跳走。
    //
    // 为什么等 [CHECK_IN_NAVIGATE_DELAY_MS] 而不是立刻跳：见那个常量的注释
    // （要覆盖撒花 550ms + 进度条 350ms + 圆钮回弹，取最长再加余量）。
    LaunchedEffect(celebrateKey) {
        if (celebrateKey <= 0) return@LaunchedEffect
        delay(CHECK_IN_NAVIGATE_DELAY_MS)
        onCheckInCompleted()
    }

    // 计时状态提升到这里（而不是留在 TimerPanel 内部），
    // 否则切到「主页」再切回来时面板退出组合，倒计时会被静默重置。
    val timerState = rememberSaveable(saver = TimerState.Saver) { TimerState() }

    val haptic = LocalHapticFeedback.current
    val progress = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size

    // 计时结束的提示：复用 Scaffold 的 Snackbar，不新增任何页面元素。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        // ⚠️ 本页是**选项卡根页**，底部让位已由外层统一给出（`TabNavHost` 的
        // `rootBottomPadding` = 底栏高 80dp + 系统导航栏内边距），所以这里必须**不再**
        // 消费系统栏内边距：否则底边会被扣两次。与 `CalendarScreen` 同一根因同一修法
        // （症状：底栏上方凭空多出一条系统导航栏高的空白，内容在那条线处被切断）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // 三段可切换的标签：选中项用主题色 + 更粗字重，未选中项退到次要色。
                    //
                    // 间距与字号沿用原样（22dp / titleLarge），没有因为多出「记录」而收窄：
                    // 三个两字标签实测约 176dp，标题栏可用宽度在窄屏上也有近 300dp，
                    // 不会顶到右上角的「+」——没必要为它改动既有的视觉节奏。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomePanel.entries.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.width(22.dp))
                            val selected = pane == item
                            Text(
                                text = item.label,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.titleLarge,
                                // v1.1.72：标题栏标签也改成统一按压反馈（与统计页标题栏同源）。
                                // 这是全应用最高频的点击目标之一，裸 clickable 的默认水波纹
                                // 在浅色标题栏上几乎看不见——正是需求里"要点击甚至长按
                                // 才有动画效果"要治的那种情况。
                                modifier = Modifier.pressScale(onClick = { pane = item })
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        // 无障碍文案统一为「更多」：这个菜单里其实是三项
                        // （新建任务 / 扫一扫 / 打卡记录），叫「新建」会把后两项漏在语义之外。
                        Icon(Icons.Filled.Add, contentDescription = "更多")
                    }
                    // 菜单外观（需求：加号列表改为圆角 + 浅灰，与背景区分）：
                    // - 圆角 16dp：默认的 4dp 在"浮层"语义下太"方"，圆角让它读作一张卡片；
                    // - `surfaceContainerHigh` 是比页面底色**亮一档**的中性灰——浅色模式
                    //   下页面是灰底（surfaceContainerLow 系）而菜单更亮、深色模式下页面更暗
                    //   而菜单更亮，两个模式都能拉开层次。用 `surface` 会在浅色下与背景几乎同色。
                    //   （M3 1.3.1 的 DropdownMenu 支持 shape/containerColor，已用 javap 核实。）
                    //
                    // 统一规格：这套「加号图标 + 菜单圆角 16dp + surfaceContainerHigh + 三项
                    // 文案/顺序/图标」的规格，在**主页 / 发现 / 统计**三处保持一致；
                    // 三处要改一起改，不要各自维护一套。
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        // 三处（主页 / 发现 / 统计）菜单项统一走 [AppMenuItem]：
                        // 2026-10-31 需求「菜单按钮加大」——行高 56dp、文字 titleMedium、
                        // 图标 26dp。规格只有一份，见 `ui/common/AppMenu.kt`。
                        AppMenuItem(
                            label = "新建任务",
                            icon = Icons.Filled.Add,
                            onClick = {
                                menuOpen = false
                                onNewTask()
                            }
                        )
                        // 「扫一扫」用扫码框图标，与发现页列表保持同一枚图标。
                        AppMenuItem(
                            label = "扫一扫",
                            icon = Icons.Filled.QrCodeScanner,
                            onClick = {
                                menuOpen = false
                                onOpenScanner()
                            }
                        )
                        AppMenuItem(
                            label = "打卡记录",
                            icon = Icons.Filled.History,
                            onClick = {
                                menuOpen = false
                                onOpenRecords()
                            }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 「打卡」按钮固定在**屏幕底部**（本轮需求：从「我的任务」下方移到页面最底部）。
        //
        // 放在 Scaffold 的 `bottomBar` 槽位，而不是原来那个可滚动的 Column 里：
        // 滚动容器的高度是无限的，在它里面写 `Spacer(Modifier.weight(1f))` 只会拿到 0 高度，
        // 按钮照样跟着内容滚——长内容下要滚到底才看得见，等于没改。
        // 交给 `bottomBar` 后，Scaffold 会把它钉在底部，并**自动**从传给内容的 innerPadding
        // 里扣掉它的高度，因此滚动内容既不会钻到按钮下面，也不用手工维护「给按钮留多高」这个数。
        //
        // 只在「打卡」面板显示：切到「计时 / 记录」面板时底部不应还留着一个打卡入口。
        //
        // 判定写成 `== MAIN`（而不是 `!= TIMER`）是刻意的：本轮新增「记录」面板时，
        // 后者会让打卡按钮一起出现在记录面板底下——那正是需求点名要避免的。
        bottomBar = {
            if (pane == HomePanel.MAIN) {
                CheckInButton(
                    onClick = {
                        // 需求：只有一个任务时直接打卡，不再弹出选择列表。
                        val only = tasks.singleOrNull()
                        if (only != null && only.id !in checked) {
                            viewModel.checkIn(only.id)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            celebrateKey++
                        } else {
                            showPicker = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (pane) {
                // 计时面板：真的可用（选时长 → 开始 / 暂停 / 停止 → 到点震动 + 响铃提示）。
                // viewModel 显式传入：声音开关、铃声与「最近用过的时长」都在设置那一份实例里，
                // 靠它才和「设置 → 声音设置」页共享同一处状态。
                HomePanel.TIMER -> TimerPanel(
                    state = timerState,
                    onFinished = {
                        scope.launch { snackbarHostState.showSnackbar("时间到") }
                    },
                    viewModel = LocalSettingsViewModel.current
                )

                HomePanel.MAIN -> HomeContent(
                    // ⚠️ v1.1.74 需求 8：走「日期显示格式」开关（生效值）。
                    // `fullLabelWith` 是唯一的格式化入口，默认 `CN_FULL` 与改造前逐字相同。
                    todayLabel = today.fullLabelWith(dateFormat),
                    tasks = tasks,
                    checked = checked,
                    doneCount = doneCount,
                    progress = progress,
                    streakSummary = streakSummary,
                    onCheckIn = { id -> viewModel.checkIn(id) },
                    celebrate = { celebrateKey++ },
                    onUndoRequest = { pendingUndo = it },
                    onDeleteRequest = { pendingDelete = it }
                )
            }

            // 以下浮层放在 when 之外：它们的位置压在内容之上，
            // 不能因为切换面板就被重新挂到别的父节点里去。
            // ⚠️ v1.1.74 需求 8：「开发者选项 → 界面测试 → 打卡完成动画」的总闸。
            //
            // 关闭时**不挂载** `ConfettiEffect`（而不是传一个 `enabled=false` 进去）——
            // 撒花是纯装饰、没有任何状态要维护，不挂载就等于彻底不存在，
            // 也不会留一个每帧都在跑却什么都不画的空 Composable。
            //
            // 注意这**只关撒花**，不关打卡本身：卡片翻转、圆点变色、撤销按钮出现
            // 那些反馈都在别处（`TimerPanel` / 各卡片自身），它们是"状态变了"的信息，
            // 不是"庆祝"，关掉庆祝不该让用户看不出打卡是否成功。
            if (checkinCelebrationEnabled) {
                ConfettiEffect(celebrateKey, Modifier.fillMaxSize())
                // 打卡成功后的一句正向反馈：把 streak 的正向激励放到打卡当场，
                // 强化"连续"的正循环（连续 1 天不弹，避免打扰）。
                CheckInCheer(
                    triggerKey = celebrateKey,
                    streak = streakSummary.current,
                    modifier = Modifier.fillMaxSize()
                )
            }

            pendingUndo?.let { task ->
                ConfirmDialog(
                    title = "撤销打卡",
                    text = "确定要撤销「${task.name}」今天的打卡吗？",
                    confirmLabel = "撤销",
                    onConfirm = {
                        viewModel.undo(task.id)
                        pendingUndo = null
                    },
                    onDismiss = { pendingUndo = null }
                )
            }

            pendingDelete?.let { task ->
                ConfirmDialog(
                    title = "删除任务",
                    text = "确定要删除「${task.name}」吗？该任务的打卡记录会一并删除。",
                    confirmLabel = "删除",
                    onConfirm = {
                        viewModel.deleteTask(task)
                        pendingDelete = null
                    },
                    onDismiss = { pendingDelete = null }
                )
            }

            if (showPicker) {
                TaskPickerSheet(
                    tasks = tasks,
                    checked = checked,
                    onDismiss = { showPicker = false },
                    onConfirm = { ids ->
                        if (ids.isNotEmpty()) {
                            viewModel.checkInMany(ids)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            celebrateKey++
                        }
                        showPicker = false
                    }
                )
            }
        }
    }
}

/**
 * 「主页」面板的正文。
 *
 * 内容与加入计时面板之前**逐字一致**，只是从 HomeScreen 的 Scaffold 内容里搬进了一个
 * 独立函数，以便与「计时」面板并列做 when 分支。
 */
@Composable
private fun HomeContent(
    todayLabel: String,
    tasks: List<CheckTask>,
    checked: Set<Long>,
    doneCount: Int,
    progress: Float,
    streakSummary: StreakSummary,
    onCheckIn: (Long) -> Unit,
    celebrate: () -> Unit,
    onUndoRequest: (CheckTask) -> Unit,
    onDeleteRequest: (CheckTask) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            todayLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "今天打卡了吗？",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (tasks.isEmpty()) "先添加一个任务吧" else "已完成 $doneCount / ${tasks.size} 项",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        // 打卡后进度条要"涨"上去，而不是瞬间跳变。
        //
        // 用 animateFloatAsState 把目标进度过渡一下；`tween` 比弹簧更贴合进度条这种
        // 单向增长的量——弹簧会过冲，进度条冲过头再退回来看着像出错。
        val animatedProgress by animateFloatAsState(
            targetValue = progress,
            animationSpec = tween(durationMillis = PROGRESS_ANIM_MS),
            label = "homeProgress"
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            // 2026-10-27 评审：原来轨道色由主题色派生（浅色下实测 `#E9F8EC` 压在页底
            // `#EDEDED` 上只有 **1.06:1**，深色下 `#001706` 干脆看不见），
            // 而"已完成 0/1"时进度为 0，屏幕上就只剩那条看不见的轨道 ——
            // 看起来像渲染残留，又无法表达"这里是一条进度条"。
            // 改成中性轨道（onSurface 16%：浅色 ≈#C8C8C8 / 1.26:1，深色 ≈#2B2B2B），
            // 与强调色无关，**换任何主题色都稳定可见**。
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
            color = MaterialTheme.colorScheme.primary,
            // 关掉轨道末端那个小圆点（stop indicator）。
            //
            // Material3 1.3 起 LinearProgressIndicator 默认会在进度末端画一个"停止指示点"，
            // 在只有 0/1 两档的打卡进度条上就是一个孤零零的小圆点，看起来像渲染残留。
            // 传 DrawScope 空实现即可去掉它：drawStopIndicator 是最后一个 draw 调用，
            // 不画它不影响进度本身。
            drawStopIndicator = {},
            // ⚠️ gapSize 必须显式给 0dp（2026-10-28 修「进度条在 50% 处断开」）。
            //
            // Material3 1.3.1 的 `LinearProgressIndicator` 默认会在**进度色与轨道之间
            // 留一道 4dp 的缝**（`ProgressIndicatorDefaults.LinearIndicatorTrackGapSize`），
            // 用来把"已完成"和"未完成"在视觉上分成两段。在本应用（6dp 高的细条、
            // 底部压着同一个中性轨道色）这道缝看起来不像设计，而是**条断了一截**：
            // 2 个任务完成 1 个时正好卡在 50%，中间空出一块 4dp 的底色。
            // 传 `0.dp` 即为无缝：进度色直接接到轨道色上，整条是连续的一根。
            gapSize = 0.dp,
            // ⚠️ 端点也必须改成平头（2026-10-28 第二轮修）。
            //
            // 只把 gapSize 设成 0dp 还不够：M3 默认 `strokeCap = StrokeCap.Round`，
            // 进度色与轨道各自带一个**半圆头**，两个半圆在交界处会互相让开，
            // 上下两角留下一道楔形的底色（53% 缩放的实机截图里就是"还是断了一下"）。
            // 改成 Butt 之后交界是一条直边，两条颜色真正接上；
            // 整条进度条的**两端**不会变成直角——外层 `clip(RoundedCornerShape(3.dp))`
            // 已经把 6dp 高的条裁成圆头了，所以这一改只影响中间那道接缝。
            strokeCap = StrokeCap.Butt
        )

        // 连续打卡卡片：当前 / 最长 / 今天是否达标。放在进度条之下、任务区之上，
        // 读起来是"今天打完卡，顺带看一眼这波坚持了几天"。
        Spacer(Modifier.height(20.dp))
        StreakCard(summary = streakSummary)

        Spacer(Modifier.height(28.dp))

        if (tasks.isNotEmpty()) {
            Text(
                "我的任务",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                tasks.forEach { task ->
                    val isChecked = task.id in checked
                    TaskCircle(
                        task = task,
                        checked = isChecked,
                        onClick = {
                            if (isChecked) {
                                onUndoRequest(task)
                            } else {
                                onCheckIn(task.id)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                celebrate()
                            }
                        },
                        // 长按（手机上）或右键（鼠标）弹出删除确认。
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDeleteRequest(task)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 主页的「连续打卡」紧凑卡片。
 *
 * 样式走项目既有纪律：浅色下用 1dp 边框 + 与页面拉开一档的底色做层次，
 * **刻意不用 shadow**——软渲染器上那层投影会糊成一片灰罩，反而把卡片压脏。
 * 所有颜色取自 MaterialTheme.colorScheme，不写死任何十六进制色值，
 * 深浅色与换肤都自动跟随。
 */
@Composable
private fun StreakCard(summary: StreakSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "连续打卡",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StreakStat(label = "当前连续（天）", value = "${summary.current}", modifier = Modifier.weight(1f))
                StreakStat(label = "最长纪录（天）", value = "${summary.longest}", modifier = Modifier.weight(1f))
                // 今天是否达标用一个图标 + 一句短语表达，不占一个大数字位：
                // 它是布尔状态，数字位数与左边两列摆在一起会显得忽大忽小。
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val done = summary.todayCompleted
                    val tint = if (done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        imageVector = if (done) Icons.Filled.Check else Icons.Filled.History,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (done) "今天已达标" else "今天待打卡",
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                }
            }
        }
    }
}

/** 卡片里的一列数字 + 说明，三列共用同一套排版。 */
@Composable
private fun StreakStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 底部固定的「打卡」横条按钮。
 *
 * 常驻在页面最底部（Scaffold 的 `bottomBar`），样式对齐个人信息页的「注册 / 登录」通栏主按钮
 * （fillMaxWidth + 50dp 高 + 16sp）。
 *
 * 铺一层 `background` 是刻意的：内容区虽然在 Scaffold 的 innerPadding 里已经让出了高度，
 * 但让按钮区自带不透明底色后，将来若有人往内容区加「不参与 padding 的浮层」，
 * 也不会从按钮的缝隙里透出来。底色取 `colorScheme.background`，与 Scaffold 容器同色，
 * 视觉上不额外分层，深浅色模式都自动跟随。
 */
@Composable
private fun CheckInButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("打卡", fontSize = 16.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskCircle(
    task: CheckTask,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val scale = remember { Animatable(1f) }
    // ⚠️ 只在「本次组合内 checked 由 false 变 true」时弹一下，不能直接 `LaunchedEffect(checked)`：
    // 底栏四入口各自 `key(selectedTab)` 组合，切回主页时 TaskCircle 全部重建；
    // 而 `checked` 现在**初值来自进程内缓存**（见 HomeStateCache），首次组合就可能是 true，
    // 于是 effect 一跑就把所有已打卡圆钮从 0.8 弹回 1.0 —— 用户看到的正是
    // "切回主页，所有任务图标一起闪一下"。用上一次的取值做门槛即可滤掉重建。
    var wasChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (checked && !wasChecked) {
            scale.snapTo(0.8f)
            scale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)
            )
        }
        wasChecked = checked
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .clip(CircleShape)
                    .background(Color(task.color))
                    // combinedClickable 同时覆盖长按与右键（鼠标右键会映射为长按）。
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(task.icon, fontSize = 26.sp)
            }
            if (checked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已打卡",
                        modifier = Modifier.size(14.dp),
                        tint = Color.White
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            task.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPickerSheet(
    tasks: List<CheckTask>,
    checked: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "选择要打卡的任务",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "已选 ${selected.size} 项",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            if (tasks.isEmpty()) {
                Text(
                    "还没有任务，请先到「任务」页添加",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tasks.forEach { task ->
                    val done = task.id in checked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡）。
                            // `enabled` 照旧传"未打卡才可点"——已打卡的行点不动，
                            // 也不该有按压动画（否则像能点）。
                            .pressScale(
                                onClick = {
                                    if (task.id in selected) selected.remove(task.id) else selected.add(task.id)
                                },
                                enabled = !done
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(task.color)),
                            contentAlignment = Alignment.Center
                        ) { Text(task.icon, fontSize = 20.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.name, style = MaterialTheme.typography.bodyLarge)
                            // 备注：选择打卡任务时可见，便于区分同名/相似任务。
                            if (!task.note.isNullOrBlank()) {
                                Text(
                                    text = task.note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (done) {
                            Text("已打卡", color = MaterialTheme.colorScheme.primary)
                        } else {
                            Checkbox(
                                checked = task.id in selected,
                                onCheckedChange = { if (it) selected.add(task.id) else selected.remove(task.id) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onConfirm(selected.toSet()) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("打卡") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 「打卡完成动画」开关的**生效值**（v1.1.74 需求 8）。
 *
 * ## 为什么单独抽一个函数，而不是把取值写在调用处
 *
 * `LocalContext.current` 是 `CompositionLocal`，**只能在组合期读**；而
 * `remember { ... }` 的 lambda 是普通函数体、不是组合期。把 `LocalContext.current`
 * 写进 lambda 里会编译报错（"@Composable invocations can only happen from the
 * context of a @Composable function"）——本项目已经踩过一次（`GameActivityHost`）。
 * 因此先在**函数体**（组合期）取出 Context，再把它作为 `remember` 的 key。
 *
 * ## 为什么 initialValue 必须是 `true`
 *
 * DataStore 首读要走磁盘，值到达之前有一小段窗口。按本项目「新开关默认值必须等于
 * 改造前既有行为」的纪律，这里给 `true`——否则老用户冷启动时会看到"撒花先消失、
 * 半个身位后又出现"的闪动。
 */
@Composable
private fun rememberCheckinCelebrationEnabled(): State<Boolean> {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
    return repository.checkinCelebrationEnabled
        .collectAsStateWithLifecycle(initialValue = true)
}

/**
 * 「日期显示格式」的生效值（v1.1.74 需求 8）。
 *
 * default 参数给 `CN_FULL`：DataStore 首读要走磁盘，在它到达之前若返回别的档，
 * 会看到"日期格式先跳一下再变回来"。默认必须等于改造前观感。
 */
@Composable
private fun rememberDateDisplayFormat(): State<SettingsRepository.DateDisplayFormat> {
    val appContext = LocalContext.current.applicationContext
    val repository = remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
    return repository.dateFormatSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DateDisplayFormat.CN_FULL)
}
