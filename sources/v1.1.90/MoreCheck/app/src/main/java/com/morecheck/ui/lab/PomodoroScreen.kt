// 接线状态：`TabNavHost` 的 Pomodoro 路由仍按 `showTitleBar = gameTitleBar` 接线；
// 颜色与开屏两项**已不再有形参**（v1.1.74 需求 3：番茄时钟不归类为游戏），
// 本页形参默认值只保证"不传也能编译"。

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
 * 番茄时钟页要加载的本地页面地址。
 *
 * 指向 `app/src/main/assets/pomodoro/index.html`——Android 的 asset 目录在 WebView 里
 * 固定映射为 `file:///android_asset/` 前缀，因此**不能**写成相对路径。
 *
 * 与数独页（`SudokuScreen`）**不同**，本页可以走 `file://`：页面是**单文件自包含**的
 * （内联 CSS + 内联 JS，零外链、零网络请求，已逐项核实无 fetch / XMLHttpRequest /
 * WebSocket / location / window.open / import()），也没有数独那种
 * `new Blob(...)` + `URL.createObjectURL` 起的 Worker——因此不存在
 * "file:// 的 opaque origin 被 Chromium 拒绝创建 Blob Worker" 那个问题，
 * 不需要 `WebViewAssetLoader` 的 https 映射。这一点与贪吃蛇同构。
 */
private const val POMODORO_PAGE_URL = "file:///android_asset/pomodoro/index.html"

/**
 * 页面主底色（暖白）。
 *
 * 取 `0xFFFFF8F3`：与 `assets/pomodoro/index.html` 里 `:root` 的 `--bg` **同一个色值**。
 * 素材是**固定浅色**温柔风（`--bg #FFF8F3` / `--card #FFFFFF`），不跟随
 * `prefers-color-scheme`，因此这里也只写一份浅色。
 *
 * 用途：WebView 的 `setBackgroundColor`（WebView 默认白底，页面首帧绘制前露出的就是这一层）
 * 与页面 `--bg` 同值，任何一处露出都不会出现突兀的白边。
 * **不用于 chrome 底色**——标题栏 / 状态栏那一带取 [POMODORO_DARK_CHROME_COLOR]
 * （两档深色）或主题的 `surface`（SYSTEM 档），见 [PomodoroScreen] 的分档注释。
 *
 * ⚠️ 必须是**不透明**（前缀 `FF`）：半透明时会与窗口背景混合，深色主题下会透出深色。
 * ⚠️ 改页面 `--bg` 时必须同步改这里，否则兜底层与页面背景会有一圈可见色差。
 */
private const val POMODORO_BACKGROUND_COLOR = 0xFFFFF8F3.toInt()

/**
 * 本页 chrome 底色在**深色模式**下要盖住的那一层颜色。
 *
 * 背景：需求 3 起番茄时钟**不再有 chromeMode 形参**，整页 chrome 恒等于
 * `MaterialTheme.colorScheme.surface`。但 WebView 承载的网页是**固定暖白浅色**的
 * （`assets/pomodoro/index.html` 的 `--bg #FFF8F3`，全文没有 `prefers-color-scheme` 分支），
 * 而本应用允许切深色主题。于是深色主题下会出现：
 *
 * ```
 * ┌──────────────────────────┐  ← 主题 surface（深色）
 * │ 标题栏「番茄时钟」        │
 * ├──────────────────────────┤
 * │                          │  ← 暖白网页（永远浅色）
 * │      （番茄圆环）         │
 * └──────────────────────────┘
 * ```
 *
 * 即"深色标题栏 + 白色内容区"的两截观感。这**不是缺陷**：素材是固定浅色的，
 * 给它注入反色只会把这份已经跑通的视觉毁掉（同 [PomodoroScreen] KDoc「刻意不写」第 1 条）。
 * 本常量只用于**手势条那一带**的兜底：内容区 Box 铺到手势条下方，那一带若露出
 * `colorScheme.surface`，深色主题下就会在暖白页面底下露出一条**深色横条**——
 * 那才是真正难看的一处。铺一层与网页同值的暖白，手势条区域就与页面连成一片。
 *
 * 浅色主题下这个色值与 `surface` 本身接近，铺了也看不出差别；因此可以**无分支出地铺**。
 *
 * ⚠️ 改页面 `--bg` 时必须同步改这里（同 [POMODORO_BACKGROUND_COLOR] 的理由）。
 */
private const val POMODORO_NAVBAR_GUARD_COLOR = 0xFFFFF8F3.toInt()

/**
 * 单个 WebView 实例的持有者。
 *
 * 与 `SnakeScreen` / `SudokuScreen` / `SearchWebScreen` 里的同名类**刻意各写一份**，
 * 不抽公共类：本项目的既有纪律是"一屏一个自洽实现"（理由见 `SnakeScreen` 里同名类 KDoc）。
 *
 * `destroy()` 先把 `view` 置空再销毁，保证 `AndroidView.onRelease` 与
 * `DisposableEffect.onDispose` 两条路径谁先到都只销毁一次。
 */
private class PomodoroWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 番茄时钟」。
 *
 * ## 这个页面由谁负责什么（原生与网页的分工）
 *
 * **网页（`assets/pomodoro/index.html`）负责全部业务逻辑**：25 / 5 / 15 分钟三档计时、
 * 圆环进度（`stroke-dasharray` / `stroke-dashoffset`）、模式切换、今日统计与轮次点、
 * `localStorage` 持久化（键 `pomodoro-state-v1`，按 `new Date().toDateString()` 判当日）。
 * 这些一律**不在 Kotlin 侧重写**——重写等于把一份已经跑通的逻辑维护成两份。
 *
 * **原生（本页）只负责"容器"**：标题栏与返回、系统栏图标明暗、
 * WebView 的创建 / 配置 / 销毁，以及跟随应用主题的配色。原生不读网页里的计时状态
 * （唯一例外是 v3 云同步的存档桥：整包采集 localStorage 后交给同步引擎，
 * 不解析、不理解内容，见 [GameDataBridge]），也**不向网页注入任何游戏逻辑**
 * （本页没有需要落盘的防抖缓存，不像数独要 `flushSave()`）。
 *
 * ## ⚠️ 本页**不是游戏页**（v1.1.74 需求 3）
 *
 * 需求原文：「番茄时钟不要归类为游戏，不要跟随深色背景与开屏设置」。
 * 落实成三条具体规则：
 *
 * 1. **颜色跟随应用主题**：整页 chrome 恒等于 `MaterialTheme.colorScheme.surface`，
 *    系统栏图标按主题深浅取。**不再有 [GameChromeMode] 形参**，
 *    「游戏颜色模式」那个三档开关（跟随系统 / 深色经典 / 深色适配）**不再作用于本页**。
 *    理由：那两个深色档是按「网页内容恒深色」的两个游戏页写的——它们的正文是真的黑底，
 *    chrome 必须跟着黑才不割裂；番茄钟的网页是固定暖白浅色，给它套深色 chrome
 *    只会造出"深色标题栏 + 白色内容区"的两截观感，而它又没有黑底可依托。
 * 2. **不显示 Hello World 开屏**，详见下面「为什么本页没有开屏」一节。
 * 3. **标题栏开关仍沿用「游戏标题栏显示」那个键**——它是唯一的全局工具页标题栏开关，
 *    需求 3 没有要求番茄钟有自己的。若将来要拆，删 [showTitleBar] 形参即可。
 *
 * ## 为什么本页没有开屏（需求 3 的「不跟随开屏设置」怎么落地的）
 *
 * 「不跟随开屏设置」有两种自洽读法：①**恒不显示**；② 恒显示 1 秒但不受那个开关控制。
 * 本项目选了 ①，理由两条：
 *
 * - **语义上，番茄钟不是"启动一个东西"，而是"看一个一直开着的工具"。**
 *   贪吃蛇 / 数独进入时需要建引擎、读存档，玩家有心理预期"可能要等一下"；
 *   番茄钟的网页是固定 13KB 单文件、零加载成本，遮住它 1 秒纯粹是白等。
 * - **交互上，番茄钟常被反复进出**（看一眼还剩多少、暂停、继续）。
 *   需求 5 把两个游戏页的「Hello World」改成"进程内首次才显示"之后，
 *   本页若还每次都闪，用户会看到两套互相矛盾的规则。
 *
 * **与需求 5 的边界**：本页**不参与**那条"进程内首次显示"规则——它压根没有开屏层，
 * 因此也不需要"首次"标记。需求 5 的口径只覆盖贪吃蛇与数独两页。
 * 原来那四个开屏常量（`POMODORO_SPLASH_HOLD_MS` 等）与 `PomodoroSplashOverlay`
 * 已**一并删除**而不是留着：留着它们会让后来者以为"改这几个数就能配置番茄钟开屏"，
 * 而实际上没有调用点（项目纪律：不留死代码）。
 *
 * **这个决定可被推翻**：若将来确实要给番茄钟加开屏，改法是**加回来**而不是改判定——
 * 恢复开屏常量与 Composable、把需求 5 引入的进程级"已显示"标记在本页也调一次，
 * 然后把本节最后一段改掉。不存在"要同时改两处互相打架的规则"的问题。
 *
 * ## 为什么用 WebView，而不是用 Compose 重写
 *
 * 素材是用户提供的**自包含单文件**（内联 CSS + 内联 JS，零外链、零网络请求），
 * 重写要复刻圆环动画、统计口径与视觉风格，收益为零、跑偏风险高。
 * 与贪吃蛇 / 数独走同一条既有模式。
 *
 * ## WebView 配置（每项都写清理由）
 *
 * - `javaScriptEnabled = true`：计时、渲染、localStorage 全是 JS，关掉只剩一张静态壳。
 * - `domStorageEnabled = true`（**硬性**）：今日番茄数、累计专注分钟、当前轮次都存在
 *   `localStorage` 里。Android WebView **默认关闭** DOM storage，不开时页面的
 *   `try/catch` 会静默降级为"只存内存"——表现为退出再进"今日番茄归零"，
 *   用户看不到任何报错。
 * - `allowFileAccess = true`：本页加载的**本身就是** `file:///android_asset/...`。
 *   页面虽然单文件、没有同目录子资源，但与贪吃蛇保持同一口径（本页只加载这一份固定本地页面、
 *   不接受任何外部 URL、无跳转能力，放开的风险面约等于零）。
 *   ⚠️ 与 `SearchWebScreen` 的 `false` **相反且都必须如此**——按页面场景取值，别跨页照抄。
 * - `allowContentAccess = false`：本页不读任何 `content://`，能给的最小权限就给最小的。
 * - `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` **刻意不设置**，
 *   保持系统默认 false：页面没有任何跨源读取需求。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页，**刻意不做**「`canGoBack()` 就先回退网页历史」：
 * 本页只 `loadUrl` 一次，页面内没有任何导航（已核实无 location / history / window.open），
 * `canGoBack()` 永远为 false。
 *
 * ## 网页素材装进 WebView 时做的适配（只动版式，业务逻辑零改动）
 *
 * 已写进 `assets/pomodoro/index.html` 的 `<style>` 里（JS 块与源文件逐字节相同）：
 * 1. `html,body{height:100%}` → `html{height:100%}` + `body{min-height:100dvh;height:auto}`：
 *    原来 body 恒为视口高，内容一旦高于视口，`align-items:center` 会把溢出
 *    **对半分到上下**、上半部分 `top` 变负且滚不到（HANDOVER 5.15 第 3 条，贪吃蛇踩过）。
 * 2. 补 `100dvh`：兜住移动 WebView 里 `100vh` 与地址栏 / 系统栏的误差（旧内核回落 100vh）。
 * 3. 补 `-webkit-text-size-adjust:100%`：抑制 WebView 的字号自动放大，
 *    否则系统字体缩放档位一变，整套 px 版式会被放大后挤破。
 * 4. 补 `env(safe-area-inset-*)` 内边距 + `viewport-fit=cover`：本页 WebView 铺到手势条下方，
 *    底部不做内边距会让提示文案压在手势条上。
 * 5. 圆环与倒计时字号改 `min(248px, 46vh, 100%)` / `clamp(...)` 自适应：
 *    横屏与小屏（高度 300~400px）上固定 248px 会把控件挤出视口。
 *    圆环用 `viewBox` 等比缩放，JS 只操作基于 `2πr` 的 dasharray / dashoffset，
 *    与 CSS 尺寸无关，所以缩放不影响进度环的正确性。
 * 6. 矮屏（`max-height:560px`）收一档内外边距。
 *
 * ## 刻意"不写"的两件事
 *
 * 1. **不给 WebView 注入反色 / 强制深色**：页面是固定的暖白设计，注入反色只会把它毁掉。
 * 2. **不在 Compose 侧叠加任何手势**：页面自己处理按钮点击，本层只被动承载。
 *
 * @param onBack 退出本页（交给导航层 `popBackStack`）。
 * @param showTitleBar 是否显示顶部标题栏。关闭时这 64dp 高度要**还给**内容区，
 *   因此是条件渲染而不是 `alpha(0f)` 隐藏。
 *   ⚠️ 它仍取自「游戏标题栏显示」那个全局开关（`TabNavHost` 接线传入）——
 *   需求 3 只把**颜色**与**开屏**两件事移出游戏归类，没要求番茄钟有自己的标题栏开关。
 *   若将来要拆，删掉这个形参即可（页面会因为没有 TopAppBar 而内容顶到状态栏，
 *   那时需要给内容区补 top inset，别忘了）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PomodoroScreen(
    onBack: () -> Unit,
    showTitleBar: Boolean = true
) {
    val holder = remember { PomodoroWebViewHolder() }

    // ── 存档同步桥（v3 云同步）────────────────────────────────────────────────
    //
    // 番茄钟的全部状态（今日番茄数 / 累计分钟 / 当前轮次）在一个 localStorage 键
    // `pomodoro-state-v1` 里（见 `GameDataBridge.GameKeys.POMODORO`）。
    // 水合脚本已随 assets/index.html 的 <head> 注入；采集走下方 factory 里的
    // onPageFinished 与定时循环。原生只整包搬运，不解析 JSON 内容。
    val gameBridge = rememberGameBridge(GameDataBridge.GameKeys.POMODORO)

    // ── 配色：恒跟随应用主题（v1.1.74 需求 3，番茄时钟不再有 chromeMode 档位）──
    //
    // 曾经的三个分支（按 GameChromeMode 分）已全部删除，理由见本页 KDoc
    // 「本页不是游戏页」第 1 条：那两个深色档是按"网页内容恒深色"的两个游戏页设计的，
    // 番茄钟的网页是固定暖白浅色，套深色 chrome 只会造出"深色标题栏 + 白色内容区"。
    //
    // 现在整页 chrome 就一个值：主题的 `colorScheme.surface`。这正是原
    // `GameChromeMode.SYSTEM` 档的语义——保留的是"跟随主题"这一档，丢掉另外两档。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 整页 chrome 底色：Scaffold 容器 + 内容区兜底两处必须同值，
    // 否则状态栏那一带会出现「标题栏色 / 状态栏色」的分界。
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // ── 状态栏 / 导航栏图标明暗 ─────────────────────────────────────────────
    //
    // `isAppearanceLightStatusBars = true` 表示栏内用**深色**图标（字段名指的是
    // "栏的 appearance 是否 light"，语义容易记反，见 `ScanScreen` 里同名字段的注释）。
    //
    // ⚠️ 与贪吃蛇页**不同**：那里内容恒深，所以状态栏与导航栏**都**设浅色图标；
    // 本页上下两块底色不同，两栏必须**分别**判定：
    // - 状态栏压在 chrome（= `colorScheme.surface`）上 → **完全跟随主题**：
    //   浅色主题 → 深色图标（true），深色主题 → 浅色图标（false）。
    // - 导航栏 / 手势条压在**网页内容**上：本 Box 用 `consumeWindowInsets` 铺到屏幕最底，
    //   WebView 也是 `fillMaxSize`，所以手势条那一带露出的**永远是暖白页面**
    //   （不是上面的 chrome 色，见 [POMODORO_NAVBAR_GUARD_COLOR] 的说明）。
    //   因此导航栏**恒用深色**图标（true）——哪怕当前是深色主题，因为身 behind 它的是
    //   一条浅色带；若按主题取成浅色图标，就是贪吃蛇页踩过的"白底 + 白图标"反过来的一种。
    //
    // 离开时把进入前的取值**原样写回**，否则会把本页的取值泄漏给之后返回的实验室页。
    val lightStatusBarIcons: Boolean = !darkTheme

    // 手势条身后的内容恒为暖白（网页固定浅色）→ 恒用深色图标（理由见上）。
    // 这条**不再**随 chromeMode 变化：需求 3 删掉档位后，本页与主题深浅无关，
    // 只有"内容恒浅"这一条依据。
    val lightNavBarIcons = true

    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, lightStatusBarIcons, lightNavBarIcons) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            // 拿不到窗口（理论上不会发生在 Compose 视图树上）时什么都不做，
            // 但 onDispose 必须存在：DisposableEffect 的 lambda 必须有一个收尾。
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = lightStatusBarIcons
            controller.isAppearanceLightNavigationBars = lightNavBarIcons
            onDispose {
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
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.POMODORO)
            holder.destroy()
        }
    }

    // ── 定时采集存档（v3 云同步）──────────────────────────────────────────────
    //
    // 番茄钟的状态随计时推进持续变化（轮次点、今日统计），15 秒采集一次保证
    // 最坏只丢 15 秒内的进度；onPageFinished 与离开时的两次采集兜住两端。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(GAME_PUSH_INTERVAL_MS)
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.POMODORO)
        }
    }

    BackHandler { onBack() }

    Scaffold(
        // 整页容器色：TopAppBar 会用它绘制状态栏 inset 的背景，这里必须与标题栏同源，
        // 否则状态栏那一带会出现「标题栏色 / 状态栏色」的分界。
        containerColor = chromeBackground,
        topBar = {
            // 条件渲染而不是恒渲染 + alpha(0f)：不显示时这 64dp 高度要**还给**内容区
            // （TopAppBar 固定 64dp，alpha 隐藏只是看不见、空间照占）。
            if (showTitleBar) {
                TopAppBar(
                    title = { Text("番茄时钟") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    // 整行吃 `TopAppBar` 的默认配色：容器即 `colorScheme.surface`（= 本页
                    // `chromeBackground`），前景取主题的 `onSurface`。
                    // 这就是「跟随系统」——需求 3 之后本页**只有**这一种配色，
                    // 因此不再需要按档位分支显式指定容器色与前景色。
                    colors = TopAppBarDefaults.topAppBarColors()
                )
            }
        }
    ) { innerPadding ->
        // 内容区只有一层：网页本体（v1.1.74 需求 3 删掉了上层开屏层，
        // 因此这里不再是"两层"，`AndroidView` 可以直接铺满）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 铺一层**与网页同值**的暖白（不是 `chromeBackground`）：`padding(innerPadding)`
                // 之后底部手势条那一带露出的是窗口背景，而身后真正压着的是暖白网页，
                // 深色主题下铺 `colorScheme.surface` 会露出一条深色横条。
                // 理由与取值依据见 [POMODORO_NAVBAR_GUARD_COLOR]。
                .background(Color(POMODORO_NAVBAR_GUARD_COLOR))
                // 把 Scaffold 给的 bottom inset 让回给内容：本 Box 铺到屏幕最底，
                // 底色因此延伸到手势条区域（与贪吃蛇 / 数独同一修法）。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // ── 底层：网页本体 ───────────────────────────────────────────────
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 纯 JS 驱动的计时器 / 渲染 / 持久化，关掉只剩静态壳。
                        settings.javaScriptEnabled = true

                        // ⚠️ **硬性**：今日番茄数 / 累计分钟 / 轮次都在 localStorage，
                        // 不开 DOM storage 会静默降级成"只存内存"（退出再进统计归零）。
                        settings.domStorageEnabled = true

                        // 加载的本身就是 file:///android_asset：放开文件访问（与贪吃蛇同口径）。
                        settings.allowFileAccess = true

                        // 不读 content://，保持最小权限。
                        settings.allowContentAccess = false

                        // 与页面 `--bg` 同值的暖白兜底（WebView 默认白底，
                        // 页面首帧绘制完成前会露出这一层）。
                        // ⚠️ 这里**刻意不跟随主题**：网页是固定浅色内容，
                        // 兜底色只在"页面没画完"时露出，跟随主题会在深色主题下
                        // 露出一圈深色边，与暖白页面形成长期可见的色差。
                        setBackgroundColor(POMODORO_BACKGROUND_COLOR)

                        // ── 视口（v1.1.78：跟随 SudokuScreen 的实测结论）──────────
                        // 显式声明视口策略，避免依赖 WebView 版本默认值。
                        // 与 SudokuScreen 不同的是：本页布局不在页面里做宽屏/竖屏分支，
                        // 打开 wide viewport 后按设备宽度 1:1 渲染即可（实测正常）。
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        // ── 存档同步（v3）──────────────────────────────────────────
                        //
                        // 桥先于 loadUrl 注册：assets/index.html 的 <head> 水合脚本
                        // 在页面解析期就调 `__CIM_GAME__.pendingState()`。
                        addJavascriptInterface(gameBridge, GameDataBridge.JS_NAME)

                        // 加载完成 → 首次采集。本页原来没有任何 WebViewClient，
                        // 挂一个只实现 onPageFinished 的（默认客户端无该行为，无副作用）。
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.pushGameSnapshot(GameDataBridge.GameKeys.POMODORO)
                            }
                        }

                        // 只在**首次创建**时加载。`update` 里刻意不调 loadUrl：
                        // 任何一次重组都会走 update，在那里加载会把正在计的这轮重置。
                        // `factory` 是本页组合的第一时刻，因此页面从进入那一帧起
                        // 就在加载与渲染——需求 3 删掉开屏层后**没有任何东西遮住它**，
                        // 用户点进来的第一眼看到的就是页面本身。
                        loadUrl(POMODORO_PAGE_URL)

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
                    web.pushGameSnapshot(GameDataBridge.GameKeys.POMODORO)
                    holder.destroy()
                }
            )
        }
    }
}
