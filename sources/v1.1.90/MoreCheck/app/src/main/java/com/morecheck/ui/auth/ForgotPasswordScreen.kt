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
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 「找回密码」页：手机号 + 短信验证码 → 下一步。
 *
 * **纯展示页，不提供实际功能**：点「下一步」只弹一句明确说明这是演示的提示，
 * 不改任何状态、不写 DataStore、不发任何请求。这是需求写死的。
 *
 * ## 为什么连「下一步」也什么都不做，而不是假装成功
 *
 * 找回密码的下一步本该是「重设新密码」，而本项目没有密码体系（密码登录本身也是
 * 任意密码都通过）。如果这里假装跳到重设密码页，用户改完密码回来发现旧密码照旧能用，
 * 那才是真的把界面做成了谎话。只弹一句「演示界面：未接入真实找回流程」，
 * 既满足了「界面展示」的要求，也不会让人对功能产生错误预期。
 *
 * ## 与登录页的一致性
 *
 * 「发送验证码」这一格**完全复用**登录页 / 注册页的那套 [rememberSendCodeState]
 * （点击 → 立刻起倒计时 + 转圈 → 转圈结束后才弹「已发送」），连开发者选项
 * 「验证码测试」的接线 [rememberDevCodeSender] 也一并接上。
 * 三页共用同一实现，才不会出现「登录页发码会通知、找回密码页发码没反应」这种错位。
 *
 * 配色全部取自 [rememberAuthColors]，因此本页自动跟随主题色与深浅色模式，
 * 不新增任何颜色常量。
 */
@Composable
fun ForgotPasswordScreen(onBack: () -> Unit) {
    val colors = rememberAuthColors()

    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }

    // 手机号为空时点「发送验证码」的内联错误（下划线 + 下方红字），一输入就清掉。
    // 与登录页、注册页同一口径：这里也刻意用内联提示而不是 toast。
    var phoneError by remember { mutableStateOf(false) }

    // 「验证码测试」的接线：开启时点「发送验证码」会真的生成六位码并发通知。
    // 未开启时它是空操作，本页行为与纯展示完全一致。
    val devCode = rememberDevCodeSender(onMessage = { toast = it })

    val sendState = rememberSendCodeState(
        onSent = {
            // 提示文案跟随「验证码测试」开关（与登录页同一句），
            // 关闭时如实说明演示、不会真的发出。
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

    val canSubmit = phone.isNotBlank() && code.isNotBlank()

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
                // 副标题解释「靠什么找回」：光看标题「找回密码」用户不知道要准备什么，
                // 先说明走短信验证码，下面两个输入框的存在就顺理成章了。
                Text(
                    text = "通过短信验证码找回密码",
                    color = colors.muted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(34.dp))

                AuthUnderlineField(
                    value = phone,
                    onValueChange = { input ->
                        phone = input.filter(Char::isDigit).take(11)
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

                // 验证码行：与登录页逐字节同构（输入在左 / 竖线 / 「发送验证码」在右，
                // 右侧那格用固定宽度 [SendCodeSlotWidth] 顶住，分界线不会随文案宽窄左右移动）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuthUnderlineField(
                        value = code,
                        onValueChange = { code = it.filterNot(Char::isWhitespace).take(6) },
                        placeholder = "验证码",
                        // 注册页用的也是信封图标；本页同样带「发送验证码」，
                        // 用一致的信封能让「这一格右边能发码」这件事在视觉上对上。
                        leadingIcon = Icons.Filled.MailOutline,
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
                                        phoneError = false
                                        // 「验证码测试」开启时才真正生成 + 通知（未开启是空操作）。
                                        devCode.send()
                                        sendState.onStart()
                                    } else {
                                        // 没填手机号就点发送：只报红、**不起倒计时**。
                                        // 否则用户白白被锁 60 秒，连补填的机会都没有
                                        // （与注册页同一处取舍）。
                                        phoneError = true
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(34.dp))

                AuthPrimaryButton(
                    text = "下一步",
                    colors = colors,
                    enabled = canSubmit,
                    onClick = {
                        // 需求：本页仅供界面展示，点「下一步」只弹提示，**不改任何状态**。
                        // 文案里明确写出「演示」，避免用户以为已经发出了找回请求而在等短信。
                        toast = "演示界面：未接入真实找回流程"
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
