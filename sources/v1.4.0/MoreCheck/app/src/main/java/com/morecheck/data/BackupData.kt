package com.morecheck.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份文件的数据结构（纯 JSON，可读写到用户选择的任意文件）。
 *
 * 只包含用户真正在乎的内容：任务、打卡记录、外观设置、记事本。
 * 不含登录态——那是本地模拟数据，恢复它没有意义。
 *
 * ## 记事本为什么也进备份（v3 起）
 *
 * 文字随本结构直接序列化；图片本体是文件，**不在 JSON 里**，只存文件名
 * （[notebookImages]）。本地导出 / 恢复时图片就在本机私有目录，按名字能找到；
 * 云端备份则由 `CloudSyncRepository` 先把这些名字对应的对象下载到私有目录
 * 再走同一条恢复路径——因此恢复端只需要认名字，不需要知道图片从哪来。
 *
 * 两个字段都是**可选**的：旧版本应用写出的备份没有它们，解析为 null / 空；
 * 新版本写出的备份被旧版本读取时，旧版本忽略未知字段。格式版本因此不必升级。
 */
data class BackupData(
    val version: Int,
    val exportedAt: Long,
    val tasks: List<CheckTask>,
    val records: List<CheckRecord>,
    /** 深色模式：light / dark / system。 */
    val themeMode: String?,
    /** 主题色标识，见 `ui/theme/AccentColor`。 */
    val themeAccent: String?,
    /** 记事本正文；null = 该备份不含记事本（旧版本导出）。 */
    val notebookText: String? = null,
    /** 记事本图片的**文件名**（`note_<uuid>.jpg`），按添加先后排列；不含图片字节。 */
    val notebookImages: List<String> = emptyList()
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
                    put("note", t.note ?: JSONObject.NULL)
                    put("createdAt", t.createdAt)
                    // 同步时间戳随备份保留：恢复端直接沿用可维持相对新旧关系，
                    // 恢复动作本身再统一刷新（见 BackupRepository.restore）。
                    put("updatedAt", t.updatedAt)
                    // v5 组织字段写成**可选键**：旧版本解析时忽略未知键，
                    // 因此格式版本仍可保持 1（见类注释的兼容口径）。
                    put("groupName", t.groupName ?: JSONObject.NULL)
                    put("tags", t.tags ?: JSONObject.NULL)
                    put("archived", t.archived)
                    put("paused", t.paused)
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
                    put("updatedAt", r.updatedAt)
                }
            )
        }
        root.put("records", recordArray)

        themeMode?.let { root.put("themeMode", it) }
        themeAccent?.let { root.put("themeAccent", it) }

        // 记事本：只在**有过内容**时才写这两个键，让"没写过笔记"的备份
        // 与旧版本导出的备份在结构上无差别（读取端一律按可选处理）。
        if (!notebookText.isNullOrEmpty() || notebookImages.isNotEmpty()) {
            notebookText?.let { root.put("notebookText", it) }
            if (notebookImages.isNotEmpty()) {
                root.put(
                    "notebookImages",
                    JSONArray().apply { notebookImages.forEach { put(it) } }
                )
            }
        }
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
                    note = if (o.isNull("note")) null else o.optString("note").ifBlank { null },
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    // 旧版本备份没有这一字段 → 回落到 exportedAt（见下方 data 构造），
                    // 语义是"这份备份写于何时，行就视为何时最后改动过"。
                    updatedAt = o.optLong("updatedAt", 0L),
                    // v5 组织字段：旧备份没有这些键，一律回落到中立默认值。
                    groupName = if (o.isNull("groupName")) null else o.optString("groupName").ifBlank { null },
                    tags = if (o.isNull("tags")) null else o.optString("tags").ifBlank { null },
                    archived = o.optBoolean("archived", false),
                    paused = o.optBoolean("paused", false)
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
                    checkedAt = o.optLong("checkedAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }

            val exportedAt = root.optLong("exportedAt", 0L)
            // 旧备份缺 updatedAt 的行统一按导出时间回填（见上面两处 optLong 的 0 默认值），
            // 保证恢复端拿到的时间戳单调、可比较。
            val fallbackUpdatedAt = if (exportedAt > 0L) exportedAt else System.currentTimeMillis()

            val notebookImages = mutableListOf<String>()
            val imageArray = root.optJSONArray("notebookImages") ?: JSONArray()
            for (i in 0 until imageArray.length()) {
                val name = imageArray.optString(i, "")
                if (name.isNotBlank()) notebookImages += name
            }

            return BackupData(
                version = version,
                exportedAt = exportedAt,
                tasks = tasks,
                records = records,
                themeMode = root.optString("themeMode", "").ifBlank { null },
                themeAccent = root.optString("themeAccent", "").ifBlank { null },
                notebookText = root.optString("notebookText", "").ifBlank { null },
                notebookImages = notebookImages
            ).let { data ->
                // 缺时间戳的行用 fallbackUpdatedAt 补齐（copy 一份，不动原对象语义）。
                data.copy(
                    tasks = data.tasks.map { if (it.updatedAt > 0L) it else it.copy(updatedAt = fallbackUpdatedAt) },
                    records = data.records.map { if (it.updatedAt > 0L) it else it.copy(updatedAt = fallbackUpdatedAt) }
                )
            }
        }
    }
}
