package com.morecheck.ui.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import com.morecheck.data.AiChatSession
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.common.FullScreenImageViewer
import com.morecheck.ui.common.asImageBitmapOrNull
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * AI 气泡 `surfaceDim`、错误消息 `errorContainer`，与全 App 的配色体系保持一致。
 * AI 气泡为什么选 `surfaceDim` 而不是 `surface` / `surfaceContainerHigh`
 * 见 [MessageBubble] 的配色说明（那里逐个数了本主题里哪些 `surface*` 是白、哪些是灰）。
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

    // 合并后新增：知识库模式 / 文档列表 / 忙碌态（都是"这一屏"的展示状态，源在 ViewModel）。
    val knowledgeMode by viewModel.knowledgeMode.collectAsStateWithLifecycle()
    val knowledgeDocs by viewModel.knowledgeDocs.collectAsStateWithLifecycle()
    val knowledgeBusy by viewModel.knowledgeBusy.collectAsStateWithLifecycle()

    // 知识库管理弹层、引用详情弹层的开关。
    var showKnowledgeBase by remember { mutableStateOf(false) }
    var citationTarget by remember { mutableStateOf<CitationRef?>(null) }

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

    // ---- 知识库导入：系统文件选择器（可多选，纯文本）----
    //
    // 与原来「知识问答」页同一套口径：不在主动申请存储权限的前提下读内容
    // （SAF 给的是一次性读授权），字节交给 ViewModel → Store 去探测编码、
    // 同名覆盖、重建索引；界面只负责把结果回报给用户。
    val importDocs = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var ok = 0
            var failed = 0
            var reason: String? = null
            for (uri in uris) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes == null) {
                    failed++
                    reason = "无法读取文件"
                    continue
                }
                if (bytes.size > MAX_KNOWLEDGE_BYTES) {
                    failed++
                    reason = "单个文件不能超过 8 MB"
                    continue
                }
                viewModel.importKnowledge(knowledgeDisplayName(context, uri), bytes) { msg ->
                    onMessage(msg)
                }
                ok++
            }
            if (failed > 0) {
                onMessage(
                    buildString {
                        append("已提交 $ok 份")
                        append("，$failed 份失败")
                        reason?.let { append("（").append(it).append("）") }
                    }
                )
            }
        }
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
        // 知识库模式只吃文字：切换模式时可能还挂着上一刻选的图，这里一并清掉，
        // 避免"图被静默带进去又被丢弃"这种查不出来的诡异感。
        val uri = if (knowledgeMode) {
            if (attachment != null) attachment = null
            null
        } else {
            attachment
        }
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
            // 消息区底色沿用 surfaceVariant：浅色模式下它其实是**纯白** `#FFFFFF`
            // （不是灰底，`NeutralLight.surfaceVariant`，见 Theme.kt），深色模式是 `#1E1E1E`。
            // 因此"气泡能不能从底上分出来"完全取决于气泡色与它的落差，
            // 具体的色值对照与选型见 MessageBubble 的配色说明。
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
                    onStopStreaming = { viewModel.stopStreaming() },
                    allowAttachment = !knowledgeMode,
                    placeholder = if (knowledgeMode) "问文档里的内容…" else "输入消息…"
                )
            }
        ) { innerPadding ->
            // 模式切换条固定压在标题栏下面：它管的是"下一次提问怎么答"，
            // 与消息列表是并列关系（不是列表里的一项），所以放在 LazyColumn 外面。
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                AgentModeBar(
                    knowledgeMode = knowledgeMode,
                    docCount = knowledgeDocs.size,
                    busy = knowledgeBusy,
                    onToggleMode = { viewModel.setKnowledgeMode(it) },
                    onOpenKnowledgeBase = { showKnowledgeBase = true }
                )
                if (messages.isEmpty()) {
                    // 空态：知识库模式下给「建议问题」（点一下就能看到完整效果），
                    // 自由对话模式保持原来的引导文案。
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (knowledgeMode) {
                                if (knowledgeDocs.isEmpty()) {
                                    "知识库还是空的\n先在右上角导入 .txt / .md 文档，再提问"
                                } else {
                                    "只依据你的 ${knowledgeDocs.size} 份文档作答\n" +
                                        "每条回答都会标出来自哪份文档、第几段、第几行"
                                }
                            } else {
                                "自由对话：回答不限于知识库\n输入 /help 查看可用命令，" +
                                    "/searchon 可联网搜索后回答"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (knowledgeMode) {
                            Spacer(Modifier.height(20.dp))
                            if (knowledgeDocs.isEmpty()) {
                                TextButton(onClick = { showKnowledgeBase = true }) {
                                    Text("导入文档")
                                }
                            } else {
                                SuggestedQuestions(docs = knowledgeDocs) { input = it }
                            }
                        }
                    }
                } else {
                // 数据倒序喂（见类注释）：messages 是升序，asReversed 后第 0 项最新。
                //
                // 这里在倒序序列上**就地插入日期分隔项**（见 buildAgentChatListItems 的
                // 详细说明），返回的仍是"倒序"序列，因此 reverseLayout 的坐标不变、
                // 「第 0 项 = 最新」这条不变量继续成立。
                //
                // `today` 参与 remember 的 key：`LocalDate` 只在跨零点时变化，
                // 平时它与 messages 引用一起决定要不要重建列表（同一个时刻读到的
                // "今天"必须与分隔文案一致，否则跨零点后「今天」会一直停在前一天）。
                val today = LocalDate.now()
                val display = remember(messages, today) {
                    buildAgentChatListItems(messages, today)
                }
                LazyColumn(
                    state = listState,
                    // innerPadding 已经由外层 Column 消费，这里只负责铺满剩余空间。
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    reverseLayout = true,
                    // 行距比原来 8dp 略放大到 10dp：加了日期分隔后，同一屏里会同时
                    // 出现"同类消息"与"跨日消息"两种相邻关系，稍大的行距能让
                    // 分隔条自己站出来，不必靠加粗/加色去抢注意力。
                    verticalArrangement = Arrangement.spacedBy(CHAT_MESSAGE_SPACING),
                    // 水平 12dp 保持原值（气泡最大宽度已由 0.78 屏宽控制，再收窄会让
                    // 长回答可用宽度不够）；垂直 10dp 收到 6dp——上下留白交给底部输入栏
                    // 与顶栏，列表自己不必再各留一圈，长对话能多露出一行。
                    contentPadding = PaddingValues(
                        horizontal = CHAT_LIST_HORIZONTAL_PADDING,
                        vertical = CHAT_LIST_VERTICAL_PADDING
                    )
                ) {
                    // key 用列表项自己的 key：消息项就是消息 id（流式更新最后一条内容时
                    // 身份不变，Compose 只原地重组它，不会整列表重建）；
                    // 日期分隔项用 AI_DAY_KEY_PREFIX 前缀串（见 AgentChatListItem 的说明，
                    // 前缀保证与 Long 型消息 id 在类型与取值上都不可能撞）。
                    items(display, key = { it.key }) { item ->
                        when (item) {
                            is AgentChatListItem.Message -> MessageRow(
                                message = item.message,
                                isStreaming = isStreaming,
                                onLongPress = { copyTarget = item.message },
                                onImageClick = {
                                    item.message.imageFile?.let { previewFile = it }
                                },
                                onCitationClick = { citationTarget = it }
                            )
                            // 分隔条的上下间距由它自己的 padding 给，此处不再额外加码；
                            // 注意 LazyColumn 的 Arrangement.spacedBy 是**所有相邻项之间**
                            // 都生效的，所以分隔条与相邻气泡之间实际是 10 + 8 dp。
                            // 这是刻意留的：日期标签本来就该比普通行距更"隔开"一点。
                            is AgentChatListItem.Day -> AgentChatDayDivider(item.label)
                        }
                    }
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
            // 只有"最后一条回答"才给「重新生成」：对中间某条重生成会把它后面的对话
            // 弄成"未知分支"，而这个页面没有分支树，只能撤回最后一问一答（见 ViewModel）。
            val isLastAnswer = !target.isUser && !target.isLocal &&
                messages.lastOrNull { !it.isUser && !it.isLocal }?.id == target.id
            AlertDialog(
                onDismissRequest = { copyTarget = null },
                title = { Text("消息操作") },
                text = {
                    Column {
                        Text(
                            if (hasText) {
                                "复制这条消息的文本？"
                            } else {
                                "这条消息没有可复制的文本。"
                            }
                        )
                        if (target.citations.isNotEmpty() || isLastAnswer) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // 「复制含来源」：把回答贴到别处时，依据清单跟着走，
                                // 否则那段话就变成无从核对的一段文字。
                                if (target.citations.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(
                                                AnnotatedString(buildCopyWithSources(target))
                                            )
                                            copyTarget = null
                                            onMessage("已复制（含 ${target.citations.size} 条来源）")
                                        }
                                    ) { Text("复制含来源") }
                                }
                                if (isLastAnswer && !isStreaming) {
                                    TextButton(
                                        onClick = {
                                            copyTarget = null
                                            viewModel.regenerateLast()
                                        }
                                    ) { Text("重新生成") }
                                }
                            }
                        }
                    }
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
        //
        // 2026-10-24 需求「优化添加图片弹窗的样式」：从"裸 AlertDialog 塞两行文字"
        // 改成一套明确的视觉层次——
        // · 标题区带图标 + 副标题（说明这两个入口各自做什么）；
        // · 两个选项做成**独立的卡片式条目**（surfaceVariant 底 + 12dp 圆角 +
        //   垂直 12dp 间距），与对话框底色拉开层次，点哪一处一目了然；
        // · 底部一枚「取消」文字按钮，替代原先的空白 confirmButton
        //   （原本 `confirmButton = {}` 会留一条高度为 0 的区域，视觉上对话框下缘显得突兀）。
        if (menuOpenFromBar) {
            AlertDialog(
                onDismissRequest = { menuOpenFromBar = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.AddPhotoAlternate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("添加图片", style = MaterialTheme.typography.titleMedium)
                    }
                },
                text = {
                    Column {
                        Text(
                            text = "选择一张图片作为本次提问的附件；图片会上传到你所配置的 AI 服务商。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        // 每个选项一张浅色卡片：图标 + 主文案 + 次文案。
                        AttachOptionCard(
                            icon = { Icons.Outlined.PhotoLibrary },
                            title = "从相册选择",
                            subtitle = "调用系统照片选择器，只读取你选中的那一张",
                            onClick = {
                                menuOpenFromBar = false
                                pickImage.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        AttachOptionCard(
                            icon = { Icons.Filled.PhotoCamera },
                            title = "拍照",
                            subtitle = "调用系统相机，拍完直接作为附件",
                            onClick = {
                                menuOpenFromBar = false
                                cameraUri.value?.let { uri -> takePhoto.launch(uri) }
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { menuOpenFromBar = false }) { Text("取消") }
                }
            )
        }

        // ---- 知识库管理（导入 / 删除 / 规模）----
        if (showKnowledgeBase) {
            KnowledgeBaseDialog(
                docs = knowledgeDocs,
                busy = knowledgeBusy,
                onImport = { importDocs.launch(KNOWLEDGE_MIME_TYPES) },
                onDelete = { doc -> viewModel.deleteKnowledge(doc) { msg -> onMessage(msg) } },
                onDismiss = { showKnowledgeBase = false }
            )
        }

        // ---- 引用详情：落到原文位置（段号 / 行号 / 字符区间 + 高亮上下文）----
        citationTarget?.let { ref ->
            CitationDetailDialog(ref = ref, onDismiss = { citationTarget = null })
        }
    }
}

/** 单份知识库文档的大小上限：再大就不是"知识库"而是"数据集"，本地检索会明显变慢。 */
private const val MAX_KNOWLEDGE_BYTES = 8 * 1024 * 1024

/**
 * 文件选择器的 MIME 过滤。
 *
 * 不只写 `text/plain`：Android 上 .md 常被报成 `application/octet-stream`，
 * 部分网盘/文件管理器还会给 `application/x-markdown`。多列几项只是让文件能在选择器里
 * 出现，真正的类型判定在导入时按内容做（见 `KnowledgeQaStore` 的编码探测）。
 */
private val KNOWLEDGE_MIME_TYPES = arrayOf(
    "text/plain",
    "text/markdown",
    "text/*",
    "application/octet-stream"
)

/** 取系统给的文件显示名（含扩展名）；取不到时回落到 URI 末段。 */
private fun knowledgeDisplayName(context: Context, uri: Uri): String {
    val fromResolver = runCatching {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromResolver?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "未命名文档.txt"
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
    onImageClick: () -> Unit,
    onCitationClick: (CitationRef) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
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
        // 依据放在气泡**外面**（下方）：它是"可核对的出处"，不是回答正文的一部分。
        // 塞进气泡会让复制正文时把芯片文案一起选中，也会让气泡看起来像在自证。
        if (!message.isUser && message.text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            CitationSection(message = message, onCitationClick = onCitationClick)
        }
    }
}

/**
 * 单个消息气泡。
 *
 * ## 配色（全部取自 MaterialTheme，跟随深色模式与主题色）
 *
 * - 用户：`primaryContainer` / `onPrimaryContainer`——主题色派生的弱化色，
 *   一眼认出"我说的"，又不抢 `primary` 的视觉权重；
 * - AI：`surfaceDim` / `onSurface`——中性、安静，与消息区有明确落差（理由见下）；
 * - 错误：`errorContainer` / `error`——失败消息（网络错、无 Key 等）用语义色直接标出，
 *   用户不用读全文就知道这条没成功。
 *
 * ### AI 气泡为什么是 `surfaceDim`（逐个色值核对的结论）
 *
 * 消息区底色是 `surfaceVariant`：浅色 `#FFFFFF`、深色 `#1E1E1E`。
 * 把 `NeutralLight` / `NeutralDark`（见 `ui/theme/Theme.kt`）里所有语义面**逐个点一遍**，
 * 看它们与消息区到底差多少（括号内为与消息区底色的分量差，0 即完全同色）：
 *
 * | 语义面 | 浅色 | 差 | 深色 | 差 |
 * |---|---|---|---|---|
 * | `surfaceBright` | `#FFFFFF` | **0 同色** | `#2A2A2A` | 12 |
 * | `surfaceContainerLowest` | `#FFFFFF` | **0 同色** | `#141414` | 10 |
 * | `surfaceContainerLow` | `#FFFFFF` | **0 同色** | `#191919` | 5 |
 * | `surfaceContainer` | `#FFFFFF` | **0 同色** | `#191919` | 5 |
 * | `surfaceContainerHighest` | `#FFFFFF` | **0 同色** | `#191919` | 5 |
 * | `surfaceContainerHigh` | `#F7F7F7` | 8 | `#202020` | **2** |
 * | `surface`（原值） | `#EDEDED` | 18 | `#111111` | 13 |
 * | `surfaceDim` | `#DDDDDD` | **34** | `#0C0C0C` | **18** |
 *
 * 结论一目了然：**五个面在浅色模式下就是纯白，与消息区同色**——用它们做 AI 气泡，
 * 浅色下气泡会彻底融进白底（这比现状更糟）。剩下的候选里，`surfaceContainerHigh`
 * 在深色下只差 2/255（几乎看不见），只有浅色可用，等于只修了一半。
 * **`surfaceDim` 是唯一在深浅两套主题里都取到最大落差的那个**（34 与 18）。
 *
 * 另外它的**方向**还正好沿用原值：`surface` 相对消息区也是"更深一档"
 * （18 / 13），所以换成 `surfaceDim` 是"把落差拉大到看得清"，
 * 而不是把气泡的明暗关系翻过来——观感连续，不是换了一套设计语言。
 *
 * ⚠️ 注意 `surface` 与页面底色 `background` 同值（浅色都是 `#EDEDED`）：
 * 用 `surface` 的 AI 气泡其实是在"透出页面底色"，在白色消息区里显得像一块
 * 没铺好的灰斑。这也是本次要修掉的核心观感问题。
 *
 * ## 形状的不对称圆角
 *
 * 用户气泡右上角收小、AI 气泡左上角收小（4dp vs 16dp）——微信的经典做法，
 * 让气泡"指向"说话者的方向，比四个角全等的圆角矩形多一层方向感。
 *
 * 圆角值抽成 BUBBLE_CORNER_RADIUS / BUBBLE_TAIL_CORNER_RADIUS 两个具名常量：
 * 气泡本体与 [BubbleImage] 里的图片必须**用同一组数**，否则图片四角会与气泡边缘
 * 差出零点几毫米，在浅色描边下看得出"图片没贴齐"。
 *
 * ## 为什么用 1dp 描边而不是阴影
 *
 * 两者都能把气泡从底上"托"起来，这里选描边：
 * - 本项目的设计取向是**扁平、灰底白卡**（见 Theme.kt 顶部说明），
 *   `Modifier.shadow` 的软阴影属于 Material You 的层叠语言，加进来会与
 *   「卡片靠 1dp 细线 + 一档灰色分层」的既有观感打架；
 * - 阴影在深色模式下基本不可见（黑底上的黑阴影），等于只对浅色模式生效；
 *   描边用 `outlineVariant`（浅色 `#E5E5E5` / 深色 `#2C2C2C`）在两种模式下都成立，
 *   在深色下它还是**比气泡底色更亮**的一圈，正是"浮起来"的那种高光边；
 * - 描边还能顺带解决纯色块边界发虚的问题，而阴影做不到这件事。
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
        // 见 KDoc 的色值对照表：surfaceDim 是唯一在深浅两套主题里
        // 都与消息区拉开足够落差的语义面（浅色 #DDDDDD vs #FFFFFF、
        // 深色 #0C0C0C vs #1E1E1E），且方向沿用原值 surface（更深一档）。
        else -> MaterialTheme.colorScheme.surfaceDim
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
        RoundedCornerShape(
            BUBBLE_CORNER_RADIUS,
            BUBBLE_TAIL_CORNER_RADIUS,
            BUBBLE_CORNER_RADIUS,
            BUBBLE_CORNER_RADIUS
        )
    } else {
        RoundedCornerShape(
            BUBBLE_TAIL_CORNER_RADIUS,
            BUBBLE_CORNER_RADIUS,
            BUBBLE_CORNER_RADIUS,
            BUBBLE_CORNER_RADIUS
        )
    }
    // 描边只给"非用户"的气泡：用户气泡的 primaryContainer 本身色差就很清楚，
    // 再描一圈线会让它显得被框住、与右对齐的"甩出去"感觉相反。
    // 其余三种（AI 的 surfaceDim、系统提示、错误的 errorContainer）都需要这圈线
    // 来定边界——AI 气泡是为了从同色系消息区上"浮"起来（见 KDoc），
    // 错误气泡则是为了让"这条不一样"多一层强调。
    val border = if (message.isUser) {
        null
    } else {
        BorderStroke(BUBBLE_HAIRLINE_WIDTH, MaterialTheme.colorScheme.outlineVariant)
    }
    Surface(
        color = container,
        shape = shape,
        border = border,
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
        val hasImage = message.imageFile != null
        Column(
            modifier = Modifier.padding(
                // 图片气泡的内边距比文本气泡紧一档（4dp vs 14/10dp）：图片自己就是
                // 内容，厚边距只会把图挤小；这条既有意图保留，只把文本侧从原来的
                // 12/8 微调到 14/10——加了描边以后，同样的文字若贴着线会显得局促。
                horizontal = if (hasImage) BUBBLE_MEDIA_INSET else BUBBLE_TEXT_PADDING_HORIZONTAL,
                vertical = if (hasImage) BUBBLE_MEDIA_INSET else BUBBLE_TEXT_PADDING_VERTICAL
            )
        ) {
            if (message.imageFile != null) {
                BubbleImage(
                    file = message.imageFile,
                    // 图片圆角跟随气泡圆角但收小：图片缩在气泡内边距里，
                    // 用同样的 16dp 反而会在视觉上比外框更圆。
                    cornerRadius = if (message.isUser) {
                        BUBBLE_INNER_CORNER_RADIUS
                    } else {
                        BUBBLE_INNER_TAIL_CORNER_RADIUS
                    }
                )
            }
            if (message.text.isNotEmpty() || typing) {
                // 用户消息保持纯文本（他发什么就显示什么，不做任何转义/渲染）；
                // AI 回复按 Markdown 渲染（加粗/列表/代码块等，见 AiMarkdownBody）。
                // typing 时占位符「…」不参与 Markdown 解析（解析了也没意义）。
                //
                // 图片 + 文字同时存在时，正文与图片之间垫 6dp：不加这一档，
                // 文字会直接贴在图片底边上（图片四角有圆角，贴太近看着像被压住）。
                if (hasImage) Spacer(Modifier.height(BUBBLE_IMAGE_CAPTION_GAP))
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
                    // 只用现有签名 (markdown, color) 传参（AiMarkdown.kt 由别人维护）。
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
 * [cornerRadius] 由调用方按气泡方向传入（见 [MessageBubble]）：图片圆角必须与气泡
 * 形状**同源**，否则气泡"指向说话者"的那个小角落会与图角错开，看起来像没对齐。
 * 不再用 `MaterialTheme.shapes.small`（本主题 6dp）：那个值是按卡片尺寸定的，
 * 放在 200dp 宽的图片上四个角会显得"方"，与气泡的 16dp 圆角也不呼应。
 *
 * 解码完成前显示一个中性灰占位块：尺寸稳定的占位让手势区域不跳，也不会闪白。
 * 高度取 [BUBBLE_IMAGE_PLACEHOLDER_HEIGHT] 具名常量而不是内联 120dp：
 * 它和图片区的最小高度是同一件事，抽出来才能一句注释说明"为什么是这个数"。
 */
@Composable
private fun BubbleImage(file: File, cornerRadius: Dp) {
    // 2026-10-27：解码从手写 `BitmapFactory.decodeSampledFromFile` 换成 Coil 3。
    //
    // 换掉的理由不是"省代码行数"，而是三件手写版本没有的事：
    // ① **按显示尺寸采样**：Coil 知道目标宽 200dp，会自己算 inSampleSize；
    //    原实现写死 512px，在高分屏上仍然偏大、在低分屏上又浪费。
    // ② **内存缓存 + 复用**：同一张图滚出屏幕再滚回来不必重新解码
    //    （聊天页图片多时这点很明显）。
    // ③ **解码在 Coil 自己的线程池**，不再各调用点各起一个 IO 协程。
    //
    // 占位仍保持"200×120 的中性灰块"：尺寸稳定的占位让手势区域不跳，
    // 也不会出现"先白后图"的闪。加载失败与加载中**用同一块占位**——
    // 对聊天图片来说，"加载不出来"和"还在加载"对用户没有区别，
    // 分两种样式反而多一个要维护的状态。
    val placeholder: @Composable () -> Unit = {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.size(BUBBLE_IMAGE_MAX_WIDTH, BUBBLE_IMAGE_PLACEHOLDER_HEIGHT)
        ) {}
    }
    SubcomposeAsyncImage(
        // 直接传 File：Coil 3 内置 File 模型支持，不需要自定义 Fetcher。
        model = file,
        contentDescription = "消息图片，点击查看大图",
        // FillWidth：按气泡最大 200dp 宽等比显示，高度随原图比例自适应。
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .widthIn(max = BUBBLE_IMAGE_MAX_WIDTH)
            .clip(RoundedCornerShape(cornerRadius)),
        loading = { placeholder() },
        error = { placeholder() }
    )
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
    onStopStreaming: () -> Unit,
    /**
     * 是否允许发图片。
     *
     * 知识库模式传 false：检索只能靠文字，图片既进不了检索、又会让上下文变贵。
     * **把入口藏起来**而不是"发了再默默丢掉"——后者是最坏的一种：用户以为图被看了。
     */
    allowAttachment: Boolean = true,
    placeholder: String = "输入消息…"
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
                if (allowAttachment) {
                    IconButton(onClick = onOpenAttachMenu) {
                        // Add 是 core 图标（与 NotebookScreen 的核对口径一致）。
                        Icon(Icons.Filled.Add, contentDescription = "添加图片")
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(placeholder) },
                    maxLines = 4,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    // 「停止」按钮保持原样（这是流式中最主要的动作）。
                    TextButton(onClick = onStopStreaming) {
                        Text("停止", color = MaterialTheme.colorScheme.primary)
                    }
                    // 流式期间**发送键不禁用而是保留**：它现在承担"在生成中发送斜杠命令"
                    // 这一条通路（最典型的是 `/stop`，以及 `/new` 换会话）。
                    // 普通消息在生成中仍会被 ViewModel 忽略（不排队，理由见其 send 的说明），
                    // 所以这里不放行任何"并发两条流式请求"的可能；命令本身不发请求。
                    SendButton(enabled = canSend, onClick = onSend, label = "命令")
                } else {
                    SendButton(enabled = canSend, onClick = onSend, label = "发送")
                }
            }
        }
    }
}

/**
 * 发送按钮（2026-10-24 需求：**文字 + 底色**，跟随主题色）。
 *
 * ## 为什么从 IconButton 改成带底色的文字按钮
 *
 * 原实现是一枚纯图标按钮（纸飞机图标 + 主题色 tint）。需求给的参考图是
 * 「黑底白字的圆角文字按钮」，要求跟随主题色——即：**容器取主色（primary）、
 * 文字取 onPrimary**，主题换色（蓝/绿/紫等 AccentColor）时按钮自动跟着变。
 * 相比图标，文字「发送」在这个位置更直白：图标按钮要靠形状辨认，
 * 而这里是整个页面的主要动作，写成字没有理解成本。
 *
 * ## 为什么禁用态用 surfaceVariant 而不是降透明度
 *
 * `Button` 默认禁用态会把容器压成 onSurface 的低透明度，在浅色输入栏上容易
 * 看成"半透明的一块"。这里显式指定 `containerColor = surfaceVariant`、
 * `contentColor = onSurfaceVariant`，与输入框的描边同色系，一眼能看出"现在不能点"。
 *
 * ## 禁用态加一圈 1dp 边框（2026-10-25 需求）
 *
 * surfaceVariant 在浅 / 深色主题下都与输入栏底色相近，纯灰块有时会被看成
 * "没渲染完"。补一圈 `outlineVariant` 发丝描边，把按钮的轮廓从底色里勾出来；
 * 启用态保持无框（primary 实底本身已有足够的对比）。
 *
 * ## 尺寸
 *
 * 高 40dp（与 Material3 Button 默认一致）、水平内边距 18dp、圆角 10dp
 * （比输入框的 20dp 胶囊更克制，避免与输入框抢"胶囊"这个形状语言）。
 *
 * @param label 界面文案（正常态「发送」，流式态「命令」）。
 */
@Composable
private fun SendButton(
    enabled: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        // 禁用态的描边条件化：enabled 时传 Modifier（不带 border），
        // 否则 `border(width = 0.dp)` 仍会描一圈发丝线（3.0.8 踩过的坑）。
        modifier = Modifier.then(
            if (enabled) {
                Modifier
            } else {
                Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(10.dp)
                )
            }
        )
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 「添加图片」弹窗里的一个选项卡片（2026-10-24 需求）。
 *
 * ## 样式口径
 *
 * 浅色容器（`surfaceVariant`）+ 12dp 圆角 + 主色图标 + 主文案 bodyLarge +
 * 次文案 bodySmall（onSurfaceVariant）。两张卡片之间 8dp 间距，
 * 与 AlertDialog 自带的 `title` / `text` 内边距一起构成三段式层次。
 *
 * ## 为什么用卡片而不是普通整行
 *
 * 原先两个选项是"两行文字中间没有任何分隔"——在对话框里看起来像一段说明文字，
 * 而不是两个可点的入口。加一层容器底与圆角后，"这是两个选项"这件事不靠文字解释。
 * 点击反馈沿用全局 `Modifier.clickable` 默认水波纹（该弹窗是系统窗口，
 * 不走 `pressScale` 的 CompositionLocal 链，不必强行接入）。
 *
 * @param icon 获取图标的 lambda（用 `() -> ImageVector` 而不是直接传值，
 *   是为了让两个图标只在组合时才解析，与文件里其它图标用法一致）。
 */
@Composable
private fun AttachOptionCard(
    icon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
 * ## 骨架来自公共组件 [FullScreenImageViewer]（本轮需求 20）
 *
 * 原先本文件自己写了一个「Dialog + scrim + 中央一张图」的最小预览，
 * 与 `NotebookScreen.ImagePreviewDialog` 是两份高度重复的实现。
 * 本轮需求要加的是**一整套交互**（双指缩放 / 拖动 / 双击放大），
 * 因此两处一并换成公共组件——否则会出现"记事本能缩放、智能体不能"的分裂。
 * 组件 KDoc 里有完整的取舍说明（为什么抽公共、三套手势的边界、
 * 为什么缩放状态不用 rememberSaveable）。
 *
 * 本页与记事本的差异只剩两处，都留在调用方：
 * - 解码方式不同（记事本走 `NotebookStore.decodePreview`，本页走
 *   [decodeSampledFromFile] 并采样到 1080px 防 OOM）；
 * - 记事本多一个「保存到相册」按钮（挂在组件外层 Box 上，智能体没有这个入口）。
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

    FullScreenImageViewer(
        // 位图解码失败 / 被回收时给 null：组件自己显示加载态，不崩不留空白。
        image = bitmap?.asImageBitmapOrNull(),
        onDismiss = onDismiss
    )
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

// ---- 消息列表的日期分隔 -----------------------------------------------------

/**
 * 消息列表的一项：要么是一条消息，要么是一个日期分隔条。
 *
 * 列表用 `items(display, key = { it.key })` 渲染，所以这个类型必须给出**稳定的 key**。
 *
 * ## 分隔项的 key 为什么不可能与消息 id 相撞
 *
 * [UiMessage.id] 的类型是 `Long`，本项目的 id 由 `AiChatStore.nextId()` 的
 * `AtomicLong.incrementAndGet()` 生成（从 1 起单调递增，见其 KDoc），
 * 永远不会是字符串；而分隔项的 key 是 `AI_DAY_KEY_PREFIX + 日期字符串`。
 * 两者一个 `Long`、一个 `String`，`LazyColumn` 内部比较 key 时类型就不相等，
 * 取值空间也不相交——不存在"日期串恰好等于某个 id"这种可能。
 *
 * 用日期串（而不是"分隔项的下标"）做 key 的另一层原因：只有它**跨重组稳定**。
 * 新消息到来时列表整体后移一位，下标 key 会全部错位、导致 Compose 把每个分隔条
 * 都当成"换了一项"而重建；日期串则始终指向同一个自然日，分隔条原地不动。
 */
private sealed interface AgentChatListItem {
    /** 稳定 key：消息用 id，分隔条用带前缀的日期串（见 interface KDoc）。 */
    val key: Any

    data class Message(val message: UiMessage) : AgentChatListItem {
        override val key: Any get() = message.id
    }

    data class Day(val label: String, val dateKey: String) : AgentChatListItem {
        override val key: Any get() = AI_DAY_KEY_PREFIX + dateKey
    }
}

/**
 * 升序消息列表 → **倒序**的列表项（含日期分隔条），供 `reverseLayout = true` 的
 * `LazyColumn` 直接消费。
 *
 * ## reverseLayout 下如何保证分隔条位置正确（本函数最需要说清的一处）
 *
 * 先把坐标系钉死：`reverseLayout = true` 时，**下标 0 画在最下面，下标越大越靠上**。
 * 既然数据是倒序喂的（下标 0 = 最新消息），那么在这份列表里
 * **"后出现的项"在屏幕上更靠上**。
 *
 * 于是"日期标签要压在这一天消息的**上方**"这条视觉要求，翻译到本列表里就是：
 * 分隔条必须排在这一天全部消息的**后面**（下标更大）。
 *
 * ### 最容易踩的坑（我第一版就踩了，所以写在这里）
 *
 * 顺理成章的写法是在升序语义里思考——"在这一天的第一条消息前插一个标签"，
 * 然后在倒序序列上实现成"迭代到某天第一条消息时先把标签塞进去"。
 * 这是**错的**：那样标签的下标比当天的消息都小，会被画在当天消息的**下方**，
 * 也就是屏幕最底部——最新一条消息下面挂着「今天」，而历史消息逐条往上堆，
 * 分组彻底失效。
 *
 * 因此本函数的做法是：在倒序序列上逐条追加消息，**每当这一天结束时（迭代到
 * 比它更早的一天，或整个迭代结束）才把这一天的标签追加进去**，让标签天然落在
 * 该日所有消息之后。这样反而不需要为"翻转"再做任何补偿计算：
 * 全程只用倒序下标思考一次，屏幕位置自动就对了。
 *
 * ### 顺带保住的两件事
 *
 * - 下标 0 永远是**最新那条消息**（标签在它上方，不会挤到底部），
 *   所以 `animateScrollToItem(0)` 的"贴底到最新"语义完全不变；
 * - [messages] 的**升序**契约由 ViewModel 保证（`messages` 的 KDoc："按时间升序"；
 *   `/compact` 生成的摘要消息也刻意取了被压缩首条的时间戳来维持升序），
 *   所以"相邻两条属于不同自然日"就等价于"这里跨天了"，无需再做排序。
 *
 * ## 时区
 *
 * 自然日按设备时区划分（`ZoneId.systemDefault()`），与 [relativeTime] 的
 * 「昨天」口径、以及本项目 `CalendarScreen` / `util/DateFormatters.kt` 的做法一致：
 * 聊天时间戳也是 `System.currentTimeMillis()` 写的本地时钟，
 * 用同一把尺子才不会出现"列表说昨天、分隔条说今天"。
 *
 * @param today "今天"的基准日。由调用方传入（而不是在这里 `LocalDate.now()`），
 *   是为了让同一次组合里所有分隔文案共用同一个基准——否则跨零点的那一帧
 *   可能前半屏按旧日期算、后半屏按新日期算。
 */
private fun buildAgentChatListItems(
    messages: List<UiMessage>,
    today: LocalDate
): List<AgentChatListItem> {
    val zone = ZoneId.systemDefault()
    // 倒序：下标 0 = 最新消息 = 屏幕上最靠下的一条（见函数 KDoc 的坐标系说明）。
    val reversed = messages.asReversed()
    val items = ArrayList<AgentChatListItem>(reversed.size + 4)

    // 当前"还没收尾"的那一天（倒序迭代中正在累积消息的那一天）。
    var pendingDay: LocalDate? = null

    // 每个自然日已经吐出的分隔条个数：用于给"同一天出现第二段"这种情况兜底生成
    // 互不相同的 key（见下方说明）。正常情况下这里每个日期只会是 1。
    val emittedDays = HashMap<LocalDate, Int>()

    // 收尾：把 pendingDay 的标签追加到**该天全部消息之后**（= 屏幕上方，见 KDoc）。
    fun closeDay() {
        val day = pendingDay ?: return
        // ---- 为什么还要再防一层重复 key ----
        //
        // 上面已经论证过：messages 升序 ⇒ 同一自然日必然连续出现 ⇒ 每个日期
        // 只会命中一次。但 "key 撞车" 在 LazyColumn 里是**直接崩溃**级的后果
        // （重复 key 会抛异常），而这里唯一的依赖是"上游保持升序"这条**契约**
        // （ViewModel 的 KDoc，不是类型系统的保证）。给这个契约加一层廉价的
        // 显式兜底：万一日后有人新增一条"时间戳不按升序追加"的内存消息，
        // 后果从"整个聊天页崩掉"降级为"多显示一条日期条"。
        //
        // 编号由列表内容**确定性**推导（不是随机数 / 不是自增状态），
        // 因此同样的消息列表永远得到同样的 key，跨重组依旧稳定。
        val occurrence = (emittedDays[day] ?: 0) + 1
        emittedDays[day] = occurrence
        val dateKey = if (occurrence == 1) day.toString() else "$day#$occurrence"
        items.add(AgentChatListItem.Day(agentChatDayLabel(day, today), dateKey))
        pendingDay = null
    }

    for (message in reversed) {
        val day = agentChatDayOf(message.timestamp, zone)
        // 跨天：先给上一天收尾（它的标签落在它自己那批消息之后），再开始新的一天。
        if (day != pendingDay) {
            closeDay()
            pendingDay = day
        }
        items.add(AgentChatListItem.Message(message))
    }
    // 倒序里最旧的那一天身后没有"更早的消息"来触发跨天，必须显式收尾一次。
    closeDay()
    return items
}

/** epoch **毫秒** → 设备时区下的自然日。时间戳单位由 [UiMessage.timestamp] 确定（`System.currentTimeMillis()`）。 */
private fun agentChatDayOf(timestamp: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

/**
 * 自然日 → 分隔条文案：「今天」「昨天」，更早给「10月13日」，跨年再补上年份。
 *
 * ## 与 [relativeTime] 的关系（刻意不复用它）
 *
 * 两者是**不同粒度**：`relativeTime` 服务历史列表的一行会话，要回答"多久以前"
 * （所以有「3 分钟前 / 3 周前」这些**日内**精度）；而这里要把一整天的消息圈在一起，
 * 粒度只能是自然日，绝不能说"3 分钟前"——那样同一天里的两条消息会得到两个不同的
 * 标签，"按天分组"这个视觉意图就散了。
 *
 * 但**文案口径保持一致**：今天 / 昨天这两个词与 `relativeTime` 用的是同一套说法
 * （前天的消息在 `relativeTime` 里也叫「前天」，这里不沿用——分隔条上「前天」
 * 不如直接给日期更利于回看定位），因此用户不会在两处看到互相打架的表述。
 * [relativeTime] 本身**不动**，它继续只服务历史列表。
 *
 * 跨年时补年份：聊天记录可以留很久，只写「10月13日」在跨年场景下会有歧义。
 */
private fun agentChatDayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "今天"
    today.minusDays(1L) -> "昨天"
    // 同年只给月日（分隔条是**弱化**信息，多加年份反而比消息本体更抢眼）；
    // 跨年才补年份，此时它是一条必要信息。
    else -> if (day.year == today.year) {
        "${day.monthValue}月${day.dayOfMonth}日"
    } else {
        "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
    }
}

/**
 * 日期分隔条：一枚居中的小胶囊。
 *
 * ## 为什么用胶囊底而不是一行居中灰字
 *
 * 聊天气泡本身是有底色、有描边的"实体"，纯文字分隔条夹在它们中间会显得像
 * 一条漏排的正文。给一层底 + 圆角把它明确标成"标签"，层级关系才清楚：
 * 气泡是内容，它是注解。
 *
 * ## 底色为什么不照抄 [HistoryRow] 的 `surfaceContainerHighest`
 *
 * 那个色**在本页用不了**：`surfaceContainerHighest` 在浅色模式下是 `#FFFFFF`，
 * 而本页消息区底色 `surfaceVariant` 也是 `#FFFFFF`——胶囊会彻底隐形
 * （历史列表能用它，是因为那边的底是 `surface` `#EDEDED`，白卡正好浮起来；
 * 同一个语义色换到白色消息区上就失效了，所以这里必须重新挑）。
 *
 * 最终用 `surfaceDim`（浅色 `#DDDDDD` / 深色 `#0C0C0C`）：它是本主题里唯一
 * 在深浅两套模式下都与 `#FFFFFF` / `#1E1E1E` 拉开足够落差的灰色面
 * （完整对照表见 [MessageBubble] 的配色说明），且与 AI 气泡同源，
 * 列表里"中性面"的观感是统一的。区分它与 AI 气泡靠的是形状与位置——
 * 居中小胶囊 vs 左对齐大圆角块，一眼就能分开。
 *
 * **不用主题色**：日期不是需要强调的信息，用主题色会让每屏多出好几个彩色块，
 * 与"气泡才是内容"的层级关系相反。
 *
 * 文字用 `labelSmall` + `onSurfaceVariant`：这是列表里最次要的一层，
 * 字号与对比度都必须明显弱于消息正文，否则"注解"会跟"内容"抢注意力。
 */
@Composable
private fun AgentChatDayDivider(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 上下各留一档：分隔条与它上方的气泡要拉开，否则会被误读成
            // "那条消息的一部分"。
            .padding(vertical = CHAT_DAY_DIVIDER_PADDING),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceDim,
            shape = RoundedCornerShape(percent = 50),
            // 复用同一圈 1dp 细线：深色模式下分隔条的填充色（`#0C0C0C`）与消息区
            // （`#1E1E1E`）只差 18，小尺寸下光靠填充会有点"糊"，描边（`#2C2C2C`
            // 比两者都亮）正好给它一圈高光轮廓；浅色模式下这条线（`#E5E5E5`）
            // 比填充（`#DDDDDD`）略亮、几乎不可见，属于无副作用。
            // 同时它与 AI 气泡共用"中性面靠 1dp 细线定边界"的同一条规则。
            border = BorderStroke(
                BUBBLE_HAIRLINE_WIDTH,
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = CHAT_DAY_DIVIDER_HORIZONTAL_PADDING,
                    vertical = CHAT_DAY_DIVIDER_VERTICAL_PADDING
                )
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

// ---- 本次视觉打磨引入的尺寸 -------------------------------------------------
//
// 全部抽成具名常量而不是内联字面量：这里每一项的取值都有"为什么是这个数"的
// 理由（写在各自注释里），内联会让理由无处安放，也容易在下次微调时漏改某一处
// （典型的就是气泡圆角与图片圆角必须同步的那组值）。

/** 气泡的常规圆角：16dp，微信观感的近似值。 */
private val BUBBLE_CORNER_RADIUS = 16.dp

/**
 * 气泡"指向说话者"那一角的小圆角：4dp。
 * 与 [BUBBLE_CORNER_RADIUS] 的落差（16 → 4）就是这个方向感的全部来源，别拉平。
 */
private val BUBBLE_TAIL_CORNER_RADIUS = 4.dp

/**
 * 气泡描边宽度：1dp。
 *
 * 用 1dp 而不是 0.5dp / 2dp：0.5dp 在多数设备上会被四舍五入成 1 物理像素，
 * 分辨率低的屏上直接消失；2dp 则从"细线"变成"边框"，与项目扁平卡片的观感不符。
 */
private val BUBBLE_HAIRLINE_WIDTH = 1.dp

/** 文本气泡的水平内边距：14dp（原 12dp，加描边后文字贴线会显得局促）。 */
private val BUBBLE_TEXT_PADDING_HORIZONTAL = 14.dp

/** 文本气泡的垂直内边距：10dp（原 8dp，同上）。 */
private val BUBBLE_TEXT_PADDING_VERTICAL = 10.dp

/**
 * 图片气泡的内边距：4dp（沿用原值，保持"图片气泡比文本气泡紧"这个既有意图）。
 * 抽成常量只为让"图片侧恒为 4dp"这件事在代码里显式可读。
 */
private val BUBBLE_MEDIA_INSET = 4.dp

/**
 * 气泡内图片的圆角：跟随气泡但收小一档（16 → 12 / 4 → 3）。
 *
 * 不收小会让图片看起来"比它的外框还圆"——图片缩在 4dp 内边距里，
 * 视觉半径本来就比外框小；直接沿用 16dp 反而破了"图片嵌在气泡里"的层次。
 */
private val BUBBLE_INNER_CORNER_RADIUS = 12.dp

/** 图片气泡"指向说话者"那一角的图角：与 [BUBBLE_INNER_CORNER_RADIUS] 同源收小。 */
private val BUBBLE_INNER_TAIL_CORNER_RADIUS = 3.dp

/**
 * 图片下方与正文之间的间距：6dp。
 * 图片四角有圆角，正文直接贴上去会像被图片压住；这一档间距是"图 + 文"消息可读性的下限。
 */
private val BUBBLE_IMAGE_CAPTION_GAP = 6.dp

/** 气泡内图片解码完成前的占位块高度：120dp（沿用原值，保证加载前后手势区域不跳）。 */
private val BUBBLE_IMAGE_PLACEHOLDER_HEIGHT = 120.dp

/** 消息列表的水平留白：12dp（沿用原值——气泡宽度已由 0.78 屏宽控制，再收窄会挤压长回答）。 */
private val CHAT_LIST_HORIZONTAL_PADDING = 12.dp

/** 消息列表的垂直留白：6dp（原 10dp，上下留白交给顶栏与输入栏，长对话能多露一行）。 */
private val CHAT_LIST_VERTICAL_PADDING = 6.dp

/** 相邻消息的基准行距：10dp（原 8dp，加入日期分隔后需要更透气的行距）。 */
private val CHAT_MESSAGE_SPACING = 10.dp

/** 日期分隔条自身的上下留白：8dp，把它与前后两天的消息拉开。 */
private val CHAT_DAY_DIVIDER_PADDING = 8.dp

/** 日期分隔条胶囊的水平内边距：10dp，让「10月13日」这种较长的标签不显得局促。 */
private val CHAT_DAY_DIVIDER_HORIZONTAL_PADDING = 10.dp

/** 日期分隔条胶囊的垂直内边距：3dp，胶囊因此比一行正文矮，明确是"注解"而非"内容"。 */
private val CHAT_DAY_DIVIDER_VERTICAL_PADDING = 3.dp

/**
 * 日期分隔项 key 的前缀。
 *
 * 存在的唯一目的是把它与 `Long` 型的消息 id 在**类型与取值**上彻底分开
 * （见 [AgentChatListItem] 的说明）：拼接后是字符串，永远不可能等于任何 id。
 */
private const val AI_DAY_KEY_PREFIX = "agent-chat-day-"
