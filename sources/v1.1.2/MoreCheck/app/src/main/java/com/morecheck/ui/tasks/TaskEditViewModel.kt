package com.morecheck.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TaskEditViewModel(
    private val repository: TaskRepository,
    private val reminderScheduler: ReminderScheduler
) : ViewModel() {

    private val _task = MutableStateFlow<CheckTask?>(null)
    val task: StateFlow<CheckTask?> = _task

    private var editingId = -1L

    fun load(taskId: Long) {
        editingId = taskId
        if (taskId >= 0L) {
            viewModelScope.launch { _task.value = repository.getTask(taskId) }
        } else {
            _task.value = null
        }
    }

    fun save(
        name: String,
        icon: String,
        color: Int,
        reminderTime: String?,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            if (editingId < 0L) {
                val id = repository.addTask(name, icon, color, reminderTime)
                if (reminderTime != null) {
                    repository.getTask(id)?.let { reminderScheduler.schedule(it) }
                }
            } else {
                val base = _task.value ?: return@launch
                val updated = base.copy(
                    name = name.trim(),
                    icon = icon,
                    color = color,
                    reminderTime = reminderTime
                )
                repository.updateTask(updated)
                if (reminderTime != null) {
                    reminderScheduler.schedule(updated)
                } else {
                    reminderScheduler.cancel(updated.id)
                }
            }
            onComplete()
        }
    }
}
