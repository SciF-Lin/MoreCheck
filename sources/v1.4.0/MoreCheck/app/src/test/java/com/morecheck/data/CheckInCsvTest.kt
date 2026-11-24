package com.morecheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * [CheckInCsv] 的纯 JVM 回归测试。
 *
 * 全部输入用 [CsvTask] / [CsvRecord] 这两个纯数据类构造，刻意不碰 Room 实体，
 * 这样测试不需要 android.* / Room，也不依赖模拟器。每条断言都对应 KDoc 里
 * 写明的一条规则。
 */
class CheckInCsvTest {

    private val header = "日期,任务ID,任务名称,分组,标签,打卡时刻,备注"

    @Test
    fun `空数据只有表头`() {
        assertEquals(
            header,
            CheckInCsv.build(emptyList(), emptyList(), ZoneId.of("UTC"))
        )
    }

    @Test
    fun `逗号引号与换行按 RFC4180 转义`() {
        val task = CsvTask(
            id = 1L,
            name = "早,起",
            groupName = "健康\"组",
            tags = "标签1,标签2",
            note = "第一行\n第二行"
        )
        val result = CheckInCsv.build(
            listOf(task),
            listOf(CsvRecord(1L, "2026-11-04", 0L)),
            ZoneId.of("UTC")
        )
        assertEquals(
            header + "\n" +
                "2026-11-04,1,\"早,起\",\"健康\"\"组\",\"标签1,标签2\"," +
                "1970-01-01 00:00,\"第一行\n第二行\"",
            result
        )
    }

    @Test
    fun `按日期升序同日按任务名排序`() {
        val tasks = listOf(
            CsvTask(1L, "B"),
            CsvTask(2L, "Z"),
            CsvTask(3L, "A")
        )
        val records = listOf(
            CsvRecord(2L, "2026-11-04", 0L),
            CsvRecord(3L, "2026-11-04", 0L),
            CsvRecord(1L, "2026-11-03", 0L)
        )
        val result = CheckInCsv.build(tasks, records, ZoneId.of("UTC"))
        assertEquals(
            header + "\n" +
                "2026-11-03,1,B,,,1970-01-01 00:00,\n" +
                "2026-11-04,3,A,,,1970-01-01 00:00,\n" +
                "2026-11-04,2,Z,,,1970-01-01 00:00,",
            result
        )
    }

    @Test
    fun `withBom 写入 EF BB BF 且正文按 UTF8 编码`() {
        val bytes = CheckInCsv.withBom("abc中文")
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])
        assertEquals(
            "abc中文",
            bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        )
    }

    @Test
    fun `备注为空输出空字段且 build 本身不含 BOM`() {
        val result = CheckInCsv.build(
            listOf(CsvTask(1L, "喝水")),
            listOf(CsvRecord(1L, "2026-11-04", 0L)),
            ZoneId.of("UTC")
        )
        assertTrue(result.endsWith(",1970-01-01 00:00,"))
        // BOM 是文件的事，只在 withBom 里加，build 返回的必须是纯文本。
        assertTrue(!result.startsWith("\uFEFF"))
    }

    @Test
    fun `打卡时刻按注入的时区格式化`() {
        val result = CheckInCsv.build(
            listOf(CsvTask(1L, "X")),
            listOf(CsvRecord(1L, "2026-11-04", 0L)),
            ZoneId.of("Asia/Shanghai")
        )
        assertTrue(result.contains("1970-01-01 08:00"))
    }
}
