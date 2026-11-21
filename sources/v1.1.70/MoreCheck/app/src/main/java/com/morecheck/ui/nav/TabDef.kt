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
 *
 * ⚠️ [CALENDAR] 的标题 v1.1.69 起由「日历」改为「统计」（需求：底部导航栏更名）。
 * 改的是 [title]（底栏与标题栏显示用），**枚举名与 route 保持 `CALENDAR`**：
 * route 是桌面快捷方式与 `fromRouteOrNull` 匹配的依据，改名会让历史快捷方式失联；
 * 而「统计」与「日历」描述的是同一块内容（月视图 + 打卡统计），语义上没有分裂。
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
        title = "统计",
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

    /** 二级页：注册账号（由登录页的「注册」按钮推入）。 */
    Register("register"),

    /** 二级页：任务管理。 */
    Tasks("tasks"),

    /** 二级页：新建 / 编辑任务。 */
    TaskEdit("task_edit"),

    /** 二级页：设置（外观：深色模式、主题色；通用：自启动）。 */
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
    About("about"),

    /** 二级页：更新日志（由关于页的「更新日志」入口推入）。 */
    Changelog("changelog"),

    /** 二级页：实验室（由发现页的「实验室」入口推入）。 */
    Lab("lab"),

    /** 二级页：实验室 → 天气（联网查询当地天气）。 */
    Weather("weather"),

    /** 二级页：实验室 → 开发者选项（v1.1.64 新增）。 */
    DeveloperOptions("developer_options"),

    /**
     * 二级页：实验室 → 登录提示功能（v1.1.65 新增）。
     *
     * 一个只有单个开关的小页面：控制「打开软件时提示登录」那个引导弹窗。
     * 需求要求把它做成实验室里的一个入口，而不是塞进「设置」——
     * 它是**调试/偏好类**的开关，与实验室其它入口同一性质。
     */
    LoginPromptSetting("login_prompt_setting"),

    /**
     * 二级页：实验室 → 记事本（v1.1.66 新增）。
     *
     * 只有**一条**笔记（文字 + 图片），供用户在实验室里随手记录；
     * 返回时自动保存，因此没有「保存」按钮。
     */
    Notebook("notebook"),

    /**
     * 二级页：实验室 → 智能体（AI 对话）。
     *
     * 本轮由「无实际功能」的占位页升级为**真实页面**：OpenAI 兼容的 AI 对话，
     * 支持 DeepSeek / 通义千问 / 小米 MiMo 三家服务商（用户自带 API Key）。
     * [PlaceholderEntry.AGENT] 占位条目随之删除（不留死枚举，与本项目一贯做法一致；
     * 它的 `value`（`lab_agent`）只被实验室宫格当作路由参数用过，那一处已改走本真实路由）。
     */
    Agent("agent"),

    /** 二级页：智能体设置（服务提供商 / API Key / 模型 / 系统提示词 / 清空记录）。 */
    AgentSettings("agent_settings"),

    /**
     * 二级页：密码登录第一步（只填手机号，v1.1.65 新增）。
     *
     * 需求：既有登录页（手机号 + 验证码）新增「使用密码登录」，点进去先填手机号，
     * 再进第二步填密码。**两步拆成两个路由**而不是一页里的两个 step：
     * 这样系统返回键、页面推入动画都自动正确，与注册 / 登录那套完全一致。
     */
    PasswordLogin("password_login"),

    /**
     * 二级页：密码登录第二步（填密码，v1.1.65 新增）。
     *
     * `phone` 用**查询参数**传：上一步填的号码要显示在头像下方。
     * 查询参数而不是路径参数，是因为它可能为空（直接进本页时），
     * 路径参数为空会让路由匹配失败（`password_entry//`），查询参数则天然可缺省。
     */
    PasswordEntry("password_entry?$ARG_PHONE_NAME={$ARG_PHONE_NAME}"),

    /**
     * 二级页：找回密码（v1.1.65 新增）。
     *
     * 由「登录页 → 忘记密码」与「密码登录两步页 → 找回密码」共同进入。
     * 它此前是 [PlaceholderEntry.FORGOT_PASSWORD] 那张留空页，本轮做成真实界面
     * （**仅界面演示，不接真实找回流程**）。
     */
    ForgotPassword("forgot_password"),

    /** 二级页：搜一搜（内嵌网页浏览，原名「看一看」）。 */
    Search("search"),

    /** 二级页：权限管理（设置 → 通用）。 */
    Permissions("permissions"),

    /** 二级页：声音设置（设置 → 通用；计时器到点铃声）。 */
    TimerSound("timer_sound"),

    /** 二级页：全年打卡总览（点日历页标题栏年月进入）。 */
    YearOverview("year_overview");

    /** 带参数的页面需要自行拼接 `route/{arg}` 形式的路由模板。 */
    companion object {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PLACEHOLDER = "placeholder"

        /** 密码登录第二步要显示的手机号（见 [PasswordEntry]）。 */
        const val ARG_PHONE = ARG_PHONE_NAME
        const val ROUTE_TASK_EDIT = "task_edit?$ARG_TASK_ID={$ARG_TASK_ID}"

        /**
         * 按「桌面快捷方式里存的 lab_page 值」找回对应的目的地（v1.1.68 需求 1）。
         *
         * ## 为什么按 `route` 匹配，而不是再建一张映射表
         *
         * 快捷方式里存的值就是 [route] 本身（见 `LabScreen.labShortcutKey`），
         * 所以这里只要拿 route 比一下即可 —— 少一张需要人工同步的表。
         *
         * ⚠️ 找不到时返回 null 而不是抛异常：快捷方式可能是在旧版本里创建的，
         * 而对应的页面在新版本里被改名/删除了。那种情况下**应该安静地落到首页**，
         * 而不是因为一个历史快捷方式让应用崩在启动路径上。
         */
        fun fromRouteOrNull(route: String?): TabDestination? =
            route?.let { r -> entries.firstOrNull { it.route == r } }
    }
}

/**
 * 密码登录第二步的手机号参数名（见 [TabDestination.PasswordEntry]）。
 *
 * ## 为什么放在文件顶层，而不是直接写进 `TabDestination` 的 companion
 *
 * 枚举项 `PasswordEntry("password_entry?$ARG_PHONE_NAME={$ARG_PHONE_NAME}")` 的构造
 * 发生在 companion 初始化**之前**，在里面引用 companion 的 `ARG_PHONE` 会直接编译报错
 * （`Companion object of enum class 'TabDestination' is uninitialized here`）。
 *
 * 顶层 `const val` 是**编译期常量**、直接内联进使用处，没有初始化顺序问题。
 * companion 里的 `ARG_PHONE` 转发到它，所以对外仍然只有一个名字来源，
 * 不会出现「枚举里的参数名」与「导航层读的参数名」不一致这种隐蔽 bug。
 */
private const val ARG_PHONE_NAME = "phone"

/**
 * 通用占位条目。
 *
 * 「我的 → 帮助与反馈」「发现 → 看一看 / 更多功能」、**发现 → 实验室下的各功能**、
 * **个人信息页「基础资料」的四行**都走这一套：**点开无实际内容**，但层级完整
 * （有返回箭头、可回退），页面统一由 [TabDestination.Placeholder] 承载。
 *
 * [value] 同时用作「发现」页 `DiscoverItem.key`（以及实验室页、个人信息页的条目 key）
 * 与路由的路径参数，**两处必须保持一致**，否则 `fromValue()` 会静默回退到 [FEEDBACK]。
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
    ),
    // 「看一看」已在 v1.1.52 更名为「搜一搜」并实装为内嵌网页浏览，
    // 不再需要留空占位条目（原名 LOOK 已删除，避免留下无引用死枚举）。
    MORE(
        value = "more",
        title = "更多功能",
        description = "更多功能暂未开放"
    ),

    // ── 实验室（发现 → 实验室）的功能入口 ──
    //
    // v1.1.62 调整：原先「九格切图 / 视频提取音频 / 网页转应用 / 简易图片编辑」四个占位入口
    // **全部删除**（需求要求「原来四个占位入口删去」），只保留「天气」这个真实页面。
    // 对应的 LAB_GRID / LAB_AUDIO / LAB_WEB2APP / LAB_IMAGE 四个枚举项已一并删除，
    // 避免留下无引用的死枚举（本项目一贯如此处理）。
    //
    // 本轮再删掉原「登录测试选项」占位项：它已升级为真实页面「开发者选项」，
    // 改走 [TabDestination.DeveloperOptions] 真实路由，占位页不复存在。
    // 同一个理由——留着就是死代码。它的 `value`（`lab_login_test`）也随之失效：
    // 该值只被实验室宫格当作路由参数用，而现在宫格那一条已改走真实路由。

    /** 实验室页底部那个长条按钮。 */
    LAB_NEW(
        value = "lab_new",
        title = "新增功能",
        description = "新增功能暂未开放"
    ),

    // ── 已删除的占位项（不留死枚举，与本项目一贯做法一致）──
    //
    // v1.1.62 起陆续删除：九格切图等实验室占位项、登录测试选项（升级为真实页）、
    // 头像（升级为真实裁切页）、微信 / QQ 登录（改为就地提示）、忘记密码（升级为真实页）。
    //
    // 本轮删除 AGENT（`lab_agent`，智能体）：它已由占位页升级为真实页面，
    // 走 [TabDestination.Agent] 真实路由。
    //
    // v1.1.62 起「头像」不再是留空占位页，改为真实的「选图 → 1:1 裁切 → 保存」，
    // 因此 PROFILE_AVATAR 已删除（同上，不留死枚举）。
    PROFILE_NICKNAME(
        value = "profile_nickname",
        title = "昵称",
        description = "昵称修改暂未开放"
    ),
    PROFILE_PHONE(
        value = "profile_phone",
        title = "手机号",
        description = "手机号绑定暂未开放"
    ),
    PROFILE_GENDER(
        value = "profile_gender",
        title = "性别",
        description = "性别设置暂未开放"
    );

    // ── 已删除的占位项（不留死枚举，与本项目一贯做法一致）──
    //
    // v1.1.62：「微信登录」「QQ 登录」不再进留空二级页，改为就地弹「功能开发中」提示
    // （需求：删除二级界面，改为消息提示），因此 SOCIAL_WECHAT / SOCIAL_QQ 已删除。
    //
    // v1.1.65：**FORGOT_PASSWORD 已删除** —— 「找回密码」本轮做成真实页面
    // （[TabDestination.ForgotPassword]，仅界面演示）。留空页不再存在，
    // 枚举项留着就是无引用的死代码；它的 `value`（`forgot_password`）也随之失效，
    // 且不可能被路由参数命中（旧值只由登录页那一处写入，那一处已改走真实路由）。

    companion object {
        fun fromValue(value: String?): PlaceholderEntry =
            entries.firstOrNull { it.value == value } ?: FEEDBACK
    }
}

/**
 * 一级入口的顺序列表，底部导航栏与各入口栈的初始化都以此为准。
 */
val APP_TABS: List<AppTab> = AppTab.entries.toList()
