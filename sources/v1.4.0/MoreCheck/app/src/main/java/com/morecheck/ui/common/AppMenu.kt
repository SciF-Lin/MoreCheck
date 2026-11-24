package com.morecheck.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 右上角「更多」菜单里的一项，**主页 / 发现 / 统计三处共用**。
 *
 * 为什么抽组件而不是三处各写一遍：这三处菜单的文案、顺序、图标本来就是逐项对齐的
 * （见 `HomeScreen` / `CalendarScreen` 里那段"三处要改一起改"的注释），但尺寸一直是
 * 各自继承 M3 默认值（行高 48dp、文字 `bodyLarge`）。2026-10-31 用户反馈"菜单按钮偏小"
 * 需要整体加大——如果三处各改一遍，下次再调又会漂，所以把规格收到这里一处。
 *
 * 规格（改这里即三处同时生效）：
 * - 行高下限 56dp：48dp 是 M3 的最小可点高度，对"只有三行、每行两个词的菜单"显得挤；
 * - 文字 `titleMedium`（16sp / Medium）：比默认 `bodyLarge` 略重，菜单项之间的层次更清楚；
 * - 图标 26dp：与放大的行高配比，避免"字大了、图标还是 24dp"那种失衡。
 *
 * ⚠️ 只放大**视觉**尺寸不牺牲可点性：56dp 本身已经超过 48dp 的最小触摸目标。
 */
@Composable
fun AppMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.titleMedium) },
        // `heightIn(min=)` 而不是 `height()`：将来某一项文案变长、折成两行时，
        // 行高应该跟着长，而不是把文字裁掉。
        modifier = Modifier.heightIn(min = 56.dp),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(26.dp)
            )
        },
        onClick = onClick
    )
}
