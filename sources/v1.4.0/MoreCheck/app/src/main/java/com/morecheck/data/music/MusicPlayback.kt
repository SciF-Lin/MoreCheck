@file:Suppress("UnsafeOptInUsageError") // media3 的 UnstableApi 不是 Kotlin RequiresOptIn 标记，lint 按误报抑制

package com.morecheck.data.music

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.morecheck.data.SettingsRepository
import com.morecheck.data.net.MusicRepository
import com.morecheck.data.net.MusicTrack
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放模式（需求 C5）。
 *
 * 顺序写死成"用户点一次切下一个"的循环，与主流播放器一致：
 * 顺序 → 单曲循环 → 列表循环 → 随机 → 顺序。
 */
enum class PlayMode(val label: String) {
    /** 顺序播放：放完最后一首就停。 */
    ORDER("顺序播放"),

    /** 单曲循环：自动播放时回到开头，手动「下一首」仍然换歌。 */
    SINGLE_LOOP("单曲循环"),

    /** 列表循环：放完最后一首回到第一首（默认）。 */
    LIST_LOOP("列表循环"),

    /** 随机播放：自动与手动「下一首」都随机（尽量不连续重复同一首）。 */
    SHUFFLE("随机播放");

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 播放器对外的全部状态（界面只读这个，不直接摸 ExoPlayer）。
 *
 * 为什么要一个 data class 而不是散装变量：播放器页、迷你播放条、通知栏三处
 * 都要显示"在播什么、播到哪、是不是在缓冲"，散装状态必然出现三处不一致。
 */
data class PlaybackState(
    /** 当前曲目（可能已经在播、正在取链、或暂停）。 */
    val track: MusicTrack? = null,
    /** 当前队列（搜索结果 / 收藏 / 最近播放 / 已缓存，取决于从哪一处发起的播放）。 */
    val queue: List<MusicTrack> = emptyList(),
    /** [track] 在 [queue] 里的下标，-1 表示不在队列里。 */
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 实际时长明显短于标注时长 → 是试听片段。 */
    val previewOnly: Boolean = false,
    val mode: PlayMode = PlayMode.LIST_LOOP,
    /** 定时停止的结束时间（0 = 没开）。 */
    val sleepEndAtMs: Long = 0L,
    /** 正在取链的曲目 id（null = 没有在取链）。 */
    val pendingTrackId: String? = null,
    /** 一句话错误提示（取链失败 / 播放失败），界面显示后由用户下一次操作清掉。 */
    val error: String? = null
) {
    val hasTrack: Boolean get() = track != null
}

/**
 * 音乐的**唯一播放状态持有者**（2026-11-08 需求 C1/C2/C3/C5/C8 + 后台播放）。
 *
 * ## 为什么从 Composable 里搬出来
 *
 * 上一版把 `ExoPlayer` 建在 `remember` 里、播放状态挂在 `remember { mutableStateOf }` 上，
 * 于是：离开页面播放器就被 `release()`、通知栏拿不到正在播什么、换个页面状态就丢。
 * 现在改成本对象持有：
 *
 * - **界面**通过 [state] 订阅，只发意图（[toggle] / [next] / [seekTo] …）；
 * - **通知栏与锁屏**通过 [MusicPlaybackService] 里的 `MediaSession` 读同一个 player；
 * - **进程内唯一**：页面重建、旋转屏幕都不会中断播放。
 *
 * ## 后台播放开关（设置 → 音乐）
 *
 * [SettingsRepository.musicBackgroundPlayback] 为 true 时，播放开始会拉起前台服务
 * （通知栏卡片 + 锁屏控制）；为 false 时只在页面里响，离开页面就暂停并停掉服务。
 * 注意：**播放器实例不销毁**——它的内存占用很小，而"释放"会牵出
 * MediaSession 仍持有旧实例的时序问题（会直接崩）。停服务 + `stop()` 已经足够。
 *
 * ## 为什么队列是自己维护的，不用 `setMediaItems`
 *
 * 每首歌的播放地址都要**异步解析**（酷狗要拿 hash 换直链，还得考虑会员与缓存），
 * 一次性把整队 URL 都解析出来既慢又浪费（大部分歌不会播）。
 * 所以队列只是 `List<MusicTrack>`，点到哪首才解析哪首；代价是没有无缝衔接
 * （换歌有几百毫秒取链时间），对这个应用够用。
 */
object MusicPlayback {

    private const val TAG = "MusicPlayback"
    private const val TICK_MS = 500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var accountStore: KugouAccountStore? = null
    private var cache: MusicCache? = null
    private var history: MusicHistory? = null

    private var account: KugouAccount? = null

    /** 设置里的「后台播放」开关；false 时离页即停。 */
    @Volatile
    private var backgroundEnabled: Boolean = true

    private var tickerJob: Job? = null
    private var playJob: Job? = null

    /** 一次解析请求的代际号：快速连点下一首时，丢弃过期的解析结果。 */
    private var playGeneration: Int = 0

    fun isPlaying(): Boolean = player?.isPlaying == true

    /** 「设置 → 音乐 → 后台播放」当前是否开着（通知权限提示只在开着时才有意义）。 */
    fun backgroundPlaybackEnabled(): Boolean = backgroundEnabled

    /**
     * 缓存仓库（界面用它显示"已缓存"状态、列出已缓存歌曲）。
     *
     * 用**同一个实例**而不是界面自己 `new` 一个：`cachedIds` 是实例内的 StateFlow，
     * 各建一份会出现"这一页显示已缓存、那一页还显示没缓存"。
     * `init` 之后才非空；界面进入时都会先调 [init]。
     */
    val cacheStore: MusicCache? get() = cache

    /** 最近播放仓库（同上，界面用它渲染「最近播放」标签）。 */
    val historyStore: MusicHistory? get() = history

    /**
     * 初始化（幂等）。在 `MoreCheckApp.onCreate` 与页面首次进入时都可以调。
     *
     * 只挂两条收集：账号变化（取链要用）与后台播放开关（决定要不要前台服务）。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        appContext = app
        accountStore = KugouAccountStore(app)
        cache = MusicCache(app)
        history = MusicHistory(app)

        val settings = SettingsRepository(app)
        scope.launch {
            settings.musicBackgroundPlayback.collect { enabled ->
                backgroundEnabled = enabled
                if (!enabled) {
                    // 关掉开关的瞬间：如果正在放，先暂停再停服务，别让用户以为开关没生效。
                    MusicPlaybackService.stop(app)
                }
            }
        }
        scope.launch {
            accountStore?.account?.collect { account = it }
        }
        // 播放器在这里就建好：MediaSessionService 启动时能立刻拿到实例，
        // 不需要"先播一次把播放器热起来"。
        ensurePlayer(app)
    }

    /**
     * 拿到共享播放器（服务与界面共用）。
     *
     * 必须在**有 Looper 的线程**调用（主线程），ExoPlayer 的构造有这个硬性要求。
     */
    fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            // 播放地址可能 302 到 http 的 CDN：默认拒绝跨协议跳转，会把 302 当成错误。
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(mapOf("Referer" to "https://music.163.com/"))

        // ⚠️ 必须再包一层 DefaultDataSource（2026-11-08 实测踩到，离线播放直接 Source error）：
        //
        // 直接把 `DefaultHttpDataSource.Factory` 交给 `DefaultMediaSourceFactory`，
        // 它对**所有** URI 都用 HTTP 打开——本地缓存是 `file://`，HTTP 数据源打不开，
        // 于是"已缓存的歌离线播不了"，报错只有一句 `Source error`。
        //
        // `DefaultDataSource.Factory(context, http)` 会按 scheme 分流：
        // file/content/asset 走本地，http/https 才用上面那个带 Referer 的工厂。
        val dataSource = DefaultDataSource.Factory(context.applicationContext, http)

        val built = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                // 拔耳机 / 断蓝牙时暂停，而不是继续外放（默认行为是继续放）。
                setHandleAudioBecomingNoisy(true)
                // 息屏后继续拉流（需要 WAKE_LOCK 权限，见清单注释）。
                setWakeMode(C.WAKE_MODE_NETWORK)
            }
        attachListener(built)
        player = built
        return built
    }

    // ---- 对外意图 ------------------------------------------------------------

    /**
     * 用一整个队列开始播放（从搜索结果 / 收藏 / 最近播放 / 已缓存点进来）。
     *
     * @param startIndex 从队列里的第几首开始；越界时按 0 处理。
     */
    fun playQueue(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        _state.value = _state.value.copy(queue = tracks, error = null)
        playAt(index)
    }

    /** 播放 / 暂停。没在播且有队列时按"从第一首开始"处理。 */
    fun toggle() {
        val current = player ?: return
        val s = _state.value
        if (s.track == null) {
            if (s.queue.isNotEmpty()) playAt(0)
            return
        }
        if (current.isPlaying) {
            current.pause()
            return
        }
        // ⚠️ 出错后 / 从未 prepare 成功时，`play()` 不会自动重试（播放器停在 IDLE，
        // 点了没反应也没有日志——2026-11-08 实测就卡在这里）。
        // 这两种情况要**重新走一遍取链**，而不是干等。
        val needsReload = current.playerError != null ||
            current.playbackState == Player.STATE_IDLE
        if (needsReload) {
            if (s.index in s.queue.indices) {
                playAt(s.index)
            } else {
                current.prepare()
                current.play()
            }
            return
        }
        // 播完最后一首（STATE_ENDED）后按播放键：从头重放这一首。
        if (current.playbackState == Player.STATE_ENDED) current.seekTo(0)
        current.play()
        if (backgroundEnabled) ensureService()
    }

    /** 下一首（用户点按）。单曲循环模式下也换歌——循环只作用于"自动播完"。 */
    fun next() = skipTo(forward = true, userInitiated = true)

    /** 上一首（用户点按）。 */
    fun prev() = skipTo(forward = false, userInitiated = true)

    /** 拖动进度条。 */
    fun seekTo(positionMs: Long) {
        val current = player ?: return
        val target = positionMs.coerceIn(0L, current.duration.coerceAtLeast(0L))
        current.seekTo(target)
        _state.value = _state.value.copy(positionMs = target)
    }

    /** 切换播放模式（用户点按，循环切换）。 */
    fun cycleMode() {
        val next = _state.value.mode.next()
        _state.value = _state.value.copy(mode = next)
    }

    /** 定时停止：分钟数；传 0 取消。 */
    fun startSleepTimer(minutes: Int) {
        _state.value = _state.value.copy(
            sleepEndAtMs = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        )
    }

    fun cancelSleepTimer() {
        _state.value = _state.value.copy(sleepEndAtMs = 0L)
    }

    /**
     * 音乐流程内的页面**挂载**（音乐列表页 / 播放器 / 音乐账号页各自调一次）。
     *
     * 为什么需要计数：`发现 → 音乐 → 点头像进账号页` 时列表页会被销毁，
     * 若一销毁就判"离页"、在后台播放关着的情况下暂停，用户只是想改个设置，
     * 回来发现歌停了。所以离页暂停延后一小段时间再判，期间若有新页面挂上就取消。
     */
    private var attachedScreens = 0
    private var detachJob: Job? = null

    fun onScreenAttached() {
        attachedScreens++
        detachJob?.cancel()
        detachJob = null
    }

    /**
     * 离开音乐页时调用。
     *
     * 后台播放开着 → 什么都不做（继续播，通知栏还在）；
     * 关着 → **延迟 700ms** 后若仍没有音乐页面在前台，就暂停并停掉前台服务，
     * 等价于上一版的"离页即停"，但不会因为跳一个子页面就断掉。
     */
    fun onScreenDetached() {
        attachedScreens = (attachedScreens - 1).coerceAtLeast(0)
        detachJob?.cancel()
        detachJob = scope.launch {
            delay(DETACH_GRACE_MS)
            if (attachedScreens > 0) return@launch
            if (backgroundEnabled) return@launch
            player?.pause()
            appContext?.let { MusicPlaybackService.stop(it) }
        }
    }

    /** 离页到暂停之间的宽限（毫秒）：够一次导航转场把手交给子页面。 */
    private const val DETACH_GRACE_MS = 700L

    /** 彻底停掉（清空当前曲目与队列）。 */
    fun stop() {
        playGeneration++
        playJob?.cancel()
        player?.apply {
            pause()
            stop()
            clearMediaItems()
        }
        _state.value = _state.value.copy(
            track = null,
            queue = emptyList(),
            index = -1,
            playing = false,
            positionMs = 0L,
            durationMs = 0L,
            pendingTrackId = null,
            previewOnly = false
        )
        appContext?.let { MusicPlaybackService.stop(it) }
    }

    /**
     * 把一首歌下载到本机（E2 离线缓存）。
     *
     * 顺序：取远程地址 → 流式写文件 → 落元信息。失败返回 `Result.failure`，
     * 由界面给出"下载失败"而不是静默——用户点了下载按钮，必须知道成没成。
     *
     * ⚠️ 下载的直链可能是 http，这里统一升 https（与播放同一条规则）。
     */
    suspend fun downloadForOffline(track: MusicTrack): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val store = cache ?: error("缓存尚未初始化")
            val url = resolveRemoteUrl(track) ?: error("取不到播放地址")
            store.download(track, url)
        }
    }

    /** 删掉一首歌的本地缓存。 */
    suspend fun removeDownload(track: MusicTrack) {
        withContext(Dispatchers.IO) { runCatching { cache?.remove(track) } }
    }

    /** 清掉一次性错误提示（界面展示过之后调）。 */
    fun clearError() {
        if (_state.value.error != null) _state.value = _state.value.copy(error = null)
    }

    // ---- 内部实现 ------------------------------------------------------------

    private fun playAt(index: Int) {
        val track = _state.value.queue.getOrNull(index) ?: return
        val generation = ++playGeneration
        playJob?.cancel()
        _state.value = _state.value.copy(
            track = track,
            index = index,
            playing = false,
            buffering = true,
            positionMs = 0L,
            durationMs = 0L,
            previewOnly = false,
            pendingTrackId = track.id,
            error = null
        )
        playJob = scope.launch {
            val url = resolveUrl(track)
            // 期间用户又点了别的歌：这次结果作废，避免"点了 A 播了 B"。
            if (generation != playGeneration) return@launch
            if (url.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    pendingTrackId = null,
                    buffering = false,
                    playing = false,
                    error = if (account == null) {
                        "《${track.name}》需要会员或暂无版权。登录酷狗账号后可再试一次"
                    } else {
                        "《${track.name}》无法播放：账号权益不足或暂无版权"
                    }
                )
                player?.stop()
                return@launch
            }
            val current = ensurePlayer(appContext ?: return@launch)
            current.setMediaItem(buildMediaItem(track, url))
            current.prepare()
            current.play()
            _state.value = _state.value.copy(pendingTrackId = null, buffering = false)
            if (backgroundEnabled) ensureService()
            runCatching { history?.record(track) }
        }
    }

    /**
     * 取播放地址：**本地缓存优先**，其次是账号授权链路，最后公开接口/跨源兜底。
     *
     * 缓存优先是离线播放的关键——飞行模式下这一条必须命中，否则"离线可听"不成立。
     */
    private suspend fun resolveUrl(track: MusicTrack): String? {
        cache?.fileFor(track)?.let { file ->
            return Uri.fromFile(file).toString()
        }
        return resolveRemoteUrl(track)
    }

    /** 只取**远程**地址（跳过本地缓存）——下载到本机时用这个，否则会"下载自己"。 */
    private suspend fun resolveRemoteUrl(track: MusicTrack): String? {
        val loggedIn = account
        var url = runCatching { MusicRepository.resolvePlayUrl(track, loggedIn) }.getOrNull()

        // 授权码（auth）会过期：失败时清掉缓存重试一次，第二次会重新走 user_verify。
        if (url.isNullOrBlank() && loggedIn != null && loggedIn.auth.isNotBlank()) {
            runCatching { accountStore?.updateAuth("") }
            url = runCatching {
                MusicRepository.resolvePlayUrl(track, loggedIn.copy(auth = ""))
            }.getOrNull()
        }
        return MusicRepository.forceHttps(url)
    }

    private fun buildMediaItem(track: MusicTrack, url: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .apply {
                MusicRepository.forceHttps(track.coverUrl)?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        return MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun skipTo(forward: Boolean, userInitiated: Boolean) {
        val s = _state.value
        if (s.queue.isEmpty()) return
        // 自动播完的单曲循环：不换歌，回到开头。
        if (!userInitiated && s.mode == PlayMode.SINGLE_LOOP) {
            seekTo(0L)
            player?.play()
            return
        }
        val index = when {
            s.mode == PlayMode.SHUFFLE && s.queue.size > 1 -> {
                var random = s.index
                // 最多试几次，避免队列只有两首时死循环。
                repeat(8) { if (random == s.index) random = Random.nextInt(s.queue.size) }
                if (random == s.index) (s.index + 1) % s.queue.size else random
            }
            forward -> s.index + 1
            else -> s.index - 1
        }
        when {
            index in s.queue.indices -> playAt(index)
            // 顺序播放：到头/到尾就停，不回绕。
            s.mode == PlayMode.ORDER -> {
                player?.pause()
                if (forward) {
                    _state.value = s.copy(playing = false, positionMs = 0L)
                    player?.seekTo(0)
                } else {
                    playAt(0)
                }
            }
            // 列表循环：首尾相接。
            else -> playAt(if (forward) 0 else s.queue.lastIndex)
        }
    }

    private fun attachListener(exo: ExoPlayer) {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(playing = isPlaying)
                if (isPlaying) {
                    startTicker()
                } else {
                    stopTicker()
                    // 暂停时把最后一次进度记下来，避免界面停在 0。
                    _state.value = _state.value.copy(
                        positionMs = exo.currentPosition.coerceAtLeast(0L)
                    )
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _state.value = _state.value.copy(buffering = true)
                    }
                    Player.STATE_READY -> {
                        _state.value = _state.value.copy(
                            buffering = false,
                            durationMs = exo.duration.coerceAtLeast(0L)
                        )
                    }
                    Player.STATE_ENDED -> {
                        _state.value = _state.value.copy(buffering = false, playing = false)
                        skipTo(forward = true, userInitiated = false)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // ⚠️ 这里**不删缓存**（2026-11-08 实测教训）：
                // 之前写成"播放出错就删掉本地文件"，结果一次网络抖动 / 一次 Source error
                // 就把用户下载的歌删了——用户完全不知道发生了什么，只觉得"缓存老是丢"。
                // 坏文件可以点列表里的「已缓存」图标手动删，不该由错误路径代替用户决定。
                _state.value = _state.value.copy(
                    playing = false,
                    buffering = false,
                    error = "该歌曲无法在线播放（可能受版权或会员限制）"
                )
            }
        })
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /** 每 500ms 同步一次进度与定时器。 */
    private fun tick() {
        val exo = player ?: return
        val s = _state.value
        val duration = exo.duration.coerceAtLeast(0L)
        val expected = s.track?.durationMs ?: 0L
        _state.value = s.copy(
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            // 实际时长不到标注的 80% → 会员曲目的试听片段。
            previewOnly = expected > 0L && duration > 0L && duration < expected * 8 / 10,
            sleepEndAtMs = s.sleepEndAtMs
        )
        val sleepEnd = s.sleepEndAtMs
        if (sleepEnd > 0L && System.currentTimeMillis() >= sleepEnd) {
            exo.pause()
            _state.value = _state.value.copy(sleepEndAtMs = 0L)
        }
    }

    private fun ensureService() {
        val context = appContext ?: return
        MusicPlaybackService.start(context)
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
