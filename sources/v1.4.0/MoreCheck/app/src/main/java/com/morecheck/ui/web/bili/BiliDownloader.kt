package com.morecheck.ui.web.bili

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.morecheck.data.net.AppHttp
import com.morecheck.data.net.BilibiliRepository
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * B 站视频下载器（2026-11-03 原生改造；链路沿用 v1.2.5 的实现，按清晰度参数化）。
 *
 * ## 两条下载路径
 *
 * 1. **单文件（Progressive）**：`playurl?platform=html5` 给的 `durl[0]` 是服务端
 *    **已合成音轨**的 MP4，优先交给系统 [DownloadManager]——有系统通知、退出应用
 *    也能继续下，落盘到 `Movies/MoreCheck/`；
 * 2. **DASH 合流**：`dash.video` / `dash.audio` 是两条 fMP4 分片轨，先分别下到
 *    cacheDir，再用 [MediaMuxer] **裸重封装**成一个 MP4（零转码，几秒完成），
 *    最后通过 MediaStore 写进 `Movies/MoreCheck/`。
 *
 * ## ⚠️ CDN 的 UA 与接口层的 UA 是**两套**（2026-11-03 上机实测的 403 根因）
 *
 * 这两层不能共用同一个 UA：
 * - `api.bilibili.com`（接口层，见 [BilibiliRepository]）：要**移动版** UA；
 * - `*.bilivideo.com`（CDN 直链，本文件）：**拒绝移动版 UA**。实测同一份
 *   DASH m4s 直链，用移动版 UA 稳定 `403`，换成桌面 Chrome UA 就是 `206`。
 *
 * 二分结论（宿主机用真实直链 + `Range: bytes=0-1023` 复现）：
 * | 组合 | DASH m4s | 单文件 MP4 |
 * | --- | --- | --- |
 * | 移动 UA + Referer | 403 | 206 |
 * | 桌面 UA + Referer | 206 | 206 |
 * | 桌面 UA 无 Referer | 403 | 206 |
 * | 桌面 UA + Referer + Origin | 206 | 206 |
 *
 * 所以：**两种下载一律用 [CDN_UA]（桌面 Chrome）+ Referer（不带结尾斜杠）**；
 * `Origin` 与 `Cookie: buvid3` 实测**不是必需**，本文件不伪造 cookie。
 *
 * ## 系统下载器「排队不启动」时的应用内兜底
 *
 * 模拟器（partialConnectivity + 蜂窝）上实测：单文件入队后 `DownloadManager`
 * 一直停在 `STATUS_PENDING`（`_data` 为空、从未开始），用户看到的就是"点了没反应"。
 * 这是系统下载服务对网络状态的判断，**不一定是代码问题**，但体验不能赌它。
 * 因此单文件路径改为：入队 → 最多等 [SYSTEM_DOWNLOAD_START_TIMEOUT_MS] 观察状态
 * → 真正开始（RUNNING/成功）就交给系统；一直排队（PENDING/PAUSED）或已失败，
 * 就 `remove()` 掉系统任务，**改用应用内 OkHttp 下载 + MediaStore 落盘**
 * （复用 [downloadToFile] + [saveVideoToMovies]，单文件不需要合流）。
 *
 * ## 为什么 DASH 路径必须走 MediaStore 而不是直接 File 写公共目录
 *
 * 本应用 targetSdk 36（scoped storage），`Environment.getExternalStoragePublicDirectory`
 * 拿到的是普通路径，**应用无权直接写 `/sdcard/Movies`**（v1.2.5 的 DASH 兜底路径
 * 在真机上就是在这里失败的：合流前一切正常，最后一步 `mkdirs`/写文件抛 EACCES）。
 * API 29+ 的正确做法是经 `MediaStore.Video` 插入 + `IS_PENDING` 标记：
 * 写入期间对其他应用不可见，写完清除 pending 才出现在相册里。
 *
 * ## 为什么直链不复用
 *
 * `playurl` 返回的直链带 `deadline` 时效参数（约数小时失效）且与请求签名绑定，
 * 所以每次下载都**重新解析一次**（见 [BilibiliRepository.resolveStreamPlan]），
 * 拿到即用，不缓存。
 */
internal object BiliDownloader {

    /**
     * **CDN 专用** UA：桌面 Chrome。
     *
     * ⚠️ 不要改成移动版 UA，也不要与仓库层 [BilibiliRepository] 接口 UA 合并：
     * B 站 CDN 会把移动版 UA 拦成 403（详见类注释的二分表）。
     */
    private const val CDN_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 等系统下载器「真正开始」的上限。
     *
     * 取值理由：真机上正常 1~2 秒内就会进 RUNNING，6 秒足够覆盖弱网建连；
     * 再长会让用户对着"正在准备下载"干等，而模拟器那种永远 PENDING 的情况
     * 也不会因为多等而恢复。超时后直接走应用内兜底。
     */
    private const val SYSTEM_DOWNLOAD_START_TIMEOUT_MS = 6_000L

    /** 观察系统下载器状态的轮询间隔（不值得更密：状态查询是跨进程 ContentProvider）。 */
    private const val SYSTEM_DOWNLOAD_POLL_MS = 400L

    /** 下载结果：成功时 message 进 Snackbar；失败时 message 是可直接展示的原因。 */
    data class Result(val success: Boolean, val message: String)

    /** CDN 直链的请求头：桌面 UA + 不带结尾斜杠的 Referer（见类注释二分表）。 */
    private fun cdnHeaders(bvid: String): Map<String, String> = mapOf(
        "User-Agent" to CDN_UA,
        "Referer" to "https://www.bilibili.com/video/$bvid"
    )

    /**
     * 按用户选中的清晰度开始下载。
     *
     * @param onProgress 下载进度回调（Compose 侧据此画底部进度条）：
     *   `stage` 是当前阶段文案，`percent` 为 null 表示"进行中但总大小未知"。
     */
    suspend fun download(
        context: Context,
        plan: BilibiliRepository.BiliStreamPlan,
        onProgress: (stage: String, percent: Int?) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val baseName = BilibiliRepository.safeFileName(plan.title, plan.bvid)
        val qualityTag = plan.quality.label.replace(Regex("[\\s/]+"), "")
        val fileName = "$baseName-$qualityTag.mp4"

        try {
            if (plan.progressiveUrl != null) {
                downloadProgressive(context, plan, fileName, onProgress)
            } else {
                downloadDash(context, plan, fileName, onProgress)
                Result(true, "下载完成：$fileName（保存在相册「Movies/MoreCheck」）")
            }
        } catch (e: CancellationException) {
            // 用户离开播放页 → 协程取消。这里必须原样抛出：不能把"取消"报成"下载失败"。
            throw e
        } catch (e: Exception) {
            Result(false, "下载失败：${e.message ?: "网络错误"}")
        }
    }

    // ---------------------------------------------------------------------
    // 路径一：单文件 MP4 →（系统下载器，起不来则应用内兜底）
    // ---------------------------------------------------------------------

    /**
     * 单文件下载：先入队系统下载器并观察它是否真的开始，起不来就应用内下。
     *
     * 为什么要观察而不是"入队即报成功"：模拟器上实测系统下载器会永远 PENDING，
     * 用户看到"已加入下载"却永远没有文件，比直接报错更糟。
     */
    private suspend fun downloadProgressive(
        context: Context,
        plan: BilibiliRepository.BiliStreamPlan,
        fileName: String,
        onProgress: (stage: String, percent: Int?) -> Unit
    ): Result {
        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = enqueueSystemDownload(downloadManager, plan, fileName)

        onProgress("等待系统下载器启动", null)
        val status = waitForSystemDownloadStart(downloadManager, downloadId)
        when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> return Result(
                true,
                "下载完成：$fileName（保存在相册「Movies/MoreCheck」）"
            )

            DownloadManager.STATUS_RUNNING -> return Result(
                true,
                "已加入下载：$fileName（完成后在相册「Movies/MoreCheck」查看）"
            )

            // 查询失败（极少数 ROM）：不重复下载，按"已入队"告知，让用户去通知栏看。
            null -> return Result(
                true,
                "已加入下载：$fileName（系统下载器状态未知，请留意通知栏）"
            )

            // PENDING / PAUSED / FAILED：系统下载器没真正开始或已失败 → 应用内兜底。
            else -> {
                runCatching { downloadManager.remove(downloadId) }
                onProgress("系统下载器未启动，改用应用内下载", null)
                downloadProgressiveInApp(context, plan, fileName, onProgress)
                return Result(true, "下载完成：$fileName（保存在相册「Movies/MoreCheck」）")
            }
        }
    }

    /**
     * 入队系统 DownloadManager，返回任务 id。
     *
     * 为什么这条路径优先：系统下载器有通知栏进度、断网自动重试、应用退到后台
     * 也能继续下，体感远好于应用内自己下。前提是接口给的是单文件 MP4。
     */
    private fun enqueueSystemDownload(
        downloadManager: DownloadManager,
        plan: BilibiliRepository.BiliStreamPlan,
        fileName: String
    ): Long {
        val url = plan.progressiveUrl ?: throw IllegalStateException("下载地址为空")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("B站视频 · ${plan.quality.label}")
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "MoreCheck/$fileName"
            )
            // 允许计量网络 / 漫游：系统默认对二者保守，模拟器（蜂窝 + partialConnectivity）
            // 会因此一直 PENDING；放开后真机在流量/漫游下也能正常下载。
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            // 防盗链口径见类注释：桌面 UA + 不带结尾斜杠的 Referer。
            .addRequestHeader("User-Agent", CDN_UA)
            .addRequestHeader("Referer", "https://www.bilibili.com/video/${plan.bvid}")
        return downloadManager.enqueue(request)
    }

    /**
     * 观察系统下载器是否真正开始，最多 [SYSTEM_DOWNLOAD_START_TIMEOUT_MS]。
     *
     * 返回：`STATUS_RUNNING`/`SUCCESSFUL`/`FAILED` 终态；超时后返回最后一次查到的
     * 状态（通常是 `STATUS_PENDING`）；查询本身失败返回 null（调用方按"已入队"处理）。
     */
    private suspend fun waitForSystemDownloadStart(
        downloadManager: DownloadManager,
        downloadId: Long
    ): Int? {
        val deadline = System.currentTimeMillis() + SYSTEM_DOWNLOAD_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val status = querySystemDownloadStatus(downloadManager, downloadId) ?: return null
            if (status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_SUCCESSFUL ||
                status == DownloadManager.STATUS_FAILED
            ) {
                return status
            }
            // PENDING（等网络）/ PAUSED（条件不满足）继续观察，直到超时。
            delay(SYSTEM_DOWNLOAD_POLL_MS)
        }
        return querySystemDownloadStatus(downloadManager, downloadId)
    }

    /** 查一次系统下载任务状态；任务已不存在或查询异常返回 null。 */
    private fun querySystemDownloadStatus(
        downloadManager: DownloadManager,
        downloadId: Long
    ): Int? = runCatching {
        downloadManager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return@use null
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
    }.getOrNull()

    /**
     * 应用内兜底下载（单文件 MP4）：OkHttp 下到 cacheDir，再经 MediaStore 写相册。
     *
     * 单文件本身已带音轨，**不需要** [mergeDashToMp4]；落盘直接复用
     * [saveVideoToMovies]（与 DASH 合流后的写相册逻辑同一套）。
     */
    private fun downloadProgressiveInApp(
        context: Context,
        plan: BilibiliRepository.BiliStreamPlan,
        fileName: String,
        onProgress: (stage: String, percent: Int?) -> Unit
    ) {
        val url = plan.progressiveUrl ?: throw IllegalStateException("下载地址为空")
        val tmpDir = File(context.cacheDir, "bili_dl").apply { mkdirs() }
        val tempFile = File(tmpDir, "progressive_${System.currentTimeMillis()}.mp4")
        try {
            onProgress("下载视频", 0)
            downloadToFile(url, tempFile, cdnHeaders(plan.bvid)) { read, total ->
                onProgress("下载视频", percentOf(read, total))
            }
            onProgress("写入相册", null)
            saveVideoToMovies(context, tempFile, fileName)
            onProgress("完成", 100)
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    // ---------------------------------------------------------------------
    // 路径二：DASH 双轨 → 本地合流 → 媒体库
    // ---------------------------------------------------------------------

    private fun downloadDash(
        context: Context,
        plan: BilibiliRepository.BiliStreamPlan,
        fileName: String,
        onProgress: (stage: String, percent: Int?) -> Unit
    ) {
        val videoUrl = plan.dashVideoUrl ?: throw IllegalStateException("视频轨地址为空")
        val audioUrl = plan.dashAudioUrl ?: throw IllegalStateException("音频轨地址为空")

        val tmpDir = File(context.cacheDir, "bili_dl").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val videoFile = File(tmpDir, "video_$stamp.m4s")
        val audioFile = File(tmpDir, "audio_$stamp.m4s")
        val mergedFile = File(tmpDir, "merged_$stamp.mp4")
        // ⚠️ 这里**不用** plan.headers（那是接口层给的移动版 UA + 带斜杠 Referer）：
        // CDN 下载必须走 [cdnHeaders]，否则 DASH m4s 直接 403（见类注释二分表）。
        val headers = cdnHeaders(plan.bvid)
        try {
            onProgress("下载视频流", 0)
            downloadToFile(videoUrl, videoFile, headers) { read, total ->
                onProgress("下载视频流", percentOf(read, total))
            }
            onProgress("下载音频流", 0)
            downloadToFile(audioUrl, audioFile, headers) { read, total ->
                onProgress("下载音频流", percentOf(read, total))
            }
            onProgress("合成 MP4", null)
            mergeDashToMp4(videoFile, audioFile, mergedFile)
            onProgress("写入相册", null)
            saveVideoToMovies(context, mergedFile, fileName)
            onProgress("完成", 100)
        } finally {
            // 临时文件无论成败都清掉：一次合流可能几百 MB，留着会吃满 cacheDir。
            runCatching { videoFile.delete() }
            runCatching { audioFile.delete() }
            runCatching { mergedFile.delete() }
        }
    }

    private fun percentOf(read: Long, total: Long): Int? =
        if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else null

    /** OkHttp 流式下载（带 CDN 防盗链头），每 200ms 回调一次进度。 */
    private fun downloadToFile(
        url: String,
        target: File,
        headers: Map<String, String>,
        onProgress: (read: Long, total: Long) -> Unit
    ) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> builder.header(key, value) }
        AppHttp.client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: throw IllegalStateException("响应体为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    var lastReportAt = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportAt >= 200L) {
                            lastReportAt = now
                            onProgress(readTotal, total)
                        }
                    }
                    onProgress(readTotal, total)
                }
            }
        }
    }

    /**
     * DASH 双轨合流：fMP4 的 video/audio 直接重封装成一个 MP4，零转码。
     *
     * 与 v1.2.5 的实现逐行同口径（MediaExtractor + MediaMuxer）。约束：
     * 两路轨必须是同一封装容器（B 站 DASH 都是 fMP4，成立），否则 muxer 会抛。
     */
    private fun mergeDashToMp4(videoFile: File, audioFile: File, outFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        val muxer = MediaMuxer(
            outFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            var videoTrack = -1
            for (index in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = muxer.addTrack(format)
                    videoExtractor.selectTrack(index)
                    break
                }
            }
            audioExtractor.setDataSource(audioFile.absolutePath)
            var audioTrack = -1
            for (index in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = muxer.addTrack(format)
                    audioExtractor.selectTrack(index)
                    break
                }
            }
            check(videoTrack >= 0) { "视频轨缺失，无法合成" }
            check(audioTrack >= 0) { "音频轨缺失，无法合成" }

            muxer.start()
            val buffer = java.nio.ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            // 先写完视频轨再写音频轨即可：MP4 的轨间交错不影响播放（两路都是连续时间戳）。
            listOf(videoExtractor to videoTrack, audioExtractor to audioTrack).forEach {
                val (extractor, track) = it
                buffer.clear()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = extractor.sampleTime
                    // MediaExtractor 的 flag 与 MediaCodec.BufferInfo 的不是同一套常量：
                    // 直接赋值会把 SAMPLE_FLAG_PARTIAL_FRAME(4) 误当成 END_OF_STREAM(4)。
                    val sampleFlags = extractor.sampleFlags
                    var flags = 0
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                        flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }
                    info.flags = flags
                    muxer.writeSampleData(track, buffer, info)
                    extractor.advance()
                }
            }
            muxer.stop()
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer.release() }
        }
    }

    /**
     * 把下载好的 MP4（单文件或 DASH 合流产物）写进系统媒体库的 `Movies/MoreCheck/`。
     *
     * 为什么用 MediaStore：见类注释——scoped storage 下直接 File 写公共目录会 EACCES。
     * 写入期间置 `IS_PENDING=1`（相册不可见），成功后清 0；失败则删掉半成品条目，
     * 避免相册里留一个放不出来的坏文件。
     */
    private fun saveVideoToMovies(context: Context, source: File, displayName: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/MoreCheck"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入系统媒体库")
        try {
            val output = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法打开媒体库输出流")
            output.use { sink ->
                source.inputStream().use { input -> input.copyTo(sink, DEFAULT_BUFFER_SIZE * 16) }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }
}
