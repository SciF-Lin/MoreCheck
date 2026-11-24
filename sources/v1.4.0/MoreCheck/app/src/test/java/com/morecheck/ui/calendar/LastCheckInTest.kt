package com.morecheck.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日历页底部「距上次打卡」的文案与取数（2026-10-31 需求）。
 *
 * 需求原文：以**时和天**为单位，**不足 1 小时的部分舍去**。
 * 边界都钉在这里：59 分钟 → 不足 1 小时；23h59m → 23 小时；整 24h → 1 天。
 */
class LastCheckInTest {

    private val now = 1_760_000_000_000L
    private fun minusMinutes(m: Long) = now - m * 60_000L

    @Test
    fun `没有记录时提示还没打过卡`() {
        assertEquals("还没有打卡记录", lastCheckInText(now, null))
    }

    @Test
    fun `不足一小时舍去为不足1小时`() {
        assertEquals("距上次打卡 不足 1 小时", lastCheckInText(now, minusMinutes(59)))
        assertEquals("距上次打卡 不足 1 小时", lastCheckInText(now, minusMinutes(0)))
    }

    @Test
    fun `整一小时后按小时显示`() {
        assertEquals("距上次打卡 1 小时", lastCheckInText(now, minusMinutes(60)))
        assertEquals("距上次打卡 23 小时", lastCheckInText(now, minusMinutes(23 * 60 + 59)))
    }

    @Test
    fun `超过一天按天与小时显示`() {
        assertEquals("距上次打卡 1 天", lastCheckInText(now, minusMinutes(24 * 60)))
        assertEquals("距上次打卡 1 天 1 小时", lastCheckInText(now, minusMinutes(25 * 60)))
        assertEquals("距上次打卡 2 天 3 小时", lastCheckInText(now, minusMinutes((2 * 24 + 3) * 60L)))
    }

    /** 时钟回拨 / 记录来自更快的设备：不能显示负数。 */
    @Test
    fun `未来时间按刚打过处理`() {
        assertEquals("距上次打卡 不足 1 小时", lastCheckInText(now, now + 3_600_000L))
    }

    @Test
    fun `取全部记录里最晚的一次`() {
        val records = mapOf(
            "2026-10-29" to mapOf(1L to now - 5_000L, 2L to now - 9_000L),
            "2026-10-30" to mapOf(3L to now - 1_000L)
        )
        assertEquals(now - 1_000L, latestCheckInOf(records))
    }

    @Test
    fun `没有记录时取数为空`() {
        assertNull(latestCheckInOf(emptyMap()))
        assertNull(latestCheckInOf(mapOf("2026-10-30" to emptyMap())))
    }
}
