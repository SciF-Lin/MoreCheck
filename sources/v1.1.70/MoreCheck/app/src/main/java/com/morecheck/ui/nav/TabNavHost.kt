package com.morecheck.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.morecheck.ui.calendar.YearOverviewScreen
import com.morecheck.ui.mine.AGREEMENT_MARKDOWN
import com.morecheck.ui.mine.EmptyPlaceholderScreen
import com.morecheck.ui.mine.LegalTextScreen
import com.morecheck.ui.mine.MyScreen
import com.morecheck.ui.mine.PersonalInfoScreen
import com.morecheck.ui.lab.DeveloperOptionsScreen
import com.morecheck.ui.lab.LabScreen
import com.morecheck.ui.lab.LoginPromptSettingScreen
import com.morecheck.ui.lab.NotebookScreen
import com.morecheck.ui.lab.WeatherScreen
import com.morecheck.ui.agent.AgentChatScreen
import com.morecheck.ui.agent.AiSettingsScreen
import com.morecheck.ui.web.SearchWebScreen
import com.morecheck.ui.backup.BackupScreen
import com.morecheck.ui.settings.PermissionsScreen
import com.morecheck.ui.settings.SoundSettingsScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.mine.PRIVACY_TEXT
import com.morecheck.ui.mine.SettingsPage
import com.morecheck.ui.auth.ForgotPasswordScreen
import com.morecheck.ui.auth.LoginScreen
import com.morecheck.ui.auth.PasswordEntryScreen
import com.morecheck.ui.auth.PasswordLoginScreen
import com.morecheck.ui.auth.RegisterScreen
import com.morecheck.ui.records.RecordsScreen
import com.morecheck.ui.scan.ScanScreen
import com.morecheck.ui.settings.AboutScreen
import com.morecheck.ui.settings.ChangelogScreen
import com.morecheck.ui.tasks.TaskEditScreen
import com.morecheck.ui.tasks.TaskListScreen
import com.morecheck.util.openInBrowser

/**
 * 页面推入 / 退出的转场时长。
 *
 * 微信式节奏：进入下级页面从右向左推入，返回从左向右退出。
 * 这里用「整屏宽度」滑移，动效保持对称，进出都是 [NAV_DURATION_MS]，
 * 因此在动画中途反向操作也不会出现速度突变。
 */
private const val NAV_DURATION_MS = 280

/**
 * 微信式推入：新页面从屏幕右侧滑入，**整屏覆盖**在上一级页面上。
 *
 * ## ⚠️ 这里刻意**不带任何淡入淡出**
 *
 * 曾经是 `slideIn + fadeIn`、`pushExit = fadeOut`。两层同时改变透明度时，
 * 中间帧的合成不透明度只有 `1-(1-0.5)×(1-0.5) = 0.75`——也就是**有两成半是透明的**。
 * 而底部导航栏画在 `NavHost` **下层**（见 `MoreCheckApp`），于是它就从这两成半里透出来，
 * 表现为「二级页切三级页时底栏错误地半透明浮现」（需求 bug3）。
 *
 * 去掉淡入淡出后：
 * - 新页面始终**完全不透明**地滑入，盖住多少就是多少；
 * - 旧页面原地不动、不淡出，被新页面逐步遮住 —— 这正是微信推入的手感。
 *
 * 这条也顺带保证了「三级页不会透出二级页」，因为两层都不存在半透明中间态。
 */
private fun pushEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(NAV_DURATION_MS),
        initialOffsetX = { fullWidth -> fullWidth }
    )

/** 被覆盖的旧页面原地不动，**不做淡出**（理由见 [pushEnter]）。 */
private fun pushExit(): ExitTransition = ExitTransition.None

/** 返回：当前页面向右滑出（沿原路滑回屏幕右侧），同样不淡出。 */
private fun popExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(NAV_DURATION_MS),
        targetOffsetX = { fullWidth -> fullWidth }
    )

/** 返回：被让出的下层页面原地不动（它本就完整可见，不需要淡入）。 */
private fun popEnter(): EnterTransition = EnterTransition.None

/**
 * 「检查更新」跳转的目标：GitHub Releases 页。
 *
 * 刻意**固定指向 Releases 列表**而不是某个具体 tag：新版本发布后这里不用跟着改，
 * 用户也总能看到最新一版。
 */
private const val RELEASES_URL = "https://github.com/SciF-Lin/MoreCheck/releases"

/**
 * 一个一级入口的独立页面栈。
 *
 * 状态保留由两层保证：
 * 1. 各入口的 [NavHostController] 建在骨架根部且不销毁，回退栈天然保留；
 * 2. 页面内的 `rememberSaveable` 状态（含 LazyListState 的滚动位置）由 Compose
 *    的可保存状态机制托管，入口重新进入时自动恢复。
 *
 * 返回键策略由骨架统一处理（见 `MoreCheckApp.kt` 的 `MoreCheckRoot`）：
 * 栈内还有页面则回退当前入口的内部页面；已到入口首页时交还系统退出应用。
 * 这里刻意**不再注册** BackHandler，避免同一动作出现两个处理器、
 * 生效顺序依赖注册先后而变得不可预期。
 */
@Composable
fun TabNavHost(
    tab: AppTab,
    navController: NavHostController,
    /** 直接显示一条提示原文（不做「将在后续任务实现」包装）。 */
    onActionMessage: (String) -> Unit,
    maskedPhone: String?,
    momentsBadge: Int,
    onMomentsOpened: () -> Unit,
    /**
     * 一级入口首页的底部让位高度（= 底部导航栏高度）。
     *
     * 底栏由 `MoreCheckApp` 画在**本 NavHost 之下**，二级页会整页盖住它。
     * 因此只有「一级入口首页」需要给底栏留出空间；二级页**不留**，铺满整屏。
     *
     * 关键：这个值在**根目的地存续期间是常量**（不随导航动画变化），
     * 所以推入/返回二级页时根页面的布局不会变、不会跳动。
     * 反过来用 `Scaffold.bottomBar` 或给整页加会动画的内边距，都会让底栏变成
     * 「自己在缺口里消失」而不是「被新页面盖住」。
     */
    rootBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    // 「检查更新」要交系统浏览器打开，需要 Context 发起 Intent。
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = tab.rootRoute,
        modifier = modifier,
        enterTransition = { pushEnter() },
        exitTransition = { pushExit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() }
    ) {
        composable(tab.routeOf(TabDestination.Root)) {
            // 只有一级入口首页给底栏让位；二级页各自铺满整屏（它们会盖住底栏）。
            Box(Modifier.padding(bottom = rootBottomPadding)) {
                TabRootScreen(
                    tab = tab,
                    navController = navController,
                    onOpenRecords = { navController.navigate(tab.routeOf(TabDestination.Records)) },
                    onOpenTasks = { navController.navigate(tab.routeOf(TabDestination.Tasks)) },
                    onOpenAbout = { navController.navigate(tab.routeOf(TabDestination.About)) },
                    onOpenScanner = { navController.navigate(tab.routeOf(TabDestination.Scanner)) },
                    // 右上角「+」→ 新建任务：与「任务管理」页的 FAB 走同一条编辑路由。
                    onNewTask = { navController.navigate(tab.taskEditRoute(taskId = -1L)) },
                    onOpenMoments = {
                        // 需求：点开朋友圈后未读角标消失。
                        onMomentsOpened()
                        navController.navigate(tab.routeOf(TabDestination.Moments))
                    },
                    onOpenLab = { navController.navigate(tab.routeOf(TabDestination.Lab)) },
                    onOpenSearch = { navController.navigate(tab.routeOf(TabDestination.Search)) },
                    maskedPhone = maskedPhone,
                    momentsBadge = momentsBadge
                )
            }
        }

        // 扫一扫：调起相机实时扫码，识别结果在底部卡片中展示。
        composable(tab.routeOf(TabDestination.Scanner)) {
            ScanScreen(onBack = { navController.popBackStack() })
        }

        // 朋友圈：Hello World 展示页（样式与开屏一致）。
        composable(tab.routeOf(TabDestination.Moments)) {
            MomentsScreen(onBack = { navController.popBackStack() })
        }

        // 登录页：由「我的 → 个人信息」或「设置 → 登录」推入，不主动弹出。
        composable(tab.routeOf(TabDestination.Login)) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onOpenRegister = { navController.navigate(tab.routeOf(TabDestination.Register)) },
                // v1.1.65：「忘记密码」不再是留空页，进真实的找回密码页（仅界面演示）。
                onOpenForgotPassword = {
                    navController.navigate(tab.routeOf(TabDestination.ForgotPassword))
                },
                // v1.1.65：新增的「使用密码登录」入口 → 密码登录第一步（只填手机号）。
                onOpenPasswordLogin = {
                    navController.navigate(tab.routeOf(TabDestination.PasswordLogin))
                }
            )
        }

        // 密码登录第一步：只填手机号（需求：仿微信，先填手机号再进第二步填密码）。
        composable(tab.routeOf(TabDestination.PasswordLogin)) {
            PasswordLoginScreen(
                onBack = { navController.popBackStack() },
                onOpenForgotPassword = {
                    navController.navigate(tab.routeOf(TabDestination.ForgotPassword))
                },
                // 手机号用**查询参数**带到第二步：第二步要把它显示在头像下方。
                // 这里不做 URL 编码——手机号只可能由输入框产出，是纯数字。
                onPhoneSubmitted = { phone ->
                    navController.navigate("${tab.name}.password_entry?${TabDestination.ARG_PHONE}=$phone")
                }
            )
        }

        // 密码登录第二步：填密码（任意密码都提示成功，仅界面演示）。
        composable(
            route = tab.routeOf(TabDestination.PasswordEntry),
            arguments = listOf(
                navArgument(TabDestination.ARG_PHONE) {
                    // 用字符串 + 默认空串而不是 NavType 的可空类型：
                    // 直接进本页（没带上一步的手机号）时不该崩，显示空串即可。
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            PasswordEntryScreen(
                phone = entry.arguments?.getString(TabDestination.ARG_PHONE).orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenForgotPassword = {
                    navController.navigate(tab.routeOf(TabDestination.ForgotPassword))
                },
                // 「用短信验证码登录」= 回到验证码登录那条链路。
                // 用路由回退而不是连弹两次：密码登录可能是从登录页进的，
                // 也可能（将来）从别处直达，按路由回退比数层数稳。
                onOpenCodeLogin = {
                    val popped = navController.popBackStack(
                        route = tab.routeOf(TabDestination.Login),
                        inclusive = false
                    )
                    if (!popped) navController.popBackStack()
                },
                // 登录成功：**回到整条登录流程的起点**。
                //
                // 不用 `popBackStack(PersonalInfo, inclusive = false)`：登录页有两个入口——
                // 「我的 → 个人信息 → 登录」和开屏引导弹窗的「去登录」（直接推入登录页，
                // 栈里根本没有「个人信息」这一项）。实测后者会命中兜底分支、只弹掉一层，
                // 用户停在「密码登录」第一步上，看起来像没登录成功。
                //
                // 改成弹到**登录页本身**（inclusive = true），两种入口都正确收敛到
                // 「登录流程是从哪进来的」，与既有验证码登录成功后的 `onBack()` 落点一致。
                onLoggedIn = {
                    val popped = navController.popBackStack(
                        route = tab.routeOf(TabDestination.Login),
                        inclusive = true
                    )
                    if (!popped) navController.popBackStack()
                }
            )
        }

        // 找回密码：手机号 + 短信验证码，仅界面演示（不接真实找回流程）。
        composable(tab.routeOf(TabDestination.ForgotPassword)) {
            ForgotPasswordScreen(onBack = { navController.popBackStack() })
        }

        // 注册页：由登录页的「注册」按钮推入。
        composable(tab.routeOf(TabDestination.Register)) {
            RegisterScreen(
                onBack = { navController.popBackStack() },
                onOpenAgreement = { navController.navigate(tab.routeOf(TabDestination.Agreement)) },
                onOpenPrivacy = { navController.navigate(tab.routeOf(TabDestination.Privacy)) },
                // 需求：注册成功后**直接回到「我的信息」**。
                //
                // 这条页面栈是 `我的信息 → 登录 → 注册账号`，所以要一次性弹掉两层。
                // 用 `popBackStack(route, inclusive = false)` 精确回到「我的信息」这一项，
                // 而不是连弹两次 `popBackStack()`：注册页其实**不一定**是从「我的信息」
                // 那条路进来的（理论上将来可能有别的入口），按路由回退比数层数稳。
                //
                // 兜底：万一栈里没有「我的信息」（例如从开屏引导弹窗的「去登录」
                // 直接进的登录页，再点「注册」——那条栈是 `我的 → 登录 → 注册账号`），
                // `popBackStack` 会返回 false 且什么都不做，此时退回**整条登录流程的起点**
                // （弹到登录页本身，inclusive = true），与密码登录成功后的落点同一口径，
                // 至少不会把用户卡在注册页上。
                onRegistered = {
                    val popped = navController.popBackStack(
                        route = tab.routeOf(TabDestination.PersonalInfo),
                        inclusive = false
                    )
                    if (!popped) {
                        val backToLoginOrigin = navController.popBackStack(
                            route = tab.routeOf(TabDestination.Login),
                            inclusive = true
                        )
                        if (!backToLoginOrigin) navController.popBackStack()
                    }
                }
            )
        }

        composable(tab.routeOf(TabDestination.Records)) {
            RecordsScreen(onBack = { navController.popBackStack() })
        }

        // 全年打卡总览：点日历页标题栏的年月进入。只读，日期不可点。
        composable(tab.routeOf(TabDestination.YearOverview)) {
            YearOverviewScreen(onBack = { navController.popBackStack() })
        }

        // 数据备份：导出 / 导入都走系统文件选择器（SAF），不需要任何存储权限。
        // 实现早已写在 ui/backup/ 里，此前只是没接线；这里改为调用真实页面。
        composable(tab.routeOf(TabDestination.Backup)) {
            BackupScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage,
                viewModel = viewModel(factory = AppViewModelProvider.Factory)
            )
        }

        composable(tab.routeOf(TabDestination.Tasks)) {
            TaskListScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(tab.taskEditRoute(taskId = -1L)) },
                onEdit = { id -> navController.navigate(tab.taskEditRoute(taskId = id)) }
            )
        }

        // ---- 任务3：「我的」的子页 -------------------------------------------

        composable(tab.routeOf(TabDestination.Settings)) {
            SettingsPage(
                onBack = { navController.popBackStack() },
                onOpenPermissions = {
                    navController.navigate(tab.routeOf(TabDestination.Permissions))
                },
                onOpenSound = { navController.navigate(tab.routeOf(TabDestination.TimerSound)) }
            )
        }

        // 声音设置：倒计时结束的铃声开关与铃声选择（系统预设 + SAF 导入）。
        composable(tab.routeOf(TabDestination.TimerSound)) {
            SoundSettingsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 权限管理：三个开关 + 使用场景说明，开关默认全关。
        composable(tab.routeOf(TabDestination.Permissions)) {
            PermissionsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        composable(tab.routeOf(TabDestination.PersonalInfo)) {
            PersonalInfoScreen(
                maskedPhone = maskedPhone,
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) },
                // 四行「基础资料」自 1.1.53 起改为只读，此形参保留以兼容调用方。
                onOpenPlaceholder = { value ->
                    navController.navigate("${tab.name}.placeholder/$value")
                },
                onMessage = onActionMessage
            )
        }

        composable(tab.routeOf(TabDestination.Agreement)) {
            LegalTextScreen(
                title = "用户协议",
                body = AGREEMENT_MARKDOWN,
                onBack = { navController.popBackStack() }
            )
        }

        composable(tab.routeOf(TabDestination.Privacy)) {
            LegalTextScreen(
                title = "隐私政策",
                body = PRIVACY_TEXT,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = tab.placeholderPattern(),
            arguments = listOf(navArgument(TabDestination.ARG_PLACEHOLDER) { type = NavType.StringType })
        ) { entry ->
            val item = PlaceholderEntry.fromValue(
                entry.arguments?.getString(TabDestination.ARG_PLACEHOLDER)
            )
            EmptyPlaceholderScreen(
                title = item.title,
                description = item.description,
                onBack = { navController.popBackStack() }
            )
        }

        composable(tab.routeOf(TabDestination.About)) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenAgreement = { navController.navigate(tab.routeOf(TabDestination.Agreement)) },
                onOpenPrivacy = { navController.navigate(tab.routeOf(TabDestination.Privacy)) },
                // 「检查更新」实装：跳到 GitHub Releases 页，由系统浏览器打开。
                onCheckUpdate = {
                    openInBrowser(
                        context = context,
                        url = RELEASES_URL,
                        onMessage = onActionMessage
                    )
                },
                onOpenChangelog = { navController.navigate(tab.routeOf(TabDestination.Changelog)) },
                // v1.1.69：「支持作者」占位入口（关于页左下角），点了只弹提示、不导航。
                onOpenSupport = { onActionMessage("支持作者功能开发中") },
                // 「帮助与反馈」从「关于我们」标题栏的扳手图标进入。
                onOpenFeedback = {
                    navController.navigate("${tab.name}.placeholder/${PlaceholderEntry.FEEDBACK.value}")
                }
            )
        }

        // 更新日志：内容全部内置，不联网。
        composable(tab.routeOf(TabDestination.Changelog)) {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }

        // 实验室：发现 → 实验室。宫格里「天气」与「开发者选项」都是真实页面，
        // 底部「新增功能」仍只进入留空二级页。
        composable(tab.routeOf(TabDestination.Lab)) {
            LabScreen(
                onBack = { navController.popBackStack() },
                onOpenEntry = { value ->
                    navController.navigate("${tab.name}.placeholder/$value")
                },
                onOpenWeather = { navController.navigate(tab.routeOf(TabDestination.Weather)) },
                onOpenDeveloperOptions = {
                    navController.navigate(tab.routeOf(TabDestination.DeveloperOptions))
                },
                // v1.1.65：宫格第三项「登录提示功能」（控制首启那个引导弹窗的开关）。
                onOpenLoginPromptSetting = {
                    navController.navigate(tab.routeOf(TabDestination.LoginPromptSetting))
                },
                // v1.1.66：宫格第四项「记事本」（一条笔记，文字 + 图片，返回自动保存）。
                onOpenNotebook = {
                    navController.navigate(tab.routeOf(TabDestination.Notebook))
                },
                // 本轮：宫格第五项「智能体」由占位页升级为真实页面（AI 对话）。
                onOpenAgent = {
                    navController.navigate(tab.routeOf(TabDestination.Agent))
                }
            )
        }

        // 记事本：只有一条笔记，返回时自动保存（没有「保存」按钮）。
        composable(tab.routeOf(TabDestination.Notebook)) {
            NotebookScreen(onBack = { navController.popBackStack() })
        }

        // 智能体：AI 对话（OpenAI 兼容，用户自带 API Key，支持发送图片）。
        composable(tab.routeOf(TabDestination.Agent)) {
            AgentChatScreen(
                onBack = { navController.popBackStack() },
                onOpenSettings = {
                    navController.navigate(tab.routeOf(TabDestination.AgentSettings))
                },
                onMessage = onActionMessage
            )
        }

        // 智能体设置：服务提供商 / API Key / 模型名 / 系统提示词 / 清空记录。
        composable(tab.routeOf(TabDestination.AgentSettings)) {
            AiSettingsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 登录提示功能：只有一个开关（是否在打开软件时提示登录）。
        composable(tab.routeOf(TabDestination.LoginPromptSetting)) {
            LoginPromptSettingScreen(onBack = { navController.popBackStack() })
        }

        // 开发者选项：本轮由「登录测试选项」占位页升级而来（需求：更名 + 仿手机开发者选项）。
        // 页面自身不持有路由知识，只通过 onBack / onMessage 与外界交互。
        composable(tab.routeOf(TabDestination.DeveloperOptions)) {
            DeveloperOptionsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 天气：按「定位」开关门控，联网查询当地天气。
        // 需要权限时把人送到「权限管理」页，那里有开关与系统设置入口。
        composable(tab.routeOf(TabDestination.Weather)) {
            WeatherScreen(
                onBack = { navController.popBackStack() },
                onOpenPermissionSettings = {
                    navController.navigate(tab.routeOf(TabDestination.Permissions))
                },
                onMessage = onActionMessage
            )
        }

        // 搜一搜（原「看一看」）：内嵌网页浏览，默认百度首页。
        composable(tab.routeOf(TabDestination.Search)) {
            SearchWebScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        composable(
            route = tab.taskEditPattern(),
            arguments = listOf(
                navArgument(TabDestination.ARG_TASK_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) { entry ->
            val taskId = entry.arguments?.getLong(TabDestination.ARG_TASK_ID) ?: -1L
            TaskEditScreen(taskId = taskId, onDone = { navController.popBackStack() })
        }
    }
}

/** 某入口的「新建 / 编辑任务」实参路由。 */
internal fun AppTab.taskEditRoute(taskId: Long): String =
    "${routeOf(TabDestination.TaskEdit)}?${TabDestination.ARG_TASK_ID}=$taskId"

/** 某入口的「新建 / 编辑任务」路由模板（含占位符，供 composable 注册）。 */
internal fun AppTab.taskEditPattern(): String =
    "${name}.${TabDestination.ROUTE_TASK_EDIT}"

/** 某入口的占位页路由模板（含路径占位符，供 composable 注册）。 */
internal fun AppTab.placeholderPattern(): String =
    "${name}.${TabDestination.Placeholder.route}"

/**
 * 入口首页。后续任务会把各入口的真实内容替换进来；
 * 当前只提供结构与统一的手势反馈，便于先对齐骨架。
 */
@Composable
private fun TabRootScreen(
    tab: AppTab,
    navController: NavHostController,
    onOpenRecords: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSearch: () -> Unit,
    onNewTask: () -> Unit,
    maskedPhone: String?,
    momentsBadge: Int
) {
    when (tab) {
        AppTab.HOME -> com.morecheck.ui.home.HomeScreen(
            onOpenScanner = onOpenScanner,
            onOpenRecords = onOpenRecords,
            onNewTask = onNewTask
        )

        AppTab.CALENDAR -> com.morecheck.ui.calendar.CalendarScreen(
            onOpenScanner = onOpenScanner,
            onOpenRecords = onOpenRecords,
            onNewTask = onNewTask,
            // 点标题栏年月 → 全年打卡总览。
            onOpenYearOverview = {
                navController.navigate(tab.routeOf(TabDestination.YearOverview))
            }
        )

        AppTab.DISCOVER -> DiscoverScreen(
            momentsBadge = momentsBadge,
            onOpenScanner = onOpenScanner,
            onOpenMoments = onOpenMoments,
            onOpenRecords = onOpenRecords,
            onNewTask = onNewTask,
            onOpenLab = onOpenLab,
            onOpenSearch = onOpenSearch,
            // 看一看 / 更多功能：与扫一扫一样进入通用占位二级页。
            // value 与 PlaceholderEntry.value 一致，由 placeholderPattern() 注册的路由承载。
            onOpenPlaceholder = { value ->
                navController.navigate("${tab.name}.placeholder/$value")
            }
        )

        AppTab.MINE -> MyScreen(
            onOpenPersonalInfo = { navController.navigate(tab.routeOf(TabDestination.PersonalInfo)) },
            onOpenTasks = onOpenTasks,
            onOpenRecords = onOpenRecords,
            onOpenSettings = { navController.navigate(tab.routeOf(TabDestination.Settings)) },
            onOpenAbout = onOpenAbout,
            onOpenBackup = { navController.navigate(tab.routeOf(TabDestination.Backup)) },
            maskedPhone = maskedPhone
        )
    }
}
