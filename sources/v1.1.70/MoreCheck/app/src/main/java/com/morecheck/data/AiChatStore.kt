package com.morecheck.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 「实验室 → AI 助手」的单条聊天记录。
 *
 * @param id 全局自增 id（[AiChatStore.nextId]），界面用它定位"流式回答中的那条占位消息"做增量更新。
 * @param isUser true = 用户消息，false = 助手消息。
 * @param text 正文。助手消息在流式输出期间会不断变化（由 ViewModel 持有），落盘时是最终文本。
 * @param imageFile 用户随消息附带的图片文件名，**相对** [AiChatStore] 图片目录
 *   （`filesDir/ai_chat/images`）而非绝对路径。
 *   为什么存相对名：应用私有目录在应用卸载 / 换设备后整体消失，绝对路径毫无可移植性，
 *   反而会在"备份恢复到另一台手机"时变成一堆指向不存在位置的死路径；
 *   相对名配合 [AiChatStore.imageFile] 现场拼出绝对路径，目录怎么挪都不会失效。
 *   null = 纯文字消息。
 * @param timestamp 毫秒时间戳。历史按它升序展示与落盘。
 */
data class AiChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val imageFile: String?,
    val timestamp: Long
)

/**
 * 一条会话在历史列表里需要的最小信息（`ai_chat/sessions.json` 里的一项）。
 *
 * 与 [AiChatMessage] 分开：消息体在 `sessions/<id>.json` 里，索引文件里只放
 * "列表页要画的四个字段"。历史列表因此不需要把 20 个会话的消息体全读一遍——
 * 打开设置页那种轻场景也不会顺带做 20 次 JSON 解析。
 *
 * @param id 会话 id，即 `System.currentTimeMillis().toString()`（见 [AiChatStore.newSessionId]）。
 *   同时是消息文件名 `sessions/<id>.json`。
 * @param title 会话标题：首次产生用户消息时取其前 [AiChatStore.SESSION_TITLE_MAX_CHARS] 个字符；
 *   还没有任何消息时是 [AiChatStore.NEW_SESSION_TITLE]。
 * @param updatedAt 最后更新时间（毫秒）。索引按它**降序**展示，也用它判断"最旧的那个"。
 * @param preview 最后一条有文字的消息的预览（[AiChatStore.sessionPreview] 截断后的单行文本）。
 *   空串 = 该会话还没有可预览的文字。
 */
data class AiChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String
)

/**
 * AI 聊天记录的本地持久化（文件 + org.json，**刻意不用 Room**）。
 *
 * ## 为什么不用数据库
 *
 * 聊天记录的访问模式只有两种："整读一个会话"与"整写当前会话"。没有按 id 查单条、
 * 没有按关键字检索、没有分页——对这些需求 Room 的建表、DAO、迁移全套机械成本
 * 换不来任何收益；几个 JSON 文件 + 整体读写（与 `NotebookStore` 的思路一致）
 * 几十行就够，且**不触碰**工程里既有的 `CheckDatabase`，不新增任何依赖。
 * 单会话消息上限 [MAX_MESSAGES] 保证单个文件不会无限膨胀（500 条文本 + 若干图片名，
 * 量级在几百 KB），会话数上限 [MAX_SESSIONS] 保证文件个数不会无限膨胀。
 *
 * ## 目录布局（多会话版）
 *
 * - 会话索引：`filesDir/ai_chat/sessions.json`（一个 JSON 数组，元素见 [AiChatSession]）
 * - 会话消息：`filesDir/ai_chat/sessions/<sessionId>.json`（结构沿用旧的单文件版本：
 *   一个 [AiChatMessage] 的 JSON 数组）
 * - 图片本体：`filesDir/ai_chat/images/img_<时间戳>_<序号>.jpg`（消息里只存文件名）
 *
 * 图片**不**按会话分目录：图片是"被消息引用的资源"，一处存放让引用检查只需要
 * "扫一遍所有会话文件"，不必再去推理"这个会话的图片目录在哪、删会话时目录删干净没有"。
 *
 * ## 旧数据迁移（`messages.json` → 单会话）
 *
 * v1.1.69 之前的版本把全部消息写在 `filesDir/ai_chat/messages.json`。本版 [loadSessions]
 * 发现**索引文件不存在**而旧文件存在时，会把旧文件整体迁移成一条会话（v1.1.69 之前没有会话概念，
 * 全部消息在语义上本来就属于同一条会话），随后把旧文件改名为 `messages.json.migrated`
 * 就地封存——**不删除**：新结构万一出问题，用户的原始数据还在，可以人工回捞。
 * 迁移只在"索引文件不存在"时尝试一次（迁移成功即写索引），失败（旧文件解析不出来）
 * 则原样留着、当次按"没有历史"处理，与下面的读失败口径一致。
 *
 * ## 读失败的闸门口径（防数据丢失）
 *
 * 历史文件已存在但解析失败（进程被杀写了一半、磁盘损坏等）时，读函数返回空列表——
 * 调用方无法区分"真的没有历史"与"读不出来"。若此时照常落盘，就会用一份只剩最新几条的
 * 列表**把整个历史覆盖掉**，读失败反而变成数据丢失。所以两个写函数
 * （[saveSessionAsync] / [deleteSessionAsync]）落盘前都有一道闸门：目标文件已存在且读不出来时
 * **拒绝写**，宁可这一条消息没存上，也不把旧历史冲掉（与 `NotebookStore.read` 返回 null
 * 的取舍同理）。
 *
 * 另外写入用"临时文件 + 重命名"的近原子写法：进程在写一半时被杀，
 * 最坏留下一个 `.tmp` 残件，正式文件仍是上一次的完整内容。
 *
 * ## 为什么有进程级 [ioScope] 与一把 [ioMutex]
 *
 * 落盘时机由 ViewModel 驱动，而 ViewModel 的 `viewModelScope` 会随页面销毁被取消；
 * "发完消息就退出页面"是极常见的路径，若用会取消的作用域写盘，写操作可能在执行前就被取消——
 * 丢消息。所以与 `NotebookStore` 相同：用与界面生命周期无关的进程级作用域提交，
 * 带 `SupervisorJob` + `runCatching`，单次磁盘失败不崩应用、不拖垮其它写任务。
 *
 * [ioMutex] 是本版新增的：`Dispatchers.IO` 是线程池，"写会话文件 + 写索引 + 清孤儿图片"
 * 这三步必须在同一个临界区里按顺序完成——否则两次并发落盘会互相看到对方的中间状态
 * （A 刚写完会话文件、B 就开始扫索引，把 A 的图片当孤儿删掉）。
 */
object AiChatStore {

    /**
     * 单个会话的消息上限（条）。
     *
     * 落盘时超出就删**最旧**的（聊天是"越新越有价值"的数据，与最近使用的时长历史同思路）。
     * 500 条对"一个本地小助手"绰绰有余，同时把 JSON 文件与孤儿清理的扫描成本都钉在常数级。
     */
    const val MAX_MESSAGES = 500

    /**
     * 会话条数上限。
     *
     * 超出时删**最旧**的一条（按 [AiChatSession.updatedAt]），连同它的消息文件与被它独占的图片。
     * 取 20：历史列表一屏能翻完，索引文件与孤儿图片扫描的成本都封在常数级；
     * 再多的会话对"翻回上周问过什么"这个真实诉求也没有边际收益。
     */
    const val MAX_SESSIONS = 20

    /** 会话标题的截断长度（字符）。见 [sessionTitle]。 */
    const val SESSION_TITLE_MAX_CHARS = 20

    /** 还没有任何消息的会话标题。 */
    const val NEW_SESSION_TITLE = "新对话"

    /**
     * 落盘图片的最长边（像素）。
     *
     * 取 1280：聊天图片是"发给模型看"的，主流多模态接口对输入图的内部处理分辨率
     * 也在这个量级，1280 足够模型识别内容；同时把动辄 4000×3000 的原图降采样，
     * JPEG 后约 200~400KB，既不占满私有目录，base64 后（约再放大 1/3）也不会把
     * 请求体撑到离谱。比 `NotebookStore` 的 1600 略小，因为这里的图片还要走网络。
     */
    const val IMAGE_MAX_PX = 1280

    /** JPEG 压缩质量。85 是"肉眼无损与体积"的常见平衡点，走网络的图片比本地图略压一点。 */
    const val JPEG_QUALITY = 85

    /**
     * 聊天气泡缩略图的解码上限（最长边像素）。
     *
     * 气泡内图片显示尺寸只有一百多 dp，按 3x 屏算几百 px，720 足够清晰；
     * 列表里多张图同时解码时，小上限意味着更低的峰值内存。
     */
    const val THUMB_DECODE_MAX_PX = 720

    /**
     * 孤儿图片清理的"宽限期"（毫秒）。
     *
     * 清理逻辑是"删掉不被任何消息引用的文件"，但存在一个合法的间隙：
     * 用户刚通过 [addImage] 选好了图、**还没点发送**——这个文件此刻不被任何消息引用，
     * 却绝不能被并发的落盘清理顺手删掉。宽限期内（5 分钟）新写入的文件一律豁免；
     * 超过 5 分钟仍无消息引用的文件才视为真正的孤儿。取 5 分钟是"挑图犹豫"的
     * 合理上限，又不会让真正的垃圾文件堆积太久。
     */
    private const val ORPHAN_GRACE_MS = 5L * 60 * 1000

    private const val DIR_NAME = "ai_chat"
    private const val IMAGE_DIR_NAME = "images"
    private const val SESSION_DIR_NAME = "sessions"
    private const val INDEX_FILE_NAME = "sessions.json"
    private const val SESSION_FILE_SUFFIX = ".json"

    /** 旧版单文件历史的文件名（迁移源，见类注释）。 */
    private const val LEGACY_HISTORY_FILE_NAME = "messages.json"

    /**
     * 合法的会话 id 形态。
     *
     * 正常来源只有 [newSessionId]（纯数字时间戳），但 id 是从 JSON 里读回来的——
     * 若哪天文件被外部改坏、塞进 `../` 之类，用它拼路径就能越出会话目录读写任意私有文件。
     * 与 [imageFile] 挡路径注入同一口径：**白名单**，只允许时间戳这类字符。
     */
    private val SESSION_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    /** 自增 id 计数器。[loadMessages] 会把它抬到历史最大 id 之上，避免重启后 id 撞车。 */
    private val idCounter = AtomicLong(0)

    /** 见类注释：与界面生命周期无关的落盘作用域。 */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 见类注释：把"写文件 + 写索引 + 清孤儿"串成不可交错的临界区。 */
    private val ioMutex = Mutex()

    // ---- 路径 ---------------------------------------------------------------

    /** 图片目录：`filesDir/ai_chat/images`。 */
    private fun imageDir(context: Context): File =
        File(context.filesDir, "$DIR_NAME/$IMAGE_DIR_NAME")

    /** 会话目录：`filesDir/ai_chat/sessions`。 */
    private fun sessionDir(context: Context): File =
        File(context.filesDir, "$DIR_NAME/$SESSION_DIR_NAME")

    /** 会话索引文件：`filesDir/ai_chat/sessions.json`。 */
    private fun indexFile(context: Context): File =
        File(context.filesDir, "$DIR_NAME/$INDEX_FILE_NAME")

    /** 旧版单文件历史：`filesDir/ai_chat/messages.json`（迁移源）。 */
    private fun legacyHistoryFile(context: Context): File =
        File(context.filesDir, "$DIR_NAME/$LEGACY_HISTORY_FILE_NAME")

    /**
     * 某个会话的消息文件；id 形态不合法时返回 null（调用方据此静默放弃本次读写）。
     * 见 [SESSION_ID_PATTERN] 的路径注入说明。
     */
    private fun sessionFile(context: Context, sessionId: String): File? {
        if (!SESSION_ID_PATTERN.matches(sessionId)) return null
        return File(sessionDir(context), "$sessionId$SESSION_FILE_SUFFIX")
    }

    // ---- 会话 id / 标题 -----------------------------------------------------

    /**
     * 生成一个新的会话 id。
     *
     * 口径由需求钉死：`System.currentTimeMillis().toString()`。
     * 代价是"同一毫秒内创建两条会话会撞 id"——实际上做不到：会话创建只由用户的
     * 一次点击 / 一条 `/new` 触发，两次 UI 事件之间至少隔一帧（16ms）。
     * 刻意不在这里加随机后缀：id 同时是文件名与索引主键，越简单越不容易在
     * 调试时看花眼，而真实撞车概率为零。
     */
    fun newSessionId(): String = System.currentTimeMillis().toString()

    /**
     * 由消息列表推导会话标题：取**第一条有文字的用户消息**的前
     * [SESSION_TITLE_MAX_CHARS] 个字符，空白（含换行）压成单个空格。
     *
     * - 为什么压空白：标题在列表里是单行显示，原文里的换行会把"第一行"变成标题，
     *   而用户的第一行经常只有"你好"两个字，信息量远不如多带一点后续内容；
     * - 为什么是第一条**用户**消息：助手的第一条可能是很长的一段回答，
     *   拿用户的提问当标题才符合"这段对话在聊什么"；
     * - 一条消息都没有（或只有图片）时回落 [NEW_SESSION_TITLE]。
     */
    fun sessionTitle(messages: List<AiChatMessage>): String {
        val first = messages.firstOrNull { it.isUser && it.text.isNotBlank() }
            ?: return NEW_SESSION_TITLE
        val oneLine = first.text.replace(WHITESPACE, " ").trim()
        if (oneLine.isEmpty()) return NEW_SESSION_TITLE
        return oneLine.take(SESSION_TITLE_MAX_CHARS)
    }

    /**
     * 由消息列表推导列表项预览：**最后一条有文字的消息**压成单行并截断到
     * [PREVIEW_MAX_CHARS] 个字符（界面侧再做单行省略号，两处都截是为了
     * 索引文件本身也别被一条长回答撑大）。
     */
    fun sessionPreview(messages: List<AiChatMessage>): String {
        val last = messages.lastOrNull { it.text.isNotBlank() } ?: return ""
        return last.text.replace(WHITESPACE, " ").trim().take(PREVIEW_MAX_CHARS)
    }

    // ---- 读 -----------------------------------------------------------------

    /**
     * 读取会话索引，按 [AiChatSession.updatedAt] **降序**（最近更新的在最前）。
     *
     * ## 失败语义
     *
     * - 索引文件不存在 → 先尝试一次旧数据迁移（见类注释），迁移不出东西就是空列表
     *   （首次使用的正常状态）。
     * - 索引文件存在但解析失败 → **返回空列表，但写函数的闸门会拦住后续写盘**，
     *   绝不会用空列表覆盖掉读不出来的旧索引。
     *
     * ⚠️ 本函数可能触发迁移写盘，**必须在 IO 线程调用**。
     */
    fun loadSessions(context: Context): List<AiChatSession> {
        val file = indexFile(context)
        if (!file.isFile) return migrateLegacyHistory(context)
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val sessions = parseSessionsOrNull(text) ?: return emptyList()
        return sessions.sortedByDescending { it.updatedAt }
    }

    /**
     * 读取某个会话的全部消息，按 [AiChatMessage.timestamp] 升序返回。
     *
     * ## 失败语义
     *
     * - 文件不存在（新建的空会话）/ id 非法 → 空列表。
     * - 文件存在但解析失败 → **同样返回空列表，但 [saveSessionAsync] 的闸门会拦住
     *   后续写盘**（见类注释），绝不会用空列表覆盖掉读不出来的旧历史。
     *
     * 读取时顺带把 [idCounter] 抬到历史最大 id 之上：进程重启后内存计数器归零，
     * 若不抬升，新消息的 id 会与历史消息撞车，而 ViewModel 靠 id 定位占位消息。
     */
    fun loadMessages(context: Context, sessionId: String): List<AiChatMessage> {
        val file = sessionFile(context, sessionId) ?: return emptyList()
        if (!file.isFile) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val messages = parseMessagesOrNull(text) ?: return emptyList()
        // 读到的历史 id 可能大于本进程计数器：抬上去，新消息才不与历史撞车。
        bumpIdCounter(messages)
        return messages.sortedBy { it.timestamp }
    }

    // ---- 写 -----------------------------------------------------------------

    /**
     * 把一个会话**整体**落盘（不等待，提交到 [ioScope]）：写消息文件 → 写索引 → 清孤儿图片。
     *
     * `messages` 是"当前会话的完整列表"（整写口径，与本 store 的读接口对称）；
     * 超出 [MAX_MESSAGES] 时裁掉最旧的部分。
     *
     * `index` 是**写完这次会话后**应有的完整索引（含本次会话的标题 / 时间 / 预览）。
     * 为什么由调用方传而不是本函数自己维护：标题与预览是从消息推导出来的，
     * 而"当前会话还没产生任何消息时不该占用 20 条上限"这类策略属于会话编排，
     * 归 ViewModel；本函数只负责"按你给的索引落盘，并清掉不在索引里的会话文件"。
     * 索引超出 [MAX_SESSIONS] 时同样在这里裁到最新 20 条。
     *
     * 顺序不可交换：消息文件先落盘，索引才可能指向一个已存在的文件；
     * 反过来若索引先写成功而消息写失败，历史列表里就会出现一条点开是空的会话。
     *
     * @param session 本会话在索引里的项（标题 / 更新时间 / 预览）。
     * @param messages 本会话的全部消息（会被裁到 [MAX_MESSAGES] 条）。
     * @param index 落盘后应有的完整索引；**未出现在其中的会话文件会被删除**。
     */
    fun saveSessionAsync(
        context: Context,
        session: AiChatSession,
        messages: List<AiChatMessage>,
        index: List<AiChatSession>
    ) {
        val appContext = context.applicationContext
        val sorted = messages.sortedBy { it.timestamp }
        val trimmed =
            if (sorted.size > MAX_MESSAGES) sorted.takeLast(MAX_MESSAGES) else sorted
        val indexSnapshot = index
        ioScope.launch {
            ioMutex.withLock {
                runCatching {
                    val file = sessionFile(appContext, session.id) ?: return@runCatching
                    // 闸门：目标文件已存在但读不出来时拒绝写（见类注释"读失败的闸门口径"）。
                    // 拒绝时**连同索引一起放弃**：索引指向一个读不出来的文件没有意义。
                    if (!canOverwrite(file)) return@runCatching
                    writeAtomically(file, toJsonArray(trimmed).toString())
                    writeIndexLocked(appContext, indexSnapshot)
                    pruneOrphanImages(appContext)
                }
            }
        }
    }

    /**
     * 删除一条会话：删消息文件 + 按新索引落盘 + 清理因此变成孤儿的图片。
     *
     * 之所以还要传 [index]：删会话的同时索引也必须少一项，两件事在同一个临界区里
     * 做完才不会出现"文件没了但列表还在"。传新索引（而不是让本函数自己删一项）
     * 与 [saveSessionAsync] 保持同一口径：索引的最终形态由调用方决定。
     */
    fun deleteSessionAsync(context: Context, sessionId: String, index: List<AiChatSession>) {
        val appContext = context.applicationContext
        val indexSnapshot = index
        ioScope.launch {
            ioMutex.withLock {
                runCatching {
                    sessionFile(appContext, sessionId)?.let { runCatching { it.delete() } }
                    writeIndexLocked(appContext, indexSnapshot)
                    pruneOrphanImages(appContext)
                }
            }
        }
    }

    // ---- 图片 ---------------------------------------------------------------

    /**
     * 把用户选中的一张图解码、压缩、写进图片目录，返回**相对文件名**（供 [AiChatMessage.imageFile] 用）。
     *
     * 解码口径与 `AvatarStore.decodeForCrop` 完全一致，只维护一套踩过坑的逻辑：
     * - 用 `ImageDecoder`（API 28+，本项目 minSdk 29）**自动按 EXIF 方向摆正**，
     *   相机竖拍的照片不会躺倒，且不需要 `androidx.exifinterface`（禁止新增依赖）；
     * - `setTargetSize` 按 [IMAGE_MAX_PX] 降采样，不会把整张原图读进内存；
     *   用 API 28 的 `setTargetSize` 而不是 API 30 的 `setTargetSampleSize`，避免 minSdk 29 上的 NewApi 告警；
     * - 强制 `ALLOCATOR_SOFTWARE`：默认可能返回硬件位图，硬件位图不能 `compress` 进
     *   OutputStream（更不能进软件 Canvas），必须在解码阶段就要软件位图。
     *
     * ⚠️ 本函数做磁盘与解码的重活，**必须在 IO 线程调用**（调用方用
     * `withContext(Dispatchers.IO)` 包住），不要在主线程直接调。
     *
     * 文件名带一段自增序号（`img_<时间戳>_<序号>.jpg`）：只按毫秒命名时，
     * 同一毫秒内两次添加（快速连点）会撞名互相覆盖，加序号彻底消除这个概率。
     *
     * @return 失败（URI 失效 / 不是图片 / 磁盘满）返回 null，调用方据此提示而不是静默丢图。
     */
    fun addImage(context: Context, uri: Uri): String? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = sampleSizeFor(info.size.width, info.size.height, IMAGE_MAX_PX)
                // 目标尺寸至少 1px，避免极端比例下算出 0 而抛 IllegalArgumentException。
                decoder.setTargetSize(
                    (info.size.width / sample).coerceAtLeast(1),
                    (info.size.height / sample).coerceAtLeast(1)
                )
            }
        }.getOrNull() ?: return null

        val dir = imageDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null

        val file = File(dir, "img_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                // `compress` 自己会返回 false（例如编码器拒绝该配置），不能只看有没有抛异常。
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)

        if (!ok) {
            // 失败时清掉可能写了一半的残文件，避免留下无法解码的垃圾。
            runCatching { if (file.isFile) file.delete() }
            return null
        }
        // 位图不主动 recycle：与 AvatarCropScreen 同一取舍，交给 GC 更稳。
        return file.name
    }

    /**
     * 把一张**已存在的图片文件**（界面侧拷出的 cacheDir 临时件）压缩落盘进图片目录，
     * 返回**相对文件名**。与 [addImage] 唯一的区别是数据源：那边从 content:// Uri 解码，
     * 这边直接 `ImageDecoder.createSource(File)`——摆正 EXIF、降采样、软件位图、
     * 压缩质量等口径完全一致，维护一套逻辑。
     *
     * ⚠️ 必须在 IO 线程调用（与 [addImage] 同）。
     *
     * @return 失败（文件缺失 / 不是图片 / 磁盘满）返回 null，调用方降级为纯文字。
     */
    fun addImageFile(context: Context, source: File): String? {
        if (!source.isFile) return null
        val bitmap = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = sampleSizeFor(info.size.width, info.size.height, IMAGE_MAX_PX)
                decoder.setTargetSize(
                    (info.size.width / sample).coerceAtLeast(1),
                    (info.size.height / sample).coerceAtLeast(1)
                )
            }
        }.getOrNull() ?: return null

        val dir = imageDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null

        val file = File(dir, "img_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)
        if (!ok) {
            runCatching { if (file.isFile) file.delete() }
            return null
        }
        return file.name
    }

    /**
     * 由相对文件名取回图片文件；不存在 / 不是普通文件时返回 null。
     *
     * 顺手挡一道路径注入：相对名里不允许出现路径分隔符——它应该永远只是
     * [addImage] 生成的文件名。若哪天有人把用户可控字符串直接传进来，
     * `../` 之类可以越出图片目录读到任意私有文件，这里直接拒绝。
     */
    fun imageFile(context: Context, relative: String): File? {
        if (relative.isBlank()) return null
        if (relative.contains('/') || relative.contains('\\')) return null
        val file = File(imageDir(context), relative)
        return if (file.isFile) file else null
    }

    /**
     * 解码一张已保存的图片作为聊天气泡缩略图（**必须在 IO 线程调用**）。
     *
     * `BitmapFactory` 两遍法（与 `AvatarStore.decodeStored` 相同口径）：
     * 第一遍 `inJustDecodeBounds = true` 只读文件头拿尺寸（不分配像素内存），
     * 算出 `inSampleSize` 后第二遍才真正解码。文件缺失 / 损坏返回 null，界面回落占位图。
     */
    fun decodeThumb(context: Context, relative: String, maxPx: Int = THUMB_DECODE_MAX_PX): Bitmap? {
        val file = imageFile(context, relative) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        // outWidth/outHeight <= 0 说明不是能识别的图片（或文件被截断）。
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    // ---- 清理 ---------------------------------------------------------------

    /**
     * 清空全部聊天数据：删会话索引、会话目录、旧版单文件历史与整个图片目录。
     *
     * 与"逐条删除"不同，这是"清空重来"的一把梭：目录直接 `deleteRecursively`，
     * 不再逐个文件判断——目录都不存在了，引用检查没有意义。
     */
    fun clearAll(context: Context) {
        runCatching { indexFile(context).delete() }
        runCatching { sessionDir(context).deleteRecursively() }
        runCatching { legacyHistoryFile(context).delete() }
        runCatching { imageDir(context).deleteRecursively() }
    }

    // ---- id -----------------------------------------------------------------

    /** 全局自增 id。见 [idCounter] 与 [loadMessages] 的说明（重启后不与历史撞车）。 */
    fun nextId(): Long = idCounter.incrementAndGet()

    // ---- 内部实现：索引与消息的读写 -----------------------------------------

    /**
     * 把索引写盘（**调用方必须已持有 [ioMutex]**，见类注释）。
     *
     * 写完之后扫一遍会话目录：**不在索引里的 `.json` 文件即"已被删除或被上限挤出"的会话**，
     * 直接删掉。这条规则把"删会话"与"超上限删最旧"两条路径合并成同一个动作——
     * 调用方只要给出正确的索引，文件与索引就不可能不一致。
     *
     * `.tmp` 残件跳过：同临界区里不该存在，但真遇到（上次进程被杀）也不该把它当会话删掉
     * 之前先删掉别人可能正在用的临时件——留着由下一次同 id 写入覆盖即可。
     */
    private fun writeIndexLocked(context: Context, index: List<AiChatSession>) {
        val file = indexFile(context)
        // 闸门：索引文件已存在但读不出来时拒绝写（见类注释"读失败的闸门口径"）。
        if (!canOverwrite(file)) return

        val trimmed = index.sortedByDescending { it.updatedAt }.take(MAX_SESSIONS)
        val keptIds = trimmed.map { it.id }.toHashSet()
        writeAtomically(file, toSessionJsonArray(trimmed).toString())

        val dir = sessionDir(context)
        for (stale in dir.listFiles().orEmpty()) {
            if (!stale.isFile) continue
            if (stale.name.endsWith(TMP_SUFFIX)) continue
            if (!stale.name.endsWith(SESSION_FILE_SUFFIX)) continue
            val id = stale.name.removeSuffix(SESSION_FILE_SUFFIX)
            if (id !in keptIds) runCatching { stale.delete() }
        }
    }

    /**
     * 尝试把旧版单文件历史迁移成一条会话（见类注释）。
     *
     * 触发条件由 [loadSessions] 保证：**索引文件不存在**。因此本函数不需要额外的"迁移过没有"
     * 标记——索引一写下去，下次启动就不会再进来。
     *
     * @return 迁移后的索引（一条会话）；没有可迁移内容 / 迁移失败时返回空列表。
     */
    private fun migrateLegacyHistory(context: Context): List<AiChatSession> {
        val legacy = legacyHistoryFile(context)
        if (!legacy.isFile) return emptyList()
        // 旧文件读不出来：原样留着（用户数据不能删），当次按"没有历史"处理。
        val text = runCatching { legacy.readText() }.getOrNull() ?: return emptyList()
        val messages = parseMessagesOrNull(text) ?: return emptyList()
        if (messages.isEmpty()) return emptyList()

        val session = AiChatSession(
            id = newSessionId(),
            title = sessionTitle(messages),
            updatedAt = messages.maxOf { it.timestamp },
            preview = sessionPreview(messages)
        )
        val file = sessionFile(context, session.id) ?: return emptyList()
        return runCatching {
            writeAtomically(file, toJsonArray(messages).toString())
            writeAtomically(indexFile(context), toSessionJsonArray(listOf(session)).toString())
            // 迁移完把旧文件改名封存：不再被读取，也不删除（新结构万一出问题还能人工回捞）。
            legacy.renameTo(File(legacy.parentFile, legacy.name + ".migrated"))
            bumpIdCounter(messages)
            listOf(session)
        }.getOrDefault(emptyList())
    }

    /**
     * 解析索引文件；**解析不出来返回 null**（而不是空列表）。
     *
     * 这个区分是闸门的基础：`null` = "文件坏了"，调用方据此拒绝写；
     * `emptyList` = "索引里真的没有会话"，可以正常写。
     */
    private fun parseSessionsOrNull(text: String): List<AiChatSession>? = try {
        val array = JSONArray(text)
        val result = ArrayList<AiChatSession>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id")
            if (!SESSION_ID_PATTERN.matches(id)) continue
            result.add(
                AiChatSession(
                    id = id,
                    title = obj.optString("title").ifBlank { NEW_SESSION_TITLE },
                    updatedAt = obj.optLong("updatedAt"),
                    preview = obj.optString("preview")
                )
            )
        }
        result
    } catch (_: Exception) {
        null
    }

    /** 解析消息数组；解析不出来返回 null（含义同 [parseSessionsOrNull]）。 */
    private fun parseMessagesOrNull(text: String): List<AiChatMessage>? = try {
        val array = JSONArray(text)
        val result = ArrayList<AiChatMessage>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            result.add(
                AiChatMessage(
                    id = obj.optLong("id"),
                    isUser = obj.optBoolean("isUser"),
                    text = obj.optString("text"),
                    imageFile = obj.optString("imageFile").takeIf { it.isNotEmpty() },
                    timestamp = obj.optLong("timestamp")
                )
            )
        }
        result
    } catch (_: Exception) {
        null
    }

    /** 把 [idCounter] 抬到 [messages] 的最大 id 之上（见 [loadMessages] 的说明）。 */
    private fun bumpIdCounter(messages: List<AiChatMessage>) {
        val maxId = messages.maxOfOrNull { it.id } ?: return
        idCounter.accumulateAndGet(maxId) { current, candidate -> maxOf(current, candidate) }
    }

    // ---- 内部实现：写入与清理 -----------------------------------------------

    /**
     * 目标文件是否可以被覆盖写。
     *
     * 文件不存在 → 可以（首次写）。文件存在 → 必须能解析成 JSON 数组才允许写，
     * 否则说明"文件在，但读不出来"，照常写就是用新数据覆盖旧历史（见类注释的闸门口径）。
     */
    private fun canOverwrite(file: File): Boolean {
        if (!file.isFile) return true
        val text = runCatching { file.readText() }.getOrNull() ?: return false
        return runCatching { JSONArray(text); true }.getOrDefault(false)
    }

    /**
     * 近原子写：先写同目录临时文件，再 rename 覆盖。
     *
     * 进程在写一半时被杀，最坏留下一个 `.tmp` 残件，正式文件仍是上一次的完整内容
     * （个别文件系统不支持覆盖式 rename 时退化为直接写）。
     */
    private fun writeAtomically(target: File, text: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return
        val tmp = File(parent, target.name + TMP_SUFFIX)
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    /**
     * 删除图片目录里不被**任何会话**引用的孤儿文件（[ORPHAN_GRACE_MS] 内的新文件豁免）。
     *
     * 与旧版的区别：引用关系从"当前这一个列表"变成"扫一遍所有会话文件"。
     * 会话数上限 20 让这次扫描的成本仍是常数级（20 个小 JSON 文件）。
     *
     * ⚠️ **任何一个会话文件读不出来就整体放弃清理**：读不出来的那份里引用的图片
     * 会被误判成孤儿并删掉，而这些图片可能是用户唯一的一份。宁可多留几个垃圾文件
     * 到下次（文件修好或仍坏着）再清，也不承担这个数据损失——与"读失败不覆盖"同一口径。
     */
    private fun pruneOrphanImages(context: Context) {
        val dir = imageDir(context)
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return

        val referenced = HashSet<String>()
        for (stored in sessionDir(context).listFiles().orEmpty()) {
            if (!stored.isFile) continue
            if (!stored.name.endsWith(SESSION_FILE_SUFFIX)) continue
            val text = runCatching { stored.readText() }.getOrNull() ?: return
            val messages = parseMessagesOrNull(text) ?: return
            for (message in messages) {
                message.imageFile?.let { referenced.add(it) }
            }
        }

        val graceDeadline = System.currentTimeMillis() - ORPHAN_GRACE_MS
        for (file in files) {
            if (file.name in referenced) continue
            // 刚添加、还没随消息落盘的"草稿图"不删——见 ORPHAN_GRACE_MS 的说明。
            if (file.lastModified() > graceDeadline) continue
            runCatching { file.delete() }
        }
    }

    private fun toJsonArray(messages: List<AiChatMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("isUser", message.isUser)
                    .put("text", message.text)
                    .put("imageFile", message.imageFile ?: JSONObject.NULL)
                    .put("timestamp", message.timestamp)
            )
        }
        return array
    }

    /**
     * 会话索引 → JSON 数组。
     *
     * ⚠️ **刻意不叫 `toJsonArray` 做重载**：消息版本的重载擦除后都是
     * `toJsonArray(List) -> JSONArray`，参数类型不同不构成不同的 JVM 签名，
     * Kotlin 会以 platform declaration clash 编译失败。名字上直接区分开。
     */
    private fun toSessionJsonArray(sessions: List<AiChatSession>): JSONArray {
        val array = JSONArray()
        for (session in sessions) {
            array.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("updatedAt", session.updatedAt)
                    .put("preview", session.preview)
            )
        }
        return array
    }

    /**
     * 算出 `inSampleSize`：保证最长边降到 [maxPx] 附近，且只取 2 的幂
     * （解码器对 2 的幂有优化）。与 `AvatarStore.sampleSizeFor` 同一算法；
     * 那边是 private 无法复用，且跨包暴露一个纯工具函数也不值得，故本地实现一份。
     */
    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        if (maxPx <= 0) return 1
        var sample = 1
        val longest = maxOf(width, height)
        // 循环结束后最长边落在 [maxPx, 2*maxPx) 区间内。
        while (longest / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    /** 临时文件后缀（见 [writeAtomically]）。同时用于在扫目录时排除残件。 */
    private const val TMP_SUFFIX = ".tmp"

    /** 标题 / 预览共用的空白压缩正则：换行、连续空格、制表符都压成一个空格。 */
    private val WHITESPACE = Regex("\\s+")

    /** 索引里预览文本的截断长度。 */
    private const val PREVIEW_MAX_CHARS = 60
}
