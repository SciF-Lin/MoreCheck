package com.morecheck.di

import android.content.Context
import com.morecheck.data.AuthRepository
import com.morecheck.data.BackupRepository
import com.morecheck.data.CheckDatabase
import com.morecheck.data.SettingsRepository
import com.morecheck.data.TaskRepository
import com.morecheck.reminder.NotificationHelper
import com.morecheck.reminder.ReminderScheduler

/** Manual dependency container (no reflection framework needed). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: CheckDatabase = CheckDatabase.get(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val authRepository = AuthRepository(settingsRepository)
    val taskRepository = TaskRepository(database.checkDao(), settingsRepository)
    val backupRepository = BackupRepository(database.checkDao(), settingsRepository)
    val notificationHelper = NotificationHelper(appContext)
    val reminderScheduler = ReminderScheduler(appContext, taskRepository, notificationHelper)
}
