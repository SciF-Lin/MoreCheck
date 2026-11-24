package com.morecheck.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 出错着色的停留时长（毫秒）：够看清"变红了"，又不至于让人等。 */
private const val ERROR_HOLD_MS = 600L

/**
 * 画对之后「成功着色」的停留时长（毫秒）。
 *
 * 2026-11-03 需求「手势输入后短暂停留」：用户画完最后一点抬手时，界面若**当帧**就
 * 切走（解锁进主页 / 进设置第 2 步），他其实没机会确认"我画的是不是刚才那条轨迹"，
 * 观感上像被闪了一下。留 400ms 让轨迹用成功色亮一下再切，既给出明确反馈，
 * 又短到不会被当成卡顿。
 *
 * ⚠️ **调用方必须用同一个常量来排自己的后续动作**（[AppLockCore] 的解锁、
 * [PatternSetupLayer] 的两步流转）：手势板自己只是把着色保持这段时间，
 * 真正"停留结束后再切换"的定时在调用方；两边时长不一致会出现
 * 「颜色已消失但界面还没切」或「界面已切但颜色还没播完」的割裂。
 */
internal const val PATTERN_SUCCESS_HOLD_MS = 400L

/**
 * 3×3 手势图案绘制板（2026-10-25 锁屏密码）。
 *
 * ## 交互
 *
 * 手指按下后拖动，途经的九个点按顺序被"选中"（每个点只入选一次）；
 * 抬起时把**选中的点序列**回调出去（[onPattern]）。少于 4 个点视为无效手势，
 * 由调用方决定如何提示（本组件只负责收集）。
 *
 * ## 为什么自绘而不用第三方库
 *
 * 需求只要"3×3 连线"这一种形态；Canvas + drag 手势六十来行就能画完，
 * 引一个锁屏库反而带进一整套样式与状态机（且多为 View 体系）。
 *
 * ## 命中判定
 *
 * 以每个点为中心、半径 = 格宽 38% 的圆为命中区（比可见圆点大一圈，
 * 手指不需要精确压在点上）；拖动经过即选中，**不**自动补"跨越中间点"
 * （本应用自用，简单直接优先；系统锁屏那套中间点规则这里刻意不做）。
 *
 * @param onPattern 手指抬起时回调；参数是选中的点下标序列（0..8，按下顺序）。
 * @param errorSignal **每变一次就表示"这次画错了"**（调用方自增）。组件收到后先把
 *   已画出的痕迹按 [errorColor] 着色并停留 [ERROR_HOLD_MS]，然后**自动清空**。
 *   2026-10-26 修：原实现只在下次按下时 `selected.clear()`，于是输错后那几根线
 *   一直挂在板上，看着像"没反应过来"。
 * @param errorColor 出错时的着色；传 `null` = 只清空、不变色
 *   （用户关掉「输错震动变红」时的口径）。
 * @param successSignal **每变一次就表示"这次画对了"**（调用方自增，2026-11-03）。
 *   组件收到后把已画出的轨迹与选中点按 [successColor] 加粗高亮并停留
 *   [PATTERN_SUCCESS_HOLD_MS]，然后自动清空。
 *   **停留期间不再接受新的手势输入**：否则用户在"对了"的这 400ms 里再划一下，
 *   会覆盖掉正在展示的成功轨迹，甚至把下一次输入提前送进 `onPattern`，
 *   让调用方的校验状态机（尤其两步设置）错乱。
 * @param successColor 成功时的着色；传 `null`（默认）= 主题主色。刻意不写死绿色：
 *   本应用所有强调色都跟主题走，锁屏板突然冒出一个固定绿点会与主题脱节。
 * @param clearSignal **每变一次就清空板面**（不闪烁、不着色）。用于「设置/修改手势」
 *   的两步流程：第 1 步画完切到第 2 步时，上一笔痕迹必须立刻消失，否则用户会以为
 *   程序卡住或把自己刚画的图案当成"确认的那一遍"（v1.1.92 修）。
 * @param modifier 外部尺寸约束（调用方给一个正方形区域）。
 */
@Composable
fun PatternLockView(
    onPattern: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
    errorSignal: Int = 0,
    errorColor: Color? = null,
    successSignal: Int = 0,
    successColor: Color? = null,
    clearSignal: Int = 0
) {
    // 已选中的点（按下顺序）。mutableStateListOf：绘制层要逐帧读它。
    val selected = remember { mutableStateListOf<Int>() }
    // 当前指尖位置（画"最后一点到指尖"的橡皮筋线）；null = 手指不在屏上。
    var finger by remember { mutableStateOf<Offset?>(null) }
    // 是否处于「出错着色」的短暂窗口。
    var showError by remember { mutableStateOf(false) }
    // 是否处于「成功着色」的短暂窗口（2026-11-03）。
    var showSuccess by remember { mutableStateOf(false) }

    // 收到"清空"信号 → 立刻清掉（不等待、不着色）。
    LaunchedEffect(clearSignal) {
        if (clearSignal <= 0) return@LaunchedEffect
        showError = false
        // 成功窗口里收到清空信号虽然走不到（调用方都等停留结束才切步），
        // 但状态标志必须一起复位：留一个 true 会让手势板永久拒绝输入。
        showSuccess = false
        selected.clear()
        finger = null
    }

    // 收到新的错误信号 → 着色 → 停留一小会儿让用户看清 → 清空痕迹。
    // 首次组合时 errorSignal 为 0，直接跳过（不会误清）。
    LaunchedEffect(errorSignal) {
        if (errorSignal <= 0) return@LaunchedEffect
        // 两种着色互斥：错误到来时把成功窗口收掉，避免"又绿又红"。
        showSuccess = false
        showError = errorColor != null
        delay(ERROR_HOLD_MS)
        showError = false
        selected.clear()
        finger = null
    }

    // 收到新的成功信号 → 成功色高亮 → 保持 → 清空（2026-11-03）。
    // 与错误窗口一样按"信号自增"触发，保证同一段成功轨迹只播一次。
    LaunchedEffect(successSignal) {
        if (successSignal <= 0) return@LaunchedEffect
        showError = false
        showSuccess = true
        // 抬手时已把橡皮筋线收掉；这里再兜一次，避免残留。
        finger = null
        delay(PATTERN_SUCCESS_HOLD_MS)
        showSuccess = false
        // 停留结束才清空：清早了用户看不到"对了"的那一眼。
        selected.clear()
    }

    BoxWithConstraints(modifier = modifier) {
        // ⚠️ 必须是「短边」而不是「宽」：调用方给的盒子是 `fillMaxWidth().height(300.dp)`，
        // 竖屏下宽 ≈ 320~400dp、高固定 300dp，两者不等。原实现只按宽度算格宽，
        // 于是 3×3 网格比盒子高、最后一行被裁掉一截，看起来像"下面三个点偏小/难点"。
        // 取短边并把正方形居中，宽高不等时九宫格仍是正圆 + 等距。
        val density = LocalDensity.current
        val sidePx = with(density) { minOf(maxWidth, maxHeight).toPx() }
        val originX = with(density) { (maxWidth - maxHeight).toPx().coerceAtLeast(0f) / 2f }
        val originY = with(density) { (maxHeight - maxWidth).toPx().coerceAtLeast(0f) / 2f }
        // 第 i 个点的中心：3×3 网格，格宽 = sidePx / 3，中心在格中央（再叠加居中偏移）。
        fun centerOf(index: Int): Offset {
            val col = index % 3
            val row = index / 3
            val cell = sidePx / 3f
            return Offset(originX + cell * col + cell / 2f, originY + cell * row + cell / 2f)
        }
        val hitRadius = sidePx / 3f * 0.38f

        val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
        // 三种窗口的着色优先级：出错 > 成功 > 常态。两个窗口互斥（各自的 effect 会复位对方）。
        val accent = when {
            showError -> errorColor ?: primary
            showSuccess -> successColor ?: primary
            else -> primary
        }
        val dotIdle = when {
            showError && errorColor != null -> errorColor.copy(alpha = 0.45f)
            // 成功时未选中的点也淡淡染上成功色，整块板"亮起来"比只抬高亮线更容易被看见。
            showSuccess -> accent.copy(alpha = 0.35f)
            else -> androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
        }
        val dotSelected = accent
        // 成功窗口里线更实、更粗（6dp → 8dp），拉开与"正在画"的差别。
        val lineColor = accent.copy(alpha = if (showSuccess) 0.95f else 0.65f)
        // toPx 需要 Density 作用域：这里在 BoxWithConstraints 的 content 里（不是
        // DrawScope），直接调 .toPx() 编译不过，必须走上面取出的 density。
        val lineWidth = with(density) { (if (showSuccess) 8.dp else 6.dp).toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // ⚠️ 不用 `detectDragGestures`：它的 `onDragStart` 给的是**越过触摸阈值之后**
                    // 的位置，不是手指真正按下的点。快速起手时（一次事件位移 > 命中半径）
                    // 第一个点会被直接跳过——用户画的是 4 个点，系统只认到 3 个，报"至少连接 4 个点"。
                    // 2026-10-26 模拟器实测复现（同一次手势画 4 点只识别到 3 点）。
                    // 改为自己跑手势循环：以 `awaitFirstDown` 的真实坐标作为起点。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 成功停留期间忽略输入（2026-11-03）：见 [successSignal] 的说明。
                        // `awaitEachGesture` 在块结束后会等所有手指抬起，所以这里提前
                        // 返回不会漏掉抬起事件、也不会把这次按下当成下一笔。
                        if (showSuccess) return@awaitEachGesture
                        selected.clear()
                        finger = down.position
                        // 起点落在哪个点就先选上（照旧按命中半径判定）。
                        (0..8).firstOrNull { i ->
                            val c = centerOf(i)
                            (down.position - c).getDistance() <= hitRadius
                        }?.let { selected.add(it) }

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                // 抬手：回调一次完整序列，并把橡皮筋线收掉。
                                finger = null
                                onPattern(selected.toList())
                                break
                            }
                            finger = change.position
                            (0..8).firstOrNull { i ->
                                !selected.contains(i) && run {
                                    val c = centerOf(i)
                                    (change.position - c).getDistance() <= hitRadius
                                }
                            }?.let { selected.add(it) }
                            // 不消费事件：本组件不参与嵌套滚动，让位给宿主的手势（返回手势等）。
                        }
                    }
                }
        ) {
            // ① 连线：选中点依次相连；手指在屏上时再画"最后一点 → 指尖"一段。
            val centers = selected.map { centerOf(it) }
            if (centers.size > 1) {
                for (i in 0 until centers.size - 1) {
                    drawLine(
                        color = lineColor,
                        start = centers[i],
                        end = centers[i + 1],
                        strokeWidth = lineWidth,
                        cap = StrokeCap.Round
                    )
                }
            }
            if (centers.isNotEmpty() && finger != null) {
                drawLine(
                    color = lineColor.copy(alpha = 0.35f),
                    start = centers.last(),
                    end = finger!!,
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round
                )
            }

            // ② 九个点：未选中的小而淡，选中的大而实；成功窗口里选中点加一圈柔光。
            for (i in 0..8) {
                val c = centerOf(i)
                val isOn = selected.contains(i)
                if (isOn && showSuccess) {
                    // 柔光用低 alpha 大圆画在点下面，不新增依赖也能做出"发光"观感。
                    drawCircle(
                        color = accent.copy(alpha = 0.16f),
                        radius = 24.dp.toPx(),
                        center = c
                    )
                }
                drawCircle(
                    color = if (isOn) dotSelected else dotIdle,
                    radius = (when {
                        isOn && showSuccess -> 16.dp
                        isOn -> 14.dp
                        else -> 10.dp
                    }).toPx(),
                    center = c
                )
            }
        }
    }
}
