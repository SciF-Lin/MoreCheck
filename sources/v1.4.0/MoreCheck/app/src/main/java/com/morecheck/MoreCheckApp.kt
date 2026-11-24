package com.morecheck

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.morecheck.data.net.AppHttp
import com.morecheck.di.AppContainer
import com.morecheck.reminder.CloudSyncWorker
import com.morecheck.reminder.DateChangeReceiver
import com.morecheck.reminder.MidnightRolloverWorker
import com.morecheck.util.CrashMonitor
import com.morecheck.util.DayState
import com.morecheck.util.ErrorLogStore
import com.morecheck.util.LaunchState
import com.morecheck.util.LinkOpenGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class MoreCheckApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 本机错误日志（2026-11-14 需求 3）：崩溃堆栈与手动记录的报错都落在
        // `filesDir/logs`，用户可在「设置 → 问题与诊断 → 错误日志」里查看与复制。
        //
        // ⚠️ 它**不涉及任何数据采集与外发**（只写自己的私有目录），所以放在
        // 隐私同意之前安装没有问题；Bugly 那条上报链路仍然只在同意之后初始化，
        // 见 `CrashMonitor.initAfterConsent` 的说明。越早装越好：启动阶段崩了也能留下记录。
        ErrorLogStore.install(this)

        installDiagnosticCrashLogger()

        // 只在 debug 变体打开 WebView 远程调试（chrome://inspect / CDP）。
        //
        // 2026-10-26 加：内嵌云页（今日待办）的排障一直卡在"看不见网页内部状态"上——
        // 页面里的 JS 报错、输入框是否真的拿到焦点、发送的请求头长什么样，
        // 只能靠 logcat 的 onConsoleMessage 间接猜。开了这个开关就能用
        // `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` +
        // DevTools 直接读/写页面上下文。
        // ⚠️ 必须用 BuildConfig.DEBUG 门控：release 包绝不能把页面暴露给调试协议。
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        // Coil 3 的全局 ImageLoader（2026-10-27 接入）：默认实现会自己 new 一个
        // OkHttpClient + 线程池；这里换成复用 `AppHttp.client`，好处是**连接池只有一份**
        // （图片与接口请求共享），也不会因为引了图片库就多一组常驻线程。
        //
        // `setSafe` 而不是 `set`：Compose 预览 / 测试里 onCreate 可能被多次触发，
        // `set` 第二次会抛 IllegalStateException，`setSafe` 是幂等的。
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { AppHttp.client }))
                }
                // 淡入：本地文件解码通常一帧内就绪，这个选项主要对将来的网络图有用；
                // 对本地图它等价于"没有额外延迟"，不会拖慢聊天页。
                .crossfade(true)
                .build()
        }

        container = AppContainer(this)
        DayState.today.value = LocalDate.now()

        // 2026-11-14 需求 1：在**隐私门闸之前**记下"这个用户以前是否用过本应用"。
        // 用途见 `LaunchState` 的 KDoc——更新介绍的弹与不弹靠它区分
        // "老用户升级" 与 "全新安装"。读完（成功或失败）都要给值，UI 在等它。
        appScope.launch {
            val used = runCatching {
                container.settingsRepository.privacyAgreed.first()
            }.getOrDefault(false)
            LaunchState.markEverUsedBeforeLaunch(used)
        }

        // 「链接打开方式」的进程级缓存：openInBrowser 是同步函数、拿不到协程作用域，
        // 这里随 DataStore 的变化持续镜像，进程一启动就已就位（不依赖任何页面组合过）。
        // 用 appScope（SupervisorJob + IO）且不取消，与容器里的长寿命作用域同一口径。
        appScope.launch {
            container.settingsRepository.linkOpenInApp.collect { LinkOpenGate.inApp = it }
        }

        // 崩溃监控（2026-11-05 需求 2）：**必须等用户同意隐私政策**再初始化。
        // 这里挂一条一次性收集：同意过（True）就直接过，没同意过就等用户点同意的那一刻。
        // 放在 App 而不是界面上，是为了"在任何页面同意都生效"，且进程重建后也能补上。
        appScope.launch {
            container.settingsRepository.privacyAgreed.first { it }
            CrashMonitor.initAfterConsent(this@MoreCheckApp)
        }

        // 实验室宫格顺序的进程级快照（v1.3.1 beta 需求 8）。
        //
        // `SettingsRepository.labGridOrderSnapshot` 只在有人收集那条流时才有值；
        // 在进程启动时就收集一次，意味着用户「回到实验室」时第一帧拿到的就是真实顺序，
        // 不会先按默认顺序画一帧再"瞬间移动排序"（详见那个属性的 KDoc）。
        // 这条流只读 DataStore、无副作用，长驻收集的开销与 linkOpenInApp 同一量级。
        appScope.launch {
            container.settingsRepository.labGridOrder.collect { }
        }

        // 「官网 / 搜一搜」模式与「入口归属覆盖表」同样预热（2026-11-14 需求 2）。
        //
        // 这两条值决定发现页每一行长什么样、实验室宫格有哪些格子：
        // 只给 `initialValue` 的话，切回发现页 / 进实验室时会先按默认归属画一帧，
        // 真实配置到达后再整块长出来或重新排序——就是用户反馈的"闪一下"和"重排位置"。
        // 预热后首帧拿到的就是真实值，画面不再跳。
        appScope.launch {
            container.settingsRepository.devDiscoverSearchMode.collect { }
        }
        appScope.launch {
            container.settingsRepository.entryPlacementOverrides.collect { }
        }

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
