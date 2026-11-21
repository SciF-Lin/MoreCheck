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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.morecheck.di.appContainer
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.theme.strongTextColor
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
 * ## 五个分区的行序（需求 2 归类 + 需求 8 新增分区之后）
 *
 * 从上到下五张卡片，顺序与 `DeveloperSectionTitle` 的调用顺序一致：
 *
 * 1. **登录测试** —— 1 行：登录测试（入口，含验证码测试 / 通知测试）
 * 2. **性能测试** —— 启用性能测试（开关）→ 开屏显示时长（滑块）
 *    → 左右滑动动画（开关）→ 开屏时长随机浮动（滑块）
 * 3. **图标测试** —— 设备信息查询（入口）→ 朋友圈红点提示（开关）
 *    → 图标更改(实验性)（入口）
 * 4. **实验室测试**（需求 2 新增）—— 实验室背景设置（入口）
 *    → 游戏标题栏显示（开关）→ 游戏开屏显示（入口）→ 游戏颜色模式（入口）
 * 5. **界面测试**（需求 8 新增）—— 打卡完成动画（开关）→ 页面转场动画（开关）
 *    → 按压反馈（开关）→ 日期显示格式（入口，内含三选一二级页）
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

    // 子项的**原始值**（仅用于回显，不代表是否生效）。
    // initialValue 给 false：DataStore 首次读取要走磁盘，先渲染成"关"再被真实值覆盖，
    // 比给 true 安全——真值是"开"时顶多闪一下，而给 true 会在真值是"关"时出现
    // "开关自己弹回去"的假象。
    val codeTestSwitch by repository.codeTestSwitch.collectAsStateWithLifecycle(initialValue = false)
    val perfTestSwitch by repository.perfTestSwitch.collectAsStateWithLifecycle(initialValue = false)

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

    // 「左右滑动动画」开关（需求 11a）的原始值。
    // 注意默认是 true：这个开关关掉的是"本来就有的动画"，默认开 = 与改造前一致。
    val tabSlideAnimSwitch by repository.tabSlideAnimSwitch.collectAsStateWithLifecycle(
        initialValue = true
    )

    // 「游戏标题栏显示」/「游戏开屏显示」的**总开关原始值**（2026-10-22 需求 10：
    // 两项改成「入口行 + 右侧总开关」，箭头左侧是总开关）。
    // 一级页只回显原始值，子开关与合成规则都在仓库层 / 二级页里。
    val gameTitleBarTotal by repository.gameTitleBarSwitch
        .collectAsStateWithLifecycle(initialValue = true)
    val gameSplashTotal by repository.gameSplashSwitch
        .collectAsStateWithLifecycle(initialValue = true)

    // 「游戏标题栏显示」一级页入口（本轮需求 5 起是入口行，开关本体在二级页）。
    // 一级页不再读它的原始值——总开关的原始值已由 `masterEnabled` 表达，
    // 二级页自己订阅两个子开关。

    // 「游戏设置」总开关（本轮需求 3）的原始值。默认开。
    val gameSettingsSwitch by repository.gameSettingsSwitch.collectAsStateWithLifecycle(
        initialValue = true
    )

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

    // 「日期显示格式」二级页是否正显示着（与其它二级页同一套就地覆盖）。
    var showDateFormat by remember { mutableStateOf(false) }

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
    // 已经确立了**就地覆盖**这个做法（见那个文件的注释），这里沿用同一套，
    // 好处是「图标更改」完全不依赖导航层，也不需要改任何导航文件。
    // 系统返回键由 `IconPickerScreen` 内部的 `BackHandler` 接住。
    var showIconPicker by remember { mutableStateOf(false) }

    // 「设备信息查询」是否正显示着（同样的就地覆盖）。
    //
    // 它没有自己的持久状态：整页是**纯只读的规格清单**，唯一的状态是页面内部的
    // 「按压缩放演示」那一个动画值，跟着二级页一起创建 / 销毁即可。
    var showUiInspector by remember { mutableStateOf(false) }

    // 「开发者选项背景设置」二级页（需求 11b）是否正显示着。
    // 与其它二级页同一套就地覆盖：不加导航路由，返回键由二级页自己的 BackHandler 接住。
    var showLabBackground by remember { mutableStateOf(false) }

    // 「游戏开屏显示」二级页（本轮需求 2）是否正显示着。
    //
    // 需求 2 原文：「游戏开屏显示改为二级界面入口，可单独设置贪吃蛇与数独的显示」。
    // 因此它从开关变成**入口**：本页只留一行入口（见下方「实验室测试」分区），
    // 真正的两个游戏开关搬进 [GameSplashScreen] 二级页。
    //
    // 与另外几个二级页同一套就地覆盖（`SecondaryPageEnterHost`），
    // 返回键由二级页自己的 `BackHandler` 接住。
    var showGameSplash by remember { mutableStateOf(false) }

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

    // 「样式设置」二级页（2026-10-22 需求 8）：本轮新增视觉项的开关集合。
    var showStyleSettings by remember { mutableStateOf(false) }

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

    // ── 是否有二级页正显示着 ────────────────────────────────────────────────
    //
    // 只用来给 [SecondaryPageEnterHost] 的 `visible` 传参；**不再**用来隐藏一级页标题栏
    // （见下方「整个页面包在 Box 里」那段注释：那一版会让标题栏先消失 220ms 再出现）。
    val secondaryVisible = showLoginTest || showIconPicker || showUiInspector ||
        showLabBackground || showGameSplash || showGameChrome || showDateFormat ||
        showGameTitleBar || showStyleSettings

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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
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
                        // 只在"关闭总开关 + 确实有子项处于开的位置"时提示：
                        // 全关状态下说这句就是噪音。这里读的是上面那几个原始值流，
                        // 回调执行时读到的就是此刻的最新值。
                        //
                        // ⚠️ 判断条件里刻意**不含**「朋友圈红点提示」：它的默认值是 `true`，
                        // 把它算进来就等于"每次关总开关都弹这句提示"，反而成了噪音。
                        // 那句提示只在"用户自己开过某项、现在被暂时压住"时才有信息量；
                        // 而这里只需保证条件里不出现已删除的 `iconTestSwitch`（那会编译不过）。
                        if (!enabled && (codeTestSwitch || perfTestSwitch)) {
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

            // ---- 登录测试 ---------------------------------------------------
            // 唯一有真实作用的板块：它的值会被登录 / 注册的「发送验证码」流程读到
            // （`DeveloperOptionsRepository.isCodeTestEnabledNow()`）。
            //
            // ⚠️ 本轮改成**一行入口**（用户要求：「验证码测试」与「通知测试」挪进二级界面）。
            // 一级页只留这一行，两项子设置全部搬进 [LoginTestScreen]——本板块本身
            // 仍然是「入口 + 无开关」的形态，所以这里不需要 `DeveloperSwitchRow`。
            DeveloperSectionTitle("登录测试")
            Card(Modifier.fillMaxWidth()) {
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
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 性能测试 ---------------------------------------------------
            DeveloperSectionTitle("性能测试")
            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    title = "启用性能测试",
                    description = PERF_TEST_DESCRIPTION,
                    checked = perfTestSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setPerfTestEnabled(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 开屏显示时长（需求：以滑块形式在 0~10 秒间调节，可一键恢复默认）----
                //
                // 门控：**两个开关都开才能拖**（总开关 + 性能测试），见下方 KDoc 与
                // `DeveloperOptionsRepository.splashHoldDurationMs` 的说明。
                // 不满足时滑块置灰、**但仍显示当前值**——「值还在、只是暂不生效」这条口径
                // 与别的子项完全一致，不能让用户以为设置被清空了。
                SplashHoldRow(
                    holdMs = splashHoldMs,
                    // 「随机浮动」勾选框（2026-10-23 需求 8）：勾上后实际时长 = 设定值 ± 0.3 秒。
                    // 它取代了原先那条"浮动范围"滑块（需求 8 明确删除）。
                    jitterEnabled = splashJitterEnabled,
                    enabled = masterEnabled && perfTestSwitch,
                    onValueChange = { scope.launch { repository.setSplashHoldMs(it) } },
                    onJitterChange = { scope.launch { repository.setSplashJitterEnabled(it) } },
                    onRestoreDefault = {
                        scope.launch { repository.setSplashHoldMs(SettingsRepository.DEFAULT_SPLASH_HOLD_MS) }
                    }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 实验室测试（本轮新增分区）----------------------------------
            //
            // 需求原文：「开发者选项 归类图标测试中与实验室和游戏有关的功能，
            // 小标题为『实验室测试』」。本分区就是这条需求的落地。
            //
            // ## 为什么这四行归到「实验室测试」而不是留在「图标测试」
            //
            // 用**它实际改变的对象**做判据，而不是看它"挂在哪个页面里"：
            //
            // | 行 | 实际改变的对象 | 判定 |
            // |---|---|---|
            // | 实验室背景设置 | 实验室页（发现 → 实验室）的背景 | 实验室 ✓ |
            // | 游戏标题栏显示 | 贪吃蛇 / 数独两页的 `TopAppBar` | 游戏 ✓ |
            // | 游戏开屏显示 | 贪吃蛇 / 数独两页的 Hello World 开屏层 | 游戏 ✓ |
            // | 游戏颜色模式 | 贪吃蛇 / 数独两页的标题栏 / 状态栏 / 开屏底色 | 游戏 ✓ |
            //
            // 留在「图标测试」的三行则改的是**应用图标本身**或**图标上的红点**：
            // 「图标更改」换桌面图标、「朋友圈红点提示」管底栏角标、
            // 「设备信息查询」是 UI 规格清单。按同一条判据，它们都留在原处。
            //
            // ⚠️ 「实验室背景设置」在上一轮曾被**移到「图标测试」卡片的最顶部**，
            // 但那一次只是行序微调（顶部落"设置类"动作），并没有语义依据：
            // 它的标题里就写着「实验室」，KDoc 也明说作用于实验室页的背景
            // （需求原文「开发者选项背景设置选项」里的"开发者选项背景"
            // 指的正是实验室页那个按规则随机轮换的背景）。因此本轮按语义归位。
            //
            // ## 行序：实验室在前、游戏在后
            //
            // 「实验室背景设置」是实验室页自己的外观，排在前面；
            // 后面三行是同一组游戏设置（标题栏 / 开屏 / 配色），相邻摆放用户找得到——
            // 三行都是"两个游戏页共用同一个值"，是同一个心智模型。
            DeveloperSectionTitle("实验室测试")
            Card(Modifier.fillMaxWidth()) {
                // ---- 游戏设置总开关（本轮需求 3）--------------------------------
                //
                // 需求原文：「游戏标题栏与开屏显示增加总开关，外显在开发者选项页」。
                // 它是「游戏标题栏显示」「游戏开屏显示」「游戏颜色模式」三行的**上一级**总门：
                // 关掉它，三行里的子开关无论怎么拨都不生效（生效合成见
                // `DeveloperOptionsRepository.gameSettingsEnabled` 及各游戏子项的流），
                // 但各子开关**保存的值不清空**——重新打开后按原样恢复
                // （与本页其它受门控子项的既定口径一致）。
                //
                // 门控只有开发者选项总开关一级（它自己就是"游戏那一组"的总门）。
                DeveloperSwitchRow(
                    title = GAME_SETTINGS_TITLE,
                    description = GAME_SETTINGS_DESCRIPTION,
                    checked = gameSettingsSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setGameSettingsEnabled(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

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
                    // 2026-10-23 需求 4：「启用游戏设置」关闭时，除「实验室背景」以外的
                    // 三行都变灰（不可点）。它自己那一行（总开关）仍只受开发者选项总开关门控。
                    enabled = masterEnabled && gameSettingsSwitch,
                    onAction = { showGameTitleBar = true },
                    switchChecked = gameTitleBarTotal,
                    onSwitchChange = { scope.launch { repository.setGameTitleBar(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 游戏开屏显示（需求 2：开关改为二级界面入口）----------------
                //
                // 需求 2 原文：「游戏开屏显示改为二级界面入口，可单独设置
                // 贪吃蛇与数独的显示」。因此它不再是一个开关，而是一行**入口**
                // （点开进 [GameSplashScreen] 二级页），进去分别拨两个游戏的开屏。
                //
                // 默认两个都开 = 与改造前一致。
                DeveloperActionRow(
                    title = GAME_SPLASH_TITLE,
                    // 需求 14：本分区不再显示副标题备注（只留标题）。
                    description = "",
                    trailing = TrailingStyle.NavigationWithSwitch,
                    enabled = masterEnabled && gameSettingsSwitch,
                    onAction = { showGameSplash = true },
                    switchChecked = gameSplashTotal,
                    onSwitchChange = { scope.launch { repository.setGameSplash(it) } }
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
                    enabled = masterEnabled && gameSettingsSwitch,
                    // 需求 5：入口行右侧外显当前档位（截图里的红色「跟随系统」）。
                    valueText = gameChromeModeValue.label,
                    onAction = { showGameChrome = true }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 界面测试（需求 8，本轮新增分区）-------------------------
            //
            // 四项都是**全局界面行为**：既不属于实验室（它们不只作用于实验室页），
            // 也不属于图标（它们不改任何图标）——因此单独一个分区，放在「实验室测试」之后。
            //
            // ## 三个开关为什么默认都是 true
            //
            // 本项目反复强调的一条纪律：**新增开关的默认值必须等于改造前的既有行为**。
            // 四项里三项的默认都是 true（撒花 / 转场 / 按压反馈本来就开着），
            // 若默认关掉，等于用"新增一个设置项"悄悄改掉了所有人熟悉的观感。
            // 默认值写在 `SettingsRepository.Keys` 上，那里有完整说明。
            //
            // ## 三选一那一项为什么做成二级页而不是本页的单选框
            //
            // 与「游戏颜色模式」完全同构的理由：三档互斥且各带说明文字，
            // 一级页那一张卡片塞不下三个 `RadioButton` 行；塞进去会让这张卡变成
            // 全页行数最多的卡片，用户滚动成本变高。做成二级页后一级页只留一行入口。
            //
            // 行序：三个开关（越基础越靠前）+ 一个入口。
            DeveloperSectionTitle("界面测试")
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

                // ---- 日期显示格式（三选一入口）------------------------------
                //
                // 与「游戏颜色模式」同构：入口行 + 二级页三选一。
                // ⚠️ 回显读**原始值**（`dateFormatSwitch`，已解析成枚举），
                // 关掉总开关时本行只是点不动、单选框仍停在用户选的那一档。
                DeveloperActionRow(
                    title = DATE_FORMAT_TITLE,
                    description = DATE_FORMAT_DESCRIPTION_ENTRY,
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    onAction = { showDateFormat = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 样式设置（2026-10-22 需求 8）---------------------------
                //
                // 这一轮改了几处**纯观感**的东西（渐变按钮 / 药丸底栏 / 深色今日 /
                // 小米滑块 / 日历默认视图），用户要求把它们做成开关，好逐项比对。
                // 做成二级页而不是在本卡片里再塞五个开关：本卡片已经有四行，
                // 再加五行会让它变成全页最长的一张卡（与「日期显示格式」同一条理由）。
                DeveloperActionRow(
                    title = STYLE_SETTINGS_TITLE,
                    description = STYLE_SETTINGS_DESCRIPTION_ENTRY,
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    onAction = { showStyleSettings = true }
                )
            }

            // ---- 图标测试 ------------------------------------------------------
            //
            // ## 本轮（需求 2）把实验室 / 游戏相关行整体迁到「实验室测试」分区
            //
            // 需求原文：「开发者选项 归类图标测试中与实验室和游戏有关的功能，
            // 小标题为『实验室测试』」。因此这里**只留真正与图标 / 红点有关**的行：
            //
            //   设备信息查询（入口）→ 朋友圈红点提示（开关）→ 图标更改(实验性)（入口）
            //
            // 被迁走的三行（实验室背景设置 / 游戏标题栏显示 / 游戏开屏显示 /
            // 游戏颜色模式）见下方「实验室测试」分区。三条判据见那处的注释。
            //
            // ⚠️ 「设备信息查询」留在本分区：它是 UI 规格清单（按钮 / 颜色 / 动画），
            // 与「图标测试」里的「朋友圈红点提示」同属"界面观感"这一类，
            // 而不属于实验室或游戏的功能开关（它连设置都不改）。
            //
            // 行序：两个入口 + 一个开关，用分隔线断开；「图标更改」放最底部是上一轮
            // 定的口径（防误触：它会立刻改写桌面图标），本轮不变。
            DeveloperSectionTitle("图标测试")
            Card(Modifier.fillMaxWidth()) {
                // ---- 设备信息查询 --------------------------------------------
                //
                // 需求原文：「显示本 app 使用的按钮、动画、颜色等前端 UI 的配置与名称……
                // 方便后续调试时沟通」。因此它是一行**入口**（进 [UiInspectorScreen]），
                // 不是一个开关——它不改任何设置，只把项目里真实的 UI 规格摊开给人看。
                //
                // 右侧是右箭头（与其它入口行逐字同构），点整行进 [UiInspectorScreen]。
                DeveloperActionRow(
                    title = UI_INSPECTOR_TITLE,
                    description = UI_INSPECTOR_DESCRIPTION,
                    trailing = TrailingStyle.Navigation,
                    enabled = masterEnabled,
                    onAction = { showUiInspector = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

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
                    onAction = { showIconPicker = true }
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

            // ---- 页脚说明 ---------------------------------------------------
            // 只解释"总开关与子项的关系"这条最容易误解的规则，不引入任何新功能。
            // 与 PermissionsScreen / SoundSettingsScreen 一样收在页面末尾，
            // 让用户在读完开关后顺手看到，而不是一进页面就被一段说明挡住。
            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "子项只在总开关开启时生效；关闭总开关不会清空它们的设置，" +
                    "重新打开后按原样恢复。性能测试下的两个滑块" +
                    "还需要「启用性能测试」也打开才会生效；图标更改执行时立即作用于桌面图标。" +
                    "「实验室测试」下的「游戏标题栏显示」点进去可分别设置贪吃蛇 / 数独 / 数独2 的标题栏；" +
                    "「游戏开屏显示」点进去可分别设置两个游戏；" +
                    "「游戏颜色模式」点进去可选跟随系统 / 深色经典 / 深色适配三档，两个游戏页共用。" +
                    "「界面测试」下的四项都是全局生效；「日期显示格式」点进去可选" +
                    "中文完整 / 横杠式 / 省略年份三档。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

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
        SecondaryPageEnterHost(visible = secondaryVisible) {
            when {
                showLoginTest -> LoginTestScreen(
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

                showIconPicker -> IconPickerScreen(
                    // 门控只有一个总开关（图标测试没有独立子开关了，见上方 KDoc 的门控表）。
                    enabled = masterEnabled,
                    onBack = { showIconPicker = false },
                    onMessage = onMessage
                )

                showUiInspector -> UiInspectorScreen(
                    onBack = { showUiInspector = false }
                )

                showLabBackground -> LabBackgroundScreen(
                    onBack = { showLabBackground = false }
                )

                showGameSplash -> GameSplashScreen(
                    // 门控口径与一级页其它子项一致：总开关关时本页两行置灰。
                    masterEnabled = masterEnabled,
                    onBack = { showGameSplash = false }
                )

                showGameChrome -> GameChromeScreen(
                    // 门控口径与一级页其它子项一致：总开关关时三档单选框置灰。
                    masterEnabled = masterEnabled,
                    onBack = { showGameChrome = false }
                )

                // 本轮需求 5：「游戏标题栏显示」二级页（贪吃蛇 / 数独各一个子开关）。
                showGameTitleBar -> GameTitleBarScreen(
                    masterEnabled = masterEnabled,
                    onBack = { showGameTitleBar = false }
                )

                // 需求 8：「日期显示格式」三选一（与 GameChromeScreen 同一套形态）。
                showStyleSettings -> StyleSettingsScreen(
                    masterEnabled = masterEnabled,
                    onBack = { showStyleSettings = false }
                )

                showDateFormat -> DateFormatScreen(
                    // 回显传**原始值**（不是与过总开关的"生效值"），
                    // 关掉总开关时三行置灰但单选框仍停在用户选的那一档。
                    selected = dateFormatSwitch,
                    masterEnabled = masterEnabled,
                    onSelect = { format -> scope.launch { repository.setDateFormat(format) } },
                    onBack = { showDateFormat = false }
                )
            }
        }
    }
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
private fun DeveloperSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    )
}

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
                // 取 104dp：能舒服容纳「发送通知」（四个汉字 @ labelLarge 约 64dp + 默认左右内边距），
                // 又不会把左侧说明文字挤得太窄（说明文案允许换行，见上面的 `weight(1f)`）。
                modifier = Modifier.width(ACTION_BUTTON_WIDTH)
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
 * @param jitterEnabled 是否开启「随机浮动」（需求 8：实际时长 = 设定值 ± 0.3 秒）。
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
    modifier: Modifier = Modifier
) {
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
                // 档数 = 0..10000 共 101 档 → steps = 99（见 SPLASH_HOLD_STEPS）。
                steps = SPLASH_HOLD_STEPS,
                enabled = enabled,
                fraction = (holdMs - SettingsRepository.MIN_SPLASH_HOLD_MS).toFloat() /
                    (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS),
                // 需求 6：读数在滑动条**右端**，因此滑块要 weight 而不是 fillMaxWidth。
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            // 当前值：固定「N.N 秒」宽度稳定，拖动时不抖。
            Text(
                text = formatHoldSeconds(holdMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
                    .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ---- 随机浮动（需求 8）------------------------------------------
            //
            // 勾上后每次开屏时长 = 设定值 ± 0.3 秒（实现见
            // `DeveloperOptionsRepository.splashHoldDurationMs`）。整行可点，
            // 不只是那个小方块——勾选框的点击区实在太小。
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onJitterChange(!jitterEnabled) }
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
                    title = "验证码测试",
                    description = CODE_TEST_DESCRIPTION,
                    // ★ 原始值（不是与过总开关的 `codeTestEnabled`），理由见文件顶部 KDoc。
                    checked = codeTestSwitch,
                    // 门控只有总开关一个（与改造前逐字相同）。
                    enabled = masterEnabled,
                    onCheckedChange = onCodeTestChange
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
            }

            // 与一级页的页脚同一句口径说明：本页两个子项也只受总开关门控。
            // 放在这里而不是省掉：用户可能直接从一级页点进来，没读那一页的页脚。
            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "两项都只在总开关开启时生效；关闭总开关不会清空它们的设置，" +
                    "重新打开后按原样恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 把毫秒格式化成「N.N 秒」。
 *
 * 一律保留一位小数（`3.0 秒` 而不是 `3 秒`）：档位是 0.1 秒（见 `SPLASH_HOLD_STEPS`），
 * 位数固定时数字宽度不会跳动——`3 秒` 与 `10 秒` 之间来回拖会让右侧文字左右抖动，很难看。
 *
 * 用整数运算（`ms / 1000` 与 `ms % 1000 / 100`）而不是浮点除法 + 格式化：
 * 浮点在这里没有任何精度收益，却要多引入一个 `String.format` 的 Locale 问题
 * （某些区域会把小数点写成逗号），这是个显示给用户看的读数，稳定比花哨重要。
 */
private fun formatHoldSeconds(ms: Int): String {
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
    "仅用于测试：开启后，点击「发送验证码」不再走真实短信，而是在后台随机生成六位数字，" +
        "以通知形式发给你；只有填写与记录的验证码一致才能登录成功。关闭时走已接入的真实后端。"

/** 「性能测试」的说明文案：写清它现在**真的会改变开屏时长**，不再只是动画。 */
private const val PERF_TEST_DESCRIPTION =
    "开启后，下方的开屏显示时长设置才会生效"

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
    "控制开屏停留时长，0~10 秒；需开启上方「启用性能测试」后才可调节"

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
    "可切换内置桌面图标，或上传图片裁切后用于应用内显示；受 Android 限制，" +
        "上传的图片无法作为桌面图标"

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
private const val LOGIN_TEST_DESCRIPTION =
    "登录已接入真实后端；此开关仅保留用于测试（模拟验证码 / 测试通知）"

/**
 * 「设备信息查询」入口行的标题。
 *
 * 用户给这一项起的名字就是「设备信息查询」，直接沿用（虽然页面里列的主要是 UI 规格
 * 而不是硬件信息）——需求里点名的字面量不擅自改写，否则用户按名字找不到。
 */
private const val UI_INSPECTOR_TITLE = "设备信息查询"

/**
 * 「设备信息查询」入口行的说明文案。
 *
 * 点明它是**只读的规格清单**：不写的话，用户（或下一个开发者）会以为这是个能改
 * 按钮尺寸 / 颜色的调试开关，点进去发现都不能改，会当成"功能没做完"。
 */
private const val UI_INSPECTOR_DESCRIPTION =
    "只读地列出按钮尺寸、圆角、颜色与动画参数（附来源文件），用于沟通 UI 规格"

/**
 * 「朋友圈红点提示」开关的标题。
 *
 * 用「红点提示」而不是「未读角标」：底部导航栏那个东西在用户嘴里就是"红点"
 * （数字也只是红点里的一行字），与本项目其他页面的口语化命名一致。
 */
private const val MOMENTS_BADGE_TITLE = "朋友圈红点提示"

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
    "关闭后不再显示未读红点与数字"

/**
 * 「通知测试」按钮的说明文案。
 *
 * 说清「发一条什么内容」和「为什么要发」：用户按这个按钮的动机就是
 * 「我到底能不能收到通知」，所以文案要直接告诉他通知里会出现什么字样，
 * 好让他去通知栏核对。
 */
private const val NOTIFY_TEST_DESCRIPTION =
    "点一下立刻发一条内容为「Hello World」的通知，用来确认通知权限是否已开启"

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
private val ACTION_BUTTON_WIDTH = 104.dp

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
    "底部四个入口切换时页面跟随手指位移、新旧两页整屏滑移的动画；关闭后改为直接切换"

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

/** 「游戏开屏显示」入口行的标题（需求 2：开关改为二级界面入口）。 */
private const val GAME_SPLASH_TITLE = "游戏开屏显示"

/**
 * 「游戏开屏显示」入口行的说明文案（需求 2 起它是入口而不是开关）。
 *
 * 点明「进去可以分别设置贪吃蛇与数独」——用户看这一行就该知道，
 * 关掉一个游戏的开屏不影响另一个。
 */
private const val GAME_SPLASH_DESCRIPTION_ENTRY =
    "进入可分别设置贪吃蛇与数独的 Hello World 开屏层；关闭后直接进入游戏"

/** 「游戏标题栏显示」入口行的标题（本轮需求 5：开关改为二级界面入口）。 */
private const val GAME_TITLE_BAR_TITLE = "游戏标题栏显示"

/** 「游戏设置」总开关的标题（本轮需求 3）。 */
private const val GAME_SETTINGS_TITLE = "启用游戏设置"

/**
 * 「游戏设置」总开关的说明文案（本轮需求 3）。
 *
 * 三件事：管的是**哪几行**（标题栏 / 开屏 / 颜色）、关掉会怎样（子开关暂不生效但不清空）、
 * 与开发者选项总开关的关系（它也要开着）。
 */
private const val GAME_SETTINGS_DESCRIPTION =
    "贪吃蛇与数独的标题栏、开屏与颜色设置的总体开关；关闭后这些设置暂不生效" +
        "（恢复默认观感），重新打开后按原样恢复"

/**
 * 「游戏标题栏显示」入口行的说明文案（本轮需求 5 起它是入口而不是开关）。
 *
 * 与「游戏开屏显示」入口同一口径：点明「进去可分别设置两个游戏」、
 * 点明总开关仍保留（关掉它两个游戏都不显示，二级页里的子开关不再生效）。
 */
private const val GAME_TITLE_BAR_DESCRIPTION_ENTRY =
    "进入可分别设置贪吃蛇与数独的标题栏显隐；此处总开关关闭则两个都不显示"

/** 「游戏标题栏显示」二级页的标题（本轮需求 5）。 */
private const val GAME_TITLE_BAR_SCREEN_TITLE = "游戏标题栏显示"

/** 「游戏标题栏显示」二级页里贪吃蛇那一行的标题。 */
private const val GAME_TITLE_BAR_SNAKE_TITLE = "贪吃蛇标题栏"

/**
 * 「游戏标题栏显示」二级页里贪吃蛇那一行的说明。
 * 点明关掉后怎么返回（标题栏上有返回键，关掉后只剩系统返回键）。
 */
private const val GAME_TITLE_BAR_SNAKE_DESCRIPTION =
    "关闭后进入贪吃蛇不再显示顶部标题栏（含返回键），返回请使用系统返回键"

/** 「游戏标题栏显示」二级页里数独那一行的标题。 */
private const val GAME_TITLE_BAR_SUDOKU_TITLE = "数独标题栏"

/** 「游戏标题栏显示」二级页里数独那一行的说明（同贪吃蛇那行的口径）。 */
private const val GAME_TITLE_BAR_SUDOKU_DESCRIPTION =
    "关闭后进入数独不再显示顶部标题栏（含返回键），返回请使用系统返回键"

/** 「游戏标题栏显示」二级页里数独2 那一行的标题（2026-10-22 补）。 */
private const val GAME_TITLE_BAR_SUDOKU2_TITLE = "数独2 标题栏"

/** 「游戏标题栏显示」二级页里数独2 那一行的说明（同另两行的口径）。 */
private const val GAME_TITLE_BAR_SUDOKU2_DESCRIPTION =
    "关闭后进入数独2 不再显示顶部标题栏（含返回键），返回请使用系统返回键"

/** 「开发者选项背景设置」入口行的标题（需求 11b）。 */
private const val LAB_BG_TITLE = "实验室背景"

/**
 * 「开发者选项背景设置」入口行的说明文案。
 *
 * 把三档模式写全（默认随机轮换 / 经典跟随系统 / 自定义颜色），
 * 用户不用点进去就知道自己要的是哪一档。
 */
private const val LAB_BG_DESCRIPTION =
    "默认随机轮换 / 经典跟随系统深浅色 / 自定义固定颜色"

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
    "主页打卡成功时播放的彩带动画；关闭后打卡正常进行，只是没有动画反馈"

/** 「页面转场动画」开关的标题。 */
private const val PAGE_TRANSITION_TITLE = "页面转场动画"

/**
 * 「页面转场动画」的说明。
 *
 * 点明它是"应用内页面之间"的推入/弹出（底部导航四个入口之间的切换**不受**它影响），
 * 否则用户会以为连底栏切换动画也被关掉了——那会是一个他没要求的副作用。
 */
private const val PAGE_TRANSITION_DESCRIPTION =
    "打开应用内的二级页面时从侧面推入、返回时推出的动画；底部四个入口之间的切换不受影响"

/** 「按压反馈」开关的标题。 */
private const val PRESS_FEEDBACK_TITLE = "按压反馈"

/**
 * 「按压反馈」的说明。
 *
 * 点明它是"按下即缩放 + 变淡"、且**全应用统一**（不是某个页面的），
 * 让用户知道关掉之后所有可点元素的手感会一起变。
 */
private const val PRESS_FEEDBACK_DESCRIPTION =
    "点击按钮、列表行等可点元素时按下即缩放并变淡的反馈，全应用统一生效"

/** 「日期显示格式」入口行的标题。 */
private const val DATE_FORMAT_TITLE = "日期显示格式"

/**
 * 「日期显示格式」入口行的说明。
 *
 * 一行里把三档**全列出来**（与「游戏颜色模式」那条说明同一口径）：
 * 用户不点进去就知道自己要的是哪一档，而"三档可选"这种空话没有信息量。
 */
private const val DATE_FORMAT_DESCRIPTION_ENTRY =
    "2026年10月19日 周一 / 2026-10-19 周一 / 10月19日 周一"

/** 「样式设置」入口行标题（2026-10-22 需求 8）。 */
private const val STYLE_SETTINGS_TITLE = "样式设置"

/** 「样式设置」入口行说明：把五项一句话列出来，用户不点进去就知道有什么。 */
private const val STYLE_SETTINGS_DESCRIPTION_ENTRY =
    "渐变按钮 / 药丸底栏 / 深色今日 / 日历默认视图 / 小米滑块"

/** 「样式设置」二级页的页首说明。 */
private const val STYLE_SETTINGS_HINT =
    "这里只影响观感，不影响功能。每一项都能立刻看到差别，改完返回一两次即可确认。"

private const val STYLE_GRADIENT_TITLE = "渐变边框按钮"
private const val STYLE_GRADIENT_DESCRIPTION =
    "实验室底部「新增功能」按钮用蓝紫渐变描边；关闭后回落普通实心按钮"

private const val STYLE_PILL_TABBAR_TITLE = "药丸底栏"
private const val STYLE_PILL_TABBAR_DESCRIPTION =
    "热搜页底部导航用白色胶囊 + 橙色选中丸；关闭后回落普通等宽文字栏"

private const val STYLE_SOLID_TODAY_TITLE = "深色今日标记"
private const val STYLE_SOLID_TODAY_DESCRIPTION =
    "日历里「今天」用实心主色块标出（白字）；关闭后回落淡色底 + 主色字"

private const val STYLE_WEEK_DEFAULT_TITLE = "日历默认周视图"
private const val STYLE_WEEK_DEFAULT_DESCRIPTION =
    "打开统计页时日历默认收起成一周；关闭则默认展开整月（可随时拖动底部横条切换）"

private const val STYLE_XIAOMI_SLIDER_TITLE = "小米风格滑块"
private const val STYLE_XIAOMI_SLIDER_DESCRIPTION =
    "滑块改用粗轨道 + 白色圆点手柄；关闭后回落 Material 默认的细轨道样式"

/** 「日期显示格式」二级页的标题。 */
private const val DATE_FORMAT_SCREEN_TITLE = "日期显示格式"

/**
 * 「日期显示格式」二级页的页首说明。
 *
 * 写清两件事：① 影响的是**哪几处**（主页 / 打卡记录 / 日历），
 * ② **不影响**哪些地方（月份标题、时间点如 08:30、备份文件名）——
 * 第 ② 点尤其重要，否则用户会以为"备份文件名前面那个 20261019 也会变"。
 */
private const val DATE_FORMAT_SCREEN_HINT =
    "影响主页、打卡记录与日历里显示的完整日期；" +
        "月份标题、打卡时间点（如 08:30）与备份文件名不受影响。选中的档位立即生效。"

/** 「游戏开屏显示」二级页的标题（本轮需求 2）。 */
private const val GAME_SPLASH_SCREEN_TITLE = "游戏开屏显示"

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
    "贪吃蛇与数独共用：跟随系统 / 深色经典 / 深色适配"

/** 「游戏颜色模式」二级页的标题（需求 3）。 */
private const val GAME_CHROME_SCREEN_TITLE = "游戏颜色模式"

/**
 * 「游戏颜色模式」二级页的页首说明。
 *
 * 要点明"两页共用"与"标题栏、状态栏、开屏底色会一起变"——用户拨之前不知道
 * 三档到底改了什么，容易以为是"只改标题栏颜色"。
 */
private const val GAME_CHROME_SCREEN_HINT =
    "这一项同时作用于贪吃蛇与数独两个游戏页，并一起决定标题栏、状态栏图标与 " +
        "Hello World 开屏的底色。选中的档位立即生效，下次进入游戏即可看到。"

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
        "标题栏与页面底色跟随应用主题：浅色主题下是浅底深字，系统栏图标也随之变深。" +
            "这一档下标题栏与下方的深色游戏画面之间会有一条分界，这是它的语义",
    GameChromeMode.DARK_CLASSIC to
        "标题栏与游戏画面同色连成一片，系统栏图标始终用浅色。" +
            "这是默认档，也是改造后的既有观感",
    GameChromeMode.DARK_ADAPTIVE to
        "页面底色与系统栏图标都同「深色经典」，但标题栏不再画自己那块底色，" +
            "标题与返回箭头直接浮在游戏画面之上，视觉上更连贯"
)

/** 「游戏开屏显示」二级页里贪吃蛇那一行的标题。 */
private const val GAME_SPLASH_SNAKE_TITLE = "贪吃蛇开屏"

/**
 * 「游戏开屏显示」二级页里贪吃蛇那一行的说明。
 *
 * 点明关掉后看到什么（直接进入游戏画面），不然用户会担心"是不是坏了"。
 */
private const val GAME_SPLASH_SNAKE_DESCRIPTION =
    "关闭后进入贪吃蛇不再显示 Hello World，直接看到游戏画面"

/** 「游戏开屏显示」二级页里数独那一行的标题。 */
private const val GAME_SPLASH_SUDOKU_TITLE = "数独开屏"

/** 「游戏开屏显示」二级页里数独那一行的说明（同贪吃蛇那行的口径）。 */
private const val GAME_SPLASH_SUDOKU_DESCRIPTION =
    "关闭后进入数独不再显示 Hello World，直接看到难度选择"

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
 * 「游戏开屏显示」二级页（本轮需求 2：开关改为入口，可分别设置两个游戏）。
 *
 * 需求 2 原文：「游戏开屏显示改为二级界面入口，可单独设置贪吃蛇与数独的显示」。
 * 因此本页只有两行开关：贪吃蛇、数独，各拨各的。
 *
 * ## 为什么原来的「一个开关管两页」改成了两个
 *
 * 需求 23 当时的取舍是「两页开屏层视觉完全一致，分成两个开关会让用户为了同一件事拨两次」。
 * 本轮用户明确要求**可单独设置**——新的理由更充分：两个游戏是不同内容，
 * 有人想跳过贪吃蛇的开屏（玩得多、每次等 1 秒嫌烦）但保留数独的（不常玩、
 * 开屏正好当过渡）。"能单独关"比"少拨一次"更实用。
 *
 * ## 数据层沿用旧键还是新增两个键
 *
 * **新增两个**（`dev_game_splash_snake` / `dev_game_splash_sudoku`）而不是
 * 复用 `dev_game_splash`：旧键是**两页共用**的布尔，改用它会让"关贪吃蛇"顺手
 * 把数独也关掉，必须再加一个"是否已拆分"的迁移标记——那是把一次简单的拆分
 * 做成三层状态。直接新增两个键、默认都开，则旧键变成**无人读取的死数据**，
 * 迁移逻辑为零（ DataStore 里留着旧的 `dev_game_splash`，不影响任何行为）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameSplashScreen(
    /** 总开关状态：关掉时本页两行置灰（口径与一级页其它子项一致）。 */
    masterEnabled: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 与 [LabBackgroundScreen] 同一套取法：`SettingsRepository` 在 AppContainer 上是
    // 进程内单例，而 `SettingsViewModel` 并不转发它的每个属性。
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }

    // 两个游戏的**原始值**（界面回显用，不代表是否生效）。
    // initialValue 给 true：两个默认都开（= 改造前行为）。
    val snakeSplash by settingsRepository.devGameSplashSnake
        .collectAsStateWithLifecycle(initialValue = true)
    val sudokuSplash by settingsRepository.devGameSplashSudoku
        .collectAsStateWithLifecycle(initialValue = true)

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(GAME_SPLASH_SCREEN_TITLE) },
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
            // 说明卡：把「关掉会怎样」讲清楚，避免用户以为关掉后游戏进不去。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "关闭某个游戏的开屏后，进入该游戏不再显示 Hello World，" +
                        "而是直接看到游戏画面。另一个游戏不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                Column {
                    DeveloperSwitchRow(
                        title = GAME_SPLASH_SNAKE_TITLE,
                        description = GAME_SPLASH_SNAKE_DESCRIPTION,
                        // 原始值：与总开关的合成在仓库层（总开关关 → 恒显示）。
                        checked = snakeSplash,
                        // 门控只有总开关一个（与其它子项口径一致）。
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setDevGameSplashSnake(it) } }
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )

                    DeveloperSwitchRow(
                        title = GAME_SPLASH_SUDOKU_TITLE,
                        description = GAME_SPLASH_SUDOKU_DESCRIPTION,
                        checked = sudokuSplash,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setDevGameSplashSudoku(it) } }
                    )
                }
            }
        }
    }
}

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
 * 生效值在仓库层合成：`总开关 && 游戏总开关 && 游戏子开关`
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
                        // 原始值：生效合成在仓库层（总开关或游戏总开关关 → 恒显示）。
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
                    "关闭总开关时本项暂不生效，所选档位仍会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 「日期显示格式」二级页（需求 8 第 3 项，就地覆盖在开发者选项页上）。
 *
 * ## 为什么与 [GameChromeScreen] **逐项同构**而不抽公共组件
 *
 * 两者都是"三档互斥单选 + 每档一段说明 + 置灰但保留选中态"，
 * 视觉与交互完全同型。但项目纪律是**一屏一个自洽实现**：
 * 两处的说明文案、门控语义、默认值、落盘目标全都不一样，
 * 抽公共组件要给五个形参加上参数与文档，反而把两处各自的说理拆散。
 * 本项目已有多处这样的"刻意各写一份"（见 `SnakeScreen` / `SudokuScreen` 的开屏层）。
 *
 * ## 为什么**不**在这里读 `DeveloperOptionsRepository`
 *
 * 这一页只需要"原始枚举 + 写回"，两件事都由一级页通过形参传进来
 * （`selected` / `onSelect`）。本页自己去 `applicationContext as MoreCheckApp`
 * 再取一次仓库，与 [GameChromeScreen] 那种"自己取"是**不同**的取舍：
 * 这里刻意走形参，让"这一页读的是原始值还是生效值"在**调用处一眼可见**——
 * 调用处写的是 `dateFormatSwitch`（原始值），读代码就知道回显不会被总开关清空。
 *
 * ## 门控
 *
 * 与一级页其它子项一致：`enabled = masterEnabled`。关掉总开关时三行置灰、点不动，
 * 但**单选框仍停在用户选的那一档**（这正是调用处传原始值的原因）。
 *
 * @param selected 当前档位（**原始值**，不是与过总开关的生效值）。
 * @param masterEnabled 总开关状态：关掉时三行置灰。
 * @param onSelect 选中某一档的回调（由一级页负责落盘）。
 * @param onBack 返回开发者选项页。
 */
/**
 * 「样式设置」二级页（2026-10-22 需求 8）。
 *
 * ## 它管什么
 *
 * 只放**纯观感**的开关，每一项都能在界面上立刻看到差别：
 *
 * | 开关 | 影响的地方 |
 * | --- | --- |
 * | 渐变边框按钮 | 实验室底部「新增功能」按钮（蓝紫渐变描边） |
 * | 药丸底栏 | 热搜页底部导航（白色胶囊 + 橙色选中丸） |
 * | 深色今日标记 | 日历里"今天"的底色（实心主色块 vs 淡色底） |
 * | 日历默认周视图 | 进统计页时日历是收起成一周还是展开成整月 |
 * | 小米风格滑块 | 所有滑块（粗轨道 + 白色圆点）vs Material 默认细轨道 |
 *
 * ## 为什么不做成一级页卡片里的五个开关
 *
 * 「界面测试」那张卡已经有四行（三个开关 + 一个入口），再加五行会变成全页最长的一张，
 * 与「日期显示格式」当年做成二级页的理由完全一致（见该页 KDoc）。
 *
 * ## 门控
 *
 * 只有开发者选项总开关一级（与「界面测试」其它项同口径）。关掉总开关时这里的
 * 开关位置照旧显示、只是不生效（回落各自的默认观感）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StyleSettingsScreen(
    masterEnabled: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.developerOptionsRepository }

    // 五个**原始值**（界面回显用；生效合成在仓库层，总开关关时回落默认值）。
    val gradient by repository.styleGradientButtonSwitch.collectAsStateWithLifecycle(initialValue = true)
    val pillTabBar by repository.stylePillTabBarSwitch.collectAsStateWithLifecycle(initialValue = true)
    val solidToday by repository.styleSolidTodaySwitch.collectAsStateWithLifecycle(initialValue = true)
    val weekDefault by repository.styleCalendarWeekDefaultSwitch.collectAsStateWithLifecycle(initialValue = false)
    val xiaomiSlider by repository.styleXiaomiSliderSwitch.collectAsStateWithLifecycle(initialValue = true)

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(STYLE_SETTINGS_TITLE) },
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
                Text(
                    text = STYLE_SETTINGS_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                Column {
                    DeveloperSwitchRow(
                        title = STYLE_GRADIENT_TITLE,
                        description = STYLE_GRADIENT_DESCRIPTION,
                        checked = gradient,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setStyleGradientButton(it) } }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    DeveloperSwitchRow(
                        title = STYLE_PILL_TABBAR_TITLE,
                        description = STYLE_PILL_TABBAR_DESCRIPTION,
                        checked = pillTabBar,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setStylePillTabBar(it) } }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    DeveloperSwitchRow(
                        title = STYLE_SOLID_TODAY_TITLE,
                        description = STYLE_SOLID_TODAY_DESCRIPTION,
                        checked = solidToday,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setStyleSolidToday(it) } }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    DeveloperSwitchRow(
                        title = STYLE_WEEK_DEFAULT_TITLE,
                        description = STYLE_WEEK_DEFAULT_DESCRIPTION,
                        checked = weekDefault,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setStyleCalendarWeekDefault(it) } }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                    DeveloperSwitchRow(
                        title = STYLE_XIAOMI_SLIDER_TITLE,
                        description = STYLE_XIAOMI_SLIDER_DESCRIPTION,
                        checked = xiaomiSlider,
                        enabled = masterEnabled,
                        onCheckedChange = { scope.launch { repository.setStyleXiaomiSlider(it) } }
                    )
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "这些开关只改观感、不改功能；改动立即生效，已经打开的页面返回一次即可看到。" +
                    "关闭总开关时全部回到默认观感，但这里的位置仍会保留。",
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
private fun DateFormatScreen(
    selected: SettingsRepository.DateDisplayFormat,
    masterEnabled: Boolean,
    onSelect: (SettingsRepository.DateDisplayFormat) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(DATE_FORMAT_SCREEN_TITLE) },
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
            // 说明卡：写清影响哪几处、以及**不影响**哪几处（见该常量 KDoc）。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = DATE_FORMAT_SCREEN_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            Card(Modifier.fillMaxWidth()) {
                Column {
                    // 数据驱动地遍历枚举：新增一档只需改枚举 + 补下面 Map 的说明，
                    // 本页自动多一行（与 GameChromeScreen 同一做法）。
                    //
                    // ⚠️ 分隔线画在"不是最后一行"之后，而不是每行前面画一条：
                    // 后者会在卡片顶部多出一条悬空的线（Material3 列表的常见失误）。
                    SettingsRepository.DateDisplayFormat.entries.forEachIndexed { index, format ->
                        LabBgModeRow(
                            // 标题用**中文档位名**而不是枚举 id（2026-10-22）：
                            // 原来直接显示 `cn_full` / `iso` / `cn_short`，
                            // 那是持久化用的标识符，不该出现在给用户看的界面上。
                            title = DATE_FORMAT_LABELS[format] ?: format.id,
                            // 说明取自下面的 Map；漏写时退化成空串而不是崩
                            //（新增档位的人可能忘了补文案，空白说明是可接受的降级）。
                            description = DATE_FORMAT_DESCRIPTIONS[format].orEmpty(),
                            selected = selected == format,
                            enabled = masterEnabled,
                            onClick = { onSelect(format) }
                        )
                        if (index != SettingsRepository.DateDisplayFormat.entries.lastIndex) {
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
                text = "选择会立即保存并生效；返回主页、打卡记录或日历即可看到效果。" +
                    "关闭总开关时本项暂不生效，所选档位仍会保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 三档各自的**说明文案**。
 *
 * 为什么用 `Map` 而不是把说明塞进枚举本身（`DateDisplayFormat` 只有 `id`）：
 * 那个枚举是**被持久化的数据**（存 `id` 字符串），放在 `data` 层；
 * 而"在开发者选项的二级页里怎么向用户解释这一档"是界面文案，属于本页。
 * 两者放在一起会让"改一句说明"触碰数据层的取值契约。
 *
 * 用 `Map` 而不是 `when`：新增一档时枚举是唯一入口，本页不需要改结构
 * （`orEmpty()` 兜住漏写的说明）。
 */
private val DATE_FORMAT_LABELS: Map<SettingsRepository.DateDisplayFormat, String> = mapOf(
    SettingsRepository.DateDisplayFormat.CN_FULL to "中文完整",
    SettingsRepository.DateDisplayFormat.ISO to "横杠式",
    SettingsRepository.DateDisplayFormat.CN_SHORT to "省略年份"
)

/**
 * 三档各自的**说明文案**（2026-10-22 重写：改成「示例 + 什么时候适合用」）。
 *
 * 原来的说明只说了"长什么样"和"和什么一致"，用户读完仍不知道**该选哪个**。
 * 现在每档都给一条具体示例 + 一句适用场景，三档之间形成"完整 / 通用 / 紧凑"的取舍，
 * 照着场景选即可。
 */
private val DATE_FORMAT_DESCRIPTIONS: Map<SettingsRepository.DateDisplayFormat, String> = mapOf(
    SettingsRepository.DateDisplayFormat.CN_FULL to
        "示例：2026年10月19日 周一。信息最全，适合截图、打印、写进备忘或和朋友核对日期",
    SettingsRepository.DateDisplayFormat.ISO to
        "示例：2026-10-19 周一。横杠式，和电脑文件名、系统设置一致，方便与其它工具对照",
    SettingsRepository.DateDisplayFormat.CN_SHORT to
        "示例：10月19日 周一。最紧凑，列表里一屏能看到更多；但跨年时看不出是哪一年"
)

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
                        description = "按系统深浅色分池，每次进入实验室换一条渐变背景",
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
                        description = "跟随系统深浅色模式的纯色背景，与其它页面一致",
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
                        description = "挑选一个固定颜色，深浅色模式下都显示它",
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
