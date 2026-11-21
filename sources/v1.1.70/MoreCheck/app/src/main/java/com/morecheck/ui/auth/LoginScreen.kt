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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.R
import com.morecheck.ui.AppViewModelProvider
import kotlinx.coroutines.delay

/**
 * 登录页。
 *
 * 版式照参考图，**配色全部由主题推导**（见 [rememberAuthColors]）：
 * 底色跟随深色模式开关，按钮与链接跟随主题色。
 *
 * 表单是「手机号 + 验证码」，走的是**纯 UI 校验**：
 * 任意手机号 + 任意验证码都能登录（校验规则见 [AuthViewModel.simulate]），
 * 页面自己只保证两个框非空。这是需求明确要求的，别再加格式校验。
 *
 * 五处刻意的取舍：
 * - 验证码用 `KeyboardType.Number` 而非 `NumberPassword`：验证码本来就要肉眼核对，
 *   遮蔽只会让人打错。注册页用的是同一套写法，两页保持一致。
 * - 「发送验证码」是**纯前端倒计时**，不发短信，与注册页共用 [rememberSendCodeState]。
 * - 「登录」成功与否看 [AuthViewModel.loginSucceeded]，**绝不能用 isLoggedIn**：
 *   后者由 DataStore 驱动、初值为 false，页面内有任何一次重组都会把它采成 false 而立刻回退。
 * - 「忘记密码?」**v1.1.65 起进入真实的「找回密码」页**（[onOpenForgotPassword] →
 *   `TabDestination.ForgotPassword`，通过短信验证码找回，**仅界面演示**）。
 *   此前它进的是 `PlaceholderEntry.FORGOT_PASSWORD` 那张留空页，
 *   那个占位枚举项已随之删除；本页的 `onOpenPlaceholder` 参数也一并删掉——
 *   它当时是**为「忘记密码」而加**的，没有别的调用方，留着就是死参数。
 * - 底部的「微信」「QQ」**不再进二级页**，点一下只就地弹一句「功能开发中」
 *   （需求：删除二级界面，改为消息提示）。提示复用本页已有的 `toast` + [AuthToast]，
 *   不新增全局消息通道，因此无需改 `TabNavHost.kt`。
 *
 * ## 两个新接线点都带默认值，且都排在 `authViewModel` 之前
 *
 * [onOpenForgotPassword] 与 [onOpenPasswordLogin] 都是「后来新增的接线点」：
 * 带默认值 `{}` 可让既有调用方（以及 preview / 测试）无需立刻跟着改。
 * 位置也必须在这里——带默认值的参数后面不能再跟一个不带默认值的参数，
 * 而 `authViewModel` 也不能被挤到更前面（既有调用方按具名传它，动它就等于改签名）。
 */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onOpenRegister: () -> Unit,
    /** 点击「忘记密码?」：进入「找回密码」页（v1.1.65 起是真实页面）。 */
    onOpenForgotPassword: () -> Unit = {},
    /** 点击「使用密码登录」：进入密码登录第一步（只填手机号）。 */
    onOpenPasswordLogin: () -> Unit = {},
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val colors = rememberAuthColors()

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    val isSubmitting by authViewModel.isSubmitting.collectAsStateWithLifecycle()
    val error by authViewModel.error.collectAsStateWithLifecycle()
    val loginSucceeded by authViewModel.loginSucceeded.collectAsStateWithLifecycle()

    // 「发送验证码」与注册页共用同一套状态机（转圈时长抖动 + 转圈结束后才弹提示），
    // 需求要求两页这里完全一致。
    // 手机号为空时点「发送验证码」的内联错误提示；一输入就清掉。
    var phoneError by remember { mutableStateOf(false) }

    // 开发者选项「验证码测试」的接线：开启时点「发送验证码」会生成六位码、记为后端记录并发通知。
    // 与注册页共用同一实现（`AuthComponents.rememberDevCodeSender`），两页不会走样。
    //
    // 未开启时 [DevCodeSender.send] 是空操作，本页行为与从前完全一致。
    val devCode = rememberDevCodeSender(onMessage = { toast = it })

    val sendState = rememberSendCodeState(
        onSent = {
            // 「已发送」这句话要跟着验证码测试的开关变：
            // 关闭时（原行为）如实说明是演示、不会真的发出；
            // 开启时验证码真的以通知形式送到了，让用户去通知栏拿就去通知栏拿。
            toast = if (devCode.enabled) {
                "验证码已发送，请查看通知栏"
            } else {
                "验证码已发送（演示，不会真的发出）"
            }
        }
    )


    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    // 登录成功：先提示，再退回上一页（「我的」页此时已能读到登录态）。
    // 用 loginSucceeded 这个显式信号，而不是 DataStore 驱动的 isLoggedIn，原因见类注释。
    LaunchedEffect(loginSucceeded) {
        if (loginSucceeded) {
            toast = "登录成功"
            delay(600)
            onBack()
        }
    }

    // 失败提示也要走 toast，用完立刻清掉，避免下次回到本页又弹一次。
    LaunchedEffect(error) {
        error?.let {
            toast = it
            authViewModel.clearError()
        }
    }

    val canSubmit = phone.isNotBlank() && code.isNotBlank() && !isSubmitting

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
                Spacer(Modifier.height(40.dp))

                AuthUnderlineField(
                    value = phone,
                    // 只允许数字：手机号就是纯数字，顺手挡掉字母与符号（与注册页同一口径），
                    // 但仍然不校验号段——需求允许任意手机号通过。
                    onValueChange = { input ->
                        phone = input.filter(Char::isDigit).take(11)
                        // 一旦开始输入就清掉内联错误，避免「改了还是红」。
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
                    // 「发送验证码」这一格用固定宽度：转圈、原文、倒计时三种形态都居中在这里面。
                    //
                    // 不加固定宽度时，这格的宽度随内容变（转圈 16dp / 「发送验证码」5 字 /
                    // 「57s 后重发」更宽），左侧验证码输入框是 weight(1f) 吃剩余空间，
                    // 于是分界线会跟着左右移动——正是用户反馈的「分界线位置变了」。
                    Box(
                        Modifier.widthIn(min = SendCodeSlotWidth),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (sendState.sending) {
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
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
                                    if (phone.isNotEmpty()) {
                                        // 倒计时与转圈一起启动；提示由状态机在转圈结束后回调。
                                        phoneError = false
                                        // 「验证码测试」开启时才真正生成 + 通知（未开启是空操作）。
                                        devCode.send()
                                        sendState.onStart()
                                    } else {
                                        // 内联红色错误态（下划线 + 下方红字），不再弹 toast：
                                        // toast 会盖在键盘上还马上消失，内联提示能一直指着输入框。
                                        phoneError = true
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
                        text = if (isSubmitting) "登录中…" else "登录",
                        colors = colors,
                        enabled = canSubmit,
                        // 登录页没有协议勾选框，因此固定传 agreed = true；
                        // 否则 submit 会在协议校验处直接返回，永远登不上。
                        onClick = {
                            authViewModel.submit(
                                mode = LoginMode.SMS,
                                phone = phone,
                                credential = code,
                                agreed = true
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // 「使用密码登录」与「忘记密码?」并排一行：前者靠左、后者靠右。
                //
                // 为什么并排而不是上下两行：两个都是「换一条路登录 / 找回」的次级入口，
                // 分成两行会让表单下方堆积三层链接，主按钮的视觉终点被拉散；
                // 而这两件事本来就是一对（一个是「用别的凭证登」、一个是「登不上怎么办」），
                // 摆在同一行、各占一端，用户扫一眼就知道「下面这一栏是备选方案」。
                // 顺序上「使用密码登录」在左：它是正向操作，先读到；
                // 「忘记密码?」在右，是异常路径的兜底。
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
                        // v1.1.65：改用真实的「找回密码」页（通过短信验证码，仅界面演示）。
                        // 此前走 `PlaceholderEntry.FORGOT_PASSWORD` 那张留空页，
                        // 那个占位枚举项已随之删除（不留死枚举）。
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
                        // 微信登录不再进留空二级页，改为就地弹一句提示（需求：删除二级界面，改为消息提示）。
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
