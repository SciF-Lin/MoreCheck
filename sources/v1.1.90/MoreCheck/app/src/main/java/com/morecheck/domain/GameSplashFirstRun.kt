package com.morecheck.domain

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「Hello World」游戏开屏的**进程内**首次标记（v1.1.74 需求 5）。
 *
 * ## 需求原文与最终口径
 *
 * 需求：「游戏的启动 helloworld 只在第一次进入时显示，短时间内再次进入则不显示」，
 * 用户随后明确：「**应用在后台运行时不显示**」。
 *
 * 两条合起来就是一句话：**本次应用进程生命周期内，第一次进入游戏页才播开屏**。
 * 于是：
 *
 * - 玩一局 → 退出 → 应用还在后台 → 再进游戏：**不播**（这是用户真正在意的场景）；
 * - 应用被系统回收 / 手动杀掉后重新冷启动 → 第一次进游戏：**照常播**。
 *
 * ## 为什么是「进程内」而不是持久化
 *
 * 刻意**不**写 DataStore（这是用户确认过的口径），理由：
 *
 * 1. **语义上不需要持久化**。持久化会变成「这台设备这辈子只播一次」，
 *    那是另一个需求；用户要的是「别在后台来回切时反复闪」，进程内状态恰好覆盖。
 * 2. **持久化会与另一个开关打架**：「开发者选项 → 游戏开屏显示」是用户**手动**的
 *    长期偏好。若再叠一个持久化的「已看过」标记，就会出现「用户手动打开开屏，
 *    但因为历史上看过所以永远不播」这种无法解释的状态。
 * 3. 进程被杀掉就重来，反而符合「Hello World 是给新会话的一个仪式感」这层意思。
 *
 * ## 为什么放在 `domain` 而不是 `data`
 *
 * 这里没有任何 IO：就是一个进程内的布尔量 + 两条纯函数式的判定规则。
 * 放 `domain` 与 `StreakCalculator`（同样是纯逻辑、零 Android 依赖）同一口径，
 * 也让「规则」与「谁在用它」彻底分开。
 *
 * ## 并发口径
 *
 * 用 [java.util.concurrent.atomic.AtomicBoolean] 而非普通 `var`：
 * 游戏页现在各自住在**独立 Activity / 独立 task**（见 `GameActivityHost`），
 * 主 task 与游戏 task 的组合可能同时存在。`AtomicBoolean` 保证
 * 「先到先得、只有一个赢家」——两个页面几乎同时被打开时，只有真正第一个
 * 进入的那个会播开屏，另一个直接跳过。这是本文件存在并发保护的实际原因。
 */
object GameSplashFirstRun {

    /** 本进程是否已经播过一次游戏开屏。`true` = 已经播过了，之后都不再播。 */
    private val alreadyShown = AtomicBoolean(false)

    /**
     * 本次进入**是否应该**播开屏；调用方应当**恰好调用一次**。
     *
     * ## 为什么用「问的时候就把标记占掉」而不是「问完再标记」
     *
     * 若拆成 `shouldShow()` + `markShown()` 两步，两个页面同时进入时可能都读到
     * `false`（都认为自己该播），或者都跳过。合并成一次「取号」就只有一个赢家。
     *
     * @param enabled 调用方算好的**生效值**（总开关 && 用户开关）。传 `false` 时
     *   **不消耗首次机会**——用户手动关掉开屏时不该把「第一次」用掉，
     *   否则他下次打开开关就再也看不到开屏了。这是与需求同等重要的一个细节。
     * @return `true` 表示这次应当播开屏（并且首次机会已被消耗）。
     */
    fun consumeIfFirstRun(enabled: Boolean): Boolean {
        // 用户关着开屏：不播，但也**不**占掉首次机会（见 KDoc）。
        if (!enabled) return false
        // compareAndSet(false, true)：只有把 false 成功换成 true 的那个调用者拿到 true。
        return alreadyShown.compareAndSet(false, true)
    }

    /**
     * 仅供测试与调试读取当前状态；**业务代码不应调用**。
     *
     * 业务侧需要的判定只有 [consumeIfFirstRun] 一条。
     */
    fun hasAlreadyShown(): Boolean = alreadyShown.get()
}
