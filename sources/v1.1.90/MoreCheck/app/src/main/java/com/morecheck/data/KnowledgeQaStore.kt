package com.morecheck.data

import android.content.Context
import com.morecheck.domain.KnowledgeChunk
import com.morecheck.domain.KnowledgeChunker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 知识库里的一份文档（索引项）。
 *
 * @param id 稳定 id，同时是正文文件名（`docs/<id>.txt`）。用随机串而不是文件名：
 *   用户可能导入两份同名文件（来自不同目录），也随时可能改名；
 *   把 id 与名字解耦，改名不影响已保存的引用定位。
 * @param name 显示名（导入时的文件名，含扩展名）。**同名导入是覆盖语义**，见 [KnowledgeQaStore.importDocument]。
 * @param charCount 正文字符数（界面显示「1.2 万字」这类规模信息）。
 * @param addedAt 导入时间戳（毫秒）。用于列表排序（新的在前）。
 */
data class KnowledgeDoc(
    val id: String,
    val name: String,
    val charCount: Int,
    val addedAt: Long
)

/**
 * 知识库的本地持久化 + 切块。
 *
 * ## 为什么是「文件 + JSON」而不是 Room
 *
 * 三条理由，按重要性排序：
 * 1. **不加数据库迁移**：本项目的 `CheckDatabase` 每加一张表都要写迁移并上真机验证，
 *    而知识库是**孤立数据**（不参与打卡、不参与云同步），为它动数据库收益极低、风险不低。
 * 2. **一份文档一个文件**：正文动辄几十万字，放 BLOB 里会让数据库文件迅速膨胀，
 *    而备份 / 清理 / 排查都变得不直观（用户能把 docs 目录下的 txt 文件直接拷出来看）。
 *
 *    ⚠️ 顺带记一条本项目反复踩过的坑：注释里**不要写「斜杠紧跟星」的序列**
 *    （比如某个路径通配符）——Kotlin 的块注释是可嵌套的，那种序列会被当成
 *    嵌套注释的开头，整段 KDoc 永远闭合不了，报 "Unclosed comment" 且行号
 *    指向文件末尾，极难定位。CloudConfig.kt 顶部有同样的警告。
 * 3. **切块是派生的**：块不需要持久化——每次进页面从正文重算（见 [chunks]），
 *    好处是"改了切块算法立刻生效"，不用写数据迁移；代价是冷启动要多花几十毫秒。
 *
 * 目录布局（`filesDir` 下，随应用卸载一起清掉，不进云同步）：
 * ```
 * knowledge_qa/
 *   index.json      [{id,name,chars,addedAt}, ...]
 *   docs/<id>.txt   正文（统一存 UTF-8）
 * ```
 *
 * ## 编码
 *
 * 导入时统一转成 UTF-8 落盘（见 [decode]）。用户从 Windows 记事本拿来的 .txt
 * 常见是 GB18030：直接按 UTF-8 解会得到满篇 U+FFFD，检索等于全废，
 * 而且**不会报错**——这是最阴的一种"看起来成功"的失败，所以解码时做了探测。
 */
class KnowledgeQaStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "knowledge_qa")
    private val docsDir = File(root, "docs")
    private val indexFile = File(root, "index.json")

    /** 所有文档，按导入时间倒序（新的在前）。索引文件缺失 / 损坏时返回空列表。 */
    suspend fun list(): List<KnowledgeDoc> = withContext(Dispatchers.IO) { readIndex() }

    /**
     * 导入一份文档。
     *
     * 同名（忽略大小写）视为**覆盖**：保留原 id，所以之前对话里指到这份文档的引用
     * 仍然指向同一份文档（内容变了，位置会重算）。用户改完文档重新导入时，
     * 期望的是"更新那份"，而不是"多出一份同名的"。
     *
     * @param name 显示名（取系统的 DISPLAY_NAME）。
     * @param bytes 原始字节（编码由这里探测）。
     * @return 落盘后的索引项。
     * @throws IllegalArgumentException 正文为空（全是空白 / 二进制乱码解不出字）。
     */
    suspend fun importDocument(name: String, bytes: ByteArray): KnowledgeDoc = withContext(Dispatchers.IO) {
        docsDir.mkdirs()
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        require(text.isNotBlank()) { "文档正文是空的，可能不是纯文本文件" }

        val docs = readIndex()
        val existing = docs.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val doc = KnowledgeDoc(
            id = existing?.id ?: UUID.randomUUID().toString().replace("-", "").take(16),
            name = name,
            charCount = text.length,
            addedAt = System.currentTimeMillis()
        )
        File(docsDir, "${doc.id}.txt").writeText(text, Charsets.UTF_8)
        writeIndex(listOf(doc) + docs.filter { it.id != doc.id })
        doc
    }

    /** 删除一份文档（索引项与正文一起删；正文文件不存在也算成功）。 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(docsDir, "$id.txt").delete()
        writeIndex(readIndex().filter { it.id != id })
    }

    /**
     * 全库切块（每次调用都重新读正文并重算，见类注释"切块是派生的"）。
     *
     * 单份文档读失败 / 切块抛异常时**跳过这一份**，不影响其它文档——
     * 知识库里一份坏文件不该让整个问答功能瘫痪。
     */
    suspend fun chunks(): List<KnowledgeChunk> = withContext(Dispatchers.IO) {
        val result = ArrayList<KnowledgeChunk>()
        for (doc in readIndex()) {
            val file = File(docsDir, "${doc.id}.txt")
            if (!file.exists()) continue
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            result += runCatching {
                KnowledgeChunker.chunk(doc.id, doc.name, text)
            }.getOrDefault(emptyList())
        }
        result
    }

    /** 读一份文档的全文（引用详情里定位/高亮用）。读不到返回空串。 */
    suspend fun fullText(docId: String): String = withContext(Dispatchers.IO) {
        runCatching { File(docsDir, "$docId.txt").readText(Charsets.UTF_8) }.getOrDefault("")
    }

    // ---- 索引读写 ----------------------------------------------------------

    private fun readIndex(): List<KnowledgeDoc> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(indexFile.readText(Charsets.UTF_8))
            val docs = ArrayList<KnowledgeDoc>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) continue
                docs += KnowledgeDoc(
                    id = id,
                    name = name,
                    charCount = item.optInt("chars"),
                    addedAt = item.optLong("addedAt")
                )
            }
            docs.sortedByDescending { it.addedAt }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(docs: List<KnowledgeDoc>) {
        root.mkdirs()
        val array = JSONArray()
        for (doc in docs) {
            array.put(
                JSONObject()
                    .put("id", doc.id)
                    .put("name", doc.name)
                    .put("chars", doc.charCount)
                    .put("addedAt", doc.addedAt)
            )
        }
        indexFile.writeText(array.toString(), Charsets.UTF_8)
    }

    // ---- 解码 --------------------------------------------------------------

    /**
     * 字节 → 字符串，带编码探测。
     *
     * 顺序：UTF-8 BOM → 按 UTF-8 解 → 若替换符占比过高再试 GB18030。
     *
     * 判据用「U+FFFD 的比例」而不是"解不出来就换"：UTF-8 解码器对非法字节**不抛异常**，
     * 只替换成 U+FFFD，所以只能靠比例判断。阈值取 1/200（0.5%）：
     * 正常 UTF-8 文档里几乎不会出现这个字符，而 GBK 文本按 UTF-8 解时占比通常远超 10%。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        val broken = utf8.count { it == '\uFFFD' }
        if (broken == 0 || broken * 200 < utf8.length) return utf8
        return runCatching { String(bytes, charset("GB18030")) }.getOrDefault(utf8)
    }
}
