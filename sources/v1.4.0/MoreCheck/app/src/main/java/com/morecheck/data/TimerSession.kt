package com.morecheck.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One completed countdown session. Written when the timer reaches zero
 * (see TimerPanel's finish effect); paused-and-resumed runs stay one session.
 */
@Entity(tableName = "timer_sessions")
data class TimerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Wall clock (epoch millis) of the moment the user pressed start. */
    val startedAt: Long,
    /** Wall clock (epoch millis) of the moment the countdown reached zero. */
    val endedAt: Long,
    /** Planned countdown length in millis (the wheel selection at start). */
    val durationMs: Long
)