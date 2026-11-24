package com.morecheck.ui.mine

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

/**
 * 订阅「当前头像位图」，供「我的」页与「我的信息」页共用。
 *
 * ## 为什么抽成一个函数
 *
 * 这两页原本各抄了一份 12 行的「collect + LaunchedEffect 解码 + 写回缓存」，
 * 连注释都是复制的；2026-10-26 修闪动时要在两处做同样的改动，正是重复代码开始漂移的信号。
 *
 * ## ⚠️ 为什么不是 `collectAsStateWithLifecycle(initialValue = null)`
 *
 * 那是闪动的真正成因（2026-10-26 定位）：
 *
 * 1. `collectAsStateWithLifecycle` 在流**第一次发射到达之前**先返回 `initialValue`，
 *    而 DataStore 的首读要走磁盘（且有 IO 线程切换），所以第一帧拿到的必然是 `null`；
 * 2. 那一帧的组合里 `remember { cachedFor(...) }` 明明是**缓存命中**（正确头像），
 *    但随后的 `LaunchedEffect(avatarPath, ...)` 把 `null` 当成"用户没有头像"，
 *    立即 `avatarBitmap = null` → 灰占位闪一下 → 真正的路径到达 → 再解码出头像。
 * 3. 净效果就是用户报告的「切到我的页头像闪一下」。缓存本身没白做，
 *    它只挡住了**第一帧**，第二帧又被自己抹掉了。
 *
 * 修法：不再用"合成初值"，而是在 [LaunchedEffect] 里 **collect 真流**——
 * 协程收到的第一个值就是盘上的真实值，`null` 只可能表示"确实没设置过头像"。
 * 同时 `combine` 上 [AvatarRevision]：头像文件路径恒定、内容会被覆盖，
 * 只靠 path 感知不到"换了一张"，必须由版本号再触发一次解码。
 *
 * 副作用仅两处，都在 IO 完成之后：写进程内缓存（供下次进页首帧命中）、更新返回的位图。
 */
@Composable
fun rememberAvatarBitmap(): Bitmap? {
    val context = LocalContext.current
    val settings = remember(context) { context.appContainer.settingsRepository }

    // 换头像的进程级版本号（定义见 PersonalInfoScreen.kt 末尾的 AvatarRevision）。
    val revision by AvatarRevision.value.collectAsStateWithLifecycle()

    // 首帧直接吃缓存：同一进程内切回本页时正确头像第一帧就在，不会先画灰占位。
    var bitmap by remember { mutableStateOf(AvatarStore.cachedFor(revision)) }

    LaunchedEffect(settings) {
        combine(settings.avatarPath, AvatarRevision.value) { path, rev -> path to rev }
            .collect { (path, rev) ->
                val decoded = if (path == null) {
                    // 真·没有头像（键被移除）。这里收到的是**真实发射**，不是初值。
                    null
                } else {
                    withContext(Dispatchers.IO) { AvatarStore.decodeStored(path) }
                }
                AvatarStore.cacheDecoded(rev, decoded)
                bitmap = decoded
            }
    }

    return bitmap
}
