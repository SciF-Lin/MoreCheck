package com.morecheck.ui.auth

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MoreCheckApp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 登录 / 注册两页共用的配色。
 *
 * 全部由当前主题**推导**而来，不写死色值：
 * - [page] / [card] / [tinted] 跟随「深色模式」开关（浅色模式下是浅灰白，深色模式下是深灰）；
 * - [accent] 系列跟随「设置 → 主题色」；
 * 因此换主题色或切深色模式时，这两页会与其余页面一起变。
 */
@Immutable
data class AuthColors(
    /** 页面底色（登录页整页 / 注册页卡片）。 */
    val page: Color,
    /** 注册页卡片之外露出的那一层：底色上薄薄压一层主题色（对应参考图里的"绿调顶栏"）。 */
    val tinted: Color,
    /** 注册页卡片底，与 [page] 区分开，读起来才像一张卡。 */
    val card: Color,
    /** 次级按钮底（登录页的「注册」）。 */
    val secondary: Color,
    /** 分隔线 / 描边。 */
    val hairline: Color,
    /** 输入框下划线（未聚焦时）。 */
    val fieldLine: Color,
    /** 页面主文字。 */
    val onPage: Color,
    /** 占位符 / 次要说明文字。 */
    val muted: Color,
    /** 强调色 = 主题色。 */
    val accent: Color,
    val onAccent: Color,
    /** 主按钮的不可用态（用主题色叠透明度，深浅两种模式都成立）。 */
    val accentDisabled: Color,
    val accentDisabledText: Color,
    /** 错误态：输入框下划线 / 图标 / 提示文字统一用的红。 */
    val errorColor: Color,
    /** 当前是否深色。 */
    val dark: Boolean
)

/**
 * 错误态固定用的红。
 *
 * 用户要求「文本框与提示固定用红色」：不用 `colorScheme.error` 是因为它随主题变化，
 * 而这里需要的是「无论选什么主题色、深浅色，错误就是这一个红」。
 */
private val ErrorRed = Color(0xFFE53935)

/**
 * 「发送验证码」那一格的固定最小宽度。
 *
 * 这格里会出现三种形态：转圈（16dp）、「发送验证码」（5 字）、「57s 后重发」（更宽）。
 * 不固定宽度时格子随内容伸缩，左侧验证码输入框是 weight(1f)，分界线就会左右移动。
 * 92dp 是按「57s 后重发」的实际字宽留出余量取的，三种形态都放得下。
 */
val SendCodeSlotWidth = 92.dp

/**
 * 下划线输入框「输入行」的固定高度。
 *
 * 取 52dp：比 17sp 文字的自然行高（约 24dp）高出不少，留出足够的上下呼吸空间，
 * 让三行输入框之间的视觉间距一致；同时不至于高到把表单撑得很散
 * （注册页现在有手机号 / 验证码 / 昵称 / 密码四行）。
 */
val FieldRowHeight = 52.dp

@Composable
fun rememberAuthColors(): AuthColors {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    val accent = cs.primary
    return AuthColors(
        page = cs.background,
        tinted = lerp(cs.background, accent, if (dark) 0.22f else 0.18f),
        card = cs.surfaceContainerLowest,
        secondary = cs.surfaceContainerHigh,
        hairline = cs.outlineVariant,
        fieldLine = cs.outline,
        onPage = cs.onBackground,
        muted = cs.onSurfaceVariant,
        accent = accent,
        onAccent = cs.onPrimary,
        // 不可用态：淡色主题底 + 主题色文字（**回退到 1.1.54 之前的做法**）。
        //
        // 期间试过「压暗成灰绿 + 白字」，用户对比后要求改回来：还是淡底 + 主题色字顺眼。
        // 深浅色模式各取不同的透明度，保证淡底在两种背景下都成立。
        accentDisabled = accent.copy(alpha = if (dark) 0.16f else 0.20f),
        accentDisabledText = accent.copy(alpha = 0.55f),
        // 错误态的红色：**固定取值**，不随主题色 / 深浅色变。
        //
        // 用户明确要求「文本框与提示固定用红色」——如果取 `colorScheme.error`，
        // 换主题色时它会跟着变（比如红色主题下和正常色混淆）。
        // 这两个值是 Material 错误红的常用取值，深底上也能看清。
        errorColor = ErrorRed,
        dark = dark
    )
}

/**
 * 统一的"按下缩一下 + 触觉反馈"，两页所有可点元素共用。
 *
 * [pressedScale] 默认 0.96：按钮尺寸较大，缩太多会显得抖；圆形小图标传 0.92。
 */
@Composable
fun Modifier.authPressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: Boolean = true,
    /**
     * 是否用带回弹的弹簧。
     *
     * 圆形图标按钮（微信 / QQ）传 false：回弹会让尺寸在按下期间**越过目标值再弹回来**，
     * 圆形边缘在中间帧上容易被看出棱角。改成无过冲的平滑过渡后，缩放全程都只是
     * 对同一张圆做等比变换，边缘始终干净。
     */
    bouncy: Boolean = true
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = if (bouncy) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            // DampingRatioNoBouncy = 1.0（临界阻尼）：最快到达且不过冲。
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "authPress"
    )
    val hapticFeedback = LocalHapticFeedback.current
    return this
        // clip 必须由调用方放在这一层**之后**（即缩放层内层），
        // 否则圆角会在缩放中间帧上退化成多边形，见 `AuthSocialButton` 的注释。
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled
        ) {
            if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

/**
 * 下划线式输入框（参考图里没有容器框，只有一条底线）。
 *
 * 聚焦时底线变粗并换成主题色 —— 这是这套设计里唯一的"输入态"提示。
 *
 * ## 输入行固定高度（`FieldRowHeight`）
 *
 * 原先这一行的高度完全由内容撑开，于是同一页里三种输入框会**微妙地不等高**：
 * 「验证码」那一行外面套了一层 `Row`（右侧还有「发送验证码」），
 * 它的基线/行高与另外两行对不齐，视觉上就是「手机号与验证码的间距偏窄」。
 * 现在给输入行固定一个高度，三种输入框的**文字基线、下划线位置、行间距**全部一致。
 */
@Composable
fun AuthUnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    colors: AuthColors,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
    /** 错误态：下划线 / 图标变红，且聚焦时也是红（正常聚焦色是主题色）。 */
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // 错误态下这条线永远是红的、且比正常态粗一点，让「哪里错了」一眼可见；
    // 非错误态保持原样（聚焦主题色粗线 / 平时灰细线）。
    val lineColor = when {
        isError -> colors.errorColor
        focused -> colors.accent
        else -> colors.fieldLine
    }
    val lineThickness = when {
        isError -> 1.5.dp
        focused -> 1.5.dp
        else -> 1.dp
    }

    Column(modifier) {
        Row(
            modifier = Modifier.height(FieldRowHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = when {
                    isError -> colors.errorColor
                    focused -> colors.accent
                    else -> colors.muted
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(color = colors.onPage, fontSize = 17.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = colors.muted,
                                fontSize = 17.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(
            thickness = lineThickness,
            color = lineColor
        )
        if (supporting != null) {
            Spacer(Modifier.height(10.dp))
            supporting()
        }
    }
}

/**
 * 主按钮：主题色实底 + 主题色上的前景色；不可用时降为淡色底 + 主题色文字。
 *
 * [loading] 为 true 时按钮**同时**置为不可点并显示一个小转圈（注册页用）。
 * 转圈用 [colors.accentDisabledText] 上色：加载态下按钮底色已经是 `accentDisabled`，
 * 用主题色会让圈在淡底上不清晰。
 */
@Composable
fun AuthPrimaryButton(
    text: String,
    colors: AuthColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    // 加载中一律不可点：避免连点触发多次注册。
    val interactive = enabled && !loading
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (interactive) colors.accent else colors.accentDisabled)
            .authPressable(onClick = onClick, enabled = interactive),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.accentDisabledText,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(
                text = text,
                color = if (interactive) colors.onAccent else colors.accentDisabledText,
                fontSize = 16.sp
            )
        }
    }
}

/** 次级按钮：安静的实底 + 一条描边，用来和主按钮拉开层级（参考图的「注册」）。 */
@Composable
fun AuthSecondaryButton(
    text: String,
    colors: AuthColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.secondary)
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .authPressable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = colors.onPage, fontSize = 16.sp)
    }
}

/** 下划线文字链接（「忘记密码?」「发送验证码」「《用户协议》」用的都是这一套）。 */
@Composable
fun AuthTextLink(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    enabled: Boolean = true,
    underline: Boolean = false
) {
    Text(
        text = text,
        color = if (enabled) color else color.copy(alpha = 0.45f),
        fontSize = fontSize,
        textDecoration = if (underline) TextDecoration.Underline else null,
        modifier = modifier
            .authPressable(onClick = onClick, enabled = enabled, haptic = false)
            .padding(vertical = 4.dp)
    )
}

/** 屏幕中央的半透明提示框，1.6 秒后自动消失（与扫一扫页保持同一套反馈语言）。 */
@Composable
fun AuthToast(
    message: String?,
    colors: AuthColors,
    modifier: Modifier = Modifier
) {
    var lastMessage by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (!message.isNullOrEmpty()) lastMessage = message
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, animationSpec = tween(160)),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.onPage.copy(alpha = 0.88f))
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(
                text = lastMessage,
                color = colors.page,
                fontSize = 14.sp
            )
        }
    }
}

/** 第三方登录的品牌色（固定值，不随主题色变化——它们代表的是别家的品牌）。 */
object SocialBrand {
    /** QQ 的蓝色圆底；企鹅本身用透明底贴图叠在上面。 */
    val Qq = Color(0xFF5E9CC3)
}

/**
 * 第三方登录的圆形入口：56dp 圆形容器 + 下方文字，按下缩到 0.92。
 *
 * 圆内**完全交给调用方**画（[circle] 槽位），因为两个品牌的素材形态不同：
 * 微信给的是一张绿底圆角方块图标，裁圆即可；QQ 给的是透明底企鹅，需要自己铺一层蓝底再叠上去。
 * 与其在这里堆品牌特例，不如把「容器」和「内容」分开。
 */
@Composable
fun AuthSocialButton(
    label: String,
    colors: AuthColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            // ⚠️ 修饰符顺序很关键：`authPressable`（内含 graphicsLayer 缩放）必须放在
            // `clip(CircleShape)` **之前**，也就是让 clip 成为缩放层**内层**。
            //
            // 原先写成 `size → clip → graphicsLayer`：裁剪在缩放之外，每帧都要在**已缩放**的
            // 坐标系里重新生成一次圆形裁剪路径，缩放中间值上圆的边缘会退化成多边形
            // （实测长按截图里就是一个八边形/圆角方块轮廓）。
            // 调换后圆形在 56dp 的层内一次光栅化完成，缩放只是对这一层做变换，边缘始终是平滑的圆。
            modifier = Modifier
                .size(56.dp)
                .authPressable(onClick = onClick, pressedScale = 0.92f, bouncy = false)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
            content = circle
        )
        Spacer(Modifier.height(8.dp))
        Text(text = label, color = colors.muted, fontSize = 12.sp)
    }
}

/** 提示行：圆角浅底 + 主题色图标 + 一行说明（注册页「建议使用邮箱注册…」）。 */
@Composable
fun AuthNoteRow(
    text: String,
    icon: ImageVector,
    colors: AuthColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.tinted)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            color = colors.onPage,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

/** 顶部返回按钮：透明底 + 图标，按下缩到 0.92。 */
@Composable
fun AuthBackButton(
    onClick: () -> Unit,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .authPressable(onClick = onClick, pressedScale = 0.92f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "返回",
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
    }
}

/** 倒计时：到 0 自动停。用于「发送验证码」。 */
@Composable
fun rememberCountdown(seconds: Int): Pair<Int, () -> Unit> {
    var left by remember { mutableStateOf(0) }
    LaunchedEffect(left) {
        if (left > 0) {
            delay(1000)
            left -= 1
        }
    }
    return left to { if (left == 0) left = seconds }
}

/**
 * 「发送验证码」的当前状态，由 [rememberSendCodeState] 产出。
 *
 * @property onStart 点「发送验证码」时调用：立刻开始倒计时并亮起转圈。
 */
@Immutable
data class SendCodeUiState(
    val secondsLeft: Int,
    val sending: Boolean,
    val onStart: () -> Unit
)

/**
 * 登录页与注册页共用的「发送验证码」状态机。
 *
 * 抽到这里的原因：两页的这套交互**必须完全一致**（需求明确要求登录页参照注册页），
 * 各写一份迟早会走样。
 *
 * 时序（按需求）：
 * 1. 点击 → **立刻**开始 60 秒倒计时，同时显示转圈；
 * 2. 转圈持续 [SEND_MS_BASE]±[SEND_MS_JITTER] 毫秒；
 * 3. **转圈结束后**才回调 [onSent]（由调用方弹「已发送」提示）。
 *
 * 为什么提示要等转圈结束：转圈期间就弹「已发送」，而圈还在转，
 * 用户会分不清到底是「正在发」还是「已经发完了」，两个信号自相矛盾。
 *
 * 为什么转圈时长要抖动：每次都是同一个精确毫秒数会显得像假动画，
 * 真实网关的返回时间本来就是抖动的。
 *
 * @param onSent 转圈结束后调用（弹提示用）。
 * @param seconds 倒计时秒数。
 */
@Composable
fun rememberSendCodeState(
    onSent: () -> Unit,
    seconds: Int = 60
): SendCodeUiState {
    var left by remember { mutableStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    val durationMs = remember { mutableIntStateOf(SEND_MS_BASE) }
    // 回调用 rememberUpdatedState 持有最新值，避免调用方每次重组换 lambda 时读到旧的。
    val currentOnSent by rememberUpdatedState(onSent)

    LaunchedEffect(left) {
        if (left > 0) {
            delay(1000)
            left -= 1
        }
    }

    LaunchedEffect(sending) {
        if (sending) {
            delay(durationMs.intValue.toLong())
            sending = false
            currentOnSent()
        }
    }

    // 注意这里用 by 委托：下面的 lambda 读到的始终是**调用时**的最新值，不存在闭包过期问题，
    // 因此不需要把整个对象塞进 remember。
    return SendCodeUiState(
        secondsLeft = left,
        sending = sending,
        onStart = {
            durationMs.intValue = (
                SEND_MS_BASE + Random.nextInt(-SEND_MS_JITTER, SEND_MS_JITTER + 1)
                ).coerceAtLeast(SEND_MS_MIN)
            sending = true
            // 倒计时与转圈同帧启动：不让用户为了看动画而白等一秒。
            if (left == 0) left = seconds
        }
    )
}

/**
 * 「发送验证码」转圈动画的基准时长与抖动幅度（毫秒）。
 *
 * 实际时长 = [SEND_MS_BASE] ± [SEND_MS_JITTER]，下限 [SEND_MS_MIN]。
 */
private const val SEND_MS_BASE = 780
private const val SEND_MS_JITTER = 260
private const val SEND_MS_MIN = 420

// ---- 昵称校验（注册页）------------------------------------------------------

/** 昵称长度下限（含）。 */
const val NICKNAME_MIN_LENGTH = 2

/** 昵称长度上限（含）。 */
const val NICKNAME_MAX_LENGTH = 12

/**
 * 昵称是否**只由中文、英文、数字组成**。
 *
 * 用逐字符判定而不是正则：需求要排除的正是「中英数以外的一切」
 * （空格、emoji、标点、日文假名、韩文…），白名单比黑名单更不容易漏。
 *
 * - `Char.isLetter()` 对中文与英文都为 true，但**也对日文 / 韩文 / 俄文等为 true**，
 *   所以不能只用它；这里额外要求「CJK 统一表意文字区」或 ASCII 字母。
 * - 数字用 [Char.isDigit]（含全角数字）；范围外的一律不通过。
 */
fun isNicknameCharAllowed(c: Char): Boolean {
    if (c in '0'..'9') return true
    if (c in 'a'..'z' || c in 'A'..'Z') return true
    // CJK 统一表意文字基本区（覆盖常用汉字）。
    if (c.code in 0x4E00..0x9FFF) return true
    return false
}

/** 昵称整体是否合法（字符集 + 长度）。 */
fun isNicknameValid(value: String): Boolean =
    value.length in NICKNAME_MIN_LENGTH..NICKNAME_MAX_LENGTH &&
        value.all(::isNicknameCharAllowed)

/**
 * 昵称输入是否**包含非法字符**。
 *
 * 与 [isNicknameValid] 分开：需求要求「填了中英数以外的字符就变红报错」，
 * 而「太短」不报错（用户刚开始输入，两三个字之前不该一直红着）。
 */
fun nicknameHasIllegalChar(value: String): Boolean = value.any { !isNicknameCharAllowed(it) }

/**
 * 昵称「查重」状态，由 [rememberNicknameCheckState] 产出。
 *
 * @property checking 正在转小圈（约 1 秒）。
 * @property available 查重通过，可以显示「昵称可使用」。
 */
@Immutable
data class NicknameCheckUiState(
    val checking: Boolean,
    val available: Boolean
)

/**
 * 昵称「查重」状态机：输入合法 → 转约 1 秒小圈 → 提示「昵称可使用」。
 *
 * **纯前端延时模拟**，不接后端（本项目整体就是本地原型，注册也不发真实请求）。
 * 加一个转圈的意义在于：真实产品里昵称查重必然是一次往返，
 * 让用户在这里先看到「系统在检查」的反馈，接上后端时界面无需再改。
 *
 * 关键行为：
 * - 输入**不合法**（含非法字符或长度不足）时**不转圈、也不说可用**，
 *   立刻把状态清干净 —— 否则会先转圈再报错，看起来像检查通过了又反悔。
 * - 每次输入变化都重新计时（`LaunchedEffect(value)`），所以连续输入时
 *   转圈会不断重启，最终只在**停止输入后**的那一次真正给出结论。
 */
@Composable
fun rememberNicknameCheckState(value: String): NicknameCheckUiState {
    var checking by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        // 先复位：任何一次输入变化都让上一个结论失效。
        checking = false
        available = false
        if (isNicknameValid(value)) {
            checking = true
            delay(NICKNAME_CHECK_MS)
            checking = false
            available = true
        }
    }

    return NicknameCheckUiState(checking = checking, available = available)
}

/** 昵称查重转圈的时长。需求要求「转 1s 左右」。 */
private const val NICKNAME_CHECK_MS = 1000L

// ---- 开发者选项「验证码测试」：发送验证码时生成并通知 -------------------------

/**
 * 「验证码测试」在登录 / 注册页的接线结果，由 [rememberDevCodeSender] 产出。
 *
 * @property enabled 「验证码测试」当前是否**生效**（总开关 && 子开关，已在仓库里与过）。
 *   页面用它决定「已发送」提示该说哪一句。
 * @property send 点「发送验证码」时调用：生效时生成六位码、记为后端记录并发通知；
 *   未生效时**什么都不做**，页面行为与从前完全一致。
 */
@Immutable
data class DevCodeSender(
    val enabled: Boolean,
    val send: () -> Unit
)

/**
 * 把「发送验证码」接到开发者选项的验证码测试上。**登录页与注册页共用**：
 * 两页都有这个按钮，各接一份迟早会出现「一页发了码、另一页没发」的错位。
 *
 * ## 为什么生成动作放在**点击那一刻**，而不是 `rememberSendCodeState` 的 `onSent`
 *
 * 需求原文是「点击『发送验证码』时，App 将在后台随机生成六位数字并记录在后端」。
 * `onSent` 是转圈结束（约 0.8 秒后）才回调的，把生成放那儿会让「点击」与「记录」
 * 之间隔着一个纯动画的时长；更糟的是——用户如果在转圈期间直接离开页面，
 * `onSent` 根本不会执行，码就没生成，可提示却已经亮过。
 * 放在点击回调里，时序与语义都是直的：点一下 = 记一个码。
 *
 * ## 通知权限
 *
 * 验证码是**通过通知**送达的，所以系统没给通知权限时必须让用户知道，
 * 否则表现就是「提示已发送，但通知栏什么都没有」，看起来像功能坏了。
 * 这里先照常 [com.morecheck.data.DeveloperOptionsRepository.issueCode]（后端记录不该
 * 依赖通知权限是否授予），再检查权限：没给就弹一次系统申请框并附一句说明。
 *
 * API < 33 上 `POST_NOTIFICATIONS` 不存在，`isPostNotificationGranted()` 恒为 true，
 * 因此不会走到申请分支（也不会对着一个不存在的权限调 `launch`）。
 *
 * @param onMessage 需要告知用户时的一句话（两页都接到各自的 toast 上）。
 */
@Composable
fun rememberDevCodeSender(onMessage: (String) -> Unit): DevCodeSender {
    val context = LocalContext.current
    // 容器是进程单例，`remember` 一下即可；不要每次重组都去 cast。
    val repository = remember {
        (context.applicationContext as MoreCheckApp).container.developerOptionsRepository
    }
    val notificationHelper = remember {
        (context.applicationContext as MoreCheckApp).container.notificationHelper
    }
    // 开关在 DataStore 里，订阅它即可实时跟随开发者选项页的改动。
    val enabled by repository.codeTestEnabled.collectAsStateWithLifecycle(initialValue = false)

    val currentOnMessage by rememberUpdatedState(onMessage)
    // launcher 必须在组合期创建一次（同一实例），因此放在这里而不是点击回调里。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) currentOnMessage("未获得通知权限，收不到验证码，请到系统设置里允许")
    }

    // 刻意**不**用 `remember` 固定这个 lambda：它每次重组重新创建，而 `enabled` 是委托属性、
    // 每次执行都会重新读 `State.value`，所以无论哪种写法读到的都是最新值。
    // 不 remember 反而少一层「捕获的是哪一次组合的 State」的推理负担。
    val send: () -> Unit = {
        if (enabled) {
            repository.issueCode()
            if (!notificationHelper.isPostNotificationGranted()) {
                currentOnMessage("请允许通知权限，否则收不到验证码")
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    return DevCodeSender(enabled = enabled, send = send)
}
