package com.morecheck.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「官网」默认首页与入口地址归一化的纯 JVM 冒烟测试（2026-11-10）。
 *
 * 这些分支都是"用户不一定按格式输入"的边界：发现页入口传空串、
 * 检查更新传 https 全路径、老快捷方式可能带 http、自定义地址可能只写主机名。
 */
class OfficialSiteTest {

    @Test
    fun `空初始地址一律落到官网首页`() {
        assertEquals(OFFICIAL_SITE_URL, officialHomeUrl(null))
        assertEquals(OFFICIAL_SITE_URL, officialHomeUrl(""))
        assertEquals(OFFICIAL_SITE_URL, officialHomeUrl("   "))
    }

    @Test
    fun `官网首页是 scif top 且带 https`() {
        assertEquals("https://www.scif.top/", OFFICIAL_SITE_URL)
        assertEquals("官网", OFFICIAL_SITE_LABEL)
    }

    @Test
    fun `只写主机名时补 https`() {
        assertEquals("https://www.scif.top", officialHomeUrl("www.scif.top"))
        assertEquals("https://example.com/a", officialHomeUrl("example.com/a"))
        assertEquals("https://localhost:8080/a", officialHomeUrl("localhost:8080/a"))
    }

    @Test
    fun `明文 http 自动升级 保留路径与查询`() {
        assertEquals(
            "https://example.com/a?b=1",
            officialHomeUrl("http://example.com/a?b=1")
        )
    }

    @Test
    fun `https 地址原样 伪协议回落到官网`() {
        assertEquals("https://github.com/x/y", officialHomeUrl("https://github.com/x/y"))
        assertEquals(OFFICIAL_SITE_URL, officialHomeUrl("javascript:alert(1)"))
        assertEquals(OFFICIAL_SITE_URL, officialHomeUrl("file:///sdcard/a.apk"))
    }

    @Test
    fun `来回重定向不会在窗口内被反复升级`() {
        // 首次升级：lastUpgradeAt 初值 0，单调时钟已远大于窗口 → 允许。
        val first = 10_000L
        assertTrue(allowRedirectUpgrade(first, 0L))
        // 同一轮来回（300ms 后又降级回 http）→ 必须掐断，否则死循环。
        assertFalse(allowRedirectUpgrade(first + 300L, first))
        assertFalse(allowRedirectUpgrade(first + UPGRADE_LOOP_WINDOW_MS, first))
        // 过了窗口，用户后续正常访问仍可再升级一次。
        assertTrue(allowRedirectUpgrade(first + UPGRADE_LOOP_WINDOW_MS + 1L, first))
    }
}
