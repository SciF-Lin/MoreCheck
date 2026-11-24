package com.morecheck.ui.web.bili

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.morecheck.data.net.AppHttp
import com.morecheck.ui.theme.Radius
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 「B站视频」封面图加载器（2026-11-03 原生改造新增）。
 *
 * ## 为什么不引图片库
 *
 * 项目虽然带了 Coil，但本轮任务约束明确要求**不新增依赖、用 OkHttp + BitmapFactory
 * + LruCache 自写**：封面列表是最典型的"小图 + 高复用"场景，自写 40 行足够，
 * 也避免给一个二级页面绑定额外图片库语义（缓存策略、单例 ImageLoader 等）。
 *
 * ## 为什么复用 [AppHttp.client]
 *
 * 全应用只有一个 OkHttpClient（连接池/线程池共享）。这里直接拿它的
 * `newCall(...).execute()`，不另起 client，也不新增线程池——列表滚动时
 * 同时最多只有屏幕内几张图在解码，这个量级完全不需要额外的加载调度器。
 *
 * ## 为什么不做磁盘缓存
 *
 * B 站图片 URL 带 40 位十六进制哈希，内容固定不变；但列表每次都是新请求、
 * 用户一页看完就走。进程内 LruCache（约 12MB，覆盖两三屏封面）已经足够，
 * 加磁盘缓存反而要处理目录清理与隐私问题（浏览记录留存），得不偿失。
 *
 * ## 为什么解码要降采样
 *
 * 封面原图是 480×270 起步、个别横图更大，而卡片展示宽度约 160dp。
 * 直接全尺寸解码在长列表滚动时会明显掉帧，所以按 maxWidth=480 做
 * `inSampleSize` 粗略降采样（不需要 ImageDecoder/精确缩放，够用且稳）。
 */
internal object BiliCoverCache {

    /** 约 12MB：按单张 480×270 ARGB_8888（约 0.5MB）算，能放 20 张左右。 */
    private const val MAX_CACHE_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 同步取（首帧先吃缓存，避免"先灰一下再出图"的闪动）。 */
    fun peek(url: String): Bitmap? = cache.get(url)

    /**
     * 网络加载 + 解码；失败返回 null（卡片会显示占位图标，不弹错误）。
     *
     * 图片失败不值得打断浏览：B 站图床偶发 403/超时，缺一张封面不影响用。
     */
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        cache.get(url)?.let { return@withContext it }
        runCatching {
            val request = Request.Builder()
                .url(url)
                .get()
                // B 站图床对大流量来源会校验 Referer，带上与接口一致的来源最稳。
                .header("Referer", "https://www.bilibili.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0 Mobile Safari/537.36"
                )
                .build()
            AppHttp.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, 480)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.RGB_565
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull()?.also { bitmap -> cache.put(url, bitmap) }
    }

    /** 找到能让宽高不超过目标的 2 的幂次降采样倍数。 */
    private fun calculateInSampleSize(width: Int, height: Int, maxWidth: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (width / sampleSize > maxWidth) {
            sampleSize *= 2
        }
        return sampleSize
    }
}

/**
 * 视频封面：圆角裁切 + 灰底占位 + 加载完成直接显示（无淡入动画，避免滚动时闪烁）。
 *
 * @param url 已归一成 https 的封面地址（[com.morecheck.data.net.BilibiliRepository.normalizeImageUrl]）。
 */
@Composable
internal fun BiliCover(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = Radius.control
) {
    // remember(url)：同一个卡片项换图（例如搜索结果刷新）时要重新加载。
    var bitmap by remember(url) { mutableStateOf(BiliCoverCache.peek(url)) }

    LaunchedEffect(url) {
        if (bitmap == null && url.isNotBlank()) {
            bitmap = BiliCoverCache.load(url)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // 占位：视频图标而不是纯灰块，一眼能看出"这里将来是封面"。
            Icon(
                imageVector = Icons.Filled.PlayCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
