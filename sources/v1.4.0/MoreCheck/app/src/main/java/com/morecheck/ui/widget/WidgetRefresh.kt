package com.morecheck.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.morecheck.MainActivity
import com.morecheck.R
import com.morecheck.data.CheckDao
import com.morecheck.di.appContainer
import com.morecheck.ui.nav.AppTab
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 桌面「今日打卡」小组件的刷新中枢。
 *
 * Provider（系统定时 / 首次添加）、Receiver（一键打卡）、App 内打卡后
 * 三条路径都收敛到这里，保证「读 DAO 算状态、构造 RemoteViews、updateAppWidget」
 * 只有一份实现。
 */
object WidgetRefresh {

    /**
     * 同步可调用的刷新入口（fire-and-forget），供 App 内接线使用。
     *
     * 调用方不必持有协程作用域：MainActivity.onResume 或 HomeViewModel 打卡
     * 成功后直接调 [refreshAll] 即可。这里复用容器里的进程级 IO 作用域，
     * 避免每次刷新都新建一个 CoroutineScope，也避免被 Activity 生命周期取消。
     */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        appContext.appContainer.appScope.launch {
            refreshAllNow(appContext)
        }
    }

    /**
     * 在调用方已有的协程里刷新全部实例，返回时所有实例均已更新。
     * Provider / Receiver 的 goAsync 协程用它，不必再套一层 launch。
     */
    suspend fun refreshAllNow(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, CheckInWidgetProvider::class.java)
        )
        refreshIdsNow(appContext, manager, ids)
    }

    /**
     * 刷新指定 id。系统 APPWIDGET_UPDATE 只带来变化的 id，直接复用；
     * 数据库只在这一处读一次，多个实例共享同一份状态。
     */
    suspend fun refreshIdsNow(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val dao = context.applicationContext.appContainer.database.checkDao()
        val state = loadState(dao)
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, buildRemoteViews(context, id, state))
        }
    }

    private suspend fun loadState(dao: CheckDao): WidgetTextState {
        val today = LocalDate.now().toString()
        val activeTasks = dao.getActiveTasksOnce()
        val checkedIds = dao.checkedTaskIdsOnDate(today).toHashSet()
        // 分母只算未归档未暂停任务；分子取今日已打卡 id 与该集合的交集。
        val done = activeTasks.count { it.id in checkedIds }
        return WidgetText.of(done, activeTasks.size)
    }

    private fun buildRemoteViews(
        context: Context,
        appWidgetId: Int,
        state: WidgetTextState
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_checkin)
        views.setTextViewText(R.id.widget_progress, state.progress)
        views.setTextViewText(R.id.widget_hint, context.getString(hintResOf(state.hint)))

        val openHome = openHomePendingIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, openHome)
        views.setOnClickPendingIntent(R.id.widget_title, openHome)

        when (state.button) {
            WidgetButton.CHECK_IN -> {
                views.setTextViewText(
                    R.id.widget_btn_checkin,
                    context.getString(R.string.widget_checkin_button)
                )
                views.setOnClickPendingIntent(
                    R.id.widget_btn_checkin,
                    checkInPendingIntent(context, appWidgetId)
                )
            }
            WidgetButton.DISABLED -> {
                // 资源里没有单独的「已完成」按钮串，且本轮不改 res，故完成态内联字面量；
                // 无任务态则保留布局默认文案。
                views.setTextViewText(
                    R.id.widget_btn_checkin,
                    state.disabledLabel ?: context.getString(R.string.widget_checkin_button)
                )
                // 禁用态点击只进主页，不会误写一条记录。
                views.setOnClickPendingIntent(R.id.widget_btn_checkin, openHome)
            }
        }
        return views
    }

    private fun hintResOf(hint: WidgetHint): Int = when (hint) {
        WidgetHint.CHECK_IN_TIP -> R.string.widget_checkin_hint
        WidgetHint.NO_TASK -> R.string.widget_checkin_no_task
        WidgetHint.ALL_DONE -> R.string.widget_checkin_all_done
    }

    private fun openHomePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_NAV_TAB
            putExtra(MainActivity.EXTRA_TAB_TARGET, AppTab.HOME.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_HOME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun checkInPendingIntent(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetCheckInReceiver::class.java).apply {
            action = WidgetCheckInReceiver.ACTION_CHECK_IN
        }
        // requestCode 用 widgetId：多个小组件实例各自持有独立 PendingIntent，互不覆盖。
        return PendingIntent.getBroadcast(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private const val REQUEST_OPEN_HOME = 0
}
