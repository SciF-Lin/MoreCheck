package com.morecheck.ui.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morecheck.data.AiChatException
import com.morecheck.data.AiChatMessage
import com.morecheck.data.AiChatProviders
import com.morecheck.data.AiChatRepository
import com.morecheck.data.AiChatSession
import com.morecheck.data.AiChatSettings
import com.morecheck.data.AiChatStore
import com.morecheck.data.AiModelInfo
import com.morecheck.data.AiProvider
import com.morecheck.data.AiTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * 「AI 助手」页的单条展示消息。
 *
 * 与落盘层的 [AiChatMessage] 的区别：
 * - `imageFile` 直接是 [File]（界面显示缩略图要用文件本体，不必再拼路径）；
 * - 多一个 [isError]：错误提示（如"API Key 未填"、"发送失败：…"）只活在内存里，
 *   **绝不落盘**——重启后再看到一条"发送失败"没有任何意义，反而像历史被污染；
 * - 多一个 [isLocal]：斜杠命令的系统提示气泡（见 [AiChatViewModel.send]）。
 *   它同样是**内存消息、不落盘**，但显示上必须是普通助手气泡而不是红色错误气泡，
 *   所以不能复用 [isError]——两个语义正交，就分成两个字段。
 *
 * @param isError true = 这条不是真实对话，而是发给用户的错误提示（气泡用 errorContainer）。
 * @param isLocal true = 这条只是界面提示（命令回执等），**不落盘、不参与发给模型的上下文**。
 *   显示样式与普通助手消息一致（`isError = false`），与"内存消息不落盘"的既有口径同源。
 */
data class UiMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val imageFile: File?,
    val timestamp: Long,
    val isError: Boolean = false,
    val isLocal: Boolean = false
)

/**
 * 「AI 助手」的 ViewModel：多会话编排 + 内存状态 + 流式请求的粘合层。
 *
 * ## 依赖与接线约定
 *
 * 构造签名固定为 `(store, repository)`。本类**没有 Context**，而读历史 / 落盘 /
 * 由相对文件名取图片文件都离不开 Context——统一从 [repository] 取
 * （`AiChatRepository` 构造时持有 `AiChatSettingsStore`，后者带 applicationContext，
 * 见其 KDoc）。接线方（`AppViewModelProvider` 的 factory 或 `AppContainer`）只需要：
 *
 * ```
 * val settingsStore = AiChatSettingsStore(appContext)
 * val repository = AiChatRepository(settingsStore)
 * initializer { AiChatViewModel(AiChatStore, repository) }
 * ```
 *
 * ## 多会话的三份状态
 *
 * - [sessions]：会话索引（历史列表页画的就是它），落盘在 `ai_chat/sessions.json`；
 * - [currentSessionId]：当前会话 id；
 * - [messages]：**当前会话**的消息。
 *
 * 三者的一致性由本类维护，写盘时把"消息文件 + 索引"作为一个整体交给
 * [AiChatStore.saveSessionAsync]（它在同一把锁里写两个文件并清理被挤掉的会话）。
 *
 * ## 启动行为：每次启动都开一条**内存里的**新对话
 *
 * 需求钉死了"启动默认开新对话"。实现上刻意**不把这条新会话立刻写进索引**：
 * 空会话进索引会占掉 20 条上限里的一个坑，用户开 20 次应用就能把全部历史挤没。
 * 因此新会话只在"产生第一条消息"时才落盘进索引（见 [persistMessages]）。
 *
 * ## 索引未读完之前拒绝落盘
 *
 * [init] 读索引是异步的，而落盘要用"当前索引 + 本次会话"拼出新索引。
 * 若用户在读完之前就发消息（极快的手速 + 冷启动），拼出来的索引只有一条，
 * 会把整份历史索引**覆盖成一条**——和 store 里那道"读失败不覆盖"的闸门
 * 是同一类事故，这里用 [indexReady] 把落盘挡在读索引之后。
 *
 * ## 为什么流式更新走 MutableStateFlow.update 而不是整列表重赋值
 *
 * 流式回答每秒可能回调几十次 delta。若每次都 `messages.value = messages.value.map…`
 * 直接赋值，会与"切会话""清空"等并发路径发生读改写竞争；
 * `MutableStateFlow.update` 是原子的 CAS 更新，配合按 id 定位占位消息、
 * 每次只改一条的 text 字段，既安全又省掉整份列表的换引用。
 *
 * ## 取消语义
 *
 * [send] 的 Job 存进 [streamJob]，[stopStreaming] 靠它 cancel。仓库层在 IO 阻塞读上
 * 自然中断（见 `AiChatRepository` 的说明），`CancellationException` 一路向上抛——
 * 但"停止生成"是**正常结束**而非失败，所以兜底 catch 里要先区分取消与真错误：
 * 取消时占位消息保留已收到的部分回答并继续抛出；真错误才改成"发送失败：…"。
 */
class AiChatViewModel(
    private val store: AiChatStore,
    private val repository: AiChatRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())

    /** 当前会话的全部消息，按时间升序。含内存中的错误 / 系统提示气泡（都不落盘）。 */
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)

    /** 是否正在流式接收回答。true 期间再点发送会被忽略（见 [send]）。 */
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _settings = MutableStateFlow(AiChatSettings())

    /** 当前设置快照。设置页保存后由 DataStore 流自动回灌刷新。 */
    val settings: StateFlow<AiChatSettings> = _settings.asStateFlow()

    private val _sessions = MutableStateFlow<List<AiChatSession>>(emptyList())

    /** 历史会话索引，按最后更新时间**降序**（最近的在最前）。 */
    val sessions: StateFlow<List<AiChatSession>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow(store.newSessionId())

    /** 当前会话 id。历史列表用它画选中态。 */
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    /** 当前流式请求的 Job；null = 空闲。 [stopStreaming] 靠它 cancel。 */
    private var streamJob: Job? = null

    /**
     * 索引读取完成的信号（见类注释）。落盘前 `await()` 它，
     * 保证拼新索引时手里已经有一份**完整的**旧索引。
     */
    private val indexReady = CompletableDeferred<Unit>()

    init {
        val appContext = repository.appContext
        // 设置：DataStore 流长期收集，用户在设置页改动后这里的快照自动刷新。
        viewModelScope.launch {
            repository.settingsStore.settings.collect { _settings.value = it }
        }
        // 历史索引：一次性读盘（AiChatStore 是文件而非响应式存储，没有持续流可收集）。
        // 读失败返回空列表且绝不覆盖写回（闸门在 store 里），这里照常展示。
        // 读完之后**总是**开一条新的空会话：需求要求"每次启动默认开新对话"，
        // 不自动加载上次会话；旧会话仍可从历史列表切回。
        viewModelScope.launch(Dispatchers.IO) {
            _sessions.value = store.loadSessions(appContext)
            _currentSessionId.value = freshSessionId()
            indexReady.complete(Unit)
        }
    }

    /**
     * 发送一条用户消息（可选带图），并流式接收回答。
     *
     * ## 斜杠命令的入口也在这里
     *
     * 消息**首行**以 `/` 开头时整条按命令处理（见 [handleCommand]，请求不发给模型）。
     * 命令由界面无差别地走同一个 [send]：界面不需要知道有哪些命令，
     * 也就不存在"命令表在界面与 ViewModel 里各存一份"的失同步问题。
     * 附带的图片仍然要由本层消费掉（与下面的图片生命周期同一口径，否则 cacheDir 里
     * 会留下一个没人删的临时件）。
     *
     * 流式中再点发送**直接忽略**（不排队）：并发两条流式请求会让占位消息定位与
     * 落盘顺序都变含糊，而"停止"按钮已提供放弃当前回答的出口，排队又让用户等两轮，
     * 都不如忽略简单明确。
     *
     * ## 图片参数的生命周期（本函数收口，调用方不要再碰）
     *
     * [image] 是界面侧从相册拷出的 **cacheDir 临时文件**。本函数第一步就用
     * [AiChatStore.addImageFile] 把它压缩落盘进图片目录（ImageDecoder 摆正 EXIF、
     * 按 [AiChatStore.IMAGE_MAX_PX] 降采样、JPEG 压缩；失败降级为纯文字），
     * **无论成败都删除临时文件**。此后缩略图、落盘相对名、发给模型的 base64
     * 三处全部以图片目录里那份压缩件为准——不引用 cacheDir（重启即失效），
     * 也不把动辄几 MB 的原图直接塞进请求体。
     *
     * 流程：Key 校验 → 图片入库（IO）→ 上下文快照 → 用户消息进列表 + 落盘 →
     * 插入 assistant 占位 → `streamChat` 逐 delta 更新占位 → 完成后整写落盘。
     * 任何一步异常：占位消息改为 isError=true 的"发送失败：…"；用户消息不回滚
     * （它确实发出去了，回滚反而造成"我到底发过没有"的困惑）。
     */
    fun send(text: String, image: File?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && image == null) return

        val appContext = repository.appContext

        // ---- 斜杠命令：只识别**整条消息的首行**以 / 开头，大小写不敏感 ----
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
        val isCommand = firstLine.startsWith(COMMAND_PREFIX)

        // ⚠️ `/stop` 必须在下面的流式闸门**之前**判断：它是"正在生成时才有意义"的命令，
        // 放到闸门之后会被 `if (_isStreaming.value) return` 直接吞掉，
        // 变成一条永远无法触发的命令（需求明确要求它等价于点「停止」）。
        // 非流式时这里不拦，交给 handleCommand 回一句"当前没有正在生成的回答"。
        if (isCommand && isStopCommand(firstLine) && _isStreaming.value) {
            // 临时图片是界面交给本层消费的，命令路径也必须删掉它。
            if (image != null) {
                viewModelScope.launch(Dispatchers.IO) { runCatching { image.delete() } }
            }
            stopStreaming()
            appendSystem("已停止生成")
            return
        }

        if (_isStreaming.value) return

        if (isCommand) {
            viewModelScope.launch {
                // 临时图片是界面交给本层消费的，命令路径也必须删掉它。
                if (image != null) {
                    withContext(Dispatchers.IO) { runCatching { image.delete() } }
                }
                handleCommand(firstLine, hadImage = image != null)
            }
            return
        }

        val currentSettings = _settings.value
        val provider = AiChatProviders.byId(currentSettings.providerId)
        val apiKey = currentSettings.apiKeyFor(provider)

        // Key 未填：不落盘、不发请求，插一条内存错误气泡（isError 消息不进历史文件）。
        if (apiKey == null) {
            appendError("请先在右上角设置里填写 API Key")
            return
        }

        // 「自定义」提供商的两个必填项：接口地址与模型名（内置三家都有默认值，
        // 不会触发这两条）。同样走内存错误气泡，不产生任何落盘。
        val missing = mutableListOf<String>()
        if (currentSettings.baseUrlFor(provider).isBlank()) {
            missing.add("接口地址（Base URL）")
        }
        if (currentSettings.modelFor(provider).isBlank()) {
            missing.add("模型名")
        }
        if (missing.isNotEmpty()) {
            appendError("当前是自定义提供商，请先在设置里补全：${missing.joinToString("、")}")
            return
        }

        // 先置位再启动：launch 的体是调度后才执行的，若在体内置位，
        // "连续快速点两次发送"能双双穿过检查。
        _isStreaming.value = true
        streamJob = viewModelScope.launch {
            try {
                // ---- 图片入库（重活放 IO 线程）----
                //
                // 临时文件 → 压缩落盘进图片目录，返回**相对文件名**。
                // 无论成败都删除临时文件（它是本层消费的，界面侧不回收）。
                // 失败返回 null：这条消息降级为纯文字。
                val storedRelative: String? = image?.let { temp ->
                    withContext(Dispatchers.IO) {
                        store.addImageFile(appContext, temp).also {
                            runCatching { if (temp.exists()) temp.delete() }
                        }
                    }
                }

                // ---- 上下文快照（在追加新消息之前取，避免本轮消息重复进入 turns）----
                // 只回放非 isError / 非 isLocal 的真实对话；历史用户消息不重发图片——历史里的图
                // 模型上一轮已经"看过"，多模态接口对重复大图按 token 计费，重发纯浪费。
                // 纯图片无文字的消息（text 为空）在历史回放里跳过：部分兼容端点会拒绝
                // 空 content 的轮次，而这条消息的图片信息在当轮已随 imageBase64 发过。
                //
                // takeLast(effectiveContextLimit())：设置项「上下文条数」控制的截断，
                // 只取最近 N 条（`/compact` 压缩后的历史同样走这里，两者叠加生效）。
                val historyTurns: List<AiTurn> = _messages.value
                    .filter { !it.isError && !it.isLocal && it.text.isNotEmpty() }
                    .map { message ->
                        AiTurn(isUser = message.isUser, text = message.text, imageBase64 = null)
                    }
                    .takeLast(currentSettings.effectiveContextLimit())

                // ---- 用户消息进内存列表 + 落盘 ----
                // UI 与落盘用同一个 id：两份状态描述的是同一条消息，id 一致，
                // 以后若要做"按 id 同步修正"才有可比对的锚点。
                // imageFile 解析成图片目录里的绝对 File 给界面；落盘只存相对名。
                val userMessageId = store.nextId()
                val userTimestamp = System.currentTimeMillis()
                val userImageFile: File? =
                    storedRelative?.let { AiChatStore.imageFile(appContext, it) }
                _messages.update {
                    it + UiMessage(
                        id = userMessageId,
                        isUser = true,
                        text = trimmed,
                        imageFile = userImageFile,
                        timestamp = userTimestamp
                    )
                }
                withContext(Dispatchers.IO) {
                    persistMessages(appContext) { list ->
                        list + AiChatMessage(
                            id = userMessageId,
                            isUser = true,
                            text = trimmed,
                            imageFile = storedRelative, // 相对图片目录的文件名（见 AiChatMessage 注释）
                            timestamp = userTimestamp
                        )
                    }
                }

                // ---- assistant 占位 ----
                val placeholderId = store.nextId()
                _messages.update {
                    it + UiMessage(
                        id = placeholderId,
                        isUser = false,
                        text = "",
                        imageFile = null,
                        timestamp = System.currentTimeMillis()
                    )
                }

                // ---- 流式请求 ----
                //
                // base64 从图片目录里的**压缩件**读（不是原图），且只在最新一条
                // 携带图片；历史轮次不重发（理由见上文上下文快照段）。
                val imageDataUri: String? = storedRelative?.let { relative ->
                    withContext(Dispatchers.IO) {
                        AiChatStore.imageFile(appContext, relative)?.let { encodeDataUriOrNull(it) }
                    }
                }
                val turns = historyTurns + AiTurn(
                    isUser = true,
                    text = trimmed,
                    imageBase64 = imageDataUri
                )
                val answer = repository.streamChat(
                    settings = currentSettings,
                    turns = turns,
                    onDelta = { delta ->
                        _messages.update { list ->
                            list.map { message ->
                                if (message.id == placeholderId) {
                                    message.copy(text = message.text + delta)
                                } else {
                                    message
                                }
                            }
                        }
                    }
                )

                // ---- 完整回答落盘 ----
                withContext(Dispatchers.IO) {
                    persistMessages(appContext) { list ->
                        // 内存列表里占位消息已在（同 id），先按 id 去重再放最终文本，
                        // 否则落盘文件里会出现两条相同 id 的助手消息。
                        list.filterNot { it.id == placeholderId } + AiChatMessage(
                            id = placeholderId,
                            isUser = false,
                            text = answer,
                            imageFile = null,
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            } catch (e: AiChatException) {
                // 非 2xx / 网络 / Key 缺失等业务错误。CancellationException 不是它的子类，
                // 主动停止不会误入这里。
                markLastAssistantAsError("发送失败：${e.message ?: "未知错误"}")
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // 主动"停止生成"：不算失败。占位消息保留已收到的部分回答；
                    // 一个字都没收到时空气泡很难看，补一条中性提示。
                    fillEmptyAssistant("已停止生成")
                    // 取消必须继续向上抛（不能吞，见类注释）。
                    throw e
                }
                markLastAssistantAsError("发送失败：${e.message ?: "未知错误"}")
            } finally {
                _isStreaming.value = false
                streamJob = null
            }
        }
    }

    /**
     * 停止当前流式回答（相当于聊天应用的"停止生成"，也是 `/stop` 的实现）。
     *
     * cancel 之后仓库层的阻塞读被中断、协程在 catch 处保留部分回答后结束；
     * 空闲时调用是无害的 no-op。
     */
    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * 开启一条新的空会话（`/new` 与历史列表页的「新对话」都走这里）。
     *
     * 行为：取消正在进行的流式回答（"开新对话"是明确的换会话意图，把回答留在旧会话里
     * 没有意义）→ 换一个未占用的 id → 清空内存消息列表。
     * **不写索引**：空会话落盘会白占 20 条上限里的一个坑（见类注释）。
     *
     * @param announce true = 追加一条系统提示气泡（`/new` 命令用）。
     */
    fun newSession(announce: Boolean = true) {
        stopStreaming()
        _currentSessionId.value = freshSessionId()
        _messages.value = emptyList()
        if (announce) appendSystem("已开启新对话")
    }

    /**
     * 切到某个历史会话：读它的消息文件并替换当前消息列表。
     *
     * 正在流式生成时**拒绝切换**并给出系统气泡：切换会换掉 [currentSessionId]，
     * 而流式回调与落盘都按"当前会话"归属，边生成边切会把回答写进错误的会话。
     * 让用户先点「停止」是唯一没有歧义的出口（停止随时可用，不会卡住）。
     */
    fun openSession(sessionId: String) {
        if (_isStreaming.value) {
            appendSystem("正在生成回答，请先点「停止」再切换会话")
            return
        }
        if (sessionId == _currentSessionId.value) return
        val appContext = repository.appContext
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadMessages(appContext, sessionId) }
            // 读盘期间用户可能又点了别的会话：只认最后一次选择，避免慢的那次覆盖快的。
            if (_currentSessionId.value != sessionId) return@launch
            _messages.value = loaded.map { it.toUi(appContext) }
        }
    }

    /**
     * 删除一个历史会话（界面侧已做二次确认）：删消息文件 + 索引少一项 + 清孤儿图片。
     *
     * 删的是当前会话时，就地开一条新的空会话——留着一条"已删除会话"的消息在屏幕上，
     * 用户再发一条就会把已删的会话重新写回索引，等于删除失败。
     *
     * 正在流式生成时拒绝删除当前会话（同 [openSession] 的理由）。
     */
    fun deleteSession(sessionId: String) {
        if (_isStreaming.value && sessionId == _currentSessionId.value) {
            appendSystem("正在生成回答，请先点「停止」再删除会话")
            return
        }
        val appContext = repository.appContext
        if (sessionId == _currentSessionId.value) newSession(announce = false)
        viewModelScope.launch(Dispatchers.IO) {
            // 先等索引读完再算"剩下的会话"：冷启动时用户可能抢在 init 的读盘之前
            // 就把列表点开了，此时 _sessions 还是空的，直接减会写出一个空索引
            // 把其他会话**全部删掉**（与 persistMessages 的 await 是同一类保护）。
            indexReady.await()
            // 过滤放在临界点之后重新取一次，避免用到 await 之前的旧快照。
            val remaining = _sessions.value.filterNot { it.id == sessionId }
            _sessions.value = remaining
            store.deleteSessionAsync(appContext, sessionId, remaining)
        }
    }

    /**
     * 清空全部聊天记录：删索引 / 会话文件 / 图片目录、清内存列表（含错误气泡）。
     * 不可逆，界面侧应自带确认弹窗。
     */
    fun clearHistory() {
        val appContext = repository.appContext
        stopStreaming()
        _messages.value = emptyList()
        _sessions.value = emptyList()
        // 索引刚被清空，此时直接取一个新 id 不可能撞车（撞车检查见 [freshSessionId]）。
        _currentSessionId.value = store.newSessionId()
        viewModelScope.launch(Dispatchers.IO) {
            store.clearAll(appContext)
        }
    }

    /** 保存设置（设置页调用）。DataStore 流会自动把新值回灌进 [settings]。 */
    fun saveSettings(s: AiChatSettings) {
        viewModelScope.launch {
            repository.settingsStore.save(s)
        }
    }

    /**
     * 拉取当前提供商的可用模型列表（「获取模型列表」按钮）。
     *
     * 网络与解析全部在仓库层（见 [AiChatRepository.listModels]）；本函数做线程桥接、
     * **顺手把拉取结果存进设置**、再回调界面。**回调在主线程**（viewModelScope 默认调度器），
     * 设置页可以直接用它改 UI 状态。
     *
     * 为什么要在这里存而不是让设置页自己塞：模型列表与"每个模型的思考深度"是本轮
     * 新增的两个派生数据，`/models`、`/reasoninglist` 两个命令都要读它们，
     * 界面（设置页）与命令（聊天页）用的是**两个不同的 ViewModel 实例**
     * （各自挂在导航目的地上），只有落进 DataStore 才能互相看见。
     *
     * @param onResult 成功时回调模型信息列表（可能为空列表 = 服务商没返回模型）；
     *   失败时不回调本参数，改回调 [onError]。
     * @param onError 失败时回调中文错误描述（来自 AiChatException.message）。
     */
    fun fetchModels(onResult: (List<AiModelInfo>) -> Unit, onError: (String) -> Unit) {
        val current = _settings.value
        viewModelScope.launch {
            try {
                val models = repository.listModels(current)
                val provider = AiChatProviders.byId(current.providerId)
                // 两张表一起更新：
                // - modelLists：提供商 → 已拉取的模型 id 列表（`/models` 按序号列的是它）；
                // - reasoningEfforts：模型 id → 该模型的等级列表（`/reasoninglist` 读的是它）。
                // 只增不减：键是服务端给的模型 id，一个 id 长期有效，量级可忽略；
                // 而"这次没返回"不代表该模型不存在了（用户可能换了 baseUrl）。
                saveSettings(
                    current.copy(
                        modelLists = current.modelLists + (provider.id to models.map { it.id }),
                        reasoningEfforts = current.reasoningEfforts +
                            models.filter { it.reasoningEfforts.isNotEmpty() }
                                .associate { it.id to it.reasoningEfforts }
                    )
                )
                onResult(models)
            } catch (e: AiChatException) {
                onError(e.message ?: "获取失败")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onError("获取失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // ---- 斜杠命令 -----------------------------------------------------------

    /**
     * 处理一条斜杠命令（见 [COMMAND_HELP] 的实现侧对照）。
     *
     * 命令**不发给模型**，结果统一用一条内存系统气泡展示（[appendSystem]）。
     * 参数按空白切分；命令名大小写不敏感；未知命令给一句统一提示，
     * 而不是静默忽略——用户敲错命令时最需要知道的就是"这条路不通"。
     *
     * 只有**首行**参与解析（[send] 只把首行传进来）：命令后面附一段正文时，
     * 正文被忽略而不报错。多行输入下"整条都当命令"会让用户以为正文发出去了。
     *
     * @param line 首行命令原文（一定以 `/` 开头）。
     * @param hadImage 这条消息是否附带图片（命令不处理图片，提示里明说一下，
     *   免得用户以为图片也一起生效了）。
     */
    private suspend fun handleCommand(line: String, hadImage: Boolean) {
        val parts = line.split(WHITESPACE).filter { it.isNotEmpty() }
        val name = parts.firstOrNull()?.lowercase().orEmpty()
        val args = parts.drop(1)
        val imageNote = if (hadImage) "\n（命令不携带图片，已忽略随消息附带的图片）" else ""
        val appContext = repository.appContext
        val current = _settings.value
        val provider = AiChatProviders.byId(current.providerId)
        val model = current.modelFor(provider)

        when (name) {
            "/new" -> {
                newSession(announce = false)
                appendSystem("已开启新对话" + imageNote)
            }

            "/compact" -> {
                val compacted = compactCurrentSession(appContext)
                if (compacted == null) {
                    appendSystem("当前会话内容不多（不足 ${COMPACT_KEEP_RECENT + 1} 条），无需压缩" + imageNote)
                } else {
                    appendSystem("已把更早的 ${compacted.first} 条消息压缩成一条摘要，保留最近 ${compacted.second} 条" + imageNote)
                }
            }

            "/models" -> {
                val ids = current.modelLists[provider.id].orEmpty()
                if (ids.isEmpty()) {
                    appendSystem("请先在设置里获取模型列表" + imageNote)
                } else {
                    val body = ids.mapIndexed { index, id ->
                        val mark = if (id == model) "（当前）" else ""
                        "${index + 1}. $id$mark"
                    }.joinToString("\n")
                    appendSystem("可用模型（${ids.size}）：\n$body\n\n用 /model 序号 切换" + imageNote)
                }
            }

            "/reasoninglist", "/reasonings" -> {
                appendSystem(reasoningListText(current, provider, model) + imageNote)
            }

            "/reasoning" -> {
                appendSystem(setReasoningEffort(current, provider, model, args) + imageNote)
            }

            "/model" -> {
                appendSystem(setModel(current, provider, args) + imageNote)
            }

            "/stop" -> {
                if (_isStreaming.value) {
                    stopStreaming()
                    appendSystem("已停止生成")
                } else {
                    appendSystem("当前没有正在生成的回答")
                }
            }

            "/help" -> appendSystem(COMMAND_HELP + imageNote)

            else -> appendSystem("未知命令，输入 /help 查看全部命令")
        }
    }

    /**
     * `/compact` 的实现：保留最近 [COMPACT_KEEP_RECENT] 条，把更早的消息压成一条
     * **本地生成**的 assistant 摘要消息（不调模型，见 [summarize]），并立即落盘。
     *
     * 必须落盘：压缩的目的就是"让后面的请求少带点上下文"，只改内存的话重启即失效，
     * 用户会看到"压了但没用"的诡异效果。
     *
     * @return `(被压缩的条数, 保留的条数)`；没有可压缩的内容时返回 null。
     */
    private suspend fun compactCurrentSession(appContext: Context): Pair<Int, Int>? {
        val real = _messages.value.filter { !it.isError && !it.isLocal }
        if (real.size <= COMPACT_KEEP_RECENT) return null

        val older = real.dropLast(COMPACT_KEEP_RECENT)
        val recent = real.takeLast(COMPACT_KEEP_RECENT)
        val summary = UiMessage(
            id = store.nextId(),
            isUser = false,
            text = summarize(older),
            imageFile = null,
            // 时间戳取被压缩内容的**第一条**：落盘后读回来按 timestamp 升序排，
            // 摘要必须排在保留下来的最近几条之前（取 now 会把它排到最后）。
            timestamp = older.first().timestamp
        )
        _messages.value = listOf(summary) + recent
        withContext(Dispatchers.IO) {
            // combine 传恒等：内存列表刚刚就是最终形态，落盘照抄即可。
            persistMessages(appContext) { it }
        }
        return older.size to recent.size
    }

    /**
     * 把一批更早的消息拼成一条摘要文本。
     *
     * 需求钉死了"本地截断，不调模型"：每条消息取**首尾各 [SUMMARY_EDGE_CHARS] 字**，
     * 中间用省略号表示。取舍是"信息密度换免费与确定性"——调模型做摘要要花一次
     * 请求的钱与几秒延迟，还可能失败；首尾截断至少保住了每轮对话的"开头在问什么"
     * 与"结尾落在哪"，对"翻回上上文看看聊过什么"这个真实用途够用。
     */
    private fun summarize(older: List<UiMessage>): String {
        val builder = StringBuilder("【历史摘要】以下是更早的 ${older.size} 条对话（已压缩，仅保留首尾各 $SUMMARY_EDGE_CHARS 字）：")
        for (message in older) {
            val role = if (message.isUser) "用户" else "AI"
            val body = if (message.text.isBlank()) "（图片消息）" else abbreviate(message.text)
            builder.append("\n- ").append(role).append("：").append(body)
        }
        return builder.toString()
    }

    /** 单条消息的首尾截断：短消息原样，长消息 `前 120 字……后 120 字`（空白压成单空格）。 */
    private fun abbreviate(text: String, edge: Int = SUMMARY_EDGE_CHARS): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        if (oneLine.length <= edge * 2) return oneLine
        return oneLine.take(edge) + "……" + oneLine.takeLast(edge)
    }

    /** `/reasoninglist`（别名 `/reasonings`）与 `/reasoning` 无参时共用的列表文本。 */
    private fun reasoningListText(
        settings: AiChatSettings,
        provider: AiProvider,
        model: String
    ): String {
        if (model.isBlank()) return "当前提供商（${provider.label}）还没有模型，请先在设置里选择模型"
        val efforts = settings.reasoningEffortsFor(model)
        if (efforts.isEmpty()) {
            return "当前模型 $model 没有可用的思考深度（服务商未提供，或该模型不支持）"
        }
        val selected = settings.reasoningEffortFor(model)
        val body = efforts.mapIndexed { index, effort ->
            val mark = if (effort == selected) "（当前）" else ""
            "${index + 1}. $effort$mark"
        }.joinToString("\n")
        return "当前模型：$model\n可用的思考深度：\n$body\n\n用 /reasoning 序号 切换，/reasoning --default 恢复默认"
    }

    /**
     * `/reasoning [序号 | 等级ID | --default]`：
     * - 无参 = 查看当前（等价于 `/reasoninglist` 的简版）；
     * - `--default` = 清掉选择，请求体里不再带 `reasoning_effort`，由服务端用自己的默认值；
     * - 数字 = 按 `/reasoninglist` 的序号选；
     * - 其他字符串 = 按等级 id 精确匹配（大小写敏感——id 是服务端定义的，
     *   界面里的胶囊显示的也是原文，宽松匹配反而会掩盖"服务端换了 id"这件事）。
     */
    private fun setReasoningEffort(
        settings: AiChatSettings,
        provider: AiProvider,
        model: String,
        args: List<String>
    ): String {
        val efforts = settings.reasoningEffortsFor(model)
        val selected = settings.reasoningEffortFor(model)
        val arg = args.firstOrNull()

        if (arg == null) {
            val currentText = selected ?: "未设置（服务端默认）"
            return reasoningListText(settings, provider, model) + "\n\n当前思考深度：$currentText"
        }
        if (efforts.isEmpty()) {
            return "当前模型 $model 没有可用的思考深度（服务商未提供，或该模型不支持）"
        }
        if (arg == "--default" || arg.equals("-d", ignoreCase = true)) {
            if (selected == null) return "当前思考深度本来就是默认（未设置）"
            saveSettings(settings.copy(reasoningEffort = settings.reasoningEffort - model))
            return "已恢复默认思考深度（请求不再带 reasoning_effort）"
        }
        val picked = arg.toIntOrNull()?.let { index -> efforts.getOrNull(index - 1) } ?: arg
        if (picked !in efforts) {
            return "无效的思考深度：$arg\n可用取值：" + efforts.joinToString("、")
        }
        saveSettings(settings.copy(reasoningEffort = settings.reasoningEffort + (model to picked)))
        return "已把 $model 的思考深度切换为：$picked"
    }

    /**
     * `/model [序号 | 完整模型ID] [推理等级ID]`：
     * - 无参 = 查看当前模型（含它的思考深度）；
     * - 序号 = 从**已拉取**的模型列表里选（未拉取过时提示去设置页拉）；
     * - 其他字符串 = 直接当模型 id 用（手填是拉取失败时的兜底出口，也支持中转站的
     *   私有模型名）；
     * - 可选的第二个参数 = 顺手给新模型设一个思考深度；该模型不支持这个等级时
     *   **只切模型、不设等级**并在回执里说明（拿一个无效 id 发请求只会换回 400）。
     *
     * 注意这里切的是"**当前提供商的模型**"（与设置页的「模型名」是同一个存储项）：
     * 设置页是模型的唯一事实来源，再存一份"每会话模型"必然与之冲突。
     */
    private fun setModel(
        settings: AiChatSettings,
        provider: AiProvider,
        args: List<String>
    ): String {
        val currentModel = settings.modelFor(provider)
        val arg = args.firstOrNull()
        if (arg == null) {
            val currentText = currentModel.ifBlank { "未设置" }
            val effort = settings.reasoningEffortFor(currentModel) ?: "未设置（服务端默认）"
            return "当前提供商：${provider.label}\n当前模型：$currentText\n思考深度：$effort\n\n" +
                "用法：/model 序号 | /model 完整模型ID [等级ID]"
        }

        val ids = settings.modelLists[provider.id].orEmpty()
        val target: String = if (arg.toIntOrNull() != null) {
            val index = arg.toInt()
            if (ids.isEmpty()) return "请先在设置里获取模型列表"
            ids.getOrNull(index - 1)
                ?: return "序号超出范围：${ids.size} 个可用模型（1~${ids.size}）"
        } else {
            arg
        }

        val effortArg = args.getOrNull(1)
        val efforts = settings.reasoningEffortsFor(target)
        val newEfforts = if (effortArg == null) {
            settings.reasoningEffort
        } else if (effortArg in efforts) {
            settings.reasoningEffort + (target to effortArg)
        } else {
            settings.reasoningEffort
        }
        saveSettings(
            settings.copy(
                models = settings.models + (provider.id to target),
                reasoningEffort = newEfforts
            )
        )
        val effortNote = when {
            effortArg == null -> ""
            effortArg in efforts -> "\n思考深度已设为：$effortArg"
            efforts.isEmpty() -> "\n（该模型不支持思考深度，等级 $effortArg 未生效）"
            else -> "\n（该模型不支持等级 $effortArg，未生效；可用：" + efforts.joinToString("、") + "）"
        }
        return "已切换模型为：$target" + effortNote
    }

    /** `/help` 展示的命令表（与 [handleCommand] 的 when 分支一一对应）。 */
    private val COMMAND_HELP: String = "" +
        "可用命令（在输入框直接发送，不发给模型）：\n" +
        "/new 开启新对话\n" +
        "/compact 压缩更早的历史（保留最近 $COMPACT_KEEP_RECENT 条）\n" +
        "/models 列出可用模型（需先在设置里获取）\n" +
        "/reasoninglist 列出当前模型的思考深度（别名 /reasonings）\n" +
        "/reasoning [序号|等级ID|--default] 查看或切换思考深度\n" +
        "/model [序号|完整模型ID] [等级ID] 查看或切换模型\n" +
        "/stop 停止当前生成\n" +
        "/help 显示本帮助"

    // ---- 内部实现 -----------------------------------------------------------

    /**
     * 首行是否是 `/stop`（含别名判断的大小写不敏感口径）。
     * 单独抽出来是因为它要在 [send] 的**流式闸门之前**被判断（见那里的说明）。
     */
    private fun isStopCommand(firstLine: String): Boolean =
        firstLine.split(WHITESPACE).firstOrNull()?.lowercase() == "/stop"

    /**
     * 取一个当前索引里没被占用的会话 id。
     *
     * 正常情况就是 [AiChatStore.newSessionId]（当前毫秒）。撞车只有一种可能：
     * 启动迁移旧数据时用同一个毫秒创建了会话（见 `AiChatStore.newSessionId` 的说明）。
     * 真撞上时就取"已占用 id 的最大值 + 1"——仍是纯数字时间戳形态，
     * **不需要等待时钟前进**，在不确定的时刻也不会像 sleep 那样卡住主线程。
     */
    private fun freshSessionId(): String {
        val taken = _sessions.value.mapTo(HashSet()) { it.id }
        val id = store.newSessionId()
        if (id !in taken) return id
        var candidate = (taken.mapNotNull { it.toLongOrNull() }.maxOrNull() ?: 0L) + 1
        while (candidate.toString() in taken) candidate++
        return candidate.toString()
    }

    /**
     * 落盘"当前会话的真实对话 + [combine] 产生的增量"，并同步刷新会话索引。
     *
     * `saveSessionAsync` 是"整写当前会话"的口径（见 `AiChatStore` 类注释），
     * 所以每次落盘都基于**当前内存列表**：先滤掉 isError / isLocal 气泡
     * （提示类消息绝不进历史文件，见 [UiMessage] 注释），转成落盘消息后交给 [combine]。
     *
     * 索引的拼装也在这里，且必须在这里：标题 / 预览都是从**落盘消息**推导出来的
     * （见 `AiChatStore.sessionTitle` / `sessionPreview`），放在别处就要把同一份
     * "滤气泡 + 转落盘类型"的逻辑再写一遍。
     * 标题一旦定下就**不再重算**（只有还是「新对话」时才随消息升级）：
     * 让一条会话的名字随内容变来变去，历史列表就失去了"认得出这是哪次对话"的作用。
     *
     * ⚠️ **suspend 且必须在 IO 线程上下文里调用**：开头 await [indexReady]，
     * 保证拼新索引时手里是一份完整的旧索引（见类注释）。
     */
    private suspend fun persistMessages(
        context: Context,
        combine: (List<AiChatMessage>) -> List<AiChatMessage>
    ) {
        indexReady.await()
        val diskMessages = combine(_messages.value.filter { !it.isError && !it.isLocal }.map { it.toDisk() })
        val sessionId = _currentSessionId.value
        val existing = _sessions.value.firstOrNull { it.id == sessionId }
        val computedTitle = store.sessionTitle(diskMessages)
        val entry = AiChatSession(
            id = sessionId,
            // 已有标题优先；只有它还是占位标题（首次消息是纯图 / 空文本）时才升级。
            title = existing?.title?.takeIf { it != AiChatStore.NEW_SESSION_TITLE } ?: computedTitle,
            updatedAt = System.currentTimeMillis(),
            preview = store.sessionPreview(diskMessages)
        )
        // 新的完整索引：本次会话（放在最前）+ 其余会话，按更新时间降序裁到 20 条。
        // 被裁掉的那些会话文件由 store 在同一个临界区里删掉（含它们独占的图片）。
        val index = (_sessions.value.filterNot { it.id == sessionId } + entry)
            .sortedByDescending { it.updatedAt }
            .take(AiChatStore.MAX_SESSIONS)
        _sessions.value = index
        store.saveSessionAsync(context, entry, diskMessages, index)
    }

    /** 追加一条内存错误气泡（红色），**不落盘**、不参与发给模型的上下文。 */
    private fun appendError(text: String) {
        _messages.update {
            it + UiMessage(
                id = store.nextId(),
                isUser = false,
                text = text,
                imageFile = null,
                timestamp = System.currentTimeMillis(),
                isError = true
            )
        }
    }

    /**
     * 追加一条内存系统气泡（普通助手样式）：斜杠命令的结果、以及"不能这么做"的说明。
     * 见 [UiMessage.isLocal] 的说明——不落盘、不回放给模型。
     */
    private fun appendSystem(text: String) {
        _messages.update {
            it + UiMessage(
                id = store.nextId(),
                isUser = false,
                text = text,
                imageFile = null,
                timestamp = System.currentTimeMillis(),
                isError = false,
                isLocal = true
            )
        }
    }

    /**
     * 把最后一条 assistant 消息改成错误提示（找不到时追加一条错误气泡兜底）。
     *
     * 已经收到过部分回答的话，部分内容**保留**、错误说明另起一段追加在后面：
     * 用户的提问和模型已输出的内容都是真实发生的，整条抹掉反而像"被吃掉了"。
     *
     * 定位时跳过 [UiMessage.isLocal] 的系统气泡：否则"先敲了 /help、再发送失败"
     * 会把那条帮助文本整段改成红字错误。
     */
    private fun markLastAssistantAsError(errorText: String) {
        _messages.update { list ->
            val lastAssistantIndex = list.indexOfLast { !it.isUser && !it.isLocal }
            if (lastAssistantIndex < 0) {
                list + UiMessage(
                    id = store.nextId(),
                    isUser = false,
                    text = errorText,
                    imageFile = null,
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
            } else {
                list.mapIndexed { index, message ->
                    if (index == lastAssistantIndex) {
                        val newText =
                            if (message.text.isEmpty()) errorText
                            else "${message.text}\n\n$errorText"
                        message.copy(text = newText, isError = true)
                    } else {
                        message
                    }
                }
            }
        }
    }

    /** 占位消息一个字都没收到就被停止时，给它一个中性提示，避免留下空气泡。 */
    private fun fillEmptyAssistant(text: String) {
        _messages.update { list ->
            list.map { message ->
                if (!message.isUser && !message.isLocal && message.text.isEmpty()) {
                    message.copy(text = text)
                } else {
                    message
                }
            }
        }
    }

    /**
     * 把一张图片文件读成 `data:image/jpeg;base64,...` 形式的 data URI。
     *
     * 传进来的文件是 [AiChatStore.addImage] 落盘的 JPEG（已降采样、已按 EXIF 摆正），
     * 直接读字节编码即可，不需要再过一遍解码。**必须在 IO 线程调用**。
     * 读失败（文件被清掉等）返回 null：这条消息降级为纯文字发送。
     */
    private fun encodeDataUriOrNull(file: File): String? = runCatching {
        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(file.readBytes())
    }.getOrNull()

    // ---- UiMessage / AiChatMessage 互转 -------------------------------------

    /** 落盘消息 → 界面消息。相对文件名现场解析成 [File]（解析失败即 null，界面回落占位）。 */
    private fun AiChatMessage.toUi(context: Context): UiMessage = UiMessage(
        id = id,
        isUser = isUser,
        text = text,
        imageFile = imageFile?.let { AiChatStore.imageFile(context, it) },
        timestamp = timestamp
    )

    /** 界面消息 → 落盘消息。图片只存**相对文件名**（见 [AiChatMessage.imageFile] 注释）。 */
    private fun UiMessage.toDisk(): AiChatMessage = AiChatMessage(
        id = id,
        isUser = isUser,
        text = text,
        imageFile = imageFile?.name,
        timestamp = timestamp
    )
}

/** `/compact` 保留的最近消息条数（需求口径：保留最近 6 条，更早的压成摘要）。 */
private const val COMPACT_KEEP_RECENT = 6

/** 摘要里每条消息首尾各保留的字符数（需求口径：各 120 字）。 */
private const val SUMMARY_EDGE_CHARS = 120

/** 斜杠命令前缀。 */
private const val COMMAND_PREFIX = "/"

/** 命令参数切分与摘要压缩共用的空白正则。 */
private val WHITESPACE = Regex("\\s+")
