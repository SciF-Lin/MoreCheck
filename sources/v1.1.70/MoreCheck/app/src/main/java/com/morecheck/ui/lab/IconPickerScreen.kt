package com.morecheck.ui.lab

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MoreCheckApp
import com.morecheck.R
import com.morecheck.data.AppIconRepository
import com.morecheck.data.IconApplyResult
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「开发者选项 → 图标测试 → 图标更改（实验性）」。
 *
 * 本页做三件事（需求原文：可自行上传图片并裁切，替代 app 原先图标，也可一键恢复默认）：
 * 1. **内置图标真实切换桌面图标** —— 四套 `activity-alias`，见 [AppIconRepository]；
 * 2. **自定义图片上传 + 正方形裁切** —— 存进应用私有目录，**只用于应用内显示**；
 * 3. **一键恢复默认** —— 切回默认那套 alias + 清掉自传图。
 *
 * ---
 * ## ⚠️ 本页最容易被误解的一点：自传图**不会**成为桌面图标
 *
 * 桌面图标是 manifest 里的**资源引用**，系统在安装期解析并缓存，运行期没有任何 API 能换掉它。
 * 所以「替代 app 原先图标」这句在 Android 上只有两条路：
 * - **内置图标**（alias 切换）→ 真的换桌面图标；
 * - **自传图片** → 只能换应用内显示。
 *
 * 这条口子已与用户确认（manifest 图标必须是资源，这是硬限制）。
 * 界面上因此把两件事**分成两张卡片**、并分别写明作用范围，
 * 否则用户上传完一张图、回到桌面发现图标没变，会认为功能坏了。
 *
 * ## 本页为什么「就地覆盖」在开发者选项页上，而不是走导航路由
 *
 * 走路由要在 `ui/nav/TabDef.kt` 里加一个 `TabDestination`、再到 `TabNavHost.kt` 注册 `composable`，
 * 把一个纯 UI 的临时流程提升成全局导航状态（还要处理「返回时回本页还是回上一页」）。
 * 本项目的 `PersonalInfoScreen → AvatarCropScreen` 已经确立了**就地覆盖**这个做法，
 * 这里沿用同一套：调用方用 `if/else` 在「两页」之间二分，系统返回键由本页的
 * [BackHandler] 接住。好处是本页完全不依赖导航层，也不需要改任何导航文件。
 *
 * ## 总开关（`DEV_MODE_ENABLED`）怎么门控
 *
 * 本页整体受开发者选项总开关控制（与页面里其它子项一致的口径）：
 * 总开关关闭时**整页内容置灰不可点**，但**照旧显示当前选中的图标**——
 * 「值还在、只是暂不生效」必须能自解释，否则用户会以为自己的选择被清空了
 * （这正是 `DeveloperOptionsScreen` KDoc 里那条踩过的坑）。
 *
 * 注意门控条件**只有总开关一个**：图标测试原来那个占位开关已被删除（需求：原占位开关删掉），
 * 图标更改就是该板块本身，不再有独立的启用开关。
 *
 * @param enabled 总开关是否开启。false 时整页置灰不可点（值照常显示）。
 * @param onBack 点返回箭头 / 按系统返回键时回调。
 * @param onMessage 页面级一次性提示（切换结果、保存结果等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerScreen(
    enabled: Boolean,
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 与 `DeveloperOptionsScreen` 同一写法：必须 applicationContext + 进程内单例容器，
    // 拿 `LocalContext.current`（通常是 Activity）直接 as 会 ClassCastException。
    //
    // AppIconRepository **每次新建即可**：它不持有内存状态（读写都在 DataStore 与
    // PackageManager 里，两者都是进程级共享），理由详见该类 KDoc 第五节。
    val repository = remember(context) {
        val container = (context.applicationContext as MoreCheckApp).container
        AppIconRepository(context.applicationContext, container.settingsRepository)
    }

    val selectedIconId by repository.selectedIconId
        .collectAsStateWithLifecycle(initialValue = repository.defaultIconId)
    val customIconPath by repository.customIconPath
        .collectAsStateWithLifecycle(initialValue = null)

    // 待裁切的图片（用户在系统选择器里刚选中的）。非 null 即整页切到裁切流程。
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }

    // 正在切换内置图标（切 alias 要走 PackageManager，很快，但仍是平台调用）。
    var switching by remember { mutableStateOf(false) }

    // 选图用 PickVisualMedia（Android 13+ 原生照片选择器），与「我的信息 → 头像」同一套：
    // · **不需要任何存储权限**，低版本自动回退到 ACTION_OPEN_DOCUMENT，全平台一致；
    // · 对比 GetContent("image/*")，它在 13+ 上不给应用任何相册访问权，隐私上更干净。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // 用户取消时 uri 为 null，什么都不做（保持原样）。
        if (uri != null) pendingCropUri = uri
    }

    // 兜底自检：万一出现「一个内置图标 alias 都没启用」（应用在桌面上彻底消失），
    // 进本页时自动把默认套修回来。正常路径永远不会触发，但那个失败模式的代价太大，
    // 值得多花一次查询（详见 AppIconRepository.repairIfNoIconEnabled 的 KDoc）。
    LaunchedEffect(Unit) {
        if (repository.repairIfNoIconEnabled()) {
            onMessage("检测到桌面图标丢失，已恢复为默认图标")
        }
    }

    // 裁切流程整页覆盖本页（见 KDoc 的「就地覆盖」）。
    val cropUri = pendingCropUri
    if (cropUri != null) {
        CustomIconCropScreen(
            sourceUri = cropUri,
            repository = repository,
            onCancel = { pendingCropUri = null },
            onSaved = {
                pendingCropUri = null
                onMessage("自定义图标已保存（仅应用内显示）")
            },
            onFailed = { message ->
                pendingCropUri = null
                onMessage(message)
            }
        )
        return
    }

    // 系统返回键等同「返回开发者选项」，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图标更改") },
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
            // ── 卡片一：内置图标（真的换桌面图标）────────────────────────────
            IconSectionTitle("桌面图标（内置）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "选择一套内置图标替换桌面图标；切换后桌面可能需要几秒才刷新。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.strongTextColor
                    )

                    Spacer(Modifier.height(14.dp))

                    BuiltInIconRow(
                        iconIds = repository.builtInIconIds,
                        selectedId = selectedIconId,
                        // 切换中整排不可点：连点会发出多次 setComponentEnabledSetting，
                        // 中间态虽然安全（先启用后禁用），但界面反馈会乱跳。
                        enabled = enabled && !switching,
                        repository = repository,
                        onPick = { id ->
                            scope.launch {
                                switching = true
                                val result = repository.applyBuiltInIcon(id)
                                switching = false
                                // ⚠️ 必须依据返回值提示，不能忽略它。
                                //
                                // 原先这里虽然是 `val ok = ...`，但失败的两种情形
                                //（组件名对不上 / 平台拒绝）在仓库里都被压成了同一个 `false`，
                                // 界面只能显示「失败，请重试」——而其中一种重试多少次都一样。
                                // 现在 [IconApplyResult] 自带原因，直接显示出来即可。
                                onMessage(
                                    iconResultMessage(
                                        result = result,
                                        successText = "已切换为「${builtInIconLabel(id)}」，" +
                                            "桌面图标可能需几秒刷新"
                                    )
                                )
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // 「恢复默认」只切内置图标（不动自传图）——但**一键恢复默认**那个按钮
                    // 会两件一起做，见下面卡片三。这里给的是「只想改回默认外观」的场景。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            enabled = enabled && !switching,
                            onClick = {
                                scope.launch {
                                    switching = true
                                    val result =
                                        repository.applyBuiltInIcon(repository.defaultIconId)
                                    switching = false
                                    onMessage(
                                        iconResultMessage(
                                            result = result,
                                            successText = "已恢复默认图标"
                                        )
                                    )
                                }
                            }
                        ) {
                            Text("恢复默认图标")
                        }
                    }
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ── 卡片二：自定义图标（只用于应用内显示）────────────────────────
            IconSectionTitle("自定义图标（仅应用内显示）")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        // 这句是本页最要紧的说明：不写清「为什么桌面上没变」，
                        // 用户上传完一定会以为功能坏了。
                        text = "上传的图片会裁成正方形保存在应用内。受 Android 限制，" +
                            "自定义图片无法作为桌面图标（桌面图标必须是应用内置资源），" +
                            "因此这里上传的图只用于应用内显示。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.strongTextColor
                    )

                    Spacer(Modifier.height(14.dp))

                    CustomIconPreview(
                        customIconPath = customIconPath,
                        repository = repository
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Text(if (customIconPath == null) "选择图片" else "重新选择")
                        }
                        OutlinedButton(
                            // 没有自传图时不让点：一个必然无效的按钮比灰掉的按钮更让人困惑。
                            enabled = enabled && customIconPath != null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    repository.clearCustomIcon()
                                    onMessage("已清除自定义图标")
                                }
                            }
                        ) {
                            Text("清除")
                        }
                    }
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ── 卡片三：一键恢复默认 ────────────────────────────────────────
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                            .alpha(if (enabled && !switching) 1f else DISABLED_CONTENT_ALPHA)
                    ) {
                        Text(
                            text = "一键恢复默认",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.strongTextColor
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "把桌面图标切回默认，并清除已上传的自定义图片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    OutlinedButton(
                        enabled = enabled && !switching,
                        onClick = {
                            scope.launch {
                                switching = true
                                val result = repository.restoreDefault()
                                switching = false
                                onMessage(
                                    iconResultMessage(
                                        result = result,
                                        successText = "已恢复默认图标并清除自定义图片"
                                    )
                                )
                            }
                        }
                    ) {
                        Text("恢复默认")
                    }
                }
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // 页脚：只解释「为什么是实验性」这一条最需要预期管理的规则。
            Text(
                text = "图标更改属实验性功能：桌面图标的刷新时机由系统桌面决定，" +
                    "部分机型需要几秒、甚至重新进入桌面后才会更新，这是平台行为，应用无法控制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 内置图标的选择区：**每行三个、自动换行**。
 *
 * ## 为什么 v1.1.69 要从「一行铺满」改成换行
 *
 * 图标从 4 套增加到 9 套。原先的写法是 `Row` + 每项 `weight(1f)`，
 * 也就是**把可用宽度平均分给所有项**：4 项时每项约 74dp 正合适，
 * 9 项时每项只剩约 30dp——预览图会被压成指甲盖大小，
 * 选中的 2dp 描边把格子占掉一大半，标签（「橙色」「火焰」）直接换行溢出。
 * 所以这里按 [ICON_CELLS_PER_ROW] 切分成多个 `Row`。
 *
 * ## 为什么用 `chunked` 手写换行，而不是 `FlowRow`
 *
 * `FlowRow` 在本项目解析到的 `foundation` 里确实可用（1.7 起稳定），
 * 但它会**按测量结果**决定换行，格子宽度随内容浮动，同一批图标在不同字号 /
 * 不同字体缩放下会换出不整齐的行。固定列数 + 固定格宽在任何屏幕宽度与字体缩放下
 * 都得到同样的网格，且不引入任何新 API 依赖（本页要保证在无编译验证的情况下可读可查）。
 *
 * 每个格子给**固定宽度**而不是 `weight(1f)`：用 weight 时最后一行不满
 * （9 项按 3 列正好排满，但增删一套就会出现 3 + 3 + 2 这种行）
 * 会把最后几个格子拉宽，与上面几行对不齐，看着像排版错了。
 *
 * ⚠️ 本函数**完全由 `iconIds` 驱动**，因此增删图标套数不需要改这里——
 * 真正需要同步的是 `AppIconRepository.BUILT_IN_ICON_IDS`、
 * `builtInIconResOf` / `builtInIconLabel` 与 manifest 的 alias（见那几处的说明）。
 *
 * @param enabled 是否可点（总开关关闭 / 切换中时为 false）。
 */
@Composable
private fun BuiltInIconRow(
    iconIds: List<String>,
    selectedId: String,
    enabled: Boolean,
    repository: AppIconRepository,
    onPick: (String) -> Unit
) {
    val density = LocalDensity.current

    // 预览位图的边长（px）：直接用显示尺寸 `PREVIEW_SIZE` 换算成像素，
    // 而不是写死一个数。这样在任意屏幕密度上都恰好是「1:1 像素」渲染——
    // 位图不需要被缩放着画，边缘最干净；也不会在高密度屏上因为位图偏小而发虚。
    //
    // 内存：72dp@3x = 216px，一张 216×216 ARGB_8888 约 178KB，
    // 九张合计约 1.6MB，与改造前（4 张 96dp 约 1.3MB）基本持平，可忽略。
    val previewPx = with(density) { PREVIEW_SIZE.roundToPx() }

    // id → 渲染好的位图。用 Map 而不是「每套一个独立 state」：数量是数据驱动的
    // （`repository.builtInIconIds`），写死变量会在增删套数时漏改。
    var previews by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }

    LaunchedEffect(iconIds, previewPx) {
        // 刻意**不用** `buildMap { ... }`：查过 kotlin-stdlib 2.1.20 的字节码，
        // 它带着 `@WasExperimental(ExperimentalStdlibApi)` 与 `@BuilderInference`，
        // 在这种「先判 res、再 `?.let { put(...) }`」的写法里容易触发 opt-in 警告
        // （而本项目没有开 `-opt-in`，警告在 CI 上会变成噪声）。
        //
        // 改用最朴素的 `mapNotNull` + `toMap`：没有实验性 API、类型推导直接，
        // 代价只是多一次中间 List，而这里只有个位数项，完全无所谓。
        val rendered = iconIds
            .mapNotNull { id ->
                val res = builtInIconResOf(id)
                // res 为 0 表示代码与 manifest 不同步；此时跳过，
                // 界面上那一格会显示为空位而不是崩溃。
                if (res == 0) return@mapNotNull null
                repository.renderBuiltInIcon(res, previewPx)?.let { id to it }
            }
            .toMap()
        previews = rendered
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ICON_ROW_SPACING)
    ) {
        iconIds.chunked(ICON_CELLS_PER_ROW).forEach { rowIds ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ICON_COLUMN_SPACING)
            ) {
                rowIds.forEach { id ->
                    BuiltInIconCell(
                        id = id,
                        selected = id == selectedId,
                        enabled = enabled,
                        preview = previews[id],
                        onPick = onPick
                    )
                }
            }
        }
    }
}

/**
 * 单个图标格子：预览图 + 名字。
 *
 * 抽成独立可组合函数（而不是塞回上面那个两层 `forEach` 里）：
 * 两层循环 + 七八个参数全写在一个 lambda 里，缩进会把 `modifier` 链读成一段糊在一起的代码。
 *
 * @param preview 已渲染好的位图；null = 还在渲染 / 资源缺失（显示空位而不是破图）。
 */
@Composable
private fun BuiltInIconCell(
    id: String,
    selected: Boolean,
    enabled: Boolean,
    preview: Bitmap?,
    onPick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .width(ICON_CELL_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            // 选中态用主题色描边 + 加粗文字表达，**不新增颜色**：
            // 边框取 colorScheme.primary，底色取 surfaceContainerHigh（深浅色自动成立）。
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled) { onPick(id) }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(PREVIEW_SIZE)
                // 垫一层底色：自适应图标渲染出来的位图**遮罩之外是透明的**
                // （系统用 mask 路径裁掉了外圈），不垫色在深色 / 浅色下会透出页面底色，
                // 看着像图标缺了一块。
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(PREVIEW_SIZE),
                    // 位图已经是正方形、且已按自身比例铺满画布，Fit 不会再缩放。
                    contentScale = ContentScale.Fit
                )
            } else {
                // 渲染还没完成 / 资源缺失：留一个空位而不是显示破图。
                Spacer(Modifier.size(PREVIEW_SIZE))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = builtInIconLabel(id),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            // 名字都很短（2~3 个字），限定一行并居中：
            // 万一系统字体被调到最大、或将来加了更长的名字，也只截断这一行，
            // 不会把格子撑高导致整行图标高度不齐。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 自定义图标的预览位。
 *
 * 解码放 IO 线程（`remember` 的 lambda 在主线程跑，读磁盘会卡首帧），
 * 做法与 `PersonalInfoScreen` 解码头像完全一致。
 */
@Composable
private fun CustomIconPreview(
    customIconPath: String?,
    repository: AppIconRepository
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(customIconPath) {
        val path = customIconPath
        bitmap = if (path == null) null else repository.decodeCustomIcon(path)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(CUSTOM_PREVIEW_SIZE)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            val shown = bitmap
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "自定义图标预览",
                    modifier = Modifier.fillMaxSize(),
                    // 裁切导出的就是 1:1 方图，用 Crop 保证任何存储/解码误差下都不露白边。
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = if (customIconPath == null) "未设置" else "无法读取",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (bitmap != null) "已设置自定义图标" else "尚未设置自定义图标",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "裁切后保存为 ${repository.exportSize}×${repository.exportSize} 的正方形图片",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 裁切：自定义图片 → 512×512 正方形
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 1:1 正方形裁切页（**自定义图标专用**）。
 *
 * ## ⚠️ 为什么不复用现成的 `AvatarCropScreen`
 *
 * 那个页面「看起来」就能用，但它的保存路径是**写死**的：
 * `exportAndSave()` 最后调的是 `AvatarStore.saveAvatar(...)` + `AvatarStore.storedPath(...)`，
 * 也就是**覆盖用户的头像**。直接复用它会让「换个应用图标」这个动作把头像一起改掉——
 * 一个动作改错了另一个功能的数据，是最难排查的一类 bug。
 *
 * 所以这里另写一份，把「几何计算」与「导出」分开：几何部分与头像那套**逐行同构**
 * （同一组 `baseScale` / `userScale` / `offset`，见 [IconCropMath]），
 * 差别只有最后落盘的那一步换成 [AppIconRepository.saveCustomIcon]。
 * 刻意不做成「给 AvatarCropScreen 加一个保存回调参数」：那要改动 `ui/mine/` 下
 * 与本需求无关的文件，而本次任务的文件边界是明确划定的。
 *
 * ## 「所见即所得」怎么保证
 *
 * 与头像裁切页同一套办法：预览与导出**共用同一组 state**（`baseScale` / `userScale` / `offset`），
 * 导出矩阵只是把预览的屏幕坐标线性映射到输出画布，不存在第二套几何。
 * 预览阶段**一张图都不裁**，只做 graphicsLayer 的缩放平移（纯 GPU 变换，不需要每帧重建位图），
 * 真正读像素只发生在点「完成」的那一刻。
 *
 * @param sourceUri 用户选中的原图。
 * @param repository 保存落盘用（写私有目录 + 记 DataStore）。
 * @param onCancel 放弃裁切。
 * @param onSaved 保存成功。
 * @param onFailed 保存失败 / 无法解码，参数是给用户看的提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomIconCropScreen(
    sourceUri: Uri,
    repository: AppIconRepository,
    onCancel: () -> Unit,
    onSaved: () -> Unit,
    onFailed: (String) -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    var userScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 解码放 IO 线程：ImageDecoder 是同步且可能耗时的，放主线程会卡住首帧。
    LaunchedEffect(sourceUri) {
        val decoded = repository.decodeForCrop(sourceUri)
        bitmap = decoded
        decodeFailed = decoded == null
    }

    val framePx = minOf(viewport.width, viewport.height) * CROP_FRAME_RATIO

    BackHandler(onBack = onCancel)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("裁切图片") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        // 解码没完成 / 正在保存 / 解码失败时不允许保存，否则会写出空白图。
                        enabled = bitmap != null && !saving,
                        onClick = {
                            val source = bitmap ?: return@TextButton
                            saving = true
                            scope.launch {
                                val path = exportCustomIcon(
                                    repository = repository,
                                    source = source,
                                    baseScale = IconCropMath.baseScale(
                                        source.width, source.height, framePx
                                    ),
                                    userScale = userScale,
                                    offset = offset,
                                    framePx = framePx
                                )
                                saving = false
                                if (path != null) {
                                    onSaved()
                                } else {
                                    onFailed("保存失败，请重试")
                                }
                            }
                        }
                    ) {
                        Text("完成", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 裁切区用纯黑底：图片编辑页的惯例，也让取景框外的区域自然变暗。
                .background(Color.Black)
                .onSizeChanged { viewport = it }
        ) {
            val source = bitmap
            when {
                decodeFailed -> {
                    Text(
                        text = "无法读取这张图片，请换一张试试",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                source == null -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                viewport.width > 0 && framePx > 0 -> {
                    // 基准比例：把原图放大到**刚好填满**取景框，因此 userScale = 1 时框内不会露白。
                    val baseScale = IconCropMath.baseScale(source.width, source.height, framePx)

                    // 布局基准用 fitScale（图片**装进整个视口**），不是 baseScale。
                    //
                    // 为什么必须这样：`Modifier.size()` 会被父约束**夹住**，
                    // 按 baseScale 算出来的尺寸往往比视口大、会被 Compose 截断，
                    // 宽高各自被截的程度不同 → 图片压变形，且 graphicsLayer 的缩放是
                    // 相对于这个（已被截断的）布局尺寸，几何随之报废。
                    // fitScale 保证布局尺寸两个方向都不超过视口，永远不会被夹。
                    val fitScale = IconCropMath.fitScale(
                        source.width, source.height,
                        viewport.width.toFloat(), viewport.height.toFloat()
                    )
                    if (fitScale > 0f) {
                        val layoutWidthDp = with(density) { (source.width * fitScale).toDp() }
                        val layoutHeightDp = with(density) { (source.height * fitScale).toDp() }
                        val renderScale = baseScale * userScale / fitScale

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(source, framePx) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        // 先算新缩放并夹到 [1, MAX]，再夹平移——
                                        // 顺序不能反：缩放变化会改变可平移的余量。
                                        val nextScale = IconCropMath.clampScale(userScale * zoom)
                                        val nextOffset = IconCropMath.clampOffset(
                                            offset = offset + pan,
                                            bitmapWidth = source.width,
                                            bitmapHeight = source.height,
                                            effectiveScale = baseScale * nextScale,
                                            framePx = framePx
                                        )
                                        userScale = nextScale
                                        offset = nextOffset
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = source.asImageBitmap(),
                                contentDescription = null,
                                // 布局尺寸与位图等比，FillBounds 就是精确铺满，不会二次介入。
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier
                                    .size(layoutWidthDp, layoutHeightDp)
                                    // 与 [IconCropMath.exportMatrix] 共用同一组 userScale / offset。
                                    // graphicsLayer 默认绕布局中心缩放，而布局中心就是视口中心
                                    // （contentAlignment = Center），因此屏幕位移恰好等于 offset。
                                    .graphicsLayer {
                                        scaleX = renderScale
                                        scaleY = renderScale
                                        translationX = offset.x
                                        translationY = offset.y
                                    }
                            )
                        }
                    }

                    // 取景框 + 框外半透明遮罩 + 三分线，画在图片之上。
                    //
                    // 需求原文要求「一个正方形取景框 + 半透明遮罩」，因此这里**画四块遮罩**
                    // 把框外的画面压暗，而不是只描一个白框：
                    // 框内框外的对比让人一眼看出「哪一块会进入最终图片」，
                    // 也让用户拖动时能判断图片还够不够填满取景框（夹取逻辑保证不会露白）。
                    //
                    // 遮罩用半透明黑而不是不透明：仍能看清框外内容的走向，
                    // 拖动到边缘时不会变成"盲操作"。
                    Canvas(Modifier.fillMaxSize()) {
                        // 先把画布尺寸取到局部变量：下面的 `drawRect(size = ...)` 里
                        // `size` 既是参数名、又是 DrawScope 的属性名，混在一起极易看错，
                        // 命名成 `viewport` 之后"画在哪、画多大"一眼可读。
                        val viewportW = size.width
                        val viewportH = size.height
                        val left = (viewportW - framePx) / 2f
                        val top = (viewportH - framePx) / 2f
                        val scrim = Color.Black.copy(alpha = CROP_SCRIM_ALPHA)

                        // 上下两块
                        drawRect(
                            color = scrim,
                            topLeft = Offset(0f, 0f),
                            size = Size(viewportW, top)
                        )
                        drawRect(
                            color = scrim,
                            topLeft = Offset(0f, top + framePx),
                            size = Size(viewportW, viewportH - top - framePx)
                        )
                        // 左右两块（只覆盖取景框那一段高度，避免与上下两块重叠而叠加变深）
                        drawRect(
                            color = scrim,
                            topLeft = Offset(0f, top),
                            size = Size(left, framePx)
                        )
                        drawRect(
                            color = scrim,
                            topLeft = Offset(left + framePx, top),
                            size = Size(viewportW - left - framePx, framePx)
                        )

                        // 白色描边：2dp，压在遮罩上，明确框的边界。
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(left, top),
                            size = Size(framePx, framePx),
                            style = Stroke(width = 2.dp.toPx())
                        )

                        // 三分线：细一点、半透明，只作构图参考，不该抢过边框。
                        val gridColor = Color.White.copy(alpha = 0.35f)
                        val step = framePx / 3f
                        for (i in 1..2) {
                            val d = step * i
                            drawLine(
                                color = gridColor,
                                start = Offset(left + d, top),
                                end = Offset(left + d, top + framePx),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = gridColor,
                                start = Offset(left, top + d),
                                end = Offset(left + framePx, top + d),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                    }
                }
            }

            if (saving) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

/**
 * 导出并写盘。返回落盘路径；任一步失败返回 null。
 *
 * 抽成 `suspend` 函数而不是写在点击回调里：回调是同步的，而这里既要建 512×512 位图、
 * 又要做 JPEG 压缩、还要写 DataStore，都该离开主线程。
 *
 * **回收说明**：导出后的临时位图与源位图都**不主动 `recycle()`**。
 * 源位图马上还会被预览继续绘制，主动回收会撞上
 * "Canvas: trying to use a recycled bitmap" 崩溃；临时位图交给 GC 即可
 * （512×512 ARGB_8888 只有 1MB，压力可忽略）。宁可晚一点回收，也不要冒崩溃风险。
 */
private suspend fun exportCustomIcon(
    repository: AppIconRepository,
    source: Bitmap,
    baseScale: Float,
    userScale: Float,
    offset: Offset,
    framePx: Float
): String? {
    // 视口还没测量出来（framePx 为 0）时不能导出：k 会变成无穷大。
    if (framePx <= 0f) return null

    val outPx = repository.exportSize
    // 用全限定名：本文件同时 import 了 Compose 的 `Canvas`（画取景框的可组合函数），
    // 两者同名，不能同时 import，这里显式写出平台类避免歧义。
    val output = withContext(Dispatchers.Default) {
        val bmp = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val matrix = IconCropMath.exportMatrix(
            bitmapWidth = source.width,
            bitmapHeight = source.height,
            baseScale = baseScale,
            userScale = userScale,
            offset = offset,
            framePx = framePx,
            outPx = outPx
        )
        // FILTER_BITMAP_FLAG：缩放时做双线性插值，否则 512 输出会有明显锯齿。
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp
    }

    // 落盘 + 记 DataStore 都在仓库里完成（见 saveCustomIcon），
    // 因此这里拿到非 null 就代表"已生效"，不需要调用方再写一次设置。
    return repository.saveCustomIcon(output)
}

/**
 * 裁切的几何计算。**预览与导出共用这里的三个数**，是"所见即所得"的唯一来源。
 *
 * 坐标系约定：原点在取景框中心，向右为 +x、向下为 +y，单位 px。
 * 图片中心相对取景框中心的位移就是 `offset`。
 *
 * 与 `ui/mine/AvatarCropScreen` 里的 `CropMath` **逐条对应**（那段是 private，
 * 跨文件用不了）。这里保留一份同名同义的实现，而不是抽成公共工具：
 * 抽出去要改 `ui/mine/` 下与本次需求无关的文件，而两份几何都不复杂、
 * 又在注释里互相指认，比引入一个跨页面的公共模块代价小。
 */
private object IconCropMath {

    /**
     * 基准比例：让图片**填满**取景框。
     *
     * 取长边（`max`）而不是短边：若按短边算，另一方向会不足、框内露白；
     * 按长边算则两个方向都 >= 取景框，缩放后的边长恒 >= 取景框边长，因此
     * `userScale = 1` 就是"恰好填满"的下限。
     */
    fun baseScale(bitmapWidth: Int, bitmapHeight: Int, framePx: Float): Float {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || framePx <= 0f) return 1f
        return maxOf(framePx / bitmapWidth, framePx / bitmapHeight)
    }

    /**
     * 布局比例：把图片**完整装进视口**（两条边都不超出），即 `min` 而非 `max`。
     *
     * 它是 `Image` 实际布局尺寸的计算依据，存在的理由是**规避父约束夹取**（见调用处注释）。
     */
    fun fitScale(bitmapWidth: Int, bitmapHeight: Int, viewW: Float, viewH: Float): Float {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewW <= 0f || viewH <= 0f) return 0f
        return minOf(viewW / bitmapWidth, viewH / bitmapHeight)
    }

    /** 用户缩放倍数夹取：下限 1（再小就露白），上限 [CROP_MAX_USER_SCALE]。 */
    fun clampScale(scale: Float): Float = scale.coerceIn(1f, CROP_MAX_USER_SCALE)

    /**
     * 平移夹取。
     *
     * 图片半边长减去取景框半边长，就是**允许的最大位移**：超过它，
     * 图片的某条边就会滑进取景框内、露出背景。
     * `coerceAtLeast(0f)` 兜住"图片比框还小"的退化情形（正常不会出现，
     * 因为 [baseScale] 保证了至少相等）。
     */
    fun clampOffset(
        offset: Offset,
        bitmapWidth: Int,
        bitmapHeight: Int,
        effectiveScale: Float,
        framePx: Float
    ): Offset {
        val halfImageW = bitmapWidth * effectiveScale / 2f
        val halfImageH = bitmapHeight * effectiveScale / 2f
        val maxX = (halfImageW - framePx / 2f).coerceAtLeast(0f)
        val maxY = (halfImageH - framePx / 2f).coerceAtLeast(0f)
        return Offset(
            x = offset.x.coerceIn(-maxX, maxX),
            y = offset.y.coerceIn(-maxY, maxY)
        )
    }

    /**
     * 导出矩阵：把取景框内的画面线性映射到 `outPx × outPx` 的输出位图。
     *
     * 推导（与预览严格同源）：
     * 预览里，位图坐标 `p` 落到屏幕上的位置是
     *   `screen(p) = frameCenter + offset + (p - bitmapCenter) * effectiveScale`
     * 而 k = outPx / framePx 就是「屏幕像素 → 输出像素」的比例，于是
     *   `out(p) = outCenter + (screen(p) - frameCenter) * k`
     *          = outCenter + offset * k + (p - bitmapCenter) * effectiveScale * k
     *
     * 因此矩阵 = 缩放 `effectiveScale * k`，再平移让位图中心落到 `outCenter + offset * k`。
     * **用的是与 `graphicsLayer` 完全相同的 `userScale` / `offset`**，所以框里看到的就是导出的那一块。
     */
    fun exportMatrix(
        bitmapWidth: Int,
        bitmapHeight: Int,
        baseScale: Float,
        userScale: Float,
        offset: Offset,
        framePx: Float,
        outPx: Int
    ): Matrix {
        val effectiveScale = baseScale * userScale
        val k = outPx / framePx
        val scale = effectiveScale * k
        val outCenter = outPx / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                outCenter + offset.x * k - bitmapWidth / 2f * scale,
                outCenter + offset.y * k - bitmapHeight / 2f * scale
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 常量与映射
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 把 [IconApplyResult] 翻成一句给用户看的提示。
 *
 * 抽成一个私有函数（而不是在三个点击回调里各写一遍 `when`）：
 * 本页有三处会切换内置图标（选某一套 / 恢复默认图标 / 一键恢复默认），
 * 三处的成功文案不同但**失败语义完全一致**，各写一遍必然出现
 * 「一处显示了原因、另两处还在说请重试」。文案只有一个来源，改起来也只看一处。
 *
 * ## 四类结果为什么**都要**给出不同的话
 *
 * - [IconApplyResult.Success]：用调用方给的 [successText]（三处各不相同）；
 * - [IconApplyResult.Partial]：**不算失败**，但必须把「可能多出一个图标」讲出来——
 *   否则用户看到桌面上多出一个旧图标，会以为切换把图标弄坏了；
 * - [IconApplyResult.FellBackToDefault]：用户点的 id 仓库不认识，
 *   实际切到了默认套。**不能**冒充成功说「已切换为「XX」」，
 *   那会让用户以为是自己点的那套生效了；
 * - [IconApplyResult.Failed]：直接显示仓库给的原因（内含平台异常信息）——
 *   这正是 v1.1.69 要的效果：用户能把这句话贴给我，而不是只有「失败，请重试」。
 *
 * @param successText 仅用于 [IconApplyResult.Success] 的成功文案。
 */
private fun iconResultMessage(result: IconApplyResult, successText: String): String =
    when (result) {
        // 两个无参结果是 `data object`，用等值匹配即可；
        // 带参数的两个必须用 `is` 才能把 `reason` 智能转换成 String。
        IconApplyResult.Success -> successText

        IconApplyResult.FellBackToDefault -> "未识别的图标选项，已切回默认图标"

        is IconApplyResult.Partial -> result.reason

        is IconApplyResult.Failed -> "图标切换失败：${result.reason}"
    }

/**
 * 内置图标 id → 图标的 `@mipmap` 资源。
 *
 * ## 为什么这个映射在**界面层**而不是数据层
 *
 * `R` 是界面资源的索引，把它引进 `data/` 会让数据层反向依赖界面资源
 * （本项目在 `themeAccent` 那里已经确立了「数据层只存字符串、不引用界面类型」的口径）。
 * 所以 `AppIconRepository` 只知道「有哪几套、当前选中哪一套」，
 * 「每套长什么样」由界面决定。
 *
 * ## 为什么不会有「资源不存在」的漏配
 *
 * 这几个都是 `res/mipmap-anydpi-v26` 目录下的 `xml` 自适应图标。本项目 **minSdk = 29**，
 * 而 `-v26` 限定的资源在 API 26+ 一律命中，所以不需要为低版本准备 PNG 兜底
 * （若哪天 minSdk 降到 26 以下，这里就**必须**补 `mipmap-*dpi` 的位图版本，
 * 否则老系统上会找不到资源而崩）。
 *
 * ## ⚠️ 本函数与 `AppIconRepository.BUILT_IN_ICON_IDS`、manifest 的 alias **三处必须同步**
 *
 * 前者决定"有哪几套、以什么顺序显示"，本函数决定"每套画出来是什么"，
 * manifest 决定"系统能不能真的切过去"。三者漏配**任何一处都不会崩**，
 * 而是表现为某一格空白 / 点了没反应——所以增删图标时必须一起改
 * （完整步骤见 `AppIconRepository` 类注释第七节）。
 *
 * 返回 0 表示这个 id 没有对应资源（调用方据此显示空位而不是崩溃）——
 * 正常情况下不会发生，它是「代码与 manifest 不同步」时的**防御**，不是正常分支。
 */
@DrawableRes
private fun builtInIconResOf(id: String): Int = when (id) {
    "default" -> R.mipmap.ic_launcher
    "blue" -> R.mipmap.ic_launcher_blue
    "purple" -> R.mipmap.ic_launcher_purple
    "orange" -> R.mipmap.ic_launcher_orange
    // v1.1.69 新增四套：各自一个手绘矢量前景 + 一个深色背景（见 values/colors.xml）。
    "check" -> R.mipmap.ic_launcher_check
    "star" -> R.mipmap.ic_launcher_star
    "flame" -> R.mipmap.ic_launcher_flame
    "leaf" -> R.mipmap.ic_launcher_leaf
    // 第九套（v1.1.69）：复用「一个木函」的品牌 Logo 位图前景，
    // 背景刻意用深青（`#004D40`）——树本体近白，配白底实测几乎看不见。
    // 来源、授权边界与此处背景色的理由见 `res/mipmap-anydpi-v26/ic_launcher_tree.xml`。
    "tree" -> R.mipmap.ic_launcher_tree
    else -> 0
}

/**
 * 内置图标 id → 界面上显示的名字。
 *
 * 「default」显示为「默认」而不是「黑色」：这套图标的背景确实是黑的，
 * 但它的身份是**改造前的原始图标**（「恢复默认」要去的地方），
 * 叫「黑色」会让用户以为它只是四种颜色之一，弱化了「这是默认值」这件事。
 *
 * 新增四套一律用**图形名**（对勾 / 星标 / 火焰 / 叶片）而不是颜色名：
 * 它们的背景色各异，而用户是照着"哪张图"在选的；用颜色命名会出现
 * 「对勾」被叫成「青色」这种"图与名对不上"的别扭。
 * 「tree」显示为「杉树」而不是「木函」：用户是在挑**自己桌面上要显示什么图案**，
 * 图案是树；把第三方应用的名字摆到图标选择器里对用户没有帮助。
 *
 * `else -> id` 是**兜底而不是死分支**：`BUILT_IN_ICON_IDS` 里若加了新 id
 * 而这里忘了补，界面会显示原始的英文 id（一眼能看出漏配），而不是空白。
 */
private fun builtInIconLabel(id: String): String = when (id) {
    "default" -> "默认"
    "blue" -> "蓝色"
    "purple" -> "紫色"
    "orange" -> "橙色"
    "check" -> "对勾"
    "star" -> "星标"
    "flame" -> "火焰"
    "leaf" -> "叶片"
    "tree" -> "杉树"
    else -> id
}

/**
 * 分区小标题。
 *
 * 与 `DeveloperOptionsScreen.DeveloperSectionTitle` 同一套视觉语言
 * （labelLarge + onSurface 压到 75%）。**函数名带 `Icon` 前缀**：
 * 本文件与 `DeveloperOptionsScreen.kt` 同属 `com.morecheck.ui.lab` 包，
 * 顶层 private 函数**同名也会在编译期报 Conflicting overloads**，
 * 因此同包内的顶层函数一律起专属名字（这正是 `DeveloperSectionTitle` 当初避坑的原因）。
 */
@Composable
private fun IconSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 失效（总开关关闭）时内容的不透明度。
 *
 * 取 0.38f，与 `DeveloperOptionsScreen.DISABLED_CONTENT_ALPHA` 同值：
 * 这是 Material 规范里"禁用态内容"的标准系数，Material3 的 `Switch` / `Button`
 * 内部调淡用的也是同一档，对齐之后整页的灰是同一个灰。
 * 只乘在主题色上（合成期系数），因此**不产生新的颜色值**，深浅色模式自动成立。
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/** 两个板块之间的垂直间距，与开发者选项页的 `SECTION_SPACING` 同值（同一节奏）。 */
private val SECTION_SPACING = 20.dp

/**
 * 内置图标预览的显示尺寸（正方形）。
 *
 * v1.1.69 从 96dp 收到 **72dp**：图标由 4 套增加到 9 套，改成「一行三个」的网格后，
 * 96dp 的格子加上内边距在窄屏（320dp）上会挤不下三个。72dp 仍然远大于桌面上
 * 真实的图标显示尺寸（通常 48~64dp），预览的辨认度没有损失。
 */
private val PREVIEW_SIZE = 72.dp

/**
 * 图标选择网格：每行几个。
 *
 * 取 3 是**屏幕宽度与格子辨识度之间的折中**：9 套图标按 3 列正好排满 3 行，
 * 页面上占的高度约 3 行 × 110dp，与原来 4 套挤在一行时的观感相当；
 * 若取 2 列则要 5 行、卡片明显变长，取 4 列则格子只有约 60dp、
 * 预览图小到看不出「对勾」和「叶片」的区别（这两套的辨识度正是本需求的重点）。
 */
private const val ICON_CELLS_PER_ROW = 3

/**
 * 单个图标格子的宽度。
 *
 * 用**固定宽度**而不是 `weight(1f)`：按 3 列排时最后一行常常不满
 * （例如 8 套 → 3 + 3 + 2），用 weight 会把不满那行的格子拉宽、
 * 与上面几行对不齐，看着像排版错了。
 * 76dp 的算法：320dp 的窄屏可用宽度约 288dp（左右各 16dp 页面内边距 + 卡片 16dp 内边距）
 * 减去两个 10dp 间距，除以 3 约等于 89dp；这里取 76dp **留出余量**，
 * 于是它在任何常见屏宽上都放得下，而格子里 72dp 的预览图正好居中、两侧各留 2dp。
 */
private val ICON_CELL_WIDTH = 76.dp

/** 网格的行间距。 */
private val ICON_ROW_SPACING = 10.dp

/** 网格的列间距。与行间距同值，网格看起来才是"等距"的。 */
private val ICON_COLUMN_SPACING = 10.dp

/** 自定义图标预览的显示尺寸。比内置的小一档，突出它只是「应用内显示」。 */
private val CUSTOM_PREVIEW_SIZE = 72.dp

/**
 * 取景框边长占视口短边的比例。
 *
 * 取 0.82，与头像裁切页同值：留出边距，让用户看得见框外的画面（否则等于没有裁切）。
 */
private const val CROP_FRAME_RATIO = 0.82f

/**
 * 取景框外遮罩的不透明度。
 *
 * 取 0.6：足够把框外压暗到"一眼看出不算数"，又仍能看清框外内容的走向——
 * 拖动到边缘时不会变成盲操作（这正是需求要"半透明"而不是纯黑的原因）。
 */
private const val CROP_SCRIM_ALPHA = 0.6f

/**
 * 用户缩放倍数上限。
 *
 * 下限恒为 1（见 [IconCropMath.clampScale]）：1 恰好等于「图片刚好填满取景框」，
 * 再小就会在框里露出空白。上限 8 倍足够看清细节，又不至于让图糊成一片。
 */
private const val CROP_MAX_USER_SCALE = 8f
