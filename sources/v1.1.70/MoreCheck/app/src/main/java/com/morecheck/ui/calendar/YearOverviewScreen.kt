package com.morecheck.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.util.DayState
import java.time.LocalDate
import java.time.YearMonth

/** 星期表头，周一为一周第一天，与日历主页的规则保持一致。 */
private val WEEKDAY_HEADS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 一行放几个月（需求：改为**两个月份一行**）。
 *
 * 原来是一行三个。改成两列之后每个月的可用宽度从屏宽 1/3 涨到约 1/2，
 * 7 列日期格也随之变宽，于是日期字号与格子都能放大一档、看起来不再挤。
 */
private const val MONTHS_PER_ROW = 2

/** 两个月块之间的横向间距。两列变宽后需要比原来更明确的分栏感。 */
private val MONTH_COLUMN_GAP = 12.dp

/** 每行月份之间的纵向间距（含块内最后一行日期与下一行标题之间的呼吸）。 */
private val MONTH_ROW_GAP = 14.dp

/**
 * 全年打卡总览：12 个月块按两列排布，一屏看清整年。
 *
 * 本页只读——需求没有要求在这里打卡或点某天，所以日期格一律不可点。
 *
 * ## v1.1.64 的两处调整（需求）
 *
 * 1. **改为两个月份一行**（原三个）。三列时每格不到 20dp，日期数字只能压到 12sp；
 *    两列后每格约 26~34dp，可以把字号提到 13sp、格子放宽到 34dp，可读性明显变好。
 * 2. **字体与层次优化**：月份标题、星期表头、日期数字按「标题 → 表头 → 数据」三级
 *    重新定字号与字重，见 [MonthBlock] 与 [YearDayCell] 里的注释。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearOverviewScreen(
    onBack: () -> Unit,
    viewModel: CalendarViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val recordsByDate by viewModel.recordsByDate.collectAsStateWithLifecycle()
    val today by DayState.today.collectAsStateWithLifecycle()
    val year = today.year

    // 12 个月 × 最多 42 格 = 最多 504 次查询，逐格查 map 也能跑，
    // 但每次都要把 LocalDate 转成 "yyyy-MM-dd" 字符串再哈希，属于纯浪费；
    // 这里预先摊平成 Set，格子内只做一次 contains。
    //
    // 用 runCatching 而不是直接 LocalDate.parse：date 键虽然由 LocalDate.toString() 生成，
    // 但备份恢复（CheckDao.insertRecords）会把外部 JSON 里的日期原样写进库，
    // 一个畸形字符串不应该让整页崩掉，解析不出来的键直接忽略即可。
    //
    // 判定条件用 byTask.isNotEmpty()（而非判断值非 0）：checkedAt 是时间戳，
    // 值本身永远"为真"，是否存在记录只看这张 map 有没有条目。
    val checkedDates = remember(recordsByDate, year) {
        recordsByDate.asSequence()
            .filter { (_, byTask) -> byTask.isNotEmpty() }
            .mapNotNull { (key, _) -> runCatching { LocalDate.parse(key) }.getOrNull() }
            .filter { it.year == year }
            .toSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("全年打卡") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        // 12 个块、高度还不固定，用 LazyVerticalGrid 反而要处理嵌套滚动的高度测量；
        // 固定的一层 Column + 每行 Row + weight(1f) 等分既简单又不会出现滚动冲突。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            (0 until 12).chunked(MONTHS_PER_ROW).forEachIndexed { rowIndex, rowMonths ->
                // 行与行之间显式留白：两列布局下每个块的周数不同（4~6 行），
                // 没有间距时相邻两个月的日期会连成一片、看不出分栏。
                if (rowIndex > 0) Spacer(Modifier.height(MONTH_ROW_GAP))
                Row(Modifier.fillMaxWidth()) {
                    rowMonths.forEachIndexed { columnIndex, index ->
                        if (columnIndex > 0) Spacer(Modifier.width(MONTH_COLUMN_GAP))
                        MonthBlock(
                            month = YearMonth.of(year, index + 1),
                            today = today,
                            checkedDates = checkedDates,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun MonthBlock(
    month: YearMonth,
    today: LocalDate,
    checkedDates: Set<LocalDate>,
    modifier: Modifier = Modifier
) {
    val isCurrentMonth = month.year == today.year && month.monthValue == today.monthValue
    val firstDay = month.atDay(1)
    val daysInMonth = month.lengthOfMonth()
    val leadingBlank = firstDay.dayOfWeek.value - 1 // Monday == 1
    val totalCells = leadingBlank + daysInMonth
    val rows = (totalCells + 6) / 7

    Column(modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        Text(
            // 不带空格，与参考图一致。
            "${month.monthValue}月",
            // ## 字号层次（本轮优化）
            //
            // 三级信息按「月份标题 20sp 粗体 → 星期表头 11sp 常规 → 日期 13sp」定档：
            // - 月份标题 20sp（原 19sp）：两列布局下每块更宽，标题需要更有分量才压得住；
            //   用 `letterSpacing = 1.sp` 给「12月」这种两字符标题一点字距，横向不局促。
            // - 星期表头 11sp 且 `letterSpacing = 0.sp`：单个汉字加字距反而会与下面的
            //   日期数字对不齐（7 列是靠 weight 等分的），去掉更整齐。
            // - 日期 13sp（原 12sp）：格子变大了，字号也该跟上。
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 20.sp,
                letterSpacing = 1.sp
            ),
            fontWeight = FontWeight.Bold,
            color = if (isCurrentMonth) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth()) {
            WEEKDAY_HEADS.forEach { label ->
                Text(
                    label,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val index = row * 7 + col
                    if (index < leadingBlank || index >= totalCells) {
                        // 首行前与末行后的空位，只占宽度不画内容。
                        Spacer(Modifier.weight(1f))
                    } else {
                        val date = month.atDay(index - leadingBlank + 1)
                        YearDayCell(
                            date = date,
                            isToday = date == today,
                            hasCheckIn = date in checkedDates,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一年总览里的单个日期格。
 *
 * 视觉规则只有三条，且「今天」与「有打卡」走不同视觉通道（实底方块 / 下方短横线），
 * 所以同一天两者都成立时可以叠加显示，而不是互相覆盖。
 */
@Composable
private fun YearDayCell(
    date: LocalDate,
    isToday: Boolean,
    hasCheckIn: Boolean,
    modifier: Modifier = Modifier
) {
    // 短横线要求「约为字宽的 60%」：固定 dp 会让 1 位数显得过长、2 位数显得过短，
    // 所以在文字测量完成后按真实字宽取比例。
    var underlineWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Column(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                // 上限 34dp（原 30dp）：两列布局下每格约 26~34dp，把上限放宽一档，
                // 宽屏上格子能真正长大，日期不再缩在格子中间一小块。
                // 先卡上限再 fillMaxWidth()+aspectRatio(1f)，
                // 就能在窄屏自动缩小、宽屏不超过 34dp，且始终是正方形。
                .sizeIn(maxWidth = 34.dp, maxHeight = 34.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                // 圆角用百分比（24% ≈ 34dp 时的 8dp）：窄屏缩小后仍是圆角方块，
                // 不会因为半径大于半边长而退化成正圆。
                .clip(RoundedCornerShape(percent = 24))
                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                date.dayOfMonth.toString(),
                // 日期字号 12sp → 13sp：与放大后的格子配套。
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                fontWeight = when {
                    isToday -> FontWeight.Bold
                    // 「有打卡」的日期同时用**颜色 + 字重**表达（原来只有颜色）：
                    // 一屏 365 个数字，单靠颜色在小字号下不够醒目，加半档字重后
                    // 「哪些天打过卡」扫一眼就能看出来。
                    hasCheckIn -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                color = when {
                    isToday -> MaterialTheme.colorScheme.onPrimary
                    hasCheckIn -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                onTextLayout = { layout ->
                    underlineWidth = with(density) { (layout.size.width * 0.6f).toDp() }
                }
            )
        }
        if (hasCheckIn) {
            Spacer(Modifier.height(1.dp))
            Box(
                Modifier
                    .width(underlineWidth)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
