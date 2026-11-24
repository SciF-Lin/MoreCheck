package com.morecheck.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.music.KugouAccountStore
import com.morecheck.di.appContainer
import com.morecheck.data.music.MusicPlayback
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.SquircleSwitch
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.util.CacheCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「音乐账号」独立路由（2026-11-08 需求 1：UI 风格跟随音乐）。
 *
 * 不再用 `Scaffold` + `TopAppBar` + M3 `Card`：那一套是应用设置页的语言，
 * 和音乐页的玻璃卡片 / 渐变底 / 球面圆键放在一起会明显割裂。
 * 现在整页是 [MusicBackground] + 圆键顶栏 + [GlassCard]，颜色全部来自
 * [rememberMusicPalette]，切主题时主页 / 播放页 / 本页一起换。
 *
 * 内容本体抽成 [MusicAccountContent]：音乐主页底栏「我的」把这段内容**内嵌**在同一页里
 * （2026-11-09 起不再整屏滑出一个覆盖层、也没有自己的返回栈），两处共用一份逻辑，
 * 不会各改各的；本路由只留给「设置 → 音乐」这个真正的二级入口。
 */
@Composable
fun MusicAccountScreen(
    onBack: () -> Unit,
    /** 提示出口（清理缓存、退出登录的结果）。 */
    onMessage: (String) -> Unit = {},
    viewModel: SettingsViewModel = LocalSettingsViewModel.current
) {
    var showThemeSheet by remember { mutableStateOf(false) }

    // 本页也算"音乐流程内"：进来看设置时不该被判成离页（见 MusicPlayback.onScreenAttached）。
    DisposableEffect(Unit) {
        MusicPlayback.onScreenAttached()
        onDispose { MusicPlayback.onScreenDetached() }
    }

    val palette = rememberMusicPalette()
    MusicBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    size = 40.dp
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "音乐账号",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text
                )
                Spacer(Modifier.weight(1f))
                ThemeBrushButton(onClick = { showThemeSheet = true }, size = 40.dp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                MusicAccountContent(onMessage = onMessage, viewModel = viewModel)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showThemeSheet) {
        MusicThemeSheet(onDismiss = { showThemeSheet = false })
    }
}

/**
 * 账号 + 音乐设置的**内容本体**，可嵌进任意音乐页面。
 *
 * 三段：账号（登录 / 退出）、音乐主题（三风格 × 明暗）、音乐行为（后台播放 / 缓存）。
 * 所有卡片走 [GlassCard]，所有文字走 palette；这里没有一处 `MaterialTheme.colorScheme`。
 */
@Composable
fun MusicAccountContent(
    onMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = LocalSettingsViewModel.current
) {
    val context = LocalContext.current
    val palette = rememberMusicPalette()
    val scope = rememberCoroutineScope()

    val store = remember(context) { KugouAccountStore(context) }
    val account by store.account.collectAsStateWithLifecycle(initialValue = null)
    val backgroundPlayback by viewModel.musicBackgroundPlayback.collectAsStateWithLifecycle()
    // 2026-11-09 需求 5：两个歌词开关（逐字 / 桌面歌词）。
    val karaokeLyric by viewModel.musicLyricKaraoke.collectAsStateWithLifecycle()
    val desktopLyric by viewModel.musicDesktopLyric.collectAsStateWithLifecycle()
    // 2026-11-14：退出确认不再只是开发者选项，音乐设置里也能开关。
    // 两处写同一枚 DataStore 值（dev_music_exit_confirm），MusicScreen 读原始值。
    val developerOptions = remember(context) {
        context.applicationContext.appContainer.developerOptionsRepository
    }
    val exitConfirm by developerOptions.musicExitConfirmSwitch
        .collectAsStateWithLifecycle(initialValue = false)
    // 2026-11-14 需求 2：进音乐页的确认弹窗也搬到这里。
    // 它与「退出音乐时确认」共用同一段 UI 位置，用户不必再去开发者选项翻。
    // 语义说明：仓库里存的是「不再提示」标记（勾过就不再弹），
    // 界面上一律用正向表述「进入音乐时确认」，checked = 取反。
    val settingsRepository = remember(context) {
        context.applicationContext.appContainer.settingsRepository
    }
    val entryTipDismissed by settingsRepository.musicUiTipDismissed
        .collectAsStateWithLifecycle(initialValue = false)

    var showLoginDialog by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var cacheBytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    /** 2026-11-09 需求 6：清缓存先确认。 */
    var confirmClear by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }

    // 缓存体积在进页面时算一次，清理后再算一次。
    LaunchedEffect(tick) {
        cacheBytes = withContext(Dispatchers.IO) { CacheCleaner.musicBytes(context) }
    }

    Column(modifier) {
        // ---- 账号 ----
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val current = account
                if (current == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(palette.track),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = palette.textMuted
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "未登录酷狗账号",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.text
                            )
                            Text(
                                text = "登录后可用账号授权播放会员曲目；不登录也能搜歌与播放",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSub
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(palette.accentLight, palette.accentDark)
                                )
                            )
                            .bounceClick(onClick = { showLoginDialog = true }),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "扫码登录酷狗",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onAccent
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KugouAvatar(account = current, size = 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = current.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.text,
                                maxLines = 1
                            )
                            Text(
                                text = if (current.isVip) "会员权益：可用" else "账号已登录",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSub
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // 退出登录是副作用动作：用描边按钮而不是实心强调色。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.glass)
                            .border(1.dp, palette.glassBorder, RoundedCornerShape(14.dp))
                            .bounceClick(
                                onClick = { confirmSignOut = true },
                                enabled = !signingOut
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (signingOut) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = palette.accent
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Logout,
                                contentDescription = null,
                                tint = palette.text,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "退出登录",
                                fontSize = 14.sp,
                                color = palette.text
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 音乐主题（与主页 / 播放页顶栏刷子键同一份设置）----
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicBrushIcon(
                        outline = Brush.linearGradient(
                            listOf(palette.accentLight, palette.accentDark)
                        ),
                        fill = Color.White,
                        dimmed = false,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "音乐主题",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.text
                    )
                }
                Spacer(Modifier.height(14.dp))
                MusicThemeControls()
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 音乐设置 ----
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "音乐设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.text
                    )
                }
                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "后台播放",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            "离开应用后继续播放，通知栏与锁屏可以控制。" +
                                "关闭后离开播放页即暂停，也不会有通知卡片。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SquircleSwitch(
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.track,
                        thumbColor = palette.onAccent,
                        outlineColor = palette.buttonBorder,
                        checked = backgroundPlayback,
                        onCheckedChange = { viewModel.setMusicBackgroundPlayback(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(12.dp))

                // ---- 逐字歌词 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "逐字歌词",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            "歌词页按演唱进度一个字一个字点亮（按行内时间插值，" +
                                "不需要逐字歌词源）。关掉则整行一起变色。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SquircleSwitch(
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.track,
                        thumbColor = palette.onAccent,
                        outlineColor = palette.buttonBorder,
                        checked = karaokeLyric,
                        onCheckedChange = { viewModel.setMusicLyricKaraoke(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(12.dp))

                // ---- 桌面歌词 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "桌面歌词",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            "把当前这句歌词浮在其他应用上方，可拖动位置。" +
                                "首次开启需要授予「显示在其他应用上层」权限。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SquircleSwitch(
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.track,
                        thumbColor = palette.onAccent,
                        outlineColor = palette.buttonBorder,
                        checked = desktopLyric,
                        onCheckedChange = { enabled ->
                            if (enabled && !DesktopLyric.canDrawOverlays(context)) {
                                // 没权限：先去系统页授予，回来再点一次即可。
                                requestOverlayPermission(context)
                                onMessage("请先允许「显示在其他应用上层」，再回来打开桌面歌词")
                            } else {
                                viewModel.setMusicDesktopLyric(enabled)
                                DesktopLyric.sync(context, enabled)
                                onMessage(if (enabled) "桌面歌词已开启" else "桌面歌词已关闭")
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(12.dp))

                // ---- 进入音乐时确认（2026-11-14 需求 2：从开发者选项搬来）----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "进入音乐时确认",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            "从发现页或实验室点「音乐」时先弹一次说明与确认框。" +
                                "关掉后点一下就直达音乐页。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SquircleSwitch(
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.track,
                        thumbColor = palette.onAccent,
                        outlineColor = palette.buttonBorder,
                        checked = !entryTipDismissed,
                        onCheckedChange = { enabled ->
                            // 关掉 = 记下「不再提示」；重新打开 = 清掉标记，下次点音乐又会弹。
                            scope.launch { settingsRepository.setMusicUiTipDismissed(!enabled) }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(12.dp))

                // ---- 退出音乐时二次确认（2026-11-14：音乐设置里也能开/关）----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "退出音乐时确认",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            "返回箭头或系统返回键离开音乐页时先弹确认框。",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SquircleSwitch(
                        checkedTrackColor = palette.accent,
                        uncheckedTrackColor = palette.track,
                        thumbColor = palette.onAccent,
                        outlineColor = palette.buttonBorder,
                        checked = exitConfirm,
                        onCheckedChange = { enabled ->
                            scope.launch { developerOptions.setMusicExitConfirm(enabled) }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = palette.divider)
                Spacer(Modifier.height(12.dp))

                // ---- 缓存 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "音乐缓存",
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.text
                        )
                        Text(
                            text = when {
                                cacheBytes == null -> "正在统计…"
                                else -> "${CacheCleaner.formatSize(cacheBytes ?: 0L)}" +
                                    " · 为离线播放下载的歌曲"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textSub
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.glass)
                            .border(1.dp, palette.glassBorder, RoundedCornerShape(10.dp))
                            .bounceClick(
                                onClick = { confirmClear = true },
                                enabled = !clearing && (cacheBytes ?: 0L) > 0L
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (clearing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = palette.accent
                            )
                        } else {
                            Text(
                                text = "清理",
                                fontSize = 13.sp,
                                color = palette.text
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "清理后这些歌需要联网才能播放；收藏与最近播放不受影响。",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textMuted
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 会员音乐播放检测（2026-11-14）----
        MusicVipProbeCard(onMessage = onMessage)

        Spacer(Modifier.height(12.dp))

        // ---- 说明 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudDone,
                contentDescription = null,
                tint = palette.textMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "账号凭据只存在本机，不随系统备份离开设备",
                fontSize = 11.sp,
                color = palette.textMuted
            )
        }
    }

    if (showLoginDialog) {
        // 复用同一个扫码登录框：成功后它会切成"登录成功"提示，账号卡自动刷新。
        KugouLoginDialog(onDismiss = { showLoginDialog = false })
    }

    // 清理音乐缓存先确认（2026-11-09 需求 6）：删了要重新下载，属于不可撤销操作。
    if (confirmClear) {
        ConfirmDialog(
            title = "清理音乐缓存？",
            text = "将删除已下载的 " + CacheCleaner.formatSize(cacheBytes ?: 0L) +
                " 音频。删掉后这些歌需要联网重播，收藏与最近播放不受影响。",
            confirmLabel = "清理",
            onConfirm = {
                confirmClear = false
                clearing = true
                scope.launch {
                    val freed = CacheCleaner.clearMusic(context)
                    clearing = false
                    tick++
                    onMessage(
                        if (freed > 0) {
                            "已清除音乐缓存，释放 ${CacheCleaner.formatSize(freed)}"
                        } else {
                            "没有需要清理的音乐缓存"
                        }
                    )
                }
            },
            onDismiss = { confirmClear = false }
        )
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "退出酷狗账号",
            text = "退出后只能用公开接口取链，会员曲目可能无法播放。" +
                "收藏、最近播放与已缓存的歌曲都会保留。",
            confirmLabel = "退出登录",
            onConfirm = {
                confirmSignOut = false
                signingOut = true
                scope.launch {
                    runCatching { store.clearLogin() }
                    signingOut = false
                    onMessage("已退出酷狗账号")
                }
            },
            onDismiss = { confirmSignOut = false }
        )
    }
}

/**
 * 跳到系统的「显示在其他应用上层」授权页。
 *
 * 不同 ROM 对 `ACTION_MANAGE_OVERLAY_PERMISSION` 的处理不一致，所以带上包名 + 兜底
 * 到应用详情页——两步都不成时用户仍可自己去设置里找权限。
 */
private fun requestOverlayPermission(context: android.content.Context) {
    val appIntent = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        android.net.Uri.parse("package:${'$'}{context.packageName}")
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(appIntent) }.onFailure {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${'$'}{context.packageName}")
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
