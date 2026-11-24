package com.morecheck.ui.web.bili

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import com.morecheck.ui.theme.animateThemeColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.data.net.BilibiliRepository.BiliNavState
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.data.net.BiliLibraryStore

/**
 * B 站首页（2026-11-18 按参考实现重做）。
 *
 * 视觉尺寸、配色、图标全部取自对参考 APK（`com.lihuan.bili` v15.81）的逆向：
 * 顶栏渐变（浅色粉 / 深色深灰）、36dp 胶囊搜索框、46dp 分类条、30dp 胶囊、
 * 双列卡片（封面固定 100dp、标题固定 38dp 两行）、64dp 悬浮玻璃底部导航。
 *
 * 深浅两套颜色见 [BiliColors]；图标是从参考 APK 提取的矢量，放在 `drawable/bili_ic_*`。
 */
@Composable
fun BiliHomeScreen(
    recommendItems: List<BiliVideoItem>,
    popularItems: List<BiliVideoItem>,
    channelItems: List<BiliVideoItem>,
    searchItems: List<BiliVideoItem>,
    hotWords: List<String>,
    navState: BiliNavState?,
    recommendLoading: Boolean,
    popularLoading: Boolean,
    channelLoading: Boolean,
    searchLoading: Boolean,
    searchKeyword: String,
    searchInput: String,
    recommendError: String?,
    popularError: String?,
    channelError: String?,
    searchError: String?,
    onBack: () -> Unit,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onOpenLogin: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenShorts: () -> Unit,
    onOpenLibrary: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMorePopular: () -> Unit,
    onSearchInput: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onHomeTabChanged: (String) -> Unit,
    onReloadHotWords: () -> Unit
) {
    BiliTheme {
        val c = biliColors()
        val context = LocalContext.current
        var bottom by rememberSaveable { mutableIntStateOf(0) }
        var homeTab by rememberSaveable { mutableIntStateOf(1) }
        var searching by remember { mutableStateOf(false) }
        // 只在搜索态拦截返回键：浏览层本身是 tab，退出由宿主处理；
        // 若这里无条件拦截，从播放页返回时会连锁退出到发现页。
        BackHandler(enabled = searching) {
            searching = false
            onSearchInput("")
        }

        // 从「发现 → B站视频」点进来时整页淡入 240ms：上一版是硬切，观感上像
        // 屏幕闪了一下。只做透明度、不做位移，避免和内部 AnimatedContent 打架。
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val enterAlpha by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(240),
            label = "biliHomeEnter"
        )

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = enterAlpha }
                .background(c.page)
        ) {
            Column(Modifier.fillMaxSize()) {
                BiliTopBar(
                    navState = navState,
                    searching = searching,
                    searchInput = searchInput,
                    onSearchInput = onSearchInput,
                    onSearchSubmit = onSearchSubmit,
                    onStartSearch = {
                        searching = true
                        onReloadHotWords()
                    },
                    onCloseSearch = {
                        searching = false
                        onSearchInput("")
                    },
                    onOpenLogin = onOpenLogin,
                    onOpenAccount = onOpenAccount,
                    onBack = onBack
                )

                // 页面切换动画（2026-11-18 需求 3）：220ms 淡入 + 极轻上移，
                // 退出只淡出。位移量取 1/32 屏高（约 8dp）——再大就会像"翻页"，
                // 与这个 App 扁平、克制的整体调性不符。
                AnimatedContent(
                    targetState = when {
                        searching -> BiliPage.Search
                        bottom == 1 -> BiliPage.Follow
                        bottom == 2 -> BiliPage.Profile
                        else -> BiliPage.Home(homeTab)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 32 })
                            .togetherWith(fadeOut(tween(140)))
                    },
                    label = "biliPage"
                ) { page ->
                    when (page) {
                        BiliPage.Search -> BiliSearchPage(
                            items = searchItems,
                            hotWords = hotWords,
                            loading = searchLoading,
                            error = searchError,
                            keyword = searchKeyword,
                            onOpenVideo = onOpenVideo,
                            onHotWord = {
                                onSearchInput(it)
                                onSearchSubmit()
                            },
                            onRetry = {
                                if (searchInput.isNotBlank()) onSearchSubmit() else onReloadHotWords()
                            }
                        )

                        BiliPage.Follow -> BiliFollowPage(
                            navState = navState,
                            onOpenLogin = onOpenLogin,
                            onOpenVideo = onOpenVideo
                        )

                        BiliPage.Profile -> BiliProfilePage(
                            navState = navState,
                            onOpenLogin = onOpenLogin,
                            onOpenDownloads = onOpenDownloads,
                            onOpenAccount = onOpenAccount,
                            onOpenShorts = onOpenShorts,
                            onOpenLibrary = onOpenLibrary,
                            onOpenSettings = onOpenSettings
                        )

                        is BiliPage.Home -> {
                            // ⚠️ 必须包 Column：AnimatedContent 的内容槽是 **Box 语义**，
                            // 直接并排放 ChipBar 与 Grid 会重叠（实测分类栏被列表盖住）。
                            Column(Modifier.fillMaxSize()) {
                                BiliChipBar(
                                    selected = page.tab,
                                    onSelect = { idx ->
                                        // 第 5 个胶囊是「短视频」：它不是列表页，而是
                                        // 打开整屏竖滑流，所以不参与 homeTab 的取值。
                                        if (idx == BILI_CHIP_SHORTS) {
                                            onOpenShorts()
                                        } else {
                                            homeTab = idx
                                            onHomeTabChanged(
                                                when (idx) {
                                                    0 -> "live"
                                                    1 -> "recommend"
                                                    2 -> "popular"
                                                    else -> "anime"
                                                }
                                            )
                                        }
                                    }
                                )
                                BiliDivider()
                                when (page.tab) {
                                    0 -> BiliLiveGrid(context)
                                    2 -> BiliVideoGrid(
                                        items = popularItems,
                                        loading = popularLoading,
                                        error = popularError,
                                        onOpenVideo = onOpenVideo,
                                        onLoadMore = onLoadMorePopular
                                    )
                                    3 -> BiliVideoGrid(
                                        items = channelItems,
                                        loading = channelLoading,
                                        error = channelError,
                                        onOpenVideo = onOpenVideo,
                                        onLoadMore = null
                                    )
                                    else -> BiliVideoGrid(
                                        items = recommendItems,
                                        loading = recommendLoading,
                                        error = recommendError,
                                        onOpenVideo = onOpenVideo,
                                        onLoadMore = null,
                                        onRetry = onRefresh
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 手机导航栏区域的「磨砂渐隐」（2026-11-21 需求 2）。
            //
            // 目标：底栏改回半透明后，它下方那一条（含系统手势条）不能直接露出
            // 生硬的视频封面，需要「半透明 + 从上往下渐渐加深」。
            //
            // ⚠️ 关于「模糊」的实现取舍：Compose 的 `Modifier.blur()` 只能模糊
            // **自己画出来的内容**，无法采样它背后的内容；想对底下的信息流做真高斯模糊，
            // 必须把整页内容离屏复制一份再裁到这条带子里，代价是每帧一次全屏离屏渲染
            // （本机模拟器还是 swiftshader 软渲染，必卡）。所以这里用**垂直渐变遮罩**
            // 实现同样的观感：越往下越接近页面底色，等于把内容"糊"进背景里。
            // 若以后要真模糊，正确做法是给内容加 `rememberGraphicsLayer()` 录制 +
            // `RenderEffect` 采样，而不是给这一层加 blur。
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(
                        BiliDimen.navHeight + BiliDimen.navMarginBottom + navInset + 24.dp
                    )
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.38f to c.page.copy(alpha = 0.42f),
                            0.72f to c.page.copy(alpha = 0.80f),
                            1f to c.page.copy(alpha = 0.97f)
                        )
                    )
            )

            BiliBottomBar(
                selected = bottom,
                onSelect = { bottom = it },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 顶栏
// ---------------------------------------------------------------------------

@Composable
private fun BiliTopBar(
    navState: BiliNavState?,
    searching: Boolean,
    searchInput: String,
    onSearchInput: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onStartSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenAccount: () -> Unit,
    onBack: () -> Unit
) {
    val c = biliColors()
    // 搜索态一进入就自动聚焦输入框并弹键盘（用户点搜索框后不必再点一次输入框）。
    // 用 FocusRequester 而不是在组合期直接 show：Compose 要求焦点请求在 LaunchedEffect 里发，
    // 键盘由 BasicTextField 获得焦点后系统自动弹起，再补一次 show 兜底部分机型不弹的问题。
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searching) {
        if (searching) {
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(c.headerStart, c.headerEnd)))
            .statusBarsPadding()
            .padding(
                start = BiliDimen.topBarPadH,
                end = BiliDimen.topBarPadH,
                top = BiliDimen.topBarPadTop,
                bottom = BiliDimen.topBarPadBottom
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                Icon(
                    painterResource(R.drawable.bili_ic_back),
                    "返回",
                    tint = c.headerOn,
                    modifier = Modifier.size(22.dp).clickable { onCloseSearch() }
                )
            } else {
                // 顶栏左侧改为返回键（移除原 Cheers 标题与 🍊 图标、消息按钮）：
                // B 站是「发现」下的二级模块，返回语义比品牌 logo 更有用。
                Box(
                    Modifier
                        .size(BiliDimen.avatarTop)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.bili_ic_back),
                        "返回",
                        tint = c.headerOn,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 2026-11-19 二轮需求 4：品牌标题靠左（返回键右侧），
                // 不再居中——居中时与两侧圆钮的视觉重心互相抢。
                Spacer(Modifier.width(10.dp))
                Text(
                    "bilibili",
                    color = c.headerOn,
                    fontSize = BiliText.logo,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(BiliDimen.avatarTop)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                        .clickable {
                            if (navState?.isLogin == true) onOpenAccount() else onOpenLogin()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.bili_ic_person),
                        "账号",
                        tint = c.headerOn,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (searching) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BiliDimen.searchHeight)
                    .clip(RoundedCornerShape(BiliDimen.searchRadius))
                    .background(c.searchFill)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(R.drawable.bili_ic_search),
                    null,
                    tint = c.headerOn.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = searchInput,
                    onValueChange = onSearchInput,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = c.headerOn,
                        fontSize = BiliText.search
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                    decorationBox = { inner ->
                        if (searchInput.isEmpty()) {
                            Text(
                                "搜索视频、UP主",
                                color = c.headerOn.copy(alpha = 0.9f),
                                fontSize = BiliText.search
                            )
                        }
                        inner()
                    }
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BiliDimen.searchHeight)
                    .clip(RoundedCornerShape(BiliDimen.searchRadius))
                    .background(c.searchFill)
                    .clickable { onStartSearch() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painterResource(R.drawable.bili_ic_search),
                    "搜索",
                    tint = c.headerOn.copy(alpha = 0.9f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "搜索视频、UP主",
                    color = c.headerOn.copy(alpha = 0.92f),
                    fontSize = BiliText.search
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分类胶囊
// ---------------------------------------------------------------------------

@Composable
private fun BiliChipBar(selected: Int, onSelect: (Int) -> Unit) {
    val c = biliColors()
    val tabs = listOf("直播", "推荐", "热门", "动画", "短视频")
    val shape = RoundedCornerShape(BiliDimen.chipRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(BiliDimen.chipBarHeight)
            .background(c.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tabs.forEachIndexed { idx, label ->
            val on = selected == idx
            // 选中态颜色 200ms 过渡：胶囊从白底描边"变实"的过程能看清，
            // 比瞬间切换更容易判断焦点落在哪个分类上。
            val bg by animateThemeColorAsState(
                targetValue = if (on) c.pink else c.chipBg,
                animationSpec = tween(200),
                label = "chipBg"
            )
            val fg by animateThemeColorAsState(
                targetValue = if (on) Color.White else c.chipText,
                animationSpec = tween(200),
                label = "chipFg"
            )
            val borderAlpha by animateFloatAsState(
                targetValue = if (on) 0f else 1f,
                animationSpec = tween(200),
                label = "chipBorder"
            )
            Box(
                Modifier
                    .height(BiliDimen.chipHeight)
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, c.divider.copy(alpha = borderAlpha), shape)
                    .clickable { onSelect(idx) }
                    .padding(horizontal = BiliDimen.chipPadH),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = fg,
                    fontSize = BiliText.chip,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun BiliDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(biliColors().divider))
}

// ---------------------------------------------------------------------------
// 双列视频流
// ---------------------------------------------------------------------------

@Composable
private fun BiliVideoGrid(
    items: List<BiliVideoItem>,
    loading: Boolean,
    error: String?,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onLoadMore: (() -> Unit)?,
    onRetry: (() -> Unit)? = null
) {
    val c = biliColors()
    when {
        loading && items.isEmpty() -> BiliLoading(c)
        items.isEmpty() -> BiliEmptyState(error ?: "暂时没有内容", onRetry)
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BiliDimen.gridPad, end = BiliDimen.gridPad,
                top = BiliDimen.gridPad, bottom = 112.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(BiliDimen.gridSpace),
            verticalArrangement = Arrangement.spacedBy(BiliDimen.gridSpace + 6.dp)
        ) {
            gridItems(items, key = { it.bvid + it.pubdate }) { item ->
                BiliGridCard(item = item, onClick = { onOpenVideo(item) })
            }
            if (onLoadMore != null) {
                item(span = { GridItemSpan(2) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "加载更多",
                            color = c.pink,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { onLoadMore() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BiliGridCard(item: BiliVideoItem, onClick: () -> Unit) {
    val c = biliColors()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BiliDimen.coverHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface2)
        ) {
            if (item.coverUrl.isNotBlank()) {
                BiliCover(
                    url = item.coverUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 8.dp
                )
            }
            if (item.durationSeconds > 0) {
                Text(
                    text = formatDuration(item.durationSeconds),
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Text(
            text = item.title,
            color = c.textPrimary,
            fontSize = BiliText.cardTitle,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .height(BiliDimen.titleHeight)
                .padding(top = BiliDimen.titleMarginTop)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = BiliDimen.infoMarginTop),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painterResource(R.drawable.bili_ic_video_dot),
                null,
                tint = c.textHint,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = gridInfoText(item),
                color = c.textHint,
                fontSize = BiliText.cardInfo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 卡片信息行：有播放量显示「播放量 · UP主」，否则「UP主 · 日期」。 */
private fun gridInfoText(item: BiliVideoItem): String =
    if (item.viewCount > 0) {
        "${formatPlayCount(item.viewCount)} · ${item.upName}"
    } else {
        "${item.upName} · ${formatPublishDate(item.pubdate)}"
    }

// ---------------------------------------------------------------------------
// 搜索页
// ---------------------------------------------------------------------------

@Composable
private fun BiliSearchPage(
    items: List<BiliVideoItem>,
    hotWords: List<String>,
    loading: Boolean,
    error: String?,
    keyword: String,
    onOpenVideo: (BiliVideoItem) -> Unit,
    onHotWord: (String) -> Unit,
    onRetry: () -> Unit
) {
    val c = biliColors()
    when {
        loading -> BiliLoading(c)

        keyword.isBlank() -> Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 112.dp)
        ) {
            Spacer(Modifier.height(14.dp))
            Text("大家都在搜", color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (hotWords.isEmpty()) {
                Text(
                    error ?: "热搜加载中…",
                    color = c.textHint,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onRetry() }
                )
            } else {
                hotWords.forEachIndexed { i, w ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onHotWord(w) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}",
                            color = if (i < 3) c.pink else c.textHint,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(26.dp)
                        )
                        Text(w, color = c.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        items.isEmpty() -> BiliEmptyState(error ?: "没有找到相关视频", onRetry)

        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp)
        ) {
            items(items, key = { it.bvid }) { item ->
                BiliVideoCard(item = item, onClick = { onOpenVideo(item) })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 直播
// ---------------------------------------------------------------------------

@Composable
private fun BiliLiveGrid(context: Context) {
    val c = biliColors()
    var rooms by remember { mutableStateOf<List<BilibiliRepository.BiliLiveRoom>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            rooms = BilibiliRepository.fetchLiveRooms(1)
        } catch (e: Exception) {
            error = e.message ?: "直播列表加载失败"
        } finally {
            loading = false
        }
    }

    when {
        loading -> BiliLoading(c)
        rooms.isEmpty() -> BiliEmptyState(error ?: "暂时没有推荐的直播", null)
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BiliDimen.gridPad, end = BiliDimen.gridPad,
                top = BiliDimen.gridPad, bottom = 112.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(BiliDimen.gridSpace),
            verticalArrangement = Arrangement.spacedBy(BiliDimen.gridSpace + 6.dp)
        ) {
            gridItems(rooms, key = { it.roomId }) { room ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://live.bilibili.com/${room.roomId}"))
                            )
                        }
                    }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(BiliDimen.coverHeight)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.surface2)
                    ) {
                        if (room.coverUrl.isNotBlank()) {
                            BiliCover(room.coverUrl, room.title, Modifier.fillMaxSize(), 8.dp)
                        }
                        Text(
                            "直播中",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(c.pink)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        room.title,
                        color = c.textPrimary,
                        fontSize = BiliText.cardTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = BiliDimen.titleMarginTop)
                    )
                    Text(
                        "${room.userName} · ${room.areaName}",
                        color = c.textHint,
                        fontSize = BiliText.cardInfo,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 关注页（动态）
// ---------------------------------------------------------------------------

@Composable
private fun BiliFollowPage(
    navState: BiliNavState?,
    onOpenLogin: () -> Unit,
    onOpenVideo: (BiliVideoItem) -> Unit
) {
    val c = biliColors()
    if (navState?.isLogin != true) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painterResource(R.drawable.bili_ic_person),
                null,
                tint = c.pink,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("登录后查看关注动态", color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "关注 UP 主后，这里会显示他们的最新投稿与动态",
                color = c.textHint,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "去登录",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.pink)
                    .clickable { onOpenLogin() }
                    .padding(horizontal = 28.dp, vertical = 8.dp)
            )
        }
        return
    }

    var items by remember { mutableStateOf<List<BilibiliRepository.BiliDynamicItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            items = BilibiliRepository.fetchDynamicFeed().first
        } catch (e: Exception) {
            error = e.message ?: "动态加载失败"
        } finally {
            loading = false
        }
    }

    when {
        loading -> BiliLoading(c)
        items.isEmpty() -> BiliEmptyState(error ?: "还没有动态，去关注一些 UP 主吧", null)
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { it.id }) { d ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c.surface2)
                        ) {
                            if (d.upAvatar.isNotBlank()) {
                                BiliCover(d.upAvatar, d.upName, Modifier.fillMaxSize(), 17.dp)
                            }
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.upName, color = c.pink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(d.timeText, color = c.textHint, fontSize = 11.sp)
                        }
                    }
                    if (d.text.isNotBlank()) {
                        Text(
                            d.text,
                            color = c.textPrimary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    d.video?.let { v ->
                        Spacer(Modifier.height(8.dp))
                        BiliVideoCard(item = v, onClick = { onOpenVideo(v) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 我的页
// ---------------------------------------------------------------------------

@Composable
private fun BiliProfilePage(
    navState: BiliNavState?,
    onOpenLogin: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenShorts: () -> Unit,
    onOpenLibrary: (Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    val c = biliColors()
    val context = LocalContext.current
    val history by BiliLibraryStore.historyOf(context).collectAsState(initial = emptyList())
    val watchLater by BiliLibraryStore.watchLaterOf(context).collectAsState(initial = emptyList())
    val favorites by BiliLibraryStore.favoritesOf(context).collectAsState(initial = emptyList())

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 112.dp)
    ) {
        // ---- header ----
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.headerStart, c.headerEnd)))
                .statusBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 24.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(BiliDimen.profileAvatar)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.bili_ic_person),
                        null,
                        tint = c.headerOn,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (navState?.isLogin == true) navState.uname.ifBlank { "B站用户" } else "未登录",
                        color = c.headerOn,
                        fontSize = BiliText.profileName,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (navState?.isLogin == true) "UID ${navState.mid}" else "登录后同步收藏、历史与关注",
                        color = c.headerOn.copy(alpha = 0.85f),
                        fontSize = BiliText.profileSub
                    )
                }
                Text(
                    if (navState?.isLogin == true) "账号" else "登录",
                    color = c.pink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE6FFFFFF))
                        .clickable { if (navState?.isLogin == true) onOpenAccount() else onOpenLogin() }
                        .padding(horizontal = 16.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }

        // ---- 功能列表（历史/收藏/稍后再看走二级页；移除无实现的死行）----
        BiliProfileRow(R.drawable.bili_ic_history, "观看历史", "${history.size}") { onOpenLibrary(0) }
        BiliProfileRow(R.drawable.bili_ic_star, "我的收藏", "${favorites.size}") { onOpenLibrary(2) }
        BiliProfileRow(R.drawable.bili_ic_history, "稍后再看", "${watchLater.size}") { onOpenLibrary(1) }
        BiliProfileRow(R.drawable.bili_ic_download, "我的下载", "") { onOpenDownloads() }
        BiliProfileRow(R.drawable.bili_ic_short, "短视频", "") { onOpenShorts() }
        BiliProfileRow(R.drawable.bili_ic_setting, "设置", "") { onOpenSettings() }
    }
}

@Composable
private fun BiliProfileRow(
    iconRes: Int,
    label: String,
    trailing: String,
    onClick: () -> Unit
) {
    val c = biliColors()
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painterResource(iconRes),
            null,
            tint = c.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.textPrimary, fontSize = BiliText.listItem, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(trailing, color = c.textHint, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            painterResource(R.drawable.bili_ic_arrow_right),
            null,
            tint = c.textHint,
            modifier = Modifier.size(16.dp)
        )
    }
    Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(0.5.dp).background(c.divider))
}

// ---------------------------------------------------------------------------
// 底部悬浮导航
// ---------------------------------------------------------------------------

@Composable
private fun BiliBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = biliColors()
    val items = listOf(
        R.drawable.bili_ic_home to "首页",
        R.drawable.bili_ic_comment to "关注",
        R.drawable.bili_ic_person to "我的"
    )
    BoxWithConstraints(
        modifier
            .padding(horizontal = BiliDimen.navMarginH)
            .padding(bottom = BiliDimen.navMarginBottom)
            .fillMaxWidth()
            .height(BiliDimen.navHeight)
            // 2026-11-20：底色改不透明纯色后阴影同步收小，避免灰边（原 10.dp 会晕出一圈灰）。
            .shadow(4.dp, RoundedCornerShape(BiliDimen.navRadius))
            .clip(RoundedCornerShape(BiliDimen.navRadius))
            .background(c.navTint)
            .border(1.dp, c.navStroke, RoundedCornerShape(BiliDimen.navRadius))
            .padding(horizontal = BiliDimen.navPadH)
    ) {
        // 滑动指示（2026-11-24 再调整）：原 58dp 正圆改横向拉长的胶囊，
        // 仍作为一个整体在三个 item 间平移（240ms），同时罩住图标与文字。
        // 选中色继续由图标/文字的粉色承担，胶囊本体做半透明玻璃底。
        val itemWidth = maxWidth / items.size
        // 2026-11-24：圆形横向拉长成胶囊（78×46dp），并加一层"液态玻璃"光泽。
        // 项目没有 Haze 依赖，Modifier.blur 又只能模糊自身内容，所以走
        // 半透明底 + 亮边 + 上下高光渐变 + 柔和投影的轻量玻璃方案（与全站玻璃卡同口径）。
        val pillWidth = 78.dp
        val pillHeight = 46.dp
        val pillShape = RoundedCornerShape(percent = 50)
        val pillX by animateDpAsState(
            targetValue = itemWidth * selected + (itemWidth - pillWidth) / 2,
            animationSpec = tween(240),
            label = "navPill"
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = pillX)
                .size(width = pillWidth, height = pillHeight)
                .shadow(8.dp, pillShape, spotColor = c.pink.copy(alpha = 0.16f))
                .clip(pillShape)
                .background(c.navPill.copy(alpha = if (c.isDark) 0.52f else 0.64f))
                .border(
                    1.dp,
                    Color.White.copy(alpha = if (c.isDark) 0.16f else 0.72f),
                    pillShape
                )
        ) {
            // 玻璃光泽：顶部高光 + 底部反光，跟着胶囊一起滑动。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (c.isDark) 0.10f else 0.42f),
                                Color.Transparent,
                                Color.White.copy(alpha = if (c.isDark) 0.04f else 0.14f)
                            )
                        )
                    )
            )
        }
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { idx, (res, label) ->
            val on = selected == idx
            // 选中反馈：圆底由外层滑动指示承担，这里只做图标弹簧缩放 +
            // 图标/文字颜色过渡，避免两套高亮叠加。
            val iconScale by animateFloatAsState(
                targetValue = if (on) 1f else 0.9f,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                ),
                label = "navScale"
            )
            // 参考图口径：未选中图标近黑、文字灰；选中图标与文字同粉。
            val iconTint by animateThemeColorAsState(
                targetValue = if (on) c.pink else c.textPrimary,
                animationSpec = tween(220),
                label = "navIcon"
            )
            val labelColor by animateThemeColorAsState(
                targetValue = if (on) c.pink else c.navUnselected,
                animationSpec = tween(220),
                label = "navLabel"
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    // 滑动圆底就是选中反馈：关掉默认水波纹，否则按下时
                    // 整格矩形 ripple 会在半透明条上留下一块方形亮斑。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(idx) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(res),
                        label,
                        tint = iconTint,
                        modifier = Modifier.size(BiliDimen.navIcon)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    color = labelColor,
                    fontSize = BiliText.navLabel,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用状态
// ---------------------------------------------------------------------------

@Composable
private fun BiliLoading(c: BiliColors) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = c.pink, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun BiliEmptyState(message: String, onRetry: (() -> Unit)?) {
    val c = biliColors()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message, color = c.textHint, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Text("点击重试", color = c.pink, fontSize = 14.sp, modifier = Modifier.clickable { onRetry() })
        }
    }
}

// ---------------------------------------------------------------------------
// 页面标识与常量
// ---------------------------------------------------------------------------

/** 浏览层四个页面：AnimatedContent 用它做 targetState（值不变就不触发切换动画）。 */
private sealed interface BiliPage {
    data object Search : BiliPage
    data object Follow : BiliPage
    data object Profile : BiliPage
    data class Home(val tab: Int) : BiliPage
}

/** 「短视频」在分类胶囊里的下标（第 5 个）：它打开整屏竖滑流，不参与 homeTab 取值。 */
private const val BILI_CHIP_SHORTS = 4
