package com.morecheck.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 云端数据库客户端（PostgREST 风格）。
 *
 * 与官方 Web SDK 的 `cloud.database` 是**同一套服务端契约**，只是本应用是
 * 原生 Android、没有 JS SDK 可用，因此按数据平面的固定路径直连：
 * 基地址 `/.cloud/database/rest`，方法与 supabase-js 的 PostgREST 客户端一致。
 *
 * ## 安全模型（客户端什么都不用做）
 *
 * - `owner_id` **绝不发送**：表上它是 `DEFAULT auth.uid()`，服务端从令牌取出用户身份；
 * - 行级安全（RLS）保证每个用户只能读写自己的行——跨用户的更新 / 删除
 *   服务端返回空数组（不是报错），调用方据此判断「没改动任何行」；
 * - 客户端不做「这行是不是我的」判断，那是服务端的职责。
 */
class CloudDatabaseClient(private val http: CloudHttp) {

    /**
     * 查询。
     *
     * @param filters PostgREST 过滤器，如 `mapOf("updated_at" to "gt.1730000000000")`。
     *   键值都会 URL 编码。
     * @param select 列清单，默认 `*`。
     * @param order 排序，如 `"updated_at.asc"`（列.方向）。
     */
    suspend fun select(
        table: String,
        filters: Map<String, String> = emptyMap(),
        select: String = "*",
        order: String? = null,
        limit: Int? = null
    ): List<JSONObject> {
        val query = buildMap {
            put("select", select)
            putAll(filters)
            order?.let { put("order", it) }
            limit?.let { put("limit", it.toString()) }
        }
        val response = http.send(method = "GET", url = "${CloudConfig.DB_BASE}/$table", query = query)
        return response.asJsonArray().objects()
    }

    /** 插入（一批）。返回插入后的行（`Prefer: return=representation`）。 */
    suspend fun insert(table: String, rows: List<JSONObject>): List<JSONObject> {
        if (rows.isEmpty()) return emptyList()
        val body = org.json.JSONArray().apply { rows.forEach { put(it) } }
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.DB_BASE}/$table",
            // PostgREST 的写接口收**裸数组** body（不是包一层的对象）。
            body = body,
            extraHeaders = mapOf("Prefer" to "return=representation")
        )
        return response.asJsonArray().objects()
    }

    /**
     *  upsert（冲突时更新）。
     *
     * @param onConflict 冲突目标列，必须是表上某个唯一索引的左前缀，
     *   如 `"owner_id,local_id"`。`owner_id` 由服务端填默认值，
     *   这里出现在 on_conflict 里只是告诉服务端「按这个唯一索引判重」。
     */
    suspend fun upsert(
        table: String,
        rows: List<JSONObject>,
        onConflict: String
    ): List<JSONObject> {
        if (rows.isEmpty()) return emptyList()
        val body = org.json.JSONArray().apply { rows.forEach { put(it) } }
        val response = http.send(
            method = "POST",
            url = "${CloudConfig.DB_BASE}/$table",
            query = mapOf("on_conflict" to onConflict),
            body = body,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=representation")
        )
        return response.asJsonArray().objects()
    }

    /**
     * 更新。返回**实际被改动的行**。
     *
     * ⚠️ 返回空数组 = 一行都没改到（RLS 过滤或不存在），**不是成功**。
     * 调用方必须判空（与官方文档同一要求）。
     */
    suspend fun update(
        table: String,
        filters: Map<String, String>,
        values: JSONObject
    ): List<JSONObject> {
        require(filters.isNotEmpty()) { "update 必须带过滤条件，禁止全表更新" }
        val response = http.send(
            method = "PATCH",
            url = "${CloudConfig.DB_BASE}/$table",
            query = filters,
            body = values,
            extraHeaders = mapOf("Prefer" to "return=representation")
        )
        return response.asJsonArray().objects()
    }

    /** 删除。返回实际被删掉的行（同样可能为空数组）。 */
    suspend fun delete(table: String, filters: Map<String, String>): List<JSONObject> {
        require(filters.isNotEmpty()) { "delete 必须带过滤条件，禁止全表删除" }
        val response = http.send(
            method = "DELETE",
            url = "${CloudConfig.DB_BASE}/$table",
            query = filters,
            extraHeaders = mapOf("Prefer" to "return=representation")
        )
        return response.asJsonArray().objects()
    }
}
