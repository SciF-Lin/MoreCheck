package com.morecheck.data.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 存储里的一个对象（备份文件列表用）。 */
data class StorageObject(
    /** 对象键（相对路径，如 `users/<uid>/backups/backup-1730000000000.json`）。 */
    val key: String,
    /** 字节数；服务端没给为 0。 */
    val size: Long,
    /** 最后修改时间（服务端原样返回，可能是字符串或数字）。 */
    val lastModified: String?
)

/**
 * 云端存储客户端（整包备份 JSON / 记事本图片）。
 *
 * 与官方 Web SDK 的 `cloud.storage` 同一套数据平面（路径 `/.cloud/storage` 之下），
 * 本应用是原生 Android、无 JS SDK，按固定路径直连。
 *
 * ## 路径规则
 *
 * 平台只暴露一个逻辑桶 `runtime`，其下：
 * ```text
 * runtime/
 * ├── users/<uid>/...     仅本人可读写
 * └── shared/<uid>/...    本应用所有登录用户可读，仅本人可写
 * ```
 * 本项目只用 `users/<uid>/...`（私有备份）。URL 里带 `runtime/` 前缀，
 * 接口 body 里的 `prefixes` / `prefix` 用**不带桶名**的逻辑路径——
 * 这与官方 SDK 的行为一致（SDK 内部对 URL 拼 `runtime/`、对 body 传逻辑路径）。
 */
class CloudStorageClient(private val http: CloudHttp) {

    /**
     * 上传（默认覆盖同名对象）。
     *
     * 裸字节 + 显式 `content-type`（不用 multipart：备份 JSON 与图片都没有
     * 需要附加元数据的场景，multipart 只会让请求体大一圈）。
     *
     * @param path 逻辑路径，如 `users/<uid>/backups/backup-<ts>.json`。
     * @return 服务端确认的对象键（`fullPath`）。
     */
    suspend fun upload(
        path: String,
        bytes: ByteArray,
        contentType: String = "application/json",
        upsert: Boolean = true
    ): String {
        val response = http.sendBytes(
            method = "POST",
            url = "${CloudConfig.STORAGE_BASE}/object/${objectUrlPath(path)}",
            bytes = bytes,
            contentType = contentType,
            extraHeaders = mapOf(
                "cache-control" to "max-age=3600",
                "x-upsert" to upsert.toString()
            )
        )
        val json = response.asJsonObject()
        return json.optString("Key", "").ifBlank { path }
    }

    /** 下载对象为字节。 */
    suspend fun download(path: String): ByteArray =
        http.fetchBytes(
            method = "GET",
            url = "${CloudConfig.STORAGE_BASE}/object/${objectUrlPath(path)}"
        )

    /**
     * 列出某个前缀下的对象（偏移分页）。
     *
     * 服务端返回结构在各版本间不完全一致（数组 / `{items:[…]}` 都见过），
     * 因此解析时两者都认；字段名同样兼容 `Key` / `name` 两种。
     */
    suspend fun list(prefix: String, limit: Int = 100, offset: Int = 0): List<StorageObject> {
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.STORAGE_BASE}/object/list/${CloudConfig.STORAGE_BUCKET}",
            body = JSONObject().apply {
                put("prefix", prefix)
                put("limit", limit)
                put("offset", offset)
                put("sortBy", JSONObject().apply {
                    put("column", "name")
                    put("order", "desc")
                })
            }
        )
        val items: List<JSONObject> = when (val json = runCatching { JSONObject(response) }.getOrNull()) {
            null -> response.asJsonArray().objects()
            else -> json.optJSONArray("items")?.objects()
                ?: json.optJSONArray("objects")?.objects()
                ?: json.optJSONArray("files")?.objects()
                ?: emptyList()
        }
        return items.map { item ->
            val key = item.optString("Key", "").ifBlank { null }
                ?: item.optString("name", "").ifBlank { null }
                ?: item.optString("path", "").ifBlank { null }
                ?: ""
            StorageObject(
                key = key,
                size = item.optLong("Size", 0L).takeIf { it > 0 }
                    ?: item.optLong("size", 0L),
                lastModified = item.opt("LastModified")?.toString()
                    ?: item.opt("last_modified")?.toString()
            )
        }
    }

    /** 删除一批对象（按逻辑路径）。 */
    suspend fun remove(paths: List<String>) {
        if (paths.isEmpty()) return
        http.send(
            method = "POST",
            url = "${CloudConfig.STORAGE_BASE}/object/${CloudConfig.STORAGE_BUCKET}",
            body = JSONObject().apply {
                put("prefixes", JSONArray().apply { paths.forEach { put(it) } })
            }
        )
    }

    /**
     * 取一个带签名的下载 URL（默认 10 分钟有效）。
     *
     * 本项目目前不走签名 URL（下载统一用带令牌的 [download]），保留它是为了
     * 「把某个备份分享出去」这类将来的需求——签名 URL 就是干这个的。
     */
    suspend fun signedUrl(path: String, expiresIn: Int = 600): String {
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.STORAGE_BASE}/object/sign/${objectUrlPath(path)}",
            body = JSONObject().apply { put("expiresIn", expiresIn) }
        )
        val signed = response.asJsonObject().optString("signedURL", "")
        if (signed.isEmpty()) {
            cloudFail(
                CloudError(
                    kind = CloudError.Kind.BACKEND_UNAVAILABLE,
                    message = "服务端没有返回下载地址",
                    code = null,
                    status = 200
                )
            )
        }
        return CloudConfig.STORAGE_BASE + signed
    }

    /**
     * 逻辑路径 → URL 路径：逐段编码、保留分隔符。
     *
     * 整条路径直接编码会把 `/` 编成 `%2F`（服务端按字面量找对象，找不到）；
     * 不编码则文件名里的空格 / 中文会拼出非法 URL。逐段编码是唯一正确的做法。
     */
    private fun objectUrlPath(path: String): String =
        "${CloudConfig.STORAGE_BUCKET}/" + path.trim('/').split('/')
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
