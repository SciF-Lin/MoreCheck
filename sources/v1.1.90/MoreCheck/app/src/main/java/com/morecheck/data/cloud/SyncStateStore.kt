package com.morecheck.data.cloud

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 同步状态的本地存储（独立 DataStore 文件 `sync_state`）。
 *
 * ⚠️ 文件名必须与 `settings` / `notebook` / `cloud_session` **都不同**：
 * DataStore 对同一文件有全进程唯一性校验，两个实例同时持有会直接抛
 * `IllegalStateException`（详见 `NotebookStore` 顶部那段注释）。
 *
 * ## 这里存什么、不存什么
 *
 * **存**：同步引擎自己的簿记——水位线（推送 / 按表拉取 / 墓碑）、自动同步开关、
 * 上次同步的结果、设置与记事本的"已推送基线"（用于判断本地有没有未上传的改动）。
 *
 * **不存**：任何业务数据。业务数据在 Room（结构化轨道）与私有目录（图片 /
 * 整包备份）里，本文件只回答"同步到哪了"这一个问题。
 *
 * ## ⚠️ 水位线按账号命名空间存储（v3.1 起）
 *
 * 水位线（推送 / 拉取 / 墓碑）与"已推送基线"**必须按云账号隔离**：
 * 键名带 `userId` 后缀（见 [SyncStateKeys] 的工厂方法）。
 *
 * 原因：这些键记录的是"**云端**推进到哪了"，而云端数据是按账号隔离的。
 * 若用全局键，换账号登录后拉取水位线仍是旧账号的高水位 → 新账号那些
 * `updated_at` 更早的历史数据**永远拉不下来**；推送水位线同理，会导致
 * "首次登录选上传"一行都传不上去。
 */
private val Context.syncStateStore by preferencesDataStore(name = "sync_state")

/**
 * 同步簿记 + 面向界面的同步状态。
 *
 * [lastPushAt] / [pullAtTasks] 等水位线是**引擎内部**状态，界面不展示；
 * 界面只看 [autoSyncEnabled] 与上次同步结果三项。
 */
data class SyncState(
    /** 自动同步（每日一次 + 打卡后轻量推送）总开关。默认开。 */
    val autoSyncEnabled: Boolean = true,
    /** 打卡后是否触发一次轻量推送。默认开（可在同步设置里关）。 */
    val syncAfterCheckIn: Boolean = true,
    /** 上次同步完成时间（epoch 毫秒）；从未同步过为 null。 */
    val lastSyncAt: Long? = null,
    /** 上次同步是否成功；从未同步过为 null。 */
    val lastSyncOk: Boolean? = null,
    /** 上次同步的结果摘要（成功时是统计，失败时是原因），直接可展示。 */
    val lastSyncMessage: String? = null,
    /**
     * 本次登录是否**已经问过**「上传 / 下载 / 暂不处理」。
     *
     * 每个账号生命周期只问一次：登录成功后若为 false 就弹首次登录策略窗，
     * 用户做过选择（任一结果）即置 true。**登出时重置为 false**——
     * 换账号登录时云端可能是另一份数据，必须重新问。
     */
    val firstLoginHandled: Boolean = false
)

/**
 * 拉取水位线按表分（原因见 `CloudSyncRepository` 类注释）。
 *
 * 可见性是 public 而不是 internal：[SyncStateStore.pullAt] / [SyncStateStore.setPullAt]
 * 是 public 方法、参数里带着它——Kotlin 不允许 public 成员暴露 internal 类型。
 * 它只描述"哪张表"，不携带任何敏感信息，公开无害。
 */
enum class PullTable {
    TASKS,
    RECORDS,
    NOTEBOOK,
    SETTINGS,
    GAMES
}

/**
 * [SyncState] 持久化的全部键（集中一处，避免字符串散落）。
 *
 * `internal`：只供同步引擎与 [SyncStateStore] 内部读写，界面用不到——
 * 界面看 [SyncState] 上的那些字段。曾经有一个 `SyncState.Keys` 转发属性，
 * 它是 public 属性暴露 internal 类型（编译错误）且**没有任何使用方**，
 * 已删除（本项目不留死代码）。
 */
internal object SyncStateKeys {
    val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
    val SYNC_AFTER_CHECKIN = booleanPreferencesKey("sync_after_check_in")
    val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    val LAST_SYNC_OK = booleanPreferencesKey("last_sync_ok")
    val LAST_SYNC_MESSAGE = stringPreferencesKey("last_sync_message")

    /** 首次登录策略窗是否已问过（见 [SyncState.firstLoginHandled]）。 */
    val FIRST_LOGIN_HANDLED = booleanPreferencesKey("first_login_handled")

    // ---- 账号命名空间的水位线 / 基线（**必须**带 userId 后缀）-----------------
    //
    // 这些值描述的是「某个账号的云端推进到哪了」，跨账号共用会互相污染。
    // 键名用 `@<userId>` 后缀区分；userId 来自云端会话的 `sub`。

    /** 推送水位线：只推 `updated_at > 它` 的行。 */
    fun lastPushAt(userId: String) = longPreferencesKey("last_push_at@$userId")

    /**
     * 墓碑推送水位线：只推 `deleted_at > 它` 的墓碑。
     *
     * 与 [lastPushAt] **分开**：墓碑的时间戳是"删除发生时刻"，行的
     * `updated_at` 是"内容改动时刻"，两者语义不同、共用一条水位线会让
     * 某些墓碑既没被推、又被当作"已推"而丢弃（删除事件丢失）。
     */
    fun lastTombstonePushAt(userId: String) = longPreferencesKey("last_tombstone_push_at@$userId")

    /**
     * 游戏数据的推送水位线：只推 `updated_at > 它` 的游戏快照。
     *
     * 与 [lastPushAt] 分开：游戏行不参与任务 / 记录行的"整轮最大已推时间戳"
     * （`maxPushed`），若共用同一水位线，游戏行会因为"其它表推过更新的行"
     * 而被误判为已推、从此再也推不上去。
     */
    fun lastGamePushAt(userId: String) = longPreferencesKey("last_game_push_at@$userId")

    // ---- 按表拉取水位线（不能合并成一个全局值，原因见 CloudSyncRepository）----
    fun pullAt(userId: String, table: PullTable) =
        longPreferencesKey("pull_at_${table.name.lowercase()}@$userId")

    /** 设置快照的"已推送基线"：与当前设置序列化比较，判断本地有没有未上传改动。 */
    fun pushedSettingsJson(userId: String) = stringPreferencesKey("pushed_settings_json@$userId")

    /** 记事本的"已推送基线"（纯文字）。 */
    fun pushedNotebookText(userId: String) = stringPreferencesKey("pushed_notebook_text@$userId")
}

/**
 * 同步状态仓库。
 *
 * 与 `SettingsRepository` 的关系：那个管**用户设置**，这个管**同步机制**。
 * 刻意分开两个文件——设置的读写是高频单项开关，同步簿记是低频内部状态，
 * 混在一份 DataStore 里会让"改一个主题色"与"推进水位线"互相挤压。
 */
class SyncStateStore(context: Context) {

    private val store = context.applicationContext.syncStateStore

    /** 面向界面的状态流（同步设置页 / 我的页订阅）。 */
    val state: Flow<SyncState> = store.data.map { prefs ->
        SyncState(
            autoSyncEnabled = prefs[SyncStateKeys.AUTO_SYNC] ?: true,
            syncAfterCheckIn = prefs[SyncStateKeys.SYNC_AFTER_CHECKIN] ?: true,
            lastSyncAt = prefs[SyncStateKeys.LAST_SYNC_AT],
            lastSyncOk = prefs[SyncStateKeys.LAST_SYNC_OK],
            lastSyncMessage = prefs[SyncStateKeys.LAST_SYNC_MESSAGE],
            firstLoginHandled = prefs[SyncStateKeys.FIRST_LOGIN_HANDLED] ?: false
        )
    }

    suspend fun current(): SyncState = state.first()

    suspend fun setAutoSyncEnabled(enabled: Boolean) {
        store.edit { it[SyncStateKeys.AUTO_SYNC] = enabled }
    }

    suspend fun setSyncAfterCheckIn(enabled: Boolean) {
        store.edit { it[SyncStateKeys.SYNC_AFTER_CHECKIN] = enabled }
    }

    /** 记一次同步结果（三个字段一起写，避免界面读到"时间更新了但结果还是旧的"）。 */
    suspend fun recordSyncResult(at: Long, ok: Boolean, message: String) {
        store.edit {
            it[SyncStateKeys.LAST_SYNC_AT] = at
            it[SyncStateKeys.LAST_SYNC_OK] = ok
            it[SyncStateKeys.LAST_SYNC_MESSAGE] = message
        }
    }

    // ---- 首次登录策略窗（见 [SyncState.firstLoginHandled]）-----------------------

    /** 本次登录是否已问过合并策略。 */
    suspend fun firstLoginHandled(): Boolean =
        store.data.first()[SyncStateKeys.FIRST_LOGIN_HANDLED] ?: false

    /** 标记"已问过"（用户做了任一选择后调用；登出时重置）。 */
    suspend fun setFirstLoginHandled(handled: Boolean) {
        store.edit { it[SyncStateKeys.FIRST_LOGIN_HANDLED] = handled }
    }

    // ---- 水位线与基线（引擎内部，**按账号隔离**）--------------------------------

    suspend fun lastPushAt(userId: String): Long = readLong(SyncStateKeys.lastPushAt(userId))

    suspend fun setLastPushAt(userId: String, value: Long) =
        writeLong(SyncStateKeys.lastPushAt(userId), value)

    suspend fun lastTombstonePushAt(userId: String): Long =
        readLong(SyncStateKeys.lastTombstonePushAt(userId))

    suspend fun setLastTombstonePushAt(userId: String, value: Long) =
        writeLong(SyncStateKeys.lastTombstonePushAt(userId), value)

    suspend fun lastGamePushAt(userId: String): Long =
        readLong(SyncStateKeys.lastGamePushAt(userId))

    suspend fun setLastGamePushAt(userId: String, value: Long) =
        writeLong(SyncStateKeys.lastGamePushAt(userId), value)

    suspend fun pullAt(userId: String, table: PullTable): Long =
        readLong(SyncStateKeys.pullAt(userId, table))

    suspend fun setPullAt(userId: String, table: PullTable, value: Long) =
        writeLong(SyncStateKeys.pullAt(userId, table), value)

    suspend fun pushedSettingsJson(userId: String): String? =
        readString(SyncStateKeys.pushedSettingsJson(userId))

    suspend fun setPushedSettingsJson(userId: String, value: String) =
        writeString(SyncStateKeys.pushedSettingsJson(userId), value)

    suspend fun pushedNotebookText(userId: String): String? =
        readString(SyncStateKeys.pushedNotebookText(userId))

    suspend fun setPushedNotebookText(userId: String, value: String) =
        writeString(SyncStateKeys.pushedNotebookText(userId), value)

    /**
     * 清掉某个账号的**全部**簿记（水位线 + 基线）。
     *
     * 登出时调用：换账号后旧账号的水位线没有意义（新账号有自己的一套），
     * 留着只会让"新账号的旧数据拉不下来"这类问题以另一种形式复发。
     *
     * 用 `preferences.asMap()` 找出所有带 `@<userId>` 后缀的键一并删除——
     * DataStore 没有"按前缀批量删"的 API，只能自己扫。
     */
    suspend fun clearAccountScope(userId: String) {
        val suffix = "@$userId"
        store.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.endsWith(suffix) }
                .forEach { prefs.remove(it) }
        }
    }

    private suspend fun readLong(key: Preferences.Key<Long>): Long =
        store.data.first()[key] ?: 0L

    private suspend fun readString(key: Preferences.Key<String>): String? =
        store.data.first()[key]

    private suspend fun writeLong(key: Preferences.Key<Long>, value: Long) {
        store.edit { it[key] = value }
    }

    private suspend fun writeString(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }
}
