package com.morecheck.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.morecheck.data.music.MusicPlayback

/**
 * 播放时的「通知权限」提示（2026-11-08）。
 *
 * ## 为什么需要
 *
 * Android 13+ 的 `POST_NOTIFICATIONS` 是运行时权限。没有它，前台服务照样跑、
 * 音乐照样播，**但通知栏卡片不会出现**——用户会觉得"后台播放做了但没做"
 * （2026-11-08 在模拟器上正是这个现象：媒体会话 PLAyING、`startForegroundCount:0`、
 * 通知栏空无一物）。
 *
 * ## 什么时候问
 *
 * 只在**用户第一次点播放、且后台播放开关是开着**的时候问：
 * - 关掉后台播放的用户不需要通知，问了就是打扰；
 * - 一进页面就问更糟——用户还没表达"我要听歌"。
 *
 * 系统对同一权限只会弹两次，之后不再弹（返回 denied），所以这里不必自己记"问过没有"。
 */
@Composable
fun rememberNotificationPermissionAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响播放：没授权就只是没有通知卡片 */ }

    return remember(context, launcher) {
        {
            val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                MusicPlayback.backgroundPlaybackEnabled() &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            if (needsAsk) {
                // 从非 Activity 的 Context 启动会抛异常；捕获掉，不因为"要不到权限"打断播放。
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}
