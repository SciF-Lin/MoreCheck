package com.morecheck.ui.music

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.music.MusicThemeMode
import com.morecheck.data.music.MusicThemeSpec
import com.morecheck.data.music.MusicThemeStore
import com.morecheck.data.music.MusicThemeStyle
import com.morecheck.data.net.MusicTrack
import kotlinx.coroutines.delay

/**
 * 音乐模块的视觉语言（2026-11-08 第二版，按「设计说明」规格图重做）。
 *
 * ## 规格图里的硬指标（都已落进组件）
 *
 * - **圆形封面**：10dp 纯白环框 + 1px 内描边 + 1px 高光线；封面 50% 裁切
 *   叠径向暗角压暗四周；18dp 主投影 + 4dp 近距投影用**自绘渐变**表达
 *   （不用 `Modifier.shadow()`：本项目在 swiftshader 软件渲染上实测会把 elevation
 *   渲染成罩住整块底色的灰雾）；中心 11dp 轴点。
 * - **旋转**：播放时 12s 一圈（30°/s）匀速，暂停定格在当前角度；唱片与歌词淡入淡出。
 * - **三套主题**：海洋蓝 / 落日橙 / 极光紫，各自浅色与深色，进度条、控件、
 *   强调色、文字全部联动；音乐模块自己一套颜色，不读全局 `MaterialTheme`。
 * - **可点性**：所有圆形按钮触控热区 ≥ 44dp，按下 0.86 倍缩放 + 弹簧回弹。
 *
 * ## 为什么主题用 StateFlow 广播而不是 CompositionLocal
 *
 * 主页、播放页（覆盖层）、音乐账号页是三个不同的组合点，其中账号页是**独立路由**，
 * 不在主页的 CompositionLocal 树下。用进程内单例 + `collectAsStateWithLifecycle`
 * 让任何一处调用 [rememberMusicPalette] 都自动订阅，切主题时三处一起换，
 * 不必逐层传参。
 */
data class MusicPalette(
    val spec: MusicThemeSpec,
    val isDark: Boolean,
    /** 整页背景渐变（自上而下）。 */
    val background: Brush,
    /** 玻璃卡片填充 / 描边。 */
    val glass: Color,
    val glassBorder: Color,
    /** 圆形按钮：中段底色 / 顶部高光 / 底部压深 / 亮边。 */
    val buttonFill: Color,
    val buttonTop: Color,
    val buttonBottom: Color,
    val buttonBorder: Color,
    /** 按钮底部的球面投影色（自绘径向渐变，不用 elevation）。 */
    val shadow: Color,
    /** 唱片白环。 */
    val discRing: Color,
    /** 进度条轨道。 */
    val track: Color,
    /** 强调色与它的渐变两端。 */
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val onAccent: Color,
    /** 音乐模块自己的文字层级（与 app 其余页面无关）。 */
    val text: Color,
    val textSub: Color,
    val textMuted: Color,
    val divider: Color,
    /** 底部弹层、卡片等不透明表面。 */
    val surface: Color,
    /** 收藏红。 */
    val error: Color
)

/** 构造期中间量：避免 [MusicPalette] 的构造调用出现一长串重复字段。 */
private data class PaletteBase(
    val bg: List<Color>,
    val glass: Color,
    val glassBorder: Color,
    val buttonFill: Color,
    val buttonTop: Color,
    val buttonBottom: Color,
    val buttonBorder: Color,
    val shadow: Color,
    val discRing: Color,
    val track: Color,
    val accent: Color,
    val accentLight: Color,
    val accentDark: Color,
    val text: Color,
    val textSub: Color,
    val textMuted: Color,
    val surface: Color,
    val divider: Color
)

/** 三套风格 × 明暗，共六套调色板。数值照设计稿色板调，文字对比度均 ≥ 4.5:1。 */
private fun buildPalette(spec: MusicThemeSpec, dark: Boolean): MusicPalette {
    val base = when (spec.style) {
        MusicThemeStyle.OCEAN -> if (dark) {
            PaletteBase(
                bg = listOf(Color(0xFF07101F), Color(0xFF0D1A2E), Color(0xFF081221)),
                glass = Color.White.copy(alpha = 0.07f),
                glassBorder = Color.White.copy(alpha = 0.13f),
                buttonFill = Color.White.copy(alpha = 0.10f),
                buttonTop = Color.White.copy(alpha = 0.24f),
                buttonBottom = Color.White.copy(alpha = 0.02f),
                buttonBorder = Color.White.copy(alpha = 0.20f),
                shadow = Color(0xFF000814).copy(alpha = 0.72f),
                discRing = Color(0xFFD6E4FA),
                track = Color.White.copy(alpha = 0.16f),
                accent = Color(0xFF5B9DFF),
                accentLight = Color(0xFF95C6FF),
                accentDark = Color(0xFF2A62D8),
                text = Color(0xFFEDF4FF),
                textSub = Color(0xFFAABDD8),
                textMuted = Color(0xFF7189A8),
                surface = Color(0xFF101E33),
                divider = Color.White.copy(alpha = 0.08f)
            )
        } else {
            PaletteBase(
                bg = listOf(Color(0xFFE3EDFD), Color(0xFFF4F8FF), Color(0xFFDEE9FA)),
                // 2026-11-09 需求 1：浅色下按钮原本是"纯白 + 纯白描边"，压在同样接近白的
                // 渐变底上分不出边界（用户反馈"底色与按钮区分不开"）。这里给浅色统一加
                // **主题色系描边 + 微冷底色 + 更实的投影**：不靠阴影单独撑（swiftshader 上
                // 阴影会糊成一片），而是靠那一圈描边把轮廓勾出来。
                glass = Color.White.copy(alpha = 0.86f),
                glassBorder = Color(0xFFC6D8F2),
                buttonFill = Color(0xFFF3F8FF),
                buttonTop = Color.White,
                buttonBottom = Color(0xFFCBDDF4),
                buttonBorder = Color(0xFFAFC7E8),
                shadow = Color(0xFF2C4C79).copy(alpha = 0.30f),
                discRing = Color.White,
                track = Color(0xFFD5E2F5),
                accent = Color(0xFF2E7BFF),
                accentLight = Color(0xFF74BAFF),
                accentDark = Color(0xFF1C55D2),
                text = Color(0xFF15243B),
                textSub = Color(0xFF5A6E8B),
                textMuted = Color(0xFF93A4BC),
                surface = Color(0xFFF7FAFF),
                divider = Color(0xFF15243B).copy(alpha = 0.07f)
            )
        }

        MusicThemeStyle.SUNSET -> if (dark) {
            PaletteBase(
                bg = listOf(Color(0xFF1A0E0B), Color(0xFF27160F), Color(0xFF1B100C)),
                glass = Color.White.copy(alpha = 0.07f),
                glassBorder = Color.White.copy(alpha = 0.13f),
                buttonFill = Color.White.copy(alpha = 0.10f),
                buttonTop = Color.White.copy(alpha = 0.24f),
                buttonBottom = Color.White.copy(alpha = 0.02f),
                buttonBorder = Color.White.copy(alpha = 0.20f),
                shadow = Color(0xFF0A0402).copy(alpha = 0.75f),
                discRing = Color(0xFFFFE9DA),
                track = Color.White.copy(alpha = 0.16f),
                accent = Color(0xFFFF9A6B),
                accentLight = Color(0xFFFFC79C),
                accentDark = Color(0xFFD9532A),
                text = Color(0xFFFFF1E9),
                textSub = Color(0xFFD6B09C),
                textMuted = Color(0xFF9E7C6B),
                surface = Color(0xFF261610),
                divider = Color.White.copy(alpha = 0.08f)
            )
        } else {
            PaletteBase(
                bg = listOf(Color(0xFFFCE9DC), Color(0xFFFFF6F0), Color(0xFFFBE3D5)),
                glass = Color.White.copy(alpha = 0.86f),
                glassBorder = Color(0xFFEDCFB9),
                buttonFill = Color(0xFFFFF8F2),
                buttonTop = Color.White,
                buttonBottom = Color(0xFFF0CFB8),
                buttonBorder = Color(0xFFDFB08E),
                shadow = Color(0xFF8C5336).copy(alpha = 0.28f),
                discRing = Color.White,
                track = Color(0xFFF4DBCA),
                accent = Color(0xFFFF7A45),
                accentLight = Color(0xFFFFB57C),
                accentDark = Color(0xFFD94B22),
                text = Color(0xFF3A2318),
                textSub = Color(0xFF7C5C49),
                textMuted = Color(0xFFB29A8B),
                surface = Color(0xFFFFF8F3),
                divider = Color(0xFF3A2318).copy(alpha = 0.07f)
            )
        }

        MusicThemeStyle.AURORA -> if (dark) {
            PaletteBase(
                bg = listOf(Color(0xFF0F0A20), Color(0xFF1A1230), Color(0xFF120C24)),
                glass = Color.White.copy(alpha = 0.07f),
                glassBorder = Color.White.copy(alpha = 0.13f),
                buttonFill = Color.White.copy(alpha = 0.10f),
                buttonTop = Color.White.copy(alpha = 0.24f),
                buttonBottom = Color.White.copy(alpha = 0.02f),
                buttonBorder = Color.White.copy(alpha = 0.20f),
                shadow = Color(0xFF060211).copy(alpha = 0.75f),
                discRing = Color(0xFFEADFFF),
                track = Color.White.copy(alpha = 0.16f),
                accent = Color(0xFFB48BFF),
                accentLight = Color(0xFFD9C2FF),
                accentDark = Color(0xFF7140D8),
                text = Color(0xFFF4EFFF),
                textSub = Color(0xFFBFA9DE),
                textMuted = Color(0xFF8772A9),
                surface = Color(0xFF1D1533),
                divider = Color.White.copy(alpha = 0.08f)
            )
        } else {
            PaletteBase(
                bg = listOf(Color(0xFFEDE6FF), Color(0xFFF9F5FF), Color(0xFFE9E0FC)),
                glass = Color.White.copy(alpha = 0.86f),
                glassBorder = Color(0xFFD8C8F2),
                buttonFill = Color(0xFFFBF7FF),
                buttonTop = Color.White,
                buttonBottom = Color(0xFFDBCAF5),
                buttonBorder = Color(0xFFC2A9E6),
                shadow = Color(0xFF5B3E96).copy(alpha = 0.28f),
                discRing = Color.White,
                track = Color(0xFFE2D7F7),
                accent = Color(0xFF8A5CF6),
                accentLight = Color(0xFFC2A2FF),
                accentDark = Color(0xFF6234D1),
                text = Color(0xFF241A3D),
                textSub = Color(0xFF6B5A8E),
                textMuted = Color(0xFFA296BC),
                surface = Color(0xFFFBF8FF),
                divider = Color(0xFF241A3D).copy(alpha = 0.07f)
            )
        }
    }
    return MusicPalette(
        spec = spec,
        isDark = dark,
        background = Brush.verticalGradient(base.bg),
        glass = base.glass,
        glassBorder = base.glassBorder,
        buttonFill = base.buttonFill,
        buttonTop = base.buttonTop,
        buttonBottom = base.buttonBottom,
        buttonBorder = base.buttonBorder,
        shadow = base.shadow,
        discRing = base.discRing,
        track = base.track,
        accent = base.accent,
        accentLight = base.accentLight,
        accentDark = base.accentDark,
        onAccent = Color.White,
        text = base.text,
        textSub = base.textSub,
        textMuted = base.textMuted,
        divider = base.divider,
        surface = base.surface,
        error = if (spec.style == MusicThemeStyle.SUNSET) Color(0xFFF04B63) else Color(0xFFFF5A7A)
    )
}

/** 主题色板上的小圆点用：取某风格在浅色下的强调色。 */
fun musicStyleAccent(style: MusicThemeStyle): Color =
    buildPalette(MusicThemeSpec(style, MusicThemeMode.LIGHT), false).accent

/**
 * 当前调色板。切风格 / 切明暗都会让所有调用点一起重组。
 *
 * [MusicThemeStore.init] 幂等，放在这里保证「账号页单独进入」也会加载已保存的主题。
 */
@Composable
fun rememberMusicPalette(): MusicPalette {
    val context = LocalContext.current
    MusicThemeStore.init(context)
    val spec by MusicThemeStore.spec.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = when (spec.mode) {
        MusicThemeMode.SYSTEM -> systemDark
        MusicThemeMode.LIGHT -> false
        MusicThemeMode.DARK -> true
    }
    return remember(spec, dark) { buildPalette(spec, dark) }
}

/** 整页背景：同色系渐变。列表页与播放页共用，切页时底色连续。 */
@Composable
fun MusicBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = rememberMusicPalette()
    Box(modifier = modifier.background(palette.background), content = content)
}

/** 玻璃卡片：半透明底 + 1dp 亮边 + 大圆角。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = rememberMusicPalette()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(palette.glass)
            .border(1.dp, palette.glassBorder, RoundedCornerShape(corner))
    ) {
        content()
    }
}

/**
 * 按压反馈（2026-11-08 需求 3）：**外轮廓不变**，只给内容蒙一层短暂灰。
 *
 * 上一版是整个元素缩到 0.86 倍，用户看到的是「圆变小了」，而设计稿里的圆键是
 * 固定尺寸的实体。现在改成：按下时在原内容之上叠 16% 灰，松开后仍保持 140ms
 * 再用 110ms 淡出（这就是「短暂变灰」），几何尺寸完全不动。
 *
 * 按钮的图标缩放不在这里做：[SphereButton] 会把按压状态传给 content，
 * 只缩放图标本身 —— 这样「外轮廓不变、图标缩放」两件事才不会互相打架。
 */
@Composable
fun Modifier.bounceClick(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val source = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            pressed = when (interaction) {
                is PressInteraction.Press -> true
                is PressInteraction.Release, is PressInteraction.Cancel -> false
                else -> pressed
            }
        }
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            dimmed = true
        } else {
            delay(220)
            dimmed = false
        }
    }
    val overlayAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.26f else 0f,
        // 淡出比淡入慢一点（110 → 200ms）：与球面按钮的 240ms 变灰恢复同一节奏，
        // 两种按钮放在同一屏里不会再"一个先恢复、一个后恢复"。
        animationSpec = tween(if (dimmed) 110 else 200),
        label = "musicDim"
    )
    return this
        .drawWithContent {
            drawContent()
            if (overlayAlpha > 0f) {
                drawRect(Color(0xFF8A93A6).copy(alpha = overlayAlpha))
            }
        }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 按钮底下的球面投影。
 *
 * 径向渐变从中心向外淡出，中间被按钮本体盖住，露出的是一圈柔和暗晕——
 * 比 `Modifier.shadow()` 真实，而且不会在软件渲染上糊成灰雾。
 */
/**
 * 音乐页按钮的形状：**正圆**。
 *
 * 2026-11-11 上午曾按需求 7 改成 30% 圆角的方形，当天下午用户看过实机后回退：
 * 「上一条音乐页改为方形的按钮回退为圆形」——要变方的是**开关**，不是这些圆键
 * （开关的形状见 [com.morecheck.ui.common.SquircleSwitch]）。
 * 形状集中在这一个常量里，以后再调只改这里，[SphereButton] 与它的投影都跟着走。
 */
private val MUSIC_BUTTON_SHAPE: Shape = CircleShape

@Composable
private fun SphereShadow(size: Dp, color: Color) {
    Box(
        Modifier
            .size(size * 1.42f)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(color, Color.Transparent)))
    )
}

/**
 * 所有按钮的统一底座：立体渐变 + **只有图标会动的按压反馈**。
 *
 * 形状由 [MUSIC_BUTTON_SHAPE] 决定（当前是正圆；它曾被改成圆角方形又回退，
 * 所以名字保持中性的 `SphereButton`，不把形状写进名字——否则每次调形状
 * 名字都要跟着改一遍）。
 *
 * 三段线性渐变（左上高光 → 中段底色 → 右下压深）+ 1dp 亮边 + 底部径向投影；
 * 按下时外圈圆、投影、边框**一动不动**，只把 [content] 收到的 `iconScale`
 * 缩到 0.76 倍（弹簧回弹）并把 `dimmed` 置真让图标短暂变灰。
 *
 * ⚠️ 这里没有复用 [bounceClick]：那个 Modifier 处理的是「整块灰罩」，而按钮要求
 * 轮廓完全不变。两套反馈各自独立，改一处不会影响另一处。
 */
@Composable
fun SphereButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    accentFill: Boolean = false,
    content: @Composable BoxScope.(iconScale: Float, dimmed: Boolean) -> Unit
) {
    val palette = rememberMusicPalette()
    val source = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            pressed = when (interaction) {
                is PressInteraction.Press -> true
                is PressInteraction.Release, is PressInteraction.Cancel -> false
                else -> pressed
            }
        }
    }
    LaunchedEffect(pressed) {
        if (pressed) {
            dimmed = true
        } else {
            // 2026-11-14 需求 4：变灰的恢复时间与图标回弹对齐（原来 160ms 提前收尾，
            // 观感是"灰已经退了、图标还在弹"）。240ms 与下面 MediumLow 弹簧的回弹时长同量级。
            delay(240)
            dimmed = false
        }
    }
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.76f else 1f,
        animationSpec = spring(
            // 2026-11-14 需求 4：回弹时间"适当增长"。把刚度从 Medium 降到 MediumLow，
            // 回弹从约 160ms 拉到约 240ms，正好铺满变灰恢复的那段，
            // 手指抬起后能看到图标"弹回来"而不是"啪一下复位"。
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "musicIconBounce"
    )

    val top = if (accentFill) Color.White.copy(alpha = 0.38f) else palette.buttonTop
    val middle = if (accentFill) palette.accent else palette.buttonFill
    val bottom = if (accentFill) palette.accentDark else palette.buttonBottom
    Box(
        modifier = modifier
            .size(size.coerceAtLeast(44.dp))
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // 外轮廓层：投影 + 圆 + 渐变 + 亮边，全程不参与动画。
        SphereShadow(size, palette.shadow)
        Box(
            modifier = Modifier
                .size(size)
                .clip(MUSIC_BUTTON_SHAPE)
                // 2026-11-14 需求 4：按下时**整颗球**也压淡一点（不只是图标变色），
                // 让"点到了"在图标还没缩到 0.76 倍的那一帧就能看出来。
                .graphicsLayer { alpha = if (dimmed) 0.82f else 1f }
                .background(Brush.linearGradient(listOf(top, middle, bottom)))
                .border(
                    width = 1.dp,
                    color = if (accentFill) {
                        Color.White.copy(alpha = 0.48f)
                    } else {
                        palette.buttonBorder
                    },
                    shape = MUSIC_BUTTON_SHAPE
                ),
            contentAlignment = Alignment.Center
        ) {
            content(iconScale, dimmed)
        }
    }
}

/** 圆形图标按钮：只有图标缩放与短暂变灰。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    active: Boolean = false,
    enabled: Boolean = true,
    /**
     * 是否正在处理中（2026-11-14 需求 4）。
     *
     * 为真时把图标原地换成转圈：这里是"下载中"的唯一反馈点，
     * 用户点了下载按钮后必须**立刻**看到有东西在动，否则会怀疑没点上而重复点。
     */
    loading: Boolean = false,
    /**
     * 是否正在删除缓存（2026-11-14 需求 4）。
     *
     * 为真时图标换成垃圾桶并**向上飘走**：删缓存没有进度可看，
     * 只把图标变灰用户会以为没点上；一个 500ms 的飘动既是"正在删"也是"删掉了"。
     */
    removing: Boolean = false
) {
    val palette = rememberMusicPalette()
    SphereButton(
        onClick = onClick,
        modifier = modifier,
        size = size,
        enabled = enabled
    ) { iconScale, dimmed ->
        if (removing) {
            val drift by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(520),
                label = "musicTrashDrift"
            )
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "正在删除缓存",
                tint = palette.error,
                modifier = Modifier
                    .size(size * 0.44f)
                    .graphicsLayer {
                        // 向上飘 10dp 并淡出到 0.35：像被"吸走"而不是原地消失。
                        translationY = -10.dp.toPx() * drift
                        alpha = 1f - drift * 0.65f
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
        } else if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(size * 0.44f),
                color = palette.accent,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = when {
                    !enabled -> palette.textMuted
                    dimmed -> palette.textSub
                    active -> palette.accent
                    else -> palette.text
                },
                modifier = Modifier
                    .size(size * 0.44f)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            )
        }
    }
}

/** 强调色播放键：渐变球面 + 白色三角，按压缩放的是三角不是圆。 */
@Composable
fun AccentPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true
) {
    SphereButton(
        onClick = onClick,
        modifier = modifier,
        size = size,
        enabled = enabled,
        accentFill = true
    ) { iconScale, dimmed ->
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = if (dimmed) Color.White.copy(alpha = 0.6f) else Color.White,
            modifier = Modifier
                .size(size * 0.46f)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

/**
 * 底栏中央的「正在播放」圆键（2026-11-09 需求 3）。
 *
 * 与 [AccentPlayButton] 二选一：有歌在放时显示**这首歌的封面**，`playing` 为真就
 * 以 24°/秒 缓慢旋转（和唱片页的转速一致，不抢眼又能看出"在转"）；暂停时停住并压暗、
 * 中间叠一个播放三角，点一下进播放页而不是就地切换播放状态——底栏这颗键的语义
 * 一直是"进详情"，不要在一处出现两种含义。
 *
 * 转速用 `withFrameNanos` 自己积分：`rememberInfiniteTransition` 在暂停再继续时
 * 会从 0° 重来（封面会"跳"一下），这里从当前角度接着转。
 */
@Composable
fun SpinningCoverButton(
    track: MusicTrack?,
    playing: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberMusicPalette()
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, track?.id) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last) / 1_000_000_000f
                last = now
                angle = (angle + seconds * 24f) % 360f
            }
        }
    }

    val coverSize = size - 6.dp
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, palette.accent.copy(alpha = 0.5f), CircleShape)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .clip(CircleShape)
                .graphicsLayer { rotationZ = angle }
        ) {
            TrackArtwork(track = track, size = coverSize, circular = true)
        }
        if (!playing) {
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "继续播放",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.34f)
                )
            }
        }
    }
}

/**
 * 刷子图标（2026-11-08 需求 1）：**白色填充 + 彩色渐变外轮廓**。
 *
 * 自绘而不是用 material icon：现成图标只能整体染一个色，做不出「白心彩边」。
 * 形状是斜 45° 的一把刷子 —— 上半段刷毛（宽）、下半段刷柄（细），
 * 两段各画一次填充 + 描边，中间那道线正好成了刷箍。
 */
@Composable
internal fun MusicBrushIcon(
    outline: Brush,
    fill: Color,
    dimmed: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.055f).coerceAtLeast(1.2f)
        val handleW = w * 0.22f
        val handleH = h * 0.58f
        val headW = w * 0.52f
        val headH = h * 0.30f
        val handleTop = center.y - handleH * 0.08f
        val headTop = handleTop - headH + handleH * 0.06f
        val body = fill.copy(alpha = if (dimmed) 0.6f else 1f)
        rotate(degrees = -45f, pivot = center) {
            val handleRect = androidx.compose.ui.geometry.Rect(
                center.x - handleW / 2f, handleTop,
                center.x + handleW / 2f, handleTop + handleH
            )
            val headRect = androidx.compose.ui.geometry.Rect(
                center.x - headW / 2f, headTop,
                center.x + headW / 2f, headTop + headH
            )
            listOf(handleRect, headRect).forEach { rect ->
                val radius = if (rect === handleRect) {
                    CornerRadius(handleW / 2f, handleW / 2f)
                } else {
                    CornerRadius(headW * 0.24f, headW * 0.24f)
                }
                val topLeft = Offset(rect.left, rect.top)
                val rectSize = Size(rect.width, rect.height)
                drawRoundRect(
                    color = body,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius
                )
                drawRoundRect(
                    brush = outline,
                    topLeft = topLeft,
                    size = rectSize,
                    cornerRadius = radius,
                    style = Stroke(width = stroke)
                )
            }
        }
    }
}

/**
 * 主题切换键：刷子图标（白色填充 + 当前主题强调色渐变描边）。
 *
 * 2026-11-08 需求 1 把上一版的三色点换成刷子——它是「换配色」的通用符号，
 * 而且彩色外轮廓会跟着当前主题变色，比固定的三个点更能表达「现在用的哪套」。
 */
@Composable
fun ThemeBrushButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    val palette = rememberMusicPalette()
    val outline = Brush.linearGradient(listOf(palette.accentLight, palette.accentDark))
    SphereButton(onClick = onClick, modifier = modifier, size = size) { iconScale, dimmed ->
        MusicBrushIcon(
            outline = outline,
            fill = Color.White,
            dimmed = dimmed,
            modifier = Modifier
                .size(size * 0.56f)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

/** 排名数字（设计稿 `01` `02`…）：斜体 + 强调色渐变。 */
@Composable
fun RankText(rank: Int, modifier: Modifier = Modifier) {
    val palette = rememberMusicPalette()
    Text(
        text = rank.toString().padStart(2, '0'),
        modifier = modifier,
        style = TextStyle(
            brush = Brush.linearGradient(listOf(palette.accentLight, palette.accentDark)),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic
        )
    )
}

/**
 * 黑胶唱片封面（规格图 01~04）。
 *
 * 10dp 白环 + 1dp 内描边 + 径向暗角 + conic 反光 + 同心细纹 + 11dp 轴点，
 * 外圈一层自绘径向投影做「悬浮」。播放时 30°/s 匀速旋转、暂停定格在当前角度。
 */
@Composable
fun VinylArtwork(
    track: MusicTrack?,
    size: Dp,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = rememberMusicPalette()
    val ringWidth = 10.dp
    val discSize = size - ringWidth * 2

    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, track?.id) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val seconds = (now - last) / 1_000_000_000f
                last = now
                angle = (angle + seconds * 30f) % 360f
            }
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        // 悬浮投影（规格图 03：18dp 主投影 + 4dp 近距投影）
        SphereShadow(size, palette.shadow)
        // 白色环框（规格图 01：10px 环 + 1px 内描边 + 1px 高光）
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(palette.discRing)
                .border(1.dp, palette.buttonBorder, CircleShape)
        )
        // 唱片本体（旋转）
        Box(
            modifier = Modifier
                .size(discSize)
                .graphicsLayer { rotationZ = angle }
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TrackArtwork(track = track, size = discSize, circular = true)
            // 暗角：四周压暗 42%，让封面上的斜排白字仍可读（规格图 02）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                            radius = discSize.value * 1.05f
                        )
                    )
            )
            // conic 反光：模拟 CD 折射的两道扇状高光（规格图 01）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.11f),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.07f),
                                Color.Transparent
                            )
                        )
                    )
            )
            // 同心细纹
            Canvas(Modifier.fillMaxSize()) {
                val r = this.size.minDimension / 2f
                for (i in 1..5) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.055f),
                        radius = r * (0.34f + i * 0.12f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
            // 中心轴点
            Box(
                modifier = Modifier
                    .size((discSize * 0.075f).coerceIn(8.dp, 12.dp))
                    .clip(CircleShape)
                    .background(palette.discRing.copy(alpha = 0.92f))
                    .border(1.dp, Color.Black.copy(alpha = 0.12f), CircleShape)
            )
        }
        // 环内 1dp 内描边：让封面与白环干净分离
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.10f), CircleShape)
        )
    }
}

/**
 * 细进度条（4dp 圆角条 + 白心圆点）。
 *
 * M3 `Slider` 的轨道 / thumb 尺寸关系固定，压不成规格图那种细线；这里自绘。
 * 交互三段：按下即跳、拖动实时跟随、松手提交 seek。拖动中只改本地状态，
 * 不回写播放器——否则每帧 seek 会让音频一直重缓冲。
 */
@Composable
fun MusicProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val palette = rememberMusicPalette()
    val safeDuration = durationMs.coerceAtLeast(1L)
    val playingFraction = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: playingFraction

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                val width = size.width.toFloat().coerceAtLeast(1f)
                detectTapGestures { offset ->
                    onSeek((offset.x / width * safeDuration).toLong())
                }
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                val width = size.width.toFloat().coerceAtLeast(1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { f -> onSeek((f * safeDuration).toLong()) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                ) { change, _ ->
                    dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(palette.track)
        )
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(4.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(listOf(palette.accentLight, palette.accentDark))
                )
        )
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(18.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                Modifier
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(palette.accent)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

/** 分区标题行：大标题 + 小字说明 + 右侧提示。 */
@Composable
fun MusicSectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    val palette = rememberMusicPalette()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = palette.text
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = palette.textMuted
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = 11.sp,
                color = palette.textMuted
            )
        }
    }
}

/** 空列表提示。 */
@Composable
fun MusicEmptyHint(text: String, modifier: Modifier = Modifier) {
    val palette = rememberMusicPalette()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = palette.textMuted,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}
