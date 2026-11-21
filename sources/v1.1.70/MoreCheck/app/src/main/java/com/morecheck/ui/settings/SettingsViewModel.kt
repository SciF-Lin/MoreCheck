package com.morecheck.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.SettingsRepository
import com.morecheck.util.AppPermission
import com.morecheck.util.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页 / 主题的共享状态。
 *
 * **不用 `stateIn(WhileSubscribed)`**：之前用
 * `settings.dynamicColor.stateIn(viewModelScope, WhileSubscribed(5000), true)`
 * 时，上游 DataStore 流始终没有被订阅（实测该流一次都没发射过），
 * 界面读到的永远是初始默认值，于是「开关关不掉」。
 *
 * 现在改成：自己持有 [MutableStateFlow] 作为唯一可信来源，
 * 启动时从 DataStore 读一次作为初值，写入后**立即更新本地状态**再落盘。
 * 这样界面既不依赖 DataStore 的发射时机，也不会出现读写回环。
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode = _themeMode.asStateFlow()

    /** 主题色标识，取值见 `ui/theme/AccentColor`。 */
    private val _themeAccent = MutableStateFlow(SettingsRepository.DEFAULT_THEME_ACCENT)
    val themeAccent = _themeAccent.asStateFlow()

    /** 自启动：开机后是否自动恢复每日提醒。 */
    private val _autoStartOnBoot = MutableStateFlow(true)
    val autoStartOnBoot = _autoStartOnBoot.asStateFlow()

    /**
     * 打开软件时是否提示登录（实验室 → 登录提示功能）。
     *
     * 与 [privacyAgreed] 是两件事：那个是「不同意就不能用」的强制门闸，
     * 这个是同意之后那个**可关**的引导弹窗。默认 `true` = 保持应用原有行为。
     */
    private val _loginPromptEnabled = MutableStateFlow(true)
    val loginPromptEnabled = _loginPromptEnabled.asStateFlow()

    /**
     * 是否在底部导航栏显示朋友圈未读红点与数字
     * （实验室 → 开发者选项 → 图标测试 → 朋友圈红点提示）。
     *
     * 与 [loginPromptEnabled] 同一模式：默认 `true` = 与改造前的观感完全一致。
     * ⚠️ 它只门控「显不显示」角标，不影响「进入朋友圈后清除未读」那条链路
     * （见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc）。
     */
    private val _momentsBadgeEnabled = MutableStateFlow(true)
    val momentsBadgeEnabled = _momentsBadgeEnabled.asStateFlow()

    // ---- 权限管理开关（设置 → 通用 → 权限管理）--------------------------------
    //
    // 三项与 `util/AppPermissions.kt` 的 `AppPermission` 一一对应，
    // **统一按「默认关」处理**：没有使用意图就不该默认占用摄像头 / 定位 / 通知。
    // 这里同样刻意不用 stateIn：理由见类注释。

    /** 摄像头：是否允许「扫一扫」使用相机。 */
    private val _cameraEnabled = MutableStateFlow(false)
    val cameraEnabled = _cameraEnabled.asStateFlow()

    /** 定位：是否允许「实验室 → 天气」读取所在城市。 */
    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled = _locationEnabled.asStateFlow()

    /** 通知：每日提醒推送的总开关；关掉后 `ReminderReceiver` 不再弹通知。 */
    private val _notificationEnabled = MutableStateFlow(false)
    val notificationEnabled = _notificationEnabled.asStateFlow()

    // ---- 计时器声音（设置 → 通用 → 声音设置）----------------------------------

    /** 倒计时结束时是否响铃。默认开。 */
    private val _timerSoundEnabled = MutableStateFlow(true)
    val timerSoundEnabled = _timerSoundEnabled.asStateFlow()

    /** 选中铃声的 URI 字符串；null = 用系统默认通知音。 */
    private val _timerSoundUri = MutableStateFlow<String?>(null)
    val timerSoundUri = _timerSoundUri.asStateFlow()

    /** 选中铃声的显示名，仅用于设置页展示。 */
    private val _timerSoundTitle = MutableStateFlow<String?>(null)
    val timerSoundTitle = _timerSoundTitle.asStateFlow()

    // ---- 计时器最近使用的时长 -------------------------------------------------

    /** 最近使用过的时长（秒），最新在前，最多 [SettingsRepository.MAX_RECENT_DURATIONS] 个。 */
    private val _recentTimerDurations = MutableStateFlow<List<Int>>(emptyList())
    val recentTimerDurations = _recentTimerDurations.asStateFlow()

    // ---- 隐私同意 ---------------------------------------------------------

    /** 是否已同意《用户协议》/《隐私政策》。默认 false，由启动门闸拦截。 */
    private val _privacyAgreed = MutableStateFlow(false)
    val privacyAgreed = _privacyAgreed.asStateFlow()

    /**
     * 隐私同意状态是否已从 DataStore 加载完毕。
     *
     * 启动门闸用它区分「还没读出来」（不弹窗，等一等）与「读出来是未同意」（弹强制同意窗）。
     * 否则 `privacyAgreed` 初始为 false，已经同意过的用户会在启动瞬间被闪一下同意弹窗。
     */
    private val _privacyLoaded = MutableStateFlow(false)
    val privacyLoaded = _privacyLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            // 读一次持久化值作为初值；读失败保持默认值，不阻塞界面。
            runCatching {
                _themeMode.value = settings.themeMode.first()
                _themeAccent.value = settings.themeAccent.first()
                _autoStartOnBoot.value = settings.autoStartOnBoot.first()
                _loginPromptEnabled.value = settings.loginPromptEnabled.first()
                _momentsBadgeEnabled.value = settings.momentsBadgeEnabled.first()
                _cameraEnabled.value = settings.cameraEnabled.first()
                _locationEnabled.value = settings.locationEnabled.first()
                _notificationEnabled.value = settings.notificationEnabled.first()
                _timerSoundEnabled.value = settings.timerSoundEnabled.first()
                _timerSoundUri.value = settings.timerSoundUri.first()
                _timerSoundTitle.value = settings.timerSoundTitle.first()
                _recentTimerDurations.value = settings.recentTimerDurations.first()
                _privacyAgreed.value = settings.privacyAgreed.first()
                _privacyLoaded.value = true
            }
        }
    }

    fun agreePrivacy() {
        if (_privacyAgreed.value) return
        _privacyAgreed.value = true
        viewModelScope.launch { settings.setPrivacyAgreed(true) }
    }

    fun revokePrivacy() {
        if (!_privacyAgreed.value) return
        _privacyAgreed.value = false
        viewModelScope.launch { settings.setPrivacyAgreed(false) }
    }

    fun setThemeMode(mode: String) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setThemeAccent(accentId: String) {
        if (_themeAccent.value == accentId) return
        _themeAccent.value = accentId
        viewModelScope.launch { settings.setThemeAccent(accentId) }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        if (_autoStartOnBoot.value == enabled) return
        _autoStartOnBoot.value = enabled
        viewModelScope.launch { settings.setAutoStartOnBoot(enabled) }
    }

    fun setLoginPromptEnabled(enabled: Boolean) {
        if (_loginPromptEnabled.value == enabled) return
        _loginPromptEnabled.value = enabled
        viewModelScope.launch { settings.setLoginPromptEnabled(enabled) }
    }

    fun setMomentsBadgeEnabled(enabled: Boolean) {
        if (_momentsBadgeEnabled.value == enabled) return
        _momentsBadgeEnabled.value = enabled
        viewModelScope.launch { settings.setMomentsBadgeEnabled(enabled) }
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (_cameraEnabled.value == enabled) return
        _cameraEnabled.value = enabled
        viewModelScope.launch { settings.setPermissionEnabled(AppPermission.CAMERA.id, enabled) }
    }

    fun setLocationEnabled(enabled: Boolean) {
        if (_locationEnabled.value == enabled) return
        _locationEnabled.value = enabled
        viewModelScope.launch { settings.setPermissionEnabled(AppPermission.LOCATION.id, enabled) }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        if (_notificationEnabled.value == enabled) return
        _notificationEnabled.value = enabled
        viewModelScope.launch {
            settings.setPermissionEnabled(AppPermission.NOTIFICATION.id, enabled)
        }
    }

    fun setTimerSoundEnabled(enabled: Boolean) {
        if (_timerSoundEnabled.value == enabled) return
        _timerSoundEnabled.value = enabled
        viewModelScope.launch { settings.setTimerSoundEnabled(enabled) }
    }

    /**
     * 选中一个铃声；[uri] 传 null 表示恢复系统默认音。
     *
     * 先改本地状态再落盘：设置页要立刻显示选中态，不能等 DataStore 往返。
     */
    fun setTimerSound(uri: String?, title: String?) {
        _timerSoundUri.value = uri
        _timerSoundTitle.value = title
        viewModelScope.launch { settings.setTimerSound(uri, title) }
    }

    /** 记一次用过的时长（去重与截断都在仓库层做）。 */
    fun rememberTimerDuration(seconds: Int) {
        if (seconds <= 0) return
        // 本地立即更新，计时面板上的芯片不等待磁盘。
        _recentTimerDurations.value =
            (listOf(seconds) + _recentTimerDurations.value.filter { it != seconds })
                .take(SettingsRepository.MAX_RECENT_DURATIONS)
        viewModelScope.launch { settings.rememberTimerDuration(seconds) }
    }

    fun forgetTimerDuration(seconds: Int) {
        _recentTimerDurations.value = _recentTimerDurations.value.filter { it != seconds }
        viewModelScope.launch { settings.forgetTimerDuration(seconds) }
    }
}
