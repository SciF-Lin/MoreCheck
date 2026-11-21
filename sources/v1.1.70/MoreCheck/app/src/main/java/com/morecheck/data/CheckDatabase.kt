package com.morecheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CheckTask::class, CheckRecord::class],
    version = 2,
    exportSchema = false
)
abstract class CheckDatabase : RoomDatabase() {

    abstract fun checkDao(): CheckDao

    companion object {
        @Volatile
        private var instance: CheckDatabase? = null

        /**
         * v1 → v2：任务表新增 `note`（备注）列。
         *
         * **必须用迁移而不是 `fallbackToDestructiveMigration()`**：
         * 后者会直接删库重建，用户已有的任务与打卡记录会全部丢失。
         * 这里只加一列，旧行的备注为 NULL，数据完整保留。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_tasks ADD COLUMN note TEXT")
            }
        }

        fun get(context: Context): CheckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheckDatabase::class.java,
                    "morecheck.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
