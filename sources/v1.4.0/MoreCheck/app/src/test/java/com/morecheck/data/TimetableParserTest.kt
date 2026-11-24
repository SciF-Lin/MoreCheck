package com.morecheck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * [TimetableParser] 的回归测试（v1.1.75 首次引入单元测试）。
 *
 * ## 这些断言是从哪来的（重要，别凭印象改）
 *
 * `curriculum.ics` 那份素材的 **220 个实例 / 84 个上课日 / 某天 4 节课**，
 * 原本只记在 `HANDOVER.md` 的 6.5 节里，来源是「Lead 用 Python 独立重写了
 * RRULE 展开、与 Kotlin 实现对拍」。那是一次**人工**验证，代码不会替你复查——
 * 解析器改一行就得重新做一遍。
 *
 * 这里把那次对拍的结论固化成断言：以后谁动了 [TimetableParser]，
 * 跑一次 `./gradlew test` 就知道有没有踩到回归，不必再起一遍 Python。
 *
 * ## 为什么测试里直接读 assets 文件
 *
 * 单元测试的 working dir 是模块根（`MoreCheck/app/`），因此
 * `src/main/assets/...` 是相对路径可达的。**没有**用 Robolectric 去模拟
 * `AssetManager`：那会为了读一个文件引入整个 Android 运行环境，
 * 而 `TimetableParser` 本身是纯 JVM 的（零 Android import），
 * 完全不需要。这也正是当初把解析器与 `TimetableRepository`（负责读 assets）
 * 拆开的价值——解析逻辑可以脱离 Android 单测。
 */
class TimetableParserTest {

    /** 被测素材：真实打进 APK 的那一份，不另做裁剪。 */
    private val icsText: String by lazy {
        val file = File("src/main/assets/timetable/curriculum.ics")
        assertTrue(
            "找不到素材文件 ${file.absolutePath}（单元测试的 working dir 应是模块根 app/）",
            file.exists()
        )
        file.readText()
    }

    private val timetable: Timetable by lazy { TimetableParser.parse(icsText) }

    // ── 回归基线：与 HANDOVER 6.5 的 Python 对拍结论逐字一致 ──────────────────

    @Test
    fun `真实素材展开出 220 个上课实例`() {
        assertEquals(220, timetable.courses.size)
    }

    @Test
    fun `真实素材共 84 个上课日`() {
        assertEquals(84, timetable.activeDates.size)
    }

    @Test
    fun `学期的起止日期与素材一致`() {
        // HANDOVER 3.0 需求 11 记的是 2026-08-31 ~ 2027-01-08。
        assertEquals(LocalDate.of(2026, 8, 31), timetable.firstDate)
        assertEquals(LocalDate.of(2027, 1, 8), timetable.lastDate)
    }

    @Test
    fun `2026-10-19 当天有 4 节课且时刻与素材一致`() {
        // ⚠️ 这四条来自 HANDOVER 3.0 的实测记录（原文写的是「今天 4 节课」，
        // 那轮实测日期是 **2026-10-19**，不是 10-20）。我曾在本测试里误记成 10-20，
        // 是测试自己把错误抓出来的——随后用 Python 独立重算复核（tools/verify_timetable.py），
        // 确认素材在 10-19 恰好是这四节、10-20 是另外四节。
        // 这条教训值得留着：**别把文档里的"今天"当成任何一个具体日期**。
        val courses = timetable.coursesOn(LocalDate.of(2026, 10, 19))
        assertEquals(4, courses.size)

        val names = courses.map { it.summary }
        assertEquals(
            listOf("数据库原理及应用", "大学物理实验", "数字电子技术", "素描静物画入门"),
            names
        )

        val starts = courses.map { it.startTime }
        assertEquals(
            listOf(
                LocalTime.of(10, 25),
                LocalTime.of(13, 50),
                LocalTime.of(16, 30),
                LocalTime.of(18, 30)
            ),
            starts
        )
    }
    @Test
    fun `2026-10-20 当天是另一组 4 节课`() {
        // 与上一条互为对照：同一周相邻两天课表完全不同，说明按日期索引没有串。
        // 数据同样由 Python 独立复核过。
        val courses = timetable.coursesOn(LocalDate.of(2026, 10, 20))
        assertEquals(4, courses.size)
        assertEquals(
            listOf(
                "数据结构与算法设计（C++）",
                "概率论与数理统计",
                "数据库原理及应用实验",
                "香港历史与文化"
            ),
            courses.map { it.summary }
        )
        assertEquals(
            listOf(
                LocalTime.of(8, 30),
                LocalTime.of(10, 25),
                LocalTime.of(14, 40),
                LocalTime.of(18, 30)
            ),
            courses.map { it.startTime }
        )
    }

    // ── RRULE 展开语义 ────────────────────────────────────────────────────────

    @Test
    fun `展开结果按周重复（同一门课同时段的实例间隔是 7 的整数倍）`() {
        // ⚠️ 这条断言我写错过两次，两次都是**测试的假设**不成立，不是代码有 bug：
        //
        // ① 一开始取"实例数最多的那门课"就断言相邻两次差 7 天 → 得到 28 天。
        //    因为同一门课在一个学期里可能被拆成**多条 VEVENT**（实验课隔几周上），
        //    按时间排序后自然出现 14/28 天的跳跃。
        //
        // ② 改成按「课名 + 开始时刻」分组后仍失败 → 得到间隔不是 7 的倍数。
        //    实测素材里存在这样一组：`Python基础 13:50` 出现在
        //    2026-10-09(周五)、11-04(周三)、11-05(周四)、11-27(周五)——
        //    **是四条不同的 VEVENT**（临时调课），不是一个规则的展开。
        //
        // 所以正确的不变量不能建在"课名+时刻"上。改为验证**每条规则内部**的间隔：
        // 按 (课名, 时刻, 星期几) 分组——同一门课在同一星期几、同一时刻上的实例，
        // 必然来自同一串 `FREQ=WEEKLY` 展开，间隔只能是 7 的整数倍。
        val groups = timetable.courses
            .groupBy { Triple(it.summary, it.startTime, it.dayOfWeek) }
            .values
            .filter { it.size >= 3 }

        assertTrue("素材里应有至少一组上满 3 次以上的同星期课程", groups.isNotEmpty())

        groups.forEach { group ->
            group.sortedBy { it.start }.zipWithNext { a, b ->
                val gap = java.time.temporal.ChronoUnit.DAYS.between(a.date, b.date)
                assertEquals(
                    "同一(课名,时刻,星期)的间隔应是 7 的整数倍，实际 $gap 天：${a.summary} ${a.date}→${b.date}",
                    0L,
                    gap % 7
                )
                assertTrue("间隔不应为 0", gap > 0)
                assertEquals("同一规则内时长应一致", a.duration, b.duration)
            }
        }
    }

    @Test
    fun `coursesOn 的返回结果确实按开始时间升序（KDoc 承诺）`() {
        // 这条钉住的是 v1.1.75 修的一个"文档与实现不符"：
        // `byDate` 原先是 `groupBy` 的裸结果，顺序 = ICS 里 VEVENT 的出现先后，
        // 而 `coursesOn` 的 KDoc 写着"按开始时间升序"。
        // 展示侧自己补了一次 sortedBy 所以用户没看见，但搜索路径会拿到乱序。
        timetable.activeDates.forEach { date ->
            val courses = timetable.coursesOn(date)
            courses.zipWithNext { a, b ->
                assertTrue(
                    "$date 的课程未按时间升序：${a.summary}@${a.startTime} 在 ${b.summary}@${b.startTime} 之前",
                    !a.start.isAfter(b.start)
                )
            }
        }
    }

    @Test
    fun `activeDates 是升序的（KDoc 承诺）`() {
        val dates = timetable.activeDates.toList()
        assertEquals(dates.sorted(), dates)
    }

    @Test
    fun `UNTIL 的 UTC 瞬时值被正确换算（跨 8 小时不能吃掉最后一节课）`() {
        // 手动构造一条最小 ICS：DTSTART 上海 14:40，UNTIL 是 UTC 的 2026-09-07T16:00Z
        // = 上海 2026-09-08 00:00。因此 09-01 那节课应当**被包含**，
        // 若解析器拿墙钟直接比大小（偏 8 小时），这条会被误判为超出 UNTIL 而丢失。
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTIMEZONE
            TZID:Asia/Shanghai
            END:VTIMEZONE
            BEGIN:VEVENT
            SUMMARY:边界课
            LOCATION:A101
            DTSTART;TZID=Asia/Shanghai:20260901T144000
            DTEND;TZID=Asia/Shanghai:20260901T154000
            RRULE:FREQ=WEEKLY;UNTIL=20260907T160000Z;INTERVAL=1
            DESCRIPTION:第6 - 6节\nA101\n某老师
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = TimetableParser.parse(ics)
        assertEquals("09-01 那一节必须保留（UTC 换算后才能正确判断）", 1, result.courses.size)
        assertEquals(LocalDate.of(2026, 9, 1), result.courses.single().date)
    }

    @Test
    fun `没有 UNTIL 的规则不会死循环`() {
        // 去掉 UNTIL：数学上无限。解析器必须有硬上限兜底，且**照常返回已产出的部分**。
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:无限课
            DTSTART;TZID=Asia/Shanghai:20260901T080000
            DTEND;TZID=Asia/Shanghai:20260901T090000
            RRULE:FREQ=WEEKLY;INTERVAL=1
            DESCRIPTION:第1 - 1节\nB202\n某老师
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = TimetableParser.parse(ics)
        // 上限是 400（MAX_OCCURRENCES_PER_RULE），既不无限也不为 0。
        assertTrue("应产出非空结果（上限兜底而非整体放弃）", result.courses.isNotEmpty())
        assertTrue("必须被上限截断", result.courses.size <= 400)
    }

    // ── 容错：一条坏数据不能毁掉整份课表 ──────────────────────────────────────

    @Test
    fun `缺 SUMMARY 的 VEVENT 被跳过而其它的照常解析`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            DTSTART;TZID=Asia/Shanghai:20260901T080000
            DTEND;TZID=Asia/Shanghai:20260901T090000
            END:VEVENT
            BEGIN:VEVENT
            SUMMARY:好课
            DTSTART;TZID=Asia/Shanghai:20260902T080000
            DTEND;TZID=Asia/Shanghai:20260902T090000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = TimetableParser.parse(ics)
        assertEquals(1, result.courses.size)
        assertEquals("好课", result.courses.single().summary)
    }

    @Test
    fun `不认识的 FREQ 被跳过而不是猜成 WEEKLY`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:日报课
            DTSTART;TZID=Asia/Shanghai:20260901T080000
            DTEND;TZID=Asia/Shanghai:20260901T090000
            RRULE:FREQ=DAILY;INTERVAL=1
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        // 若把 DAILY 悄悄当 WEEKLY，会得到一串"日期全错但没有报错"的课，
        // 比空结果更糟。这里的断言就是钉住"宁可跳过"这个选择。
        assertTrue(TimetableParser.parse(ics).courses.isEmpty())
    }

    @Test
    fun `空输入返回空课表而不是崩溃`() {
        val empty = TimetableParser.parse("")
        assertEquals(0, empty.courses.size)
        assertEquals(null, empty.firstDate)
        assertEquals(0, empty.coursesOn(LocalDate.of(2026, 10, 20)).size)
    }

    // ── 素材里的两处"意外"，写 UI 前必须知道 ──────────────────────────────────

    @Test
    fun `转义逗号被还原（多教师与多实验室）`() {
        // 素材里有 `许亮\,钟灵` 这种多教师写法。反转义后应当是一个含中文逗号的字符串。
        val multiTeacher = timetable.courses.firstOrNull { it.teacher.contains("许亮") }
        assertNotNull("素材里应有许亮老师的课", multiTeacher)
        assertTrue(
            "多教师应被还原成 '许亮,钟灵' 而不是保留反斜杠",
            multiTeacher!!.teacher.contains("钟灵") && !multiTeacher.teacher.contains("\\")
        )
    }

    @Test
    fun `DESCRIPTION 里没有残留字面量反斜杠 n`() {
        // 坑 1（HANDOVER 5.24）：DESCRIPTION 里的 \n 是**两个字符**，不是换行。
        // 反转义要单次左到右扫描；链式 replace 会把 `\\n` 二次处理成"换行 + n"。
        val suspicious = timetable.courses.filter { it.description.contains("\\n") }
        assertTrue(
            "不应有课程描述残留字面量 \\n，发现 ${suspicious.size} 条：${suspicious.map { it.summary }}",
            suspicious.isEmpty()
        )
    }

    @Test
    fun `时长不是固定 45 分钟（素材里有 7 种时长）`() {
        val durations = timetable.courses.map { it.duration.toMinutes() }.toSet()
        assertTrue(
            "素材实测有 7 种时长，若只剩 1 种说明解析退化了：$durations",
            durations.size >= 7
        )
        assertTrue("应存在非 45 分钟的课", durations.any { it != 45L })
    }

    @Test
    fun `存在非整点的开始时刻（10-25 与 14-40）`() {
        val starts = timetable.courses.map { it.startTime }.toSet()
        assertTrue("应有 10:25 这种非整点", starts.contains(LocalTime.of(10, 25)))
        assertTrue("应有 14:40 这种非整点", starts.contains(LocalTime.of(14, 40)))
    }

    // ── 查询能力 ──────────────────────────────────────────────────────────────

    @Test
    fun `coursesOn 对没有课的日期返回空列表而不是 null`() {
        val result = timetable.coursesOn(LocalDate.of(2026, 10, 24))
        assertNotNull(result)
        assertEquals(0, result.size)
    }

    @Test
    fun `weekOf 总是返回 7 组`() {
        val week = timetable.weekOf(LocalDate.of(2026, 10, 19))
        assertEquals(7, week.size)
    }

    @Test
    fun `sortedCourses 严格按开始时间升序`() {
        val sorted = timetable.sortedCourses
        assertEquals(timetable.courses.size, sorted.size)
        sorted.zipWithNext { a, b ->
            assertTrue("排序被破坏：${a.start} 在 ${b.start} 之后", !a.start.isAfter(b.start))
        }
    }
}
