package com.morecheck.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.morecheck.data.net.MusicTrack

/** 毫秒 → `m:ss`。未知时长（0 或负数）显示 `--:--`。 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * 歌曲封面（列表行、迷你播放条、播放器页共用）。
 *
 * ## 为什么不用裸 `AsyncImage`
 *
 * 封面是**外部数据**：地址可能是空的、域名可能挂、图片可能被删。
 * 裸 `AsyncImage` 在这三种情况都会留一块空白，看起来像界面坏了。
 * 这里统一兜底成音符图标；加载失败也兜底（只判"地址为空"不够）。
 *
 * 地址的 http → https 已在数据层（`MusicRepository.forceHttps`）做掉，
 * 否则会被网络安全配置拦住，同样表现为"封面全是灰块"。
 */
@Composable
internal fun TrackArtwork(
    track: MusicTrack?,
    size: Dp,
    corner: Dp = 8.dp,
    modifier: Modifier = Modifier,
    /** true = 裁成正圆（黑胶唱片用），忽略 [corner]。 */
    circular: Boolean = false
) {
    val palette = rememberMusicPalette()
    val cover = track?.coverUrl
    var failed by remember(cover) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(if (circular) CircleShape else RoundedCornerShape(corner))
            .background(palette.track),
        contentAlignment = Alignment.Center
    ) {
        if (cover.isNullOrBlank() || failed) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(size * 0.42f),
                tint = palette.textMuted
            )
        } else {
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { state -> if (state is AsyncImagePainter.State.Error) failed = true }
            )
        }
    }
}
