package com.morecheck.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.cloud.CloudSyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * date（"yyyy-MM-dd"）->（taskId -> 该次打卡的 checkedAt，epoch 毫秒）。
 *
 * 用 typealias 只是为了让这条状态在 ViewModel / Screen / 子组件的 5 处签名里读起来短一点。
 */
typealias CheckedAtByDate = Map<String, Map<Long, Long>>

/**
 * @param cloudSync 云同步引擎（v3，可空）。接线后补打卡 / 撤销都会触发一次轻量推送；
 *   未接线时行为与改造前完全一致。
 */
class CalendarViewModel(
    private val repository: TaskRepository,
    private val cloudSync: CloudSyncRepository? = null
) : ViewModel() {

    val tasks: StateFlow<List<CheckTask>> =
        repository.tasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * date ("yyyy-MM-dd") -> (taskId -> checkedAt)。
     *
     * 相比原来的 `Map<String, Set<Long>>`（只留 taskId 集合），这里额外保留了 checkedAt，
     * 因为日期详情弹层要在「已打卡」任务名下方显示打卡时刻。
     *
     * 之所以直接改类型而不是新加一个并列状态：全仓库 grep 下来只有日历页消费它
     * （CalendarScreen 自己 + MonthGrid/DayCell + DayDetailDialog），没有别的调用方；
     * 日历页里唯一只关心「有没有打卡」的地方（DayCell 的圆点）取 `.keys` 即可，
     * 于是没必要维护两份内容几乎重复的 Flow。
     *
     * ⚠️ 语义坑：补打卡走 `TaskRepository.checkInOnDate`，它写入的是
     * `System.currentTimeMillis()`——即**补录那一刻**，而不是所补历史日期的时刻。
     * 所以补打卡出来的记录会显示操作时间（例如给上周三补卡，显示的是今天的 HH:mm）。
     * 本页只负责展示，不去改动 data 层的写入语义。
     */
    val recordsByDate: StateFlow<CheckedAtByDate> =
        repository.allRecords
            .map { records ->
                records.groupBy { it.date }
                    .mapValues { (_, list) -> list.associate { it.taskId to it.checkedAt } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 补打卡：为过去的某一天补一条打卡记录。 */
    fun backfill(taskId: Long, date: LocalDate) {
        viewModelScope.launch {
            repository.checkInOnDate(taskId, date)
            cloudSync?.pushAfterCheckIn()
        }
    }

    /** 撤销某一天的打卡记录。 */
    fun undo(taskId: Long, date: LocalDate) {
        viewModelScope.launch {
            repository.undoCheckIn(taskId, date)
            // 撤销 = 删除一条记录：墓碑要推出去。
            cloudSync?.pushAfterCheckIn()
        }
    }
}
