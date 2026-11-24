package com.morecheck.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程启动瞬间读到的几个「历史状态」（2026-11-14 需求 1）。
 *
 * ## 为什么需要它
 *
 * 「更新后首次启动」要区分两件**看起来一样**的事：
 * - 老用户从上一版升级上来（该弹更新介绍）；
 * - 全新安装 / 清过数据（不该弹）。
 *
 * 判断依据是"这个用户在**本次启动之前**是否已经用过本应用"，而最可靠、最早就存在的
 * 痕迹是隐私协议同意标记（`privacy_agreed`）——它在第一次点「同意」时写入，
 * 之后一直为真。
 *
 * ⚠️ 关键在于**读取时机**：UI 是在隐私门闸之后才组合的，那时全新安装的用户
 * 也已经同意了，标记为真，两种情况又混在一起。所以必须在
 * [com.morecheck.MoreCheckApp.onCreate]（协议弹窗出现之前）就先读一次，
 * 把结果放进这里，UI 只来取现成的结论。
 *
 * ## 为什么用 StateFlow 而不是普通 Boolean
 *
 * Application 里的读取在 IO 线程异步进行，UI 可能在它完成之前就组合了。
 * UI 侧 `filterNotNull().first()` 会**挂起等待**这个值，因此不存在"读到 null 就当没升级"
 * 的竞态；读失败时也会写 `false`（宁可少弹一次，不要误弹给新用户）。
 */
object LaunchState {

    private val _everUsedBeforeLaunch = MutableStateFlow<Boolean?>(null)

    /** 本次启动之前是否已经用过（null = 还在读）。 */
    val everUsedBeforeLaunch: StateFlow<Boolean?> = _everUsedBeforeLaunch

    /** 由 Application 在启动早期写入，之后不再改动。 */
    fun markEverUsedBeforeLaunch(used: Boolean) {
        if (_everUsedBeforeLaunch.value == null) {
            _everUsedBeforeLaunch.value = used
        }
    }
}
