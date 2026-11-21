package com.morecheck.ui.lab

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.theme.AccentColor
import com.morecheck.ui.theme.strongTextColor
import kotlin.math.roundToInt

/**
 * 「开发者选项 → 图标测试 → 设备信息查询」（本轮扩充版）。
 *
 * ## 这一页是干什么的
 *
 * 需求原文：「显示本 app 使用的按钮、动画、颜色等前端 UI 的配置与名称，主要界面的按钮
 * 高度与文字大小，按钮可点击感受，方便后续调试时沟通」。
 * 本轮追加需求：「补充完善设备信息查询的内容与排版」。
 *
 * 所以它是一张**可交互的 UI 规格清单**，不是设置页：
 * - 尺寸 / 字号 / 动画 / 颜色逐条列出，**每条都注明来源文件**——用户拿着某一项来问
 *   「这个按钮为什么是这么高」时，我能直接翻到那个文件，而不用凭印象回忆；
 * - 「控件样例（可按）」放**真控件**（开关 / 滑块 / 芯片 / 输入框 / 卡片 / 图标按钮），
 *   「按压手感」放**真按钮**：很多手感问题只能在真机上按一按才知道，光看数字说不清；
 * - 颜色同时给出「可选的 8 个主题色」与「当前主题实际生效的那几个语义色」，
 *   后者是**运行时从 [MaterialTheme] 读出来的**（换了主题色 / 深浅模式，这一页跟着变）。
 *
 * ⚠️ 本页**只读**：不改任何设置、不写 DataStore。控件样例里的开关 / 滑块 / 输入框
 * 虽然能拨能拖，但它们的值只活在本页的 `rememberSaveable` 里，**不会**回写仓库，
 * 也就不会真的改掉「开屏显示时长」之类的设置。
 *
 * ## 这一页怎么看（本轮新增的阅读方式）
 *
 * 全页是**12 个可折叠分区**，每个分区一张 `Card`，**卡片标题行整行可点**，
 * 右侧一个 `ExpandMore` / `ExpandLess` 指示展开态（见 [InspectorCollapsibleSection]）。
 *
 * 默认只展开「控件样例（可按）」一个，其余 11 个收起——理由写在
 * [InspectorCollapsibleSection] 的参数说明里：一进页面要能**一眼扫到 12 个分区的标题**，
 * 而不是先滚过四十多行「逐页规格」才看见下面还有什么。
 *
 * ## 每条规格都标了来源，怎么用
 *
 * 三要素固定是「控件名 / 尺寸或字号 / 来源文件:常量名」：
 * - **左标题右数值**（见 [InspectorSpecTableRow]），一眼能左右对照；
 * - 来源单独一行、压暗、小一号，读完取值想核对时再看它。
 *
 * 拿某一项来问「这个数字对不对」时，照着来源那一行翻文件即可——这正是本页的价值。
 *
 * ## 为什么「就地覆盖」在开发者选项页上，而不是走导航路由
 *
 * 与 [IconPickerScreen]（以及同文件的 `LoginTestScreen`）同一套：调用方用 `if` 二分，
 * 整页覆盖、系统返回键由本页的 [BackHandler] 接住。
 * 走路由要在 `ui/nav/` 下加 `Destinations` 与 `composable`，把一个纯 UI 的只读页
 * 提升成全局导航状态，不值得（详见 `DeveloperOptionsScreen` 的 KDoc）。
 *
 * ## 数值为什么有的写死、有的运行时取
 *
 * 这是本页最需要守住的一条纪律：
 * - **能在运行时读到的，一律运行时读**（`MaterialTheme.colorScheme.*`、
 *   `MaterialTheme.shapes.*`、`Spring.*` 常量、`AccentColor.entries`、
 *   `SettingsRepository` 的公开常量）——这些会随主题 / 库版本 / 配置变，
 *   写死就是给自己埋一个迟早对不上的数字；
 * - **其它文件里的 private 常量读不到**（如 `TimerPanel.kt` 的
 *   `TimerStartButtonWidth`、`DeveloperOptionsScreen.kt` 的 `ACTION_BUTTON_WIDTH`），
 *   只能把值抄过来并在来源里写明**取自哪个文件的哪个常量**——
 *   这正是"注明来源"存在的意义：抄错时能一眼核对；
 * - **`material3` 库内部的 token 值**（`SwitchTokens.TrackWidth` 等）在源码里也是
 *   引用不到的 internal 对象，本轮是**反编译 `material3-release.aar`（1.3.1）的
 *   字节码常量池**读出来的，逐条写在「Material 3 组件默认尺寸」分区里并注明这个取法。
 *
 * 改那些常量时**本页不会自动跟着变**，这是这种"规格清单页"的固有代价，
 * 也是我把来源文件写进每一条的原因。
 *
 * @param onBack 点返回箭头 / 按系统返回键时回调（回开发者选项页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiInspectorScreen(
    onBack: () -> Unit
) {
    // 系统返回键等同「返回开发者选项」，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备信息查询") },
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
            // ---- 页首说明 ---------------------------------------------------
            // 需求要求顶部先讲清这一页的用途。本轮补上「怎么看」那一段：
            // 分区可折叠 + 每条标来源，是本轮改版后最需要先知道的两件事——
            // 不写的话，用户会以为"怎么只有一张卡片展开了"，或者以为页面内容变少了。
            //
            // 写"只读、不回写设置"是刻意的：控件样例里的开关能拨动、滑块能拖，
            // 明说它们不落盘，用户才敢随便点。
            Text(
                text = "本页只读地列出本应用前端 UI 的真实配置：控件样例（可按）、" +
                    "各主要页面的逐页规格、按钮尺寸与文字规格、按压动画、形状圆角、" +
                    "可选主题色与当前生效配色，以及各处动画参数。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(INSPECTOR_INTRO_PARAGRAPH_GAP))
            Text(
                text = "怎么看：全页分成 12 个可折叠分区，点卡片标题行即可展开 / 收起，" +
                    "右侧箭头指示当前状态；默认只展开最上面的「控件样例（可按）」，" +
                    "这样一进页面就能一眼扫到全部分区名。" +
                    "每一条规格都注明来源文件与常量名，可以直接拿着它和开发者对照代码；" +
                    "所有取值都标注了真实来源，读数是只读的——样例控件不会改掉任何设置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_BODY_ALPHA)
            )

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 一、控件样例（可按）------------------------------------------
            // ★ 唯一默认展开的分区：它内容最短（约一屏），又正好是"可交互"这个卖点的
            // 直接演示；其余分区（尤其新增的逐页规格有四十多行）默认收起，
            // 保证不为任何一个分区把后面的分区标题推到很远的下面去。
            InspectorCollapsibleSection(
                title = "控件样例（可按）",
                initiallyExpanded = true
            ) {
                InspectorControlSamples()
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 二、主要页面规格（逐页）---------------------------------------
            InspectorCollapsibleSection(title = "主要页面规格（逐页 · 标注来源）") {
                InspectorPageSpecsContent()
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 三、按钮规格 -------------------------------------------------
            InspectorCollapsibleSection(title = "按钮尺寸与文字规格") {
                InspectorSpecRow(
                    label = "计时页「开始」胶囊按钮",
                    value = "宽度 176dp；高度 64dp；圆角 50%（胶囊）",
                    source = "TimerPanel.kt：TimerStartButtonWidth / TimerButtonSize"
                )
                InspectorSpecRow(
                    label = "计时页「停止 / 暂停 / 继续」圆形按钮",
                    value = "直径 64dp；图标 30dp；按钮间距 28dp",
                    source = "TimerPanel.kt：TimerButtonSize / TimerButtonIconSize / " +
                        "TimerButtonSpacing"
                )
                InspectorSpecRow(
                    label = "开发者选项「动作」按钮（通知测试等）",
                    value = "固定宽度 104dp；高度 40dp（Material 默认）；左右内边距各 24dp；" +
                        "文字 labelLarge 单行居中",
                    source = "宽度：DeveloperOptionsScreen.kt：ACTION_BUTTON_WIDTH；" +
                        "高 40dp 与 24dp 内边距：material3 1.3.1 的 " +
                        "OutlinedButtonTokens.ContainerHeight / ButtonDefaults.ButtonHorizontalPadding"
                )
                InspectorSpecRow(
                    label = "顶栏 TopAppBar（Material 3）",
                    value = "容器高度 64dp；标题 titleLarge；前导图标 24dp",
                    source = "material3 1.3.1：TopAppBarDefaults.TopAppBarExpandedHeight → " +
                        "TopAppBarSmallTokens.ContainerHeight（已反编译核对字节码，取到 double 64.0）"
                )
                InspectorSpecRow(
                    label = "主页「打卡」通栏按钮",
                    value = "宽度撑满（左右各留 20dp）；高度 50dp；文字 16sp",
                    source = "HomeScreen.kt：CheckInButton"
                )
                InspectorSpecRow(
                    label = "协议弹窗的主 / 次按钮",
                    value = "各占一半宽度；高度 52dp；文字 16sp",
                    source = "PrivacyConsent.kt：BUTTON_HEIGHT"
                )
                InspectorSpecRow(
                    label = "计时滚轮单行",
                    value = "行高 52dp；中心行 30sp，上下行 24sp",
                    source = "TimerPanel.kt：WheelItemHeight 与 WheelColumn 里的 fontSize"
                )
                InspectorSpecRow(
                    label = "计时页「最近用过的时长」芯片",
                    value = "胶囊形（圆角 50%）；内边距 左右 16dp / 上下 9dp；文字 14sp 等宽",
                    source = "TimerPanel.kt：DurationChip（本页「控件样例」里按同一规格复刻）"
                )
                InspectorSpecRow(
                    label = "实验室宫格卡片",
                    value = "两列等宽（各 weight 1f）；行 / 列间距各 12dp；" +
                        "卡片内边距 左右 14dp / 上下 18dp",
                    source = "LabScreen.kt：LabScreen 的 chunked(2) 与 LabCard"
                )
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 四、按压手感（真按钮）------------------------------------------
            InspectorCollapsibleSection(title = "按压手感演示（可按）") {
                Text(
                    // 正文里的 Markdown 强调标记（两个星号）Compose 的 Text **不解析**，
                    // 会原样显示成星号——所以用户可见的文案里一律不写它（KDoc 里可以写）。
                    text = "下面这枚按钮与计时页按钮逐项同源的参数绘制：" +
                        "缩放走弹簧、容器色向 scrim 混合、投影变浅、并触发一次长按触觉反馈。" +
                        "按住它就能确认「按下是不是看得出来」「弹回来是否过冲」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(INSPECTOR_INTRO_TO_CONTENT_GAP))
                InspectorPressDemo()
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 五、形状与圆角 -------------------------------------------------
            // 本轮把原「圆角规格（主题 Shapes）」单独一张卡片**并入本分区**：
            // 两者回答的是同一个问题（"这个界面里的直角 / 圆角是多少"），
            // 各自一张卡片反而要先在两处之间来回找。
            InspectorCollapsibleSection(title = "形状与圆角（主题 Shapes）") {
                Text(
                    text = "本应用把 Material 3 默认的大圆角收窄过，值定义在 Theme.kt 的 " +
                        "Shapes 里（微信 / QQ 那种接近直角的卡片）。下面的值与色块都是" +
                        "运行时从 MaterialTheme.shapes 读出来的，不是抄的字面量。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(INSPECTOR_INTRO_TO_CONTENT_GAP))
                InspectorShapeDemo()
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 六、可选主题色 -----------------------------------------------
            InspectorCollapsibleSection(title = "可选主题色（AccentColor 全部枚举）") {
                Text(
                    text = "用户在「我的 → 设置 → 外观」里可选的 8 个基准色。" +
                        "注意这 8 个是基准色，深浅两套色板由主题用 lerp 从它推导，" +
                        "因此卡片、容器色与它同色系但不是同一个值（下面一节是实际生效值）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(12.dp))
                // `entries` 而不是 `values()`：Kotlin 2.1 起 entries 是稳定 API，
                // 返回的是不可变 List，也不会像 values() 那样每次都新建一个数组副本。
                AccentColor.entries.forEach { accent ->
                    InspectorColorRow(
                        name = "${accent.label}（id = ${accent.id}）",
                        hex = accent.seed.toHex(),
                        swatch = accent.seed,
                        note = if (accent == AccentColor.DEFAULT) "默认主题色" else ""
                    )
                }
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 七、当前生效配色 ---------------------------------------------
            InspectorCollapsibleSection(title = "当前主题生效配色（运行时取值）") {
                // 这一列**每帧从 MaterialTheme 现取**：切换主题色或深浅模式后，
                // 不需要任何额外代码，这里的 hex 与色块就会跟着变。
                val cs = MaterialTheme.colorScheme
                val palette = listOf(
                    "background" to cs.background,
                    "surface" to cs.surface,
                    "surfaceVariant" to cs.surfaceVariant,
                    "primary" to cs.primary,
                    "primaryContainer" to cs.primaryContainer,
                    "onSurface" to cs.onSurface,
                    "onSurfaceVariant" to cs.onSurfaceVariant,
                    "outline" to cs.outline,
                    "outlineVariant" to cs.outlineVariant
                )
                Text(
                    text = "以下 9 个语义色是当前主题下 MaterialTheme.colorScheme 的实际取值，" +
                        "截图沟通配色时以这里为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(12.dp))
                palette.forEach { (name, color) ->
                    InspectorColorRow(
                        name = name,
                        hex = color.toHex(),
                        swatch = color,
                        note = ""
                    )
                }
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 八、动画参数 -------------------------------------------------
            InspectorCollapsibleSection(title = "动画与交互参数") {
                InspectorSpecRow(
                    label = "计时页「滚轮 ↔ 读数」切换",
                    value = "fadeIn + scaleIn tween(200)，initialScale 0.92；" +
                        "fadeOut tween(150)",
                    source = "TimerPanel.kt：AnimatedContent 的 transitionSpec"
                )
                InspectorSpecRow(
                    label = "计时按钮按压缩放",
                    value = "按下 0.84 / 松开 1.00；按下 spring(NoBouncy, StiffnessMediumLow)，" +
                        "松开 spring(MediumBouncy, StiffnessMedium)",
                    source = "TimerPanel.kt：TimerActionButton 的 animateFloatAsState"
                )
                InspectorSpecRow(
                    label = "计时按钮容器压暗与投影",
                    value = "容器色向 scrim 混 22%（animateColorAsState）；" +
                        "投影 8dp ↔ 2dp（animateDpAsState），两者均用 spring(NoBouncy, StiffnessMedium)",
                    source = "TimerPanel.kt：TimerActionButton 的 pressedContainer / elevation"
                )
                InspectorSpecRow(
                    label = "计时页到点提示",
                    value = "连续两次长震，间隔 delay(180)ms，之后再响铃",
                    source = "TimerPanel.kt：finishTick 的 LaunchedEffect"
                )
                InspectorSpecRow(
                    label = "计时页重组时钟",
                    value = "计时中每 200ms 自增一次（delay(200)）",
                    source = "TimerPanel.kt：refreshTick 所在的循环"
                )
                InspectorSpecRow(
                    label = "「暂停 / 继续」点击节流",
                    value = "两次点击最小间隔 300ms（静默丢弃，不置灰）",
                    source = "TimerPanel.kt：PAUSE_RESUME_THROTTLE_MS"
                )
                InspectorSpecRow(
                    label = "主页打卡进度条",
                    value = "animateFloatAsState + tween(350)",
                    source = "HomeScreen.kt：PROGRESS_ANIM_MS"
                )
                InspectorSpecRow(
                    label = "主页任务圆形按钮勾选",
                    value = "先 snapTo(0.8)，再用 spring(MediumBouncy, StiffnessHigh) 弹回 1.0",
                    source = "HomeScreen.kt：TaskCircle"
                )
                InspectorSpecRow(
                    label = "「我的」页顶部时钟刷新",
                    value = "每秒一跳（delay(1000L)），只重算 LocalTime.now()",
                    source = "MyScreen.kt：CLOCK_TICK_MS"
                )
                InspectorSpecRow(
                    label = "悬浮窗小球 ↔ 小窗切换",
                    value = "fadeIn + scaleIn tween(180)，initialScale 0.7；fadeOut tween(120)",
                    source = "NotebookOverlayService.kt：NotebookOverlayHost"
                )
                InspectorSpecRow(
                    label = "底部标签页切换",
                    value = "tween(280)（进出同长，整屏宽度滑移）",
                    source = "TabNavHost.kt：NAV_DURATION_MS"
                )
                InspectorSpecRow(
                    label = "主题色选择块",
                    value = "animateColorAsState 四处 + tween(180)",
                    source = "SettingsPage.kt：ACCENT_ANIM_MS"
                )
                InspectorSpecRow(
                    label = "开屏 Hello World 淡出",
                    value = "AnimatedVisibility exit = fadeOut(tween(320))",
                    source = "MoreCheckApp.kt：SPLASH_FADE_MS"
                )
                InspectorSpecRow(
                    label = "协议全文阅读层进出场",
                    value = "进入从右滑入 tween(280)；退出向下滑出 tween(280)",
                    source = "PrivacyConsent.kt：LEGAL_SLIDE_MS + LegalDocLayer"
                )
                InspectorSpecRow(
                    label = "登录 / 注册页按压",
                    value = "缩放 0.96（圆形图标传 0.92）；默认 spring(MediumBouncy, " +
                        "StiffnessMedium)，圆形用 spring(NoBouncy, StiffnessMediumLow)",
                    source = "AuthComponents.kt：Modifier.authPressable"
                )
                InspectorSpecRow(
                    label = "登录 / 注册 / 扫一扫 提示条",
                    value = "进入 fadeIn + scaleIn(0.92) tween(160)；" +
                        "退出 fadeOut + scaleOut(0.92) tween(200)",
                    source = "AuthComponents.kt、ScanScreen.kt"
                )
                InspectorSpecRow(
                    label = "打卡成功彩带",
                    value = "tween(550, FastOutSlowInEasing)",
                    source = "Confetti.kt"
                )
                InspectorSpecRow(
                    label = "开屏 / 朋友圈入场",
                    value = "Animatable 0 → 1 配 tween(420)，同时驱动透明度与位移",
                    source = "SplashScreen.kt、MomentsScreen.kt"
                )
                InspectorSpecRow(
                    label = "日历翻月",
                    value = "animateScrollToPage(tween(220))",
                    source = "CalendarScreen.kt"
                )
                InspectorSpecRow(
                    label = "本页分区展开 / 收起",
                    value = "展开 expandVertically + fadeIn tween(" +
                        "${INSPECTOR_SECTION_EXPAND_MS}ms / ${INSPECTOR_SECTION_FADE_MS}ms)；" +
                        "收起 shrinkVertically + fadeOut tween(" +
                        "${INSPECTOR_SECTION_COLLAPSE_MS}ms / ${INSPECTOR_SECTION_FADE_MS}ms)",
                    source = "本文件：InspectorCollapsibleSection 的 INSPECTOR_SECTION_* 常量"
                )
                // Spring 的五个档位常量直接从库里读出来打印：它们是 Float 常量，
                // 写死就等于把"我记的"当成"库里的"，而这一页存在的意义恰恰是不凭印象。
                InspectorSpecRow(
                    label = "Spring 档位常量实测值",
                    value = "NoBouncy = ${Spring.DampingRatioNoBouncy}、" +
                        "MediumBouncy = ${Spring.DampingRatioMediumBouncy}；" +
                        "StiffnessMediumLow = ${Spring.StiffnessMediumLow}、" +
                        "StiffnessMedium = ${Spring.StiffnessMedium}、" +
                        "StiffnessHigh = ${Spring.StiffnessHigh}",
                    source = "androidx.compose.animation.core.Spring（运行时读取）"
                )
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 十一、开屏 Hello World 显示逻辑（本轮需求 22）---------------------
            // 需求原文：「设备信息查询新增开屏helloworld显示逻辑、登录注册页逻辑等」。
            // 这一节回答"从点图标到看见主界面之间到底发生了什么"——
            // 它是本应用唯一一个"所有用户都必然经历"的流程，却也是最容易被
            // 凭印象描述错的一段（三个退场条件、两段独立等待、弹窗与开屏的叠放关系）。
            InspectorCollapsibleSection(title = "开屏 Hello World 显示逻辑") {
                InspectorSpecRow(
                    label = "最短停留时长",
                    value = "900ms（默认）",
                    source = "MoreCheckApp.kt：SPLASH_DURATION_MS；" +
                        "可被「性能测试」滑块覆盖，读取处见 MainActivity 的 splashHoldMs"
                )
                InspectorSpecRow(
                    label = "同意后加停",
                    value = "750ms",
                    source = "MoreCheckApp.kt：SPLASH_AGREE_HOLD_MS；" +
                        "仅「本次会话刚点了同意」时计，老用户冷启动不多等"
                )
                InspectorSpecRow(
                    label = "退场条件",
                    value = "3 个条件全部满足",
                    source = "SplashGate：minElapsed && !consentPending && holdElapsed，" +
                        "随后 fadeOut tween(SPLASH_FADE_MS = 320) 走完才回调 onFinished"
                )
                InspectorSpecRow(
                    label = "弹窗期间不退出",
                    value = "条件未齐不退场",
                    source = "consentPending = !consentLoaded || !consentAgreed；" +
                        "同意弹窗是独立 Dialog 窗口，浮在开屏之上，背景始终是 Hello World"
                )
                InspectorSpecRow(
                    label = "时长可调范围",
                    value = "0 ~ 10 秒",
                    source = "SettingsRepository：MIN/MAX_SPLASH_HOLD_MS + SPLASH_HOLD_STEP_MS(100)；" +
                        "「恢复默认」写回 DEFAULT_SPLASH_HOLD_MS = 900"
                )
                InspectorSpecRow(
                    label = "随机浮动方向",
                    value = "只向上浮动",
                    source = "DeveloperOptionsRepository.splashHoldDurationMs：base + " +
                        "Random.nextInt(0..jitter+1)，结果夹到 20000ms；" +
                        "只向上是为避免开屏在同意弹窗弹出前就退场"
                )
                InspectorSpecRow(
                    label = "登录引导后置",
                    value = "退场后才弹",
                    source = "MainActivity：onFinished = { if (agreedJustNow) promptLogin = true }；" +
                        "否则 Dialog 会盖掉「同意后再显示 0.75 秒」的 Hello World"
                )
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 十二、登录注册页逻辑（本轮需求 22）-------------------------------
            // 与上一节同一来源。这一节回答"登录 / 注册 / 撤回同意各自走到哪"——
            // 页面栈的落点是历史上返工最多的地方（注册成功回哪、撤回后怎么重启），
            // 逐条写明判定顺序与兜底分支。
            InspectorCollapsibleSection(title = "登录注册页逻辑") {
                InspectorSpecRow(
                    label = "首启顺序",
                    value = "先开屏再弹窗",
                    source = "MainActivity：PrivacyConsentGate 的 enabled = splashMinElapsed；" +
                        "最短时长未到时只铺背景、不弹同意窗"
                )
                InspectorSpecRow(
                    label = "登录引导弹窗",
                    value = "可关的一次性弹窗",
                    source = "MoreCheckApp.kt：promptLogin && !isLoggedIn && loginPromptEnabled；" +
                        "开关默认开，实验室「登录提示功能」页可关，不影响强制协议门闸"
                )
                InspectorSpecRow(
                    label = "登录入口",
                    value = "我的 → 个人信息",
                    source = "TabNavHost：PersonalInfo → Login；" +
                        "登录页内另有注册 / 使用密码登录 / 忘记密码三个出口"
                )
                InspectorSpecRow(
                    label = "验证码测试",
                    value = "六位码一次性校验",
                    source = "DeveloperOptionsRepository.issueCode / verifyAndConsume；" +
                        "开关关时只校验非空，任意码可过（演示口径）"
                )
                InspectorSpecRow(
                    label = "撤回隐私同意",
                    value = "5 秒倒计时后退出",
                    source = "PermissionsScreen.kt：RevokePrivacyDialog 的 confirmEnabled = " +
                        "secondsLeft == 0 → revokePrivacy() + findActivity()?.finish()"
                )
                InspectorSpecRow(
                    label = "注册成功落点",
                    value = "回个人信息页",
                    source = "TabNavHost：onRegistered 先 popBackStack(PersonalInfo," +
                        "inclusive = false)，栈里没有它时兜底弹到 Login(inclusive = true)"
                )
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 十、消息弹窗规格（本轮需求 9）------------------------------------
            // 「不同消息弹窗」= 应用里所有向用户传达一次性消息的浮层。
            // 每条注明它在哪里出现、由什么触发、规格取自哪个文件——
            // 沟通"弹窗"类 UI 问题时以这一节为准。
            InspectorCollapsibleSection(title = "消息弹窗规格（Toast / Snackbar / 弹窗）") {
                InspectorSpecRow(
                    label = "全局 Snackbar（页面级一次性提示）",
                    value = "挂在全局骨架右下 / 底部中央（随页面），" +
                        "底部留白恒等于底栏高度（80dp + 导航栏 inset），各页高度一致；" +
                        "容器 surfaceContainerHigh、文字 onSurface",
                    source = "MoreCheckApp.kt：snackbarHost 的 snackbarBottomPadding；" +
                        "触发入口：TabNavHost.onActionMessage → snackbarHostState.showSnackbar"
                )
                InspectorSpecRow(
                    label = "强制同意弹窗（首次启动 / 撤回后）",
                    value = "底部弹窗（ModalBottomSheet），高度 0.41 屏高，顶部圆角 20dp，" +
                        "容器色恒透明、可见底色由内层自画（防闪白的结构性方案）；" +
                        "主按钮「同意并继续」/ 次按钮「不同意，退出」高 52dp、字号 16sp；" +
                        "不可下滑关闭、返回键不关（shouldDismissOnBackPress = false）",
                    source = "PrivacyConsent.kt：SHEET_HEIGHT_FRACTION / BUTTON_HEIGHT / " +
                        "AppBottomSheet 的 sheetProperties"
                )
                InspectorSpecRow(
                    label = "撤回同意弹窗",
                    value = "与同意弹窗同一骨架（同高 0.41），差异三处：" +
                        "正本文案更长、禁止上下滚动（bodyScrollable = false）、" +
                        "可下滑关闭（dismissible = true）且带 5 秒倒计时",
                    source = "PrivacyConsent.kt：AppBottomSheet 的 bodyScrollable / dismissible 形参"
                )
                InspectorSpecRow(
                    label = "协议全文阅读层",
                    value = "整屏覆盖层（与弹窗同处一个 Dialog 窗口，无跨窗口残影），" +
                        "进出场：从底部向上滑入 / 向下滑出 tween(280)（无淡入淡出）；" +
                        "顶栏 TopAppBar + 返回箭头，正文 Card 内 Markdown",
                    source = "PrivacyConsent.kt：LEGAL_SLIDE_MS + LegalDocLayer"
                )
                InspectorSpecRow(
                    label = "普通 AlertDialog（确认类）",
                    value = "Material3 默认规格：容器 surfaceContainerHigh、" +
                        "圆角 Shapes.medium（本主题 8dp）、标题 titleMedium、正文 bodyMedium；" +
                        "按钮为 TextButton（confirm + dismiss 两枚）",
                    source = "ui/common/Dialogs.kt：ConfirmDialog；" +
                        "material3 1.3.1 AlertDialogDefaults"
                )
                InspectorSpecRow(
                    label = "长按宫格菜单（实验室）",
                    value = "AlertDialog（标题 = 条目名，正文「要做什么？」），" +
                        "确认按钮「置顶」、次要区「恢复默认排序（有自定义顺序时）/ 添加到桌面」；" +
                        "用 AlertDialog 而不是 DropdownMenu：卡片在网格里锚点不稳",
                    source = "LabScreen.kt：pendingLongPress / pendingPin 两个对话框"
                )
                InspectorSpecRow(
                    label = "下载拒绝弹窗（搜一搜）",
                    value = "AlertDialog「已拒绝下载」，正文含文件名（拿不到时写明是下载任务）" +
                        "与完整 URL；同时 Snackbar 提示「已拒绝下载」",
                    source = "SearchWebScreen.kt：rejectedDownloadText + rejectedDownload 状态"
                )
                InspectorSpecRow(
                    label = "登录引导弹窗",
                    value = "AlertDialog「欢迎使用 MoreCheck」：正文「登录后可使用完整功能。」，" +
                        "确认「去登录」/ 取消「暂不」；仅在开屏结束后、未登录且开关打开时弹一次",
                    source = "PrivacyConsent.kt：LoginPromptDialog；" +
                        "门控：MoreCheckApp.kt 的 loginPromptEnabled"
                )
                InspectorSpecRow(
                    label = "更换图标确认弹窗（本轮新增）",
                    value = "AlertDialog「更换应用图标」，正文固定以" +
                        "「更换应用图标：软件将重新启动。」开头，后接目标图标名与刷新提示；" +
                        "确认「确定」执行 alias 切换 / 取消放弃",
                    source = "IconPickerScreen.kt：pendingConfirmId 对话框（需求 13 同轮新增）"
                )
            }

            Spacer(Modifier.height(INSPECTOR_SECTION_SPACING))

            // ---- 九、Material 3 组件默认尺寸 ------------------------------------
            // 本轮新增。这一节回答的是"我们没显式指定的那些尺寸到底是多少"——
            // 比如"Switch 多宽""输入框多高""图标按钮的点击区域多大"。
            // 这些值在 material3 源码里是 internal 的 token 对象，业务代码引用不到，
            // 所以取值方式是**反编译 material3-release.aar（1.3.1）的字节码常量池**，
            // 逐条写在这里；每条都标了 token 名，换库版本时可以照着重新核对。
            InspectorCollapsibleSection(title = "Material 3 组件默认尺寸（库内 token）") {
                Text(
                    text = "以下不是本项目的自定义值，而是 material3 1.3.1 各组件 token 的" +
                        "默认值——也就是本项目没有显式指定时实际生效的尺寸。" +
                        "它们随库版本变化，来源里给的是 token 类名与字段名。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(INSPECTOR_SAMPLE_CONTROL_TOP_GAP))
                InspectorSpecRow(
                    label = "Switch 开关",
                    value = "轨道 52dp × 32dp；手柄 选中 24dp / 未选中 16dp / 按下 28dp；" +
                        "状态层 40dp；轨道描边 2dp；手柄内图标 16dp",
                    source = "material3 1.3.1：SwitchTokens." +
                        "TrackWidth / TrackHeight / SelectedHandleWidth / UnselectedHandleWidth / " +
                        "PressedHandleWidth / StateLayerSize / TrackOutlineWidth"
                )
                InspectorSpecRow(
                    label = "Slider 滑块",
                    value = "轨道高 16dp；手柄 44dp × 4dp（按下时宽 2dp）；" +
                        "停止指示点 4dp（本项目主页进度条已显式关掉它）",
                    source = "material3 1.3.1：SliderTokens." +
                        "ActiveTrackHeight / HandleHeight / HandleWidth / PressedHandleWidth / " +
                        "StopIndicatorSize"
                )
                InspectorSpecRow(
                    label = "OutlinedTextField 输入框",
                    value = "单行容器高 56dp；获得焦点时描边 2dp；前后图标 24dp",
                    source = "material3 1.3.1：OutlinedTextFieldTokens." +
                        "ContainerHeight / FocusOutlineWidth / LeadingIconSize"
                )
                InspectorSpecRow(
                    label = "IconButton 图标按钮",
                    value = "状态层（水波纹容器）40dp；图标 24dp；" +
                        "最小交互尺寸 48dp（组件不够大时由框架补足）",
                    source = "material3 1.3.1：IconButtonTokens." +
                        "StateLayerSize / IconSize；最小交互尺寸见 MinimumInteractiveModifier " +
                        "的 issue 文案「Reserves at least 48.dp」"
                )
                InspectorSpecRow(
                    label = "OutlinedButton 描边按钮",
                    value = "容器高 40dp；按钮内图标 18dp；左右内边距各 24dp",
                    source = "material3 1.3.1：OutlinedButtonTokens.ContainerHeight / IconSize；" +
                        "内边距：ButtonDefaults.ButtonHorizontalPadding"
                )
                InspectorSpecRow(
                    label = "Card 卡片（默认外观）",
                    value = "形状 = Shapes.medium（本主题 8dp）；默认海拔 = Level0（0dp）；" +
                        "未显式传 colors 时容器色为 surfaceContainerLow",
                    source = "material3 1.3.1：CardDefaults.shape → " +
                        "FilledCardTokens.ContainerShape = ShapeKeyTokens.CornerMedium；" +
                        "ContainerElevation = ElevationTokens.Level0"
                )
                InspectorSpecRow(
                    label = "Material 图标向量默认尺寸",
                    value = "24dp × 24dp（每个 ImageVector 自带 24dp 基准尺寸，" +
                        "不指定 size 时生效）",
                    source = "material-icons-core：filled 图标类的 ImageVector.Builder " +
                        "基准值 24f（已反编译核对 AddKt 的字节码）"
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 分区骨架：可折叠的 Card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一个**可折叠分区**：卡片标题行整行可点，标题右侧一个展开 / 收起箭头，
 * 内容用 [AnimatedVisibility] 做纵向展开 / 收起 + 淡入淡出。
 *
 * ## 展开状态为什么用「每个分区各自一个 `rememberSaveable`」
 *
 * 需求里给了两条路：`mutableStateMapOf` 配合 `rememberSaveable` 的 saver，
 * 或者最朴素地**每个分区自己 `rememberSaveable { mutableStateOf(...) }`**。
 * 这里选后者，三条理由：
 *
 * 1. **不会被 key 写错**。用 map 就必须给每个分区起一个字符串 key，
 *    而 key 是手写的字面量——加一个分区忘了加 key、或者复制粘贴时两个分区用了同一个
 *    key，表现为"点 A 结果 B 也一起动"，而且**编译期毫无提示**；
 *    每分区各自持有状态则根本不存在 key 这个概念，天然隔离。
 * 2. **不需要自定义 Saver**。`Map<String, Boolean>` 不是 `Bundle` 支持的类型，
 *    走 map 就必须自己写一个 `Saver`（还要处理恢复时类型不匹配）；
 *    而 `mutableStateOf(Boolean)` 由 Compose 内置的 `mutableStateSaver` 直接支持，
 *    一行 `rememberSaveable { mutableStateOf(...) }` 就够了。
 * 3. **状态跟着分区走**。分区的状态和它的 UI 写在同一个函数里，
 *    读代码时不必跳出去找一个集中的 map 才知道"这个分区默认是开还是关"。
 *
 * 代价是每个分区各自一个 `rememberSaveable` 槽位（12 个分区 12 个槽），
 * 这点开销可以忽略；而"加分区时忘同步 map"的成本是整个分区联动，代价不对等。
 *
 * ## 默认展开值是怎么定的
 *
 * 由调用方通过 [initiallyExpanded] 显式传，**默认 `false`（收起）**——让"忘记传"
 * 的后果是"这一节被折叠起来"，用户点一下就看到了；反过来若默认展开，
 * 以后新增一个长分区时忘记传值，就会把整页又变回"要滚很久"的老样子。
 *
 * 目前**只有「控件样例（可按）」传 `true`**：
 * - 它内容最短（约一屏），展开它不会把后面的分区标题推远；
 * - 它恰好是"可交互"这个卖点的直接演示，一进页面就摸得到；
 * - 其余分区（尤其新增的「主要页面规格」有四十多行）默认收起，
 *   这样一进页面就能**一眼看到全部 12 个分区名**，符合"长清单先扫结构"的目标。
 *
 * ## 动画参数为什么展开比收起略慢
 *
 * 展开 220ms、收起 180ms（[INSPECTOR_SECTION_EXPAND_MS] /
 * [INSPECTOR_SECTION_COLLAPSE_MS]）。方向不对称是有意的：
 * 展开是"我要读里面的东西"，稍慢一点让眼睛跟得上；收起是"我扫完了，合上"，
 * 快一点更符合"合上"的直觉。两个值与项目里既有的位移动画同一量级
 * （`TabNavHost` 的 280ms、日历翻月 220ms），不会显得是另一套节奏。
 *
 * 淡入淡出只有 140ms（[INSPECTOR_SECTION_FADE_MS]）：它是给高度变化"盖一层柔化"的，
 * 用与高度同长的时间会显得迟钝（内容都已经完全展开了，字还在慢慢变清楚）。
 *
 * ⚠️ 这两个 `Dp` / 动画常量**不做成可传参数**：12 个分区的开合节奏必须完全一致，
 * 留一个能各传各的入口，迟早会出现"某一节展开得特别慢"这种没人会发现的不一致。
 *
 * @param title 分区标题（也是卡片标题行上的文字）。
 * @param initiallyExpanded 首次组合 / 进程重建后的默认展开态，见上文的选择理由。
 * @param content 展开时显示的内容。
 */
@Composable
private fun InspectorCollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    // 每个分区各自一份状态，互不影响（理由见上面 KDoc 的第 1 条）。
    // 用 rememberSaveable：进程被回收后重建，用户之前展开的那几节还在原位，
    // 不会把用户刚展开的长分区又合上、逼他再点一次。
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Card(Modifier.fillMaxWidth()) {
        // 卡片内是一个 Column：第一行是标题行（整行可点），下面是内容。
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 整行可点（需求原文「卡片标题行整行可点」）：只有文字可点的话，
                    // 在标题右侧那大片空白上点会毫无反应，而用户的手指点在哪里很难说准。
                    .clickable { expanded = !expanded }
                    .padding(
                        start = INSPECTOR_CARD_PADDING,
                        end = INSPECTOR_CARD_PADDING,
                        top = INSPECTOR_SECTION_HEADER_V_PADDING,
                        bottom = INSPECTOR_SECTION_HEADER_V_PADDING
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InspectorSectionTitle(
                    text = title,
                    // weight(1f) 把箭头推到最右：标题短的时候箭头不会贴着文字，
                    // 而是一直在卡片的右边缘，12 个分区的箭头上下对齐成一列。
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    // ExpandMore / ExpandLess 属于 **material-icons-extended**，
                    // 不在 material-icons-core 里（已反编译 core 的 classes.jar 核实：
                    // filled 包只有 49 个类，没有任何 Expand* 图标）。
                    // 本项目已依赖 material-icons-extended（app/build.gradle.kts 第 116 行），
                    // 因此可用——这与 LabScreen 里「能用核心集就不用扩展集」的纪律不冲突：
                    // 那条纪律是"少一个隐性绑定"，而 core 里根本没有语义合适的展开箭头
                    // （只能用 KeyboardArrowDown / Up 凑，那是下拉与返回顶部的语义）。
                    //
                    // 图标方向与状态一一对应：展开时显示向上的收起箭头，收起时显示向下。
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    // 箭头是"指示器"不是"按钮"：压暗一档，不与标题抢视觉权重。
                    tint = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = INSPECTOR_HEADER_ICON_ALPHA
                    ),
                    modifier = Modifier.size(INSPECTOR_SECTION_TOGGLE_ICON_SIZE)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                // 进入 = 纵向展开 + 淡入；退出 = 纵向收起 + 淡出（参数理由见 KDoc）。
                // expandVertically 的默认 expandFrom 是 Alignment.Top、clip = true：
                // 内容从标题正下方"长出来"，且不会在动画中途溢出卡片边界。
                enter = expandVertically(
                    animationSpec = tween(durationMillis = INSPECTOR_SECTION_EXPAND_MS)
                ) + fadeIn(
                    animationSpec = tween(durationMillis = INSPECTOR_SECTION_FADE_MS)
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = INSPECTOR_SECTION_COLLAPSE_MS)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = INSPECTOR_SECTION_FADE_MS)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = INSPECTOR_CARD_PADDING,
                            end = INSPECTOR_CARD_PADDING,
                            // 收起时不留底部内边距：那时卡片高度就该等于标题行的高度，
                            // 多留一截会造成"空卡片"的观感。
                            bottom = INSPECTOR_CARD_PADDING
                        )
                ) {
                    content()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 分区组件
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 分区小标题 / 折叠卡片标题行上的文字。
 *
 * 视觉语言与 `DeveloperOptionsScreen.DeveloperSectionTitle`、
 * `IconPickerScreen.IconSectionTitle` 完全一致（labelLarge + onSurface 压到 75%）。
 *
 * ⚠️ 本轮改版后**内边距由调用方的标题行负责**（原来是写死在这里的
 * `padding(start = 4.dp, top = 4.dp, bottom = 8.dp)`）：
 * 标题现在落在一张卡片的可点标题行里，内边距必须和那一行保持一致，
 * 否则可点区域与视觉上的卡片边界会错位。
 * 因此这里只保留"文字长什么样"，位置交给 [InspectorCollapsibleSection] 的标题行。
 *
 * **函数名带 `Inspector` 前缀**：`com.morecheck.ui.lab` 包下已有 9 个文件，
 * 顶层 private 函数同名也会在编译期报 Conflicting overloads，所以每个文件都得有
 * 自己专属的名字（`IconSectionTitle`、`DeveloperSectionTitle` 当初也是为此才起的名）。
 *
 * @param modifier 用于 `weight(1f)` 之类由调用方决定的布局修饰符。
 */
@Composable
private fun InspectorSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = modifier
    )
}

/**
 * 一条规格：左标题、右取值、下面一行来源。
 *
 * ## 为什么来源单独占一行而不是挤在右边
 *
 * 来源写的是「文件名：常量名」，中文 + 英文混排通常比取值本身还长；
 * 挤在同一行会把取值压到换行，两个都读不清。单独一行、压暗、小一号，
 * 读的人是"取值读完想核对时"才去看它，不影响第一眼的扫读。
 *
 * ## 与 [InspectorSpecTableRow] 的分工
 *
 * 这一条是**纵向三段式**（标题 / 取值 / 来源各占一行），适合取值说得比较长、
 * 需要成句解释的条目（「按钮尺寸与文字规格」「动画与交互参数」两节用它）。
 * 而「主要页面规格」那种"控件 → 一个数字"的条目用 [InspectorSpecTableRow] 的
 * 左右双栏，扫读时能顺着一条竖线上下比数字。
 *
 * @param label 这一条说的是什么（按钮 / 动画的名字）。
 * @param value 真实取值。
 * @param source 出处（文件名 + 常量名），**每条必填**——这正是本页的价值所在。
 */
@Composable
private fun InspectorSpecRow(
    label: String,
    value: String,
    source: String
) {
    Column(Modifier.fillMaxWidth().padding(vertical = INSPECTOR_SPEC_ROW_V_PADDING)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // 取值用主题色：这一页是拿来"读数字"的，取值比标题更需要抓眼睛。
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "来源：$source",
            style = MaterialTheme.typography.labelSmall,
            // 压到 0.6：比页面上其它副标题（0.75）更淡一档——它是引用信息，
            // 不该和"说明文案"抢同一层视觉权重。
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
        )
    }
}

/**
 * 一条**表格化**的规格：左标题、右数值（右对齐），下面一行来源。
 *
 * ## 为什么做成左右双栏而不是像 [InspectorSpecRow] 那样竖向排
 *
 * 需求对「逐页规格」明确要求"用表格化的视觉（左标题右数值），不要堆成一段话"。
 * 这一节里的条目高度同构（控件名很短、取值是一个数字或几个数字），
 * 竖排会让"标题"和"数字"之间隔着一整个行高的空白，读起来是一列标题一列数字
 * 各自成块；双栏之后，**同一行的标题与数字在同一水平线上**，
 * 上下扫一眼能直接比出"哪个按钮更高"。
 *
 * 两栏用 `weight` 而不是固定 dp 宽度：中文字数与数值长度都会随系统字体缩放变化，
 * 固定宽度在最大字体下会把长取值挤成一列单字。权重取 0.44 / 0.56
 * （[INSPECTOR_SPEC_LABEL_WEIGHT] / [INSPECTOR_SPEC_VALUE_WEIGHT]）：
 * 标题是 2~8 个汉字，取值常带「dp / sp / 百分比」且更长，所以右侧稍宽。
 *
 * 数值右对齐（`TextAlign.End`）：这样右侧形成一条竖线，同一分区的数字能沿着它对齐。
 *
 * @param label 控件名（如「打卡按钮」）。
 * @param value 尺寸或字号（如「通栏；高 50dp；文字 16sp」）。
 * @param source 出处（文件名 + 常量名 / 行内字面量说明），**每条必填**。
 */
@Composable
private fun InspectorSpecTableRow(
    label: String,
    value: String,
    source: String
) {
    Column(Modifier.fillMaxWidth().padding(vertical = INSPECTOR_SPEC_ROW_V_PADDING)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.strongTextColor,
                modifier = Modifier.weight(INSPECTOR_SPEC_LABEL_WEIGHT)
            )
            Spacer(Modifier.width(INSPECTOR_SPEC_TABLE_GAP))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                // 与 [InspectorSpecRow] 同一口径：取值用主题色，是这一页最该被读到的东西。
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(INSPECTOR_SPEC_VALUE_WEIGHT)
            )
        }
        Spacer(Modifier.height(INSPECTOR_LABEL_VALUE_GAP))
        Text(
            text = "来源：$source",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
        )
    }
}

/**
 * 「逐页规格」里的一个页面分组：页面名 + 该页对应的源文件，下面挂这一页的若干条规格。
 *
 * ## 为什么分组标题上要写文件名
 *
 * 需求要求"每一条都必须注明来源文件"，所以每一行其实已经带了自己的来源。
 * 分组标题再写一次文件名不是重复：它让**"这一页对应哪个文件"**在扫读时就能看到，
 * 不用逐行去读小字。同时也暴露了那些"一页写在两个文件里"的情况
 * （如主页的正文在 `HomeScreen.kt`、计时面板在 `TimerPanel.kt`），
 * 分组标题里会把两个文件都列出来。
 *
 * @param pageName 页面名（底部导航栏上的叫法，用户在界面上认得的那个）。
 * @param file 该页的源文件（相对 `app/src/main/java/com/morecheck/`）。
 * @param content 这一页的规格条目。
 */
@Composable
private fun InspectorPageSpecGroup(
    pageName: String,
    file: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = INSPECTOR_PAGE_GROUP_TOP_GAP)
    ) {
        Text(
            text = pageName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(INSPECTOR_LABEL_VALUE_GAP))
        Text(
            text = file,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
        )
        Spacer(Modifier.height(INSPECTOR_PAGE_ROWS_TOP_GAP))
        content()
    }
}

/**
 * 一个**可点按样例**的容器：小标题 + 真控件 + 一行说明它真实规格与来源的字。
 *
 * 三者缺一不可，理由正是这一页的用途：
 * - 只有控件没有文字，用户不知道"这个开关和别处的开关是不是同一个"；
 * - 只有文字没有控件，手感 / 观感无从确认（这正是需求要"可按"的原因）；
 * - 小标题是给"我要找某个控件"的人一眼定位用的。
 *
 * @param title 样例名（如「Switch 开关」）。
 * @param caption 它的真实规格与来源（一行小字，压暗）。
 * @param content 真控件本体。
 */
@Composable
private fun InspectorSampleBlock(
    title: String,
    caption: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = INSPECTOR_SAMPLE_BLOCK_TOP_GAP)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(INSPECTOR_SAMPLE_CONTROL_TOP_GAP))
        content()
        Spacer(Modifier.height(INSPECTOR_SAMPLE_CAPTION_GAP))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
        )
    }
}

/**
 * 一条颜色：色块 + 名称 + hex。
 *
 * 色块用 `MaterialTheme.shapes.small`（本主题的 6dp）做圆角，并加一圈
 * `outlineVariant` 描边：浅色模式下白色 / 极浅的色块压在白色卡片上时，
 * 不描边会看不见边界（`surfaceVariant` 就是纯白）。
 *
 * @param swatch 要展示的颜色。它**不由本页构造**，而是从 AccentColor 或
 *   MaterialTheme.colorScheme 原样传入，因此永远不会出现"色块与 hex 对不上"。
 * @param note 附注（如「默认主题色」），空串则不占位。
 */
@Composable
private fun InspectorColorRow(
    name: String,
    hex: String,
    swatch: Color,
    note: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(INSPECTOR_SWATCH_SIZE)
                .clip(MaterialTheme.shapes.small)
                .background(swatch)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.strongTextColor
            )
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
                )
            }
        }
        Text(
            text = hex,
            style = MaterialTheme.typography.bodyMedium,
            // hex 用等宽：数字字形宽度一致，一列 hex 上下对齐才方便逐位比对。
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 控件样例（可按）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「控件样例（可按）」分区的内容：把 App 里真正在用的控件各放一个**真控件**，
 * 每个下面配一行小字讲清它的真实规格与来源。
 *
 * ## 为什么放真控件而不是画一张示意图
 *
 * 需求要的是"按钮可点击感受"。开关拨动的手感、滑块拖动的跟手程度、
 * 输入框聚焦时描边变粗的时机、芯片按下时有没有水波纹——这些**都读不出来也画不出来**，
 * 必须是真的 `Switch` / `Slider` / `OutlinedTextField`。
 *
 * ## 这些控件的值不会落盘
 *
 * 全部状态都用本页自己的 `rememberSaveable` 持有，**不接仓库、不写 DataStore**：
 * 这一页的定位是只读清单，若把样例滑块接到 `SettingsRepository.splashHoldMs`，
 * 那"拖一下看看手感"就变成了真的改设置——那正是本页承诺不做的事。
 * 样例滑块只是**照抄真实的取值范围与档位**（见 [INSPECTOR_SAMPLE_SLIDER_STEPS]），
 * 让人能感受到"真实的那个滑块拖起来是什么感觉"。
 *
 * ## 顺序为什么是「开关 → 滑块 → 芯片 → 输入框 → 卡片 → 图标按钮」
 *
 * 按"从最常被问到的问题"排：开关和滑块是设置类页面里最常要沟通尺寸的两个；
 * 芯片与输入框是这一页新增内容里被点名要复刻的；卡片与图标按钮是"形状 / 点击区域"
 * 这两类问题的代表。已有的「按压手感」真按钮在下一个分区，**刻意不搬过来**，
 * 因为它那套参数（缩放 / 压暗 / 投影 / 触觉）需要一整段说明才讲得清。
 */
@Composable
private fun InspectorControlSamples() {
    Text(
        text = "以下都是 App 里真正在用的控件（同一个 Material 3 组件、同一套主题）。" +
            "拨动 / 拖动 / 输入都能感受到真实手感；它们的取值只活在本页，" +
            "不会回写任何设置。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.strongTextColor
    )

    InspectorSwitchSample()
    InspectorSliderSample()
    InspectorChipSample()
    InspectorTextFieldSample()
    InspectorCardSample()
    InspectorIconButtonSample()
}

/**
 * 开关样例：**开 / 关两个真开关**并排。
 *
 * ## 为什么放两个而不是一个
 *
 * `Switch` 的选中态与未选中态在视觉上是两套颜色（轨道 + 手柄 + 手柄内图标），
 * 一个开关只能同时显示其中一套。要沟通"关掉时到底长什么样"，
 * 就得让用户自己先拨一下——而沟通时更常见的场景是**指着屏幕说**
 * "就是左边这个浅灰的样子"，两个都在，就不用先拨。
 *
 * 两个开关各自一份 `rememberSaveable` 状态：互相独立，拨一个不会带动另一个。
 */
@Composable
private fun InspectorSwitchSample() {
    // 两个独立状态：默认一开一关，正好把两套外观同时摆在屏幕上。
    var switchOn by rememberSaveable { mutableStateOf(true) }
    var switchOff by rememberSaveable { mutableStateOf(false) }

    InspectorSampleBlock(
        title = "Switch 开关",
        caption = "material3 Switch 默认外观：轨道 52dp × 32dp，" +
            "手柄选中 24dp / 未选中 16dp，状态层 40dp，" +
            "最小交互尺寸 48dp。来源：material3 1.3.1 SwitchTokens（用法见 " +
            "DeveloperOptionsScreen.kt 的 DeveloperSwitchRow）"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = switchOn, onCheckedChange = { switchOn = it })
            Spacer(Modifier.width(INSPECTOR_SWITCH_LABEL_GAP))
            Text(
                text = if (switchOn) "开（选中态）" else "开（已拨到关）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(INSPECTOR_SAMPLE_ROW_GAP))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = switchOff, onCheckedChange = { switchOff = it })
            Spacer(Modifier.width(INSPECTOR_SWITCH_LABEL_GAP))
            Text(
                text = if (switchOff) "关（已拨到开）" else "关（未选中态）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 滑块样例：**照抄开发者选项里「开屏显示时长」滑块的取值域与档位**，可拖动，
 * 旁边给一个当前值读数。
 *
 * ## 为什么要复刻那一个滑块，而不是随便做一个 0~1 的
 *
 * 这一页的用途是"对着这里沟通 UI 问题"。开发者选项里的滑块是本项目唯一一个
 * 有真实档位吸附的滑块，用户实际会拖的就是它；做一个取值域不同的样例滑块，
 * 手感（每档在屏幕上的间距、拖动阻力）与真实的那个**不一样**，参考价值就没了。
 *
 * 取值域与档位直接引用 `SettingsRepository` 的公开常量
 * （[SettingsRepository.MIN_SPLASH_HOLD_MS] / [SettingsRepository.MAX_SPLASH_HOLD_MS] /
 * [SettingsRepository.SPLASH_HOLD_STEP_MS]），不抄字面量：这几个值以后改了，
 * 只要 `steps` 跟着算式走，这一页的滑块仍然与真实滑块同档距。
 * 算式见 [INSPECTOR_SAMPLE_SLIDER_STEPS]。
 *
 * ## 为什么回调里要自己吸附
 *
 * 与 `DeveloperOptionsScreen.SplashHoldRow` 的注释同源：`Slider` 的
 * `onValueChange` 给出的是**连续值**（如 2371.4），`steps` 只负责把**滑块位置**
 * 吸附到档位上，并不会把回调的值整除好。不吸附的话读数会显示「2.371 秒」
 * 这种带小数噪声的东西，与"0.1 秒一档"的档位对不上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectorSliderSample() {
    // 默认值取真实的默认档（900ms），这样一进页面看到的就是"真实滑块平时的样子"。
    var sampleMs by rememberSaveable { mutableStateOf(SettingsRepository.DEFAULT_SPLASH_HOLD_MS) }

    InspectorSampleBlock(
        title = "Slider 滑块（可拖动）",
        caption = "取值域 0 ~ 10000ms（0~10 秒），档距 100ms → steps = " +
            "$INSPECTOR_SAMPLE_SLIDER_STEPS；轨道高 16dp、手柄 44dp × 4dp。" +
            "取值域来源：SettingsRepository.MIN_SPLASH_HOLD_MS / MAX_SPLASH_HOLD_MS / " +
            "SPLASH_HOLD_STEP_MS；轨道与手柄来源：material3 1.3.1 SliderTokens"
    ) {
        // 当前值读数用等宽字体：拖动时数字位数不变，读数不会左右抖。
        Text(
            text = sampleMs.toInspectorSecondsText(),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(INSPECTOR_SAMPLE_ROW_GAP))
        Slider(
            value = sampleMs.toFloat(),
            onValueChange = { raw -> sampleMs = inspectorSnapSlider(raw) },
            valueRange = SettingsRepository.MIN_SPLASH_HOLD_MS.toFloat()..
                SettingsRepository.MAX_SPLASH_HOLD_MS.toFloat(),
            steps = INSPECTOR_SAMPLE_SLIDER_STEPS,
            // 与开发者选项里那个真实滑块同款的自绘平滑轨道（本轮需求：消除刻度锯齿）。
            // 样例的职责是"展示真实手感"——真滑块换了无刻度轨道，这里必须跟着换，
            // 否则两处外观不一致，样例就失去了参考价值（同款理由见上文 caption 的来源标注）。
            track = {
                SmoothSliderTrack(
                    fraction = (sampleMs - SettingsRepository.MIN_SPLASH_HOLD_MS).toFloat() /
                        (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 芯片样例：**逐项照抄 `TimerPanel.kt` 的 `DurationChip`**。
 *
 * ## 复刻的每一项（与来源一一对应）
 *
 * | 项 | 值 | 来源 |
 * |---|---|---|
 * | 形状 | `RoundedCornerShape(50)`（胶囊） | `TimerPanel.kt` DurationChip |
 * | 内边距 | 左右 16dp / 上下 9dp | 同上 |
 * | 字号 | 14sp | 同上 |
 * | 字体 | 等宽（`FontFamily.Monospace`） | 同上 |
 * | 选中底色 | `primaryContainer`，文字 `onPrimaryContainer` | 同上 |
 * | 未选中底色 | `surfaceContainerHigh`，文字 `onSurfaceVariant` | 同上 |
 * | 选中描边 | 1dp `primary` | 同上 |
 * | 手势 | `combinedClickable`（长按也接） | 同上 |
 *
 * 这里**不复用** `DurationChip` 本体：它是 `ui.home` 包里的 private 函数，
 * 引用不到；把它提升成 internal 只为了在本页展示一个样本，是把一个纯展示需求
 * 扩散成跨包 API，代价大于照抄（照抄的风险是"以后那边改了这里不跟着改"，
 * 所以上面把每一项的来源都写清楚了，改的时候有地方对）。
 *
 * 两个芯片（选中 / 未选中）都真的可点：点一下会切换选中态，长按也给一次触觉反馈
 * ——与计时页那个芯片的行为一致，这样"长按删除前的反馈是什么感觉"也能在这里确认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspectorChipSample() {
    val haptic = LocalHapticFeedback.current
    // 选中项用 `Int?` 表示"当前选中的是哪一个芯片"，null = 都没选中。
    // 这样点选中的那个可以取消选中，两个状态都能被用户摆出来看。
    var selectedChip by rememberSaveable { mutableStateOf(INSPECTOR_CHIP_SAMPLE_DEFAULT) }

    InspectorSampleBlock(
        title = "芯片 Chip（可按 / 可长按）",
        caption = "照 TimerPanel.kt 的 DurationChip 复刻：圆角 50%（胶囊）、" +
            "内边距 左右 16dp / 上下 9dp、文字 14sp 等宽；选中态 primaryContainer + " +
            "1dp primary 描边，未选中 surfaceContainerHigh；手势用 combinedClickable"
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(INSPECTOR_CHIP_ROW_SPACING),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InspectorChipBody(
                label = "25:00",
                selected = selectedChip == INSPECTOR_CHIP_SAMPLE_DEFAULT,
                onClick = { selectedChip = INSPECTOR_CHIP_SAMPLE_DEFAULT },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
            InspectorChipBody(
                label = "05:00",
                selected = selectedChip == INSPECTOR_CHIP_SAMPLE_ALTERNATE,
                onClick = { selectedChip = INSPECTOR_CHIP_SAMPLE_ALTERNATE },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }
    }
}

/**
 * 芯片本体。逐项照抄 `TimerPanel.kt` 的 `DurationChip`（对照表见
 * [InspectorChipSample] 的 KDoc）。
 *
 * 与 `DurationChip` 唯一的差异是 `enabled` 恒定可用：那一版有个"计时中不可点"的
 * 门控，这一页没有"计时中"这个概念，控件应该一直能点才有参考意义。
 *
 * ⚠️ 修饰符顺序照抄（clip → background → border → combinedClickable → padding）：
 * `clip` 必须在最前面，否则水波纹会溢出一个方形的底；`padding` 必须在最后，
 * 否则边框会紧贴文字、胶囊两端会显得很挤。
 *
 * @param label 芯片上的文字（时长文本，用等宽字体）。
 * @param selected 选中态决定底色 / 文字色 / 有没有描边。
 * @param onClick 点击回调（本页用来切换选中态）。
 * @param onLongClick 长按回调（本页只给一次触觉反馈，行为与计时页一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InspectorChipBody(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val shape = RoundedCornerShape(INSPECTOR_CHIP_CORNER_PERCENT)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .then(
                if (selected) {
                    Modifier.border(
                        width = INSPECTOR_CHIP_BORDER_WIDTH,
                        color = MaterialTheme.colorScheme.primary,
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
            // combinedClickable 同时覆盖长按与右键（鼠标右键会映射为长按），
            // 与 HomeScreen 里 TaskCircle、TimerPanel 里 DurationChip 的既有写法一致。
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(
                horizontal = INSPECTOR_CHIP_H_PADDING,
                vertical = INSPECTOR_CHIP_V_PADDING
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = INSPECTOR_CHIP_FONT_SIZE,
            // 芯片配色全部取自主题，跟着深色模式与主题色一起变。
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 输入框样例：**照抄智能体页输入栏的 `OutlinedTextField`**
 * （`RoundedCornerShape(20.dp)` + `maxLines = 4` + 占位文案「输入消息…」）。
 *
 * ## 为什么这几项要一致
 *
 * 智能体的输入框是全应用唯一一个"多行 + 大圆角"的输入框，它的圆角（20dp）
 * 与主题的 `shapes.*` 都不同（是有意单独定的），所以"这个框为什么这么圆"
 * 是个会被问到的问题；把同规格的框放在这里，比在对话页里翻找方便。
 *
 * `maxLines = 4` 是让它"长到第四行就不再长高"的那个参数——用户报
 * "输入框会不会把键盘顶掉"时，看的就是它。这里保留同样的值，
 * 所以在这里敲四行字能复现真实的增高行为。
 *
 * 输入内容用 `rememberSaveable`：旋转屏幕 / 进程重建后在这里敲的字不丢
 * （与 `AgentChatScreen` 对输入框的处理同一口径）。
 */
@Composable
private fun InspectorTextFieldSample() {
    var sampleInput by rememberSaveable { mutableStateOf("") }

    InspectorSampleBlock(
        title = "OutlinedTextField 输入框（可输入）",
        caption = "照 AgentChatScreen 输入栏：shape = RoundedCornerShape(20.dp)、" +
            "maxLines = 4、占位文案「输入消息…」；单行容器高 56dp、焦点描边 2dp。" +
            "来源：AgentChatScreen.kt 的 ChatInputBar；容器高度来源 material3 1.3.1 " +
            "OutlinedTextFieldTokens"
    ) {
        OutlinedTextField(
            value = sampleInput,
            onValueChange = { sampleInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入消息…") },
            maxLines = INSPECTOR_INPUT_MAX_LINES,
            shape = RoundedCornerShape(INSPECTOR_INPUT_CORNER_RADIUS)
        )
    }
}

/**
 * 卡片样例：展示**当前主题真实的** `MaterialTheme.shapes.medium`。
 *
 * ## 为什么圆角数值得运行时量、而不是写 8dp
 *
 * `WeChatShapes` 是 `Theme.kt` 里的 private 值，跨文件引用不到；写死就等于把
 * "我抄的"当成"主题里的"。所以这里的圆角直接用 `MaterialTheme.shapes.medium`
 * 本身，读数用 [cornerSizeDp] 现场量（与 [InspectorShapeDemo] 完全同一套口径）。
 * 量不出来时显示「运行时不可测」而**不编一个数**。
 *
 * 容器色用 `surfaceContainerHigh` 而不是默认的 `surfaceContainerLow`：
 * 样例卡片嵌在分区的 `Card` 里，默认容器色与本页卡片几乎同色，
 * 根本看不出这张样例卡片的边界——那样它就没法演示"形状"这件事了。
 */
@Composable
private fun InspectorCardSample() {
    // 与 InspectorShapeDemo 同一套量法：运行时读，不抄字面量。
    val mediumCorner = cornerSizeDp(MaterialTheme.shapes.medium, LocalDensity.current)
    val mediumText = if (mediumCorner == Dp.Unspecified) {
        "运行时不可测"
    } else {
        "${mediumCorner.value.roundToInt()}dp"
    }

    InspectorSampleBlock(
        title = "Card 卡片",
        caption = "形状 = MaterialTheme.shapes.medium（运行时量得 $mediumText）；" +
            "默认海拔 Level0（0dp）。来源：Theme.kt 的 Shapes；" +
            "默认形状对应 material3 1.3.1 FilledCardTokens.ContainerShape = CornerMedium"
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            // 只改容器色（让样例卡片在分区卡片上看得见），形状仍走主题默认——
            // 这样"样例的形状"就是真实的那个默认形状，不会被我改动。
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(Modifier.padding(INSPECTOR_CARD_PADDING)) {
                Text(
                    text = "这是一张真卡片",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(INSPECTOR_LABEL_VALUE_GAP))
                Text(
                    text = "它的圆角就是全应用卡片默认用的那一档（shapes.medium）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                        .copy(alpha = INSPECTOR_BODY_ALPHA)
                )
            }
        }
    }
}

/**
 * 图标按钮样例：**照项目里 `Icons.Filled.Add` 的既有用法**做一个真 `IconButton`，
 * 并显示被点了几次。
 *
 * ## 为什么要显示点击数
 *
 * `IconButton` 的可点区域比它画出来的图标大得多（状态层 40dp，最小交互尺寸 48dp），
 * 这是"点不准也能点中"的设计。光看图标看不出这一点，所以配一个计数器：
 * 在图标**外围**点也能加一，用户就能确认真实的点击范围有多大——
 * 这正是"截图沟通点击区域"这类问题最需要的现场证据。
 *
 * 用 `Icons.Filled.Add`：它与主页 / 发现 / 统计三处右上角菜单按钮、以及
 * 智能体输入栏的加号是同一个图标（`Add` 在 material-icons-core 里，见 LabScreen
 * 对核心集图标的核对口径）。
 */
@Composable
private fun InspectorIconButtonSample() {
    var iconClickCount by rememberSaveable { mutableStateOf(0) }

    InspectorSampleBlock(
        title = "IconButton 图标按钮",
        caption = "Icons.Filled.Add（material-icons-core）；状态层 40dp、" +
            "图标 24dp、最小交互尺寸 48dp。来源：HomeScreen.kt / DiscoverScreen.kt / " +
            "AgentChatScreen.kt 的既有用法；尺寸来源 material3 1.3.1 IconButtonTokens"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { iconClickCount++ }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "加号样例，点击可计数"
                )
            }
            Spacer(Modifier.width(INSPECTOR_SWITCH_LABEL_GAP))
            Text(
                text = "已点 $iconClickCount 次（在图标外围点也算）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 逐页规格
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「主要页面规格（逐页）」分区的内容。
 *
 * ## 为什么要从"概览式清单"扩成"逐页规格"
 *
 * 原来的清单是按**控件类型**列的（所有按钮一条、所有动画一条），
 * 适合作者自己查阅；但用户实际沟通时的说法是
 * **"主页那个打卡按钮多高"**、**"智能体的气泡圆角是多少"**——按页面组织，
 * 才能顺着用户的话直接定位到那一段。
 *
 * ## 每一条的来源都是实际读文件核对过的
 *
 * 全部数值来自逐行阅读对应源文件（不是凭印象）：
 * - 有具名常量的写常量名；
 * - **行内字面量**（如主页标签间隔的 `22.dp`）如实写"行内字面量"，
 *   不硬凑一个不存在的常量名——那样反而会误导人去找一个找不到的名字。
 *
 * ## 为什么分页而不是一次全铺开
 *
 * 每一页用 [InspectorPageSpecGroup] 起一个小标题（页面名 + 源文件），
 * 条目挂在下面。这样竖着滚动时靠页面名就能定位，不用读每一条的来源小字。
 */
@Composable
private fun InspectorPageSpecsContent() {
    Text(
        text = "按页面分组，逐条列出该页关键控件的尺寸与字号，" +
            "每条都注明来源文件（有具名常量的写常量名，行内字面量则如实说明）。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.strongTextColor
    )

    InspectorHomeSpecs()
    InspectorCalendarSpecs()
    InspectorDiscoverSpecs()
    InspectorMineSpecs()
    InspectorLabSpecs()
    InspectorAgentSpecs()
    InspectorSnakeSpecs()
    InspectorSudokuSpecs()
    InspectorDeveloperOptionsSpecs()
}

/** 主页（`AppTab.HOME`）：标题栏标签、打卡按钮、任务圆钮、进度条。 */
@Composable
private fun InspectorHomeSpecs() {
    InspectorPageSpecGroup(
        pageName = "主页（底部导航「主页」）",
        file = "ui/home/HomeScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "标题栏「打卡 / 计时」标签",
            value = "字号 typography.titleLarge（M3 默认 22sp）；" +
                "选中 primary + Bold，未选中 onSurfaceVariant + Normal",
            source = "HomeScreen.kt 第 152-167 行；字号取 typography.titleLarge（第 164 行）"
        )
        InspectorSpecTableRow(
            label = "标题栏标签间隔",
            value = "22dp",
            source = "HomeScreen.kt 第 154 行：Spacer(Modifier.width(22.dp))——" +
                "主页这里是**行内字面量**，没有具名常量（统计页把它提成了 " +
                "TITLE_LABEL_SPACING，两处必须同值）"
        )
        InspectorSpecTableRow(
            label = "打卡通栏按钮（含外层留白）",
            value = "通栏 fillMaxWidth；高 50dp；文字 16sp；" +
                "外层左右各 20dp、上下各 12dp",
            source = "HomeScreen.kt：CheckInButton（第 450-465 行）——" +
                "高度与字号是行内字面量，无具名常量"
        )
        InspectorSpecTableRow(
            label = "任务圆钮直径",
            value = "64dp（CircleShape）；整列宽 76dp",
            source = "HomeScreen.kt：TaskCircle（第 486-496 行）"
        )
        InspectorSpecTableRow(
            label = "任务圆钮内的图标",
            value = "26sp（emoji 文字）",
            source = "HomeScreen.kt 第 504 行：Text(task.icon, fontSize = 26.sp)"
        )
        InspectorSpecTableRow(
            label = "任务圆钮勾选动画",
            value = "snapTo(0.8f) → spring(MediumBouncy, StiffnessHigh) 弹回 1.0",
            source = "HomeScreen.kt：TaskCircle 的 LaunchedEffect（第 476-485 行）"
        )
        InspectorSpecTableRow(
            label = "已打卡角标",
            value = "直径 22dp；描边 2dp（surface 色）；对勾图标 14dp",
            source = "HomeScreen.kt 第 506-522 行"
        )
        InspectorSpecTableRow(
            label = "打卡进度条",
            value = "高 8dp；圆角 4dp；动画 tween(350)，" +
                "并显式关掉轨道末端的停止指示点",
            source = "HomeScreen.kt：PROGRESS_ANIM_MS = 350（第 89 行）；" +
                "进度条本体与 drawStopIndicator = {} 在第 375-393 行"
        )
        InspectorSpecTableRow(
            label = "主页正文留白与字号",
            value = "内容区左右 20dp / 上下 16dp；日期 titleMedium、" +
                "主问句 headlineSmall + Bold、完成数 bodyMedium",
            source = "HomeScreen.kt：HomeContent（第 346-370 行）"
        )
        InspectorSpecTableRow(
            label = "任务横排与选择弹层",
            value = "任务圆钮横向间距 16dp（可横向滚动）；" +
                "选择弹层每行圆点 40dp、图标 20sp、行内边距上下 10dp",
            source = "HomeScreen.kt 第 404-431 行（横排）与 564-601 行（TaskPickerSheet）"
        )
    }
}

/** 统计（月历 + 记录两个面板）。 */
@Composable
private fun InspectorCalendarSpecs() {
    InspectorPageSpecGroup(
        pageName = "统计（底部导航「统计」）",
        file = "ui/calendar/CalendarScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "标题栏「统计 / 记录」标签字号",
            value = "typography.titleLarge（M3 默认 22sp）；选中 Bold + primary",
            source = "CalendarScreen.kt 第 168 行；与主页同源引用同一个语义 token"
        )
        InspectorSpecTableRow(
            label = "标签间隔",
            value = "22dp",
            source = "CalendarScreen.kt：TITLE_LABEL_SPACING = 22.dp（第 105 行）——" +
                "需求要求「必须与主页相等」，主页那边是行内字面量"
        )
        InspectorSpecTableRow(
            label = "右键菜单（MoreVert）",
            value = "触发图标 MoreVert；菜单圆角 16dp；容器色 surfaceContainerHigh",
            source = "CalendarScreen.kt 第 190-198 行；这套规格在主页 / 发现 / 统计三处统一"
        )
        InspectorSpecTableRow(
            label = "年月标题",
            value = "titleLarge + Bold；可点区域圆角 8dp（点了进全年总览）",
            source = "CalendarScreen.kt 第 306-315 行"
        )
        InspectorSpecTableRow(
            label = "翻月动画",
            value = "animateScrollToPage(animationSpec = tween(220))",
            source = "CalendarScreen.kt 第 300 行（上月）与第 316 行（下月）"
        )
        InspectorSpecTableRow(
            label = "星期表头",
            value = "labelMedium + onSurfaceVariant；每格 weight(1f)；行左右留白 8dp",
            source = "CalendarScreen.kt 第 321-331 行"
        )
        InspectorSpecTableRow(
            label = "月历格子",
            value = "正方形 (aspectRatio 1f)；格内留白 2dp；圆角 10dp；" +
                "每行 7 格各 weight(1f)、行左右留白 8dp",
            source = "CalendarScreen.kt：DayCell 第 413-419 行、MonthGrid 第 364 行"
        )
        InspectorSpecTableRow(
            label = "格子里的日期圆",
            value = "直径 26dp；日期文字 bodyMedium；今天用 primary 底 + 白字 + Bold",
            source = "CalendarScreen.kt 第 422-435 行"
        )
        InspectorSpecTableRow(
            label = "打卡圆点",
            value = "每颗 6dp；间距 2dp；圆点行高 8dp；最多显示 5 颗，" +
                "超出的显示「+N」",
            source = "CalendarScreen.kt 第 410 行（take(5)）与第 437-451 行"
        )
        InspectorSpecTableRow(
            label = "日期详情弹窗",
            value = "任务圆点 12dp + 间隔 10dp；任务名 bodyLarge；" +
                "打卡时刻 labelSmall；补打卡窗口为最近 7 天",
            source = "CalendarScreen.kt 第 473 行（7 天）、第 498-528 行"
        )
        InspectorSpecTableRow(
            label = "pager 初始化",
            value = "起始页 1200，共 2400 页（前后各 100 年）",
            source = "CalendarScreen.kt 第 129 行"
        )
    }
}

/** 发现（含右上角菜单与列表行）。 */
@Composable
private fun InspectorDiscoverSpecs() {
    InspectorPageSpecGroup(
        pageName = "发现（底部导航「发现」）",
        file = "ui/nav/DiscoverScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "右上角「+」按钮与菜单",
            value = "触发图标 Icons.Filled.Add（无障碍文案「更多」）；" +
                "菜单圆角 16dp；容器色 surfaceContainerHigh",
            source = "DiscoverScreen.kt 第 107-127 行；三处（主页 / 发现 / 统计）统一规格"
        )
        InspectorSpecTableRow(
            label = "分组小标题",
            value = "labelMedium + onSurfaceVariant；左起 28dp、右 16dp、上 4dp",
            source = "DiscoverScreen.kt 第 171-176 行"
        )
        InspectorSpecTableRow(
            label = "列表卡片与行间距",
            value = "卡片左右留白 16dp；卡片之间 12dp；列表内容上下留白 12dp",
            source = "DiscoverScreen.kt 第 164-183 行（LazyColumn 的 contentPadding 与 " +
                "verticalArrangement）"
        )
        InspectorSpecTableRow(
            label = "列表行高（内边距）",
            value = "左右 16dp / 上下 14dp（行高由内容 + 这组内边距决定）",
            source = "DiscoverScreen.kt：DiscoverRow 第 237 行"
        )
        InspectorSpecTableRow(
            label = "行图标底与图标",
            value = "34dp 圆角方块（圆角 8dp，色 primaryContainer）；" +
                "图标内边距 8dp（即图标约 18dp），tint onPrimaryContainer",
            source = "DiscoverScreen.kt 第 240-251 行"
        )
        InspectorSpecTableRow(
            label = "行标题与间隔",
            value = "bodyLarge + Medium + strongTextColor；图标与文字间隔 14dp",
            source = "DiscoverScreen.kt 第 253-262 行"
        )
        InspectorSpecTableRow(
            label = "未读角标",
            value = "圆形（CircleShape）+ error / onError；labelSmall；" +
                "内边距左右 6dp、上下 1dp；超过 99 显示「99+」",
            source = "DiscoverScreen.kt 第 264-278 行"
        )
        InspectorSpecTableRow(
            label = "行分隔线",
            value = "outlineVariant；左缩进 64dp",
            source = "DiscoverScreen.kt 第 205-210 行"
        )
    }
}

/** 我的（时钟、头像、列表行）。 */
@Composable
private fun InspectorMineSpecs() {
    InspectorPageSpecGroup(
        pageName = "我的（底部导航「我的」）",
        file = "ui/mine/MyScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "顶部时钟字号",
            value = "30sp + Bold + primary；等宽字体；右侧「当前时间」labelMedium",
            source = "MyScreen.kt 第 246-259 行（字号 30.sp 在第 250 行）"
        )
        InspectorSpecTableRow(
            label = "时钟刷新间隔",
            value = "1000ms（每秒一跳）",
            source = "MyScreen.kt：CLOCK_TICK_MS = 1_000L（第 66 行）"
        )
        InspectorSpecTableRow(
            label = "头像尺寸",
            value = "60dp 圆形（CircleShape）",
            source = "MyScreen.kt：ProfileAvatar 第 340 行（占位）与第 360 行（真头像）"
        )
        InspectorSpecTableRow(
            label = "灰头像占位",
            value = "底色 surfaceContainerHighest；人形图标内边距 12dp（图标约 36dp）",
            source = "MyScreen.kt 第 337-348 行"
        )
        InspectorSpecTableRow(
            label = "头像卡片",
            value = "整张卡片可点；行内边距 16dp；头像与文字间隔 16dp；" +
                "主标题 titleMedium + Bold、副标题 bodySmall",
            source = "MyScreen.kt：ProfileCard 第 277-303 行"
        )
        InspectorSpecTableRow(
            label = "列表行（任务管理 / 打卡记录 / 设置 / 数据备份 / 关于我们）",
            value = "左右 16dp / 上下 14dp；图标 tint primary；" +
                "图标与文字间隔 16dp；标题 bodyLarge + strongTextColor、" +
                "副标题 labelSmall + onSurfaceVariant",
            source = "MyScreen.kt：MineRow 第 381-417 行"
        )
        InspectorSpecTableRow(
            label = "列表分隔线",
            value = "outlineVariant；左缩进 56dp",
            source = "MyScreen.kt：MineDivider 第 420-424 行"
        )
        InspectorSpecTableRow(
            label = "卡片间距与页面留白",
            value = "卡片之间 12dp；页面左右留白 16dp",
            source = "MyScreen.kt：MyContent 第 166-221 行"
        )
    }
}

/** 实验室（宫格 + 底部按钮）。 */
@Composable
private fun InspectorLabSpecs() {
    InspectorPageSpecGroup(
        pageName = "实验室（发现 → 实验室）",
        file = "ui/lab/LabScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "宫格布局",
            value = "两列（chunked(2)），每格 weight(1f) 等宽；" +
                "行间距 12dp、列间距 12dp；宫格左右 16dp / 上下 12dp",
            source = "LabScreen.kt 第 598-644 行"
        )
        InspectorSpecTableRow(
            label = "宫格卡片内边距",
            value = "左右 14dp / 上下 18dp",
            source = "LabScreen.kt：LabCard 第 814-817 行"
        )
        InspectorSpecTableRow(
            label = "宫格图标",
            value = "24dp；tint primary；与文字间隔 12dp",
            source = "LabScreen.kt 第 820-826 行"
        )
        InspectorSpecTableRow(
            label = "宫格标题文字",
            value = "15sp + Medium；最多 2 行、尾部省略",
            source = "LabScreen.kt 第 827-834 行（字号 15.sp 在第 829 行）"
        )
        InspectorSpecTableRow(
            label = "底部「新增功能」按钮",
            value = "通栏；高 50dp；文字 16sp；外层左右 16dp / 上下 16dp，" +
                "并带系统导航栏内边距",
            source = "LabScreen.kt 第 651-665 行"
        )
        InspectorSpecTableRow(
            label = "标题栏",
            value = "容器与标题栏均透明（让渐变透上来）；" +
                "前景色由渐变起点色明度决定（明度 > 0.5 用近黑，否则白）",
            source = "LabScreen.kt 第 576-589 行与 backdropForegroundOf（第 754-755 行）"
        )
    }
}

/** 智能体（对话版式：顶栏、气泡、输入栏、加号菜单）。 */
@Composable
private fun InspectorAgentSpecs() {
    InspectorPageSpecGroup(
        pageName = "智能体（实验室 → 智能体）",
        file = "ui/agent/AgentChatScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "顶栏",
            value = "标题「智能体」；左侧 navigationIcon 槽位放 Row（返回 + 历史两个 " +
                "IconButton）；右侧 actions 一个设置 IconButton",
            source = "AgentChatScreen.kt 第 294-319 行"
        )
        InspectorSpecTableRow(
            label = "消息区底色与内边距",
            value = "容器色 surfaceVariant（微信式灰底）；" +
                "列表左右 12dp / 上下 10dp；消息之间 8dp",
            source = "AgentChatScreen.kt 第 290-292 行与第 364-374 行"
        )
        InspectorSpecTableRow(
            label = "气泡最大宽度比例",
            value = "屏宽的 78%（0.78）",
            source = "AgentChatScreen.kt：BUBBLE_MAX_WIDTH_FACTOR = 0.78f（第 1240 行）；" +
                "用法在第 536-537 行"
        )
        InspectorSpecTableRow(
            label = "气泡圆角（用户）",
            value = "RoundedCornerShape(16, 4, 16, 16)——右上角收小，指向说话者",
            source = "AgentChatScreen.kt 第 598-599 行"
        )
        InspectorSpecTableRow(
            label = "气泡圆角（AI）",
            value = "RoundedCornerShape(4, 16, 16, 16)——左上角收小",
            source = "AgentChatScreen.kt 第 600-602 行"
        )
        InspectorSpecTableRow(
            label = "气泡内边距",
            value = "纯文本：左右 12dp / 上下 8dp；图片气泡：左右 4dp / 上下 4dp",
            source = "AgentChatScreen.kt：MessageBubble 第 622-627 行"
        )
        InspectorSpecTableRow(
            label = "气泡内图片最大宽度",
            value = "200dp（FillWidth 等比缩放）",
            source = "AgentChatScreen.kt：BUBBLE_IMAGE_MAX_WIDTH = 200.dp（第 1243 行）"
        )
        InspectorSpecTableRow(
            label = "输入栏外框",
            value = "顶部两角圆角 20dp；容器色 surfaceContainer；" +
                "内边距左右 8dp / 上下 8dp；底部让位用 safeDrawing 的 Bottom 单源取值",
            source = "AgentChatScreen.kt：ChatInputBar 第 727-748 行"
        )
        InspectorSpecTableRow(
            label = "输入框",
            value = "shape = RoundedCornerShape(20.dp)；maxLines = 4；" +
                "占位文案「输入消息…」",
            source = "AgentChatScreen.kt 第 766-773 行（本页「控件样例」里按同规格复刻）"
        )
        InspectorSpecTableRow(
            label = "附件预览区高度",
            value = "恒定 72dp（无附件时为 0dp，加图前后输入行不跳）",
            source = "AgentChatScreen.kt 第 751 行（注释亦见第 703 行「0 / 72dp」）"
        )
        InspectorSpecTableRow(
            label = "附件小预览",
            value = "64dp 方块（圆角取 shapes.small）；右上角移除按钮：圆形、" +
                "底色 scrim α0.6、白叉 14dp",
            source = "AgentChatScreen.kt：AttachPreview 第 829-863 行"
        )
        InspectorSpecTableRow(
            label = "加号菜单",
            value = "AlertDialog（标题「添加图片」）；两个整行可点项，" +
                "行圆角 shapes.medium、内边距左右 4dp / 上下 14dp，" +
                "图标与文字间隔 12dp、文字 bodyLarge；无确认按钮",
            source = "AgentChatScreen.kt 第 456-510 行"
        )
        InspectorSpecTableRow(
            label = "历史列表行",
            value = "左右 16dp / 上下 12dp；选中底色 surfaceContainerHighest；" +
                "标题 bodyLarge、时间与预览 bodySmall；分隔线左缩进 16dp",
            source = "AgentChatScreen.kt：HistoryRow 第 1152-1197 行与第 1111-1114 行"
        )
        InspectorSpecTableRow(
            label = "「选择部分复制」弹窗",
            value = "选择区最大高度 360dp（超出在框内滚动）",
            source = "AgentChatScreen.kt：SELECTION_DIALOG_MAX_HEIGHT = 360.dp（第 1246 行）"
        )
    }
}

/** 开发者选项（本页的入口页）。 */
@Composable
private fun InspectorDeveloperOptionsSpecs() {
    InspectorPageSpecGroup(
        pageName = "开发者选项（实验室 → 开发者选项）",
        file = "ui/lab/DeveloperOptionsScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "动作按钮固定宽度",
            value = "104dp（硬宽度，不随文案变化）",
            source = "DeveloperOptionsScreen.kt：ACTION_BUTTON_WIDTH = 104.dp（第 1147 行）"
        )
        InspectorSpecTableRow(
            label = "动作按钮高度",
            value = "40dp（OutlinedButton 默认容器高）；文字 labelLarge 单行居中",
            source = "material3 1.3.1：OutlinedButtonTokens.ContainerHeight = 40.0；" +
                "单行见 DeveloperOptionsScreen.kt 第 664 行 maxLines = 1"
        )
        InspectorSpecTableRow(
            label = "开关行 / 动作行内边距",
            value = "左右 16dp / 上下 14dp；左侧文字区与右侧控件之间留 12dp",
            source = "DeveloperOptionsScreen.kt：DeveloperSwitchRow 第 571 行、" +
                "DeveloperActionRow 第 629 与 635 行"
        )
        InspectorSpecTableRow(
            label = "分区间距",
            value = "20dp",
            source = "DeveloperOptionsScreen.kt：SECTION_SPACING = 20.dp（第 992 行）"
        )
        InspectorSpecTableRow(
            label = "滑块行内边距",
            value = "左右 16dp / 上下 14dp；滑块通栏；「恢复默认」文字按钮在滑块下方靠右",
            source = "DeveloperOptionsScreen.kt：SplashHoldRow 第 709-713 与 781-789 行"
        )
        InspectorSpecTableRow(
            label = "开屏时长滑块的取值域与档位",
            value = "0 ~ 10000ms（0~10 秒），档距 100ms → steps = " +
                "$INSPECTOR_SAMPLE_SLIDER_STEPS",
            source = "DeveloperOptionsScreen.kt：SPLASH_HOLD_STEPS（第 1044-1046 行）= " +
                "(MAX - MIN) / STEP - 1，三个常量取自 SettingsRepository（第 643-666 行）。" +
                "⚠️ 该常量上方的 KDoc 仍写着「0.5 秒一档共 21 档 → steps = 19」，" +
                "那是档距改 100ms 之前的旧口径，与现在的算式不一致（文档滞后，代码为准）"
        )
        InspectorSpecTableRow(
            label = "子项置灰系数",
            value = "0.38f（只乘在左侧文字上，不套整行）",
            source = "DeveloperOptionsScreen.kt：DISABLED_CONTENT_ALPHA = 0.38f（第 983 行）"
        )
        InspectorSpecTableRow(
            label = "通知测试冷却",
            value = "10 秒（冷却期间按钮上显示剩余秒数）",
            source = "DeveloperOptionsScreen.kt：NOTIFY_TEST_COOLDOWN_SECONDS = 10（第 1134 行）"
        )
        InspectorSpecTableRow(
            label = "本页（设备信息查询）入口行",
            value = "标题「设备信息查询」；说明「只读地列出按钮尺寸、圆角、颜色与动画参数" +
                "（附来源文件），用于沟通 UI 规格」；按钮文案「进入」",
            source = "DeveloperOptionsScreen.kt：UI_INSPECTOR_TITLE（第 1086 行）与 " +
                "UI_INSPECTOR_DESCRIPTION（第 1094 行）"
        )
    }
}

/** 贪吃蛇页（实验室 → 贪吃蛇）：WebView 游戏 + 本页开屏层 + 标题栏开关。 */
@Composable
private fun InspectorSnakeSpecs() {
    InspectorPageSpecGroup(
        pageName = "贪吃蛇（实验室 → 贪吃蛇）",
        file = "ui/lab/SnakeScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "页面地址",
            value = "file:///android_asset/snake/index.html",
            source = "SnakeScreen.kt：SNAKE_PAGE_URL；" +
                "贪吃蛇只用同目录的相对引用，不需要 https origin"
        )
        InspectorSpecTableRow(
            label = "WebView 兜底色",
            value = "#080B14（固定深色，不跟随主题）",
            source = "SnakeScreen.kt：SNAKE_BACKGROUND_COLOR；" +
                "与 assets/snake/style.css 的 --bg-0 同值，两者必须同步改"
        )
        InspectorSpecTableRow(
            label = "本页开屏层停留 / 淡出",
            value = "停留 1000ms；淡出 220ms；文字入场 420ms + 上浮 24px",
            source = "SnakeScreen.kt：SNAKE_SPLASH_HOLD_MS / SNAKE_SPLASH_FADE_MS / " +
                "SNAKE_SPLASH_TEXT_ENTER_MS / SNAKE_SPLASH_TEXT_LIFT_PX；" +
                "后三个值与 SplashScreen / MomentsScreen 同款，保证三处观感一致"
        )
        InspectorSpecTableRow(
            label = "开屏层配色",
            value = "底色 colorScheme.background（跟随主题）；文字 colorScheme.primary（跟随主题色）",
            source = "SnakeScreen.kt：SnakeSplashOverlay；" +
                "⚠️ 与 WebView 兜底色是**两个不同职责**的颜色，刻意不同值"
        )
        InspectorSpecTableRow(
            label = "转场（进入 / 退出）",
            value = "slideInVertically / slideOutVertically，240ms",
            source = "TabNavHost.kt：SNAKE_TRANSITION_MS = 240；" +
                "只覆盖本路由的 enter 与 popExit，全局仍是微信式左右推入"
        )
        InspectorSpecTableRow(
            label = "标题栏开关",
            value = "默认显示；关掉后条件渲染（64dp 高度还给游戏区）",
            source = "DeveloperOptionsRepository.snakeTitleBarEnabled → SnakeScreen 形参；" +
                "总开关关时回落「显示」（= 改造前行为）"
        )
    }
}

/** 数独页（实验室 → 数独）：WebView 游戏 + 本页开屏层 + 标题栏开关（需求 17）。 */
@Composable
private fun InspectorSudokuSpecs() {
    InspectorPageSpecGroup(
        pageName = "数独（实验室 → 数独，需求 17 新增）",
        file = "ui/lab/SudokuScreen.kt"
    ) {
        InspectorSpecTableRow(
            label = "页面地址",
            value = "https://appassets.androidplatform.net/assets/sudoku/index.html",
            source = "SudokuScreen.kt：SUDOKU_PAGE_URL；" +
                "⚠️ 刻意不用 file:///：数独用 Blob 起 Worker 生成题目，" +
                "file:// 的 opaque origin 会被 Chromium 以 SecurityError 拒绝"
        )
        InspectorSpecTableRow(
            label = "WebView 兜底色",
            value = "#12151C（固定深色，不跟随主题）",
            source = "SudokuScreen.kt：SUDOKU_BACKGROUND_COLOR；" +
                "与 assets/sudoku/index.html 的 --c-bg 同值（那页写死深色、无亮色分支）"
        )
        InspectorSpecTableRow(
            label = "本页开屏层停留 / 淡出",
            value = "停留 1000ms；淡出 220ms；文字入场 420ms + 上浮 24px（与贪吃蛇逐项同值）",
            source = "SudokuScreen.kt：SUDOKU_SPLASH_HOLD_MS 等同名四常量；" +
                "需求 17 原话「打开和贪吃蛇一样也显示 helloworld」——同值才能算「一样」"
        )
        InspectorSpecTableRow(
            label = "转场（进入 / 退出）",
            value = "slideInVertically / slideOutVertically，240ms（与贪吃蛇同款）",
            source = "TabNavHost.kt：数独 composable 复用 snakeEnter / snakePopExit"
        )
        InspectorSpecTableRow(
            label = "离开前主动落盘",
            value = "onRelease 时 evaluateJavascript 调 flushSave()",
            source = "SudokuScreen.kt：onRelease；" +
                "页面自己的保存是 200ms 防抖，而 beforeunload 在 WebView 里不保证触发，" +
                "不补这一下会丢掉最后 200ms 内填的数"
        )
        InspectorSpecTableRow(
            label = "开屏 / 标题栏开关",
            value = "「游戏开屏显示」与「游戏标题栏显示」两个开关，默认开",
            source = "DeveloperOptionsRepository.gameSplashEnabled / gameTitleBarEnabled；" +
                "开屏开关两个游戏页共用，标题栏开关只作用于数独（贪吃蛇有自己那个）"
        )
        InspectorSpecTableRow(
            label = "新增依赖",
            value = "androidx.webkit:webkit 1.17.1",
            source = "gradle/libs.versions.toml 与 app/build.gradle.kts；" +
                "只用 WebViewAssetLoader 一个类，不为它引第三方库；" +
                "版本从 google() 仓库的 maven-metadata.xml 核实为最新稳定版"
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 可交互演示
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「按压手感」演示：一枚**真按钮**，参数逐项照 `TimerPanel.kt` 的 `TimerActionButton`。
 *
 * ## 为什么要在规格页里放一个真按钮
 *
 * 手感这件事读数字读不出来：「0.84 的缩放配 MediumBouncy」到底是"干脆"还是"软"，
 * 必须按一下。用户（和我）在讨论"按下看不看得出来""弹回来是不是过头了"时，
 * 需要一个**可复现**的样本——这一枚就是。
 *
 * ## 为什么把缩放目标值与当前值都显示出来
 *
 * 需求明确要求"页面要显示当前缩放目标值"。除目标值外还显示了**动画中的实时值**：
 * 只看目标值无法判断"弹簧是不是一闪而过"，而实时值在按下 / 松开时会连续变化，
 * 一眼就能看出过渡时长与过冲幅度（松开时它会短暂冲过 1.00）。实时值是按帧读的
 * `animateFloatAsState` 返回值，因此它本身就是动画的忠实读数。
 *
 * @see InspectorPressButton
 */
@Composable
private fun InspectorPressDemo() {
    // 与计时按钮同一个交互源写法：indication = null（用缩放代替水波纹，
    // 圆形按钮上的方形涟漪会很难看），按压态由 interactionSource 给出。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // 目标值：按压态决定，和计时按钮一样是 0.84 / 1.00 两档。
    val target = if (pressed) INSPECTOR_PRESS_SCALE else 1f

    // ★ 缩放动画**上提到这里**（而不是留在按钮内部）：这一页要把"当前值"显示出来，
    // 而动画只有它的持有者读得到。上提之后按钮只负责把 scale 画到 graphicsLayer 上，
    // 两者读的是同一个 `animateFloatAsState`，不存在"读数与画面不同步"的可能。
    //
    // 弹簧规格与计时页逐字相同：按下走偏硬的 NoBouncy + StiffnessMediumLow
    //（快速"陷下去"、不回弹过头），松开走 MediumBouncy + StiffnessMedium
    //（回落带一点过冲，读作"弹回来"）。同一套 spec 用在两个方向会让按下也拖泥带水。
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = if (pressed) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
        } else {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        },
        label = "inspectorPressScale"
    )

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "缩放目标值：${target.toScaleText()}　当前值：${scale.toScaleText()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // 只看目标值判断不了"弹簧是不是一闪而过"，所以把动画的实时值也读出来：
            // 按住 / 松开时它会连续变化，松开瞬间还会短暂冲过 1.00（过冲 = 弹簧在回弹）。
            text = "按下去，上面这个「当前值」会跟着动；松开时它会短暂冲过 1.00（弹簧过冲）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            InspectorPressButton(
                interactionSource = interactionSource,
                pressed = pressed,
                scale = scale
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "参数：按下 ${INSPECTOR_PRESS_SCALE.toScaleText()} / 松开 " +
                "${1f.toScaleText()}；图标额外缩到 " +
                "${INSPECTOR_PRESS_ICON_SCALE.toScaleText()}、透明度 " +
                "${INSPECTOR_PRESS_ICON_ALPHA.toScaleText()}；" +
                "容器色向 scrim 混 ${INSPECTOR_PRESS_CONTAINER_DARKEN.toScaleText()}；" +
                "投影 ${INSPECTOR_ELEVATION_IDLE.value.roundToInt()}dp ↔ " +
                "${INSPECTOR_ELEVATION_PRESSED.value.roundToInt()}dp；" +
                "松开时也会触发一次长按触觉反馈。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/**
 * 演示按钮本体。**逐项照抄 `TimerPanel.kt` 的 `TimerActionButton`**：
 *
 * - 缩放：由调用方传入（见 [InspectorPressDemo] 的说明：这一页要把当前值显示出来，
 *   所以动画由调用方持有），弹簧规格与那里逐字相同；
 * - 容器色：`lerp(container, scrim, 0.22f)`——用主题的 `scrim` 而不是 `Color.Black`，
 *   这样不引入硬编码颜色，深浅两套主题里 `scrim` 都是纯黑，正是"压暗"需要的色；
 * - 投影：8dp ↔ 2dp，配合缩放读作"凸起 → 压平"；
 * - 顶部高光：一层白色竖直渐变（alpha 0.18 → 0），模拟左上光源；
 * - 描边：1dp 主题色（alpha 0.12），让纯色填充与浅色背景之间有一道分界。
 *
 * ## 修饰符顺序不能改
 *
 * `shadow` → `graphicsLayer`（缩放）→ `clip` → `background`。
 * - `shadow` 在缩放层**外面**：阴影要跟着按钮一起缩，否则按钮缩小、阴影不动，
 *   看起来像"按钮从阴影上掉下来了"；
 * - `clip` 在缩放层**里面**：反过来时缩放动画每一帧都要在已被缩放的坐标系里重算
 *   裁剪路径，圆形边缘会退化成多边形（本项目在圆形图标上实测踩过）。
 *
 * ⚠️ 高光用的 `Color.White` 是**半透明叠加层**，不是新的语义色——这与
 * `TimerPanel.kt` 的写法一致（那里也写 `Color.White`）。换成主题色提亮会在浅色主题色
 * （如青色）上彻底看不见，所以这里刻意不换。
 *
 * @param interactionSource 由调用方持有，好让上方的读数能同时读到按压态。
 * @param pressed 当前是否被按住（决定颜色与投影的目标值）。
 * @param scale 当前缩放值（由调用方按同一按压态算出，与上方读数同源）。
 */
@Composable
private fun InspectorPressButton(
    interactionSource: MutableInteractionSource,
    pressed: Boolean,
    scale: Float
) {
    val haptic = LocalHapticFeedback.current

    // 图标的两条通道仍归按钮自己管：它们只影响按钮内部，没有对外读数需求。
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) INSPECTOR_PRESS_ICON_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "inspectorPressIconScale"
    )
    val iconAlpha by animateFloatAsState(
        targetValue = if (pressed) INSPECTOR_PRESS_ICON_ALPHA else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "inspectorPressIconAlpha"
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) INSPECTOR_ELEVATION_PRESSED else INSPECTOR_ELEVATION_IDLE,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "inspectorPressElevation"
    )

    // 演示用单按钮 = 主操作，所以取 primary / onPrimary（与计时页"单个开始按钮"同档）。
    val container = MaterialTheme.colorScheme.primary
    val content = MaterialTheme.colorScheme.onPrimary
    val pressedContainer = lerp(container, MaterialTheme.colorScheme.scrim, INSPECTOR_PRESS_CONTAINER_DARKEN)
    val animatedContainer by animateColorAsState(
        targetValue = if (pressed) pressedContainer else container,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "inspectorPressContainer"
    )
    val highlightAlpha = if (pressed) INSPECTOR_HIGHLIGHT_PRESSED else INSPECTOR_HIGHLIGHT_IDLE

    // 形状取当前主题的胶囊圆角（50%），与计时页"开始"按钮一致。
    val shape = RoundedCornerShape(50)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                // ⚠️ 尺寸挂在 Box 上（而不是里面那个 Text）：`shadow` / `clip` / `background`
                // 全都作用在 Box 上，尺寸给 Text 会让外框跟着文字走——而这一页刻意要
                // **固定外框**，让"按下时只有缩放在变"这件事看得清楚。
                .width(INSPECTOR_DEMO_BUTTON_WIDTH)
                .height(INSPECTOR_DEMO_BUTTON_HEIGHT)
                .shadow(elevation = elevation, shape = shape, clip = false)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(animatedContainer)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = highlightAlpha),
                            Color.Transparent
                        )
                    ),
                    shape = shape
                )
                .border(width = 1.dp, color = content.copy(alpha = 0.12f), shape = shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    // 与计时按钮同款反馈。这里没有业务动作——它就是一个手感样本。
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (pressed) "松开" else "按住我",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = content.copy(alpha = content.alpha * iconAlpha),
                maxLines = 1,
                // 文字跟着图标那条通道一起缩小：计时页里"图标缩到 0.90"就是让内容
                // 与容器同向"陷下去"，这里用文字代替图标，参数保持同源才叫样本一致。
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
            )
        }
    }
}

/**
 * 圆角演示：把当前主题的 5 档 Shapes 各画一个色块，并标出**运行时量到的** dp 值。
 *
 * ## 为什么不直接把 4 / 6 / 8 / 10 / 14 写死
 *
 * 那几个数是 `Theme.kt` 里 `WeChatShapes` 的声明值，但 `WeChatShapes` 是 **private**，
 * 跨文件引用不到。写死就等于把"我抄的"当成"主题里的"，改了主题这里不会跟着变。
 * 所以：**色块直接画 `MaterialTheme.shapes.*` 本身**（一定是最新的真实形状），
 * 标签则用 `CornerSize.toPx` 现场量出来（见 [cornerSizeDp]）。
 *
 * 量的过程对 `RoundedCornerShape` 是精确的（固定 dp 的 `CornerSize` 忽略传入尺寸，
 * 直接返回 dp 换算的像素）；万一将来主题换成百分比圆角或非圆角形状，
 * [cornerSizeDp] 会返回 `Dp.Unspecified`，这里就显示「运行时不可测」而不是编一个数。
 */
@Composable
private fun InspectorShapeDemo() {
    val density = LocalDensity.current
    val shapes = MaterialTheme.shapes

    // 五档按 Material 的从小到大的顺序排，和用户在别处看到的命名一致。
    val entries: List<Pair<String, Shape>> = listOf(
        "extraSmall" to shapes.extraSmall,
        "small" to shapes.small,
        "medium" to shapes.medium,
        "large" to shapes.large,
        "extraLarge" to shapes.extraLarge
    )

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { (name, shape) ->
            // 变量名刻意不叫 `dp`：本文件里 `N.dp` 这个扩展属性到处在用，
            // 同名局部变量会让人读代码时先愣一下（能用，但没必要让人多花这一秒）。
            val cornerDp = cornerSizeDp(shape, density)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(INSPECTOR_SHAPE_DEMO_SIZE)
                        // 用主题色填充：色块本身就随主题色走，换色时这里立刻能看出来。
                        .background(MaterialTheme.colorScheme.primary, shape)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "MaterialTheme.shapes.$name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.strongTextColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (cornerDp == Dp.Unspecified) {
                            "运行时不可测（该档不是固定 dp 圆角）"
                        } else {
                            "${cornerDp.value.roundToInt()}dp"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
            }
        }
        Text(
            text = "来源：Theme.kt 的 Shapes（private，跨文件引用不到，因此这里运行时量取）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = INSPECTOR_SOURCE_ALPHA)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 工具
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 把颜色转成 `#RRGGBB`（不透明时）或 `#AARRGGBB`（有透明度时）。
 *
 * ## 为什么逐字节手拼，而不是 `String.format("#%08X", ...)`
 *
 * `String.format` 不指定 Locale 时走系统默认，某些区域会把十六进制数字换成当地数字
 * 字形，同一个颜色在不同语言设置下显示成不同的字符串。这是个给开发者对色号用的读数，
 * 稳定比方便重要。
 *
 * ## 为什么也不用 `Integer.toHexString`
 *
 * 它对负数会输出带减号的 `-1234abcd`（`toArgb()` 的返回值是 `Int`，不透明色的最高位
 * 是 1，必然是负数），还得先转无符号再补位。手拼三行反而是最短且没有平台差异的路径
 * （`HEX_DIGITS` 只含 ASCII 字符）。
 *
 * ## 为什么不总是给 8 位
 *
 * 本项目的主题色与语义色**全部不透明**，多出来的 `FF` 前缀只是噪音，读色号时反而要多
 * 跳两位。真有半透明色（将来）时再补上 AA 分量，此时保留 8 位才不会丢信息。
 */
private fun Color.toHex(): String {
    val argb = toArgb()
    val alpha = (argb ushr 24) and 0xFF
    val red = hexByte((argb shr 16) and 0xFF)
    val green = hexByte((argb shr 8) and 0xFF)
    val blue = hexByte(argb and 0xFF)
    // 不透明时省略 AA 分量（见上方 KDoc）。
    return if (alpha == 0xFF) "#$red$green$blue" else "#${hexByte(alpha)}$red$green$blue"
}

/** 一个字节 → 两位大写十六进制（如 `7` → `"07"`、`255` → `"FF"`）。 */
private fun hexByte(value: Int): String {
    val hi = HEX_DIGITS[(value shr 4) and 0x0F]
    val lo = HEX_DIGITS[value and 0x0F]
    // 用字符串模板而不是 `Char.plus`：后者在这里会先做 Char 的算术提升，
    // 拼出来是数字和而不是字符，模板才得到 "F" + "F" = "FF"。
    return "$hi$lo"
}

/**
 * 把 `Shape` 的左上圆角量成 dp。
 *
 * 只处理 [CornerBasedShape]（`RoundedCornerShape` 的父类，本项目全部形状都是它）。
 * 返回 `Dp.Unspecified` 表示"量不出来"——调用处据此显示「运行时不可测」，**不编造一个数**。
 *
 * ## 为什么传 `Size(1f, 1f)`
 *
 * 接口要求给一个尺寸，但它只对**百分比圆角**有意义（百分比按短边算）。
 * 本项目的五档全是固定 dp 的 `CornerSize`，`toPx` 会直接返回 dp 换算值、完全忽略这个尺寸；
 * 万一本主题将来换成百分比圆角，这里量出来的就不是"某个 dp"，而那个分支本来就该显示
 * 「运行时不可测」——所以这个占位尺寸不会制造错误的读数。
 */
private fun cornerSizeDp(shape: Shape, density: Density): Dp {
    val corner = (shape as? CornerBasedShape)?.topStart ?: return Dp.Unspecified
    return with(density) { corner.toPx(Size(1f, 1f), this).toDp() }
}

/**
 * 十六进制数字表（大写）。
 *
 * 用它手拼而不是 `Char.plus` / `Integer.toHexString`：前者不做数值到字符的映射，
 * 后者对负数会带出减号。一张 16 个字符的表是最短且没有平台差异的路径。
 */
private val HEX_DIGITS = "0123456789ABCDEF"

/**
 * 把缩放系数格式化成两位小数（如 `1.00` / `0.84`）。
 *
 * 位数固定，读数在动画过程中不会因为小数位数变化而左右抖动——这一点很重要：
 * 这一页显示的正是**正在变化的动画值**，用 `toString()` 会输出
 * `0.9353342` 这种十几位的东西，还会随精度变化改变宽度。
 *
 * 用整数运算而不是 `String.format("%.2f")`：后者走系统 Locale，
 * 个别地区的小数点是逗号（与 [Color.toHex] 避开 Locale 是同一个理由）。
 * 缩放系数恒为非负，因此不需要处理负号。
 */
private fun Float.toScaleText(): String {
    val hundredths = (this * 100f).roundToInt()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/**
 * 把毫秒读数格式化成「N.N 秒」（滑块样例旁边的当前值）。
 *
 * ## 为什么与 `DeveloperOptionsScreen.formatHoldSeconds` 各写一份
 *
 * 那个是 `ui.lab` 包**另一个文件**里的 private 函数，跨文件引用不到；
 * 而把两个 private 变成 internal 只为了省这四行，会往本包已经很大的顶层命名空间里
 * 再塞一个名字（本包顶层重名会直接编译失败，见文件顶部 KDoc）。
 * 因此各留一份，规格与那边逐字一致（档距 0.1 秒 → 保留一位小数）。
 *
 * 一律保留一位小数（`3.0 秒` 而不是 `3 秒`）：档位是 0.1 秒，位数固定时数字宽度
 * 不会跳动——`3.0 秒` 与 `10.0 秒` 之间来回拖会让读数左右抖动，很难看。
 *
 * 用整数运算而不是浮点除法 + 格式化：浮点在这里没有任何精度收益，
 * 却要多引入一个 `String.format` 的 Locale 问题（某些区域小数点写成逗号），
 * 这是个显示给用户看的读数，稳定比花哨重要。
 */
private fun Int.toInspectorSecondsText(): String {
    // 先钳到取值域：DataStore 被改坏或将来取值域调整时，读数不会出现负数秒。
    val safe = coerceIn(
        SettingsRepository.MIN_SPLASH_HOLD_MS,
        SettingsRepository.MAX_SPLASH_HOLD_MS
    )
    val seconds = safe / 1000
    val tenths = (safe % 1000) / 100
    return "$seconds.$tenths 秒"
}

/**
 * 把滑块给出的连续值吸附到 [INSPECTOR_SAMPLE_SLIDER_STEP_MS] 的整数倍，并钳进取值域。
 *
 * 与 `DeveloperOptionsScreen.SplashHoldRow` 里的吸附逻辑同一口径：
 * `Slider` 的 `onValueChange` 给的是连续值（如 2371.4），`steps` 只负责把**滑块位置**
 * 吸附到档位，不会把回调的值整除好；不吸附的话读数会出现「2.371 秒」这种噪声。
 *
 * `roundToInt` 而不是 `toInt`：后者是截断，会让"往上拖一点"经常没反应。
 * 缩放系数恒为非负，这里也只需处理非负值（取值域下限是 0）。
 */
private fun inspectorSnapSlider(raw: Float): Int {
    val snapped = (raw / INSPECTOR_SAMPLE_SLIDER_STEP_MS).roundToInt() *
        INSPECTOR_SAMPLE_SLIDER_STEP_MS
    return snapped.coerceIn(
        SettingsRepository.MIN_SPLASH_HOLD_MS,
        SettingsRepository.MAX_SPLASH_HOLD_MS
    )
}

/**
 * 两个分区之间的垂直间距。
 *
 * 取 20dp，与 `DeveloperOptionsScreen.SECTION_SPACING`、
 * `IconPickerScreen.SECTION_SPACING` 同值：全应用"板块之间"是同一种呼吸节奏，
 * 三页放在一起看才不会像两套设计。
 */
private val INSPECTOR_SECTION_SPACING = 20.dp

/** 卡片内的统一内边距。取 16dp，与本项目其它设置卡片的正文内边距一致。 */
private val INSPECTOR_CARD_PADDING = 16.dp

/**
 * 页首两段说明之间的间距。
 *
 * 取 8dp：比 [INSPECTOR_SECTION_SPACING]（20dp）明显小一档——
 * 那两段话是同一块"怎么用这一页"的导言，间距必须小于"导言与第一个分区之间"，
 * 否则会被读成两段互不相干的话。
 */
private val INSPECTOR_INTRO_PARAGRAPH_GAP = 8.dp

/**
 * 正文说明文字的不透明度。
 *
 * 取 0.75，与 `DeveloperOptionsScreen` / `IconPickerScreen` 的正文口径一致；
 * 比"来源"那一行的 [INSPECTOR_SOURCE_ALPHA]（0.6）深一档——
 * 说明文字是"要读的"，来源是"想核对时才看的"。
 */
private const val INSPECTOR_BODY_ALPHA = 0.75f

/**
 * 折叠卡片标题行的上下内边距。
 *
 * 取 14dp，与本项目设置页里开关行 / 动作行的 `vertical = 14.dp` 同值：
 * 标题行现在**本身就是**一行可点区域，用同一档内边距能让它的高度和别处的行一致，
 * 手指点上去的命中面积也一样好按。
 */
private val INSPECTOR_SECTION_HEADER_V_PADDING = 14.dp

/**
 * 标题右侧展开 / 收起箭头的尺寸。
 *
 * 取 20dp：比 Material 图标默认的 24dp（每个 `ImageVector` 自带 24dp 基准）小一档。
 * 箭头是**状态指示器**而不是按钮（可点区域是整行标题），
 * 按 24dp 画会与 `labelLarge` 的标题一样抢眼，把"标题行"读成"按钮行"。
 */
private val INSPECTOR_SECTION_TOGGLE_ICON_SIZE = 20.dp

/** 展开箭头的不透明度。压到 0.62：同上，它是指示器不是按钮，不该与标题同级。 */
private const val INSPECTOR_HEADER_ICON_ALPHA = 0.62f

/**
 * 分区**展开**的动画时长。
 *
 * 取 220ms，与日历翻月（`CalendarScreen` 的 `tween(220)`）同值：
 * 同一个应用里"内容出现"的节奏应当一致。比收起略慢是有意的——
 * 展开是"我要读里面的东西"，稍慢让眼睛跟得上；收起是"扫完了，合上"，快了更自然。
 */
private const val INSPECTOR_SECTION_EXPAND_MS = 220

/**
 * 分区**收起**的动画时长。
 *
 * 取 180ms：比展开短 40ms（理由见 [INSPECTOR_SECTION_EXPAND_MS]），
 * 又与"提示条退出"这类既有动画（`tween(160)` / `tween(180)`）同一量级。
 */
private const val INSPECTOR_SECTION_COLLAPSE_MS = 180

/**
 * 分区展开 / 收起时，内容淡入淡出的时长。
 *
 * 取 140ms，**刻意短于高度变化的时长**：淡入淡出在这里的作用是给高度变化
 * "盖一层柔化"，如果与高度同长，等高度完全展开时字还在慢慢变清楚，读起来发钝。
 * 短一档就能做到"高度展开到位时内容已经清楚"。
 */
private const val INSPECTOR_SECTION_FADE_MS = 140

/**
 * 一条规格（[InspectorSpecRow] / [InspectorSpecTableRow]）的上下内边距。
 *
 * 取 7dp：比设置页的行内边距（14dp）明显小——规格条目**不是可点行**，
 * 不需要手指命中面积；用 14dp 会让四十多条规格把这一页拉得非常长，
 * 反而更难看。7dp 刚好让相邻条目之间有一条清晰的缝。
 */
private val INSPECTOR_SPEC_ROW_V_PADDING = 7.dp

/**
 * 「表格化」规格里，左标题栏与右数值栏之间的水平间距。
 *
 * 取 10dp：两栏之间的这条缝是"表格"这件事唯一的视觉线索，
 * 太窄（<6dp）时两栏会读成一整句被折断的话。
 */
private val INSPECTOR_SPEC_TABLE_GAP = 10.dp

/**
 * 「表格化」规格里左标题栏的权重。
 *
 * 取 0.44：标题是 2~8 个汉字（如「打卡通栏按钮（含外层留白）」），
 * 而右侧取值常带「dp / sp / 百分比」且更长，所以右侧稍宽（0.56，见
 * [INSPECTOR_SPEC_VALUE_WEIGHT]）。两栏都用权重而不是固定 dp：
 * 中文字数与数值长度都会随系统字体缩放变化，固定宽度在最大字体下会把长取值
 * 挤成一列单字。
 */
private const val INSPECTOR_SPEC_LABEL_WEIGHT = 0.44f

/** 「表格化」规格里右数值栏的权重。取 0.56，理由见 [INSPECTOR_SPEC_LABEL_WEIGHT]。 */
private const val INSPECTOR_SPEC_VALUE_WEIGHT = 0.56f

/**
 * 「来源」那一行文字的不透明度。
 *
 * 取 0.6：比页面上其它副标题（0.75）更淡一档——它是引用信息，
 * 不该和"说明文案"抢同一层视觉权重。
 */
private const val INSPECTOR_SOURCE_ALPHA = 0.6f

/**
 * 标题与其下方取值 / 页面名与其下方文件名之间的小间距。
 *
 * 取 3dp：这一层是"同一件事的两行"，必须明显小于两条规格之间的
 * [INSPECTOR_SPEC_ROW_V_PADDING]（7dp），否则读者会把标题和它的取值读成两条独立条目。
 */
private val INSPECTOR_LABEL_VALUE_GAP = 3.dp

/**
 * 「逐页规格」里，一个页面分组与上一个分组之间的间距。
 *
 * 取 16dp：比分组**内部**的行间距（7dp）大一倍以上，
 * 这样竖着滚动时"换页了"这件事一眼能看出来。
 */
private val INSPECTOR_PAGE_GROUP_TOP_GAP = 16.dp

/** 页面名与它下面那行文件名之间的间距。取 4dp，理由同 [INSPECTOR_LABEL_VALUE_GAP]。 */
private val INSPECTOR_PAGE_ROWS_TOP_GAP = 4.dp

/**
 * 「控件样例」里，一个样例块与上一个样例块之间的间距。
 *
 * 取 14dp：每块里有"标题 + 控件 + 说明"三部分，必须比块内间距（6dp）大得多，
 * 否则上一个样例的说明会被读成下一个样例的标题。
 */
private val INSPECTOR_SAMPLE_BLOCK_TOP_GAP = 14.dp

/**
 * 样例标题与控件本体之间的间距。
 *
 * 取 6dp：标题是"给控件命名的"，必须紧贴控件；离远了读者会先看到孤零零一行字，
 * 不确定它是在说上面那个还是下面那个控件。
 */
private val INSPECTOR_SAMPLE_CONTROL_TOP_GAP = 6.dp

/** 控件本体与它下面那行规格说明之间的间距。取 6dp，理由同上方两处。 */
private val INSPECTOR_SAMPLE_CAPTION_GAP = 6.dp

/**
 * 分区开头那段说明文字与它下面第一块内容之间的间距。
 *
 * 取 14dp：比 [INSPECTOR_SAMPLE_CONTROL_TOP_GAP]（6dp）大一档——
 * 说明文字与它要介绍的内容之间要有一道明显的缝，否则第一块内容会被读成
 * 那段说明的下一句话；但又不能取到 [INSPECTOR_SAMPLE_BLOCK_TOP_GAP]（14dp）以上，
 * 那样"说明"和"它管的这块内容"会被拆成两截。
 */
private val INSPECTOR_INTRO_TO_CONTENT_GAP = 14.dp

/** 同一个样例块内部，两行控件之间的间距（如两个开关、两个芯片）。取 10dp。 */
private val INSPECTOR_SAMPLE_ROW_GAP = 10.dp

/** 样例里"控件 + 跟在它右边的文字"之间的间距。取 12dp，与本项目其它处一致。 */
private val INSPECTOR_SWITCH_LABEL_GAP = 12.dp

/** 颜色色块的边长。28dp 足够看清色相，又不会把一行撑得过高（一行还要放名称与 hex）。 */
private val INSPECTOR_SWATCH_SIZE = 28.dp

/** 圆角演示里色块的边长。48dp：圆角最大 14dp，再小就分不清 10dp 与 14dp 的差别。 */
private val INSPECTOR_SHAPE_DEMO_SIZE = 48.dp

/**
 * 演示按钮的尺寸。取 168dp × 56dp：
 * 比计时页的 176dp × 64dp 略小一档——它在卡片里只是个样本，占满整行会把这一页
 * 的阅读节奏打断；但又要明显是"一个真按钮"，所以不能缩到 100dp 以下。
 */
private val INSPECTOR_DEMO_BUTTON_WIDTH = 168.dp
private val INSPECTOR_DEMO_BUTTON_HEIGHT = 56.dp

/**
 * 芯片样例的圆角百分比。取 50（= 胶囊形），与 `TimerPanel.kt` 的 `DurationChip`
 * 里 `RoundedCornerShape(50)` 逐字同值——芯片是胶囊这件事本身就是它最显著的规格。
 *
 * 注意这里传的是**百分比**（`Int`），不是 dp：`RoundedCornerShape(50)` 会让圆角
 * 等于短边的一半，因此芯片高度变化时它始终是胶囊，不需要跟着改数字。
 */
private const val INSPECTOR_CHIP_CORNER_PERCENT = 50

/** 芯片样例的水平内边距。16dp，来源：`TimerPanel.kt` 的 `DurationChip`。 */
private val INSPECTOR_CHIP_H_PADDING = 16.dp

/** 芯片样例的垂直内边距。9dp（不是 8dp），来源：`TimerPanel.kt` 的 `DurationChip`。 */
private val INSPECTOR_CHIP_V_PADDING = 9.dp

/** 芯片样例选中态的描边宽度。1dp，来源：`TimerPanel.kt` 的 `DurationChip`。 */
private val INSPECTOR_CHIP_BORDER_WIDTH = 1.dp

/**
 * 芯片样例的字号。14sp，来源：`TimerPanel.kt` 的 `DurationChip`。
 *
 * 这里不能写成 `const val`：`.sp` 是带单位信息的扩展属性，不是编译期常量。
 */
private val INSPECTOR_CHIP_FONT_SIZE = 14.sp

/** 两个芯片之间的水平间距。10dp：比 `TimerPanel.kt` 里那排的 8dp 稍宽，便于分辨两只。 */
private val INSPECTOR_CHIP_ROW_SPACING = 10.dp

/** 第一个芯片样例的标识（选中态比较用）。用 Int 而不是枚举：本页只有两只芯片。 */
private const val INSPECTOR_CHIP_SAMPLE_DEFAULT = 0

/** 第二个芯片样例的标识。 */
private const val INSPECTOR_CHIP_SAMPLE_ALTERNATE = 1

/**
 * 输入框样例的圆角。
 *
 * 取 20dp，**与 `AgentChatScreen.ChatInputBar` 的 `RoundedCornerShape(20.dp)` 同值**：
 * 那一个是全应用唯一的大圆角输入框，之所以不复用 `MaterialTheme.shapes.*`，
 * 是因为主题里最大的一档（extraLarge = 14dp）也到不了它要的圆润度。
 * 换成主题形状就不再是这个输入框的真实规格了。
 */
private val INSPECTOR_INPUT_CORNER_RADIUS = 20.dp

/** 输入框样例的最大行数。4，来源：`AgentChatScreen.ChatInputBar` 的 `maxLines = 4`。 */
private const val INSPECTOR_INPUT_MAX_LINES = 4

/**
 * 滑块样例的 `steps` 参数（= 档位数 - 2）。
 *
 * ## 为什么写成算式而不是写死 99
 *
 * `steps` 的语义是「在 `valueRange` 两端之间**插入**几个离散点」，因此
 * 总档数 = `steps + 2`。本项目那个真实滑块的档数 =
 * `(MAX - MIN) / STEP + 1`，故 `steps = (MAX - MIN) / STEP - 1`。
 *
 * 三个常量都引用 `SettingsRepository` 的公开常量（[SettingsRepository.MIN_SPLASH_HOLD_MS] /
 * [SettingsRepository.MAX_SPLASH_HOLD_MS] / [SettingsRepository.SPLASH_HOLD_STEP_MS]），
 * 所以以后改档距或上限时这里自动跟着变，不会留下一个与真实滑块对不上的魔法数字。
 * 按当前值算：(10000 - 0) / 100 - 1 = 99。
 *
 * ⚠️ 可以用 `const val`：三个来源都是 `const val Int`，整个算式在编译期就能算完。
 */
private const val INSPECTOR_SAMPLE_SLIDER_STEPS =
    (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS) /
        SettingsRepository.SPLASH_HOLD_STEP_MS - 1

/** 滑块样例的档距（毫秒）。100ms = 0.1 秒一档，引自 `SettingsRepository.SPLASH_HOLD_STEP_MS`。 */
private const val INSPECTOR_SAMPLE_SLIDER_STEP_MS = SettingsRepository.SPLASH_HOLD_STEP_MS

/**
 * 按压缩放的下限。**0.84**，与 `TimerPanel.kt` 的 `TimerActionButton` 同值
 * （那里是从 0.88 加深过来的：64dp 按钮上 0.88 只有约 7.7dp 位移，肉眼偏弱）。
 */
private const val INSPECTOR_PRESS_SCALE = 0.84f

/** 按下时图标额外缩到 0.90，与容器一起"陷下去"。来源同 `TimerActionButton`。 */
private const val INSPECTOR_PRESS_ICON_SCALE = 0.90f

/** 按下时图标的透明度。取 0.82，与容器压暗同向叠加（不是相消）。来源同 `TimerActionButton`。 */
private const val INSPECTOR_PRESS_ICON_ALPHA = 0.82f

/**
 * 按下时容器色向 `scrim` 混合的比例。取 0.22，与 `TimerPanel.kt` 当前值一致
 * （那里注明是从 0.16 加深到 0.22，理由是需求要"较为明显的按下反馈"）。
 */
private const val INSPECTOR_PRESS_CONTAINER_DARKEN = 0.22f

/** 待机投影深度。8dp，来源同 `TimerActionButton`（"抬得高"）。 */
private val INSPECTOR_ELEVATION_IDLE = 8.dp

/** 按下投影深度。2dp，来源同 `TimerActionButton`（"贴下去"）。 */
private val INSPECTOR_ELEVATION_PRESSED = 2.dp

/** 待机时顶部高光的 alpha。0.18，来源同 `TimerActionButton`。 */
private const val INSPECTOR_HIGHLIGHT_IDLE = 0.18f

/** 按下时顶部高光的 alpha。0.10，来源同 `TimerActionButton`。 */
private const val INSPECTOR_HIGHLIGHT_PRESSED = 0.10f
