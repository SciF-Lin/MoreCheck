package com.morecheck.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.morecheck.ui.mine.AGREEMENT_TEXT
import com.morecheck.ui.mine.EmptyPlaceholderScreen
import com.morecheck.ui.mine.LegalTextScreen
import com.morecheck.ui.mine.MyScreen
import com.morecheck.ui.mine.PersonalInfoScreen
import com.morecheck.ui.mine.PRIVACY_TEXT
import com.morecheck.ui.mine.SettingsPage
import com.morecheck.ui.records.RecordsScreen
import com.morecheck.ui.settings.AboutScreen
import com.morecheck.ui.tasks.TaskEditScreen
import com.morecheck.ui.tasks.TaskListScreen

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
 * 上一级页面保持不动（`pushExit` 不做位移），因此视觉上就是新页面把旧页面盖住，
 * 而不是两页一起平移。
 */
private fun pushEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(NAV_DURATION_MS),
        initialOffsetX = { fullWidth -> fullWidth }
    ) + fadeIn(tween(NAV_DURATION_MS))

/** 被覆盖的旧页面原地不动，仅在末尾轻微淡出，避免退出瞬间闪一下。 */
private fun pushExit(): ExitTransition =
    fadeOut(animationSpec = tween(NAV_DURATION_MS))

/** 返回：当前页面从右向左退出（沿原路滑回屏幕右侧）。 */
private fun popExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(NAV_DURATION_MS),
        targetOffsetX = { fullWidth -> fullWidth }
    ) + fadeOut(tween(NAV_DURATION_MS))

/** 返回：被让出的下层页面原地淡入复位。 */
private fun popEnter(): EnterTransition =
    fadeIn(animationSpec = tween(NAV_DURATION_MS))

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
    onPlaceholderAction: (String) -> Unit,
    /** 直接显示一条提示原文（不做「将在后续任务实现」包装）。 */
    onActionMessage: (String) -> Unit,
    maskedPhone: String?,
    momentsBadge: Int,
    onMomentsOpened: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            TabRootScreen(
                tab = tab,
                navController = navController,
                onOpenRecords = { navController.navigate(tab.routeOf(TabDestination.Records)) },
                onOpenTasks = { navController.navigate(tab.routeOf(TabDestination.Tasks)) },
                onOpenAbout = { navController.navigate(tab.routeOf(TabDestination.About)) },
                onOpenScanner = { navController.navigate(tab.routeOf(TabDestination.Scanner)) },
                onOpenMoments = {
                    // 需求：点开朋友圈后未读角标消失。
                    onMomentsOpened()
                    navController.navigate(tab.routeOf(TabDestination.Moments))
                },
                onPlaceholderAction = onPlaceholderAction,
                maskedPhone = maskedPhone,
                momentsBadge = momentsBadge
            )
        }

        // 扫一扫：留空二级页。
        composable(tab.routeOf(TabDestination.Scanner)) {
            EmptyPlaceholderScreen(
                title = "扫一扫",
                description = "扫一扫功能暂未开放",
                onBack = { navController.popBackStack() }
            )
        }

        // 朋友圈：Hello World 展示页（样式与开屏一致）。
        composable(tab.routeOf(TabDestination.Moments)) {
            MomentsScreen(onBack = { navController.popBackStack() })
        }

        // 登录页：保留入口，但二级页按要求留空（不主动弹出）。
        composable(tab.routeOf(TabDestination.Login)) {
            EmptyPlaceholderScreen(
                title = "登录",
                description = "登录功能暂未开放",
                onBack = { navController.popBackStack() }
            )
        }

        composable(tab.routeOf(TabDestination.Records)) {
            RecordsScreen(onBack = { navController.popBackStack() })
        }

        // 数据备份：保留入口，但二级页按要求留空。
        composable(tab.routeOf(TabDestination.Backup)) {
            EmptyPlaceholderScreen(
                title = "数据备份",
                description = "备份与恢复功能暂未开放",
                onBack = { navController.popBackStack() }
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
                onOpenLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) },
                onOpenBackup = { navController.navigate(tab.routeOf(TabDestination.Backup)) }
            )
        }

        composable(tab.routeOf(TabDestination.PersonalInfo)) {
            PersonalInfoScreen(
                maskedPhone = maskedPhone,
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) }
            )
        }

        composable(tab.routeOf(TabDestination.Agreement)) {
            LegalTextScreen(
                title = "用户协议",
                body = AGREEMENT_TEXT,
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
                onCheckUpdate = { onActionMessage("暂无新版本") }
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
    onPlaceholderAction: (String) -> Unit,
    maskedPhone: String?,
    momentsBadge: Int
) {
    when (tab) {
        AppTab.HOME -> com.morecheck.ui.home.HomeScreen(
            onOpenScanner = onOpenScanner,
            onOpenRecords = onOpenRecords
        )

        AppTab.CALENDAR -> com.morecheck.ui.calendar.CalendarScreen(
            onOpenScanner = onOpenScanner,
            onOpenRecords = onOpenRecords
        )

        AppTab.DISCOVER -> DiscoverScreen(
            momentsBadge = momentsBadge,
            onOpenScanner = onOpenScanner,
            onOpenMoments = onOpenMoments,
            onOpenRecords = onOpenRecords,
            onItemClick = onPlaceholderAction
        )

        AppTab.MINE -> MyScreen(
            onOpenPersonalInfo = { navController.navigate(tab.routeOf(TabDestination.PersonalInfo)) },
            onOpenTasks = onOpenTasks,
            onOpenRecords = onOpenRecords,
            onOpenSettings = { navController.navigate(tab.routeOf(TabDestination.Settings)) },
            onOpenAbout = onOpenAbout,
            onOpenBackup = { navController.navigate(tab.routeOf(TabDestination.Backup)) },
            onOpenPlaceholder = { value ->
                navController.navigate("${tab.name}.placeholder/$value")
            },
            maskedPhone = maskedPhone
        )
    }
}
