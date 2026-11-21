package com.morecheck.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.morecheck.ui.auth.AuthViewModel
import com.morecheck.ui.nav.APP_TABS
import com.morecheck.ui.nav.AppTab
import com.morecheck.ui.nav.SplashScreen
import com.morecheck.ui.nav.TabDestination
import com.morecheck.ui.nav.TabNavHost
import com.morecheck.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 启动页停留时长（含自身淡入时间）。 */
private const val SPLASH_DURATION_MS = 900L

/**
 * 启动页门闸（任务2）。
 *
 * 先显示浅色启动页（中间 `Hello World`），短暂停留后进入主界面；
 * 交接处用淡入淡出，避免两屏硬切。启动页只在**应用启动时**出现一次。
 */
@Composable
fun SplashGate(content: @Composable () -> Unit) {
    var showSplash by rememberSaveable { mutableStateOf(true) }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MS)
        showSplash = false
    }

    // 启动页是浅色底，状态栏图标必须是深色，否则白底白图标看不清；
    // 进入主界面后交还给应用原本的样式。
    LaunchedEffect(showSplash, window) {
        if (window == null) return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = showSplash
        controller.isAppearanceLightNavigationBars = showSplash
    }

    Box {
        // 主界面在启动页下面同步准备好，淡入时不会出现空白帧。
        AnimatedVisibility(visible = !showSplash, enter = fadeIn(tween(320))) {
            content()
        }
        AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(320))) {
            SplashScreen()
        }
    }
}

/** 主题设置 ViewModel。由 MainActivity 创建，借此下发给设置页，避免出现多份实例。 */
val LocalSettingsViewModel = staticCompositionLocalOf<SettingsViewModel> {
    error("LocalSettingsViewModel 未提供")
}

/**
 * 全局导航骨架（任务1）。
 *
 * - 底部四个一级入口：主页 / 日历 / 发现 / 我的；
 * - 每个入口一个独立 [androidx.navigation.NavHostController]，各自维护回退栈；
 * - 入口内容用 [movableContentOf] 承载：切换入口时组合节点被「移动」而非销毁重建，
 *   因此各入口的页面栈与滚动位置都会保留；
 * - 顶部标题栏随当前入口变化，右侧固定加号；
 * - 入口之间直接切换（不做横向滑动），页面进入 / 返回使用微信式左右推入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreCheckRoot(
    tabBadgeViewModel: TabBadgeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val tabs = APP_TABS

    // 每个入口一个独立的 NavController。建在骨架根部，切走不销毁，
    // 因此各入口的回退栈互不干扰、且天然保留。
    val navControllers = tabs.associateWith { rememberNavController() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 当前选中的一级入口。用 rememberSaveable 保证进程重建后仍停留在原入口。
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    val selectedTab = AppTab.valueOf(selectedTabName)

    val maskedPhone by authViewModel.maskedPhone.collectAsStateWithLifecycle()

    val selectedController = navControllers.getValue(selectedTab)

    // 直接订阅当前入口自己的回退栈，而不是靠旁路 Map 上报。
    // 关键点：`previousBackStackEntry` / `currentDestination` 都是普通属性，
    // 读取它们**不会**订阅状态、也不会因栈变化触发重组；
    // 之前用它们推导 canPop，导致返回键在二级页始终不生效（实测复现）。
    // 这里改用 currentBackStackEntryAsState()，它是可观察的 State，栈一变就重组。
    val selectedEntry by selectedController.currentBackStackEntryAsState()
    val selectedRoute = selectedEntry?.destination?.route ?: selectedTab.rootRoute
    val isRootDestination = selectedRoute == selectedTab.rootRoute
    val canPop = selectedController.previousBackStackEntry != null

    // 登录页是全屏页面：不显示全局标题栏与底部栏。
    val isLoginDestination = selectedRoute == selectedTab.routeOf(TabDestination.Login)

    // 入口内容承载器。
    //
    // 原先用 movableContentOf 承载以保状态，但它在入口切换动画期间会同时出现在
    // 两个位置（淡出中的旧页面 + 淡入中的新页面），而 movableContentOf 要求
    // 同一时刻只有一个实例，会直接抛异常。
    // 现在改用 AnimatedContent + 每个入口自己的 NavController：
    // 回退栈由 NavController 持有、切走不销毁；可保存状态由 rememberSaveable 托管。
    val tabContent: @Composable (AppTab, String?) -> Unit = { tab, phone ->
        TabNavHost(
            tab = tab,
            navController = navControllers.getValue(tab),
            onPlaceholderAction = { label ->
                scope.launch { snackbarHostState.showSnackbar("「$label」将在后续任务实现") }
            },
            onActionMessage = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            maskedPhone = phone,
            momentsBadge = (tabBadgeViewModel.badgeOf(AppTab.DISCOVER) as? TabBadge.Count)?.value ?: 0,
            onMomentsOpened = { tabBadgeViewModel.clear(AppTab.DISCOVER) }
        )
    }

    // 返回键优先回退「当前入口」内部页面；到达入口首页后不消费，交给系统退出应用。
    BackHandler(enabled = canPop) {
        selectedController.popBackStack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 标题栏已移入各入口首页自行绘制。
        // 原因：原先由本骨架统一绘制时，进入二级页会立刻判定「不再是根页面」
        // 而移除标题栏，但根页面在推入动画期间仍然可见，于是内容整体上移，
        // 动画看起来是割裂的。移入页面后，根页面连同标题栏一起被新页面盖住。
        bottomBar = {
            if (!isLoginDestination) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = tab == selectedTab
                        val badge = tabBadgeViewModel.badgeOf(tab)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                val controller = navControllers.getValue(tab)
                                if (selected) {
                                    // 再次点击当前入口：回到该入口首页（其余页面保留在栈下）。
                                    if (controller.previousBackStackEntry != null) {
                                        controller.popBackStack(
                                            route = tab.rootRoute,
                                            inclusive = false
                                        )
                                    }
                                } else {
                                    // 切到别的入口时，同时把该入口回退到首页。
                                    // 否则「在 A 入口进过二级页 → 切到 B → 再切回 A」会直接
                                    // 落在 A 的二级页上，表现为「点某个入口进了二级界面」。
                                    if (controller.previousBackStackEntry != null) {
                                        controller.popBackStack(
                                            route = tab.rootRoute,
                                            inclusive = false
                                        )
                                    }
                                    selectedTabName = tab.name
                                }
                            },
                            icon = {
                                BadgedTabIcon(tab = tab, selected = selected, badge = badge)
                            },
                            label = { Text(tab.title) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            // 入口之间用淡入淡出切换。
            //
            // 用 Crossfade 而不是 AnimatedContent：AnimatedContent 默认会对内容尺寸
            // 做动画，内容高度一变就从上方滑动展开，实测表现为「从主页切到日历时
            // 日历从上方向下滑出」。Crossfade 只做透明度过渡，天然没有这个问题。
            Crossfade(
                targetState = selectedTab,
                animationSpec = tween(220),
                label = "tab-fade"
            ) { tab ->
                tabContent(tab, maskedPhone)
            }
        }
    }
}

/** 入口图标 + 未读角标。 */
@Composable
private fun BadgedTabIcon(
    tab: AppTab,
    selected: Boolean,
    badge: TabBadge?
) {
    val icon = if (selected) tab.iconSelected else tab.iconUnselected
    Box(Modifier.wrapContentSize(unbounded = true)) {
        Icon(imageVector = icon, contentDescription = tab.title)
        when (badge) {
            is TabBadge.Count -> {
                if (badge.value > 0) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-4).dp)
                    ) {
                        Text(if (badge.value > 99) "99+" else badge.value.toString())
                    }
                }
            }

            TabBadge.Dot -> {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-4).dp)
                        .size(9.dp),
                    containerColor = MaterialTheme.colorScheme.error
                )
            }

            null -> Unit
        }
    }
}

/**
 * 一级入口的角标状态：未读数字或红点。
 */
sealed interface TabBadge {
    /** 未读数字（超过 99 显示 99+）。 */
    data class Count(val value: Int) : TabBadge

    /** 纯红点，无数字。 */
    data object Dot : TabBadge
}

/**
 * 一级入口角标的本地模拟状态。
 *
 * 任务1 只提供骨架所需的读写能力与一组演示初值；
 * 后续任务把真实未读来源接到 [TabBadgeViewModel.setCount] / [TabBadgeViewModel.setDot] 即可。
 */
class TabBadgeViewModel : androidx.lifecycle.ViewModel() {

    private val badges = mutableStateMapOf<AppTab, TabBadge>()

    init {
        // 演示初值：发现=未读数字，我的=纯红点。
        badges[AppTab.DISCOVER] = TabBadge.Count(3)
        badges[AppTab.MINE] = TabBadge.Dot
    }

    /** 读取角标。在组合中调用，角标变化会触发重组。 */
    @Composable
    fun badgeOf(tab: AppTab): TabBadge? = badges[tab]

    /** 设置未读数字；传 0 表示清除角标。 */
    fun setCount(tab: AppTab, value: Int) {
        if (value <= 0) badges.remove(tab) else badges[tab] = TabBadge.Count(value)
    }

    /** 增减未读数，用于本地模拟「收到一条新消息」。 */
    fun increment(tab: AppTab, delta: Int = 1) {
        val current = (badges[tab] as? TabBadge.Count)?.value ?: 0
        setCount(tab, current + delta)
    }

    /** 设置为纯红点（如「我的」有更新提示）。 */
    fun setDot(tab: AppTab) {
        badges[tab] = TabBadge.Dot
    }

    /** 清除角标（如进入页面后标记已读）。 */
    fun clear(tab: AppTab) {
        badges.remove(tab)
    }
}
