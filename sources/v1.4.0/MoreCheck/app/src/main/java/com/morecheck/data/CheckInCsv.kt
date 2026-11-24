package com.morecheck.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 打卡记录的 CSV 视图所需的最小字段。
 *
 * 为什么另建纯数据类：纯 JVM 测试要能直接构造输入而不引用 Room 实体。
 * 带 [CheckTask] / [CheckRecord] 的 [CheckInCsv.build] 重载只是把 Room 实体
 * 映射成这两个类再走同一段纯实现，界面调它、测试调纯数据类重载，各取所需。
 */
data class CsvTask(
    val id: Long,
    val name: String,
    val groupName: String? = null,
    val tags: String? = null,
    val note: String? = null
)

/** 一条打卡记录的 CSV 视图字段，[date] 为 `yyyy-MM-dd`。 */
data class CsvRecord(
    val taskId: Long,
    val date: String,
    val checkedAt: Long
)

/**
 * 打卡记录 → CSV 文本的纯 JVM 格式化器。
 *
 * 列固定为：日期,任务ID,任务名称,分组,标签,打卡时刻,备注。
 * [build] 返回**不含 BOM** 的纯文本；写文件时由调用方套一层 [withBom]，
 * 这样纯文本既能直接断言，落盘时又能让 Excel 正确识别中文。
 */
object CheckInCsv {

    private val Columns = listOf("日期", "任务ID", "任务名称", "分组", "标签", "打卡时刻", "备注")
    private val Header: String = Columns.joinToString(",")
    private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 生成 CSV 文本：表头一行，其后每条记录一行。
     *
     * 排序口径：按 [CsvRecord.date] 字符串升序（ISO 日期字典序即时间序），
     * 同一天按任务名升序、再按任务 ID 升序兜底，保证结果稳定可复现。
     * 任务已被删除但记录仍在时（删任务不带历史），该行的任务信息列留空、记录行保留。
     *
     * @param zone 打卡时刻的显示时区。默认取系统时区；纯 JVM 测试可显式传入，
     *   避免断言随运行机器的时区漂移。
     */
    fun build(
        tasks: List<CsvTask>,
        records: List<CsvRecord>,
        zone: ZoneId = ZoneId.systemDefault()
    ): String = format(tasks, records, zone)

    /**
     * 便捷重载：直接吃 Room 实体 [CheckTask] / [CheckRecord]，映射后走纯实现。
     *
     * 这是 UI 导出的主入口，打卡时刻取系统默认时区。纯 JVM 测试请用上面的
     * 纯数据类重载，这样测试就不必引用 Room 实体。
     *
     * 两个重载的 JVM 形参个数不同（3 个 vs 2 个），且元素类型互不相干，
     * 因此既不会有签名冲突，也不会在调用点产生歧义。
     */
    fun build(tasks: List<CheckTask>, records: List<CheckRecord>): String =
        format(
            tasks = tasks.map { CsvTask(it.id, it.name, it.groupName, it.tags, it.note) },
            records = records.map { CsvRecord(it.taskId, it.date, it.checkedAt) },
            zone = ZoneId.systemDefault()
        )

    private fun format(
        tasks: List<CsvTask>,
        records: List<CsvRecord>,
        zone: ZoneId
    ): String {
        val byId = tasks.associateBy { it.id }
        val rows = records.map { record ->
            val task = byId[record.taskId]
            val time = Instant.ofEpochMilli(record.checkedAt)
                .atZone(zone)
                .format(TimeFormatter)
            Row(
                date = record.date,
                taskName = task?.name.orEmpty(),
                taskId = record.taskId,
                fields = listOf(
                    record.date,
                    record.taskId.toString(),
                    task?.name,
                    task?.groupName,
                    task?.tags,
                    time,
                    task?.note
                )
            )
        }.sortedWith(
            compareBy<Row> { it.date }
                .thenBy { it.taskName }
                .thenBy { it.taskId }
        )

        return buildString {
            append(Header)
            rows.forEach { row ->
                append('\n')
                append(row.fields.joinToString(",") { escape(it) })
            }
        }
    }

    /**
     * 给 CSV 文本加 UTF-8 BOM（`EF BB BF`）后返回字节数组，供写入文件。
     *
     * 为什么要 BOM：Excel 在中文 Windows 上按本地编码猜无 BOM 的 CSV，中文表头会乱码；
     * 开头三字节能让它明确按 UTF-8 解码。BOM 只属于**文件**，不属于文本本身，
     * 因此不放在 [build] 的返回值里。
     */
    fun withBom(text: String): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        return ByteArray(body.size + 3).also { out ->
            out[0] = 0xEF.toByte()
            out[1] = 0xBB.toByte()
            out[2] = 0xBF.toByte()
            body.copyInto(out, destinationOffset = 3)
        }
    }

    /**
     * RFC4180 字段转义：含逗号 / 双引号 / CR / LF 的字段用双引号包起，
     * 内部双引号翻倍。null 视为空串，与「备注为空」的输出一致。
     */
    private fun escape(field: String?): String {
        val value = field.orEmpty()
        val mustQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (mustQuote) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    private data class Row(
        val date: String,
        val taskName: String,
        val taskId: Long,
        val fields: List<String?>
    )
}
