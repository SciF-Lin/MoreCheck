package com.morecheck.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（2026-10-27 评审落地，见 `docs/UI评审与优化方案-2026-10-27.md`）。
 *
 * ## 为什么要有这个文件
 *
 * 评审时量出来的三个数字：**143 处 `fontSize` 覆盖共 23 种取值**、
 * **14 种 padding**、**174 处硬编码圆角只有 24 处走 `MaterialTheme.shapes`**。
 * 结论是"每页各自决定字号/间距/圆角"——不改这个，任何逐屏优化都会重新漂移。
 *
 * 所以这里只做一件事：**把尺度固定成有限的几档**，新代码一律只用这几档。
 * 存量代码按屏分批替换，不一次性全量改（diff 无法审查）。
 */

/**
 * 排版 7 档。
 *
 * 规则：**一屏之内正文不超过 3 档**；`13sp` 与 `14sp` 不许并存
 * （评审时 13sp 出现 29 次、14sp 16 次、15sp 13 次、16sp 16 次，四档正文混用，
 * 同一列表里相邻两行字号不一致，是"看着有点乱"最直接的来源）。
 *
 * 和 `MaterialTheme.typography` 的关系：那套是 Material3 默认值（本项目的
 * `Theme.kt` 传的就是 `Typography()`），语义名叫 body/label/title，
 * 与"这一行是正文还是说明"对不上，所以另立一套按**用途**命名的样式；
 * 组件里继续用 `MaterialTheme.typography` 也不算错，但**改字号时两处都要看**，
 * 新代码直接用这里。
 */
object AppType {
    /** 时钟 / 倒计时大字。 */
    val display = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

    /** 页面主标题（「我的」「设置」这类）。 */
    val title = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)

    /** 区块标题 / 主问句（「今天打卡了吗？」「我的任务」）。 */
    val heading = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)

    /** 主要正文。 */
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)

    /** 副标题 / 说明文字（列表行的第二行）。 */
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    /** 标签 / 角标。 */
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

    /** 极少数辅助信息（版本号之类）。 */
    val micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
}

/**
 * 间距 6 档，全部是 4 的倍数。
 *
 * 评审时数到 14 种取值（2/4/6/8/10/12/14/16/18/20/22/24/32/40），
 * 其中 14 / 18 / 22 是"半个刻度"，正是它们让纵向节奏看起来不齐。
 */
object Space {
    val xs = 4.dp

    /** 图标与文字之间。 */
    val sm = 8.dp

    /** 行与行之间。 */
    val md = 12.dp

    /** 页面水平内边距、卡片内边距。 */
    val lg = 16.dp

    /** 区块之间。 */
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 形状 4 档 + 胶囊。
 *
 * 规则：小控件 [control]、输入框 [field]、卡片 [card]、弹层 [sheet]。
 * `Theme.kt` 的 `WeChatShapes` 是「按 Material 组件槽位」映射的（extraSmall=输入框…），
 * 这里是「按用途」映射的，二者数值一致，新代码用这套更好读。
 */
object Radius {
    val control = 8.dp
    val field = 12.dp
    val card = 16.dp
    val sheet = 24.dp
    val pill = 999.dp
}

/** WCAG 相对对比度（1:1 ~ 21:1）。 */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 压在强调色**实心块**上的文字/图标色：恒为白色。
 *
 * ## 2026-10-27 晚：已按用户要求回退成「绿底白字」
 *
 * 上一版这里做的是「按对比度择优」——白字压绿色只有 2.38:1，达不到 WCAG AA（4.5），
 * 所以绿/橙/黄/青底上自动改用深字 `#141414`（绿底变 7.73:1）。
 * 数字是达标的，但用户看过实机后明确要求**改回白字**：深字压绿底在观感上"不像按钮"，
 * 与微信那种"实心色块 + 白字"的固定印象不符。视觉一致性优先于对比度数值。
 *
 * 所以现在恒返回 [Color.White]，**代价如实记在这里**：
 * - 绿 `#07C160` 白字 2.38:1、橙 `#FB8C00` 2.37、黄 `#F9A825` 1.97、青 `#00A6A6` 3.00，
 *   均低于 AA 正文门槛；这些色只用在**大号粗体按钮文字**上（约 16sp SemiBold），
 *   按 WCAG 的 large text 口径（3.0）绿/橙/青勉强接近，黄色仍不足；
 * - 靛 / 酒红 / 蓝灰 / 棕 / 红（`#C62828`）等深色系的对比度 4.3~7.0，没有问题。
 *
 * 保留函数形态（而不是让调用点直接写 `Color.White`）是为了：将来若要按"主题色是否偏亮"
 * 单独给黄色开个例外，改**一处**即可；调用点不必知道这条规则。
 */
@Suppress("UNUSED_PARAMETER")
fun onAccent(seed: Color): Color = Color.White

/**
 * 把强调色**当文字/图标**压在 [background] 上时的可读版本。
 *
 * 强调色本身是给"实心块"设计的，直接当浅底上的文字会不可读
 * （绿色 `#07C160` on `#EDEDED` = 2.04:1）。这里逐步压暗，**刚好**达标就停：
 *
 * - 不做统一压暗（比如一律压 40%），因为靛色 / 酒红本来就合格，
 *   统一压暗只会让它们变脏、失去色相辨识度；
 * - 深色模式下 [background] 很暗，压暗永远不达标 —— 调用方应改用
 *   `readableOn(seed, bg)` 的反向（提亮）分支，或直接用 [onAccent] 反色。
 */
fun readableOn(seed: Color, background: Color, minRatio: Float = 4.5f): Color {
    var color = seed
    repeat(16) {
        if (contrastRatio(color, background) < minRatio) {
            color = lerp(color, Color.Black, 0.06f)
        }
    }
    return color
}
