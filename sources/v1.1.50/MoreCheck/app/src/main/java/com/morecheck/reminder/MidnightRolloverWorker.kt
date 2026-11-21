package com.morecheck.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morecheck.MoreCheckApp
import com.morecheck.util.DayState
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

        DayState.today.value = today
        app.container.settingsRepository.setLastRolloverDate(today.toString())
        app.container.reminderScheduler.rescheduleAll()

        return Result.success()
    }
}
