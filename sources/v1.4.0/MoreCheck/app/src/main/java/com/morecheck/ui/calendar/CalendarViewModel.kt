package com.morecheck.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.ui.widget.WidgetRefresh
import com.morecheck.domain.StreakRecord
import com.morecheck.domain.StreakSummary
import com.morecheck.domain.computeStreakSummary
import com.morecheck.util.DayState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * date（"yyyy-MM-dd"）->（taskId -> 该次打卡的 checkedAt，epoch 毫秒）。
 *
 * 用 typealias 只是为了让这条状态在 ViewModel / Screen / 子组件的 5 处签名里读起来短一点。
 */
typealias CheckedAtByDate = Map<String, Map<Long, Long>>

/**
 * 「按任务完成率排行」的一行：近 30 天里该任务打了几天卡。
 *
 * 带上 name / icon / color 是为了让排行页直接渲染，不必再把 taskId 回查一次任务表；
 * 它们都是普通值（String / Int），不拖任何 Android 类型进这份状态。
 */
data class TaskRankingRow(
    val taskId: Long,
    val name: String,
    val icon: String,
    val color: Int,
    val days: Int
)

/**
 * 统计页下半区两页共用的数据快照：连续打卡摘要 + 任务完成率排行。
 *
 * 合成一份而不是两个 StateFlow：两页读的是同一批记录与同一批在册任务，
 * 拆开会让两处各自订阅、各自判"哪些任务在册"，口径迟早漂移。
 */
data class StreakDashboard(
    val summary: StreakSummary,
    val ranking: List<TaskRankingRow>
) {
    companion object {
        /** 空快照：stateIn 的初值，首帧不伪造任何天数或排行。 */
        val EMPTY = StreakDashboard(StreakSummary.EMPTY, emptyList())
    }
}

/**
 * @param cloudSync 云同步引擎（v3，可空）。接线后补打卡 / 撤销都会触发一次轻量推送；
 *   未接线时行为与改造前完全一致。
 */
class CalendarViewModel(
    private val repository: TaskRepository,
    private val cloudSync: CloudSyncRepository? = null,
    private val appContext: android.content.Context? = null
) : ViewModel() {

    /** 补打卡 / 撤销也要让桌面进度跟上（口径与 HomeViewModel 一致）。 */
    private fun refreshWidget() {
        appContext?.let { WidgetRefresh.refreshAll(it) }
    }

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
     * ⚠️ 语义坑（v1.2.3 起）：补打卡走 `TaskRepository.checkInOnDate`，写入的
     * `checkedAt` 是**用户在时间选择器里设置的时刻**（所补日期 + 所选 HH:mm），
     * 不再是补录那一刻。所以补上周三 08:30 的卡，任务名下方就显示 08:30，
     * 日历页底部的「距上次打卡」也按这个时刻倒推。
     * `updatedAt`（同步用）仍由 data 层写为补录那一刻，两者分工见仓库侧注释。
     */
    val recordsByDate: StateFlow<CheckedAtByDate> =
        repository.allRecords
            .map { records ->
                records.groupBy { it.date }
                    .mapValues { (_, list) -> list.associate { it.taskId to it.checkedAt } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * 统计页下半区两页的数据源：一次 combine 同时算出连续打卡摘要与完成率排行。
     *
     * 直接用 [recordsByDate] 而不是再订阅一次 allRecords：它本就是全量记录按日期
     * 分组的结果，摊平后即可喂给 [computeStreakSummary]，排行也顺手从同一张表数出来，
     * 少一份订阅、也保证两页看到的是同一帧数据。
     *
     * activeTaskIds 排除 paused 任务，与主页 HomeViewModel 的 streakSummary / doneCount
     * 同口径：暂停任务的历史记录仍在库里，但不该续连续纪录、也不该出现在排行里。
     * stateIn 初值取 [StreakDashboard.EMPTY]，空态语义干净。
     */
    val dashboard: StateFlow<StreakDashboard> =
        combine(recordsByDate, repository.activeTasks, DayState.today) { byDate, tasks, today ->
            val active = tasks.filterNot { it.paused }
            val activeIds = active.map { it.id }.toSet()

            // 摘要要的是全量历史（最长连续会往回翻），recordsByDate 已含全部日期，
            // 摊平成 (taskId, date) 列表即可，checkedAt 对"是否达标"没贡献所以丢掉。
            val flatRecords = buildList {
                byDate.forEach { (date, byTask) ->
                    byTask.keys.forEach { taskId -> add(StreakRecord(taskId, date)) }
                }
            }
            val summary = computeStreakSummary(flatRecords, activeIds, today)

            // 排行：近 30 天窗口里每个在册任务的打卡天数。
            // 内层 Map 是 taskId -> checkedAt，取 keys 就是当天打过卡的任务集合，
            // 每个 (任务, 日期) 至多计一次，正好是"天数"。
            val windowStart = today.minusDays(29)
            val daysByTask = mutableMapOf<Long, Int>()
            byDate.forEach { (dateStr, byTask) ->
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
                if (date.isBefore(windowStart) || date.isAfter(today)) return@forEach
                byTask.keys.forEach { taskId ->
                    if (taskId in activeIds) {
                        daysByTask[taskId] = (daysByTask[taskId] ?: 0) + 1
                    }
                }
            }
            val ranking = active.map { task ->
                TaskRankingRow(
                    taskId = task.id,
                    name = task.name,
                    icon = task.icon,
                    color = task.color,
                    days = daysByTask[task.id] ?: 0
                )
            }.sortedWith(compareByDescending<TaskRankingRow> { it.days }.thenBy { it.taskId })
            StreakDashboard(summary, ranking)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakDashboard.EMPTY)

    /**
     * 补打卡：为过去的某一天补一条打卡记录。
     *
     * @param time 用户为该天设置的打卡时刻（HH:mm）；与 [date] 合成 `checkedAt`。
     */
    fun backfill(taskId: Long, date: LocalDate, time: LocalTime) {
        viewModelScope.launch {
            val checkedAt = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.checkInOnDate(taskId, date, checkedAt)
            refreshWidget()
            cloudSync?.pushAfterCheckIn()
        }
    }

    /** 撤销某一天的打卡记录。 */
    fun undo(taskId: Long, date: LocalDate) {
        viewModelScope.launch {
            repository.undoCheckIn(taskId, date)
            refreshWidget()
            // 撤销 = 删除一条记录：墓碑要推出去。
            cloudSync?.pushAfterCheckIn()
        }
    }
}
