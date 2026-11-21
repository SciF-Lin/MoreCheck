package com.morecheck.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DataStore 委托。
 *
 * ⚠️ 这里**必须**是本文件独有的名字（`ai_chat_settings`），不能复用 `settings` 或
 * `notebook` 那一个：`preferencesDataStore` 委托每声明一次就产生一个独立的 DataStore
 * 实例，而 DataStore 对**同一个文件**有全进程唯一性校验，第二个实例一建连接就会抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 * 这是 `NotebookStore` 已经注释过的硬约束，此处不再展开。
 */
private val Context.aiChatSettingsDataStore by
    preferencesDataStore(name = "ai_chat_settings")

/**
 * 一个 AI 服务提供方（OpenAI 兼容 Chat Completions 端点）的**静态描述**。
 *
 * 只放"不会变"的事实（域名、默认模型、给用户看的说明文字）；会变的
 * （用户填的 Key、用户改的模型）在 [AiChatSettings] 里按 [AiProvider.id] 存取。
 *
 * @param id 稳定短名，同时是设置存储键 `ai_key_<id>` / `ai_model_<id>` 的一部分。
 *   一旦有用户存了设置就**不能再改**，否则旧设置全部失联。
 * @param label 界面显示名。
 * @param baseUrl 端点根。请求 URL 统一拼成 `baseUrl.trimEnd('/') + "/chat/completions"`，
 *   所以带不带 `/v1`、`/compatible-mode/v1` 都行，见 `AiChatProviders` 各条注释。
 * @param defaultModel 用户没自定义模型时的回落值。
 * @param helperText 设置界面里展示给用户的一行说明（端点可靠性、可替代写法等）。
 */
data class AiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val helperText: String
)

/**
 * 内置的三个 OpenAI 兼容提供方。
 *
 * 端点核实状态（截至编写时）：
 * - deepseek / qwen：已从官方文档核实为 OpenAI 兼容端点；
 * - mimo：域名 DNS 可解析，但**未能在线核实**端点与模型名，
 *   [AiProvider.helperText] 里已明确提示用户以小米开放平台文档为准。
 */
object AiChatProviders {

    /** DeepSeek 官方 OpenAI 兼容端点（官方文档：`https://api.deepseek.com/chat/completions`）。 */
    val DEEPSEEK = AiProvider(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        helperText = "官方 OpenAI 兼容端点，路径 /chat/completions；在 DeepSeek 开放平台创建 API Key。"
    )

    /**
     * 阿里云百炼（DashScope）OpenAI 兼容模式。
     * 有独立业务空间的用户可把 baseUrl 换成
     * `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1`（见 helperText）。
     */
    val QWEN = AiProvider(
        id = "qwen",
        label = "通义千问（百炼）",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-plus",
        helperText = "阿里云百炼 OpenAI 兼容模式；有独立业务空间的用户可把 baseUrl 换成 " +
            "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1。"
    )

    /**
     * 小米 MiMo 开放平台。
     *
     * ⚠️ 域名 DNS 可解析，但端点与模型名**未能在线核实**，
     * helperText 已按此口径编写——用户文档为准、两者均可修改。
     */
    val MIMO = AiProvider(
        id = "mimo",
        label = "小米 MiMo",
        baseUrl = "https://api.xiaomimimo.com/v1",
        defaultModel = "mimo-vl",
        helperText = "端点与模型名以小米开放平台文档为准，均可修改。"
    )

    /**
     * 自定义（OpenAI 兼容）：任何暴露 `/chat/completions`（以及 `/models`）的服务。
     *
     * ## 与内置三家的本质区别
     *
     * [AiProvider.baseUrl] 与 [AiProvider.defaultModel] 都是**空串**——它们不是
     * "留空用默认值"，而是**必填项**：baseUrl 没有合理默认（每个中转站/私有部署都不同），
     * 模型名也没有（同一网关后头挂什么模型无从猜起）。校验放在发送与取模型列表两处
     * （`streamChat` / [AiChatRepository.listModels] 的 baseUrl 判空，与 ViewModel
     * 发送前的模型名判空），错误信息直接告诉用户去设置页补。
     *
     * 常见用法：OpenAI 官方（`https://api.openai.com/v1`）、各类 OpenAI 兼容中转站、
     * Ollama / vLLM 本地部署（`http://<局域网IP>:11434/v1`）等。
     */
    val CUSTOM = AiProvider(
        id = "custom",
        label = "自定义（OpenAI 兼容）",
        baseUrl = "",
        defaultModel = "",
        helperText = "填 OpenAI 兼容的接口地址（如 https://api.openai.com/v1 或本地 Ollama 地址），" +
            "填好 Key 后可点「获取模型列表」自动拉取。"
    )

    val ALL = listOf(DEEPSEEK, QWEN, MIMO, CUSTOM)

    /**
     * 按 id 取提供方；无法识别的 id（历史脏数据 / 上游传 null）回落到 DeepSeek，
     * 保证调用方永远拿到一个可用的 [AiProvider]，不必处处判空。
     */
    fun byId(id: String?): AiProvider = ALL.firstOrNull { it.id == id } ?: DEEPSEEK
}

/**
 * `/models` 里一个模型的信息（拉取模型列表时一并拿到）。
 *
 * 为什么要专门一个类型而不是继续用 `List<String>`：需求要求"取模型列表时顺带把每个模型
 * 的推理等级存下来"，等级是**跟着模型走**的数据，用 `Map<String, List<String>>` 分散在
 * 调用方拼装容易两处不同步（模型列表刷新了、等级还是上一次的）。一个类型同时带 id 与
 * 等级，调用方一次遍历就能把两张表都写对。
 *
 * @param id 模型 id（请求体 `model` 字段用的就是它）。
 * @param reasoningEfforts 该模型可选的推理等级 id 列表；**空 = 该模型不支持推理等级**
 *   （服务端没给、给了空数组、或格式认不出来，三种情况都并到这个语义上——
 *   调用方只需要判空，不必区分"不支持"与"没提供"）。
 */
data class AiModelInfo(
    val id: String,
    val reasoningEfforts: List<String> = emptyList()
)

/**
 * AI 助手的用户设置快照。
 *
 * Key / 模型 / baseUrl 三者都按 providerId 分开存（Map），而不是每个提供方一个字段：
 * 新增提供方不需要动这个类，设置界面也只需要遍历 `AiChatProviders.ALL`。
 *
 * @param providerId 当前选中的提供方（`AiChatProvider.id`）。
 * @param apiKeys providerId → API Key。空 / 缺失 = 未填写（ViewModel 据此拦截发送）。
 * @param models providerId → 自定义模型名。空 / 缺失 = 用该提供方的 [AiProvider.defaultModel]。
 * @param baseUrls providerId → 接口地址覆盖。空 / 缺失 = 用该提供方的 [AiProvider.baseUrl]。
 *   内置三家一般不动它；「自定义」提供商这里**必填**（其静态 baseUrl 就是空串）。
 * @param systemPrompt 系统提示词；空串 = 使用内置默认提示词
 *   [DEFAULT_AI_SYSTEM_PROMPT]（见其 KDoc——"没填"与"空串"语义合流的取舍说明）。
 * @param reasoningEfforts **模型 id** → 该模型可选的推理等级 id 列表（拉取模型列表时写入）。
 *   键用模型 id 而不是 providerId：同一提供方下不同模型的等级可以完全不同
 *   （DeepSeek 的 reasoner 与 chat 就不是一套），按模型存才不会串味。
 *   缺失 / 空列表 = 该模型不支持推理等级（界面据此不显示「思考深度」这一行）。
 * @param modelLists **providerId** → 最近一次「获取模型列表」拉到的模型 id 列表
 *   （去重排序后的原样顺序）。存它是因为 `/models` 命令要"按序号列出所有可用模型"，
 *   而聊天页与设置页是**两个 ViewModel 实例**，只有落进 DataStore 才能互相看见。
 *   缺失 / 空列表 = 还没拉取过（`/models` 据此提示"请先在设置里获取模型列表"）。
 * @param reasoningEffort **模型 id** → 用户为该模型选中的推理等级 id。
 *   缺失 = 没设（请求体里就不带 `reasoning_effort`，由服务端用它自己的默认值）。
 * @param contextLimit 发给模型的**上下文消息条数上限**（最近 N 条），默认 [DEFAULT_CONTEXT_LIMIT]。
 *   由 `/context` 之外、需求指定的这个设置项控制；`/compact` 压缩后的历史也受它约束。
 */
data class AiChatSettings(
    val providerId: String = AiChatProviders.DEEPSEEK.id,
    val apiKeys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
    val baseUrls: Map<String, String> = emptyMap(),
    val systemPrompt: String = "",
    val reasoningEfforts: Map<String, List<String>> = emptyMap(),
    val modelLists: Map<String, List<String>> = emptyMap(),
    val reasoningEffort: Map<String, String> = emptyMap(),
    val contextLimit: Int = DEFAULT_CONTEXT_LIMIT
) {
    /** 当前提供方的 API Key；未填写（null / 空白）时返回 null。 */
    fun apiKeyFor(provider: AiProvider): String? =
        apiKeys[provider.id]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 当前提供方实际生效的模型名：用户自定义优先，否则回落 [AiProvider.defaultModel]。
     */
    fun modelFor(provider: AiProvider): String =
        models[provider.id]?.trim()?.takeIf { it.isNotEmpty() } ?: provider.defaultModel

    /**
     * 当前提供方实际生效的接口地址：用户覆盖优先，否则回落 [AiProvider.baseUrl]。
     * 结果可能为空串（「自定义」提供商没填 baseUrl 时）——调用方（请求层 / 设置页）
     * 据此判空并给出「去设置页补全」的提示。
     */
    fun baseUrlFor(provider: AiProvider): String =
        baseUrls[provider.id]?.trim()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl

    /**
     * 实际生效的系统提示词：用户没写就用内置默认（[DEFAULT_AI_SYSTEM_PROMPT]）。
     * 永不返回空串，请求层因此不需要兜底逻辑。
     */
    fun effectiveSystemPrompt(): String =
        systemPrompt.trim().ifEmpty { DEFAULT_AI_SYSTEM_PROMPT }

    /**
     * 某个模型可选的推理等级；不支持时返回空列表。
     *
     * 空模型名返回空列表（调用方不必先判空）：模型名本身缺失时，
     * 这里给出"没有等级可选"的答案比抛异常更符合调用方的需要。
     */
    fun reasoningEffortsFor(model: String): List<String> =
        reasoningEfforts[model.trim()].orEmpty()

    /**
     * 某个模型当前选中的推理等级；没设过 / 设的值已不在可选列表里时返回 null。
     *
     * 为什么要校验"还在列表里"：模型列表是服务端给的，可能因为服务端升级而改动，
     * 用户几个月前的选择未必还合法。此时当作"没设"处理，请求体里不带这个字段，
     * 让服务端用它自己的默认值——比拿一个已经失效的 id 去换一个 400 好得多。
     */
    fun reasoningEffortFor(model: String): String? {
        val id = reasoningEffort[model.trim()] ?: return null
        return id.takeIf { reasoningEffortsFor(model).contains(it) }
    }

    /** 生效的上下文条数上限：越界值夹到 1..[MAX_CONTEXT_LIMIT]（脏数据不至于发出超长请求）。 */
    fun effectiveContextLimit(): Int = contextLimit.coerceIn(1, MAX_CONTEXT_LIMIT)
}

/**
 * 上下文档数的默认上限（条）。
 *
 * 取 20：一轮"问答 + 若干次追问"的典型长度，token 花销与"还记得上一条"的体感之间
 * 比较平衡。这与 [MAX_CONTEXT_LIMIT] 一起构成设置项「上下文条数」的取值范围。
 */
const val DEFAULT_CONTEXT_LIMIT = 20

/**
 * 上下文档数的上限（条）。
 *
 * 用户可以在设置里把 [DEFAULT_CONTEXT_LIMIT] 调大（1~100）。封顶 100：
 * 500 条的单会话上限已经保证历史不会无限长，但"每次请求都回放 500 条"能让
 * 一次普通提问的 token 花销涨十倍以上——那是用户不会预期、也未必愿意付的账。
 * 封顶在 100 条（约几十 K token）是这个本地小助手合理的对话深度。
 */
const val MAX_CONTEXT_LIMIT = 100

/**
 * 内置的默认系统提示词：用户**没有**自定义系统提示词时生效。
 *
 * ## 设计目标：一个"较为完整通用的底座"，而不是某个具体人设
 *
 * 覆盖通用助手最常踩的四类问题，且对"是否真的具备某项能力"留了余地（不替模型
 * 编造能力）：
 * - **语言与风格**：默认中文回答；先给结论、再给解释；不堆空话；
 * - **格式**：默认克制排版（短段落 / 必要时列表），按对话场景的繁简自行判断；
 * - **诚实性**：不知道就说不知道、区分事实与推测、不编造链接与引用——这是
 *   通用助手里收益最高的一条；
 * - **图片输入**：应用支持发图，提示词里说明"用户可能发图，按图回答；看不清
 *   或与本问题无关就直说"，避免模型对着图片瞎编。
 *
 * ## 为什么"留空"直接等于"用默认"（而不是"不发 system 消息"）
 *
 * 早期版本空串 = 完全不发 system。实测这类"裸"对话的回答风格普遍散漫
 * （过长、少结构、易幻觉），而要求普通用户手写一份好提示词不现实——
 * "默认给一份 decent 的"比"默认没有"体验好得多。想彻底关掉的用户极少，
 * 且仍可填一个空格近似达成（trim 后仍会回落默认，这是刻意的：空 system
 * 消息没有意义，不发与发默认之间永远选后者）。
 *
 * 用户在设置页填了自己的提示词后，本常量即被完全替代（不是拼接——拼接会让
 * "我想完全自定义"变成不可能）。
 */
const val DEFAULT_AI_SYSTEM_PROMPT = "" +
        "你是一个通用 AI 助手，通过 API 接入一款本地打卡应用（MoreCheck）的对话功能。" +
        "回答遵循以下约定：\n" +
        "1. 语言：默认使用简体中文回答；用户用其他语言提问时，跟随用户的语言。\n" +
        "2. 风格：先给结论或答案，再给必要的解释；不写空洞的开场白和结尾客套。" +
        "内容较长时用短段落或列表组织，保持易读；闲聊时保持自然简短。\n" +
        "3. 诚实：不确定或不知道的内容要直接说明，不要编造事实、数据、链接或引用；" +
        "区分「确定的事实」与「你的推测」，推测要标注出来。\n" +
        "4. 图片：用户可能发送图片。有图时先描述你从图中看到的关键信息，再回答问题；" +
        "图片看不清、无法打开或与问题无关时，直接说明并请用户补充，不要凭空想象图片内容。\n" +
        "5. 数学与代码：涉及计算时给出关键步骤；涉及代码时给出可直接运行的完整片段并注明语言。\n" +
        "6. 安全：拒绝协助任何违法、有害或侵犯他人隐私的请求，并简要说明原因。"

/**
 * AI 助手设置的 DataStore 持久化。
 *
 * 键设计：`ai_provider`、`ai_system_prompt` 是标量；Key 与模型带 providerId 后缀
 * （`ai_key_deepseek` / `ai_model_qwen` ...），这样切换提供方时各自的 Key 互不覆盖，
 * 切回来 Key 还在——用户最讨厌"切个服务商就要重填一遍密码"。
 *
 * ## 三张"整体序列化成 JSON 字符串"的表
 *
 * `ai_reasoning_efforts`（模型 id → 等级列表）、`ai_reasoning_effort`（模型 id → 选中等级）
 * 与 `ai_model_lists`（providerId → 已拉取的模型 id 列表）都不从 providerId 拼动态键：
 * 模型 id 里可能带 `:` `/` 等字符，直接拼进 Preferences 键有歧义风险，而且
 * "换提供方"与"换模型"是两件正交的事。因此它们**整体序列化成一个 JSON 字符串**存取，
 * 读写处集中在本类的两个私有编解码函数里，格式坏掉时整体回落到空表
 * （等级只是可选的锦上添花，为它让设置页崩掉不值得）。
 *
 * Context 存成公有的 [appContext]（applicationContext）：仓库本身没有生命周期，
 * 持有 appContext 不构成泄漏；[AiChatRepository] 会把它转手给 ViewModel 落盘用。
 */
class AiChatSettingsStore(private val context: Context) {

    /** applicationContext（非 Activity Context，无泄漏风险）。 */
    val appContext: Context get() = context.applicationContext

    private object Keys {
        val PROVIDER = stringPreferencesKey("ai_provider")
        val SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")

        /** 模型 id → 可选推理等级列表（JSON 字符串，见类注释）。 */
        val REASONING_EFFORTS = stringPreferencesKey("ai_reasoning_efforts")

        /** providerId → 已拉取的模型 id 列表（JSON 字符串，见类注释）。 */
        val MODEL_LISTS = stringPreferencesKey("ai_model_lists")

        /** 模型 id → 选中的推理等级（JSON 字符串，见类注释）。 */
        val REASONING_EFFORT = stringPreferencesKey("ai_reasoning_effort")

        /** 上下文档数上限。 */
        val CONTEXT_LIMIT = intPreferencesKey("ai_context_limit")

        // 动态键（带 providerId 后缀）无法预声明成 val，只能现场拼。
        fun apiKey(providerId: String) = stringPreferencesKey("ai_key_$providerId")
        fun model(providerId: String) = stringPreferencesKey("ai_model_$providerId")
        fun baseUrl(providerId: String) = stringPreferencesKey("ai_base_url_$providerId")
    }

    /** 设置流：任何键变化都会发射新快照，界面与 ViewModel 据此响应。 */
    val settings: Flow<AiChatSettings> = context.aiChatSettingsDataStore.data.map { prefs ->
        AiChatSettings(
            providerId = prefs[Keys.PROVIDER] ?: AiChatProviders.DEEPSEEK.id,
            apiKeys = AiChatProviders.ALL.mapNotNull { provider ->
                prefs[Keys.apiKey(provider.id)]?.let { provider.id to it }
            }.toMap(),
            models = AiChatProviders.ALL.mapNotNull { provider ->
                prefs[Keys.model(provider.id)]?.let { provider.id to it }
            }.toMap(),
            baseUrls = AiChatProviders.ALL.mapNotNull { provider ->
                prefs[Keys.baseUrl(provider.id)]?.let { provider.id to it }
            }.toMap(),
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: "",
            reasoningEfforts = decodeEfforts(prefs[Keys.REASONING_EFFORTS]),
            modelLists = decodeEfforts(prefs[Keys.MODEL_LISTS]),
            reasoningEffort = decodeChosen(prefs[Keys.REASONING_EFFORT]),
            contextLimit = prefs[Keys.CONTEXT_LIMIT] ?: DEFAULT_CONTEXT_LIMIT
        )
    }

    /**
     * 整体写入一份设置快照。未填写（null / 空白）的 Key / 模型 / baseUrl 会移除对应键，
     * 让"没填"只有一种表示法（键不存在）。
     */
    suspend fun save(settings: AiChatSettings) {
        context.aiChatSettingsDataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = settings.providerId
            if (settings.systemPrompt.isBlank()) {
                prefs.remove(Keys.SYSTEM_PROMPT)
            } else {
                prefs[Keys.SYSTEM_PROMPT] = settings.systemPrompt
            }
            for (provider in AiChatProviders.ALL) {
                val key = settings.apiKeys[provider.id]?.trim().orEmpty()
                if (key.isEmpty()) prefs.remove(Keys.apiKey(provider.id))
                else prefs[Keys.apiKey(provider.id)] = key

                val model = settings.models[provider.id]?.trim().orEmpty()
                if (model.isEmpty()) prefs.remove(Keys.model(provider.id))
                else prefs[Keys.model(provider.id)] = model

                val baseUrl = settings.baseUrls[provider.id]?.trim().orEmpty()
                if (baseUrl.isEmpty()) prefs.remove(Keys.baseUrl(provider.id))
                else prefs[Keys.baseUrl(provider.id)] = baseUrl
            }

            // 两张按模型 id 的表：空表 → 移除键（与上面"没填只有一种表示法"同一口径）。
            val efforts = encodeEfforts(settings.reasoningEfforts)
            if (efforts == null) prefs.remove(Keys.REASONING_EFFORTS)
            else prefs[Keys.REASONING_EFFORTS] = efforts

            val modelLists = encodeEfforts(settings.modelLists)
            if (modelLists == null) prefs.remove(Keys.MODEL_LISTS)
            else prefs[Keys.MODEL_LISTS] = modelLists

            val chosen = encodeChosen(settings.reasoningEffort)
            if (chosen == null) prefs.remove(Keys.REASONING_EFFORT)
            else prefs[Keys.REASONING_EFFORT] = chosen

            // 越界值夹到合法区间再存：设置页输入框允许自由敲字，
            // 夹在这里让"内存里的快照"与"下一次读出来的快照"永远一致。
            prefs[Keys.CONTEXT_LIMIT] = settings.contextLimit.coerceIn(1, MAX_CONTEXT_LIMIT)
        }
    }

    // ---- 两张按模型 id 的表的序列化（见类注释：为什么是 JSON 字符串）---------

    /** 等级列表表 → JSON；空表返回 null（调用方移除键）。解析失败的一律丢弃。 */
    private fun encodeEfforts(map: Map<String, List<String>>): String? {
        val cleaned = map.filterValues { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null
        val root = JSONObject()
        for ((model, efforts) in cleaned) {
            root.put(model, JSONArray(efforts.distinct()))
        }
        return root.toString()
    }

    /** 选中等级表 → JSON；空表返回 null。 */
    private fun encodeChosen(map: Map<String, String>): String? {
        val cleaned = map.filterValues { it.isNotBlank() }
        if (cleaned.isEmpty()) return null
        val root = JSONObject()
        for ((model, effort) in cleaned) {
            root.put(model, effort)
        }
        return root.toString()
    }

    /** JSON → 等级列表表；缺失 / 格式坏掉返回空表。 */
    private fun decodeEfforts(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = HashMap<String, List<String>>()
        for (model in root.keys()) {
            val array = root.optJSONArray(model) ?: continue
            val efforts = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val effort = array.optString(i)
                if (effort.isNotBlank()) efforts.add(effort)
            }
            if (efforts.isNotEmpty()) result[model] = efforts
        }
        return result
    }

    /** JSON → 选中等级表；缺失 / 格式坏掉返回空表。 */
    private fun decodeChosen(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = HashMap<String, String>()
        for (model in root.keys()) {
            val effort = root.optString(model)
            if (effort.isNotBlank()) result[model] = effort
        }
        return result
    }
}

/**
 * 一条发给模型的对话轮次。
 *
 * @param isUser true = user 轮，false = assistant 轮（历史上下文要按角色回放给模型）。
 * @param text 文本内容。
 * @param imageBase64 已含 `data:image/jpeg;base64,` 前缀的 data URI；null = 纯文字。
 *   只允许"一图一文"，多图轮次由调用方拆成多条 [AiTurn]——本仓库不做多图拼装。
 */
data class AiTurn(
    val isUser: Boolean,
    val text: String,
    val imageBase64: String?
)

/** AI 请求失败。message 为面向用户展示的中文描述（含服务端原文）。 */
class AiChatException(message: String) : Exception(message)

/**
 * OpenAI 兼容 Chat Completions 的流式客户端。
 *
 * ## 依赖口径
 *
 * 只用 `java.net.HttpURLConnection` + `org.json` + `java.util.Base64`，
 * **零新依赖**。不用 OkHttp：本项目没有引入它的场景，为一个请求加一套依赖不划算；
 * HttpURLConnection 在流式读取（逐行读 SSE）上完全够用。
 *
 * ## SSE 解析口径
 *
 * `data: {...}` 逐行读，`[DONE]` 结束；取 `choices[0].delta.content` 逐段回调 [onDelta]。
 * - `delta.content` 为 null / 缺失时**跳过**该段（有些端点在起始 chunk 里只发 role）。
 * - **刻意忽略** `delta.reasoning_content`：DeepSeek-R1 类模型的思维链也走这个字段，
 *   把它当正文显示，用户会看到一堆推理过程混进回答里。
 * - 不是 `data: ` 开头的行（空行、`event:`、注释行）直接跳过。
 *
 * ## 线程与取消
 *
 * 网络段整体包在 `withContext(Dispatchers.IO)`；协程被取消时 `ensureActive()` 与
 * 底层流的阻塞读都会中断（`CancellationException` 一律向上抛，**绝不吞掉**），
 * 由 ViewModel 决定"停止生成"后的 UI 状态。
 *
 * ## 为什么构造函数里带着 [AiChatSettingsStore]
 *
 * [AiChatSettingsStore] 需要 Context，而 `AiChatViewModel` 的构造签名是
 * `(store, repository)` 两个依赖、没有 Context。把设置仓库挂在本类上
 * （构造参数 `settingsStore` 直接声明为公开 `val`，其 `appContext` 也随之可读），
 * ViewModel 就能从这里拿到设置流与读历史 / 落盘所需的 application context——
 * 这是接线方（工厂 / `AppContainer`）只需构造两个依赖的原因，
 * 见 `ui/agent/AiChatViewModel` 的说明。
 */
class AiChatRepository(val settingsStore: AiChatSettingsStore) {

    /** Application Context 的转手出口（来自 [settingsStore]，applicationContext 无泄漏风险）。 */
    val appContext: Context get() = settingsStore.appContext

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** SSE 数据行的固定前缀。 */
        private const val SSE_DATA_PREFIX = "data:"

        /** SSE 结束哨兵。 */
        private const val SSE_DONE = "[DONE]"

        /**
         * 模型项里"推理等级列表"可能用的字段名（按优先级）。
         * 见 [parseReasoningEfforts] 对"为什么放宽字段名"的说明。
         */
        private val REASONING_EFFORT_KEYS = listOf(
            "reasoning_efforts",
            "supported_reasoning_efforts",
            "reasoning_effort",
            "reasoning_levels"
        )

        /**
         * 等级元素（对象形态）里"等级 id"可能用的字段名（按优先级）。
         * 服务端给的常见形态是 `{"effort":"low","description":"..."}`。
         */
        private val REASONING_EFFORT_ID_KEYS = listOf("effort", "id", "name", "value")
    }

    /**
     * 发起流式对话。
     *
     * @param settings 从 [AiChatSettingsStore] 读出的设置快照（含当前 Key 与模型）。
     * @param turns 本轮要发送的完整上下文（含历史），按时间顺序。
     * @param onDelta 每收到一段正文就回调一次；**从 IO 协程里被调用**，
     *   实现方若碰 UI 状态请自行切线程（本项目的用法是只更新 StateFlow，天然线程安全）。
     * @return 拼接后的完整回答文本（与逐段回调的内容一致）。
     * @throws AiChatException 非 2xx / 网络失败（message 为中文，可直接展示）。
     * @throws kotlinx.coroutines.CancellationException 协程被取消时正常向上抛，不要捕获。
     */
    suspend fun streamChat(
        settings: AiChatSettings,
        turns: List<AiTurn>,
        onDelta: (String) -> Unit
    ): String {
        val provider = AiChatProviders.byId(settings.providerId)
        val apiKey = settings.apiKeyFor(provider)
            ?: throw AiChatException("请先在设置里填写 API Key")
        val model = settings.modelFor(provider)
        if (model.isBlank()) {
            // 只有「自定义」提供商可能走到这里（内置三家都有 defaultModel）。
            throw AiChatException("请先在设置里填写模型名（或点「获取模型列表」选择）")
        }
        val baseUrl = settings.baseUrlFor(provider)
        if (baseUrl.isBlank()) {
            // 只有「自定义」提供商可能走到这里（内置三家都有静态 baseUrl）。
            throw AiChatException("请先在设置里填写接口地址（Base URL）")
        }
        // IOException 单独转成中文提示；AiChatException 直接透传；
        // CancellationException 属于正常取消，绝不捕获。
        try {
            val fullText = StringBuilder()
            withContext(Dispatchers.IO) {
                val connection = openConnection(baseUrl, apiKey, model, settings, turns)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw buildHttpError(connection, code)
                    }

                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            // ensureActive：让取消能立刻中断，即使对端迟迟不发数据。
                            coroutineContext.ensureActive()
                            val line = reader.readLine() ?: break
                            if (!line.startsWith(SSE_DATA_PREFIX)) continue
                            val payload = line.removePrefix(SSE_DATA_PREFIX).trim()
                            if (payload == SSE_DONE) break
                            val delta = parseDelta(payload) ?: continue
                            fullText.append(delta)
                            onDelta(delta)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            return fullText.toString()
        } catch (e: IOException) {
            // 连接失败 / 流被重置等底层网络错误。
            // （AiChatException 不是 IOException，从上面直接穿透到这里之外，无需重抛。）
            throw AiChatException("网络连接失败，请检查网络")
        }
    }

    /**
     * 拉取该提供商可用的模型列表（OpenAI 兼容 `GET /models`）。
     *
     * ## 适用范围
     *
     * DeepSeek / 百炼 / OpenAI 官方 / 各中转站 / Ollama(v1 挂了 /v1/models) 基本都实现了
     * 这个端点；个别服务商（如部分旧版网关）没有时，会返回 404 —— 此时错误信息里
     * 会带上 HTTP 状态，用户改手填模型名即可，功能降级而非报错卡死。
     *
     * ## 解析口径
     *
     * 响应形如 `{"object":"list","data":[{"id":"deepseek-chat",...},...]}`——
     * `data[].id` 是模型名。解析不出来 / data 为空时返回空列表，调用方提示
     * "获取成功但列表为空"。**刻意不做任何过滤**（不按 "chat"/"vl" 关键词筛）：
     * 各家命名差异太大，过滤很容易把可用模型筛没了，宁多勿缺，选择交给用户。
     *
     * 每个模型项还可能带推理等级（`reasoning_efforts` 等字段），见 [parseReasoningEfforts]
     * 对两种服务端形态的兼容说明。
     *
     * @return 模型信息列表，按 id 排序（去重后）。id 与等级都在里面。
     * @throws AiChatException Key 未填 / baseUrl 未填 / 非 2xx / 网络失败（中文消息）。
     */
    suspend fun listModels(settings: AiChatSettings): List<AiModelInfo> {
        val provider = AiChatProviders.byId(settings.providerId)
        val apiKey = settings.apiKeyFor(provider)
            ?: throw AiChatException("请先填写 API Key 再获取模型列表")
        val baseUrl = settings.baseUrlFor(provider)
        if (baseUrl.isBlank()) {
            throw AiChatException("请先填写接口地址（Base URL）再获取模型列表")
        }
        return try {
            withContext(Dispatchers.IO) {
                val connection = openModelsConnection(baseUrl, apiKey)
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        throw buildHttpError(connection, code)
                    }
                    val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use {
                        it.readText()
                    }
                    parseModels(body)
                } finally {
                    connection.disconnect()
                }
            }
        } catch (e: IOException) {
            throw AiChatException("网络连接失败，请检查网络")
        }
    }

    /** GET `<baseUrl>/models`，带 Bearer 头；超时口径与对话请求一致。 */
    private fun openModelsConnection(baseUrl: String, apiKey: String): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/models")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    /**
     * 从 `/models` 响应体里取模型信息（id + 推理等级）。
     * 兼容两种形态：标准 `{"data":[{"id":...}]}` 与极简 `[ "id", ... ]`（个别网关）。
     * 任何解析失败都返回空列表（调用方提示"列表为空"），不让单次坏响应炸界面。
     */
    private fun parseModels(body: String): List<AiModelInfo> = try {
        val array = when {
            body.trimStart().startsWith("[") -> JSONArray(body)
            else -> JSONObject(body).optJSONArray("data") ?: JSONArray()
        }
        val byId = LinkedHashMap<String, AiModelInfo>()
        for (i in 0 until array.length()) {
            val item = array.get(i)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id")
                else -> null
            }?.trim()
            if (id.isNullOrEmpty()) continue
            // 同一个 id 出现两次时取"有等级的那一份"：极少见，但真遇到时
            // 丢掉等级就是白白少一个功能，而合并两份列表又可能混进不存在的等级。
            val efforts = if (item is JSONObject) parseReasoningEfforts(item) else emptyList()
            val existing = byId[id]
            if (existing == null || (existing.reasoningEfforts.isEmpty() && efforts.isNotEmpty())) {
                byId[id] = AiModelInfo(id = id, reasoningEfforts = efforts)
            }
        }
        byId.values.sortedBy { it.id }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 从一个模型项的 JSON 里取推理等级 id 列表；取不到就返回空列表 = 该模型不支持。
     *
     * ## 为什么要把字段名与元素形态都放宽（兼容优先）
     *
     * "推理等级"不是 OpenAI 协议的标准字段，各家的写法不一：DeepSeek / OpenAI 用
     * `reasoning_efforts`，也有网关写 `reasoning_efforts` / `reasoning_levels` /
     * `supported_reasoning_efforts`，元素可能是 `{"effort":"low"}` 这样的对象，
     * 也可能直接是字符串 `"low"`。这里对**字段名与元素形态两者都做兼容**，
     * 任一命中即可；全都不命中就当"该模型不支持推理等级"——需求明确了这个兜底口径。
     *
     * 元素里的 id 字段同样放宽：优先 `effort`，回落 `id` / `name` / `value`。
     * 空串与重复项一律丢掉（界面按这个列表画胶囊，重复项会画出两个一样的按钮）。
     */
    private fun parseReasoningEfforts(model: JSONObject): List<String> {
        val raw = REASONING_EFFORT_KEYS.firstNotNullOfOrNull { key ->
            model.optJSONArray(key)
        } ?: return emptyList()

        val efforts = ArrayList<String>(raw.length())
        for (i in 0 until raw.length()) {
            val effort = when (val item = raw.get(i)) {
                is String -> item
                is JSONObject -> REASONING_EFFORT_ID_KEYS.firstNotNullOfOrNull { key ->
                    item.nullSafeString(key)?.takeIf { it.isNotBlank() }
                }
                else -> null
            }?.trim()
            if (!effort.isNullOrEmpty()) efforts.add(effort)
        }
        return efforts.distinct()
    }

    private fun openConnection(
        baseUrl: String,
        apiKey: String,
        model: String,
        settings: AiChatSettings,
        turns: List<AiTurn>
    ): HttpURLConnection {
        val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.outputStream.use { out ->
            out.write(buildBody(model, settings, turns).toString().toByteArray(Charsets.UTF_8))
            out.flush()
        }
        return connection
    }

    /**
     * 拼请求体。system prompt 恒非空（[AiChatSettings.effectiveSystemPrompt] 空时回落
     * 内置默认 [DEFAULT_AI_SYSTEM_PROMPT]），所以 system 消息总是第一条；
     * 含图消息 content 用数组形态，纯文字用字符串形态
     * （OpenAI 兼容协议的两种合法形态，混着用没问题）。
     *
     * ## `reasoning_effort`
     *
     * 当前模型设了推理等级时才带上这个字段（[AiChatSettings.reasoningEffortFor] 已经
     * 剔除了"已失效的旧选择"）。服务商不支持这个字段时会直接回 400，错误经
     * [buildHttpError] 原样透传给用户——**刻意不做本地白名单拦截**：
     * 等级列表本身就是从服务端拉来的，本地再存一份"谁支持"的认知只会过期，
     * 让服务端当唯一裁判更准。
     */
    private fun buildBody(
        model: String,
        settings: AiChatSettings,
        turns: List<AiTurn>
    ): JSONObject {
        val messages = JSONArray()
        // 空白提示词回落默认常量（见 effectiveSystemPrompt 的取舍说明）：
        // "不发 system"这个选项被刻意移除——默认给一份 decent 的比裸奔强。
        messages.put(
            JSONObject().put("role", "system")
                .put("content", settings.effectiveSystemPrompt())
        )
        for (turn in turns) {
            val message = JSONObject().put(
                "role",
                if (turn.isUser) "user" else "assistant"
            )
            if (turn.imageBase64 != null) {
                // 含图消息：content 必须是数组形态（text + image_url）。
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", turn.text))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", turn.imageBase64))
                    )
                message.put("content", content)
            } else {
                message.put("content", turn.text)
            }
            messages.put(message)
        }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
        settings.reasoningEffortFor(model)?.let { body.put("reasoning_effort", it) }
        return body
    }

    /**
     * 从一行 SSE payload 里取出正文增量；无正文（只有 role / 只有思维链 / 值是 null）返回 null。
     *
     * ⚠️ **`optString` 对 JSON null 返回字符串 `"null"`**：这是 Android `org.json` 的
     * 既有行为（键存在但值为 `JSONObject.NULL` 时不走默认值分支，而是 `NULL.toString()`）。
     * OpenAI 兼容协议的流式首块经常是 `{"delta":{"role":"assistant","content":null}}`——
     * 直接用 `optString` 会拿到四个字符 `null` 并追加进正文，实机上表现为回答开头
     * 一大串 `nullnullnull…`（用户截图报过，v1.1.69 修复）。
     *
     * 所以这里必须**三重判定**：键存在、值不是 `JSONObject.NULL`、拼出来非空。
     * 同理 [buildHttpError] 解析服务端 message 时也要防这一手。
     */
    private fun parseDelta(payload: String): String? = try {
        val delta = JSONObject(payload)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("delta")
            ?: return null
        // 只取 content；reasoning_content 刻意忽略（思维链不进正文，见类注释）。
        delta.nullSafeString("content")?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        // 单行坏 JSON 不该炸掉整次对话：跳过即可。
        null
    }

    /**
     * 取一个字符串字段，把「键不存在」「值是 null」「不是字符串」三种情况统一成 null。
     * 专门绕开 `optString` 的 `null → "null"` 行为（见 [parseDelta] 的说明）。
     */
    private fun JSONObject.nullSafeString(key: String): String? {
        if (!has(key)) return null
        if (isNull(key)) return null
        return optString(key)
    }

    /** 把非 2xx 响应转成中文异常：401/403 给明确说法，其余透传服务端原文。 */
    private fun buildHttpError(connection: HttpURLConnection, code: Int): AiChatException {
        val bodyText = runCatching {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull().orEmpty()

        // 服务端通常回 {"error":{"message":"..."}}；解析不出来就把响应体原文带上，便于排查。
        val serverMessage = try {
            // 同样防 optString 的 null→"null" 陷阱（见 parseDelta 的说明）：
            // 服务端回 {"error":{"message":null}} 时不该把字面 "null" 当错误文案展示。
            JSONObject(bodyText).optJSONObject("error")?.nullSafeString("message")?.takeIf {
                it.isNotEmpty()
            }
        } catch (_: Exception) {
            null
        }

        val prefix = when (code) {
            401, 403 -> "API Key 无效或未授权"
            else -> "HTTP $code"
        }
        return AiChatException(
            if (serverMessage != null) "$prefix（$serverMessage）"
            else if (bodyText.isNotBlank()) "$prefix：$bodyText"
            else prefix
        )
    }
}
