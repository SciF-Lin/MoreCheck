package com.morecheck.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.reminder.ReminderScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(
    private val repository: TaskRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    val tasks: StateFlow<List<CheckTask>> =
        repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteTask(task: CheckTask, deleteHistory: Boolean) {
        viewModelScope.launch {
            reminderScheduler.cancel(task.id)
            repository.deleteTask(task, deleteHistory)
        }
    }
}
