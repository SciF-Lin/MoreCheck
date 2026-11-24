package com.morecheck.util

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 缓存统计与清理（「设置 → 清除缓存」，v1.1.79 需求 1）。
 *
 * ## 这里说的"缓存"到底包含什么
 *
 * 只清**可以随时重建、删了不丢用户数据**的东西：
 *
 * - `cacheDir`（应用私有缓存目录：图片临时文件、WebView 的 HTTP 缓存、
 *   相机 / 裁剪产生的中间文件都落在这里）；
 * - `externalCacheDir`（同上，外部存储里的那一半；设备没挂载时为空）；
 * - WebView 的**内存 + 磁盘缓存**（`WebView.clearCache(true)`）——
 *   网页游戏与"官网"的页面资源缓存，删了只是下次重新加载；
 * - `codeCacheDir`（ART 的 JIT 缓存）：删掉不影响功能，系统会自己重建。
 *
 * ⚠️ **绝不碰**：
 * - `filesDir` / `databases` / `shared_prefs`（DataStore 也在这里）——
 *   里面是打卡记录、任务、设置、对话记录、课表进度，属于**用户数据**，
 *   由「数据备份 → 清空全部数据」那一套负责，与"清缓存"是两个不同的语义；
 * - 记事本保存到相册的图片（那在系统相册里，根本不属于本应用缓存目录）。
 *
 * ## 音乐缓存为什么单独一类（2026-11-08）
 *
 * 「音乐」下载的歌曲放在 `filesDir/music_cache`，**故意不在这里的统计范围内**：
 * 它是用户主动下载、用于离线播放的内容，与"网页缓存可以随时重建"不是一回事。
 * 两类分开统计、分开清理（[musicBytes] / [clearMusic]），界面上由用户自己选清哪一类。
 * 混在一起清会出现"我只想清网页缓存，结果下的歌全没了"。
 *
 * ## 为什么 WebView 清理要单独一步
 *
 * `WebView` 的磁盘缓存在 `cacheDir` 下的子目录里，但它自己维护着索引；
 * 只删文件不调 `clearCache` 会让索引与磁盘不一致（表现为"缓存明明删了，页面还是旧的"）。
 * 因此在主线程创建一个临时 WebView 调一次 `clearCache(true)` 再销毁——
 * 这是官方唯一无副作用的清理方式（不需要加载任何页面）。
 */
object CacheCleaner {

    /**
     * 统计缓存占用的总字节数（不阻塞：目录都很小，递归读取即可）。
     *
     * 统计口径与 [clearAll] **严格一致**：两个函数遍历同一组目录，
     * 否则会出现"显示 12 MB、清理完还剩 3 MB"这种让人以为没清干净的现象。
     * （WebView 的独立缓存无法纳入统计，见 [clearAll] 的说明。）
     */
    fun totalBytes(context: Context): Long {
        var total = 0L
        cacheDirsOf(context).forEach { dir ->
            total += runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
                .getOrDefault(0L)
        }
        return total
    }

    /**
     * 清理**除音乐缓存之外**的全部缓存，返回**实际释放**的字节数。
     * **挂起函数**：内部自己切线程。
     *
     * 名字仍叫 `clearAll` 是历史原因（音乐缓存是后来加的，见文件头）；
     * 音乐缓存走 [clearMusic]。
     *
     * 返回值用「清理前 - 清理后」两次统计的差值算，而不是把删除的文件大小累加：
     * 后者在遇到删除失败（文件被占用）时会虚报，前者是用户真正能看到的变化。
     *
     * ## 线程约束（实测必须）
     *
     * `WebView` 只能在**有 Looper 的线程**（= 主线程）创建，因此在主线程建一个临时
     * WebView 调 `clearCache(true)` 后立刻销毁；目录遍历与删除放到 IO。
     * 如果整体丢进 IO，会抛 `RuntimeException: Can't create handler inside thread
     * that has not called Looper.prepare()`。
     */
    suspend fun clearAll(context: Context): Long {
        val before = withContext(Dispatchers.IO) { totalBytes(context) }

        // 先让 WebView 自己清（它会顺手把索引一起清掉），再删目录里的残留文件。
        withContext(Dispatchers.Main) {
            runCatching {
                WebView(context).apply {
                    clearCache(true)
                    destroy()
                }
            }
        }

        withContext(Dispatchers.IO) {
            cacheDirsOf(context).forEach { dir ->
                runCatching {
                    dir.listFiles()?.forEach { child ->
                        runCatching { child.deleteRecursively() }
                    }
                }
            }
        }

        val after = withContext(Dispatchers.IO) { totalBytes(context) }
        return (before - after).coerceAtLeast(0L)
    }

    // ---- 音乐缓存（单独一类，不计入上面的 totalBytes / clearAll）---------------

    /** 音乐缓存占用（下载下来用于离线播放的音频文件）。 */
    fun musicBytes(context: Context): Long =
        runCatching {
            com.morecheck.data.music.MusicCache.dirOf(context).walkBottomUp()
                .filter { it.isFile }
                .sumOf { it.length() }
        }.getOrDefault(0L)

    /**
     * 清空音乐缓存，返回实际释放的字节数。
     *
     * 会一并清掉"已缓存"标记（`MusicCache` 自己维护的 StateFlow 会重新扫描），
     * 之后这些歌需要联网才能播——这正是用户点这个按钮时的预期。
     */
    suspend fun clearMusic(context: Context): Long =
        com.morecheck.data.music.MusicCache(context).clear()

    /** 需要参与统计 / 清理的目录清单（顺序无关）。 */
    private fun cacheDirsOf(context: Context): List<File> = buildList {
        context.cacheDir?.let(::add)
        context.externalCacheDir?.let(::add)
        context.codeCacheDir?.let(::add)
    }.filter { it.exists() }

    /**
     * 把字节数格式化成「12.3 MB」这类给用户看的文案。
     *
     * 不足 1 MB 显式显示 KB（而不是显示 "0.0 MB"——那会让人以为统计坏了）；
     * 完全为空显示「0 B」。
     */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1) return "$bytes B"
        if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
        return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
    }
}
