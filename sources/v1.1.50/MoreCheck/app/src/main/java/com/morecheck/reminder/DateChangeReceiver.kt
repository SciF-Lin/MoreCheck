package com.morecheck.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.morecheck.util.DayState
import java.time.LocalDate

/**
 * Receives ACTION_DATE_CHANGED / ACTION_TIME_CHANGED / ACTION_TIME_SET /
 * ACTION_TIMEZONE_CHANGED and rolls the day over via WorkManager.
 */
class DateChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        DayState.today.value = LocalDate.now()
        val request = OneTimeWorkRequestBuilder<MidnightRolloverWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
