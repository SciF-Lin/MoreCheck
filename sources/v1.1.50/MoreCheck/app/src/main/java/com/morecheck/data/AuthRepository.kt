package com.morecheck.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本地模拟的登录态仓库。
 *
 * 任务2 只做前端与本地模拟：不接真实短信、不接后端。
 *
 * 键名前缀统一为 `auth_`，与 [SettingsRepository] 的键共用同一个 DataStore 文件，
 * 因此这里通过 [SettingsRepository.rawData] 读取，避免同一文件被打开两次。
 */
class AuthRepository(private val settings: SettingsRepository) {

    private object Keys {
        val LOGGED_IN = booleanPreferencesKey("auth_logged_in")
        val PHONE = stringPreferencesKey("auth_phone")
        val LOGIN_MODE = stringPreferencesKey("auth_login_mode")
    }

    /** 是否已登录。 */
    val isLoggedIn: Flow<Boolean> =
        settings.rawData.map { it[Keys.LOGGED_IN] ?: false }

    /** 已登录手机号；未登录为 null。 */
    val phone: Flow<String?> =
        settings.rawData.map { it[Keys.PHONE] }

    /** 最近一次使用的登录方式：sms / password。 */
    val loginMode: Flow<String?> =
        settings.rawData.map { it[Keys.LOGIN_MODE] }

    suspend fun login(phone: String, mode: String) {
        settings.setLoggedIn(phone = phone, mode = mode)
    }

    suspend fun logout() {
        settings.setLoggedOut()
    }
}
