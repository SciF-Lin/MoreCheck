package com.morecheck.ui.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
// `androidx.compose.ui.draw.shadow` 的 import 已随上面两处 `.shadow(…)` 一起删除：
// swiftshader 模拟器会把 elevation 阴影渲染成一坨盖住白底的灰（详见 RoundIconButton 处的注释）。
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.net.AppHttp
import com.morecheck.di.appContainer
import com.morecheck.ui.common.pressScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「实验室 → 热搜」（v1.1.77 需求 2，占位页升级为真实页面）。
 *
 * 参考 `E:\Documents\Downloads\index.html`（网页版）与 `热搜.txt`（设计说明）实现，
 * UI 走 Compose 原生（不是 WebView 套网页）：本页的核心交互（筛选 chips、横向对比、
 * 下拉展开完整榜）都是列表与滚动，Compose 原生实现比网页更贴合应用的整体观感与深浅色。
 *
 * ## 数据链路（与网页版逐项对应）
 *
 * - **实时数据**：GET [DEFAULT_API_URL]（腾讯云 CloudBase 云函数，云端每 4 小时
 *   整点抓取一次各大平台热搜并聚合）。返回 `{code:0, data:{aggregate:{...}, weibo:{...}, ...}}`；
 * - **回退链**：接口失败 / 无网络 → **内置快照**（[SNAPSHOT]，网页版抓取时刻的那份）→
 *   理论上不会走到空数据（快照恒存在）；
 * - **手动刷新**：标题栏刷新按钮 = 先调 [DEFAULT_CRAWL_URL]（带 [DEFAULT_CRAWL_TOKEN]，
 *   让云端**真的**去各平台抓一次），成功后再拉 [DEFAULT_API_URL]。
 *   两次真实抓取之间最短间隔 60 秒（与网页版同口径，避免耗尽云端额度）；
 * - **联网声明**：本页是继「天气」「智能体」之后第三个联网功能，接口为
 *   `*.app.tcloudbase.com`（https）。隐私政策第三节的联网清单需同步（已更新）。
 *
 * ## 页面结构（对照设计稿五块）
 *
 * 1. 标题栏：返回 + 「热搜聚合榜 / 聚合 N 大平台 · 实时热度」+ 搜索 + 刷新（带旋转动画）；
 * 2. 平台筛选 chips：横向滚动胶囊，选中用强调色实心；
 * 3. 榜单卡片：全部 = 聚合总榜 Top5（爆/热/新 图例 + 「查看完整榜单」通栏按钮），
 *    单平台 = 该平台 Top5；点击行跳转话题链接（系统浏览器）；
 * 4. 平台横向对比：横向滑动卡片，每平台 Top5，品牌色圆点 + 平台名；
 * 5. 底部一行数据说明（来源 + 抓取时刻 + 下次抓取时间）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotSearchScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { context.appContainer.settingsRepository }

    // ── 页面状态 ─────────────────────────────────────────────────────────────
    // data 非空即有可渲染内容（快照兜底保证首屏永远有东西）。
    var data by remember { mutableStateOf<HotSearchData?>(null) }
    // live / snapshot / demo / error——决定提示条与 footer 文案。
    var mode by remember { mutableStateOf("loading") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPlatform by rememberSaveable { mutableStateOf("all") }
    var refreshing by remember { mutableStateOf(false) }
    var lastCrawlAt by rememberSaveable { mutableStateOf(0L) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }

    // ── 底部导航（2026-10-22 需求：增加导航栏，实现设置功能）──────────────────
    //
    // 四个入口与参考设计稿一致：榜单 / 发现 / 关注 / 我的。
    // 「我的」承载设置（接口地址 / 数据模式 / 关于），
    // 「发现」「关注」按设计稿是占位页（原稿同样是 design placeholder）。
    var hotTab by rememberSaveable { mutableStateOf(HotTab.BOARD) }

    // 完整榜单的二级界面（同一页内覆盖，带返回；不再用弹窗）。
    var fullBoardOpen by rememberSaveable { mutableStateOf(false) }

    // 「新闻概况」二级界面（2026-10-30 需求 6）。非空时覆盖在最上层。
    var briefItem by remember { mutableStateOf<HotSearchItem?>(null) }
    // 退出动画（200ms）期间 briefItem 已经是 null，直接用它会闪一下空白；
    // 所以另存一份"当前要画的那条"，只在打开时更新。
    var briefShown by remember { mutableStateOf<HotSearchItem?>(null) }
    val openBrief: (HotSearchItem) -> Unit = { item ->
        briefItem = item
        briefShown = item
    }

    // 自定义接口地址（DataStore）。null = 用内置默认值。
    val customApiUrl by settings.hotSearchApiUrl.collectAsStateWithLifecycle(initialValue = null)
    val apiUrl = customApiUrl ?: DEFAULT_API_URL

    // ── 平台原文读取检测（2026-11-09）────────────────────────────────────────
    //
    // 「我的」页的按钮逐个平台取当前榜首，走与「新闻概况」完全相同的
    // `NewsBriefRepository.brief` 链路——检测出来的就是用户真会看到的能力，
    // 而不是另写一套探测逻辑（那套逻辑迟早会和正式链路跑偏）。
    var probes by remember { mutableStateOf<List<PlatformProbe>>(emptyList()) }
    var probing by remember { mutableStateOf(false) }

    fun probePlatforms() {
        if (probing) return
        val snapshot = data ?: run {
            onMessage("榜单还没加载完，稍后再试")
            return
        }
        val keys = snapshot.platformKeys()
        if (keys.isEmpty()) {
            onMessage("当前没有可检测的平台")
            return
        }
        probing = true
        probes = emptyList()
        scope.launch {
            for (key in keys) {
                val board = snapshot.platform(key)
                // 优先测「能直接打开网页」的那一条：没有 url 的条目必然抓不到原文，
                // 拿它当样本会把整个平台误判成不可读。
                val item = board?.items?.firstOrNull { it.url.startsWith("https://") }
                    ?: board?.items?.firstOrNull()
                val one = if (item == null) {
                    PlatformProbe(key, platformName(key), ProbeStatus.NoData, "榜单没有可检测的条目", 0)
                } else {
                    val started = System.currentTimeMillis()
                    val brief = try {
                        NewsBriefRepository.brief(item)
                    } catch (e: Throwable) {
                        NewsBrief("", BriefSource.NONE, e.message ?: "检测失败")
                    }
                    PlatformProbe(
                        platformKey = key,
                        platformLabel = platformName(key),
                        status = when (brief.source) {
                            BriefSource.OFFICIAL_AI, BriefSource.OFFICIAL -> ProbeStatus.Fetched
                            BriefSource.AI -> ProbeStatus.ModelOnly
                            BriefSource.NONE -> ProbeStatus.Failed
                        },
                        detail = when (brief.source) {
                            BriefSource.OFFICIAL_AI -> "读到原文 · 官方 AI 总结 · ${brief.text.length} 字"
                            BriefSource.OFFICIAL -> "读到原文 · 官方摘要 · ${brief.text.length} 字"
                            BriefSource.AI -> "原文没读到 · 仅按标题生成 · ${brief.text.length} 字"
                            BriefSource.NONE -> (brief.note ?: "无法读取原文").take(60)
                        },
                        elapsedMs = System.currentTimeMillis() - started
                    )
                }
                probes = probes + one
            }
            probing = false
            val ok = probes.count { it.status == ProbeStatus.Fetched }
            onMessage("检测完成：$ok/${probes.size} 个平台能读原文并总结")
        }
    }

    /** 拉取榜单数据（IO 线程走 HttpURLConnection，本项目零新增依赖）。 */
    fun loadLive(onDone: () -> Unit = {}) {
        scope.launch {
            // 腾讯新闻与聚合接口**并行**拉（2026-10-27）：
            // 前者是官方公开接口、10 分钟一更，后者是云端 4 小时一抓，两者互不依赖。
            // 串行拉会让首屏多等一个 RTT，而腾讯失败又只该少它自己那一块。
            val tencentJob = async { fetchTencentHot() }
            val result = fetchHotSearchData(apiUrl)
            val tencentBoard = tencentJob.await()

            /**
             * 加一个「腾讯新闻」平台卡；拿不到就原样返回。
             *
             * ⚠️ **刻意不并进「全部」聚合总榜**（`intoAggregate = false`）：
             * 实测并入后，聚合榜前五从「微博 / 百度 / 头条」变成了四条知乎 ——
             * 因为各平台热度**量纲根本不同**（微博是 3000 万级、腾讯 hotScore 是 200 万级、
             * 知乎又是另一个刻度），按数值混排等于让量纲大的平台通吃。
             * 云端那边是按"各平台自己的排名"聚的，不该在端上重排。
             * 想只看腾讯的排序，点「腾讯新闻」那个 chip。
             */
            fun merge(base: HotSearchData): HotSearchData =
                tencentBoard?.let { base.plusBoard("tencent", it) } ?: base

            result.fold(
                onSuccess = { d ->
                    data = merge(d)
                    mode = "live"
                    errorMessage = null
                },
                onFailure = { e ->
                    // 回退到内置快照（恒存在），并把原因展示在提示条上。
                    // 快照同样接受腾讯补位：聚合接口挂了，腾讯这条链路往往还是通的。
                    data = merge(buildSnapshotData())
                    mode = "snapshot"
                    errorMessage = e.message
                }
            )
            onDone()
        }
    }

    // 首次进入即加载一次（与网页版"启动立即加载"同口径）。
    // key = apiUrl：用户在设置里改了地址后回到榜单会自动按新地址重拉。
    LaunchedEffect(apiUrl) { loadLive() }

    /** 手动刷新：先让云端抓一次（带冷却），再拉数据。 */
    fun refreshNow() {
        if (refreshing) return
        val wait = CRAWL_COOLDOWN_MS - (System.currentTimeMillis() - lastCrawlAt)
        if (wait > 0) {
            onMessage("刚抓取过，请 ${wait / 1000 + 1} 秒后再试")
            return
        }
        refreshing = true
        lastCrawlAt = System.currentTimeMillis()
        onMessage("正在抓取各大平台最新热搜…")
        scope.launch {
            // 抓取失败不阻断拉数据（云端可能只是 crawler 暂不可用，榜单里可能已有新数据）。
            val crawlOk = runCatching {
                withContext(Dispatchers.IO) {
                    httpGetText("${DEFAULT_CRAWL_URL}?token=${urlEncode(DEFAULT_CRAWL_TOKEN)}")
                }
            }.isSuccess
            if (!crawlOk) onMessage("抓取接口不可达，仅拉取现有数据")
            loadLive { refreshing = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                HotSearchTopBar(
                    platformCount = data?.platformCount() ?: 0,
                    refreshing = refreshing,
                    searchOpen = searchOpen,
                    keyword = keyword,
                    // 2026-10-29 需求：「查看演示数据」时**必须在标题上标明是演示数据**，
                    // 否则用户会把这些编好的条目当成真实热搜（页脚那行小字不够醒目）。
                    demo = mode == "demo",
                    onBack = onBack,
                    onToggleSearch = { searchOpen = !searchOpen; if (!searchOpen) keyword = "" },
                    onKeywordChange = { keyword = it },
                    onRefresh = { refreshNow() }
                )
            },
            bottomBar = {
                // 搜索态不显示底部导航：那时用户在做"找话题"这件事，
                // 底栏只会挤掉结果列表；退出搜索即恢复。
                if (!searchOpen) {
                    HotPillNavBar(
                        selected = hotTab,
                        onSelect = {
                            hotTab = it
                            fullBoardOpen = false
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when {
                    hotTab == HotTab.BOARD && searchOpen -> SearchBody(
                        data = data,
                        keyword = keyword,
                        selectedPlatform = selectedPlatform,
                        onOpenBrief = openBrief
                    )

                    hotTab == HotTab.BOARD -> {
                        // 取一次局部值：`data` 是委托属性，Kotlin 不会对它做智能转换，
                        // 直接传给要求非空的 HotSearchBody 会编译不过。
                        val snapshot = data
                        if (snapshot == null) {
                            // 首屏加载动画（2026-10-22 重做：转圈画在白色栏上）。
                            HotSearchLoading()
                        } else {
                            // ⚠️ 必须是 Column 而不是并列在 Box 里：Box 的两个子项会**叠在
                            // 同一位置**，chips 会压住列表首行（2026-10-22 模拟器实测：
                            // 榜单卡顶部被 chips 盖掉半行）。列表要占满剩余高度，
                            // 所以给一个 weight(1f) 的 Box 当容器。
                            Column(Modifier.fillMaxSize()) {
                                // 平台筛选 chips（榜单视图专属；搜索视图隐藏，与网页版同口径）。
                                PlatformChips(
                                    data = snapshot,
                                    selected = selectedPlatform,
                                    onSelect = { selectedPlatform = it }
                                )
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    HotSearchBody(
                                        data = snapshot,
                                        mode = mode,
                                        errorMessage = errorMessage,
                                        selectedPlatform = selectedPlatform,
                                        onSelectPlatform = { selectedPlatform = it },
                                        onOpenBrief = openBrief,
                                        onCopyApi = { copyText(context, apiUrl, onMessage) },
                                        onOpenFullBoard = { key ->
                                            selectedPlatform = key
                                            fullBoardOpen = true
                                        }
                                    )
                                }
                            }
                        }
                    }

                    hotTab == HotTab.ME -> HotSearchSettings(
                        isCustomApi = customApiUrl != null,
                        mode = mode,
                        snapshotStamp = data?.aggregate?.updateTime?.let { dataStamp(it) }.orEmpty(),
                        onSaveApiUrl = { url ->
                            scope.launch {
                                settings.setHotSearchApiUrl(url)
                                onMessage("接口地址已保存，正在刷新")
                                loadLive()
                            }
                        },
                        onResetApiUrl = {
                            scope.launch {
                                settings.setHotSearchApiUrl(null)
                                onMessage("已恢复默认接口地址")
                                loadLive()
                            }
                        },
                        onShowDemo = {
                            data = buildSnapshotData()
                            mode = "demo"
                            errorMessage = null
                            hotTab = HotTab.BOARD
                            onMessage("已切换到演示数据")
                        },
                        probes = probes,
                        probing = probing,
                        onProbe = { probePlatforms() }
                    )

                    else -> HotSearchPlaceholder(tab = hotTab)
                }
            }
        }

        // ── 完整榜单：二级界面（2026-10-22 需求）────────────────────────────
        //
        // 从弹窗改成整页覆盖：条数多时弹窗里的列表又矮又窄，且"查看完整榜单"
        // 语义上就是进下一级。返回键 / 左上角返回都能退出这一层。
        AnimatedVisibility(
            visible = fullBoardOpen,
            enter = slideInHorizontally(tween(220), initialOffsetX = { it }),
            exit = slideOutHorizontally(tween(200), targetOffsetX = { it })
        ) {
            FullBoardPage(
                data = data,
                platformKey = selectedPlatform,
                demo = mode == "demo",
                onBack = {
                    fullBoardOpen = false
                    onMessage("已退出完整榜单")
                },
                onOpenBrief = openBrief
            )
        }

        // ── 新闻概况：最上层的二级界面（2026-10-30 需求 6）──────────────────
        //
        // 叠在完整榜单之上：从完整榜单点一条进来，返回时回到完整榜单而不是榜单首页。
        AnimatedVisibility(
            visible = briefItem != null,
            enter = slideInHorizontally(tween(220), initialOffsetX = { it }),
            exit = slideOutHorizontally(tween(200), targetOffsetX = { it })
        ) {
            val current = briefShown
            if (current != null) {
                NewsBriefPage(
                    item = current,
                    onBack = { briefItem = null },
                    onOpenExternal = { openTopic(context, current.url, onMessage) },
                    // 平台胶囊也用系统浏览器打开；走同一个 openTopic，
                    // 这样「非 https / 没有浏览器」的中文提示与原文按钮完全一致。
                    onOpenPlatformUrl = { url -> openTopic(context, url, onMessage) }
                )
            }
        }
    }

    // 二级界面开着时，系统返回键先退出最上面那一层（而不是退出整个热搜页）。
    androidx.activity.compose.BackHandler(enabled = briefItem != null || fullBoardOpen) {
        if (briefItem != null) briefItem = null else fullBoardOpen = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 圆形白按钮（标题栏动作用，参考设计稿）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 圆形图标按钮（形态以用户给的截图为准：**白色圆片 + 深灰图标**）。
 *
 * ## 形态
 *
 * 白底圆片、图标是近黑的石墨灰、外面一圈极淡的描边，整颗带一点柔和投影
 * （截图里能看出浮在标题栏上的层次）。
 *
 * ## 为什么不是"橙色实心 + 白图标"
 *
 * 中间试过那一版（把图标统一成白色），与用户随后给的截图不符：
 * 标题栏这两颗按钮是**白底深图标**，橙色的只有底部导航的选中丸。
 * 现在按截图做，`RoundIconTint` 只负责取"深色主题下仍看得清"的那一档灰。
 *
 * 用 `Box + clip(CircleShape)` 手绘，而不是 `IconButton` + `background`：
 * 后者默认 48dp 触摸区、图标 24dp，圆片会显得过大（截图里圆片直径 ≈ 36dp）。
 */
@Composable
private fun RoundIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(36.dp)
            // 2026-10-30 用户要求：去掉描边；位置**向左**微调。
            // 上一版是 +4dp（太贴右边缘，拇指要够到手机构边），改 -4dp 后两颗圆片
            // 与屏幕右沿留出余量，点击落点更稳；两片之间的 8dp 间距不变。
            .offset(x = (-4).dp)
            .clip(CircleShape)
            .background(cardSurface(scheme))
            .pressScale(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 标题栏 / 底栏**未选中**图标的颜色（深色石墨灰，接近截图里的 `#3C4048`）。
 *
 * 浅色主题下直接用这个常量；深色主题下卡面是深色，这个灰会糊掉，
 * 因此统一经 [hotChromeTint] 取色，不直接引用本常量。
 */
private val RoundIconTint = Color(0xFF4A4F57)

/**
 * 底部导航**未选中**项的颜色（2026-10-23 需求 3，取值照 `index.html` 的 `.tab`：
 * `color: #9AA0AA`）。比标题栏那个 `#4A4F57` 更浅一档——底栏是"选中项抢眼、
 * 其余退到背景里"的信息层级。
 */
private val NavIdleTint = Color(0xFF9AA0AA)

/**
 * 上面那两处图标在**当前主题**下该用的颜色。
 *
 * 判据用 `surface` 的亮度（与本文件 [cardSurface] 同一判据，不会出现
 * "卡面已经换成深色、图标还是深灰"的错配）。
 */
private fun hotChromeTint(scheme: androidx.compose.material3.ColorScheme): Color =
    if (scheme.surface.luminance() > 0.5f) RoundIconTint else scheme.onSurface

/** 底栏未选中项在当前主题下的颜色（深色下同样回落到 `onSurface`，见 [hotChromeTint]）。 */
private fun navIdleTint(scheme: androidx.compose.material3.ColorScheme): Color =
    if (scheme.surface.luminance() > 0.5f) NavIdleTint else scheme.onSurface

// ─────────────────────────────────────────────────────────────────────────────
// 底部导航（药丸样式，参考设计稿）
// ─────────────────────────────────────────────────────────────────────────────

/** 热搜页的四个底部入口（2026-10-22；与设计稿的 榜单 / 发现 / 关注 / 我的 一致）。 */
private enum class HotTab(val label: String) {
    BOARD("榜单"),
    DISCOVER("发现"),
    FOLLOW("关注"),
    ME("我的")
}

/**
 * 底部悬浮**药丸导航栏**（2026-10-22 需求，样式参考设计稿截图）。
 *
 * ## 形态
 *
 * 一条白色圆角胶囊（`surface` + 细边框 + 轻投影），四个入口等分；
 * 选中项是一颗**实心暖橙胶囊**，图标与文字一起变白——这是设计稿里的核心视觉，
 * 与 Material 默认的 `NavigationBar`（指示条 + 文字，无药丸底）完全不同，
 * 因此这里手绘而不是硬改 `NavigationBar` 的配色。
 *
 * ## 为什么不用 `NavigationBar`
 *
 * 除了上面那条视觉原因，`NavigationBar` 自带 80dp 高 + 系统导航栏 inset 的固定
 * 布局，没法做成"悬浮、两侧留白、可自由圆角"的胶囊；强行改要覆盖它的测量，
 * 反而比这几十行更脆。
 */
@Composable
private fun HotPillNavBar(
    selected: HotTab,
    onSelect: (HotTab) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // 未选中项的颜色：浅色下 #9AA0AA、深色下回落 onSurface（见 [navIdleTint]）。
    val idleTint = navIdleTint(scheme)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(999.dp))
            // 形态以用户给的截图为唯一口径：**白色胶囊 + 橙色选中丸**。
            // 没选中的图标 / 文字是石墨灰，选中那颗里的是白色——
            // 白底由 `cardSurface` 给出（深色主题下自动变成深色卡面，不会露出白板）。
            .background(cardSurface(scheme))
            // `.tabbar-inner` 的 1px 描边保留。
            // ⚠️ 原先照抄设计稿的 `box-shadow: 0 4px 18px rgba(15,23,42,.10)` 用的
            // `.shadow(6.dp,…)` 已删：swiftshader 模拟器把这道阴影渲染成罩在
            // 整条胶囊上的一层灰（内部透出下层内容的"半透明"假象），
            // 用户看到的就是这坨灰并反馈"浅色没改"。白底 + 描边已足够分层。
            .border(1.dp, chromeHairline(scheme), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HotTab.entries.forEach { tab ->
            val active = tab == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) HotAccent else Color.Transparent)
                    .pressScale(onClick = { onSelect(tab) })
                    // `.tab`：padding 7px 0 5px（上多下少，图标与文字的整体重心略偏上）。
                    .padding(top = 7.dp, bottom = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (tab) {
                        HotTab.BOARD -> Icons.AutoMirrored.Filled.List
                        HotTab.DISCOVER -> Icons.Filled.Explore
                        HotTab.FOLLOW -> Icons.Filled.FavoriteBorder
                        HotTab.ME -> Icons.Filled.Person
                    },
                    contentDescription = tab.label,
                    // 选中 = 白（在橙丸上）；未选中 = 石墨灰。
                    tint = if (active) Color.White else idleTint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) Color.White else idleTint
                )
            }
        }
    }
}

/**
 * 「发现 / 关注」占位页（设计稿里这两项就是示意页）。
 *
 * 不做成"空白页"：空屏会让人以为功能坏了。这里给一句话说明它只在本设计稿里占位，
 * 并引导用户回「榜单」——这与参考文件的 placeholderView 文案同口径。
 */
@Composable
private fun HotSearchPlaceholder(tab: HotTab) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (tab == HotTab.DISCOVER) "◎" else "♡",
            fontSize = 44.sp,
            color = HotAccent.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = tab.label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "该页面为设计示意，核心功能请使用「榜单」页。",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = scheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 首屏加载动画
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 首屏加载动画（v1.1.80 引入；2026-10-22 重做）。
 *
 * ## 2026-10-30：转圈进「信息栏」的背景层
 *
 * 上一版是一条独立的白色胶囊栏（"圈 + 正在加载今日热搜…"），下面再跟骨架卡——
 * 同一件事说了两遍，还多占一条竖排空间。用户要求「转圈动画移动到信息栏底层背景上」：
 *
 * - 现在只有**一张卡**（与真实榜单卡同形），卡头就是信息栏：左边标题 + 一行加载文案，
 * - 转圈画在卡头 `Box` 的**底层**（`Row + matchParentSize`，右对齐、alpha 0.55），
 *   文字压在上面——读起来是"这条信息栏正在刷新"，而不是"页面中间有个转圈"。
 * - 骨架骨片保留（那是"数据在来"的第二重提示，且保证数据到达时不跳版）。
 */
@Composable
private fun HotSearchLoading() {
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "hot-search-loading")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton-pulse"
    )
    // 骨架骨片颜色：低透明度 + 呼吸，不会像纯灰块那样显得"卡死"。
    val bone = scheme.onSurfaceVariant.copy(alpha = 0.08f + 0.10f * pulse)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // ── 骨架卡（与真实榜单卡同形，数据到达时不跳版）────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardSurface(scheme))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 信息栏（卡头）：转圈画在它的**底层背景**上（2026-10-30 用户要求）──
            //
            // 原来转圈独占一条白色胶囊栏，压在骨架上，两层东西都在说"在加载"。
            // 现在收进卡头：文字在前、转圈在右侧背景层（压到半透明，不与文案抢注意力），
            // 视觉上就是"这条信息栏正在刷新"，也更省一条竖向空间。
            Box(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.matchParentSize(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = HotAccent.copy(alpha = 0.55f)
                    )
                }
                Column {
                    Text(
                        text = "今日聚合总榜",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface
                    )
                    Text(
                        text = "正在加载今日热搜…",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // 5 行榜单骨片：排名 + 标题 + 热度三段，宽度比例模拟真实行。
            repeat(5) { index ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bone)
                    )
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier
                            .weight(if (index % 2 == 0) 1f else 0.78f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bone)
                    )
                    if (index % 2 != 0) Spacer(Modifier.weight(0.22f))
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .width(38.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bone)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 常量（与网页版逐字对齐）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 已部署的 CloudBase「api」云函数访问地址（实时榜单数据）。
 *
 * ⚠️ 这是**第三方公网接口**（用户自己的云函数）：本页是继「天气」「智能体」之后
 * 第三个联网功能，隐私政策的联网清单与《热搜》条目文案都按此口径描述。
 */
internal const val DEFAULT_API_URL =
    "https://backtest-d5g643vc1174fc8a9-1509601819.ap-shanghai.app.tcloudbase.com/api"

/** 手动刷新时先调它：让云端 crawler 云函数**真的**去各平台抓一次。 */
internal const val DEFAULT_CRAWL_URL =
    "https://backtest-d5g643vc1174fc8a9-1509601819.ap-shanghai.app.tcloudbase.com/crawl"

/** crawler 云函数的访问密钥（网页版同样内置；密钥错时云端返回 403）。 */
internal const val DEFAULT_CRAWL_TOKEN = "200547a9378c6cb93982db601ea0b974"

/** 两次真实抓取之间的最小间隔（毫秒）。与网页版同值：避免频繁点击耗尽云端额度。 */
internal const val CRAWL_COOLDOWN_MS = 60 * 1000L

/**
 * 腾讯新闻热榜（2026-10-27 需求：「在热搜中补充腾讯新闻热点」）。
 *
 * ## 为什么这一路不走聚合接口
 *
 * 其余平台的榜由用户自己的 CloudBase 云函数每 4 小时抓一次再聚合；腾讯新闻这份
 * 是**官方公开接口**（`r.inews.qq.com`，每 10 分钟更新），可以直接在端上拉：
 * - 省掉一次云端抓取额度；
 * - 时效性比"每 4 小时"高一个量级 —— 热点这事差 4 小时就没意义了。
 *
 * 所以它作为**独立来源**并行拉取，失败时只少这一块，不影响原有榜单与快照兜底。
 *
 * 返回结构：`{ret:0, idlist:[{newslist:[{title,url,readCount,timestamp,...}]}]}`，
 * 第 0 条是一条说明文案（`articletype == "560"`），解析时跳过。
 */
internal const val TENCENT_HOT_URL =
    "https://r.inews.qq.com/gw/event/hot_ranking_list?page_size=30"

/** 平台 key → 中文名（设计稿的八个平台 + 快照里实际出现的都覆盖到）。 */
internal fun platformName(key: String): String = when (key) {
    "weibo" -> "微博"
    "zhihu" -> "知乎"
    "baidu" -> "百度"
    "bilibili" -> "B站"
    "douyin" -> "抖音"
    "toutiao" -> "头条"
    "xiaohongshu" -> "小红书"
    "weixin" -> "微信"
    "tencent" -> "腾讯新闻"
    else -> key
}

// ─────────────────────────────────────────────────────────────────────────────
// 强调色与状态标识色
// ─────────────────────────────────────────────────────────────────────────────

/** 强调色（暖红橙 #F5522D）与三个状态标识色——设计稿钦定，深浅色模式共用。 */
internal val HotAccent = Color(0xFFF5522D)
private val HotAccentSoft = Color(0xFFFFF1ED)

/**
 * 「非实时数据」这句话的颜色（2026-10-30）。
 *
 * 取橙 600（#EA580C）：比主强调色 [HotAccent] 略暗一档，压在白色卡面上
 * 对比度约 3.9:1（够读），又比标题栏里那句深棕橙 `#B45309` 更"橙"，
 * 一眼能看出这是**提示**而不是普通说明文字。
 */
private val DemoStampOrange = Color(0xFFEA580C)
private val TagBoom = Color(0xFFEF4444)
private val TagHot = Color(0xFFF97316)
private val TagNew = Color(0xFF16A34A)

/**
 * 状态标识的**品牌强调色**（2026-11-03 重做样式后只用来画圆点 / 描边 / 底色）。
 *
 * 未知标签给中性灰，保证解析异常时不会抛异常。
 */
private fun tagAccent(tag: String): Color = when (tag) {
    "爆" -> TagBoom
    "热" -> TagHot
    "新" -> TagNew
    else -> Color(0xFF94A3B8)
}

/**
 * 状态标识的**文字色**（深浅色各一套）。
 *
 * ## 为什么不能直接用品牌色当文字
 *
 * 新版标识是「半透明底 + 同色系文字 + 细描边 + 小圆点」（见 [HotTag]）。底色变浅以后，
 * 品牌色文字在浅色底 / 深色底上的对比度都不够：
 * - 浅色主题把品牌色**压暗 25%**（#EF4444 → #B33333），压在浅灰/白卡面上够读；
 * - 深色主题把品牌色**提亮 45%**，压在 #202020 的卡面上够读。
 * 两个分支都仍是同一色系，所以「爆=红 / 热=橙 / 新=绿」的语义不变；
 * 并且**文字始终保留**，不靠颜色单独表意（色弱 / 灰度截图也能读）。
 */
private fun tagTextColor(tag: String, dark: Boolean): Color {
    val base = tagAccent(tag)
    return if (dark) {
        Color(
            red = base.red + (1f - base.red) * 0.45f,
            green = base.green + (1f - base.green) * 0.45f,
            blue = base.blue + (1f - base.blue) * 0.45f,
            alpha = 1f
        )
    } else {
        Color(
            red = base.red * 0.75f,
            green = base.green * 0.75f,
            blue = base.blue * 0.75f,
            alpha = 1f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HotSearchTopBar(
    platformCount: Int,
    refreshing: Boolean,
    searchOpen: Boolean,
    keyword: String,
    /** true = 当前展示的是「查看演示数据」那份内置样例（2026-10-29 需求）。 */
    demo: Boolean,
    onBack: () -> Unit,
    onToggleSearch: () -> Unit,
    onKeywordChange: (String) -> Unit,
    onRefresh: () -> Unit
) {
    val barTint = hotChromeTint(MaterialTheme.colorScheme)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        title = {
            if (searchOpen) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    placeholder = { Text("搜索当前榜单话题…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Column {
                    Text(
                        // 演示模式下标题后缀「（演示数据）」：标题是这一页最显眼的一行，
                        // 只在页脚写"内置快照"容易被当成真实榜单转发出去。
                        text = if (demo) "热搜聚合榜（演示数据）" else "热搜聚合榜",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (demo) {
                            "当前为演示数据 · 非实时榜单"
                        } else {
                            "聚合 $platformCount 大平台 · 实时热度"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (demo) {
                            Color(0xFFB45309)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        actions = {
            // 2026-10-22 需求：两个动作改成设计稿里的**圆形白按钮**
            // （白底圆片 + 深灰图标 + 细描边），与页面白卡片同一族，
            // 比默认的无底色图标更像"可点的按钮"。
            RoundIconButton(
                onClick = onToggleSearch,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索"
            ) {
                Icon(
                    imageVector = if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = null,
                    tint = barTint
                )
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                contentDescription = "刷新"
            ) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "实时刷新",
                        tint = barTint
                    )
                }
            }
            // 需求：按钮别顶着屏幕右缘——整组动作末尾补 8dp，与页面卡片同距。
            Spacer(Modifier.width(8.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 平台筛选 chips
// ─────────────────────────────────────────────────────────────────────────────

/** 平台品牌色（信息编码用：名称文字 + 圆点；对照设计稿的平台色）。 */
private fun platformColor(key: String): Color = when (key) {
    "weibo" -> Color(0xFFE6162D)
    "zhihu" -> Color(0xFF0084FF)
    "baidu" -> Color(0xFF2932E1)
    "bilibili" -> Color(0xFFFB7299)
    "douyin" -> Color(0xFF161823)
    "toutiao" -> Color(0xFFED2024)
    "xiaohongshu" -> Color(0xFFFF2442)
    "weixin" -> Color(0xFF07C160)
    // 腾讯新闻品牌蓝。
    "tencent" -> Color(0xFF1570EF)
    else -> Color(0xFF999999)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformChips(
    data: HotSearchData?,
    selected: String,
    onSelect: (String) -> Unit
) {
    // 2026-10-30：原来是单行横向滚动。接上腾讯新闻后变成 7 个 chip
    // （全部 + 6 平台），总宽约 470dp > 屏幕 393dp，最后一个「腾讯新闻」
    // 被右边缘**切掉半个**——滚动条又没有视觉提示，看着就像排版坏了。
    // 改成 FlowRow 自动换行：393dp 下 4+3 两行，窄屏 3+4，宽屏一行。
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Chip("全部", selected == "all", HotAccent) { onSelect("all") }
        data?.platformKeys()?.forEach { key ->
            Chip(platformName(key), selected == key, HotAccent) { onSelect(key) }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) accent else scheme.surfaceContainer,
        contentColor = if (active) Color.White else scheme.onSurfaceVariant,
        border = if (active) null else androidx.compose.foundation.BorderStroke(
            1.dp, scheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.pressScale(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 7.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 榜单主体
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HotSearchBody(
    data: HotSearchData,
    mode: String,
    errorMessage: String?,
    selectedPlatform: String,
    onSelectPlatform: (String) -> Unit,
    onOpenBrief: (HotSearchItem) -> Unit,
    onCopyApi: () -> Unit,
    /** 参数是被展开的那个榜（`"all"` 或平台 key）——平台卡上也会用到。 */
    onOpenFullBoard: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp, vertical = 4.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 数据来源提示条（离线回退时展示原因，与网页版的 banner 同口径）。
        // 演示数据单独一条（2026-10-29 需求）：它比快照更容易被当成真数据——
        // 快照至少是"曾经抓过的真实榜单"，演示数据则完全是编出来的样例。
        if (mode == "demo") {
            item(key = "demo-banner") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFFF7E6))
                        .border(1.dp, Color(0xFFFFE7BA), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "当前为演示数据",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFAD6800)
                    )
                    Text(
                        text = "下面是内置的示例条目，只用来演示页面布局，与真实热搜无关。" +
                            "点右上角刷新可拉回实时数据。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFF8A6D3B),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }

        if (mode == "snapshot") {
            item(key = "banner") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFFF7E6))
                        .border(1.dp, Color(0xFFFFE7BA), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "实时数据加载失败（${errorMessage ?: "网络不通"}），" +
                            "已回退到内置数据快照。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFAD6800)
                    )
                    Text(
                        text = "点右上角刷新可重试；接口地址在「我的」里配置，点此复制当前地址",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = Color(0xFF8A6D3B),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable(onClick = onCopyApi)
                    )
                }
            }
        }

        // ── 榜单卡片 ────────────────────────────────────────────────────────
        item(key = "board") {
            val isAll = selectedPlatform == "all"
            val board = if (isAll) data.aggregate else data.platform(selectedPlatform)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    // 2026-10-22 需求：榜单卡改**白底**（设计稿里卡面就是纯白，
                    // 浅色主题下的浅灰卡面与页面底色几乎同色，读起来没有"卡片"感）。
                    .background(cardSurface(scheme))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            isAll -> "今日聚合总榜"
                            else -> platformName(selectedPlatform)
                        } + if (mode == "demo") "（演示数据）" else "",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        // 显式给色：卡面在深色主题下不再是 `surface`，
                        // 只靠继承的 LocalContentColor 会在某些嵌套层里带出错误的一档。
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    board?.let {
                        // 2026-10-30：演示数据不是"某个时刻抓的"，写时间会误导，
                        // 改成一句「非实时数据」并用橙色标出来（与标题栏那句同色系）。
                        if (mode == "demo") {
                            Text(
                                text = "非实时数据",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = DemoStampOrange
                            )
                        } else {
                            Text(
                                text = dataStamp(it.updateTime) + " 抓取",
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 图例（聚合总榜才有跨平台聚合语义；单平台视图同样能出现爆/热/新，
                // 一并显示图例帮助理解，与网页版"榜单页恒显示图例"的实测形态一致）。
                if (board != null && board.items.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TagLegend("爆", "顶级热度")
                        TagLegend("热", "高热")
                        TagLegend("新", "新上榜")
                    }
                }

                val items = board?.items.orEmpty().take(5)
                if (items.isEmpty()) {
                    Text(
                        text = "暂无数据\n请点右上角刷新",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                } else {
                    items.forEachIndexed { index, item ->
                        BoardRow(
                            rank = index + 1,
                            title = item.title,
                            sourceNames = if (isAll) item.sourceNames else "",
                            heat = item.heat,
                            tag = item.tag,
                            onOpen = { onOpenBrief(item) }
                        )
                        if (index < items.size - 1) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .height(1.dp)
                                    .background(scheme.outlineVariant.copy(alpha = 0.5f))
                            )
                        }
                    }

                    // 2026-10-30 需求 5：**每个平台**都给这个入口（原来只有聚合总榜有），
                    // 所以条件里不再要求 isAll；判断只看"这个榜是不是超过一屏 5 条"。
                    if (board != null && board.items.size > 5) {
                        // 「查看完整榜单」→ **二级界面**（2026-10-22 需求）。
                        // 早先是弹窗，条数一多就变成"在弹窗里滚一个又矮又窄的列表"，
                        // 而且它语义上本来就是"进入下一级看全量"。
                        FullBoardButton(onClick = { onOpenFullBoard(selectedPlatform) })
                    }
                }
            }
        }

        // ── 平台横向对比 ────────────────────────────────────────────────────
        item(key = "compare-head") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "平台横向对比",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "左滑查看各平台 Top 5",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        item(key = "compare") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 2026-11-09 用户要求：横向对比各平台列表高度一致。
                    // IntrinsicSize.Max 以最高的那张卡为基准，其余卡片 fillMaxHeight 拉齐；
                    // 卡片内部的 5 行（含占位）与 footer 也各自固定高度，整排严格等高。
                    .height(IntrinsicSize.Max)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                data.platformKeys().forEach { key ->
                    PlatformCard(
                        data = data,
                        platformKey = key,
                        onOpenBrief = onOpenBrief,
                        onOpenFullBoard = onOpenFullBoard
                    )
                }
            }
        }

        // ── 底部数据说明 ────────────────────────────────────────────────────
        item(key = "footer") {
            Text(
                // 2026-10-22 需求 2：去掉「每 4 小时抓取（下次约 …）」与那对括号，
                // 只保留数据来源与本次的取数状态——抓取节奏属于实现细节，
                // 说明挪到「我的 → 关于」里写成一段原理描述。
                text = "数据来源：腾讯云 CloudBase 云函数\n" +
                    "本页每 10 分钟自动刷新 · " +
                    when (mode) {
                        "live" -> "实时数据"
                        "demo" -> "演示数据（非实时）"
                        else -> "内置快照"
                    },
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** 榜单里的一行（排名 + 标题 + 来源 + 热度 + 状态标识）。 */
@Composable
private fun BoardRow(
    rank: Int,
    title: String,
    sourceNames: String,
    heat: Long,
    tag: String,
    onOpen: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(onClick = onOpen)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank",
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            color = when (rank) {
                1 -> HotAccent
                2 -> Color(0xFFF97316)
                3 -> Color(0xFFF5A623)
                else -> Color(0xFFB0B6C0)
            },
            modifier = Modifier.width(26.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (sourceNames.isNotBlank()) {
                Text(
                    text = sourceNames,
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (tag.isNotBlank()) HotTag(tag)
        Spacer(Modifier.width(6.dp))
        if (heat > 0) {
            Text(
                text = formatHeat(heat),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface
            )
        }
    }
}

/**
 * 爆 / 热 / 新 状态标识（2026-11-03 重做样式）。
 *
 * ## 形态
 *
 * 「**品牌色小圆点 + 同色系文字 + 半透明底 + 一档细描边**」的胶囊，圆角取 999dp。
 *
 * ## 为什么要改（用户反馈"生硬"）
 *
 * 旧版是 `实心纯色块 + 白字`：压在白色卡面里是一块高饱和色斑，比标题还抢眼，
 * 深色主题下又因为底色太亮显得刺。新版把"重量"降下来——底是品牌色 10~18% 的淡色，
 * 描边一档，靠圆点保留"这是状态色"的识别度。
 *
 * ## 可读性
 *
 * 文字色按深浅主题分别取（见 [tagTextColor]），不是把品牌色直接拿来写字；
 * 文字本身保留，所以**不只靠颜色**区分（色弱 / 灰度截图也读得出「爆/热/新」）。
 *
 * 榜单行、完整榜单、聚合榜三处都走这个函数（另外两处是 [BoardRow] 的不同调用点），
 * 样式天然一致；图例 [TagLegend] 也复用它。
 */
@Composable
internal fun HotTag(tag: String) {
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val accent = tagAccent(tag)
    val text = tagTextColor(tag, dark)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = if (dark) 0.18f else 0.10f))
            .border(
                1.dp,
                accent.copy(alpha = if (dark) 0.42f else 0.30f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (dark) text.copy(alpha = 0.9f) else accent)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = tag,
            color = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 图例项：与 [HotTag] **完全同一套胶囊** + 一句含义说明。
 *
 * 复用 [HotTag] 而不是各画一份，是为了让"图例长什么样"永远等于"榜单里长什么样"
 * ——2026-11-03 改样式时只要改一处，两处不会走样。
 */
@Composable
private fun TagLegend(tag: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HotTag(tag)
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 「查看完整榜单 ›」通栏按钮。
 *
 * 2026-10-22 需求：底色改成**与总榜卡一样的白底**（原来是浅橙块）。
 * 浅橙块在白色卡面里像一块"警告条"，而它其实只是一条入口；
 * 白底 + 细描边 + 暖橙文字，既与卡面同族，又保留了"这是可点的一行"的提示。
 */
@Composable
private fun FullBoardButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = "查看完整榜单 ›",
        color = HotAccent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardSurface(scheme))
            .border(
                1.dp,
                scheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp)
            )
            .pressScale(onClick = onClick)
            .padding(vertical = 11.dp)
    )
}

/**
 * 药丸 / 圆按钮的**发丝描边**色（对照 `index.html` 的 `--line: #F0F1F4`）。
 *
 * 浅色主题直接取这个值；深色主题下 `#F0F1F4` 会变成一圈刺眼白边，
 * 因此回落到主题的 `outlineVariant`（本身就是"深色下的一档浅描边"）。
 */
private fun chromeHairline(scheme: androidx.compose.material3.ColorScheme): Color =
    if (scheme.surface.luminance() > 0.5f) Color(0xFFF0F1F4) else scheme.outlineVariant.copy(alpha = 0.6f)

/**
 * 卡片底色：浅色主题取**纯白**、深色主题取 `surface`。
 *
 * 设计稿的卡面是纯白；本应用的 `surfaceContainer` 在浅色下是浅灰，
 * 与页面底色几乎同色，"卡片感"就没了（2026-10-22 用户反馈这一带的底要白）。
 * 深色主题仍用 `surface`——不能给深色主题塞白块。
 */
internal fun cardSurface(scheme: androidx.compose.material3.ColorScheme): Color =
    if (scheme.surface.luminance() > 0.5f) {
        Color.White
    } else {
        // 深色主题：本应用的 `surface` 与 `background` **是同一个值**（都是 #111111），
        // 用 `surface` 当卡面等于没有卡面（2026-10-22 需求 9：深色下榜单"糊成一片"）。
        // 取 `surfaceContainerHigh`（#202020）——比底色亮一档，卡片边界与分隔线都看得见，
        // 也不会亮到刺眼。
        scheme.surfaceContainerHigh
    }

/**
 * 完整榜单的**二级界面**（2026-10-22 需求：点击「查看完整榜单」后在二级界面显示）。
 *
 * ## 形态（参考设计稿的 `#fullBoardPage`）
 *
 * 整页覆盖：顶部一条标题栏（返回箭头 + 标题 + 抓取时间），下面是一整屏可滚动的榜单。
 * 视觉与「榜单」一级页一致（同一个 [BoardRow]），因此从一级到二级只是"条目变多"，
 * 不会让人误以为进了另一个功能。
 *
 * ## 为什么不做成导航路由
 *
 * 它是热搜页**内部**的一层（内容完全来自同一份 `data`），开一个 `TabDestination`
 * 只会让路由表多一项、并让"返回"多一层跨页面语义；用页面内的覆盖层 + `BackHandler`
 * 就能得到同样的返回体验，且不会与热搜页自身的状态（选中的平台 / 搜索词）失联。
 *
 * @param data 为 null 时（理论上只在加载中可能进入）显示空提示，不崩。
 */
@Composable
private fun FullBoardPage(
    data: HotSearchData?,
    /**
     * 展开的是哪个榜（2026-10-30 需求 5：各平台也要能展开完整榜）。
     * `"all"` = 聚合总榜，其余是平台 key。
     */
    platformKey: String = "all",
    /** true = 展示的是演示数据（标题上加「（演示数据）」，见 2026-10-29 需求）。 */
    demo: Boolean = false,
    onBack: () -> Unit,
    onOpenBrief: (HotSearchItem) -> Unit
) {
    val isAll = platformKey == "all"
    val board = if (isAll) data?.aggregate else data?.platform(platformKey)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        // 标题栏：与页面其它二级界面的口径一致（返回箭头 + 标题 + 右端说明）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = (if (isAll) "今日聚合总榜" else platformName(platformKey)) +
                        if (demo) "（演示数据）" else "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = scheme.onSurface
                )
                Text(
                    text = board?.let { "共 ${it.items.size} 条 · ${dataStamp(it.updateTime)} 抓取" }
                        ?: "暂无数据",
                    fontSize = 11.sp,
                    color = if (demo) Color(0xFFB45309) else scheme.onSurfaceVariant
                )
            }
        }

        val items = board?.items.orEmpty()
        if (items.isEmpty()) {
            Text(
                text = "暂无数据\n请返回后点右上角刷新",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = scheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
            )
            return@Column
        }

        // 2026-10-22 需求 9：整张列表包进一张卡面。
        //
        // 深色主题下本应用的 background 与 surface 同色，纯列表直接铺在页面上
        // 会和背景"糊"在一起（没有卡边、分隔线也读不出层次）；垫一层
        // [cardSurface] 后与一级页的「今日聚合总榜」卡完全同一观感，
        // 从一级进二级也不会觉得换了一套排版。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardSurface(scheme))
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp
                )
            ) {
                items(items, key = { it.rank }) { item ->
                    BoardRow(
                        rank = item.rank,
                        title = item.title,
                        sourceNames = item.sourceNames,
                        heat = item.heat,
                        tag = item.tag,
                        onOpen = { onOpenBrief(item) }
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .height(1.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

/**
 * 「我的」→ 设置页（2026-10-22 需求：导航栏实现设置功能；版式参考设计稿 settingsView）。
 *
 * 三张卡：接口地址 / 数据模式 / 关于。
 * 与设计稿的差异只有一处：**「保存并刷新」会真的写入 DataStore 并立即重拉**
 * （设计稿是网页原型，只存在内存里）。
 */
@Composable
private fun HotSearchSettings(
    isCustomApi: Boolean,
    mode: String,
    snapshotStamp: String,
    onSaveApiUrl: (String) -> Unit,
    onResetApiUrl: () -> Unit,
    onShowDemo: () -> Unit,
    probes: List<PlatformProbe>,
    probing: Boolean,
    onProbe: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // 2026-11-09 用户要求：接口地址**不在界面明文显示**。
    // 输入框故意不预填当前地址（只给占位提示）——地址仍可自行填入，
    // 也可以直接点「恢复默认地址」用内置那一条。
    // 旧实现 `remember(apiUrl) { mutableStateOf(apiUrl) }` 等于把云端接口地址
    // 原样印在设置页上，截图/演示时会把内部接口带出去。
    var draft by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 接口地址 --------------------------------------------------------
        item(key = "api") {
            SettingsCard(title = "接口地址") {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = false,
                    maxLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = {
                        Text(
                            text = if (isCustomApi) "已设置自定义接口，输入新地址可替换"
                            else "留空使用内置默认接口，也可填入自己的地址",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isCustomApi) "当前使用自定义接口（地址已隐藏）"
                    else "当前使用内置默认接口",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsButton(text = "保存并刷新", primary = true) {
                        val url = draft.trim()
                        draft = ""
                        if (url.isBlank()) {
                            // 留空 = 用回内置默认接口（与占位提示同一语义），
                            // 而不是点了没反应。
                            onResetApiUrl()
                        } else {
                            onSaveApiUrl(url)
                        }
                    }
                    SettingsButton(text = "恢复默认地址", primary = false) {
                        draft = ""
                        onResetApiUrl()
                    }
                }
            }
        }

        // ---- 数据模式 --------------------------------------------------------
        item(key = "mode") {
            SettingsCard(title = "数据模式") {
                SettingsKv("当前模式", when (mode) {
                    "live" -> "云端实时数据"
                    "snapshot" -> "内置快照（接口不可达）"
                    "demo" -> "演示数据"
                    else -> "加载中"
                })
                SettingsKv("云端抓取", "后台定时抓取；右上角刷新可立即触发")
                SettingsKv("页面刷新", "打开即刷新 / 每 10 分钟")
                SettingsKv("内置快照", snapshotStamp.ifBlank { "-" })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsButton(text = "查看演示数据", primary = false) { onShowDemo() }
                }
            }
        }

        // ---- 平台原文读取检测（2026-11-09）------------------------------------
        item(key = "probe") {
            SettingsCard(title = "平台原文读取检测") {
                Text(
                    text = "逐个平台取当前榜首，检测能否读到原文并生成概况。" +
                        "走的是「新闻概况」同一条链路，每个平台可能各调用一次云端模型，" +
                        "整轮约需半分钟。",
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsButton(
                        text = if (probing) "检测中…（${probes.size}）" else "开始检测",
                        primary = true,
                        enabled = !probing
                    ) { onProbe() }
                }
                if (probes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    probes.forEach { probe ->
                        ProbeRow(probe)
                        Spacer(Modifier.height(6.dp))
                    }
                    val readable = probes.count { it.status == ProbeStatus.Fetched }
                    Text(
                        text = "可读原文 $readable/${probes.size} 个平台 · " +
                            "「仅模型」表示原文没读到，只能按标题生成概况。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 关于 ------------------------------------------------------------
        item(key = "about") {
            SettingsCard(title = "关于") {
                // 2026-10-30 用户要求「删除关于部分内容，保留实现原理」：
                // 数据来源 / 环境 ID / 聚合算法这三行是给开发者看的，普通用户读不出信息，
                // 其中环境 ID 还是一条内部标识（不该出现在界面里）。整块删掉，
                // 只留下面这段「实现原理」——它解释了「为什么一个话题有多个来源标记」，
                // 是用户真会问的问题。
                // 2026-10-22 需求 2：把「什么时候抓、怎么抓、怎么排」写进这里。
                // 原先这段写在榜单页脚（一句话塞了三件事，还在括号里塞了下次时间），
                // 既挤又像调试信息；放到关于页，用户可以主动来读。
                // 2026-10-23 需求 9：「实现原理」当成**正文**显示——
                // 单独一行小标题 + 下面一段正常正文（而不是原来那种把
                // 「实现原理」塞进同一段文字里的写法，那样它看起来像正文的第一句）。
                Text(
                    text = "实现原理",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "云函数按固定节奏抓取微博、知乎、百度、B 站、抖音等平台的实时热榜，" +
                        "把标题做相似度匹配并合并成同一话题（这也是「聚合总榜」里一个话题会带多个" +
                        "平台来源标记的原因），再按综合热度统一排序。" +
                        "客户端只负责取这份已经排好序的结果：打开页面取一次，" +
                        "之后每 10 分钟刷新；右上角刷新会让云端立刻再抓一轮（两次之间有一分钟冷却）。" +
                        "云函数不可达时回落到内置快照，界面上会标注「内置快照」。",
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = scheme.onSurface.copy(alpha = 0.86f)
                )
            }
        }
    }
}

/** 设置页的一张卡（标题 + 内容，样式与设计稿的 `.card` 对齐）。 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .padding(14.dp)
    ) {
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** 设置页的一行「键 + 值」。 */
@Composable
private fun SettingsKv(key: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = key,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 设置页的按钮（主按钮 = 暖橙实心，次按钮 = 描边；[enabled] 为 false 时整体淡化且不响应点击）。 */
@Composable
private fun SettingsButton(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val textColor = when {
        !enabled -> (if (primary) Color.White else scheme.onSurface).copy(alpha = 0.45f)
        primary -> Color.White
        else -> scheme.onSurface
    }
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !primary -> Color.Transparent
                    enabled -> HotAccent
                    else -> HotAccent.copy(alpha = 0.45f)
                }
            )
            .then(
                if (primary) Modifier
                else Modifier.border(
                    1.dp,
                    scheme.outlineVariant,
                    RoundedCornerShape(10.dp)
                )
            )
            .then(if (enabled) Modifier.pressScale(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

/**
 * 「平台原文读取检测」的一行：平台名 + 状态徽标 + 一句说明 + 耗时。
 *
 * 状态色：绿 = 读到原文，橙 = 只拿到模型总结，红 = 完全读不到，灰 = 榜单没有条目。
 * 耗时取的是整条链路（抓页 + 官方摘要 + 必要时模型），能直观看出哪个平台慢。
 */
@Composable
private fun ProbeRow(probe: PlatformProbe) {
    val scheme = MaterialTheme.colorScheme
    val (label, tint) = when (probe.status) {
        ProbeStatus.Fetched -> "可读原文" to Color(0xFF16A34A)
        ProbeStatus.ModelOnly -> "仅模型" to Color(0xFFEA580C)
        ProbeStatus.Failed -> "不可读" to Color(0xFFEF4444)
        ProbeStatus.NoData -> "无数据" to Color(0xFF94A3B8)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = probe.platformLabel,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            modifier = Modifier.width(64.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint.copy(alpha = 0.12f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = probe.detail,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (probe.elapsedMs > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${probe.elapsedMs}ms",
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/** 单平台的横向对比卡（品牌色圆点 + 平台名 + Top5）。 */
@Composable
private fun PlatformCard(
    data: HotSearchData,
    platformKey: String,
    onOpenBrief: (HotSearchItem) -> Unit,
    onOpenFullBoard: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val board = data.platform(platformKey)
    val rows = board?.items?.take(5).orEmpty()
    Column(
        modifier = Modifier
            .width(156.dp)
            // 2026-11-09 用户要求「横向对比各平台列表高度一致」：
            // 外层 Row 用 IntrinsicSize.Max，这里 fillMaxHeight 把每张卡拉到同一高度。
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(platformColor(platformKey))
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = platformName(platformKey),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = platformColor(platformKey),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(6.dp))
        // 行高固定 40dp：标题一行的平台和两行的平台不再一高一低；
        // 不足 5 条时用等高占位补齐，卡片底部也对齐。
        repeat(5) { index ->
            val item = rows.getOrNull(index)
            if (item == null) {
                Spacer(Modifier.height(40.dp))
            } else {
                Row(
                    modifier = Modifier.height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (index) {
                            0 -> HotAccent
                            1 -> Color(0xFFF97316)
                            2 -> Color(0xFFF5A623)
                            else -> Color(0xFFB0B6C0)
                        },
                        modifier = Modifier.width(13.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = item.title,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(onClick = { onOpenBrief(item) })
                    )
                }
            }
        }
        // 2026-10-30 需求 5：六大平台也各有全量榜，入口就放在各自的卡片底部
        // （平台卡是横向滑动的窄卡，只放 Top 5，不给入口用户就以为"就这五条"）。
        // 2026-11-09：footer 固定 20dp，有没有这个入口卡片高度都一样。
        Box(
            modifier = Modifier.height(20.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if ((board?.items?.size ?: 0) > 5) {
                Text(
                    text = "查看完整榜单 ›",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = HotAccent,
                    modifier = Modifier.pressScale(onClick = { onOpenFullBoard(platformKey) })
                )
            }
        }
    }
}

/** 搜索结果列表（在当前选中的榜单里按关键词过滤）。 */
@Composable
private fun SearchBody(
    data: HotSearchData?,
    keyword: String,
    selectedPlatform: String,
    onOpenBrief: (HotSearchItem) -> Unit
) {
    val kw = keyword.trim()
    val items = remember(data, kw, selectedPlatform) {
        val board = when {
            data == null -> emptyList()
            selectedPlatform == "all" -> data.aggregate.items
            else -> data.platform(selectedPlatform)?.items.orEmpty()
        }
        if (kw.isEmpty()) emptyList()
        else board.filter { it.title.contains(kw, ignoreCase = true) }
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        if (kw.isEmpty()) {
            Text(
                text = "输入关键词，在当前榜单中搜索话题",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else if (items.isEmpty()) {
            Text(
                text = "未找到与「$kw」相关的话题",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            Text(
                text = "找到 ${items.size} 条相关话题",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)
            ) {
                items(items, key = { "${it.rank}-${it.title}" }) { item ->
                    BoardRow(
                        rank = item.rank,
                        title = item.title,
                        sourceNames = item.sourceNames,
                        heat = item.heat,
                        tag = item.tag,
                        onOpen = { onOpenBrief(item) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 数据模型与解析
// ─────────────────────────────────────────────────────────────────────────────

/** 一个平台（或聚合总榜）的榜单。 */
internal class HotSearchBoard(
    val name: String,
    val updateTime: String,
    val items: List<HotSearchItem>
)

/** 榜单里的一条话题。 */
internal class HotSearchItem(
    val rank: Int,
    val title: String,
    val heat: Long,
    val tag: String,
    val url: String,
    val sourceNames: String
)

/**
 * 「平台原文读取检测」里单个平台的结果。
 *
 * 判据就是 [NewsBriefRepository.brief] 的返回来源，不另设阈值：
 * 官方 AI 总结 / 官方摘要 = 真读到了原文；`AI` = 原文没读到、只能按标题生成；
 * `NONE` = 两条路都没成。这样检测结果与用户点进「新闻概况」看到的东西始终一致。
 */
internal enum class ProbeStatus {
    /** 读到原文，且拿到了官方 AI 总结或官方摘要。 */
    Fetched,

    /** 原文没读到，只有云端模型按标题生成的概况。 */
    ModelOnly,

    /** 原文与模型总结都没成。 */
    Failed,

    /** 该平台榜单里没有可检测的条目。 */
    NoData
}

/** 一条检测结果：平台、状态、说明与整条链路耗时。 */
internal class PlatformProbe(
    val platformKey: String,
    val platformLabel: String,
    val status: ProbeStatus,
    val detail: String,
    val elapsedMs: Long
)

/**
 * 一次拉取到的全部数据：`aggregate` 是跨平台聚合总榜，其余 key 是各平台自己的榜。
 */
internal class HotSearchData(
    private val boards: Map<String, HotSearchBoard>
) {
    val aggregate: HotSearchBoard get() = boards.getValue("aggregate")

    fun platformKeys(): List<String> = boards.keys.filter { it != "aggregate" }

    fun platformCount(): Int = platformKeys().size

    fun platform(key: String): HotSearchBoard? = boards[key]

    /**
     * 追加一个来源（2026-10-27：腾讯新闻）。
     *
     * @param intoAggregate 是否把这些条目并进「全部」聚合总榜。**当前调用方传 false**。
     *
     * ## 为什么不并进聚合榜（实测结论，别再改回去）
     *
     * 第一版是并进去的：按标题去重 → 按热度重排 → 重编排名。结果实测**聚合榜前五
     * 变成四条知乎**，"东鹏饮料 / 补水啦"这些微博大热点直接被挤掉。
     * 原因很简单：各平台热度不是同一个量纲（微博 3000 万级 / 腾讯 hotScore 200 万级 /
     * 知乎另一个刻度），按数值混排等于"谁家数字大谁通吃"，这不是聚合，是污染。
     *
     * 云端已经按"每个平台自己的排名"聚好了，端上不该重排。
     * 所以腾讯只作为一个独立平台卡存在（`platformKeys()` 里会多出「腾讯新闻」，
     * 平台横向对比里也会有它），想看它自己的排序就点那个 chip。
     *
     * 保留 `intoAggregate` 参数是为了把那次的判据留在代码里，而不是删掉当没发生过。
     */
    fun plusBoard(
        key: String,
        board: HotSearchBoard,
        intoAggregate: Boolean = false
    ): HotSearchData {
        val next = boards.toMutableMap()
        next[key] = board
        if (!intoAggregate) return HotSearchData(next)
        val agg = boards["aggregate"] ?: return HotSearchData(next)
        val limit = agg.items.size.coerceAtLeast(5)
        val seen = agg.items.mapTo(HashSet()) { it.title }
        val extra = board.items.filter { seen.add(it.title) }
        if (extra.isEmpty()) return HotSearchData(next)
        val merged = (agg.items + extra)
            .sortedByDescending { it.heat }
            .take(limit)
            .mapIndexed { index, item ->
                HotSearchItem(
                    rank = index + 1,
                    title = item.title,
                    heat = item.heat,
                    tag = item.tag,
                    url = item.url,
                    sourceNames = item.sourceNames
                )
            }
        next["aggregate"] = HotSearchBoard(agg.name, agg.updateTime, merged)
        return HotSearchData(next)
    }
}

/**
 * 解析接口返回的 JSON（结构与网页版逐字段对齐）。
 *
 * 用平台自带的 `org.json`（Android OS 内置，零新增依赖——与本项目"不为一个功能
 * 引三方库"的纪律一致；网页版用的浏览器原生 JSON.parse 在 Android 侧对应它）。
 * 接受两种形态：`{code:0, data:{...}}` 包装（CloudBase 云函数），或直接的数据对象。
 */
internal fun parseHotSearchJson(text: String): HotSearchData? = runCatching {
    val root = org.json.JSONObject(text)
    val payload = when {
        root.optInt("code", -1) == 0 -> root.optJSONObject("data") ?: return null
        root.has("aggregate") -> root
        else -> return null
    }
    val boards = mutableMapOf<String, HotSearchBoard>()
    payload.keys().forEach { key ->
        val obj = payload.optJSONObject(key) ?: return@forEach
        val items = mutableListOf<HotSearchItem>()
        val arr = obj.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val title = it.optString("title", "")
                if (title.isBlank()) continue
                items += HotSearchItem(
                    rank = it.optInt("rank", items.size + 1),
                    title = title,
                    heat = it.optLong("heat", it.optLong("hotValue", 0L)),
                    tag = it.optString("tag", ""),
                    url = it.optString("url", ""),
                    sourceNames = it.optString("sourceNames", "")
                )
            }
        }
        boards[key] = HotSearchBoard(
            name = obj.optString("name", key),
            updateTime = obj.optString("updateTime", ""),
            items = items
        )
    }
    if (!boards.containsKey("aggregate")) return null
    HotSearchData(boards)
}.getOrNull()

// ─────────────────────────────────────────────────────────────────────────────
// 网络与工具
// ─────────────────────────────────────────────────────────────────────────────

/** 拉取并解析榜单；任何一步失败都返回 Failure（原因给提示条）。 */
internal suspend fun fetchHotSearchData(apiUrl: String): Result<HotSearchData> =
    withContext(Dispatchers.IO) {
        runCatching {
            val text = httpGetText(apiUrl)
            parseHotSearchJson(text) ?: error("接口返回格式无法解析")
        }
    }

/**
 * 解析腾讯新闻热榜。
 *
 * 结构：`{ret:0, idlist:[{newslist:[...]}]}`。第 0 条是"每 10 分钟更新一次"的说明
 * （`articletype == "560"`），跳过；其余每条给 `title / url / readCount / timestamp`。
 *
 * 热度用 `readCount`（阅读数）。它与微博的"热度"不是同一个量纲，
 * 混排只能算近似，所以腾讯同时以**独立平台卡**存在（见 [HotSearchData.plusBoard]）。
 */
internal fun parseTencentHotJson(text: String): HotSearchBoard? = runCatching {
    val root = org.json.JSONObject(text)
    if (root.optInt("ret", 0) != 0) return null
    val idList = root.optJSONArray("idlist") ?: return null
    val newsList = idList.optJSONObject(0)?.optJSONArray("newslist") ?: return null

    val items = mutableListOf<HotSearchItem>()
    for (i in 0 until newsList.length()) {
        val obj = newsList.optJSONObject(i) ?: continue
        // 「腾讯新闻用户最关注的热点，每10分钟更新一次」这条是说明文案，不是热点。
        val type = obj.optString("articletype", "")
        val id = obj.optString("id", "")
        if (type == "560" || id.startsWith("TIP")) continue
        val title = obj.optString("title", "").trim()
        if (title.isBlank()) continue
        val url = obj.optString("url", "").ifBlank { obj.optString("surl", "") }
        items += HotSearchItem(
            rank = items.size + 1,
            title = title,
            // ⚠️ 热度取 `hotEvent.hotScore`，**不是** `readCount`：
            // 实测 readCount 是几百量级（像是"某段时间内的阅读增量"），
            // 拿它当热度会出现"第 1 名 960、第 5 名 3499"这种倒挂。
            // hotScore 才是榜单真正的排序依据（80 万 ~ 240 万那一档）。
            heat = obj.optJSONObject("hotEvent")?.optLong("hotScore", 0L) ?: 0L,
            // 腾讯榜单不分"爆 / 热 / 新"，统一留空，避免画出一个假的标签。
            tag = "",
            url = url,
            sourceNames = obj.optString("source", "").ifBlank { "腾讯新闻" }
        )
    }
    if (items.isEmpty()) return null
    HotSearchBoard(
        name = "腾讯新闻",
        // 用**抓取时刻**而不是最新文章的发布时间：卡片上那行写的是「XX 抓取」，
        // 混入文章时间会让人以为这份榜单停在几小时前（实测出现过「03:19 抓取」）。
        // dataStamp() 认毫秒时间戳。
        updateTime = System.currentTimeMillis().toString(),
        items = items
    )
}.getOrNull()

/** 拉腾讯新闻热榜；失败返回 null（只少这一块，不打断整页）。 */
internal suspend fun fetchTencentHot(): HotSearchBoard? = withContext(Dispatchers.IO) {
    runCatching {
        val body = httpGetText(
            TENCENT_HOT_URL,
            headers = mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
        )
        val board = parseTencentHotJson(body)
        // 静默失败会让"到底有没有生效"变成猜谜，DEBUG 构建留一行日志。
        if (com.morecheck.BuildConfig.DEBUG) android.util.Log.d("HotSearch", "腾讯热榜：${board?.items?.size ?: -1} 条")
        board
    }.onFailure {
        if (com.morecheck.BuildConfig.DEBUG) android.util.Log.w("HotSearch", "腾讯热榜失败", it)
    }.getOrNull()
}

/**
 * 最朴素的 GET（超时 10 秒，状态码非 2xx 抛错）。
 *
 * ## 2026-10-27 起内部换成 OkHttp
 *
 * 原来这里是手写的 `HttpURLConnection`。之所以保留这个**函数**而不是让 5 个调用点
 * 直接调 `AppHttp.getText`：那几个调用点分布在热搜、腾讯热榜、GitHub 三处，
 * 签名完全相同，保留一层转发能把改动面压到一行——真正需要统一的是**实现**，不是名字。
 *
 * 行为有三点刻意保持不变：
 * - **同步**：调用方都在 `withContext(Dispatchers.IO)` 里；
 * - **10 秒超时**：见 `AppHttp` 的类 KDoc（调小会误判弱网失败）；
 * - **失败信息形如 `HTTP 403`**：`fetchTrending` 靠 `message.contains("403")`
 *   区分"限流"与"接口挂了"，改措辞会静默丢掉友好提示。
 *
 * 顺带得到的好处：连接池复用、自动 gzip、重定向处理，以及 `https` 证书校验
 * 不再依赖每个调用点自己写对。
 */
internal fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String =
    AppHttp.getText(url, headers)

/** URL 编码（java.net.URLEncoder；网页版用的是 encodeURIComponent，空格转 %20，这里等价处理）。 */
internal fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

/** 热度值格式化：万以下原样，万以上保留至多一位小数 + 「万」，亿同理（与网页版 fmtHot 一致）。 */
internal fun formatHeat(value: Long): String = when {
    value <= 0 -> ""
    value < 10_000 -> "$value"
    value < 100_000_000 -> {
        val w = value / 10_000.0
        if (w >= 100) "${w.toInt()}万"
        else String.format(java.util.Locale.ROOT, "%.1f", w).removeSuffix(".0") + "万"
    }
    else -> String.format(java.util.Locale.ROOT, "%.1f", value / 100_000_000.0)
        .removeSuffix(".0") + "亿"
}

/** 时间戳（ISO 或毫秒）→ 「MM-dd HH:mm」。解析不了返回空串。 */
internal fun dataStamp(raw: String): String = runCatching {
    val instant = raw.toLongOrNull()?.let { Instant.ofEpochMilli(it) }
        ?: Instant.parse(raw)
    val local = instant.atZone(ZoneId.systemDefault())
    DateTimeFormatter.ofPattern("MM-dd HH:mm").format(local)
}.getOrDefault("")

/** 下次云端抓取时间文案（每 4 小时整点 0/4/8/12/16/20）。 */
internal fun nextCrawlText(): String {
    val now = java.time.LocalDateTime.now()
    val hours = listOf(0, 4, 8, 12, 16, 20)
    val next = hours.firstOrNull { it > now.hour }
    val target = if (next != null) {
        now.withHour(next).withMinute(0)
    } else {
        now.plusDays(1).withHour(0).withMinute(0)
    }
    return DateTimeFormatter.ofPattern("MM-dd HH:mm").format(target)
}

/** 点话题行 → 有链接用系统浏览器打开；无链接不动作（设计稿的聚合条目可能没有 url）。 */
internal fun openTopic(context: Context, url: String, onMessage: (String) -> Unit) {
    if (url.isBlank()) return
    if (!url.startsWith("https://", ignoreCase = true)) {
        onMessage("出于安全考虑，仅支持打开 https 链接")
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { onMessage("没有可用的浏览器") }
}

internal fun copyText(context: Context, text: String, onMessage: (String) -> Unit) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        onMessage("复制失败：剪贴板不可用")
        return
    }
    runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("接口地址", text)) }
        .onSuccess { onMessage("接口地址已复制") }
        .onFailure { onMessage("复制失败，请重试") }
}

// ─────────────────────────────────────────────────────────────────────────────
// 内置数据快照（离线兜底）
// ─────────────────────────────────────────────────────────────────────────────

// 自动生成自网页版 index.html 的内置快照（抓取于 2026-10-20 22:23，勿手改数字；
// 重新生成脚本：.verify/generate_snapshot.py）。
internal fun buildSnapshotData(): HotSearchData {
    val boards = mutableMapOf<String, HotSearchBoard>()

    val items_weibo = mutableListOf<HotSearchItem>()
    items_weibo += HotSearchItem(1, "油车电车10年成本账怎么算", 2081980, "新", "https://s.weibo.com/weibo?q=%E6%B2%B9%E8%BD%A6%E7%94%B5%E8%BD%A610%E5%B9%B4%E6%88%90%E6%9C%AC%E8%B4%A6%E6%80%8E%E4%B9%88%E7%AE%97", "")
    items_weibo += HotSearchItem(2, "女孩 这个世界因你的较真而进步", 979374, "新", "https://s.weibo.com/weibo?q=%E5%A5%B3%E5%AD%A9%20%E8%BF%99%E4%B8%AA%E4%B8%96%E7%95%8C%E5%9B%A0%E4%BD%A0%E7%9A%84%E8%BE%83%E7%9C%9F%E8%80%8C%E8%BF%9B%E6%AD%A5", "")
    items_weibo += HotSearchItem(3, "伟大长征精神何以永恒", 785666, "", "https://s.weibo.com/weibo?q=%E4%BC%9F%E5%A4%A7%E9%95%BF%E5%BE%81%E7%B2%BE%E7%A5%9E%E4%BD%95%E4%BB%A5%E6%B0%B8%E6%81%92", "")
    items_weibo += HotSearchItem(4, "今晚8点双11买爆款上淘宝", 782978, "", "https://s.weibo.com/weibo?q=%E4%BB%8A%E6%99%9A8%E7%82%B9%E5%8F%8C11%E4%B9%B0%E7%88%86%E6%AC%BE%E4%B8%8A%E6%B7%98%E5%AE%9D", "")
    items_weibo += HotSearchItem(5, "理想汽车发布电池保障政策", 516776, "新", "https://s.weibo.com/weibo?q=%E7%90%86%E6%83%B3%E6%B1%BD%E8%BD%A6%E5%8F%91%E5%B8%83%E7%94%B5%E6%B1%A0%E4%BF%9D%E9%9A%9C%E6%94%BF%E7%AD%96", "")
    items_weibo += HotSearchItem(6, "美人余大结局可太解气了", 477640, "新", "https://s.weibo.com/weibo?q=%E7%BE%8E%E4%BA%BA%E4%BD%99%E5%A4%A7%E7%BB%93%E5%B1%80%E5%8F%AF%E5%A4%AA%E8%A7%A3%E6%B0%94%E4%BA%86", "")
    items_weibo += HotSearchItem(7, "到底谁把微信变成工作软件的", 387526, "", "https://s.weibo.com/weibo?q=%E5%88%B0%E5%BA%95%E8%B0%81%E6%8A%8A%E5%BE%AE%E4%BF%A1%E5%8F%98%E6%88%90%E5%B7%A5%E4%BD%9C%E8%BD%AF%E4%BB%B6%E7%9A%84", "")
    items_weibo += HotSearchItem(8, "迪丽热巴瑞士莲全新品牌代言人", 384021, "", "https://s.weibo.com/weibo?q=%E8%BF%AA%E4%B8%BD%E7%83%AD%E5%B7%B4%E7%91%9E%E5%A3%AB%E8%8E%B2%E5%85%A8%E6%96%B0%E5%93%81%E7%89%8C%E4%BB%A3%E8%A8%80%E4%BA%BA", "")
    items_weibo += HotSearchItem(9, "井柏然最有腔调的红毯来了", 368051, "新", "https://s.weibo.com/weibo?q=%E4%BA%95%E6%9F%8F%E7%84%B6%E6%9C%80%E6%9C%89%E8%85%94%E8%B0%83%E7%9A%84%E7%BA%A2%E6%AF%AF%E6%9D%A5%E4%BA%86", "")
    items_weibo += HotSearchItem(10, "钟南山心梗后仍每周运动3次", 352834, "", "https://s.weibo.com/weibo?q=%E9%92%9F%E5%8D%97%E5%B1%B1%E5%BF%83%E6%A2%97%E5%90%8E%E4%BB%8D%E6%AF%8F%E5%91%A8%E8%BF%90%E5%8A%A83%E6%AC%A1", "")
    items_weibo += HotSearchItem(11, "宁德时代三季报净利645亿", 351780, "新", "https://s.weibo.com/weibo?q=%E5%AE%81%E5%BE%B7%E6%97%B6%E4%BB%A3%E4%B8%89%E5%AD%A3%E6%8A%A5%E5%87%80%E5%88%A9645%E4%BA%BF", "")
    items_weibo += HotSearchItem(12, "27岁宠物爱心博主感染罕见真菌离世", 351152, "", "https://s.weibo.com/weibo?q=27%E5%B2%81%E5%AE%A0%E7%89%A9%E7%88%B1%E5%BF%83%E5%8D%9A%E4%B8%BB%E6%84%9F%E6%9F%93%E7%BD%95%E8%A7%81%E7%9C%9F%E8%8F%8C%E7%A6%BB%E4%B8%96", "")
    items_weibo += HotSearchItem(13, "曝湖南台力捧张雅琪原因", 349808, "热", "https://s.weibo.com/weibo?q=%E6%9B%9D%E6%B9%96%E5%8D%97%E5%8F%B0%E5%8A%9B%E6%8D%A7%E5%BC%A0%E9%9B%85%E7%90%AA%E5%8E%9F%E5%9B%A0", "")
    items_weibo += HotSearchItem(14, "陆毅鲍蕾女儿18岁生日试装", 349196, "热", "https://s.weibo.com/weibo?q=%E9%99%86%E6%AF%85%E9%B2%8D%E8%95%BE%E5%A5%B3%E5%84%BF18%E5%B2%81%E7%94%9F%E6%97%A5%E8%AF%95%E8%A3%85", "")
    items_weibo += HotSearchItem(15, "唐禹哲获刑1年7个月缓刑3年", 348128, "热", "https://s.weibo.com/weibo?q=%E5%94%90%E7%A6%B9%E5%93%B2%E8%8E%B7%E5%88%911%E5%B9%B47%E4%B8%AA%E6%9C%88%E7%BC%93%E5%88%913%E5%B9%B4", "")
    items_weibo += HotSearchItem(16, "王腾说希望雷总放我们一条生路", 346759, "", "https://s.weibo.com/weibo?q=%E7%8E%8B%E8%85%BE%E8%AF%B4%E5%B8%8C%E6%9C%9B%E9%9B%B7%E6%80%BB%E6%94%BE%E6%88%91%E4%BB%AC%E4%B8%80%E6%9D%A1%E7%94%9F%E8%B7%AF", "")
    items_weibo += HotSearchItem(17, "马龙生日众好友欢聚庆祝", 346000, "新", "https://s.weibo.com/weibo?q=%E9%A9%AC%E9%BE%99%E7%94%9F%E6%97%A5%E4%BC%97%E5%A5%BD%E5%8F%8B%E6%AC%A2%E8%81%9A%E5%BA%86%E7%A5%9D", "")
    items_weibo += HotSearchItem(18, "刘恺威黎纪君承认恋情", 344152, "热", "https://s.weibo.com/weibo?q=%E5%88%98%E6%81%BA%E5%A8%81%E9%BB%8E%E7%BA%AA%E5%90%9B%E6%89%BF%E8%AE%A4%E6%81%8B%E6%83%85", "")
    items_weibo += HotSearchItem(19, "男子抽关节积液抽出喷泉效果", 343722, "", "https://s.weibo.com/weibo?q=%E7%94%B7%E5%AD%90%E6%8A%BD%E5%85%B3%E8%8A%82%E7%A7%AF%E6%B6%B2%E6%8A%BD%E5%87%BA%E5%96%B7%E6%B3%89%E6%95%88%E6%9E%9C", "")
    items_weibo += HotSearchItem(20, "肖战演唱会二开售票无退票权益", 342728, "", "https://s.weibo.com/weibo?q=%E8%82%96%E6%88%98%E6%BC%94%E5%94%B1%E4%BC%9A%E4%BA%8C%E5%BC%80%E5%94%AE%E7%A5%A8%E6%97%A0%E9%80%80%E7%A5%A8%E6%9D%83%E7%9B%8A", "")
    boards["weibo"] = HotSearchBoard("微博热搜", "2026-10-20T14:23:56.008Z", items_weibo)

    val items_zhihu = mutableListOf<HotSearchItem>()
    items_zhihu += HotSearchItem(1, "郑钦文世界排名升至第22", 0, "", "https://www.zhihu.com/search?type=content&q=%E9%83%91%E9%92%A6%E6%96%87%E4%B8%96%E7%95%8C%E6%8E%92%E5%90%8D%E5%8D%87%E8%87%B3%E7%AC%AC22", "")
    items_zhihu += HotSearchItem(2, "美股牛市4岁了", 0, "", "https://www.zhihu.com/search?type=content&q=%E7%BE%8E%E8%82%A1%E7%89%9B%E5%B8%824%E5%B2%81%E4%BA%86", "")
    items_zhihu += HotSearchItem(3, "兰香如故", 0, "", "https://www.zhihu.com/search?type=content&q=%E5%85%B0%E9%A6%99%E5%A6%82%E6%95%85", "")
    items_zhihu += HotSearchItem(4, "嫁金钗", 0, "", "https://www.zhihu.com/search?type=content&q=%E5%AB%81%E9%87%91%E9%92%97", "")
    items_zhihu += HotSearchItem(5, "Yandex数据中心遭乌袭击停运", 0, "", "https://www.zhihu.com/search?type=content&q=Yandex%E6%95%B0%E6%8D%AE%E4%B8%AD%E5%BF%83%E9%81%AD%E4%B9%8C%E8%A2%AD%E5%87%BB%E5%81%9C%E8%BF%90", "")
    items_zhihu += HotSearchItem(6, "缩量", 0, "", "https://www.zhihu.com/search?type=content&q=%E7%BC%A9%E9%87%8F", "")
    items_zhihu += HotSearchItem(7, "女子晒手机单号遭5人接力冒领", 0, "", "https://www.zhihu.com/search?type=content&q=%E5%A5%B3%E5%AD%90%E6%99%92%E6%89%8B%E6%9C%BA%E5%8D%95%E5%8F%B7%E9%81%AD5%E4%BA%BA%E6%8E%A5%E5%8A%9B%E5%86%92%E9%A2%86", "")
    items_zhihu += HotSearchItem(8, "特斯拉SpaceX大跌", 0, "", "https://www.zhihu.com/search?type=content&q=%E7%89%B9%E6%96%AF%E6%8B%89SpaceX%E5%A4%A7%E8%B7%8C", "")
    items_zhihu += HotSearchItem(9, "自愿放弃社保无效", 0, "", "https://www.zhihu.com/search?type=content&q=%E8%87%AA%E6%84%BF%E6%94%BE%E5%BC%83%E7%A4%BE%E4%BF%9D%E6%97%A0%E6%95%88", "")
    items_zhihu += HotSearchItem(10, "妈妈因大一儿子未考技能证书降生活费", 0, "", "https://www.zhihu.com/search?type=content&q=%E5%A6%88%E5%A6%88%E5%9B%A0%E5%A4%A7%E4%B8%80%E5%84%BF%E5%AD%90%E6%9C%AA%E8%80%83%E6%8A%80%E8%83%BD%E8%AF%81%E4%B9%A6%E9%99%8D%E7%94%9F%E6%B4%BB%E8%B4%B9", "")
    boards["zhihu"] = HotSearchBoard("知乎热搜", "2026-10-20T14:23:56.308Z", items_zhihu)

    val items_bilibili = mutableListOf<HotSearchItem>()
    items_bilibili += HotSearchItem(1, "王力宏rap回应在梅边舞台", 7468247, "", "https://search.bilibili.com/all?keyword=%E7%8E%8B%E5%8A%9B%E5%AE%8Frap%E5%9B%9E%E5%BA%94%E5%9C%A8%E6%A2%85%E8%BE%B9%E8%88%9E%E5%8F%B0", "")
    items_bilibili += HotSearchItem(2, "重庆狼队 深圳DYG", 4291369, "", "https://search.bilibili.com/all?keyword=%E9%87%8D%E5%BA%86%E7%8B%BC%E9%98%9F%20%E6%B7%B1%E5%9C%B3DYG", "")
    items_bilibili += HotSearchItem(3, "A2O LITE入驻B站", 1547230, "", "https://search.bilibili.com/all?keyword=A2O%20LITE%E5%85%A5%E9%A9%BBB%E7%AB%99", "")
    items_bilibili += HotSearchItem(4, "IG全球总决赛出征片", 1533537, "", "https://search.bilibili.com/all?keyword=IG%E5%85%A8%E7%90%83%E6%80%BB%E5%86%B3%E8%B5%9B%E5%87%BA%E5%BE%81%E7%89%87", "")
    items_bilibili += HotSearchItem(5, "纪念长征胜利90周年主题晚会", 1487044, "", "https://search.bilibili.com/all?keyword=%E7%BA%AA%E5%BF%B5%E9%95%BF%E5%BE%81%E8%83%9C%E5%88%A990%E5%91%A8%E5%B9%B4%E4%B8%BB%E9%A2%98%E6%99%9A%E4%BC%9A", "")
    items_bilibili += HotSearchItem(6, "歌剧老师锐评S16主题曲", 1175793, "", "https://search.bilibili.com/all?keyword=%E6%AD%8C%E5%89%A7%E8%80%81%E5%B8%88%E9%94%90%E8%AF%84S16%E4%B8%BB%E9%A2%98%E6%9B%B2", "")
    items_bilibili += HotSearchItem(7, "4万字拆解9分动画边缘行者", 1158425, "", "https://search.bilibili.com/all?keyword=4%E4%B8%87%E5%AD%97%E6%8B%86%E8%A7%A39%E5%88%86%E5%8A%A8%E7%94%BB%E8%BE%B9%E7%BC%98%E8%A1%8C%E8%80%85", "")
    items_bilibili += HotSearchItem(8, "NBA西部常规赛各队问题是什么", 1096187, "", "https://search.bilibili.com/all?keyword=NBA%E8%A5%BF%E9%83%A8%E5%B8%B8%E8%A7%84%E8%B5%9B%E5%90%84%E9%98%9F%E9%97%AE%E9%A2%98%E6%98%AF%E4%BB%80%E4%B9%88", "")
    items_bilibili += HotSearchItem(9, "理不清三本账的欧盟将何去何从", 1084540, "", "https://search.bilibili.com/all?keyword=%E7%90%86%E4%B8%8D%E6%B8%85%E4%B8%89%E6%9C%AC%E8%B4%A6%E7%9A%84%E6%AC%A7%E7%9B%9F%E5%B0%86%E4%BD%95%E5%8E%BB%E4%BD%95%E4%BB%8E", "")
    items_bilibili += HotSearchItem(10, "能飞的特斯拉有哪些技术难点", 1069978, "", "https://search.bilibili.com/all?keyword=%E8%83%BD%E9%A3%9E%E7%9A%84%E7%89%B9%E6%96%AF%E6%8B%89%E6%9C%89%E5%93%AA%E4%BA%9B%E6%8A%80%E6%9C%AF%E9%9A%BE%E7%82%B9", "")
    items_bilibili += HotSearchItem(11, "绝区零菲欧妮EP", 1066414, "", "https://search.bilibili.com/all?keyword=%E7%BB%9D%E5%8C%BA%E9%9B%B6%E8%8F%B2%E6%AC%A7%E5%A6%AEEP", "")
    items_bilibili += HotSearchItem(12, "如何看索汉一年被裁两次", 1040282, "", "https://search.bilibili.com/all?keyword=%E5%A6%82%E4%BD%95%E7%9C%8B%E7%B4%A2%E6%B1%89%E4%B8%80%E5%B9%B4%E8%A2%AB%E8%A3%81%E4%B8%A4%E6%AC%A1", "")
    items_bilibili += HotSearchItem(13, "前三季度我国GDP同比增长4.6% ​", 1040281, "", "https://search.bilibili.com/all?keyword=%E5%89%8D%E4%B8%89%E5%AD%A3%E5%BA%A6%E6%88%91%E5%9B%BDGDP%E5%90%8C%E6%AF%94%E5%A2%9E%E9%95%BF4.6%25%20%E2%80%8B", "")
    items_bilibili += HotSearchItem(14, "IG出征单曲 仁川共鸣", 1040279, "", "https://search.bilibili.com/all?keyword=IG%E5%87%BA%E5%BE%81%E5%8D%95%E6%9B%B2%20%E4%BB%81%E5%B7%9D%E5%85%B1%E9%B8%A3", "")
    items_bilibili += HotSearchItem(15, "UP主拆解爆款AI代码动画", 1040278, "", "https://search.bilibili.com/all?keyword=UP%E4%B8%BB%E6%8B%86%E8%A7%A3%E7%88%86%E6%AC%BEAI%E4%BB%A3%E7%A0%81%E5%8A%A8%E7%94%BB", "")
    items_bilibili += HotSearchItem(16, "崩铁仙舟是正义的吗", 1040278, "", "https://search.bilibili.com/all?keyword=%E5%B4%A9%E9%93%81%E4%BB%99%E8%88%9F%E6%98%AF%E6%AD%A3%E4%B9%89%E7%9A%84%E5%90%97", "")
    items_bilibili += HotSearchItem(17, "律师回应王扁摆摊被袭击事件", 1040277, "", "https://search.bilibili.com/all?keyword=%E5%BE%8B%E5%B8%88%E5%9B%9E%E5%BA%94%E7%8E%8B%E6%89%81%E6%91%86%E6%91%8A%E8%A2%AB%E8%A2%AD%E5%87%BB%E4%BA%8B%E4%BB%B6", "")
    items_bilibili += HotSearchItem(18, "我推的BLG", 1040254, "", "https://search.bilibili.com/all?keyword=%E6%88%91%E6%8E%A8%E7%9A%84BLG", "")
    items_bilibili += HotSearchItem(19, "怎么看王扁街头遇袭事件", 1040115, "", "https://search.bilibili.com/all?keyword=%E6%80%8E%E4%B9%88%E7%9C%8B%E7%8E%8B%E6%89%81%E8%A1%97%E5%A4%B4%E9%81%87%E8%A2%AD%E4%BA%8B%E4%BB%B6", "")
    items_bilibili += HotSearchItem(20, "中年人的重阳节摇滚单曲", 1039709, "", "https://search.bilibili.com/all?keyword=%E4%B8%AD%E5%B9%B4%E4%BA%BA%E7%9A%84%E9%87%8D%E9%98%B3%E8%8A%82%E6%91%87%E6%BB%9A%E5%8D%95%E6%9B%B2", "")
    boards["bilibili"] = HotSearchBoard("B站热搜", "2026-10-20T14:23:56.555Z", items_bilibili)

    val items_baidu = mutableListOf<HotSearchItem>()
    items_baidu += HotSearchItem(1, "菲媒：若驱逐中国大使中方必对等回应", 7809298, "", "https://www.baidu.com/s?wd=%E8%8F%B2%E5%AA%92%EF%BC%9A%E8%8B%A5%E9%A9%B1%E9%80%90%E4%B8%AD%E5%9B%BD%E5%A4%A7%E4%BD%BF%E4%B8%AD%E6%96%B9%E5%BF%85%E5%AF%B9%E7%AD%89%E5%9B%9E%E5%BA%94&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(2, "俞小平被开除党籍 中国人保表态", 7711878, "", "https://www.baidu.com/s?wd=%E4%BF%9E%E5%B0%8F%E5%B9%B3%E8%A2%AB%E5%BC%80%E9%99%A4%E5%85%9A%E7%B1%8D+%E4%B8%AD%E5%9B%BD%E4%BA%BA%E4%BF%9D%E8%A1%A8%E6%80%81&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(3, "前三季度中国经济“成绩单”来了", 7616004, "", "https://www.baidu.com/s?wd=%E5%89%8D%E4%B8%89%E5%AD%A3%E5%BA%A6%E4%B8%AD%E5%9B%BD%E7%BB%8F%E6%B5%8E%E2%80%9C%E6%88%90%E7%BB%A9%E5%8D%95%E2%80%9D%E6%9D%A5%E4%BA%86&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(4, "学生机直逼5000元", 7521418, "", "https://www.baidu.com/s?wd=%E5%AD%A6%E7%94%9F%E6%9C%BA%E7%9B%B4%E9%80%BC5000%E5%85%83&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(5, "中年男子骑车载白发老人看哭网友", 7428090, "", "https://www.baidu.com/s?wd=%E4%B8%AD%E5%B9%B4%E7%94%B7%E5%AD%90%E9%AA%91%E8%BD%A6%E8%BD%BD%E7%99%BD%E5%8F%91%E8%80%81%E4%BA%BA%E7%9C%8B%E5%93%AD%E7%BD%91%E5%8F%8B&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(6, "100家！最新央企名录公布", 7332220, "", "https://www.baidu.com/s?wd=100%E5%AE%B6%EF%BC%81%E6%9C%80%E6%96%B0%E5%A4%AE%E4%BC%81%E5%90%8D%E5%BD%95%E5%85%AC%E5%B8%83&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(7, "工信部：禁止车辆行驶时用零重力座椅", 7237925, "", "https://www.baidu.com/s?wd=%E5%B7%A5%E4%BF%A1%E9%83%A8%EF%BC%9A%E7%A6%81%E6%AD%A2%E8%BD%A6%E8%BE%86%E8%A1%8C%E9%A9%B6%E6%97%B6%E7%94%A8%E9%9B%B6%E9%87%8D%E5%8A%9B%E5%BA%A7%E6%A4%85&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(8, "王腾：希望雷总放我们一条生路", 7136030, "", "https://www.baidu.com/s?wd=%E7%8E%8B%E8%85%BE%EF%BC%9A%E5%B8%8C%E6%9C%9B%E9%9B%B7%E6%80%BB%E6%94%BE%E6%88%91%E4%BB%AC%E4%B8%80%E6%9D%A1%E7%94%9F%E8%B7%AF&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(9, "公交车闯红灯致6死16伤 调查报告发布", 7045763, "", "https://www.baidu.com/s?wd=%E5%85%AC%E4%BA%A4%E8%BD%A6%E9%97%AF%E7%BA%A2%E7%81%AF%E8%87%B46%E6%AD%BB16%E4%BC%A4+%E8%B0%83%E6%9F%A5%E6%8A%A5%E5%91%8A%E5%8F%91%E5%B8%83&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(10, "岳云鹏专场遇音响事故 观众喊退票", 6950841, "", "https://www.baidu.com/s?wd=%E5%B2%B3%E4%BA%91%E9%B9%8F%E4%B8%93%E5%9C%BA%E9%81%87%E9%9F%B3%E5%93%8D%E4%BA%8B%E6%95%85+%E8%A7%82%E4%BC%97%E5%96%8A%E9%80%80%E7%A5%A8&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(11, "“完美恋人”背后的陷阱", 6850760, "", "https://www.baidu.com/s?wd=%E2%80%9C%E5%AE%8C%E7%BE%8E%E6%81%8B%E4%BA%BA%E2%80%9D%E8%83%8C%E5%90%8E%E7%9A%84%E9%99%B7%E9%98%B1&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(12, "钟南山心梗后仍每周运动3次", 6763541, "", "https://www.baidu.com/s?wd=%E9%92%9F%E5%8D%97%E5%B1%B1%E5%BF%83%E6%A2%97%E5%90%8E%E4%BB%8D%E6%AF%8F%E5%91%A8%E8%BF%90%E5%8A%A83%E6%AC%A1&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(13, "宠物博主感染罕见真菌病逝 年仅27岁", 6666098, "", "https://www.baidu.com/s?wd=%E5%AE%A0%E7%89%A9%E5%8D%9A%E4%B8%BB%E6%84%9F%E6%9F%93%E7%BD%95%E8%A7%81%E7%9C%9F%E8%8F%8C%E7%97%85%E9%80%9D+%E5%B9%B4%E4%BB%8527%E5%B2%81&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(14, "米粉店老板靠一句“泥嚎”意外走红", 6566372, "", "https://www.baidu.com/s?wd=%E7%B1%B3%E7%B2%89%E5%BA%97%E8%80%81%E6%9D%BF%E9%9D%A0%E4%B8%80%E5%8F%A5%E2%80%9C%E6%B3%A5%E5%9A%8E%E2%80%9D%E6%84%8F%E5%A4%96%E8%B5%B0%E7%BA%A2&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(15, "瓶装饮料集体涨价", 6473462, "", "https://www.baidu.com/s?wd=%E7%93%B6%E8%A3%85%E9%A5%AE%E6%96%99%E9%9B%86%E4%BD%93%E6%B6%A8%E4%BB%B7&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(16, "大同下雪了", 6381215, "", "https://www.baidu.com/s?wd=%E5%A4%A7%E5%90%8C%E4%B8%8B%E9%9B%AA%E4%BA%86&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(17, "杨小伟一审被判12年", 6271055, "", "https://www.baidu.com/s?wd=%E6%9D%A8%E5%B0%8F%E4%BC%9F%E4%B8%80%E5%AE%A1%E8%A2%AB%E5%88%A412%E5%B9%B4&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(18, "钟南山才是真正的薄肌“90后”", 6176933, "", "https://www.baidu.com/s?wd=%E9%92%9F%E5%8D%97%E5%B1%B1%E6%89%8D%E6%98%AF%E7%9C%9F%E6%AD%A3%E7%9A%84%E8%96%84%E8%82%8C%E2%80%9C90%E5%90%8E%E2%80%9D&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(19, "新央企董事长总经理亮相", 6086569, "", "https://www.baidu.com/s?wd=%E6%96%B0%E5%A4%AE%E4%BC%81%E8%91%A3%E4%BA%8B%E9%95%BF%E6%80%BB%E7%BB%8F%E7%90%86%E4%BA%AE%E7%9B%B8&sa=fyb_news&rsv_dl=fyb_news", "")
    items_baidu += HotSearchItem(20, "“医护无过错不致歉”", 5996647, "", "https://www.baidu.com/s?wd=%E2%80%9C%E5%8C%BB%E6%8A%A4%E6%97%A0%E8%BF%87%E9%94%99%E4%B8%8D%E8%87%B4%E6%AD%89%E2%80%9D&sa=fyb_news&rsv_dl=fyb_news", "")
    boards["baidu"] = HotSearchBoard("百度热搜", "2026-10-20T14:23:56.695Z", items_baidu)

    val items_toutiao = mutableListOf<HotSearchItem>()
    items_toutiao += HotSearchItem(1, "工信部：禁止车辆行驶时用零重力座椅", 22310337, "", "https://www.toutiao.com/trending/7698667807361319433/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2215%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698667807361319433%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E5%B7%A5%E4%BF%A1%E9%83%A8%EF%BC%9A%E7%A6%81%E6%AD%A2%E8%BD%A6%E8%BE%86%E8%A1%8C%E9%A9%B6%E6%97%B6%E7%94%A8%E9%9B%B6%E9%87%8D%E5%8A%9B%E5%BA%A7%E6%A4%85%22%7D&rank=&style_id=40132&topic_id=7698667807361319433", "")
    items_toutiao += HotSearchItem(2, "美国一战斗舰冲闯中国南沙海域", 20187228, "热", "https://www.toutiao.com/trending/7698609753362026022/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%225%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698609753362026022%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%BE%8E%E5%9B%BD%E4%B8%80%E6%88%98%E6%96%97%E8%88%B0%E5%86%B2%E9%97%AF%E4%B8%AD%E5%9B%BD%E5%8D%97%E6%B2%99%E6%B5%B7%E5%9F%9F%22%7D&rank=&style_id=40132&topic_id=7698609753362026022", "")
    items_toutiao += HotSearchItem(3, "前三季度中国经济“成绩单”来了", 18266159, "", "https://www.toutiao.com/article/7698655799295492617", "")
    items_toutiao += HotSearchItem(4, "特朗普：中方在乌问题上立场很公允", 16527904, "", "https://www.toutiao.com/trending/7697897058136604715/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%221%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227697897058136604715%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%89%B9%E6%9C%97%E6%99%AE%EF%BC%9A%E4%B8%AD%E6%96%B9%E5%9C%A8%E4%B9%8C%E9%97%AE%E9%A2%98%E4%B8%8A%E7%AB%8B%E5%9C%BA%E5%BE%88%E5%85%AC%E5%85%81%22%7D&rank=&style_id=40132&topic_id=7697897058136604715", "")
    items_toutiao += HotSearchItem(5, "张雪说不要送米了", 14955066, "", "https://www.toutiao.com/trending/7698296287954780179/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%222%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698296287954780179%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E5%BC%A0%E9%9B%AA%E8%AF%B4%E4%B8%8D%E8%A6%81%E9%80%81%E7%B1%B3%E4%BA%86%22%7D&rank=&style_id=40132&topic_id=7698296287954780179", "")
    items_toutiao += HotSearchItem(6, "菲媒警告：驱逐大使中方必然对等回应", 13531903, "热", "https://www.toutiao.com/trending/7698665786767868467/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2215%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698665786767868467%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E8%8F%B2%E5%AA%92%E8%AD%A6%E5%91%8A%EF%BC%9A%E9%A9%B1%E9%80%90%E5%A4%A7%E4%BD%BF%E4%B8%AD%E6%96%B9%E5%BF%85%E7%84%B6%E5%AF%B9%E7%AD%89%E5%9B%9E%E5%BA%94%22%7D&rank=&style_id=40132&topic_id=7698665786767868467", "")
    items_toutiao += HotSearchItem(7, "专家：美日利剑演习剧本值得高度警惕", 12244173, "新", "https://www.toutiao.com/trending/7697966461738270758/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%226%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227697966461738270758%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E4%B8%93%E5%AE%B6%EF%BC%9A%E7%BE%8E%E6%97%A5%E5%88%A9%E5%89%91%E6%BC%94%E4%B9%A0%E5%89%A7%E6%9C%AC%E5%80%BC%E5%BE%97%E9%AB%98%E5%BA%A6%E8%AD%A6%E6%83%95%22%7D&rank=&style_id=40132&topic_id=7697966461738270758", "")
    items_toutiao += HotSearchItem(8, "专家：房地产最危险的是不涨也不跌", 11078985, "", "https://www.toutiao.com/trending/7698687758277021226/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2213%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698687758277021226%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E4%B8%93%E5%AE%B6%EF%BC%9A%E6%88%BF%E5%9C%B0%E4%BA%A7%E6%9C%80%E5%8D%B1%E9%99%A9%E7%9A%84%E6%98%AF%E4%B8%8D%E6%B6%A8%E4%B9%9F%E4%B8%8D%E8%B7%8C%22%7D&rank=&style_id=40132&topic_id=7698687758277021226", "")
    items_toutiao += HotSearchItem(9, "新央企国家数据集团成立", 10024681, "", "https://www.toutiao.com/trending/7698695338558947355/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%222%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698695338558947355%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E6%96%B0%E5%A4%AE%E4%BC%81%E5%9B%BD%E5%AE%B6%E6%95%B0%E6%8D%AE%E9%9B%86%E5%9B%A2%E6%88%90%E7%AB%8B%22%7D&rank=&style_id=40132&topic_id=7698695338558947355", "")
    items_toutiao += HotSearchItem(10, "邵永灵：美日军演解放军无人机侦察", 9070706, "", "https://www.toutiao.com/trending/7698698383946419775/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2213%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698698383946419775%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E9%82%B5%E6%B0%B8%E7%81%B5%EF%BC%9A%E7%BE%8E%E6%97%A5%E5%86%9B%E6%BC%94%E8%A7%A3%E6%94%BE%E5%86%9B%E6%97%A0%E4%BA%BA%E6%9C%BA%E4%BE%A6%E5%AF%9F%22%7D&rank=&style_id=40132&topic_id=7698698383946419775", "")
    items_toutiao += HotSearchItem(11, "“完美恋人”背后的陷阱", 8207514, "", "https://www.toutiao.com/trending/7697854752536690742/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%222%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227697854752536690742%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E2%80%9C%E5%AE%8C%E7%BE%8E%E6%81%8B%E4%BA%BA%E2%80%9D%E8%83%8C%E5%90%8E%E7%9A%84%E9%99%B7%E9%98%B1%22%7D&rank=&style_id=40132&topic_id=7697854752536690742", "")
    items_toutiao += HotSearchItem(12, "美股市值前十公司盘初股价全线下跌", 7426466, "新", "https://www.toutiao.com/trending/7698236248431050795/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%222%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698236248431050795%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%BE%8E%E8%82%A1%E5%B8%82%E5%80%BC%E5%89%8D%E5%8D%81%E5%85%AC%E5%8F%B8%E7%9B%98%E5%88%9D%E8%82%A1%E4%BB%B7%E5%85%A8%E7%BA%BF%E4%B8%8B%E8%B7%8C%22%7D&rank=&style_id=40132&topic_id=7698236248431050795", "")
    items_toutiao += HotSearchItem(13, "李小冉：一忍再忍不想占用公共资源", 6719744, "", "https://www.toutiao.com/trending/7698568735229038089/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%222%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698568735229038089%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E6%9D%8E%E5%B0%8F%E5%86%89%EF%BC%9A%E4%B8%80%E5%BF%8D%E5%86%8D%E5%BF%8D%E4%B8%8D%E6%83%B3%E5%8D%A0%E7%94%A8%E5%85%AC%E5%85%B1%E8%B5%84%E6%BA%90%22%7D&rank=&style_id=40132&topic_id=7698568735229038089", "")
    items_toutiao += HotSearchItem(14, "欧盟：俄乌冲突结束前不解除对俄制裁", 6080276, "新", "https://www.toutiao.com/trending/7698535958010151434/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2215%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698535958010151434%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E6%AC%A7%E7%9B%9F%EF%BC%9A%E4%BF%84%E4%B9%8C%E5%86%B2%E7%AA%81%E7%BB%93%E6%9D%9F%E5%89%8D%E4%B8%8D%E8%A7%A3%E9%99%A4%E5%AF%B9%E4%BF%84%E5%88%B6%E8%A3%81%22%7D&rank=&style_id=40132&topic_id=7698535958010151434", "")
    items_toutiao += HotSearchItem(15, "新央企国家数据集团领导班子公布", 5501661, "新", "https://www.toutiao.com/trending/7698704770961247753/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%225%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698704770961247753%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E6%96%B0%E5%A4%AE%E4%BC%81%E5%9B%BD%E5%AE%B6%E6%95%B0%E6%8D%AE%E9%9B%86%E5%9B%A2%E9%A2%86%E5%AF%BC%E7%8F%AD%E5%AD%90%E5%85%AC%E5%B8%83%22%7D&rank=&style_id=40132&topic_id=7698704770961247753", "")
    items_toutiao += HotSearchItem(16, "中信银行为何成了“香饽饽”", 4978109, "", "https://www.toutiao.com/trending/7698684092216249892/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2213%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698684092216249892%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E4%B8%AD%E4%BF%A1%E9%93%B6%E8%A1%8C%E4%B8%BA%E4%BD%95%E6%88%90%E4%BA%86%E2%80%9C%E9%A6%99%E9%A5%BD%E9%A5%BD%E2%80%9D%22%7D&rank=&style_id=40132&topic_id=7698684092216249892", "")
    items_toutiao += HotSearchItem(17, "男子被拐22年后凭碎片记忆寻回至亲", 4504379, "新", "https://www.toutiao.com/trending/7698593130576379940/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%220%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698593130576379940%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%94%B7%E5%AD%90%E8%A2%AB%E6%8B%9022%E5%B9%B4%E5%90%8E%E5%87%AD%E7%A2%8E%E7%89%87%E8%AE%B0%E5%BF%86%E5%AF%BB%E5%9B%9E%E8%87%B3%E4%BA%B2%22%7D&rank=&style_id=40132&topic_id=7698593130576379940", "")
    items_toutiao += HotSearchItem(18, "数据领域“国家队”是干什么的", 4075731, "", "https://www.toutiao.com/trending/7698712202798173722/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%221%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698712202798173722%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E6%95%B0%E6%8D%AE%E9%A2%86%E5%9F%9F%E2%80%9C%E5%9B%BD%E5%AE%B6%E9%98%9F%E2%80%9D%E6%98%AF%E5%B9%B2%E4%BB%80%E4%B9%88%E7%9A%84%22%7D&rank=&style_id=40132&topic_id=7698712202798173722", "")
    items_toutiao += HotSearchItem(19, "美舰首次在仙宾礁挑衅有何意图", 3687874, "热", "https://www.toutiao.com/trending/7698684349570371122/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%2213%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698684349570371122%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%BE%8E%E8%88%B0%E9%A6%96%E6%AC%A1%E5%9C%A8%E4%BB%99%E5%AE%BE%E7%A4%81%E6%8C%91%E8%A1%85%E6%9C%89%E4%BD%95%E6%84%8F%E5%9B%BE%22%7D&rank=&style_id=40132&topic_id=7698684349570371122", "")
    items_toutiao += HotSearchItem(20, "张柏芝说养育3个儿子很轻松", 3336926, "新", "https://www.toutiao.com/trending/7698023372047335434/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%228%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698023372047335434%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E5%BC%A0%E6%9F%8F%E8%8A%9D%E8%AF%B4%E5%85%BB%E8%82%B23%E4%B8%AA%E5%84%BF%E5%AD%90%E5%BE%88%E8%BD%BB%E6%9D%BE%22%7D&rank=&style_id=40132&topic_id=7698023372047335434", "")
    boards["toutiao"] = HotSearchBoard("头条热榜", "2026-10-20T14:23:56.935Z", items_toutiao)

    val items_aggregate = mutableListOf<HotSearchItem>()
    items_aggregate += HotSearchItem(1, "工信部：禁止车辆行驶时用零重力座椅", 29548262, "", "https://www.baidu.com/s?wd=%E5%B7%A5%E4%BF%A1%E9%83%A8%EF%BC%9A%E7%A6%81%E6%AD%A2%E8%BD%A6%E8%BE%86%E8%A1%8C%E9%A9%B6%E6%97%B6%E7%94%A8%E9%9B%B6%E9%87%8D%E5%8A%9B%E5%BA%A7%E6%A4%85&sa=fyb_news&rsv_dl=fyb_news", "百度 · 头条")
    items_aggregate += HotSearchItem(2, "前三季度中国经济“成绩单”来了", 25882163, "", "https://www.baidu.com/s?wd=%E5%89%8D%E4%B8%89%E5%AD%A3%E5%BA%A6%E4%B8%AD%E5%9B%BD%E7%BB%8F%E6%B5%8E%E2%80%9C%E6%88%90%E7%BB%A9%E5%8D%95%E2%80%9D%E6%9D%A5%E4%BA%86&sa=fyb_news&rsv_dl=fyb_news", "百度 · 头条")
    items_aggregate += HotSearchItem(3, "“完美恋人”背后的陷阱", 15058274, "", "https://www.baidu.com/s?wd=%E2%80%9C%E5%AE%8C%E7%BE%8E%E6%81%8B%E4%BA%BA%E2%80%9D%E8%83%8C%E5%90%8E%E7%9A%84%E9%99%B7%E9%98%B1&sa=fyb_news&rsv_dl=fyb_news", "百度 · 头条")
    items_aggregate += HotSearchItem(4, "王腾说希望雷总放我们一条生路", 7482789, "", "https://s.weibo.com/weibo?q=%E7%8E%8B%E8%85%BE%E8%AF%B4%E5%B8%8C%E6%9C%9B%E9%9B%B7%E6%80%BB%E6%94%BE%E6%88%91%E4%BB%AC%E4%B8%80%E6%9D%A1%E7%94%9F%E8%B7%AF", "微博 · 百度")
    items_aggregate += HotSearchItem(5, "钟南山心梗后仍每周运动3次", 7116375, "", "https://s.weibo.com/weibo?q=%E9%92%9F%E5%8D%97%E5%B1%B1%E5%BF%83%E6%A2%97%E5%90%8E%E4%BB%8D%E6%AF%8F%E5%91%A8%E8%BF%90%E5%8A%A83%E6%AC%A1", "微博 · 百度")
    items_aggregate += HotSearchItem(6, "油车电车10年成本账怎么算", 2081980, "新", "https://s.weibo.com/weibo?q=%E6%B2%B9%E8%BD%A6%E7%94%B5%E8%BD%A610%E5%B9%B4%E6%88%90%E6%9C%AC%E8%B4%A6%E6%80%8E%E4%B9%88%E7%AE%97", "微博")
    items_aggregate += HotSearchItem(7, "王力宏rap回应在梅边舞台", 7468247, "", "https://search.bilibili.com/all?keyword=%E7%8E%8B%E5%8A%9B%E5%AE%8Frap%E5%9B%9E%E5%BA%94%E5%9C%A8%E6%A2%85%E8%BE%B9%E8%88%9E%E5%8F%B0", "B站")
    items_aggregate += HotSearchItem(8, "菲媒：若驱逐中国大使中方必对等回应", 7809298, "", "https://www.baidu.com/s?wd=%E8%8F%B2%E5%AA%92%EF%BC%9A%E8%8B%A5%E9%A9%B1%E9%80%90%E4%B8%AD%E5%9B%BD%E5%A4%A7%E4%BD%BF%E4%B8%AD%E6%96%B9%E5%BF%85%E5%AF%B9%E7%AD%89%E5%9B%9E%E5%BA%94&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(9, "俞小平被开除党籍 中国人保表态", 7711878, "", "https://www.baidu.com/s?wd=%E4%BF%9E%E5%B0%8F%E5%B9%B3%E8%A2%AB%E5%BC%80%E9%99%A4%E5%85%9A%E7%B1%8D+%E4%B8%AD%E5%9B%BD%E4%BA%BA%E4%BF%9D%E8%A1%A8%E6%80%81&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(10, "学生机直逼5000元", 7521418, "", "https://www.baidu.com/s?wd=%E5%AD%A6%E7%94%9F%E6%9C%BA%E7%9B%B4%E9%80%BC5000%E5%85%83&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(11, "中年男子骑车载白发老人看哭网友", 7428090, "", "https://www.baidu.com/s?wd=%E4%B8%AD%E5%B9%B4%E7%94%B7%E5%AD%90%E9%AA%91%E8%BD%A6%E8%BD%BD%E7%99%BD%E5%8F%91%E8%80%81%E4%BA%BA%E7%9C%8B%E5%93%AD%E7%BD%91%E5%8F%8B&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(12, "100家！最新央企名录公布", 7332220, "", "https://www.baidu.com/s?wd=100%E5%AE%B6%EF%BC%81%E6%9C%80%E6%96%B0%E5%A4%AE%E4%BC%81%E5%90%8D%E5%BD%95%E5%85%AC%E5%B8%83&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(13, "美国一战斗舰冲闯中国南沙海域", 20187228, "热", "https://www.toutiao.com/trending/7698609753362026022/?category_name=topic_innerflow&event_type=hot_board&log_pb=%7B%22category_name%22%3A%22topic_innerflow%22%2C%22cluster_type%22%3A%225%22%2C%22enter_from%22%3A%22click_category%22%2C%22entrance_hotspot%22%3A%22outside%22%2C%22event_type%22%3A%22hot_board%22%2C%22hot_board_cluster_id%22%3A%227698609753362026022%22%2C%22hot_board_impr_id%22%3A%22202610202223561BFEFD20CF4AF6CF05C4%22%2C%22jump_page%22%3A%22hot_board_page%22%2C%22location%22%3A%22news_hot_card%22%2C%22page_location%22%3A%22hot_board_page%22%2C%22source%22%3A%22trending_tab%22%2C%22style_id%22%3A%2240132%22%2C%22title%22%3A%22%E7%BE%8E%E5%9B%BD%E4%B8%80%E6%88%98%E6%96%97%E8%88%B0%E5%86%B2%E9%97%AF%E4%B8%AD%E5%9B%BD%E5%8D%97%E6%B2%99%E6%B5%B7%E5%9F%9F%22%7D&rank=&style_id=40132&topic_id=7698609753362026022", "头条")
    items_aggregate += HotSearchItem(14, "公交车闯红灯致6死16伤 调查报告发布", 7045763, "", "https://www.baidu.com/s?wd=%E5%85%AC%E4%BA%A4%E8%BD%A6%E9%97%AF%E7%BA%A2%E7%81%AF%E8%87%B46%E6%AD%BB16%E4%BC%A4+%E8%B0%83%E6%9F%A5%E6%8A%A5%E5%91%8A%E5%8F%91%E5%B8%83&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(15, "岳云鹏专场遇音响事故 观众喊退票", 6950841, "", "https://www.baidu.com/s?wd=%E5%B2%B3%E4%BA%91%E9%B9%8F%E4%B8%93%E5%9C%BA%E9%81%87%E9%9F%B3%E5%93%8D%E4%BA%8B%E6%95%85+%E8%A7%82%E4%BC%97%E5%96%8A%E9%80%80%E7%A5%A8&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(16, "宠物博主感染罕见真菌病逝 年仅27岁", 6666098, "", "https://www.baidu.com/s?wd=%E5%AE%A0%E7%89%A9%E5%8D%9A%E4%B8%BB%E6%84%9F%E6%9F%93%E7%BD%95%E8%A7%81%E7%9C%9F%E8%8F%8C%E7%97%85%E9%80%9D+%E5%B9%B4%E4%BB%8527%E5%B2%81&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(17, "米粉店老板靠一句“泥嚎”意外走红", 6566372, "", "https://www.baidu.com/s?wd=%E7%B1%B3%E7%B2%89%E5%BA%97%E8%80%81%E6%9D%BF%E9%9D%A0%E4%B8%80%E5%8F%A5%E2%80%9C%E6%B3%A5%E5%9A%8E%E2%80%9D%E6%84%8F%E5%A4%96%E8%B5%B0%E7%BA%A2&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(18, "瓶装饮料集体涨价", 6473462, "", "https://www.baidu.com/s?wd=%E7%93%B6%E8%A3%85%E9%A5%AE%E6%96%99%E9%9B%86%E4%BD%93%E6%B6%A8%E4%BB%B7&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(19, "大同下雪了", 6381215, "", "https://www.baidu.com/s?wd=%E5%A4%A7%E5%90%8C%E4%B8%8B%E9%9B%AA%E4%BA%86&sa=fyb_news&rsv_dl=fyb_news", "百度")
    items_aggregate += HotSearchItem(20, "杨小伟一审被判12年", 6271055, "", "https://www.baidu.com/s?wd=%E6%9D%A8%E5%B0%8F%E4%BC%9F%E4%B8%80%E5%AE%A1%E8%A2%AB%E5%88%A412%E5%B9%B4&sa=fyb_news&rsv_dl=fyb_news", "百度")
    boards["aggregate"] = HotSearchBoard("今日聚合总榜", "2026-10-20T14:23:56.935Z", items_aggregate)

    return HotSearchData(boards)
}
