package com.morecheck

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.morecheck.di.AppContainer
import com.morecheck.reminder.DateChangeReceiver
import com.morecheck.reminder.MidnightRolloverWorker
import com.morecheck.util.DayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class MoreCheckApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DayState.today.value = LocalDate.now()

        // Seed the default example task on first launch.
        appScope.launch {
            container.taskRepository.seedDefaultTaskIfNeeded()
        }

        // Runtime-registered receiver for date/time changes so the midnight
        // rollover works while the process is alive (these implicit broadcasts
        // are not delivered to manifest receivers on Android 8+).
        ContextCompat.registerReceiver(
            this,
            DateChangeReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Daily WorkManager safety net: settles the day and re-arms reminders.
        scheduleDailySync()
    }

    private fun scheduleDailySync() {
        val request = PeriodicWorkRequestBuilder<MidnightRolloverWorker>(24, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_rollover",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
