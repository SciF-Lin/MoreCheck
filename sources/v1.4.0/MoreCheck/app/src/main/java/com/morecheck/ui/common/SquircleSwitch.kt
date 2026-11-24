package com.morecheck.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 「偏方形」开关：圆角矩形轨道 + 圆角方形滑块（用户 2026-11-11 给的参考图）。
 *
 * ## 为什么不用 Material 3 的 [androidx.compose.material3.Switch]
 *
 * M3 的 Switch 是「胶囊轨道 + 正圆滑块」，几何写死在组件里（轨道宽高、滑块直径、
 * 内边距都是 internal 常量），没有任何形状参数可调。要做成参考图那种
 * **轨道四角不顶满、滑块是个圆角方块**的样子，只能自己画。
 *
 * ## 几何（与参考图逐项对齐）
 *
 * - 轨道 56×32dp，圆角 [TrackCorner] = 11dp：约为高度 1/3，
 *   所以看起来"偏方"但仍明显是圆角，不是直角方块；
 * - 滑块 24dp 见方，圆角 8dp（同样约 1/3 边），四周留 4dp；
 * - 滑块行程 = 56 − 24 − 4×2 = 24dp，开时贴右、关时贴左。
 *
 * 轨道与滑块都描 1dp 边：参考图里两者都有明显勾边，没有描边的话浅色轨道
 * 与页面底色会糊在一起（这也是本机浅色主题在软件渲染模拟器上的老问题）。
 *
 * ## 状态与可达性
 *
 * 整块用一个 [toggleable]（`role = Switch`）承担点击与语义，没有内外两套热区；
 * 滑块用 `animateDpAsState` 平移，颜色直接切换（不做过场动画——
 * 12dp 的色块做渐变反而显得脏）。
 */
private val TrackWidth = 56.dp
private val TrackHeight = 32.dp
private val ThumbSize = 24.dp
private val ThumbInset = 4.dp
private val TrackCorner = 11.dp
private val ThumbCorner = 8.dp

/**
 * @param checkedTrackColor 打开时的轨道色（参考图是主题绿）。
 * @param uncheckedTrackColor 关闭时的轨道色。
 * @param thumbColor 滑块填充色。
 * @param outlineColor 轨道与滑块的描边色（一般给当前配色里的分隔线 / 描边色）。
 */
@Composable
fun SquircleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedTrackColor: Color = MaterialTheme.colorScheme.primary,
    uncheckedTrackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    thumbColor: Color = MaterialTheme.colorScheme.surface,
    outlineColor: Color = MaterialTheme.colorScheme.outline
) {
    val interaction = remember { MutableInteractionSource() }
    val travel: Dp = TrackWidth - ThumbSize - ThumbInset * 2
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "squircleSwitchThumb"
    )

    // 关闭态整体压暗：与 M3 的 disabled 观感一致，但保留描边可见（否则像"消失"）。
    val trackColor = when {
        !enabled && checked -> checkedTrackColor.copy(alpha = 0.38f)
        !enabled -> uncheckedTrackColor.copy(alpha = 0.38f)
        checked -> checkedTrackColor
        else -> uncheckedTrackColor
    }

    Box(
        modifier = modifier
            .width(TrackWidth)
            .height(TrackHeight)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(trackColor, RoundedCornerShape(TrackCorner))
                .border(1.dp, outlineColor.copy(alpha = 0.45f), RoundedCornerShape(TrackCorner))
        ) {
            Box(
                Modifier
                    .padding(ThumbInset)
                    .offset(x = thumbOffset)
                    .size(ThumbSize)
                    .background(thumbColor, RoundedCornerShape(ThumbCorner))
                    .border(
                        width = 1.dp,
                        color = outlineColor.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(ThumbCorner)
                    )
            )
        }
    }
}
