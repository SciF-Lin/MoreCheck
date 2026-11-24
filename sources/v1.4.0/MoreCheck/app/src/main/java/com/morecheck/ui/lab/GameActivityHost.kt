package com.morecheck.ui.lab

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.MainActivity
import com.morecheck.di.appContainer
import com.morecheck.data.DeveloperOptionsRepository
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.theme.AccentColor
import com.morecheck.ui.theme.MoreCheckTheme
import com.morecheck.util.ThemeMode

/**
 * 「实验室 → 网页小游戏」的**独立 Activity 宿主**（v1.1.74 需求 6）。
 *
 * 承载三屏：[SnakeHostActivity]（贪吃蛇）、[SudokuHostActivity]（数独）、
 * [Sudoku2HostActivity]（数独2）。
 * 三屏各自一个文件级 `WebViewHolder`、各自的开屏与配色策略（项目纪律：一屏一个自洽实现，
 * 不抽公共组件），本文件只提供**它们共用的那一层**：主题 + 开发者选项读取 + 导航语义。
 *
 * ## 为什么要拆成独立 Activity + 独立 task（需求原文）
 *
 * 「贪吃蛇和数独单独打开，不要和其他页面挤在一起」——用户要的是它们在
 * **Android 最近任务**里作为**独立卡片**出现，而不是被埋在「实验室 → 贪吃蛇」
 * 那一长串应用内页面栈的最深处：滑不出来、也说不清"我现在在哪一层"。
 *
 * ## 为什么不直接用 `android:process` 做真进程隔离
 *
 * 需求明确是「独立 Activity + 独立 task」，不是进程隔离。真进程隔离会付出：
 * 跨进程 DataStore 读、跨进程传参、进程被杀时正在计的番茄/正在玩的局全部丢失，
 * 而收益（内存隔离）对两个 13KB / 5121 行的本地网页毫无意义。
 * 同进程 + 不同 taskAffinity 已经能拿到「最近任务分开」这个用户可见的效果。
 *
 * ## 三个关键 manifest 取值与理由
 *
 * 宿主 Activity 在 `AndroidManifest.xml` 里各写：
 *
 * - **`android:taskAffinity="com.morecheck.task.snake"` / `...sudoku` / `...sudoku2`**：
 *   这是"独立 task"的**唯一开关**。默认 affinity 是包名，与 `MainActivity` 相同——
 *   同 affinity 的 Activity 启动时会**复用宿主那个 task**，最近任务里就还是一张卡片，
 *   需求落空。改成**互不相同**的字符串后，每个游戏各自开一个新 task，
 *   最近任务里就是四张互不相干的卡片。
 *   ⚠️ 加新游戏页时**必须换一个全新的字符串**：沿用已有的任何一个，
 *   它就会与那个游戏共用同一个 task，最近任务里仍是一张卡片——
 *   而这个 bug 不报错，只表现为"两个游戏挤在一张卡片里"，极难定位。
 *   ⚠️ 它**只影响新 task 的归属**，不改变 `MainActivity` 自己的单 task 行为
 *   （那由它的 `singleTask` + `documentLaunchMode="never"` 兜底，两套机制互不干扰）。
 * - **`android:launchMode="singleTask"`**：与 `MainActivity` 同一口径。
 *   游戏页只有一屏、不该出现多实例；`singleTask` 让「重复点实验室宫格 / 重复点
 *   桌面快捷方式」变成把**已有那个 task 搬到前台**，而不是往同一个 task 里叠第二个实例
 *   （那正是需求 18 的老症状）。
 *   ⚠️ 因为是 `singleTask`，从实验室反复进出走的是 `onNewIntent` 而非 `onCreate`——
 *   所以本页**刻意不做任何跨页的状态恢复**（不做 `savedInstanceState` 解析），
 *   每次进来都是一次干净的"重新开一局"。这也与「离开即销毁 WebView」的既有行为一致。
 * - **`android:excludeFromRecents="false"`（即不写，默认就是 false）**：明确**要**出现在最近任务里。
 *   写在这里是为了让"这个 Activity 故意不写 excludeFromRecents"这件事在 manifest 里
 *   自解释，而不是让后来者怀疑"是不是漏了"。
 *
 * ## ⚠️ 为什么绝不给它们加 `LAUNCHER` intent-filter
 *
 * `.MainActivity` 刻意把自己的 `LAUNCHER` 过滤器搬到了 9 个 `activity-alias` 上
 * （见 `AndroidManifest.xml` 里那段注释）：真实 Activity 若还留着 LAUNCHER，
 * 桌面会**同时出现两个图标**。给这两个新 Activity 加 LAUNCHER 是完全同一个坑，
 * 它们只能被**代码里的显式 Intent**启动（`openSnake()` / `openSudoku()`）。
 *
 * ## 开发者选项那批开关怎么在这里取
 *
 * 走 `applicationContext as MoreCheckApp` → `container.developerOptionsRepository`，
 * 与 `TabNavHost` 里那两个 `composable(Snake)` / `composable(Sudoku)` **完全同一条路径**。
 *
 * ## 为什么这样做不需要跨进程那套（`android:process`）的麻烦
 *
 * 两者同进程，`AppContainer` 是 `Application` 持有的**同一个对象**：
 * 独立 Activity 拿到的与 `MainActivity` 拿到的是同一实例、同一份 DataStore 缓存。
 * 取值写法的唯一注意点是**不能**写 `this as MoreCheckApp`（Activity 不是 Application），
 * 必须经 `applicationContext` 转一次——与 `MainActivity.kt:166` 同一条纪律。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 贪吃蛇宿主
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「实验室 → 贪吃蛇」的独立宿主 Activity。
 *
 * 页面本体是 [SnakeScreen]——**一个字节都没改**。本页只做三件事：
 * 铺主题、把开发者选项那批开关接上去、把 `onBack` 翻译成"结束本页 task"。
 */
class SnakeHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 与 `MainActivity.onCreate` 同一条：整页内容铺到系统栏下面，
        // 由各页自己用 WindowInsets 让位（贪吃蛇页已有一套完整的 inset 处理）。
        enableEdgeToEdge()

        // 不读 savedInstanceState、不解析 Intent：见本文件 KDoc「launchMode」那段——
        // 本页每次进入都是一次干净的开始，这是**有意**的选择。
        setContent {
            GameHostTheme {
                SnakeHostContent(onFinish = { finish() })
            }
        }
    }
}

/**
 * [SnakeHostActivity] 的内容层：读开发者选项开关 → 组合 [SnakeScreen]。
 *
 * ## 为什么把它单独拆成一个 `@Composable`
 *
 * 让 `setContent` 的 lambda 只剩"铺主题 + 调这一个函数"，
 * 而开关的读取与默认值处理集中在这一个可读的地方。
 * 顺带的好处：将来若有第二个地方想组合贪吃蛇页（例如开发期的预览），
 * 复用的是这一段而不是 `setContent` 里的匿名内容。
 *
 * @param onFinish 结束本页（返回键 / 标题栏返回箭头都走它）。
 *   宿主页的"返回"语义就是**结束这一个 task**，与它在 NavHost 里当二级页时的
 *   `popBackStack()` 语义不同——这是拆 Activity 的必然结果，见函数体注释。
 */
@Composable
private fun SnakeHostContent(onFinish: () -> Unit) {
    val options = rememberDeveloperOptions()

    // 三档 chrome 与各开关：取值与默认值口径照搬 `TabNavHost` 里原来的 `composable(Snake)`。
    //
    // 🔄 本轮需求 5：标题栏改读**贪吃蛇专属**的生效值
    // （`gameTitleBarSnakeEnabled` = 开发者选项总开关 && 贪吃蛇子开关）。
    // 原来读的 `gameTitleBarEnabled`（两页共用）保留在仓库层，但贪吃蛇页从此走自己的流。
    val gameTitleBar by options.gameTitleBarSnakeEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val snakeSplash by options.gameSplashSnakeEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val gameChrome by options.gameChromeMode
        .collectAsStateWithLifecycle(initialValue = GameChromeMode.DARK_CLASSIC)

    // 返回键：结束本页 task。
    //
    // 为什么不复用 `SnakeScreen` 里那条「快捷方式直达时回桌面、否则 popBackStack」
    // 的分支：本页**已经没有 NavHost 了**，没有"实验室 → 贪吃蛇"这一层可 pop，
    // 也没有 `launchedFromLabShortcut` 概念（它属于 NavHost 那条链路）。
    // `finish()` 在两种入口下都是正确语义——从实验室点进来时它回到实验室那个 task，
    // 从桌面快捷方式点进来时它回桌面。这是拆 Activity 之后**更简洁**的一条规则，
    // 不需要任何标志位。
    SnakeScreen(
        onBack = onFinish,
        gameTitleBar = gameTitleBar,
        showSplash = snakeSplash,
        chromeMode = gameChrome,
        // 2026-10-25：标题栏右侧「回到主页」按钮（标题栏开着时才可见）。
        onGoHome = rememberGoHome(onFinish)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 数独宿主
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「实验室 → 数独」的独立宿主 Activity。
 *
 * 与 [SnakeHostActivity] **刻意各写一份**：两屏的开关不同
 * （数独读 `gameSplashSudokuEnabled` 而非贪吃蛇那一半），抽成一个参数化的宿主
 * 只会把"哪个游戏读哪个开关"这件本该一眼看清的事藏进布尔形参里。
 */
class SudokuHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameHostTheme {
                SudokuHostContent(onFinish = { finish() })
            }
        }
    }
}

/** [SudokuHostActivity] 的内容层，取值口径照搬 `TabNavHost` 原来的 `composable(Sudoku)`。 */
@Composable
private fun SudokuHostContent(onFinish: () -> Unit) {
    val options = rememberDeveloperOptions()

    // 🔄 本轮需求 5：标题栏改读**数独专属**的生效值（`gameTitleBarSudokuEnabled`），
    // 与贪吃蛇那条对称；合成规则见 `DeveloperOptionsRepository`。
    val gameTitleBar by options.gameTitleBarSudokuEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val sudokuSplash by options.gameSplashSudokuEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val gameChrome by options.gameChromeMode
        .collectAsStateWithLifecycle(initialValue = GameChromeMode.DARK_CLASSIC)

    SudokuScreen(
        onBack = onFinish,
        showSplash = sudokuSplash,
        showTitleBar = gameTitleBar,
        chromeMode = gameChrome,
        // 2026-10-25：标题栏右侧「回到主页」按钮。
        onGoHome = rememberGoHome(onFinish)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 数独2 宿主
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「实验室 → 数独2」的独立宿主 Activity。
 *
 * 与 [SnakeHostActivity] / [SudokuHostActivity] **刻意各写一份**：三屏的开关组合
 * 各不相同（数独2 不接开屏那一套），抽成一个参数化的宿主只会把
 * "哪个游戏读哪个开关"这件本该一眼看清的事藏进布尔形参里。
 */
class Sudoku2HostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameHostTheme {
                Sudoku2HostContent(onFinish = { finish() })
            }
        }
    }
}

/**
 * [Sudoku2HostActivity] 的内容层：读「游戏标题栏显示」→ 组合 [Sudoku2Screen]。
 *
 * ## 开关形参：两个（2026-10-27 起补上开屏）
 *
 * - **开屏**：`Sudoku2Screen` 本轮新增了开屏层，读 `gameSplashSudoku2Enabled`
 *   （原文档说"本页压根没有开屏层"，那是改造前的状态）；
 * - **不读三档 chrome**：素材自带深浅色，`Sudoku2Screen` 刻意恒跟随应用主题。
 */
@Composable
private fun Sudoku2HostContent(onFinish: () -> Unit) {
    val options = rememberDeveloperOptions()

    // 标题栏开关：读**数独2 专属**的生效值（2026-10-22 补）。
    // 此前这里读的是两页共用的 `gameTitleBarEnabled`，而那个总开关在界面上
    // 已经变成"进二级页"的入口，用户没有地方能关掉数独2 的标题栏。
    val gameTitleBar by options.gameTitleBarSudoku2Enabled
        .collectAsStateWithLifecycle(initialValue = true)

    // 开屏：2026-10-27 新增（实验室开屏总开关 && 游戏设置 && 数独2 子开关）。
    val sudoku2Splash by options.gameSplashSudoku2Enabled
        .collectAsStateWithLifecycle(initialValue = true)

    Sudoku2Screen(
        onBack = onFinish,
        showSplash = sudoku2Splash,
        showTitleBar = gameTitleBar,
        // 2026-10-25：标题栏右侧「回到主页」按钮。
        onGoHome = rememberGoHome(onFinish)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 两个宿主共用的那一层
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 两个宿主共用的**主题包装**：读「主题模式 / 主题色」→ 算出 `darkTheme` → 铺
 * [MoreCheckTheme]。
 *
 * ## 为什么必须复刻这一段，而不能直接 `MoreCheckTheme()` 用默认值
 *
 * `MoreCheckTheme` 的两个形参都有默认值（`isSystemInDarkTheme()` / 默认绿色），
 * 不传的话用户在「我的 → 设置 → 外观」里选的深浅色与主题色在这两屏**全部失效**——
 * 会出现"应用其它地方是深色，点进贪吃蛇变浅色"。所以这两个宿主必须和
 * `MainActivity.setContent` 一样把用户的选择读出来。
 *
 * ## 为什么读的是 SettingsViewModel 而不是直接订阅仓库
 *
 * `SettingsViewModel` 里的 `themeMode` / `themeAccent` 是 `StateFlow`，
 * 且它的 `init` 已经把 DataStore 的持久化值灌进去了；`MainActivity` 用的就是它。
 * 这里用同一个 `viewModel(factory = AppViewModelProvider.Factory)` 拿到的是
 * **同一个 Activity 作用域之外的 ViewModelStoreOwner**——`viewModel()` 在
 * `ComponentActivity` 上默认按**这个 Activity**分作用域，因此每次进游戏是**新实例**，
 * 它会在自己的 `init` 里重新读一次 DataStore（几毫秒的磁盘读，值先落到默认值
 * 再被真实值替换，与 `MainActivity` 冷启动时的观感完全一样）。
 *
 * 这正是我们要的：**不共享 ViewModel**，因此游戏 task 的状态与主 task 的状态互不干扰
 * （这正是需求 6 要的"独立"的一部分）。若反过来去找 MainActivity 那个实例，
 * 就等于把两个 task 又绑回一起了。
 *
 * @param content 铺在主题里的内容。
 */
@Composable
private fun GameHostTheme(content: @Composable () -> Unit) {
    val settingsViewModel: SettingsViewModel =
        viewModel(factory = AppViewModelProvider.Factory)
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val themeAccent by settingsViewModel.themeAccent.collectAsStateWithLifecycle()

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    MoreCheckTheme(
        darkTheme = darkTheme,
        accent = AccentColor.fromId(themeAccent)
    ) {
        content()
    }
}

/**
 * 取**进程内**的开发者选项仓库单例。
 *
 * ## 写法与理由
 *
 * `context.appContainer.developerOptionsRepository`
 * ——与 `MainActivity.kt:166` 与 `TabNavHost` 里那两处**逐字同款**。
 *
 * - 必须经 `applicationContext`：`this` 是 Activity，**不是** `MoreCheckApp`，
 *   直接 `as` 会抛 `ClassCastException`。这是本项目反复踩过的一条纪律。
 * - 与 Activity 生命周期无关：容器挂在 `Application` 上，两个宿主 task 无论怎么
 *   重建，拿到的都是同一实例、同一份内存中的 DataStore 缓存。
 *
 * 用 `remember(context)` 缓存而不是每次重组都取：后者会产生一个虽然正确但
 * 每次重组都新建的引用，Compose 的相等性判断失效、`collectAsStateWithLifecycle`
 * 的订阅可能被反复重建。
 *
 * ⚠️ `applicationContextOf()` **必须在 `remember` 的 key 位置求值**，
 * 不能写进它的 lambda 里：`LocalContext.current` 是 `CompositionLocal`，
 * 只有组合期才能读，而 `remember { ... }` 的 lambda 是**普通函数体**、
 * 不是组合期——在 lambda 里调 `@Composable` 函数会直接编译报错
 * （"@Composable invocations can only happen from the context of a @Composable function"）。
 */
@Composable
private fun rememberDeveloperOptions(): DeveloperOptionsRepository {
    val appContext = applicationContextOf()
    return remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
}

/**
 * 「回到主页」动作（2026-10-25 需求：游戏标题栏右侧的主页图标）。
 *
 * 语义 = **回到应用主页**，而不是只退出本页：
 * 1. `startActivity(MainActivity)` + `FLAG_ACTIVITY_NEW_TASK`——MainActivity 是
 *    `singleTask`，已有主 task 会被**整体搬到前台**（不会新建实例，也不会动
 *    用户离开时的页面栈）；
 * 2. 随后 [onFinish] 结束本游戏 Activity（= 它所在的独立 task 从最近任务收起，
 *    「回到主页」的语义就是离开这一局，不给最近任务留一个空壳任务）。
 *
 * ⚠️ 顺序先启动再 finish：反过来会让屏幕短暂落回桌面（游戏 task 结束时
 * 系统默认回桌面，主 task 的切换动画接不上）。
 */
@Composable
private fun rememberGoHome(onFinish: () -> Unit): () -> Unit {
    val context = LocalContext.current
    return remember(context, onFinish) {
        {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            onFinish()
        }
    }
}

/**
 * 取 `LocalContext.current.applicationContext`。
 *
 * 单独拆一个函数是因为 `@Composable` 的 `remember` key 必须先算出来才能写参数，
 * 而 `LocalContext.current` 只能在**组合期**读——两行挤在一句里读起来反而绕。
 *
 * 顺带说明为什么这里**不**直接 `LocalContext.current.applicationContext as MoreCheckApp`
 * 一行写完：那样也完全可行，功能上没有差别。拆开只是为了 `remember` 的 key
 * 与取值体读起来是同一件事，减少"key 用 A、值取自 B"这类手滑。
 */
@Composable
private fun applicationContextOf(): Context =
    androidx.compose.ui.platform.LocalContext.current.applicationContext

// ─────────────────────────────────────────────────────────────────────────────
// 启动入口（供 LabScreen / 桌面快捷方式调用）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 启动「实验室 → 贪吃蛇」独立 task。
 *
 * ## 为什么不带 `FLAG_ACTIVITY_NEW_TASK`
 *
 * 常见误解是"新 task 必须加 NEW_TASK"。实际上 `NEW_TASK` 的语义是
 * **"把这次启动当作一个独立任务来查找归属"**，它会让系统按 affinity 去找已有 task；
 * 而我们**正是**想要系统按新 affinity 开一个新 task——不加 `NEW_TASK` 时，
 * 系统同样按 `taskAffinity` 决定新 Activity 进哪个 task（新 affinity ⇒ 新的 task）。
 *
 * 加了反而有害：`MainActivity` 是 `singleTask`，从它启动时**不加** NEW_TASK
 * 才会把请求交给已有的游戏实例（`onNewIntent` / 搬到前台）；
 * 加了 NEW_TASK 则每次都新建一个实例，与 `singleTask` 的意图矛盾。
 *
 * `GameHostActivity` 的 `singleTask` + 独立 affinity 已经完整表达"独立 task 且不重复"。
 *
 * ## `FLAG_ACTIVITY_REORDER_TO_FRONT` 要不要带
 *
 * 不带。用户从实验室点进贪吃蛇、去别的应用、回来再点实验室的贪吃蛇格子时，
 * **每次都应该开一局新的**（游戏语义），而不是把上一局捞回来继续。
 * `singleTask` 已经保证 task 本身不会被重复创建，但它的 WebView 每次进入都是新建的
 * （离开即 `destroy()`），所以"新的一局"这件事由页面自己保证，不需要这个 flag。
 *
 * @param context 发起方 Context（`Activity` 或 `Application` 都行）。
 */
fun openSnake(context: Context) {
    context.startActivity(Intent(context, SnakeHostActivity::class.java))
}

/**
 * 启动「实验室 → 数独」独立 task。flag 口径与理由与 [openSnake] **完全相同**，不重复写。
 */
fun openSudoku(context: Context) {
    context.startActivity(Intent(context, SudokuHostActivity::class.java))
}

/**
 * 启动「实验室 → 数独2」独立 task。flag 口径与理由与 [openSnake] **完全相同**，不重复写。
 */
fun openSudoku2(context: Context) {
    context.startActivity(Intent(context, Sudoku2HostActivity::class.java))
}

// ─────────────────────────────────────────────────────────────────────────────
// 桌面快捷方式的深链分流
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 实验室桌面快捷方式里，**已经变成独立 Activity** 的那几页的 key 集合。
 *
 * ## 这张表为什么必须与 `LabRealPage.key` 逐字一致
 *
 * 快捷方式的 `lab_page` 值就是 `LabRealPage.key`（见 `LabScreen.pinLabShortcut`），
 * 本项目一贯的硬约束是「key 与 `TabDestination.route` 同名」。
 * 这里列的是**例外**：贪吃蛇与两个数独拆成独立 Activity 之后，`TabNavHost` 里
 * **不再有** `composable(route = .../snake)` / `.../sudoku` / `.../sudoku2`
 * 这三个目的地，因此这三条快捷方式**不能再走应用内导航**
 * （那会 navigate 到一个不存在的目的地）。
 *
 * 刻意**不**引用 `LabRealPage.SNAKE` 等枚举项来生成这张表：
 * 那是 `ui.lab` 的**文件级 private** 枚举（见 `LabScreen.kt:131`），跨文件拿不到；
 * 与其把它改成 internal（把一个只该在本页使用的枚举暴露给整个包），
 * 不如在这里手写三个字符串并用注释绑定——与项目里"跨模块必须共享的字面量
 * 才 public/常量"的做法一致（见 `ACTION_LAB_SHORTCUT` 的那两段注释）。
 *
 * ⚠️ 将来新增游戏页时，**这里也要加一行**，否则那个游戏的桌面快捷方式
 * 会安静地落回首页（`TabDestination.fromRouteOrNull` 解析不出 → 不导航）。
 */
private val GAME_SHORTCUT_KEYS = mapOf(
    "snake" to { context: Context -> openSnake(context) },
    "sudoku" to { context: Context -> openSudoku(context) },
    "sudoku2" to { context: Context -> openSudoku2(context) }
)

/**
 * 若 [labPage] 指向一个「已拆成独立 Activity」的实验室页，就把它启动起来并返回 `true`。
 *
 * ## 谁在调用、为什么必须有这一层分流（v1.1.74 需求 6）
 *
 * 桌面快捷方式的链路是：`ACTION_LAB_SHORTCUT` + `EXTRA_LAB_PAGE` →
 * [com.morecheck.MainActivity.shortcutTargetOf] → `labShortcutTarget` →
 * `MoreCheckRoot` 的 `LaunchedEffect` → `TabDestination.fromRouteOrNull` → `navigate`。
 *
 * 需求 6 之后，贪吃蛇与数独**不再有 NavHost 目的地**，所以它们必须在这条链的**更前面**
 * 被拦下来、由本函数直接启动对应的 Activity。否则 `fromRouteOrNull("snake")` 仍会命中
 * `TabDestination.Snake`（枚举项还在，`LabScreen` 的桌面快捷方式仍能生成），
 * 而 `navigate` 会因为**没有匹配的 `composable`** 而抛
 * `IllegalArgumentException: Navigation destination that matches request cannot be found`——
 * 也就是"点了桌面上的贪吃蛇图标，应用直接崩"。
 *
 * 拦截点选在 `MainActivity`（而不是 `MoreCheckRoot`）的原因：
 * `MoreCheckRoot` 不持有 Activity、也不该知道"有些页是另一个 Activity"这件事；
 * 而 `MainActivity` 恰好是读这个 Intent 的地方，拦截在这里改动面最小、
 * 也不需要给骨架加一个"哪些 route 要外抛"的形参。
 *
 * @param context 用于启动的 Context（`MainActivity` 自己传 `this` 即可）。
 * @param labPage `EXTRA_LAB_PAGE` 的值，即某个 `LabRealPage.key`。
 * @return 命中独立 Activity 的页并已启动时返回 `true`（调用方应**不再**做应用内导航）；
 *   不是这两页时返回 `false`，调用方按原链路继续。
 */
fun openGameFromLabShortcut(context: Context, labPage: String?): Boolean {
    val launch = GAME_SHORTCUT_KEYS[labPage] ?: return false
    launch(context)
    return true
}
