package com.morecheck.ui.mine

import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.auth.authPressable
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
    /** 进入「同步管理」页（2026-10-22 需求 7：从「我的」首页搬来）。 */
    onOpenSyncManage: () -> Unit = {},
    /** 进入「数据备份」页（2026-10-22 需求 8：从「我的」首页搬来）。 */
    onOpenBackup: () -> Unit = {},
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
    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()

    val enabledPermissionCount = listOf(cameraEnabled, notificationEnabled)
        .count { it }

    // 声音设置只用副标题汇总「响不响 / 用哪个铃声」。
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
    var sizeTick by remember { mutableStateOf(0) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 统计缓存体积：DISK 线上去做（目录遍历），主线程只拿结果。
    LaunchedEffect(sizeTick) {
        cacheSize = withContext(Dispatchers.IO) { CacheCleaner.totalBytes(context) }
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

    // 「清除缓存」二次确认（v1.1.79）。
    //
    // 为什么必须确认：清理会丢掉网页缓存与图片临时文件（下次要重新加载），
    // 属于"不可撤销"的动作；但**不动任何用户数据**，所以确认文案里明确写清
    // 「打卡记录与设置不受影响」，避免用户误以为会清账号数据而不敢点。
    if (showClearCacheDialog) {
        val sizeText = CacheCleaner.formatSize(cacheSize ?: 0L)
        AlertDialog(
            onDismissRequest = { if (!clearing) showClearCacheDialog = false },
            // ---- 标题：图标 + 标题，比纯文字更像"一次操作"而不是"一条通知" ----
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
            // ---- 正文：先给结论（清多少），再分「会清 / 不会清」两段 ----
            //
            // 旧版是一段连着的长句（"将清除约 X 的缓存……打卡记录、任务、设置与对话记录
            // 都不会被删除，无需担心。清理后网页首次加载会稍慢一些。"），
            // 用户反馈读起来像在"劝退"。现在拆成三段 + 圆点列表，
            // 让"能安全清"和"会慢一点"这两件事各自独立、一眼可扫。
            text = {
                Column {
                    Text(
                        text = "将释放约 $sizeText 空间",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    CleanCacheBullet("会清除：网页缓存、图片临时文件、缩略图")
                    CleanCacheBullet("不受影响：打卡记录、任务、设置、对话与课表进度")
                    CleanCacheBullet("清理后网页首次打开会稍慢，属正常现象")
                }
            },
            // ---- 按钮：主按钮实心、次按钮描边 ----
            //
            // 旧版两个都是 TextButton（纯文字），危险动作与取消长得一样，
            // 而且"清除"在两个按钮里的落点是右下的文字，不够明确。
            // 现在「立即清除」是主题色实心按钮、清理中显示转圈 + 「正在清理」，
            // 「取消」是描边按钮，层级一眼可分。
            confirmButton = {
                Button(
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        scope.launch {
                            // clearAll 内部自己切线程（WebView 必须在主线程建），
                            // 这里直接调即可。
                            val freed = CacheCleaner.clearAll(context)
                            clearing = false
                            showClearCacheDialog = false
                            sizeTick++
                            onMessage(
                                if (freed > 0) "已清除缓存，释放 ${CacheCleaner.formatSize(freed)}"
                                else "缓存已是最干净的状态"
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (clearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (clearing) "正在清理" else "立即清除")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearCacheDialog = false },
                    enabled = !clearing,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("取消")
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
            // 2026-10-23 需求 2：「账号与数据」提到**最顶端**。
            // 同步管理与数据备份是"进来就要办的实事"，外观类设置次之。
            // ---- 账号与数据（2026-10-22 需求 7 / 8）-------------------------
            //
            // 两个入口都是从「我的」首页**搬进来**的：
            // - 同步管理（需求 7）：同时保留在「我的信息」页，两处指向同一个二级页；
            // - 数据备份（需求 8）：「我的」首页不再有它。
            // 放在「通用」之后而不是最上面：设置页的主线仍是外观与通用行为，
            // 这两项是"偶尔来一次"的数据操作。
            Card(Modifier.fillMaxWidth()) {
                Column {
                    SettingsEntryRow(
                        title = "同步管理",
                        subtitle = "云端备份打卡数据、记事本与游戏存档",
                        onClick = onOpenSyncManage
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    SettingsEntryRow(
                        title = "数据备份",
                        subtitle = "导出 / 恢复本机数据",
                        onClick = onOpenBackup
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 外观 ----
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "外观",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        // 用项目既有的 strongTextColor：浅色模式下是**纯黑**，
                        // 比 onSurface(#191919) 更深，符合「文字黑色加深」的要求；
                        // 深色模式下自动沿用 onSurface，不会变成黑底黑字。
                        color = MaterialTheme.strongTextColor
                    )
                    Spacer(Modifier.height(16.dp))

                    // ---- 深色模式 ----
                    Text(
                        "深色模式",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.strongTextColor
                    )
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeOptions.forEachIndexed { index, (mode, label) ->
                            // 选中项的「✔」用 M3 规范的 `icon` 参数提供。
                            //
                            // ⚠️ 不能再在 label 后面**手动加**一个 Icon：那样会出现两个勾
                            // （M3 自带一个 + 手动一个），实测手动那个还跑到了分段按钮外面。
                            // `SegmentedButtonDefaults.Icon(active)` 由 M3 画在正确位置，
                            // 未选中时是透明占位，选中时显示实心 Check（比描边勾粗，深色下也看得清）。
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

                    // ---- 主题色 ----
                    Text(
                        "主题色",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.strongTextColor
                    )
                    Text(
                        "影响导航栏、打卡按钮、时间与图标颜色",
                        style = MaterialTheme.typography.labelSmall,
                        // 副标题用 onSurface 压暗，而不是 onSurfaceVariant：
                        // 后者在浅色模式下偏灰、深色模式下偏灰白，观感都「不够清楚」。
                        // 75% 的 onSurface 在两种模式下都明显比 variant 深（亮）一档。
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.height(12.dp))
                    AccentPicker(
                        selected = currentAccent,
                        onSelect = { viewModel.setThemeAccent(it.id) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- 通用 ----
            Card(Modifier.fillMaxWidth()) {
                // 外层 Column 不设内边距：让分隔线能像其它分组那样只做「左侧缩进」，
                // 而不是被整块内容的内边距一起往里推。
                Column {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "通用",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.strongTextColor
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Switch(
                                checked = autoStartOnBoot,
                                onCheckedChange = { enabled ->
                                    viewModel.setAutoStartOnBoot(enabled)
                                    // 只在「打开」时引导：关闭不需要任何系统操作。
                                    // 这里**无条件**弹窗，不去（也无法）判断是否已有该权限——
                                    // Android 查不到，见文件头与 AppPermissions 的说明。
                                    //
                                    // 「取消」时开关**不回退**（保持开启）的论证：
                                    // 这个开关的语义是「用户的意愿」（是否希望开机后自动恢复提醒），
                                    // 不是「系统是否已放行」的事实。用户既然拨到了开，意愿就是开；
                                    // 需要的只是在系统里补一步操作。若取消就回退成关，会出现两个问题：
                                    //   1. 用户的意愿被系统能力不足静默改写，下次还得再拨一次；
                                    //   2. 回退后开关显示「关」，而用户其实想要「开」，
                                    //      与「开关表示意愿」的语义自相矛盾。
                                    // 所以保留开启，并在对话框里把「能否生效取决于系统设置」讲清楚。
                                    if (enabled) showAutoStartGuide = true
                                }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // 「权限管理」入口：只进二级页，具体的三个开关在 PermissionsScreen 里。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable。
                            .pressScale(onClick = onOpenPermissions)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "权限管理",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                // 副标题只汇报已开启项数，不列举「摄像头 / 定位 / 通知」：
                                // 那三个名字在二级页里已经写全，入口再列一遍是重复信息。
                                text = "已开启 $enabledPermissionCount 项",
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

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // 「声音设置」入口：计时器到点的铃声开关与铃声选择放在二级页里。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable。
                            .pressScale(onClick = onOpenSound)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "声音设置",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                // 副标题直接给结论，不用进二级页就能看出「到点会不会响」。
                                text = if (timerSoundEnabled) {
                                    timerSoundTitle?.takeIf { it.isNotBlank() }?.let { "计时结束响铃：$it" }
                                        ?: "计时结束响铃：系统默认"
                                } else {
                                    "计时结束不响铃"
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

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // 「清除缓存」入口（v1.1.79 需求 1）：
                    // 副标题实时显示当前占用，点开二次确认后真清（不是假按钮）。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(onClick = { showClearCacheDialog = true })
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "清除缓存",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.strongTextColor
                            )
                            Text(
                                // 还没统计出结果时显示「计算中…」而不是 0 B ——
                                // 后者会被误读成"没有缓存"，与事实不符。
                                text = cacheSize?.let { "当前占用 ${CacheCleaner.formatSize(it)}" }
                                    ?: "正在统计…",
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
            }

            Spacer(Modifier.height(24.dp))
        }
    }
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
