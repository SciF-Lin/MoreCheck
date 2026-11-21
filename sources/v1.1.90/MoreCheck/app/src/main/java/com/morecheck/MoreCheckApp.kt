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
import com.morecheck.reminder.CloudSyncWorker
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

        // 课程提醒续排（v1.1.79 需求 2）。
        //
        // 每次进程启动都重排一遍「未来 7 天」的一次性闹钟：
        // 覆盖"装机后第一次打开""用户改了开关 / 提前量""上次排程已过期"三种情况。
        // 幂等（内部先 cancelAll 再排），重复调用无副作用；失败也不影响启动。
        appScope.launch {
            runCatching { container.courseReminderScheduler.rescheduleAll() }
        }

        // 云登录会话恢复（v3 云同步）：把持久化的令牌读回内存，
        // 之后 `isLoggedIn` / `session` / `validToken()` 才有真值。
        // 必须尽早跑——登录页、我的页、每日 Worker 都依赖它。
        appScope.launch {
            runCatching { container.cloudAuthClient.restore() }
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

        // 每日云同步（v3）：独立唯一周期任务，与上面的 daily_rollover 互不相干。
        scheduleCloudSync()
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
     * 每日云同步的周期任务（v3）。
     *
     * KEEP 策略：任务一旦排下就**不因进程重启而重排**（24 小时间隔从首次排下起算），
     * 这样反复杀进程不会把"每天一次"刷成"每次启动一次"。
     * 真正的开关判断在 [CloudSyncWorker] 里（autoSyncEnabled + 登录态）——
     * 关掉自动同步时任务留在队列里安静退出，重新打开即刻恢复。
     */
    private fun scheduleCloudSync() {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(24, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cloud_sync_daily",
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
