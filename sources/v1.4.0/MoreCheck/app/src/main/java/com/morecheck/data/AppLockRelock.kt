package com.morecheck.data

/**
 * @param key 存进 DataStore 的值（不要改，改了老用户的设置会回落默认）。
 * @param label 设置页显示的名字。
 * @param timeoutMs 离开多久后要求重新解锁；[COLD_START] 用 -1 表示"从不因切后台而锁"。
 */
enum class AppLockRelock(val key: String, val label: String, val timeoutMs: Long) {

    /** 仅冷启动（改造前的行为，默认）。 */
    COLD_START("cold_start", "仅冷启动", -1L),

    /** 一离开前台就锁（切到桌面、锁屏、跳去别的应用都算）。 */
    IMMEDIATE("immediate", "立即", 0L),

    /** 离开 1 分钟以上才锁。 */
    ONE_MINUTE("1m", "离开 1 分钟后", 60_000L),

    /** 离开 5 分钟以上才锁。 */
    FIVE_MINUTES("5m", "离开 5 分钟后", 300_000L),

    /** 解锁后 1 小时内切后台回来不锁；超过 1 小时后回前台立即要求解锁。 */
    ONE_HOUR_GRACE("1h", "1 小时内不再锁", 3_600_000L),

    /** 解锁后当天内切后台回来不锁；跨天（次日 0 点起）回前台立即要求解锁。 */
    TODAY_GRACE("today", "今天内不再锁", -2L);

    /** 是否需要观察前后台切换（[COLD_START] 不需要，宽限档也需要）。 */
    val watchesBackground: Boolean get() = this != COLD_START

    /** 宽限档：判据是"距上次解锁多久"，与"离开前台多久"无关。 */
    val isGrace: Boolean get() = this == ONE_HOUR_GRACE || this == TODAY_GRACE

    companion object {
        /** 默认值（保持升级前后行为一致）。 */
        val DEFAULT = COLD_START

        /** 按存盘值取枚举；未知值一律回落默认（防止将来降级安装读到新键）。 */
        fun fromKey(value: String?): AppLockRelock =
            entries.firstOrNull { it.key == value } ?: DEFAULT
    }
}
