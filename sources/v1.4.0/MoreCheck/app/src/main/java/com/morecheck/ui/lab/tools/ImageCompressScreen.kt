package com.morecheck.ui.lab.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 「实验室 → 图片压缩」。
 *
 * ## 内存安全是这一页的主线（大图 OOM 是本页唯一的"会崩"风险）
 *
 * 相册里的照片动辄 4000×3000，直接 `BitmapFactory.decodeStream` 会分配约 48MB
 * 的 ARGB_8888 位图；再算上缩放与旋转的中间副本，峰值可以翻倍。
 * 因此本页的解码路径固定为：
 *
 * 1. 第一遍 `inJustDecodeBounds = true` **只读文件头**拿原始宽高（不分配像素内存）；
 * 2. 按「最长边目标值」算出 `inSampleSize`（只取 2 的幂，平台要求）；
 * 3. 第二遍才真正解码，得到一张已经小一个量级的位图；
 * 4. 采样后再按精确最长边做一次 `createScaledBitmap`（采样只能取 2 的幂，
 *    单靠它得不到 1280 这种精确值）；
 * 5. 压缩进 `ByteArrayOutputStream`；
 * 6. 全程在 `Dispatchers.IO`。
 *
 * 仍然兜一层 `OutOfMemoryError`：命中时把 `inSampleSize` 翻倍重试（最多两次）。
 * 这是**最后一道防线**——现代 Android 上 OOM 是 `Error` 而非 `Exception`，
 * 不显式捕获会直接杀掉进程；而"稍微糊一点的压缩结果"远好于一次崩溃。
 *
 * ## 「原图」为什么也有最大边上限
 *
 * 「原图」档表示**不做用户可见的缩放**，但它仍然受 [MAX_SAFE_PIXELS] 约束：
 * 超过这个像素数就继续采样。理由是"原样解码一张 8000×6000 的扫描件"
 * 会直接 OOM，用户拿不到任何结果；相比之下，把超大的图降到 12MP 是
 * 用户能接受的降级（且**在界面上如实标注实际输出尺寸**，不假装没动过）。
 *
 * ## 结果为什么放在内存而不是落临时文件
 *
 * 压缩产物需要同时满足两件事：立刻显示大小与压缩率、以及「保存到相册」时能原样写出。
 * 用 `ByteArray` 同时满足两者，不必管临时文件的清理与跨进程可见性。
 * 代价是进程被回收后这两份状态会丢（用 `remember` 而非 `rememberSaveable` 正是承认这一点），
 * 用户重新点一次「压缩」即可——而用户的**输入参数**（质量、最长边、选中的图）
 * 全部用 `rememberSaveable` 保留，回来时不用重选。
 *
 * ## 为什么所有图片操作都走 `rememberCoroutineScope`
 *
 * 与记事本「自动落盘」那条纪律（HANDOVER 5.8）不同：那里的协程必须在组合销毁后
 * 仍把最后一次编辑写下去，所以用进程级 scope；而本页的压缩是**用户点一下才开始、
 * 结果只服务于当前屏幕**的操作，用户离开页面时任务本就该取消（省电、省内存）。
 * 因此这里用 `rememberCoroutineScope` 是正确的取舍，而不是踩了 5.8 的坑。
 *
 * @param onBack 返回上一级（实验室宫格）。
 * @param onMessage 页面级一次性提示（保存成功 / 失败原因），走全局 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageCompressScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 系统返回键等价于左上角箭头，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    // ---- 用户输入（全部 rememberSaveable）----
    //
    // 选中的图片用 **Uri 字符串**保存：`Uri` 本身不是 Saveable 类型，
    // 转成字符串是唯一可靠的存法（Photo Picker 给出的 content:// Uri 在
    // 进程重启后仍然有效，它背后是系统的媒体授权，不是我们自己的临时引用）。
    var selectedUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var quality by rememberSaveable { mutableIntStateOf(DEFAULT_QUALITY) }
    var maxEdgeOption by rememberSaveable { mutableIntStateOf(MAX_EDGE_ORIGINAL) }

    // ---- 源图信息与压缩结果 ----
    //
    // 源图信息用 `rememberSaveable` 保留（文件名 / 大小 / 尺寸都是可存进 Bundle 的标量），
    // 这样进程被回收重建后卡片上仍然写着「这张图本来多大」，不会变成一片"未知"。
    // 真正的读取放在下面的 `LaunchedEffect(selectedUriString)` 里，而不是只在
    // 选图回调里读：进程重建后回调不会重放，靠 effect 才能自动补读。
    var sourceName by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceBytes by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceWidth by rememberSaveable { mutableIntStateOf(0) }
    var sourceHeight by rememberSaveable { mutableIntStateOf(0) }

    var working by remember { mutableStateOf(false) }
    var compressedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var resultWidth by remember { mutableStateOf(0) }
    var resultHeight by remember { mutableStateOf(0) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }
    var savedOnce by remember { mutableStateOf(false) }

    // 源图信息读取：选中变化时（含进程重建后的恢复）自动重读。
    // 清空时把旧信息一并清掉，否则会出现「没选图却显示上一张的尺寸」。
    LaunchedEffect(selectedUriString) {
        val uri = selectedUriString?.let(Uri::parse)
        if (uri == null) {
            sourceName = null
            sourceBytes = null
            sourceWidth = 0
            sourceHeight = 0
            return@LaunchedEffect
        }
        val info = withContext(Dispatchers.IO) { readSourceInfo(context, uri) }
        sourceName = info?.displayName
        sourceBytes = info?.sizeBytes
        sourceWidth = info?.width ?: 0
        sourceHeight = info?.height ?: 0
    }

    // Photo Picker：与「我的信息 → 头像」「记事本 → 添加图片」同一套。
    // 它**不需要任何存储权限**，Android 13+ 走系统照片选择器，低版本由框架回退到
    // ACTION_OPEN_DOCUMENT，全平台行为一致；对比 GetContent("image/*")，
    // 它在 13+ 上不给应用任何相册访问权，隐私上更干净。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedUriString = uri.toString()
        // 换了图，上一张的压缩结果立刻作废：留着会让用户以为「这张图已经压过了」，
        // 点「保存到相册」时保存的还是上一张——这是本页最容易出现的错配。
        compressedBytes = null
        previewBitmap = null
        resultWidth = 0
        resultHeight = 0
        savedOnce = false
        errorText = null
    }

    val selectedUri = selectedUriString?.let(Uri::parse)

    ToolScreen(title = "图片压缩", onBack = onBack) {
        // ---- 选图 ----
        ToolCard(title = "选择图片") {
            if (selectedUri == null) {
                ToolHint("从相册选一张图片，选完即可设置压缩参数。")
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    text = sourceName ?: "已选择图片",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(6.dp))
                ToolHint(
                    buildString {
                        append(sourceBytes?.let(::formatBytes) ?: "大小未知")
                        if (sourceWidth > 0 && sourceHeight > 0) {
                            append(" · ${sourceWidth}×${sourceHeight}")
                        }
                        append(" · 原始图片")
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(
                onClick = {
                    errorText = null
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedUri == null) "从相册选择" else "重新选择")
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 参数 ----
        ToolCard(title = "压缩参数") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "质量",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor,
                    modifier = Modifier.weight(1f)
                )
                // 读数用等宽字体：拖动时 100 → 99 位数不变，数字不会左右跳。
                Text(
                    text = "$quality%",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = quality.toFloat(),
                onValueChange = { raw ->
                    // 回调给的是连续值，必须吸附到整数百分比：
                    // 直接存 Float 会让 DataStore/显示层出现 87.33333 这种数。
                    // 吸附到 5% 档位而不是直接 toInt：`steps` 只保证滑块位置吸附，
                    // 回调给的仍是连续值，直接取整会出现 37%（与 5% 的档位对不上）。
                    quality = ((raw / QUALITY_STEP).roundToInt() * QUALITY_STEP)
                        .coerceIn(MIN_QUALITY, MAX_QUALITY)
                    // 参数一变，旧结果立刻失效（否则大小与压缩率会与当前参数对不上）。
                    compressedBytes = null
                    previewBitmap = null
                },
                valueRange = MIN_QUALITY.toFloat()..MAX_QUALITY.toFloat(),
                // 10%~100% 每 5% 一档：共 19 档 → 17 个中间档。
                // 用过密的档位没意义（JPEG 质量差 1% 根本看不出来），
                // 但 5% 一档足以让用户精确找到「体积明显变小」的拐点。
                steps = (MAX_QUALITY - MIN_QUALITY) / QUALITY_STEP - 1,
                modifier = Modifier.fillMaxWidth()
            )
            ToolHint("数值越低体积越小、画质损失越明显；照片一般 70%~85% 足够。")

            Spacer(Modifier.height(12.dp))

            Text(
                text = "最长边缩放",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(8.dp))
            // 横向排列 + 自动换行（`FlowRow` 需要 material 的额外实验 API，
            // 这里改为两行 Row，选项数量是固定的 4 个，没必要引入额外布局）。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaxEdgeOptionChip(
                    label = "原图",
                    value = MAX_EDGE_ORIGINAL,
                    selected = maxEdgeOption,
                    onSelect = {
                        maxEdgeOption = it
                        compressedBytes = null
                        previewBitmap = null
                    }
                )
                MaxEdgeOptionChip(
                    label = "2048",
                    value = 2048,
                    selected = maxEdgeOption,
                    onSelect = {
                        maxEdgeOption = it
                        compressedBytes = null
                        previewBitmap = null
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MaxEdgeOptionChip(
                    label = "1280",
                    value = 1280,
                    selected = maxEdgeOption,
                    onSelect = {
                        maxEdgeOption = it
                        compressedBytes = null
                        previewBitmap = null
                    }
                )
                MaxEdgeOptionChip(
                    label = "800",
                    value = 800,
                    selected = maxEdgeOption,
                    onSelect = {
                        maxEdgeOption = it
                        compressedBytes = null
                        previewBitmap = null
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            ToolHint(
                if (maxEdgeOption == MAX_EDGE_ORIGINAL) {
                    "保持原始尺寸（超过 ${MAX_SAFE_PIXELS / 1_000_000}MP 的超大图会自动降采样以防内存溢出）"
                } else {
                    "长边缩到 $maxEdgeOption px，短边按比例（适合发朋友圈 / 传文档）"
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val uri = selectedUri ?: return@Button
                errorText = null
                working = true
                scope.launch {
                    // `try/finally` 而不是顺序写：`compressImage` 内部绝大多数步骤都自带
                    // `runCatching`，但仍有几处可能在极端情况下抛（例如给输出流预分配
                    // 缓冲区时的 OutOfMemoryError）。一旦抛出去而 `working` 没被复位，
                    // 「压缩中…」就会永久转圈、按钮永久禁用，只能杀掉页面重进。
                    // `finally` 保证这条路径一定复位。
                    val outcome = try {
                        withContext(Dispatchers.IO) {
                            compressImage(
                                context = context,
                                uri = uri,
                                maxEdge = maxEdgeOption,
                                quality = quality
                            )
                        }
                    } catch (t: Throwable) {
                        // ⚠️ `CancellationException` 必须原样抛回去，不能吞：
                        // 它是协程取消的控制流（用户离开页面 = `rememberCoroutineScope` 取消），
                        // 吞掉会让取消语义失效，还会被当成一次"压缩失败"弹提示。
                        if (t is CancellationException) throw t
                        // 其余连 Error（含 OOM）一起兜：本页宁可给一句"压缩失败"，
                        // 也不能让一次失败的压缩把整个应用带走。
                        CompressOutcome.Failure("压缩失败：${t.javaClass.simpleName}")
                    } finally {
                        working = false
                    }
                    when (outcome) {
                        is CompressOutcome.Success -> {
                            compressedBytes = outcome.bytes
                            resultWidth = outcome.width
                            resultHeight = outcome.height
                            savedOnce = false
                            // 预览用压缩后的字节解码，**不是**原图：用户要看到的就是
                            // 「压完之后长什么样」，用原图预览会让判断失真。
                            previewBitmap = withContext(Dispatchers.IO) { decodePreview(outcome.bytes) }
                        }

                        is CompressOutcome.Failure -> {
                            compressedBytes = null
                            previewBitmap = null
                            errorText = outcome.reason
                        }
                    }
                }
            },
            enabled = selectedUri != null && !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
                Text("压缩中…")
            } else {
                Text("压缩")
            }
        }

        errorText?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // ---- 结果 ----
        //
        // 用 `?.let` 而不是 `if (compressedBytes != null)`：后者不会对委托属性做智能转换，
        // 里面每处引用还得重新判空；`let` 的参数是一次取定的非空引用。
        compressedBytes?.let { bytes ->
            Spacer(Modifier.height(16.dp))
            ToolCard(title = "压缩结果") {
                previewBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "压缩后的预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(INLINE_PREVIEW_HEIGHT)
                            .clip(RoundedCornerShape(CARD_CORNER))
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Row {
                    StatColumn("原大小", sourceBytes?.let(::formatBytes) ?: "未知", Modifier.weight(1f))
                    StatColumn("压缩后", formatBytes(bytes.size.toLong()), Modifier.weight(1f))
                    StatColumn("压缩率", ratioText(sourceBytes, bytes.size), Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                ToolHint(
                    "输出尺寸 ${resultWidth}×${resultHeight} · 质量 $quality% · " +
                        "格式 JPEG（不支持透明通道）"
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                saveCompressedToGallery(context, bytes)
                            }
                            savedOnce = ok
                            onMessage(
                                if (ok) {
                                    "已保存到相册（Pictures/MoreCheck）"
                                } else {
                                    "保存失败，请重试"
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (savedOnce) "再次保存到相册" else "保存到相册")
                }
            }
        }
    }
}

/** 一个统计格：上方数值、下方标签。 */
@Composable
private fun StatColumn(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.strongTextColor
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/** 「最长边」档位的一个可选胶囊。 */
@Composable
private fun MaxEdgeOptionChip(
    label: String,
    value: Int,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    FilterChip(
        selected = selected == value,
        onClick = { onSelect(value) },
        label = { Text(label) }
    )
}

// ---- 压缩核心（全部在 IO 线程执行）-------------------------------------------

/** 压缩结果：成功带上字节与输出尺寸，失败带一句给用户看的原因。 */
private sealed interface CompressOutcome {
    data class Success(val bytes: ByteArray, val width: Int, val height: Int) : CompressOutcome
    data class Failure(val reason: String) : CompressOutcome
}

/** 源图的基本信息（选图后立刻读一次，供界面显示与结果对比）。 */
private data class SourceInfo(
    val displayName: String?,
    val sizeBytes: Long?,
    val width: Int,
    val height: Int
)

/**
 * 读源图的基本信息（文件名、字节数、像素尺寸），**必须在 IO 线程调用**。
 *
 * 文件名与大小走 `OpenableColumns` 查询；像素尺寸走同样的「只读文件头」两遍法第一遍
 * （`inJustDecodeBounds`），全程不解码像素。
 *
 * 返回 null 表示这张 Uri 已经失效（用户在系统相册里删了原图、或授权被回收）——
 * 界面据此显示"大小未知"，而不是崩掉或假装读到了 0。
 */
private fun readSourceInfo(context: Context, uri: Uri): SourceInfo? {
    var name: String? = null
    var size: Long? = null

    // `query` 可能抛（provider 崩溃 / 权限被撤），失败时继续往后读尺寸，
    // 不能因为"拿不到文件名"就让整个选图流程失败。
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                // SIZE 列在某些 provider 上返回 -1 或 null，那种情况下"大小未知"比
                // 显示「-1 B」诚实；用 0 当哨兵值会被误判成"这是一张空图"。
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    val raw = cursor.getLong(sizeIndex)
                    if (raw > 0) size = raw
                }
            }
        }
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val decoded = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        } ?: false
    }.getOrDefault(false)

    if (!decoded || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return SourceInfo(
        displayName = name,
        sizeBytes = size,
        width = bounds.outWidth,
        height = bounds.outHeight
    )
}

/**
 * 解码 → 采样 → 精确缩放 → 校正 EXIF 方向 → 压缩，**必须在 IO 线程调用**。
 *
 * @param maxEdge 目标最长边；[MAX_EDGE_ORIGINAL]（0）表示不按用户意愿缩放。
 */
private fun compressImage(
    context: Context,
    uri: Uri,
    maxEdge: Int,
    quality: Int
): CompressOutcome {
    // ---- 第 1 步：只读文件头拿原始尺寸（不分配像素内存）----
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val headOk = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        } ?: false
    }.getOrDefault(false)

    if (!headOk || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        // 最典型的成因：图片在系统相册里已被删除 / 云盘图尚未下载完成。
        return CompressOutcome.Failure("无法读取这张图片，可能已被删除或需要先下载到本地")
    }

    val srcWidth = bounds.outWidth
    val srcHeight = bounds.outHeight

    // 目标最长边：
    // - 用户指定了具体值 → 用它，但不超过原图（放大没有意义，只会更糊更大）；
    // - 「原图」→ 用原图最长边，同时受 MAX_SAFE_PIXELS 兜底（见类 KDoc）。
    val requestedEdge = when (maxEdge) {
        MAX_EDGE_ORIGINAL -> maxOf(srcWidth, srcHeight)
        else -> minOf(maxEdge, maxOf(srcWidth, srcHeight))
    }

    // ---- 第 2 步：根据「最长边目标」算采样率（2 的幂）----
    var sampleSize = sampleSizeFor(srcWidth, srcHeight, requestedEdge)

    // 像素总数兜底：「原图」档下即使最长边没变，超大图也要继续降采样。
    // 两条约束叠加：先解决超大像素（OOM 主因），再保证最长边不超过目标。
    while (pixelCountAfterSample(srcWidth, srcHeight, sampleSize) > MAX_SAFE_PIXELS &&
        sampleSize < MAX_SAMPLE_SIZE
    ) {
        sampleSize *= 2
    }

    // ---- 第 3 步：真正解码（带 OOM 重试）----
    //
    // 用 `var decoded: Bitmap?` + 循环，而不是「试一次、失败就返回」：
    // 内存不足只表现为返回 null 或抛 OutOfMemoryError（`runCatching` 连 Error 一起兜），
    // 把采样率再翻倍往往就能成功。最多 [OOM_RETRIES] 次，避免在一张坏图上无限重试。
    var decoded: Bitmap? = null
    var attempts = 0
    while (decoded == null && attempts <= OOM_RETRIES) {
        decoded = runCatching {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // RGB_565 能省一半内存，但会让照片出现明显色带；这里宁可多占一点，
                // 用默认的 ARGB_8888（JPEG 本身不存透明度，但解码中间态仍是 8888）。
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull()

        if (decoded == null) {
            attempts++
            if (sampleSize >= MAX_SAMPLE_SIZE) break
            sampleSize *= 2
        }
    }

    // 收成一个非空局部变量：后面的缩放/旋转会不断替换它，用 `Bitmap?` 的话
    // 每处调用都要判空；而 `decoded` 被上面的 lambda 捕获过，Kotlin 不会为它做智能转换，
    // 所以必须在收窄的这一刻用 `?: return` 明确取出非空引用。
    var bitmap: Bitmap = decoded ?: return CompressOutcome.Failure(
        "图片过大或已损坏，无法解码。可以先在系统相册里裁剪后再试"
    )

    // ---- 第 4 步：精确缩放到目标最长边 ----
    //
    // 采样率只能取 2 的幂，解码结果的边长会落在 [目标, 2×目标) 之间，
    // 因此还要一次精确缩放才能得到 1280 这种整齐的值。
    val currentMaxEdge = maxOf(bitmap.width, bitmap.height)
    if (currentMaxEdge > requestedEdge) {
        val scale = requestedEdge.toFloat() / currentMaxEdge
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }.getOrNull()
        if (scaled != null && scaled !== bitmap) {
            // 缩放成功后原图立刻回收：这一张往往是几十 MB，留着等 GC 太冒险。
            bitmap.recycle()
            bitmap = scaled
        }
    }

    // ---- 第 5 步：校正 EXIF 方向 ----
    //
    // `BitmapFactory` **不读** EXIF 的方向标签，所以竖拍的照片解码出来常常是横的。
    // 必须显式读一次（又一次开流，但只读头部、代价很小）。
    val rotation = runCatching { readExifRotation(context, uri) }.getOrDefault(0)
    if (rotation != 0) {
        val rotated = runCatching {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
        if (rotated != null) {
            bitmap.recycle()
            bitmap = rotated
        }
        // 旋转失败（例如 OOM）就接受"方向不对"这个降级：总比整张图压不出来好。
    }

    // ---- 第 6 步：压缩进 ByteArrayOutputStream ----
    //
    // 固定 JPEG：相册里的照片基本都是 JPEG，且 JPEG 的质量参数是用户理解的
    // 「压缩质量」。**不支持透明通道**——PNG 截图里的透明区域会变成黑色/白色背景，
    // 这一点写在结果卡片的说明里，不藏着。
    val output = ByteArrayOutputStream(BUFFER_INITIAL_BYTES)
    val ok = runCatching {
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    }.getOrDefault(false)

    val width = bitmap.width
    val height = bitmap.height
    // 压缩完立刻回收：后面不再需要这张位图（预览用压缩后的字节重新解码）。
    bitmap.recycle()

    if (!ok) {
        return CompressOutcome.Failure("压缩失败，请降低质量或换一张图片重试")
    }

    val bytes = output.toByteArray()
    if (bytes.isEmpty()) {
        return CompressOutcome.Failure("压缩结果为空，请重试")
    }
    return CompressOutcome.Success(bytes = bytes, width = width, height = height)
}

/**
 * 读 EXIF 方向并换算成需要顺时针旋转的角度；读不到时返回 0。
 *
 * 只处理「旋转」不处理「镜像」：镜像翻转在手机照片里几乎不出现
 * （前置摄像头自拍可能带，但那种情况系统相册本身也常显示为未翻转）。
 * 漏掉镜像的代价是"左右反了"，而误判镜像的代价是"明明正常却反了"——
 * 后者更糟，因此这里宁可保守。
 */
private fun readExifRotation(context: Context, uri: Uri): Int {
    val orientation = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: return 0

    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

/**
 * 用压缩后的字节解码一张**用于屏幕预览**的图（**必须在 IO 线程调用**）。
 *
 * 刻意做降采样（最长边 [PREVIEW_MAX_EDGE]）：预览槽位只有 200dp 高，
 * 按原尺寸解码一张 1280×960 的图会白白占用约 5MB，而每次重新压缩都会换一张、
 * 旧的那张要等 GC 才回收。降采样后预览图约 1MB 量级，反复压缩也不会堆积内存。
 *
 * **不主动 `recycle()` 旧预览位图**：它可能仍在当前帧的绘制队列里，
 * 显式回收会抛 `IllegalStateException: Can't call recycle on a bitmap being drawn`。
 * 控制住单张大小、交给 GC 是这里更安全的选择。
 */
private fun decodePreview(bytes: ByteArray): Bitmap? {
    // ⚠️ 这里写成**块体函数**而不是 `= runCatching { ... }.getOrNull()`：
    // 表达式体里如果要提前返回（尺寸读不出来时返回 null），`return` 只能带标签
    // （`return@runCatching`），而它返回的是 Unit，与 `Bitmap?` 对不上。
    // 块体 + 局部 runCatching 最直白。
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PREVIEW_MAX_EDGE)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }.getOrNull()
}

/**
 * 把压缩结果写进系统相册 `Pictures/MoreCheck`（**必须在 IO 线程调用**）。
 *
 * 与 `NotebookStore.saveImageToGallery` 同一套标准三步：`insert`（`IS_PENDING = 1`）
 * → 写字节 → `IS_PENDING = 0` 发布。**零权限**：minSdk 29 起写自己插入的
 * `MediaStore` 行不需要任何存储权限，因此本功能没有在 manifest 里加过任何东西。
 *
 * ⚠️ 任一步失败都必须 `delete` 掉已插入的行：否则相册里会永久残留一条
 * 打不开也删不掉的 0 字节记录，比"保存失败"更让用户困惑。
 *
 * 与记事本那版的唯一区别：那边从私有文件复制字节，这里直接把内存里的 `ByteArray` 写出。
 */
private fun saveCompressedToGallery(context: Context, bytes: ByteArray): Boolean {
    val resolver = context.contentResolver
    val displayName = "morecheck_compress_${System.currentTimeMillis()}.jpg"

    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    // `insert` 在 MediaStore 不可用 / 空间不足时抛异常，也会返回 null，
    // 两种失败都要挡（与 NotebookStore 同口径）。
    val uri: Uri = runCatching {
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull() ?: return false

    val written = runCatching {
        // `openOutputStream` 可能返回 null（这一行刚被其它进程删掉），不能直接 `!!`。
        val out = resolver.openOutputStream(uri) ?: return@runCatching false
        out.use { it.write(bytes) }
        true
    }.getOrDefault(false)

    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }

    val published = runCatching {
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null
        )
    }.isSuccess

    if (!published) {
        // 发布失败同样要清理：这一行仍对外不可见，留着只是一条永远挂着的 pending 记录。
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    return true
}

/**
 * 算 `inSampleSize`：把最长边降到 [maxPx] 附近，且只取 2 的幂。
 *
 * 取 2 的幂是平台要求（非 2 的幂会被下取整到最近的 2 的幂），而**往上取**
 * （宁可采样少一点、解码结果大一点）而不是往下取，是为了让第 4 步的精确缩放
 * 只做"缩小"不做"放大"——放大一张已被采样掉的图毫无意义。
 */
private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
    if (maxPx <= 0) return 1
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / (sample * 2) >= maxPx && sample < MAX_SAMPLE_SIZE) {
        sample *= 2
    }
    return sample
}

/** 按采样率折算后的像素总数（用于 [MAX_SAFE_PIXELS] 兜底判断）。 */
private fun pixelCountAfterSample(width: Int, height: Int, sample: Int): Long =
    (width / sample).toLong() * (height / sample).toLong()

/** 把字节数格式化成人看的字符串（B / KB / MB）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 压缩率文案：体积减少的百分比；原大小未知时如实说未知。 */
private fun ratioText(originalBytes: Long?, compressedBytes: Int): String {
    if (originalBytes == null || originalBytes <= 0L) return "未知"
    val reduced = (1.0 - compressedBytes.toDouble() / originalBytes) * 100.0
    // 压缩率可能为负（低质量小图重压后反而更大，例如已压过的小图用 100% 质量重编码）。
    // 负数不能显示成「-12% 压缩率」那样别扭，改成「增大 12%」直说。
    return if (reduced >= 0) {
        String.format(Locale.US, "↓ %.1f%%", reduced)
    } else {
        String.format(Locale.US, "↑ %.1f%%", -reduced)
    }
}

/** 「原图」档的哨兵值：0 表示不按用户意愿缩放（但仍受像素上限约束）。 */
private const val MAX_EDGE_ORIGINAL = 0

/** 默认质量：80% 是照片在体积与画质之间公认的平衡点。 */
private const val DEFAULT_QUALITY = 80

/** 质量下限：再低就会出现肉眼可见的方块噪点。 */
private const val MIN_QUALITY = 10

/** 质量上限。 */
private const val MAX_QUALITY = 100

/** 质量滑块的档距（每 5% 一档）。 */
private const val QUALITY_STEP = 5

/**
 * 解码后的像素总数上限。
 *
 * 12MP 覆盖了绝大多数手机主摄的单张尺寸（4000×3000 = 12.0MP），
 * 再往上就属于扫描件 / 全景图那类用户本来也少见的图。
 * 按 ARGB_8888 折算约 48MB，加上缩放/旋转的中间副本，峰值控制在百兆以内。
 */
private const val MAX_SAFE_PIXELS = 12_000_000L

/** `inSampleSize` 的上限：再大就基本没有可用的图像信息了。 */
private const val MAX_SAMPLE_SIZE = 16

/** 解码因内存不足失败时的重试次数（每次采样率翻倍）。 */
private const val OOM_RETRIES = 2

/** 相册落点：独立分组，不与相机照片混在 `Pictures` 根目录。 */
private const val GALLERY_RELATIVE_PATH = "Pictures/MoreCheck"

/**
 * 输出缓冲区的初始容量。
 *
 * 刻意**不**按上限（几 MB）预留：那会在每次压缩时都先分配一大块内存，
 * 而实际结果常常只有几百 KB。`ByteArrayOutputStream` 扩容是摊还 O(1) 的，
 * 从一个小的初始值长上去，总开销与"一次到位"同阶，但常态占用低得多。
 */
private const val BUFFER_INITIAL_BYTES = 64 * 1024

/** 结果卡片里预览图的高度：给个固定高度，避免高矮不一的图把卡片撑得忽大忽小。 */
private val INLINE_PREVIEW_HEIGHT = 200.dp

/**
 * 预览解码允许的最大边长（像素）。
 *
 * 预览槽位只有 [INLINE_PREVIEW_HEIGHT] 高、屏宽也就 400dp 左右，
 * 640px 在高分屏上已经足够清晰；再大只是白占内存。
 */
private const val PREVIEW_MAX_EDGE = 640

/** 预览图圆角：与项目 Card 的 medium 形状同量级。 */
private val CARD_CORNER = 8.dp
