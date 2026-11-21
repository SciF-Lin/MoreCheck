package com.morecheck

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
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
        installDiagnosticCrashLogger()
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

    /**
     * 诊断构建专用：安装全局未捕获异常处理器。
     *
     * 仅在 `-PdiagBuild=true` 构建的包里生效（`BuildConfig.DIAG_BUILD`），
     * 正式包是空操作。作用是把完整堆栈以固定 TAG 打到 logcat，便于：
     *   adb logcat -d -s MoreCheckDiag:E
     */
    private fun installDiagnosticCrashLogger() {
        if (!BuildConfig.DIAG_BUILD) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e(
                    DIAG_TAG,
                    "=== 未捕获异常：线程=${thread.name} 包名=${packageName} " +
                        "版本=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) " +
                        "R8=${if (BuildConfig.DEBUG) "debug" else "release"} ===",
                    throwable
                )
            }
            // 交回原处理器（通常是系统默认），保持正常崩溃行为便于复现。
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(DIAG_TAG, "诊断异常捕获已启用：${packageName} ${BuildConfig.VERSION_NAME}")
    }

    companion object {
        const val DIAG_TAG = "MoreCheckDiag"
    }
}
