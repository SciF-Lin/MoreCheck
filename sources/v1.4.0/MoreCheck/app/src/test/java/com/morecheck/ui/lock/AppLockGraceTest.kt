package com.morecheck.ui.lock

import com.morecheck.data.AppLockRelock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 「今天内不再锁 / 1 小时内不再锁」跨进程恢复的回归测试。
 *
 * 缺陷：解锁状态原先只在进程内存活，划掉最近任务后冷启动必然重新验证。
 */
class AppLockGraceTest {

    private val now = System.currentTimeMillis()

    private fun yesterdaySameTime(): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    @Test
    fun 今天内档在同一天解锁后可恢复() {
        val at = now - 60_000L
        val raw = AppLockGrace.encode(AppLockRelock.TODAY_GRACE, at)
        assertEquals(at, AppLockGrace.decode(raw, AppLockRelock.TODAY_GRACE, now))
    }

    /**
     * 2026-11-06 回归：在设置页**刚切到**宽限档、还没经历过一次新的解锁，就杀掉进程。
     *
     * 修法是"切档的那一刻立即写入凭据"（用户此刻本来就是已解锁状态），
     * 所以这里断言「以切换时刻写入的凭据，冷启动时能直接恢复」。
     */
    @Test
    fun 切到宽限档的当下写入凭据即可冷启动恢复() {
        val raw = AppLockGrace.encode(AppLockRelock.TODAY_GRACE, now)
        assertNotNull(AppLockGrace.decode(raw, AppLockRelock.TODAY_GRACE, now))
        // 一小时档同理
        val raw1h = AppLockGrace.encode(AppLockRelock.ONE_HOUR_GRACE, now)
        assertNotNull(AppLockGrace.decode(raw1h, AppLockRelock.ONE_HOUR_GRACE, now))
    }

    @Test
    fun 今天内档跨天后失效() {
        val raw = AppLockGrace.encode(AppLockRelock.TODAY_GRACE, yesterdaySameTime())
        assertNull(AppLockGrace.decode(raw, AppLockRelock.TODAY_GRACE, now))
    }

    @Test
    fun 一小时档在宽限期内有效过期失效() {
        val inside = AppLockGrace.encode(AppLockRelock.ONE_HOUR_GRACE, now - 59 * 60_000L)
        val outside = AppLockGrace.encode(AppLockRelock.ONE_HOUR_GRACE, now - 61 * 60_000L)
        assertNotNull(AppLockGrace.decode(inside, AppLockRelock.ONE_HOUR_GRACE, now))
        assertNull(AppLockGrace.decode(outside, AppLockRelock.ONE_HOUR_GRACE, now))
    }

    @Test
    fun 存盘档位与当前档位不一致时失效() {
        val raw = AppLockGrace.encode(AppLockRelock.TODAY_GRACE, now - 1_000L)
        assertNull(AppLockGrace.decode(raw, AppLockRelock.ONE_HOUR_GRACE, now))
    }

    @Test
    fun 非宽限档一律不恢复() {
        val raw = AppLockGrace.encode(AppLockRelock.COLD_START, now - 1_000L)
        assertNull(AppLockGrace.decode(raw, AppLockRelock.COLD_START, now))
        assertNull(AppLockGrace.decode(raw, AppLockRelock.IMMEDIATE, now))
    }

    @Test
    fun 空值与脏值不生效() {
        assertNull(AppLockGrace.decode(null, AppLockRelock.TODAY_GRACE, now))
        assertNull(AppLockGrace.decode("", AppLockRelock.TODAY_GRACE, now))
        assertNull(AppLockGrace.decode("garbage", AppLockRelock.TODAY_GRACE, now))
        assertNull(AppLockGrace.decode("today|abc", AppLockRelock.TODAY_GRACE, now))
        assertNull(AppLockGrace.decode("|123", AppLockRelock.TODAY_GRACE, now))
    }

    @Test
    fun 未来时间戳不生效防止调系统时间永久免验证() {
        val raw = AppLockGrace.encode(AppLockRelock.TODAY_GRACE, now + 60_000L)
        assertNull(AppLockGrace.decode(raw, AppLockRelock.TODAY_GRACE, now))
    }
}
