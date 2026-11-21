package com.morecheck.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.morecheck.data.Course
import com.morecheck.data.SettingsRepository
import com.morecheck.data.TimetableRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 课程提醒的投递端：闹钟响了 → 发一条通知。
 *
 * ## 为什么把"课程名 / 地点 / 时间"塞进 Intent，而不是响的时候再读课表
 *
 * 响的时候去读 `assets` 再解析一遍课表（几百毫秒 + IO）只为拿一个课程名，
 * 收益为零；而闹钟在**排程那一刻**就已经知道是哪节课了。
 * 塞 extras 的额外好处是：将来课表素材换了、某节课被删了，
 * 已经排好的闹钟仍会按排程时的内容播报——不会出现"响了但找不到这门课"的空通知。
 *
 * ## 通知只走 [NotificationHelper.showCourseReminder]，不自己判开关
 *
 * 开关（用户是否开启课程提醒）在**排程**时就判过了：关掉开关会取消所有闹钟，
 * 因此走到本接收器就意味着"用户要这条提醒"。通知的**系统级权限**仍由 helper 把关。
 */
class CourseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COURSE_REMINDER) return

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        if (name.isBlank()) return
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val startText = intent.getStringExtra(EXTRA_START).orEmpty()
        val leadMinutes = intent.getIntExtra(EXTRA_LEAD, 0)

        val helper = (context.applicationContext as? com.morecheck.MoreCheckApp)
            ?.container?.notificationHelper
            ?: NotificationHelper(context)

        // 发送本身是同步的（只查系统通知权限 + 一次 notify），直接调即可。
        // 万一将来它变成挂起/耗时操作，再按 goAsync() + 协程的写法改造。
        runCatching {
            helper.showCourseReminder(
                courseName = name,
                location = location,
                startText = startText,
                leadMinutes = leadMinutes
            )
        }
    }

    companion object {
        const val ACTION_COURSE_REMINDER = "com.morecheck.reminder.ACTION_COURSE"

        const val EXTRA_NAME = "course_name"
        const val EXTRA_LOCATION = "course_location"
        const val EXTRA_START = "course_start"
        const val EXTRA_LEAD = "course_lead_minutes"
    }
}

/**
 * 课程提醒的排程器（v1.1.79 需求 2）。
 *
 * ## 设计：一次排「未来 N 天」的全部提醒，而不是注册一个每日轮询
 *
 * 课表来自内置素材，**每节课的日期与时刻都是确定的**，因此可以在排程那一刻
 * 直接算出「接下来 7 天里每个 (课程, 提前量) 组合的触发时刻」并逐个设成**一次性**闹钟。
 *
 * 为什么不用 `WorkManager` 的周期任务每天算一次：
 * - 周期任务的最小间隔是 15 分钟且**不保证准点**（doze / 省电策略下能偏几分钟到几十分钟），
 *   而"上课前 10 分钟提醒"偏 10 分钟就失去意义；
 * - `AlarmManager.setExactAndAllowWhileIdle` 才能给出秒级准确度（与打卡提醒同一口径）。
 *
 * 为什么是 7 天而不是整个学期：整学期约 400 个（课程 × 提前量）闹钟，
 * 数量本身可接受，但用户换了学期素材后旧闹钟需要逐个撤销、且长期驻留的闹钟
 * 在部分 ROM 上会被清理。7 天是"够用 + 每次打开应用都会续排"的折中：
 * 每天的自动续排由 [com.morecheck.reminder.MidnightRolloverWorker] 与
 * 应用启动共同触发（见 `MoreCheckApp` 与 `BootReceiver`）。
 *
 * ## requestCode 怎么保证不撞
 *
 * `ReminderScheduler`（打卡提醒）用的是 `taskId`，课程提醒换成
 * [REQUIREMENT_BASE] 起算的独立区段，避免与打卡提醒的 requestCode 冲突——
 * 两者都挂在同一个 `AlarmManager` 上，requestCode 相同会互相顶掉。
 */
class CourseReminderScheduler(
    private val context: Context,
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationHelper: NotificationHelper
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * 按当前设置重排全部课程提醒：先撤销旧的全部，再按新设置排未来 [DAYS_AHEAD] 天。
     *
     * **幂等**：重复调用等价于"重新算一遍"，不需要调用方自己判断"是否已经排过"。
     */
    suspend fun rescheduleAll() {
        cancelAll()

        val enabled = settingsRepository.timetableReminderEnabled.first()
        if (!enabled) return

        val leadMinutes = settingsRepository.timetableReminderLeadMinutes.first()
        val timetable = runCatching { timetableRepository.load() }.getOrNull() ?: return

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val today = now.toLocalDate()
        var requestCode = REQUEST_BASE

        for (dayOffset in 0 until DAYS_AHEAD) {
            val date: LocalDate = today.plusDays(dayOffset.toLong())
            timetable.coursesOn(date).forEach { course ->
                val triggerAt = course.start.minusMinutes(leadMinutes.toLong())
                // 已经错过的（含"现在就要响"的边界）直接跳过，避免开应用时补弹一堆历史提醒。
                if (!triggerAt.isAfter(now)) return@forEach
                schedule(
                    triggerAtMillis = triggerAt.atZone(zone).toInstant().toEpochMilli(),
                    course = course,
                    leadMinutes = leadMinutes,
                    requestCode = requestCode
                )
                requestCode++
            }
        }
    }

    /** 撤销全部课程提醒（关闭开关 / 重排前调用）。 */
    fun cancelAll() {
        var requestCode = REQUEST_BASE
        repeat(MAX_ALARMS) {
            val pi = pendingIntentFor(requestCode, emptyIntent())
            alarmManager.cancel(pi)
            requestCode++
        }
    }

    private fun schedule(
        triggerAtMillis: Long,
        course: Course,
        leadMinutes: Int,
        requestCode: Int
    ) {
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderReceiver.ACTION_COURSE_REMINDER
            putExtra(CourseReminderReceiver.EXTRA_NAME, course.summary)
            putExtra(CourseReminderReceiver.EXTRA_LOCATION, course.location)
            putExtra(CourseReminderReceiver.EXTRA_START, course.startTime.toString())
            putExtra(CourseReminderReceiver.EXTRA_LEAD, leadMinutes)
        }
        val pi = pendingIntentFor(requestCode, intent)
        // 与打卡提醒同一口径：没拿到精确闹钟权限时退回不精确（仍会响，只是可能偏几分钟）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun emptyIntent(): Intent =
        Intent(context, CourseReminderReceiver::class.java).apply {
            action = CourseReminderReceiver.ACTION_COURSE_REMINDER
        }

    private fun pendingIntentFor(requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        /** 排未来多少天（7 天：见类 KDoc 的取舍）。 */
        const val DAYS_AHEAD = 7

        /**
         * requestCode 起点。取 0x2E00（≈11776，远大于打卡提醒的 taskId 量级），
         * 与打卡提醒的 requestCode 区段完全隔开，不会互相顶掉。
         */
        const val REQUEST_BASE = 0x2E00

        /** 撤销时最多扫多少个 requestCode（7 天 × 每天最多 12 节 = 84，留足余量）。 */
        const val MAX_ALARMS = 200
    }
}
