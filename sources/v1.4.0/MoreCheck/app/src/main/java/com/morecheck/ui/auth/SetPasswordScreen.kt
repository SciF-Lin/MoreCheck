package com.morecheck.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
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
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「登录密码」页：修改**当前已登录**账号的密码。
 *
 * 链路：`CloudAuthClient.updateUserPassword(oldPassword, newPassword)`——官方 SDK
 * `resetPasswordForOld` 同款两步：`/v1/user/sudo` 用旧密码换一次性凭据 →
 * `PATCH /v1/user/password` 提交新密码。修改成功当前会话保持有效
 * （密码只影响下一次登录）。
 *
 * ⚠️ sudo 强制验证旧密码：**从未设过密码的账号在这里走不通**（实测传任何旧值
 * 都报 `invalid_password`）——这类用户请退出登录后走登录页「忘记密码」，
 * 用验证码设置首个密码（`/v1/reset` 是唯一免旧密码的端点）。旧密码输错时
 * 界面会如实说明这一点。
 *
 * 入口在「我的信息 → 账号与同步 → 登录密码」，仅登录后可见。
 */
@Composable
fun SetPasswordScreen(
    onBack: () -> Unit,
    /** 修改成功后由本页在提示结束后调用（调用方回退上一页）。 */
    onSaved: () -> Unit
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember(context) { context.appContainer.cloudAuthClient }

    var oldPassword by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            delay(1100)
            onSaved()
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2200)
            toast = null
        }
    }

    val canSubmit = oldPassword.isNotBlank() && password.isNotBlank() &&
        confirm.isNotBlank() && !submitting

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
                    text = "登录密码",
                    color = colors.onPage,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "验证旧密码后设置新密码（6–72 位）",
                    color = colors.muted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(34.dp))

                AuthUnderlineField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it.take(CloudAuthClient.PASSWORD_MAX_LENGTH) },
                    placeholder = "旧密码（从未设置过请用「忘记密码」）",
                    leadingIcon = Icons.Filled.Lock,
                    colors = colors,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(22.dp))

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
                    text = if (submitting) "保存中…" else "保存",
                    colors = colors,
                    enabled = canSubmit,
                    loading = submitting,
                    onClick = {
                        when {
                            password.length < CloudAuthClient.PASSWORD_MIN_LENGTH ->
                                toast = "密码长度需至少 ${CloudAuthClient.PASSWORD_MIN_LENGTH} 位"
                            password != confirm -> toast = "两次输入的密码不一致"
                            else -> {
                                submitting = true
                                scope.launch {
                                    try {
                                        authClient.updateUserPassword(oldPassword, password)
                                        saved = true
                                        toast = "密码已保存"
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: CloudException) {
                                        // sudo 对旧密码校验失败（含「从未设过密码」的账号，
                                        // 实测同样报 invalid_password）：给出可行动的出路。
                                        toast = if (e.error.code == "invalid_password") {
                                            "旧密码不正确；若从未设置过密码，" +
                                                "请退出登录后用「忘记密码」通过验证码设置"
                                        } else {
                                            friendlyCloudMessage(e.error)
                                        }
                                    } catch (e: Exception) {
                                        toast = "保存失败：${e.message ?: "未知错误"}"
                                    } finally {
                                        submitting = false
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
