package com.morecheck.domain

import java.time.LocalTime

/**
 * 打卡提醒的策略判定（纯 JVM，无 Android 依赖，便于单测）。
 *
 * 抽出来的原因：静默时段涉及「跨午夜」边界，直接写在 BroadcastReceiver 里
 * 没法用纯 JVM 测试验证，而这里一旦判错就会在最不该打扰用户的时间发通知。
 */
object ReminderPolicy {

    /**
     * 当前是否处于静默时段。
     *
     * - `start < end`（如 12:00 → 14:00）：区间 `[start, end)`；
     * - `start > end`（如 22:00 → 07:00，默认）：跨午夜，命中 `now >= start || now < end`；
     * - `start == end`：视为无效配置，返回 false（不静默），避免"全天不提醒"
     *   这种用户其实没打算设置的状态。
     *
     * 结束时刻取开区间：07:00 整点应当恢复提醒，而不是再静默一分钟。
     */
    fun isQuietNow(now: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (start == end) return false
        return if (start < end) {
            !now.isBefore(start) && now.isBefore(end)
        } else {
            !now.isBefore(start) || now.isBefore(end)
        }
    }

    /**
     * 解析 "HH:mm"；格式非法时回落到 [fallback]。
     *
     * 设置项由 UI 写入合法值，但 DataStore 是用户数据、可能被旧版本或手工改坏，
     * 所以这里不抛异常——解析失败按「没配静默时段」处理更安全。
     */
    fun parseTime(raw: String?, fallback: LocalTime): LocalTime =
        raw?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() } ?: fallback

    /** 是否应当抑制这次提醒（开关关时一律不抑制）。 */
    fun shouldSuppressReminder(
        enabled: Boolean,
        now: LocalTime,
        startRaw: String?,
        endRaw: String?,
        defaultStart: LocalTime = LocalTime.of(22, 0),
        defaultEnd: LocalTime = LocalTime.of(7, 0)
    ): Boolean {
        if (!enabled) return false
        val start = parseTime(startRaw, defaultStart)
        val end = parseTime(endRaw, defaultEnd)
        return isQuietNow(now, start, end)
    }
}
