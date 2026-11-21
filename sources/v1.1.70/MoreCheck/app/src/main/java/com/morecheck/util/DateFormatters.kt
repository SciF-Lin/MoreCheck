package com.morecheck.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val MONTH_DATE = DateTimeFormatter.ofPattern("yyyy年M月")
private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

fun LocalDate.weekdayLabel(): String = WEEKDAYS[dayOfWeek.value - 1]

fun LocalDate.fullLabel(): String = format(FULL_DATE) + " " + weekdayLabel()

fun LocalDate.monthLabel(): String = format(MONTH_DATE)

/**
 * 把 epoch 毫秒格式化成 "HH:mm"（设备时区）。
 *
 * 放在 util 里而不是各页面内联，是因为日历页与打卡记录页要用同一种时间格式，
 * 各写一份迟早会漂移（RecordsScreen 目前就还内联着同样的实现）。
 */
fun Long.toHhMm(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(HOUR_MINUTE)
