package com.morecheck.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay

/**
 * 「实验室功能开屏」：进入实验室的某个功能页时，先盖一层 Hello World 开屏
 * （2026-10-25 需求，开发者选项「实验室功能开屏」开关控制，默认关）。
 *
 * ## 与三个网页游戏自己的开屏是什么关系
 *
 * 贪吃蛇 / 数独 / 数独2 的开屏层在**各 Screen 内部**实现（带游戏专属的取号
 * `GameSplashFirstRun`），它们是独立 Activity、不在本组件的覆盖范围内。
 * 本组件服务的是 **NavHost 里的实验室二级页**（天气 / 记事本 / 智能体 / 课程表 /
 * 热搜 / 今日待办 / 四个小工具 / 番茄钟 / 登录提示），视觉上向游戏开屏看齐：
 * 全屏 `background` 底 + `primary` 色「Hello World」，停留后淡出。
 *
 * ## 「每页只播一次」的口径
 *
 * **本次应用进程内，每个功能页第一次进入时播一次**（与 `GameSplashFirstRun`
 * 同一语义）：离开再进不重播；杀进程重来会再播。开关关闭期间进入**不消耗**
 * 名额（否则用户关开关逛一圈、再开开关就一个都看不到了）。
 *
 * ## durationMs 的三态
 *
 * - `> 0`：正常播放（停留该时长后淡出）；
 * - `<= 0`：不播（开屏时长设为 0 = 「未开启」时，整个实验室开屏也随之不播）；
 * - 由调用方传「未就绪」哨兵（如 -1）时同样不播——DataStore 首读未返回前
 *   不能抢着消耗「第一次」名额。
 */
@Composable
fun LabSplashHost(
    /** 进程内取号用的稳定 key（与页面路由同名）。 */
    routeKey: String,
    /** 开关是否开启（开发者选项 `labFeatureSplashEnabled` 生效值）。 */
    enabled: Boolean,
    /** 开屏停留时长（毫秒）；<= 0 视为不播。 */
    durationMs: Int,
    /** 盖在开屏之下的页面本体。 */
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        // 只在「开关开 + 时长就绪且 > 0」时才消耗名额并画层
        //（理由见 KDoc「每页只播一次」与「durationMs 的三态」）。
        if (enabled && durationMs > 0) {
            // remember 的 lambda 只在首次进入该条件分支的组合时执行一次：
            // 期间重组不会重复取号；离开页面组合销毁，再次进入时 consume
            // 返回 false（本进程已播过），层不再出现。
            val firstRun = remember(routeKey) { LabSplashBook.consume(routeKey) }
            if (firstRun) {
                HelloWorldSplashLayer(durationMs = durationMs)
            }
        }
    }
}

/**
 * 全屏 Hello World 开屏层的**通用实现**（淡出结束后自动移出组合）。
 *
 * 抽出来是为了让「锁屏密码」也能用它（v1.1.92：解锁前先播一层再弹密码）。
 * 两个使用方唯一的差别是"播完要不要通知我"，所以多一个 [onFinished]：
 *
 * - `LabSplashHost` 不关心播完的时刻（层自己消失，下面就是页面本体），传默认空实现；
 * - 锁屏门闸则需要知道播完，才能把这一层换成锁屏页（否则会一直停在开屏上）。
 *
 * @param durationMs 停留时长（毫秒）。
 * @param onFinished 淡出并移出组合之后回调一次。
 */
@Composable
internal fun HelloWorldSplashLayer(
    durationMs: Int,
    onFinished: () -> Unit = {}
) {
    // showing 三态：true（停留中）→ false（开始淡出）→ null（淡出完，移出组合）。
    var showing by remember { mutableStateOf(true) }
    var removed by remember { mutableStateOf(false) }

    // 停留 → 淡出（320ms，与应用开屏 SPLASH_FADE_MS 同一节奏）→ 移除。
    LaunchedEffect(durationMs) {
        delay(durationMs.toLong())
        showing = false
        delay(LAB_SPLASH_FADE_MS.toLong())
        removed = true
        onFinished()
    }

    if (!removed) {
        val alpha by animateFloatAsState(
            targetValue = if (showing) 1f else 0f,
            animationSpec = tween(LAB_SPLASH_FADE_MS),
            label = "labSplashAlpha"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                // 底色取 background：淡出过程中透出的是页面本体，
                // 与游戏开屏「跟随主题」的取色同一口径。
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            // 2026-10-27：文案 / 字号 / 字体 / 单多行改为读「开屏显示文字」设置
            //（默认仍是 34sp 的 Hello World，没改过设置的用户看不出任何变化）。
            // 读取点在这里而不是调用方：本层被实验室各页、数独2 与锁屏共用，
            // 只有一处读才不会出现"某处改了、某处没改"。
            SplashTextLabel(
                style = rememberSplashTextStyle(),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 淡出时长（毫秒），与 `MoreCheckApp.SPLASH_FADE_MS`（320）同值同义。 */
private const val LAB_SPLASH_FADE_MS = 320

/**
 * 「本进程内每个 key 只播一次」的取号表。
 *
 * 与 `domain/GameSplashFirstRun` 同一思想（原子取号，不能拆成「查 + 标记」），
 * 这里用 [ConcurrentHashMap.putIfAbsent] 一步完成：返回 null 说明是第一次
 * （插入成功），非 null 说明已经播过。
 * 游戏那侧用 `AtomicBoolean.compareAndSet` 而这里用 map，只因 key 是多个。
 */
private object LabSplashBook {
    private val shown = ConcurrentHashMap<String, Boolean>()

    /** 是本进程内该 key 的第一次吗？是则当场占号并返回 true。 */
    fun consume(key: String): Boolean = shown.putIfAbsent(key, true) == null
}
