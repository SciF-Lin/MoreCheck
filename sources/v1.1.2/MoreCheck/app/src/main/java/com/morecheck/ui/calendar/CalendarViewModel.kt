package com.morecheck.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class CalendarViewModel(private val repository: TaskRepository) : ViewModel() {

    val tasks: StateFlow<List<CheckTask>> =
        repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** date ("yyyy-MM-dd") -> set of task ids checked that day. */
    val recordsByDate: StateFlow<Map<String, Set<Long>>> =
        repository.allRecords
            .map { records ->
                records.groupBy { it.date }
                    .mapValues { (_, list) -> list.map { it.taskId }.toSet() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 补打卡：为过去的某一天补一条打卡记录。 */
    fun backfill(taskId: Long, date: LocalDate) {
        viewModelScope.launch { repository.checkInOnDate(taskId, date) }
    }

    /** 撤销某一天的打卡记录。 */
    fun undo(taskId: Long, date: LocalDate) {
        viewModelScope.launch { repository.undoCheckIn(taskId, date) }
    }
}
