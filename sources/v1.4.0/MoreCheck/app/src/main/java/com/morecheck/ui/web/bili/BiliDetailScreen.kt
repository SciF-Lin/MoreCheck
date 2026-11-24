package com.morecheck.ui.web.bili

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.data.net.BilibiliRepository.BiliComment
import com.morecheck.data.net.BilibiliRepository.BiliPage
import com.morecheck.data.net.BilibiliRepository.BiliVideoDetail
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import com.morecheck.ui.theme.onAccent
import kotlinx.coroutines.launch

/**
 * 「B站视频」原生详情页（2026-11-03 新增）。
 *
 * ## 为什么要有这一层
 *
 * 用户需求里有「视频详情页显示简介」。直接在列表卡片上塞简介会很挤，
 * 而只靠 WebView 播放页又看不到简介（H5 播放页的简介在很下面）。
 * 所以两者之间加一层原生详情页：封面、标题、UP 主、播放/点赞/弹幕数、
 * 发布时间、完整简介、多 P 选择，底部一个「播放视频」主按钮。
 *
 * ## 为什么下载入口不在这里
 *
 * 用户明确要求「下载按钮不要常驻，只在播放页提供入口」，所以本页刻意
 * **不放下载按钮**：想下载就先播放，再从播放页顶栏的下载图标进清晰度选择。
 * 这样"下载"这个动作永远发生在用户已经决定要看这个视频之后。
 *
 * @param bvid 视频 BV 号。
 * @param initialTitle 列表带过来的标题（详情还没加载完时先显示，避免标题栏空着）。
 * @param knownDetail 父级缓存里的详情（从播放页返回时不重复请求）。
 * @param onDetailLoaded 加载成功后回写父级缓存。
 * @param onPlay 点「播放视频」：把详情、分 P 序号、该 P 的 cid 交给播放页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliDetailScreen(
    bvid: String,
    initialTitle: String,
    knownDetail: BiliVideoDetail?,
    onDetailLoaded: (BiliVideoDetail) -> Unit,
    onBack: () -> Unit,
    onPlay: (
        detail: BiliVideoDetail,
        pageIndex: Int,
        cid: Long,
        durationSeconds: Int
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detail by remember(bvid) { mutableStateOf(knownDetail) }
    // loading 初值 = 还没拿到详情（用于首帧就显示加载态，而不是先闪一下错误页）。
    var loading by remember { mutableStateOf(knownDetail == null) }
    // requesting 是「有没有在飞的请求」——不能用 loading 当重入锁：
    // 首帧 loading 已经是 true，用 loading 判断会把第一次加载直接挡掉（空转死锁）。
    var requesting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }

    // 当前选中的分 P 序号（单 P 视频恒为 1）。rememberSaveable：旋转后不丢选择。
    var selectedPageIndex by rememberSaveable(bvid) { mutableIntStateOf(1) }

    // ---- 评论（2026-11-03 增强）----
    var comments by remember { mutableStateOf<List<BiliComment>>(emptyList()) }
    var commentTotal by remember { mutableIntStateOf(0) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsLoadingMore by remember { mutableStateOf(false) }
    var commentsError by remember { mutableStateOf<String?>(null) }
    var commentPage by remember { mutableIntStateOf(0) }
    var commentsHasMore by remember { mutableStateOf(false) }

    /**
     * 拉评论。
     *
     * 实测限制（见 [BilibiliRepository.fetchComments]）：未登录时接口只回 3 条/页、
     * 且翻页直接空；所以「加载更多」只在服务端明确回满一页（hasMore）时才显示，
     * 未登录时用一句说明解释为什么只有几条。
     */
    fun loadComments(reset: Boolean) {
        val aid = detail?.aid ?: 0L
        if (aid <= 0L || commentsLoading || commentsLoadingMore) return
        val targetPage = if (reset) 1 else commentPage + 1
        if (!reset && !commentsHasMore) return
        if (reset) {
            commentsLoading = true
        } else {
            commentsLoadingMore = true
        }
        commentsError = null
        scope.launch {
            try {
                val result = BilibiliRepository.fetchComments(aid, targetPage)
                comments = if (reset) result.comments else comments + result.comments
                commentTotal = result.totalCount
                commentPage = targetPage
                commentsHasMore = result.hasMore
            } catch (e: Exception) {
                commentsError = e.message ?: "评论加载失败"
            } finally {
                commentsLoading = false
                commentsLoadingMore = false
            }
        }
    }

    fun load() {
        if (requesting) return
        requesting = true
        loading = true
        errorMessage = null
        noNetwork = false
        if (!BilibiliRepository.isNetworkAvailable(context)) {
            errorMessage = "当前没有网络连接，请检查网络后重试"
            noNetwork = true
            loading = false
            requesting = false
            return
        }
        scope.launch {
            try {
                val fetched = BilibiliRepository.fetchDetail(bvid)
                detail = fetched
                onDetailLoaded(fetched)
                // 多 P 视频默认选中第 1 P；记住的序号越界时回落到第 1 P。
                if (fetched.pages.none { it.index == selectedPageIndex }) {
                    selectedPageIndex = fetched.pages.firstOrNull()?.index ?: 1
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "加载失败"
                noNetwork = !BilibiliRepository.isNetworkAvailable(context)
            } finally {
                loading = false
                requesting = false
            }
        }
    }

    LaunchedEffect(bvid) {
        if (detail == null) load()
    }

    // 详情（拿到 aid）后自动拉第一页评论；换视频时重新拉。
    LaunchedEffect(detail?.aid) {
        val aid = detail?.aid ?: 0L
        if (aid > 0L && comments.isEmpty() && !commentsLoading) {
            loadComments(reset = true)
        }
    }

    BackHandler { onBack() }

    val current = detail
    val selectedPage: BiliPage? = current?.pages
        ?.firstOrNull { it.index == selectedPageIndex }
        ?: current?.pages?.firstOrNull()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.title ?: initialTitle.ifBlank { "视频详情" },
                        style = AppType.heading,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // 详情加载出来之后才有「播放」的意义；加载中/失败时不显示按钮，避免点了没反应。
            if (current != null && selectedPage != null) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // ⚠️ 必须自己让出系统导航栏（2026-11-03 上机实测）：
                            // 本页是 TabNavHost 里的二级页，外层不会替它扣导航栏内边距；
                            // 而 Scaffold 的 bottomBar **槽位本身不消费系统 inset**。
                            // 三键导航机上不加这行时，按钮下半截压在导航栏触摸区后面——
                            // 点「播放视频」实际按到的是系统 Home（uiautomator 实测
                            // bounds 到 y≈2695，导航栏触摸区从 y≈2650 开始，直接回桌面）。
                            .navigationBarsPadding()
                            .padding(horizontal = Space.lg, vertical = Space.md)
                    ) {
                        Button(
                            onClick = {
                                onPlay(
                                    current,
                                    selectedPage.index,
                                    selectedPage.cid,
                                    selectedPage.durationSeconds
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                text = if (current.pages.size > 1) {
                                    "播放 P${selectedPage.index}"
                                } else {
                                    "播放视频"
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading && current == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(Space.md))
                        Text(
                            text = "正在加载视频信息…",
                            style = AppType.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                current == null -> BiliErrorPane(
                    message = errorMessage ?: "视频信息加载失败",
                    onRetry = { load() },
                    noNetwork = noNetwork
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 封面：16:9 通栏，与 B 站详情页的主视觉一致。
                    BiliCover(
                        url = current.coverUrl,
                        contentDescription = current.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                        cornerRadius = 0.dp
                    )

                    Column(modifier = Modifier.padding(Space.lg)) {
                        Text(
                            text = current.title,
                            style = AppType.heading,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(Space.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "${formatPlayCount(current.viewCount)} 播放",
                                style = AppType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(Space.md))
                            Text(
                                text = "${formatPlayCount(current.danmakuCount)} 弹幕",
                                style = AppType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(Space.md))
                            Text(
                                text = "${formatPlayCount(current.likeCount)} 点赞",
                                style = AppType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = "UP主：${current.upName} · ${formatPublishDate(current.pubdate)} · " +
                                "时长 ${formatDuration(current.durationSeconds)}",
                            style = AppType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (current.pages.size > 1) {
                            Spacer(Modifier.height(Space.md))
                            Text(
                                text = "选集（共 ${current.pages.size} P）",
                                style = AppType.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(Space.sm))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(Space.sm)
                            ) {
                                current.pages.forEach { page ->
                                    val isSelected = page.index == selectedPageIndex
                                    val accent = MaterialTheme.colorScheme.primary
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) accent
                                                else MaterialTheme.colorScheme.surfaceContainer,
                                                RoundedCornerShape(Radius.pill)
                                            )
                                            .then(
                                                if (isSelected) {
                                                    Modifier
                                                } else {
                                                    Modifier.border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outlineVariant,
                                                        RoundedCornerShape(Radius.pill)
                                                    )
                                                }
                                            )
                                            .clickableNoIndication {
                                                selectedPageIndex = page.index
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "P${page.index} ${page.title}",
                                            style = AppType.caption,
                                            color = if (isSelected) {
                                                onAccent(accent)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(Space.lg))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(Space.md))
                        Text(
                            text = "简介",
                            style = AppType.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = current.desc,
                            style = AppType.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(Space.lg))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BiliCommentsSection(
                            comments = comments,
                            totalCount = commentTotal,
                            loading = commentsLoading,
                            loadingMore = commentsLoadingMore,
                            hasMore = commentsHasMore,
                            errorMessage = commentsError,
                            loggedIn = BilibiliRepository.isLoggedIn(),
                            onLoadMore = { loadComments(reset = false) },
                            onRetry = { loadComments(reset = true) }
                        )
                        Spacer(Modifier.height(Space.xxl))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 评论区块
// ---------------------------------------------------------------------------

/**
 * 详情页评论列表（2026-11-03 增强）。
 *
 * 设计取向：不抢「播放」主按钮的风头——评论放在简介下面，用较淡的层级；
 * 未登录时只显示服务端给的那几条并明确说明原因（实测匿名只回 3 条），
 * 不伪造「加载更多」。
 */
@Composable
private fun BiliCommentsSection(
    comments: List<BiliComment>,
    totalCount: Int,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    errorMessage: String?,
    loggedIn: Boolean,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Space.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "评论",
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (totalCount > 0) {
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "共 $totalCount 条",
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!loggedIn) {
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "未登录时 B 站只返回少量评论；登录后可查看更多（登录入口在视频列表页顶栏）。",
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Space.sm))

        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "正在加载评论…",
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            errorMessage != null -> Column {
                Text(
                    text = errorMessage,
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }

            comments.isEmpty() -> Text(
                text = "还没有评论（或该视频关闭了评论区）",
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            else -> Column {
                comments.forEachIndexed { index, comment ->
                    if (index > 0) {
                        Spacer(Modifier.height(Space.md))
                    }
                    CommentRow(comment)
                }
                if (hasMore) {
                    Spacer(Modifier.height(Space.sm))
                    if (loadingMore) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(Space.sm))
                            Text(
                                text = "加载更多评论…",
                                style = AppType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        TextButton(onClick = onLoadMore) { Text("加载更多评论") }
                    }
                }
            }
        }
    }
}

/** 单条评论：头像 + 昵称 + 时间 + 正文 + 点赞数。 */
@Composable
private fun CommentRow(comment: BiliComment) {
    Row(modifier = Modifier.fillMaxWidth()) {
        BiliCover(
            url = comment.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            cornerRadius = Radius.pill
        )
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = comment.uname,
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = formatCommentTime(comment.ctimeSeconds),
                    style = AppType.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = comment.message,
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("👍 ")
                    append(comment.likeCount)
                    if (comment.replyCount > 0) {
                        append(" · ").append(comment.replyCount).append(" 条回复")
                    }
                },
                style = AppType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 评论时间：相对时间（刚刚 / x分钟前 / x小时前 / x天前），超过 30 天显示日期。 */
private fun formatCommentTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val diff = System.currentTimeMillis() / 1000 - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60} 分钟前"
        diff < 86_400 -> "${diff / 3600} 小时前"
        diff < 30L * 86_400 -> "${diff / 86_400} 天前"
        else -> formatPublishDate(epochSeconds)
    }
}
