package com.morecheck.data.net

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 赞助片段跳过（SponsorBlock 的 B 站镜像 bsbsb.top，2026-11-18 新增）。
 *
 * 协议与参考实现 Cheers 的 `com.lihuan.bili.net.Sponsor` 一致：
 * - 端点走 **SHA-256 哈希前缀**：`GET /api/skipSegments/<sha256(bvid) 前 4 位十六进制>`，
 *   返回所有命中该前缀的视频，客户端再按 `videoID` 精确过滤（不向服务端明文暴露 bvid）；
 * - 每条 segment 带 `category` / `actionType` / `segment:[起始秒, 结束秒]`，
 *   镜像站还会带 `cid`（分 P 定位）；只取 `actionType == "skip"`；
 * - 结果按起始时间排序，进程内按 `bvid#cid` 缓存（含空结果，避免反复请求）。
 *
 * ⚠️ 凭据口径：bsbsb.top 是**第三方站点**，请求只带 UA，
 * 绝不带 B 站 Cookie / Referer（[BilibiliRepository.biliHeaders] 不能用在这里）。
 *
 * ⚠️ 隐私政策尚未把该域名列入联网面清单——用户已确认「先实现、后补政策」，
 * 补政策前不要发版。
 */
object BiliSponsorBlock {

    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    private const val BASE = "https://www.bsbsb.top/api/skipSegments/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    private val cache = mutableMapOf<String, List<Segment>>()

    /** 取该视频（指定分 P）的可跳过片段；任何失败都返回空列表，不阻断播放。 */
    suspend fun segments(bvid: String, cid: Long): List<Segment> = withContext(Dispatchers.IO) {
        val key = "$bvid#$cid"
        cache[key]?.let { return@withContext it }
        val result = runCatching {
            val prefix = sha256Hex(bvid.trim()).substring(0, 4)
            val array = JSONArray(AppHttp.getText(BASE + prefix, mapOf("User-Agent" to UA)))
            val list = mutableListOf<Segment>()
            for (i in 0 until array.length()) {
                val video = array.optJSONObject(i) ?: continue
                if (video.optString("videoID") != bvid) continue
                val segs = video.optJSONArray("segments") ?: continue
                for (j in 0 until segs.length()) {
                    val s = segs.optJSONObject(j) ?: continue
                    // 镜像站按分 P 标注 cid；个别老条目没有 cid 字段时视为整稿通用。
                    val segCid = s.optString("cid")
                    if (segCid.isNotEmpty() && segCid != cid.toString()) continue
                    if (s.optString("actionType", "skip") != "skip") continue
                    val category = s.optString("category")
                    val range = s.optJSONArray("segment") ?: continue
                    if (category.isEmpty() || range.length() < 2) continue
                    val start = (range.optDouble(0, -1.0) * 1000).toLong()
                    val end = (range.optDouble(1, -1.0) * 1000).toLong()
                    if (start >= 0 && end > start) list += Segment(start, end, category)
                }
            }
            list.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
        cache[key] = result
        result
    }

    /** 当前进度落在哪个片段里（没有则 null）。 */
    fun hit(segments: List<Segment>, positionMs: Long): Segment? =
        segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    /** category → 提示文案（与参考实现的分类口径一致）。 */
    fun categoryName(category: String): String = when (category) {
        "sponsor" -> "赞助"
        "selfpromo" -> "自我推广"
        "interaction" -> "互动提醒"
        "intro" -> "片头"
        "outro" -> "片尾"
        "preview" -> "预览"
        "music_offtopic" -> "音乐离题"
        else -> "其它片段"
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}