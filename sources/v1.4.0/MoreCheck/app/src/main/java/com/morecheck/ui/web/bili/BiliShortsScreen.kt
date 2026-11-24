@file:Suppress("UnsafeOptInUsageError") // media3 的 UnstableApi 不是 Kotlin RequiresOptIn 标记，lint 按误报抑制

package com.morecheck.ui.web.bili

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import android.view.TextureView
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.data.net.BiliLibraryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ShortsPink = Color(0xFFFB7299)

/**
 * B 站**竖滑短视频流**（2026-11-18 重做新增）。
 *
 * 复用 [BilibiliRepository.fetchShortVideos]：它从推荐接口里筛出 3 分钟以内的视频，
 * 不依赖随时会改版的私有短视频端点。每一屏一个 [ExoPlayer]，翻页时释放上一屏，
 * 保证同时只有一个解码器在跑。互动（点赞 / 稍后再看）走本地库，免登录可用。
 *
 * @param onBack 返回首页。
 * @param onOpenDetail 点击标题进入原生详情页（看完整信息与评论）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BiliShortsScreen(
    onBack: () -> Unit,
    onOpenDetail: (BiliVideoItem) -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)

    var videos by remember { mutableStateOf<List<BiliVideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var index by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            videos = BilibiliRepository.fetchShortVideos(1)
        } catch (e: Exception) {
            error = e.message ?: "短视频加载失败"
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = ShortsPink)
                Spacer(Modifier.height(10.dp))
                Text("正在加载短视频…", color = Color.White, fontSize = 13.sp)
            }

            videos.isEmpty() -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    error ?: "暂时没有短视频",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "返回",
                    color = ShortsPink,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onBack() }
                )
            }

            else -> {
                val pagerState = rememberPagerState(initialPage = 0) { videos.size }
                LaunchedEffect(pagerState.currentPage) { index = pagerState.currentPage }

                VerticalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    ShortsPage(
                        item = videos[page],
                        active = pagerState.currentPage == page,
                        onOpenDetail = onOpenDetail,
                        onMessage = onMessage
                    )
                }

                // 顶部返回 + 计数
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "返回",
                        tint = Color.White,
                        modifier = Modifier
                            .size(26.dp)
                            .clickable { onBack() }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "短视频  ${index + 1}/${videos.size}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortsPage(
    item: BiliVideoItem,
    active: Boolean,
    onOpenDetail: (BiliVideoItem) -> Unit,
    onMessage: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(active) }
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var streamHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var liked by remember { mutableStateOf(false) }
    var inWatchLater by remember { mutableStateOf(false) }

    LaunchedEffect(item.bvid) {
        BiliLibraryStore.likedOf(context).collect { liked = item.bvid in it }
    }
    LaunchedEffect(item.bvid) {
        BiliLibraryStore.watchLaterOf(context).collect { inWatchLater = it.any { i -> i.bvid == item.bvid } }
    }

    // 解析单文件播放地址：一次 html5 取流即够（10min 内翻页回来直接命中缓存）。
    LaunchedEffect(item.bvid) {
        loading = true
        try {
            val cid = BilibiliRepository.fetchDetail(item.bvid).pages.firstOrNull()?.cid
                ?: 0L
            val playback = if (cid > 0L) {
                BilibiliRepository.fetchPlayback(item.bvid, cid)
            } else {
                null
            }
            streamUrl = playback?.url
            streamHeaders = playback?.headers ?: emptyMap()
            runCatching { BiliLibraryStore.recordWatch(context, item) }
        } catch (e: Exception) {
            streamUrl = null
        } finally {
            loading = false
        }
    }

    // 只在 active 且拿到地址时建播放器；翻页离开立即释放。
    LaunchedEffect(active, streamUrl) {
        player?.release()
        player = null
        if (!active || streamUrl.isNullOrBlank()) return@LaunchedEffect
        val url = streamUrl ?: return@LaunchedEffect
        val headers = streamHeaders.ifEmpty {
            mapOf(
                "Referer" to "https://www.bilibili.com/video/${item.bvid}/",
                "User-Agent" to "Mozilla/5.0"
            )
        }
        val exo = BiliPlayerFactory.create(context, headers).apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
        player = exo
        playing = true
    }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!streamUrl.isNullOrBlank()) {
            AndroidView(
                factory = { ctx -> TextureView(ctx) },
                update = { view -> player?.setVideoTextureView(view) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .align(Alignment.Center)
            )
        } else if (loading) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(30.dp),
                color = ShortsPink
            )
        } else {
            Text(
                "该短视频暂时无法播放",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 点击切换播放/暂停
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .align(Alignment.Center)
                .clickable {
                    player?.let { exo ->
                        if (exo.isPlaying) {
                            exo.pause()
                            playing = false
                        } else {
                            exo.play()
                            playing = true
                        }
                    }
                }
        )

        // 右侧互动条
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Favorite,
                    if (liked) "取消点赞" else "点赞",
                    tint = if (liked) ShortsPink else Color.White,
                    modifier = Modifier
                        .size(30.dp)
                        .clickable {
                            scope.launch {
                                val next = !liked
                                runCatching { BilibiliRepository.actLike(item.bvid, next) }
                                BiliLibraryStore.setLiked(context, item.bvid, next)
                                liked = next
                                onMessage(if (next) "已点赞" else "已取消点赞")
                            }
                        }
                )
                Text(if (liked) "已赞" else "点赞", color = Color.White, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.WatchLater,
                    "稍后再看",
                    tint = if (inWatchLater) ShortsPink else Color.White,
                    modifier = Modifier
                        .size(30.dp)
                        .clickable {
                            scope.launch {
                                if (inWatchLater) {
                                    BiliLibraryStore.removeWatchLater(context, item.bvid)
                                    onMessage("已移出稍后再看")
                                } else {
                                    BiliLibraryStore.addWatchLater(context, item)
                                    onMessage("已加入稍后再看")
                                }
                                inWatchLater = !inWatchLater
                            }
                        }
                )
                Text("稍后看", color = Color.White, fontSize = 11.sp)
            }
        }

        // 底部信息
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.4f))
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                "@${item.upName}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                item.title,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onOpenDetail(item) }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatPlayCount(item.viewCount)} 次播放 · 点击标题查看详情",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }

        if (playing) {
            Icon(
                Icons.Filled.Pause,
                null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.Center).size(40.dp)
            )
        } else {
            Icon(
                Icons.Filled.PlayArrow,
                null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(48.dp)
            )
        }
    }
}
