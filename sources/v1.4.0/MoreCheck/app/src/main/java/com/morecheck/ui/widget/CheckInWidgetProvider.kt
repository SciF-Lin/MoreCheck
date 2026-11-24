package com.morecheck.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面「今日打卡」小组件。
 *
 * onUpdate 由 AppWidgetProvider.onReceive 同步分发，因此这里调用 goAsync()
 * 拿到的 PendingResult 仍然有效：把数据库读取挪到 IO 协程，
 * 系统更新周期（30 分钟）与首次添加都不会阻塞主线程。
 */
class CheckInWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetRefresh.refreshIdsNow(
                    context.applicationContext,
                    appWidgetManager,
                    appWidgetIds
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
