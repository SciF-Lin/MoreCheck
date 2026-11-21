package com.morecheck.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val MONTH_DATE = DateTimeFormatter.ofPattern("yyyy年M月")

fun LocalDate.weekdayLabel(): String = WEEKDAYS[dayOfWeek.value - 1]

fun LocalDate.fullLabel(): String = format(FULL_DATE) + " " + weekdayLabel()

fun LocalDate.monthLabel(): String = format(MONTH_DATE)
