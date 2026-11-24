package com.morecheck.data.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.morecheck.data.net.MusicTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「最近播放」（2026-11-08 需求 E1）。
 *
 * ## 存在哪
 *
 * 与收藏同一个 `music` DataStore 文件的另一个键（委托在 `MusicStore.kt`，
 * 这里**不要**再声明一次 `preferencesDataStore`，否则同一文件会有两个实例、启动即崩）。共用文件是因为它们
 * 生命周期一致（都是音乐模块的本机数据、都不上云），分成两个文件只是多一次
 * 磁盘打开开销。
 *
 * ## 为什么记录"播放开始"而不是"播放结束"
 *
 * 用户点了一首就算听过一次。按结束记会导致"听了 3 分钟然后切歌"什么都不留，
 * 与"最近播放"的直觉相反。所以每次**发起播放**就写一条。
 *
 * ## 去重与上限
 *
 * 同一首歌只保留最新那一条（重复播放把它提到最前，而不是留一串相同的行）；
 * 最多 [MAX_ITEMS] 条，超出丢弃最旧的。列表本身不大，整串 JSON 重写代价可以接受。
 */
class MusicHistory(private val context: Context) {

    private val key = stringPreferencesKey("history_json")

    /** 最近播放列表（最新在最前）。 */
    val recent: Flow<List<MusicTrack>> =
        context.musicDataStore.data.map { prefs -> decode(prefs[key]) }

    /** 记一次播放。 */
    suspend fun record(track: MusicTrack) {
        context.musicDataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = (listOf(track) + current.filterNot { it.id == track.id })
                .take(MAX_ITEMS)
            prefs[key] = encode(next)
        }
    }

    /** 清空历史（给"最近播放"页的清理入口用）。 */
    suspend fun clear() {
        context.musicDataStore.edit { prefs -> prefs.remove(key) }
    }

    private fun decode(raw: String?): List<MusicTrack> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: runCatching {
            JSONObject(raw).optJSONArray("items") ?: JSONArray()
        }.getOrNull() ?: return emptyList()
        val list = ArrayList<MusicTrack>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            MusicTrack.fromJson(item)?.let { list += it }
        }
        return list
    }

    private fun encode(tracks: List<MusicTrack>): String {
        val array = JSONArray()
        tracks.forEach { track -> runCatching { array.put(track.toJson()) } }
        return JSONObject().put("items", array).toString()
    }

    private companion object {
        /** 上限 60 条：够翻很久，又不会让整串 JSON 太大（每条约 200 字节）。 */
        const val MAX_ITEMS = 60
    }
}
