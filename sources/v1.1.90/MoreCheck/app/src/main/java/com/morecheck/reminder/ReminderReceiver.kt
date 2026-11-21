package com.morecheck.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morecheck.di.appContainer
import com.morecheck.util.AppPermission
import com.morecheck.util.AppPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 每日提醒到点时的接收器。
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
 *
 * ## 关于 `goAsync` 与 `finish()`
 *
 * `onReceive` 跑在主线程，读 DataStore（`isPermissionEnabled` 是 suspend）必须离开主线程，
 * 所以用 `goAsync()` 告诉系统「这次广播还没处理完」，在协程里继续做。
 * 系统对 `goAsync()` 有严格时限：**必须**在结束前调用 `pendingResult.finish()`，
 * 任何分支（包括抛异常）漏掉都会被认为接收器卡死（ANR / 后续广播被丢弃），
 * 因此这里用 `try / finally` 把 `finish()` 钉死在 finally 里。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        // v1.1.75：统一走 `Context.appContainer`（原先自己转 Application）。
        // ⚠️ 必须在进入协程**之前**取：`context` 是 `onReceive` 的形参，
        // 而容器是进程单例，提前取出来让协程体只依赖容器、不依赖那个 Context。
        val container = context.appContainer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = container.taskRepository.getTask(taskId)
                if (task != null) {
                    // 两个条件都满足才推送：应用开关（用户意图）+ 系统授权（系统事实）。
                    val toggleEnabled = container.settingsRepository
                        .isPermissionEnabled(AppPermission.NOTIFICATION.id)
                    val systemGranted = AppPermissions.isGranted(context, AppPermission.NOTIFICATION)
                    if (toggleEnabled && systemGranted) {
                        container.notificationHelper.showReminder(task.id, task.name, task.icon)
                    }
                    // 续排与是否弹通知无关，见类注释。
                    task.reminderTime?.let { container.reminderScheduler.scheduleNext(task.id, it) }
                }
            } finally {
                // 放在 finally：无论上面哪一步抛异常，都不会让系统认为接收器卡死。
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
