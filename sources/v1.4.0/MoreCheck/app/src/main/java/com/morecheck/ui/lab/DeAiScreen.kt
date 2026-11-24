// 「实验室 → 去 AI 味」：内嵌「去AI味改写」单页应用（WebView 承载）。
//
// 素材来源：用户自己在 WorkBuddy 里建的网页应用（`E:\WorkBuddy\2026-10-27-13-13-33`，
// appId `wbapp_bX9XF0rkMz1SruPdhPMgHM`），本页只做**容器**，业务全在网页里。
//
// 本页与 `TodoTodayScreen` 同构（都是"网页 + 云"），差异只有三处：
// 1. **云应用不同**：endpoint = `deai-rewriter-64769.app.workbuddy.host`，
//    所以 `WebViewAssetLoader` 的域名也要跟着换（见 [DEAI_ORIGIN_HOST]）；
// 2. **没有登录**：页面不带 auth，直接调 `cloud.llm.chat.completions`（云端按 client 计费/限流），
//    所以不需要 `domStorageEnabled` 撑会话——但**仍然开着**，因为 SDK 内部会缓存模型列表；
// 3. **有大量文字输入**：输入框占满一屏，键盘弹出必须让位（[imePadding]），
//    否则下半屏的「开始改写」按钮会被键盘盖住。
//
// 页面里还有 `window.DeAIAPI`（`rewrite` / `listModels`）作为对外接口。
// **本轮刻意不接原生桥**：接了就要在原生侧做文本进出、进度、错误映射一整套，
// 而网页本身的交互（分段控件、强度/风格、报告）已经完整；等真要做"选中文字直接改写"
// 那种系统级入口时再接。

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
import androidx.compose.foundation.layout.imePadding
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
 * 去AI味页所在云应用的**主机名**（= 页面的 origin）。
 *
 * ⚠️ 与「今日待办」同一个坑，别再踩：云网关对每个 client
 * （请求头 `x-wb-webapp-access-key`）绑定了一张 **Origin 白名单**，
 * `WebViewAssetLoader` 的默认域名 `appassets.androidplatform.net` 会被
 * **403 `access_denied: the request origin is not allowed for this client`**。
 *
 * 所以这里把 assets 继续映射成本地资源，但**域名填应用自己的域名**：
 * 页面 origin 与 `/.cloud/llm` 同源 → 网关放行，且页面仍是 https（安全上下文，
 * SDK 不会把 `crypto.subtle` / localStorage 降级）。
 */
private const val DEAI_ORIGIN_HOST = "deai-rewriter-64769.app.workbuddy.host"

/**
 * 页面地址：`app/src/main/assets/deai/index.html` 经 https 映射后的 URL。
 *
 * 不用 `file:///android_asset/...` 的原因有两条（与今日待办同源）：
 * 1. `file://` 是**不透明 origin**，`localStorage` 会抛 `SecurityError`，
 *    SDK 会**静默回落到内存存储**——表现就是"每次进来模型列表都要重新拉"；
 * 2. 云网关要按 Origin 判白名单，`file://` 的 `Origin: null` 虽实测放行，
 *    但那是运气，不是契约。
 */
private const val DEAI_PAGE_URL = "https://$DEAI_ORIGIN_HOST/assets/deai/index.html"

/**
 * assets 子目录前缀。
 *
 * ⚠️ 必须带首尾斜杠：`shouldInterceptRequest` 收到的是完整 URL，
 * `WebViewAssetLoader` 按前缀匹配路径；写成 `assets/deai` 匹配不上 → 整页 404 白屏。
 */
private const val DEAI_ASSET_PATH_PREFIX = "/assets/"

/**
 * WebView 兜底色：与 `assets/deai/index.html` 的 `:root --bg`（#E8EAEF）同值。
 *
 * 只用于首帧绘制前露出的那一层。**改页面 `--bg` 时必须同步改这里**，
 * 否则首帧会闪一圈色差（与番茄 / 今日待办同一条维护约定）。
 */
private const val DEAI_BACKGROUND_COLOR = 0xFFE8EAEF.toInt()

/**
 * 手势条那一带的兜底色：与 [DEAI_BACKGROUND_COLOR] 同值。
 *
 * 内容区铺到屏幕最底时，深色主题下若露出主题 `surface` 会有一条深色横条
 * 压在浅色网页下面；铺一层与网页 `--bg` 同值的浅灰，手势条区域就与页面连成一片。
 */
private const val DEAI_NAVBAR_GUARD_COLOR = 0xFFE8EAEF.toInt()

/** 单个 WebView 实例的持有者（先置空再销毁，两条销毁路径都只销毁一次）。 */
private class DeAiWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 去 AI 味」。
 *
 * ## 原生与网页的分工
 *
 * **网页负责全部业务**：文本输入、强度（轻/中/重）与风格选择、模型列表、
 * 流式改写、改写报告。**原生只负责容器**：标题栏与返回、系统栏图标明暗、
 * WebView 生命周期、键盘让位。原生不读写网页的任何业务状态。
 *
 * ## 数据流向（写进隐私政策的口径）
 *
 * 页面 → `https://deai-rewriter-64769.app.workbuddy.host/.cloud/llm/...`（WorkBuddy 云）。
 * 用户输入的文字**只发这一次请求**，原生层不读取、不留存、不落盘。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页：页面内没有 web 层导航（单页、无外链），
 * `canGoBack()` 恒为 false，因此不做"先回退网页历史"的分支。
 *
 * @param onBack 退出本页（交给导航层 `popBackStack`）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeAiScreen(
    onBack: () -> Unit
) {
    val holder = remember { DeAiWebViewHolder() }

    // 网页固定浅色（--bg #E8EAEF / 白卡），chrome 跟随应用主题。
    // 深色主题下是"深色标题栏 + 浅色网页"的两截观感——这是既有取舍（三个网页工具都这样），
    // 按 UI 评审的建议用一条 1dp 分隔线把它做成"有意分区"，而不是让色差自己硬切。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // 状态栏压在 chrome 上 → 跟随主题；手势条被浅灰兜底压着 → 恒深色图标。
    // 离开时原样写回，避免把本页取值泄漏给其它页。
    val lightStatusBarIcons: Boolean = !darkTheme
    val lightNavBarIcons = true

    val view = LocalView.current
    val appContext = LocalContext.current.applicationContext
    val deAiAssetLoader = remember(appContext) {
        WebViewAssetLoader.Builder()
            // 域名必须是云网关白名单里的那个，见 DEAI_ORIGIN_HOST。
            .setDomain(DEAI_ORIGIN_HOST)
            .addPathHandler(DEAI_ASSET_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(appContext))
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
                title = { Text("去 AI 味") },
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
                .background(Color(DEAI_NAVBAR_GUARD_COLOR))
                // Scaffold 给的 bottom inset 让回给内容：WebView 铺到屏幕最底。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
                // 键盘弹出时整体上移：这是本页与今日待办最大的差异——
                // 那个页面只有一行验证码输入，被盖住还能滑；这个页面输入框占半屏，
                // 不跟随的话「开始改写」按钮直接压在键盘下面。
                .imePadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                // 标题栏与网页之间的 1dp 分隔线（评审 UI-5，见 ChromeHairline 的注释）。
                ChromeHairline()
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            holder.view = this
                            settings.javaScriptEnabled = true
                            // SDK 会把模型列表 / 少量状态放进 localStorage；
                            // 不开的话每次进来都要重拉模型（且 SDK 会静默回落到内存存储，
                            // 表现为"慢但能用"，很难察觉）。
                            settings.domStorageEnabled = true
                            // 页面资源全部经 WebViewAssetLoader 映射，没有 file:// 访问，
                            // 与数独 / 今日待办同一口径：关掉 file 访问，少一个攻击面。
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            setBackgroundColor(DEAI_BACKGROUND_COLOR)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true

                            // ── 诊断：网页 console 与资源错误 → logcat ──────────────
                            // 内嵌页面的报错默认完全不可见（没有 DevTools）。这两行是本项目
                            // 排障标配：tag = DeAiWebView，只在 DEBUG 构建里启用
                            // （`WebView.setWebContentsDebuggingEnabled` 见 MoreCheckApp）。
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    Log.i(
                                        "DeAiWebView",
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
                                    return deAiAssetLoader.shouldInterceptRequest(request.url)
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    Log.w(
                                        "DeAiWebView",
                                        "资源失败 ${request?.url} : ${error?.errorCode} ${error?.description}"
                                    )
                                }

                                // 页面是单页、无外链跳转；出现整页跳转一定是异常（比如 SDK 升级后
                                // 走了 OAuth），这里只记日志、不拦截，避免掩盖问题。
                                override fun onPageFinished(view: WebView, url: String?) {
                                    Log.i("DeAiWebView", "loaded: $url")
                                }
                            }

                            loadUrl(DEAI_PAGE_URL)
                        }
                    },
                    onRelease = { holder.destroy() }
                )
            }
        }
    }
}

/**
 * 标题栏与网页之间的分隔线。
 *
 * 评审 UI-5 的结论：网页素材是固定浅色，深色主题下"深色标题栏 + 浅色网页"本身是
 * 两截；与其让它靠色差硬切，不如补一条 1dp 细线把关系说明白（"这里有分区"）。
 * 浅色主题下这条线几乎看不见，无副作用，所以两个模式都画。
 */
@Composable
private fun ChromeHairline() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
