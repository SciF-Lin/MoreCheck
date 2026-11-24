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
     * v5 组织字段（分组 / 标签 / 归档 / 暂停）只在本机保存、不进云同步：
     * - 只改了这四个字段时走 [TaskRepository.updateTaskLocalOnly]，不刷新 LWW 时间戳，
     *   避免制造一次内容没变的云端 upsert；
     * - 一旦名称 / 图标 / 颜色 / 备注 / 提醒时间这些会同步的字段有变动，
     *   仍走原来的 [TaskRepository.updateTask] 刷新时间戳并正常推送。
     *
     * @param onResult `true` 表示保存成功；`false` 并附带原因表示失败（此时不返回上级页面）。
     */
    fun save(
        name: String,
        icon: String,
        color: Int,
        reminderTime: String?,
        note: String?,
        groupName: String?,
        tags: String?,
        archived: Boolean,
        paused: Boolean,
        onResult: (success: Boolean, message: String?) -> Unit
    ) {
        viewModelScope.launch {
            val result = runCatching {
                // 与 addTask / updateTask 一致：空白一律归一成 null，兼容旧数据。
                val normalizedGroup = groupName.normalizeNote()
                val normalizedTags = tags.normalizeNote()

                if (editingId < 0L) {
                    // 新建：addTask 只收会同步的字段，groupName / tags 一并带过去。
                    val id = repository.addTask(
                        name, icon, color, reminderTime, note, normalizedGroup, normalizedTags
                    )
                    val inserted = repository.getTask(id)
                    // 新建页同样暴露「归档 / 暂停」开关，而 addTask 不收这两个本机字段，
                    // 补一次本地写回即可（不刷新 updatedAt、不推云端）。
                    val created = if (inserted != null && (archived || paused)) {
                        val withOrg = inserted.copy(archived = archived, paused = paused)
                        repository.updateTaskLocalOnly(withOrg)
                        withOrg
                    } else {
                        inserted
                    }
                    if (reminderTime != null) {
                        created?.let { reminderScheduler.schedule(it) }
                    }
                } else {
                    val base = _task.value ?: error("找不到要编辑的任务")
                    val updated = base.copy(
                        name = name.trim(),
                        icon = icon,
                        color = color,
                        reminderTime = reminderTime,
                        note = note.normalizeNote(),
                        groupName = normalizedGroup,
                        tags = normalizedTags,
                        archived = archived,
                        paused = paused
                    )
                    // 只比较会进云同步的字段；组织字段的差异不算「需要推送的改动」。
                    val syncFieldsChanged = base.name != updated.name ||
                        base.icon != updated.icon ||
                        base.color != updated.color ||
                        base.reminderTime != updated.reminderTime ||
                        base.note != updated.note
                    if (syncFieldsChanged) {
                        repository.updateTask(updated)
                    } else {
                        repository.updateTaskLocalOnly(updated)
                    }
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
                    // 组织字段的本地写回不刷 updatedAt，这里即便推送也不会把它们带上去。
                    cloudSync?.pushAfterCheckIn()
                },
                onFailure = { onResult(false, it.message ?: it::class.java.simpleName) }
            )
        }
    }
}
