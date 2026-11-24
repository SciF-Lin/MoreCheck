package com.morecheck.ui.web.bili

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.morecheck.data.net.BiliMediaCache

/**
 * B 站播放页与短视频共用的 ExoPlayer 装配（2026-11-18 P0 改造新增）。
 *
 * 参数口径来自参考实现 Cheers 的 `buildExo`（静态反编译观察值）：
 * - **起播缓冲 500ms**（ExoPlayer 默认 2.5s，是「起播慢」的主因之一）；
 *   最小 18s / 最大 45s / 重缓冲 3s；
 * - **解码回退**：硬解失败自动试其它解码器（B 站 HEVC 档在部分机型硬解会挂）；
 * - **跨协议重定向**：B 站 CDN 会 http→https 跳；
 * - **Accept-Encoding: identity**：避免 gzip 干扰 Range 请求；
 * - 连接 10s / 读取 12s 超时；
 * - 媒体数据走 [BiliMediaCache]（200MB LRU、去 query 缓存键），
 *   seek 回看 / 重进视频 / 短视频翻页不再全程走 CDN。
 *
 * 请求头（UA / Referer / Cookie）由调用方从取流结果里原样传入。
 */
@UnstableApi
object BiliPlayerFactory {

    fun create(context: Context, headers: Map<String, String>): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0")
            .setDefaultRequestProperties(headers + ("Accept-Encoding" to "identity"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(12_000)
        val upstream = DefaultDataSource.Factory(context, http)
        val mediaSource = DefaultMediaSourceFactory(
            BiliMediaCache.dataSourceFactory(context, upstream)
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(18_000, 45_000, 500, 3_000)
            .build()
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSource)
            .setLoadControl(loadControl)
            .setRenderersFactory(renderers)
            .build()
    }
}