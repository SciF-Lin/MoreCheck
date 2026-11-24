package com.morecheck.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morecheck.di.appContainer
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
        // v1.1.75：走统一的 `Context.appContainer`，不再自己转 Application
        //（那时写的是 `applicationContext as MoreCheckApp`，转换逻辑散在各处）。
        val container = applicationContext.appContainer
        val today = LocalDate.now()

        // 换日始终执行：它与「自启动」无关，日期一变界面就该刷新。
        DayState.today.value = today
        container.settingsRepository.setLastRolloverDate(today.toString())

        // 「自启动」开关**只约束开机这一条路径**：
        // 关闭后开机不再自动恢复提醒，但用户正在使用应用时的零点换日、
        // 以及每日兜底同步仍会照常重排，避免提醒莫名其妙失效。
        val fromBoot = inputData.getBoolean(KEY_FROM_BOOT, false)
        val autoStartEnabled = container.settingsRepository.autoStartOnBoot.first()
        if (!fromBoot || autoStartEnabled) {
            container.reminderScheduler.rescheduleAll()
        }

        // 课程提醒同样每次换日续排（v1.1.79 需求 2）。
        // 不受「自启动」开关约束：那个开关的语义是"开机后要不要恢复每日打卡提醒"
        // （见上面的注释），而课程提醒是用户在课程表页显式打开的、范围仅 7 天，
        // 若再叠一层自启动门控，用户会遇到"开着课程提醒却收不到"的困惑。
        // 内部幂等：关闭状态下 rescheduleAll() 只会做一次 cancelAll。
        runCatching { container.courseReminderScheduler.rescheduleAll() }

        return Result.success()
    }

    companion object {
        /** 输入标记：本次工作是否由开机广播触发。 */
        const val KEY_FROM_BOOT = "from_boot"
    }
}
