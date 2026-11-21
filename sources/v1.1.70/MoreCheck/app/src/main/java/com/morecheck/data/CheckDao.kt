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

    @Query("SELECT * FROM check_tasks WHERE id = :id")
    suspend fun getTask(id: Long): CheckTask?

    @Query("SELECT COUNT(*) FROM check_tasks")
    suspend fun countTasks(): Int

    // ---- Records ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: CheckRecord)

    @Query("DELETE FROM check_records WHERE taskId = :taskId AND date = :date")
    suspend fun deleteRecord(taskId: Long, date: String)

    @Query("DELETE FROM check_records WHERE taskId = :taskId")
    suspend fun deleteRecordsForTask(taskId: Long)

    @Query("SELECT * FROM check_records")
    fun observeAllRecords(): Flow<List<CheckRecord>>

    @Query("SELECT * FROM check_records WHERE date = :date")
    suspend fun getRecordsForDate(date: String): List<CheckRecord>

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
}
