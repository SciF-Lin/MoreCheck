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
    val checkedAt: Long
)
