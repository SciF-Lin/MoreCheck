package com.morecheck.ui.records

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.data.SettingsRepository
import com.morecheck.data.TimerSession
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.util.DayState
import com.morecheck.util.fullLabelWith
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private fun formatTimerDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

/**
 * 「打卡记录」二级页：标题栏 + 返回箭头 + 正文。
 *
 * 本轮改造把本页拆成**外壳**与**正文**两半（正文见 [RecordsContent]）：
 * 主页标题栏新增的「记录」面板要内联显示同一份内容（热力图 + 时间线），
 * 而那里既不该有独立标题栏、也不该有返回箭头——它是主页的一个标签，不是二级页。
 *
 * 拆分的取舍：外壳只负责 Scaffold / TopAppBar / 返回，正文只负责数据与排版。
 * 入口（统计页标题栏的「记录」、我的页的「打卡记录」、各「+」菜单）**照旧走本页**，
 * 路由与跳转一个都没动，因此这次拆分对外部调用方是完全透明的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    onBack: () -> Unit,
    viewModel: RecordsViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val heatmap by viewModel.heatmap.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val timerSessions by viewModel.timerSessions.collectAsStateWithLifecycle()
    val today by DayState.today.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("打卡记录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        // 把外壳的 innerPadding 转成边距交给正文：正文自己补 fillMaxSize()，
        // 因此这里仍然"铺满扣除标题栏后的剩余空间"。
        RecordsContent(
            heatmap = heatmap,
            timeline = timeline,
            today = today,
            timerSessions = timerSessions,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * 记录页正文：打卡热力图 + 历史时间线。**无状态**（不持有 ViewModel、不订阅任何流）。
 *
 * 两处调用点共用同一个实现：
 * - 二级页 [RecordsScreen]（套在 Scaffold 里，带标题栏与返回箭头）；
 * - 主页标题栏的「记录」标签（内联铺在主页 Scaffold 的内容区里，无标题栏、无返回箭头）。
 *
 * 可见性取舍：`internal` 而不是 `private`——正文要被另一个包（`ui.home`）复用；
 * 也不是 `public`——它只是页面内部的排版件，没有对外承诺。
 *
 * ## 为什么保持"数据由调用方传进来"而不在这里 `viewModel()`
 *
 * 那样写能让调用点更短（只给一个 Modifier），但会把这个可组合函数变成"自带数据源"的
 * 半页面：它的重组范围、生命周期、以及在 `LazyColumn` 里的实例身份都变得不再由调用方掌握。
 * 现在这样两个调用点各自订阅同一份 [RecordsViewModel] 暴露的状态，正文只认三个入参，
 * 与拆分前的写法**逐字一致**——拆分只动了"谁来 Scaffold"，没动渲染逻辑。
 *
 * ## ⚠️ 「日期显示格式」为什么是第 4 个入参、而不是在这里自己订阅（v1.1.74 需求 8）
 *
 * 这是**唯一一个**本组件自己订阅的设置，理由：它有两个调用方
 * （二级页 `RecordsScreen` 与主页的「记录」标签），若由调用方传入就要改**两处**调用点；
 * 而这个设置与 `heatmap` / `timeline` / `today` 那三个**数据入参**性质不同——
 * 它是全局偏好，不是本组件的数据。所以就地订阅，`[RecordsContent]` 对它保持"无感知"。
 *
 * 这不违反上面「不自带数据源」那条：那条说的是 **ViewModel 数据**（决定实例身份与
 * 重组范围），而这里读的是一个布尔/枚举偏好，不引入任何新的数据生命周期。
 */
@Composable
internal fun RecordsContent(
    heatmap: Map<LocalDate, Float>,
    timeline: List<TimelineDay>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    timerSessions: List<TimerSession> = emptyList()
) {
    // 「日期显示格式」的生效值：时间线里每一天的标题用它。默认 CN_FULL（= 改造前观感）。
    val appContext = LocalContext.current.applicationContext
    val developerOptions = remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
    val dateFormat by developerOptions.dateFormatSwitch
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DateDisplayFormat.CN_FULL)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("打卡热力图", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "颜色越深，表示当天完成的任务越多",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (heatmap.isEmpty()) {
                        Text("还没有数据，先去打个卡吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Heatmap(data = heatmap, today = today)
                        Spacer(Modifier.height(10.dp))
                        HeatmapLegend(MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (timerSessions.isNotEmpty()) {
            item(key = "timer-sessions") {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("计时记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "倒计时走完一轮记一条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            timerSessions.take(30).forEach { session ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = Instant.ofEpochMilli(session.startedAt)
                                            .atZone(ZoneId.systemDefault())
                                            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = formatTimerDuration(session.durationMs),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (timeline.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有打卡记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            timeline.forEach { day ->
                item(key = "d_${day.date}") {
                    Text(
                        // ⚠️ v1.1.74 需求 8：跟随「日期显示格式」开关。
                        day.date.fullLabelWith(dateFormat),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                day.entries.forEach { entry ->
                    item(key = "e_${day.date}_${entry.task.id}") {
                        TimelineRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun Heatmap(data: Map<LocalDate, Float>, today: LocalDate) {
    val earliest = data.keys.minOrNull() ?: today
    // 周一作为每列的第一天。
    val earliestWeek = earliest.minusDays((earliest.dayOfWeek.value - 1).toLong())
    val todayWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())

    val cell = 13.dp
    val gap = 3.dp
    /** 左侧星期栏（20dp 标签 + 4dp 间距），换行宽度要扣掉。 */
    val gutter = 24.dp

    BoxWithConstraints {
        // 2026-10-30 修：原来 weeks 只按「最早一条记录 → 今天」算。
        // 新装用户只有两三周数据时，整块热力图就画成 5 列贴在卡片左边，
        // 右边留一大片空白（模拟器实测：卡片宽 360dp，网格只占 90dp）。
        // 改成「至少铺满可视宽度」：可视宽度放得下几周就往前补几周，
        // 数据跨度比可视宽度更长时仍然横向滚动（此时才真的需要滚）。
        val perWeek = cell + gap
        val fitWeeks = (((maxWidth - gutter + gap) / perWeek).toInt()).coerceIn(2, 53)
        val coverageStart = todayWeek.minusDays(((fitWeeks - 1) * 7).toLong())
        val start = minOf(earliestWeek, coverageStart)
        val weeks = ((ChronoUnit.DAYS.between(start, todayWeek) / 7).toInt() + 1).coerceAtLeast(2)

        val scroll = rememberScrollState()
        LaunchedEffect(weeks) {
            snapshotFlow { scroll.maxValue }.first { it > 0 }
            scroll.scrollTo(scroll.maxValue)
        }

        val primary = MaterialTheme.colorScheme.primary
        // ⚠️ 空白格不能用 `surfaceVariant`：本项目的主题把 surfaceVariant 定义成
        // 纯白（Theme.kt:75，为了让白卡与灰底分出层次），画在白卡上等于没画。
        // 改用 onSurface 的低透明度，深色模式下也能自然变成浅灰。
        val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

        val monthOfWeek = (0 until weeks).map { w -> start.plusDays((w * 7).toLong()).monthValue }
        val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

        Row {
            // Fixed left gutter with weekday labels.
            Column(horizontalAlignment = Alignment.End) {
                Spacer(Modifier.height(17.dp))
                weekdays.forEach { wd ->
                    Box(
                        Modifier.height(cell + gap).width(20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(wd, fontSize = 9.sp, color = labelColor)
                    }
                }
            }
            Spacer(Modifier.width(4.dp))

            Column(Modifier.horizontalScroll(scroll)) {
                // Month labels aligned to the week columns.
                Row(
                    modifier = Modifier.height(15.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Bottom
                ) {
                    for (w in 0 until weeks) {
                        Box(Modifier.width(cell)) {
                            if (w == 0 || monthOfWeek[w] != monthOfWeek[w - 1]) {
                                Text("${monthOfWeek[w]}月", fontSize = 9.sp, color = labelColor, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))

                for (row in 0 until 7) {
                    Row(
                        modifier = Modifier.height(cell + gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (w in 0 until weeks) {
                            val date = start.plusDays((w * 7 + row).toLong())
                            if (date.isAfter(today)) {
                                Box(Modifier.size(cell))
                            } else {
                                Box(
                                    Modifier
                                        .size(cell)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(levelColor(data[date], primary, empty))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun levelColor(rate: Float?, primary: Color, empty: Color): Color = when {
    // 没有记录的日子用**实色**的 surfaceVariant：早先这里是 alpha 0.35，
    // 在白卡上几乎看不见，整块热力图看着只剩右边几列有内容（模拟器实测）。
    // GitHub 那套也是实色浅灰，靠灰度差表示"这天没有数据"。
    rate == null -> empty
    rate <= 0f -> primary.copy(alpha = 0.12f)
    rate <= 0.34f -> primary.copy(alpha = 0.32f)
    rate <= 0.67f -> primary.copy(alpha = 0.60f)
    else -> primary
}

@Composable
private fun HeatmapLegend(primary: Color) {
    val alphas = listOf(0.12f, 0.32f, 0.60f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("少", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        alphas.forEach { a ->
            Box(
                Modifier
                    .size(11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(primary.copy(alpha = a))
            )
            Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text("多", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry) {
    val time = remember(entry.checkedAt) {
        Instant.ofEpochMilli(entry.checkedAt)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(entry.task.color)),
            contentAlignment = Alignment.Center
        ) { Text(entry.task.icon, fontSize = 18.sp) }
        Spacer(Modifier.width(12.dp))
        Text(entry.task.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
