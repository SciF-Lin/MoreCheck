package com.morecheck.util

import com.morecheck.data.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val MONTH_DATE = DateTimeFormatter.ofPattern("yyyy年M月")
private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")

/** 「日期显示格式」的 `iso` 档：横杠式完整日期（`2026-10-19`）。 */
private val ISO_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** 「日期显示格式」的 `cn_short` 档：省略年份（`10月19日`）。 */
private val CN_SHORT_DATE = DateTimeFormatter.ofPattern("M月d日")

fun LocalDate.weekdayLabel(): String = WEEKDAYS[dayOfWeek.value - 1]

/**
 * 完整日期标签 = 「日期 + 星期」（**默认档 `cn_full` 的输出**）。
 *
 * ## 为什么保留这个**不带参数**的函数
 *
 * 需求 8 新增的「日期显示格式」三选一里，`cn_full` 的输出就是这一档，
 * 改造前后**逐字相同**。刻意保留它而不是让全部调用点改用带参数的版本：
 * 这样"没接开关的调用点"与"接了开关的调用点"在默认值下输出完全一致，
 * 将来若有人只想改某一处，不必先把整个调用点列表都迁移一遍。
 *
 * 三档**都带星期**（理由见 [fullLabelWith]）。
 */
fun LocalDate.fullLabel(): String = format(FULL_DATE) + " " + weekdayLabel()

/**
 * 按指定档位格式化「日期 + 星期」（需求 8 第 3 项）。
 *
 * 三个档位都**保留星期**，只有日期那一段不同：
 * - [SettingsRepository.DateDisplayFormat.CN_FULL] → `2026年10月19日 周一`（默认，= 改造前）
 * - [SettingsRepository.DateDisplayFormat.ISO] → `2026-10-19 周一`
 * - [SettingsRepository.DateDisplayFormat.CN_SHORT] → `10月19日 周一`
 *
 * ## 未知档位为什么这样处理
 *
 * `when` 刻意**不给 `else`**，让 [SettingsRepository.DateDisplayFormat] 自己保证
 * 只有三个取值（它的 `parse` 对无法识别的 id 回落 `CN_FULL`）——
 * 这与本项目「宁可编译失败也不猜」的既有做法同源：将来若加了第四档却忘了在这里
 * 补分支，**编译期就会报错**，而不是让某个日期静默按错误的格式显示。
 *
 * ## 为什么星期固定用 [weekdayLabel]
 *
 * 三档都带星期，而星期在项目里只有这一种中文写法（全项目统一），
 * 没有"要不要显示星期"这项偏好，因此不在枚举里做这个选择。
 *
 * @param format 目标档位，由调用方从开发者选项那一档传入。
 */
fun LocalDate.fullLabelWith(format: SettingsRepository.DateDisplayFormat): String =
    when (format) {
        SettingsRepository.DateDisplayFormat.CN_FULL -> format(FULL_DATE)
        SettingsRepository.DateDisplayFormat.ISO -> format(ISO_DATE)
        SettingsRepository.DateDisplayFormat.CN_SHORT -> format(CN_SHORT_DATE)
    } + " " + weekdayLabel()

fun LocalDate.monthLabel(): String = format(MONTH_DATE)

/**
 * 把 epoch 毫秒格式化成 "HH:mm"（设备时区）。
 *
 * 放在 util 里而不是各页面内联，是因为日历页与打卡记录页要用同一种时间格式，
 * 各写一份迟早会漂移（RecordsScreen 目前就还内联着同样的实现）。
 *
 * ⚠️ 这是**时间**不是日期，因此**不受**「日期显示格式」影响
 * （需求 8 只给了日期三选一，时间不在其列）。
 */
fun Long.toHhMm(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(HOUR_MINUTE)