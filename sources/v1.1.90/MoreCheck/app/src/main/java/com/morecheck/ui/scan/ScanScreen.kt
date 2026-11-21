package com.morecheck.ui.scan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
// ⚠️ `animateFloat` 是 `InfiniteTransition` 的**扩展函数**（不是 `animateFloatAsState`），
// 少这行 import 时编译器只会报「Unresolved reference 'animateFloat'」并连带把
// 后面两个具名参数的「Cannot infer type」也报出来——那是缺 import 的连带错误，
// 不是参数写错了。扫描线要用它。
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
// ⚠️ `TextSnippet` 必须用 AutoMirrored 版本：`Icons.Filled.TextSnippet` 已被
// material-icons 标记为 deprecated（编译期警告），AutoMirrored 版本在 RTL 语言下
// 会把「文档 + 文字行」的方向镜像过来，与项目里 ArrowBack / Send 的既有纪律一致。
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MoreCheckApp
import com.morecheck.ui.theme.MoreCheckScanTheme
import com.morecheck.util.AppPermissions
import com.morecheck.util.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 扫一扫页面的固定配色。
 *
 * 照设计稿写死、**不接主题色**：取景页保持中性深色，才不会与相机画面里的真实颜色互相干扰。
 */
private object ScanColors {
    val Background = Color(0xFF101014)
    val Sheet = Color(0xFF18181B)
    /** rgba(255,255,255,0.05)：卡片描边用的「微光」线。 */
    val Hairline = Color(0x0DFFFFFF)
    /** 圆形按钮 / 内容框的浅色底。 */
    val Surface = Color(0x14FFFFFF)
    val SurfaceBorder = Color(0x14FFFFFF)
    val BrandBlue = Color(0xFF2F6BFF)
    val Frame = Color(0xFF4C8DFF)
    val TextSecondary = Color(0x99FFFFFF)
}

// ---- 视觉常量 ---------------------------------------------------------------
//
// 本页新增的尺寸 / 时长 / 不透明度一律抽成有名字的常量：
// 一是正文里不再散落魔法数字，二是这些参数的**取值理由**集中写在这里，
// 以后微调时不必回到绘制代码里去猜当初为什么写这个数。
//
// 命名一律以 `Scan` 开头，避免与同包其它文件的顶层私有名字撞车。

/** 顶部渐变遮罩：起点（屏幕最上方）不透明度。相机画面可能是白墙，0.62f 才压得住。 */
private const val ScanTopScrimTopAlpha = 0.62f
/**
 * 顶部渐变遮罩：中段（渐变的 50% 处）不透明度。
 *
 * 留一个「平台期」而不是让不透明度从顶到底一路线性掉到 0：
 * 标题正好落在中段附近，若这里已经很淡，白底画面下标题就会发虚。
 */
private const val ScanTopScrimMidAlpha = 0.26f
/** 返回按钮圆形底色不透明度：有了渐变遮罩兜底，比原先的 0.4 更轻，视觉重心让给标题。 */
private const val ScanTopButtonScrimAlpha = 0.32f
/** 返回按钮 44dp：保持原尺寸不动，44dp 是可点区域的下限。 */
private val ScanTopButtonSize = 44.dp
/** 返回按钮 / 标题扫码图标尺寸。 */
private val ScanTopIconSize = 20.dp
/**
 * 顶部渐变遮罩的高度。
 *
 * 要明显高于顶栏本身（状态栏 + 44dp 按钮 ≈ 约 90dp），渐变才有地方「慢慢淡出」；
 * 取 132dp 是让遮罩尾部刚好探到取景框上沿附近，与取景框的框外压暗自然衔接，
 * 中间不留一条没被压暗的亮带。
 */
private val ScanTopScrimHeight = 132.dp
/** 标题字号：17.sp（原 18.sp）＋ 0.5.sp 字距，与 44dp 的返回键重量更接近。 */
private val ScanTopTitleSize = 17.sp
private val ScanTopTitleLetterSpacing = 0.5.sp
/** 扫码图标与标题之间的间距：比原先的 8dp 略收，配合加大后的字距更像一个整体词组。 */
private val ScanTopIconGap = 7.dp

/** 取景框边长与位置比例：沿用原设计稿的比例，抽名字只是为了去掉魔法数字。 */
private const val ScanFrameWidthRatio = 0.68f
private const val ScanFrameHeightRatio = 0.40f
private const val ScanFrameTopRatio = 0.16f
/**
 * 取景框外压暗不透明度。
 *
 * 取 0.45f（与描边 alpha 同值，视觉上属于同一档「轻」）而不是更高：
 * 框外仍需看得见轮廓，用户才知道自己大概对着哪儿；压到 0.7 以上会像「黑屏挖了个洞」，
 * 反而让人怀疑相机是不是坏了。
 */
private const val ScanFrameScrimAlpha = 0.45f
/** 扫描线单程时长（毫秒）。2000ms 走完整个取景框，接近真实扫描器的光带走速。 */
private const val ScanSweepDurationMillis = 2000
/** 扫描线在圆角之外再让开的距离，避免光带贴到边框、看起来像撞上去。 */
private val ScanSweepEndInset = 10.dp
/** 扫描线粗细：2dp 比描边（1.5dp）略粗，层级上高于边框。 */
private val ScanSweepStrokeWidth = 2.dp
/** 扫描线上下光晕的总高度。 */
private val ScanSweepGlowHeight = 44.dp
/** 光晕峰值不透明度：0.16f 只做「余晖」，再高就会盖住二维码本身。 */
private const val ScanSweepGlowAlpha = 0.16f

/** 出结果时整屏压暗的不透明度：0.35f 足够让卡片浮起来，又保留取景画面的空间感。 */
private const val ScanResultScrimAlpha = 0.35f

/** 结果卡片：下拉把手 / 标题 / 类型行 / 操作区之间的垂直间距，统一成一套节奏。 */
private val ScanSheetHandleGap = 16.dp
private val ScanSheetHeaderGap = 16.dp
private val ScanSheetTypeGap = 16.dp
private val ScanSheetActionsGap = 24.dp
/** 下拉把手尺寸。 */
private val ScanSheetHandleWidth = 36.dp
private val ScanSheetHandleHeight = 4.dp
/** 内容块圆角与内边距：比卡片顶部的 28dp 小一档，形成「卡片 → 块」的层次。 */
private val ScanSheetBlockRadius = 16.dp
private val ScanSheetBlockPaddingH = 16.dp
private val ScanSheetBlockPaddingV = 14.dp
/** 类型徽标：34dp 圆底 + 18dp 图标，只做「类型指示」，不冒充品牌 Logo。 */
private val ScanTypeBadgeSize = 34.dp
private val ScanTypeBadgeIconSize = 18.dp
/** 徽标与类型文字之间的间距。 */
private val ScanTypeBadgeGap = 8.dp
/** 四个操作按钮统一 48dp 圆底 + 20dp 图标 + 8dp 标签间距。 */
private val ScanActionButtonSize = 48.dp
private val ScanActionIconSize = 20.dp
private val ScanActionLabelGap = 8.dp

/** 结果卡片标题字号：17.sp 比正文大一档即可，卡片里不需要「大标题」的体量。 */
private val ScanSheetTitleSize = 17.sp
/** 标题字距：与顶部栏取同一值，让卡片标题和页面标题属于同一套排版语气。 */
private val ScanSheetTitleLetterSpacing = 0.5.sp
/** 类型标签字号。 */
private val ScanSheetTypeLabelSize = 13.sp
/** 识别内容字号。 */
private val ScanSheetBodyTextSize = 14.sp
/**
 * 操作按钮标签字号 11.sp：四个按钮等分宽度后每个只有约 80dp，
 * 「复制文本」四个字必须用 11.sp 才不折行；再小就影响可读了。
 */
private val ScanSheetActionLabelSize = 11.sp
/** 下拉把手不透明度：沿用原值，把手只负责「提示可拖动」，不该抢戏。 */
private const val ScanSheetHandleAlpha = 0.55f
/**
 * 类型徽标底色不透明度：**复用已有的 [ScanColors.BrandBlue]**，靠 alpha 压成淡色底。
 * 这样既保留原有品牌蓝的痕迹，又不会像原来那块 72dp 实心蓝方块那样像个占位 Logo。
 */
private const val ScanTypeBadgeAlpha = 0.18f

/**
 * 识别结果的内容类型，仅用于展示标签与决定「访问」是否可用。
 *
 * @param label 展示用的中文类型名。
 * @param icon 类型徽标图标。**只是「哪一类」的指示符**，不是品牌 Logo：
 *   用 Material 里语义最贴近的现成图标，避免凭空画一个像厂家商标的图形。
 */
private enum class ScanContentType(val label: String, val icon: ImageVector) {
    URL("网址", Icons.Filled.Link),
    WIFI("WiFi", Icons.Filled.Wifi),
    PHONE("电话号码", Icons.Filled.Phone),
    EMAIL("邮箱", Icons.Filled.AlternateEmail),
    TEXT("文本", Icons.AutoMirrored.Filled.TextSnippet);

    companion object {
        fun of(raw: String): ScanContentType {
            val text = raw.trim()
            return when {
                text.startsWith("http://", true) || text.startsWith("https://", true) -> URL
                text.startsWith("WIFI:", true) -> WIFI
                text.startsWith("tel:", true) -> PHONE
                text.startsWith("mailto:", true) -> EMAIL
                else -> TEXT
            }
        }
    }
}

/**
 * 「扫一扫」。
 *
 * 交互与视觉按设计稿：
 * - 全屏相机取景 + 圆角取景框（四角加粗、框外压暗、上下往返的扫描线）；
 * - 顶部：左侧 44dp 纯圆返回按钮，居中「扫一扫」标题 + 扫码图标，
 *   整条顶栏压一层由上到下渐隐的黑色遮罩，保证白底画面下也看得清；
 * - 识别到底部弹出深灰卡片（顶圆角 28dp，rgba(255,255,255,0.05) 微光描边）：
 *   类型徽标（34dp 圆底 + 类型图标，与类型标签同行）+ 内容框 + 四个等分宽度的操作按钮；
 * - 所有可点元素按下缩到 **scale(0.92)** 并伴随触觉反馈；
 * - 操作成功在屏幕中央浮出半透明提示框。
 *
 * 状态：识别到一条结果后 [QrCodeAnalyzer.paused] 置真停止重复回调；
 * **轻触取景区或把卡片下拉**即可继续扫描；返回箭头退出本页。
 *
 * 另外，本页受「设置 → 通用 → 权限管理」里的**摄像头开关**约束：
 * 开关关着时不初始化相机、也不弹系统权限框，只显示一句明确提示
 * （见下方 `cameraGateBlocked` 的短路分支）。开关打开时，本页原有行为完全不变。
 */
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 「设置 → 通用 → 权限管理」里的摄像头开关。
    //
    // 用 collectAsStateWithLifecycle 持续读，而不是进页面读一次：用户在设置页改完开关
    // 再回到本页时要立刻生效。
    //
    // 初值刻意给 `null` 表示「还没读到持久化值」：如果直接拿 false 当初值，
    // 开关本来是开的用户进本页会先闪一下「未开启」提示，看起来像 bug。
    //
    // 两个兜底分支都退到 `true`（即「不拦」），理由一致：**不能因为读不到开关状态
    // 就把扫码功能锁死**——那会表现为「进扫一扫永远黑屏」，用户完全无从下手。
    // 兜底到「不拦」等于退回本页改动之前的既有行为，是风险最小的选择。
    //   · 取不到容器（理论上不会发生）；
    //   · DataStore 读失败（`data` 流会抛 IOException）。
    // （`Flow` 是协变的，`Flow<Boolean>` 可以直接当作 `Flow<Boolean?>` 用。）
    val cameraToggleFlow: Flow<Boolean?> = remember(context) {
        val repository = (context.applicationContext as? MoreCheckApp)
            ?.container?.settingsRepository
        if (repository == null) {
            flowOf(true)
        } else {
            repository.cameraEnabled.catch { emit(true) }
        }
    }
    val cameraToggle by cameraToggleFlow.collectAsStateWithLifecycle(initialValue = null)
    /** 是否已经读到开关的持久化值。 */
    val cameraGateKnown = cameraToggle != null
    /** 开关是否为「开」。 */
    val cameraGateOn = cameraToggle == true
    /** 开关已知且为「关」——只有这种确定状态下才短路。 */
    val cameraGateBlocked = cameraGateKnown && !cameraGateOn

    var decoded by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<String?>(null) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // 权限申请：**新增门控** —— 摄像头开关关着（或还没读到）时不弹系统权限框。
    // 开关未知（null）时也先不申请，等 DataStore 的初值读出来再决定，
    // 否则开关本来是开的用户会白白被弹一次系统框。
    // 其余行为与原先完全一致：仍未授权才 launch。
    LaunchedEffect(cameraGateKnown, cameraGateOn) {
        if (!cameraGateOn) return@LaunchedEffect
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 分析回调在相机线程上，切回主线程再写状态。
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val analyzer = remember {
        QrCodeAnalyzer { text -> mainHandler.post { decoded = text } }
    }

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(granted, cameraGateOn) {
        // **新增门控**：开关关着就不绑相机。这是唯一新增的短路点，
        // 下面原有的「初始化失败 / 绑定失败」处理逻辑一行未动。
        if (!cameraGateOn) {
            // 开关被拨回关闭时，把已经绑上的相机关掉（幂等，且与 onDispose 里的解绑不冲突）：
            // 否则相机会一直占着后台资源，用户会看到「开关关了但摄像头指示灯还亮着」。
            runCatching { cameraProvider?.unbindAll() }
            return@LaunchedEffect
        }
        if (!granted) return@LaunchedEffect
        val provider = runCatching { awaitCameraProvider(context) }.getOrElse {
            cameraError = "相机初始化失败"
            return@LaunchedEffect
        }
        cameraProvider = provider
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisExecutor, analyzer) }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            cameraError = null
        }.onFailure {
            cameraError = "无法启动相机：${it.message ?: it::class.java.simpleName}"
        }
    }

    // 离开本页时解绑并释放分析线程池；lifecycleOwner 是本导航条目，销毁时 CameraX 也会自动解绑。
    DisposableEffect(Unit) {
        onDispose {
            // 只解绑已经拿到手的 provider，避免在 onDispose 里阻塞等待单例初始化。
            runCatching { cameraProvider?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    // 提示框自动消失。
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    // 旋转重建后 decoded 会被恢复，但 analyzer 是全新的（paused=false），
    // 这里把暂停状态同步回去，避免结果卡片刚恢复就被下一帧扫码覆盖。
    // 只置真、不置假，因此不会和 resumeScan() 抢。
    LaunchedEffect(decoded != null) {
        if (decoded != null) analyzer.paused = true
    }

    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingSave
        pendingSave = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                }.isSuccess
            }
            toast = if (ok) "已保存为文本文件" else "保存失败"
        }
    }

    // 本页固定深色，状态栏 / 导航栏图标需要切成**浅色**，
    // 否则在浅色主题下顶部的浅底图标会与深色背景糊在一起。
    // 离开时把进入前的取值原样写回，不影响其它页面。
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller == null) {
            onDispose { }
        } else {
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNav = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNav
            }
        }
    }

    /** 继续扫描：解除暂停并收起结果卡片。 */
    fun resumeScan() {
        analyzer.paused = false
        decoded = null
    }

    MoreCheckScanTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(ScanColors.Background)
        ) {
            // **新增门控**：开关确定关闭时，相机不绑定，取景画面也不显示。
            val cameraReady = cameraGateOn && granted && cameraError == null

            if (cameraReady) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                ScanFrameOverlay(Modifier.fillMaxSize())
            }

            // 已有结果时，轻触取景区即可继续扫描。
            // 必须画在顶部栏**之前**：否则这个全屏点击层会盖住返回按钮，导致有结果时退不出去。
            if (decoded != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // 出结果时把取景画面压暗，让底部卡片浮起来。
                        // 这一层本来就是「点哪儿都能继续扫描」的全屏点击层，
                        // 顺便让它承担遮罩职责，不必再插一个 Box 改动层级顺序（顺序一错返回键就被盖住）。
                        .background(Color.Black.copy(alpha = ScanResultScrimAlpha))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { resumeScan() }
                )
            }

            ScanTopBar(onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))

            // **新增门控**：开关确定关闭时，改用专门的提示层——
            // 这里刻意不复用下面的 ScanBlockedHint：那一个的动作是「申请系统权限」，
            // 而当前的问题在**应用开关**上，去系统设置里授权也解决不了，
            // 所以这里的主按钮是「返回上一页」（用户回设置里打开开关），
            // 次按钮才留着跳系统设置（应对系统权限同样没给的情况）。
            if (cameraGateBlocked) {
                ScanCameraOffHint(
                    onBack = onBack,
                    onOpenSystemSettings = { AppPermissions.openAppSettings(context) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (!cameraReady) {
                ScanBlockedHint(
                    message = cameraError ?: "需要相机权限才能扫码",
                    showGrant = !granted,
                    onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onSettings = { openAppSettings(context) },
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            decoded?.let { text ->
                ScanResultSheet(
                    text = text,
                    onDismiss = { resumeScan() },
                    onOpen = { openUrl(context, text) { toast = it } },
                    onCopy = {
                        copyText(context, text)
                        toast = "已复制到剪贴板"
                    },
                    onSave = {
                        pendingSave = text
                        saveLauncher.launch(defaultTextFileName())
                    },
                    onShare = { shareText(context, text) { toast = it } },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            ScanToast(message = toast, modifier = Modifier.align(Alignment.Center))
        }
    }
}

// ---- 顶部栏 ---------------------------------------------------------------

/**
 * 顶部栏：一层由上到下渐隐的黑色遮罩 + 返回键与居中标题。
 *
 * 加遮罩的**理由**：相机画面不可控，镜头对着白墙 / 天空时纯白底上的白字完全看不见。
 * 渐变（而不是一块纯色条）是为了让遮罩在取景区上方自然消隐，不出现一条生硬的横向分界线。
 *
 * `statusBarsPadding()` 保留：标题与返回键仍要给状态栏让位。
 */
@Composable
private fun ScanTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        // 遮罩单独作为一个「贴屏幕物理顶边、固定高度」的图层，而不是直接 background 到外层：
        // 外层要垫 statusBarsPadding，若把渐变画在外层，渐变的 0% 端点会从状态栏下沿才开始，
        // 状态栏那一段就会露出没被遮罩覆盖的原始画面。
        //
        // 注意：Box 的尺寸取子项最大值，所以外层会被这个 132dp 的遮罩顶高。
        // 这是**无害**的——外层在根 Box 里是 TopCenter 对齐，内容层又是 TopStart 对齐，
        // 返回键与标题的实际位置与改动前逐像素一致；遮罩自身不接收点击，
        // 落在它上面（按钮以外）的手势照旧穿透到下层「轻触继续扫描」的点击层。
        Box(
            Modifier
                .fillMaxWidth()
                .height(ScanTopScrimHeight)
                .align(Alignment.TopCenter)
                // 用「颜色列表」重载（三个色标默认落在 0% / 50% / 100%），
                // 而不是 Pair 变参重载：不透明度从顶端 0.62 收到中段 0.26、再到底部全透明，
                // 中段留一点「平台期」是为了让标题在白底画面下依然清晰。
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = ScanTopScrimTopAlpha),
                            Color.Black.copy(alpha = ScanTopScrimMidAlpha),
                            Color.Transparent
                        )
                    )
                )
        )

        // 内容层：负责真正的排版与内边距，高度由返回按钮（44dp）撑出来，与改动前一致。
        Box(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            CircleIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    // 扫码图标退成次级色：它是标题的修饰，不该和标题抢注意力。
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(ScanTopIconSize)
                )
                Spacer(Modifier.width(ScanTopIconGap))
                Text(
                    text = "扫一扫",
                    color = Color.White,
                    fontSize = ScanTopTitleSize,
                    // 字号收细一档 + 加一点字距，中文标题在两字/三字时更「立得住」。
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = ScanTopTitleLetterSpacing
                )
            }
        }
    }
}

/** 44dp 纯圆图标按钮：按下缩到 0.92 并触发一次触觉反馈。 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "circleIconPress"
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .size(ScanTopButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            // 原先写死 0x66000000（40%）。现在顶栏自带渐变遮罩，圆底再压这么重就「两块黑叠一起」发闷，
            // 所以减到 [ScanTopButtonScrimAlpha]，保留玻璃胶囊的轮廓感即可。
            .background(Color.Black.copy(alpha = ScanTopButtonScrimAlpha))
            .border(1.dp, ScanColors.Hairline, CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(ScanTopIconSize)
        )
    }
}

// ---- 取景框 ---------------------------------------------------------------

/**
 * 取景框：框外压暗 + 一圈淡描边 + 四角加粗括号 + 上下往返的扫描线。
 *
 * 三件事各自的**理由**：
 * - **框外压暗**：注意力收进框内，也让浅色画面上本来就偏淡的描边重新变得可读。
 *   用「大矩形 + 内嵌圆角矩形」组成的偶奇填充路径挖洞，而不是画四条矩形条：
 *   四条矩形只能挖出方角洞，圆角处会留下没压暗的碎块，边缘看起来是脏的。
 * - **扫描线**：给「正在识别」一个持续动效，这是扫码页最需要传达的状态；
 *   没有它时，相机画面是静止的，用户会怀疑是不是卡住了。
 * - **四角括号**：保留原有语言（加粗、圆头端帽），不引入新颜色。
 *
 * 动画参数说明见各处注释；整体只用 Compose 自带能力，无新依赖。
 */
@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    val outlineWidth = 1.5.dp
    val bracketWidth = 3.dp
    val bracketLength = 26.dp
    val cornerRadius = 22.dp

    // 往复扫描线：0 → 1 为「从上到下」，Reverse 让它自动折返，形成上下往返。
    //
    // 刻意用 LinearEasing：真实扫描器的光带是匀速推进的；若用 FastOutSlowIn 之类的缓动，
    // 光带会在两端「粘」一下再弹出去，看起来像卡顿而不是扫描。
    // 单程 [ScanSweepDurationMillis]（2s）是「看得出在动、又不晃眼」的区间：
    // 快到 1s 以内会显得焦躁，慢过 3s 又会被当成静止。
    val sweep = rememberInfiniteTransition(label = "scanFrameSweep")
    val sweepProgress by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ScanSweepDurationMillis,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanFrameSweepProgress"
    )

    Canvas(modifier) {
        val side = minOf(size.width * ScanFrameWidthRatio, size.height * ScanFrameHeightRatio)
        val left = (size.width - side) / 2f
        val top = size.height * ScanFrameTopRatio
        val radius = cornerRadius.toPx()
        val right = left + side
        val bottom = top + side

        // 框外压暗：偶奇填充 = 大矩形减去内嵌圆角矩形，得到圆角精确的「洞」。
        val hole = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(0f, 0f, size.width, size.height))
            addRoundRect(RoundRect(left, top, right, bottom, radius, radius))
        }
        drawPath(path = hole, color = Color.Black.copy(alpha = ScanFrameScrimAlpha))

        drawRoundRect(
            color = ScanColors.Frame.copy(alpha = 0.45f),
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = outlineWidth.toPx())
        )

        val len = bracketLength.toPx()
        val stroke = bracketWidth.toPx()
        val cap = StrokeCap.Round
        // 扫描线可移动的范围：两端各让开一个圆角半径 + [ScanSweepEndInset]。
        // 让开圆角是必须的——否则光带在端点会压在弧线上，看着像撞到了边框。
        val sweepInset = radius + ScanSweepEndInset.toPx()
        val sweepTop = top + sweepInset
        val sweepBottom = bottom - sweepInset
        val sweepY = sweepTop + (sweepBottom - sweepTop) * sweepProgress
        val sweepInsetX = radius
        val sweepHalf = ScanSweepGlowHeight.toPx() / 2f

        // 扫描线先画光晕再画亮芯：光晕是「上下渐隐」的横带（峰值在亮芯那一行），
        // 这样光带不会是一根硬邦邦的直线，而有一点辉光感。
        // 光晕压得比亮芯淡得多（[ScanSweepGlowAlpha]），是为了不遮住二维码本身。
        // 颜色列表重载的色标均分在 0% / 50% / 100%，正好是「透明 → 峰值 → 透明」。
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    ScanColors.Frame.copy(alpha = ScanSweepGlowAlpha),
                    Color.Transparent
                ),
                startY = sweepY - sweepHalf,
                endY = sweepY + sweepHalf
            ),
            topLeft = Offset(left + sweepInsetX, sweepY - sweepHalf),
            size = Size(side - sweepInsetX * 2f, sweepHalf * 2f)
        )
        drawLine(
            color = ScanColors.Frame,
            start = Offset(left + sweepInsetX, sweepY),
            end = Offset(right - sweepInsetX, sweepY),
            strokeWidth = ScanSweepStrokeWidth.toPx(),
            cap = cap
        )

        // 四角：各画一横一竖，起点让开圆角半径，避免和弧线打架。
        drawLine(ScanColors.Frame, Offset(left, top + radius), Offset(left, top + radius + len), stroke, cap)
        drawLine(ScanColors.Frame, Offset(left + radius, top), Offset(left + radius + len, top), stroke, cap)
        drawLine(ScanColors.Frame, Offset(right, top + radius), Offset(right, top + radius + len), stroke, cap)
        drawLine(ScanColors.Frame, Offset(right - radius, top), Offset(right - radius - len, top), stroke, cap)
        drawLine(ScanColors.Frame, Offset(left, bottom - radius), Offset(left, bottom - radius - len), stroke, cap)
        drawLine(ScanColors.Frame, Offset(left + radius, bottom), Offset(left + radius + len, bottom), stroke, cap)
        drawLine(ScanColors.Frame, Offset(right, bottom - radius), Offset(right, bottom - radius - len), stroke, cap)
        drawLine(ScanColors.Frame, Offset(right - radius, bottom), Offset(right - radius - len, bottom), stroke, cap)
    }
}

// ---- 识别结果卡片 ---------------------------------------------------------

@Composable
private fun ScanResultSheet(
    text: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val type = ScanContentType.of(text)
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val dismissThreshold = with(LocalDensity.current) { 110.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = offsetY.value }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = {
                    if (offsetY.value > dismissThreshold) {
                        onDismiss()
                    } else {
                        scope.launch {
                            offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                }
            )
            .clip(shape)
            .background(ScanColors.Sheet)
            .border(1.dp, ScanColors.Hairline, shape)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 下拉把手
        Box(
            Modifier
                .width(ScanSheetHandleWidth)
                .height(ScanSheetHandleHeight)
                .clip(CircleShape)
                .background(ScanColors.TextSecondary.copy(alpha = ScanSheetHandleAlpha))
        )
        Spacer(Modifier.height(ScanSheetHandleGap))

        Text(
            text = "识别结果",
            color = Color.White,
            fontSize = ScanSheetTitleSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = ScanSheetTitleLetterSpacing
        )
        Spacer(Modifier.height(ScanSheetHeaderGap))

        // 类型徽标 + 类型标签同一行。
        //
        // 替换掉原先「72dp 蓝色圆角方块 + 白圆环」：那个图形没有任何信息量，
        // 却占了卡片最重的位置，看起来像没做完的占位 Logo。
        // 现在改成「淡蓝圆底 + 类型图标 + 类型名」，只承担「这是哪一类内容」的指示职责，
        // 不暗示任何品牌；图标也是 Material 里语义最贴近的现成图形，没有自行绘制品牌标志。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(ScanTypeBadgeSize)
                    .clip(CircleShape)
                    .background(ScanColors.BrandBlue.copy(alpha = ScanTypeBadgeAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = type.icon,
                    contentDescription = null,
                    tint = ScanColors.BrandBlue,
                    modifier = Modifier.size(ScanTypeBadgeIconSize)
                )
            }
            Spacer(Modifier.width(ScanTypeBadgeGap))
            Text(
                text = type.label,
                color = ScanColors.TextSecondary,
                fontSize = ScanSheetTypeLabelSize
            )
        }
        Spacer(Modifier.height(ScanSheetTypeGap))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ScanSheetBlockRadius))
                .background(ScanColors.Surface)
                .border(1.dp, ScanColors.SurfaceBorder, RoundedCornerShape(ScanSheetBlockRadius))
                .padding(horizontal = ScanSheetBlockPaddingH, vertical = ScanSheetBlockPaddingV)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = ScanSheetBodyTextSize,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // 内容框必须占满卡片宽度，否则短内容会让卡片看起来左右不对称。
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(ScanSheetActionsGap))

        Row(
            modifier = Modifier.fillMaxWidth(),
            // 四个按钮等分宽度（每个 weight = 1f，见 ScanAction 的 modifier）：
            // 原先用 SpaceEvenly，标签长短不一时按钮圆心并不在等分点上，看起来是「歪」的。
            verticalAlignment = Alignment.Top
        ) {
            ScanAction(Icons.Filled.OpenInBrowser, "访问", onOpen, Modifier.weight(1f))
            ScanAction(Icons.Filled.ContentCopy, "复制文本", onCopy, Modifier.weight(1f))
            ScanAction(Icons.Filled.SaveAlt, "保存文本", onSave, Modifier.weight(1f))
            ScanAction(Icons.Filled.Share, "分享", onShare, Modifier.weight(1f))
        }
    }
}

/**
 * 操作按钮：等分宽度的一列（[ScanActionButtonSize] 纯圆底 + 下方标签），
 * 按下缩到 0.92 并伴随触觉反馈——这两点与原实现一致，只把尺寸与标签字号统一。
 *
 * @param modifier 由调用方传入 `weight(1f)` 实现四等分；默认空修饰符。
 */
@Composable
private fun ScanAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scanActionPress"
    )
    val haptic = LocalHapticFeedback.current

    // 外层 Column 吃下 weight(1f) 后自身居中，保证四个按钮的圆心严格落在四等分点上，
    // 与标签长短无关。
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(ScanActionButtonSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(ScanColors.Surface)
                .border(1.dp, ScanColors.SurfaceBorder, CircleShape)
                .clickable(interactionSource = interaction, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(ScanActionIconSize)
            )
        }
        Spacer(Modifier.height(ScanActionLabelGap))
        Text(
            text = label,
            color = ScanColors.TextSecondary,
            fontSize = ScanSheetActionLabelSize,
            maxLines = 1
        )
    }
}

// ---- 权限 / 错误提示、中央提示框 -------------------------------------------

@Composable
private fun ScanBlockedHint(
    message: String,
    showGrant: Boolean,
    onGrant: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = ScanColors.TextSecondary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = message,
            color = Color.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        if (showGrant) {
            Button(onClick = onGrant) { Text("授予相机权限") }
            Spacer(Modifier.height(4.dp))
        }
        TextButton(onClick = onSettings) {
            Text("打开系统设置", color = ScanColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

/**
 * 「设置 → 通用 → 权限管理」里的摄像头开关关着时的提示层。
 *
 * 与 [ScanBlockedHint] 的区别：那一个是**系统权限**没给，主按钮是「申请权限」；
 * 这一个的根因在**应用开关**上，去系统设置里授权也没用，
 * 所以主按钮是「返回上一页」（回设置页把开关打开），
 * 次按钮才留一个跳系统设置的入口，应对「开关开了但系统也没授权」的复合情况。
 *
 * 视觉沿用本页原有的深色语言（同一个 [ScanColors] 调色板），不引入新风格。
 */
@Composable
private fun ScanCameraOffHint(
    onBack: () -> Unit,
    onOpenSystemSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.NoPhotography,
            contentDescription = null,
            tint = ScanColors.TextSecondary,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "摄像头权限未开启",
            color = Color.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "请到「设置 → 通用 → 权限管理」打开摄像头开关",
            color = ScanColors.TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onBack) { Text("返回去开启") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenSystemSettings) {
            Text("打开系统设置", color = ScanColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** 屏幕中央的半透明提示框：淡入 + 轻微放大，1.6 秒后自动消失。 */
@Composable
private fun ScanToast(message: String?, modifier: Modifier = Modifier) {
    var lastMessage by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (!message.isNullOrEmpty()) lastMessage = message
    }

    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.92f, animationSpec = tween(160)),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.92f, animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE6242428))
                .border(1.dp, ScanColors.Hairline, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 14.dp)
        ) {
            Text(text = lastMessage, color = Color.White, fontSize = 14.sp)
        }
    }
}

// ---- 平台动作 -------------------------------------------------------------

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cont.resume(it) }
                    .onFailure { cont.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

/** 只有形如 http(s)://host 的内容才交给浏览器，其余给一句提示。 */
private fun openUrl(context: Context, text: String, onMessage: (String) -> Unit) {
    val trimmed = text.trim()
    val isHttp = (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) &&
        !runCatching { Uri.parse(trimmed).host }.getOrNull().isNullOrBlank()
    if (!isHttp) {
        onMessage("不是网址，无法访问")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trimmed))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    runCatching { context.startActivity(intent) }
        .onFailure { onMessage("没有可打开该链接的应用") }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("扫描结果", text))
}

private fun shareText(context: Context, text: String, onMessage: (String) -> Unit) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "分享识别结果")) }
        .onFailure { onMessage("没有可分享的应用") }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun defaultTextFileName(): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return "扫描结果-$stamp.txt"
}

