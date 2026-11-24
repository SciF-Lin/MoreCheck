package com.morecheck.data.net

import android.content.Context
import android.util.Base64
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 歌词（「播放器页」用，2026-11-08 需求 C8）。
 *
 * ## 链路
 *
 * 酷狗有两条公开的歌词接口，都是 https，不需要登录态：
 *
 * 1. `krcs.kugou.com/search` —— 用 `hash` + `duration` 找到候选歌词的 `id` 与 `accesskey`；
 * 2. `lyrics.kugou.com/download` —— 用上面两个值换 **base64 编码的 LRC 文本**。
 *
 * 只有酷狗源自带 `hash`。网易源的条目要先拿「歌名 + 歌手」回酷狗搜一次换 hash
 * （实测：只给 `keyword` 参数搜英文歌会返回 0 条，必须给 hash，所以这一步不能省）。
 *
 * ## 失败就当作"没有歌词"
 *
 * 歌词是锦上添花，任何一步失败都返回空列表，界面显示"暂无歌词"。
 * 不要在播放器页弹错误——那会让"这首歌没歌词"看起来像功能坏了。
 *
 * ## 为什么要缓存（2026-11-08 需求 4）
 *
 * 原实现每次进播放器页都重新走两跳网络。用户反馈的现象是"这首歌刚才还有歌词，
 * 再放一次就变「暂无歌词」"——酷狗对同一 IP 的密集请求会返回空候选，于是同样的歌
 * 第二次反而取不到。现在按曲目 id 两级缓存：
 *
 * - **内存**（[memory]）：进程内再次播放同一首**同步命中**，连"加载中"都不会闪；
 * - **磁盘**（`filesDir/music_lyrics/<id>.lrc`）：杀进程、断网后照样有歌词。
 *
 * 只缓存**取到内容**的结果：空结果不缓存，否则一次网络抖动就会让这首歌永远没歌词。
 */
object MusicLyrics {

    /** 一行歌词。[timeMs] 是该行开始显示的时间。 */
    data class Line(val timeMs: Long, val text: String)

    private const val SEARCH_URL = "https://krcs.kugou.com/search"
    private const val DOWNLOAD_URL = "https://lyrics.kugou.com/download"

    /** 目录名固定，清理音乐缓存时不动它（歌词只有几 KB，且丢了就得重新联网）。 */
    private const val CACHE_DIR = "music_lyrics"

    /** 进程内缓存：曲目 id → 已解析的歌词行。 */
    private val memory = ConcurrentHashMap<String, List<Line>>()

    /** 只读缓存（不发起网络）。界面首帧用它，命中就不用显示"加载中"。 */
    fun peek(trackId: String): List<Line>? = memory[trackId]

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Referer" to "https://www.kugou.com/"
    )

    /**
     * 取一首歌的歌词。返回空列表表示"没取到/没版权/解析失败"，调用方据此显示占位。
     *
     * 顺序是**内存 → 磁盘 → 网络**：前两级命中都不发请求，所以"再放一次"必然有歌词。
     *
     * @param context 传了才读写磁盘缓存（界面上都有 `LocalContext`，测试里可以不传）。
     * @param hashForNetease 网易源条目用来换 hash 的酷狗搜索结果，
     *   由 [MusicRepository.searchKugou] 提供（同模块 internal，直接调）。
     */
    suspend fun load(track: MusicTrack, context: Context? = null): List<Line> {
        memory[track.id]?.let { return it }

        if (context != null) {
            val raw = withContext(Dispatchers.IO) { readCache(context, track.id) }
            if (!raw.isNullOrBlank()) {
                val parsed = parseLrc(raw)
                if (parsed.isNotEmpty()) {
                    memory[track.id] = parsed
                    return parsed
                }
            }
        }

        val net = fetch(track)
        if (net.isNotEmpty()) {
            memory[track.id] = net
            if (context != null) {
                withContext(Dispatchers.IO) { writeCache(context, track.id, net) }
            }
        }
        return net
    }

    /** 真正走网络的两跳：找候选 → 下载 LRC → 解析。失败一律空列表。 */
    private suspend fun fetch(track: MusicTrack): List<Line> = withContext(Dispatchers.IO) {
        val hash: String
        val durationSeconds: Long
        when (track.source) {
            MusicSource.KUGOU -> {
                hash = track.playKey
                durationSeconds = track.durationMs / 1000L
            }
            MusicSource.NETEASE -> {
                // 网易源没有酷狗 hash：拿「歌名 + 歌手」去酷狗搜第一条。
                val candidate = runCatching {
                    MusicRepository.searchKugou(
                        "${track.name} ${track.artist}".trim(),
                        1
                    ).firstOrNull()
                }.getOrNull() ?: return@withContext emptyList()
                hash = candidate.playKey
                durationSeconds = (candidate.durationMs.takeIf { it > 0L } ?: track.durationMs) / 1000L
            }
        }
        if (hash.isBlank()) return@withContext emptyList()

        val candidate = runCatching { findCandidate(hash, durationSeconds) }.getOrNull()
            ?: return@withContext emptyList()

        val lrc = runCatching { downloadLrc(candidate.first, candidate.second) }.getOrNull()
            ?: return@withContext emptyList()

        parseLrc(lrc)
    }

    // ---- 磁盘缓存 -------------------------------------------------------------
    //
    // 存的是**重新拼出来的 LRC 文本**，不是解析结果：行数据要序列化就得定格式，
    // 而 LRC 本身就是"人类可读 + 解析器已经测过"的格式，顺手还能直接看。

    private fun dirOf(context: Context): File =
        File(context.filesDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, trackId: String): File {
        // id 里有 `:` 等字符，文件名只留字母数字下划线，避免不同 ROM 上的非法文件名。
        val safe = trackId.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return File(dirOf(context), "$safe.lrc")
    }

    private fun readCache(context: Context, trackId: String): String? = runCatching {
        val file = fileFor(context, trackId)
        if (file.isFile && file.length() > 0L) file.readText() else null
    }.getOrNull()

    private fun writeCache(context: Context, trackId: String, lines: List<Line>) {
        runCatching {
            val text = lines.joinToString("\n") { line ->
                val totalMs = line.timeMs
                val minutes = totalMs / 60_000L
                val seconds = (totalMs % 60_000L) / 1000L
                val centis = (totalMs % 1000L) / 10L
                "[%02d:%02d.%02d]%s".format(minutes, seconds, centis, line.text)
            }
            fileFor(context, trackId).writeText(text)
        }
    }

    /** 返回 (id, accesskey)；找不到候选返回 null。 */
    private fun findCandidate(hash: String, durationSeconds: Long): Pair<String, String>? {
        val url = "$SEARCH_URL?ver=1&man=yes&client=mobi" +
            "&keyword=&duration=$durationSeconds&hash=$hash"
        val text = AppHttp.getText(url, headers)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val candidates = root.optJSONArray("candidates") ?: return null
        // 候选按接口给的顺序取第一条：它已经是"最匹配"的排序（官方推荐在前）。
        val first = candidates.optJSONObject(0) ?: return null
        val id = first.optString("id").takeIf { it.isNotBlank() } ?: return null
        val accessKey = first.optString("accesskey").takeIf { it.isNotBlank() } ?: return null
        return id to accessKey
    }

    private fun downloadLrc(id: String, accessKey: String): String {
        val url = "$DOWNLOAD_URL?ver=1&client=pc&id=$id" +
            "&accesskey=${URLEncoder.encode(accessKey, "UTF-8")}&fmt=lrc&charset=utf8"
        val text = AppHttp.getText(url, headers)
        val root = JSONObject(text)
        val content = root.optString("content")
        if (content.isBlank()) error("歌词内容为空")
        return String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
    }

    /**
     * 解析 LRC。只保留"有时间戳且有文字"的行，元信息标签（`[ti:]` `[ar:]` 等）丢弃。
     *
     * 一行可能带多个时间戳（`[00:01.00][00:30.00]副歌`），每个都生成一行——
     * 这样副歌重复出现时也会跟着滚动。
     *
     * 时间戳格式兼容 `[mm:ss]`、`[mm:ss.xx]`、`[mm:ss.xxx]`；
     * 解析失败的行直接跳过，不抛异常（歌词文件是外部数据，不能信）。
     *
     * 2026-11-08 补：酷狗 LRC 的开头常有几行**带时间戳的元信息**
     * （`[00:00.00]歌手 - 歌名`、`[00:03.00]编曲：某某`、`[00:05.00]制作人：某某`）。
     * 它们不是歌词，却会被当成第 0、1 行——歌名下方那两行预览就变成制作名单。
     * 中文制作名单一律过滤（这些词几乎不会出现在歌词正文），英文元信息与
     * 「歌手 - 歌名」标题行只在开头几秒内过滤，避免误伤真正 0 秒起唱的歌词。
     */
    internal fun parseLrc(raw: String): List<Line> {
        val result = ArrayList<Line>()
        val tag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        raw.lineSequence().forEach { line ->
            val matches = tag.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
                val seconds = match.groupValues[2].toLongOrNull() ?: return@forEach
                val fractionRaw = match.groupValues[3]
                // `.5` 是 500ms，`.50` 也是 500ms，`.500` 才是 500ms —— 按位数补零。
                val fraction = when (fractionRaw.length) {
                    0 -> 0L
                    1 -> fractionRaw.toLongOrNull().orZero() * 100L
                    2 -> fractionRaw.toLongOrNull().orZero() * 10L
                    else -> fractionRaw.take(3).toLongOrNull().orZero()
                }
                val timeMs = (minutes * 60L + seconds) * 1000L + fraction
                if (!looksLikeMetadata(text, timeMs)) result += Line(timeMs, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    /** 中文制作名单：这些词几乎不会出现在歌词正文里，**不限时间戳**都过滤。 */
    private val CN_METADATA_WORDS = listOf(
        "作词", "作曲", "编曲", "演唱", "原唱", "制作人", "混音",
        "词:", "曲:", "词：", "曲："
    )

    /** 英文元信息：可能撞上英文歌词正文（"lyrics"），所以只在开头 3 秒内生效。 */
    private val EN_METADATA_WORDS = listOf("lyrics by", "music by", "written by")

    private fun looksLikeMetadata(text: String, timeMs: Long): Boolean {
        if (CN_METADATA_WORDS.any { text.contains(it, ignoreCase = true) }) return true
        if (timeMs <= 3_000L &&
            EN_METADATA_WORDS.any { text.contains(it, ignoreCase = true) }
        ) return true
        // 「歌手 - 歌名」标题行：短、含半角连字符、且出现在开头 1.5 秒内。
        return timeMs <= 1_500L && text.contains(" - ") && text.length < 40
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
