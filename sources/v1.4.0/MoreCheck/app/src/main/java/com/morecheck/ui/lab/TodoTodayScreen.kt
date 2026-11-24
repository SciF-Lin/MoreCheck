// 「实验室 → 今日待办」：内嵌自用户提供的 todo-app 单页应用（WebView 承载）。
//
// 本页与 `PomodoroScreen` 同构（WebView + 标题栏容器），但有三处关键不同，
// 都源于 todo-app 与番茄钟素材的本质差异——**它不是自包含单文件，而是"网页 + 云后端"**：
//
// 1. **云请求不需要改基址（2026-10-23 踩坑后修正）**：SDK 的 Auth/Database/Storage
//    三个模块都是 `${endpoint}${R.xxx}` **显式拼接**（`R.auth = "/.cloud" + "/auth"`），
//    页面又显式传入了 `endpoint`（2026-10-29 起是 `morecheck-sync` 那个域名），
//    因此 SDK 发出的请求**从来都是绝对 URL**——file:// 下照样可用，
//    前提只是网关 CORS 放行（已实测：`Access-Control-Allow-Origin` 原样回显，
//    连 `Origin: null` 都回显，preflight 允许 Authorization / x-wb-webapp-access-key）。
//    ⚠️ 曾把 SDK 里的 `"/.cloud"` 改写成绝对 URL，结果 `endpoint + R.auth` 双拼出
//    `https://hosthttps//...` 的非法主机名（net::ERR_NAME_NOT_RESOLVED），
//    表现正是用户反馈的"无法获取验证码"。**SDK 必须保持原版**。
// 2. **登录方式**：todo-app 提供 OAuth（WorkBuddy / 微信授权）与验证码两条登录路。
//    （2026-10-29 起还有第三条、也是实际最常用的那条：**直接用原生会话**，
//     见 `TodoBridge.loadSession`。）
//    OAuth 要整页跳第三方域名再 redirect 回来，`file:///android_asset` 的回调回不来，
//    所以 assets 副本里**隐藏了两个授权按钮**，只留验证码登录（纯接口调用，可用）。
//    两边是同一账号体系：网页端用过的账号在这里输同一手机号验证码即看到同一份待办。
//    服务端要求手机号为 E.164 格式（`+86 134…`），SDK 的 normalizePhone 已自动补 `+86 `，
//    用户直接输 11 位裸号即可。
// 3. **外链资源**：副本里删除了 PWA 相关的旁支（sw 注册自带 https 判定天然短路；
//    `initWechat()` 里的 `fetch('./mini.json')` 在 file:// 下必失败，已整体短路）。
//
// ── 2026-10-28 接入改造（详见 `integration/todo-web-v2/INTEGRATION.md`）──
//
// 网页侧新增了 5 项能力，其中两项**必须由宿主提供**才能落地，故本页新增：
//
// - **原生桥**（[TodoBridge]）：选图（图片备注 / 头像）、会话长期保存、
//   把 App 侧昵称头像带给网页、退出与提示。
// - **图片选择**：`ActivityResultContracts.PickVisualMedia`（系统 PhotoPicker，
//   不需要任何存储权限）。同时补上 [WebChromeClient.onShowFileChooser]——
//   没有它，网页里的 `<input type="file">` 在 WebView 里点了没反应（静默失败）。
//
// ⚠️ **origin 那套一个字都没改**（见 [TODO_ORIGIN_HOST]）：改域名会让所有
// `/.cloud` 请求变成 403，症状是"点获取验证码提示接口错误"，极难定位。

package com.morecheck.ui.lab

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.morecheck.di.appContainer
import com.morecheck.util.findActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 今日待办页所在云应用的**主机名**（= 页面的 origin）。
 *
 * ⚠️ 2026-10-26 修复「今日待办无法登录」，这里是关键：云网关对每个 client
 * （`x-wb-webapp-access-key`）绑定了一张 Origin 白名单，实测（curl，同一份 payload）：
 *
 * | 请求头 Origin | 结果 |
 * | --- | --- |
 * | `https://appassets.androidplatform.net`（上一版的映射域名） | **403** `access_denied: the request origin is not allowed for this client` |
 * | `https://today-todo-50052.app.workbuddy.host`（应用自己的域名） | 200，正常下发 verification_id |
 * | `null`（`file://` 时代） | 放行（打到限频 429，说明已经过了 origin 校验） |
 *
 * 也就是说：2026-10-24 为了修「重复登录」把页面从 `file://` 搬到
 * `appassets.androidplatform.net` 时，**origin 同时被换出了白名单**，
 * 于是"登录会丢"变成了"根本登录不上"（点获取验证码 → 403 → 页面提示接口错误）。
 * `WebViewAssetLoader` 允许自定义域名（[WebViewAssetLoader.Builder.setDomain]），
 * 因此这里把 assets 继续映射成本地资源，但**域名填应用自己的域名**：
 * 页面 origin 与云接口同源 → 网关放行，且 `localStorage` 仍是稳定 origin（会话能持久化），
 * 两个目标同时满足。除此之外的请求（`/.cloud/...`）不由 loader 处理，照常走网络。
 *
 * ## 2026-10-29 账号合并：域名换成打卡云应用
 *
 * 待办页不再连 `today-todo-50052`，而是连 `morecheck-sync`（与原生同一个云应用、
 * 同一个账号）。域名因此**从 `CloudConfig.ENDPOINT` 推导**，不再写死字符串——
 * 换云环境只改 `CloudConfig` 一处，本页跟着走，不会再出现"两个常量不同步"
 * （那种不同步的表现是登录全部 403，且极难定位）。
 *
 * 网关白名单是**按 client 绑定的**：白名单里的域名属于哪个应用，就必须配哪个应用的 Key。
 * 页面 `publicConfig` 与 `CloudConfig` 一起改成 `morecheck-sync` 之后才成立。
 */
private val TODO_ORIGIN_HOST: String =
    android.net.Uri.parse(com.morecheck.data.cloud.CloudConfig.ENDPOINT).host
        ?: "morecheck-sync.app.workbuddy.host"

/**
 * 今日待办页要加载的页面地址（`app/src/main/assets/todo/index.html`）。
 *
 * ## ⚠️ 为什么用 https 映射地址而不是 `file:///android_asset/...`（2026-10-24 修复）
 *
 * 用户报告「今日待办登录后再次进入要重复登录」。根因是 **origin**：
 *
 * - `file://` 页面在 Chromium 里是**不透明 origin（opaque origin）**，
 *   `localStorage` 的写入会抛 `SecurityError`；
 *   本项目里数独页早先就踩过同一件事（见 `SudokuScreen` 的 KDoc：
 *   "数独需要安全上下文才能用 localStorage"）。
 * - todo-app 的登录会话由 WorkBuddy SDK 存在 `localStorage`（键前缀
 *   `workbuddy-cloud.session.`）。SDK 的 `resolveStorage()` 在读写失败时**静默回落到
 *   内存存储**——于是同一次进程内看起来"已登录"，WebView 一销毁（离开本页）
 *   会话就没了，下次进来必须重新登录。
 * - 修法与数独页完全一致：用 `WebViewAssetLoader` 把 assets 映射到一个 https 域名，
 *   页面获得稳定 origin，localStorage 正常持久化。
 *
 * **域名选谁**见 [TODO_ORIGIN_HOST]：必须是云网关放行的应用域名，
 * 不能用 loader 的默认域名（会 403）。
 *
 * 页面自身的相对引用（`./workbuddy-cloud-sdk.js`、`icon-192.png`）在该映射下
 * 照常解析；云请求本来就是 SDK 拼出来的绝对地址，不受影响。
 */
private val TODO_PAGE_URL: String
    get() = "https://$TODO_ORIGIN_HOST/assets/todo/index.html"

/**
 * todo 页面所在的 assets 子目录前缀。
 *
 * ⚠️ 前缀必须带首尾斜杠：`shouldInterceptRequest` 收到的是完整 URL，
 * `WebViewAssetLoader` 按前缀匹配路径；写成 `assets/todo` 会匹配不上 → 整页 404 白屏。
 */
private const val TODO_ASSET_PATH_PREFIX = "/assets/"

/**
 * 为 todo 页构造 assets → https 的映射器。
 *
 * `WebViewAssetLoader` 本身无状态（一张前缀 → handler 的表），
 * 每次进页面重建没有副作用；用 `remember(context)` 保证一次组合内复用。
 */
@Composable
private fun rememberTodoAssetLoader(context: android.content.Context): WebViewAssetLoader =
    remember(context) {
        WebViewAssetLoader.Builder()
            // ⚠️ 域名必须是云网关白名单里的那个（见 TODO_ORIGIN_HOST 的表格）：
            // 默认域名 appassets.androidplatform.net 会让所有 /.cloud 请求被 403。
            .setDomain(TODO_ORIGIN_HOST)
            .addPathHandler(TODO_ASSET_PATH_PREFIX, WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

/**
 * 页面主底色：与 `assets/todo/index.html` 里 `:root` 的 `--bg`（#F2F4F8）同值。
 *
 * 用途只有一处——WebView 的兜底色（页面首帧绘制前露出的一层）。
 * ⚠️ 必须不透明（`FF` 前缀）；⚠️ 改页面 `--bg` 时必须同步改这里，
 * 否则首帧会闪出一圈色差（与 [POMODORO_BACKGROUND_COLOR] 同一条维护约定）。
 */
private const val TODO_BACKGROUND_COLOR = 0xFFF2F4F8.toInt()

/**
 * 手势条那一带的兜底色：与 [TODO_BACKGROUND_COLOR] 同值，理由同番茄页的
 * `POMODORO_NAVBAR_GUARD_COLOR`——内容区铺到屏幕最底时，深色主题下若露出
 * 主题 `surface` 会有一条深色横条压在浅色网页下面；铺一层与网页 `--bg`
 * 同值的浅灰，手势条区域就与页面连成一片。浅色主题下该色与 `surface` 接近，无副作用。
 */
private const val TODO_NAVBAR_GUARD_COLOR = 0xFFF2F4F8.toInt()

/**
 * 单个 WebView 实例的持有者。
 *
 * 与 `PomodoroScreen` 等页的同名类**刻意各写一份**（本项目"一屏一个自洽实现"的纪律，
 * 理由见 `SnakeScreen` 同名 KDoc）。`destroy()` 先置空再销毁，
 * 两条销毁路径谁先到都只销毁一次。
 */
private class TodoWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 今日待办」。
 *
 * ## 原生与网页的分工
 *
 * **网页负责全部业务**：验证码登录、待办增删勾、剩余计数、云端保存
 * （`workbuddy-cloud-sdk.js` → `/.cloud/auth` 与 `/.cloud/database/rest`），
 * 外加 2026-10-28 新增的五项：待办永久保留、个人简介、图片备注、
 * 登录态保持、以及对外暴露的原生桥接口。
 *
 * **原生负责容器与三件网页做不了的事**：
 * 1. 标题栏与返回、系统栏图标明暗、WebView 生命周期；
 * 2. **选图**（系统 PhotoPicker，见 [TodoBridge]）；
 * 3. **会话长期保存**（网页 localStorage 被清后，宿主用存档的 refresh token 恢复）。
 *
 * 原生**不读**也不写网页的其它业务状态（没有番茄那样的存档桥——
 * 待办的数据本来就在云端，不需要经原生搬运）。
 *
 * ## WebView 配置（每项理由）
 *
 * - `javaScriptEnabled = true`：整页交互与云 SDK 全是 JS。
 * - `domStorageEnabled = true`（**硬性**）：登录会话与 SDK 缓存都在 localStorage，
 *   不开会表现为"每次进来都要重新登录"。
 * - `allowFileAccess = false`：页面资源全部经 assets 映射覆盖（`./workbuddy-cloud-sdk.js` 是
 *   `<script src>` 静态资源，不受这个开关限制——它只管 fetch/XHR 读 file://），
 *   而云请求已改写为 https 绝对地址，不需要放开。
 * - `allowContentAccess = false`：页面不读 `content://`，最小权限。
 * - `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` **保持默认 false**：
 *   高危开关一律不开。
 * - `useWideViewPort = true` + `loadWithOverviewMode = true`：页面有标准的
 *   `width=device-width` viewport meta，跟随番茄 / 数独的实测口径。
 * - `mixedContentMode` 不动（默认 NEVER_ALLOW）：页面没有 https 页面引 http 资源的场景。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出本页。页面内没有 web 层导航（OAuth 按钮已被隐藏、
 * 唯一可能的整页跳转已被短路），`canGoBack()` 恒为 false，
 * 因此不做"先回退网页历史"的分支（与番茄页同一口径）。
 *
 * @param onBack 退出本页（交给导航层 `popBackStack`）。
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTodayScreen(
    onBack: () -> Unit
) {
    val holder = remember { TodoWebViewHolder() }

    // 配色与番茄同策略：网页固定浅色（--bg #F2F4F8 / 白卡），chrome 跟随应用主题。
    // 深色主题下是"深色标题栏 + 浅色网页"的两截观感——与番茄页同一接受口径，
    // 不给固定浅色的素材注入反色。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // 状态栏压在 chrome（= 主题 surface）上 → 完全跟随主题；
    // 手势条那一带被内容区兜底的浅灰（[TODO_NAVBAR_GUARD_COLOR]）压着 → 恒深色图标。
    // 离开时把进入前的取值原样写回（与番茄页同一做法，避免把本页取值泄漏给其它页）。
    val lightStatusBarIcons: Boolean = !darkTheme
    val lightNavBarIcons = true

    val view = LocalView.current
    // assets → https 映射器：一次组合内复用，随 WebView 一起销毁（无状态，重建也无害）。
    val appContext = LocalContext.current.applicationContext
    val todoAssetLoader = rememberTodoAssetLoader(appContext)
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

    // ── 原生桥与选图（2026-10-28 接入改造）────────────────────────────
    //
    // 桥的回调要碰 UI（起 Activity / 弹 Toast / 退出），所以统一投到主线程 scope。
    val scope = rememberCoroutineScope()

    // 选图请求的两条来源：
    // 1. 网页调桥的 `pickImage(callbackId)` → 用 callbackId 回传 data URL；
    // 2. 网页里 `<input type=file>` 触发 WebView 的文件选择回调 → 用 URI 回传。
    // 两条共用一个 launcher，用 pendingPickId / pendingFileCallback 区分。
    var pendingPickId by remember { mutableStateOf<String?>(null) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // ⚠️ 变量名不要叫 `settings`：WebView 的 `settings`（getSettings()）与它同名，
    //    在 `WebView(ctx).apply { … }` 里写 `settings.xxx` 会解析到**这个仓库**上，
    //    编译期报 "Unresolved reference 'javaScriptEnabled'"（踩坑手册 5.19 同源问题）。
    val settingsRepository = remember(appContext) { appContext.appContainer.settingsRepository }
    val authRepository = remember(appContext) { appContext.appContainer.authRepository }

    // ⚠️ 桥是 remember 复用的（它是 WebView 的伴生对象），因此它的回调里
    //    **不能**直接捕获会变的值。onBack 用 rememberUpdatedState 取最新值，
    //    否则父层换了回调实例时，桥里拿到的还是第一次那个。
    val currentOnBack by rememberUpdatedState(onBack)

    // ⚠️ 桥对象用 remember 复用（它是 WebView 的伴生对象，重复注册没有意义）；
    //    但它的回调里要用到最新的 holder / onBack，所以这些依赖走 holder 与
    //    直接引用（onBack 在本函数里是稳定参数），不缓存旧值。
    val cloudAuthClient = remember(appContext) { appContext.appContainer.cloudAuthClient }

    /**
     * 网页里的图片灯箱是否开着（2026-10-30 需求 2）。
     *
     * 页面在 openViewer / closeViewer 里各调一次桥的 `setViewerOpen`，宿主只做记账——
     * 这样返回键的判断是**同步**的，不用先问一次网页再等回调（异步那套在返回键上
     * 会漏一帧：用户已经看到页面被退出去了）。
     */
    val viewerOpen = remember { mutableStateOf(false) }

    val bridge: TodoBridge = remember(appContext) {
        TodoBridge(
            context = appContext,
            scope = scope,
            onPickImage = { callbackId -> pendingPickId = callbackId },
            // 原生会话 → 网页（2026-10-29 账号合并）：取的是 CloudAuthClient 那一份，
            // 序列化成网页 SDK 认识的结构（accessToken / refreshToken / expiresAt / user）。
            sessionProvider = {
                // ⚠️ runBlocking：@JavascriptInterface 必须同步返回，而 currentSession()
                // 是挂起函数（首次要从 DataStore 读）。阻塞的是 **JavaBridge 线程**，
                // 不是主线程，与上面 profileProvider 同一口径。
                runBlocking {
                    cloudAuthClient.currentSession()?.let { session ->
                        org.json.JSONObject().apply {
                            put("accessToken", session.accessToken)
                            put("refreshToken", session.refreshToken)
                            put("expiresAt", session.expiresAt)
                            put("user", org.json.JSONObject().apply {
                                put("id", session.userId)
                                put("phone", session.phone ?: "")
                                // raw.sub / raw.phone_number：SDK 认这两个字段名。
                                put("raw", org.json.JSONObject().apply {
                                    put("sub", session.userId)
                                    put("phone_number", session.phone ?: "")
                                })
                            })
                        }.toString()
                    }
                }
            },
            // 网页登录 → 原生采纳：一个账号在哪儿登录都算登录。
            adoptSession = { json ->
                scope.launch {
                    val ok = cloudAuthClient.adoptSession(json)
                    if (ok) {
                        // 本地登录态（「我的」页显示"已登录"、顶部头像卡片）也要同步，
                        // 否则会出现"云会话有了、界面还是未登录"。
                        val phone = runCatching {
                            org.json.JSONObject(json).optJSONObject("user")
                                ?.optString("phone", "")?.takeIf { it.isNotBlank() }
                        }.getOrNull()
                        authRepository.onCloudLoginSucceeded(phone = phone ?: "", cloudName = null)
                    }
                }
            },
            // 网页点退出 → 原生一起退（一个账号，处处退出）。
            onSignOut = {
                scope.launch {
                    runCatching { cloudAuthClient.signOut() }
                    authRepository.logout()
                }
            },
            profileProvider = {
                // ⚠️ runBlocking：@JavascriptInterface 必须同步返回，而 DataStore 的
                // 读是 Flow。这里阻塞的是 **JavaBridge 线程**（不是主线程），
                // 不存在 ANR 风险——与 GameDataBridge.pendingState 同一口径。
                runBlocking {
                    TodoNativeProfile(
                        nickname = authRepository.nickname.first().orEmpty(),
                        avatarPath = settingsRepository.avatarPath.first().orEmpty(),
                        userId = authRepository.userId.first().orEmpty()
                    )
                }
            },
            onToast = { message -> android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show() },
            onExit = { currentOnBack() },
            // 长按图片 → 保存 / 分享（2026-10-31 需求 4）：两者都只能由原生做。
            onSaveImage = { url ->
                scope.launch {
                    val error = TodoImageSaver.save(appContext, url)
                    android.widget.Toast.makeText(
                        appContext,
                        error ?: "已保存到相册（Pictures/MoreCheck）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onShareImage = { url ->
                scope.launch {
                    val error = TodoImageSaver.share(appContext, url)
                    if (error != null) {
                        android.widget.Toast.makeText(
                            appContext, error, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            // 灯箱开合（2026-10-30 需求 2）：宿主据此决定返回键归谁。
            onViewerState = { open -> viewerOpen.value = open }
        )
    }

    // 选图 launcher：系统 PhotoPicker（不需要任何存储权限）。
    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val callbackId = pendingPickId
        val fileCallback = pendingFileCallback
        pendingPickId = null
        pendingFileCallback = null
        if (uri == null) {
            // 用户取消选图。
            //
            // ⚠️ 桥这条路**必须**回一个空串：页面那边的 `Native.pickImage()` 返回的是
            // 一个 Promise，只认 `window.__cimTodoImage(callbackId, …)` 这一个出口。
            // 不回的话 Promise 永远 pending，界面上就是"点了选图，一直转圈/什么都不发生"。
            // 约定里 `""` = 取消或解码失败（页面提示"未选择图片"），`null` 才表示没有桥。
            // 初版这里只处理了 `<input type=file>` 的 fileCallback 就 return，真机上
            // 取消一次选图后页面就一直挂着——已修。
            if (callbackId != null) bridge.deliverImage(callbackId, "")
            fileCallback?.onReceiveValue(null)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // ① 先试**原图字节直传**（2026-10-31 需求 3：用户反馈"不是原图"）。
            //    原来无论如何都要 decode → JPEG q85 → 网页 canvas 再压一次 1600/q0.82，
            //    两次有损重编码 + 尺寸回缩，长按放大就是一片糊。现在只要原文件
            //    不超过 8MB 且是 WebView 能认的图片格式，就原样给页面，零损失。
            // ② 走不了原图（太大 / HEIC / 读不出）才退回压缩，尺寸提到 2048/q90。
            val dataUrl = TodoBridge.encodeOriginalPicked(appContext, uri) ?: run {
                val bitmap = TodoBridge.decodePicked(appContext, uri)
                if (bitmap == null) {
                    // 解码失败（图片损坏 / 内存不足）：给网页一个空回调，
                    // 页面会提示"未选择图片"而不是一直转圈。
                    if (callbackId != null) bridge.deliverImage(callbackId, "")
                    fileCallback?.onReceiveValue(null)
                    return@launch
                }
                val encoded = TodoBridge.encodeDataUrl(bitmap)
                bitmap.recycle()
                encoded
            }
            if (callbackId != null) bridge.deliverImage(callbackId, dataUrl)
            fileCallback?.onReceiveValue(arrayOf(uri))
        }
    }

    // ── 桥请求选图 → 真正拉起系统 PhotoPicker（2026-10-28 接入修复）─────────
    //
    // ⚠️ 这一步**不能省**：桥的 `onPickImage` 只是把 `callbackId` 记进
    //    `pendingPickId`（它跑在 JavaBridge 线程，碰不了 ActivityResultLauncher），
    //    真正的 `launch` 必须由主线程的副作用来做。
    //
    // 初版漏了这一句，症状极具迷惑性：`window.__CIM_TODO__.version` 在、
    // `pickImage()` 也返回正常（不抛错），但**屏幕上什么都不发生**——
    // 选图按钮点了没反应，也没有任何报错。真机上先看焦点窗口没变、
    // 再看 `pendingPickId` 只有赋值没有消费者，才定位到这里。
    //
    // 用 `LaunchedEffect(pendingPickId)` 而不是 `if (pendingPickId != null) launch(...)`：
    // 后者在重组时可能重复 launch（一次请求弹出两个选图器）。
    LaunchedEffect(pendingPickId) {
        val id = pendingPickId ?: return@LaunchedEffect
        // 与 onShowFileChooser 那条路共用一个 launcher；
        // 若此时恰好还有 input 兜底的回调在等，先清掉，避免两边串号。
        Log.i("TodoBridge", "[pick] launch PhotoPicker for $id")
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 兜底销毁，避免 WebView 泄漏（onRelease 已销毁过一次，这是二次保险）。
    DisposableEffect(Unit) {
        onDispose { holder.destroy() }
    }

    // 返回键：图片灯箱开着 → 只关灯箱；否则退出本页（2026-10-30 需求 2）。
    //
    // 原来一律 onBack()，于是"看大图时按返回"直接把整个待办页退掉——用户以为自己
    // 只是关掉图片。关闭灯箱要走页面自己的 closeViewer（它还要清 src、复位
    // viewerImageId），所以这里注入一段 JS 而不是改宿主状态。
    BackHandler {
        if (viewerOpen.value) {
            holder.view?.evaluateJavascript(
                "window.__cimTodoCloseViewer&&window.__cimTodoCloseViewer()",
                null
            )
        } else {
            onBack()
        }
    }

    Scaffold(
        // TopAppBar 用它绘制状态栏 inset 的背景，必须与标题栏同源。
        containerColor = chromeBackground,
        topBar = {
            TopAppBar(
                title = { Text("今日待办") },
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
                // 铺一层与网页 `--bg` 同值的浅灰（不是 `chromeBackground`）：
                // 本 Box 铺到屏幕最底，深色主题下底部手势条区域若露出主题 `surface`
                // 会有一条深色横条压在浅色网页下面（理由与番茄页同一份注释）。
                .background(Color(TODO_NAVBAR_GUARD_COLOR))
                // Scaffold 给的 bottom inset 让回给内容：WebView 铺到屏幕最底。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            AndroidView(
                // ⚠️ 底部让位用 safeDrawing 的 Bottom 侧**单源**取值（2026-10-29）：
                // 这一层同时解决两件事，写法沿用 `AgentChatScreen` 里踩过两轮后定下的口径
                // （`navigationBarsPadding() + imePadding()` 会双重让位，只留 imePadding()
                // 键盘收起时又被导航条压住；safeDrawing 的 Bottom 是 systemBars 与 IME 的
                // 并集取 max，单一 inset、无叠加）：
                //
                // 1. **导航栏**：本页 edge-to-edge，WebView 原本铺到屏幕最底、连三键导航栏
                //    那一带都盖在下面。网页的底部弹窗（个人简介等）是 `position: fixed;
                //    bottom: 0`，底边 = WebView 底边 → 正好落进导航栏区域，底部按钮被系统
                //    吃掉触摸，点不到。
                // 2. **软键盘**：弹窗里有输入框（昵称 / 个性签名），点一下键盘弹起，
                //    弹窗整体被键盘盖住——不打这一层的话，连"取消/保存"都摸不到。
                //
                // 下面那层 Box 仍铺满全屏并垫着与 `--bg` 同值的浅灰，所以这两条带子
                // 都看不出色差（见 TODO_NAVBAR_GUARD_COLOR）。
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // ⚠️ 全部写 `this.settings.`：外层有个同名的仓库变量（见上面的说明），
                        //    省略 this 就会解析到仓库上，编译期直接报未解析引用。
                        this.settings.javaScriptEnabled = true
                        // 登录会话 / SDK 缓存在 localStorage（见 TODO_PAGE_URL 的 KDoc：
                        // 这也是必须走 https 映射、而不是 file:// 的根本原因）。
                        this.settings.domStorageEnabled = true
                        // 页面资源全部经 WebViewAssetLoader 映射，不再有 file:// 访问，
                        // 因此与数独页同一口径：**关掉** file 访问，少一个攻击面。
                        this.settings.allowFileAccess = false
                        this.settings.allowContentAccess = false
                        // 与页面 `--bg` 同值的兜底色（首帧前露出）。刻意不跟随主题，
                        // 理由同番茄页：网页固定浅色，深色兜底会长期露出一圈色差。
                        setBackgroundColor(TODO_BACKGROUND_COLOR)
                        this.settings.useWideViewPort = true
                        this.settings.loadWithOverviewMode = true

                        // ── 原生桥注册（2026-10-28）──────────────────────────
                        //
                        // ⚠️ 必须在 loadUrl **之前**调用：页面脚本一跑就会探测
                        // `window.__CIM_TODO__`，晚注册等于这一页没有桥。
                        addJavascriptInterface(bridge, TodoBridge.JS_NAME)
                        // 桥需要把选好的图回传给页面 → 借 WebView 注入 JS。
                        bridge.javaScriptCallback = { script ->
                            holder.view?.evaluateJavascript(script, null)
                        }

                        // ── 诊断：网页 console 与资源错误 → logcat ──────────────
                        //
                        // 2026-10-23：「无法获取验证码」的排查要用。网页 JS 的报错
                        // （console.error / 未捕获异常）与资源加载失败默认不会出现在
                        // logcat，没有这两行就只能盲猜。tag = TodoWebView。
                        // 诊断完成后这层保留：它只写 logcat，不影响任何行为。
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                Log.i(
                                    "TodoWebView",
                                    "[console/${message.messageLevel()}] ${message.message()} " +
                                        "(${message.sourceId()}:${message.lineNumber()})"
                                )
                                return true
                            }

                            /**
                             * 网页里的 `<input type="file">` 在 WebView 里需要一个宿主实现，
                             * 否则**点了完全没反应**（没有任何报错）。
                             *
                             * 待办页优先走原生桥选图，这个回调只是兜底
                             * （例如网页在没有桥的环境里退化到 input）。
                             */
                            override fun onShowFileChooser(
                                view: WebView,
                                callback: ValueCallback<Array<Uri>>,
                                params: FileChooserParams
                            ): Boolean {
                                pendingFileCallback = callback
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // assets → https 映射（2026-10-24 修复「重复登录」的核心）。
                            // ⚠️ 必须在 loadUrl 之前装好：首屏请求就会走
                            // shouldInterceptRequest，装晚了会 404 → 白屏。
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return todoAssetLoader.shouldInterceptRequest(request.url)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    Log.e(
                                        "TodoWebView",
                                        "[main-frame-error] ${error?.errorCode} ${error?.description} ${request?.url}"
                                    )
                                } else {
                                    Log.w(
                                        "TodoWebView",
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
                                    "TodoWebView",
                                    "[http-error] ${errorResponse.statusCode} ${request.url}"
                                )
                            }
                        }

                        // 只在首次创建时加载；update 刻意不 loadUrl（与番茄页同一理由：
                        // 任何一次重组都走 update，在那里加载会重置页面状态）。
                        loadUrl(TODO_PAGE_URL)

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
