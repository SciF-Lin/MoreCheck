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

    /**
     * 二级页：安装包下载（本轮需求 2）。
     *
     * 由「关于我们 → 检查更新」进入（默认行为，可在开发者选项里改成
     * 应用内网页 / 系统浏览器）。列出云存储里托管的全部**正式版**安装包，
     * 选一版下载并拉起系统安装器。需要登录云账号——云存储只对已登录用户开放读，
     * 见 `ApkDownloadScreen` 与 `ReleaseApkRepository` 的 KDoc。
     */
    ApkDownload("apk_download"),

    /** 二级页：实验室（由发现页的「实验室」入口推入）。 */
    Lab("lab"),

    /**
     * 二级页：实验室 → 效率工具分组。
     *
     * 把原本平铺在宫格里的「天气 / 记事本 / 智能体 / 番茄钟 / 课程表」收进这一页，
     * 让实验室首页的宫格不至于一眼望不到底。这些功能各自的 route 全部保留，
     * 老桌面快捷方式仍经 [fromRouteOrNull] 直达——本页只是多一个集中入口。
     */
    EfficiencyGroup("efficiency_group"),

    /**
     * 二级页：实验室 → 工具箱分组。
     *
     * 收纳「随机数 / 摩斯电码 / 图片压缩 / 做个决定 / 色卡」五个轻量小工具。
     * 与 [EfficiencyGroup] 同理：子功能 route 保留，本页只是聚合入口。
     */
    ToolboxGroup("toolbox_group"),

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
     * 二级页：实验室 → 贪吃蛇（内置的网页小游戏）。
     *
     * 内容是 `assets/snake/` 下那份自包含网页（Canvas 棋盘 + localStorage 存最高分 +
     * 滑动 / 方向键操作 + 三档难度），由 `SnakeScreen` 用 WebView 本地加载，
     * **不联网**。
     *
     * route 取 `"snake"`：与实验室宫格那一项
     * `LabRealPage.SNAKE("snake")` 的 `key` **同名**。
     * 这两处必须一致——桌面快捷方式把 `key` 原样写进 `EXTRA_LAB_PAGE`，
     * 本枚举的 [fromRouteOrNull] 再拿它比对 [route]；
     * 一旦不同名，快捷方式会**静默**落到首页（那条路径刻意不抛异常，见 [fromRouteOrNull]），
     * 排查起来非常费劲。已确认 `"snake"` 与本枚举其余 route 均不重名
     * （这也正是它能被 [fromRouteOrNull] 唯一命中的前提）。
     */
    Snake("snake"),

    /**
     * 二级页：实验室 → 数独（内置的网页小游戏，需求 17）。
     *
     * 内容是 `assets/sudoku/` 下那份自包含网页（5121 行单文件：题目生成引擎 +
     * 难度评分 + 笔记 / 高亮 / 统计 / 设置，localStorage 存进度），
     * 由 `SudokuScreen` 用 WebView 本地加载。
     *
     * ## ⚠️ 与贪吃蛇页**唯一的机制差异**：它走 https 而不是 file://
     *
     * 数独页面用 `new Blob(...)` + `URL.createObjectURL` 起 Worker 生成题目，
     * 而 Blob URL 的 origin 继承自文档——`file://` 文档的 origin 是 opaque，
     * Chromium 会以 `SecurityError` 拒绝 `new Worker`，页面只能降级到
     * 「主线程分片生成」（功能不中断，但生成期间主线程被占住）。
     * 因此 `SudokuScreen` 用 `WebViewAssetLoader` 把 assets 映射到
     * `https://appassets.androidplatform.net/assets/sudoku/index.html`。
     * 那个域名不在公网 DNS 中，**不产生任何网络请求**。
     *
     * route 取 `"sudoku"`：与实验室宫格那一项 `LabRealPage.SUDOKU("sudoku")` 的
     * `key` **同名**。这两处必须一致——桌面快捷方式把 `key` 原样写进
     * `EXTRA_LAB_PAGE`，本枚举的 [fromRouteOrNull] 再拿它比对 [route]；
     * 一旦不同名，快捷方式会**静默**落到首页（那条路径刻意不抛异常，见
     * [fromRouteOrNull]），排查起来非常费劲。已确认 `"sudoku"` 与本枚举其余
     * route 均不重名。
     */
    Sudoku("sudoku"),

    /**
     * 实验室 → 数独2（v1.1.74 需求 1，用户提供的第二份数独素材）。
     *
     * 内容是 `assets/sudoku2/index.html`（1094 行单文件：题目生成 + 高亮 / 冲突 /
     * 笔记 / 统计，`localStorage` 存局面快照与偏好），由 `Sudoku2Screen` 用 WebView
     * 本地加载，**不联网**。
     *
     * ## 与 [Sudoku] 是**两份不同素材**，不是新旧替代
     *
     * | | 数独 | 数独2 |
     * |---|---|---|
     * | 素材 | `assets/sudoku/`（5121 行） | `assets/sudoku2/`（1094 行） |
     * | 题目生成 | `new Blob` + Worker | 无 Worker，页面内直接生成 |
     * | 加载地址 | https（`WebViewAssetLoader`） | `file:///android_asset/` |
     * | 配色 | 写死深色 | **自带深浅色** |
     *
     * 因此两页是并列的两个游戏，各写一份实现（项目纪律：一屏一个自洽实现）。
     * 完整理由见 `Sudoku2Screen` 的 KDoc 对照表。
     *
     * ## ⚠️ route 取 `"sudoku2"`：与 `LabRealPage.SUDOKU2("sudoku2")` 的 key **同名**
     *
     * 理由与 [Snake] / [Sudoku] 完全相同：桌面快捷方式把 `key` 原样写进
     * `EXTRA_LAB_PAGE`，两处一旦不同名，快捷方式会**静默**落到首页（那条路径
     * 刻意不抛异常，见 [fromRouteOrNull]），排查起来非常费劲。
     * 已确认 `"sudoku2"` 与本枚举其余 route 均不重名
     * （它与 `"sudoku"` 是两个不同的字符串，不是前缀匹配）。
     *
     * ⚠️ 与另两个游戏一样，本项在 `TabNavHost` 里**没有** `composable` 目的地——
     * 它住在独立 Activity（`Sudoku2HostActivity`）里。本枚举项保留下来，
     * 作用是当**快捷方式 key 的契约锚点**（删了那条契约就失去校验对象）。
     * 快捷方式的实际分流在 `GameActivityHost.GAME_SHORTCUT_KEYS` 里，
     * 它有一行 `"sudoku2"`——漏了那行，桌面快捷方式会安静地落回首页。
     */
    Sudoku2("sudoku2"),

    /**
     * 二级页：实验室 → 番茄时钟（v1.1.73 新增）。
     *
     * 内容是 `assets/pomodoro/index.html`（用户提供的单文件网页：25/5/15 分钟三档、
     * 圆环进度、localStorage 存今日番茄数与累计专注分钟），由 `PomodoroScreen`
     * 用 WebView 本地加载，**不联网、无 Blob Worker**，因此走 `file:///android_asset/`
     * （与贪吃蛇同路，而不是数独那条 https + WebViewAssetLoader）。
     *
     * route 取 `"pomodoro"`：与实验室宫格 `LabRealPage.POMODORO("pomodoro")` 的 key
     * **同名**——这是桌面快捷方式直达链路的硬约束（见 [Snake] 的 KDoc）。
     */
    Pomodoro("pomodoro"),

    /**
     * 二级页：实验室 → 随机数生成（v1.1.73 新增，参考「一个木函」的同类功能）。
     *
     * 纯本地计算：范围 + 个数 + 是否去重，无持久化、不联网。
     */
    RandomNumber("random_number"),

    /**
     * 二级页：实验室 → 摩斯电码（v1.1.73 新增）。
     *
     * 文字与摩斯码的双向实时转换，纯本地查表、不联网。
     */
    MorseCode("morse_code"),

    /**
     * 二级页：实验室 → 图片压缩（v1.1.73 新增）。
     *
     * 选图走系统 Photo Picker（无需读相册权限），压缩在 IO 线程完成，
     * 保存到 MediaStore 的 `Pictures/MoreCheck`（与记事本保存图片同一条路）。
     */
    ImageCompress("image_compress"),

    /**
     * 二级页：实验室 → 做个决定（v1.1.73 新增）。
     *
     * 输入若干候选项，滚动动画后随机选出一个。纯本地、无持久化。
     */
    DecisionMaker("decision_maker"),

    /**
     * 二级页：实验室 → 色卡（2026-11-02 新增）。
     *
     * 中国色 526 + 日本色 250，自包含网页（`assets/colors`）：搜色名/拼音/HEX、
     * 按来源筛选、点色块看详情并复制 HEX/RGB/CMYK。纯本地、不联网。
     */
    ColorCard("color_card"),

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
    /** 登录密码：给已登录账号设置 / 修改密码（2026-11-03 密码登录配套）。 */
    SetPassword("set_password"),

    /**
     * 二级页：微信登录 · 绑定手机号（v1.2.4 新增，客户端先行的接缝页）。
     *
     * 正式入口是微信授权返回"未绑定"后自动跳转（服务端落地后生效）；
     * 当前的可见入口是登录页微信说明弹窗里的「体验绑定流程（调试）」。
     * 发码真实、绑定提交为接缝——详见 [com.morecheck.ui.auth.WechatBindPhoneScreen]。
     */
    WechatBindPhone("wechat_bind_phone"),

    /**
     * 二级页：官网（内嵌网页浏览，原名「看一看」）。
     *
     * 2026-10-25 起 route 带一个可选查询参数 `url`：空 = 打开默认首页（百度），
     * 非空 = 首次进入直接加载该地址（「检查更新应用内打开」经这里进 Releases 页）。
     * 参数名常量 [ARG_URL_NAME] 的摆放理由同 [ARG_PHONE_NAME]（枚举构造先于
     * companion 初始化，顶层 const 才能被枚举项引用）。
     */
    Search("search?url={$ARG_URL_NAME}"),

    /**
     * 二级页：课程表（实验室 → 课程表，v1.1.74 需求 10）。
     *
     * 数据来自内置素材 `assets/timetable/curriculum.ics`，**不联网、不持久化**，
     * 由 `TimetableRepository` 读 assets 并在内存里缓存。
     *
     * 取值 `"timetable"` **必须与 `LabRealPage.TIMETABLE` 的 key 同名**——
     * 这是桌面快捷方式的硬约束（理由同 [SUDOKU2]）：宫格长按「添加到桌面」时
     * 把 key 原样写进 `EXTRA_LAB_PAGE`，本枚举的 [fromRouteOrNull] 再拿它比对 [route]，
     * 一旦不同名，快捷方式会**静默**落到首页而不是本页。
     * 已确认 `"timetable"` 与本枚举其余全部 route 都不重名。
     */
    Timetable("timetable"),

    /** 二级页：权限管理（设置 → 通用）。 */
    Permissions("permissions"),

    /** 二级页：声音设置（设置 → 通用；计时器到点铃声）。 */
    TimerSound("timer_sound"),

    /** 二级页：全年打卡总览（点日历页标题栏年月进入）。 */
    YearOverview("year_overview"),

    /**
     * 二级页：同步管理（v3 云同步）。
     *
     * **按登录态二态显示**：未登录时显示引导卡（「去登录」→ [Login]）；
     * 已登录时显示云账号、同步内容清单、自动同步两个开关、手动同步按钮与上次结果。
     * 「我的」页的「同步管理」卡片与本页是同一功能的入口与本体。
     */
    SyncManage("sync_manage"),

    /**
     * 二级页：热搜（实验室 → 热搜，v1.1.77 需求 2，占位页升级为真实页面）。
     *
     * 数据来自腾讯云 CloudBase 云函数（https，云端每 4 小时抓取一次各大平台热搜），
     * 失败回退到内置快照。取值 `"hot_search"` 沿用原占位条目的 value——
     * 实验室宫格的快捷方式 key 因此**天然延续**：
     * 之前 pin 到桌面的「热搜」快捷方式不需要重建就能直达本页。
     * 已确认 `"hot_search"` 与本枚举其余全部 route 都不重名。
     */
    HotSearch("hot_search"),

    /**
     * 二级页：B站视频（2026-11-03：发现页新增，入口在「热搜」正下方）。
     *
     * v1.2.5 初版是一个 WebView 加载 B 站移动版；**v1.2.7 改为原生浏览 + WebView 播放**：
     * 推荐 / 热门 / 搜索三个 tab 全部走 B 站公开接口，详情页原生，播放页仍是 WebView。
     * 下载入口只在播放页顶栏（不常驻），可按接口真实返回的档位选择清晰度。
     * 链路说明见 `ui/web/BilibiliScreen.kt` 与 `data/net/BilibiliRepository.kt` 的 KDoc。
     */
    Bilibili("bilibili"),

    /**
     * 二级页：音乐（2026-11-05 需求：联网搜索 / 在线播放 / 收藏）。
     *
     * 入口在「发现 → B站视频」下方。数据走网易云音乐公开 Web 接口（不带登录态），
     * 播放用 media3 ExoPlayer，收藏存本机 DataStore。链路说明见
     * `ui/music/MusicScreen.kt` 与 `data/net/MusicRepository.kt` 的 KDoc。
     *
     * 取值 `"music"` 需与本枚举其余 route 互不重名（已核对：无冲突）。
     */
    Music("music"),

    /**
     * 二级页：音乐账号与音乐设置（2026-11-08 需求 2）。
     *
     * 入口是**设置页 → 音乐**（音乐页自己的「我的」在 2026-11-09 改成内嵌段，
     * 不再走这个路由，顶栏头像入口也一并去掉了）。账号（登录 / 退出）与音乐设置
     * （后台播放、音乐缓存）收在一处。
     *
     * 取值 `"music_account"` 需与本枚举其余 route 互不重名（已核对：无冲突）。
     */
    MusicAccount("music_account"),

    /**
     * 二级页：打卡提醒增强（2026-11-08 需求 5：从设置页内联分区搬成独立页）。
     *
     * 内容与原来完全一致（静默时段 / 漏打卡补推），只是不再挤在设置首页里，
     * 设置页改为「一行入口 + 副标题摘要」。
     */
    ReminderEnhance("reminder_enhance"),

    /**
     * 二级页：个性化（2026-11-08 需求 5：同 [ReminderEnhance]，从内联分区搬成独立页）。
     */
    Personalization("personalization"),

    /**
     * 今日待办（v1.1.82：实验室宫格新增，内嵌用户的 todo-app 单页应用）。
     *
     * WebView 加载 `assets/todo/index.html`，登录与数据都走 WorkBuddy 云
     * （endpoint 在 assets 副本里固化为绝对 URL，理由见 `TodoTodayScreen` 的 KDoc）。
     * 取值 `"todo_today"` 与 `LabRealPage.TODO_TODAY.key` 同名——
     * 桌面快捷方式 / 宫格排序用的 key 与路由同名这条纪律继续成立。
     */
    TodoToday("todo_today"),

    /**
     * 成绩收集统计（2026-10-28：实验室宫格新增，内嵌成绩应用单页应用）。
     *
     * WebView 加载 `assets/score/index.html`，登录与数据都走 WorkBuddy 云
     * （origin 映射到成绩应用自己的域名，理由见 `ScoreScreen` 的 KDoc）。
     * 取值 `"score_stats"` 与 `LabRealPage.SCORE_STATS.key` 同名——
     * 「宫格 key = 路由」这条纪律继续成立。
     */
    ScoreStats("score_stats"),

    /**
     * GitHub 热门项目（2026-10-27 需求：实验室 → GitHub 热门）。
     *
     * 端上直连 GitHub Search API（`pushed:>=N天 stars:>=10` 近似官方 Trending），
     * 按「周期 + 语言」缓存 10 分钟。取值 `"github_trending"` 与
     * `LabRealPage.GITHUB_TRENDING.key` 同名——「宫格 key = 路由」这条纪律继续成立。
     */
    GithubTrending("github_trending"),

    /**
     * 去 AI 味（2026-10-27：实验室 → 去 AI 味）。
     *
     * WebView 承载 `assets/deai/index.html`（用户自建的「去AI味改写」网页应用），
     * 改写请求走 WorkBuddy 云 LLM（`deai-rewriter-64769.app.workbuddy.host`）。
     * 取值 `"de_ai"` 与 `LabRealPage.DE_AI.key` 同名。
     */
    DeAi("de_ai"),

    /**
     * 知识问答（2026-11-09：实验室 → 知识问答）。
     *
     * WebView 承载 `assets/knowledge_qa/index.html`（用户自建的「知识问答 Agent」网页应用），
     * 检索与作答走 WorkBuddy 云数据库与 LLM（`knowledge-qa-agent-91639.app.workbuddy.host`）。
     * 取值 `"knowledge_qa"` 与 `LabRealPage.KNOWLEDGE_QA.key` 同名。
     */
    KnowledgeQa("knowledge_qa"),

    /**
     * 域名比价（2026-11-09：实验室 → 域名比价）。
     *
     * 页面从线上 Node 服务加载（价格表由服务端现抓、SSE 推送），
     * 取值 `"domain_price"` 与 `LabRealPage.DOMAIN_PRICE.key` 同名。
     */
    DomainPrice("domain_price"),

    /**
     * 开屏密码设置（2026-10-25 需求：设置 → 安全 → 开屏密码，二级页）。
     *
     * 手势 + 指纹的应用锁设置页；运行时门闸在 `MainActivity`（`AppLockGate`）。
     * 与实验室无关，因此没有同名的 `LabRealPage` key。
     */
    AppLock("app_lock"),

    /**
     * 设备信息查询（2026-10-25 需求：从「开发者选项」二级页迁到 设置）。
     *
     * 原先寄居在 `DeveloperOptionsScreen` 的就地覆盖层里；迁出后成为一个
     * 普通导航目的地，入口挂在设置页（详见 `UiInspectorScreen` 的 KDoc）。
     */
    UiInspector("ui_inspector"),

    /**
     * 错误日志（2026-11-14 需求 3）。
     *
     * 入口在设置页的「问题与诊断」分组，读的是 `util/ErrorLogStore` 落在
     * `filesDir/logs` 的本机崩溃 / 报错记录。与设备信息查询一样，属于"排查用"的
     * 只读页面，所以排在设置页最后一段。
     */
    ErrorLog("error_log");

    /** 带参数的页面需要自行拼接 `route/{arg}` 形式的路由模板。 */
    companion object {
        const val ARG_TASK_ID = "taskId"
        const val ARG_PLACEHOLDER = "placeholder"

        /** 密码登录第二步要显示的手机号（见 [PasswordEntry]）。 */
        const val ARG_PHONE = ARG_PHONE_NAME

        /** 官网首次进入要加载的地址（见 [Search]）。 */
        const val ARG_URL = ARG_URL_NAME
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
 * 官网的地址查询参数名（摆放理由与 [ARG_PHONE_NAME] 完全相同：
 * 枚举项构造先于 companion 初始化，顶层编译期常量才能被它引用）。
 */
private const val ARG_URL_NAME = "url"

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
    // 「看一看」已在 v1.1.52 更名为「官网」并实装为内嵌网页浏览，
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

    // 「热搜」占位项已删除（v1.1.77）：升级为真实页面，走 [TabDestination.HotSearch]
    // 真实路由。它的 value（"hot_search"）被新路由沿用——老快捷方式无需重建即直达。

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
