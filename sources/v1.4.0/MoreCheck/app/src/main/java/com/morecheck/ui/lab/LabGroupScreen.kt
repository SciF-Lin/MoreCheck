package com.morecheck.ui.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * 实验室的分组二级页（2026-11-24：宫格收敛）。
 *
 * 只负责「一个标题 + 一列功能入口」的通用展示；每个入口的点击交给 [onOpen]，
 * 由导航层复用原有的 `onOpenXxx` 回调，**不新建路由逻辑**。
 *
 * 这样原本平铺在宫格里的十项被收进两页，宫格首页一眼能看完；而这些功能各自的
 * `TabDestination` route 与桌面快捷方式 key 全部原样保留，老快捷方式仍能直达。
 */
data class LabGroupItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

/**
 * 「效率工具」分组的子项。图标集中在这里定义，导航层只需传各项的点击回调，
 * 不必在 TabNavHost 里堆一排图标 import。
 */
fun efficiencyGroupItems(
    onWeather: () -> Unit,
    onNotebook: () -> Unit,
    onAgent: () -> Unit,
    onPomodoro: () -> Unit,
    onTimetable: () -> Unit
): List<LabGroupItem> = listOf(
    LabGroupItem("天气", "联网查询当地天气", Icons.Filled.WbSunny, onWeather),
    LabGroupItem("记事本", "随手记一条文字与图片", Icons.Filled.Edit, onNotebook),
    LabGroupItem("智能体", "AI 对话（需自带 API Key）", Icons.Filled.SmartToy, onAgent),
    LabGroupItem("番茄时钟", "专注计时，圆环进度", Icons.Filled.Timer, onPomodoro),
    LabGroupItem("课程表", "查看课程与教学周", Icons.AutoMirrored.Filled.EventNote, onTimetable)
)

/** 「工具箱」分组的子项，口径同 [efficiencyGroupItems]。 */
fun toolboxGroupItems(
    onRandomNumber: () -> Unit,
    onMorseCode: () -> Unit,
    onImageCompress: () -> Unit,
    onDecisionMaker: () -> Unit,
    onColorCard: () -> Unit
): List<LabGroupItem> = listOf(
    LabGroupItem("随机数生成", "范围 / 个数 / 去重", Icons.Filled.Casino, onRandomNumber),
    LabGroupItem("摩斯电码", "文字与摩斯码互转", Icons.Filled.GraphicEq, onMorseCode),
    LabGroupItem("图片压缩", "选图压缩后存入相册", Icons.Filled.Compress, onImageCompress),
    LabGroupItem("做个决定", "从候选项里随机挑一个", Icons.Filled.Shuffle, onDecisionMaker),
    LabGroupItem("色卡", "中国色与日本色，共 776 色", Icons.Filled.Palette, onColorCard)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabGroupScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    items: List<LabGroupItem>
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            item {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.xs)
                )
            }
            items(items) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.field))
                        .clickable(onClick = entry.onClick)
                        .padding(horizontal = Space.md, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.padding(start = Space.md).weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
