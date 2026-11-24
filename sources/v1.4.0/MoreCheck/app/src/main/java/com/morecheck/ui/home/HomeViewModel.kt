package com.morecheck.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.domain.StreakRecord
import com.morecheck.domain.StreakSummary
import com.morecheck.domain.computeStreakSummary
import com.morecheck.ui.widget.WidgetRefresh
import com.morecheck.util.DayState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * @param cloudSync 云同步引擎（v3）。**可空**：未接线时一切行为与改造前一致。
 *   接线后，每次打卡 / 撤销 / 删除都会触发一次轻量推送
 *   （[CloudSyncRepository.pushAfterCheckIn] 内部会检查开关与登录态，
 *   失败静默，不弹提示、不写同步状态）。
 * @param appContext 只用于刷新桌面小组件；取 applicationContext，
 *   不持有 Activity，因此不会泄漏。为空时（测试 / 旧调用方）跳过刷新。
 */
class HomeViewModel(
    private val repository: TaskRepository,
    private val cloudSync: CloudSyncRepository? = null,
    private val appContext: android.content.Context? = null
) : ViewModel() {

    /** 每次写入后让桌面进度立即跟上，而不是等 30 分钟的系统周期。 */
    private fun refreshWidget() {
        appContext?.let { WidgetRefresh.refreshAll(it) }
    }

    // 主页只认未归档任务：归档任务从主页与提醒里隐藏，是 v5 的组织语义，
    // 用 activeTasks 而不是 tasks，一行便不会让已归档任务重新冒回主页。
    val tasks: StateFlow<List<CheckTask>> =
        repository.activeTasks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val today: StateFlow<LocalDate> = DayState.today

    val checkedToday: StateFlow<Set<Long>> =
        combine(repository.allRecords, DayState.today) { records, today ->
            records.filter { it.date == today.toString() }.map { it.taskId }.toSet()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val doneCount: StateFlow<Int> =
        combine(checkedToday, tasks) { checked, taskList ->
            // 暂停任务不计入今日目标：它灰显、可手动打卡，但"已完成"数不该把它算进去。
            // 这里只在计数时排除 paused，与 streakSummary 的 activeTaskIds 同口径。
            taskList.count { !it.paused && it.id in checked }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 主页「连续打卡」卡片的全部派生量。
     *
     * 为什么把摘要放在 ViewModel 算、而不是让 Composable 自己数天数：
     * 同一个 [computeStreakSummary] 纯函数同时被统计页复用，连续天数、热力条
     * 在两处必须逐字一致；放在一处算，UI 只负责渲染，不会各写一份逻辑后漂移。
     *
     * activeTaskIds 里排除 paused 任务，与 doneCount 同口径：暂停任务的历史记录
     * 仍在库里，但不该把连续记录续上。stateIn 初值取 [StreakSummary.EMPTY]，
     * 首帧不伪造任何达标天数。
     */
    val streakSummary: StateFlow<StreakSummary> =
        combine(repository.allRecords, tasks, today) { records, taskList, date ->
            val activeIds = taskList.filterNot { it.paused }.map { it.id }.toSet()
            computeStreakSummary(
                records.map { StreakRecord(it.taskId, it.date) },
                activeIds,
                date
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreakSummary.EMPTY)

    fun checkIn(taskId: Long) {
        viewModelScope.launch {
            repository.checkIn(taskId)
            refreshWidget()
            cloudSync?.pushAfterCheckIn()
        }
    }

    fun undo(taskId: Long) {
        viewModelScope.launch {
            repository.undoCheckIn(taskId)
            refreshWidget()
            // 撤销 = 删除一条记录：墓碑要推出去，否则另一台设备会把打卡拉回来。
            cloudSync?.pushAfterCheckIn()
        }
    }

    fun checkInMany(ids: Set<Long>) {
        viewModelScope.launch {
            ids.forEach { repository.checkIn(it) }
            refreshWidget()
            // 批量打卡只推一次：推送本身是全量的，分批推纯属浪费网络。
            cloudSync?.pushAfterCheckIn()
        }
    }

    /**
     * 删除任务（含其打卡记录）。
     *
     * 主页的任务圆圈支持长按 / 右键删除，走这里。
     */
    fun deleteTask(task: CheckTask) {
        viewModelScope.launch {
            repository.deleteTask(task, deleteHistory = true)
            refreshWidget()
            // 删除产生墓碑（任务 + 级联记录），必须推出去。
            cloudSync?.pushAfterCheckIn()
        }
    }
}
