package com.morecheck.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 可选主题色（强调色）。
 *
 * 每个方案只声明**一个基准色**，明暗两套色板由 [MoreCheckTheme] 用 `lerp` 推导，
 * 因此新增一个颜色只要在这里加一行，不需要手写十几项颜色值。
 *
 * 现有 12 种（v1.1.92 起）。主题色影响的是「整体色调」——底部导航栏选中态、打卡按钮、时间文字、各处强调图标；
 * 页面底色与卡片保持微信式的「灰底白卡」，不随主题色变化。
 *
 * [id] 是持久化到 DataStore 的稳定标识，**不要改**（改了会让已保存的选择失效）。
 */
enum class AccentColor(
    val id: String,
    val label: String,
    val seed: Color
) {
    GREEN("green", "绿色", Color(0xFF07C160)),
    BLUE("blue", "蓝色", Color(0xFF1E88E5)),
    CYAN("cyan", "青色", Color(0xFF00A6A6)),
    INDIGO("indigo", "靛色", Color(0xFF3F51B5)),
    PURPLE("purple", "紫色", Color(0xFF7E57C2)),
    PINK("pink", "粉色", Color(0xFFEC407A)),
    ORANGE("orange", "橙色", Color(0xFFFB8C00)),
    /**
     * 红色。
     *
     * 2026-10-27 评审改值：`#E53935` 是**唯一一个白字与深字都不达标**的强调色
     * （白字 4.23:1、深字 4.36:1，AA 需 4.5），意味着它在任何用法下都不可读。
     * 换成 `#C62828` 后白字 5.62:1 —— 色相仍是同一个红，肉眼几乎看不出差别。
     * 详细数据见 `docs/UI评审与优化方案-2026-10-27.md` 第 3.2 节。
     */
    RED("red", "红色", Color(0xFFC62828)),

    // ---- v1.1.92：由 8 种扩到 12 种 -------------------------------------
    //
    // 选取口径：与上面 8 个**在色相或明度上都拉开距离**的那几档。
    // 顺序按色相从暖到冷插不进去，就统一追加在后面——顺序即调色板显示顺序，
    // 追加能保证老用户记住的位置不变（前 8 个原地不动）。
    YELLOW("yellow", "黄色", Color(0xFFF9A825)),
    BROWN("brown", "棕色", Color(0xFF8D6E63)),
    BLUE_GREY("blue_grey", "蓝灰色", Color(0xFF546E7A)),
    WINE("wine", "酒红", Color(0xFFAD1457));

    companion object {
        /** 默认主题色：绿色（与改造前的配色一致）。 */
        val DEFAULT: AccentColor = GREEN

        /** 按 id 取方案；无法识别（含旧版本残留值）时回退到默认色。 */
        fun fromId(id: String?): AccentColor =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
