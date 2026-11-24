package com.morecheck.ui.web

import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.data.net.BilibiliRepository.BiliNavState
import com.morecheck.data.net.BilibiliRepository.BiliVideoDetail
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import com.morecheck.ui.theme.onAccent
import com.morecheck.ui.web.bili.BiliDetailScreen
import com.morecheck.ui.web.bili.BiliDownloadsScreen
import com.morecheck.ui.web.bili.BiliEmptyPane
import com.morecheck.ui.web.bili.BiliErrorPane
import com.morecheck.ui.web.bili.BiliHomeScreen
import com.morecheck.ui.web.bili.BiliLibraryPage
import com.morecheck.ui.web.bili.BiliLoginScreen
import com.morecheck.ui.web.bili.BiliNativePlayerScreen
import com.morecheck.ui.web.bili.BiliSettingsPage
import com.morecheck.ui.web.bili.BiliShortsScreen
import com.morecheck.ui.web.bili.BiliSkeletonList
import com.morecheck.ui.web.bili.BiliVideoCard
import com.morecheck.ui.web.bili.clickableNoIndication
import kotlinx.coroutines.launch

/**
 * 「B站视频」（发现 → B站视频）—— 2026-11-03 原生改造后的总入口。
 *
 * ## 为什么从「一个 WebView」改成「原生列表 + WebView 播放」
 *
 * 改造前整页只有一个 WebView 加载 m.bilibili.com：网页首屏是巨大的运营位 +
 * 需要滚很久才看到内容，而且用户明确反馈「功能缺失」。实质问题是：
 * **浏览（找视频）与观看（看视频）是两种交互，不该共用一个网页外壳**。
 * 所以现在拆成三层：
 *
 * 1. **浏览层**（本文件）：原生三 tab —— 推荐 / 热门 / 搜索，全部走 B 站公开接口，
 *    列表卡片展示封面、标题、UP 主、播放量、时长；
 * 2. **详情页**（[BiliDetailScreen]）：原生展示简介、统计、分 P，作为「看什么」的决策页；
 * 3. **播放页**（[BiliPlayerScreen]）：仍然是 WebView（B 站 H5 播放器是唯一可靠的
 *    播放实现，自研播放器不现实），保留 v1.2.5 已修的 UA/autoplay 两个坑。
 *
 * ## 下载按钮为什么不在列表里常驻
 *
 * 用户需求原话：「视频下载按钮不要常驻，下载可选择清晰度」。
 * 现在列表页**没有任何下载入口**；只有进入播放页（[BiliPlayerScreen]）后，
 * 顶栏才出现一个下载图标，点开先弹「选择清晰度」，档位来自接口实时返回。
 * 这样既避免了列表里一排下载按钮的视觉噪音，也避免"没看就下"的误触。
 *
 * ## 基础能力（对应需求「其他基础功能」）
 *
 * - 顶栏：返回 / 标题 / 搜索（切到搜索 tab）/ 刷新（刷新当前 tab）；
 * - 推荐支持「换一批」（`fresh_idx` 递增）；热门支持分页加载更多；搜索支持翻页；
 * - 首屏骨架屏、失败重试、空态文案、无网络与接口错误分开提示；
 * - 详情页展示简介；播放页有加载进度条；系统返回键逐层回退（播放页先回退网页历史）。
 *
 * ## 登录可能性
 *
 * 本轮不实装登录（风险与接缝详见 [BilibiliRepository] 的类注释「登录研究」）。
 * 用户可感知的后果是：免登录时清晰度上限较低（实测单文件 720P、DASH 仅 480P/360P），
 * 而且登录后 DASH 才能拿到 1080P 以上档位——这条已写进清晰度对话框的说明文案。
 *
 * @param onBack 退出本页（浏览层按系统返回键 / 顶栏返回）。
 * @param onMessage 走全局 Snackbar 的提示出口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilibiliScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messageSink = rememberUpdatedState(onMessage)

    // ---- 路由：浏览 / 详情 / 播放 ----
    // 用可保存的 String 路由（而不是 sealed class）：进程重建后能恢复到同一位置。
    var route by rememberSaveable { mutableStateOf(ROUTE_BROWSE) }
    var activeBvid by rememberSaveable { mutableStateOf("") }
    var activeTitle by rememberSaveable { mutableStateOf("") }
    var activeCid by rememberSaveable { mutableStateOf(0L) }
    var activePage by rememberSaveable { mutableStateOf(1) }
    // 当前分 P 时长：传给播放页用于清晰度弹层的体积估算。
    var activeDuration by rememberSaveable { mutableIntStateOf(0) }
    // UP 主 mid 与名字：原生播放页「关注 UP」按钮要用（BiliVideoItem 已带 mid）。
    var activeMid by rememberSaveable { mutableStateOf(0L) }
    var activeUpName by rememberSaveable { mutableStateOf("") }
    // 二级列表页（观看历史/稍后再看/我的收藏）的初始 tab。
    var activeLibraryTab by rememberSaveable { mutableIntStateOf(0) }

    // B 站账号登录态（2026-11-03 增强：WebView 登录 + Cookie 持久化）。
    var navState by remember { mutableStateOf<BiliNavState?>(null) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 详情缓存：详情页 ↔ 播放页来回切换时不重复请求（也避免返回后详情页重刷闪一下）。
    val detailCache = remember { mutableStateMapOf<String, BiliVideoDetail>() }

    // 浏览层数据状态放在 BilibiliScreen 级别：进入详情/播放再返回时列表与 tab 不丢。
    val browse = remember { BiliBrowseState() }
    val saveableStateHolder = rememberSaveableStateHolder()

    // ---------------- 数据加载 ----------------

    fun loadRecommend(refresh: Boolean) {
        if (browse.recommendLoading) return
        if (!BilibiliRepository.isNetworkAvailable(context)) {
            browse.recommendError = "当前没有网络连接，请检查网络后重试"
            browse.recommendNoNetwork = true
            return
        }
        browse.recommendLoading = true
        browse.recommendError = null
        browse.recommendNoNetwork = false
        // 刷新 = 换一批：fresh_idx 递增后 B 站会返回另一组推荐。
        if (refresh) browse.freshIndex += 1
        val targetIndex = browse.freshIndex
        scope.launch {
            try {
                browse.recommendItems = BilibiliRepository.fetchRecommend(targetIndex)
            } catch (e: Exception) {
                // 刷新失败不清空旧列表：保留上一次内容比让页面变白有用。
                browse.recommendError = e.message ?: "加载失败"
                browse.recommendNoNetwork = !BilibiliRepository.isNetworkAvailable(context)
            } finally {
                browse.recommendLoading = false
            }
        }
    }

    fun loadPopular(refresh: Boolean) {
        if (browse.popularLoading || browse.popularLoadingMore) return
        if (refresh) {
            browse.popularPage = 1
            browse.popularHasMore = true
        }
        val targetPage = browse.popularPage
        if (!refresh && !browse.popularHasMore) return
        if (!BilibiliRepository.isNetworkAvailable(context)) {
            browse.popularError = "当前没有网络连接，请检查网络后重试"
            browse.popularNoNetwork = true
            return
        }
        // 首页加载显示骨架，后续页加载显示底部小转圈（两者不要互相抢占状态）。
        if (refresh || browse.popularItems.isEmpty()) {
            browse.popularLoading = true
        } else {
            browse.popularLoadingMore = true
        }
        browse.popularError = null
        browse.popularNoNetwork = false
        scope.launch {
            try {
                val result = BilibiliRepository.fetchPopular(targetPage)
                browse.popularItems = if (refresh || targetPage == 1) {
                    result.items
                } else {
                    browse.popularItems + result.items
                }
                browse.popularHasMore = result.hasMore
            } catch (e: Exception) {
                browse.popularError = e.message ?: "加载失败"
                browse.popularNoNetwork = !BilibiliRepository.isNetworkAvailable(context)
            } finally {
                browse.popularLoading = false
                browse.popularLoadingMore = false
            }
        }
    }

    fun loadSearchPage(page: Int, append: Boolean) {
        val keyword = browse.searchKeyword
        if (keyword.isBlank() || browse.searchLoading || browse.searchLoadingMore) return
        if (!BilibiliRepository.isNetworkAvailable(context)) {
            browse.searchError = "当前没有网络连接，请检查网络后重试"
            browse.searchNoNetwork = true
            return
        }
        if (append) browse.searchLoadingMore = true else browse.searchLoading = true
        browse.searchError = null
        browse.searchNoNetwork = false
        scope.launch {
            try {
                val result = BilibiliRepository.searchVideos(keyword, page)
                browse.searchItems = if (append) browse.searchItems + result.items else result.items
                browse.searchPage = page
                browse.searchHasMore = result.hasMore
            } catch (e: Exception) {
                browse.searchError = e.message ?: "搜索失败"
                browse.searchNoNetwork = !BilibiliRepository.isNetworkAvailable(context)
            } finally {
                browse.searchLoading = false
                browse.searchLoadingMore = false
            }
        }
    }

    fun submitSearch() {
        val keyword = browse.searchInput.trim()
        if (keyword.isBlank()) {
            messageSink.value("请输入搜索关键词")
            return
        }
        browse.searchKeyword = keyword
        browse.searchItems = emptyList()
        browse.searchHasMore = false
        browse.searchPage = 1
        loadSearchPage(1, append = false)
    }

    /** 点热搜词：填进输入框并直接搜。 */
    fun searchHotWord(word: String) {
        browse.searchInput = word
        submitSearch()
    }

    /** 加载频道排行（分区 tab 用；切换分区时按 rid 重新拉）。 */
    fun loadChannel(rid: Int, refresh: Boolean) {
        if (browse.channelLoading) return
        if (!refresh && rid == browse.channelRid && browse.channelItems.isNotEmpty()) return
        if (!BilibiliRepository.isNetworkAvailable(context)) {
            browse.channelError = "当前没有网络连接，请检查网络后重试"
            browse.channelNoNetwork = true
            return
        }
        browse.channelRid = rid
        browse.channelLoading = true
        browse.channelError = null
        browse.channelNoNetwork = false
        scope.launch {
            try {
                browse.channelItems = BilibiliRepository.fetchRanking(rid)
            } catch (e: Exception) {
                browse.channelError = e.message ?: "排行榜加载失败"
                browse.channelNoNetwork = !BilibiliRepository.isNetworkAvailable(context)
            } finally {
                browse.channelLoading = false
            }
        }
    }

    /** 加载 B 站热搜词（搜索 tab 空输入时展示）。 */
    fun loadHotWords() {
        if (browse.hotWordsLoading) return
        browse.hotWordsLoading = true
        browse.hotWordsError = null
        scope.launch {
            try {
                browse.hotWords = BilibiliRepository.fetchSearchHotWords()
                if (browse.hotWords.isEmpty()) browse.hotWordsError = "暂时没有热搜词"
            } catch (e: Exception) {
                browse.hotWordsError = e.message ?: "热搜加载失败"
            } finally {
                browse.hotWordsLoading = false
            }
        }
    }

    /** 刷新 B 站登录态（启动时、登录成功后、退出后都要调）。 */
    fun refreshNavState() {
        scope.launch {
            navState = runCatching { BilibiliRepository.fetchNavState() }.getOrNull()
        }
    }

    /** 退出 B 站登录：清内存/磁盘 Cookie + WebView CookieManager。 */
    fun logoutBilibili() {
        BilibiliRepository.clearSession(context)
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        navState = BiliNavState(isLogin = false, uname = "", avatarUrl = "", mid = 0L)
        messageSink.value("已退出 B 站账号")
    }

    // 进程启动：恢复持久化 Cookie，然后查一次真实登录态。
    LaunchedEffect(Unit) {
        BilibiliRepository.restoreSession(context)
        navState = runCatching { BilibiliRepository.fetchNavState() }.getOrNull()
    }

    // 切 tab 首次进入时按需加载（已加载过就不重复请求，返回本页时列表不会闪）。
    LaunchedEffect(browse.tab) {
        when (browse.tab) {
            BiliTab.RECOMMEND -> if (browse.recommendItems.isEmpty() && !browse.recommendLoading) {
                loadRecommend(refresh = false)
            }
            BiliTab.POPULAR -> if (browse.popularItems.isEmpty() && !browse.popularLoading) {
                loadPopular(refresh = false)
            }
            BiliTab.RANKING -> if (browse.channelItems.isEmpty() && !browse.channelLoading) {
                loadChannel(browse.channelRid, refresh = false)
            }
            BiliTab.SEARCH -> {
                // 搜索 tab 首次进入把热搜词也拉好，输入框为空时直接展示。
                if (browse.hotWords.isEmpty() && !browse.hotWordsLoading) loadHotWords()
            }
        }
    }

    // ---------------- 导航动作 ----------------

    fun openDetail(item: BiliVideoItem) {
        activeBvid = item.bvid
        activeTitle = item.title
        activeMid = item.mid
        activeUpName = item.upName
        route = ROUTE_DETAIL
    }

    /** 点列表卡片直达播放页（对齐参考实现：视频页即详情+评论页，不再单独跳详情）。 */
    fun openVideoDirect(item: BiliVideoItem) {
        activeBvid = item.bvid
        activeTitle = item.title
        activeMid = item.mid
        activeUpName = item.upName
        activeCid = 0L
        activePage = 1
        activeDuration = item.durationSeconds
        route = ROUTE_PLAYER
    }

    fun openPlayer(detail: BiliVideoDetail, pageIndex: Int, cid: Long, durationSeconds: Int) {
        activeBvid = detail.bvid
        activeTitle = detail.title
        activeCid = cid
        activePage = pageIndex
        activeDuration = durationSeconds
        // 详情页的 UP 名/ mid 用它自己的；BiliVideoDetail 没有 mid 字段，沿用列表点入时的值。
        route = ROUTE_PLAYER
    }

    // ---------------- 三层页面 ----------------
    //
    // SaveableStateProvider：详情页/播放页用「bvid + 分 P」做 key 保存各自的可保存状态
    // （播放页的地址、进度；详情页的分 P 选择）。浏览页只有一个 key。
    //
    // AnimatedContent（2026-11-18 需求 3）：路由切换时旧页淡出、新页从侧向轻推入，
    // 位移只有 1/26 屏宽（约 16dp）——"克制流畅"的口径，不做翻页式大位移。
    // 返回浏览层时方向反过来，让"退回去"这个动作有方向感。
    fun screenKeyOf(r: String): String = when (r) {
        ROUTE_DETAIL -> "detail|$activeBvid"
        ROUTE_PLAYER -> "player|$activeBvid|$activePage"
        ROUTE_LOGIN -> ROUTE_LOGIN
        ROUTE_DOWNLOADS -> ROUTE_DOWNLOADS
        ROUTE_SHORTS -> ROUTE_SHORTS
        ROUTE_LIBRARY -> ROUTE_LIBRARY
        ROUTE_SETTINGS -> ROUTE_SETTINGS
        else -> ROUTE_BROWSE
    }

    AnimatedContent(
        targetState = route,
        transitionSpec = {
            val forward = targetState != ROUTE_BROWSE
            // 侧向轻推入 + 极微缩放（0.98→1）：比纯平移多一点"展开"的层次，
            // 但幅度克制，不做翻页式大位移。返回时方向反转。
            val enter = fadeIn(tween(240)) +
                slideInHorizontally(tween(240)) { width ->
                    if (forward) width / 26 else -width / 26
                } +
                scaleIn(tween(240), initialScale = 0.98f)
            enter togetherWith fadeOut(tween(160))
        },
        label = "biliRoute"
    ) { currentRoute ->
        saveableStateHolder.SaveableStateProvider(screenKeyOf(currentRoute)) {
            when (currentRoute) {
            ROUTE_DETAIL -> BiliDetailScreen(
                bvid = activeBvid,
                initialTitle = activeTitle,
                knownDetail = detailCache[activeBvid],
                onDetailLoaded = { detailCache[activeBvid] = it },
                onBack = { route = ROUTE_BROWSE },
                onPlay = { detail, page, cid, duration ->
                    openPlayer(detail, page, cid, duration)
                }
            )

            // key(bvid)：点「相关推荐」换视频时整页重建，播放器/弹幕/评论全部按新 bvid
            // 重新初始化。不加这层 key 的话，remember 状态会沿用上一条视频，
            // 出现"标题换了、还在播上一条"的错位。
            ROUTE_PLAYER -> key(activeBvid) {
                BiliNativePlayerScreen(
                    bvid = activeBvid,
                    cid = activeCid,
                    title = activeTitle,
                    upName = activeUpName.ifBlank { "未知UP主" },
                    mid = activeMid,
                    pageIndex = activePage,
                    onOpenVideo = { item -> openVideoDirect(item) },
                    onBack = { route = ROUTE_BROWSE },
                    onMessage = { messageSink.value(it) }
                )
            }

            ROUTE_LOGIN -> BiliLoginScreen(
                onBack = { route = ROUTE_BROWSE },
                onLoggedIn = {
                    refreshNavState()
                    route = ROUTE_BROWSE
                },
                onMessage = { messageSink.value(it) }
            )

            ROUTE_DOWNLOADS -> BiliDownloadsScreen(
                onBack = { route = ROUTE_BROWSE },
                onMessage = { messageSink.value(it) }
            )

            ROUTE_SHORTS -> BiliShortsScreen(
                onBack = { route = ROUTE_BROWSE },
                onOpenDetail = { item -> openVideoDirect(item) },
                onMessage = { messageSink.value(it) }
            )

            ROUTE_LIBRARY -> BiliLibraryPage(
                initialTab = activeLibraryTab,
                onBack = { route = ROUTE_BROWSE },
                onOpenVideo = { item -> openVideoDirect(item) }
            )

            ROUTE_SETTINGS -> BiliSettingsPage(
                onBack = { route = ROUTE_BROWSE },
                onMessage = { messageSink.value(it) }
            )

            else -> BiliHomeScreen(
                recommendItems = browse.recommendItems,
                popularItems = browse.popularItems,
                channelItems = browse.channelItems,
                searchItems = browse.searchItems,
                hotWords = browse.hotWords,
                navState = navState,
                recommendLoading = browse.recommendLoading,
                popularLoading = browse.popularLoading,
                channelLoading = browse.channelLoading,
                searchLoading = browse.searchLoading,
                searchKeyword = browse.searchKeyword,
                searchInput = browse.searchInput,
                recommendError = browse.recommendError,
                popularError = browse.popularError,
                channelError = browse.channelError,
                searchError = browse.searchError,
                onBack = onBack,
                onOpenVideo = { item -> openVideoDirect(item) },
                onOpenLogin = { route = ROUTE_LOGIN },
                onOpenDownloads = { route = ROUTE_DOWNLOADS },
                onOpenAccount = { showAccountDialog = true },
                onOpenShorts = { route = ROUTE_SHORTS },
                onOpenLibrary = { tab ->
                    activeLibraryTab = tab
                    route = ROUTE_LIBRARY
                },
                onOpenSettings = { route = ROUTE_SETTINGS },
                onRefresh = {
                    // 首页刷新固定刷推荐（当前主 tab），与参考图"推荐"为默认一致。
                    loadRecommend(refresh = true)
                },
                onLoadMorePopular = {
                    if (browse.popularHasMore && !browse.popularLoadingMore) {
                        browse.popularPage += 1
                        loadPopular(refresh = false)
                    }
                },
                onSearchInput = { browse.searchInput = it },
                onSearchSubmit = { submitSearch() },
                onHomeTabChanged = { tab ->
                    when (tab) {
                        "popular" -> if (browse.popularItems.isEmpty() && !browse.popularLoading) {
                            loadPopular(refresh = false)
                        }
                        "anime" -> if (browse.channelItems.isEmpty() && !browse.channelLoading) {
                            loadChannel(1, refresh = false)
                        }
                        "recommend" -> if (browse.recommendItems.isEmpty() && !browse.recommendLoading) {
                            loadRecommend(refresh = false)
                        }
                        // "live" 的数据由 BiliHomeScreen 内部自行加载。
                    }
                },
                onReloadHotWords = { loadHotWords() }
            )
            }
        }
    }

    // 「我的」账号弹窗 / 退出确认（只在浏览层用到，放在最外层不影响其它页面）。
    if (showAccountDialog) {
        val state = navState
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("B 站账号", style = AppType.heading) },
            text = {
                Text(
                    text = if (state?.isLogin == true) {
                        "已登录：${state.uname.ifBlank { "B 站用户" }}" +
                            "\n\n登录 Cookie 明文保存在本机应用私有目录，仅用于 B 站相关功能；" +
                            "退出登录会同时清除磁盘与 WebView 内的 Cookie。"
                    } else {
                        "当前未登录。登录后可以查看更多评论、可能解锁更高清晰度档位；" +
                            "登录信息以明文 Cookie 存在本机，仅用于 B 站相关功能。"
                    },
                    style = AppType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                if (state?.isLogin == true) {
                    TextButton(onClick = {
                        showAccountDialog = false
                        showLogoutConfirm = true
                    }) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    TextButton(onClick = {
                        showAccountDialog = false
                        route = ROUTE_LOGIN
                    }) {
                        Text("去登录")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) { Text("关闭") }
            }
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出 B 站账号？", style = AppType.heading) },
            text = {
                Text(
                    text = "退出后本机保存的登录 Cookie 会被清除；已下载的视频不受影响。",
                    style = AppType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    logoutBilibili()
                }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 浏览层
// ---------------------------------------------------------------------------

/** 路由常量（放进 enum 会与「可保存的 String」之间来回转换，没必要）。 */
private const val ROUTE_BROWSE = "browse"
private const val ROUTE_DETAIL = "detail"
private const val ROUTE_PLAYER = "player"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_DOWNLOADS = "downloads"
private const val ROUTE_SHORTS = "shorts"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_SETTINGS = "settings"

/** 四个浏览 tab（2026-11-03 增强：新增「频道」分区排行）。 */
private enum class BiliTab(val label: String) {
    RECOMMEND("推荐"),
    POPULAR("热门"),
    RANKING("频道"),
    SEARCH("搜索")
}

/**
 * 频道（分区）配置。
 *
 * rid 全部在宿主机实测过 `x/web-interface/ranking/v2?rid=&type=all` 返回 code=0
 * （详见 [BilibiliRepository.fetchRanking] 的 KDoc）；**13 番剧 / 254 未收录**
 * 是因为该接口对它们回 -400。
 */
private data class BiliChannel(val rid: Int, val label: String)

private val BILI_CHANNELS = listOf(
    BiliChannel(0, "总榜"),
    BiliChannel(1, "动画"),
    BiliChannel(168, "国创"),
    BiliChannel(177, "纪录片"),
    BiliChannel(11, "电视剧"),
    BiliChannel(23, "电影"),
    BiliChannel(181, "影视"),
    BiliChannel(4, "游戏"),
    BiliChannel(3, "音乐"),
    BiliChannel(129, "舞蹈"),
    BiliChannel(119, "鬼畜"),
    BiliChannel(36, "知识"),
    BiliChannel(188, "科技"),
    BiliChannel(160, "生活"),
    BiliChannel(211, "美食"),
    BiliChannel(234, "运动"),
    BiliChannel(155, "时尚"),
    BiliChannel(5, "明星")
)

/**
 * 浏览层状态（挂在 [BilibiliScreen] 的 `remember` 里）。
 *
 * 为什么不用 ViewModel：本页状态只服务这一个页面，且进入详情/播放时
 * BilibiliScreen 仍留在组合里，`remember` 天然跨路由存活；引 ViewModel 只会
 * 多一层生命周期语义（项目其它二级页也都是 remember + rememberCoroutineScope 写法）。
 */
private class BiliBrowseState {
    var tab by mutableStateOf(BiliTab.RECOMMEND)

    // 推荐（每次刷新换一批，不做分页）
    var recommendItems by mutableStateOf<List<BiliVideoItem>>(emptyList())
    var recommendLoading by mutableStateOf(false)
    var recommendError by mutableStateOf<String?>(null)
    var recommendNoNetwork by mutableStateOf(false)
    var freshIndex by mutableIntStateOf(1)

    // 热门（分页：加载更多）
    var popularItems by mutableStateOf<List<BiliVideoItem>>(emptyList())
    var popularLoading by mutableStateOf(false)
    var popularError by mutableStateOf<String?>(null)
    var popularNoNetwork by mutableStateOf(false)
    var popularPage by mutableIntStateOf(1)
    var popularHasMore by mutableStateOf(true)
    var popularLoadingMore by mutableStateOf(false)

    // 搜索（输入 + 已提交关键词 + 分页）
    var searchInput by mutableStateOf("")
    var searchKeyword by mutableStateOf("")
    var searchItems by mutableStateOf<List<BiliVideoItem>>(emptyList())
    var searchLoading by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searchNoNetwork by mutableStateOf(false)
    var searchPage by mutableIntStateOf(1)
    var searchHasMore by mutableStateOf(false)
    var searchLoadingMore by mutableStateOf(false)

    // 搜索热词（输入为空时展示，点一下直接搜）
    var hotWords by mutableStateOf<List<String>>(emptyList())
    var hotWordsLoading by mutableStateOf(false)
    var hotWordsError by mutableStateOf<String?>(null)

    // 频道（分区排行榜，2026-11-03 增强）
    var channelRid by mutableIntStateOf(0)
    var channelItems by mutableStateOf<List<BiliVideoItem>>(emptyList())
    var channelLoading by mutableStateOf(false)
    var channelError by mutableStateOf<String?>(null)
    var channelNoNetwork by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BiliBrowseScreen(
    state: BiliBrowseState,
    navState: BiliNavState?,
    onBack: () -> Unit,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onTabChange: (BiliTab) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMorePopular: () -> Unit,
    onChannelSelect: (Int) -> Unit,
    onSearchInput: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchHotWord: (String) -> Unit,
    onReloadHotWords: () -> Unit,
    onLoadMoreSearch: () -> Unit
) {
    BackHandler { onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("B站视频", style = AppType.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 我的下载（2026-11-03 增强）
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Filled.VideoLibrary, contentDescription = "我的下载")
                    }
                    // B 站账号：已登录显示账号弹窗（含退出），未登录直接进登录页。
                    IconButton(
                        onClick = {
                            if (navState?.isLogin == true) onOpenAccount() else onOpenLogin()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = if (navState?.isLogin == true) {
                                "B站账号：${navState.uname.ifBlank { "已登录" }}"
                            } else {
                                "登录 B 站"
                            },
                            tint = if (navState?.isLogin == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    // 顶栏搜索：直接把用户带到搜索 tab（输入框已在那里），少一次点击。
                    IconButton(onClick = { onTabChange(BiliTab.SEARCH) }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(
                        onClick = onRefresh,
                        enabled = !state.recommendLoading &&
                            !state.popularLoading &&
                            !state.channelLoading &&
                            !state.searchLoading
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            BiliTabBar(selected = state.tab, onSelect = onTabChange)

            when (state.tab) {
                BiliTab.RECOMMEND -> RecommendTab(
                    state = state,
                    onOpenVideo = onOpenVideo,
                    onRefresh = onRefresh
                )
                BiliTab.POPULAR -> PopularTab(
                    state = state,
                    onOpenVideo = onOpenVideo,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMorePopular
                )
                BiliTab.RANKING -> RankingTab(
                    state = state,
                    onOpenVideo = onOpenVideo,
                    onChannelSelect = onChannelSelect,
                    onRefresh = onRefresh
                )
                BiliTab.SEARCH -> SearchTab(
                    state = state,
                    onOpenVideo = onOpenVideo,
                    onInputChange = onSearchInput,
                    onSubmit = onSearchSubmit,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMoreSearch,
                    onHotWordClick = onSearchHotWord,
                    onReloadHotWords = onReloadHotWords
                )
            }
        }
    }
}

/** 顶部分段 tab（推荐/热门/搜索）。样式与 GitHub 热门的周期分段一致。 */
@Composable
private fun BiliTabBar(selected: BiliTab, onSelect: (BiliTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.sm)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(Radius.control)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BiliTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(Radius.control - 2.dp)
                    )
                    .clickableNoIndication { onSelect(tab) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tab.label,
                    style = AppType.bodySmall,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = if (isSelected) {
                        onAccent(MaterialTheme.colorScheme.primary)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

// ---- 推荐 tab ----

@Composable
private fun RecommendTab(
    state: BiliBrowseState,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onRefresh: () -> Unit
) {
    when {
        state.recommendLoading && state.recommendItems.isEmpty() -> BiliSkeletonList()
        state.recommendError != null && state.recommendItems.isEmpty() -> BiliErrorPane(
            message = state.recommendError.orEmpty(),
            onRetry = onRefresh,
            noNetwork = state.recommendNoNetwork
        )
        state.recommendItems.isEmpty() -> BiliEmptyPane(
            title = "这里还没有内容",
            hint = "点右上角刷新换一批推荐"
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.lg,
                end = Space.lg,
                top = Space.xs,
                bottom = Space.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (state.recommendError != null) {
                item(key = "recommend-error") {
                    BiliInlineError(
                        message = state.recommendError.orEmpty(),
                        onRetry = onRefresh
                    )
                }
            }
            itemsIndexed(
                items = state.recommendItems,
                key = { index, item -> "recommend-${item.bvid}-$index" }
            ) { _, item ->
                BiliVideoCard(item = item, onClick = { onOpenVideo(item) })
            }
            item(key = "recommend-footer") {
                Text(
                    text = "内容来自 B 站公开推荐接口，每次刷新换一批",
                    style = AppType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.sm)
                )
            }
        }
    }
}

// ---- 热门 tab ----

@Composable
private fun PopularTab(
    state: BiliBrowseState,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit
) {
    when {
        state.popularLoading && state.popularItems.isEmpty() -> BiliSkeletonList()
        state.popularError != null && state.popularItems.isEmpty() -> BiliErrorPane(
            message = state.popularError.orEmpty(),
            onRetry = onRefresh,
            noNetwork = state.popularNoNetwork
        )
        state.popularItems.isEmpty() -> BiliEmptyPane(
            title = "榜单暂时为空",
            hint = "点右上角刷新重试"
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.lg,
                end = Space.lg,
                top = Space.xs,
                bottom = Space.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            if (state.popularError != null) {
                item(key = "popular-error") {
                    BiliInlineError(
                        message = state.popularError.orEmpty(),
                        onRetry = onRefresh
                    )
                }
            }
            itemsIndexed(
                items = state.popularItems,
                key = { index, item -> "popular-${item.bvid}-$index" }
            ) { _, item ->
                BiliVideoCard(item = item, onClick = { onOpenVideo(item) })
            }
            item(key = "popular-footer") {
                ListFooter(
                    loadingMore = state.popularLoadingMore,
                    hasMore = state.popularHasMore,
                    onLoadMore = onLoadMore,
                    endText = "已经到底啦"
                )
            }
        }
    }
}

// ---- 搜索 tab ----

@Composable
private fun SearchTab(
    state: BiliBrowseState,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onHotWordClick: (String) -> Unit,
    onReloadHotWords: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchInput,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xs),
            singleLine = true,
            textStyle = AppType.body,
            placeholder = {
                Text("搜索 B 站视频", style = AppType.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingIcon = {
                IconButton(
                    onClick = onSubmit,
                    enabled = !state.searchLoading && state.searchInput.isNotBlank()
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            },
            // 键盘右下角直接是「搜索」，符合输入法习惯。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() })
        )

        when {
            state.searchLoading && state.searchItems.isEmpty() -> BiliSkeletonList()
            state.searchError != null && state.searchItems.isEmpty() -> BiliErrorPane(
                message = state.searchError.orEmpty(),
                onRetry = onRefresh,
                noNetwork = state.searchNoNetwork
            )
            state.searchKeyword.isBlank() -> SearchHotWords(
                words = state.hotWords,
                loading = state.hotWordsLoading,
                errorMessage = state.hotWordsError,
                onWordClick = onHotWordClick,
                onReload = onReloadHotWords
            )
            state.searchItems.isEmpty() -> BiliEmptyPane(
                title = "没有搜到相关视频",
                hint = "换个关键词试试，比如更短的名字"
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.xs,
                    bottom = Space.xl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                if (state.searchError != null) {
                    item(key = "search-error") {
                        BiliInlineError(
                            message = state.searchError.orEmpty(),
                            onRetry = onRefresh
                        )
                    }
                }
                itemsIndexed(
                    items = state.searchItems,
                    key = { index, item -> "search-${item.bvid}-$index" }
                ) { _, item ->
                    BiliVideoCard(item = item, onClick = { onOpenVideo(item) })
                }
                item(key = "search-footer") {
                    ListFooter(
                        loadingMore = state.searchLoadingMore,
                        hasMore = state.searchHasMore,
                        onLoadMore = onLoadMore,
                        endText = "没有更多结果了"
                    )
                }
            }
        }
    }
}

// ---- 频道（分区排行）tab ----

/**
 * 频道 tab：顶部横向滚动分区 chips + 该分区排行榜（`ranking/v2`）。
 *
 * 为什么单独做 tab 而不是塞进热门页筛选：分区之间是"换一个内容池"，与热门榜的
 * 全站榜语义不同；做成一级 tab + 横向频道更符合 B 站网页"排行榜"的用法。
 */
@Composable
private fun RankingTab(
    state: BiliBrowseState,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onChannelSelect: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            BILI_CHANNELS.forEach { channel ->
                val selected = channel.rid == state.channelRid
                val accent = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .background(
                            if (selected) accent else MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(Radius.pill)
                        )
                        .clickableNoIndication { onChannelSelect(channel.rid) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.label,
                        style = AppType.caption,
                        color = if (selected) {
                            onAccent(accent)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }

        when {
            state.channelLoading && state.channelItems.isEmpty() -> BiliSkeletonList()
            state.channelError != null && state.channelItems.isEmpty() -> BiliErrorPane(
                message = state.channelError.orEmpty(),
                onRetry = onRefresh,
                noNetwork = state.channelNoNetwork
            )
            state.channelItems.isEmpty() -> BiliEmptyPane(
                title = "这个分区暂时没有榜单",
                hint = "换一个分区，或点右上角刷新重试"
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Space.lg,
                    end = Space.lg,
                    top = Space.xs,
                    bottom = Space.xl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                if (state.channelError != null) {
                    item(key = "channel-error") {
                        BiliInlineError(
                            message = state.channelError.orEmpty(),
                            onRetry = onRefresh
                        )
                    }
                }
                itemsIndexed(
                    items = state.channelItems,
                    key = { index, item -> "channel-${state.channelRid}-${item.bvid}-$index" }
                ) { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 名次用等宽数字，两位/一位对齐（与 GitHub 热门页同一做法）。
                        Text(
                            text = "${index + 1}",
                            style = AppType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Space.sm)
                        )
                        BiliVideoCard(
                            item = item,
                            onClick = { onOpenVideo(item) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item(key = "channel-footer") {
                    Text(
                        text = "榜单来自 B 站分区排行榜接口，按内容质量与近期数据动态更新",
                        style = AppType.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Space.sm)
                    )
                }
            }
        }
    }
}

// ---- 搜索热词 ----

/**
 * 搜索 tab 空输入时的 B 站热搜词（2026-11-03 增强）。
 *
 * 数据来自 `s.search.bilibili.com/main/hotword`（实测未登录可用），点一下直接搜；
 * 拉取失败只影响这一块，搜索本身仍可用，所以给「重试」而不是整页错误。
 */
@Composable
private fun SearchHotWords(
    words: List<String>,
    loading: Boolean,
    errorMessage: String?,
    onWordClick: (String) -> Unit,
    onReload: () -> Unit
) {
    when {
        loading -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.xl),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = "正在获取 B 站热搜…",
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        errorMessage != null -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = errorMessage,
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onReload) { Text("重试") }
        }

        words.isEmpty() -> BiliEmptyPane(
            title = "搜索 B 站视频",
            hint = "输入关键词，按键盘搜索键或右侧放大镜"
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Space.lg,
                end = Space.lg,
                top = Space.sm,
                bottom = Space.xl
            )
        ) {
            item(key = "hot-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "B站热搜",
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReload) { Text("换一批") }
                }
            }
            itemsIndexed(
                words,
                key = { index, word -> "hot-$index-$word" }
            ) { index, word ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoIndication { onWordClick(word) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        style = AppType.caption,
                        color = if (index < 3) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(end = Space.md)
                    )
                    Text(
                        text = word,
                        style = AppType.body,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item(key = "hot-footer") {
                Text(
                    text = "热词来自 B 站搜索接口，点一条直接搜索",
                    style = AppType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.sm)
                )
            }
        }
    }
}

// ---- 小部件 ----

/** 列表内联错误条：已有旧数据时刷新失败，只在顶部提示一句，不整页报错。 */
@Composable
private fun BiliInlineError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(Radius.control)
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

/** 分页列表底部：加载中转圈 / 「加载更多」按钮 / 到底文案。 */
@Composable
private fun ListFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    endText: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.md),
        contentAlignment = Alignment.Center
    ) {
        when {
            loadingMore -> CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )

            hasMore -> Button(onClick = onLoadMore) { Text("加载更多") }

            else -> Text(
                text = endText,
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// 提示：BilibiliScreen 的对外签名（onBack / onMessage）在本次改造中保持不变，
// TabNavHost 的调用点无需改动。
