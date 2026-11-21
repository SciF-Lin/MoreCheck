package com.morecheck.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.BuildConfig
import com.morecheck.data.AuthRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 登录方式。 */
enum class LoginMode(val label: String, val credentialLabel: String) {
    SMS(label = "验证码", credentialLabel = "验证码"),
    PASSWORD(label = "密码", credentialLabel = "密码")
}

/** 登录结果，用于把失败原因回传给 UI 做抖动与轻提示。 */
sealed interface LoginResult {
    data object Success : LoginResult
    data class Failure(val message: String) : LoginResult
}

/**
 * 登录流程的本地模拟实现（任务2）。
 *
 * 「模拟」的判定规则集中写在 [simulate] 里，**没有**任何网络或短信调用：
 * - 手机号必须为 11 位、以 1 开头；
 * - 验证码模式：任意 6 位数字均通过；
 * - 密码模式：固定密码 `123456` 通过，其余报「密码错误」；
 * - 以 `0` 结尾的手机号一律失败，用于稳定复现失败态。
 */
class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    /** 每次失败自增，UI 监听其变化来触发输入框抖动。 */
    private val _failureTick = MutableStateFlow(0)
    val failureTick: StateFlow<Int> = _failureTick.asStateFlow()

    /**
     * 是否刚刚登录成功。
     *
     * 刻意不复用 [isLoggedIn]：那个流由 DataStore 驱动、初值为 `false`，
     * 若用它在登录页做 `LaunchedEffect` 跳转，页面一打开就会立刻回退。
     * 这里用显式信号，只有真正 `submit` 成功才会置为 `true`。
     */
    private val _loginSucceeded = MutableStateFlow(false)
    val loginSucceeded: StateFlow<Boolean> = _loginSucceeded.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = authRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 已登录手机号的脱敏展示，如 `138****8888`；未登录为 null。 */
    val maskedPhone: StateFlow<String?> = authRepository.phone
        .map { phone -> phone?.let(::maskPhone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearError() {
        _error.value = null
    }

    fun submit(mode: LoginMode, phone: String, credential: String, agreed: Boolean) {
        // 诊断构建才输出，正式包此处为空操作。
        if (BuildConfig.DIAG_BUILD) {
            Log.d("MoreCheckDiag", "submit 被调用 mode=$mode phone=$phone credLen=${credential.length} agreed=$agreed isSubmitting=${_isSubmitting.value}")
        }

        if (_isSubmitting.value) return
        _error.value = null

        // 协议校验放在请求之前，且不进入「登录中」状态。
        if (!agreed) {
            _error.value = "请先阅读并同意用户协议和隐私政策"
            _failureTick.value += 1
            return
        }

        _isSubmitting.value = true

        viewModelScope.launch {
            // 模拟一次请求往返，让「登录中」状态可见。
            delay(250)

            when (val result = simulate(mode, phone, credential)) {
                is LoginResult.Success -> {
                    authRepository.login(phone = phone, mode = mode.name.lowercase())
                    _error.value = null
                    _loginSucceeded.value = true
                    if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "模拟登录成功 -> loginSucceeded=true")
                }

                is LoginResult.Failure -> {
                    _error.value = result.message
                    _failureTick.value += 1
                    if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "模拟登录失败 -> ${result.message}")
                }
            }
            _isSubmitting.value = false
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    private fun simulate(mode: LoginMode, phone: String, credential: String): LoginResult {
        if (!phone.matches(Regex("^1\\d{10}$"))) {
            return LoginResult.Failure("请输入 11 位手机号")
        }
        if (phone.endsWith("0")) {
            return LoginResult.Failure("该手机号登录失败，请稍后重试")
        }
        return when (mode) {
            LoginMode.SMS -> {
                if (credential.length != 6 || !credential.all(Char::isDigit)) {
                    LoginResult.Failure("验证码为 6 位数字")
                } else {
                    LoginResult.Success
                }
            }

            LoginMode.PASSWORD -> {
                if (credential.isEmpty()) {
                    LoginResult.Failure("请输入密码")
                } else if (credential != DEMO_PASSWORD) {
                    LoginResult.Failure("密码错误")
                } else {
                    LoginResult.Success
                }
            }
        }
    }

    companion object {
        /** 本地模拟用的固定密码。 */
        const val DEMO_PASSWORD = "123456"
    }
}

/** 手机号脱敏：保留前 3 位与后 4 位。 */
internal fun maskPhone(phone: String): String =
    if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone
