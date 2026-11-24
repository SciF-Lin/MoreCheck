package com.morecheck.ui.lab

import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.nav.EntryPlacement
import com.morecheck.ui.nav.LAB_SPLASH_PAGES
import com.morecheck.ui.nav.MOVABLE_ENTRIES
import com.morecheck.ui.nav.decodeEntryPlacement
import com.morecheck.ui.nav.decodeSplashDisabled
import com.morecheck.ui.nav.effectivePlacement
import com.morecheck.ui.nav.encodeEntryPlacement
import com.morecheck.ui.common.AppDropdownItem
import com.morecheck.ui.common.AppDropdownRow
import com.morecheck.ui.common.SplashTextLabel
import com.morecheck.ui.common.displayLabel
import com.morecheck.ui.common.rememberSplashTextStyle
import com.morecheck.di.appContainer
import com.morecheck.data.SettingsRepository
import com.morecheck.data.ChangelogEntryPlacement
import com.morecheck.data.CheckUpdateTarget
import com.morecheck.data.SplashTextFont
import com.morecheck.data.SplashTextStyle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.DEFAULT_SUPPORT_AUTHOR_URL
import com.morecheck.util.LinkOpenGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「实验室 → 开发者选项」（原「登录测试选项」）。
 *
 * 版式仿手机系统里的「开发者选项」：最上面一张总开关卡片（本页的视觉重心），
 * 下面按板块分组，每个板块一张卡片、卡片里若干开关行。
 * 颜色一律取自 [MaterialTheme]，因此**跟随主题色与深色模式设置**，不新造任何颜色值。
 *
 * ## 为什么直接拿 `AppContainer` 里的仓库，而不是写一个 ViewModel
 *
 * 本页各子项的**生效条件**都由 [com.morecheck.data.DeveloperOptionsRepository]
 * 里的 `combine(总开关, 子开关)` 统一算好，页面不需要再做一次运算，也不持有任何
 * 自己的状态；为一个纯"读写设置"的页面新建 ViewModel + Factory 只会多两个文件、
 * 多一层转发。这与本项目的 `WeatherScreen` / `PersonalInfoScreen` 拿
 * `settingsRepository` 的做法一致（见 `PersonalInfoScreen` 的 KDoc）。
 *
 * ⚠️ 必须走 `applicationContext` 再转 [MoreCheckApp]：`LocalContext.current`
 * 在 Compose 里通常是 Activity，直接 `as MoreCheckApp` 会 `ClassCastException`；
 * 而且 `applicationContext as` 拿到的是**进程内唯一**的那份容器——验证码测试生成的
 * 六位码就存在那个单例的字段里，换成别的实例会出现"刚发的码立刻失效"。
 * 用 `remember(context)` 缓存，避免每次重组都做一次类型转换与容器查找。
 *
 * ## 为什么「朋友圈红点提示」这一项**不走**这个仓库，而走 `LocalSettingsViewModel`
 *
 * 上面那条「不新建 ViewModel」的理由只适用于**本页独占**的设置：子开关的生效条件
 * 要在仓库层与总开关「与」一次，界面只管回显。
 * 但「朋友圈红点提示」不是这样——它的读取方在 `MoreCheckApp`（底部导航栏画角标那里），
 * 那份状态必须与这里**是同一个实例**，否则会出现"在开发者选项里拨了开关、底栏却不理会"。
 * 项目里 [com.morecheck.ui.LocalSettingsViewModel] 就是为此存在的（`MainActivity` 建好、
 * 全应用共享一份），「登录提示功能」页用的也是它，这里沿用同一条路，**不是**在自创第二种写法。
 *
 * ## 子项的「原始值」与「是否生效」是两份状态，本页刻意让它们各自显示（本项目踩过的坑）
 *
 * 仓库把子项拆成了**原始值**（`codeTestSwitch` / `perfTestSwitch` / `splashHoldSwitch`）
 * 与**是否生效**（`codeTestEnabled` / `splashHoldDurationMs` 等）两条流。页面上的控件**必须**读原始值：
 * - 若拿 `codeTestEnabled` 当 `checked`，一旦关掉总开关，子开关会**立刻全部跳到"关"**，
 *   用户会以为自己的设置被清空了（其实仓库里还留着），再打开总开关时又"自己弹回来"，
 *   这是最让人困惑的一种表现；
 * - 正确做法是 `checked = 原始值` + `enabled = 总开关`：**位置照旧显示、只是点不动**，
 *   "值还在、只是暂不生效"这件事在视觉上就自解释了。
 *
 * 因此本页不使用 `codeTestEnabled` / `perfTestEnabled` 这几条已经与过总开关的流——
 * 它们更适合"业务代码判断某项功能要不要真的跑"，而不是回显开关位置。
 *
 * ## 需求边界（用户明确要求「不要额外增加功能」）
 *
 * - 「性能测试」有真实作用：它门控板块里的**开屏停留时长滑块**（0~10 秒）；
 * - 「图标测试」整体改版为**真实的图标更改**（内置图标切桌面图标 + 自定义图上传裁切），
 *   原来那个只演示动画的占位开关已按需求删除；
 * - 「图标测试」有一个「朋友圈红点提示」开关：门控底部导航栏「发现」入口的未读角标
 *   **要不要画**（默认开 = 与改造前观感一致）；它**不**改变未读状态本身，
 *   见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc；
 * - 「图标测试」还有一行「设备信息查询」入口（进 [UiInspectorScreen]）：
 *   只读地列出按钮 / 颜色 / 动画的真实规格，供后续沟通 UI 用，**不改任何设置**；
 * - **本轮需求 2**：原先混在「图标测试」里的实验室 / 游戏相关四行
 *   （实验室背景设置、游戏标题栏显示、游戏开屏显示、游戏颜色模式）
 *   整体迁到新增的「实验室测试」小标题下，本需求只改归类、不改任何行为；
 * - 没有重置按钮（滑块自带「恢复默认」）、没有导入导出、没有日志查看、没有"关于开发者选项"。
 *
 * ## 三个入口行的右侧为什么是「右箭头」而不是「进入」按钮
 *
 * 用户需求原文：「开发者选项里的"进入"改为向右的箭头，像"我的"页那种样式」。
 * 三个入口行（登录测试 / 图标更改 / 设备信息查询）的右侧因此从 `OutlinedButton("进入")`
 * 改为 [Icons.AutoMirrored.Filled.KeyboardArrowRight]，规格逐项照「我的」页
 * （默认 24dp 尺寸 + `onSurfaceVariant` + `contentDescription = null`）。
 *
 * 随之而来的两处行为变化，都是刻意为之：
 * 1. **可点区域从按钮扩大到整行**——箭头不是控件，只是"这行可以点"的视觉指示，
 *    只让箭头本身响应点击与用户预期相反（详见 [DeveloperActionRow] 的 KDoc）；
 * 2. **「通知测试」那行保持是按钮**——它没有下一级页面，按一下当场发通知，
 *    画成箭头会给出错误的导航暗示（见 [LoginTestScreen] 的 KDoc）。
 *
 * 因此 [DeveloperActionRow] 加了一个 [TrailingStyle] 形态参数而不是复制一个组件：
 * 三种入口行的标题 / 说明 / 内边距 / 置灰系数完全同构，复制一份迟早漂移。
 *
 * ## 本轮新增的两级入口为什么都是「就地覆盖」而不是导航路由
 *
 * 「登录测试」与「设备信息查询」都沿用 `IconPickerScreen` 那套：本页用一个布尔状态
 * 决定画哪一页，二级页整页覆盖本页、系统返回键由二级页自己的 `BackHandler` 接住。
 * 走路由要在 `ui/nav/` 下加 `TabDestination` 与 `composable`，把一个纯 UI 的子流程
 * 提升成全局导航状态（还要处理"返回时回本页还是回上一页"），而本页的各个二级页
 * 都只从本页进入、也只回本页，不值得动导航层（本项目的
 * `PersonalInfoScreen → AvatarCropScreen` 已确立这个做法）。
 *
 * ⚠️ 每个二级页各自**独立**一个布尔状态，不共用一个"当前子页"枚举：
 * 共享枚举会引入"上一页是谁"这种本不存在的状态，而它们互不可达（谁也不能从
 * 「图标更改」直接跳进「登录测试」）。
 *
 * ## 二级页的**进出场动画**（需求：「开发者选项进二级界面增加动画」+ 本轮「进出连贯、不白屏」）
 *
 * 就地覆盖不经过导航层，因此拿不到 `NavHost` 那套转场动画。补法是
 * [SecondaryPageEnterHost]：从右侧整屏滑入（无淡入）、返回时向右滑出，
 * 三处共用同一个组件以保证口径一致，完整取舍（含"为什么要垫一层不透明底色"、
 * "为什么不能用提前 return 整页换血"）见该组件的 KDoc。
 *
 * ## ⚠️ 本页各分区的「门控」口径**故意不同**，别当成不一致
 *
 * | 分区 | 子项 | 生效条件 |
 * |---|---|---|
 * | 性能测试 | 开屏时长滑块 | `DEV_MODE_ENABLED` **且** `DEV_PERF_TEST_ENABLED` |
 * | 图标测试 | 图标更改 | 只有 `DEV_MODE_ENABLED` |
 * | 图标测试 | 设备信息查询 | 只有 `DEV_MODE_ENABLED` |
 * | 图标测试 | 朋友圈红点提示 | 只有 `DEV_MODE_ENABLED` |
 * | 实验室测试 | 实验室背景设置 | 只有 `DEV_MODE_ENABLED` |
 * | 实验室测试 | 游戏标题栏显示 | 只有 `DEV_MODE_ENABLED` |
 * | 实验室测试 | 游戏开屏显示 | 只有 `DEV_MODE_ENABLED` |
 * | 实验室测试 | 游戏颜色模式 | 只有 `DEV_MODE_ENABLED` |
 * | 界面测试 | 打卡完成动画 / 页面转场动画 / 按压反馈 | 只有 `DEV_MODE_ENABLED` |
 * | 界面测试 | 日期显示格式 | 只有 `DEV_MODE_ENABLED`（三选一，非布尔） |
 * | 工具与测试 | 界面尺寸尺（2026-11-03 新增） | 只有 `DEV_MODE_ENABLED` |
 * | 登录测试 | 验证码测试 / 通知测试 | 只有 `DEV_MODE_ENABLED` |
 *
 * 差异的来源就是需求本身：
 * - 需求原文说滑块「受性能测试总开关控制」，所以它有两个约束；
 * - 其余分区**都没有**独立子开关（「图标测试」原来那个占位开关已删除，
 *   「实验室测试」是从「图标测试」拆出来的，四个子项本来就不带子开关），
 *   因此按「总开关关掉后子项不生效」这条本页规矩，它们只需总开关；
 * - 「登录测试」板块**本来就没有**独立子开关（改造前后一致），因此搬进二级页后
 *   两个子项的门控口径仍是"只有总开关"，一个字都没变。
 *
 * ## 分区与行序（2026-11-03 核对，以代码中的调用顺序为准）
 *
 * 从上到下五张卡片，顺序与 `DeveloperSectionTitle` 的调用顺序一致：
 *
 * 1. **开屏** —— App 启动开屏时长 / 实验室功能开屏 / 开屏显示文字
 * 2. **动画与反馈** —— 打卡完成动画、页面转场动画、底部入口切换动画、
 *    两级动画时长滑块、按压反馈
 * 3. **实验室与游戏** —— 实验室背景设置、游戏标题栏显示、实验室功能开屏、游戏颜色模式
 * 4. **图标** —— 图标更改(实验性)、朋友圈红点提示
 * 5. **工具与测试** —— 登录测试、检查更新按钮功能、更新日志入口、支持作者链接，
 *    以及 2026-11-03 新增的 **界面尺寸尺**（全屏标尺工具）
 *
 * ⚠️ 2026-11-11：原「外观与内容」分区（样式设置 / 页面自定义两个二级页）已按要求
 * **整体删除**，观感固定为各项默认值（生效流见 `DeveloperOptionsRepository`）。
 *
 * > 本清单只求"新接手的人知道页面长什么样"，各行的**生效条件**以上面的门控表为准；
 * > 具体行序改页面时请顺手更新这里（它是唯一一处文字版目录）。
 *
 * ### 需求 8 为什么单独一个「界面测试」分区
 *
 * 这四项改的都是**全局界面行为**：既不属于实验室（它们不只作用于实验室页），
 * 也不属于图标（它们不改任何图标）。三个开关的默认一律 true（= 改造前既有行为），
 * 理由与本文件其它新增开关同源：新增设置项的默认值必须等于用户没调过时
 * 看到的样子，否则等于悄悄改掉了所有人熟悉的观感。
 *
 * ### 「日期显示格式」为什么不做成 `...Enabled`
 *
 * 它是三选一而非布尔开关，"关掉总开关时它该显示哪一档"没有唯一答案；
 * 若回落默认档，用户拨好的选择会在关总开关的瞬间弹回——那正是本文件反复要消除的
 * 那种"看起来设置被清空"的困惑。因此与 `gameChromeModeSwitch` 同口径：
 * **原始值永远可读**，二级页读它做回显。
 *
 * ### 需求 2 的归类判据
 *
 * 原文：「开发者选项 归类图标测试中与实验室和游戏有关的功能，
 * 小标题为『实验室测试』」。落地的判据是**这一行实际改变的对象**：
 *
 * - 改的是**实验室页或游戏页** → 归「实验室测试」（4 行：实验室背景设置、
 *   游戏标题栏显示、游戏开屏显示、游戏颜色模式）；
 * - 改的是**应用图标本身或图标上的红点** → 留在「图标测试」（3 行：图标更改、
 *   朋友圈红点提示、设备信息查询）。
 *
 * ⚠️ 「实验室背景设置」上一轮曾被移到「图标测试」卡片顶部，但那一次只是行序微调，
 * 没有语义依据（它的 KDoc 明说作用于实验室页背景，标题里也写着「实验室」），
 * 因此本轮按语义归位。详见该分区小标题上方的注释。
 *
 * ## 「图标测试」那三行为什么在同一个卡片里
 *
 * 需求把「朋友圈红点提示」归在「图标测试」分组（它是底部导航栏**图标上的红点**，
 * 确实属于"图标测试"的范畴），用户又明确要求「设备信息查询」也加在这一板块。
 * 因此图标测试卡片里现在是「两行入口 + 一行开关」，行之间都用分隔线断开。
 *
 * ⚠️ 本需求 2 之后本卡片**不再有**实验室 / 游戏相关行（那四行已迁到「实验室测试」），
 * 「两行入口 + 一行开关」这句描述因此从"曾经混在一张卡里很多行"变成了字面意义上的
 * 当前形态——需求 2 之前它是"图标测试里混着实验室与游戏的行"。
 *
 * ⚠️ 「朋友圈红点提示」与 [SplashHoldRow] 那种"值 + 生效条件"的双状态不同：
 * 它只有**一个**布尔值，门控也只是总开关，因此直接复用 [DeveloperSwitchRow] 即可，
 * 不要为它再造一个组件（本页已经出现过"三份几乎一样的 Row 各自漂移"的问题）。
 *
 * 页脚那句说明会把这个差异讲给用户听，否则「为什么这个板块没有开关」会被当成 bug。
 *
 * @param onBack 点左上角返回箭头时回调（由导航图决定回哪一页）。
 * @param onMessage 页面级一次性提示。本页只在**关闭总开关且确实保留了子项设置**时报一句，
 *   用来消除"关掉总开关是不是把子设置也重置了"的误解；其余情况不打扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 见上方 KDoc：必须 applicationContext + 进程内单例容器。
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    // 总开关：同时决定本页各子项是否可交互。
    val masterEnabled by repository.masterEnabled.collectAsStateWithLifecycle(initialValue = false)

    // 「链接打开方式」全局开关（2026-11-05）：一键切换所有走 openInBrowser 的链接。
    // 初值 false 与 util.LinkOpenGate 的默认值一致，读盘期间不会先按"应用内"渲染。
    val linkOpenInApp by repository.linkOpenInApp.collectAsStateWithLifecycle(initialValue = false)

    // 2026-11-14：发现页官网位（官网 / 搜一搜）。
    val discoverSearchMode by repository.discoverSearchMode
        .collectAsStateWithLifecycle(initialValue = false)

    // 子项的**原始值**（仅用于回显，不代表是否生效）。
    // initialValue 给 false：DataStore 首次读取要走磁盘，先渲染成"关"再被真实值覆盖，
    // 比给 true 安全——真值是"开"时顶多闪一下，而给 true 会在真值是"关"时出现
    // "开关自己弹回去"的假象。
    val codeTestSwitch by repository.codeTestSwitch.collectAsStateWithLifecycle(initialValue = false)
    // ⚠️ 2026-10-27：`perfTestSwitch` 的订阅已删除——用户要求移除「启用性能测试」子开关，
    // 而它门控的开屏时长上一轮已放宽为只受总开关，这个订阅已无用武之地。

    // 「朋友圈红点提示」的**原始值**（默认 true，持久化在 DataStore 的 `moments_badge_enabled`）。
    //
    // ## 为什么这里可以不传 initialValue
    //
    // 它来自 `MainActivity` 建好的那份 [com.morecheck.ui.settings.SettingsViewModel]，
    // 是一条 `StateFlow<Boolean>`（初值就是 `true`，见 `SettingsViewModel` 的字段声明），
    // 因此 `collectAsStateWithLifecycle()` 直接拿它的当前值当初始值即可——不会像上面那些
    // DataStore 流一样先渲染成 `false` 再被真值覆盖（那正是上面几行需要显式给 initialValue 的原因）。
    //
    // ## 为什么走 `LocalSettingsViewModel` 而不是仓库
    //
    // 与「登录提示功能」页同一选择：复用同一份实例，用户在这里拨动开关，
    // `MoreCheckApp` 里读的也是同一份状态，回主界面立刻生效、不用重启。
    // 自己 `viewModel(...)` 新建一份会多出一份状态，正是本项目历史上「开关关不掉」的成因。
    val settingsViewModel = LocalSettingsViewModel.current
    val momentsBadgeEnabled by settingsViewModel.momentsBadgeEnabled.collectAsStateWithLifecycle()

    // 开屏停留时长的**原始值**（用户设的那个数）。
    // initialValue 用仓库里的默认值而不是 0：给 0 会让滑块先跳到最左再弹回真实位置，
    // 而默认值就是"没调过时应该是多少"，先渲染成它最接近真实、视觉上最不突兀。
    val splashHoldMs by repository.splashHoldSwitch.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_SPLASH_HOLD_MS
    )

    // 「随机浮动」勾选状态（2026-10-23 需求 8）。默认 false（= 不浮动，与改造前一致）。
    val splashJitterEnabled by repository.splashJitterEnabledSwitch
        .collectAsStateWithLifecycle(initialValue = false)

    // 「随机浮动」介绍弹窗是否已看过（2026-10-25：首次勾选弹窗、之后改工具提示）。
    val splashJitterIntroShown by repository.splashJitterIntroShown
        .collectAsStateWithLifecycle(initialValue = true)

    // 「游戏标题栏 / 开屏显示」入口行开关的**生效值**（2026-10-25 需求 9）。
    //
    // 原先这两行读原始值，导致「上一级总门已关（= 实际恒显示）而行开关仍显示关」
    // 的自相矛盾；改为读生效值后，行开关永远反映"现在实际是什么"。
    // 二级页里的子开关**仍读原始值**（"值还在、只是暂不生效"的口径不变）。
    val gameTitleBarEffective by repository.gameTitleBarEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    // 「游戏开屏显示」那一行 2026-10-27 已并入「实验室功能开屏」二级页，
    // 一级页不再有它的入口行，`gameSplashEffective` 订阅随之删除。

    // ── 2026-10-25 本轮新增订阅 ──────────────────────────────────────────
    val navAnimMs by repository.navAnimDurationSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_NAV_ANIM_MS)
    val tabSwitchMs by repository.tabSwitchDurationSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_TAB_SWITCH_MS)
    val labFeatureSplashSwitch by repository.labFeatureSplashSwitch
        .collectAsStateWithLifecycle(initialValue = false)
    // 实验室功能开屏（2026-10-27）：总开关的原始值（二级页回显）与生效值（一级页行开关）。
    val labSplashAllSwitch by repository.labSplashAllSwitch
        .collectAsStateWithLifecycle(initialValue = true)
    val labSplashAllEffective by repository.labSplashAllEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    val revokeExitSpinnerSwitch by repository.revokeExitSpinnerSwitch
        .collectAsStateWithLifecycle(initialValue = true)
    // ⚠️ 旧开关 `checkUpdateInAppSwitch` 本轮不再在本页读取（那一行已换成三选一入口，
    // 见「检查更新按钮功能」）。仓库里的读写方法保留，见下方常量处的说明。
    // 「检查更新」按钮的行为（本轮需求 2）：三值枚举，默认云端安装包页。
    val checkUpdateTarget by repository.checkUpdateTarget
        .collectAsStateWithLifecycle(initialValue = CheckUpdateTarget.APK_DOWNLOAD)

    // 「更新日志」入口位置（2026-11-02 需求）：下载页底部 / 关于页，二选一。
    // 与上面的去处**解耦**：一个决定"点按钮去哪"，一个决定"日志门口开在哪"。
    val changelogPlacement by repository.changelogEntryPlacement
        .collectAsStateWithLifecycle(initialValue = ChangelogEntryPlacement.INSIDE)

    // 「支持作者」链接（2026-10-29 需求）：自定义值（null = 用内置默认地址）。
    // 它是一段**文本**而不是开关，所以下面单独开一个编辑弹窗，而不是开关行。
    val supportAuthorUrl by repository.supportAuthorUrl
        .collectAsStateWithLifecycle(initialValue = null)

    // 「左右滑动动画」开关（需求 11a）的原始值。
    // 注意默认是 true：这个开关关掉的是"本来就有的动画"，默认开 = 与改造前一致。
    val tabSlideAnimSwitch by repository.tabSlideAnimSwitch.collectAsStateWithLifecycle(
        initialValue = true
    )

    // 「游戏标题栏显示」/「游戏开屏显示」的入口行开关（2026-10-25 需求 9 改版）：
    // 一律读**生效值**（见下方 gameTitleBarEffective / gameSplashEffective），
    // 原先那两个原始值订阅（gameTitleBarTotal / gameSplashTotal）随之删除；
    // 二级页里的子开关仍读各自原始值（"值还在、只是暂不生效"的口径不变）。

    // 「游戏标题栏显示」一级页入口（本轮需求 5 起是入口行，开关本体在二级页）。
    // 一级页不再读它的原始值——总开关的原始值已由 `masterEnabled` 表达，
    // 二级页自己订阅两个子开关。

    // ---- 「界面测试」分区（v1.1.74 需求 8）四项的**原始值** ---------------------
    //
    // initialValue 一律给**各自的默认值**而不是 false：
    // 三个开关的默认都是 true（= 改造前行为，理由见 SettingsRepository 的 Keys），
    // 给 false 会让它们先渲染成"关"再被真实值弹回——那正是本文件 KDoc
    // 「原始值 vs 是否生效」一节要消除的"开关自己弹回去"假象。
    // 日期格式的 default 用仓库常量，与"没调过滑块时应该是多少"同一口径。
    val checkinCelebrationSwitch by repository.checkinCelebrationSwitch
        .collectAsStateWithLifecycle(initialValue = true)

    val pageTransitionAnimSwitch by repository.pageTransitionAnimSwitch
        .collectAsStateWithLifecycle(initialValue = true)

    val pressFeedbackSwitch by repository.pressFeedbackSwitch
        .collectAsStateWithLifecycle(initialValue = true)

    val dateFormatSwitch by repository.dateFormatSwitch.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DateDisplayFormat.CN_FULL
    )

    // ---- 「音乐」分区（2026-11-09）两项的**原始值** ---------------------------
    //
    // initialValue 都给各自的默认值（退出确认 false / 入口歌词 true），
    // 理由与上面那批一致：给反值会让开关先渲染成另一态再弹回去。
    val musicEntryLyricsSwitch by repository.musicEntryLyricsSwitch
        .collectAsStateWithLifecycle(initialValue = true)

    // 🔄 2026-10-27：「日期显示格式」从**二级页**改为一级页的**下拉列表**
    //（用户要求），`showDateFormat` 状态、`SecondaryPage.DATE_FORMAT` 与整个
    // `DateFormatScreen` 一并删除——留着只会在下次改这一项时多一条死路径。

    // 「通知测试」要真的发通知，所以需要通知工具——**它现在由二级页自己取**（见
    // [LoginTestScreen]）：一级页不再有发射通知的入口，把 helper 提前到这里只是一份
    // 用不上的字段。同理，「通知测试」的 10 秒冷却状态与它的 `LaunchedEffect`
    // 也一并搬进二级页，行为（按一下 → 置灰并显示剩余秒数 → 归零后恢复）完全不变。

    // 「登录测试」是否正显示着（二级页，含「验证码测试」与「通知测试」两项）。
    //
    // ⚠️ 本轮起各二级页**不再用「提前 return + 整页换血」**，而是常驻组合：
    // 宿主 Scaffold 照常画着，二级页用 [SecondaryPageEnterHost]（AnimatedVisibility）
    // 盖在上面——这是修「进二级界面白屏 / 动画不连贯」的结构前提：
    // 提前 return 会让主页面与二级页**同帧换血**，动画层无论如何都会有一帧空档；
    // 常驻 + 动画容器后，进出都有完整动画帧（取舍详见该组件的 KDoc）。
    var showLoginTest by remember { mutableStateOf(false) }

    // 「图标更改」是否正显示着。
    //
    // ## 为什么是**就地覆盖**，而不是加一条导航路由
    //
    // 走路由要在 `ui/nav/TabDef.kt` 里加一个 `TabDestination`、再到 `ui/nav/TabNavHost.kt`
    // 注册 `composable`，把一个纯 UI 的子流程提升成全局导航状态（还要处理
    // 「返回时回本页还是回上一页」）。本项目的 `PersonalInfoScreen → AvatarCropScreen`
    // 已经确立了**就地覆盖**这一做法（见那个文件的注释），这里沿用同一套，
    // 好处是「图标更改」完全不依赖导航层，也不需要改任何导航文件。
    // 系统返回键由 `IconPickerScreen` 内部的 `BackHandler` 接住。
    // 「图标更改(实验性)」的风险提醒弹窗（需求：强制弹窗 + 5 秒倒计时确认）。
    var showIconWarn by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }

    // 🔄 2026-10-25：「设备信息查询」二级页已整体迁出（设置 → 通用，走真实路由
    // `TabDestination.UiInspector`），原先在这里的 showUiInspector 就地覆盖状态随之删除。

    // 「开发者选项背景设置」二级页（需求 11b）是否正显示着。
    // 与其它二级页同一套就地覆盖：不加导航路由，返回键由二级页自己的 BackHandler 接住。
    var showLabBackground by remember { mutableStateOf(false) }

    // 「实验室功能开屏」二级页（2026-10-27 新增）是否正显示着。
    //
    // 需求原文：「实验室功能开屏可单独设置包括热搜和三个游戏，有一个总开关，
    // 总开关关闭时都不显示开屏。实验室开屏功能与 app 开屏时长分开控制」。
    // 因此它从"一行开关"变成"入口 + 二级页"，原来那张卡里的「游戏开屏显示」
    // 入口也随之并进这一页（游戏也是实验室功能）。
    var showLabSplash by remember { mutableStateOf(false) }

    // 2026-11-14：发现页 ↔ 实验室「入口管理」二级页。
    var showEntryPlacement by remember { mutableStateOf(false) }

    // 「开屏显示文字」二级页（2026-10-27 新增）。入口在一级页「开屏」分区的第三行。

    // 「支持作者链接」编辑弹窗（2026-10-29 需求）。

    // 「检查更新按钮功能」选择弹窗（本轮需求 2：三选一，见 CheckUpdateTarget）。

    // 「更新日志入口位置」选择弹窗（2026-11-02 需求：里面 / 外面）。

    // 「游戏颜色模式」二级页（本轮需求 3）是否正显示着。
    //
    // 需求 3：「游戏标题栏 / 状态栏颜色」三档可选。三档是**互斥单选**且有说明文字，
    // 一级页那一行只放不下，因此做成与「游戏开屏显示」同构的入口 + 二级页。
    // 同样走就地覆盖，返回键由二级页自己的 `BackHandler` 接住。
    var showGameChrome by remember { mutableStateOf(false) }

    // 「游戏标题栏显示」二级页（本轮需求 5）是否正显示着。
    //
    // 需求 5 原文：「游戏标题栏显示增加二级界面，可单独设置标题栏显隐，原总开关保留，
    // 右侧增加向右箭头」。与「游戏开屏显示」完全同构：一级页开关变入口，
    // 贪吃蛇 / 数独各一个子开关进 [GameTitleBarScreen] 二级页，原总开关保留。
    var showGameTitleBar by remember { mutableStateOf(false) }

    // 「样式设置」「页面自定义」两个二级页已于 2026-11-11 按用户要求**整体删除**
    // （入口、页面、生效开关一并下线，观感固定为各项默认值）。
    // 这里不再保留对应布尔量，避免"入口没了但状态还在"的半截实现。

    // 「界面尺寸尺」全屏调试工具（2026-11-03 需求 6）是否正显示着。
    //
    // 它**不进**下面的 `secondaryVisible` 汇总：那套动画容器是"带位移的二级页"，
    // 而尺寸尺必须让屏幕边缘与量尺基准重合，动画期间会量偏；因此它单独整屏覆盖。
    // 用 rememberSaveable：本 Activity 没有声明 configChanges，旋转会重建，
    // 需求要求"横竖屏都能用"——重建后尺子要还在，insets/尺寸会按新方向重算。
    var showHeightRuler by rememberSaveable { mutableStateOf(false) }

    // ── 「游戏颜色模式」/「实验室背景」的**当前档位**（2026-10-23 需求 5）──────
    //
    // 这两行是入口行，右侧除箭头外还要外显"现在选的是哪一档"（截图样式）。
    // 读的是**原始值**（不是与过总开关的生效值）：总开关关着时用户仍能看到
    // 自己上次选的是哪一档，与其它子项"值还在、只是暂不生效"的口径一致。
    val gameChromeModeValue by repository.gameChromeModeSwitch
        .collectAsStateWithLifecycle(initialValue = GameChromeMode.DARK_CLASSIC)

    // 实验室背景的模式存在 SettingsRepository（不归开发者选项仓库），因此单独取一份。
    val settingsRepo = remember(context) { context.appContainer.settingsRepository }
    val labBgMode by settingsRepo.devLabBackgroundMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.LAB_BG_MODE_RANDOM)

    // 「开屏显示文字」的当前设置（2026-10-27）：一级页入口行右侧外显当前文案，
    // 二级页自己再订阅一次（与其它二级页同一口径：数据放 DataStore，各页自取）。
    val splashTextStyle by settingsRepo.splashTextStyle
        .collectAsStateWithLifecycle(initialValue = SplashTextStyle.DEFAULT)

    // ── 是否有二级页正显示着 ────────────────────────────────────────────────
    //
    // 只用来给 [SecondaryPageEnterHost] 的 `visible` 传参；**不再**用来隐藏一级页标题栏
    // （见下方「整个页面包在 Box 里」那段注释：那一版会让标题栏先消失 220ms 再出现）。
    val secondaryVisible = showLoginTest || showIconPicker ||
        showLabBackground || showLabSplash || showGameChrome ||
        showGameTitleBar || showEntryPlacement

    // ⚠️ 整个页面（含一级页 Scaffold）包在 Box 里，二级页作为 Box 的**第二个子项**。
    //
    // 二级页必须画在 `Scaffold` **外面**才盖得住一级页的 `TopAppBar`：Material3 的
    // `Scaffold` 是「先画内容、后画 topBar」，把二级页放进 content lambda 里，
    // 它永远在一级页标题栏**下面**。之前那版为了避开压层，在有二级页时
    // 干脆不画一级页标题栏——于是进入瞬间标题栏先消失 220ms 再出现
    // （用户反馈的「进二级界面标题栏突然消失」）。搬到 Scaffold 外面后，
    // 一级页标题栏**始终在**，二级页自己滑上来盖住它，与写微信式推入一致。
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("开发者选项") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { innerPadding ->
            // ---- 一级页本体 --------------------------------------------------
            //
            // 整页滚动（与 PermissionsScreen 同一写法）：四张分区卡片在矮屏 / 大字体下会超出一屏。
            // 卡片数量固定且很少，不值得上 LazyColumn——那反而要处理滚动位置恢复。
            // 2026-11-14 需求 5：整页滚动状态提出来，供顶部「分区速览」跳转使用。
            val pageScroll = rememberScrollState()
            // 各分区的纵向位置（px，相对内容顶）。由分区标题的 onGloballyPositioned 填。
            val sectionTops = remember { mutableStateMapOf<String, Int>() }
            val jumpScope = rememberCoroutineScope()
            // 速览跳末段顶对齐用：视口高与末段（标题顶→卡片底）高，差值做尾部留白。
            var viewportPx by remember { mutableStateOf(0) }
            var lastSectionPx by remember { mutableStateOf(0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    // 视口高必须在 verticalScroll **之外**量：链上在它下面量到的是
                    // 内容高（无限约束），尾部留白会把它当视口用，逐帧自增直到
                    // Constraints 溢出崩溃（2026-11-15 回归实测）。
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(pageScroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
            // ---- 总开关（本页视觉重心）--------------------------------------
            MasterSwitchCard(
                checked = masterEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        // 写仓库是 suspend（DataStore 落盘）。放进协程而不是
                        // runBlocking：后者会在主线程等磁盘 IO，点一下就能感觉出卡顿。
                        repository.setMasterEnabled(enabled)
                        // 2026-10-25 需求：「随机时长」随总开关关闭而**取消勾选**——
                        // 用户口径是"当关闭开发者选项时不勾选"，这里在关总开关时
                        // 把持久值一并写回 false，重新打开后不会残留上次的勾选。
                        if (!enabled) {
                            repository.setSplashJitterEnabled(false)
                        }
                        // 只在"关闭总开关 + 确实有子项处于开的位置"时提示：
                        // 全关状态下说这句就是噪音。这里读的是上面那几个原始值流，
                        // 回调执行时读到的就是此刻的最新值。
                        //
                        // ⚠️ 判断条件里刻意**不含**「朋友圈红点提示」：它的默认值是 `true`，
                        // 把它算进来就等于"每次关总开关都弹这句提示"，反而成了噪音。
                        // 那句提示只在"用户自己开过某项、现在被暂时压住"时才有信息量；
                        // 而这里只需保证条件里不出现已删除的 `iconTestSwitch`（那会编译不过）。
                        if (!enabled && codeTestSwitch) {
                            onMessage("开发者选项已关闭，子开关的位置已保留")
                        }
                    }
                }
            )

            // ⚠️ 2026-10-23 需求 7：**总开关关闭时隐藏界面内所有功能项**。
            //
            // 之前是"全部置灰但都显示"（`enabled = masterEnabled`），用户明确要求
            // 改成直接隐藏：开关关着的时候，这一页只该剩下"总开关"本身，
            // 免得一屏灰掉的控件把页面撑得很长、也免得误以为功能坏了。
            //
            // 保留页脚那句"子项只在总开关开启时生效…"：它解释的正是"现在为什么是空的"。
            if (masterEnabled) {
            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 分区速览（2026-11-14 需求 5）---------------------------------
            //
            // 本页有六个分区、两三屏长，改一项往往要滚半天找。这排小胶囊列出全部分区，
            // 点一下滚到对应位置——比在顶部堆一个"目录页"轻，也不会把开关挤得更深。
            // 位置来自各分区标题的 `onGloballyPositioned`，初次组合后即填好；
            // 还没测到（极少见）时滚到 0，不会崩。
            DeveloperSectionOverview(
                sections = DEV_SECTIONS,
                onJump = { name ->
                    jumpScope.launch {
                        pageScroll.animateScrollTo(sectionTops[name] ?: 0)
                    }
                }
            )
            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 开屏 --------------------------------------------------------
            // ## 为什么单独一个「开屏」分区（2026-10-27 重排）
            //
            // 这两行原先分散在两处：「Hello World 显示时长」在「性能测试」卡片里
            // （历史原因：它最初是用来量"开屏拖慢了多少启动"的），
            // 「实验室功能开屏」在「实验室测试」卡片里。
            // 但它们改的都是同一件事——**开屏显示多久 / 哪些页面有开屏**，
            // 与"性能"和"实验室"都只是历史耦合。归到一处后，
            // 用户想调开屏只需看这一个分区。
            //
            // ⚠️ 顺带放宽了门控：应用开屏时长以前还要求「启用性能测试」也开着，
            // 现在只受总开关门控（用户在这个分区拖滑块，却被另一个分区的开关卡住，
            // 是上一版最容易被误解的地方）。
            DeveloperSectionTitle(
                "开屏",
                icon = Icons.Rounded.SlowMotionVideo,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["开屏"] = it.positionInParent().y.toInt()
                }
            )
            Card(Modifier.fillMaxWidth()) {
                // ---- App 启动开屏显示时长（0~5 秒，可一键恢复默认）------------
                //
                // ⚠️ 2026-10-27：本行从「性能测试」卡片迁到这里，门控同步放宽为
                // **只受总开关**（原来还要求"启用性能测试"开着，见分区注释）。
                // 生效合流见 `DeveloperOptionsRepository.splashHoldDurationMs`。
                //
                // 不满足门控时滑块置灰、**但仍显示当前值**——「值还在、只是暂不生效」
                // 这条口径与别的子项完全一致，不能让用户以为设置被清空了。
                //
                // ⚠️ 它只管**应用冷启动**那一层 Hello World。实验室功能页/游戏的
                // 开屏时长是另一个值（在下面那行的二级页里），两者互不影响。
                SplashHoldRow(
                    holdMs = splashHoldMs,
                    // 「随机浮动」勾选框：勾上后实际时长 = 设定值 ±15%（2026-10-30 起为比例）。
                    // 它取代了原先那条"浮动范围"滑块（需求 8 明确删除）。
                    jitterEnabled = splashJitterEnabled,
                    // 只受总开关门控（见上方分区注释）。
                    enabled = masterEnabled,
                    onValueChange = { scope.launch { repository.setSplashHoldMs(it) } },
                    onJitterChange = { scope.launch { repository.setSplashJitterEnabled(it) } },
                    onRestoreDefault = {
                        scope.launch { repository.setSplashHoldMs(SettingsRepository.DEFAULT_SPLASH_HOLD_MS) }
                    },
                    // 2026-10-25 需求：首次勾选先弹介绍、之后改用长按工具提示。
                    jitterIntroShown = splashJitterIntroShown,
                    onJitterIntroShown = {
                        scope.launch { repository.setSplashJitterIntroShown(true) }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 实验室功能开屏（2026-10-27 改为入口 + 二级页）-------------
                //
                // 需求原文：「实验室功能开屏可单独设置包括热搜和三个游戏，有一个总开关，
                // 总开关关闭时都不显示开屏。实验室开屏功能与 app 开屏时长分开控制」。
                //
                // 因此这一行从**开关**变成**入口**：右侧保留一个总开关（关掉即整组不播），
                // 点进去逐项设置（其他页面 / 热搜 / 三个游戏）并单独调时长。
                DeveloperActionRow(
                    title = LAB_FEATURE_SPLASH_TITLE,
                    description = LAB_FEATURE_SPLASH_DESCRIPTION,
                    trailing = TrailingStyle.NavigationWithSwitch,
                    enabled = masterEnabled,
                    onAction = { showLabSplash = true },
                    // 行开关显示**生效值**（总开关），与「游戏标题栏」那几行同口径：
                    // 关掉开发者选项总开关时它会显示"开"，因为那时实际就是按开走。
                    switchChecked = labSplashAllEffective,
                    onSwitchChange = { scope.launch { repository.setLabSplashAll(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 开屏显示文字：已迁至「设置 → 个性化」（2026-11-08）--------
                //
                // 这里原来是一个二级页（文案 / 字号 / 字体 / 单多行 / 恢复默认 + 预览），
                // 而「设置 → 个性化」里还有一套**同名的弹窗编辑**——同一个设置两套界面，
                // 用户分不清哪边才是真的，改完还容易以为没生效。
                //
                // 现在统一收到「设置 → 个性化」（那份带检查更新 / 日志入口 / 日期格式，
                // 是一整套"个性化偏好"），开发者选项这边**只留一句提示**，
                // 不再提供第二个入口。二级页已经删除，不是藏起来了。
                Text(
                    text = "开屏显示文字、日期格式、检查更新与更新日志入口都已迁至「设置 → 个性化」",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 动画与反馈 -----------------------------------------------------
            // ## 动画与反馈（2026-10-27 合并）
            //
            // 原先散在「界面测试」一张卡里的 7 项，共同点是**都只改观感**：
            // 撒花、页面推入/退出、底栏切换、两处时长、按压缩放、退出转圈。
            // 它们的默认值全部等于改造前的既有行为（除撤回转圈是"新行为默认开"）。
            //
            // 行序：**越靠近"整页"的越靠前**（撒花 → 转场 → 底栏 → 时长 → 按压 → 退出），
            // 用户按"我在哪一层看到的动画"就能定位。
            DeveloperSectionTitle(
                "动画与反馈",
                icon = Icons.Rounded.Animation,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["动画与反馈"] = it.positionInParent().y.toInt()
                }
            )
            Card(Modifier.fillMaxWidth()) {
                // ---- 打卡完成动画 --------------------------------------------
                //
                // 门控主页打卡后的彩带动画（`ui/common/Confetti.kt`）。
                // 全项目**只有主页一处调用**（`HomeScreen` 里那个 `ConfettiEffect`），
                // 因此改一处即全站生效。
                DeveloperSwitchRow(
                    title = CHECKIN_CELEBRATION_TITLE,
                    description = CHECKIN_CELEBRATION_DESCRIPTION,
                    // 原始值（默认 true）+ 只受总开关门控，与本分区另外两项同口径。
                    checked = checkinCelebrationSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setCheckinCelebrationEnabled(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 页面转场动画 --------------------------------------------
                //
                // 门控应用内页面推入 / 弹出的转场（`TabNavHost` 的四个转场函数，
                // 含贪吃蛇与番茄钟那条纵向的）。默认 true = 与改造前一致。
                DeveloperSwitchRow(
                    title = PAGE_TRANSITION_TITLE,
                    description = PAGE_TRANSITION_DESCRIPTION,
                    checked = pageTransitionAnimSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setPageTransitionAnim(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 底部入口切换动画（2026-10-22 从「性能测试」迁入）----------
                //
                // 需求原文：「开发者选项-界面测试 增加四个底部入口切换动画的开关」。
                // 它原先在「性能测试」卡片里叫「左右滑动动画」，管的是同一件事
                // （一级入口切换时页面跟随手指位移 / 两页整屏滑移），
                // 归到「界面测试」更贴切：它不是性能开关，而是纯粹的界面观感动画。
                //
                // **迁移而不是新增第二个**：两个开关写同一个 DataStore 键、
                // 管同一段动画，迟早漂移成"一个开一个关到底听谁的"。
                // 因此旧位置的那一行随本次迁移一并删除，全应用只剩这一个入口。
                //
                // 门控回到「只有总开关」（不再要求启用性能测试）：它已经属于
                // 界面测试分区，跟本分区另外三项同一口径。
                // 生效规则见 `DeveloperOptionsRepository.tabSlideAnimEnabled`：
                // 总开关关闭时回落"开"（= 改造前一直有动画，不能让开关默认拿走它）。
                DeveloperSwitchRow(
                    title = TAB_SLIDE_ANIM_TITLE,
                    description = TAB_SLIDE_ANIM_DESCRIPTION,
                    checked = tabSlideAnimSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setTabSlideAnim(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 界面切换动画时长（2026-10-25 需求）----------------------
                //
                // 二级页推入 / 返回的滑移时长（`TabNavHost` 的 pushEnter / popExit）。
                // 默认 280ms = 改造前的固定值；上面的「页面转场动画」开关关掉时，
                // 这个时长不起作用（没有动画可谈），但滑块照常显示。
                AnimDurationRow(
                    title = NAV_ANIM_TITLE,
                    ms = navAnimMs,
                    enabled = masterEnabled,
                    onValueChange = { scope.launch { repository.setNavAnimMs(it) } },
                    onRestoreDefault = {
                        scope.launch {
                            repository.setNavAnimMs(SettingsRepository.DEFAULT_NAV_ANIM_MS)
                        }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 导航栏切换动画时长（2026-10-25 需求）--------------------
                //
                // 底栏四入口切换的滑入动画时长（`MoreCheckApp` 的 tabSwitchTo）。
                // 默认 260ms = 改造前的固定值；「底部入口切换动画」开关关掉时同样不生效。
                AnimDurationRow(
                    title = TAB_SWITCH_ANIM_TITLE,
                    ms = tabSwitchMs,
                    enabled = masterEnabled,
                    onValueChange = { scope.launch { repository.setTabSwitchMs(it) } },
                    onRestoreDefault = {
                        scope.launch {
                            repository.setTabSwitchMs(SettingsRepository.DEFAULT_TAB_SWITCH_MS)
                        }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 按压反馈 ----------------------------------------------
                //
                // 门控 `ui/common/PressScale.kt` 那个**全应用统一**的按压缩放。
                // 本项目有 40 余处调用点，但它们**一个都不需要改**——
                // `pressScale` 自己读这个开关（`DeveloperOptionsRepository` 里
                // 说明了为什么必须由它自己读，而不是做成形参传给 40 个调用点）。
                DeveloperSwitchRow(
                    title = PRESS_FEEDBACK_TITLE,
                    description = PRESS_FEEDBACK_DESCRIPTION,
                    checked = pressFeedbackSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setPressFeedbackEnabled(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 撤回退出转圈（2026-10-25 需求，回退开关）----------------
                //
                // 「撤回隐私同意 → 确认退出」点击后按钮变灰转圈、0.2 秒后才真正退出。
                // 本开关是该项的**回退开关**：关掉后确认立即退出（= 改造前的旧行为）。
                // 默认开（新行为即默认行为）。
                DeveloperSwitchRow(
                    title = REVOKE_EXIT_SPINNER_TITLE,
                    description = REVOKE_EXIT_SPINNER_DESCRIPTION,
                    checked = revokeExitSpinnerSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setRevokeExitSpinner(it) } }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 实验室与游戏 ----------------------------------------------------
            // ## 实验室与游戏（2026-10-27 调整）
            //
            // 2026-10-30 用户要求**移除「启用游戏设置」总开关**：那一级中间门在
            // 标题栏 / 配色 / 开屏三类子项之间来回绕，实际只起到"让三行一起变灰"的作用，
            // 用户看不出它解决了什么问题，反而多一次"为什么点了没反应"的排查。
            // 现在游戏子项与其它分区同一口径：**开发者选项总开关 && 子开关**。
            // 键 `dev_game_settings_enabled` 的数据仍留在 DataStore 里，但无人读取
            // （与 `dev_perf_test_enabled` / `dev_game_splash` 同一处理，不留兼容层）。
            //
            // 「实验室背景设置」放在游戏三行之前：它是实验室页自己的外观，
            // 与"游戏"只是同属实验室功能。
            DeveloperSectionTitle(
                "实验室与游戏",
                icon = Icons.Rounded.Science,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["实验室与游戏"] = it.positionInParent().y.toInt()
                }
            )
            Card(Modifier.fillMaxWidth()) {
                // ---- 实验室背景设置 --------------------------------------------
                //
                // 需求原文：「开发者选项背景设置选项（默认：与目前一致，按规则随机轮换；
                // 经典：跟随系统的浅色与深色；自定义：自行挑选颜色）」。
                // 实际作用于**实验室页**（发现 → 实验室）的背景：那是本应用唯一一个
                // 有"按规则随机轮换"背景的页面，需求里说的「开发者选项背景」即指它
                // （开关本身挂在开发者选项页，与「验证码测试进二级页」同属这一页的管理范围）。
                //
                // 三档模式（random / classic / custom）的选择界面做在 [LabBackgroundScreen]
                // 二级页（与登录测试同构的就地覆盖），入口行只负责"点开进下一级"。
                //
                // ⚠️ 本行连同下面三行是从「图标测试」卡片**整块迁过来**的，
                // 门控（`enabled = masterEnabled`）、视觉（`TrailingStyle.Navigation`）
                // 与文案一个字都没改——本需求只改归类，不改任何行为。
                DeveloperActionRow(
                    title = LAB_BG_TITLE,
                    // 需求 14：本分区不再显示副标题备注（只留标题）。
                    description = "",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    // 需求 5：同「游戏颜色模式」，右侧外显当前档位。
                    valueText = labBgModeLabel(labBgMode),
                    onAction = { showLabBackground = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 游戏标题栏显示（本轮需求 5：开关改为二级界面入口）----------
                //
                // 需求原文：「游戏标题栏显示增加二级界面，可单独设置标题栏显隐，
                // 原总开关保留，右侧增加向右箭头」。因此这里从 [DeveloperSwitchRow]
                // 改成 [DeveloperActionRow] + `TrailingStyle.Navigation`（右箭头），
                // 真正的两个子开关（贪吃蛇 / 数独各一个）搬进 [GameTitleBarScreen]。
                //
                // **原总开关保留**：它的语义从"直接管两页"变为"两页共同的总门"——
                // 关掉它，二级页里的两个子开关无论怎么拨都不生效
                // （生效规则见 `DeveloperOptionsRepository.gameTitleBarSnakeEnabled`）。
                // checked 仍读原始值：总开关关时位置照旧显示、只是暂不生效（本页 KDoc 口径）。
                DeveloperActionRow(
                    title = GAME_TITLE_BAR_TITLE,
                    // 需求 14：本分区不再显示副标题备注（只留标题）。
                    description = "",
                    trailing = TrailingStyle.NavigationWithSwitch,
                    // 2026-10-30：中间门（「启用游戏设置」）已移除，只剩开发者选项总开关。
                    enabled = masterEnabled,
                    onAction = { showGameTitleBar = true },
                    // 2026-10-25 需求 9：行开关改读**生效值**——总开关（任一级）关掉时
                    // 标题栏实际恒显示，行开关跟着显示"开"，不再出现"实际显示、开关却是关"。
                    switchChecked = gameTitleBarEffective,
                    onSwitchChange = { scope.launch { repository.setGameTitleBar(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 游戏颜色模式（需求 3：三档二级界面入口）--------------------
                //
                // 需求原文：「游戏标题栏/状态栏颜色做三档可选：跟随系统 / 深色经典 /
                // 深色适配」。因此它是一行**入口**（点开进 [GameChromeScreen]），
                // 三档互斥单选放在二级页里做，一级页不塞三个单选框。
                //
                // 与上面两行游戏设置（标题栏开关 / 开屏入口）同一套门控与视觉：
                // 右侧右箭头、整行可点、`enabled = masterEnabled`。
                // 它同时作用于贪吃蛇与数独两页（与「游戏标题栏显示」同一口径），
                // 说明文案里点明这一点，避免用户以为只是其中一页。
                DeveloperActionRow(
                    title = GAME_CHROME_TITLE,
                    // 需求 14：本分区不再显示副标题备注（只留标题）。
                    description = "",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    // 需求 5：入口行右侧外显当前档位（截图里的红色「跟随系统」）。
                    valueText = gameChromeModeValue.label,
                    onAction = { showGameChrome = true }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 音乐（2026-11-09 需求）---------------------------------------
            // ## 音乐
            //
            // 两项都只影响音乐页自身，且都属**行为开关**（不是"关掉总开关该回落成什么"
            // 的观感项）：生效值在仓库层是 `总开关 && 子开关`，关总开关即等于关闭。
            //
            // 音乐的状态在进程内单例 `MusicPlayback` 里，开关拨完回音乐页立刻生效，
            // 既不需要重启应用，也不需要重新进页面。
            // ---- 音乐 --------------------------------------------------------
            // ## 音乐（2026-11-14 需求 2 起只剩歌词一项）
            //
            // 进 / 出音乐页的两个确认弹窗**整段搬到了「设置 → 音乐」**：
            // 它们影响的是用户日常进出音乐页的感受，不属于"开发者开关"，
            // 两处各摆一份只会让人怀疑"我到底关的是哪一个"。
            // 本页继续保留的「入口实时歌词」是观感类子项，与确认弹窗无关。
            DeveloperSectionTitle(
                "音乐",
                icon = Icons.Rounded.Headphones,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["音乐"] = it.positionInParent().y.toInt()
                }
            )
            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    title = MUSIC_ENTRY_LYRICS_TITLE,
                    description = MUSIC_ENTRY_LYRICS_DESCRIPTION,
                    checked = musicEntryLyricsSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setMusicEntryLyrics(it) } }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 图标 --------------------------------------------------------
            // ## 图标（原「图标测试」，2026-10-27 改名）
            //
            // 本分区只剩两项真正与图标/角标有关的设置：
            // 底栏「发现」的未读红点、（入口）更换桌面图标。
            //
            // 「图标更改」仍压在最后：它是本页唯一"有对外副作用"的动作
            // （会立刻改写桌面图标），靠底部降低误触。
            DeveloperSectionTitle(
                "图标",
                icon = Icons.Rounded.Image,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["图标"] = it.positionInParent().y.toInt()
                }
            )
            Card(Modifier.fillMaxWidth()) {
                // ---- 朋友圈红点提示 -------------------------------------------
                //
                // 需求：底部导航栏「发现」入口的未读红点 / 数字可以关掉，**默认开启**。
                //
                // 门控与其他子项完全一致：只有总开关一个（`enabled = masterEnabled`），
                // 关掉总开关时这一行置灰、点不动，但**开关位置照旧显示自己的值**——
                // 这就是 [DeveloperSwitchRow] 里 `checked`（原始值）与 `enabled`（是否可交互）
                // 分开传的原因，不要在这里改成读"生效值"。
                //
                // ⚠️ 它门控的是**显示**，不是未读状态本身：进朋友圈清未读那条链路
                // （`TabNavHost` 的 `onMomentsOpened` → `TabBadgeViewModel.clear(DISCOVER)`）
                // 不受此开关影响，见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc。
                DeveloperSwitchRow(
                    title = MOMENTS_BADGE_TITLE,
                    description = MOMENTS_BADGE_DESCRIPTION,
                    // 原始值：来自 DataStore（默认 true），与总开关无关。
                    checked = momentsBadgeEnabled,
                    // 与「验证码测试」「启用性能测试」同一口径：只受顶部总开关门控。
                    enabled = masterEnabled,
                    onCheckedChange = { settingsViewModel.setMomentsBadgeEnabled(it) }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 图标更改（实验性）--------------------------------------
                //
                // 它是一行**入口**（点开进 `IconPickerScreen`）——该功能自己就是一个
                // 有多张卡片、有裁切流程的页面，塞不进一个开关行。
                //
                // 放本分区**最底部**：它是本页唯一"有对外副作用"的动作（立刻改写桌面图标），
                // 压在末尾降低误触。与上一轮同一条口径，只是当时对照的分组已经变了。
                DeveloperActionRow(
                    title = "图标更改(实验性)",
                    description = ICON_CHANGE_DESCRIPTION,
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    // 需求：进入前强制弹风险提醒（测试中 / 崩溃风险），确认后才进。
                    onAction = { showIconWarn = true }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 工具与测试 -----------------------------------------------------
            // ## 工具与测试（2026-10-27 合并，2026-11-03 增补界面尺寸尺）
            //
            // 都是"调试 / 验证"性质，误触不会改变应用的正常观感：
            // 登录测试（验证码与通知）、检查更新是否在应用内打开、支持作者链接、
            // 以及 2026-11-03 新增的「界面尺寸尺」（全屏标尺，量控件高度用）。
            // 放在全页最底部——正常用户几乎不会用到，但开发者需要时又必须找得到。
            //
            // ⚠️ 2026-10-27 用户要求**移除「启用性能测试」子开关**（本卡从三项变两项）：
            // 它原本门控的「开屏显示时长」上一轮已迁到「开屏」分区并放宽为只受总开关，
            // 于是这个开关**没有任何消费方**——留着就是一个拨了不产生任何效果的开关。
            // 键 `dev_perf_test_enabled` 的数据保留在盘上（回滚时还能用），但不再有读写入口。
            DeveloperSectionTitle(
                "工具与测试",
                icon = Icons.Rounded.Build,
                modifier = Modifier.onGloballyPositioned {
                    sectionTops["工具与测试"] = it.positionInParent().y.toInt()
                }
            )
            Card(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        lastSectionPx = coords.positionInParent().y.toInt() +
                            coords.size.height - (sectionTops["工具与测试"] ?: 0)
                    }
            ) {
                // 入口行：右侧是**右箭头**（不再是「进入」按钮），整行可点进二级页。
                // 与「我的」页同一套视觉语言（`Icons.AutoMirrored.Filled.KeyboardArrowRight`
                // + `onSurfaceVariant`，默认 24dp 尺寸），箭头只是"这行可以点"的指示物。
                DeveloperActionRow(
                    title = LOGIN_TEST_TITLE,
                    description = LOGIN_TEST_DESCRIPTION,
                    // 形态开关：右侧显示导航箭头而不是动作按钮。
                    trailing = TrailingStyle.Navigation,
                    // 门控只有总开关一个（与改造前两个子项的口径逐字相同）。
                    enabled = masterEnabled,
                    onAction = { showLoginTest = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 链接打开方式（2026-11-05 需求：一键设置）------------------
                //
                // 需求原文：「开发者选项里支持一键设置链接在应用内打开或默认浏览器打开」。
                // 所以这里不做二选一弹窗，点一下行就切到另一档，右侧直接显示当前值。
                // 生效范围是**所有走 util.openInBrowser 的链接**（检查更新 / 支持作者 /
                // 热搜原文 / 新闻原文 / 安装包页的仓库地址等），一处开关全覆盖。
                // 「官网」页右上那个「用系统浏览器打开」按钮是明确的反向动作，
                // 刻意不经过 openInBrowser，不受本开关影响。
                DeveloperActionRow(
                    title = "链接打开方式",
                    description = "点一下切换：应用内网页 / 系统默认浏览器",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    valueText = if (linkOpenInApp) "应用内网页" else "系统浏览器",
                    onAction = {
                        val next = !linkOpenInApp
                        // 先写进程级缓存：DataStore 回传前这一两帧里若用户立刻点了链接，
                        // 也该按刚选的方式走，不能出现"拨了开关没生效"的错觉。
                        LinkOpenGate.inApp = next
                        scope.launch {
                            repository.setLinkOpenInApp(next)
                            onMessage(
                                if (next) "链接已改为应用内打开" else "链接已改为系统浏览器打开"
                            )
                        }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 2026-11-14：发现页官网位 / 入口管理 ----
                DeveloperActionRow(
                    title = "发现页官网位",
                    description = "点一下切换：官网（www.scif.top）/ 搜一搜（百度首页）",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    valueText = if (discoverSearchMode) "搜一搜" else "官网",
                    onAction = {
                        scope.launch { repository.setDiscoverSearchMode(!discoverSearchMode) }
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                DeveloperActionRow(
                    title = "入口管理",
                    description = "把实验室功能加到发现页，或把音乐 / 热搜等入口移到实验室",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    onAction = { showEntryPlacement = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // 「支持作者链接」「更新日志入口」「检查更新按钮功能」三行也已迁至
                // 「设置 → 个性化」（2026-11-08）。这里不再重复提供入口——
                // 同一设置两处可改，用户会以为"改了一处没生效"。
                // ---- 界面尺寸尺（2026-11-03 需求 6）--------------------------
                //
                // 需求原文：「开发者选项增加高度尺码图，全屏标尺量高度」。
                // 做成**整页全屏覆盖**（不是二级页滑动宿主）：它要隐藏系统栏、
                // 铺满整个窗口才能从状态栏下沿量到屏幕底，走二级页的动画容器会多一层
                // 有位移的中间态，反而让"屏幕边缘"这个参照物在动画期间对不准。
                // 因此它和下面的 AlertDialog 们不同层，直接盖在整个页面最上面。
                DeveloperActionRow(
                    title = "界面尺寸尺",
                    description = "全屏标尺：量高度 / 宽度（dp 与 px）",
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    onAction = { showHeightRuler = true }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            } else {
                // 关闭态：只留一句说明（不列任何功能项）。
                Spacer(Modifier.height(SECTION_SPACING))
                Text(
                    text = "打开上方总开关后，这里会显示全部功能项。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            // 尾部留白 = 视口 − 末段高：没有它 animateScrollTo 会被列表末尾夹住，
            // 末段标题永远到不了顶。关闭态不加（否则留一整屏空白）。
            if (masterEnabled) {
                val tailPx = (viewportPx - lastSectionPx).coerceAtLeast(0)
                if (tailPx > 0) {
                    Spacer(Modifier.height(with(LocalDensity.current) { tailPx.toDp() }))
                }
            }

            Spacer(Modifier.height(24.dp))
            }
        }

        // ---- 六个二级页（就地覆盖 + 动画容器）---------------------------
        //
        // ⚠️ 必须画在一级页 Column **之后**（同一 Box 内的叠加关系）：
        // `Box` 的后声明者在上层，二级页才真正"盖"在一级页上面。
        //
        // visible 传六个布尔状态的「或」：六者互斥（各自独立进入，谁也不能从
        // 一个二级页跳进另一个），任意时刻最多只有一个为 true。
        // 具体显示哪一页由下面的 `when` 决定——动画容器只负责进出场，
        // 不反查是哪一页（职责分离，见 [SecondaryPageEnterHost] 的 KDoc）。
        //
        // ⚠️ 新增二级页时**这个「或」必须同步加一项**，否则那一页完全不显示
        // （不进组合就没有任何报错，只会表现为"点了入口没反应"）。
        // 本轮需求 3 加的 `showGameChrome` 就属于这一类。
        // ── 2026-10-25 修复「二级页返回短暂闪屏」────────────────────────────
        //
        // ## 根因
        //
        // onBack 把对应布尔置 false 后，`secondaryVisible` 立即为 false、
        // [SecondaryPageEnterHost] 开始播**退场**动画（沿原路滑回右侧）；
        // 但下面那个 `when` 此时**一个分支都不命中**（十个布尔全是 false），
        // 于是正在滑出的那一层瞬间变成了**空白页**在滑——用户看到的就是
        // 「返回时白屏闪一下」。
        //
        // ## 修法：退场期间继续渲染「最近显示过的那一页」
        //
        // `currentSecondary` 是此刻由布尔推出的页（全 false 时为 null）；
        // `lastSecondaryPage` 记住最近一次非 null 的页。渲染用
        // `currentSecondary ?: lastSecondaryPage`：进入时两者相同；
        // 返回的退场动画期间前者为 null、后者仍是那一页——滑出的层内容完整，
        // 动画播完 AnimatedVisibility 把整块移出组合，`lastSecondaryPage`
        // 残留的引用随之一起释放（下次进入会被新值覆盖）。
        val currentSecondary = when {
            showLoginTest -> SecondaryPage.LOGIN_TEST
            showIconPicker -> SecondaryPage.ICON_PICKER
            showLabBackground -> SecondaryPage.LAB_BACKGROUND
            showLabSplash -> SecondaryPage.LAB_SPLASH
            showGameChrome -> SecondaryPage.GAME_CHROME
            showGameTitleBar -> SecondaryPage.GAME_TITLE_BAR
            showEntryPlacement -> SecondaryPage.ENTRY_PLACEMENT
            else -> null
        }
        var lastSecondaryPage by remember { mutableStateOf<SecondaryPage?>(null) }
        SideEffect {
            if (currentSecondary != null) lastSecondaryPage = currentSecondary
        }
        val pageToRender = currentSecondary ?: lastSecondaryPage

        SecondaryPageEnterHost(visible = secondaryVisible) {
            when (pageToRender) {
                SecondaryPage.LOGIN_TEST -> LoginTestScreen(
                    // 门控只有总开关一个：两个子项的口径与改造前逐字相同（见本文件 KDoc 的门控表）。
                    masterEnabled = masterEnabled,
                    // 传**原始值**（不是与过总开关的 `codeTestEnabled`），理由见本文件 KDoc 的
                    // 「原始值 vs 是否生效」一节——否则一关总开关，开关会跳到"关"，
                    // 用户会以为自己刚设的值被清空了。
                    codeTestSwitch = codeTestSwitch,
                    onCodeTestChange = { scope.launch { repository.setCodeTestEnabled(it) } },
                    onBack = { showLoginTest = false },
                    onMessage = onMessage
                )

                SecondaryPage.ICON_PICKER -> IconPickerScreen(
                    // 门控只有一个总开关（图标测试没有独立子开关了，见上方 KDoc 的门控表）。
                    enabled = masterEnabled,
                    onBack = { showIconPicker = false },
                    onMessage = onMessage
                )

                SecondaryPage.LAB_BACKGROUND -> LabBackgroundScreen(
                    onBack = { showLabBackground = false }
                )

                // 2026-10-27：「实验室功能开屏」（总开关 + 时长 + 逐项）。
                SecondaryPage.LAB_SPLASH -> LabSplashScreen(
                    masterEnabled = masterEnabled,
                    onBack = { showLabSplash = false }
                )

                SecondaryPage.GAME_CHROME -> GameChromeScreen(
                    // 门控口径与一级页其它子项一致：总开关关时三档单选框置灰。
                    masterEnabled = masterEnabled,
                    onBack = { showGameChrome = false }
                )

                // 本轮需求 5：「游戏标题栏显示」二级页（贪吃蛇 / 数独各一个子开关）。
                SecondaryPage.GAME_TITLE_BAR -> GameTitleBarScreen(
                    masterEnabled = masterEnabled,
                    onBack = { showGameTitleBar = false }
                )

                SecondaryPage.ENTRY_PLACEMENT -> EntryPlacementScreen(
                    masterEnabled = masterEnabled,
                    onBack = { showEntryPlacement = false },
                    onMessage = onMessage
                )

                null -> Unit
            }
        }

        // 「图标更改(实验性)」进入前的强制风险提醒（需求）：
        // 5 秒倒计时内「知道了，继续」不可点——倒计时是刻意的，逼着用户读完风险说明，
        // 而不是像普通确认框那样闭眼点确认。
        if (showIconWarn) {
            IconExperimentalWarnDialog(
                onDismiss = { showIconWarn = false },
                onConfirm = {
                    showIconWarn = false
                    showIconPicker = true
                }
            )
        }

        // ---- 全屏工具：界面尺寸尺（2026-11-03 需求 6）-----------------------
        //
        // 放在本 Box 的**最后一个子项**：`Box` 后声明者在上层，它才能盖住一级页
        // 的 Scaffold（含 TopAppBar）与所有二级页。
        // 页内自己接系统返回键、进入时隐藏系统栏、退出（组合销毁）时还原，
        // 详见 `HeightRulerScreen` 的 KDoc。
        if (showHeightRuler) {
            HeightRulerScreen(onExit = { showHeightRuler = false })
        }
    }
}

/**
 * 开发者选项页里可显示的二级页集合（2026-10-25 引入，用于修「返回闪屏」）。
 *
 * 详见上方 `lastSecondaryPage` 那段注释：退场动画期间需要一个"最近显示的是哪页"
 * 的记录，十个布尔拼不出这个信息，枚举一枚搞定。
 */
private enum class SecondaryPage {
    LOGIN_TEST,
    ICON_PICKER,
    LAB_BACKGROUND,
    LAB_SPLASH,
    GAME_CHROME,
    GAME_TITLE_BAR,
    /** 2026-11-14：发现页与实验室入口归属管理。 */
    ENTRY_PLACEMENT,
    /** 2026-10-27：开屏显示文字（取代原 DATE_FORMAT 那一条二级页）。 */
}

/**
 * 页首的总开关卡片。
 *
 * 用 [MaterialTheme.colorScheme.primaryContainer] 作容器色，是为了让它在下面几张卡片里
 * **一眼看出是"总"**（参考图里的开发者选项也是这种高亮卡片）。这一点很关键：
 * 本页各子项在总开关关闭时都是灰的，如果总开关卡片和下面几张长得一样，
 * 用户会先去找"为什么点不动"，而不是先来开这个总开关。
 *
 * 不写死颜色：`primaryContainer` 会随主题色走，深色模式下 Material3 给的是一档很深的
 * 同色系容器色，文字用对应的 `onPrimaryContainer`，因此**不会出现深色卡片上的深色字**。
 * 卡片内的开关也用默认配色（不额外指定），它在容器色上依然有足够对比度。
 */
@Composable
private fun MasterSwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // 与右侧开关拉开距离：标题换行时不会贴到开关上。
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "启用开发者选项",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "开启此开关后，下列功能才会生效",
                    style = MaterialTheme.typography.labelSmall,
                    // 副标题压到 75%：同一容器色内的主次关系，靠 alpha 而不是换颜色。
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * 分区小标题（「登录测试」「性能测试」「图标测试」「实验室测试」「界面测试」）。
 *
 * 与 `SoundSettingsScreen.SectionTitle` 取同一套视觉语言（labelLarge + onSurface 压到 75%），
 * 但**函数名不叫 `SectionTitle`**：本文件与 `LabScreen.kt` 同属 `com.morecheck.ui.lab` 包，
 * 顶层函数一旦重名（哪怕一个是 private）都会在编译期报 Conflicting overloads；
 * 实验室那几个文件正被并行改动，起一个本页专属的名字可以彻底避开这种撞车。
 *
 * 为什么不用 `onSurfaceVariant`：它在浅色模式下偏灰（#888888），而本项目设置类页面的
 * 小标题统一走 `onSurface` 压暗，比它更黑一点，两者放一起才不会显得"两套灰"。
 */
@Composable
private fun DeveloperSectionTitle(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            // 2026-11-14 需求 5：分区标题补图标，扫一眼就知道这一段管什么。
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/**
 * 「分区速览」：一排可点的小胶囊，点了滚到对应分区。
 *
 * [sections] 是「标题 → 图标」的有序表，与正文里分区标题的调用顺序保持一致；
 * 两边共用同一份 [DEV_SECTIONS] 常量，改一处不会漏另一处。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperSectionOverview(
    sections: List<Pair<String, ImageVector>>,
    onJump: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Text(
                text = "分区速览",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { (name, icon) ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .clickable { onJump(name) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

/** 六个分区的顺序与图标（「分区速览」与正文标题共用）。 */
private val DEV_SECTIONS: List<Pair<String, ImageVector>> = listOf(
    "开屏" to Icons.Rounded.SlowMotionVideo,
    "动画与反馈" to Icons.Rounded.Animation,
    "实验室与游戏" to Icons.Rounded.Science,
    "音乐" to Icons.Rounded.Headphones,
    "图标" to Icons.Rounded.Image,
    "工具与测试" to Icons.Rounded.Build
)

/**
 * 一行开关：标题 + 说明 + 右侧 [Switch]。
 *
 * 三项子开关的结构完全同构，所以抽成一个组件，只在调用处绑各自的标题、说明与 setter；
 * 否则三份几乎一样的 Row 会各自漂移（本项目已经出现过"某一页的副标题忘了压暗"这类不一致）。
 *
 * ## `checked` 与 `enabled` 是两个不同的概念
 *
 * - [checked] 传**仓库里的原始值**——总开关关掉后，子开关仍然显示它自己的位置；
 * - [enabled] 传**总开关**——总开关关掉时这一行整体置灰、点不动。
 *
 * 两者刻意不合并成一个参数：合并之后"用生效值当 checked"这种写法会变得很顺手，
 * 而那正是会把用户设置"看起来清空"的写法（详见 [DeveloperOptionsScreen] 的 KDoc）。
 *
 * ## 置灰为什么是给文字乘 alpha，而不是换一组灰色
 *
 * [Switch] 在 `enabled = false` 时由 Material3 自己按规范调淡，所以**不能**把 alpha 套在
 * 整行上，否则开关会被双重调淡、比文字还浅。这里只对左侧文字区乘
 * [DISABLED_CONTENT_ALPHA]（与 M3 内部同一档），让"文字和开关一样灰"。
 */
@Composable
private fun DeveloperSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                // 1f = 不透明，等于不加任何效果（alpha 会触发一次图层合成，
                // 但只在总开关切换时变化，代价可以忽略）。
                .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                // 标题用项目统一的 strongTextColor（浅色模式纯黑），
                // 与「权限管理」「声音设置」的开关行同口径。
                color = MaterialTheme.strongTextColor
            )
            // description 传空串 = **不要副标题**（2026-10-22 需求 14：
            // 「实验室测试」分区下四行都去掉了副标题备注）。
            // 这里用条件渲染而不是渲染一个空 Text：空 Text 仍会占一行行高，
            // 行高会比其它无副标题的行高出一截。
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    // 说明文字可能有两三行，行距靠 typography 自带的行高，不再手工加 Spacer。
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 总开关关闭时由 Material3 自己置灰并屏蔽点击，
            // 不需要额外写 `clickable(enabled = false)` 之类的兜底。
            enabled = enabled
        )
    }
}

/**
 * 「带按钮」的一行：标题 + 说明 + 右侧控件（按钮或箭头）。
 *
 * 与 [DeveloperSwitchRow] 同构（同一套内边距 / 字号 / 置灰系数），
 * 区别只在右侧是**动作**而不是开关——「通知测试」按一下立刻发生，
 * 没有"开/关"这种持续状态，用 `Switch` 表达反而会让人以为它会记住什么。
 *
 * ## 为什么用 [trailing] 区分「动作按钮」与「导航箭头」两种形态，而不是新写一个组件
 *
 * 本组件被两种行复用：
 * - **动作行**（[TrailingStyle.Button]）：右侧是 [OutlinedButton]，按一下当场生效
 *   （目前只有「通知测试」，会真的发一条通知并进 10 秒冷却）；
 * - **入口行**（[TrailingStyle.Navigation]）：右侧只是一个右箭头，点整行进二级页
 *   （「登录测试」「图标更改」「设备信息查询」三处）。
 *
 * 两种行的**标题区、说明区、内边距、置灰系数完全一致**，差异只在最右侧那一小块和
 * 点击范围。项目里已经吃过"三份几乎一样的 Row 各自漂移"的亏（见 [DeveloperSwitchRow]
 * 的 KDoc），所以这里给同一个组件加一个形态参数，而不是复制一份：
 * 复制出来的那一份迟早会在某次改动里只改到一处（例如改了内边距却忘了另一份）。
 *
 * 形态参数**刻意不给默认值**（四个调用点全部显式写出）：给一个默认值就等于允许
 * "忘了选形态"，而下一次新增一行时分歧会静悄悄地发生——入口行画成按钮、
 * 或动作行画成箭头，都不会有编译错误。显式写出让每一行的形态在调用处可读。
 * 「通知测试」那处的动作分支代码是**原封不动搬过来的**（固定宽度 / 文案 / 冷却逐项未改）。
 *
 * ## 入口形态为什么把可点区域从按钮扩大到整行
 *
 * 右箭头不是按钮，它只是"这行可以点"的**视觉指示**——用户看到箭头的预期就是
 * "点这一行进下一级"，如果只让箭头本身响应点击，点在标题或说明上毫无反应，
 * 与预期相反。因此 [TrailingStyle.Navigation] 下给整行挂 `clickable`
 * （与「我的」页 `MineRow` / 「撤回隐私同意」行的写法一致）。
 *
 * `enabled = false` 时 `clickable(enabled = false)` 直接吃掉点击，
 * 配合左侧文字区的 [DISABLED_CONTENT_ALPHA] 置灰，"总开关关掉 → 整行点不动"这条
 * 门控口径与另外两种 Row 完全一致（没有额外兜底逻辑，也不需要）。
 *
 * @param trailing 右侧形态，见 [TrailingStyle]。
 * @param buttonText 仅 [TrailingStyle.Button] 使用：按钮文案。
 *   [TrailingStyle.Navigation] 下忽略（箭头不带文字）。
 */
@Composable
private fun DeveloperActionRow(
    title: String,
    description: String,
    trailing: TrailingStyle,
    enabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    buttonText: String = "",
    /**
     * 入口行右侧的**当前值文字**（2026-10-23 需求 5）。
     *
     * 参考截图：「游戏颜色模式 ——— 跟随系统 ›」——当前档位用主题色（红/主题绿）
     * 写在箭头左边。null = 不显示（多数入口行没有"当前值"这个概念）。
     */
    valueText: String? = null,
    /** 仅 [TrailingStyle.NavigationWithSwitch] 使用：入口右侧那个总开关的值。 */
    switchChecked: Boolean = false,
    /** 仅 [TrailingStyle.NavigationWithSwitch] 使用：拨动总开关。 */
    onSwitchChange: (Boolean) -> Unit = {}
) {
    // 整行可点只在入口形态下生效；动作形态下点击归右侧按钮自己管，
    // 给整行也挂 clickable 会让"点标题"和"点按钮"触发同一个动作，
    // 把可点区域扩大到用户不认为能点的地方，反而容易误触。
    val rowClickable = (trailing == TrailingStyle.Navigation ||
        trailing == TrailingStyle.NavigationWithSwitch) && enabled
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 注意顺序：clickable 必须在 padding 之前，否则点击区域只剩内边距那一圈，
            // 文字区（weight(1f)）反而不响应——这正是"箭头可点、标题不可点"的成因。
            .then(
                if (rowClickable) {
                    Modifier.clickable(onClick = onAction)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            // 空串 = 不要副标题（理由同 [DeveloperSwitchRow] 的同一处条件渲染）。
            if (description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
        // 当前值（可选）：箭头左边的主题色文字。
        valueText?.let {
            Text(
                text = it,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                // 整行置灰时当前值也跟着调暗（否则会出现"一行灰字配一个亮绿值"）。
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA
                ),
                maxLines = 1
            )
            Spacer(Modifier.width(6.dp))
        }
        when (trailing) {
            // ---- 动作形态（通知测试）-----------------------------------------
            //
            // 固定宽度 / 文案 / 冷却逻辑全部保持原样，本轮一行代码都没改。
            TrailingStyle.Button -> OutlinedButton(
                onClick = onAction,
                enabled = enabled,
                // 需求（v1.1.68）：「通知测试」的按钮要**固定大小**，不随文本变化而改变。
                //
                // 之所以要显式定宽而不是只缩短文案：文案再短也仍是「三个字 vs 两个字符」，
                // 布局上宽度仍会差几十 px，冷却一进一出按钮就会抖。
                // 定宽后无论显示「发送通知」还是「9s」，按钮外框**完全不动**。
                //
                // 取 92dp（2026-10-27 用户要求「宽度和高度适当减小」，原 104dp）：
                // 「发送通知」四个汉字 @ labelLarge ≈56dp + 左右内边距各 8dp ≈72dp，
                // 92dp 仍能完整容纳，同时不再把左侧说明文字挤得那么窄。
                // 高度同步收到 [ACTION_BUTTON_HEIGHT]（32dp，原为 Material 默认 40dp）。
                // ⚠️ **字号一个字都没改**：仍是 OutlinedButton 默认的 `labelLarge`，
                // 用户要的是按钮框变小，不是字变小。
                modifier = Modifier
                    .width(ACTION_BUTTON_WIDTH)
                    .height(ACTION_BUTTON_HEIGHT),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                // 固定宽度下文字可能溢出/换行，统一单行显示并居中。
                Text(text = buttonText, maxLines = 1)
            }

            // ---- 入口形态（登录测试 / 图标更改 / 设备信息查询）---------------
            //
            // 规格逐项照「我的」页（`MyScreen.ProfileCard` / `MineRow`）：
            // 默认 Icon 尺寸 24dp、`onSurfaceVariant`、`contentDescription = null`。
            //
            // - 用 **AutoMirrored** 版本：项目既有纪律（ArrowBack / Send / KeyboardArrowRight
            //   一律用 `Icons.AutoMirrored.Filled.*`），RTL 语言下图标自动镜像；
            //   非 AutoMirrored 的 `Icons.Filled.KeyboardArrowRight` 已被标记 deprecated。
            // - `contentDescription = null`：这一行是**纯装饰**，读屏时旁边标题已经说明了
            //   这是什么行，再念一句"进入"只会重复；「我的」页与「撤回隐私同意」行也是 null。
            // - 不加 `.size()` / 不加 tint 之外的修饰：保持与「我的」页视觉一致，
            //   图标颜色跟随主题（浅色 / 深色两套下 `onSurfaceVariant` 都有定义）。
            TrailingStyle.Navigation -> Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 入口 + 总开关（2026-10-22 需求 10）--------------------------
            //
            // 形态照用户给的参考图：「标题 / 说明 —— 关 [开关] ›」，
            // 箭头**左侧**是这一项的总开关，箭头本身仍表示"这行还能点进去"。
            //
            // 为什么把总开关放在入口行上而不是只在二级页里：用户要的是
            // "一眼能看到这一项现在开没开、并且能直接关掉"，进去再关多一步。
            // 拨开关与点整行是两个独立动作——`Switch` 自己会消费掉点击，
            // 所以不会出现"拨开关顺带进了二级页"。
            TrailingStyle.NavigationWithSwitch -> {
                Text(
                    text = if (switchChecked) "开" else "关",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = switchChecked,
                    onCheckedChange = onSwitchChange,
                    enabled = enabled
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * [DeveloperActionRow] 右侧的两种形态。
 *
 * 用一个枚举而不是两个 Boolean（或"箭头模式 / 按钮模式"两个组件）：
 * 布尔参数在调用处读作 `asNavigation = true`，将来要加第三种形态（例如"文字链"）
 * 时布尔量不够用，而枚举只是多一个分支；并且 `when` 在枚举上是穷尽的，
 * 新增分支忘写时编译器直接报错，布尔量没有这种保护。
 */
private enum class TrailingStyle {
    /** 右侧是 [OutlinedButton]：按一下当场生效（「通知测试」）。 */
    Button,

    /** 右侧是右箭头：整行可点，进二级页（三个入口行）。 */
    Navigation,

    /**
     * 右侧是「总开关 + 右箭头」（2026-10-22 需求 10）。
     *
     * 用于「游戏标题栏显示」「游戏开屏显示」两行：开关直接控制这一项的总开关，
     * 箭头表示还能点进去细调各游戏的子开关。
     */
    NavigationWithSwitch
}

/**
 * 二级界面的**进出场动画**容器（登录测试 / 图标更改 / 设备信息查询三处共用）。
 *
 * 需求原文：「开发者选项进二级界面增加动画」；本轮追加：**进出都要连贯、不得出现白屏**
 * （真机反馈：进入时整屏白一下、返回时瞬间硬切）。
 *
 * ## 为什么抽成一个组件，而不是在三处各写一份 `AnimatedVisibility`
 *
 * 本页已经吃过「三份几乎一样的 Row 各自漂移」的亏（见 [DeveloperSwitchRow] 与
 * [DeveloperActionRow] 的 KDoc）。各二级页的动画参数（方向、时长、缓动）本来就是
 * 同一套口径，复制三份之后，下一次「把时长调快一点」的人只会改到他正在看的那一份，
 * 于是三个页面速度不同——而这种不一致**没有任何编译错误**，只能靠肉眼在真机上发现。
 * 这里是同一条纪律的延续：一份实现，三处调用。
 *
 * ## 为什么改成「状态常驻 + AnimatedVisibility 包内容」，而不是旧的「先 false 再 true」
 *
 * 旧实现是「二级页分支出现时才组合本容器，容器内部 false→true 跳变触发 enter」。
 * 它有两个真机实测的问题：
 *
 * 1. **进入时白屏**：分支切换（提前 return）让主页面 `Scaffold` 与本容器**同帧**换血，
 *    而容器内的 `AnimatedVisibility` 要等下一帧才把 visible 翻 true、开始滑入——
 *    在那之前的若干帧里，屏幕上只有垫底色（且旧实现的 `fadeIn` 让内容层半透明，
 *    整个动画期间都透着这层底色），观感就是「白了一下才滑进来」。
 * 2. **返回时硬切**：退出完全没有动画（旧实现 `exit = ExitTransition.None`），
 *    二级页当帧消失、主页面当帧出现，不连贯。
 *
 * 本轮把结构反过来：**本容器常驻**在主页面内容里（三个布尔状态任何一个为 true
 * 都让二级页内容出现），进入与退出都由 `AnimatedVisibility` 自己播——
 * 进入从右滑入（无淡入，见下）、退出向右滑出。分支切换仍发生在宿主，但二级页
 * 的出现 / 消失从此有了完整的动画帧，不再有"空档帧"。
 *
 * ## 为什么进出场都**不带淡入淡出**（与 `TabNavHost.pushEnter` 同一口径）
 *
 * 两层（二级页 / 垫底色）同时变透明时，中间帧的合成不透明度不是简单相加，
 * 半透明期间会透出下层正在切换的内容——这正是旧版「进二级页白屏 / 闪一下」的根因之一。
 * 纯位移方案里二级页始终**完全不透明**：滑到哪儿盖到哪儿，身后永远是垫底色，
 * 没有任何可透的缝隙。`TabNavHost.pushEnter` 的 KDoc 记录过同一个结论。
 *
 * ## 滑入距离为什么是整屏宽度，起点为什么在屏幕外
 *
 * 取 `initialOffsetX = { fullWidth -> fullWidth }`：起点正好在屏幕右边缘之外，
 * 页面是**整块**从"看不见的右侧"推到位的，与 `TabNavHost.pushEnter` 同参数。
 * 退出 `targetOffsetX = { fullWidth -> fullWidth }` 对称地滑回右边缘外，
 * 进出同轴，动画中途反向也没有方向突变。
 *
 * ## 动画期间「身后」是什么（为什么不再需要整屏遮罩）
 *
 * 本轮起宿主 Scaffold **常驻组合**（不再提前 return），所以二级页滑入时
 * 未盖到的区域露出的是**一级页本体**——这正是 `TabNavHost.pushEnter` 的
 * 标准推入观感（旧页原地不动、新页整块逐步盖上），且两层都不透明，
 * 没有任何可透的缝隙。
 *
 * 动画层**内部**仍垫一层 `colorScheme.background` 实心底：它随页面一起滑动，
 * 保证滑入的这一块**自身**始终不透明——即使某个二级页内容将来带了透明区域
 * （Scaffold 的 containerColor 被改透明之类），透出的也是同色底而不是别的东西。
 * 取 `colorScheme.background` 不是新颜色，它就是 `Scaffold` 默认容器色，
 * 与二级页自身背景同色同深浅，看不到接缝。
 *
 * @param visible 各二级页布尔状态的「或」：任一为 true 就显示二级页并播进场动画。
 *   注意**不能**在这里反查是哪一页——那是宿主分支的事，本容器只管动画。
 * @param content 二级页内容（整页 `Scaffold`）。
 */
@Composable
private fun SecondaryPageEnterHost(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    // 见上方 KDoc：动画层内部垫一层不透明底色，随页面一起滑动。
    // `fillMaxSize()` 是因为二级页本来就整屏覆盖（内有 `fillMaxSize` 的 Scaffold）。
    //
    // ⚠️ 整块（底色 + 内容）包在 `AnimatedVisibility(visible)` 里：
    // visible = false 期间底色与内容都不参与组合（比 alpha = 0f 常驻省得多）；
    // 退出动画播完才整体移出组合，宿主分支当帧切换也不会留下孤儿图层。
    AnimatedVisibility(
        visible = visible,
        enter = secondaryPageEnter(),
        exit = secondaryPageExit()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}

/**
 * 各二级页统一的进入动画：从屏幕右侧整块滑入（**不带淡入**）。
 *
 * 用 `tween`（默认 `FastOutSlowInEasing`）而不是 `spring`：
 * 页面推入是**有明确终点**的一次性位移，弹簧的回弹在这里只会变成"滑过头再退回来"，
 * 与微信式推入的干脆手感相反；`tween` 单调推进、到点即停，也与
 * `TabNavHost` 的推入动画同一族缓动。
 *
 * ⚠️ 刻意**不带** `fadeIn`：淡入的中间帧是半透明的，会透出垫底色与下层内容，
 * 正是真机「进二级页白屏 / 闪一下」的成因之一（教训同 `TabNavHost.pushEnter`）。
 */
private fun secondaryPageEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(SECONDARY_ENTER_MS),
        initialOffsetX = { fullWidth -> fullWidth }
    )

/**
 * 各二级页统一的退出动画：整块向右滑出（与进入严格配对）。
 *
 * 需求（本轮）：「进出二级界面动画连贯」——返回不能是旧实现那种当帧硬切。
 * 方向取**沿原路滑回**：进入从右来、退出回右去，进出同轴，
 * 动画中途按返回再进也不会出现方向突变。
 */
private fun secondaryPageExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(SECONDARY_ENTER_MS),
        targetOffsetX = { fullWidth -> fullWidth }
    )

/**
 * 「开屏显示时长」行（2026-10-23 需求 6 / 8 重排版式）。
 *
 * ## 版式（与上一版的差别）
 *
 * ```
 * 开屏「Hello World」显示时长            ← 只有标题，**没有**说明小字（需求 6）
 * [========|===========]  0.9 秒        ← 读数挪到滑动条**右端**（需求 6）
 * ☑ 随机浮动                    恢复默认  ← 勾选框在「恢复默认」左侧（需求 8）
 * ```
 *
 * 上一版是「标题 + 说明」在上面、读数在最右、按钮单独一行右对齐；读数离滑块很远，
 * 拖动时眼睛要来回找。现在读数紧贴滑块右端，勾选框与按钮同一行，纵向更省一截。
 *
 * ## 档位为什么是 0.1 秒
 *
 * 需求只说「0~10 秒间调节」，档位由实现定。选 **0.1 秒** 的唯一原因，
 * 是**默认值 900ms 必须能被档位精确表示**：取 0.5 秒时 900 不是 500 的整数倍，
 * `Slider` 会把滑块位置吸附到 1.0 秒档，出现「滑块在 1.0、右边写着 0.9」的自相矛盾
 * （`SliderState.setValue` 内部会 `snapValueToTick`）。
 *
 * ## 置灰时的表现
 *
 * 不满足门控时（总开关关 / 性能测试没开）：滑块、勾选框、按钮都不可点，
 * **但当前值与勾选状态照旧显示**（只调暗）——「值还在、只是暂不生效」，
 * 与 [DeveloperSwitchRow] 的 `checked` / `enabled` 之分同一口径。
 *
 * @param jitterEnabled 是否开启「随机浮动」（实际时长 = 设定值 ±15%）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplashHoldRow(
    holdMs: Int,
    jitterEnabled: Boolean,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onJitterChange: (Boolean) -> Unit,
    onRestoreDefault: () -> Unit,
    modifier: Modifier = Modifier,
    // 2026-10-25 需求：随机浮动「第一次勾选弹介绍、之后改用工具提示」的两个新参数。
    /** 「随机浮动」的介绍弹窗是否已经看过（决定首次勾选要不要弹 AlertDialog）。 */
    jitterIntroShown: Boolean = true,
    /** 把「已看过介绍」写盘的回调（弹窗里点「开启」时调用）。 */
    onJitterIntroShown: () -> Unit = {}
) {
    // 首次勾选「随机浮动」时的介绍弹窗（2026-10-25 需求）。
    // 只在「还没勾上 + 从没看过介绍」时弹出；弹窗里点「开启」才真的勾上并记「已看过」，
    // 之后再次开关只保留长按工具提示（见下方 TooltipBox）。
    var showJitterIntro by remember { mutableStateOf(false) }
    if (showJitterIntro) {
        AlertDialog(
            onDismissRequest = { showJitterIntro = false },
            title = { Text("随机浮动是什么？") },
            text = {
                Text(
                    "开启后，每次启动的开屏时长会在你设定的基础上**上下浮动 15%**" +
                        "（例如设定 3.0 秒，实际会在 2.55~3.45 秒之间随机）。\n\n" +
                        "时长下限仍不小于 0（不会因此跳过开屏）。可以随时回来关闭。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showJitterIntro = false
                        onJitterIntroShown()
                        onJitterChange(true)
                    }
                ) {
                    Text("开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJitterIntro = false }) {
                    Text("暂不开启")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = SPLASH_HOLD_TITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.strongTextColor
                .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        )

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSlider(
                value = holdMs.toFloat(),
                onValueChange = { raw ->
                    // 吸附到 100ms 档：触摸拖动给的是连续值，不吸附会存下"2371"这类噪声；
                    // `roundToInt` 而不是 `toInt`（截断会让"往上拖一点"经常没反应）。
                    val snapped = (raw / SettingsRepository.SPLASH_HOLD_STEP_MS).roundToInt() *
                        SettingsRepository.SPLASH_HOLD_STEP_MS
                    onValueChange(
                        snapped.coerceIn(
                            SettingsRepository.MIN_SPLASH_HOLD_MS,
                            SettingsRepository.MAX_SPLASH_HOLD_MS
                        )
                    )
                },
                valueRange = SettingsRepository.MIN_SPLASH_HOLD_MS.toFloat()..
                    SettingsRepository.MAX_SPLASH_HOLD_MS.toFloat(),
                // 档数 = 0..5000 共 51 档 → steps = 49（见 SPLASH_HOLD_STEPS，按常量自动算）。
                steps = SPLASH_HOLD_STEPS,
                enabled = enabled,
                fraction = (holdMs - SettingsRepository.MIN_SPLASH_HOLD_MS).toFloat() /
                    (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS),
                // 需求 6：读数在滑动条**右端**，因此滑块要 weight 而不是 fillMaxWidth。
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            // 当前值。2026-10-25 需求「文本位置固定，不会时宽时窄」：
            // 读数在「0.5 秒 / 未开启 / 3.0 秒」之间宽度不等，不定宽时会把滑块挤得左右移动。
            // 给它一个固定的最小宽度并右对齐，任何档位下滑块宽度都不变。
            Text(
                text = formatHoldSeconds(holdMs),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
                color = MaterialTheme.strongTextColor
                    .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
                modifier = Modifier.widthIn(min = 64.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 随机浮动（需求 8；2026-10-25 改版交互）--------------------
            //
            // 勾上后每次开屏时长 = 设定值 ±15%（实现见
            // `DeveloperOptionsRepository.splashHoldDurationMs`）。
            // 2026-10-25 起的交互分两条：
            // - **首次**勾选（没看过介绍）：弹 [AlertDialog] 讲清"±15%"是什么，
            //   点「开启」才真的勾上（同时记下"已看过"）；
            // - 之后再次开关：不弹窗，改用**长按工具提示**（TooltipBox）提示含义。
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip {
                        Text("开启后每次开屏时长 = 设定值 ±15%")
                    }
                },
                state = rememberTooltipState()
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) {
                            if (!jitterEnabled && !jitterIntroShown) {
                                // 第一次勾选：先弹介绍，等用户在弹窗里确认。
                                showJitterIntro = true
                            } else {
                                onJitterChange(!jitterEnabled)
                            }
                        }
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = jitterEnabled,
                        onCheckedChange = onJitterChange,
                        enabled = enabled
                    )
                    Text(
                        text = "随机浮动",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = if (enabled) 0.9f else DISABLED_CONTENT_ALPHA)
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRestoreDefault, enabled = enabled) {
                Text("恢复默认")
            }
        }
    }
}

/**
 * 动画时长滑流行（2026-10-25 需求：界面切换 / 导航栏切换两档时长可调）。
 *
 * 与 [SplashHoldRow] 同一套版式（标题 + 滑块 + 右端定宽读数 + 恢复默认），
 * 但档距是 [SettingsRepository.ANIM_STEP_MS]（20ms）、区间 100~600ms。
 * 读数格式化为整数毫秒（`280 ms`），定宽右对齐——拖动时滑块宽度恒定，
 * 与开屏时长行的处理同一条理由。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimDurationRow(
    title: String,
    ms: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onRestoreDefault: () -> Unit,
    modifier: Modifier = Modifier,
    /** 滑块区间（毫秒）。默认是动画时长的公共区间；昵称检测那行另传自己的区间。 */
    minMs: Int = SettingsRepository.MIN_ANIM_MS,
    maxMs: Int = SettingsRepository.MAX_ANIM_MS,
    stepMs: Int = SettingsRepository.ANIM_STEP_MS
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.strongTextColor
                .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        )

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSlider(
                value = ms.coerceIn(minMs, maxMs).toFloat(),
                onValueChange = { raw ->
                    val snapped = (raw / stepMs).roundToInt() * stepMs
                    onValueChange(snapped.coerceIn(minMs, maxMs))
                },
                valueRange = minMs.toFloat()..maxMs.toFloat(),
                steps = (maxMs - minMs) / stepMs - 1,
                enabled = enabled,
                fraction = (ms - minMs).toFloat() / (maxMs - minMs).toFloat(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$ms ms",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
                color = MaterialTheme.strongTextColor
                    .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA),
                modifier = Modifier.widthIn(min = 64.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRestoreDefault, enabled = enabled) {
                Text("恢复默认")
            }
        }
    }
}

/**
 * 「开发者选项 → 登录测试」二级页（本轮新增）。
 *
 * 内容 = 改造前一级页「登录测试」板块里的**原来那两行**，一个字都没改：
 * 1. [DeveloperSwitchRow]「验证码测试」——门控只有总开关，`checked` 传**原始值**；
 * 2. 分隔线 + [DeveloperActionRow]「通知测试」——10 秒冷却 + 真发一条通知，
 *    右侧保持**动作按钮**（[TrailingStyle.Button]），**不是**右箭头。
 *
 * ## 为什么「通知测试」这一行不能跟着改成右箭头
 *
 * 本轮一级页三个入口行的右侧从「进入」按钮改成了右箭头（用户需求：像"我的"页那样），
 * 但箭头是**导航**的视觉语言——它表示"点这一行进下一级"。
 * 「通知测试」没有下一级：它按一下**当场发一条通知**，是一个立即生效的动作，
 * 而且带 10 秒冷却（文案在「发送通知」与「9s」之间切换）。
 * 把它也画成箭头会变成"这行能进下一级"的错误暗示，用户点了没反应会更困惑。
 * 因此本页这一处显式用 [TrailingStyle.Button]，固定宽度与冷却逻辑原样保留。
 *
 * ## 为什么这一页能复用一级页那几个 private 组件
 *
 * 它和 [DeveloperOptionsScreen] **写在同一个文件里**，因此 [DeveloperSwitchRow] /
 * [DeveloperActionRow] / [ACTION_BUTTON_WIDTH] 这几个 private 声明直接可用。
 * 换成单独一个文件就必须把它们提升成 internal（会污染 `ui.lab` 包的顶层命名空间，
 * 而本包已有 9 个文件、顶层重名会直接编译失败），代价比"多一个文件"大得多。
 *
 * ## 状态放哪：两个子项的状态**都不在这里**
 *
 * - 「验证码测试」的开关值来自宿主的 `repository.codeTestSwitch`，通过参数传进来、
 *   改动再经 [onCodeTestChange] 回写——本页不持有副本。理由见文件顶部 KDoc 的
 *   「原始值 vs 是否生效」一节：拿"生效值"当 `checked` 会让用户以为设置被清空；
 * - 「通知测试」的 10 秒冷却**是本页自己的状态**（`notifyCooldown` + [LaunchedEffect]）。
 *   它必须在这里，不能上提给宿主：`LaunchedEffect` 的协程生命周期跟着**它所在的
 *   可组合**走，而宿主在二级页覆盖期间根本不参与组合——留在宿主那里，计时会被取消，
 *   冷却会卡在剩余秒数上永远不动。
 *
 * ## 版式
 *
 * 照 `IconPickerScreen` 的二级页：`Scaffold` + `TopAppBar("登录测试")` + 返回箭头 +
 * 一张 `Card`（两行之间一条分隔线），系统返回键由 [BackHandler] 接住。
 * 挂 `verticalScroll` 只作兜底：内容正常远不到一屏（挂了也不产生任何位移），
 * 但在系统字体被调到最大、或将来往这张卡片里再加一行时能滚到底。
 *
 * @param masterEnabled 总开关。两个子项都只受它门控（口径与改造前逐字相同）。
 * @param codeTestSwitch 验证码测试的**原始值**，仅用于回显开关位置。
 * @param onCodeTestChange 开关被拨动时回写仓库。
 * @param onBack 点返回箭头 / 按系统返回键时回调。
 * @param onMessage 页面级一次性提示（通知发出成功 / 失败）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginTestScreen(
    masterEnabled: Boolean,
    codeTestSwitch: Boolean,
    onCodeTestChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current

    // 「通知测试」要真的发通知，所以需要通知工具。
    // 与一级页同一写法：必须 applicationContext + 进程内单例容器
    // （`LocalContext.current` 通常是 Activity，直接 as 会 ClassCastException）。
    val notificationHelper = remember(context) {
        context.appContainer.notificationHelper
    }

    // 「昵称检测转圈时长」（2026-10-25 需求）：注册页昵称输入后的查重转圈时长，
    // 默认 200ms、可在本页 100~2000ms 间调。读原始值，写经仓库夹取。
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    val nicknameCheckMs by repository.nicknameCheckMsSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_NICKNAME_CHECK_MS)
    val nicknameScope = rememberCoroutineScope()

    // 「模拟·验证码测试」开启前的二次确认（2026-10-30 用户要求）。
    var showCodeTestConfirm by remember { mutableStateOf(false) }
    if (showCodeTestConfirm) {
        AlertDialog(
            onDismissRequest = { showCodeTestConfirm = false },
            title = { Text("开启模拟登录？") },
            text = { Text("开启后将使用模拟登录，无法使用同步等功能。") },
            confirmButton = {
                TextButton(onClick = {
                    showCodeTestConfirm = false
                    onCodeTestChange(true)
                }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showCodeTestConfirm = false }) { Text("取消") }
            }
        )
    }

    // 「通知测试」的冷却剩余秒数（需求：按钮有 10s 间隔）。
    // 归零之前按钮置灰并显示还剩几秒，避免连点刷屏。
    // 递减的 [LaunchedEffect] 与状态同处一页，本页被覆盖 / 销毁时协程一起停，
    // 不会留下"没人回收的计时器"（行为与改造前完全一致）。
    var notifyCooldown by remember { mutableIntStateOf(0) }
    LaunchedEffect(notifyCooldown) {
        if (notifyCooldown > 0) {
            delay(1_000)
            notifyCooldown -= 1
        }
    }

    // 系统返回键等同「返回开发者选项」，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LOGIN_TEST_TITLE) },
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
                // 与一级页同一套内边距（16dp / 12dp）：两页之间切换时卡片位置不跳。
                //
                // 字体缩放到最大时本页那两行可能接近一屏高，加 `verticalScroll` 兜底：
                // 内容比视口短时它不产生任何位移，长了才生效。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    // 2026-10-30：标题加「模拟·」前缀，让用户在列表里就知道这是假登录。
                    title = "模拟·验证码测试",
                    description = CODE_TEST_DESCRIPTION,
                    // ★ 原始值（不是与过总开关的 `codeTestEnabled`），理由见文件顶部 KDoc。
                    checked = codeTestSwitch,
                    // 门控只有总开关一个（与改造前逐字相同）。
                    enabled = masterEnabled,
                    // 2026-10-30 用户要求：**开启**必须先二次确认（关闭不拦）。
                    // 打开后应用会走本地模拟登录，云端同步/多人功能全部不可用，
                    // 这是"看起来能用其实没数据"的那种坑，必须让人点一下确认。
                    onCheckedChange = { want ->
                        if (want) showCodeTestConfirm = true else onCodeTestChange(false)
                    }
                )

                // ---- 通知测试（原样搬来，行为不变）--------------------------
                //
                // 与上面那一行的关系：验证码测试是「开关 + 后续由登录页触发」，
                // 这个是「当场按一下、马上验证」——用来单独确认**通知权限到底有没有开**。
                //
                // ⚠️ 本轮一级页三个入口行右侧都改成了右箭头，但**这一行保持是按钮**
                // （[TrailingStyle.Button]，显式写出形态而不是依赖默认值，避免下一个人
                // 误以为"入口行都改了，这行也该跟着改"）：
                // 它按一下当场发通知，没有下一级页面，画成箭头会给出错误的导航暗示。
                // 固定宽度、文案切换、冷却逻辑三项全部原样，见下方逐条注释。
                //
                // 分隔线与别的设置行同宽（左侧缩进 16dp，和文字对齐）。
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
                DeveloperActionRow(
                    title = "通知测试",
                    description = NOTIFY_TEST_DESCRIPTION,
                    // 右侧仍是动作按钮（理由见上方注释）：固定宽度、固定文案规则不变。
                    trailing = TrailingStyle.Button,
                    // 需求（v1.1.68）：按钮**固定宽度**，不随文案长度变化。
                    //
                    // 原先文案是「发送通知」↔「Ns 后可再测」，两者宽度差很多，
                    // 冷却期间按钮会**抽一下**（宽度跳动），看着像布局坏了。
                    // 现在文案收敛成「发送通知」与「As」（A = 实际剩余秒数），
                    // 两者都短，再配合 `DeveloperActionRow` 的固定 `Modifier.width`，
                    // 冷却前后按钮尺寸完全一致。
                    buttonText = if (notifyCooldown > 0) {
                        "${notifyCooldown}s"
                    } else {
                        "发送通知"
                    },
                    // 总开关关着时本板块整体不生效，按钮同样置灰（与子开关一致的口径）。
                    enabled = masterEnabled && notifyCooldown == 0,
                    onAction = {
                        val posted = notificationHelper.notifyTestHelloWorld()
                        // 无论成功失败都进冷却：成功是防连点刷屏，
                        // 失败（权限没开）也进冷却，避免用户狂点却一直只看到同一句提示。
                        notifyCooldown = NOTIFY_TEST_COOLDOWN_SECONDS
                        onMessage(
                            if (posted) {
                                "已发送测试通知，请下拉通知栏查看"
                            } else {
                                "通知权限未开启，请到系统设置里允许后再试"
                            }
                        )
                    }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 昵称检测转圈时长（2026-10-25 需求）------------------------
                //
                // 注册页昵称输入后的「查重」转圈时长。默认 200ms（本轮从写死的
                // 1000ms 收紧）；喜欢旧观感的可以在这里调回去。
                AnimDurationRow(
                    title = NICKNAME_CHECK_TITLE,
                    ms = nicknameCheckMs,
                    enabled = masterEnabled,
                    onValueChange = { nicknameScope.launch { repository.setNicknameCheckMs(it) } },
                    onRestoreDefault = {
                        nicknameScope.launch {
                            repository.setNicknameCheckMs(
                                SettingsRepository.DEFAULT_NICKNAME_CHECK_MS
                            )
                        }
                    },
                    minMs = SettingsRepository.MIN_NICKNAME_CHECK_MS,
                    maxMs = SettingsRepository.MAX_NICKNAME_CHECK_MS,
                    stepMs = SettingsRepository.NICKNAME_CHECK_STEP_MS
                )
            }

            // 页脚说明已删除（2026-10-30 用户要求「删除底部文字」）：
            // 「只受总开关门控」这件事从置灰状态就能看出来，不必再写一遍。
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 把毫秒格式化成「N.N 秒」；0 → 「未开启」（2026-10-25 需求）。
 *
 * 一律保留一位小数（`3.0 秒` 而不是 `3 秒`）：档位是 0.1 秒（见 `SPLASH_HOLD_STEPS`），
 * 位数固定时数字宽度不会跳动——`3 秒` 与 `10 秒` 之间来回拖会让右侧文字左右抖动，很难看。
 *
 * 0 档显示「未开启」而不是「0.0 秒」：0 秒的语义就是"不播开屏"，
 * 写成 0.0 秒用户还得自己换算；「未开启」直接说清了结果。
 *
 * 用整数运算（`ms / 1000` 与 `ms % 1000 / 100`）而不是浮点除法 + 格式化：
 * 浮点在这里没有任何精度收益，却要多引入一个 `String.format` 的 Locale 问题
 * （某些区域会把小数点写成逗号），这是个显示给用户看的读数，稳定比花哨重要。
 */
private fun formatHoldSeconds(ms: Int): String {
    if (ms <= SettingsRepository.MIN_SPLASH_HOLD_MS) return "未开启"
    val seconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "$seconds.$tenths 秒"
}

/**
 * 二级界面**进入动画**的时长（登录测试 / 图标更改 / 设备信息查询三个入口共用）。
 *
 * 取 260ms，依据是两条：
 * 1. 与全局二级页推入的口径 `TabNavHost.NAV_DURATION_MS`（280ms）**同量级**——
 *    本项目页面切换的手感是统一的，本页的各二级页虽然不是导航目的地，
 *    但在用户眼里就是「从开发者选项进下一页」，速度差太多会被当成另一套 App；
 * 2. 略短于它的 280ms：这些二级页是**就地覆盖**（主页面不再铺一层在下面、
 *    只剩垫底色，见 [SecondaryPageEnterHost] 的 KDoc），
 *    画面里只有一层在位移，同样的位移距离感觉**更快**，短一点反而与 280ms 的
 *    推入观感对齐。
 *
 * ⚠️ **刻意不复用** `NAV_DURATION_MS`：那条常量是 `TabNavHost` 的**文件级 private**
 * （拿不到），而且它的语义是"所有导航目的地的统一口径"，一旦有人为了别的地方
 * 调整它，本页这些二级页会跟着变；本页要单独调速度时只动这一处即可。
 * 这与 `TabNavHost.SNAKE_TRANSITION_MS`（贪吃蛇专属时长）是同一个取舍。
 *
 * 落在 220~280ms 区间内也符合经验值：短于 200ms 会像没动画，长于 300ms 会让人等。
 */
private const val SECONDARY_ENTER_MS = 260

/**
 * 子开关失效（总开关关闭）时，左侧文字区的不透明度。
 *
 * 取 0.38f：这是 Material 规范里"禁用态内容"的标准系数，Material3 的
 * `Switch` / `Checkbox` 内部调淡用的也是同一档。跟它对齐之后，
 * 置灰的文字与右边缘已经自动置灰的开关**深浅一致**，不会出现
 * "文字比开关更淡"或"开关淡了文字还是黑的"这种割裂感。
 *
 * 只乘在主题色上（`Modifier.alpha` 是合成期系数），因此**不产生新的颜色值**，
 * 深色模式下自动变成"深色卡片上的浅灰字"。
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 三个板块之间的垂直间距。
 *
 * 取 20.dp：必须明显大于卡片内部 14.dp 的行内边距，否则"板块标题 + 卡片"这三组
 * 会挤成一整张长表，看不出是三个独立板块；但也不能太大，否则在矮屏上要滚动才能看全
 * 三个板块，失去"一屏扫完"的直观。
 */
private val SECTION_SPACING = 20.dp

/**
 * 「验证码测试」的说明文案。
 *
 * 写得比别处长，是因为它是本页唯一有真实行为的一项：不把"随机生成六位数字 → 记录 →
 * 发通知 → 必须一致才能登录"这条链路说清，用户会以为它和别的开关一样只是动画。
 * 用「发送验证码」的全角引号，与登录 / 注册页上的按钮文案保持字面一致。
 *
 * ⚠️ 本常量留在**本文件**而不是搬去 `UiInspectorScreen.kt`：它是 [LoginTestScreen]
 * （就在下面、同属本文件）的文案，搬走反而要跨文件引用一个 private 常量。
 * 同一个顶层 private 常量在两个文件里各写一份会编译失败，所以只有一份、放在用它的人旁边。
 */
private const val CODE_TEST_DESCRIPTION =
    "用本地随机码代替真实短信"

/**
 * 「开屏显示时长」滑块的标题。
 *
 * 用「显示时长」而不是「额外停留时长」：用户看到的现象是「Hello World 停在屏幕上的时间」，
 * 而"额外"这个词在界面上没有对应物（它只是实现里相对"最短展示"的说法）。
 */
private const val SPLASH_HOLD_TITLE = "开屏「Hello World」显示时长"

/**
 * 「开屏显示时长」滑块的说明文案。
 *
 * 三个信息缺一不可：
 * 1. 它管的是**哪一屏**（开屏的 Hello World，不是主界面加载）；
 * 2. 它需要**性能测试开关也打开**（否则用户会奇怪为什么拖不动）；
 * 3. 默认值是多少（「恢复默认」会回到哪）。
 */
private const val SPLASH_HOLD_DESCRIPTION =
    "开屏停留 0~5 秒，0 为不播"

// ---- 2026-10-25 本轮新增文案 --------------------------------------------------

/** 「实验室功能开屏」开关的标题。 */
private const val LAB_FEATURE_SPLASH_TITLE = "实验室功能开屏"

/** 「实验室功能开屏」开关的说明。 */
private const val LAB_FEATURE_SPLASH_DESCRIPTION =
    "进入实验室各功能页先播开屏"

/** 「界面切换动画时长」滑块标题（二级页推入 / 返回）。 */
private const val NAV_ANIM_TITLE = "界面切换动画时长"

/** 「导航栏切换动画时长」滑块标题（底栏入口切换）。 */
private const val TAB_SWITCH_ANIM_TITLE = "导航栏切换动画时长"

/** 「撤回退出转圈」回退开关的标题。 */
private const val REVOKE_EXIT_SPINNER_TITLE = "撤回退出转圈"

/** 「撤回退出转圈」回退开关的说明。 */
private const val REVOKE_EXIT_SPINNER_DESCRIPTION =
    "退出前转圈约 0.7 秒"

/**
 * 旧的「检查更新应用内打开」开关标题与说明（2026-10-25 引入）。
 *
 * ⚠️ 本轮（需求 2）起**不再有 UI 入口**：那一行已换成三选一的「检查更新按钮功能」。
 * 常量与仓库里的读写方法先留着——它们是"历史设置项"的文档，
 * 将来若要恢复那一行，文案不用再写一遍。Kotlin 对未使用的 private 常量只报 warning，
 * 不影响构建。
 */
private const val CHECK_UPDATE_IN_APP_TITLE = "检查更新应用内打开"

/** 见 [CHECK_UPDATE_IN_APP_TITLE]。 */
private const val CHECK_UPDATE_IN_APP_DESCRIPTION =
    "检查更新在应用内打开"

/** 「昵称检测转圈时长」滑块的标题（登录测试二级页）。 */
private const val NICKNAME_CHECK_TITLE = "昵称检测转圈时长"

/**
 * 滑块的 `steps` 参数。
 *
 * ## 为什么是「算出来的」而不是写死一个数字
 *
 * `steps` 的语义是「在 `valueRange` 两端之间**插入**几个离散点」，
 * 因此总档数 = `steps + 2`。
 *
 * ⚠️ **数字以 `SettingsRepository.SPLASH_HOLD_STEP_MS` 为准，不要照抄这里的举例**：
 * 该常量现为 `100`（0.1 秒一档，是为了让默认值 900ms 能被档距整除，见其 KDoc），
 * 于是档数 = `(10000 - 0) / 100 + 1 = 101`，`steps = 101 - 2 = 99`。
 * （历史注记：档距曾是 500，那时是 21 档 / steps = 19，本注释一度滞后于代码，
 * v1.1.71 核对时改正。）
 *
 * 写成算式后，以后改档距（比如改成 1 秒一档）或改上限时这里自动跟着变，
 * 不会留下一个与 `SettingsRepository` 里的常量对不上的魔法数字。
 *
 * 用 `Int` 运算（当前即 `(10000 - 0) / 100 - 1 = 99`），不存在取整问题。
 */
private val SPLASH_HOLD_STEPS =
    (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS) /
        SettingsRepository.SPLASH_HOLD_STEP_MS - 1

/**
 * 「图标测试 → 图标更改」的说明文案。
 *
 * 必须写清两件事，否则用户一定会误解：
 * 1. 内置图标**真的会改桌面图标**（这是"实验性"的部分：桌面刷新时机由 launcher 决定）；
 * 2. 自定义上传的图片**只在应用内显示**（Android 限制：manifest 图标必须是资源）。
 *
 * 第二点尤其不能省——不写，用户上传完图片、回桌面发现没变，会认为功能坏了。
 *
 * ⚠️ 本需求 2 之后，「图标测试」分区只剩三行（设备信息查询 / 朋友圈红点提示 /
 * 本行），实验室与游戏相关四行已迁到「实验室测试」分区。
 */
private const val ICON_CHANGE_DESCRIPTION =
    "切换内置图标；上传图仅应用内显示"

/**
 * 「登录测试」入口行的标题。
 *
 * 与二级页的 `TopAppBar` 标题**用同一个字符串常量**：一级页写「登录测试」、
 * 点进去顶部还是「登录测试」，用户才知道自己进的是刚才点的那一项。
 * 各写一份字面量迟早会漂（本项目别的页面已经出现过"入口叫 A、进页叫 B"的情况）。
 */
private const val LOGIN_TEST_TITLE = "登录测试"

/**
 * 「登录测试」入口行的说明文案。
 *
 * 必须写清"点进去有哪两项、各自管什么"：一级页已经把两个子项折成一行了，
 * 不写就等于让用户盲点。两项的用途完全不同（一个管登录验证码链路、一个管通知权限），
 * 而用户往往是带着"验证码不对"或"通知收不到"其中一个问题来的。
 */
private const val LOGIN_TEST_DESCRIPTION = "验证码与通知的测试入口"

// 🔄 2026-10-25：「设备信息查询」入口（UI_INSPECTOR_TITLE / UI_INSPECTOR_DESCRIPTION）
// 随页面迁到 设置 → 通用 而删除；新入口的文案在 `SettingsPage` 里就地书写。

/**
 * 「朋友圈红点提示」开关的标题。
 *
 * 用「红点提示」而不是「未读角标」：底部导航栏那个东西在用户嘴里就是"红点"
 * （数字也只是红点里的一行字），与本项目其他页面的口语化命名一致。
 */
private const val MOMENTS_BADGE_TITLE = "朋友圈红点提示"

// ---- 音乐分区（2026-11-09；2026-11-14 起只剩歌词一项）---------------------

/** 「音乐入口显示实时歌词」的标题。 */
private const val MUSIC_ENTRY_LYRICS_TITLE = "音乐入口显示实时歌词"

/**
 * 「音乐入口显示实时歌词」的说明。
 *
 * 说明两件事：歌词叠在「歌名 · 歌手」下面单独一行（不替换副标题）；
 * 没有歌词时这一行不出现。
 */
private const val MUSIC_ENTRY_LYRICS_DESCRIPTION =
    "音乐主页「正在播放」主卡在歌名下多显示一行当前歌词，随进度换句。" +
        "没有歌词时这一行不出现。"

/**
 * 「朋友圈红点提示」开关的说明文案（需求原文：「关闭后不再显示未读红点与数字」）。
 *
 * 刻意把「红点」与「数字」两种形态都写出来：角标在未读数为 1~99 时是**带数字的红点**，
 * 只写"不再显示红点"会让用户以为数字还会留着。
 *
 * 这里**不**解释「关掉后进朋友圈仍然会清未读」——那是实现语义，不是用户需要做的决定；
 * 真正需要防止误解的是下一个人改代码时把清除逻辑也一起门控，那句话写在 KDoc 里。
 */
private const val MOMENTS_BADGE_DESCRIPTION =
    "关闭后不再显示未读红点"

/**
 * 「通知测试」按钮的说明文案。
 *
 * 说清「发一条什么内容」和「为什么要发」：用户按这个按钮的动机就是
 * 「我到底能不能收到通知」，所以文案要直接告诉他通知里会出现什么字样，
 * 好让他去通知栏核对。
 */
private const val NOTIFY_TEST_DESCRIPTION =
    "发一条「Hello World」通知"

/**
 * 「通知测试」的冷却秒数（需求：按钮有 10s 间隔）。
 *
 * 取 10s：比系统通知的默认提示音时长长得多，足够用户切到通知栏看完再回来；
 * 又短到不会让人觉得「按钮坏了」。冷却期间按钮直接显示剩余秒数，
 * 用户不用猜为什么点不动。
 */
private const val NOTIFY_TEST_COOLDOWN_SECONDS = 10

/**
 * 「动作按钮」的固定宽度（v1.1.68 需求：按钮不随文本变化而改变大小）。
 *
 * 取 104dp 的依据：`OutlinedButton` 默认左右内边距各 24dp，
 * 中文 `labelLarge`（14sp）四个字约 56dp → 24+56+24 = 104dp 刚好容纳「发送通知」；
 * 冷却态显示「9s」远短于它，于是**两种文案下外框完全相同**。
 *
 * 用固定 dp 而不是 `widthIn(min)`：后者只能保证"不窄于"，文案更长时仍会变宽，
 * 而需求要的是"不随文本变化"，必须是硬宽度。代价是文案超出时会单行截断——
 * 所以本页所有按钮文案都控制在 4 个汉字以内（见 [DeveloperActionRow] 的 `maxLines = 1`）。
 *
 * ⚠️ **现在只服务「通知测试」一行**：本页三个入口行（登录测试 / 图标更改 / 设备信息查询）
 * 的右侧已从「进入」按钮改成右箭头（[TrailingStyle.Navigation]，用户需求：像"我的"页那样），
 * 不再有按钮，因此这个宽度只剩 [TrailingStyle.Button] 一种使用场景。
 * 保留它是因为"发送通知 ↔ 9s"这条文案切换仍然需要一个不跳动的固定宽度；
 * 若哪天连这一行也改成非按钮形态，届时可以连它一起删掉（届时不会留下死常量）。
 */
private val ACTION_BUTTON_WIDTH = 92.dp

/**
 * 「动作按钮」的固定高度（2026-10-27 用户要求「按钮宽度和高度适当减小」）。
 *
 * Material3 的 `OutlinedButton` 默认最小高度是 40dp。取 32dp 是"看起来更紧凑、
 * 但仍能舒服点中"的折中：**可见高度是 32dp**，而 Material 的
 * `minimumInteractiveComponentSize` 仍会把**触摸热区**兜到 48dp，
 * 因此手指不容易点偏——减小的是视觉尺寸，不是命中区域。
 *
 * ⚠️ 与 [ACTION_BUTTON_WIDTH] 一样，这里**不动字号**：文案仍是默认 `labelLarge`。
 */
private val ACTION_BUTTON_HEIGHT = 32.dp

// ---- 本轮新增子项的文案常量（需求 11a / 11b / 12）-----------------------------

/** 「左右滑动动画」开关的标题（需求 11a）。 */
private const val TAB_SLIDE_ANIM_TITLE = "底部入口切换动画"

/**
 * 「左右滑动动画」开关的说明文案。
 *
 * 写清它管的是**哪一段**动画（一级入口切换的新页滑入），不然用户会以为
 * 管的是所有动画。同时点明默认开——这是少数"默认值 = 既有行为"的开关。
 */
private const val TAB_SLIDE_ANIM_DESCRIPTION =
    "底栏切换时页面跟手滑移"

/**
 * ⚠️ 已删除：`SNAKE_TITLE_BAR_TITLE` / `SNAKE_TITLE_BAR_DESCRIPTION`
 * （原「贪吃蛇标题栏显示」这一行的标题与说明）。
 *
 * 那一行的开关早已合并进 [GAME_TITLE_BAR_TITLE]（同时控制贪吃蛇与数独），
 * 两个常量从那时起就没有任何调用点。留着它们的问题不是"多几行"，
 * 而是**它们的说明文案描述了一个界面上已经不存在的开关**——文里还在教用户
 * 「数独页请用『游戏标题栏显示』」，而那一行本身就是「游戏标题栏显示」。
 * 注释与代码不一致正是本项目反复要消除的那类问题，因此随这次归类一并清掉。
 *
 * 对应的 DataStore 旧键 `dev_snake_title_bar` 仍**保留**（不清空历史值），
 * 与仓库层「旧键留着但无人读」的口径一致——那属于数据，不是死代码。
 */

/**
 * 「游戏开屏显示」入口行的说明文案（需求 2 起它是入口而不是开关）。
 *
 * 点明「进去可以分别设置贪吃蛇与数独」——用户看这一行就该知道，
 * 关掉一个游戏的开屏不影响另一个。
 */
private const val GAME_SPLASH_DESCRIPTION_ENTRY =
    "贪吃蛇与数独的开屏开关"

/** 「游戏标题栏显示」入口行的标题（本轮需求 5：开关改为二级界面入口）。 */
private const val GAME_TITLE_BAR_TITLE = "游戏标题栏显示"

/**
 * 「游戏标题栏显示」入口行的说明文案（本轮需求 5 起它是入口而不是开关）。
 *
 * 与「游戏开屏显示」入口同一口径：点明「进去可分别设置两个游戏」、
 * 点明总开关仍保留（关掉它两个游戏都不显示，二级页里的子开关不再生效）。
 */
private const val GAME_TITLE_BAR_DESCRIPTION_ENTRY =
    "贪吃蛇与数独的标题栏显隐"

/** 「游戏标题栏显示」二级页的标题（本轮需求 5）。 */
private const val GAME_TITLE_BAR_SCREEN_TITLE = "游戏标题栏显示"

/** 「游戏标题栏显示」二级页里贪吃蛇那一行的标题。 */
private const val GAME_TITLE_BAR_SNAKE_TITLE = "贪吃蛇标题栏"

/**
 * 「游戏标题栏显示」二级页里贪吃蛇那一行的说明。
 * 点明关掉后怎么返回（标题栏上有返回键，关掉后只剩系统返回键）。
 */
private const val GAME_TITLE_BAR_SNAKE_DESCRIPTION =
    "关闭后不显示顶部标题栏"

/** 「游戏标题栏显示」二级页里数独那一行的标题。 */
private const val GAME_TITLE_BAR_SUDOKU_TITLE = "数独标题栏"

/** 「游戏标题栏显示」二级页里数独那一行的说明（同贪吃蛇那行的口径）。 */
private const val GAME_TITLE_BAR_SUDOKU_DESCRIPTION =
    "关闭后不显示顶部标题栏"

/** 「游戏标题栏显示」二级页里数独2 那一行的标题（2026-10-22 补）。 */
private const val GAME_TITLE_BAR_SUDOKU2_TITLE = "数独2 标题栏"

/** 「游戏标题栏显示」二级页里数独2 那一行的说明（同另两行的口径）。 */
private const val GAME_TITLE_BAR_SUDOKU2_DESCRIPTION =
    "关闭后不显示顶部标题栏"

/** 「开发者选项背景设置」入口行的标题（需求 11b）。 */
private const val LAB_BG_TITLE = "实验室背景"

/**
 * 「开发者选项背景设置」入口行的说明文案。
 *
 * 把三档模式写全（默认随机轮换 / 经典跟随系统 / 自定义颜色），
 * 用户不用点进去就知道自己要的是哪一档。
 */
private const val LAB_BG_DESCRIPTION =
    "随机轮换 / 跟随系统 / 自定义颜色"

//
// ⚠️ 已删除（2026-10-23 需求 8：「原开屏时长浮动设置删除」）：
// `SPLASH_JITTER_TITLE` / `SPLASH_JITTER_DESCRIPTION` / `SPLASH_JITTER_STEPS`
// 三个常量连同那条「浮动范围」滑块一并去掉，代替它的是下方的勾选式方案。
//
// 之所以连文案常量一起删（而不是留着备用）：需求要的浮动语义已经从
// 「0..N 范围内随机多停」变成「固定 ±0.3 秒」，旧文案描述的行为在界面上不再存在，
// 留着就会变成注释与实际不一致的那类债。
//
// 对应的 DataStore 旧键 `splash_jitter_ms`（连同 `SettingsRepository` 的读写入口）
// 也一并删除：它的值写在用户磁盘上会变成无人读的孤儿数据，但 DataStore 允许这样，
// 需要回滚时按键名取原始值即可（这也是本项目一贯的处理口径）。

/** 「开发者选项背景设置」二级页的标题。 */
private const val LAB_BG_SCREEN_TITLE = "实验室背景设置"

/**
 * 「界面测试」分区（需求 8）四项的文案。
 *
 * 三个开关的说明都按本文件的既有口径写清「**管的是哪一处**」与「**关掉会怎样**」，
 * 否则用户会以为它管的是全应用所有同类效果。
 */

/** 「打卡完成动画」开关的标题。 */
private const val CHECKIN_CELEBRATION_TITLE = "打卡完成动画"

/**
 * 「打卡完成动画」的说明。
 *
 * 写清"只在主页打卡后出现"（它不是全局撒花），以及关掉后**没有任何替代反馈**——
 * 打卡仍然成功，只是安静了。这与用户可能期待的"换个动效"不同，先讲清楚免得以为坏了。
 */
private const val CHECKIN_CELEBRATION_DESCRIPTION =
    "主页打卡成功的彩带动画"

/** 「页面转场动画」开关的标题。 */
private const val PAGE_TRANSITION_TITLE = "页面转场动画"

/**
 * 「页面转场动画」的说明。
 *
 * 点明它是"应用内页面之间"的推入/弹出（底部导航四个入口之间的切换**不受**它影响），
 * 否则用户会以为连底栏切换动画也被关掉了——那会是一个他没要求的副作用。
 */
private const val PAGE_TRANSITION_DESCRIPTION =
    "二级页推入与返回动画"

/** 「按压反馈」开关的标题。 */
private const val PRESS_FEEDBACK_TITLE = "按压反馈"

/**
 * 「按压反馈」的说明。
 *
 * 点明它是"按下即缩放 + 变淡"、且**全应用统一**（不是某个页面的），
 * 让用户知道关掉之后所有可点元素的手感会一起变。
 */
private const val PRESS_FEEDBACK_DESCRIPTION =
    "可点元素按下时缩放并变淡"

/** 「日期显示格式」入口行的标题。 */
private const val DATE_FORMAT_TITLE = "日期显示格式"


/** 「开屏显示文字」一级页入口行的标题（2026-10-27 新增）。 */
private const val SPLASH_TEXT_TITLE = "开屏显示文字"

/**
 * 「开屏显示文字」入口行的说明。
 *
 * 写清"改的是**全部**开屏"，否则用户会以为只改实验室那几页（实际还包括
 * 应用启动、解锁前与三个游戏）。具体作用范围在二级页页脚再讲一次。
 */
private const val SPLASH_TEXT_DESCRIPTION = "文案、字号、字体与单多行"

/** 「开屏显示文字」二级页的标题（与入口行同名，用户才知道自己进的是刚才点的那一项）。 */
private const val SPLASH_TEXT_SCREEN_TITLE = "开屏显示文字"

/** 「实验室功能开屏」二级页的标题（2026-10-27）。 */
private const val LAB_SPLASH_SCREEN_TITLE = "实验室功能开屏"

/** 「实验室功能开屏」一级页入口行的标题（开关改成入口后仍沿用原名）。 */
private const val LAB_SPLASH_ALL_TITLE = "实验室功能开屏"

/** 二级页总开关的说明。 */
private const val LAB_SPLASH_ALL_DESCRIPTION =
    "关闭后以下开屏都不显示"

/** 二级页时长行的标题。 */
private const val LAB_SPLASH_DURATION_TITLE = "开屏显示时长"

/** 二级页时长行的说明（点明它与 App 冷启动那个时长是两回事）。 */
private const val LAB_SPLASH_DURATION_DESCRIPTION =
    "只影响实验室开屏，不影响启动"

/** 「其他实验室页面开屏」行标题。 */
private const val LAB_SPLASH_OTHERS_TITLE = "其他实验室页面"

/** 「其他实验室页面开屏」行说明（列出到底包含哪些页面）。 */
private const val LAB_SPLASH_OTHERS_DESCRIPTION =
    "天气、记事本、待办等六个页面"

/** 「热搜页开屏」行标题。 */
private const val LAB_SPLASH_HOTSEARCH_TITLE = "热搜"

/** 「热搜页开屏」行说明。 */
private const val LAB_SPLASH_HOTSEARCH_DESCRIPTION =
    "只影响「发现 → 热搜」"

/** 「数独2 开屏」行标题（2026-10-27 新增：本页原先没有开屏）。 */
private const val GAME_SPLASH_SUDOKU2_TITLE = "数独2 开屏"

/** 「数独2 开屏」行说明。 */
private const val GAME_SPLASH_SUDOKU2_DESCRIPTION =
    "关闭后直接进入游戏"

/** 「游戏颜色模式」入口行的标题（本轮需求 3）。 */
private const val GAME_CHROME_TITLE = "游戏颜色模式"

/**
 * 「游戏颜色模式」入口行的说明文案（本轮需求 3）。
 *
 * 三件事缺一不可：管的是**哪两页**（贪吃蛇与数独共用一个值）、当前是**哪一档**
 * （用户不点进去也能看到"跟随系统 / 深色经典 / 深色适配"这组词）、点进去能做什么。
 * 不写"三档可选"这种空话——用户看到那三个词才知道自己要不要进去。
 */
private const val GAME_CHROME_DESCRIPTION_ENTRY =
    "贪吃蛇与数独共用的配色"

/** 「游戏颜色模式」二级页的标题（需求 3）。 */
private const val GAME_CHROME_SCREEN_TITLE = "游戏颜色模式"

/**
 * 「游戏颜色模式」二级页的页首说明。
 *
 * 要点明"两页共用"与"标题栏、状态栏、开屏底色会一起变"——用户拨之前不知道
 * 三档到底改了什么，容易以为是"只改标题栏颜色"。
 */
private const val GAME_CHROME_SCREEN_HINT =
    "贪吃蛇与数独共用，选中即生效"

/**
 * 三档 chrome 的**说明文案**，按 [GameChromeMode] 对应。
 *
 * 为什么不把说明塞进枚举本身（`GameChromeMode` 只有 `value` / `label`）：
 * 那个枚举是**渲染策略**，被两个游戏页与仓库共用；「在开发者选项的二级页里怎么
 * 向用户解释这一档」是界面文案，属于本页。两者放在一起会让"改一句说明"
 * 触碰游戏页的渲染代码。这里用一个 `Map` 而不是 `when`：新增一档时
 * 枚举是唯一的入口，本页不需要改结构（`orEmpty()` 兜住漏写的说明）。
 */
private val GAME_CHROME_DESCRIPTIONS: Map<GameChromeMode, String> = mapOf(
    GameChromeMode.SYSTEM to
        "跟随应用主题，浅色主题下标题栏与游戏画面之间有分界",
    GameChromeMode.DARK_CLASSIC to
        "标题栏与游戏画面同色连成一片（默认）",
    GameChromeMode.DARK_ADAPTIVE to
        "同「深色经典」，但标题与返回箭头直接浮在游戏画面上"
)

/** 「游戏开屏显示」二级页里贪吃蛇那一行的标题。 */
private const val GAME_SPLASH_SNAKE_TITLE = "贪吃蛇开屏"

/**
 * 「游戏开屏显示」二级页里贪吃蛇那一行的说明。
 *
 * 点明关掉后看到什么（直接进入游戏画面），不然用户会担心"是不是坏了"。
 */
private const val GAME_SPLASH_SNAKE_DESCRIPTION =
    "关闭后直接进入游戏"

/** 「游戏开屏显示」二级页里数独那一行的标题。 */
private const val GAME_SPLASH_SUDOKU_TITLE = "数独开屏"

/** 「游戏开屏显示」二级页里数独那一行的说明（同贪吃蛇那行的口径）。 */
private const val GAME_SPLASH_SUDOKU_DESCRIPTION =
    "关闭后直接进入难度选择"

/**
 * 「实验室背景设置」二级页（需求 11b，就地覆盖在开发者选项页上）。
 *
 * 内容 = 三档模式的单选列表 + `custom` 档的颜色挑选行：
 * - **随机轮换**（默认）：按系统深浅色分池，每次进实验室往前轮一条渐变
 *   （= 改造前的既有行为，见 `LabScreen.rememberLabBackdrop`）；
 * - **经典**：跟随系统深浅色的纯色（浅色 `#EDEDED` / 深色 `#111111`，
 *   与全局页面底色同源，观感就是"实验室不再有花背景"）；
 * - **自定义**：用户挑选的固定颜色，深浅色模式都显示这一个值。
 *
 * ## 为什么选择态用 `RadioButton` 行而不是分段按钮
 *
 * 与「图标更改」的格子网格不同，三档是**互斥单选**且有说明文字要展示，
 * `RadioButton` + 说明的行式布局信息量最大；M3 的 `RadioButton` 行是
 * 设置类页面（如「外观」）的标准形态，用户无需学习。
 *
 * ## 颜色挑选
 *
 * 本页**内置**一个轻量取色弹窗（[LabBgColorPickerDialog]，见文件末尾）：
 * 预设色板 + 红 / 绿 / 蓝三根滑块。没有引入任何第三方取色库——
 * 本项目的依赖纪律是"不为一个调试入口新增依赖"，而 HSL/HSV 全域取色对
 * 背景设置这个场景过重；预设色板（取自应用主题色与常用底色）+ RGB 微调
 * 已覆盖"自行挑选颜色"的需求。
 *
 * 门控：本二级页只能从开发者选项进入（入口行已受总开关门控），页内不再重复判。
 *
 * @param onBack 返回开发者选项页。
 */
/**
 * 「实验室功能开屏」二级页（2026-10-27 新增）。
 *
 * ## 需求与它改变了什么
 *
 * 原文：「实验室功能开屏可单独设置包括热搜和三个游戏，有一个总开关，
 * 总开关关闭时都不显示开屏。实验室开屏功能与 app 开屏时长分开控制」。
 *
 * 改造前这里是**一行开关**（管其他实验室页面），游戏开屏另有一行入口（管两个游戏），
 * 两套东西各管各的、还可能互相矛盾。现在合并成一页：
 *
 * | 行 | 管什么 | 默认 |
 * | --- | --- | --- |
 * | 总开关 | 整组的总门。关掉后下面几项**一律不播** | 开 |
 * | 显示时长 | 本组独有的停留时长（与 App 冷启动那个值分开） | 0.8 秒 |
 * | 其他实验室页面 | 天气 / 记事本 / 智能体 / 课程表 / 今日待办 / 小工具 / 番茄钟 | 关 |
 * | 热搜 | 只作用于热搜页 | 关 |
 * | 贪吃蛇 / 数独 / 数独2 | 三个网页游戏各自的开屏 | 开 |
 *
 * ## 默认值为什么这样定（每个都对齐"改造前的实际观感"）
 *
 * - 三个游戏原本就是**默认显示**（`dev_game_splash*` 默认 true）；
 * - 热搜与其他实验室页面原本**默认不播**（老键 `dev_lab_feature_splash` 默认 false）；
 * - 总开关默认开，是因为它必须让"游戏开屏照常"这件事在升级后成立——
 *   若默认关，所有人升级后三个游戏的开屏会集体消失。
 *
 * ## 显示时长为什么不复用 App 开屏那一条
 *
 * 那个值（`dev_splash_hold_ms`）是**冷启动** Hello World 的停留时长，调它是为了看
 * "启动多久"，与"进热搜闪一下"完全不是同一件事。共用时把冷启动调到 3 秒，
 * 进热搜也要等 3 秒——所以本轮给这一组开了独立的键 `dev_lab_splash_hold_ms`。
 *
 * ## 门控口径
 *
 * 本页每一行都只受**开发者选项总开关**与**本页总开关**两级门控；
 * 与游戏那几行的子开关无关（它们管的是标题栏与配色，不管开屏）。
 * 这是与一级页那些"游戏行"的一处**有意差异**：开屏属于"实验室功能开屏"这一组，
 * 归本页管。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabSplashScreen(
    /** 开发者选项总开关：关掉时本页所有行置灰。 */
    masterEnabled: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }

    // 全部读**原始值**：总开关关掉时，各子开关的位置照旧显示——
    // 「值还在、只是暂不生效」，与一级页其它子项同一口径。
    val allSwitch by repository.labSplashAllSwitch.collectAsStateWithLifecycle(initialValue = true)
    val othersSwitch by repository.labFeatureSplashSwitch.collectAsStateWithLifecycle(initialValue = false)
    val hotSearchSwitch by repository.labSplashHotSearchSwitch
        .collectAsStateWithLifecycle(initialValue = false)
    val snakeSplash by repository.gameSplashSnakeSwitch.collectAsStateWithLifecycle(initialValue = true)
    val sudokuSplash by repository.gameSplashSudokuSwitch.collectAsStateWithLifecycle(initialValue = true)
    val sudoku2Splash by repository.labSplashSudoku2Switch
        .collectAsStateWithLifecycle(initialValue = true)
    val holdMs by repository.labSplashHoldSwitch.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_LAB_SPLASH_HOLD_MS
    )
    // 2026-11-14：从「其他实验室页面」一把开关细到逐页开关。
    val disabledRaw by repository.labSplashDisabledPages
        .collectAsStateWithLifecycle(initialValue = "")
    val disabledPages = remember(disabledRaw) { decodeSplashDisabled(disabledRaw) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LAB_SPLASH_SCREEN_TITLE) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 说明卡：先讲清"关掉会怎样"，避免用户以为关掉后进不去页面。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "开屏是进入页面时先显示一秒「Hello World」再进入。"
                        + "关掉总开关后，下面所有项都不再显示开屏；"
                        + "只关其中某一项时，只影响那一个页面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    title = LAB_SPLASH_ALL_TITLE,
                    description = LAB_SPLASH_ALL_DESCRIPTION,
                    checked = allSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setLabSplashAll(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                LabSplashDurationRow(
                    holdMs = holdMs,
                    // 时长只有在"总开关开"时才有意义：关着的时候拖它不会改变任何观感，
                    // 但**仍显示当前值**（与子开关"值还在"同一口径）。
                    enabled = masterEnabled && allSwitch,
                    onValueChange = { scope.launch { repository.setLabSplashHoldMs(it) } }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Text(
                text = "分别设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )

            Card(Modifier.fillMaxWidth()) {
                val rowEnabled = masterEnabled && allSwitch

                DeveloperSwitchRow(
                    title = LAB_SPLASH_OTHERS_TITLE,
                    description = LAB_SPLASH_OTHERS_DESCRIPTION,
                    checked = othersSwitch,
                    enabled = rowEnabled,
                    onCheckedChange = { scope.launch { repository.setLabFeatureSplash(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                DeveloperSwitchRow(
                    title = LAB_SPLASH_HOTSEARCH_TITLE,
                    description = LAB_SPLASH_HOTSEARCH_DESCRIPTION,
                    checked = hotSearchSwitch,
                    enabled = rowEnabled,
                    onCheckedChange = { scope.launch { repository.setLabSplashHotSearch(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                DeveloperSwitchRow(
                    title = GAME_SPLASH_SNAKE_TITLE,
                    description = GAME_SPLASH_SNAKE_DESCRIPTION,
                    checked = snakeSplash,
                    enabled = rowEnabled,
                    onCheckedChange = { scope.launch { repository.setGameSplashSnake(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                DeveloperSwitchRow(
                    title = GAME_SPLASH_SUDOKU_TITLE,
                    description = GAME_SPLASH_SUDOKU_DESCRIPTION,
                    checked = sudokuSplash,
                    enabled = rowEnabled,
                    onCheckedChange = { scope.launch { repository.setGameSplashSudoku(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                DeveloperSwitchRow(
                    title = GAME_SPLASH_SUDOKU2_TITLE,
                    description = GAME_SPLASH_SUDOKU2_DESCRIPTION,
                    checked = sudoku2Splash,
                    enabled = rowEnabled,
                    onCheckedChange = { scope.launch { repository.setLabSplashSudoku2(it) } }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Text(
                text = "页面分类（逐页开关）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            Text(
                text = "「其他实验室页面」总开关仍是一票否决；下面可以再单独关掉某一页。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
            )

            Card(Modifier.fillMaxWidth()) {
                val rowEnabled = masterEnabled && allSwitch
                LAB_SPLASH_PAGES.forEachIndexed { index, (routeKey, label) ->
                    DeveloperSwitchRow(
                        title = label,
                        description = "进入该实验室页面时是否显示开屏",
                        checked = othersSwitch && routeKey !in disabledPages,
                        enabled = rowEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.setLabSplashPageEnabled(routeKey, enabled)
                            }
                        }
                    )
                    if (index != LAB_SPLASH_PAGES.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "三个游戏与热搜的开屏各自独立：本次使用期间，某个页面播过一次后，"
                    + "再进它就不播了（其他页面不受影响）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * 实验室开屏时长的滑块行。
 *
 * 取值 0 ~ [SettingsRepository.MAX_LAB_SPLASH_HOLD_MS]（3 秒）。拖到 0 时右侧显示
 * 「不播放」而不是「0.0 秒」——0 秒的实际含义是"不播"（`LabSplashHost` 判 `<= 0`），
 * 写"0.0 秒"会让人以为还是会闪一下。
 *
 * 用连续滑块而不是 [SPLASH_HOLD_STEPS] 那种分段：这个值是"过渡快慢"，
 * 用户不会在意精确到 100ms，连续拖动更顺手；也不需要「恢复默认」——
 * 拖回 0.8 秒的位置即可，为此多摆一个按钮反而占地方。
 */
@Composable
private fun LabSplashDurationRow(
    holdMs: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = LAB_SPLASH_DURATION_TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.strongTextColor
                    else MaterialTheme.strongTextColor.copy(alpha = DISABLED_CONTENT_ALPHA)
                )
                Text(
                    text = LAB_SPLASH_DURATION_DESCRIPTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.75f else DISABLED_CONTENT_ALPHA
                    )
                )
            }
            Text(
                text = if (holdMs <= 0) "不播放" else String.format("%.1f 秒", holdMs / 1000f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
            )
        }
        Slider(
            value = holdMs.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..SettingsRepository.MAX_LAB_SPLASH_HOLD_MS.toFloat(),
            enabled = enabled,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 「支持作者链接」的编辑弹窗（2026-10-29 需求）。
 *
 * ## 为什么单独一个弹窗，而不是复用 `SplashTextEditDialog`
 *
 * 那个弹窗自带"字数上限 / 多行 / 换行提示"那一套（为开屏文字定的），
 * 套到一段 URL 上只会冒出无意义的字数读数；而这个弹窗反过来要**校验协议头**
 * （只放行 http/https，与 `openInBrowser` 的判据一致），否则用户存下一个
 * `javascript:` 或 `file://` 的地址，点「支持作者」时只会得到一句"只支持 http/https"。
 *
 * 校验放在**保存按钮的 enabled** 上，而不是保存后再弹错：用户一边输入一边就能看出
 * "这样不行"，不用先按一次再看报错。
 *
 * ## 空值的语义
 *
 * 清空 = 恢复默认（与 `SettingsRepository.setSupportAuthorUrl(null)` 同口径），
 * 所以「清空」是合法输入，不该被禁用——但要用一行小字说明它等于默认值，
 * 否则用户会以为"清空了就什么都不打开"。
 */
/**
 * 「图标更改(实验性)」进入前的强制风险提醒（需求）。
 *
 * 要点：
 * - **强制**：没有"不再提醒"的勾选，点遮罩 / 返回键都只是关掉弹窗，不会进入功能；
 * - **5 秒倒计时**：确认按钮在倒计时结束前是禁用的，逼用户把风险说明读完；
 * - 确认按钮用**有底色**的 `Button`（实心），与取消的无底色 TextButton 形成主次。
 */
@Composable
private fun IconExperimentalWarnDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("实验性功能提醒") },
        text = {
            Text(
                text = "「图标更改」目前还在测试中，存在崩溃风险；" +
                    "更换的桌面图标可随时改回默认。" +
                    // 2026-11-01 需求：这里原来在倒计时期间显示
                    // 「倒计时结束（N 秒）后方可继续。」，倒计时结束时那行会消失，
                    // 于是文案少一行、正文与按钮的间距跟着变、按钮往上跳一下。
                    // 现在**恒留一个空行**（不是删掉换行）：行数固定，间距与按钮位置都不动。
                    // 剩余秒数仍然在按钮文案「知道了，继续（Ns）」里，信息没丢。
                    "\n",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = secondsLeft <= 0
            ) {
                Text(if (secondsLeft > 0) "知道了，继续（${secondsLeft}s）" else "知道了，继续")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CheckUpdateTargetDialog(
    current: CheckUpdateTarget,
    onDismiss: () -> Unit,
    onPick: (CheckUpdateTarget) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查更新按钮功能") },
        text = {
            Column {
                Text(
                    text = "点「关于 → 检查更新」时打开什么",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(8.dp))
                CheckUpdateTarget.entries.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 整行可点：单选行的命中区应当覆盖整行，而不是只有左侧圆点。
                            .clickable { onPick(target) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = target == current,
                            onClick = { onPick(target) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                text = target.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (target) {
                                    CheckUpdateTarget.APK_DOWNLOAD ->
                                        "云端正式版列表，可直接下载（需登录）"
                                    CheckUpdateTarget.WEB_IN_APP ->
                                        "应用内网页打开 GitHub Releases"
                                    CheckUpdateTarget.BROWSER ->
                                        "用系统浏览器打开"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 「更新日志入口位置」二选一弹窗（2026-11-02 需求）。
 *
 * 与 [CheckUpdateTargetDialog] 同一形态（整行可点 + 单选圆点 + 一行说明），
 * 用户不必学两套交互。
 */
@Composable
private fun ChangelogPlacementDialog(
    current: ChangelogEntryPlacement,
    onDismiss: () -> Unit,
    onPick: (ChangelogEntryPlacement) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新日志入口") },
        text = {
            Column {
                Text(
                    text = "「更新日志」这个入口放在哪一页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(8.dp))
                ChangelogEntryPlacement.entries.forEach { placement ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 整行可点：与上面的去处弹窗保持同一套命中区口径。
                            .clickable { onPick(placement) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = placement == current,
                            onClick = { onPick(placement) }
                        )
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text(
                                text = if (placement == ChangelogEntryPlacement.INSIDE) {
                                    "在里面（下载页底部）"
                                } else {
                                    "在外面（关于页）"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (placement == ChangelogEntryPlacement.INSIDE) {
                                    "固定在安装包下载页屏幕底部，未登录也能点"
                                } else {
                                    "放在「关于我们」的检查更新按钮下方"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun SupportUrlEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    val trimmed = draft.trim()
    val valid = trimmed.isEmpty() || trimmed.startsWith("http://", true) ||
        trimmed.startsWith("https://", true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支持作者链接") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    // 占位提示直接给出默认地址：用户清空后也能看到"不改是什么样"。
                    placeholder = { Text(DEFAULT_SUPPORT_AUTHOR_URL) }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        !valid -> "只支持以 http:// 或 https:// 开头的地址"
                        trimmed.isEmpty() -> "留空 = 使用默认地址（作者的 B 站主页）"
                        else -> "点击「关于 → 支持作者」时用系统浏览器打开这个地址"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = valid) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 「恢复默认」直接写空草稿：与清空是同一件事，但比"手动删光"好点。
                TextButton(onClick = { draft = "" }) { Text("恢复默认") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 开屏文字的输入弹窗（2026-10-27）。
 *
 * 用 `AlertDialog` + `OutlinedTextField` 而不是复用指纹文案那套 `BioTextEditDialog`：
 * 那个是**单行**输入（标题/副标题/按钮文案），而这里要支持换行（多行显示模式），
 * 并且需要实时字数上限提示——两者行为不同，硬套会让"回车"这个动作在那边被吃掉。
 *
 * 确认时若输入为空：直接**不写**（[SettingsRepository.setSplashText] 里也拦了一道），
 * 并提示一句"已保留原文字"，避免用户以为清空成功了而开屏还是老样子。
 */
@Composable
private fun SplashTextEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开屏显示文字") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(SplashTextStyle.MAX_TEXT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    // 三行可见：够看清一段短句，又不至于把弹窗撑到占满屏幕。
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text(SplashTextStyle.DEFAULT_TEXT) },
                    // 不限制字符集：中文、英文、数字、符号与 emoji 都照原样显示，
                    // 字体缺失的字形由系统回退补齐（见 `SplashTextStyle` 的 KDoc）。
                    singleLine = false
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${draft.length} / ${SplashTextStyle.MAX_TEXT_LENGTH} 字" +
                        if (draft.contains('\n')) "（含换行，多行显示时生效）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                // 空白内容不允许确认（与仓库层的"空白不写"一致，提前在界面上拦下）。
                enabled = draft.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 开屏文字的字号行（标题 + 当前值 + 滑块）。
 *
 * 用**连续滑块 + 右侧实时读数**，不用分段档位：字号是"拖到看着舒服为止"的量，
 * 35 / 36 / 37 sp 之间没有语义差别，分段只会让用户多按几次。
 *
 * 读数一律带单位 `sp` 并且**位数固定**（两位数 16~72 不会跳动）：
 * 拖到 9x 时文字宽度变化会让整行轻微抖动，那样看起来像布局坏了。
 */
@Composable
private fun SplashTextSizeRow(
    sizeSp: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "文字大小",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.strongTextColor
                    } else {
                        MaterialTheme.strongTextColor.copy(alpha = DISABLED_CONTENT_ALPHA)
                    }
                )
                Text(
                    text = "范围 ${SplashTextStyle.MIN_SIZE_SP}~${SplashTextStyle.MAX_SIZE_SP} sp",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.75f else DISABLED_CONTENT_ALPHA
                    )
                )
            }
            Text(
                text = "$sizeSp sp",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
                }
            )
        }
        Slider(
            value = sizeSp.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = SplashTextStyle.MIN_SIZE_SP.toFloat()..
                SplashTextStyle.MAX_SIZE_SP.toFloat(),
            enabled = enabled,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 预览卡高度（理由见 `SplashTextScreen` 里那张卡的注释）。 */
private val SPLASH_TEXT_PREVIEW_HEIGHT = 150.dp

/** 字体三档的说明（下拉项的 hint）。 */
private val SPLASH_FONT_HINTS: Map<SplashTextFont, String> = mapOf(
    SplashTextFont.DEFAULT to "系统无衬线，与改造前一致",
    SplashTextFont.SERIF to "衬线体，中文回落宋体，像印刷品",
    SplashTextFont.MONOSPACE to "等宽体，像终端 / 代码"
)

/**
 * 「游戏标题栏显示」二级页（本轮需求 5：入口 + 可分别设置，原总开关保留）。
 *
 * 需求原文：「游戏标题栏显示增加二级界面，可单独设置标题栏显隐，原总开关保留，
 * 右侧增加向右箭头」。本页与 [GameSplashScreen] **逐项同构**：
 * 两行开关（贪吃蛇 / 数独），各自拨各自的标题栏。
 *
 * ## 数据层与生效规则
 *
 * 走 `SettingsRepository` 直接读写（与 `GameSplashScreen` 同一条路——只需要原始布尔）。
 * 生效值在仓库层合成：`开发者选项总开关 && 游戏子开关`（2026-10-30 已去掉中间门）
 * （见 `DeveloperOptionsRepository.gameTitleBarSnakeEnabled`），本页只回显原始值：
 * 关掉任何一级总开关时两行置灰、但开关位置照旧显示——
 * 「值还在、只是暂不生效」（本文件 KDoc 的既定口径）。
 *
 * @param masterEnabled 开发者选项总开关：关掉时本页两行置灰。
 * @param onBack 返回开发者选项页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameTitleBarScreen(
    masterEnabled: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }

    // 两个游戏的**原始值**（界面回显用，不代表是否生效）。默认都开 = 改造前行为。
    val snakeTitleBar by repository.gameTitleBarSnakeSwitch
        .collectAsStateWithLifecycle(initialValue = true)
    val sudokuTitleBar by repository.gameTitleBarSudokuSwitch
        .collectAsStateWithLifecycle(initialValue = true)
    // 数独2 的原始值（2026-10-22 补：此前它没有自己的开关，导致标题栏关不掉）。
    val sudoku2TitleBar by repository.gameTitleBarSudoku2Switch
        .collectAsStateWithLifecycle(initialValue = true)

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(GAME_TITLE_BAR_SCREEN_TITLE) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 说明卡：讲清三层开关的关系，避免用户"关了子开关却还在显示"时困惑
            // （那通常是一级总开关没开）。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "两级开关都打开时才显示对应游戏的标题栏（三个游戏各有一行）。" +
                        "返回上一级把「游戏标题栏显示」拨开即可恢复；" +
                        "关闭某个游戏的标题栏后，进入该游戏用系统返回键返回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                Column {
                    DeveloperSwitchRow(
                        title = GAME_TITLE_BAR_SNAKE_TITLE,
                        description = GAME_TITLE_BAR_SNAKE_DESCRIPTION,
                        // 原始值：生效合成在仓库层（开发者选项总开关关 → 恒显示）。
                        checked = snakeTitleBar,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setGameTitleBarSnake(it) } }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )

                    DeveloperSwitchRow(
                        title = GAME_TITLE_BAR_SUDOKU_TITLE,
                        description = GAME_TITLE_BAR_SUDOKU_DESCRIPTION,
                        checked = sudokuTitleBar,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setGameTitleBarSudoku(it) } }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )

                    // 数独2（2026-10-22 补）：在没有这一行之前，数独2 的标题栏只能靠
                    // "两页共用"的旧总开关关掉，而它在界面上已经变成二级页入口——
                    // 用户实际无处可关，表现就是"数独2 标题栏无法关闭"。
                    DeveloperSwitchRow(
                        title = GAME_TITLE_BAR_SUDOKU2_TITLE,
                        description = GAME_TITLE_BAR_SUDOKU2_DESCRIPTION,
                        checked = sudoku2TitleBar,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setGameTitleBarSudoku2(it) } }
                    )
                }
            }
        }
    }
}

/**
 * 「游戏颜色模式」二级页（本轮需求 3：标题栏 / 状态栏 / 开屏底色三档可选）。
 *
 * 需求原文：「游戏标题栏/状态栏颜色做三档可选：跟随系统 / 深色经典 / 深色适配」。
 * 三档的渲染规则在 `GameChromeMode` 与两个游戏页的 KDoc 里，本页只负责**选择**：
 * 一列 `RadioButton` 行，标题是档位名，说明里写清这一档的观感差异。
 *
 * ## 门控
 *
 * 与一级页其它子项一致：`enabled = masterEnabled`。关掉总开关时三行置灰、点不动，
 * 但**单选框仍停在用户选的那一档**——这正是本页读
 * `repository.gameChromeModeSwitch`（原始值）而不是 `gameChromeMode`（生效值）的原因。
 * 读生效值会让"总开关一关，单选框自己跳回深色经典"，用户会以为自己的设置被清空。
 *
 * ## 数据层为什么走 `DeveloperOptionsRepository` 而不是 `SettingsRepository`
 *
 * 另外两个二级页（`GameSplashScreen` / `LabBackgroundScreen`）直接读写
 * `SettingsRepository`，因为它们只需要"原始布尔/字符串"。本页需要的是**枚举**，
 * 而字符串 → 枚举的解析统一放在 `DeveloperOptionsRepository.gameChromeModeSwitch`
 * 里做了一次；在这里再写一遍 `GameChromeMode.fromValue(raw)` 会让"解析失败回落
 * 哪一档"出现两个来源。
 *
 * @param masterEnabled 总开关状态：关掉时三行置灰（口径与一级页其它子项一致）。
 * @param onBack 返回开发者选项页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameChromeScreen(
    masterEnabled: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }

    // 原始值（界面回显用，不代表是否生效，见本函数 KDoc 的「门控」一节）。
    // initialValue 给默认档：DataStore 首次读取要走磁盘，先渲染成默认档最接近真实。
    val chromeMode by repository.gameChromeModeSwitch.collectAsStateWithLifecycle(
        initialValue = GameChromeMode.DARK_CLASSIC
    )

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(GAME_CHROME_SCREEN_TITLE) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 说明卡：三档到底改了什么，用户拨之前得知道（避免以为是"只改标题栏"）。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = GAME_CHROME_SCREEN_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                Column {
                    // 数据驱动地遍历枚举：新增一档只需改枚举，本页自动多一行。
                    //
                    // ⚠️ 分隔线画在"不是最后一行"之后，而不是每行前面画一条：
                    // 后者会在卡片顶部多出一条悬空的线（Material3 列表的常见失误）。
                    GameChromeMode.entries.forEachIndexed { index, mode ->
                        LabBgModeRow(
                            title = mode.label,
                            // 漏写说明时退化成空串而不是崩：新增档位的人可能忘了补文案，
                            // 一个空白说明是可接受的降级，运行期崩溃不是。
                            description = GAME_CHROME_DESCRIPTIONS[mode].orEmpty(),
                            selected = chromeMode == mode,
                            // 门控只有总开关一个（与一级页其它子项口径一致）。
                            enabled = masterEnabled,
                            onClick = {
                                scope.launch { repository.setGameChromeMode(mode) }
                            }
                        )
                        if (index != GameChromeMode.entries.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "选择会立即保存并生效；返回后进入贪吃蛇或数独即可看到效果。" +
                    "关闭上方总开关时本项暂不生效，所选档位仍会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabBackgroundScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // ⚠️ 必须直接取 `SettingsRepository`，不能走 `LocalSettingsViewModel`：
    // 那三个（`devLabBackgroundMode` / `devLabBackgroundCustomColor` /
    // 对应的两个 setter）定义在 `SettingsRepository` 上，而
    // `SettingsViewModel` 只暴露了它自己的那几项（`momentsBadgeEnabled` 等），
    // 并没有把仓库的每个属性都转发一遍——这点在编译期就会被抓出来
    // （引用不到方法），不会变成运行期问题。
    //
    // 走 `AppContainer` 的单例（与 `LabScreen.rememberLabBackdrop` 读的是
    // **同一个实例**，因此两处永远同源）。`applicationContext` 再转
    // `MoreCheckApp`：`LocalContext.current` 在 Compose 里通常是 Activity，
    // 直接 `as MoreCheckApp` 会 ClassCastException（本项目多处记录过这个坑）。
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }
    val mode by settingsRepository.devLabBackgroundMode
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.LAB_BG_MODE_RANDOM)
    val customColor by settingsRepository.devLabBackgroundCustomColor
        .collectAsStateWithLifecycle(initialValue = null)

    var showColorPicker by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(LAB_BG_SCREEN_TITLE) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    LabBgModeRow(
                        title = "随机轮换（默认）",
                        description = "每次进入换一条渐变背景",
                        selected = mode == SettingsRepository.LAB_BG_MODE_RANDOM,
                        onClick = {
                            scope.launch {
                                settingsRepository.setDevLabBackgroundMode(
                                    SettingsRepository.LAB_BG_MODE_RANDOM
                                )
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    LabBgModeRow(
                        title = "经典",
                        description = "跟随系统深浅色的纯色背景",
                        selected = mode == SettingsRepository.LAB_BG_MODE_CLASSIC,
                        onClick = {
                            scope.launch {
                                settingsRepository.setDevLabBackgroundMode(
                                    SettingsRepository.LAB_BG_MODE_CLASSIC
                                )
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    LabBgModeRow(
                        title = "自定义",
                        description = "固定颜色，不随深浅色变化",
                        selected = mode == SettingsRepository.LAB_BG_MODE_CUSTOM,
                        onClick = {
                            scope.launch {
                                settingsRepository.setDevLabBackgroundMode(
                                    SettingsRepository.LAB_BG_MODE_CUSTOM
                                )
                            }
                        }
                    )
                    // 自定义色行只在 custom 模式下出现：别模式下它是个"点了没反应"的摆设。
                    if (mode == SettingsRepository.LAB_BG_MODE_CUSTOM) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showColorPicker = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "挑选颜色",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.strongTextColor
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = customColor?.let { "当前：#" +
                                        Integer.toHexString(it).uppercase().padStart(8, '0') }
                                        ?: "尚未选择（默认跟随系统）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                                )
                            }
                            // 色块预览：没有自选色时不画（占位由文字说明承担）。
                            customColor?.let { argb ->
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(argb))
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "设置立即生效：返回实验室页即可看到新背景。" +
                    "自定义颜色在深色模式下同样显示，挑色时请注意深色下的观感。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showColorPicker) {
        LabBgColorPickerDialog(
            initialColor = customColor ?: 0xFF07C160.toInt(),
            onDismiss = { showColorPicker = false },
            onConfirm = { argb ->
                showColorPicker = false
                scope.launch {
                    settingsRepository.setDevLabBackgroundCustomColor(argb)
                    // 顺手把模式切到 custom：挑了颜色却停留在别的模式会让人困惑
                    // （"我选了怎么没变"），直接落到 custom 才符合直觉。
                    settingsRepository.setDevLabBackgroundMode(
                        SettingsRepository.LAB_BG_MODE_CUSTOM
                    )
                }
            }
        )
    }
}

/**
 * 实验室自定义背景的取色弹窗（需求 11b 的「自定义：自行挑选颜色」）。
 *
 * ## 为什么自己写一个，而不是引入取色库
 *
 * 需求只要"自行挑选颜色"，没有要求 HSL 全域取色 / 吸色 / 透明度这类专业能力；
 * 为一个调试入口引入一个取色库违背本项目的依赖纪律。这里的实现 =
 * **8 个预设色 + RGB 三滑块微调**：预设覆盖"我就想要某个熟悉颜色"的绝大多数场景，
 * 滑块覆盖"预设里没有"的剩余场景，全部用 Material3 现成组件拼成，零新依赖。
 *
 * ## 状态策略
 *
 * 弹窗内自持一份 `LocalColor`（从 [initialColor] 起步），**确认时才**回调 [onConfirm]：
 * 取消不产生任何副作用（与"先 hide 再执行动作"的弹窗纪律同源——
 * 中间态不该泄漏到 DataStore）。预览色块实时反映当前合成值。
 *
 * @param initialColor 打开时的初始颜色（ARGB Int）。
 * @param onDismiss 点遮罩 / 返回 / 取消时回调。
 * @param onConfirm 点「确定」时回调，参数为最终选中的 ARGB 颜色。
 */
@Composable
private fun LabBgColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var color by remember { mutableStateOf(initialColor) }

    // 预设色板：取自应用内已认可的色值家族，不拍脑袋新造色——
    // 前六个是主题可选色（AccentColor 的 seed），后两个是本应用"深色页面"的
    // 既有底色（贪吃蛇 / 全局深色窗口底），挑它们做背景都有既有观感可参考。
    val presets = listOf(
        0xFF07C160.toInt(), // 主题绿（默认）
        0xFF1E88E5.toInt(), // 蓝
        0xFF7E57C2.toInt(), // 紫
        0xFFFB8C00.toInt(), // 橙
        0xFFE53935.toInt(), // 红（火焰底）
        0xFF00A6A6.toInt(), // 青
        0xFF0B1820.toInt(), // 深蓝黑（贪吃蛇底）
        0xFFEDEDED.toInt()  // 浅灰（浅色窗口底）
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("挑选背景颜色") },
        text = {
            Column {
                // ---- 预设色板：两行四列 ----
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.take(4).forEach { argb ->
                        PresetColorCell(
                            argb = argb,
                            selected = color == argb,
                            modifier = Modifier.weight(1f),
                            onClick = { color = argb }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.drop(4).forEach { argb ->
                        PresetColorCell(
                            argb = argb,
                            selected = color == argb,
                            modifier = Modifier.weight(1f),
                            onClick = { color = argb }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ---- RGB 三滑块（自绘平滑轨道，与开发者选项滑块同款观感）----
                LabBgChannelSlider(
                    label = "红",
                    channel = (color shr 16) and 0xFF,
                    onValueChange = { v -> color = (color and 0xFF00FFFF.toInt()) or (v shl 16) }
                )
                LabBgChannelSlider(
                    label = "绿",
                    channel = (color shr 8) and 0xFF,
                    onValueChange = { v -> color = (color and 0xFFFF00FF.toInt()) or (v shl 8) }
                )
                LabBgChannelSlider(
                    label = "蓝",
                    channel = color and 0xFF,
                    onValueChange = { v -> color = (color and 0xFFFFFF00.toInt()) or v }
                )

                Spacer(Modifier.height(10.dp))

                // ---- 当前值预览 + hex ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(color))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "#" + Integer.toHexString(color).uppercase().padStart(8, '0'),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 预设色板里的一个色块格子：选中时加主题色描边。
 *
 * 42dp 是"一屏四个仍能分辨色相"的最小尺寸（32dp 以下深浅色块在低亮度屏上难分）；
 * 选中描边 2dp 与「图标更改」的格子同规格（选中语义同一套视觉语言）。
 */
@Composable
private fun PresetColorCell(
    argb: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            )
            .background(Color(argb))
            .clickable(onClick = onClick)
    )
}

/**
 * 取色弹窗里的单通道（R/G/B）滑块行：左侧通道名 + 右侧滑块 + 数值。
 *
 * 用自绘 [SmoothSliderTrack]（无刻度）：0..255 有 256 个取值，默认轨道的刻度
 * 在这个密度下同样会糊成锯齿——与本页其它滑块同款轨道，观感一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabBgChannelSlider(
    label: String,
    channel: Int,
    onValueChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(28.dp)
        )
        AppSlider(
            value = channel.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            // 连续取值（不设 steps）：颜色通道的"档位"没有用户可感知的语义。
            fraction = channel / 255f,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = channel.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

/**
 * 单选行：`RadioButton` + 标题 + 说明，整行可点。
 *
 * 与「我的 → 设置」页的开关行同一套视觉语言（bodyLarge 标题 + labelSmall 说明），
 * 但右侧是 `RadioButton`（单选语义），不是 `Switch`（独立开关语义）——
 * 两者混用会让"这几项是互斥的"变得不可读。
 *
 * 两处使用方：实验室背景的三档（[LabBackgroundScreen]）与游戏颜色模式的三档
 * （[GameChromeScreen]）。刻意不各写一份——本页已经吃过「几份几乎一样的 Row
 * 各自漂移」的亏（见 [DeveloperSwitchRow] 的 KDoc）。
 *
 * @param enabled 是否可交互。`false` 时整行置灰且点不动，但**选项位置照旧显示**——
 *   与 [DeveloperSwitchRow] 的 `checked` / `enabled` 之分完全一致：
 *   「值还在、只是暂不生效」必须能自解释。
 */
@Composable
private fun LabBgModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                // 只对左侧文字乘 alpha，理由见 [DISABLED_CONTENT_ALPHA]：
                // 整行套 alpha 会让已经被 Material3 调淡的 RadioButton 被双重调淡。
                .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        // `onClick = null`：点击由整行的 clickable 处理，避免"点文字不响应、点圆圈才响应"。
        // `enabled` 同时传给 RadioButton：置灰时圆圈本身也要变淡，否则只有文字变淡、
        // 圆圈还亮着，看起来像"还能点"。
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

/**
 * 「实验室背景」当前档位的中文名（2026-10-23 需求 5：入口行右侧外显）。
 *
 * 三档与 [SettingsRepository] 里的字符串键一一对应；未知值回落「随机轮换」
 * （那是默认档，也是 `devLabBackgroundMode` 的默认值）。
 */
private fun labBgModeLabel(mode: String): String = when (mode) {
    SettingsRepository.LAB_BG_MODE_CLASSIC -> "跟随系统"
    SettingsRepository.LAB_BG_MODE_CUSTOM -> "自定义"
    else -> "随机轮换"
}

/**
 * 「入口管理」（2026-11-14）：发现页与实验室的入口可以互相搬迁，
 * 并在这里切「官网 / 搜一搜」。两项都是**内容配置**，不受开发者选项总开关门控，
 * 与 `linkOpenInApp` 同口径；这里只是把编辑入口收在开发者选项里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryPlacementScreen(
    masterEnabled: Boolean,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    val searchMode by repository.discoverSearchMode
        .collectAsStateWithLifecycle(initialValue = false)
    val overridesRaw by repository.entryPlacementOverrides
        .collectAsStateWithLifecycle(initialValue = "")
    val overrides = remember(overridesRaw) { decodeEntryPlacement(overridesRaw) }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("入口管理") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "发现页官网位",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "当前：${if (searchMode) "搜一搜（百度首页）" else "官网（www.scif.top）"}。" +
                            "点右侧按钮切换，标题与图标一起变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    DeveloperActionRow(
                        title = "显示模式",
                        description = "点一下切换",
                        trailing = TrailingStyle.Navigation,
                        enabled = masterEnabled,
                        valueText = if (searchMode) "搜一搜" else "官网",
                        onAction = {
                            scope.launch {
                                repository.setDiscoverSearchMode(!searchMode)
                                onMessage(
                                    if (searchMode) "发现页该位置已改为官网" else "发现页该位置已改为搜一搜"
                                )
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Text(
                text = "入口归属",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
            Card(Modifier.fillMaxWidth()) {
                MOVABLE_ENTRIES.forEachIndexed { index, entry ->
                    val current = effectivePlacement(entry.key, overrides)
                    val next = if (current == EntryPlacement.DISCOVER) {
                        EntryPlacement.LAB
                    } else {
                        EntryPlacement.DISCOVER
                    }
                    DeveloperActionRow(
                        title = entry.title,
                        description = "默认在" +
                            if (entry.defaultPlacement == EntryPlacement.DISCOVER) {
                                "发现页 · 点一下移到实验室"
                            } else {
                                "实验室 · 点一下移到发现页"
                            },
                        trailing = TrailingStyle.Navigation,
                        enabled = masterEnabled,
                        valueText = if (current == EntryPlacement.DISCOVER) "发现页" else "实验室",
                        onAction = {
                            val updated = overrides.toMutableMap()
                            updated[entry.key] = next
                            scope.launch {
                                repository.setEntryPlacementOverrides(encodeEntryPlacement(updated))
                                onMessage(
                                    "${entry.title} 已移到" +
                                        if (next == EntryPlacement.DISCOVER) "发现页" else "实验室"
                                )
                            }
                        }
                    )
                    if (index != MOVABLE_ENTRIES.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "入口搬迁不会改变路由：老桌面快捷方式、检查更新、意见反馈等入口照常可用；" +
                    "音乐入口搬到哪一页都会先弹确认框。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
