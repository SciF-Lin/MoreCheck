package com.morecheck.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 回复的 Markdown 子集渲染（v1.1.69 需求：文本显示适配 Markdown 格式）。
 *
 * ## 为什么自己写一个，而不引渲染库
 *
 * 本项目一贯口径「除 AndroidX/Compose 外只保留 ZXing 一个外部依赖」，且要渲染的
 * 内容很窄：模型回复里常见的**加粗、斜体、行内代码、标题、列表、代码块、引用**。
 * 引入 `markdown-compose` 之类库要连带一整套解析器与样式体系，为这七种语法划不来；
 * 平台也没有开箱可用的 Markdown 渲染器。所以这里做一个**单文件、只读不写**的
 * 轻量子集解析：块级按行分类（代码块 / 标题 / 列表 / 引用 / 普通段），行内做
 * `**` / `*` / `` ` `` / `~~` 的 SpanStyle 着色。
 *
 * ## 刻意不支持的部分（不是遗漏）
 *
 * - **表格、嵌套列表、有序列表、任务列表、图片、链接卡片**：模型在窄屏气泡里
 *   输出这些的概率低，且实现复杂度陡增（表格要测量列宽）。链接文本会保留
 *   `[text](url)` 的原样文本而非可点击——气泡里弹浏览器需要新的交互契约，
 *   超出「能读」这个目标。
 * - **HTML / 自定义容器**：一律按纯文本展示。
 *
 * ## 渲染口径
 *
 * - 每个块之间 4dp 间距，块内不额外加距（自然行高）；
 * - 代码块用 `FontFamily.Monospace` + 等宽字号 13sp，底色取 `surfaceVariant`，
 *   与本项目卡片色系一致（不新增颜色常量）；
 * - 标题用主题 typography 的 titleSmall / titleMedium / titleLarge 三档；
 * - 列表符号用「• 」前缀 + 12dp 缩进，不用真正的 `bulleted` 修饰符——
 *   后者在嵌套换行时对齐不可控，而文本前缀在任何 Compose 版本下都稳。
 * - 流式输出中代码块未闭合时按普通文本显示（见 [AiMarkdownBody] 的说明）。
 */
@Composable
fun AiMarkdownBody(
    markdown: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseAiMarkdown(markdown) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(4.dp))
            when (block) {
                is AiBlock.Code -> CodeBlock(block.lines, color)
                is AiBlock.Heading -> Text(
                    text = inline(block.text, color),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                is AiBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                    // 引用竖条：3dp 宽、与文字同色系但半透明。
                    // 高度固定 18dp 而不是文字行高：行内样式（斜体）会让行高抖动，
                    // 固定高度在多行引用下也能保持竖条连续。
                    Spacer(
                        Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(
                                color = color.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = inline(block.text, color),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = color.copy(alpha = 0.85f)
                    )
                }
                is AiBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = block.indent.let { " ".repeat(it) } + "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color.copy(alpha = 0.7f)
                    )
                    Text(
                        text = inline(block.text, color),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color
                    )
                }
                is AiBlock.Paragraph -> Text(
                    text = inline(block.text, color),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
            }
        }
    }
}

/** 代码块：等宽 + surfaceVariant 底 + 水平内边距（视觉上"缩进一块"）。 */
@Composable
private fun CodeBlock(lines: List<String>, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        lines.forEach { line ->
            Text(
                text = line.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = color
            )
        }
    }
}

// ---- 解析 ---------------------------------------------------------------

/** Markdown 块级类型（解析的中间产物）。 */
private sealed interface AiBlock {
    data class Paragraph(val text: String) : AiBlock
    data class Heading(val level: Int, val text: String) : AiBlock
    data class Bullet(val text: String, val indent: Int) : AiBlock
    data class Quote(val text: String) : AiBlock
    data class Code(val lines: List<String>) : AiBlock
}

/**
 * 把 Markdown 文本切成块序列。
 *
 * 行分类口径（按优先级从上到下）：
 * 1. ``` 围栏 → 代码块（遇到下一条围栏才闭合；**未闭合时把已收集的行当代码块输出**，
 *    这样流式输出中途也能正确显示，而不是等模型说完才跳样式）；
 * 2. `#`~`###` → 标题（取 1~3 级，`####` 以上按普通段，避免字号过大占屏）；
 * 3. `- ` / `* ` / `+ ` → 列表项，行首连续空格 / Tab 折算成缩进级数（每 2 空格一级）；
 * 4. `> ` → 引用；
 * 5. 空行 → 段分隔；
 * 6. 其余归并入当前普通段（段内换行保留，Compose Text 自然折行）。
 */
private fun parseAiMarkdown(markdown: String): List<AiBlock> {
    val blocks = ArrayList<AiBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(AiBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    var inCode = false
    val codeLines = ArrayList<String>()

    for (raw in markdown.lines()) {
        val line = raw.trimEnd()

        // ---- 代码块围栏 ----
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks.add(AiBlock.Code(codeLines.toList()))
                codeLines.clear()
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            codeLines.add(raw) // 代码块里保留原始缩进，不做 trim
            continue
        }

        // ---- 空行：段分隔 ----
        if (line.isBlank()) {
            flushParagraph()
            continue
        }

        // ---- 标题 ----
        val heading = HEADING_PATTERN.find(line)
        if (heading != null) {
            flushParagraph()
            blocks.add(AiBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            continue
        }

        // ---- 列表项 ----
        val bullet = BULLET_PATTERN.find(line)
        if (bullet != null) {
            flushParagraph()
            val indentUnit = bullet.groupValues[1].replace("\t", "  ")
            val level = (indentUnit.length / 2).coerceAtMost(4)
            blocks.add(AiBlock.Bullet(bullet.groupValues[2], level))
            continue
        }

        // ---- 引用 ----
        val quote = QUOTE_PATTERN.find(line)
        if (quote != null) {
            flushParagraph()
            blocks.add(AiBlock.Quote(quote.groupValues[1]))
            continue
        }

        // ---- 普通段：累积（段内换行保留为 \n） ----
        if (paragraph.isNotEmpty()) paragraph.append('\n')
        paragraph.append(line)
    }
    flushParagraph()
    // 流式中途未闭合的代码块：把已收集的行作为代码块输出。
    if (inCode && codeLines.isNotEmpty()) blocks.add(AiBlock.Code(codeLines.toList()))
    return blocks
}

/**
 * 行内 Markdown → [AnnotatedString]。
 *
 * 支持：`**粗体**`、`*斜体*` / `_斜体_`、`` `行内代码` ``、`~~删除线~~`。
 * 解析用正则按优先级切分，**不做真正的 AST**——行内语法的错配（如单个落单 `*`）
 * 一律按字面文本输出，宁可少渲染也不要渲染错。
 *
 * ## 性能口径（v1.1.90 优化）
 *
 * [INLINE_PATTERN] 与三个行分类正则都是**顶层常量**：`Regex` 编译不便宜，
 * 流式输出时解析随每个 token 重跑一次，在循环体里现场 `Regex(...)`
 * 会把编译成本放大成可感知的卡顿（原实现在 [parseAiMarkdown] 每行新建 3 个、
 * 这里每个块新建 1 个）。规则串与旧版逐字一致，纯提升，无行为变化。
 */
private val INLINE_PATTERN =
    Regex("(\\*\\*[^*]+\\*\\*|\\*[^*\\n]+\\*|_[^_\\n]+_|`[^`\\n]+`|~~[^~\\n]+~~)")

private val HEADING_PATTERN = Regex("^(#{1,3})\\s+(.*)$")

private val BULLET_PATTERN = Regex("^([ \\t]*)[-*+]\\s+(.*)$")

private val QUOTE_PATTERN = Regex("^>\\s?(.*)$")

private fun inline(text: String, color: Color): AnnotatedString =
    buildAnnotatedString {
        val pattern = INLINE_PATTERN
        var cursor = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length > 4 ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) {
                        append(token.removeSurrounding("**"))
                    }
                token.startsWith("`") && token.endsWith("`") && token.length > 2 ->
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = color.copy(alpha = 0.12f)
                        )
                    ) { append(token.removeSurrounding("`")) }
                token.startsWith("~~") && token.endsWith("~~") && token.length > 4 ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = color)) {
                        append(token.removeSurrounding("~~"))
                    }
                else ->
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = color)) {
                        append(
                            token.removeSurrounding("*").removeSurrounding("_")
                        )
                    }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
        // 无任何匹配时也要保证颜色正确（buildAnnotatedString 默认不继承 Text 的 color）。
        if (toAnnotatedString().text.isEmpty()) append(text)
        addStyle(SpanStyle(color = color), 0, length)
    }
