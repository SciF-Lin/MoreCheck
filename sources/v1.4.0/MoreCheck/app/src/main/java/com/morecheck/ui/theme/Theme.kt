package com.morecheck.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 当前主题色方案。
 *
 * 供「固定浅色 / 固定深色」的局部主题页面（启动页、朋友圈）在覆盖配色时
 * 仍能取到用户选择的强调色，而不是一律回退到默认绿。
 */
val LocalAccentColor = staticCompositionLocalOf { AccentColor.DEFAULT }

/**
 * 正文强调黑：浅色模式用**纯黑**（比 [ColorScheme.onSurface] 的 `#191919` 更深），
 * 深色模式沿用 `onSurface`——否则纯黑字落在深色卡片上会看不见。
 *
 * 用 `background` 的明度判断深浅，而不是 `isSystemInDarkTheme()`：
 * 主题可以被用户强制为浅色 / 深色，与系统设置无关。
 *
 * 目前只有「发现」「我的」两个列表页使用，其余页面仍用 `onSurface`。
 */
val MaterialTheme.strongTextColor: Color
    @Composable get() = if (colorScheme.background.luminance() > 0.5f) {
        Color.Black
    } else {
        colorScheme.onSurface
    }

// ---- 微信（WeChat）中性色底 ------------------------------------------------
//
// 设计取向：**扁平、灰底白卡**，刻意避开 Material You 的大面积圆角 + 同色系渐变
// （即用户所说的「谷歌显示样式」）。
//
// 这里定义的是**中性部分**（底色、卡片、文字、分隔线），与主题色无关：
// - `background` / `surface` = 页面灰色（#EDEDED），标题栏与页面同色、无分隔；
// - `surfaceContainer*` = 白色，使 Card / 菜单 / 底部弹层呈现「灰底白卡」的层次。
//
// 强调色由 [withAccent] 在建好中性底之后覆盖上去。
private val NeutralLight = lightColorScheme(
    primary = Color(0xFF07C160),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F5E3),
    onPrimaryContainer = Color(0xFF075E3A),
    inversePrimary = Color(0xFF6FD9A3),

    secondary = Color(0xFF576B95),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7F7EE),
    onSecondaryContainer = Color(0xFF075E3A),

    tertiary = Color(0xFF576B95),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE7F7EE),
    onTertiaryContainer = Color(0xFF3A4A6B),

    background = Color(0xFFEDEDED),
    onBackground = Color(0xFF191919),

    surface = Color(0xFFEDEDED),
    onSurface = Color(0xFF191919),
    surfaceVariant = Color(0xFFFFFFFF),
    // 2026-10-27 评审：原值 #888888 压在灰底 #EDEDED 上只有 **3.03:1**
    // （白卡上 3.54:1），而它是日期、副标题、每个列表行第二行说明的默认色 ——
    // 全站面积最大的"读不清"。改为 #5F5F5F：灰底 5.45:1、白卡 6.39:1。
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceTint = Color(0xFFEDEDED),

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDDDDD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F7F7),
    surfaceContainerHighest = Color(0xFFFFFFFF),

    inverseSurface = Color(0xFF2E2E2E),
    inverseOnSurface = Color(0xFFF7F7F7),

    error = Color(0xFFFA5151),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E7),
    onErrorContainer = Color(0xFF8C1F1F),

    outline = Color(0xFFC8C8C8),
    outlineVariant = Color(0xFFE5E5E5),
    scrim = Color(0xFF000000)
)

private val NeutralDark = darkColorScheme(
    primary = Color(0xFF07C160),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B3D26),
    onPrimaryContainer = Color(0xFF9FE7C4),
    inversePrimary = Color(0xFF0B3D26),

    secondary = Color(0xFF7D90B8),
    onSecondary = Color(0xFF141414),
    secondaryContainer = Color(0xFF1B3327),
    onSecondaryContainer = Color(0xFF9FE7C4),

    tertiary = Color(0xFF7D90B8),
    onTertiary = Color(0xFF141414),
    tertiaryContainer = Color(0xFF1B3327),
    onTertiaryContainer = Color(0xFFC3D3F0),

    background = Color(0xFF111111),
    onBackground = Color(0xFFD8D8D8),

    surface = Color(0xFF111111),
    onSurface = Color(0xFFD8D8D8),
    surfaceVariant = Color(0xFF1E1E1E),
    // 深色下同理：#888888 在 #191919 卡片上 4.96:1 勉强过线，但卡片改亮到 #242424 后
    // 只剩 4.0，所以一并提到 #A8A8A8（#242424 上 5.9:1）。
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceTint = Color(0xFF111111),

    surfaceBright = Color(0xFF2A2A2A),
    surfaceDim = Color(0xFF0C0C0C),
    // 2026-10-27 评审：深色下卡片与页底的分离度只有 **1.07:1**
    // （#191919 / #111111），块与块糊成一片。整组上移一档：
    // 卡片 #242424 对页底 1.21:1，再配合 1dp #2A2A2A 描边（见 CardBorder）。
    surfaceContainerLowest = Color(0xFF161616),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF242424),

    inverseSurface = Color(0xFFE8E8E8),
    inverseOnSurface = Color(0xFF2E2E2E),

    error = Color(0xFFFA5151),
    onError = Color.White,
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFD9D9),

    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2C2C2C),
    scrim = Color(0xFF000000)
)

/**
 * 形状：全应用统一的圆角体系。
 *
 * 演进：最初刻意收窄（4~14dp 的「微信直角卡」观感）；2026-10-25 需求
 * 「文本框与显示页圆角增大（变得更圆）」整体加大到 10~24dp——
 * 文本框（OutlinedTextField 默认取 extraSmall）从 4dp → 10dp，
 * 卡片（Card 默认取 medium）从 8dp → 16dp，底部弹层（extraLarge）从 14dp → 24dp。
 * 在主题层一处生效，全站的输入框 / 卡片 / 弹层同步变圆，无需逐组件改 shape。
 */
private val WeChatShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * 把主题色叠加到中性色板上。
 *
 * 明暗两套都用 `lerp` 从同一个基准色推导，保证：
 * - 深色模式下 `primary` 仍是基准色本身（微信深色模式的绿就是这么处理的），
 *   从而「白字写在主题色实心块上」（打卡按钮）在深浅两种模式下都清晰；
 * - 各容器色（Container）与基准色同色系，不会出现 Material You 那种突兀的撞色。
 */
private fun ColorScheme.withAccent(accent: AccentColor, dark: Boolean): ColorScheme {
    val seed = accent.seed
    return if (!dark) {
        copy(
            primary = seed,
            // 2026-10-27 晚：用户看过实机后要求回退成「绿底白字」。
            // `onAccent()` 现在恒返回白色（对比度代价如实写在它的 KDoc 里），
            // 保留调用形态是为了将来按主题色单独开口子时只改一处。
            onPrimary = onAccent(seed),
            primaryContainer = lerp(seed, Color.White, 0.84f),
            onPrimaryContainer = lerp(seed, Color.Black, 0.45f),
            inversePrimary = lerp(seed, Color.White, 0.35f),
            secondary = seed,
            onSecondary = Color.White,
            secondaryContainer = lerp(seed, Color.White, 0.88f),
            onSecondaryContainer = lerp(seed, Color.Black, 0.40f),
            tertiary = seed,
            onTertiary = Color.White,
            tertiaryContainer = lerp(seed, Color.White, 0.88f),
            onTertiaryContainer = lerp(seed, Color.Black, 0.40f)
        )
    } else {
        copy(
            primary = seed,
            // 与浅色同一口径：种子色本身不变（微信深色就是这么处理的），
            // 上面的字恒为白色（同上，2026-10-27 晚回退）。
            onPrimary = onAccent(seed),
            primaryContainer = lerp(seed, Color.Black, 0.72f),
            onPrimaryContainer = lerp(seed, Color.White, 0.62f),
            inversePrimary = lerp(seed, Color.Black, 0.72f),
            secondary = lerp(seed, Color.White, 0.35f),
            onSecondary = Color.Black,
            secondaryContainer = lerp(seed, Color.Black, 0.75f),
            onSecondaryContainer = lerp(seed, Color.White, 0.62f),
            tertiary = lerp(seed, Color.White, 0.35f),
            onTertiary = Color.Black,
            tertiaryContainer = lerp(seed, Color.Black, 0.75f),
            onTertiaryContainer = lerp(seed, Color.White, 0.62f)
        )
    }
}

/**
 * 应用主题。
 *
 * @param accent 主题色方案，默认绿色。颜色来自用户在「我的 → 设置 → 外观」里的选择。
 */
@Composable
fun MoreCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.DEFAULT,
    content: @Composable () -> Unit
) {
    val base = if (darkTheme) NeutralDark else NeutralLight
    CompositionLocalProvider(LocalAccentColor provides accent) {
        MaterialTheme(
            colorScheme = base.withAccent(accent, darkTheme),
            shapes = WeChatShapes,
            typography = Typography(),
            content = content
        )
    }
}

/**
 * 启动页 / 朋友圈这类「整屏展示」页面专用的局部主题。
 *
 * **跟随当前深浅色模式**（由调用方用背景明度判断后传入 [dark]）：
 * 深色模式下是深底，浅色模式下是浅底；主题色仍然跟随用户选择。
 *
 * 早期这里写死浅色，导致深色模式下启动页仍是浅底 + 主题色字，与整个应用割裂。
 */
@Composable
fun MoreCheckSplashTheme(
    accent: AccentColor = AccentColor.DEFAULT,
    dark: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = (if (dark) NeutralDark else NeutralLight).withAccent(accent, dark = dark),
        shapes = WeChatShapes,
        typography = Typography(),
        content = content
    )
}

/**
 * 「扫一扫」专用主题：**固定深色**，不跟随系统 / 应用的深浅色设置。
 *
 * 原因：相机取景页用浅色会让取景画面刺眼，也会让半透明面板上的文字难以辨认；
 * 相机类页面固定深色是通行做法。页面自身配色（深灰卡片 + 蓝色品牌图标）按设计稿写死，
 * 刻意**不接入主题色** —— 取景页保持中性，才不会与相机画面里的真实颜色打架。
 */
@Composable
fun MoreCheckScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeutralDark,
        shapes = WeChatShapes,
        typography = Typography(),
        content = content
    )
}
