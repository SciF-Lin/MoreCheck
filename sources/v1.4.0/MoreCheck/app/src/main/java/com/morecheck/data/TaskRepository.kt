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
    private val timerDao: TimerDao,
    private val settings: SettingsRepository
) {
    val tasks: Flow<List<CheckTask>> = dao.observeTasks()
    val allRecords: Flow<List<CheckRecord>> = dao.observeAllRecords()

    /** 计时会话（走完一轮倒计时记一条），记录页「计时记录」卡消费。 */
    val timerSessions: Flow<List<TimerSession>> = timerDao.observeAll()

    suspend fun addTimerSession(startedAt: Long, endedAt: Long, durationMs: Long) {
        timerDao.insert(TimerSession(startedAt = startedAt, endedAt = endedAt, durationMs = durationMs))
    }

    /** 主页 / 小组件消费：未归档任务（v5）。 */
    val activeTasks: Flow<List<CheckTask>> = dao.observeActiveTasks()

    suspend fun addTask(
        name: String,
        icon: String,
        color: Int,
        reminderTime: String?,
        note: String? = null,
        groupName: String? = null,
        tags: String? = null
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
            updatedAt = now,
            groupName = groupName.normalizeNote(),
            tags = tags.normalizeNote()
        )
        return dao.insertTask(task)
    }

    /**
     * 只改**本机组织字段**（分组 / 标签 / 归档 / 暂停）时走这里。
     *
     * 与 [updateTask] 的区别：刻意**不刷新 `updatedAt`**。这四个字段不进云同步
     * （云端表没有对应列，见 `CloudSyncRepository.taskRow`），刷新 LWW 时间戳只会
     * 制造一次内容完全没变的云端 upsert，还可能让另一台设备误以为任务被改过。
     * 调用方传进来的实体其它字段必须与库里一致，本函数不做字段级合并。
     */
    suspend fun updateTaskLocalOnly(task: CheckTask) {
        dao.updateTask(task)
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

    /**
     * Backfill a check-in for a specific past date (补打卡)。
     *
     * ⚠️ v1.2.3 起支持指定当天打卡时刻：`checkedAt` 由调用方（日历页补打卡
     * 时间选择器）按「所补日期 + 所选 HH:mm」换算成 epoch 毫秒传入；
     * 默认值保留"补录那一刻"的旧语义，存量调用方（当前只有日历页）不受影响。
     *
     * `updatedAt` 仍取**当下**：它服务云同步的冲突判定，表达的是"这条记录
     * 什么时候被写入"，与用户主张的打卡时刻（`checkedAt`）是两码事。
     */
    suspend fun checkInOnDate(taskId: Long, date: LocalDate, checkedAt: Long = System.currentTimeMillis()) {
        dao.upsertRecord(
            CheckRecord(
                taskId = taskId,
                date = date.toString(),
                checkedAt = checkedAt,
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
