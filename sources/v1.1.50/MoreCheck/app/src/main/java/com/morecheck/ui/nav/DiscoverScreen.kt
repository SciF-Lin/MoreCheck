package com.morecheck.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 「发现」入口首页。
 *
 * - 自带标题栏（标题栏已从外层骨架移入各页面，避免进入二级页时骨架重排造成割裂）；
 * - 「扫一扫」进入留空二级页；「朋友圈」进入 Hello World 展示页，并清除未读角标；
 * - 右上角「+」弹出菜单：扫一扫 / 打卡记录，仿微信。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    momentsBadge: Int,
    onOpenScanner: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenRecords: () -> Unit,
    onItemClick: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    val sections = listOf(
        "朋友圈" to listOf(
            DiscoverItem("moments", "朋友圈", Icons.Outlined.Explore, badge = momentsBadge)
        ),
        "扫一扫与看一看" to listOf(
            DiscoverItem("scan", "扫一扫", Icons.Filled.Search),
            DiscoverItem("look", "看一看", Icons.Outlined.CalendarMonth)
        ),
        "更多" to listOf(
            DiscoverItem("more", "更多功能", Icons.Filled.MoreHoriz)
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发现") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("扫一扫") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenScanner()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("打卡记录") },
                            leadingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenRecords()
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            sections.forEach { (sectionTitle, items) ->
                item(key = "header_$sectionTitle") {
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 4.dp)
                    )
                }
                item(key = "card_$sectionTitle") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Column {
                            items.forEachIndexed { index, item ->
                                DiscoverRow(
                                    item = item,
                                    onClick = {
                                        // 明确的手势反馈：轻震动。
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        when (item.key) {
                                            // 朋友圈：进入 Hello World 页，并清除未读角标。
                                            "moments" -> onOpenMoments()
                                            // 扫一扫：留空二级页。
                                            "scan" -> onOpenScanner()
                                            else -> onItemClick(item.key)
                                        }
                                    }
                                )
                                if (index != items.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 64.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DiscoverItem(
    val key: String,
    val title: String,
    val icon: ImageVector,
    /** 未读数字；null 表示无角标。0 也不会显示。 */
    val badge: Int? = null
)

@Composable
private fun DiscoverRow(
    item: DiscoverItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(34.dp)
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(8.dp)
            )
        }

        Spacer(Modifier.width(14.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        val badge = item.badge ?: 0
        if (badge > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ) {
                Text(
                    text = if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
