package com.morecheck.ui.lab

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 把「今日待办」灯箱里的图片存进系统相册（2026-10-30 需求 1）。
 *
 * ## 为什么必须由原生做
 *
 * 页面上一张备注图的 `src` 是云端签名 URL（`https://…`）。网页里长按图片，
 * WebView 默认**不会**弹系统菜单（页面把 contextmenu 关掉了，否则长按会误触
 * 文字选择），也没有 `download` 这条路能落进相册。所以只能由宿主下载后写
 * `MediaStore.Images`。
 *
 * ## 为什么用 MediaStore 而不是自己拼路径
 *
 * minSdk 29 起 scoped storage 全面生效：写 `Pictures/` **不需要**任何运行时
 * 权限，但必须走 `IS_PENDING` 两段式写入——否则相册可能在文件写完前就去扫
 * 这个条目，扫到半张图。
 *
 * 不解码重编码：直接搬字节流。原图可能是 PNG / WEBP，重编成 JPEG 既掉画质
 * 又可能把透明背景涂黑；顺带也省掉一次大图解码的 OOM 风险。
 */
internal object TodoImageSaver {

    private const val LOG_TAG = "TodoImageSaver"

    /** 相册里的子目录名（Pictures/MoreCheck/），方便用户按来源找图。 */
    private const val ALBUM = "MoreCheck"

    /** 分享用临时文件目录（cacheDir 之下，FileProvider 已映射 cacheDir）。 */
    private const val SHARE_DIR = "todo_share"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 保存一张图。
     *
     * @param src 图片地址：`data:image/...;base64,` 或 http/https URL。
     * @return null 表示成功；否则是给用户看的失败原因（一句话）。
     */
    suspend fun save(context: Context, src: String): String? = withContext(Dispatchers.IO) {
        try {
            val payload = if (src.startsWith("data:", ignoreCase = true)) {
                decodeDataUrl(src)
            } else {
                download(src)
            }
            writeToGallery(context, payload.first, payload.second)
            null
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "save failed", e)
            "保存失败：${e.message ?: "未知错误"}"
        }
    }

    /**
     * 分享一张图（本轮需求 4：长按图片可「保存」或「分享」）。
     *
     * 走系统分享面板（`ACTION_SEND` + `FileProvider`）：
     * - 先把图落到 `cacheDir/todo_share/`——`file_paths.xml` 只映射了 cacheDir，
     *   而且分享出去的临时文件本来就不该出现在用户相册里；
     * - `content://` URI 必须显式授 `FLAG_GRANT_READ_URI_PERMISSION`，
     *   否则接收方（微信 / QQ / 相册…）打开时拿不到读权限，表现为"分享出去是空图"。
     *
     * @return null 表示已拉起分享面板；否则是给用户看的失败原因。
     */
    suspend fun share(context: Context, src: String): String? = try {
        val payload = if (src.startsWith("data:", ignoreCase = true)) {
            withContext(Dispatchers.IO) { decodeDataUrl(src) }
        } else {
            withContext(Dispatchers.IO) { download(src) }
        }
        val file = withContext(Dispatchers.IO) { writeCacheFile(context, payload.first, payload.second) }
        withContext(Dispatchers.Main) { launchShareSheet(context, file, payload.second) }
        null
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "share failed", e)
        "分享失败：${e.message ?: "未知错误"}"
    }

    /** 写一份分享用的临时文件到 `cacheDir/todo_share/`（同名文件覆盖，免得堆垃圾）。 */
    private fun writeCacheFile(context: Context, bytes: ByteArray, mime: String): File {
        val dir = File(context.cacheDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建临时目录")
        val file = File(dir, "todo_${System.currentTimeMillis()}.${extOf(mime)}")
        file.writeBytes(bytes)
        // 顺手清掉上一次分享留下的文件（系统随时可回收 cache，这里只是别让它无限长）。
        dir.listFiles()?.filter { it.isFile && it.name != file.name && it.name.startsWith("todo_") }
            ?.forEach { runCatching { it.delete() } }
        return file
    }

    private fun launchShareSheet(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享图片").apply {
            // context 可能是 applicationContext（宿主桥传的就是它），必须带 NEW_TASK。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** mime → 扩展名。 */
    private fun extOf(mime: String): String = when {
        mime.contains("png", true) -> "png"
        mime.contains("webp", true) -> "webp"
        mime.contains("gif", true) -> "gif"
        else -> "jpg"
    }

    /** `data:image/jpeg;base64,…` → (bytes, mime)。 */
    private fun decodeDataUrl(src: String): Pair<ByteArray, String> {
        val comma = src.indexOf(',')
        require(comma > 0) { "图片数据不完整" }
        val mime = src.substringAfter("data:", "").substringBefore(';').ifBlank { "image/jpeg" }
        return Base64.decode(src.substring(comma + 1), Base64.DEFAULT) to mime
    }

    /** 下载 http/https 图片 → (bytes, mime)。 */
    private fun download(url: String): Pair<ByteArray, String> {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) error("下载图片失败（HTTP ${response.code}）")
            val body = response.body ?: error("图片内容为空")
            val mime = body.contentType()?.toString()?.substringBefore(';') ?: "image/jpeg"
            return body.bytes() to mime
        }
    }

    /** 把内存位图以 PNG 存进相册，成功返回 true；供 B 站扫码登录「保存二维码」复用。 */
    suspend fun saveBitmapToGallery(context: Context, bitmap: android.graphics.Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                writeToGallery(context, out.toByteArray(), "image/png")
            }.isSuccess
        }

    /** 两段式写入 MediaStore：先 IS_PENDING=1 占位，写完再置 0 让相册可见。 */
    private fun writeToGallery(context: Context, bytes: ByteArray, mime: String) {
        val resolver = context.contentResolver
        val ext = when {
            mime.contains("png", true) -> "png"
            mime.contains("webp", true) -> "webp"
            mime.contains("gif", true) -> "gif"
            else -> "jpg"
        }
        val name = "MoreCheck_${System.currentTimeMillis()}.$ext"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在相册里创建条目")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                ByteArrayInputStream(bytes).use { input -> input.copyTo(out) }
            } ?: error("无法写入相册")
        } catch (e: Throwable) {
            // 写失败要把占位条目删掉，否则相册里留一个永远 pending 的幽灵。
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
