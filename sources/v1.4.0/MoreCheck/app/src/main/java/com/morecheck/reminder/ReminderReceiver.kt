package com.morecheck.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morecheck.data.CheckTask
import com.morecheck.di.AppContainer
import com.morecheck.di.appContainer
import com.morecheck.domain.ReminderPolicy
import com.morecheck.util.AppPermission
import com.morecheck.util.AppPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * 每日提醒到点时的接收器，同时承接「漏打卡补推」的一次性闹钟。
 *
 * **通知总开关**在「设置 → 通用 → 权限管理」里（默认关）：
 * 只有 `开关打开 && 系统已授权通知` 时才弹通知，否则静默跳过。
 *
 * ## 为什么关掉通知也必须续排次日提醒
 *
 * 这里续排的是**下一天**的闹钟。如果因为「这次不发通知」就跳过续排，
 * 提醒链条会直接断掉：用户之后再去设置里把通知开关打开，
 * 也**再也不会收到任何提醒**（因为已经没有下一次闹钟了），
 * 表现为「开关打开了但提醒永远不来」。
 * 所以续排是**无条件**的，与是否弹通知完全解耦；用户重新打开开关后，
 * 下一次到点就能立刻恢复推送（无需重启应用或重新编辑任务）。
 * 静默时段命中、任务被暂停也同理——只是这一次不打扰，链条不能断。
 *
 * ## 关于 `goAsync` 与 `finish()`
 *
 * `onReceive` 跑在主线程，读 DataStore（suspend）必须离开主线程，
 * 所以用 `goAsync()` 告诉系统「这次广播还没处理完」，在协程里继续做。
 * 系统对 `goAsync()` 有严格时限：**必须**在结束前调用 `pendingResult.finish()`，
 * 任何分支（包括抛异常）漏掉都会被认为接收器卡死（ANR / 后续广播被丢弃），
 * 因此这里用 `try / finally` 把 `finish()` 钉死在 finally 里。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (taskId <= 0L) return
        val isFollowUp = intent?.getBooleanExtra(EXTRA_FOLLOWUP, false) == true

        val pendingResult = goAsync()
        // v1.1.75：统一走 `Context.appContainer`（原先自己转 Application）。
        // ⚠️ 必须在进入协程**之前**取：`context` 是 `onReceive` 的形参，
        // 而容器是进程单例，提前取出来让协程体只依赖容器、不依赖那个 Context。
        val container = context.appContainer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = container.taskRepository.getTask(taskId)
                if (task != null) {
                    if (isFollowUp) {
                        handleFollowUp(container, task)
                    } else {
                        handleMainReminder(container, context, task)
                    }
                }
            } finally {
                // 放在 finally：无论上面哪一步抛异常，都不会让系统认为接收器卡死。
                pendingResult.finish()
            }
        }
    }

    /**
     * 主提醒：决定是否弹通知，再无条件续排次日。
     *
     * 不弹通知的三种情况——应用开关/系统授权没过、任务被暂停、命中静默时段，
     * 但三者的次日续排**都不跳过**（理由见类注释）。
     * 只有真正弹出了主提醒，才顺手排一次补推；静默 / 暂停 / 关开关时连补推也不排，
     * 免得用户明明没收到第一条，却在几十分钟后收到一条「还没打卡」。
     */
    private suspend fun handleMainReminder(
        container: AppContainer,
        context: Context,
        task: CheckTask
    ) {
        val settings = container.settingsRepository
        val toggleEnabled = settings.isPermissionEnabled(AppPermission.NOTIFICATION.id)
        val systemGranted = AppPermissions.isGranted(context, AppPermission.NOTIFICATION)

        val quietEnabled = settings.reminderQuietEnabled.first()
        val quietStart = settings.reminderQuietStart.first()
        val quietEnd = settings.reminderQuietEnd.first()
        val suppressed = ReminderPolicy.shouldSuppressReminder(
            enabled = quietEnabled,
            now = LocalTime.now(),
            startRaw = quietStart,
            endRaw = quietEnd
        )

        // 归档（v5）与暂停都只在**通知发出层**抑制，不碰闹钟排期：
        // 排期层若也跳过它们，用户从任务管理页「取消归档 / 恢复暂停」时
        // 没有任何重排入口（列表页只写数据库、不调 schedule），链条会永久断掉。
        // 保持闹钟静默触发 + 无条件续排，恢复后下一次到点自动接上。
        if (!task.paused && !task.archived && !suppressed && toggleEnabled && systemGranted) {
            container.notificationHelper.showReminder(task.id, task.name, task.icon)
            if (settings.reminderFollowUpEnabled.first()) {
                val minutes = settings.reminderFollowUpMinutes.first()
                container.reminderScheduler.scheduleFollowUp(task.id, minutes)
            }
        }

        // 续排与是否弹通知无关，见类注释。
        task.reminderTime?.let { container.reminderScheduler.scheduleNext(task.id, it) }
    }

    /**
     * 补推到点：今天已打卡就安静收场，否则再发一条提醒。
     *
     * 用 `getRecordsBetween(today, today)` 查当天全部记录、按 taskId 过滤，
     * 复用现有仓库接口，不新增 DAO 查询。
     * 这个分支**不续排、不再排下一次补推**：补推是一次性动作，
     * 次日主提醒的续排早已由 [handleMainReminder] 在上一次广播里排好。
     * 暂停 / 归档任务同样不补推——延迟期间用户把任务改了状态，就该尊重当下。
     */
    private suspend fun handleFollowUp(container: AppContainer, task: CheckTask) {
        if (task.paused || task.archived) return
        val today = LocalDate.now()
        val checked = container.taskRepository
            .getRecordsBetween(today, today)
            .any { it.taskId == task.id }
        if (checked) return
        // 门控在 showFollowUpReminder 内部（isNotificationAllowed），这里不重复判。
        container.notificationHelper.showFollowUpReminder(task.id, task.name, task.icon)
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"

        /** 标记这条广播是「漏打卡补推」而不是每日主提醒。 */
        const val EXTRA_FOLLOWUP = "extra_followup"

        /**
         * 补推闹钟的 Intent action。
         *
         * ReminderReceiver 在 Manifest 里 exported=false、没有 intent-filter，
         * 靠**显式 Intent** 投递，action 不参与系统匹配；这里给它一个与主提醒
         * 不同的值，纯粹是为了让两个 PendingIntent 不被判为同一个（见
         * ReminderScheduler.followUpPendingIntentFor 的说明）。
         */
        const val ACTION_FOLLOWUP = "com.morecheck.reminder.ACTION_FOLLOWUP"
    }
}
