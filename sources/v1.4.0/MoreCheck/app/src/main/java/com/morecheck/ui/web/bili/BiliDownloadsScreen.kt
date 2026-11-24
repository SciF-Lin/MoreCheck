package com.morecheck.ui.web.bili

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「我的下载」页（2026-11-03 B站增强新增）。
 *
 * ## 数据从哪来
 *
 * 不额外维护数据库：B 站视频统一由 [BiliDownloader] 落到
 * `Movies/MoreCheck/`（单文件走系统 DownloadManager、DASH 走 MediaStore），
 * 所以这里直接查 MediaStore 的 `RELATIVE_PATH`，下载完立刻可见，卸载/清数据也不会
 * 留下"孤儿记录"。
 *
 * ## 三个动作
 * 1. **播放**：用 `ACTION_VIEW` 交给系统播放器（项目内没有自研播放器；我们的播放页
 *    是 WebView，只播在线 B 站页面，不适合本地文件）；
 * 2. **分享**：必须走 FileProvider（targetSdk 36 禁止 file:// 外传），authority 是
 *    `${applicationId}.bili.fileprovider`，对应 `res/xml/bili_file_paths.xml`
 *    只映射 `Movies/MoreCheck/`；取不到真实路径时退回 MediaStore 的 content:// URI；
 * 3. **删除**：二次确认后 `ContentResolver.delete`（MediaStore 会连同文件一起删）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliDownloadsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messageSink = rememberUpdatedState(onMessage)

    var items by remember { mutableStateOf<List<DownloadedVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<DownloadedVideo?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(reloadTick) {
        loading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) { runCatching { queryDownloadedVideos(context) } }
        result.onSuccess { items = it }
            .onFailure {
                errorMessage = it.message ?: "读取下载列表失败"
                items = emptyList()
            }
        loading = false
    }

    fun play(item: DownloadedVideo) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.contentUri, item.mimeType.ifBlank { "video/mp4" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { messageSink.value("没有可用的视频播放器") }
    }

    fun share(item: DownloadedVideo) {
        val file = item.filePath?.let { File(it) }
        val shareUri: Uri? = if (file != null && file.exists()) {
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.bili.fileprovider",
                    file
                )
            }.getOrNull()
        } else {
            // MediaStore 的 content:// URI 也能直接分享（系统会授予临时读取权限）。
            item.contentUri
        }
        if (shareUri == null) {
            messageSink.value("分享失败：找不到文件")
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.mimeType.ifBlank { "video/mp4" })
            .putExtra(Intent.EXTRA_STREAM, shareUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(
                Intent.createChooser(send, "分享视频").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { messageSink.value("分享失败：没有可用的应用") }
    }

    fun delete(item: DownloadedVideo) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.delete(item.contentUri, null, null) > 0 }
                    .getOrElse { false }
            }
            if (ok) {
                messageSink.value("已删除：${item.displayName}")
                reloadTick++
            } else {
                messageSink.value("删除失败，可能文件已被其它应用移动")
                reloadTick++
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("我的下载", style = AppType.heading) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadTick++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(Space.md))
                    Text(
                        text = "正在扫描已下载的视频…",
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                errorMessage != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = Space.xl)
                    )
                    Spacer(Modifier.height(Space.lg))
                    TextButton(onClick = { reloadTick++ }) { Text("重试") }
                }

                items.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(Space.md))
                    Text(
                        text = "还没有下载的视频",
                        style = AppType.body,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = "在 B 站视频的播放页点右上角下载图标，选清晰度后下载；" +
                            "文件会出现在「Movies/MoreCheck」",
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.xxl)
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Space.lg,
                        end = Space.lg,
                        top = Space.sm,
                        bottom = Space.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.md)
                ) {
                    items(items, key = { it.id }) { item ->
                        DownloadedVideoCard(
                            item = item,
                            onPlay = { play(item) },
                            onShare = { share(item) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除已下载视频？", style = AppType.heading) },
            text = {
                Text(
                    text = "将从手机相册/媒体库中永久删除：\n${target.displayName}\n\n" +
                        "此操作不可恢复。",
                    style = AppType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(target)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 一条已下载视频（MediaStore 投影结果）。 */
internal data class DownloadedVideo(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val mimeType: String,
    val filePath: String?,
    val contentUri: Uri
)

/** 查询 `Movies/MoreCheck/` 下的视频（按加入时间倒序）。 */
private fun queryDownloadedVideos(context: android.content.Context): List<DownloadedVideo> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.DATA
    )
    val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf("${Environment.DIRECTORY_MOVIES}/MoreCheck%")
    val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
    val result = mutableListOf<DownloadedVideo>()
    context.contentResolver.query(
        collection,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
        val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
        val dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIndex)
            result += DownloadedVideo(
                id = id,
                displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: "未命名视频",
                sizeBytes = cursor.getLong(sizeIndex),
                durationMs = cursor.getLong(durationIndex),
                dateAddedSeconds = cursor.getLong(dateIndex),
                mimeType = cursor.getString(mimeIndex).orEmpty(),
                filePath = if (dataIndex >= 0) cursor.getString(dataIndex) else null,
                contentUri = ContentUris.withAppendedId(collection, id)
            )
        }
    }
    return result
}

@Composable
private fun DownloadedVideoCard(
    item: DownloadedVideo,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(Radius.card)
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(Radius.card)
            )
            .padding(Space.md)
    ) {
        Text(
            text = item.displayName,
            style = AppType.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = buildString {
                append(formatFileSize(item.sizeBytes))
                if (item.durationMs > 0) append(" · ").append(formatDurationMs(item.durationMs))
                append(" · ").append(formatDate(item.dateAddedSeconds))
            },
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            DownloadAction("播放", Icons.Filled.PlayArrow, onPlay)
            DownloadAction("分享", Icons.Filled.Share, onShare)
            DownloadAction("删除", Icons.Filled.Delete, onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DownloadAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(Radius.pill)
            )
            .clickableNoIndication(onClick)
            .padding(horizontal = Space.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(text = label, style = AppType.caption, color = tint)
    }
}

/** 字节数 → 人类可读大小。 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.ROOT, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
        else -> String.format(Locale.ROOT, "%.0f KB", kb)
    }
}

/** 毫秒 → `mm:ss` / `h:mm:ss`。 */
private fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

/** 秒级时间戳 → `yyyy-MM-dd`。 */
private fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "未知时间"
    return runCatching {
        // 纯数字格式固定 Locale.ROOT：默认 Locale 在佛历/和历设备上会把年份显示成
        // 2569 / 令和 8；ROOT 在常规中/英文设备上与原来输出完全一致。
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(epochSeconds * 1000))
    }.getOrDefault("未知时间")
}
