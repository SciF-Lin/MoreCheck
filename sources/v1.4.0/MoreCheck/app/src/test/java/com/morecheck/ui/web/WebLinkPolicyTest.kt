package com.morecheck.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「访问网页时频繁弹出仅支持 https 网页」的回归测试。
 *
 * 缺陷根因：非 https 的子资源导航也会弹提示，而真实网页里 about:blank /
 * data: / javascript: 极常见。这里把裁决与节流两类行为钉成断言。
 */
class WebLinkPolicyTest {

    @Test
    fun 子资源https放行() {
        assertEquals(WebLinkDecision.ALLOW, WebLinkPolicy.decide("https", isMainFrame = false))
        assertEquals(WebLinkDecision.ALLOW, WebLinkPolicy.decide("HTTPS", isMainFrame = false))
    }

    @Test
    fun 子资源的伪协议与http一律静默拦截不弹提示() {
        listOf("about", "data", "javascript", "blob", "http", "intent", null).forEach { scheme ->
            assertEquals(
                "scheme=$scheme 应当静默拦截",
                WebLinkDecision.SILENT_BLOCK,
                WebLinkPolicy.decide(scheme, isMainFrame = false)
            )
        }
    }

    @Test
    fun 主框架http改为升级https而不是弹提示() {
        assertEquals(
            WebLinkDecision.UPGRADE_HTTPS,
            WebLinkPolicy.decide("http", isMainFrame = true)
        )
    }

    @Test
    fun 主框架无害伪协议静默外部协议才提示() {
        assertEquals(
            WebLinkDecision.SILENT_BLOCK,
            WebLinkPolicy.decide("about", isMainFrame = true)
        )
        assertEquals(
            WebLinkDecision.SILENT_BLOCK,
            WebLinkPolicy.decide("data", isMainFrame = true)
        )
        assertEquals(
            WebLinkDecision.BLOCK_WITH_NOTICE,
            WebLinkPolicy.decide("intent", isMainFrame = true)
        )
        assertEquals(
            WebLinkDecision.BLOCK_WITH_NOTICE,
            WebLinkPolicy.decide("market", isMainFrame = true)
        )
        assertEquals(
            WebLinkDecision.BLOCK_WITH_NOTICE,
            WebLinkPolicy.decide(null, isMainFrame = true)
        )
    }

    @Test
    fun 升级https保留路径与查询参数() {
        assertEquals(
            "https://example.com/a/b?x=1&y=2",
            WebLinkPolicy.toHttps("http://example.com/a/b?x=1&y=2")
        )
        assertEquals(
            "https://example.com/",
            WebLinkPolicy.toHttps("HTTP://example.com/")
        )
    }

    @Test
    fun 非http地址不能被误升级() {
        assertNull(WebLinkPolicy.toHttps("https://example.com/"))
        assertNull(WebLinkPolicy.toHttps("about:blank"))
        assertNull(WebLinkPolicy.toHttps("example.com/a"))
    }

    @Test
    fun 同一句提示在窗口内只出现一次() {
        val throttle = NoticeThrottle(windowMs = 1_000L)
        assertTrue(throttle.shouldShow("仅支持 https 网页", now = 10_000L))
        assertFalse(throttle.shouldShow("仅支持 https 网页", now = 10_500L))
        assertTrue(throttle.shouldShow("仅支持 https 网页", now = 11_001L))
    }

    @Test
    fun 不同文案不被节流吞掉() {
        val throttle = NoticeThrottle(windowMs = 1_000L)
        assertTrue(throttle.shouldShow("文案 A", now = 10_000L))
        assertTrue(throttle.shouldShow("文案 B", now = 10_001L))
    }
}
