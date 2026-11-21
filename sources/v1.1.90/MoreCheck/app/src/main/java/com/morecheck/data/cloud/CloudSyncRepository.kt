package com.morecheck.data.cloud

import com.morecheck.data.AuthRepository
import com.morecheck.data.BackupData
import com.morecheck.data.BackupRepository
import com.morecheck.data.CheckDao
import com.morecheck.data.CheckRecord
import com.morecheck.data.CheckTask
import com.morecheck.data.GameSnapshot
import com.morecheck.data.NotebookStore
import com.morecheck.data.PendingRecord
import com.morecheck.data.SettingsRepository
import com.morecheck.data.SyncTombstone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/**
 * 一次同步的结果（界面据此反馈）。
 *
 * [message] 是**可直接展示给用户的一句话**：成功时带统计，失败时带原因。
 * 构造消息的规则集中在 [CloudSyncRepository.buildMessage]，不散到各调用点。
 */
data class SyncReport(
    val ok: Boolean,
    val message: String,
    val pushedTasks: Int = 0,
    val pushedRecords: Int = 0,
    val pulledTasks: Int = 0,
    val pulledRecords: Int = 0
)

/** 云端整包备份的摘要（"查看"用，不下载内容）。 */
data class CloudBackupInfo(
    val exists: Boolean,
    val exportedAt: Long = 0L,
    val taskCount: Int = 0,
    val recordCount: Int = 0,
    val notebookImageCount: Int = 0,
    val hasNotebookText: Boolean = false
)

/** 首次登录时的数据合并策略（弹窗三选一）。 */
enum class FirstLoginStrategy {
    /**
     * 上传本地数据。
     *
     * 与云端按修改时间合并：**云端更新的行不会被本地旧数据盖掉**
     * （推送前逐行比对云端 `updated_at`，云端更新则跳过该行，交由拉取处理）。
     */
    UPLOAD,

    /** 下载并覆盖：清空本机任务与记录后，用云端数据重新填充。 */
    DOWNLOAD,

    /** 暂不处理：只完成登录，不同步。 */
    SKIP
}

/**
 * 双轨云同步引擎。
 *
 * ## 两条轨道各自负责什么
 *
 * - **结构化轨道**（云数据库 5 张表）：任务、打卡记录、记事本文字、应用设置、
 *   游戏数据。增量推送 / 拉取，冲突按 `updated_at` 取晚者（LWW）。
 * - **整包备份轨道**（云存储一个 JSON + 若干图片）：`BackupData` 全量快照，
 *   手动"备份到云端 / 从云端恢复"，覆盖式语义——与本地文件备份同一套代码
 *   （[BackupRepository]），只是文件落在云端。
 *
 * 两轨**互不依赖**：结构化同步每天自动跑；整包备份只在用户点按钮时跑。
 * 记事本图片只走备份轨（字节太大，不适合结构化行），文字只走结构化轨
 * （要参与 LWW 合并）——一次编辑因此会有两个落点，这是刻意分工，不是重复。
 *
 * ## 水位线为什么按表分（而不是全局一个）
 *
 * 拉取按 `updated_at > 水位线` 过滤。若五张表共用一个全局水位线，会出现：
 * A 表查询时看到 T5 的行，而 B 表在 T4 有一行是在 A 表查询**之后**才提交的，
 * 全局水位线推进到 T5 后，B 表那行 T4 永远不会被拉到——**静默丢更新**。
 * 按表各记一个水位线即可避免（每表的水位线只推进到"该表这次确实看到的
 * 最大值"，任何晚于该表查询时刻写入的行都大于它）。
 *
 * 推送侧同理但只需一个水位线：推送查询发生在时刻 Q，只有 Q 之前存在的行
 * 会被推，它们的 `updated_at` 都 ≤ Q；Q 之后写入的行时间戳必然大于
 * "已推行的最大值"，下次一定被捞到。
 *
 * ## 时钟偏移的兜底
 *
 * 水位线是客户端时间戳，两台设备时钟不一致时会漏拉。拉取水位线因此
 * **回退 [PULL_MARGIN_MS]（5 分钟）**：重复拉到的行经 LWW 比对全是 no-op，
 * 代价是每次同步多拉几分钟内的行，收益是容忍 5 分钟内的设备间时钟差。
 *
 * ## ⚠️ 推送的 LWW 是**客户端条件写**（v3.1 起）
 *
 * 服务端的 `Prefer: resolution=merge-duplicates` 生成的是
 * `ON CONFLICT DO UPDATE SET …`（**没有 WHERE**）——直接用它就是"后写覆盖"，
 * 旧数据会把新数据盖掉。因此推送前的每张表都会先拉一次远端冲突索引
 * （键 → `updated_at`），逐行比对：
 *
 * - 远端没有这行 → 直接推；
 * - 远端 `updated_at` **≥** 本地 → **跳过**（云端更新，交给拉取阶段处理）；
 * - 远端 `updated_at` < 本地 → 推（本地更新，覆盖云端）。
 *
 * 墓碑同理：只有当远端那一行的 `updated_at` 不大于删除时刻时，才推删除标记
 * （否则会用旧删除盖掉别处更新的行）。
 */
class CloudSyncRepository(
    private val appContext: android.content.Context,
    private val dao: CheckDao,
    private val auth: CloudAuthClient,
    private val authRepository: AuthRepository,
    private val db: CloudDatabaseClient,
    private val storage: CloudStorageClient,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val syncState: SyncStateStore
) {

    // ---- 云表名与冲突目标（与建表 SQL 一致）----------------------------------
    private companion object {
        const val T_CLOUD_TASKS = "checkin_tasks"
        const val T_CLOUD_RECORDS = "checkin_records"
        const val T_CLOUD_NOTEBOOK = "notebook"
        const val T_CLOUD_SETTINGS = "app_settings"
        const val T_CLOUD_GAMES = "game_data"

        const val CONFLICT_TASKS = "owner_id,local_id"
        const val CONFLICT_RECORDS = "owner_id,task_local_id,date"
        const val CONFLICT_NOTEBOOK = "owner_id"
        const val CONFLICT_SETTINGS = "owner_id"
        const val CONFLICT_GAMES = "owner_id,game_key"

        /** 每批 upsert 的行数上限（PostgREST 单请求体别太大）。 */
        const val BATCH = 200

        /** 拉取水位线回退量，见类注释「时钟偏移的兜底」。 */
        const val PULL_MARGIN_MS = 5 * 60 * 1000L

        /** 空游戏快照视为"无数据"：不推、不应用（见 [pushGames] / [pullGames]）。 */
        const val EMPTY_GAME_JSON = "{}"
    }

    /** 界面订阅的同步状态。 */
    val state = syncState.state

    /** 一次性读当前同步状态（Worker 启动时判断要不要跑）。 */
    suspend fun current(): SyncState = syncState.current()

    // =========================================================================
    // 对外入口
    // =========================================================================

    /** 完整同步：先推后拉。自动同步（每日 / 打卡后）与手动按钮都走它。 */
    suspend fun syncNow(): SyncReport = guardedSync { pushInternal(); pullInternal() }

    /** 只推不拉（首次登录选「上传」、打卡后轻量推送）。 */
    suspend fun pushOnly(): SyncReport = guardedSync { pushInternal() }

    /** 只拉不推（首次登录选「下载」在清空本机后走全量拉取）。 */
    suspend fun pullOnly(): SyncReport = guardedSync { pullInternal() }

    /**
     * 打卡后的轻量推送（尽力而为，失败静默）。
     *
     * 与 [syncNow] 的区别：不拉取（刚打卡完不需要回填）、失败不写同步状态
     * （避免把"每日同步失败"的提示一次打卡就刷掉）。设置里可关。
     *
     * ⚠️ 整段都在保护里：`auth.validToken()` 内部会触发令牌刷新，而刷新在
     * **网络类失败时是抛异常**的——早前这一行写在 `runCatching` 之外，
     * 一次网络抖动就能让打卡流程崩掉（异常冒到 ViewModel 的 launch 里）。
     */
    suspend fun pushAfterCheckIn() {
        try {
            if (!syncState.current().syncAfterCheckIn) return
            if (auth.validToken() == null) return
            pushInternal()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 静默失败：打卡不该因为同步出错而失败。
        }
    }

    /**
     * 首次登录的合并策略。
     *
     * - [FirstLoginStrategy.UPLOAD]：只推（LWW 合并，云端更新的行不会被本地旧数据盖掉）；
     * - [FirstLoginStrategy.DOWNLOAD]：先清空本机任务与记录（**不留墓碑**——
     *   留了会在下次推送时把刚拉回来的行又删掉），再把拉取水位线归零后全量拉取；
     * - [FirstLoginStrategy.SKIP]：什么都不做。
     */
    suspend fun applyFirstLoginStrategy(strategy: FirstLoginStrategy): SyncReport {
        if (strategy == FirstLoginStrategy.SKIP) {
            // 什么都不做，也**不写**同步状态——「上次同步」行的语义是"最近一次
            // 真实同步"，登录但不合并不该把它刷掉。
            return SyncReport(ok = true, message = "已登录，未同步数据")
        }
        return guardedSync {
            when (strategy) {
                FirstLoginStrategy.UPLOAD -> {
                    pushInternal()
                }

                FirstLoginStrategy.DOWNLOAD -> {
                    // 清空本机（无墓碑）：接下来云端就是唯一事实来源。
                    dao.deleteAllRecords()
                    dao.deleteAllTasks()
                    dao.clearTombstones()
                    dao.clearPendingRecords()
                    // 水位线归零 = 全量拉取。设置 / 记事本 / 游戏的水位线同样归零，
                    // 让它们也有机会覆盖到本机（各自仍有"本地有未上传改动则不覆盖"
                    // 的保护，见 pullNotebook / pullSettings）。
                    resetPullWatermarks()
                    pullInternal()
                }

                FirstLoginStrategy.SKIP -> Unit
            }
        }
    }

    // =========================================================================
    // 同步外壳（统一异常翻译）
    // =========================================================================

    /**
     * 所有对外同步入口的公共外壳：登录校验 + 异常翻译 + 写同步状态。
     *
     * 抽出来是因为四个入口的 try/catch 块完全一样，复制四份的代价是
     * "改一处忘三处"——而这里恰恰是数据正确性的最后一道闸。
     */
    private suspend fun guardedSync(block: suspend () -> Unit): SyncReport {
        val startedAt = System.currentTimeMillis()
        // 每次同步从零开始计数：只推不拉 / 只拉不推的入口不能带上上一轮的数字。
        lastPushCounts = PushCounts()
        lastPullCounts = PullCounts()
        return try {
            requireLogin()
            block()
            val report = SyncReport(
                ok = true,
                message = "数据已是最新",
                pushedTasks = lastPushCounts.tasks,
                pushedRecords = lastPushCounts.records,
                pulledTasks = lastPullCounts.tasks,
                pulledRecords = lastPullCounts.records
            )
            val message = buildMessage(lastPushCounts, lastPullCounts)
            syncState.recordSyncResult(startedAt, ok = true, message = message)
            report.copy(message = message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudException) {
            failSync(startedAt, friendlyMessage(e.error))
        } catch (e: Exception) {
            failSync(startedAt, "同步失败：${e.message ?: "未知错误"}")
        }
    }

    // =========================================================================
    // 整包备份轨道（云存储）
    // =========================================================================

    /**
     * 备份到云端：一个 JSON（`backup/latest.json`）+ 记事本图片（`backup/images/`）。
     *
     * 同名覆盖（单槽位，不做历史列表）："备份"对用户来说就是"云端那份最新的"，
     * 多份历史反而会让人纠结恢复哪份。云端里已不存在于本次备份的图片会被清掉。
     */
    suspend fun uploadFullBackup(): CloudBackupInfo {
        requireLogin()
        val base = backupBasePath()
        val data = backupRepository.export()

        storage.upload("$base/latest.json", data.toJson().toByteArray(Charsets.UTF_8))

        val noteDir = File(appContext.filesDir, "notebook")
        val uploaded = mutableSetOf<String>()
        data.notebookImages.forEach { name ->
            val file = File(noteDir, name)
            if (file.isFile) {
                storage.upload(
                    path = "$base/images/$name",
                    bytes = file.readBytes(),
                    contentType = "image/jpeg"
                )
                uploaded += name
            }
        }
        // 清理云端多出来的图片（用户在本地删了某张笔记图，又点了备份）。
        val remoteNames = storage.list("$base/images/")
            .map { it.key.substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .toSet()
        val stale = remoteNames - uploaded
        if (stale.isNotEmpty()) {
            storage.remove(stale.map { "$base/images/$it" })
        }

        return CloudBackupInfo(
            exists = true,
            exportedAt = data.exportedAt,
            taskCount = data.tasks.size,
            recordCount = data.records.size,
            notebookImageCount = data.notebookImages.size,
            hasNotebookText = !data.notebookText.isNullOrEmpty()
        )
    }

    /** 查看云端备份摘要（只下载 JSON，不落盘）。没有备份时 [CloudBackupInfo.exists] 为 false。 */
    suspend fun fetchBackupInfo(): CloudBackupInfo {
        requireLogin()
        val path = "${backupBasePath()}/latest.json"
        val bytes = try {
            storage.download(path)
        } catch (e: CloudException) {
            if (e.error.kind == CloudError.Kind.NOT_FOUND) {
                return CloudBackupInfo(exists = false)
            }
            throw e
        }
        val data = BackupData.fromJson(String(bytes, Charsets.UTF_8))
        return CloudBackupInfo(
            exists = true,
            exportedAt = data.exportedAt,
            taskCount = data.tasks.size,
            recordCount = data.records.size,
            notebookImageCount = data.notebookImages.size,
            hasNotebookText = !data.notebookText.isNullOrEmpty()
        )
    }

    /**
     * 从云端恢复整包备份（覆盖式）。
     *
     * 图片先下载到私有目录再走 [BackupRepository.restore]——恢复路径只认本地文件，
     * 这样"本地文件备份"与"云端备份"恢复后得到完全相同的数据形态。
     *
     * 恢复后同步"记事本已推送基线"：记事本文字同时存在于结构化轨道与整包备份
     * 轨道（见类注释），恢复动作把本地文字换成了备份里的那一版，若不更新基线，
     * 下次同步会把它当成"本地未上传的改动"又推回云端。
     */
    suspend fun restoreFullBackup(): Pair<Int, Int> {
        requireLogin()
        val base = backupBasePath()
        val bytes = storage.download("$base/latest.json")
        val data = BackupData.fromJson(String(bytes, Charsets.UTF_8))

        val noteDir = File(appContext.filesDir, "notebook")
        data.notebookImages.forEach { name ->
            val target = File(noteDir, name)
            if (!target.isFile) {
                runCatching { target.writeBytes(storage.download("$base/images/$name")) }
            }
        }
        val result = backupRepository.restore(data)
        // 基线对齐：恢复后的本地内容就是"云端备份的内容"，视作已同步。
        runCatching { syncState.setPushedNotebookText(currentUserId(), NotebookStore.read(appContext)?.text.orEmpty()) }
        return result
    }

    // =========================================================================
    // 推送
    // =========================================================================

    /** 一次推送的统计（按表）。 */
    private data class PushCounts(val tasks: Int = 0, val records: Int = 0, val other: Int = 0)

    /** 一次拉取的统计（按表）。 */
    private data class PullCounts(val tasks: Int = 0, val records: Int = 0, val deletes: Int = 0)

    /** 上一轮的推送 / 拉取统计（供 [guardedSync] 组装消息）。 */
    private var lastPushCounts = PushCounts()
    private var lastPullCounts = PullCounts()

    private suspend fun pushInternal() {
        val userId = currentUserId()
        val watermark = syncState.lastPushAt(userId)
        val tombstoneWatermark = syncState.lastTombstonePushAt(userId)
        var maxPushed = watermark
        var tasks = 0
        var records = 0
        var other = 0

        // 远端冲突索引：键 → 远端 updated_at。用于 LWW 判定（见类注释）。
        val remoteTasks = remoteUpdatedAtIndex(T_CLOUD_TASKS) { it.optLong("local_id", 0L).toString() }
        val remoteRecords = remoteUpdatedAtIndex(T_CLOUD_RECORDS) { recordKeyOf(it) }
        val remoteGames = remoteUpdatedAtIndex(T_CLOUD_GAMES) { it.optString("game_key", "") }

        // ---- 任务 ----
        val taskRows = dao.getTasksUpdatedAfter(watermark)
            .map { taskRow(it) }
            .filter { row -> winsLocally(row, remoteTasks) }
        if (taskRows.isNotEmpty()) {
            maxPushed = maxOf(maxPushed, upsertChunked(T_CLOUD_TASKS, taskRows, CONFLICT_TASKS))
            tasks = taskRows.size
        }

        // ---- 记录 ----
        val recordRows = dao.getRecordsUpdatedAfter(watermark)
            .map { recordRow(it) }
            .filter { row -> winsLocally(row, remoteRecords) }
        if (recordRows.isNotEmpty()) {
            maxPushed = maxOf(maxPushed, upsertChunked(T_CLOUD_RECORDS, recordRows, CONFLICT_RECORDS))
            records = recordRows.size
        }

        // ---- 墓碑（删除事件）----
        // 只推墓碑水位线之后的墓碑：更早的必然已推送过。
        //
        // 墓碑用**独立水位线**（与行的 updated_at 水位线分开）：两者语义不同
        // （删除时刻 vs 内容改动时刻），共用一条会让"从未推送过"的墓碑
        // 被误判为"已推"而丢弃 → 删除事件丢失。
        val tombstones = dao.getTombstones().filter { it.deletedAt > tombstoneWatermark }
        val pushedTombstones = mutableListOf<SyncTombstone>()
        var maxTombstone = tombstoneWatermark
        tombstones.forEach { tomb ->
            val remoteAt = when (tomb.tableName) {
                SyncTombstone.TABLE_TASKS ->
                    remoteTasks[tomb.rowKey.toLongOrNull()?.toString() ?: ""]
                SyncTombstone.TABLE_RECORDS ->
                    remoteRecords[tomb.rowKey]
                else -> null
            }
            // 远端没有这行 → 云端本来就不存在，无需再发删除标记。
            // 远端比删除时刻还新 → 别的设备更新过这行，按 LWW 它赢，不发删除。
            if (remoteAt == null) {
                pushedTombstones += tomb
                return@forEach
            }
            if (remoteAt <= tomb.deletedAt) {
                val row = tombstoneRow(tomb) ?: return@forEach
                val target = if (tomb.tableName == SyncTombstone.TABLE_TASKS) {
                    T_CLOUD_TASKS to CONFLICT_TASKS
                } else {
                    T_CLOUD_RECORDS to CONFLICT_RECORDS
                }
                upsertChunked(target.first, listOf(row), target.second)
                pushedTombstones += tomb
                maxTombstone = maxOf(maxTombstone, tomb.deletedAt)
            }
        }

        // ---- 记事本（只推文字）----
        val noteText = NotebookStore.read(appContext)?.text.orEmpty()
        val pushedNote = syncState.pushedNotebookText(userId)
        if (noteText != pushedNote) {
            val now = System.currentTimeMillis()
            val remoteAt = remoteSingleUpdatedAt(T_CLOUD_NOTEBOOK)
            // 远端更新 → 跳过推送（交给拉取阶段裁决），不更新基线。
            if (remoteAt == null || remoteAt <= now) {
                db.upsert(
                    table = T_CLOUD_NOTEBOOK,
                    rows = listOf(
                        JSONObject().apply {
                            put("text", noteText)
                            put("updated_at", now)
                        }
                    ),
                    onConflict = CONFLICT_NOTEBOOK
                )
                syncState.setPushedNotebookText(userId, noteText)
                other++
            }
        }

        // ---- 应用设置 ----
        val settingsJson = buildSettingsJson()
        val pushedSettings = syncState.pushedSettingsJson(userId)
        if (settingsJson != pushedSettings) {
            val now = System.currentTimeMillis()
            val remoteAt = remoteSingleUpdatedAt(T_CLOUD_SETTINGS)
            if (remoteAt == null || remoteAt <= now) {
                db.upsert(
                    table = T_CLOUD_SETTINGS,
                    rows = listOf(settingsRow(settingsJson, now)),
                    onConflict = CONFLICT_SETTINGS
                )
                syncState.setPushedSettingsJson(userId, settingsJson)
                other++
            }
        }

        // ---- 游戏数据 ----
        val gameWatermark = syncState.lastGamePushAt(userId)
        val gameRows = dao.getGameSnapshots()
            .filter { it.updatedAt > gameWatermark && it.dataJson != EMPTY_GAME_JSON }
            .map {
                JSONObject().apply {
                    put("game_key", it.gameKey)
                    put("data_json", it.dataJson)
                    put("updated_at", it.updatedAt)
                }
            }
            .filter { row -> winsLocally(row, remoteGames) }
        if (gameRows.isNotEmpty()) {
            upsertChunked(T_CLOUD_GAMES, gameRows, CONFLICT_GAMES)
            other += gameRows.size
            // 游戏行有自己的推送水位线（见 [SyncStateKeys.lastGamePushAt] 的注释），
            // 推成功后推进到本批最大时间戳——不推进的话每轮都会全量重推游戏行。
            syncState.setLastGamePushAt(
                currentUserId(),
                gameRows.maxOf { it.optLong("updated_at", 0L) }
            )
        }

        // 只删除**确实推成功**的墓碑：解析失败或被 LWW 判负的墓碑留在本地，
        // 下轮还能重试（早前是无条件删除 → 删除事件直接丢失）。
        pushedTombstones.forEach { dao.deleteTombstone(it.tableName, it.rowKey) }
        if (pushedTombstones.isNotEmpty()) {
            syncState.setLastTombstonePushAt(userId, maxTombstone)
        }

        // 水位线**整轮只写一次**（早前每批 upsert 都写一次 DataStore，放大严重）。
        if (maxPushed > watermark) {
            syncState.setLastPushAt(userId, maxPushed)
        }
        lastPushCounts = PushCounts(tasks = tasks, records = records, other = other)
    }

    private fun taskRow(task: CheckTask): JSONObject = JSONObject().apply {
        put("local_id", task.id)
        put("name", task.name)
        put("icon", task.icon)
        put("color", task.color)
        put("reminder_time", task.reminderTime ?: JSONObject.NULL)
        put("note", task.note ?: JSONObject.NULL)
        put("created_at", task.createdAt)
        put("updated_at", task.updatedAt)
        // archived / deleted 不发送：本应用没有"归档"概念，deleted 恒 false
        // （删除走墓碑）。缺列在 upsert-merge 下不会动到云端原值。
    }

    private fun recordRow(record: CheckRecord): JSONObject = JSONObject().apply {
        put("task_local_id", record.taskId)
        put("date", record.date)
        put("checked_at", record.checkedAt)
        put("updated_at", record.updatedAt)
    }

    /** 墓碑 → 云端行（`deleted = true` 的 upsert）；rowKey 解析不出来返回 null。 */
    private fun tombstoneRow(tomb: SyncTombstone): JSONObject? = when (tomb.tableName) {
        SyncTombstone.TABLE_TASKS -> {
            val id = tomb.rowKey.toLongOrNull() ?: return null
            JSONObject().apply {
                put("local_id", id)
                put("deleted", true)
                put("updated_at", tomb.deletedAt)
            }
        }

        SyncTombstone.TABLE_RECORDS -> {
            val parts = tomb.rowKey.split('|')
            val taskId = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val date = parts.getOrNull(1) ?: return null
            JSONObject().apply {
                put("task_local_id", taskId)
                put("date", date)
                put("deleted", true)
                put("updated_at", tomb.deletedAt)
            }
        }

        else -> null
    }

    /** 记录行的冲突键（与 `SyncTombstone.recordKey` 同格式）。 */
    private fun recordKeyOf(row: JSONObject): String =
        "${row.optLong("task_local_id", 0L)}|${row.optString("date", "")}"

    // =========================================================================
    // 拉取
    // =========================================================================

    private suspend fun pullInternal() {
        val userId = currentUserId()
        var tasks = 0
        var records = 0
        var deletes = 0

        // ---- 任务（先拉任务：记录要做"任务存在才应用"的判断）----
        val taskWatermark = syncState.pullAt(userId, PullTable.TASKS)
        val taskRows = db.select(
            table = T_CLOUD_TASKS,
            filters = mapOf("updated_at" to "gt.$taskWatermark"),
            order = "updated_at.asc"
        )
        if (taskRows.isNotEmpty()) {
            val localTasks = dao.getAllTasks().associateBy { it.id }
            var maxSeen = taskWatermark
            taskRows.forEach { row ->
                val updatedAt = row.optLong("updated_at", 0L)
                maxSeen = maxOf(maxSeen, updatedAt)
                val localId = row.optLong("local_id", 0L)
                val local = localTasks[localId]
                if (row.optBoolean("deleted", false)) {
                    // 云端删除更新（或同时刻）才生效；本地有更新的改动则保留，下次推送赢回来。
                    if (local != null && local.updatedAt <= updatedAt) {
                        // 任务的记录会被 FK 级联删掉，逐条补墓碑让删除同步出去。
                        dao.getRecordsForTask(localId).forEach { record ->
                            dao.insertTombstone(
                                SyncTombstone(
                                    tableName = SyncTombstone.TABLE_RECORDS,
                                    rowKey = SyncTombstone.recordKey(record.taskId, record.date),
                                    deletedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        dao.deleteTask(localId)
                        deletes++
                    }
                } else if (local == null || local.updatedAt < updatedAt) {
                    val task = CheckTask(
                        id = localId,
                        name = row.optString("name", ""),
                        icon = row.optString("icon", ""),
                        color = row.optInt("color", 0),
                        reminderTime = row.optStringOrNull("reminder_time"),
                        note = row.optStringOrNull("note"),
                        createdAt = row.optLong("created_at", updatedAt),
                        updatedAt = updatedAt
                    )
                    dao.insertTasksWithIds(listOf(task))
                    tasks++
                }
            }
            advancePullWatermark(userId, PullTable.TASKS, maxSeen)
        }

        // 任务到位后，把之前"找不到归属任务"的记录补应用（见 pullRecords 的暂存逻辑）。
        records += applyPendingRecords()

        // ---- 记录 ----
        val recordWatermark = syncState.pullAt(userId, PullTable.RECORDS)
        val recordRows = db.select(
            table = T_CLOUD_RECORDS,
            filters = mapOf("updated_at" to "gt.$recordWatermark"),
            order = "updated_at.asc"
        )
        if (recordRows.isNotEmpty()) {
            val liveTaskIds = dao.getAllTasks().map { it.id }.toSet()
            val localRecords = dao.getAllRecords().associateBy {
                SyncTombstone.recordKey(it.taskId, it.date)
            }
            var maxSeen = recordWatermark
            recordRows.forEach { row ->
                val updatedAt = row.optLong("updated_at", 0L)
                maxSeen = maxOf(maxSeen, updatedAt)
                val taskId = row.optLong("task_local_id", 0L)
                val date = row.optString("date", "")
                val key = SyncTombstone.recordKey(taskId, date)
                val local = localRecords[key]
                if (row.optBoolean("deleted", false)) {
                    if (local != null && local.updatedAt <= updatedAt) {
                        dao.deleteRecord(taskId, date)
                        deletes++
                    }
                } else if (taskId in liveTaskIds) {
                    if (local == null || local.updatedAt < updatedAt) {
                        dao.upsertRecord(
                            CheckRecord(
                                taskId = taskId,
                                date = date,
                                checkedAt = row.optLong("checked_at", updatedAt),
                                updatedAt = updatedAt
                            )
                        )
                        records++
                    }
                } else {
                    // 任务本机还没有（云端任务还没同步过来 / 本机 id 撞号）：
                    // **不能丢**——丢了这笔打卡就永远补不回来。暂存到本地
                    // pending 表，等任务出现后再应用。
                    dao.insertPendingRecord(
                        PendingRecord(
                            taskLocalId = taskId,
                            date = date,
                            checkedAt = row.optLong("checked_at", updatedAt),
                            updatedAt = updatedAt
                        )
                    )
                }
            }
            advancePullWatermark(userId, PullTable.RECORDS, maxSeen)
        }

        // ---- 记事本 ----
        pullNotebook(userId)

        // ---- 应用设置 ----
        pullSettings(userId)

        // ---- 游戏数据 ----
        pullGames(userId)

        lastPullCounts = PullCounts(tasks = tasks, records = records, deletes = deletes)
    }

    /**
     * 记事本拉取：**本地有未上传的改动就不覆盖**。
     *
     * 判断办法是拿当前文字与"已推送基线"比较：不一致说明用户改过但还没推，
     * 此时云端版本再新也先让本地赢（下次推送会以当前时间戳发上去）。
     * 没有未上传改动且云端更新时，才写回本地并更新基线。
     *
     * ⚠️ **跳过应用时绝不推进水位线**：早前无论是否应用都推进，导致
     * "云端那一版被跳过 + 水位线推过去" = 该更新永久丢失。
     */
    private suspend fun pullNotebook(userId: String) {
        val watermark = syncState.pullAt(userId, PullTable.NOTEBOOK)
        val rows = db.select(
            table = T_CLOUD_NOTEBOOK,
            filters = mapOf("updated_at" to "gt.$watermark"),
            order = "updated_at.asc"
        )
        var maxSeen = watermark
        var blocked = false
        rows.forEach { row ->
            if (blocked) return@forEach
            val updatedAt = row.optLong("updated_at", 0L)
            val cloudText = row.optString("text", "")
            val localText = NotebookStore.read(appContext)?.text.orEmpty()
            val hasUnsentLocal = localText != syncState.pushedNotebookText(userId)
            if (!hasUnsentLocal) {
                NotebookStore.saveText(appContext, cloudText)
                syncState.setPushedNotebookText(userId, cloudText)
                maxSeen = maxOf(maxSeen, updatedAt)
            } else {
                // 本地有未上传的改动 → 这一版先不应用，水位线也不推。
                blocked = true
            }
        }
        if (!blocked) advancePullWatermark(userId, PullTable.NOTEBOOK, maxSeen)
    }

    /**
     * 应用设置拉取：与记事本同一条"本地有未上传改动就不覆盖"的保护。
     *
     * 基线写的是**应用后本地的实际序列化**（而不是云端那一行的反推）：
     * 云端 null 字段不会覆盖本地值，若基线写成云端版就会恒不等于本地实际值，
     * 于是"本地有未上传改动"永远成立 → 云端设置永远拉不下来。
     */
    private suspend fun pullSettings(userId: String) {
        val watermark = syncState.pullAt(userId, PullTable.SETTINGS)
        val rows = db.select(
            table = T_CLOUD_SETTINGS,
            filters = mapOf("updated_at" to "gt.$watermark"),
            order = "updated_at.asc"
        )
        var maxSeen = watermark
        var blocked = false
        rows.forEach { row ->
            if (blocked) return@forEach
            val updatedAt = row.optLong("updated_at", 0L)
            val hasUnsentLocal = buildSettingsJson() != syncState.pushedSettingsJson(userId)
            if (!hasUnsentLocal) {
                applySettingsRow(row)
                // 基线 = 应用后本地实际值
                syncState.setPushedSettingsJson(userId, buildSettingsJson())
                maxSeen = maxOf(maxSeen, updatedAt)
            } else {
                blocked = true
            }
        }
        if (!blocked) advancePullWatermark(userId, PullTable.SETTINGS, maxSeen)
    }

    /**
     * 游戏数据拉取：把更新的云端快照写进本地镜像表，并标记 `applied = false`——
     * 游戏页下次打开时据此把快照灌进 WebView 的 localStorage（见 `GameDataBridge`）。
     *
     * 空快照（`{}`）直接忽略：它表示"那台设备上没有这个游戏的数据"，
     * 不该把本机玩出来的数据抹掉。
     */
    private suspend fun pullGames(userId: String) {
        val watermark = syncState.pullAt(userId, PullTable.GAMES)
        val rows = db.select(
            table = T_CLOUD_GAMES,
            filters = mapOf("updated_at" to "gt.$watermark"),
            order = "updated_at.asc"
        )
        var maxSeen = watermark
        val local = dao.getGameSnapshots().associateBy { it.gameKey }
        rows.forEach { row ->
            val updatedAt = row.optLong("updated_at", 0L)
            maxSeen = maxOf(maxSeen, updatedAt)
            val gameKey = row.optString("game_key", "")
            if (gameKey.isBlank()) return@forEach
            val dataJson = row.optString("data_json", EMPTY_GAME_JSON)
            if (dataJson == EMPTY_GAME_JSON) return@forEach
            val existing = local[gameKey]
            if (existing == null || existing.updatedAt < updatedAt) {
                dao.upsertGameSnapshot(
                    GameSnapshot(
                        gameKey = gameKey,
                        dataJson = dataJson,
                        updatedAt = updatedAt,
                        // 待灌入：本地从没有过这份快照，或云端是更新的版本。
                        applied = false
                    )
                )
            }
        }
        advancePullWatermark(userId, PullTable.GAMES, maxSeen)
    }

    /**
     * 把"待归属"的记录应用回本地（它们的任务已经到齐了）。
     *
     * @return 实际应用的条数。
     */
    private suspend fun applyPendingRecords(): Int {
        val pending = dao.getPendingRecords()
        if (pending.isEmpty()) return 0
        val liveTaskIds = dao.getAllTasks().map { it.id }.toSet()
        var applied = 0
        pending.forEach { item ->
            if (item.taskLocalId !in liveTaskIds) return@forEach
            val local = dao.getRecord(item.taskLocalId, item.date)
            if (local == null || local.updatedAt < item.updatedAt) {
                dao.upsertRecord(
                    CheckRecord(
                        taskId = item.taskLocalId,
                        date = item.date,
                        checkedAt = item.checkedAt,
                        updatedAt = item.updatedAt
                    )
                )
                applied++
            }
            dao.deletePendingRecord(item.taskLocalId, item.date)
        }
        return applied
    }

    private suspend fun advancePullWatermark(userId: String, table: PullTable, maxSeen: Long) {
        // 回退 margin 容忍设备间时钟差（重复拉到的行经 LWW 全是 no-op）。
        val safe = maxOf(0L, maxSeen - PULL_MARGIN_MS)
        val current = syncState.pullAt(userId, table)
        if (safe > current) syncState.setPullAt(userId, table, safe)
    }

    private suspend fun resetPullWatermarks() {
        val userId = currentUserId()
        PullTable.entries.forEach { syncState.setPullAt(userId, it, 0L) }
    }

    // =========================================================================
    // 设置序列化（本地 ↔ 云端 ↔ 基线比较，三处共用同一格式）
    // =========================================================================

    /** 当前设置的标准序列化（键顺序固定，字符串比较才可靠）。 */
    private suspend fun buildSettingsJson(): String {
        val json = JSONObject().apply {
            put("nickname", authRepository.nickname.first() ?: JSONObject.NULL)
            put("gender", authRepository.gender.first() ?: JSONObject.NULL)
            put("theme_mode", settingsRepository.themeMode.first())
            put("theme_accent", settingsRepository.themeAccent.first())
        }
        return json.toString()
    }

    /** 本地设置 → 云端行（updatedAt 由调用方给）。 */
    private fun settingsRow(settingsJson: String, updatedAt: Long): JSONObject {
        val json = JSONObject(settingsJson)
        return JSONObject().apply {
            put("nickname", json.optOrNull("nickname"))
            put("gender", json.optOrNull("gender"))
            put("theme_mode", json.optString("theme_mode", ""))
            put("theme_accent", json.optString("theme_accent", ""))
            put("updated_at", updatedAt)
        }
    }

    /** 把云端设置行应用到本地仓库。只在确有值时写，避免用 null 抹掉本地。 */
    private suspend fun applySettingsRow(row: JSONObject) {
        row.optStringOrNull("nickname")?.let { settingsRepository.setNickname(it) }
        row.optStringOrNull("gender")?.let { settingsRepository.setGender(it) }
        row.optStringOrNull("theme_mode")?.let { settingsRepository.setThemeMode(it) }
        row.optStringOrNull("theme_accent")?.let { settingsRepository.setThemeAccent(it) }
    }

    // =========================================================================
    // 工具
    // =========================================================================

    private suspend fun requireLogin() {
        if (auth.validToken() == null) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.UNAUTHENTICATED,
                    message = "请先登录后再同步",
                    code = null,
                    status = 401
                )
            )
        }
    }

    /**
     * 当前云账号的**命名空间 ID**。
     *
     * 水位线按它隔离（见 `SyncStateStore`）。取不到时用固定占位符——
     * 同步入口都会先 `requireLogin()`，正常路径不会走到占位符。
     */
    private suspend fun currentUserId(): String {
        val session = auth.session.value
        val id = session?.userId?.takeIf { it.isNotBlank() }
            ?: session?.phone?.takeIf { it.isNotBlank() }
        return id ?: "unknown"
    }

    private suspend fun backupBasePath(): String {
        requireLogin()
        val userId = auth.session.value?.userId
            ?: cloudFail(
                CloudError(
                    kind = CloudError.Kind.UNAUTHENTICATED,
                    message = "登录状态异常，请重新登录",
                    code = null,
                    status = 401
                )
            )
        return "users/$userId/backup"
    }

    /**
     * 分批 upsert。返回本批全部行里最大的 `updated_at`（调用方统一推进水位线）。
     *
     * ⚠️ 这里**不写 DataStore**：早前每批回调一次写盘，放大严重。
     * 改成整轮结束后由调用方写一次。
     */
    private suspend fun upsertChunked(
        table: String,
        rows: List<JSONObject>,
        onConflict: String
    ): Long {
        var maxAt = 0L
        rows.chunked(BATCH).forEach { chunk ->
            db.upsert(table = table, rows = chunk, onConflict = onConflict)
            maxAt = maxOf(maxAt, chunk.maxOf { it.optLong("updated_at", 0L) })
        }
        return maxAt
    }

    /**
     * 拉一张表的远端冲突索引：冲突键 → 远端 `updated_at`。
     *
     * 只取**本轮要推的行涉及的键**——用 `updated_at` 下界粗筛一次全表即可
     * （个人应用数据量小），换来的是推送前的 LWW 判定。
     */
    private suspend fun remoteUpdatedAtIndex(
        table: String,
        keyOf: (JSONObject) -> String
    ): Map<String, Long> {
        val rows = db.select(table = table, order = "updated_at.asc")
        val map = HashMap<String, Long>(rows.size)
        rows.forEach { map[keyOf(it)] = it.optLong("updated_at", 0L) }
        return map
    }

    /** 单行表（记事本 / 设置）的远端 `updated_at`；没有行返回 null。 */
    private suspend fun remoteSingleUpdatedAt(table: String): Long? =
        db.select(table = table, limit = 1).firstOrNull()?.optLong("updated_at", 0L)

    /**
     * LWW 判定：本地这一行能不能推。
     *
     * 远端没有 → 能；远端 `updated_at` ≥ 本地 → **不能**（云端更新，交给拉取）；
     * 远端更旧 → 能。
     */
    private fun winsLocally(row: JSONObject, remote: Map<String, Long>): Boolean {
        val key = keyOfRow(row)
        val remoteAt = remote[key] ?: return true
        return row.optLong("updated_at", 0L) > remoteAt
    }

    /** 行 → 冲突键（按表不同：任务用 local_id，记录用 task|date，游戏用 game_key）。 */
    private fun keyOfRow(row: JSONObject): String {
        if (row.has("game_key")) return row.optString("game_key", "")
        if (row.has("task_local_id")) return recordKeyOf(row)
        return row.optLong("local_id", 0L).toString()
    }

    private fun buildMessage(push: PushCounts, pull: PullCounts): String {
        val parts = mutableListOf<String>()
        val pushed = push.tasks + push.records
        if (pushed > 0) parts += "上传 ${push.tasks} 个任务、${push.records} 条记录"
        val pulled = pull.tasks + pull.records
        if (pulled > 0) parts += "下载 ${pull.tasks} 个任务、${pull.records} 条记录"
        if (pull.deletes > 0) parts += "同步删除 ${pull.deletes} 项"
        return if (parts.isEmpty()) "数据已是最新" else parts.joinToString("；")
    }

    private suspend fun failSync(startedAt: Long, message: String): SyncReport {
        syncState.recordSyncResult(startedAt, ok = false, message = message)
        return SyncReport(ok = false, message = message)
    }

    /** 云端错误 → 用户可读的中文原因（措辞与界面共用一份，见 [friendlyCloudMessage]）。 */
    private fun friendlyMessage(error: CloudError): String = friendlyCloudMessage(error)
}

/** 取字符串字段；JSON null 与缺失都返回 null（`optString` 会把两者都变成 ""）。 */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").ifBlank { null }

/** 取任意字段；JSON null 与缺失都返回 [JSONObject.NULL]（供原样放进新 JSON）。 */
private fun JSONObject.optOrNull(key: String): Any =
    if (isNull(key)) JSONObject.NULL else opt(key)
