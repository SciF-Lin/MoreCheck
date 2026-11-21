package com.morecheck.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 可选主题色（强调色）。
 *
 * 每个方案只声明**一个基准色**，明暗两套色板由 [MoreCheckTheme] 用 `lerp` 推导，
 * 因此新增一个颜色只要在这里加一行，不需要手写十几项颜色值。
 *
 * 主题色影响的是「整体色调」——底部导航栏选中态、打卡按钮、时间文字、各处强调图标；
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
    RED("red", "红色", Color(0xFFE53935));

    companion object {
        /** 默认主题色：绿色（与改造前的配色一致）。 */
        val DEFAULT: AccentColor = GREEN

        /** 按 id 取方案；无法识别（含旧版本残留值）时回退到默认色。 */
        fun fromId(id: String?): AccentColor =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
