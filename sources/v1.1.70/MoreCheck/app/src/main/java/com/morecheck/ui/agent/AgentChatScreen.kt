package com.morecheck.ui.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.AiChatSession
import com.morecheck.ui.AppViewModelProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「智能体」对话页——仿微信的聊天版式。
 *
 * 结构：`Scaffold` + 顶栏（返回 / 标题 / 设置）+ 灰底消息区 + 底部输入栏。
 * 颜色全部取自 [MaterialTheme]（跟随深色模式与主题色，不新增颜色常量）：
 * 消息区底色用 `surfaceVariant`（微信的浅灰底），用户气泡 `primaryContainer`、
 * AI 气泡 `surface`、错误消息 `errorContainer`，与全 App 的配色体系保持一致。
 *
 * ## 为什么 `LazyColumn` 用 `reverseLayout = true`
 *
 * 聊天列表的天然诉求是「新消息贴底、向上翻历史」。正向布局要做到这一点，
 * 得在数据变化时手动滚到最后一项，还要处理键盘弹出时的滚动补偿；
 * 而 `reverseLayout` 把坐标系整个翻过来——第 0 项就是视觉上的最底部，
 * 数据**倒序**喂进去后天然贴底，键盘弹出时也会自动停在最新消息上，
 * 一行配置替代一整套滚动管理代码。注意因此喂给列表的是 `messages.asReversed()`
 *（视图反转，零拷贝），`animateScrollToItem(0)` 就是"滚到最新"。
 *
 * ## 图片附件的链路（本页最容易误解的一段）
 *
 * 系统照片选择器返回的是 **content:// Uri**，而 ViewModel 契约 `send(text, image: File?)`
 * 收的是**实体文件**。两边的换算由本页负责，而且刻意做得最朴素：
 * `contentResolver.openInputStream(uri)` 原样字节拷贝到
 * `cacheDir/ai_attach_<时间戳>.jpg`——不压缩、不旋转、不改格式。
 * 取舍：压缩与降采样是 ViewModel / 发送侧的职责，本页只保证"字节忠实"，
 * 多个观察者（缩略图、预览、ViewModel）读到的都是同一份原始数据，
 * 不会出现"缩略图看着清楚、发出去却被二次压坏"这类难以归因的问题。
 *
 * ⚠️ 临时文件的生命周期：交给 `viewModel.send(...)` 后**由 ViewModel 收口**——
 * 它先把图压缩落盘进应用私有图片目录，再无论成败删除临时文件。本页拿到 send
 * 返回值后不再碰这个文件（send 是异步的，本页若立刻删除会与 IO 段竞态）。
 * 压缩与降采样也因此发生在发送侧：本页只保证"字节忠实"，界面与落盘、
 * 发给模型三处读的都是压缩后的同一份文件。
 *
 * ## 缩略图一律异步解码
 *
 * 与 NotebookScreen 同一套纪律：位图解码是毫秒到几十毫秒级的磁盘 + CPU 操作，
 * 绝不在组合里同步做（`remember(path) { decode(path) }` 的 lambda 跑在主线程）。
 * 统一走 `LaunchedEffect` + `Dispatchers.IO` + 状态持有，解码完成后一次重组上屏。
 *
 * ## 历史列表页：就地覆盖，不走导航路由
 *
 * 顶栏左侧的「历史」按钮把本页整体换成 [HistoryListContent]（`showHistory` 的
 * if/else，与 `PersonalInfoScreen → AvatarCropScreen` / `IconPickerScreen` 的做法一致）。
 * 为一个纯 UI 的临时流程在 `TabNavHost` 里加一条 route，等于把一个页面内部的
 * "看列表 / 回聊天"提升成全局导航状态（还要处理"返回时回哪一页"），得不偿失。
 * 系统返回键由历史页内部的 [BackHandler] 接住。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: AiChatViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    // 选图、拷贝临时文件的耗时工作都在本页存活期间完成，用组合作用域即可。
    val scope = rememberCoroutineScope()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()

    // 输入框内容用 rememberSaveable：旋转 / 进程重建后不丢正在敲的字。
    var input by rememberSaveable { mutableStateOf("") }

    // 待发送的图片附件（系统照片选择器返回的 Uri）。
    // 不用 rememberSaveable：Uri 对应的是临时会话授权，进程重建后未必还能读，
    // 恢复一个打不开的附件不如直接清掉。
    var attachment by remember { mutableStateOf<Uri?>(null) }

    // 长按要操作的消息（null = 没有弹窗），以及"选择部分复制"的弹窗开关。
    var copyTarget by remember { mutableStateOf<UiMessage?>(null) }
    var partialCopyTarget by remember { mutableStateOf<UiMessage?>(null) }

    // 是否正在看历史列表页（就地覆盖本页，见类注释）。
    var showHistory by remember { mutableStateOf(false) }

    // 正在全屏预览的图片消息（null = 没在预览）。
    var previewFile by remember { mutableStateOf<File?>(null) }

    // 「加号菜单」AlertDialog 的开关。
    // 状态放在**页面级**而不是输入栏里：AlertDialog 挂在页面最外层（独立窗口），
    // 而输入栏在 Scaffold.bottomBar 里——放页面级让"谁弹的窗"与"窗挂在哪"同层，
    // 避免状态随 bottomBar 的重组意外丢失。
    var menuOpenFromBar by remember { mutableStateOf(false) }

    // ---- Photo Picker：单选图片，与 NotebookScreen 同一套（无需任何存储权限）----
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // 用户取消时返回 null，什么都不做。
        if (uri != null) attachment = uri
    }

    // ---- 拍照：系统相机 + FileProvider ----
    //
    // `TakePicture` 契约传入一个「照片要写到哪里」的 content:// Uri（FileProvider 生成），
    // 拍完回调 `true` 时把同一个 Uri 设为附件——和相册一样走后续的
    // copyUriToCacheFile → viewModel.send 链路。
    //
    // 为什么用 FileProvider 而不是 `file://`：targetSdk 36 下 StrictMode 的
    // `FileUriExposedException` 会让系统相机直接崩，必须走 content://。
    //
    // Uri 用 remember 生成一次（不要每次重组都新建）：launcher 的入参 Uri 变了
    // 会让「上一张没拍完就重拍」的边界变糊，而每次拍新照片前我们都会主动覆盖它。
    val cameraUri = remember {
        mutableStateOf(
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                java.io.File(context.cacheDir, "ai_camera_${System.currentTimeMillis()}.jpg")
            )
        )
    }
    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        // 取消 / 失败都是 false：附件不动（拍照不会留下半张图）。
        if (success) cameraUri.value?.let { attachment = it }
    }

    // ---- 发送：拼装文本 + 附件，统一入口 ----
    //
    // 附件链路（见类注释）：Uri 在 IO 线程原样拷成 cacheDir 临时文件 →
    // 交给 viewModel.send → 临时文件的压缩入库与删除**由 ViewModel 收口**
    // （send 内部无论成败都删；本页拿到返回值后不再碰它，避免与 IO 段竞态）。
    // 拷贝失败（null）时不阻塞纯文本发送，最多就是图没带上。
    //
    // 斜杠命令**也走这个入口**：本页不认识任何命令，命令的识别与执行全在 ViewModel
    // （见 AiChatViewModel.send），界面因此不需要维护第二份命令表。
    fun sendCurrent() {
        val textToSend = input.trim()
        val uri = attachment
        if (textToSend.isEmpty() && uri == null) return
        scope.launch {
            val temp = uri?.let { u ->
                withContext(Dispatchers.IO) { copyUriToCacheFile(context, u) }
            }
            viewModel.send(textToSend, temp)
            attachment = null
            input = ""
        }
    }

    // ---- 历史列表页：整体替换本页（见类注释）----
    //
    // 用 if/else 而不是提前 `return`：与本项目 `PersonalInfoScreen → AvatarCropScreen`
    // 的就地覆盖完全同一个结构（可组合函数里提前返回虽然合法，但会让
    // "这一帧到底组成了什么"变得难读，二分结构更直白）。
    //
    // ⚠️ 本页所有的页面级状态（输入框、附件、弹窗开关）与两个 launcher 都声明在
    // 这个 if/else **之前**，因此它们在"聊天页 ⇄ 历史页"来回切时都留在组合里、
    // 状态不丢；**新的页面级状态也必须声明在它之前**——声明在 else 分支里的状态
    // 会在切换到历史列表时离开组合、切回来即被重置。
    if (showHistory) {
        HistoryListContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            onBack = { showHistory = false },
            onOpen = { sessionId ->
                viewModel.openSession(sessionId)
                showHistory = false
            },
            onDelete = { sessionId ->
                viewModel.deleteSession(sessionId)
                onMessage("已删除会话")
            },
            onNewSession = {
                viewModel.newSession()
                showHistory = false
            }
        )
    } else {

        // ---- 自动滚到最新 ----
        //
        // 两个 key：消息条数变了（新消息），或最后一条的文本长度变了（流式输出在增长）。
        // reverseLayout 下第 0 项就是最新内容，animateScrollToItem(0) 即"贴底"。
        // 流式输出每个增量都会触发一次，用动画滚动比瞬时跳动顺滑，也不会打断用户
        // 正在进行的上滑翻历史（用户主动滚动时 LazyColumn 会暂停我们的动画请求）。
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(0)
        }

        Scaffold(
            // 微信式灰底：消息气泡在灰底上的层次最清楚，白底反而分不出 AI 气泡。
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            topBar = {
                TopAppBar(
                    title = { Text("智能体") },
                    navigationIcon = {
                        // 返回箭头与「历史」并排放在顶栏**左侧**：navigationIcon 是一个
                        // 可组合槽位，塞一个 Row 就能放两个按钮，两者都留在标题左边。
                        // 刻意不把「历史」塞进右侧的 actions：它和返回一样属于"离开当前上下文"
                        // 的导航动作，跟设置（配置类动作）不是一类。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            IconButton(onClick = { showHistory = true }) {
                                // AutoMirrored 的 List 图标：RTL 语言下自动镜像，
                                // 与 ArrowBack / Send 同一纪律（material-icons-extended 已在依赖里）。
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "历史会话")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            // Settings 在 material-icons-core 里（与 NotebookScreen 对 core
                            // 图标的核对口径一致），不需要动用 extended。
                            Icon(Icons.Outlined.Settings, contentDescription = "智能体设置")
                        }
                    }
                )
            },
            bottomBar = {
                ChatInputBar(
                    input = input,
                    onInputChange = { input = it },
                    attachment = attachment,
                    onRemoveAttachment = { attachment = null },
                    onOpenAttachMenu = { menuOpenFromBar = true },
                    onPickGallery = {
                        pickImage.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    // 拍照：走系统相机（TakePicture 契约，**不需要本应用申请 CAMERA 权限**），
                    // 照片写进 FileProvider 的临时目录；拍完回调里把文件 Uri 设为附件。
                    // 相机入口受「权限管理 → 摄像头」开关门控的取舍：这里刻意不门控——
                    // 权限不由本应用持有（系统相机持有），门控一个自己都没有的权限没有意义。
                    onPickCamera = { cameraUri.value?.let { uri -> takePhoto.launch(uri) } },
                    isStreaming = isStreaming,
                    canSend = input.isNotBlank() || attachment != null,
                    onSend = { sendCurrent() },
                    onStopStreaming = { viewModel.stopStreaming() }
                )
            }
        ) { innerPadding ->
            if (messages.isEmpty()) {
                // 空态：居中灰字。不塞假消息、不画插画，与本项目"占位即文案"的口径一致。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "向 AI 提问，支持发送图片\n输入 /help 查看可用命令",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 数据倒序喂（见类注释）：messages 是升序，asReversed 后第 0 项最新。
                val display = remember(messages) { messages.asReversed() }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp, vertical = 10.dp
                    )
                ) {
                    // key 用消息 id：流式更新最后一条内容时，这条 item 的身份不变，
                    // Compose 只原地重组它，不会整列表重建。
                    items(display, key = { it.id }) { message ->
                        MessageRow(
                            message = message,
                            isStreaming = isStreaming,
                            onLongPress = { copyTarget = message },
                            onImageClick = { message.imageFile?.let { previewFile = it } }
                        )
                    }
                }
            }
        }

        // ---- 长按消息：复制 / 选择部分复制 ----
        //
        // 系统气泡（isLocal）不提供复制：那些文本是界面提示，不是用户要留存的内容；
        // 但真长按了也不报错，照常弹窗、只是按钮禁用（与"没有可复制文本"同一处理）。
        copyTarget?.let { target ->
            val clipboard = LocalClipboardManager.current
            val hasText = target.text.isNotEmpty()
            AlertDialog(
                onDismissRequest = { copyTarget = null },
                title = { Text("消息操作") },
                text = {
                    Text(
                        if (hasText) {
                            "复制这条消息的文本？"
                        } else {
                            "这条消息没有可复制的文本。"
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(target.text))
                            copyTarget = null
                            onMessage("已复制")
                        },
                        enabled = hasText
                    ) { Text("复制文本") }
                },
                dismissButton = {
                    // 「选择部分复制」放到 dismissButton 一侧（次要动作用 TextButton）：
                    // 确认按钮留给"一把复制全文"这个最常见的选择。
                    Row {
                        TextButton(
                            onClick = {
                                partialCopyTarget = target
                                copyTarget = null
                            },
                            enabled = hasText
                        ) { Text("选择部分复制") }
                        TextButton(onClick = { copyTarget = null }) { Text("取消") }
                    }
                }
            )
        }

        // ---- 选择部分复制：SelectionContainer 里自由选词 ----
        //
        // 气泡本体刻意不用 SelectionContainer（长按手势会和 combinedClickable 抢事件，
        // 见 MessageBubble 的说明），所以把"选区"放进一个独立弹窗：这里是干净的手势环境，
        // 选完由系统自带的复制菜单接管，不需要我们自己实现复制按钮。
        partialCopyTarget?.let { target ->
            SelectionCopyDialog(
                text = target.text,
                onDismiss = { partialCopyTarget = null }
            )
        }

        // ---- 全屏图片预览（点击气泡里的图）----
        previewFile?.let { file ->
            ChatImagePreviewDialog(
                file = file,
                onDismiss = { previewFile = null }
            )
        }

        // ---- 加号菜单：相册 / 拍照（AlertDialog，见 ChatInputBar 的说明）----
        if (menuOpenFromBar) {
            AlertDialog(
                onDismissRequest = { menuOpenFromBar = false },
                title = { Text("添加图片") },
                text = {
                    Column {
                        // 两个入口各占一整行，行高够大（56dp）便于拇指点选。
                        // 与「退出登录确认」等 AlertDialog 的按钮风格区分开：
                        // 这里是**选择**而不是确认，所以不用 TextButton 排排坐，用整行可点。
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    menuOpenFromBar = false
                                    pickImage.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                }
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("从相册选择", style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    menuOpenFromBar = false
                                    cameraUri.value?.let { uri -> takePhoto.launch(uri) }
                                }
                                .padding(horizontal = 4.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("拍照", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                },
                confirmButton = {}
            )
        }
    }
}

/**
 * 一行消息：用户靠右、AI 靠左，气泡本体交给 [MessageBubble]。
 *
 * 对齐用 `Arrangement.End / Start` 而不是给气泡加 `align`：
 * Row 占满整行后靠排列控制位置，气泡宽度变化时对齐关系自动成立。
 */
@Composable
private fun MessageRow(
    message: UiMessage,
    isStreaming: Boolean,
    onLongPress: () -> Unit,
    onImageClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        MessageBubble(
            message = message,
            // 气泡最大宽度 = 0.78 屏宽：微信的近似比例。留出两侧余量后长文本
            // 不会顶满屏幕，左右仍能看出"谁在说话"。
            maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp *
                BUBBLE_MAX_WIDTH_FACTOR,
            typing = !message.isUser && message.text.isEmpty() && isStreaming,
            onLongPress = onLongPress,
            onImageClick = onImageClick
        )
    }
}

/**
 * 单个消息气泡。
 *
 * ## 配色（全部取自 MaterialTheme，跟随深色模式与主题色）
 *
 * - 用户：`primaryContainer` / `onPrimaryContainer`——主题色派生的弱化色，
 *   一眼认出"我说的"，又不抢 `primary` 的视觉权重；
 * - AI：`surface` / `onSurface`——中性、安静，落在灰底上像微信的白气泡；
 * - 错误：`errorContainer` / `error`——失败消息（网络错、无 Key 等）用语义色直接标出，
 *   用户不用读全文就知道这条没成功。
 *
 * ## 形状的不对称圆角
 *
 * 用户气泡右上角收小、AI 气泡左上角收小（4dp vs 16dp）——微信的经典做法，
 * 让气泡"指向"说话者的方向，比四个角全等的圆角矩形多一层方向感。
 *
 * ## 为什么文本不用 `SelectionContainer`
 *
 * 气泡上绑了 `combinedClickable`（长按弹操作菜单），`SelectionContainer` 的
 * 长按选词手势会和它竞争同一串指针事件，表现为"有时弹菜单、有时进入选择"。
 * 复制需求由长按菜单承担（见 [AgentChatScreen] 的对话框），
 * "只复制一段"则由 [SelectionCopyDialog] 那个独立弹窗承担——手势就只有一条路径。
 *
 * 系统气泡（[UiMessage.isLocal]，斜杠命令的回执）与普通 AI 气泡同色：
 * 它是一条**回答之外**的界面提示，用红色（isError）会误导用户以为出了错；
 * 与 AI 气泡同色 + 灰字正文是更准确的表达。
 *
 * [typing] 为 true 表示这条 AI 消息还没有内容而流式正在进行：显示"…"打字中，
 * 而不是留一个空瘪的气泡。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: UiMessage,
    maxBubbleWidth: Dp,
    typing: Boolean,
    onLongPress: () -> Unit,
    onImageClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val container = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        message.isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        message.isError -> MaterialTheme.colorScheme.error
        // 系统气泡的正文压暗一档：它长得像 AI 说的话，但用户不该把它当回答读。
        message.isLocal -> MaterialTheme.colorScheme.onSurfaceVariant
        message.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    // RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)。
    val shape = if (message.isUser) {
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    Surface(
        color = container,
        shape = shape,
        modifier = Modifier
            .widthIn(max = maxBubbleWidth)
            // clip 在 combinedClickable 之前：涟漪与按压效果被裁进气泡形状里，
            // 不会溢出一个方形的波纹底。
            .clip(shape)
            .combinedClickable(
                onClick = {
                    // 点击只在图片消息上有意义（看大图）；纯文本气泡点一下不做任何事。
                    if (message.imageFile != null) onImageClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                }
            )
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (message.imageFile != null) 4.dp else 12.dp,
                vertical = if (message.imageFile != null) 4.dp else 8.dp
            )
        ) {
            if (message.imageFile != null) {
                BubbleImage(file = message.imageFile)
            }
            if (message.text.isNotEmpty() || typing) {
                // 用户消息保持纯文本（他发什么就显示什么，不做任何转义/渲染）；
                // AI 回复按 Markdown 渲染（加粗/列表/代码块等，见 AiMarkdownBody）。
                // typing 时占位符「…」不参与 Markdown 解析（解析了也没意义）。
                if (typing) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content
                    )
                } else if (message.isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = content
                    )
                } else {
                    AiMarkdownBody(
                        markdown = message.text,
                        color = content
                    )
                }
            }
        }
    }
}

/**
 * 气泡内的图片：IO 线程异步解码（带采样，防大图 OOM），宽最多 200dp，点击看大图
 *（点击手势在气泡的 combinedClickable 里统一处理，这里只负责画）。
 *
 * 解码完成前显示一个中性灰占位块：尺寸稳定的占位让手势区域不跳，也不会闪白。
 */
@Composable
private fun BubbleImage(file: File) {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { decodeSampledFromFile(file.path, 512) }
    }
    val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "消息图片，点击查看大图",
            // FillWidth：按气泡最大 200dp 宽等比显示，高度随原图比例自适应。
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .widthIn(max = BUBBLE_IMAGE_MAX_WIDTH)
                .clip(MaterialTheme.shapes.small)
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(BUBBLE_IMAGE_MAX_WIDTH, 120.dp)
        ) {}
    }
}

/**
 * 底部输入栏：附件预览 + 加号菜单（相册 / 拍照）+ 输入框 + 发送 / 停止。
 *
 * ## 键盘弹起时的布局口径（v1.1.69 冒烟测试最终版）
 *
 * 这一行 inset 修了两轮，都在实机上验证过：
 * - v1.1.68（`navigationBarsPadding() + imePadding()`）：3 键导航 + 键盘弹出时
 *   **双重让位**，输入栏悬空一大截（用户截图报的「文本框显示异常」）；
 * - v1.1.69 初版（只留 `imePadding()`）：键盘弹出正确，但键盘收起时输入栏被
 *   **导航条压住**，加号点不到（冒烟测试实测，截图可见导航键与输入框重叠）。
 *
 * 最终采用 **`WindowInsets.safeDrawing` 的 Bottom 侧单源取值**：它是 systemBars 与
 * IME 的并集（取 max），键盘收起 = 导航栏高、键盘弹出 = 键盘高，无叠加、无消费链依赖。
 * 附件预览区高度恒定（0 / 72dp），加图前后输入行不跳。
 *
 * ## 加号菜单
 *
 * 「+」点开 **AlertDialog**（相册 / 拍照两个列表项）。冒烟测试实测 `DropdownMenu`
 * 挂在本栏（Scaffold bottomBar 内）时点击无响应——Popup 在 bottomBar 作用域的
 * 定位在部分设备上不可靠；而 AlertDialog 是独立窗口，无定位歧义，且与本项目
 * 「头像选择」「退出确认」等就地选择场景同一模式。
 */
@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    attachment: Uri?,
    onRemoveAttachment: () -> Unit,
    /** 点加号：由页面级弹出「相册 / 拍照」选择弹窗（见 KDoc 的加号菜单说明）。 */
    onOpenAttachMenu: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    isStreaming: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStopStreaming: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ 底部内边距用 safeDrawing 的 Bottom 侧**单源**取值（v1.1.69 冒烟测试修正）：
                //
                // 这一行先后试过两种写法，各错一头：
                // ① `navigationBarsPadding() + imePadding()`（v1.1.68 原版）：实机（3 键导航）
                //    键盘弹出时出现**双重让位**——输入栏悬在键盘上方一大截（用户截图报的问题）；
                // ② 只留 `imePadding()`（v1.1.69 初版）：键盘弹出时正确，但键盘**收起**时
                //    没有任何让位，输入栏被系统导航条压住——冒烟测试实测「加号菜单点不开」，
                //    原因正是 tap 落点被导航条拦截（截图可见导航键与输入框重叠）。
                //
                // 根因是两种 inset 的**叠加语义**不可靠（消费链在部分 ROM / 版本上不生效）。
                // `WindowInsets.safeDrawing` 的 Bottom 侧是 systemBars 与 IME 的**并集（取 max）**：
                // 键盘收起 = 导航栏高度；键盘弹出 = 键盘高度（必然 ≥ 导航栏）。
                // 单一 inset、无叠加、无消费链依赖，两头都对。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            // ---- 附件预览区：高度恒定，输入行不因有无附件上下移动 ----
            Box(modifier = Modifier.height(if (attachment != null) 72.dp else 0.dp)) {
                if (attachment != null) {
                    Row(
                        modifier = Modifier.padding(start = 56.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        AttachPreview(uri = attachment, onRemove = onRemoveAttachment)
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onOpenAttachMenu) {
                    // Add 是 core 图标（与 NotebookScreen 的核对口径一致）。
                    Icon(Icons.Filled.Add, contentDescription = "添加图片")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(4.dp))
                if (isStreaming) {
                    // 「停止」按钮保持原样（这是流式中最主要的动作）。
                    TextButton(onClick = onStopStreaming) {
                        Text("停止", color = MaterialTheme.colorScheme.primary)
                    }
                    // 流式期间**发送键不禁用而是保留**：它现在承担"在生成中发送斜杠命令"
                    // 这一条通路（最典型的是 `/stop`，以及 `/new` 换会话）。
                    // 普通消息在生成中仍会被 ViewModel 忽略（不排队，理由见其 send 的说明），
                    // 所以这里不放行任何"并发两条流式请求"的可能；命令本身不发请求。
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送（生成中可输入 / 命令）",
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                } else {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            // AutoMirrored 版本：RTL 语言下箭头自动镜像，避免反向的旧图标。
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 输入栏上方的附件小预览：64dp 方块 + 右上角「×」。
 *
 * 解码走 IO 线程（同气泡图片），原图字节不动，只做显示用的采样缩小。
 */
@Composable
private fun AttachPreview(
    uri: Uri,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { decodeSampledFromUri(context, uri, 256) }
    }
    Box(modifier = Modifier.size(64.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxSize()
        ) {
            val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "待发送的图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // 移除按钮：小黑圆底 + 白叉。底色用 scrim（本项目深浅两套主题里都是纯黑，
        // 见 NotebookScreen 对 scrim 的核对），叉用 Color.White 保证在黑底上可读——
        // 这是叠在图片上的控件，跟主题色走反而会撞色。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(onClick = onRemove)
                .padding(3.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除附件",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 全屏图片预览（点击气泡里的图打开）。
 *
 * 沿用 NotebookScreen 的预览方案：`Dialog` + `usePlatformDefaultWidth = false`
 * 铺满整屏，底色用 `scrim`（本项目两套主题里都是纯黑），点任意处或返回键关闭。
 * 大图在 IO 线程解码（带采样到 1080px，防 OOM），解码完成前显示加载文案。
 */
@Composable
private fun ChatImagePreviewDialog(
    file: File,
    onDismiss: () -> Unit
) {
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.path) {
        bitmap = withContext(Dispatchers.IO) { decodeSampledFromFile(file.path, 1080) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 底色必须显式铺满，否则对话页会透过透明窗口显示出来（详见 NotebookScreen）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "图片预览",
                    // Fit 而不是 Crop：预览要看清整张图，不能裁边。
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "图片加载中…",
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

/**
 * 把相册 Uri 原样拷贝为 cacheDir 下的临时 jpg。
 *
 * 只做字节级拷贝：不压缩、不旋转（EXIF 处理是发送侧的职责）。
 * 任何一步失败都返回 null，调用方降级为纯文本发送，不让页面崩。
 */
private fun copyUriToCacheFile(context: Context, uri: Uri): File? = runCatching {
    val out = File(context.cacheDir, "ai_attach_${System.currentTimeMillis()}.jpg")
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    input.use { source ->
        out.outputStream().use { target -> source.copyTo(target) }
    }
    out
}.getOrNull()

/**
 * 从文件路径解码一张采样后的位图（目标边长约 [targetSize] px）。
 *
 * 两段式解码：先 `inJustDecodeBounds` 读尺寸，按 2 的幂算 `inSampleSize`，
 * 再真正解码——聊天里的图不需要全分辨率，采样能成倍降低内存与解码耗时。
 * 文件缺失或格式不支持时返回 null（调用方显示占位块）。
 */
private fun decodeSampledFromFile(path: String, targetSize: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (
        bounds.outWidth / (sample * 2) >= targetSize &&
        bounds.outHeight / (sample * 2) >= targetSize
    ) {
        sample *= 2
    }
    BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sample }
    )
}.getOrNull()

/**
 * 从 content Uri 解码一张采样后的位图（用于附件预览小图），失败返回 null。
 * 流用 `use` 关闭：ContentProvider 的流不关会一直占着资源。
 */
private fun decodeSampledFromUri(context: Context, uri: Uri, targetSize: Int): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= targetSize &&
            bounds.outHeight / (sample * 2) >= targetSize
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

/**
 * 「选择部分复制」弹窗：用 [SelectionContainer] 包住消息全文，让用户自由拖选片段，
 * 再由**系统自带的复制菜单**（长按选区弹出）完成复制。
 *
 * ## 为什么不自己画一个"复制选中内容"按钮
 *
 * 拿选中文本要走 Compose 的 `SelectionContainer` + 内部 `SelectionRegistrar`，
 * 那是框架私有 API；而系统复制菜单本来就在那里（长按选区即出现「复制」），
 * 我们自己再实现一个既要用非公开接口，又和系统菜单重复。因此这里只负责
 * **提供一个没有手势竞争的选择环境**，把复制交给系统。
 *
 * 与气泡的分工见 [MessageBubble] 的说明：气泡上有 long-press 手势，不能直接套
 * [SelectionContainer]；弹窗里是干净的，两者因此不冲突。
 */
@Composable
private fun SelectionCopyDialog(
    text: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择部分复制") },
        text = {
            // 可滚动的选择区：长回答（Markdown 源码动辄几千字）在弹窗里必须能滚，
            // 否则用户只能选到开头那一段。
            Column(
                modifier = Modifier
                    .heightIn(max = SELECTION_DIALOG_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "长按文字拖动选择，选中后点系统菜单里的「复制」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 历史列表页（就地覆盖聊天页，见 [AgentChatScreen] 类注释）。
 *
 * ## 列表项的四个信息
 *
 * 标题 + 相对时间 + 最后一条消息预览（单行省略），当前会话带选中态。
 * 时间用相对表述（"3 分钟前"）而不是绝对时间：**翻历史时人脑想的是"多久以前"**，
 * 而"2025-03-08 14:22"还要用户自己做一次减法。
 *
 * ## 长按删除 + 二次确认
 *
 * 删除一条会话是不可逆的（消息与它独占的图片一起没了），所以走 AlertDialog 确认；
 * 点击（短按）进会话、长按弹删除确认，两者在同一行上由 combinedClickable 分开。
 *
 * ## 返回键
 *
 * 本页不是导航目的地（只是聊天页内部的一个状态），所以系统返回键由本页自己的
 * [BackHandler] 接住，回到聊天页——与 `AvatarCropScreen` / `IconPickerScreen`
 * 的就地覆盖做法完全一致。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HistoryListContent(
    sessions: List<AiChatSession>,
    currentSessionId: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNewSession: () -> Unit
) {
    BackHandler(onBack = onBack)

    // 待删除的会话（null = 没有确认弹窗）。
    var pendingDelete by remember { mutableStateOf<AiChatSession?>(null) }
    val haptic = LocalHapticFeedback.current
    // "现在"取一次就固定：让列表里所有相对时间以同一个基准计算，
    // 否则每行各取一次 System.currentTimeMillis() 会让两行出现"同一分钟却差一分钟"的错觉。
    val now = remember(sessions) { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史会话") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onNewSession) { Text("新对话") }
                }
            )
        }
    ) { innerPadding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "还没有历史对话",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // key 用会话 id：删除某一行时 Compose 只移除那一项，
                // 不会把后面的行整体"错位重绘"（用下标做 key 的经典问题）。
                items(sessions, key = { it.id }) { session ->
                    HistoryRow(
                        session = session,
                        selected = session.id == currentSessionId,
                        now = now,
                        onClick = { onOpen(session.id) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            pendingDelete = session
                        }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条对话？") },
            text = { Text("「${target.title}」的全部消息与图片将被删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(target.id)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 历史列表的一行：标题 + 相对时间 + 预览（单行省略）；当前会话加一层浅色底表示选中。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    session: AiChatSession,
    selected: Boolean,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 选中态用 surfaceContainerHighest 而不是主题色：历史列表可能同时有
            // 二十行，主题色底会让整屏都在"喊"，浅一档的中性色更安静。
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    // 当前会话的标题用主题色：一屏里最需要一眼找到的就是"我正在哪条对话里"。
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relativeTime(session.updatedAt, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = session.preview.ifBlank { "（暂无消息）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 单行省略：预览只是"帮你认出这是哪次对话"，不该占掉两行高度。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 毫秒时间戳 → 中文相对时间（「刚刚」「3 分钟前」「昨天」「3 天前」「3 个月前」…）。
 *
 * ## 分档口径
 *
 * 从细到粗依次减退精度：一小时内报分钟，今天报小时，一周内报天数并给"昨天 / 前天"
 * 这两个更自然的说法，再往上就只剩"哪个月"还有意义了。
 * 取"超过一年直接显示日期"而不是"13 个月前"：那个量级的相对时间用户反而算不清。
 *
 * 时间戳在未来（设备时钟被回调过）时按「刚刚」处理：宁可显示得乐观一点，
 * 也不要弹出一句"3 分钟后"这种会让用户困惑的说法。
 */
private fun relativeTime(timestamp: Long, now: Long): String {
    val diff = now - timestamp
    if (diff < TimeUnit.MINUTES.toMillis(1)) return "刚刚"
    if (diff < TimeUnit.HOURS.toMillis(1)) {
        return "${TimeUnit.MILLISECONDS.toMinutes(diff)} 分钟前"
    }
    if (diff < TimeUnit.DAYS.toMillis(1)) {
        return "${TimeUnit.MILLISECONDS.toHours(diff)} 小时前"
    }
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        days == 1L -> "昨天"
        days == 2L -> "前天"
        days < 7L -> "$days 天前"
        days < 30L -> "${days / 7} 周前"
        days < 365L -> "${days / 30} 个月前"
        // 一年以上：给具体日期（相对时间在这个尺度上已经失去参照意义）。
        else -> {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
            date
        }
    }
}

/** 气泡最大宽度占屏宽的比例：0.78 是微信观感的近似值。 */
private const val BUBBLE_MAX_WIDTH_FACTOR = 0.78f

/** 气泡内图片的最大宽度：200dp，与需求口径一致。 */
private val BUBBLE_IMAGE_MAX_WIDTH = 200.dp

/** 「选择部分复制」弹窗里选择区的最大高度：留出标题与按钮，长回答在框内滚动。 */
private val SELECTION_DIALOG_MAX_HEIGHT = 360.dp
