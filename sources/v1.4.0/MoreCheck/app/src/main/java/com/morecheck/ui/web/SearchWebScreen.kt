package com.morecheck.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
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
import androidx.compose.material3.OutlinedButton
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
import com.morecheck.ui.common.WebPageNavBarIcons

/** 点击标题后网址停留多久再切回网页标题（毫秒）。 */
private const val URL_PEEK_MS = 3000L

/**
 * 明文 http 升级失败时**页面内**错误页用的说明。
 *
 * 2026-11-10 需求 2：这句话**不再进全局提示条**。用户反馈「用内嵌网页时还会弹
 * 只能访问 https 的 toast」——正常浏览被一句安全提示打断，比白屏更烦。现在：
 * - http 一律静默升级为 https（见 `WebLinkPolicy`）；
 * - 升级不了 / 被服务端反复重定向时，只把原因写进应用内错误页；
 * - 外部协议（`intent://`、`weixin://` 等）静默拦截，不弹提示。
 * 这样既不给明文流量开口子，也不会在正常浏览时弹出与当前动作无关的提示。
 */
private const val HTTP_BLOCKED_MESSAGE =
    "出于安全考虑，仅允许访问 https 网站；http 明文链接可能被窃听或篡改，已拦截"

/** 网页标题还没回调回来时的占位文案。 */
private const val TITLE_PLACEHOLDER = "加载中…"

/**
 * 内嵌网页标题栏高度（默认 `TopAppBar` 是 64dp，这里收 8dp 给网页让出纵向空间）。
 */
private val WEB_TOP_BAR_HEIGHT = 56.dp

/**
 * 宿主侧注入的「WebView 视口兜底」脚本（2026-11-15，`https://tetris.scif.top/`）。
 *
 * 部分 Android WebView 上 **`100vh` 与「按视口高度求值的媒体查询」恒为 0**
 * （`docs/pitfalls/03` 的 5.27 记过同一个坑，当时是在自带 assets 页面里修的）。
 * 实测 `innerWidth=427 / innerHeight=796` 正常，但 `100vh=0`、
 * `body.clientHeight=16`、`.app` 高度 0、棋盘 `top=-58`，页面只剩顶部一小条；
 * 更糟的是视口高度被当成 0 后，**宽 > 高**，竖屏也被判成
 * `(orientation: landscape)`，于是页面误走横屏分支：统计卡挤成 2×2、
 * 底部方向键竖排、弹层「开始游戏」按钮被裁掉。
 *
 * 外部网站改不了源码，`useWideViewPort` / `loadWithOverviewMode` 开或关都无效
 * （本轮实测 + 5.27 逐项排除过），因此由宿主在 `onPageFinished` 注入这段脚本：
 * 1. **特性探测**：造一个 `height:100vh` 的探针，高度 < 10px 才认定视口坏。
 *    探测的是 WebView 而非某个站点；系统 WebView 修好这个 bug 后整段静默跳过；
 * 2. **高度兜底**：把 `html` / `body` 写成 `innerHeight` 的像素值，替掉解析为 0 的
 *    百分比链。官网等不依赖百分比高度的页面加上内联高度后照原样铺排，无副作用；
 * 3. **撤销误判横屏**：仅当「实际是竖屏（宽 < 高）但 WebView 说 landscape」时，
 *    遍历同源样式表删掉 `orientation:landscape` 的媒体规则，让竖屏规则正常生效。
 *    跨域 `<link>` 样式表读 `cssRules` 会抛 SecurityError，跳过即可；
 * 4. **通知页面重排**：派发一次 `resize`，页面自己的 `relayout()` 会重新量棋盘尺寸；
 *    旋转 / 键盘引起的尺寸变化也由 `resize` 监听复算。
 *
 * `running` 是重入保护：兜底自己派发的 `resize` 会再次命中监听，没有它就会无限递归。
 * 脚本用 IIFE 且带 `window.__cimWebViewFix` 幂等标记，重复导航不会叠加监听。
 */
private val WEB_VIEWPORT_FIX_JS = """
(function () {
  if (window.__cimWebViewFix) { window.__cimWebViewFix(); return; }
  var running = false;
  function fix() {
    if (running || !document.body) return;
    running = true;
    try {
      var probe = document.createElement('div');
      probe.style.cssText = 'position:absolute;top:0;left:0;width:1px;height:100vh;visibility:hidden';
      document.body.appendChild(probe);
      var broken = probe.getBoundingClientRect().height < 10;
      probe.parentNode.removeChild(probe);
      if (!broken) return;
      var changed = false;
      var h = window.innerHeight;
      if (h) {
        document.documentElement.style.height = h + 'px';
        document.body.style.height = h + 'px';
        changed = true;
      }
      var portrait = window.innerWidth < window.innerHeight;
      var saysLandscape = window.matchMedia && window.matchMedia('(orientation: landscape)').matches;
      if (portrait && saysLandscape) {
        for (var i = 0; i < document.styleSheets.length; i++) {
          var sheet, rules;
          try { sheet = document.styleSheets[i]; rules = sheet.cssRules; } catch (e) { continue; }
          for (var j = rules.length - 1; j >= 0; j--) {
            var rule = rules[j];
            var cond = rule.conditionText || (rule.media && rule.media.mediaText) || '';
            if (rule.type === 4 && /orientation\s*:\s*landscape/i.test(cond)) {
              try { sheet.deleteRule(j); changed = true; } catch (e) {}
            }
          }
        }
      }
      if (changed) window.dispatchEvent(new Event('resize'));
    } finally { running = false; }
  }
  window.__cimWebViewFix = fix;
  window.addEventListener('resize', fix);
  fix();
})();
""".trimIndent()

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
 * 2. `androidx.compose.ui.viewinterop.AndroidView.onRelease` 与 `DisposableEffect.onDispose`
 *    都会在离开本页时触发，这里把实例置空后再 destroy，保证 `WebView.destroy()` 只执行一次。
 *
 * 可见性 `internal`（2026-11-03）：`BilibiliScreen` 用完全相同的「持有 + 双保险销毁」
 * 模式，直接复用本类，不再各抄一份。
 */
internal class WebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「官网」（原「搜一搜」/「看一看」）：内嵌网页浏览器。
 *
 * 交互：
 * - 打开即访问 [OFFICIAL_SITE_URL]（[officialHomeUrl] 负责归一化带进来的初始地址）；
 * - 标题栏从左到右：返回、网页标题（点一下显示网址、再点一下复制网址）、用系统浏览器打开；
 * - 标题栏下沿一条 2dp 进度条，跟随加载进度，1..99 时可见；
 * - 返回键与返回箭头同逻辑：能回退网页历史就先回退网页，否则退出本页；
 * - **拒绝一切下载**：不启动任何下载任务，改为 Snackbar + 弹窗告知。
 *
 * 安全取舍（本页只浏览公网 **https** 页面）：
 * - 关闭 `allowFileAccess` / `allowContentAccess`，防止远程页面借 file:// 读取应用私有目录；
 * - **只加载 https**：明文 http 会先自动升级为 https；升级不了时只在页面内的错误页
 *   说明原因，**不再弹全局 Snackbar / Toast**（2026-11-10 需求 2）；
 * - 其余协议（intent:// market:// mailto: file:// 等）一律静默拦截，避免跳出宿主应用；
 * - `mixedContentMode` 保持系统默认（`MIXED_CONTENT_NEVER_ALLOW`）：既然全站只允许 https，
 *   就没有任何理由放行 https 页面里夹带的 http 子资源，放行反而会开一个降级缺口。
 *   代价是个别老旧站点的 http 图片会加载失败（表现为缺图），这是有意接受的取舍。
 *
 * @param onBack 退出本页（网页历史已到底时才会被调用）。
 * @param onMessage 走全局 Snackbar 的提示出口。
 * @param initialUrl 打开本页时首先加载的地址；空 / 缺省 = [OFFICIAL_SITE_URL]。
 *   2026-10-25 需求（检查更新应用内打开）：「关于 → 检查更新」在开发者选项
 *   开关开启时，经本页打开 GitHub Releases——路由 `search?url={url}` 带进来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchWebScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
    initialUrl: String? = null,
) {
    val context = LocalContext.current
    val holder = remember { WebViewHolder() }
    // 网页恒定白底：导航栏图标强制深色，避免深色主题下"按钮消失"（需求 3）。
    WebPageNavBarIcons()

    // 当前地址用 rememberSaveable 保存：屏幕旋转 / 进程重建后恢复到用户停留的那一页，
    // 而不是无条件重新回到首页。
    var currentUrl by rememberSaveable {
        mutableStateOf(officialHomeUrl(initialUrl))
    }
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
            // 节流器随这个 WebViewClient 实例存活（它本身已被 remember 住）。
            // 网页可能在很短时间内连续抛出多个外部协议链接，没有节流的话 Snackbar
            // 会一直刷新，观感就是用户反馈的「频繁弹出」。
            private val noticeThrottle = NoticeThrottle()

            // 服务端把 https 重定向回 http 时，短时间内只允许自动升级一次。
            //
            // ⚠️ 不能写成「升级后置 true、下次 https 页开始再置回 false」的布尔量：
            // 升级后的 https 页一样会触发 onPageStarted，标记立刻被清零，
            // 服务器再把 https 重定向回 http 时又能升级一轮 —— 顽固站点会
            // 在两种协议间无限来回。这里改成时间窗：窗口内的第二次降级直接
            // 落页面内错误页，窗口过了才允许新一轮升级（用户后续正常访问不受影响）。
            private var lastUpgradeAt = 0L

            private fun notice(text: String) {
                if (noticeThrottle.shouldShow(text)) messageSink.value(text)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // 拿不到目标地址时拒绝，绝不把未知链接甩给系统。
                val uri = request?.url ?: return true
                val isMainFrame = request.isForMainFrame
                val scheme = uri.scheme?.lowercase()

                return when (WebLinkPolicy.decide(scheme, isMainFrame)) {
                    WebLinkDecision.ALLOW -> {
                        // 下载扩展名只拦主框架：子资源里的 .zip / .bin 之类交给 WebView
                        // 自己处理即可，拦下来只会制造无意义的提示。
                        if (!isMainFrame || downloadExtension(uri) == null) {
                            false
                        } else {
                            rejectedDownload = rejectedDownloadText(uri.toString(), uri.lastPathSegment)
                            notice("已拒绝下载：本应用不支持文件下载")
                            true
                        }
                    }

                    WebLinkDecision.UPGRADE_HTTPS -> {
                        // 主动升级为 https：多数站点都支持，比「拦截 + 弹一句风险提示」
                        // 对用户有用得多。升级后若目标确实只有 http，主框架会走
                        // onReceivedError 给出一次明确的失败提示。
                        val upgraded = WebLinkPolicy.toHttps(uri.toString())
                        if (upgraded != null) {
                            view?.loadUrl(upgraded)
                        } else {
                            // 只写页面内错误页，不再弹全局提示（2026-11-10 需求 2）。
                            errorMessage = HTTP_BLOCKED_MESSAGE
                        }
                        true
                    }

                    // 子资源、about:blank / data: / javascript: 等常态写法：静默吞掉。
                    WebLinkDecision.SILENT_BLOCK -> true

                    WebLinkDecision.BLOCK_WITH_NOTICE -> {
                        // 外部协议（intent:// / market:// / weixin:// 等）多数是网页自己的
                        // 拉端尝试，并不是用户主动要离开应用。弹「仅支持 https 网页」既不准确
                        // 又打断浏览，所以这里一律静默拦截；真要跳出应用，用户可用标题栏
                        // 右上角那个「用系统浏览器打开」按钮。
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 服务端 3xx 重定向（如 https → http）不经过 shouldOverrideUrlLoading，
                // 这里补一道兜底：同样升级成 https，而不是停掉页面再反复弹提示。
                // 刻意不更新 currentUrl，失败时「重试」会回到上一个 https 页面。
                if (url != null && url.startsWith("http://", ignoreCase = true)) {
                    view?.stopLoading()
                    val now = SystemClock.elapsedRealtime()
                    val upgraded = WebLinkPolicy.toHttps(url)
                    if (upgraded != null && allowRedirectUpgrade(now, lastUpgradeAt)) {
                        lastUpgradeAt = now
                        view?.loadUrl(upgraded)
                    } else {
                        // 同样只落在页面内错误页；不再走 Snackbar / Toast。
                        errorMessage = HTTP_BLOCKED_MESSAGE
                    }
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

            override fun onPageFinished(view: WebView?, url: String?) {
                // 外部网页改不了源码，视口高度与误判横屏只能由宿主兜底；
                // 脚本自带特性探测且幂等，非 https 入口不注入。
                if (view == null || url == null) return
                if (!url.startsWith("https://", ignoreCase = true)) return
                view.evaluateJavascript(WEB_VIEWPORT_FIX_JS, null)
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
                    // 默认 64dp → 56dp：网页是「一屏」布局，标题栏每少 1dp 都还给内容区。
                    expandedHeight = WEB_TOP_BAR_HEIGHT,
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
                                // 默认 titleLarge(22sp) → titleMedium(16sp)：标题栏压缩后
                                // 仍保持「图标 48dp + 单行标题」的紧凑比例，网址态也不会撑高。
                                style = MaterialTheme.typography.titleMedium,
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
                        // 官网首页依赖 JS 才能正常渲染；domStorage 供站点保存本地状态。
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

/**
 * 主框架加载失败时的应用内错误页：给出原因与「重试」，不留白屏。
 *
 * 可见性 `internal`（2026-11-03）：B 站视频页（`BilibiliScreen`）的主框架错误
 * 也用这块面板呈现，避免两份长得一样却对不齐的 UI。
 */
@Composable
internal fun WebErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 可选的第二个动作（文案 + 回调）；不传时错误页与改造前完全一致。 */
    extraAction: Pair<String, () -> Unit>? = null
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
        extraAction?.let { (label, action) ->
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = action) { Text(label) }
        }
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
