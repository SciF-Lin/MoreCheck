package com.morecheck.data

import androidx.room.Entity

/**
 * 同步墓碑：某一行在本地被**删除**的记录（云同步用）。
 *
 * ## 为什么需要它
 *
 * 增量同步只推送"改动过的行"（`updated_at > 水位线`）。一行被删掉后，
 * 它在本地已经不存在，任何基于"现存行"的推送都**永远发现不了这次删除**——
 * 云端会一直保留着那行，下次拉取又把它带回本地，删除被无声地撤销。
 * 墓碑就是给删除事件一个可被推送的载体。
 *
 * ## 推送形态
 *
 * 墓碑本身**不直接删云端行**，而是被转换成一条 `deleted = true` 的 upsert
 * （`updated_at = deletedAt`）：
 * - 与普通行走同一条 LWW 规则，另一台设备更新的行（updated_at 更大）仍然能赢；
 * - 拉取端看到 `deleted = true` 就在本地删除，不需要额外的"删除通道"。
 *
 * 推送成功后墓碑即可丢弃（[CheckDao.deleteTombstone]）——云端已经记住了这次删除。
 *
 * ## rowKey 的格式（按表固定，解析规则在 `CloudSyncRepository`）
 *
 * - `check_tasks`：任务本地 id 的十进制字符串，如 `"42"`；
 * - `check_records`：`"<taskId>|<date>"`，如 `"42|2026-10-21"`。
 *
 * 刻意用字符串而不是给两类行各建一张墓碑表：墓碑量小（删了多少任务才有多少行），
 * 一张表 + 一个解析函数比两张表 + 两套 DAO 更易读。
 */
@Entity(
    tableName = "sync_tombstones",
    primaryKeys = ["tableName", "rowKey"]
)
data class SyncTombstone(
    /** 行所在的表名（`check_tasks` / `check_records`）。 */
    val tableName: String,
    /** 行在表内的键，格式见类注释。 */
    val rowKey: String,
    /** 删除发生的时间（epoch 毫秒），同时是云端 upsert 的 updated_at。 */
    val deletedAt: Long
) {
    companion object {
        const val TABLE_TASKS = "check_tasks"
        const val TABLE_RECORDS = "check_records"

        /** 记录行的 rowKey：`<taskId>|<date>`（date 里不会含 `|`，分隔安全）。 */
        fun recordKey(taskId: Long, date: String): String = "$taskId|$date"

        /** 任务行的 rowKey：本地 id 的十进制串。 */
        fun taskKey(taskId: Long): String = taskId.toString()
    }
}
