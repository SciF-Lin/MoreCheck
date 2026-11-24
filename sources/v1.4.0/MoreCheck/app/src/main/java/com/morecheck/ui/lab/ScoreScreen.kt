// 「实验室 → 成绩收集统计」：内嵌成绩收集统计单页应用（WebView 承载）。
//
// 本页与 `TodoTodayScreen`（今日待办）同构——都是"自带的单页应用 + WorkBuddy 云后端"，
// 因此可用的结论、踩过的坑、写法上的纪律都直接沿用，不另起一套。
//
// ── 与今日待办的关键差异 ────────────────────────────────────────────────
//
// 1. **页面自带双角色**：学生只能填自己的成绩，班长能看/改全班。
//    权限边界落在数据库（PostgreSQL RLS），不在前端——所以本页**不需要**做任何角色判断，
//    原生侧也拿不到、也不需要"当前用户是班长"这种信息。
// 2. **OAuth 登录在 App 内不可用**：成绩页的「微信登录 / WorkBuddy 登录」要整页跳到
//    第三方域名授权再回调（回调地址是当前 origin 的根路径），在 assets 映射下会跳到
//    **线上部署版**，用户会"跳出应用"。因此网页侧做了嵌入模式适配：
//    检测到宿主注入的 `window.MoreCheckHost` 就自动隐藏这两个按钮，只留验证码登录
//    （纯接口调用，WebView 内完全可用）。详见 assets/score/index.html 里的 `IS_EMBEDDED`。
// 3. **登录态由 localStorage 持久化**：WorkBuddy 云 SDK 把会话写在
//    `localStorage`（键 `workbuddy-cloud.session.<publishableKey>`），
//    页面启动时 `getSession()` 读回来即可恢复登录态。
//    ⚠️ 这要求**稳定的 https origin**——所以这里必须走 `WebViewAssetLoader` 映射，
//    不能用 `file:///android_asset/...`（不透明 origin 下 localStorage 抛 SecurityError，
//    SDK 会静默回落到内存存储，于是"每次进来都要重新登录"）。
//    宿主域名必须是云网关白名单里的那个（见 [SCORE_ORIGIN_HOST] 的说明表）。
//
// ── 原生与网页的分工 ────────────────────────────────────────────────────
//
// **网页负责全部业务**：登录、身份选择（学生/班长）、建班/加入班级、考试管理、
// 成绩填写与统计。
// **原生只负责容器**：标题栏与返回、系统栏图标明暗、WebView 生命周期、网页事件回传。

package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.morecheck.util.findActivity

/**
 * 成绩页要加载的 assets 映射域名（= 页面的 origin）。
 *
 * ⚠️ **必须是云网关白名单里的那个域名**，与今日待办同一类坑：
 * 云网关对每个 client（`x-wb-webapp-access-key`）绑定了一张 Origin 白名单，
 * 用 `WebViewAssetLoader` 的默认域名（`appassets.androidplatform.net`）会让所有
 * `/.cloud/...` 请求被 403（`access_denied: the request origin is not allowed for this client`），
 * 表现是"点获取验证码 → 报错"。
 *
 * 成绩应用自己的域名是 `score-stats-18758.app.workbuddy.host`（与网页版部署地址同域），
 * 用它做映射域名可以同时满足两件事：
 *   · origin 在云网关白名单里 → `/.cloud` 请求放行；
 *   · origin 与云接口同源 → 不触发跨域；
 *   · origin 稳定 → localStorage 能持久化 → **登录态能保留**。
 */
private const val SCORE_ORIGIN_HOST = "score-stats-18758.app.workbuddy.host"

/**
 * 本地副本地址（首选）。
 *
 * 页面副本在 `app/src/main/assets/score/index.html`，同目录还有 `workbuddy-cloud-sdk.js`
 * （页面用相对路径 `./workbuddy-cloud-sdk.js` 引入，在该映射下照常解析）。
 */
private const val SCORE_PAGE_URL = "https://$SCORE_ORIGIN_HOST/assets/score/index.html"

/**
 * 线上地址（兜底）。
 *
 * ⚠️ 只有在本地副本缺失 / 加载失败时才会用到（见 [ScoreScreen] 里的 `onReceivedError`）。
 * 它与本地副本是同一份页面、同一个域名，**登录态是共享的**（同一个 origin 的 localStorage），
 * 所以从线上切回本地不会掉登录。
 */
private const val SCORE_REMOTE_URL = "https://$SCORE_ORIGIN_HOST/"

/**
 * assets 子目录前缀。
 *
 * ⚠️ 必须带首尾斜杠：`shouldInterceptRequest` 收到的是完整 URL，
 * `WebViewAssetLoader` 按前缀匹配路径；写成 `assets/score` 会匹配不上 → 整页 404 白屏。
 */
private const val SCORE_ASSET_PATH_PREFIX = "/assets/"

/**
 * 页面主底色：与 `assets/score/index.html` 里 `:root` 的 `--bg`（#F3F2F9）同值。
 *
 * 用途只有一处——WebView 的兜底色（页面首帧绘制前露出的一层）。
 * ⚠️ 必须不透明；⚠️ 改页面 `--bg` 时必须同步改这里，否则首帧会闪出一圈色差。
 */
private const val SCORE_BACKGROUND_COLOR = 0xFFF3F2F9.toInt()

/**
 * 手势条那一带的兜底色（与 [SCORE_BACKGROUND_COLOR] 同值，理由同今日待办）。
 */
private const val SCORE_NAVBAR_GUARD_COLOR = 0xFFF3F2F9.toInt()

/**
 * 网页 → 原生的回调桥。
 *
 * 网页侧通过 `window.MoreCheckHost.notify(event, payload)` 调用；
 * 事件名见 `assets/score/index.html` 里的 `notifyHost`：
 *   · `ready`  —— 页面启动流程结束（无论是否已登录）
 *   · `login`  —— 已进入登录态，payload 是 user id
 *   · `logout` —— 已退出登录
 *   · `error`  —— 页面侧的异常，payload 是说明文字
 *                （目前只在 localStorage 不可用时触发——那种情况下登录态存不住）
 *
 * ⚠️ `@JavascriptInterface` 方法运行在 **WebView 的后台线程**，
 *    不能直接碰 UI——必须 post 回主线程。
 */
private class ScoreHostBridge(private val onEvent: (String, String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun notify(event: String, payload: String) {
        val ev = event.orEmpty()
        val pl = payload.orEmpty()
        Log.i(SCORE_WEB_LOG_TAG, "[host-event] $ev $pl")
        main.post { onEvent(ev, pl) }
    }
}

/**
 * 单个 WebView 实例的持有者。
 *
 * 与 `TodoTodayScreen` 的同名类**刻意各写一份**（本项目"一屏一个自洽实现"的纪律）。
 * `destroy()` 先置空再销毁，两条销毁路径谁先到都只销毁一次。
 */
private class ScoreWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        // ⚠️ 销毁前必须摘掉 JS 桥：桥对象持有宿主回调，
        //    留着会让网页在 WebView 销毁后仍能回调到已失效的界面。
        target.removeJavascriptInterface(SCORE_HOST_BRIDGE_NAME)
        target.settings.javaScriptEnabled = false
        target.destroy()
    }
}

/**
 * 供原生侧调用网页 API 的小工具。
 *
 * 网页暴露的接口（`assets/score/index.html` 里的 `window.MoreCheckScore`）：
 *   · `isLoggedIn()` → 是否已登录
 *   · `getUser()`    → `{ id, name, role, className }`（未登录返回 null）
 *   · `logout()`     → 主动退出登录
 *
 * 这些接口在网页版里同样存在（非嵌入模式也暴露，只是 `isEmbedded=false`），
 * 因此原生侧可以无差别调用，不存在"只在 App 里才有"的分支。
 */
object ScorePageBridge {

    private const val JS_IS_LOGGED_IN = "window.MoreCheckScore && window.MoreCheckScore.isLoggedIn()"

    /**
     * 取用户信息的表达式。
     *
     * ⚠️ 这里返回的是**对象本身**而不是 `JSON.stringify(...)` 的结果：
     * `evaluateJavascript` 的回调值是「表达式结果的 JSON 序列化」——
     * 对象会原样给出 `{"id":"u",...}`，而字符串会给成带引号且转义过的 `"{\"id\":\"u\"}"`。
     * 所以直接传对象，少一层反转义。
     */
    private const val JS_GET_USER =
        "(window.MoreCheckScore ? window.MoreCheckScore.getUser() : null)"

    private const val JS_LOGOUT = "window.MoreCheckScore && window.MoreCheckScore.logout()"

    /** 页面是否已登录。回调收到 Boolean。 */
    fun isLoggedIn(webView: WebView, callback: (Boolean) -> Unit) {
        webView.evaluateJavascript(JS_IS_LOGGED_IN) { value ->
            callback(value == "true")
        }
    }

    /** 取当前用户信息。回调收到 Map?（字段：id / name / role / className）。 */
    fun getUser(webView: WebView, callback: (Map<String, Any?>?) -> Unit) {
        webView.evaluateJavascript(JS_GET_USER) { value ->
            // 对象结果原样就是 JSON 文本；null 时是字符串 "null"
            val raw = value?.trim().orEmpty()
            callback(if (raw == "null") null else parseJsonObject(raw))
        }
    }

    /** 让网页主动退出登录。 */
    fun logout(webView: WebView) {
        webView.evaluateJavascript(JS_LOGOUT, null)
    }

    /**
     * 极简 JSON → Map 解析。
     *
     * ⚠️ 只服务于网页回传的扁平对象（4 个字段，全是字符串），
     *    不引第三方库、不求通用——`org.json` 在 Android 上有，但本地单元测试环境不可用，
     *    这里手写一份最小实现，避免为一屏页面增加依赖。
     */
    private fun parseJsonObject(raw: String): Map<String, Any?>? {
        if (raw.isBlank() || raw == "null") return null
        val out = HashMap<String, Any?>()
        var i = 0
        while (i < raw.length) {
            val keyStart = raw.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = raw.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val key = raw.substring(keyStart + 1, keyEnd)
            val colon = raw.indexOf(':', keyEnd)
            if (colon < 0) break
            var j = colon + 1
            while (j < raw.length && raw[j].isWhitespace()) j++
            if (j < raw.length && raw[j] == '"') {
                val valEnd = raw.indexOf('"', j + 1)
                if (valEnd < 0) break
                out[key] = raw.substring(j + 1, valEnd)
                i = valEnd + 1
            } else {
                val valEnd = raw.indexOf(',', j).let { if (it < 0) raw.length else it }
                val token = raw.substring(j, valEnd).trim()
                out[key] = when (token) {
                    "null" -> null
                    "true" -> true
                    "false" -> false
                    else -> token.toDoubleOrNull() ?: token
                }
                i = valEnd + 1
            }
        }
        return out
    }
}

/** logcat 标签：网页 console / 资源错误 / 宿主事件都打到这里。 */
private const val SCORE_WEB_LOG_TAG = "ScoreWebView"

/**
 * 注入给网页的桥对象名。
 *
 * 网页侧靠它判定"被嵌入"（`typeof window.MoreCheckHost !== 'undefined'`），
 * 注入与销毁两处必须用同一个名字。
 */
private const val SCORE_HOST_BRIDGE_NAME = "MoreCheckHost"

/**
 * 「实验室 → 成绩收集统计」。
 *
 * ## WebView 配置（每项理由）
 *
 * - `javaScriptEnabled = true`：整页交互与云 SDK 全是 JS。
 * - `domStorageEnabled = true`（**硬性**）：登录会话与 SDK 缓存都在 localStorage，
 *   不开会表现为"每次进来都要重新登录"。这是「登录后保留登录状态」能成立的前提。
 * - `allowFileAccess = false`：页面资源全部经 WebViewAssetLoader 映射，没有 file:// 访问，
 *   少一个攻击面（与今日待办、数独页同一口径）。
 * - `allowContentAccess = false`：页面不读 `content://`，最小权限。
 * - `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` **保持默认 false**：
 *   页面读不到本地文件；云请求是 SDK 拼出来的 https 绝对地址，不需要放开。
 * - `useWideViewPort = true` + `loadWithOverviewMode = true`：页面有标准 viewport meta。
 * - `mixedContentMode` 不动（默认 NEVER_ALLOW）：页面没有 https 页面引 http 资源的场景。
 * - `cacheMode = LOAD_DEFAULT`：默认值，让 WebView 正常走 HTTP 缓存。
 *
 * ## 返回键
 *
 * 先尝试回退网页历史（`canGoBack`），没有历史才退出本页。
 * 成绩页在 App 内没有整页跳转（OAuth 按钮已隐藏），所以正常情况 `canGoBack()` 恒为 false，
 * 这个分支只是保险——将来网页若引入站内跳转也不会把用户直接甩出页面。
 *
 * @param onBack 退出本页（交给导航层 `popBackStack`）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(
    onBack: () -> Unit
) {
    val holder = remember { ScoreWebViewHolder() }

    // 配色与今日待办同策略：网页固定浅色（--bg #F3F2F9 / 白卡），chrome 跟随应用主题。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // 状态栏压在 chrome（= 主题 surface）上 → 完全跟随主题；
    // 手势条那一带被内容区兜底的浅灰压着 → 恒深色图标。
    val lightStatusBarIcons: Boolean = !darkTheme
    val lightNavBarIcons = true

    val view = LocalView.current
    // assets → https 映射器：一次组合内复用，随 WebView 一起销毁（无状态，重建也无害）。
    val appContext = LocalContext.current.applicationContext
    val scoreAssetLoader = rememberScoreAssetLoader(appContext)
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

    // 兜底销毁，避免 WebView 泄漏（onRelease 已销毁过一次，这是二次保险）。
    DisposableEffect(Unit) {
        onDispose { holder.destroy() }
    }

    BackHandler {
        val wv = holder.view
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        // TopAppBar 用它绘制状态栏 inset 的背景，必须与标题栏同源。
        containerColor = chromeBackground,
        topBar = {
            TopAppBar(
                title = { Text("成绩收集统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 刷新：网页是云端数据，切班/换人后刷新一下最直观。
                    IconButton(onClick = { holder.view?.reload() }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 铺一层与网页 `--bg` 同值的浅灰（不是 `chromeBackground`）：
                // 深色主题下底部手势条区域若露出主题 `surface` 会有一条深色横条
                // 压在浅色网页下面（理由与今日待办同一份注释）。
                .background(Color(SCORE_NAVBAR_GUARD_COLOR))
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            AndroidView(
                // ⚠️ 底部让位用 safeDrawing 的 Bottom 侧**单源**取值（2026-10-29，理由同今日待办）：
                // 同时解决导航栏与软键盘两条带子，写法沿用 `AgentChatScreen` 里验证过的口径
                // （safeDrawing 的 Bottom = systemBars 与 IME 取 max，单一 inset、不叠加）：
                // 1. 导航栏：本页 edge-to-edge，WebView 原本铺到屏幕最底。网页的底部弹窗
                //    （新建考试 / 建班 / 加入班级等）是 `position: fixed; bottom: 0`，
                //    底边 = WebView 底边 → 底部「创建 / 确定」正好落进导航栏区域点不到。
                // 2. 键盘：弹窗里全是输入框（考试名称 / 满分 / 班级码），点一下键盘弹起就把
                //    整个弹窗盖住。打上这一层后 WebView 随键盘收缩，弹窗整体上移。
                // 下面 Box 仍铺满并垫着与 `--bg` 同值的浅灰，两条带子都看不出色差
                // （见 SCORE_NAVBAR_GUARD_COLOR）。
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        // 登录会话 / SDK 缓存在 localStorage——这是「登录后保留登录状态」
                        // 能成立的前提（也是必须走 https 映射、而不是 file:// 的根本原因）。
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // 与页面 `--bg` 同值的兜底色（首帧前露出）。刻意不跟随主题，
                        // 理由同今日待办：网页固定浅色，深色兜底会长期露出一圈色差。
                        setBackgroundColor(SCORE_BACKGROUND_COLOR)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        // ── 宿主桥：网页据此判定"被嵌入"，从而隐藏 OAuth 入口 ──
                        //
                        // ⚠️ `addJavascriptInterface` 必须在 loadUrl **之前**调用：
                        //    页面脚本在加载时就会读 `window.MoreCheckHost`，
                        //    晚一步注入就会错过判定（页面会显示 OAuth 按钮，
                        //    用户一点就跳出应用）。
                        addJavascriptInterface(ScoreHostBridge { event, payload ->
                            Log.i(SCORE_WEB_LOG_TAG, "[event] $event $payload")
                        }, SCORE_HOST_BRIDGE_NAME)
                        // ── 诊断：网页 console 与资源错误 → logcat ──────────────
                        //
                        // 排查"登录不上 / 页面空白"要用。tag = ScoreWebView。
                        // 这层只写 logcat，不影响任何行为，长期保留。
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.i(
                                    SCORE_WEB_LOG_TAG,
                                    "[console/${message.messageLevel()}] ${message.message()} " +
                                        "(${message.sourceId()}:${message.lineNumber()})"
                                )
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // assets → https 映射。必须在 loadUrl 之前装好：
                            // 首屏请求就会走 shouldInterceptRequest，装晚了会 404 → 白屏。
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return scoreAssetLoader.shouldInterceptRequest(request.url)
                            }

                            /**
                             * 主框架加载失败 → 回退到线上地址。
                             *
                             * 触发场景：本地副本没随包发出去（拷贝 assets 时漏了），
                             * 或映射路径写错。此时页面会是白屏/错误页。
                             * 线上与本地是同一份页面、同一域名，**登录态共享**，
                             * 所以回退不会让用户掉登录。
                             */
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                val isMainFrame = request?.isForMainFrame == true
                                if (isMainFrame) {
                                    Log.e(
                                        SCORE_WEB_LOG_TAG,
                                        "[main-frame-error] ${error?.errorCode} ${error?.description} ${request?.url}"
                                    )
                                    if (request?.url?.toString()?.startsWith(SCORE_PAGE_URL) == true) {
                                        Log.w(SCORE_WEB_LOG_TAG, "[fallback] 本地副本不可用，回退到线上地址")
                                        view.loadUrl(SCORE_REMOTE_URL)
                                    }
                                } else {
                                    Log.w(
                                        SCORE_WEB_LOG_TAG,
                                        "[resource-error] ${error?.errorCode} ${error?.description} ${request?.url}"
                                    )
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: android.webkit.WebResourceResponse
                            ) {
                                Log.w(
                                    SCORE_WEB_LOG_TAG,
                                    "[http-error] ${errorResponse.statusCode} ${request.url}"
                                )
                            }
                        }

                        // 只在首次创建时加载；update 刻意不 loadUrl
                        // （任何一次重组都走 update，在那里加载会重置页面状态）。
                        loadUrl(SCORE_PAGE_URL)

                        // 交给 holder 保管，供销毁路径统一处理。
                        holder.view = this
                    }
                },
                // 刻意留空，见 factory 内对 loadUrl 的说明。
                update = { },
                // 与 DisposableEffect 是同一条销毁路径的两次保险；
                // 必须走 holder.destroy()（先置空再销毁，避免双重 destroy）。
                onRelease = { holder.destroy() }
            )
        }
    }
}

/**
 * 为成绩页构造 assets → https 的映射器。
 *
 * `WebViewAssetLoader` 本身无状态（一张前缀 → handler 的表），
 * 每次进页面重建没有副作用；用 `remember(context)` 保证一次组合内复用。
 */
@Composable
private fun rememberScoreAssetLoader(context: android.content.Context): WebViewAssetLoader =
    remember(context) {
        WebViewAssetLoader.Builder()
            // ⚠️ 域名必须是云网关白名单里的那个（见 SCORE_ORIGIN_HOST）：
            // 默认域名 appassets.androidplatform.net 会让所有 /.cloud 请求被 403。
            .setDomain(SCORE_ORIGIN_HOST)
            .addPathHandler(SCORE_ASSET_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
