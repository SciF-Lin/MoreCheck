package com.morecheck.ui.auth

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.BuildConfig
import com.morecheck.ui.AppViewModelProvider

/** 协议文本上的注解标签。 */
private const val TAG = "agreement-link"

/**
 * 登录页（任务2，纯本地模拟）。
 *
 * **不主动弹出**：只由「我的 → 登录」这类调用方主动推入，见 [com.morecheck.ui.nav.TabNavHost]。
 *
 * 失败反馈有两层：
 * 1. 输入框区域整体横向抖动（[shakeOffset]）；
 * 2. 错误文案淡入显示在输入框下方，并同时弹出轻提示（由调用方负责）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onShowMessage: (String) -> Unit,
    viewModel: AuthViewModel
) {
    val isSubmitting by viewModel.isSubmitting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val failureTick by viewModel.failureTick.collectAsStateWithLifecycle()
    val loginSucceeded by viewModel.loginSucceeded.collectAsStateWithLifecycle()

    val haptic = LocalHapticFeedback.current

    var mode by remember { mutableStateOf(LoginMode.SMS) }
    var phone by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }

    // 输入框横向抖动：约 340ms 内往复衰减，位移 ±12dp。
    // 注意 Animatable 的 block 是「目标值计算器」而非逐帧回调，
    // 因此这里让 Animatable 在 1f → 0f 上跑，偏移量 = 幅度 × 当前值，
    // 真正的往复曲线由 ShakeEasing 提供。
    val shake = remember { Animatable(1f) }
    val shakeAmplitudePx = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(failureTick) {
        if (failureTick > 0) {
            // 轻提示：每次失败都给出（用 failureTick 而非 error，避免相同文案不再触发）。
            viewModel.error.value?.let(onShowMessage)

            // 输入框抖动。
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            shake.snapTo(1f)
            shake.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 340, easing = ShakeEasing)
            )
        }
    }

    // 仅当本次提交真正成功后才回退，避免页面打开即被弹回。
    LaunchedEffect(loginSucceeded) {
        if (BuildConfig.DIAG_BUILD) {
            Log.d("MoreCheckDiag", "LaunchedEffect(loginSucceeded=$loginSucceeded) 触发")
        }
        if (loginSucceeded) {
            if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "调用 onLoggedIn()")
            onLoggedIn()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = "欢迎回来",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未注册的手机号将自动创建账号（本地模拟）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            // 抖动作用域：只包住输入框，错误文案不跟着晃，避免难以阅读。
            Column(Modifier.graphicsLayer { translationX = shakeAmplitudePx * shake.value }) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = {
                        phone = it.filter(Char::isDigit).take(11)
                        viewModel.clearError()
                    },
                    label = { Text("手机号") },
                    placeholder = { Text("请输入 11 位手机号") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LoginMode.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = mode == item,
                            onClick = {
                                mode = item
                                credential = ""
                                viewModel.clearError()
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = LoginMode.entries.size
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(item.label, maxLines = 1)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = credential,
                    onValueChange = {
                        credential = when (mode) {
                            LoginMode.SMS -> it.filter(Char::isDigit).take(6)
                            LoginMode.PASSWORD -> it.take(20)
                        }
                        viewModel.clearError()
                    },
                    label = { Text(mode.credentialLabel) },
                    placeholder = {
                        Text(
                            when (mode) {
                                LoginMode.SMS -> "6 位数字验证码"
                                LoginMode.PASSWORD -> "演示密码 123456"
                            }
                        )
                    },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = if (mode == LoginMode.PASSWORD) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (mode) {
                            LoginMode.SMS -> KeyboardType.NumberPassword
                            LoginMode.PASSWORD -> KeyboardType.Password
                        },
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 错误文案淡入。
            AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // 用户协议勾选。
            // 用**单段可点击文本**而不是 TextButton：TextButton 自带最小高度与内边距，
            // 会造成复选框与文字错位、并把「和」推到很远（实测发现的排版问题）。
            val agreementText = buildAnnotatedString {
                append("我已阅读并同意")
                pushStringAnnotation(TAG, "agreement")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append("《用户协议》")
                }
                pop()
                append("和")
                pushStringAnnotation(TAG, "privacy")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                    append("《隐私政策》")
                }
                pop()
            }
            val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(
                    text = agreementText,
                    style = MaterialTheme.typography.bodySmall,
                    onTextLayout = { layoutResult.value = it },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures { position ->
                            val result = layoutResult.value ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            when (agreementText.getStringAnnotations(TAG, offset, offset).firstOrNull()?.item) {
                                "agreement" -> onShowMessage("《用户协议》将在后续任务实现")
                                "privacy" -> onShowMessage("《隐私政策》将在后续任务实现")
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.submit(mode = mode, phone = phone, credential = credential, agreed = agreed) },
                enabled = !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("登录中…")
                } else {
                    Text("登录")
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "本页为本地模拟：不发送真实短信、不请求后端。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 抖动的位移曲线：`sin(3.5πt) × (1 − t)`。
 *
 * - 起点与终点均为 0，保证抖动前后输入框回到原位；
 * - `sin` 在区间内往复 3.5 个半周期，形成左右摆动；
 * - `(1 − t)` 让振幅线性衰减，末尾自然收敛。
 */
private object ShakeEasing : androidx.compose.animation.core.Easing {
    override fun transform(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        return kotlin.math.sin(t * 3.5f * Math.PI).toFloat() * (1f - t)
    }
}
