package com.morecheck.ui.web.bili

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.net.BiliLibraryStore

/**
 * B 站模块的独立配色与尺寸（2026-11-18 按逆向参考实现重做）。
 *
 * ## 为什么不用 MaterialTheme.colorScheme
 *
 * 参考实现（`com.lihuan.bili` v15.81）有一套自己的品牌色：粉色 `#fb7299` 与
 * 近黑背景 `#0b0c0f`，与宿主 App 的绿色主题并不相干。直接复用宿主 colorScheme
 * 会让「B 站」看起来像换了皮的同一个 App。因此这里按逆向出的 `res/values` 颜色表
 * 单独建一套 token，**深浅两套数值都取自参考实现**（含 night 变体）。
 *
 * 深浅判定沿用项目既有约定：看 `colorScheme.background` 的明度，
 * 而不是 `isSystemInDarkTheme()`——用户可以在设置里强制浅色/深色。
 */
internal data class BiliColors(
    val page: Color,
    val surface: Color,
    val surface2: Color,
    val pink: Color,
    val pinkBg: Color,
    val pinkDeep: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val divider: Color,
    val headerStart: Color,
    val headerEnd: Color,
    val headerOn: Color,
    val searchFill: Color,
    val chipBg: Color,
    val chipText: Color,
    val navTint: Color,
    val navStroke: Color,
    val navUnselected: Color,
    /** 底部导航选中项的滑动圆底（2026-11-19 需求 2：参考图的浅灰圆）。 */
    val navPill: Color,
    val isDark: Boolean
)

private val LightBiliColors = BiliColors(
    page = Color(0xFFF7F8FA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF4F5F7),
    pink = Color(0xFFFB7299),
    pinkBg = Color(0xFFFFECF1),
    pinkDeep = Color(0xFFF25D85),
    textPrimary = Color(0xFF14161A),
    textSecondary = Color(0xFF5B6068),
    textHint = Color(0xFF93999F),
    divider = Color(0xFFECEDF0),
    headerStart = Color(0xFFFB7299),
    headerEnd = Color(0xFFFF8FB2),
    headerOn = Color(0xFFFFFFFF),
    searchFill = Color(0x33FFFFFF),
    chipBg = Color(0xFFFFFFFF),
    chipText = Color(0xFF5B6068),
    // 2026-11-21 需求 2：恢复半透明玻璃感（上一轮改成不透明，用户要求回滚）。
    // 叠在内容上略偏灰是半透明的固有代价，用下方「底部渐隐遮罩」把底下的内容
    // 先压淡一层来抵消，观感比单纯不透明底更接近官方。
    navTint = Color(0xC9FFFFFF),
    navStroke = Color(0x59FFFFFF),
    navUnselected = Color(0xFF93999F),
    navPill = Color(0xFFE7E9EE),
    isDark = false
)

private val DarkBiliColors = BiliColors(
    page = Color(0xFF0B0C0F),
    surface = Color(0xFF16181D),
    surface2 = Color(0xFF202329),
    pink = Color(0xFFFF7EA5),
    pinkBg = Color(0xFF3A1F29),
    pinkDeep = Color(0xFFFF6A93),
    textPrimary = Color(0xFFECEDF0),
    textSecondary = Color(0xFFA3A8B1),
    textHint = Color(0xFF6E747D),
    divider = Color(0xFF262A31),
    headerStart = Color(0xFF15171C),
    headerEnd = Color(0xFF1B1E25),
    headerOn = Color(0xFFECEDF0),
    searchFill = Color(0x1FFFFFFF),
    chipBg = Color(0xFF202329),
    chipText = Color(0xFFA3A8B1),
    // 深色同理：恢复半透明（2026-11-21 需求 2）。
    navTint = Color(0xC916181D),
    navStroke = Color(0x33FFFFFF),
    navUnselected = Color(0xFF6E747D),
    navPill = Color(0xFF2E323A),
    isDark = true
)

internal val LocalBiliColors = staticCompositionLocalOf { LightBiliColors }

/**
 * B 站自选主题色候选（设置页用）。第一项 0 = 默认品牌粉；其余为 ARGB seed。
 * 与宿主 [com.morecheck.ui.theme.AccentColor] 相互独立：B 站品牌色只影响本模块。
 */
internal val BILI_ACCENT_PALETTE: List<Pair<String, Int>> = listOf(
    "默认粉" to 0,
    "粉色" to 0xFFEC407A.toInt(),
    "红色" to 0xFFC62828.toInt(),
    "橙色" to 0xFFFB8C00.toInt(),
    "黄色" to 0xFFF9A825.toInt(),
    "绿色" to 0xFF07C160.toInt(),
    "青色" to 0xFF00A6A6.toInt(),
    "蓝色" to 0xFF1E88E5.toInt(),
    "靛色" to 0xFF3F51B5.toInt(),
    "紫色" to 0xFF7E57C2.toInt(),
    "酒红" to 0xFFAD1457.toInt(),
    "蓝灰" to 0xFF546E7A.toInt()
)

/** 由 seed 派生一套 B 站配色：只改品牌色相关项，底色/文字/导航保持参考实现口径。 */
private fun withAccent(base: BiliColors, argb: Int): BiliColors {
    if (argb == 0) return base
    val seed = Color(argb)
    val dark = base.isDark
    val pink = if (dark) lerp(seed, Color.White, 0.18f) else seed
    return base.copy(
        pink = pink,
        pinkDeep = lerp(pink, Color.Black, 0.12f),
        pinkBg = if (dark) lerp(seed, Color.Black, 0.72f) else lerp(seed, Color.White, 0.88f),
        // 浅色顶栏渐变跟随品牌色；深色顶栏保持参考实现的近黑渐变（不刺眼）。
        headerStart = if (dark) base.headerStart else pink,
        headerEnd = if (dark) base.headerEnd else lerp(pink, Color.White, 0.25f)
    )
}

/** B 站模块的根：按宿主主题深浅 + 用户自选主题色注入对应配色。 */
@Composable
internal fun BiliTheme(content: @Composable () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val accent by BiliLibraryStore.biliAccentOf(LocalContext.current).collectAsState(initial = 0)
    CompositionLocalProvider(
        LocalBiliColors provides withAccent(if (dark) DarkBiliColors else LightBiliColors, accent),
        content = content
    )
}

/** 便捷取值：`BiliColors` 当前实例。 */
@Composable
internal fun biliColors(): BiliColors = LocalBiliColors.current

/** 尺寸常量，全部照搬参考实现的 dp 值，保证与原 App 视觉一致。 */
internal object BiliDimen {
    // 顶栏
    val topBarPadTop = 10.dp
    val topBarPadBottom = 12.dp
    val topBarPadH = 14.dp
    val avatarTop = 28.dp
    val searchHeight = 36.dp
    val searchRadius = 18.dp

    // 分类胶囊
    val chipBarHeight = 46.dp
    val chipHeight = 30.dp
    val chipRadius = 15.dp
    val chipPadH = 15.dp

    // 双列卡片
    val gridPad = 10.dp
    val gridSpace = 8.dp
    val coverHeight = 100.dp
    val titleHeight = 38.dp
    val titleMarginTop = 7.dp
    val infoMarginTop = 5.dp

    // 底部导航
    val navHeight = 64.dp
    /** 2026-11-20 需求 2：导航条整体向下移一点（离手势条更近），16 → 8dp。 */
    val navMarginBottom = 8.dp
    val navMarginH = 14.dp
    val navPadH = 6.dp
    val navIcon = 23.dp
    val navRadius = 32.dp

    // 播放页
    val playerHeight = 210.dp
    val detailAvatar = 40.dp
    val relatedCoverW = 118.dp
    val relatedCoverH = 68.dp

    /**
     * 播放器控制条（照搬参考 `activity_video.xml` 的 `ctrlBar`）：
     * 整条 64dp、垂直居中、左右 padding 10/8，播放/全屏按钮 28dp（内 padding 4），
     * 进度条触摸高 44dp，倍速 28dp 高，清晰度与弹幕 24dp 高、左右 padding 6。
     */
    val ctrlBarHeight = 64.dp
    val ctrlBarPadStart = 10.dp
    val ctrlBarPadEnd = 8.dp
    val ctrlBtn = 28.dp
    val ctrlBtnPad = 4.dp
    val seekHeight = 44.dp
    val ctrlSpeedHeight = 28.dp
    val ctrlActionHeight = 24.dp
    val ctrlBtnPadH = 6.dp
    val danmakuBtnWidth = 32.dp

    /** 视频区顶部返回按钮：参考 `btnBack` 38dp、内 padding 9dp。 */
    val backBtn = 38.dp
    val backBtnPad = 9.dp

    // 我的页
    val profileAvatar = 62.dp
}

/** 字号常量（参考实现 sp 值）。 */
internal object BiliText {
    val logo = 20.sp
    val logoSub = 12.sp
    val search = 13.sp
    val chip = 14.sp
    val cardTitle = 13.sp
    val cardInfo = 10.sp
    val navLabel = 11.sp
    val sectionTab = 15.sp
    val upName = 14.sp
    val detailTitle = 17.sp
    val detailInfo = 14.sp
    val actionLabel = 14.sp
    val body = 13.sp
    val commentName = 13.sp
    val commentBody = 14.sp
    val commentMeta = 12.sp
    val profileName = 18.sp
    val profileSub = 12.sp
    val profileStat = 16.sp
    val profileStatLabel = 11.sp
    val listItem = 15.sp
}
