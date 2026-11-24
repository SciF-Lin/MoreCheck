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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
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
import com.morecheck.di.appContainer
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
    val scope = rememberCoroutineScope()
    val taskRepository = remember(context) { context.appContainer.taskRepository }

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

    // ── 进度条专用：剩余**毫秒**（本轮需求 24）────────────────────────────────
    //
    // 需求原文：「计时器的进度条改为均匀减少」。
    //
    // ## 为什么不能直接用上面的 `remaining`（秒）
    //
    // `remainingSeconds()` 是**向上取整**的展示值：剩余 7.2 秒时它返回 8，
    // 于是进度条在"刚开始的 0.8 秒里"读到的都是 `8 / 8 = 1.0`——**整条不动**；
    // 之后每隔一整秒才掉一格。观感就是"进度条一顿一顿地跳"，与"均匀减少"正好相反。
    //
    // 这里改用毫秒读数（`remainingMillis()`），200ms 的刷新间隔下每拍都在动，
    // 视觉上是一条连续的斜坡。
    //
    // ⚠️ 它**只服务进度条**，不参与任何判定：上面的 `remaining`（秒）仍是
    // 按钮三态与滚轮可用性的唯一事实源（那套判定动过会牵出历史 bug），
    // 两个读数在同一帧取自同一份快照，不会互相矛盾。
    //
    // key 与 `remaining` 完全一致（`state.remainingMillis()` 同样是快照状态的派生值，
    // 放进 key 就等于订阅），因此两者永远同步刷新。
    val remainingMillis = remember(
        refreshTick,
        state.isRunning,
        state.selectedSeconds(),
        state.remainingSeconds()
    ) {
        state.remainingMillis()
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
        // 走完一轮落一条计时会话：记录页「计时记录」卡与打卡时间线同页可查。
        val startedAt = state.startedAtWall
        val durationMs = state.selectedSeconds().toLong() * 1000L
        if (startedAt > 0L && durationMs > 0L) {
            val endedAt = System.currentTimeMillis()
            scope.launch {
                taskRepository.addTimerSession(startedAt, endedAt, durationMs)
            }
        }
    }

    // ── 通知栏计时器（需求 15）──────────────────────────────────────────────
    //
    // 「启动计时后通知栏显示计时器，切换界面后台保持计时」。
    //
    // ## 为什么计时本身不需要任何新代码就能"后台保持"
    //
    // [TimerState] 的剩余时间是从**结束时刻**（单调时钟）现算的，
    // 不依赖任何协程 / 组合存活——切标签、退后台、甚至锁屏，倒计时都不会算错
    // （该类 KDoc 有完整说明）。本 effect 补的只是**通知栏的状态展示**。
    //
    // ## 为什么只监听 `state.isRunning` 的跳变，而不是每秒重发通知
    //
    // 通知里用了 `setUsesChronometer(true)` + `setWhen(结束时刻)`：
    // 系统自己按结束时刻跳秒（超级岛 / 灵动岛 / 通知栏倒计时都是同一条链路），
    // **调用方发一次就够了**。每秒重发既浪费、又会在部分 ROM 上重置计时器动画。
    //
    // key 用 `state.isRunning` + `state.endAtMillisValue()`：暂停（isRunning → false）
    // 时撤通知；开始 / 继续（isRunning → true）时按当前结束时刻发通知。
    // `endAtMillisValue()` 放进 key 是为了「暂停后继续」这种 isRunning 不变方向的
    // 跳变也能触发（暂停时 isRunning 变 false、继续时变 true，其实已覆盖；
    // 放 key 只是让"同一轮内 start 再次被调用"这类边界也重新发一次，幂等无害）。
    val notificationHelper = remember(context) { context.appContainer.notificationHelper }
    LaunchedEffect(state.isRunning, state.endAtMillisValue()) {
        if (state.isRunning) {
            val totalMillis = state.selectedSeconds().toLong() * 1000L
            notificationHelper.showTimerNotification(
                endAtMillis = state.endAtMillisValue(),
                totalMillis = totalMillis
            )
        } else {
            // 暂停 / 停止 / 到点：撤掉常驻条目。暂停后"还剩多少"由页面自己显示
            // （此时剩余时间已冻结，通知栏的跳秒计时器反而会显示错误信息）。
            notificationHelper.cancelTimerNotification()
        }
    }
    // 本页离开组合（切到「打卡」面板 / 退出应用）时兜底撤通知：
    // 若计时仍在跑，Disposal 撤掉的是通知栏展示，倒计时本身不受影响——
    // 回到计时面板时上面的 effect 会按当前状态重新挂通知。
    // 若计时已停，这里只是无害的幂等取消。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { notificationHelper.cancelTimerNotification() }
    }

    // 铃声响到一半时用户又操作了计时器：在下面的按钮处理里显式 ringer.stop()，
    // 而不是靠 isRunning 的变化去猜——到点那一刻 isRunning 会从 true 变回 false，
    // 用「状态跳变」推导很容易把刚播出来的到点铃声立刻掐掉。显式调用最不容易出错。
    // （离开页面 / 切标签由 rememberTimerRinger 的 DisposableEffect 兜底。）

    // 正在计时时不让芯片显示为选中：此时改时长没有意义（改了也不会生效）。
    val selectedChip = if (state.isRunning) -1 else selectedSeconds

    // 「开始」按钮的一次性冷却门到期时刻（v1.1.72，见下方 TimerAction 里的两段式说明）。
    //
    // 为什么用 `mutableLongStateOf` 而不是 `mutableStateOf`：
    // 它存的是原始 Long，用 `mutableStateOf` 会被装箱成 `MutableState<Long>`，
    // 而 Long 有专用的 `mutableLongStateOf`（Compose 对基本类型有对应的
    // 无装箱 State 实现，本项目 `TimerActionButton` 里的 `lastEffectiveClick` 用的就是它）。
    //
    // 为什么**不**用 rememberSaveable：这只是个"这一秒内别再点"的瞬时门，
    // 进程重建后理应失效（重建时用户也不在连续点击），存起来反而会让
    // 重建后的第一次点击被一个陈旧的时刻挡住。
    var startGateUntil by remember { mutableLongStateOf(0L) }

    // ── 垂直居中容器（本轮需求 1：整体「偏上」的修复）────────────────────────────
    //
    // 用户反馈原文：「计时页按钮和界面整体偏上了」。
    //
    // ## 根因不是某个间距不够大，而是布局**没有把剩余空间用掉**
    //
    // 原实现是 `Column + verticalScroll`：滚动容器的内容**永远从顶边开始摆**，
    // 内容高度（滚轮槽 260 + 进度条 + 芯片行 + 按钮 64 ≈ 520dp）小于可视高度时，
    // 多出来的那三四百 dp 全部堆在**最下面**——实测截图里按钮下方有将近三分之一个屏的
    // 死白。历史需求「整体向下移动」当时用顶部加 48dp 留白去凑，只是把死白从
    // "下大上小"改成"上下都有但都不舒服"，本质没解决。
    //
    // ## 做法：内容不足一屏时撑满视口并按中线摆，超出时照常滚动
    //
    // `BoxWithConstraints` 拿到可视高度 → 内层 Column 用 `heightIn(min = 视口高)`
    // 把"至少占满一屏"变成**测量约束**，再交给 `verticalArrangement = Center`
    // 在这段最小高度内居中。于是：
    //   · 内容 < 一屏（滚轮态 / 读数态都是这种）→ 整块居中，上下留白均分；
    //   · 内容 > 一屏（极小屏 + 大字号）→ min 约束失效，Column 自然长高，
    //     `verticalScroll` 依然能滚到底，不会像"硬居中"那样把顶/底切掉。
    //
    // ⚠️ `heightIn` 必须落在 `verticalScroll` **之内**（即加在滚动内容上）：
    // 加在外面等于把视口自身撑高，滚动容器就没有可滚范围了。
    //
    // ⚠️ 这里不再有任何"顶部固定留白"常量：[TimerPanelTopInset] 已随本次重构删除，
    // 居中本身就把这块空间分配好了；再叠一个固定留白会让整块内容偏下而不是居中。
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 视口高度：只在这一层有效，用于给滚动内容一个最小高度（见上面的说明）。
        val viewportHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 水平留白留在滚动容器**外面**：滚动位置不因侧边留白左右偏移。
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                // 至少占满一屏 → `Arrangement.Center` 才有可分配的剩余空间。
                .heightIn(min = viewportHeight)
                // 垂直内边距落在**滚动内容内部**（需求 bug3：底栏上方有一截内容被切掉又滚不到）：
                // 内容超高时，滚到底能看到最后一行且下面仍留呼吸空间。
                // 上下各 24dp：居中时它俩对称，不会让中线偏移。
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        // 原先这里有一个「计时」小标题，已按要求删除（标题栏上那个「计时」标签是面板切换用的，保留）。
        // 顶部那截固定留白（原 [TimerPanelTopInset] = 48dp）同样删除：
        // 现在整块内容由外层 `Arrangement.Center` 居中，不再靠"顶着加空白"往下推。

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
        // ## 为什么整块套一层「固定高度 + 内容贴底」的 Box（v1.1.70）
        //
        // 需求原文：「按钮位置不动，时间向下移」。根因是滚轮态与读数态**高度不同**：
        // 滚轮是 [TimerPickerSlotHeight]（= WheelItemHeight * 5 = 260dp），而读数只有
        // 三行小字（48sp 大字 + 13sp + bodySmall，约 100dp）。两者由 AnimatedContent
        // 二选一显示时，Column 量到的高度会从 260dp 掉到约 100dp，
        // 于是**下游所有内容整体上移约 160dp**——用户看到的正是「一按开始，
        // 下面的时长芯片和按钮行猛地往上一跳」。
        //
        // 修法：给这块区域钉一个既定的高度（[TimerPickerSlotHeight]），与当前显示的是
        // 滚轮还是读数**无关**。槽高恒定 → 后面每一个 Spacer / 芯片 / 按钮行的 y 坐标
        // 恒定 → 按钮行在「未开始 / 计时中 / 暂停中」三态之间一个像素都不动。
        //
        // ## 为什么用 Box + contentAlignment = Alignment.BottomCenter
        // 而不是 Column + Spacer(Modifier.weight(1f))：
        // - 槽内**只有一个子项**（AnimatedContent 本身，滚轮/读数在它内部二选一），
        //   不是「读数 + 一段可伸缩空白」两个兄弟节点。Box 的 contentAlignment 直接
        //   把唯一子项摆到底部，一层的意图比「塞一个权重 Spacer 再让它把兄弟顶下去」更直白；
        // - ContentScale 上也更稳：weight 方案在槽高 < 子项测量高时会先量出负的剩余空间，
        //   得依赖 Column 的兜底行为；Box 的测量规则（强制子项在给定约束内测量、
        //   再按对齐摆放）没有这个拐点，这里是安全但更啰嗦的写法，故不用；
        // - 贴底正是需求要的「时间向下移」：读数不再浮在滚轮的**垂直中心**
        //   （那样即使槽高固定，读数也会比滚轮中心高约 80dp，切换时读数仍会跳），
        //   而是直接坐到槽底，紧挨着下面那 20dp Spacer 与时长芯片，视觉上最靠近按钮。
        //
        // AnimatedContent 的过渡（200ms fade + scale 0.92 淡入）与 label 原样保留：
        // 它现在是在固定尺寸的槽**内部**做过渡，尺寸不再参与测量，动画只影响槽内绘制。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TimerPickerSlotHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            // ⚠️ AnimatedContent 自己也要「尺寸恒定 + 贴底」，只靠外层 Box 不够：
            // 外层 Box 保证的是**槽**的高度（下游 y 不动），但 AnimatedContent 默认会按
            // **内容**尺寸做 SizeTransform 动画。若放任它自适应，滚轮态量成 260dp、
            // 读数态量成约 100dp，这 160dp 的尺寸差会被当成动画播放——读数的顶边从槽顶
            // 一路滑到槽底（终点虽对，但那 200ms「滑下来」是多余动作，返回时还会反过来滑上去）。
            // 加上 fillMaxSize() 后容器恒为槽高；再配合下面读数分支那层同样 fillMaxSize 的 Box，
            // **两个分支的高度都等于槽高**，尺寸差为 0，SizeTransform 自然变成空操作，
            // 过渡就只剩原有的 fadeIn + scaleIn（过渡参数本身一个字没改）。
            AnimatedContent(
                targetState = started,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.92f)) togetherWith
                        fadeOut(tween(150))
                },
                // 与外层 Box 用同一个对齐口径：滚轮 / 读数都贴槽底（滚轮本就与槽等高，对齐对它无影响）。
                contentAlignment = Alignment.BottomCenter,
                label = "timer-picker-vs-readout"
            ) { showReadout ->
                if (showReadout) {
                    // ---- 已开始：只显示读数 ----
                    //
                    // ⚠️ 这里再套一层 fillMaxSize + BottomCenter 的 Box，是为了让「读数贴底」
                    // **不依赖** AnimatedContent 内部如何测量子项：
                    // 若它把 minHeight（= 槽高 260dp）透传给子项，读数那个 Column 会被撑到 260dp 高，
                    // 而 Column 的内容是自上而下摆的——大字就会跑到槽顶，看起来像没生效；
                    // 若它不透传（子项自然高约 100dp），则靠外层对齐贴底。
                    // 这层 Box 在两种情况下都成立：它自己撑满可用高度，再把 Column 摆到底边。
                    // 于是「贴底」这件事只由本文件决定，不赌框架的约束透传细节。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // ── 读数整体上移（本轮需求 25）────────────────────────
                            //
                            // 需求原文：「计时时候显示的时间往上移动一点」。
                            //
                            // ⚠️ 为什么用 `offset` 而不是改外层 Box 的 padding /
                            // 高度：本页的「按钮行位置恒定」是靠这个 260dp 的固定槽
                            // 实现的（见 [TimerPickerSlotHeight] 与 v1.1.70 的修复记录），
                            // 动槽的尺寸或内边距会让下游所有内容一起位移，
                            // 按钮行在三态之间就不再"一个像素都不动"了。
                            // `offset` 只搬动**读数自身**，滚轮态与按钮行都不受影响。
                            //
                            // ⚠️ `offset` 不参与测量：被偏移的区域仍按原位占位，
                            // 因此上移后不会把下面的进度条 / 芯片行顶开
                            // （它们的 y 坐标依然恒定，这是选 offset 而不是改布局的全部理由）。
                            .offset(y = -TimerReadoutLift),
                        contentAlignment = Alignment.BottomCenter
                    ) {
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
        } // ← 固定高度槽 Box 结束；槽高恒定，所以下面所有内容的 y 坐标都不受滚轮/读数切换影响。

        // ── 进度条（v1.1.72 需求：随时间减少而减少）────────────────────────────
        //
        // 需求原文：「计时增加一个进度条，随时间减少而减少」。
        //
        // ## 位置：为什么放在滚轮/读数槽与芯片行之间
        //
        // 它是"这一轮计时整体的消耗进度"，与滚轮/读数（当前时间）、芯片（历史时长）、
        // 按钮（操作）都不同级，所以单独占一行最不会被误读。放在这里还顺带成了
        // 读数区与操作区的**视觉分隔**，比再加一个 Spacer 更有信息量。
        //
        // ⚠️ 高度必须是**常量**（见 [TimerProgressBarHeight]）：
        // 本页 v1.1.70/71 修过两轮"按钮行跳动"（滚轮↔读数高度差、芯片行空/非空高度差），
        // 结论是把每个可变区域都钉死高度。进度条若按内容或按有无自适应，
        // 等于给那条修复留一个后门——所以这里也是硬高度，宁可留白也不让它变形。
        // 与滚轮之间留 16dp（本轮需求 1）：原来进度条紧贴滚轮最下一行（被模糊的「02 10 02」），
        // 视觉上像是被裁掉的一行数字的底边。
        Spacer(Modifier.height(16.dp))

        TimerProgressBar(
            // 进度 = 剩余 / 本轮总时长。分母用 `selectedSeconds()` 而不是 remaining 的初值：
            // `selectedSeconds()` 在计时开始后被滚轮锁定，语义正是"这一轮设了多久"，
            // 而 remaining 是会被 reset() 改写的运行时量，用它当分母会在停止/重置时突变。
            //
            // ⚠️ 本轮（需求 24）改为喂**毫秒**（`remainingMillis`）而不是秒：
            // 秒读数是向上取整的展示值，会让进度条在"刚开始的将近 1 秒里"整条不动、
            // 之后每秒跳一格——那正是"不均匀"的根因。毫秒读数下 200ms 的刷新
            // 间隔每拍都在动，配合下面的 200ms 插值就是一条连续斜坡。
            total = state.selectedSeconds() * 1000L,
            remaining = remainingMillis,
            started = started
        )

        Spacer(Modifier.height(20.dp))

        // 最近用过的时长：横向滚动的一排，点一下就把滚轮与剩余时间整体换成该值，
        // 长按删除这一条。没有任何记录时显示占位文案（不塞假数据）。
        //
        // ⚠️ v1.1.71：外面再钉一层**固定高度**的槽（[RecentChipsSlotHeight]），
        // 理由见该常量的注释——这是「按钮位置不动」的第二个（也是最后一个）位移源。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RecentChipsSlotHeight),
            contentAlignment = Alignment.Center
        ) {
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
        }

        // ── 芯片行与按钮行之间的间距（本轮需求 25：按钮往下移动一点）──────────
        //
        // 需求原文：「计时器的按钮往下移动一点」。取 **[TimerButtonsNudge]**
        // 而不是直接写 28dp 的新值：原 28dp 是一个有出处的数（它与上下两个区域的
        // 留白成比例），本轮要的是"在它基础上再往下挪一点"，因此显式写成
        // `28.dp + TimerButtonsNudge`——原值与新增量都留在代码里，
        // 将来想收回这次微调时只删一项即可。
        Spacer(Modifier.height(28.dp + TimerButtonsNudge))

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
                TimerAction(Icons.Rounded.Stop, "停止") {
                    ringer.stop()
                    state.reset()
                },
                // 暂停：v1.1.69 需求 2——两次点击不小于 0.3s。
                TimerAction(
                    icon = Icons.Rounded.Pause,
                    description = "暂停",
                    throttleMs = PAUSE_RESUME_THROTTLE_MS
                ) {
                    ringer.stop()
                    state.pause()
                },
            )

            isPaused -> listOf(
                // 停止：语义同上。暂停中的「停止」不记历史（这一轮开始时就已记过）。
                TimerAction(Icons.Rounded.Stop, "停止") {
                    ringer.stop()
                    state.reset()
                },
                // 继续：从冻结的剩余时间接着走，不重记历史（时长不是这次新选的）。
                // 与暂停共用同一节流间隔——它们是同一枚按钮的两种形态，
                // 用户在「暂停↔继续」之间快速连点时，限速的正是这个组合。
                TimerAction(
                    icon = Icons.Rounded.PlayArrow,
                    description = "继续",
                    throttleMs = PAUSE_RESUME_THROTTLE_MS
                ) {
                    ringer.stop()
                    state.start()
                },
            )

            else -> listOf(
                TimerAction(
                    icon = Icons.Rounded.PlayArrow,
                    description = "开始",
                    // ── v1.1.72 需求：两段式点击限制 ──────────────────────────
                    //
                    // 需求原文：「倒计时的开始按钮点击后一秒钟内无法点击，
                    // 后续两次点击间有0.5s动画，动画期间无法点击」。
                    //
                    // 拆成两段，各由不同机制实现：
                    //
                    // **第一段（一次性 1 秒门）**：按下开始的那一刻，把
                    // `startGateUntil` 设为「现在 + 1 秒」。这 1 秒内再点一律无效
                    // （`TimerActionButton` 里 `gateUntilMs` 判断）。
                    // 用途：刚按下就开始计时，紧接着的连点若生效会让计时器
                    // 反复 start()——虽然 `TimerState.start()` 本身幂等，
                    // 但"上一轮铃声还没停就被新的开始打断"这类边界会变得难料。
                    // 1 秒足够让用户看到"计时已经开始了"（读数切换 + 进度条起动）。
                    //
                    // **第二段（常态 0.5 秒节流）**：1 秒门过去之后，靠
                    // `throttleMs = 500` 管此后每次点击的最小间隔。
                    // 取 500 而不是 300（暂停/继续用的那个）：需求明确要求
                    // "两次点击间有 0.5s 动画"，而本按钮的按压动画
                    // （`animateFloatAsState` 双弹簧：按下 StiffnessMediumLow、
                    //  弹起 MediumBouncy + StiffnessMedium）完整走一遍约 400~500ms。
                    // 节流窗口设为 500ms，意味着**上一次点击的动画还没放完，
                    // 下一次点击就会被丢弃**——这正是"动画期间无法点击"的落地方式。
                    //
                    // ⚠️ 为什么不改用"等动画结束再放开"的显式机制
                    // （比如 `Animatable` + `animateTo` 后置标志）：那要把按压动画的
                    // 控制器从 `animateFloatAsState` 换成 `Animatable` 并上提到本页，
                    // 而 `animateFloatAsState` 是**声明式**的（目标值驱动，不可挂起等待），
                    // 两者混用会让"谁在驱动动画"变得含糊。用节流窗口对齐动画时长，
                    // 不改动画实现就达到同样效果，代价只是一个魔法数
                    // （已在常量里写清它必须与动画时长匹配）。
                    throttleMs = START_THROTTLE_MS,
                    gateUntilMs = startGateUntil,
                    onClick = {
                        // 记历史的时机 = 真正开始计时的那一刻。
                        // 理由：芯片这一排的语义是「我实际用过的时长」——
                        //   · 点芯片不记：那只是把滚轮摆好，可能接着又改，属于选择过程而非使用；
                        //   · 滚轮每动一下不记：拖动会扫过十几个值，记下来的全是噪音；
                        //   · 暂停后继续也不记：同一次倒计时，时长没有重新选择过。
                        // 只有「从初始 / 重置 / 走完的状态按下开始」才代表用户认可了这个时长。
                        // 上一轮可能还在响铃（短时间连按开始），先停掉再开新的。
                        ringer.stop()
                        viewModel.rememberTimerDuration(state.selectedSeconds())
                        state.start()
                        // 关门：从现在起 1 秒内不接受再次点击（见上方两段式说明）。
                        startGateUntil = SystemClock.elapsedRealtime() + START_GATE_MS
                    }
                ),
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
     * 当前计时**结束时刻**（`SystemClock.elapsedRealtime()` 基准）；未计时返回 [NO_END]。
     *
     * 只读暴露：需求 15 要把"结束时刻"喂给通知栏的系统计时器
     * （`setWhen` + `setUsesChronometer`，见 `NotificationHelper.showTimerNotification`）。
     * 刻意**不**暴露可写入口——结束时刻只能由 [start] / [pause] / [reset] / [finish] 推导，
     * 外部改它等于绕过计时器自己的状态机。
     */
    fun endAtMillisValue(): Long = endAtMillis

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

    /**
     * 当前剩余**毫秒**的公开读法（需求 24：进度条要均匀减少）。
     *
     * ## 为什么进度条要用毫秒，而读数仍用秒
     *
     * [remainingSeconds] 是**向上取整**的展示值（刚点开始时显示的必须是完整时长），
     * 拿它当进度值会出现「刚开始的将近 1 秒里整条不动、之后每秒跳一格」——
     * 这正是需求 24 要修掉的"不均匀"。毫秒读数在 200ms 的刷新间隔下每拍都在变，
     * 视觉上是一条连续的斜坡。
     *
     * ⚠️ 它**只服务进度条**，不参与按钮三态 / 滚轮可用性等任何判定
     * （那些判定的口径牵动着历史修过的一批 bug，见 [remainingSeconds] 的注释）。
     * 两个读数在同一帧取自同一份快照，不会出现"进度条走完了按钮还在计时中"。
     */
    fun remainingMillis(): Long = currentRemainingMillis()

    /** 本轮倒计时的墙钟起点（epoch 毫秒）；0 = 还没开始过。新一轮才写，暂停继续不覆盖。 */
    var startedAtWall: Long = 0L
        private set

    fun start() {
        // 上一轮已经走完（剩余为 0）时，按当前滚轮选择重新开始，
        // 这样「到点之后再点一次播放」符合直觉，而不是按了没反应。
        if (frozenRemaining <= 0L) frozenRemaining = selectedSeconds().toLong() * 1000L
        // 直接使用冻结的**毫秒**剩余量，不再「折算成秒再乘 1000」——少一次乘除就少一次截断。
        val leftMillis = frozenRemaining
        if (leftMillis <= 0L) return
        // 剩余 == 滚轮全时长 才算新一轮；暂停继续时剩余是零头，不会覆盖同会话起点。
        if (leftMillis == selectedSeconds().toLong() * 1000L) {
            startedAtWall = System.currentTimeMillis()
        }
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
 * 「滚轮 / 读数」二选一区域的**固定高度**（需求：按钮行位置在开始前后绝不能动，v1.1.70）。
 *
 * ## 这个值是怎么算出来的
 *
 * 只由一个分量决定：滚轮列 [WheelColumn] 自身的高度。
 * 它的定义式是 `modifier.height(WheelItemHeight * 5)`，而 [WheelItemHeight] = 52dp，
 * 于是 **52dp × 5 = 260dp** —— 槽就取这个高度。
 *
 * 那个 5 行不是随手取的，它是滚轮正常显示所需的**完整**行数：
 * - `pageSize = PageSize.Fixed(WheelItemHeight)`：每页正好一行高；
 * - `contentPadding = PaddingValues(vertical = WheelItemHeight * 2)`：上下各留两行，
 *   让任何一页都能吸附在正中间，而不会卡在顶部；
 * - 于是可见行 = 中间 1 行 + 上下各 2 行 = **5 行**，正好被 260dp 装下。
 *
 * 因此槽取 260dp 时滚轮**一行都不会被裁**；这也正是「槽高必须按滚轮态定、不能按读数态定」的原因：
 * 读数只有三行小字（约 100dp），按它定高会把滚轮裁掉。
 *
 * ⚠️ 这里刻意与滚轮写成**同一个表达式** `WheelItemHeight * 5`，而不是写死 `260.dp`：
 * 我们要的不变量就是「槽高 == 滚轮高」。写成同一个表达式，这个不变量就是**结构性**的——
 * 将来谁调了 [WheelItemHeight]，槽会跟着一起变，不会出现「滚轮高了、槽没跟上、下面被裁一行」
 * 或者「槽矮了、读数被裁」。反过来若写成 260.dp，就得靠人去维持两者相等，迟早出错。
 * （两者此刻数值相等，正是不裁滚轮、读数贴底、按钮行不动三者同时成立的前提。）
 */
private val TimerPickerSlotHeight = WheelItemHeight * 5

/**
 * 「最近用过的时长」芯片行的**固定高度**（v1.1.71）。
 *
 * ## 为什么这一行也需要钉高度
 *
 * 需求是「开始计时后按钮位置不动」。滚轮 / 读数那一块已经用
 * [TimerPickerSlotHeight] 钉住了，但**它下面还有第二个位移源**：
 * [RecentDurationChips] 在两条分支下高度不同——
 * - **没有历史记录时**（首次使用）：一行占位文案 `用过的时长会出现在这里`（labelMedium，约 20dp）；
 * - **有历史记录时**：一排 [DurationChip]，芯片自身内容高 = 文字行高 + 上下各 9dp 内边距 ≈ 38dp。
 *
 * 两者相差约 18dp。而**用户第一次按下「开始」时会写入历史**
 * （`rememberTimerDuration`，见下方开始按钮的 onClick），于是同一帧里
 * 「占位文案」换成「芯片」→ 这一行变高 18dp → 下面的按钮行**仍然会往下跳 18dp**。
 * 只钉滚轮槽不钉这里，需求只完成了一半。
 *
 * ## 取 40dp 的依据
 *
 * 槽高必须**不小于**两条分支里较高的那个（芯片行约 38dp），否则芯片会被裁。
 * 取 40dp：比 38dp 留 2dp 余量（不同字体缩放 / 系统字号设置下芯片会略高，
 * 留一点缓冲避免贴边裁切），同时只比占位文案高 20dp——
 * 这 20dp 在视觉上就是「这一行本来就是给芯片预留的位置」，占位文案居中显示，
 * 看起来是刻意的留白而不是空荡。
 *
 * 用 `height()` 固定 + `contentAlignment = Center`（而非 `heightIn(min =)`）：
 * `heightIn` 只保证"不矮于"，芯片行比它高时仍会撑高、位移照样发生。
 * 要的是**恒定**，所以必须是硬高度（与本文件 [ACTION_BUTTON_WIDTH] 同一思路）。
 *
 * 代价：极端大的系统字体（> 1.3 倍）下芯片可能略超出 40dp 而被裁掉几像素。
 * 这个取舍是刻意的——需求要的是"位置绝对不动"，而位置不动的前提是高度恒定；
 * 若改成 `heightIn(min = 40.dp)` 换取不裁切，就会把位移源放回来。
 */
private val RecentChipsSlotHeight = 40.dp

/**
 * 芯片行与按钮行之间的间距（原需求 25 的「按钮往下移动一点」已在此轮重构中并入居中布局）。
 *
 * 历史值 `28.dp + TimerButtonsNudge(12dp) = 40dp`：那 12dp 是当年为了"往下挪一点"补的，
 * 但当时整块内容贴顶，往下挪只是把死白均摊了一下，并没有解决"整体偏上"。
 * 现在整块内容由外层 `Arrangement.Center` 垂直居中，**任何单侧补偿都会破坏对称**，
 * 因此这里收回到一个干净的 32dp（视觉上仍是三个区块里最大的一段，主操作依旧分离清楚），
 * 并删掉 [TimerButtonsNudge] 这个常量。
 */
private val TimerButtonsNudge = 12.dp

/**
 * 读数（大号剩余时间）在固定槽内的**额外上移量**（本轮需求 25：
 * 计时时候显示的时间往上移动一点）。
 *
 * ## 为什么不是给槽加 padding，而是给内容加 offset
 *
 * 本页 v1.1.70 起把「滚轮 / 读数」那一块钉成了固定高度的槽
 * （[TimerPickerSlotHeight]），其核心不变量是"下游所有内容的 y 坐标恒定"——
 * 按钮行在三态之间一个像素都不动，正是靠这个槽实现的。
 * 若改槽的内边距 / 高度，等于动那个不变量的地基，按钮行会跟着跳。
 *
 * 所以这里只对**读数自身**做一次 `offset(y = -...)`：
 * 滚轮态不受影响（它本来就与槽等高、贴槽底），读数态整体上移 [TimerReadoutLift]，
 * 按钮行位置一字不变。
 *
 * 取 **10dp**：与按钮的 12dp 同量级但略小——需求把两件事并列说出
 * （"按钮往下一点、时间往上一点"），视觉上应该是对向的一对位移，
 * 取同一数值会让两处的"一点"显得一样大，反而看不出方向。
 */
private val TimerReadoutLift = 10.dp

/**
 * 计时页进度条的**固定高度**（v1.1.72；本轮需求 1 由 8dp 收细到 6dp）。
 *
 * 8dp 时它紧贴滚轮底边、通栏满宽，视觉上更像一条「分割线 / 色条」而不是进度条；
 * 本轮同时做了两件事让它读起来是"计量"：
 * - 高度 8dp → **6dp**（细一点，不与按钮抢重量）；
 * - 左右各内缩 [TimerProgressBarInset]（不再与滚轮等宽贴边），并在上方留 16dp。
 *
 * ⚠️ 仍是**常量**而不是按内容自适应：本页为"按钮行不跳"钉过多个区域的高度
 * （`TimerPickerSlotHeight` / `RecentChipsSlotHeight`），进度条是最后一个
 * 可变区域，若让它按状态变形，等于给那套修复留后门。
 */
private val TimerProgressBarHeight = 6.dp

/**
 * 进度条的左右内缩量（本轮需求 1）。
 *
 * 取 **36dp**：滚轮是通栏的（每列各占约 120dp），进度条若也通栏贴边，
 * 两端会正好压在「时 / 秒」两列数字的外沿上，读起来像一条分隔线。
 * 内缩 36dp 后宽度约等于中间「时:分:秒」三列的视觉范围，进度与刻度对得上。
 */
private val TimerProgressBarInset = 36.dp

/**
 * 计时页的进度条：显示「剩余时间 / 本轮总时长」，随时间流逝**同步减少**（v1.1.72 需求；
 * 本轮需求 24 改为均匀减少）。
 *
 * ## 为什么用 `animateFloatAsState` 而不是直接拿 `remaining` 算
 *
 * `remaining` 每 **200ms** 才跳一次（本页的重算时钟是 `refreshTick` + `delay(200)`，
 * 见 `TimerPanel` 顶部的注释：用 `delay(200)` 而不是 `delay(1000)` 是为了
 * 让读数在整数秒附近更准）。直接拿它当进度值，进度条会**一秒顿 5 下**，
 * 与"平滑减少"的预期相反。
 *
 * 所以这里做一层插值。动画时长取 **200ms**，理由：
 * - **必须 ≤ 刷新间隔（200ms）**：否则上一段动画还没走完、新目标值又到，
 *   插值会持续"追不上"，表现为进度条整体滞后一个身位；
 * - 取**等于**刷新间隔而不是明显小于它：200ms 的线性插值正好把
 *   "5 段/秒的台阶"填成连续的斜坡，肉眼看不出分段；
 *   若取 100ms，前半段会走得比真实消耗快、后半段停着等下一拍，
 *   节奏反而不均匀。
 *
 * 这与此前主页打卡进度条用 350ms 不矛盾：那是**一次性**的增长动画
 * （0 → 目标，只播一次），而这里是**持续追赶**的插值，约束方向相反。
 *
 * ## 三种状态下的显示
 *
 * - **未开始**：显示**满条**。语义是"这一轮的额度还没开始消耗"，
 *   与读数区显示滚轮（而不是 00:00:00）一致——都在表达"还没开始"。
 *   选满条而不是隐藏：隐藏会让这一行高度塌掉，而高度必须是常量（见
 *   [TimerProgressBarHeight] 的注释），且空一条也不好理解。
 * - **计时中 / 暂停中**：`remaining / total`，暂停时停在当前值不动。
 * - **总时长为 0**（用户没拨滚轮）：分母为 0，必须兜住。
 *   此时显示**空条**（进度 0）而不是满条——"没有时长"更接近"没有剩余"，
 *   且与按钮行在该状态下被置灰（`enabled = selectedSeconds > 0`）一致：
 *   都表示"现在没有东西可以消耗"。用 `coerce` 保证不会出现 NaN。
 *
 * ## ⚠️ 单位：两个入参都是**毫秒**（需求 24 的改动）
 *
 * 调用方传 `state.selectedSeconds() * 1000L` 与 `state.remainingMillis()`。
 * 为什么不让本函数内部把秒乘 1000：秒→毫秒的换算放在调用方，
 * 「这一轮总时长是多少毫秒」这件事就与「滚轮选了多少秒」在同一行里看得见；
 * 放进本函数反而多一层"入参是秒还是毫秒"的猜测（本文件历史上正是这个坑：
 * 第一版曾按"remaining 是毫秒"写成 `remaining / (total * 1000f)`，
 * 实测进度条只剩一条 1px 的缝——单位差了 1000 倍）。
 *
 * ## 配色
 *
 * 进度用 `colorScheme.primary`（跟随主题色，与主页打卡进度条同色），
 * 轨道用 `colorScheme.surfaceContainerHighest`：它是本主题里比卡片底色
 * 更亮/更中性的那一档（浅色 `#FFFFFF`、深色 `#191919`），在消息区与卡片上
 * 都能与进度色区分。**不新增任何颜色常量**。
 */
@Composable
private fun TimerProgressBar(
    total: Long,
    remaining: Long,
    started: Boolean
) {
    // 目标进度：未开始 = 空条；总时长 0 = 空条；其余 = 剩余占比。
    //
    // ⚠️ 未开始态本轮（需求 1）由"满条"改成**空条**：原来一进页面就有一条通栏的
    // 绿色实线压在滚轮下方，是全页最抢眼的元素，读起来像一条分隔线而不是进度；
    // 空条（只剩浅灰轨道）才是"还没开始"的正确表达，按下开始后那 200ms 的
    // 从 0 涨到 1 也顺带成了"计时已启动"的反馈。
    val target = when {
        !started -> 0f
        total <= 0L -> 0f
        else -> (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
    // ⚠️ 单位对齐：`remaining` 与 `total` **都是毫秒**（见 KDoc「单位」一节），
    // 直接相除即可。第一版这里曾按"remaining 是毫秒、total 是秒"写成
    // `remaining / (total * 1000f)`，实测表现为**进度条只剩一条 1px 的缝**
    // （479 秒 / 480000 ≈ 0.001）——单位差了 1000 倍，填充宽几乎为零，只剩轨道。
    // 这个坑值得记住：本页同时存在毫秒（frozenRemaining / endAtMillis）与秒
    // （remainingSeconds / selectedSeconds）两套单位，**取值前必须核对来源**。


    // 插值层：把 200ms 的台阶填成斜坡（理由见 KDoc）。
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = TIMER_PROGRESS_ANIM_MS),
        label = "timerProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 左右内缩（本轮需求 1）：不与滚轮等宽贴边，见 [TimerProgressBarInset]。
            .padding(horizontal = TimerProgressBarInset)
            // 高度必须是常量：这一行的尺寸不参与任何状态判断（见 KDoc）。
            .height(TimerProgressBarHeight)
            // 半高圆角 = 胶囊形（见 TimerProgressBarCorner 的注释）。
            .clip(RoundedCornerShape(percent = 50))
            // 轨道改成 onSurface 的 10%：原来的 surfaceContainerHighest 在本主题里是**纯白**，
            // 压在 #EDEDED 的页面底色上会抢视线；10% 的黑/白叠色才是"凹槽"应有的重量。
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * 进度条插值动画时长（v1.1.72）。
 *
 * 取 200ms，**等于 `remaining` 的刷新间隔**——理由见 [TimerProgressBar] 的 KDoc：
 * 必须 ≤ 刷新间隔（否则永远追不上），取等于刷新间隔才能把 5 段/秒的台阶
 * 填成看不出分段的斜坡。
 */
private const val TIMER_PROGRESS_ANIM_MS = 200

/**
 * 循环滚动的总页数倍数：`count * LOOP_MULTIPLIER`。
 *
 * Pager 要求 pageCount 有限（`Int.MAX_VALUE` 会让它去算一个荒谬的滚动范围），
 * 取 1000 已经足够：最小的一列（24 页）也有 24000 页，从中间出发上下各有一万多页余量，
 * 正常人不可能滑到头。到不了头，也就不会出现「滑到边界卡住」。
 */
private const val LOOP_MULTIPLIER = 1000

/**
 * 中心按钮尺寸 / 图标尺寸 / 按钮间距：三种状态共用，保证视觉与手感一致。
 *
 * 本轮（需求 1）调整了三处**纯视觉**参数，不改任何判定逻辑：
 * - 图标 30dp → **26dp**：图标现在与文字并排（见 [TimerActionButton] 的 label 参数），
 *   30dp 的实心三角压在 16sp 文字旁边会明显「头重脚轻」；26dp 与一行 16sp 文字
 *   （约 22dp 视觉高）比例才协调。
 * - 按钮间距 28dp → **16dp**：双按钮不再是两个独立的圆，而是同一排胶囊，
 *   28dp 会把它们推得太开、读成两个不相干的控件。
 * - 高度 64dp **不变**：这是「三态之间按钮行位置恒定」的锚点（见 [TimerPickerSlotHeight]），
 *   改了它等于把历史上修过的「按钮上下跳」重新放出来。
 */
private val TimerButtonSize = 64.dp
private val TimerButtonIconSize = 26.dp
private val TimerButtonSpacing = 16.dp

/** 图标与文字之间的间距；8dp 是图标 + 短标签的常用呼吸值。 */
private val TimerButtonIconLabelGap = 8.dp

/** 按钮内文字字号；与 [TimerStartButtonWidth] 一起决定胶囊的视觉重量。 */
private val TimerButtonLabelSize = 16.sp

/** 「开始」胶囊按钮的宽度：明显横向，一眼能看出是主操作（图标 + 两字标签刚好放得下）。 */
private val TimerStartButtonWidth = 168.dp

/**
 * 双按钮态（停止 / 暂停或继续）每个胶囊的宽度。
 *
 * 取 **128dp**：两个 128 + 16 间距 = 272dp，在 393dp 宽的机器上左右各留 40dp 余量，
 * 在 360dp 的小屏上也放得下；同时足够容纳「❚❚ 暂停」这样的图标 + 两字标签。
 */
private val TimerDualButtonWidth = 128.dp

/**
 * 计时按钮的**待机**投影高度（v1.1.72 收敛，原为 8dp）。
 *
 * 取 **2dp**：原值 8dp 在 64dp 的圆钮周围会糊出一圈明显的黑晕，尤其**长按按住不放**
 * （停止 / 暂停两键）时最刺眼——用户反馈的正是这个。
 * 按压反馈现在主要由「缩放 0.84 + 容器压暗 22%」承担，投影只保留极轻的一层，
 * 让按钮不至于完全贴平在背景上即可。
 */
private val IDLE_ELEVATION = 2.dp

/** 计时按钮**按下**时的投影高度（原为 2dp）。取 0.5dp：贴下去，但仍有极微弱的分界。 */
private val PRESSED_ELEVATION = 0.5.dp

/**
 * 计时按钮顶部高光的**待机** alpha（v1.1.72 收敛，原为 0.18）。
 *
 * 取 **0.07**：原值 0.18 的白色渐变在实色圆钮上半部会形成一块显眼的光斑
 * （长按暂停 / 停止键时尤其明显）。压到 0.07 后高光只在斜视时隐约可见，
 * 保留"材质"感而不抢视线。
 */
private const val IDLE_HIGHLIGHT_ALPHA = 0.07f

/** 计时按钮**按下**时高光的 alpha（原为 0.10）。取 0.03：按下时高光几乎收掉。 */
private const val PRESSED_HIGHLIGHT_ALPHA = 0.03f

/**
 * 「暂停 / 继续」按钮的两次点击最小间隔（v1.1.69 需求 2）。
 *
 * 取 **300ms**：需求原文「两次暂停点击不小于 0.3s」。
 * 为什么这个按钮要限速：暂停/继续是**状态切换**，连点会让状态高速翻转
 * （跑→停→跑→停），用户既看不清当前状态，也容易在"想停"时多按一下反而又跑了；
 * 而 300ms 又短于人对"我这下按轻了没生效"的感知阈值，不会显得迟钝。
 *
 * 只作用于暂停与继续（同一枚按钮的两种形态），**不作用于停止**：
 * 停止是"放弃本轮"的明确动作，连按通常就是想赶紧停，加间隔只会添乱。
 *
 * ⚠️ v1.1.72 起「开始」也有自己的节流（见 [START_THROTTLE_MS]），
 * 与这里是两套独立参数——前者是 0.5s（与它的按压动画时长匹配），
 * 后者是 0.3s（需求指定的值）。不要把它们合并成一个常量。
 */
private const val PAUSE_RESUME_THROTTLE_MS = 300L

/**
 * 「开始」按钮的常态节流间隔（v1.1.72）。
 *
 * 取 **500ms**：需求原文「后续两次点击间有 0.5s 动画，动画期间无法点击」。
 *
 * 这个数字必须与 `TimerActionButton` 的**按压动画时长**匹配，理由：
 * 需求要的是"动画期间无法点击"，而本按钮的按压动画由
 * `animateFloatAsState` + 双弹簧驱动（按下 `StiffnessMediumLow`、
 * 弹起 `DampingRatioMediumBouncy` + `StiffnessMedium`），
 * 完整走一遍约 400~500ms，且它是**不可挂起等待**的声明式动画。
 *
 * 把节流窗口设成 500ms，等效于"上一次点击的动画放完之前，
 * 下一次点击会被静默丢弃"——不改动画实现就满足了需求。
 * 若把这里改成明显小于动画时长（如 200ms），用户可以在动画中途连点，
 * 那就违背了"动画期间无法点击"；改成明显大于动画时长（如 1s）
 * 又会让按钮显得迟钝（与第一段的那 1 秒门叠成 2 秒不可点）。
 */
private const val START_THROTTLE_MS = 500L

/**
 * 「开始」按钮的一次性冷却门时长（v1.1.72）。
 *
 * 取 **1000ms**：需求原文「倒计时的开始按钮点击后一秒钟内无法点击」。
 *
 * 与 [START_THROTTLE_MS] 的分工：那一个是"此后每次都要等 0.5s"的**常态**节流，
 * * 这一个是"只紧这一次"的**一次性**门。两者在 `TimerActionButton` 里叠加判断
 * （都过才生效），所以开始后的第 1 秒内任何点击都无效，
 * 第 1 秒过后则回到 0.5s 的常态节流。
 *
 * 为什么需要这个一次性门：按下开始的那一瞬间，读数会从滚轮切成大号倒计时、
 * 进度条从满格开始退——这一串变化要约 300~500ms 才演完。
 * 若这期间用户连点，`state.start()` 虽幂等，但会让"上一轮的铃声"
 * 与"新一轮的启动"互相打断，边界行为难以预料。1 秒足够用户看清"已经开始了"。
 */
private const val START_GATE_MS = 1000L

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
 * @param gateUntilMs 一次性冷却门的到期时刻（`SystemClock.elapsedRealtime()` 口径）。
 *   0 = 无门。用于「开始」：v1.1.72 需求要求它点击后 1 秒内不能再点，
 *   而 [throttleMs] 表达不了"只紧一次"的语义，故单独一个字段。
 */
private data class TimerAction(
    val icon: ImageVector,
    val description: String,
    val throttleMs: Long = 0L,
    val gateUntilMs: Long = 0L,
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
                    // ---- 本轮（需求 1）：图标旁边补文字，形状统一成胶囊 ----------------
                    //
                    // 原来单按钮只有一枚 ▶、双按钮只有两枚圆钮：在 64dp 的圆里，
                    // 「停止」与「暂停」的图标形状很容易认错（尤其小屏 + 大字号时），
                    // 而且圆钮与胶囊并存让同一排按钮的**圆角语言**不统一。
                    //
                    // 现在两种状态都是 64dp 高的胶囊、图标 + 文字并排：
                    //   · 未开始 = 主色实心「▶ 开始」；
                    //   · 计时中 / 暂停中 = 浅色「■ 停止」「❚❚ 暂停 / ▶ 继续」。
                    // 高度两种状态完全相同（都是 [TimerButtonSize]），所以按钮行的 y 坐标
                    // 与三态之间的「位置恒定」不变量一字未改（见下方 TimerActionButton 的注释）。
                    label = action.description,
                    contentDescription = action.description,
                    container = container,
                    tint = content,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .width(
                            if (single) TimerStartButtonWidth else TimerDualButtonWidth
                        )
                        .height(TimerButtonSize),
                    enabled = enabled,
                    throttleMs = action.throttleMs,
                    gateUntilMs = action.gateUntilMs,
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
 *
 * ## 一次性冷却门（v1.1.72 需求：开始按钮的「先 1 秒、后 0.5 秒」）
 *
 * [throttleMs] 表达的是"两次点击之间的最小间隔"，它无法表达
 * **"点完一次后，先紧一段、之后每次都要等一段动画"**这种两段式语义。
 * 所以额外加 [gateUntilMs]：一个**单调时钟上的绝对时刻**，早于它的点击全部丢弃。
 *
 * 两者是叠加关系（都过才生效）：
 * - `gateUntilMs` 管**一次性**的紧窗口（开始后的第 1 秒）；
 * - `throttleMs` 管**此后每次**的常规间隔（0.5 秒，与按压动画时长对齐）。
 *
 * 为什么门控也不置灰：与节流同一条理由——按钮没有坏，只是"这一下不算"。
 * 置灰会让用户以为开始键失灵了，而那 1 秒是刻意设计的缓冲。
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
    /** 胶囊里显示的文字；null = 只显示图标（保留给纯图标按钮的调用点）。 */
    label: String? = null,
    enabled: Boolean = true,
    throttleMs: Long = 0L,
    /** 一次性冷却门的绝对到期时刻（`SystemClock.elapsedRealtime()` 口径）；0 = 无门。 */
    gateUntilMs: Long = 0L
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

    // ── 高级材质质感（v1.1.69 需求，v1.1.72 收敛）────────────────────────────
    //
    // ⚠️ v1.1.72 用户反馈：「长按暂停与终止键底层显示」不好看——阴影 / 光晕太明显。
    // 定位到两个"过重"的通道（都是**待机态**的值，不是按下的）：
    // 1. 投影待机 8dp：64dp 的圆钮上 8dp 阴影会糊出一圈明显的黑晕，
    //    拟物感过头，不符合本项目「扁平、灰底白卡」的整体取向（见 Theme.kt 顶部）；
    // 2. 顶部高光 alpha 0.18：白色渐变压在实色圆钮上半部，形成一块显眼的光斑。
    //
    // 收敛原则：**按压反馈保留在主通道**（缩放 0.84 + 容器压暗 22%），
    // 材质层只做"很轻的"辅助——把投影与高光都压到"几乎看不见、但缺了会显得扁"的程度；
    // 按下时再往下收一点，让"凸起→压平"的物理隐喻仍然成立。
    // 这样长按（按住不放）时不会在按钮周围浮出一圈黑晕 + 白斑。
    //
    // 三个通道叠出"实体按键"的观感，全部取自主题色、不新增颜色常量：
    // 1. **投影深度随按压变化**：待机 2dp、按下 0.5dp——保留"凸起→压平"的隐喻，
    //    但幅度收到几乎察觉不到的量级；
    // 2. **顶部高光渐变**：容器上半部压一层极淡的白色竖直渐变（alpha 0.07 → 0），
    //    模拟光源从左上方打下；这是拟物按钮的通用做法，也是"材质"感的来源；
    // 3. **边缘描边**：一圈 alpha 0.12 的主题色描边，让按钮与背景之间有一道
    //    清晰的分界（纯色填充在浅色背景上容易"糊边"）。
    //
    // 高光用“与 tint 同源的白色”而不是主题色：不同主题色下白高光都成立，
    // 而用主题色提亮会在浅色主题色（如青色）上彻底看不见。
    // 阴影用 `MaterialTheme.colorScheme.shadow`（本项目两套主题里都是黑，正是投影需要的色）。
    val elevation by animateDpAsState(
        targetValue = if (pressed) PRESSED_ELEVATION else IDLE_ELEVATION,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "timerActionElevation"
    )
    val highlightAlpha = if (pressed) PRESSED_HIGHLIGHT_ALPHA else IDLE_HIGHLIGHT_ALPHA

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
                // 一次性门（v1.1.72）：开始后的第 1 秒内一律无效。
                if (gateUntilMs > 0L && now < gateUntilMs) {
                    return@clickable
                }
                if (throttleMs > 0L && now - lastEffectiveClick < throttleMs) {
                    return@clickable
                }
                lastEffectiveClick = now
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // 图标与文字并排（本轮需求 1）。两者一起随 `iconAlpha` 变暗、随 `iconScale` 缩放，
        // 所以按下时整块内容一起「陷下去」，不会出现"字没动、图标缩了"的错位观感。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TimerButtonIconLabelGap)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint.copy(alpha = tint.alpha * iconAlpha),
                modifier = Modifier
                    .size(TimerButtonIconSize)
                    .graphicsLayer {
                        // 图标缩放叠在文字旁边：用 graphicsLayer 而不是改 size，
                        // 免得每一帧都触发重新测量（压感动画是高频帧）。
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
            if (label != null) {
                Text(
                    text = label,
                    fontSize = TimerButtonLabelSize,
                    fontWeight = FontWeight.SemiBold,
                    color = tint.copy(alpha = tint.alpha * iconAlpha),
                    maxLines = 1
                )
            }
        }
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
