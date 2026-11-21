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
    /**
     * 「清空全部数据」的结果（需求 9）。
     *
     * 携带的是**清掉**的计数（不是剩下的 0），这样界面那句提示才带信息量：
     * 「已清空 12 个任务、340 条打卡记录」比「已清空数据」更能让用户确认
     * 自己删掉的是哪一批东西。
     */
    data class Cleared(val tasks: Int, val records: Int) : BackupResult
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

    /**
     * 清空全部任务与打卡记录（需求 9）。
     *
     * ## 二次确认放在**界面侧**，不在这里
     *
     * 本方法**假定**用户已经确认过。确认框是 Compose 层的 `AlertDialog`，
     * ViewModel 里既拿不到也不该持有对话框状态——把它留在界面侧，
     * 本方法只负责"真正动手"这一段。
     *
     * ## 为什么它也走 `busy` + `result`（而不是另造一套）
     *
     * 三个破坏性/耗时动作共用同一套反馈：
     * - `busy` 让三个按钮同时置灰、显示转圈——清空虽然快，但**并发触发**
     *   （例如清空的同时点导出）会产生"用户以为导出成功、其实数据库正在被清空"的错觉；
     * - `result` 走同一条 `LaunchedEffect(result)` 通道把结果转成 `onMessage`。
     *
     * ## 异常处理
     *
     * 数据库清空失败（磁盘满 / 数据库被锁）会抛，此时 `_busy` 必须复原，
     * 否则按钮永久置灰。因此 `getOrElse` 与成功路径**同样**经过
     * `_result.value = outcome` 与 `_busy.value = false` 两行——它们写在
     * `runCatching` 之外，不会被异常跳过。
     */
    fun clearAll() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { repository.clearAll() }
            }.fold(
                onSuccess = { (tasks, records) -> BackupResult.Cleared(tasks, records) },
                onFailure = { BackupResult.Failed(it.message ?: "清空失败") }
            )

            _result.value = outcome
            _busy.value = false
        }
    }
}
