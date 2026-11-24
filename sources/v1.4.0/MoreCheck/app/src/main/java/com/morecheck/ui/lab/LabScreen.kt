package com.morecheck.ui.lab

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MainActivity
import com.morecheck.R
import com.morecheck.data.SettingsRepository
import com.morecheck.di.appContainer
import com.morecheck.ui.common.TextInputDialog
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.nav.PlaceholderEntry
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.ui.nav.EntryPlacement
import com.morecheck.ui.nav.MOVABLE_ENTRIES
import com.morecheck.ui.nav.decodeEntryPlacement
import com.morecheck.ui.nav.effectivePlacement
import com.morecheck.ui.nav.encodeEntryPlacement
import com.morecheck.util.findActivity
import com.morecheck.util.rememberIsCompactWindow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 实验室里的一个功能入口。
 *
 * 宫格里现在有两类条目，用 [target] 区分：
 * - [LabTarget.Placeholder]：**尚未实现**的功能（[PlaceholderEntry]），点开进通用留空二级页；
 * - [LabTarget.RealPage]：**已经做出来**的页面（目前只有「天气」），点开进真实路由。
 *
 * 没有把「真实页」也塞进 [PlaceholderEntry]：那个枚举同时承担着
 * 「我的 → 基础资料」等**其它页面**的占位文案，往里加一条「天气」
 * 会让 `fromValue()` 在别处也能查到它，语义就混了。
 * 也没有把所有条目压成「一个裸 lambda」，那样标题就得各写一遍、
 * 与 [PlaceholderEntry.title] 产生两份来源，迟早对不上。
 */
private sealed interface LabTarget {
    /** 走通用留空二级页；`value` 即 [PlaceholderEntry.value]，用作路由参数。 */
    data class Placeholder(val entry: PlaceholderEntry) : LabTarget

    /** 走已经接好的真实路由（由调用方通过 `onOpen*` 回调导航）。 */
    data class RealPage(val page: LabRealPage) : LabTarget

    /** 从发现页搬过来的入口：由导航层的统一分派表打开（2026-11-14）。 */
    data class ExternalEntry(val key: String) : LabTarget
}

/**
 * 实验室里**已经有真实页面**的条目。
 *
 * 本轮之前只有「天气」一项，所以 [LabTarget.RealPage] 写的是 `data object`，
 * 分发处直接调 `onOpenWeather()`。加上第二项「开发者选项」后 `data object`
 * 就分不清该调哪个回调了，于是改成枚举。
 *
 * 用枚举而不是「每个条目自带一个 lambda」的好处：以后再加真实页面时，
 * 分发处的 `when` 会因不完备而**编译报错**，逼着接线处补分支。
 * 这正是本项目 `TabDestination` 注释里写的那套防漏机制（有意为之）。
 *
 * [key] 是每个真实页的**稳定字符串标识**，供「长按 → 添加桌面快捷方式」使用
 * （见 [pinLabShortcut]）。刻意手写字符串、而**不是**用 `name` 或 `ordinal`：
 * - `ordinal` 完全不能用——枚举项的先后顺序是最容易被无意调整的东西，
 *   一旦有人在中间插一项，所有已 pin 到桌面的快捷方式会集体指向**错的页面**，
 *   而且这种错在编译期毫无提示；
 * - `name`（如 `DEVELOPER_OPTIONS`）虽然稳定，但它跟路由名对不上，
 *   排查时要多绕一层。
 *
 * 取值**有意与 `TabDestination` 的 route 保持一致**（`weather` / `developer_options` / …）：
 * 这样「快捷方式带了哪个参数」与「该去哪个路由」一眼能对上。
 * 为什么不直接引用 `TabDestination.*.route`：那是导航层的事，本页只负责
 * 「点哪个格子 → 给一个标识」，引进来会让这个私有枚举平白依赖导航层；
 * 两边如果哪天真的不一致，下面的 `when` 分发处就在眼前，反而更容易发现。
 *
 * 与 [PlaceholderEntry] 分开是有意的：那个枚举同时承担「我的 → 基础资料」等
 * 其它页面的占位文案，把真实页面塞进去会让 `fromValue()` 在别处也能查到它，语义就混了。
 */
private enum class LabRealPage(val key: String) {
    /** 天气（联网查询当地天气）。 */
    WEATHER("weather"),

    /** 开发者选项（仿手机开发者选项的调试页）。 */
    DEVELOPER_OPTIONS("developer_options"),

    /** 登录提示功能（控制「打开软件时提示登录」那个引导弹窗的开关）。 */
    LOGIN_PROMPT("login_prompt_setting"),

    /** 记事本（一条笔记：文字 + 图片，返回时自动保存）。 */
    NOTEBOOK("notebook"),

    /** 智能体（AI 对话，OpenAI 兼容，用户自带 API Key）。 */
    AGENT("agent"),

    /** 贪吃蛇（assets/snake 下的自包含网页小游戏）。 */
    SNAKE("snake"),

    /**
     * 数独（assets/sudoku 下的自包含单文件）。
     * 页面用 Blob Worker 生成题目，需要 https origin（走 WebViewAssetLoader，见 SudokuScreen）。
     */
    SUDOKU("sudoku"),

    /**
     * 数独2（assets/sudoku2，与 [SUDOKU] 并列的第二份素材，非替代关系）。
     * 无 Worker，走 file:///android_asset/。
     */
    SUDOKU2("sudoku2"),

    /** 番茄时钟（assets/pomodoro 下的自包含单文件，file:// 加载）。 */
    POMODORO("pomodoro"),

    /** 随机数生成（纯 Compose 页面：范围 / 个数 / 去重 / 历史）。 */
    RANDOM_NUMBER("random_number"),

    /** 摩斯电码（文字与摩斯码双向转换）。 */
    MORSE_CODE("morse_code"),

    /** 图片压缩（本机压缩 + 保存到相册）。 */
    IMAGE_COMPRESS("image_compress"),

    /** 做个决定（候选项随机抽取 + 滚动动画）。 */
    DECISION_MAKER("decision_maker"),

    /**
     * 色卡（assets/colors 下的自包含网页：中国色 526 + 日本色 250）。
     *
     * 无 Worker / 无 fetch / 无 localStorage，因此走 file:// 即可（见 `ColorCardScreen`）。
     */
    COLOR_CARD("color_card"),

    /** 课程表（内置素材 assets/timetable/curriculum.ics，TimetableParser 解析并缓存）。 */
    TIMETABLE("timetable"),

    // 2026-10-25：热搜入口移到「发现 → 官网」下方。`LabRealPage.HOT_SEARCH`
    // 枚举项随之删除（它唯一的用途是宫格分发；快捷方式锚点由
    // `TabDestination.HotSearch` 的 route 承担，老快捷方式仍经 fromRouteOrNull 直达）。

    /** 今日待办（v1.1.82：内嵌用户的 todo-app，WebView + WorkBuddy 云）。 */
    TODO_TODAY("todo_today"),

    /** 成绩收集统计（2026-10-28：内嵌成绩应用，WebView + WorkBuddy 云）。 */
    SCORE_STATS("score_stats"),

    /**
     * GitHub 热门项目（2026-10-27 需求：实验室里能看 GitHub 热门）。
     *
     * 数据来自 GitHub 官方 Search API（`pushed:>=N天 stars:>=10` 近似 Trending），
     * 端上直连、按「周期 + 语言」缓存 10 分钟（匿名限流 10 次/分钟）。
     * key 与 `TabDestination.GithubTrending` 同名，桌面快捷方式契约继续成立。
     */
    GITHUB_TRENDING("github_trending"),

    /**
     * 去 AI 味（2026-10-27 需求：把用户自建的「去AI味改写」网页应用接进实验室）。
     *
     * assets 里是 `deai/index.html`（WebView + WorkBuddy 云 LLM），
     * 端上只做容器（见 `DeAiScreen`）。key 与 `TabDestination.DeAi` 同名。
     */
    DE_AI("de_ai"),

    /**
     * 知识问答（2026-11-09 需求：接入用户自建的「知识问答 Agent」网页应用）。
     *
     * assets 里是 `knowledge_qa/index.html`（WebView + WorkBuddy 云数据库与 LLM），
     * 端上只做容器（见 `KnowledgeQaScreen`）。key 与 `TabDestination.KnowledgeQa` 同名。
     */
    KNOWLEDGE_QA("knowledge_qa"),

    /**
     * 域名比价（2026-11-09 接入，v1.3.7 重写为原生页面）。
     *
     * 最初是把用户自建的「域名比价 Agent」（Node 服务）发布成沙箱地址、用 WebView 加载；
     * 沙箱实例回收后页面就 403 了，功能整体不可用。现在改为端上直接抓注册商官网
     * （见 `DomainPriceRepository`），不再依赖任何会过期的中间服务。
     */
    DOMAIN_PRICE("domain_price"),

    /**
     * 效率工具分组入口（2026-11-24 宫格收敛）：天气 / 记事本 / 智能体 / 番茄钟 / 课程表。
     * 点开进 effeciency_group 二级页；子项仍走各自原路由，
     * 所以 key `efficiency_group` 只是新入口的稳定标识，不对应一个被删掉的功能页。
     */
    EFFICIENCY_GROUP("efficiency_group"),

    /** 工具箱分组入口（2026-11-24）：随机数 / 摩斯 / 图片压缩 / 做个决定 / 色卡。 */
    TOOLBOX_GROUP("toolbox_group")
}

/**
 * 实验室的功能入口。
 *
 * [title] 是宫格里显示的文字：占位项取自 [PlaceholderEntry]（保持单一来源），
 * 真实页项自己给一份（它本来就不在占位枚举里）。
 */
private data class LabItem(
    val title: String,
    val target: LabTarget,
    val icon: ImageVector
)

/**
 * 实验室的功能入口，顺序即宫格内的排布顺序（两列，逐行铺开）。
 *
 * 图标选用 Material 图标库里的近似语义图标（扩展图标库已在依赖里，
 * 因此不额外引入图标资源）。
 *
 * 「开发者选项」用 `Icons.Filled.Build`（扳手）：它是 Material **核心**图标集
 * 里就有的常见图标，不需要扩展库兜底；语义上也最贴近「调试 / 工具」。
 * 原先这一格用的是 `Icons.AutoMirrored.Filled.Login`（登录箭头），
 * 那是「登录测试选项」时代的遗留，更名后语义已经对不上了。
 *
 * ## 关于图标一律优先用「核心集」
 *
 * 需求建议「智能体」用 `Icons.Filled.SmartToy`。**已实际核对**本项目解析到的
 * `material-icons-core`（1.7.6 / 1.7.8 两版产物都看了）里的 `filled` 包：
 * 全部只有 49 个类，**没有 `SmartToy`**——它只在 `material-icons-extended` 里。
 * 本项目确实依赖了 `material-icons-extended`，所以直接写 `SmartToy` 也能编译，
 * 但本文件此前已为「记事本」明确立过一条规矩：**能用核心集就不用扩展集**
 * （理由见下方 `Edit` 那段注释——避免与扩展库产生隐性绑定）。
 * 于是这里改用核心集里的 `Icons.Filled.Star`：它确定在 core 里（已核对），
 * 语义上「星标 / 智能体」也算说得通，且不至于像 `Face` / `Person` 那样
 * 与「我的」页、头像等处重复用同一个图标。
 */
private val LAB_ITEMS: List<LabItem> = listOf(
    // ── 2026-11-24 宫格收敛：十项工具/效率功能收进两个分组入口 ──
    // 这些功能各自的 LabRealPage 枚举项、TabDestination route、桌面快捷方式 key
    // 与导航回调**全部保留**（见 openItem 的分发），老快捷方式仍能直达；
    // 这里只是把它们从宫格首页移进各自的二级页，让首页一眼能看完。
    LabItem("效率工具", LabTarget.RealPage(LabRealPage.EFFICIENCY_GROUP), Icons.Filled.Build),
    LabItem("工具箱", LabTarget.RealPage(LabRealPage.TOOLBOX_GROUP), Icons.Filled.Casino),

    // 「开发者选项」v1.1.64 由占位页升级为真实页面。
    LabItem("开发者选项", LabTarget.RealPage(LabRealPage.DEVELOPER_OPTIONS), Icons.Filled.Build),
    // 「登录提示功能」v1.1.65 新增（管「打开软件提示登录」那个引导弹窗的开关）。
    LabItem("登录提示功能", LabTarget.RealPage(LabRealPage.LOGIN_PROMPT), Icons.Filled.Notifications),
    // 「贪吃蛇」：assets/snake 下的自包含网页小游戏。PlayArrow 在核心集（已核实）。
    LabItem("贪吃蛇", LabTarget.RealPage(LabRealPage.SNAKE), Icons.Filled.PlayArrow),
    // 「数独」：assets/sudoku 下的自包含网页（Blob Worker，需 https 映射）。
    LabItem("数独", LabTarget.RealPage(LabRealPage.SUDOKU), Icons.Filled.GridOn),
    // 「数独2」：另一份素材（assets/sudoku2），与「数独」是并列的两个游戏。
    // Numbers 只在扩展集（核心集没有能区分两个数独的图标）。
    LabItem("数独2", LabTarget.RealPage(LabRealPage.SUDOKU2), Icons.Filled.Numbers),

    // 2026-10-25：热搜入口移到「发现 → 官网」下方（LabRealPage.HOT_SEARCH 枚举与
    // TabDestination.HotSearch 路由保留——路由是老桌面快捷方式的直达锚点）。

    // ── v1.1.82 新增：今日待办（内嵌用户的 todo-app，WebView + 云端数据）────
    LabItem("今日待办", LabTarget.RealPage(LabRealPage.TODO_TODAY), Icons.Filled.CheckCircle),

    // ── 2026-10-28 新增：成绩收集统计（内嵌成绩应用单页应用，WebView + 云端数据）──
    // 与「今日待办」相邻：两者都是"自己的数据放云端、多个设备都要看得到"的形态，
    // 排在一起比塞到宫格末尾更容易被找到。Assessment（柱状图带勾）比 School 更贴
    // "收集 + 统计"这个动作本身。
    LabItem("成绩收集", LabTarget.RealPage(LabRealPage.SCORE_STATS), Icons.Filled.Assessment),

    // ── 2026-10-27 新增：GitHub 热门项目 ────────────────────────────────
    // 「热搜」看的是社交平台在聊什么，「GitHub 热门」看的是开发者在做什么，
    // 两者都是"今天外面发生了什么"，所以排在一起。
    // TrendingUp 只在扩展图标集（与 SmartToy 同一先例，本项目已依赖该库）。
    LabItem("GitHub 热门", LabTarget.RealPage(LabRealPage.GITHUB_TRENDING), Icons.Filled.TrendingUp),

    // ── 2026-10-27 新增：去 AI 味（内嵌网页应用，云端 LLM 改写）─────────────
    // AutoFixHigh（魔棒）只在扩展图标集，与 SmartToy / TrendingUp 同一先例。
    LabItem("去 AI 味", LabTarget.RealPage(LabRealPage.DE_AI), Icons.Filled.AutoFixHigh),

    // ── 2026-11-09 新增：知识问答（内嵌网页应用，云端知识库 + LLM）───────────
    // 和「去 AI 味」都是"网页 + 云"的形态，排在它后面；MenuBook（书本）也是扩展图标集。
    LabItem("知识问答", LabTarget.RealPage(LabRealPage.KNOWLEDGE_QA), Icons.AutoMirrored.Filled.MenuBook),

    // ── 2026-11-09 新增：域名比价（端上直抓 6 家注册商官网，v1.3.7 重写）───
    LabItem("域名比价", LabTarget.RealPage(LabRealPage.DOMAIN_PRICE), Icons.Filled.Paid)
)

/**
 * 桌面快捷方式携带的 action。
 *
 * 与 [EXTRA_LAB_PAGE] 一起构成「这个快捷方式是实验室里某一格」的标记。
 *
 * ⚠️ **目前没有任何代码读取它**，这是**已知且刻意**的取舍，见 [pinLabShortcut]。
 *
 * ## 为什么这两个常量是 `public`（其余工具函数都是 `private`）
 *
 * 它们是**给 `MainActivity` 用的契约**。读取 Intent 那段代码必须写在
 * `MainActivity`（不在本文件、也不在本次允许改动的范围内），
 * 而它的包名是 `com.morecheck`、与本文件（`com.morecheck.ui.lab`）不同——
 * 若把常量写成 `private`，将来接线的人只能**把字符串再抄一遍**到 `MainActivity`，
 * 于是「写」与「读」两处各有一份字面量，改一处漏一处就是
 * 「快捷方式点开却跳错页 / 完全不跳」这类**静默**故障。
 * 公开它们，是为了让将来那段读取代码能直接引用同一个来源。
 *
 * 它们**不是**给 UI 层之外随便用的通用 API，所以本文件其余实现细节一律 `private`：
 * 只把「跨模块必须共享的字面量」这一最小面暴露出去。
 */
const val ACTION_LAB_SHORTCUT = "com.morecheck.action.LAB_SHORTCUT"

/**
 * 桌面快捷方式携带的「目标页标识」extra，值为 [shortcutKeyOf] 的返回值。
 *
 * 用 extra 而不是把页面标识编进 action（如 `...LAB_SHORTCUT.notebook`）：
 * action 是**字符串匹配**语义，将来若要按 action 做筛选/统计会被拼串污染；
 * 而 extra 天然可缺省（老快捷方式没有这个 extra 时读到 null，不会崩）。
 *
 * 取值是**稳定字符串**（见 [shortcutKeyOf]），与 `TabDestination` 的 route 同名，
 * 因此将来 `MainActivity` 里的 `when` 可以直接拿它去比对路由名。
 * 公开的理由同上（与 [ACTION_LAB_SHORTCUT] 共用一段说明）。
 */
const val EXTRA_LAB_PAGE = "lab_page"

/**
 * 快捷方式 id 前缀。
 *
 * ⚠️ 每个条目必须用**各自不同的** id：`requestPinShortcut` 是按 id 去重的，
 * 若所有格子共用同一个 id，用户在桌面加了「天气」再加「记事本」时，
 * 第二次会**替换**掉第一个，桌面上永远只有一个实验室快捷方式。
 */
private const val LAB_SHORTCUT_ID_PREFIX = "lab_shortcut_"

/**
 * 取一个条目的**稳定**快捷方式标识（同时用作快捷方式 id 后缀与 [EXTRA_LAB_PAGE] 的值）。
 *
 * 直接复用既有枚举里的稳定字段，不另造一套字符串：
 * - 真实页用 [LabRealPage.key]（刻意手写、不用 `ordinal`，理由见那个枚举的 KDoc）；
 * - 占位项用 [PlaceholderEntry.value]（本就是路由参数，天然稳定）。
 *
 * 两者都不会因「以后在宫格中间插一项」而改变，所以已经 pin 到桌面的快捷方式
 * 不会因为增删条目而指向错误的页面。
 */
private fun shortcutKeyOf(item: LabItem): String = when (val target = item.target) {
    is LabTarget.RealPage -> target.page.key
    is LabTarget.Placeholder -> target.entry.value
    is LabTarget.ExternalEntry -> target.key
}

/**
 * 宫格条目的**稳定标识**（= [shortcutKeyOf]，即桌面快捷方式用的同一个 key）。
 *
 * 直接复用而不是另建一张映射表：快捷方式与本页排序都要"长期稳定、且与路由同名"的
 * 标识，两处用同一个函数就永远不会出现"排序认的 key 与快捷方式认的 key 不一致"
 * 这种要人工同步的隐患。真实页用 [LabRealPage.key]、占位项用 [PlaceholderEntry.value]，
 * 两者都不随宫格位置或增删而改变。
 */
private fun labItemKey(item: LabItem): String = shortcutKeyOf(item)

/**
 * 请求把一个实验室条目**固定到桌面**（长按宫格 →「添加到桌面」后直接调用）。
 *
 * ## 调用方已经表态，这里不再二次询问（需求 7）
 *
 * 长按菜单里点「添加到桌面」时**直接**调本函数，不弹应用内确认框。
 * 之所以敢这么直接，是因为**系统那层确认框仍然在**：
 * `requestPinShortcut` 会让桌面弹出它自己的「添加到桌面 / 取消」对话框，
 * 用户点不点由系统问、也由系统告诉他；应用要做的只是把请求递上去。
 * 那一层由桌面实现，**应用管不着、也不该绕**（绕它等于替用户确认一件
 * 会在他桌面上留下持久图标的事，那才是真的越界）。
 *
 * 也就是说：本需求去掉的只是**应用自己**多问的那一句，
 * 「确认」这件事本身仍然存在，只是换了执行者。
 *
 * ## 为什么用 `ShortcutManagerCompat.requestPinShortcut`
 *
 * 这正是需求推荐的方案，也是本项目唯一不需要新依赖的做法：
 * `ShortcutManagerCompat` 在已有的 `androidx.core` 里（已核对其产物确有此方法），
 * 不需要动 `build.gradle.kts`。
 *
 * 另一条老路 `ACTION_CREATE_SHORTCUT`（自己声明一个 `ShortcutActivity` 返回图标与 Intent）
 * 被否掉的原因：它要求改 **AndroidManifest.xml** 新增一个 exported 的 Activity，
 * 把「加个快捷方式」这种小事扩散到组件声明层面；而且那份老协议的交互由桌面主导，
 * 各桌面实现差异很大。`requestPinShortcut` 由系统弹出统一的确认框，行为一致、可控。
 *
 * ## 快捷方式确实能**直达**对应子页（v1.1.68 起；下面那段旧说明已作废）
 *
 * 本应用是「单 Activity + Compose 导航」：真正的页面栈在 `MoreCheckRoot` 里的
 * `NavHost` 中，而**路由由 Compose 状态持有**，外部 Intent 无法直接驱动它。
 * 所以直达必须靠一条完整的链路，这条链路**已经接好了**（v1.1.68 做「长按添加桌面快捷方式」
 * 时补上的），无需再改任何文件即对本页新增条目生效：
 *
 * 1. 创建侧（本函数）：Intent 带 `action = ACTION_LAB_SHORTCUT` + `EXTRA_LAB_PAGE = key`；
 * 2. `MainActivity.onCreate` / `onNewIntent` 调 `shortcutTargetOf(intent)` 读出 key
 *    （**只认本 action**，避免普通外部 Intent 恰好带 `lab_page` 就清掉待办跳转）；
 * 3. 传给 `MoreCheckRoot` 的 `labShortcutTarget`，由那段 `LaunchedEffect` 解析：
 *    `TabDestination.fromRouteOrNull(key)` 命中对应目的地 → 切到「发现」入口 → `navigate`；
 *    解析不出来（历史快捷方式指向已改名的页面）时**安静消费、不导航**。
 *
 * ⚠️ 这条链路的**唯一约束**是：`LabRealPage.key` 必须与 `TabDestination` 的 `route` **同名**。
 * 新增实验室条目时这一点必须同时核对两处（本文件的枚举 + `ui/nav/TabDef.kt`），
 * 否则快捷方式会静默落到首页，排查起来很费劲。
 *
 * > 历史说明（**已不成立，保留仅为理解当初的设计动机**）：早期版本里
 * > `MainActivity` 尚未读取该 Intent，点击快捷方式等同于「打开本应用」、
 * > 落在默认的一级入口首页（`AppTab.HOME`）。那段代码在 v1.1.68 补上了。
 *
 * ## 为什么 `callback` 传 null
 *
 * `requestPinShortcut` 的第三个参数是「固定成功后回调」的 [android.content.IntentSender]。
 * 用它需要在 manifest 里注册一个对应的 receiver（又是一处改动扩散），
 * 而**返回值的含义本来就够用**：`true` 只代表「请求已受理、系统确认框已弹出」，
 * 用户点不点「添加」是由系统告诉他的，应用无需再抢着提示一遍。
 *
 * ## 返回值
 *
 * - `true`：请求已被受理（系统会弹确认框，**不代表用户已经点了「添加」**）；
 * - `false`：当前桌面不支持请求固定快捷方式，或调用抛异常（已兜住，不让实验室页崩）。
 *
 * ## 图标：跟随用户选中的内置图标套（本轮需求 8）
 *
 * 需求原文：「实验室功能添加到桌面支持使用其他图标样式」。
 * 因此这里**不再固定**用 `R.mipmap.ic_launcher`，而是按
 * `AppIconRepository.selectedIconId` 当前选中的那套取对应资源
 * （见 [labShortcutIconResOf]）——用户在「开发者选项 → 图标更改」里切到哪套，
 * 新添加的桌面快捷方式就用哪套的图标，与桌面上的 App 图标一致。
 *
 * 两个刻意的取舍：
 * - **取「创建那一刻」的选中套，之后不跟着变**：`requestPinShortcut` 的语义是
 *   「请桌面按我给的图标放一个快捷方式」，图标在创建时就定下来；运行时换图标套
 *   不会回溯改桌面上已有的快捷方式（那需要 `updateShortcuts` + 动态快捷方式，
 *   而 `requestPinShortcut` 创建的是 **pinned** 快捷方式，多数桌面不允许应用改它）。
 * - **选不中的 id 回落到默认套**：与 `IconPickerScreen` 的「脏 id 落默认」同一口径
 *   （用户可能在旧版本里选过已被删除的蓝 / 紫 / 橙）。
 *
 * @param selectedIconId 当前选中的内置图标套短名（`default` / `check` / …）；
 *   由调用方从仓库读出后传进来（本函数不自己读 DataStore，保持纯函数、便于单测）。
 */
private fun pinLabShortcut(context: Context, item: LabItem, selectedIconId: String): Boolean {
    // 先问桌面支不支持。部分自带桌面 / 第三方启动器没实现该 API，
    // 不先判断的话 `requestPinShortcut` 直接返回 false，
    // 用户点了「添加」却什么都没发生，无从判断是坏了还是桌面不支持。
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false

    val key = shortcutKeyOf(item)

    val intent = Intent(context, MainActivity::class.java).apply {
        action = ACTION_LAB_SHORTCUT
        putExtra(EXTRA_LAB_PAGE, key)
        // 显式补 NEW_TASK：桌面启动快捷方式时系统通常自带这个 flag，
        // 但补上它可保证「从任何桌面/任何入口启动」都不会因为
        // 「非 Activity 上下文启动 Activity」而抛 AndroidRuntimeException。
        // 刻意**不加** CLEAR_TOP：那会把已经在运行的界面重建一遍，用户会丢失当前进度。
        // ⚠️ 也不加 FLAG_ACTIVITY_NEW_DOCUMENT：那会在 targetSdk 36 上新建一个
        // **独立的文档 task**，与 manifest 的 `documentLaunchMode="never"` 相悖，
        // 正是需求 16/18/19 要消除的「多个 task 互相堆叠」的来源之一。
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    val iconRes = labShortcutIconResOf(selectedIconId)
    val shortcut = ShortcutInfoCompat.Builder(context, LAB_SHORTCUT_ID_PREFIX + key)
        .setShortLabel(item.title)
        // 长标题与短标题取同一个值：本项目的宫格标题本来就很短（2~5 个字），
        // 强行再写一份「更长的描述」只会多一个要维护的文案来源。
        .setLongLabel(item.title)
        // 图标按当前选中的内置套取（见函数 KDoc「图标」一节）；取不到时回落默认套。
        .setIcon(IconCompat.createWithResource(context, iconRes))
        .setIntent(intent)
        .build()

    // 兜住异常：`ShortcutInfoCompat.build()` 已保证 label / intent 非空，
    // 但个别 ROM 的 ShortcutManager 实现会在请求阶段抛 RuntimeException。
    // 这是实验室里一个「可选的便利功能」，绝不能因为它把整页弄崩。
    return runCatching {
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }.getOrDefault(false)
}

/**
 * 内置图标套短名 → 桌面快捷方式要用的 `@mipmap` 资源（本轮需求 8）。
 *
 * ## 为什么不直接复用 `IconPickerScreen.builtInIconResOf`
 *
 * 那个函数是 `IconPickerScreen.kt` 的 **文件级 private**，跨文件拿不到。
 * 两条路：① 把它提升为 internal 共用；② 在这里按同一份清单再写一遍。
 *
 * 选 ②，理由是**两份清单的用途不同、演化方向也不同**：
 * - `builtInIconResOf` 服务的是「图标更改」页的**宫格预览**，它要的是
 *   「渲染出来给用户看的那张图」，与界面排版耦合；
 * - 本函数服务的是「交给系统 PackageManager 的桌面图标」，它要的是
 *   「与 manifest alias 指向完全一致的那一份」。
 *
 * 一旦将来两边不同步，表现是「预览对、桌面错」或反过来——都是**可见**的错，
 * 不会静默。真正需要防的是"某套图标在 manifest 里没有对应 mipmap"，
 * 那条由 `else -> R.mipmap.ic_launcher` 兜住（回落默认套，而不是传 0 让
 * `createWithResource` 抛 `Resources.NotFoundException`——那种异常会把
 * 「添加桌面」这个便利功能直接弄崩）。
 *
 * ⚠️ 新增内置图标套时这里**必须同步补一条**（与 `AppIconRepository.BUILT_IN_ICON_IDS`、
 * manifest 的 alias、`IconPickerScreen` 的映射四处对齐，完整步骤见
 * `AppIconRepository` 类注释第七节）。
 */
private fun labShortcutIconResOf(iconId: String): Int = when (iconId) {
    "check" -> R.mipmap.ic_launcher_check
    "star" -> R.mipmap.ic_launcher_star
    "flame" -> R.mipmap.ic_launcher_flame
    "leaf" -> R.mipmap.ic_launcher_leaf
    "tree" -> R.mipmap.ic_launcher_tree
    // 本轮新增三套（一个木函 Logo 家族，需求 8/11）。
    "pine" -> R.mipmap.ic_launcher_pine
    "snow" -> R.mipmap.ic_launcher_snow
    "mint" -> R.mipmap.ic_launcher_mint
    // default 与一切无法识别的值（含已删除的 blue / purple / 橙）都回落默认套。
    else -> R.mipmap.ic_launcher
}

/**
 * 实验室（发现 → 实验室）。
 *
 * 版式照参考图：标题栏 + 两列宫格 + 底部一个长条按钮。
 * 卡片色取自 [MaterialTheme]，因此**跟随「深色模式」与主题色**；
 * 只有**页面底色**是本页专属的渐变（见 [rememberLabBackdrop]），深浅色模式各一套，
 * 需求明确要求「仅在实验室界面背景使用，不包含子界面」——因为子界面是
 * `TabNavHost` 里的独立目的地、各自有完整的 `Scaffold`，本页的渐变天然盖不到它们。
 *
 * 宫格里的条目分两类（见 [LabTarget]）：
 * - 「天气」走 [onOpenWeather]；
 * - 「开发者选项」走 [onOpenDeveloperOptions]；
 * - 「登录提示功能」走 [onOpenLoginPromptSetting]；
 * - 「记事本」走 [onOpenNotebook]；
 * - 「智能体」走 [onOpenAgent]（本轮由占位页升级为真实页面，OpenAI 兼容 AI 对话）；
 * - 「贪吃蛇」走 [onOpenSnake]（本轮新增，内置的网页小游戏）；
 * - 「数独」走 [onOpenSudoku]（需求 17 新增，内置的网页小游戏）；
 * - 「数独2」走 [onOpenSudoku2]（v1.1.74 需求 1 新增，**第二份**数独素材，
 *   与「数独」是并列的两个游戏，不是替代关系）。
 *
 * ## 长按拖动排序 / 长按松手打开菜单（2026-11-03 需求 4）
 *
 * 宫格现在用 `LazyVerticalGrid` + 外层统一手势（见文件内 [LabDragState] 与网格处的 KDoc）：
 * - **长按并拖动**：卡片抬起跟手、其它卡片平滑让位，松手写回 `lab_grid_order`；
 *   拖到列表上下边缘附近会自动滚动（卡片多到超过一屏时仍能跨屏重排）。
 * - **长按后原地松手**：打开原来的动作菜单（置顶 / 取消置顶 / 上移 / 下移 /
 *   恢复默认排序 / 添加到桌面）——旧能力一个都没丢；从发现页搬进来的格子
 *   额外多一枚「移回发现页」（发现页长按对话框承诺了这条回路）。
 * - **置顶**：被置顶的卡片右上角显示图钉角标；置顶状态编码在同一个顺序串里
 *   （前缀见 [PINNED_KEY_PREFIX]），重启后仍然显示。
 * - **添加到桌面**：仍直接发起固定请求，提示改为**系统 Toast**（见菜单里的注释）。
 *
 * ## 长按宫格 → 添加到桌面
 *
 * 「添加到桌面」**直接**发起固定请求（需求 7 起不再有应用内确认框，
 * 见 [pinLabShortcut] 的 KDoc）。
 * 点击生成的快捷方式会**直达该格对应的子页**——这条链路（`ACTION_LAB_SHORTCUT` →
 * `MainActivity` 读 `EXTRA_LAB_PAGE` → `TabDestination.fromRouteOrNull` → `navigate`）
 * 在 v1.1.68 就接好了，新增条目自动纳入。**唯一约束**是 `LabRealPage.key` 必须与
 * `TabDestination` 的 `route` 同名，详见 [pinLabShortcut] 的 KDoc。
 *
 * @param onOpenEntry 打开留空二级页，参数是 [PlaceholderEntry.value]。
 * @param onOpenWeather 打开「天气」真实页。
 * @param onOpenDeveloperOptions 打开「开发者选项」真实页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LabScreen(
    onBack: () -> Unit,
    /**
     * 页面级一次性提示。
     *
     * 2026-11-03：本页的「添加到桌面」提示已改为**系统 Toast**（原因见菜单里的注释：
     * 长按菜单是独立 Dialog 窗口，页面 Snackbar 会被它挡住）。这个形参保留下来，
     * 是为了不破坏既有调用方的接线（`TabNavHost` 仍按名字传），后续本页再有
     * 页级提示时可继续复用。
     */
    onMessage: (String) -> Unit = {},
    /** 打开留空二级页，参数是 [PlaceholderEntry.value]。 */
    onOpenEntry: (String) -> Unit,
    /** 从发现页搬来的入口统一走导航层分派（含音乐确认门禁）。 */
    onOpenMovableEntry: (String) -> Unit = {},
    /** 打开「天气」真实页（实验室宫格第一项）。 */
    onOpenWeather: () -> Unit = {},
    /** 打开「开发者选项」真实页（实验室宫格第二项）。 */
    onOpenDeveloperOptions: () -> Unit = {},
    /** 打开「登录提示功能」页（实验室宫格第三项，v1.1.65 新增）。 */
    onOpenLoginPromptSetting: () -> Unit = {},
    /**
     * 打开「记事本」页（实验室宫格第四项）。
     *
     * 放在参数列表**最后**并**带默认值**：既有调用方（`TabNavHost` 等处）不会因此编译失败，
     * 导航接线可以按名字单独传它，不必与本次改动同步落地。
     */
    onOpenNotebook: () -> Unit = {},
    /** 打开「智能体」页（实验室宫格第五项，本轮由占位页升级为真实页面）。 */
    onOpenAgent: () -> Unit = {},
    /**
     * 打开「贪吃蛇」页（实验室宫格第六项，本轮新增的内置网页小游戏）。
     *
     * 同样放在参数列表**最后**并**带默认值**：既有调用方不会因这次改动编译失败，
     * 与上一项 `onOpenNotebook` 取的是同一条口径。
     */
    onOpenSnake: () -> Unit = {},
    /**
     * 打开「数独」页（实验室宫格第七项，需求 17 新增的内置网页小游戏）。
     *
     * 同 `onOpenSnake`：放在最后且带默认值，接线可以按名字单独传它。
     */
    onOpenSudoku: () -> Unit = {},
    /**
     * 打开「数独2」页（实验室宫格，v1.1.74 需求 1 新增的第二份数独素材）。
     *
     * 同 `onOpenSnake`：放在最后且带默认值，接线可以按名字单独传它。
     * 它启动的是**独立 Activity**（`Sudoku2HostActivity`），与另两个游戏同一口径。
     */
    onOpenSudoku2: () -> Unit = {},

    /**
     * v1.1.73 新增五项的入口回调。
     *
     * 全部放在参数表末尾并带默认值：本函数已被 `TabNavHost` 调用，
     * 带默认值可以让「新增一项」不必同时改动所有调用点（也与
     * `onOpenSnake` / `onOpenSudoku` 的接线方式一致）。
     */
    onOpenPomodoro: () -> Unit = {},
    onOpenRandomNumber: () -> Unit = {},
    onOpenMorseCode: () -> Unit = {},
    onOpenImageCompress: () -> Unit = {},
    onOpenDecisionMaker: () -> Unit = {},
    onOpenColorCard: () -> Unit = {},

    /** 打开「效率工具」分组二级页（2026-11-24 宫格收敛）。 */
    onOpenEfficiencyGroup: () -> Unit = {},
    /** 打开「工具箱」分组二级页（2026-11-24 宫格收敛）。 */
    onOpenToolboxGroup: () -> Unit = {},

    /**
     * 打开「课程表」页（v1.1.74 需求 10）。
     *
     * 同 `onOpenSnake`：放在最后且带默认值，导航接线可以按名字单独传它。
     * 页面从 `AppContainer.timetableRepository` 取课表，本页不需要给它传任何数据。
     */
    onOpenTimetable: () -> Unit = {},

    /**
     * 打开「今日待办」（v1.1.82：内嵌用户的 todo-app 单页应用，WebView 承载）。
     *
     * 同 `onOpenTimetable`：放在最后且带默认值，导航接线可以按名字单独传它。
     *
     * 2026-10-25：原「热搜」入口（onOpenHotSearch）已移到「发现」页——
     * 实验室宫格不再有这一格，回调随之删除（路由与快捷方式锚点保留在 TabDef）。
     */
    onOpenTodoToday: () -> Unit = {},

    /** 打开「GitHub 热门」（2026-10-27 需求：实验室里看 GitHub 热门项目）。 */
    onOpenGithubTrending: () -> Unit = {},

    /** 打开「去 AI 味」（2026-10-27 需求：内嵌的去AI味改写网页应用）。 */
    onOpenDeAi: () -> Unit = {},

    /** 打开「知识问答」（2026-11-09 需求：内嵌的知识问答 Agent 网页应用）。 */
    onOpenKnowledgeQa: () -> Unit = {},

    /** 打开「域名比价」（2026-11-09 需求：内嵌的域名比价 Agent，线上 Node 服务）。 */
    onOpenDomainPrice: () -> Unit = {},

    /**
     * 打开「成绩收集统计」（2026-10-28：内嵌成绩应用单页应用，WebView 承载）。
     *
     * 放在参数表末尾且带默认值——与本文件其余"页级回调"一致：导航接线按名字传，
     * 预览 / 测试可以完全不传。
     */
    onOpenScoreStats: () -> Unit = {}
) {
    // 渐变色只作用于本页：在 Scaffold **之外**铺一层 Box 画渐变，
    // 并把 Scaffold 的容器色设为透明、标题栏也设透明，让渐变从顶部一直透到底部。
    //
    // 反过来（给 Scaffold 传 containerColor = 渐变）做不到——`Scaffold` 的
    // `containerColor` 只接受单一颜色，画不出渐变。
    val backdrop = rememberLabBackdrop()

    // ── 需求 4：底部系统导航栏透明化 ────────────────────────────────────
    //
    // ## 症状
    //
    // 用户截图里实验室页屏幕最底下有一条**浅色实心横条**，与上方整屏渐变割裂。
    // 那条不是本页画的东西，而是系统给导航栏加的**对比度遮罩（scrim）**。
    //
    // ## 根因：`enableEdgeToEdge()` 的默认导航栏样式
    //
    // `MainActivity` 调的是无参 `enableEdgeToEdge()`，它给导航栏套
    // `SystemBarStyle.auto(TRANSPARENT, DEFAULT_NAVIGATION_BAR_SCRIM)`——即
    // 「导航栏底色透明，但叠一层 `DEFAULT_NAVIGATION_BAR_SCRIM`
    //   （半透明灰）保证图标可读」。于是页面渐变确实铺到了屏幕底边，
    // 只是**盖在它上面的一层灰**没被去掉，看上去就是一条独立色带。
    //
    // 这也解释了为什么别处（游戏页）没这个现象：那些页自己调了
    // `WindowInsetsController` 并把容器色设成不透明，灰罩与深色容器同色、看不出来。
    // 实验室页的 `Scaffold` 容器色是 `Transparent`（为了让渐变透出），
    // 这层灰罩就**无处隐藏**了。
    //
    // ## 修法
    //
    // 本页进出时把「导航栏对比度强制」关掉、并在 dispose 时原样归还：
    // `isNavigationBarContrastEnforced = false`（API 29+）去掉那层遮罩，
    // 于是渐变直接铺到屏幕底边。
    //
    // ⚠️ **为什么不改 `MainActivity` 的 `enableEdgeToEdge()` 调用**：
    // 那是全局策略，影响全部页面。把它改成全局无遮罩，等于让**每个**页面的
    // 手势条区域都失去对比度保护——浅色页面上深色手势条压在浅底上会看不清。
    // 需求只针对实验室这一页，故做成**页级**开关，进出页自动还原。
    //
    // ⚠️ **API 硬限制（如实记录，不要承诺过头）**：
    // 1. `isNavigationBarContrastEnforced` 是 API 29 引入的，本项目 `minSdk = 29`，
    //    因此**无需版本分支**，但部分 ROM（尤其定制较深的）可能忽略它。
    // 2. 三键导航（API 29/30）由系统强制画一层导航栏底色，
    //    `isNavigationBarContrastEnforced` 管不到；本项目面向手势导航为主，
    //    **手势导航下的透明效果可保证，三键导航下不保证**。
    // 3. 图标明暗仍按背景配对（见下方 `onBackdrop` 与顶栏颜色），
    //    不因为去掉灰罩就牺牲可读性。
    TransparentNavigationBarEffect()

    // ⚠️ 这里曾经还有一个 `pendingPin`（可空 LabItem）+ 一个「添加到桌面？」的
    // AlertDialog，用来在真正 pin 之前问一句。**需求 7 起已整体删除**：
    // 长按菜单点「添加到桌面」直接发起 pin 请求，不再有应用内二次确认。
    //
    // 删除的理由与替代口径见 [pinLabShortcut] 的 KDoc「调用方已经表态」一节——
    // 确认框仍然存在，只是由桌面（`requestPinShortcut` 触发的那一层）来问；
    // 顺带删掉的还有 `pendingPin` 本身与 `pinnedShortcuts` 的订阅。

    // ── 宫格顺序（v1.1.69 需求：长按排序 / 置顶；2026-11-03 改为长按拖拽排序）──
    //
    // 顺序存在 DataStore（`lab_grid_order`，逗号分隔的 key 串），理由见
    // `SettingsRepository.labGridOrder` 的 KDoc。本轮**沿用同一个存储键**，没有新造
    // 第二份顺序数据；「置顶」标记也编码在这条 key 串里（见下方 [PINNED_KEY_PREFIX]）。
    var pendingLongPress by remember { mutableStateOf<LabItem?>(null) }

    // 合并口径与仓库层一致：用户顺序里仍存在的排前，其余按静态顺序追加。
    // （仓库只在有记录时返回非空；默认空列表 → 直接用静态顺序。）
    // 注意：`savedOrder` 的订阅在下面 `context` / `settingsRepository` 之后就位后定义，
    // 顺序见那一段（Kotlin 的局部变量必须先声明后使用）。
    // 发起快捷方式请求需要 Context；`LocalContext` 拿到的是本 Activity。
    // `ShortcutManagerCompat` 内部只用到它取包名与服务，不会持有它造成泄漏，
    // 因此不需要额外转成 `applicationContext`。
    val context = LocalContext.current
    // 写宫格顺序是挂起调用（DataStore），用组合作用域即可：它随本页存活，
    // 离开页面时的未完成写入会被取消——而"排序"是用户点了就期望立刻生效的动作，
    // 页面都离开了这次写入也就没有意义。与「记事本自动落盘」那种"数据不能丢"
    // 的场景不同（那里必须用进程级作用域，见 NotebookStore 的说明）。
    val scope = rememberCoroutineScope()

    // 宫格顺序的订阅与合并（v1.1.69 需求：长按排序 / 置顶）。
    //
    // 顺序存在 DataStore（`lab_grid_order`，逗号分隔的 key 串），见
    // `SettingsRepository.labGridOrder` 的 KDoc；这里只做「用户已存的排前、
    // 其余按静态顺序追加」的合并，保证将来新增功能不会从宫格里消失。
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }

    // 2026-11-09 需求 3：实验室条目支持改名。
    // 表里只存"改过名的"（key → 新名），没改过的回落到内置标题，
    // 所以新增功能 / 清空数据之后不会出现一串空名字。
    val customNames by settingsRepository.labCustomNames
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    fun displayTitle(item: LabItem): String =
        customNames[labItemKey(item)]?.takeIf { it.isNotBlank() } ?: item.title

    // ---- 2026-11-14：入口可以在发现页与实验室之间搬迁 ----
    //
    // 初值取进程级快照（App 启动时预热）：把某项搬到发现页后再进实验室，
    // 第一帧就少掉那一格，而不是先按默认归属画满、再"重排位置"（需求 2 的第三个现象）。
    val overridesRaw by settingsRepository.entryPlacementOverrides
        .collectAsStateWithLifecycle(
            initialValue = settingsRepository.entryPlacementSnapshot ?: ""
        )
    val placement = remember(overridesRaw) { decodeEntryPlacement(overridesRaw) }
    /** 从发现页搬进来的格子（音乐 / 热搜 / B站 / 官网）。 */
    val extraItems = remember(placement) {
        MOVABLE_ENTRIES.filter {
            it.defaultPlacement == EntryPlacement.DISCOVER &&
                effectivePlacement(it.key, placement) == EntryPlacement.LAB
        }.map { entry ->
            LabItem(entry.title, LabTarget.ExternalEntry(entry.key), entry.icon)
        }
    }
    /** 本页实际要画的格子：默认格子去掉搬去发现页的，再追加搬进来的。 */
    val allItems = remember(placement) {
        LAB_ITEMS.filterNot { item ->
            val key = labItemKey(item)
            MOVABLE_ENTRIES.any {
                it.key == key && it.defaultPlacement == EntryPlacement.LAB &&
                    effectivePlacement(key, placement) == EntryPlacement.DISCOVER
            }
        } + extraItems
    }
    /** 正在改名的那一格（null = 没打开输入框）。 */
    var renamingItem by remember { mutableStateOf<LabItem?>(null) }
    // 「读到盘了才画」——v1.3.1 beta 需求 8 的核心修复。
    //
    // 原来初值写死 `emptyList()`：用户自定义过顺序时，第一帧按**默认顺序**画宫格，
    // 真实顺序随后到达，`animateItem()` 把卡片平滑挪到新位置，观感就是"进入实验室
    // 卡片瞬间移动排序"。
    //
    // 现在初值取进程级快照（`labGridOrderSnapshot`，App 启动时已预热），
    // 并且**把 null 当成"还没读到"**：快照缺失时门控住宫格（下面 `orderReady`），
    // 宁可空白一两帧也不先画错顺序。为 null 的窗口只在"冷启动直接落实验室"时出现。
    val savedOrderState by settingsRepository.labGridOrder
        .collectAsStateWithLifecycle(initialValue = settingsRepository.labGridOrderSnapshot)
    val orderReady = savedOrderState != null
    val savedOrder = savedOrderState ?: emptyList()

    // 「置顶」集合：从同一条顺序串里解析（前缀见 [PINNED_KEY_PREFIX]）。
    // 为什么不另存一份：本任务白名单不允许改 `SettingsRepository`，而新增第二个
    // DataStore 键会让"顺序"与"置顶"两处状态可能不同步；写成前缀则永远同源。
    // ⚠️ 老版本存的是不带前缀的纯 key 列表 → 解析出来就是空集合（没有任何钉角标），
    // 用户重新点一次「置顶」就会带上标记，不会丢数据。
    val pinnedKeys = remember(savedOrder) { parsePinnedKeys(savedOrder) }

    // 合并后的完整顺序（用户顺序 + 新增项追加）。保留 `remember` 是为了让
    // 「菜单里的上移/下移」和「拖拽排序」拿到同一份基准。
    val mergedItems = remember(savedOrder, allItems) {
        val storedKeys = parseOrderKeys(savedOrder)
        if (storedKeys.isEmpty()) {
            allItems
        } else {
            val byKey = allItems.associateBy { labItemKey(it) }
            val pinned = storedKeys.mapNotNull { byKey[it] }
            val rest = allItems.filterNot { labItemKey(it) in storedKeys }
            pinned + rest
        }
    }

    // 乐观顺序（2026-11-03 新增）：拖拽松手后先按新顺序渲染，等 DataStore 落盘回传
    // 再清掉。否则「松手 → 数据流还没到」的那一两帧会看到宫格跳回旧顺序。
    var pendingOrderKeys by remember { mutableStateOf<List<String>?>(null) }
    val orderedItems = remember(mergedItems, pendingOrderKeys) {
        val pending = pendingOrderKeys
        if (pending == null) {
            mergedItems
        } else {
            val byKey = mergedItems.associateBy { labItemKey(it) }
            pending.mapNotNull { byKey[it] }
        }
    }
    // 数据流追上来（顺序与乐观值一致）就释放乐观值。
    // ⚠️ key 用 `savedOrder` 而不是 `mergedItems`：后者是按内容比较的 List，
    // 「只加/摘置顶标记、顺序没变」时合并结果与旧值相等，effect 不会重启，
    // 乐观值就永远清不掉。用原始存盘列表做 key 能保证每次落盘都检查一次。
    LaunchedEffect(savedOrder) {
        val pending = pendingOrderKeys
        if (pending != null && mergedItems.map { labItemKey(it) } == pending) {
            pendingOrderKeys = null
        }
    }

    // 稳定 key → 条目 的索引表：拖拽排序时按 key 顺序取回条目对象用。
    val itemsByKey = remember(allItems) { allItems.associateBy { labItemKey(it) } }

    // ── 拖拽排序状态（2026-11-03 需求 4）──────────────────────────────
    //
    // 交互模型（为什么这样设计见 [LabDragState] 的 KDoc）：
    // 长按卡片 → 卡片"抬起"（原格变占位、浮起卡跟手）→ 拖动跨过别的卡片时交换顺序
    // → 松手写回 `lab_grid_order`。长按后**不拖动直接松手**会打开原来的动作菜单
    // （置顶 / 上移 / 下移 / 恢复到桌面 / 添加到桌面），所以旧能力一个都没丢。
    val drag = remember { LabDragState() }
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 长按后位移小于这个值 → 视为"没拖动"，打开原来的动作菜单。
    // 12dp 足够滤掉手指按住时的自然抖动，又不会把"想挪一格"误判成菜单。
    val dragMenuThresholdPx = with(density) { 12.dp.toPx() }
    // 手势协程会跨多次重组存活，因此这些"会变的基准值"必须经 State 读取最新值，
    // 不能在 pointerInput 创建时按值捕获（Kotlin 闭包会永远读到旧列表）。
    val displayItemsState = rememberUpdatedState(orderedItems)
    val pinnedKeysState = rememberUpdatedState(pinnedKeys)

    // 打开某一格的页面（点按 / 快捷打开共用一份分发逻辑）。
    fun openItem(item: LabItem) {
        // 两类条目在这里分流：占位项给路由参数，真实页交给调用方的专用回调。
        //
        // 真实页那层 `when` **不加 `else`**：以后在 [LabRealPage] 里加一项时，
        // 这里会因不完备而编译失败，从而强制补上接线（同上文的防漏机制）。
        when (val target = item.target) {
            is LabTarget.Placeholder -> onOpenEntry(target.entry.value)

            is LabTarget.ExternalEntry -> onOpenMovableEntry(target.key)

            is LabTarget.RealPage -> when (target.page) {
                LabRealPage.WEATHER -> onOpenWeather()
                LabRealPage.DEVELOPER_OPTIONS -> onOpenDeveloperOptions()
                LabRealPage.LOGIN_PROMPT -> onOpenLoginPromptSetting()
                LabRealPage.NOTEBOOK -> onOpenNotebook()
                LabRealPage.AGENT -> onOpenAgent()
                LabRealPage.SNAKE -> onOpenSnake()
                LabRealPage.SUDOKU -> onOpenSudoku()
                LabRealPage.SUDOKU2 -> onOpenSudoku2()
                LabRealPage.POMODORO -> onOpenPomodoro()
                LabRealPage.RANDOM_NUMBER -> onOpenRandomNumber()
                LabRealPage.MORSE_CODE -> onOpenMorseCode()
                LabRealPage.IMAGE_COMPRESS -> onOpenImageCompress()
                LabRealPage.DECISION_MAKER -> onOpenDecisionMaker()
                LabRealPage.COLOR_CARD -> onOpenColorCard()
                LabRealPage.TIMETABLE -> onOpenTimetable()
                LabRealPage.TODO_TODAY -> onOpenTodoToday()
                LabRealPage.SCORE_STATS -> onOpenScoreStats()
                LabRealPage.GITHUB_TRENDING -> onOpenGithubTrending()
                LabRealPage.DE_AI -> onOpenDeAi()
                LabRealPage.KNOWLEDGE_QA -> onOpenKnowledgeQa()
                LabRealPage.DOMAIN_PRICE -> onOpenDomainPrice()
                LabRealPage.EFFICIENCY_GROUP -> onOpenEfficiencyGroup()
                LabRealPage.TOOLBOX_GROUP -> onOpenToolboxGroup()
            }
        }
    }

    /**
     * 写回顺序（挂起版；自动补上置顶前缀）。
     *
     * 传空列表 = 清除自定义顺序（恢复默认排序）。
     * 拆成挂起版 + `persistOrder` 包装的原因：菜单里的「上移/下移」有 `moving` 串行保护，
     * 需要等写盘真正完成才放开；拖拽松手则没有可等待的 UI，直接 `launch` 即可。
     */
    suspend fun writeOrder(keys: List<String>, pinned: Set<String>) {
        if (keys.isEmpty()) {
            pendingOrderKeys = null
            settingsRepository.setLabGridOrder(emptyList())
            return
        }
        pendingOrderKeys = keys
        settingsRepository.setLabGridOrder(withPinPrefix(keys, pinned))
    }

    /** 非挂起包装：拖拽松手 / 一次性动作直接用。 */
    fun persistOrder(keys: List<String>, pinned: Set<String>) {
        // 先同步设置乐观顺序再 launch：否则松手后的下一帧会先用旧顺序渲染一次，
        // 表现为"松手后宫格闪回旧顺序"。
        if (keys.isNotEmpty()) pendingOrderKeys = keys
        scope.launch { writeOrder(keys, pinned) }
    }

    /**
     * 拖动中判断"指针停在哪个格子"并重排 [LabDragState.workingKeys]。
     *
     * 命中规则：先看指针落在哪张卡的矩形里（含被拖那张的占位格，落在自己格上就不动）；
     * 落在卡片间隙时退化为"最近的中心点"。不能用"最近中心"一条规则：
     * 拖起第一张时指针还在自己格上，而最近的其他卡中心会立刻把它判成第二位，
     * 表现为"一拿起就自己换了位置"。
     */
    fun updateDragTarget() {
        val draggingKey = drag.draggingKey ?: return
        val pointer = drag.pointerInRoot
        var target = -1
        drag.workingKeys.forEachIndexed { index, key ->
            val rect = drag.itemBounds[key] ?: return@forEachIndexed
            if (rect.contains(pointer)) {
                target = index
                return@forEachIndexed
            }
        }
        if (target < 0) {
            var bestDistance = Float.MAX_VALUE
            drag.workingKeys.forEachIndexed { index, key ->
                val rect = drag.itemBounds[key] ?: return@forEachIndexed
                val dx = pointer.x - rect.center.x
                val dy = pointer.y - rect.center.y
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    target = index
                }
            }
        }
        val current = drag.workingKeys.indexOf(draggingKey)
        if (target >= 0 && target != current) {
            drag.workingKeys = drag.workingKeys.toMutableList().apply {
                add(target, removeAt(current))
            }
            // 换位时给一次轻触感：不写长按反馈（那会让人以为又触发了一次长按）。
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    /** 长按成功、拖拽开始：记录浮起卡片的几何信息。 */
    fun startDrag(key: String) {
        val rect = drag.itemBounds[key] ?: return
        drag.draggingKey = key
        // 基准顺序取**当前显示顺序**（可能正带着乐观顺序），不能只取仓库顺序。
        drag.workingKeys = displayItemsState.value.map { labItemKey(it) }
        drag.itemSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt())
        drag.grabOffset = drag.pointerInRoot - rect.topLeft
    }

    /** 拖拽结束：位移太小视为"长按打开菜单"，否则写回顺序。 */
    fun finishDrag(movedPx: Float) {
        val key = drag.draggingKey
        val movedKeys = drag.workingKeys
        drag.clear()
        if (key == null) return
        val pressedItem = allItems.firstOrNull { labItemKey(it) == key } ?: return
        if (movedPx < dragMenuThresholdPx) {
            // 长按后没有真正拖动 → 打开原来的动作菜单（保持旧入口）。
            pendingLongPress = pressedItem
            return
        }
        val originalKeys = displayItemsState.value.map { labItemKey(it) }
        if (movedKeys != originalKeys) {
            persistOrder(movedKeys, pinnedKeysState.value)
        }
    }

    fun cancelDrag() {
        drag.clear()
    }

    // 拖拽贴边自动滚动（2026-11-03）：卡片可能有二十来张、超出一屏，
    // 若不自动滚，用户只能把卡片拖到"当前可见区域"内，没法从最后一行拖到第一行。
    // 判定用指针在**屏幕坐标**里的 y 与网格可视区比较；网格在拖拽时已禁用用户滚动，
    // 这里是唯一的滚动来源，不会和手势抢。
    LaunchedEffect(drag.draggingKey) {
        if (drag.draggingKey == null) return@LaunchedEffect
        val edgePx = with(density) { 72.dp.toPx() }
        val stepPx = with(density) { 10.dp.toPx() }
        while (isActive) {
            // 每帧推进一次，滚动速度与帧率无关（按帧固定步长，约 600px/s）。
            withFrameNanos { }
            val bounds = drag.gridBounds ?: continue
            val pointerY = drag.pointerInRoot.y
            val delta = when {
                pointerY < bounds.top + edgePx -> -stepPx
                pointerY > bounds.bottom - edgePx -> stepPx
                else -> 0f
            }
            if (delta != 0f) gridState.scrollBy(delta)
        }
    }

    // 「渐变边框按钮」样式开关（2026-10-22 需求 4 / 需求 8）。
    //
    // 读**生效值**（`DeveloperOptionsRepository.styleGradientButtonEnabled`，
    // 总开关关时回落 true = 与本次改造后的默认观感一致），不是原始值。
    val developerOptions = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    val gradientButton by developerOptions.styleGradientButtonEnabled
        .collectAsStateWithLifecycle(initialValue = true)

    // 当前选中的内置图标套（本轮需求 8：桌面快捷方式的图标跟随它）。
    //
    // 从**同一个** `settingsRepository` 读（与宫格顺序同源），
    // 不另建一个 `AppIconRepository`：这里只需要一个字符串 id，
    // 而 AppIconRepository 的价值在「切 alias」这类平台调用上（见其 KDoc）。
    //
    // initialValue 用 `SettingsRepository.DEFAULT_ICON_STYLE`：DataStore 首读要走磁盘，
    // 在它到达之前若用户就长按「添加到桌面」，快捷方式会先用默认套的图标创建。
    // 这个窗口只有一帧到几十毫秒，且后果仅是「这一次用了默认图标」——可接受；
    // 反过来若猜一个空串，会走到 [labShortcutIconResOf] 的 else 分支，
    // 结果同样是默认套，没有收益。
    val selectedIconId by settingsRepository.devIconStyle
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_ICON_STYLE)

    // ── 长按菜单（2026-10-25 重做版；2026-11-03 增加置顶/取消置顶切换）────────
    //
    // 形态仍是**自定义 Dialog + 卡片**：
    // - 上半：三枚排序动作胶囊（置顶 / 取消置顶、上移、下移，浅色有底色，等宽一行）；
    // - 下沿：左下角「恢复默认排序」（仅自定义过顺序时显示）、右下角「添加到桌面」，
    //   两个都是浅色有底色按钮（需求原文：「改为浅色有底色按钮，位置为左下角和右下角」）。
    //
    // ## 为什么拖拽之后还保留「上移 / 下移」
    //
    // 用户本轮要求「长按拖拽排序」，但没说可以删掉原有能力。拖拽适合大幅重排，
    // 而"只挪一位"用上移/下移更省事（尤其是长按后手指不想再拖）；
    // 两条路径读写的是**同一个** `lab_grid_order` 键，不会出现两份顺。
    // 「置顶」按钮在已置顶时切换为「取消置顶」——这也是旧版本没有的能力
    // （旧版只能置顶、不能取消，取消只能恢复默认排序）。
    pendingLongPress?.let { item ->
        val itemKey = labItemKey(item)
        val isPinned = itemKey in pinnedKeys
        // 当前这一格在完整顺序里的位置（每次重组重算：上移/下移之后它自己会变）。
        val position = orderedItems.indexOfFirst { labItemKey(it) == itemKey }
        val total = orderedItems.size
        // 串行保护（2026-11-01）：菜单不再点一次就关，用户可能连点「上移」。
        // 上一次落盘还没回来时再读 `orderedItems` 会拿到旧顺序、算错落点，
        // 因此一次只允许一个移动在飞。
        var moving by remember(itemKey) { mutableStateOf(false) }

        /** 把当前项在完整顺序里移动 [delta] 位（越界自动夹住），并落盘。 */
        fun moveBy(delta: Int) {
            if (moving) return
            moving = true
            scope.launch {
                try {
                    val current = orderedItems.map { labItemKey(it) }
                    val index = current.indexOf(itemKey)
                    if (index < 0) return@launch
                    val target = (index + delta).coerceIn(0, current.lastIndex)
                    if (target == index) return@launch
                    val moved = current.toMutableList().apply { add(target, removeAt(index)) }
                    // 顺序走统一的 writeOrder（它会自动补置顶前缀）。
                    writeOrder(moved, pinnedKeys)
                } finally {
                    moving = false
                }
            }
        }

        Dialog(onDismissRequest = { pendingLongPress = null }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        text = displayTitle(item),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.strongTextColor
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 2026-11-01：把「现在排第几」写出来。原来只说"调整顺序"，
                        // 用户点了几下不知道自己移到哪了，只能关掉菜单回去看宫格。
                        // 2026-11-03：补一句"可以直接拖动排序"，让拖拽入口可被发现。
                        text = if (position >= 0) {
                            "第 ${position + 1} 位 / 共 $total 项\n" +
                                "长按卡片可直接拖动排序，也可用下面的按钮微调"
                        } else {
                            "调整这一格的顺序，或添加到桌面"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    Spacer(Modifier.height(16.dp))

                    // 排序动作：三枚等宽胶囊（置顶 / 取消置顶、上移、下移）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LabSheetActionButton(
                            // 已置顶时按钮变成「取消置顶」：旧版本只能置顶、不能取消，
                            // 取消的唯一办法是「恢复默认排序」，会把手动顺序一起清掉。
                            text = if (isPinned) "取消置顶" else "置顶",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            enabled = true,
                            onClick = {
                                if (!moving) {
                                    moving = true
                                    scope.launch {
                                        try {
                                            val current = orderedItems.map { labItemKey(it) }
                                            if (isPinned) {
                                                // 取消置顶：只摘掉钉子，位置保持不变。
                                                writeOrder(current, pinnedKeys - itemKey)
                                            } else {
                                                // 置顶 = 提到第一位 + 打钉子（两件事一起做，
                                                // 否则"置顶了却没有图钉"或"有图钉却不在最前"）。
                                                val moved = listOf(itemKey) +
                                                    current.filter { it != itemKey }
                                                writeOrder(moved, pinnedKeys + itemKey)
                                            }
                                        } finally {
                                            moving = false
                                        }
                                    }
                                }
                            }
                        )
                        LabSheetActionButton(
                            text = "上移",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            enabled = position > 0,
                            // 不再 `pendingLongPress = null`：菜单留在原地，可以连续点
                            // （原来每移一格都要重新长按一次，这是本轮最主要的体验问题）。
                            onClick = { moveBy(-1) }
                        )
                        LabSheetActionButton(
                            text = "下移",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            enabled = position in 0 until total - 1,
                            onClick = { moveBy(1) }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 改名（2026-11-09 需求 3）：和排序放在一起，因为它也是"这一格怎么显示"
                    // 的一部分。改过的条目额外给一个「恢复原名」，否则用户改完就没法回去了。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LabSheetActionButton(
                            text = "重命名",
                            container = MaterialTheme.colorScheme.surfaceContainerHighest,
                            content = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                renamingItem = item
                                pendingLongPress = null
                            }
                        )
                        if (customNames.containsKey(itemKey)) {
                            LabSheetActionButton(
                                text = "恢复原名",
                                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                content = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    pendingLongPress = null
                                    scope.launch {
                                        settingsRepository.setLabCustomName(itemKey, "")
                                    }
                                }
                            )
                        }
                    }

                    // 从发现页搬进来的格子给一条「移回」回路：发现页长按对话框
                    // 承诺「在实验室长按那一格可以再移回来」，菜单必须兑现。
                    if (extraItems.any { labItemKey(it) == itemKey }) {
                        Spacer(Modifier.height(10.dp))
                        LabSheetActionButton(
                            text = "移回发现页",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                pendingLongPress = null
                                scope.launch {
                                    val updated = placement.toMutableMap().apply {
                                        put(itemKey, EntryPlacement.DISCOVER)
                                    }
                                    settingsRepository.setEntryPlacementOverrides(
                                        encodeEntryPlacement(updated)
                                    )
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(18.dp))

                    // 底沿：左下角「恢复默认排序」（只在没有自定义顺序时不显示——
                    // 没改过顺序时它是个无意义的按钮）、右下角「添加到桌面」。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (savedOrder.isNotEmpty()) {
                            LabSheetActionButton(
                                text = "恢复默认排序",
                                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                                content = MaterialTheme.colorScheme.onSurface,
                                onClick = {
                                    pendingLongPress = null
                                    scope.launch {
                                        // 2026-11-03：恢复默认时**保留置顶**——把默认顺序里
                                        // 的置顶项提到最前仍带钉子；没有置顶项时干脆清掉
                                        // 自定义顺序（写空列表 = 回到代码里的静态顺序）。
                                        if (pinnedKeys.isEmpty()) {
                                            writeOrder(emptyList(), emptySet())
                                        } else {
                                            val defaults = allItems.map { labItemKey(it) }
                                            val restored = defaults.filter { it in pinnedKeys } +
                                                defaults.filterNot { it in pinnedKeys }
                                            writeOrder(restored, pinnedKeys)
                                        }
                                    }
                                }
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        LabSheetActionButton(
                            text = "添加到桌面",
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                            onClick = {
                                pendingLongPress = null
                                // 需求 7 起「添加到桌面」直接发起固定请求，
                                // 系统那层确认框仍在（见 [pinLabShortcut] 的 KDoc）。
                                //
                                // 2026-10-30 用户要求：**要有明确反馈**。此前只调
                                // `requestPinShortcut`，桌面不支持时返回 false 也没有任何
                                // 提示，点完像没反应（这正是当初反复怀疑"功能坏了"的原因）。
                                val ok = pinLabShortcut(context, item, selectedIconId)
                                // 2026-11-03（需求 4）：这里的提示改成**系统 Toast**。
                                // 原因：长按菜单是一个 Dialog 窗口，页面级 Snackbar
                                // （onMessage）画在主窗口底层，会被弹窗遮住；系统 Toast
                                // 是独立窗口层，弹窗开着也能看到。
                                Toast.makeText(
                                    context,
                                    if (ok) {
                                        "已添加到桌面"
                                    } else {
                                        "添加失败：当前桌面不支持快捷方式"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // 改名输入框（2026-11-09 需求 3）。
    // 清空名字 = 恢复内置名（`setLabCustomName` 传空串会把它从表里删掉）。
    renamingItem?.let { target ->
        val key = labItemKey(target)
        TextInputDialog(
            title = "重命名「${target.title}」",
            label = "显示名称",
            initial = customNames[key] ?: target.title,
            confirmLabel = "保存",
            onConfirm = { name ->
                renamingItem = null
                scope.launch { settingsRepository.setLabCustomName(key, name) }
            },
            onDismiss = { renamingItem = null }
        )
    }

    // 「添加到桌面」的确认框**已整体删除**（需求 7）。
    //
    // ## 这里原本有什么，以及为什么现在不需要了
    //
    // 原先是「先弹这个框问『把「天气」添加到桌面？』→ 点了『添加』才真的 pin」。
    // 理由记在这里以备将来有人想再加回来时，能看到当初的两层分工：
    // 桌面快捷方式会在用户桌面上留下一个**需要他手动删除**的持久副作用，
    // 长按又太容易误触（滚动时手抖），所以应用这层负责确认「是不是这一格」，
    // 而系统那层（`requestPinShortcut` 触发的桌面确认框）负责「要不要放到桌面」。
    //
    // 需求 7 之后本层被去掉了，因为**长按菜单已经把话说清楚了**：
    // 用户是从一个写着「添加到桌面」的菜单项点进来的，那就是表态本身；
    // 应用再追问一句「你确定吗」不构成新信息，只增加一次点击。
    // 确认并没有消失——它只是交给了桌面那一层，而那一层本来就一直在，
    // 应用管不着也不该绕（详见 [pinLabShortcut] 的 KDoc）。
    //
    // 连带删除的还有 `pendingPin` 状态，以及 `SettingsRepository` 里那对
    // 「记录这一格加过桌面」的读写成员（`labPinnedShortcuts` / `addLabPinnedShortcut`）：
    // 它们**只**服务于「判断这一格加过没有、从而决定要不要问」，
    // 现在不再问，那个集合就没有任何判读方了。
    // 按项目纪律不留死代码——那对成员已在 v1.1.75 一并删除（见 `SettingsRepository`
    // 里 `LAB_PINNED_SHORTCUTS` 键上方的说明）。
    // 键本身留在 DataStore 里不清理：老用户设备上那份历史集合不占空间、不影响行为，
    // 删键定义反而会让它变成既读不到也说不清来历的孤儿键。

    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop.brush)
            // 记录页面根节点在窗口中的位置：拖拽浮起卡用「指针窗口坐标 - 根节点坐标」
            // 换算成这个 Box 内的偏移，因此根节点是否从 (0,0) 开始都能算对。
            .onGloballyPositioned { drag.rootOrigin = it.positionInRoot() }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("实验室") },
                    // 标题栏跟随背景透明，否则会在页顶压出一条与渐变割裂的纯色带。
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = backdrop.onBackdrop,
                        navigationIconContentColor = backdrop.onBackdrop
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // ---- 两列宫格（可滚动 + 长按拖拽排序）----
                //
                // ⚠️ v1.1.79 真机实测修复：**宫格必须能滚、且只能占剩余高度**。
                //
                // 改成 17 项后（9 行卡片 + 标题栏）内容在小屏机上超过一屏，
                // 而原来的写法是「宫格 + Spacer(weight(1f)) + 底部按钮」的普通 Column：
                // Column 不滚动，超出部分把**最后一个子项（按钮）**挤出屏幕，
                // 系统只能把它压成一条细长条——用户看到的就是「新增功能按钮显示错误」。
                //
                // 修法：宫格放进 weight(1f) 的容器里吃满剩余高度，
                // 按钮作为 Column 的固定尾部永远保留 50dp。宫格再多也只会滚动，不会挤按钮。
                // 小窗紧凑模式（2026-10-25 需求）：宫格间距收窄一档（12 → 8dp），
                // 窄窗口里一行仍放得下两张卡；全屏观感不变。
                //
                // 2026-11-03（需求 4）：容器从「Column + chunked(2)」换成 LazyVerticalGrid。
                // 原因：只有 Lazy 的网格才提供 `animateItem()`（其它卡片平滑让位）与
                // 滚动位置管理，而"拖拽排序"的灵魂正是其它项平滑让位；手写 Column
                // 重排只能让卡片瞬间跳位。网格本身仍放在 weight(1f) 里吃满剩余高度，
                // 底部「新增功能」按钮保持固定尾部，v1.1.79 的那条修复不变。
                val compactWindow = rememberIsCompactWindow()
                val gridGap = if (compactWindow) 8.dp else 12.dp
                // 拖拽中的显示顺序取 `workingKeys`，松手后回到 `orderedItems`
                // （写入完成前由乐观顺序兜住，不会闪回旧顺序）。
                val gridItems = if (drag.draggingKey != null && drag.workingKeys.isNotEmpty()) {
                    drag.workingKeys.mapNotNull { itemsByKey[it] }
                } else {
                    orderedItems
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // 拖拽入口提示：拖拽是隐藏手势，不写一句用户很难发现。
                    Text(
                        text = "长按卡片可拖动排序；长按后松手可打开更多操作",
                        fontSize = 12.sp,
                        color = backdrop.onBackdrop.copy(alpha = 0.75f),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned {
                                drag.gridBounds = it.boundsInRoot()
                                drag.gridOriginInRoot = it.positionInRoot()
                            }
                            // 手势挂在**网格外面的稳定节点**上，而不是每张卡片上：
                            // 拖拽过程中卡片顺序会变、卡片会被布局移动，手势若挂在卡片上，
                            // 事件坐标会跟着卡片走（局部坐标系漂移），位移量被"卡片自己挪动"
                            // 污染——这正是本项目 HANDOVER 5.20 记录过的那个坑。
                            // 挂在外层容器后，坐标基准永远是这块不动的 Box。
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(
                                        requireUnconsumed = false,
                                        pass = PointerEventPass.Initial
                                    )
                                    val pressedKey = keyAtRoot(
                                        drag.itemBounds,
                                        drag.gridOriginInRoot + down.position
                                    )
                                    if (pressedKey == null) return@awaitEachGesture

                                    // ── 阶段 1：长按判定（不消费事件，让竖向滚动照常开始）──
                                    var longPressPosition = down.position
                                    var upBeforeLongPress = false
                                    var abortedByScroll = false
                                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes
                                                .firstOrNull { it.id == down.id } ?: break
                                            if (change.changedToUpIgnoreConsumed()) {
                                                upBeforeLongPress = true
                                                break
                                            }
                                            // 父级滚动已经开始消费 → 交给滚动，不抢手势。
                                            if (change.isConsumed) {
                                                abortedByScroll = true
                                                break
                                            }
                                            longPressPosition = change.position
                                            val moved =
                                                (change.position - down.position).getDistance()
                                            if (moved > viewConfiguration.touchSlop) {
                                                abortedByScroll = true
                                                break
                                            }
                                        }
                                    }

                                    if (upBeforeLongPress) {
                                        // 普通点击：与改造前一致（触感 + 打开页面）。
                                        haptic.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        allItems.firstOrNull {
                                            labItemKey(it) == pressedKey
                                        }?.let { openItem(it) }
                                        return@awaitEachGesture
                                    }
                                    if (abortedByScroll) return@awaitEachGesture

                                    // ── 阶段 2：进入拖拽 ──
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    drag.pointerInRoot =
                                        drag.gridOriginInRoot + longPressPosition
                                    startDrag(pressedKey)
                                    if (drag.draggingKey == null) return@awaitEachGesture
                                    var movedPx = 0f
                                    var canceled = false
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val change = event.changes
                                            .firstOrNull { it.id == down.id }
                                        if (change == null) {
                                            canceled = true
                                            break
                                        }
                                        if (change.changedToUpIgnoreConsumed()) break
                                        if (change.isConsumed) {
                                            canceled = true
                                            break
                                        }
                                        val delta = change.positionChange()
                                        if (delta != Offset.Zero) {
                                            movedPx += delta.getDistance()
                                            // 消费掉位移：网格自己不再滚动，滚动交给贴边自动滚动。
                                            change.consume()
                                            drag.pointerInRoot += delta
                                            updateDragTarget()
                                        }
                                    }
                                    if (canceled) cancelDrag() else finishDrag(movedPx)
                                }
                            }
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = gridState,
                            // 拖拽中禁止用户滚动：滚动由贴边自动滚动接管
                            // （见上方 LaunchedEffect），否则手指拖动会被当成滚动。
                            userScrollEnabled = drag.draggingKey == null,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 12.dp,
                                bottom = 12.dp
                            ),
                            horizontalArrangement = Arrangement.spacedBy(gridGap),
                            verticalArrangement = Arrangement.spacedBy(gridGap)
                        ) {
                            // 顺序没就位（`orderReady=false`）时**一张卡都不画**：
                            // 那一帧 `gridItems` 还是「默认顺序 + 空快照」，画出来就会在
                            // 真实顺序到达后整屏挪位——正是需求 8 要修的那一跳。
                            // 网格本身仍组合（外层 Box 的手势与坐标基准依赖它），只是空集合。
                            items(
                                items = if (orderReady) gridItems else emptyList(),
                                key = { labItemKey(it) }
                            ) { item ->
                                val key = labItemKey(item)
                                val isDragging = drag.draggingKey == key
                                LabCard(
                                    item = item,
                                    title = displayTitle(item),
                                    pinned = key in pinnedKeys,
                                    modifier = Modifier
                                        // 其它卡片让位动画：顺序一变就平滑挪到新位置。
                                        .animateItem()
                                        // 被拖起的那一张留一个"占位"：仍参与布局，
                                        // 所以其它卡会绕开它让位；但画成透明，真正跟手的是
                                        // 页面层那张浮起卡。⚠️ 不能用"从列表里移除"的做法：
                                        // 移除会销毁它的 pointerInput 节点，手指还在屏幕上时
                                        // 手势当场断掉（长按拖动会半途失效）。
                                        .alpha(if (isDragging) 0f else 1f)
                                        .onGloballyPositioned {
                                            drag.itemBounds[key] = it.boundsInRoot()
                                        },
                                    onOpen = { openItem(item) }
                                )
                            }
                        }
                    }
                }

                // ---- 底部「新增功能」 ----
                // 贴在页面最底部（即 Snackbar 出现的高度），带系统导航栏内边距。
                // 宫格改用 weight(1f) 后这里不再需要 Spacer(weight(1f))——
                // 那个 Spacer 原本用来"把按钮顶到底部"，但它在内容超高时会被压成 0，
                // 反而让按钮承担了压缩（就是上面注释里那个 bug）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⚠️ 2026-10-28（第二次下移）真机实测：这里**不能再加**
                        // `navigationBarsPadding()`。
                        //
                        // 外层 Column 已经 `.padding(innerPadding)`，而 Scaffold 的
                        // `innerPadding` 默认就含系统栏内边距（`contentWindowInsets`
                        // = systemBars）——底边这一份已经给过了。再叠一层就是**双份**：
                        // 真机（1280×2772 / density 520，导航栏 inset ≈ 47dp）实测
                        // 按钮下沿 y=2439，离屏幕底还有 333px ≈ **102dp** 空白
                        // （= 47 + 47 + 8），按钮被这份多出来的内边距顶得老高。
                        // 去掉它之后空白回到 47dp（导航栏）+ 8dp，按钮下移约 47dp。
                        //
                        // 上下不对称的 20dp / 8dp 是上一轮定的：顶边距维持与宫格的层次，
                        // 底边距尽量小——底边距才是决定"离屏幕底多远"的那一项。
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)
                ) {
                    if (gradientButton) {
                        // 渐变边框按钮（2026-10-22 需求 4）：参数取自用户给的
                        // jztheme.css —— `linear-gradient(135deg, #38bdf8, #8b5cf6)` +
                        // `padding: 3px; border-radius: 12px`。
                        // 做法：外层渐变底 → 内缩 3dp 的圆角内容层，就是 CSS 里
                        // "渐变底 + 内层深色块"的那套（Compose 没有 border-image，
                        // 只能这样叠两层）。
                        GradientBorderButton(
                            text = "新增功能",
                            onClick = { onOpenEntry(PlaceholderEntry.LAB_NEW.value) },
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        )
                    } else {
                        Button(
                            onClick = { onOpenEntry(PlaceholderEntry.LAB_NEW.value) },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 高度钉死 50dp（height 而不是 heightIn(min)）：
                                // 真机出现过按钮被压成一条细长的异常。现在布局上已不会再压它，
                                // 这一行作为第二道保险保留。
                                .height(50.dp)
                        ) {
                            Text("新增功能", fontSize = 16.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // ---- 拖拽中的浮起卡片（2026-11-03 需求 4）-------------------------
        //
        // 画在 Scaffold **之外**（根 Box 的最后一个子项）：它要浮在宫格与底部按钮之上，
        // 且必须脱离 LazyGrid 的裁剪。真正跟手的是这张卡；网格里被拖的那一张只是
        // 透明占位（见上方 LazyVerticalGrid 里的注释）。
        //
        // 位置换算：指针在窗口里的坐标 - 抓取点相对卡片的偏移 - 页面根节点坐标
        // = 浮起卡在根 Box 内的左上角；再配合 pointerInput 里消费的位移，
        // 视觉上就是"卡片被提起来粘在手指上"。
        val draggingKey = drag.draggingKey
        val draggingItem = draggingKey?.let { itemsByKey[it] }
        if (draggingItem != null && drag.itemSize != IntSize.Zero) {
            val cardWidth = with(density) { drag.itemSize.width.toDp() }
            val cardX = drag.pointerInRoot.x - drag.grabOffset.x - drag.rootOrigin.x
            val cardY = drag.pointerInRoot.y - drag.grabOffset.y - drag.rootOrigin.y
            Box(
                modifier = Modifier
                    .offset { IntOffset(cardX.roundToInt(), cardY.roundToInt()) }
                    .width(cardWidth)
                    .graphicsLayer {
                        // 抬起感：轻微放大 + 投影；不做旋转（尺码/布局调试时旋转会干扰判断）。
                        scaleX = 1.04f
                        scaleY = 1.04f
                        shadowElevation = 16.dp.toPx()
                        shape = RoundedCornerShape(12.dp)
                    }
            ) {
                LabCardSurface(
                    item = draggingItem,
                    title = displayTitle(draggingItem),
                    pinned = labItemKey(draggingItem) in pinnedKeys
                )
            }
        }
    }
}

/**
 * 需求 4：进本页时关掉系统导航栏的**对比度强制遮罩**，让页面背景铺到屏幕底边。
 *
 * ## 它解决什么
 *
 * `MainActivity` 走无参 `enableEdgeToEdge()`，它给导航栏叠了一层
 * `SystemBarStyle.auto(TRANSPARENT, DEFAULT_NAVIGATION_BAR_SCRIM)`——
 * 底色透明**但**盖一层半透明灰，保证图标在任何背景上都可读。
 * 本页 `Scaffold` 的容器色是 `Transparent`（为了透出整屏渐变），
 * 这层灰就无处隐藏，看上去就是屏幕最底下多了一条浅色实心横条。
 * 详见 [LabScreen] 里「需求 4」那段调用处的注释。
 *
 * ## 为什么做成页级、而不是改 `enableEdgeToEdge` 的全局参数
 *
 * 去掉遮罩会让手势条图标失去对比度保护。本页渐变起点色足够深或足够艳，
 * 白字/黑字始终压得住（判定见 [backdropForegroundOf]）；但其它浅色页面未必。
 * 因此只在本页生效，进出自动还原，不污染全局策略。
 *
 * ## 归还策略
 *
 * `onDispose` 里把原值**原样写回**，而不是写死某个值——
 * 离开本页后紧接着显示的页面（游戏页等）各自管自己的系统栏，
 * 若这里硬写会覆盖掉它们的取值。
 *
 * ## 如实记录的 API 限制
 *
 * - `isNavigationBarContrastEnforced` 自 **API 29** 起提供，本项目 `minSdk = 29`，
 *   故无需 `@RequiresApi` 版本分支；
 * - 部分 ROM 可能忽略该标志，届时仍会看到遮罩；
 * - **三键导航下由系统强制绘制导航栏底色，本 API 管不到**：
 *   本项目以手势导航为主，该效果可保证，三键导航不保证。
 */
@Composable
private fun TransparentNavigationBarEffect() {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window) {
        if (window == null) {
            onDispose { }
        } else {
            // ⚠️ 这里必须用 **Window** 的属性，不是 `WindowInsetsControllerCompat` 的。
            // `isNavigationBarContrastEnforced` 是 `android.view.Window` 的 API 29 属性
            // （控制"系统是否强制给导航栏加一层对比度遮罩"）；
            // `WindowInsetsControllerCompat` 上**没有**这个属性，只有系统栏图标明暗那类。
            // 本项目 minSdk = 29，故无需版本分支。
            val previous = window.isNavigationBarContrastEnforced
            // 关掉对比度强制 → 系统不再额外叠加那层半透明灰，背景得以铺到底。
            window.isNavigationBarContrastEnforced = false
            onDispose {
                // 原样归还，别把本页的取值泄漏给后面显示的页面。
                window.isNavigationBarContrastEnforced = previous
            }
        }
    }
}

/**
 * 实验室页的渐变背景：一条 [Brush] 加上「压在这条渐变上该用什么前景色」。
 *
 * @property brush 铺满整页（含标题栏）的渐变。
 * @property onBackdrop 标题栏文字 / 返回箭头的前景色。
 */
private data class LabBackdrop(val brush: Brush, val onBackdrop: Color)

/**
 * 构造实验室页的渐变背景。
 *
 * ## 色值来自哪里（v1.1.68 需求 6 起改为「抓取 + 分类」）
 *
 * 渐变库在 [LabGradients]：开发期抓自 <https://ohmycolor.app/cn/gradients/> 的 **65 条**
 * `linear-gradient(135deg, ...)`，按**色相属性**分成两组（v1.1.69 需求 2 起改为按
 * 深浅色模式分池，见 [rememberLabBackdrop]）——
 * - 浅色模式用 [LabGradients.VIVID]（蓝 / 红 / 绿 / 紫等高饱和艳色）
 * - 深色模式用 [LabGradients.DARK]（灰 / 黑 / 深蓝等低明度深色）
 *
 * 抓取固定在开发期、不放到运行期：运行期去请求那个站点会平白多一个外部依赖
 * （本项目一直坚持「除天气外不外发请求」），墙了/挂了还会让本页背景变空白。
 *
 * 色值同样**刻意不接主题色**：这些是美术资源，用 `colorScheme` 推导只会得到主题色的深浅变体，
 * 跟抓来的色值对不上。「跟随主题色」在本页体现为**卡片、图标、底部按钮**仍取主题色系。
 *
 * ## 为什么是「轮换」而不是只用一条
 *
 * 一个页面同时只能显示一条背景。需求要「壁纸轮换」，所以每次进入本页都往前走一条。
 * 游标是**进程级**的 [darkGradientCursor] / [vividGradientCursor]，配合调用处的 `remember`
 * （每次进入本页都会重新组合一次）实现「进一次换一条」；退出再进来会继续往下轮。
 * 两个池子各有一个游标：深色模式只走深色池，不会被浅色模式的进入次数打乱。
 *
 * 取模用 `.mod()` 而不是 `%`：`AtomicInteger` 只增不减，迟早溢出成负数，
 * 而 `mod` 保证结果非负（`%` 在负数上会返回负下标 → 崩溃）。
 *
 * ## 135deg 在 Compose 里怎么写
 *
 * CSS 的 `135deg` 指「方向从正上方顺时针转 135°」，也就是**从左上指向右下**。
 * `Brush.linearGradient` 的默认起止点恰好是 `Offset.Zero → Offset.Infinite`，
 * 而 `Offset.Infinite` 会在绘制时被解析为「绘制区域的宽高」，
 * 于是默认行为就是左上 → 右下的对角渐变，与 `135deg` 完全等价。
 * **不要**手写 `end = Offset(width, height)`：那样得先拿到尺寸，白绕一圈。
 *
 * ## 前景色怎么定
 *
 * 与 `AuthComponents.rememberAuthColors` 同一口径：看**起点色**（左上角，标题与返回箭头
 * 就压在那里）的明度，暗底用白字、亮底用近黑字。
 * 本轮的四条渐变起点色明度都在 0.03~0.34，所以四条都是白字；
 * 但规则保留下来，以后换色值不容易翻车。
 *
 * ⚠️ 三条浅色里的「绿 → 薄荷」那条，起点 `#56AB2F` 上的白字对比度约 2.9:1，
 * 属于「大号粗体够用、正文偏紧」的水平。本页前景只有 22sp 的标题与返回箭头，可以接受；
 * 若要更稳，把该条的起点换成更深一档的绿即可。
 */
@Composable
private fun rememberLabBackdrop(): LabBackdrop {
    // ── 背景模式（本轮需求 11b）────────────────────────────────────────────
    //
    // 需求原文：「开发者选项背景设置选项（默认：与目前一致，按规则随机轮换；
    // 经典：跟随系统的浅色与深色；自定义：自行挑选颜色）」。
    // 三档的实际取值走 `SettingsRepository.devLabBackgroundMode`（与开发者选项页
    // 同一份 DataStore，改完回本页立刻生效），下面按模式分流：
    //
    // - `random`：走既有的"按深浅色分池轮换"（下方原逻辑，一字未动）；
    // - `classic`：跟随系统深浅色的**纯色**——取 `colorScheme.background`
    //   （浅色 `#EDEDED` / 深色 `#111111`，与全局窗口底同源）。前景色按底色明度配，
    //   浅底深字 / 深底浅字，与"随机轮换"那一支的判定同一条规则；
    // - `custom`：用户挑的固定颜色（深浅色模式都显示它），前景色同样按明度配。
    //
    // ## 为什么三个分支都返回同一个 LabBackdrop
    //
    // 本页所有消费方（Scaffold 容器透明、标题栏前景、卡片底色）都只认
    // `brush` 与 `onBackdrop` 两个值——纯色在 Brush 里就是两个相同端点的
    // linearGradient，这样下游一行都不用改，三种模式走同一条渲染路径。
    // ⚠️ `LocalContext.current` 必须在 `remember` **外面**读：
    // 它是 `CompositionLocal`，只能在组合期间读取；写进 `remember { ... }`
    // 的 lambda 里会被编译器判成"非组合上下文调用 @Composable"。
    // 先取值、再把它作为 `remember` 的 key 与捕获对象。
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }
    val bgMode by settingsRepository.devLabBackgroundMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.LAB_BG_MODE_RANDOM)
    val customColor by settingsRepository.devLabBackgroundCustomColor
        .collectAsStateWithLifecycle(initialValue = null)

    return when (bgMode) {
        SettingsRepository.LAB_BG_MODE_CLASSIC -> {
            // 经典：跟随系统深浅色的纯色。直接用 colorScheme.background，
            // 不新造色值（它就是全局窗口底，与其它页面完全一致）。
            val solid = MaterialTheme.colorScheme.background
            LabBackdrop(
                brush = Brush.linearGradient(listOf(solid, solid)),
                onBackdrop = backdropForegroundOf(solid)
            )
        }

        SettingsRepository.LAB_BG_MODE_CUSTOM -> {
            // 自定义：用户挑的固定色。未挑过色（DataStore 为 null）时回落 classic——
            // "选了 custom 却没给色" 不该把页面画成透明或黑色。
            val argb = customColor
            val solid = if (argb != null) Color(argb) else MaterialTheme.colorScheme.background
            LabBackdrop(
                brush = Brush.linearGradient(listOf(solid, solid)),
                onBackdrop = backdropForegroundOf(solid)
            )
        }

        // random（默认）与无法识别的脏值都走这一支：脏值回落默认，
        // 与本仓库"读脏数据回落既有行为"的一贯口径一致。
        else -> {
            // 分池依据是**系统深浅色模式**（v1.1.69 需求 2）。
            //
            // v1.1.68 的口径是「按本机时间分池」（18:00~次日 6:00 算夜间，浅色白天用、深色晚上用）。
            // 本轮需求收紧为：**灰黑等深色背景只在深色模式下使用与轮换，蓝红等艳色背景只在
            // 浅色模式下使用与轮换**。分池条件因此从「时间」换成「深浅色偏好」——
            // 两者本来正交（深色模式用户白天也会进本页），按模式分池后
            // 「深色模式 + 白天」不再出现浅色渐变，与页面内卡片（跟主题走）对比更协调。
            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
            // remember 的 key 用 `dark`：用户切深浅色模式时本页要**立刻**换池
            // （不能沿用旧模式的渐变——那正是需求要消除的组合）。
            val colors = remember(dark) {
                if (dark) nextGradient(LabGradients.DARK, darkGradientCursor)
                else nextGradient(LabGradients.VIVID, vividGradientCursor)
            }
            LabBackdrop(
                brush = Brush.linearGradient(colors),
                onBackdrop = backdropForegroundOf(colors.first())
            )
        }
    }
}

/**
 * 取某个渐变池里的「下一条」，游标只在进程内向前走。
 */
private fun nextGradient(pool: List<List<Color>>, cursor: AtomicInteger): List<Color> =
    pool[cursor.getAndIncrement().mod(pool.size)]

/** 按**起点色**的明度决定压在渐变上的前景色，见 [rememberLabBackdrop] 的说明。 */
private fun backdropForegroundOf(startColor: Color): Color =
    if (startColor.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White

/**
 * 深色池 / 艳色池各自的轮换游标（v1.1.69 需求 2）。
 *
 * 放在**顶层**（而不是 `remember` 里）是关键：它必须跨「进入 / 离开实验室」存活，
 * 否则每次进来都会拿到同一条，等于没轮换。
 *
 * 两个池子各有一个游标：深色模式只走深色池、浅色模式只走艳色池，
 * 切换深浅色时不会"串台"到另一池的进度上。
 */
private val darkGradientCursor = AtomicInteger(0)
private val vividGradientCursor = AtomicInteger(0)


/**
 * 长按菜单里的**浅色有底色**按钮（2026-10-25 需求）。
 *
 * 原先的 AlertDialog 里是纯文字 `TextButton`，按钮与正文视觉上分不开；
 * 现在统一成胶囊底色按钮——排序动作用中性浅底（`surfaceContainerHigh`），
 * 「添加到桌面」用主题色浅底（`primaryContainer`），主次一眼可分。
 */
@Composable
private fun LabSheetActionButton(
    text: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否可点。2026-11-01 新增：排序菜单里的「上移 / 下移」在已经到顶 / 到底时
     * 必须**明确灰掉**——原来它们永远可点，点了没反应，用户会以为排序坏了。
     */
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            // 2026-11-14 需求 3：深色模式下「长按菜单」里的按钮与卡片底色几乎同色，
            // 只剩文字、看不出按钮轮廓。这里补一圈 1dp 描边：
            // 深色下是比底色亮一档的 outline，浅色下是一条淡灰线，两个模式都成立。
            // 不用阴影：软件渲染上 `.shadow()` 会把圆角渲染成大片灰（见 HANDOVER 的坑）。
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                shape = RoundedCornerShape(999.dp)
            )
            // 禁用时只降透明度、不改变尺寸：按钮位置保持不动，不会因为
            // "能不能点"而在菜单里跳一下（与图标页的禁用口径一致）。
            .alpha(if (enabled) 1f else 0.4f)
            .pressScale(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
    }
}

/**
 * 宫格里的一个功能卡片（**纯展示 + 可访问性语义**；点按/长按拖拽由外层网格统一接管）。
 *
 * ## 为什么手势不再挂在卡片上（2026-11-03 需求 4 改动）
 *
 * 旧版用 `combinedClickable` 处理点按与长按菜单。拖拽排序要求"长按之后继续拖动"，
 * 且拖动过程中卡片会因重排而**移动位置**：手势力挂在卡片上时，事件坐标跟着卡片走，
 * 每个换位都会让 `positionChange` 凭空跳一下（HANDOVER 5.20 记录的坐标系漂移坑）。
 * 因此把整套手势收口到网格外层那个**不动的 Box** 上，卡片只负责画自己。
 *
 * 卡片自身仍保留 `semantics` 的点击语义：读屏（TalkBack）仍能把每一格读成可按按钮，
 * 不会因为手势上移而丢掉无障碍入口。
 *
 * @param pinned 是否已置顶。true 时右上角画一枚图钉角标。
 * @param onOpen 仅用于无障碍语义回调（读屏点击）；正常触摸点按由外层手势识别。
 */
@Composable
private fun LabCard(
    item: LabItem,
    title: String,
    pinned: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    LabCardSurface(
        item = item,
        title = title,
        pinned = pinned,
        modifier = modifier.semantics {
            role = Role.Button
            // 语义回调的 `onClick` 是 SemanticsPropertyReceiver 的扩展函数；
            // 形参刻意叫 `onOpen`，避免与它同名解析歧义。
            onClick(label = "打开$title") {
                onOpen()
                true
            }
        }
    )
}

/**
 * 卡片的视觉本体（列表里与拖拽浮起卡共用）。
 *
 * 抽出来的原因：拖拽时页面层要再画一张**一模一样的**卡片跟着手指走。
 * 若各写一份，下一次改内边距/字号时必然只改一处（本项目反复记过这个坑）。
 */
@Composable
private fun LabCardSurface(
    item: LabItem,
    title: String,
    pinned: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 置顶时右侧多留 16dp 给图钉角标，避免角标压住文字。
                    .padding(
                        start = 14.dp,
                        end = if (pinned) 30.dp else 14.dp,
                        top = 18.dp,
                        bottom = 18.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (pinned) {
                PinBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                )
            }
        }
    }
}

/**
 * 「已置顶」图钉角标（2026-11-03 需求 4）。
 *
 * 配色用 `primary` 底 + `onPrimary` 图标：主题色对深浅两套主题都保证"同色系高对比"，
 * 不写死颜色、也不用半透明（半透明图钉压在不同渐变背景上会看不清）。
 */
@Composable
private fun PinBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.PushPin,
            contentDescription = "已置顶",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(10.dp)
        )
    }
}

/**
 * 实验室宫格的拖拽排序状态（2026-11-03 需求 4）。
 *
 * ## 为什么用一个类而不是散在 Composable 里的七八个 `var`
 *
 * 拖拽涉及"正在拖谁 / 拖到哪个顺序 / 指针在哪 / 各卡片几何"四组状态，且会同时被
 * 手势协程、卡片 `onGloballyPositioned`、浮起层三处读写。散着写时任何一次重组漏传
 * 一个都会表现为"卡片不跟手 / 让位动画没了"；收进一个 `remember` 的类后，
 * 引用只有一个对象，手势协程捕获的也是同一个实例（不存在闭包捕获旧值问题）。
 *
 * ## 坐标系约定
 *
 * - [itemBounds]、[gridBounds]、[gridOriginInRoot]、[pointerInRoot]、[rootOrigin]
 *   全部是**窗口（root）坐标**，因此可以直接互相比较/相减；
 * - 浮起卡的显示偏移 = `pointerInRoot - grabOffset - rootOrigin`；
 * - 指针位置由外层手势在固定节点上累加 `positionChange` 得到，不随卡片重排漂移。
 */
@Stable
private class LabDragState {
    /** 正在拖的条目 key；null = 没有拖拽进行中（此时网格走正常顺序）。 */
    var draggingKey by mutableStateOf<String?>(null)

    /** 拖拽中的临时顺序（松手后写盘；取消则丢弃）。 */
    var workingKeys by mutableStateOf<List<String>>(emptyList())

    /** 指针在窗口里的位置。 */
    var pointerInRoot by mutableStateOf(Offset.Zero)

    /** 按下点相对被拖卡片左上角的偏移（让卡片"粘"在手指按下的那个点上）。 */
    var grabOffset by mutableStateOf(Offset.Zero)

    /** 被拖卡片的实测尺寸（决定浮起卡宽度）。 */
    var itemSize by mutableStateOf(IntSize.Zero)

    /** 页面根 Box 在窗口里的位置。 */
    var rootOrigin by mutableStateOf(Offset.Zero)

    /** 网格可视区在窗口里的位置。 */
    var gridOriginInRoot by mutableStateOf(Offset.Zero)

    /** 网格可视区在窗口里的矩形（贴边自动滚动用）。 */
    var gridBounds by mutableStateOf<Rect?>(null)

    /** 每张卡片最近一次布局后的窗口矩形（命中测试用）。 */
    val itemBounds = mutableStateMapOf<String, Rect>()

    /** 结束拖拽：清掉除几何缓存外的全部状态（几何缓存留着给下一次拖拽复用）。 */
    fun clear() {
        draggingKey = null
        workingKeys = emptyList()
        itemSize = IntSize.Zero
    }
}

// `lab_grid_order` 里的「置顶」前缀（2026-11-03 需求 4）。
//
// ## 为什么把置顶信息编进顺序串，而不是新增一个存储键
//
// 本任务只允许改 UI 文件，不能给 `SettingsRepository` 加新键；而「图钉」必须持久化
// （否则重启后钉子消失，用户会以为置顶没生效）。顺序串的**唯一读取方就是本文件**
// （已核对全仓库），因此用前缀编码不会影响任何其它模块；老数据没有前缀 =
// 没有置顶，向后兼容、不会崩。代价是存储格式不再是"纯 key 列表"——已在此处写明。
private const val PINNED_KEY_PREFIX = "^"

/** 去掉置顶前缀后的完整顺序。 */
private fun parseOrderKeys(stored: List<String>): List<String> =
    stored.map { it.removePrefix(PINNED_KEY_PREFIX) }

/** 从顺序串里解析出被置顶的 key 集合。 */
private fun parsePinnedKeys(stored: List<String>): Set<String> =
    stored.asSequence()
        .filter { it.startsWith(PINNED_KEY_PREFIX) }
        .map { it.removePrefix(PINNED_KEY_PREFIX) }
        .toSet()

/** 写回时补上置顶前缀（与 [parsePinnedKeys] 严格配对）。 */
private fun withPinPrefix(keys: List<String>, pinned: Set<String>): List<String> =
    keys.map { key -> if (key in pinned) PINNED_KEY_PREFIX + key else key }

/** 命中测试：窗口坐标 [point] 落在哪张卡片的矩形里。 */
private fun keyAtRoot(bounds: Map<String, Rect>, point: Offset): String? =
    bounds.entries.firstOrNull { it.value.contains(point) }?.key


// ─────────────────────────────────────────────────────────────────────────────
// 渐变边框按钮（2026-10-22 需求 4）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 渐变按钮（参数对照用户给的 `jztheme.css`）。
 *
 * ## 参数来源（两份 CSS 的合并口径）
 *
 * - 第一份 `.jztheme-tools-css-gradient-border` 给的是**描边形态**：
 *   `padding: 3px; border-radius: 12px`（外层渐变 + 内层实色）；
 * - 第二份 `.jztheme-gradient` 给的是**渐变配色**：
 *   `linear-gradient(135deg, rgba(217,70,239,1) 0%, rgba(99,102,241,1) 100%)`
 *   —— 品红 `#D946EF` → 靛蓝 `#6366F1`。
 *
 * 用户的最终要求是「新增功能按钮**背景**也采用渐变」，所以这一版是
 * **整块渐变填充 + 白字**（不再保留内层实色块），圆角仍取 12dp。
 *
 * ## 为什么文字用白色
 *
 * 渐变两端都是中等偏深的饱和色（相对亮度都在 0.2 附近），白字的对比度足够；
 * 用主题色/深色字会与渐变"打架"，也丢了设计稿那种饱满感。
 */
@Composable
private fun GradientBorderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    // 135deg：左上 → 右下（与 CSS 同向）。
                    colors = listOf(GRADIENT_START, GRADIENT_END),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .pressScale(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

/** 描边宽度（第一份 CSS 的 `padding: 3px`；整块渐变后不再使用）。 */
private val GRADIENT_BORDER_WIDTH = 3.dp

/**
 * 渐变两端（第二份 CSS 的 `rgba(217,70,239,1)` → `rgba(99,102,241,1)`）。
 *
 * 第一版用的是 `#38BDF8 → #8B5CF6`（蓝 → 紫），用户随后给了配色版 CSS，
 * 以这份为准：品红 → 靛蓝，观感更"按钮"、不像链接。
 */
private val GRADIENT_START = androidx.compose.ui.graphics.Color(0xFFD946EF)
private val GRADIENT_END = androidx.compose.ui.graphics.Color(0xFF6366F1)
