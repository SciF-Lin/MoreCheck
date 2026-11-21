package com.morecheck.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「待归属」的打卡记录（v4 新增）。
 *
 * ## 为什么需要这张表
 *
 * 双向同步时，打卡记录与任务**没有到达顺序保证**：云端可能先返回某条打卡记录，
 * 而它所属的任务还在路上。Room 的外键约束（`CheckRecord.taskId` → `CheckTask.id`）
 * 会直接拒绝悬空的打卡行——如果只是 try/catch 吞掉，**这笔打卡就永久丢了**，
 * 且没有任何报错（对用户来说就是"那天的打卡莫名消失了"）。
 *
 * 本表是这些记录的**暂存区**：拉到打卡记录而任务未到时写入这里；
 * 每轮拉取完成后（`CloudSyncRepository.applyPendingRecords`）把任务已到齐的
 * 记录搬回正式表。按 `(taskLocalId, date)` 唯一——与 [CheckRecord] 的业务键一致。
 *
 * 字段语义与 [CheckRecord] 一一对应（`taskLocalId` 即 `CheckRecord.taskId`），
 * 单独建表而不是复用 `CheckRecord` + nullable taskId：正式表的外键约束正是
 * 这里要绕开的东西，往它身上加可空列等于把约束整个废掉。
 */
@Entity(
    tableName = "pending_records",
    indices = [Index(value = ["taskLocalId", "date"], unique = true)]
)
data class PendingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 所属任务的本地 id（`CheckTask.id`）；任务同步到位前它可能悬空——这正是本表存在的原因。 */
    val taskLocalId: Long,
    /** 打卡日期（`yyyy-MM-dd`），与 [CheckRecord.date] 同格式。 */
    val date: String,
    /** 打卡时刻（epoch 毫秒），与 [CheckRecord.checkedAt] 同基。 */
    val checkedAt: Long,
    /** 最后修改时间（epoch 毫秒），与 [CheckRecord.updatedAt] 同基，LWW 判定用。 */
    val updatedAt: Long
)
