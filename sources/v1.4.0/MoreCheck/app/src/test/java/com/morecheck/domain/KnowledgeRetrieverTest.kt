package com.morecheck.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知识问答的纯逻辑回归测试（2026-10-22）。
 *
 * 覆盖三块「不需要 Android 运行时」的部分——它们恰好也是这个功能**唯一的判定逻辑**：
 * 1. [KnowledgeChunker]：切块与位置记录。位置错了，引用就指不到原文（功能的核心承诺）；
 * 2. [KnowledgeRetriever]：词项化与相关性闸门。闸门太松会把无关段落塞给模型
 *    （模型就会"根据片段"编答案），太紧会让单文档知识库永远"查不到"；
 * 3. [KnowledgeQaPrompt]：提示词与引用解析必须是同一套协议，改一边就会集体失效。
 *
 * ⚠️ 这些用例**不能**保证回答正确——那取决于模型。它们保证的是：
 * 本地这一侧的「检索 / 拦截 / 校验」三件事不会在改动中悄悄坏掉。
 */
class KnowledgeRetrieverTest {

    private val doc = """
        打卡是指在设定的时间点击打卡按钮，完成一次记录。
        连续打卡会累积连胜天数，断卡则归零。

        课程表支持今天、本周、全部课程三种视图切换。
    """.trimIndent()

    private fun chunksOf(name: String = "使用说明.md"): List<KnowledgeChunk> =
        KnowledgeChunker.chunk("doc1", name, doc)

    // ── 切块 ────────────────────────────────────────────────────────────────

    @Test
    fun `切块的位置区间能还原出原文`() {
        val chunks = chunksOf()
        assertTrue("短文档也应至少切出一块", chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue("起始必须小于结束", chunk.start < chunk.end)
            // 用位置回切原文（再 trim）必须与块正文一致——这正是引用详情里
            // 「点开就能看到原文这一段」的实现前提。
            val restored = doc.replace("\r\n", "\n")
                .substring(chunk.start, chunk.end)
                .trim()
            assertEquals(chunk.text, restored)
        }
    }

    @Test
    fun `块号从一开始且逐块递增`() {
        val chunks = chunksOf()
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index + 1, chunk.seq)
        }
    }

    @Test
    fun `长文档会被切成多块且相邻块有重叠`() {
        val long = buildString {
            for (i in 1..40) {
                append("第").append(i).append("条：这是用来凑长度的一段说明文字，")
                append("内容本身没有意义，只用于验证切块行为是否正确。\n")
            }
        }
        val chunks = KnowledgeChunker.chunk("d", "长文.md", long)
        assertTrue("4 千字以上应切成多块", chunks.size >= 2)
        // 重叠是为了防止答案压在切缝上被两边各切一半；这里只验证"确实有重叠"，
        // 不锁死精确字数（边界对齐会让实际重叠在上限附近浮动）。
        assertTrue("后一块应从前一块内部开始", chunks[1].start < chunks[0].end)
    }

    @Test
    fun `空白文档切不出任何块`() {
        assertTrue(KnowledgeChunker.chunk("d", "空.txt", "   \n\n  \t ").isEmpty())
    }

    // ── 词项化 ──────────────────────────────────────────────────────────────

    @Test
    fun `中文按二元组切分_英文数字按单词切分`() {
        val tokens = KnowledgeRetriever.tokenize("打卡ABC123")
        assertTrue("应含中文二元组 打卡", tokens.contains("打卡"))
        assertTrue("应含英文数字词", tokens.contains("abc123"))
        assertTrue("单字不应单独成词", !tokens.contains("打"))
    }

    @Test
    fun `疑问套话不参与检索`() {
        val tokens = KnowledgeRetriever.tokenize("如何打卡")
        assertFalse("「如何」是套话，不该进词项", tokens.contains("如何"))
        assertTrue(tokens.contains("打卡"))
    }

    // ── 检索 ────────────────────────────────────────────────────────────────

    @Test
    fun `相关问题能命中对应片段`() {
        val hits = KnowledgeRetriever.search("连胜天数怎么算", chunksOf())
        assertTrue("应命中至少一段", hits.isNotEmpty())
        assertTrue(
            "命中片段里应包含「连胜」",
            hits.first().chunk.text.contains("连胜")
        )
    }

    @Test
    fun `完全无关的问题必须返回空列表`() {
        // 空列表 = 「知识库没查到」 → 界面直接拒答，不调用模型。
        // 这条是需求里「不许靠模型记忆作答」的开关，破了整个功能就失去意义。
        val hits = KnowledgeRetriever.search("如何申请差旅报销", chunksOf())
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `单文档知识库也能检索到_不因语料太小被分数闸门挡住`() {
        // 回归用例：早期用固定分数下限（1.0）时，一篇文档的知识库会因为
        // BM25 的 idf 随语料规模变小而**永远查不到**。现在改用词项覆盖率判定。
        val hits = KnowledgeRetriever.search("打卡记录", chunksOf())
        assertTrue("单文档库必须能命中", hits.isNotEmpty())
    }

    @Test
    fun `知识库为空时检索返回空列表`() {
        assertTrue(KnowledgeRetriever.search("随便问点什么", emptyList()).isEmpty())
    }

    @Test
    fun `命中结果不超过指定条数`() {
        val many = (1..20).flatMap { i ->
            KnowledgeChunker.chunk("doc$i", "文档$i.md", "打卡记录与连胜天数说明第 $i 份")
        }
        val hits = KnowledgeRetriever.search("打卡记录连胜天数", many, limit = 3)
        assertTrue("最多返回 limit 条", hits.size <= 3)
    }

    // ── 提示词与引用协议 ─────────────────────────────────────────────────────

    @Test
    fun `提示词里带上文件名与段号`() {
        val hits = KnowledgeRetriever.search("连胜天数", chunksOf(name = "说明.md"))
        assertTrue(hits.isNotEmpty())
        val prompt = KnowledgeQaPrompt.systemPrompt(hits)
        assertTrue("必须带文件名，模型才能在回答里指认依据", prompt.contains("《说明.md》"))
        assertTrue("必须带段号", prompt.contains("第 1 段"))
        assertTrue("必须把规则写进提示词", prompt.contains(KnowledgeQaPrompt.NO_EVIDENCE_ANSWER))
    }

    @Test
    fun `引用解析只认合法编号并去重排序`() {
        assertEquals(listOf(1, 2), KnowledgeQaPrompt.citedIndexes("结论[1]，补充[2]，重复[1]，越界[9]", 3))
        assertEquals(listOf(3), KnowledgeQaPrompt.citedIndexes("全角［3］也应识别", 3))
        assertTrue(KnowledgeQaPrompt.citedIndexes("这句话没有任何标记", 3).isEmpty())
        assertTrue("没有可引用片段时一律为空", KnowledgeQaPrompt.citedIndexes("[1]", 0).isEmpty())
    }

    @Test
    fun `没查到的固定回答能被识别出来`() {
        assertTrue(KnowledgeQaPrompt.isNoEvidenceAnswer(KnowledgeQaPrompt.NO_EVIDENCE_ANSWER + "。"))
        assertFalse(KnowledgeQaPrompt.isNoEvidenceAnswer("打卡是点击按钮完成记录[1]"))
    }
}
