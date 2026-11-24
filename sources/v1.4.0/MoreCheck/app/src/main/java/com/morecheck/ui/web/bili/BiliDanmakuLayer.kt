@file:OptIn(ExperimentalTextApi::class)

package com.morecheck.ui.web.bili

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.net.BilibiliRepository.BiliDanmaku

/**
 * B 站弹幕的自绘覆盖层（2026-11-18 重做新增）。
 *
 * ## 为什么不用 WebView 的弹幕
 *
 * 播放换成原生直链后，H5 播放器自带的弹幕也随之消失。逆向的「哔哩哔哩 Lite」
 * 用的是自绘 `DanmakuView`——我们照这个思路做：拿到 XML 弹幕后按时间轴自己在
 * Canvas 上画，不需要额外依赖，深浅主题也不影响（弹幕颜色来自 B 站原始 RGB）。
 *
 * ## 滚动模型（刻意做简化）
 *
 * 每条滚动弹幕活 [DANMAKU_LIFE_MS]，横向从右边跑到左边完全出屏；
 * 轨道按 `index % TRACK_COUNT` 分配，同一时刻最多画 [MAX_VISIBLE] 条，
 * 避免长视频上千条弹幕每帧全部遍历导致掉帧。
 * 这不是精确复刻 B 站的弹道调度，但观感接近、成本可控——本模块的目标是
 * "能看弹幕"，不是做一个播放器内核。
 *
 * [currentMs] 由播放器每 ~100ms 推一次；用状态而不是读 `player.currentPosition`
 * 是为了让 Canvas 只在时间变化时重绘，不跟播放器内部帧率绑定。
 */
@Composable
fun BiliDanmakuLayer(
    danmakus: List<BiliDanmaku>,
    currentMs: Long,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** 弹幕整体不透明度：0.35~1，播放器的设置面板可调。 */
    opacity: Float = 1f,
    /** 描边开关：白底视频上深色描边更清楚。 */
    outline: Boolean = true
) {
    if (!enabled || danmakus.isEmpty()) return
    val measurer = rememberTextMeasurer()
    // 按时间建索引，避免每帧扫全表；列表本身来自 Repository，已按时间排好。
    val indexed = remember(danmakus) { danmakus.mapIndexed { index, d -> index to d } }

    Canvas(modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val trackHeight = 34.dp.toPx()
        val trackCount = (height / trackHeight).toInt().coerceIn(2, 8)
        val fontSize = 17.sp.toPx()

        var drawn = 0
        for ((index, danmaku) in indexed) {
            if (drawn >= MAX_VISIBLE) break
            val startMs = (danmaku.timeSec * 1000f).toLong()
            val age = currentMs - startMs
            // 只画"刚开始 8 秒内"的弹幕：太早的还没进场，太晚的已经出屏。
            if (age < 0L || age > DANMAKU_LIFE_MS) continue
            // 顶部 / 底部弹幕静止显示 4 秒，滚动弹幕按生命周期横移。
            val progress = age.toFloat() / DANMAKU_LIFE_MS
            if (progress > 1f) continue

            val style = TextStyle(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                // B 站给的是 RGB（如 0xFFFFFF），Compose 的 Color(Int) 按 ARGB 解释，
                // 直接传会得到 alpha=0 的全透明字——必须补上不透明的 alpha 通道。
                color = Color((0xFF000000L or (danmaku.color.toLong() and 0xFFFFFFL)).toInt())
                    .copy(alpha = opacity)
            )
            val measured = measurer.measure(danmaku.text, style)
            val textWidth = measured.size.width.toFloat()
            val track = index % trackCount
            val y = track * trackHeight + fontSize * 0.2f

            when (danmaku.mode) {
                5 -> drawCentered(measurer, danmaku.text, style, y, width, outline)
                4 -> drawCentered(
                    measurer, danmaku.text, style,
                    height - trackHeight * 1.2f, width, outline
                )

                else -> {
                    val x = width - progress * (width + textWidth)
                    drawDanmakuText(measurer, danmaku.text, style, x, y, outline)
                }
            }
            drawn++
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCentered(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    y: Float,
    width: Float,
    outline: Boolean
) {
    val measured = measurer.measure(text, style)
    val x = (width - measured.size.width) / 2f
    drawDanmakuText(measurer, text, style, x, y, outline)
}

/** 先用半透明黑描边、再画正文——白字落在浅色画面上也读得清。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDanmakuText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    outline: Boolean
) {
    if (outline) {
        drawText(
            measurer.measure(text, style.copy(color = Color.Black.copy(alpha = 0.65f))),
            topLeft = Offset(x + 1f, y + 1f)
        )
    }
    drawText(measurer.measure(text, style), topLeft = Offset(x, y))
}

/** 一条弹幕在屏幕上停留的时长。比 B 站默认的 8~12 秒短，移动端小屏读起来更利索。 */
private const val DANMAKU_LIFE_MS = 8_000L

/** 单帧最多绘制条数；超出部分丢弃，优先画前面的（按时间顺序，早的更相关）。 */
private const val MAX_VISIBLE = 48
