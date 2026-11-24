package com.morecheck.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morecheck.di.appContainer

/**
 * 每日云同步（WorkManager 周期任务，默认 24 小时一次）。
 *
 * ## 与 [MidnightRolloverWorker] 的关系
 *
 * 两个 Worker 是两个**独立**的唯一周期任务（`cloud_sync_daily` / `daily_rollover`），
 * 互不知道对方存在：换日兜底管的是「零点结算 + 重排提醒」，本 Worker 管的是
 * 「每天找一次云端拉新数据」。刻意不合并成一个：两者的失败语义不同——
 * 换日失败会让提醒失效，必须每次都试；云同步失败（没网 / 没登录）只是少更新一天，
 * 静默跳过即可。
 *
 * ## 为什么要这个 Worker（而不是只在打开 App 时同步）
 *
 * 用户完全可能一周不打开本应用，但另一台设备（或将来可能出现的其它入口）
 * 每天在改数据。WorkManager 的周期任务会在系统允许时被唤醒执行（即使进程被杀），
 * 因此它是"每天至少同步一次"的唯一可靠保证。
 *
 * ## 为什么进门有两道判断
 *
 * 1. **`autoSyncEnabled`**（同步设置页可关）：用户显式关掉自动同步时，
 *    每日同步与打卡后推送都停。Worker 尊重这个开关——但**不取消**已排程的任务，
 *    这样用户重新打开开关后不需要等下一次冷启动才恢复。
 * 2. **登录态**：未登录 / 令牌已过期且刷新失败时，`syncNow()` 内部会抛
 *    UNAUTHENTICATED，被下面的 runCatching 静默吞掉。提前用
 *    `cloudAuthClient.isLoggedIn` 判断只是省一次无谓的网络往返。
 *
 * ## 失败一律 success
 *
 * 任何异常都返回 [Result.success] 而不是 [Result.retry]：周期任务下次还会来，
 * 立即重试只会徒增网络往返；而同步失败的可见反馈（"上次同步失败"）已经由
 * [com.morecheck.data.cloud.CloudSyncRepository.syncNow] 写进了同步状态，
 * 用户下次打开同步设置页就能看到。
 */
class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer

        // 用户关掉了自动同步：安静退出，任务保留（重新打开开关即恢复）。
        if (!container.cloudSyncRepository.current().autoSyncEnabled) {
            return Result.success()
        }

        // 未登录（或会话还没恢复完）：不做无谓的网络请求。
        // `restore()` 在 Application.onCreate 里已异步跑过一次，这里读到的
        // 在绝大多数情况下已经是真实值；极端情况（冷启动瞬间 Worker 被触发）
        // 只是少同步一次，下个周期补上。
        if (!container.cloudAuthClient.isLoggedIn) {
            return Result.success()
        }

        // syncNow() 自己会记同步状态（成功统计 / 失败原因），这里不重复记录。
        // 异常全部吞掉：周期任务的失败不该以重试轰炸系统。
        runCatching { container.cloudSyncRepository.syncNow() }

        return Result.success()
    }
}
