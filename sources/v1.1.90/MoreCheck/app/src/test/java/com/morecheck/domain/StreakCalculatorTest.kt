package com.morecheck.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * [StreakCalculator] 的回归测试（v1.1.75 首次引入单元测试）。
 *
 * 它是纯函数（`Set<LocalDate>` + `today` 进，`Int` 出），是项目里最容易被
 * "看起来对"掩盖 bug 的一段逻辑：连续打卡的天数只在跨月 / 跨年 / 闰日附近
 * 才会暴露差一错误，而模拟器实测很难构造这些边界。
 *
 * 这里的每条断言都对应 KDoc 里写明的一条规则，**不是**为了凑覆盖率。
 */
class StreakCalculatorTest {

    private fun d(iso: String): LocalDate = LocalDate.parse(iso)

    // ── currentStreak ────────────────────────────────────────────────────────

    @Test
    fun `空历史返回 0`() {
        assertEquals(0, StreakCalculator.currentStreak(emptySet(), d("2026-10-20")))
    }

    @Test
    fun `今天已打卡时从今天往回数`() {
        val dates = setOf(d("2026-10-20"), d("2026-10-19"), d("2026-10-18"))
        assertEquals(3, StreakCalculator.currentStreak(dates, d("2026-10-20")))
    }

    @Test
    fun `今天没打卡但昨天打了 - 连续记录仍然存活`() {
        // 这是 KDoc 明确写下的行为：否则用户早上打开应用会看到"连续 0 天"，
        // 而其实他只要今天补上就还是连续的。
        val dates = setOf(d("2026-10-19"), d("2026-10-18"), d("2026-10-17"))
        assertEquals(3, StreakCalculator.currentStreak(dates, d("2026-10-20")))
    }

    @Test
    fun `昨天也没打卡时连续记录已断 - 返回 0`() {
        val dates = setOf(d("2026-10-18"), d("2026-10-17"))
        assertEquals(0, StreakCalculator.currentStreak(dates, d("2026-10-20")))
    }

    @Test
    fun `今天只有孤立一条时返回 1`() {
        assertEquals(1, StreakCalculator.currentStreak(setOf(d("2026-10-20")), d("2026-10-20")))
    }

    @Test
    fun `连续记录跨月边界正确`() {
        // 9 月 30 天：09-30 往前是 09-29，不是"09-31"。
        val dates = setOf(
            d("2026-09-30"), d("2026-09-29"), d("2026-09-28"), d("2026-09-27")
        )
        assertEquals(4, StreakCalculator.currentStreak(dates, d("2026-09-30")))
    }

    @Test
    fun `连续记录跨年边界正确`() {
        // 2026-12-31 往前是 2026-12-30，2027-01-01 往前是 2026-12-31。
        val dates = setOf(
            d("2027-01-01"), d("2026-12-31"), d("2026-12-30"), d("2026-12-29")
        )
        assertEquals(4, StreakCalculator.currentStreak(dates, d("2027-01-01")))
    }

    @Test
    fun `连续记录跨越闰日 2028-02-29`() {
        // 2028 是闰年。02-29 往前是 02-28（不是 02-29 不存在的情况），
        // 再往前是 02-27。若用"月日加减 1"而不是 LocalDate.minusDays 实现，这里会错。
        val dates = setOf(
            d("2028-03-01"), d("2028-02-29"), d("2028-02-28"), d("2028-02-27")
        )
        assertEquals(4, StreakCalculator.currentStreak(dates, d("2028-03-01")))
    }

    @Test
    fun `历史中有中断时只数到中断处`() {
        // 10-20 / 10-19 / 10-18 连续，10-16 有但 10-17 缺 → 从今天数到 10-18 为止。
        val dates = setOf(
            d("2026-10-20"), d("2026-10-19"), d("2026-10-18"), d("2026-10-16")
        )
        assertEquals(3, StreakCalculator.currentStreak(dates, d("2026-10-20")))
    }

    @Test
    fun `未来日期不影响当前连续记录`() {
        // 补打卡只允许过去 7 天，但数据里理论上可能混进未来日期（时区/改系统时间）。
        // 它们不该被计入，也不该让计算崩掉。
        val dates = setOf(d("2026-10-20"), d("2026-10-19"), d("2026-10-25"))
        assertEquals(2, StreakCalculator.currentStreak(dates, d("2026-10-20")))
    }

    // ── longestStreak ────────────────────────────────────────────────────────

    @Test
    fun `空历史的最长连续为 0`() {
        assertEquals(0, StreakCalculator.longestStreak(emptySet()))
    }

    @Test
    fun `单条记录的最长连续为 1`() {
        assertEquals(1, StreakCalculator.longestStreak(setOf(d("2026-10-20"))))
    }

    @Test
    fun `取历史里最长的那一段而不是最后一段`() {
        // [10-01..10-05] 连续 5 天，[10-10..10-12] 连续 3 天 → 答案是 5。
        val dates = setOf(
            d("2026-10-01"), d("2026-10-02"), d("2026-10-03"), d("2026-10-04"), d("2026-10-05"),
            d("2026-10-10"), d("2026-10-11"), d("2026-10-12")
        )
        assertEquals(5, StreakCalculator.longestStreak(dates))
    }

    @Test
    fun `最长连续跨年时正确`() {
        val dates = setOf(
            d("2026-12-30"), d("2026-12-31"), d("2027-01-01"), d("2027-01-02"),
            d("2027-01-10")
        )
        assertEquals(4, StreakCalculator.longestStreak(dates))
    }

    @Test
    fun `无序输入也能算对`() {
        // 实现里做了 sorted()，但这正是值得钉住的地方：
        // 若哪天有人把 sorted() 去掉，只有乱序输入才会暴露。
        val dates = setOf(
            d("2026-10-03"), d("2026-10-01"), d("2026-10-02"), d("2026-10-20")
        )
        assertEquals(3, StreakCalculator.longestStreak(dates))
    }

    @Test
    fun `全长连续的输入等于总数`() {
        val dates = (1..10).map { d("2026-10-%02d".format(it)) }.toSet()
        assertEquals(10, StreakCalculator.longestStreak(dates))
        assertEquals(10, StreakCalculator.currentStreak(dates, d("2026-10-10")))
    }
}
