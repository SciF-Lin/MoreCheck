package com.morecheck.ui.web.bili

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.morecheck.R
import com.morecheck.data.net.BilibiliRepository
import com.morecheck.ui.common.WebPageNavBarIcons
import com.morecheck.ui.lab.TodoImageSaver
import com.morecheck.ui.web.WebViewHolder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B 站登录页。两种方式：**扫码登录**（默认，走 passport 二维码接口）与
 * **账号登录**（内嵌官方网页，用户自己走密码/短信）。
 *
 * ## 为什么扫码是默认
 *
 * 官方 App 的登录页默认就是扫码：不用在第三方网页里输密码，也不受 web 登录的
 * 风控/验证码影响。二维码只依赖两个公开接口（generate / poll），成功时 B 站直接在
 * 轮询结果里返回带 `SESSDATA` 的跳转 URL，与网页登录拿到的 Cookie 完全等价。
 *
 * ## 账号登录（WebView）
 *
 * 仍然保留作为兜底：二维码接口若被风控，用户还能用官方网页登录，点右上角「完成」
 * 时从 [CookieManager] 抓 `SESSDATA / bili_jct / DedeUserID` 保存。
 *
 * ### 深色模式
 *
 * 官方登录页只有白底。用户要求「背景随深色模式变化」，因此深色下给页面注入一段
 * 反色 CSS（`invert + hue-rotate`，图片再翻回来）。这是第三方页面做深色最稳的办法——
 * 不需要知道它的 DOM 结构，也不会因为类名改版而失效。
 *
 * ### 「我已阅读并同意隐私政策」浮层
 *
 * 该弹窗是 B 站网页自己弹的、居中盖住内容。这里注入脚本在页面加载后自动点掉
 * 「同意」，用户不会再看到一个挡住半个屏幕的浮层。App 侧的隐私口径见 [BiliLoginScreen]
 * 上方的说明（Cookie 明文存本机）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BiliLoginScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    // 网页恒定白底：导航栏图标强制深色（需求 3）。
    WebPageNavBarIcons()
    val messageSink = rememberUpdatedState(onMessage)
    val holder = remember { WebViewHolder() }

    // 深浅跟随**应用主题**（用户可在设置里强制），不跟系统。
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f

    // 0 = 扫码登录，1 = 账号登录（网页）。默认扫码，与官方 App 一致。
    var tab by remember { mutableIntStateOf(0) }

    var progress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Cookie 嗅探兜底：记录请求头里出现过的含 SESSDATA 的 Cookie 串。
    var sniffedCookie by remember { mutableStateOf<String?>(null) }

    val loginUrl = "https://passport.bilibili.com/login" +
        "?gourl=https%3A%2F%2Fm.bilibili.com%2F"

    /** 把两份 Cookie 串合并去重（同名 key 后者覆盖前者）。 */
    fun mergeCookies(vararg sources: String?): String {
        val map = linkedMapOf<String, String>()
        sources.filterNotNull().forEach { raw ->
            raw.split(';').forEach { pair ->
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) return@forEach
                val key = trimmed.substringBefore('=')
                if (key.isNotBlank()) map[key] = trimmed
            }
        }
        return map.values.joinToString("; ")
    }

    /** 读取并保存登录态；成功返回 true。 */
    fun captureLogin(): Boolean {
        val cookieManager = CookieManager.getInstance()
        val domains = listOf(
            "https://passport.bilibili.com",
            "https://m.bilibili.com",
            "https://www.bilibili.com",
            "https://api.bilibili.com"
        )
        val fromManager = domains.mapNotNull { runCatching { cookieManager.getCookie(it) }.getOrNull() }
        val merged = mergeCookies(sniffedCookie, *fromManager.toTypedArray())
        if (!merged.contains("SESSDATA=")) {
            messageSink.value("还没有检测到登录状态，请先在页面里完成登录，再点右上角「完成」")
            return false
        }
        BilibiliRepository.saveSessionCookie(context, merged)
        runCatching { cookieManager.flush() }
        return true
    }

    BackHandler { onBack() }

    DisposableEffect(Unit) {
        onDispose { holder.destroy() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 顶栏在状态栏下方：与播放页同一口径，绝不让内容压住通知栏。
            .statusBarsPadding()
            // 底部同理：扫码页最后一行提示原来正好压在系统手势条上。
            .navigationBarsPadding()
    ) {
        // ---- 52dp 顶栏（照参考 activity_login 的三件套尺寸）----
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(start = 4.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painterResource(R.drawable.bili_ic_back),
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                "登录哔哩哔哩",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 「完成」只在网页登录态有意义（扫码是自动完成的），避免误解。
            if (tab == 1) {
                Text(
                    "完成",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (captureLogin()) {
                                messageSink.value("登录成功，已保存登录状态")
                                onLoggedIn()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        // ---- 两个登录方式（下划线 tab，与播放页「简介/评论」同一套语汇）----
        //
        // ⚠️ 每个 tab 用 `fillMaxHeight` 而不是 `fillMaxSize`：Row 里 fillMaxSize 会
        // 吃掉整行宽度，第二个 tab 直接被挤出屏幕（第一版就是这样，只剩「扫码登录」）。
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("扫码登录", "账号登录").forEachIndexed { idx, label ->
                val on = tab == idx
                Column(
                    Modifier
                        .fillMaxHeight()
                        .clickable { tab = idx }
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (on) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                }
                            )
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        if (tab == 0) {
            QrLoginPane(
                onLoggedIn = onLoggedIn,
                onMessage = { messageSink.value(it) }
            )
        } else {
            // 加载进度：2dp 细线贴在网页上方，不用居中转圈挡内容。
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }

            // 外层底色兜住"页面内容不足一屏"的那一段：
            // B 站登录页只有约 396px（CSS px）高，反色只作用于页面内容，
            // 下面一截会露出 WebView 自己的白底。把 WebView 设成透明、由这层
            // 主题色垫底，深色下就是连续的深色，浅色下也和白页无缝。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        if (isDark) {
                            androidx.compose.ui.graphics.Color(0xFF0B0C0F)
                        } else {
                            androidx.compose.ui.graphics.Color.White
                        }
                    )
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            // 与播放页同一套「不带 wv」UA：passport 对 WebView UA 有风控。
                            settings.userAgentString = BILI_LOGIN_UA
                            // 登录链路可能跳转到 passport/www/m 多个域名，允许第三方 cookie。
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // 透明：底色交给外层的主题色，避免页面下方露白。
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setWebViewClient(object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    // 嗅探兜底：从请求头 Cookie 里抓 SESSDATA。
                                    val header = request?.requestHeaders?.get("Cookie")
                                    if (!header.isNullOrBlank() && header.contains("SESSDATA=")) {
                                        sniffedCookie = header
                                    }
                                    return null
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val scheme = request?.url?.scheme?.lowercase() ?: return true
                                    return scheme != "https"
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    errorMessage = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    // 深色反色 + 自动点掉「我已阅读并同意隐私政策」浮层。
                                    applyLoginPageSkin(view, isDark)
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    errorMessage =
                                        error?.description?.toString()?.ifBlank { "登录页加载失败" }
                                }
                            })
                            setWebChromeClient(object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            })
                            injectBiliCookies(this, BilibiliRepository.currentSessionCookie())
                            loadUrl(loginUrl)
                            holder.view = this
                        }
                    },
                    update = { },
                    onRelease = { holder.destroy() }
                )

                errorMessage?.let { message ->
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 扫码登录
// ---------------------------------------------------------------------------

/**
 * 扫码登录面板。
 *
 * 流程：`generate` 拿二维码内容与 key → 画二维码 → 每 2 秒 `poll` 一次 →
 * 成功时把返回的 Cookie 存下来。二维码卡片**必须保持白底黑码**（反色后部分
 * 扫码器识别率会掉），所以这里是全页唯一不跟随深色的一处。
 */
@Composable
private fun QrLoginPane(
    onLoggedIn: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    var qr by remember { mutableStateOf<BilibiliRepository.BiliLoginQr?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf(BilibiliRepository.BiliQrState.WAITING) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 换一张二维码（过期或接口失败时点刷新）。 */
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTick) {
        loading = true
        error = null
        state = BilibiliRepository.BiliQrState.WAITING
        bitmap = null
        qr = null
        runCatching { BilibiliRepository.fetchLoginQrCode() }
            .onSuccess { result ->
                qr = result
                bitmap = withContext(Dispatchers.Default) { encodeQrBitmap(result.content) }
                loading = false
                if (bitmap == null) error = "二维码绘制失败，请点击刷新"
            }
            .onFailure {
                error = it.message ?: "二维码生成失败，请点击刷新"
                loading = false
            }
    }

    // 轮询：key 变了就重新起一轮（刷新二维码会换 key）。
    val qrKey = qr?.key
    LaunchedEffect(qrKey) {
        if (qrKey.isNullOrBlank()) return@LaunchedEffect
        while (true) {
            delay(2000)
            val result = runCatching { BilibiliRepository.pollLoginQrCode(qrKey) }.getOrNull()
                ?: continue
            state = result.first
            when (result.first) {
                BilibiliRepository.BiliQrState.SUCCESS -> {
                    val cookie = result.second
                    if (cookie.isNullOrBlank()) {
                        error = "登录成功但未能读取登录态，请改用账号登录"
                        onMessage("登录成功但未能读取登录态，请改用账号登录")
                    } else {
                        BilibiliRepository.saveSessionCookie(context, cookie)
                        onMessage("登录成功，已保存登录状态")
                        onLoggedIn()
                    }
                    break
                }

                BilibiliRepository.BiliQrState.EXPIRED -> break
                else -> Unit
            }
        }
    }

    val statusText = when {
        error != null -> error.orEmpty()
        loading -> "正在生成二维码…"
        state == BilibiliRepository.BiliQrState.SCANNED -> "已扫码，请在手机上确认登录"
        state == BilibiliRepository.BiliQrState.EXPIRED -> "二维码已失效，点击刷新"
        else -> "打开哔哩哔哩 App 扫一扫"
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .size(248.dp)
                .clip(RoundedCornerShape(16.dp))
                // 白底黑码：不跟深色，扫码器认的是对比度而不是主题。
                .background(androidx.compose.ui.graphics.Color.White)
                .clickable(enabled = !loading && error != null) { refreshTick++ },
            contentAlignment = Alignment.Center
        ) {
            when {
                loading -> CircularProgressIndicator(
                    Modifier.size(28.dp),
                    color = primary,
                    strokeWidth = 2.5.dp
                )

                bitmap != null && state != BilibiliRepository.BiliQrState.EXPIRED -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.size(216.dp)
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (state == BilibiliRepository.BiliQrState.EXPIRED) "二维码已失效" else "二维码加载失败",
                        color = androidx.compose.ui.graphics.Color(0xFF5B6068),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "点击刷新",
                        color = androidx.compose.ui.graphics.Color(0xFFFB7299),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            statusText,
            color = if (error != null) MaterialTheme.colorScheme.error else onSurface,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "扫码后手机确认，本机自动登录；登录信息只保存在本机",
            color = variant,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        // 保存二维码到相册 + 拉起哔哩哔哩：本机没有扫码器对着自己屏幕扫时，
        // 用户可把二维码存进相册，再在哔哩哔哩「扫一扫」里从相册识别。
        val bmp = bitmap
        if (bmp != null && state != BilibiliRepository.BiliQrState.EXPIRED) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(primary.copy(alpha = 0.12f))
                    .clickable {
                        scope.launch {
                            val saved = TodoImageSaver.saveBitmapToGallery(context, bmp)
                            if (!saved) {
                                onMessage("保存二维码失败，请重试")
                                return@launch
                            }
                            val bili = context.packageManager
                                .getLaunchIntentForPackage("tv.danmaku.bili")
                            runCatching {
                                if (bili != null) {
                                    context.startActivity(bili)
                                } else {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://www.bilibili.com")
                                        )
                                    )
                                }
                            }
                            onMessage("二维码已保存到相册，请在哔哩哔哩「扫一扫」里从相册识别")
                        }
                    }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "保存二维码并跳转哔哩哔哩",
                    color = primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (error != null || state == BilibiliRepository.BiliQrState.EXPIRED) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(primary.copy(alpha = 0.12f))
                    .clickable { refreshTick++ }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("刷新二维码", color = primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "也可以切换到「账号登录」用手机号密码登录",
            color = variant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )
    }
}

/**
 * 把登录页二维码内容编码成位图。
 *
 * 边距给 1（而不是默认 4）：卡片本身有 16dp 内边距，再留 4 模块白边会显得二维码很小。
 */
private fun encodeQrBitmap(content: String, sizePx: Int = 640): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] =
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}.getOrNull()

/**
 * 给官方登录页套一层"皮肤"：深色反色 + 收掉压住按钮的协议提示。
 *
 * ## 为什么用反色而不是改 DOM 颜色
 *
 * 第三方页面（passport.bilibili.com）的类名会随改版变化，按选择器写背景色很快就会
 * 失效；`invert(1) hue-rotate(180deg)` 只依赖渲染结果，结构怎么变都成立。
 *
 * ## 三个具体修法（2026-11-18 用 CDP 实测页面后定的）
 *
 * - **露白**：该页内容只有 396px 高，而视口 755px——反色只作用于页面内容，下面一截
 *   露出 WebView 白底。**这个页面里 `vh` 单位是失效的**（CDP 实测：新建元素设
 *   `height:100vh`，量出来是 0；`min-height:100%` 也压不住页面的样式重置），
 *   所以只能用 px 铺一块 3000px 高的底色 div，反色后正好显示为深色。
 * - **协议提示压按钮**：它是一个 `div.explain-tips`（`position:absolute`，正好浮在
 *   「登录」按钮上），不是弹窗、点不掉，直接 `display:none`。
 * - **品牌色偏移**：纯反色会把粉色按钮压成暗紫，`saturate` 拉回一档；完全还原品牌色
 *   只能逐个元素二次反色，代价大于收益，这里接受轻微偏移。
 *
 * 图片/图标二次反色回到原样，不会变成负片。
 */
private fun applyLoginPageSkin(webView: WebView?, dark: Boolean) {
    if (webView == null) return
    // 基础样式与深浅无关：协议提示块 `div.explain-tips` 用 absolute 浮在
    // 「登录」按钮上，两种模式下都必须收掉（浅色下它同样挡住按钮，实测过）。
    val css = ".explain-tips{display:none!important}" + buildString {
        if (dark) {
            append("html{filter:invert(1) hue-rotate(180deg) saturate(1.35) brightness(1.03);")
            append("background:#fff!important}")
            append("body{background:#fff!important}")
            append("img,svg,video,canvas,picture{")
            append("filter:invert(1) hue-rotate(180deg) saturate(0.8)!important}")
        }
    }
    val script = """
        (function(){
          var id='__cm_skin';
          var old=document.getElementById(id);
          if (old) old.remove();
          var s=document.createElement('style');
          s.id=id;
          s.innerHTML='$css';
          document.documentElement.appendChild(s);
          try{
            var tips=document.querySelectorAll('.explain-tips');
            for(var i=0;i<tips.length;i++){
              tips[i].style.setProperty('display','none','important');
            }
          }catch(e){}
          if (${dark}) {
            // 页面只有约 396px 高、视口 755px，下方那一截会露出 WebView 白底。
            // ⚠️ 这个页面里 **vh 单位失效**（实测新建元素 `height:100vh` 算出来是 0），
            // 所以只能用 px 铺一块足够高的底色；反色后 #fff 显示为深色，正好盖住白底。
            try{
              if (!document.getElementById('__cm_bg')) {
                var bg=document.createElement('div');
                bg.id='__cm_bg';
                bg.style.cssText='position:absolute;left:0;top:0;width:100%;height:3000px;' +
                  'background:#fff;z-index:-1;pointer-events:none;';
                var host=document.body||document.documentElement;
                host.insertBefore(bg, host.firstChild);
              }
            }catch(e){}
          } else {
            // 切回浅色：撤掉底色块，否则页面下方会留一块深色。
            try{
              var bg2=document.getElementById('__cm_bg');
              if (bg2) bg2.remove();
            }catch(e){}
          }
          // 协议浮层兜底：以「我已阅读并同意」开头的块若仍在，点掉它。
          if (window.__cmAgreeTimer) clearInterval(window.__cmAgreeTimer);
          var tries=0;
          window.__cmAgreeTimer=setInterval(function(){
            tries++;
            var nodes=document.querySelectorAll('div,span,button,a,p,label');
            for (var i=0;i<nodes.length;i++){
              var t=(nodes[i].innerText||'').replace(/\s/g,'');
              if (t.indexOf('我已阅读并同意')===0 && t.length<120) {
                try{nodes[i].click();}catch(e){}
                clearInterval(window.__cmAgreeTimer);
                window.__cmAgreeTimer=null;
                return;
              }
              if (t==='同意' || t==='同意并继续' || t==='我知道了') {
                try{nodes[i].click();}catch(e){}
                clearInterval(window.__cmAgreeTimer);
                window.__cmAgreeTimer=null;
                return;
              }
            }
            if (tries>10) {
              clearInterval(window.__cmAgreeTimer);
              window.__cmAgreeTimer=null;
            }
          },500);
        })();
    """.trimIndent()
    runCatching { webView.evaluateJavascript(script, null) }
}

/**
 * 把已保存的 Cookie 回注到 WebView 的 CookieManager。
 *
 * 场景：应用重启后 WebView 的 cookie jar 可能已被系统清理（或用户清了数据），
 * 但 SharedPreferences 里还有登录串——回注一次就能继续用登录态。
 * 失败不抛异常：登录态只是增强，不能因为它让播放页崩掉。
 */
@Suppress("UNUSED_PARAMETER")
internal fun injectBiliCookies(webView: WebView, cookie: String?) {
    if (cookie.isNullOrBlank()) return
    val cookieManager = CookieManager.getInstance()
    listOf(
        "https://m.bilibili.com",
        "https://www.bilibili.com",
        "https://api.bilibili.com",
        "https://passport.bilibili.com"
    ).forEach { url ->
        runCatching { cookieManager.setCookie(url, cookie) }
    }
    runCatching { cookieManager.flush() }
}

/** 登录页 WebView 的 UA：移动 Chrome、不带 `; wv`（口径同播放页）。 */
private const val BILI_LOGIN_UA =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
