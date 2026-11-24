package com.morecheck.data.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * B 站模块的**本机观看库**（2026-11-18 重做新增）。
 *
 * ## 为什么要本地一份
 *
 * B 站的观看历史 / 稍后再看 / 收藏都在云端、都要登录，而本应用有相当比例的用户
 * 是免登录刷视频的：他们没有"我的"列表，看完就找不回来了。逆向的两款客户端
 * 都把历史做在本地（Lite 的 `HistoryStore`、BMSC 的 sqflite 播放记录），
 * 因此这里也用本地存储做**免登录可用的那一份**，云端那份由 [BilibiliRepository]
 * 提供，界面按登录态选择展示哪边。
 *
 * ## 为什么用 JSON 字符串而不是每个视频一行 Room
 *
 * 这只是"最近看过哪些"的轻量列表（上限 [MAX_HISTORY] 条），不是业务数据：
 * - 不需要关系查询、不需要迁移、不需要和打卡表混在一个数据库里；
 * - 读写都是整包替换，一次 `DataStore.edit` 就够；
 * - 界面本来就拿到完整的 [BiliVideoItem]，序列化成本极低。
 *
 * 真正需要结构化的用户数据（打卡 / 任务 / 云同步）仍走 Room，两者不混。
 *
 * ## 一致性口径
 *
 * - 历史按 bvid 去重、**最新在前**，上限 [MAX_HISTORY]；
 * - 稍后再看 / 收藏同样按 bvid 去重，新加入的在前；
 * - 点赞与关注只记本机状态，真正的动作由 [BilibiliRepository.actLike] /
 *   [BilibiliRepository.actFollow] 发到服务端；本地状态只是"这次点过"的即时回显。
 */
object BiliLibraryStore {

    private val Context.biliLibraryDataStore: DataStore<Preferences> by preferencesDataStore(
        name = "bili_library"
    )

    private val HISTORY = stringPreferencesKey("history_json")
    private val WATCH_LATER = stringPreferencesKey("watch_later_json")
    private val FAVORITES = stringPreferencesKey("favorites_json")
    private val LIKES = stringSetPreferencesKey("liked_bvids")
    private val FOLLOWS = stringSetPreferencesKey("followed_mids")
    private val PLAY_POS = stringPreferencesKey("play_pos_json")
    private val SPONSOR_SKIP = booleanPreferencesKey("sponsor_skip")
    private val BILI_ACCENT = intPreferencesKey("bili_accent")

    const val MAX_HISTORY = 100
    private const val MAX_LIST = 300

    /** 进度记忆条目上限：超限时整体清一次只留当前条（参考 Cheers PlayPos 的 MAX_KEYS）。 */
    private const val MAX_POS_KEYS = 200

    // DataStore 实例按 Context 取，因此这些 Flow 都以 `xxxOf(context)` 的形式提供。
    // 不要在 object 顶层建全局 Flow——那会在 object 初始化时就需要 Context。

    /** 历史（需要 Context 才能拿到 DataStore）。 */
    fun historyOf(context: Context): Flow<List<BiliVideoItem>> =
        context.biliLibraryDataStore.data.map { decode(it[HISTORY].orEmpty()) }

    fun watchLaterOf(context: Context): Flow<List<BiliVideoItem>> =
        context.biliLibraryDataStore.data.map { decode(it[WATCH_LATER].orEmpty()) }

    fun favoritesOf(context: Context): Flow<List<BiliVideoItem>> =
        context.biliLibraryDataStore.data.map { decode(it[FAVORITES].orEmpty()) }

    fun likedOf(context: Context): Flow<Set<String>> =
        context.biliLibraryDataStore.data.map { it[LIKES].orEmpty() }

    fun followedOf(context: Context): Flow<Set<Long>> =
        context.biliLibraryDataStore.data.map { prefs ->
            prefs[FOLLOWS].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        }

    /** 记一次观看：旧记录先删，再插到头部。 */
    suspend fun recordWatch(context: Context, item: BiliVideoItem) {
        if (item.bvid.isBlank()) return
        context.biliLibraryDataStore.edit { prefs ->
            val current = decode(prefs[HISTORY].orEmpty())
                .filterNot { it.bvid == item.bvid }
            prefs[HISTORY] = encode((listOf(item) + current).take(MAX_HISTORY))
        }
    }

    suspend fun addWatchLater(context: Context, item: BiliVideoItem) {
        if (item.bvid.isBlank()) return
        context.biliLibraryDataStore.edit { prefs ->
            val current = decode(prefs[WATCH_LATER].orEmpty())
                .filterNot { it.bvid == item.bvid }
            prefs[WATCH_LATER] = encode((listOf(item) + current).take(MAX_LIST))
        }
    }

    suspend fun removeWatchLater(context: Context, bvid: String) {
        context.biliLibraryDataStore.edit { prefs ->
            prefs[WATCH_LATER] = encode(decode(prefs[WATCH_LATER].orEmpty()).filterNot { it.bvid == bvid })
        }
    }

    suspend fun toggleFavorite(context: Context, item: BiliVideoItem): Boolean {
        if (item.bvid.isBlank()) return false
        var added = false
        context.biliLibraryDataStore.edit { prefs ->
            val current = decode(prefs[FAVORITES].orEmpty())
            added = current.none { it.bvid == item.bvid }
            prefs[FAVORITES] = encode(
                if (added) (listOf(item) + current).take(MAX_LIST)
                else current.filterNot { it.bvid == item.bvid }
            )
        }
        return added
    }

    suspend fun setLiked(context: Context, bvid: String, liked: Boolean) {
        if (bvid.isBlank()) return
        context.biliLibraryDataStore.edit { prefs ->
            val current = prefs[LIKES].orEmpty().toMutableSet()
            if (liked) current += bvid else current -= bvid
            prefs[LIKES] = current
        }
    }

    suspend fun setFollowed(context: Context, mid: Long, followed: Boolean) {
        if (mid <= 0L) return
        context.biliLibraryDataStore.edit { prefs ->
            val current = prefs[FOLLOWS].orEmpty().toMutableSet()
            val key = mid.toString()
            if (followed) current += key else current -= key
            prefs[FOLLOWS] = current
        }
    }

    suspend fun clearHistory(context: Context) {
        context.biliLibraryDataStore.edit { it.remove(HISTORY) }
    }

    // ---- 播放进度记忆（2026-11-18 P0 改造新增）------------------------------
    //
    // 键是 `bvid#page`（多 P 各记各的），值是毫秒。播放页每 ~5s 落一次、
    // 退出时补一次；播完（ENDED）清除，下次从头播。

    suspend fun savePlayPos(context: Context, bvid: String, page: Int, positionMs: Long) {
        if (bvid.isBlank() || positionMs <= 0L) return
        context.biliLibraryDataStore.edit { prefs ->
            val obj = posObject(prefs[PLAY_POS])
            val key = "$bvid#$page"
            obj.put(key, positionMs)
            prefs[PLAY_POS] = if (obj.length() > MAX_POS_KEYS) {
                JSONObject().put(key, positionMs).toString()
            } else {
                obj.toString()
            }
        }
    }

    suspend fun playPosOf(context: Context, bvid: String, page: Int): Long {
        val prefs = context.biliLibraryDataStore.data.first()
        return posObject(prefs[PLAY_POS]).optLong("$bvid#$page", 0L)
    }

    suspend fun clearPlayPos(context: Context, bvid: String, page: Int) {
        context.biliLibraryDataStore.edit { prefs ->
            val obj = posObject(prefs[PLAY_POS])
            obj.remove("$bvid#$page")
            prefs[PLAY_POS] = obj.toString()
        }
    }

    private fun posObject(raw: String?): JSONObject =
        runCatching { JSONObject(raw.orEmpty().ifBlank { "{}" }) }.getOrDefault(JSONObject())

    // ---- 赞助跳过开关（默认开；数据源见 BiliSponsorBlock）--------------------

    fun sponsorSkipOf(context: Context): Flow<Boolean> =
        context.biliLibraryDataStore.data.map { it[SPONSOR_SKIP] ?: true }

    suspend fun setSponsorSkip(context: Context, enabled: Boolean) {
        context.biliLibraryDataStore.edit { it[SPONSOR_SKIP] = enabled }
    }

    // ---- B 站自选主题色（0 = 默认品牌粉；其余为 ARGB seed）------------------

    fun biliAccentOf(context: Context): Flow<Int> =
        context.biliLibraryDataStore.data.map { it[BILI_ACCENT] ?: 0 }

    suspend fun setBiliAccent(context: Context, argb: Int) {
        context.biliLibraryDataStore.edit { it[BILI_ACCENT] = argb }
    }

    // ---- JSON 编解码 --------------------------------------------------------

    private fun encode(items: List<BiliVideoItem>): String =
        JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("bvid", item.bvid)
                        .put("title", item.title)
                        .put("coverUrl", item.coverUrl)
                        .put("upName", item.upName)
                        .put("viewCount", item.viewCount)
                        .put("durationSeconds", item.durationSeconds)
                        .put("pubdate", item.pubdate)
                        .put("mid", item.mid)
                )
            }
        }.toString()

    private fun decode(raw: String): List<BiliVideoItem> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val bvid = obj.optString("bvid")
                if (bvid.isBlank()) return@mapNotNull null
                BiliVideoItem(
                    bvid = bvid,
                    title = obj.optString("title").ifBlank { "（无标题）" },
                    coverUrl = obj.optString("coverUrl"),
                    upName = obj.optString("upName").ifBlank { "未知UP主" },
                    viewCount = obj.optInt("viewCount", 0),
                    durationSeconds = obj.optInt("durationSeconds", 0),
                    pubdate = obj.optLong("pubdate", 0L),
                    mid = obj.optLong("mid", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }
}
