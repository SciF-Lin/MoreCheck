package com.morecheck.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules exact daily reminder alarms. Uses setExactAndAllowWhileIdle on
 * Android 12+ (falling back to inexact when the exact-alarm permission is not
 * granted) and re-arms the next occurrence after each firing.
 */
class ReminderScheduler(
    private val context: Context,
    private val repository: TaskRepository,
    @Suppress("unused") private val notificationHelper: NotificationHelper
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    suspend fun schedule(task: CheckTask) {
        cancel(task.id)
        val time = task.reminderTime ?: return
        val triggerAt = nextTriggerMillis(time) ?: return
        setExact(triggerAt, pendingIntentFor(task.id))
    }

    suspend fun rescheduleAll() {
        repository.tasks.first().forEach { task ->
            if (task.reminderTime != null) schedule(task)
        }
    }

    fun cancel(taskId: Long) {
        alarmManager.cancel(pendingIntentFor(taskId))
    }

    /** Re-arm after a reminder has fired. */
    fun scheduleNext(taskId: Long, time: String) {
        val triggerAt = nextTriggerMillis(time) ?: return
        setExact(triggerAt, pendingIntentFor(taskId))
    }

    private fun pendingIntentFor(taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.morecheck.reminder.ACTION_REMINDER"
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setExact(triggerAtMillis: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun nextTriggerMillis(time: String): Long? {
        val parsed = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var trigger = ZonedDateTime.of(LocalDate.now(zone), parsed, zone)
        if (!trigger.isAfter(now)) {
            trigger = trigger.plusDays(1)
        }
        return trigger.toInstant().toEpochMilli()
    }
}
