package com.morecheck.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZonedDateTime

class TaskRepository(
    private val dao: CheckDao,
    private val settings: SettingsRepository
) {
    val tasks: Flow<List<CheckTask>> = dao.observeTasks()
    val allRecords: Flow<List<CheckRecord>> = dao.observeAllRecords()

    suspend fun addTask(name: String, icon: String, color: Int, reminderTime: String?): Long {
        val task = CheckTask(
            id = 0L,
            name = name.trim(),
            icon = icon,
            color = color,
            reminderTime = reminderTime,
            createdAt = System.currentTimeMillis()
        )
        return dao.insertTask(task)
    }

    suspend fun updateTask(task: CheckTask) = dao.updateTask(task)

    suspend fun deleteTask(task: CheckTask, deleteHistory: Boolean) {
        if (deleteHistory) dao.deleteRecordsForTask(task.id)
        dao.deleteTask(task.id)
    }

    /** Check in. Same-day re-taps only update the timestamp (REPLACE strategy). */
    suspend fun checkIn(taskId: Long) {
        val now = ZonedDateTime.now()
        dao.upsertRecord(
            CheckRecord(
                taskId = taskId,
                date = now.toLocalDate().toString(),
                checkedAt = now.toInstant().toEpochMilli()
            )
        )
    }

    suspend fun undoCheckIn(taskId: Long, date: LocalDate = LocalDate.now()) {
        dao.deleteRecord(taskId, date.toString())
    }

    /** Backfill a check-in for a specific past date (补打卡). */
    suspend fun checkInOnDate(taskId: Long, date: LocalDate) {
        dao.upsertRecord(
            CheckRecord(
                taskId = taskId,
                date = date.toString(),
                checkedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun getTask(taskId: Long): CheckTask? = dao.getTask(taskId)

    suspend fun getRecordsBetween(from: LocalDate, to: LocalDate): List<CheckRecord> =
        dao.getRecordsBetween(from.toString(), to.toString())

    suspend fun seedDefaultTaskIfNeeded() {
        if (!settings.defaultTaskSeeded.first()) {
            dao.insertTask(
                CheckTask(
                    id = 0L,
                    name = "早起",
                    icon = "🌅",
                    color = 0xFFFF9800.toInt(),
                    reminderTime = null,
                    createdAt = System.currentTimeMillis()
                )
            )
            settings.setDefaultTaskSeeded()
        }
    }
}
