package com.morecheck.ui.lab

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.morecheck.data.game.GameDataBridge
import com.morecheck.di.appContainer

/**
 * 四个游戏页共用的**存档同步管线**（v3 云同步）。
 *
 * ## 为什么这里可以抽公共代码，而页面本体不能
 *
 * 项目纪律「一屏一个自洽实现」约束的是**视觉与交互决策**（开屏、chrome 档位、
 * 标题栏开关——那些必须每页自己拍板）。本文件这三样东西不是决策，是**纯管线**：
 *
 * - [rememberGameBridge]：从容器取 dao + 作用域建桥。四页若各写一遍，
 *   差别只会是手滑（用错 gameKey、忘记传 scope），不可能是有意的不同；
 * - [pushGameSnapshot]：把采集脚本 `evaluateJavascript` 进页面。四页逐字相同；
 * - [GAME_PUSH_INTERVAL_MS]：采集节奏。四页不同步调只会让"为什么番茄钟的存档
 *   比数独容易丢"这种无意义的问题出现。
 *
 * 反过来，**注册桥的时机、`onPageFinished` 与页面既有 `WebViewClient` 的合并、
 * 离开页面时的兜底推送**仍然每页自己写——那些与该页既有的 WebView 配置
 * （退出桥、asset loader、flushSave）纠缠在一起，抽出来反而要把四页的差异
 * 塞进形参里。
 */

/**
 * 定时采集存档的间隔（毫秒）。
 *
 * 15 秒的取舍：存档数据量在几百字节 ~ 几 KB，一次采集 + 一次 Room 写的成本
 * 可以忽略；间隔取小只是为了缩小"退出页面时最后一批改动的丢失窗口"
 * （真正的兜底是各页 `onRelease` 里的离开前采集）。
 */
internal const val GAME_PUSH_INTERVAL_MS = 15_000L

/**
 * 建（并缓存）本页游戏的数据桥。
 *
 * 桥本身无状态（只有 gameKey + dao + scope 引用），随组合期 `remember` 缓存；
 * 页面销毁时桥对象一起被回收，**不需要**任何显式清理——它没有注册任何回调，
 * WebView 侧的 `removeJavascriptInterface` 由各页的 holder 销毁路径负责。
 *
 * @param gameKey [GameDataBridge.GameKeys] 里的某一个。
 */
@Composable
internal fun rememberGameBridge(gameKey: String): GameDataBridge {
    val container = LocalContext.current.appContainer
    return remember(gameKey) {
        GameDataBridge(
            gameKey = gameKey,
            dao = container.database.checkDao(),
            scope = container.appScope
        )
    }
}

/**
 * 把当前页面里的存档采集并推给原生（写进 `game_snapshots`，等同步引擎上传）。
 *
 * 经 `post {}` 包一层：`evaluateJavascript` 虽然内部线程安全，但各页的 holder
 * 可能在视图已 detach 后才被读到，`post` 到已销毁的 WebView 上是静默 no-op，
 * 不会抛"WebView 已销毁"的异常。
 */
internal fun WebView.pushGameSnapshot(gameKey: String) {
    val script = GameDataBridge.collectPushScript(gameKey)
    if (script.isEmpty()) return
    post { evaluateJavascript(script, null) }
}

/**
 * 游戏页存档同步的**统一装配说明**（给四个页面的实现者看的 checklist）：
 *
 * 1. `addJavascriptInterface(bridge, GameDataBridge.JS_NAME)` —— 必须在
 *    `loadUrl` **之前**（水合脚本在页面解析期就调 `pendingState()`）；
 * 2. `onPageFinished` 里 `view.pushGameSnapshot(gameKey)` —— 页面加载完
 *    立即采集一次（此时 localStorage 已是页面自己恢复出来的内容）；
 * 3. 组合期一个 `LaunchedEffect` 循环：`delay(GAME_PUSH_INTERVAL_MS)` 后
 *    `holder.view?.pushGameSnapshot(gameKey)`；
 * 4. `onRelease` / 销毁前再采集一次（尽力而为：`evaluateJavascript` 是异步的，
 *    采集脚本本身在 WebView 消息循环里跑，随后桥的写投递到进程级 IO 作用域，
 *    不随 WebView 销毁而取消）。
 */
