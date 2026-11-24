package com.morecheck.ui.web.bili

import android.content.Context

/**
 * 播放页本地偏好（2026-11-03 B站增强新增）。
 *
 * 目前只有一项：**弹幕开关**。为什么不塞进 SettingsRepository/DataStore：
 * 它只服务 B站播放页、属于「播放器偏好」而不是全局设置，用 SharedPreferences
 * 一个键值最轻量，也不必动 SettingsRepository（那不在本次写文件白名单里）。
 */
internal object BiliPlaybackPrefs {

    private const val PREFS_NAME = "bili_playback"
    private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"

    /** 弹幕默认开（B 站默认行为；第一次进入与未改过设置的用户体验一致）。 */
    fun isDanmakuEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DANMAKU_ENABLED, true)

    fun setDanmakuEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DANMAKU_ENABLED, enabled)
            .apply()
    }
}
