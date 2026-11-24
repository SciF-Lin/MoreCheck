package com.morecheck.ui.lock

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 锁屏页顶部引导文案的映射（2026-10-30 需求 5）。
 *
 * 为什么单独测这句：指纹弹窗需要有可用指纹的设备才能弹出，模拟器上没有可录入的
 * 生物识别，这条路走不到；而"弹窗在前台时背景该写什么"是纯字符串判断，抽成
 * [lockHintText] 之后就能在这里钉死，避免以后改动锁屏布局时又被顺手改回去。
 */
class LockHintTextTest {

    @Test
    fun `指纹弹窗在前台时显示使用指纹解锁`() {
        assertEquals("使用指纹解锁", lockHintText(awaitingBiometric = true, patternHint = "绘制手势图案解锁"))
    }

    @Test
    fun `指纹弹窗不在前台时沿用原提示`() {
        assertEquals("绘制手势图案解锁", lockHintText(awaitingBiometric = false, patternHint = "绘制手势图案解锁"))
    }

    /** 画错后的重试提示同样不能被覆盖：弹窗关掉后要立刻回到"手势不正确，请重试"。 */
    @Test
    fun `重试提示在弹窗关闭后原样显示`() {
        assertEquals("手势不正确，请重试", lockHintText(awaitingBiometric = false, patternHint = "手势不正确，请重试"))
    }
}
