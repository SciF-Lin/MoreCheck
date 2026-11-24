package com.morecheck.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.morecheck.ui.music.MusicEntryConfirmDialog
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
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
import com.morecheck.ui.mine.PersonalizationScreen
import com.morecheck.ui.mine.ReminderEnhanceScreen
import com.morecheck.ui.lab.DeAiScreen
import com.morecheck.ui.lab.DomainPriceScreen
import com.morecheck.ui.lab.KnowledgeQaScreen
import com.morecheck.ui.lab.DeveloperOptionsScreen
import com.morecheck.ui.lab.GithubTrendingScreen
import com.morecheck.ui.lab.HotSearchScreen
import com.morecheck.ui.music.MusicAccountScreen
import com.morecheck.ui.music.MusicScreen
import com.morecheck.ui.common.LoginRequiredPane
import com.morecheck.ui.web.BilibiliScreen
import com.morecheck.ui.lab.LabGroupScreen
import com.morecheck.ui.lab.LabScreen
import com.morecheck.ui.lab.efficiencyGroupItems
import com.morecheck.ui.lab.toolboxGroupItems
import com.morecheck.ui.lab.LoginPromptSettingScreen
import com.morecheck.ui.lab.NotebookScreen
import com.morecheck.ui.lab.PomodoroScreen
import com.morecheck.ui.lab.TimetableScreen
import com.morecheck.ui.lab.TodoTodayScreen
import com.morecheck.ui.lab.ScoreScreen
import com.morecheck.ui.lab.UiInspectorScreen
import com.morecheck.ui.lab.WeatherScreen
import com.morecheck.ui.lock.AppLockSettingsScreen
import com.morecheck.ui.lab.openSnake
import com.morecheck.ui.lab.openSudoku
import com.morecheck.ui.lab.openSudoku2
import com.morecheck.ui.lab.tools.ColorCardScreen
import com.morecheck.ui.lab.tools.DecisionMakerScreen
import com.morecheck.ui.lab.tools.ImageCompressScreen
import com.morecheck.ui.lab.tools.MorseCodeScreen
import com.morecheck.ui.lab.tools.RandomNumberScreen
import com.morecheck.ui.agent.AgentChatScreen
import com.morecheck.ui.agent.AiSettingsScreen
import com.morecheck.ui.common.LabSplashHost
import com.morecheck.ui.web.SearchWebScreen
import com.morecheck.ui.cloud.DataSyncScreen
import com.morecheck.ui.settings.ErrorLogScreen
import com.morecheck.ui.settings.PermissionsScreen
import com.morecheck.ui.settings.SoundSettingsScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.data.ChangelogEntryPlacement
import com.morecheck.data.CheckUpdateTarget
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.mine.PRIVACY_TEXT
import com.morecheck.ui.mine.SettingsPage
import com.morecheck.ui.auth.ForgotPasswordScreen
import com.morecheck.ui.auth.SetPasswordScreen
import com.morecheck.ui.auth.LoginScreen
import com.morecheck.ui.auth.PasswordEntryScreen
import com.morecheck.ui.auth.PasswordLoginScreen
import com.morecheck.ui.auth.WechatBindPhoneScreen
import com.morecheck.ui.auth.RegisterScreen
import com.morecheck.ui.records.RecordsScreen
import com.morecheck.ui.scan.ScanScreen
import com.morecheck.ui.settings.AboutScreen
import com.morecheck.ui.settings.ApkDownloadScreen
import com.morecheck.ui.settings.ChangelogScreen
import com.morecheck.ui.tasks.TaskEditScreen
import com.morecheck.ui.tasks.TaskListScreen
import com.morecheck.util.LinkOpenGate
import com.morecheck.util.openInBrowser
import kotlinx.coroutines.launch

/**
 * 页面推入 / 退出的转场时长**默认值**（毫秒）。
 *
 * 微信式节奏：进入下级页面从右向左推入，返回从左向右退出。
 * 这里用「整屏宽度」滑移，动效保持对称，进出都用同一时长，
 * 因此在动画中途反向操作也不会出现速度突变。
 *
 * 2026-10-25 起实际时长**可调**（开发者选项「界面切换动画时长」滑块，
 * 100~600ms）：由 [TabNavHost] 把生效值写进 [NavTransitionGate.durationMs]，
 * 本常量只是 DataStore 首读未到时的初值（= 改造前的固定节奏）。
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
 *
 * ## ⚠️ v1.1.74 需求 8：「页面转场动画」开关
 *
 * 关闭时全部返回 [EnterTransition.None] / [ExitTransition.None]——即**页面直接切换**，
 * 没有任何滑动。四个函数各自**开头**问一次 [NavTransitionGate.enabled]，理由：
 * 它们是**顶层函数**（不是 `@Composable`），拿不到 CompositionLocal；而逐个调用点
 * 加条件会让 30 多处 `composable(...)` 都要改一遍，那是更大的面。
 * 在**转场工厂内部**统一判断，改动面只有这 6 个函数，且新加转场时只需照此写。
 */
private fun pushEnter(): EnterTransition =
    if (!NavTransitionGate.enabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            animationSpec = tween(NavTransitionGate.durationMs),
            initialOffsetX = { fullWidth -> fullWidth }
        )
    }

/** 被覆盖的旧页面原地不动，**不做淡出**（理由见 [pushEnter]）。 */
private fun pushExit(): ExitTransition = ExitTransition.None

/** 返回：当前页面向右滑出（沿原路滑回屏幕右侧），同样不淡出。 */
private fun popExit(): ExitTransition =
    if (!NavTransitionGate.enabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            animationSpec = tween(NavTransitionGate.durationMs),
            targetOffsetX = { fullWidth -> fullWidth }
        )
    }

/** 返回：被让出的下层页面原地不动（它本就完整可见，不需要淡入）。 */
private fun popEnter(): EnterTransition = EnterTransition.None

/**
 * 贪吃蛇页**专属**的转场时长。
 *
 * 与全局 [NAV_DURATION_MS]（280ms）取同量级的下半区：贪吃蛇是"从底部顶上来"的纵向滑移，
 * 纵向动画比横向更容易让人觉得"重"，略短一点（240ms）手感更轻，又不会快到看不清来源。
 * 这里**刻意不复用** [NAV_DURATION_MS]：那条常量是"所有二级页的统一口径"，
 * 一旦改它，全应用的推入/返回节奏都会跟着变；贪吃蛇要单独调速度时也只动这一处。
 */
private const val SNAKE_TRANSITION_MS = 240

/**
 * 贪吃蛇页**专属**的进入动画：从屏幕下沿外向上滑入。
 *
 * `initialOffsetY = { it }` 里的 `it` 是**目标内容的全高**，取满值时初始位置正好落在
 * 屏幕下沿之外，因此页面是从"看不见的下方"整块顶进屏幕的，符合需求
 * 「贪吃蛇页进入动画改为从底部向上滑出」。
 *
 * ## 为什么只给贪吃蛇配这一套，而不是改全局 [pushEnter]
 *
 * 本项目 v1.1.x 的转场是**统一口径**（微信式左右推入，见 [pushEnter] 的注释）。
 * 改 [pushEnter] 会让所有二级页都变成纵向滑移，那是需求没要求的行为变化。
 * 所以这里在 `composable(...)` 的**路由级别**单独覆盖 enter / popExit 两个参数
 * （Nav-Compose 2.8.x 起 `composable` 支持逐路由覆盖四个转场参数）。
 */
private fun snakeEnter(): EnterTransition =
    if (!NavTransitionGate.enabled) {
        EnterTransition.None
    } else {
        slideInVertically(
            animationSpec = tween(SNAKE_TRANSITION_MS),
            // 满高 = 初始位置在屏幕下沿外，向上滑入。
            initialOffsetY = { fullHeight -> fullHeight }
        )
    }

/**
 * 贪吃蛇页返回时的退场动画：往屏幕下方滑出。
 *
 * 与 [snakeEnter] **严格配对**：进入从底部上来、退出回底部去。若只覆盖 enter
 * 而让 popExit 沿用全局的 `slideOutHorizontally`，返回时会出现"新页面（实验室）
 * 从右侧滑回来、旧页面（贪吃蛇）却往下方掉"的方向冲突，观感就是明显的跳变。
 */
private fun snakePopExit(): ExitTransition =
    if (!NavTransitionGate.enabled) {
        ExitTransition.None
    } else {
        slideOutVertically(
            animationSpec = tween(SNAKE_TRANSITION_MS),
            // 满高 = 目标位置在屏幕下沿外，向下滑出。
            targetOffsetY = { fullHeight -> fullHeight }
        )
    }

/**
 * 「页面转场动画」开关的**进程级镜像**（v1.1.74 需求 8）。
 *
 * ## 为什么需要它，而不是直接读 DataStore
 *
 * 本文件里的转场是**顶层函数**（`pushEnter()` / `snakePopExit()` 等），
 * 它们在 `composable(...)` 的参数位置被调用，**不在组合作用域内**，
 * 因此既读不到 `LocalContext` 也读不到 CompositionLocal。
 *
 * 而 `NavHost` 的转场参数是**在组合时求值**的，所以由 [TabNavHost]（Composable）
 * 订阅 DataStore、把生效值写进这里，转场工厂再读这份镜像——
 * 这是"顶层函数读设置"在本项目里成本最低的一种写法。
 *
 * ## 为什么默认 `true`
 *
 * 默认必须等于**改造前的既有观感**（本项目对所有新开关的硬要求）：
 * 在 DataStore 首读到达之前，所有转场照常播放。
 *
 * ## 写入点只有一个
 *
 * [TabNavHost] 里那一句 `NavTransitionGate.enabled = …`。它是普通 `@Volatile var`，
 * 不需要 `MutableState`——转场工厂每次组合都会被重新调用（`composable` 的参数），
 * 而 `@Volatile` 保证跨线程可见（转场可能在非主线程求值）。
 */
internal object NavTransitionGate {
    @Volatile
    var enabled: Boolean = true

    /**
     * 转场时长（毫秒，2026-10-25 新增，随「界面切换动画时长」滑块可调）。
     *
     * 与 [enabled] 同一套写入路径（[TabNavHost] 里的 `SideEffect`）、同一套默认值纪律：
     * 初值 = 改造前的固定节奏 280ms，DataStore 首读到达前所有转场照旧播。
     */
    @Volatile
    var durationMs: Int = 280
}

/**
 * 「检查更新」跳转的目标：GitHub Releases 页。
 *
 * 刻意**固定指向 Releases 列表**而不是某个具体 tag：新版本发布后这里不用跟着改，
 * 用户也总能看到最新一版。
 */
private const val RELEASES_URL = "https://github.com/SciF-Lin/MoreCheck/releases"

/** 「关于我们 → 意见反馈」问卷（2026-11-12）：应用内网页打开，外壳跟随 App。 */
private const val FEEDBACK_SURVEY_URL = "https://wj.qq.com/s2/28659788/m7x0/"

/**
 * 横滑切入口的最小横向位移（v1.1.72，见 `TabRootScreen` 的 `swipeModifier`）。
 *
 * 取 **64dp**：太短（<48dp）会把普通点按 / 轻扫误判成切页；
 * 太长会让用户觉得"滑了没反应"——80dp 在窄屏上已接近屏宽的三分之一，
 * 2026-11-01 用户反馈"四个界面滑不动"，其中一个成因就是这个门槛偏高
 * （配合下面 `TAB_SWIPE_FAR_ENOUGH_RATIO` 的兜底一起调）。
 * （这个值与 `MoreCheckApp.TAB_SWIPE_MIN_DISTANCE` 同名同值——
 * 第一版手势挂在骨架层，迁到本文件后各自需要一份；
 * 两处必须保持一致，改一处时搜另一个文件同名常量。）
 */
private val TAB_SWIPE_MIN_DISTANCE = 64.dp

/**
 * 「已经滑得足够远」的兜底比例（2026-11-01 新增，修「滑不动」）。
 *
 * 消费比例判定（[TAB_SWIPE_CONSUMED_RATIO]）存在误伤：可滚动页面在手指按下
 * 初期会有一两帧被内层消费，一旦凑够 [INNER_CLAIM_MIN_SAMPLES] 就整手势作废，
 * 表现为"怎么滑都不切页"。而用户明确的、超过屏宽这个比例的大幅横滑，
 * 意图已经足够清楚，不应再被"猜内层想不想吃"否决。
 *
 * 取 0.3（约四分之一屏）：比切页门槛 64dp 大得多，只有真正的"划过去"才触发；
 * 结构性闸门 [TabSwipeClaim] 仍然**优先于**本兜底（日历下半区照样不切页）。
 */
private const val TAB_SWIPE_FAR_ENOUGH_RATIO = 0.3f

/**
 * 跟手动画的**启动阈值**（本轮需求：标签切换跟手动画）。
 *
 * 手势横向位移超过它之后才开始上报 [onTabDragUpdate]（页面开始跟手）：
 * 太小（<16dp）会把尚未定下方向的轻扫也变成页面抖动；
 * 取 **24dp**——约为 [TAB_SWIPE_MIN_DISTANCE] 的三分之一，用户能明显感到
 * "页面被我抓住拖动"时仍然留着足够的手势判定余量。
 */
private val TAB_SWIPE_FOLLOW_START_DISTANCE = 24.dp

/**
 * 判定「本次横滑已被内层组件接管」所需的**消费比例**阈值（本轮修复，需求 5）。
 *
 * ## 为什么需要这个判定（真机实测发现的手势打架）
 *
 * 统计页本轮改成「上：月历 / 下：功能栏目」两个 `HorizontalPager`。
 * 在下半留空区左右滑动时，**统计页被切成「发现」页**——内层 Pager 完全没反应。
 *
 * 根因在 `swipeModifier`：它在 `PointerEventPass.Initial` 上观察指针，
 * 拿到起止坐标就在手势结束时自行判定是否切入口，**从不检查内层是否消费了这次拖动**。
 * 于是「内层 Pager 想翻栏目」和「外层想切入口」同向发生，外层总是赢。
 *
 * （原注释里那段"月历翻月 pager 也在 Initial 之后的 pass 工作，我们的手势只是看"
 * 说明当时的观察是**知道**这个重叠的，但把它当成"可接受的取舍"——
 * 实测证明不成立：翻月 pager 至少在月历区域内还抢不到；而本轮新增的下半 Pager
 * 占据整块下半屏，一次横滑同时翻栏目 + 切入口，观感是"滑一下整个人被踢到别的页"。）
 *
 * ## 判定方式
 *
 * 在 `Final` pass 上再读一次事件：`Final` 是在所有子级（Main pass）处理完之后
 * 派发的，此时 `change.isConsumed` 反映的是**内层是否真的拿走了这次拖动**。
 * 只要横向方向上出现了消费，就认为"这次横滑属于内层"，外层不切入口。
 *
 * 阈值用「出现过消费的移动事件数 / 总移动事件数」的比例而不是"任意一次消费"：
 * 滚动容器（LazyColumn / verticalScroll）在纵向拖动时也会消费位置事件，
 * 而那种消费在纯横向拖动里不应出现。要求 [TAB_SWIPE_CONSUMED_RATIO] 以上的
 * 移动帧被消费，才能稳妥地把它归给内层。
 */
private const val TAB_SWIPE_CONSUMED_RATIO = 0.5f

/**
 * 判定「内层接管」所需的最少**被消费移动帧数**（2026-10-22 修复，配合
 * [TAB_SWIPE_CONSUMED_RATIO]）。
 *
 * 取 3：单帧消费可能来自一次偶发的按压/长按指示器，不足以说明"内层在横向拖动"；
 * 连续 3 帧以上被消费，才是一个真实的、内层正在处理的拖动手势。
 * 取更大值会让"外层让步"来得太晚（前几帧页面已经被拖走再弹回，观感是抖动）。
 */
private const val INNER_CLAIM_MIN_SAMPLES = 3

/**
 * 统计页（日历）下半区占屏比例对应的「手势归属登记表」——本轮修复用的**共享状态**。
 *
 * ## 它解决什么
 *
 * 上面的比例判定能覆盖「内层 Pager 消费了拖动」的常见情形，但存在一个盲区：
 * 用户**滑得很快很浅**时，Pager 可能还来不及消费、手指就抬起了，
 * 外层却已判定超过 80dp 而切页。
 *
 * 因此再加一道**结构性**的闸：统计页在下半区挂一个
 * 「本区域正在处理横向手势」的标记，外层看到标记就**直接放弃**切入口判定。
 * 这比"猜消费比例"更可靠，代价只是外层多读一个布尔量。
 *
 * ## 为什么用普通 object + mutableStateOf 而不是 CompositionLocal
 *
 * 它要跨越 `TabRootScreen`（外层手势）与 `CalendarScreen`（内层区域）两个
 *  compose 子树传递，而两者中间隔着 `when (tab)` 的分支——
 * 用 CompositionLocal 要把它从 `TabRootScreen` 一路 provide 到 `CalendarScreen`，
 * 中间那层 `when` 只是转发、没有别的职责。
 * 一个进程内的单例状态量即可：任一时刻只有一页在显示，不存在多页并发读写。
 */
internal object TabSwipeClaim {
    /** 当前是否有内层组件正在接管横向手势。 */
    var claimed by mutableStateOf(false)

    fun claim() { claimed = true }
    fun release() { claimed = false }
}

/**
 * 跟手动画的**位移同步站**（本轮需求：跟手动画的坐标系补偿）。
 *
 * ## 为什么需要它（慢拖不切页的根因）
 *
 * 跟手位移由外层（`MoreCheckApp`）施加在**页面层**的 `graphicsLayer.translationX` 上，
 * 而横滑手势挂的 `TabRootScreen` 根 Box 是页面层的**子孙**——页面被平移后，
 * 手势层所在的局部坐标系**整体跟着移动**：手指在屏幕上不动，`change.position`（局部坐标）
 * 却每帧反向跳变，跳变量恰好等于当前已施加的位移。
 *
 * 后果：手势层用 `position - startX` 算出的"手指位移"被这个漂移污染——
 * 页面被拖出多少 px，测得的位移就被抵消多少 px。慢速拖动帧多、页面位移时间长，
 * 累计位移几乎被完全抵消 → 切页判定永不达成（实测：慢拖 400px 在所有页都不切）。
 *
 * **修法**：外层每帧把已施加的位移写进这里，手势层计算真实位移时把漂移**加回来**：
 * `真实位移 = (当前局部坐标 - 起始局部坐标) + (当前 applied - 按下时 applied)`。
 * 按下时的基准也要参与补偿（新手势可能落在上一次弹回动画尚未结束的时刻）。
 */
internal object TabDragState {
    /** 外层页面当前已施加的跟手位移（px，右正左负），与 `graphicsLayer.translationX` 同源。 */
    var applied by mutableStateOf(0f)
}

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
    /** 云会话被服务端吊销（其它设备登录挤掉）时为真：我的页头部显示「登录过期」。 */
    sessionExpired: Boolean = false,
    momentsBadge: Int,
    onMomentsOpened: () -> Unit,
    /**
     * 主页打卡成功且反馈动画播完后的回调（v1.1.72 需求：完成后跳统计页）。
     *
     * 只在 `tab == AppTab.HOME` 时会被 `HomeScreen` 用到；其余入口收下但不用。
     * "跳到哪"由外层（`MoreCheckApp`）决定——本类不知道底部导航的切换机制。
     */
    onCheckInCompleted: () -> Unit = {},
    /**
     * 「本次会话由桌面快捷方式直达实验室」标志（需求 14）。
     *
     * 由 `MoreCheckRoot` 判定后传下来：**快捷方式带来的导航**（`labShortcutTarget`
     * 消费成功）为 true；用户正常从发现页点进实验室为 false。
     * 贪吃蛇页据此决定返回键的行为（直达会话 → 直接结束 Activity 回桌面）。
     */
    launchedFromLabShortcut: Boolean = false,
    /**
     * 结束整个应用（回桌面）的回调（需求 14）。
     *
     * 只在 `launchedFromLabShortcut = true` 且用户在贪吃蛇页按返回时被调用。
     * 导航层不持有 Activity，"结束"这个动作必须由外层注入
     * （`MoreCheckRoot` 再往上收到 `MainActivity` 传的 `finish()`）。
     */
    onExitApplication: () -> Unit = {},
    /**
     * 一级页内容区的**横滑手势切入口**回调（v1.1.72 需求：底栏四入口支持左右滑动切换）。
     *
     * ⚠️ 手势**只挂在一级页内容区**（`TabRootScreen` 的根 Box，自带底栏让位 padding、
     * 物理上不覆盖底栏）。第一版把满屏手势挂在骨架层（`MoreCheckApp` 的内容层
     * 最外框），z 轴盖住底栏，导致真机上底栏四个 tab 全部点不动——时序竞态吞事件。
     * 挂载点的教训完整记录在 `MoreCheckApp.tabSwipeModifier` 的注释里。
     *
     * 只在根目的地（一级页）生效；二级页不留手势（二级页有自己的交互语义，
     * 且满高盖住底栏时也不该被横滑切走）。
     */
    onTabSwipe: (AppTab) -> Unit = {},
    /**
     * 横滑拖动过程中的**实时位移**回调（本轮需求：标签切换跟手动画）。
     *
     * 参数是当前手势的横向位移（px，右正左负）。只在手势被判定为「横滑切页意图」后
     * （见 [TAB_SWIPE_FOLLOW_START_DISTANCE]）才会开始上报，纵向滚动不会触发。
     *
     * ⚠️ 与 [onTabSwipe] 的分工：拖动中每帧走这里（外层让页面跟手位移）；
     * 松手时若判定切页走 [onTabSwipe]（外层会自行清零拖动位移并播滑入动画），
     * 判定不切页则走 [onTabDragEnd]（外层把页面弹回原位）。
     *
     * ⚠️ 目标入口不存在（最左往右 / 最右往左）时**不上报**——边缘方向没有可去的页，
     * 跟手位移会露出身后没有任何内容的空屏。
     */
    onTabDragUpdate: (Float) -> Unit = {},
    /** 一次跟手拖动**未达成切页**后的收尾回调：外层据此把页面弹回原位。 */
    onTabDragEnd: () -> Unit = {},
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

    // ⚠️ v1.1.74 需求 8：「页面转场动画」开关写入 [NavTransitionGate]。
    //
    // 本函数是**唯一**写入点（见该 object 的 KDoc）。这里能读到仓库，是因为
    // `TabNavHost` 本身是 `@Composable`——而 `pushEnter()` 那些顶层转场工厂不能。
    //
    // initialValue = true（= 改造前观感）：DataStore 首读要走磁盘，冷启动那一瞬
    // 若先按 false 渲染，用户会看到"页面不滑了、随后又恢复滑动"。
    //
    // 用 `SideEffect` 而不是 `LaunchedEffect`：这里是"把组合期读到的值同步出去"，
    // 不涉及挂起与取消语义；`SideEffect` 在每次成功重组后执行，语义正好。
    val devOptions = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    // 设置仓库（本轮需求 2：「检查更新」行为三选一存在这里）。
    // 与 devOptions 分开取：那个是开发者能力的门控，这个是应用级偏好。
    val settingsRepo = remember(context) { context.appContainer.settingsRepository }
    // 2026-11-11 需求 4：「关于我们」右上角的「链接打开位置」。
    // 与开发者选项里那一行读**同一个** DataStore 键，因此两处天然同步；
    // initialValue 与 `LinkOpenGate` 的进程级默认一致（false = 系统浏览器）。
    val linkOpenInApp by settingsRepo.linkOpenInApp
        .collectAsStateWithLifecycle(initialValue = false)
    /** 本 NavHost 内一次性副作用（落盘设置、提示）用的作用域。 */
    val settingsScope = rememberCoroutineScope()
    val pageTransitionAnim by devOptions.pageTransitionAnimEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    SideEffect { NavTransitionGate.enabled = pageTransitionAnim }

    // 2026-10-25：转场时长（「界面切换动画时长」滑块的生效值）写入同一个门闸。
    // initialValue = 改造前固定值，首读未到时节奏不变。
    val navAnimDuration by devOptions.navAnimDurationMs
        .collectAsStateWithLifecycle(initialValue = 280)
    SideEffect { NavTransitionGate.durationMs = navAnimDuration }

    // 2026-10-25：「实验室功能开屏」的生效值与停留时长。
    // duration 的 initialValue 用 -1 当「未就绪」哨兵：DataStore 首读没回来前
    // 不播也不消耗名额（用户可能把时长设成 0 = 未开启，抢播会是错的）。
    // 实验室开屏（2026-10-27 拆成三路）：
    // - `labFeatureSplash`：其他实验室页面（天气 / 记事本 / 课程表 / 今日待办 …）
    // - `labSplashHotSearch`：热搜页单独一个开关
    // - `labSplashDurationMs`：**实验室开屏自己的时长**，不再借用 App 冷启动那个值
    val labFeatureSplash by devOptions.labFeatureSplashEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val labSplashHotSearch by devOptions.labSplashHotSearchEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val labSplashDurationMs by devOptions.labSplashHoldMs
        .collectAsStateWithLifecycle(initialValue = -1)

    // ---- 2026-11-14：官网位模式 / 开屏逐页分类 / 入口统一分派 ----
    val discoverSearchMode by devOptions.discoverSearchMode
        .collectAsStateWithLifecycle(initialValue = false)
    val labSplashDisabledRaw by devOptions.labSplashDisabledPages
        .collectAsStateWithLifecycle(initialValue = "")
    val labSplashDisabled = remember(labSplashDisabledRaw) {
        decodeSplashDisabled(labSplashDisabledRaw)
    }
    /** 逐页开屏：总开关仍是一票否决，再排除被单独关掉的页面。 */
    fun splashOn(routeKey: String): Boolean =
        labFeatureSplash && routeKey !in labSplashDisabled

    // 音乐确认弹窗上移到导航层：入口在发现页 / 实验室都走同一条门禁。
    var showMusicConfirm by remember { mutableStateOf(false) }
    fun launchMusic() {
        settingsScope.launch {
            if (settingsRepo.musicUiTipDismissed.first()) {
                navController.navigate(tab.routeOf(TabDestination.Music))
            } else {
                showMusicConfirm = true
            }
        }
    }

    /**
     * 可搬入口的**唯一**导航分派表。发现页与实验室共用它，
     * 所以入口搬到哪一页都不需要复制一遍 `when`；音乐分支带确认门禁。
     */
    val openEntry: (String) -> Unit = { key ->
        when (key) {
            "search" -> navController.navigate(
                "${tab.name}.search?url=" + android.net.Uri.encode(
                    if (discoverSearchMode) SEARCH_MODE_HOME_URL else ""
                )
            )
            "hot_search" -> navController.navigate(tab.routeOf(TabDestination.HotSearch))
            "bilibili" -> navController.navigate(tab.routeOf(TabDestination.Bilibili))
            "music" -> launchMusic()
            "weather" -> navController.navigate(tab.routeOf(TabDestination.Weather))
            "notebook" -> navController.navigate(tab.routeOf(TabDestination.Notebook))
            "agent" -> navController.navigate(tab.routeOf(TabDestination.Agent))
            "pomodoro" -> navController.navigate(tab.routeOf(TabDestination.Pomodoro))
            "timetable" -> navController.navigate(tab.routeOf(TabDestination.Timetable))
            "todo_today" -> navController.navigate(tab.routeOf(TabDestination.TodoToday))
            "score_stats" -> navController.navigate(tab.routeOf(TabDestination.ScoreStats))
            "github_trending" -> navController.navigate(tab.routeOf(TabDestination.GithubTrending))
            "color_card" -> navController.navigate(tab.routeOf(TabDestination.ColorCard))
            "domain_price" -> navController.navigate(tab.routeOf(TabDestination.DomainPrice))
            "de_ai" -> navController.navigate(tab.routeOf(TabDestination.DeAi))
            "knowledge_qa" -> navController.navigate(tab.routeOf(TabDestination.KnowledgeQa))
            "random_number" -> navController.navigate(tab.routeOf(TabDestination.RandomNumber))
            "morse_code" -> navController.navigate(tab.routeOf(TabDestination.MorseCode))
            "image_compress" -> navController.navigate(tab.routeOf(TabDestination.ImageCompress))
            "decision_maker" -> navController.navigate(tab.routeOf(TabDestination.DecisionMaker))
            "login_prompt_setting" -> navController.navigate(
                tab.routeOf(TabDestination.LoginPromptSetting)
            )
            else -> Unit
        }
    }

    // 2026-11-03（v1.2.5）：登录态。热搜改为登录后可用（用户需求 2），
    // 门禁挂在**路由层**而不是热搜页内部——桌面快捷方式直达热搜路由时同样被拦住。
    // initialValue = null 当「登录态还没从 DataStore 读出来」的哨兵：
    // 冷启动瞬间既不闪门禁也不闪真页面（首读很快，一帧级别的空窗可接受）。
    val loggedIn by remember(context) { context.appContainer.authRepository }
        .isLoggedIn
        .collectAsStateWithLifecycle(initialValue = null)

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
                    sessionExpired = sessionExpired,
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
                    // 2026-10-25：Search 路由带可选 url 参数，导航要给实参。
                    // 2026-11-14：走统一分派，按开发者选项里的「官网 / 搜一搜」模式
                    // 决定首页（官网 = 空 url；搜一搜 = 百度首页）。
                    onOpenSearch = { openEntry("search") },
                    // 2026-10-25：热搜入口从实验室宫格移到发现页（官网下方）。
                    onOpenHotSearch = {
                        navController.navigate(tab.routeOf(TabDestination.HotSearch))
                    },
                    // 2026-11-03（v1.2.5）：B站视频，入口在热搜正下方。
                    onOpenBilibili = {
                        navController.navigate(tab.routeOf(TabDestination.Bilibili))
                    },
                    // 2026-11-05：音乐（发现 → B站视频下方）。
                    onOpenMusic = { launchMusic() },
                    // 可搬入口统一分派（发现页 / 实验室共用）。
                    onOpenMovableEntry = openEntry,
                    // 打卡完成回调透传（见 TabNavHost 的参数说明与 TabRootScreen 里的接线）。
                    onCheckInCompleted = onCheckInCompleted,
                    // 横滑切入口手势（v1.1.72）。⚠️ 挂在**这里**（一级页内容区、
                    // 自带底栏让位 padding）而不是骨架的满屏层——挂满屏会盖住底栏，
                    // 真机上底栏四个 tab 全部点不动（教训见 onTabSwipe 的 KDoc）。
                    onTabSwipe = onTabSwipe,
                    // 跟手动画（本轮需求）：拖动位移实时上报 + 未切页的弹回收尾。
                    onTabDragUpdate = onTabDragUpdate,
                    onTabDragEnd = onTabDragEnd,
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

        // 登录页：真实云登录（手机号 + 真实短信验证码，v1.1.80 起）。由「我的 →
        // 个人信息」「同步管理引导卡」「数据备份云端卡」或开屏引导弹窗推入。
        // 登录成功即回退——本地登录态已落地，首次登录策略弹窗（根骨架托管）会接住后续流程。
        composable(tab.routeOf(TabDestination.Login)) {
            LoginScreen(
                onBack = { navController.popBackStack() },
                onOpenRegister = { navController.navigate(tab.routeOf(TabDestination.Register)) },
                // 「忘记密码」进真实的找回密码页（仅界面演示）。
                onOpenForgotPassword = {
                    navController.navigate(tab.routeOf(TabDestination.ForgotPassword))
                },
                // 「使用密码登录」入口 → 密码登录第一步（只填手机号，界面演示）。
                onOpenPasswordLogin = {
                    navController.navigate(tab.routeOf(TabDestination.PasswordLogin))
                },
                // 微信说明弹窗里的调试入口 → 微信绑定手机号页（v1.2.4 接缝）。
                onOpenWechatBind = {
                    navController.navigate(tab.routeOf(TabDestination.WechatBindPhone))
                }
            )
        }

        // 微信登录 · 绑定手机号（v1.2.4，客户端先行的接缝页）：发码真实、
        // 绑定提交待服务端落地。由登录页微信弹窗的调试入口推入。
        composable(tab.routeOf(TabDestination.WechatBindPhone)) {
            WechatBindPhoneScreen(
                onBack = { navController.popBackStack() }
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

        // 找回密码：手机号 + 短信验证码 → 设置新密码（真实链路，2026-11-03 起）。
        // 成功后与密码登录同一落点：弹回登录流程起点，此时已是登录态。
        composable(tab.routeOf(TabDestination.ForgotPassword)) {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onDone = {
                    val popped = navController.popBackStack(
                        route = tab.routeOf(TabDestination.Login),
                        inclusive = true
                    )
                    if (!popped) navController.popBackStack()
                }
            )
        }

        // 登录密码：已登录账号设置 / 修改密码（「我的信息 → 登录密码」进入）。
        composable(tab.routeOf(TabDestination.SetPassword)) {
            SetPasswordScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
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
        // 同一条页面的「本机备份」标签：老入口（我的信息页 / 通知）继续可用。
        composable(tab.routeOf(TabDestination.Backup)) {
            DataSyncScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = {
                    navController.navigate(tab.routeOf(TabDestination.Login))
                },
                onMessage = onActionMessage,
                initialTab = 1
            )
        }

        // 数据与同步（2026-11-08 需求 5）：原「同步管理」与「数据备份」两个入口合并成一页，
        // 页内两个标签。这条路由落在「云同步」标签（历史入口保持可用）。
        composable(tab.routeOf(TabDestination.SyncManage)) {
            DataSyncScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) },
                onMessage = onActionMessage,
                initialTab = 0
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
                onOpenSound = { navController.navigate(tab.routeOf(TabDestination.TimerSound)) },
                // 2026-11-08 需求 5：同步管理与数据备份合并成「数据与同步」一页。
                onOpenSyncManage = { navController.navigate(tab.routeOf(TabDestination.SyncManage)) },
                // 两个原内联分区改为二级页。
                onOpenReminderEnhance = {
                    navController.navigate(tab.routeOf(TabDestination.ReminderEnhance))
                },
                onOpenPersonalization = {
                    navController.navigate(tab.routeOf(TabDestination.Personalization))
                },
                // 与音乐页右上角头像指向同一页（音乐账号 + 音乐设置）。
                onOpenMusicSettings = {
                    navController.navigate(tab.routeOf(TabDestination.MusicAccount))
                },
                // 2026-10-25：安全 → 开屏密码；通用 → 设备信息查询（自开发者选项迁入）。
                onOpenAppLock = { navController.navigate(tab.routeOf(TabDestination.AppLock)) },
                onOpenUiInspector = {
                    navController.navigate(tab.routeOf(TabDestination.UiInspector))
                },
                // 2026-11-14 需求 3：问题与诊断 → 错误日志。
                onOpenErrorLog = {
                    navController.navigate(tab.routeOf(TabDestination.ErrorLog))
                },
                // v1.1.79：清除缓存完成后用全局 Snackbar 报释放量。
                onMessage = onActionMessage
            )
        }

        // 开屏密码（2026-10-25 需求：设置 → 安全）。运行时门闸在 MainActivity（AppLockGate）。
        composable(tab.routeOf(TabDestination.AppLock)) {
            AppLockSettingsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 设备信息查询（2026-10-25：从「开发者选项」就地覆盖层迁为普通导航目的地）。
        composable(tab.routeOf(TabDestination.UiInspector)) {
            UiInspectorScreen(onBack = { navController.popBackStack() })
        }

        // 错误日志（2026-11-14 需求 3）：读本机 filesDir/logs 的崩溃与报错记录。
        composable(tab.routeOf(TabDestination.ErrorLog)) {
            ErrorLogScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
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
                // 2026-10-22 需求 7：同步管理入口同时出现在「我的信息」页。
                onOpenSyncManage = { navController.navigate(tab.routeOf(TabDestination.SyncManage)) },
                // 2026-11-03：登录密码（设置 / 修改）入口，仅登录后可点。
                onOpenSetPassword = { navController.navigate(tab.routeOf(TabDestination.SetPassword)) },
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
            // 本轮需求 2：「检查更新」按钮的行为改成三选一（默认云端安装包下载页）。
            // 历史布尔开关 `devCheckUpdateInApp` 不再参与分流——它的 UI 入口已换成这里的
            // 三选一，若还让它参与就会出现"选了 A 却执行 B"的错位。
            val checkUpdateTarget by settingsRepo.checkUpdateTarget
                .collectAsStateWithLifecycle(initialValue = CheckUpdateTarget.APK_DOWNLOAD)
            // 「更新日志」入口位置（2026-11-02）：与"检查更新去哪"解耦，由用户单独设置。
            val changelogPlacement by settingsRepo.changelogEntryPlacement
                .collectAsStateWithLifecycle(initialValue = ChangelogEntryPlacement.INSIDE)
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenAgreement = { navController.navigate(tab.routeOf(TabDestination.Agreement)) },
                onOpenPrivacy = { navController.navigate(tab.routeOf(TabDestination.Privacy)) },
                // 「检查更新」：按开发者选项里选的行为分流（见 `CheckUpdateTarget`）。
                onCheckUpdate = {
                    when (checkUpdateTarget) {
                        CheckUpdateTarget.APK_DOWNLOAD ->
                            navController.navigate(tab.routeOf(TabDestination.ApkDownload))

                        // 应用内「官网」加载 Releases 页：URL 编码进查询参数。
                        CheckUpdateTarget.WEB_IN_APP -> navController.navigate(
                            "${tab.name}.search?url=" + android.net.Uri.encode(RELEASES_URL)
                        )

                        CheckUpdateTarget.BROWSER -> openInBrowser(
                            context = context,
                            url = RELEASES_URL,
                            onMessage = onActionMessage
                        )
                    }
                },
                onOpenChangelog = { navController.navigate(tab.routeOf(TabDestination.Changelog)) },
                // 2026-11-02 需求 2：入口位置改成**用户自己选**（开发者选项 → 更新日志入口）。
                // 选「在外面」才在关于页出现；选「在里面」由下载页底部承接。
                showChangelogLink = changelogPlacement == ChangelogEntryPlacement.OUTSIDE,
                // 「意见反馈」问卷：走应用内「官网」同一条 WebView 路由，
                // 页面外壳/返回键/深浅色都跟随 App（不是甩给系统浏览器）。
                onOpenSurvey = {
                    navController.navigate(
                        "${tab.name}.search?url=" + android.net.Uri.encode(FEEDBACK_SURVEY_URL)
                    )
                },
                // 「帮助与反馈」从「关于我们」标题栏的扳手图标进入。
                onOpenFeedback = {
                    navController.navigate("${tab.name}.placeholder/${PlaceholderEntry.FEEDBACK.value}")
                },
                // 2026-11-11 需求 4：下簇「关于我们」→ 应用内「官网」首屏。
                // 路由带空 url，`officialHomeUrl` 会回落 `OFFICIAL_SITE_URL`；
                // 仍走既有的 `search?url=` 通道，不新增路由。
                onOpenOfficialSite = {
                    navController.navigate("${tab.name}.search?url=")
                },
                linkOpenInApp = linkOpenInApp,
                onLinkOpenInAppChange = { wantInApp ->
                    // 先写进程级缓存再落盘：理由与开发者选项那一行完全相同——
                    // 用户拨完可能立刻点链接，不能出现"拨了没生效"的一两帧。
                    LinkOpenGate.inApp = wantInApp
                    settingsScope.launch {
                        settingsRepo.setLinkOpenInApp(wantInApp)
                        onActionMessage(
                            if (wantInApp) "链接已改为应用内打开" else "链接已改为系统浏览器打开"
                        )
                    }
                }
            )
        }

        // 更新日志：内容全部内置，不联网。
        composable(tab.routeOf(TabDestination.Changelog)) {
            ChangelogScreen(onBack = { navController.popBackStack() })
        }

        // 安装包下载（本轮需求 2）：云端托管的正式版 APK 列表 + 下载安装。
        // 需要登录云账号（云存储只对已登录用户开放读），未登录时本页给「去登录」。
        composable(tab.routeOf(TabDestination.ApkDownload)) {
            // 入口是否显示由用户设置决定（2026-11-02 需求 2）：选「在里面」时
            // 固定在屏幕底部，选「在外面」时本页不出现、改由关于页承接。
            val changelogPlacement by settingsRepo.changelogEntryPlacement
                .collectAsStateWithLifecycle(initialValue = ChangelogEntryPlacement.INSIDE)
            ApkDownloadScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) },
                onOpenChangelog = {
                    navController.navigate(tab.routeOf(TabDestination.Changelog))
                },
                showChangelogLink = changelogPlacement == ChangelogEntryPlacement.INSIDE,
                onMessage = onActionMessage
            )
        }

        // 实验室：发现 → 实验室。宫格里「天气」与「开发者选项」都是真实页面，
        // 底部「新增功能」仍只进入留空二级页。
        composable(tab.routeOf(TabDestination.Lab)) {
            LabScreen(
                onBack = { navController.popBackStack() },
                // 2026-10-30：添加桌面快捷方式后要提示成功 / 失败，走全局提示条。
                onMessage = onActionMessage,
                onOpenEntry = { value ->
                    navController.navigate("${tab.name}.placeholder/$value")
                },
                onOpenMovableEntry = openEntry,
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
                },
                // 🔄 v1.1.74 需求 6：宫格第六项「贪吃蛇」改为**独立 Activity + 独立 task**，
                // 因此不再走 `navController.navigate(...)`，而是用显式 Intent 启动
                // `SnakeHostActivity`（见 `ui/lab/GameActivityHost.kt`）。
                //
                // 为什么在这里 `popBackStack()` 不需要：实验室这一格不往 NavHost 里压任何东西，
                // 启动的是**另一个 task**，主 task 的回退栈原样保留。
                // 用户在游戏里按返回 → 结束那个 task → 自动回到实验室。
                //
                // ⚠️ `TabDestination.Snake` 这个枚举项**故意保留**：
                // 它是「桌面快捷方式 = route」的匹配依据，也是 `LabRealPage.SNAKE.key`
                // 必须同名的那个契约锚点（删了会让那条契约失去校验对象）。
                // 快捷方式的分流在 `MainActivity` 里由 `openGameFromLabShortcut` 处理，
                // 不再走到 NavHost。
                onOpenSnake = { openSnake(context) },
                // 需求 17：宫格第七项「数独」，同样改为独立 Activity（口径完全同上）。
                onOpenSudoku = { openSudoku(context) },
                // v1.1.74 需求 1：「数独2」（第二份数独素材），口径与上面两个游戏完全相同。
                // ⚠️ 它在 NavHost 里**没有**对应的 `composable` 目的地，route 枚举项
                // 只作为桌面快捷方式 key 的契约锚点（快捷方式的实际分流见
                // `GameActivityHost.GAME_SHORTCUT_KEYS` 里的 `"sudoku2"` 那一行）。
                onOpenSudoku2 = { openSudoku2(context) },
                // ── v1.1.73 新增五项 ────────────────────────────────────────
                // 番茄时钟：内置的网页工具（assets/pomodoro，WebView 本地加载、不联网）。
                onOpenPomodoro = {
                    navController.navigate(tab.routeOf(TabDestination.Pomodoro))
                },
                // 随机数生成 / 摩斯电码 / 图片压缩 / 做个决定：
                // 四个都是纯 Compose 页面（参考「一个木函」的同类功能），无网页素材。
                onOpenRandomNumber = {
                    navController.navigate(tab.routeOf(TabDestination.RandomNumber))
                },
                onOpenMorseCode = {
                    navController.navigate(tab.routeOf(TabDestination.MorseCode))
                },
                onOpenImageCompress = {
                    navController.navigate(tab.routeOf(TabDestination.ImageCompress))
                },
                onOpenDecisionMaker = {
                    navController.navigate(tab.routeOf(TabDestination.DecisionMaker))
                },
                onOpenColorCard = {
                    navController.navigate(tab.routeOf(TabDestination.ColorCard))
                },
                // v1.1.74 需求 10：宫格「课程表」。
                //
                // 与上面三个游戏**相反**：它留在 NavHost 里做一个普通二级目的地，
                // 不开独立 Activity。理由是它**没有自己的 task 语义**——
                // 游戏是"玩一局就走"的沉浸页，而课程表是"看一眼这周怎么上"的信息页，
                // 属于本应用导航栈的一部分，回到时理应回到实验室页。
                // （三个游戏为什么反过来，见 `ui/lab/GameActivityHost.kt`。）
                onOpenTimetable = {
                    navController.navigate(tab.routeOf(TabDestination.Timetable))
                },
                // v1.1.77 需求 2 → 2026-10-25 调整：宫格「热搜」入口已移到
                // 「发现」页（官网下方），实验室不再传入 onOpenHotSearch。
                // HotSearch 路由本身保留（老桌面快捷方式仍经 fromRouteOrNull 直达）。

                // v1.1.82：宫格新增「今日待办」（内嵌用户的 todo-app，WebView 承载）。
                onOpenTodoToday = {
                    navController.navigate(tab.routeOf(TabDestination.TodoToday))
                },
                // 2026-10-27：宫格新增「GitHub 热门」（端上直连 GitHub Search API）。
                onOpenGithubTrending = {
                    navController.navigate(tab.routeOf(TabDestination.GithubTrending))
                },
                // 2026-10-27：宫格新增「去 AI 味」（内嵌网页应用，云端 LLM 改写）。
                onOpenDeAi = {
                    navController.navigate(tab.routeOf(TabDestination.DeAi))
                },
                // 2026-11-09：宫格新增「知识问答」（内嵌网页应用，云端知识库 + LLM）。
                onOpenKnowledgeQa = {
                    navController.navigate(tab.routeOf(TabDestination.KnowledgeQa))
                },
                // 2026-11-09：宫格新增「域名比价」（Node 服务，从线上地址加载）。
                onOpenDomainPrice = {
                    navController.navigate(tab.routeOf(TabDestination.DomainPrice))
                },
                // 2026-10-28：宫格新增「成绩收集统计」（内嵌成绩应用单页应用，WebView 承载）。
                onOpenScoreStats = {
                    navController.navigate(tab.routeOf(TabDestination.ScoreStats))
                },
                // 2026-11-24：宫格收敛后的两个分组入口。
                onOpenEfficiencyGroup = {
                    navController.navigate(tab.routeOf(TabDestination.EfficiencyGroup))
                },
                onOpenToolboxGroup = {
                    navController.navigate(tab.routeOf(TabDestination.ToolboxGroup))
                }
            )
        }

        // 2026-11-24：实验室的两个分组二级页。子项点击复用上面同一套 navigate，
        // 所以从分组页进入的功能页与从旧宫格进入的完全是同一个目的地，行为一致。
        composable(tab.routeOf(TabDestination.EfficiencyGroup)) {
            LabGroupScreen(
                title = "效率工具",
                subtitle = "随手记录、专注与查询类工具",
                onBack = { navController.popBackStack() },
                items = efficiencyGroupItems(
                    onWeather = { navController.navigate(tab.routeOf(TabDestination.Weather)) },
                    onNotebook = { navController.navigate(tab.routeOf(TabDestination.Notebook)) },
                    onAgent = { navController.navigate(tab.routeOf(TabDestination.Agent)) },
                    onPomodoro = { navController.navigate(tab.routeOf(TabDestination.Pomodoro)) },
                    onTimetable = { navController.navigate(tab.routeOf(TabDestination.Timetable)) }
                )
            )
        }
        composable(tab.routeOf(TabDestination.ToolboxGroup)) {
            LabGroupScreen(
                title = "工具箱",
                subtitle = "几个即开即用的小工具",
                onBack = { navController.popBackStack() },
                items = toolboxGroupItems(
                    onRandomNumber = { navController.navigate(tab.routeOf(TabDestination.RandomNumber)) },
                    onMorseCode = { navController.navigate(tab.routeOf(TabDestination.MorseCode)) },
                    onImageCompress = { navController.navigate(tab.routeOf(TabDestination.ImageCompress)) },
                    onDecisionMaker = { navController.navigate(tab.routeOf(TabDestination.DecisionMaker)) },
                    onColorCard = { navController.navigate(tab.routeOf(TabDestination.ColorCard)) }
                )
            )
        }

        // 课程表：实验室 → 课程表（v1.1.74 需求 10）。
        //
        // 数据全部来自内置素材与 AppContainer 里的 `TimetableRepository`，
        // 不需要从上一个目的地带任何参数，因此这里不需要 `arguments`。
        // `onMessage` 走全局 Snackbar（分享失败时用），
        // 与本页其它"需要反馈"的二级页同一口径。
        composable(tab.routeOf(TabDestination.Timetable)) {
            LabSplashHost(
                routeKey = TabDestination.Timetable.route,
                enabled = splashOn(TabDestination.Timetable.route),
                durationMs = labSplashDurationMs
            ) {
                TimetableScreen(
                    onBack = { navController.popBackStack() },
                    onMessage = onActionMessage
                )
            }
        }

        // 记事本：只有一条笔记，返回时自动保存（没有「保存」按钮）。
        composable(tab.routeOf(TabDestination.Notebook)) {
            LabSplashHost(
                routeKey = TabDestination.Notebook.route,
                enabled = splashOn(TabDestination.Notebook.route),
                durationMs = labSplashDurationMs
            ) {
                NotebookScreen(onBack = { navController.popBackStack() })
            }
        }

        // 智能体：AI 对话（OpenAI 兼容，用户自带 API Key，支持发送图片）。
        composable(tab.routeOf(TabDestination.Agent)) {
            LabSplashHost(
                routeKey = TabDestination.Agent.route,
                enabled = splashOn(TabDestination.Agent.route),
                durationMs = labSplashDurationMs
            ) {
                AgentChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = {
                        navController.navigate(tab.routeOf(TabDestination.AgentSettings))
                    },
                    onMessage = onActionMessage
                )
            }
        }

        // 智能体设置：服务提供商 / API Key / 模型名 / 系统提示词 / 清空记录。
        composable(tab.routeOf(TabDestination.AgentSettings)) {
            AiSettingsScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // ── 贪吃蛇 / 数独 / 数独2：已移出 NavHost（v1.1.74 需求 6、需求 1）────────
        //
        // 这三屏现在是**独立 Activity + 独立 task**（`SnakeHostActivity` /
        // `SudokuHostActivity` / `Sudoku2HostActivity`，见 `ui/lab/GameActivityHost.kt`
        // 与 manifest 里那段关于 taskAffinity 的注释），因此本 NavHost 里**不再注册**它们的
        // `composable` 目的地：宫格那三格改用显式 Intent 启动（见上方 `onOpenSnake`）。
        //
        // ## 为什么不能让 `composable` 留着、只是不跳过去
        //
        // 留着但没人 navigate 它，等于一个永远进不去的 destination：
        // 一旦有任何一条路径 navigate 到它（最典型的就是**桌面快捷方式**），
        // `NavController` 会抛
        // `IllegalArgumentException: Navigation destination that matches request cannot be found`——
        // 直接崩。所以必须是**删掉目的地**而不是"留着不用"。
        //
        // ## 桌面快捷方式那条链路去哪了
        //
        // 快捷方式链路（`ACTION_LAB_SHORTCUT` → `MainActivity.shortcutTargetOf`
        // → `labShortcutTarget` → 骨架消费）现在在 `MainActivity` 里**更前面**被分流：
        // `openGameFromLabShortcut(this, target)` 命中就把对应 Activity 拉起来、
        // 不再设 `labShortcutTarget`（详见那个函数的 KDoc 与 `MainActivity.onCreate`
        // / `onNewIntent` 两处的注释）。应用内导航那条路对本项目里**只有**
        // 贪吃蛇与两个数独这三页是例外，其余页（记事本 / 智能体 / 番茄钟 / …）照旧。
        //
        // ## `TabDestination.Snake` / `.Sudoku` / `.Sudoku2` 枚举项为什么保留
        //
        // 它们是「实验室桌面快捷方式的 key 必须与 route 同名」这条契约的**校验对象**
        // （见 `LabScreen.LabRealPage` 的 KDoc），删掉会让那条契约失去锚点；
        // 而且 `openGameFromLabShortcut` 的映射表里的三个字符串也正是这三个 route。
        //
        // ## `launchedFromLabShortcut` / `onExitApplication` 现在还有谁用
        //
        // 需求 14 那条「快捷方式直达的会话里，贪吃蛇页返回键直接回桌面」的分支
        // 随上面两个 `composable` 一起移走了。**形参本身保留**：
        // 它是 `MoreCheckRoot` 的公开契约（骨架 → NavHost 的一条既有接线），
        // 删它要同时改骨架，而骨架不在本任务的改动范围内；保留一个当前无人消费的
        // 形参是有意的（骨架那侧仍按原样传值，删一端反而会让两处失配）。

        // ── v1.1.73 新增五项 ──────────────────────────────────────────
        //
        // 番茄时钟：网页工具（assets/pomodoro）。走应用内导航（**不**像贪吃蛇 /
        // 数独那样拆独立 Activity）。
        //
        // 🔄 2026-10-22 需求 4：「别归类为游戏」。
        // 此前它借用游戏那套转场（从屏幕下沿滑入 / 下滑出）与「游戏标题栏显示」开关，
        // 用户在开发者选项里关掉游戏标题栏时，番茄钟会莫名其妙跟着没标题栏。
        // 现在它**与其它全屏工具页完全同构**：
        // - 转场用 `composable` 的默认推入（与随机数 / 摩斯电码等一致）；
        // - 标题栏恒显示（不再订阅任何开发者开关）；
        // - 颜色跟随应用主题（已在上文 `gameChromeMode` 剥离时完成）。
        composable(route = tab.routeOf(TabDestination.Pomodoro)) {
            LabSplashHost(
                routeKey = TabDestination.Pomodoro.route,
                enabled = splashOn(TabDestination.Pomodoro.route),
                durationMs = labSplashDurationMs
            ) {
                PomodoroScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // 随机数生成（纯 Compose 页面）。
        composable(tab.routeOf(TabDestination.RandomNumber)) {
            LabSplashHost(
                routeKey = TabDestination.RandomNumber.route,
                enabled = splashOn(TabDestination.RandomNumber.route),
                durationMs = labSplashDurationMs
            ) {
                RandomNumberScreen(
                    onBack = { navController.popBackStack() },
                    onMessage = onActionMessage
                )
            }
        }

        // 摩斯电码。
        composable(tab.routeOf(TabDestination.MorseCode)) {
            LabSplashHost(
                routeKey = TabDestination.MorseCode.route,
                enabled = splashOn(TabDestination.MorseCode.route),
                durationMs = labSplashDurationMs
            ) {
                MorseCodeScreen(
                    onBack = { navController.popBackStack() },
                    onMessage = onActionMessage
                )
            }
        }

        // 图片压缩。
        composable(tab.routeOf(TabDestination.ImageCompress)) {
            LabSplashHost(
                routeKey = TabDestination.ImageCompress.route,
                enabled = splashOn(TabDestination.ImageCompress.route),
                durationMs = labSplashDurationMs
            ) {
                ImageCompressScreen(
                    onBack = { navController.popBackStack() },
                    onMessage = onActionMessage
                )
            }
        }

        // 做个决定。
        composable(tab.routeOf(TabDestination.DecisionMaker)) {
            LabSplashHost(
                routeKey = TabDestination.DecisionMaker.route,
                enabled = splashOn(TabDestination.DecisionMaker.route),
                durationMs = labSplashDurationMs
            ) {
                DecisionMakerScreen(
                    onBack = { navController.popBackStack() },
                    onMessage = onActionMessage
                )
            }
        }

        // 色卡（2026-11-02）：776 个传统色 / 和色，自包含网页。
        composable(tab.routeOf(TabDestination.ColorCard)) {
            LabSplashHost(
                routeKey = TabDestination.ColorCard.route,
                enabled = splashOn(TabDestination.ColorCard.route),
                durationMs = labSplashDurationMs
            ) {
                ColorCardScreen(onBack = { navController.popBackStack() })
            }
        }

        // 登录提示功能：只有一个开关（是否在打开软件时提示登录）。
        composable(tab.routeOf(TabDestination.LoginPromptSetting)) {
            LabSplashHost(
                routeKey = TabDestination.LoginPromptSetting.route,
                enabled = splashOn(TabDestination.LoginPromptSetting.route),
                durationMs = labSplashDurationMs
            ) {
                LoginPromptSettingScreen(onBack = { navController.popBackStack() })
            }
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
            LabSplashHost(
                routeKey = TabDestination.Weather.route,
                enabled = splashOn(TabDestination.Weather.route),
                durationMs = labSplashDurationMs
            ) {
                WeatherScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPermissionSettings = {
                        navController.navigate(tab.routeOf(TabDestination.Permissions))
                    },
                    onMessage = onActionMessage
                )
            }
        }

        // 热搜（v1.1.77 需求 2）：实验室占位入口升级为真实页面。
        // 联网拉取各大平台热搜（CloudBase 云函数），失败回退内置快照。
        // 路由沿用原占位条目的 value（"hot_search"），
        // 老的桌面快捷方式无需重建即可直达。
        // 2026-11-03（v1.2.5，用户需求 2）：热搜改为**登录后可用**——
        // 门禁在本路由层：未登录显示 [LoginRequiredPane]（去登录 → 登录页），
        // 登录成功后登录页回退，登录态翻转，这里自动换成真页面。
        composable(tab.routeOf(TabDestination.HotSearch)) {
            when (loggedIn) {
                // 登录态还没读出来：不渲染（一帧级空窗，避免闪错内容）。
                null -> Unit
                true -> LabSplashHost(
                    routeKey = TabDestination.HotSearch.route,
                    // 热搜用**自己的**开关（用户点名要单独控制它）。
                    enabled = labSplashHotSearch,
                    durationMs = labSplashDurationMs
                ) {
                    HotSearchScreen(
                        onBack = { navController.popBackStack() },
                        onMessage = onActionMessage
                    )
                }
                // 未登录：门禁面板。
                false -> LoginRequiredPane(
                    featureName = "热搜",
                    onLogin = { navController.navigate(tab.routeOf(TabDestination.Login)) }
                )
            }
        }

        // B站视频（2026-11-03 v1.2.5 初版；v1.2.7 原生改造）：
        // 原生浏览（推荐/热门/搜索）+ 原生详情 + WebView 播放，下载入口只在播放页顶栏、
        // 可选清晰度。链路见 `ui/web/BilibiliScreen.kt` 与 `data/net/BilibiliRepository.kt` 的 KDoc。
        composable(tab.routeOf(TabDestination.Bilibili)) {
            BilibiliScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 音乐（2026-11-05）：发现 → 音乐。联网搜索 + 在线播放 + 本机收藏 + 离线缓存。
        // 播放状态在进程内单例里（可后台播放），链路见 ui/music/MusicScreen.kt 的 KDoc。
        composable(tab.routeOf(TabDestination.Music)) {
            // 2026-11-09：底栏「我的」改为**同一页内的第二段内容**（不再整屏覆盖，
            // 右上角头像入口也已去掉）；MusicAccount 路由只留给设置页的「音乐」入口。
            MusicScreen(onBack = { navController.popBackStack() })
        }

        // 音乐账号与设置：账号登录 / 退出 + 后台播放开关 + 音乐缓存清理。
        composable(tab.routeOf(TabDestination.MusicAccount)) {
            MusicAccountScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        // 打卡提醒增强 / 个性化（2026-11-08 需求 5）：从设置页内联分区搬成独立页。
        composable(tab.routeOf(TabDestination.ReminderEnhance)) {
            ReminderEnhanceScreen(onBack = { navController.popBackStack() })
        }

        composable(tab.routeOf(TabDestination.Personalization)) {
            PersonalizationScreen(onBack = { navController.popBackStack() })
        }

        // 今日待办（v1.1.82）：实验室宫格新增，内嵌用户的 todo-app 单页应用。
        // WebView 加载 assets/todo/index.html；登录 / 数据都走 WorkBuddy 云
        // （endpoint 固化在 assets 副本里，CORS 已实测放行 file:// 的 opaque origin）。
        // 页面自己有标题栏，页面本体不再叠加原生控件；BackHandler 在屏幕内部处理返回。
        // GitHub 热门（2026-10-27）：实验室 → GitHub 热门。
        //
        // 数据端上直连 `api.github.com/search/repositories`：一个公开 GET，
        // 不必过本项目自己的云函数（那是为"抓八个平台"建的）。
        // 匿名限流 10 次/分钟，所以页面内部按「周期 + 语言」做了 10 分钟内存缓存。
        // 去 AI 味（2026-10-27）：实验室 → 去 AI 味。
        //
        // 内嵌用户自建的「去AI味改写」网页应用（assets/deai），改写走 WorkBuddy 云 LLM。
        // 页面 origin 必须映射成云应用自己的域名，否则网关按白名单 403（见 DeAiScreen）。
        composable(tab.routeOf(TabDestination.DeAi)) {
            DeAiScreen(onBack = { navController.popBackStack() })
        }

        // 知识问答（2026-11-09）：实验室 → 知识问答。
        //
        // 内嵌用户自建的「知识问答 Agent」网页应用（assets/knowledge_qa），
        // 检索走该云应用的数据库、作答走云端 LLM；同样必须把 origin 映射成
        // 云应用自己的域名，否则网关按白名单 403（见 KnowledgeQaScreen）。
        composable(tab.routeOf(TabDestination.KnowledgeQa)) {
            KnowledgeQaScreen(onBack = { navController.popBackStack() })
        }

        // 域名比价（2026-11-09）：实验室 → 域名比价。
        // 与其他网页工具的差别：它有一个 Node 后端（SSE 抓价），页面直接从线上加载。
        composable(tab.routeOf(TabDestination.DomainPrice)) {
            DomainPriceScreen(onBack = { navController.popBackStack() })
        }

        composable(tab.routeOf(TabDestination.GithubTrending)) {
            GithubTrendingScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage
            )
        }

        composable(tab.routeOf(TabDestination.TodoToday)) {
            LabSplashHost(
                routeKey = TabDestination.TodoToday.route,
                enabled = splashOn(TabDestination.TodoToday.route),
                durationMs = labSplashDurationMs
            ) {
                TodoTodayScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // 成绩收集统计（2026-10-28：内嵌成绩应用单页应用，WebView 承载）。
        //
        // 与「今日待办」同构：assets 经 WebViewAssetLoader 映射到应用自己的域名，
        // 登录态存在该 origin 的 localStorage 里；不接 LabSplashHost，
        // 因为页面自带启动骨架屏（闪过两层的观感更差）。
        composable(tab.routeOf(TabDestination.ScoreStats)) {
            ScoreScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // 知识问答（2026-10-22）：已并入「智能体」页的双模式，不再单独一个目的地。
        // 保留这条注释是给"找 knowledge_qa 路由"的人一个明确的路标。

        // 官网（原「看一看」）：内嵌网页浏览，默认打开官网 https://www.scif.top/。
        // 2026-10-25：路由带可选 url 参数（「检查更新应用内打开」用它直达 Releases）。
        composable(
            route = tab.routeOf(TabDestination.Search),
            arguments = listOf(
                navArgument(TabDestination.ARG_URL) {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            SearchWebScreen(
                onBack = { navController.popBackStack() },
                onMessage = onActionMessage,
                initialUrl = entry.arguments?.getString(TabDestination.ARG_URL)
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

    // 音乐确认弹窗挂在 NavHost 之外：入口在发现页或实验室都能覆盖到，
    // 且确认之前不会导航，背景是当前页面（不是先渲一层黑底的音乐页）。
    if (showMusicConfirm) {
        MusicEntryConfirmDialog(
            onCancel = { showMusicConfirm = false },
            onConfirm = { dontShowAgain ->
                showMusicConfirm = false
                settingsScope.launch {
                    if (dontShowAgain) settingsRepo.setMusicUiTipDismissed(true)
                    navController.navigate(tab.routeOf(TabDestination.Music))
                }
            }
        )
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
    sessionExpired: Boolean = false,
    navController: NavHostController,
    onOpenRecords: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenMoments: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenSearch: () -> Unit,
    /** 打开「热搜」（2026-10-25 需求：入口从实验室宫格移到发现页）。 */
    onOpenHotSearch: () -> Unit = {},
    /** 打开「B站视频」（2026-11-03：入口在热搜正下方，v1.2.5）。 */
    onOpenBilibili: () -> Unit = {},
    /** 打开「音乐」（2026-11-05：入口在 B站视频正下方）。 */
    onOpenMusic: () -> Unit = {},
    /** 入口统一分派（可搬入口 / 音乐门禁）。 */
    onOpenMovableEntry: (String) -> Unit = {},
    onNewTask: () -> Unit,
    /**
     * 主页打卡成功且反馈动画播完后的回调（v1.1.72 需求：完成后跳统计页）。
     *
     * 只在 `tab == AppTab.HOME` 时会被 `HomeScreen` 用到；其余入口收下但不用。
     * "跳到哪"由外层（`MoreCheckApp`）决定——本类不知道底部导航的切换机制。
     */
    onCheckInCompleted: () -> Unit = {},
    /**
     * 横滑切入口的手势回调（v1.1.72）。
     *
     * ⚠️ 手势**只挂在本组件的根 Box**：这里自带底栏让位 padding，物理上不覆盖
     * 底栏——第一版手势挂在骨架的满屏层，z 轴盖住底栏，真机上底栏点不动
     * （教训见 `MoreCheckApp.tabSwipeModifier` 与 `onTabSwipe` 的 KDoc）。
     */
    onTabSwipe: (AppTab) -> Unit = {},
    /**
     * 拖动跟手位移的实时回调（本轮需求）。参数与收尾语义见
     * [TabNavHost] 的 `onTabDragUpdate` KDoc——由它透传。
     */
    onTabDragUpdate: (Float) -> Unit = {},
    /** 一次跟手拖动未达成切页后的弹回收尾（由 [TabNavHost] 透传）。 */
    onTabDragEnd: () -> Unit = {},
    maskedPhone: String?,
    momentsBadge: Int
) {
    // 横滑手势判定（v1.1.72）：
    // - 挂在根 Box（内容区），**物理上不覆盖底栏**——底栏的点击区域在本组件之外；
    // - 只认横向位移 ≥ [TAB_SWIPE_MIN_DISTANCE] 且明显大于纵向的滑动；
    //
    // ⚠️ 为什么不用 `waitForUpOrCancellation()`（第一版踩的坑）：
    // 它在**任何**消费者拿走事件时就返回 null——而一级页内容几乎总是包着
    // `verticalScroll` / LazyColumn，**纵向滚动会消费 move**，于是"横着滑"
    // 也被它判定为"被消费了"而放弃，滑动切页在内容可滚的页面上永远不触发
    // （实测：主页 / 我的页全失效）。
    //
    // 修法：在 **Initial pass** 手工跟踪指针（`awaitPointerEvent(PointerEventPass.Initial)`）。
    // Initial pass 先于子级的 Main pass 派发，**子级消费不影响这里收到事件**；
    // 我们全程不消费任何事件（change.consume() 一次都不调），所以内层组件
    // 的滚动 / 点击完全不受影响——两层互不干扰。月历翻月 pager 也在
    // Initial 之后的 pass 工作，它的横滑我们同样只是"看"：看总位移后
    // 自行判定是否达到切页阈值（阈值 80dp 远大于翻月一页的触发量，
    // 且翻月动画照常进行——两者会同时发生，这是可接受的取舍，
    // 实测不冲突：翻月需要停在月历区域，切页手势通常发生在内容任意处）。
    //
    // 切换目标 = 当前入口的左 / 右邻位（`AppTab.entries` 顺序 = 底栏从左到右）。
    val swipeModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            // Initial pass 等待按下（子级消费与否不影响）。
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            val startY = down.position.y
            var upX = 0f
            var upY = 0f
            var sawUp = false
            // ── 「内层是否接管了这次拖动」的观测（本轮修复，理由见 [TAB_SWIPE_CONSUMED_RATIO]）──
            var moveEvents = 0
            var consumedMoves = 0
            // ── 跟手动画状态（本轮需求）───────────────────────────────────────
            // `following`：已超过启动阈值、页面正在跟手。之后每帧上报位移，
            // 直到抬起（上报 0 由外层弹回 / 切页）。
            // ⚠️ 边缘方向（最左往右 / 最右往左）没有可去的入口，从头到尾不上报。
            var following = false
            var followStartX = 0f
            var edgeBlocked = false
            // ⚠️ 「这次手势已被内层接管」一旦成立就**不可撤销**（本轮修复）：
            // 原实现只在消费的那一帧把 following 置 false，下一帧位移又满足条件时
            // 会**重新**把 following 置 true → 再消费 → 再回弹……于是"跟手 → 回弹"
            // 每帧交替，表现为整屏闪烁 + 页面原地抖动（长按慢拖时必然踩到）。
            var innerClaimed = false
            // 按下时刻外层已施加的位移基准（坐标系漂移补偿，见 [TabDragState] KDoc）。
            val appliedAtDown = TabDragState.applied
            // 跟踪到抬起为止：**全程不消费**，只读位置。
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (change.changedToUp()) {
                    upX = change.position.x
                    upY = change.position.y
                    sawUp = true
                    break
                }
                // ── 跟手位移上报（本轮需求）─────────────────────────────────
                //
                // ⚠️ 三个前置条件缺一不可：
                // ① 横向位移明显大于纵向（与最终切页判定同一口径，否则纵向滚动列表
                //    会把页面拽出屏幕）；② 位移已超过 [TAB_SWIPE_FOLLOW_START_DISTANCE]
                //    （轻扫不抖页）；③ 当前入口在拖动方向上有相邻入口（边缘不上报）。
                //
                // 上报的位移做**半幅钳制**：超过半屏宽后按比例衰减（每超出 1px 只多移
                // 0.4px），既保留"拽到底"的跟随感，又避免页面被完全拖出屏幕露出空底。
                //
                // ⚠️ 位移一律走 `trueDx`（含坐标系漂移补偿）：
                // 跟手期间页面（手势层的祖先）被外层平移，局部坐标会反向跳变，
                // 见 [TabDragState] KDoc 的完整推理——**所有**位移判定（跟手启动、
                // 最终切页）都必须用补偿后的值，否则慢速拖动不切页（实测踩过）。
                val trueDx = (change.position.x - startX) + (TabDragState.applied - appliedAtDown)
                val trueDy = change.position.y - startY
                // 本帧是不是「横向为主」。消费统计只认这种帧（2026-11-01）：
                // 分母若把纵向滚动帧也算进去，一次"先竖着滚一下再横滑"的手势
                // 消费比例会被抬高到阈值以上，整手势被判给内层、横滑失效。
                val horizontalFrame = kotlin.math.abs(trueDx) > kotlin.math.abs(trueDy)
                if (!following && !edgeBlocked && !innerClaimed) {
                    val index = AppTab.entries.indexOf(tab)
                    val hasNeighbor =
                        if (trueDx < 0) index < AppTab.entries.lastIndex else index > 0
                    if (!hasNeighbor) {
                        edgeBlocked = true
                    } else if (kotlin.math.abs(trueDx) > TAB_SWIPE_FOLLOW_START_DISTANCE.toPx() &&
                        kotlin.math.abs(trueDx) > kotlin.math.abs(trueDy) * 2f
                    ) {
                        following = true
                        // 跟手从 0 位移起算：起始局部坐标取"补偿后等于 0"的那一点，
                        // 即 `当前局部坐标 + 当前已施加位移`（手指此刻的真实窗口位置）。
                        followStartX = change.position.x + TabDragState.applied
                    }
                }
                if (following) {
                    val raw = change.position.x + TabDragState.applied - followStartX
                    val half = size.width / 2f
                    val clamped = if (kotlin.math.abs(raw) <= half) {
                        raw
                    } else {
                        // 超出半屏后阻尼跟随。
                        val sign = if (raw < 0) -1f else 1f
                        sign * (half + (kotlin.math.abs(raw) - half) * 0.4f)
                    }
                    onTabDragUpdate(clamped)
                }
                // ── Final pass 复核消费状态（本轮新增）─────────────────────────
                //
                // ⚠️ 为什么必须在 **Final** pass 再读一次：
                // Initial 是最早的一轮派发，那时内层还没处理（也就还没消费）；
                // 只有 Final（所有子级 Main 处理完之后）的 `isConsumed`
                // 才真实反映"内层拿走了这次拖动"。在原实现里读 Initial 的
                // `isConsumed` 永远为 false，所以那段兜底分支实际从未生效。
                val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                val finalChange = finalEvent.changes.firstOrNull()
                if (finalChange != null && finalChange.position != change.position) {
                    // 事件已经结束（指针在 Final 之前消失）：不再计数。
                } else if (horizontalFrame) {
                    // ⚠️ 只统计「横向为主」的帧：纵向滚动消耗掉的帧不算进比例，
                    // 否则一次竖滚 + 横滑的手势会被误判成"内层接管"（2026-11-01）。
                    moveEvents++
                    if (finalChange?.isConsumed == true) {
                        consumedMoves++
                        // ⚠️ 内层确实接管（如统计页下半 Pager / 月历翻月）时立即停止跟手：
                        // 继续上报会让"内层翻栏目"与"外层页面位移"同时发生，
                        // 正是 5.16 记录过的双重响应。位移回 0 由末尾统一处理。
                        //
                        // 判定门槛：
                        // - 至少 [INNER_CLAIM_MIN_SAMPLES] 帧被消费，且消费比例过半，
                        //   才算"这次横滑属于内层"。只看单帧会被一次偶发消费误判，
                        //   一旦误判整个手势就废了（表现为"滑不动"）；
                        // - 认领后**不再撤销**（innerClaimed），否则跟手/回弹会逐帧交替。
                        if (!innerClaimed &&
                            consumedMoves >= INNER_CLAIM_MIN_SAMPLES &&
                            consumedMoves * 2 >= moveEvents
                        ) {
                            innerClaimed = true
                            if (following) {
                                following = false
                                onTabDragEnd()
                            }
                        }
                    }
                }
            }
            if (!sawUp) {
                // 指针异常消失（系统取消）：若正在跟手，必须把页面弹回原位。
                if (following) onTabDragEnd()
                return@awaitEachGesture
            }
            // ⚠️ 抬起坐标同样要做坐标系漂移补偿（跟手期间页面被外层平移），
            // 见 [TabDragState] KDoc——否则慢拖的真实位移被抵消、切页判定失效。
            val dragX = (upX - startX) + (TabDragState.applied - appliedAtDown)
            val dragY = upY - startY
            val horizontal = kotlin.math.abs(dragX) > TAB_SWIPE_MIN_DISTANCE.toPx() &&
                kotlin.math.abs(dragX) > kotlin.math.abs(dragY) * 2f
            if (!horizontal) {
                // 未达切页标准：正在跟手就弹回（含内层接管后已停跟手的情形——
                // 那时 onTabDragEnd 已发过一次，这里 following 已为 false，不会重复）。
                if (following) onTabDragEnd()
                return@awaitEachGesture
            }

            // ── 闸门一：统计页下半区已声明接管本次手势 ──────────────────────────
            if (TabSwipeClaim.claimed) {
                if (following) onTabDragEnd()
                return@awaitEachGesture
            }

            // ── 闸门二：内层在横向拖动里确实消费了足够多的移动帧 ─────────────────
            //
            // `moveEvents == 0` 表示一次移动都没观察到（纯点按 / 系统取消），
            // 此时按"没被接管"处理（保留原有的切页能力）；
            // 否则要求消费比例低于阈值才判定为"这次横滑属于外层"。
            if (moveEvents > 0) {
                val consumedRatio = consumedMoves.toFloat() / moveEvents.toFloat()
                // 大幅横滑豁免（2026-11-01，修「滑不动」）：滑过屏宽 [TAB_SWIPE_FAR_ENOUGH_RATIO]
                // 时用户的意图已经明确，不应再被"猜内层想不想吃"否决。
                // 结构性闸门 [TabSwipeClaim]（日历下半区）在上面已经拦过，
                // 因此这里放行的只是"内层恰好也消费了几帧"的误判场景。
                val farEnough =
                    kotlin.math.abs(dragX) > size.width.toFloat() * TAB_SWIPE_FAR_ENOUGH_RATIO
                if (consumedRatio >= TAB_SWIPE_CONSUMED_RATIO && !farEnough) {
                    if (following) onTabDragEnd()
                    return@awaitEachGesture
                }
            }

            val currentIndex = AppTab.entries.indexOf(tab)
            val targetIndex = if (dragX < 0) currentIndex + 1 else currentIndex - 1
            if (targetIndex in AppTab.entries.indices) {
                // 切页成立：**不**发 onTabDragEnd——外层在 tabSwitchTo 里自行把
                // 跟手位移接管过去（旧页按当前位移滑出 / 新页滑入），位移在
                // 外层一次收口，避免"先弹回再滑入"的两段跳变。
                onTabSwipe(AppTab.entries[targetIndex])
            } else {
                if (following) onTabDragEnd()
            }
        }
    }
    // 手势层包住整个一级页内容：本组件（TabRootScreen）只被根目的地组合、
    // 自带底栏让位 padding，因此手势区域**物理上不含底栏**（教训见 onTabSwipe KDoc）。
    Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
        when (tab) {
        AppTab.HOME -> com.morecheck.ui.home.HomeScreen(
            onOpenScanner = onOpenScanner,
            onOpenRecords = onOpenRecords,
            onNewTask = onNewTask,
            onCheckInCompleted = onCheckInCompleted
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
            onOpenHotSearch = onOpenHotSearch,
            onOpenBilibili = onOpenBilibili,
            onOpenMusic = onOpenMusic,
            onOpenMovableEntry = onOpenMovableEntry,
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
            // 2026-10-22 需求 7 / 8：同步管理与数据备份已移出本页（见设置页 / 我的信息）。
            maskedPhone = maskedPhone,
            sessionExpired = sessionExpired
        )
        }
    }
}
