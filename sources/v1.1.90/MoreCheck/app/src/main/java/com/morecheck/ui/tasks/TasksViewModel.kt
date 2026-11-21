package com.morecheck.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.reminder.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @param cloudSync 云同步引擎（v3，可空）。删除任务产生墓碑，接线后会轻量推送出去。
 */
class TasksViewModel(
    private val repository: TaskRepository,
    private val reminderScheduler: ReminderScheduler,
    private val cloudSync: CloudSyncRepository? = null
) : ViewModel() {

    val tasks: StateFlow<List<CheckTask>> =
        repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteTask(task: CheckTask, deleteHistory: Boolean) {
        viewModelScope.launch {
            reminderScheduler.cancel(task.id)
            repository.deleteTask(task, deleteHistory)
            // 删除产生墓碑（任务 + 级联记录），必须推出去。
            cloudSync?.pushAfterCheckIn()
        }
    }
}
