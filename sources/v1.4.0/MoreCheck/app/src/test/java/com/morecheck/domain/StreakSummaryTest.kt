package com.morecheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [computeStreakSummary] 的回归测试（纯 JVM，不引 Robolectric / MockK）。
 *
 * 它把「打卡记录 + 在册任务 + 今天」折叠成一份 [StreakSummary]，
 * 最容易被"看起来对"掩盖 bug 的地方是：达标口径（至少一项 vs 全部完成）、
 * 活跃任务过滤、未来日期剔除，以及 30 天窗口的首尾与长度。
 * 每条断言都对着 StreakSummary.kt 的 KDoc 里写明的一条规则。
 */
class StreakSummaryTest {

    private fun d(iso: String): LocalDate = LocalDate.parse(iso)

    /** 便捷构造：同一任务在若干日期各打一次卡。 */
    private fun records(taskId: Long, vararg dates: String): List<StreakRecord> =
        dates.map { StreakRecord(taskId, it) }

    // 大多数用例只用任务 1，且它始终在册。
    private val active = setOf(1L, 2L)

    @Test
    fun `空记录得到全零摘要且窗口仍为 30 天全 false`() {
        val s = computeStreakSummary(emptyList(), active, d("2026-10-20"))
        assertTrue(s.completedDates.isEmpty())
        assertEquals(0, s.current)
        assertEquals(0, s.longest)
        assertFalse(s.todayCompleted)
        assertEquals(30, s.last30Days.size)
        assertTrue(s.last30Days.all { !it.second })
    }

    @Test
    fun `今天没打卡但昨天打了 - 当前连续仍存活且今天未达标`() {
        // KDoc 明确写下的行为：早上打开应用不该看到"连续 0 天"。
        // 达标口径是"当天至少一项打卡"，19/18/17 三天各有一条记录即算三天。
        val s = computeStreakSummary(
            records(1L, "2026-10-19", "2026-10-18", "2026-10-17"),
            active,
            d("2026-10-20")
        )
        assertEquals(3, s.current)
        assertEquals(3, s.longest)
        assertFalse(s.todayCompleted)
        assertTrue(d("2026-10-19") in s.completedDates)
    }

    @Test
    fun `连续记录跨月边界正确`() {
        // 11-01 往前是 10-31，不是 11-00；30 天窗口同样要跨回去。
        val s = computeStreakSummary(
            records(1L, "2026-11-01", "2026-10-31", "2026-10-30", "2026-10-29"),
            active,
            d("2026-11-01")
        )
        assertEquals(4, s.current)
        assertEquals(4, s.longest)
        assertTrue(s.todayCompleted)
    }

    @Test
    fun `最长纪录取历史里最长的一段而非最后一段`() {
        // [10-01..10-05] 连续 5 天，[10-19..10-20] 连续 2 天：当前 2、最长 5。
        val s = computeStreakSummary(
            records(
                1L,
                "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05",
                "2026-10-19", "2026-10-20"
            ),
            active,
            d("2026-10-20")
        )
        assertEquals(2, s.current)
        assertEquals(5, s.longest)
    }

    @Test
    fun `30 天窗口长度 30 且按时间升序 首尾正确 标记与达标集合一致`() {
        val today = d("2026-10-20")
        val s = computeStreakSummary(
            records(1L, "2026-10-20", "2026-10-01", "2026-09-01"),
            active,
            today
        )
        assertEquals(30, s.last30Days.size)
        assertEquals(today.minusDays(29), s.last30Days.first().first)
        assertEquals(today, s.last30Days.last().first)
        // 窗口内的达标日标 true，窗口外的 09-01 不进窗口但仍是达标日（喂给 longest）。
        assertEquals(true, s.last30Days.last().second)
        val windowTrue = s.last30Days.filter { it.second }.map { it.first }.toSet()
        assertEquals(setOf(d("2026-10-20"), d("2026-10-01")), windowTrue)
        assertTrue(d("2026-09-01") in s.completedDates)
        s.last30Days.forEach { (date, done) ->
            assertEquals(date in s.completedDates, done)
        }
    }

    @Test
    fun `非在册任务 未来日期 无效日期都被剔除`() {
        val s = computeStreakSummary(
            listOf(
                StreakRecord(1L, "2026-10-20"),   // 在册、今天：算
                StreakRecord(2L, "2026-10-19"),   // 在册、过去：算
                StreakRecord(9L, "2026-10-18"),   // 不在册（归档/暂停/已删）：不算
                StreakRecord(1L, "2026-10-25"),   // 未来日期：不算
                StreakRecord(1L, "not-a-date")    // 无法解析：不算
            ),
            active,
            d("2026-10-20")
        )
        assertEquals(setOf(d("2026-10-20"), d("2026-10-19")), s.completedDates)
        assertEquals(2, s.current)
        assertEquals(2, s.longest)
    }
}
