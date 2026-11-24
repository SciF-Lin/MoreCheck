package com.morecheck.util

import android.content.Context
import android.util.Log
import com.morecheck.BuildConfig
import com.tencent.bugly.crashreport.CrashReport

/**
 * 崩溃监控（Bugly）的**唯一入口**。
 *
 * ## 三步纪律
 *
 * 1. **只在这里初始化**：调用点只有 [com.morecheck.MoreCheckApp] 一处，
 *    且必须发生在用户同意《隐私政策》之后（见那里的 `privacyAgreed.first { it }`）。
 *    在同意之前初始化等于"未授权就采集设备信息"，这是应用商店的常见驳回项。
 * 2. **AppId 为空就什么都不做**：仓库不提交 AppId（见 `app/build.gradle.kts` 的注入说明）。
 *    未配置时连 SDK 的 `initCrashReport` 都不调用，**不会**产生任何网络请求与采集。
 * 3. **不上报用户身份**：不调 `setUserId`、不传手机号与云账号 ID。
 *    Bugly 上报的是崩溃堆栈 + 应用版本 + 设备型号/系统版本/网络类型，
 *    这也是《隐私政策》第四条 SDK 清单里写的那几项。
 *
 * ## 为什么不捕获崩溃自己处理
 *
 * Bugly 内部会 `setDefaultUncaughtExceptionHandler` 并落盘堆栈，在**下一次启动**时上报
 * （崩溃当下进程已经不可信）。我们自己的诊断日志（[MoreCheckApp.DIAG_TAG]，仅 diag 变体）
 * 是另一条链路，两者互不冲突：诊断日志只写 logcat，供真机排查用。
 *
 * ## mapping 文件
 *
 * release 开了 R8，堆栈默认是 `a.b.c`。Bugly 支持在控制台上传 `mapping.txt` 还原，
 * 发布流程需要把 `app/build/outputs/mapping/release/mapping.txt` 一并上传（见发布手册）。
 * 不上传不会崩，只是堆栈难读。
 */
object CrashMonitor {

    private const val TAG = "CrashMonitor"

    @Volatile
    private var initialized = false

    /**
     * 初始化崩溃监控。可重复调用（幂等）。
     *
     * @param context 任意 Context，内部取 applicationContext。
     */
    fun initAfterConsent(context: Context) {
        if (initialized) return

        val appId = BuildConfig.BUGLY_APP_ID.trim()
        if (appId.isEmpty()) {
            // 没配 AppId 属于正常状态（本地开发 / 未注册 Bugly 应用）。
            // 这里只打一行日志，不抛异常、不影响启动。
            Log.i(TAG, "未配置 BUGLY_APP_ID，崩溃监控未启用")
            return
        }

        initialized = true
        val app = context.applicationContext
        runCatching {
            val strategy = CrashReport.UserStrategy(app).apply {
                // 版本号用应用自己的 versionName：与控制台里的版本维度对齐，
                // 否则 Bugly 会把所有构建归到同一个默认版本。
                appVersion = BuildConfig.VERSION_NAME
                // 渠道只分 debug / release：本项目没有应用市场渠道包。
                appChannel = if (BuildConfig.DEBUG) "debug" else "release"
                appPackageName = app.packageName
            }
            CrashReport.initCrashReport(app, appId, BuildConfig.DEBUG, strategy)
            if (BuildConfig.DEBUG) {
                // 标记为开发设备：控制台里可单独筛选，避免调试期的崩溃淹掉真实用户数据。
                CrashReport.setIsDevelopmentDevice(app, true)
            }
            Log.i(
                TAG,
                "崩溃监控已启用 channel=${if (BuildConfig.DEBUG) "debug" else "release"} " +
                    "version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
            )
        }.onFailure {
            // 初始化失败（网络/包名与 AppId 不匹配等）绝不能让应用起不来。
            initialized = false
            Log.w(TAG, "崩溃监控初始化失败：${it.message}")
        }
    }

    /** 当前是否真的启用了（供开发者选项/关于页展示，不涉及任何上报）。 */
    fun isEnabled(): Boolean = initialized
}
