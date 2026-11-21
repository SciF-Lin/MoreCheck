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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
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
import com.morecheck.ui.theme.strongTextColor

/**
 * 「发现」入口首页。
 *
 * - 自带标题栏（标题栏已从外层骨架移入各页面，避免进入二级页时骨架重排造成割裂）；
 * - 「朋友圈」进入 Hello World 展示页并清除未读角标；「扫一扫」进入真实扫码页；
 *   「搜一搜」（原「看一看」）进入内嵌网页浏览页；「实验室」进入实验室页；
 *   「更多功能」进入留空二级页；
 * - 右上角「+」弹出菜单：新建任务 / 扫一扫 / 打卡记录，仿微信。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    momentsBadge: Int,
    onOpenScanner: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenRecords: () -> Unit,
    onNewTask: () -> Unit = {},
    /** 打开「实验室」页。 */
    onOpenLab: () -> Unit = {},
    /** 打开「搜一搜」（内嵌网页浏览，原名「看一看」）。 */
    onOpenSearch: () -> Unit = {},
    /**
     * 打开通用占位二级页，参数是 [PlaceholderEntry.value]
     * （「更多功能」= `more`，与本页 `DiscoverItem.key` 一致）。
     */
    onOpenPlaceholder: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    val sections = listOf(
        "朋友圈" to listOf(
            DiscoverItem("moments", "朋友圈", Icons.Outlined.Explore, badge = momentsBadge)
        ),
        "扫一扫与搜一搜" to listOf(
            // 扫一扫换成更贴合的扫码图标：原来的放大镜（Search）现在归「搜一搜」。
            DiscoverItem("scan", "扫一扫", Icons.Filled.QrCodeScanner),
            DiscoverItem("search", "搜一搜", Icons.Filled.Search)
        ),
        "更多" to listOf(
            // 「实验室」排在「更多功能」**上面**（需求：实验室入口移到更多功能上面）。
            //
            // 顺序有语义：实验室里全是**已实现**的真实功能（天气 / 开发者选项 /
            // 登录提示 / 记事本 / 智能体），而「更多功能」是留空占位页——
            // 把能用的放在能点的前面，符合"越靠上越有用"的列表直觉。
            DiscoverItem("lab", "实验室", Icons.Filled.Science),
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
                    // 菜单外观（需求：加号列表改为圆角 + 浅灰，与背景区分）：
                    // - 圆角 16dp：默认的 4dp 在"浮层"语义下太"方"，圆角让它读作一张卡片；
                    // - `surfaceContainerHigh` 是比页面底色**亮一档**的中性灰——浅色模式
                    //   下页面是灰底（surfaceContainerLow 系）而菜单更亮、深色模式下页面更暗
                    //   而菜单更亮，两个模式都能拉开层次。用 `surface` 会在浅色下与背景几乎同色。
                    //   （M3 1.3.1 的 DropdownMenu 支持 shape/containerColor，已用 javap 核实。）
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("新建任务") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onNewTask()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("扫一扫") },
                            // 与列表里的「扫一扫」用同一个扫码图标（QrCodeScanner）。
                            // 这里原先漏改了、仍是放大镜（Search），而放大镜早已归「搜一搜」。
                            leadingIcon = {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onOpenScanner()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("打卡记录") },
                            // 与「主页」的 + 菜单保持一致：都用 History。
                            // 原先这里是 Outlined.CalendarMonth（描边日历），
                            // 同一个「打卡记录」在两个入口显示成两种图标（需求 bug3）。
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
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
                                            // 扫一扫：真实扫码页。
                                            "scan" -> onOpenScanner()
                                            // 实验室：进入实验室页（内含四个留空功能）。
                                            "lab" -> onOpenLab()
                                            // 搜一搜：内嵌网页浏览（原名「看一看」）。
                                            "search" -> onOpenSearch()
                                            // 更多功能：进入留空二级页。
                                            else -> onOpenPlaceholder(item.key)
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
            // 正文黑加深（仅本页与「我的」页）。
            color = MaterialTheme.strongTextColor,
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
