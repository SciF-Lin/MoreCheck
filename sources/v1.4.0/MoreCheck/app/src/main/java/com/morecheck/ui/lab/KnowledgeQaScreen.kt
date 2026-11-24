// 「实验室 → 知识问答」：内嵌「知识问答 Agent」单页应用（WebView 承载）。
//
// 素材来源：用户自己在 WorkBuddy 里建的网页应用（`E:\WorkBuddy\all\knowledge-qa-agent`，
// 云应用 `knowledge-qa-agent-91639.app.workbuddy.host`），本页只做**容器**，
// 业务（检索 → 硬门禁 → 大模型作答 → 引用校验）全在网页里。
//
// 本页与 `DeAiScreen` 同构，差异有三处：
// 1. **云应用不同**：endpoint = `knowledge-qa-agent-91639.app.workbuddy.host`，
//    所以 `WebViewAssetLoader` 的域名也要跟着换（见 [KBQA_ORIGIN_HOST]）；
// 2. **除 LLM 还读云端数据库**：页面把文档与片段存在该云应用的 `kb_documents` /
//    `kb_chunks` 两张表里（首次进入会写入示例文档），所以 `domStorageEnabled` 必须开——
//    SDK 会用 localStorage 缓存模型目录与会话，关掉会退化成"每次都重拉"；
// 3. **有大量文字输入**：提问框在页面底部，键盘弹出必须让位（[imePadding]），
//    否则输入框与发送键会被键盘盖住。
//
// 页面里没有登录：数据库走该应用自己的公开策略，LLM 按应用标识计费/限流。

package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.morecheck.util.findActivity

/**
 * 知识问答页所在云应用的**主机名**（= 页面的 origin）。
 *
 * ⚠️ 与「今日待办」「去 AI 味」同一个坑：云网关对每个 client
 * （请求头 `x-wb-webapp-access-key`）绑定了一张 **Origin 白名单**，
 * `WebViewAssetLoader` 的默认域名 `appassets.androidplatform.net` 会被
 * **403 `access_denied: the request origin is not allowed for this client`**。
 *
 * 所以 assets 仍映射成本地资源，但**域名填这个云应用自己的域名**：
 * 页面 origin 与 `/.cloud/llm`、`/.cloud/database` 同源 → 网关放行，
 * 且页面仍是 https（安全上下文，SDK 不会把 `crypto.subtle` / localStorage 降级）。
 */
private const val KBQA_ORIGIN_HOST = "knowledge-qa-agent-91639.app.workbuddy.host"

/**
 * 页面地址：`app/src/main/assets/knowledge_qa/index.html` 经 https 映射后的 URL。
 *
 * 不用 `file:///android_asset/...` 的两条理由（与今日待办、去 AI 味同源）：
 * 1. `file://` 是不透明 origin，`localStorage` 会抛 `SecurityError`，SDK 静默回落到内存存储；
 * 2. 云网关按 Origin 判白名单，`Origin: null` 只是"实测能过"，不是契约。
 */
private const val KBQA_PAGE_URL = "https://$KBQA_ORIGIN_HOST/assets/knowledge_qa/index.html"

/**
 * assets 子目录前缀。
 *
 * ⚠️ 必须带首尾斜杠：`shouldInterceptRequest` 收到的是完整 URL，
 * `WebViewAssetLoader` 按前缀匹配；写成 `assets/knowledge_qa` 匹配不上 → 整页 404 白屏。
 */
private const val KBQA_ASSET_PATH_PREFIX = "/assets/"

/**
 * WebView 兜底色：与 `assets/knowledge_qa/styles.css` 的 `:root --bg`（#f5f6fa）同值。
 *
 * 只用于首帧绘制前露出的那一层。**改页面 `--bg` 时必须同步改这里**，
 * 否则首帧会闪一圈色差（与去 AI 味 / 今日待办同一条维护约定）。
 */
private const val KBQA_BACKGROUND_COLOR = 0xFFF5F6FA.toInt()

/** 手势条那一带的兜底色：与 [KBQA_BACKGROUND_COLOR] 同值，避免深色主题下露出深色横条。 */
private const val KBQA_NAVBAR_GUARD_COLOR = 0xFFF5F6FA.toInt()

/** 单个 WebView 实例的持有者（先置空再销毁，两条销毁路径都只销毁一次）。 */
private class KbqaWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 知识问答」。
 *
 * ## 原生与网页的分工
 *
 * **网页负责全部业务**：知识库文档的增删、检索、提问、流式回答、引用卡片与原文定位。
 * **原生只负责容器**：标题栏与返回、系统栏图标明暗、WebView 生命周期、键盘让位
 * （本页输入框在底部，不跟随的话发送键会被键盘盖住）。
 *
 * ## 数据流向（写进隐私政策的口径）
 *
 * 页面 → `https://knowledge-qa-agent-91639.app.workbuddy.host/.cloud/...`（WorkBuddy 云）。
 * 用户的问题只发往这一个云应用；原生层不读取、不留存、不落盘。
 *
 * ## 返回键
 *
 * 页面是单页、无 web 层导航（`canGoBack()` 恒为 false），所以只用 [BackHandler] 退出本页。
 *
 * @param onBack 退出本页（交给导航层 `popBackStack`）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeQaScreen(
    onBack: () -> Unit
) {
    val holder = remember { KbqaWebViewHolder() }

    // 网页固定浅色（--bg #f5f6fa / 白卡），chrome 跟随应用主题；
    // 深色主题下用一条 1dp 分隔线把"深色标题栏 + 浅色网页"做成有意分区（同去 AI 味）。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // 状态栏压在 chrome 上 → 跟随主题；手势条被浅灰兜底压着 → 恒深色图标。
    val lightStatusBarIcons: Boolean = !darkTheme
    val lightNavBarIcons = true

    val view = LocalView.current
    val appContext = LocalContext.current.applicationContext
    val assetLoader = remember(appContext) {
        WebViewAssetLoader.Builder()
            // 域名必须是云网关白名单里的那个，见 KBQA_ORIGIN_HOST。
            .setDomain(KBQA_ORIGIN_HOST)
            .addPathHandler(KBQA_ASSET_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(appContext))
            .build()
    }
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, lightStatusBarIcons, lightNavBarIcons) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = lightStatusBarIcons
            controller.isAppearanceLightNavigationBars = lightNavBarIcons
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    DisposableEffect(Unit) { onDispose { holder.destroy() } }

    BackHandler { onBack() }

    Scaffold(
        containerColor = chromeBackground,
        topBar = {
            TopAppBar(
                title = { Text("知识问答") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(KBQA_NAVBAR_GUARD_COLOR))
                // Scaffold 给的 bottom inset 让回给内容：WebView 铺到屏幕最底（页面自己的
                // 浅色底板就能一直铺到手势条，不留一条异色横条）。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
                // 页面的提问框固定在底部（`.app-shell{position:fixed;inset:0}`），
                // 而网页不知道系统安全区：铺到最底就必然被导航栏压住半截。
                // 取「导航栏高度」与「键盘高度」的较大值让位：
                // 键盘收起时让开导航栏，键盘弹出时让开键盘（ime 已含导航栏区域，不会叠加）。
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime)
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                // 标题栏与网页之间的 1dp 分隔线（同 DeAiScreen 的 ChromeHairline）。
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            holder.view = this
                            settings.javaScriptEnabled = true
                            // 页面的文档 / 片段存在云端，但 SDK 会把模型目录与会话缓存进
                            // localStorage；不开的话每次进来都要重拉（且 SDK 静默回落内存存储）。
                            settings.domStorageEnabled = true
                            // 资源全部经 WebViewAssetLoader 映射，没有 file:// 访问。
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            setBackgroundColor(KBQA_BACKGROUND_COLOR)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true

                            // ── 诊断：网页 console 与资源错误 → logcat（排障标配）──────
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    Log.i(
                                        "KbqaWebView",
                                        "[console/${message.messageLevel()}] ${message.message()} " +
                                            "(${message.sourceId()}:${message.lineNumber()})"
                                    )
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                // assets → https 映射。⚠️ 必须在 loadUrl 之前装好：
                                // 首屏请求就会走 shouldInterceptRequest，装晚了会 404 白屏。
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? {
                                    return assetLoader.shouldInterceptRequest(request.url)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    Log.w(
                                        "KbqaWebView",
                                        "资源失败 ${request?.url} : ${error?.errorCode} ${error?.description}"
                                    )
                                }

                                // 单页、无外链；出现整页跳转只记日志，不拦截（避免掩盖问题）。
                                override fun onPageFinished(view: WebView, url: String?) {
                                    Log.i("KbqaWebView", "loaded: $url")
                                }
                            }

                            loadUrl(KBQA_PAGE_URL)
                        }
                    },
                    onRelease = { holder.destroy() }
                )
            }
        }
    }
}
