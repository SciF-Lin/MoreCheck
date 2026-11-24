package com.morecheck.ui.lab

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.BuildConfig
import com.morecheck.data.CheckUpdateTarget
import com.morecheck.di.appContainer
import com.morecheck.ui.auth.AuthToast
import com.morecheck.ui.auth.rememberAuthColors
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.LocalAccentColor
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.AppPermission
import com.morecheck.util.AppPermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「设备信息查询」——本轮（需求 3）整体改版。
 *
 * ## 改版前是什么样、为什么必须改
 *
 * 上一版有 **17 个可折叠分区**：图例、按导航四栏速查、控件样例、逐页规格表、
 * 按钮规格、按压手感演示、颜色、字号、形状、部件名称、7 种反馈演示……
 * 内容本身没错（都是真实配置），但它是「设计规范大全」，不是「查询」：
 * 用户想确认"这台机器的 Android 版本 / 我的版本号"，要滚三四屏再在一堆表里找。
 * 用户的原话是「使界面简洁易找，功能逻辑完备」——关键词是**查询**。
 *
 * ## 现在的结构：四个标签，各管一件事
 *
 * | 标签 | 回答什么问题 | 内容 |
 * | --- | --- | --- |
 * | 设备 | 这台手机是什么 | 机型 / 屏幕 / 硬件 / 系统 / 存储 / 运行环境 |
 * | 应用 | 装的是哪个包、能用哪些能力 | 版本、构建信息、安装时间、权限状态 |
 * | UI 规范 | 界面上的字、色、圆角、控件是多大 | 主题色、字号、圆角与间距、控件外观 |
 * | 切换与加载 | 页面怎么换、内容怎么等 | 全应用页面切换 / 加载表现盘点 + 是否值得加动画的建议 |
 *
 * 前三个标签**互不嵌套**，第四个「切换与加载」是一份只读盘点清单，
 * 也不与前三个产生跳转关系，因此同样不需要目录、也不需要"先看图例再读表"的阅读引导。
 *
 * 第四个标签是 2026-11-03 需求 5 新增的：用户要求先把「页面切换方式与当前加载表现」
 * 盘点记录下来，**本轮只记录、不新增动画**。因此该标签里全部是**静态清单**，
 * 不改任何页面行为（详见 `SwitchLoadingTab` 的 KDoc）。
 *
 * ## 每条都能复制
 *
 * 这一页的原始用途是「调试时沟通」，所以"把值发给别人"比"把值显示出来"更常用：
 * 点任意一行复制该条（`标签: 值`），标题栏的复制按钮导出全部。
 * 提示用 Snackbar 而不是 Toast：本页在深色模式下也要看清，Snackbar 跟随主题。
 *
 * ## 删掉了什么（以及为什么不是"删了就少信息"）
 *
 * - **逐页规格表 / 四栏速查 / 部件名称 / 7 种反馈演示**：这些是设计评审阶段的
 *   一次性产物，静态写在代码里会随界面迭代变成错的（改了按钮尺寸没人会回来改这里）。
 *   现在只在「UI 规范」里保留**少量**取自真实常量的规格。
 * - **图例**：三栏表没了，图例自然也不需要。
 *
 * 保留的规格都直接读 [AppType] / [Radius] / [Space] 这些**设计令牌对象**，
 * 而不是抄一遍字面量——令牌变了这里跟着变，不会说谎。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UiInspectorScreen(
    onBack: () -> Unit
) {
    // 系统返回键等同「返回上一级」，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tabIndex by rememberSaveable { mutableStateOf(0) }
    // 设备信息只在进入页面时读一次：这些都是不会中途变化的静态值（除运行时长，
    // 它是"开机到现在"的快照，本来就是查询那一刻的事实）。
    val facts = remember(context) { DeviceFacts.read(context) }

    fun copy(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        scope.launch { snackbar.showSnackbar("已复制：$value") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备信息查询") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val text = facts.asPlainText() + "\n\n" + uiSpecPlainText()
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("设备信息", text))
                            scope.launch { snackbar.showSnackbar("已复制全部信息") }
                        }
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "复制全部")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 四个标签用 FilterChip 而不是 TabRow：本页只有四档、标签很短，
            // Chip 的胶囊形与全站（计时页时长芯片、主题色芯片）同一套语汇，
            // 也不需要在下面再画一条分割线。
            //
            // ⚠️ 2026-11-03 从三档变四档后，「UI 规范 / 切换与加载」两枚芯片在
            // 360dp 窄屏 + 大字体下会超出一行，因此给这一行加**横向滚动**：
            // 直接换 TabRow 会改变整页视觉语言，加滚动是最小改动。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                InspectorTab.entries.forEachIndexed { index, tab ->
                    FilterChip(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        label = { Text(tab.title) }
                    )
                }
            }

            // `weight(1f)`：标签行固定高度，列表吃掉剩下的空间并自己滚动。
            // 不给权重的话，LazyColumn 的 fillMaxSize 会按"当前剩余高度"测量，
            // 在极端字号下可能算出 0 高度（列表看起来是空白的）。
            Box(Modifier.weight(1f)) {
                when (InspectorTab.entries[tabIndex]) {
                    InspectorTab.DEVICE -> DeviceTab(facts = facts, onCopy = ::copy)
                    InspectorTab.APP -> AppTab(facts = facts, onCopy = ::copy)
                    InspectorTab.UI -> UiSpecTab(onCopy = ::copy)
                    InspectorTab.SWITCH_LOADING -> SwitchLoadingTab(onCopy = ::copy)
                }
            }
        }
    }
}

/** 页内四个标签。 */
private enum class InspectorTab(val title: String) {
    DEVICE("设备"),
    APP("应用"),
    UI("UI 规范"),

    /**
     * 页面切换与加载盘点（2026-11-03 需求 5 新增）。
     *
     * 用户原话：「页面切换动画可以先不新增动画，先把页面切换方式与加载的表现记录下来」。
     * 因此这一档只有**静态记录**，没有任何会改变行为的代码。
     */
    SWITCH_LOADING("切换与加载")
}

// ---------------------------------------------------------------------------
// 一、设备标签
// ---------------------------------------------------------------------------

@Composable
private fun DeviceTab(facts: DeviceFacts, onCopy: (String, String) -> Unit) {
    InspectorList {
        item(key = "dev-hero") {
            HeroCard(
                title = "${facts.brand} ${facts.model}",
                subtitle = "Android ${facts.androidRelease}（API ${facts.sdkInt}） · ${facts.device}",
                icon = Icons.Rounded.PhoneAndroid
            )
        }

        item(key = "dev-screen-title") {
            SectionHeader("屏幕", Icons.Rounded.Straighten)
        }
        item(key = "dev-screen") {
            InfoCard {
                InfoRow("分辨率", "${facts.widthPx} × ${facts.heightPx} px", onCopy)
                RowDivider()
                InfoRow("可用区域", "${facts.widthDp} × ${facts.heightDp} dp", onCopy)
                RowDivider()
                InfoRow("密度", "${facts.densityDpi} dpi（×${"%.2f".format(facts.density)}）", onCopy)
                RowDivider()
                InfoRow("屏幕尺寸", "%.2f 英寸（按 dpi 估算）".format(facts.diagonalInch), onCopy)
                RowDivider()
                InfoRow("刷新率", "${"%.0f".format(facts.refreshRate)} Hz", onCopy)
                RowDivider()
                InfoRow("字体缩放", "×${"%.2f".format(facts.fontScale)}", onCopy)
            }
        }

        item(key = "dev-hw-title") { SectionHeader("硬件", Icons.Rounded.Memory) }
        item(key = "dev-hw") {
            InfoCard {
                InfoRow("厂商 / 品牌", "${facts.manufacturer} / ${facts.brand}", onCopy)
                RowDivider()
                InfoRow("型号", facts.model, onCopy)
                RowDivider()
                // 设备代号：查 ROM / 内核 / 适配问题时的第一手信息，比"型号"更常用。
                InfoRow("设备代号", facts.device, onCopy)
                RowDivider()
                InfoRow("主板 / 硬件", "${facts.board} / ${facts.hardware}", onCopy)
                RowDivider()
                InfoRow("芯片平台", facts.soc, onCopy)
                RowDivider()
                InfoRow("CPU 架构", facts.abis, onCopy)
                RowDivider()
                InfoRow("CPU 核心", "${facts.cores} 核", onCopy)
                RowDivider()
                InfoRow("运行内存", facts.totalRamText, onCopy)
            }
        }

        item(key = "dev-sys-title") { SectionHeader("系统", Icons.Rounded.Android) }
        item(key = "dev-sys") {
            InfoCard {
                InfoRow("Android 版本", "${facts.androidRelease}（API ${facts.sdkInt}）", onCopy)
                RowDivider()
                InfoRow("安全补丁", facts.securityPatch, onCopy)
                RowDivider()
                InfoRow("内核版本", facts.kernel, onCopy)
                RowDivider()
                InfoRow("构建号", facts.buildId, onCopy)
                RowDivider()
                // 指纹一行很长，允许折行显示（复制出去仍是一整串）。
                InfoRow("构建指纹", facts.fingerprint, onCopy, allowWrap = true)
                RowDivider()
                InfoRow("系统语言", facts.locale, onCopy)
                RowDivider()
                InfoRow("时区", facts.timeZone, onCopy)
                RowDivider()
                InfoRow("已开机", facts.uptimeText, onCopy)
            }
        }

        item(key = "dev-storage-title") { SectionHeader("存储", Icons.Rounded.Tune) }
        item(key = "dev-storage") {
            InfoCard {
                InfoRow("内部存储", facts.storageText, onCopy)
                RowDivider()
                InfoRow("应用安装占用", facts.appSizeText, onCopy)
            }
        }

        item(key = "dev-tip") {
            Text(
                text = "点任意一行可复制该项；标题栏右上角可复制全部。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = Space.xs)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 二、应用标签
// ---------------------------------------------------------------------------

@Composable
private fun AppTab(facts: DeviceFacts, onCopy: (String, String) -> Unit) {
    val context = LocalContext.current
    // 云会话：判断"这一台机器登录了没有"。订阅的是进程内单例的 StateFlow，
    // 用户在本页另一处登出后回来，这里会同步刷新。
    val sessionFlow = remember(context) { context.appContainer.cloudAuthClient.session }
    val session by sessionFlow.collectAsStateWithLifecycle()
    // 「检查更新」当前行为（需求 2 的三选一）：本页只做只读展示，方便确认设置是否生效。
    val updateTargetFlow = remember(context) {
        context.appContainer.settingsRepository.checkUpdateTarget
    }
    val updateTarget by updateTargetFlow.collectAsStateWithLifecycle(
        initialValue = CheckUpdateTarget.APK_DOWNLOAD
    )

    InspectorList {
        item(key = "app-hero") {
            HeroCard(
                title = "MoreCheck ${facts.appVersionName}",
                subtitle = "versionCode ${facts.appVersionCode} · ${facts.packageName}",
                icon = Icons.Rounded.Android
            )
        }

        item(key = "app-version-title") { SectionHeader("版本与构建", Icons.Rounded.Android) }
        item(key = "app-version") {
            InfoCard {
                InfoRow("版本名", facts.appVersionName, onCopy)
                RowDivider()
                InfoRow("版本号", facts.appVersionCode.toString(), onCopy)
                RowDivider()
                InfoRow("包名", facts.packageName, onCopy)
                RowDivider()
                InfoRow(
                    "构建类型",
                    if (facts.buildType == "release") "release（正式）" else "${facts.buildType}（调试）",
                    onCopy
                )
                RowDivider()
                InfoRow("SDK 范围", "min ${facts.minSdk} / target ${facts.targetSdk}", onCopy)
                RowDivider()
                InfoRow("首次安装", facts.firstInstall, onCopy)
                RowDivider()
                InfoRow("最后更新", facts.lastUpdate, onCopy)
            }
        }

        item(key = "app-perm-title") { SectionHeader("权限", Icons.Rounded.Shield) }
        item(key = "app-perm") {
            InfoCard {
                // 只列本应用真正会申请的 3 项（与「设置 → 权限管理」同一份枚举），
                // 不列全部 manifest 权限：那一长串里大部分是系统自动授予的，
                // 对排查问题没有帮助，只会把有用的三行淹掉。
                AppPermission.entries.forEachIndexed { index, permission ->
                    if (index > 0) RowDivider()
                    val granted = AppPermissions.isGranted(context, permission)
                    InfoRow(
                        label = permission.title,
                        value = if (granted) "已授权" else "未授权",
                        onCopy = onCopy,
                        valueColor = if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        extra = permission.scene
                    )
                }
            }
        }

        item(key = "app-cloud-title") { SectionHeader("云账号", Icons.Rounded.Shield) }
        item(key = "app-cloud") {
            InfoCard {
                InfoRow(
                    "登录状态",
                    when {
                        session == null -> "未登录"
                        else -> "已登录（${session?.phone ?: "手机号未知"}）"
                    },
                    onCopy
                )
                RowDivider()
                // 需求 4 的约定在这里可见即可：账号以手机号为主码。
                InfoRow("账号主码", "手机号", onCopy, extra = "后续接入的其它登录方式都必须先关联手机号")
            }
        }

        item(key = "app-dev-title") { SectionHeader("当前生效的开发者选项", Icons.Rounded.Tune) }
        item(key = "app-dev") {
            InfoCard {
                InfoRow("检查更新按钮", updateTarget.title, onCopy)
                RowDivider()
                InfoRow("按压反馈", "跟随「开发者选项 → 界面测试」", onCopy)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 三、UI 规范标签
// ---------------------------------------------------------------------------

@Composable
private fun UiSpecTab(onCopy: (String, String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = LocalAccentColor.current

    InspectorList {
        item(key = "ui-theme-title") { SectionHeader("主题色", Icons.Rounded.Palette) }
        item(key = "ui-theme") {
            InfoCard {
                InfoRow("当前主题色", "${accent.label}（${accent.seed.toHex()}）", onCopy)
                RowDivider()
                ColorRow("primary （主色）", scheme.primary, onCopy)
                RowDivider()
                ColorRow("primaryContainer （浅色容器）", scheme.primaryContainer, onCopy)
                RowDivider()
                ColorRow("background （页面底色）", scheme.background, onCopy)
                RowDivider()
                ColorRow("surfaceContainerLowest （卡片）", scheme.surfaceContainerLowest, onCopy)
                RowDivider()
                ColorRow("onSurface （正文）", scheme.onSurface, onCopy)
                RowDivider()
                ColorRow("onSurfaceVariant （次要文字）", scheme.onSurfaceVariant, onCopy)
                RowDivider()
                ColorRow("error （错误）", scheme.error, onCopy)
                RowDivider()
                ColorRow("outlineVariant （分隔线）", scheme.outlineVariant, onCopy)
            }
        }

        item(key = "ui-type-title") { SectionHeader("字号（7 档）", Icons.Rounded.TextFields) }
        item(key = "ui-type") {
            InfoCard {
                TypeRow("display · 时钟大字", AppType.display)
                RowDivider()
                TypeRow("title · 页面标题", AppType.title)
                RowDivider()
                TypeRow("heading · 区块标题", AppType.heading)
                RowDivider()
                TypeRow("body · 主要正文", AppType.body)
                RowDivider()
                TypeRow("bodySmall · 副标题", AppType.bodySmall)
                RowDivider()
                TypeRow("caption · 标签 / 角标", AppType.caption)
                RowDivider()
                TypeRow("micro · 版本号这类", AppType.micro)
            }
        }

        item(key = "ui-shape-title") { SectionHeader("圆角与间距", Icons.Rounded.Straighten) }
        item(key = "ui-shape") {
            InfoCard {
                RadiusRow("control · 小控件", Radius.control)
                RowDivider()
                RadiusRow("field · 输入框", Radius.field)
                RowDivider()
                RadiusRow("card · 卡片", Radius.card)
                RowDivider()
                RadiusRow("sheet · 弹层", Radius.sheet)
                RowDivider()
                InfoRow(
                    "间距刻度",
                    "4 / 8 / 12 / 16 / 24 / 32 dp（Space.xs…xxl）",
                    onCopy
                )
                RowDivider()
                InfoRow("页面水平内边距", "${Space.lg.value.toInt()} dp", onCopy)
                RowDivider()
                InfoRow("列表行最小高度", "56 dp", onCopy)
                RowDivider()
                InfoRow("主按钮高度", "48–64 dp（按层级）", onCopy)
                RowDivider()
                InfoRow("顶栏高度", "64 dp（Material 3）", onCopy)
            }
        }

        item(key = "ui-demo-title") { SectionHeader("控件样例（可按）", Icons.Rounded.Tune) }
        item(key = "ui-demo") {
            ControlSamples()
        }

        item(key = "ui-parts-title") { SectionHeader("界面部位名称一览", Icons.Rounded.Tune) }
        item(key = "ui-parts") { PartNamesCard(onCopy) }

        item(key = "ui-buttons-title") { SectionHeader("按钮样式分类（可点）", Icons.Rounded.TouchApp) }
        item(key = "ui-buttons") { ButtonStyleSection() }

        item(key = "ui-dialogs-title") { SectionHeader("文字提示与弹窗样式（可点）", Icons.Rounded.Forum) }
        item(key = "ui-dialogs") { FeedbackDemos() }
    }
}

// ---------------------------------------------------------------------------
// 四、页面切换与加载（2026-11-03 需求 5：只记录、不改动画）
// ---------------------------------------------------------------------------

/**
 * 「页面切换与加载」标签。
 *
 * ## 这一档是**只读记录**，不是新的动画开关
 *
 * 用户 2026-11-03 原话：「页面切换动画可以先不新增动画，先把页面切换方式与加载的表现
 * 记录下来」。因此这里把全应用现有的页面切换 / 加载 / 弹窗表现写成**静态清单**，
 * 每条附「建议（是否适合加动画）」，供后续单独决策。
 *
 * ⚠️ 纪律：本标签**不读写任何设置、不修改任何页面行为**。清单内容对得上代码现状
 * （2026-11-03 逐页核对），但它是"某个时间点的快照"——**以后改了转场/加载态要回来同步这里**，
 * 否则它会变成一份会骗人的文档。这也是为什么把数字（260/280ms、开关名）直接写进文案：
 * 它们都是代码里的可调项，不是永久值。
 *
 * 为什么做成静态清单而不是运行时采集：转场参数分散在 `MoreCheckApp`、`TabNavHost`、
 * `DeveloperOptionsScreen` 三处且多为常量，运行时采集不到"另一个页面用了什么动画"；
 * 硬采集要么加一层全局埋点（改动面大）、要么统计出的是不完整样本，反而误导。
 */
@Composable
private fun SwitchLoadingTab(onCopy: (String, String) -> Unit) {
    InspectorList {
        item(key = "switch-notice") {
            Card2 {
                Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
                    Text(
                        text = "本轮只记录、不改动画",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "下表是 2026-11-03 的代码现状盘点，用来判断哪些地方值得加动画。" +
                            "按用户要求，本轮不新增也不修改任何转场与加载动画；" +
                            "每条末尾的「建议」只是结论，不是已落地的改动。",
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.strongTextColor
                    )
                }
            }
        }

        item(key = "switch-page-title") {
            SectionHeader("页面切换", Icons.Rounded.Tune)
        }
        item(key = "switch-page-list") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                PAGE_SWITCH_NOTES.forEach { note -> SwitchLoadingCard(note, onCopy) }
            }
        }

        item(key = "switch-loading-title") {
            SectionHeader("加载与占位", Icons.Rounded.Straighten)
        }
        item(key = "switch-loading-list") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                LOADING_NOTES.forEach { note -> SwitchLoadingCard(note, onCopy) }
            }
        }

        item(key = "switch-dialog-title") {
            SectionHeader("弹窗与反馈", Icons.Rounded.Forum)
        }
        item(key = "switch-dialog-list") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                FEEDBACK_NOTES.forEach { note -> SwitchLoadingCard(note, onCopy) }
            }
        }

        item(key = "switch-tip") {
            Text(
                text = "点任意一条可复制它的「目标 / 当前表现 / 建议」，方便把结论贴到需求里。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = Space.xs)
            )
        }
    }
}

/** 一条盘点记录。 */
private data class SwitchLoadingNote(
    /** 页面 / 组件（带入口路径）。 */
    val target: String,
    /** 当前代码里的真实表现（不是规划）。 */
    val current: String,
    /** 是否适合加动画，以及为什么。 */
    val advice: String
)

/** 页面切换类盘点（对应 `MoreCheckApp` / `TabNavHost` / `DeveloperOptionsScreen`）。 */
private val PAGE_SWITCH_NOTES = listOf(
    SwitchLoadingNote(
        target = "底栏四入口（主页 / 发现 / 统计 / 我的）",
        current = "同一时刻只组合当前入口（各入口独立 NavController，`key(selectedTab)`）。" +
            "切换时当前页与目标页并排做整屏平移：跟手拖动，松手后 tween 收尾。" +
            "默认 260ms，可在「开发者选项 → 导航栏切换动画时长」调 100–600ms；" +
            "「底部入口切换动画」关闭时当帧硬切。全程无淡入淡出。",
        advice = "已覆盖跟手与收尾，不建议再叠加动画；" +
            "加淡入会让两页半透明时透出底栏（历史 bug），不要做。"
    ),
    SwitchLoadingNote(
        target = "二级页推入 / 返回（TabNavHost 统一转场）",
        current = "新页从屏幕右侧整屏滑入、被覆盖的旧页原地不动；返回时当前页向右滑出、" +
            "下层原地不动。默认 280ms，可在「开发者选项 → 界面切换动画时长」调 100–600ms；" +
            "「页面转场动画」关闭后四个转场全部为 None（当帧切换）。",
        advice = "与微信推入一致，节奏已可调；不建议加淡入" +
            "（历史上叠透明度导致底栏半透明浮现）。"
    ),
    SwitchLoadingNote(
        target = "贪吃蛇页（游戏专属转场）",
        current = "从屏幕下沿整块向上滑入、返回向下滑出，240ms；" +
            "同样受「页面转场动画」总开关控制。",
        advice = "纵向动画已有专属节奏，不建议改；其它游戏页仍走统一的横向推入。"
    ),
    SwitchLoadingNote(
        target = "实验室二级页（开发者选项各子页 / 图标更改等就地覆盖）",
        current = "宿主页常驻、二级页整页盖上来：从右滑入 260ms、返回向右滑出，无淡入；" +
            "退场期间继续渲染「最近显示过的那一页」，避免返回瞬间白屏。",
        advice = "与导航层 280ms 基本一致；不建议再为它单独加时长/缓动开关（多一个开关、收益低）。"
    ),
    SwitchLoadingNote(
        target = "应用开屏（Hello World）与隐私同意弹窗",
        current = "开屏最短展示时长可调（0–5 秒），退场淡出 320ms；" +
            "用户点「同意并继续」后再额外停留 750ms 才退场。",
        advice = "开屏是品牌页，淡出已够；不建议再加缩放/位移（会与同意弹窗淡出叠加）。"
    ),
    SwitchLoadingNote(
        target = "页面内标签（主页「打卡 / 计时」、日历周月视图等）",
        current = "标签切换为即时状态切换，无转场；日历周/月切换即时调整行高与内容。",
        advice = "可考虑给内容区加 120–150ms 交叉淡化（成本低、收益中），" +
            "但要逐页确认不会闪占位——本轮不改。"
    ),
    SwitchLoadingNote(
        target = "系统返回键 / 返回手势",
        current = "各页用 `BackHandler` 或 NavHost 回退栈处理；" +
            "「页面转场动画」开启时播放对应退出转场，关闭时当帧消失。",
        advice = "无需新增；返回动画必须与进入严格配对，改一处就要改另一处。"
    )
)

/** 加载与占位类盘点（对应各页的 loading / skeleton 分支）。 */
private val LOADING_NOTES = listOf(
    SwitchLoadingNote(
        target = "B站视频（推荐 / 热门 / 搜索）",
        current = "首屏显示与真实列表同形的骨架屏（呼吸 alpha）；加载更多在列表底部显示小转圈；" +
            "失败显示错误卡与重试按钮，无网络与接口错误分开提示。",
        advice = "已是本应用最完整的加载态，不必改。"
    ),
    SwitchLoadingNote(
        target = "安装包下载页（含云端安装包读取）",
        current = "加载时「转圈 + 说明」头卡 + 两张骨架卡（900ms 呼吸）；" +
            "已有列表后刷新只在标题栏转圈、不退回骨架；失败显示错误卡 + 重试。",
        advice = "可让骨架条数随列表长度自适应，收益很低，不建议本轮做。"
    ),
    SwitchLoadingNote(
        target = "热搜（发现 → 热搜）",
        current = "加载时转圈 + 与真实榜单同形的骨架卡（呼吸），数据到达不跳版；" +
            "概况页（NewsBrief）另有自己的转圈与失败态。",
        advice = "不建议再改；热搜刷新频繁，多加动画反而显得吵。"
    ),
    SwitchLoadingNote(
        target = "GitHub 热门 / 天气",
        current = "居中 `CircularProgressIndicator`（约 28dp / 2dp 描边）转圈，" +
            "加载完直接换内容，无骨架、无淡入。",
        advice = "适合补 150ms 淡入或骨架（收益中、成本低）；" +
            "建议先抽公共加载组件再统一，本轮只记录。"
    ),
    SwitchLoadingNote(
        target = "云端读取（同步管理 / 备份 / 云账号）",
        current = "按钮或区域内的 `CircularProgressIndicator`；失败多为文字提示，" +
            "部分入口有重试；没有骨架屏。",
        advice = "适合统一成「错误卡 + 重试」并补骨架（收益中）；" +
            "需要先定公共组件，本轮只记录。"
    ),
    SwitchLoadingNote(
        target = "内嵌网页（今日待办 / 成绩收集 / 去 AI 味 等）",
        current = "WebView 自身加载，页面顶部有加载进度 / 转圈；" +
            "加载失败由共享的 WebView 错误页组件承接。",
        advice = "建议补一层与主题同色的加载遮罩再淡出（收益中）；" +
            "WebView 首帧白底与深色主题不协调，值得后续单独做。"
    ),
    SwitchLoadingNote(
        target = "图片加载（头像 / B站封面）",
        current = "头像手动解码 + 进程级缓存，未命中时显示灰底占位；" +
            "B站封面自写 OkHttp + BitmapFactory + LruCache，占位底色；两者都没有淡入。",
        advice = "列表图片可加 100–150ms 淡入（成本低、收益中），" +
            "但会增加滚动帧开销，本轮不改。"
    ),
    SwitchLoadingNote(
        target = "本地列表首屏（首页任务 / 统计 / 我的）",
        current = "本地数据来自 Room / DataStore，首帧多为直接渲染或极短占位，无统一骨架。",
        advice = "本地读通常感知不到，不建议加骨架（加了反而会闪一下）。"
    )
)

/** 弹窗与反馈类盘点。 */
private val FEEDBACK_NOTES = listOf(
    SwitchLoadingNote(
        target = "AlertDialog（确认 / 三选一）",
        current = "系统默认的窗口淡入与遮罩，无自定义转场；弹窗本身由独立窗口承载。",
        advice = "无需改；系统弹窗动画由系统设置（动画缩放）决定。"
    ),
    SwitchLoadingNote(
        target = "ModalBottomSheet（隐私同意共用骨架 AppBottomSheet）",
        current = "自带下滑消失动画；点击按钮时先 `hide()` 再执行动作（保证退场动画完整）；" +
            "返回键 / 下滑 / 遮罩由 `dismissible` 控制。",
        advice = "不建议加自定义动画；该骨架有闪白 / 返回键 / overlay 四处历史雷区，改动风险高。"
    ),
    SwitchLoadingNote(
        target = "页面级提示（Snackbar / Toast）",
        current = "页面级提示用 Snackbar（跟随主题、底部居中、让出底栏高度）；" +
            "「添加桌面」「安装包下载」等动作提示已改为系统 Toast。",
        advice = "无需改；系统 Toast 不受应用内 Snackbar 排队影响，适合动作完成提示。"
    ),
    SwitchLoadingNote(
        target = "按压反馈（全应用统一）",
        current = "可点元素统一使用 `pressScale` 按压缩放（默认开，" +
            "可在「开发者选项 → 界面测试 → 按压反馈」关闭）；长按有触感反馈。",
        advice = "已足够；不建议再叠加涟漪以外的动画（性能敏感区域多）。"
    )
)

/** 一条盘点卡片：目标 + 当前表现 + 建议，整卡可点复制。 */
@Composable
private fun SwitchLoadingCard(
    note: SwitchLoadingNote,
    onCopy: (String, String) -> Unit
) {
    Card2 {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onCopy(
                        note.target,
                        "${note.target}\n当前表现：${note.current}\n建议：${note.advice}"
                    )
                }
                .padding(horizontal = Space.lg, vertical = Space.md)
        ) {
            Text(
                text = note.target,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前表现",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = note.current,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "建议（是否适合加动画）",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = note.advice,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
            )
        }
    }
}

/** 可交互控件样例：只在本页生效，不写任何设置（与上一版同一口径）。 */
@Composable
private fun ControlSamples() {
    var switchOn by remember { mutableStateOf(true) }
    var sliderValue by remember { mutableStateOf(0.4f) }
    var chipIndex by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }

    Card2 {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开关 Switch", fontSize = 14.sp, color = MaterialTheme.strongTextColor)
                Spacer(Modifier.weight(1f))
                Switch(checked = switchOn, onCheckedChange = { switchOn = it })
            }
            SampleGap()
            Text(
                text = "滑块 Slider · ${"%.0f".format(sliderValue * 100)}%",
                fontSize = 14.sp,
                color = MaterialTheme.strongTextColor
            )
            Slider(value = sliderValue, onValueChange = { sliderValue = it })
            SampleGap()
            Text("芯片 FilterChip", fontSize = 14.sp, color = MaterialTheme.strongTextColor)
            Spacer(Modifier.height(Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                listOf("全部", "本周", "本月").forEachIndexed { index, label ->
                    FilterChip(
                        selected = chipIndex == index,
                        onClick = { chipIndex = index },
                        label = { Text(label) }
                    )
                }
            }
            SampleGap()
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入框 OutlinedTextField") },
                placeholder = { Text("随便输点字试试") }
            )
            SampleGap()
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(onClick = { }) { Text("主按钮") }
                Button(onClick = { }, enabled = false) { Text("禁用") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 数据收集
// ---------------------------------------------------------------------------

/**
 * 本机的一次性快照。
 *
 * 为什么做成 data class + `remember` 一次读全：
 * 这些值散落在 Build / Resources / ActivityManager / StatFs / PackageManager 五处，
 * 每次重组都去查一遍既慢又可能读到不同的值（比如剩余存储每秒都在变）。
 * 页面要的是"打开这一页时的机器状态"，一次快照就是最贴切的语义。
 */
private data class DeviceFacts(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val board: String,
    val hardware: String,
    val soc: String,
    val abis: String,
    val cores: Int,
    val totalRamText: String,
    val widthPx: Int,
    val heightPx: Int,
    val widthDp: Int,
    val heightDp: Int,
    val densityDpi: Int,
    val density: Float,
    val fontScale: Float,
    val refreshRate: Float,
    val diagonalInch: Double,
    val androidRelease: String,
    val sdkInt: Int,
    val securityPatch: String,
    val kernel: String,
    val buildId: String,
    val fingerprint: String,
    val locale: String,
    val timeZone: String,
    val uptimeText: String,
    val storageText: String,
    val appSizeText: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val buildType: String,
    val minSdk: Int,
    val targetSdk: Int,
    val firstInstall: String,
    val lastUpdate: String
) {

    /** 导出成纯文本（标题栏「复制全部」用）。 */
    fun asPlainText(): String = buildString {
        appendLine("== 设备 ==")
        appendLine("机型: $brand $model")
        appendLine("设备代号: $device")
        appendLine("Android: $androidRelease (API $sdkInt)")
        appendLine("屏幕: ${widthPx}x${heightPx}px / ${widthDp}x${heightDp}dp @ ${densityDpi}dpi")
        appendLine("刷新率: ${"%.0f".format(refreshRate)}Hz")
        appendLine("芯片: $soc")
        appendLine("内存: $totalRamText")
        appendLine("存储: $storageText")
        appendLine("构建指纹: $fingerprint")
        appendLine("== 应用 ==")
        appendLine("版本: $appVersionName ($appVersionCode)")
        appendLine("包名: $packageName")
        appendLine("构建类型: $buildType")
        appendLine("更新: $lastUpdate")
    }

    companion object {
        fun read(context: Context): DeviceFacts {
            val res = context.resources
            val metrics = res.displayMetrics
            val configuration = res.configuration
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val refreshRate = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.refreshRate ?: 0f

            val pkg = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val appInfo = context.applicationInfo

            val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
            val totalBytes = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L
            val freeBytes = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L

            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

            return DeviceFacts(
                manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
                brand = Build.BRAND.orEmpty().replaceFirstChar { it.uppercase() },
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                board = Build.BOARD.orEmpty(),
                hardware = Build.HARDWARE.orEmpty(),
                soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MODEL.orEmpty().ifBlank { Build.HARDWARE.orEmpty() }
                } else {
                    Build.HARDWARE.orEmpty()
                },
                abis = Build.SUPPORTED_ABIS.joinToString(" / "),
                cores = Runtime.getRuntime().availableProcessors(),
                totalRamText = "%.1f GB".format(memoryInfo.totalMem / 1024.0 / 1024 / 1024),
                widthPx = metrics.widthPixels,
                heightPx = metrics.heightPixels,
                widthDp = (metrics.widthPixels / metrics.density).toInt(),
                heightDp = (metrics.heightPixels / metrics.density).toInt(),
                densityDpi = metrics.densityDpi,
                density = metrics.density,
                fontScale = configuration.fontScale,
                refreshRate = refreshRate,
                diagonalInch = run {
                    val w = metrics.widthPixels.toDouble()
                    val h = metrics.heightPixels.toDouble()
                    if (metrics.xdpi > 0 && metrics.ydpi > 0) {
                        sqrt(w * w + h * h) / metrics.xdpi
                    } else {
                        sqrt(w * w + h * h) / metrics.densityDpi
                    }
                },
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Build.VERSION.SECURITY_PATCH.orEmpty().ifBlank { "未知" }
                } else {
                    "未知"
                },
                kernel = System.getProperty("os.version").orEmpty(),
                buildId = Build.DISPLAY.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
                locale = Locale.getDefault().let { "${it.language}-${it.country}" },
                timeZone = TimeZone.getDefault().id,
                uptimeText = formatUptime(SystemClock.elapsedRealtime()),
                storageText = "%s 可用 / %s 共".format(
                    formatBytes(freeBytes), formatBytes(totalBytes)
                ),
                appSizeText = formatBytes(
                    // APK 本体大小（sourceDir 就是已安装的那份 apk）。
                    runCatching { java.io.File(appInfo.sourceDir).length() }.getOrDefault(0L)
                ),
                appVersionName = pkg?.versionName ?: BuildConfig.VERSION_NAME,
                appVersionCode = run {
                    @Suppress("DEPRECATION")
                    val code = pkg?.versionCode?.toLong() ?: 0L
                    @Suppress("DEPRECATION")
                    if (code > 0) code else BuildConfig.VERSION_CODE.toLong()
                },
                packageName = context.packageName,
                buildType = BuildConfig.BUILD_TYPE,
                minSdk = appInfo.minSdkVersion,
                targetSdk = appInfo.targetSdkVersion,
                firstInstall = pkg?.firstInstallTime?.let { formatter.format(Date(it)) } ?: "未知",
                lastUpdate = pkg?.lastUpdateTime?.let { formatter.format(Date(it)) } ?: "未知"
            )
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes <= 0L -> "未知"
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
            else -> "%.0f KB".format(bytes / 1024.0)
        }

        private fun formatUptime(millis: Long): String {
            val totalMinutes = millis / 60_000
            val days = totalMinutes / (60 * 24)
            val hours = (totalMinutes % (60 * 24)) / 60
            val minutes = totalMinutes % 60
            return buildString {
                if (days > 0) append("${days} 天 ")
                if (days > 0 || hours > 0) append("${hours} 小时 ")
                append("${minutes} 分")
            }
        }
    }
}

/** UI 规范的纯文本导出（标题栏「复制全部」用）。 */
private fun uiSpecPlainText(): String = buildString {
    appendLine("== UI 规范 ==")
    appendLine("字号: display 34 / title 20 / heading 17 / body 15 / bodySmall 13 / caption 12 / micro 11")
    appendLine("圆角: control 8 / field 12 / card 16 / sheet 24 / pill 全圆")
    appendLine("间距: 4 / 8 / 12 / 16 / 24 / 32 dp")
    appendLine("列表行最小高度: 56dp；顶栏 64dp")
    // 2026-11-03 需求 5：把「页面切换与加载」盘点的一句话结论也带上，
    // 方便复制给需求方时不必再手动补一段（完整盘点见「切换与加载」标签）。
    appendLine("== 页面切换与加载（只记录，未改动画） ==")
    appendLine("底栏切换: 跟手整屏平移，默认 260ms，可在开发者选项调 100–600ms")
    appendLine("二级页: 右滑入 / 右滑出，默认 280ms，可在开发者选项调 100–600ms；可关闭")
    appendLine("实验室二级页: 就地覆盖右滑入 / 右滑出 260ms")
    appendLine("加载态: 榜单类多为骨架屏 + 呼吸；工具类多为居中转圈；云端读取无骨架")
    appendLine("图片: 占位底色 + 无淡入；弹窗: 系统默认 / 底部弹层自带下滑消失")
}

// ---------------------------------------------------------------------------
// 通用小部件
// ---------------------------------------------------------------------------

@Composable
private fun InspectorList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.lg, end = Space.lg, top = Space.xs, bottom = Space.xl
        ),
        verticalArrangement = Arrangement.spacedBy(Space.md),
        content = content
    )
}

@Composable
private fun SectionHeader(text: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = Space.xs, top = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
    }
}

/** 页面顶部的概览卡：一眼看到"这是什么机器 / 什么版本"。 */
@Composable
private fun HeroCard(title: String, subtitle: String, icon: ImageVector) {
    Card2 {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.strongTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/** 一组信息行共用的白卡。 */
@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card2 { Column { content() } }
}

@Composable
private fun Card2(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        content()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = Space.lg),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 一条信息：左标签、右值，整行可点复制。
 *
 * `extra` 是值下面的一行小字（比如权限的"使用场景"）；`allowWrap` 给构建指纹那种
 * 长到必须折行的值（默认单行省略，避免一行把卡片撑得很高）。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    onCopy: (String, String) -> Unit,
    extra: String? = null,
    valueColor: Color? = null,
    allowWrap: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(label, value) }
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.width(104.dp)
        )
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f)) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor ?: MaterialTheme.strongTextColor,
                maxLines = if (allowWrap) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = if (allowWrap) FontFamily.Monospace else FontFamily.Default
            )
            if (extra != null) {
                Text(
                    text = extra,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** 色板行：色块 + 名称 + 十六进制，点一下复制色值。 */
@Composable
private fun ColorRow(label: String, color: Color, onCopy: (String, String) -> Unit) {
    val hex = color.toHex()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(label, hex) }
            .heightIn(min = 44.dp)
            .padding(horizontal = Space.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color)
                // 浅色色块落在白卡上没有边界，补一圈极淡描边（不用投影：
                // 软件渲染下会糊成一片灰，见项目约定）。
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.strongTextColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = hex,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

/** 字号行：用该档样式渲染一段样例文字，右侧标出字号。 */
@Composable
private fun TypeRow(label: String, style: androidx.compose.ui.text.TextStyle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "正文 Aa 123",
            style = style,
            color = MaterialTheme.strongTextColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${style.fontSize.value.toInt()} sp",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Text(
                text = label.substringBefore(' '),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
    }
}

/** 圆角行：一个按该半径画的小方块 + 数值。 */
@Composable
private fun RadiusRow(label: String, radius: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(radius))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(radius))
        )
        Spacer(Modifier.width(Space.md))
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.strongTextColor,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${radius.value.toInt()} dp",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun SampleGap() {
    Spacer(Modifier.height(Space.md))
}

/** `Color` → `#RRGGBB`（忽略 alpha；本页展示的都是不透明色）。 */
private fun Color.toHex(): String {
    val argb = toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

// ---------------------------------------------------------------------------
// 界面部位名称一览（恢复自 v1.1.98/99：沟通界面问题时先说标准名字）
// ---------------------------------------------------------------------------

/** 各界面区域的标准叫法 + 本应用对应哪一处，便于按位置对上名字。 */
@Composable
private fun PartNamesCard(onCopy: (String, String) -> Unit) {
    InfoCard {
        InfoRow("顶部标题栏", "每页最上方：标题 + 返回箭头，如本页「设备信息查询」", onCopy)
        RowDivider()
        InfoRow("底部导航栏", "主页 / 发现 / 统计 / 我的四个一级入口的底栏", onCopy)
        RowDivider()
        InfoRow("标签页", "底栏每一项；主页标题栏里「打卡 / 计时」的切换标签", onCopy)
        RowDivider()
        InfoRow("卡片", "设置页分组卡、本页每个分区、发现页列表卡片", onCopy)
        RowDivider()
        InfoRow("列表行", "我的页「任务管理 / 设置 / 数据备份」等，以及发现页每一条", onCopy)
        RowDivider()
        InfoRow("悬浮按钮", "本应用未使用；等价入口是发现页右上角「+」与实验室底部通栏按钮", onCopy)
        RowDivider()
        InfoRow("底部提示条", "页面级一次性提示，底部居中、让出底栏高度", onCopy)
        RowDivider()
        InfoRow("弹窗", "AlertDialog 用于确认；ModalBottomSheet 用于隐私同意", onCopy)
        RowDivider()
        InfoRow("胶囊标签", "计时页「最近用过的时长」胶囊；见下方控件样例", onCopy)
        RowDivider()
        InfoRow("角标", "主页任务圆钮「已打卡」圆点（22dp）；发现页未读红点（99+ 封顶）", onCopy)
        RowDivider()
        InfoRow("分割线", "列表行之间细线（outlineVariant，左缩进 56/64dp）", onCopy)
        RowDivider()
        InfoRow("搜索框", "官网页顶部输入框；见下方控件样例的 OutlinedTextField", onCopy)
    }
}

// ---------------------------------------------------------------------------
// 可交互演示通用骨架（恢复自 v1.1.98/99，常量就地取值）
// ---------------------------------------------------------------------------

/** 浮层/按钮演示块：组件名 + 可点示例 + 规格说明。 */
@Composable
private fun DemoBlock(
    title: String,
    spec: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.strongTextColor)
        Spacer(Modifier.height(6.dp))
        content()
        Spacer(Modifier.height(6.dp))
        Text(spec, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.strongTextColor)
    }
}

// ---------------------------------------------------------------------------
// 按钮样式分类（恢复自 v1.1.98/99：五枚真按钮，按「有没有底色」分两组）
// ---------------------------------------------------------------------------

/** Material 3 五种按钮：有底色的 Filled / Tonal，无底色的 Outlined / Text / IconButton。 */
@Composable
private fun ButtonStyleSection() {
    Text(
        text = "按「有没有底色」分两类：Filled / Tonal 强调主次动作，" +
            "Outlined / Text / IconButton 用在次要入口。均可点。",
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.strongTextColor
    )

    Card2 {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            DemoBlock(
                title = "Filled Button（实心按钮 · 有底色）",
                spec = "容器 primary、内容 onPrimary；胶囊形；高 40dp、左右内边距 24dp、" +
                    "文字 labelLarge（14sp / Medium）。使用场景：主页「打卡」通栏按钮、" +
                    "隐私同意弹窗主按钮。"
            ) {
                Button(onClick = {}) { Text("Filled Button") }
            }

            DemoBlock(
                title = "Tonal Button（浅色按钮 · 有底色）",
                spec = "浅底：容器 secondaryContainer、内容 onSecondaryContainer；" +
                    "尺寸与 Filled 相同。本项目没有直接用 Tonal Button，浅底次要动作目前用 " +
                    "primaryContainer 自绘（发现页列表行的图标底）。"
            ) {
                FilledTonalButton(onClick = {}) { Text("Tonal Button") }
            }

            DemoBlock(
                title = "Outlined Button（描边按钮 · 无底色）",
                spec = "透明容器 + 1dp outline 描边；高 40dp、左右 24dp；按下描边变粗一档。" +
                    "使用场景：开发者选项的动作按钮（通知测试等），固定宽 104dp。"
            ) {
                OutlinedButton(onClick = {}) { Text("Outlined Button") }
            }

            DemoBlock(
                title = "Text Button（文字按钮 · 无底色）",
                spec = "无底色无描边，只有文字；高 40dp、左右 12dp、主色文字。" +
                    "使用场景：AlertDialog 的确认 / 取消、「恢复默认」「暂不」等次要出口。"
            ) {
                TextButton(onClick = {}) { Text("Text Button") }
            }

            DemoBlock(
                title = "IconButton（图标按钮 · 无底色）",
                spec = "没有容器底色，只有 40dp 透明状态层（按下才显形）；图标 24dp、" +
                    "最小点击区 48dp。使用场景：顶栏返回箭头、各页右上角菜单、输入栏加号。"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Add, contentDescription = "图标按钮演示")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "图标周围 48dp 都能点",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文字提示与弹窗演示（恢复自 v1.1.98/99：全部是真组件，可点）
// ---------------------------------------------------------------------------

/**
 * 「文字提示与弹窗样式示例」：Tooltip / Snackbar（深浅两版）/ 居中提示 /
 * AlertDialog / BottomSheet / DropdownMenu / Popconfirm。
 * 系统 Toast 全项目没有调用（统一 Snackbar），Popconfirm 无内置组件只作说明。
 */
@Composable
private fun FeedbackDemos() {
    Text(
        text = "下面都是真实组件，可以直接点；规格就是当前主题实际生效的值。",
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.strongTextColor
    )

    Card2 {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            TooltipDemo()
            SnackbarDemo()
            LightSnackbarDemo()
            CenterToastDemo()
            AlertDialogDemo()
            BottomSheetDemo()
            DropdownMenuDemo()
            PopconfirmDemo()
        }
    }
}

/** Tooltip：长按 / 悬停出现的小黑气泡。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipDemo() {
    DemoBlock(
        title = "Tooltip（工具提示）",
        spec = "小黑气泡：底色 inverseSurface、文字 inverseOnSurface、圆角 4dp、" +
            "正文 bodySmall、左右内边距 8dp / 上下 4dp、最大宽度 200dp；" +
            "触屏长按约 500ms 出现。本项目很少用：触屏没有 hover，长按又多被列表长按菜单占用。"
    ) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("长按 / 悬停查看提示") } },
            state = rememberTooltipState()
        ) {
            OutlinedButton(onClick = {}) {
                Text("长按我（触屏）/ 悬停我（鼠标）")
            }
        }
    }
}

/** Toast：本项目没有系统 Toast，统一用 Snackbar，所以演示一条真 Snackbar。 */
@Composable
private fun SnackbarDemo() {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DemoBlock(
        title = "Toast（轻提示）",
        spec = "本项目用 Snackbar 代替系统 Toast（全项目没有 android.widget.Toast 调用）。" +
            "差异：系统 Toast 是独立窗口、不跟主题、约 2 秒消失且不能操作；" +
            "Snackbar 长在页面里、跟随主题、可带操作按钮、可划走。" +
            "规格：容器 surfaceContainerHigh、文字 onSurface、底部让出底栏高度（80dp + 导航栏 inset）。"
    ) {
        SnackbarHost(hostState = hostState, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            scope.launch { hostState.showSnackbar("这是一条 Snackbar 轻提示") }
        }) {
            Text("显示 Snackbar")
        }
    }
}

/** 白色轻提示：同一条 Snackbar 换皮，固定白底深字 12dp 圆角，不随主题反转。 */
@Composable
private fun LightSnackbarDemo() {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    DemoBlock(
        title = "Toast（白色轻提示）",
        spec = "同一条 Snackbar 机制换一层皮：容器固定 #FFFFFF、文字 #191919、圆角 12dp。" +
            "浅色系界面（登录 / 注册那类亮底页）里比主题深底更协调；" +
            "深色模式下刻意仍是白底——「白色轻提示」的语义就是「亮底」，不随主题反转。"
    ) {
        SnackbarHost(hostState = hostState) { data ->
            Snackbar(
                data,
                containerColor = Color.White,
                contentColor = Color(0xFF191919),
                shape = RoundedCornerShape(12.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            scope.launch { hostState.showSnackbar("白色轻提示：操作已完成") }
        }) {
            Text("显示白色轻提示")
        }
    }
}

/** 居中提示：直接复用登录/注册页真实的 AuthToast（1.6 秒自动消失）。 */
@Composable
private fun CenterToastDemo() {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            delay(1600)
            message = null
        }
    }

    DemoBlock(
        title = "居中提示（Center Toast）",
        spec = "屏幕正中短暂浮现的结果提示（登录 / 注册页的「注册成功」「验证码已发送」就是它）：" +
            "圆角 16dp、底色文字随登录页配色（accent 淡底 + 深字），约 1.6 秒自动消失、不挡操作。"
    ) {
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { message = "注册成功" }) {
                Text("显示居中提示")
            }
            AuthToast(
                message = message,
                colors = rememberAuthColors(),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/** AlertDialog：真实的确认弹窗，确认 / 取消两枚 TextButton。 */
@Composable
private fun AlertDialogDemo() {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    DemoBlock(
        title = "AlertDialog（警告 / 确认弹窗）",
        spec = "容器 surfaceContainerHigh、圆角取主题 shapes.medium（本主题 8dp）、" +
            "标题 titleMedium、正文 bodyMedium；确认 / 取消都是无底色 TextButton" +
            "（高 40dp、左右 12dp）；宽度最小 280dp、最大 560dp，点遮罩或返回键关闭。" +
            "使用场景：更换图标确认、登录引导、下载拒绝。"
    ) {
        OutlinedButton(onClick = { showDialog = true }) {
            Text("打开确认弹窗")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("确认操作") },
            text = {
                Text("这是一枚真实的 Material 3 AlertDialog：确认 / 取消都是无底色的 TextButton。")
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("取消") }
            }
        )
    }
}

/** ModalBottomSheet：真实的底部弹窗（应用内用于隐私同意 / 撤回同意）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomSheetDemo() {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    DemoBlock(
        title = "ModalBottomSheet（底部弹窗）",
        spec = "顶部圆角 20dp（本项目 AppBottomSheet 同款），容器色由内层自画（防闪白）；" +
            "隐私同意弹窗高度 0.41 屏高、不可下滑关闭；主按钮高 52dp、字号 16sp。" +
            "使用场景：首次启动的隐私同意弹窗、撤回隐私同意弹窗。"
    ) {
        OutlinedButton(onClick = { showSheet = true }) {
            Text("打开底部弹窗")
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "隐私同意弹窗（示意）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "真实场景是首次启动的强制同意弹窗：高度 0.41 屏高、不可下滑关闭。" +
                        "这里用同一套 Material 3 ModalBottomSheet 骨架演示，所以可以下滑关闭。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("同意并继续")
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** DropdownMenu：真实的浮层菜单（应用内用于任务行 / 各页右上角）。 */
@Composable
private fun DropdownMenuDemo() {
    var expanded by rememberSaveable { mutableStateOf(false) }

    DemoBlock(
        title = "Popup / DropdownMenu（浮层菜单）",
        spec = "M3 默认：浮层容器 surfaceContainer、圆角 shapes.extraSmall、阴影 Level2；" +
            "每项高 48dp、左右 16dp、文字 bodyLarge，锚在触发元素上方。" +
            "应用内三处菜单把容器改成 surfaceContainerHigh、圆角 16dp。" +
            "使用场景：任务行右上角菜单，主页 / 发现 / 统计的右上角菜单。"
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("任务行右上角菜单")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(text = { Text("置顶") }, onClick = { expanded = false })
                DropdownMenuItem(text = { Text("编辑") }, onClick = { expanded = false })
                DropdownMenuItem(text = { Text("删除") }, onClick = { expanded = false })
            }
        }
    }
}

/** Popconfirm：Compose 无内置组件，本项目用 AlertDialog 替代。 */
@Composable
private fun PopconfirmDemo() {
    DemoBlock(
        title = "Popconfirm（气泡确认）",
        spec = "Compose Material 3 没有内置 Popconfirm（气泡里放「确定 / 取消」）。" +
            "本项目统一用 AlertDialog 承担二次确认：标题一句问句、正文给出后果、" +
            "确认 / 取消两枚 TextButton。不用气泡是因为触屏上锚点不稳，" +
            "也会给列表行多引入一层浮层。"
    ) {
        Text(
            text = "无内置组件，故无可点示例。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
