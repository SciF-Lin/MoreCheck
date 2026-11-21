package com.morecheck.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.morecheck.MoreCheckApp
import com.morecheck.ui.calendar.CalendarViewModel
import com.morecheck.ui.home.HomeViewModel
import com.morecheck.ui.records.RecordsViewModel
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.tasks.TaskEditViewModel
import com.morecheck.ui.tasks.TasksViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        initializer { HomeViewModel(app().container.taskRepository) }
        initializer { CalendarViewModel(app().container.taskRepository) }
        initializer { RecordsViewModel(app().container.taskRepository) }
        initializer { SettingsViewModel(app().container.settingsRepository) }
        initializer {
            TasksViewModel(app().container.taskRepository, app().container.reminderScheduler)
        }
        initializer {
            TaskEditViewModel(app().container.taskRepository, app().container.reminderScheduler)
        }
    }
}

private fun CreationExtras.app(): MoreCheckApp =
    this[APPLICATION_KEY] as MoreCheckApp
