package com.morecheck.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector

/** 入口归属：发现页列表 / 实验室宫格。 */
enum class EntryPlacement { DISCOVER, LAB }

/**
 * 可以在「发现页 ↔ 实验室」之间搬迁的入口。
 *
 * [key] 一律复用现有稳定标识：发现页用 `DiscoverItem.key`，实验室用
 * `LabRealPage.key`（与 `TabDestination.route` 同名）。**不新造 key**，
 * 老桌面快捷方式与 `lab_grid_order` 里的旧数据才不会失效。
 */
data class MovableEntry(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val defaultPlacement: EntryPlacement
)

/** 可搬入口总表：顺序即两页追加时的展示顺序。 */
val MOVABLE_ENTRIES: List<MovableEntry> = listOf(
    // ---- 默认在发现页（可搬到实验室）----
    MovableEntry("search", "官网", Icons.Filled.Public, EntryPlacement.DISCOVER),
    MovableEntry("hot_search", "热搜", Icons.Filled.Whatshot, EntryPlacement.DISCOVER),
    MovableEntry("bilibili", "B站视频", Icons.Filled.PlayArrow, EntryPlacement.DISCOVER),
    MovableEntry("music", "音乐", Icons.Filled.MusicNote, EntryPlacement.DISCOVER),
    // ---- 默认在实验室（可搬到发现页）----
    MovableEntry("weather", "天气", Icons.Filled.WbSunny, EntryPlacement.LAB),
    MovableEntry("notebook", "记事本", Icons.Filled.Edit, EntryPlacement.LAB),
    MovableEntry("agent", "智能体", Icons.Filled.Star, EntryPlacement.LAB),
    MovableEntry("pomodoro", "番茄钟", Icons.Filled.Timer, EntryPlacement.LAB),
    MovableEntry("timetable", "课程表", Icons.Filled.GridOn, EntryPlacement.LAB),
    MovableEntry("todo_today", "今日待办", Icons.Filled.Assessment, EntryPlacement.LAB),
    MovableEntry("score_stats", "成绩统计", Icons.Filled.Numbers, EntryPlacement.LAB),
    MovableEntry("github_trending", "GitHub 热门", Icons.Filled.TrendingUp, EntryPlacement.LAB),
    MovableEntry("color_card", "色卡", Icons.Filled.Palette, EntryPlacement.LAB),
    MovableEntry("domain_price", "域名比价", Icons.Filled.Build, EntryPlacement.LAB)
)

/** 「官网」位切成「搜一搜」时用的图标。 */
val SEARCH_MODE_ICON: ImageVector = Icons.Filled.Search

/** 「搜一搜」模式的默认首页（历史默认页是百度；官网模式仍走 `search?url=` 空参）。 */
const val SEARCH_MODE_HOME_URL = "https://www.baidu.com/"

/**
 * 开屏分类：现在可以**逐页**开关，不再是「其他实验室页面」一把总开关。
 *
 * key 用 `TabDestination.route`，与 `LabSplashHost(routeKey=...)` 完全一致。
 */
val LAB_SPLASH_PAGES: List<Pair<String, String>> = listOf(
    "weather" to "天气",
    "notebook" to "记事本",
    "agent" to "智能体",
    "pomodoro" to "番茄钟",
    "random_number" to "随机数",
    "morse_code" to "摩斯密码",
    "image_compress" to "图片压缩",
    "decision_maker" to "决策助手",
    "color_card" to "色卡",
    "login_prompt_setting" to "登录提示功能",
    "timetable" to "课程表",
    "todo_today" to "今日待办"
)

/** 归属覆盖表的落盘格式：`key=LAB` / `key=DISCOVER`，逗号分隔；空串 = 全部走默认。 */
fun decodeEntryPlacement(raw: String): Map<String, EntryPlacement> = raw
    .split(",")
    .mapNotNull { part ->
        val kv = part.split("=", limit = 2)
        if (kv.size != 2) return@mapNotNull null
        val placement = runCatching { EntryPlacement.valueOf(kv[1]) }.getOrNull()
            ?: return@mapNotNull null
        kv[0].trim().takeIf { it.isNotEmpty() }?.let { it to placement }
    }
    .toMap()

/** 编码回写；与默认归属相同的项不写，保持存盘串尽量短。 */
fun encodeEntryPlacement(overrides: Map<String, EntryPlacement>): String = overrides
    .filter { (key, value) ->
        MOVABLE_ENTRIES.firstOrNull { it.key == key }?.defaultPlacement != value
    }
    .toSortedMap()
    .entries
    .joinToString(",") { "${it.key}=${it.value.name}" }

/** 某个入口当前应该出现在哪一页。 */
fun effectivePlacement(
    key: String,
    overrides: Map<String, EntryPlacement>
): EntryPlacement = overrides[key]
    ?: MOVABLE_ENTRIES.firstOrNull { it.key == key }?.defaultPlacement
    ?: EntryPlacement.DISCOVER

/** 把逗号分隔的「已关闭开屏的页面」解析成 Set。 */
fun decodeSplashDisabled(raw: String): Set<String> = raw
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()
