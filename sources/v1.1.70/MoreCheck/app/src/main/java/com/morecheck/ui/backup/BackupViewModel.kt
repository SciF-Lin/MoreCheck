package com.morecheck.ui.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.BackupData
import com.morecheck.data.BackupRepository
import com.morecheck.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份页的一次性结果，用于弹提示。 */
sealed interface BackupResult {
    data class Exported(val fileName: String, val tasks: Int, val records: Int) : BackupResult
    data class Restored(val tasks: Int, val records: Int) : BackupResult
    data class Failed(val reason: String) : BackupResult
}

/**
 * 备份 / 恢复（任务3 的「数据备份」占位入口落地）。
 *
 * 走 Storage Access Framework：文件由用户自己选，因此**不需要任何存储权限**。
 */
class BackupViewModel(
    private val repository: BackupRepository,
    taskRepository: TaskRepository
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _result = MutableStateFlow<BackupResult?>(null)
    val result = _result.asStateFlow()

    /**
     * 当前数据概况：任务数与记录数。
     *
     * 直接由数据库流派生，而不是在 `init` 里做一次性查询——
     * 后者依赖执行时机，实测容易一直停在「统计中…」。
     * 派生写法既必然同步，导入导出后也会自动刷新。
     */
    val summary = combine(taskRepository.tasks, taskRepository.allRecords) { tasks, records ->
        tasks.size to records.size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun consumeResult() {
        _result.value = null
    }

    /** 导出到用户选定的文件。 */
    fun export(context: Context, uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                val data = withContext(Dispatchers.IO) { repository.export() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(data.toJson().toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                BackupResult.Exported(
                    fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "备份文件",
                    tasks = data.tasks.size,
                    records = data.records.size
                )
            }.getOrElse { BackupResult.Failed(it.message ?: "导出失败") }

            _result.value = outcome
            _busy.value = false
        }
    }

    /** 从用户选定的文件恢复（覆盖式）。 */
    fun restore(context: Context, uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                }
                // 容错：部分编辑器会在文件头写入 BOM。
                val parsed = BackupData.fromJson(text.removePrefix("\uFEFF"))
                val (tasks, records) = withContext(Dispatchers.IO) { repository.restore(parsed) }
                BackupResult.Restored(tasks = tasks, records = records)
            }.getOrElse {
                BackupResult.Failed(
                    when (it) {
                        is IllegalArgumentException -> it.message ?: "备份文件内容有误"
                        else -> it.message ?: "恢复失败"
                    }
                )
            }

            _result.value = outcome
            _busy.value = false
        }
    }
}
