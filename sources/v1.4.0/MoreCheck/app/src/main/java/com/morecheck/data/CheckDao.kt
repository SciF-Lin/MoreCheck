package com.morecheck.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckDao {

    // ---- Tasks ----

    @Insert
    suspend fun insertTask(task: CheckTask): Long

    @Update
    suspend fun updateTask(task: CheckTask)

    @Query("DELETE FROM check_tasks WHERE id = :id")
    suspend fun deleteTask(id: Long)

    @Query("SELECT * FROM check_tasks ORDER BY createdAt ASC")
    fun observeTasks(): Flow<List<CheckTask>>

    /**
     * 主页 / 小组件用的「未归档」任务（v5）。
     *
     * 暂停任务仍在列表里（灰显、可手动打卡），只是不计入今日目标与提醒。
     * 归档任务只在任务管理页的「已归档」筛选里出现。
     */
    @Query("SELECT * FROM check_tasks WHERE archived = 0 ORDER BY createdAt ASC")
    fun observeActiveTasks(): Flow<List<CheckTask>>

    /** 小组件一次性读取：未归档且未暂停的任务（今日目标的 denominator）。 */
    @Query("SELECT * FROM check_tasks WHERE archived = 0 AND paused = 0 ORDER BY createdAt ASC")
    suspend fun getActiveTasksOnce(): List<CheckTask>

    /** 小组件 / 周报：某一天的全部打卡记录条数（按任务 id 去重前）。 */
    @Query("SELECT COUNT(*) FROM check_records WHERE date = :date")
    suspend fun countRecordsOnDate(date: String): Int

    /** 小组件：某一天已打卡的任务 id 集合。 */
    @Query("SELECT taskId FROM check_records WHERE date = :date")
    suspend fun checkedTaskIdsOnDate(date: String): List<Long>

    @Query("SELECT * FROM check_tasks WHERE id = :id")
    suspend fun getTask(id: Long): CheckTask?

    @Query("SELECT COUNT(*) FROM check_tasks")
    suspend fun countTasks(): Int

    // ---- Records ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: CheckRecord)

    @Query("DELETE FROM check_records WHERE taskId = :taskId AND date = :date")
    suspend fun deleteRecord(taskId: Long, date: String)

    /** 撤销打卡前取回原行，用于生成同步墓碑（见 `SyncTombstone`）。 */
    @Query("SELECT * FROM check_records WHERE taskId = :taskId AND date = :date")
    suspend fun getRecord(taskId: Long, date: String): CheckRecord?

    /** 删任务（带历史）前取回其全部记录，用于逐条生成同步墓碑。 */
    @Query("SELECT * FROM check_records WHERE taskId = :taskId")
    suspend fun getRecordsForTask(taskId: Long): List<CheckRecord>

    @Query("DELETE FROM check_records WHERE taskId = :taskId")
    suspend fun deleteRecordsForTask(taskId: Long)

    @Query("SELECT * FROM check_records")
    fun observeAllRecords(): Flow<List<CheckRecord>>

    @Query("SELECT * FROM check_records WHERE date >= :from AND date <= :to")
    suspend fun getRecordsBetween(from: String, to: String): List<CheckRecord>

    @Query("SELECT COUNT(*) FROM check_records WHERE taskId = :taskId")
    suspend fun countRecordsForTask(taskId: Long): Int

    // ---- 备份 / 恢复 --------------------------------------------------------

    @Query("SELECT * FROM check_tasks ORDER BY createdAt ASC")
    suspend fun getAllTasks(): List<CheckTask>

    @Query("SELECT * FROM check_records")
    suspend fun getAllRecords(): List<CheckRecord>

    /**
     * 恢复备份时插入任务。
     *
     * 这里**保留备份里的 id**（REPLACE 策略会整行替换），
     * 因为打卡记录通过 taskId 关联任务；若让 id 自增重新分配，记录就会错位。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasksWithIds(tasks: List<CheckTask>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<CheckRecord>)

    @Query("DELETE FROM check_records")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM check_tasks")
    suspend fun deleteAllTasks()

    // ---- 云同步（v3）---------------------------------------------------------
    //
    // 这一批查询只服务 `CloudSyncRepository`：推送取"水位线之后改动过的行"，
    // 拉取前的 LWW 比对取"本地现存行"。普通业务路径一个都不用。

    /** 推送用：取 [after] 之后改动过的任务（含新增 / 编辑），按时间正序。 */
    @Query("SELECT * FROM check_tasks WHERE updatedAt > :after ORDER BY updatedAt ASC")
    suspend fun getTasksUpdatedAfter(after: Long): List<CheckTask>

    /** 推送用：取 [after] 之后改动过的记录，按时间正序。 */
    @Query("SELECT * FROM check_records WHERE updatedAt > :after ORDER BY updatedAt ASC")
    suspend fun getRecordsUpdatedAfter(after: Long): List<CheckRecord>

    /** 全部墓碑（量小：删过多少行才有多少条），按删除时间正序。 */
    @Query("SELECT * FROM sync_tombstones ORDER BY deletedAt ASC")
    suspend fun getTombstones(): List<SyncTombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTombstone(tombstone: SyncTombstone)

    /** 丢弃一条已推送成功的墓碑。 */
    @Query("DELETE FROM sync_tombstones WHERE tableName = :tableName AND rowKey = :rowKey")
    suspend fun deleteTombstone(tableName: String, rowKey: String)

    /** 丢弃删除时间不晚于 [before] 的墓碑（拉取确认云端已删除后用）。 */
    @Query("DELETE FROM sync_tombstones WHERE deletedAt <= :before")
    suspend fun deleteTombstonesBefore(before: Long)

    @Query("DELETE FROM sync_tombstones")
    suspend fun clearTombstones()

    // ---- 游戏数据快照（v3）---------------------------------------------------
    //
    // 一行一个游戏（见 `GameSnapshot` 的类注释）。这组查询同时被
    // 同步引擎（推 / 拉）与四个游戏页（导出 / 恢复）使用。

    @Query("SELECT * FROM game_snapshots ORDER BY gameKey ASC")
    suspend fun getGameSnapshots(): List<GameSnapshot>

    @Query("SELECT * FROM game_snapshots WHERE gameKey = :gameKey")
    suspend fun getGameSnapshot(gameKey: String): GameSnapshot?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGameSnapshot(snapshot: GameSnapshot)

    @Query("DELETE FROM game_snapshots WHERE gameKey = :gameKey")
    suspend fun deleteGameSnapshot(gameKey: String)

    @Query("DELETE FROM game_snapshots")
    suspend fun clearGameSnapshots()

    // ---- 待归属的打卡记录（v4）----------------------------------------------
    //
    // 见 `PendingRecord` 的类注释：拉取打卡记录时任务未到，先进本表暂存，
    // 每轮拉取结束后由同步引擎把任务已到齐的记录搬回正式表。

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingRecord(record: PendingRecord)

    @Query("SELECT * FROM pending_records ORDER BY updatedAt ASC")
    suspend fun getPendingRecords(): List<PendingRecord>

    @Query("DELETE FROM pending_records WHERE taskLocalId = :taskLocalId AND date = :date")
    suspend fun deletePendingRecord(taskLocalId: Long, date: String)

    @Query("DELETE FROM pending_records")
    suspend fun clearPendingRecords()
}
