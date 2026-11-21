package com.morecheck.util

import android.content.Context
import android.content.pm.PackageManager
import java.time.Instant
import java.time.ZoneId

object AppInfo {

    /**
     * 应用最近更新日期，取自系统的 `PackageInfo.lastUpdateTime`。
     *
     * 之前这里是硬编码常量，改版本时容易忘记同步，导致界面上的「更新日期」与实际不符。
     * 改为读取已安装包的真实更新时间后，每次覆盖安装都会自动同步，无需手工维护。
     */
    fun lastUpdateDate(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val date = Instant.ofEpochMilli(info.lastUpdateTime)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }.getOrElse { "未知" }

    /** 已安装包的版本名，用于「版本：vX」展示。 */
    fun versionName(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "未知"
}
