package com.morecheck.domain

import java.time.LocalDate

/** 周报的任务输入（由 UI 层从 Room 实体映射，且已过滤掉归档 / 暂停的任务）。 */
data class TaskInfo(
    val id: Long,
    val name: String,
    val groupName: String? = null
)

/** 周报的记录输入，[date] 为 `yyyy-MM-dd`。 */
data class RecordInfo(
    val taskId: Long,
    val date: String,
    val checkedAt: Long
)

/**
 * 本周某一天的达标情况。
 *
 * @param checkedCount 当天有打卡记录的不同任务数（即「达标数」）。只统计在册任务
 *   （见 [WeeklyReport.build] 的过滤口径），因此不会超过 [totalTasks]。
 * @param totalTasks 任务总数（未归档未暂停的任务数，UI 层过滤后传入）。
 */
data class WeeklyDayStat(
    val date: LocalDate,
    val checkedCount: Int,
    val totalTasks: Int
) {
    /** 当天任务完成率；无任务时为 0。 */
    val dailyRate: Float
        get() = if (totalTasks <= 0) 0f else (checkedCount.toFloat() / totalTasks).coerceIn(0f, 1f)

    /**
     * 当天是否「达标」：至少有一条打卡记录。
     *
     * 这条口径与统计页一致——把「当天有过任意打卡」视为一个打卡日，
     * 而不是要求所有任务都完成。周完成率与连续天数都建立在这个二值判断上。
     */
    val achieved: Boolean get() = checkedCount > 0
}

/** 单个任务在本周的打卡天数。 */
data class TaskDayCount(
    val taskId: Long,
    val taskName: String,
    val days: Int
)

/**
 * 一周打卡周报的统计结果。
 *
 * @param weekStart 本周起始日（含），恒为 [today] 往前 6 天。
 * @param days 固定 7 天、按日期升序的每日达标情况。
 * @param completionRate 周完成率 = [achievedDays] / 7，范围 0..1。
 * @param taskDayCounts 本周每个任务的打卡天数排行（降序）。
 * @param currentStreak 当前连续天数（复用 [StreakCalculator.currentStreak]）。
 * @param longestStreak 全历史最长连续天数（复用 [StreakCalculator.longestStreak]）。
 */
data class WeeklyReport(
    val weekStart: LocalDate,
    val today: LocalDate,
    val totalTasks: Int,
    val days: List<WeeklyDayStat>,
    val achievedDays: Int,
    val completionRate: Float,
    val taskDayCounts: List<TaskDayCount>,
    val bestTaskName: String?,
    val currentStreak: Int,
    val longestStreak: Int
) {
    companion object {

        /**
         * 由任务与打卡记录计算周报。
         *
         * ## 两套时间口径（刻意分开，写清楚以免被当成 bug）
         *
         * - **本周窗口**（[weekStart]..[today]，共 7 天）：只影响 [days]、[achievedDays]、
         *   [completionRate]、[taskDayCounts] 与 [bestTaskName]。窗口外的记录不进柱状图，
         *   也不进任务排行。
         * - **全历史**：[currentStreak] / [longestStreak] 取所有记录的日期集合。
         *   「连续」本就是跨周概念——若只喂本周 7 天，周一打开应用会把上周攒的连续天数
         *   误判成 0，那才是错的。
         *
         * ## 达标口径
         *
         * 某天「达标」= 当天至少有一条打卡记录（见 [WeeklyDayStat.achieved]）。
         * 与统计页 `computeStreakSummary` 完全同口径：只认**在册任务**（taskId 落在
         * [tasks] 内）、能解析、且不晚于 [today] 的记录。归档 / 暂停 / 已删除任务的
         * 历史记录会被过滤掉，否则一个早已归档的任务会把连续记录凭空续上（幽灵数据）。
         * 脏日期字符串按 `runCatching` 丢弃，不让一条坏数据把整页弄崩。
         *
         * ## 排行口径
         *
         * 按「本周内打卡的不同天数」降序；天数相同按任务名升序，保证结果稳定、可测。
         * 只列出本周至少打过一次卡的任务。
         *
         * @param tasks 未归档未暂停的任务（由 UI 层过滤后传入，[totalTasks] 即其数量）。
         * @param records 全部打卡记录（不限本周，供连续天数使用）。
         */
        fun build(
            tasks: List<TaskInfo>,
            records: List<RecordInfo>,
            today: LocalDate
        ): WeeklyReport {
            val weekStart = today.minusDays(6)
            val totalTasks = tasks.size
            val taskById = tasks.associateBy { it.id }
            val activeIds = taskById.keys

            // 与统计页 computeStreakSummary 同口径地清洗记录：在册任务、日期可解析、
            // 不晚于今天。清洗后的 (日期, 记录) 同时供连续天数、每日柱状与排行使用。
            val validRecords = records.mapNotNull { record ->
                if (record.taskId !in activeIds) return@mapNotNull null
                val date = runCatching { LocalDate.parse(record.date) }.getOrNull()
                    ?: return@mapNotNull null
                if (date.isAfter(today)) return@mapNotNull null
                date to record
            }

            // 全历史日期集合：喂给 StreakCalculator 的是「哪几天打过卡」，不区分任务。
            val allDates = validRecords.map { it.first }.toSet()

            // 本周窗口内的记录，供每日柱状与任务排行使用（validRecords 已排除未来日期）。
            val weekRecords = validRecords
                .filter { (date, _) -> !date.isBefore(weekStart) }
                .map { it.second }

            val checkedCountByDate = weekRecords
                .groupBy { LocalDate.parse(it.date) }
                .mapValues { (_, list) -> list.map { it.taskId }.distinct().size }

            val days = (0L..6L).map { offset ->
                val date = weekStart.plusDays(offset)
                WeeklyDayStat(
                    date = date,
                    checkedCount = checkedCountByDate[date] ?: 0,
                    totalTasks = totalTasks
                )
            }

            val achievedDays = days.count { it.achieved }
            val completionRate = achievedDays / 7f

            val taskDayCounts = weekRecords
                .groupBy { it.taskId }
                .map { (taskId, list) ->
                    // validRecords 已保证 taskId 在册，getValue 不会落空。
                    TaskDayCount(
                        taskId = taskId,
                        taskName = taskById.getValue(taskId).name,
                        days = list.map { it.date }.distinct().size
                    )
                }
                .sortedWith(
                    compareByDescending<TaskDayCount> { it.days }
                        .thenBy { it.taskName }
                )

            return WeeklyReport(
                weekStart = weekStart,
                today = today,
                totalTasks = totalTasks,
                days = days,
                achievedDays = achievedDays,
                completionRate = completionRate,
                taskDayCounts = taskDayCounts,
                bestTaskName = taskDayCounts.firstOrNull()?.taskName,
                currentStreak = StreakCalculator.currentStreak(allDates, today),
                longestStreak = StreakCalculator.longestStreak(allDates)
            )
        }
    }
}
