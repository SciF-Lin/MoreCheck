@file:Suppress("UnsafeOptInUsageError") // media3 的 UnstableApi 不是 Kotlin RequiresOptIn 标记，lint 按误报抑制

package com.morecheck.data.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.morecheck.MainActivity
import com.morecheck.R

/**
 * 音乐播放的前台服务（2026-11-08 需求：后台播放 + 通知栏卡片）。
 *
 * ## 它做什么
 *
 * 只是把 [MusicPlayback] 里那个共享 `ExoPlayer` 包进一个 `MediaSession`，然后交给
 * media3 的 [MediaSessionService] 托管：
 *
 * - **播放中自动升为前台服务**，进程不会被系统随手回收（这是"后台还能播"的前提）；
 * - **通知栏卡片的播放/暂停/上一首/下一首/进度**由 media3 自动生成，不用手写；
 * - **锁屏与耳机键 / 蓝牙控制**走同一个 MediaSession，也不用手写。
 *
 * ## 它不做什么
 *
 * 不创建自己的播放器。界面和通知栏必须操作**同一个** player，否则会出现
 * "通知栏暂停了，界面还在转圈"这类经典错位。所以播放器归属 [MusicPlayback]，
 * 这里只借用。
 *
 * ## 生命周期
 *
 * - `startService` 在**每次开始播放**时调用（幂等，重复调不会重复建会话）；
 * - 暂停后 media3 会把服务退回后台（通知还在，可继续操作）；
 * - 用户在最近任务里划掉应用时 [onTaskRemoved] 判断：没在播就 `stopSelf()`，
 *   还在播就继续（这正是"后台播放"的语义）；
 * - `onDestroy` 只释放会话，**不**释放播放器——播放器归 [MusicPlayback]，
 *   在这里释放会让界面下一次操作直接崩在已释放的实例上。
 */
class MusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 通知渠道名与 id 由我们给，避免默认的 "media3_notification_channel" 出现在系统设置里。
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                // ⚠️ 这个名字参数是**字符串资源 id**（`@StringRes int`），不是 String。
                // 小图标在 media3 1.11 的 Builder 上没有 setter，只能靠资源名约定：
                // `res/drawable/media3_notification_small_icon.xml`（见那个文件的注释）。
                .setChannelName(R.string.music_playback_channel)
                .setNotificationId(NOTIFICATION_ID)
                .build()
        )

        session = MediaSession.Builder(this, MusicPlayback.ensurePlayer(this))
            // 点通知卡片回到应用（不是回到音乐页：应用内没有直达路由，回首页最稳）。
            .setSessionActivity(openAppIntent())
            .build()
            .also { built ->
                // ⚠️ 必须显式 addSession（2026-11-08 实测踩到，否则**永远没有通知卡片**）。
                //
                // media3 只在 `onBind`（有 MediaController 连进来）时才会把这个会话
                // 加进服务（`MediaSessionService.onBind` → `addSession`），而本应用没有
                // 走 MediaController（界面直接持有同一个 ExoPlayer）。
                // 结果是：服务被 startService 起来了、系统也看得到会话、媒体键能控制，
                // 但 `startForegroundCount:0`、通知栏什么都没有。
                //
                // 手动 addSession 会同时把服务的 `MediaSession.Listener` 挂上，
                // 之后播放状态一变就会触发 `onUpdateNotification` → 建渠道 + 发通知 + 升前台。
                addSession(built)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户从最近任务里划掉应用。
     *
     * 正在播 → 保留服务（后台继续）；已暂停 → 直接结束，不留一个空的通知卡片。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MusicPlayback.isPlaying()) {
            stopSelf()
            return
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 0x4D55 // "MU"

        /**
         * 拉起服务（幂等）。
         *
         * 用 `startService` 而不是 `startForegroundService`：调用点一定在
         * 用户点了播放之后（应用在前台），此时普通 `startService` 合法；
         * `startForegroundService` 要求 5 秒内必须 `startForeground`，
         * 而 media3 是在"真正开始播放"时才升前台，取链慢一点就会 ANR 崩。
         */
        fun start(context: Context) {
            runCatching {
                context.startService(Intent(context, MusicPlaybackService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, MusicPlaybackService::class.java))
            }
        }
    }
}
