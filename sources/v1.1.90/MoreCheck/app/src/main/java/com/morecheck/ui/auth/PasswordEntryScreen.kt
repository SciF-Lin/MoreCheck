package com.morecheck.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import kotlinx.coroutines.delay

/**
 * 「密码登录」第二步：给上一步填的手机号输入密码。
 *
 * 版式照参考图：顶部返回 → **居中的灰色默认头像** → 头像正下方是脱敏手机号 →
 * 密码框 → 小字「用短信验证码登录」→ 通栏「登录」→ 底部「找回密码」。
 *
 * ## 为什么手机号要居中显示在头像下方
 *
 * 这是用户给的参考图的排布，但更有实际理由：密码登录这条路上，**手机号是隐式的上下文**
 * （用户上一步刚打完，眼前这一屏却没有任何地方再提起它）。不显示的话，
 * 用户看着一个空白表单没法确认「我登的是哪个号」；而把号码摆在头像下面居中，
 * 一眼就能核对，且不需要为此占一行表单（页面重心仍在密码框上）。
 *
 * 号码用 [maskPhone] 脱敏：**这一屏是密码输入页，属于「可能被旁人看到」的场景**，
 * 完整号码摊在屏幕中央等于把它暴露给身边人。脱敏后既能核对，又不泄露全号。
 *
 * ## 头像为什么用「灰底 + 人形图标」而不是首字母
 *
 * 参考图里的头像就是一个中性灰占位。用手机号首字母当头像看起来更「有设计感」，
 * 但本页并没有真实账号体系，头像内容与账号毫无关系，只会让人误以为那是头像设置过的结果。
 * 颜色取 `surfaceContainerHigh`（深浅色模式下都会自动变成合适的灰），
 * 与「我的信息」页的默认头像同源，**不新增颜色常量**。
 *
 * ## 与「密码错误」的关系
 *
 * 本页**不校验密码内容**：需求原文是「输入任何密码都提示登录成功」。
 * 所以这里既没有「密码错误」的失败分支、也没有失败抖动；
 * 校验只做「非空」，且失败提示走内联红字（与第一步、与既有登录页同一套语言）。
 */
@Composable
fun PasswordEntryScreen(
    phone: String,
    onBack: () -> Unit,
    onOpenCodeLogin: () -> Unit,
    onOpenForgotPassword: () -> Unit,
    onLoggedIn: () -> Unit,
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val colors = rememberAuthColors()

    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // 「请输入密码」的内联错误：只在一瞬间成立（按钮本身在密码为空时就是灰的），
    // 保留它是因为**它才是用户能看懂的反馈**——按钮变灰不会告诉用户缺的是哪一项。
    var passwordError by remember { mutableStateOf(false) }

    var toast by remember { mutableStateOf<String?>(null) }

    val isSubmitting by authViewModel.isSubmitting.collectAsStateWithLifecycle()
    val loginSucceeded by authViewModel.loginSucceeded.collectAsStateWithLifecycle()

    // 用 `isSubmitting` 同时驱动文案（「登录中…」）与按钮的 loading 态：
    // 两者必须同源，否则会出现「字已经变回『登录』但圈还在转」的错位帧。
    val canSubmit = password.isNotBlank() && !isSubmitting

    // 「登录成功」的停留时长。AuthToast 自己 1.6 秒后消失，这里只等一段足以读清的时间
    // 就跳走——等满 1.6 秒会让人以为卡住了。
    LaunchedEffect(loginSucceeded) {
        if (loginSucceeded) {
            toast = "登录成功"
            delay(PASSWORD_LOGIN_TOAST_MS)
            // ⚠️ 先消费掉成功信号再跳转。
            //
            // AuthViewModel 是**页面级共享**的（登录页、注册页、本页都拿同一个实例），
            // 所以 `loginSucceeded` 会一直停在 true。本页若被重新进入，
            // `LaunchedEffect(loginSucceeded)` 的键一开始就是 true，会立刻再弹一次
            // 「登录成功」并自动跳走——用户会看到自己刚进来就被踢出去。
            // 消费掉才是「一次性事件」该有的样子。
            authViewModel.resetLoginSuccess()
            onLoggedIn()
        }
    }

    // 提示的自动清空由本页自己管（与登录页同一写法：1.6 秒后清掉，
    // 避免下次回到本页时又闪一下同一句话）。
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
    ) {
        // 与登录页 / [PasswordLoginScreen] 同一套背景光晕，避免两步之间切换时底色发跳。
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.width * 0.62f
            val center = Offset(size.width * 0.94f, -radius * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.18f), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            AuthBackButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                tint = colors.onPage,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )

            // 注意：这一栏**没有**像第一步那样的大标题。
            //
            // 参考图的排布是「返回 → 头像 → 手机号 → 密码框」，头像本身就是这一屏的视觉标题；
            // 再压一行 34sp 的「密码登录」会把头像挤下去，整个页面重心散掉。
            // 步骤感由第一步的大标题与「通过验证码登录」的返回路径承担，这里不必重复。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))

                // 灰色占位头像：Surface + 圆形裁剪 + 人形图标。
                // 用 Surface 而不是 Box + background：`Surface` 的默认 `contentColor`
                // 会让里面的图标在没显式指定 tint 时也能跟着深浅色走，这里显式传 tint
                // 只是为了让「图标比底色更深一档」这件事写在一眼可见的地方。
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(PasswordAvatarSize)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "头像",
                        tint = colors.muted,
                        // 图标占头像的约一半：太满会显得头像被撑爆，太小又看不清是人形。
                        modifier = Modifier.padding(PasswordAvatarIconInset)
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = maskPhone(phone),
                    color = colors.muted,
                    fontSize = 15.sp
                )

                Spacer(Modifier.height(34.dp))
            }

            // 表单部分单独一栏、左对齐：头像区居中，输入框区左对齐。
            // 输入框如果跟着居中，下划线的左右端点就会与第一步（左对齐）不一致，
            // 从第一步跳到第二步时会看到表单「横移」一下。
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                AuthUnderlineField(
                    value = password,
                    onValueChange = { input ->
                        password = input
                        if (passwordError && password.isNotEmpty()) passwordError = false
                    },
                    // 参考图这里是「请填写微信密码」；本页**不用微信字样**，改成中性文案。
                    placeholder = "请填写密码",
                    leadingIcon = Icons.Filled.Lock,
                    colors = colors,
                    isError = passwordError,
                    supporting = if (passwordError) {
                        {
                            Text(
                                "请输入密码",
                                color = colors.errorColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        null
                    },
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    // 明文/密文切换：与注册页完全同一套写法（含图标语义）。
                    // 默认打码，与注册页一致：密码框先打码是用户的默认预期。
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        Box(
                            Modifier
                                .size(44.dp)
                                .authPressable(
                                    onClick = { passwordVisible = !passwordVisible },
                                    pressedScale = 0.92f
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.Visibility
                                } else {
                                    Icons.Filled.VisibilityOff
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                tint = colors.muted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                )

                Spacer(Modifier.height(20.dp))

                // 「用短信验证码登录」= 回到验证码登录。与第一步的「通过验证码登录」一样，
                // 交给调用方决定怎么回去（可能就是 onBack，也可能要重置整条栈）。
                AuthTextLink(
                    text = "用短信验证码登录",
                    color = colors.accent,
                    onClick = onOpenCodeLogin
                )

                Spacer(Modifier.height(34.dp))

                AuthPrimaryButton(
                    // 文案与 loading 同时看 `isSubmitting`：需求要求「转 1 秒左右再提示成功」，
                    // 按钮上的「登录中…」就是这 1 秒唯一的进度信号。
                    // AuthPrimaryButton 在 loading 时画的是小转圈、**不显示文字**，
                    // 所以这里传「登录中…」是为非 loading 的那一帧兜底，不会与转圈打架。
                    text = if (isSubmitting) "登录中…" else "登录",
                    colors = colors,
                    enabled = canSubmit,
                    loading = isSubmitting,
                    onClick = {
                        if (password.isBlank()) {
                            passwordError = true
                        } else {
                            passwordError = false
                            // 走演示专用入口：它**不校验密码内容**（原因见
                            // [AuthViewModel.submitPasswordDemo] 的 KDoc）。
                            authViewModel.submitPasswordDemo(phone)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    AuthTextLink(
                        text = "找回密码",
                        color = colors.accent,
                        underline = true,
                        onClick = onOpenForgotPassword
                    )
                }
            }
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 「登录成功」提示的停留时长（毫秒），随即触发 [PasswordEntryScreen] 的 `onLoggedIn`。 */
private const val PASSWORD_LOGIN_TOAST_MS = 1100L

/** 占位头像的直径。取 84dp：比「我的信息」页那个 36dp 的列表头像大得多，才撑得起页面重心。 */
private val PasswordAvatarSize = 84.dp

/** 占位头像内人形图标四周留白。84dp − 2×22dp = 40dp 的图标，正好是头像的一半。 */
private val PasswordAvatarIconInset = 22.dp
