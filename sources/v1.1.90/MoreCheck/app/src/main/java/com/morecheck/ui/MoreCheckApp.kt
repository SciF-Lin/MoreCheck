package com.morecheck.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.morecheck.di.appContainer
import com.morecheck.ui.auth.AuthViewModel
import com.morecheck.ui.nav.APP_TABS
import com.morecheck.ui.nav.AppTab
import com.morecheck.ui.nav.SplashScreen
import com.morecheck.ui.nav.TabDestination
import com.morecheck.ui.nav.TabDragState
import com.morecheck.ui.nav.TabNavHost
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.cloud.FirstLoginStrategyDialog
import kotlinx.coroutines.CancellationException
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
 * 底部入口切换动画时长（2026-10-22 重做跟手切换时设定）。
 *
 * 取 **260ms**：与二级页推入 `NAV_DURATION_MS`(280) 同量级——一级入口之间的切换
 * 是"整屏平移"，距离远、比二级页更依赖动画来交代空间关系，不该比推入更快；
 * 再长（>300ms）连点两次会觉得粘手。跟手拖动松手后的收尾也是这一段时长
 * （起点取当前位移，所以实际观感更短）。
 */
private const val TAB_SWITCH_MS = 260

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
    /**
     * 本次启动是否来自**桌面 App 图标**（而非快捷方式），需要回到入口首页
     * （本轮需求 16/18）。
     *
     * ## 这个参数解决什么问题
     *
     * `launchMode="singleTask"` 保证「后台运行时再点 App 图标」不会新建实例，
     * 但它带来一个副作用：**已存在的 task 被原样搬到前台**，用户会停在离开时的页面
     * （比如贪吃蛇）。而点 App 图标的直觉是「从首页重新开始」——
     * 微信、QQ 也都是这个行为（回到 task 时重置到首页）。
     *
     * 同时它还是需求 18「主页覆盖在贪吃蛇上」那类嵌套的**兜底**：
     * 只要这一次是 App 图标启动，就把各入口的回退栈一次性清到首页，
     * 上一轮残留的多层页面不再有机会与新页面叠在一起。
     *
     * ## 为什么用一次性标志而不是持久状态
     *
     * 它是**一次事件**（"用户刚刚点了一下图标"），不是界面状态。
     * 写成 `Boolean` + 消费后回调清位（与 [labShortcutTarget] 同一套路），
     * 重建 / 重组都不会重复执行；进程被杀后也不会残留一个"该重置"的假信号。
     *
     * 默认 `false` = 不重置（例如由悬浮窗「跳转」拉起的会话，
     * 那正是要**保持**当前页面栈的场景）。
     */
    appIconLaunched: Boolean = false,
    /** 消费掉「回到首页」请求后回调（避免重组时反复重置，见 [appIconLaunched]）。 */
    onAppIconLaunchConsumed: () -> Unit = {},
    /**
     * 结束整个应用（回桌面）的回调（需求 14）。
     *
     * 透传给各 [TabNavHost]：桌面快捷方式直达贪吃蛇的会话里，用户在贪吃蛇页按返回
     * 就是"玩一局就走"，此时按需求要**直接回桌面**而不是回到实验室页。
     * 由 `MainActivity` 传 `finish()` 进来——本骨架与导航层都不持有 Activity。
     */
    onExitApplication: () -> Unit = {},
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

    // ── 横向滑动切换入口（v1.1.72 需求）──────────────────────────────────────
    //
    // 需求原文：「新增横向滑动功能，导航栏四大功能可以左右滑动切换」。
    //
    // ## 为什么用「手势检测」而不是 `HorizontalPager`（本轮实测的返工教训）
    //
    // 第一版用了 HorizontalPager 包住四个入口，模拟器实测出一个难以定位的回归：
    // 底栏的 NavigationBarItem 会**随机整体消失**（tab 项不在无障碍树里、点不动），
    // 且与 pager 相邻页的组合时机相关、时隐时现。根因是本应用的骨架与
    // Pager 的组合模型**天然冲突**：
    // - 既有架构刻意做到"**同一时刻只组合当前入口**"（`key(selectedTab)`，
    //   各入口有独立 NavController，切走不销毁）；
    // - 而 Pager 为了滑动必须**同时组合相邻页**，等于把 2~3 个完整的
    //   NavHost + Scaffold 挂到同一棵树上，底栏的测量（`onSizeChanged`
    //   采 `measuredTabBarHeight`）与可见性判定都被这个"多页并存"的状态干扰。
    //
    // 所以改成**手势检测 + 直接切状态**：页面结构回到"单实例组合"的既有形态，
    // 滑动只在**水平快速滑动**时切换入口——没有页面跟随手指的位移，
    // 换来的是零结构风险与完全一致的双向同步（状态仍然只有 `selectedTabName` 一个）。
    //
    // ## ⚠️ 手势挂载点必须避开底栏（真机实测踩过：v1.1.72 第一版点不动底栏）
    //
    // 最初这个手势挂在**满屏的内容层 Box** 上——它在 z 轴上盖住了底栏。
    // 模拟器上点按正常，**真机上四个 tab 全部点不动**：满屏的
    // `awaitFirstDown(requireUnconsumed = false)` 观察器与 NavigationBarItem
    // 自己的 clickable 在指针事件派发上存在时序竞态，部分按下被观察路径
    // 吞掉，底栏收不到完整的按下-抬起。
    //
    // 修法：**手势只挂在一级页内容区**（`TabRootScreen` 的根 Box），
    // 那里自带 `padding(bottom = rootBottomPadding)`，物理上**不覆盖底栏**，
    // 与底栏的点击区域零重叠——竞态从根上消除。切页逻辑抽成
    // [tabSwitchTo] 一个入口，底栏点击与横滑共用同一条路径。
    //
    // ## 手势判定
    //
    // - 只认**横向**位移明显大于纵向的滑动（`abs(dx) > abs(dy) * 2` 且 `dx` 超过
    //   最小距离，即 `TabNavHost` 里的 `TAB_SWIPE_MIN_DISTANCE`），
    //   避免把纵向滚动列表误判成切页；
    //
    //   ⚠️ 那个常量在 `ui/nav/TabNavHost.kt`，**不在本文件**——
    //   v1.1.72 修「真机底栏点不动」时手势整体下沉到了一级页内容区
    //   （`TabRootScreen`），常量跟着搬走了。这里不能写成 KDoc 链接 `[TAB_SWIPE_MIN_DISTANCE]`：
    //   本文件已没有该符号，链接指向一个不存在的目标，编译不报错但注释在骗人。
    // - 用 `detectHorizontalDragGestures` 不合适：它会把**所有**横滑都拦截，
    //   与日历页的翻月 pager、发现页列表内的横滑元素抢手势。这里用
    //   `pointerInput + awaitEachGesture` 手工判定，且**只在手势结束时**
    //   判定一次——不消费中间事件，内层可滚动组件想吃就让它先吃
    //   （它们消费了事件，`waitForUpOrCancellation` 返回 null，天然让位）。
    //
    // ⚠️ 已知取舍：日历页的月历翻月 pager 与本手势同向，实测**内层翻月优先**
    // （它消费了横向拖动）。想在日历页切入口仍用底栏点击——这是嵌套滚动的
    // 标准让位行为，与微信"首页横滑切 tab、日历内横滑翻月"一致。
    //
    // @param target 切换目标入口（由手势判定算出，或未来其它入口手势复用）。
    //   切换时会同时把目标入口回退到首页，与底栏点击同一条路径
    //   （理由见 NavigationBar onClick 处的长注释）。
    // 「左右滑动动画」开关（需求 11a）的**生效值**。
    //
    // 来源与「朋友圈红点提示」同一处（`LocalSettingsViewModel` / DataStore），
    // 但「与总开关的合成」已在 `DeveloperOptionsRepository.tabSlideAnimEnabled`
    // 里做完（总开关关 → 恒开 = 改造前行为），这里只订阅结果、不重复判。
    //
    // 为什么走仓库而不是直接读 DataStore：判断写两处迟早漂移——
    // 这是 `DeveloperOptionsRepository` 存在的意义（其 KDoc 有完整记录）。
    // ⚠️ `LocalContext.current` 必须在 `remember` **外面**读：它是
    // `CompositionLocal`，只能在组合期间取值；写进 `remember { ... }` 的
    // lambda 里会被判成"非组合上下文调用 @Composable"。
    val localContext = LocalContext.current
    val developerOptionsRepository = remember(localContext) {
        localContext.appContainer.developerOptionsRepository
    }
    val tabSlideAnimEnabled by developerOptionsRepository.tabSlideAnimEnabled
        .collectAsStateWithLifecycle(initialValue = true)

    // ── 底部入口切换：跟手位移 + 双页并存的连贯过渡（2026-10-22 重做）────────
    //
    // ## 需求
    //
    // 「底部标签页切换要跟随手指移动，界面切换连贯（参考安卓微信的动画）」。
    //
    // ## 为什么必须"两页并存"
    //
    // 上一版只平移**当前页**（旧页一旦拖出去身后就是空白），所以只能做到
    // "整页跟着手指走、松手硬切"，不是微信那种"新页从手指后面顺势顶上来"。
    // 要做到连贯，拖动时手指两侧必须**同时**有内容：当前页在动、目标页在跟。
    //
    // ## 与 v1.1.72 那次 `HorizontalPager` 返工的关系
    //
    // 当时的结论是「不能用 Pager」，根因是 Pager 会**常驻**组合 3 页
    // （当前 + 两侧相邻），三个完整 `NavHost` + `Scaffold` 同时存在把底栏的
    // 测量/可见性搞坏（实测：底栏 item 随机消失、点不动）。
    //
    // 现在的做法把"并存"压到**最小且有时限**：只有 `neighborTab != null` 期间
    // （手指拖动中 / 一次切换动画的 200~260ms 内）才组合第二页，
    // 且**只组合一页**（拖到最左/最右时根本不组合）；松手回弹或提交完成即移除。
    // 静止状态下树上仍然只有当前入口一个 `NavHost`——Pager 那个"三页常驻"
    // 的前提不成立，底栏的测量环境与改造前一致。
    //
    // ## 坐标系（三个量的关系，改之前先看这段）
    //
    // - `dragOffsetPx` = **当前页**的 translationX；
    // - `neighborTab` 目标的 translationX = `dragOffsetPx + dir * 屏宽`
    //   （`dir` = +1 表示目标在当前页右侧）；
    // - 因此拖动结束把 `dragOffsetPx` 动画到 `-dir * 屏宽` 时，目标页正好落在 0，
    //   此刻提交（改 `selectedTabName`、清 `neighborTab`、位移归零）**没有跳变**：
    //   提交前后屏幕上的内容都是同一个页面、同一个位置。
    //
    // `TabDragState.applied` 同步写的是"当前页被施加的位移"，供手势层做
    // 局部坐标系漂移补偿（见该 object 的 KDoc，慢拖不切页的根因就在那里）。
    val gridWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.coerceAtLeast(1f)

    // ⚠️ 2026-10-22 修复（长按横滑闪屏 / 滑不动）：位移改成**单一同步来源**。
    //
    // 原实现是 `Animatable`：拖动时每帧 `scope.launch { snapTo(v) }`，
    // 而 `TabDragState.applied` 是**当场**同步写的。两者在时间上错开一帧——
    // 手势层用 `applied` 做坐标系漂移补偿、页面层却还停在上一帧的 `snapTo` 结果，
    // 于是每一帧都多补偿/少补偿一点：慢拖表现为"页面来回抖、位移被吃掉"（滑不动），
    // 快速长按拖动时抖动被放大成整屏闪烁。
    //
    // 现在只保留**一个** `Float` 状态：手势写它（同步）、页面 `graphicsLayer` 读它、
    // `TabDragState.applied` 与它同时写。动画不再是 `Animatable`，而是用
    // `animate()` 把每一帧的值写回同一个状态——三处永远同帧同值，补偿才真正成立。
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    /** 拖动 / 切换动画期间被组合的**相邻入口**；null = 树上只有当前入口。 */
    var neighborTab by remember { mutableStateOf<AppTab?>(null) }
    /** 一次切换动画是否在跑（防连点导致两个协程同时改 `selectedTabName`）。 */
    var switchingTabs by remember { mutableStateOf(false) }
    /** 是否有"回弹 / 切页"动画正在跑（防重复启动多段动画互相打断）。 */
    var animatingOffset by remember { mutableStateOf(false) }

    /**
     * 当前正在跑的位移协程。
     *
     * ⚠️ 换成 `animate()` 写状态后，**没有** `Animatable.snapTo` 那种"自动取消上一段动画"
     * 的语义了：两段动画会同时往同一个 `Float` 里写，谁后写谁生效，表现为位移乱跳。
     * 因此这里显式持有 Job：拖动开始 / 硬切入口前先取消它（用户一按下去就该接管）。
     */
    var offsetAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun cancelOffsetAnimation() {
        offsetAnimJob?.cancel()
        offsetAnimJob = null
        animatingOffset = false
    }

    /** 唯一的位移写入口：页面、手势补偿、读值三处永远同帧同值。 */
    fun applyDragOffset(value: Float) {
        dragOffsetPx = value
        TabDragState.applied = value
    }

    /**
     * 把位移动画到 [target]（内部每帧走 [applyDragOffset]）。
     *
     * 用 `animate(initialValue, targetValue, spec)` 而不是 `Animatable.animateTo`：
     * 前者给我们每一帧的回调，能把值直接写进同一个状态；后者会把值存在
     * `Animatable.value` 里，又多出第二个真相（正是本轮要消掉的东西）。
     */
    suspend fun animateDragOffsetTo(target: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>) {
        animate(
            initialValue = dragOffsetPx,
            targetValue = target,
            animationSpec = spec
        ) { value, _ -> applyDragOffset(value) }
    }

    /** 把目标入口的回退栈清到它的首页（与既有「切入口」口径一致）。 */
    fun resetTabStack(target: AppTab) {
        val controller = navControllers.getValue(target)
        if (controller.previousBackStackEntry != null) {
            controller.popBackStack(route = target.rootRoute, inclusive = false)
        }
    }

    /** 无动画硬切（开关关闭 / 动画进行中的连点 / 程序化跳转都走它）。 */
    fun switchTabInstant(target: AppTab) {
        if (target.name == selectedTabName) return
        cancelOffsetAnimation()
        applyDragOffset(0f)
        neighborTab = null
        resetTabStack(target)
        selectedTabName = target.name
    }

    /**
     * 切到 [target]：两页一起滑（微信式整屏位移）。
     *
     * 起点取 `dragOffsetPx` 的当前值而不是 0——手势松手时的提交正是从这里接着跑，
     * 于是"手指drag到一半 → 松手"能顺势滑完，不会先弹回再重滑。
     */
    fun tabSwitchTo(target: AppTab) {
        if (target.name == selectedTabName) return
        if (!tabSlideAnimEnabled) {
            switchTabInstant(target)
            return
        }
        if (switchingTabs) return
        val from = tabs.indexOfFirst { it.name == selectedTabName }
        val to = tabs.indexOf(target)
        if (from < 0 || to < 0) {
            switchTabInstant(target)
            return
        }
        val dir = if (to > from) 1 else -1
        // 先掐掉可能还在跑的回弹动画（否则两段动画同时写位移）。
        cancelOffsetAnimation()
        neighborTab = target
        switchingTabs = true
        offsetAnimJob = scope.launch {
            animatingOffset = true
            animateDragOffsetTo(
                target = -dir * gridWidthPx,
                spec = tween(
                    durationMillis = TAB_SWITCH_MS,
                    easing = FastOutSlowInEasing
                )
            )
            animatingOffset = false
            offsetAnimJob = null
            // 提交：此刻目标页已在 0，下面三行不会产生任何视觉跳变。
            applyDragOffset(0f)
            resetTabStack(target)
            selectedTabName = target.name
            neighborTab = null
            switchingTabs = false
        }
    }

    val onTabDragUpdate: (Float) -> Unit = { offsetPx ->
        // 拖动中每帧 snapTo（跟手要的是零延迟，不用 animate）。
        if (!switchingTabs) {
            // 手指一按下来就该接管：把还没跑完的回弹动画掐掉。
            cancelOffsetAnimation()
            val currentIndex = tabs.indexOf(selectedTab)
            // 负位移 = 手指往左 = 要下一个入口；正位移 = 上一个。
            val step = if (offsetPx < 0f) 1 else -1
            val targetIndex = currentIndex + step
            if (targetIndex in tabs.indices) {
                if (neighborTab != tabs[targetIndex]) neighborTab = tabs[targetIndex]
                // 方向锁：只允许朝本方向拖（微信里往回拖不会把上一页拽出来）。
                val locked = if (step > 0) offsetPx.coerceAtMost(0f) else offsetPx.coerceAtLeast(0f)
                // 同步写（不再 `launch { snapTo }`）：拖动中每帧都要与手势层的
                // 补偿量同帧一致，异步写就是抖动的来源（见上面 `dragOffsetPx` 的注释）。
                applyDragOffset(locked)
            }
        }
    }
    val onTabDragEnd: () -> Unit = {
        // 幂等：手势层可能连发（历史上出现过"每帧回弹一次"的抖动），
        // 已经在跑回弹 / 切页动画时直接忽略，不再叠加第二段动画。
        if (!switchingTabs && !animatingOffset && dragOffsetPx != 0f) {
            offsetAnimJob = scope.launch {
                animatingOffset = true
                // 弹回原位：短弹簧（阻尼高、无回弹）。
                animateDragOffsetTo(
                    target = 0f,
                    spec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                animatingOffset = false
                offsetAnimJob = null
                // 只有"确实没有切页"时才卸掉相邻页；切页那条路上 tabSwitchTo 自己收口。
                if (!switchingTabs) neighborTab = null
            }
        } else if (!switchingTabs && !animatingOffset) {
            // 位移本来就是 0：只需把相邻页卸掉，不必跑动画。
            neighborTab = null
        }
    }

    // 手势判定本体已迁到 `ui/nav/TabNavHost.kt` 的 `TabRootScreen`（挂载点教训见上：
    // 骨架满屏层盖住底栏，真机点不动；一级页内容区不覆盖底栏才安全）。
    // 这里只保留 `tabSwitchTo` 这个切页入口，经 `onTabSwipe` 传给一级页使用。

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
    //
    // ── 「本次会话由快捷方式直达」标记（需求 14）─────────────────────────────
    //
    // 快捷方式**消费成功**（目标解析命中、真的导航了）的那一次会话里，
    // 贪吃蛇页的返回键要直接结束 Activity 回桌面（需求 14），而不是回实验室页。
    // 这个事实只在这里能判定（快捷方式解析与消费都发生在本骨架），
    // 因此记成一个进程内状态向下传；`rememberSaveable` 不需要——
    // 进程重建后快捷方式 Intent 也不会再来，重建即普通会话。
    var shortcutLaunchedSession by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTabName, labShortcutTarget) {
        val target = labShortcutTarget ?: return@LaunchedEffect
        val destination = TabDestination.fromRouteOrNull(target) ?: run {
            // 解析不出来（历史快捷方式指向已改名的页面）：安静消费掉，不导航。
            onLabShortcutConsumed()
            return@LaunchedEffect
        }
        if (selectedTabName != AppTab.DISCOVER.name) {
            switchTabInstant(AppTab.DISCOVER)
            return@LaunchedEffect
        }
        navControllers.getValue(AppTab.DISCOVER)
            .navigate(AppTab.DISCOVER.routeOf(destination))
        // 导航真的发生了：本次会话记为"快捷方式直达"（贪吃蛇返回键直接回桌面的依据）。
        shortcutLaunchedSession = true
        onLabShortcutConsumed()
    }

    // ── 桌面 App 图标启动：把各入口回退栈清到首页 ───────────────────────────
    //
    // 🔄 **需求 4 起本段逻辑已不会被触发**（保留代码 + 完整说明，见下）。
    //
    // ## 为什么整段保留而不是删掉
    //
    // `MainActivity.appIconLaunched` 现在**永远为 false**（需求 4：普通启动
    // 不再请求回首页，见那个字段的 KDoc）。因此下面这段 effect 每次进入都会
    // 在第一个 `if` 上返回，实际是死代码。
    //
    // 刻意保留的三个理由：
    // 1. 它是需求 16/18/19 的**结构性修复说明**——那段"为什么清四个入口"的推理
    //    解释了 `singleTask` 之下各入口栈的关系，删掉就少了一份史料；
    // 2. 保留参数与调用点，将来若要把"回首页"做成一个**用户可选**的行为
    //    （而不是 Android 默认约定），只要让 `MainActivity` 重新置位即可，
    //    不用再翻出这套逻辑重写；
    // 3. 它已经完全无害：`if (!appIconLaunched) return@LaunchedEffect`
    //    是一次布尔判断，不会真的执行循环体。
    //
    // ## 需求 4 为什么不再清栈
    //
    // 用户原话：「如果短时间内再次打开软件保留当前位置，不要返回到主页」。
    // "点图标 = 回首页"只是 Android 的默认约定之一，不是用户要的；
    // 用户要的是"我走的时候在哪，回来还在哪"。
    // 而需求 16（反复跳转）/ 18（层叠嵌套）/ 19（快捷方式打不开）这三个症状的
    // 根因是**并行的 Activity 实例与并行导航栈**，已由 manifest 的
    // `singleTask` + `documentLaunchMode="never"` 修掉，与本标志无关——
    // 所以去掉这个额外行为不会让那三个问题回来。
    LaunchedEffect(appIconLaunched) {
        if (!appIconLaunched) return@LaunchedEffect
        for (tab in tabs) {
            val controller = navControllers.getValue(tab)
            // 只在该入口确有深层页面时才动：没有的话 `popBackStack` 返回 false，
            // 但更重要的是**不触发任何栈变化**——首帧冷启动时四个控制器都还没
            // 设置 navigation graph，此时调用它们是无意义的空转。
            if (controller.previousBackStackEntry != null) {
                controller.popBackStack(route = tab.rootRoute, inclusive = false)
            }
        }
        // 顺手把选中入口固定到「主页」：需求 18 的场景里用户当时停在「发现」的
        // 贪吃蛇页，只清栈而不切入口的话，用户会看到「发现」入口空掉到实验室页，
        // 而不是预期中的主页。
        switchTabInstant(AppTab.HOME)
        onAppIconLaunchConsumed()
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
            // 主页打卡成功 → 跳「统计」入口（v1.1.72 需求：「待动画结束后跳转到统计页」，
            // 动画等待在 HomeScreen 内部完成，这里只负责"跳"）。
            //
            // ⚠️ 必须与底栏点击走**同一条路径**：改 `selectedTabName` 即可
            // （pager 的 `LaunchedEffect` 会跟着 animateScrollToPage，底栏高亮也会跟着变），
            // 但**不要**在这里 popBackStack 目标入口——那是"切入口"的附加动作，
            // 而打卡是用户主动的主页操作，统计页也没有二级页残留的问题；
            // 若照搬 popBackStack，还会把用户在统计页里可能停留的状态清掉。
            // 2026-10-22 需求 11：**带滑动转场**跳统计页（原来是无动画硬切）。
            // 走 `tabSwitchTo` 而不是 `switchTabInstant`：主页与统计在底栏上是相邻项，
            // 这条路径与"手指左滑一次"完全同一套动画（两页一起位移、目标页落在 0 后提交），
            // 因此不会出现"从主页直接闪到统计"的断层。
            // 开关门控在 `tabSwitchTo` 内部：关掉「底部入口切换动画」时它自己回落硬切。
            onCheckInCompleted = { tabSwitchTo(AppTab.CALENDAR) },
            // 一级页内容区的横滑手势切入口（v1.1.72）。挂载点与竞态教训见
            // `tabSwipeModifier` 声明处的注释——手势**只在一级页内容区**，
            // 不覆盖底栏（第一版挂满屏层导致真机底栏点不动）。
            onTabSwipe = ::tabSwitchTo,
            // 跟手动画（本轮需求）：拖动中实时位移 + 未切页的弹回收尾，
            // 由 `TabNavHost` 透传到 `TabRootScreen` 的手势层。
            // ⚠️ 受「左右滑动动画」开关门控：关 = 连跟手位移也禁用（见上注释）。
            // 用 lambda 包一层让手势层每次调用时都读到最新开关值。
            onTabDragUpdate = { offsetPx ->
                if (tabSlideAnimEnabled) onTabDragUpdate(offsetPx)
            },
            onTabDragEnd = onTabDragEnd,
            // 需求 14：快捷方式直达的会话里，贪吃蛇页按返回 = 结束应用（回桌面）。
            // 两个参数的语义见 TabNavHost 对应形参的 KDoc。
            launchedFromLabShortcut = shortcutLaunchedSession,
            onExitApplication = onExitApplication,
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
                                // 切到别的入口 —— 走 [tabSwitchTo] 这条**唯一**路径：
                                // 与横滑手势、桌面快捷方式共用同一套逻辑
                                // （回退到首页 + 改 selectedTabName + 记录滑动方向），
                                // 于是底栏点击也能拿到与横滑一致的滑入动画。
                                //
                                // 之前这里是内联的 `popBackStack + selectedTabName = ...`，
                                // 与 tabSwitchTo 重复了一份；v1.1.72 加滑动动画后，
                                // 两份实现会各自漂移（本项目已多次踩这个坑，见
                                // `DeveloperSwitchRow` 的注释），故统一。
                                tabSwitchTo(tab)
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
            //
            // v1.1.72：⚠️ 这一层**刻意不挂**横滑手势——它满屏、z 轴盖住底栏，
            // 第一版挂在这里导致真机上底栏四个 tab 全部点不动（时序竞态吞事件）。
            // 手势已下沉到一级页内容区（`TabRootScreen` 根 Box，自带底栏让位 padding、
            // 物理上不覆盖底栏），切页逻辑走 `onTabSwipe` 传下去；完整来龙去脉见
            // `tabSwipeModifier` 声明处的注释。
            Box(Modifier.fillMaxSize()) {
                // ── 两页并存的滑移容器（2026-10-22 重做）────────────────────────
                //
                // 结构：**下层**是相邻页（只在 `neighborTab != null` 时存在），
                // **上层**是当前页。两层各自只做 `translationX`，没有淡入淡出
                // （两层同时半透明会透出身后内容，历史上表现为"双重曝光"）。
                //
                // 为什么静止时只有一页：`neighborTab` 在拖动开始时才被置位、
                // 松手回弹或提交完成立即清空——Pager 那种"三页常驻"不成立，
                // 底栏的测量环境与改造前完全一致（v1.1.72 的返工教训见上）。
                //
                // ⚠️ 上下顺序不能反过来：相邻页必须先声明（画在下面），
                // 否则它会被当前页盖住，拖动时看到的是"当前页在动、身后空白"。
                neighborTab?.let { pending ->
                    val dir = if (tabs.indexOf(pending) > tabs.indexOf(selectedTab)) 1 else -1
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                // 吸附在当前页旁边：当前页到 -屏宽时它正好落在 0。
                                translationX = dragOffsetPx + dir * gridWidthPx
                            }
                    ) {
                        key(pending) { tabContent(pending, maskedPhone) }
                    }
                }

                // `key(selectedTab)` 不能省：让每个入口占用**各自的组合槽位**，
                // 否则四个入口复用同一槽位，NavHost 会残留上一个入口的导航状态。
                key(selectedTab) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = dragOffsetPx }
                    ) {
                        tabContent(selectedTab, maskedPhone)
                    }
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
                switchTabInstant(AppTab.MINE)
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

    // ── 云登录后的首次合并策略弹窗（v3 云同步）────────────────────────────────
    //
    // 挂在根骨架而不是云登录页：判断条件是「云会话存在 且 本次登录还没问过合并
    // 策略」，这个条件天然覆盖**所有**登录入口（现在只有云登录页，将来再加
    // 引导流程也不会漏），不必每个登录页各弹一次。
    //
    // ⚠️ 同步状态首帧为 null（DataStore 磁盘读未完成）：此时**不弹**——
    // 宁可晚一帧，也不能在还没读到「已问过」标志时误弹一次。
    //
    // 「已问过」由本宿主负责写入（`applyFirstLoginStrategy` 不自己写）：
    // 任一选择（含「暂不处理」与点弹窗外）都算问过；登出时由云同步页重置。
    val cloudAuthClient = remember(localContext) { localContext.appContainer.cloudAuthClient }
    val cloudSyncRepository = remember(localContext) {
        localContext.appContainer.cloudSyncRepository
    }
    val cloudSyncStateStore = remember(localContext) {
        localContext.appContainer.syncStateStore
    }
    val cloudSession by cloudAuthClient.session.collectAsStateWithLifecycle()
    val cloudSyncState by cloudSyncRepository.state.collectAsStateWithLifecycle(initialValue = null)
    var firstLoginBusy by remember { mutableStateOf(false) }

    val syncSnapshot = cloudSyncState
    val showFirstLoginDialog = cloudSession != null &&
        syncSnapshot != null &&
        !syncSnapshot.firstLoginHandled &&
        !firstLoginBusy

    if (showFirstLoginDialog) {
        FirstLoginStrategyDialog(
            onStrategy = { strategy ->
                firstLoginBusy = true
                scope.launch {
                    val message = try {
                        cloudSyncRepository.applyFirstLoginStrategy(strategy).message
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "数据合并出现问题：${e.message ?: "未知错误"}，可稍后在云同步页手动同步"
                    }
                    // 无论成败都标记「已问过」：失败的用户仍可在云同步页手动同步，
                    // 但不该为此每次冷启动都被重新弹一次。
                    cloudSyncStateStore.setFirstLoginHandled(true)
                    firstLoginBusy = false
                    snackbarHostState.showSnackbar(message)
                }
            },
            onDismiss = {
                // 点弹窗外 = 「暂不处理」：不同步、同样标记已问，避免反复打扰。
                scope.launch { cloudSyncStateStore.setFirstLoginHandled(true) }
            }
        )
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
 * 一级入口角标的本地状态。
 *
 * ## ⚠️ v1.1.75：**不再预置任何演示角标**
 *
 * 此前 `init` 里硬编码了两条假数据（`DISCOVER = Count(3)`、`MINE = Dot`），
 * 那是骨架初建时的占位演示值，注释也写着「后续任务把真实未读来源接进来」。
 * 后果是：**用户看到的「发现 3 条未读」是编的**——进一次朋友圈就清零，
 * 重启应用又变回 3，而背后根本没有任何未读数据源。
 *
 * 现在默认**一个角标都不显示**（`badges` 初始为空）。本类保留完整的读写接口
 * （[setCount] / [increment] / [setDot] / [clear]），供将来真正接入未读来源时使用：
 * 届时只需在产生未读的地方调 [setCount] / [setDot]，显示侧（底栏 `BadgedTabIcon`
 * 与发现页列表）**一行都不用改**——它们已经是按状态渲染的。
 *
 * `clear(DISCOVER)` 那条链路（进朋友圈清未读）保持可用：集合为空时它是幂等的空操作。
 */
class TabBadgeViewModel : androidx.lifecycle.ViewModel() {

    private val badges = mutableStateMapOf<AppTab, TabBadge>()

    // 刻意**没有** init 块：默认无角标。见类 KDoc 里 v1.1.75 那段。

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
