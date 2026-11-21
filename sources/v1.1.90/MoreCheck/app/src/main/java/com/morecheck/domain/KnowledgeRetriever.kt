package com.morecheck.domain

import kotlin.math.ln

/**
 * 知识库里的一段切块（= 检索的最小单位，也是**引用定位的锚点**）。
 *
 * ## 为什么每条引用都要带 start / end / lineStart
 *
 * 需求要求「引用能定位到原文位置」。只给文档名不够（一篇文档可能几万字），
 * 所以在切块时就记下它在**原文**里的字符区间与起始行号，界面上可以据此：
 * - 展示「第 3 段 · 第 42 行 · 字符 1200–1900」；
 * - 点开时只渲染命中位置前后的一小段上下文，并高亮这段区间，
 *   而不是把整篇文档甩给用户自己找。
 *
 * @param id 全局唯一（`<docId>#<seq>`）。**生成新回答时它不进模型上下文**，
 *   只用于界面回查——模型看到的是片段序号 1..N，避免它编造一个看起来合法的 id。
 * @param docId 所属文档的稳定 id（[com.morecheck.data.KnowledgeDoc.id]）。
 * @param docName 文档显示名（导入时的文件名），引用展示要用。
 * @param seq 该文档内的段号，**从 1 开始**（界面直接写「第 N 段」）。
 * @param start 在原文中的起始字符下标（含）。
 * @param end 在原文中的结束字符下标（不含）。
 * @param lineStart 起始字符所在行号（从 1 开始），给「第 N 行」用。
 * @param text 切出来的正文（已 trim；可能与 [start]..[end] 的原始切片有一两个空白的出入，
 *   这是刻意的——定位区间宁可按未 trim 的原文算，也不为了让两者严格相等而保留脏空白）。
 */
data class KnowledgeChunk(
    val id: String,
    val docId: String,
    val docName: String,
    val seq: Int,
    val start: Int,
    val end: Int,
    val lineStart: Int,
    val text: String
)

/**
 * 一次检索命中的片段。
 *
 * @param chunk 命中的切块。
 * @param score BM25 得分（同一次检索内可比，跨查询不可比）。
 * @param matchedTerms 命中的词项（用于界面「命中：打卡 记录」这类轻量提示，
 *   以及给用户一个「为什么这段会被选中」的最小解释）。
 */
data class KnowledgeHit(
    val chunk: KnowledgeChunk,
    val score: Double,
    val matchedTerms: List<String>
)

/**
 * 把一篇文档切成检索用的段落块。
 *
 * ## 为什么是「定长窗口 + 滑窗重叠 + 边界对齐」，而不是按标题/段落切
 *
 * 用户导入的是任意纯文本 / Markdown：可能是一篇有清晰小标题的说明，也可能是
 * 从别处复制来的、一段就是三千字的流水账。按空行切在后者上会切出超长块
 * （一大块塞满上下文，检索精度骤降），按标题切在没标题的文档上会退化成整篇一块。
 * 所以用**定长窗口**兜住最坏情况，再做两件改善：
 * 1. **边界对齐**：窗口末尾若不是换行 / 句末标点，就往前（必要时往后）挪几十个字
 *    对齐到最近的边界，避免一句话被切成两半、两边都不完整；
 * 2. **滑窗重叠**：相邻块重叠 [CHUNK_OVERLAP] 字，避免「答案正好压在切缝上」时
 *    两边各缺一半、谁都检索不到。
 *
 * 参数取值：700 字 ≈ 中文 500–700 token，一次检索带 6 块进上下文仍在万级 token
 * 以内，对用户自带的 DeepSeek / 千问等模型是舒适区间。重叠 150 字（约两句话）。
 */
object KnowledgeChunker {

    /** 单块目标长度（字符）。 */
    const val CHUNK_SIZE = 700

    /** 相邻块重叠长度（字符）。必须显著小于 [CHUNK_SIZE]，否则滑窗推不动。 */
    const val CHUNK_OVERLAP = 150

    /** 句末标点：边界对齐时优先断在这些字符之后。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '；', '.', '!', '?', ';')

    /**
     * 切块。空白文档返回空列表（调用方据此提示「这份文档没有可检索的正文」）。
     *
     * 换行统一成 `\n` 后再算下标：否则 CRLF 文档的 `start`/`end` 与界面里
     * 重新读出来的文本对不上，高亮会整体偏移一个字。
     */
    fun chunk(docId: String, docName: String, raw: String): List<KnowledgeChunk> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()

        val result = ArrayList<KnowledgeChunk>()
        var start = 0
        var seq = 1
        while (start < text.length) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) end = snapToBoundary(text, start, end)
            val slice = text.substring(start, end).trim()
            if (slice.isNotEmpty()) {
                result += KnowledgeChunk(
                    id = "$docId#$seq",
                    docId = docId,
                    docName = docName,
                    seq = seq,
                    start = start,
                    end = end,
                    lineStart = lineOf(text, start),
                    text = slice
                )
                seq++
            }
            if (end >= text.length) break
            // 至少前进一个字，保证循环一定收敛（CHUNK_SIZE > CHUNK_OVERLAP 已保证，
            // 这里再兜一次，防止将来有人把两个常量改成相等）。
            start = (end - CHUNK_OVERLAP).coerceAtLeast(start + 1)
        }
        return result
    }

    /**
     * 把窗口末尾挪到最近的语义边界。
     *
     * 先往回找（最多 160 字，且不越过窗口一半），优先换行、其次句末标点；
     * 往回找不到就往后找 80 字内的换行。都没有就保持原样——不对齐只是切缝难看，
     * 不影响功能，所以这里**绝不为了对齐把窗口缩到很短**（那会让块数暴涨）。
     */
    private fun snapToBoundary(text: String, start: Int, end: Int): Int {
        val lower = maxOf(start + CHUNK_SIZE / 2, end - 160)
        for (i in end downTo lower) {
            if (i - 1 >= 0 && text[i - 1] == '\n') return i
        }
        for (i in end downTo lower) {
            if (i - 1 >= 0 && text[i - 1] in SENTENCE_END) return i
        }
        val upper = minOf(end + 80, text.length)
        for (i in end until upper) {
            if (text[i] == '\n') return i + 1
        }
        return end
    }

    /** 字符下标 → 1 起始的行号。 */
    private fun lineOf(text: String, offset: Int): Int {
        var line = 1
        val limit = minOf(offset, text.length)
        for (i in 0 until limit) if (text[i] == '\n') line++
        return line
    }
}

/**
 * 纯本地的关键词检索（BM25）。
 *
 * ## 为什么不用向量检索（embedding）
 *
 * 本项目的智能体是「用户自带 API Key 直连 OpenAI 兼容端点」，没有任何本地模型，
 * 也没有可长期依赖的 embedding 服务（用户可能随时停用/换 Key）。为知识库引入
 * 一个额外必填的 embedding 提供商，会让「导入文档就能问」变成「先配三样东西」，
 * 而且一旦 Key 失效，连检索都做不了。BM25 零依赖、离线可跑、可解释（每个命中都能
 * 说清是哪些词命中），对「个人知识库规模（几篇到几十篇）」足够。
 *
 * 代价必须写清楚：**它不理解同义改写**。用户问「怎么补卡」而文档里写的是
 * 「补打卡」时，靠二元组「补卡」无法命中。这是已知取舍，缓解手段是
 * 界面把「检索到的片段」原样展示给用户，并提示可以换个说法再问。
 *
 * ## 中文为什么用二元组
 *
 * 没有分词器。中文单字信息量太低（「的」「是」满篇），长词又没有词典可查，
 * 二元组是零依赖下性价比最高的近似：绝大部分双字词能原样命中，未登录词也能
 * 落到两个相邻二元组上。英文/数字按单词切（长度 ≥ 2）。
 *
 * ## 性能口径（刻意不做缓存）
 *
 * 每次提问都重新分词全部切块。个人知识库通常几百块、几十万字，单次检索是
 * 毫秒级到几十毫秒级；引入倒排索引缓存要管失效（导入/删除文档时），
 * 在一个「问一句等几秒」的功能里不值得。若将来知识库到几 MB，这里需要改成
 * 增量倒排。
 */
object KnowledgeRetriever {

    /** BM25 词频饱和参数（文献常用值）。 */
    private const val K1 = 1.2

    /** BM25 长度归一参数（文献常用值）。 */
    private const val B = 0.75

    /** 默认取几块进上下文。6 块 ≈ 4200 字，仍在常见模型的舒适上下文内。 */
    const val DEFAULT_LIMIT = 6

    /**
     * 绝对分下限。**刻意定得很低（不是"合格线"）**：BM25 的分值随语料规模变化，
     * 同样的相关性在小库上只有 0.3、在大库上能到 3.0（idf 依赖文档频率）。
     * 拿它当"够不够相关"的判据，会让**只导入了一份文档**的用户永远得到"没查到"。
     * 所以这里只用来丢掉近乎 0 分的噪声，真正的相关性判定交给
     * 「命中词项覆盖率」（见 [search] 里的 `required`，与语料规模无关）。
     */
    private const val MIN_SCORE = 0.15

    /** 相对分下限：只保留不低于最高分 35% 的块，避免一份文档的弱命中挤掉强命中。 */
    private const val RELATIVE_FLOOR = 0.35

    /** 整句原样出现（去空白标点后）的额外加分，用来压过「只是零散词命中」的块。 */
    private const val PHRASE_BOOST = 4.0

    /** 拉丁字母/数字词。 */
    private val LATIN = Regex("[a-z0-9_]+")

    /** 连续汉字串。 */
    private val CJK = Regex("[\u4e00-\u9fff]+")

    /**
     * 极少量无信息量的二元组。
     *
     * 刻意只放**疑问套话**，不放「的/是/和」这类单字（单字本来就不进词表）
     * 与「可以/需要」这类实词——滤多了会把有效查询词一起滤掉，
     * 而多留几个套话词只是略微稀释得分，后果轻得多。
     */
    private val STOP = setOf(
        "什么", "怎么", "如何", "哪些", "哪个", "是否", "为什", "这个", "那个",
        "一个", "我们", "你们", "他们", "请问", "一下", "可以", "多少"
    )

    /**
     * 分词。返回**可能重复**的词项（词频信息在这里保留，由 [search] 统计）。
     *
     * @return 词项列表；纯标点 / 空白输入返回空列表。
     */
    fun tokenize(raw: String): List<String> {
        val text = raw.lowercase()
        val tokens = ArrayList<String>()
        for (m in LATIN.findAll(text)) {
            val w = m.value
            if (w.length >= 2) tokens += w
        }
        for (m in CJK.findAll(text)) {
            val run = m.value
            if (run.length == 1) {
                tokens += run
            } else {
                for (i in 0 until run.length - 1) tokens += run.substring(i, i + 2)
            }
        }
        return tokens.filter { it !in STOP }
    }

    /**
     * 检索。
     *
     * @param query 用户问题原文。
     * @param chunks 全库切块（[KnowledgeChunker.chunk] 的结果拼接）。
     * @return 命中的片段，按得分降序、最多 [limit] 条。
     *   **空列表 = 「知识库里没有能回答这个问题内容」**，调用方必须据此直接拒绝回答，
     *   不许把问题单独丢给模型——那正是需求里明确禁止的「靠模型记忆作答」。
     */
    fun search(
        query: String,
        chunks: List<KnowledgeChunk>,
        limit: Int = DEFAULT_LIMIT
    ): List<KnowledgeHit> {
        if (chunks.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val prepared = chunks.map { chunk ->
            val terms = tokenize(chunk.text)
            PreparedChunk(chunk, terms, normalizeForPhrase(chunk.text))
        }.filter { it.terms.isNotEmpty() }
        if (prepared.isEmpty()) return emptyList()

        val avgLen = prepared.sumOf { it.terms.size.toDouble() } / prepared.size
        val safeAvg = if (avgLen <= 0.0) 1.0 else avgLen
        val total = prepared.size.toDouble()

        // 文档频率：一个词项出现在多少个块里。
        val df = HashMap<String, Int>()
        for (item in prepared) {
            for (t in item.terms.distinct()) df[t] = (df[t] ?: 0) + 1
        }
        val idf = HashMap<String, Double>(queryTerms.size)
        for (t in queryTerms) {
            val n = (df[t] ?: 0).toDouble()
            idf[t] = ln(1.0 + (total - n + 0.5) / (n + 0.5))
        }

        val phrase = normalizeForPhrase(query)
        val hits = ArrayList<KnowledgeHit>()
        for (item in prepared) {
            val tf = HashMap<String, Int>()
            for (t in item.terms) tf[t] = (tf[t] ?: 0) + 1
            val len = item.terms.size.toDouble()
            var score = 0.0
            val matched = ArrayList<String>()
            for (t in queryTerms) {
                val f = tf[t] ?: continue
                val denom = f + K1 * (1.0 - B + B * len / safeAvg)
                if (denom <= 0.0) continue
                score += (idf[t] ?: 0.0) * f * (K1 + 1.0) / denom
                matched += t
            }
            if (score <= 0.0) continue
            // 整句命中：问题（去空白标点后）原样出现在块里，说明这块大概就是答案所在，
            // 加分让它稳压那些"零散词恰好都出现"的块。
            if (phrase.length >= 2 && item.normalized.contains(phrase)) {
                score += PHRASE_BOOST
                if (!matched.contains(phrase)) matched += phrase
            }
            hits += KnowledgeHit(item.chunk, score, matched)
        }
        if (hits.isEmpty()) return emptyList()

        hits.sortByDescending { it.score }
        // 相关性闸门：**必须命中足够多的查询词项**，而不只是"总分看起来还行"。
        // 单靠分数无法区分"库里只有一份文档，任何词都能捞到点分"与"真的相关"，
        // 而词项覆盖率与语料规模无关，所以拿它当判据。
        // 阈值取 min(2, 查询词数)：单词查询（"打卡"）只要求命中 1 个；
        // 多词查询要求至少 2 个，避免"只撞上一个泛词"的片段冒充相关。
        val required = minOf(2, queryTerms.size)
        val relevant = hits.filter { hit ->
            hit.matchedTerms.size >= required || hit.score >= PHRASE_BOOST
        }
        if (relevant.isEmpty()) return emptyList()

        val best = relevant.first().score
        if (best < MIN_SCORE) return emptyList()
        val floor = maxOf(MIN_SCORE, best * RELATIVE_FLOOR)
        return relevant.filter { it.score >= floor }.take(limit)
    }

    /** 去掉空白与标点后的小写串，用于「整句命中」判断。 */
    private fun normalizeForPhrase(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }

    /** 预分词后的块（避免在一次检索里对同一段文本分词两次）。 */
    private class PreparedChunk(
        val chunk: KnowledgeChunk,
        val terms: List<String>,
        val normalized: String
    )
}
