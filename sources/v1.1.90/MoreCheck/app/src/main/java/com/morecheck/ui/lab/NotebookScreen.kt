package com.morecheck.ui.lab

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morecheck.data.NotebookStore
import com.morecheck.ui.common.FullScreenImageViewer
import com.morecheck.ui.common.asImageBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「实验室 → 记事本」。
 *
 * 需求：**一条**笔记，内容 = 文字 + 图片，返回时自动保存（没有"保存"按钮）。
 *
 * 版式照实验室其它二级页：`Scaffold` + `TopAppBar` + 左上返回箭头 + 卡片。
 * 颜色全部取自 [MaterialTheme]，因此跟随深色模式与主题色，**不新增任何颜色常量**。
 *
 * ## 数据存在哪
 *
 * 文字与图片路径都交给 [NotebookStore]（它用**自己的** DataStore 文件
 * `datastore/notebook.preferences_pb`；为什么不复用设置那一个文件，见该文件顶部注释——
 * 那是一条会直接崩溃的硬约束，不是风格选择）。
 *
 * ## 自动保存是怎么做的（本页最容易出错的地方）
 *
 * 需求明确"不要保存按钮，返回时自动保存"，而落盘是 `suspend`（DataStore 要写磁盘），
 * "返回"却是**同步**回调。这里把三件事分开处理：
 *
 * 1. **打字过程中**：`LaunchedEffect(text, initialised)` 里 `delay` 一段时间再落盘。
 *    `LaunchedEffect` 的 key 一变就**取消上一次**再重启，于是"连续打字只写最后一版"，
 *    天然就是防抖——不需要额外引入 `snapshotFlow` + `debounce`（后者带
 *    `@FlowPreview`，还要多一层 `collect`）。效果是：打字本身不触发高频磁盘写。
 *
 * 2. **返回时**：箭头、系统返回键/手势、以及"被上层销毁"三条路径统一走
 *    [NotebookStore.saveTextAsync]。它把写操作提交到**进程级**作用域，
 *    因此**不会**随着本页被销毁而取消（见第 3 点），提交完立刻导航，界面不等磁盘往返。
 *
 * 3. ⚠️ **为什么不能用 `rememberCoroutineScope()` 去保存**：那个作用域绑定在组合上，
 *    本页一离开组合就被取消。`onBack()` 一旦先执行，随后 `launch` 的写操作会在真正落盘前
 *    被取消——这正是"丢最后一次编辑"的经典成因。反过来先 `launch` 再在协程里 `onBack()`，
 *    虽能保住数据，却让"点返回"要等一次磁盘往返才响应。
 *    `NotebookStore` 的进程级作用域两头都不欠：导航立即生效，写操作一定跑完。
 *
 * 4. `DisposableEffect(Unit) { onDispose { ... } }` 兜住"不是从本页返回、
 *    而是上层把本页整体销毁"的情况（例如 Activity 重建）。**它必须配合
 *    [rememberUpdatedState] 读取正文**：`DisposableEffect(Unit)` 的 lambda 只在首次组合时
 *    捕获一次变量，若直接读 [text]，`onDispose` 里拿到的会是**首次组合时的空串**，
 *    于是"退出时把笔记清空"——一个把用户内容抹掉的事故。
 *
 * ### 会不会丢最后一次编辑
 *
 * **正常退出都不会丢。** 点箭头、按系统返回键、手势返回：`finish()` 先把当前正文提交到
 * 进程级作用域再导航；被上层销毁：`onDispose` 同样提交。两条路径写的是同一个最新值，
 * 重复写也只是幂等的重复覆盖。
 *
 * 唯一的理论窗口是：**用户在防抖间隔内（≤ [TEXT_DEBOUNCE_MS]）打完最后一个字，
 * 进程随即被系统杀掉**（不是退出应用，是进程被杀），此时那次按键还没被任何一次落盘覆盖到。
 * 这是"不在每次按键都写磁盘"必然要接受的取舍——每敲一个字就写一次 DataStore
 * 会让输入明显发涩，代价远大于这个极小窗口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 选图与"添加图片"的耗时工作在本页存活期间进行，用组合作用域即可
    // （用户此刻还在页面上，不存在"边退出边写"的问题；退出路径另见 finish()）。
    val scope = rememberCoroutineScope()

    // 正文用 rememberSaveable：屏幕旋转 / 进程重建后不丢正在敲的字。
    // 这一点是**必须**的——正文有 [TEXT_DEBOUNCE_MS] 的防抖，盘上可能还落后于输入框里的内容。
    var text by rememberSaveable { mutableStateOf("") }

    // 图片列表**不用** rememberSaveable，每次重新进入本页都从盘上读回。
    //
    // 理由：每次增删图片都会立即落盘（见 [NotebookStore.commitImagesAsync]），
    // 因此盘上**永远**是最新值，直接读回来既正确又免去一份要同步的副本。
    // 顺带避开一个真实的坑：`rememberSaveable` 的默认 autoSaver 依赖值本身能进 Bundle，
    // `List<String>` 的实现类（`listOf` 返回的并不是 `ArrayList`）不保证满足这一点，
    // 一旦不满足就是运行时崩溃；不存它则完全没有这个问题。
    var imagePaths by remember { mutableStateOf(listOf<String>()) }

    /**
     * 正文是否已"可信"——即**当前 [text] 的值可以安全地写回盘上**。
     *
     * 只有两种情况为 true：
     * 1. 已成功从盘上读到笔记（此时 text 就是盘上那一份）；
     * 2. 用户已经动过输入框（此时 text 是用户的新内容，当然该存）。
     *
     * 它存在的唯一目的是防住"**读失败时把笔记清空**"：
     * 读失败时 text 还是初始空串、[initialised] 为 false，
     * 于是防抖落盘与退出保存**都不会执行**，盘上那份笔记原样保留。
     * 若没有这个闸门，读失败后无论用户有没有输入，退出时都会把空串写回去——
     * 一次读盘抖动就变成了真正的数据丢失。
     *
     * 它**可保存**：旋转 / 重建后仍保持"已可信"，不会因为重新组合而回退成 false，
     * 从而避免重建后突然不再保存。
     */
    var initialised by rememberSaveable { mutableStateOf(false) }

    // 一次性的失败提示（图片读取/保存失败）。不持久化：它只是当下的反馈。
    var hint by remember { mutableStateOf<String?>(null) }

    // 最近一次**已经写进盘上**的正文。用来跳过"值没变"的那些落盘尝试。
    //
    // 它必须是普通 `remember`（不参与 Bundle 保存）：重建后回到 null 顶多多写一次，
    // 而把它塞进 Bundle 等于同一份正文在内存里存两份，得不偿失。
    var lastSaved by remember { mutableStateOf<String?>(null) }

    // ---- 首次进入时读回那一条笔记 ----
    //
    // 图片**无条件**从盘上读回（每次增删都已落盘，盘上就是最新值）；
    // 正文只在"本次进入尚未初始化"时读回，避免覆盖 rememberSaveable 恢复出来的输入。
    //
    // note 为 null 表示**读失败**（DataStore 的 data 流会抛 IOException）。
    // 此时刻意"什么都不做"：既不覆盖正文，也不让下面的防抖落盘启动——
    // 读失败时若回落到空字符串并照常落盘，就等于把用户真正的笔记清空了。
    // 用户仍可正常输入并保存（[initialised] 置位后防抖照常工作）。
    LaunchedEffect(Unit) {
        val note = NotebookStore.read(context) ?: return@LaunchedEffect
        imagePaths = note.imagePaths
        if (!initialised) {
            text = note.text
            // 记下"盘上就是这个值"，避免防抖那段立刻把同样的内容再写一遍。
            lastSaved = note.text
            initialised = true
        }
    }

    // ---- 打字防抖落盘 ----
    //
    // initialised 之前不写：第一次进入时 text 还是初始空串，
    // 若在这里写就会把盘上已有的笔记清掉（读到内容之前不要覆盖）。
    //
    // text == lastSaved 时直接返回：DataStore 的 edit 即使写的是同样的值也会重写整个文件，
    // 这一层能让"刚进来还没打字就退出"完全不产生写盘。
    LaunchedEffect(text, initialised) {
        if (!initialised) return@LaunchedEffect
        if (text == lastSaved) return@LaunchedEffect
        delay(TEXT_DEBOUNCE_MS)
        NotebookStore.saveText(context, text)
        lastSaved = text
    }

    // ---- 返回：统一入口 ----
    //
    // 用 rememberUpdatedState 持有"当前最新值"：下面三个回调（箭头、BackHandler、
    // onDispose）都可能在这一个组合实例存活期间被执行任意多次，
    // 若闭包直接捕获 text，拿到的会是注册那一刻的旧值。
    val latestText by rememberUpdatedState(text)
    val latestOnBack by rememberUpdatedState(onBack)

    // 保存正文后返回。文字用异步版本（见类注释第 3 点）；
    // 图片不需要在这里补写——每次增删都已即时落盘（见 NotebookStore.commitImagesAsync）。
    //
    // `if (latestInitialised)` 是"读失败保护"的最后一道闸（见 initialised 的说明）：
    // 没读到、用户也没输入时，退出**不能**把空串写回去，否则读失败就变成清空笔记。
    val latestInitialised by rememberUpdatedState(initialised)
    val finish: () -> Unit = {
        if (latestInitialised) NotebookStore.saveTextAsync(context, latestText)
        latestOnBack()
    }

    // 系统返回键 / 手势返回也要保存，不能只依赖左上角箭头。
    // 本页注册的 BackHandler 比上层（MoreCheckApp 里那个）更靠内，因此优先生效。
    BackHandler { finish() }

    // 兜住"被上层销毁"（例如 Activity 重建、导航图整体替换）这条不经过 finish() 的路径。
    // 必须走 rememberUpdatedState，理由见类注释第 4 点。
    DisposableEffect(Unit) {
        onDispose {
            if (latestInitialised) NotebookStore.saveTextAsync(context, latestText)
        }
    }

    // ---- Photo Picker ----
    //
    // 与「我的信息」的头像同一套做法：PickVisualMedia 系列**不需要任何存储权限**，
    // Android 13+ 走系统照片选择器，低版本由框架回退到 ACTION_OPEN_DOCUMENT，全平台一致。
    // 对比 GetContent("image/*")，它在 13+ 上不给应用任何相册访问权，隐私上更干净。
    //
    // 多选契约的 maxItems 必须 > 1（解包 activity 1.10.1 的字节码确认：
    // 构造时会 `require(maxItems > 1)`，否则直接 IllegalArgumentException），
    // 所以这里传图片上限 [NotebookStore.MAX_IMAGES]（当前为 9）；
    // 即使只想要一张也不能传 1。
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(NotebookStore.MAX_IMAGES)
    ) { uris ->
        // 用户取消时返回空列表，什么都不做。
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = mutableListOf<String>()
            var failed = false
            for (uri in uris) {
                // 还差几张就只处理几张：选择器本身已按上限约束，这里再兜一层，
                // 避免"上限被绕过"时把多余的文件也写进私有目录（写进去就没人回收了）。
                if (imagePaths.size + added.size >= NotebookStore.MAX_IMAGES) break
                val path = NotebookStore.addImage(context, uri)
                if (path == null) failed = true else added.add(path)
            }
            if (added.isNotEmpty()) {
                val updated = ArrayList(imagePaths).apply { addAll(added) }
                imagePaths = updated
                // 图片**即时落盘**（不像文字那样等返回）：选完图就已经产生了实体文件，
                // 越早让路径与文件对齐越安全，也让界面重建后能恢复出正确的列表。
                NotebookStore.commitImagesAsync(context, updated)
            }
            hint = when {
                failed && added.isEmpty() -> "图片读取失败，换一张试试"
                failed -> "有图片读取失败，已添加其余 ${added.size} 张"
                else -> null
            }
        }
    }

    // ---- 缩略图解码（在 IO 线程，不在组合里同步解码）----
    //
    // 与 PersonalInfoScreen 的头像同一写法：用 LaunchedEffect + 状态持有，
    // 而不是 remember(path) { decode(path) }——后者的 lambda 跑在**主线程**，
    // 每次列表变化都会卡一下首帧。
    var thumbs by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    LaunchedEffect(imagePaths) {
        val paths = imagePaths
        thumbs = withContext(Dispatchers.IO) { paths.map { NotebookStore.decodeThumb(it) } }
    }

    // ---- 一次性 UI 状态（v1.1.68 需求 4）----

    // 正在全屏预览的图片下标；null = 没在预览。
    //
    // 存**下标**而不是路径：删除时列表会变，而预览层在删除后必须立刻关闭
    //（否则会停在一张已经不存在的图上）；存下标时删除后判 `index >= size` 即可兜住。
    var previewIndex by remember { mutableStateOf<Int?>(null) }

    // 待确认删除的图片下标（长按触发）。null = 没有待确认的删除。
    var pendingDelete by remember { mutableStateOf<Int?>(null) }

    // 右上角「三个点」菜单的展开状态。
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记事本") },
                navigationIcon = {
                    // 返回箭头走同一个 finish()：与系统返回键行为完全一致，
                    // 不存在"点箭头保存了、按返回键没保存"这种分叉。
                    IconButton(onClick = finish) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // ── 「添加图片」移到标题栏（v1.1.68 需求 3）────────────────
                    //
                    // 原先它是正文区底部一个大按钮。移到标题栏后正文区腾出一整块高度，
                    // 输入框能更长；同时它从"一次性的动作"变成随时可点的常驻入口。
                    //
                    // 顺序要求：**「添加图片」在左、「三个点」在右**（需求原话）。
                    // `actions` 的 `Row` 按声明顺序从左到右排，所以先写添加图片即可。
                    //
                    // 到上限后禁用：让"为什么加不进去"在按钮上自解释，不用弹提示打扰
                    //（与移动前完全一致的口径）。
                    IconButton(
                        onClick = {
                            hint = null
                            pickImages.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        enabled = imagePaths.size < NotebookStore.MAX_IMAGES
                    ) {
                        Icon(
                            // ⚠️ 用 `Icons.Filled.AddPhotoAlternate`，**已核实存在**（v1.1.69 需求 4）。
                            //
                            // 需求要求"相册 + 加号"语义，首选就是它。本项目立过规矩：
                            // 图标必须**核实**存在，不能凭印象写。核实方式与结论：
                            // 解包本项目 Compose BOM 2025.03.00 实际解析到的
                            // `material-icons-extended-android` **1.7.8**
                            // （`...\androidx.compose.material\material-icons-extended-android\1.7.8\
                            // 7499b6142a798c77a178b1098319c0b90595ab84\material-icons-extended-release.aar`
                            // → 解出的 `classes.jar`），
                            // 其中确有条目
                            // `androidx/compose/material/icons/filled/AddPhotoAlternateKt.class`。
                            // 因此无需回退到 `AddToPhotos` / `Outlined.PhotoLibrary`。
                            //
                            // ## 与上一版「用 core 图标」的注释如何取舍
                            //
                            // 上一版这里用 `Icons.Filled.Add`（core），理由是"不想把按钮绑在
                            // `material-icons-extended` 上，万一将来有人去掉 extended 去瘦身，
                            // 这里会静默编译失败"。本轮需求明确要求换成"相册 + 加号"语义的图标，
                            // 该语义**只存在于 extended**，所以那条保守取舍被需求覆盖：
                            // 本模块的 `build.gradle.kts` 第 116 行确实声明了
                            // `implementation(libs.androidx.material.icons.extended)`，
                            // 且同一文件里的 `Icons.Filled.Download`（新增的"保存到相册"按钮）
                            // 同样来自 extended——本页已经依赖它，这一点没有新增风险。
                            // 若日后真要移除 extended，这两个图标会**编译期**报错（而非静默失效），
                            // 与上一版注释里担心的情况一致，届时按编译错误换图标即可。
                            imageVector = Icons.Filled.AddPhotoAlternate,
                            // 无障碍标签**不跟着图标改**：用户听到的是"添加图片"这个动作，
                            // 而不是"这张图标长得像相册加号"，保持原样（需求明确要求）。
                            contentDescription = "添加图片"
                        )
                    }

                    // ── 右上角「三个点」溢出菜单（v1.1.68 需求 2 的入口）──────
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("悬浮窗") },
                                onClick = {
                                    menuOpen = false
                                    // 快照**取界面上的实时值**（`text` / `imagePaths`），
                                    // 而不是让悬浮窗自己去读 DataStore。
                                    //
                                    // 原因：正文有 [TEXT_DEBOUNCE_MS] 的防抖，用户刚敲的字
                                    // 可能还没落盘；若由 Service 读盘，小窗里会**少最后一句**
                                    // ——这是用户最容易发现、也最不能接受的错误。
                                    // 这里 `text` 就是屏幕上正在显示的内容，必然准确。
                                    // 图片在添加时就已即时落盘，内存里的 `imagePaths` 同样是最新值。
                                    hint = NotebookOverlay.open(context, text, imagePaths)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 键盘弹出时把内容顶上去：正文框占满中段，不留内边距的话
                // 会把已选缩略图整块盖在键盘下面。
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ---- 正文：占满中段，自动换行 ----
            OutlinedTextField(
                value = text,
                // 用户一动输入框，text 就是"用户自己的内容"，此后写回盘上永远是安全的
                // （见 initialised 的说明：它挡的是"读失败时拿空串覆盖"那一种情况）。
                onValueChange = {
                    text = it
                    initialised = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("记点什么…") },
                // 不限行数、自动换行（默认就是多行）；不设 maxLines，让内容自己撑开。
                singleLine = false,
                shape = MaterialTheme.shapes.medium
            )

            Spacer(Modifier.height(12.dp))

            // ---- 已添加的图片：横向排列 ----
            //
            // 交互（v1.1.68 需求 4）：**点一下看大图**、**长按删除**（带二次确认）。
            // 原先右上角那个小叉已移除——它太小、且与"点开看大图"的手势区域重叠。
            if (imagePaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 只看下标：点击/长按都只需要"第几张"，路径由 `NotebookThumb` 外面
                    // 通过 `thumbs` / `imagePaths` 按下标取，所以这里不需要 `path` 本身
                    //（写成 `_` 而不是留一个未使用的名字，避免"这里到底有没有用到它"的歧义）。
                    imagePaths.forEachIndexed { index, _ ->
                        NotebookThumb(
                            bitmap = thumbs.getOrNull(index),
                            onClick = { previewIndex = index },
                            onLongClick = { pendingDelete = index }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- 状态行：张数 + 一次性失败提示 ----
            // 张数常显（"最多几张、已经几张"是添加图片时唯一的约束）；
            // 失败提示只在真的失败时出现，避免这里长期挂着一句话。
            Text(
                text = hint
                    ?: "已添加 ${imagePaths.size}/${NotebookStore.MAX_IMAGES} 张图片",
                style = MaterialTheme.typography.labelSmall,
                color = if (hint != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // ---- 全屏预览（点击缩略图）----
    previewIndex?.let { index ->
        // 列表可能在预览期间变化（例如从这里长按删除），越界就直接关掉，
        // 而不是硬取 `imagePaths[index]` —— 那会抛 IndexOutOfBounds。
        val path = imagePaths.getOrNull(index)
        if (path == null) {
            previewIndex = null
        } else {
            ImagePreviewDialog(
                path = path,
                onDismiss = { previewIndex = null },
                // 「保存到相册」（v1.1.69 需求 3）。
                //
                // 用页面既有的 `hint` 反馈（本文件此前所有"图片读取失败"之类的一次性提示
                // 都走它，见状态行那句 `Text(text = hint ?: ...)`），**不引入 Snackbar**：
                // 本页是 `Scaffold` 且没有 `SnackbarHost`，加一个会多出一套宿主与消息队列，
                // 而 `hint` 已经在屏幕上有一个常驻位置、深浅色与错误色都配好了。
                //
                // ⚠️ **为什么保存完要顺手关掉预览**：
                // `hint` 渲染在预览层**下面**的记事本页面上（见状态行那句
                // `Text(text = hint ?: ...)`），而全屏预览是一整层不透明的 `scrim`，
                // 所以用户在全屏状态下**看不到**这句提示。因此这里保存一返回就
                // `previewIndex = null`：预览一关，那句提示正好落在用户眼前
                //（成功与否都能立刻看到），也符合"存完了就回列表"的直觉。
                onSaveToGallery = {
                    scope.launch {
                        // `File(path).name` 取相对名（`note_<uuid>.jpg`）：
                        // `saveImageToGallery` 的口径是"相对本页私有目录的文件名"，
                        // 而不是绝对路径（见该函数 KDoc）。
                        val ok = NotebookStore.saveImageToGallery(
                            context = context,
                            relativeName = File(path).name
                        )
                        previewIndex = null
                        hint = if (ok) "已保存到相册（Pictures/MoreCheck）" else "保存失败，请重试"
                    }
                }
            )
        }
    }

    // ---- 删除确认（长按缩略图）----
    pendingDelete?.let { index ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这张图片？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = imagePaths.getOrNull(index)
                        if (path != null) {
                            val updated = ArrayList(imagePaths).apply { removeAt(index) }
                            imagePaths = updated
                            // 同时删掉那张文件；列表与文件一起收敛，不留孤儿文件。
                            NotebookStore.commitImagesAsync(
                                context = context,
                                paths = updated,
                                removedPath = path
                            )
                            // 若正在预览的就是这一张，预览也要一起关掉，
                            // 否则会停在文件已被删除的图上。
                            if (previewIndex == index) previewIndex = null
                        }
                        pendingDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 一张已添加图片的缩略图：**点击预览、长按删除**（v1.1.68 需求 4）。
 *
 * [bitmap] 为 null 表示还没解码完或文件已损坏，此时显示占位底而不是留空白——
 * 一张尺寸正确的灰块能让手势区域仍然待在用户预期的位置，不会因为图没出来而错位。
 *
 * ## 为什么把删除从"右上角小叉"改成"长按"
 *
 * 原先那个 26dp 的小叉有三个问题：① 太小，手指容易点偏；② 它与"点开看大图"的
 * 手势区域完全重叠（都在缩略图上），点边缘时到底是预览还是删除取决于几像素的偏差；
 * ③ 误触即删、没有回退。改成长按 + 二次确认后：单击=预览（无破坏性）、
 * 长按=删除意图（明确且难以误触）、确认框再兜一层。
 *
 * ## 为什么用 `combinedClickable` 而不是叠两个手势检测器
 *
 * 与 `LabScreen` 里宫格卡片同一理由（那边有详细注释）：两个独立的手势检测器会**竞争**
 * 同一串指针事件，谁先消费取决于修饰符顺序，表现为"有时能长按、有时只触发单击"。
 * `combinedClickable` 是官方为此场景提供的单一修饰符。
 *
 * 触感反馈在长按时**显式**触发：已核对 foundation 产物，
 * `combinedClickable` 内部不会自己触发长按触感，不写就完全没有反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookThumb(
    bitmap: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(NOTEBOOK_THUMB_SIZE)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                    )
                    onLongClick()
                }
            )
    ) {
        // 位图可能已被回收（低内存下系统会回收，而状态还没更新），
        // `asImageBitmapOrNull()` 会兜住回收位图的异常，宁可回落占位也不要让页面崩掉。
        val image = bitmap?.asImageBitmapOrNull()
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "已添加的图片，点击预览，长按删除",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxSize()
            ) {}
        }
    }
}

/**
 * 全屏图片预览（点击缩略图打开）。
 *
 * ## 为什么用 `Dialog` 而不是导航到新页面
 *
 * 预览是**纯展示**、且退出后必须回到记事本原样（含未保存的正文与滚动位置）。
 * 走路由会把它变成一个全局可达的目的地，还要处理"返回时回哪一层"，
 * 而它其实只是本页的一个叠加态。项目里「头像裁切」也是就地覆盖、不走路由，同一套思路。
 *
 * ## 为什么 `usePlatformDefaultWidth = false`
 *
 * `Dialog` 默认宽度受平台限制（是个小卡片），显示大图必须铺满整屏，
 * 否则图片会被约束在一个居中的窄框里、失去"看大图"的意义。
 *
 * ## 关闭方式
 *
 * 点画面任意处、或按系统返回键（`dismissOnBackPress` 默认 true）都关。
 * 大图解码在 IO 线程（见 [NotebookStore.decodePreview]），不在组合里同步解码。
 *
 * ## 交互骨架来自 [FullScreenImageViewer]（本轮需求 20）
 *
 * 原先本文件自己写了一个「Dialog + scrim + 中央一张图」的最小预览；
 * 本轮把它换成公共组件，于是**记事本与智能体两处同时拿到**双指缩放、
 * 单指拖动、双击放大 / 还原，以及底部一行操作提示。
 * 两处原本各写一遍同一套骨架，需求要改交互时必然只改一边——
 * 这正是抽公共组件的理由（组件 KDoc 里有完整说明）。
 *
 * ## 「保存到相册」（v1.1.69 需求 3）
 *
 * 右上角一个下载图标，点了把这张图**再存一份进系统相册**
 * （见 [NotebookStore.saveImageToGallery]，走 `MediaStore` 的插入通道，零权限）。
 *
 * 按钮**必须**用 `IconButton`（自带 `clickable`）而不是往外层那个
 * "点任意处关闭"的 `Box` 上再叠一层：Compose 里子级 `clickable` 会**消费**掉
 * 这次指针事件，因此点按钮**不会**顺带触发父级的"关闭预览"——
 * 这正是我们要的（点"保存"却把预览关掉、看不到结果，是很别扭的体验）。
 * 底衬用一层半透明 `scrim` 圆底：图片本身可能是纯白的，
 * 不铺底衬的话白色图标落在白色照片上会看不见；`scrim` 在本主题里
 * 深浅两种模式下都是纯黑（见本文件里的既有说明），因此**没有新增颜色常量**。
 *
 * ⚠️ 「保存到相册」按钮挂在公共组件的**外层 Box** 上而不是传进组件：
 * 保存动作是记事本**独有**的能力（智能体没有"存进相册"这个入口），
 * 把它做成公共组件的参数会让组件背上一个"只有一个调用方用"的形参；
 * 而外层 Box 是调用方自己声明的，按钮叠在上面天然不会与组件的
 * 单击关闭手势冲突（组件的手势挂在 Image 上，见其 KDoc）。
 */
@Composable
private fun ImagePreviewDialog(
    path: String,
    onDismiss: () -> Unit,
    onSaveToGallery: () -> Unit
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { NotebookStore.decodePreview(path) }
    }

    // 外层只负责「保存到相册」这个记事本独有的动作；骨架与手势交给公共组件。
    Box(modifier = Modifier.fillMaxSize()) {
        FullScreenImageViewer(
            // 位图可能被回收 / 解码失败：组件的入参是可空的，
            // null 时它自己显示加载态（不崩、不留空白）。
            image = bitmap?.asImageBitmapOrNull(),
            onDismiss = onDismiss
        )

        // ---- 「保存到相册」按钮（v1.1.69 需求 3）----
        //
        // 放右上角（`Alignment.TopEnd`）而不是底部居中：
        // 底部区域是系统手势导航条的位置，放那里容易与"上滑返回"打架，
        // 而右上角在预览这种"看一眼"的场景里是最不挡画面的空档。
        // 组件底部另有一行常驻操作提示（「双击放大 · 点击关闭」），
        // 两者一上一下、互不遮挡。
        //
        // 按钮常显、不禁用：即使此刻图片还在解码（`bitmap == null`），
        // 保存读的是**磁盘上的文件**，与解码状态无关（见 saveImageToGallery）。
        // 按解码状态禁用反而会制造"图还没出来时点不了保存"的假限制。
        IconButton(
            onClick = onSaveToGallery,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
        ) {
            Icon(
                // 已核实 `androidx/compose/material/icons/filled/DownloadKt.class`
                // 存在于本项目解析到的 material-icons-extended 1.7.8（核实过程同
                // 「添加图片」按钮的注释），无需回退图标。
                imageVector = Icons.Filled.Download,
                // 标签写"动作 + 对象"：读屏时不能只说"下载"，用户不知道下载到哪。
                contentDescription = "保存到相册",
                tint = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }
}

/**
 * 缩略图边长。
 *
 * 取 72dp：这是"能认出是哪张图"与"一屏能看见多少张"的平衡点。
 *
 * ⚠️ 上限提到 9 张（[NotebookStore.MAX_IMAGES]）之后，一排已经放不下全部图片：
 * 9 × 72 + 8 × 10（间距） = 728dp，远超任何手机屏宽。因此这一行**必须横向滚动**
 *（`horizontalScroll`，见 [NotebookScreen] 里的用法），而不是像 3 张时那样
 * "刚好铺满一行、不需要滚动"。
 * 缩略图尺寸**没有跟着上限一起缩小**：上限变多不该以"看不清哪张是哪张"为代价，
 * 滚动本身就足以容纳多出来的图（这也是相册类界面的通行做法）。
 */
private val NOTEBOOK_THUMB_SIZE = 72.dp

/**
 * 文字防抖落盘的等待时长。
 *
 * 取 600ms：短于"一串连续输入里的字间停顿"（通常 <300ms），因此打字期间基本不会写盘；
 * 又长到用户在写完一句、停手看屏幕时会自然触发一次落盘，把"未落盘窗口"压到很小。
 * 这个值**只影响打字途中**的落盘频率；真正的退出路径不依赖它（见 [NotebookScreen] 类注释）。
 */
private const val TEXT_DEBOUNCE_MS = 600L
