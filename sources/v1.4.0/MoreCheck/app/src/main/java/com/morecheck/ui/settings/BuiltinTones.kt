package com.morecheck.ui.settings

import android.content.Context
import java.io.File
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 应用**自带的提示音**（2026-10-22 需求 13：「声音设置里增加更多的系统内置声音」）。
 *
 * ## 为什么要自己生成
 *
 * 铃声列表原先只有 `RingtoneManager` 里那几条。实测不少机型（尤其是精简 ROM）只预置
 * 一两条闹钟 / 通知音，列表短到没法挑；再让用户去「从文件导入」又要求他先有音频文件。
 * 这里用**合成**的方式补一组：不占 `res/raw`（不增包体、不用给每种密度放资源），
 * 首次进入声音设置页时在 `filesDir/tones/` 下生成 5 个 16 位 PCM 的 WAV。
 *
 * ## 为什么是生成文件而不是实时合成播放
 *
 * 播放链路（[com.morecheck.ui.home.TimerRinger]）走的是 `RingtoneManager.getRingtone`，
 * 它吃的是 **URI**。把音调写成文件后，内置音与系统音、导入音在数据层完全同构：
 * 都是"一个 URI + 一个标题"，选中 / 持久化 / 试听 / 到点播放全部复用同一套代码，
 * 不需要为内置音单开一条合成播放器分支（那会变成两套播放实现，迟早不一致）。
 *
 * ## 幂等
 *
 * 文件名里带音色 id、内容只跟代码里的音调定义有关，因此已存在就跳过，
 * 不重复写盘（每次进页面都重写 5 个文件纯属浪费）。
 */
internal object BuiltinTones {

    /** 一个内置音：[uri] 是 `file://` 地址（持久化用），[title] 是界面标题。 */
    data class Tone(val uri: String, val title: String)

    /** 采样率。22050 对提示音足够，文件体积是 44100 的一半。 */
    private const val SAMPLE_RATE = 22_050

    /**
     * 音色定义：id（也是文件名）、标题、以及"怎么发这个音"。
     *
     * 标题都写成**听得出来的描述**（叮咚 / 双短音 / 上升三音……），
     * 而不是「提示音 1/2/3」——后者在列表里等于没有信息。
     */
    private val DEFINITIONS: List<Triple<String, String, () -> ShortArray>> = listOf(
        Triple("ding_dong", "叮咚（内置）") {
            concat(
                tone(1318.51, 0.22, decay = 0.55f),
                gap(0.04),
                tone(1046.50, 0.34, decay = 0.7f)
            )
        },
        Triple("double_beep", "双短音（内置）") {
            concat(
                tone(880.0, 0.11, decay = 0.15f),
                gap(0.07),
                tone(880.0, 0.11, decay = 0.15f)
            )
        },
        Triple("rising_three", "上升三音（内置）") {
            concat(
                tone(523.25, 0.15, decay = 0.2f),
                tone(659.25, 0.15, decay = 0.2f),
                tone(783.99, 0.26, decay = 0.35f)
            )
        },
        Triple("soft_low", "柔和低音（内置）") {
            // 慢起慢落：起音 8% 淡入、末尾按指数衰减，听感接近"嗡"而不是"叮"。
            tone(440.0, 0.65, attack = 0.08f, decay = 0.75f)
        },
        Triple("triple_tick", "三连轻响（内置）") {
            concat(
                tone(1046.5, 0.06, decay = 0.05f),
                gap(0.06),
                tone(1046.5, 0.06, decay = 0.05f),
                gap(0.06),
                tone(1046.5, 0.10, decay = 0.12f)
            )
        }
    )

    /**
     * 取全部内置音（必要时先生成文件）。
     *
     * **必须在 IO 线程调用**（会写文件）。任何一步失败都只是少一条，不抛——
     * 提示音缺失不该让声音设置页打不开。
     */
    fun ensure(context: Context): List<Tone> {
        val dir = File(context.filesDir, "tones")
        if (!dir.exists() && !dir.mkdirs()) return emptyList()
        return DEFINITIONS.mapNotNull { (id, title, build) ->
            val file = File(dir, "$id.wav")
            runCatching {
                if (!file.exists() || file.length() <= 44L) {
                    file.writeBytes(toWav(build()))
                }
                Tone(uri = "file://${file.absolutePath}", title = title)
            }.getOrNull()
        }
    }

    // ---- 合成与 WAV 封装 ---------------------------------------------------

    /**
     * 单个音：正弦波 + 起音淡入 + 指数衰减。
     *
     * @param hz 频率（Hz）。
     * @param seconds 时长（秒）。
     * @param attack 起音淡入占时长的比例（0.02 = 20ms 淡入，避免"啪"的爆音）。
     * @param decay 衰减强度：越大收得越快（0 = 不衰减，会听到尾巴被硬切）。
     */
    private fun tone(
        hz: Double,
        seconds: Double,
        attack: Float = 0.02f,
        decay: Float = 0.3f
    ): ShortArray {
        val count = (seconds * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val out = ShortArray(count)
        val attackSamples = (count * attack).toInt().coerceAtLeast(1)
        for (i in 0 until count) {
            val phase = 2.0 * PI * hz * i / SAMPLE_RATE
            val fadeIn = min(1f, i.toFloat() / attackSamples)
            // 指数衰减到 0.02（不是 0，避免最后一个样本突然跳到 0 产生咔哒声）。
            val progress = i.toFloat() / count
            val envelope = Math.pow(0.02, (progress * decay * 3f).toDouble()).toFloat()
            val value = sin(phase) * fadeIn * envelope
            out[i] = (value * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        return out
    }

    /** 静音段（用于拉开两个音的间隔）。 */
    private fun gap(seconds: Double): ShortArray =
        ShortArray((seconds * SAMPLE_RATE).toInt().coerceAtLeast(1))

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    /**
     * 16 位单声道 PCM 的 WAV 封装（44 字节标准头 + 数据）。
     *
     * 手写而不是找库：只需要这一个格式，`android.media` 里的编码器要用 `MediaCodec` +
     * `MediaMuxer` 那一整套，为 5 个提示音引入那条链路不值得。
     */
    private fun toWav(samples: ShortArray): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArray(44 + dataSize)
        fun putAscii(offset: Int, text: String) {
            text.forEachIndexed { i, c -> out[offset + i] = c.code.toByte() }
        }

        fun putIntLe(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
            out[offset + 2] = ((value shr 16) and 0xFF).toByte()
            out[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        fun putShortLe(offset: Int, value: Int) {
            out[offset] = (value and 0xFF).toByte()
            out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putIntLe(4, 36 + dataSize)          // RIFF chunk size
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putIntLe(16, 16)                    // PCM fmt chunk size
        putShortLe(20, 1)                   // audio format = PCM
        putShortLe(22, 1)                   // mono
        putIntLe(24, SAMPLE_RATE)
        putIntLe(28, SAMPLE_RATE * 2)       // byte rate = rate * channels * bytes/sample
        putShortLe(32, 2)                   // block align
        putShortLe(34, 16)                  // bits per sample
        putAscii(36, "data")
        putIntLe(40, dataSize)

        var offset = 44
        for (sample in samples) {
            out[offset] = (sample.toInt() and 0xFF).toByte()
            out[offset + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            offset += 2
        }
        return out
    }
}
