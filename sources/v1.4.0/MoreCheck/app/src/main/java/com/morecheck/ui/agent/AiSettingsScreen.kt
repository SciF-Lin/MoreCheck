package com.morecheck.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.AiChatProviders
import com.morecheck.data.AiModelInfo
import com.morecheck.data.DEFAULT_AI_SYSTEM_PROMPT
import com.morecheck.data.DEFAULT_CONTEXT_LIMIT
import com.morecheck.data.MAX_CONTEXT_LIMIT
import com.morecheck.ui.AppViewModelProvider

/**
 * 「智能体 → 设置」页：选服务提供商、填 API Key 与模型名、写系统提示词、清空历史。
 *
 * ## 修改即时保存（没有"保存"按钮）
 *
 * 每一次 onChange 都直写 `viewModel.saveSettings(...)`（内部落 DataStore）。
 * 与本项目「权限管理」直写风格一致：
 * - DataStore 的 `edit` 对相同值是幂等的，频发的同值写入不会产生垃圾 IO 峰；
 * - 设置类页面没有"误触半截还得撤销"的问题——用户改的就是要留的，
 *   立即生效反而符合"调一下、切回去看看效果"的使用节奏；
 * - 省掉"忘记点保存"这一整类用户错误。
 *
 * 代价是"清空 API Key"也即时生效——因此清空历史走了二次确认，
 * 而 Key 的增删本身无损（重新粘上即可），不做确认框。
 *
 * ## 不发明契约外 API
 *
 * 本页只调 ViewModel 的 `settings` / `saveSettings` / `clearHistory`。
 * 提供商列表来自 `AiChatProviders.ALL`（data 包的静态事实），不经 ViewModel 转手。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: AiChatViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // 清空历史的二次确认弹窗。
    var confirmClear by remember { mutableStateOf(false) }

    // 「清空系统提示词」的二次确认开关（v1.1.69 需求 4）。
    // 与「清空对话记录」的 `confirmClear` 分开两个状态而不是复用一个布尔：
    // 两者是**不同的破坏性动作**（一个删对话、一个删提示词），
    // 共用一个状态会出现"确认框文案与实际要执行的动作对不上"的错配风险。
    var confirmClearPrompt by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能体设置") },
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
            // ---- 服务提供商 ------------------------------------------------
            Text("服务提供商", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column {
                    AiChatProviders.ALL.forEachIndexed { index, provider ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 整行可点：radio 只是状态显示，点击热区放大到整行，
                                // 免得用户精确瞄准那个小圆圈。
                                .clickable {
                                    if (settings.providerId != provider.id) {
                                        viewModel.saveSettings(
                                            settings.copy(providerId = provider.id)
                                        )
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.providerId == provider.id,
                                onClick = null // 点击由整行的 clickable 处理，radio 只做显示。
                            )
                            Spacer(Modifier.padding(start = 4.dp))
                            Text(
                                text = provider.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (settings.providerId == provider.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        // 组内分隔线：最后一条后面不画。
                        if (index < AiChatProviders.ALL.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 当前提供商的配置卡片 --------------------------------------
            //
            // 只显示当前选中提供商的配置：换提供商时卡片整体跟着切，
            // 各家的 Key / 模型 / 接口地址在 settings 的三个 Map 里按 id 各存一份，互不覆盖。
            val current = AiChatProviders.byId(settings.providerId)
            val isCustom = current.id == AiChatProviders.CUSTOM.id
            // 当前生效的模型名（自定义优先，回落默认）：思考深度那一行按它取等级列表。
            val effectiveModel = settings.modelFor(current)
            ProviderConfigCard(
                apiKey = settings.apiKeys[current.id].orEmpty(),
                onApiKeyChange = { value ->
                    viewModel.saveSettings(
                        settings.copy(apiKeys = settings.apiKeys + (current.id to value))
                    )
                },
                model = settings.models[current.id].orEmpty(),
                onModelChange = { value ->
                    viewModel.saveSettings(
                        settings.copy(models = settings.models + (current.id to value))
                    )
                },
                // 「自定义」提供商需要覆盖接口地址；内置三家该输入框隐藏（有静态地址，
                // 且 KDoc 里写了 URL，需要改的用户极少——真要改也走「自定义」）。
                baseUrl = if (isCustom) settings.baseUrls[current.id].orEmpty() else null,
                onBaseUrlChange = { value ->
                    viewModel.saveSettings(
                        settings.copy(baseUrls = settings.baseUrls + (current.id to value))
                    )
                },
                defaultModel = current.defaultModel,
                helperText = current.helperText,
                staticBaseUrl = current.baseUrl,
                // 思考深度：等级列表来自「拉取模型时」拿到的 reasoning_efforts。
                // 当前模型没有等级（没拉过 / 服务商不提供）时，卡片里那一整行不显示。
                effectiveModel = effectiveModel,
                reasoningEfforts = settings.reasoningEffortsFor(effectiveModel),
                selectedReasoningEffort = settings.reasoningEffortFor(effectiveModel),
                onReasoningEffortChange = { effort ->
                    viewModel.saveSettings(
                        settings.copy(
                            reasoningEffort = settings.reasoningEffort + (effectiveModel to effort)
                        )
                    )
                },
                // 填好 Key（自定义还要求 baseUrl）才能拉模型列表；缺失项提示由卡片内部展示。
                canFetchModels = settings.apiKeyFor(current) != null &&
                    settings.baseUrlFor(current).isNotBlank(),
                missingForFetch = when {
                    settings.apiKeyFor(current) == null -> "API Key"
                    settings.baseUrlFor(current).isBlank() -> "接口地址（Base URL）"
                    else -> null
                },
                onFetchModels = viewModel::fetchModels,
                onMessage = onMessage
            )
            Spacer(Modifier.height(12.dp))

            // ---- 上下文条数 --------------------------------------------------
            //
            // 每次请求最多回放多少条历史消息（最近 N 条）。它与 /compact 配合：
            // /compact 压缩的是**已存的历史**，这里限制的是**每次发多少**，
            // 两者独立生效（压过的历史更短，同样的 N 能覆盖更长的时间跨度）。
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "上下文",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    ContextLimitField(
                        limit = settings.contextLimit,
                        onLimitChange = { value ->
                            viewModel.saveSettings(settings.copy(contextLimit = value))
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 系统提示词 --------------------------------------------------
            //
            // 三个动作把"提示词从哪来"这件事对用户透明化：
            // · 留空 = 使用内置默认（`DEFAULT_AI_SYSTEM_PROMPT`，请求层回落）；
            // · 「填入默认」= 把默认文本填进编辑框，作为修改底稿；
            // · 「清空」= 一键清空（v1.1.69 需求 4）——见下方按钮处的说明。
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "系统提示词",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.saveSettings(settings.copy(systemPrompt = DEFAULT_AI_SYSTEM_PROMPT)) }) {
                            Text("填入默认")
                        }
                        // 「清空」（v1.1.69 需求 4：系统提示词支持一键清空）。
                        //
                        // ## 为什么"清空"就是"恢复内置默认"，而不是"什么都不发"
                        //
                        // 请求层对空提示词的处理是**回落到内置默认**（见 `effectiveSystemPrompt` 的
                        // KDoc：空 system 消息没有意义，"不发"与"发默认"之间永远选后者）。
                        // 所以这一键的真实语义是「丢掉我的自定义、回到出厂的提示词」——
                        // 按钮文案就叫「清空」而不是「恢复默认」，因为用户的心智是"把我写的删掉"；
                        // 但紧邻的说明文字必须讲清它删完之后**不是没有提示词，而是用内置的**，
                        // 否则用户会以为"清空 = 让 AI 裸奔"而不敢点（或反之后悔）。
                        //
                        // ## 为什么清空也要二次确认
                        //
                        // 用户可能写了很长的自定义提示词，误触一键清空是不可逆的（DataStore 无历史）。
                        // 这与「清空对话记录」同属"删掉用户输入"的破坏性动作，因此同样走确认框——
                        // 只是它不算重灾（数据量小、容易重写），所以用更轻的 `AlertDialog` 而非
                        // 倒计时确认。
                        TextButton(
                            onClick = { confirmClearPrompt = true },
                            enabled = settings.systemPrompt.isNotBlank()
                        ) {
                            Text(
                                text = "清空",
                                color = if (settings.systemPrompt.isNotBlank()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 2026-10-24 需求：系统提示词编辑框改为**固定高度**。
                    //
                    // 原实现 `minLines = 3, maxLines = 8`：高度会随内容行数变化，
                    // 打字时整个卡片跟着长高/缩回，下面的说明文字与页面滚动位置一起跳动。
                    // 现在固定 148dp（约 6 行 body + 内边距），内容超出时**框内滚动**，
                    // 布局不再随输入变化。用 `height` 而不是 `heightIn`：要的就是"钉死"。
                    OutlinedTextField(
                        value = settings.systemPrompt,
                        onValueChange = { value ->
                            viewModel.saveSettings(settings.copy(systemPrompt = value))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(148.dp),
                        placeholder = { Text("留空使用内置默认提示词；也可自定义") },
                        supportingText = { Text("发送时作为 system 消息附加，塑造回答的语言、格式与诚实度") },
                        shape = MaterialTheme.shapes.medium
                    )
                    // 未自定义时把默认提示词展示出来（只读），让"留空时实际生效的是什么"可见。
                    if (settings.systemPrompt.isBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "当前生效（内置默认）：\n$DEFAULT_AI_SYSTEM_PROMPT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 「清空系统提示词」二次确认（见上方按钮处的说明）。
            if (confirmClearPrompt) {
                AlertDialog(
                    onDismissRequest = { confirmClearPrompt = false },
                    title = { Text("清空系统提示词？") },
                    text = {
                        Text("将删除你自定义的提示词，之后使用应用内置的默认提示词。此操作无法撤销。")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmClearPrompt = false
                                viewModel.saveSettings(settings.copy(systemPrompt = ""))
                                onMessage("已清空，改用内置默认提示词")
                            }
                        ) { Text("清空") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmClearPrompt = false }) { Text("取消") }
                    }
                )
            }

            // ---- 数据：清空对话记录 ------------------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "数据",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    // 2026-10-24 需求：清空按钮改为**有底色**。
                    //
                    // 原实现是纯文字按钮（红字、无容器），在卡片里存在感很弱，
                    // 用户容易"找不到清空在哪"。改为 errorContainer 底的实心按钮：
                    // 既有颜色分区（错误色系 = 破坏性操作），又保持"它是可点的按钮"
                    // 这一层视觉契约。确认逻辑不变，仍走下方 AlertDialog。
                    Button(
                        onClick = { confirmClear = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("清空对话记录")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ---- 隐私说明 ----------------------------------------------------
            Text(
                text = "API Key 仅保存在本机 DataStore，请求直接发往所选服务商，" +
                    "不经过任何第三方服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 清空历史：二次确认 ------------------------------------------------
    //
    // 这是本页唯一不可逆操作（与 BackupScreen 恢复备份的确认口径一致），必须兜一层。
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空对话记录？") },
            text = { Text("全部聊天内容将被删除，且无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearHistory()
                        onMessage("已清空")
                    }
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 当前选中提供商的配置卡片：API Key（密码态 + 眼睛切换）、接口地址（仅自定义）、
 * 模型名（支持「获取模型列表」自动拉取）。
 *
 * ## 为什么两个输入框都"直写"而不做本地草稿
 *
 * 若先存在本地 `remember` 再等某个时机落盘，就要回答"切提供商 / 离开页面时
 * 没写盘的草稿算谁的"——答案是又一个同步 bug 温床。既然 DataStore 写入幂等，
 * onChange 直写是最短且无歧义的路径。
 *
 * ## Key 的存取口径
 *
 * 读：`settings.apiKeys[id].orEmpty()`——Map 里没有这一项就给空串，不能返回 null
 * （OutlinedTextField 的 value 不接受 null）；写：`apiKeys + (id to v)` 复制旧 Map
 * 再覆盖本提供商的项，其他提供商的 Key 不受影响。
 *
 * ## 密码态的取舍
 *
 * Key 一旦贴错，明文模式是唯一能核对的方式，所以提供眼睛切换；
 * 但默认仍为密码态——聊天页在旁边， shoulder-surfing 的风险大于"贴完想再看一眼"。
 *
 * ## 「获取模型列表」的交互设计
 *
 * - 前置条件：Key 已填（自定义还要求 baseUrl）。缺失时按钮禁用并在旁边标出缺什么，
 *   让用户不用点了才知道为什么不行；
 * - 点击后按钮变 loading（转圈），成功弹**选择弹窗**：点一个模型即写入模型名并关窗——
 *   直接写入比"弹窗 + 再点确定"少一步，且拉一次列表的意图就是"选一个"；
 * - 弹窗内保留手填入口（顶部搜索框兼作自定义输入）：列表可能很长（中转站常挂几十个
 *   模型），搜索过滤是刚需；服务商标了新模型而列表没刷出来时也能直接敲。
 * - 失败走 [onMessage]（Snackbar），不打断页面状态。
 *
 * ## 「思考深度」一行
 *
 * 等级列表不是写死的，而是**拉取模型列表时**从服务端拿到的（见
 * `AiChatRepository.parseReasoningEfforts` 对两种响应形态的兼容）。因此这一行
 * 只在"当前模型有可用等级"时出现——没拉过模型或服务商不提供时整行不显示，
 * 而不是画一排点不动的空胶囊。写死的"低/中/高"兜底被刻意放弃：服务端的等级 id
 * 未必是这三个词（可能是 `minimal` / `xhigh` 之类），猜错就是必然 400。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderConfigCard(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    /** 仅「自定义」提供商非空：显示接口地址输入框。 */
    baseUrl: String?,
    onBaseUrlChange: (String) -> Unit,
    defaultModel: String,
    helperText: String,
    /** 内置三家的静态接口地址（展示用）；自定义提供商传空串。 */
    staticBaseUrl: String,
    /** 当前生效的模型名（自定义优先，回落默认）：思考深度这一行按它取等级。 */
    effectiveModel: String,
    /** 当前模型可选的思考深度等级；空 = 不显示这一行。 */
    reasoningEfforts: List<String>,
    /** 当前模型已选中的等级；null = 未设（请求不带 reasoning_effort）。 */
    selectedReasoningEffort: String?,
    onReasoningEffortChange: (String) -> Unit,
    canFetchModels: Boolean,
    /** 拉取前置条件缺失项的名称（如 "API Key"）；null = 条件齐了。 */
    missingForFetch: String?,
    onFetchModels: (onResult: (List<AiModelInfo>) -> Unit, onError: (String) -> Unit) -> Unit,
    onMessage: (String) -> Unit
) {
    // 明文开关只在本次进入页面内有效，不必跨进程记住。
    var showKey by rememberSaveable { mutableStateOf(false) }

    // ---- 获取模型列表的状态机：idle → loading →（成功弹窗 / 失败 Snackbar）----
    var fetching by remember { mutableStateOf(false) }
    var modelPickerModels by remember { mutableStateOf<List<AiModelInfo>?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            imageVector = if (showKey) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key"
                        )
                    }
                },
                placeholder = { Text("粘贴服务商控制台里的密钥") },
                shape = MaterialTheme.shapes.medium
            )
            // ---- 接口地址：只有「自定义」提供商显示（静态地址的展示在下方灰字里）----
            if (baseUrl != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("接口地址（Base URL）") },
                    singleLine = true,
                    supportingText = { Text("OpenAI 兼容根地址，例如 https://api.openai.com/v1") },
                    placeholder = { Text("https://…") },
                    shape = MaterialTheme.shapes.medium
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型名") },
                singleLine = true,
                supportingText = {
                    // 有默认模型的提供商提示回落值；自定义没有默认，提示获取方式。
                    Text(
                        if (defaultModel.isBlank()) {
                            "可手填，或点下方「获取模型列表」选择"
                        } else {
                            "留空使用默认模型：$defaultModel"
                        }
                    )
                },
                placeholder = { Text(defaultModel.ifBlank { "例如 gpt-4o-mini" }) },
                shape = MaterialTheme.shapes.medium
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        if (fetching) return@TextButton
                        fetching = true
                        onFetchModels(
                            { models ->
                                fetching = false
                                if (models.isEmpty()) {
                                    onMessage("获取成功，但服务商未返回任何模型")
                                } else {
                                    modelPickerModels = models
                                }
                            },
                            { error ->
                                fetching = false
                                onMessage(error)
                            }
                        )
                    },
                    enabled = canFetchModels && !fetching
                ) {
                    if (fetching) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.padding(start = 8.dp))
                    }
                    Text(if (fetching) "获取中…" else "获取模型列表")
                }
                // 前置条件没齐时的就地提示（比点完报错的反馈路径短）。
                if (missingForFetch != null) {
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(
                        text = "请先填写$missingForFetch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = staticBaseUrl.ifBlank { "接口地址未填写" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (helperText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = helperText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ---- 思考深度（只在当前模型确实有可选等级时出现）----
            if (reasoningEfforts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "思考深度",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(6.dp))
                // 横向滚动的胶囊行：等级个数由服务端决定（可能只有两个，也可能七八个），
                // 横排一行 + 可滚动比换行堆叠更省纵向空间，也不会把卡片撑高。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 「默认」胶囊 = 不设（请求不带 reasoning_effort，由服务端用自己的默认值）。
                    // 它是**显式的一个选项**而不是"没选就没选"：用户需要一个明确的
                    // "改回服务端默认"的出口，否则选了 low 就再也回不去。
                    FilterChip(
                        selected = selectedReasoningEffort == null,
                        onClick = {
                            // 已经未设时再点 = no-op（避免每次点击都写一次 DataStore）。
                            if (selectedReasoningEffort != null) {
                                onReasoningEffortChange("")
                            }
                        },
                        label = { Text("默认") }
                    )
                    reasoningEfforts.forEach { effort ->
                        FilterChip(
                            selected = effort == selectedReasoningEffort,
                            onClick = {
                                if (effort != selectedReasoningEffort) {
                                    onReasoningEffortChange(effort)
                                }
                            },
                            label = { Text(reasoningEffortLabel(effort)) }
                        )
                    }
                }
                Text(
                    text = "当前模型：$effectiveModel（等级由服务商提供，切换模型后可能不同）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ---- 模型选择弹窗（获取成功后弹出）------------------------------------
    //
    // 顶部搜索框 + 模型列表。选中即写入模型名并关窗；「手动输入」按钮兜底
    // 列表里没有的目标模型。
    modelPickerModels?.let { models ->
        var query by remember { mutableStateOf("") }
        val filtered = remember(models, query) {
            if (query.isBlank()) models
            else models.filter { it.id.contains(query.trim(), ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { modelPickerModels = null },
            title = { Text("选择模型（${models.size}）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("搜索或输入模型名") },
                        shape = MaterialTheme.shapes.medium
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(320.dp)) {
                        if (query.isNotBlank() && filtered.isEmpty()) {
                            item {
                                TextButton(
                                    onClick = {
                                        onModelChange(query.trim())
                                        modelPickerModels = null
                                    }
                                ) { Text("使用「${query.trim()}」") }
                            }
                        }
                        items(filtered, key = { it.id }) { info ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModelChange(info.id)
                                        modelPickerModels = null
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = info.id == effectiveModel,
                                    onClick = null
                                )
                                Spacer(Modifier.padding(start = 4.dp))
                                Text(
                                    text = info.id,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                // 支持思考深度的模型就地标出来：用户选模型时就能看到
                                // "这个可以调思考深度"，不必选完回卡片里才发现。
                                if (info.reasoningEfforts.isNotEmpty()) {
                                    Spacer(Modifier.padding(start = 6.dp))
                                    Text(
                                        text = "· 支持思考深度",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelPickerModels = null }) { Text("关闭") }
            }
        )
    }
}

/**
 * 「上下文条数」输入框：每次请求最多回放多少条历史消息（最近 N 条）。
 *
 * ## 为什么用"本地草稿 + 失焦/非法时纠正"而不是每次按键都直接存
 *
 * 数字输入框有个绕不开的中间态：用户想输 `20`，敲完 `2` 的那一瞬间值是 2 而不是 20，
 * 直接落盘就会把 20 悄悄改成 2（而且用户看不出来，因为框里最终显示 20）。
 * 因此这里维持一份本地草稿字符串，**只有解析成合法数字时才写设置**；
 * 输空 / 非数字 / 越界时在 supportingText 里说清楚，并保留草稿让用户继续改
 * （离开页面时草稿丢弃，重新进来显示真实生效值——不会把非法值留在存储里）。
 *
 * 上下界与 [DEFAULT_CONTEXT_LIMIT] / [MAX_CONTEXT_LIMIT] 同一套口径，
 * 越界只在提示里说、不做静默截断：静默把 200 改成 100 会让用户以为设置没生效。
 */
@Composable
private fun ContextLimitField(
    limit: Int,
    onLimitChange: (Int) -> Unit
) {
    var draft by rememberSaveable { mutableStateOf(limit.toString()) }
    // 外部改了值（例如别处恢复默认）时跟上，但用户正在敲的合法草稿不被覆盖。
    LaunchedEffect(limit) {
        if (draft.toIntOrNull() != limit) draft = limit.toString()
    }
    val parsed = draft.trim().toIntOrNull()
    val valid = parsed != null && parsed in 1..MAX_CONTEXT_LIMIT

    OutlinedTextField(
        value = draft,
        onValueChange = { value ->
            // 只收数字（含空串，让用户能删干净重输）；其它字符直接吞掉，
            // 而不是收下来再报错——数字框里出现字母没有任何有用的解释。
            if (value.all { it.isDigit() }) {
                draft = value
                val parsedNow = value.trim().toIntOrNull()
                if (parsedNow != null && parsedNow in 1..MAX_CONTEXT_LIMIT) {
                    onLimitChange(parsedNow)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("上下文条数") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !valid,
        supportingText = {
            Text(
                if (valid) {
                    "每次请求最多带上最近这么多条历史消息（1~$MAX_CONTEXT_LIMIT，默认 $DEFAULT_CONTEXT_LIMIT）"
                } else {
                    "请输入 1~$MAX_CONTEXT_LIMIT 之间的整数"
                }
            )
        },
        shape = MaterialTheme.shapes.medium
    )
}

/**
 * 思考深度等级 id → 显示名。
 *
 * 只翻译业界最通行的三档（`low` / `medium` / `high`，见 OpenAI 的 reasoning_effort
 * 取值），**其余一律原样显示**：等级是服务端定义的字符串，本地编不出对应的中文时
 * 硬译（比如把 `xhigh` 译成"超高"）反而会让用户拿到的值与文档对不上。
 * 这就是需求里"低/中/高或服务端给的 id"两种显示形态的落点。
 */
private fun reasoningEffortLabel(effort: String): String = when (effort.lowercase()) {
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    else -> effort
}
