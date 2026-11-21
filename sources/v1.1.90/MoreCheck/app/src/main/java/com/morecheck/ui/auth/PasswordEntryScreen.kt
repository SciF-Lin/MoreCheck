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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * ## 这一页现在是真的（v1.1.91 起）
 *
 * 改造前这里是纯演示：任意密码都提示成功。现在：
 * - 点击「登录」走 `CloudAuthClient.signInWithPassword` —— 手机号 + 真实密码，
 *   由云端校验；
 * - 成功经 `AuthRepository.onCloudLoginSucceeded` 落地本地登录态（与验证码登录
 *   共用同一条落地路径，「我的」页随即显示已登录）；
 * - 失败按服务端原文展示（[friendlyCloudMessage]），并**不区分**「号码没注册」
 *   与「密码错」——账号存在性不做区分，统一一句「手机号或密码不正确」。
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
 * ## 密码错误怎么反馈
 *
 * 走 toast（与登录页的错码提示同一出口），**不做**输入框红态抖动：服务端的
 * 「密码错」与「号码不存在」是同一种失败，标在输入框上会让人以为「格式不对」。
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
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // 「请输入密码」的内联错误：只在一瞬间成立（按钮本身在密码为空时就是灰的），
    // 保留它是因为**它才是用户能看懂的反馈**——按钮变灰不会告诉用户缺的是哪一项。
    var passwordError by remember { mutableStateOf(false) }

    var toast by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    val canSubmit = password.isNotBlank() && !submitting

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))

                // 灰色占位头像：Surface + 圆形裁剪 + 人形图标。
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(PasswordAvatarSize)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = "头像",
                        tint = colors.muted,
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
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                AuthUnderlineField(
                    value = password,
                    onValueChange = { input ->
                        password = input
                        if (passwordError && password.isNotEmpty()) passwordError = false
                    },
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

                // 「用短信验证码登录」= 回到验证码登录。
                AuthTextLink(
                    text = "用短信验证码登录",
                    color = colors.accent,
                    onClick = onOpenCodeLogin
                )

                Spacer(Modifier.height(34.dp))

                AuthPrimaryButton(
                    text = if (submitting) "登录中…" else "登录",
                    colors = colors,
                    enabled = canSubmit,
                    loading = submitting,
                    onClick = {
                        if (password.isBlank()) {
                            passwordError = true
                            return@AuthPrimaryButton
                        }
                        passwordError = false
                        submitting = true
                        scope.launch {
                            try {
                                val session = authClient.signInWithPassword(phone, password)

                                // 云登录落地到本地登录态（与验证码登录同一条路径）。
                                // 昵称取云端资料，失败不影响登录本身。
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
                                toast = "登录成功"
                                delay(PASSWORD_LOGIN_TOAST_MS)
                                onLoggedIn()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: CloudException) {
                                // 401 / 400 都收敛成同一句，不暴露账号是否存在。
                                toast = if (e.error.kind == com.morecheck.data.cloud.CloudError.Kind.UNAUTHENTICATED ||
                                    e.error.kind == com.morecheck.data.cloud.CloudError.Kind.INVALID_REQUEST
                                ) {
                                    "手机号或密码不正确"
                                } else {
                                    friendlyCloudMessage(e.error)
                                }
                            } catch (e: Exception) {
                                toast = "登录失败：${e.message ?: "未知错误"}"
                            } finally {
                                submitting = false
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
