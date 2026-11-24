package com.morecheck.domain

/**
 * 知识问答的系统提示词构造 + 引用标记解析。
 *
 * ## 这一层为什么单独存在（而不是塞进界面文件里）
 *
 * 「不许靠模型记忆作答」「每句话都要带依据」这两条是**产品的硬约束**，不是排版细节。
 * 约束靠两件事落地：发给模型的提示词，以及拿回答案后的**引用校验**。
 * 两者必须成对演进（改了引用格式却没改解析，用户就会看到一堆解析不出来的标记），
 * 所以放在一起、且**纯函数、无 Android 依赖**，可以直接单测。
 *
 * ## 引用协议（提示词与解析必须是同一套）
 *
 * - 模型看到的片段编号是 1..N（[hits] 的顺序），**不是**数据库里的 chunk id——
 *   给 id 会让模型有机会"发明"一个看起来合法但实际不存在的引用
 *   （形如 `abc123#7`）；给 1..N 之后，任何越界编号都能被我们判为无效。
 * - 模型被要求在每个事实句后写 `[1]`、`[2]`，多个片段写 `[1][3]`。
 * - [citedIndexes] 只认 1..N 的编号，越界与重复一律丢弃。
 * - **一个合法引用都没有 = 未标注依据**：界面会显式提示，不把这段文字当结论。
 */
object KnowledgeQaPrompt {

    /**
     * 「没查到」时给用户的固定回答。
     *
     * 这句话**由本地生成、不经过模型**——需求明确要求「检索不到依据时直接说明没查到」，
     * 若把问题丢给模型让它"自由发挥"再说自己没查到，等于把幻觉的可能性请进门。
     */
    const val NO_EVIDENCE_ANSWER = "知识库中没有找到能回答这个问题的内容。"

    /** 没查到时的补充建议（也由本地拼，不进模型）。 */
    const val NO_EVIDENCE_HINT =
        "可以换一种说法再问，或先在「知识库」里导入包含相关内容的一份文档（当前只支持 .txt / .md 等纯文本）。"

    /** 引用标记：`[1]` ~ `[99]`。允许中文全角方括号的常见误写。 */
    private val MARKER = Regex("""[\[［](\d{1,2})[\]］]""")

    /**
     * 把检索结果渲染成提示词里的「资料片段」段落。
     *
     * 每个片段都带上文件名、段号、行号、字符区间——这些信息进上下文有两个作用：
     * 1. 模型在答案里引用时可以顺带说得更具体（「第 3 段」），用户更容易核对；
     * 2. 万一模型把编号写错，用户仍能靠界面上的引用卡片定位（卡片用的是本地数据，
     *    不依赖模型输出的文字）。
     */
    fun excerpts(hits: List<KnowledgeHit>): String = buildString {
        hits.forEachIndexed { index, hit ->
            val c = hit.chunk
            append("【片段").append(index + 1).append("】")
            append("文件：《").append(c.docName).append("》")
            append("｜第 ").append(c.seq).append(" 段")
            append("｜第 ").append(c.lineStart).append(" 行")
            append("｜字符 ").append(c.start).append("-").append(c.end)
            append('\n')
            append(c.text)
            append("\n\n")
        }
    }.trimEnd()

    /**
     * 构造系统提示词。
     *
     * 规则写得比较"硬"，是刻意的：通用大模型默认会把「常识」补进答案，
     * 而知识问答里"补一句想当然的话"比"少说一句"危害大得多。
     */
    fun systemPrompt(hits: List<KnowledgeHit>): String = buildString {
        append("你是「知识问答 Agent」。你只能依据下面给出的【资料片段】回答用户问题。\n\n")
        append("必须遵守的规则：\n")
        append("1. 每一个陈述事实的句子后面都要紧跟引用标记，格式为 [1]、[2]（对应片段编号）；")
        append("一句话依据多个片段时写成 [1][3]。\n")
        append("2. 只使用片段里出现过的信息。片段里没有的内容一律不写，")
        append("不要用你自己的知识补充，不要推测，不要编造数据、示例或链接。\n")
        append("3. 如果这些片段不足以回答用户的问题，")
        append("只输出这一句：").append(NO_EVIDENCE_ANSWER).append("不许输出任何其它内容。\n")
        append("4. 片段之间说法冲突时，如实指出冲突（分别引用各自的片段），不要替用户裁决。\n")
        append("5. 用简体中文回答；先给结论，再给必要要点；不要整段抄写片段原文，")
        append("也不要输出「根据资料片段」这类套话。\n\n")
        append("资料片段：\n")
        append(excerpts(hits))
    }

    /**
     * 从模型回答里取出它引用了哪几个片段。
     *
     * @param answer 模型输出原文。
     * @param count 可引用片段的数量（= 送给模型的片段数）。
     * @return 去重升序的片段编号（1..count）；**空列表 = 这条回答没有可核对的依据**。
     */
    fun citedIndexes(answer: String, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        return MARKER.findAll(answer)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..count }
            .distinct()
            .sorted()
            .toList()
    }

    /**
     * 模型是否"照章办事"地回答了没查到。
     *
     * 提示词第 3 条让模型在资料不足时只回一句固定话。这句固定话里没有任何引用标记，
     * 所以它必然落到「未标注依据」分支；这里单独识别一下，好让界面显示成
     * **正常的「没查到」**（灰色说明卡）而不是**警告的「未标注」**（黄条）——
     * 两者对用户的含义完全不同。
     */
    fun isNoEvidenceAnswer(answer: String): Boolean =
        answer.trim().trim('。', '.', ' ').startsWith(NO_EVIDENCE_ANSWER.trim('。'))
}
