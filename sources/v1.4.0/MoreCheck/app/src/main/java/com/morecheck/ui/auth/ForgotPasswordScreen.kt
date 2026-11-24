package com.morecheck.ui.auth

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudError
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「找回密码」页：手机号 + 短信验证码 → 设置新密码。
 *
 * ## 真实链路（2026-11-03 起本页不再是演示）
 *
 * 三步全部是真实请求，与登录页共用同一个 [CloudAuthClient]：
 * 1. **发码**：`sendCode(phone, usage = PASSWORD_RESET)`——云平台真实短信，
 *    服务端实测接受手机号的这个用途标记（60s 冷却与登录页同一套状态机）；
 * 2. **验证码证明身份**：`verifyForPasswordReset` 只校验验证码并取一次性令牌，
 *    **不建会话**（比「verify 即登录」少踢一次其它设备的线）；
 * 3. **重置密码**：`resetPasswordByPhone`（`POST /v1/reset`，服务端实测 200）。
 *    这是**唯一不需要旧密码**的设密端点——已登录改密走 sudo 流程必须验旧密码，
 *    未设过密码的账号只有这条路可设。成功后用新密码走一次密码登录建立会话，
 *    用户直接进入已登录状态。
 *
 * ## 2026-11-03 修：三处"不能假装成功"的边界
 *
 * 1. **自动密码登录可能失败**（刚写入的密码尚未生效 / 服务端限制 / 网络断）。
 *    这时密码**已经设置成功**，但用户没有会话。原实现掉进统一的错误提示、
 *    停在原地，用户再点一次「设置新密码」会拿一个已被 `/v1/reset` 消耗掉的
 *    验证令牌去重置，必然失败——等于卡死。现在降级为：
 *    「密码已设置成功，但自动登录失败，请用短信验证码登录」→ 把手机号写进
 *    [LoginPhonePrefill] → 退回登录页（尽可能落在登录页，见下）。
 * 2. **验证令牌要缓存**：`/v1/verification/verify` 换到的令牌是一次性的，
 *    而 `/v1/reset` 失败（网络抖动）时用户重试的是"设置密码"这一步，
 *    不该再做一次 verify（同一个验证会话已被消费）。令牌存在 [resetToken]，
 *    重试只重发 reset。
 * 3. **`/v1/reset` 对失效令牌回 404**（实测错误体是 "Username or password
 *    incorrect."，对"令牌过期"与"号码不存在"不区分）。这种情况唯一能做的就是
 *    重新取码，所以清掉旧挑战并明确提示「验证码已失效，请重新获取」。
 *
 * ## 降级时"导航回登录页"的边界（如实说明）
 *
 * 本页的 `onBack` 只弹一层栈。从登录页「忘记密码?」进来的最常见路径下，
 * 弹一层正好回到登录页；从「密码登录」两步页进来的路径下会先回到密码登录页
 * （那里有「用短信验证码登录」入口，且凭 [LoginPhonePrefill] 回登录页时号码已预填）。
 * 要无条件直达登录页需要改 `TabNavHost` 的导航参数，超出本次文件白名单，故未做。
 *
 * @param onDone 密码设置成功并完成登录后的离开动作——调用方把栈弹回登录流程起点
 *   （与密码登录成功后的落点同一套，见导航图的同段注释）。
 */
@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember(context) { context.appContainer.cloudAuthClient }

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf("") }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }
    /**
     * `verifyForPasswordReset` 换到的一次性令牌（2026-11-03）。
     *
     * 缓存它，是为了让「设置新密码」这一步可以安全重试：verify 的验证会话是一次性的，
     * 而 `/v1/reset` 可能因网络抖动失败；重试时复用令牌只重发 reset，
     * 不重新 verify（否则服务端会报验证会话已被消费）。
     */
    var resetToken by remember { mutableStateOf<String?>(null) }

    var toast by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val sendState = rememberSendCodeState(onSent = { /* 真发码成功即倒计时，无需额外提示 */ })

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    val canSubmit = phone.isNotBlank() && code.isNotBlank() &&
        password.isNotBlank() && confirm.isNotBlank() && !submitting

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
    ) {
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

            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "找回密码",
                    color = colors.onPage,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "验证短信验证码后设置新密码",
                    color = colors.muted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(34.dp))

                AuthUnderlineField(
                    value = phone,
                    onValueChange = { input ->
                        val next = input.filter(Char::isDigit).take(11)
                        if (next != phone) {
                            // 换号 = 之前的验证会话与令牌作废：
                            // 否则会拿 A 号收到的验证码 / 令牌去重置 B 号的密码。
                            challenge = null
                            resetToken = null
                            code = ""
                        }
                        phone = next
                        if (phoneError && phone.isNotEmpty()) phoneError = false
                    },
                    placeholder = "手机号",
                    leadingIcon = Icons.Filled.Phone,
                    colors = colors,
                    isError = phoneError,
                    supporting = if (phoneError) {
                        {
                            Text(
                                "请输入手机号",
                                color = colors.errorColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    } else {
                        null
                    },
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(26.dp))

                // 验证码行：与登录页逐字节同构（输入在左 / 竖线 / 「发送验证码」在右）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuthUnderlineField(
                        value = code,
                        onValueChange = { code = it.filterNot(Char::isWhitespace).take(6) },
                        placeholder = "验证码",
                        leadingIcon = Icons.Filled.MailOutline,
                        colors = colors,
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(colors.hairline)
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier.widthIn(min = SendCodeSlotWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (sending) {
                            SendCodeLoading(colors = colors)
                        } else {
                            AuthTextLink(
                                text = if (sendState.secondsLeft > 0) {
                                    "${sendState.secondsLeft}s 后重发"
                                } else {
                                    "发送验证码"
                                },
                                color = colors.accent,
                                enabled = sendState.secondsLeft == 0,
                                onClick = {
                                    if (phone.isEmpty()) {
                                        // 没填手机号就点发送：只报红、不起倒计时（与旧口径一致）。
                                        phoneError = true
                                        return@AuthTextLink
                                    }
                                    sending = true
                                    scope.launch {
                                        try {
                                            CloudAuthClient.requireMainlandPhone(phone)
                                            // usage=PASSWORD_RESET：verify 后的令牌配套走
                                            // /v1/reset（见 [resetPasswordByPhone] 的 KDoc）。
                                            challenge = authClient.sendCode(
                                                phone, usage = "PASSWORD_RESET"
                                            )
                                            // 新码 = 新验证会话，旧令牌作废。
                                            resetToken = null
                                            sendState.onStart()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: CloudException) {
                                            toast = friendlyCloudMessage(e.error)
                                        } catch (e: Exception) {
                                            toast = "发送失败：${e.message ?: "未知错误"}"
                                        } finally {
                                            sending = false
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(26.dp))

                // 新密码：与注册页同一套打码 / 明文切换写法。
                AuthUnderlineField(
                    value = password,
                    onValueChange = { password = it.take(CloudAuthClient.PASSWORD_MAX_LENGTH) },
                    placeholder = "新密码（至少 6 位）",
                    leadingIcon = Icons.Filled.Lock,
                    colors = colors,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
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

                Spacer(Modifier.height(22.dp))

                AuthUnderlineField(
                    value = confirm,
                    onValueChange = { confirm = it.take(CloudAuthClient.PASSWORD_MAX_LENGTH) },
                    placeholder = "确认新密码",
                    leadingIcon = Icons.Filled.Lock,
                    colors = colors,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(34.dp))

                AuthPrimaryButton(
                    text = if (submitting) "设置中…" else "设置新密码",
                    colors = colors,
                    enabled = canSubmit,
                    loading = submitting,
                    onClick = {
                        val current = challenge
                        when {
                            // 与登录页同一句引导：没有有效的验证会话就不能提交。
                            current == null -> toast = "请先获取验证码"
                            code.isBlank() -> toast = "请输入验证码"
                            password.length < CloudAuthClient.PASSWORD_MIN_LENGTH ->
                                toast = "密码长度需至少 ${CloudAuthClient.PASSWORD_MIN_LENGTH} 位"
                            password != confirm -> toast = "两次输入的密码不一致"
                            else -> {
                                submitting = true
                                scope.launch {
                                    try {
                                        // ① 验证码证明身份（只取一次性令牌，不建会话）。
                                        //    令牌缓存复用：/v1/reset 失败重试时不再 verify
                                        //    （验证会话是一次性的，重复 verify 会被服务端拒绝）。
                                        val token = resetToken
                                            ?: authClient.verifyForPasswordReset(current, code)
                                                .also { resetToken = it }
                                        // ② 重置密码（/v1/reset，唯一免旧密码的设密端点）。
                                        authClient.resetPasswordByPhone(
                                            current.phone, password, token
                                        )
                                        // ③ 用新密码登录建立会话。**这一步失败不代表重置失败**：
                                        //    密码已经写进服务端，只是没拿到会话。必须单独兜住并降级，
                                        //    否则会掉进外层 catch、停在原地——用户重试时拿的是
                                        //    已被 /v1/reset 消耗的令牌，必然再次失败（见 KDoc 第 1 条）。
                                        val session = try {
                                            authClient.signInWithPhonePassword(
                                                current.phone, password
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            // 降级：把号码带回登录页，请用户用短信验证码登录。
                                            // 写 11 位裸号：消费方（LoginScreen）只认这个格式。
                                            LoginPhonePrefill.pending =
                                                CloudAuthClient.phoneDigits(current.phone)
                                                    ?: current.phone
                                            toast = "密码已设置成功，但自动登录失败，请用短信验证码登录"
                                            delay(SET_PASSWORD_FALLBACK_MS)
                                            onBack()
                                            return@launch
                                        }
                                        val cloudName = try {
                                            authClient.me().name
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            null
                                        }
                                        context.appContainer.authRepository.onCloudLoginSucceeded(
                                            phone = session.phone ?: current.phone,
                                            cloudName = cloudName
                                        )
                                        toast = "密码已设置，已为你登录"
                                        delay(1100)
                                        onDone()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: CloudException) {
                                        if (e.error.kind == CloudError.Kind.NOT_FOUND) {
                                            // /v1/reset 对"令牌失效 / 过期"与"号码不存在"都回 404
                                            // （实测错误体 "Username or password incorrect."）。
                                            // 重试没有意义，唯一出路是重新取码。
                                            resetToken = null
                                            challenge = null
                                            toast = "验证码已失效，请重新获取验证码后再试"
                                        } else {
                                            toast = friendlyCloudMessage(e.error)
                                        }
                                    } catch (e: Exception) {
                                        toast = "设置失败：${e.message ?: "未知错误"}"
                                    } finally {
                                        submitting = false
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))
            }
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 「自动登录失败」降级提示的停留时长（毫秒）。
 *
 * 该句包含"密码其实已经设置成功"这个关键信息，用户没看清就退回登录页，
 * 会以为重置失败了而再走一遍流程（那时验证码已作废）。取 1500ms：
 * 比页面 toast 的自动清空（1600ms）略早，保证点回退那一刻提示还在屏上。
 */
private const val SET_PASSWORD_FALLBACK_MS = 1500L
