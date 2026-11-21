package com.morecheck.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-defined check-in task (e.g. "早起", "喝水"). */
@Entity(tableName = "check_tasks")
data class CheckTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val icon: String,
    val color: Int,
    /** Daily reminder time as "HH:mm" (24h, device time zone), or null when disabled. */
    val reminderTime: String?,
    val createdAt: Long
)
