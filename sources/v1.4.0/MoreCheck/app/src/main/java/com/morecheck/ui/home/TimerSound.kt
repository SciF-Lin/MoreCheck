package com.morecheck.ui.home

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 铃声最长响多久。
 *
 * 到点提示不能「响到用户自己去关」：手机扣在桌上时铃声可能响很久，
 * 既扰民也费电，所以到点 30 秒后强制停下（震动与 Snackbar 已经提示过了）。
 */
private const val RING_TIMEOUT_MILLIS = 30_000L

/** 声音设置页「试听」的时长：3 秒，够听出是什么铃声，又不会拖太久。 */
const val PREVIEW_RING_MILLIS = 3_000L

/**
 * 计时器铃声播放器。
 *
 * 用 `android.media.RingtoneManager` + `Ringtone` 播放：这是系统现成的能力，
 * **不需要任何权限**，也不用往 `res/raw/` 里塞音频文件。
 *
 * 刻意**不持有自己的 `CoroutineScope`**：30 秒自动停止的协程必须挂在调用方的
 * 组合作用域上（见 [rememberTimerRinger]），这样页面一离开组合，作用域自动取消，
 * 不会留下取消不掉的协程，也不会留下响个不停的铃声。
 */
class TimerRinger(private val scope: CoroutineScope) {

    private var ringtone: Ringtone? = null
    private var autoStopJob: Job? = null

    /** 当前是否真的在响：读系统 [Ringtone] 的实际状态，而不是我们自己记的标志位。 */
    val isPlaying: Boolean get() = ringtone?.isPlaying == true

    /**
     * 播放 [uri] 指向的铃声；[uri] 为 null 表示系统默认通知音。
     *
     * [durationMillis] 到点后自动 [stop]（默认 30 秒，试听传 [PREVIEW_RING_MILLIS]）。
     *
     * 失败一律静默降级：先回退到系统默认音，再失败就什么都不放。
     * 到点还有震动和 Snackbar 兜底，绝不能因为一个失效的 URI 让应用崩掉。
     */
    fun play(context: Context, uri: String?, durationMillis: Long = RING_TIMEOUT_MILLIS) {
        // 先停掉上一段：连续触发时不会两个铃声叠着响。
        stop()
        val tone = resolveRingtone(context.applicationContext, uri) ?: return
        // play() 在个别机型 / 坏 URI 上会抛（IllegalStateException 等），必须包住。
        if (runCatching { tone.play() }.isFailure) return
        ringtone = tone
        autoStopJob = scope.launch {
            delay(durationMillis)
            stop()
        }
    }

    /** 立刻停止（重复调用安全）。 */
    fun stop() {
        autoStopJob?.cancel()
        autoStopJob = null
        val current = ringtone
        ringtone = null
        if (current != null) runCatching { current.stop() }
    }

    /**
     * 解析出可播放的 [Ringtone]。
     *
     * `getRingtone` 在 URI 失效（文件被删、SAF 持久化权限丢了）时返回 null 或直接抛，
     * 所以整条链路都用 runCatching 包住，拿不到就退到系统默认通知音，再拿不到返回 null。
     */
    private fun resolveRingtone(context: Context, uri: String?): Ringtone? {
        if (!uri.isNullOrBlank()) {
            val picked = runCatching {
                RingtoneManager.getRingtone(context, Uri.parse(uri))
            }.getOrNull()
            if (picked != null) return picked
        }
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: return null
        return runCatching { RingtoneManager.getRingtone(context, fallback) }.getOrNull()
    }
}

/**
 * 取一个与当前组合同生命周期的 [TimerRinger]。
 *
 * [DisposableEffect] 保证**离开页面时一定停**：到点后用户可能立刻切换标签 / 返回，
 * 铃声不能跟着飘到别的页面去；协程作用域由 `rememberCoroutineScope` 提供，
 * 离开组合时连那个 30 秒延时任务一起取消。
 */
@Composable
fun rememberTimerRinger(): TimerRinger {
    val scope = rememberCoroutineScope()
    val ringer = remember(scope) { TimerRinger(scope) }
    DisposableEffect(ringer) {
        onDispose { ringer.stop() }
    }
    return ringer
}
