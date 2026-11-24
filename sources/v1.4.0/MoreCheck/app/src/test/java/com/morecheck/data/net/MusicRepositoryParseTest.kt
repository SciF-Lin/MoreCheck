package com.morecheck.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索结果的解析细节（2026-11-08 修复"列表封面不显示"时补的回归）。
 *
 * 根因不是 Coil：酷狗返回的封面与音频直链都是 `http://`，被应用的明文流量策略拦住，
 * 图片加载失败后只剩灰块。修法是在解析阶段统一升级到 https，
 * 所以这里锁住"解析出来的地址一定是 https"。
 */
class MusicRepositoryParseTest {

    @Test
    fun 酷狗封面与直链升到https() {
        val json = """
            {"data":{"info":[{
              "hash":"ABC123",
              "songname":"晴天",
              "singername":"周杰伦",
              "album_name":"叶惠美",
              "duration":269,
              "imgurl":"http://imge.kugou.com/stdmusic/{size}/cover.jpg",
              "album_audio_id":123456
            }]}}
        """.trimIndent()
        val tracks = MusicRepository.parseKugouSearch(json)
        assertEquals(1, tracks.size)
        val cover = tracks[0].coverUrl
        assertTrue("封面应为 https，实际 $cover", cover != null && cover.startsWith("https://"))
        // 尺寸占位符也要替换掉，否则服务端返回 404。
        assertTrue(cover!!.contains("/480/"))
    }

    @Test
    fun 网易封面升到https() {
        val json = """
            {"result":{"songs":[{
              "id":42,
              "name":"Shape of You",
              "duration":233000,
              "artists":[{"name":"Ed Sheeran"}],
              "album":{"name":"÷","picUrl":"http://p1.music.126.net/a.jpg"}
            }]}}
        """.trimIndent()
        val tracks = MusicRepository.parseNeteaseSearch(json)
        assertEquals(1, tracks.size)
        assertTrue(tracks[0].coverUrl!!.startsWith("https://"))
    }

    @Test
    fun https地址保持原样() {
        assertEquals("https://a/b.mp3", MusicRepository.forceHttps("https://a/b.mp3"))
        // http 只换协议头，不动主机与路径（主机可能带端口）。
        assertEquals("https://fsandroid.kugou.com:8080/x", "https://fsandroid.kugou.com:8080/x")
        assertEquals(
            "https://fsandroid.kugou.com:8080/x",
            MusicRepository.forceHttps("http://fsandroid.kugou.com:8080/x")
        )
        assertNull(MusicRepository.forceHttps(null))
        assertNull(MusicRepository.forceHttps("   "))
    }

    @Test
    fun 缺字段的条目被丢弃而不是崩溃() {
        val json = """{"data":{"info":[{"songname":"没有 hash"},{"hash":"OK","songname":"有"}]}}"""
        val tracks = MusicRepository.parseKugouSearch(json)
        assertEquals(1, tracks.size)
        assertEquals("有", tracks[0].name)
    }
}
