package com.morecheck.data.music

import android.content.Context
import com.morecheck.data.net.AppHttp
import com.morecheck.data.net.MusicTrack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 「音乐 → 缓存」：把一首歌的音频**文件**存到本机，之后没有网络也能播。
 *
 * ## 为什么不放在 `cacheDir`
 *
 * `cacheDir` 是系统认为"可以随时删"的目录（存储紧张时 Android 会自己清），
 * 而用户的预期是"我下载过的歌，没网也能听"。所以放在 `filesDir/music_cache`：
 * 属于应用私有数据，系统不会自动清，只有用户点「清理音乐缓存」才消失。
 *
 * ## 与「清除缓存」的关系（2026-11-08 需求）
 *
 * 原来的 `CacheCleaner` 只清 `cacheDir` / `codeCacheDir` / WebView 缓存。
 * 音乐缓存**故意不在**那三个目录里，因此不会被"清除缓存"误删；
 * `CacheCleaner` 单独统计它、单独清，界面上分成两类让用户自己选。
 *
 * ## 文件布局
 *
 * ```
 * filesDir/music_cache/
 *   kg_<hash>.audio     ← 音频本体（扩展名统一 .audio，不猜容器格式）
 *   kg_<hash>.json      ← 这一条的元信息（歌名 / 歌手 / 专辑 / 封面 / 时长）
 * ```
 *
 * 用「id + 后缀」而不是原名：`id` 已经带源前缀（`kg:` / `ne:`），本身唯一；
 * 原名里可能有 `/`、空格、emoji，直接当文件名在不同 ROM 上会出问题。
 *
 * ## 什么情况下会缺歌
 *
 * 缓存只存"当时能取到直链"的那一版。版权/会员变化、CDN 链接失效都不影响已有文件
 * （文件是完整的 mp3）。但缓存不会自动刷新：要更新就删掉重新缓存。
 */
class MusicCache(private val context: Context) {

    private val dir: File
        get() = dirOf(context)

    private val _cachedIds = MutableStateFlow<Set<String>>(emptySet())

    /** 已缓存歌曲的 id 集合（与 [MusicTrack.id] 同口径），界面用它显示下载状态。 */
    val cachedIds: StateFlow<Set<String>> = _cachedIds.asStateFlow()

    init {
        // 构造时扫一次目录：界面首帧就能显示"哪些已缓存"，不用等异步结果。
        _cachedIds.value = scanIds()
    }

    /** 某一首歌的缓存文件；不存在返回 null。 */
    fun fileFor(track: MusicTrack): File? =
        audioFile(track).takeIf { it.isFile && it.length() > 0L }

    fun isCached(track: MusicTrack): Boolean = fileFor(track) != null

    /**
     * 下载并落盘，返回写入的字节数。
     *
     * **挂起函数**：网络与磁盘都在 IO 线程。失败会抛异常（由调用方决定怎么提示），
     * 已经写了一半的临时文件会被删掉——不留"半个文件"让下次误判成已缓存。
     */
    suspend fun download(track: MusicTrack, url: String): Long = withContext(Dispatchers.IO) {
        val target = audioFile(track)
        val temp = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kugou.com/")
            .build()

        try {
            AppHttp.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: error("响应为空")
                temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            check(temp.length() > 0L) { "下载内容为空" }

            // 先写元信息再改名：反过来的话，进程在两步之间被杀会留下"有音频没元信息"的孤儿文件，
            // 列表里就查不到它了。现在这个顺序最坏情况是"有元信息没音频"，下次会被判为未缓存并重下。
            metaFile(track).writeText(track.toJson().toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            _cachedIds.value = scanIds()
            target.length()
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    /** 删掉一首歌的缓存（音频 + 元信息）。 */
    suspend fun remove(track: MusicTrack): Long = withContext(Dispatchers.IO) {
        val size = audioFile(track).length()
        runCatching { audioFile(track).delete() }
        runCatching { metaFile(track).delete() }
        _cachedIds.value = scanIds()
        size
    }

    /** 清空全部音乐缓存，返回释放的字节数（清理前 - 清理后，与 `CacheCleaner` 同口径）。 */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val before = totalBytes()
        runCatching {
            dir.listFiles()?.forEach { child -> runCatching { child.deleteRecursively() } }
        }
        _cachedIds.value = scanIds()
        (before - totalBytes()).coerceAtLeast(0L)
    }

    /** 缓存目录当前占用的字节数。 */
    fun totalBytes(): Long = runCatching {
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    /** 已缓存的全部歌曲（按缓存时间倒序：最近下的在最前）。 */
    fun cachedTracks(): List<MusicTrack> {
        val metas = runCatching {
            dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX_META) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }.getOrDefault(emptyList())

        return metas.mapNotNull { file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return@mapNotNull null
            val track = MusicTrack.fromJson(json) ?: return@mapNotNull null
            // 元信息在但音频被系统/用户删了：不算已缓存，直接跳过。
            track.takeIf { audioFile(it).isFile }
        }
    }

    private fun audioFile(track: MusicTrack): File = File(dir, safeName(track.id) + SUFFIX_AUDIO)

    private fun metaFile(track: MusicTrack): File = File(dir, safeName(track.id) + SUFFIX_META)

    private fun scanIds(): Set<String> {
        val tracks = cachedTracks()
        return tracks.map { it.id }.toSet()
    }

    /** id → 安全文件名：只留字母数字与少数几个符号，其余一律下划线。 */
    private fun safeName(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120)

    companion object {
        private const val SUFFIX_AUDIO = ".audio"
        private const val SUFFIX_META = ".json"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        /** 缓存根目录（`CacheCleaner` 也要用它做分类统计，所以暴露出来）。 */
        fun dirOf(context: Context): File = File(context.filesDir, "music_cache")
    }
}
