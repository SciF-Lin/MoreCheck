package com.morecheck.ui.mine

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.morecheck.util.CacheCleaner
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.auth.authPressable
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.theme.AccentColor
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.AppPermissions
import com.morecheck.util.ThemeMode

/** 深色模式三选项，顺序即分段按钮从左到右的顺序。 */
private val ThemeOptions = listOf(
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
    ThemeMode.SYSTEM to "跟随系统"
)

/**
 * 设置子页。
 *
 * 承载：
 * 1. 账号与数据入口（登录 / 数据备份）；
 * 2. **外观**——深色模式三选一 + **主题色选择**（原先的「动态配色」开关已移除，
 *    改用固定的自选主题色，彻底摆脱跟随系统壁纸取色的「谷歌样式」）；
 * 3. **通用**——自启动开关（含自启动引导）+ 「权限管理」入口（摄像头 / 定位 / 通知）
 *    + 「声音设置」入口 + 「清除缓存」入口（v1.1.79）。
 *
 * 全部设置都通过 [SettingsViewModel] 写入 DataStore 本地保存，切换即时生效。
 *
 * ## 「清除缓存」为什么单独一行、还要先算体积
 *
 * 它不是一个"开 / 关"设置，而是一个**一次性动作**，且后果需要用户确认
 * （删了就要重新加载网页、重新生图）。行副标题显示当前缓存体积，
 * 点开先弹确认框、确认后清、再用全局 Snackbar 报出释放了多少——
 * 三步都能让用户看见"确实发生了"，而不是点一下没反应。
 * 清理范围见 [com.morecheck.util.CacheCleaner]：只清缓存目录，
 * **不碰**打卡记录 / 设置 / 对话 / 课表进度等用户数据。
 *
 * ## 自启动为什么是「提示 + 引导」而不是「查权限」
 *
 * Android **没有**任何标准 API 能查询「本应用是否允许自启动」，各家 ROM 都是私有实现
 * （详见 [AppPermissions.openAutoStartSettings] 的注释）。所以这里的做法只能是：
 * 用户把开关打到开时**始终**弹一个引导对话框，并提供「去设置」把它送到厂商自启动页，
 * 界面上一律不显示「已获得自启动权限」这类状态——那是查不出来的，显示了就是骗用户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSound: () -> Unit,
    /**
     * 进入「数据与同步」页（2026-11-08 需求 5：原「同步管理」与「数据备份」两个入口合并）。
     *
     * 参数名沿用 `onOpenSyncManage`：调用点已经接好，改名只会制造无意义的 diff。
     */
    onOpenSyncManage: () -> Unit = {},
    /** 进入「打卡提醒增强」二级页（2026-11-08 需求 5：从本页内联分区搬出）。 */
    onOpenReminderEnhance: () -> Unit = {},
    /** 进入「个性化」二级页（同上）。 */
    onOpenPersonalization: () -> Unit = {},
    /** 进入「音乐账号」二级页（2026-11-08 需求 2：与音乐页点头像同一页）。 */
    onOpenMusicSettings: () -> Unit = {},
    /** 进入「锁屏密码」页（2026-10-25 需求：设置 → 安全）。 */
    onOpenAppLock: () -> Unit = {},
    /** 进入「设备信息查询」页（2026-10-25 需求：自开发者选项迁入）。 */
    onOpenUiInspector: () -> Unit = {},
    /** 进入「错误日志」页（2026-11-14 需求 3）。 */
    onOpenErrorLog: () -> Unit = {},
    /** 全局 Snackbar 出口（「清除缓存」完成后报释放量）。 */
    onMessage: (String) -> Unit = {},
    // 复用 MainActivity 创建的那一份，保证与主题使用同一处状态。
    // 若在这里用 viewModel(...) 自建，会拿到第二份实例，开关就会「关不掉」。
    viewModel: SettingsViewModel = LocalSettingsViewModel.current
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeAccent by viewModel.themeAccent.collectAsStateWithLifecycle()
    val autoStartOnBoot by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()
    // 权限开关只用于在入口副标题里显示「已开启 N 项」，让用户不进二级页也能看到状态。
    val cameraEnabled by viewModel.cameraEnabled.collectAsStateWithLifecycle()
    val locationEnabled by viewModel.locationEnabled.collectAsStateWithLifecycle()
    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()

    val enabledPermissionCount = listOf(cameraEnabled, locationEnabled, notificationEnabled)
        .count { it }

    // 声音设置只用副标题汇总「响不响 / 用哪个铃声」。
    // 音乐后台播放（设置 → 音乐）：默认开，关掉退回"页内播放、离页即停"。
    val musicBackgroundPlayback by viewModel.musicBackgroundPlayback.collectAsStateWithLifecycle()

    val timerSoundEnabled by viewModel.timerSoundEnabled.collectAsStateWithLifecycle()
    val timerSoundTitle by viewModel.timerSoundTitle.collectAsStateWithLifecycle()

    val currentAccent = AccentColor.fromId(themeAccent)

    val context = LocalContext.current

    // 自启动引导对话框是否可见。
    //
    // 用 remember（不是 rememberSaveable）保存「这次是否要弹」是刻意的：
    // 它描述的是「刚刚这次点击需要引导」这个瞬时意图，旋转屏幕后重新弹一次没意义，
    // 反而会在用户已经去过设置页之后又冒出来。
    var showAutoStartGuide by remember { mutableStateOf(false) }

    // ── 「清除缓存」状态（v1.1.79）─────────────────────────────────────────
    //
    // sizeTick 只是"重新统计一次"的触发器：清理完成后 +1 → 下面的 produceState
    // 重新读一遍缓存体积，行副标题立刻从「12.3 MB」变成「0 B」。
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    // 音乐缓存单独统计（下载的歌曲，见 CacheCleaner 的说明）：两类分开显示、分开清。
    var musicCacheSize by remember { mutableStateOf<Long?>(null) }
    var sizeTick by remember { mutableStateOf(0) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var clearingOther by remember { mutableStateOf(false) }
    var clearingMusic by remember { mutableStateOf(false) }
    /** 2026-11-09：清理音乐缓存改为二次确认，先问再删。 */
    var confirmClearMusic by remember { mutableStateOf(false) }
    val clearing = clearingOther || clearingMusic
    val scope = rememberCoroutineScope()

    // 统计缓存体积：两类都在 IO 线程上算（目录遍历），主线程只拿结果。
    LaunchedEffect(sizeTick) {
        cacheSize = withContext(Dispatchers.IO) { CacheCleaner.totalBytes(context) }
        musicCacheSize = withContext(Dispatchers.IO) { CacheCleaner.musicBytes(context) }
    }

    // 自启动引导对话框。
    //
    // 为什么用对话框而不是 Snackbar：这是一个**要用户去别的页面完成操作**的引导，
    // 需要停留到用户做出选择（去设置 / 取消）为止；Snackbar 几秒就消失，
    // 用户还没看清就没了，等于没提示。
    if (showAutoStartGuide) {
        AlertDialog(
            onDismissRequest = { showAutoStartGuide = false },
            title = { Text("允许自启动") },
            text = {
                Text(
                    "手机系统没有提供查询「是否允许自启动」的接口，请在系统设置里手动允许" +
                        "本应用自启动，否则开机后每日提醒可能不会恢复。\n\n" +
                        "开关会先保持开启——它记录的是你的意愿；能否真正生效取决于系统设置。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutoStartGuide = false
                        // 直达厂商自启动管理页；全部不存在则自动回退应用详情页。
                        AppPermissions.openAutoStartSettings(context)
                    }
                ) {
                    Text("去设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAutoStartGuide = false }) { Text("取消") }
            }
        )
    }

    // 「清除缓存」二次确认（v1.1.79；2026-11-08 改为**两类分开清**）。
    //
    // 为什么必须分两类：音乐缓存是用户主动下载、用于离线播放的歌曲，
    // 与"网页缓存可以随时重建"不是一回事。混在一个按钮里清，会出现
    // "我只想清网页缓存，结果下的歌全没了"。
    //
    // 为什么仍然要确认：两类都是"删了要重新下载 / 重新加载"的动作，
    // 但**都不动用户数据**（打卡记录、设置、对话、课表进度），文案里要写明。
    if (showClearCacheDialog) {
        val otherText = CacheCleaner.formatSize(cacheSize ?: 0L)
        val musicText = CacheCleaner.formatSize(musicCacheSize ?: 0L)
        AlertDialog(
            onDismissRequest = { if (!clearing) showClearCacheDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("清除缓存", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    // ---- 其他缓存 ----
                    Text(
                        text = "其他缓存 · $otherText",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CleanCacheBullet("包含：网页缓存、图片临时文件、缩略图")
                    CleanCacheBullet("清理后网页首次打开会稍慢，属正常现象")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            clearingOther = true
                            scope.launch {
                                val freed = CacheCleaner.clearAll(context)
                                clearingOther = false
                                sizeTick++
                                onMessage(
                                    if (freed > 0) "已清除其他缓存，释放 ${CacheCleaner.formatSize(freed)}"
                                    else "其他缓存已是最干净的状态"
                                )
                            }
                        },
                        enabled = !clearing && (cacheSize ?: 0L) > 0L,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (clearingOther) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (clearingOther) "正在清理" else "清理其他缓存")
                    }

                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(14.dp))

                    // ---- 音乐缓存 ----
                    Text(
                        text = "音乐缓存 · $musicText",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    CleanCacheBullet("包含：为离线播放下载的歌曲")
                    CleanCacheBullet("清理后这些歌需要联网才能播放（收藏与最近播放不受影响）")
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        // 2026-11-09 需求 6：先确认再删（缓存删掉要重新下载，属于不可撤销操作）。
                        onClick = { confirmClearMusic = true },
                        enabled = !clearing && (musicCacheSize ?: 0L) > 0L,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (clearingMusic) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (clearingMusic) "正在清理" else "清理音乐缓存")
                    }

                    if (confirmClearMusic) {
                        ConfirmDialog(
                            title = "清理音乐缓存？",
                            text = "将删除已下载的 ${musicText} 音频，共 " +
                                "${CacheCleaner.formatSize(musicCacheSize ?: 0L)}。" +
                                "缓存删掉后这些歌需要联网重播，收藏与最近播放不受影响。",
                            confirmLabel = "清理",
                            onConfirm = {
                                confirmClearMusic = false
                                clearingMusic = true
                                scope.launch {
                                    val freed = CacheCleaner.clearMusic(context)
                                    clearingMusic = false
                                    sizeTick++
                                    onMessage(
                                        if (freed > 0) {
                                            "已清除音乐缓存，释放 ${CacheCleaner.formatSize(freed)}"
                                        } else {
                                            "没有需要清理的音乐缓存"
                                        }
                                    )
                                }
                            },
                            onDismiss = { confirmClearMusic = false }
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "两类都不影响：打卡记录、任务、设置、对话与课表进度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            // 两个"清理"按钮都在正文里（各自带体积与后果说明），底部只留一个关闭，
            // 避免"三个按钮里挑一个"的误触。
            confirmButton = {
                TextButton(onClick = { showClearCacheDialog = false }, enabled = !clearing) {
                    Text("关闭")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ================================================================
            // 2026-11-14 需求 5：按「账号 → 外观 → 通用 → 功能 → 安全 → 存储 →
            // 问题与诊断」七段重排，每段一个分区标题、每条入口一个前置图标。
            //
            // 改版前的问题是**分组靠卡片，卡片没有名字**：七张卡一字排开，
            // 用户只能靠读副标题猜哪张是"数据"、哪张是"外观"。现在每段先给一行
            // 小标题，卡片才有归属；同段内的多项用分隔线连成一张卡，
            // 视觉上"一段一卡"，比原来七八张孤立卡片更容易扫。
            // ================================================================

            // ---- 账号与数据 --------------------------------------------------
            SettingsSectionHeader("账号与数据")
            Card(Modifier.fillMaxWidth()) {
                SettingsEntryRow(
                    title = "同步与备份",
                    subtitle = "云同步账号与开关、本机导出恢复、CSV 与周报",
                    leadingIcon = Icons.Rounded.CloudSync,
                    onClick = onOpenSyncManage
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---- 外观 --------------------------------------------------------
            SettingsSectionHeader("外观")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "深色模式",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.strongTextColor
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeOptions.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeOptions.size
                                ),
                                icon = {
                                    SegmentedButtonDefaults.Icon(active = themeMode == mode)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        "主题色",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.strongTextColor
                    )
                    Text(
                        "影响导航栏、打卡按钮、时间与图标颜色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.height(12.dp))
                    AccentPicker(
                        selected = currentAccent,
                        onSelect = { viewModel.setThemeAccent(it.id) }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 通用 --------------------------------------------------------
            SettingsSectionHeader("通用")
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "自启动",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                "开机后自动恢复每日提醒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = autoStartOnBoot,
                            onCheckedChange = { enabled ->
                                viewModel.setAutoStartOnBoot(enabled)
                                // 只在「打开」时引导：关闭不需要任何系统操作。
                                // Android 查不到"是否已允许自启动"，所以这里无条件弹引导，
                                // 详见本页 KDoc 与 AppPermissions 的说明。
                                if (enabled) showAutoStartGuide = true
                            }
                        )
                    }

                    SettingsRowDivider()

                    SettingsEntryRow(
                        title = "权限管理",
                        // 副标题只汇报已开启项数，三个权限的名字在二级页里已写全。
                        subtitle = "已开启 $enabledPermissionCount 项",
                        leadingIcon = Icons.Rounded.VerifiedUser,
                        onClick = onOpenPermissions
                    )

                    SettingsRowDivider()

                    SettingsEntryRow(
                        title = "声音设置",
                        subtitle = if (timerSoundEnabled) {
                            timerSoundTitle?.takeIf { it.isNotBlank() }?.let { "计时结束响铃：$it" }
                                ?: "计时结束响铃：系统默认"
                        } else {
                            "计时结束不响铃"
                        },
                        leadingIcon = Icons.Rounded.VolumeUp,
                        onClick = onOpenSound
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 功能与内容 --------------------------------------------------
            SettingsSectionHeader("功能与内容")
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsEntryRow(
                        title = "音乐",
                        subtitle = if (musicBackgroundPlayback) {
                            "账号登录、进出确认、后台播放（已开启）、缓存"
                        } else {
                            "账号登录、进出确认、后台播放（已关闭）、缓存"
                        },
                        leadingIcon = Icons.Rounded.Headphones,
                        onClick = onOpenMusicSettings
                    )

                    SettingsRowDivider()

                    SettingsEntryRow(
                        title = "打卡提醒增强",
                        subtitle = "夜间静默时段与漏打卡补推",
                        leadingIcon = Icons.Rounded.NotificationsActive,
                        onClick = onOpenReminderEnhance
                    )

                    SettingsRowDivider()

                    SettingsEntryRow(
                        title = "个性化",
                        subtitle = "检查更新去处、日志入口、日期与开屏偏好",
                        leadingIcon = Icons.Rounded.Palette,
                        onClick = onOpenPersonalization
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 安全 --------------------------------------------------------
            SettingsSectionHeader("安全")
            Card(Modifier.fillMaxWidth()) {
                SettingsEntryRow(
                    title = "锁屏密码",
                    subtitle = "启用应用时验证",
                    leadingIcon = Icons.Filled.Lock,
                    onClick = onOpenAppLock
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---- 存储 --------------------------------------------------------
            SettingsSectionHeader("存储")
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(onClick = { showClearCacheDialog = true })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "清除缓存",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.strongTextColor
                        )
                        Text(
                            text = if (cacheSize == null || musicCacheSize == null) {
                                "正在统计…"
                            } else {
                                "其他 ${CacheCleaner.formatSize(cacheSize ?: 0L)}" +
                                    " · 音乐 ${CacheCleaner.formatSize(musicCacheSize ?: 0L)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- 问题与诊断（2026-11-14 需求 3 / 5）---------------------------
            //
            // 这两个入口都是"出了问题才来"的排查工具，与日常设置不属于同一层，
            // 所以合到同一段并排在页面末尾，普通用户不会被它们打扰。
            SettingsSectionHeader("问题与诊断")
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsEntryRow(
                        title = "错误日志",
                        subtitle = "崩溃与报错记录，可复制给开发者",
                        leadingIcon = Icons.Rounded.BugReport,
                        onClick = onOpenErrorLog
                    )

                    SettingsRowDivider()

                    SettingsEntryRow(
                        title = "设备信息查询",
                        subtitle = "机型、系统、版本与 UI 规格，点哪条复制哪条",
                        leadingIcon = Icons.Filled.Info,
                        onClick = onOpenUiInspector
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 一段设置的标题（2026-11-14 需求 5）。
 *
 * 只在小标题这一层用 `onSurface` 压暗 + 小号字：它要与卡片内的正文标题
 * 明确区分开（正文用 [strongTextColor] 加深），否则"段落名"和"条目名"
 * 会看起来同级，分组就白做了。
 */
@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 同段卡片内的分隔线：与项目其它设置页同一口径（左侧留 16dp 缩进）。 */
@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 设置页里的一条「入口行」：标题 + 副标题 + 右箭头。
 *
 * 只在本页内部用（同步管理 / 数据备份两条），因此不放到 `ui/common`。
 */
@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    /** 行首图标；null = 不加（保持原样，避免动到本页其它入口）。 */
    leadingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 统一按压反馈（缩放 0.96 + 变淡），与本页其它入口同一套。
            .pressScale(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 「清除缓存」弹窗里的一条说明（圆点 + 文字）。
 *
 * 单独抽出来只是为了让三条的圆点严格对齐——逐条手写 `Row` 时，
 * 第一条带"会清除："、第三条带括号，很容易各自缩进不同。
 */
@Composable
private fun CleanCacheBullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
        )
    }
}

/**
 * 主题色选择器：**每行 4 个**胶囊，共 8 色。
 *
 * 单个胶囊 = 左侧实心圆点（该颜色本身）+ 右侧颜色名称：
 * 底色取该颜色的浅色调、描边取同色系深色调；**选中项的描边更粗、更深**。
 * 每行用 `weight(1f)` 均分宽度，保证任何屏宽下都严格 4 个一行且不溢出。
 */
@Composable
private fun AccentPicker(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentColor.entries.chunked(4).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColors.forEach { accent ->
                    AccentChip(
                        accent = accent,
                        selected = accent == selected,
                        onClick = { onSelect(accent) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 单个主题色胶囊。 */
@Composable
private fun AccentChip(
    accent: AccentColor,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(50)

    // 「未选中 = 浅底深字 / 选中 = 实色底白字」，两种状态用颜色过渡而不是瞬变。
    //
    // 早先的实现里选中与未选中的**背景完全相同**（都是 seed 掺 80% 白），
    // 只有描边粗细差 1dp，而文字恒为白色——浅底白字基本看不清，
    // 既分不清选中状态，也牺牲了可读性。
    val containerColor by animateColorAsState(
        targetValue = if (selected) accent.seed else lerp(accent.seed, Color.White, 0.82f),
        animationSpec = tween(ACCENT_ANIM_MS),
        label = "accentChipContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else lerp(accent.seed, Color.Black, 0.55f),
        animationSpec = tween(ACCENT_ANIM_MS),
        label = "accentChipContent"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            accent.seed
        } else {
            lerp(accent.seed, Color.Black, 0.16f)
        },
        animationSpec = tween(ACCENT_ANIM_MS),
        label = "accentChipBorder"
    )
    val dotColor by animateColorAsState(
        // 选中时底已实色，圆点要反过来用白色才看得见。
        targetValue = if (selected) Color.White else accent.seed,
        animationSpec = tween(ACCENT_ANIM_MS),
        label = "accentChipDot"
    )

    Row(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = shape
            )
            // 复用登录/注册页那套「按压缩放 + 触觉反馈」，保证全应用的点击手感一致。
            //
            // 这里把回弹调得更「弹」：胶囊偏小（4 个一行），0.94 的默认缩放几乎看不出，
            // 改成 0.90。`bouncy = true`（默认）用 MediumBouncy 弹簧，松手有明显回弹。
            .authPressable(onClick = onClick, pressedScale = 0.90f)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = accent.label,
            fontSize = 13.sp,
            color = contentColor,
            maxLines = 1
        )
    }
}

/** 主题色胶囊的颜色过渡时长。与全应用二级页推入（280ms）同量级，但不至于拖沓。 */
private const val ACCENT_ANIM_MS = 180
