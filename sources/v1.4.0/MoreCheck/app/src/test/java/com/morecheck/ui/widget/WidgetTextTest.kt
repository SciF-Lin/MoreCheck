package com.morecheck.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WidgetText.of] 的回归测试：进度 / 提示 / 按钮状态是纯分支逻辑，
 * 抽成无 Android 依赖的函数后可以直接在 JVM 上钉死边界。
 */
class WidgetTextTest {

    @Test
    fun `无任务时进度归零且提示还没有任务 按钮禁用`() {
        val state = WidgetText.of(done = 0, total = 0)
        assertEquals("0 / 0", state.progress)
        assertEquals(WidgetHint.NO_TASK, state.hint)
        assertEquals(WidgetButton.DISABLED, state.button)
        assertNull(state.disabledLabel)
    }

    @Test
    fun `部分完成时显示已完成数斜杠总数且按钮可打卡`() {
        val partial = WidgetText.of(done = 2, total = 3)
        assertEquals("2 / 3", partial.progress)
        assertEquals(WidgetHint.CHECK_IN_TIP, partial.hint)
        assertEquals(WidgetButton.CHECK_IN, partial.button)
        assertNull(partial.disabledLabel)

        val noneDone = WidgetText.of(done = 0, total = 1)
        assertEquals("0 / 1", noneDone.progress)
        assertEquals(WidgetButton.CHECK_IN, noneDone.button)
    }

    @Test
    fun `全部完成时进度等于总数且按钮显示已完成并禁用`() {
        val state = WidgetText.of(done = 3, total = 3)
        assertEquals("3 / 3", state.progress)
        assertEquals(WidgetHint.ALL_DONE, state.hint)
        assertEquals(WidgetButton.DISABLED, state.button)
        assertEquals("已完成", state.disabledLabel)
    }

    @Test
    fun `异常入参被夹紧 不显示负数且超额完成视为全部完成`() {
        val negative = WidgetText.of(done = -1, total = -5)
        assertEquals("0 / 0", negative.progress)
        assertEquals(WidgetHint.NO_TASK, negative.hint)

        val overflow = WidgetText.of(done = 9, total = 3)
        assertEquals("9 / 3", overflow.progress)
        assertEquals(WidgetHint.ALL_DONE, overflow.hint)
        assertEquals(WidgetButton.DISABLED, overflow.button)
    }
}
