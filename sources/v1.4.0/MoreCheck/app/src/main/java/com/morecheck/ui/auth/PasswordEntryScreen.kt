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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * 但本页登录前账号资料本来就是未知的，头像内容与账号毫无关系，只会让人误以为
 * 那是头像设置过的结果。颜色取 `surfaceContainerHigh`（深浅色模式下都会自动
 * 变成合适的灰），与「我的信息」页的默认头像同源，**不新增颜色常量**。
 *
 * ## 真实登录链路（2026-11-03 起本页不再是演示）
 *
 * 「登录」走 `CloudAuthClient.signInWithPhonePassword`——与验证码登录**同一个**
 * `/v1/signin` 端点（请求体带 password 即走密码校验，服务端契约实测支持），
 * 成功后与登录页同一套落地：`onCloudLoginSucceeded` 写本地登录态、
 * 昵称取云端资料。账号未设密码（`password_not_set`）时明确引导去
 * 底部「找回密码」——那里验证码通过后可直接设置新密码。
 */
@Composable
fun PasswordEntryScreen(
    phone: String,
    onBack: () -> Unit,
    onOpenCodeLogin: () -> Unit,
    onOpenForgotPassword: () -> Unit,
    onLoggedIn: () -> Unit
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember(context) { context.appContainer.cloudAuthClient }

    // 2026-11-11 需求 5：提交后必须收键盘。
    //
    // 这一页的密码框一聚焦就会把 IME 顶上来，而「登录」按钮正好在键盘上方；
    // 点它的那一刻键盘还占着半屏，随后的「登录中…」转圈与「登录成功」提示
    // 全落在键盘后面——用户看到的是"点了没反应"。所以提交的第一步就是
    // 收键盘 + 清焦点（清焦点还会让下划线回到未聚焦态，视觉上也"结束了输入"）。
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // 「请输入密码」的内联错误：只在一瞬间成立（按钮本身在密码为空时就是灰的），
    // 保留它是因为**它才是用户能看懂的反馈**——按钮变灰不会告诉用户缺的是哪一项。
    var passwordError by remember { mutableStateOf(false) }

    var toast by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(false) }

    val canSubmit = password.isNotBlank() && !submitting

    // 「登录成功」的停留时长。AuthToast 自己 1.6 秒后消失，这里只等一段足以读清的时间
    // 就跳走——等满 1.6 秒会让人以为卡住了。
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            toast = "登录成功"
            delay(PASSWORD_LOGIN_TOAST_MS)
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
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "输入账号密码登录",
                    color = colors.muted,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(28.dp))
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
                    // 键盘「确定」键同样收键盘（与点按钮一致，两条路径不留差异）。
                    onImeDone = { dismissKeyboard() },
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
                    // 文案与 loading 同时看 `submitting`：密码登录有一次真实网络往返，
                    // 按钮上的「登录中…」就是这段时间唯一的进度信号。
                    // AuthPrimaryButton 在 loading 时画的是小转圈、**不显示文字**，
                    // 所以这里传「登录中…」是为非 loading 的那一帧兜底，不会与转圈打架。
                    text = if (submitting) "登录中…" else "登录",
                    colors = colors,
                    enabled = canSubmit,
                    loading = submitting,
                    onClick = {
                        // 先收键盘，再校验/提交：空密码的内联报错也要能被看见
                        // （它就在密码框下方，键盘在的时候同样被挡住）。
                        dismissKeyboard()
                        if (password.isBlank()) {
                            passwordError = true
                        } else {
                            passwordError = false
                            submitting = true
                            scope.launch {
                                try {
                                    val session = authClient.signInWithPhonePassword(phone, password)

                                    // 云登录落地到本地登录态（与登录页同一套，见其同段注释）。
                                    val cloudName = try {
                                        authClient.me().name
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                    context.appContainer.authRepository.onCloudLoginSucceeded(
                                        phone = session.phone ?: phone,
                                        cloudName = cloudName
                                    )
                                    loggedIn = true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: CloudException) {
                                    toast = if (CloudAuthClient.isPasswordNotSetError(e)) {
                                        // 还没设过密码：密码登录走不通，但「找回密码」
                                        // 页用验证码就能设置——直接把用户送去那条路。
                                        onOpenForgotPassword()
                                        "该账号还未设置密码，请用验证码设置"
                                    } else {
                                        friendlyCloudMessage(e.error)
                                    }
                                } catch (e: Exception) {
                                    toast = "登录失败：${e.message ?: "未知错误"}"
                                } finally {
                                    submitting = false
                                }
                            }
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
