package com.morecheck.ui.widget

/**
 * 小组件文案状态计算（纯 JVM，无 Android 依赖）。
 *
 * 为什么把资源映射留到 Android 侧：这里若直接引用 R.string 或 Context，
 * 单元测试就得挂 Robolectric；而文案决策本身只是「done 与 total」的分支，
 * 用枚举把「该显示哪一类提示」表达出来即可，字符串资源由 WidgetRefresh 负责取。
 */
enum class WidgetHint {
    /** 还有任务没打卡：用默认引导语。 */
    CHECK_IN_TIP,
    NO_TASK,
    ALL_DONE
}

enum class WidgetButton {
    /** 可打卡：点击发广播给 WidgetCheckInReceiver。 */
    CHECK_IN,
    /** 禁用：点击只打开主页，不会写入记录。 */
    DISABLED
}

data class WidgetTextState(
    val progress: String,
    val hint: WidgetHint,
    val button: WidgetButton,
    /** 禁用态按钮的覆盖文案；null 表示保留布局默认的一键打卡。 */
    val disabledLabel: String? = null
)

object WidgetText {
    /** 按今日已完成数与有效任务总数，算出进度 / 提示 / 按钮状态。 */
    fun of(done: Int, total: Int): WidgetTextState {
        val safeDone = done.coerceAtLeast(0)
        val safeTotal = total.coerceAtLeast(0)
        return when {
            safeTotal == 0 -> WidgetTextState(
                progress = "0 / 0",
                hint = WidgetHint.NO_TASK,
                button = WidgetButton.DISABLED
            )
            safeDone >= safeTotal -> WidgetTextState(
                progress = "$safeDone / $safeTotal",
                hint = WidgetHint.ALL_DONE,
                button = WidgetButton.DISABLED,
                disabledLabel = "已完成"
            )
            else -> WidgetTextState(
                progress = "$safeDone / $safeTotal",
                hint = WidgetHint.CHECK_IN_TIP,
                button = WidgetButton.CHECK_IN
            )
        }
    }
}
