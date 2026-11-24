package com.morecheck.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ICS（RFC 5545）课表解析器：把 `assets/timetable/curriculum.ics` 的文本
 * 变成「某天有哪些课」的纯逻辑。
 *
 * ## 为什么手写而不引三方库
 *
 * 本项目零新增依赖是既有纪律（与「AI 对话只用 HttpURLConnection」同一条取舍）。
 * 何况这份素材只用到 ICS 的一个极小子集：`VEVENT` 的 `SUMMARY` / `DTSTART` /
 * `DTEND` / `RRULE` / `LOCATION` / `DESCRIPTION`，重复规则只有
 * `FREQ=WEEKLY;INTERVAL=n;UNTIL=<UTC 瞬时值>`。为这 6 个属性引一个几百 KB 的
 * icalendar 库不划算。
 *
 * ## 纯逻辑、不碰 Android
 *
 * 输入是 [String]，输出是 [Course] / [Timetable] 等纯 Kotlin 对象，**不引用任何
 * `android.*`**。因此可以脱离 Context、脱离 Gradle 直接验证"某天有几节课"
 * 这类问题（本文件末尾的「自检」一节给了不依赖模拟器的核对方法）。
 *
 * ## ⚠️ 素材里真实存在的三个坑（本文件逐处标注了对应位置）
 *
 * 1. **RRULE 的 `UNTIL` 是 UTC 瞬时值，而 `DTSTART` 是本地墙钟时间。**
 *    两者直接比大小会差 8 小时。见 [RecurrenceRule.untilInstant] 的详细说明。
 * 2. **`DESCRIPTION` 里的 `\n` 是字面量两字符**（反斜杠 + `n`），不是真换行。
 *    见 [unescapeText] 与 [Course.periodText] 的推导。
 * 3. **`VALARM` 块里也有 `DESCRIPTION`**，内容是 `课程名@地点\n` 这种**闹钟文案**。
 *    写"在 VEVENT 里找 DESCRIPTION"会拿到它。见 [parse] 里组件栈的处理。
 *
 * 另外素材里还有两处**不在任务书里、但必须处理**的细节：
 * `\,`（RFC 5545 的逗号转义，见 [unescapeText]）与 3 条**只有 2 行**的
 * `DESCRIPTION`（缺教师，见 [Course.teacher]）。
 */

// ─────────────────────────────────────────────────────────────────────────────
// 模型
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一门课的**某个具体上课实例**（RRULE 展开后的一节）。
 *
 * ## 为什么 `start` / `end` 是 [LocalDateTime] 而不是 `Instant` / `ZonedDateTime`
 *
 * 课表是**墙上时间**语义：14:40 的课就是本地 14:40，与用户在哪台设备、当前
 * UTC 偏移是多少无关。用 `Instant` 存会在换时区/夏令时后把"14:40"变成"13:40"，
 * 而用户要看到的恰恰是"14:40"。
 * 时区换算只在**解析 RRULE 的 UNTIL 边界**这一处需要（见 [RecurrenceRule]），
 * 换算完就丢掉，不进入模型。
 *
 * @property summary 课程名（`SUMMARY`）。非空——缺它的 VEVENT 直接跳过（见 [parse]）。
 * @property location 上课地点（`LOCATION`）。可能为空串。
 * @property description 原始 `DESCRIPTION` 全文（已反转义成真换行）。
 * @property periodText 节次原文（DESCRIPTION 第一行，如 `第6 - 6节`）。
 *   DESCRIPTION 结构异常时为空串。
 * @property teacher 教师（DESCRIPTION 第三行）。**可能为空**——素材里有 3 条
 *   `DESCRIPTION` 只有两行（`第X - Y节` + 地点），见下方说明。
 * @property start 本节课的开始本地时间。
 * @property end 本节课的结束本地时间。由 `DTEND - DTSTART` 的**时长**推出，
 *   保证每一节的时长与素材一致（见 [parse] 里加时长的注释）。
 */
data class Course(
    val summary: String,
    val location: String,
    val description: String,
    val periodText: String,
    val teacher: String,
    val start: LocalDateTime,
    val end: LocalDateTime
) {
    /** 上课日期（[start] 的日期部分）。周视图按它分列。 */
    val date: LocalDate get() = start.toLocalDate()

    /** 星期几（[DayOfWeek]，即 `MONDAY` … `SUNDAY`）。 */
    val dayOfWeek: DayOfWeek get() = start.dayOfWeek

    /** 开始时间（`HH:mm`），界面上显示用。 */
    val startTime: LocalTime get() = start.toLocalTime()

    /** 结束时间（`HH:mm`）。直接由 [end] 取，不做 `start + 时长` 的二次推导。 */
    val endTime: LocalTime get() = end.toLocalTime()

    /** 本节课的时长。[start] 与 [end] 已包含它，这里只是给界面一个现成的值。 */
    val duration: Duration get() = Duration.between(start, end)
}

/**
 * RRULE 的最小展开结果：一条重复规则的"第几周、到哪为止"。
 *
 * ## ⚠️ 这里是本文件最重要的一段：`UNTIL` 必须是 UTC 瞬时值
 *
 * 素材里长这样：
 * ```
 * DTSTART;TZID=Asia/Shanghai:20260901T144000     ← 本地墙钟时间 2026-09-01 14:40
 * RRULE:FREQ=WEEKLY;UNTIL=20260907T160000Z;INTERVAL=1
 *                                                 ↑ 结尾那个 Z = UTC
 * ```
 *
 * - `DTSTART` 是**浮动本地时间**（配 `TZID` 参数），在 `Asia/Shanghai` 时区下
 *   就是本地 14:40。
 * - `UNTIL=20260907T160000Z` 结尾的 **`Z` 表示 UTC**，它指向的瞬时值换算回上海
 *   墙钟是 **2026-09-08 00:00**（周一→周二那一周的周一零点）。
 *
 * **因此比较时两边必须在同一个尺度上。** 本类的做法是：
 * `DTSTART` 走 [RecurrenceRule.zone] 变成 `ZonedDateTime` → `toInstant()`；
 * `UNTIL` 走 `LocalDateTime.parse(..., UTC)` 变成 `Instant`；
 * 两者都成了**时间轴上的瞬时值**再比大小。
 *
 * ### 如果不做换算会怎样（实测：本素材下**恰好**不出错，但那是巧合）
 *
 * 直接拿 `DTSTART` 的墙钟（2026-09-01 14:40）与 `UNTIL` 的墙钟（2026-09-07 16:00）
 * 比，会**偏松 8 小时**。而本素材的每一条 `UNTIL` 换算回上海都恰好是
 * 「该课最后一次上课那天的**次日**零点」，比如上例是 09-08 00:00，
 * 而最后一次课在 09-01 14:40 —— 两者相差 9 小时 20 分，**远大于 8 小时的误差**。
 * 所以这份素材下"换算"与"不换算"都得到同样的 220 个实例。
 *
 * ⚠️ **但这是巧合，不能依赖**：换一份 ICS（比如别的学校导出规则、或者客户端
 * 把 `UNTIL` 写成"最后一次课的当天 23:59"），8 小时的误差就会立刻吃掉或多出
 * 最后一节课。本类仍然做换算，成本是一次 `toInstant()`，收益是换任何素材都对。
 * 这一点在下面的 KDoc 与交付说明里都记着，别把"现在测不出差别"当成"可以不换算"。
 *
 * @property frequency 重复频率。本项目只支持 [Frequency.WEEKLY]；
 *   其它值会让 [TimetableParser.parse] 跳过那条 VEVENT（见那里的说明）。
 * @property interval 间隔几周。素材里全是 1。
 * @property untilInstant 重复截止的**瞬时值**（UTC 尺度）。
 *   `null` 表示无 `UNTIL`（此时由 [TimetableParser.parse] 的展开上限兜底）。
 * @property zone [DTSTART] 所属的时区。由 `TZID` 参数或文件头 `VTIMEZONE` 解析得出。
 */
data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int,
    val untilInstant: Instant?,
    val zone: ZoneId
) {
    /** RRULE 的 `FREQ` 取值。本项目只实现了 [WEEKLY]。 */
    enum class Frequency { WEEKLY, UNKNOWN;

        companion object {
            fun fromIcs(raw: String?): Frequency = when (raw?.trim()?.uppercase()) {
                "WEEKLY" -> WEEKLY
                // ⚠️ 刻意**不**把 DAILY / MONTHLY 悄悄映射成 WEEKLY：
                // 那会让课程表的日期全错，而且错得没有任何提示。
                // 正确做法是当作"不认识"、跳过那条 VEVENT 并继续（见 [TimetableParser.parse]）。
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 一份课表的全部内容：所有 [Course] 实例 + 常用查询。
 *
 * ## 为什么查询按 [LocalDate] 而不是按"第几周"
 *
 * 界面上要做的是"今天有什么课"与"本周有什么课"，两者都能归结成"某一天有什么课"。
 * 按日期索引一次 `map[date]` 就够，不需要再维护"第几周 → 哪些天"这一层
 * （那一层每次查询都要现算周一起止，还得处理跨年周）。真要做周视图，
 * 调用方自己用 `coursesOn` 逐日取即可（见 `TimetableScreen` 侧的做法）。
 */
class Timetable(val courses: List<Course>) {

    /**
     * 按日期分组的索引。**每一组内部已按开始时间升序**。
     *
     * 构造期建一次、而不是每次 [coursesOn] 现算：周视图要连续取 7 天，
     * 每取一天就重扫一遍 220 条是白费的功。索引不可变、构造期建好，
     * 因此多线程读取是安全的。
     *
     * ## ⚠️ v1.1.75：这里**必须显式排序**，`groupBy` 的插入序不等于时间序
     *
     * 原写法是 `courses.groupBy { it.date }`——`groupBy` 保留的是**源列表的出现顺序**，
     * 而源列表的顺序由 VEVENT 在 ICS 文本里的先后决定（跟课程时刻毫无关系）。
     * 于是 [coursesOn] 的 KDoc 写着"按开始时间升序"，实际返回的却是"按 ICS 里
     * 出现的先后"。
     *
     * 这个不一致此前**没被用户看见**，因为唯一的展示方 `TimetableScreen`
     * 在渲染时又自己 `sortedBy { it.start }` 了一遍（见该页周一视图那段）——
     * 相当于上层替下层补了漏。而搜索态那条路径（`coursesOn(selectedDate)` 直接取）
     * 就拿到了未排序的结果。
     *
     * 这类"文档承诺 A、实现是 B、靠调用方各自兜底"的缺口应该在下层修掉：
     * 让 [coursesOn] 真的兑现它的 KDoc，调用方也就不必再自己排。
     * 排序成本是一次构造期的 220 条比较，与缓存本来的目的（省重复扫描）不冲突。
     */
    private val byDate: Map<LocalDate, List<Course>> =
        courses.groupBy { it.date }
            .mapValues { (_, list) -> list.sortedBy { it.start } }

    /** 课表覆盖的第一天（含）。空课表为 `null`。 */
    val firstDate: LocalDate? = courses.minOfOrNull { it.date }

    /** 课表覆盖的最后一天（含）。空课表为 `null`。 */
    val lastDate: LocalDate? = courses.maxOfOrNull { it.date }

    /** 全部实例按时间升序（同一时刻并列时按课程名排，保证顺序稳定可测）。 */
    val sortedCourses: List<Course> = courses.sortedWith(
        compareBy({ it.start }, { it.summary })
    )

    /**
     * 上课日期的集合（**升序**、去重）。
     *
     * ⚠️ 原写法是 `byDate.keys`：`Map.keys` 的类型是 `Set`，**不保证顺序**
     * （实际是 `LinkedHashMap` 的插入序 = ICS 里首次出现的先后）。
     * KDoc 却写着"升序"。周/月视图要按顺序遍历日期时，这是潜在的错序来源。
     * 改用 `toSortedSet()` 显式兑现承诺；对 84 个日期来说成本可以忽略。
     */
    val activeDates: Set<LocalDate> = byDate.keys.toSortedSet()

    /**
     * 某一天的课，按开始时间升序。**没有课返回空列表**（不返回 null）。
     *
     * "今天没有课"是常态而非异常——学期里有大量空课日（实测 84 个上课日里
     * 有 17 天只上 1 节，且整周可能一天都没有）。让调用方每次都判 null
     * 只会把它写成 `?: emptyList()` 这种噪音。
     */
    fun coursesOn(date: LocalDate): List<Course> = byDate[date].orEmpty()

    /** [date] 那一天有没有课。 */
    fun hasCoursesOn(date: LocalDate): Boolean = byDate.containsKey(date)

    /**
     * 一周的课：从 [weekStart] 起连续 7 天，每天一组。
     *
     * 每天一组（而不是拍平成一个 List）是因为周视图**按列**渲染，
     * 一组对应一列，不需要调用方再按星期几分组。
     *
     * @param weekStart 一周的第一天。调用方自己决定用周一还是周日，
     *   本项目界面用**周一**（见 `TimetableScreen`）。
     */
    fun weekOf(weekStart: LocalDate): List<List<Course>> =
        (0L..6L).map { coursesOn(weekStart.plusDays(it)) }
}

// ─────────────────────────────────────────────────────────────────────────────
// 解析器
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ICS 文本 → [Timetable] 的解析器。
 *
 * 全程**纯函数、无 Android 依赖**：[parse] 收一个 [String]、吐一个 [Timetable]。
 */
object TimetableParser {

    /**
     * ICS 的日期时间格式 `yyyyMMdd'T'HHmmss`。
     *
     * 用固定格式而不是 `ISO_LOCAL_DATE_TIME`（它要求带 `T` 之后有秒、
     * 且允许省略秒）：ICS 用的是 `20260901T144000` 这种**紧凑无分隔**写法，
     * `ISO_LOCAL_DATE_TIME` 解析不了。
     */
    private val ICS_DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * 展开时的硬上限：最多产出多少个实例，以及最多向后迭代多少次。
     *
     * ## 为什么需要它
     *
     * 一条**没有 `UNTIL`** 的 `FREQ=WEEKLY` 规则在数学上是无穷的。
     * 本素材每条都有 `UNTIL`，但解析器不能假设"换一份 ICS 也一定都有"——
     * 缺 `UNTIL` 时若老实迭代就会死循环、把内存撑爆。
     * 上限兜住它：超过就停下并**照常返回已产出的部分**（宁可少几节，
     * 也不能让整个页面打不开）。
     */
    private const val MAX_OCCURRENCES_PER_RULE = 400

    /**
     * 解析整份 ICS。
     *
     * ## 容错原则：一条坏数据不能毁掉整份课表
     *
     * 任何一条 VEVENT 出问题（缺 `SUMMARY`、缺 `DTSTART`、时间格式不对、
     * `FREQ` 不认识、`DTEND` 早于 `DTSTART`……）都**只跳过那一条**，
     * 其余课程照常产出。本项目一贯的处理是"脏值回落而不是抛异常"
     * （见 `SettingsRepository` / `GameChromeMode.fromValue`），
     * 一份 51 条的课表因为第 37 条坏了就整页空白是不能接受的。
     *
     * @param icsText ICS 文件全文。
     * @return 展开后的课表。输入为空/全坏时返回**空课表**（不是 null）。
     */
    fun parse(icsText: String): Timetable {
        val lines = unfoldLines(icsText)
        val zones = collectTimeZones(lines)
        val courses = mutableListOf<Course>()

        for (block in splitBlocks(lines, "VEVENT")) {
            // ⚠️ 这一条 VEVENT 可能展开成**多个**实例（RRULE），
            // 也可能一个都没有（不可用而跳过）。用 `addAll` 而不是 `add`，
            // 否则只会把第一节课放进课表——那是最难发现的错。
            courses.addAll(parseEvent(block, zones))
        }
        return Timetable(courses)
    }

    /**
     * 处理 RFC 5545 的**行折行（line unfolding）**。
     *
     * 规则：一条以**空格或制表符开头**的物理行，是上一条逻辑行的**续行**；
     * 拼接时**去掉那个前导空白**（它只是折行标记，不是内容）。
     *
     * ## 为什么必须做，哪怕这份素材没有折行
     *
     * RFC 5545 规定每条逻辑行最长 75 字节，超出就要折行。DESCRIPTION 里写一段
     * 课程说明就很容易超。这份 `curriculum.ics` 的最长行只有 ~100 字节且没有折行，
     * 但**解析器不能依赖素材的现状**——换一份 ICS（甚至同一份文件被另一个客户端
     * 重新导出）就可能带折行，届时若不做还原，每条被折的行都会被当成一条独立的、
     * 语法不对的行而丢弃，表现为"部分字段莫名其妙是空的"。
     *
     * @return 还原后的逻辑行列表。
     */
    private fun unfoldLines(text: String): List<String> {
        // 兼容 CRLF 与 LF 两种换行：素材是 CRLF，但不该假设永远如此。
        val physical = text.split("\r\n", "\n", "\r")
        val result = ArrayList<String>(physical.size)
        for (raw in physical) {
            if (raw.isEmpty()) continue
            val isContinuation = raw[0] == ' ' || raw[0] == '\t'
            if (isContinuation && result.isNotEmpty()) {
                // 去掉续行的前导空白后再拼：那个空白是折行标记，不是内容。
                val last = result[result.size - 1]
                result[result.size - 1] = last + raw.substring(1)
            } else if (!isContinuation) {
                result.add(raw)
            }
            // 孤立的续行（文件开头就以空白开头）没有上一行可拼，
            // 直接丢弃——它不携带任何完整信息。
        }
        return result
    }

    /**
     * 扫描全���，收集 `VTIMEZONE` 块声明的时区：`TZID` → [ZoneId]。
     *
     * ## 为什么要读 `VTIMEZONE`
     *
     * `DTSTART;TZID=Asia/Shanghai` 里的 `Asia/Shanghai` 是一个 ** Olson 时区名**，
     * 而不是 `+0800` 这样的固定偏移。理论上应该按 `VTIMEZONE` 里声明的
     * STANDARD/DAYLIGHT 规则来换算（含历史遗留的偏移变更）。
     *
     * 但本文件**刻意**用 [ZoneId.of] 直接交给 JDK 的时区数据库处理，理由：
     * - JDK 自带完整 IANA 时区库，`Asia/Shanghai` 的历史偏移（含 1986–1991
     *   的夏令时）它都知道，比手写 VTIMEZONE 解析更准；
     * - 素材是 2026–2027 学年，`Asia/Shanghai` 恒为 +08:00 无夏令时，
     *   两条路的结果完全一致；
     * - 手写 VTIMEZONE 解析（STANDARD/DAYLIGHT/RDATE/规则串）要多写近百行，
     *   且换算精度反而不如 JDK。
     *
     * `VTIMEZONE` 里那个 `TZOFFSETTO:+0800` 仍然读出来，作为**兜底**：
     * 若遇到 JDK 不认识的时区名（例如文件里写了个自定义 TZID），
     * 至少能按那个固定偏移算，而不是整条丢掉。
     *
     * @return `TZID` 到 [ZoneId] 的映射。至少含 `Asia/Shanghai`。
     */
    private fun collectTimeZones(lines: List<String>): Map<String, ZoneId> {
        val zones = LinkedHashMap<String, ZoneId>()
        for (block in splitBlocks(lines, "VTIMEZONE")) {
            val tzid = block.firstPropertyValue("TZID")?.trim().orEmpty()
            if (tzid.isEmpty()) continue
            // 读 VTIMEZONE 里声明的固定偏移作为**兜底**：若 JDK 不认识这个 TZID
            // （例如别的客户端写了自定义时区名），至少能按那个偏移算，而不是整条丢掉。
            val offset = block.firstPropertyValue("TZOFFSETTO")
                ?.let { runCatching { ZoneOffset.of(it.trim()) }.getOrNull() }
            zones[tzid] = resolveZone(tzid, offset)
        }
        // 素材声明了 `Asia/Shanghai`；若文件里没有 VTIMEZONE 也仍给出这个默认值，
        // 因为课程表场景下它是最可能的那一个（DTSTART 上的 TZID 会覆盖它）。
        zones.putIfAbsent(DEFAULT_TZID, ZoneId.of(DEFAULT_TZID))
        return zones
    }

    /** 物料里的 `TZID` 默认值。`VTIMEZONE` 缺失时用它。 */
    private const val DEFAULT_TZID = "Asia/Shanghai"

    /**
     * 按 TZID 解析时区：优先查 JDK 的 IANA 时区库，查不到才退回 `VTIMEZONE`
     * 里声明的固定偏移。
     */
    private fun resolveZone(tzid: String, offsetFromFile: ZoneOffset?): ZoneId =
        runCatching { ZoneId.of(tzid) }.getOrElse {
            offsetFromFile ?: ZoneId.of(DEFAULT_TZID)
        }

    /**
     * 把整份行列表切成若干个具名组件块（`BEGIN:VEVENT` … `END:VEVENT`）。
     *
     * ## 为什么**必须**按块切，而不是"在 VEVENT 里找 DESCRIPTION"
     *
     * 本素材里每条 VEVENT 都嵌了一个 `VALARM` 子组件，而 **`VALARM` 里也有
     * `DESCRIPTION`**：
     * ```
     * BEGIN:VEVENT
     * ...
     * DESCRIPTION:第6 - 6节\n宿舍(大学城)\n刘栋     ← 这条是要的
     * BEGIN:VALARM
     *   ...
     *   DESCRIPTION:劳动教育@宿舍(大学城)\n          ← 这条是闹钟文案，不是课的
     * END:VALARM
     * END:VEVENT
     * ```
     * 写"扫描 VEVENT 区间、记住最后一个 DESCRIPTION"会拿到 `劳动教育@宿舍(大学城)`——
     * 于是节次变成整句 `劳动教育@宿舍(大学城)`、教师为空。
     * **更糟**的是"记住第一个"也不安全：一旦某个客户端把 VALARM 写在
     * DESCRIPTION 之前，就会拿到闹钟文案。顺序不可依赖。
     *
     * 因此这里用**组件栈**逐层匹配 `BEGIN:` / `END:`，只把**深度为 1** 的
     * （即直属 VEVENT 的）属性收进来，`VALARM` 内部的一切自动被排除。
     * 这与 ICS 的嵌套结构严格对应，也不依赖任何出现顺序。
     *
     * @param name 组件名，如 `VEVENT`。
     * @return 每个组件的属性列表（`name` / `params` / `value`）。
     */
    private fun splitBlocks(lines: List<String>, name: String): List<List<IcsProperty>> {
        val blocks = mutableListOf<List<IcsProperty>>()
        // 当前嵌套深度。`BEGIN:` 令它 +1，`END:` 令它 -1。
        // 不维护组件名栈：我们要的只是"直属属性"，用深度就够表达，
        // 而深度计数不会因为组件名大小写 / 拼写差异出错。
        var depth = 0
        var current: MutableList<IcsProperty>? = null
        // 正在收集的那个块**内部**的深度。直属属性的 depth 恰好等于它；
        // VALARM 内的属性 depth 比它大 1，因此被下面的条件天然排除。
        var collectDepth = -1

        for (line in lines) {
            if (line.startsWith("BEGIN:")) {
                depth++
                val component = line.removePrefix("BEGIN:").trim()
                if (current == null && component.equals(name, ignoreCase = true)) {
                    current = mutableListOf()
                    collectDepth = depth
                }
                continue
            }
            if (line.startsWith("END:")) {
                val component = line.removePrefix("END:").trim()
                // 只有"收尾的正是我们收集的那个组件"才落盘。
                // VALARM 的 END：component 是 VALARM、depth 也大于 collectDepth，都不匹配。
                if (current != null &&
                    component.equals(name, ignoreCase = true) &&
                    depth == collectDepth
                ) {
                    blocks.add(current)
                    current = null
                    collectDepth = -1
                }
                depth--
                continue
            }
            // 只收**直属**属性：depth 必须恰好等于 collectDepth。
            if (current != null && depth == collectDepth) {
                parseProperty(line)?.let { current.add(it) }
            }
        }
        return blocks
    }

    /**
     * 解析一条属性行：`NAME;PARAM=VALUE;PARAM2=V2:value`。
     *
     * ## ⚠️ 冒号要取**第一个**且**不在引号内**的
     *
     * 形式参数的值可以带引号（`ALTREP="cid:part1.0001@example.com"`），
     * 里面含冒号。若直接 `indexOf(':')`，会在引号内的冒号处截断，
     * 把 `DESCRIPTION` 变成 `"cid` ——值被毁掉。
     */
    private fun parseProperty(line: String): IcsProperty? {
        val colon = findUnquotedColon(line) ?: return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val segments = head.split(';')
        val name = segments.firstOrNull()?.trim().orEmpty()
        if (name.isEmpty()) return null
        val params = segments.drop(1).mapNotNull { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) null
            else segment.substring(0, eq).trim().uppercase() to
                unquote(segment.substring(eq + 1))
        }.toMap()
        return IcsProperty(name.uppercase(), params, value)
    }

    /**
     * 找第一个**不在双引号内**的冒号。找不到返回 null（那不是一条合法属性行）。
     *
     * ⚠️ 返回类型必须是 `Int?` 而不是 `Int`：本函数**确实有**返回 `null` 的路径
     * （一行里通篇是引号内内容、没有任何裸冒号）。写成 `Int` 时那句 `return null`
     * 编译不过——这也是"编译器能挡住静态自查挡不住的错误"的一个实例
     * （见 HANDOVER 5.23）。
     */
    private fun findUnquotedColon(line: String): Int? {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ':' -> if (!inQuotes) return i
            }
        }
        return null
    }

    /** 去掉参数值外层的双引号。 */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else raw

    /**
     * 把一条 VEVENT 的属性块展开成若干 [Course]。
     *
     * 返回空列表表示"这条 VEVENT 不可用，已跳过"，**不抛异常**。
     */
    private fun parseEvent(
        props: List<IcsProperty>,
        zones: Map<String, ZoneId>
    ): List<Course> {
        val summary = props.firstOrNull { it.name == "SUMMARY" }
            ?.value?.let(::unescapeText)?.trim()
        // 缺 SUMMARY 的 VEVENT 直接跳过：课程名是 UI 上唯一不可缺的字段，
        // 空名字的课程块在周视图里无法呈现，留着反而会占一格空白。
        if (summary.isNullOrEmpty()) return emptyList()

        val dtstartProp = props.firstOrNull { it.name == "DTSTART" } ?: return emptyList()
        // ⚠️ 这条 VEVENT 的时区（DTSTART 上的 `TZID` 参数）是**展开时**用的那个时区。
        // 它必须与 UNTIL 放在同一尺度上比较——见 [RecurrenceRule] 的 KDoc。
        val zone = zoneOf(dtstartProp, zones)
        val startWallClock = parseIcsDateTime(dtstartProp.value) ?: return emptyList()

        // 时长来自 DTEND - DTSTART，而不是「每节都 45 分钟」这种硬编码。
        // 素材里实测有 45 / 95 / 145 / 205 / 215 / 325 / 335 分钟七种时长
        // （实验课、连续大节、晚间连堂），硬编码必错。
        val duration = resolveDuration(props, startWallClock)

        val location = props.firstOrNull { it.name == "LOCATION" }
            ?.value?.let(::unescapeText)?.trim().orEmpty()
        val descriptionRaw = props.firstOrNull { it.name == "DESCRIPTION" }
            ?.value?.let(::unescapeText).orEmpty()

        // DESCRIPTION → 三段：节次 / 地点 / 教师。
        //
        // ⚠️ 这里必须**先反转义再按行拆**（见 [unescapeText] 的说明）：
        // 素材里 `\n` 是字面量两字符（反斜杠 + n），不是换行符。
        // 直接按 '\n'（真换行）拆会得到**整段一坨**，节次就变成
        // `第6 - 6节\n宿舍(大学城)\n刘栋` 整句 ——界面上直接显示反斜杠。
        val descLines = descriptionRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val periodText = descLines.getOrNull(0).orEmpty()
        // 地点优先取 DESCRIPTION 第二段，缺失时退回 LOCATION 属性
        // （素材里两者一致，但 LOCATION 是更规范的字段）。
        val descLocation = descLines.getOrNull(1).orEmpty()
        val effectiveLocation = descLocation.ifEmpty { location }
        // ⚠️ 教师**允许为空**：素材里有 3 条 DESCRIPTION 只有两行
        // （2 条「大学物理实验」的多教室串、1 条「一元积分学」）。
        // 那时从 LOCATION 尾部兜底抠出教师（见下方 [teacherFromLocation]）。
        val teacher = descLines.getOrNull(2)
            ?: teacherFromLocation(location).orEmpty()

        // ⚠️ v1.1.75 修掉一个真实 bug：**「有 RRULE 但解析失败」必须跳过整条 VEVENT**。
        //
        // 原写法是 `props.firstOrNull { RRULE }?.value?.let { parseRRule(it, zone) }`，
        // 于是三种情况塌缩成同一个 `rule == null`：
        //   ① 没有 RRULE      → 的确是"不重复，只有一节"（正确）
        //   ② 有 RRULE 但 FREQ 不认识（如 DAILY）→ parseRRule 返回 null
        //   ③ 有 RRULE 但 INTERVAL/UNTIL 畸形 → parseRRule 返回 null
        // 而 [parseRRule] 的 KDoc 写的是"返回 null（**调用方跳过该 VEVENT**）"，
        // [expandOccurrences] 却在 `rule == null` 时**当成不重复、产出一节**。
        // 结果就是：一条 FREQ=DAILY 的规则不会被"跳过"，而是被悄悄降级成
        // 「只在 DTSTART 那天上一节课」——正是 [RecurrenceRule.Frequency] 那段
        // KDoc 明确说不许发生的事（"宁可少显示几门课"）。
        //
        // 现在把"有没有 RRULE"与"RRULE 能不能解析"分开判断：
        // 缺 RRULE 才走单节分支；有 RRULE 但解析不出来就整条丢弃。
        // 这个 bug 是被 v1.1.75 引入的单元测试抓出来的（见 TimetableParserTest
        // 的「不认识的 FREQ 被跳过而不是猜成 WEEKLY」一条）。
        val rruleProp = props.firstOrNull { it.name == "RRULE" }
        val rule = rruleProp?.value?.let { parseRRule(it, zone) }
        // 有 RRULE 但没解析出来 → 不可用，跳过整条 VEVENT。
        if (rruleProp != null && rule == null) return emptyList()

        val occurrences = expandOccurrences(startWallClock, duration, rule)
        return occurrences.map { (s, e) ->
            Course(
                summary = summary,
                location = effectiveLocation,
                description = descriptionRaw,
                periodText = periodText,
                teacher = teacher,
                start = s,
                end = e
            )
        }
    }

    /**
     * 一节课的时长：优先 `DTEND - DTSTART`，都没有则退回 45 分钟。
     *
     * 45 分钟是**兜底默认值**（中国高校标准课时），只在素材异常时才用到。
     * 缺 `DTEND` 不抛异常——任务书明确要求"缺 DTEND 要跳过那条并继续"，
     * 但**跳过整门课**对用户更糟（一门课整天消失），所以这里退到一个合理的
     * 默认时长，让这节课仍然出现在正确的时间位置上。
     */
    private fun resolveDuration(
        props: List<IcsProperty>,
        startWallClock: LocalDateTime
    ): Duration {
        val endWallClock = props.firstOrNull { it.name == "DTEND" }
            ?.value?.let { parseIcsDateTime(it) }
        if (endWallClock != null) {
            val d = Duration.between(startWallClock, endWallClock)
            // DTEND 早于 DTSTART（导出的脏数据）时用默认值：负时长会让
            // `end < start`，周视图里块的高度与排序都会乱。
            if (!d.isNegative && !d.isZero) return d
        }
        return Duration.ofMinutes(DEFAULT_DURATION_MINUTES)
    }

    /** 素材异常时的默认课时（分钟）。中国高校标准课时 = 45 分钟。 */
    private const val DEFAULT_DURATION_MINUTES = 45L

    /**
     * 从 `LOCATION` 尾部兜底抠出教师。
     *
     * ## 为什么需要它
     *
     * 素材里有 3 条 VEVENT 的 `DESCRIPTION` 只有两行（节次 + 地点），没有第三行的教师。
     * 而它们的 `LOCATION` 是 `地点 空格 教师` 的形态，例如
     * `实4-401\,实4-403\,…\,实4-507 庞玮` ——教师就在最后一个空格之后。
     *
     * ## 为什么不用 `substringAfterLast(' ')` 就完事
     *
     * 有两种边界：
     * - `教3-205 `（一元积分学）：末尾是个**空**格，后面没有教师 →
     *   `substringAfterLast` 会得到空串，正好当"无教师"处理，正确。
     * - `实4-401\,…\,实4-507 庞玮`：分隔处只有一个真空格（逗号都被转义成 `\,`），
     *   不会误切，正确。
     *
     * @return 教师名；抠不出时返回 `null`。
     */
    private fun teacherFromLocation(location: String): String? {
        val trimmed = location.trim()
        if (trimmed.isEmpty()) return null
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace <= 0) return null
        return trimmed.substring(lastSpace + 1).trim().ifEmpty { null }
    }

    /**
     * 解析 `RRULE` 值，形如 `FREQ=WEEKLY;UNTIL=20260907T160000Z;INTERVAL=1`。
     *
     * ## ⚠️ 本素材的 RRULE 里字段顺序不固定
     *
     * 素材里既有 `FREQ=...;UNTIL=...;INTERVAL=1` 也有别的顺序出现，
     * 因此这里按 `;` 拆成键值对逐个认，**不假设任何顺序**。
     *
     * @return 解析好的规则；`FREQ` 不认识或整条格式不对时返回 `null`（调用方跳过该 VEVENT）。
     */
    private fun parseRRule(raw: String, zone: ZoneId): RecurrenceRule? {
        val parts = raw.split(';').mapNotNull { segment ->
            val eq = segment.indexOf('=')
            if (eq <= 0) null else segment.substring(0, eq).trim().uppercase() to
                segment.substring(eq + 1).trim()
        }.toMap()
        if (parts.isEmpty()) return null

        val frequency = RecurrenceRule.Frequency.fromIcs(parts["FREQ"])
        // 只支持 WEEKLY：DAILY / MONTHLY 映射成 WEEKLY 会让日期全错且无提示，
        // 那比"少显示几门课"糟得多。详见 [RecurrenceRule.Frequency] 的 KDoc。
        if (frequency != RecurrenceRule.Frequency.WEEKLY) return null

        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1

        // ⚠️ UNTIL 是 **UTC 瞬时值**：结尾的 `Z` 必须参与解析。
        // 做法是先把结尾的 `Z` 剥掉、按 UTC 解释成一个 LocalDateTime，
        // 再 `.toInstant(ZoneOffset.UTC)` 变回瞬时值。
        // 若忽略那个 `Z`、把它当成本地墙钟去比，边界会差 8 小时
        // （完整讨论见 [RecurrenceRule] 的 KDoc）。
        val untilInstant = parts["UNTIL"]?.let { parseUntilAsInstant(it, zone) }

        return RecurrenceRule(frequency, interval, untilInstant, zone)
    }

    /**
     * 把 `UNTIL` 的值解析成 [Instant]。
     *
     * 两种形态（RFC 5545 允许）：
     * - `20260907T160000Z` —— **UTC 形式**（结尾有 `Z`）。本素材全是这种。
     * - `20260907T160000` —— 本地/浮动形式（无 `Z`）。此时按 [fallbackZone] 解释。
     *
     * ⚠️ 两种形态的**语义完全不同**（前者是瞬时值、后者是墙上时间），
     * 必须靠那个 `Z` 区分，不能一律当本地解析。
     */
    private fun parseUntilAsInstant(raw: String, fallbackZone: ZoneId): Instant? {
        val value = raw.trim()
        val isUtc = value.endsWith("Z")
        val body = if (isUtc) value.dropLast(1) else value
        val wallClock = parseIcsDateTime(body) ?: return null
        return if (isUtc) {
            wallClock.toInstant(ZoneOffset.UTC)
        } else {
            wallClock.atZone(fallbackZone).toInstant()
        }
    }

    /** `UNTIL` 无 `Z`（浮动时间）时用它解释，由 [parseRRule] 传入 DTSTART 的时区。 */

    /**
     * 展开重复规则，产出该 VEVENT 的全部上课实例。
     *
     * @param startWallClock 首次上课的本地墙钟时间。
     * @param duration 每节时长（来自 `DTEND - DTSTART`）。
     * @param rule RRULE；`null` 表示"不重复，只有一节"。
     * @return `(开始, 结束)` 的列表，按时间升序。
     */
    private fun expandOccurrences(
        startWallClock: LocalDateTime,
        duration: Duration,
        rule: RecurrenceRule?
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        if (rule == null) return listOf(startWallClock to startWallClock.plus(duration))

        val step = Duration.ofDays(7L * rule.interval)
        val result = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
        var occurrence = startWallClock
        var iteration = 0

        while (iteration < MAX_OCCURRENCES_PER_RULE) {
            // ⚠️ 边界判定：把本次候选的**墙钟时间**按规则时区解释成瞬时值，
            // 与 UNTIL（已是瞬时值）比大小。两侧同一尺度，8 小时的坑就在这一步被绕开。
            val occurrenceInstant = occurrence.atZone(rule.zone).toInstant()
            val beyondUntil = rule.untilInstant?.let { occurrenceInstant.isAfter(it) } ?: false
            if (beyondUntil) break

            result.add(occurrence to occurrence.plus(duration))
            occurrence = occurrence.plus(step)
            iteration++
        }
        return result
    }

    /**
     * 解析 ICS 的 `yyyyMMdd'T'HHmmss` 时间戳。
     *
     * @return 解析成功返回 [LocalDateTime]（**墙上时间**，不带时区）；
     *   格式不认识返回 `null`（调用方跳过该 VEVENT）。
     */
    private fun parseIcsDateTime(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw.trim(), ICS_DATE_TIME) }.getOrNull()

    /**
     * RFC 5545 的 TEXT 反转义：`\\` `\;` `\,` `\N`/`\n` → 真字符。
     *
     * ## ⚠️ 为什么必须先反转义再按行拆
     *
     * 素材的 `DESCRIPTION` 里写的是 `第6 - 6节\n宿舍(大学城)\n刘栋`，
     * 这里的 `\n` 是**字面量两个字符**（反斜杠 + `n`），文件里没有真的换行符。
     * 若不反转义就 `split('\n')`，得到的是**只有一段**的字符串，
     * 节次就变成整句 `第6 - 6节\n宿舍(大学城)\n刘栋`——
     * 界面上会直接显示反斜杠，教师也永远是空。
     *
     * ## 为什么 `\\` 必须**先于**其它替换处理
     *
     * 若先把 `\n` 换成换行符，那么原本表示"一个反斜杠"的 `\\n`
     * （反斜杠 + 反斜杠 + n）会被二次处理、变成换行 + n，顺序反了就会出错。
     * 正确做法是**从左到右扫描一次**，每遇一个反斜杠就吃掉它和后一个字符。
     *
     * ## 为什么 `\,` 也必须处理（任务书里没提，但素材里真实存在）
     *
     * 多教师 / 多教室的字段会用逗号分隔，而逗号在 ICS 的 TEXT 里**必须转义**，
     * 否则会被解析器当成"属性分隔符之外的语义分隔"。素材实测：
     * - `教2-218 许亮\,钟灵`（两位教师）
     * - `实4-401\,实4-403\,实4-407\,…\,实4-507 庞玮`（七个实验室）
     *
     * 不处理的话，界面上会原样显示 `实4-401\,实4-403\,…`，很难看。
     * 本方法把 `\,` 还原成 `,`（保留分隔语义，但不让用户看到反斜杠）。
     *
     * ⚠️ 反转义之后**再**按行拆：顺序反了会把"本该是换行的位置"留在字符串里。
     */
    private fun unescapeText(raw: String): String {
        if ('\\' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\' -> out.append('\\')
                    ',' -> out.append(',')
                    ';' -> out.append(';')
                    // 其它转义序列（RFC 5545 里少见）：保守地**只保留字符本身**，
                    // 丢掉反斜杠，避免把未知序列原样显示给用户。
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** 取 `DTSTART` 这类属性的时区：优先它自己的 `TZID` 参数，其次全局默认。 */
    private fun zoneOf(prop: IcsProperty, zones: Map<String, ZoneId>): ZoneId {
        val tzid = prop.params["TZID"] ?: return zones[DEFAULT_TZID] ?: ZoneId.of(DEFAULT_TZID)
        return zones[tzid] ?: resolveZone(tzid, null)
    }

    /** 一条已解析的 ICS 属性。 */
    private data class IcsProperty(
        val name: String,
        val params: Map<String, String>,
        val value: String
    )

    /** 一组属性的辅助扩展：取第一个指定名字的值。 */
    private fun List<IcsProperty>.firstPropertyValue(name: String): String? =
        firstOrNull { it.name == name }?.value
}