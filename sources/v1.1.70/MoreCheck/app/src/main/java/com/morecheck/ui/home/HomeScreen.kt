package com.morecheck.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.CheckTask
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.ConfettiEffect
// 说明：主页此前曾内联渲染记录页正文（`RecordsContent`），本轮按需求把「记录」
// 面板迁到「统计」页，因此这里不再引用 records 包下的任何符号。
import com.morecheck.util.fullLabel
import kotlinx.coroutines.launch

/**
 * 主页进度条「涨上去」的动画时长。
 *
 * 350ms 是试出来的折中：太短（<200ms）看不出增长、跟瞬间跳变没区别；
 * 太长（>500ms）打卡反馈会显得拖沓，与撒花/回弹的节奏也对不上。
 */
private const val PROGRESS_ANIM_MS = 350

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenScanner: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onNewTask: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val checked by viewModel.checkedToday.collectAsStateWithLifecycle()
    val doneCount by viewModel.doneCount.collectAsStateWithLifecycle()

    var showPicker by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<CheckTask?>(null) }
    var pendingDelete by remember { mutableStateOf<CheckTask?>(null) }
    var celebrateKey by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    // 标题栏选中的面板。用 rememberSaveable：进程重建后仍停在同一面板。
    var pane by rememberSaveable { mutableStateOf(HomePanel.MAIN) }

    // 计时状态提升到这里（而不是留在 TimerPanel 内部），
    // 否则切到「主页」再切回来时面板退出组合，倒计时会被静默重置。
    val timerState = rememberSaveable(saver = TimerState.Saver) { TimerState() }

    val haptic = LocalHapticFeedback.current
    val progress = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size

    // 计时结束的提示：复用 Scaffold 的 Snackbar，不新增任何页面元素。
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
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
                                modifier = Modifier.clickable { pane = item }
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                            // 「扫一扫」用扫码框图标，放大镜留给「搜一搜」。
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
                    todayLabel = today.fullLabel(),
                    tasks = tasks,
                    checked = checked,
                    doneCount = doneCount,
                    progress = progress,
                    onCheckIn = { id -> viewModel.checkIn(id) },
                    celebrate = { celebrateKey++ },
                    onUndoRequest = { pendingUndo = it },
                    onDeleteRequest = { pendingDelete = it }
                )
            }

            // 以下浮层放在 when 之外：它们的位置压在内容之上，
            // 不能因为切换面板就被重新挂到别的父节点里去。
            ConfettiEffect(celebrateKey, Modifier.fillMaxSize())

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
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            // 关掉轨道末端那个小圆点（stop indicator）。
            //
            // Material3 1.3 起 LinearProgressIndicator 默认会在进度末端画一个"停止指示点"，
            // 在只有 0/1 两档的打卡进度条上就是一个孤零零的小圆点，看起来像渲染残留。
            // 传 DrawScope 空实现即可去掉它：drawStopIndicator 是最后一个 draw 调用，
            // 不画它不影响进度本身。
            drawStopIndicator = {}
        )

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
    LaunchedEffect(checked) {
        if (checked) {
            scale.snapTo(0.8f)
            scale.animateTo(
                1f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh)
            )
        }
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
                            .clickable(enabled = !done) {
                                if (task.id in selected) selected.remove(task.id) else selected.add(task.id)
                            }
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
