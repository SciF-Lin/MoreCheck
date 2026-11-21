package com.morecheck.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morecheck.MoreCheckApp
import com.morecheck.util.DayState
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Runs at midnight (triggered by date/time broadcasts, boot, and a daily
 * WorkManager safety net). Settles the new day and re-arms every reminder.
 */
class MidnightRolloverWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MoreCheckApp
        val today = LocalDate.now()

        // 换日始终执行：它与「自启动」无关，日期一变界面就该刷新。
        DayState.today.value = today
        app.container.settingsRepository.setLastRolloverDate(today.toString())

        // 「自启动」开关**只约束开机这一条路径**：
        // 关闭后开机不再自动恢复提醒，但用户正在使用应用时的零点换日、
        // 以及每日兜底同步仍会照常重排，避免提醒莫名其妙失效。
        val fromBoot = inputData.getBoolean(KEY_FROM_BOOT, false)
        val autoStartEnabled = app.container.settingsRepository.autoStartOnBoot.first()
        if (!fromBoot || autoStartEnabled) {
            app.container.reminderScheduler.rescheduleAll()
        }

        return Result.success()
    }

    companion object {
        /** 输入标记：本次工作是否由开机广播触发。 */
        const val KEY_FROM_BOOT = "from_boot"
    }
}
