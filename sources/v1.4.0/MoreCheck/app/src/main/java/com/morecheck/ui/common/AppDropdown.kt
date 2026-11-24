package com.morecheck.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.ui.theme.strongTextColor

/**
 * 下拉列表里的一项。
 *
 * @param label 主文案（用户选的那一行）。
 * @param hint 可选的一行解释。**下拉菜单里放得下第二行**（`DropdownMenuItem` 的
 *   `text` 是任意 composable），所以「锁定时机」那五档的取舍说明不必砍掉——
 *   这一点与 HANDOVER 里"DropdownMenu 只适合单行选项"的旧结论不同：
 *   那条结论只对**系统 AlertDialog 之外的极窄菜单**成立，M3 的 DropdownMenuItem
 *   自定义 text 后高度会自适应。
 */
data class AppDropdownItem(
    val label: String,
    val hint: String? = null
)

/**
 * 「标题 / 说明 —— 当前值 ⌄」的**下拉列表行**（2026-10-27 用户要求）。
 *
 * ## 为什么抽成公共组件
 *
 * 同一天里有两处从"弹窗单选"改成下拉（设置 → 应用锁 → 锁定时机、
 * 开发者选项 → 日期显示格式），后面还会加（开屏文字 → 字体）。三份各写一遍的话，
 * 行高、置灰系数、菜单圆角迟早漂成三种样子——本项目已经吃过这类亏
 * （见 `DeveloperSwitchRow` 的 KDoc）。
 *
 * ## 为什么不用 `ExposedDropdownMenuBox` + `OutlinedTextField`
 *
 * 那个组合长成**输入框**：有边框、有 label 浮动动画，与设置页里"一行一个开关 /
 * 一行一个入口"的视觉语言不是一套。这里要的是「点这一行、从下面弹出选项」，
 * 与既有 `DeveloperActionRow` 的入口行完全同构，只把右箭头换成"当前值 + 下箭头"。
 *
 * ## DropdownMenu 的定位口径
 *
 * `DropdownMenu` 相对**直接父级**定位（HANDOVER 里踩过的坑），所以这里用一个
 * [Box] 把「行」和「菜单」包在一起：父级就是这一整行，菜单自然贴着行弹出，
 * 而不是跑到屏幕左上角去。菜单宽度固定 [menuWidth] 而不是 `fillMaxWidth`：
 * 铺满整行会让它看起来像一个展开的区块，而不是浮层。
 *
 * ## 置灰
 *
 * `enabled = false` 时整行（含当前值）统一乘 [DISABLED_ALPHA]，并且 `clickable`
 * 直接不挂——置灰的东西点不动是基本预期，比"点了弹个提示"更好。
 *
 * @param title 行标题。
 * @param subtitle 行说明；null / 空串则不占位（与 `DeveloperSwitchRow` 同一口径）。
 * @param selectedLabel 右侧显示的**当前值**文案（不传则只显示下箭头，用于"还没选"的场景）。
 * @param selectedIndex 当前选中项在 [items] 里的下标（用于菜单里高亮那一项）；
 *   -1 表示没有选中项。
 * @param items 选项列表。
 * @param onSelect 选中回调，参数是 `items` 里的**下标**。
 * @param menuWidth 菜单宽度；默认 240dp，窄屏下自行收窄由系统处理。
 */
@Composable
fun AppDropdownRow(
    title: String,
    subtitle: String?,
    selectedLabel: String,
    selectedIndex: Int,
    items: List<AppDropdownItem>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    menuWidth: Dp = 240.dp
) {
    var expanded by remember { mutableStateOf(false) }

    // 深浅判定用**页面底色**的亮度，而不是 `isSystemInDarkTheme()`：主题可以被用户
    // 在设置里强制指定，跟着实际生效的配色走才不会判错。
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // 深色下菜单底色朝白提 12%（#262626 → 约 #3B3B3B）：深色页底是 #111111，
    // 原先的 #262626 几乎糊在一起（用户要求「选项底色浅一点，和背景区分开」）。
    val menuContainerColor = if (darkTheme) {
        lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.White, 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    // 选中项底色：主题色低透明度。深色下给高一点，否则压在深底上几乎看不出来。
    val selectedItemColor = MaterialTheme.colorScheme.primary
        .copy(alpha = if (darkTheme) 0.22f else 0.10f)

    // 菜单收起时把 expanded 复位也交给选择 / 点击外部，这里只负责"能不能点开"。
    val rowClickable = enabled

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (rowClickable) {
                        Modifier.clickable { expanded = true }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
            }
            // 菜单锚点（2026-10-30 用户要求「靠右显示、在箭头的地方下拉」）：
            // 把「当前值 + 下箭头」包成一个小 Box，菜单放进这个 Box 里。
            // `DropdownMenu` 相对**直接父级**定位，父级变成这一小块之后，菜单自然贴着
            // 箭头弹出；右侧放不下时它会自动向左展开，于是看起来就是**右对齐**的浮层
            // （以前父级是整行，菜单贴在行首，离箭头隔了一整行）。
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        // 当前值用主题色：与 `DeveloperActionRow` 的 `valueText` 同一口径，
                        // 让"这一行现在是什么"在整页里一眼可辨。
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(menuWidth),
                    shape = RoundedCornerShape(16.dp),
                    // 深色下菜单底色要比页底亮一档才分得开（#111111 的页底上，#262626 的
                    // 菜单几乎糊在一起，用户原话「深色模式下选项底色浅一点，和背景区分开」）。
                    // 这里按主题明暗把容器色朝白色提一点，而不是硬编码一个色值。
                    containerColor = menuContainerColor
                ) {
                    items.forEachIndexed { index, item ->
                        val isSelected = index == selectedIndex
                        DropdownMenuItem(
                            // 选中项加一层浅色底（2026-10-30「点开时突出选项卡」）：
                            // 只把文字变主题色在深色下不够显眼，整块底色更容易扫。
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) selectedItemColor else Color.Transparent
                                ),
                            text = {
                                Column {
                                    Text(
                                        text = item.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        // 选中项用主题色加粗（与单选圈同义，但下拉里没有圈）。
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.strongTextColor
                                        },
                                        fontWeight = if (isSelected) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                    item.hint?.let { hint ->
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(index)
                            },
                            // 菜单项本身不置灰：能打开菜单就说明是可交互状态。
                            enabled = enabled
                        )
                    }
                }
            }
        }
    }
}

/** 置灰系数：与 `DeveloperSwitchRow` / `DeveloperActionRow` 取同一个值。 */
private const val DISABLED_ALPHA = 0.38f
