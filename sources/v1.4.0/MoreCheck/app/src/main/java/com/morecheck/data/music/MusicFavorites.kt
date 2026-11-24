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
 * 收藏分组（2026-11-09 需求：我的收藏支持新建分组）。
 *
 * [id] 稳定不变（改名不换 id，歌曲的归属才不会丢）；
 * [name] 可改；[DEFAULT_ID] 这一组**不能被删除**，删其它分组时组内歌曲回到它。
 */
data class FavoriteGroup(
    val id: String,
    val name: String
) {
    companion object {
        const val DEFAULT_ID = "default"
        val DEFAULT = FavoriteGroup(DEFAULT_ID, "默认分组")
    }
}

/** 一条收藏：曲目 + 归属分组。 */
data class FavoriteEntry(
    val track: MusicTrack,
    val groupId: String = FavoriteGroup.DEFAULT_ID
)

/**
 * 「收藏的歌曲」的本地存储（含分组）。
 *
 * 存储委托在 `MusicStore.kt`（**不能在本文件再声明一次**：同一文件名两份委托会
 * 让两个 DataStore 实例指向同一个文件，运行时直接抛
 * `There are multiple DataStores active for the same file`）。
 *
 * ## 存储格式（v2，2026-11-09）
 *
 * ```json
 * { "version": 2,
 *   "groups": [{"id":"default","name":"默认分组"}, {"id":"g...","name":"通勤"}],
 *   "items":  [{"groupId":"g...","track":{...}}] }
 * ```
 *
 * **兼容 v1**：v1 是 `{"items":[track, track…]}`（扁平、无分组）。读到时按
 * 「每条形如 track（没有 track 壳）→ 归默认分组」处理，**下次写入时自动升级成 v2**，
 * 用户不需要做任何迁移动作。
 *
 * ## 为什么还是 JSON 而不是 Room
 *
 * 数据量小（几百首）、要整体读写、没有查询需求；Room 要加表 + 迁移，收益不足。
 *
 * ⚠️ 与云同步**无关**：收藏只在本机（《隐私政策》里如实写了"收藏只存本机"）。
 */
class MusicFavorites(private val context: Context) {

    private val key = stringPreferencesKey("favorites_json")

    /** 全部收藏（最近的在前）。 */
    val favorites: Flow<List<FavoriteEntry>> =
        context.musicDataStore.data.map { prefs -> decode(prefs[key]).items }

    /** 仅曲目列表（不需要分组信息的调用点用）。 */
    val tracks: Flow<List<MusicTrack>> =
        context.musicDataStore.data.map { prefs -> decode(prefs[key]).items.map { it.track } }

    /** 分组列表，默认分组恒在第一位。 */
    val groups: Flow<List<FavoriteGroup>> =
        context.musicDataStore.data.map { prefs ->
            val groups = decode(prefs[key]).groups
            if (groups.any { it.id == FavoriteGroup.DEFAULT_ID }) groups
            else listOf(FavoriteGroup.DEFAULT) + groups
        }

    /**
     * 切换收藏状态，返回**切换后**是否为已收藏。
     *
     * 新收藏落到 [groupId]（默认分组）；已收藏则取消（无论它在哪个分组里）。
     */
    suspend fun toggle(track: MusicTrack, groupId: String = FavoriteGroup.DEFAULT_ID): Boolean {
        var nowFavorite = false
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            val existed = snapshot.items.any { it.track.id == track.id }
            val items = if (existed) {
                snapshot.items.filterNot { it.track.id == track.id }
            } else {
                listOf(FavoriteEntry(track, groupId)) + snapshot.items
            }
            nowFavorite = !existed
            prefs[key] = encode(snapshot.groups, items)
        }
        return nowFavorite
    }

    /** 从收藏里移除。 */
    suspend fun remove(trackId: String) {
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            prefs[key] = encode(
                snapshot.groups,
                snapshot.items.filterNot { it.track.id == trackId }
            )
        }
    }

    /** 把某首歌移到指定分组；分组不存在时不动（返回 false）。 */
    suspend fun moveToGroup(trackId: String, groupId: String): Boolean {
        var moved = false
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            if (snapshot.groups.any { it.id == groupId }) {
                moved = snapshot.items.any { it.track.id == trackId }
                prefs[key] = encode(
                    snapshot.groups,
                    snapshot.items.map {
                        if (it.track.id == trackId) it.copy(groupId = groupId) else it
                    }
                )
            }
        }
        return moved
    }

    /** 新建分组，返回新分组的 id（名字重复时自动加序号）。 */
    suspend fun createGroup(name: String): String {
        val trimmed = name.trim().ifBlank { "新分组" }
        val id = "g" + System.currentTimeMillis().toString(36)
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            val finalName = uniqueName(trimmed, snapshot.groups)
            prefs[key] = encode(
                snapshot.groups + FavoriteGroup(id, finalName),
                snapshot.items
            )
        }
        return id
    }

    /** 分组改名（默认分组也允许改名，但 id 不变）。 */
    suspend fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim().ifBlank { return }
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            if (snapshot.groups.none { it.id == groupId }) return@edit
            val finalName = uniqueName(trimmed, snapshot.groups.filterNot { it.id == groupId })
            prefs[key] = encode(
                snapshot.groups.map {
                    if (it.id == groupId) it.copy(name = finalName) else it
                },
                snapshot.items
            )
        }
    }

    /**
     * 删除分组：组内歌曲**不删除**，回到默认分组。
     *
     * 这是最容易踩的坑——用户以为是"删分组"，不是"删歌"。
     */
    suspend fun deleteGroup(groupId: String) {
        if (groupId == FavoriteGroup.DEFAULT_ID) return
        context.musicDataStore.edit { prefs ->
            val snapshot = decode(prefs[key])
            prefs[key] = encode(
                snapshot.groups.filterNot { it.id == groupId },
                snapshot.items.map {
                    if (it.groupId == groupId) {
                        it.copy(groupId = FavoriteGroup.DEFAULT_ID)
                    } else {
                        it
                    }
                }
            )
        }
    }

    // ---- 内部：读写 ---------------------------------------------------------

    private data class Snapshot(
        val groups: List<FavoriteGroup>,
        val items: List<FavoriteEntry>
    )

    private fun uniqueName(candidate: String, others: List<FavoriteGroup>): String {
        if (others.none { it.name == candidate }) return candidate
        var index = 2
        while (others.any { it.name == "$candidate $index" }) index++
        return "$candidate $index"
    }

    private fun decode(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) {
            return Snapshot(listOf(FavoriteGroup.DEFAULT), emptyList())
        }
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Snapshot(listOf(FavoriteGroup.DEFAULT), emptyList())

        val groups = ArrayList<FavoriteGroup>()
        root.optJSONArray("groups")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                val name = item.optString("name").ifBlank { "未命名分组" }
                groups += FavoriteGroup(id, name)
            }
        }
        if (groups.none { it.id == FavoriteGroup.DEFAULT_ID }) {
            groups.add(0, FavoriteGroup.DEFAULT)
        }

        val items = ArrayList<FavoriteEntry>()
        root.optJSONArray("items")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                if (item == null) continue
                // v2：{"groupId": "...", "track": {...}}
                val wrapper = item.optJSONObject("track")
                if (wrapper != null) {
                    val track = MusicTrack.fromJson(wrapper) ?: continue
                    val groupId = item.optString("groupId")
                        .takeIf { g -> groups.any { it.id == g } }
                        ?: FavoriteGroup.DEFAULT_ID
                    items += FavoriteEntry(track, groupId)
                } else {
                    // v1：扁平 track —— 当成默认分组的收藏
                    MusicTrack.fromJson(item)?.let { items += FavoriteEntry(it) }
                }
            }
        }
        return Snapshot(groups, items)
    }

    private fun encode(groups: List<FavoriteGroup>, items: List<FavoriteEntry>): String {
        val groupArray = JSONArray()
        groups.forEach { group ->
            runCatching {
                groupArray.put(JSONObject().put("id", group.id).put("name", group.name))
            }
        }
        val itemArray = JSONArray()
        items.forEach { entry ->
            runCatching {
                itemArray.put(
                    JSONObject()
                        .put("groupId", entry.groupId)
                        .put("track", entry.track.toJson())
                )
            }
        }
        return JSONObject()
            .put("version", 2)
            .put("groups", groupArray)
            .put("items", itemArray)
            .toString()
    }
}
