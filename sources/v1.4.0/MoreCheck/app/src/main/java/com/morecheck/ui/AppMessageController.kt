package com.morecheck.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.channels.Channel

/**
 * 全局提示（Snackbar）的统一调度器（2026-11-14）。
 *
 * 之前所有页面都直接 `snackbarHostState.showSnackbar(message)`，M3 的行为是：
 * **不去重、串行排队、每条固定 4 秒**。于是快速连点会看到同一句话连着刷好几次，
 * 或者 5 条提示排队 20 秒；在「关于我们」页还会压住底部的链接入口。
 * 这里用一个 `Channel.CONFLATED` 做单点消费：
 *
 * - **相同文案去重**：[DEDUPE_WINDOW_MS] 内重复出现的同一句话直接丢掉；
 * - **多条合并**：当前这条还没播完时来的新消息只保留最后一条（CONFLATED），
 *   不排队累积；
 * - **动态时长**：由宿主里自定义 `Snackbar` 的计时器按 [shortDuration] 决定，
 *   单条 1800ms、连续多条 1100ms（M3 的 `Short` 写死 4 秒，做不到）；
 * - **位置避让**：由 `MoreCheckRoot` 根据「是否一级页」另加一段底部留白，
 *   二级页把提示抬到页面底部可交互区之上。
 *
 * 这个控制器只接管主通道（`onMessage` / `onActionMessage`）；
 * 登录/扫码的居中浮层、离开页面仍要可见的系统 Toast、音乐页内联 `flash`
 * 都有各自明确的设计理由，不受影响。
 */
@Stable
class AppMessageController {
    /** CONFLATED：同一时刻只留最新一条，天然实现「多条合并」。 */
    private val inbox = Channel<String>(Channel.CONFLATED)

    private var lastText = ""
    private var lastAt = 0L
    private var windowStart = 0L
    private var windowCount = 0
    private val _shortDuration = mutableStateOf(false)

    /** 当前是否处于「连续多条」状态，供宿主的显示计时器读取。 */
    val shortDuration: State<Boolean> get() = _shortDuration

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        if (text == lastText && now - lastAt < DEDUPE_WINDOW_MS) return
        if (now - windowStart > BURST_WINDOW_MS) {
            windowStart = now
            windowCount = 0
        }
        windowCount += 1
        _shortDuration.value = windowCount > 1
        lastText = text
        lastAt = now
        inbox.trySend(text)
    }

    /**
     * 在 `LaunchedEffect(Unit)` 里调用一次：串行消费消息。
     *
     * 用 `Indefinite` 把时长交给宿主里的自定义 Snackbar 计时器；
     * 若上一条还在（极端时序），先 dismiss 再显示新的一条。
     */
    suspend fun pump(hostState: SnackbarHostState) {
        for (message in inbox) {
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Indefinite
            )
        }
    }

    companion object {
        /** 同一句话的去重时间窗。 */
        const val DEDUPE_WINDOW_MS = 1_500L

        /** 判定「连续多条」的窗口。 */
        const val BURST_WINDOW_MS = 4_000L

        /** 单条提示停留时长（比 M3 默认 4s 短，避免长时间压住页面）。 */
        const val SINGLE_DURATION_MS = 1_800L

        /** 连续多条时每条的停留时长。 */
        const val BURST_DURATION_MS = 1_100L

        /** 二级页（底栏隐藏）额外抬高的距离，避开页面底部链接/按钮区。 */
        const val SECONDARY_PAGE_EXTRA_DP = 96
    }
}
