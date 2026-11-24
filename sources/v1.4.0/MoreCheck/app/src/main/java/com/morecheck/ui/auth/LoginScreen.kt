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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.WeChatAuthGateway
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 「发送验证码」成功后的冷却秒数（与云端口径一致：重发限频约 1 分钟）。 */
private const val SEND_COOLDOWN_SECONDS = 60

/**
 * 「找回密码」页成功设置密码、但自动登录失败时要带回登录页的预填手机号（2026-11-03）。
 *
 * ## 为什么是进程内对象而不是导航参数
 *
 * 预填发生在「找回密码 → 自动登录失败 → 退回登录页」这条路径上，而登录页的
 * 导航入口（`TabNavHost`）目前不接参数；改导航参数在本任务的文件白名单之外。
 * 这里复用项目已有的「进程内一次性传值」手法（同 `AvatarRevision`，见踩坑 5.10）：
 * 找回页写入、登录页组合时**取走并清空**。
 *
 * 值只是用户刚输入的 11 位手机号，不含凭据；即使没被取走（用户没回登录页），
 * 影响也仅限于下次进登录页时预填一个号码——不会造成错误登录。
 */
internal object LoginPhonePrefill {
    /** 待预填的 11 位手机号；null = 没有待处理值。 */
    var pending: String? = null
}

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
 * - **使用密码登录** / **忘记密码?**：v1.2.6 起两条都是真实链路（密码登录走
 *   `/v1/signin`，找回密码走 PASSWORD_RESET + `/v1/reset`），不是演示页。
 *
 * 「QQ」维持就地弹「功能开发中」的原口径；「微信」v1.2.4 起是**客户端先行的接缝**：
 * 点开是流程说明弹窗（授权 → 首次绑定手机号），当前状态（AppID 与服务端未落地）
 * 在弹窗里如实展示，另有调试入口直达绑定页（[onOpenWechatBind]）。
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
    /** 点击「忘记密码?」：进入「找回密码」页（真实链路：PASSWORD_RESET → /v1/reset）。 */
    onOpenForgotPassword: () -> Unit = {},
    /** 点击「使用密码登录」：进入密码登录第一步（只填手机号，真实密码登录）。 */
    onOpenPasswordLogin: () -> Unit = {},
    /** 点击微信说明弹窗里的「体验绑定流程（调试）」：进微信绑定手机号页（v1.2.4）。 */
    onOpenWechatBind: () -> Unit = {}
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    // 从「找回密码」页返回时预填手机号（2026-11-03，见 [LoginPhonePrefill] 的说明）。
    // 用 LaunchedEffect(Unit) 而不是初始化 rememberSaveable：组合时取走即清空，
    // 避免同一号码在之后任何一次进入登录页时都被预填。
    LaunchedEffect(Unit) {
        val pending = LoginPhonePrefill.pending ?: return@LaunchedEffect
        LoginPhonePrefill.pending = null
        // 只接受能还原成 11 位大陆手机号的值（防御性：写入方已经校验过）。
        CloudAuthClient.phoneDigits(pending)?.let { phone = it }
    }

    /** 手机号的内联错误（空 / 格式错各一句话）；一开始输入就清掉。 */
    var phoneError by remember { mutableStateOf<String?>(null) }

    var sending by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }

    // 「微信登录」说明弹窗（v1.2.4 接缝）：按钮不再弹一句"开发中"，
    // 而是把两步流程与当前可用状态一次讲清楚。
    var showWechatDialog by remember { mutableStateOf(false) }

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
                    // 2026-10-25 需求：标注这是**真实登录**（与注册页的「模拟注册」相对），
                    // 前缀放在最前面，用户一眼就能分辨两条链路的性质。
                    text = "真实登录 · 验证码登录后可同步打卡数据、记事本与游戏存档",
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
                        text = if (submitting) "登录中…" else "登录",
                        colors = colors,
                        loading = submitting,
                        enabled = phone.isNotEmpty() && code.isNotEmpty() && !submitting,
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
                                    val session = authClient.verifyCode(current, code)

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
                        // v1.2.4：打开微信登录说明弹窗（状态与流程，见弹窗本体）。
                        onClick = { showWechatDialog = true }
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

        if (showWechatDialog) {
            WeChatLoginDialog(
                colors = colors,
                onDismiss = { showWechatDialog = false },
                onStarted = { message -> showWechatDialog = false; toast = message },
                onOpenBindPreview = {
                    showWechatDialog = false
                    onOpenWechatBind()
                }
            )
        }
    }
}

/**
 * 「微信登录」说明弹窗（v1.2.4 接缝）。
 *
 * 把两步流程（微信授权 → 首次使用绑定手机号）与**当前真实状态**一次讲清：
 * [WeChatAuthGateway.status] 判定"现在到底能不能发起"，
 * 未开通就明说未开通——**不出现假按钮**（点"发起授权"只在 Ready 态出现）。
 *
 * 弹窗里的「体验绑定流程（调试）」是绑定页在服务端落地前的唯一可见入口：
 * 绑定页的发码是真的（真实短信），只有最后提交是接缝，会得到如实的
 * "暂未开通"提示。
 */
@Composable
private fun WeChatLoginDialog(
    colors: AuthColors,
    onDismiss: () -> Unit,
    /** 点「发起授权」后的结果反馈（成功发起不会回来；失败带原文）。 */
    onStarted: (String) -> Unit,
    onOpenBindPreview: () -> Unit
) {
    val context = LocalContext.current
    val status = remember { WeChatAuthGateway.status(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text("微信登录", color = colors.onPage, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "微信授权登录分两步：\n" +
                        "1. 拉起微信完成授权；\n" +
                        "2. 首次使用需绑定手机号——你的微信与该手机号共用同一份云数据。",
                    color = colors.onPage,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when (status) {
                        WeChatAuthGateway.Status.NotConfigured ->
                            "当前状态：暂未开通。客户端流程已就绪，等待微信开放平台资质" +
                                "与服务端支持；开通后无需更新此页即可直接发起。"
                        WeChatAuthGateway.Status.WeChatMissing ->
                            "本机未检测到微信，请安装微信后重试。"
                        WeChatAuthGateway.Status.Ready ->
                            "一切就绪，点击下方「发起授权」。"
                    },
                    color = colors.muted,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                // 服务端落地前绑定页的唯一可见入口（调试预览，绑定页自身有标注）。
                AuthTextLink(
                    text = "体验绑定流程（调试）",
                    color = colors.accent,
                    underline = true,
                    onClick = onOpenBindPreview
                )
            }
        },
        confirmButton = {
            if (status == WeChatAuthGateway.Status.Ready) {
                TextButton(onClick = {
                    try {
                        // AppID 就绪前不可达；接缝在 WeChatAuthGateway.launchAuth。
                        WeChatAuthGateway.launchAuth(context)
                    } catch (e: Exception) {
                        onStarted(e.message ?: "微信登录暂未开通")
                    }
                }) { Text("发起授权", color = colors.accent) }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了", color = colors.accent) }
            }
        },
        dismissButton = {
            if (status == WeChatAuthGateway.Status.Ready) {
                TextButton(onClick = onDismiss) { Text("取消", color = colors.muted) }
            }
        }
    )
}
