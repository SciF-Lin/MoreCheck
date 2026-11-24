package com.morecheck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CheckTask::class,
        CheckRecord::class,
        SyncTombstone::class,
        GameSnapshot::class,
        PendingRecord::class,
        TimerSession::class
    ],
    version = 6,
    exportSchema = true
)
abstract class CheckDatabase : RoomDatabase() {

    abstract fun checkDao(): CheckDao

    abstract fun timerDao(): TimerDao

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

        /**
         * v2 → v3：云同步三件套。
         *
         * 1. `check_tasks` / `check_records` 各加 `updatedAt`（同步的 LWW 时间戳）；
         * 2. 新建 `sync_tombstones`（删除事件的同步载体，见该类注释）；
         * 3. 新建 `game_snapshots`（网页游戏 localStorage 的本地镜像）。
         *
         * ## 列名为什么是驼峰（`updatedAt`）而不是下划线（`updated_at`）
         *
         * Room 的列名默认取**字段名本身**，不做驼峰→下划线转换（那是 Hibernate 的
         * 行为）。本库既有列（`createdAt` / `checkedAt` / `reminderTime`）也全是
         * 驼峰，因此新列必须跟着用驼峰——写成下划线的话 KSP 直接报
         * `no such column: updated_at`（查询按实体字段名校验列名）。
         * 云端表那边确实是下划线（`updated_at` / `deleted_at` / `game_key`），
         * 由 `CloudSyncRepository` 在「实体字段 ↔ JSON 键」之间显式映射，
         * 两边不需要、也不应该保持一致。
         *
         * ## 回填口径：旧行的 updatedAt 取什么
         *
         * - 任务取 [CheckTask.createdAt]、记录取 [CheckRecord.checkedAt]。
         *   它们是"这行最后一次有意义的变化"的最接近代理——比统一填 0 更好：
         *   填 0 会让**所有**旧行在首次同步时以 `updatedAt = 0` 推上云端，
         *   之后任何一台设备上的旧数据都能以任意时间戳覆盖它们。
         *   用创建 / 打卡时间回填，至少保留了"新建的在旧的之后"这个真实顺序。
         * - 回填后首次同步仍会全量推送（水位线从 0 开始），行为上无损失。
         *
         * 同样**不能**用 `fallbackToDestructiveMigration()`：那会删掉用户全部打卡数据。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE check_tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE check_records ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE check_tasks SET updatedAt = createdAt")
                db.execSQL("UPDATE check_records SET updatedAt = checkedAt")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_tombstones (
                        tableName TEXT NOT NULL,
                        rowKey TEXT NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY (tableName, rowKey)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS game_snapshots (
                        gameKey TEXT NOT NULL,
                        dataJson TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        applied INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (gameKey)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v3 → v4：新建 `pending_records`（"待归属"打卡记录的暂存表）。
         *
         * 背景：双向同步时打卡记录与任务**没有到达顺序保证**——云端可能先返回
         * 某条打卡记录而它所属的任务还在路上，Room 的外键约束会拒绝悬空行，
         * 直接丢等于"那天的打卡莫名消失"。本表让这些记录先落地、等任务到齐
         * 再搬回正式表（`CloudSyncRepository.applyPendingRecords`）。
         * 完整设计见 [PendingRecord] 的类注释。
         *
         * 纯新增表，旧数据零影响；同样不用 `fallbackToDestructiveMigration()`。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskLocalId INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        checkedAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                        index_pending_records_taskLocalId_date
                        ON pending_records (taskLocalId, date)
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 → v5：任务的四个组织字段（分组 / 标签 / 归档 / 暂停）。
         *
         * 全部是**可空的 TEXT** 或**带 DEFAULT 0 的非空 INTEGER**，因此一条
         * `ALTER TABLE ... ADD COLUMN` 就能让旧行保持原样：未分组、无标签、
         * 未归档、未暂停。Room 校验 schema 时要求布尔列是 `INTEGER NOT NULL`，
         * 而给既有表加非空列必须带默认值，这里取 0（false）。
         *
         * 字段名沿用本库既有的驼峰口径（见 `MIGRATION_2_3` 的注释）；云端表没有
         * 这些列，同步层刻意不推送它们，见 `CloudSyncRepository.taskRow`。
         *
         * 同时把 [exportSchema] 打开（Gradle 已配 `room.schemaLocation`），
         * 从这一版起 schema JSON 入库，后续迁移可用 MigrationTestHelper 验证。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_tasks ADD COLUMN groupName TEXT")
                db.execSQL("ALTER TABLE check_tasks ADD COLUMN tags TEXT")
                db.execSQL("ALTER TABLE check_tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE check_tasks ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 → v6：新建 `timer_sessions`（走完一轮倒计时记一条）。
         *
         * 纯新增表，旧数据零影响；列名沿用本库驼峰口径（见 `MIGRATION_2_3`）。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS timer_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL,
                        durationMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): CheckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheckDatabase::class.java,
                    "morecheck.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
