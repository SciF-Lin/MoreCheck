package com.morecheck.data.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 三套音乐专属风格（设计稿「主题色板」：海洋蓝 / 落日橙 / 极光紫）。 */
enum class MusicThemeStyle(val label: String, val english: String) {
    OCEAN("海洋蓝", "Ocean"),
    SUNSET("落日橙", "Sunset"),
    AURORA("极光紫", "Aurora")
}

/** 明暗模式。SYSTEM 时跟随系统夜间标志，其余两档由用户强制指定。 */
enum class MusicThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

data class MusicThemeSpec(
    val style: MusicThemeStyle = MusicThemeStyle.OCEAN,
    val mode: MusicThemeMode = MusicThemeMode.SYSTEM
)

/**
 * 音乐主题设置的**唯一出处**（2026-11-08 设计稿需求 3）。
 *
 * ## 为什么要独立
 *
 * 用户明确要求音乐界面「作为独立风格，不需要和 app 其他地方 UI 风格相衔接」。
 * 所以它不读 `SettingsRepository` 的深浅开关，也不改全局 `MaterialTheme`：
 * 只在音乐模块内部通过 [spec] 广播，主页 / 播放页 / 账号页同时重组。
 *
 * ## 为什么复用 `musicDataStore`
 *
 * `preferencesDataStore("music")` 全项目只允许声明一次（见 [musicDataStore] 的注释）。
 * 主题只有两个字符串键，和收藏 / 最近播放放同一个文件足够；再开一个文件
 * 反而会踩「同一文件多实例」那条老坑。
 *
 * ## 响应式写入
 *
 * [setStyle] / [setMode] **先更新 StateFlow 再落盘**：点击后当帧就换色，
 * 不等 DataStore 的 IO 往返。落盘失败也不影响当前会话（下次进页面回到上次值）。
 */
object MusicThemeStore {

    private val KEY_STYLE = stringPreferencesKey("music_theme_style")
    private val KEY_MODE = stringPreferencesKey("music_theme_mode")

    private val _spec = MutableStateFlow(MusicThemeSpec())
    val spec: StateFlow<MusicThemeSpec> = _spec.asStateFlow()

    private val started = AtomicBoolean(false)

    @Volatile
    private var app: Context? = null

    /** 幂等：任何读调色板的地方都会调一次，只有第一次真正启动收集。 */
    fun init(context: Context) {
        val application = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        app = application
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            application.musicDataStore.data
                .map { prefs ->
                    val style = runCatching {
                        MusicThemeStyle.valueOf(prefs[KEY_STYLE].orEmpty())
                    }.getOrDefault(MusicThemeStyle.OCEAN)
                    val mode = runCatching {
                        MusicThemeMode.valueOf(prefs[KEY_MODE].orEmpty())
                    }.getOrDefault(MusicThemeMode.SYSTEM)
                    MusicThemeSpec(style, mode)
                }
                .collect { _spec.value = it }
        }
    }

    fun setStyle(style: MusicThemeStyle) {
        _spec.value = _spec.value.copy(style = style)
        write { it[KEY_STYLE] = style.name }
    }

    fun setMode(mode: MusicThemeMode) {
        _spec.value = _spec.value.copy(mode = mode)
        write { it[KEY_MODE] = mode.name }
    }

    private fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val context = app ?: return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { context.musicDataStore.edit(block) }
        }
    }
}
