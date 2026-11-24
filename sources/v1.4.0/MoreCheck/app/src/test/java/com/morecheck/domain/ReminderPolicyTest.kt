package com.morecheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * [ReminderPolicy] 的纯 JVM 回归测试。
 *
 * 静默时段判错只会在「最不该打扰用户的时间」暴露出来，而 BroadcastReceiver
 * 里的分支没法脱离 Android 环境直接验证，所以把跨午夜 / 开闭区间 / 脏值回落
 * 这几条规则固化成断言。每条都对应 ReminderPolicy KDoc 里写明的一条约定。
 */
class ReminderPolicyTest {

    private fun time(hour: Int, minute: Int = 0): LocalTime = LocalTime.of(hour, minute)

    // ── isQuietNow ───────────────────────────────────────────────────────────

    @Test
    fun `同日区间命中中段`() {
        assertTrue(ReminderPolicy.isQuietNow(time(13), time(12), time(14)))
    }

    @Test
    fun `同日区间起闭结束开`() {
        assertTrue(ReminderPolicy.isQuietNow(time(12), time(12), time(14)))
        assertFalse(ReminderPolicy.isQuietNow(time(14), time(12), time(14)))
    }

    @Test
    fun `同日区间两侧之外都不命中`() {
        assertFalse(ReminderPolicy.isQuietNow(time(11), time(12), time(14)))
        assertFalse(ReminderPolicy.isQuietNow(time(20), time(12), time(14)))
    }

    @Test
    fun `跨午夜命中深夜与凌晨两段`() {
        assertTrue(ReminderPolicy.isQuietNow(time(23), time(22), time(7)))
        assertTrue(ReminderPolicy.isQuietNow(time(3), time(22), time(7)))
    }

    @Test
    fun `跨午夜白天不命中`() {
        assertFalse(ReminderPolicy.isQuietNow(time(12), time(22), time(7)))
        assertFalse(ReminderPolicy.isQuietNow(time(21), time(22), time(7)))
    }

    @Test
    fun `跨午夜起闭结束开`() {
        assertTrue(ReminderPolicy.isQuietNow(time(22), time(22), time(7)))
        // 07:00 整点应恢复提醒，而不是再静默一分钟。
        assertFalse(ReminderPolicy.isQuietNow(time(7), time(22), time(7)))
    }

    @Test
    fun `起止相等视为无效配置一律不静默`() {
        // 否则用户误设成 22:00 到 22:00 会变成「全天不提醒」。
        assertFalse(ReminderPolicy.isQuietNow(time(22), time(22), time(22)))
        assertFalse(ReminderPolicy.isQuietNow(time(7), time(7), time(7)))
    }

    // ── parseTime ────────────────────────────────────────────────────────────

    @Test
    fun `parseTime 解析合法值与首尾空白`() {
        assertEquals(time(22), ReminderPolicy.parseTime("22:00", time(0)))
        assertEquals(time(7, 5), ReminderPolicy.parseTime(" 07:05 ", time(0)))
    }

    @Test
    fun `parseTime 非法或空值回落 fallback`() {
        val fallback = time(9, 30)
        assertEquals(fallback, ReminderPolicy.parseTime(null, fallback))
        assertEquals(fallback, ReminderPolicy.parseTime("", fallback))
        assertEquals(fallback, ReminderPolicy.parseTime("not-a-time", fallback))
        assertEquals(fallback, ReminderPolicy.parseTime("25:00", fallback))
    }

    // ── shouldSuppressReminder ───────────────────────────────────────────────

    @Test
    fun `开关关闭时即使命中区间也不抑制`() {
        assertFalse(
            ReminderPolicy.shouldSuppressReminder(
                enabled = false,
                now = time(23),
                startRaw = "22:00",
                endRaw = "07:00"
            )
        )
    }

    @Test
    fun `开关开启且缺省配置按默认 22 到 7 判定`() {
        assertTrue(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(23),
                startRaw = null,
                endRaw = null
            )
        )
        assertFalse(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(12),
                startRaw = null,
                endRaw = null
            )
        )
    }

    @Test
    fun `自定义同日区间覆盖默认区间`() {
        assertTrue(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(13),
                startRaw = "12:00",
                endRaw = "14:00"
            )
        )
        // 默认区间本会命中的 23 点，在自定义区间下不该再被抑制。
        assertFalse(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(23),
                startRaw = "12:00",
                endRaw = "14:00"
            )
        )
    }

    @Test
    fun `脏配置回落默认区间而不是抛异常`() {
        assertTrue(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(23),
                startRaw = "bad",
                endRaw = "bad"
            )
        )
        assertFalse(
            ReminderPolicy.shouldSuppressReminder(
                enabled = true,
                now = time(12),
                startRaw = "bad",
                endRaw = "bad"
            )
        )
    }
}
