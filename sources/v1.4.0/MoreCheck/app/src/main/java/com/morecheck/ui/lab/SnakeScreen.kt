package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.webkit.WebView
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
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.morecheck.data.game.GameDataBridge
import com.morecheck.domain.GameSplashFirstRun
import com.morecheck.ui.common.SplashTextLabel
import com.morecheck.ui.common.rememberSplashTextStyle
import com.morecheck.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 贪吃蛇小游戏要加载的本地页面地址。
 *
 * 指向 `app/src/main/assets/snake/index.html`——Android 的 asset 目录在 WebView 里
 * 固定映射为 `file:///android_asset/` 前缀，因此**不能**写成相对路径或 `file:///android_res/`。
 *
 * 用 `const val` 而不是把字面量散在 `loadUrl` 调用处：将来若把页面挪到别的目录
 * （例如 `snake/` 改名为 `games/snake/`），只需要改这一处。
 */
private const val SNAKE_PAGE_URL = "file:///android_asset/snake/index.html"

/**
 * 「退出游戏」桥接（本轮需求 3）。
 *
 * 需求原文：「不显示标题时，贪吃蛇中“重新开始”按钮改为“退出游戏”，功能与标题栏
 * 返回键一样」。标题栏隐藏后页面里没有返回键，退出动作只能由网页发起：
 * `game.js` 在宿主注册了退出回调时把底部「重新开始」按钮整体替换成红色的
 * 「退出游戏」（游戏中置灰），点击时调用 `window.__MORECHECK__.setExitHandler`
 * 注册进来的回调。
 *
 * 桥的注册时机在 WebView 的 [WebViewClient.onPageFinished]（页面里的 game.js
 * 已经执行完 `init`，`window.__MORECHECK__` 已存在）。注册与否由组合期的
 * [gameTitleBar] 决定：**只有标题栏被隐藏时**才注册——标题栏还在时用户有返回键，
 * 底部按钮保留原「重新开始」语义，网页侧检测不到宿主回调就会自动维持原状。
 *
 * ⚠️ `@JavascriptInterface` 方法运行在 WebView 的后台线程，不能直接碰 UI——
 * 这里通过 `post { onBack() }` 把调用转回主线程（Activity.post 是线程安全的）。
 */
private class SnakeExitBridge(private val onExit: () -> Unit) {
    @android.webkit.JavascriptInterface
    fun exitGame() {
        onExit()
    }
}

/** 注册退出桥的 JS 片段：把桥的 `exitGame()` 包成 game.js 期望的回调形态。 */
private const val SNAKE_REGISTER_EXIT_JS =
    "javascript:window.__MORECHECK__.setExitHandler(function(){ " +
        "window.__SNAKE_EXIT__.exitGame(); });"

/**
 * WebView 自身的底色（**兜底色**，职责与本页开屏层的底色**完全不同**，见 [SnakeScreen] 的说明）。
 *
 * 取 `0xFF080B14`：与 `assets/snake/style.css` 的 `--bg-0` **同一个色值**——
 * 素材里那套深色定义在 CSS 变量中，这里只能写死一份，因此**改 CSS 时必须同步改这里**，
 * 否则兜底层与页面背景会有一圈可见色差。
 *
 * ⚠️ 必须是**不透明**（前缀 `FF`）：半透明时 WebView 会把底色与窗口背景混合，
 * 在浅色主题下仍会透出浅色。
 *
 * ⚠️ 它**刻意不跟随主题**（与本页开屏层相反——开屏层取 `colorScheme.background`）：
 * 这一层管的是"网页自己没画出来 / 没盖住的地方露出什么颜色"，而网页游戏是**永远深色**
 * 的固定内容，用深底则任何一处露出来都与画面同色；若改成跟随主题，浅色模式下就会露出
 * 一圈浅色边，而且这个缺陷是**长期可见**的（不是开屏那一秒的事）。
 * 完整理由见 [SnakeScreen] 里「两层底色的分工」那一节。
 */
private const val SNAKE_BACKGROUND_COLOR = 0xFF080B14.toInt()

/**
 * 本页开屏层的**停留时长**（含它自己的淡入时间）。
 *
 * 需求原文：「加上开屏动画显示 'Hello World' 1 秒，此时后端仍在继续加载」，
 * 取 1000ms 整。
 *
 * 注意保持 **Long**：它喂给 `kotlinx.coroutines.delay`，那个函数收的是 Long。
 * （`tween(durationMillis)` 才收 Int，与 `MoreCheckApp` 里 `SPLASH_DURATION_MS`
 * 与 `SPLASH_FADE_MS` 分成两种类型的理由完全相同。）
 */
private const val SNAKE_SPLASH_HOLD_MS = 1000L

/**
 * 本页开屏层的**淡出时长**。
 *
 * 需求只规定了"1 秒后消失"，没规定怎么消失。硬切（这一帧 Hello World、
 * 下一帧 WebView）会非常跳，因此给它一段与 [SNAKE_SPLASH_HOLD_MS] 分离的淡出。
 * 取 220ms：比全局开屏的 `SPLASH_FADE_MS`（320ms）略短——贪吃蛇整页只停留 1 秒，
 * 淡出占太久会让"Hello World"的实际可读时间被吃掉一截。
 */
private const val SNAKE_SPLASH_FADE_MS = 220

/**
 * 本页开屏层文字的**入场时长**（淡入 + 上浮）。
 *
 * 照 `SplashScreen` / `MomentsScreen` 里那段 `Animatable` 动画的 420ms：
 * 同一个 `Hello World`、同一套上浮手感，三处（应用开屏、朋友圈、本页）观感才一致，
 * 而不是本页另发明一种节奏。
 */
private const val SNAKE_SPLASH_TEXT_ENTER_MS = 420

/**
 * 本页开屏层文字的**上浮距离**（像素）。
 *
 * 同样照 `SplashScreen` / `MomentsScreen` 的 `+24f`：三处必须同值，
 * 否则用户会觉得"贪吃蛇的开屏和别处不太一样"，而这里没有任何理由不一样。
 */
private const val SNAKE_SPLASH_TEXT_LIFT_PX = 24f

/**
 * 单个 WebView 实例的持有者。
 *
 * 与 `ui/web/SearchWebScreen.kt` 里的同名类**刻意各写一份**，不抽公共类：
 * 本项目的既有纪律是"一屏一个自洽实现"。抽公共类要让两个页面共享一个位于第三处的
 * 实现细节（谁负责 destroy、能否置空、何时置空），改动半径反而变大——
 * 一处为某个页面放宽，另一处就跟着失去那条约束。
 *
 * ## 为什么**不**用 `remember { mutableStateOf<WebView?>(null) }`
 *
 * 沿 `SearchWebScreen` 的三条理由：
 * 1. 它不需要被**观察**——只在「返回 / 离开」这类事件回调里读取，不是组合期读取，
 *    用普通字段就不会因为一次赋值平白多触发一轮重组；
 * 2. `AndroidView.onRelease` 与 `DisposableEffect.onDispose` 都会在离开本页时触发，
 *    这里先把实例置空再 `destroy()`，保证同一实例只被销毁一次
 *    （`WebView.destroy()` 重复调用虽然不崩，但会让"谁负责销毁"变得含糊）；
 * 3. 置空之后 `view` 恒为 null，任何走到销毁路径的代码都不会再用到一个已 destroy 的 WebView。
 */
private class SnakeWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 贪吃蛇页的**本页专属**开屏层：整屏居中一个 `Hello World`，淡入 + 上浮，
 * 由外层 [AnimatedVisibility] 负责 [SNAKE_SPLASH_FADE_MS] 的淡入 / 淡出。
 *
 * ## 为什么在 `SnakeScreen` 里另写一份，而不直接调用 `ui/nav/SplashScreen.kt`
 *
 * `SplashScreen` 是 `public` 的（`MoreCheckApp` 的 `SplashGate` 在用），但它**自己带着**
 * 一层局部主题 `MoreCheckSplashTheme(accent = LocalAccentColor.current, dark = <按背景明度判断>)`。
 * 本页的开屏层是**叠在本页既有主题里**的一层——它必须直接用**外层 `MoreCheckTheme`**
 * 已经算好的配色（那个配色同时包含了用户的主题色选择与深浅色设置），
 * 而不是再按"背景明度"重新推一套。若直接调用 `SplashScreen`，深浅的判断会绕一道
 * 局部主题，等于把同一件事算两遍，且两遍的 `primary` 未必同源。
 *
 * 所以这里**刻意不调用**它，而是照它的写法写一个本页私有版本，只保留视觉部分：
 * - 同样的文案、同样的字号 / 字重（`displaySmall` + `Bold`）、同样的淡入 + 上浮曲线
 *   （[SNAKE_SPLASH_TEXT_ENTER_MS]、[SNAKE_SPLASH_TEXT_LIFT_PX]），
 *   三处（应用开屏、朋友圈、本页）观感一致；
 * - **不套任何局部主题**，直接读外层 [MaterialTheme] 的语义色（见下）。
 *
 * ## 底色为什么由调用方传入，而不是在本函数里自己算
 *
 * 需求 3 给游戏页加了**三档 chrome 模式**：`SYSTEM` 档下开屏层要跟随主题
 * （`colorScheme.background`），两档深色模式要固定深底（与页面 `--bg-0` 同值）。
 * 这个判断的输入（`chromeMode`）本来就在 [SnakeScreen] 里，而且同一个判断结果
 * 还要用于 Scaffold / TopAppBar / 内容区底色——**一处算、四处用**，才不会出现
 * "开屏层是深底、内容区却是浅底"这种半档状态。因此这里只收一个已经算好的
 * [backdrop] 颜色参数，不再自己读 `MaterialTheme`。
 *
 * ## 文字色取 `colorScheme.primary`、为什么三档都不改
 *
 * 它就是用户在「我的 → 设置 → 外观 → 主题色」里选的那个色：
 * `MoreCheckTheme` 用 `accent.seed` 覆盖 `primary`（深色模式下同样是 `seed` 本身，
 * 见 `Theme.kt` 的 `withAccent`），所以**深浅两套取值都由主题色推导**，
 * 换主题色或切深浅色，这个字都自动跟着变。
 *
 * ⚠️ 与 `SplashScreen` / `MomentsScreen` 那两处**故意不同**：它们写的是
 * `color = if (dark) Color.White else MaterialTheme.colorScheme.primary`——深色模式改用白字，
 * 因为它们的底在深色模式下是 `NeutralDark.background`（`#111111`），
 * 而主题色（比如默认的 `#07C160`）压在 `#111111` 上偏暗、不够醒目。
 * 本页的需求明确指定"文字颜色跟随主题色"，因此**所有档位都取** `primary`，
 * 不做那个 `if (dark)` 分支切换。代价是深色档下这行字比全局开屏略暗一点，
 * 这是需求指定的取舍，不是遗漏。
 *
 * ## 为什么不新增颜色常量
 *
 * 纪律：不新增颜色常量。本层**一个颜色字面量都没有**——底色由调用方按档位算，
 * 文字色在组合期从 `MaterialTheme.colorScheme` 读，因此：改主题色、切深浅色、
 * 用户在设置里换色，这一层全部自动跟随，不需要任何同步维护。
 *
 * @param backdrop 开屏层整屏底色，由 [SnakeScreen] 按 `chromeMode` 算好传入。
 */
@Composable
private fun SnakeSplashOverlay(
    backdrop: Color,
    modifier: Modifier = Modifier
) {
    // 淡入 + 上浮：0 → 1 的进度同时驱动透明度与位移（与 SplashScreen / MomentsScreen 同一手法）。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = SNAKE_SPLASH_TEXT_ENTER_MS)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 开屏底色**按 chrome 档位**：SYSTEM 档跟随主题、深色两档固定深底（见上方 KDoc）。
            //
            // ⚠️ 注意它与 WebView 兜底色（`setBackgroundColor(SNAKE_BACKGROUND_COLOR)`）
            // 是**两个不同职责**的颜色：本层只存在 1 秒，
            // 而兜底色是"网页没画到的地方长期露出的颜色"，**永远**深色（见 [SnakeScreen]
            // 里「两层底色的分工」一节）。SYSTEM 档下两者不同值是有意的。
            .background(backdrop),
        contentAlignment = Alignment.Center
    ) {
        // 2026-10-27：改读「开屏显示文字」设置（默认仍是 hello world，
        // 字号/字体/单多行由用户在开发者选项里调）。文字色仍跟随主题色。
        SplashTextLabel(
            style = rememberSplashTextStyle(),
            // 文字色**跟随主题色**：`primary` 就是用户选的那个强调色，
            // 深浅两种模式各有对应取值（由 `Theme.kt` 的 `withAccent` 推导）。
            // 为什么两种模式都取 primary、不像 SplashScreen 那样深色模式改白字，见本函数 KDoc。
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * SNAKE_SPLASH_TEXT_LIFT_PX
            }
        )
    }
}

/**
 * 「实验室 → 贪吃蛇」。
 *
 * 需求：把一份自包含的网页小游戏（Canvas 绘制棋盘、localStorage 存最高分、
 * 触屏滑动 + 屏幕方向键操作、三档难度）作为实验室里的一个入口。
 *
 * ## 为什么用 WebView，而不是用 Compose 重写一遍
 *
 * 素材（`index.html` / `game.js` / `style.css`）是**自包含**的：
 * `game.js` 里没有 `fetch` / `XMLHttpRequest` / 动态 `import` / `WebSocket`，
 * 也没有 `location` 跳转与 `window.open`（已逐项 grep 核实），
 * 所以整包放进 `assets` 后本地加载即可，**运行期不产生任何网络请求**——
 * 与本项目「除天气外不外发请求」的口径一致。
 * 重写成 Compose 要重新实现全部游戏逻辑与手感，收益为零、跑偏风险却很高。
 *
 * ## 版式
 *
 * 照 `NotebookScreen` / `AgentChatScreen` 那套二级页：`Scaffold` + `TopAppBar` + 左上返回箭头。
 * 标题栏与整页 chrome 的颜色由形参 [chromeMode] 的三档决定（默认深色经典；
 * 完整对照见下方「三档 chrome 模式」一节）。**游戏画布自身**永远是固定的深色风格
 * （页面 `theme-color` 就是 `#0d1220`），所以这里**不给 WebView 注入任何反色 / 强制深色**：
 * 素材本来就是深色设计，注入反色只会把它毁掉。SYSTEM 档下"浅色标题栏 + 深色画布"
 * 会有明显分界，那是这一档的语义（用户明确选了"跟随系统"），不是缺陷。
 *
 * ## ⚠️ 网页素材装进 WebView 时踩到的两个坑（已在 assets 里修好，别改回去）
 *
 * 素材在**桌面浏览器里完全正常**，但装进 WebView 后是白板 / 内容看不见。
 * 两处都在 `assets/snake/style.css`，都是"浏览器无所谓、WebView 致命"的写法：
 *
 * 1. **`background-attachment: fixed`**（原 body 背景上带着它）
 *    → 背景不随文档高度铺开，只有顶部一小条有渐变、下面全露出 WebView 的白色底。
 *    修法：`html` 另铺一层纯色底兜底，body 去掉该属性。
 * 2. **flex 垂直居中 + 内容高于视口**（原 `body { height: 100%; align-items: center }`）
 *    → 内容溢出后居中会把溢出**对半分到上下**，上半部分 `top` 变成负值
 *    （实测诊断：`.app` top = **-360**，棋盘与「准备开始」遮罩层几乎全在屏幕外，
 *    而且这段溢出**滚不到**）。
 *    修法：`min-height` 取代 `height`、`align-items: flex-start` + `overflow-y: auto`。
 *
 * 诊断这两处的方法（下次遇到同类问题可复用）：临时给 WebView 挂一个
 * `WebChromeClient.onConsoleMessage` 打印 JS 控制台，并在 `onPageFinished` 里用
 * `evaluateJavascript` 把关键元素的 `getBoundingClientRect()`（宽高 + top）打出来——
 * **top 是负数**就是这类"内容跑到视口外"的直接证据。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页，**刻意不做**「`canGoBack()` 就先回退网页历史」那一套
 * （`SearchWebScreen` 有，因为它是一个真正的浏览器）。判断依据：
 * 本页是**单页应用**——只 `loadUrl` 一次、页面内没有任何导航与跳转
 * （已核实 `game.js` 没有 `location` / `history` / `window.open`），
 * 因此 `canGoBack()` 永远为 false，写出来也只是一段**永远不会执行**的死分支；
 * 不写它，反而不会让人误以为"这个游戏里有多个网页页面"。
 *
 * ## 两处刻意的"不写"
 *
 * 1. **不在 Compose 侧叠加任何手势**：游戏页面自己在棋盘上监听 `touchstart` / `touchend`
 *    实现滑动，并自带屏幕方向键按钮。若在 Compose 这一侧再挂 `pointerInput` /
 *    `draggable` / `nestedScroll`，两层手势检测器会**竞争**同一串指针事件，
 *    表现为"有时滑动没反应、有时方向反了"，且难以稳定复现。本页的 WebView 只被动承载。
 * 2. **不做游戏状态恢复，每次进入都是新一局**：离开本页时 `AndroidView.onRelease` 会
 *    `destroy()` WebView，下次进入由 `factory` 新建实例并重新加载页面，于是分数归零、
 *    棋盘重开。这是**有意接受**的行为——WebView 必须销毁（不销毁就是泄漏 Activity Context），
 *    而在"销毁"的前提下恢复游戏中途状态需要把整局状态搬到 JS 之外，
 *    成本远超一个小游戏的价值。唯一持久化的是**最高分**：它存在 `localStorage` 里，
 *    因此不随页面重载丢失。
 *
 * ## WebView 用的是哪种 Context（会不会泄漏 Activity）
 *
 * 用 `AndroidView` 的 `factory` 参数给出的 `ctx`，也就是**本 Activity 的 Context**，
 * **不是** `applicationContext`——这一点与 `SearchWebScreen` 完全一致（已读代码确认它也是
 * `WebView(ctx)`）。答案是：本项目**有意**用 Activity Context，因为要把 WebView 挂进
 * 当前视图树就必须用 Activity 的 Context（用 applicationContext 创建 WebView 在部分 ROM 上
 * 会因缺少主题/窗口信息而出错）。持有 Activity 的代价由**销毁**来偿：
 * `onRelease` + `DisposableEffect` 两条路径都会 `destroy()`，销毁后 WebView 与它持有的
 * Context、渲染进程都会释放，因此**页面离开后不残留**。
 * 更严格的做法（在 `onDestroy` 里 `removeAllViews()`）本页不做：那需要在 Activity 里动手，
 * 而本任务不改 Activity；且 `destroy()` 已经覆盖了"退出本页即释放"这一主要场景。
 *
 * ## 本页的两层结构（需求：「贪吃蛇进入界面做成两层，底部是游戏本体，顶层是 helloworld
 * 的文字 1 秒全屏显示，不影响游戏的启动」）
 *
 * 内容区是一个 `Box`，里面**依次**放两样东西：
 * 1. **底层**：`AndroidView` 承载的 WebView（游戏本体）——它在 `factory` 里、进入本页的
 *    第一时刻就 `loadUrl` 起跑；
 * 2. **顶层**：[SnakeSplashOverlay]（整屏 `Hello World`）——[SNAKE_SPLASH_HOLD_MS] 之后淡出。
 *
 * **声明顺序决定了叠放顺序**：Compose 的 `Box` 按声明顺序绘制，后声明的画在上面，
 * 所以顶层必须写在 `AndroidView` **之后**（见下方代码注释）。这个顺序同时保证了两层
 * **真的同时存在**——底层不是等顶层消失后才组合的，两者在同一帧一起挂载、一起跑。
 *
 * **加载与开屏是并行的，不是串行的**：`loadUrl` 仍然在 `AndroidView` 的 `factory` 里、
 * 在 WebView 实例创建后的第一时刻执行（见下方 factory 内的注释）。开屏层只是
 * **盖在上面**的一层 `Box`，它出现与否不影响 WebView 的加载进度——这正是需求里
 * "不影响游戏的启动" / "此时后端仍在继续加载"的含义，也**不是**"等 1 秒再开始加载"
 * （那样反而白等 1 秒，加载被推迟）。
 *
 * 为什么用 `LaunchedEffect` 定时而不是 `WebViewClient.onPageFinished`：
 * 需求规定的是"1 秒"这个**固定时长**，与页面加载快慢无关。若挂到 `onPageFinished` 上，
 * 加载慢时开屏会一直挂着（用户不知道还要等多久），与需求不符；本地 asset 页面本来就
 * 毫秒级加载完成，`onPageFinished` 也几乎总是早于 1 秒到点，用它反而更慢。
 *
 * ## ⚠️ 开屏现在**不是每次都播**（v1.1.74 需求 5）
 *
 * 上面描述的是「这一层怎么播」；**要不要播**由 [GameSplashFirstRun] 决定：
 * 本次应用进程生命周期内只有第一次进入游戏页才播，之后（包括应用切后台再回来）
 * 都直接看到游戏本体。番茄时钟不参与这条规则（见 `PomodoroScreen`）。
 *
 * ## 三档 chrome 模式（需求 3）：标题栏 / 系统栏 / 开屏底色怎么变
 *
 * 形参 [chromeMode] 决定本页 chrome 的一整套颜色，档位语义见 [GameChromeMode]。
 * 本页把它展开成**五处**派生值（容器色、标题栏色、标题/图标色、开屏层底色、
 * 系统栏图标明暗），全部在组合期从同一份 `when` 算出来——**一处算、多处用**。
 * 为什么不让各处的 `when` 各写一遍：那样极易漏掉一处，出现"标题栏跟随主题、
 * 系统栏图标却还是浅色"这种半档状态，正是本页历史上踩过的白底白图标。
 *
 * - **DARK_CLASSIC（默认）**：与改造后完全一致。`Scaffold` 容器、`TopAppBar`、
 *   内容区兜底一律取 [SNAKE_BACKGROUND_COLOR]（= 页面 `--bg-0`），系统栏图标一律浅色。
 *
 * - **SYSTEM**：`Scaffold` 容器与 `TopAppBar` 取 `colorScheme.surface`，
 *   标题 / 返回箭头取 `onSurface`，系统栏图标 `isAppearanceLightStatusBars =
 *   isAppearanceLightNavigationBars = !深色主题`。
 *   ⚠️ 这一档**额外把底部 inset 让回去**（`padding(innerPadding)` 而不是
 *   `consumeWindowInsets` + 只留 top）：内容区是固定深色的网页，而这一档要求
 *   系统栏图标跟随主题（浅色主题下是**深色**图标）。若仍让深色游戏区铺到手势条下面，
 *   手势条那一带就是「深底 + 深图标」——把 5.17 那条"图标必须与背景配对"换个方向再踩一次。
 *   让回 inset 后，两条系统栏所占据的角落由 `Scaffold` 的**浅色容器**绘制，
 *   与深色图标配对；代价是游戏画面比另两档矮一条手势条的高度，这是这一档的语义本身。
 *
 * - **DARK_ADAPTIVE**：容器与内容层同 DARK_CLASSIC（固定深色），但 `TopAppBar` 的
 *   容器色取 `Color.Transparent` —— 标题栏不再画自己那块底色，与游戏区连成一片，
 *   只有标题文字与返回箭头浮在上沿。系统栏图标仍取浅色。
 *   ⚠️ 透明标题栏在画状态栏 inset 时会**露出 `Scaffold` 的容器色**，所以这一档
 *   容器色**必须**是不透明深色（不能跟着透明）——否则状态栏那一带会露出窗口背景，
 *   浅色主题下又是「浅底 + 浅色图标」。这是 5.17 那条在"透明"方向上的同一约束。
 *
 * ## 两层底色的分工：开屏层随档位、WebView 兜底色永远深色
 *
 * 本页有两个"底色"，职责不同，**可能不同值**：
 *
 * - **开屏层底色**：SYSTEM 档取 `colorScheme.background`（跟随主题），
 *   两档深色模式取 [SNAKE_BACKGROUND_COLOR]。它是"进入本页时用户第一眼看到的那一屏底"；
 * - **WebView 兜底色** = [SNAKE_BACKGROUND_COLOR]（固定深色，**不跟随主题也不跟随档位**）。
 *   它是"网页自己没画出来 / 没盖住的地方长期露出的颜色"，而网页游戏是**永远深色**的
 *   固定内容，用深底则任何一处露出来都与画面同色。若跟随主题，浅色主题下就会露出
 *   一圈**浅色边**——那是**长期可见**的缺陷（不局限于开屏那一秒）。
 *
 * **取舍**：SYSTEM 档 + 浅色主题下，开屏层（浅底 `#EDEDED`）淡出时会露出 WebView 的
 * 深色兜底（`#080B14`），淡出的 220ms 里能看到一次由浅到深的过渡。
 * 接受它的理由：本地 asset 页面是毫秒级加载的，1 秒到点时页面早已绘制完成、画面铺满，
 * 兜底色根本没机会露出来；只有"1 秒内没画完"这种异常情况才会看到。反过来把兜底色
 * 改成跟随主题会换来一个长期可见的浅色边，代价大得多。
 *
 * @param onBack 退出本页。
 *
 *   🔄 v1.1.74 需求 6 起本页住在**独立 Activity**（`SnakeHostActivity`，见
 *   `GameActivityHost.kt`）里，不再是 `TabNavHost` 的一个二级目的地。
 *   调用方传的是 `finish()`，因此「返回」= 结束本页所在的 task；
 *   原来那条「快捷方式直达时 finish、否则 popBackStack」的分支随之消失——
 *   在独立 Activity 里没有 NavHost 可 pop，两种入口下 `finish()` 都是正确语义。
 *   本页**不知道**自己被谁组合（NavHost / 独立 Activity / 将来别的宿主），
 *   它只负责把"退出"翻译成一次回调，这也是本页至今一个字都没改的原因。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnakeScreen(
    onBack: () -> Unit,
    /**
     * 是否显示顶部标题栏（需求 2：与数独共用一个「游戏标题栏显示」开关）。
     *
     * 默认 `true` = 与改造前完全一致（不传即旧行为，调用方未接线也不会编译失败）。
     * 传**生效值**（总开关 && 用户开关，回落规则见
     * `DeveloperOptionsRepository.gameTitleBarEnabled`），不是原始值。
     */
    gameTitleBar: Boolean = true,
    /**
     * 是否显示本页开屏层（Hello World，需求 2：贪吃蛇与数独可分别设置）。
     *
     * 默认 `true` = 与改造前完全一致。传**生效值**
     * （`DeveloperOptionsRepository.gameSplashSnakeEnabled`，总开关关时回落 true），
     * 不是原始值。
     *
     * ⚠️ 关闭时**连 1 秒计时都不启动**（见下方 `LaunchedEffect` 里的提前返回）：
     * 关掉开屏的语义就是"不要等待"，留一个空跑的定时器没有意义。
     *
     * ⚠️ v1.1.74 需求 5：本形参**不再**等于"这次一定会播"。它与
     * 「本进程是否已播过」两个条件同时成立才播——规则见
     * [GameSplashFirstRun]。即：开发期间若本进程已进过一次贪吃蛇，
     * 再进这一页不会看到 Hello World（这是需求要的行为，不是 bug）。
     */
    showSplash: Boolean = true,
    /**
     * 本页「标题栏 / 系统栏」的配色档位（需求 3，两页共用一个值）。
     *
     * 默认 [GameChromeMode.DARK_CLASSIC] = 与改造后完全一致（不传即旧行为，
     * 调用方未接线也不会编译失败）。传**生效值**
     * （`DeveloperOptionsRepository.gameChromeMode`，总开关关时回落 DARK_CLASSIC），
     * 不是原始值。
     *
     * 三档的具体渲染差异见本函数 KDoc 的「三档 chrome 模式」一节。
     */
    chromeMode: GameChromeMode = GameChromeMode.DARK_CLASSIC,
    /**
     * 「回到主页」按钮（2026-10-25 需求：开启标题栏的情况下，标题栏右侧显示
     * 一个主页图标，点了直接回应用主页而不是只退出本页）。
     *
     * null（默认）= 不显示该按钮（本页不知道「主页」怎么回，由宿主注入——
     * 独立 Activity 宿主传「启动 MainActivity + finish」，与 `onBack` 一样
     * 保持本页对宿主无知）。
     */
    onGoHome: (() -> Unit)? = null
) {
    val holder = remember { SnakeWebViewHolder() }

    // ── 存档同步桥（v3 云同步）────────────────────────────────────────────────
    //
    // 贪吃蛇的存档是 localStorage 里的三个键（最高分 / 难度 / 主题，见
    // `GameDataBridge.GameKeys`）。桥把"页面 → 原生"的采集入口
    // （`__CIM_GAME__.pushState`）与"原生 → 页面"的待灌快照
    // （`__CIM_GAME__.pendingState`）接上；水合脚本已随 assets/index.html
    // 的 <head> 注入，页面自身脚本之前执行。
    //
    // 注册时机见下方 factory 内：**必须先于 loadUrl**。
    val gameBridge = rememberGameBridge(GameDataBridge.GameKeys.SNAKE)

    // ── 本页开屏（Hello World，[SNAKE_SPLASH_HOLD_MS] 后淡出）──────────────────
    //
    // `rememberSaveable` 而不是 `remember`：屏幕旋转 / 进程被系统回收后重建时，
    // 不希望把"已经看完开屏"这个事实丢掉、让用户再看一次 1 秒。
    // （`SplashGate` 里那个 `showSplash` 用的也是 `rememberSaveable`，同一口径。）
    //
    // ## ⚠️ 变量名必须叫 `splashVisible`，绝不能叫 `showSplash`（真机 bug，已修）
    //
    // 这里原本写的是 `var showSplash by rememberSaveable { mutableStateOf(true) }`——
    // **与上面的同名形参重名**。Kotlin 允许局部声明遮蔽形参，于是函数体里所有
    // `showSplash` 都指向**局部变量**（恒为 true），形参彻底不可见：
    // 下面的 `LaunchedEffect(showSplash)` 读到的是那个恒 true 的局部状态、
    // 而不是调用方传进来的生效值 → **关掉「贪吃蛇开屏显示」后开屏照旧显示 1 秒**。
    // 更难受的是它静默生效：没有编译警告（形参被遮蔽不是 `-Xlint` 的默认项），
    // 只有真机上肉眼能看到。修法是改名区分"调用方意愿"与"本页播放状态"：
    // 形参 `showSplash` = 要不要播；局部 `splashVisible` = 现在还在不在播。
    // （数独页从一开始就叫 `splashVisible`，两页同口径。）
    //
    // ⚠️ 初值取 `shouldSplash` 而不是写死 `true`：写死 true 时，即使调用方传了
    // `showSplash = false`，第一帧仍会以"可见"组合出开屏层
    // （`AnimatedVisibility` 先按可见播一次 fadeIn），随后才被下面的 effect 置 false
    // 触发 220ms 淡出——表现为"关掉开屏开关后仍会闪一下 Hello World"。
    // 用形参当初值，这一帧根本不存在。旋转 / 进程重建时 `rememberSaveable`
    // 会恢复"已看完"的状态，不会重播。
    //
    // ## ⚠️ v1.1.74 需求 5：开屏改为「本进程只播一次」
    //
    // 原行为是**每次进入都播**。用户要求「只在第一次进入时显示，短时间内再次进入
    // 则不显示」，并明确「应用在后台运行时不显示」——即：本次应用进程生命周期内，
    // 第一次进入游戏页才播；玩一局退出、应用还在后台、再进来直接看到游戏。
    //
    // 取号放在**组合期**（`GameSplashFirstRun.consumeIfFirstRun`）而不是 effect 里，
    // 因为 `rememberSaveable` 的初值**每个组合实例只算一次**：若放进
    // `LaunchedEffect` 里取号，旋转重建会再取一次号，把"第一次"消耗掉两次。
    // 组合期取号则天然与页面实例一一对应。
    //
    // 传进去的是**生效值**（`showSplash`，已含开发者选项总开关 && 子开关）。
    // `consumeIfFirstRun` 在开关关闭时**不消耗首次机会**，所以用户手动关掉开屏
    // 不会把"第一次"用掉——他下次打开开关仍能正常看到开屏。
    val shouldSplash = remember(showSplash) { GameSplashFirstRun.consumeIfFirstRun("snake", showSplash) }
    var splashVisible by rememberSaveable { mutableStateOf(shouldSplash) }

    // 1 秒定时：**纯计时**，与 WebView 的加载进度毫无关系。
    //
    // ⚠️ 这里刻意**不**用 `WebViewClient.onPageFinished` 来控制开屏消失：
    // 需求给的是固定 1 秒；挂到加载完成上会让"慢加载"变成"开屏一直挂着"，
    // 用户无法预期还要等多久。本地 asset 页面加载在毫秒级，1 秒足够它绘制完。
    //
    // ⚠️ 也刻意**不**把 `loadUrl` 挪到这个 effect 里再 `delay` 之后调用：
    // 那等于"等 1 秒才开始加载"，与需求"这 1 秒里后端仍在继续加载"正好相反。
    // `loadUrl` 的位置保持不动（见下方 `factory` 内的注释），与这里的计时**同时**起跑。
    //
    // ⚠️ 需求 23 的开关（形参 `showSplash`）：关掉时**不启动这段计时**——
    // 关掉开屏的语义就是"不要等待"，留一个空跑的 `delay(1000)` 没有意义，
    // 而且它会在协程里白占 1 秒。真正的"不等待"应该是打开页面立刻看到游戏。
    // 注意这里同时把 `splashVisible` 置 false：形参在页面存活期间被切成 false
    // （仓库流重新发射，例如用户在开发者选项里拨了开关）时，开屏层要立刻退场。
    //
    // ⚠️ key 里用 `shouldSplash` 而**不是** `showSplash`：用户中途把开关打开时，
    // `shouldSplash` 仍为 false（本进程已播过）→ 立刻退场，符合需求 5；
    // 若用 `showSplash` 当 key，打开开关会重跑 1 秒计时，等于把需求 5 架空。
    LaunchedEffect(shouldSplash) {
        if (!shouldSplash) {
            splashVisible = false
            return@LaunchedEffect
        }
        delay(SNAKE_SPLASH_HOLD_MS)
        splashVisible = false
    }

    // ── 需求 3：三档 chrome 模式 → 一组派生颜色（一处算、多处用）──────────────
    //
    // 五处消费点：Scaffold 容器色 / TopAppBar 容器色 / 标题与图标色 /
    // 开屏层底色 / 系统栏图标明暗。全部由下面这几个 `val` 提供，
    // 各个消费点**不再各自写 `when`**——那正是"标题栏跟随主题、系统栏图标却还是浅色"
    // 这种半档状态的来源（本页历史上真的出现过白底白图标）。
    //
    // 深色主题判定用 `background.luminance() < 0.5f`，与 `MoreCheckApp`
    // 里 `SplashGate` 的判定逐字一致（README「状态栏图标」那条记录的就是它）：
    // 不能用 `isSystemInDarkTheme()`——本应用的深浅色由用户设置决定，可能
    // 与系统设置相反（`ThemeMode` 三档）。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val classicBackground = Color(SNAKE_BACKGROUND_COLOR)

    // Scaffold 容器色 = "页面底 / 系统栏 inset 谁来画"的颜色。
    // SYSTEM 档必须是**不透明语义色**：这一档标题栏的 inset 区域由它绘制，
    // 而系统栏图标是深色（浅色主题下），浅底深图标才配对（HANDOVER 5.17）。
    val chromeBackground: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.surface
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> classicBackground
    }

    val titleBarContainer: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.surface
        GameChromeMode.DARK_CLASSIC -> classicBackground
        // 深色适配：标题栏不画自己的底色，与游戏区无缝（见本函数 KDoc）。
        GameChromeMode.DARK_ADAPTIVE -> Color.Transparent
    }

    val titleBarContent: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.onSurface
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> Color.White
    }

    // 开屏层底色：SYSTEM 跟主题（浅色主题下是浅底），两档深色固定深底。
    val splashBackdrop: Color = when (chromeMode) {
        GameChromeMode.SYSTEM -> MaterialTheme.colorScheme.background
        GameChromeMode.DARK_CLASSIC, GameChromeMode.DARK_ADAPTIVE -> classicBackground
    }

    // ⚠️ 下面这个值直接赋给 `isAppearanceLightStatusBars` / `...NavigationBars`，
    // 而那个字段名的含义是「**栏的外观是浅色的**」，即**图标 / 文字用深色**——
    // 语义极易记反（`ScanScreen` 里专门注释过）。因此：
    // - 两档深色模式：背景恒深 → 必须**浅色**图标 → 标志为 `false`；
    // - SYSTEM 档：与主题配对，浅色主题（`darkTheme = false`）→ 深色图标 → `true`；
    //   深色主题 → 浅色图标 → `false`。
    // 写成 `== SYSTEM && !darkTheme` 而不是 `!= SYSTEM || !darkTheme`（把两个方向
    // 一次摆在同一行，避免这种取反写错——本轮就差点写成后者）。
    val appearanceLightBars: Boolean =
        chromeMode == GameChromeMode.SYSTEM && !darkTheme

    // ── 状态栏 / 导航栏图标明暗（需求 3：按 chrome 档位）────────────────────
    //
    // 照 `ScanScreen` 的既有做法（`WindowCompat.getInsetsController`）：
    // 两档深色模式下游戏是固定深底（`#080B14`），浅色主题下系统默认给的是**深色**
    // 状态栏图标，深图标压深底等于看不见。所以进入本页时把两个 appearance 标志
    // 置成"用浅色图标"，离开时把进入前的取值**原样写回**——不写回的话会把
    // "浅色图标"泄漏给之后返回的实验室页（那一页跟随主题，浅色主题下需要深色图标）。
    //
    // ⚠️ 需求 3 的 SYSTEM 档**不再写死 false**：那一档要求 chrome 跟随主题，
    // 图标必须与主题配对（浅色主题 → 深色图标）。因此这里读上面算好的
    // [appearanceLightBars]，并把它作为 `DisposableEffect` 的 key：
    // 用户在设置里切档位时（仓库流重新发射 → 组合重算）必须**重新应用**一次，
    // 否则会停留在进场那一刻的取值上。
    //
    // 为什么不用 `MoreCheckApp` 里那条"按主题深浅统一设置"的规则：
    // 它在外层统管所有页面，而本页两档深色模式与当前主题无关；两套口径会互相打架
    // （谁最后执行谁生效，返回时还会闪过一帧错误图标）。这与 `ScanScreen`
    // （同样固定深底）的处置完全一致——本页只是在 SYSTEM 档下才交出控制权。
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, appearanceLightBars) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            // 拿不到窗口（理论上不会发生在 Compose 视图树上）时什么都不做，
            // 但 onDispose 必须存在：DisposableEffect 的 lambda 必须有一个 onDispose 收尾。
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            // 字段名是"bar 的 appearance 是否 light"，指的是栏内图标 / 文字为**深色**，
            // 语义容易记反，见 ScanScreen 里同名字段的注释。
            controller.isAppearanceLightStatusBars = appearanceLightBars
            controller.isAppearanceLightNavigationBars = appearanceLightBars
            onDispose {
                // 写回"进入本页 / 上一次切档之前"的取值，而不是复位成某个常量：
                // 外层 `SplashGate` 会根据当前主题统一管理这两个标志，
                // 我们只借一会儿，离开时必须原样归还。
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    // 兜底销毁，避免 WebView 泄漏。
    //
    // `AndroidView.onRelease` 已经销毁过一次，这里是**二次保险**：页面被上层整体销毁
    // （如 Activity 重建）时 onRelease 不一定跑到，而 WebView 一旦泄漏会把 Activity Context
    // 与整棵视图树一起拖住。两处都走 `holder.destroy()`，它销毁前会置空实例，
    // 所以**不会重复 destroy**。
    DisposableEffect(Unit) {
        onDispose {
            // 离开前尽力再采集一次存档（v3）：`post` 到已销毁的 WebView 上是
            // 静默 no-op，因此这里是"能采到就采"的兜底，真正的保证是下面
            // 15 秒的定时采集（最坏丢 15 秒内的改动）。
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.SNAKE)
            holder.destroy()
        }
    }

    // ── 定时采集存档（v3 云同步）──────────────────────────────────────────────
    //
    // 页面存活期间每 [GAME_PUSH_INTERVAL_MS] 把 localStorage 里的三个键采一遍。
    // 贪吃蛇的存档只在"一局结束 / 切难度 / 切主题"时变，15 秒的间隔意味着
    // 最坏情况丢 15 秒内的改动——由 onPageFinished 与离开时的两次采集兜底。
    //
    // 读 `holder.view` 而不是在 effect 里捕获 WebView：销毁路径会把它置空，
    // 空引用自然跳过，不需要额外的"页面是否还活着"标志位。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(GAME_PUSH_INTERVAL_MS)
            holder.view?.pushGameSnapshot(GameDataBridge.GameKeys.SNAKE)
        }
    }

    BackHandler { onBack() }

    // ── 「游戏标题栏显示」开关（需求 2，两页共用）───────────────────────────
    //
    // 需求 2 原文：「游戏标题栏显示开关同时控制贪吃蛇和数独，原贪吃蛇开关删除」。
    // true（默认）= 与改造前一致；false = 顶部不画 `TopAppBar`，整页只有
    // WebView 游戏本体（游戏面积更大）。
    // 关掉标题栏后的返回路径：系统返回键（下方 `BackHandler` 仍挂着，不受影响）。
    //
    // 传**原始值**（`gameTitleBarSwitch`），**生效值**在 `SnakeScreen` 的调用方
    // （`TabNavHost`）已经与过总开关——与本页无总开关概念一致：本页是功能页，
    // 不是设置页，"开关关了标题栏回不回来"由仓库层的回落规则决定
    // （总开关关 → 恒显示标题栏 = 改造前行为）。
    val showTitleBar: Boolean = gameTitleBar

    Scaffold(
        // ── 需求 3：容器色按档位来 ─────────────────────────────────────────
        //
        // 见 `SudokuScreen` 里同位置的长注释。`TopAppBar` 会绘制状态栏的 inset
        // 背景，取的是容器色；若容器是默认的主题 `background`（浅色主题下接近白色），
        // 而系统栏图标又是浅色，状态栏区域就是「白底 + 白图标」——正是用户截图反映的问题。
        // 现在两档深色给深色容器、SYSTEM 档给语义色容器，与各自档位的图标明暗**配对**。
        containerColor = chromeBackground,
        topBar = {
            // 条件渲染而不是恒渲染 + alpha(0f)：不显示时这 64dp 高度要**还给**游戏区
            // （TopAppBar 固定 64dp，alpha 隐藏只是看不见、空间照占，游戏区会矮一截）。
            if (showTitleBar) {
                TopAppBar(
                    title = { Text("贪吃蛇") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    // 2026-10-25 需求：标题栏右侧「回到主页」图标按钮（宿主注入才显示）。
                    actions = {
                        if (onGoHome != null) {
                            IconButton(onClick = onGoHome) {
                                Icon(
                                    imageVector = Icons.Filled.Home,
                                    contentDescription = "回到主页"
                                )
                            }
                        }
                    },
                    // ── 需求 3：标题栏配色随档位 ────────────────────────────
                    //
                    // 两档深色的色值取 [SNAKE_BACKGROUND_COLOR]（= 页面 `--bg-0`）而不是
                    // 另造一个：三处（标题栏 / WebView 兜底 / 底栏延伸区）同色才不会出现分界。
                    // DARK_ADAPTIVE 用全透明，让标题栏那一块"消失"在游戏区上沿；
                    // 它的状态栏 inset 仍由上面的 `chromeBackground`（不透明深色）绘制，
                    // 所以不会露出窗口白底——这是"透明标题栏"唯一需要注意的坑。
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = titleBarContainer,
                        titleContentColor = titleBarContent,
                        navigationIconContentColor = titleBarContent
                    )
                )
            }
        }
    ) { innerPadding ->
        // ── 需求要的"两层"，就在这个 Box 里 ────────────────────────────────────
        //
        // Box 里**按顺序**放两样东西：底层＝游戏本体（WebView），顶层＝Hello World 开屏层。
        //
        // ⚠️ **为什么必须按这个顺序声明**：Compose 的 `Box` 按声明顺序绘制，
        // 后声明的子元素画在先声明的**上面**。所以：
        // - `AndroidView`（游戏本体）写在**前面** → 它在**底层**；
        // - 开屏层写在**后面** → 它盖在**顶层**。
        // 反过来写的话开屏层会被 WebView 完全遮住，一秒也没有。
        //
        // ⚠️ 这个顺序同时也是"**两层真的同时存在**"、进而"**不影响游戏的启动**"的保证：
        // 两者在**同一帧**里一起组合完成——底层的 `AndroidView` 在这一帧就建好 WebView 实例
        // 并在 `factory` 里 `loadUrl`（见下方 factory 内注释），
        // 顶层的 LaunchedEffect 也在几乎同一时刻开始 1 秒计时。
        // 即：**加载从 0ms 起跑，顶层只是盖在上面被看 1 秒**，
        // 而不是"等 1 秒再开始加载"（后者会把游戏的启动整整推迟 1 秒）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // ── 内容区底色 + 系统栏 inset 归属（按档位，见上方「三档 chrome」）──
                //
                // **两档深色**：铺一层深色兜底，再把底部 inset 让回给内容
                // （`consumeWindowInsets` 消费掉 Scaffold 给的 bottom，让本 Box 铺到
                // 屏幕最底；深色背景因此延伸到手势条区域）。
                // 原因：`Scaffold` 的 `innerPadding` 含**底部导航栏 inset**，
                // `padding(innerPadding)` 之后内容只铺到手势条上方，手势条那一带露出的是
                // **窗口背景色**（浅色主题下是白色）；而本页把
                // `isAppearanceLightNavigationBars` 置成 false（= 用**浅色**图标）——
                // 于是那一带变成「白底 + 白图标」。让深色铺到底后两者才配对
                // （HANDOVER 5.17：系统栏图标必须与它身后的背景配对）。
                //
                // **SYSTEM 档**：反过来，**保留底部 inset**（用 `padding(innerPadding)`）。
                // 这一档系统栏图标跟随主题（浅色主题下是**深色**图标），因此它身后的背景
                // 必须是**浅色**的——也就是 `Scaffold` 的 `surface` 容器色。
                // 若仍让深色游戏区铺到手势条下，就成了「深底 + 深图标」，把 5.17 那条
                // 换个方向再踩一次。代价是游戏画面矮一条手势条的高度，这是本档的语义。
                //
                // ⚠️ `SNAKE_BACKGROUND_COLOR` 存的是**ARGB 整数**（给 WebView 的
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
                            // ⚠️ 底部 inset 让给内容（v1.1.78 修复）：WebView 的
                            // `innerHeight` 把系统导航栏那一条也算进去（网页无法感知），
                            // 方向键最下面一行会被手势条压住。补一条等高内边距即可；
                            // 背景仍是 chromeBackground，不会出现分界色。
                            .navigationBarsPadding()
                    }
                )
        ) {
            // ── 需求的"两层"之底层：游戏本体（WebView）───────────────────────────
            //
            // 声明在开屏层**之前** → 画在**下面**（Box 按声明顺序绘制）。
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {                        // ── 逐条设置，每条都写清理由 ──────────────────────────

                        // 游戏是纯 JS 驱动（Canvas 绘制、键盘 / 触屏事件、难度切换），
                        // 关掉 JS 就只剩一张静态棋盘，所以必须开。
                        settings.javaScriptEnabled = true

                        // **关键**：最高分与上次选择的难度都存在 `window.localStorage`
                        // （见 game.js 的 `CONFIG.STORAGE_KEY_SCORE` / `STORAGE_KEY_LEVEL`）。
                        // 不打开 domStorage，`localStorage` 对象不可用，game.js 里那两处
                        // `try` 包裹会静默降级成"只存内存"——表现为：同一局里最高分正常，
                        // 退出本页再进来最高分归零。
                        settings.domStorageEnabled = true

                        // ⚠️ 这里**必须**是 true，与 `SearchWebScreen` 里的 false 相反。
                        // 两个页面场景不同，取值就必须不同：
                        // - `SearchWebScreen` 只加载公网 http(s)，页面根本不需要碰本地文件，
                        //   开着只是白送"远程页面借 file:// 读应用私有目录"这一攻击面；
                        // - 本页加载的**本身就是** `file:///android_asset/snake/index.html`，
                        //   而它的样式表与脚本是同目录的相对引用
                        //   （html 里 `<link href="style.css">`、`<script src="game.js">`）。
                        //   `allowFileAccess = false` 会让 WebView 拒绝这些 file:// 子资源，
                        //   结果是**白板 + 完全不可玩**，不是"少了样式"那么轻。
                        // 因为本页只加载这一份本地固定页面、不接受任何外部 URL
                        // （也没有任何跳转能力），放开这一项的风险面约等于零。
                        settings.allowFileAccess = true

                        // 本地页面不需要 `content://`（本游戏不读任何系统内容），保持关闭，
                        // 与 `SearchWebScreen` 同一口径：能给的最小权限就给最小的。
                        settings.allowContentAccess = false

                        // `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs`
                        // **刻意不设置**，保持系统默认的 false：
                        // 本游戏只有**同目录**的相对引用，不涉及任何跨源读取
                        // （file:// 之间或 file:// → http(s)），因此没有任何理由放开跨源。
                        // 打开它们等于让页面里的脚本能读任意本地文件，是纯粹的净损失。
                        //
                        // `mediaPlaybackRequiresUserGesture` 等其余开关同理**不碰**：
                        // 本游戏没有音频视频，改了没有收益，只会多一处
                        // "为什么这个页面跟别处不一样"的解释成本。

                        // ── 深色背景兜底（实测必需）──────────────────────────────
                        //
                        // WebView 自身的默认底色是**白色**。页面第一次绘制完成之前
                        // （以及万一 CSS 背景层未能铺满时）露出的就是这一层，
                        // 表现为「顶部一小条深色、下面一片白」。
                        //
                        // 取值与素材 `style.css` 里的 `--bg-0`（#080b14）一致：
                        // 两处用同一个色值，兜底层才不会与页面背景出现色差。
                        //
                        // ⚠️ 这里**刻意不跟随主题**（上方开屏层的底色是跟随主题的，
                        // 两者职责不同，本文件 KDoc「两层底色的分工」一节有完整理由）：
                        // 游戏是固定深色的全屏内容，这里改成跟随主题会在浅色主题下
                        // 露出一圈浅色边，而且那是**长期可见**的缺陷；
                        // 而保持深底的唯一代价是浅色模式下开屏淡出的 220ms 里
                        // 能看到一次由浅到深的过渡（且只在页面没画完时才会出现）。
                        setBackgroundColor(SNAKE_BACKGROUND_COLOR)

                        // ── 视口（v1.1.78：显式声明，避免依赖 WebView 版本默认值）──
                        // 素材页面带 `<meta name="viewport" content="width=device-width,
                        // maximum-scale=1">`，但 WebView 默认忽略它、按 980px 桌面宽度
                        // 排版再缩小 —— 表现为整页字变小、棋盘与按钮被压小。
                        // 打开后布局宽度 = 设备 CSS 宽度，页面 1:1 渲染。
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        // ── 「退出游戏」桥（本轮需求 3）────────────────────────────
                        //
                        // 仅当标题栏被隐藏（[gameTitleBar] = false）时注册：
                        // 那时页面里没有返回键，需求要求底部按钮变成「退出游戏」，
                        // 退出动作必须由网页回调到原生侧（= onBack = finish()）。
                        // 标题栏还在时不注册——game.js 检测不到宿主回调，
                        // 底部按钮自然保留原「重新开始」，零 JS 侧改动成本。
                        //
                        // `addJavascriptInterface` 必须在 loadUrl **之前**调用：
                        // 页面脚本执行时桥就要已就位（虽然 game.js 只在点击时才用到它，
                        // 顺序上先注册更稳，也避免"页面先跑完、桥后到"的时序窗口）。
                        // 因此这里把桥的注册放在 loadUrl 之前，页面加载完成时机不受影响。
                        //
                        // ⚠️ WebView 销毁前必须 removeJavascriptInterface：
                        // 桥持有 onExit 闭包（内含 Compose 侧的回调链），
                        // 随 holder.destroy() 的销毁路径一起收口——见下方 holder 销毁逻辑。
                        if (!showTitleBar) {
                            addJavascriptInterface(
                                SnakeExitBridge { (ctx as? android.app.Activity)?.finish() },
                                "SNAKE_EXIT"
                            )
                        }

                        // ── 存档同步桥（v3）────────────────────────────────────────
                        //
                        // 与退出桥同在 loadUrl 之前注册，且**无条件注册**（与标题栏
                        // 开关无关）：存档同步是后台能力，不该被"退出按钮长什么样"
                        // 这种界面开关影响。assets/index.html 的 <head> 水合脚本在
                        // 页面解析期就会调 `__CIM_GAME__.pendingState()`——注册晚于
                        // loadUrl 会让首次水合静默失败（脚本里检测不到桥就跳过）。
                        addJavascriptInterface(gameBridge, GameDataBridge.JS_NAME)

                        // 只在**首次创建**时加载。`update` 里刻意不调 loadUrl：
                        // 任何一次重组都会走 update，在那里加载会让正在玩的这一局被无端重置。
                        //
                        // ⚠️ 这个位置**刻意不改**：本页开屏层（[SnakeSplashOverlay]）要求
                        // 「开屏显示的这 1 秒里加载仍在继续」，而 `factory` 正是"本页组合的
                        // 第一时刻"——加载从这里起跑，与上方 `LaunchedEffect` 的 1 秒计时
                        // **同时开始**。若把 loadUrl 挪到计时结束之后，就变成"等 1 秒才加载"，
                        // 与需求正好相反。
                        loadUrl(SNAKE_PAGE_URL)

                        // 页面加载完成后的两件事：退出回调补注册（标题栏隐藏时）+
                        // 存档首次采集（始终）。
                        //
                        // 原来这里**只在标题栏隐藏时**才挂 WebViewClient；现在改为
                        // 无条件挂一个，`onPageFinished` 里按开关分支——退出桥的语义
                        // 完全不变，存档采集则始终执行。默认 WebViewClient 的
                        // onPageFinished 本来就是空实现，多挂一个没有行为差异。
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!showTitleBar) {
                                    view.loadUrl(SNAKE_REGISTER_EXIT_JS)
                                }
                                view.pushGameSnapshot(GameDataBridge.GameKeys.SNAKE)
                            }
                        }

                        // 交给 holder 保管，供销毁路径统一处理。
                        holder.view = this
                    }
                },
                // 刻意留空，见 factory 内对 loadUrl 的说明。
                update = { },
                // 与上面的 DisposableEffect 是同一条销毁路径的两次保险。
                //
                // ⚠️ 必须走 `holder.destroy()` 而不是直接 `it.destroy()`：
                // holder 是"谁负责销毁"的**唯一收口**，它销毁前会把实例置空，
                // 因此 onRelease 与 onDispose 谁先到都只销毁一次。
                // 若这里直接 `it.destroy()`，holder.view 仍指着那个已销毁的实例，
                // 随后 onDispose 会再销毁一遍——虽然 `WebView.destroy()` 重复调用不崩，
                // 但那正是上面 KDoc 第 2 条要避免的"谁负责销毁变得含糊"。
                //
                // v3：销毁前先采集一次存档（尽力而为，理由见 DisposableEffect 里那段）。
                onRelease = { web ->
                    web.pushGameSnapshot(GameDataBridge.GameKeys.SNAKE)
                    holder.destroy()
                }
            )

            // ── 需求的第二层：开屏层，盖在 WebView（游戏本体）之上 ──────────────────
            //
            // ⚠️ **为什么写在这里、写在 AndroidView 之后**：Compose 的 `Box`
            // 按**声明顺序**绘制，后声明的画在上面。本层写在 `AndroidView` 之后，
            // 于是它就是**顶层**；WebView 是**底层**。二者在**同一帧**一起挂载，
            // 所以"两层同时存在"是真的——不是"先显示开屏、1 秒后才去建 WebView"。
            //
            // 它出现 / 消失**完全不干预** WebView：上面的 `factory` 在同一帧就已经
            // 建好实例并 `loadUrl` 了，开屏存在的这 1 秒里加载一直在后台继续
            // （需求原话："不影响游戏的启动"、"此时后端仍在继续加载"）。
            //
            // 用 `AnimatedVisibility` 而不是裸 `if`：进出各播一段淡入 / 淡出，避免硬切。
            // 与 `SplashGate` 里的用法同源（那里只配了 exit，因为它的 enter 由"内容先在
            // 开屏后面组合好"来承担；本页的开屏层是**后来盖上去**的，首次出现也需要入场）。
            //
            // ⚠️ enter 为什么**显式写** `fadeIn` 而不吃默认值：
            // `AnimatedVisibility` 的默认 enter 是 `fadeIn() + expandIn()`（从中心展开）。
            // 本层的 Box 是 `fillMaxSize`，expand 会让内容在动画途中被裁剪；
            // 而文字自身已经有一段"淡入 + 上浮"（见 [SnakeSplashOverlay] 里的 Animatable），
            // 再叠一层展开就是两套互不相干的入场动效，观感会散。只留纯淡入，
            // 让"容器淡入"和"文字淡入上浮"各司其职。
            // 淡入时长与淡出取同值 [SNAKE_SPLASH_FADE_MS]，进出对称。
            AnimatedVisibility(
                // ⚠️ 这里必须是**本页播放状态** `splashVisible`，不是形参 `showSplash`：
                // 形参只表达"调用方要不要播"，看完 1 秒后仍为 true，
                // 用它当可见性会让开屏永不消失（详见上方局部状态声明处的说明）。
                visible = splashVisible,
                enter = fadeIn(animationSpec = tween(SNAKE_SPLASH_FADE_MS)),
                exit = fadeOut(animationSpec = tween(SNAKE_SPLASH_FADE_MS))
            ) {
                SnakeSplashOverlay(backdrop = splashBackdrop)
            }
        }
    }
}
