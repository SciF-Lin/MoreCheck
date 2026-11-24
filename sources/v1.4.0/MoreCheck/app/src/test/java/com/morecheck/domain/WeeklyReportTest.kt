package com.morecheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [WeeklyReport] 的纯 JVM 回归测试。
 *
 * 重点钉住两处最容易被"看起来对"掩盖的规则：本周窗口与全历史连续的分离、
 * 以及「达标 = 当天至少一条记录」的二值口径（连续天数因此有存活语义）。
 */
class WeeklyReportTest {

    private fun d(iso: String): LocalDate = LocalDate.parse(iso)
    private val today = d("2026-11-04")

    private fun record(taskId: Long, date: String) =
        RecordInfo(taskId = taskId, date = date, checkedAt = 0L)

    @Test
    fun `空数据返回全零周报`() {
        val r = WeeklyReport.build(emptyList(), emptyList(), today)
        assertEquals(d("2026-10-29"), r.weekStart)
        assertEquals(7, r.days.size)
        assertEquals(0, r.totalTasks)
        assertEquals(0, r.achievedDays)
        assertEquals(0f, r.completionRate, 0.0001f)
        assertEquals(0, r.currentStreak)
        assertEquals(0, r.longestStreak)
        assertNull(r.bestTaskName)
        assertTrue(r.taskDayCounts.isEmpty())
        assertTrue(r.days.all { it.checkedCount == 0 && !it.achieved })
        assertEquals(d("2026-10-29"), r.days.first().date)
        assertEquals(today, r.days.last().date)
    }

    @Test
    fun `跨周记录不计入本周柱状但计入最长连续`() {
        val tasks = listOf(TaskInfo(1L, "A"))
        val records = listOf(
            record(1L, "2026-10-01"),
            record(1L, "2026-10-02"),
            record(1L, "2026-10-03"),
            record(1L, "2026-10-30")
        )
        val r = WeeklyReport.build(tasks, records, today)
        assertEquals(1, r.days.sumOf { it.checkedCount })
        assertEquals(1, r.achievedDays)
        assertEquals(1f / 7f, r.completionRate, 0.0001f)
        assertEquals(3, r.longestStreak)
        assertEquals(0, r.currentStreak)
        // 排行只统计本周窗口，故只有 10-30 那一条。
        assertEquals(listOf(TaskDayCount(1L, "A", 1)), r.taskDayCounts)
    }

    @Test
    fun `完成率边界 无记录为0 满勤7天为1`() {
        val tasks = listOf(TaskInfo(1L, "A"))

        val none = WeeklyReport.build(tasks, emptyList(), today)
        assertEquals(0, none.achievedDays)
        assertEquals(0f, none.completionRate, 0.0001f)

        val full = (0L..6L).map { offset ->
            record(1L, today.minusDays(6 - offset).toString())
        }
        val all = WeeklyReport.build(tasks, full, today)
        assertEquals(7, all.achievedDays)
        assertEquals(1f, all.completionRate, 0.0001f)
    }

    @Test
    fun `任务打卡天数排行降序且并列按名称`() {
        val tasks = listOf(
            TaskInfo(1L, "Run"),
            TaskInfo(2L, "Water"),
            TaskInfo(3L, "Read")
        )
        val records = listOf(
            record(1L, "2026-10-29"), record(1L, "2026-10-30"), record(1L, "2026-11-01"),
            record(2L, "2026-10-29"), record(2L, "2026-11-02"), record(2L, "2026-11-03"),
            record(3L, "2026-10-29")
        )
        val r = WeeklyReport.build(tasks, records, today)
        // Run 与 Water 同为 3 天，按名称升序 Run 在前；Read 只有 1 天排最后。
        assertEquals(listOf("Run", "Water", "Read"), r.taskDayCounts.map { it.taskName })
        assertEquals(listOf(3, 3, 1), r.taskDayCounts.map { it.days })
        assertEquals("Run", r.bestTaskName)
    }

    @Test
    fun `今天未打卡但昨天打卡时当前连续仍存活`() {
        val tasks = listOf(TaskInfo(1L, "A"))
        val records = listOf(
            record(1L, "2026-11-03"),
            record(1L, "2026-11-02"),
            record(1L, "2026-11-01")
        )
        val r = WeeklyReport.build(tasks, records, today)
        assertEquals(3, r.currentStreak)
        assertEquals(3, r.longestStreak)
        assertEquals(3, r.achievedDays)
    }

    @Test
    fun `每日达标数与任务总数口径`() {
        val tasks = listOf(
            TaskInfo(1L, "A"),
            TaskInfo(2L, "B"),
            TaskInfo(3L, "C")
        )
        val records = listOf(
            record(1L, "2026-11-04"),
            record(3L, "2026-11-04")
        )
        val r = WeeklyReport.build(tasks, records, today)
        assertEquals(3, r.totalTasks)
        val last = r.days.last()
        assertEquals(2, last.checkedCount)
        assertEquals(3, last.totalTasks)
        assertEquals(2f / 3f, last.dailyRate, 0.0001f)
        assertTrue(last.achieved)
        assertFalse(r.days.first().achieved)
        assertEquals(1, r.achievedDays)
        assertEquals(1f / 7f, r.completionRate, 0.0001f)
    }
}
