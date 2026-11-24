package com.morecheck.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 任务组织相关的复用控件（筛选 chip、标签 chip、状态角标、分组标题）。
 *
 * 编辑页的标签预览和列表页的任务行都要画同一套 chip，集中放这里避免两处
 * 各自维护样式；这里只做展示，不含任何筛选 / 分组判定（那些在 TaskOrganization.kt）。
 */

/** 顶部筛选：四个状态横排，横向可滚动，窄屏也不会挤掉「已归档」。 */
@Composable
fun TaskFilterRow(
    selected: TaskFilter,
    onSelect: (TaskFilter) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(TaskFilter.entries.toList(), key = { it.name }) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) }
            )
        }
    }
}

/** 标签 chip（只读展示）。空列表时不占高度。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tags.forEach { tag ->
            // 标签只是展示、不是操作：给空 onClick 保留 AssistChip 的可读配色，
            // 不用 disabled（那样文字会被压到 38% 透明度，小字几乎看不清）。
            AssistChip(
                onClick = {},
                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

/** 暂停 / 归档状态角标，颜色区分严重程度，一眼能看出任务为何「不在进行中」。 */
@Composable
fun StatusBadges(archived: Boolean, paused: Boolean, modifier: Modifier = Modifier) {
    if (!archived && !paused) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (archived) {
            StatusBadge(
                text = "已归档",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        if (paused) {
            StatusBadge(
                text = "已暂停",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun StatusBadge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** 分组小标题。 */
@Composable
fun TaskGroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
    )
}
