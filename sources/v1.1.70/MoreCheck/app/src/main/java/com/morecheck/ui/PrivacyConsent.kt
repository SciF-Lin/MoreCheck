package com.morecheck.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import kotlinx.coroutines.launch
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.mine.AGREEMENT_MARKDOWN
import com.morecheck.ui.mine.LegalMarkdownBody
import com.morecheck.ui.mine.PRIVACY_TEXT
import com.morecheck.ui.settings.SettingsViewModel

/**
 * 首次启动 / 撤回同意后的「强制同意协议」门闸。
 *
 * ## 为什么放在 MainActivity 里、包住整个主界面
 *
 * 需求明确「不同意则退出软件」，因此它必须挡在**所有**内容之前：
 * 只要还没同意，就不该露出主界面、启动页之后的任何东西。
 *
 * ## 为什么要一个 `privacyLoaded` 标志
 *
 * [SettingsViewModel.privacyAgreed] 初始为 `false`（DataStore 还没读完），
 * 如果直接拿它做判断，**已经同意过的用户**会在启动瞬间被闪一下同意弹窗。
 * 所以在 DataStore 读出来之前先显示空白、不弹窗，读到真实值再决定。
 *
 * ## 为什么要 `enabled`
 *
 * 需求要求**先放完 Hello World 开屏、再弹同意窗**。开屏期间本门闸先按「放行内容」处理，
 * 等 `SplashGate` 回调后才开始拦截。若不做这层，底部弹窗会在开屏还在显示时就压上来，
 * 盖住居中的 `Hello World`。
 *
 * ## 不可关闭的三重保证
 *
 * 1. `sheetState` 的 `confirmValueChange` 恒返回 false：下滑 / 点遮罩都关不掉；
 * 2. [BackHandler]：按返回键不关；
 * 3. 底部弹窗本身没有「关闭」按钮，只有「同意并继续」与「不同意，退出」两个动作。
 *
 * 注意「查看协议全文」是**例外**：那一层允许返回（返回回到同意弹窗），
 * 否则用户点进去就读不到底、也退不出来。
 *
 * @param enabled 是否开始拦截。开屏未结束时传 false。
 */
@Composable
fun PrivacyConsentGate(
    viewModel: SettingsViewModel,
    onAgreed: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val agreed by viewModel.privacyAgreed.collectAsStateWithLifecycle()
    val loaded by viewModel.privacyLoaded.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 未加载完 → 空屏等待（通常一帧内完成，用户无感知）。
    if (!loaded) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
    }

    if (!agreed) {
        // 开屏还没放完 → 只铺一层背景，**不弹窗、也不渲染主界面**。
        //
        // 需求：「先展示 Hello World 开屏，再弹同意窗口」。
        // 这里刻意不调用 `content()`：开屏阶段就把主界面建出来、开屏结束后又因为未同意而撤掉，
        // 会让 `MoreCheckRoot` 白跑一遍「创建 → 销毁」；而且开屏淡出后会先露出主界面、
        // 再盖上一个同意弹窗，观感是「闪了一下主界面」。
        // 铺背景是为了让开屏淡出时落到干净的页面底色上，不会闪黑。
        if (!enabled) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
            return
        }

        // 强制同意底部弹窗：不可关闭。不同意就结束本 Activity（单 Activity 应用即退出）。
        ConsentBottomSheet(
            onAgree = {
                viewModel.agreePrivacy()
                onAgreed()
            },
            onDisagree = { context.findActivity()?.finish() }
        )
        // 弹窗盖住一切；下面不渲染主界面，避免从缝隙里露出内容。
        return
    }

    content()
}

/**
 * 强制同意：**底部弹窗**（样式参考小米系统弹窗，高度约占屏幕 1/3）。
 *
 * ## 为什么用 `Dialog` 而不是 M3 的 `ModalBottomSheet`
 *
 * 一开始用的是 `ModalBottomSheet`，实机测试踩了两个坑：
 * 1. `confirmValueChange = { false }`（想挡下滑关闭）把**展开**也一起挡掉了，
 *    弹层永远停在 partially-expanded 锚点，按钮下面空出一大块；
 * 2. 改成只拦 `Hidden` + `skipPartiallyExpanded` 后，**仍然**停在半屏锚点上。
 *
 * `ModalBottomSheet` 的高度由它自己的锚点系统决定，和内容高度脱钩。
 * 而这里要的是「高度 = 内容高度、贴底、占屏幕约 1/3」这种**确定性**布局，
 * 所以改用底部对齐的 `Dialog`：窗口全屏 + 内容 `Alignment.BottomCenter`，
 * 高度完全由内容决定，不会再有半天吊的锚点。
 *
 * 不可关闭由 `DialogProperties` 直接保证，比在 sheet 里拦状态更可靠：
 * - `dismissOnBackPress = false`：返回键不关；
 * - `dismissOnClickOutside = false`：点遮罩不关。
 */
@Composable
private fun ConsentBottomSheet(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // 当前正在查看的协议；`docVisible` 单独控制进出场（见 [LegalDocLayer] 的参数说明）。
    // `viewing` 在关闭后**刻意不清空**，让滑出动画期间内容仍在。
    var viewing by remember { mutableStateOf<LegalDoc?>(null) }
    var docVisible by remember { mutableStateOf(false) }

    // ⚠️ 协议阅读层与底部弹窗**共用同一个 Dialog 窗口**（见下方 [LegalDocLayer]），
    // 而不是各开一个 `Dialog`。
    //
    // 踩过的坑（需求：点协议「有残影」）：原先是这样写的——
    //   `viewing?.let { LegalDocDialog(...); return }`
    // 也就是「销毁底部弹窗的窗口 → 新建阅读层的窗口」。两个 Dialog 窗口各自做系统级淡入淡出，
    // **互相不同步**，重叠的那几帧里两层都半透明地糊在一起，看起来就是残影。
    // 现在改成在同一个窗口内部换内容，并给阅读层加「从右滑入」的动画，
    // 既没有跨窗口的时序问题，动画也能自己控制。
    AppBottomSheet(
        title = "用户协议与隐私政策",
        confirmLabel = "同意并继续",
        onConfirm = onAgree,
        onCancel = onDisagree,
        cancelLabel = "不同意，退出",
        onDismissRequest = { /* 点遮罩不关 */ },
        overlay = {
            // 阅读层盖在弹窗之上；关闭时由 AnimatedVisibility 播放滑出动画。
            LegalDocLayer(
                doc = viewing,
                visible = docVisible,
                onBack = { docVisible = false }
            )
        },
        body = {
            // ---- 正文分两层，而不是两段平铺 ----
            //
            // 第一层是**要用户做的事**（阅读并同意哪两份文件），句中两个书名号是**可点链接**；
            // 第二层是**后果告知**（数据怎么处理、不同意会怎样）。
            // 原先两段只隔 10dp、字号也接近，读起来像被硬拆成两行的一句话；
            // 现在把第二层收进一个浅色圆角容器，层次一眼可分：主句 → 附注。
            // 需求 v1.1.66：**单段、单一字号、单一颜色**（去掉「缩进 + 浅底 + 小字」的附注块观感）。
            // 链接改用 [LegalParagraph]（自己算命中区），不用 `LinkAnnotation`——
            // 那一版在「链接落在行尾」时整行点不动，正是需求 bug 的根因。
            LegalParagraph(
                fullText = "使用本应用前，请阅读并同意《用户协议》与《隐私政策》。" +
                    "我们仅在本机存储您的打卡数据，不会上传。不同意将退出应用。",
                linkTexts = listOf("《用户协议》", "《隐私政策》"),
                onLinkClick = { index ->
                    viewing = if (index == 0) LegalDoc.Agreement else LegalDoc.Privacy
                    docVisible = true
                }
            )
        }
    )
}

/**
 * 门闸 / 设置页里可查看的两份文档。
 *
 * 定义在 `ui` 层而不是各自页面内：同意弹窗与撤回弹窗都要用它来区分「当前在看哪一份」，
 * 两个页面各写一个枚举迟早不一致。
 */
internal enum class LegalDoc {
    Agreement,
    Privacy
}

/** 取某份文档的标题与正文（两处阅读层共用，避免各写一份 `when`）。 */
internal fun legalDocContent(doc: LegalDoc): Pair<String, String> = when (doc) {
    LegalDoc.Agreement -> "用户协议" to AGREEMENT_MARKDOWN
    LegalDoc.Privacy -> "隐私政策" to PRIVACY_TEXT
}

/**
 * 全应用共用的**底部弹窗骨架**（小米风格：贴底、圆角、高度受限可滚）。
 *
 * 抽出来是为了让「首次同意」与「撤回同意」两处**长得完全一样**——
 * 需求明确要求后者「同理」，各写一份迟早走样。
 *
 * ## 为什么从 `Dialog` 换回 `ModalBottomSheet`（本轮改动）
 *
 * 这里**曾经**用自绘的 `Dialog`（`usePlatformDefaultWidth = false` + 内容 `BottomCenter`），
 * 理由是当年 `ModalBottomSheet` 的锚点把高度锁在半屏。但那个结论其实站不住：
 * 当时用 `confirmValueChange = { false }` 想挡下滑关闭，而这个回调返回 false 会
 * **拒绝一切状态变更（包括「展开」）**，弹层才永远停在 `PartiallyExpanded`。
 * 正确做法是只拦 `Hidden`（见下面的 `confirmValueChange`）。
 *
 * 换回来还顺带解决了 `Dialog` 方案一个治不好的问题：**弹窗白底盖不住系统导航栏**。
 * 实测 `dumpsys window`：
 * ```
 * Dialog 方案:  mAttrs={(0,0)(1281x2772) ty=APPLICATION surfaceInsets=Rect(96, 96, 96, 96)
 * ```
 * 窗口 frame 是全屏，但绘制表面四周被内缩 96px，最底部那一条没有弹窗表面，
 * 露出被遮罩压暗的下层内容（实测 `#656565` 单色带）。
 * 而 `ModalBottomSheet` 走的 `ModalBottomSheetDialogWrapper` 没有这个内缩，
 * 同应用的「选择要打卡的任务」实测从 y=2620 到屏幕最底是**纯白 `#FFFFFF`**。
 * 试过 4 种在 `Dialog` 上打补丁的办法（改窗口尺寸 / 关对比度保护 / 导航栏透明 / 换窗口背景）
 * 全部无效，根因在窗口实现本身，不在参数。
 *
 * ## 高度
 *
 * `ModalBottomSheet` 的锚点按**内容实测高度**算 `Expanded`，所以给内容一个确定高度即可；
 * 配合 `skipPartiallyExpanded = true`（下面已设），展开后就停在这个高度，
 * 不会出现「滑到一半卡住」。这里用 [SHEET_HEIGHT_FRACTION] 定高，与「打卡选择」弹窗体量一致。
 *
 * ## 关闭行为
 *
 * - 下滑手势**保留**（这是底部弹窗应有的手感）；
 * - 遮罩点击 / 返回键由 [dismissible] 控制：同意弹窗必须不可关，撤回弹窗允许关。
 *
 * @param title 弹窗标题。
 * @param body 标题下的正文内容。
 * @param confirmLabel 主按钮文案。
 * @param cancelLabel 次按钮文案。
 * @param confirmEnabled 主按钮是否可点（撤回弹窗倒计时期间传 false）。
 * @param overlay 盖在整个底部弹窗**之上**的一层（可空）。
 *   用来放「协议全文阅读层」——与弹窗同处一个窗口，所以不会出现两个窗口各自淡入淡出、
 *   叠在一起产生的残影。
 * @param bodyScrollable 正文区是否可上下滚动（默认 true；撤回弹窗按需求传 false）。
 * @param heightFraction 弹窗高度占屏高的比例（默认共用值）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelLabel: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissible: Boolean = false,
    cancelContentColor: Color? = null,
    overlay: (@Composable () -> Unit)? = null,
    /**
     * 正文区是否可上下滚动。
     *
     * 默认 `true`（同意弹窗照旧）。撤回弹窗按需求传 `false`：
     * 用户要求那个弹窗「禁止上下滑动」。
     */
    bodyScrollable: Boolean = true,
    /**
     * 弹窗高度占屏高的比例；默认用两个弹窗共用的 [SHEET_HEIGHT_FRACTION]。
     *
     * 允许个别调用方覆盖：撤回弹窗的正文本轮变长了（需求要求把文案写丰富），
     * 需要的版面比同意弹窗大一点。默认值保证「不传就还是原来那个高度」，
     * 因此同意弹窗完全不受影响（需求：不改同意页）。
     */
    heightFraction: Float = SHEET_HEIGHT_FRACTION,
    body: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    // `confirmValueChange` 是 `SheetState` 的构造参数，**不是** `ModalBottomSheet` 的入参，
    // 所以必须挂在 `rememberModalBottomSheetState` 上。
    //
    // 这里只拦 `Hidden`：**绝不能**写成 `{ false }` —— 那会拒绝一切状态变更（包括「展开」），
    // 弹层永远停在 `PartiallyExpanded` 半屏锚点。
    // 这正是本项目当年误判「ModalBottomSheet 的高度锁在半屏」并在注释里记下错误结论的原因。
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> dismissible || value != SheetValue.Hidden }
    )

    // ── 为什么这里**没有任何**「覆盖层是否可见」的状态（v1.1.69 本轮删净）──────────
    //
    // 需求 bug 1：「返回的一瞬间闪白」（用户录屏第 47→48 帧，整屏 100% 白）。
    //   → 已由「容器底色恒透明」结构性修掉（见 `containerColor`），**不需要任何状态**。
    // 需求 bug 2：「进出二级界面时圆角处显示异常」（v1.1.69）。
    //   → 已由「内容高度恒定（Box 恒 fillMaxSize + shape 恒直角）」结构性修掉：
    //     锚点不再跳变，外层没有任何东西会掀翻布局。见下方 `Box` 处的完整说明。
    // 需求 bug 3：「同意 / 撤回协议弹窗打开与返回时，圆角处突白」（本轮）。
    //   → 由「内层 Column 圆角恒定」结构性修掉，见下方 `sheetShape` 处的完整说明。
    //
    // 三个 bug 的修法都是同一句话：**让坏状态无法表示**。
    // 曾经存在的 `overlayCovering`（粘性状态）+ `LaunchedEffect` + `delay(LEGAL_SLIDE_MS)`
    // 就是「按时序对齐动画边界」那一类修法，本轮的 bug 3 正是它残留的时机窗口，
    // 因此连同 `overlayVisible` 形参一起删除——不留任何需要在两种形态间切换的状态。

    // ⚠️ **返回键必须单独拦**（`dismissible` 管不到它）——这是需求 bug「同意弹窗按返回键后消失、
    // 进不去 app」的根因。
    //
    // `dismissible = false` 只作用于 `confirmValueChange`，也就是**手势 / 下滑**那条路径；
    // 而返回键走的是 `ModalBottomSheetProperties.shouldDismissOnBackPress`——它的默认值是 `true`，
    // 由 `ModalBottomSheetDialog` 里的 `dismissOnBackPress` 直接作用于**平台 Dialog 窗口**，
    // **完全绕过 `confirmValueChange`**。
    //
    // 后果特别隐蔽：弹窗被平台直接关掉 → 但 `onDismissRequest` 是个刻意的空实现
    // （同意弹窗不允许关）→ Compose 侧的状态没变、也就**不会重建弹窗**。
    // 而门闸那边 `agreed` 仍是 false、不渲染主界面，于是整屏变成一块空白页面
    // （实测像素 `#EDEDED`、`mCurrentFocus` 仍在 MainActivity 上），
    // 用户既点不了同意、也退不出去——只能杀进程。
    //
    // 所以这里显式把 `shouldDismissOnBackPress` 设成 `dismissible`：
    // 同意弹窗（`dismissible = false`）返回键不关；
    // 撤回弹窗（`dismissible = true`）保持返回键可关，行为不变。
    val sheetProperties = remember(dismissible) {
        ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible)
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        properties = sheetProperties,
        // ⚠️ 容器底色**永远透明**——这是「点击闪白 / 返回闪白」这一类问题的根治办法。
        //
        // ## 为什么不能让它有时白、有时透明
        //
        // 覆盖层用 `fillMaxSize()`，会把 `Box`（以及容纳它的这个 Sheet）**瞬间**从半高撑到整屏
        // （高度变化没有动画）。只要容器在**任何一帧**里是「不透明 + 整屏高」，
        // 那一帧就会整屏刷成单色 —— 这就是用户看到的闪白。
        //
        // 前两版都在「**按时机**切换容器颜色」上做文章（先按 `overlayVisible`、再按延时后的
        // `overlayOnScreen`），都没修好。原因很实在：`AnimatedVisibility` 的退场结束时刻与我们
        // `delay` 的猜测**不可能严格对齐**（实测返回后约 130ms 时白卡范围是 1492..2624，
        // 静止态是 1520..2768 —— 容器已翻回不透明、而 `Box` 还没缩回半高），
        // 只要错开一帧，那一帧就是整屏白。**靠计时去猜动画边界，本身就是不可靠的设计。**
        //
        // ## 现在的做法：结构性消除，而不是调时机
        //
        // 容器永远透明，弹窗那块**可见的**底色由内层 `Column` 自己画
        // （它永远只有 `heightFraction` 那么高，不存在「整屏高的不透明块」）。
        // 于是无论动画停在哪一帧，露出的都是真实的遮罩/背景，**没有任何时序假设**。
        containerColor = Color.Transparent,
        // 形状**恒为直角**（v1.1.69 起，结构性修法，见下方 Box 处的完整说明）。
        //
        // v1.1.68 及以前这里是二态切换（覆盖层在屏幕上时改直角），先后用过
        // `overlayVisible`、`overlayOnScreen`（delay 猜动画）、`overlayFullScreen`
        // （onSizeChanged 量高度）三种判据，都没修好「进出二级界面圆角显示异常」——
        // 因为**根因不在 shape 的时机**，而在内容高度跳变导致 Sheet 锚点重算
        // （详见下方 `Box` 处的说明）。shape 只是被锚点跳变连带掀翻的表象。
        //
        // 现在内容高度恒定（Box 恒 fillMaxSize）→ 锚点恒为 0 → 没有任何东西会掀翻形状，
        // 于是「什么时候切圆角」这个二态本身就被消除了：容器永远整屏高且永远透明，
        // 用户看到的圆角由内层 Column 自己画（它恒为 heightFraction 高，圆角**也恒定**，
        // 不再有任何判据——见下方 `sheetShape` 处的完整说明）。
        shape = RectangleShape,
        // 去掉顶部那条拖拽把手（需求）。
        //
        // 传 null 即**完全不画**把手那一层，比「画一条同色横线盖住」干净：后者仍会占掉
        // 一段高度，弹窗顶部会多出一截空白，且深浅色切换时要保证盖色恰好等于容器色，很脆。
        //
        // 副作用：用户失去「这里可以下滑」的视觉提示。可以接受——本弹窗的两个按钮都很大、
        // 动作也很明确，不依赖手势发现性；而 `dismissible = true` 的撤回弹窗仍可下滑关闭，
        // 只是没有把手提示而已。
        dragHandle = null
    ) {
        // ⚠️ `overlay`（协议全文阅读层）**必须画在这个 content 里面、而且必须画在下面这个
        // `Box` 里面**。
        //
        // 两个约束各踩过一次坑：
        // 1. 不能放到 `ModalBottomSheet` 之外：弹窗自带独立窗口，放外面会被整个窗口挡住，
        //    表现为「点《用户协议》没反应」。
        // 2. **不能写成 `Box { ... }` 后面的兄弟节点**（v1.1.60 修掉的 bug）：
        //    `ModalBottomSheet` 的 content 槽位是个 `Column`，兄弟节点会被**上下堆叠**而不是叠加。
        //    于是「撤回隐私同意」在上面、协议阅读层在下面；两层高度相加超过一屏，
        //    底部对齐的弹窗被整体顶上去，标题压到状态栏上——用户截图里就是这个样子。
        //
        // 放进 `Box` 之后两者才是真正的叠放关系。`contentAlignment = BottomCenter` 是配套的：
        // 覆盖层用 `fillMaxSize()`，它会把 `Box` 撑到整屏高，此时弹窗自己的内容必须**贴在底部**
        // （保持在原地、被覆盖层挡住），而不是跑到屏幕上方去。
        //
        // ── v1.1.69：Box 恒为 `fillMaxSize()`（结构性修「进出二级界面圆角显示异常」）──
        //
        // 需求 bug：从弹窗里点《用户协议》进阅读层、再返回时，**弹窗圆角处显示异常**
        // （圆角提前消失 / 迟迟不恢复 / 顶部出现缺口 / 底色溢出）。
        //
        // 根因（这一条与闪白完全不同，别再混为一谈）：
        // `ModalBottomSheet` 把 content 包进 `Surface(shape, color)`，而它的锚点是用
        // **draggableAnchors 按内容实测高度**算的（`Expanded at max(0f, fullHeight - sheetHeight)`）。
        // 覆盖层 `fillMaxSize()` 一出现，Box 的内容高度就从「半高卡片」跳到「整屏」→
        // **锚点跳变 + Surface 重新 settle**，shape / 裁剪 / 底色全都在这一帧被重算——
        // 用户看到的「圆角处显示异常」就是这次重算的中间态。
        //
        // 为什么三种 shape 判据都修不好它：`overlayVisible`（点击即真，圆角提前消失）、
        // `overlayOnScreen`（delay 280ms 猜动画，退出时圆角迟迟不恢复）、
        // `overlayFullScreen`（onSizeChanged 量高度，能跟上动画但治不了锚点跳变本身）——
        // 它们全都只在修「shape 什么时候切」这个**表象**，而动帧发生在更底层。
        //
        // 结构性修法：让内容高度**恒定**，锚点就恒为 0，永远不会跳：
        // - 本 `Box` 恒 `fillMaxSize()`（原来只有覆盖层可见时才被撑到整屏）；
        // - 外层 `shape` 恒 `RectangleShape`（Surface 恒整屏高 + 恒透明，见 `containerColor`）；
        // - 用户看到的圆角与底色全部由内层 `Column` 自己画（它恒为 `heightFraction` 高）。
        //
        // 于是「半高留圆角 / 整屏切直角」这个二态从**数据结构上被消除**：
        // 没有任何代码需要在两种形态间切换，自然不存在切错时机。
        // 这与闪白的修法（底色恒透明）是同一个思想：**让坏状态无法表示**，而不是按时序对齐。
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            // 弹窗自身那块（标题 + 正文 + 按钮）**自己画底色与圆角**。
            //
            // 为什么不让外面那层容器画：覆盖层可见时容器必须是透明的（否则撑开瞬间整屏闪一下，
            // 见 `containerColor` 处的说明）；而弹窗内容本身**需要**不透明底色，
            // 否则会直接浮在被遮罩压暗的页面上，像没画完。
            //
            // ── 形状：**恒为顶部 20dp 圆角**（本轮 v1.1.69 的结构性修法）──────────────
            //
            // 需求 bug 3：「同意 / 撤回协议弹窗**打开与返回**协议全文时，圆角处突白」。
            //
            // ## 真因：那个会被看见的"直角状态"根本不存在
            //
            // 覆盖层（[LegalDocLayer]）用 `fillMaxSize()`，铺满后**完全盖住**底部弹窗——
            // 也就是说，在"覆盖层可见"的整个期间，本 Column 的圆角**根本不在屏幕上**，
            // 用户看不到它长什么样。既然如此，「覆盖层可见时把圆角改成直角」这件事
            // 从头到尾**没有任何可观测收益**，它只是为一段不可见的状态调时机。
            //
            // 而切换本身是有代价的：`overlayCovering` 由 `LaunchedEffect` + `delay` 猜动画边界，
            // 进入时立刻切直角、退出时延时恢复。切换的那一瞬间，本 Column 的形状在
            // 「直角」与「圆角」之间跳变，而圆角之外的那两块矩形区域是**透明的**——
            // 透出的正是 `Dialog` 窗口底色（弹窗遮罩之下的那一层）。
            // 于是切换瞬间就会在顶部两角闪出两块白，也就是用户看到的「圆角处突白」。
            // 它出现在打开与返回两条路径上，因为两条路径都必然经过这个切换。
            //
            // 这和前面两个 bug 是同一类错误：**为一个看不见的状态维护一个会跳变的二态**。
            // 修法同样不是把时机调得更准（调不准，`AnimatedVisibility` 的退场结束时刻与
            // `delay` 的猜测不可能严格对齐），而是**让坏状态无法表示**：
            // 圆角恒定 → 没有任何切换 → 没有任何时机可错。
            //
            // ## 恒圆角不会带来"顶部漏缺口"吗
            //
            // 不会，而且这恰恰是真因推导的一部分：覆盖层铺满时圆角被盖住、看不见；
            // 覆盖层不可见时弹窗本来就以圆角示人。所以「恒定圆角」在两种情形下都是对的，
            // 反过来「按可见性切直角」在两种情形下的收益都是零。
            val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .background(color = cs.surfaceContainerLow, shape = sheetShape)
                    // 固定高度：锚点按内容高度算 Expanded，给了确定高度就停在这里。
                    .height(LocalConfiguration.current.screenHeightDp.dp * heightFraction)
                    .padding(horizontal = 24.dp)
                    // 顶部让出一截（需求：标题「往下移一点」）。
                    //
                    // 去掉拖拽把手之后，标题就成了弹窗**最上面**的内容，直接贴着 20dp 的
                    // 圆角顶边，视觉上「顶到了框上」——圆角在标题两侧切掉的那两块尤其明显。
                    // 给一截顶部内边距，标题落进安全区，圆角与文字之间才有呼吸。
                    //
                    // 取 16dp：够看出下移（实机约 48px @3x），又不会把弹窗顶部撑出一块空台；
                    // 与下面的 24dp 底边距一起，让标题与按钮各自离边框有一档合理的距离。
                    .padding(top = SHEET_TOP_INSET)
                    // 底部留出系统导航栏高度。
                    //
                    // ⚠️ 这里与 `Dialog` 方案不同：`ModalBottomSheet` 的容器**默认已经**
                    // 把导航栏内边距算进了自己的布局（`BottomSheetDefaults.windowInsets`），
                    // 所以这里**不能**再叠加一次 `navigationBars` 内边距，
                    // 否则按钮会被抬高整整一个导航栏的高度、下面空出一大块。
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface
                )
                Spacer(Modifier.height(20.dp))
                // ⚠️ 正文区为什么是一个「内容竖直居中」的 Box，而不是让 Column 顶在上面（旧写法）
                //
                // 正文区必须吃掉中段剩余空间，按钮才会被推到底部（否则空白全留在按钮下方，
                // 看着像布局坏了）——这一点新旧写法一致。差别在于**正文短的时候怎么分这块空间**：
                //
                // 弹窗按需求长高 20% 之后，本弹窗的正文只有三行，顶对齐会在正文与按钮之间
                // 留下一大片空白，整块看起来「没画完」。改成居中后，多余留白被均分到正文上下，
                // 构图对称、像是刻意留的气口；标题仍固定在最上面，视觉层级不受影响。
                //
                // `verticalScroll` 留在**内层**：正文超长（小屏 / 大字体 / 撤回弹窗那种两段长文）时
                // 它照旧可滚动，滚动条不会把按钮挤出屏幕；内容比视口短时 Column 收缩成内容高，
                // 于是 Box 的居中才真正生效。
                //
                // `bodyScrollable = false`（撤回弹窗）时**完全不挂** `verticalScroll`：
                // 需求要求那个弹窗「禁止上下滑动」。注意不能只把滚动范围设成 0——那样手势仍被
                // 这个修饰符消费，弹窗自己的「下滑关闭」手势会被吃掉一半，反而更怪。
                // 不挂修饰符，纵向手势才会原样交给 `ModalBottomSheet` 的 `nestedScroll`。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(weight = 1f, fill = true),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (bodyScrollable) {
                                    Modifier.verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        body()
                    }
                }
                // 按钮区与正文之间留 24dp（原 20dp）：弹窗变高后按钮离正文更远，
                // 多给一点间距，按钮才读作「独立的操作区」而不是正文的最后一行。
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── 点按钮先播「向下滑出」动画，动画走完再执行动作（v1.1.69 需求 6）──
                    //
                    // ## 为什么必须这样接（这是"弹窗消失时没有下滑动画"的根因）
                    //
                    // `ModalBottomSheet` 自身**有**向下滑出的退场动画，但它只在
                    // **sheet 仍参与组合**时才会播。而原来的写法是按钮直接调
                    // `onCancel` / `onConfirm`，调用方立刻把"是否显示弹窗"的状态置 false
                    // → 整个弹窗当帧就被移出组合 → hide 动画**一帧都没播**
                    // → 用户看到的就是"点一下弹窗瞬间消失"。
                    //
                    // 正确顺序：先 `sheetState.hide()`（让 sheet 自己滑下去，**挂起直到动画结束**），
                    // 再调业务回调（此时调用方再把状态置 false 就只是"卸载已经看不见的弹窗"，观感无变化）。
                    //
                    // ## 为什么用 `rememberCoroutineScope` 而不是让回调变成 suspend
                    //
                    // `onCancel` / `onConfirm` 是调用方传进来的普通 lambda（`() -> Unit`），
                    // 它们的语义是"用户做了这个决定"，不该被迫改成挂起函数——
                    // 这会把"等动画"这件事泄漏到所有调用点。所以在这里用局部 scope 起协程，
                    // 对调用方保持原签名不变。
                    //
                    // ⚠️ 必须 `runCatching` 兜住 `hide()`：个别 ROM 在 sheet 已被系统回收时
                    // 抛异常，那种情况不能让"确认按钮"变成点了没反应——失败也要执行业务动作。
                    val scope = rememberCoroutineScope()
                    val runAfterHide: (() -> Unit) -> Unit = { action ->
                        scope.launch {
                            runCatching { sheetState.hide() }
                            action()
                        }
                    }
                    // 两个按钮统一高度：默认 Material 按钮在「大字体」下会各自变高、
                    // 一高一低很难看，所以显式定高并让文字垂直居中。
                    OutlinedButton(
                        onClick = { runAfterHide(onCancel) },
                        modifier = Modifier
                            .weight(1f)
                            .height(BUTTON_HEIGHT),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = cancelContentColor ?: cs.onSurfaceVariant
                        )
                    ) {
                        // 按钮文字适当增大（需求）：`titleSmall` 是 14sp，在 52dp 高的按钮里偏小，
                        // 显式写 16sp 与「打卡」「注册」等通栏主按钮同一档，全应用按钮字号才统一。
                        Text(cancelLabel, fontSize = 16.sp)
                    }
                    Button(
                        onClick = { runAfterHide(onConfirm) },
                        enabled = confirmEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(BUTTON_HEIGHT),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cs.primary,
                            contentColor = cs.onPrimary
                        )
                    ) {
                        // 与左侧次按钮同一档字号，两个按钮的文字视觉重量才一致。
                        Text(confirmLabel, fontSize = 16.sp)
                    }
                }
            }

            // 覆盖层画在 `Box` 内、主内容之后 —— 这样才是**叠在弹窗之上**，而不是与它上下排布。
            // 详见上面那段关于「兄弟节点会被 Column 上下堆叠」的注释。
            overlay?.invoke()
        }
    }
}
/**
 * 弹窗高度占屏高的比例。
 *
 * `ModalBottomSheet` 会在内容之外**再加**两段高度 —— 顶部拖拽把手与
 * 底部系统导航栏内边距（`BottomSheetDefaults.windowInsets`），实测约 121px。
 * 所以「内容高度」要比「目标整窗高度」小一截：给 0.42 时整窗是 47.2% 屏高。
 *
 * 取 **0.41**：在上一版 **0.34** 的基础上**增高约 20%**（用户要求「用户协议弹窗增高 20%」），
 * 整窗约 45% 屏高。
 *
 * ## 为什么这次是「增高」而不是继续压
 *
 * 0.34 是为了对齐参照图而一路压下来的（0.42 → 0.38 → 0.34，用户当时要求「高度降低 10%」）。
 * 压到 0.34 的代价在 1280×2772 上实测是：正文只有三行，与按钮之间留出一大片空白，
 * 整块看起来像没画完。这次增高后配合 [AppBottomSheet] 里「正文竖直居中」的改法，
 * 多出来的高度被均分到正文上下，才是舒展的。
 *
 * ## 两个弹窗共用本比例
 *
 * 首次同意弹窗与撤回同意弹窗**依然一样高**（这是刻意的：需求要求后者「同理」，
 * 两者共用 [AppBottomSheet] 骨架，各写一份高度迟早走样）。因此本比例一改，两个弹窗一起变。
 */
private const val SHEET_HEIGHT_FRACTION = 0.41f

/**
 * 弹窗内容距顶边的内边距（需求：标题「往下移一点」）。
 *
 * 取 **24dp**：第一版给的是 16dp，用户实机看后仍觉得标题「偏高」，本轮再加 8dp。
 * 之所以需要它，是因为拖拽把手已按需求去掉——没有把手之后标题就成了最上面的内容，
 * 直接贴着 20dp 的圆角顶边，圆角在标题两侧切掉的那两块会显得「字顶到框上」。
 *
 * 与底部的 24dp 对称：标题离顶 24dp、按钮离底 24dp，上下两头的气口一致。
 */
private val SHEET_TOP_INSET = 24.dp

/**
 * 弹窗里主/次按钮的高度。
 *
 * 取 52dp：Material 默认按钮高 40dp，在两个按钮并排、且中文按钮文案较长的场景下偏矮，
 * 点击热区也偏小；52dp 接近系统对话框按钮的手感，又不会高到让弹窗显得笨重。
 */
private val BUTTON_HEIGHT = 52.dp

/**
 * 构造含可点链接的协议句子。
 *
 * 与注册页的 `ConsentRow` 同构：用 `buildAnnotatedString` 而不是并排多个 `Text`，
 * 整句能自然换行，窄屏上不会把《隐私政策》挤出屏幕。
 *
 * **两处弹窗共用本函数**（首次同意 / 撤回同意），保证「可点击」这件事不会只做一半——
 * 实测就是撤回弹窗漏了这层，用户点了没反应（见需求 bug2）。
 *
 * @param prefix 链接之前的前缀文字。
 * @param onOpenAgreement 点《用户协议》。
 * @param onOpenPrivacy 点《隐私政策》。
 * @param suffix 链接之后的收尾文字。
 *   v1.1.66 新增：两个弹窗按需求改成「无附注块、全段一个样式」后，
 *   原本单独成块的那段说明要**接在同一段末尾**，因此需要一个收尾参数。
 *   默认空串，不影响既有调用（注册页那处继续只传 prefix）。
 */
@Composable
internal fun legalLinkSentence(
    prefix: String,
    onOpenAgreement: () -> Unit,
    onOpenPrivacy: () -> Unit,
    suffix: String = ""
): AnnotatedString {
    // 链接必须**看起来能点**：给主题色 + 下划线。
    // 只加下划线不加色时，链接与正文同色，用户根本看不出哪几个字可点（实机确认过）。
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline
        )
    )
    return buildAnnotatedString {
        append(prefix)
        withLink(
            LinkAnnotation.Clickable(
                tag = "agreement",
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { onOpenAgreement() }
            )
        ) {
            append("《用户协议》")
        }
        append("与")
        withLink(
            LinkAnnotation.Clickable(
                tag = "privacy",
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { onOpenPrivacy() }
            )
        ) {
            append("《隐私政策》")
        }
        append("。")
        // 收尾说明接在同一段里（需求：全段一个样式、不要附注块的样子）。
        if (suffix.isNotEmpty()) append(suffix)
    }
}

/**
 * 协议全文阅读层 —— **画在底部弹窗的同一个 Dialog 窗口内**，从右滑入、向下滑出。
 *
 * ## 为什么不再单独开一个 `Dialog`
 *
 * 需求：点《用户协议》时「有残影」。
 * 根因是原先的实现「销毁弹窗窗口 → 新建阅读层窗口」，两个 `Dialog` 各有自己的
 * 系统级淡入淡出，**时序不同步**：重叠的那几帧两层都半透明，糊在一起就是残影。
 *
 * 现在改为同一个窗口内换内容 + 自己控制滑入滑出动画，从根上没有跨窗口的时序问题。
 *
 * ## 动画方向
 *
 * **进入从右滑入、退出向下滑出**：
 * - 进入沿用主界面二级页推入的手感——「从右滑入」（需求明确要求「打开动画改为从右侧滑入」）；
 * - 退出改为**向下滑出**（本轮需求）。理由是不再让阅读层"原路退回"：
 *   它盖在底部弹窗之上，向下退场在语义上就是"把这一层收回到下面的弹窗里"，
 *   与底部弹窗自身的形态（贴底、可下滑关闭）一致；而向右滑出是"页面级"的退场语言，
 *   用在一个覆盖层上会让"退回去"和"推出下一页"两种含义混在一起。
 *
 * 两段时长都用 [LEGAL_SLIDE_MS]，和 `TabNavHost` 的 `NAV_DURATION_MS` 同量级，
 * 全应用页面切换节奏统一。
 *
 * ## 参数为什么分成 `doc` + `visible` 两个
 *
 * `visible` 翻成 false 后 `AnimatedVisibility` 会播滑出动画，这期间**内容仍在组合中**。
 * 如果把「显示哪一份」也绑在可见性上（`doc = if (visible) viewing else null`），
 * 滑出动画播到一半内容就变 null 了，画面会先闪成空白。
 * 因此这里让 `doc` 由调用方保持**粘性**（关了也不清空），只用 `visible` 控制进出场。
 *
 * @param doc 要显示的协议；关闭后仍应保持上一次的值，见上文。
 * @param visible 是否显示（false 时播放滑出动画）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegalDocLayer(
    doc: LegalDoc?,
    visible: Boolean,
    onBack: () -> Unit
) {
    AnimatedVisibility(
        visible = visible && doc != null,
        enter = slideInHorizontally(
            animationSpec = tween(LEGAL_SLIDE_MS),
            initialOffsetX = { fullWidth -> fullWidth }
        ),
        exit = slideOutVertically(
            animationSpec = tween(LEGAL_SLIDE_MS),
            targetOffsetY = { fullHeight -> fullHeight }
        )
    ) {
        val shown = doc ?: return@AnimatedVisibility
        val (docTitle, docBody) = legalDocContent(shown)

        // 阅读层允许返回键退出（回到同意弹窗），这是刻意留的出口。
        BackHandler(enabled = true) { onBack() }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                // 吞掉落在空白处的点击。
                //
                // 阅读层是整屏不透明覆盖层，但 Compose 的 `Surface` **默认不消费点击**，
                // 而它正下方就是底部弹窗的按钮区：不拦的话，用户在协议页底部随便一点，
                // 点击会穿透过去命中「同意并继续」——那等于读着协议就被同意了。
                // 这里挂一个空 `clickable`（`indication = null`，无涟漪）把空白区的点击吃掉；
                // 子节点（返回按钮、内层滚动）仍按正常优先级先处理自己的手势。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            color = MaterialTheme.colorScheme.background
        ) {
            // ⚠️ 阅读层**占满整屏**（见上方 `overlay` 的说明），而它所在窗口是边到边的
            // （弹窗窗口 `decorFitsSystemWindows = false`，只给底部导航栏留了内边距）。
            // 不自己让出状态栏高度，TopAppBar 里的「用户协议」标题与返回箭头就会和
            // 状态栏的时间 / 电量叠在一起——这正是用户截图里那种「顶部样式错乱」。
            // 底部不用再加 `navigationBarsPadding()`：弹窗容器已经把导航栏内边距算进去了
            // （见 [AppBottomSheet] 里那段注释），再加一次会把正文顶高一整条导航栏。
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                TopAppBar(
                    title = { Text(docTitle) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Card(Modifier.fillMaxWidth()) {
                        LegalMarkdownBody(
                            markdown = docBody,
                            modifier = Modifier.padding(18.dp)
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * 带可点链接的**单一样式段落**（v1.1.66）。
 *
 * ## 为什么不能用 [legalLinkSentence]（`LinkAnnotation`）来做这一处
 *
 * 需求 bug：「撤回隐私同意界面用户协议无法点击」。实机逐点扫描确认：
 * 同一段里 **《隐私政策》点得动、《用户协议》整行都点不动**；
 * 而同意弹窗里同样的两个链接**都点得动**。
 *
 * 唯一差别是**断行位置**：撤回弹窗里《用户协议》正好落在第 1 行**行尾**
 * （后面跟着换行的「与《隐私政策》」），同意弹窗里两个链接都在同一行中段。
 * `LinkAnnotation` 的命中区是按行盒算的，链接贴在行尾时算出来的矩形与实际字形错位，
 * 于是点上去落不到那个 annotation 上——这不是坐标没对准（我把整行 x 扫遍了都是死区）。
 *
 * ## 这里怎么绕开
 *
 * 不依赖 `LinkAnnotation`：整段用**一个 `Text`**（所以视觉上仍是单一样式、自然换行、
 * 链接只靠主题色 + 下划线区分，与同意弹窗完全一致），但链接**不做成 annotation**，
 * 只加 `SpanStyle`；命中判断由本组件的 `pointerInput` 自己算：
 * 点击位置 → `TextLayoutResult.getOffsetForPosition()` → 该字符落在哪个链接区间。
 * 这是「点 → 字符」最直接的一条路径，不受行盒矩形的影响。
 *
 * ## 为什么不用「拆成一排 Text + clickable」
 *
 * 那样就无法自然换行了：句子会退化成若干块，窄屏上排版会散。
 * 需求同时要求「文字都是一个样式」，保持**单段自然换行**才满足这一条。
 *
 * @param fullText 整段文字（含链接文字本身）。
 * @param linkTexts 段落里要变成链接的**子串**，顺序与 [onLinkClick] 的下标一致。
 * @param onLinkClick 点了第几个链接（下标对应 [linkTexts]）。
 */
@Composable
internal fun LegalParagraph(
    fullText: String,
    linkTexts: List<String>,
    onLinkClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    lineHeight: TextUnit = 24.sp,
    bodyColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val linkColor = MaterialTheme.colorScheme.primary
    // 在整段里定位每个链接子串的起始下标。用 indexOf 递增搜索而不是硬编码偏移：
    // 文案改一个字，偏移就全错了，而按子串找是自适应的。
    val starts = remember(fullText, linkTexts) {
        var from = 0
        linkTexts.map { link ->
            val at = fullText.indexOf(link, from)
            if (at >= 0) from = at + link.length
            at
        }
    }
    val annotated = remember(fullText, starts, linkColor) {
        buildAnnotatedString {
            append(fullText)
            starts.forEachIndexed { index, start ->
                if (start >= 0) {
                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = start,
                        end = start + linkTexts[index].length
                    )
                }
            }
        }
    }
    // 布局结果是命中判断的依据；`onTextLayout` 每次布局都会回调，存下来即可。
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotated,
        style = style,
        lineHeight = lineHeight,
        color = bodyColor,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(fullText) {
            detectTapGestures { position ->
                val result = layout ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                starts.forEachIndexed { index, start ->
                    if (start >= 0 && offset in start until (start + linkTexts[index].length)) {
                        onLinkClick(index)
                    }
                }
            }
        }
    )
}

/** 协议阅读层的滑入 / 滑出时长。与 `TabNavHost` 的二级页推入同量级。 */
private const val LEGAL_SLIDE_MS = 280

/**
 * 底部弹窗「自下而上」出现的时长。
 *
 * 取 **300ms**：需求要求「速率中等」。参考量级——页面推入是 280ms、开屏淡化是 320ms，
 * 300ms 与它们同档，整个应用的动效节奏不会出现一个明显更快或更慢的异类。
 */
private const val SHEET_ENTER_MS = 300

/**
 * 同意协议后引导登录的可关闭弹窗。
 *
 * 由 [MoreCheckRoot] 调用；「去登录」切到「我的」入口并推入登录页，
 * 「暂不」直接关闭，之后不再打扰。
 */
@Composable
fun LoginPromptDialog(
    onLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("欢迎使用 MoreCheck") },
        text = {
            Text(
                "登录后可使用完整功能。",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onLogin) {
                Text("去登录")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("暂不")
            }
        }
    )
}

/** 从 Compose 的 `LocalContext` 取宿主 [Activity]；取不到返回 null。 */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
