// 桌面歌词：把当前这句歌词浮在屏幕最上层（2026-11-09 需求 5）。
//
// ## 为什么是前台服务 + 系统悬浮窗
//
// 桌面歌词的意义就是"离开 App 也能看"，所以它必须活在 Activity 之外：
// 用 `TYPE_APPLICATION_OVERLAY` 的 WindowManager 窗口 + 前台服务保活。
// 本项目已有同构先例（`ui/lab/NotebookOverlayService`），这里沿用它的三条纪律：
// 1. `startForeground` 必须**第一时间**调用（否则 ANR/崩溃）；
// 2. 权限 `SYSTEM_ALERT_WINDOW` 要在 UI 层先拿到，Service 只负责"请给我就画"；
// 3. 所有窗口操作都在主线程，销毁时先 `removeView` 再 `stopSelf`。
//
// ## 为什么不用 ComposeView
//
// 悬浮窗的 ComposeView 没有 Activity 作为 ViewTreeOwner，主题、生命周期、
// 重组作用域都要手工搭（笔记本那边踩过）。桌面歌词只有两行字 + 一个关闭键，
// 用原生 `TextView` 拼一下更稳、更省内存，也能直接拿到系统的默认字体渲染。

package com.morecheck.ui.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.morecheck.MainActivity
import com.morecheck.R
import com.morecheck.data.music.MusicPlayback
import com.morecheck.data.net.MusicLyrics
import com.morecheck.data.net.MusicTrack
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 桌面歌词的开关入口（给 UI 用）。
 *
 * 开关值本身存在 `SettingsRepository.musicDesktopLyric`，这里只管**服务的起停**：
 * UI 改完开关调 [sync]，服务名下的窗口状态就跟着变。
 */
object DesktopLyric {

    /** 按开关值启动 / 停止悬浮窗。 */
    fun sync(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            if (!canDrawOverlays(app)) return
            runCatching {
                app.startForegroundService(Intent(app, LyricOverlayService::class.java))
            }
        } else {
            runCatching { app.stopService(Intent(app, LyricOverlayService::class.java)) }
        }
    }

    /** 是否已获得"显示在其他应用上层"权限。 */
    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

/**
 * 桌面歌词悬浮窗服务。
 *
 * 一个可拖动的小卡片：当前这句歌词（大）+ 下一句（小），右上角「×」关闭（同时把设置开关关掉）。
 * 歌词跟随 [MusicPlayback.state] 的 `positionMs` 走，切歌时重新载入歌词（走两级缓存，不额外打接口）。
 */
class LyricOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var currentText: TextView? = null
    private var nextText: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var lyrics: List<MusicLyrics.Line> = emptyList()
    private var lyricTrackId: String? = null
    private var lyricJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        addOverlay()
        observePlayback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用户从通知栏点「关闭」时也会走这里（action = ACTION_STOP）。
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        super.onDestroy()
    }

    // ---- 通知 ---------------------------------------------------------------

    private fun startForegroundWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "桌面歌词",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在其他应用上显示当前歌词"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LyricOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("桌面歌词已开启")
            .setContentText("歌词会浮在其他应用上方，可拖动位置")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "关闭", stop)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    // ---- 悬浮窗 -------------------------------------------------------------

    private fun addOverlay() {
        if (!DesktopLyric.canDrawOverlays(this)) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#E6101A2E"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
        }
        currentText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            text = "桌面歌词已就绪"
        }
        nextText = TextView(this).apply {
            setTextColor(Color.parseColor("#B9C4D6"))
            textSize = 12f
            maxLines = 1
            text = ""
        }
        val close = TextView(this).apply {
            text = "×"
            setTextColor(Color.parseColor("#B9C4D6"))
            textSize = 16f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { stopSelf() }
        }
        container.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(currentText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            }
        )
        container.addView(nextText)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        params = layoutParams
        attachDrag(container, layoutParams)
        runCatching { windowManager.addView(container, layoutParams) }
        root = container
    }

    /** 拖动移动位置；轻点不做任何事（避免误触）。 */
    private fun attachDrag(view: View, layoutParams: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        layoutParams.x = startX + dx.toInt()
                        layoutParams.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, layoutParams) }
                    }
                    true
                }

                else -> false
            }
        }
    }

    // ---- 歌词跟随 -----------------------------------------------------------

    private fun observePlayback() {
        scope.launch {
            MusicPlayback.state.collectLatest { state ->
                val track = state.track
                if (track == null) {
                    currentText?.text = "桌面歌词已就绪"
                    nextText?.text = ""
                    return@collectLatest
                }
                if (track.id != lyricTrackId) {
                    lyricTrackId = track.id
                    lyrics = MusicLyrics.peek(track.id) ?: emptyList()
                    if (lyrics.isEmpty()) {
                        lyricJob?.cancel()
                        lyricJob = scope.launch {
                            val loaded = runCatching { MusicLyrics.load(track, this@LyricOverlayService) }
                                .getOrDefault(emptyList())
                            if (lyricTrackId == track.id) lyrics = loaded
                        }
                    }
                }
                renderLine(track, state.positionMs)
            }
        }
    }

    private fun renderLine(track: MusicTrack, positionMs: Long) {
        if (lyrics.isEmpty()) {
            currentText?.text = if (track.name.isBlank()) "桌面歌词" else track.name
            nextText?.text = ""
            return
        }
        val index = lyrics.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
        currentText?.text = lyrics.getOrNull(index)?.text.orEmpty().ifBlank { track.name }
        nextText?.text = lyrics.getOrNull(index + 1)?.text.orEmpty()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "desktop_lyric"
        private const val NOTIFICATION_ID = 4127
        const val ACTION_STOP = "com.morecheck.action.STOP_LYRIC_OVERLAY"
    }
}
