package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.morecheck.data.game.GameDataBridge
import com.morecheck.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 数独2 要加载的本地页面地址。
 *
 * 指向 `app/src/main/assets/sudoku2/index.html`，走 `file:///android_asset/`——
 * 与**贪吃蛇 / 番茄时钟**同路，而**不是**既有数独那条 https + `WebViewAssetLoader`。
 *
 * ## 为什么不照抄 [SudokuScreen] 的 https 映射
 *
 * 既有数独页面用 `new Blob(...)` + `URL.createObjectURL` 起 Worker 做题目生成，
 * 而 Blob URL 的 origin 继承自创建它的文档，`file://` 文档的 origin 是 opaque，
 * Chromium 会以 `SecurityError` 拒绝它（完整后果见 `SudokuScreen` 的 KDoc）。
 *
 * 本页素材**没有这个问题**（已逐项核实）：无 `Worker`、无 `createObjectURL`、
 * 无 `fetch` / `XMLHttpRequest` / `WebSocket`、无 `<link>` / `<script src>` 外链、
 * 无 `window.open` / `serviceWorker`。它是真正的**单文件自包含**页面，
 * 因此 `file://` 下不会遇到任何 origin 限制，不需要 https 映射。
 *
 * ⚠️ 顺带说明：`app/src/main/assets/sudoku/` 下那份**是另一份素材**（5121 行、
 * 带 Blob Worker）。两者的目录名只差一个 `2`，改动时务必分清——
 * 本页只认 `sudoku2/`。
 */
private const val SUDOKU2_PAGE_URL = "file:///android_asset/sudoku2/index.html"

/**
 * WebView 自身的兜底色（页面 `:root` 的 `--paper` 浅色值 `#F7F3E9`）。
 *
 * ## 取值依据：为什么是**浅色**这一档
 *
 * 本页素材**自带深浅色**：`:root` 是 `--paper:#f7f3e9`（暖白），
 * `html[data-theme="dark"]` 换成 `#131c28`（深蓝黑，**不是纯黑**），
 * 页面在 `boot()` 里按用户偏好或 `prefers-color-scheme` 自己决定切哪一档。
 *
 * 也就是说：**原生这一侧无法预知它会显示哪一档**——它取决于用户在页面里
 * 拨过什么、或系统处于深色。而这个兜底色只在「页面首帧绘制完成前」露出，
 * 是一段几十毫秒的过渡。两档里取哪个，差别只在这段过渡：
 *
 * - 取**浅色**（本值）：浅色主题下完全无感；深色主题下是「暖白闪一下 → 深蓝黑」。
 * - 取**深色**：浅色主题下是「深蓝黑闪一下 → 暖白」，那是一块**深色**在亮屏上跳，
 *   比一块**浅色**在暗屏上跳显眼得多。
 *
 * 因此取浅色。这也是本页与 [PomodoroScreen]（素材固定浅色，只能取浅色）
 * 看似同值、实则理由不同的地方：那边是「只有一档，取它」，这边是「两档里挑不那么刺眼的一档」。
 *
 * ⚠️ 必须是**不透明**（前缀 `FF`）：半透明时会与窗口背景混合，深色主题下会透出主题色。
 * ⚠️ 改页面 `--paper` 浅色值时必须同步改这里，否则首帧会有一圈可见色差。
 */
private const val SUDOKU2_BACKGROUND_COLOR = 0xFFF7F3E9.toInt()

/**
 * 单个 WebView 实例的持有者。
 *
 * 与 `SnakeScreen` / `SudokuScreen` / `PomodoroScreen` 里的同名类**刻意各写一份**，
 * 不抽公共类：本项目既有纪律是"一屏一个自洽实现"（理由见 `SnakeScreen` 里同名类的 KDoc）。
 *
 * `destroy()` 先把 `view` 置空再销毁，保证 `AndroidView.onRelease` 与
 * `DisposableEffect.onDispose` 两条路径谁先到都只销毁一次。
 */
private class Sudoku2WebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 数独2」。
 *
 * 需求原文：「实验室新增数独2游戏，参考html文件」。
 *
 * ## 与既有数独页的定位差别（**素材是两份，不是同一份的新版**）
 *
 * | | 数独（`SudokuScreen`） | 数独2（`Sudoku2Screen`） |
 * |---|---|---|
 * | 素材 | `assets/sudoku/index.html`（5121 行） | `assets/sudoku2/index.html`（1094 行） |
 * | 题目生成 | `new Blob` + Worker（策略 A） | 无 Worker，页面内直接生成 |
 * | 加载地址 | `https://appassets.androidplatform.net/...` | `file:///android_asset/...` |
 * | 配色 | 写死深色（`--c-bg #12151C`） | **自带深浅色**（`--paper` 两档） |
 * | 开屏 | 有（Hello World） | **无**（见下） |
 *
 * 两页是**并列的两个游戏**，不是新旧替代关系：素材不同、机制不同，
 * 因此这里**各写一份**而不是在 `SudokuScreen` 上加参数（项目纪律：一屏一个自洽实现）。
 *
 * ## 为什么本页**没有** Hello World 开屏
 *
 * 「游戏开屏显示」那两个开关只覆盖贪吃蛇与数独（见 `DeveloperOptionsRepository` 的
 * `gameSplashSnakeEnabled` / `gameSplashSudokuEnabled`，以及需求 5 引入的
 * `GameSplashFirstRun` 首次标记）。本页**刻意不接那一套**：
 *
 * - 接了就得再加两个 DataStore 键 + 两对"原始值 / 生效值"流 + 一份开关文案，
 *   而用户的需求原文只说"新增数独2游戏"，没有任何关于开屏的要求；
 * - 素材是 43KB 单文件、零加载成本，遮住它 1 秒是纯白等。
 *
 * **这是个可被推翻的取舍**：若将来要给本页加开屏，改法是**照抄**
 * `SudokuScreen` 的开屏三件套（常量 + Overlay + `GameSplashFirstRun.consumeIfFirstRun`）
 * 并在 `DeveloperOptionsRepository` 里加一对开关，不需要改动本页已有的任何逻辑。
 *
 * ## 配色跟随**应用主题**（而不是既有数独那套三档 chrome）
 *
 * 本页 chrome 恒等于 `MaterialTheme.colorScheme.surface`，系统栏图标跟随主题深浅——
 * 与 `PomodoroScreen` 同一口径，而不是 `GameChromeScreen` 的三档。
 *
 * 理由是本页素材**自带深浅色**：它在 `boot()` 里自己按用户偏好 /
 * `prefers-color-scheme` 决定 `data-theme`。那三档 chrome 是按
 * "**网页内容恒为深色**"的两个游戏页设计的（贪吃蛇 / 既有数独都是写死深底），
 * 对一个自己会切深浅色的页面套"深色经典 / 深色适配"，等于用外层强行钉死
 * 内层本该自适应的观感。本页**刻意不读** `gameChromeMode`。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页，**刻意不做**「`canGoBack()` 就先回退网页历史」：
 * 本页只 `loadUrl` 一次，页面内没有任何导航（已核实无 `location` / `history` /
 * `window.open`），`canGoBack()` 永远为 false，写出来只是一段永不执行的死分支。
 *
 * ## WebView 配置（每项都写清理由）
 *
 * - `javaScriptEnabled = true`：出题、渲染、localStorage 全是 JS，关掉只剩一张静态壳。
 * - `domStorageEnabled = true`（**硬性**）：页面把当前局面快照与偏好存在
 *   `localStorage` 里（素材里 `Store.loadAuto` / `saveAuto` / `loadPref` 三处）。
 *   Android WebView **默认关闭** DOM storage，不开时页面会静默降级为"只存内存"——
 *   表现为退出再进"上次那局没了"，而用户看不到任何报错。
 * - `allowFileAccess = true`：本页加载的**本身就是** `file:///android_asset/...`，
 *   与贪吃蛇 / 番茄钟同一口径。
 *   ⚠️ 与 `SudokuScreen` 的 `false` **相反且都必须如此**——按页面场景取值，别跨页照抄。
 * - `allowContentAccess = false`：本页不读任何 `content://`，最小权限。
 * - `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` **刻意不设置**，
 *   保持系统默认 false：页面没有任何跨源读取需求。
 *
 * ## 返回键与原生侧不读网页状态
 *
 * 原生**不向网页注入任何 JS**：本页的保存由页面自己的 `boot()` / 快照逻辑负责，
 * 不像既有数独要 `flushSave()`（那边是 200ms 防抖 + `beforeunload` 在 WebView 里
 * 不保证触发；本页没有那个机制，注入一个不存在的入口只会写出一段永不执行的代码）。
 *
 * @param onBack 退出本页。
 *
 *   🔄 v1.1.74 需求 6 起本页住在**独立 Activity**（`Sudoku2HostActivity`，见
 *   `GameActivityHost.kt`）里，调用方传的是 `finish()`——「返回」= 结束本页所在的 task。
 *   与 `SudokuScreen` / `SnakeScreen` 同一口径：页面本身不知道自己被谁组合。
 * @param showTitleBar 是否显示顶部标题栏（仍取自「游戏标题栏显示」那个全局开关）。
 *   关闭时这 64dp 高度要**还给**内容区，因此是条件渲染而不是 `alpha(0f)` 隐藏。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sudoku2Screen(
    onBack: () -> Unit,
    showTitleBar: Boolean = true
) {
    val holder = remember { Sudoku2WebViewHolder() }

    // ── 存档同步桥（v3 云同步）────────────────────────────────────────────────
    //
    // 数独2 的存档是 localStorage 里的三键（自动续局 / 存档列表 / 偏好，见
    // `GameDataBridge.GameKeys.SUDOKU2`）。水合脚本已随 assets/index.html 的
    // <head> 注入；采集走下方 factory 里的 onPageFinished 与定时循环。
    val gameBridge = rememberGameBridge(GameDataBridge.GameKeys.SUDOKU2)

    // ── 配色：跟随应用主题（见 KDoc「配色跟随应用主题」）────────────────────
    //
    // 深色判定与 `MoreCheckApp` 的 `SplashGate` 逐字一致（`background` 的明度），
    // 不能用 `isSystemInDarkTheme()`：本应用深浅色由用户设置决定，可能与系统相反。
    //
    // 整页 chrome 底色：Scaffold 容器 + 内容区兜底两处必须同值，
    // 否则状态栏那一带会出现「标题栏色 / 状态栏色」的分界。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // ── 状态栏 / 导航栏图标明暗 ─────────────────────────────────────────────
    //
    // `isAppearanceLightStatusBars = true` 表示栏内用**深色**图标（字段名指的是
    // "栏的 appearance 是否 light"，语义容易记反，见 `ScanScreen` 里同名字段的注释）。
    //
    // 与贪吃蛇 / 既有数独**不同**：那两页内容恒深，所以两栏都设浅色图标；
    // 本页整页跟随主题，因此**两栏都完全跟随主题**：
    // 浅色主题 → 深色图标（true），深色主题 → 浅色图标（false）。
    //
    // 底部同样跟随主题：内容区 Box 用 `consumeWindowInsets` 铺到屏幕最底，
    // WebView 也是 `fillMaxSize`，因此手势条那一带露出的就是这个 Box 的底色，
    // 而它恒等于 `colorScheme.surface` —— 与上面的 chrome 同源，按主题取图标明暗即配对。
    val appearanceLightBars: Boolean = !darkTheme

    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, appearanceLightBars) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            // 拿不到窗口时什么都不做，但 onDispose 必须存在：
            // DisposableEffect 的 lambda 必须有一个收尾。
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = appearanceLightBars
            controller.isAppearanceLightNavigationBars = appearanceLightBars
            onDispose {
                // 原样归还，别把本页的取值泄漏给返回后的实验室页。
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    // 兜底销毁，避免 WebView 泄漏（onRelease 已经销毁过一次，这是二次保险）。
    // 两处都走 `holder.destroy()`，它销毁前置空实例 → **不会重复 destroy**。
    DisposableEffect(Unit) {
        onDispose {
            // v3：离开前尽力采集存档（post 到已销毁的 WebView 上是静默 no-op）。
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU2)
            holder.destroy()
        }
    }

    // ── 定时采集存档（v3 云同步）──────────────────────────────────────────────
    //
    // 与另三个游戏同一节奏（15 秒）：采集成本可忽略，间隔只为缩小
    // "退出时最后一批改动"的丢失窗口。本页素材没有防抖保存机制
    // （不象既有数独要 flushSave），因此离开时直接采即可。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(GAME_PUSH_INTERVAL_MS)
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU2)
        }
    }

    BackHandler { onBack() }

    Scaffold(
        // TopAppBar 会用它绘制状态栏 inset 的背景，必须与标题栏同源，
        // 否则状态栏那一带会出现「标题栏色 / 状态栏色」的分界。
        containerColor = chromeBackground,
        topBar = {
            // 条件渲染而不是恒渲染 + alpha(0f)：不显示时这 64dp 高度要**还给**内容区
            // （TopAppBar 固定 64dp，alpha 隐藏只是看不见、空间照占）。
            if (showTitleBar) {
                TopAppBar(
                    title = { Text("数独2") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    // 整行吃 `TopAppBar` 的默认配色：容器即 `colorScheme.surface`
                    // （= 本页 `chromeBackground`），前景取主题的 `onSurface`。
                    colors = TopAppBarDefaults.topAppBarColors()
                )
            }
        }
    ) { innerPadding ->
        // 内容区只有一层：网页本体（本页没有开屏层，因此 `AndroidView` 直接铺满）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(chromeBackground)
                // 把 Scaffold 给的 bottom inset 让回给内容：本 Box 铺到屏幕最底，
                // 底色因此延伸到手势条区域（与贪吃蛇 / 数独同一修法），
                // 身后的底色恒为 `chromeBackground`，与上面的图标明暗按主题配对。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 出题 / 渲染 / 持久化全是 JS，关掉只剩静态壳。
                        settings.javaScriptEnabled = true

                        // ⚠️ **硬性**：局面快照与用户偏好在 localStorage 里，
                        // 不开 DOM storage 会静默降级成"只存内存"（退出再进上次那局没了）。
                        settings.domStorageEnabled = true

                        // 加载的本身就是 file:///android_asset：放开文件访问
                        // （与贪吃蛇 / 番茄钟同口径；与既有数独相反，见常量 KDoc）。
                        settings.allowFileAccess = true

                        // 不读 content://，保持最小权限。
                        settings.allowContentAccess = false

                        // 首帧兜底色：页面 `:root` 的 `--paper` 浅色值（取值依据见
                        // [SUDOKU2_BACKGROUND_COLOR] 的 KDoc——页面自带深浅色，
                        // 原生预知不了它会切哪一档，取浅色是两者中不那么刺眼的一个）。
                        setBackgroundColor(SUDOKU2_BACKGROUND_COLOR)

                        // ── 视口（v1.1.78：显式声明，避免依赖 WebView 版本默认值）──
                        // 页面带 `<meta name="viewport" content="width=device-width">`，
                        // 本页布局不在页面里做宽屏/竖屏分支，打开 wide viewport 后
                        // 按设备宽度 1:1 渲染即可（与 SudokuScreen 同口径）。
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        // ── 存档同步（v3）──────────────────────────────────────────
                        //
                        // 桥先于 loadUrl 注册：assets/index.html 的 <head> 水合脚本
                        // 在页面解析期就调 `__CIM_GAME__.pendingState()`。
                        addJavascriptInterface(gameBridge, GameDataBridge.JS_NAME)

                        // 加载完成 → 首次采集。本页原来没有任何 WebViewClient
                        // （走系统默认），这里挂一个只实现 onPageFinished 的：
                        // 默认客户端本来就没有 onPageFinished 行为，挂上无副作用。
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU2)
                            }
                        }

                        // 只在**首次创建**时加载。`update` 里刻意不调 loadUrl：
                        // 任何一次重组都会走 update，在那里加载会让正在玩的一局重置。
                        loadUrl(SUDOKU2_PAGE_URL)

                        // 交给 holder 保管，供销毁路径统一处理。
                        holder.view = this
                    }
                },
                // 刻意留空，见 factory 内对 loadUrl 的说明。
                update = { },
                // 与上面的 DisposableEffect 是同一条销毁路径的两次保险；
                // 必须走 `holder.destroy()` 而不是直接 `it.destroy()`（见 holder KDoc）。
                //
                // v3：销毁前尽力采集一次存档（理由见 DisposableEffect 里那段）。
                onRelease = { web ->
                    web.pushGameSnapshot(GameDataBridge.GameKeys.SUDOKU2)
                    holder.destroy()
                }
            )
        }
    }
}
