package com.morecheck.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.SettingsRepository
import com.morecheck.data.music.MusicPlayback
import com.morecheck.data.music.PlayMode
import com.morecheck.data.music.PlaybackState
import com.morecheck.data.net.MusicLyrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏播放器（2026-11-08 第二版，按「设计说明」规格图重排）。
 *
 * ## 版面（自上而下，2026-11-08 红框 / 绿框标注后）
 *
 * 1. **顶栏**：收起圆键 · 「歌名 / 音源」 · 主题刷子圆键；
 * 2. **主视觉**：黑胶唱片占整宽居中；点唱片 → 同区域淡入滚动歌词
 *    （歌词占满主视觉区，当前行加粗并自动居中；2026-11-09 起**点歌词任意处切回封面**，
 *    不再另设「切回唱片」按钮）；
 * 3. **信息行**：左侧绿框 = 标题 + 副标题 + 两行歌词占位栏（整组、顺序不变），
 *    右侧红框 = 歌词 / 下载 / 收藏 / 更多 竖排四键；绿框组垂直居中于按钮列。
 *    **歌词视图整行淡出并纵向收起**——标题与歌词占位栏都不显示，主视觉自动吃掉这段高度；
 *    收起 / 展开都带高度动画，封面不会在切换时跳帧；
 * 4. **进度**：4dp 细条 + 强调色圆点，两端时间；
 * 5. **控制**：上一首 / 播放暂停 / 下一首三颗立体球面圆键；
 * 6. **底行**：播放模式 · 队列 N 首 · 睡眠定时。
 *
 * ## 为什么要按红框 / 绿框重排
 *
 * 四颗动作键原本贴在唱片右侧，唱片被挤偏、标题组下方又空出一大块。标注把
 * 「标题 + 歌词」圈进左侧 78% 的绿框、右侧 16% 的红框留给按钮列，两者并成一行，
 * 唱片因此重新居中。歌词视图是极简态：只留滚动歌词 + 进度 + 控制，不再重复
 * 显示标题与歌词占位栏。
 *
 * ## 主题
 *
 * 顶栏的刷子键打开 [MusicThemeSheet]；本页所有颜色来自 [rememberMusicPalette]，
 * 与应用其它页面的 `MaterialTheme` 完全解耦。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    state: PlaybackState,
    favorite: Boolean,
    cached: Boolean,
    downloading: Boolean,
    /** 是否正在删除缓存（2026-11-14 需求 4：图标换成飘走的垃圾桶）。 */
    removingCache: Boolean,
    onClose: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCache: () -> Unit
) {
    val track = state.track
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val palette = rememberMusicPalette()

    // 逐字歌词开关（设置 → 音乐；默认开）。读 DataStore 是异步的，
    // 首帧用 true 兜底——原生歌词页本来就是"整行变色"，晚一帧切到逐字不会有观感问题。
    val settings = remember(context) { SettingsRepository(context) }
    val karaokeEnabled by settings.musicLyricKaraoke
        .collectAsStateWithLifecycle(initialValue = true)

    var showSleepDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    /** false = 唱片视图，true = 歌词视图。 */
    var showLyrics by remember { mutableStateOf(false) }

    // 歌词：首帧先看内存缓存，命中时连「歌词加载中」都不会闪。
    var lyrics by remember(track?.id) {
        mutableStateOf(track?.let { MusicLyrics.peek(it.id) } ?: emptyList())
    }
    var lyricLoading by remember(track?.id) {
        mutableStateOf(track != null && MusicLyrics.peek(track.id) == null)
    }
    LaunchedEffect(track?.id) {
        val current = track ?: return@LaunchedEffect
        lyricLoading = lyrics.isEmpty()
        lyrics = runCatching { MusicLyrics.load(current, context) }.getOrDefault(emptyList())
        lyricLoading = false
    }

    val listState = rememberLazyListState()
    val currentLine = remember(lyrics, state.positionMs) {
        if (lyrics.isEmpty()) -1
        else lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
    }
    LaunchedEffect(currentLine, lyrics.size, showLyrics) {
        if (lyrics.isNotEmpty() && currentLine >= 0 && showLyrics) {
            // 负偏移把当前行推到视口中部偏上：贴顶时看不到下一句，跟唱会来不及。
            val center = -(listState.layoutInfo.viewportSize.height / 3)
            runCatching { listState.animateScrollToItem(currentLine, center) }
        }
    }

    // 返回键在播放器内部按层级收：队列 → 歌词 → 关播放器。
    // 2026-11-09 补上最后一级：以前这里只处理歌词，其余情况把事件漏给外层，
    // 外层处理器若正好处于禁用态（收起动画/重组的时间差），返回会一路落到 NavHost，
    // 把整个音乐页 pop 掉——也就是用户说的"点返回偶尔直接退出音乐"。
    BackHandler(enabled = true) {
        when {
            showQueue -> showQueue = false
            showLyrics -> showLyrics = false
            else -> onClose()
        }
    }

    // 整页 Surface 必须吞掉空白处的点击：它是覆盖在列表页之上的图层，
    // Compose 的 Surface 默认不消费触摸，不拦就会点到下面的歌曲列表。
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        color = Color.Transparent
    ) {
        MusicBackground(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // ---- 顶栏 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundIconButton(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "收起播放器",
                            onClick = onClose,
                            size = 40.dp
                        )
                        // 2026-11-12：标题附近的空白以前不接收点击，
                        // 「点上方任意位置切唱片/歌词」在这里补上整块热区。
                        // 收起键与主题键是自己的子项，点击照旧只走各自 onClick。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showLyrics = !showLyrics }
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = track?.name ?: "未在播放",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = palette.text,
                                // 长按复制歌名；短按仍由外层整块热区接着切视图。
                                modifier = Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { showLyrics = !showLyrics },
                                    onLongClick = {
                                        val name = track?.name
                                        if (!name.isNullOrBlank()) {
                                            clipboard.setText(AnnotatedString(name))
                                            Toast.makeText(context, "歌名已复制", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            )
                            Text(
                                text = buildString {
                                    append(track?.artist.orEmpty())
                                    val source = track?.source?.label.orEmpty()
                                    if (source.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(source)
                                    }
                                }.ifBlank { "—" },
                                fontSize = 10.sp,
                                color = palette.textMuted
                            )
                        }
                        ThemeBrushButton(
                            onClick = { showThemeSheet = true },
                            size = 40.dp
                        )
                    }

                    // ---- 主视觉：唱片 / 歌词同区域淡入淡出 ----
                    // 2026-11-08 标注调整：竖排四键已下移到「信息行」右侧（红框），
                    // 唱片重新居中吃满整宽。用 BoxWithConstraints 就地测可用高度，
                    // 信息行变高后唱片也不会被裁切。
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // 2026-11-12：除了唱片内圈 / 歌词块自己那层，
                            // 主视觉区域里剩下的空白（唱片上下左右）也都能切视图。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showLyrics = !showLyrics }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val discSize = minOf(maxWidth * 0.80f, maxHeight * 0.88f)
                        // 用文件级 FadeLayer：容器里直接写 AnimatedVisibility 会命中
                        // RowScope/BoxScope 的扩展重载而报歧义（本项目实测）。
                        FadeLayer(visible = !showLyrics) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(discSize)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember {
                                                MutableInteractionSource()
                                            },
                                            indication = null,
                                            onClick = { showLyrics = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (track == null) {
                                        Box(
                                            modifier = Modifier
                                                .size(discSize)
                                                .clip(CircleShape)
                                                .background(palette.buttonFill),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = palette.textMuted,
                                                modifier = Modifier.size(discSize * 0.22f)
                                            )
                                        }
                                    } else {
                                        VinylArtwork(
                                            track = track,
                                            size = discSize,
                                            playing = state.playing
                                        )
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(palette.accent)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "点击唱片 · 切换歌词",
                                        fontSize = 10.sp,
                                        color = palette.textMuted
                                    )
                                }
                            }
                        }

                        FadeLayer(visible = showLyrics) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                LyricsBlock(
                                    lyrics = lyrics,
                                    loading = lyricLoading,
                                    currentLine = currentLine,
                                    listState = listState,
                                    positionMs = state.positionMs,
                                    karaoke = karaokeEnabled,
                                    onTap = { showLyrics = false }
                                )
                                // 2026-11-09 用户要求：不再另设「切回唱片」按钮，
                                // 点击歌词本身即可切回（LyricsBlock 整体接收 tap）。
                                // 这里只留一行极淡提示，让「能点」这件事可被发现。
                                Text(
                                    text = "点击歌词 · 切回封面",
                                    fontSize = 10.sp,
                                    color = palette.textMuted.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                )
                            }
                        }
                    }

                    // ---- 信息行（2026-11-08 红框 / 绿框标注）----
                    // 左侧绿框：标题 → 副标题 → 歌词占位栏，整组一起搬，组内顺序、
                    // 内容与点击行为都不变；右侧红框：原先贴在唱片右边的竖排四键下移
                    // 到这里，与绿框组并排，绿框组垂直居中于按钮列（与标注一致）。
                    //
                    // 2026-11-09 修复「切歌词时两行歌词先消失、封面迅猛下移」：
                    // 旧写法 `if (!showLyrics)` 在同一帧摘掉整行，高度瞬间归零 →
                    // 主视觉 maxHeight 变大，唱片在淡出过程中突然放大/下移。
                    // 现在整行「淡出 + 纵向收缩」同步进行，唱片区域连续变化；
                    // 切回唱片时反向展开，两端都不再跳帧。
                    InfoRowLayer(visible = !showLyrics) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 2026-11-12：红框里的"中间标题"以前整块不接收点击，
                            // 只有下面的歌词占位栏能切。现在整列都是热区，
                            // 歌名本身再单独接长按复制（短按仍切视图）。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showLyrics = !showLyrics }
                                    )
                            ) {
                                Text(
                                    text = track?.name ?: "未在播放",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = palette.text,
                                    modifier = Modifier.combinedClickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showLyrics = !showLyrics },
                                        onLongClick = {
                                            val name = track?.name
                                            if (!name.isNullOrBlank()) {
                                                clipboard.setText(AnnotatedString(name))
                                                Toast.makeText(
                                                    context,
                                                    "歌名已复制",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    )
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = buildString {
                                        val artist = track?.artist.orEmpty()
                                        if (artist.isNotBlank()) append(artist)
                                        val album = track?.album.orEmpty()
                                        if (album.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(album)
                                        }
                                        if (state.previewOnly) append(" · 试听片段")
                                    },
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = palette.textSub
                                )
                                Spacer(Modifier.height(8.dp))
                                LyricPreviewCard(
                                    lyrics = lyrics,
                                    currentLine = currentLine,
                                    loading = lyricLoading,
                                    onClick = { showLyrics = true }
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            PlayerActionColumn(
                                favorite = favorite,
                                cached = cached,
                                downloading = downloading,
                removing = removingCache,
                                hasTrack = state.hasTrack,
                                onShowLyrics = { showLyrics = true },
                                onToggleCache = onToggleCache,
                                onToggleFavorite = onToggleFavorite,
                                onOpenQueue = { showQueue = true }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ---- 进度 ----
                    MusicProgressBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        onSeek = { MusicPlayback.seekTo(it) },
                        enabled = state.hasTrack
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(state.positionMs),
                            fontSize = 10.sp,
                            color = palette.textMuted
                        )
                        Text(
                            text = formatDuration(state.durationMs),
                            fontSize = 10.sp,
                            color = palette.textMuted
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // ---- 控制：上一首 / 播放暂停 / 下一首 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BigControlButton(
                            icon = Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            size = 56.dp,
                            enabled = state.queue.size > 1,
                            onClick = { MusicPlayback.prev() }
                        )
                        Spacer(Modifier.width(22.dp))
                        BigControlButton(
                            icon = if (state.playing) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = if (state.playing) "暂停" else "播放",
                            size = 76.dp,
                            primary = true,
                            enabled = state.hasTrack,
                            onClick = { MusicPlayback.toggle() }
                        )
                        Spacer(Modifier.width(22.dp))
                        BigControlButton(
                            icon = Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            size = 56.dp,
                            enabled = state.queue.size > 1,
                            onClick = { MusicPlayback.next() }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // ---- 底行：播放模式 / 队列 / 睡眠定时 ----
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BottomAction(
                            icon = modeIcon(state.mode),
                            label = state.mode.label,
                            onClick = { MusicPlayback.cycleMode() }
                        )
                        Spacer(Modifier.weight(1f))
                        BottomAction(
                            icon = Icons.Rounded.QueueMusic,
                            label = "${state.queue.size} 首队列",
                            enabled = state.queue.isNotEmpty(),
                            onClick = { showQueue = true }
                        )
                        Spacer(Modifier.weight(1f))
                        BottomAction(
                            icon = Icons.Rounded.Timer,
                            label = if (state.sleepEndAtMs > 0L) {
                                formatDuration(
                                    (state.sleepEndAtMs - System.currentTimeMillis())
                                        .coerceAtLeast(0L)
                                )
                            } else {
                                "定时"
                            },
                            active = state.sleepEndAtMs > 0L,
                            onClick = { showSleepDialog = true }
                        )
                    }
                }
            }
        }
    }

    if (showThemeSheet) {
        MusicThemeSheet(onDismiss = { showThemeSheet = false })
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            active = state.sleepEndAtMs > 0L,
            onDismiss = { showSleepDialog = false },
            onPick = { minutes ->
                MusicPlayback.startSleepTimer(minutes)
                showSleepDialog = false
            },
            onCancelTimer = {
                MusicPlayback.cancelSleepTimer()
                showSleepDialog = false
            }
        )
    }

    // 2026-11-09：队列改为常驻挂载 + visible 驱动进出动画。
    // 若继续用 `if (showQueue)` 挂载/卸载，关闭时组件被直接摘掉，
    // 退出动画根本没有机会播放。
    QueueSheet(
        state = state,
        visible = showQueue,
        onDismiss = { showQueue = false },
        onPick = { index ->
            MusicPlayback.playQueue(state.queue, index)
            showQueue = false
        }
    )
}

/**
 * 同一区域的淡入淡出切换（规格图 04：0.42s）。
 *
 * 单独抽出来是为了避开 `RowScope/BoxScope.AnimatedVisibility` 扩展重载：在容器里直接用
 * 会因隐式接收者歧义而编译失败（本项目实测）。
 */
@Composable
private fun FadeLayer(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(420)),
        exit = fadeOut(tween(280))
    ) {
        content()
    }
}

/**
 * 信息行的出现 / 收起（2026-11-09）。
 *
 * 与 [FadeLayer] 分开：这里除了透明度，还要让**高度**一起变化。
 * 退出时 `shrinkVertically` 把整行高度逐步收到 0，主视觉的 `weight(1f)`
 * 才会连续变大——封面不再在淡出途中被瞬间拉高、看起来像"迅猛下移"；
 * 进入时 `expandVertically` 反向展开，位置同样连续。
 *
 * 仍然抽成文件级函数：Column / Row 作用域里直接写 `AnimatedVisibility`
 * 会命中扩展重载而报歧义（本项目实测）。
 */
@Composable
private fun InfoRowLayer(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(300)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(300))
    ) {
        content()
    }
}

/** 播放队列遮罩的淡入淡出（不跟着面板一起位移）。 */
@Composable
private fun SheetFade(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200))
    ) {
        content()
    }
}

/** 播放队列面板的滑入滑出：从底部整块推上来，关闭时原路退回。 */
@Composable
private fun SheetSlide(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(300)) { it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(160))
    ) {
        content()
    }
}

/**
 * 竖排四键（2026-11-08 标注里的**红框**位置）。
 *
 * 原先贴在唱片右侧，把唱片挤偏；现在与「标题 + 歌词占位栏」同一行，落在右侧
 * 窄竖条里。四颗键的图标、顺序、启用条件与点击行为都和原来完全一致。
 */
@Composable
private fun PlayerActionColumn(
    favorite: Boolean,
    cached: Boolean,
    downloading: Boolean,
    removing: Boolean,
    hasTrack: Boolean,
    onShowLyrics: () -> Unit,
    onToggleCache: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RoundIconButton(
            icon = Icons.Rounded.Lyrics,
            contentDescription = "切换到歌词",
            onClick = onShowLyrics,
            size = 40.dp
        )
        RoundIconButton(
            icon = Icons.Rounded.FileDownload,
            contentDescription = when {
                downloading -> "正在下载"
                cached -> "删除已下载"
                else -> "下载到本机"
            },
            onClick = onToggleCache,
            size = 40.dp,
            active = cached,
            enabled = !downloading && !removing && hasTrack,
            // 2026-11-14 需求 4：下载中把图标原地换成转圈；删缓存时换成向上飘的垃圾桶。
            loading = downloading,
            removing = removing
        )
        RoundIconButton(
            icon = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (favorite) "取消收藏" else "收藏",
            onClick = onToggleFavorite,
            size = 40.dp,
            active = favorite
        )
        RoundIconButton(
            icon = Icons.Rounded.MoreHoriz,
            contentDescription = "更多（播放队列）",
            onClick = onOpenQueue,
            size = 40.dp
        )
    }
}

/**
 * 两行歌词占位栏（2026-11-08 标注要求）。
 *
 * - **默认**：完全透明，与页面背景同色，没有白底、边框、左侧竖线等任何突出效果；
 * - **点击激活**：按下的瞬间淡入白色底板 + 亮边 + 左侧主题色竖线，这是它唯一的
 *   点击态反馈；松手（或取消按压）后短时保持再淡出，恢复默认；
 * - **点击行为不变**：仍是进入滚动歌词页。为了让白色态来得及被看见，onClick
 *   延后 170ms 再交给调用方——只加了一段过渡，不改变功能。
 *
 * 歌词页里本组件根本不渲染（信息行整行隐藏），因此不再需要「已展开」占位文案。
 * 固定 52dp 高：切换视图时整页不会跳动。
 */
@Composable
private fun LyricPreviewCard(
    lyrics: List<MusicLyrics.Line>,
    currentLine: Int,
    loading: Boolean,
    onClick: () -> Unit
) {
    val palette = rememberMusicPalette()
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    var highlighted by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> highlighted = true
                is PressInteraction.Release -> {
                    delay(260)
                    highlighted = false
                }
                is PressInteraction.Cancel -> highlighted = false
                else -> Unit
            }
        }
    }
    val glow by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(if (highlighted) 90 else 180),
        label = "lyricPreviewGlow"
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(palette.glass.copy(alpha = palette.glass.alpha * glow))
            .border(1.dp, palette.glassBorder.copy(alpha = glow), shape)
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = { scope.launch { delay(170); onClick() } }
            )
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 竖线始终占 3dp（只是 alpha 跟着 glow 变），避免按下时文字左右跳动。
            Box(
                Modifier
                    .width(3.dp)
                    .height(52.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                palette.accentLight.copy(alpha = glow),
                                palette.accentDark.copy(alpha = glow)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                when {
                    loading -> Text(
                        text = "歌词加载中…",
                        fontSize = 12.sp,
                        color = palette.textMuted
                    )

                    lyrics.isEmpty() -> {
                        Text(
                            text = "暂无歌词",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.text
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "点击唱片查看滚动歌词",
                            fontSize = 11.sp,
                            color = palette.textMuted
                        )
                    }

                    else -> {
                        val current = lyrics.getOrNull(currentLine.coerceAtLeast(0))?.text.orEmpty()
                        val next = lyrics.getOrNull(currentLine + 1)?.text.orEmpty()
                        Text(
                            text = current.ifBlank { "· · ·" },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.text
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = next.ifBlank { "· · ·" },
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.textMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * 歌词块：整块可滚动，当前行加粗放大、其余压到弱文字色。
 *
 * 行距 22dp：歌词要「跟着唱」，太密会找不到当前行；太高则一屏看不到几句。
 */
@Composable
private fun LyricsBlock(
    lyrics: List<MusicLyrics.Line>,
    loading: Boolean,
    currentLine: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    /** 播放进度：逐字高亮要用它算"这一行唱到第几个字"。 */
    positionMs: Long,
    /** 逐字高亮开关（设置里可关；关了就是整行变色）。 */
    karaoke: Boolean,
    onTap: () -> Unit
) {
    val palette = rememberMusicPalette()
    // 2026-11-09：整块歌词接收 tap（点击切回封面）。与 LazyColumn 的滚动并存——
    // 滚动容器只消费拖动，单击会冒泡到这一层 clickable，两者不冲突。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            )
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "歌词加载中…",
                    fontSize = 12.sp,
                    color = palette.textMuted
                )
            }

            lyrics.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无歌词\n（有些冷门歌与纯音乐确实没有）",
                    fontSize = 12.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    color = palette.textMuted
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(vertical = 48.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val active = index == currentLine
                    // 2026-11-09 需求 5：**逐字高亮**。
                    // LRC 只有"整行开始时间"，没有逐字时间轴，所以按行内字数做线性插值：
                    // 本行开始 → 下一行开始（最后一行按 +4s 估），走完就把整行点亮。
                    // 这是 KTV 歌词的常见近似做法，代价是长句与短句语速看起来一致；
                    // 换来的是**不需要改数据源**，任何 LRC 都能用。
                    val sungEnd = if (active && karaoke) {
                        val start = line.timeMs
                        val end = lyrics.getOrNull(index + 1)?.timeMs
                            ?.takeIf { it > start }
                            ?: (start + 4_000L)
                        val frac = ((positionMs - start).toFloat() / (end - start).toFloat())
                            .coerceIn(0f, 1f)
                        (line.text.length * frac).toInt().coerceIn(0, line.text.length)
                    } else {
                        0
                    }
                    val shown = if (active && karaoke) {
                        buildAnnotatedString {
                            withStyle(
                                SpanStyle(color = palette.accent)
                            ) { append(line.text.take(sungEnd)) }
                            withStyle(
                                SpanStyle(color = palette.text)
                            ) { append(line.text.drop(sungEnd)) }
                        }
                    } else {
                        AnnotatedString(line.text)
                    }
                    Text(
                        text = shown,
                        fontSize = if (active) 19.sp else 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        lineHeight = if (active) 28.sp else 22.sp,
                        textAlign = TextAlign.Center,
                        color = if (active) palette.text else palette.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

/** 播放控制的大圆键：统一走 [SphereButton] 的立体渐变 + 按压回弹。 */
@Composable
private fun BigControlButton(
    icon: ImageVector,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true
) {
    val palette = rememberMusicPalette()
    SphereButton(
        onClick = onClick,
        size = size,
        enabled = enabled,
        accentFill = false
    ) { iconScale, dimmed ->
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> palette.textMuted
                dimmed -> palette.textSub
                else -> palette.text
            },
            modifier = Modifier
                .size(size * if (primary) 0.42f else 0.38f)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
    }
}

/** 底行的图标 + 文字小按钮，同样带回弹。 */
@Composable
private fun BottomAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false
) {
    val palette = rememberMusicPalette()
    val tint = when {
        !enabled -> palette.textMuted
        active -> palette.accent
        else -> palette.textSub
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .bounceClick(onClick, enabled)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, fontSize = 10.sp, color = tint)
    }
}

/**
 * 播放队列弹层（2026-11-08 手势要求，**自定义实现**）。
 *
 * 为什么不用 `ModalBottomSheet`：它的 Expanded 锚点固定贴屏幕顶，
 * `contentWindowInsets` 只把**内容**往下推，白色 sheet 背景仍盖住状态栏，
 * 做不到「向上滑动后约 90% 且不与通知栏重叠」。这里把整张 sheet 底部对齐、
 * 高度在 50% / 90% 两档之间做动画，顶部自然留出 10%（全屏）或 50%（半屏），
 * 状态栏与系统导航栏都露在外面。
 *
 * - 默认 **半屏**；点遮罩或按返回键关闭；
 * - 手柄 / 标题行**向上滑** → 展开到 90%；
 * - 从 90% **向下滑** → 回半屏；从半屏再向下滑 → 关闭；
 * - 内容底部 `navigationBarsPadding()`，不压导航键。
 *
 * 拖拽手势只挂在手柄 + 标题这一小段上：挂到整张 sheet 会把 LazyColumn 的
 * 竖向滚动吃掉，列表就滑不动了。
 */
@Composable
private fun QueueSheet(
    state: PlaybackState,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val palette = rememberMusicPalette()
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var expanded by remember { mutableStateOf(false) }
    val sheetHeight by animateDpAsState(
        targetValue = if (expanded) screenHeight * 0.9f else screenHeight * 0.5f,
        animationSpec = tween(280),
        label = "queueSheetHeight"
    )

    // 关闭后把展开状态收回半屏：下次打开不会停在上次拉到的 90%。
    LaunchedEffect(visible) { if (!visible) expanded = false }

    BackHandler(enabled = visible) { onDismiss() }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩淡入淡出，不跟着面板一起位移。
        SheetFade(visible = visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // 面板从底部整块推上来 / 原路退回。外层普通 Box 承担底对齐，
        // 文件级 wrapper 内部再调 AnimatedVisibility，避开 BoxScope 重载歧义。
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            SheetSlide(visible = visible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(sheetHeight)
                    // padding 放在 background 之前：白色底板本身也止于导航栏上沿，
                    // 不只是把内容往上挤，弹层不会铺到系统导航键底下。
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(palette.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(expanded) {
                                var total = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { total = 0f },
                                    onDragEnd = {
                                        when {
                                            total < -72f -> expanded = true
                                            total > 72f -> {
                                                if (expanded) expanded = false else onDismiss()
                                            }
                                        }
                                    },
                                    onVerticalDrag = { _, dragAmount -> total += dragAmount }
                                )
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(palette.textMuted.copy(alpha = 0.5f))
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "播放队列",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.text
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${state.queue.size} 首 · 上滑全屏 / 下滑关闭",
                                fontSize = 11.sp,
                                color = palette.textMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(bottom = 12.dp)
                    ) {
                        itemsIndexed(state.queue) { index, item ->
                            val active = index == state.index
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bounceClick(onClick = { onPick(index) })
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = (index + 1).toString().padStart(2, '0'),
                                    fontSize = 12.sp,
                                    color = if (active) palette.accent else palette.textMuted,
                                    modifier = Modifier.width(26.dp)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (active) palette.accent else palette.text
                                    )
                                    Text(
                                        text = item.artist,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = palette.textMuted
                                    )
                                }
                                Text(
                                    text = formatDuration(item.durationMs),
                                    fontSize = 11.sp,
                                    color = palette.textMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 播放模式 → 图标。顺序、单曲、列表、随机各一个，和主流播放器一致。 */
private fun modeIcon(mode: PlayMode) = when (mode) {
    PlayMode.ORDER -> Icons.Rounded.PlaylistPlay
    PlayMode.SINGLE_LOOP -> Icons.Rounded.RepeatOne
    PlayMode.LIST_LOOP -> Icons.Rounded.Repeat
    PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
}

/**
 * 定时停止选择框。
 *
 * 档位只给 15 / 30 / 60 分钟：这是「睡着前听完这一会儿」的典型需求，
 * 再多档位反而让人犹豫。已经开着时额外给「关闭定时」。
 */
@Composable
private fun SleepTimerDialog(
    active: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onCancelTimer: () -> Unit
) {
    val palette = rememberMusicPalette()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.text,
        textContentColor = palette.textSub,
        title = { Text("定时停止") },
        text = {
            Column {
                Text(
                    text = "到达时间后自动暂停播放。",
                    fontSize = 13.sp,
                    color = palette.textSub
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(15, 30, 60).forEach { minutes ->
                        TextButton(
                            onClick = { onPick(minutes) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("$minutes 分钟", fontSize = 13.sp)
                        }
                    }
                }
                if (active) {
                    TextButton(
                        onClick = onCancelTimer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("关闭定时", color = palette.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
