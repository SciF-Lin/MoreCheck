package com.morecheck.ui.mine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户缩放倍数上限。
 *
 * 下限恒为 1（见 [CropMath.clampScale]）：1 恰好等于「图片刚好填满取景框」，
 * 再小就会在框里露出空白。上限 8 倍足够看清细节，又不至于让图糊成一片。
 */
private const val MAX_USER_SCALE = 8f

/** 取景框边长占视口短边的比例。留出边距，让用户看得见框外的画面。 */
private const val FRAME_RATIO = 0.82f

/**
 * 1:1 方形头像裁切页。
 *
 * 交互：单指拖动、双指缩放，中间固定一个正方形取景框，点右上角「保存」导出。
 *
 * ---
 * **「所见即所得」是怎么保证的（本页最要紧的一点）**
 *
 * 预览与导出若各算一套几何，两者迟早会漂移（哪怕只差一点点，用户也会发现
 * 保存出来的头像和框里看到的不是同一个画面）。所以这里把几何**只写一份**，
 * 放在 [CropMath]：
 *
 * - 状态只有三个数：`baseScale`（把原图放大到刚好填满取景框的基准比例）、
 *   `userScale`（用户双指缩放倍数，>= 1）、`offset`（相对取景框中心的平移，单位 px）；
 * - **预览**用 `Modifier.graphicsLayer { scaleX/scaleY = userScale; translation = offset }`；
 * - **导出**用 [CropMath.exportMatrix]，它复用**同样的** `userScale` 与 `offset`，
 *   只是额外乘一个「取景框 px → 导出 px」的固定比例 k。
 *
 * 也就是说两条路径共用同一组 `userScale` / `offset` / `baseScale`，
 * 导出的矩阵只是把预览的屏幕坐标线性映射到输出画布上，不存在第二套几何。
 *
 * ---
 * **这里没有真的去"裁剪 Bitmap 再显示"**。预览阶段一张图都没裁，
 * 只是对它做缩放平移；真正读像素只发生在点「保存」的那一刻。
 * 这样拖动缩放是纯 GPU 变换，不需要每帧重建 Bitmap。
 *
 * @param onCancel 用户放弃裁切（返回箭头 / 系统返回键）。
 * @param onSaved 保存成功，参数是写入的绝对路径（由调用方落进 DataStore）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvatarCropScreen(
    sourceUri: Uri,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 解码后的原图（已按 [AvatarStore.CROP_DECODE_MAX_PX] 降采样，且已被 ImageDecoder 摆正方向）。
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    // 视口尺寸：取景框边长与拖动夹取都依赖它，必须等测量结果。
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    var userScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 解码放 IO 线程：ImageDecoder 是同步且可能耗时的，放主线程会卡住首帧。
    LaunchedEffect(sourceUri) {
        val decoded = withContext(Dispatchers.IO) {
            AvatarStore.decodeForCrop(context, sourceUri)
        }
        bitmap = decoded
        decodeFailed = decoded == null
    }

    val framePx = minOf(viewport.width, viewport.height) * FRAME_RATIO

    // 系统返回键等同「取消」，避免用户被卡在裁切页里。
    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("裁切头像") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        // 解码没完成 / 正在保存 / 解码失败时不允许保存，否则会写出空白图。
                        enabled = bitmap != null && !saving,
                        onClick = {
                            val source = bitmap ?: return@TextButton
                            saving = true
                            saveFailed = false
                            // 导出 + 写盘都要离开主线程：`compress` 是同步阻塞的，
                            // 512×512 虽小，也不该卡住主线程。
                            scope.launch {
                                val path = exportAndSave(
                                    context = context,
                                    source = source,
                                    baseScale = CropMath.baseScale(
                                        source.width, source.height, framePx
                                    ),
                                    userScale = userScale,
                                    offset = offset,
                                    framePx = framePx
                                )
                                if (path != null) {
                                    onSaved(path)
                                } else {
                                    saving = false
                                    saveFailed = true
                                }
                            }
                        }
                    ) {
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 裁切区用纯黑底：这是图片编辑页的惯例，也让取景框外的区域自然变暗，
                // 不需要额外画一层半透明遮罩来表达"框外不算数"。
                .background(Color.Black)
                .onSizeChanged { viewport = it }
        ) {
            val source = bitmap
            when {
                decodeFailed -> {
                    Text(
                        text = "无法读取这张图片，请换一张试试",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                source == null -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                viewport.width > 0 && framePx > 0 -> {
                    // 基准比例：把原图放大到**刚好填满**取景框，因此 userScale = 1 时框内不会露白。
                    val baseScale = CropMath.baseScale(source.width, source.height, framePx)

                    // 布局基准用 fitScale（图片**装进整个视口**），不是 baseScale。
                    //
                    // 为什么必须这样：`Modifier.size()` 会被父约束**夹住**——
                    // 按 baseScale 算出来的尺寸往往比视口大，会被 Compose 截断到视口尺寸，
                    // 宽高各自被截的程度不同，图片就被压变形，且 graphicsLayer 的缩放
                    // 是相对于这个（已被截断的）布局尺寸，几何随之报废。
                    //
                    // fitScale 保证布局尺寸在两个方向都**不超过视口**，永远不会被夹，
                    // 于是剩下的放大倍数交给 graphicsLayer 表达：
                    //   renderScale = (baseScale * userScale) / fitScale
                    // 因为 baseScale >= fitScale，所以 renderScale >= 1，语义清晰。
                    val fitScale = CropMath.fitScale(
                        source.width, source.height,
                        viewport.width.toFloat(), viewport.height.toFloat()
                    )
                    if (fitScale > 0f) {
                        val layoutWidthDp = with(density) { (source.width * fitScale).toDp() }
                        val layoutHeightDp = with(density) { (source.height * fitScale).toDp() }
                        val renderScale = baseScale * userScale / fitScale

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(source, framePx) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        // 先算新缩放，再夹到 [1, MAX]；夹完之后重新夹一次平移，
                                        // 因为缩放变化会改变可平移的余量。
                                        val nextScale = CropMath.clampScale(userScale * zoom)
                                        val nextOffset = CropMath.clampOffset(
                                            offset = offset + pan,
                                            bitmapWidth = source.width,
                                            bitmapHeight = source.height,
                                            effectiveScale = baseScale * nextScale,
                                            framePx = framePx
                                        )
                                        userScale = nextScale
                                        offset = nextOffset
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = source.asImageBitmap(),
                                contentDescription = null,
                                // 布局尺寸与位图等比，FillBounds 就是精确铺满，不会二次介入。
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .size(layoutWidthDp, layoutHeightDp)
                                    // 与 [CropMath.exportMatrix] 共用同一组 userScale / offset。
                                    // graphicsLayer 默认绕布局中心缩放，而布局中心就是视口中心
                                    // （contentAlignment = Center），因此屏幕位移恰好等于 offset。
                                    .graphicsLayer {
                                        scaleX = renderScale
                                        scaleY = renderScale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        }
                    }

                    // 取景框：白色描边 + 三分线网格，画在图片之上。
                    Canvas(Modifier.fillMaxSize()) {
                        val frame = Size(framePx, framePx)
                        val topLeft = Offset(
                            (size.width - framePx) / 2f,
                            (size.height - framePx) / 2f
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = topLeft,
                            size = frame,
                            style = Stroke(width = 2.dp.toPx())
                        )
                        // 三分线：细一点、半透明，只作构图参考，不该抢过边框。
                        val gridColor = Color.White.copy(alpha = 0.35f)
                        val step = framePx / 3f
                        for (i in 1..2) {
                            val d = step * i
                            drawLine(
                                color = gridColor,
                                start = Offset(topLeft.x + d, topLeft.y),
                                end = Offset(topLeft.x + d, topLeft.y + framePx),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = gridColor,
                                start = Offset(topLeft.x, topLeft.y + d),
                                end = Offset(topLeft.x + framePx, topLeft.y + d),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                }
            }

            if (saveFailed) {
                Text(
                    text = "保存失败，请重试",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                )
            }

            if (saving) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/**
 * 导出并写盘。返回落盘路径；任一步失败返回 null。
 *
 * 抽成 `suspend` 函数而不是写在点击回调里：回调是同步的，
 * 而这里既要建 512×512 位图、又要做 JPEG 压缩，都该离开主线程。
 *
 * **回收说明**：导出后的临时位图与源位图都**不主动 `recycle()`**。
 * 源位图马上还会被预览继续绘制，主动回收会撞上
 * "Canvas: trying to use a recycled bitmap" 崩溃；临时位图交给 GC 即可
 * （512×512 ARGB_8888 只有 1MB，压力可忽略）。宁可晚一点回收，也不要冒崩溃风险。
 */
private suspend fun exportAndSave(
    context: Context,
    source: Bitmap,
    baseScale: Float,
    userScale: Float,
    offset: Offset,
    framePx: Float
): String? = withContext(Dispatchers.IO) {
    // 视口还没测量出来（framePx 为 0）时不能导出：k 会变成无穷大。
    if (framePx <= 0f) return@withContext null

    val outPx = AvatarStore.EXPORT_SIZE
    val output = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
    // 用全限定名：本文件同时 import 了 Compose 的 `Canvas`（画取景框的可组合函数），
    // 两者同名，不能同时 import，这里显式写出平台类避免歧义。
    val canvas = android.graphics.Canvas(output)

    val matrix = CropMath.exportMatrix(
        bitmapWidth = source.width,
        bitmapHeight = source.height,
        baseScale = baseScale,
        userScale = userScale,
        offset = offset,
        framePx = framePx,
        outPx = outPx
    )
    // FILTER_BITMAP_FLAG：缩放时做双线性插值，否则 512 输出会有明显锯齿。
    canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))

    val ok = AvatarStore.saveAvatar(context, output)
    if (ok) AvatarStore.storedPath(context) else null
}

/**
 * 裁切的几何计算。**预览与导出共用这里的三个数**，是"所见即所得"的唯一来源。
 *
 * 坐标系约定：原点在取景框中心，向右为 +x、向下为 +y，单位 px。
 * 图片中心相对取景框中心的位移就是 `offset`。
 */
private object CropMath {

    /**
     * 基准比例：让图片**填满**取景框。
     *
     * 取长边（`max`）而不是短边：若按短边算，另一方向会不足、框内露白；
     * 按长边算则两个方向都 >= 取景框，缩放后的边长恒 >= 取景框边长。
     */
    fun baseScale(bitmapWidth: Int, bitmapHeight: Int, framePx: Float): Float {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || framePx <= 0f) return 1f
        return maxOf(
            framePx / bitmapWidth,
            framePx / bitmapHeight
        )
    }

    /**
     * 布局比例：把图片**完整装进视口**（两条边都不超出），即 `min` 而非 `max`。
     *
     * 它是 [Image] 实际布局尺寸的计算依据，存在的理由是**规避父约束夹取**：
     * 若直接按 [baseScale] 布局，得到的尺寸常常比视口大，`Modifier.size()` 会被
     * Compose 夹到视口尺寸，而宽高被夹的程度不同 → 图片变形、几何失真。
     * 用 fit-into-viewport 的尺寸就绝不会被夹，剩下的放大交给 `graphicsLayer`。
     */
    fun fitScale(bitmapWidth: Int, bitmapHeight: Int, viewW: Float, viewH: Float): Float {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewW <= 0f || viewH <= 0f) return 0f
        return minOf(viewW / bitmapWidth, viewH / bitmapHeight)
    }

    /** 用户缩放倍数夹取：下限 1（再小就露白），上限 [MAX_USER_SCALE]。 */
    fun clampScale(scale: Float): Float = scale.coerceIn(1f, MAX_USER_SCALE)

    /**
     * 平移夹取。
     *
     * 图片半边长减去取景框半边长，就是**允许的最大位移**：
     * 超过它，图片的某条边就会滑进取景框内、露出背景。
     * `coerceAtLeast(0f)` 兜住"图片比框还小"的退化情形（正常不会出现，
     * 因为 [baseScale] 保证了至少相等）。
     */
    fun clampOffset(
        offset: Offset,
        bitmapWidth: Int,
        bitmapHeight: Int,
        effectiveScale: Float,
        framePx: Float
    ): Offset {
        val halfImageW = bitmapWidth * effectiveScale / 2f
        val halfImageH = bitmapHeight * effectiveScale / 2f
        val maxX = (halfImageW - framePx / 2f).coerceAtLeast(0f)
        val maxY = (halfImageH - framePx / 2f).coerceAtLeast(0f)
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY)
        )
    }

    /**
     * 导出矩阵：把取景框内的画面线性映射到 `outPx × outPx` 的输出位图。
     *
     * 推导（与预览严格同源）：
     * 预览里，位图坐标 `p` 落到屏幕上的位置是
     *   `screen(p) = frameCenter + offset + (p - bitmapCenter) * effectiveScale`
     * 而 k = outPx / framePx 就是「屏幕像素 → 输出像素」的比例，于是
     *   `out(p) = outCenter + (screen(p) - frameCenter) * k`
     *          = outCenter + offset * k + (p - bitmapCenter) * effectiveScale * k
     *
     * 因此矩阵 = 缩放 `effectiveScale * k`，再平移让位图中心落到
     * `outCenter + offset * k`。**用的是与 `graphicsLayer` 完全相同的
     * `userScale` / `offset`**，所以框里看到的就是导出的那一块。
     */
    fun exportMatrix(
        bitmapWidth: Int,
        bitmapHeight: Int,
        baseScale: Float,
        userScale: Float,
        offset: Offset,
        framePx: Float,
        outPx: Int
    ): Matrix {
        val effectiveScale = baseScale * userScale
        val k = outPx / framePx
        val scale = effectiveScale * k
        val outCenter = outPx / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                outCenter + offset.x * k - bitmapWidth / 2f * scale,
                outCenter + offset.y * k - bitmapHeight / 2f * scale
            )
        }
    }
}
