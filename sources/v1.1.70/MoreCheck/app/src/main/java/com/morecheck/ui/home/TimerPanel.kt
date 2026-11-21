package com.morecheck.ui.home

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 标题栏「计时」标签下的计时面板。
 *
 * 结构：上方三列循环滚轮选时长，中间一排「最近用过的时长」芯片，
 * 底部按钮按状态切换（未开始 = 一个胶囊播放键；计时中 = 停止 + 暂停；暂停中 = 停止 + 继续）。
 * 到 0 时震动两次、响铃（可在声音设置里关掉）并回调 [onFinished]，由宿主页决定怎么提示。
 *
 * 面板本身**不持有**跨标签存活的状态——时长与倒计时全部挂在 [TimerState] 上由 [HomeScreen] 持有，
 * 原因见 [TimerState] 的类注释。
 */
@Composable
fun TimerPanel(
    state: TimerState,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    // 与 PermissionsScreen / SettingsPage 一致：复用 MainActivity 下发的同一份实例。
    // 放在最后且带默认值，因此原有的三参数调用点不必改动。
    viewModel: SettingsViewModel = LocalSettingsViewModel.current,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val soundEnabled by viewModel.timerSoundEnabled.collectAsStateWithLifecycle()
    val soundUri by viewModel.timerSoundUri.collectAsStateWithLifecycle()
    val recentDurations by viewModel.recentTimerDurations.collectAsStateWithLifecycle()

    val ringer = rememberTimerRinger()

    // 到点那一刻要读的是**当时**的开关与铃声。这个协程是按 isRunning / selectedSeconds
    // 启动的，不会因为用户中途去声音设置里改开关而重启，所以用 rememberUpdatedState
    // 保证它读到的是最新值，而不是启动瞬间的旧值。
    val currentSoundEnabled by rememberUpdatedState(soundEnabled)
    val currentSoundUri by rememberUpdatedState(soundUri)

    // 「重组时钟」：运行中每 200ms 自增一次。
    //
    // 「还剩多少秒」是从结束时刻（单调时钟）现算的，而**时间流逝本身不会触发重组**，
    // 所以必须有一个快照状态按秒变化去驱动重组，否则界面会停在初始值不动。
    var refreshTick by remember { mutableIntStateOf(0) }

    // 展示与判定共用的剩余秒数：**永远现算，不保存任何副本**。
    //
    // ★ 这里刻意不再用「变量镜像 + 协程异步回写」的写法，那正是用户报的
    // 「点按滚轮调整时间时，开始按钮异常闪动」的根因：
    // 镜像由协程回写、会比 `selectedSeconds()` **晚一帧**。滚轮停稳回写时
    // `selectedSeconds()` 先变成新值（如 480 → 540），镜像却还是旧的 480，
    // 于是下面 `isPaused` 的判定 `0 < 480 < 540` 会短暂成立，按钮行就从
    // 「单个开始胶囊」闪成「停止 + 继续」两个圆钮、再闪回来。
    //
    // 现在把「重算时机」和「取值」分开：
    //   · key 列表决定**什么时候**重算；
    //   · lambda 里 `state.remainingSeconds()` 决定取**什么**值，每次都从快照现算。
    // 于是 remaining 与 selectedSeconds 在同一帧取自同一份快照，中间态在结构上不可能出现。
    //
    // key 里为什么必须有 `state.remainingSeconds()`：它读的是 `frozenRemaining` 等快照状态，
    // 放进 key 就等于订阅了它们。少了它，「暂停中按停止」（reset() 把 frozenRemaining
    // 从部分剩余改回整段时长，而 isRunning 与所选时长都没变）就不会触发重算，
    // 按钮会错误地停在「停止 + 继续」两键状态上。
    val remaining = remember(
        refreshTick,
        state.isRunning,
        state.selectedSeconds(),
        state.remainingSeconds()
    ) {
        state.remainingSeconds()
    }

    // 滚轮当前选中的**总时长**（秒）。提前到这里取而不是留在下面按钮那一块，
    // 是因为「滚轮能不能滑」与「按钮该显示什么」两个判定都要用它，
    // 而滚轮在按钮之前就先调用了；两处共用同一个值，也才不会有先后不一致的中间帧。
    val selectedSeconds = state.selectedSeconds()

    // 暂停中：不在跑，但剩余既不是 0、也不是「完整时长」——即中途停手。
    //
    // 这个判定原本写在按钮那一块，现在提到这里，是因为滚轮的可用性也要用它（见下）。
    // 判定条件一个字都没改。
    val isPaused = !state.isRunning && remaining > 0L && remaining < selectedSeconds.toLong()

    // 「本次计时已经开始」= 计时中 或 暂停中。
    //
    // ★ 为什么暂停也算「已开始」（需求：计时开始后禁止滑动）：
    // 暂停只是同一轮倒计时中途停手（剩余毫秒被冻结），时长在按下开始那一刻就已经被认定；
    // 此时若还允许滚，用户一滑就会改掉 selectedSeconds，而冻结的剩余时间并不会跟着变，
    // 于是「共N分钟」与读数对不上，按「继续」走完的也不是他刚在滚轮上看到的那段时长。
    // 只有「未开始 / 停止复位 / 已走完」这三种真正回到准备就绪的情况才可滑。
    //
    // ⚠️ 边界（既有行为，本次不改）：这个判定与按钮那一行用的是**同一个**表达式，
    // 所以它和按钮显示的三种状态天然一致——不会出现「滚轮锁了但按钮说准备就绪」。
    // 代价是「开始后不到 1 秒内就暂停」时，remaining 因为向上取整仍等于完整时长，
    // 界面（按钮与滚轮）会一起回到「准备就绪」态；要消掉它得改 remainingSeconds 的取整口径，
    // 那会牵动计时精度那批历史修复，属于需求之外，这里不动。
    val started = state.isRunning || isPaused

    // 到点信号：每完成一轮计时自增一次（0 = 还没完成过）。
    // 用来把「到点要做的提示」交给下面那个独立的 effect 执行，理由见那里的注释。
    var finishTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.isRunning, state.selectedSeconds()) {
        while (state.isRunning) {
            if (state.remainingSeconds() <= 0L) {
                // 先把状态收干净（isRunning = false、剩余置 0），界面立刻回到初始态，
                // 不会出现「已经 00:00:00 了按钮还停在停止/暂停」的中间帧。
                state.finish()
                // ⚠️ 这里**只能**发信号，不能直接做震动 / 响铃 / onFinished。
                //
                // 本 effect 以 state.isRunning 为 key，而上面这行 finish() 恰好把 isRunning
                // 置成 false —— 等于**用自己的状态写入取消自己**：紧随其后的任何挂起点
                // （原实现里就有一个 delay(180)）都会抛出 CancellationException，
                // 把后面的第二次震动、响铃、onFinished 全部丢掉。
                // 这就是用户报的「倒计时结束后没有像计划一样播放声音」的根因
                //（Snackbar「时间到」挂在 onFinished 里，所以它同样不会弹）。
                // finishTick++ 是一次非挂起的纯写入，协程取消插不进来，信号一定能发出去。
                finishTick++
                return@LaunchedEffect
            }
            // 自增即通知重组，下一帧 remaining 会现算出新的剩余。
            refreshTick++
            delay(200)
        }
    }

    // 到点一次性提示：震动两次 + 响铃 + 回调 onFinished。
    //
    // ★ 单独放在这个 effect 里，且它**只读 finishTick、不写任何自己依赖的 key**，
    // 因此不可能被自己取消（上面那个循环会被自己的 finish() 取消，副作用不能留在里面）。
    // 它读的 currentSoundEnabled / currentSoundUri 都是 rememberUpdatedState 的最新值，
    // 用户中途去声音设置改开关也能拿到当前值。
    LaunchedEffect(finishTick) {
        // 首帧 finishTick 就是 0，直接跳过，避免刚进页面就震一下 / 响一声。
        if (finishTick == 0) return@LaunchedEffect
        // 连续两次长震，比一次更容易被注意到。
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(180)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // 响铃在震动之后：开关关掉就完全不播（震动与 Snackbar 不受影响）。
        // 播放失败由 TimerRinger 内部静默降级，绝不因为铃声问题影响「到点」这件事。
        if (currentSoundEnabled) ringer.play(context, currentSoundUri)
        onFinished()
    }

    // 铃声响到一半时用户又操作了计时器：在下面的按钮处理里显式 ringer.stop()，
    // 而不是靠 isRunning 的变化去猜——到点那一刻 isRunning 会从 true 变回 false，
    // 用「状态跳变」推导很容易把刚播出来的到点铃声立刻掐掉。显式调用最不容易出错。
    // （离开页面 / 切标签由 rememberTimerRinger 的 DisposableEffect 兜底。）

    // 正在计时时不让芯片显示为选中：此时改时长没有意义（改了也不会生效）。
    val selectedChip = if (state.isRunning) -1 else selectedSeconds

    Column(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ 内边距的**内外之分**很关键（需求 bug3：底栏上方有一截内容被切掉、又滚不到）。
            //
            // 写成 `.verticalScroll(...).padding(vertical = 20.dp)`：
            // 垂直内边距落在**滚动内容内部**，于是它属于可滚动范围 ——
            // 内容超高时，滚到底能看到最后一行，且下面还留 20dp 呼吸空间，
            // 不会像以前那样让最后一行正好压在视口边缘、被横向切成半截。
            //
            // 与 `ChangelogScreen`（已验证可滚动）保持同一写法；水平留白留在外层，
            // 这样滚动位置不会因侧边留白而左右偏移。
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            // bottom 由 28dp 加到 48dp：底部那行提示小字已整段删除（原先它加 8dp 内边距
            // 一共占掉约 44dp 的视线空间），这段留白挪进滚动内容内部补回来，
            // 滚到底时按钮下方仍有呼吸空间，不会贴着底栏。
            .padding(top = 20.dp, bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 原先这里有一个「计时」小标题，已按要求删除（标题栏上那个「计时」标签是面板切换用的，保留）。
        // 只留一小段呼吸空间，滚轮不至于贴着状态栏太挤，也不会离得太空。
        Spacer(Modifier.height(12.dp))

        // ── 滚轮 / 读数 按状态二选一显示（v1.1.69 需求 1）────────────────────
        //
        // 需求原文：「设置时间时仅显示轮盘，开始计时后，轮盘消失，仅显示剩余时间」。
        //
        // 旧版两块**同时常驻**（滚轮在上、读数在下），设置阶段用户要越过一大块滚轮
        // 才能看到自己设了多久；计时阶段滚轮又占着位置却已经不能滑。
        // 现在按 `started`（= 计时中 或 暂停中，见其定义）二选一：
        //   · 未开始 → 只显示滚轮；
        //   · 已开始 → 只显示读数（剩余时间 + 共N分钟 + 状态文案）。
        //
        // ## 为什么用 AnimatedContent 而不是两个 if
        //
        // 硬切会让读数「瞬移」到滚轮的位置，视觉上像跳了一下。AnimatedContent
        // 用 200ms 的缩放 + 淡入淡出过渡，与「收起 / 展开」的语义一致
        // （本项目对显隐切换的统一口径，参照 NotebookOverlayService 的小球切换）。
        //
        // ## 滚轮状态为什么不会丢
        //
        // 滚轮的时/分/秒存在 [TimerState]（由 HomeScreen 持有的状态对象）里，
        // **不在**滚轮组件内部——切换显示只是让它退出组合，状态本身在
        // `state.hours/minutes/seconds` 上原样保留。按「停止」回到准备态时
        // 滚轮显示的还是用户刚才拨的那几个数（reset() 也刻意只重置剩余时间，
        // 不动滚轮选择，这是既有语义，本次不改）。
        AnimatedContent(
            targetState = started,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.92f)) togetherWith
                    fadeOut(tween(150))
            },
            label = "timer-picker-vs-readout"
        ) { showReadout ->
            if (showReadout) {
                // ---- 已开始：只显示读数 ----
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 大号剩余时间：等宽字体、加粗，数字跳动时整行不会左右晃。
                    Text(
                        text = formatDuration(remaining),
                        fontSize = 48.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(2.dp))
                    // 正下方这行是**本次设定的总时长**，与剩余时间无关，所以计时 / 暂停中
                    // 读的仍是 selectedSeconds（计时开始后滚轮已锁住，这个值不会变）。
                    Text(
                        text = formatTotalDurationLabel(selectedSeconds),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    // 既有状态文案（计时中 / 时间到 / 准备就绪），取值与判定一个字没改。
                    Text(
                        text = when {
                            state.isRunning -> "计时中，点暂停可停下"
                            remaining <= 0L -> "时间到"
                            else -> "准备就绪"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // ---- 未开始：只显示滚轮 ----
                TimeWheelPicker(
                    selectedHours = state.hours,
                    selectedMinutes = state.minutes,
                    selectedSeconds = state.seconds,
                    // 未开始态恒可滑（`started` 为 false 时这里必然为 true），
                    // 保留 enabled 参数是为了不破坏组件契约，也让"锁定"逻辑仍有单一来源。
                    enabled = true,
                    onHoursChange = { state.updateHours(it) },
                    onMinutesChange = { state.updateMinutes(it) },
                    onSecondsChange = { state.updateSeconds(it) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // 最近用过的时长：横向滚动的一排，点一下就把滚轮与剩余时间整体换成该值，
        // 长按删除这一条。没有任何记录时显示占位文案（不塞假数据）。
        RecentDurationChips(
            durations = recentDurations,
            selected = selectedChip,
            enabled = !state.isRunning,
            onPick = { total ->
                state.applyPreset(total)
                // 不必再手动同步 remaining：它由 remember 按状态重算，
                // applyPreset 写入 frozenRemaining 后同一帧就是新值。
                // 点芯片只是「摆好时长」，不算用过，因此不在这里记历史（见下方开始按钮）。
            },
            onForget = { total -> viewModel.forgetTimerDuration(total) }
        )

        Spacer(Modifier.height(28.dp))

        // ---- 底部按钮：三种状态 --------------------------------------------------
        //
        // 判定只用三个量，互斥且穷尽，不会出现「重置后仍显示两个按钮」这类错位：
        //   isRunning                                  → 计时中
        //   !isRunning && 0 < remaining < selectedSeconds → 暂停中（中途停下）
        //   其余（remaining == selectedSeconds 或 remaining <= 0）→ 初始 / 刚重置 / 刚走完
        //
        // 注意 remaining 可能大于 selectedSeconds 吗？不会：start() 的剩余只减不增，
        // 而滚轮在本轮计时开始后（含暂停）就被禁用，所以 selectedSeconds 不会变。
        //
        // isPaused 与上面滚轮的可用性判定已经在函数开头统一算好（`started`），
        // 这里直接复用，按钮与滚轮不会各算一套而错位。
        val actions = when {
            state.isRunning -> listOf(
                // 停止：回到初始态（剩余 = 滚轮所选时长），按钮随即变回单个胶囊。
                // 顺手停掉可能还在响的铃声：用户主动停了，就不该再响。
                // **不节流**：停止是"放弃本轮"的明确动作，连按往往就是想赶紧停，
                // 加间隔只会让人多按一次。
                TimerAction(Icons.Filled.Stop, "停止") {
                    ringer.stop()
                    state.reset()
                },
                // 暂停：v1.1.69 需求 2——两次点击不小于 0.3s。
                TimerAction(
                    icon = Icons.Filled.Pause,
                    description = "暂停",
                    throttleMs = PAUSE_RESUME_THROTTLE_MS
                ) {
                    ringer.stop()
                    state.pause()
                },
            )

            isPaused -> listOf(
                // 停止：语义同上。暂停中的「停止」不记历史（这一轮开始时就已记过）。
                TimerAction(Icons.Filled.Stop, "停止") {
                    ringer.stop()
                    state.reset()
                },
                // 继续：从冻结的剩余时间接着走，不重记历史（时长不是这次新选的）。
                // 与暂停共用同一节流间隔——它们是同一枚按钮的两种形态，
                // 用户在「暂停↔继续」之间快速连点时，限速的正是这个组合。
                TimerAction(
                    icon = Icons.Filled.PlayArrow,
                    description = "继续",
                    throttleMs = PAUSE_RESUME_THROTTLE_MS
                ) {
                    ringer.stop()
                    state.start()
                },
            )

            else -> listOf(
                TimerAction(Icons.Filled.PlayArrow, "开始") {
                    // 记历史的时机 = 真正开始计时的那一刻。
                    // 理由：芯片这一排的语义是「我实际用过的时长」——
                    //   · 点芯片不记：那只是把滚轮摆好，可能接着又改，属于选择过程而非使用；
                    //   · 滚轮每动一下不记：拖动会扫过十几个值，记下来的全是噪音；
                    //   · 暂停后继续也不记：同一次倒计时，时长没有重新选择过。
                    // 只有「从初始 / 重置 / 走完的状态按下开始」才代表用户认可了这个时长。
                    // 上一轮可能还在响铃（短时间连按开始），先停掉再开新的。
                    // **不节流**：理由同「停止」。
                    ringer.stop()
                    viewModel.rememberTimerDuration(state.selectedSeconds())
                    state.start()
                },
            )
        }

        TimerButtonRow(
            actions = actions,
            // 只有一个按钮时是「开始」：00:00:00 时 start() 本来就无事可做，这里显式置灰。
            enabled = if (actions.size == 1) selectedSeconds > 0 else true
        )

        // 原先这里还有一行小字提示（「上下滑动滚轮选时长…长按可删除。」），已按要求整段删除：
        // 它连同上方 20dp、下方 8dp 两个 Spacer 一起去掉，不留空白块；
        // 底部呼吸空间改由滚动内容内部的 bottom 内边距承担（见本函数顶部 modifier 的注释）。
    }
}

/**
 * 计时器的全部状态，由 [HomeScreen] 持有并在两个标签之间共享。
 *
 * 为什么把它提升到 [HomeScreen] 而不是留在面板内部：
 * 「主页」与「计时」是标题栏里的两个互斥分支，切走时面板会退出组合，
 * 面板内的 `remember` 状态随之丢失，倒计时会被静默重置；用 `rememberSaveable`
 * 留在面板里虽然也能活下来，但依赖「回到同一组合位置」才恢复，比显式提升更脆。
 *
 * 为什么不用「每秒把剩余秒数减一」：
 * 切到后台时 Compose 的 `delay` 会被系统限流，这样累计出来的倒计时是偏慢的。
 * 这里只记录「应当结束的**单调**时刻」（`SystemClock.elapsedRealtime()`，不受系统对时影响），
 * 剩余时间每次现算，切标签 / 退后台都不会算错。
 */
@Stable
class TimerState(
    initialHours: Int = 0,
    initialMinutes: Int = 8,
    initialSeconds: Int = 0
) {
    var hours by mutableIntStateOf(initialHours)
        private set
    var minutes by mutableIntStateOf(initialMinutes)
        private set
    var seconds by mutableIntStateOf(initialSeconds)
        private set

    /** 计时是否正在跑。对外只读，只能通过 [start] / [pause] / [reset] / [finish] 改变。 */
    var isRunning by mutableStateOf(false)
        private set

    /**
     * 结束时刻（毫秒），取自 [SystemClock.elapsedRealtime]；[NO_END] 表示当前没有在计时。
     *
     * ★ 为什么时间源从 `System.currentTimeMillis()` 换成单调时钟
     *（用户所报「时间不变」的根因不在这里，这是同一批精度问题里顺带修掉的一半）：
     * 墙钟会被系统对时改写——NTP 校准、用户手动改时间、跨时区飞行。
     * 而「未来时刻 − 当前时刻」的算法一旦遇到墙钟被**往前**跳，
     * 剩余时间会凭空少掉一截甚至直接变成负数，界面表现为倒计时突然卡住或立刻到点；
     * 被往后跳则倒计时凭空空转。`elapsedRealtime()` 从开机起单调递增、
     * 不受任何改时间影响，凡是「过了多久」的计算都应该用它。
     * 本次只换时间源，判定与显示语义完全不变。
     *
     * 哨兵值取 -1L（[NO_END]）而不是 0L：`elapsedRealtime()` 的零点就是开机那一刻，
     * 刚开机的一瞬间它确实可能返回 0，用 0 当「没有在计时」会跟这个真实取值撞上。
     */
    private var endAtMillis by mutableLongStateOf(NO_END)

    /**
     * 未在计时时冻结的剩余时间，单位**毫秒**。
     *
     * ★ 这里是用户所报「连续多次按暂停再开始，显示的时间不变」的根因。
     *
     * 旧实现：本字段存的是**向上取整到整秒**的秒数（`remainingSeconds()` 的返回值），
     * 而 `start()` 又拿这个整秒数去重算结束时刻（`now + left * 1000L`）。
     * 于是每一轮「暂停 → 继续」都会把不足一秒的零头**向上抹成整秒**：
     * 真实还剩 7.2 秒 → 暂停冻结成 8 秒 → 继续时又从 8 秒整重新起跑，
     * 倒计时凭空多出 0.8 秒。反复按几次，每按一次就多攒一截，
     * 与此同时显示是向下取整的整秒读数，短期内看着就是同一个数字——
     * 用户看到的现象正是「时间不变（不推进）」。
     *
     * 现在：这里存的就是**毫秒**，暂停时原样保存「还剩多少毫秒」，
     * 继续时 `endAt = now + 这一个毫秒数`，整条链路没有任何乘除与取整，
     * 因此暂停/继续任意多次都不会累积误差（每轮最多在毫秒以下截断一次）。
     * 秒级读数只是 [remainingSeconds] 现算出来的展示值，绝不回写状态。
     */
    private var frozenRemaining by mutableLongStateOf(
        (initialHours * 3600 + initialMinutes * 60 + initialSeconds).toLong() * 1000L
    )

    /** 滚轮当前选中的总秒数。 */
    fun selectedSeconds(): Int = hours * 3600 + minutes * 60 + seconds

    /**
     * 当前剩余**秒数**，向上取整（因此刚点开始时显示的正是完整时长，到 0 才显示 00:00:00）。
     *
     * 这是**纯展示 / 判定**用的派生值：向上取整只影响怎么显示，不影响怎么计时。
     * 它绝不写回任何状态——一旦写回就等于把零头抹掉，正是 [frozenRemaining] 注释里那个 bug。
     */
    fun remainingSeconds(): Long {
        // 未计时时 frozenRemaining 已经是毫秒，按同一口径取整，两种状态显示才不会跳。
        val leftMillis = currentRemainingMillis()
        return if (leftMillis <= 0L) 0L else (leftMillis + 999L) / 1000L
    }

    /**
     * 当前剩余**毫秒**。内部一切计算都走这里，好处是「取剩余」这个动作本身不再丢精度：
     * 暂停时直接冻结它即可，不必先折算成秒。
     */
    private fun currentRemainingMillis(): Long =
        if (!isRunning) {
            frozenRemaining
        } else {
            (endAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }

    fun start() {
        // 上一轮已经走完（剩余为 0）时，按当前滚轮选择重新开始，
        // 这样「到点之后再点一次播放」符合直觉，而不是按了没反应。
        if (frozenRemaining <= 0L) frozenRemaining = selectedSeconds().toLong() * 1000L
        // 直接使用冻结的**毫秒**剩余量，不再「折算成秒再乘 1000」——少一次乘除就少一次截断。
        val leftMillis = frozenRemaining
        if (leftMillis <= 0L) return
        endAtMillis = SystemClock.elapsedRealtime() + leftMillis
        isRunning = true
    }

    fun pause() {
        // ⚠️ 必须冻结**毫秒**。这里若写成 remainingSeconds()（秒级、而且向上取整），
        // 每次暂停都把零头向上抹一次，反复暂停/继续就会一轮轮攒出时间，
        // 显示上却看不出变化——这正是用户报的「时间不变」，切勿改回去。
        frozenRemaining = currentRemainingMillis()
        isRunning = false
        endAtMillis = NO_END
    }

    fun reset() {
        isRunning = false
        endAtMillis = NO_END
        // 与 frozenRemaining 的新口径一致：重置即「剩余 = 滚轮所选时长」，单位毫秒。
        frozenRemaining = selectedSeconds().toLong() * 1000L
    }

    /** 倒计时走到 0：停下来并把剩余置 0（滚轮上的时长保持不动，方便再来一次）。 */
    fun finish() {
        isRunning = false
        endAtMillis = NO_END
        frozenRemaining = 0L
    }

    /** 历史时长芯片 / 预设：一次性设定时分秒并同步剩余时间。 */
    fun applyPreset(total: Int) {
        val safe = total.coerceIn(0, MAX_SECONDS)
        hours = safe / 3600
        minutes = (safe % 3600) / 60
        seconds = safe % 60
        // 未计时时的剩余量与 reset() 同口径，用毫秒。
        frozenRemaining = safe.toLong() * 1000L
    }

    /**
     * 滚轮某一列停稳后回写选中值。计时中滚轮是禁用的，因此不会干扰正在跑的倒计时。
     *
     * 这三个函数刻意**不叫** `setHours` / `setMinutes` / `setSeconds`：
     * 上面的 `var hours` 等委托属性本身就会在 JVM 上生成 `setHours(I)V` 之类的 setter，
     * 即使加了 `private set` 也一样，同名会造成 Platform declaration clash。
     * 早期版本正是踩了这个坑，编译直接失败（`same JVM signature (setHours(I)V)`）。
     */
    fun updateHours(value: Int) {
        val v = value.coerceIn(0, 23)
        if (v == hours) return
        hours = v
        syncFrozen()
    }

    fun updateMinutes(value: Int) {
        val v = value.coerceIn(0, 59)
        if (v == minutes) return
        minutes = v
        syncFrozen()
    }

    fun updateSeconds(value: Int) {
        val v = value.coerceIn(0, 59)
        if (v == seconds) return
        seconds = v
        syncFrozen()
    }

    /** 未在计时时，把剩余时间对齐到滚轮上的选择（单位毫秒，与 [frozenRemaining] 同口径）。 */
    private fun syncFrozen() {
        if (!isRunning) frozenRemaining = selectedSeconds().toLong() * 1000L
    }

    companion object {
        /** 上限 23:59:59，与滚轮的量程一致。 */
        const val MAX_SECONDS = 23 * 3600 + 59 * 60 + 59

        /** 「没有在计时」的哨兵值：不用 0，因为 [SystemClock.elapsedRealtime] 的零点就是开机时刻。 */
        private const val NO_END = -1L

        /**
         * [Saver] 的持久化格式版本号。见 [Saver] 的注释：
         * `frozenRemaining` 从「秒」改成了「毫秒」，必须能区分新旧数据。
         */
        private const val SAVER_VERSION = 2

        /**
         * 让 [TimerState] 跨 `rememberSaveable` 存活：只存时分秒与冻结剩余（毫秒）。
         *
         * 刻意不存运行中的结束时刻——它现在取自开机后的单调时钟，
         * 进程被系统回收后那个时间点同样没有意义，恢复成「暂停在剩余时间」
         * 比恢复成一个早已过期的倒计时更合理，也不会让用户回到页面时发现计时已经悄悄走完了。
         *
         * ★ 第 5 个槽位是**格式版本号**，不能省：
         * `rememberSaveable` 的数据放在 Bundle 里，能活过进程被回收后重建，
         * 所以升级后的新代码完全可能读到旧版本写下的列表。
         * `frozenRemaining` 的口径已经从「秒」变成「毫秒」，若不区分版本，
         * 旧的 8（秒）会被当成 8（毫秒）恢复，用户回到页面会看到倒计时只剩 00:00:01、
         * 几乎立刻到点。因此：元素个数不足 5 的一律按旧格式（秒）解读再乘 1000 升级；
         * 顺带把脏值（负数）夹回 0，避免恢复出一个负数剩余。
         */
        val Saver = listSaver<TimerState, Int>(
            save = {
                listOf(
                    it.hours,
                    it.minutes,
                    it.seconds,
                    it.frozenRemaining.toInt(),
                    SAVER_VERSION
                )
            },
            restore = {
                TimerState(it[0], it[1], it[2]).apply {
                    // 只有长度 5 才是新格式（毫秒）；其余按旧格式（秒）升级。
                    val millis = if (it.size >= 5) it[3].toLong() else it[3].toLong() * 1000L
                    frozenRemaining = millis.coerceAtLeast(0L)
                }
            }
        )
    }
}

/** 滚轮单行高度。所有几何换算都建立在「行高固定」之上。 */
private val WheelItemHeight = 52.dp

/**
 * 循环滚动的总页数倍数：`count * LOOP_MULTIPLIER`。
 *
 * Pager 要求 pageCount 有限（`Int.MAX_VALUE` 会让它去算一个荒谬的滚动范围），
 * 取 1000 已经足够：最小的一列（24 页）也有 24000 页，从中间出发上下各有一万多页余量，
 * 正常人不可能滑到头。到不了头，也就不会出现「滑到边界卡住」。
 */
private const val LOOP_MULTIPLIER = 1000

/** 中心按钮尺寸 / 图标尺寸 / 按钮间距：三种状态共用，保证视觉与手感一致。 */
private val TimerButtonSize = 64.dp
private val TimerButtonIconSize = 30.dp
private val TimerButtonSpacing = 28.dp

/** 「开始」胶囊按钮的宽度：明显横向，一眼能看出是主操作。 */
private val TimerStartButtonWidth = 176.dp

/**
 * 「暂停 / 继续」按钮的两次点击最小间隔（v1.1.69 需求 2）。
 *
 * 取 **300ms**：需求原文「两次暂停点击不小于 0.3s」。
 * 为什么这个按钮要限速：暂停/继续是**状态切换**，连点会让状态高速翻转
 * （跑→停→跑→停），用户既看不清当前状态，也容易在"想停"时多按一下反而又跑了；
 * 而 300ms 又短于人对"我这下按轻了没生效"的感知阈值，不会显得迟钝。
 *
 * 只作用于暂停与继续（同一枚按钮的两种形态），**不作用于停止与开始**：
 * 那两 个是"放弃 / 重新开始"的明确动作，连按通常就是想赶紧执行，加间隔只会添乱。
 */
private const val PAUSE_RESUME_THROTTLE_MS = 300L

/**
 * 模糊是否可用。
 *
 * `Modifier.blur` 底层是 `RenderEffect`，**API 31+ 才真的生效**，在 29 / 30 上无效果（降级）。
 * 这里显式判断一次，低版本干脆不加这个 modifier，既省一次无用的节点，也避免误以为「模糊坏了」。
 */
private val BlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 三列滚轮：时 0-23 / 分 0-59 / 秒 0-59，中间一行高亮，上下相邻项变暗缩小并逐渐模糊。
 *
 * 用 `VerticalPager` 而不是 `LazyColumn` + 手动算中间行：
 * Pager 的 `currentPage` 本身就定义了「停在吸附位的那一页」，
 * 配合 `contentPadding` 就是「中间那一页」；而 LazyColumn 要靠
 * `visibleItemsInfo.offset` 反推中间行，那个 offset 是否包含 contentPadding
 * 在 Compose 里并没有稳定的语义保证，很容易整体偏一到两行。
 */
@Composable
private fun TimeWheelPicker(
    selectedHours: Int,
    selectedMinutes: Int,
    selectedSeconds: Int,
    enabled: Boolean,
    onHoursChange: (Int) -> Unit,
    onMinutesChange: (Int) -> Unit,
    onSecondsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WheelColumn(
            count = 24,
            selected = selectedHours,
            enabled = enabled,
            suffix = "时",
            onSettled = onHoursChange,
            modifier = Modifier.weight(1f)
        )
        WheelSeparator()
        WheelColumn(
            count = 60,
            selected = selectedMinutes,
            enabled = enabled,
            suffix = "分",
            onSettled = onMinutesChange,
            modifier = Modifier.weight(1f)
        )
        WheelSeparator()
        WheelColumn(
            count = 60,
            selected = selectedSeconds,
            enabled = enabled,
            suffix = "秒",
            onSettled = onSecondsChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WheelSeparator() {
    Text(
        text = ":",
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

/**
 * 把「无限页码」折算回 0..count-1 的规范值。
 *
 * 为什么必须取模：页码是 `count * LOOP_MULTIPLIER` 这么大的绝对索引，
 * 而 `TimerState.updateHours/updateMinutes/updateSeconds` 内部有 `coerceIn(0,23)` / `(0,59)`，
 * 两者语义不同——回写状态、显示数字、跟 `selected` 比相等，用的都必须是取模后的规范值。
 * 直接传绝对页码会让小时永远是 23、分钟永远是 59（被 clamp 死），所以这里统一收敛。
 */
private fun canonicalPage(page: Int, count: Int): Int = ((page % count) + count) % count

/**
 * 单列滚轮（无限循环）。
 *
 * ## 循环怎么做到无缝
 *
 * 不用「首尾各补一份」的视觉方案（那种做法在吸附边界上要么多出一份跳动，要么得额外纠正
 * 内容偏移），而是让页码本身没有尽头：`pageCount = count * LOOP_MULTIPLIER`，
 * 初始定位在**正中间**且保证 `initialPage % count == selected`。
 * 显示与回写一律取模，于是「23 的下一条」天然就是 0，「0 的上一条」天然就是 59。
 *
 * ## 外部改值（点历史芯片 / applyPreset）为什么不会被拽回开头
 *
 * 老实现直接 `animateScrollToPage(selected)`——在无限页码下 `selected` 是 0..count-1 的
 * 小数字，等于每次都往「开头附近」跳，用户会看到滚轮被硬拽回去。
 * 现在改成**相对当前页求最近等价页**：先算出当前页的规范值与目标值的差，
 * 再把差值折到 (-count/2, count/2] 这个区间（超过半圈就反方向走更近），
 * 目标页 = `currentPage + delta`。于是无论停在哪一份副本上，都只滚最近的几格。
 *
 * 另外同步前会 `snapshotFlow { isScrollInProgress }.first { !it }` 等用户**滚停**再对齐：
 * 用户手指还在滑时插进去定位，会和惯性抢方向，表现为「滚到一半被拽回去」的抖动。
 * 等停稳后再对齐，既没有抖动，外部值也一定会生效。
 *
 * 还有一个自激风险：停稳回调会把取模后的值写回 [TimerState]，那又会触发本组件的同步逻辑。
 * 这里靠「规范值相等就直接 return」掐断——刚写回去的值必然等于当前页的规范值，
 * 因此不会形成「动画 → 回写 → 再动画」的死循环。
 */
@Composable
private fun WheelColumn(
    count: Int,
    selected: Int,
    enabled: Boolean,
    suffix: String,
    onSettled: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val totalPages = count * LOOP_MULTIPLIER
    // 中点本身就是 count 的整数倍，因此「中点 + 规范值」的规范值正好是 selected。
    val middlePage = count * (LOOP_MULTIPLIER / 2)

    // initialPage 用外部状态定位：从「计时」切走再切回来时 pager 会重建，
    // 靠它把滚轮恢复到用户之前选的位置（跨标签的状态在 TimerState 里）。
    val pagerState = rememberPagerState(
        initialPage = middlePage + selected.coerceIn(0, count - 1),
        pageCount = { totalPages }
    )

    // 外部改时长（点历史芯片）时，把滚轮也滚到最近的等价页，两边不会脱节。
    LaunchedEffect(selected) {
        // 用户正在滚就先等它停：中途抢定位会和惯性打架，看起来像被拽回去。
        snapshotFlow { pagerState.isScrollInProgress }.first { !it }
        val target = selected.coerceIn(0, count - 1)
        val current = pagerState.currentPage
        if (canonicalPage(current, count) == target) return@LaunchedEffect
        // 差值折到半圈以内：+50 分等价于 -10 分，走更近的那条路。
        var delta = target - canonicalPage(current, count)
        if (delta > count / 2) delta -= count
        if (delta < -count / 2) delta += count
        // 夹回合法页码：理论上从中间出发永远滑不到边界，
        // 但真到了边界附近再减 delta 会得到负数，Pager 对非法页码会抛异常，这里兜住。
        pagerState.animateScrollToPage((current + delta).coerceIn(0, totalPages - 1))
    }

    // 滚轮停稳后回写选中值（**取模成规范值**再回写）。
    // 用 isScrollInProgress 过滤而不是逐帧读 currentPage：一次甩动会经过很多页，
    // 只有停下来的那一页才是用户真正选的，逐帧回写会让剩余时间一路乱跳。
    LaunchedEffect(pagerState, onSettled) {
        snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }
            .collect { (scrolling, page) ->
                if (!scrolling) onSettled(canonicalPage(page, count))
            }
    }

    Box(
        modifier = modifier.height(WheelItemHeight * 5),
        contentAlignment = Alignment.Center
    ) {
        VerticalPager(
            state = pagerState,
            // 每页正好一行高、上下各留两行的内边距，
            // 于是任何一页都能停在正中间，而不会卡在顶部。
            pageSize = PageSize.Fixed(WheelItemHeight),
            contentPadding = PaddingValues(vertical = WheelItemHeight * 2),
            userScrollEnabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            // 与吸附位的距离（单位：行）。0 就是正中间那一行。
            val distance = abs(
                (pagerState.currentPage + pagerState.currentPageOffsetFraction) - page
            )
            // 离中心越远越小；1 行之内保持原样，之后线性衰减。
            val scale = (1f - (distance - 1f).coerceAtLeast(0f) * 0.14f).coerceIn(0.7f, 1f)

            // alpha 用 smoothstep 而不是直线衰减：
            // 直线在 distance = 1 处一阶导不为 0，数字刚离开中线就会「突然开始变淡」；
            // smoothstep 两端导数为 0，滑出时是**先几乎不变、再加速变浅**，观感更顺。
            val fade = ((distance - 1f).coerceIn(0f, 2f) / 2f).let { it * it * (3f - 2f * it) }
            val alpha = (1f - fade * 0.8f).coerceIn(0.2f, 1f)

            // 模糊半径同样随 distance 增长：刚离开中线（<1 行）完全不模糊，
            // 之后最多 8dp，配合变淡一起把上下两行「推」出视野。
            // API < 31 时 blur 是空操作（见 BlurSupported 注释）；
            // 半径为 0 时干脆不加这个 modifier——`blur(0.dp)` 仍会为此行建一个
            // 渲染层，而中心那一行本就不该有图层，省掉它对滚动流畅度更有利。
            val blurRadius = ((distance - 1f).coerceAtLeast(0f) * 4f).coerceIn(0f, 8f).dp
            val useBlur = BlurSupported && blurRadius > 0.dp

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    // 顺序说明：链里靠前的 modifier 在外层，所以这里是
                    // 「graphicsLayer 先作用（缩放 + 变淡）→ blur 再作用（把结果糊掉）」，
                    // 也就是**先变浅、再模糊**；distance 继续增大时 blur 拉到 8dp 上限、
                    // alpha 落到下限，整行彻底融进背景，看起来就是「最后消失」。
                    // 反过来的话模糊先发生、字还很清楚时才变淡，边缘会留下能看清的“鬼影”数字。
                    // 另外 blur 默认按行边界裁切（BlurredEdgeTreatment.Rectangle），
                    // 正好避免上下行糊到相邻行上去。
                    .then(if (useBlur) Modifier.blur(blurRadius) else Modifier)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clickable(enabled = enabled) {
                        // 点某一项就把它滚到中间（page 是绝对页码，无限滚动下直接可用）。
                        scope.launch { pagerState.animateScrollToPage(page) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    // 显示取模后的规范值：滚动没有尽头，文字必须落在 0..count-1。
                    text = "%02d".format(canonicalPage(page, count)),
                    // 正中间那一行字号最大、完全不模糊、不透明（distance < 0.5 时 blur = 0、alpha = 1）。
                    fontSize = if (distance < 0.5f) 30.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    // 中间行用正文色（深色模式下就是白色大字），上下行退到次要色。
                    color = if (distance < 0.5f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 原先这里还有一条「中线」：居中的 1dp 高 Box，用 outlineVariant 画一道淡横线
        // 强调「这一行才是选中的」。已按需求**整条删除**（不是改成透明色，而是不留节点、不留占位）。
        //
        // 删除后不影响读数对齐：三列数字的纵向位置本来就不是它撑起来的，而是由
        // VerticalPager 的 pageSize（每页一行高）+ 上下各两行的 contentPadding 决定的，
        // 吸附位恒定落在 Box 的垂直中心；那 1dp 只是叠在上面的一层装饰，删掉不会让内容位移。
        //
        // 也不额外补 Spacer：它当初是用 `align(Alignment.Center)` 叠在 Pager 上的，
        // 完全不参与 Box 的尺寸测量，因此删掉不产生任何空白。

        // 单位贴在滚轮右侧，不随滚动移动。
        Text(
            text = suffix,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 底部按钮的一种「动作」，用来把三种状态收敛成同一套渲染逻辑。
 *
 * @param throttleMs 两次点击的最小间隔（毫秒）。0 = 不节流。
 *   用于「暂停 / 继续」这类**状态切换**按钮：连点会让暂停→继续→暂停高速翻转，
 *   用户既看不清状态也容易误触。见 [TimerActionButton] 里节流实现的取舍说明。
 */
private data class TimerAction(
    val icon: ImageVector,
    val description: String,
    val throttleMs: Long = 0L,
    val onClick: () -> Unit
)

/**
 * 底部按钮排：单个动作时是主题色**胶囊**大按钮，两个动作时是并排的**圆形**按钮。
 *
 * 三种状态共用这一个渲染入口，尺寸 / 间距 / 图标大小 / 按压动画 / 触觉反馈全部一致，
 * 只有「按钮个数」决定形状与配色，避免三份代码各写各的导致手感不统一。
 *
 * ## 配色一律跟随主题色，且保持主次层级
 *
 * - 单按钮（开始，主操作）= `primary` / `onPrimary`，最重；
 * - 双按钮（停止 + 暂停/继续，都是次要操作）= `primaryContainer` / `onPrimaryContainer`。
 *
 * 为什么双按钮用 `primaryContainer` 而不是原来的 `surfaceContainerHigh`：
 * 后者是**中性灰**，不随主题色走，换主题色时按钮完全没反应。
 * `primaryContainer` 是由主题色派生的弱化语义色——既是同一个色相（一眼看出「这是主题色的按钮」），
 * 又比 `primary` 轻，主次层级天然成立；深浅色模式各有对应取值，也自动适配深色。
 * 两个按钮用同一个容器色，视觉权重保持一致（这是既定约束）。
 */
@Composable
private fun TimerButtonRow(
    actions: List<TimerAction>,
    enabled: Boolean
) {
    val single = actions.size == 1
    // 待机色与按下色都在这里算，两个按钮共用同一套，不会出现一个亮一个暗。
    val container = if (single) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (single) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimerButtonSpacing)
    ) {
        actions.forEach { action ->
            // ⚠️ 必须用 key 圈住每个按钮。
            //
            // 不加 key 时，从「单个开始按钮」切到「停止 + 暂停」的两个按钮，Compose 会把
            // 原来开始按钮的**组合槽位**复用给停止按钮：`TimerActionButton` 里的
            // `animateColorAsState` 因此把旧值 `primary`（亮色实底）当成起点，
            // 动画到 `primaryContainer`（浅色）——表现就是用户看到的
            // 「点开始后，停止按钮突然高亮又快速减淡」（暂停按钮是全新槽位，没有旧值，所以不闪）。
            // 加 key 后，单↔双切换时旧槽位销毁、新按钮全新创建，停止键一出现就是浅色。
            key(action.description) {
                TimerActionButton(
                    icon = action.icon,
                    contentDescription = action.description,
                    container = container,
                    tint = content,
                    shape = if (single) RoundedCornerShape(50) else CircleShape,
                    modifier = if (single) {
                        Modifier.width(TimerStartButtonWidth).height(TimerButtonSize)
                    } else {
                        Modifier.size(TimerButtonSize)
                    },
                    enabled = enabled,
                    throttleMs = action.throttleMs,
                    onClick = action.onClick
                )
            }
        }
    }
}

/**
 * 计时面板的按钮：按下缩小 + 容器色压暗 + 图标微暗 + 触觉反馈，禁用时整体降透明度。
 *
 * ## 按压反馈为什么做成「多通道」
 *
 * 只做缩放时，小尺寸按钮上的位移太小（64dp 缩到 0.9 只有约 6dp），用户反馈「看不出来」。
 * 因此几个通道一起给，让按压在任何视角下都有明确回应：
 * 1. **缩放 0.9**：配弹簧后不会一闪而过，按下与回弹都看得见；
 * 2. **容器色压暗**：按下时用 `lerp` 把容器色向主题里的 `scrim` 混 10%，
 *    深浅色模式下都是「更沉一点」。用 `scrim` 而不是写死 `Color.Black`，
 *    是为了不引入硬编码颜色（本项目的 `scrim` 在深浅两套里都是纯黑，正是「压暗」需要的那个色）；
 * 3. **图标微暗**（alpha 0.85）：给「陷下去」再补一层层次。
 *
 * ★ 为什么**没有**采用「叠一层 8% 高光」那种做法：
 * 高光要用 `tint`（单按钮是 `onPrimary`、双按钮是 `onPrimaryContainer`），
 * 而这两个色恰恰是各自容器的**对比色**——压在刚压暗的容器上会把压暗效果抵消掉，
 * 实测中和后几乎回到原色，反而削弱了反馈。改成「容器压暗 + 图标微暗」方向一致，
 * 叠加而不是相消，按压感更强。
 *
 * 缩放与颜色都走**弹簧**（`DampingRatioMediumBouncy` + `StiffnessMedium`）：
 * 比默认弹簧更有回弹感，松手时轻微过冲，符合「按下去弹回来」的直觉。
 *
 * ## 修饰符顺序很关键
 *
 * `graphicsLayer`（缩放层）**在外**、`clip(shape)` **在内**，不能颠倒：
 * `clip` 在外层时，缩放动画的每一帧都要在**已被缩放**的坐标系里重新计算裁剪路径，
 * 圆形 / 圆角的边缘会退化成多边形（这在微信、QQ 的圆形图标上实测踩过）。
 * 现在是 `graphicsLayer → clip → background`，裁剪在缩放层内侧，边缘始终是干净的正圆 / 圆角。
 *
 * `indication = null` 也是刻意的：这里用缩放代替水波纹，避免圆形按钮上出现一块方形涟漪。
 *
 * ## 点击节流（v1.1.69 需求 2）
 *
 * [throttleMs] > 0 时，两次点击间隔小于该值的调用被**静默丢弃**。
 *
 * 为什么用「时间戳比较」而不是 `var lastClick + delay`：
 * - delay 方案要挂一个协程，且在协程跑完前按钮仍可点——快速连点时多个协程并发，
 *   各自读到的 lastClick 可能相同，节流形同虚设；
 * - 时间戳是**同步**判断，点与生效之间没有任何异步窗口，行为可预测。
 *
 * 时间源用 `SystemClock.elapsedRealtime()`（单调时钟）而不是
 * `System.currentTimeMillis()`：后者会被系统对时改写，用户改一次时间就可能让
 * 节流窗口突然失效或失效很久。
 *
 * 节流期间**不改变禁用态**（不置灰、不变透明度）：按钮看起来完全正常，
 * 只是不响应。禁用态会让用户以为功能坏了，而这里只是"按得太快"。
 */
@Composable
private fun TimerActionButton(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    tint: Color,
    shape: Shape,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    throttleMs: Long = 0L
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // ── 按压动画（v1.1.69 需求：按下与弹起更明显）────────────────────────────
    //
    // 按下用**两种弹簧**：按下走偏硬的 `StiffnessMediumLow` + 低回弹（快速"陷下去"、
    // 不回弹过头），弹起走 `DampingRatioMediumBouncy` + `StiffnessMedium`（回落带一点
    // 过冲，读作"弹回来"）。同一套 spec 用在两个方向会让按下也拖泥带水，
    // 而"按下干脆、弹起有弹性"才是实体按键的手感。
    //
    // 缩放幅度从 0.88 加深到 **0.84**：需求要求"较为明显"，0.88 在 64dp 按钮上
    // 只有约 7.7dp 位移，肉眼偏弱；0.84 是约 10dp，配合下面的阴影变化足够"看得见"。
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.84f else 1f,
        animationSpec = if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "timerActionScale"
    )
    // 图标在按下时也同步缩小，与容器一起「陷下去」。
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "timerActionIconScale"
    )
    // 按下时的容器色：向主题 scrim 混 22%（原为 16%）——需求要"较为明显"的按下反馈。
    // 之所以不用「叠高光」：tint 是容器的对比色，会把压暗抵消掉（见上方注释）。
    val pressedContainer = lerp(container, MaterialTheme.colorScheme.scrim, 0.22f)
    // ⚠️ 容器色动画只服务于「按压压暗」这一个通道，**不能**跨状态过渡。
    //
    // 上一版这里没有限制，导致按钮从「开始(primary)」换形态成「停止(primaryContainer)」时，
    // 旧的亮色被当作动画起点，停止按钮先亮后淡。现在 `TimerButtonRow` 已经用
    // `key(action.description)` 让旧槽位销毁，这里是双保险：即使将来有人去掉 key，
    // 也不会再把状态切换当成一次「按压回弹」来播放。
    //
    // 用 `animateColorAsState` 而不是直接切值：新增的材质高光层依赖这个颜色的**中间帧**，
    // 硬切会让"压暗"这一步看起来是跳变而不是按下去的过程。
    val animatedContainer by animateColorAsState(
        targetValue = if (pressed) pressedContainer else container,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "timerActionContainer"
    )
    // 图标跟着一起微暗，和容器压暗同向叠加。
    val iconAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.82f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "timerActionIconAlpha"
    )

    // ── 高级材质质感（v1.1.69 需求）────────────────────────────────────────
    //
    // 三个通道叠出"实体按键"的观感，全部取自主题色、不新增颜色常量：
    // 1. **投影深度随按压变化**：待机时抬得高（8dp）、按下时贴下去（2dp）——
    //    这是"凸起→压平"最直观的物理隐喻，比单纯缩放更能读出层次；
    // 2. **顶部高光渐变**：容器上半部压一层极淡的白色竖直渐变（alpha 0.18 → 0），
    //    模拟光源从左上方打下；这是拟物按钮的通用做法，也是"材质"感的来源；
    // 3. **边缘描边**：一圈 alpha 0.12 的主题色描边，让按钮与背景之间有一道
    //    清晰的分界（纯色填充在浅色背景上容易"糊边"）。
    //
    // 高光用“与 tint 同源的白色”而不是主题色：不同主题色下白高光都成立，
    // 而用主题色提亮会在浅色主题色（如青色）上彻底看不见。
    // 阴影用 `MaterialTheme.colorScheme.shadow`（本项目两套主题里都是黑，正是投影需要的色）。
    val elevation by animateDpAsState(
        targetValue = if (pressed) 2.dp else 8.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "timerActionElevation"
    )
    val highlightAlpha = if (pressed) 0.10f else 0.18f

    // 上一次**生效**点击的时刻（单调时钟毫秒）。见 [TimerActionButton] 的节流说明。
    var lastEffectiveClick by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier
            // ── 材质层次：投影 → 缩放 → 裁剪 → 底色 → 高光 → 描边 ──────────────
            //
            // 顺序全部经过推敲（详见下方各处注释）：
            //   `shadow` 必须在 `graphicsLayer` **之前**：阴影要跟着缩放一起缩，
            //   放在缩放层内部才会跟按钮本体贴合；放在外面则按钮缩小、阴影不动，
            //   看起来像"按钮浮在阴影上方掉下来了"。
            //   `graphicsLayer`（缩放）在 `clip` 之前：见类注释，颠倒会让圆形边缘退化成多边形。
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 不可用时整体变淡，而不是只淡化图标（与旧的 RoundIconButton 行为一致）。
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(shape)
            .background(animatedContainer)
            // 顶部高光：一层从上到下的白色竖直渐变（alpha 由 highlightAlpha 收到 0）。
            // 它是**叠加**在底色之上的半透明层，不改变按钮的实际颜色，
            // 因此深浅色主题、任何主题色下都成立（见上方"高级材质质感"的说明）。
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = highlightAlpha),
                        Color.Transparent
                    )
                ),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = tint.copy(alpha = 0.12f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                // 节流：间隔不足时静默丢弃（见 KDoc：不置灰、不变外观）。
                // 触觉反馈也一并跳过——那次点击"没有发生"，不该有反馈，
                // 否则用户会觉得"按了但没反应"，比不反馈更困惑。
                val now = SystemClock.elapsedRealtime()
                if (throttleMs > 0L && now - lastEffectiveClick < throttleMs) {
                    return@clickable
                }
                lastEffectiveClick = now
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint.copy(alpha = tint.alpha * iconAlpha),
            modifier = Modifier
                .size(TimerButtonIconSize)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

/**
 * 「最近用过的时长」芯片排：横向滚动，点一下套用，长按删除。
 *
 * 一条记录都没有时（首次使用）显示占位文案，**不预置任何假数据**。
 */
@Composable
private fun RecentDurationChips(
    durations: List<Int>,
    selected: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
    onForget: (Int) -> Unit
) {
    if (durations.isEmpty()) {
        Text(
            text = "用过的时长会出现在这里",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        durations.forEach { total ->
            DurationChip(
                label = formatDuration(total.toLong()),
                selected = selected == total,
                enabled = enabled,
                onClick = { onPick(total) },
                onLongClick = { onForget(total) }
            )
        }
    }
}

/** 单个时长芯片：选中时背景更亮并加一圈主题色描边；长按删除（带一次长按触觉反馈）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DurationChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            // combinedClickable 同时覆盖长按与右键（鼠标右键会映射为长按），
            // 与 HomeScreen 里 TaskCircle 的既有写法一致。
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            // 芯片配色全部取自主题，跟着深色模式与主题色一起变。
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 秒 → `HH:MM:SS`。 */
private fun formatDuration(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(safe / 3600, (safe % 3600) / 60, safe % 60)
}

/**
 * 大号读数正下方那行「本次总时长」小字（参考图里的 `共9分钟`）。
 *
 * 传的是**滚轮选中的总时长**（[TimerState.selectedSeconds]），不是剩余时间——
 * 这行的语义是「这一轮设了多久」，与已经走了多少无关，所以计时中 / 暂停中它都不会变。
 *
 * ## 文案规则（三条分支，互斥且穷尽）
 *
 * 1. **不足 1 分钟** → 显示秒，形如 `共45秒`。
 *    为什么不写 `共0.8分钟`：需求明确不许出现 `共0分钟`，而任何「保留一位小数」的
 *    分钟写法在 < 30 秒时都会四舍五入 / 截断成 `0.0`，同样难看；干脆整体退到秒，
 *    读数与滚轮上的「秒」一列也更好对上。上限 59 秒，所以这个分支不会出现 `共60秒`。
 * 2. **整分钟** → 显示整数，形如 `共9分钟`（540 秒 / 60 = 9，无余数）。
 * 3. **其余** → 保留一位小数，形如 `共1.5分钟`（90 秒）。
 *    小数**用整数运算四舍五入到一位**（放大 10 倍再除以 60，加半个单位）：
 *    `61 秒` 真实是 1.016 分，四舍五入成 `1.0`，若照直拼就会输出 `共1.0分钟`——
 *    所以这里再判一次「取整后是不是整十分位」，是就退化成整数写法，输出 `共1分钟`。
 *    同理 `119 秒` → `共2分钟`。这样永远不会出现 `共x.0分钟` 这种别扭写法。
 *
 * 分支 2 与分支 3 不会打架：分支 2 只是让精确的整分钟不走四舍五入，
 * 结果与走分支 3 再退化完全一致（如 540 秒两边都是 `共9分钟`）。
 *
 * 时长上限是 23:59:59，三位数分钟也没问题，不需要额外补位。
 *
 * 刻意**不**用 `String.format` 的 `%.1f`：那会走系统 Locale，个别地区的小数点是逗号，
 * 同一份文案在不同语言设置下长得不一样。这里改成整数运算拼字符串，输出恒定。
 */
private fun formatTotalDurationLabel(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    if (safe < 60) return "共${safe}秒"
    if (safe % 60 == 0) return "共${safe / 60}分钟"
    // 放大 10 倍后四舍五入（+30 即半个 60），避免浮点误差。
    val tenths = (safe * 10 + 30) / 60
    // 四舍五入恰好落回整分钟（如 61 秒 → 1.0）时显示整数，别输出「共1.0分钟」。
    if (tenths % 10 == 0) return "共${tenths / 10}分钟"
    return "共${tenths / 10}.${tenths % 10}分钟"
}
