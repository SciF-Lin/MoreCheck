package com.morecheck.ui

import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.morecheck.MoreCheckApp
import com.morecheck.ui.agent.AiChatViewModel
import com.morecheck.ui.auth.AuthViewModel
import com.morecheck.ui.backup.BackupViewModel
import com.morecheck.ui.calendar.CalendarViewModel
import com.morecheck.ui.home.HomeViewModel
import com.morecheck.ui.records.RecordsViewModel
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.tasks.TaskEditViewModel
import com.morecheck.ui.tasks.TasksViewModel

object AppViewModelProvider {
    val Factory = viewModelFactory {
        // v3：打卡 / 撤销 / 删除 / 新建 / 编辑任务的 ViewModel 都接云同步引擎，
        // 写操作完成后触发一次轻量推送（内部检查开关与登录态，失败静默）。
        initializer { HomeViewModel(app().container.taskRepository, app().container.cloudSyncRepository) }
        initializer { CalendarViewModel(app().container.taskRepository, app().container.cloudSyncRepository) }
        initializer { RecordsViewModel(app().container.taskRepository) }
        initializer { SettingsViewModel(app().container.settingsRepository) }
        // AuthViewModel 多接一个「开发者选项」仓库：登录 / 注册要在点击那一刻读到
        // 「验证码测试」是否生效，并与「发送验证码」记下的六位码比对。
        initializer {
            AuthViewModel(
                app().container.authRepository,
                app().container.developerOptionsRepository
            )
        }
        initializer { TabBadgeViewModel() }
        initializer { BackupViewModel(app().container.backupRepository, app().container.taskRepository) }
        initializer {
            TasksViewModel(
                app().container.taskRepository,
                app().container.reminderScheduler,
                app().container.cloudSyncRepository
            )
        }
        initializer {
            TaskEditViewModel(
                app().container.taskRepository,
                app().container.reminderScheduler,
                app().container.cloudSyncRepository
            )
        }
        // 智能体（AI 对话）：历史存文件、设置存独立 DataStore，网络请求走仓库层。
        initializer {
            AiChatViewModel(
                com.morecheck.data.AiChatStore,
                app().container.aiChatRepository
            )
        }
    }
}

private fun CreationExtras.app(): MoreCheckApp =
    this[APPLICATION_KEY] as MoreCheckApp
