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

    // 任务管理页要看得到归档任务（才能恢复），所以用全量 tasks，而非主页的 activeTasks。
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

    /** 归档 / 取消归档（本机组织字段，不刷新 LWW 时间戳、不推云端）。 */
    fun setArchived(task: CheckTask, archived: Boolean) {
        updateOrganization(task.id) { it.copy(archived = archived) }
    }

    /** 暂停 / 恢复（本机组织字段，不刷新 LWW 时间戳、不推云端）。 */
    fun setPaused(task: CheckTask, paused: Boolean) {
        updateOrganization(task.id) { it.copy(paused = paused) }
    }

    /**
     * 组织字段写回的统一入口。
     *
     * 先按 id 取库里最新一行再改，而不是直接用列表里的旧快照：云同步可能在
     * 「列表渲染」和「点击」之间更新了名称 / 颜色，整行覆盖会把那些改动悄悄冲掉。
     * updateTaskLocalOnly 不做字段级合并，因此这一步由调用方保证。
     */
    private fun updateOrganization(taskId: Long, transform: (CheckTask) -> CheckTask) {
        viewModelScope.launch {
            val current = repository.getTask(taskId) ?: return@launch
            repository.updateTaskLocalOnly(transform(current))
        }
    }
}
