package com.morecheck.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.TimerSession
import com.morecheck.util.DayState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TimelineEntry(val task: CheckTask, val date: LocalDate, val checkedAt: Long)
data class TimelineDay(val date: LocalDate, val entries: List<TimelineEntry>)

class RecordsViewModel(repository: TaskRepository) : ViewModel() {

    /**
     * date -> completion rate (0..1). Only contains dates where at least one
     * task already existed, so days before any task are left empty in the heatmap.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val heatmap: StateFlow<Map<LocalDate, Float>> =
        combine(repository.tasks, repository.allRecords, DayState.today) { tasks, records, today ->
            val zone = ZoneId.systemDefault()
            val createdByTask = tasks.associate { t ->
                t.id to Instant.ofEpochMilli(t.createdAt).atZone(zone).toLocalDate()
            }
            val checkedByDate = records.groupBy { it.date }
                .mapValues { (_, v) -> v.map { it.taskId }.toSet() }

            val minDate = createdByTask.values.minOrNull() ?: return@combine emptyMap()
            val result = mutableMapOf<LocalDate, Float>()
            var d = minDate
            while (!d.isAfter(today)) {
                val existed = tasks.count { t -> !d.isBefore(createdByTask.getValue(t.id)) }
                if (existed > 0) {
                    val checked = checkedByDate[d.toString()].orEmpty()
                    val done = tasks.count { t -> t.id in checked }
                    result[d] = done.toFloat() / existed
                }
                d = d.plusDays(1)
            }
            result
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** All records, newest first, grouped by date. */
    val timeline: StateFlow<List<TimelineDay>> =
        combine(repository.tasks, repository.allRecords) { tasks, records ->
            val taskMap = tasks.associateBy { it.id }
            records
                .mapNotNull { r -> taskMap[r.taskId]?.let { TimelineEntry(it, LocalDate.parse(r.date), r.checkedAt) } }
                .sortedWith(compareByDescending<TimelineEntry> { it.date }.thenByDescending { it.checkedAt })
                .groupBy { it.date }
                .map { (date, entries) -> TimelineDay(date, entries) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 走完的倒计时会话（新的在前），记录页「计时记录」卡消费。 */
    val timerSessions: StateFlow<List<TimerSession>> =
        repository.timerSessions
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
