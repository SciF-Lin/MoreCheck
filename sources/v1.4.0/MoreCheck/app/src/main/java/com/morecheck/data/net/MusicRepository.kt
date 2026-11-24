package com.morecheck.data.net

import com.morecheck.data.music.KugouAccount
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 音源。同一个搜索词在不同平台能搜到的东西不一样，能不能播也不一样，
 * 因此"这一条来自哪里"必须跟着条目走，不能到播放时再猜。
 */
enum class MusicSource(val label: String) {
    /** 酷狗（搜索用 mobilecdn 公开接口，播放用 m.kugou.com 的 playInfo）。 */
    KUGOU("酷狗"),

    /** 网易云（搜索用公开 Web 接口，播放用 song/media/outer 外链）。 */
    NETEASE("网易云")
}

/**
 * 一首「可播放的搜索结果」。
 *
 * [playKey] 是该音源内部的主键：酷狗是音频 `hash`，网易是歌曲 `id`。
 * 播放时用它换直链——不同音源的取链方式完全不同，所以不能只存一个数字 id。
 */
data class MusicTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
    val source: MusicSource,
    val playKey: String,
    /**
     * 酷狗的专辑音频 id（搜索返回的 `album_audio_id`）。
     *
     * 登录态取链时**必须**带上它：`/v1/authorization` 用它定位"这首录音",
     * 只给 hash 而不给 album_audio_id 时，很多会员曲目会拿不到授权。
     * 网易源与旧收藏里没有这个值，默认 0（此时取链会退回公开接口）。
     */
    val albumAudioId: Long = 0L
) {
    /** 收藏清单里存的一行 JSON（字段名与 [MusicFavorites] 的解析一一对应）。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("artist", artist)
        put("album", album)
        put("cover", coverUrl ?: JSONObject.NULL)
        put("duration", durationMs)
        put("source", source.name)
        put("playKey", playKey)
        put("albumAudioId", albumAudioId)
    }

    companion object {
        fun fromJson(json: JSONObject): MusicTrack? {
            val id = json.optString("id", "")
            if (id.isBlank()) return null
            // 老版本收藏里没有 source / playKey：那时只有网易一条源，按网易兜底并
            // 用 id 当 playKey，保证升级后旧收藏还能播。
            val source = runCatching {
                MusicSource.valueOf(json.optString("source", "NETEASE"))
            }.getOrDefault(MusicSource.NETEASE)
            return MusicTrack(
                id = id,
                name = json.optString("name", "").ifBlank { "未知歌曲" },
                artist = json.optString("artist", ""),
                album = json.optString("album", ""),
                coverUrl = json.optString("cover", "").ifBlank { null },
                durationMs = json.optLong("duration", 0L),
                source = source,
                playKey = json.optString("playKey", "").ifBlank { id },
                albumAudioId = json.optLong("albumAudioId", 0L)
            )
        }
    }
}

/**
 * 在线音乐的**唯一数据源**：搜索 + 取播放地址。
 *
 * ## 为什么从"单源"改成"双源 + 交叉兜底"（2026-11-06）
 *
 * 之前只用网易的 `song/media/outer/url`。它有个硬伤：大量热门歌是会员曲目，
 * 那条外链直接 403/空响应，界面只能提示"无法播放"。
 *
 * 参考 GitHub 上的 EchoMusic（酷狗概念版客户端，`hoowhoami/EchoMusic`）的做法后
 * 改成两条源：
 * 1. **搜索优先酷狗**：`mobilecdn.kugou.com` 的公开搜索接口，中文曲库覆盖更广，
 *    返回里直接带 `hash`（后续取链要用的主键）；
 * 2. **播放逐条取链**：酷狗 `m.kugou.com/app/i/getSongInfo.php?cmd=playInfo` 对
 *    **免费曲目**会给直链；会员曲目返回空 `url`，此时**自动回落到网易**同名结果
 *    的外链——两家免费/会员的边界不一样，交叉之后能播的比例明显高于单源。
 *
 * ## 与 EchoMusic 的差别（重要，别照抄结论）
 *
 * EchoMusic 能"听绝大多数"是因为它用**登录后的账号授权**去换地址
 * （`user_verify` → `authorization` → `tracker/v5/url`，见 [KugouAuthedApi]）。
 *
 * 2026-11-08 起本项目也支持这条路，但**凭据是用户自己的**：用户在「音乐」页扫码登录
 * 自己的酷狗账号，token 只存本机、只用于给自己换播放地址，不做任何转售或共享
 * （`data/music/KugouAccount.kt`）。**不登录时**行为与之前完全一样：
 * 免费曲目能播，会员曲目如实提示"需要会员"。
 *
 * ## 已知限制
 *
 * 1. 未登录时会员/独家曲目两家都不给直链；登录后能听的范围由**该账号自己的会员权益**决定，
 *    非会员账号登录也不会凭空多出权限；
 * 2. 酷狗的搜索与取链接口只有 http（https 证书与主机名对不上，OkHttp 会直接拒），
 *    已在 `res/xml/network_security_config.xml` 里**只对这几个域名**放行明文；
 * 3. 都是非官方接口，字段可能变；这里全部容错解析，失败只影响本页。
 */
object MusicRepository {

    private const val KUGOU_SEARCH = "http://mobilecdn.kugou.com/api/v3/search/song"
    private const val KUGOU_PLAY_INFO = "http://m.kugou.com/app/i/getSongInfo.php"
    private const val NETEASE_SEARCH = "https://music.163.com/api/search/get/web"
    private const val NETEASE_OUTER = "https://music.163.com/song/media/outer/url"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val kugouHeaders = mapOf(
        "Referer" to "http://m.kugou.com/",
        "User-Agent" to DESKTOP_UA
    )

    private val neteaseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "User-Agent" to DESKTOP_UA
    )

    /**
     * 搜索：酷狗为主、网易补位（2026-11-08 改为"中英文都能搜"）。
     *
     * ## 中文查询
     *
     * 酷狗中文曲库覆盖更好，结果非空就直接用；空了才换网易。
     *
     * ## 英文查询（纯 ASCII）
     *
     * 两家的英文库各有缺口：酷狗的原版/翻唱混杂，网易的欧美曲库更全但缺一些小众。
     * 所以英文查询**两边都查、合并去重**，并且门槛更低（酷狗结果少于
     * [EN_MERGE_THRESHOLD] 条就补网易），否则会出现"搜 Shape of You 只有翻唱"。
     *
     * ## 为什么不做"全量合并"
     *
     * 中文查询全量合并会让同一首歌出现两行（两家的时长与封面还差几秒）。
     * 只在英文查询时合并，且按"歌名 + 歌手互相包含"判重，是收益与噪音的折中。
     */
    suspend fun search(keyword: String, limit: Int = 30): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val query = keyword.trim().replace(Regex("\\s+"), " ")
            if (query.isEmpty()) return@withContext emptyList()
            val fromKugou = runCatching { searchKugou(query, limit) }.getOrDefault(emptyList())
            val asciiOnly = query.all { it.code < 128 }
            if (!asciiOnly) {
                return@withContext fromKugou.ifEmpty {
                    runCatching { searchNetease(query, limit) }.getOrDefault(emptyList())
                }
            }
            if (fromKugou.size >= EN_MERGE_THRESHOLD) return@withContext fromKugou
            val fromNetease = runCatching { searchNetease(query, limit) }.getOrDefault(emptyList())
            mergeDistinct(fromKugou, fromNetease, limit)
        }

    /**
     * 合并两份结果并去重（英文查询专用）。
     *
     * 判重规则：歌名相同（忽略大小写）且一方歌手名包含另一方——
     * 两家的歌手字段格式不一样（`A/B`、`A、B`、`A & B`），严格相等会漏判。
     */
    private fun mergeDistinct(
        primary: List<MusicTrack>,
        extra: List<MusicTrack>,
        limit: Int
    ): List<MusicTrack> {
        val out = primary.toMutableList()
        for (candidate in extra) {
            if (out.size >= limit) break
            val duplicated = out.any { existing ->
                existing.name.equals(candidate.name.trim(), ignoreCase = true) &&
                    (
                        existing.artist.contains(candidate.artist.trim(), ignoreCase = true) ||
                            candidate.artist.contains(existing.artist.trim(), ignoreCase = true)
                        )
            }
            if (!duplicated) out += candidate
        }
        return out
    }

    /** 英文查询时"够用"的条数门槛；低于它就补一份网易结果。 */
    private const val EN_MERGE_THRESHOLD = 8

    /**
     * 取播放地址。返回可直接喂给播放器的 URL。
     *
     * 顺序（酷狗源）：
     * 1. **已登录** → [KugouAuthedApi.playUrl]（带账号授权的正路，能拿会员曲目）；
     * 2. 公开接口 `playInfo`（免费曲目）；
     * 3. 换网易按「歌名 + 歌手」再搜一次。
     *
     * 三条都拿不到才返回 null，由界面提示"需要会员或暂无版权"。
     * 之所以把登录态放在最前而不是"只在公开接口失败时才用"：公开接口对会员曲目
     * 返回的是**空 url 而不是错误**，先试公开的等于每首歌都白跑一次失败。
     *
     * @param account 当前登录的酷狗账号；未登录传 null（行为不变）。
     */
    suspend fun resolvePlayUrl(
        track: MusicTrack,
        account: KugouAccount? = null
    ): String? = withContext(Dispatchers.IO) {
        when (track.source) {
            MusicSource.KUGOU -> {
                val authed = account?.let { acct ->
                    runCatching {
                        KugouAuthedApi.playUrl(track.playKey, track.albumAudioId, acct)
                    }.getOrNull()
                }
                authed ?: kugouPlayUrl(track.playKey) ?: fallbackViaNetease(track)
            }
            MusicSource.NETEASE -> neteasePlayUrl(track.playKey) ?: fallbackViaKugou(track, account)
        }
    }

    /**
     * 把 http 地址升到 https（音频直链、封面、头像都走它）。
     *
     * 本应用默认禁止明文流量，只有 `mobilecdn.kugou.com` / `m.kugou.com` 等
     * **接口域名**单独放行。酷狗返回的音频 CDN 与图片 CDN 都是 `http://`，
     * 实测同路径换 https 返回同样的内容，所以统一升级，
     * **不为它们再加明文白名单**（能不开的口子就不开）。
     */
    internal fun forceHttps(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://")) {
            "https://" + trimmed.removePrefix("http://")
        } else {
            trimmed
        }
    }

    // ---- 酷狗 ----------------------------------------------------------------

    /** 酷狗搜索。`hash` 是后续取链的主键；封面里的 `{size}` 是尺寸占位符。 */
    internal fun searchKugou(keyword: String, limit: Int): List<MusicTrack> {
        val url = "$KUGOU_SEARCH?format=json&keyword=${encode(keyword)}" +
            "&page=1&pagesize=${limit.coerceIn(1, 100)}&showtype=1"
        val text = AppHttp.getText(url, kugouHeaders)
        return parseKugouSearch(text)
    }

    /** 酷狗取链：`url` 非空即可播；会员曲目这里返回空串（不是异常）。 */
    internal fun kugouPlayUrl(hash: String): String? {
        if (hash.isBlank()) return null
        val text = runCatching {
            AppHttp.getText("$KUGOU_PLAY_INFO?cmd=playInfo&hash=$hash", kugouHeaders)
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return forceHttps(json.optString("url").takeIf { it.startsWith("http") })
    }

    private fun fallbackViaNetease(track: MusicTrack): String? {
        val candidates = runCatching {
            searchNetease("${track.name} ${track.artist}".trim(), 5)
        }.getOrDefault(emptyList())
        for (candidate in candidates) {
            val url = neteasePlayUrl(candidate.playKey)
            if (url != null) return url
        }
        return null
    }

    private suspend fun fallbackViaKugou(track: MusicTrack, account: KugouAccount? = null): String? {
        val candidates = runCatching {
            searchKugou("${track.name} ${track.artist}".trim(), 5)
        }.getOrDefault(emptyList())
        for (candidate in candidates) {
            val url = account?.let { acct ->
                runCatching {
                    KugouAuthedApi.playUrl(candidate.playKey, candidate.albumAudioId, acct)
                }.getOrNull()
            } ?: kugouPlayUrl(candidate.playKey)
            if (url != null) return url
        }
        return null
    }

    // ---- 网易云 --------------------------------------------------------------

    internal fun searchNetease(keyword: String, limit: Int): List<MusicTrack> {
        val url = "$NETEASE_SEARCH?s=${encode(keyword)}" +
            "&type=1&offset=0&limit=${limit.coerceIn(1, 100)}"
        val text = AppHttp.getText(url, neteaseHeaders)
        return parseNeteaseSearch(text)
    }

    /**
     * 网易外链：本身就是"地址的地址"，播放器请求时由服务端 302 到 CDN。
     *
     * ⚠️ 这里无法提前判断"能不能播"：会员曲目可能返回空壳页面而不是 4xx，
     * 真正的失败发生在播放器侧。所以失败后要允许用户换一首，而不是卡在这里。
     */
    internal fun neteasePlayUrl(id: String): String? {
        if (id.isBlank()) return null
        return "$NETEASE_OUTER?id=$id.mp3"
    }

    // ---- 解析（抽成 internal 便于纯 JVM 单测）--------------------------------

    /**
     * URL 查询参数编码。
     *
     * `URLEncoder.encode` 会把空格编成 `+`，那是 **form 表单**的口径；
     * 放在 query string 里两家接口都按字面量处理（部分服务端直接搜不到结果）。
     * 统一换成 `%20`，中文与英文都走这一条。
     */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    internal fun parseKugouSearch(json: String): List<MusicTrack> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        val info = data.optJSONArray("info") ?: return emptyList()
        val list = ArrayList<MusicTrack>(info.length())
        for (i in 0 until info.length()) {
            val song = info.optJSONObject(i) ?: continue
            val hash = song.optString("hash").lowercase()
            if (hash.isBlank()) continue
            val name = song.optString("songname")
            if (name.isBlank()) continue
            // 时长单位是秒
            list += MusicTrack(
                id = "kg:$hash",
                name = name,
                artist = song.optString("singername").ifBlank { "未知歌手" },
                album = song.optString("album_name"),
                coverUrl = kugouCover(song),
                durationMs = song.optLong("duration", 0L) * 1000L,
                source = MusicSource.KUGOU,
                playKey = hash,
                // 登录态取链要用它定位"本次录音"（见 MusicTrack.albumAudioId 的说明）。
                albumAudioId = song.optLong("album_audio_id", 0L)
            )
        }
        return list
    }

    /**
     * 酷狗的封面地址（实测**三个地方**都可能给，优先级从上到下）：
     *
     * 1. `imgurl` —— 老接口字段，很多新歌这里是 null；
     * 2. `album_img` —— 同上；
     * 3. `trans_param.union_cover` —— **实际最常命中的那个**，且带 `{size}` 占位符。
     *    注意 `trans_param` 在某些返回里是对象、在某些里是**字符串**，两种都要认。
     *
     * 不取的话表现就是"列表里所有封面都是音符占位"（2026-11-08 用户反馈）。
     * 拿到后统一替换尺寸并升 https（http 会被明文策略拦住，同样是灰块）。
     */
    private fun kugouCover(song: JSONObject): String? {
        val direct = song.optString("imgurl").ifBlank { song.optString("album_img") }
        val fromTrans = runCatching {
            val raw = song.opt("trans_param")
            val obj = when (raw) {
                is JSONObject -> raw
                is String -> JSONObject(raw)
                else -> null
            }
            obj?.optString("union_cover").orEmpty()
        }.getOrDefault("")
        val cover = direct.ifBlank { fromTrans }.trim()
        if (cover.isEmpty()) return null
        return forceHttps(cover.replace("{size}", "480"))
    }

    internal fun parseNeteaseSearch(json: String): List<MusicTrack> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val result = root.optJSONObject("result") ?: return emptyList()
        val songs = result.optJSONArray("songs") ?: return emptyList()
        val list = ArrayList<MusicTrack>(songs.length())
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            if (id <= 0L) continue
            val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar")
            val artistNames = buildString {
                if (artists != null) {
                    for (j in 0 until artists.length()) {
                        val name = artists.optJSONObject(j)?.optString("name").orEmpty()
                        if (name.isBlank()) continue
                        if (isNotEmpty()) append(" / ")
                        append(name)
                    }
                }
            }
            val albumObject = song.optJSONObject("album") ?: song.optJSONObject("al")
            list += MusicTrack(
                id = "ne:$id",
                name = song.optString("name", "").ifBlank { "未知歌曲" },
                artist = artistNames.ifBlank { "未知歌手" },
                album = albumObject?.optString("name").orEmpty(),
                coverUrl = forceHttps(albumObject?.optString("picUrl")),
                durationMs = song.optLong("duration", 0L),
                source = MusicSource.NETEASE,
                playKey = id.toString()
            )
        }
        return list
    }
}
