package com.morecheck.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.morecheck.MoreCheckApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (taskId <= 0L) return

        val pendingResult = goAsync()
        val app = context.applicationContext as MoreCheckApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val task = app.container.taskRepository.getTask(taskId)
                if (task != null) {
                    app.container.notificationHelper.showReminder(task.id, task.name, task.icon)
                    task.reminderTime?.let { app.container.reminderScheduler.scheduleNext(task.id, it) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
