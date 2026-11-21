package com.morecheck.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.CheckTask
import com.morecheck.data.TaskRepository
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.data.normalizeNote
import com.morecheck.reminder.ReminderScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * @param cloudSync 云同步引擎（v3，可空）。新建 / 编辑任务后轻量推送一次。
 */
class TaskEditViewModel(
    private val repository: TaskRepository,
    private val reminderScheduler: ReminderScheduler,
    private val cloudSync: CloudSyncRepository? = null
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

    /**
     * 保存任务。
     *
     * 之前这里没有任何错误处理：写入若抛异常，会被 `viewModelScope.launch` 直接吞掉，
     * 界面表现为「点了保存、页面返回了，但列表里没有新任务」——也就是查不出原因的静默失败。
     * 现在改为把失败原因回传，由界面提示，便于定位是数据库、提醒排期还是别的问题。
     *
     * @param onResult `true` 表示保存成功；`false` 并附带原因表示失败（此时不返回上级页面）。
     */
    fun save(
        name: String,
        icon: String,
        color: Int,
        reminderTime: String?,
        note: String?,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                if (editingId < 0L) {
                    val id = repository.addTask(name, icon, color, reminderTime, note)
                    if (reminderTime != null) {
                        repository.getTask(id)?.let { reminderScheduler.schedule(it) }
                    }
                } else {
                    val base = _task.value ?: error("找不到要编辑的任务")
                    val updated = base.copy(
                        name = name.trim(),
                        icon = icon,
                        color = color,
                        reminderTime = reminderTime,
                        note = note.normalizeNote()
                    )
                    repository.updateTask(updated)
                    if (reminderTime != null) {
                        reminderScheduler.schedule(updated)
                    } else {
                        reminderScheduler.cancel(updated.id)
                    }
                }
            }
            result.fold(
                onSuccess = {
                    onResult(true, null)
                    // 新建 / 编辑都要同步出去。放在 onResult 之后调用：
                    // 失败是静默的（pushAfterCheckIn 内部吞异常），不该影响保存成功的反馈。
                    cloudSync?.pushAfterCheckIn()
                },
                onFailure = { onResult(false, it.message ?: it::class.java.simpleName) }
            )
        }
    }
}
