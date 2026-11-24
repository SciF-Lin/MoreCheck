package com.morecheck.ui.settings

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.home.PREVIEW_RING_MILLIS
import com.morecheck.ui.home.rememberTimerRinger
import com.morecheck.ui.theme.Space
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 一个可选铃声：[uri] 是持久化的标识，[title] 只用于展示。 */
private data class RingtoneOption(val uri: String, val title: String)

/**
 * 「设置 → 通用 → 声音设置」。
 *
 * 三件事：倒计时结束响不响、用哪个铃声、从文件导入一个自定义铃声。
 *
 * ## 铃声来源
 *
 * - **系统预设**：`RingtoneManager` 遍历闹钟 / 通知 / 来电三类铃声；
 * - **系统默认**：`uri = null`，播放时用系统默认通知音；
 * - **从文件导入**：SAF `OpenDocument`（mime 为 `audio` 通配符），选完立刻
 *   `takePersistableUriPermission` 拿到**持久化读权限**，否则重启后 URI 就失效了。
 *
 * 不复制音频文件、不申请任何权限：系统铃声直接可读，导入的文件靠 SAF 授权。
 *
 * ## 为什么铃声列表不调 `getRingtoneUri(position)`
 *
 * 那个方法内部会 `moveToPosition`，与正在遍历的同一个 cursor 相互干扰；
 * 直接从 cursor 的 `URI_COLUMN_INDEX` / `TITLE_COLUMN_INDEX` 读更稳。
 * 另外公开 SDK 里**没有** `RingtoneManager.getRingtoneTitle(...)` 这个方法
 * （只有 `TITLE_COLUMN_INDEX` 和 `Ringtone.getTitle(context)`），所以标题取自 cursor。
 *
 * ## 本轮（需求 5）界面改了什么、为什么
 *
 * 用户反馈「优化声音设置 UI，使其更美观」。改版前的问题是按"一行一张白卡"渲染：
 * 40 多条系统铃声就是 40 多张卡，卡与卡之间 8dp 灰缝，整页被切成一片"面包片"，
 * 视线每行都要重新起跳；试听按钮是裸三角，选中态只有标题变绿 + 右侧一个勾，
 * 在灰底白卡上几乎看不出来。
 *
 * 现在：
 * 1. **同组共用一张卡**，行间用 1dp 分隔线（微信 / 系统设置的标准做法）；
 *    卡片之间留 12dp，分组的边界因此比行边界更粗一档，层次一眼可辨。
 * 2. **每行 56dp 定高**（`heightIn(min = 56.dp)`），长列表滚起来节奏均匀。
 * 3. **选中态用三重信号**：行底色淡淡一层主题色 + 标题变主题色并加粗 + 右侧勾。
 *    任何一个信号在深色模式下失效时，另外两个仍然成立（不依赖单一颜色）。
 * 4. **试听按钮做成 36dp 圆形淡底**（主题色 10% 底 + 主题色图标），
 *    试听中换成停止图标并同步把副标题改成「试听中」，避免"点了没反应"。
 * 5. 分区标题前补一个小图标，把「铃声 / 内置提示音 / 系统铃声」三块的信息层级拉开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
    viewModel: SettingsViewModel = LocalSettingsViewModel.current,
) {
    val context = LocalContext.current
    val soundEnabled by viewModel.timerSoundEnabled.collectAsStateWithLifecycle()
    val currentUri by viewModel.timerSoundUri.collectAsStateWithLifecycle()
    val currentTitle by viewModel.timerSoundTitle.collectAsStateWithLifecycle()

    // 试听用的播放器：离开本页时 DisposableEffect 会保证 stop()，3 秒后也会自己停。
    val ringer = rememberTimerRinger()

    // 正在试听的那一条（null = 没在试听）。用来切换行尾按钮的图标。
    var previewingUri by remember { mutableStateOf<String?>(null) }

    // 系统铃声查询是磁盘 IO，放到 IO 线程；cursor 由 loadSystemRingtones 内部关闭。
    val ringtones by produceState(initialValue = emptyList<RingtoneOption>(), context) {
        value = withContext(Dispatchers.IO) { loadSystemRingtones(context) }
    }

    // 内置提示音（2026-10-22 需求 13）。首次进入时在 IO 线程生成 5 个 WAV，
    // 之后只是读文件是否已存在——与系统铃声同为"URI + 标题"，选中 / 试听 / 持久化
    // 走完全同一条链路（见 [BuiltinTones] 的 KDoc）。
    val builtinTones by produceState(initialValue = emptyList<BuiltinTones.Tone>(), context) {
        value = withContext(Dispatchers.IO) { BuiltinTones.ensure(context) }
    }

    // 试听计时：3 秒后清掉状态（铃声本身由 TimerRinger 在同一时刻停掉）。
    LaunchedEffect(previewingUri) {
        if (previewingUri != null) {
            delay(PREVIEW_RING_MILLIS)
            previewingUri = null
        }
    }

    // 用 rememberUpdatedState 拿最新的 onMessage，避免 launcher 因回调变化反复重建。
    val currentOnMessage by rememberUpdatedState(onMessage)

    /** 试听 / 停止试听的统一切换：点同一条 = 停，点另一条 = 改播那一条。 */
    fun togglePreview(uri: String) {
        if (previewingUri == uri) {
            ringer.stop()
            previewingUri = null
            return
        }
        ringer.play(context, uri, PREVIEW_RING_MILLIS)
        previewingUri = uri
    }

    // 从文件导入。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 持久化读权限：不拿的话重启后这个 URI 就打不开了。
        // 部分提供者（云盘 / 某些文件管理器）根本不支持持久化，会抛 SecurityException；
        // 这里吞掉异常：拿不到也仍然能先用，只是重启后可能失效。
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.isSuccess
        val title = resolveDisplayName(context, uri)
        viewModel.setTimerSound(uri.toString(), title)
        currentOnMessage(
            if (persisted) {
                "已选择「$title」"
            } else {
                "已选择「$title」（该来源不支持长期授权，重启后可能失效）"
            }
        )
    }

    // 导入进来的铃声不在系统铃声列表里，单独补一行，
    // 否则用户选完文件回来后「选中态」会凭空消失。
    val importedUri = currentUri?.takeIf { uri ->
        ringtones.none { it.uri == uri } && builtinTones.none { it.uri == uri }
    }
    val importedTitle = currentTitle ?: "已导入的铃声"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("声音设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.xl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            // ---- 总开关 ----------------------------------------------------
            item(key = "sound-master") {
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp)
                            .padding(horizontal = Space.lg, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 图标只是"这一行是声音类设置"的视觉锚点，不参与交互。
                        SettingsGlyph(Icons.Rounded.NotificationsActive)
                        Spacer(Modifier.size(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "倒计时结束响铃",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                // strongTextColor：浅色模式纯黑，深色模式沿用 onSurface。
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                text = if (soundEnabled) {
                                    "到点会响起下面选中的铃声（最长 30 秒后自动停止）"
                                } else {
                                    "关闭后到点只震动并提示，不播放声音"
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { viewModel.setTimerSoundEnabled(it) }
                        )
                    }
                }
            }

            // ---- 当前选用 ----------------------------------------------------
            // 改版新增：先说"现在用的是哪一首"，再看列表。
            // 40 多条铃声里靠"哪一行变绿"去找当前值，本来就要滚很久。
            item(key = "sound-current") {
                val nowTitle = when {
                    currentUri == null -> "系统默认"
                    importedUri != null -> importedTitle
                    else -> currentTitle
                        ?: (builtinTones.firstOrNull { it.uri == currentUri }?.title)
                        ?: ringtones.firstOrNull { it.uri == currentUri }?.title
                        ?: "已选铃声"
                }
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .padding(horizontal = Space.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SettingsGlyph(Icons.Rounded.MusicNote)
                        Spacer(Modifier.size(Space.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "当前铃声",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = nowTitle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        // 直接试听当前铃声 —— 选完不用再去列表里找那一行。
                        PreviewButton(
                            previewing = false,
                            enabled = soundEnabled,
                            onClick = {
                                ringer.play(context, currentUri, PREVIEW_RING_MILLIS)
                                previewingUri = null
                                                    }
                        )
                    }
                }
            }

            // ---- 铃声来源 ----------------------------------------------------
            item(key = "sound-src-title") {
                SectionTitle("铃声来源", Icons.Rounded.LibraryMusic)
            }
            item(key = "sound-src") {
                SettingsGroup {
                    SoundRow(
                        title = "系统默认",
                        subtitle = if (soundEnabled) "跟随手机的默认通知音" else "当前已关闭响铃",
                        selected = currentUri == null,
                        previewing = false,
                        onClick = {
                            // uri = null 就是「系统默认」，标题一并清掉。
                            ringer.stop()
                            previewingUri = null
                            viewModel.setTimerSound(null, null)
                        },
                        onPreview = {
                            ringer.play(context, null, PREVIEW_RING_MILLIS)
                            previewingUri = null
                                            }
                    )
                    RowDivider()
                    SoundRow(
                        title = "从文件导入",
                        subtitle = "支持常见音频格式，可选用本地音乐",
                        selected = false,
                        previewing = false,
                        leadingIcon = Icons.Rounded.FileOpen,
                        onClick = {
                            // OpenDocument 只接受 mime 类型数组；audio/* 覆盖绝大多数音频文件。
                            importLauncher.launch(arrayOf("audio/*"))
                        }
                    )
                    // 导入的铃声如果既不在内置里也不在系统列表里，就补一行在导入入口下面：
                    // 位置紧跟来源、和它的"出身"在一起，比塞到列表末尾更容易找到。
                    if (importedUri != null) {
                        RowDivider()
                        SoundRow(
                            title = importedTitle,
                            subtitle = "来自本地文件",
                            selected = currentUri == importedUri,
                            previewing = previewingUri == importedUri,
                            onClick = { viewModel.setTimerSound(importedUri, importedTitle) },
                            onPreview = { togglePreview(importedUri) }
                        )
                    }
                }
            }

            // ---- 内置提示音（2026-10-22 需求 13）----------------------------
            //
            // 单独一段而不是混进「系统铃声」：它们的来源不同（本应用合成 vs 系统预置），
            // 混在一起时用户会以为这也是手机自带的，换机后"找不到"就变成疑惑。
            if (builtinTones.isNotEmpty()) {
                item(key = "sound-builtin-title") {
                    SectionTitle("内置提示音", Icons.Rounded.GraphicEq)
                }
                item(key = "sound-builtin") {
                    SettingsGroup {
                        builtinTones.forEachIndexed { index, tone ->
                            if (index > 0) RowDivider()
                            SoundRow(
                                title = tone.title,
                                subtitle = if (previewingUri == tone.uri) "试听中…" else "应用内置，无需导入",
                                selected = currentUri == tone.uri,
                                previewing = previewingUri == tone.uri,
                                onClick = {
                                    ringer.stop()
                                    previewingUri = null
                                    viewModel.setTimerSound(tone.uri, tone.title)
                                },
                                onPreview = { togglePreview(tone.uri) }
                            )
                        }
                    }
                }
            }

            // ---- 系统预设铃声列表 -------------------------------------------
            item(key = "sound-system-title") {
                SectionTitle(
                    text = if (ringtones.isEmpty()) "系统铃声" else "系统铃声 · ${ringtones.size} 首",
                    icon = Icons.Rounded.MusicNote
                )
            }
            if (ringtones.isEmpty()) {
                item(key = "sound-system-empty") {
                    SettingsGroup {
                        Text(
                            text = "没有读到系统铃声，可以用上面的「从文件导入」选一个。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.lg)
                        )
                    }
                }
            } else {
                // 40+ 行放进**同一张卡**（同一 item），行间 1dp 分隔线：
                // 一行一张卡时每行都要额外吃掉 8dp 灰缝，且每张卡都有自己的圆角，
                // 整页读起来是碎片。同一张卡里 40 行的测量开销远小于 40 个 Lazy 项的回收代价。
                item(key = "sound-system") {
                    SettingsGroup {
                        ringtones.forEachIndexed { index, option ->
                            if (index > 0) RowDivider()
                            SoundRow(
                                title = option.title,
                                subtitle = if (previewingUri == option.uri) "试听中…" else null,
                                selected = currentUri == option.uri,
                                previewing = previewingUri == option.uri,
                                onClick = {
                                    // 选中新铃声时顺手停掉试听，避免「选了 A 还在响 B」。
                                    ringer.stop()
                                    previewingUri = null
                                    viewModel.setTimerSound(option.uri, option.title)
                                },
                                onPreview = { togglePreview(option.uri) }
                            )
                        }
                    }
                }
            }

            item(key = "sound-footer") {
                Text(
                    text = "响铃在倒计时结束时触发，最多响 30 秒后自动停止；" +
                        "关闭上面的开关后不播放声音，但震动与提示不受影响。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xs)
                )
            }
        }
    }
}

/** 一组设置行共用的白卡：圆角 16dp、无描边，与全站「灰底白卡」一致。 */
@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column { content() }
    }
}

/** 组内行之间的 1dp 分隔线，左端与文字对齐（不顶到卡片边缘）。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 分区标题：小图标 + 文字，比纯灰字更容易在长列表里定位。 */
@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.xs, top = Space.xs, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/** 卡片内左侧的小图标（28dp 圆形淡底 + 18dp 图标），给整行一个视觉锚点。 */
@Composable
private fun SettingsGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp)
        )
    }
}

/**
 * 单个铃声行：选中指示 + 标题 / 副标题 + 行尾试听按钮。
 *
 * 点文字区 = 选中；行尾按钮 = 试听 3 秒，两者互不干扰（点击只绑在文字区上）。
 *
 * 选中态**三重信号**（见文件头注释）：底色 / 标题颜色与字重 / 右侧勾。
 */
@Composable
private fun SoundRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    previewing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    // 选中底色与标题色都走动画：切换选中时不会"啪"一下变色，与全站其他开关一致。
    val rowBackground by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
        } else {
            Color.Transparent
        },
        label = "soundRowBackground"
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.strongTextColor
        },
        label = "soundRowTitleColor"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground)
            // 整行可点：改版前只有文字区可点，行内空白处点了没反应，是"手感不一致"的常见抱怨。
            // 试听按钮自己会消费点击（IconButton），不会连带选中。
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = Space.lg, end = Space.sm, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧图标位：固定 20dp 宽，有没有图标都不影响文字起点（列表看起来才齐）。
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                selected -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                leadingIcon != null -> Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.size(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                // 选中加粗一档：光靠颜色在小字号上不够明显（尤其深色模式）。
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = titleColor
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
        if (onPreview != null) {
            PreviewButton(previewing = previewing, onClick = onPreview)
        }
    }
}

/**
 * 试听 / 停止按钮：36dp 圆形淡主题色底 + 主题色图标。
 *
 * 改版前是裸 `IconButton`（一个灰色三角），既是"看起来不像能按"，
 * 也与本页其它「圆形淡底图标」的语汇（[SettingsGlyph]）不统一。
 * 试听中换成实心停止图标 + 底色提亮一档，用两组信号表示"正在响"。
 */
@Composable
private fun PreviewButton(
    previewing: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val bg by animateColorAsState(
        targetValue = if (previewing) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        },
        label = "previewButtonBackground"
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            // 与全站列表行一致：按压缩放 0.96，不用水波纹（圆形小按钮上会出现方形涟漪）。
            .pressScale(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            contentDescription = if (previewing) "停止试听" else "试听",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 列出系统可选铃声（闹钟 + 通知 + 来电）。
 *
 * `cursor` 一定要 `use {}` 关掉：它背后是 ContentProvider 的游标，
 * 不关会一直占着窗口，查得多了会抛 CursorWindow 泄漏类异常。
 * 任何一步失败都返回空列表（界面会提示改用「从文件导入」），不让设置页崩掉。
 */
private fun loadSystemRingtones(context: Context): List<RingtoneOption> = runCatching {
    val manager = RingtoneManager(context.applicationContext)
    // 三类一起列出来：闹钟铃声往往更响亮，作为倒计时提示更合适。
    manager.setType(
        RingtoneManager.TYPE_ALARM or
            RingtoneManager.TYPE_NOTIFICATION or
            RingtoneManager.TYPE_RINGTONE
    )
    val result = mutableListOf<RingtoneOption>()
    // 去重：个别机型会把同一首曲子同时挂在多个类型下。
    val seen = mutableSetOf<String>()
    manager.cursor?.use { cursor ->
        // URI_COLUMN_INDEX / TITLE_COLUMN_INDEX 本身就是**列下标**（不是列名），
        // 所以直接喂给 getString，不要再过一次 getColumnIndex。
        val uriIndex = RingtoneManager.URI_COLUMN_INDEX
        val titleIndex = RingtoneManager.TITLE_COLUMN_INDEX
        if (uriIndex >= cursor.columnCount || titleIndex >= cursor.columnCount) return@use
        while (cursor.moveToNext()) {
            // getString 在列值为 null 时会返回 null，两列都要判空。
            val uri = cursor.getString(uriIndex)
            val title = cursor.getString(titleIndex)
            if (uri.isNullOrBlank() || title.isNullOrBlank()) continue
            if (!seen.add(uri)) continue
            result += RingtoneOption(uri = uri, title = title)
        }
    }
    result.sortedBy { it.title.lowercase() }
}.getOrDefault(emptyList())

/**
 * 取导入文件的显示名：先问 `OpenableColumns.DISPLAY_NAME`（SAF 标准列），
 * 取不到就退到 URI 的最后一段路径，再取不到给一个固定文案。
 */
private fun resolveDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "已导入的铃声"
}
