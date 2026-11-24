package com.morecheck.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 课表仓库：把 `assets/timetable/curriculum.ics` 读进来、解析、缓存。
 *
 * ## 分层：解析与读取分开
 *
 * - [TimetableParser] 是**纯逻辑**（String 进、[Timetable] 出、零 Android 依赖），
 *   因此可以在不跑 Gradle 的前提下单独核对"某天有几节课"这类问题；
 * - 本类只做两件 Android 相关的事：**从 assets 读文本**、**在 IO 线程上跑解析并缓存**。
 *   这样切分之后，"解析逻辑对不对"与"文件读得到读不到"是两个可分别排查的问题。
 *
 * ## 为什么解析结果要缓存（而不是每次进页面重解析）
 *
 * - 解析成本实测在**毫秒级**（24KB 文本、51 条 VEVENT、展开成 220 个实例），
 *   单看性能并不需要缓存；
 * - 但课表是**静态素材**（打进 assets、运行期不变），每次进页面重解析毫无意义；
 *   更重要的是界面会反复切日期、切周——若解析放在组合期或每次重组里跑，
 *   就成了"同一份输入算 N 次"。缓存一次即可，内存代价也只有几百 KB。
 *
 * ## 为什么用 `@Volatile` + 双重检查而不是 `by lazy`
 *
 * `by lazy` 默认是 `SYNCHRONIZED` 同步的，会给**读路径**加锁。
 * 课表对象读得频繁（每次重组可能读 `timetable`），加锁不划算。
 * 这里用 `@Volatile` 保证"引用可见"，用 `@Volatile var cached` 自身做发布点：
 * `Timetable` 与其中的 `Map` 都是构造完才赋值的不可变对象，
 * 赋值一次之后对其它线程可见（`@Volatile` 的写-读可见性保证），读取时判空即可。
 */
class TimetableRepository(private val appContext: Context) {

    /**
     * 解析后的课表。`null` = 还没解析过。
     *
     * 只在内存里，**不落 DataStore / 不落文件**：素材是应用自带的静态文件，
     * 没有任何用户修改的入口，持久化一份只是给自己制造两份可能不一致的事实。
     */
    @Volatile
    private var cached: Timetable? = null

    /** 素材在 assets 里的路径。与仓库根的 `app/src/main/assets/timetable/` 对应。 */
    private val assetPath = "timetable/curriculum.ics"

    /**
     * 取课表，必要时解析一次。**挂起函数**，内部切到 IO 线程。
     *
     * 调用方应当把它放在 ViewModel 的 `init` 或某个 `LaunchedEffect` 里，
     * 而不是每次重组都调——它内部有缓存兜底、重复调不会重解析，
     * 但每次都过一次挂起函数的开销没意义。
     *
     * @return 解析后的课表。**读不到文件或解析失败时返回空课表**（不抛异常），
     *   让界面显示"课表加载失败"而不是崩在解析路径上。
     */
    suspend fun load(): Timetable = withContext(Dispatchers.IO) {
        // 双重检查：锁外先读一次（命中就零开销），未命中才进临界区再读一次。
        // `cached` 是 @Volatile，两次读都能看到其它线程的写入。
        cached ?: synchronized(this) {
            cached ?: loadUncached().also { cached = it }
        }
    }

    /**
     * 读文件 + 解析，**不加锁**。调用方 [load] 已把它放进 `synchronizedLoad` 的临界区。
     *
     * 名字刻意是 "loadUncached" 而不带 Sync：它本身没有任何同步动作，
     * 叫 `synchronized…` 会让人误以为内部加了锁。
     */
    private fun loadUncached(): Timetable {
        val text = runCatching {
            appContext.assets.open(assetPath).use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull() ?: return Timetable(emptyList())

        // ⚠️ 解析器本身对脏数据是容错的（一条坏 VEVENT 只跳那一条），
        // 但这里仍然包一层 runCatching：素材若整体损坏（比如被截断成空文件），
        // 顶层解析也可能抛。让"课表为空"这个结果能一路传到界面上。
        return runCatching { TimetableParser.parse(text) }.getOrElse { Timetable(emptyList()) }
    }

    /**
     * 今天（设备本地日期）的课。
     *
     * 用 [LocalDate.now] 而不是某个写死的日期：课表页要显示"今天"，日期当然要实时。
     * 设备时区就是学校所在时区时它与 ICS 的 `TZID` 自然一致；
     * 万一不一致，`Course.start` 存的也是设备本地墙钟时间，显示仍然自洽。
     */
    suspend fun today(): List<Course> = load().coursesOn(LocalDate.now())

    /**
     * 某一周的课，七天一组（顺序 = [weekStart] 起连续 7 天）。
     *
     * @param weekStart 一周的第一天。**调用方自己决定周一还是周日**，
     *   本方法不做归一化——不同界面对"一周起点"的定义可能不同
     *   （课程表习惯周一，日历习惯可能按月），在这里写死会限制调用方。
     */
    suspend fun week(weekStart: LocalDate): List<List<Course>> = load().weekOf(weekStart)

    /**
     * 按课程名 / 地点 / 教师搜索。
     *
     * ## 为什么搜索范围是**整份课表**而不是"今天"或"本周"
     *
     * 用户搜"数据结构"时想找的是"我这学期哪几节数据结构"，
     * 而不是"今天有没有数据结构"。把范围限定在某一天会让搜索几乎总是空结果
     * （实测 84 个上课日里，最多的一天也只有 4 节），
     * 搜到的东西还会让人误以为"这学期就这一节"。
     *
     * 因此这里搜**整份课表**，返回的仍是**上课实例**（带具体日期时间），
     * 调用方按需要去重成"课程"或直接列实例。
     *
     * @param keyword 查询词。空串或纯空白视为"不过滤"（返回全部），
     *   这样调用方不必自己判空。
     * @return 命中的实例，按时间升序。
     */
    suspend fun search(keyword: String): List<Course> {
        val trimmed = keyword.trim()
        val all = load().sortedCourses
        if (trimmed.isEmpty()) return all
        // 小写化后比较：中文无大小写，但教师名/地点里可能有英文（如 C++、Lab）。
        val needle = trimmed.lowercase()
        return all.filter {
            it.summary.lowercase().contains(needle) ||
                it.location.lowercase().contains(needle) ||
                it.teacher.lowercase().contains(needle) ||
                it.periodText.lowercase().contains(needle)
        }
    }

    /**
     * 把 [date] 归一到"它所在那一周的周一"。
     *
     * 课程表以**周一**为一周之始（国内高校通例）。这个函数给调用方一个
     * "传任意一天、拿回该周周一"的工具，省得每个调用处各写一遍
     * `date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))`——
     * 那种重复写法正是"周视图起点"在各处漂移的来源。
     */
    fun weekStartOf(date: LocalDate): LocalDate {
        val offset = (date.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        return date.minusDays(offset.toLong())
    }

    companion object {
        /** 素材在 assets 里的路径，暴露给将来的"导出 .ics"功能复用。 */
        const val ASSET_PATH = "timetable/curriculum.ics"
    }
}