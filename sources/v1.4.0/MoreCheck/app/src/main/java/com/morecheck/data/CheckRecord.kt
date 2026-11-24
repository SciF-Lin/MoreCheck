package com.morecheck.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * One check-in record. Composite key (taskId, date) enforces the "one check-in
 * per task per day" rule: re-checking the same day only replaces checkedAt.
 */
@Entity(
    tableName = "check_records",
    primaryKeys = ["taskId", "date"],
    foreignKeys = [
        ForeignKey(
            entity = CheckTask::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index("date")]
)
data class CheckRecord(
    val taskId: Long,
    /** LocalDate formatted as "yyyy-MM-dd" in the device time zone. */
    val date: String,
    /** Epoch millis of the most recent check-in on that date. */
    val checkedAt: Long,
    /**
     * 本行最后一次变更的时间（epoch 毫秒），云同步 LWW 裁决用。
     *
     * 打卡 / 补打卡 / 撤销打卡都会刷新它（撤销走墓碑，见 `SyncTombstone`）。
     * v3 迁移回填 [checkedAt]（见 `MIGRATION_2_3`），备份解析用文件内的时间兜底。
     */
    val updatedAt: Long = 0L
)
