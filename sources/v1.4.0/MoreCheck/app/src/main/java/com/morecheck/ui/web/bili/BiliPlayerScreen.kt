package com.morecheck.ui.web.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.morecheck.ui.common.WebPageNavBarIcons
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Space
import com.morecheck.ui.web.NoticeThrottle
import com.morecheck.ui.web.WebErrorPane
import com.morecheck.ui.web.WebLinkDecision
import com.morecheck.ui.web.WebLinkPolicy
import com.morecheck.ui.web.WebViewHolder
import com.morecheck.util.openInBrowser
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * B 站视频播放页（2026-11-03 原生改造；同日修「只有图片墙没有播放器」+ 增强横屏/弹幕/登录）。
 *
 * ## 一、为什么不再直接 loadUrl 视频页（定位结论）
 *
 * 直接打开 `https://m.bilibili.com/video/{bvid}?p=N` 时，服务端返回的 HTML **完全正常**
 * （宿主机抓包核对过 `__INITIAL_STATE__`、`m-video` 结构、4 个 JS bundle / 3 个 CSS 与
 * 「从首页点进去」拿到的是同一份；桌面 Chromium 移动视口直接打开也能正常出播放器），
 * 但 Android WebView（Chromium 124）里只出来几块封面图——说明是「直达视频页」这条
 * 客户端启动路径（SSR 水合分支）的问题，不是接口/Referer/防盗链。
 *
 * 旧版验证可用的路径是「先打开 m 站首页 → 页内路由点进视频」，所以本页：
 * 1. 先 `loadUrl(HOME_URL)`；2. 首页完成后注入 `#app.__vue__.$router.push('/video/…')`；
 * 3. 跳转后做健康检查；4. 不健康则切桌面播放器页兜底；仍失败给原生错误页 + 重试。
 *
 * ## 二、播放状态机（需求「播放页是否真起播」）
 *
 * [BiliPlaybackStatus] 三态：
 * - [BiliPlaybackStatus.LOADING]：正在打开首页/跳转/等播放器初始化（盖原生加载层）；
 * - [BiliPlaybackStatus.READY]：健康检查通过（目标视频页 + `<video>` 元素 + 播放器容器
 *   有高度 + 文本量正常）——这是「不是空壳」的机器可验证判据；
 * - [BiliPlaybackStatus.FAILED]：移动版 + 桌面版两条路都没出播放器 → 原生错误页 + 重试。
 * 健康检查原始数据会打到 logcat（tag `BiliPlayerHealth`），便于下次直接定位。
 *
 * ## 三、横屏/全屏（需求「横屏观看」）
 *
 * 两个入口都实现：
 * 1. **网页内全屏按钮**：补齐 [WebChromeClient.onShowCustomView]/[onHideCustomView]，
 *    把 B 站播放器交出的 customView 放进 `decorView` 之上的一层 FrameLayout（转 90°）；
 * 2. **原生全屏按钮**（顶栏）：隐藏系统栏 + 原生顶栏，把 WebView 容器旋转 90° 铺满。
 *
 * ⚠️ 为什么**不用** `requestedOrientation = SENSOR_LANDSCAPE`：MainActivity 的 manifest
 * 没有声明 `configChanges`，改屏幕方向会让 Activity 重建 → Compose/WebView 一起销毁，
 * 播放器必然重来（本需求明确要求「旋转不重建播放器」）。而 manifest 本轮只允许加
 * FileProvider。所以改用「**旋转渲染容器、不改窗口方向**」：WebView 不重建、播放不断，
 * 用户把手机横过来看就是正常横屏画面；退出全屏恢复正向。
 * 代价（如实记录）：系统栏方向仍按竖屏，已隐藏；部分 ROM 截图/录屏可能仍是竖屏尺寸。
 * 若将来允许给 MainActivity 加 `orientation|screenSize` configChanges，应改回标准
 * `SENSOR_LANDSCAPE` 方案。
 *
 * ## 四、弹幕开关（需求「弹幕开/关 + 持久化」）
 *
 * 实测 m 站播放器是 `gsl-*` 框架：`window.player` 提供 play/pause/seek/getCurrentTime/
 * isFullScreen 等，但**没有公开的弹幕开关 API**（弹幕状态在 `store.state.dm` 私有）；
 * 唯一的 UI 开关是 `.gsl-dm-btn`，点击只改图标类、无法可靠读回状态。
 * 所以用「注入 CSS 隐藏弹幕层」实现关闭：`.gsl-dm / .mplayer-danmaku / .bpx-player-dm-wrap`
 * 等选择器覆盖多套播放器；关闭偏好持久化在 [BiliPlaybackPrefs]，进入/换页后重放。
 *
 * ## 五、登录（2026-11-03 用户同意）
 *
 * 登录页（[BiliLoginScreen]）把 Cookie 存进 [BilibiliRepository]；本页创建 WebView 时
 * 用 [injectBiliCookies] 回注到 CookieManager，网页播放器也处于登录态（可看 1080P）。
 *
 * ## 六、已修的两个 WebView 坑（v1.2.5 起，逐字保留）
 *
 * 1. **UA 不能带 `; wv`**；2. **`mediaPlaybackRequiresUserGesture = false`**；
 * 3. **`onRenderProcessGone` 必须返回 true**（否则宿主 Activity 会被一起杀掉）。
 *
 * @param bvid 入口视频 BV 号。
 * @param cid 入口视频当前分 P 的 cid。
 * @param pageIndex 分 P 序号（从 1 起）。
 * @param durationSeconds 当前分 P 时长（秒），用于清晰度弹层估算体积，0=未知。
 * @param initialTitle 详情页带来的标题。
 * @param onBack 返回详情页。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliPlayerScreen(
    bvid: String,
    cid: Long,
    pageIndex: Int,
    durationSeconds: Int,
    initialTitle: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 网页恒定白底：导航栏图标强制深色（需求 3）。
    WebPageNavBarIcons()
    val messageSink = rememberUpdatedState(onMessage)
    val activity = remember(context) { context.findActivity() }

    // 进程内恢复登录 Cookie（幂等，非 suspend）。
    BilibiliRepository.restoreSession(context)

    val mobileVideoUrl = "https://m.bilibili.com/video/$bvid?p=$pageIndex"
    val desktopVideoUrl = "https://www.bilibili.com/video/$bvid?p=$pageIndex"

    val holder = remember { WebViewHolder() }
    // 网页全屏（customView）宿主：挂到 decorView 上，与 Compose 无关，退出时移除。
    val customViewHost = remember(activity) { activity?.let { CustomViewHost(it) } }

    // 当前地址默认是首页：播放页从「打开 m 站首页」开始（原因见类注释一）。
    var currentUrl by rememberSaveable { mutableStateOf(HOME_URL) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    /** 渲染进程崩溃后的重建世代号。 */
    var webViewEpoch by remember { mutableIntStateOf(0) }

    // ---- 进入流程：0=首页中；1=移动版视频页；2=桌面版兜底；3=两条路都没出播放器 ----
    var phase by rememberSaveable { mutableIntStateOf(PHASE_HOME) }
    var homeNavigateInjected by remember { mutableStateOf(false) }
    var mobileHealthScheduled by remember { mutableStateOf(false) }
    var desktopHealthScheduled by remember { mutableStateOf(false) }

    // ---- 播放状态机（需求：加载中 / 可播放 / 失败）----
    var playbackStatus by remember { mutableStateOf(BiliPlaybackStatus.LOADING) }

    // ---- 全屏 / 横屏 ----
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var customViewCallback by remember {
        mutableStateOf<WebChromeClient.CustomViewCallback?>(null)
    }
    var customViewActive by remember { mutableStateOf(false) }

    // ---- 弹幕开关（持久化）----
    var danmakuEnabled by remember { mutableStateOf(BiliPlaybackPrefs.isDanmakuEnabled(context)) }

    // ---- 下载相关状态 ----
    var qualityState by remember { mutableStateOf<BiliQualityUiState?>(null) }
    var qualityRequestId by remember { mutableIntStateOf(0) }
    var downloadStage by remember { mutableStateOf<String?>(null) }
    var downloadPercent by remember { mutableStateOf<Int?>(null) }
    var targetCid by remember { mutableStateOf(cid) }
    var targetPageIndex by remember { mutableIntStateOf(pageIndex) }

    /** 从当前网页地址里读分 P（B 站 H5 切 P 会改 URL 的 `p=` 参数）。 */
    fun currentPageFromUrl(): Int =
        PAGE_PARAM_REGEX.find(currentUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceAtLeast(1) ?: pageIndex

    /** 把「弹幕开/关」应用到当前网页（注入/移除隐藏样式，见类注释四）。 */
    fun applyDanmaku(web: WebView) {
        web.evaluateJavascript(buildDanmakuScript(danmakuEnabled)) { }
    }

    /** 退出网页全屏（customView）：移除宿主、回调页面、恢复系统栏。 */
    fun exitCustomViewFullscreen() {
        customViewHost?.hide()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customViewActive = false
        fullscreen = false
        setSystemBarsHidden(activity, hidden = false)
    }

    /**
     * 跑一次播放器健康检查（移动版 / 桌面版共用同一段 JS，判定标准不同）。
     *
     * @param attempt 本阶段的第几次检查；水合慢时短间隔重试，见
     *   [HEALTH_CHECK_MAX_ATTEMPTS] 的说明。
     */
    fun runHealthCheck(web: WebView, expectDesktop: Boolean, attempt: Int = 0) {
        web.evaluateJavascript(PLAYER_HEALTH_SCRIPT) { raw ->
            val health = parsePlayerHealth(raw)
            android.util.Log.w(
                "BiliPlayerHealth",
                "phase=$phase desktop=$expectDesktop attempt=$attempt raw=$raw parsed=${health?.toLogString()}"
            )
            val healthy = health != null && if (expectDesktop) {
                isDesktopPageHealthy(health, bvid)
            } else {
                isMobilePageHealthy(health, bvid)
            }
            if (healthy) {
                playbackStatus = BiliPlaybackStatus.READY
                phase = if (expectDesktop) PHASE_DESKTOP_VIDEO else PHASE_MOBILE_VIDEO
                applyDanmaku(web)
                return@evaluateJavascript
            }
            // 还在本阶段就再给页面一点时间；用户已经重试 / 页面已切走时不再排队，
            // 免得旧回调把新一次播放流程的状态改坏。
            val stillInPhase = if (expectDesktop) {
                phase == PHASE_DESKTOP_VIDEO
            } else {
                phase == PHASE_MOBILE_VIDEO
            }
            if (attempt + 1 < HEALTH_CHECK_MAX_ATTEMPTS && stillInPhase) {
                web.postDelayed(
                    { runHealthCheck(web, expectDesktop, attempt + 1) },
                    HEALTH_CHECK_RETRY_MS
                )
                return@evaluateJavascript
            }
            if (expectDesktop) {
                playbackStatus = BiliPlaybackStatus.FAILED
                phase = PHASE_FAILED
            } else {
                // 移动版没出播放器 → 换桌面版兜底（两套渲染路径，见类注释一）。
                phase = PHASE_DESKTOP_VIDEO
                playbackStatus = BiliPlaybackStatus.LOADING
                desktopHealthScheduled = false
                web.settings.userAgentString = DESKTOP_UA
                web.loadUrl(desktopVideoUrl)
            }
        }
    }

    /** 探测清晰度并打开底部弹层。 */
    fun probeQualities() {
        val requestId = qualityRequestId + 1
        qualityRequestId = requestId
        qualityState = BiliQualityUiState.Loading
        scope.launch {
            val page = currentPageFromUrl()
            val resolvedCid = try {
                if (page == pageIndex && cid > 0L) {
                    cid
                } else {
                    val detail = BilibiliRepository.fetchDetail(bvid)
                    detail.pages.firstOrNull { it.index == page }?.cid
                        ?: throw BilibiliRepository.BilibiliException(
                            "没有找到 P$page 的视频信息，请返回详情页重新进入"
                        )
                }
            } catch (e: Exception) {
                if (requestId == qualityRequestId) {
                    qualityState = BiliQualityUiState.Failed(
                        e.message ?: "无法确定当前分 P 的视频信息"
                    )
                }
                return@launch
            }
            targetCid = resolvedCid
            targetPageIndex = page
            val next = try {
                val qualities = BilibiliRepository.fetchQualities(bvid, resolvedCid)
                BiliQualityUiState.Ready(qualities)
            } catch (e: Exception) {
                BiliQualityUiState.Failed(e.message ?: "查询清晰度失败")
            }
            if (requestId == qualityRequestId) qualityState = next
        }
    }

    /** 用户选定清晰度：重新解析直链 → 开始下载。 */
    fun startDownload(quality: BilibiliRepository.BiliQuality) {
        qualityState = null
        qualityRequestId += 1
        scope.launch {
            val title = initialTitle.ifBlank { pageTitle }.ifBlank { bvid }
            downloadStage = "解析下载地址"
            downloadPercent = null
            try {
                val plan = BilibiliRepository.resolveStreamPlan(
                    bvid = bvid,
                    cid = targetCid,
                    title = title,
                    pageIndex = targetPageIndex,
                    quality = quality
                )
                val result = BiliDownloader.download(context, plan) { stage, percent ->
                    downloadStage = stage
                    downloadPercent = percent
                }
                messageSink.value(result.message)
            } catch (e: Exception) {
                messageSink.value(e.message ?: "下载失败")
            } finally {
                downloadStage = null
                downloadPercent = null
            }
        }
    }

    /** 重试整个进入流程（失败页的「重试」按钮）。 */
    fun restartPlaybackFlow() {
        val web = holder.view
        exitCustomViewFullscreen()
        runCatching { web?.settings?.userAgentString = BILI_BROWSER_UA }
        phase = PHASE_HOME
        playbackStatus = BiliPlaybackStatus.LOADING
        homeNavigateInjected = false
        mobileHealthScheduled = false
        desktopHealthScheduled = false
        errorMessage = null
        currentUrl = HOME_URL
        pageTitle = ""
        web?.loadUrl(HOME_URL)
    }

    /** 原生全屏开/关（不改窗口方向，只旋转渲染容器；理由见类注释三）。 */
    fun toggleNativeFullscreen() {
        if (customViewActive) {
            exitCustomViewFullscreen()
            return
        }
        fullscreen = !fullscreen
        setSystemBarsHidden(activity, hidden = fullscreen)
    }

    val onEntryVideo = currentUrl.contains(bvid, ignoreCase = true)

    // ---- WebView 回调 ----
    val webViewClient = remember {
        object : WebViewClient() {
            // B站 H5 会不断尝试 intent:// / bilibili:// 唤起客户端，也会加载大量
            // about:blank / data: 子资源。策略与「官网」共用，避免提示刷屏。
            private val noticeThrottle = NoticeThrottle()
            private var redirectUpgradeDone = false

            private fun notice(text: String) {
                if (noticeThrottle.shouldShow(text)) messageSink.value(text)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true
                val isMainFrame = request.isForMainFrame
                val scheme = uri.scheme?.lowercase()
                return when (WebLinkPolicy.decide(scheme, isMainFrame)) {
                    WebLinkDecision.ALLOW -> false
                    WebLinkDecision.UPGRADE_HTTPS -> {
                        val upgraded = WebLinkPolicy.toHttps(uri.toString())
                        if (upgraded != null) view?.loadUrl(upgraded)
                        else notice("出于安全考虑，仅允许访问 https 网页")
                        true
                    }
                    // 唤起 B站客户端的自定义协议与网页内部伪协议：静默拦截，不弹提示。
                    WebLinkDecision.SILENT_BLOCK -> true
                    WebLinkDecision.BLOCK_WITH_NOTICE -> {
                        notice("无法打开该链接：仅支持 https 网页")
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null && url.startsWith("http://", ignoreCase = true)) {
                    view?.stopLoading()
                    val upgraded = WebLinkPolicy.toHttps(url)
                    if (upgraded != null && !redirectUpgradeDone) {
                        redirectUpgradeDone = true
                        view?.loadUrl(upgraded)
                    } else {
                        notice("出于安全考虑，仅允许访问 https 网页")
                        errorMessage = "该地址是 http 明文链接，已被拦截"
                    }
                    return
                }
                redirectUpgradeDone = false
                if (url != null) currentUrl = url
                errorMessage = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val realView = view ?: return
                val realUrl = url ?: return
                // 第 1 步：首页加载完成 → 触发它自己的 SPA 路由跳转（旧版可用路径）。
                if (!homeNavigateInjected &&
                    realUrl.startsWith("https://m.bilibili.com") &&
                    !realUrl.contains("/video/")
                ) {
                    homeNavigateInjected = true
                    realView.evaluateJavascript(
                        buildSpaNavigateScript(
                            path = "/video/$bvid?p=$pageIndex",
                            fallbackUrl = mobileVideoUrl
                        )
                    ) { result ->
                        android.util.Log.w("BiliPlayerHealth", "spaNavigate -> $result")
                    }
                    // 兜底超时：注入路由后仍停在首页 → 错误页，避免一直转圈。
                    realView.postDelayed(
                        {
                            if (phase == PHASE_HOME &&
                                playbackStatus == BiliPlaybackStatus.LOADING
                            ) {
                                phase = PHASE_FAILED
                                playbackStatus = BiliPlaybackStatus.FAILED
                            }
                        },
                        HOME_NAVIGATE_TIMEOUT_MS
                    )
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView?,
                url: String?,
                isReload: Boolean
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) currentUrl = url
                val realView = view ?: return
                if (url != null) {
                    val onMobileVideo = url.startsWith("https://m.bilibili.com") &&
                        url.contains("/video/$bvid")
                    val onDesktopVideo = url.startsWith("https://www.bilibili.com") &&
                        url.contains("/video/$bvid")
                    if (onMobileVideo && !mobileHealthScheduled) {
                        mobileHealthScheduled = true
                        phase = PHASE_MOBILE_VIDEO
                        errorMessage = null
                        realView.postDelayed(
                            { runHealthCheck(realView, expectDesktop = false) },
                            MOBILE_HEALTH_CHECK_DELAY_MS
                        )
                    }
                    if (onDesktopVideo && !desktopHealthScheduled) {
                        desktopHealthScheduled = true
                        realView.postDelayed(
                            { runHealthCheck(realView, expectDesktop = true) },
                            DESKTOP_HEALTH_CHECK_DELAY_MS
                        )
                    }
                    // 页内跳转/点击后播放器可能重挂，重新应用一次弹幕偏好。
                    if (onMobileVideo || onDesktopVideo) applyDanmaku(realView)
                }
                // SPA 路由变化不触发 onReceivedTitle，主动读一次 document.title。
                realView.evaluateJavascript("document.title") { value ->
                    val title = runCatching {
                        JSONObject("""{"t":$value}""").optString("t")
                    }.getOrElse { value.trim('"') }
                    if (title.isNotBlank()) pageTitle = title
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                errorMessage = error?.description?.toString()?.ifBlank { "无法加载该网页" }
                messageSink.value("网页加载失败")
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                holder.destroy()
                exitCustomViewFullscreen()
                currentUrl = HOME_URL
                pageTitle = ""
                phase = PHASE_HOME
                playbackStatus = BiliPlaybackStatus.LOADING
                homeNavigateInjected = false
                mobileHealthScheduled = false
                desktopHealthScheduled = false
                webViewEpoch++
                messageSink.value("页面渲染异常，已重新加载")
                return true
            }
        }
    }

    val webChromeClient = remember {
        object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) pageTitle = title
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }

            /**
             * B 站 H5 播放器点「全屏」时把播放视图交给 WebView：
             * 标准做法是接管到 decorView 上层；这里额外把它旋转 90° 获得横屏画面，
             * **不改变 Activity 方向**，所以播放器不会被销毁重建（类注释三）。
             */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val host = customViewHost
                if (host == null) {
                    // 拿不到 Activity（理论上不会发生）：交还页面，不装死。
                    callback?.onCustomViewHidden()
                    return
                }
                customViewCallback = callback
                customViewActive = true
                fullscreen = true
                setSystemBarsHidden(activity, hidden = true)
                host.show(view) { exitCustomViewFullscreen() }
            }

            override fun onHideCustomView() {
                exitCustomViewFullscreen()
            }
        }
    }

    /** 返回优先级：网页全屏 > 原生全屏 > 网页历史 > 退出本页。 */
    fun navigateBack() {
        if (customViewActive) {
            exitCustomViewFullscreen()
            return
        }
        if (fullscreen) {
            toggleNativeFullscreen()
            return
        }
        val web = holder.view
        val onEntryVideoPage = currentUrl.contains("/video/$bvid")
        if (onEntryVideoPage) {
            onBack()
        } else if (web != null && web.canGoBack()) {
            web.goBack()
        } else {
            onBack()
        }
    }

    BackHandler { navigateBack() }

    DisposableEffect(Unit) {
        onDispose {
            customViewHost?.hide()
            setSystemBarsHidden(activity, hidden = false)
            holder.destroy()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 全屏时把原生顶栏一起藏掉（需求：全屏隐藏系统栏/原生顶栏）。
            if (!fullscreen) {
                Column {
                    TopAppBar(
                        title = {
                            Text(
                                text = pageTitle.ifBlank { initialTitle.ifBlank { "B站视频" } },
                                style = AppType.heading,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { navigateBack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        },
                        actions = {
                            // 弹幕开关（持久化；实现方式见类注释四）。
                            IconButton(onClick = {
                                danmakuEnabled = !danmakuEnabled
                                BiliPlaybackPrefs.setDanmakuEnabled(context, danmakuEnabled)
                                holder.view?.let { applyDanmaku(it) }
                                messageSink.value(
                                    if (danmakuEnabled) "弹幕已开启" else "弹幕已关闭"
                                )
                            }) {
                                Text(
                                    text = "弹",
                                    style = AppType.bodySmall,
                                    color = if (danmakuEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (onEntryVideo) {
                                IconButton(onClick = { probeQualities() }) {
                                    Icon(Icons.Filled.Download, contentDescription = "下载视频")
                                }
                            }
                            // 原生全屏按钮（网页内全屏按钮也支持，双入口）。
                            IconButton(onClick = { toggleNativeFullscreen() }) {
                                Icon(
                                    imageVector = Icons.Filled.Fullscreen,
                                    contentDescription = "全屏横屏观看"
                                )
                            }
                            IconButton(onClick = { holder.view?.reload() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "刷新页面")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(if (fullscreen) Modifier else Modifier.padding(innerPadding))
        ) {
            // 全屏时把「渲染容器」旋转 90° 并交换宽高：同一个 WebView 实例不重建，
            // 页面会按横屏视口重新布局（H5 播放器随之变成横屏画面）。
            val targetWidth = if (fullscreen) maxHeight else maxWidth
            val targetHeight = if (fullscreen) maxWidth else maxHeight
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .requiredSize(targetWidth, targetHeight)
                    .graphicsLayer { rotationZ = if (fullscreen) 90f else 0f }
            ) {
                key(webViewEpoch) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.userAgentString = BILI_BROWSER_UA
                                // 登录态回注（用户同意方案）：播放器也走登录态。
                                injectBiliCookies(this, BilibiliRepository.currentSessionCookie())

                                setWebViewClient(webViewClient)
                                setWebChromeClient(webChromeClient)

                                loadUrl(HOME_URL)
                                holder.view = this
                            }
                        },
                        update = { },
                        onRelease = { holder.destroy() }
                    )
                }
            }

            // 加载中遮罩（状态机 LOADING）：遮住「首页 → 视频页」两跳，避免首页闪一下。
            if (playbackStatus == BiliPlaybackStatus.LOADING) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.height(Space.md))
                        Text(
                            text = "正在进入播放页…",
                            style = AppType.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = "首次进入需要先打开 B 站首页以取得播放环境",
                            style = AppType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (playbackStatus == BiliPlaybackStatus.FAILED) {
                WebErrorPane(
                    message = "B 站网页播放器在当前 WebView 环境里没有正常显示。" +
                        "可以点「重试」重新进入；或直接交给 B 站客户端 / 网页播放，" +
                        "那条链路不依赖本应用的内嵌渲染。右上角下载入口仍可正常使用。",
                    onRetry = { restartPlaybackFlow() },
                    // 播放链路调研结论：B站 m 站是 SPA，WebView 水合失败时重试也未必好，
                    // 给用户一条不依赖内嵌渲染的确定性出路（系统浏览器会优先唤起 B站 App）。
                    // 走 openInBrowser 而不是硬编码 ACTION_VIEW，才能顺带遵守
                    // 开发者选项里「链接在应用内 / 系统浏览器打开」的全局开关。
                    extraAction = "用 B 站客户端 / 网页打开" to {
                        openInBrowser(
                            context = context,
                            url = "https://www.bilibili.com/video/$bvid?p=$pageIndex",
                            onMessage = onMessage
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            errorMessage?.let { message ->
                WebErrorPane(
                    message = message,
                    onRetry = {
                        errorMessage = null
                        holder.view?.loadUrl(currentUrl)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            val stage = downloadStage
            if (stage != null) {
                DownloadStatusBar(
                    stage = stage,
                    percent = downloadPercent,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            // 原生全屏时的退出按钮（网页 customView 会盖住 Compose 层，那条路靠返回键退出）。
            if (fullscreen && !customViewActive) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.md)
                ) {
                    IconButton(onClick = { toggleNativeFullscreen() }) {
                        Icon(
                            imageVector = Icons.Filled.FullscreenExit,
                            contentDescription = "退出全屏"
                        )
                    }
                }
            }
        }
    }

    qualityState?.let { state ->
        BiliQualityDialog(
            state = state,
            durationSeconds = durationSeconds,
            onSelect = { quality -> startDownload(quality) },
            onRetry = { probeQualities() },
            onDismiss = {
                qualityState = null
                qualityRequestId += 1
            }
        )
    }
}

/** 播放页底部的下载进度条（不常驻，只在下载期间出现）。 */
@Composable
private fun DownloadStatusBar(
    stage: String,
    percent: Int?,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stage,
                    style = AppType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = percent?.let { "$it%" } ?: "",
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Space.xs))
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { (percent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                text = "下载完成后在相册「Movies/MoreCheck」查看",
                style = AppType.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 进入流程常量 / 播放状态机 / 健康检查工具
// ---------------------------------------------------------------------------

/** 播放状态机三态（需求：加载中 / 可播放 / 失败）。 */
private enum class BiliPlaybackStatus {
    /** 正在打开首页、跳转视频页或等播放器初始化。 */
    LOADING,

    /** 健康检查通过：目标视频页 + 播放器容器有高度 → 可以看。 */
    READY,

    /** 移动版与桌面版都没出播放器 → 原生错误页 + 重试。 */
    FAILED
}

/** 进入流程的阶段（数值越小越"早"，健康检查里用它判断结果是否过期）。 */
private const val PHASE_HOME = 0
private const val PHASE_MOBILE_VIDEO = 1
private const val PHASE_DESKTOP_VIDEO = 2
private const val PHASE_FAILED = 3

/** 注入路由后最长等待离开首页的时间（超时说明注入没生效，走错误页 + 重试）。 */
private const val HOME_NAVIGATE_TIMEOUT_MS = 45_000L

/** m 站首页：进入播放页前必须先打开它（见类注释一）。 */
private const val HOME_URL = "https://m.bilibili.com/"

/** SPA 路由跳转后等多久再检查播放器是否出来。 */
private const val MOBILE_HEALTH_CHECK_DELAY_MS = 7_000L

/** 桌面版兜底页等多久（桌面播放器初始化更重）。 */
private const val DESKTOP_HEALTH_CHECK_DELAY_MS = 8_000L

/**
 * 健康检查最多跑几次。
 *
 * B站移动页是 SPA，水合完成时间受设备性能与网络波动影响很大：固定 7 秒后
 * 判一次，水合慢一点就会被误判成"空壳"并立刻切到更重的桌面页，反而更难起播。
 * 改成短间隔轮询，只要任意一次通过就 READY，全部失败才走原来的兜底/错误页。
 */
private const val HEALTH_CHECK_MAX_ATTEMPTS = 3

/** 相邻两次健康检查的间隔。 */
private const val HEALTH_CHECK_RETRY_MS = 1_500L

/** 判定页面「有内容」的最小文本量：只有图片墙、空壳时这个值会很小。 */
private const val HEALTH_MIN_TEXT_LENGTH = 150

/** 移动版播放器容器的最小高度（px）：布局塌掉时这里会是 0。 */
private const val HEALTH_MIN_PLAYER_HEIGHT_PX = 60

/**
 * 移动版视频页的"文档高 / 视口高"下限（见 [isMobilePageHealthy]）。
 *
 * 1.3 而不是 1.0：给首屏之外至少留出三成内容，避免"只多一行页脚"的页面被当成正常。
 */
private const val HEALTH_MIN_DOC_WIN_RATIO = 1.3f

/**
 * 从网页地址里读分 P 序号（`?p=2` / `&p=2`）。
 * B 站 H5 播放页切 P 时只改 URL 的 `p=` 参数，不会重新加载整页。
 */
private val PAGE_PARAM_REGEX = Regex("[?&]p=(\\d+)")

/**
 * 播放器健康检查脚本：把「页面是不是空壳」变成一组可判定的数字。
 *
 * 返回字段：`path`（是否停在目标视频页）、`videos`（`<video>` 数量）、
 * `text`（body 文本量）、`playerH`（播放器容器高度）、`wplayer`（播放器 SDK 就绪）。
 *
 * 判据来自本次「图片墙没有播放器」的故障定位：URL 对了、页面加载成功了，
 * 但播放器区域是空的——只看加载回调会被骗过去。实测 m 站播放器是 `gsl-*` 框架
 * （`.gsl-wrap/.gsl-area`），桌面版有 `#bilibili-player`。
 */
private const val PLAYER_HEALTH_SCRIPT =
    "(function(){try{" +
        "var p=document.querySelector('.m-video-player,.gsl-wrap,.mplayer,#bilibili-player,.bpx-player-container');" +
        "var r=p?p.getBoundingClientRect():null;" +
        "return JSON.stringify({" +
        "path:location.pathname," +
        "videos:document.querySelectorAll('video').length," +
        "text:document.body?document.body.innerText.length:0," +
        "playerH:r?Math.round(r.height):0," +
        // 2026-11-11：补上"文档高 / 视口高"。m 站给访客的页面会**只渲染一半**
        // （播放器 + 半行相关推荐，其余 DOM 根本没生成），此时 player/video/text
        // 三个旧指标全部达标，旧判定会误认为"页面正常"。见 isMobilePageHealthy。
        "docH:Math.round(Math.max(document.documentElement.scrollHeight," +
        "document.body?document.body.scrollHeight:0))," +
        "winH:Math.round(window.innerHeight||0)," +
        "wplayer:!!(window.WPlayer||window.player)||!!document.querySelector('.gsl-wrap')" +
        "});}catch(e){return JSON.stringify({error:String(e)});}})()"

/** 健康检查解析结果。 */
private class PlayerHealth(
    val path: String,
    val videos: Int,
    val textLength: Int,
    val playerHeightPx: Int,
    val documentHeightPx: Int,
    val windowHeightPx: Int,
    val hasPlayerSdk: Boolean
) {
    fun toLogString(): String =
        "path=$path videos=$videos text=$textLength playerH=$playerHeightPx " +
            "docH=$documentHeightPx winH=$windowHeightPx sdk=$hasPlayerSdk"
}

/** evaluateJavascript 返回的是 JSON 字符串字面量，先解码再解析。 */
private fun parsePlayerHealth(raw: String): PlayerHealth? {
    val decoded = runCatching {
        JSONObject("""{"v":$raw}""").optString("v")
    }.getOrNull() ?: return null
    return runCatching {
        val json = JSONObject(decoded)
        PlayerHealth(
            path = json.optString("path"),
            videos = json.optInt("videos", 0),
            textLength = json.optInt("text", 0),
            playerHeightPx = json.optInt("playerH", 0),
            documentHeightPx = json.optInt("docH", 0),
            windowHeightPx = json.optInt("winH", 0),
            hasPlayerSdk = json.optBoolean("wplayer", false)
        )
    }.getOrNull()
}

/**
 * 移动版视频页的可用标准（播放器容器 + 视频元素 + 文本量 + **页面高度**）。
 *
 * ## 为什么要加最后那条（2026-11-11 修「B 站网页无法在 app 内正常显示」）
 *
 * B 站给"未安装 App 的移动浏览器"返回的是**阉割版** H5：播放器照常渲染、视频也能播，
 * 但简介 / 评论 / UP 主那一大段 DOM 根本没生成，页面高度只有视口的一半左右，
 * 屏幕下半截就是一片空白（模拟器实测 `docH=379 / winH=788`）。
 * 旧判定只看 player / video / 文本量，这三个指标全都达标，于是被判成"正常"，
 * 桌面兜底永远不会触发 —— 用户看到的就是"网页显示不正常"。
 *
 * 真正可用的视频页一定比视口高（要往下滚才有简介和评论），所以这里要求
 * `docH` 至少是视口高度的 [HEALTH_MIN_DOC_WIN_RATIO] 倍。
 */
private fun isMobilePageHealthy(health: PlayerHealth, bvid: String): Boolean =
    health.path.contains(bvid) &&
        health.videos >= 1 &&
        health.textLength >= HEALTH_MIN_TEXT_LENGTH &&
        health.playerHeightPx >= HEALTH_MIN_PLAYER_HEIGHT_PX &&
        (
            health.windowHeightPx <= 0 ||
                health.documentHeightPx >= (health.windowHeightPx * HEALTH_MIN_DOC_WIN_RATIO).toInt()
            )

/** 桌面版兜底页的可用标准（容器选择器不同，只要求视频元素 + 文本量）。 */
private fun isDesktopPageHealthy(health: PlayerHealth, bvid: String): Boolean =
    health.path.contains(bvid) &&
        health.videos >= 1 &&
        health.textLength >= HEALTH_MIN_TEXT_LENGTH

/**
 * 构造「触发 B 站首页自己的 SPA 路由跳转」的脚本。
 *
 * 优先 `$router.push`（与用户从首页点进视频是同一条客户端路径），
 * 拿不到 Vue 根实例才退回 `location.href`。
 */
private fun buildSpaNavigateScript(path: String, fallbackUrl: String): String =
    "(function(){try{" +
        "var app=document.querySelector('#app');var vm=app&&app.__vue__;" +
        "if(vm&&vm.\$router&&vm.\$router.push){vm.\$router.push('$path');return 'spa';}" +
        "}catch(e){}" +
        "location.href='$fallbackUrl';return 'href';})()"

/**
 * 弹幕开关脚本（见类注释四）。
 *
 * 关：注入 `<style id="cim-dm-style">` 隐藏各版本播放器的弹幕层；
 * 开：移除该 style。只改展示，不影响发送弹幕/加载逻辑，页面刷新后由本页重放。
 */
private fun buildDanmakuScript(enabled: Boolean): String =
    if (enabled) {
        "(function(){var s=document.getElementById('cim-dm-style');" +
            "if(s&&s.parentNode)s.parentNode.removeChild(s);return 'dm-on';})()"
    } else {
        "(function(){if(document.getElementById('cim-dm-style'))return 'dm-off';" +
            "var s=document.createElement('style');s.id='cim-dm-style';" +
            "s.textContent='.gsl-dm,.gsl-dm-text,.mplayer-danmaku,.bpx-player-dm-wrap," +
            ".bpx-player-row-dm-wrap,[class*=\"danmaku-layer\"]{display:none!important}';" +
            "document.head.appendChild(s);return 'dm-off';})()"
    }

/** 播放页 WebView 的 UA：移动 Chrome、**不带** `; wv`。 */
private const val BILI_BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

/** 桌面版兜底用的 UA（移动 UA 访问 www 会被 302 回 m 站）。 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// ---------------------------------------------------------------------------
// 全屏工具
// ---------------------------------------------------------------------------

/** 从 Compose 的 LocalContext 往上找出宿主 Activity。 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

/**
 * 显示/隐藏系统栏（状态栏 + 导航栏）。
 *
 * 用 [WindowInsetsControllerCompat]（minSdk 29 走兼容路径），
 * 不用已废弃的 `systemUiVisibility`。
 */
private fun setSystemBarsHidden(activity: Activity?, hidden: Boolean) {
    val window = activity?.window ?: return
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (hidden) {
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * 网页全屏（customView）宿主：挂在 Activity 的 decorView 上、旋转 90° 显示。
 *
 * 为什么挂 decorView 而不是 Compose 层：WebChromeClient 交出的是普通 `View`，
 * 标准做法就是加到 decorView；这样系统返回键/我们的退出按钮都能拿到它。
 * 旋转只是「容器旋转」，不改窗口方向（理由见 [BiliPlayerScreen] 类注释三）。
 */
private class CustomViewHost(private val activity: Activity) {

    private var wrapper: FrameLayout? = null

    fun show(view: View, onExit: () -> Unit) {
        hide()
        val decor = activity.window.decorView as? ViewGroup ?: return
        val metrics = activity.resources.displayMetrics
        val screenWidth = decor.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenHeight = decor.height.takeIf { it > 0 } ?: metrics.heightPixels

        val root = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
        val rotated = FrameLayout(activity)
        // 交换宽高 + 旋转 90°，正好铺满竖屏窗口，看起来就是横屏播放。
        root.addView(
            rotated,
            FrameLayout.LayoutParams(screenHeight, screenWidth, Gravity.CENTER)
        )
        rotated.rotation = 90f
        rotated.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // 全屏时原生 UI 都被盖住，额外给一个可点的退出入口（系统返回键也可）。
        val exitLabel = TextView(activity).apply {
            text = "退出全屏"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(36, 18, 36, 18)
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { onExit() }
        }
        root.addView(
            exitLabel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(24, 24, 24, 24) }
        )
        decor.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        wrapper = root
    }

    fun hide() {
        val current = wrapper ?: return
        wrapper = null
        (current.parent as? ViewGroup)?.removeView(current)
    }
}
