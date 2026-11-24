package com.morecheck.ui.settings

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.R
import com.morecheck.data.cloud.CloudError
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.LoginRequiredException
import com.morecheck.data.cloud.ReleaseApk
import com.morecheck.data.cloud.ReleaseChannel
import com.morecheck.data.cloud.isPublicRelease
import com.morecheck.di.appContainer
import com.morecheck.ui.theme.Space
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.AppInfo
import com.morecheck.util.openInBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 列表状态：加载中 / 读不到 / 读到了（可能为空）。
 *
 * 2026-11-03 需求 5：`Failed` 多了 [Failed.offline]——「本机没网」与「连上了但云端出错」
 * 要给用户完全不同的动作（检查网络 vs 直接重试），不能合成一句"读取失败"。
 */
private sealed interface ApkListState {
    data object Loading : ApkListState
    data class Failed(val reason: String, val offline: Boolean = false) : ApkListState
    data class Ready(val releases: List<ReleaseApk>) : ApkListState
}

/**
 * 异常 → 界面失败态（需求 5）。
 *
 * 只有"请求根本没发出去 / 没收到响应"才算离线（[CloudError.Kind.NETWORK]，
 * 见 `CloudHttp`：连接阶段异常统一归到这一类）。服务端 5xx / 鉴权失败等
 * 属于"连上了但读不到"，不告诉用户去检查 Wi-Fi。
 */
private fun Throwable.toApkFailure(): ApkListState.Failed = when (this) {
    is CloudException -> ApkListState.Failed(
        reason = error.message.ifBlank { "读取云端失败" },
        offline = error.kind == CloudError.Kind.NETWORK
    )

    // 兜底：存储层若把底层 IO 异常漏出来，也按离线处理。
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.net.SocketTimeoutException ->
        ApkListState.Failed("网络不可用，请检查网络连接后重试", offline = true)

    else -> ApkListState.Failed(message ?: "读取云端失败")
}
/** 单个安装包的下载进度（0f~1f）；不在 map 里 = 没在下。 */
private class DownloadProgress {
    var fraction by mutableStateOf(0f)
    var running by mutableStateOf(false)
}

/**
 * 「关于 → 检查更新 → 安装包下载」（本轮需求 2）。
 *
 * ## 这一页解决什么
 *
 * 正式版与测试版 APK 都托管在**打卡的云存储**上（`shared/<发布账号>/apk/`），
 * 页面按渠道分栏列出，用户挑一版下载安装。
 * 相比跳 GitHub Releases：国内网络下不用翻墙、不用等网页加载、
 * 也不会出现"Assets 折叠区藏在最下面找不到"。
 *
 * ## 正式版 / 测试版分级（本轮需求）
 *
 * ⚠️ 平台约束：本应用的云存储**只服务已登录用户**（官方 storage 文档：
 * "Public Bucket/public URL access is not exposed"），实测不带会话的对象请求在
 * 云网关层就返回 404，数据库 RLS 加 anon 策略也无法绕过。所以分级落在客户端：
 * - **正式版**：清单随 APK 打包在 `assets/public_releases.json`，未登录也能看到；
 *   点击下载交给浏览器 / GitHub Releases，App 内不要求任何登录。
 * - **测试版**：必须登录云账号，走云存储对象下载并做 SHA256 校验；未登录时
 *   列表显示引导卡，点下载明确提示去登录。
 * - 已登录时两版都在应用内直接下载、下完拉起安装器。
 * 详见 `ReleaseApkRepository` 的 KDoc 与 `scripts/PUBLIC_RELEASE_TIER.md`。
 *
 * ## 数据从哪来
 *
 * 未登录：`assets/public_releases.json`（随包，零网络请求）。
 * 已登录：`shared/<前缀>/manifest.json`（完整清单，含 beta），与随包清单合并去重；
 * 清单缺失时退化为列目录、从文件名解析版本号——细节见 `ReleaseApkRepository` 的 KDoc。
 *
 * ## 正式版 / 测试版分类（2026-11-01 需求）
 *
 * 云端同一个目录里同时托管两种包，[ReleaseChannel] 按版本名里的预发布标记
 * （`beta` / `alpha` / `rc` / `snapshot`）判定，页面用分段控件分栏展示。
 * 默认停在**正式版**——绝大多数用户要的是"我能装的最新稳定版"。
 *
 * ## 版本比较
 *
 * 只比较 `versionCode`（单调递增的整数）。与本机相同的标「当前版本」，低的标「旧版」，
 * 但仍然允许下载——用户可能想回退或给别的手机装，拦着没有意义。
 */
@Suppress("StateFlowValueCalledInComposition") // 首帧初值必须是当前会话值，避免闪回未登录（见下方 KDoc）
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkDownloadScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit = {},
    /**
     * 「更新日志」入口（2026-11-02 需求）。
     *
     * 当「检查更新」的去处被设成本页时，关于页那个「更新日志 ›」入口**搬到这里**：
     * 版本列表与"这些版本都改了什么"本来就是同一簇信息，
     * 用户在挑版本时顺手就能看，不必先退回关于页。
     */
    onOpenChangelog: () -> Unit = {},
    /**
     * 是否在本页显示「更新日志」入口（2026-11-02 需求 2）。
     *
     * 由**用户设置**决定（开发者选项 → 更新日志入口），与「检查更新」的去处解耦。
     * 显示时**固定在屏幕底部**：它是"门"而不是内容，不该随列表滚动，
     * 列表只有一张卡时也不该浮在半空。
     */
    showChangelogLink: Boolean = true,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val scope = rememberCoroutineScope()
    val repository = container.releaseApkRepository

    // 云端会话：决定看「公开清单 / 完整清单」，未登录也发匿名请求（见 KDoc）。
    //
    // ⚠️ 显式传 `initialValue`（会话 StateFlow 的当前值），不用默认的 null：
    // 默认 null 会让"从更新日志返回本页"的第一帧先按"未登录"取缓存 / 发请求，
    // 待真实会话发射又切一次视图，闪一下。HANDOVER 纪律 4：initialValue 的语义是
    // 「流还没发射时先给你看什么」，不是业务默认值。
    val session by container.cloudAuthClient.session.collectAsStateWithLifecycle(
        initialValue = container.cloudAuthClient.session.value
    )
    val ownerId = session?.userId
    // 登录态决定"看哪份清单"：未登录只读匿名公开清单（仅正式版），
    // 已登录读完整清单（含测试版）。仓库与下载都按它分两条通道。
    val loggedIn = session != null

    // 列表状态：初值直接取仓库的**进程内缓存**。
    // 这是需求 1「从更新日志返回安装包下载页不用刷新」的核心落点：
    // 返回时 NavHost 会重建本页组合（踩坑 5.33），remember 从初值重来，
    // 初值命中缓存 → 首帧就是列表，不闪 Loading、也不再发一次云端请求。
    // 缓存为什么放仓库层、键为什么是 userId、TTL 为什么是 5 分钟，
    // 见 `ReleaseApkRepository.cachedSnapshot` 的 KDoc。
    var state by remember {
        mutableStateOf<ApkListState>(
            repository.cachedSnapshot(loggedIn, ownerId)
                ?.let { ApkListState.Ready(it) }
                ?: ApkListState.Loading
        )
    }
    // 手动刷新计数：点右上角刷新时 +1，触发下面的 effect **强制**拉清单。
    // 故意用普通 remember（不是 rememberSaveable）：从二级页返回时应视为"没有新请求"，
    // 让它回到 0 才能走缓存分支。
    var reloadTick by remember { mutableStateOf(0) }
    // 是否正在静默刷新（已有列表时刷新不退回骨架，只在标题栏转圈）。
    var refreshing by remember { mutableStateOf(false) }

    val installedVersionName = remember { AppInfo.versionName(context) }
    val installedVersionCode = remember { AppInfo.versionCode(context) }

    // 每个安装包各自的下载进度（按 objectPath 区分，同一版本重复下载互不干扰）。
    val progress = remember { mutableStateMapOf<String, DownloadProgress>() }

    // 当前查看的渠道。默认正式版：绝大多数用户要的是能装的最新稳定版。
    // 用 rememberSaveable 存**名字**（枚举不是 Parcelable，字符串才是）：
    // 从更新日志返回时用户看的是哪一栏就还是哪一栏——视图状态被恢复，
    // 但数据没重拉，两者不矛盾。
    var channelName by rememberSaveable { mutableStateOf(ReleaseChannel.STABLE.name) }
    val channel = ReleaseChannel.entries.firstOrNull { it.name == channelName }
        ?: ReleaseChannel.STABLE

    // 底部确认弹窗（需求：旧版本下载确认 + 已安装版本重装确认）。
    // mode = "older" 安装比当前低的版本；"reinstall" 重新安装当前版本。
    var confirmRelease by remember { mutableStateOf<ReleaseApk?>(null) }
    var confirmMode by remember { mutableStateOf("older") }

    /**
     * 本页「下载相关」提示统一用**系统 Toast**（2026-11-03 需求 3）。
     *
     * ## 为什么不用 onMessage（应用内提示条 / Snackbar）
     *
     * 1. 用户明确要求「用手机系统的 toast」；
     * 2. 下载是异步长任务：用户很可能在下载途中退到桌面或别的页面，
     *    应用内提示依附于本页组合，页面销毁就没人显示——而「下载完成 / 失败」
     *    恰恰最需要在"用户已经离开本页"时也能看到；系统 Toast 由系统窗口管理，
     *    与页面是否还在组合里无关；
     * 3. Toast 不参与应用内 Snackbar 的排队，不会被紧接着的其它提示挤掉。
     *
     * ## 线程（必须回主线程）
     *
     * `Toast` 内部要创建 `Handler`，必须在**有 Looper 的线程**（这里是主线程）上调用。
     * 下载协程当前虽回到主线程，但 `onProgress` 回调与将来可能的线程切换不可控，
     * 所以统一 post 到主 Looper 兜底；用 `applicationContext` 避免隐式持有 Activity。
     *
     * 只覆盖**下载相关**提示；刷新失败、GitHub 跳转等页面级提示仍走 `onMessage`，
     * 保持与其它页面一致的全局提示体验。
     */
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val showDownloadToast: (String) -> Unit = remember(context, mainHandler) {
        { message ->
            mainHandler.post {
                Toast.makeText(
                    context.applicationContext,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 下载入口统一收口到这里：旧版本确认弹窗、卡片点击两处都调它。
    //
    // 2026-11-10 需求 4 的三处改动都在这里：
    //   ① 先找本机已有的同版本包，有就直接安装（大小 + SHA-256 校验见仓库）；
    //   ② 只有真的下载时才进入进度态，下面的按钮显示实时百分比；
    //   ③ 结果 Toast 统一为「下载完成」，不再区分"正在安装 / 请手动安装"。
    val startDownload: (ReleaseApk) -> Unit = { release ->
        // 正在下载时按钮已被换成进度态，这里再兜一层：
        // 双击的第二次事件可能仍在队列里。
        if (progress[release.objectPath]?.running != true) {
            scope.launch {
                // ① 本机已有可用安装包 → 直接拉安装器，不再下一次。
                //    withContext(IO) 是因为要读文件 / 查 MediaStore；
                //    任何异常都当"没有本地包"，退回正常下载，别把点击卡死。
                val local = withContext(Dispatchers.IO) {
                    runCatching { repository.findLocalApk(release) }.getOrNull()
                }
                if (local != null) {
                    runCatching { repository.install(local) }
                    showDownloadToast("下载完成")
                    return@launch
                }
                // ② 正式版 + 未登录：平台云存储只服务已登录用户（匿名 GET 在网关层就 404），
                //    所以「免登录下载」落在浏览器 / GitHub Releases 这条链路上——App 内
                //    不要求任何登录，也不发注定失败的请求。已登录用户仍走下面的应用内下载。
                if (!loggedIn && isPublicRelease(release)) {
                    openInBrowser(
                        context = context,
                        url = GITHUB_RELEASES_URL,
                        onMessage = onMessage
                    )
                    return@launch
                }
                // ③ 真正下载：进度 tracker 只在这时创建，本地命中不会闪一次进度动画。
                val tracker = DownloadProgress().apply { running = true }
                progress[release.objectPath] = tracker
                try {
                    val result = container.releaseApkRepository.download(
                        apk = release,
                        loggedIn = loggedIn,
                        onProgress = { done, total ->
                            if (total > 0) {
                                tracker.fraction =
                                    (done.toFloat() / total).coerceIn(0f, 1f)
                            }
                        }
                    )
                    tracker.fraction = 1f
                    // 拉安装器失败不吞掉：文件已经在下载目录里，安装器失败时
                    // 用户仍能在系统下载目录里手动装；Toast 统一给「下载完成」。
                    runCatching { container.releaseApkRepository.install(result) }
                    showDownloadToast("下载完成")
                } catch (e: Throwable) {
                    // 测试版且未登录：仓库已明确拒绝，这里引导去登录，
                    // 而不是把"请先登录"混进一句"下载失败"里。
                    if (e is LoginRequiredException) {
                        showDownloadToast(e.message ?: "请先登录")
                        onOpenLogin()
                    } else {
                        showDownloadToast("下载失败：${e.message ?: "网络异常，请重试"}")
                    }
                } finally {
                    tracker.running = false
                }
            }
        }
    }

    // 拉清单：账号变化 / 手动刷新时重跑。
    //
    // 需求 1 的关键：**只有**手动刷新或缓存不可用时才访问网络。
    // 从更新日志返回时 effect 会重跑（组合重建），但缓存命中 → 直接返回，
    // 列表一次都不 Loading、网络一次都不打。
    // 未登录也照常拉清单（匿名公开清单），不再整屏挡一个"去登录"。
    // 登录/退出会改 ownerId 与 loggedIn，effect 重跑且缓存键不同 → 自动换视图。
    LaunchedEffect(loggedIn, ownerId, reloadTick) {
        // ① 非手动刷新 + 缓存命中 → 用缓存，不发请求。
        if (reloadTick == 0) {
            repository.cachedSnapshot(loggedIn, ownerId)?.let { cached ->
                state = ApkListState.Ready(cached)
                return@LaunchedEffect
            }
        }
        // ② 需要联网：已经有列表时**不退回 Loading**（静默刷新，只在标题栏转圈），
        //    避免用户点刷新时整页闪成骨架。
        if (state !is ApkListState.Ready) state = ApkListState.Loading
        refreshing = true
        try {
            state = ApkListState.Ready(
                repository.fetch(loggedIn = loggedIn, ownerId = ownerId, force = true)
            )
        } catch (e: Throwable) {
            if (state is ApkListState.Ready) {
                // 静默刷新失败：保留旧列表比换成一张错误卡更有用，原因走全局提示条。
                val reason = (e as? CloudException)?.error?.message?.takeIf { it.isNotBlank() }
                    ?: "刷新失败，请检查网络后重试"
                onMessage(reason)
            } else {
                state = e.toApkFailure()
            }
        } finally {
            refreshing = false
        }
    }

    // 刷新图标转圈：**只在 refreshing 时**才创建无限动画。
    // 若无条件 rememberInfiniteTransition，页面静止时也会有一个永久运行的帧回调——
    // 一个装饰性动效不值得；Compose 的 if 分支本身就带独立组合组，进出都安全。
    // 不引入第三方动画库。
    val refreshAngle = if (refreshing) {
        val spin = rememberInfiniteTransition(label = "apk-refresh-spin")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing)
            ),
            label = "apk-refresh-angle"
        )
        angle
    } else {
        0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安装包下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 未登录也要能重试公开清单（匿名读失败时不会一直转圈）。
                    IconButton(onClick = { if (!refreshing) reloadTick++ }) {
                        // 刷新中让图标自己转起来（需求 4/5 的轻量动效）：
                        // 静默刷新时页面不闪骨架，这个转圈就是"正在请求"的唯一指示。
                        if (refreshing) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "正在刷新列表",
                                modifier = Modifier
                                    .size(24.dp)
                                    .rotate(refreshAngle)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新列表"
                            )
                        }
                    }
                    // 需求：标题栏加 GitHub 入口，跳转仓库主页。
                    // 图标用官方 Octocat 标记（`res/drawable/ic_github.xml`，2026-11-01 换），
                    // 不再用 Material 的通用 `Code` —— 那个看不出是 GitHub。
                    IconButton(onClick = {
                        openInBrowser(context, GITHUB_REPO_URL, onMessage = onMessage)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_github),
                            contentDescription = "GitHub 仓库",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        Column(modifier) {
            // 未登录也照常显示列表（只读匿名公开清单），不再整屏挡一个"去登录"。
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.xl
                ),
                verticalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                // 2026-11-10 需求 3：登录后也不再显示「已登录 · 含测试版」状态横幅。
                // 用户看到的就是一份版本列表，登录与否由列表内容（是否含测试版）自己说明，
                // 不需要再在顶部挂一条与选择无关的登录状态提示。
                item(key = "apk-current") {
                    val ready = state as? ApkListState.Ready
                    val failed = state as? ApkListState.Failed
                    CurrentVersionCard(
                        versionName = installedVersionName,
                        // 云端概况文案跟列表状态一致：读不到时不能再写"读取中…"，
                        // 否则错误卡说"没网"、上面这张卡还说"正在读取"，自相矛盾。
                        cloudSummary = when {
                            ready != null -> {
                                val stableCount =
                                    ready.releases.count { it.channel == ReleaseChannel.STABLE }
                                val betaCount =
                                    ready.releases.count { it.channel == ReleaseChannel.BETA }
                                if (stableCount == 0 && betaCount == 0) {
                                    "云端版本：暂无安装包"
                                } else if (!loggedIn) {
                                    // 未登录不再提"登录可见测试版"（2026-11-05 需求 3：
                                    // 页面里不要出现未登录相关提示）。测试版通道有引导卡。
                                    "云端共 $stableCount 个正式版"
                                } else {
                                    "云端共 $stableCount 个正式版、$betaCount 个测试版"
                                }
                            }
                            failed != null && failed.offline -> "云端版本：当前无网络"
                            failed != null -> "云端版本：读取失败"
                            else -> "云端版本：读取中…"
                        }
                    )
                }

                when (val s = state) {
                    ApkListState.Loading -> {
                        // 需求 4：加载态不再是一行"转圈 + 一句话"，改成
                        // 「加载头卡（转圈 + 说明）+ 两张骨架卡（呼吸动画）」，
                        // 让用户能预判页面结构、等待感更轻。
                        item(key = "apk-loading-head") { LoadingHeaderCard() }
                        items(
                            count = 2,
                            key = { index -> "apk-skeleton-$index" }
                        ) { ApkSkeletonCard() }
                    }

                    is ApkListState.Failed -> item(key = "apk-failed") {
                        FailureCard(failed = s, onRetry = { reloadTick++ })
                    }

                    is ApkListState.Ready -> {
                        val stable = s.releases.filter { it.channel == ReleaseChannel.STABLE }
                        val beta = s.releases.filter { it.channel == ReleaseChannel.BETA }

                        // 分段控件即使某栏为空也显示：空栏本身是有用的信息
                        // （"作者还没发过测试版"），比整块消失更好理解。
                        item(key = "apk-channels") {
                            ChannelSwitch(
                                selected = channel,
                                stableCount = stable.size,
                                betaCount = beta.size,
                                onSelect = { channelName = it.name }
                            )
                        }

                        val shown = if (channel == ReleaseChannel.STABLE) stable else beta
                        if (shown.isEmpty()) {
                            item(key = "apk-empty") {
                                if (channel == ReleaseChannel.BETA && !loggedIn) {
                                    // 未登录明确选了测试版通道：引导登录，而不是显示"还没有测试版"。
                                    BetaLoginGate(onOpenLogin = onOpenLogin)
                                } else {
                                    CenterNote(
                                        if (channel == ReleaseChannel.STABLE) {
                                            "云端还没有正式版安装包。\n" +
                                                "发布者上传后这里会自动出现。"
                                        } else {
                                            "云端还没有测试版安装包。\n" +
                                                "测试版更新更频繁，但不保证稳定；" +
                                                "想尝鲜可以看 GitHub Releases。"
                                        }
                                    )
                                }
                            }
                        } else {
                            // 「最新」角标在**当前栏内**比较：测试版里的 1.2.1-beta 不该
                            // 因为正式版有 1.2.0 就被标成"旧版"，两者不在同一条线上。
                            val newest = shown.firstOrNull()

                            items(shown, key = { it.objectPath }) { release ->
                                // ⚠️ 这里**只读**进度表：写入只发生在上面的点击回调里。
                                // 若写成 `progress.getOrPut(...) { … }`，等于在组合过程中
                                // 修改一个 SnapshotStateMap，会触发"组合期间写入状态"的重组循环。
                                val p = progress[release.objectPath]
                                val installed = release.versionCode > 0 &&
                                    release.versionCode.toLong() == installedVersionCode
                                val older = release.versionCode > 0 &&
                                    release.versionCode.toLong() < installedVersionCode
                                ReleaseCard(
                                    release = release,
                                    isNewest = release.objectPath == newest?.objectPath,
                                    isInstalled = installed,
                                    isOlder = older,
                                    downloading = p?.running == true,
                                    fraction = p?.fraction ?: 0f,
                                    // 未登录时给条目打上「无需登录 / 需登录」角标。
                                    tierLabel = if (loggedIn) {
                                        null
                                    } else if (release.channel == ReleaseChannel.BETA) {
                                        "需登录"
                                    } else {
                                        "无需登录"
                                    },
                                    onDownload = {
                                        when {
                                            // 防御：万一匿名视图里混进了 beta，点击先引导登录。
                                            !loggedIn && release.channel == ReleaseChannel.BETA -> onOpenLogin()
                                            // 旧版本：先弹底部确认，确认后才真正开始下载。
                                            older -> {
                                                confirmMode = "older"
                                                confirmRelease = release
                                            }
                                            // 当前版本：点「已安装」弹重装确认（需求）。
                                            installed -> {
                                                confirmMode = "reinstall"
                                                confirmRelease = release
                                            }
                                            else -> startDownload(release)
                                        }
                                    }
                                )
                            }
                            item(key = "apk-footer") {
                                Text(
                                    text = "安装包来自打卡云存储。" +
                                        "正式版是稳定版本；测试版更新更快，但可能存在问题。" +
                                        "下载的文件保存在 手机存储/Download/MoreCheck/。",
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xs)
                                )
                            }
                        }
                    }
                }
            }

            // ---- 底部固定入口（2026-11-02 需求 2）----
            // 无论登录与否都挂在内容区下方：日志是全内置内容，
            // 被"登录才能下载"顺带挡住没有道理。
            if (showChangelogLink) {
                ChangelogFooter(onOpenChangelog)
            }
        }
    }

    // 底部确认弹窗：旧版本安装 / 当前版本重装共用一套骨架，只换文案。
    // 用 ModalBottomSheet 而不是 AlertDialog：这是"继续 / 算了"的轻量决策，
    // 且从底部升起与列表的视觉动线一致。
    //
    // 2026-11-03 需求 3：上一轮按需求把弹窗"加高"，本轮用户反馈太高，改成**紧凑版**
    // （弹窗宽度与圆角不变，仍用默认 sheet 形状 + 左右 24dp 内边距）：
    //   ① 两个按钮由纵向堆叠（50dp + 44dp，各占整行 ≈102dp）改为**横排**：
    //      取消在左、「仍要下载并安装」在右，都缩到 40dp 高、wrap-content 宽度，
    //      按钮块高度从 ≈102dp 降到 40dp；
    //   ② 图标头保留但 52→48dp；标题 19→18sp；说明 14/22sp → 13/19sp；
    //      段间距 16/12/10/24 收紧到 12/8/6/16；
    //   ③ 内容总高约 200dp（原约 305dp，均不计拖拽条），高度减少量正好落在
    //      "原来两个按钮的总高度"（≈102dp）这个量级里，比只留一个按钮的版本
    //      也没矮到失去图标头与说明。
    // 仍保留 `verticalScroll`：小屏 / 大字体 / 横屏时内容超高也不会压扁按钮（踩坑 5.29）。
    confirmRelease?.let { target ->
        val reinstall = confirmMode == "reinstall"
        ModalBottomSheet(
            onDismissRequest = { confirmRelease = null }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(Space.md))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (reinstall) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (reinstall) {
                            Icons.Rounded.SystemUpdate
                        } else {
                            Icons.Rounded.History
                        },
                        contentDescription = null,
                        tint = if (reinstall) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = if (reinstall) "重新安装当前版本？" else "安装旧版本？",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (reinstall) {
                        "v${target.version} 就是当前安装的版本。" +
                            "重新下载覆盖安装不会影响打卡数据，通常用于怀疑安装包损坏时。"
                    } else {
                        "v${target.version} 比当前安装的 v$installedVersionName 旧。" +
                            "覆盖安装后打卡数据仍会保留，但应用功能会回退到该版本。"
                    },
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(Space.lg))
                // 按钮横排：取消在左、确认在右，右对齐。
                // 用 wrap-content 而不是各占一半，才是用户要的「按钮缩小」；
                // 「仍要下载并安装」比「取消」更宽是内容决定的（7 个字），
                // 40dp 高既保留足够热区，又比原来两个按钮各占一整行矮得多。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        Space.sm,
                        Alignment.End
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { confirmRelease = null },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            confirmRelease = null
                            startDownload(target)
                        },
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        Text("仍要下载并安装", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(Space.md))
            }
        }
    }
}

/** 未登录却切到「测试版」通道时的引导卡（替代原来的"还没有测试版"空态）。 */
@Composable
private fun BetaLoginGate(onOpenLogin: () -> Unit) {
    Card2 {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(Space.md))
            Text(
                text = "测试版需登录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "测试版仅对已登录用户开放。登录后即可查看并下载。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(Space.lg))
            Button(onClick = onOpenLogin) {
                Text("去登录")
            }
        }
    }
}

/** 单个条目上的访问角标：「无需登录」（主色）/「需登录」（警示色）。 */
@Composable
private fun TierTag(text: String, needLogin: Boolean) {
    val color = if (needLogin) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

/**
 * 屏幕底部的「更新日志 ›」入口（2026-11-02 需求 2）。
 *
 * ## 为什么固定在底部而不是塞进列表
 *
 * 用户要求「设置在里面时，显示在屏幕底部」。做成列表里的一项会有两个毛病：
 * 云端只有一张包时它浮在页面中间，像是内容的一部分；包多时又被卷到看不见的地方。
 * 它是"门"不是内容，所以放在内容区之外、贴着屏幕底边。
 *
 * ## 样式
 *
 * 上方一条 1dp 分隔线（`outlineVariant`，与下载页卡片同一档颜色），
 * 里面是 14sp 主色文字 + 右箭头——与关于页那个入口、与设置页的"进入下一级"同一套语言。
 * 高度按 44dp 走：比 48dp 的触摸热区略紧，但下面还有 `Space.sm` 的内边距托着。
 */
@Composable
private fun ChangelogFooter(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(horizontal = Space.lg)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(onClick = onClick)
                .padding(vertical = Space.md),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "更新日志",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 顶部「当前版本」卡：先说本机装的是什么，再列云端有什么。 */
@Composable
private fun CurrentVersionCard(versionName: String, cloudSummary: String) {
    Card2 {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.size(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "本机版本 v$versionName",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.strongTextColor
                )
                Text(
                    text = cloudSummary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 「正式版 / 测试版」分段切换（2026-11-01 需求 1）。
 *
 * ## 为什么不用 `SegmentedButton`
 *
 * 用户明确要求「移除切换动画」。Material3 的 `SegmentedButton` 自带选中态过渡
 * （容器色/描边随动画插值）与点击涟漪，两样都没法关干净；这里自己用
 * `clip + background + clickable(indication = null)` 拼一个——**状态一变立刻生效**，
 * 没有任何过渡，也没有涟漪。
 *
 * 计数只在该渠道有条目时显示：`正式版 3` / `测试版`（空栏不加 "0"，避免噪声）。
 */
@Composable
private fun ChannelSwitch(
    selected: ReleaseChannel,
    stableCount: Int,
    betaCount: Int,
    onSelect: (ReleaseChannel) -> Unit
) {
    val entries = listOf(
        Triple(ReleaseChannel.STABLE, "正式版", stableCount),
        Triple(ReleaseChannel.BETA, "测试版", betaCount)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        entries.forEach { (channel, label, count) ->
            val active = channel == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        // 涟漪本质上是"按下 → 扩散"的动画，一并去掉。
                        indication = null,
                        onClick = { onSelect(channel) }
                    )
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (count > 0) "$label $count" else label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1
                )
            }
        }
    }
}

/** 单个安装包卡片。 */
@Composable
private fun ReleaseCard(
    release: ReleaseApk,
    isNewest: Boolean,
    isInstalled: Boolean,
    isOlder: Boolean,
    downloading: Boolean,
    fraction: Float,
    /** 未登录视图里的访问角标（"无需登录" / "需登录"）；已登录时为 null。 */
    tierLabel: String? = null,
    onDownload: () -> Unit
) {
    Card2 {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "v${release.version}",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.strongTextColor
                        )
                        Spacer(Modifier.size(Space.sm))
                        // 角标只显示**一个**，按优先级取：
                        //   当前使用 > 比本机旧 > 云端最新 > 无
                        //
                        // 早先三个条件是并列的，结果 1.1.90 在 1.2.0 的机器上会同时出现
                        // 「最新」和「旧版」——"最新"说的是云端、'旧版'说的是本机，
                        // 两个都真但读起来自相矛盾。用户关心的是"我要不要装它"，
                        // 所以以本机为基准优先。
                        when {
                            // 2026-11-01 需求：本机正在跑的这一版角标写「当前版本」；
                            // 「已安装」会被读成"这台机器装过它"，在别的版本旁边也可能成立。
                            // **按钮文案不动**（按钮仍是「已安装」）。
                            // 2026-11-03 需求 3：改用主题色淡色（primary 12% 底 + primary 字），
                            // 跟随用户在「主题色」里选的色；有更新时「最新」仍是实心主色。
                            isInstalled -> Badge("当前版本", accent = true)
                            // 需求：「旧版」标识更灰一点（比普通角标再弱一档）。
                            isOlder -> Badge("旧版", muted = true)
                            isNewest -> Badge("最新", highlighted = true)
                        }
                        // 访问角标独立于上面那组版本角标：未登录时明确告诉用户
                        // 这一版「无需登录」还是「需登录」，不跟"最新/旧版"抢位置。
                        tierLabel?.let {
                            Spacer(Modifier.size(Space.xs))
                            TierTag(text = it, needLogin = it == "需登录")
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(release.sizeBytes.toReadableSize())
                            release.uploadedAt?.let {
                                append(" · ")
                                append(it.toReadableDate())
                            }
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                // 下载按钮：**进度就长在按钮上**（2026-11-10 需求 4）。
                //
                // 原先卡片底下单独挂一条 LinearProgressIndicator，而仓库是一次性
                // `readBytes()`，进度只会回调 0 与总量 —— 用户看到的就是"突然从 0 到 100"。
                // 现在底层按 16KB 分块真实回调，按钮底色按进度从左往右铺满，
                // 百分比文字压在上面；尺寸与「下载」按钮完全一致，列表不会抖。
                //
                // ⚠️ 这里刻意**不用** LinearProgressIndicator 画填充：Material 3 的
                // 线性进度条会在进度末端画一枚「停止指示圆点」，圆点直径跟着轨道高度走；
                // 按钮被拉满高度后它就变成一个盖住百分比文字的大圆色块（模拟器 14%
                // 那一帧实测可见）。改用纯色 Box 按 animated 分数铺宽，形状完全可控。
                if (downloading) {
                    // 用一个短补间把每个分块之间的台阶抹平；真实进度仍决定终点，
                    // 不做"假装还在下"的模拟进度。
                    val animated by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                        label = "apk-download-fraction"
                    )
                    Box(
                        modifier = Modifier
                            .size(RELEASE_BUTTON_WIDTH, RELEASE_BUTTON_HEIGHT)
                            .clip(ButtonDefaults.shape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .fillMaxWidth(animated.coerceIn(0f, 1f))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                        )
                        Text(
                            text = "${(animated * 100).toInt()}%",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                } else {
                    val container = when {
                        // 当前版本：浅色底（tonal），主色文字，明确"这是你现在用的"。
                        isInstalled -> MaterialTheme.colorScheme.secondaryContainer
                        // 旧版本：更弱的容器色，视觉上退后。
                        isOlder -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val content = when {
                        isInstalled -> MaterialTheme.colorScheme.onSecondaryContainer
                        isOlder -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onPrimary
                    }
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = container,
                            contentColor = content
                        ),
                        modifier = Modifier
                            .width(RELEASE_BUTTON_WIDTH)
                            .height(RELEASE_BUTTON_HEIGHT),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = when {
                                isInstalled -> "已安装"
                                isOlder -> "下载"
                                else -> "下载"
                            },
                            maxLines = 1
                        )
                    }
                }
            }
            if (release.notes != null) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    text = release.notes,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 卡片底部那条独立进度条已移除：进度改由上面的按钮自己显示，
            // 下载中卡片高度恒定，列表不会因为多出一行而跳动。
        }
    }
}

/**
 * 读不到云端时的卡片（需求 5：把「没网」与「服务端出错」拆成两种说法）。
 *
 * ## 为什么两种状态要分开
 *
 * 原来只有一句 `reason` + 重试：断网时用户看到"读取云端失败"，
 * 不知道是自己该去开 Wi-Fi，还是作者的服务挂了、只要等着。
 * 现在离线态明确写"当前没有网络 / 请检查 Wi-Fi 或移动数据"，
 * 服务端错误才显示服务端给的原始原因。
 *
 * ## 入场动画
 *
 * 错误卡是"突然出现"的：加载失败瞬间从骨架换成错误卡，会很跳。
 * 用 Compose 自带的 `AnimatedVisibility` 淡入 + 轻微上移（260ms），
 * 出发点是状态切换的连续性，不是装饰——所以有 [appeared] 这个哨兵：
 * `AnimatedVisibility(visible = true)` 首帧不会播动画，必须先在 effect 里
 * 把 `appeared` 置真，才会走 enter 过渡。
 */
@Composable
private fun FailureCard(failed: ApkListState.Failed, onRetry: () -> Unit) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    AnimatedVisibility(
        visible = appeared,
        enter = fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 10 }
    ) {
        Card2 {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (failed.offline) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (failed.offline) {
                            Icons.Rounded.WifiOff
                        } else {
                            Icons.Rounded.CloudOff
                        },
                        contentDescription = null,
                        tint = if (failed.offline) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    text = if (failed.offline) "当前没有网络" else "读取云端安装包失败",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.strongTextColor,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    // 离线时只讲"怎么办"，不把底层异常原文（如 "Failed to connect..."）
                    // 念给用户听；服务端错误才展示云端给的原因。
                    text = if (failed.offline) {
                        "安装包列表需要联网从云存储读取。" +
                            "请检查 Wi-Fi 或移动数据后重试。"
                    } else {
                        failed.reason
                    },
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(Space.lg))
                RetryButton(
                    text = if (failed.offline) "检查后重试" else "重试",
                    onRetry = onRetry
                )
            }
        }
    }
}

/**
 * 失败卡里的「重试」按钮：点一下让刷新图标转一圈。
 *
 * 为什么不用 `pressScale`：按钮本身自带涟漪，再叠缩放会出现两种反馈；
 * 这里把"正在/刚刚执行了刷新"表达在图标上，更贴合"重试"的语义。
 */
@Composable
private fun RetryButton(text: String, onRetry: () -> Unit) {
    var spinCount by remember { mutableStateOf(0) }
    val angle by animateFloatAsState(
        targetValue = spinCount * 360f,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "apk-retry-spin"
    )
    Button(
        onClick = {
            spinCount++
            onRetry()
        },
        modifier = Modifier.height(42.dp),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier
                .size(16.dp)
                .rotate(angle)
        )
        Spacer(Modifier.width(Space.sm))
        Text(text)
    }
}

/**
 * 加载态头部卡（需求 4）。
 *
 * 比原来一行 `CircularProgressIndicator + 一句话` 多了三件事：
 * 1. 转圈放在 44dp 主色淡底圆里，尺寸 22dp / 描边 2.5dp——比默认 40dp 细描边更精致，
 *    也不会在页面里显得突兀；
 * 2. 两行文案说明"在干什么 / 卡在哪一步"，等待超过 1 秒时比一个孤零零的圈更有信息量；
 * 3. 与下方两张骨架卡同一套卡片外观，视觉上是"页面正在成形"而不是"一片空白"。
 */
@Composable
private fun LoadingHeaderCard() {
    Card2 {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp
                )
            }
            Spacer(Modifier.width(Space.md))
            Column {
                Text(
                    text = "正在读取云端安装包…",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "正在连接云存储，获取版本列表",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * 骨架卡（需求 4）：模拟 [ReleaseCard] 的版式——左侧两行文字、右侧按钮、底部一行说明。
 *
 * 呼吸动画由 `rememberInfiniteTransition` 驱动（Compose 自带，无第三方库）：
 * 透明度在 0.45→1 之间往返，900ms 一个来回。骨架卡本身不是真实内容，
 * 不需要滚出去时回收动画——它只在加载期存在。
 */
@Composable
private fun ApkSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "apk-skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "apk-skeleton-pulse"
    )
    Card2 {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SkeletonBar(width = 92.dp, height = 16.dp, pulse = pulse)
                    Spacer(Modifier.height(Space.sm))
                    SkeletonBar(width = 150.dp, height = 11.dp, pulse = pulse)
                }
                // 与真实卡片的下载按钮同尺寸，避免加载完成时整页高度跳变。
                SkeletonBar(
                    width = RELEASE_BUTTON_WIDTH,
                    height = RELEASE_BUTTON_HEIGHT,
                    pulse = pulse,
                    corner = 20.dp
                )
            }
            Spacer(Modifier.height(Space.md))
            SkeletonBar(width = 220.dp, height = 11.dp, pulse = pulse)
        }
    }
}

/** 骨架里的一根条：圆角 + 随 [pulse] 呼吸的浅色底。 */
@Composable
private fun SkeletonBar(width: Dp, height: Dp, pulse: Float, corner: Dp = 6.dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(corner))
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f + 0.05f * pulse)
            )
    )
}

/** 居中一句话（加载中 / 空态）。 */
@Composable
private fun CenterNote(text: String, showSpinner: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showSpinner) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.height(Space.md))
        } else {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(Space.md))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 小角标（最新 / 当前版本 / 旧版）。
 *
 * 2026-11-03 需求 3：新增 [accent] ——「当前版本」不再用灰底，
 * 而是「主题色淡底 + 主题色文字」（primary 12% 背景 + primary 前景）。
 * 之所以用 `primary.copy(alpha = …)` 而不是写死某个色号：
 * 1. 用户可以在设置里换主题色，写死颜色就跟不过去；
 * 2. 深浅色模式下 primary 由 M3 色板保证与背景的对比度，淡底不会变成"看不见"。
 */
@Composable
private fun Badge(
    text: String,
    highlighted: Boolean = false,
    muted: Boolean = false,
    accent: Boolean = false
) {
    val bg by animateColorAsState(
        targetValue = when {
            highlighted -> MaterialTheme.colorScheme.primary
            // 需求 3：「当前版本」用主题色的淡色底。
            accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            // 「旧版」：比普通灰角标再弱一档，几乎融入背景。
            muted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        },
        label = "apkBadgeBg"
    )
    // 文字色也跟着做过渡：主题色切换时底色与字色一起变，不会出现"底变了字没变"的闪帧。
    val fg by animateColorAsState(
        targetValue = when {
            highlighted -> MaterialTheme.colorScheme.onPrimary
            accent -> MaterialTheme.colorScheme.primary
            muted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        },
        label = "apkBadgeFg"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = fg
        )
    }
}

/** 本页统一的白卡外观（与声音设置页的 `SettingsGroup` 同一口径）。 */
@Composable
private fun Card2(content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        content()
    }
}

/** 字节数 → `8.4 MB` / `820 KB`。 */
private fun Long.toReadableSize(): String {
    if (this <= 0L) return "大小未知"
    val kb = this / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$this B"
    }
}

/**
 * 清单里的时间 → `2026-10-31`。
 *
 * 上传脚本写的是 ISO 8601（`2026-10-31T14:40:00+08:00`）；服务端列目录回来的
 * 可能是 `2026-10-31T06:40:00.000Z` 甚至带毫秒。这里只取前 10 个字符，
 * 不做时区换算——它只是"这一版是哪天发的"，差一天不影响决策，而做换算要引入
 * `java.time` 的解析分支（两种格式都能抛异常），收益不成比例。
 */
private fun String.toReadableDate(): String {
    val head = take(10)
    return if (head.length == 10 && head[4] == '-' && head[7] == '-') head else this
}

/** 项目仓库主页（标题栏 GitHub 按钮的跳转目标）。 */
private const val GITHUB_REPO_URL = "https://github.com/SciF-Lin/MoreCheck"

/** 正式版免登录下载的去处：浏览器打开 Releases 页，用户自行挑选资产。 */
private const val GITHUB_RELEASES_URL = "$GITHUB_REPO_URL/releases"

/** 列表里所有下载按钮的统一尺寸（需求：不随文案变化，列表不抖）。 */
private val RELEASE_BUTTON_WIDTH = 92.dp
private val RELEASE_BUTTON_HEIGHT = 40.dp
