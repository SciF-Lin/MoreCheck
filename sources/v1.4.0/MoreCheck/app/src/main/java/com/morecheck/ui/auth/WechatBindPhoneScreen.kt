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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「发送验证码」成功后的冷却秒数（与登录页同一口径：重发限频约 1 分钟）。 */
private const val SEND_COOLDOWN_SECONDS = 60

/**
 * 微信登录 · 绑定手机号页（v1.2.4，客户端先行的接缝页）。
 *
 * ## 为什么要有这一页（账号主码约定）
 *
 * 云账号的**唯一主码是手机号**（2026-10-31 约定，见 [CloudAuthClient] 头注）。
 * 微信授权登录拿到的是微信身份，它必须先落到一个手机号账号上：
 * 已关联 → 直接登录（不经过本页）；未关联 → 进本页绑定后才建会话。
 * 这样微信登录与验证码登录永远指向**同一个账号**，不会出现"一个人两份数据"。
 *
 * ## 当前状态（诚实声明）
 *
 * - **发验证码是真的**：走 [CloudAuthClient.sendCode]，云平台代发真实短信；
 * - **绑定提交是接缝**：走 [CloudConfig.WECHAT_BIND_PHONE_PATH]（草案端点），
 *   服务端尚未实现，提交必然得到 404，本页把它如实转成「微信登录暂未开通」，
 *   **不假装绑定成功**。
 * - 入口：登录页「微信登录」说明弹窗的「体验绑定流程（调试）」小字。
 *   正式入口是微信授权返回"未绑定"错误码后自动跳转（服务端落地后生效）。
 *
 * ## 与登录页共享的状态机口径
 *
 * 冷却倒计时在发送**成功后**才开始、错码可重试、错误经 [friendlyCloudMessage]
 * 展示——与 [LoginScreen] 完全一致，避免同一件事两种说法。
 */
@Composable
fun WechatBindPhoneScreen(
    onBack: () -> Unit,
    /** 绑定成功后的回调（当前不可达；服务端落地后走这里回登录流）。 */
    onBindSucceeded: () -> Unit = {}
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }

    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }

    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2000)
            toast = null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
    ) {
        // 与登录页同款右上角光晕：同一簇页面读起来才是一套流程。
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

            Column(Modifier.padding(horizontal = 24.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "绑定手机号",
                    color = colors.onPage,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "微信授权后，首次使用需绑定手机号——" +
                        "你的微信与该手机号共用同一份云数据",
                    color = colors.muted,
                    fontSize = 13.sp
                )
                Text(
                    // 调试入口的诚实标注：正式入口（微信授权后自动跳转）要等
                    // 服务端落地，本页目前只有这一条可见来路。
                    text = "调试预览：服务端尚未开通微信绑定，提交将得到「暂未开通」提示",
                    color = colors.muted.copy(alpha = 0.75f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(28.dp))

                AuthUnderlineField(
                    value = phone,
                    onValueChange = { input ->
                        phone = input.filter(Char::isDigit).take(11)
                        if (phoneError != null && phone.isNotEmpty()) phoneError = null
                    },
                    placeholder = "手机号",
                    leadingIcon = Icons.Filled.Phone,
                    colors = colors,
                    isError = phoneError != null,
                    supporting = phoneError?.let { message ->
                        {
                            Text(
                                message,
                                color = colors.errorColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuthUnderlineField(
                        value = code,
                        onValueChange = { code = it.filterNot(Char::isWhitespace).take(6) },
                        placeholder = "验证码",
                        leadingIcon = Icons.Filled.VpnKey,
                        colors = colors,
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
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
                                text = if (secondsLeft > 0) {
                                    "${secondsLeft}s 后重发"
                                } else {
                                    "发送验证码"
                                },
                                color = colors.accent,
                                enabled = secondsLeft == 0,
                                onClick = {
                                    phoneError = when {
                                        phone.isEmpty() -> "请输入手机号"
                                        else -> runCatching {
                                            CloudAuthClient.requireMainlandPhone(phone)
                                        }.exceptionOrNull()?.let { e ->
                                            (e as? CloudException)?.error?.message
                                        }
                                    }
                                    if (phoneError != null) return@AuthTextLink
                                    sending = true
                                    scope.launch {
                                        try {
                                            challenge = authClient.sendCode(phone)
                                            secondsLeft = SEND_COOLDOWN_SECONDS
                                            toast = "验证码已发送，请查看短信"
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

                Spacer(Modifier.height(32.dp))

                AuthPrimaryButton(
                    text = if (submitting) "绑定中…" else "绑定并登录",
                    colors = colors,
                    loading = submitting,
                    enabled = phone.isNotEmpty() && code.isNotEmpty() && !submitting,
                    onClick = {
                        val current = challenge
                        if (current == null) {
                            toast = "请先获取验证码"
                            return@AuthPrimaryButton
                        }
                        if (code.isBlank()) {
                            toast = "请输入验证码"
                            return@AuthPrimaryButton
                        }
                        submitting = true
                        scope.launch {
                            try {
                                // 接缝提交：服务端落地前必然 NOT_FOUND。
                                // loginTicket 在正式链路由微信授权换发（见
                                // CloudAuthClient.signInWithWechatCode 的 KDoc），
                                // 调试预览没有票据，传空串即可——结果一样是未开通。
                                authClient.bindWechatPhone(
                                    loginTicket = "",
                                    challenge = current,
                                    code = code
                                )
                                toast = "绑定成功"
                                delay(600)
                                onBindSucceeded()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: CloudException) {
                                // 404（端点不存在）= 服务端尚未开通，单独给一句话，
                                // 比「云端没有找到对应数据」更贴近用户的处境。
                                toast = if (e.error.status == 404) {
                                    "微信登录暂未开通：服务端尚未支持"
                                } else {
                                    friendlyCloudMessage(e.error)
                                }
                            } catch (e: Exception) {
                                toast = "绑定失败：${e.message ?: "未知错误"}"
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.weight(1f))
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
