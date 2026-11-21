package com.morecheck.ui.lab

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MoreCheckApp
import com.morecheck.MainActivity
import com.morecheck.R
import com.morecheck.ui.nav.PlaceholderEntry
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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

    /** 登录提示功能（控制「打开软件时提示登录」那个引导弹窗的开关，v1.1.65 新增）。 */
    LOGIN_PROMPT("login_prompt_setting"),

    /** 记事本（一条笔记：文字 + 图片，返回时自动保存）。 */
    NOTEBOOK("notebook"),

    /**
     * 智能体（AI 对话，OpenAI 兼容，用户自带 API Key）。
     *
     * 本轮由占位页升级为真实页面，[PlaceholderEntry.AGENT] 随之删除。
     */
    AGENT("agent")
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
    // 「天气」是真实页面（联网查询当地天气）。
    LabItem("天气", LabTarget.RealPage(LabRealPage.WEATHER), Icons.Filled.WbSunny),
    // 「开发者选项」v1.1.64 由占位页升级为真实页面（需求：更名 + 仿手机开发者选项）。
    LabItem("开发者选项", LabTarget.RealPage(LabRealPage.DEVELOPER_OPTIONS), Icons.Filled.Build),
    // 「登录提示功能」v1.1.65 新增（需求：把「打开软件提示登录」加一个开关）。
    // 用通知类图标：这个开关管的就是那个引导弹窗，语义上比扳手 / 太阳都更贴。
    LabItem("登录提示功能", LabTarget.RealPage(LabRealPage.LOGIN_PROMPT), Icons.Filled.Notifications),
    // 「记事本」新增（需求：一条笔记，文字 + 图片，返回时自动保存）。
    //
    // 图标选 `Icons.Filled.Edit`（笔），理由是**依赖最小化**：
    // `Edit` 在本项目已解析的 `material-icons-core` 产物里确实存在；
    // 而语义上同样贴切的 `Book` / `MenuBook` / `NoteAdd` 只在
    // `material-icons-extended` 里才有（本项目虽依赖了它，但图标能用核心集就不用扩展集，
    // 少一个"以后移除扩展依赖就会编译失败"的隐性绑定）。
    // `Edit` 的「拿笔记录」语义对记事本也成立。
    LabItem("记事本", LabTarget.RealPage(LabRealPage.NOTEBOOK), Icons.Filled.Edit),
    // 「智能体」本轮由占位页升级为真实页面（OpenAI 兼容 AI 对话）。
    //
    // 图标 v1.1.69 需求改为**机器人脑袋** `Icons.Filled.SmartToy`：
    // 它是"AI 助手"最通用的视觉符号，比原先的 `Star`（星标）语义精确得多
    // （星标在"收藏 / 精选 / 评分"里到处都是，指代不了"对话机器人"）。
    //
    // ⚠️ `SmartToy` **只在 `material-icons-extended` 里**（`material-icons-core` 的
    // filled 包只有 49 个类，没有它）——本项目确实依赖了 extended，因此可用。
    // 这与本文件此前为「记事本」立过的"能用核心集就不用扩展集"的规矩**并不矛盾**：
    // 那条规矩的目的是避免"以后移除扩展依赖就编译失败"的隐性绑定，而不是禁用扩展图标；
    // 当核心集里没有语义合适的图标时（机器人脑袋就是这样），用扩展集是正确取舍。
    // 已在 Gradle 缓存的 `material-icons-extended-release.aar` 里核实
    // `androidx/compose/material/icons/filled/SmartToyKt.class` 确实存在。
    //
    // 放在最后一项：宫格 `chunked(2)` 逐行铺开，奇数行补空位的兜底本来就在，
    // 不需要动任何布局代码。
    LabItem("智能体", LabTarget.RealPage(LabRealPage.AGENT), Icons.Filled.SmartToy)
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
 * 请求把一个实验室条目**固定到桌面**（长按宫格 → 确认后调用）。
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
 * ## ⚠️ 关键限制：点击快捷方式**只会打开应用首页，不会跳到对应子页**
 *
 * 本应用是「单 Activity + Compose 导航」：真正的页面栈在 `MoreCheckRoot` 里的
 * `NavHost` 中，而**路由由 Compose 状态持有**，外部 Intent 无法直接驱动它。
 * 要想让快捷方式直达某个实验室子页，必须在 `MainActivity` 里读这个 Intent
 * （`onCreate` 取 `intent`、并覆写 `onNewIntent`），再把参数一路传到 `MoreCheckRoot`
 * 去 `navigate(...)`。
 *
 * 本轮**刻意不改 `MainActivity.kt`**（它不在本次允许改动的文件范围内），
 * 所以这里仍然把 action / extra **写好带上**，让快捷方式的 Intent 是自解释的、
 * 信息完整的：等 Lead 决定要直达子页时，只需在 `MainActivity` 读
 * [EXTRA_LAB_PAGE] 即可，不必回头重新设计快捷方式的创建逻辑。
 * 在补上那段读取代码之前，点击快捷方式等同于「打开本应用」，
 * 落在默认的一级入口首页（`AppTab.HOME`，即「主页」）。
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
 * 图标用应用自身的启动图标 `R.mipmap.ic_launcher`：
 * 快捷方式与本应用的关系一眼可见，且**不需要新增任何图片资源**。
 * （本项目 minSdk = 29 > 26，自适应图标在快捷方式里是被支持的。）
 */
private fun pinLabShortcut(context: Context, item: LabItem): Boolean {
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
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    val shortcut = ShortcutInfoCompat.Builder(context, LAB_SHORTCUT_ID_PREFIX + key)
        .setShortLabel(item.title)
        // 长标题与短标题取同一个值：本项目的宫格标题本来就很短（2~5 个字），
        // 强行再写一份「更长的描述」只会多一个要维护的文案来源。
        .setLongLabel(item.title)
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
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
 * - 「智能体」走 [onOpenAgent]（本轮由占位页升级为真实页面，OpenAI 兼容 AI 对话）。
 *
 * ## 长按宫格 → 添加到桌面
 *
 * 长按任一格会弹确认框，确认后把它固定到桌面（见 [pinLabShortcut] 与确认框处的注释）。
 * ⚠️ 点击生成的快捷方式**只会打开本应用首页，不会直达该格对应的子页**——
 * 原因与「要改 `MainActivity` 才能补上」的说明，见 [pinLabShortcut] 的 KDoc。
 *
 * 宫格是两列、逐行铺开（`chunked(2)`）：本轮加了第五项「智能体」，
 * 自然排到第三行第 1 格（右侧由既有的补空位兜底占住），
 * 因此**不需要动任何布局逻辑**。
 *
 * @param onOpenEntry 打开留空二级页，参数是 [PlaceholderEntry.value]。
 * @param onOpenWeather 打开「天气」真实页。
 * @param onOpenDeveloperOptions 打开「开发者选项」真实页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen(
    onBack: () -> Unit,
    /** 打开留空二级页，参数是 [PlaceholderEntry.value]。 */
    onOpenEntry: (String) -> Unit,
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
    onOpenAgent: () -> Unit = {}
) {
    // 渐变色只作用于本页：在 Scaffold **之外**铺一层 Box 画渐变，
    // 并把 Scaffold 的容器色设为透明、标题栏也设透明，让渐变从顶部一直透到底部。
    //
    // 反过来（给 Scaffold 传 containerColor = 渐变）做不到——`Scaffold` 的
    // `containerColor` 只接受单一颜色，画不出渐变。
    val backdrop = rememberLabBackdrop()

    // 「长按哪一格、正在等用户确认」的那一项；null = 不显示对话框。
    //
    // 用**单个可空状态**而不是 `Pair<Boolean, LabItem?>` 两个状态：
    // 两个状态会出现「显示对话框但没记住是哪一项」的非法组合，
    // 而对话框的确认按钮恰恰必须知道要 pin 哪一格。
    //
    // 不需要 `rememberSaveable`：它是一个**瞬时**的交互中间态，
    // 对话框弹出期间进程被杀属于极端情况，重建后不弹出来反而是更安全的行为
    // （不会在用户没预期时突然又弹一个确认框）。
    var pendingPin by remember { mutableStateOf<LabItem?>(null) }

    // ── 宫格顺序（v1.1.69 需求：长按排序 / 置顶）──────────────────────────────
    //
    // 用户可把常用功能**置顶**（长按 → 置顶），也可一键恢复默认排序。
    // 顺序存在 DataStore（`lab_grid_order`，逗号分隔的 key 串），理由见
    // `SettingsRepository.labGridOrder` 的 KDoc。
    //
    // ⚠️ 这里**只做「置顶」，不做任意拖拽排序**：宫格是两列紧凑布局，
    // 手上拖动一格会盖住邻格、且没有明确的落点提示（不像列表有明确的插入线），
    // 在手机上很容易"越拖越乱"。置顶是这套场景里**收益最高、误操作最低**的动作：
    // 一键把常用项提到最前，不需要精确拖放。同理提供「恢复默认排序」一步退回。
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
        (context.applicationContext as MoreCheckApp).container.settingsRepository
    }
    val savedOrder by settingsRepository.labGridOrder
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val orderedItems = remember(savedOrder) {
        if (savedOrder.isEmpty()) {
            LAB_ITEMS
        } else {
            val byKey = LAB_ITEMS.associateBy { labItemKey(it) }
            val pinned = savedOrder.mapNotNull { byKey[it] }
            val rest = LAB_ITEMS.filterNot { labItemKey(it) in savedOrder }
            pinned + rest
        }
    }

    // 长按菜单：置顶 / 添加到桌面（v1.1.69 需求）。
    //
    // 用 AlertDialog 而不是 DropdownMenu：`DropdownMenu` 挂在宫格卡片上时
    // 需要自己锚定位置，而卡片是网格里的一格、锚点会随行数变化；
    // AlertDialog 是独立窗口、无定位歧义，也与「添加到桌面」原有的确认框同一形态
    // （两者本来就是先后关系：先选动作，再确认动作的后果）。
    pendingLongPress?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingLongPress = null },
            title = { Text(item.title) },
            text = { Text("要做什么？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLongPress = null
                        // 置顶：把该项提到顺序最前，其余保持相对次序。
                        // 写回**完整**列表（含未置顶项），这样下次读盘就是这次的结果。
                        scope.launch {
                            val current = orderedItems.map { labItemKey(it) }
                            val moved = listOf(labItemKey(item)) + current.filter {
                                it != labItemKey(item)
                            }
                            settingsRepository.setLabGridOrder(moved)
                        }
                    }
                ) {
                    Text("置顶")
                }
            },
            dismissButton = {
                Row {
                    // 「恢复默认排序」只在用户真的改过顺序后才显示：
                    // 没有自定义顺序时它是个无意义的按钮，会让人以为"当前不是默认"。
                    if (savedOrder.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                pendingLongPress = null
                                scope.launch { settingsRepository.setLabGridOrder(emptyList()) }
                            }
                        ) {
                            Text("恢复默认排序")
                        }
                    }
                    TextButton(
                        onClick = {
                            pendingLongPress = null
                            // 走原有的「添加到桌面」确认框（它会讲清后果并调 pinLabShortcut）。
                            pendingPin = item
                        }
                    ) {
                        Text("添加到桌面")
                    }
                }
            }
        )
    }

    // 确认对话框：**只有点了「添加」才真的创建快捷方式**。
    //
    // ## 为什么长按不能直接加
    //
    // 桌面快捷方式会在用户桌面上**留下一个图标**，属于用户可感知且需要他手动删除的
    // 持久副作用。长按本身太容易误触（用户可能只想看看、或者在滚动时手抖），
    // 一旦直接创建，桌面上就会莫名多出图标而用户不知道哪来的。
    // 先弹确认框，把「要发生什么」讲清楚（含具体是哪一个功能），是这类
    // 「写用户桌面」操作应有的最低礼貌。系统自己在 `requestPinShortcut` 之后
    // 还会再弹一次系统级确认框——两层的分工是：我们这层确认「是不是这一格」，
    // 系统那层确认「要不要放到桌面」，因此不冲突、也不冗余。
    pendingPin?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingPin = null },
            title = { Text("添加到桌面") },
            text = { Text("把「${item.title}」添加到桌面？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinLabShortcut(context, item)
                        // 无论成功与否都关掉对话框：失败原因（桌面不支持）不是用户
                        // 在这个确认框里能解决的问题，留在页面上没有意义。
                        // 这里刻意**不弹 Snackbar**：本页没有 Snackbar 宿主，
                        // 为一句提示新增宿主属于超出需求范围的改动。
                        pendingPin = null
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPin = null }) {
                    Text("取消")
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(backdrop.brush)) {
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
                // ---- 两列宫格 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    orderedItems.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { item ->
                                LabCard(
                                    item = item,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        // 两类条目在这里分流：占位项给路由参数，
                                        // 真实页交给调用方的专用回调（本页不关心路由长什么样）。
                                        //
                                        // 真实页那层 `when` **不加 `else`**：以后在
                                        // [LabRealPage] 里加一项时，这里会因不完备而编译失败，
                                        // 从而强制补上接线（同上文的防漏机制）。
                                        when (val target = item.target) {
                                            is LabTarget.Placeholder ->
                                                onOpenEntry(target.entry.value)

                                            is LabTarget.RealPage -> when (target.page) {
                                                LabRealPage.WEATHER -> onOpenWeather()
                                                LabRealPage.DEVELOPER_OPTIONS ->
                                                    onOpenDeveloperOptions()

                                                LabRealPage.LOGIN_PROMPT ->
                                                    onOpenLoginPromptSetting()

                                                LabRealPage.NOTEBOOK -> onOpenNotebook()

                                                LabRealPage.AGENT -> onOpenAgent()
                                            }
                                        }
                                    },
                                    // 长按任一格：弹「置顶 / 添加到桌面」菜单（v1.1.69）。
                                    // 两种动作分开问，比"长按直接 pin"更清楚：
                                    // 用户长按的意图可能是排序，也可能是加桌面图标。
                                    onLongClick = { pendingLongPress = item }
                                )
                            }
                            // 条目数为奇数时补一个等宽空位，保证卡片宽度一致。
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ---- 底部「新增功能」 ----
                // 贴在页面最底部（即 Snackbar 出现的高度），带系统导航栏内边距。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Button(
                        onClick = { onOpenEntry(PlaceholderEntry.LAB_NEW.value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("新增功能", fontSize = 16.sp)
                    }
                }
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
    return LabBackdrop(
        brush = Brush.linearGradient(colors),
        onBackdrop = backdropForegroundOf(colors.first())
    )
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
 * 宫格里的一个功能卡片。
 *
 * ## 为什么用 `combinedClickable` 而不是再叠一层 `pointerInput`
 *
 * 单击保持原样（同样的 [HapticFeedbackType.LongPress] 触感反馈、同样调用 [onClick]），
 * 长按则交给 [onLongClick]。`combinedClickable` 是官方为此场景提供的**单一**手势修饰符：
 * 若改为在原 `.clickable{}` 之上另加一个 `detectTapGestures(onLongPress = ...)`，
 * 两个手势检测器会**竞争**同一串指针事件，谁先消费掉取决于修饰符顺序，
 * 表现为「有时能长按、有时只触发单击」，且难以稳定复现。
 *
 * 触感反馈仍在两个分支里**各自显式**调用，没有改为依赖框架默认行为：
 * 一是保持单击的手感与改动前**逐字节一致**（原先就在 `clickable` 里手动触发）；
 * 二是已核对本项目解析到的 `foundation` 产物——`combinedClickable` 内部
 * **不会**自己触发长按触感，不写就完全没有反馈。
 * 单击与长按的反馈刻意用同一个类型：这里表达的都是「格子被按住了」，
 * 手机的单种线性马达也分不出更细的差别，引入第二种类型只会显得不一致。
 *
 * @param onLongClick 长按回调（用于「添加到桌面」，见 [LabScreen] 里的确认对话框）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LabCard(
    item: LabItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = modifier.combinedClickable(
            onClick = {
                // 与本应用其它可点行一致的手势反馈。
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            onLongClick = {
                // 长按同样给一次触感：否则用户按住不放时界面毫无反应，
                // 会以为这一格不支持长按而提前松手。
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp),
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
                text = item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
