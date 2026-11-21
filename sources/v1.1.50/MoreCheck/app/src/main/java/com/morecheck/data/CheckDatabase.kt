package com.morecheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CheckTask::class, CheckRecord::class],
    version = 1,
    exportSchema = false
)
abstract class CheckDatabase : RoomDatabase() {

    abstract fun checkDao(): CheckDao

    companion object {
        @Volatile
        private var instance: CheckDatabase? = null

        fun get(context: Context): CheckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheckDatabase::class.java,
                    "morecheck.db"
                ).build().also { instance = it }
            }
    }
}
