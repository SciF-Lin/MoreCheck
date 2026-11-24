package com.morecheck.ui.calendar

import java.util.concurrent.TimeUnit

/**
 * 「距上次打卡」的文案（2026-10-31 需求：日历页底部显示，以天和小时为单位，
 * **不足 1 小时的部分舍去**）。
 *
 * ## 为什么抽成纯函数
 *
 * 只做时间差与字符串拼接，没有任何 Compose / Android 依赖，可以直接单测。
 * 这段文案的分支不多但容易写错边界（整 24 小时算"1 天"还是"24 小时"、
 * 59 分钟该显示什么），放成纯函数就能把这些边界钉死。
 *
 * @param nowMillis 当前时刻。
 * @param lastMillis 最近一次打卡的时刻；`null` = 一条记录都没有。
 */
internal fun lastCheckInText(nowMillis: Long, lastMillis: Long?): String {
    if (lastMillis == null) return "还没有打卡记录"
    // 时钟回拨 / 记录来自未来的设备时差：按"刚打过"处理，不显示负数。
    val elapsed = (nowMillis - lastMillis).coerceAtLeast(0L)
    val totalHours = TimeUnit.MILLISECONDS.toHours(elapsed)
    if (totalHours < 1) return "距上次打卡 不足 1 小时"
    val days = totalHours / 24
    val hours = totalHours % 24
    // 整天的部分不拖一个"0 小时"的尾巴；不足一天就只说小时。
    return when {
        days <= 0L -> "距上次打卡 $hours 小时"
        hours == 0L -> "距上次打卡 $days 天"
        else -> "距上次打卡 $days 天 $hours 小时"
    }
}

/**
 * 从「日期 → (任务 id → 打卡时刻)」里取最近一次打卡的时刻。
 *
 * 结构见 `CheckedAtByDate`。全空时返回 null（还没打过卡）。
 */
internal fun latestCheckInOf(recordsByDate: Map<String, Map<Long, Long>>): Long? =
    recordsByDate.values.asSequence()
        .flatMap { it.values.asSequence() }
        .maxOrNull()
