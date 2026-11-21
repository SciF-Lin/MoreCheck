package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.morecheck.data.game.GameDataBridge
import com.morecheck.domain.GameSplashFirstRun
import com.morecheck.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 数独小游戏要加载的本地页面地址。
 *
 * 指向 `app/src/main/assets/sudoku/index.html`——**但不是** `file:///android_asset/`，
 * 而是经过 `WebViewAssetLoader` 映射出的 **https** 地址，理由见 [SudokuScreen] 的
 * KDoc「为什么必须走 https 地址」一节（Blob Worker 需要合法 origin，
 * `file://` 下会被 Chromium 拒绝）。
 */
private const val SUDOKU_PAGE_URL =
    "https://appassets.androidplatform.net/assets/sudoku/index.html"

/**
 * WebView 自身的兜底色（**与贪吃蛇页同口径**：固定深色、不跟随主题）。
 *
 * 取 `0xFF12151C`：与 `assets/sudoku/index.html` 里的 `--c-bg` **同一个色值**——
 * 那是个写死深色的页面（全文没有 `prefers-color-scheme` 分支，已核实），
 * 所以浅色主题下也必须用深底兜底，否则 WebView 在页面绘制完成前会露出一圈白边。
 *
 * ⚠️ 必须是**不透明**（前缀 `FF`）：半透明时 WebView 会把底色与窗口背景混合，
 * 在浅色主题下仍会透出浅色。
 */
private const val SUDOKU_BACKGROUND_COLOR = 0xFF12151C.toInt()

/**
 * 本页开屏层的**停留时长**（含它自己的淡入时间）。
 *
 * 与贪吃蛇页的 `SNAKE_SPLASH_HOLD_MS`（同为 1000L）**刻意取同一个值**：
 * 需求 17 原文「打开和贪吃蛇一样也显示 helloworld」——"一样"同时指
 * 文案、字号、动效与时长，两处必须逐项一致，否则用户会觉得数独是另一套东西。
 * （那个常量是 `SnakeScreen.kt` 的文件级 private，这里引用不到，
 * 也不该为"引用它"而把它改成 internal——两处各写一个同名同值的常量，
 * 靠注释绑定，比跨文件共享一个值更经得起以后的单独调整。）
 */
private const val SUDOKU_SPLASH_HOLD_MS = 1000L

/** 本页开屏层的**淡出时长**（与贪吃蛇同值，理由同上）。 */
private const val SUDOKU_SPLASH_FADE_MS = 220

/** 本页开屏层文字的**入场时长**（与贪吃蛇 / 应用开屏 / 朋友圈同值）。 */
private const val SUDOKU_SPLASH_TEXT_ENTER_MS = 420

/** 本页开屏层文字的**上浮距离**（与另外三处同值）。 */
private const val SUDOKU_SPLASH_TEXT_LIFT_PX = 24f

/**
 * 单个 WebView 实例的持有者（与贪吃蛇页的同名类**刻意各写一份**）。
 *
 * 本项目既有纪律是"一屏一个自洽实现"：抽公共类要让两个页面共享一个位于第三处的
 * 实现细节（谁负责 destroy、能否置空、何时置空），改动半径反而变大。见
 * `SnakeScreen.SnakeWebViewHolder` 的完整说明。
 */
private class SudokuWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 数独页的**本页专属**开屏层：整屏居中一个 `Hello World`，淡入 + 上浮。
 *
 * 与 `SnakeScreen.SnakeSplashOverlay` 是**逐项同源**的两份实现（文案、字号、字重、
 * 曲线、上浮距离全部一致，需求 17 要求"和贪吃蛇一样"）。刻意不抽公共组件：
 * 两处的外层主题不同（贪吃蛇那层的底色取舍记录在它自己的 KDoc 里），
 * 抽出来就要给公共组件加一个"底色从哪来"的参数，反而把两处各自的说理拆散。
 *
 * ## 底色由调用方传入
 *
 * 需求 3 给两个游戏页加了**三档 chrome 模式**：SYSTEM 档下开屏层跟随主题，
 * 两档深色模式固定深底。判断的输入（`chromeMode`）在 [SudokuScreen] 里，
 * 同一个判断结果还要给 Scaffold 容器与内容区兜底用——一处算、四处用，
 * 才不会出现"开屏层是深底、内容区却是浅底"这种半档状态。
 * 文字色跟随主题色（`primary`）的取舍，以及为什么不像 `SplashScreen` 那样
 * 在深色模式改白字，见 `SnakeScreen.SnakeSplashOverlay` 的 KDoc——
 * 两页共用同一套理由，不重复写。
 *
 * @param backdrop 开屏层整屏底色，由 [SudokuScreen] 按 `chromeMode` 算好传入。
 */
@Composable
private fun SudokuSplashOverlay(
    backdrop: Color,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = SUDOKU_SPLASH_TEXT_ENTER_MS)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backdrop),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Hello World",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * SUDOKU_SPLASH_TEXT_LIFT_PX
            }
        )
    }
}

/**
 * 数独页的 asset loader（https 本地资源映射的唯一入口）。
 *
 * 为什么用 `remember` 而不是文件级单例：`AssetsPathHandler` 的构造签名是
 * `AssetsPathHandler(Context)`（已用 javap 核对字节码确认），而文件级
 * `lazy` 拿不到 Context——若为了建它而存一个 Context 在文件级，
 * 那是一个**长期持有 Context** 的泄漏隐患。放在 `remember` 里：
 * Context 随组合生命周期走，loader 也随本页重建，两者同生共死。
 *
 * 为什么可以放心每次进入都新建：`WebViewAssetLoader` 内部只有一份
 * `PathMatcher` 列表（前缀 → handler），**无状态**；handler 也只是把
 * `assets/xxx` 解析成 `AssetManager.open("xxx")`。重建它没有任何副作用。
 *
 * ⚠️ `addPathHandler("/assets/", ...)` 的**前缀必须带斜杠**：请求 URL 是
 * `https://appassets.androidplatform.net/assets/sudoku/index.html`，
 * 去掉开头的 `/` 会让前缀匹配不上，整页 404 → 白屏。
 */
@Composable
private fun rememberSudokuAssetLoader(context: Context): WebViewAssetLoader = remember(context) {
    WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
}

/**
 * 「实验室 → 数独」。
 *
 * 需求 17 原文：「实验室新增数独游戏，文件为：<用户提供的 shudu.html>，
 * 打开和贪吃蛇一样也显示 helloworld」。
 *
 * ## 为什么用 WebView，而不是用 Compose 重写
 *
 * 素材是一份 **5121 行的自包含单文件**（内联 JS 模块注册表 + 内联 CSS，零外链、
 * 零网络请求——已逐项 grep 核实：无 fetch / XMLHttpRequest / WebSocket / location /
 * window.open / history / import() / ServiceWorker / sessionStorage）。
 * 重写成 Compose 要重新实现题目生成引擎（含难度评分、唯一解校验）、
 * 笔记 / 高亮 / 冲突检测、统计与设置，工作量以千行计而收益为零。
 *
 * ## ⚠️ 为什么必须走 https 地址，不能 `file:///android_asset/`
 *
 * 贪吃蛇页用的是 `file:///android_asset/snake/index.html`，这里**不能照抄**：
 * 数独页面用 `new Blob(...)` + `URL.createObjectURL` 起了一个 **Worker**
 * 做题目生成（`assets/sudoku/index.html` 第 5093-5094 行），而 **Blob URL 的
 * origin 继承自创建它的文档**——`file://` 文档的 origin 是 **opaque**，
 * Chromium 在部分版本上会直接以 `SecurityError` 拒绝 `new Worker(blobUrl)`。
 *
 * 拒绝的后果不是白屏，而是页面自己的降级链：策略 A（Worker）→ 策略 C
 * （主线程 `requestAnimationFrame` 分片生成）。功能不中断，但生成期间**主线程
 * 被分片占住**，低端机上菜单点「开始新局」会明显卡顿。
 *
 * 因此这里用 `WebViewAssetLoader` 把 assets 映射到
 * `https://appassets.androidplatform.net/assets/...`（AndroidX webkit 已在依赖里，
 * 不需要新依赖）。这个域名是 Google 官方为「本地资源 + 合法 https origin」
 * 保留的：它**不在公网 DNS 里**，不会被解析到任何真实服务器，
 * 因此不产生任何网络请求（与「除天气外不外发请求」的口径一致），
 * 却给了页面一个 https origin，Blob Worker 的策略 A 得以保留。
 *
 * ## 与贪吃蛇页的三个关键差异（都不是疏漏）
 *
 * 1. **`domStorageEnabled = true` 是硬性要求**（贪吃蛇也有，但那里只存最高分）：
 *    数独把**当前进度**（`sudoku.v1.save`）、统计、设置、上次难度四项都存在
 *    `localStorage` 里。Android WebView **默认关闭 DOM storage**，不开就是
 *    「每次重开都从第一关开始」，且页面自己的 `probe()` 会静默降级——
 *    用户看不到任何报错，只会觉得"我玩的进度莫名其妙没了"。
 * 2. **`allowFileAccess` 保持关闭**（贪吃蛇必须开）：数独是**单文件**，
 *    没有任何同目录相对引用（无 `<link>` / `<script src>`，已核实），
 *    因此没有"必须放开 file:// 才能加载子资源"的理由。开着只是白送攻击面。
 * 3. **离开页面时主动 `flushSave()`**：页面自己的保存是 200ms 防抖
 *    （`saveTimer`），而 `beforeunload` 在 WebView 里**不保证触发**
 *    （`onPause` 不派发它）。防抖未到期就销毁 WebView = 丢掉最后 200ms 内的操作。
 *    所以这里在 `onRelease` 前先 `evaluateJavascript` 同步调一次
 *    `window.__sudokuApp.flushSave()`（该入口在素材第 5109 行挂到 window 上）。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页，**刻意不做**「`canGoBack()` 就先回退网页历史」：
 * 本页是单页应用（只加载一个 URL，页面内没有导航，已核实无 location / history），
 * `canGoBack()` 永远为 false，写出来只是一段永不执行的死分支。
 *
 * ## 状态栏 / 导航栏图标与三档 chrome（需求 3）
 *
 * 与贪吃蛇页**完全同口径**，且两页的档位由**同一个**持久化键驱动
 * （`SettingsRepository.Keys.DEV_GAME_CHROME_MODE`），因此用户在开发者选项里
 * 换一次档，两个游戏页一起变——不会出现"贪吃蛇浅色标题栏、数独深色标题栏"的割裂。
 *
 * - **DARK_CLASSIC（默认）**：改造后的既有观感。容器 / 标题栏 / 内容兜底全部取
 *   [SUDOKU_BACKGROUND_COLOR]（= 页面 `--c-bg`），系统栏图标一律浅色；
 * - **SYSTEM**：容器与标题栏取 `colorScheme.surface`，标题 / 返回箭头取 `onSurface`，
 *   系统栏图标跟随主题（浅色主题 → 深色图标）；底部 inset **保留**给内容区，
 *   让手势条那一带的背景由 `Scaffold` 的浅色容器绘制，与深色图标配对（HANDOVER 5.17）；
 * - **DARK_ADAPTIVE**：容器与内容固定深色，标题栏 `containerColor = Color.Transparent`，
 *   标题栏与游戏区无缝；系统栏图标仍取浅色（容器不透明，inset 区域不会露白）。
 *
 * 完整的推理（为什么图标必须与身后背景配对、为什么透明标题栏要求容器不透明）
 * 写在 `SnakeScreen` 的「三档 chrome 模式」一节，两页刻意不重复长篇说明。
 *
 * @param onBack 退出本页。
 *
 *   🔄 v1.1.74 需求 6 起本页住在**独立 Activity**（`SudokuHostActivity`，见
 *   `GameActivityHost.kt`）里，不再是 `TabNavHost` 的一个二级目的地，
 *   调用方传的是 `finish()`——「返回」= 结束本页所在的 task。
 *   与 `SnakeScreen` 同一口径：页面本身不知道自己被谁组合。
 * @param showSplash 是否显示本页开屏层（需求 23：开发者选项可关）。
 *   传**生效值**（总开关 && 用户开关，回落规则见
 *   `DeveloperOptionsRepository.gameSplashSudokuEnabled`），不是原始值。
 *
 *   ⚠️ v1.1.74 需求 5：本形参**不再**等于"这次一定会播"。它与
 *   「本进程是否已播过」两个条件同时成立才播——规则见 [GameSplashFirstRun]，
 *   与贪吃蛇页共用同一份进程级标记。
 * @param showTitleBar 是否显示顶部标题栏（与贪吃蛇页的开关同源，需求 23 扩展）。
 * @param chromeMode 本页 chrome 配色档位（需求 3，与贪吃蛇页共用一个生效值）。
 *   默认 [GameChromeMode.DARK_CLASSIC] = 与改造后完全一致。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SudokuScreen(
    onBack: () -> Unit,
    showSplash: Boolean = true,
    showTitleBar: Boolean = true,
    chromeMode: GameChromeMode = GameChromeMode.DARK_CLASSIC
) {
    val holder = remember { SudokuWebViewHolder() }

    // ── 存档同步桥（v3 云同步）────────────────────────────────────────────────
    //
    // 数独把当前进度 / 统计 / 设置 / 上次难度四项存在 localStorage
    // （`GameDataBridge.GameKeys.SUDOKU` 列了全部键）。水合脚本已随
    // assets/sudoku/index.html 的 <head> 注入，先于页面自身脚本执行；
    // 采集则走下方 factory 里的 onPageFinished 与定时循环。
    val gameBridge = rememberGameBridge(GameDataBridge.GameKeys.SUDOKU)

    // ── 本页开屏（Hello World，[SUDOKU_SPLASH_HOLD_MS] 后淡出）────────────────
    //
    // `rememberSaveable` 而不是 `remember`：屏幕旋转 / 进程被回收后重建时，
    // 不希望把"已经看完开屏"这个事实丢掉、让用户再看一次 1 秒（与贪吃蛇同口径）。
    //
    // ⚠️ 关掉开屏（需求 23）时**不启动那个 1 秒计时**：计时本身没有副作用，
    // 但"关掉开屏"的语义就是"不要等待"，留着定时器只是白跑一次 delay。
    //
    // ⚠️ 局部状态必须叫 `splashVisible`，**不能**与形参 `showSplash` 同名：
    // 同名会在 Kotlin 里构成"局部声明遮蔽形参"，函数体里所有 `showSplash`
    // 都指向那个恒 true 的局部状态，形参彻底不可见——贪吃蛇页真的这么写错过，
    // 表现为"关掉开屏开关仍然显示 Hello World"（详见 `SnakeScreen` 里那段 KDoc）。
    // 本页从一开始就叫 `splashVisible`，两页现在同口径。
    //
    // ⚠️ 初值取形参 `showSplash` 而不是写死 `true`：写死 true 时即使传了
    // `showSplash = false`，第一帧仍会以"可见"组合出开屏层并播一次 fadeIn，
    // 随后才被 effect 置 false 触发淡出——"关掉开屏仍闪一下 Hello World"。
    //
    // ## ⚠️ v1.1.74 需求 5：开屏改为「本进程只播一次」
    //
    // 口径与贪吃蛇页**完全一致**（规则本体见 `GameSplashFirstRun` 的 KDoc，
    // 这里不重复）：本次应用进程生命周期内第一次进入才播；玩一局退出、
    // 应用还在后台、再进来直接看到游戏。
    //
    // 番茄时钟**不参与**这条规则——它已按需求 3 取消了开屏层（见 `PomodoroScreen`）。
    //
    // 取号放在组合期而不是 effect 里：`rememberSaveable` 的初值每个组合实例只算一次，
    // 放进 effect 会在旋转重建时**多消耗一次**「首次」。
    //
    // `consumeIfFirstRun(showSplash)` 在开关关闭时**不消耗**首次机会——
    // 用户手动关掉开屏不该把"第一次"用掉，否则他下次打开开关就再也看不到了。
    val shouldSplash = remember(showSplash) { GameSplashFirstRun.consumeIfFirstRun(showSplash) }
    var splashVisible by rememberSaveable { mutableStateOf(shouldSplash) }
    LaunchedEffect(shouldSplash) {
        if (!shouldSplash) {
            splashVisible = false
            return@LaunchedEffect
        }
        delay(SUDOKU_SPLASH_HOLD_MS)
        splashVisible = false
    }

    // ── 需求 3：三档 chrome → 一组派生颜色（一处算、多处用，与贪吃蛇页同构）──
    //
    // 深色主题判定与 `MoreCheckApp` 的 `SplashGate` 逐字一致（`background` 明度），
    // 不能用 `isSystemInDarkTheme()`：本应用深浅色由用户设置决定，可能与系统相反。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val classicBackground = Color(SUDOKU_BACKGROUND_COLOR)

    val chromeBackground: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.surface
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> classicBackground
    }

    val titleBarContainer: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.surface
        GameChromeMode.DARK_CLASSIC -> classicBackground
        GameChromeMode.DARK_ADAPTIVE -> Color.Transparent
    }

    val titleBarContent: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.onSurface
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> Color.White
    }

    val splashBackdrop: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.background
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> classicBackground
    }

    // ⚠️ 这个值直接赋给 `isAppearanceLightStatusBars` / `...NavigationBars`，
    // 字段含义是「**栏的外观是浅色的**」，即**图标 / 文字用深色**（语义极易记反，
    // `ScanScreen` 里专门注释过）：
    // - 两档深色：背景恒深 → 必须**浅色**图标 → `false`；
    // - SYSTEM：浅色主题 → **深色**图标 → `true`；深色主题 → 浅色图标 → `false`。
    // 与贪吃蛇页同一个表达式（两页共用一个档位，图标明暗必须完全一致）。
    val appearanceLightBars: Boolean =
        chromeMode == GameChromeMode.SYSTEM && !darkTheme

    // ── 状态栏 / 导航栏图标明暗（与贪吃蛇页同口径，见 KDoc）──────────────────
    //
    // `appearanceLightBars` 进 `DisposableEffect` 的 key：切档位时要重新应用一次，
    // 否则会停留在进场那一刻的取值上。
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, appearanceLightBars) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            // 字段名是"bar 的 appearance 是否 light"，指的是栏内图标 / 文字为**深色**，
            // 语义容易记反，见 ScanScreen 里同名字段的注释。
            controller.isAppearanceLightStatusBars = appearanceLightBars
            controller.isAppearanceLightNavigationBars = appearanceLightBars
            onDispose {
                // 原样归还，别把本页的取值泄漏给返回后的实验室页。
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    // 兜底销毁（onRelease 已经销毁过一次，这里是二次保险）。
    DisposableEffect(Unit) {
        onDispose {
            // v3：离开前尽力采集存档（先 flushSave 再采，与 onRelease 同序）。
            holder.view?.let { web ->
                web.evaluateJavascript("window.__sudokuApp && window.__sudokuApp.flushSave()", null)
                web.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU)
            }
            holder.destroy()
        }
    }

    // ── 定时采集存档（v3 云同步）──────────────────────────────────────────────
    //
    // 数独的存档是"刚填了一个数就走"这种高频小改动，15 秒的采集间隔保证
    // 最坏只丢 15 秒内的输入；onPageFinished 与离开时的两次采集兜住两端。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(GAME_PUSH_INTERVAL_MS)
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU)
        }
    }

    // https 本地资源映射器（见 [rememberSudokuAssetLoader] 的 KDoc）。
    // 必须在本页顶层创建、再传进 WebView：它持有一个 Context（来自 AssetsPathHandler），
    // 而 `AndroidView.factory` 里的 `ctx` 是**Activity** Context——用 applicationContext
    // 更稳妥（handler 只读 assets，用不到 Activity 的任何东西）。
    val appContext = LocalContext.current.applicationContext
    val assetLoader = rememberSudokuAssetLoader(appContext)

    BackHandler { onBack() }

    Scaffold(
        // ── 需求 3：容器色按 chrome 档位来 ─────────────────────────────
        //
        // `TopAppBar` 会绘制状态栏的 inset 背景，取的是容器色。若容器是默认的
        // 主题 `background`（浅色主题下接近白色），而系统栏图标又是浅色，
        // 状态栏区域就是「白底 + 白图标」（用户截图反映的问题）。
        // 现在两档深色给深色容器、SYSTEM 档给语义色容器，与各自档位的图标明暗**配对**。
        // 完整推理见 `SnakeScreen` 的「三档 chrome 模式」一节。
        containerColor = chromeBackground,
        topBar = {
            // 条件渲染而不是恒渲染 + alpha(0f)：不显示时这 64dp 高度要**还给**游戏区
            // （与贪吃蛇页同一个理由）。
            if (showTitleBar) {
                TopAppBar(
                    title = { Text("数独") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    // ── 需求 3：标题栏配色随档位 ────────────────────────────
                    //
                    // 两档深色的底色取 [SUDOKU_BACKGROUND_COLOR]（= 页面 `--c-bg`），
                    // 三处（标题栏 / WebView 兜底 / 底栏延伸区）同色才不会出现分界。
                    // DARK_ADAPTIVE 用全透明，让标题栏"消失"在游戏区上沿；它的状态栏
                    // inset 仍由上面的 `chromeBackground`（不透明深色）绘制，不会露出白底。
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = titleBarContainer,
                        titleContentColor = titleBarContent,
                        navigationIconContentColor = titleBarContent
                    )
                )
            }
        }
    ) { innerPadding ->
        // 「两层」结构：底层 WebView（游戏本体）+ 顶层 Hello World 开屏层。
        // ⚠️ **声明顺序决定叠放顺序**：Compose 的 Box 按声明顺序绘制，后声明的画在上面，
        // 所以 AndroidView 必须写在开屏层**之前**。这与贪吃蛇页是同一条约束
        // （那里有更详细的说明）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ── 内容区底色 + 系统栏 inset 归属（按档位，与贪吃蛇页同构）──
                //
                // 两档深色：铺深色兜底 + 把底部 inset 让回给内容，让深色铺到手势条下，
                // 与「浅色系统栏图标」配对（HANDOVER 5.17）。
                // SYSTEM：反过来**保留底部 inset**，手势条那一带的背景由 `Scaffold` 的
                // 浅色容器绘制，与「跟随主题的深色图标」配对；代价是游戏画面矮一条
                // 手势条的高度，这是本档的语义。完整理由见 `SnakeScreen` 里那段长注释。
                //
                // ⚠️ `SUDOKU_BACKGROUND_COLOR` 存的是**ARGB 整数**（给 WebView 的
                // `setBackgroundColor(Int)` 用），而 Compose 的 `background()` 收
                // `Color`，所以上面用 `classicBackground` 把它包成了 `Color`。
                // 这里直接用 `chromeBackground`（SYSTEM 档=surface，两档深色=classicBackground），
                // 与 `Scaffold` 容器同色，手势条那一条与页面底不会出现两截色。
                .background(chromeBackground)
                .then(
                    if (chromeMode == GameChromeMode.SYSTEM) {
                        Modifier.padding(innerPadding)
                    } else {
                        Modifier
                            .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                            .padding(top = innerPadding.calculateTopPadding())
                            // ⚠️ 底部 inset 必须**让给内容**（v1.1.78 修复）：
                            // 放行后 WebView 会一直铺到屏幕最底，但它自己的 `innerHeight`
                            // 把导航栏那一条也算进去了（页面里 `100dvh` 无法感知系统栏），
                            // 于是「键盘贴底」的最后一行数字键会被手势条压住。
                            // 这里补一条与手势条等高的内边距，键盘正好落在可视区上方；
                            // 背景色仍是 chromeBackground，视觉上不会有分界。
                            .navigationBarsPadding()
                    }
                )
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 游戏是纯 JS 驱动（题目生成引擎 + Canvas/DOM 渲染），
                        // 关掉 JS 就只剩一个空壳。
                        settings.javaScriptEnabled = true

                        // ⚠️ **硬性要求**（见 KDoc 第 1 条差异）：进度 / 统计 / 设置 /
                        // 上次难度全在 localStorage 里，不开 DOM storage 就是每次重开
                        // 都从第一关开始，且页面会静默降级、用户看不到任何提示。
                        settings.domStorageEnabled = true

                        // 与贪吃蛇**相反**：数独是单文件页面，没有任何同目录子资源
                        // 要加载，因此没有"必须放开 file://"的理由。保持关闭，
                        // 少一个"页面里的脚本读应用私有目录"的攻击面。
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false

                        // 深色兜底色：与页面 `--c-bg` 同值（见常量 KDoc）。
                        // 页面写死深色、不跟随 prefers-color-scheme，所以这里
                        // **刻意不跟随主题**——跟随主题会让浅色模式下露出一圈浅边，
                        // 那是长期可见的缺陷。
                        setBackgroundColor(SUDOKU_BACKGROUND_COLOR)

                        // ── 视口设置（v1.1.78 实测结论）──────────────────────────────
                        //
                        // 素材页面带 `<meta name="viewport" content="width=device-width,
                        // initial-scale=1, viewport-fit=cover">`。这里**保持 WebView 默认的
                        // `useWideViewPort = false`**（即不启用宽视口、忽略该 meta）：
                        // 实测此时布局宽度等于 WebView 的 CSS 宽度（约 385px = 手机宽度），
                        // 页面走竖屏分支（棋盘在上、键盘贴底），渲染 1:1、字号正常。
                        //
                        // 曾经把这一项打开（`true`）试图"让 meta 生效"，实测**两者结果相同**，
                        // 真正导致「键盘跑到棋盘右侧、整页缩成一半」的不是它，而是：
                        // ① 页面里 `@media (orientation: landscape) and (max-height:480px)`
                        //    被 WebView 误判为命中（见 index.html 里那处注释）；
                        // ② WebView 的 `vh`/`dvh` 与媒体查询 height 恒为 0
                        //    （见 index.html 末尾的 vh-fix 脚本）。
                        // 两处都在页面侧修掉了，因此这里保持默认值即可，改动最小。
                        settings.loadWithOverviewMode = true

                        // ── https 本地资源映射（Blob Worker 的 origin 来源）────────
                        //
                        // `WebViewAssetLoader` 是 AndroidX webkit 提供的官方方案：
                        // 把 assets / res 映射到一个**保留的 https 域名**下。
                        // 该域名不在公网 DNS 中，因此不会产生任何真实网络请求。
                        //
                        // ⚠️ 必须在 `loadUrl` **之前**装好：WebView 只在
                        // `shouldInterceptRequest` 里问 loader 要内容，
                        // 而第一次 `loadUrl` 就会触发这个回调。装晚了
                        // （比如先 loadUrl 再 setWebViewClient）首屏会走系统默认
                        // 处理 → 解析失败 → 白屏。
                        //
                        // ⚠️ 刻意**不**用 `WebViewClientCompat`（webkit 库提供的
                        // 兼容版）：本项目 minSdk = 29 > 24，
                        // 而 `shouldInterceptRequest(view, request)` 这个两参重载
                        // 从 API 21 起就存在，直接用平台类即可，少一层包装。
                        //
                        // ⚠️ 必须用**方法名** `setWebViewClient(...)` 而不是
                        // 属性赋值 `webViewClient = ...`：WebView 的
                        // `getWebViewClient()` 是 `@hide`（公开 SDK 里没有它），
                        // Kotlin 的合成属性访问要求 setter 与 getter 成对出现，
                        // 缺 getter 时属性写法解析不到。`SearchWebScreen` 里
                        // 同样用的是方法名（同一理由）。
                        //
                        // ## 调试期用的日志已移除（真机验证结论记录在此）
                        //
                        // 联调时曾在这里临时挂 `Log.d` 打印每个被拦截的请求与
                        // `onPageFinished` 里的 DOM 探针。真机（模拟器）实测结论：
                        // `assets/sudoku/index.html` → `text/html/200`（资源映射正确）；
                        // DOM 探针返回 `{"title":"数独 Sudoku","app":true,
                        // "text":"数独\n入门\n…\n开始新局\n统计\n设置"}`，
                        // 说明页面完整渲染、`window.__sudokuApp` 已就位。
                        // 唯一失败项是 `favicon.ico` 的 `net::ERR_NAME_NOT_RESOLVED`：
                        // 那个域名不在公网 DNS 里（这正是它「不产生真实网络请求」的原因），
                        // favicon 请求失败无害，页面也不会等它。
                        // 结论确认后日志已删——线上代码不该为一次排查留下持续开销。
                        setWebViewClient(object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return assetLoader.shouldInterceptRequest(request.url)
                            }

                            // 页面加载完成：首次采集存档（v3）。
                            // 此时页面自身脚本已把 localStorage 恢复出来，
                            // 采到的是"当前事实"，不会采到空。
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU)
                            }
                        })

                        // ── 存档同步桥（v3）────────────────────────────────────────
                        //
                        // 先于 loadUrl 注册：assets/index.html 的 <head> 水合脚本在
                        // 页面解析期就调 `__CIM_GAME__.pendingState()`。
                        // 本页是 https 本地映射（WebViewAssetLoader），localStorage
                        // 按 origin 隔离——桥是原生侧对象，与 origin 无关，照常工作。
                        addJavascriptInterface(gameBridge, GameDataBridge.JS_NAME)

                        // 只在**首次创建**时加载。`update` 里刻意不调 loadUrl：
                        // 任何一次重组都会走 update，在那里加载会让正在玩的一局被重置。
                        //
                        // ⚠️ 关于「加载时机」的实测结论（v1.1.78）：曾经怀疑是
                        // "WebView 未测量就加载导致视口高度被缓存为 0"，因此试过
                        // `post{}` 延后加载、`addOnLayoutChangeListener` 等首次非零布局后再加载、
                        // 以及加载后 reload —— **三种都不改变结果**：本机 WebView 里
                        // `innerHeight=746` 但 `100vh`/`100dvh` 恒为 0、媒体查询 height 恒为 0。
                        // 这是 WebView 内核自身在该环境下的行为，不是加载时机问题。
                        // 页面侧的兼容修正见 `assets/sudoku/index.html` 末尾的 vh-fix 脚本。
                        loadUrl(SUDOKU_PAGE_URL)

                        holder.view = this
                    }
                },
                // 刻意留空，见 factory 内对 loadUrl 的说明。
                update = { },
                // 与 DisposableEffect 是同一条销毁路径的两次保险。
                onRelease = { web ->
                    // ── 离开前主动落盘（见 KDoc 第 3 条差异）────────────────────
                    //
                    // 页面自己的保存是 200ms 防抖，而 `beforeunload` 在 WebView 的
                    // `onPause` 路径上**不保证触发**。防抖未到期就 destroy
                    // = 丢掉最后 200ms 内的操作（数独里最常见的是"刚填了一个数就走"）。
                    //
                    // `window.__sudokuApp.flushSave()` 是素材第 5109 行挂上来的入口，
                    // 幂等（清掉未到期的定时器并立即写一次）。页面还没加载完时
                    // `__sudokuApp` 不存在，`evaluateJavascript` 会静默失败——
                    // 那种情况下也没有任何进度可存，无需提示。
                    web.evaluateJavascript("window.__sudokuApp && window.__sudokuApp.flushSave()", null)
                    // ── v3：落盘之后才能采集 ────────────────────────────────────
                    //
                    // 两条 evaluateJavascript 在同一 WebView 上**按调用顺序执行**：
                    // flushSave 先把防抖里的进度写进 localStorage，采集脚本随后才读，
                    // 否则采到的是 200ms 前的旧进度。
                    web.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU)
                    holder.destroy()
                }
            )

            // 顶层开屏层：写在 AndroidView **之后** → 画在上面（Box 按声明顺序绘制）。
            // 淡入时长与淡出取同值 [SUDOKU_SPLASH_FADE_MS]，进出对称。
            //
            // 为什么显式写 `fadeIn` 而不吃 `AnimatedVisibility` 的默认
            // `fadeIn() + expandIn()`：本层的 Box 是 `fillMaxSize`，expand 会让内容
            // 在动画途中被裁剪；而文字自身已有一段"淡入 + 上浮"，再叠一层展开
            // 就是两套互不相干的入场动效（与贪吃蛇页同一个理由）。
            AnimatedVisibility(
                visible = splashVisible,
                enter = fadeIn(animationSpec = tween(SUDOKU_SPLASH_FADE_MS)),
                exit = fadeOut(animationSpec = tween(SUDOKU_SPLASH_FADE_MS))
            ) {
                SudokuSplashOverlay(backdrop = splashBackdrop)
            }
        }
    }
}

/**
 * 本文件的 asset loader 单例。
 *
 * 为什么放在文件级而不是 `remember`：`WebViewAssetLoader` 内部只持有
 * 一份 `PathMatcher` 列表（路径前缀 → handler 的映射），**不持有 Context**——
 * Context 在 [WebViewAssetLoader.AssetsPathHandler] 里（那是另一层）。
 * 因此 loader 本身可以在多次进入本页之间复用同一实例（官方用法就是建一个全局的）。
 *
 * ⚠️ 但 `AssetsPathHandler` **需要 Context**（构造签名
 * `AssetsPathHandler(Context)`，已用 javap 核对字节码确认），而本文件没有
 * 组合外的 Context 可用。解法：把 loader 的创建推迟到 `AndroidView.factory`
 * 里（那里有 `ctx`），用 `remember` 缓存到当前组合实例上。
 * 代价是每次重新进入本页会新建一个 loader——那是几次对象分配，可忽略；
 * 换来的是不把一个 Context 长期留在文件级单例里（那会是一个泄漏隐患）。
 *
 * 因此这个常量**不存在**，见 [rememberSudokuAssetLoader]。
 */
