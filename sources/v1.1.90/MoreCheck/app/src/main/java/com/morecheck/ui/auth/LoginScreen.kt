package com.morecheck.ui.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「发送验证码」成功后的冷却秒数（与云端口径一致：重发限频约 1 分钟）。 */
private const val SEND_COOLDOWN_SECONDS = 60

/** 注册时登录密码的最短长度。与服务端约束对齐，避免提交后才被拒。 */
private const val MIN_PASSWORD_LENGTH = 6

/**
 * 登录页（**真实云登录**，v1.1.80 起）。
 *
 * ## 这一页现在是真的
 *
 * 版式与改造前完全一致（配色全部由主题推导，见 [rememberAuthColors]），但验证码链路
 * 已从「纯 UI 校验的本地演示」升级为**真实云登录**：
 * - 「发送验证码」走 `CloudAuthClient.sendCode` —— 云平台代发**真实短信**；
 * - 「登录」走 `verifyCode` —— 老用户登录 / 新用户自动注册，同一个按钮；
 * - 登录成功经 `AuthRepository.onCloudLoginSucceeded` 落地本地登录态，
 *   「我的」页与「个人信息」页随即显示已登录。
 *
 * ## 三个占位入口保持占位（需求原话：保持占位作用）
 *
 * - **注册**：真实云登录**自动注册**（新手机号发验证码即可登录 = 注册），独立的注册页
 *   保留为界面演示（[onOpenRegister]）；
 * - **使用密码登录** / **忘记密码?**：仍进既有的密码登录两步页与找回密码页（仅界面演示）。
 *
 * 「微信」「QQ」也维持就地弹「功能开发中」的原口径。
 *
 * ## 与开发者选项「验证码测试」的关系
 *
 * 那个开关生成的是**本地六位码**（经通知送达），服务端从未见过它——对真实云登录
 * 没有意义，本页不再接入。若短信因限额 / 网络问题收不到，服务端的原文错误会经
 * [friendlyCloudMessage] 如实展示在 toast 上。
 *
 * ## 「发送验证码」的状态机
 *
 * - [sending]：`sendCode` 网络往返中（转圈）。**倒计时在它成功后才开始**——
 *   请求可能失败，失败了还倒计时会让用户以为码已发出；
 * - [submitting]：`verifyCode` 往返中（按钮转圈）。错码可重试且**复用同一个
 *   [OtpChallenge]**（服务端的验证会话在成功或过期前一直有效）。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onOpenRegister: () -> Unit,
    /** 点击「忘记密码?」：进入「找回密码」页（界面演示）。 */
    onOpenForgotPassword: () -> Unit = {},
    /** 点击「使用密码登录」：进入密码登录第一步（只填手机号，界面演示）。 */
    onOpenPasswordLogin: () -> Unit = {}
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    /** 手机号的内联错误（空 / 格式错各一句话）；一开始输入就清掉。 */
    var phoneError by remember { mutableStateOf<String?>(null) }

    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }

    // 新号码要顺手设置登录密码（v1.1.91）。
    //
    // 云端「注册」与「登录」是同一个验证码入口：`isExistingUser == false` 时，
    // 提交验证码会在服务端**创建账号**。改造前这条路径不设密码，账号从此只能收短信
    // 才能登录；现在把密码框就地补在这一屏（而不是把用户推去注册页重发一次短信，
    // 那要多等一分钟的发送冷却，还会白花一条短信）。
    var password by rememberSaveable { mutableStateOf("") }
    // 是否已确定「这是新号码」。challenge 一到手就有答案。
    val isNewUser = challenge?.isExistingUser == false

    // 冷却倒计时：到 0 自动停。
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    // toast 1.6 秒后自动消失（与改造前同一时长）。
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
        // 右上角那团柔和的主题色光晕（参考图右上角的装饰圆）。
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
                    text = "登录",
                    color = colors.onPage,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "验证码登录后可同步打卡数据、记事本与游戏存档",
                    color = colors.muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(32.dp))

                AuthUnderlineField(
                    value = phone,
                    // 只允许数字、最多 11 位（与注册页同一口径）。
                    onValueChange = { input ->
                        phone = input.filter(Char::isDigit).take(11)
                        // 一旦开始输入就清掉内联错误，避免「改了还是红」。
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

                Spacer(Modifier.height(26.dp))

                // 验证码：输入在左，「发送验证码」在右（与注册页同版式）。
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
                    // 「发送验证码」这一格用固定宽度：转圈、原文、倒计时三种形态都居中在这里面，
                    // 分界线不随内容左右移动（理由同改造前）。
                    Box(
                        Modifier.widthIn(min = SendCodeSlotWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
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
                                    // 先做本地校验：空号 / 格式错都走内联红态，不白发请求
                                    // （格式校验复用 CloudAuthClient 的同一实现，两处口径不会漂移）。
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
                                            // 只有**真的发出去了**才开始冷却。
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

                Spacer(Modifier.height(34.dp))

                // 新号码：就地设置登录密码（理由见上面 isNewUser 的注释）。
                // 老号码不显示——他们的密码是注册时设的，这里多一个空密码框只会让人以为要改密码。
                if (isNewUser) {
                    AuthUnderlineField(
                        value = password,
                        onValueChange = { password = it.take(32) },
                        placeholder = "设置登录密码（至少 6 位）",
                        leadingIcon = Icons.Filled.Lock,
                        colors = colors,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "该手机号还没有账号，验证后将自动注册；设置密码后可用密码登录",
                        color = colors.muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(24.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AuthSecondaryButton(
                        text = "注册",
                        colors = colors,
                        onClick = onOpenRegister,
                        modifier = Modifier.weight(1f)
                    )
                    AuthPrimaryButton(
                        // 2026-10-22 需求 15：加载态由 `loading` 驱动（转圈 + 「登录中…」），
                        // 不再只靠"把按钮置灰 + 换文案"——那样点下去看不出在转。
                        text = if (submitting) "登录中…" else if (isNewUser) "注册并登录" else "登录",
                        colors = colors,
                        loading = submitting,
                        enabled = phone.isNotEmpty() && code.isNotEmpty() && !submitting &&
                            (!isNewUser || password.length >= MIN_PASSWORD_LENGTH),
                        // 登录页没有协议勾选框，云端登录以登录行为本身视为同意。
                        onClick = {
                            val current = challenge
                            if (current == null) {
                                // 没取过验证码（或页面被重建过）：引导先取码，
                                // 而不是提交一个服务端不认识的空会话。
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
                                    // 新号码把密码一起交给服务端注册；老号码传 null（改密码要走旧密码校验）。
                                    val session = authClient.verifyCode(
                                        current,
                                        code,
                                        password = if (current.isExistingUser) null else password
                                    )

                                    // 云登录落地到本地登录态（"我的"页头像卡的已登录状态与
                                    // 手机号、个人信息页都读本地 AuthRepository）。
                                    // 昵称取云端资料（me()），失败不影响登录本身。
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

                                    toast = "登录成功"
                                    // 先提示再退：回退后「我的」页已能读到新登录态，
                                    // 首次登录策略弹窗（根骨架托管）会接住后续流程。
                                    delay(600)
                                    onBack()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: CloudException) {
                                    // 错码可重试：challenge 仍然有效，倒计时也不重置
                                    // （服务端的验证会话没被这次失败消耗掉）。
                                    toast = friendlyCloudMessage(e.error)
                                } catch (e: Exception) {
                                    toast = "登录失败：${e.message ?: "未知错误"}"
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 「使用密码登录」与「忘记密码?」并排一行（占位入口，保持原口径）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    AuthTextLink(
                        text = "使用密码登录",
                        color = colors.accent,
                        underline = true,
                        onClick = onOpenPasswordLogin
                    )
                    AuthTextLink(
                        text = "忘记密码?",
                        color = colors.accent,
                        underline = true,
                        onClick = onOpenForgotPassword
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "其它登录方式", color = colors.muted, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    AuthSocialButton(
                        label = "微信",
                        colors = colors,
                        // 微信登录不再进留空二级页，改为就地弹一句提示（原口径）。
                        onClick = { toast = "微信登录功能开发中" }
                    ) {
                        // 素材本身就是「绿底圆角方块 + 白气泡」，裁成圆形即可，颜色沿用素材自带的品牌绿。
                        Image(
                            painter = painterResource(R.drawable.ic_social_wechat),
                            contentDescription = null,
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    AuthSocialButton(
                        label = "QQ",
                        colors = colors,
                        // 与微信一致：只弹提示，不进二级页。
                        onClick = { toast = "QQ 登录功能开发中" }
                    ) {
                        // 素材是透明底企鹅，先铺一层品牌蓝底，再把企鹅叠上去。
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(SocialBrand.Qq)
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_social_qq),
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
