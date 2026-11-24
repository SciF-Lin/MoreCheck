package com.morecheck.data.cloud

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 安装包所属渠道。
 *
 * 下载页按它分「正式版 / 测试版」两栏（2026-11-01 需求）。判定只看版本名里的
 * 预发布标记（见 [ReleaseApkRepository.BETA_MARKERS]），不额外依赖清单里的字段——
 * 清单是老脚本写的、字段可能缺，而文件名一定带着 `beta`。
 */
enum class ReleaseChannel {
    /** 正式版：版本名里没有预发布标记。 */
    STABLE,

    /** 测试版：`beta` / `alpha` / `rc` / `snapshot`。 */
    BETA;

    companion object {
        /**
         * 从版本名（或文件名）判定渠道。
         *
         * 逐段判断用的是 `contains` 而不是前缀：本项目历史上出现过
         * `1.2.0 beta` 与 `1.2.1-beta` 两种写法，统一按"出现过就算"处理。
         */
        fun of(version: String): ReleaseChannel =
            if (ReleaseApkRepository.BETA_MARKERS.any { version.contains(it, ignoreCase = true) }) {
                BETA
            } else {
                STABLE
            }
    }
}

/**
 * 云端托管的一个安装包（正式版或测试版）。
 *
 * 字段全部来自 `manifest.json`（由 `tools/upload_release_apk.py` 在发布时生成），
 * 服务端不提供任何元数据接口，所以清单文件就是唯一事实源。
 */
data class ReleaseApk(
    /**
     * 语义版本号，如 `1.2.0`。
     *
     * 测试版的写法历史上不统一（`1.2.0 beta` / `1.2.1-beta`），这里**原样保留**，
     * 由 [channel] 承担"是不是测试版"的判断，展示时再拼「测试版」角标。
     */
    val version: String,
    /** 所属渠道，见 [ReleaseChannel]。 */
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    /** versionCode；清单里缺失时按 0 处理，排序时退化为按版本号解析。 */
    val versionCode: Int,
    /** 展示用文件名，如 `MoreCheck-1.2.0.apk`。 */
    val fileName: String,
    /** 存储里的逻辑路径（下载用），如 `shared/<uid>/apk/MoreCheck-1.2.0.apk`。 */
    val objectPath: String,
    /** 字节数；未知为 0。 */
    val sizeBytes: Long,
    /** SHA-256（小写十六进制）；未知为 null。 */
    val sha256: String?,
    /** 上传时间（ISO 8601 字符串，仅展示）。 */
    val uploadedAt: String?,
    /** 一句话更新说明（可空）。 */
    val notes: String?
)

/** 一次下载的结果：落盘位置 + 是否校验通过。 */
data class ApkDownloadResult(
    /** `Downloads/MoreCheck/xxx.apk` 对应的 MediaStore uri（系统下载目录里可见）。 */
    val uri: Uri?,
    /** cacheDir 里的一份副本，用于拉起系统安装器（FileProvider 已映射 cacheDir）。 */
    val installFile: File,
    /** 实际字节数。 */
    val bytes: Long,
    /** SHA-256 是否与清单一致；清单没给哈希时为 true（无从校验）。 */
    val verified: Boolean
)

/**
 * 未登录却请求了「必须登录」的资源（测试版安装包）时抛出，界面据此引导登录。
 *
 * 正式版永远不抛它——正式版本来就该免登录可见；只有用户明确要测试版且未登录
 * 才走这条路，避免发一个注定 403 的匿名请求后只回一句「下载失败」。
 */
class LoginRequiredException(message: String) : IllegalStateException(message)

/**
 * 正式版的版本名排除词（随包公开清单的筛选口径）。
 *
 * 只列 beta / debug：alpha / rc / snapshot 那一族由 [ReleaseChannel] 的渠道判断
 * 挡住，不必在这里重复；debug 是渠道判断会漏掉的那一个（它不在预发布标记里）。
 */
private val PUBLIC_EXCLUDE_MARKERS = listOf("beta", "debug")

/**
 * 判定一条清单记录是否属于「公开正式版」（纯函数，不依赖 Android 运行时）。
 *
 * ## 为什么 channel 与 version 要双判
 *
 * - `channel` 由 [ReleaseChannel.of] 从版本名推导，覆盖 `beta / alpha / rc /
 *   snapshot` 这一整族预发布标记；
 * - `version` 再单独挡 `beta / debug`：`debug` 不在 [ReleaseChannel] 的预发布
 *   标记里，一个 `1.2.0-debug` 会被判成 STABLE，只信 channel 就会把它当正式版
 *   放进随包公开清单，未登录用户因此看到一个不该免登录的诊断包。
 *
 * 客户端不信任清单内容：即使随包清单被误写进测试版，未登录视图也会把它滤掉，
 * 测试版永远走「必须登录」那条链路。
 */
internal fun isPublicRelease(entry: ReleaseApk): Boolean =
    entry.channel != ReleaseChannel.BETA &&
        PUBLIC_EXCLUDE_MARKERS.none { entry.version.contains(it, ignoreCase = true) }

/** 未登录视图：只保留公开正式版（见 [isPublicRelease]）。 */
internal fun publicReleaseView(releases: List<ReleaseApk>): List<ReleaseApk> =
    releases.filter(::isPublicRelease)

/**
 * 本机文件与清单里的这一版是否**同一个包**（纯函数，便于 JVM 单测）。
 *
 * 大小与 SHA-256 双判：同名同大小但内容不同的情况（同版本重出）只能靠哈希发现；
 * 清单没给哈希时（老脚本生成的条目）退化为只比大小，不能凭文件名就说"已存在"。
 */
internal fun apkBytesMatch(sizeBytes: Long, sha256: String?, expected: ReleaseApk): Boolean {
    if (expected.sizeBytes > 0 && sizeBytes != expected.sizeBytes) return false
    val expectedHash = expected.sha256 ?: return true
    return expectedHash.equals(sha256, ignoreCase = true)
}

/**
 * 「安装包下载页」的云端读取端。
 *
 * ## 为什么走云存储而不是 GitHub
 *
 * 用户需求原文：「利用打卡的云服务器存储正式版安装包（无 beta 后缀的）」。
 * GitHub Releases 在国内网络下经常连不上（本项目 `NewsBrief` 的 KDoc 里记过
 * 同类问题），而云存储就在应用自己的域名下、握手一次就通。
 *
 * 2026-11-01 起**测试版也走同一条链路**（`--include-beta` 上传），
 * 由 [ReleaseChannel] 区分，客户端在这一页分「正式版 / 测试版」两栏展示。
 *
 * ## 数据在哪
 *
 * ```text
 * APK assets/
 * └── public_releases.json         ← 随包正式版清单（未登录可读，零网络请求）
 *
 * runtime/
 * └── shared/<发布账号 uid>/apk/
 *     ├── manifest.json            ← 完整清单（正式版 + 测试版，仅登录可读）
 *     └── MoreCheck-<版本>.apk   ← 安装包本体（仅登录可读）
 * ```
 *
 * ⚠️ 云存储平台不开放匿名读：不带会话的 GET 在网关层就 404，加 anon RLS 策略
 * 也绕过不了（官方 storage 文档明确禁止 public bucket / 匿名 fallback）。
 * 因此**正式版免登录、测试版必须登录**由客户端实现：未登录读随包清单并由界面
 * 把下载交给浏览器；已登录读完整清单（含 beta）在应用内下载。详见
 * `scripts/PUBLIC_RELEASE_TIER.md`。
 *
 * ## 清单找不到时的兜底
 *
 * 只上传了 APK、忘了生成清单（或清单还在但对象被删）时，退化成
 * [CloudStorageClient.list] 列目录 + 从文件名解析版本号。这样"至少还能下到东西"，
 * 而不是整页空白——安装包是用户最需要的东西，不该因为少一个 json 就全没了。
 */
class ReleaseApkRepository(
    private val context: Context,
    private val storage: CloudStorageClient
) {

    // ---- 进程内清单缓存（2026-11-03 需求 1）----
    // 三件套必须一起改，故用同一个锁；`cachedReleases` 是快照、写入即替换，
    // 不原地修改，读侧拿到的 List 始终完整（`fetch` 返回的本来就是新 List）。
    private val cacheLock = Any()

    /** 最近一次成功拉到的清单；`null` = 没缓存。 */
    private var cachedReleases: List<ReleaseApk>? = null

    /**
     * 缓存属于哪个**视图**（[cachedReleases] 的键，见 [viewKey]）。
     *
     * 刻意不直接拿 ownerId 当键：匿名（仅正式版）与登录（含测试版）是两份不同的
     * 数据，登录/退出切换时键会变、自动失效，绝不会把「仅正式版」错显示成
     * 「含测试版」，或反过来把测试版泄漏给未登录用户。
     */
    private var cachedViewKey: String? = null

    /** 缓存写入时刻（`System.currentTimeMillis()`）。 */
    private var cachedAtMillis: Long = 0L

    /** "目录不存在 / 对象不存在 / 匿名无权"这类可忽略的失败（换下一个前缀再试）。 */
    private fun isBenign(error: CloudException): Boolean =
        error.error.kind in setOf(
            CloudError.Kind.NOT_FOUND,
            CloudError.Kind.INVALID_REQUEST,
            CloudError.Kind.PERMISSION_DENIED
        )

    /**
     * 逐个候选前缀尝试读 [fileName] 指定的清单，第一个**读到**（哪怕是空清单）的就返回。
     *
     * @return 读到的清单（可能为空）；`null` 表示所有前缀都没读到、且失败都属于
     *   [isBenign] 那类可忽略的情况。网络 / 服务端等硬错误直接抛出，交给界面区分
     *   "空"与"读不到"，不会静默显示"暂无正式版"。
     * @param allowListing 是否允许"清单缺失时列目录兜底"——只有完整清单需要；
     *   随包公开清单根本不走网络，用不到这个方法。
     */
    private suspend fun fetchFrom(
        prefixes: List<String>,
        fileName: String,
        requireAuth: Boolean,
        allowListing: Boolean
    ): List<ReleaseApk>? {
        var hardFailure: CloudException? = null
        for (prefix in prefixes) {
            val manifest = runCatching { readManifest(prefix, fileName, requireAuth) }
                .onFailure { error ->
                    if (error is CloudException && !isBenign(error)) hardFailure = error
                }
                .getOrNull()
            // 读到清单（即使为空）即算成功——空清单是合法的"作者还没发布"。
            if (manifest != null) return manifest
            if (allowListing) {
                val listing = runCatching { readListing(prefix) }
                    .onFailure { error ->
                        if (error is CloudException && !isBenign(error)) hardFailure = error
                    }
                    .getOrDefault(emptyList())
                if (listing.isNotEmpty()) return listing
            }
        }
        // 硬错误优先抛给用户（断网 / 服务端挂了别伪装成"暂无安装包"）；
        // 全是"没找到 / 没权限"则返回 null，由上层决定怎么提示。
        hardFailure?.let { throw it }
        return null
    }

    /** 完整清单（正式版 + 测试版）：登录通道、带列目录兜底。 */
    private suspend fun fetchFullManifest(): List<ReleaseApk> =
        fetchFrom(
            prefixes = CloudConfig.RELEASE_APK_PREFIXES,
            fileName = MANIFEST_NAME,
            requireAuth = true,
            allowListing = true
        ) ?: emptyList()

    /**
     * 公开正式版清单：**随 APK 打包在 assets 里**，不发任何网络请求。
     *
     * ## 为什么不是从云存储匿名读
     *
     * 本应用的云存储平台明确只服务已登录用户（官方 storage 文档：
     * "Storage is for signed-in users. Public Bucket/public URL access is not exposed."），
     * 实测不带会话的对象请求在云网关层就返回 404 `STORAGE_BUCKET_NOT_FOUND`，
     * 连数据库 RLS 都走不到，加 anon 策略也无效。因此「正式版免登录可见」
     * 只能由随包清单提供；正式版的**下载**由界面交给浏览器 / GitHub Releases，
     * 测试版才走云存储并强制要求登录。
     *
     * 随包清单由 `tools/upload_release_apk.py` 在发布正式版时重新生成，
     * 字段与云端 `manifest.json` 完全一致，复用 [parseManifestJson]。
     */
    private suspend fun fetchPublicManifest(): List<ReleaseApk> = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.assets.open(CloudConfig.PUBLIC_RELEASE_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull()
        if (text == null) return@withContext emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return@withContext emptyList()
        publicReleaseView(
            parseManifestJson(json, CloudConfig.RELEASE_APK_PREFIXES.last())
        )
    }

    /** 已登录视图：以完整清单为准，合并公开清单里多出来的正式版并去重。 */
    private suspend fun fetchLoggedInReleases(): List<ReleaseApk> {
        val full = fetchFullManifest()
        // 公开清单是"锦上添花"：缺了它不影响已登录用户看完整列表，故吞掉异常。
        val public = runCatching { fetchPublicManifest() }.getOrDefault(emptyList())
        return mergeReleases(full, public)
    }

    /** 同一 versionCode 以 [primary]（完整清单）为准；无 versionCode 时退化为按版本号去重。 */
    private fun mergeReleases(
        primary: List<ReleaseApk>,
        secondary: List<ReleaseApk>
    ): List<ReleaseApk> {
        val seen = HashSet<String>()
        val merged = ArrayList<ReleaseApk>(primary.size + secondary.size)
        for (apk in primary + secondary) {
            if (seen.add(dedupeKey(apk))) merged += apk
        }
        return merged.sortedWith(RELEASE_ORDER)
    }

    private fun dedupeKey(apk: ReleaseApk): String =
        if (apk.versionCode != 0) "vc:${apk.versionCode}" else "v:${apk.version}"

    /** 缓存视图键：把"匿名公开视图"与"已登录完整视图"彻底分开，互不串味。 */
    private fun viewKey(loggedIn: Boolean, ownerId: String?): String =
        if (loggedIn) "user:${ownerId ?: ""}" else "public"

    /**
     * 拉取云端清单（带**进程内缓存**）。按 [loggedIn] 分两条路径：
     * - 未登录：只读公开清单（匿名、绝不带 Bearer），只返回正式版，**不抛登录异常**；
     * - 已登录：读完整清单（含 beta，带 Bearer），并与公开清单合并去重。
     *
     * 缓存放在仓库层而非界面 `remember` 的理由（NavHost 返回时组合重建会重发请求、
     * 闪加载态）见踩坑手册 5.33；[ReleaseApkRepository] 是 `AppContainer` 单例，
     * 生命周期正好覆盖整个进程。缓存键见 [viewKey]：匿名与登录是两套互不相通的
     * 视图，5 分钟 TTL 不会把"仅正式版"错当成"含测试版"展示。
     *
     * @param force `true` = 跳过缓存直接请求云端（手动刷新用）。
     * @throws CloudException 已登录读完整清单时的网络 / 鉴权失败
     *   （界面据此区分"空"与"读不到"）。未登录路径只读随包资源，不会抛。
     */
    suspend fun fetch(
        loggedIn: Boolean,
        ownerId: String? = null,
        force: Boolean = false
    ): List<ReleaseApk> {
        val key = viewKey(loggedIn, ownerId)
        // 非强制请求先看缓存：命中就直接返回，一个网络包都不发。
        if (!force) {
            cachedSnapshot(loggedIn, ownerId)?.let { return it }
        }
        val releases = if (loggedIn) fetchLoggedInReleases() else fetchPublicManifest()
        // 成功才写缓存：请求失败时保留上一份可用数据，下次返回本页仍能立刻显示。
        synchronized(cacheLock) {
            cachedReleases = releases
            cachedViewKey = key
            cachedAtMillis = System.currentTimeMillis()
        }
        return releases
    }

    /**
     * 读取进程内缓存（同一视图且未过期才返回，否则 `null`）。
     *
     * 界面用它做 `mutableStateOf` 的**初值**：从二级页返回时首帧就是列表，
     * 不会先画一帧"正在读取云端安装包…"。返回 `null` 表示"必须联网拉一次"。
     */
    fun cachedSnapshot(loggedIn: Boolean, ownerId: String?): List<ReleaseApk>? {
        var fresh: List<ReleaseApk>? = null
        synchronized(cacheLock) {
            val snapshot = cachedReleases
            if (snapshot != null &&
                cachedViewKey == viewKey(loggedIn, ownerId) &&
                System.currentTimeMillis() - cachedAtMillis <= CACHE_TTL_MS
            ) {
                fresh = snapshot
            }
        }
        return fresh
    }

    /** 主动丢弃缓存（账号切换等场景由界面调用）。 */
    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedReleases = null
            cachedViewKey = null
            cachedAtMillis = 0L
        }
    }

    /**
     * 读云端完整清单；对象不存在 / 无权返回 null。
     *
     * 完整清单只对已登录用户可读，所以 [requireAuth] 恒为 true；随包正式版清单
     * 走 [fetchPublicManifest]，不经这里。
     */
    private suspend fun readManifest(
        prefix: String,
        manifestName: String,
        requireAuth: Boolean
    ): List<ReleaseApk>? {
        val bytes = try {
            storage.download("$prefix/$manifestName", requireAuth = requireAuth)
        } catch (e: CloudException) {
            // 存储层对"对象不存在"并不总是回 404：实测部分路径回 400
            // （`object not found`），匿名无权读时回 403。三者都按"没有清单"处理，
            // 交给下一个前缀或列目录兜底，而不是把整个读取判成失败。
            if (e.error.status == 404 || e.error.status == 400 ||
                e.error.kind == CloudError.Kind.PERMISSION_DENIED
            ) return null
            throw e
        }
        if (bytes.isEmpty()) return null
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return parseManifestJson(json, prefix)
    }

    /**
     * 把一份清单 JSON 解析成 [ReleaseApk] 列表。
     *
     * 云端完整清单与随包公开清单共用这套解析，避免两条链路的字段口径漂移。
     */
    private fun parseManifestJson(json: JSONObject, prefix: String): List<ReleaseApk> {
        // 兼容正式脚本的 `releases` 与另一种写法的 `items`，避免公开清单格式差异
        // 导致整个列表读不出来。
        val array = json.optJSONArray("releases") ?: json.optJSONArray("items") ?: return emptyList()
        val result = ArrayList<ReleaseApk>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // ⚠️ 不能写成 `.ifBlank { continue }`：`continue` 在 inline lambda 里
            // 属于 Kotlin 2.2 才放开的特性，本项目语言版本更低，编译直接报错。
            val version = item.optString("version", "")
            if (version.isBlank()) continue
            val fileName = item.optString("fileName", "").ifBlank { "MoreCheck-$version.apk" }
            val path = item.optString("objectPath", "").ifBlank { "$prefix/$fileName" }
            val size = item.optLong("sizeBytes", 0L).takeIf { it > 0L }
                ?: item.optLong("size", 0L)
            result += ReleaseApk(
                version = version,
                // 渠道以版本名为准，清单里的 `channel` 字段只作兜底（老脚本不写它）。
                channel = ReleaseChannel.of(version),
                versionCode = item.optInt("versionCode", 0),
                fileName = fileName,
                objectPath = path,
                sizeBytes = size,
                sha256 = item.optString("sha256", "").ifBlank { null },
                uploadedAt = item.optString("uploadedAt", "").ifBlank { null },
                notes = item.optString("notes", "").ifBlank { null }
            )
        }
        return result.sortedWith(RELEASE_ORDER)
    }

    /**
     * 列目录兜底：从文件名解析版本号（`MoreCheck-1.2.0.apk` / `MoreCheck-1.2.1-beta.apk`）。
     *
     * ⚠️ 这里**不再**过滤 beta（2026-11-01 需求：下载页要分「正式版 / 测试版」）。
     * 以前过滤是为了让页面只列正式版，现在渠道由 [ReleaseChannel.of] 标记，
     * 由界面分栏，过滤逻辑搬到界面层。`-diag.apk` 这类诊断包仍然排除。
     */
    private suspend fun readListing(prefix: String): List<ReleaseApk> {
        val objects = storage.list(prefix, limit = 100)
        return objects.mapNotNull { obj ->
            val name = obj.key.substringAfterLast('/')
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val version = name.removeSuffix(".apk").removePrefix("MoreCheck-").removePrefix("morecheck-")
            if (version.isBlank() || !version.first().isDigit()) return@mapNotNull null
            // 诊断包不是给用户装的（包名带 .diagnostic，与正式包不互通），一律不列。
            if (version.contains("diag", ignoreCase = true)) return@mapNotNull null
            ReleaseApk(
                version = version,
                channel = ReleaseChannel.of(version),
                versionCode = versionCodeOf(version),
                fileName = name,
                objectPath = obj.key,
                sizeBytes = obj.size,
                sha256 = null,
                uploadedAt = obj.lastModified,
                notes = null
            )
        }.sortedWith(RELEASE_ORDER)
    }

    /**
     * 下载一个安装包。
     *
     * 两处落盘，各有用途：
     * 1. **系统下载目录**（`Download/MoreCheck/xxx.apk`，MediaStore 两段式写入）——
     *    用户之后能在文件管理器里找到它；minSdk 29 起写这里**不需要**任何权限。
     * 2. **cacheDir 副本** —— 拉起系统安装器要用 `content://`（FileProvider 只映射了 cacheDir），
     *    直接喂 MediaStore 的 uri 给 `PackageInstaller` 在多数 ROM 上会被拒。
     *
     * 云存储平台只服务已登录用户（匿名 GET 在网关层就 404），因此这里**始终按
     * 登录态决定鉴权通道**：已登录的请求带 Bearer，无论正式版还是测试版都能读
     * `shared/` 下的对象。未登录时：
     * - 正式版由界面直接交给浏览器 / GitHub Releases，根本不会走到本方法；
     * - 测试版在方法入口抛 [LoginRequiredException]，让界面引导登录。
     *
 * @param loggedIn 当前是否已登录（由界面从云会话传入）。
 * @param onProgress 已下载字节数回调；现在按 16KB 分块真实回调（2026-11-10 需求 4），
 *   不再是"开始 0 / 结束 100"两跳。
 * @throws LoginRequiredException 未登录却要下载测试版。
 * @throws CloudException 网络 / 鉴权失败。
 * @throws IllegalStateException 哈希与清单不一致（文件在传输中被改坏）。
 */
    suspend fun download(
        apk: ReleaseApk,
        loggedIn: Boolean,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): ApkDownloadResult = withContext(Dispatchers.IO) {
        val public = isPublicRelease(apk)
        if (!public && !loggedIn) {
            throw LoginRequiredException("测试版安装包需登录云账号后才能下载")
        }
        val total = apk.sizeBytes.takeIf { it > 0 } ?: 0L
        onProgress(0L, total)
        // 平台不支持匿名读：能走到这里必然已登录（未登录的正式版在界面层走浏览器），
        // 统一带 Bearer，避免再发一个注定 404 的匿名请求。
        val bytes = storage.downloadWithProgress(apk.objectPath, loggedIn) { done ->
            onProgress(done, total)
        }
        onProgress(bytes.size.toLong(), bytes.size.toLong())

        val digest = sha256(bytes)
        val verified = apk.sha256 == null || apk.sha256.equals(digest, ignoreCase = true)
        if (!verified) {
            Log.w(LOG_TAG, "sha256 mismatch for ${apk.fileName}: expect=${apk.sha256} got=$digest")
            throw IllegalStateException("安装包校验失败（文件可能不完整），请重试")
        }

        val installFile = writeCacheCopy(apk.fileName, bytes)
        val uri = runCatching { writeToDownloads(apk.fileName, bytes) }
            .onFailure { Log.w(LOG_TAG, "write to Downloads failed", it) }
            .getOrNull()
        ApkDownloadResult(
            uri = uri,
            installFile = installFile,
            bytes = bytes.size.toLong(),
            verified = verified
        )
    }

    /**
     * 下载前先找本机已有的**同版本**安装包（2026-11-10 需求 4）。
     *
     * 顺序有讲究：
     * 1. `cacheDir/apk_install/<文件名>`：上次下载一定会写这份，且 FileProvider
     *    只能映射 cacheDir，安装器必须从它读——命中即可直接复用；
     * 2. 系统下载目录 `Download/MoreCheck/<文件名>`：用户手动放进去、或缓存被清但
     *    下载目录里的包还在。找到后把字节拷回 cacheDir 再交给安装器。
     *
     * 两处都要按清单的**大小 + SHA-256** 校验：同名不等于同一版（beta 同版本重出
     * 会换包），旧包 / 半截文件绝不能拿去安装。校验不通过就当没有，走正常下载。
     *
     * @return 可直接交给 [install] 的结果；没有可用本地包时返回 null。
     */
    suspend fun findLocalApk(apk: ReleaseApk): ApkDownloadResult? = withContext(Dispatchers.IO) {
        val cached = File(File(context.cacheDir, CACHE_DIR), apk.fileName)
        if (cached.isFile) {
            val bytes = runCatching { cached.readBytes() }.getOrNull()
            if (bytes != null && apkBytesMatch(bytes.size.toLong(), sha256(bytes), apk)) {
                return@withContext ApkDownloadResult(
                    uri = null,
                    installFile = cached,
                    bytes = bytes.size.toLong(),
                    verified = true
                )
            }
        }
        val downloaded = findInDownloads(apk.fileName) ?: return@withContext null
        val bytes = runCatching {
            context.contentResolver.openInputStream(downloaded)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (!apkBytesMatch(bytes.size.toLong(), sha256(bytes), apk)) return@withContext null
        ApkDownloadResult(
            uri = downloaded,
            installFile = writeCacheCopy(apk.fileName, bytes),
            bytes = bytes.size.toLong(),
            verified = true
        )
    }

    /**
     * 在系统下载目录里按文件名找包；只认本应用子目录、且已写完（非 pending）的条目。
     *
     * 用 `DISPLAY_NAME = ?` 精确到文件名，再逐行过滤 RELATIVE_PATH：同名包可能
     * 存在于别的目录，装错版本比重新下载一遍糟得多。
     */
    private fun findInDownloads(fileName: String): Uri? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.IS_PENDING
        )
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(fileName),
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
            val pendingIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)
            while (cursor.moveToNext()) {
                val path = cursor.getString(pathIndex).orEmpty()
                if (cursor.getInt(pendingIndex) == 0 && path.contains(DOWNLOAD_ALBUM)) {
                    return ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex)
                    )
                }
            }
        }
        return null
    }

    /**
     * 拉起系统安装器。
     *
     * 必须在**主线程**调用（`startActivity`），且带 `FLAG_GRANT_READ_URI_PERMISSION`：
     * 安装器是另一个进程，不给这个标志它读不到我们 cacheDir 里的文件，
     * 表现为"点安装没反应 / 解析包出错"。
     */
    fun install(result: ApkDownloadResult) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            result.installFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** 写一份 cacheDir 副本（同名覆盖，避免同一版本反复下载堆出多份）。 */
    private fun writeCacheCopy(fileName: String, bytes: ByteArray): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建临时目录")
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return file
    }

    /** 写系统下载目录（`IS_PENDING` 两段式，理由同 `TodoImageSaver`）。 */
    private fun writeToDownloads(fileName: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_ALBUM"
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载目录创建条目")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                ByteArrayInputStream(bytes).use { input -> input.copyTo(out) }
            } ?: error("无法写入下载目录")
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val LOG_TAG = "ReleaseApkRepository"

        /** 完整清单文件名（与上传脚本一致）：正式版 + 测试版，仅登录可读。 */
        private const val MANIFEST_NAME = "manifest.json"

        /**
         * 进程内缓存有效期：5 分钟。
         *
         * 只用来兜住"从更新日志 / 登录页返回"这类秒级往返；
         * 用户想拿最新列表有右上角刷新（`force = true`），不依赖 TTL 过期。
         */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** cacheDir 下的安装包副本目录（FileProvider 的 cache-path 已覆盖）。 */
        private const val CACHE_DIR = "apk_install"

        /** 系统下载目录里的子目录名。 */
        private const val DOWNLOAD_ALBUM = "MoreCheck"

        private const val APK_MIME = "application/vnd.android.package-archive"

        /** 文件名里出现这些词的一律视为预发布版，归入 [ReleaseChannel.BETA]。 */
        internal val BETA_MARKERS = listOf("beta", "alpha", "rc", "snapshot")

        /**
         * 新版本在前；versionCode 相同（清单没写）时按版本号兜底。
         *
         * 兜底用的是 [versionSortKey] 而不是 `versionParts()` 返回的 `List<Int>`——
         * `List` 不是 `Comparable`，直接塞进 `thenByDescending` 编译不过；
         * 而把每段补零成定宽字符串后，"1.2.10" 与 "1.2.9" 的自然序就与数值序一致了。
         */
        private val RELEASE_ORDER = compareByDescending<ReleaseApk> { it.versionCode }
            .thenByDescending { versionSortKey(it.version) }

        /** `"1.2.10"` → `"0001.0002.0010"`：逐段补零，字符串序 == 数值序。 */
        private fun versionSortKey(version: String): String =
            versionParts(version).joinToString(".") { it.toString().padStart(4, '0') }

        /** `"1.2.10"` → 可比较的数字列表（逐段比较，避免字符串序把 1.2.10 排在 1.2.9 前面）。 */
        private fun versionParts(version: String): List<Int> =
            version.split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }

        private fun versionCodeOf(version: String): Int {
            val parts = versionParts(version)
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            return major * 10_000 + minor * 100 + patch
        }
    }
}
