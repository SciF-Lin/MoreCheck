package com.morecheck.ui.settings

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.home.PREVIEW_RING_MILLIS
import com.morecheck.ui.home.rememberTimerRinger
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
        currentOnMessage("试听中，3 秒后自动停止")
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ---- 总开关 ----------------------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "倒计时结束响铃",
                                style = MaterialTheme.typography.bodyLarge,
                                // 用项目既有的 strongTextColor 加深：浅色模式是纯黑
                                // （比 onSurface 的 #191919 更深），深色模式自动沿用 onSurface，
                                // 不会变成深色卡片上的黑字。不新造颜色常量。
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                text = if (soundEnabled) {
                                    "到点会响起下面选中的铃声（最长 30 秒后自动停止）"
                                } else {
                                    "关闭后到点只震动并提示，不播放声音"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        Switch(
                            checked = soundEnabled,
                            onCheckedChange = { viewModel.setTimerSoundEnabled(it) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- 系统默认 + 从文件导入 --------------------------------------
            item { SectionTitle("铃声") }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        RingtoneRow(
                            title = "系统默认",
                            subtitle = "跟随手机的默认通知音",
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
                                currentOnMessage("试听中，3 秒后自动停止")
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        RingtoneRow(
                            title = "从文件导入",
                            subtitle = "支持常见音频格式，可选用本地音乐作为铃声",
                            selected = false,
                            previewing = false,
                            leadingImportIcon = true,
                            onClick = {
                                // OpenDocument 只接受 mime 类型数组；audio/* 覆盖绝大多数音频文件。
                                importLauncher.launch(arrayOf("audio/*"))
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 导入进来的铃声不在系统铃声列表里，单独补一行，
            // 否则用户选完文件回来后「选中态」会凭空消失。
            val importedUri = currentUri?.takeIf { uri ->
                ringtones.none { it.uri == uri } && builtinTones.none { it.uri == uri }
            }
            if (importedUri != null) {
                val importedTitle = currentTitle ?: "已导入的铃声"
                item { SectionTitle("已导入") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        RingtoneRow(
                            title = importedTitle,
                            subtitle = "来自本地文件",
                            selected = true,
                            previewing = previewingUri == importedUri,
                            onClick = { viewModel.setTimerSound(importedUri, importedTitle) },
                            onPreview = { togglePreview(importedUri) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ---- 内置提示音（2026-10-22 需求 13）----------------------------
            //
            // 单独一段而不是混进「系统铃声」：它们的来源不同（本应用合成 vs 系统预置），
            // 混在一起时用户会以为这也是手机自带的，换机后"找不到"就变成疑惑。
            if (builtinTones.isNotEmpty()) {
                item { SectionTitle("内置提示音") }
                items(builtinTones, key = { it.uri }) { tone ->
                    Card(Modifier.fillMaxWidth()) {
                        RingtoneRow(
                            title = tone.title,
                            subtitle = "应用内置，无需导入",
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
                    Spacer(Modifier.height(8.dp))
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            // ---- 系统预设铃声列表 -------------------------------------------
            item { SectionTitle("系统铃声") }
            if (ringtones.isEmpty()) {
                item {
                    Text(
                        text = "没有读到系统铃声，可以用上面的「从文件导入」选一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else {
                // key 用 uri：列表项身份稳定，选中态切换时不会错位。
                items(ringtones, key = { it.uri }) { option ->
                    Card(Modifier.fillMaxWidth()) {
                        RingtoneRow(
                            title = option.title,
                            subtitle = null,
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
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "响铃在倒计时结束时触发，最多响 30 秒后自动停止；" +
                        "关闭上面的开关后不播放声音，但震动与提示不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 分区小标题。 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        // 小节标题改用 onSurface 压暗：原来是 onSurfaceVariant，浅色模式下偏灰。
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 单行铃声：标题 + 可选副标题 + 行尾「试听」按钮 + 选中勾。
 *
 * 点文字区 = 选中；行尾按钮 = 试听 3 秒，两者互不干扰。
 */
@Composable
private fun RingtoneRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    previewing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null,
    leadingImportIcon: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingImportIcon) {
            Icon(
                imageVector = Icons.Filled.FileOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(12.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // 点击只绑在文字区，右侧的试听按钮才不会连带触发选中。
                // v1.1.72：改用统一按压反馈（缩放 0.96 + 变淡）替换裸 clickable。
                // `pressScale` 在前、`clip` 在后：缩放层内侧裁剪，圆角不在中间帧上劣化。
                .pressScale(onClick = onClick)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    // 未选中项也走 strongTextColor（浅色模式纯黑），与设置页标题同口径。
                    MaterialTheme.strongTextColor
                }
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onPreview != null) {
            IconButton(onClick = onPreview) {
                Icon(
                    imageVector = if (previewing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (previewing) "停止试听" else "试听",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 选中勾 / 同宽占位：行尾宽度一致，切换选中时图标不会左右横跳。
        Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
