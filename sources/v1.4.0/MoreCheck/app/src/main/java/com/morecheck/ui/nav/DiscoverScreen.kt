package com.morecheck.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morecheck.ui.common.AppMenuItem
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.theme.strongTextColor

/**
 * 「发现」入口首页。
 *
 * - 自带标题栏（标题栏已从外层骨架移入各页面，避免进入二级页时骨架重排造成割裂）；
 * - 「朋友圈」进入 Hello World 展示页并清除未读角标；「扫一扫」进入真实扫码页；
 *   「官网」（原「看一看」）进入内嵌网页浏览页；「实验室」进入实验室页；
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
    /** 打开「官网」（内嵌网页浏览，原名「看一看」）。 */
    onOpenSearch: () -> Unit = {},
    /**
     * 打开「热搜」（2026-10-25 需求：入口从实验室宫格移到本页「官网」下方）。
     *
     * 路由仍是 `TabDestination.HotSearch`（"hot_search"），老的桌面快捷方式
     * 无需重建即可直达；实验室宫格里那一项已删除，但排序键里残留的
     * "hot_search" 会被 `mapNotNull` 自然跳过，不影响宫格渲染。
     */
    onOpenHotSearch: () -> Unit = {},
    /** 打开「B站视频」（2026-11-03：入口在「热搜」正下方，v1.2.5）。 */
    onOpenBilibili: () -> Unit = {},
    /** 打开「音乐」（2026-11-05：入口在「B站视频」正下方）。 */
    onOpenMusic: () -> Unit = {},
    /**
     * 打开通用占位二级页，参数是 [PlaceholderEntry.value]
     * （「更多功能」= `more`，与本页 `DiscoverItem.key` 一致）。
     */
    onOpenPlaceholder: (String) -> Unit = {},
    /** 可搬入口统一分派（从实验室搬来的入口 / 音乐确认门禁）。 */
    onOpenMovableEntry: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    // 2026-11-14：入口归属（发现页 ↔ 实验室）与「官网 / 搜一搜」模式都落在
    // DataStore 里，由开发者选项统一配置。音乐确认弹窗已上移到 TabNavHost，
    // 这样音乐入口搬到实验室后仍会先弹窗，不会出现两条不同的门禁链路。
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        context.applicationContext.appContainer.settingsRepository
    }
    // 初值取**进程级快照**（App 启动时已预热）：进本页的第一帧就是真实配置，
    // 不会先按默认归属 / 官网模式画一帧再跳（2026-11-14 需求 2 的闪动就是这么来的）。
    val overridesRaw by settingsRepository.entryPlacementOverrides
        .collectAsStateWithLifecycle(
            initialValue = settingsRepository.entryPlacementSnapshot ?: ""
        )
    val placement = remember(overridesRaw) { decodeEntryPlacement(overridesRaw) }
    val searchMode by settingsRepository.devDiscoverSearchMode
        .collectAsStateWithLifecycle(
            initialValue = settingsRepository.discoverSearchModeSnapshot ?: false
        )

    // 2026-11-14 需求 5：长按发现页的某个入口 → 选择把它放到哪一页。
    // 与开发者选项里的「入口管理」写的是同一张覆盖表，两处天然同步。
    var movingEntry by remember { mutableStateOf<DiscoverItem?>(null) }
    val scope = rememberCoroutineScope()

    // 默认在发现页的可搬入口（被搬到实验室的自动不显示）。
    val baseDiscover = remember(placement, searchMode) {
        MOVABLE_ENTRIES.filter {
            it.defaultPlacement == EntryPlacement.DISCOVER &&
                effectivePlacement(it.key, placement) == EntryPlacement.DISCOVER
        }.map { entry ->
            val title = if (entry.key == "search" && searchMode) "搜一搜" else entry.title
            val icon = if (entry.key == "search" && searchMode) SEARCH_MODE_ICON else entry.icon
            DiscoverItem(entry.key, title, icon)
        }
    }
    // 默认在实验室、被搬到发现页的入口，单独成组放在「更多」之前。
    val movedFromLab = remember(placement) {
        MOVABLE_ENTRIES.filter {
            it.defaultPlacement == EntryPlacement.LAB &&
                effectivePlacement(it.key, placement) == EntryPlacement.DISCOVER
        }.map { DiscoverItem(it.key, it.title, it.icon) }
    }
    val sections = buildList<Pair<String, List<DiscoverItem>>> {
        add("朋友圈" to listOf(
            DiscoverItem("moments", "朋友圈", Icons.Outlined.Explore, badge = momentsBadge)
        ))
        // 分组标题跟着官网位模式走：切成「搜一搜」后还写「官网」会名不副实。
        add((if (searchMode) "扫一扫与搜索" else "扫一扫与官网") to listOf(
            DiscoverItem("scan", "扫一扫", Icons.Filled.QrCodeScanner)
        ) + baseDiscover)
        if (movedFromLab.isNotEmpty()) {
            add("来自实验室" to movedFromLab)
        }
        add("更多" to listOf(
            DiscoverItem("lab", "实验室", Icons.Filled.Science),
            DiscoverItem("more", "更多功能", Icons.Filled.MoreHoriz)
        ))
    }

    Scaffold(
        // ⚠️ 选项卡根页：底部让位由外层（`TabNavHost` 的 `rootBottomPadding`）统一给出，
        // 这里不能再消费系统栏内边距，否则底栏上方会多出一条空白、内容被切断
        // （根因与修法说明见 `CalendarScreen` 同位置的注释）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("发现") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        // 无障碍文案统一为「更多」：这个菜单里其实是三项
                        // （新建任务 / 扫一扫 / 打卡记录），叫「新建」会把后两项漏在语义之外。
                        Icon(Icons.Filled.Add, contentDescription = "更多")
                    }
                    // 菜单外观（需求：加号列表改为圆角 + 浅灰，与背景区分）：
                    // - 圆角 16dp：默认的 4dp 在"浮层"语义下太"方"，圆角让它读作一张卡片；
                    // - `surfaceContainerHigh` 是比页面底色**亮一档**的中性灰——浅色模式
                    //   下页面是灰底（surfaceContainerLow 系）而菜单更亮、深色模式下页面更暗
                    //   而菜单更亮，两个模式都能拉开层次。用 `surface` 会在浅色下与背景几乎同色。
                    //   （M3 1.3.1 的 DropdownMenu 支持 shape/containerColor，已用 javap 核实。）
                    //
                    // 统一规格：这套「加号图标 + 菜单圆角 16dp + surfaceContainerHigh + 三项
                    // 文案/顺序/图标」的规格，在**主页 / 发现 / 统计**三处保持一致；
                    // 三处要改一起改，不要各自维护一套。
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        // 尺寸与另外两处（主页 / 统计）一致，统一走 [AppMenuItem]。
                        AppMenuItem(
                            label = "新建任务",
                            icon = Icons.Filled.Add,
                            onClick = {
                                menuOpen = false
                                onNewTask()
                            }
                        )
                        // 与列表里的「扫一扫」用同一个扫码图标（QrCodeScanner）。
                        // 这里原先漏改过图标，与列表里的「扫一扫」保持一致即可。
                        AppMenuItem(
                            label = "扫一扫",
                            icon = Icons.Filled.QrCodeScanner,
                            onClick = {
                                menuOpen = false
                                onOpenScanner()
                            }
                        )
                        // 与「主页」的 + 菜单保持一致：都用 History。
                        // 原先这里是 Outlined.CalendarMonth（描边日历），
                        // 同一个「打卡记录」在两个入口显示成两种图标（需求 bug3）。
                        AppMenuItem(
                            label = "打卡记录",
                            icon = Icons.Filled.History,
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
                                    // 只有可搬入口（官网 / 热搜 / B站 / 音乐 / 实验室功能）才挂长按；
                                    // 朋友圈、扫一扫、实验室、更多功能这些固定入口长按不做事。
                                    onLongClick = if (MOVABLE_ENTRIES.any { it.key == item.key }) {
                                        { movingEntry = item }
                                    } else {
                                        null
                                    },
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
                                            // 官网：内嵌网页浏览（原名「看一看」）。
                                            "search" -> onOpenSearch()
                                            // 热搜：聚合各平台热搜（2026-10-25 移入本页）。
                                            "hot_search" -> onOpenHotSearch()
                                            // B站视频：内嵌网页观看 + 原生下载（v1.2.5）。
                                            "bilibili" -> onOpenBilibili()
                                            // 音乐：确认弹窗由 TabNavHost 统一挂，
                                            // 入口在发现页还是实验室走的是同一条门禁。
                                            "music" -> onOpenMovableEntry("music")
                                            // 更多功能：进入留空二级页。
                                            "more" -> onOpenPlaceholder(item.key)
                                            // 从实验室搬过来的入口：统一交给导航层分派。
                                            else -> onOpenMovableEntry(item.key)
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

    // 长按后的「放在哪里」选择（2026-11-14 需求 5）。
    //
    // 为什么用 AlertDialog 而不是下拉菜单：这是一个**二选一的位置决定**，
    // 需要一句话解释后果（"发现页不再显示、改在实验室宫格"），
    // 菜单那种一列短标签塞不下这句说明，用户点完也不知道发生了什么。
    movingEntry?.let { entry ->
        val currentlyOnDiscover =
            effectivePlacement(entry.key, placement) == EntryPlacement.DISCOVER
        AlertDialog(
            onDismissRequest = { movingEntry = null },
            title = { Text("把「${entry.title}」放在哪里？") },
            text = {
                Text(
                    if (currentlyOnDiscover) {
                        "移到实验室后，发现页不再显示这一项，改为出现在实验室宫格里；" +
                            "在实验室长按那一格可以再移回来。"
                    } else {
                        "回到发现页后，会出现在「来自实验室」分组里；" +
                            "长按同样可以再移回实验室。"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = if (currentlyOnDiscover) {
                            EntryPlacement.LAB
                        } else {
                            EntryPlacement.DISCOVER
                        }
                        val updated = placement.toMutableMap().apply { put(entry.key, target) }
                        movingEntry = null
                        scope.launch {
                            settingsRepository.setEntryPlacementOverrides(
                                encodeEntryPlacement(updated)
                            )
                        }
                    }
                ) {
                    Text(if (currentlyOnDiscover) "移到实验室" else "移回发现页")
                }
            },
            dismissButton = {
                TextButton(onClick = { movingEntry = null }) { Text("取消") }
            }
        )
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable 的默认水波纹。
            // 理由见 `ui/common/PressScale.kt` 的 KDoc。
            .pressScale(onClick = onClick, onLongClick = onLongClick)
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
