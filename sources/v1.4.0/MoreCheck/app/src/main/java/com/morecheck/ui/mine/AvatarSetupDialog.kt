package com.morecheck.ui.mine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.data.SettingsRepository
import com.morecheck.di.appContainer
import com.morecheck.ui.common.pressScale
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「登录后头像引导」（2026-10-29 需求：
 * 「登录时，如果还是灰色头像，引导用户选择默认头像，或自行上传」）。
 *
 * ## 为什么挂在根骨架而不是登录页
 *
 * 判断条件是「已登录 + 头像还是空的 + 这次登录还没问过」，这三条都**与登录页无关**：
 * - 登录成功后登录页就退栈了，弹窗若挂在登录页会跟着一起消失；
 * - 登录入口不止一个（验证码登录 / 密码登录 / 未来可能加的第三方），
 *   挂在登录页意味着每个入口都要各弹一次、且容易漏。
 *
 * 这与本项目「首次登录合并策略弹窗挂在根骨架」的选择完全同构
 * （见 `MoreCheckApp` 里 `FirstLoginStrategyDialog` 那段注释），
 * 所以这里也交给根骨架托管，本文件只提供"弹什么、怎么存"。
 *
 * ## 两条路各走哪条代码
 *
 * 1. **选择默认头像**：解码内置图 → [AvatarStore.saveAvatar] → 写 `avatarPath` →
 *    [AvatarRevision.bump]。与「我的信息」页里选默认头像**同一条路径**（同一个文件、
 *    同一套落盘），因此之后全应用只有 `avatarPath` 一个事实来源。
 * 2. **自行上传**：系统照片选择器（PickVisualMedia，无需任何存储权限）→
 *    [AvatarCropScreen] 裁成正方形 → 同一条落盘路径。
 *    裁切页是整屏组件，这里用"覆盖在原界面上"的方式放它，而不是新增导航路由：
 *    它是引导流程的一个临时步骤，进路由表反而要处理"返回时回哪儿"。
 *
 * ## 只在"确实需要"时打扰
 *
 * - 已经有头像（`avatarPath` 非空）→ 不弹；
 * - 这次登录已经问过（`avatar_prompt_shown`，退出登录时清掉）→ 不弹；
 * - 用户点了「以后再说」→ 立刻记为已问过，不再重复打扰。
 */
@Composable
fun AvatarSetupHost(
    visible: Boolean,
    onFinish: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { context.appContainer.settingsRepository }

    // 用户从相册选中的图，等待裁切。非 null = 正在裁切（此时先不画引导弹窗，
    // 否则 Dialog（独立窗口）会盖在裁切页上面）。
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    // 正在落盘的内置头像（同一格显示转圈，挡连点）。
    var savingResId by remember { mutableStateOf<Int?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pendingCropUri = uri }

    /** 内置头像 → 落盘 → 收尾。与「我的信息」页同一条路径（见文件头注释）。 */
    fun applyDefault(resId: Int) {
        if (savingResId != null) return
        savingResId = resId
        scope.launch {
            val ok = applyDefaultAvatar(context, settings, resId)
            savingResId = null
            if (ok) {
                onMessage("头像已更新")
                onFinish()
            } else {
                onMessage("默认头像保存失败，请重试")
            }
        }
    }

    val cropUri = pendingCropUri
    if (visible && cropUri != null) {
        // 裁切页铺满**整个窗口**：这里用 `Dialog(usePlatformDefaultWidth = false)` 而不是
        // 一个铺满父容器的 Box —— 本组件挂在根骨架里（父容器是带内边距的内容层），
        // 直接铺 Box 会只铺到"内容区"，两侧/底部漏出底栏。Dialog 是独立窗口，
        // 天然盖住整屏，也不受父容器测量约束。
        //
        // `decorFitsSystemWindows = false`：裁切页自己处理状态栏内边距（它的 Scaffold
        // 里就是这么写的），戴上一层系统栏裁剪反而会多出一圈留白。
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { pendingCropUri = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(Modifier.fillMaxSize()) {
                AvatarCropScreen(
                    sourceUri = cropUri,
                    onCancel = { pendingCropUri = null },
                    onSaved = { path ->
                        pendingCropUri = null
                        scope.launch {
                            settings.setAvatarPath(path)
                            AvatarRevision.bump()
                            onMessage("头像已更新")
                            onFinish()
                        }
                    }
                )
            }
        }
        return
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = onFinish,
        title = { Text("设置一个头像吧") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左边就是"现在那个灰色头像"本身：用户一进来就知道说的是哪块。
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(9.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "当前还是默认的灰色头像，选一个默认头像或上传自己的照片，" +
                            "都能让你在「我的」页一眼认出自己。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "默认头像",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // 3 列网格（8 张 = 3 + 3 + 2）。与「我的信息」页的网格同一个规则：
                // 数量固定且很少，普通 Column + chunked 就够，不必上懒网格
                // （AlertDialog 的 text 槽位是无限高度约束，懒布局在里面会测量异常）。
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DefaultAvatarResIds.chunked(AvatarGridColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { resId ->
                                DefaultAvatarCell(
                                    resId = resId,
                                    saving = savingResId == resId,
                                    onClick = { applyDefault(resId) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(AvatarGridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                // 「自行上传」做成一行通栏可点（图标 + 两行文字），与「我的信息」页
                // 的「修改头像」来源弹窗同一形态，用户在两处看到的是同一种交互。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(onClick = {
                            pickImage.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        })
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("从相册上传", fontSize = 14.sp)
                        Text(
                            text = "选一张照片并裁成正方形",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 「以后再说」= 记为已问过（由宿主写入 `avatar_prompt_shown`），不再打扰。
            TextButton(onClick = onFinish) { Text("以后再说") }
        }
    )
}

/**
 * 网格里的一个默认头像单元格（与「我的信息」页的网格格子同一实现，2026-10-29 抽出来共用）。
 *
 * 缩略图在 IO 线程按 192px 采样解码：8 张全尺寸 JPEG 同步解码会卡主线程，
 * 而对话框里每格只有 ~72dp，读全尺寸没有意义。
 */
@Composable
internal fun DefaultAvatarCell(
    resId: Int,
    saving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(resId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(resId) {
        bitmap = withContext(Dispatchers.IO) { decodeDefaultAvatarBitmap(context, resId, 192) }
    }
    val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            // `pressScale` 在前、`clip` 在后：圆形边缘在缩放中间帧上才不会退化成多边形
            // （本项目实测踩过，见 `ui/common/PressScale.kt`）。
            .pressScale(onClick = onClick)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "默认头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxSize()
            ) {}
        }
        if (saving) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 内置默认头像的资源 id（v1.1.69 引入，2026-10-29 从 `PersonalInfoScreen` 移到本文件共用）。
 *
 * 素材来自用户提供的微信导出图，去重后按时间序取 8 张，统一命名
 * `ic_default_avatar_1..8`。**顺序即网格里的展示顺序**，新增头像往末尾追加
 * （不要插队——用户已经熟悉"第几个是哪个"）。
 */
internal val DefaultAvatarResIds: List<Int> = listOf(
    R.drawable.ic_default_avatar_1,
    R.drawable.ic_default_avatar_2,
    R.drawable.ic_default_avatar_3,
    R.drawable.ic_default_avatar_4,
    R.drawable.ic_default_avatar_5,
    R.drawable.ic_default_avatar_6,
    R.drawable.ic_default_avatar_7,
    R.drawable.ic_default_avatar_8
)

/** 默认头像网格的列数（两处网格共用，改一处即两处一致）。 */
internal const val AvatarGridColumns = 3

/**
 * 采样解码一个内置默认头像资源。
 *
 * 与 [AvatarStore.decodeStored] 同一套两遍法：先 `inJustDecodeBounds` 读尺寸，
 * 按 [targetSize] 算 `inSampleSize` 再真正解码——内置图最大只有 ~700px，
 * 网格缩略图只要 192px，全尺寸读进内存是无谓的浪费。
 *
 * @param targetSize 目标最长边（像素）。
 * @return 解码失败返回 null，调用方显示占位。
 *
 * ⚠️ **不能**写成 `runCatching { ... return@runCatching null }`：
 * `return@runCatching` 的标签类型与 `Result<T>` 的 lambda 返回类型对不上，会编译失败。
 */
internal fun decodeDefaultAvatarBitmap(context: Context, resId: Int, targetSize: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(context.resources, resId, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null

    var sample = 1
    while (
        options.outWidth / (sample * 2) >= targetSize &&
        options.outHeight / (sample * 2) >= targetSize
    ) {
        sample *= 2
    }
    return BitmapFactory.decodeResource(
        context.resources,
        resId,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}

/**
 * 把某个内置头像设成当前头像：解码 → 写文件 → 写路径 → 通知各页重新解码。
 *
 * 内部自带 `withContext(Dispatchers.IO)`（解码与 JPEG 编码都是阻塞操作），
 * 调用方直接在 `scope.launch { }` 里 await 结果即可。
 *
 * @return 是否成功；失败时路径不写、[AvatarRevision] 不 bump（界面保持原样）。
 */
internal suspend fun applyDefaultAvatar(
    context: Context,
    settings: SettingsRepository,
    resId: Int
): Boolean {
    val bitmap = withContext(Dispatchers.IO) {
        decodeDefaultAvatarBitmap(context, resId, AvatarStore.EXPORT_SIZE)
    } ?: return false
    val ok = withContext(Dispatchers.IO) { AvatarStore.saveAvatar(context, bitmap) }
    if (!ok) return false
    settings.setAvatarPath(AvatarStore.storedPath(context))
    // 头像文件是**固定路径**，内容换了但路径没变，必须用版本号通知各页重新解码
    // （原因见 [AvatarRevision] 的 KDoc）。
    AvatarRevision.bump()
    return true
}
