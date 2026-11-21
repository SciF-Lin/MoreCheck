package com.morecheck.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.SettingsRepository
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
 * 界面读到的永远是初始默认值 `true`，于是「动态配色关不掉」。
 *
 * 现在改成：自己持有 [MutableStateFlow] 作为唯一可信来源，
 * 启动时从 DataStore 读一次作为初值，写入后**立即更新本地状态**再落盘。
 * 这样界面既不依赖 DataStore 的发射时机，也不会出现读写回环。
 */
class SettingsViewModel(private val settings: SettingsRepository) : ViewModel() {

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(true)
    val dynamicColor = _dynamicColor.asStateFlow()

    init {
        viewModelScope.launch {
            // 读一次持久化值作为初值；读失败保持默认值，不阻塞界面。
            runCatching {
                _themeMode.value = settings.themeMode.first()
                _dynamicColor.value = settings.dynamicColor.first()
            }
        }
    }

    fun setThemeMode(mode: String) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        if (_dynamicColor.value == enabled) return
        _dynamicColor.value = enabled
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }
}
