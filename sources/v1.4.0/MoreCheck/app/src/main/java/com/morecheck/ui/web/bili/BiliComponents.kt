package com.morecheck.ui.web.bili

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morecheck.data.net.BilibiliRepository.BiliQuality
import com.morecheck.data.net.BilibiliRepository.BiliQualitySource
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// 文案 / 数字格式化（列表卡片与详情页共用）
// ---------------------------------------------------------------------------

/**
 * 播放量缩写：12345 → 1.2万，123456789 → 1.2亿。
 *
 * 为什么不用 GitHub 那套 k/m：这是中文视频站，用户对「万」的量级更敏感；
 * 而且 B 站自己的列表也这么写，与用户对这个界面的既有认知一致。
 */
internal fun formatPlayCount(count: Int): String = when {
    count >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.ROOT, "%.1f万", count / 10_000.0)
    else -> count.toString()
}

/** 秒 → `mm:ss` / `h:mm:ss`（B 站列表的口径）。 */
internal fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return "00:00"
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
    }
}

/** 秒级时间戳 → `yyyy-MM-dd`（详情页「发布于」用）。 */
internal fun formatPublishDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "未知时间"
    return runCatching {
        // 与上面的 String.format 一致固定 Locale.ROOT：纯数字格式；不传时默认 Locale
        // 在佛历/和历设备上会把 yyyy 显示成 2569 / 令和 8。
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))
    }.getOrDefault("未知时间")
}

// ---------------------------------------------------------------------------
// 视频卡片
// ---------------------------------------------------------------------------

/**
 * 列表卡片：左封面 + 右文字（标题 / UP 主 / 播放量 / 时长）。
 *
 * 为什么封面固定 16:9 且宽度固定：三处列表（推荐/热门/搜索）共用同一个卡片，
 * 固定尺寸能让滚动时的行高完全稳定，不出现"有的行高一行有的两行"的跳动。
 */
@Composable
internal fun BiliVideoCard(
    item: BiliVideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // 深色模式下卡片与页底只差 1.21:1，补一条描边才看得出边界（与 GitHub 热门页同口径）。
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(Radius.card)
            )
            .clickableNoIndication(onClick)
            .padding(Space.sm),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .aspectRatio(16f / 9f)
        ) {
            BiliCover(
                url = item.coverUrl,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize()
            )
            // 时长角标压在封面右下角：与 B 站列表一致，用户扫一眼就能判断"要不要花这个时间"。
            if (item.durationSeconds > 0) {
                Text(
                    text = formatDuration(item.durationSeconds),
                    style = AppType.micro,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(Modifier.width(Space.sm))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = AppType.body,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.upName,
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Space.sm))
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = formatPlayCount(item.viewCount),
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 加载中骨架 / 错误 / 空态
// ---------------------------------------------------------------------------

/** 骨架屏的呼吸透明度（无限往返）。 */
@Composable
private fun skeletonAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "biliSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse
        ),
        label = "biliSkeletonAlpha"
    )
    return alpha
}

/**
 * 骨架屏（首屏加载态）。
 *
 * 为什么用骨架而不是一个居中转圈：列表页首屏结构固定（图+三行字），
 * 骨架能让用户提前看到"内容会出现在哪"，体感比空白转圈快；
 * 也与项目其它列表页的加载卡口径一致。
 */
@Composable
internal fun BiliSkeletonList(modifier: Modifier = Modifier) {
    val alpha = skeletonAlpha()
    val blockColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.22f)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        repeat(6) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(Space.sm)
            ) {
                Box(
                    Modifier
                        .width(132.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Radius.control))
                        .background(blockColor)
                )
                Spacer(Modifier.width(Space.sm))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.92f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(Radius.control))
                            .background(blockColor)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.66f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(Radius.control))
                            .background(blockColor)
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(0.45f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(Radius.control))
                            .background(blockColor)
                    )
                }
            }
        }
    }
}

/**
 * 错误态：一句人话 + 重试按钮。
 *
 * `noNetwork=true` 时文案与图标都换成「没网」语义：让用户知道要做的是打开网络，
 * 而不是反复点重试（用户需求点名要区分这两种情况）。
 */
@Composable
internal fun BiliErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    noNetwork: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (noNetwork) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = if (noNetwork) "当前没有网络连接" else "加载失败",
            style = AppType.heading,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = message,
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.lg))
        Button(onClick = onRetry) { Text("重试") }
    }
}

/** 空态：标题 + 一句可执行的下一步（不写"暂无数据"这种没有信息量的文案）。 */
@Composable
internal fun BiliEmptyPane(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = title, style = AppType.body, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(Space.xs))
        Text(
            text = hint,
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------------------------------------------------------------
// 清晰度选择对话框
// ---------------------------------------------------------------------------

/** 清晰度探测的三态。 */
internal sealed interface BiliQualityUiState {
    /** 正在请求 playurl。 */
    data object Loading : BiliQualityUiState

    /** 拿到真实可用档位。 */
    data class Ready(val qualities: List<BiliQuality>) : BiliQualityUiState

    /** 探测失败（网络/风控/接口变化），带中文原因。 */
    data class Failed(val message: String) : BiliQualityUiState
}

/**
 * 「选择下载清晰度」**底部弹层**（2026-11-03 增强：从普通对话框改成底部弹层）。
 *
 * ## 设计要点（对应用户需求「清晰度弹窗美化」）
 *
 * 1. 档位**全部来自接口实时返回**，不做任何本地写死；
 * 2. 每档给出：清晰度名、链路（单文件/合流）、码率（DASH 有）、估算体积——
 *    让用户在下之前就知道"这个文件多大、要等多久"；
 * 3. **选中态**：点击行高亮并显示对勾，底部「开始下载」才可点（避免误触直接开下）；
 * 4. 探测失败时给出原因与「重新获取」，绝不显示空列表或假档位；
 * 5. 深浅色都走 MaterialTheme 语义色，不硬编码背景。
 *
 * @param state 三态探测结果。
 * @param durationSeconds 视频时长（秒）：用于把 DASH 带宽估算成体积，0 = 未知。
 * @param onSelect 确认下载某档（外层负责关闭弹层并开始下载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliQualityDialog(
    state: BiliQualityUiState,
    durationSeconds: Int = 0,
    onSelect: (BiliQuality) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    // 选中态：默认不选，必须用户明确点一下再按「开始下载」。
    var selectedId by remember(state) { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.lg)
        ) {
            Text("选择下载清晰度", style = AppType.heading)
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "清晰度由 B 站接口实时返回；登录 B 站账号后可能解锁更高档位。" +
                    "文件保存到相册「Movies/MoreCheck」。",
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Space.md))

            when (state) {
                is BiliQualityUiState.Loading -> Row(
                    modifier = Modifier.padding(vertical = Space.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(Space.md))
                    Text(
                        text = "正在向 B 站查询可用清晰度…",
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is BiliQualityUiState.Failed -> Column(
                    modifier = Modifier.padding(vertical = Space.sm)
                ) {
                    Text(
                        text = "拿不到可用清晰度",
                        style = AppType.body,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = state.message,
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Space.sm))
                    TextButton(onClick = onRetry) { Text("重新获取") }
                }

                is BiliQualityUiState.Ready -> Column {
                    state.qualities.forEachIndexed { index, quality ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        QualityRow(
                            quality = quality,
                            durationSeconds = durationSeconds,
                            selected = quality.id == selectedId,
                            onClick = { selectedId = quality.id }
                        )
                    }
                    Spacer(Modifier.height(Space.md))
                    Button(
                        onClick = {
                            val picked = state.qualities.firstOrNull { it.id == selectedId }
                            if (picked != null) onSelect(picked)
                        },
                        enabled = selectedId != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("开始下载")
                    }
                }
            }
        }
    }
}

/** 清晰度弹层里的一行：名称 / 链路与体积 / 选中对勾。 */
@Composable
private fun QualityRow(
    quality: BiliQuality,
    durationSeconds: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(Radius.control)
            )
            .padding(horizontal = Space.sm, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = quality.label,
                    style = AppType.body,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(Space.sm))
                // 链路标签：单文件 vs 合流，直接影响下载时长，显式标出来。
                Text(
                    text = when (quality.source) {
                        BiliQualitySource.PROGRESSIVE -> "单文件 MP4"
                        BiliQualitySource.DASH -> "音视频合流"
                    },
                    style = AppType.micro,
                    color = accent,
                    modifier = Modifier
                        .background(
                            accent.copy(alpha = 0.12f),
                            RoundedCornerShape(Radius.pill)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = qualityMetaText(quality, durationSeconds),
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选择",
                tint = accent,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 一行说明：码率 / 体积 / 下载方式补充。 */
private fun qualityMetaText(quality: BiliQuality, durationSeconds: Int): String {
    val parts = mutableListOf<String>()
    if (quality.bandwidth > 0) {
        parts += "码率 ${String.format(java.util.Locale.ROOT, "%.0f", quality.bandwidth / 1000.0)} kbps"
    }
    val estimate = estimateSizeBytes(quality, durationSeconds)
    if (estimate > 0) {
        parts += "约 ${formatSizeBytes(estimate)}"
    }
    parts += if (quality.source == BiliQualitySource.PROGRESSIVE) {
        "系统下载器，支持后台继续"
    } else {
        "需先下两轨再封装，稍慢"
    }
    return parts.joinToString(" · ")
}

/** 体积估算：单文件用服务端 size；DASH 用 带宽×时长/8。 */
private fun estimateSizeBytes(quality: BiliQuality, durationSeconds: Int): Long {
    if (quality.sizeBytes > 0L) return quality.sizeBytes
    if (quality.bandwidth > 0L && durationSeconds > 0) {
        return quality.bandwidth * durationSeconds / 8L
    }
    return 0L
}

/** 字节数 → 人类可读大小。 */
private fun formatSizeBytes(bytes: Long): String {
    if (bytes <= 0L) return "未知"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb >= 1024.0) {
        String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
    } else {
        String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    }
}

/**
 * 极简点击：只响应点击、不画涟漪。
 *
 * 与 GitHub 热门页同一做法：卡片是自绘圆角容器，Material 水波纹会被裁得不圆；
 * 按压反馈交给未启用——本页是浏览型页面，滚动中误触比"点下去有反馈"更影响体验。
 */
@Composable
internal fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick
        )
    )
}
