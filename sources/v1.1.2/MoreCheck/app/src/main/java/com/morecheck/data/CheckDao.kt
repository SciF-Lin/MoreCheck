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
}
