package com.morecheck.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份文件的数据结构（纯 JSON，可读写到用户选择的任意文件）。
 *
 * 只包含用户真正在乎的内容：任务、打卡记录、外观设置。
 * 不含登录态——那是本地模拟数据，恢复它没有意义。
 */
data class BackupData(
    val version: Int,
    val exportedAt: Long,
    val tasks: List<CheckTask>,
    val records: List<CheckRecord>,
    /** 深色模式：light / dark / system。 */
    val themeMode: String?,
    val dynamicColor: Boolean?
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", version)
        root.put("exportedAt", exportedAt)

        val taskArray = JSONArray()
        tasks.forEach { t ->
            taskArray.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("name", t.name)
                    put("icon", t.icon)
                    put("color", t.color)
                    put("reminderTime", t.reminderTime ?: JSONObject.NULL)
                    put("createdAt", t.createdAt)
                }
            )
        }
        root.put("tasks", taskArray)

        val recordArray = JSONArray()
        records.forEach { r ->
            recordArray.put(
                JSONObject().apply {
                    put("taskId", r.taskId)
                    put("date", r.date)
                    put("checkedAt", r.checkedAt)
                }
            )
        }
        root.put("records", recordArray)

        themeMode?.let { root.put("themeMode", it) }
        dynamicColor?.let { root.put("dynamicColor", it) }
        return root.toString(2)
    }

    companion object {
        const val FORMAT = "morecheck-backup"
        const val CURRENT_VERSION = 1

        /**
         * 解析备份 JSON。
         *
         * 对缺字段、类型不符、格式不对都做了容错：无法识别时抛
         * [IllegalArgumentException]，由调用方转成用户可读的提示。
         */
        fun fromJson(text: String): BackupData {
            val root = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalArgumentException("不是有效的 JSON 文件") }

            val format = root.optString("format", "")
            if (format != FORMAT) {
                throw IllegalArgumentException("不是 MoreCheck 的备份文件")
            }

            val version = root.optInt("version", CURRENT_VERSION)
            if (version > CURRENT_VERSION) {
                throw IllegalArgumentException("备份来自更高版本应用（v$version），请先升级")
            }

            val tasks = mutableListOf<CheckTask>()
            val taskArray = root.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until taskArray.length()) {
                val o = taskArray.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isBlank()) continue
                tasks += CheckTask(
                    id = o.optLong("id", 0L),
                    name = name,
                    icon = o.optString("icon", ""),
                    color = o.optInt("color", 0),
                    reminderTime = if (o.isNull("reminderTime")) null else o.optString("reminderTime").ifBlank { null },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis())
                )
            }

            val records = mutableListOf<CheckRecord>()
            val recordArray = root.optJSONArray("records") ?: JSONArray()
            for (i in 0 until recordArray.length()) {
                val o = recordArray.optJSONObject(i) ?: continue
                val taskId = o.optLong("taskId", 0L)
                val date = o.optString("date", "")
                if (taskId <= 0L || date.isBlank()) continue
                records += CheckRecord(
                    taskId = taskId,
                    date = date,
                    checkedAt = o.optLong("checkedAt", System.currentTimeMillis())
                )
            }

            return BackupData(
                version = version,
                exportedAt = root.optLong("exportedAt", 0L),
                tasks = tasks,
                records = records,
                themeMode = root.optString("themeMode", "").ifBlank { null },
                dynamicColor = if (root.has("dynamicColor")) root.optBoolean("dynamicColor") else null
            )
        }
    }
}
