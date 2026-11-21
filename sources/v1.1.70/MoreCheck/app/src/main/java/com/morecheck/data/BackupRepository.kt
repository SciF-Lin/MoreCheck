package com.morecheck.data

import kotlinx.coroutines.flow.first

/**
 * 备份与恢复。
 *
 * 目的：应用数据只存在 `/data/user/0/<包名>` 私有目录里，**卸载即全部丢失**。
 * 提供导出到用户自选文件、以及从文件恢复的能力，避免再次发生不可逆的数据丢失。
 *
 * 恢复策略：**覆盖式**——先清空现有任务与记录，再写入备份内容。
 * 这样备份是唯一事实来源，不会出现「新旧数据混在一起」的中间态。
 */
class BackupRepository(
    private val dao: CheckDao,
    private val settings: SettingsRepository
) {

    /** 汇总当前全部数据，供导出。 */
    suspend fun export(): BackupData = BackupData(
        version = BackupData.CURRENT_VERSION,
        exportedAt = System.currentTimeMillis(),
        tasks = dao.getAllTasks(),
        records = dao.getAllRecords(),
        themeMode = settings.themeMode.first(),
        themeAccent = settings.themeAccent.first()
    )

    /**
     * 用备份内容覆盖本地数据。
     *
     * 返回 (任务数, 记录数) 便于向用户反馈。
     */
    suspend fun restore(data: BackupData): Pair<Int, Int> {
        // 先删记录再删任务：记录通过 taskId 外键引用任务。
        dao.deleteAllRecords()
        dao.deleteAllTasks()

        if (data.tasks.isNotEmpty()) {
            dao.insertTasksWithIds(data.tasks)
        }
        if (data.records.isNotEmpty()) {
            dao.insertRecords(data.records)
        }

        // 外观设置随备份一并恢复；缺失则保持当前设置不变。
        data.themeMode?.let { settings.setThemeMode(it) }
        data.themeAccent?.let { settings.setThemeAccent(it) }

        return data.tasks.size to data.records.size
    }
}
