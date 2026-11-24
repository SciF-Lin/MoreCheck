package com.morecheck.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词 LRC 解析（2026-11-08，播放器页 C8）。
 *
 * 只测纯解析：网络链路（酷狗 krcs → lyrics 两步）在真机/模拟器上验证。
 * 歌词是**外部数据**，"格式怪"是常态，所以这里刻意覆盖几种畸形输入。
 */
class MusicLyricsTest {

    private fun times(raw: String): List<Long> =
        MusicLyrics.parseLrc(raw).map { it.timeMs }

    @Test
    fun 基本时间戳与文本对应() {
        val lines = MusicLyrics.parseLrc(
            """
            [ti:测试]
            [00:01.00]第一行
            [00:12.50]第二行
            """.trimIndent()
        )
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
        assertEquals(12_500L, lines[1].timeMs)
    }

    @Test
    fun 毫秒位数有一到三位() {
        // `.5` = 500ms，`.50` = 500ms，`.500` = 500ms —— 按位数补零，不是按字面数值。
        assertEquals(listOf(500L), times("[00:00.5]a"))
        assertEquals(listOf(500L), times("[00:00.50]a"))
        assertEquals(listOf(500L), times("[00:00.500]a"))
        assertEquals(listOf(120L), times("[00:00.12]a"))
    }

    @Test
    fun 一行多个时间戳各生成一行() {
        val lines = MusicLyrics.parseLrc("[00:10.00][01:20.00]副歌")
        assertEquals(2, lines.size)
        assertEquals(listOf(10_000L, 80_000L), lines.map { it.timeMs })
        assertTrue(lines.all { it.text == "副歌" })
    }

    @Test
    fun 元信息与空白行被丢弃() {
        val lines = MusicLyrics.parseLrc(
            """
            [ar:某歌手]
            [by:]
            [00:03.00]正文
            [00:04.00]
            """.trimIndent()
        )
        assertEquals(1, lines.size)
        assertEquals("正文", lines[0].text)
    }

    @Test
    fun 开头两行制作名单不会被当成歌词() {
        // 酷狗 LRC 常见开头：标题行 + 作词/作曲行，都带 [00:00.00] 时间戳。
        // 歌名下方的两行预览必须显示真正的歌词，否则像功能坏了。
        val lines = MusicLyrics.parseLrc(
            """
            [00:00.00]旺仔小乔 - 晴天 (Live)
            [00:00.00]作词：周杰伦
            [00:03.00]编曲：周杰伦
            [00:05.00]制作人：周杰伦
            [00:12.00]故事的小黄花
            [00:16.00]从出生那年就飘着
            """.trimIndent()
        )
        assertEquals(2, lines.size)
        assertEquals("故事的小黄花", lines[0].text)
        assertEquals("从出生那年就飘着", lines[1].text)
    }

    @Test
    fun 结果按时间升序() {
        val lines = MusicLyrics.parseLrc(
            """
            [00:20.00]后面的
            [00:05.00]前面的
            """.trimIndent()
        )
        assertEquals(listOf(5_000L, 20_000L), lines.map { it.timeMs })
    }

    @Test
    fun 畸形输入不崩溃() {
        assertEquals(0, MusicLyrics.parseLrc("").size)
        assertEquals(0, MusicLyrics.parseLrc("完全没有时间戳的一行").size)
        assertEquals(0, MusicLyrics.parseLrc("[aa:bb]不是数字").size)
    }
}
