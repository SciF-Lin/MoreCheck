package com.morecheck.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.util.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** App-level settings persisted with Jetpack DataStore (Preferences). */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_TASK_SEEDED = booleanPreferencesKey("default_task_seeded")
        val LAST_ROLLOVER_DATE = stringPreferencesKey("last_rollover_date")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LOGGED_IN = booleanPreferencesKey("auth_logged_in")
        val PHONE = stringPreferencesKey("auth_phone")
        val LOGIN_MODE = stringPreferencesKey("auth_login_mode")
    }

    /**
     * 原始 DataStore 数据流。仅用于让同层的仓库（如 [AuthRepository]）
     * 复用同一个 DataStore 实例，避免同一文件被打开两次。
     */
    internal val rawData: Flow<Preferences> get() = context.dataStore.data

    val defaultTaskSeeded: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEFAULT_TASK_SEEDED] ?: false }

    val lastRolloverDate: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_ROLLOVER_DATE] }

    /** 深色模式：light / dark / system。 */
    val themeMode: Flow<String> =
        context.dataStore.data.map { it[Keys.THEME_MODE] ?: ThemeMode.SYSTEM }

    /** 动态配色：开启时跟随系统取色（Android 12+）。 */
    val dynamicColor: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

    suspend fun setDefaultTaskSeeded() {
        context.dataStore.edit { it[Keys.DEFAULT_TASK_SEEDED] = true }
    }

    suspend fun setLastRolloverDate(date: String) {
        context.dataStore.edit { it[Keys.LAST_ROLLOVER_DATE] = date }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    // ---- 登录态（本地模拟，任务2）------------------------------------------

    suspend fun setLoggedIn(phone: String, mode: String) {
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = true
            it[Keys.PHONE] = phone
            it[Keys.LOGIN_MODE] = mode
        }
    }

    suspend fun setLoggedOut() {
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = false
            it.remove(Keys.PHONE)
            it.remove(Keys.LOGIN_MODE)
        }
    }
}
