package com.morecheck.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.SplashTextFont
import com.morecheck.data.SplashTextStyle
import com.morecheck.di.appContainer

/**
 * 读取「开屏显示文字」的设置（2026-10-27 需求）。
 *
 * 四处开屏（应用启动 / 实验室页面 / 锁屏解锁前 / 三个网页游戏）都调这一个函数，
 * 保证「改一处、四处同步」——这是需求里"自定义开屏显示文字"能被理解的唯一前提。
 *
 * ## 首帧为什么先给默认值
 *
 * DataStore 是异步读的，第一帧必然还没有真值。这里 `initialValue` 给
 * [SplashTextStyle.DEFAULT]（= 改造前的观感）而不是空串或"未就绪"哨兵：
 * 开屏层本身带淡入，首帧本来就几乎不可见；而给空串会让某一版在慢设备上
 * 出现"开屏闪一下空白"。
 *
 * 代价如实记录：如果用户把文案改成了别的，冷启动时理论上会有一帧还是 Hello World。
 * 实测这一帧落在开屏淡入（420ms）之内，肉眼看不到；要彻底消除得把设置同步读到
 * Application 启动路径上，为一个显示细节引入阻塞读不划算。
 */
@Composable
fun rememberSplashTextStyle(): SplashTextStyle {
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.settingsRepository }
    val style by repository.splashTextStyle
        .collectAsStateWithLifecycle(initialValue = SplashTextStyle.DEFAULT)
    return style
}

/**
 * 开屏文字的统一渲染（四处开屏共用）。
 *
 * 只负责**文字本身**：字号 / 字体 / 单多行 / 文案都从 [style] 来，
 * 颜色与动效由调用方决定（启动页要浅色底深字、游戏页要跟着 chrome 档位换底色，
 * 这些差异属于各自的页面，不该塞进这里）。
 *
 * ## 单行 / 多行的具体口径
 *
 * | 模式 | 换行符 | 自动换行 | 最大行数 | 超出 |
 * | --- | --- | --- | --- | --- |
 * | 单行（默认） | 替换成空格 | 否（`softWrap = false`） | 1 | 省略号 |
 * | 多行 | 生效 | 是 | 4 | 省略号 |
 *
 * 单行模式**把换行符换成空格**而不是直接裁掉：用户从多行切到单行时，
 * 两行字应该合成一行显示，而不是只剩第一行——后者看起来像"后半句丢了"。
 *
 * 多行模式给 4 行上限：开屏层是**无滚动、纯居中**的一层，行数再多就会被屏幕裁掉
 * （裁掉和省略号对用户是两件事：前者让人以为布局坏了）。文案本身也已限长
 * （[SplashTextStyle.MAX_TEXT_LENGTH]），4 行只是为了兜住"字号拉到 72sp"的极端情况。
 *
 * @param color 文字颜色，由调用方按各自底色给（这里不猜深浅）。
 */
@Composable
fun SplashTextLabel(
    style: SplashTextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = if (style.multiline) style.text else style.text.replace('\n', ' '),
        color = color,
        fontSize = style.sizeSp.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = style.font.toFontFamily(),
        textAlign = TextAlign.Center,
        maxLines = if (style.multiline) SPLASH_TEXT_MULTILINE_MAX_LINES else 1,
        softWrap = style.multiline,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** 多行模式的行数上限（理由见 [SplashTextLabel] 的 KDoc）。 */
private const val SPLASH_TEXT_MULTILINE_MAX_LINES = 4

/**
 * 字体档位 → Compose 字体族。
 *
 * 三档都是**系统字体族**，不打包任何 ttf：中文字形由系统的 CJK 回退补齐，
 * 因此「支持中文、英文和数字」不需要额外处理（见 [SplashTextFont] 的 KDoc）。
 */
fun SplashTextFont.toFontFamily(): FontFamily = when (this) {
    SplashTextFont.DEFAULT -> FontFamily.SansSerif
    SplashTextFont.SERIF -> FontFamily.Serif
    SplashTextFont.MONOSPACE -> FontFamily.Monospace
}

/**
 * 字体档位的中文名（设置界面用）。
 *
 * 放在 UI 层而不是 `data/SplashTextStyle.kt`：那是持久化契约（只有 `id`），
 * 中文名属于"界面上怎么叫它"，改文案不该触碰数据层。
 */
fun SplashTextFont.displayLabel(): String = when (this) {
    SplashTextFont.DEFAULT -> "默认"
    SplashTextFont.SERIF -> "衬线"
    SplashTextFont.MONOSPACE -> "等宽"
}

/** 设置页里那种"预览块"用的浅色底（与真实开屏同一个 `background` 语义色）。 */
@Composable
internal fun splashPreviewBackground(): Color = MaterialTheme.colorScheme.background
