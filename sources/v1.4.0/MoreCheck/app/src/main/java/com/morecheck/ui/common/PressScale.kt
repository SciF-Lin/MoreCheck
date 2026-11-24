package com.morecheck.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 全局统一的「按下即缩放 + 变淡」按压反馈，给所有可点元素共用。
 *
 * ## 为什么要有这个修饰符（v1.1.72 需求原文）
 *
 * > 所有按钮目前的动画效果都一般，要点击甚至长按才有动画效果，请设法优化。
 *
 * 问题的本质是：**大部分可点元素用的是 `Modifier.clickable` 的默认水波纹**，
 * 而本项目视觉取向是「扁平、灰底白卡」（见 `Theme.kt` 顶部注释）——
 * 水波纹是一块**高亮的彩色圆形**，与扁平风格本身就不搭；且在
 * `surfaceContainer*` 这类接近白色的容器上，默认 ripple 的颜色极淡，
 * 于是表现为「按下去几乎看不出反应」，用户要按到底甚至长按才察觉到"它是不是坏了"。
 *
 * 少数自绘的（计时页按钮、扫一扫圆钮、实验室宫格）各自实现过一份缩放动画，
 * 但参数各不相同、也不是处处都有——同一应用里三种按压手感，也是"效果一般"的一部分。
 *
 * ## 为什么用「缩放 + 变淡」而不是水波纹
 *
 * 1. **与扁平风格一致**：缩放是几何变换，不引入任何颜色；
 * 2. **在任意底色上都可见**：变淡用 alpha（合成期系数），不依赖底色与 ripple 色的对比；
 * 3. **按下瞬间就有反馈**：`collectIsPressedAsState` 在手指**按下的那一帧**就出状态，
 *    而水波纹要等系统派发 `PressInteraction` 且有些路径上还要等到抬起才补画；
 * 4. **零成本复用**：一个修饰符，全项目统一手感。
 *
 * ## 为什么缩放量是 0.96 而不是更小
 *
 * `TimerPanel` 的圆形按钮用 0.84（64dp 按钮，位移约 10dp，需求要"较为明显"），
 * 但**列表行 / 卡片**不能照搬那个值：列表行往往很宽（通栏），0.84 会让整行横向收缩近 60dp，
 * 视觉上是"整行被捏扁"而不是"被按下"，且会让行内的文字一起被压缩变形。
 * 0.96 在通栏行上是约 12px 的位移（360dp 宽下），够察觉又不破坏排版。
 *
 * 想要更明显反馈的大按钮，调用处传更小的 `pressedScale`（如 0.92）即可——
 * 这正是 `AuthComponents.authPressable` 已有的做法（它默认 0.96、圆钮传 0.92），
 * 本修饰符与它**口径一致**，不是另起一套。
 *
 * ## ⚠️ `clip` 的顺序约束（调用方必须遵守）
 *
 * 缩放是在 `graphicsLayer` 里做的，它作用于**这一层及其子内容**。
 * 如果调用方在**缩放层之前**就 `clip(shape)`，那么裁剪路径是在未缩放的坐标系里算的，
 * 缩放后的内容会被按旧路径裁掉——表现为**圆角边缘在中间帧上退化成多边形**
 * （微信 / QQ 的圆形图标上实测踩过这个坑，见 `AuthComponents.AuthSocialButton` 的注释）。
 *
 * 所以：**调用方若需要裁剪，必须把 `clip` 放在本修饰符之后**（即缩放层的内侧），
 * 形如 `Modifier.pressScale(...).clip(shape).background(...)`。
 * 本项目现有代码（`AuthSocialButton`、`TimerActionButton`）都是这个顺序。
 *
 * ## 关于 `indication = null`
 *
 * 这里显式关掉系统水波纹：本修饰符的缩放就是反馈，再叠一层 ripple 会
 * **两种反馈同时出现**（又缩又亮），反而显得脏。这也让"关掉 ripple"成为一个
 * 显式决定而不是副作用。
 *
 * @param onClick 点击回调。
 * @param enabled 是否可点。false 时**不播放任何动画**（避免置灰元素还在"呼吸"）。
 * @param pressedScale 按下时的缩放系数，默认 [DEFAULT_PRESSED_SCALE]。
 * @param pressedAlpha 按下时整体的不透明度，默认 [DEFAULT_PRESSED_ALPHA]。
 *   「变淡」是第二通道：缩放对极扁的元素（如 4dp 高的分隔条、细进度条）几乎看不出来，
 *   而 alpha 对任何形状都有效。两者叠加保证"一定看得出来"。
 * @param haptic 是否在点击时触发一次长按触觉反馈。默认 true
 *   （本项目大部分可点元素都带，见 `AuthComponents.authPressable`）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressScale(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressedScale: Float = DEFAULT_PRESSED_SCALE,
    pressedAlpha: Float = DEFAULT_PRESSED_ALPHA,
    haptic: Boolean = true,
    /**
     * 长按回调（2026-11-14 需求 5：发现页长按可把入口移到实验室）。
     *
     * 为 null 时行为与原来完全一致（内部仍用 `combinedClickable`，
     * 只是不派发长按）——这样调用点不必因为"多了个能力"各改一遍。
     * 长按同样受「按压反馈」总闸的触觉约定约束：总闸关掉时只执行动作、不震动。
     */
    onLongClick: (() -> Unit)? = null
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ⚠️ v1.1.74 需求 8：「开发者选项 → 界面测试 → 按压反馈」的总闸。
    //
    // ## 为什么在这里读，而不是让 40 多个调用点各自传参
    //
    // 本修饰符的调用点遍布全应用（HomeScreen / LabScreen / DeveloperOptionsScreen …），
    // 逐个加一个参数意味着**改几十个文件**且以后每加一处都要记得传——漏一处就静默失效。
    // 本函数自己就是 `@Composable`（它必须组合才能读 `collectIsPressedAsState`），
    // 因此可以直接读 [LocalPressFeedbackEnabled]，**一处修改即全站生效**。
    //
    // ## 关掉时的行为：完全无反馈（不是回落到水波纹）
    //
    // 本修饰符显式传了 `indication = null`（见 KDoc「关于 indication = null」——
    // 那是"缩放就是反馈，再叠 ripple 显得脏"这条显式决定）。若改成"关掉缩放、
    // 放开 ripple"，等于把**两种反馈同时存在**这件事又请回来，而且用户明确说了
    // 「不要按压动效」——他要的是零反馈，不是换一种反馈。
    //
    // 触觉反馈同样归零：`haptic` 也读同一个开关，否则会出现"视觉没动、手机却在震"
    // 的割裂感。
    val pressFeedbackOn = LocalPressFeedbackEnabled.current
    val actuallyEnabled = enabled && pressFeedbackOn

    // 用**无过冲的临界阻尼**弹簧（DampingRatioNoBouncy = 1.0）：
    // 按下要"干脆陷下去"，弹起也不要越过 1.0 再回弹——
    // 本项目 `authPressable` 对圆形图标正是用这个 spec（理由见那里的注释：
    // 回弹会让尺寸在中间帧越过目标值，圆形边缘容易看出棱角）。
    // 对非圆形的列表行 / 卡片，无过冲同样更"干净"：过冲会让行内容抖一下。
    val scale by animateFloatAsState(
        targetValue = if (pressed && actuallyEnabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed && actuallyEnabled) pressedAlpha else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressAlpha"
    )
    val hapticFeedback = LocalHapticFeedback.current

    // 动画的目标值与 clickable 的 enabled 都改读 `actuallyEnabled`：
    // 开关关闭时 `actuallyEnabled` 恒 false → scale/alpha 恒为静止值、触觉不触发，
    // 但 `clickable` 仍需保持可点（所以下面传的是 `enabled = enabled`，不是
    // `actuallyEnabled`）——用户关掉的是"动效"，不是"这个按钮能点"。
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .alpha(alpha)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onLongClick = onLongClick?.let { action ->
                {
                    if (haptic && pressFeedbackOn) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    action()
                }
            },
            onClick = {
                if (haptic && pressFeedbackOn) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            }
        )
}

/**
 * 「按压反馈」总闸（v1.1.74 需求 8）。
 *
 * ## 为什么要用 CompositionLocal，而不是给 [pressScale] 加一个参数
 *
 * [pressScale] 的调用点遍布全应用（40+ 处）。加参数意味着改几十个文件，
 * 且以后每新增一个调用点都要记得传——漏一处就静默失效，而且**编译不会报错**。
 * 由 [pressScale] 自己读这个 Local，是"一处修改、全站生效"。
 *
 * ## 默认值为什么是 `true`
 *
 * 默认必须等于**改造前的既有观感**（本项目对所有新开关的硬要求）。
 * 所以任何尚未被 `CompositionLocalProvider` 包裹的组合子树、
 * 以及预览（Preview）环境，读到的都是 `true` = 照常有反馈。
 */
val LocalPressFeedbackEnabled = staticCompositionLocalOf { true }

/** [pressScale] 的默认按下缩放：通栏列表行的安全值，理由见该函数的 KDoc。 */
const val DEFAULT_PRESSED_SCALE = 0.96f

/** [pressScale] 的默认按下不透明度：与缩放叠加的第二通道反馈。 */
const val DEFAULT_PRESSED_ALPHA = 0.72f
