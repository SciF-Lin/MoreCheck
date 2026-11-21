package com.morecheck.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部四个一级入口。顺序即底部导航栏从左到右的顺序。
 *
 * 每个入口拥有**独立的页面栈**（各自的 NavHost 与 NavController），
 * 切走再切回时页面栈与滚动位置都会保留，详见 [TabNavHost]。
 */
enum class AppTab(
    val title: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector
) {
    HOME(
        title = "主页",
        iconSelected = Icons.Filled.Home,
        iconUnselected = Icons.Outlined.Home
    ),
    CALENDAR(
        title = "日历",
        iconSelected = Icons.Filled.CalendarMonth,
        iconUnselected = Icons.Outlined.CalendarMonth
    ),
    DISCOVER(
        title = "发现",
        iconSelected = Icons.Filled.Explore,
        iconUnselected = Icons.Outlined.Explore
    ),
    MINE(
        title = "我的",
        iconSelected = Icons.Filled.Person,
        iconUnselected = Icons.Outlined.Person
    );

    /** 本入口页面栈的根路由。 */
    val rootRoute: String get() = routeOf(TabDestination.Root)

    internal fun routeOf(destination: TabDestination): String = "${name}.${destination.route}"
}

/**
 * 各入口页面栈内可用的页面。用枚举集中管理路由，避免字符串硬编码散落。
 *
 * 注意：新增页面时，除了在枚举里加一项，还要在 [TabNavHost] 的 `when` 里接线，
 * 否则编译器会因 `when` 不完备而报错（这是有意为之，防止漏接线）。
 */
enum class TabDestination(val route: String) {
    /** 入口首页（显示顶部标题栏与加号）。 */
    Root("root"),

    /** 二级页：打卡记录。 */
    Records("records"),

    /** 二级页：扫一扫（留空）。 */
    Scanner("scanner"),

    /** 二级页：朋友圈（Hello World 展示页）。 */
    Moments("moments"),

    /** 二级页：数据备份与恢复。 */
    Backup("backup"),

    /** 二级页：登录（不主动弹出，仅由调用方推入）。 */
    Login("login"),

    /** 二级页：任务管理。 */
    Tasks("tasks"),

    /** 二级页：新建 / 编辑任务。 */
    TaskEdit("task_edit"),

    /** 二级页：设置（外观：深色模式、动态配色）。 */
    Settings("settings"),

    /** 二级页：个人信息。 */
    PersonalInfo("personal_info"),

    /** 二级页：用户协议。 */
    Agreement("agreement"),

    /** 二级页：隐私政策。 */
    Privacy("privacy"),

    /** 二级页：通用占位页，用路径参数区分具体条目。 */
    Placeholder("placeholder/{placeholder}"),

    /** 二级页：关于我们。 */
    About("about");

    /** 带参数的页面需要自行拼接 `route/{arg}` 形式的路由模板。 */
    companion object {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PLACEHOLDER = "placeholder"
        const val ROUTE_TASK_EDIT = "task_edit?$ARG_TASK_ID={$ARG_TASK_ID}"
    }
}

/**
 * 「我的」入口下方的占位条目。
 *
 * 任务3 只要求「点开无实际内容，但样式与结构与设置页保持一致」，
 * 因此这里集中声明它们的标题与说明，页面统一由 [TabDestination.Placeholder] 承载。
 */
enum class PlaceholderEntry(
    val value: String,
    val title: String,
    val description: String
) {
    FEEDBACK(
        value = "feedback",
        title = "帮助与反馈",
        description = "常见问题与意见反馈"
    );

    companion object {
        fun fromValue(value: String?): PlaceholderEntry =
            entries.firstOrNull { it.value == value } ?: FEEDBACK
    }
}

/**
 * 一级入口的顺序列表，底部导航栏与各入口栈的初始化都以此为准。
 */
val APP_TABS: List<AppTab> = AppTab.entries.toList()
