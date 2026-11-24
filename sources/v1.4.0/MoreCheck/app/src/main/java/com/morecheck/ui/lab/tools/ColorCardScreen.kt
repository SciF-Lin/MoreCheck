package com.morecheck.ui.lab.tools

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.morecheck.util.findActivity

/**
 * 色卡页要加载的本地页面。
 *
 * 指向 `app/src/main/assets/colors/index.html`，走 `file:///android_asset/`——
 * 与**贪吃蛇 / 番茄时钟 / 数独2**同一口径，而不是数独那条 https + `WebViewAssetLoader`。
 *
 * ## 为什么可以不映射 https
 *
 * 本页已逐项核实：无 `Worker`、无 `createObjectURL`、无 `fetch` / `XMLHttpRequest`、
 * 无 `window.open`、无 `serviceWorker`；唯一的资源是**同目录**的 `colors.js`
 * （`<script src>` 在 `file://` 下按相对路径解析，属于同源读取，不受限制）。
 * 数据 776 条 66KB，一次性读入内存后不再访问磁盘。
 *
 * ⚠️ 页面**不用 localStorage**（没有"记住上次筛选"这类需求），所以也不用为了
 * 存储能力去换 https origin——真需要时再按 `SudokuScreen` 那套改。
 */
private const val COLOR_CARD_PAGE_URL = "file:///android_asset/colors/index.html"

/**
 * 页面 `:root` 的 `--bg` 浅色值（`#f6f5f2`）。
 *
 * 与 `Sudoku2Screen` 的 `SUDOKU2_BACKGROUND_COLOR` 同理：这是**首帧绘制前**的兜底色，
 * 只露几十毫秒。本页两档都由原生决定（见 [ColorCardScreen] 的 KDoc），
 * 所以这里取浅色档，深色主题时由 [COLOR_CARD_BACKGROUND_DARK] 顶上。
 */
private const val COLOR_CARD_BACKGROUND_LIGHT = 0xFFF6F5F2.toInt()

/** 页面 `html[data-theme="dark"]` 的 `--bg` 值（`#121212`），深色主题下的首帧兜底色。 */
private const val COLOR_CARD_BACKGROUND_DARK = 0xFF121212.toInt()

/**
 * WebView 实例持有者。
 *
 * 与其它 WebView 页里的同名类一样**各写一份**、不抽公共类：本项目既有纪律是
 * "一屏一个自洽实现"（理由见 `SnakeScreen` 里同名类的 KDoc）。
 * `destroy()` 先置空再销毁，保证 `onRelease` 与 `DisposableEffect` 两条路径
 * 谁先到都只销毁一次。
 */
private class ColorCardWebViewHolder {
    var view: WebView? = null

    fun destroy() {
        val target = view ?: return
        view = null
        target.destroy()
    }
}

/**
 * 「实验室 → 色卡」（2026-11-02）。
 *
 * 776 个色（中国色 526 + 日本色 250）。列表支持按色名 / 拼音 / HEX 搜索、
 * 按来源筛选；点任一色块弹出底部详情，HEX / RGB / CMYK 各自可一键复制。
 *
 * ## 为什么主题由原生"传"进去，而不是让页面读 `prefers-color-scheme`
 *
 * 本应用的深浅色是**用户在设置里选的**，可能与系统相反；而 WebView 里的
 * `prefers-color-scheme` 跟随的是系统/应用 uiMode，两者会打架。
 * 于是这里按 `MaterialTheme.colorScheme.background` 的明度判定（与
 * `MoreCheckApp` 的 `SplashGate` 同一算法），把 `?theme=dark|light` 拼进 URL，
 * 页面照它设 `data-theme`。**一次决定、全程一致**，也顺手让首帧兜底色有依据。
 *
 * ## 返回键
 *
 * 只用 [BackHandler] 退出：页面自身没有任何导航（详情是页内弹层，不是路由），
 * `canGoBack()` 恒为 false，写"先回退网页历史"只会留下一段永不执行的分支。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorCardScreen(
    onBack: () -> Unit,
    /** 是否显示顶部标题栏（沿用实验室其它页的参数名，宿主不传时默认显示）。 */
    showTitleBar: Boolean = true
) {
    val holder = remember { ColorCardWebViewHolder() }

    // 深色判定：与 MoreCheckApp 的 SplashGate 同口径（看 background 的明度），
    // 不能用 isSystemInDarkTheme()——本应用的主题可以由用户设置成与系统相反。
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val chromeBackground: Color = MaterialTheme.colorScheme.surface

    // 状态栏 / 导航栏图标明暗完全跟随主题（本页整页跟随主题，不是恒深色的游戏页）。
    val appearanceLightBars = !darkTheme
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, appearanceLightBars) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = appearanceLightBars
            controller.isAppearanceLightNavigationBars = appearanceLightBars
            onDispose {
                // 原样归还，别把本页的取值泄漏给返回后的实验室页。
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { holder.destroy() }
    }

    BackHandler { onBack() }

    Scaffold(
        containerColor = chromeBackground,
        topBar = {
            if (showTitleBar) {
                TopAppBar(
                    title = { Text("色卡") },
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(chromeBackground)
                // 让底色铺到手势条区域（与贪吃蛇 / 数独 / 数独2 同一修法）。
                .consumeWindowInsets(WindowInsets(0, 0, 0, 0))
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        // 加载的本身就是 file:///android_asset：放开文件访问。
                        settings.allowFileAccess = true
                        // 不读 content://，保持最小权限。
                        settings.allowContentAccess = false
                        // 视口显式声明，不依赖 WebView 版本的默认值。
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true

                        setBackgroundColor(
                            if (darkTheme) COLOR_CARD_BACKGROUND_DARK else COLOR_CARD_BACKGROUND_LIGHT
                        )

                        // ⚠️ 只在首次创建时加载：`update` 里再调 loadUrl 会把用户
                        // 正在看的详情/搜索状态重置掉（与数独2 同一条理由）。
                        loadUrl("$COLOR_CARD_PAGE_URL?theme=${if (darkTheme) "dark" else "light"}")
                        holder.view = this
                    }
                },
                update = { },
                onRelease = { holder.destroy() }
            )
        }
    }
}
