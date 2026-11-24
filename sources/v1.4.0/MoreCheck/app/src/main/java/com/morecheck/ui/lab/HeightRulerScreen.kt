package com.morecheck.ui.lab

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.morecheck.util.findActivity
import kotlin.math.roundToInt

/**
 * 开发者选项 →「界面尺寸尺」：一张**全屏的刻度尺**调试工具（2026-11-03 需求 6）。
 *
 * ## 它是干什么用的
 *
 * 用户/开发者描述界面问题时常用「这块高大概多少」「状态栏下面到按钮还有多远」这类
 * 说不清的话。本工具把整块屏幕变成一把可读的尺子：**从状态栏下沿开始**每隔 8dp / 16dp
 * 画一条刻度，关键距离直接标成「dp + px」，因此可以当场报出"某控件高多少"。
 *
 * ## 为什么做成「就地全屏覆盖」而不是导航路由
 *
 * 本工具只从「开发者选项」进入、退出后也只回「开发者选项」，与该项目既有的
 * 二级页口径一致（见 `DeveloperOptionsScreen` 的 KDoc：`PersonalInfoScreen → AvatarCropScreen`
 * 确立的就地覆盖做法）。走路由要改 `ui/nav/TabDef.kt` 与 `TabNavHost.kt`，
 * 本次改动白名单不允许，且没有任何导航语义上的收益。
 *
 * ## 系统栏：进入隐藏、退出还原
 *
 * 全屏测量必须让状态栏 / 导航栏先让位，否则量到的是"被系统栏裁过"的可视区。
 * 用 [WindowInsetsControllerCompat] 隐藏两类系统栏；`DisposableEffect` 的
 * `onDispose` 里 `show()` 回去并还原 `systemBarsBehavior`——
 * 本项目 `LabScreen` 的 `TransparentNavigationBarEffect` 已经踩过"页级系统栏改动不还原
 * 会污染下一页"的坑，这里沿用同一条纪律。
 *
 * ## 关键高度从哪里来
 *
 * 状态栏 / 导航栏高度用 `getInsetsIgnoringVisibility()` 读：
 * **系统栏被隐藏后，普通 `getInsets()` 会变成 0**，量不到真实值；
 * `getInsetsIgnoringVisibility()` 不看可见性、只报尺寸，正好是这把尺子要的语义。
 *
 * ## 返回键
 *
 * 全屏页必须自己接住返回键（本页没有 `Scaffold` 的返回箭头可点，只有面板上的关闭钮），
 * 因此用 [BackHandler] 显式处理，与项目其它全屏页口径一致。
 *
 * @param onExit 退出本工具、回到开发者选项（由调用方把显示状态置 false）。
 */
@Composable
fun HeightRulerScreen(onExit: () -> Unit) {
    // 全屏工具必须自己处理返回键：不接的话会直接退出整个应用。
    BackHandler(onBack = onExit)

    // 进入隐藏系统栏 / 保持屏幕常亮，退出把两者都还原。
    HideSystemBarsWhileVisible()

    val density = LocalDensity.current
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 背景遮罩：半透明，隐约还能看到后面的开发者选项页。
    // 深浅色各一套：浅色用白雾、深色用黑雾，刻度线/文字再按主题取高对比色。
    val scrim = if (darkTheme) {
        Color.Black.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.88f)
    }
    val tickColor = MaterialTheme.colorScheme.onSurface
    val accentColor = MaterialTheme.colorScheme.primary

    // ---- 可切换项 --------------------------------------------------------
    // 刻度密度：8dp 细尺 / 16dp 粗尺（细尺标签多，粗尺更干净）。
    var stepDp by rememberSaveable { mutableIntStateOf(DEFAULT_STEP_DP) }
    // 显示方向：双向 / 仅纵向 / 仅横向（用 name 存，避免依赖枚举的 Serializable 行为）。
    var axisName by rememberSaveable { mutableStateOf(RulerAxis.BOTH.name) }
    val axis = RulerAxis.entries.firstOrNull { it.name == axisName } ?: RulerAxis.BOTH
    // 控制面板是否展开：测量时点一下屏幕空白处可收起，避免面板挡住刻度。
    var panelVisible by rememberSaveable { mutableStateOf(true) }

    val insets = rememberRulerInsets()
    val interactionSource = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scrim)
    ) {
        // 整屏尺寸（含系统栏；本应用是 edge-to-edge，Compose 根铺满整个窗口）。
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        // 点空白处：展开时收起控制面板、收起时再展开（Canvas 自己不消费点击）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) { panelVisible = !panelVisible }
        ) {
            RulerCanvas(
                stepDp = stepDp,
                axis = axis,
                insets = insets,
                tickColor = tickColor,
                accentColor = accentColor,
                labelTextColor = MaterialTheme.colorScheme.onSurface
            )
        }

        if (panelVisible) {
            ControlPanel(
                widthPx = widthPx,
                heightPx = heightPx,
                insets = insets,
                stepDp = stepDp,
                axis = axis,
                onStepChange = { stepDp = it },
                onAxisChange = { axisName = it.name },
                onExit = onExit,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            )
        } else {
            // 面板收起后留一个很小的按钮，方便重新展开（不占测量区域）。
            IconButton(
                onClick = { panelVisible = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Straighten,
                    contentDescription = "展开尺寸尺控制面板",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 进入本页时隐藏系统栏、保持屏幕常亮；离开时**原样还原**。
 *
 * ⚠️ 还原必须写在 `onDispose` 里而不是页面内某个按钮的 `onClick`：
 * 用户也可能用返回键 / 手势返回退出，所有出口都会走到组合销毁，只有这里有保证。
 */
@Composable
private fun HideSystemBarsWhileVisible() {
    val view = LocalView.current
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, view) {
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val previousBehavior = controller.systemBarsBehavior
            val previousKeepScreenOn = view.keepScreenOn
            // 下滑呼出临时系统栏：测量时想临时看一眼状态栏也能做到。
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 量尺期间用户通常不会碰屏幕，保持常亮避免量到一半熄屏。
            view.keepScreenOn = true
            onDispose {
                view.keepScreenOn = previousKeepScreenOn
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 控制面板
// ---------------------------------------------------------------------------

/**
 * 顶部的信息 + 开关面板。
 *
 * 必须用半透明卡片而不是纯色：它压在全屏刻度上，纯色会把顶部一段刻度完全盖掉。
 * 卡片容器取主题的 `surface`（跟随深浅色），文字一律用 `onSurface` 系列，
 * 因此深色模式下是深底浅字、浅色模式下是浅底深字，两边都清晰。
 */
@Composable
private fun ControlPanel(
    widthPx: Int,
    heightPx: Int,
    insets: RulerInsets,
    stepDp: Int,
    axis: RulerAxis,
    onStepChange: (Int) -> Unit,
    onAxisChange: (RulerAxis) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // `Density` 接口只暴露 density / fontScale，没有 densityDpi；
    // 屏幕 dpi 要从 `Configuration` 读（它就是资源里的那个 densityDpi）。
    val densityDpi = LocalConfiguration.current.densityDpi
    val widthDp = with(density) { widthPx.toDp().value.roundToInt() }
    val heightDp = with(density) { heightPx.toDp().value.roundToInt() }
    val statusDp = with(density) { insets.statusBarPx.toDp().value.roundToInt() }
    val navDp = with(density) { insets.navBarPx.toDp().value.roundToInt() }

    Card(
        modifier = modifier
            .fillMaxWidth()
            // 面板本身吃掉点击：否则点在面板文字/空白处会穿透到下面那层
            // "点空白收起面板"的 clickable，面板会莫名其妙消失。
            // 指示设为 null（不加涟漪）：这不是一个"可点动作"，只是拦截。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "界面尺寸尺",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "退出尺寸尺",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            InfoLine("屏幕", "$widthDp × $heightDp dp / $widthPx × $heightPx px")
            InfoLine(
                "密度",
                "density ×${format1(density.density)} · $densityDpi dpi · 字体 ×${
                    format1(density.fontScale)
                }"
            )
            InfoLine(
                "系统栏",
                "状态栏 $statusDp dp / ${insets.statusBarPx} px · " +
                    "导航栏 $navDp dp / ${insets.navBarPx} px"
            )
            InfoLine(
                "可量高度",
                "${heightDp - statusDp - navDp} dp / ${heightPx - insets.statusBarPx - insets.navBarPx} px"
            )

            Spacer(Modifier.height(10.dp))

            // 刻度密度：两条芯片，选中态由 FilterChip 自己按主题画。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "刻度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                STEP_OPTIONS.forEach { step ->
                    FilterChip(
                        selected = stepDp == step,
                        onClick = { onStepChange(step) },
                        label = { Text("${step}dp") }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "方向",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                RulerAxis.entries.forEach { option ->
                    FilterChip(
                        selected = axis == option,
                        onClick = { onAxisChange(option) },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "纵向刻度从状态栏下沿（0dp）算起；点空白处可收起/展开本面板，" +
                    "返回键退出并恢复系统栏。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}

/** 面板里的一行「标签 · 值」；值用等宽字体，读数字时不跳字宽。 */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(52.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// 刻度绘制
// ---------------------------------------------------------------------------

/**
 * 全屏刻度画布。
 *
 * ## 为什么零点是「状态栏下沿」而不是屏幕顶
 *
 * 用户要量的几乎总是"内容区里某个控件多高"。把零点放在状态栏下沿后，
 * 控件下沿落在刻度上的读数就是它的高度（含顶栏内边距时可直接减），
 * 不必自己去做 `y - statusBarHeight` 的减法。
 *
 * ## 三种刻度长度
 *
 * 细刻度只给"这有刻度"的视觉，中线每 5 格加长，主线每 5 格标数字：
 * 数字按当前 [stepDp] 每 5 格出现一次（8dp → 40dp 一个数字；16dp → 80dp 一个）。
 */
@Composable
private fun RulerCanvas(
    stepDp: Int,
    axis: RulerAxis,
    insets: RulerInsets,
    tickColor: Color,
    accentColor: Color,
    labelTextColor: Color
) {
    // 文字用 android.graphics.Paint 画：Compose 的 drawText 需要 TextMeasurer，
    // 而这里只需要固定字号的等宽小字，原生画布更直接、也不引入额外组合状态。
    val localDensity = LocalDensity.current
    val labelPaint = remember(localDensity) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(localDensity) { 10.dp.toPx() }
        }
    }
    val smallPaint = remember(localDensity) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = with(localDensity) { 9.dp.toPx() }
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // ⚠️ 这里必须用外层捕获的 `localDensity`：`DrawScope` 自己也有 `density: Float`
        // 属性，直接写 `density` 会解析成 Float，`.density` 就编译不过了。
        val pxPerDp = localDensity.density
        val statusTop = insets.statusBarPx.toFloat()
        val contentBottom = size.height - insets.navBarPx
        if (contentBottom <= statusTop) return@Canvas

        val majorEvery = 5

        fun pxOf(dp: Int): Int = (dp * pxPerDp).roundToInt()

        // ---- 纵向标尺（左侧）--------------------------------------------
        if (axis != RulerAxis.HORIZONTAL_ONLY) {
            val originX = 6.dp.toPx()
            val majorLen = 26.dp.toPx()
            val mediumLen = 17.dp.toPx()
            val minorLen = 10.dp.toPx()

            var dp = 0
            // 刻度一直画到**屏幕最底**（需求原文：「从状态栏下沿到屏幕底部」），
            // 导航栏上沿另有一条强调线（contentBottom），方便看清导航栏占多少。
            while (statusTop + dp * pxPerDp <= size.height + 0.5f) {
                val y = statusTop + dp * pxPerDp
                val isMajor = dp % (stepDp * majorEvery) == 0
                val isMedium = dp % stepDp == 0 && (dp / stepDp) % majorEvery == 0
                val len = when {
                    isMajor -> majorLen
                    isMedium -> mediumLen
                    else -> minorLen
                }
                drawLine(
                    color = tickColor.copy(alpha = if (isMajor) 0.95f else if (isMedium) 0.8f else 0.5f),
                    start = Offset(originX, y),
                    end = Offset(originX + len, y),
                    strokeWidth = if (isMajor) 1.6.dp.toPx() else 1.dp.toPx()
                )
                if (isMajor) {
                    drawTickLabel(
                        paint = labelPaint,
                        color = labelTextColor,
                        x = originX + majorLen + 4.dp.toPx(),
                        baseline = y + 3.5.dp.toPx(),
                        lines = listOf("$dp dp", "${pxOf(dp)} px")
                    )
                }
                dp += stepDp
            }

            // 状态栏下沿（零点）与内容区底沿：两条强调线。
            drawLine(
                color = accentColor.copy(alpha = 0.9f),
                start = Offset(0f, statusTop),
                end = Offset(size.width, statusTop),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = accentColor.copy(alpha = 0.9f),
                start = Offset(0f, contentBottom),
                end = Offset(size.width, contentBottom),
                strokeWidth = 1.dp.toPx()
            )

            // 常见控件高度参考线（从状态栏下沿起算）：列表行 48 / 行高 56 / 顶栏 64。
            // 只有它们在可视区内才画，标签贴在屏幕右侧，读数仍以左侧主尺为准。
            KEY_COMPONENT_HEIGHTS_DP.forEach { heightDp ->
                val y = statusTop + heightDp.dp.toPx()
                if (y < contentBottom - 2.dp.toPx()) {
                    drawLine(
                        color = accentColor.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawTickLabel(
                        paint = smallPaint,
                        color = accentColor,
                        x = size.width - 96.dp.toPx(),
                        baseline = y - 3.dp.toPx(),
                        lines = listOf("$heightDp dp / ${pxOf(heightDp)} px")
                    )
                }
            }
        }

        // ---- 横向标尺（底沿）--------------------------------------------
        if (axis != RulerAxis.VERTICAL_ONLY) {
            val originY = contentBottom
            val majorLen = 26.dp.toPx()
            val mediumLen = 17.dp.toPx()
            val minorLen = 10.dp.toPx()

            var dp = 0
            while (dp * pxPerDp <= size.width + 0.5f) {
                val x = dp * pxPerDp
                val isMajor = dp % (stepDp * majorEvery) == 0
                val isMedium = dp % stepDp == 0 && (dp / stepDp) % majorEvery == 0
                val len = when {
                    isMajor -> majorLen
                    isMedium -> mediumLen
                    else -> minorLen
                }
                drawLine(
                    color = tickColor.copy(alpha = if (isMajor) 0.95f else if (isMedium) 0.8f else 0.5f),
                    start = Offset(x, originY),
                    end = Offset(x, originY - len),
                    strokeWidth = if (isMajor) 1.6.dp.toPx() else 1.dp.toPx()
                )
                if (isMajor) {
                    drawTickLabel(
                        paint = labelPaint,
                        color = labelTextColor,
                        x = x + 4.dp.toPx(),
                        // 两行字画在刻度上方，anchor 由 baseline 手工回推。
                        baseline = originY - majorLen - 12.dp.toPx(),
                        lines = listOf("$dp dp", "${pxOf(dp)} px")
                    )
                }
                dp += stepDp
            }
        }
    }
}

/**
 * 画一段「dp + px」两行小字。
 *
 * 不直接写 `drawText` 的原因：这里要多行、要贴刻度、要按主题换色，
 * 而原生 [Paint] 只接受一次一行的 drawText，包一层能让调用处保持短小。
 */
private fun DrawScope.drawTickLabel(
    paint: Paint,
    color: Color,
    x: Float,
    baseline: Float,
    lines: List<String>
) {
    paint.color = color.toArgb()
    val lineHeight = paint.textSize * 1.05f
    lines.forEachIndexed { index, text ->
        drawContext.canvas.nativeCanvas.drawText(text, x, baseline + index * lineHeight, paint)
    }
}

// ---------------------------------------------------------------------------
// 系统 insets 读取
// ---------------------------------------------------------------------------

/** 本工具需要读的三段系统 insets（像素；已按「忽略可见性」读取）。 */
private data class RulerInsets(
    /** 状态栏高度（px）。 */
    val statusBarPx: Int,
    /** 导航栏高度（px）。 */
    val navBarPx: Int
)

/**
 * 读一次系统栏尺寸。
 *
 * ## 为什么是「只读一次 + 按屏幕配置重读」
 *
 * 这是"打开工具那一刻"的机器状态，量尺期间不会变；但要跟随旋转 / 分屏尺寸变化，
 * 所以把 `ScreenWidthDp` / `orientation` 放进 `remember` 的 key 里。
 *
 * ## 为什么用 `getInsetsIgnoringVisibility`
 *
 * 见文件头 KDoc：系统栏被隐藏后普通 `getInsets` 会返回 0，
 * 而这里要的是"状态栏本来多高"，与可见性无关。
 */
@Composable
private fun rememberRulerInsets(): RulerInsets {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    return remember(view, configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        val windowInsets = ViewCompat.getRootWindowInsets(view)
        RulerInsets(
            statusBarPx = windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
                ?.top ?: 0,
            navBarPx = windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
                ?.bottom ?: 0
        )
    }
}

/** 显示方向。 */
private enum class RulerAxis(val label: String) {
    /** 纵向 + 横向都显示（默认）。 */
    BOTH("双向"),

    /** 只显示纵向高度标尺。 */
    VERTICAL_ONLY("仅纵向"),

    /** 只显示横向宽度标尺。 */
    HORIZONTAL_ONLY("仅横向")
}

/** 可选的刻度密度（dp）。8dp 适合量控件高度，16dp 适合快速看大区块。 */
private val STEP_OPTIONS = listOf(8, 16)

/** 默认刻度密度：8dp（需求原文首选值）。 */
private const val DEFAULT_STEP_DP = 8

/**
 * 「常见控件高度」参考线（从状态栏下沿起算，dp）。
 *
 * 48dp = 列表行最小高度；56dp = 通用列表行；64dp = M3 顶栏高度。
 * 这三个值来自 `UiInspectorScreen` 的 UI 规范分区，与真实设计令牌同源。
 */
private val KEY_COMPONENT_HEIGHTS_DP = listOf(48, 56, 64)

/** 保留一位小数（density 这类值显示成 3.0 / 2.75 更好读）。 */
private fun format1(value: Float): String = "%.1f".format(value)
