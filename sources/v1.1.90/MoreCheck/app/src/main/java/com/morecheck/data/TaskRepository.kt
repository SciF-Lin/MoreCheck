package com.morecheck.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 打卡数据的业务仓库。
 *
 * ## 云同步簿记（v3 起每个写入口的硬约束）
 *
 * 本仓库是 `check_tasks` / `check_records` 的**唯一写方**（同步引擎拉取回来的行
 * 也最终经由本仓库的 DAO 落库，但时间戳由引擎按 LWW 规则另给）。
 * 因此"改动一行就必须刷新它的 `updated_at`、删除一行就必须留下墓碑"这条不变式
 * 就落在本文件里——**新增写入口时务必同时维护这两样**，漏一处的后果是
 * 同步把用户的本地改动悄悄吞掉（或把删掉的行又从云端带回来）。
 *
 * 墓碑的生成集中在 [tombstoneTask] / [tombstoneRecord] 两个私有函数里，
 * 写入口只负责调用，不自己拼 rowKey。
 */
class TaskRepository(
    private val dao: CheckDao,
    private val settings: SettingsRepository
) {
    val tasks: Flow<List<CheckTask>> = dao.observeTasks()
    val allRecords: Flow<List<CheckRecord>> = dao.observeAllRecords()

    suspend fun addTask(
        name: String,
        icon: String,
        color: Int,
        reminderTime: String?,
        note: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val task = CheckTask(
            id = 0L,
            name = name.trim(),
            icon = icon,
            color = color,
            reminderTime = reminderTime,
            note = note.normalizeNote(),
            createdAt = now,
            // 新建即改动：与 createdAt 同值，保证首同步就能推上去。
            updatedAt = now
        )
        return dao.insertTask(task)
    }

    suspend fun updateTask(task: CheckTask) {
        // 刷新时间戳而不是沿用调用方传进来的值：编辑是"这一次改动"，
        // 用它旧的 updatedAt 会让 LWW 把这次编辑判输给任何后来发生的云端版本。
        dao.updateTask(task.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * 删除任务。
     *
     * 墓碑语义：
     * - **带历史删**：任务与它的每一条记录各留一条墓碑——记录是独立的行，
     *   云端的软删除不会级联（见 `CloudSyncRepository` 的推送段注释）；
     * - **不带历史删**：只留任务墓碑，记录继续存在并参与同步
     *   （它们引用一个已不存在的任务，界面按任务join后不可见，属可接受状态）。
     */
    suspend fun deleteTask(task: CheckTask, deleteHistory: Boolean) {
        if (deleteHistory) {
            dao.getRecordsForTask(task.id).forEach { record ->
                tombstoneRecord(record.taskId, record.date)
            }
        }
        tombstoneTask(task.id)
        if (deleteHistory) dao.deleteRecordsForTask(task.id)
        dao.deleteTask(task.id)
    }

    /** Check in. Same-day re-taps only update the timestamp (REPLACE strategy). */
    suspend fun checkIn(taskId: Long) {
        val now = ZonedDateTime.now()
        dao.upsertRecord(
            CheckRecord(
                taskId = taskId,
                date = now.toLocalDate().toString(),
                checkedAt = now.toInstant().toEpochMilli(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 撤销打卡：先留墓碑再删行。
     *
     * 顺序不能反——删了就取不回 (taskId, date) 之外的键信息，
     * 而墓碑的 rowKey 正是这两个字段（见 [SyncTombstone.recordKey]）。
     */
    suspend fun undoCheckIn(taskId: Long, date: LocalDate = LocalDate.now()) {
        val existing = dao.getRecord(taskId, date.toString()) ?: return
        tombstoneRecord(existing.taskId, existing.date)
        dao.deleteRecord(taskId, date.toString())
    }

    /** Backfill a check-in for a specific past date (补打卡). */
    suspend fun checkInOnDate(taskId: Long, date: LocalDate) {
        dao.upsertRecord(
            CheckRecord(
                taskId = taskId,
                date = date.toString(),
                checkedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getTask(taskId: Long): CheckTask? = dao.getTask(taskId)

    suspend fun getRecordsBetween(from: LocalDate, to: LocalDate): List<CheckRecord> =
        dao.getRecordsBetween(from.toString(), to.toString())

    suspend fun seedDefaultTaskIfNeeded() {
        if (!settings.defaultTaskSeeded.first()) {
            val now = System.currentTimeMillis()
            dao.insertTask(
                CheckTask(
                    id = 0L,
                    name = "早起",
                    icon = "🌅",
                    color = 0xFFFF9800.toInt(),
                    reminderTime = null,
                    note = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
            settings.setDefaultTaskSeeded()
        }
    }

    // ---- 墓碑（删除事件的同步载体）--------------------------------------------

    private suspend fun tombstoneTask(taskId: Long) {
        dao.insertTombstone(
            SyncTombstone(
                tableName = SyncTombstone.TABLE_TASKS,
                rowKey = SyncTombstone.taskKey(taskId),
                deletedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun tombstoneRecord(taskId: Long, date: String) {
        dao.insertTombstone(
            SyncTombstone(
                tableName = SyncTombstone.TABLE_RECORDS,
                rowKey = SyncTombstone.recordKey(taskId, date),
                deletedAt = System.currentTimeMillis()
            )
        )
    }
}

/**
 * 备注归一化：去掉首尾空白，空串一律存 `null`。
 *
 * 这样「填了又清空」和「从没填过」在数据库里是同一种状态，
 * 界面上判断 `note.isNullOrBlank()` 就够了，不必区分空串与 null。
 */
fun String?.normalizeNote(): String? = this?.trim()?.ifBlank { null }
