package com.morecheck.domain

import java.time.LocalDate

/**
 * 一条打卡记录在 domain 层的**中性**表示。
 *
 * 为什么不用 data 层的 CheckRecord：它带 Room 的实体注解，属于数据库实现细节；
 * 而"哪天算达标"是纯日期逻辑，理应能在纯 JVM 单测里随手构造，不拖进任何
 * Android 运行时。只留下算达标真正用得到的两个字段，checkedAt 与 updatedAt
 * 对"这一天有没有打卡"没有贡献，刻意不带。
 *
 * @param date "yyyy-MM-dd"，设备时区下的日期；解析失败的记录会被 [computeStreakSummary] 忽略。
 */
data class StreakRecord(val taskId: Long, val date: String)

/**
 * 「连续打卡可视化」的全部派生量。
 *
 * 这是 StateFlow 的 value 类型：纯数据、可安全参与 Compose 重组与进程级缓存。
 * 所有字段都由 [computeStreakSummary] 一次算出，UI 不必再自己数天数、自己判今天。
 *
 * @param completedDates 达标日期集合（完整历史）。最长连续与热力条都从它派生，
 *   因此它**不**只含近 30 天。
 * @param current 当前连续天数，复用 StreakCalculator.currentStreak。
 * @param longest 历史最长连续天数，复用 StreakCalculator.longestStreak。
 * @param last30Days 近 30 天达标标记，按时间**升序**（最左是 today-29、最右是 today），
 *   供热力条按"过去到今天"从左到右绘制；长度恒为 30。
 * @param todayCompleted 今天是否已达标，等于 today 落在 completedDates 内。UI 直接读它，
 *   不必把 today 再塞进摘要判一次。
 */
data class StreakSummary(
    val completedDates: Set<LocalDate>,
    val current: Int,
    val longest: Int,
    val last30Days: List<Pair<LocalDate, Boolean>>,
    val todayCompleted: Boolean
) {
    companion object {
        /** 空摘要：stateIn 的初值。首帧还没拿到数据时全是零值，不伪造任何达标。 */
        val EMPTY = StreakSummary(
            completedDates = emptySet(),
            current = 0,
            longest = 0,
            last30Days = emptyList(),
            todayCompleted = false
        )
    }
}

/**
 * 由打卡记录算出 [StreakSummary] 的纯函数（无 Android 依赖，可在纯 JVM 单测里跑）。
 *
 * ## 达标口径：当天「至少有一个任务打卡」即算达标
 *
 * 刻意**不**采用"当天所有任务都完成"。原因：任务会增删、归档、暂停，也会补打卡，
 * 若要求全部完成，新用户或偶尔漏一项的用户几乎永远停 0 天，"连续打卡"这条
 * 正向反馈在产品上就没有意义了。只要当天留下过一次真实打卡就承认这一天，
 * 这是最宽容也最稳定的口径。
 *
 * ## 为什么要用 activeTaskIds 过滤
 *
 * 只有"当前仍在册"的任务的打卡才算数（activeTaskIds 由调用方给出，语义是
 * 未归档且未暂停的任务 id）。归档、暂停、已删除任务的**历史记录仍可能留在库里**
 * （删除可不删历史，见 TaskRepository.deleteTask），若不过滤，一个早已归档的
 * 任务会把用户的连续记录凭空续上，属于幽灵数据。
 *
 * ## 边界处理
 *
 * - 空记录，或记录全都不属于 activeTaskIds：completedDates 为空、各计数为 0、
 *   last30Days 全 false；
 * - 暂停任务：不在 activeTaskIds 内（由调用方过滤），其记录被忽略；
 * - 未来日期（date 晚于 today）：改系统时间 / 异常补录可能造出，一律剔除，
 *   否则会污染 longest（在 today 之外续出一段假的连续）；
 * - 无法解析的 date 字符串：runCatching 后丢弃，不让一条脏数据把整页弄崩。
 */
fun computeStreakSummary(
    records: List<StreakRecord>,
    activeTaskIds: Set<Long>,
    today: LocalDate
): StreakSummary {
    val completedDates = records
        .asSequence()
        .filter { it.taskId in activeTaskIds }
        .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .filter { !it.isAfter(today) }
        .toSet()

    val current = StreakCalculator.currentStreak(completedDates, today)
    val longest = StreakCalculator.longestStreak(completedDates)
    val last30Days = (29L downTo 0L).map { daysAgo ->
        val date = today.minusDays(daysAgo)
        date to (date in completedDates)
    }
    return StreakSummary(
        completedDates = completedDates,
        current = current,
        longest = longest,
        last30Days = last30Days,
        todayCompleted = today in completedDates
    )
}
