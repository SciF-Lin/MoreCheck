package com.morecheck.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morecheck.data.CheckRecord
import com.morecheck.di.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 小组件「一键打卡」：不打开界面，直接补上今天第一个未打卡任务并刷新全部实例。
 */
class WidgetCheckInReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CHECK_IN = "com.morecheck.action.WIDGET_CHECK_IN"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CHECK_IN) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                checkInFirstUndone(appContext)
                WidgetRefresh.refreshAllNow(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun checkInFirstUndone(context: Context) {
        val dao = context.appContainer.database.checkDao()
        val today = LocalDate.now().toString()
        val activeTasks = dao.getActiveTasksOnce()
        val checkedIds = dao.checkedTaskIdsOnDate(today).toHashSet()
        val target = activeTasks.firstOrNull { it.id !in checkedIds } ?: return
        val now = System.currentTimeMillis()
        // 小组件写入不产生墓碑、不触发推送，下次打开 App 或每日同步会被 LWW 正常带上去。
        // 这里只落一条本地记录：upsertRecord 走 REPLACE，updatedAt 取当前时间，
        // 保证它比任何旧行新，能被同步引擎按 LWW 正常推上云端。
        dao.upsertRecord(
            CheckRecord(
                taskId = target.id,
                date = today,
                checkedAt = now,
                updatedAt = now
            )
        )
    }
}
