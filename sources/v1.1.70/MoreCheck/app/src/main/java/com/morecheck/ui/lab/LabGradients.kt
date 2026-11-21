package com.morecheck.ui.lab

import androidx.compose.ui.graphics.Color

/**
 * 实验室背景渐变库 —— 数据抓取自 <https://ohmycolor.app/cn/gradients/>（v1.1.68 需求 6），
 * v1.1.69 需求 2 起按「深浅色模式」重新分池。
 *
 * ## 为什么把色值"抄"进源码，而不是运行时去抓
 *
 * 需求是「抓取该网站的颜色并分类，用于实验室壁纸轮换」。**抓取发生在开发期**：
 * 运行期去请求那个站点会平白多出一个外部依赖（本项目一直坚持「除天气外不外发请求」），
 * 而且墙/挂掉就会让实验室背景变空白。所以这里把抓到的色值固化成常量表。
 *
 * ## 分池口径（v1.1.69 需求 2：**按深浅色模式，不再按时间**）
 *
 * v1.1.68 的口径是「按本机时间分池」（18:00~次日 6:00 算夜间）。需求本轮收紧为：
 * - [VIVID]：**蓝、红、绿、紫等高饱和艳色**——只在**浅色模式**下使用与轮换；
 * - [DARK]：**灰、黑、深蓝等低明度深色**——只在**深色模式**下使用与轮换。
 *
 * 为什么这样分：艳色渐变整体明度高、饱和高，压在浅色模式的卡片与文字上最协调；
 * 而灰黑深色渐变与深色模式的暗底同属一个明度区间，不会出现"深色模式配亮粉渐变"
 * 这种割裂组合。分池条件用**系统深浅色偏好**而不是时间，是因为它才是用户明确
 * 表达过的意图（他在设置里选了深色就是选了深色），时间只是猜测。
 *
 * ## 为什么前景色仍按"起点色明度"单独判定
 *
 * 池内每条渐变的明度仍有差异（例如 VIVID 里的「黄→金」起点很亮、压白字会糊），
 * 所以标题栏前景色不看"在哪个池"，而看**这一条**的起点色明度
 * （见 `LabScreen.backdropForegroundOf`）——分池定大方向，单条定细节，两件事不混。
 *
 * ⚠️ 每条都是 CSS `linear-gradient(135deg, ...)` 的等价写法，方向由
 * `Brush.linearGradient` 的默认起止点天然给出（见 `LabScreen` 的说明）。
 */
internal object LabGradients {

    /**
     * 浅色模式轮换用：高饱和艳色（蓝 / 红 / 绿 / 紫 / 橙 / 青）。
     *
     * 排序无含义（轮换是循环的），但**同一版本的顺序必须稳定**：
     * 顺序变了，"进页面换下一条"的观感会跳变，用户会以为按钮时灵时不灵。
     */
    val VIVID: List<List<Color>> = listOf(
        listOf(Color(0xFFFF6B6B), Color(0xFFFF6B6B), Color(0xFFFF8E53)),
        listOf(Color(0xFFFFA8A8), Color(0xFFFF7979)),
        listOf(Color(0xFFFF9A56), Color(0xFFFF6961)),
        listOf(Color(0xFFFFB347), Color(0xFFFF6347), Color(0xFFFF8C42)),
        listOf(Color(0xFFFFE5B4), Color(0xFFFF7F50)),
        listOf(Color(0xFFFF8C00), Color(0xFFFFA500)),
        listOf(Color(0xFFFF4500), Color(0xFFFFB84D)),
        listOf(Color(0xFFFFBF00), Color(0xFFFF4500), Color(0xFFFF7F00)),
        listOf(Color(0xFFFFD89B), Color(0xFF19547B)),
        listOf(Color(0xFFFDDB92), Color(0xFFD1FDFF)),
        listOf(Color(0xFFFFF700), Color(0xFFFFD700)),
        listOf(Color(0xFFFFFF99), Color(0xFFFFEB3B)),
        listOf(Color(0xFFFFC107), Color(0xFFFF8F00)),
        listOf(Color(0xFFFFEB3B), Color(0xFFFFECB3), Color(0xFFFFF176)),
        listOf(Color(0xFFCCFF00), Color(0xFFFFFF00)),
        listOf(Color(0xFF56AB2F), Color(0xFFA8E6CF)),
        listOf(Color(0xFF00B09B), Color(0xFF96C93D)),
        listOf(Color(0xFF348F50), Color(0xFF56B4D3)),
        listOf(Color(0xFF228B22), Color(0xFF7CFC00), Color(0xFF32CD32)),
        listOf(Color(0xFF9CAF88), Color(0xFFC8D5B9)),
        listOf(Color(0xFF32CD32), Color(0xFFADFF2F)),
        listOf(Color(0xFF2E8B57), Color(0xFF66CDAA)),
        listOf(Color(0xFF74B9FF), Color(0xFF0984E3)),
        listOf(Color(0xFF87CEEB), Color(0xFFF7DC6F), Color(0xFF98D8E8)),
        listOf(Color(0xFF00BFFF), Color(0xFF87CEEB)),
        listOf(Color(0xFF0080FF), Color(0xFF00FFFF)),
        listOf(Color(0xFFE8D5FF), Color(0xFFA4C7FF)),
        listOf(Color(0xFFBA55D3), Color(0xFFDDA0DD)),
        listOf(Color(0xFFFF9A9E), Color(0xFFFECFEF)),
        listOf(Color(0xFFFFB6C1), Color(0xFFFFE4E1)),
        listOf(Color(0xFFFF69B4), Color(0xFFFFB6C1)),
        listOf(Color(0xFFFFC0CB), Color(0xFFFFFFFF), Color(0xFFFFE4E1)),
        listOf(Color(0xFFFA8072), Color(0xFFFFA07A)),
        listOf(Color(0xFFC9D6FF), Color(0xFFE2E2E2)),
        listOf(Color(0xFFD3D3D3), Color(0xFF696969), Color(0xFFA9A9A9)),
        listOf(Color(0xFFE5E4E2), Color(0xFFC0C0C0)),
        listOf(Color(0xFF00C9FF), Color(0xFFFF006E), Color(0xFF92FE9D)),
        listOf(Color(0xFFFFECD2), Color(0xFFFCB69F)),
        listOf(Color(0xFFE3FFE7), Color(0xFFD9E7FF)),
        listOf(Color(0xFFF5F5DC), Color(0xFFDEB887)),
        listOf(Color(0xFFF4A460), Color(0xFFF5F5DC), Color(0xFFDDD3C8)),
        listOf(Color(0xFFFAF0E6), Color(0xFFFDF5E6)),
        listOf(Color(0xFFD2B48C), Color(0xFFBC9A6A)),
        listOf(Color(0xFFFFFDD0), Color(0xFFF5F5F5), Color(0xFFF8F8FF))
    )

    /**
     * 深色模式轮换用：灰 / 黑 / 深蓝 / 深紫等低明度深色。
     *
     * ⚠️ 与 v1.1.68 的 [NIGHT]（旧名）的区别：旧池按"时间"使用且**混入了高饱和艳色**
     * （如「红→橙红」「粉→紫红」），那些颜色在深色模式下与暗底的对比过强、
     * 卡片文字压上去发飘。本池只保留真正"深"的条目。
     */
    val DARK: List<List<Color>> = listOf(
        listOf(Color(0xFF667EEA), Color(0xFF764BA2)),
        listOf(Color(0xFF1E3C72), Color(0xFF2A5298)),
        listOf(Color(0xFF191970), Color(0xFF6495ED), Color(0xFF4169E1)),
        listOf(Color(0xFF9932CC), Color(0xFFDA70D6)),
        listOf(Color(0xFF8A2BE2), Color(0xFFDDA0DD), Color(0xFF9370DB)),
        listOf(Color(0xFF663399), Color(0xFF9966CC)),
        listOf(Color(0xFF4B0082), Color(0xFF8B008B)),
        listOf(Color(0xFFBDC3C7), Color(0xFF2C3E50)),
        listOf(Color(0xFF36454F), Color(0xFF708090)),
        listOf(Color(0xFF2F4F4F), Color(0xFF708090)),
        listOf(Color(0xFF2C3539), Color(0xFF4F4F4F)),
        listOf(Color(0xFF8B0000), Color(0xFFCD5C5C)),
        listOf(Color(0xFFB87333), Color(0xFFFF7F50))
    )
}
