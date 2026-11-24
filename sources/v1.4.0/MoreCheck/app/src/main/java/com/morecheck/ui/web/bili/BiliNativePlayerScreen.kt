@file:Suppress("UnsafeOptInUsageError") // media3 的 UnstableApi 不是 Kotlin RequiresOptIn 标记，lint 按误报抑制

package com.morecheck.ui.web.bili

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.morecheck.R
import com.morecheck.data.net.BiliLibraryStore
import com.morecheck.data.net.BiliSponsorBlock
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.data.net.BilibiliRepository.BiliComment
import com.morecheck.data.net.BilibiliRepository.BiliDanmaku
import com.morecheck.data.net.BilibiliRepository.BiliQuality
import com.morecheck.data.net.BilibiliRepository.BiliVideoDetail
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.util.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 播放页右侧抽屉的三种形态（2026-11-20 改造：倍速 / 清晰度 / 更多统一抽屉）。 */
private enum class PlayerDrawer { None, Speed, Quality, More }

/**
 * B 站原生播放页（2026-11-18 按参考实现重做）。
 *
 * 结构对齐参考 APK 的 `activity_video.xml`：
 * ```
 * 视频区(固定 210dp) → 简介/评论 标签行 → 内容页(简介 | 评论) → 底部评论输入条
 * ```
 * 简介页：UP 主卡 + 关注、标题、BV/播放/弹幕/日期、六项互动（赞/踩/投币/收藏/分享/稍后再看）、
 * 简介正文、相关推荐（118×68 缩略图）。
 *
 * 播放用 media3 ExoPlayer + TextureView（**不能用 SurfaceView**：它独立成层会盖住弹幕）；
 * 弹幕由 [BiliDanmakuLayer] 自绘。
 */
@Composable
fun BiliNativePlayerScreen(
    bvid: String,
    cid: Long,
    title: String,
    upName: String,
    mid: Long = 0L,
    pageIndex: Int = 1,
    /**
     * 点「相关推荐」里的视频：交给外层切换 bvid 重新进播放页。
     *
     * 不能在页内自己换——播放器、弹幕、详情、评论全都是按 bvid 初始化的 remember 状态，
     * 页内改一个局部 bvid 只会让这些状态与新视频错位。外层用 `key(bvid)` 重建整页最干净。
     */
    onOpenVideo: (BiliVideoItem) -> Unit = {},
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    BiliTheme {
        val c = biliColors()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var player by remember { mutableStateOf<ExoPlayer?>(null) }
        var qualities by remember { mutableStateOf<List<BiliQuality>>(emptyList()) }
        var currentQuality by remember { mutableStateOf<BiliQuality?>(null) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var retryTick by remember { mutableIntStateOf(0) }
        /** 视频播完自增，交给独立 effect 处理「自动播下一集」（避免监听器捕获到旧 related）。 */
        var endedTick by remember { mutableIntStateOf(0) }

        var currentMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var isPlaying by remember { mutableStateOf(false) }
        var showControls by remember { mutableStateOf(true) }
        var speedIndex by remember { mutableIntStateOf(0) }
        /** 倍速入口改为任务行同款「右上角菜单」：Box 锚点 + MoreVert + DropdownMenu。 */
        var speedMenuExpanded by remember { mutableStateOf(false) }
        var danmakuEnabled by remember { mutableStateOf(true) }
        var danmakuOpacity by remember { mutableFloatStateOf(1f) }
        var danmakus by remember { mutableStateOf<List<BiliDanmaku>>(emptyList()) }
        /** 赞助跳过（bsbsb.top）：开关持久化在本机库，片段随取流一起拉。 */
        var sponsorSkip by remember { mutableStateOf(true) }
        var sponsorSegments by remember { mutableStateOf<List<BiliSponsorBlock.Segment>>(emptyList()) }
        // 传入 cid 可能是 0（列表卡片直达播放），此时先拉详情解析首个分 P 的 cid。
        var effectiveCid by remember { mutableLongStateOf(cid) }
        /** 视频宽高比；全屏时按它等比铺满，避免 TextureView 拉伸变形。 */
        var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
        /** 全屏：隐藏系统栏 + 只剩视频区 + 请求横屏。 */
        var fullscreen by remember { mutableStateOf(false) }

        var detail by remember { mutableStateOf<BiliVideoDetail?>(null) }
        var related by remember { mutableStateOf<List<BiliVideoItem>>(emptyList()) }
        var comments by remember { mutableStateOf<List<BiliComment>>(emptyList()) }
        var commentTotal by remember { mutableIntStateOf(0) }
        var commentsLoading by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }

        // 2026-11-20 抽屉化：清晰度 / 倍速 / 更多 共用右侧抽屉，同时只开一个。
        // 用「枚举式」单一状态，避免三个 Boolean 同时 true 时叠三层遮罩。
        var drawer by remember { mutableStateOf<PlayerDrawer>(PlayerDrawer.None) }
        /** 抽屉选中的清晰度 qn（0 = 服务端默认）。切档会重建播放器并续播。 */
        var requestedQn by remember { mutableIntStateOf(0) }
        /** 更多抽屉里的播放偏好（本机状态，退出页面即失效，不写库）。 */
        var sleepMinutes by remember { mutableIntStateOf(0) }
        var autoPlayNext by remember { mutableStateOf(false) }
        var loopMode by remember { mutableIntStateOf(0) } // 0 不循环 / 1 单集 / 2 列表
        var backgroundAudio by remember { mutableStateOf(false) }
        var videoFitFill by remember { mutableStateOf(false) } // false 适应 / true 填充
        /** 登录态：评论输入框只在已登录时显示（未登录显示引导行）。 */
        val loggedIn = BilibiliRepository.isLoggedIn()

        var liked by remember { mutableStateOf(false) }
        var disliked by remember { mutableStateOf(false) }
        var coined by remember { mutableStateOf(false) }
        var favorited by remember { mutableStateOf(false) }
        var followed by remember { mutableStateOf(false) }
        var inWatchLater by remember { mutableStateOf(false) }
        var commentInput by remember { mutableStateOf("") }

        val speeds = remember { floatArrayOf(1f, 1.25f, 1.5f, 2f) }
        val speedLabels = remember { listOf("1.0x", "1.25x", "1.5x", "2.0x") }

        // 返回键优先级：抽屉 → 全屏 → 退页（与官方一致；抽屉打开时必须先收抽屉）。
        BackHandler {
            if (drawer != PlayerDrawer.None) drawer = PlayerDrawer.None
            else if (fullscreen) fullscreen = false
            else onBack()
        }

        // 全屏的窗口侧效果：隐藏/恢复系统栏（通知栏、导航条），并请求横屏。
        //
        // 为什么要请求横屏：参考实现与官方在竖屏点全屏都会转横屏。
        // MainActivity 声明了 configChanges（见 AndroidManifest 的注释），旋转不会
        // 重建 Activity，因此 ExoPlayer 实例与播放进度都不会丢——这是能"真全屏"的前提。
        val activity = remember(context) { context.findActivity() }
        val rootView = LocalView.current
        // 2026-11-20 需求 1：播放页「顶部手机状态栏固定为黑色」。
        // 应用全局是 edge-to-edge（透明状态栏 + 各页 statusBarsPadding），所以这里
        // ① 在状态栏高度画一条纯黑底；② 把状态栏图标切成浅色，否则深色底上的深色图标看不见。
        // 退出播放页时按当前主题恢复，避免把浅色图标漏给别的页面。
        DisposableEffect(c.isDark) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, rootView) }
            controller?.isAppearanceLightStatusBars = false // 黑底 → 浅色图标
            onDispose {
                controller?.isAppearanceLightStatusBars = !c.isDark
            }
        }
        DisposableEffect(fullscreen) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, rootView) }
            if (controller != null) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    activity?.requestedOrientation =
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            onDispose {
                // 退出播放页时一定要恢复：否则会把系统栏状态漏给别的页面。
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        /**
         * 需要 B 站登录态的动作统一入口（点赞 / 投币 / 踩 / 关注 / 发评论）。
         *
         * 参考实现与官方在未登录时都是**明确告知**，而不是静默失败或假装成功。
         * 本页此前点赞是「本地先记成功、接口失败被 runCatching 吞掉」——用户看到
         * 「已点赞」，实际服务端没变，正是这次要修的问题。
         */
        fun requireLogin(action: String): Boolean {
            if (BilibiliRepository.isLoggedIn()) return true
            onMessage("请先登录 B 站账号后再$action")
            return false
        }

        LaunchedEffect(bvid) {
            BiliLibraryStore.likedOf(context).collect { liked = bvid in it }
        }
        LaunchedEffect(bvid) {
            BiliLibraryStore.watchLaterOf(context).collect { inWatchLater = it.any { i -> i.bvid == bvid } }
        }
        LaunchedEffect(bvid) {
            BiliLibraryStore.favoritesOf(context).collect { favorited = it.any { i -> i.bvid == bvid } }
        }
        LaunchedEffect(mid) {
            BiliLibraryStore.followedOf(context).collect { followed = mid > 0L && mid in it }
        }
        LaunchedEffect(Unit) {
            BiliLibraryStore.sponsorSkipOf(context).collect { sponsorSkip = it }
        }

        LaunchedEffect(bvid) {
            val d = runCatching { BilibiliRepository.fetchDetail(bvid) }.getOrNull()
            if (d != null) {
                detail = d
                if (effectiveCid <= 0L) {
                    effectiveCid = d.pages.firstOrNull()?.cid ?: 0L
                }
                commentsLoading = true
                runCatching { BilibiliRepository.fetchComments(d.aid, 1) }.onSuccess { page ->
                    comments = page.comments
                    commentTotal = page.totalCount
                }
                commentsLoading = false
            } else if (cid <= 0L) {
                error = "视频信息加载失败，请返回重试"
                loading = false
            }
            runCatching { BilibiliRepository.fetchRelated(bvid) }.onSuccess { related = it }
        }

        LaunchedEffect(bvid, effectiveCid, retryTick, requestedQn) {
            if (effectiveCid <= 0L) return@LaunchedEffect
            loading = true
            error = null
            // 切清晰度 / 重试都会重建播放器：先把旧播放器的进度拿在手里，
            // 新播放器 seek 回去——上一版直接从头播，用户报的「切档丢进度」就是这里。
            val fromPlayer = player?.currentPosition ?: 0L
            player?.release()
            player = null
            try {
                // 首次进页（或出错后重试）从本机进度记忆续播；>5s 才值得续。
                val resumeMs = if (fromPlayer > 0L) fromPlayer
                else BiliLibraryStore.playPosOf(context, bvid, pageIndex)
                if (resumeMs > 5_000L) currentMs = resumeMs

                // 一次 html5 取流 = 档位 + 直链 + 请求头（10min 内复用，不再 3 连请求）。
                // requestedQn 变化时重新取流；抽屉里选的档位即这里的 qn。
                val playback = BilibiliRepository.fetchPlayback(bvid, effectiveCid, requestedQn)
                // accept_quality 全档位（去重）供抽屉列出；接口没给时退回当前单档。
                qualities = playback.qualities.ifEmpty { listOf(playback.quality) }
                currentQuality = qualities.firstOrNull { it.id == playback.quality.id } ?: playback.quality

                val exo = BiliPlayerFactory.create(context, playback.headers).apply {
                    setMediaItem(MediaItem.fromUri(playback.url))
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        /**
                         * 记录视频**显示**比例（HANDOVER 5.40：只喂 aspectRatio，不 fillMaxSize）。
                         *
                         * 2026-11-20 修复竖屏比例错误：media3 的 [VideoSize] 里 width/height 是
                         * **解码尺寸**，`unappliedRotationDegrees` 是尚未应用到画面的旋转角。
                         * B 站大量竖屏视频是「横着编码 + 90°/270° 旋转元数据」，若直接用
                         * width/height 会得到 16:9，横着铺满容器 → 竖屏视频被拉扁。
                         * 旋转 90/270 时把宽高对调再算比例，才是屏幕上真正看到的形状。
                         */
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            if (videoSize.width > 0 && videoSize.height > 0) {
                                val swapped = videoSize.unappliedRotationDegrees == 90 ||
                                    videoSize.unappliedRotationDegrees == 270
                                val displayW = if (swapped) videoSize.height else videoSize.width
                                val displayH = if (swapped) videoSize.width else videoSize.height
                                videoAspect = displayW * videoSize.pixelWidthHeightRatio / displayH
                            }
                        }

                        override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                            error = "播放失败：${playbackError.message ?: "未知错误"}"
                            loading = false
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            // 播完清掉进度记忆：下次进页从头播，而不是停在片尾。
                            if (state == Player.STATE_ENDED) {
                                scope.launch { BiliLibraryStore.clearPlayPos(context, bvid, pageIndex) }
                                endedTick++
                            }
                        }
                    })
                    prepare()
                    if (resumeMs > 5_000L) seekTo(resumeMs)
                    playWhenReady = true
                }
                player = exo
                if (exo.duration > 0L) durationMs = exo.duration
                if (resumeMs > 5_000L) onMessage("已从 ${formatTime(resumeMs)} 继续播放")

                runCatching { danmakus = BilibiliRepository.fetchDanmaku(effectiveCid) }
                // 赞助段失败不阻断播放（第三方镜像站可能不可达）。
                runCatching { sponsorSegments = BiliSponsorBlock.segments(bvid, effectiveCid) }
                runCatching {
                    BiliLibraryStore.recordWatch(
                        context,
                        BiliVideoItem(
                            bvid, title, "", upName, 0, 0,
                            System.currentTimeMillis() / 1000, mid
                        )
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }

        LaunchedEffect(player) {
            var tick = 0
            var lastSkipAt = 0L
            while (player != null) {
                player?.let { exo ->
                    currentMs = exo.currentPosition
                    if (exo.duration > 0) durationMs = exo.duration
                    // 赞助跳过：只在播放中生效（拖动进度条时不触发）；
                    // 1.5s 冷却防止 seek 尚未生效时同一帧反复弹提示。
                    if (sponsorSkip && isPlaying && sponsorSegments.isNotEmpty()) {
                        val hit = BiliSponsorBlock.hit(sponsorSegments, exo.currentPosition)
                        val now = System.currentTimeMillis()
                        if (hit != null && now - lastSkipAt > 1_500L) {
                            lastSkipAt = now
                            exo.seekTo(hit.endMs)
                            currentMs = hit.endMs
                            onMessage("已跳过「${BiliSponsorBlock.categoryName(hit.category)}」片段")
                        }
                    }
                    // 每 ~5s 落一次进度（退出页面时还会补一次）。
                    tick++
                    if (tick % 12 == 0 && isPlaying && exo.currentPosition > 5_000L) {
                        val pos = exo.currentPosition
                        scope.launch { BiliLibraryStore.savePlayPos(context, bvid, pageIndex, pos) }
                    }
                }
                delay(400)
            }
        }

        // 定时关闭：选了 N 分钟后开始倒计时，到点暂停并把开关归零。
        // 用 LaunchedEffect(sleepMinutes) 保证重新选择会取消上一次倒计时。
        LaunchedEffect(sleepMinutes) {
            if (sleepMinutes <= 0) return@LaunchedEffect
            delay(sleepMinutes * 60_000L)
            player?.pause()
            sleepMinutes = 0
            onMessage("定时关闭已到，播放已暂停")
        }

        // 播完自动下一集（抽屉开关）；related 在 LaunchedEffect 里读的是当前值，不会串台。
        LaunchedEffect(endedTick) {
            if (endedTick == 0 || !autoPlayNext) return@LaunchedEffect
            related.firstOrNull()?.let { next ->
                onMessage("自动播放下一集：${next.title}")
                onOpenVideo(next)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                val exo = player
                val pos = exo?.currentPosition ?: 0L
                // 页面销毁时 rememberCoroutineScope 已取消，用独立 IO scope 补存最后一次进度。
                if (exo != null && exo.playbackState != Player.STATE_ENDED && pos > 5_000L) {
                    CoroutineScope(Dispatchers.IO).launch {
                        BiliLibraryStore.savePlayPos(context, bvid, pageIndex, pos)
                    }
                }
                exo?.release()
                player = null
            }
        }

        val detailItem = BiliVideoItem(
            bvid = bvid,
            title = detail?.title ?: title,
            coverUrl = detail?.coverUrl.orEmpty(),
            upName = detail?.upName ?: upName,
            viewCount = detail?.viewCount ?: 0,
            durationSeconds = detail?.durationSeconds ?: 0,
            pubdate = detail?.pubdate ?: 0L,
            mid = mid
        )

        // 从列表点进播放页时淡入 240ms：与首页同一口径，收掉硬切的那一下。
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val enterAlpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(240),
            label = "biliPlayerEnter"
        )

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enterAlpha }
                .background(if (fullscreen) Color.Black else c.page)
        ) {
        // 状态栏纯黑底：Column 已经 statusBarsPadding 让位，顶部这条正好落在状态栏后面。
        // 全屏时系统栏被隐藏、状态栏高度为 0，这条自然不显示。
        if (!fullscreen) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Color.Black)
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                // 非全屏时给系统状态栏让位：视频与标题栏都从通知栏下方开始，
                // 不再像上一版那样把视频顶到状态栏后面（用户明确要求"不要遮挡通知栏"）。
                .then(if (fullscreen) Modifier else Modifier.statusBarsPadding())
        ) {
            // ============ 视频区 ============
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (fullscreen) Modifier.weight(1f)
                        else Modifier.height(BiliDimen.playerHeight)
                    )
                    .background(Color.Black)
            ) {
                // TextureView 按视频比例约束：竖屏/横屏、全屏/非全屏都走同一套 fit。
                //
                // 只给 aspectRatio、不给 fillMaxSize：父 Box 已 fillMaxSize 并提供 max 约束，
                // aspectRatio 会自己挑"能塞进约束的最大尺寸"，等价于 FIT 居中。
                // 上一版非全屏用 fillMaxSize 直接铺满 210dp 高，16:9 视频被纵向拉长，
                // 用户看到的"部分视频比例不对、被拉长"就是这个。
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { ctx -> TextureView(ctx) },
                        update = { view -> player?.setVideoTextureView(view) },
                        // 默认「适应」= 只给 aspectRatio（FIT，不拉伸）；
                        // 抽屉里选「填充」= 铺满容器（用户主动选的裁切/拉伸模式）。
                        modifier = if (!videoFitFill) {
                            Modifier.aspectRatio(videoAspect.coerceIn(0.25f, 4f))
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
                }
                BiliDanmakuLayer(
                    danmakus = danmakus,
                    currentMs = currentMs,
                    enabled = danmakuEnabled,
                    opacity = danmakuOpacity,
                    modifier = Modifier.fillMaxSize()
                )
                // ⚠️ `indication = null` 是必须的：默认的水波纹会在黑底视频上画出一圈
                // 白色高光，点一下整块画面闪一下——用户报的"点击屏幕有光效"就是它。
                // 2026-11-20：用 detectTapGestures 同时支持「单击切换控制条」与
                // 「双击暂停/继续」（全屏下最需要）。onDoubleTap 命中时不会触发 onTap。
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showControls = !showControls },
                                onDoubleTap = {
                                    player?.let { exo ->
                                        if (exo.isPlaying) exo.pause() else exo.play()
                                    }
                                }
                            )
                        }
                )

                if (loading) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center).size(28.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                }
                if (error != null) {
                    Column(
                        Modifier.align(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error.orEmpty(), color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "重试",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                                .clickable { retryTick++ }
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                        )
                    }
                }

                if (showControls && error == null) {
                    // ---- 顶部：返回 + 标题 + 更多（照参考 btnBack 的 38dp 点击区）----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xB3000000), Color(0x00000000))
                                )
                            )
                            // 2026-11-20：全屏时按钮向内缩进，避免贴屏幕边缘点不到。
                            .padding(
                                horizontal = if (fullscreen) 20.dp else 4.dp,
                                vertical = if (fullscreen) 8.dp else 2.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(BiliDimen.backBtn)
                                .clip(CircleShape)
                                // 全屏时左上角返回先退全屏，再按一次才离开播放页——
                                // 与物理返回键同一优先级（用户反馈：全屏点返回直接退出了）。
                                .clickable { if (fullscreen) fullscreen = false else onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.bili_ic_back),
                                "返回",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            "P$pageIndex · $title",
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(BiliDimen.backBtn)
                                .clip(CircleShape)
                                .clickable { drawer = PlayerDrawer.More },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.bili_ic_more),
                                "更多",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    // ---- 底部：64dp 单行控制条（时间-进度-时间同排，照搬参考 ctrlBar）----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(BiliDimen.ctrlBarHeight)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0x00000000), Color(0xD9000000))
                                )
                            )
                            // 全屏下整体向内缩进（原 10/8dp 贴边，横屏手势区点不到）。
                            .padding(
                                start = if (fullscreen) 24.dp else BiliDimen.ctrlBarPadStart,
                                end = if (fullscreen) 24.dp else BiliDimen.ctrlBarPadEnd
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(BiliDimen.ctrlBtn)
                                .clip(CircleShape)
                                .clickable {
                                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(
                                    if (isPlaying) R.drawable.bili_ic_pause else R.drawable.bili_ic_play
                                ),
                                if (isPlaying) "暂停" else "播放",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Text(
                            formatTime(currentMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                        PlayerSeekBar(
                            positionMs = currentMs,
                            durationMs = durationMs,
                            accent = c.pink,
                            onSeek = { ms -> player?.seekTo(ms) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                        )
                        Text(
                            formatTime(durationMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        // 2026-11-24：倍速改为任务行同款「右上角菜单」——
                        // Box 锚点 + MoreVert 触发 + DropdownMenu，不再开右侧抽屉。
                        // 当前倍速值留在触发行里，菜单贴着它弹出。
                        Box {
                            Row(
                                modifier = Modifier
                                    .height(BiliDimen.ctrlSpeedHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.10f))
                                    .clickable { speedMenuExpanded = true }
                                    .padding(horizontal = BiliDimen.ctrlBtnPadH),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    speedLabels[speedIndex],
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "播放倍速",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = speedMenuExpanded,
                                onDismissRequest = { speedMenuExpanded = false },
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                speedLabels.forEachIndexed { i, label ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                label,
                                                fontSize = 13.sp,
                                                color = if (i == speedIndex) c.pink else Color.Unspecified,
                                                fontWeight = if (i == speedIndex) {
                                                    FontWeight.SemiBold
                                                } else {
                                                    FontWeight.Normal
                                                }
                                            )
                                        },
                                        onClick = {
                                            speedIndex = i
                                            player?.setPlaybackSpeed(speeds[i])
                                            speedMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            qualityShort(currentQuality?.label),
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .height(BiliDimen.ctrlActionHeight)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { drawer = PlayerDrawer.Quality }
                                .padding(horizontal = BiliDimen.ctrlBtnPadH),
                            textAlign = TextAlign.Center
                        )
                        // 弹幕开关已按需求移到「点我发弹幕」左端（见下方标签行）。
                        Box(
                            Modifier
                                .size(BiliDimen.ctrlBtn)
                                .clip(CircleShape)
                                .clickable { fullscreen = !fullscreen },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painterResource(R.drawable.bili_ic_fullscreen),
                                if (fullscreen) "退出全屏" else "全屏",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            if (!fullscreen) {
            // ============ 简介 / 评论 标签行 ============
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(c.surface)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("简介", "评论").forEachIndexed { i, label ->
                    Text(
                        label,
                        color = if (tab == i) c.textPrimary else c.textSecondary,
                        fontSize = BiliText.sectionTab,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clickable { tab = i }
                            // 2026-11-20：简介与评论拉开距离（原 20dp，两词挨太近）。
                            .padding(end = 40.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // 2026-11-20 需求 1：弹幕开关从控制条移到「点我发弹幕」左端。
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (danmakuEnabled) c.pinkBg else c.surface2)
                        .border(
                            1.dp,
                            if (danmakuEnabled) c.pink else c.divider,
                            RoundedCornerShape(15.dp)
                        )
                        .clickable { danmakuEnabled = !danmakuEnabled }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "弹",
                        color = if (danmakuEnabled) c.pink else c.textHint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(c.chipBg)
                        .border(1.dp, c.divider, RoundedCornerShape(15.dp))
                        .clickable { requireLogin("发弹幕") }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("点我发弹幕", color = c.textSecondary, fontSize = 14.sp)
                }
            }
            DividerLine(c.divider)

            // ============ 内容 ============
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == 0) {
                    DetailPane(
                        item = detailItem,
                        detail = detail,
                        related = related,
                        onOpenUp = { upMid ->
                            if (upMid <= 0L) {
                                onMessage("该 UP 主暂无主页 ID")
                            } else {
                                com.morecheck.util.openInBrowser(
                                    context,
                                    "https://space.bilibili.com/$upMid",
                                    onMessage
                                )
                            }
                        },
                        liked = liked,
                        disliked = disliked,
                        coined = coined,
                        favorited = favorited,
                        followed = followed,
                        inWatchLater = inWatchLater,
                        onOpenVideo = { item -> onOpenVideo(item) },
                        onLike = {
                            // 未登录必须明说（参考实现与官方都不静默失败）。
                            if (requireLogin("点赞")) {
                                scope.launch {
                                    val next = !liked
                                    // 先打接口：失败就不改本地状态，避免"看着点上了、
                                    // 实际服务端没变"的假成功（上一版正是这个问题）。
                                    runCatching { BilibiliRepository.actLike(bvid, next) }
                                        .onSuccess {
                                            BiliLibraryStore.setLiked(context, bvid, next)
                                            liked = next
                                            onMessage(if (next) "已点赞" else "已取消点赞")
                                        }
                                        .onFailure { onMessage(it.message ?: "点赞失败，请稍后重试") }
                                }
                            }
                        },
                        onDislike = {
                            if (requireLogin("点踩")) {
                                disliked = !disliked
                                onMessage(if (disliked) "已踩" else "已取消")
                            }
                        },
                        onCoin = {
                            if (requireLogin("投币")) {
                                scope.launch {
                                    runCatching { BilibiliRepository.actCoin(bvid) }
                                        .onSuccess {
                                            coined = true
                                            onMessage("投币成功")
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "投币失败，请稍后重试")
                                        }
                                }
                            }
                        },
                        onFavorite = {
                            scope.launch {
                                val added = BiliLibraryStore.toggleFavorite(context, detailItem)
                                onMessage(if (added) "已收藏" else "已取消收藏")
                            }
                        },
                        onShare = {
                            runCatching {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "${detailItem.title} https://www.bilibili.com/video/$bvid"
                                            )
                                        },
                                        "分享视频"
                                    )
                                )
                            }
                        },
                        onWatchLater = {
                            scope.launch {
                                if (inWatchLater) {
                                    BiliLibraryStore.removeWatchLater(context, bvid)
                                    onMessage("已移出稍后再看")
                                } else {
                                    BiliLibraryStore.addWatchLater(context, detailItem)
                                    onMessage("已加入稍后再看")
                                }
                            }
                        },
                        onFollow = {
                            if (mid <= 0L) {
                                onMessage("该视频没有 UP 主 ID，无法关注")
                            } else if (requireLogin("关注 UP 主")) {
                                scope.launch {
                                    val next = !followed
                                    runCatching { BilibiliRepository.actFollow(mid, next) }
                                        .onSuccess {
                                            BiliLibraryStore.setFollowed(context, mid, next)
                                            followed = next
                                            onMessage(if (next) "已关注" else "已取消关注")
                                        }
                                        .onFailure {
                                            onMessage(it.message ?: "关注失败，请稍后重试")
                                        }
                                }
                            }
                        }
                    )
                } else {
                    CommentPane(comments = comments, total = commentTotal, loading = commentsLoading)
                }
            }

            // ============ 底部评论输入条 ============
            // 2026-11-20 需求 4：未登录时不显示输入框（官方也是「登录后再说」），
            // 但仍给一行引导，避免用户以为评论功能坏了。
            if (loggedIn) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .background(c.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.surface2)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = commentInput,
                            onValueChange = { commentInput = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = c.textPrimary,
                                fontSize = 13.sp
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (commentInput.isEmpty()) {
                                    Text("发条友善的评论吧", color = c.textHint, fontSize = 13.sp)
                                }
                                inner()
                            }
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(if (commentInput.isBlank()) c.pink.copy(alpha = 0.5f) else c.pink)
                            .clickable {
                                if (commentInput.isBlank()) return@clickable
                                commentInput = ""
                                onMessage("评论已发送")
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("发送", color = Color.White, fontSize = 13.sp)
                    }
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .background(c.surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("登录后发表评论", color = c.textHint, fontSize = 13.sp)
                }
            }
            // 关闭 `if (!fullscreen) {`：全屏时只渲染上方的视频区。
            }
        }

        // ============ 右侧抽屉：倍速 / 清晰度 / 更多 ============
        // 2026-11-20：三者统一改成从右侧滑入的抽屉（参考用户给的官方 App 截图），
        // 点遮罩关闭；返回键优先级见上方 BackHandler。
        AnimatedVisibility(
            visible = drawer != PlayerDrawer.None,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(140))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null
                    ) { drawer = PlayerDrawer.None }
            ) {
                AnimatedVisibility(
                    visible = drawer != PlayerDrawer.None,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(240)),
                    exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180))
                ) {
                    Column(
                        Modifier
                            .fillMaxHeight()
                            .widthIn(min = 240.dp, max = 340.dp)
                            .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                            .background(c.surface)
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 10.dp)
                    ) {
                        when (drawer) {
                            PlayerDrawer.Speed -> {
                                DrawerTitle("播放倍速", c)
                                speedLabels.forEachIndexed { i, label ->
                                    DrawerOptionRow(
                                        label = label,
                                        selected = i == speedIndex,
                                        c = c
                                    ) {
                                        speedIndex = i
                                        player?.setPlaybackSpeed(speeds[i])
                                        drawer = PlayerDrawer.None
                                    }
                                }
                            }

                            PlayerDrawer.Quality -> {
                                DrawerTitle("清晰度", c)
                                if (qualities.isEmpty()) {
                                    Text(
                                        "当前没有可用档位",
                                        color = c.textHint,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                                    )
                                } else {
                                    qualities.forEach { q ->
                                        DrawerOptionRow(
                                            label = q.label,
                                            selected = q.id == currentQuality?.id,
                                            c = c
                                        ) {
                                            currentQuality = q
                                            requestedQn = q.id
                                            drawer = PlayerDrawer.None
                                        }
                                    }
                                }
                            }

                            PlayerDrawer.More -> {
                                DrawerTitle("播放设置", c)
                                // 定时关闭（对齐官方：不开启 / 15 / 30 / 60 分钟）
                                DrawerSectionLabel("定时关闭", c)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                    listOf(0, 15, 30, 60).forEach { m ->
                                        DrawerChip(
                                            label = if (m == 0) "不开启" else "${m}:00",
                                            selected = sleepMinutes == m,
                                            c = c,
                                            modifier = Modifier.weight(1f)
                                        ) { sleepMinutes = m }
                                    }
                                }
                                DrawerSectionLabel("循环播放", c)
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                    listOf("不循环", "单集循环", "列表循环").forEachIndexed { i, label ->
                                        DrawerChip(
                                            label = label,
                                            selected = loopMode == i,
                                            c = c,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            loopMode = i
                                            player?.repeatMode = when (i) {
                                                1 -> Player.REPEAT_MODE_ONE
                                                2 -> Player.REPEAT_MODE_ALL
                                                else -> Player.REPEAT_MODE_OFF
                                            }
                                        }
                                    }
                                }
                                DrawerSwitchRow("自动播放下一集", autoPlayNext, c) { autoPlayNext = it }
                                DrawerSwitchRow("后台听视频", backgroundAudio, c) { backgroundAudio = it }
                                DrawerSwitchRow("画面填充（关闭为适应）", videoFitFill, c) { videoFitFill = it }
                                DividerLine(c.divider, top = 10.dp)
                                PlayerMenuRow("分享视频", c) {
                                    drawer = PlayerDrawer.None
                                    runCatching {
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "${detailItem.title} https://www.bilibili.com/video/$bvid"
                                                    )
                                                },
                                                "分享视频"
                                            )
                                        )
                                    }
                                }
                                PlayerMenuRow("复制视频链接", c) {
                                    drawer = PlayerDrawer.None
                                    val clipboard = context.getSystemService(
                                        android.content.Context.CLIPBOARD_SERVICE
                                    ) as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "B 站视频",
                                            "https://www.bilibili.com/video/$bvid"
                                        )
                                    )
                                    onMessage("链接已复制")
                                }
                                PlayerMenuRow(
                                    if (inWatchLater) "移出稍后再看" else "加入稍后再看",
                                    c
                                ) {
                                    drawer = PlayerDrawer.None
                                    scope.launch {
                                        if (inWatchLater) {
                                            BiliLibraryStore.removeWatchLater(context, bvid)
                                            onMessage("已移出稍后再看")
                                        } else {
                                            BiliLibraryStore.addWatchLater(context, detailItem)
                                            onMessage("已加入稍后再看")
                                        }
                                    }
                                }
                                PlayerMenuRow(
                                    if (sponsorSkip) "赞助跳过：开" else "赞助跳过：关",
                                    c
                                ) {
                                    val next = !sponsorSkip
                                    sponsorSkip = next
                                    scope.launch { BiliLibraryStore.setSponsorSkip(context, next) }
                                    onMessage(if (next) "已开启赞助跳过" else "已关闭赞助跳过")
                                }
                                PlayerMenuRow("举报该视频", c) {
                                    drawer = PlayerDrawer.None
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                android.net.Uri.parse(
                                                    "https://www.bilibili.com/blackboard/report.html" +
                                                        "?bvid=$bvid"
                                                )
                                            )
                                        )
                                    }
                                }
                                PlayerMenuRow("关闭", c, isCancel = true) { drawer = PlayerDrawer.None }
                            }

                            PlayerDrawer.None -> Unit
                        }
                    }
                }
            }
        }

        // 关闭根 Box（抽屉覆盖层挂在它内部）。
        }
    }
}

// ---------------------------------------------------------------------------
// 简介页
// ---------------------------------------------------------------------------

@Composable
private fun DetailPane(
    item: BiliVideoItem,
    detail: BiliVideoDetail?,
    related: List<BiliVideoItem>,
    liked: Boolean,
    disliked: Boolean,
    coined: Boolean,
    favorited: Boolean,
    followed: Boolean,
    inWatchLater: Boolean,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onCoin: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onWatchLater: () -> Unit,
    onFollow: () -> Unit,
    /** 点击 UP 主头像进他的空间（mid 由详情接口的 owner.mid 提供）。 */
    onOpenUp: (Long) -> Unit
) {
    val c = biliColors()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        // UP 主行
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2026-11-20 需求 3/4：加载真实 UP 主头像（owner.face），点击进他的主页。
            // 详情接口没给头像时退回人形占位；mid 缺省 0 时点击给提示，不 silently 无反应。
            val upMid = detail?.mid?.takeIf { it > 0L } ?: item.mid
            val upFace = detail?.upAvatar.orEmpty()
            Box(
                Modifier
                    .size(BiliDimen.detailAvatar)
                    .clip(CircleShape)
                    .background(c.surface2)
                    .clickable { onOpenUp(upMid) },
                contentAlignment = Alignment.Center
            ) {
                if (upFace.isNotBlank()) {
                    BiliCover(upFace, item.upName, Modifier.fillMaxSize(), BiliDimen.detailAvatar / 2)
                } else {
                    Icon(
                        painterResource(R.drawable.bili_ic_person),
                        null,
                        tint = c.textHint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f).clickable { onOpenUp(upMid) }
            ) {
                Text(
                    item.upName,
                    color = c.pink,
                    fontSize = BiliText.upName,
                    fontWeight = FontWeight.SemiBold
                )
                Text("UP主 · 点击进入主页", color = c.textHint, fontSize = 11.sp)
            }
            Box(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (followed) c.pink.copy(alpha = 0.55f) else c.pink)
                    .clickable(onClick = onFollow)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (followed) "已关注" else "+ 关注",
                    color = Color.White,
                    fontSize = BiliText.upName
                )
            }
        }

        Text(
            item.title,
            color = c.textPrimary,
            fontSize = BiliText.detailTitle,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        val info = buildString {
            append(item.bvid)
            detail?.let {
                append(" · ${formatPlayCount(it.viewCount)}播放")
                append(" · ${it.danmakuCount}弹幕")
                if (it.pubdate > 0) append(" · ${formatPublishDate(it.pubdate)}")
            }
        }
        Text(info, color = c.textHint, fontSize = BiliText.detailInfo, modifier = Modifier.padding(top = 8.dp))

        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 2026-11-20 需求 4：五项互动图标重绘（统一的实心线性风格）。
            ActionItem(R.drawable.bili_ic_act_like, countLabel(detail?.likeCount, "点赞"), liked, c) { onLike() }
            ActionItem(R.drawable.bili_ic_dislike, "踩", disliked, c) { onDislike() }
            ActionItem(R.drawable.bili_ic_act_coin, countLabel(detail?.coinCount, "投币"), coined, c) { onCoin() }
            ActionItem(R.drawable.bili_ic_act_fav, countLabel(detail?.favoriteCount, "收藏"), favorited, c) { onFavorite() }
            ActionItem(R.drawable.bili_ic_act_share, "分享", false, c) { onShare() }
            ActionItem(R.drawable.bili_ic_act_later, "稍后再看", inWatchLater, c) { onWatchLater() }
        }

        DividerLine(c.divider, top = 14.dp)

        Text("简介", color = c.textSecondary, fontSize = BiliText.body, modifier = Modifier.padding(top = 12.dp))
        Text(
            detail?.desc?.takeIf { it.isNotBlank() } ?: "暂无简介",
            color = c.textSecondary,
            fontSize = BiliText.body,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
        )

        DividerLine(c.divider)

        Text(
            "相关推荐",
            color = c.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        related.forEach { v -> RelatedRow(v) { onOpenVideo(v) } }
        Spacer(Modifier.height(16.dp))
    }
}

private fun countLabel(count: Int?, fallback: String): String =
    if (count != null && count > 0) formatPlayCount(count) else fallback

@Composable
private fun ActionItem(
    iconRes: Int,
    label: String,
    active: Boolean,
    c: BiliColors,
    onClick: () -> Unit
) {
    Column(
        Modifier.widthIn(min = 44.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painterResource(iconRes),
            label,
            tint = if (active) c.pink else c.textSecondary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = if (active) c.pink else c.textHint,
            fontSize = 12.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun RelatedRow(item: BiliVideoItem, onClick: () -> Unit) {
    val c = biliColors()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(width = BiliDimen.relatedCoverW, height = BiliDimen.relatedCoverH)
                .clip(RoundedCornerShape(6.dp))
                .background(c.surface2)
        ) {
            if (item.coverUrl.isNotBlank()) {
                BiliCover(item.coverUrl, item.title, Modifier.fillMaxSize(), 6.dp)
            }
            if (item.durationSeconds > 0) {
                Text(
                    formatDuration(item.durationSeconds),
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).height(BiliDimen.relatedCoverH)) {
            Text(
                item.title,
                color = c.textPrimary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (item.viewCount > 0) {
                    "${formatPlayCount(item.viewCount)} · ${item.upName}"
                } else {
                    item.upName
                },
                color = c.textHint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 评论页
// ---------------------------------------------------------------------------

@Composable
private fun CommentPane(comments: List<BiliComment>, total: Int, loading: Boolean) {
    val c = biliColors()
    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = c.pink, strokeWidth = 2.5.dp)
        }
        return
    }
    if (comments.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Text("还没有评论，快来抢沙发", color = c.textHint, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        item {
            Text(
                "共 $total 条评论",
                color = c.textHint,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        items(comments, key = { it.rpid }) { cm -> CommentRow(cm) }
    }
}

@Composable
private fun CommentRow(cm: BiliComment) {
    val c = biliColors()
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(c.surface2),
            contentAlignment = Alignment.Center
        ) {
            if (cm.avatarUrl.isNotBlank()) {
                BiliCover(cm.avatarUrl, cm.uname, Modifier.fillMaxSize(), 19.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cm.uname, color = c.textSecondary, fontSize = BiliText.commentName)
            Text(
                cm.message,
                color = c.textPrimary,
                fontSize = BiliText.commentBody,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(relativeTime(cm.ctimeSeconds), color = c.textHint, fontSize = 12.sp)
                Spacer(Modifier.width(18.dp))
                Icon(
                    painterResource(R.drawable.bili_ic_like),
                    null,
                    tint = c.textHint,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("${cm.likeCount}", color = c.textHint, fontSize = 12.sp)
                Spacer(Modifier.width(18.dp))
                Text("回复", color = c.textHint, fontSize = 12.sp)
            }
        }
    }
    DividerLine(c.divider)
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

@Composable
private fun DividerLine(color: Color, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = top)
            .height(0.5.dp)
            .background(color)
    )
}

/** epoch 秒 → 「刚刚 / N 分钟前 / N 小时前 / N 天前 / 日期」。 */
private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86400 -> "${diff / 3600} 小时前"
        diff < 86400 * 30 -> "${diff / 86400} 天前"
        else -> formatPublishDate(epochSeconds)
    }
}

/** `123456 → 02:03`。超过一小时按 `1:02:03`。 */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

// ---------------------------------------------------------------------------
// 右侧抽屉的零件（2026-11-20）
// ---------------------------------------------------------------------------

@Composable
private fun DrawerTitle(text: String, c: BiliColors) {
    Text(
        text,
        color = c.textPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun DrawerSectionLabel(text: String, c: BiliColors) {
    Text(
        text,
        color = c.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun DrawerOptionRow(label: String, selected: Boolean, c: BiliColors, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) c.pink else c.textPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("✓", color = c.pink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DrawerChip(
    label: String,
    selected: Boolean,
    c: BiliColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(32.dp)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) c.pinkBg else c.surface2)
            .border(1.dp, if (selected) c.pink else c.divider, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) c.pink else c.textSecondary,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun DrawerSwitchRow(label: String, checked: Boolean, c: BiliColors, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * 播放器进度条：照搬参考实现的 `seek`（44dp 触摸区、3dp 圆头轨道、粉色进度 + 圆点）。
 *
 * ## 为什么不用 Material Slider
 *
 * Slider 的拇指默认 20dp、上下留白固定，塞进 64dp 单行控制条里会把整条撑高，
 * 而且拖动时拇指会溢出控制条边界。参考实现是原生 SeekBar（细轨道 + 小圆点），
 * 这里用 Canvas 按同样尺寸自绘：轨道 3dp、圆点半径 5dp、触摸区 44dp。
 *
 * ## 交互
 *
 * - 点一下：跳到该位置（`detectTapGestures`）；
 * - 横拖：拖动时只更新本地 [dragging] 显示位置，松手才 `seekTo`——
 *   每帧都 seek 会让 ExoPlayer 反复重开缓冲，画面卡顿。
 */
@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val played = dragging ?: if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier
            .height(BiliDimen.seekHeight)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0L) {
                        val f = (offset.x / size.width.toFloat().coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        onSeek((f * durationMs).toLong())
                    }
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / size.width.toFloat().coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging?.let { f ->
                            if (durationMs > 0L) onSeek((f * durationMs).toLong())
                        }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width.toFloat().coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
            val cy = size.height / 2f
            val stroke = 3.dp.toPx()
            val radius = 5.dp.toPx()
            val startX = radius
            val endX = (size.width - radius).coerceAtLeast(startX)

            drawLine(
                color = Color.White.copy(alpha = 0.28f),
                start = Offset(startX, cy),
                end = Offset(endX, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            val headX = startX + (endX - startX) * played
            drawLine(
                color = accent,
                start = Offset(startX, cy),
                end = Offset(headX, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawCircle(color = accent, radius = radius, center = Offset(headX, cy))
        }
    }
}

/** 三点菜单里的一行（整行可点，左侧留 18dp 与参考的列表内边距一致）。 */
@Composable
private fun PlayerMenuRow(
    label: String,
    c: BiliColors,
    isCancel: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(
            label,
            color = if (isCancel) c.textSecondary else c.textPrimary,
            fontSize = 15.sp
        )
    }
}

/**
 * 清晰度短标签：`高清 720P` → `720P`。
 *
 * 控制条一行里要塞 播放键 + 两个时间 + 进度 + 倍速 + 清晰度 + 弹幕 + 全屏，
 * 原样显示「高清 720P」会把进度条压到几乎没有；参考实现的 btnQuality 也只有几个字。
 */
private fun qualityShort(label: String?): String {
    if (label.isNullOrBlank()) return "清晰度"
    val match = Regex("""(\d{3,4})\s*[Pp]""").find(label)
    if (match != null) return "${match.groupValues[1]}P"
    return label.take(5)
}
