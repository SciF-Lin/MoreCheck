package com.morecheck.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morecheck.MainActivity
import com.morecheck.R

class NotificationHelper(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        const val CHANNEL_REMINDER = "reminder_channel"
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_REMINDER,
            "打卡提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "每日打卡提醒"
        }
        manager.createNotificationChannel(channel)
    }

    fun showReminder(taskId: Long, taskName: String, icon: String) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("该打卡啦 $icon")
            .setContentText("「$taskName」等你打卡，坚持就是胜利！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(taskId.toInt(), notification)
        } catch (_: SecurityException) {
            // Notification permission denied — silently skip.
        }
    }
}
