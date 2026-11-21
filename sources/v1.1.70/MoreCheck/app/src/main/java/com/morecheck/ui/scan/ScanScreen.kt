package com.morecheck.ui.scan

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

/** 识别结果的内容类型，仅用于展示标签与决定「访问」是否可用。 */
private enum class ScanContentType(val label: String) {
    URL("网址"),
    WIFI("WiFi"),
    PHONE("电话号码"),
    EMAIL("邮箱"),
    TEXT("文本");

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
 * - 全屏相机取景 + 圆角取景框（四角加粗）；
 * - 顶部：左侧 44dp 纯圆返回按钮，居中「扫一扫」标题 + 扫码图标；
 * - 识别到底部弹出深灰卡片（顶圆角 28dp，rgba(255,255,255,0.05) 微光描边）：
 *   品牌图标（圆角 14dp）+ 类型标签 + 内容框 + 四个 44dp 纯圆操作按钮；
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

@Composable
private fun ScanTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
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
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "扫一扫",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
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
            .size(44.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(Color(0x66000000))
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
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---- 取景框 ---------------------------------------------------------------

/** 取景框：一圈淡描边 + 四角加粗括号，帮助用户对准。 */
@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    val outlineWidth = 1.5.dp
    val bracketWidth = 3.dp
    val bracketLength = 26.dp
    val cornerRadius = 22.dp

    Canvas(modifier) {
        val side = minOf(size.width * 0.68f, size.height * 0.40f)
        val left = (size.width - side) / 2f
        val top = size.height * 0.16f
        val radius = cornerRadius.toPx()

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
        val right = left + side
        val bottom = top + side
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
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(ScanColors.TextSecondary.copy(alpha = 0.55f))
        )
        Spacer(Modifier.height(18.dp))

        Text(
            text = "识别结果",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(18.dp))

        // 品牌图标：圆角 14dp 方块 + 白色圆环
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ScanColors.BrandBlue),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
            )
        }
        Spacer(Modifier.height(10.dp))

        Text(text = type.label, color = ScanColors.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ScanColors.Surface)
                .border(1.dp, ScanColors.SurfaceBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(26.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ScanAction(Icons.Filled.OpenInBrowser, "访问", onOpen)
            ScanAction(Icons.Filled.ContentCopy, "复制文本", onCopy)
            ScanAction(Icons.Filled.SaveAlt, "保存文本", onSave)
            ScanAction(Icons.Filled.Share, "分享", onShare)
        }
    }
}

/** 44dp 纯圆操作按钮 + 下方标签，按下缩到 0.92 并伴随触觉反馈。 */
@Composable
private fun ScanAction(icon: ImageVector, label: String, onClick: () -> Unit) {
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

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(44.dp)
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
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = ScanColors.TextSecondary,
            fontSize = 11.sp,
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

/** 从可能被包装过的 Context 里取出宿主 Activity（取不到就返回 null，调用方按无窗口处理）。 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
