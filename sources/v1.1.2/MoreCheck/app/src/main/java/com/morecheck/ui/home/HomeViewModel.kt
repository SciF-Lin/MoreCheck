package com.morecheck.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.util.DayState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(private val repository: TaskRepository) : ViewModel() {

    val tasks: StateFlow<List<CheckTask>> =
        repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val today: StateFlow<LocalDate> = DayState.today

    val checkedToday: StateFlow<Set<Long>> =
        combine(repository.allRecords, DayState.today) { records, today ->
            records.filter { it.date == today.toString() }.map { it.taskId }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val doneCount: StateFlow<Int> =
        combine(checkedToday, tasks) { checked, taskList ->
            taskList.count { it.id in checked }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun checkIn(taskId: Long) {
        viewModelScope.launch { repository.checkIn(taskId) }
    }

    fun undo(taskId: Long) {
        viewModelScope.launch { repository.undoCheckIn(taskId) }
    }

    fun checkInMany(ids: Set<Long>) {
        viewModelScope.launch { ids.forEach { repository.checkIn(it) } }
    }
}
