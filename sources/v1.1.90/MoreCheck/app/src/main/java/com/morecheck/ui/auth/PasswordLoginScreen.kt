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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「密码登录」第一步：只填手机号。
 *
 * ## 为什么不复用 [LoginScreen]
 *
 * 两者的**版式**同族（大标题 + 下划线输入框 + 通栏主按钮），但表单内容与底部动作完全不同：
 * 这里是「手机号 + 登录按钮 + 找回密码」，登录页是「手机号 + 验证码 / 注册 / 第三方登录」。
 * 想靠参数把两者合并，就需要给登录页加一堆 `showCode/showSocial/showRegister` 开关，
 * 每个开关都让「登录页现在到底长什么样」更难读——两个各自 200 行的页面合成 400 行的
 * 条件分支页，收益是负的。复用的是**更细一级的件**（[AuthUnderlineField] /
 * [AuthPrimaryButton] / [AuthTextLink] / [AuthBackButton] / [rememberAuthColors]），
 * 所以视觉与交互仍然不会走样。
 *
 * ## 配色
 *
 * 一律走 [rememberAuthColors]，它是从 `MaterialTheme.colorScheme` 推导出来的，
 * 因此本页**自动**跟随「设置 → 主题色」与深浅色模式，不新增任何颜色常量。
 *
 * ## 与登录页刻意保持一致的两处细节
 *
 * - 右上角那团主题色光晕：登录页有，本页也照画。少了它，两步之间来回切换时
 *   背景会「闪一下」——两页明暗明显不同，切页看起来像换了个 App。
 * - 手机号只允许数字、最多 11 位（与登录页 / 注册页同一口径）。**不校验号段**：
 *   需求明确「任意手机号都能进」，本项目整体是本地原型，格式校验只会挡住演示。
 */
@Composable
fun PasswordLoginScreen(
    onBack: () -> Unit,
    onOpenForgotPassword: () -> Unit,
    onPhoneSubmitted: (String) -> Unit,
) {
    val colors = rememberAuthColors()

    // 手机号用 rememberSaveable：本页是「第一步」，用户跳去第二步又点返回时会重建页面，
    // 号码被清空会显得像操作被丢弃。验证码之类的短命状态才用 remember。
    var phone by rememberSaveable { mutableStateOf("") }

    // 空手机号点「登录」时的内联错误（下划线变红 + 下方红字）。
    // 刻意用 remember 而不是 rememberSaveable：它是**一次性的提示**，
    // 页面重建后还留着红框反而莫名其妙。
    var phoneError by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.page)
    ) {
        // 右上角的柔和主题色光晕（与登录页同一套画法，见类注释）。
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
                    text = "密码登录",
                    color = colors.onPage,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(40.dp))

                AuthUnderlineField(
                    value = phone,
                    onValueChange = { input ->
                        phone = input.filter(Char::isDigit).take(11)
                        // 一开始输入就清掉内联错误：错误提示必须能随输入立刻消失，
                        // 否则用户改对了还看着红框，会以为改错了。
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
                    // Next 而不是 Done：这一屏的唯一「下一步」是下面那个按钮，
                    // 键盘上的回车如果直接提交，用户就没机会核对刚打完的号码。
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(26.dp))

                // 「通过验证码登录」= 回上一屏（就是验证码登录），所以直接接 onBack。
                // 不做成独立路由：对用户来说这就是「原路返回」，走返回栈才能保证
                // 从密码登录进来时是退回验证码登录、从别处进来时退回别处，一步不乱。
                AuthTextLink(
                    text = "通过验证码登录",
                    color = colors.accent,
                    onClick = onBack
                )

                Spacer(Modifier.height(34.dp))

                AuthPrimaryButton(
                    text = "登录",
                    colors = colors,
                    enabled = phone.isNotBlank(),
                    onClick = {
                        // 需求要求空手机号时**内联报错**而不是弹 toast：
                        // toast 盖在键盘上又马上消失，用户来不及看清哪里错了；
                        // 内联提示能一直指着出问题的那个输入框。
                        if (phone.isBlank()) {
                            phoneError = true
                        } else {
                            phoneError = false
                            onPhoneSubmitted(phone)
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
    }
}
