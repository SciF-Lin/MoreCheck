package com.morecheck.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure streak math over a set of checked dates (device time zone). */
object StreakCalculator {

    /**
     * Current consecutive streak.
     * - If today is checked, count backwards from today.
     * - If today is not yet checked but yesterday is, the streak is still "alive"
     *   and counts backwards from yesterday.
     * - Otherwise the streak is broken -> 0.
     */
    fun currentStreak(dates: Set<LocalDate>, today: LocalDate): Int {
        if (dates.isEmpty()) return 0
        val start = when {
            today in dates -> today
            today.minusDays(1) in dates -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        var cursor = start
        while (cursor in dates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    /** Longest consecutive run over the whole history. */
    fun longestStreak(dates: Set<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.sorted()
        var longest = 1
        var current = 1
        for (i in 1 until sorted.size) {
            val gap = ChronoUnit.DAYS.between(sorted[i - 1], sorted[i])
            current = if (gap == 1L) current + 1 else 1
            if (current > longest) longest = current
        }
        return longest
    }
}
