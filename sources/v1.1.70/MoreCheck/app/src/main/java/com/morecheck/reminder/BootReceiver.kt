package com.morecheck.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * 开机后恢复提醒与换日。
 *
 * 这里只负责把工作交给 [MidnightRolloverWorker]，并打上「来自开机」的标记；
 * 是否真的重排提醒由 worker 读取「自启动」设置后决定
 * （BroadcastReceiver 里不能阻塞读 DataStore，所以判断放在 worker 里）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val request = OneTimeWorkRequestBuilder<MidnightRolloverWorker>()
                .setInputData(workDataOf(MidnightRolloverWorker.KEY_FROM_BOOT to true))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
