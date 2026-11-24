package com.morecheck.data

import android.content.Context
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 备份与恢复。
 *
 * 目的：应用数据只存在 `/data/user/0/<包名>` 私有目录里，**卸载即全部丢失**。
 * 提供导出到用户自选文件、以及从文件恢复的能力，避免再次发生不可逆的数据丢失。
 *
 * 恢复策略：**覆盖式**——先清空现有任务与记录，再写入备份内容。
 * 这样备份是唯一事实来源，不会出现「新旧数据混在一起」的中间态。
 *
 * ## 与云同步的关系（v3 起）
 *
 * 本仓库同时被**本地文件备份**与**云端整包备份**复用（后者由
 * `CloudSyncRepository` 下载后调用 [restore]），因此两个入口都必须维持
 * 同步簿记的完整：
 *
 * - [restore] 把写入行的 `updated_at` 统一刷新为恢复时刻，并给"被恢复操作
 *   删掉的行"（本地有、备份里没有）补墓碑——否则下次同步会把它们从云端
 *   原样拉回来，恢复被无声地撤销；
 * - [clearAll] 给**全部**行补墓碑——否则已登录用户清空后，下一次自动同步
 *   就会把云端数据整个拉回来，"清空"变成一次无操作。
 *
 * 墓碑只是同步簿记，不是给用户的撤销机制；清空 / 覆盖的不可逆性不变。
 */
class BackupRepository(
    private val dao: CheckDao,
    private val settings: SettingsRepository,
    private val appContext: Context
) {

    /** 汇总当前全部数据，供导出。 */
    suspend fun export(): BackupData {
        val note = NotebookStore.read(appContext)
        return BackupData(
            version = BackupData.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            tasks = dao.getAllTasks(),
            records = dao.getAllRecords(),
            themeMode = settings.themeMode.first(),
            themeAccent = settings.themeAccent.first(),
            // 记事本：文字直接进 JSON，图片只记**文件名**（字节由云端轨道的
            // Storage 负责，本地轨道则本就在同一台设备上）。
            notebookText = note?.text?.takeIf { it.isNotEmpty() },
            notebookImages = note?.imagePaths?.map { File(it).name }.orEmpty()
        )
    }

    /**
     * 用备份内容覆盖本地数据。
     *
     * 返回 (任务数, 记录数) 便于向用户反馈。
     */
    suspend fun restore(data: BackupData): Pair<Int, Int> {
        // 删前快照：用于算出"这次恢复删掉了哪些行"，给它们补墓碑。
        val tasksBefore = dao.getAllTasks()
        val recordsBefore = dao.getAllRecords()
        val now = System.currentTimeMillis()

        // 先删记录再删任务：记录通过 taskId 外键引用任务。
        dao.deleteAllRecords()
        dao.deleteAllTasks()
        // 旧墓碑一律作废：恢复是"以备份为唯一事实重新开始"，
        // 删前存在的墓碑要么被下面的差量墓碑取代（行仍不在备份里），
        // 要么对应的行回来了（墓碑必须丢，否则会把恢复的行又删掉）。
        dao.clearTombstones()

        // 时间戳统一刷新为恢复时刻：恢复是用户明确的"现在以这份备份为准"，
        // 让这些行在后续同步里赢过云端任意更旧的版本。
        val restoredTasks = data.tasks.map { it.copy(updatedAt = now) }
        val restoredRecords = data.records.map { it.copy(updatedAt = now) }
        if (restoredTasks.isNotEmpty()) {
            dao.insertTasksWithIds(restoredTasks)
        }
        if (restoredRecords.isNotEmpty()) {
            dao.insertRecords(restoredRecords)
        }

        // 差量墓碑：本地有、备份里没有的行 = 被这次恢复删掉的行。
        val keptTaskIds = data.tasks.map { it.id }.toSet()
        tasksBefore.filter { it.id !in keptTaskIds }.forEach { task ->
            dao.insertTombstone(
                SyncTombstone(
                    tableName = SyncTombstone.TABLE_TASKS,
                    rowKey = SyncTombstone.taskKey(task.id),
                    deletedAt = now
                )
            )
        }
        val keptRecordKeys = data.records.map { SyncTombstone.recordKey(it.taskId, it.date) }.toSet()
        recordsBefore
            .filter { SyncTombstone.recordKey(it.taskId, it.date) !in keptRecordKeys }
            .forEach { record ->
                dao.insertTombstone(
                    SyncTombstone(
                        tableName = SyncTombstone.TABLE_RECORDS,
                        rowKey = SyncTombstone.recordKey(record.taskId, record.date),
                        deletedAt = now
                    )
                )
            }

        // 记事本随备份恢复。图片本体应已在私有目录（本地恢复：本来就在；
        // 云端恢复：CloudSyncRepository 先下载再调本方法），这里只恢复
        // "文字 + 路径清单"。文件缺失时界面显示占位，不影响其它数据。
        data.notebookText?.let { text ->
            NotebookStore.saveText(appContext, text)
        }
        if (data.notebookImages.isNotEmpty()) {
            val dir = File(appContext.filesDir, "notebook")
            NotebookStore.saveImagePaths(
                appContext,
                data.notebookImages.map { File(dir, it).absolutePath }
            )
        }

        // 外观设置随备份一并恢复；缺失则保持当前设置不变。
        data.themeMode?.let { settings.setThemeMode(it) }
        data.themeAccent?.let { settings.setThemeAccent(it) }

        return data.tasks.size to data.records.size
    }

    /**
     * 清空全部任务与打卡记录（需求 9，与 [restore] 互补的「重置」动作）。
     *
     * ## 为什么它叫「清空数据」而不是「删除备份」
     *
     * 需求字面写的是"删除备份功能"，但本应用**不保存任何备份列表**：备份是
     * 「导出到用户自选路径的 JSON 文件」，那个文件归**用户**所有，位置（下载 / 云盘 /
     * Drive）也由用户决定。应用既没有备份的"索引"可以删，也**不应该**去删用户
     * 文件管理器里的东西。
     *
     * 更关键的是：**即便把导出时的 URI 记下来，删除也做不到**。SAF 的 URI 权限
     * 依附于那次交互与进程生命周期，跨次启动后 `ContentResolver.delete()` 大概率抛
     * `SecurityException`；要可靠删除就得申请全量外部存储权限，那会破坏本页
     * 「**零存储权限**」的核心设计（与记事本 / 图标设置是同一口径）。
     *
     * 因此这里实现的是**唯一可靠且语义自洽**的那个版本：把应用内的数据清空，
     * 让用户回到"全新安装"的起点。三个动作构成完整闭环：
     * 导出 = 备份 → 清空 = 重置起点 → 恢复 = 回灌。
     *
     * ## 与 [restore] 的区别（两者都清空，区别在清空之后做什么）
     *
     * - [restore]：清空**之后立刻写入**备份内容 → 用户看到的是"数据回来了"；
     * - `clearAll`：清空**之后什么都不写** → 用户看到的是"数据没了"。
     *
     * ⚠️ 两者都**无法撤销**：清空是物理删除，没有回收站。
     * 因此界面侧必须二次确认，并提示用户"建议先导出备份"（见 `BackupScreen`）。
     * （墓碑不是撤销机制，它只保证这次清空能同步到云端，见类注释。）
     *
     * ## 为什么不顺带清掉外观设置与记事本
     *
     * [restore] 会顺手恢复 `themeMode` / `themeAccent` 与记事本，因为那是备份内容的一部分。
     * 而"清空数据"针对的是**打卡数据**：把用户刚挑的主题色、正在写的笔记也一并抹掉，
     * 是超出他预期的副作用（他点的是"清空数据"，不是"恢复出厂设置"）。
     * 外观设置与记事本同备份数据本身无关，因此**刻意不动**。
     *
     * @return 清掉的 (任务数, 记录数)，供界面如实反馈"删掉了多少"。
     *   刻意返回**删除前**的计数而不是 0：用户点确认时想知道的正是"我要删掉多少东西"，
     *   删完之后再数就只剩 0 了，那句提示就变成了一句没有信息量的"已清空 0 个任务"。
     */
    suspend fun clearAll(): Pair<Int, Int> {
        val tasks = dao.getAllTasks()
        val records = dao.getAllRecords()
        val now = System.currentTimeMillis()

        // 全部留墓碑（在删之前攒）：已登录用户的云端数据必须跟着清，
        // 否则下一次自动同步会把它们原样拉回来，"清空"被无声地撤销。
        tasks.forEach { task ->
            dao.insertTombstone(
                SyncTombstone(
                    tableName = SyncTombstone.TABLE_TASKS,
                    rowKey = SyncTombstone.taskKey(task.id),
                    deletedAt = now
                )
            )
        }
        records.forEach { record ->
            dao.insertTombstone(
                SyncTombstone(
                    tableName = SyncTombstone.TABLE_RECORDS,
                    rowKey = SyncTombstone.recordKey(record.taskId, record.date),
                    deletedAt = now
                )
            )
        }

        // 先删记录再删任务，与 [restore] 同一条顺序：记录通过 taskId 外键引用任务。
        dao.deleteAllRecords()
        dao.deleteAllTasks()

        return tasks.size to records.size
    }
}
