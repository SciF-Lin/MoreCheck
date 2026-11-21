package com.morecheck.data

import com.morecheck.reminder.NotificationHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * 「实验室 → 开发者选项」的状态与行为。
 *
 * ## 为什么单独一个仓库，而不是塞进 `SettingsRepository`
 *
 * 三个子开关的**生效条件**都依赖总开关（`总开关 && 子开关`）。这个「与」运算如果散落在
 * 每个使用点，迟早会漏掉一处，表现为「总开关关了但某项还在生效」。
 * 集中在这里之后，调用方拿到的一律是**已经与过总开关的结果**，不可能忘。
 *
 * ## 验证码测试的「后端」在哪
 *
 * 本项目**没有后端**。需求说「在后台随机生成六位数字并记录在后端」，
 * 这里对应为**进程内的一个字段**（[issuedCode]）：
 * - 发送时写入，登录 / 注册时比对；
 * - 它是 `AppContainer` 里的单例，因此在整个应用进程内共享；
 * - **进程被杀（或应用被划掉）就清空**——这与「一次性验证码」的真实语义一致
 *   （真实短信验证码也有有效期，过期即失效）。
 *
 * 刻意**不**写进 DataStore：那会让验证码在重启后仍然生效，反而不像真实的短信验证码，
 * 也存在「上次的码被翻出来复用」的隐患。
 */
class DeveloperOptionsRepository(
    private val settings: SettingsRepository,
    private val notificationHelper: NotificationHelper
) {

    /** 总开关本身（界面上的那一个）。 */
    val masterEnabled: Flow<Boolean> = settings.devModeEnabled

    /** 原始子开关状态，仅用于界面回显（**不代表是否生效**）。 */
    val codeTestSwitch: Flow<Boolean> = settings.devCodeTestEnabled
    val perfTestSwitch: Flow<Boolean> = settings.devPerfTestEnabled

    /** 「验证码测试」是否**真正生效**（总开关与子开关都开）。 */
    val codeTestEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devCodeTestEnabled) { master, sub -> master && sub }

    /** 「性能测试」是否生效。 */
    val perfTestEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devPerfTestEnabled) { master, sub -> master && sub }

    /**
     * 开屏「Hello World」额外停留时长（毫秒）的**原始值**，仅用于界面回显。
     *
     * 与 [splashHoldDurationMs] 的关系，就是上面 `perfTestSwitch` 与 `perfTestEnabled` 的关系：
     * 这个是用户设的值（永远可读），那个是「现在到底按哪个数走」。
     */
    val splashHoldSwitch: Flow<Int> = settings.splashHoldMs

    /**
     * 开屏额外停留时长**当前真正生效**的值（毫秒）。
     *
     * 门控条件 = `DEV_MODE_ENABLED`（页面总开关）**且** `DEV_PERF_TEST_ENABLED`（性能测试开关）——
     * 两者都开才用用户设的值；否则回落到 [SettingsRepository.DEFAULT_SPLASH_HOLD_MS]。
     *
     * ## 为什么关掉开关时是「回落默认」而不是「回落到 0」
     *
     * 需求说这个滑块「受性能测试总开关控制」。控制的方式有两种，选「回落默认」是因为它
     * **与改造前的行为完全一致**：性能测试默认关，此时开屏就该是原来的 900ms。
     * 若回落 0，那么「性能测试开关默认关」就等于**所有用户的开屏都被缩短了**，
     * 等于用一个默认关闭的开关悄悄改掉了全局行为——那正是需求里「不要自行增加需求之外的功能」
     * 想要避免的越界。
     *
     * 调用方（`MainActivity`）用这个值决定 `SplashGate` 的额外停留时长，不需要自己再判一次开关。
     */
    val splashHoldDurationMs: Flow<Int> =
        combine(settings.devModeEnabled, settings.devPerfTestEnabled, settings.splashHoldMs) {
                master, perf, hold ->
            if (master && perf) hold else SettingsRepository.DEFAULT_SPLASH_HOLD_MS
        }

    suspend fun setMasterEnabled(enabled: Boolean) = settings.setDevModeEnabled(enabled)

    suspend fun setCodeTestEnabled(enabled: Boolean) = settings.setDevCodeTestEnabled(enabled)

    suspend fun setPerfTestEnabled(enabled: Boolean) = settings.setDevPerfTestEnabled(enabled)

    /** 写入滑块的值。夹取与「默认值是什么」都在仓库层（见 `SettingsRepository` 的常量）。 */
    suspend fun setSplashHoldMs(ms: Int) = settings.setSplashHoldMs(ms)

    // ---- 验证码测试 --------------------------------------------------------

    /**
     * 当前有效的验证码；null = 还没发过、或已被消费 / 清除。
     *
     * `@Volatile`：写入发生在「点发送验证码」那一侧（可能不在主线程），
     * 读取发生在登录校验那一侧；虽然本项目两侧都在主线程调度，
     * 但加上它成本为零，且避免以后把校验挪到 IO 线程时踩内存可见性的坑。
     */
    @Volatile
    private var issuedCode: String? = null

    /** 是否已经发过验证码（用于界面提示与校验顺序）。 */
    fun hasIssuedCode(): Boolean = issuedCode != null

    /** 仅供调试展示：当前验证码（界面上不展示，避免把"答案"直接给用户）。 */
    fun peekCode(): String? = issuedCode

    /**
     * 生成一个六位验证码、记为「后端记录」，并给用户发一条通知。
     *
     * 返回生成的验证码，便于调用方做诊断输出（正式包不展示）。
     *
     * 六位数字首位允许为 0：真实短信验证码就是 000000~999999 的等概率六位，
     * 若强制首位非 0 会变成 100000~999999，与真实场景不符（也少了一成空间）。
     */
    fun issueCode(): String {
        val code = buildString {
            repeat(CODE_LENGTH) { append(Random.nextInt(0, 10)) }
        }
        issuedCode = code
        // 通知是「模拟收到短信」的关键一步：用户要从通知里读到这六位数字再填回输入框。
        notificationHelper.notifyDevVerificationCode(code)
        return code
    }

    /**
     * 校验用户填写的验证码。
     *
     * 规则（需求：只有与后端记录一致才能成功）：
     * - 还没发过码 → 一律失败（否则「随便填」也能过）；
     * - 与记录不一致 → 失败；
     * - 一致 → **消费掉**（置空），保证一个码只能用一次。
     */
    fun verifyAndConsume(input: String): Boolean {
        val expected = issuedCode ?: return false
        if (input.trim() != expected) return false
        issuedCode = null
        return true
    }

    /** 清除当前验证码（例如点「发送验证码」后重新进入页面、或退出登录时）。 */
    fun clearCode() {
        issuedCode = null
    }

    /**
     * 同步读取「验证码测试是否生效」。
     *
     * 给**非 Composable 的流程**（登录 / 注册按钮的点击回调）用：
     * 那里拿不到 `StateFlow`，但需要立刻决定「是走真实校验还是走原来的任意验证码」。
     * 用 `first()` 取 DataStore 的当前值——DataStore 首次读取有磁盘 IO，
     * 因此调用点要在协程里（点击回调本来就在 `viewModelScope` 里）。
     */
    suspend fun isCodeTestEnabledNow(): Boolean =
        settings.devModeEnabled.first() && settings.devCodeTestEnabled.first()

    internal companion object {
        /** 验证码位数。 */
        const val CODE_LENGTH = 6
    }
}
