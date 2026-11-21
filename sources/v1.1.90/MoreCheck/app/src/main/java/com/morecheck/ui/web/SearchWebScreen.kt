package com.morecheck.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/** 默认首页。集中声明成常量，避免地址散落在各处。 */
private const val HOME_URL = "https://www.baidu.com"

/** 点击标题后网址停留多久再切回网页标题（毫秒）。 */
private const val URL_PEEK_MS = 3000L

/**
 * 拦截明文 http 时的风险提示。
 *
 * 集中成常量：[WebViewClient.shouldOverrideUrlLoading]（点击链接 / 页面跳转）与
 * [WebViewClient.onPageStarted]（服务端 3xx 跳转兜底）两处都要用，避免文案不一致。
 */
private const val HTTP_BLOCKED_MESSAGE =
    "出于安全考虑，仅允许访问 https 网站；http 明文链接可能被窃听或篡改，已拦截"

/** 网页标题还没回调回来时的占位文案。 */
private const val TITLE_PLACEHOLDER = "加载中…"

/**
 * 标题栏「标题区」的显示状态。
 *
 * 用枚举而不是两个布尔量，是为了让三态互斥、写不出「已复制但仍会自动收起」这类非法组合：
 * - [TITLE]：显示网页标题（常态）；
 * - [URL]：显示网址，[URL_PEEK_MS] 后自动切回 [TITLE]；
 * - [URL_COPIED]：已把网址复制到剪贴板，**钉住**不再自动收起，
 *   好让用户能看着屏幕核对刚复制的内容。
 */
private enum class TitleDisplay { TITLE, URL, URL_COPIED }

/**
 * 会被当作「下载任务」的扩展名。
 *
 * 这类链接交给 WebView 也不会正常渲染（要么触发下载、要么直接变空白），
 * 因此在 shouldOverrideUrlLoading 里提前拦掉，给出一致提示。
 */
private val DOWNLOAD_EXTENSIONS = setOf(
    "apk", "apks", "obb", "zip", "rar", "7z", "tar", "gz", "xz", "exe", "dmg", "bin", "iso"
)

/**
 * 单个 WebView 实例的持有者。
 *
 * 为什么不直接用 `remember { mutableStateOf<WebView?>(null) }`：
 * 1. 它不需要被观察——只在「返回键」「重试」「打开浏览器」这些**事件回调**里读取，
 *    不是组合期读取，用普通字段即可，赋值时也不会平白多触发一次重组；
 * 2. `AndroidView.onRelease` 与 `DisposableEffect.onDispose` 都会在离开本页时触发，
 *    这里把实例置空后再 destroy，保证 `WebView.destroy()` 只执行一次。
 */
private class WebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「搜一搜」（原「看一看」）：内嵌网页浏览器。
 *
 * 交互：
 * - 打开即访问 [HOME_URL]（百度）；
 * - 标题栏从左到右：返回、网页标题（点一下显示网址、再点一下复制网址）、用系统浏览器打开；
 * - 标题栏下沿一条 2dp 进度条，跟随加载进度，1..99 时可见；
 * - 返回键与返回箭头同逻辑：能回退网页历史就先回退网页，否则退出本页；
 * - **拒绝一切下载**：不启动任何下载任务，改为 Snackbar + 弹窗告知。
 *
 * 安全取舍（本页只浏览公网 **https** 页面）：
 * - 关闭 `allowFileAccess` / `allowContentAccess`，防止远程页面借 file:// 读取应用私有目录；
 * - **只允许 https**：Manifest 不再声明 `android:usesCleartextTraffic`，明文 http 会被系统拦成
 *   白屏，所以这里在跳转前就主动拦下并给出风险提示，而不是把白屏留给用户；
 * - 其余协议（intent:// market:// mailto: file:// 等）一律不外抛，避免跳出宿主应用；
 * - `mixedContentMode` 保持系统默认（`MIXED_CONTENT_NEVER_ALLOW`）：既然全站只允许 https，
 *   就没有任何理由放行 https 页面里夹带的 http 子资源，放行反而会开一个降级缺口。
 *   代价是个别老旧站点的 http 图片会加载失败（表现为缺图），这是有意接受的取舍。
 *
 * @param onBack 退出本页（网页历史已到底时才会被调用）。
 * @param onMessage 走全局 Snackbar 的提示出口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchWebScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val holder = remember { WebViewHolder() }

    // 当前地址用 rememberSaveable 保存：屏幕旋转 / 进程重建后恢复到用户停留的那一页，
    // 而不是无条件重新回到首页。
    var currentUrl by rememberSaveable { mutableStateOf(HOME_URL) }
    var pageTitle by rememberSaveable { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }

    // 标题区三态（标题 / 网址 / 已复制网址），详见 TitleDisplay。
    // 刻意**不用** rememberSaveable：它只是个短暂的展示态，旋转后回到「标题态」更自然，
    // 也避免把「已复制」这种一次性反馈在旋转后重新摆出来。
    var titleDisplay by remember { mutableStateOf(TitleDisplay.TITLE) }
    // urlShowTick 只为「重新计时」而存在：第二次点击时它变化，LaunchedEffect 因键变化重启，
    // 延时于是重新从 URL_PEEK_MS 开始，而不是沿用上一次的剩余时间。
    var urlShowTick by remember { mutableIntStateOf(0) }

    // 被拒绝的下载：非空即弹窗（用户明确要求「弹窗告诉我」）。
    var rejectedDownload by remember { mutableStateOf<String?>(null) }
    // 主框架加载失败时的应用内错误页；非空即覆盖 WebView，避免白屏。
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 回调对象会被 remember 住，若直接捕获 onMessage 参数就会一直用第一次组合的版本；
    // rememberUpdatedState 让回调始终读到最新的 lambda。
    val messageSink = rememberUpdatedState(onMessage)

    LaunchedEffect(titleDisplay, urlShowTick) {
        // 只有「网址态」才自动收起；URL_COPIED 是用户主动复制后的钉住态，要一直留着。
        if (titleDisplay == TitleDisplay.URL) {
            delay(URL_PEEK_MS)
            titleDisplay = TitleDisplay.TITLE
        }
    }

    /** 返回：能回退网页历史就先回退网页，否则退出本页。返回箭头与系统返回键共用这一套。 */
    fun navigateBack() {
        val web = holder.view
        if (web != null && web.canGoBack()) web.goBack() else onBack()
    }

    /**
     * 把当前网址复制到剪贴板。
     *
     * 用平台的 [ClipboardManager]，不引入任何依赖。
     * 取不到剪贴板服务或写入抛异常（个别 ROM 会因剪贴板被占用而抛）时也要给出反馈，
     * 不能让点击静默无反应。
     */
    fun copyCurrentUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            messageSink.value("复制失败：剪贴板不可用")
            return
        }
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("网址", currentUrl))
        }.onSuccess {
            messageSink.value("已复制网址")
        }.onFailure {
            messageSink.value("复制失败，请重试")
        }
    }

    BackHandler { navigateBack() }

    // 兜底销毁，避免 WebView 泄漏（onRelease 已经销毁过一次，这里是二次保险）。
    DisposableEffect(Unit) {
        onDispose { holder.destroy() }
    }

    val webViewClient = remember {
        object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // 拿不到目标地址时拒绝，绝不把未知链接甩给系统。
                val uri = request?.url ?: return true
                val scheme = uri.scheme?.lowercase()

                // **只允许 https**：Manifest 已不再声明 usesCleartextTraffic，
                // 明文 http 即使放行也会被系统拦成白屏。与其让用户对着白屏发懵，
                // 不如在这里就拦下并给出明确的风险提示（这是对 v1.1.53「放行 http」的逆转）。
                if (scheme == "http") {
                    messageSink.value(HTTP_BLOCKED_MESSAGE)
                    return true
                }
                if (scheme != "https") {
                    // 非 http/https（intent:// market:// mailto: file:// …）一律不交给系统：
                    // 这些可能唤起外部应用，或指向应用私有数据，属于必须守住的边界。
                    messageSink.value("无法打开该链接：仅支持 https 网页")
                    return true
                }
                if (downloadExtension(uri) != null) {
                    rejectedDownload = rejectedDownloadText(uri.toString(), uri.lastPathSegment)
                    messageSink.value("已拒绝下载：本应用不支持文件下载")
                    return true
                }
                // 其余交给 WebView 自己加载。
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 服务端 3xx 重定向（如 https → http）**不会**经过 shouldOverrideUrlLoading，
                // 这里补一道兜底：停掉这次明文加载，并复用已有的错误页把「为什么打不开」
                // 讲清楚，而不是让系统把它拦成白屏。
                //
                // 注意此处刻意**不**更新 currentUrl：于是错误页上的「重试」会回到上一个
                // https 页面，而不是反复重试那个注定失败的 http 地址。
                if (url != null && url.startsWith("http://", ignoreCase = true)) {
                    view?.stopLoading()
                    messageSink.value(HTTP_BLOCKED_MESSAGE)
                    errorMessage = HTTP_BLOCKED_MESSAGE
                    return
                }
                if (url != null) currentUrl = url
                // 新页面开始加载：清掉上一次的错误页，并把标题区复位成「显示网页标题」。
                // 不复位的话，用户在某页点出网址态后再跳转，新页面标题栏会继续显示
                // 上一页的网址，看起来像是加载卡住了。
                errorMessage = null
                titleDisplay = TitleDisplay.TITLE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // 只处理主框架失败；图片、埋点等子资源失败不该整页报错。
                // 这是**刻意**的：一次缺图就盖掉整个页面会让网页变得没法用，
                // 子资源的失败留给页面自己的占位符表现即可。
                if (request?.isForMainFrame != true) return
                val detail = error?.description?.toString().orEmpty()
                errorMessage = detail.ifBlank { "无法加载该网页" }
                messageSink.value("网页加载失败")
            }
        }
    }

    val webChromeClient = remember {
        object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle = title.orEmpty()
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { navigateBack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    title = {
                        // ── 「X」关闭按钮 + 标题，同一个 Row（本轮需求 10）────────────
                        //
                        // 需求原文：「标题栏增加 X 关闭按钮，位于返回键右边」。
                        // TopAppBar 的 actions 槽位画在**最右侧**，放不到"返回键右边"；
                        // 因此把 X 做成 title 行的**第一个子元素**（紧贴返回键、在标题之前），
                        // 与浏览器的通用布局一致（Chrome / Edge 的页签也是返回旁挂关闭）。
                        // X 与标题同处一个 Row：X 占固定宽度，标题吃剩余宽度（weight），
                        // 原有的三态点击、单行省略行为不受影响。
                        //
                        // ## 与返回键的分工（两者语义不同，一个都不能省）
                        //
                        // - 返回箭头：**沿网页历史回退**（canGoBack 就先 goBack），
                        //   回退到没有历史才退出本页；
                        // - X 关闭：**无论历史多深，直接退出本页**（onBack）。
                        //   用户看完网页想"整个关掉走人"时，逐层点返回要按 N 次——
                        //   这正是需求要这个按钮的原因。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭页面"
                                )
                            }
                            Text(
                                text = if (titleDisplay == TitleDisplay.TITLE) {
                                    pageTitle.ifBlank { TITLE_PLACEHOLDER }
                                } else {
                                    currentUrl
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        when (titleDisplay) {
                                            // 标题态 → 显示网址，并开始 URL_PEEK_MS 倒计时。
                                            TitleDisplay.TITLE -> {
                                                titleDisplay = TitleDisplay.URL
                                                urlShowTick++
                                            }
                                            // 网址态 → 复制网址；成功后进入钉住态，不再自动收起。
                                            TitleDisplay.URL -> {
                                                copyCurrentUrl()
                                                titleDisplay = TitleDisplay.URL_COPIED
                                            }
                                            // 已复制态：保持网址显示，再点不做多余动作
                                            // （避免重复写剪贴板，也避免用户以为「点了没反应」而狂点）。
                                            TitleDisplay.URL_COPIED -> Unit
                                        }
                                    }
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                    .onFailure { messageSink.value("没有可用的浏览器") }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "使用系统默认浏览器打开"
                            )
                        }
                    }
                )

                // 进度条紧贴标题栏下沿：只在加载中（1..99）出现，到 100 自动隐藏。
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // 百度首页依赖 JS 才能正常渲染；domStorage 供站点保存本地状态。
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 刻意关闭：本页只浏览公网页面，开放本地文件 / content://
                        // 访问会让远程页面有机会读取应用私有目录，属于不必要的攻击面。
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false

                        // 刻意不设置 mixedContentMode，保持系统默认的
                        // MIXED_CONTENT_NEVER_ALLOW：本站只允许 https，
                        // 放行 https 页面里的 http 子资源等于自己开一个降级缺口。
                        // v1.1.53 曾设为 COMPATIBILITY_MODE，本次一并回收。

                        // 必须用方法名调用：WebView 没有公开 getWebViewClient() /
                        // getWebChromeClient()，写成属性赋值会解析到外层的同名 val 而报错。
                        setWebViewClient(webViewClient)
                        setWebChromeClient(webChromeClient)

                        // 一律拒绝下载：不启动任何下载任务，只提示 + 弹窗。
                        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                            val fileName = runCatching {
                                URLUtil.guessFileName(url, contentDisposition, mimeType)
                            }.getOrNull()
                            rejectedDownload = rejectedDownloadText(url, fileName)
                            messageSink.value("已拒绝下载：本应用不支持文件下载")
                        }

                        // 只在首次创建时载入。update 里刻意不调 loadUrl：
                        // 进度、标题变化都会触发重组，若在 update 里加载就会每次都重新拉整页。
                        loadUrl(currentUrl)
                        holder.view = this
                    }
                },
                update = { /* 刻意留空，见 factory 内对 loadUrl 的说明。 */ },
                onRelease = { it.destroy() }
            )

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
        }
    }

    rejectedDownload?.let { text ->
        AlertDialog(
            onDismissRequest = { rejectedDownload = null },
            title = { Text("已拒绝下载") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { rejectedDownload = null }) { Text("知道了") }
            }
        )
    }
}

/** 主框架加载失败时的应用内错误页：给出原因与「重试」，不留白屏。 */
@Composable
private fun WebErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            // 用中性标题：这个面板既承载「加载失败」，也承载「因是 http 被拦截」，
            // 写死「网页加载失败」会让后者文不对题。
            text = "无法打开该网页",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

/** 取扩展名并判断是否为下载类链接；不是则返回 null。 */
private fun downloadExtension(uri: Uri): String? {
    val segment = uri.lastPathSegment ?: return null
    val ext = segment.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it.isNotEmpty() && it in DOWNLOAD_EXTENSIONS }
}

/** 拼出「能说清是什么被拒了」的弹窗文案（文件名拿不到就只说这是下载任务）。 */
private fun rejectedDownloadText(url: String, fileName: String?): String {
    val name = fileName?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
    return buildString {
        append("已拒绝下载：本应用不支持文件下载。\n\n")
        if (name != null) {
            append("文件：").append(name)
        } else {
            append("该链接是下载任务。")
        }
        append("\n\n地址：").append(url)
    }
}
