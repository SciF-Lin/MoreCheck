package com.morecheck.data

import android.content.Context
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
    }

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
}
