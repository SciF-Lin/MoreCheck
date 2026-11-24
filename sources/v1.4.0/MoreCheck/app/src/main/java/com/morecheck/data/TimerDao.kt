package com.morecheck.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {
    @Insert
    suspend fun insert(session: TimerSession): Long

    @Query("SELECT * FROM timer_sessions ORDER BY endedAt DESC")
    fun observeAll(): Flow<List<TimerSession>>
}