package com.morecheck.ui.lock

import com.morecheck.data.AppLockRelock
import java.util.Calendar

/**
 * 「宽限档解锁凭据」的编码与校验（纯 Kotlin，无 Android 依赖，便于纯 JVM 单测）。
 *
 * ## 修的是什么问题
 *
 * 「1 小时内不再锁」/「今天内不再锁」原先只活在 [AppLockSession] 的进程内变量里。
 * 用户划掉最近任务（或系统回收进程）后这些变量归零，冷启动必然重新弹锁屏——
 * 与设置项的字面承诺不符。现在把「解锁时刻 + 当时的档位」落进 DataStore，
 * 冷启动时由 `AppLockGate` 读取并恢复解锁状态。
 *
 * ## 为什么把档位一起存
 *
 * 只存时间戳的话，用户把档位从「今天内不再锁」改成「立即」再改回来，
 * 一个当天早些时候的旧时间戳会重新生效，等于凭空放行一次。带上档位后，
 * 解码时要求存的和当前的完全一致，改过档位就自然失效。
 *
 * ## 安全口径
 *
 * 这里存的**不是密码**，只是"用户什么时候成功解锁过"的时间戳。能读写应用私有
 * 数据的人本来就能绕过这层本地锁（手势哈希与盐同在盘上，见 AppLockGate 的 KDoc），
 * 持久化一个时间戳没有扩大威胁面；它换来的是设置项行为与用户预期一致。
 */
internal object AppLockGrace {

    private const val SEPARATOR = '|'

    /** 编码成单个字符串，避免为两个值各开一个 DataStore key。 */
    fun encode(mode: AppLockRelock, unlockedAt: Long): String = "${mode.key}$SEPARATOR$unlockedAt"

    /**
     * 校验并返回可用于恢复解锁状态的时刻；任何一项不满足都返回 null（要求重新验证）。
     *
     * @param raw DataStore 里读到的原始值，空串代表从未写过。
     * @param mode 当前生效的重新锁定档位。
     * @param now 当前墙钟时刻（毫秒）。
     */
    fun decode(raw: String?, mode: AppLockRelock, now: Long): Long? {
        if (!mode.isGrace) return null
        val value = raw ?: return null
        val separator = value.indexOf(SEPARATOR)
        if (separator <= 0) return null
        if (value.substring(0, separator) != mode.key) return null

        val unlockedAt = value.substring(separator + 1).toLongOrNull() ?: return null
        // 未来时刻（用户往后调过系统时间）不认，避免一次解锁永久免验证。
        if (unlockedAt <= 0L || unlockedAt > now) return null

        return when (mode) {
            AppLockRelock.ONE_HOUR_GRACE ->
                if (now - unlockedAt < 3_600_000L) unlockedAt else null

            AppLockRelock.TODAY_GRACE ->
                if (isSameLocalDay(unlockedAt, now)) unlockedAt else null

            else -> null
        }
    }

    /** 与 [AppLockSession] 里的判定保持同一套「本地日历天」口径。 */
    private fun isSameLocalDay(a: Long, b: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = a
        val dayA = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = b
        val dayB = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        return dayA == dayB
    }
}
