package com.morecheck.ui.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.BuildConfig
import com.morecheck.data.AuthRepository
import com.morecheck.data.DeveloperOptionsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

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
 * 「模拟」的判定规则集中写在 [simulate] 里，**没有**任何网络或短信调用。
 *
 * 规则刻意放到最松：用户要求「任意手机号 + 任意验证码都能进入，仅 UI 校验」，
 * 因此这里**只兜底「非空」**，不再校验手机号位数，也不再要求验证码是 6 位数字；
 * 格式与长度都由登录页的输入框自己决定。
 * 密码模式（当前无页面使用）保留固定密码 `123456` 的旧行为，改动范围最小。
 *
 * ## 唯一的例外：开发者选项 →「验证码测试」
 *
 * 当 [developerOptions] 里「总开关 && 验证码测试」都打开时，验证码不再放行任意值，
 * 而是必须与「发送验证码」时生成的六位数字一致（校验规则见 [DeveloperOptionsRepository.verifyAndConsume]）。
 * 这是刻意**保留**在模拟登录之上的一层真实校验：需求要的就是「模拟真实填写验证码的场景」，
 * 而关掉这个开关时必须完全回到原来的宽松行为，一秒都不该多拦。
 */
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val developerOptions: DeveloperOptionsRepository
) : ViewModel() {

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

    /** 用户 ID（注册时生成的 8 位数字）；未注册为 null。 */
    val userId: StateFlow<String?> = authRepository.userId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 注册时填写的昵称；未注册为 null。 */
    val nickname: StateFlow<String?> = authRepository.nickname
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 注册时选择的性别（`male` / `female`）；未注册为 null。 */
    val gender: StateFlow<String?> = authRepository.gender
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 是否正在注册（注册按钮的转圈态）。
     *
     * 与 [isSubmitting] 分开：登录的转圈只有 250ms，注册要 3~5 秒，
     * 而两者可能在同一个 ViewModel 上先后发生，共用一个标志会让登录的短暂转圈
     * 把注册按钮也点亮。
     */
    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering.asStateFlow()

    /** 注册成功信号：置 `true` 后由注册页负责跳转 / 提示，再由页面调用 [consumeRegisterSuccess] 复位。 */
    private val _registerSucceeded = MutableStateFlow(false)
    val registerSucceeded: StateFlow<Boolean> = _registerSucceeded.asStateFlow()

    fun consumeRegisterSuccess() {
        _registerSucceeded.value = false
    }

    /**
     * 注册（本地模拟）。
     *
     * 流程：先校验（含开发者选项「验证码测试」的一致性校验），再转圈 3~5 秒模拟请求往返，
     * 成功后才落盘并置位成功信号。
     * 时长取 `3000 + [0,2000)` 的随机值，落在需求要求的 3~5 秒区间内；
     * 固定值会让每次注册都像同一个动画，随机一点更像真实网络的往返差异。
     *
     * 校验（空值 / 昵称字符集 / 性别未选）由注册页负责，因为那些是**输入态**的问题，
     * 页面要就近把错误标在对应输入框上；这里只做最后一道兜底。
     *
     * @param credential 用户填写的验证码。仅在「验证码测试」开启时参与校验，
     *   关闭时**完全不看**（保持原来的宽松行为）。
     */
    fun register(phone: String, nickname: String, gender: String, credential: String) {
        if (_isRegistering.value) return
        _isRegistering.value = true
        _error.value = null

        viewModelScope.launch {
            // ⚠️ 校验放在 3~5 秒转圈**之前**：
            // 验证码错了还要白等 5 秒才被告知，是最让人恼火的一种交互。
            // 成功路径的时序完全不变（校验通过后照旧转圈、照旧落盘）。
            val codeFailure = verifyDevCodeOrNull(credential)
            if (codeFailure != null) {
                _error.value = codeFailure
                _failureTick.value += 1
                _isRegistering.value = false
                if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "注册被验证码测试拦下 -> $codeFailure")
                return@launch
            }

            delay(3000L + Random.nextLong(2000L))

            authRepository.register(
                phone = phone,
                userId = AuthRepository.newUserId(),
                nickname = nickname,
                gender = gender
            )
            _isRegistering.value = false
            _registerSucceeded.value = true
            if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "注册成功 -> registerSucceeded=true")
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * 复位「刚刚登录成功」这个一次性信号。
     *
     * 为什么需要：一个 AuthViewModel 实例会被登录页、注册页、密码登录页**共享**
     * （三页都用 `AppViewModelProvider.Factory` 取同一个实例），而 [loginSucceeded]
     * 一旦置 true 就**不会再自己变回 false**。页面用
     * `LaunchedEffect(loginSucceeded) { if (loginSucceeded) ... }` 监听它，
     * 于是「第一次进入一个已经把信号置亮的页面」时，effect 的键一开始就是 true，
     * 会在组合的第一帧立刻触发——表现就是刚进页面就弹「登录成功」并被自动跳走。
     *
     * 所以成功信号必须被**消费掉**：页面提示完、跳转前调用本方法。
     */
    fun resetLoginSuccess() {
        _loginSucceeded.value = false
    }

    /**
     * 「密码登录」演示：**任意密码都算成功**。
     *
     * ## 为什么不复用 [submit] + `LoginMode.PASSWORD`
     *
     * [simulate] 里 PASSWORD 分支的规则是「密码必须等于 [DEMO_PASSWORD]」，
     * 这与本轮的硬性需求「输入任何密码都提示登录成功」**直接冲突**：
     * 复用的话用户随手输一个密码就会看到「密码错误」，而参考界面里根本没有这条路径。
     * 与其改 `simulate`（那条规则仍是既有登录逻辑的一部分，动它会波及别处），
     * 不如单开一个语义明确的演示入口。
     *
     * ## 为什么还要写 `authRepository.login`
     *
     * 只置成功信号不落盘的话，「我的」页读到的仍是未登录——用户刚被告知「登录成功」，
     * 退回去却还是「未登录」，界面自相矛盾。所以本地登录态必须写。
     * `mode` 传 `"password"`：与 [submit] 里 `mode.name.lowercase()` 的写法保持一致，
     * 让「我的」页等既有读取方无需区分是哪个入口写进去的。
     *
     * ## 时序
     *
     * 打开 [isSubmitting] → `delay` 约 900ms（让按钮上的「登录中…」真的可见，
     * 而不是闪一帧；需求要求「1 秒左右」）→ 写入登录态 → 关闭 [isSubmitting]
     * → 置 [loginSucceeded]。先关转圈再置信号，是为了保证成功提示出现时
     * 按钮已经回到常态，不会出现「圈还在转 + 已经提示成功」的自相矛盾画面。
     *
     * 开头把 [loginSucceeded] 复位，配合 [resetLoginSuccess] 一起用：
     * 这样每次成功都必然是一次 `false → true` 的**真实跳变**，
     * 页面的 `LaunchedEffect` 一定会被触发，不存在「信号已经是 true 所以这次不弹」的哑火。
     */
    fun submitPasswordDemo(phone: String) {
        // 连点保护：转圈期间重复点击不应叠加多次落盘。
        if (_isSubmitting.value) return
        _loginSucceeded.value = false
        _error.value = null
        _isSubmitting.value = true

        viewModelScope.launch {
            if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "密码登录演示 submitPasswordDemo phone=$phone")
            delay(PASSWORD_DEMO_MS)

            authRepository.login(phone = phone, mode = "password")
            _isSubmitting.value = false
            _loginSucceeded.value = true
            if (BuildConfig.DIAG_BUILD) Log.d("MoreCheckDiag", "密码登录演示成功 -> loginSucceeded=true")
        }
    }

    fun submit(mode: LoginMode, phone: String, credential: String, agreed: Boolean) {
        // 诊断构建才输出，正式包此处为空操作。
        if (BuildConfig.DIAG_BUILD) {
            Log.d("MoreCheckDiag", "submit 被调用 mode=$mode phone=$phone credLen=${credential.length} agreed=$agreed isSubmitting=${_isSubmitting.value}")
        }

        if (_isSubmitting.value) return
        _error.value = null
        // 与 [submitPasswordDemo] 同一处防哑火处理：成功信号必须先落回 false，
        // 本次成功才是一次真实跳变，页面的 `LaunchedEffect(loginSucceeded)` 必然被触发。
        // 少了这一句，「登录页 → 密码登录页 → 返回登录页再验证码登录成功」这种
        // 共用同一个 ViewModel 的路径就可能不弹提示。
        _loginSucceeded.value = false

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

    private suspend fun simulate(mode: LoginMode, phone: String, credential: String): LoginResult {
        // 仅剩的两条校验：都非空即可。原先「必须 11 位」「以 0 结尾必失败」两条硬规则
        // 会让「任意手机号」不成立，已删除。
        if (phone.isBlank()) {
            return LoginResult.Failure("请输入手机号")
        }
        if (credential.isBlank()) {
            return LoginResult.Failure(if (mode == LoginMode.SMS) "请输入验证码" else "请输入密码")
        }
        return when (mode) {
            LoginMode.SMS -> {
                // 唯一的例外：开发者选项「验证码测试」打开时，验证码必须与下发的一致
                // （规则与原因见 [verifyDevCodeOrNull] 与类注释）。
                verifyDevCodeOrNull(credential)
                    ?.let { LoginResult.Failure(it) }
                    ?: LoginResult.Success
            }

            LoginMode.PASSWORD -> {
                // 非空已在上方统一校验过，这里只剩「密码是否正确」。
                if (credential != DEMO_PASSWORD) {
                    LoginResult.Failure("密码错误")
                } else {
                    LoginResult.Success
                }
            }
        }
    }

    /**
     * 开发者选项「验证码测试」的一致性校验。**登录与注册共用这一处**：
     * 两个入口都点了「发送验证码」，若只有登录校验，注册那边就会变成「随便填都行」，
     * 用户会以为功能坏了。
     *
     * @return `null` = 通过；非 null = 直接可以显示给用户的失败原因。
     *   开关没打开时**恒为 null**，即完全回到原来的宽松行为。
     */
    private suspend fun verifyDevCodeOrNull(credential: String): String? {
        if (!developerOptions.isCodeTestEnabledNow()) return null
        // 顺序很重要：先判「有没有发过码」，再比对。
        // 否则用户什么都没做就随便填一个数字，只会得到「验证码不正确」这种误导性提示。
        if (!developerOptions.hasIssuedCode()) {
            return "请先点击「发送验证码」获取验证码"
        }
        if (!developerOptions.verifyAndConsume(credential)) {
            return "验证码不正确，请核对通知里的六位数字"
        }
        return null
    }

    companion object {
        /** 本地模拟用的固定密码。 */
        const val DEMO_PASSWORD = "123456"

        /**
         * 「密码登录」演示的模拟往返时长（毫秒）。
         *
         * 取 900ms 而不是登录页 [submit] 里的 250ms：那条路上按钮变的是文案，
         * 而这一屏要看到的是**转圈**（需求要求「显示 1 秒左右的『登录中…』」）。
         * 250ms 的圈几乎只够闪一帧，看起来像没加载。
         */
        private const val PASSWORD_DEMO_MS = 900L
    }
}

/**
 * 手机号脱敏：保留首位与末位，中间一律用 `****` 顶掉。
 *
 * 为什么要放宽到「任意长度」：登录页已经允许任意手机号进入，
 * 若仍按「必须 11 位才脱敏」处理，「我的」页会把用户输入的完整号码原样显示出来。
 * 长度 ≤ 2 时没有可遮蔽的中间段，原样返回（此时也不构成隐私暴露）。
 */
internal fun maskPhone(phone: String): String {
    // 云端回来的号码是规范化后的 `+86 13812345678`，若按「首字符 + **** + 末字符」脱敏，
    // 会显示成 `+****8`——既看不出是哪个号，也不像手机号。这里先剥掉国家码，
    // 再按用户熟悉的 `138****5678` 展示。
    val digits = phone.filter(Char::isDigit)
    val local = when {
        digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
        digits.length == 15 && digits.startsWith("0086") -> digits.drop(4)
        else -> digits
    }
    return when {
        local.length >= 8 -> "${local.take(3)}****${local.takeLast(4)}"
        // 极短输入（本地演示 / 异常数据）：没有可遮蔽的中间段，原样返回。
        phone.length <= 2 -> phone
        else -> "${phone.first()}****${phone.last()}"
    }
}
