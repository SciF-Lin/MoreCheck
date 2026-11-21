package com.morecheck.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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

/** 启动页停留时长（含自身淡入时间）。`delay` 需要 Long。 */
private const val SPLASH_DURATION_MS = 900L

/**
 * 启动页淡入 / 淡出时长。
 *
 * 注意保持 **Int**：`tween(durationMillis)` 收的是 Int，而 `delay` 要 Long。
 * 早先这里写成 `320L`，直接传给 `tween` 会编译不过（`delay` 用它可以，两者签名不同）。
 */
private const val SPLASH_FADE_MS = 320

/**
 * 点「同意并继续」之后，开屏**再多停留**这么久才退场。
 *
 * 需求原文是「0.75±0.25 秒」，取中值 750ms。
 *
 * 这段停留还有一个附带好处：此刻已经同意，主界面**可以开始组合**了，
 * 于是它是在开屏后面**预热完**的，开屏淡出时不会出现空白帧。
 */
private const val SPLASH_AGREE_HOLD_MS = 750L

/**
 * 启动页门闸（任务2）。
 *
 * 先显示启动页（中间 `Hello World`，跟随深浅色模式），再进入主界面；
 * 交接处用淡入淡出，避免两屏硬切。启动页只在**应用启动时**出现一次。
 *
 * ## 退场条件（三个都满足才退）
 *
 * 1. 最短展示时长 [SPLASH_DURATION_MS] 已到；
 * 2. [consentPending] 已变为 false，即**同意流程已经结束**；
 * 3. [holdMs] 这段额外停留已走完。
 *
 * ## 为什么开屏要一直留到同意之后（需求 bug9）
 *
 * 需求：「第一次使用软件弹出用户协议弹窗时，背景请一直保持为 Hello world；
 * 点击同意后，再显示 Hello World（0.75±0.25）秒」。
 *
 * 同意的底部弹窗是一个独立 `Dialog` 窗口，**本来就浮在整个 Activity 内容之上**，
 * 所以只要开屏在同意期间**不退场**，用户看到的就是「Hello World + 浮在上面的同意窗」。
 * 早先的做法是开屏先把内容淡出来、再由门闸补一层空白 `Surface`，结果同意窗背后是
 * 一块纯色底而不是 Hello World。
 *
 * @param consentPending 同意流程是否**还没结束**。true = 开屏必须继续显示，充当同意弹窗的背景。
 *   已同意过的老用户读盘一结束它就是 false，不会多留。
 * @param justAgreed 用户**本次会话里刚点了**「同意并继续」。为 true 时开屏再停留
 *   [SPLASH_AGREE_HOLD_MS]；进来就已经同意过的老用户传 false ——
 *   否则每次冷启动都会无谓地多等 0.75 秒。
 * @param onMinElapsed 最短展示时长结束时回调一次。调用方据此才允许弹同意窗
 *   （先放完开屏、再弹窗，弹窗不会盖住居中的 `Hello World`）。
 * @param onFinished 开屏**淡出动画走完之后**回调一次。
 *   登录引导弹窗要挂在这里而不是「点了同意」的瞬间：否则它会立刻盖在
 *   需求要求「同意后再显示 0.75 秒」的 `Hello World` 上，那 0.75 秒等于白留。
 * @param minDurationMs 开屏的**最短展示时长**（条件 1）。
 *
 *   ## 为什么需要这个参数（需求：性能测试里的开屏时长滑块）
 *
 *   原先这里是**写死**的 [SPLASH_DURATION_MS]。但那个 900ms 是**开屏内部的下限**，
 *   外部（`MainActivity`）无论怎么等都无法把它**缩短**——`onMinElapsed` 本身就是
 *   `delay(900ms)` 之后才触发的。因此「0 秒」这一档在写死的情况下根本做不到，
 *   而需求要的是「0~10 秒间可调、且能一键恢复默认」。
 *
 *   加了这个参数后，调用方（`MainActivity`）把用户在「开发者选项 → 性能测试」里设的值传进来即可，
 *   **默认值仍然是 [SPLASH_DURATION_MS]**，所以不传就是改造前的行为，一行都没变。
 *
 *   ⚠️ 它只替换**这一段**（最短展示时长），**不动** [SPLASH_AGREE_HOLD_MS]：
 *   后者是「用户点了同意之后再多留 0.75 秒」，是上一轮明确要求的行为，
 *   与本参数是**两次独立的等待**，各自计一次，不能互相顶替。
 */
@Composable
fun SplashGate(
    consentPending: Boolean,
    justAgreed: Boolean,
    minDurationMs: Long = SPLASH_DURATION_MS,
    onMinElapsed: () -> Unit = {},
    onFinished: () -> Unit = {},
    content: @Composable () -> Unit
) {
    // 「0.75 秒」这条策略只留在开屏内部：调用方只负责说明「用户是不是刚点了同意」这个事实。
    val holdMs = if (justAgreed) SPLASH_AGREE_HOLD_MS else 0L

    var showSplash by rememberSaveable { mutableStateOf(true) }
    var minElapsed by remember { mutableStateOf(false) }
    var holdElapsed by remember { mutableStateOf(false) }

    val view = LocalView.current
    val window = (view.context as? Activity)?.window

    // 条件 1：最短展示时长。
    //
    // key 用 `minDurationMs` 而不是 `Unit`：该值从 DataStore 读来，
    // 首帧拿到的多半是初始默认值（900）；用 `Unit` 的话计时会在"默认值"上先起跑，
    // 真实值到达后又不会重启计时——用户设了 0 秒却仍要等满 900ms。
    // 用值做 key 后，真实值一到就取消旧计时、按新值重新计。
    //
    // ⚠️ 已知的极小边界：若用户把时长设得**比默认值更长**（比如 5 秒），
    // 且 DataStore 首次读取慢到超过 900ms，那么旧计时会先到点、`minElapsed` 提前置真。
    // 但 `minElapsed` 一旦为真就不会回退，于是开屏可能比设定值早退。
    // 不加额外机制去堵它：DataStore 是**本地**读，实测在毫秒级，
    // 触发条件要求磁盘读超过 900ms（设备已严重卡顿）；而为此引入"设置是否加载完"的标志位，
    // 会让这个纯展示门闸多背一个状态。这里如实记下取舍。
    LaunchedEffect(minDurationMs) {
        delay(minDurationMs)
        minElapsed = true
        onMinElapsed()
    }

    // 条件 3：同意流程一结束就开始算额外停留。
    //
    // 这里等的是 `consentPending` 变 false —— 也就是**用户真的点了同意**（或本来就已经同意）那一刻，
    // 而不是开屏自己的计时；两者是不同的时间点，绑错了就会变成「同意窗还没关开屏就走了」。
    LaunchedEffect(consentPending, holdMs) {
        if (!consentPending) {
            if (holdMs > 0L) delay(holdMs)
            holdElapsed = true
        }
    }

    // 条件 1 + 2 + 3 齐了才退场。
    LaunchedEffect(minElapsed, consentPending, holdElapsed) {
        if (minElapsed && !consentPending && holdElapsed) {
            showSplash = false
            // 等淡出真的走完再通知。
            // `delay` 收 Long，而常量是 Int（`tween` 需要 Int），这里显式转一下。
            delay(SPLASH_FADE_MS.toLong())
            onFinished()
        }
    }

    // 启动页现在跟随深浅色模式，状态栏 / 导航栏图标统一按「当前页面是深还是浅」来定：
    // 浅色页面用深色图标，深色页面用浅色图标。
    //
    // 早期这里写死成 `showSplash`，等于「启动页用深色图标、主界面用浅色图标」——
    // 深色模式下启动页会变成「深底 + 深图标」，浅色模式下主界面会变成「浅底 + 浅图标」。
    // 去掉 `showSplash` 后，启动页与主界面共用同一条正确规则，并随主题切换重新生效。
    val darkThemeNow = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(darkThemeNow, window) {
        if (window == null) return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkThemeNow
        controller.isAppearanceLightNavigationBars = !darkThemeNow
    }

    // 整屏底色：垫在开屏与主界面**下面**，保证任何时刻都不透出窗口底色。
    //
    // 这是修「深色模式下开屏转主界面时短暂白屏」的关键（需求 bug1）：
    // 原来主界面 `fadeIn` 与开屏 `fadeOut` 同时进行，中间帧两层都半透明，
    // 合成不透明度只有 0.75，于是从缝隙里透出**窗口的白色背景**，深色下就是明显的一层白。
    // 铺一层 `background` 色的实底后，透出来的永远是页面同色，不再有白屏。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 内容**始终**参与组合，不随开屏增删。这一点有两个原因，都不能省：
        //
        // ① 同意弹窗是一个独立 `Dialog` 窗口，需要浮在开屏**之上**。所以门闸必须在
        //    开屏还显示着的时候就已经挂载好了（需求 bug9：弹窗期间背景一直是 Hello World）。
        //    若这里写成 `AnimatedVisibility(visible = !showSplash) { content() }`，
        //    开屏期间 `content` 压根不在组合里，同意窗就永远等不到。
        // ② 内容始终不透明，开屏淡出时把它直接露出来即可 —— 于是**不存在**
        //    「两层同时半透明」的中间帧，从根上避免了透出窗口底色（需求 bug1 的白屏）。
        //    （这正是当年那段 `fadeIn` 造成的 0.75 合成不透明度问题的干净解法。）
        content()

        AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(SPLASH_FADE_MS))) {
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
 * - 入口内容直接切换（**不做过渡动画**，见下方注释），各入口的页面栈与滚动位置都会保留；
 * - 顶部标题栏由**各页面自行绘制**（含状态栏内边距），本骨架不消费顶部内边距；
 * - **底部导航栏只在一级入口页显示**：进入二级页即隐藏，仿微信；
 * - 入口之间直接切换（不做横向滑动），页面进入 / 返回使用微信式左右推入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreCheckRoot(
    tabBadgeViewModel: TabBadgeViewModel = viewModel(factory = AppViewModelProvider.Factory),
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory),
    /**
     * 刚同意协议后，是否要弹一次可关闭的「引导登录」窗。
     * 由 `MainActivity` 的门闸在同意时置位，弹窗被关掉后回调清位。
     */
    promptLogin: Boolean = false,
    onLoginPromptDismiss: () -> Unit = {},
    /**
     * 桌面快捷方式要直达的实验室子页（v1.1.68 需求 1）。
     *
     * 传的是「`lab_page` 里那个稳定字符串」，本骨架用
     * [TabDestination.fromRouteOrNull] 解析成目的地后切到「发现」入口并推入对应页面。
     *
     * ## 为什么由这里消费、而不是 MainActivity 自己导航
     *
     * 路由与回退栈都由本骨架持有（每个入口一个 `NavController`），`MainActivity` 拿不到；
     * 它能做的只是把「用户想开哪一页」这个**事实**传进来。职责边界与 `promptLogin` 完全一致。
     *
     * 为 null / 解析不出来时什么都不做（安静落到默认首页）——历史快捷方式对应的页面
     * 可能在新版本被改名，那种情况不该崩在启动路径上，见
     * [TabDestination.fromRouteOrNull] 的说明。
     */
    labShortcutTarget: String? = null,
    /** 消费掉快捷方式目标后回调，避免重建/重组时反复导航（见下方 LaunchedEffect）。 */
    onLabShortcutConsumed: () -> Unit = {},
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
    val isLoggedIn by authViewModel.isLoggedIn.collectAsStateWithLifecycle()

    // 「朋友圈红点提示」开关（实验室 → 开发者选项 → 图标测试）。
    //
    // 来源与文件下方那个 `loginPromptEnabled` **完全同一处**：`LocalSettingsViewModel`
    // （即 `MainActivity` 建好的那一份 `SettingsViewModel`），因此用户在开发者选项里拨动它，
    // 回到主界面这里立刻生效——不新建任何 ViewModel 实例。
    //
    // ⚠️ 它只门控「底部导航栏『发现』角标要不要画」（见下方 NavigationBar 里对 `badge` 的处理），
    // **不门控**「进入朋友圈后清除未读」（`onMomentsOpened` → `TabBadgeViewModel.clear`）：
    // 后者改的是"未读状态"，与"显不显示"是两件独立的事，
    // 详见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc。
    //
    // ⚠️ 这里**刻意不**再「与」上开发者选项总开关（尽管本页别的子项都是 `总开关 && 子开关`）：
    // `DEV_MODE_ENABLED` 默认是**关**的，若把它算进来，等于所有没开过开发者选项的用户
    // 升级后底部角标全部消失——那是用一个默认关闭的开关改掉了所有人的既有观感。
    // 需求要的也只是"这个开关为 false 时不显示"，因此判断条件只有它自己。
    val momentsBadgeEnabled by LocalSettingsViewModel.current.momentsBadgeEnabled
        .collectAsStateWithLifecycle()

    // ── 桌面快捷方式直达（v1.1.68 需求 1）──────────────────────────────────
    //
    // 必须在**这一帧组合完成之后**再导航：各入口的 `NavHost` 是 `key(selectedTab)` 下
    // 只组合当前选中入口的，刚切到「发现」那一帧它的 navigation graph 还没 set，
    // 立刻 navigate 会抛 `Cannot navigate to ... Navigation graph has not been set`
    // （这个坑修「去登录」时踩过一次，见下方登录引导那段注释）。
    //
    // `LaunchedEffect(selectedTabName, labShortcutTarget)`：先切入口、再导航；
    // 走完立刻 `onLabShortcutConsumed()` 清掉目标，避免重组时反复推入同一页。
    LaunchedEffect(selectedTabName, labShortcutTarget) {
        val target = labShortcutTarget ?: return@LaunchedEffect
        val destination = TabDestination.fromRouteOrNull(target) ?: run {
            // 解析不出来（历史快捷方式指向已改名的页面）：安静消费掉，不导航。
            onLabShortcutConsumed()
            return@LaunchedEffect
        }
        if (selectedTabName != AppTab.DISCOVER.name) {
            selectedTabName = AppTab.DISCOVER.name
            return@LaunchedEffect
        }
        navControllers.getValue(AppTab.DISCOVER)
            .navigate(AppTab.DISCOVER.routeOf(destination))
        onLabShortcutConsumed()
    }

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

    // 底部导航栏当前是否显示：只有一级入口页显示（仿微信）。
    val tabBarVisible = !isLoginDestination && isRootDestination

    // ── Snackbar 的统一高度 ──
    //
    // 底部栏收起后，Scaffold 的 bottomBar 高度归零，Snackbar 会跟着掉到屏幕最下沿
    // （实测：「关于我们」页的「暂无新版本」贴着屏幕底边，而「我的」页停在导航栏之上，
    // 同一句提示在两个页面高度不同）。
    // 这里显式给 SnackbarHost 补上「底部栏被隐藏时缺的那一段」，
    // 让它在所有页面都停在同一个高度。
    val density = LocalDensity.current
    val systemNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底部栏的实测高度（含系统导航栏内边距）。只在它可见时采样——
    // 收起动画期间尺寸是连续变小的中间值，采进去会让 Snackbar 一路往下飘。
    var measuredTabBarHeight by remember { mutableStateOf(0.dp) }
    // 兜底值：Material3 的 NavigationBar 固定 80dp，再加上系统导航栏内边距。
    val tabBarHeight =
        if (measuredTabBarHeight > 0.dp) measuredTabBarHeight else 80.dp + systemNavInset
    // 底栏现在画在**内容层内部**（见下方 content lambda），Scaffold 自己不知道它的存在，
    // 因此 Snackbar 的底部留白要**恒定**补上栏高，各页面的提示才会停在同一高度。
    val snackbarBottomPadding = tabBarHeight

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
            onActionMessage = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
            maskedPhone = phone,
            // ⚠️ 这里的角标必须**同样**受「朋友圈红点提示」门控（本轮补齐）。
            //
            // 上一版只门控了底部导航栏那一处（`BadgedTabIcon`），漏了发现页列表里
            // 「朋友圈」这一行的数字角标——它们是**同一个未读数的两处呈现**，
            // 只关一处会表现为"底栏没红点了，列表里还挂着 3"，与需求
            // 「关闭后不再显示未读红点与数字」不符。
            //
            // 因此门控统一放在**读数**这一步：开关关闭时传给发现页的 `momentsBadge`
            // 直接为 0（`Badge` 组件对 0 不绘制），两处自然一致，
            // 不需要在渲染侧各判一次（各判一次迟早会漏第二处，就是这次的情形）。
            //
            // 仍然**不动** `onMomentsOpened`：它清的是"未读状态"，
            // 与"显不显示"是两件事（详见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc）。
            momentsBadge =
                if (momentsBadgeEnabled) {
                    (tabBadgeViewModel.badgeOf(AppTab.DISCOVER) as? TabBadge.Count)?.value ?: 0
                } else {
                    0
                },
            onMomentsOpened = { tabBadgeViewModel.clear(AppTab.DISCOVER) },
            // 一级入口首页自己给底栏让位；二级页不留，从而整页盖住底栏。
            rootBottomPadding = tabBarHeight
        )
    }

    // 返回键优先回退「当前入口」内部页面；到达入口首页后不消费，交给系统退出应用。
    BackHandler(enabled = canPop) {
        selectedController.popBackStack()
    }

    Scaffold(
        snackbarHost = {
            // 底部留白让提示在所有页面停在同一高度，见上方 snackbarBottomPadding。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = snackbarBottomPadding)
            )
        },
        // 关键：外层骨架**不再消费任何窗口内边距**。
        //
        // 本骨架是 edge-to-edge 的，默认会把状态栏高度算进 innerPadding；
        // 而每个页面又各自绘制 TopAppBar，TopAppBar 自带状态栏内边距，
        // 两者叠加就表现为「标题栏上方多出一大块留白」。
        // 归零后：顶部内边距完全交给各页面自己的 TopAppBar（按状态栏实际高度自适应），
        // 底部内边距仍由本骨架的底部导航栏高度给出。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            // ── 底部导航栏：画在内容层里、**页面之下** ──
            //
            // 三种做法都试过，这里说明为什么最终是这样：
            //
            // ① `Scaffold.bottomBar` + AnimatedVisibility（最早）：bottomBar 是独立于内容的一层，
            //    进二级页时它只能自己「向下滑走」，而二级页要 280ms 才滑到位，
            //    于是底栏先塌、页面后盖，观感是「底栏先消失」。
            // ② 放进内容层 + alpha 淡出 + 页面底部内边距跟着 alpha 收放（上一版）：
            //    仍然不对——页面的底部内边距**是内边距，不是被遮住**，那一段始终露在外面，
            //    底栏就等于在一个正在收缩的缺口里自己淡出，还是「自己消失」而不是「被盖住」。
            // ③ 现在这版：**底栏不做任何动画**（不淡出、不位移、不缩放），
            //    页面容器**满高**、不留任何内边距，于是推入的二级页从头到尾都完整盖在底栏上，
            //    底栏是被新页面**物理遮住**的，这才是「跟主界面一起隐去」。
            //
            // 一级页的底部让位改由页面自己承担（`TabNavHost` 的 `rootBottomPadding`），
            // 这样一级页的布局**常量不变**，过渡期间不会因为让位变化而跳动。
            NavigationBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged {
                        measuredTabBarHeight = with(density) { it.height.toDp() }
                    },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                tabs.forEach { tab ->
                    val selected = tab == selectedTab
                    // 角标的**原始值**（未读状态本身，永远可读）。
                    val rawBadge = tabBadgeViewModel.badgeOf(tab)
                    // 「朋友圈红点提示」开关（实验室 → 开发者选项 → 图标测试）：
                    // 关掉后「发现」入口**不画任何角标**（数字与红点都不画）。
                    //
                    // ⚠️ 这里挡的只是**显示**，不是「未读状态」：
                    // 「进入朋友圈后清除未读」那条链路（`onMomentsOpened` →
                    // `TabBadgeViewModel.clear(DISCOVER)`，见上方 `tabContent`）**保持不动**——
                    // 它是"把未读标记为已读"，与本开关管的"要不要画出来"是两件独立的事。
                    // 若把清除逻辑也一起门控，等于「关掉提示」顺手改掉了未读语义，是行为越界。
                    //
                    // 只作用于「发现」入口：「我的」页那个纯红点（`TabBadge.Dot`）不在本次范围内。
                    val badge = if (!momentsBadgeEnabled && tab == AppTab.DISCOVER) null else rawBadge
                    NavigationBarItem(
                        selected = selected,
                        // 底栏始终参与组合（不写 `if`）：按可见性增删会让底层页面在返回时
                        // 找不到它的实测高度，底部内容会跳一下。
                        //
                        // 但它被二级页盖住时仍然参与命中测试，所以这里必须禁用，
                        // 否则二级页底部会有一条看不见却可点的栏，用户一点就被静默切走。
                        enabled = tabBarVisible,
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
                            // 仿微信：选中项只用绿色区分，**不做药丸形高亮底色**。
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            // ── 页面：画在底栏之上，**满高**，二级页因此从第一帧起就完整盖住底栏 ──
            //
            // 这里刻意**不加**任何 `padding(bottom = ...)`：
            // 用内边距给底栏让位，那一块始终是"露出来"的，底栏就会在缺口里自己消失；
            // 满高覆盖才能让底栏是被"盖住"的。
            //
            // 一级页的底部让位交给页面自己（见 `TabNavHost` 的 `contentBottomPadding`），
            // 且它的取值在一级页内是常量，所以过渡期间页面布局不会变化、不会跳。
            Box(Modifier.fillMaxSize()) {
                // 入口之间**直接切换**，不做任何过渡动画（仿微信）。
                //
                // 曾经这里用 Crossfade(220ms) 做淡入淡出，实测会「双重曝光」：
                // 过渡期间新旧两页同时以半透明叠在一起，标题栏已经换成新入口，
                // 页面主体却还残留上一个入口的内容（打卡按钮、任务列表压在日历网格上），
                // 观感就是闪烁 + 抖动。
                //
                // 这里改为直接切换：只组合当前入口，不存在两页同时绘制的情况。
                // 各入口的回退栈由各自的 NavController 持有、可保存状态由 rememberSaveable
                // 托管，因此切换入口后页面栈与滚动位置依然保留，不受影响。
                //
                // `key(selectedTab)` 不能省：它让每个入口占用**各自的组合槽位**。
                // 否则四个入口会复用同一个槽位，NavHost 在槽位内按参数复用内部
                // remember（图、导航器），切换入口时可能残留上一个入口的导航状态。
                // 原先 Crossfade 的 contentKey 正是起这个作用，去掉动画后必须显式补回。
                key(selectedTab) {
                    tabContent(selectedTab, maskedPhone)
                }
            }
        }
    }

    // 同意协议后的一次性登录引导：未登录时才弹，弹过（或点了「去登录」）即关。
    //
    // 另外受「实验室 → 登录提示功能」那个开关门控（需求：把这个提示加一个开关）。
    // 订阅的是 `LocalSettingsViewModel`（与 `MainActivity` 同一份实例），
    // 所以用户在实验室里关掉它，回到主界面这里立刻就不再弹，不用重启。
    //
    // ⚠️ 开关只影响**这一个**引导弹窗，不影响上面那层强制协议门闸：
    // 后者在 `PrivacyConsentGate` 里，不同意直接退出应用，任何开关都不该绕过它。
    val loginPromptEnabled by LocalSettingsViewModel.current.loginPromptEnabled
        .collectAsStateWithLifecycle()

    // 「去登录」要**先切到「我的」入口、再推入登录页**，但这两步不能写在同一个点击回调里：
    // 各入口的 `NavHost` 是 `key(selectedTab)` 之下**只组合当前选中入口**的，
    // 点「我的」那一帧它的 navigation graph 还没被 set，
    // 立刻 `navigate("MINE.login")` 会抛
    // `IllegalArgumentException: Cannot navigate to MINE.login. Navigation graph has not been set`，
    // **整个应用闪退**（模拟器实测抓到的，v1.1.65 冒烟测试）。
    //
    // 所以点击时只切入口 + 置起一个待办标记，真正的导航交给下面这个 LaunchedEffect：
    // 它在**这一帧组合完成之后**才跑，此时「我的」的 NavHost 已组合、graph 已就绪。
    var pendingLoginNavigation by remember { mutableStateOf(false) }

    if (promptLogin && !isLoggedIn && loginPromptEnabled) {
        LoginPromptDialog(
            onLogin = {
                onLoginPromptDismiss()
                // 切到「我的」入口；导航留给 LaunchedEffect。
                selectedTabName = AppTab.MINE.name
                pendingLoginNavigation = true
            },
            onDismiss = onLoginPromptDismiss
        )
    }

    LaunchedEffect(selectedTabName, pendingLoginNavigation) {
        if (pendingLoginNavigation && selectedTabName == AppTab.MINE.name) {
            // 先清标记再导航：否则导航引起的重组会再次进入这个分支（虽然 navigate 幂等性尚可，
            // 但重复推入登录页会叠出两层，返回时要按两次）。
            pendingLoginNavigation = false
            navControllers.getValue(AppTab.MINE)
                .navigate(AppTab.MINE.routeOf(TabDestination.Login))
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
