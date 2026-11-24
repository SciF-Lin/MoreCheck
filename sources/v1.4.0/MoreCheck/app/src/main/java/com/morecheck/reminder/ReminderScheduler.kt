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

    /**
     * 主提醒发出后，排一个「漏打卡补推」的一次性闹钟。
     *
     * 复用 [setExact] 的精确闹钟实现，只是触发时刻改成「当前 + minutes 分钟」
     * （而不是像每日提醒那样算下一个 HH:mm）。到点仍投递给 [ReminderReceiver]，
     * 由它带 [ReminderReceiver.EXTRA_FOLLOWUP] 的分支判断是否真要补发通知——
     * 因此无需新增 BroadcastReceiver，也不必动 Manifest。
     */
    fun scheduleFollowUp(taskId: Long, minutes: Int) {
        val triggerAt = System.currentTimeMillis() + minutes.toLong() * 60_000L
        setExact(triggerAt, followUpPendingIntentFor(taskId))
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

    /**
     * 补推专用 PendingIntent。
     *
     * 与主提醒的 [pendingIntentFor] 用**不同的 action 和 requestCode**：
     * getBroadcast 的判等不看 extras，只比 action / class / requestCode，
     * 若两者相同，FLAG_UPDATE_CURRENT 会让后排的补推把主提醒闹钟的 extras
     * 覆盖掉（或反过来），两个闹钟就串了。两者独立后，主闹钟的每日续排与
     * 一次性补推互不影响。
     */
    private fun followUpPendingIntentFor(taskId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FOLLOWUP
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_FOLLOWUP, true)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.toInt() + FOLLOWUP_REQUEST_CODE_OFFSET,
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

    companion object {
        /** 补推 PendingIntent 的 requestCode 偏移，与主提醒的 taskId 错开。 */
        const val FOLLOWUP_REQUEST_CODE_OFFSET = 500_000
    }
}
