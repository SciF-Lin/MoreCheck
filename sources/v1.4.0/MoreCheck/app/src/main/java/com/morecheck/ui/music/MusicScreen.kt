package com.morecheck.ui.music

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.music.FavoriteGroup
import com.morecheck.data.music.MusicFavorites
import com.morecheck.data.music.MusicPlayback
import com.morecheck.util.ErrorLogStore
import com.morecheck.data.music.PlaybackState
import com.morecheck.data.net.MusicLyrics
import com.morecheck.data.net.MusicRepository
import com.morecheck.data.net.MusicTrack
import com.morecheck.di.appContainer
import com.morecheck.ui.common.ChoiceDialog
import com.morecheck.ui.common.TextInputDialog
import com.morecheck.util.findActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 列表的五个视图。
 *
 * [HOT] 是**默认视图**（2026-11-08 需求 5）：进页面就有热门榜单与推荐歌曲，
 * 搜索后才切到 [RESULTS]；其余三个是本机数据。
 */
private enum class MusicTab(val label: String) {
    HOT("热门榜单"),
    RESULTS("搜索结果"),
    FAVORITES("我的收藏"),
    RECENT("最近播放"),
    CACHED("已缓存")
}

/** 三张榜单各自对应一组搜索词；「换一批」在组内轮转，避免每次都是同一批歌。 */
private val HOT_BOARDS = listOf("飙升榜", "新歌榜", "热歌榜")
private val HOT_KEYWORDS = listOf(
    listOf("热门歌曲", "流行金曲", "华语热歌"),
    listOf("新歌", "2026 新歌", "最新歌曲"),
    listOf("热歌", "经典老歌", "抖音热歌")
)

/**
 * 「发现 → 音乐」（2026-11-08 按设计稿重做视觉；2026-11-09 收敛入口与返回键）。
 *
 * ## 版面（自上而下）
 *
 * 1. **顶栏**：返回圆键 + 搜索胶囊 + 刷子键（主题）。
 *    切到「我的」时搜索胶囊换成「我的」标题，返回圆键回「发现」；
 *    **右上角不再有头像入口**——「我的」已由底栏常驻入口承接，两个入口既重复
 *    又挤掉搜索宽度（未登录时那个耳机图标还容易被误当成后台播放开关）；
 * 2. **主卡**：没在播时是「红心 Radio」（一键随机播收藏），在播时**就地变成正在播放**——
 *    小唱片 + 歌名 + 播放/暂停圆键；点卡片进播放页；
 * 3. **榜单区**：默认「热门榜单」（飙升榜 / 新歌榜 / 热歌榜 + 换一批），
 *    搜索后才切到「搜索结果」；下面一张玻璃大卡，行内是
 *    「排名 01 + 封面 + 歌名歌手 + 收藏 + 时长 + 播放圆键」；
 * 4. **底栏**：发现 / 中央播放键 / 我的。
 *    - 中央键在播时直接显示**当前歌曲封面**并缓慢旋转，暂停时压暗显示播放三角；
 *      点它进播放页；没歌时是强调色播放键，点了随机播收藏；
 *    - 右「我的」是**同一页的第二段内容**（[MusicAccountContent] 内嵌），
 *      不是二级页面也不是整屏覆盖层：标题栏与底栏都还在，只是内容区换掉。
 *
 * ## 2026-11-09 三个开关 / 观感项
 *
 * - **主卡实时歌词**（开发者选项 → 音乐，默认开）：在「歌名 · 歌手」下面多一行当前歌词，
 *   随播放进度换句、交叉淡入；没有歌词时这一行不出现（曲目信息始终保留）。
 * - **退出二次确认**（开发者选项 → 音乐，默认关）：离开本页前弹确认框，
 *   返回箭头与系统返回键共用同一个出口 [requestExit]。
 * - **系统栏沉浸**：导航栏 / 状态栏的图标明暗跟随音乐调色板（而不是应用主题），
 *   底栏底色铺到屏幕最下沿；离开本页时把系统栏还原。
 *
 * ## 数据与逻辑没有变
 *
 * 搜索、收藏、最近播放、离线缓存仍然走原来的仓库（[MusicRepository] / [MusicFavorites] /
 * `MusicHistory` / `MusicCache`），播放仍然交给进程内单例 [MusicPlayback]。
 * 这轮只换皮与重排入口，不改任何数据契约。
 */
@Composable
fun MusicScreen(onBack: () -> Unit) {
    val palette = rememberMusicPalette()
    val context = LocalContext.current
    // 幂等：进页面先确保控制器起来了（Application 里也会调一次）。
    MusicPlayback.init(context)
    val scope = rememberCoroutineScope()

    // ⚠️ 2026-11-12：「进入音乐页前的确认弹窗」已移到**发现页**（DiscoveScreen 里
    // 的 MusicEntryConfirmDialog），点确认才导航到本页。本页不再持有任何门禁/弹窗状态，
    // 一进来就直接渲染内容——这才不会出现"先进页面、背景一片黑、再弹窗"的观感。
    // 音乐开关（2026-11-09，2026-11-14 调整）：退出确认现在也能在
    // 「音乐 → 我的 → 音乐设置」里开关，两处写的是同一枚 DataStore 值，
    // 所以这里读**原始值**而不是 `master && sub` 的生效值——否则关掉开发者选项
    // 总开关后，用户在音乐设置里明明打开了却不会弹确认，看起来像设置坏了。
    val developerOptions = remember(context) {
        context.applicationContext.appContainer.developerOptionsRepository
    }
    val exitConfirmEnabled by developerOptions.musicExitConfirmSwitch
        .collectAsStateWithLifecycle(initialValue = false)
    val entryLyricsEnabled by developerOptions.musicEntryLyricsEnabled
        .collectAsStateWithLifecycle(initialValue = false)

    // 系统栏（导航栏 / 状态栏）的图标明暗跟随**音乐调色板**，而不是应用主题。
    //
    // 本页底色来自 `MusicPalette`，与应用 `MaterialTheme` 完全解耦：音乐选深色风格、
    // 应用是浅色主题时，系统导航栏会按"浅色页面"给深色图标，压在深色底上几乎看不见，
    // 用户看到的就是"底部一条黑边、按钮消失了"——这就是"不沉浸"的由来。
    // 这里只在音乐页存续期间改写，离开时原样还回去（与 DeAiScreen / 番茄钟同一套做法）。
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, palette.isDark) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = !palette.isDark
            controller.isAppearanceLightNavigationBars = !palette.isDark
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    val playback by MusicPlayback.state.collectAsStateWithLifecycle()
    val favorites = remember(context) { MusicFavorites(context) }
    // 2026-11-09 收藏分组：曲目列表仍用 favoriteList（其它地方只关心"收藏了哪些"），
    // 收藏页额外要 entry（含 groupId）与分组表。
    val favoriteList by favorites.tracks.collectAsStateWithLifecycle(initialValue = emptyList())
    val favoriteEntries by favorites.favorites.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    val favoriteGroups by favorites.groups.collectAsStateWithLifecycle(
        initialValue = listOf(FavoriteGroup.DEFAULT)
    )
    /** 收藏页当前选中的分组；null = 全部。 */
    var favGroupId by remember { mutableStateOf<String?>(null) }
    /** 新建 / 重命名分组的输入框弹窗（同一个弹窗两用，靠 [editingGroup] 区分）。 */
    var showGroupDialog by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<FavoriteGroup?>(null) }
    var groupDraft by remember { mutableStateOf("") }
    /** 正在为哪首歌选择分组（null = 没打开）。 */
    var movingTrack by remember { mutableStateOf<MusicTrack?>(null) }

    // 缓存与最近播放用控制器里的同一份实例，避免"这一页已缓存、那一页没缓存"。
    val cache = MusicPlayback.cacheStore
    val cachedIdsFlow = remember(cache) { cache?.cachedIds ?: MutableStateFlow(emptySet()) }
    val cachedIds by cachedIdsFlow.collectAsStateWithLifecycle()
    val history = MusicPlayback.historyStore
    val recentFlow = remember(history) { history?.recent ?: flowOf(emptyList()) }
    val recentList by recentFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    var showPlayer by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    /**
     * 「我的」是**同一页里的第二段内容**（2026-11-09 改为内嵌）：
     * 不再是整屏滑上来的覆盖层、也没有自己的顶栏与返回栈，
     * 只是把列表区换成账号与音乐设置，底栏仍然在，返回箭头回「发现」。
     */
    var showMine by remember { mutableStateOf(false) }
    /** 退出确认弹窗（开发者选项里「退出音乐时二次确认」打开时才会出现）。 */
    var showExitConfirm by remember { mutableStateOf(false) }

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(MusicTab.HOT) }

    // 热门榜单：进页面自动拉一批真实可播的歌；失败时退回本机最近播放 + 收藏。
    var hotTracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var hotLoading by remember { mutableStateOf(false) }
    var hotBoard by remember { mutableStateOf(0) }
    var hotRound by remember { mutableStateOf(0) }
    /** 一次性提示：文字 + 是否是错误（决定颜色）。 */
    var flash by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    // 最近一次下载失败的歌：横幅上给「重试 / 关闭」，不让错误提示常驻无出口。
    var retryTrack by remember { mutableStateOf<MusicTrack?>(null) }
    /** 正在下载的曲目 id（显示转圈 / 防重复点击）。 */
    var downloading by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 正在删除缓存的曲目 id（2026-11-14 需求 4：播放页那颗键换飘走的垃圾桶）。 */
    var removingCache by remember { mutableStateOf<Set<String>>(emptySet()) }

    // ---- 下载结果提示（2026-11-14 需求 4）----
    //
    // 与上面的 `flash`（页面内的一行文字）分开：下载是在播放页点按钮发起的，
    // 那里根本看不到列表下方那行字。这里做成**屏幕底部居中的白色胶囊**，
    // 盖在播放页与列表之上，3.5 秒后自己消失（2.2 秒实测偏短，用户常错过）。
    var toast by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var toastToken by remember { mutableIntStateOf(0) }
    fun showToast(text: String, isError: Boolean) {
        toast = text to isError
        toastToken++
    }
    LaunchedEffect(toastToken) {
        if (toast != null) {
            delay(3500)
            toast = null
        }
    }

    // 页面挂载 / 离开：后台播放开着就继续，关着则在离开后（留 700ms 宽限）暂停并停服务。
    // 用成对的 attach / detach 计数，跳去「音乐账号」页不会把歌停掉。
    DisposableEffect(Unit) {
        MusicPlayback.onScreenAttached()
        onDispose { MusicPlayback.onScreenDetached() }
    }

    fun search() {
        val query = keyword.trim()
        if (query.isEmpty()) {
            flash = "请输入歌名、歌手或专辑" to true
            return
        }
        if (searching) return
        searching = true
        flash = null
        scope.launch {
            runCatching { MusicRepository.search(query) }
                .onSuccess { list ->
                    results = list
                    tab = MusicTab.RESULTS
                    flash = if (list.isEmpty()) "没搜到相关歌曲，换个关键词试试" to true else null
                }
                .onFailure { flash = "搜索失败：${it.message ?: "网络异常"}" to true }
            searching = false
        }
    }

    /**
     * 拉一批热门/推荐歌曲。
     *
     * 没有独立的榜单 API，用「榜单词」走同一条搜索链路拿真实可播的曲目；
     * 网络失败或空结果时退回本机最近播放 + 收藏（去重），保证榜单区不会是一片空白。
     */
    fun loadHot() {
        if (hotLoading) return
        hotLoading = true
        flash = null
        val pool = HOT_KEYWORDS[hotBoard]
        val query = pool[hotRound % pool.size]
        scope.launch {
            val fetched = runCatching { MusicRepository.search(query) }.getOrDefault(emptyList())
            hotTracks = if (fetched.isNotEmpty()) {
                fetched.take(12)
            } else {
                (recentList + favoriteList).distinctBy { it.id }.take(12)
            }
            hotLoading = false
        }
    }

    // 只在首次进入时自动拉一次；换榜单 / 换一批由按钮显式触发。
    LaunchedEffect(Unit) { loadHot() }

    // 第一次点播放时问一次通知权限（只在后台播放开着时问）。
    val askNotificationPermission = rememberNotificationPermissionAsk()

    fun play(list: List<MusicTrack>, track: MusicTrack) {
        // 播放中的歌**再点一次不重新取链**（会白等一次网络、还打断当前进度），
        // 直接进详情页——这也是主流播放器的行为。
        val alreadyCurrent = playback.track?.id == track.id && playback.hasTrack
        if (alreadyCurrent) {
            showPlayer = true
            return
        }
        // 队列 = 当前这一屏的列表：这样"下一首"走的就是用户看到的顺序。
        val index = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        MusicPlayback.playQueue(list, index)
        flash = null
        showPlayer = true
        askNotificationPermission()
    }

    /** 主卡 / 底栏中央键：有歌就进播放页，没歌就随机播收藏（红心 Radio）。 */
    fun startRadio() {
        if (playback.hasTrack) {
            showPlayer = true
            return
        }
        if (favoriteList.isEmpty()) {
            flash = "还没有收藏的歌曲，先搜一首点个爱心吧" to true
            return
        }
        MusicPlayback.playQueue(favoriteList.shuffled(), 0)
        askNotificationPermission()
    }

    fun toggleDownload(track: MusicTrack) {
        if (cache == null) return
        if (cache.isCached(track)) {
            if (track.id in removingCache) return
            removingCache = removingCache + track.id
            scope.launch {
                MusicPlayback.removeDownload(track)
                flash = "已删除缓存：${track.name}" to false
                showToast("已删除缓存：${track.name}", false)
                // 垃圾桶飘动至少留 520ms（与动画等长）：删得太快时图标一闪而过，
                // 用户根本看不见，反而会以为点了没反应。
                delay(520)
                removingCache = removingCache - track.id
            }
            return
        }
        if (track.id in downloading) return
        if (!isNetworkAvailable(context)) {
            // 断网时取链必然失败，报「取不到播放地址」会误导成音源问题。
            flash = "网络不可用，请检查连接后重试" to true
            retryTrack = track
            ErrorLogStore.record(
                "MusicDownload",
                IllegalStateException("网络不可用"),
                "缓存失败：${track.name}"
            )
            showToast("网络不可用，请检查连接后重试", true)
            return
        }
        downloading = downloading + track.id
        scope.launch {
            val result = MusicPlayback.downloadForOffline(track)
            downloading = downloading - track.id
            flash = result.fold(
                onSuccess = { "已缓存，无网络也能播放：${track.name}" to false },
                onFailure = {
                    if (!isNetworkAvailable(context)) "网络不可用，请检查连接后重试" to true
                    else "缓存失败：${it.message ?: "网络异常"}" to true
                }
            )
            result.fold(
                onSuccess = {
                    retryTrack = null
                    showToast("下载完成，可离线播放：${track.name}", false)
                },
                onFailure = { error ->
                    // 失败也写一条本机日志：用户来反馈时，"错误日志"里能直接看到原因。
                    retryTrack = track
                    ErrorLogStore.record("MusicDownload", error, "缓存失败：${track.name}")
                    showToast(
                        if (!isNetworkAvailable(context)) "网络不可用，请检查连接后重试"
                        else "下载失败：${error.message ?: "网络异常"}",
                        true
                    )
                }
            )
        }
    }

    val cachedTracks = remember(cachedIds) { cache?.cachedTracks().orEmpty() }
    // 收藏页按所选分组过滤（favGroupId == null 时是"全部"）。
    val favGroupFiltered = remember(favoriteEntries, favGroupId) {
        val chosen = favGroupId
        favoriteEntries
            .filter { chosen == null || it.groupId == chosen }
            .map { it.track }
    }
    val list: List<MusicTrack> = when (tab) {
        MusicTab.HOT -> hotTracks
        MusicTab.RESULTS -> results
        MusicTab.FAVORITES -> favGroupFiltered
        MusicTab.RECENT -> recentList
        MusicTab.CACHED -> cachedTracks
    }

    val listState = rememberLazyListState()

    /**
     * 真正离开本页的唯一出口（2026-11-09 需求：可选二次确认）。
     *
     * 返回箭头与系统返回键都走这里，所以"确认"对两条路径同时生效；
     * 关闭播放器 / 收起「我的」不算离开，不经过这里。
     */
    fun requestExit() {
        if (exitConfirmEnabled) showExitConfirm = true else onBack()
    }

    // 2026-11-09 修复「播放页按系统返回键有概率整页退出音乐」：
    // 之前是两个 `enabled = showPlayer` / `enabled = showMine` 的处理器，
    // 收起播放器的同一帧处理器就被禁用，紧跟着的一次返回没人接，直接落到
    // NavHost 把音乐页 pop 掉；连续按两下、或收起动画还没播完时按，命中率更高。
    // 现在只留一个**恒为启用**的处理器，由它决定「关播放器 / 回发现 / 真正离开」，
    // 并在刚收起覆盖层 400ms 内吞掉返回，连按两下也不会误退整页。
    var lastBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = true) {
        val now = SystemClock.uptimeMillis()
        when {
            showPlayer -> {
                lastBackAt = now
                showPlayer = false
            }

            showMine -> {
                lastBackAt = now
                showMine = false
            }

            now - lastBackAt < 400L -> Unit
            // 退出确认弹窗自带 BackHandler 会先接走返回键；这里留着防御性分支，
            // 无论如何都不直接置 false，避免弹窗被同帧摘掉、退出动画没机会画。
            showExitConfirm -> Unit
            else -> {
                lastBackAt = now
                requestExit()
            }
        }
    }

    // ---- 主卡实时歌词（2026-11-09 需求 2）------------------------------------
    // 只在开关打开且有歌时才去取歌词：`MusicLyrics.peek` 命中进程内缓存就直接显示，
    // 没命中才走网络 / 磁盘（与播放页同一条链路，任何一边取过，另一边都直接命中，
    // 不会为了主页那一行多打一次接口）。
    var heroLyrics by remember(playback.track?.id) {
        mutableStateOf(playback.track?.let { MusicLyrics.peek(it.id) } ?: emptyList())
    }
    LaunchedEffect(playback.track?.id, entryLyricsEnabled) {
        val track = playback.track
        heroLyrics = when {
            track == null || !entryLyricsEnabled -> emptyList()
            else -> MusicLyrics.peek(track.id)
                ?: runCatching { MusicLyrics.load(track, context) }.getOrDefault(emptyList())
        }
    }
    val heroLyricLine: String? = remember(heroLyrics, playback.positionMs, entryLyricsEnabled) {
        if (!entryLyricsEnabled || heroLyrics.isEmpty()) {
            null
        } else {
            val index = heroLyrics.indexOfLast { it.timeMs <= playback.positionMs }
            heroLyrics.getOrNull(index.coerceAtLeast(0))?.text?.takeIf { it.isNotBlank() }
        }
    }

    MusicBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ---- 顶栏：返回 / 搜索（「我的」段换成标题） / 主题刷子 ----
            // 2026-11-09：右上角头像入口去掉。「我的」已经由底栏常驻入口承接，
            // 顶栏再放一个头像既重复、又挤掉搜索胶囊的宽度；未登录时那个耳机圆键
            // 更是让人以为是"后台播放"开关。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = if (showMine) "返回发现" else "返回",
                    onClick = { if (showMine) showMine = false else requestExit() },
                    size = 40.dp
                )
                Spacer(Modifier.width(10.dp))
                if (showMine) {
                    Text(
                        text = "我的",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.text,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    SearchPill(
                        value = keyword,
                        onValueChange = { value ->
                            keyword = value
                            // 2026-11-09 需求 3：搜索结果只在"正在搜索"时出现。
                            // 清空关键词等于退出搜索，直接回到热门榜单，
                            // 不用先点「← 回到热门榜单」再清空。
                            if (value.isBlank() && tab == MusicTab.RESULTS) {
                                tab = MusicTab.HOT
                            }
                        },
                        searching = searching,
                        onSearch = { search() },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 刷子键：点开换风格与明暗（音乐界面独立主题）。
                ThemeBrushButton(onClick = { showThemeSheet = true }, size = 40.dp)
            }

            // 2026-11-09：「我的」内嵌成同一页的第二段内容。
            // 用 Crossfade 换内容区，而不是整屏滑上来的覆盖层——标题栏、底栏、
            // 返回键层级都不变，观感是"翻了一页内容"，不是"跳了二级页"。
            Crossfade(
                targetState = showMine,
                animationSpec = tween(220),
                modifier = Modifier.weight(1f),
                label = "musicSection"
            ) { mine ->
                if (mine) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                    ) {
                        // 我的段里也有动作（退出登录 / 清理缓存），提示就地显示：
                        // 列表区那条提示在这段里是看不见的。
                        flash?.let { (text, isError) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                            ) {
                                Text(
                                    text = text,
                                    fontSize = 12.sp,
                                    color = if (isError) palette.error else palette.textSub,
                                    modifier = Modifier.weight(1f)
                                )
                                if (retryTrack != null) {
                                    TextButton(
                                        onClick = {
                                            val t = retryTrack
                                            retryTrack = null
                                            if (t != null) toggleDownload(t)
                                        }
                                    ) { Text("重试", fontSize = 12.sp) }
                                    TextButton(
                                        onClick = {
                                            flash = null
                                            retryTrack = null
                                        }
                                    ) { Text("关闭", fontSize = 12.sp) }
                                }
                            }
                        }
                        MusicAccountContent(onMessage = { flash = it to false })
                        Spacer(Modifier.height(20.dp))
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 16.dp)
                    ) {
                        // ---- 主卡：红心 Radio / 正在播放 ----
                        item(key = "hero") {
                            HeroCard(
                                state = playback,
                                favoriteCount = favoriteList.size,
                                lyricLine = heroLyricLine,
                                onOpenPlayer = { showPlayer = true },
                                onToggle = {
                                    if (playback.hasTrack) MusicPlayback.toggle() else startRadio()
                                },
                                onRadio = { startRadio() }
                            )
                        }

                        // ---- 榜单标题 + 标签 ----
                        item(key = "chips") {
                            Column(Modifier.padding(top = 18.dp)) {
                                MusicSectionHeader(
                                    title = tabTitle(tab),
                                    subtitle = when (tab) {
                                        MusicTab.HOT -> if (hotLoading) "正在找好歌…" else "为你推荐 · ${hotTracks.size} 首"
                                        MusicTab.RESULTS -> "共 ${results.size} 首"
                                        MusicTab.FAVORITES -> "共 ${favoriteList.size} 首"
                                        MusicTab.RECENT -> "共 ${recentList.size} 首"
                                        MusicTab.CACHED -> "共 ${cachedTracks.size} 首 · 可离线播放"
                                    },
                                    trailing = when {
                                        tab == MusicTab.RESULTS -> "搜索结果"
                                        tab == MusicTab.HOT -> "完整榜单"
                                        else -> null
                                    }
                                )

                                // 热门榜单：三个榜单子标签 + 「换一批」（设计稿的刷新动作）。
                                if (tab == MusicTab.HOT) {
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        HOT_BOARDS.forEachIndexed { index, name ->
                                            val selected = hotBoard == index
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(
                                                        if (selected) palette.accent else palette.glass
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (selected) palette.accent else palette.glassBorder,
                                                        RoundedCornerShape(50)
                                                    )
                                                    .bounceClick(onClick = {
                                                        hotBoard = index
                                                        hotRound = 0
                                                        loadHot()
                                                    })
                                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                                            ) {
                                                Text(
                                                    text = name,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selected) {
                                                        FontWeight.SemiBold
                                                    } else {
                                                        FontWeight.Normal
                                                    },
                                                    color = if (selected) {
                                                        palette.onAccent
                                                    } else {
                                                        palette.textSub
                                                    }
                                                )
                                            }
                                            Spacer(Modifier.width(7.dp))
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .bounceClick(onClick = {
                                                    hotRound++
                                                    loadHot()
                                                })
                                                .padding(horizontal = 4.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = null,
                                                tint = palette.accent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = if (hotLoading) "加载中" else "换一批",
                                                fontSize = 11.sp,
                                                color = palette.accent
                                            )
                                        }
                                    }
                                }

                                // 搜索后给一条明确的回退路径，不用清掉关键词才能回榜单。
                                if (tab == MusicTab.RESULTS) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "← 回到热门榜单",
                                        fontSize = 11.sp,
                                        color = palette.accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .bounceClick(onClick = { tab = MusicTab.HOT })
                                            .padding(horizontal = 4.dp, vertical = 4.dp)
                                    )
                                }

                                // 2026-11-09 需求 3：热门榜单**独立成块**（首屏默认就是它，
                                // 带自己的三个子榜与「换一批」），搜索结果默认不出现、
                                // 只在真正搜索时独占一屏（那时整行标签一起隐藏）。
                                if (tab != MusicTab.RESULTS) {
                                    Spacer(Modifier.height(10.dp))
                                }
                                if (tab != MusicTab.RESULTS) LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(
                                        listOf(
                                            MusicTab.HOT,
                                            MusicTab.FAVORITES,
                                            MusicTab.RECENT,
                                            MusicTab.CACHED
                                        )
                                    ) { item ->
                                        TabChip(
                                            label = item.label,
                                            icon = when (item) {
                                                MusicTab.HOT -> Icons.Rounded.Whatshot
                                                MusicTab.FAVORITES -> Icons.Rounded.Favorite
                                                MusicTab.RECENT -> Icons.Rounded.History
                                                MusicTab.CACHED -> Icons.Rounded.DownloadDone
                                                MusicTab.RESULTS -> Icons.Rounded.Search
                                            },
                                            count = when (item) {
                                                MusicTab.HOT -> hotTracks.size
                                                MusicTab.RESULTS -> results.size
                                                MusicTab.FAVORITES -> favoriteList.size
                                                MusicTab.RECENT -> recentList.size
                                                MusicTab.CACHED -> cachedTracks.size
                                            },
                                            selected = tab == item,
                                            onClick = { tab = item }
                                        )
                                    }
                                }
                                // ---- 收藏分组条（2026-11-09 需求 4）----------------
                                // 「全部 + 各分组 + 新建」；长按分组名可改名/删除。
                                if (tab == MusicTab.FAVORITES) {
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        item(key = "all") {
                                            GroupChip(
                                                text = "全部 ${favoriteEntries.size}",
                                                selected = favGroupId == null,
                                                onClick = { favGroupId = null }
                                            )
                                        }
                                        items(
                                            favoriteGroups,
                                            key = { it.id }
                                        ) { group ->
                                            val count = favoriteEntries.count {
                                                it.groupId == group.id
                                            }
                                            GroupChip(
                                                text = if (count > 0) {
                                                    "${group.name} $count"
                                                } else {
                                                    group.name
                                                },
                                                selected = favGroupId == group.id,
                                                onLongClick = {
                                                    editingGroup = group
                                                    groupDraft = group.name
                                                    showGroupDialog = true
                                                },
                                                onClick = { favGroupId = group.id }
                                            )
                                        }
                                        item(key = "add") {
                                            GroupChip(
                                                text = "＋ 新建分组",
                                                selected = false,
                                                onClick = {
                                                    editingGroup = null
                                                    groupDraft = ""
                                                    showGroupDialog = true
                                                }
                                            )
                                        }
                                    }
                                }

                                val message = flash ?: playback.error?.let { it to true }
                                message?.let { (text, isError) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 8.dp)
                                    ) {
                                        Text(
                                            text = text,
                                            fontSize = 11.sp,
                                            color = if (isError) palette.error
                                            else palette.textSub,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (retryTrack != null) {
                                            TextButton(
                                                onClick = {
                                                    val t = retryTrack
                                                    retryTrack = null
                                                    if (t != null) toggleDownload(t)
                                                }
                                            ) { Text("重试", fontSize = 11.sp) }
                                            TextButton(
                                                onClick = {
                                                    flash = null
                                                    retryTrack = null
                                                }
                                            ) { Text("关闭", fontSize = 11.sp) }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        // ---- 歌曲卡 ----
                        if (list.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                ) {
                                    MusicEmptyHint(
                                        when (tab) {
                                            MusicTab.HOT -> "暂时没找到推荐歌曲\n点「换一批」再试试"
                                            MusicTab.RESULTS -> "输入关键词开始搜索\n中文、英文都可以"
                                            MusicTab.FAVORITES -> "还没有收藏的歌曲\n播放页点爱心就会出现在这里"
                                            MusicTab.RECENT -> "还没有播放记录\n播过的歌会出现在这里"
                                            MusicTab.CACHED -> "还没有缓存的歌曲\n在播放页点下载图标可离线播放"
                                        }
                                    )
                                }
                            }
                        } else {
                            item(key = "list") {
                                GlassCard(Modifier.fillMaxWidth()) {
                                    list.forEachIndexed { index, track ->
                                        TrackRow(
                                            rank = index + 1,
                                            track = track,
                                            playingNow = playback.track?.id == track.id,
                                            pending = playback.pendingTrackId == track.id,
                                            favorite = favoriteList.any { it.id == track.id },
                                            cached = cachedIds.contains(track.id),
                                            playing = playback.track?.id == track.id && playback.playing,
                                            onPlay = { play(list, track) },
                                            onToggleFavorite = {
                                                scope.launch { favorites.toggle(track) }
                                            },
                                            // 收藏页才显示「移入分组」的小按钮，
                                            // 其它列表里出现会显得多余。
                                            onMoveGroup = if (tab == MusicTab.FAVORITES) {
                                                { movingTrack = track }
                                            } else {
                                                null
                                            }
                                        )
                                        if (index != list.lastIndex) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp)
                                                    .height(1.dp)
                                                    .background(
                                                        palette.divider
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 底栏 ----
            MusicBottomBar(
                state = playback,
                mineSelected = showMine,
                // 「发现」= 回到列表顶部；已经在「我的」里时先切回列表（与返回箭头一致）。
                onScrollTop = {
                    if (showMine) {
                        showMine = false
                    } else {
                        scope.launch { listState.animateScrollToItem(0) }
                    }
                },
                onOpenPlayer = {
                    if (playback.hasTrack) showPlayer = true else startRadio()
                },
                onOpenMine = { showMine = true }
            )
        }

        // ---- 播放器覆盖层：从下往上滑入，向下滑出 ----
        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically(
                animationSpec = tween(PLAYER_SLIDE_MS),
                initialOffsetY = { fullHeight -> fullHeight }
            ),
            exit = slideOutVertically(
                animationSpec = tween(PLAYER_SLIDE_MS),
                targetOffsetY = { fullHeight -> fullHeight }
            )
        ) {
            PlayerScreen(
                state = playback,
                favorite = playback.track?.let { track ->
                    favoriteList.any { it.id == track.id }
                } == true,
                cached = playback.track?.let { cache?.isCached(it) } == true,
                downloading = playback.track?.id?.let { it in downloading } == true,
                removingCache = playback.track?.id?.let { it in removingCache } == true,
                onClose = { showPlayer = false },
                onToggleFavorite = {
                    playback.track?.let { track -> scope.launch { favorites.toggle(track) } }
                },
                onToggleCache = { playback.track?.let(::toggleDownload) }
            )
        }
    }

    // ---- 下载结果提示（需求 4）：白色胶囊，盖在列表与播放页之上 ----
    toast?.let { (text, isError) ->
        MusicToast(text = text, isError = isError)
    }

    if (showThemeSheet) {
        MusicThemeSheet(onDismiss = { showThemeSheet = false })
    }

    // 退出确认（2026-11-09 需求 1）：只在开发者选项里打开时出现。
    // 2026-11-12：换成音乐调色板自己的卡片 + 进出动画，不再用全局 Material 弹窗
    // （深色音乐页里突然冒出一张浅色系统卡片很跳）。
    // 文案必须说清"只是离开页面"，否则用户会以为点确定就停止播放。
    if (showExitConfirm) {
        MusicExitConfirmDialog(
            onConfirm = {
                showExitConfirm = false
                onBack()
            },
            onDismiss = { showExitConfirm = false }
        )
    }

    // ---- 收藏分组：新建 / 重命名 / 删除（2026-11-09 需求 4）------------------
    // 同一个输入框两用：editingGroup == null → 新建；否则是给这一组改名。
    if (showGroupDialog) {
        val editing = editingGroup
        TextInputDialog(
            title = if (editing == null) "新建分组" else "重命名分组",
            label = "分组名称",
            initial = groupDraft,
            extraLabel = if (editing != null && editing.id != FavoriteGroup.DEFAULT_ID) {
                "删除这个分组"
            } else {
                null
            },
            onConfirm = { name ->
                showGroupDialog = false
                scope.launch {
                    if (editing == null) {
                        val id = favorites.createGroup(name)
                        favGroupId = id
                        flash = "已新建分组「$name」" to false
                    } else {
                        favorites.renameGroup(editing.id, name)
                        flash = "分组已改名为「$name」" to false
                    }
                }
            },
            onDismiss = { showGroupDialog = false },
            onExtra = if (editing != null && editing.id != FavoriteGroup.DEFAULT_ID) {
                {
                    showGroupDialog = false
                    scope.launch {
                        favorites.deleteGroup(editing.id)
                        if (favGroupId == editing.id) favGroupId = null
                        flash = "分组「${editing.name}」已删除，组内歌曲回到默认分组" to false
                    }
                }
            } else {
                null
            }
        )
    }

    // 把一首收藏移进哪个分组。列表里同时给出「新建分组」的快捷入口，
    // 否则用户要先退出去建组、再回来点它，路径太长。
    movingTrack?.let { target ->
        ChoiceDialog(
            title = "把「${target.name}」移入分组",
            options = favoriteGroups.map { it.name to it.id } + ("＋ 新建分组" to NEW_GROUP),
            onPick = { picked ->
                movingTrack = null
                scope.launch {
                    if (picked == NEW_GROUP) {
                        editingGroup = null
                        groupDraft = ""
                        showGroupDialog = true
                    } else {
                        val ok = favorites.moveToGroup(target.id, picked)
                        val name = favoriteGroups.firstOrNull { it.id == picked }?.name ?: "该分组"
                        flash = if (ok) "已移入「$name」" to false else "这首歌已不在收藏里" to true
                    }
                }
            },
            onDismiss = { movingTrack = null }
        )
    }
}

/** [ChoiceDialog] 里「新建分组」那一项的哨兵值（真实分组 id 都以 g/default 开头）。 */
private const val NEW_GROUP = "__new_group__"

/** 播放器覆盖层的升降时长：整屏纵向位移，比横向推入略慢一点更稳。 */
private const val PLAYER_SLIDE_MS = 300

/** 标题跟随标签变化（设计稿那张榜是固定的，我们这四类来源不同，所以跟着切）。 */
private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    return cm.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

private fun tabTitle(tab: MusicTab): String = when (tab) {
    MusicTab.HOT -> "热门榜单"
    MusicTab.RESULTS -> "搜索结果"
    MusicTab.FAVORITES -> "我的收藏"
    MusicTab.RECENT -> "最近播放"
    MusicTab.CACHED -> "离线缓存"
}

/**
 * 顶部搜索胶囊。
 *
 * 用 `BasicTextField` 而不是 `OutlinedTextField`：后者的描边、内边距与最小高度（56dp、
 * 方角容器）压不成设计稿这种 44dp 胶囊。装饰（放大镜 / 占位字 / 清除 / 搜索）
 * 全在 `decorationBox` 里手摆，键盘的搜索键直接触发搜索。
 */
@Composable
private fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberMusicPalette()
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(palette.glass)
            .border(1.dp, palette.glassBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.sp,
                color = palette.text
            ),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                onSearch()
            }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "搜索歌曲、歌手",
                            fontSize = 13.sp,
                            color = palette.textMuted
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f)
        )
        if (value.isNotEmpty()) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "清空",
                tint = palette.textMuted,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onValueChange("") }
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (searching) "搜索中" else "搜索",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = palette.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(enabled = !searching) {
                    keyboard?.hide()
                    onSearch()
                }
                .padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}

/**
 * 主卡：没在播时是「红心 Radio」，在播时就地变成"正在播放"。
 *
 * 为什么合并成一张卡：设计稿这张卡是「红心 Radio + 一键播放」，而列表页还需要一个
 * "当前在播什么"的落点（原来的迷你播放条）。合成一张——有歌时它就是迷你播放器，
 * 没歌时它就是推荐入口——省掉一条常驻底栏，也更符合设计稿的留白。
 */
@Composable
private fun HeroCard(
    state: PlaybackState,
    favoriteCount: Int,
    /** 当前这句歌词（null = 没开开关 / 没歌词，退回歌名 + 歌手）。 */
    lyricLine: String?,
    onOpenPlayer: () -> Unit,
    onToggle: () -> Unit,
    onRadio: () -> Unit
) {
    val palette = rememberMusicPalette()
    val playing = state.hasTrack
    val track = state.track
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (playing) onOpenPlayer() else onRadio() }
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧圆：在播时是旋转唱片，否则是一枚带渐变的"电台"图标。
            if (playing) {
                VinylArtwork(track = track, size = 62.dp, playing = state.playing)
            } else {
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    palette.accent.copy(alpha = 0.85f),
                                    palette.accentLight.copy(alpha = 0.85f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = palette.error,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (playing) "正在播放" else "红心 Radio",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.text,
                        maxLines = 1
                    )
                    if (!playing) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = palette.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (playing) {
                        buildString {
                            append(track?.name.orEmpty())
                            val artist = track?.artist.orEmpty()
                            if (artist.isNotBlank()) append(" · $artist")
                        }
                    } else {
                        if (favoriteCount > 0) {
                            "根据你的 $favoriteCount 首收藏随机播放"
                        } else {
                            "收藏几首歌，这里会随机播给你"
                        }
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textSub
                )
                // 实时歌词（2026-11-09 需求 2）：叠在「歌名 · 歌手」**下面**单独一行，
                // 而不是把副标题换掉——换掉会让用户看不出在放哪首（歌名比歌词更需要留）。
                // 换句用交叉淡入：每 3~5 秒硬切一次文字会显得很跳。
                if (playing && lyricLine != null) {
                    Spacer(Modifier.height(2.dp))
                    Crossfade(
                        targetState = lyricLine,
                        animationSpec = tween(220),
                        label = "heroLyric"
                    ) { line ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = palette.accent
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            AccentPlayButton(
                playing = state.playing,
                onClick = onToggle,
                size = 44.dp,
                enabled = if (playing) true else favoriteCount > 0
            )
        }
    }
}

/** 标签胶囊：选中 = 主题色实心白字，未选中 = 玻璃白底。 */
@Composable
private fun TabChip(
    label: String,
    count: Int,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = rememberMusicPalette()
    val accent = palette.accent
    val contentColor = if (selected) palette.onAccent else palette.textSub
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else palette.buttonFill)
            .border(
                width = 1.dp,
                color = if (selected) accent else palette.buttonBorder,
                shape = RoundedCornerShape(50)
            )
            .bounceClick(onClick)
            .padding(start = 11.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 2026-11-14 需求 4：标签文字前加图标。
        // 图标与文字同色、只有 12~13dp：它是"扫一眼分得清这是哪一类"的辅助，
        // 不该比文字本身更抢眼；选中态跟随胶囊底色一起翻成反色。
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = if (count > 0) "$label $count" else label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = contentColor
        )
    }
}

/**
 * 音乐页的下载结果提示（2026-11-14 需求 4）。
 *
 * 白色胶囊 + 深色文字，固定在屏幕底部居中、底栏之上：
 * - 用白色而不是跟着音乐主题走，是因为它同时出现在**播放页（深色）**与列表页，
 *   白色在两种背景下都够清楚，也与系统 Toast 的观感一致；
 * - 失败时图标与文字用红色，成功用绿色，但**底色仍是白的**——
 *   整块变红在深色音乐页里过于刺眼。
 */
@Composable
private fun MusicToast(text: String, isError: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 108.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .shadow(10.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline
                else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (isError) Color(0xFFD93025) else Color(0xFF1E8E3E),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 12.5.sp,
                color = Color(0xFF1F1F1F),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 收藏分组胶囊（2026-11-09 需求 4）。
 *
 * 与 [TabChip] 的区别只有两点：不需要计数参数（文案里已含），
 * 且支持**长按**改名 / 删除分组——短按永远是「切到这一组」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val palette = rememberMusicPalette()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.accent else palette.buttonFill)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent else palette.buttonBorder,
                shape = RoundedCornerShape(50)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.onAccent else palette.textSub
        )
    }
}

/**
 * 榜单里的一行：排名 + 封面 + 歌名歌手 + 收藏 + 时长 + 播放圆键。
 *
 * 整行点击 = 播放并进详情页；收藏与播放键各自独立（命中区 44dp）。
 * 缓存开关不在列表里重复出现——它在播放页的下载键上，列表只在副标题里标「已缓存」。
 */
@Composable
private fun TrackRow(
    rank: Int,
    track: MusicTrack,
    playingNow: Boolean,
    pending: Boolean,
    favorite: Boolean,
    cached: Boolean,
    playing: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** 非 null 时在行尾多一个「移入分组」图标（收藏页专用，2026-11-09）。 */
    onMoveGroup: (() -> Unit)? = null
) {
    val palette = rememberMusicPalette()
    val accent = palette.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onPlay)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RankText(rank = rank, modifier = Modifier.width(30.dp))
        Spacer(Modifier.width(6.dp))
        TrackArtwork(track = track, size = 44.dp, corner = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playingNow) accent else palette.text
            )
            Text(
                text = buildString {
                    append(
                        listOf(track.artist, track.album)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                    if (cached) append(" · 已缓存")
                    if (pending) append(" · 取链中…")
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = palette.textSub
            )
        }
        Spacer(Modifier.width(4.dp))
        // 移入分组（仅收藏页）：文件夹图标，命中区 40dp。
        onMoveGroup?.let { move ->
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .bounceClick(move),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = "移入分组",
                    tint = palette.textMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
        // 收藏：图标小、命中区仍有 44dp（外层 Box 撑）。
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .bounceClick(onToggleFavorite),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) palette.error
                else palette.textMuted,
                modifier = Modifier.size(17.dp)
            )
        }
        Text(
            text = formatDuration(track.durationMs),
            fontSize = 11.sp,
            color = palette.textMuted
        )
        Spacer(Modifier.width(8.dp))
        // 播放圆键（设计稿里是"浅色底 + 主题色三角"）。
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f))
                .bounceClick(onPlay),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (playingNow && playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playingNow && playing) "暂停" else "播放",
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 底栏：发现 / 中央悬浮播放键 / 我的。
 *
 * - 左「发现」= 回到列表（已在列表时滚回顶部）；
 * - 中央键 = 有歌时显示**这首歌的封面**（播放中缓慢旋转，暂停时压暗显示播放图标），
 *   点它进播放页；没歌时是强调色播放键，点了随机播收藏（2026-11-09 需求 3）；
 * - 右「我的」= 同一页内的账号与音乐设置（2026-11-09 改为内嵌，不再是二级页）。
 *
 * ## 2026-11-12 按参考图重排的层级
 *
 * 之前整个底栏是一根 `Column`（进度线 → `Row`（两侧项 + 中央键）），中央键用
 * `offset(y=-10.dp)` 从 Row 里往上顶。这样有两个问题：两侧项被 `padding(28dp)`
 * 固定在屏幕最边上，跟中央键之间空出一大片；而进度线是 Column 的第一个子项，
 * 会被后面画的整行（含两侧项的胶囊底）压住，看起来"断在中间"。
 *
 * 现在改成 `Box` 三层显式 z 序：
 * 1. `Column`（z=0）：进度线占位 + 两侧项，中央位置只留一个 62dp 的空格；
 *    两侧项向内挪进参考图的红框区域，并把按钮本身加宽到 92dp（红框宽度）；
 * 2. 进度条（z=1）：贴底栏顶沿、通栏 2dp，压在两侧项之上，**左右两段完整可见**；
 * 3. 中央播放键（z=2）：明确高于导航栏与进度条，向上跨过顶沿（黄线位置），
 *    只遮住进度条正中间那一小段，这正是参考图里黄线绕着圆键走的画法。
 */
@Composable
private fun MusicBottomBar(
    state: PlaybackState,
    mineSelected: Boolean,
    onScrollTop: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenMine: () -> Unit
) {
    val palette = rememberMusicPalette()
    val accent = palette.accent
    val fraction = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 2026-11-09 沉浸式：底色先铺到屏幕最下沿（含系统导航栏那一截），
            // 再让内容避开导航栏。反过来写（先 padding 再 background）时，
            // 导航栏那一条露出的是页面背景色，底栏像"浮在一条不同色的条上"。
            .background(palette.glass)
            .navigationBarsPadding()
    ) {
        Column {
            // 进度条的实际绘制在下面（z=1），这里只占住 2dp 高度让 Row 往下排。
            Spacer(Modifier.fillMaxWidth().height(MUSIC_BOTTOM_PROGRESS_HEIGHT))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = MUSIC_BOTTOM_SIDE_INSET),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomBarItem(
                    icon = Icons.Rounded.GraphicEq,
                    label = "发现",
                    selected = !mineSelected,
                    onClick = onScrollTop
                )
                Spacer(Modifier.weight(1f))
                // 中央键不在 Row 里画：只把位置空出来，键本体在 Box 的 z=2 层。
                Spacer(Modifier.size(MUSIC_CENTER_BUTTON_SIZE))
                Spacer(Modifier.weight(1f))
                BottomBarItem(
                    icon = Icons.Rounded.Headphones,
                    label = "我的",
                    selected = mineSelected,
                    onClick = onOpenMine
                )
            }
        }

        // ---- z=1：进度条 ----
        // 通栏压在两侧项之上，"不遮挡"指的是左右两段都要露出来；
        // 中间那一段被 z=2 的播放键盖住是设计意图（参考图黄线就是这样画的）。
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(1f)
                .fillMaxWidth()
                .height(MUSIC_BOTTOM_PROGRESS_HEIGHT)
                .background(palette.divider)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(MUSIC_BOTTOM_PROGRESS_HEIGHT)
                    .background(accent)
            )
        }

        // ---- z=2：中央悬浮播放键 ----
        // 比两侧项高：圆键顶部跨出底栏顶沿（黄线），视觉上"浮"在导航栏之上。
        // 在播时直接显示当前歌曲封面，没歌时是强调色播放键。
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = MUSIC_CENTER_BUTTON_OFFSET)
                .zIndex(2f)
                .size(MUSIC_CENTER_BUTTON_SIZE)
                .clip(CircleShape)
                .background(palette.buttonFill)
                .border(1.5.dp, palette.buttonBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (state.hasTrack) {
                SpinningCoverButton(
                    track = state.track,
                    playing = state.playing,
                    size = 52.dp,
                    onClick = onOpenPlayer
                )
            } else {
                AccentPlayButton(
                    playing = false,
                    onClick = onOpenPlayer,
                    size = 52.dp
                )
            }
        }
    }
}

/** 底栏顶沿的播放进度线高度。 */
private val MUSIC_BOTTOM_PROGRESS_HEIGHT = 2.dp

/** 两侧「发现 / 我的」向内收的距离（按钮加宽后同步回收，保持红框中心不变）。 */
private val MUSIC_BOTTOM_SIDE_INSET = 46.dp

/** 单侧按钮宽度：约等于参考图红框宽度，胶囊底和点击区一起变宽。 */
private val MUSIC_BOTTOM_ITEM_WIDTH = 92.dp

/** 中央圆键直径；Row 里的占位空格也用这个尺寸。 */
private val MUSIC_CENTER_BUTTON_SIZE = 62.dp

/** 中央圆键相对底栏顶沿向上的位移，让圆心落在顶沿下方约 19dp。 */
private val MUSIC_CENTER_BUTTON_OFFSET = (-12).dp

/**
 * 底栏两侧的图标 + 文字（2026-11-09 需求 2：位置与点击动画）。
 *
 * - **位置**：整列固定 52dp 高、底对齐（`Arrangement.Bottom`），让两侧的图标/文字
 *   与中间那颗上移的播放键形成"中间高、两边低"的层次，而不是三者挤在一条线上；
 * - **点击动画**：按下整体缩到 0.92、图标再缩到 0.86（两段不同速率，手感比单纯
 *   透明度变化明显），松开回弹；选中项加一层主题色 12% 的胶囊底。
 */
@Composable
private fun BottomBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val palette = rememberMusicPalette()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bottomItemScale"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bottomItemIconScale"
    )
    val tint = if (selected) palette.accent else palette.textMuted
    Column(
        modifier = Modifier
            // 2026-11-12：按参考图红框把整颗按钮（含选中态胶囊底与点击区）加宽，
            // 不再只有"图标+文字"那一条窄窄的可点区域。
            .width(MUSIC_BOTTOM_ITEM_WIDTH)
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) palette.accent.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable(
                interactionSource = source,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 音乐页自己的「退出确认」弹窗（2026-11-12，2026-11-14 改覆盖层）。
 *
 * ## 为什么不用公共的 [ConfirmDialog]
 *
 * 音乐页的底色来自 [MusicPalette]、和 App 的 `MaterialTheme` 完全解耦，
 * 公共弹窗却按应用主题画（深色音乐页里会突然浮出一张浅色系统卡片）。
 * 这里所有颜色都取自 `palette`：卡片 `surface`、正文 `textSub`、
 * 主行动 `accent` + `onAccent`；「取消」是灰白纯文字、无底色。
 *
 * ## 进出动画与卡顿修复（2026-11-14）
 *
 * 与「进入音乐确认」共用 [MusicConfirmOverlay] 的页面内全屏覆盖层：
 * 不再开独立 `Dialog` 窗口、不再缩放卡片，只做 150/120ms 淡入淡出，
 * 消除软件渲染下的整层重绘与卡片两侧残影；遮罩与返回键都不放行，
 * 仍然只有「取消 / 退出」两个出口。
 */
@Composable
private fun MusicExitConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val palette = rememberMusicPalette()
    MusicConfirmOverlay(scrimColor = Color.Black.copy(alpha = 0.45f)) { leave ->
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 340.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(palette.surface)
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "退出音乐？",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.text
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "这次只是离开音乐页面。正在播放的歌会按「后台播放」设置继续，" +
                    "下次进来还停在这一首。",
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = palette.textSub
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 2026-11-12：取消键改成灰白纯文字、无底色，
                // 只让「退出」那颗带主题色，主次一眼分得开。
                TextButton(
                    onClick = { leave(onDismiss) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = palette.textSub
                    ),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(MUSIC_EXIT_DIALOG_BUTTON_HEIGHT)
                ) {
                    Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = { leave(onConfirm) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent
                    ),
                    elevation = null,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(MUSIC_EXIT_DIALOG_BUTTON_HEIGHT)
                ) {
                    Text("退出", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private val MUSIC_EXIT_DIALOG_BUTTON_HEIGHT = 48.dp
