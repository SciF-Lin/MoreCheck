package com.morecheck.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.morecheck.data.KnowledgeDoc
import com.morecheck.data.KnowledgeQaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 合并轮新增的知识库相关界面（2026-10-22）。
 *
 * 从原「知识问答」页整体搬过来，**改为挂在「智能体」页内部**：
 * 一个入口、两种模式，检索与引用不再是一个孤立功能，而是对话的一个开关。
 *
 * 为什么单独一个文件：`AgentChatScreen.kt` 已经 1500 行，这些面板彼此内聚
 * （都只依赖 [CitationRef] / [KnowledgeDoc] 与一个 [KnowledgeQaStore]），
 * 塞回去会让那个文件更难扫读。
 */

// ---- 顶部的模式切换条 -------------------------------------------------------

/**
 * 「知识库 / 自由对话」切换条。
 *
 * 用 FilterChip 而不是 Tab/SegmentedButton：
 * - 这不是两个页面，而是**同一个对话链路的两种回答口径**，Tab 会暗示"切页"；
 * - FilterChip 自带"选中即生效"的语义，且高度可控，压在标题栏下面一行不占地方。
 *
 * @param docCount 知识库文档数（0 时模式条右侧显示"去导入"，把空库这件事摆在眼前）。
 * @param busy 正在读写知识库（导入 / 重建索引）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AgentModeBar(
    knowledgeMode: Boolean,
    docCount: Int,
    busy: Boolean,
    onToggleMode: (Boolean) -> Unit,
    onOpenKnowledgeBase: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 2026-10-22 需求 3：**「自由对话」在左、「知识库」在右**。
            // 顺序与「默认模式」无关——默认仍是知识库（选中态由 `selected` 决定，
            // 不靠位置表达），这里只是把更常用的入口挪到离拇指更近的左侧。
            FilterChip(
                selected = !knowledgeMode,
                onClick = { onToggleMode(false) },
                label = { Text("自由对话") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = knowledgeMode,
                onClick = { onToggleMode(true) },
                label = { Text("知识库") },
                leadingIcon = if (knowledgeMode) {
                    {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                } else {
                    null
                }
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenKnowledgeBase) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (docCount > 0) "$docCount 篇文档" else "去导入",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ---- 气泡下方的引用区 -------------------------------------------------------

/**
 * 助手气泡下方的「依据」区。
 *
 * 三种状态互斥展示（顺序也是优先级）：
 * 1. 有引用 → 列「依据」芯片（点开定位到原文）；
 * 2. 未标注（[UiMessage.ungrounded]）→ 黄色警告 + "检索到的片段"，明确这段文字不能当结论；
 * 3. 只检索到、没引用（如缺 Key）→ 列"检索到的片段（未被引用）"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CitationSection(message: UiMessage, onCitationClick: (CitationRef) -> Unit) {
    val hasCitations = message.citations.isNotEmpty()
    if (!hasCitations && message.references.isEmpty() && !message.ungrounded) return

    Column(Modifier.widthIn(max = 320.dp)) {
        if (message.ungrounded) {
            Text(
                "⚠️ 这条回答没有标注可核对的依据，可能来自模型自己的记忆，不能作为知识库结论。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .background(WARN_BACKGROUND, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            Spacer(Modifier.height(6.dp))
        }
        val chips = if (hasCitations) message.citations else message.references
        if (chips.isNotEmpty()) {
            Text(
                if (hasCitations) "依据" else "检索到的片段（未被引用）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (ref in chips) {
                    CitationChip(ref = ref, onClick = { onCitationClick(ref) })
                }
            }
        }
    }
}

@Composable
private fun CitationChip(ref: CitationRef, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "[${ref.n}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "${ref.docName} · 第${ref.seq}段",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 170.dp)
            )
        }
    }
}

/**
 * 引用详情：**「能定位到原文位置」的最终落点**。
 *
 * 上面是元信息（文件 / 段号 / 行号 / 字符区间 / 命中词），中间是命中的那段原文，
 * 下面是原文上下文并**高亮**命中区间。上下文窗口取前后各 1200 字：
 * 再长就变成"全文阅读器"，而这里的目的只是让用户一眼确认"这句话确实在原文这里"。
 *
 * 原文按需从知识库读（[KnowledgeQaStore.fullText]），不在每条历史消息里存全文——
 * 20 条会话 × 几十条引用的正文会把历史文件撑爆（见 [CitationRef] 的说明）。
 */
@Composable
fun CitationDetailDialog(ref: CitationRef, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { KnowledgeQaStore(context.applicationContext) }
    val clipboard = LocalClipboardManager.current
    var fullText by remember(ref.docId) { mutableStateOf<String?>(null) }

    LaunchedEffect(ref.docId) { fullText = store.fullText(ref.docId) }

    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ref.docName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                Text(
                    "第 ${ref.seq} 段 · 第 ${ref.lineStart} 行 · 字符 ${ref.start}–${ref.end}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (ref.matched.isNotEmpty()) {
                    Text(
                        "命中：" + ref.matched.take(6).joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(ref.snippet, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        "原文位置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    val full = fullText
                    if (full == null) {
                        Text("读取原文…", style = MaterialTheme.typography.bodySmall)
                    } else if (full.isBlank()) {
                        Text(
                            "原文已不存在（文档可能已被删除或重新导入）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        // 文档可能被重新导入过，长度变了 → 位置先夹回合法范围，
                        // 否则 substring 会抛异常把弹层炸掉。
                        val windowStart = (ref.start - CONTEXT_RADIUS).coerceIn(0, full.length)
                        val windowEnd = (ref.end + CONTEXT_RADIUS).coerceIn(windowStart, full.length)
                        val annotated: AnnotatedString = remember(full, ref, highlight) {
                            buildAnnotatedString {
                                append(full.substring(windowStart, windowEnd))
                                val from = (ref.start - windowStart).coerceIn(0, windowEnd - windowStart)
                                val to = (ref.end - windowStart).coerceIn(from, windowEnd - windowStart)
                                addStyle(
                                    SpanStyle(background = highlight, fontWeight = FontWeight.Medium),
                                    from,
                                    to
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(10.dp)
                                )
                                .padding(10.dp)
                        ) {
                            Text(annotated, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
                        }
                        if (windowStart > 0 || windowEnd < full.length) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "仅显示命中位置前后各 $CONTEXT_RADIUS 字（全文 ${full.length} 字）。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(ref.snippet))
                    }) { Text("复制片段") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

// ---- 知识库管理 -------------------------------------------------------------

/**
 * 知识库管理弹层：导入 / 删除 / 看规模。
 *
 * 与原来那页的弹层一致（删除立刻生效，不提供撤销——数据是用户自己的文件，
 * 重导一次即可；多一层撤销栈会让这个十几行的弹层复杂一倍），
 * 额外补了一句「同名覆盖」的口径说明，避免用户重复导入后以为多了一份。
 */
@Composable
fun KnowledgeBaseDialog(
    docs: List<KnowledgeDoc>,
    busy: Boolean,
    onImport: () -> Unit,
    onDelete: (KnowledgeDoc) -> Unit,
    onDismiss: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<KnowledgeDoc?>(null) }
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("知识库") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (docs.isEmpty()) {
                        "还没有导入文档。支持 .txt / .md 等纯文本，单个最大 8 MB。导入后提问只会依据这些内容作答。"
                    } else {
                        "${docs.size} 份文档。同名文件再次导入会覆盖旧版。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                for (doc in docs) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                doc.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${formatCharCount(doc.charCount)} · ${formatter.format(Date(doc.addedAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pendingDelete = doc }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (busy) "处理中…" else "导入文档")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${doc.name}》？") },
            text = { Text("这份文档与它的全部切分内容会一起删掉。之前对话里的引用将无法再定位到原文。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(doc)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

// ---- 空态建议 ---------------------------------------------------------------

/**
 * 空态的三条建议问题。
 *
 * 从知识库文档名推出来（"《XX》里讲了什么"），而不是写死几句通用话术：
 * 文档名是用户自己导入的，用它生成的问题**一定**能在库里检索到东西，
 * 第一次点就能看到"回答 + 依据"的完整效果——这比任何引导文案都有说服力。
 * 没有文档时给一条"去导入"的说明，不假装能问。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestedQuestions(docs: List<KnowledgeDoc>, onPick: (String) -> Unit) {
    if (docs.isEmpty()) return
    val suggestions = remember(docs) {
        docs.take(3).map { "《${it.name.substringBeforeLast('.')}》里讲了什么？" }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            "试试这样问",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (text in suggestions) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.clickable { onPick(text) }
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ---- 小工具 -----------------------------------------------------------------

/**
 * 「复制含来源」：回答正文 + 依据清单。
 *
 * 依据写成 `[n] 《文件名》第 N 段（第 L 行）`——用户把回答贴到别处时，
 * 来源跟着走，而不是只剩一段无从核对的话。没有引用时退回纯文本。
 */
fun buildCopyWithSources(message: UiMessage): String {
    if (message.citations.isEmpty()) return message.text
    return buildString {
        append(message.text.trimEnd())
        append("\n\n依据：\n")
        message.citations.forEach { ref ->
            append("[${ref.n}] 《${ref.docName}》第 ${ref.seq} 段（第 ${ref.lineStart} 行）\n")
        }
    }.trimEnd()
}

/** 引用详情里展示的原文上下文半径（字符）。 */
private const val CONTEXT_RADIUS = 1200

/** 未标注依据的提示底色（浅琥珀，深浅主题下都读得清）。 */
private val WARN_BACKGROUND = Color(0x33FFB300)

/** 字符数 → 「1.2 万字」这类可读规模。 */
private fun formatCharCount(count: Int): String = when {
    count >= 10_000 -> String.format(Locale.getDefault(), "%.1f 万字", count / 10_000.0)
    count >= 1_000 -> String.format(Locale.getDefault(), "%.1f 千字", count / 1_000.0)
    else -> "$count 字"
}
