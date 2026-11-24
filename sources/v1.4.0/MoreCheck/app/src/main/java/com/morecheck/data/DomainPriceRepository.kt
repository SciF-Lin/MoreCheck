// 「域名比价」的数据层：**端上直接抓注册商官网**，不经任何中间服务。
//
// ## 为什么不在网页应用里做了（v1.3.7 重写的原因）
//
// v1.3.6 的做法是把用户自建的 `domain-price-agent`（一个 Node 服务）发布成
// 沙箱地址，实验室里用 WebView 加载。那个地址是**临时沙箱**，实例回收后
// 页面直接 403/无法访问，功能就整体消失了；而且它把「能不能用」绑死在
// 一个随时会过期的域名上。
//
// 这一版改为把采集逻辑搬进 App：6 家注册商的官方价格页/官方接口由端上直连，
// 解析、汇率换算、排序全部在本地完成。好处是没有任何外部服务依赖，
// 坏处是**页面结构一变解析就会失效**——所以每个解析器都对"解析不出任何价格"
// 明确报错（绝不用记忆里的价格兜底），界面会把失败原因原样列出来。
//
// ## 采集口径（与官方页面一致，不做任何估算）
//
// - 阿里云·万网：官网「热门后缀」公示价（首年促销价 / 续费价 / 转入价 / 日常价）
// - 西部数码：价格总览页内嵌的全量价目表（GB18030 页面）
// - Porkbun：官方公开价目表接口（全量 TLD，单位美元）
// - Dynadot：官网 TLD price list（页面内嵌 NUXT 数据池）
// - Gandi：官网对比表（欧元）
// - Name.com：官网定价页（部分后缀，促销价 + 划线原价）
//
// ## 缓存
//
// 同一份官网价格 15 分钟内只抓一次（进程内内存缓存）。用户点「重新抓取」时
// 传 `force = true` 绕过缓存。

package com.morecheck.data

import com.morecheck.data.net.AppHttp
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一家注册商的数据源定义。 */
internal data class RegistrarSource(
    val id: String,
    val name: String,
    val short: String,
    /** 「国内」/「国外」，用于分组展示。 */
    val region: String,
    /** 该站点的标价币种（页面上识别出别的币种时会以识别结果为准）。 */
    val currency: String,
    val pageUrl: String,
    /** 一句话说明这个价格是什么口径（促销 / 批发价 / 含税等）。 */
    val note: String
)

/** 一条「某后缀在某注册商」的公示价。金额尚未换算，币种见 [currency]。 */
data class RegistrarPrice(
    val vendorId: String,
    val vendor: String,
    val short: String,
    val region: String,
    val tld: String,
    val currency: String,
    val register: Double?,
    val renew: Double?,
    val transfer: Double?,
    /** 划线原价（有促销时才有）。 */
    val regular: Double?,
    /** 注册价是不是「首年促销价」。 */
    val firstYear: Boolean,
    val buyUrl: String,
    val sourceUrl: String,
    val note: String
)

/** 一次抓取里单个数据源的结局。 */
data class SourceOutcome(
    val id: String,
    val name: String,
    val region: String,
    val ok: Boolean,
    val rowCount: Int,
    val error: String?,
    val elapsedMs: Long,
    val fromCache: Boolean
)

/** 解析出来的查询对象。 */
data class DomainQuery(
    val host: String,
    val sld: String,
    val tld: String,
    val domain: String,
    /** 用户是否自己写了后缀（没写时顺带给出一批常见后缀的最低价）。 */
    val explicit: Boolean
)

/** 实时汇率（人民币口径）。[ok] 为 false 时不做任何跨币种换算。 */
data class FxRates(
    val ok: Boolean,
    val usdCny: Double?,
    val eurCny: Double?,
    val gbpCny: Double?,
    val updatedAt: String?,
    val error: String?
) {
    fun cnyOf(amount: Double?, currency: String): Double? {
        val value = amount ?: return null
        if (!ok) return null
        val rate = when (currency.uppercase(Locale.ROOT)) {
            "CNY" -> 1.0
            "USD" -> usdCny
            "EUR" -> eurCny
            "GBP" -> gbpCny
            else -> null
        } ?: return null
        return Math.round(value * rate * 100.0) / 100.0
    }
}

/** RDAP 查询到的域名注册状态。 */
data class RdapStatus(
    /** registered / available / unknown */
    val status: String,
    val label: String,
    val detail: String
)

/** 换算成人民币之后的一行报价（列表按 [registerCny] 升序）。 */
data class PriceRow(
    val price: RegistrarPrice,
    val registerCny: Double?,
    val renewCny: Double?,
    val transferCny: Double?,
    val regularCny: Double?
)

/** 没写后缀时，常见后缀各自的最低价。 */
data class TldBest(
    val tld: String,
    val vendor: String,
    val registerCny: Double?,
    val currency: String,
    val register: Double
)

/** 一次比价的完整结果。 */
data class PriceComparison(
    val query: DomainQuery,
    val rows: List<PriceRow>,
    /** 抓到了该后缀、但换算不出人民币的行（汇率失败或币种未知）。 */
    val unconverted: List<PriceRow>,
    val spread: Double?,
    val fx: FxRates,
    val rdap: RdapStatus,
    val sources: List<SourceOutcome>,
    /** 没写后缀时才有：常见后缀的最低价一览。 */
    val overview: List<TldBest>,
    val fetchedAt: Long
) {
    val bestRegister: PriceRow? get() = rows.firstOrNull()
    val bestRenew: PriceRow? get() = rows.filter { it.renewCny != null }.minByOrNull { it.renewCny ?: 0.0 }
    val bestTransfer: PriceRow? get() = rows.filter { it.transferCny != null }.minByOrNull { it.transferCny ?: 0.0 }
    val okSourceCount: Int get() = sources.count { it.ok }
}

/** 本次未能取得可机读实时价格的厂商（如实列出，不用记忆填价）。 */
data class UnlistedVendor(val name: String, val region: String, val reason: String)

object DomainPriceRepository {

    /** 常见后缀（没写后缀时给出的概览顺序）。 */
    val POPULAR_TLDS = listOf(
        "com", "cn", "net", "org", "xyz", "io", "ai", "app", "dev", "top", "shop", "online"
    )

    /** 明确拿不到实时价格的厂商。 */
    val UNLISTED_VENDORS = listOf(
        UnlistedVendor("腾讯云 / DNSPod", "国内", "官网价格由登录态接口动态下发，没有公开可机读报价"),
        UnlistedVendor("华为云", "国内", "官网价格由脚本动态加载，公开页面不含价格表"),
        UnlistedVendor("Cloudflare", "国外", "报价走 WebSocket 实时接口且需账号，本次未接入"),
        UnlistedVendor("GoDaddy / Namecheap", "国外", "官网对自动访问返回 403")
    )

    /** 价格表缓存 15 分钟：官网价目表不是分钟级变动的东西，但也不能一直用旧值。 */
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private class CacheEntry(
        val at: Long,
        val rows: List<RegistrarPrice>,
        val elapsedMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private val SOURCES = listOf(
        RegistrarSource(
            id = "aliyun",
            name = "阿里云 · 万网",
            short = "万网",
            region = "国内",
            currency = "CNY",
            pageUrl = "https://wanwang.aliyun.com/domain/",
            note = "官网「热门后缀」公示价：首年价 / 续费价 / 转入价 / 日常价"
        ),
        RegistrarSource(
            id = "west",
            name = "西部数码",
            short = "西部数码",
            region = "国内",
            currency = "CNY",
            pageUrl = "https://www.west.cn/web/price/domainpricelist",
            note = "官网价格总览内嵌价目表：注册首年价 / 续费价 / 转入价"
        ),
        RegistrarSource(
            id = "porkbun",
            name = "Porkbun",
            short = "Porkbun",
            region = "国外",
            currency = "USD",
            pageUrl = "https://www.porkbun.com/products/domains",
            note = "官方价目表接口，全量后缀；首页价可能含首年优惠"
        ),
        RegistrarSource(
            id = "dynadot",
            name = "Dynadot",
            short = "Dynadot",
            region = "国外",
            currency = "USD",
            pageUrl = "https://www.dynadot.com/domain/tld-price",
            note = "官网 TLD price list：注册 / 续费 / 转入"
        ),
        RegistrarSource(
            id = "gandi",
            name = "Gandi",
            short = "Gandi",
            region = "国外",
            currency = "EUR",
            pageUrl = "https://www.gandi.net/en/domain",
            note = "官网对比表：Register / Renew / Transfer（欧元）"
        ),
        RegistrarSource(
            id = "namecom",
            name = "Name.com",
            short = "Name.com",
            region = "国外",
            currency = "USD",
            pageUrl = "https://www.name.com/pricing",
            note = "官网定价页促销价，划线价为原价（仅部分后缀）"
        )
    )

    /* ------------------------------------------------------------------ */
    /* 对外入口                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 查询一个域名/后缀的跨注册商价格。
     *
     * @param onProgress 抓取过程中的进度文案（在 IO 线程回调，界面自己切主线程）。
     * @param force 是否绕过 15 分钟缓存。
     */
    suspend fun query(
        input: String,
        force: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): Result<PriceComparison> = withContext(Dispatchers.IO) {
        runCatching {
            val q = parseDomainInput(input)
            onProgress("并行抓取 ${SOURCES.size} 家注册商官方价格表…")

            val fetched = coroutineScope {
                val fxJob = async { fetchFx() }
                val rdapJob = async {
                    // 纯后缀查询没有具体域名，RDAP 无从查起。
                    if (q.sld.isNotEmpty()) checkRdap(q.domain) else null
                }
                val sourceJobs = SOURCES.map { src ->
                    async {
                        val outcome = loadSource(src, force)
                        onProgress(if (outcome.ok) "${src.name}：${outcome.rowCount} 个后缀" else "${src.name}：${outcome.error}")
                        outcome
                    }
                }
                Triple(sourceJobs.awaitAll(), fxJob.await(), rdapJob.await())
            }

            val (outcomes, fx, rdap) = fetched
            onProgress("按人民币统一口径比价…")
            buildComparison(q, outcomes, fx, rdap)
        }
    }

    /* ------------------------------------------------------------------ */
    /* 抓取与解析                                                          */
    /* ------------------------------------------------------------------ */

    private suspend fun loadSource(src: RegistrarSource, force: Boolean): SourceOutcome {
        val started = System.currentTimeMillis()
        val hit = cache[src.id]
        if (!force && hit != null && started - hit.at < CACHE_TTL_MS) {
            return SourceOutcome(
                id = src.id,
                name = src.name,
                region = src.region,
                ok = true,
                rowCount = hit.rows.size,
                error = null,
                elapsedMs = hit.elapsedMs,
                fromCache = true
            )
        }
        return try {
            val rows = fetchRows(src)
            val elapsed = System.currentTimeMillis() - started
            cache[src.id] = CacheEntry(started, rows, elapsed)
            SourceOutcome(src.id, src.name, src.region, true, rows.size, null, elapsed, false)
        } catch (e: Exception) {
            // 失败时保留旧缓存：官网偶发 502 不该让 15 分钟内刚抓到的价格凭空消失。
            val stale = cache[src.id]
            if (stale != null) {
                SourceOutcome(
                    src.id,
                    src.name,
                    src.region,
                    true,
                    stale.rows.size,
                    null,
                    stale.elapsedMs,
                    true
                )
            } else {
                SourceOutcome(
                    src.id,
                    src.name,
                    src.region,
                    false,
                    0,
                    friendlyError(e),
                    System.currentTimeMillis() - started,
                    false
                )
            }
        }
    }

    private fun friendlyError(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("HTTP 403") -> "官网拒绝自动访问（HTTP 403）"
            raw.contains("HTTP 404") -> "官网价格页已改址（HTTP 404）"
            raw.contains("HTTP 5") -> "官网临时故障（$raw）"
            raw.contains("timeout", ignoreCase = true) -> "请求超时"
            raw.contains("Unable to resolve host", ignoreCase = true) -> "域名解析失败（网络不可用）"
            raw.isNotBlank() -> raw
            else -> "抓取失败"
        }
    }

    private fun fetchRows(src: RegistrarSource): List<RegistrarPrice> {
        val rows = when (src.id) {
            "aliyun" -> parseAliyun(fetchText(src.pageUrl, html = true), src)
            "west" -> parseWest(fetchText(src.pageUrl, html = true, charsetName = "GB18030"), src)
            "porkbun" -> parsePorkbun(
                AppHttp.postJson(
                    "https://api.porkbun.com/api/json/v3/pricing/get",
                    "{}",
                    mapOf("Accept" to "application/json")
                ),
                src
            )
            "dynadot" -> parseDynadot(fetchText(src.pageUrl, html = true), src)
            "gandi" -> parseGandi(fetchText(src.pageUrl, html = true), src)
            "namecom" -> parseNameCom(fetchText(src.pageUrl, html = true), src)
            else -> error("未知数据源：${src.id}")
        }
        if (rows.isEmpty()) error("官方页面结构变化：未解析出任何价格")
        return rows
    }

    private fun fetchText(url: String, html: Boolean, charsetName: String = "UTF-8"): String {
        val headers = if (html) {
            mapOf(
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "User-Agent" to UA
            )
        } else {
            mapOf("User-Agent" to UA)
        }
        val bytes = AppHttp.getBytes(url, headers)
        return String(bytes, charsetOf(charsetName))
    }

    private fun charsetOf(name: String): java.nio.charset.Charset =
        runCatching { java.nio.charset.Charset.forName(name) }
            .getOrDefault(Charsets.UTF_8)

    /* ------------------------------------------------------------------ */
    /* 汇率与 RDAP                                                         */
    /* ------------------------------------------------------------------ */

    private fun fetchFx(): FxRates {
        return try {
            val text = AppHttp.getText(
                "https://open.er-api.com/v6/latest/USD",
                mapOf("Accept" to "application/json")
            )
            val root = JSONObject(text)
            if (root.optString("result") != "success") {
                return FxRates(false, null, null, null, null, "汇率接口返回异常")
            }
            val rates = root.optJSONObject("rates") ?: return FxRates(false, null, null, null, null, "汇率缺字段")
            val cny = rates.optDouble("CNY", Double.NaN)
            if (cny.isNaN() || cny <= 0.0) {
                return FxRates(false, null, null, null, null, "汇率缺 CNY 牌价")
            }
            // 接口以 USD 为基准，EUR/GBP 是「1 美元兑多少欧/英镑」，
            // 所以「1 欧元兑多少人民币」要用 cny / eur 交叉算出来。
            fun cross(code: String): Double? {
                val perUsd = rates.optDouble(code, Double.NaN)
                if (perUsd.isNaN() || perUsd <= 0.0) return null
                return cny / perUsd
            }
            FxRates(
                ok = true,
                usdCny = cny,
                eurCny = cross("EUR"),
                gbpCny = cross("GBP"),
                updatedAt = root.optString("time_last_update_utc", "").takeIf { it.isNotBlank() },
                error = null
            )
        } catch (e: Exception) {
            FxRates(false, null, null, null, null, friendlyError(e))
        }
    }

    private fun checkRdap(domain: String): RdapStatus {
        val url = "https://rdap.org/domain/$domain"
        val code = try {
            AppHttp.getStatus(
                url,
                mapOf(
                    "Accept" to "application/rdap+json",
                    "User-Agent" to "MoreCheck-Android"
                ),
                timeoutSeconds = 15
            )
        } catch (e: Exception) {
            return RdapStatus("unknown", "状态未知", "RDAP 查询未完成（${friendlyError(e)}）")
        }
        return when (code) {
            200 -> RdapStatus("registered", "已被注册", "RDAP 返回 200，注册局存在该域名记录")
            404 -> RdapStatus("available", "可能可注册", "RDAP 返回 404，注册局无此域名记录")
            else -> RdapStatus("unknown", "状态未知", "RDAP 返回 $code")
        }
    }

    /* ------------------------------------------------------------------ */
    /* 比价计算                                                            */
    /* ------------------------------------------------------------------ */

    private fun buildComparison(
        q: DomainQuery,
        outcomes: List<SourceOutcome>,
        fx: FxRates,
        rdap: RdapStatus?
    ): PriceComparison {
        val allRows = cache.values.flatMap { it.rows }
        val converted = allRows
            .filter { it.tld == q.tld && it.register != null }
            .map { row ->
                PriceRow(
                    price = row,
                    registerCny = fx.cnyOf(row.register, row.currency),
                    renewCny = fx.cnyOf(row.renew, row.currency),
                    transferCny = fx.cnyOf(row.transfer, row.currency),
                    regularCny = fx.cnyOf(row.regular, row.currency)
                )
            }
        val rows = converted
            .filter { it.registerCny != null }
            .sortedBy { it.registerCny ?: 0.0 }
        val unconverted = converted.filter { it.registerCny == null }
        val spread = if (rows.size > 1) {
            val first = rows.first().registerCny ?: 0.0
            val last = rows.last().registerCny ?: 0.0
            Math.round((last - first) * 100.0) / 100.0
        } else {
            null
        }
        return PriceComparison(
            query = q,
            rows = rows,
            unconverted = unconverted,
            spread = spread,
            fx = fx,
            rdap = rdap ?: RdapStatus("unknown", "未查询", "未写后缀，跳过注册状态查询"),
            sources = outcomes,
            overview = if (q.explicit) emptyList() else buildOverview(allRows, fx),
            fetchedAt = System.currentTimeMillis()
        )
    }

    private fun buildOverview(allRows: List<RegistrarPrice>, fx: FxRates): List<TldBest> =
        POPULAR_TLDS.mapNotNull { tld ->
            val best = allRows
                .filter { it.tld == tld && it.register != null }
                .mapNotNull { row ->
                    fx.cnyOf(row.register, row.currency)?.let { cny ->
                        TldBest(tld, row.short, cny, row.currency, row.register ?: 0.0)
                    }
                }
                .minByOrNull { it.registerCny ?: Double.MAX_VALUE }
            best
        }
}

/* ---------------------------------------------------------------------- */
/* 解析器：全部是纯函数，便于单元测试喂固定样本                             */
/* ---------------------------------------------------------------------- */

private val MONEY_PATTERN = Regex("([¥￥\$€£]|USD|EUR|CNY|GBP)?\\s*([0-9.,]+)")

/** 把 "¥11.65" / "$11.62" / "11,65" 拆成金额与币种；识别不出返回 (null, null)。 */
internal fun parseMoney(raw: String?): Pair<Double?, String?> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.startsWith("-")) return null to null
    val match = MONEY_PATTERN.find(text) ?: return null to null
    val currency = when (match.groupValues[1]) {
        "¥", "￥" -> "CNY"
        "€", "EUR" -> "EUR"
        "£", "GBP" -> "GBP"
        "\$", "USD" -> "USD"
        "CNY" -> "CNY"
        else -> null
    }
    return toAmount(match.groupValues[2]) to currency
}

/** "1,299.00" → 1299.0；解析不出返回 null。 */
internal fun toAmount(raw: String?): Double? {
    val text = raw?.replace(",", "")?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.toDoubleOrNull()
}

private fun cleanTld(raw: String?): String? {
    val text = raw?.trim()?.lowercase(Locale.ROOT)?.removePrefix(".").orEmpty()
    if (text.isEmpty()) return null
    if (!text.matches(Regex("[a-z0-9.-]{1,40}"))) return null
    return text
}

private fun row(
    src: RegistrarSource,
    tld: String,
    currency: String?,
    register: Double?,
    renew: Double?,
    transfer: Double?,
    regular: Double?,
    firstYear: Boolean,
    buyUrl: String
): RegistrarPrice = RegistrarPrice(
    vendorId = src.id,
    vendor = src.name,
    short = src.short,
    region = src.region,
    tld = tld,
    currency = currency ?: src.currency,
    register = register,
    renew = renew,
    transfer = transfer,
    regular = regular,
    firstYear = firstYear,
    buyUrl = buyUrl,
    sourceUrl = src.pageUrl,
    note = src.note
)

/* ------------------------------ 阿里云万网 ------------------------------ */

private val ALIYUN_TLD = Regex("class=\"suffix\">\\s*\\.([a-z0-9-]+)", RegexOption.IGNORE_CASE)
private val ALIYUN_FIRST = Regex("class=\"price-info__price\">\\s*¥\\s*<i class=\"integer\">([0-9.]+)</i>")
private val ALIYUN_AGAIN = Regex("class=\"price-info__again\">([\\s\\S]*?)<div class=\"price-info__begin\">")
private val ALIYUN_YEAR = Regex("¥\\s*([0-9.]+)/年")
private val ALIYUN_REGULAR = Regex("日常价:</span>\\s*¥\\s*([0-9.]+)/年")

/**
 * 阿里云「热门后缀」卡片。
 *
 * 每张卡的结构是：`.suffix` → `price-info__again`（续费、转入）→
 * `price-info__begin`（首年价）→ `日常价`。续费与转入的先后顺序由页面决定，
 * 这里按出现顺序取（第 1 个是续费、第 2 个是转入）。
 */
internal fun parseAliyun(html: String, src: RegistrarSource): List<RegistrarPrice> {
    val rows = mutableListOf<RegistrarPrice>()
    val blocks = html.split("class=\"recommendation__commodity\"")
    for (index in 1 until blocks.size) {
        val body = blocks[index].take(3000)
        val tld = cleanTld(ALIYUN_TLD.find(body)?.groupValues?.get(1)) ?: continue
        val first = ALIYUN_FIRST.find(body)?.groupValues?.get(1)
        val againBlock = ALIYUN_AGAIN.find(body)?.groupValues?.get(1).orEmpty()
        val again = ALIYUN_YEAR.findAll(againBlock).map { it.groupValues[1] }.toList()
        val regular = ALIYUN_REGULAR.find(body)?.groupValues?.get(1)
        val register = toAmount(first) ?: toAmount(regular)
        val renew = toAmount(again.getOrNull(0))
        val transfer = toAmount(again.getOrNull(1))
        if (register == null && renew == null) continue
        rows += row(
            src = src,
            tld = tld,
            currency = "CNY",
            register = register,
            renew = renew,
            transfer = transfer,
            regular = toAmount(regular),
            firstYear = first != null,
            buyUrl = "https://wanwang.aliyun.com/domain/searchresult/#/?suffix=$tld"
        )
    }
    return rows
}

/* ------------------------------- 西部数码 ------------------------------- */

private val WEST_DATA = Regex("var\\s+enData\\s*=\\s*JSON\\.parse\\('([\\s\\S]*?)'\\)\\s*;")

/**
 * 西部数码价格总览：价格表以 `var enData = JSON.parse('…');` 的形式内嵌在页面里。
 *
 * 那段内容本身是「JSON 文本包在 JS 单引号字符串里」的形态，所以先按 JS 字符串
 * 反转义（`\'`、`\uXXXX` 等），再当 JSON 解析。
 */
internal fun parseWest(html: String, src: RegistrarSource): List<RegistrarPrice> {
    val embedded = WEST_DATA.find(html)?.groupValues?.get(1) ?: return emptyList()
    val list = try {
        JSONArray(decodeJsString(embedded))
    } catch (e: Exception) {
        return emptyList()
    }
    val rows = mutableListOf<RegistrarPrice>()
    for (i in 0 until list.length()) {
        val item = list.optJSONObject(i) ?: continue
        val tld = cleanTld(item.optString("suffix", "")) ?: continue
        val buy = item.optJSONObject("buy")
        val renew = item.optJSONObject("renew")
        val register = firstPrice(buy) ?: firstPrice(renew)
        val renewPrice = firstPrice(renew)
        val transfer = item.optJSONObject("turnprice")?.optString("buyprice", "")
        if (register == null && renewPrice == null) continue
        rows += row(
            src = src,
            tld = tld,
            currency = "CNY",
            register = register,
            renew = renewPrice,
            transfer = toAmount(transfer),
            regular = toAmount(item.optString("oldprice", "")),
            firstYear = false,
            buyUrl = item.optString("link", "").ifBlank { src.pageUrl }
        )
    }
    return rows
}

/** 西部数码的价目结构是 `buy[年限][数量] = 价格`，取「1 年 1 个」那一档。 */
private fun firstPrice(block: JSONObject?): Double? {
    if (block == null) return null
    for (yearKey in listOf("1", "7")) {
        val year = block.optJSONObject(yearKey) ?: continue
        val price = toAmount(year.optString("1", ""))
        if (price != null) return price
    }
    return null
}

/**
 * 解开 JS 字符串字面量的转义。
 *
 * 为什么不直接 `JSONObject("\"" + raw + "\"")`：原始文本里可能含未转义的引号
 * （外层是单引号字符串，双引号无需转义），包进双引号会立刻变非法 JSON。
 */
internal fun decodeJsString(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            sb.append(c)
            i++
            continue
        }
        when (val next = raw[i + 1]) {
            'u' -> {
                val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                val code = hex.toIntOrNull(16)
                if (code != null && hex.length == 4) {
                    sb.append(code.toChar())
                    i += 6
                } else {
                    sb.append(next)
                    i += 2
                }
            }
            'n' -> { sb.append('\n'); i += 2 }
            'r' -> { sb.append('\r'); i += 2 }
            't' -> { sb.append('\t'); i += 2 }
            else -> { sb.append(next); i += 2 }
        }
    }
    return sb.toString()
}

/* -------------------------------- Porkbun -------------------------------- */

/** Porkbun 官方价目表接口：`pricing[<tld>] = {registration, renewal, transfer}`（字符串，美元）。 */
internal fun parsePorkbun(json: String, src: RegistrarSource): List<RegistrarPrice> {
    val pricing = JSONObject(json).optJSONObject("pricing") ?: return emptyList()
    val rows = mutableListOf<RegistrarPrice>()
    val keys = pricing.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val tld = cleanTld(key) ?: continue
        val item = pricing.optJSONObject(key) ?: continue
        val register = toAmount(item.optString("registration", ""))
        val renew = toAmount(item.optString("renewal", ""))
        val transfer = toAmount(item.optString("transfer", ""))
        if (register == null && renew == null) continue
        rows += row(
            src = src,
            tld = tld,
            currency = "USD",
            register = register,
            renew = renew,
            transfer = transfer,
            regular = null,
            firstYear = false,
            buyUrl = "https://www.porkbun.com/tld/$tld"
        )
    }
    return rows
}

/* -------------------------------- Dynadot -------------------------------- */

private val NUXT_DATA = Regex(
    "<script type=\"application/json\"[^>]*id=\"__NUXT_DATA__\"[^>]*>([\\s\\S]*?)</script>"
)

/**
 * Dynadot 的 TLD 价目表把全量数据塞在页面的 NUXT 数据池里：
 * 池是一个数组，池内对象里的**整数值是下标引用**（真正的字符串在 `pool[下标]`）。
 * 所以取值必须判断"这个数字是不是一个合法的池下标"。
 */
internal fun parseDynadot(html: String, src: RegistrarSource): List<RegistrarPrice> {
    val payload = NUXT_DATA.find(html)?.groupValues?.get(1) ?: return emptyList()
    val pool = try {
        JSONArray(payload)
    } catch (e: Exception) {
        return emptyList()
    }
    fun resolve(ref: Any?): Any? {
        val index = when (ref) {
            is Int -> ref
            is Long -> if (ref in 0L..Int.MAX_VALUE.toLong()) ref.toInt() else -1
            else -> return ref
        }
        if (index < 0 || index >= pool.length()) return ref
        return pool.opt(index)
    }
    val rows = mutableListOf<RegistrarPrice>()
    val seen = mutableSetOf<String>()
    for (i in 0 until pool.length()) {
        val item = pool.optJSONObject(i) ?: continue
        if (!item.has("reg_price")) continue
        val name = resolve(item.opt("name")) as? String ?: continue
        val tld = cleanTld(name) ?: continue
        if (!seen.add(tld)) continue
        val regRaw = resolve(item.opt("reg_price")).toString()
        val (regValue, regCurrency) = parseMoney(regRaw)
        if (regValue == null) continue
        val (renewValue, renewCurrency) = parseMoney(resolve(item.opt("renew_price")).toString())
        val (transferValue, _) = parseMoney(resolve(item.opt("tr_price")).toString())
        rows += row(
            src = src,
            tld = tld,
            currency = regCurrency ?: renewCurrency ?: src.currency,
            register = regValue,
            renew = renewValue,
            transfer = transferValue,
            regular = null,
            firstYear = false,
            buyUrl = "https://www.dynadot.com/domain/$tld"
        )
    }
    return rows
}

/* --------------------------------- Gandi --------------------------------- */

private val GANDI_ROW = Regex("<tr class=\"comparative-table__row\">([\\s\\S]*?)</tr>")
private val GANDI_TLD = Regex("/en/domain/tld/([^\"']+)")
private val GANDI_PRICE = Regex("class=\"comparative-table__price\">\\s*([^<]+)<")

/** Gandi 官网对比表：一行一个后缀，依次是 Register / Renew / Transfer。 */
internal fun parseGandi(html: String, src: RegistrarSource): List<RegistrarPrice> {
    val rows = mutableListOf<RegistrarPrice>()
    for (match in GANDI_ROW.findAll(html)) {
        val body = match.groupValues[1]
        val tld = cleanTld(GANDI_TLD.find(body)?.groupValues?.get(1)) ?: continue
        val prices = GANDI_PRICE.findAll(body).map { parseMoney(it.groupValues[1]) }.toList()
        if (prices.isEmpty()) continue
        val first = prices[0]
        if (first.first == null) continue
        rows += row(
            src = src,
            tld = tld,
            currency = first.second ?: src.currency,
            register = first.first,
            renew = prices.getOrNull(1)?.first,
            transfer = prices.getOrNull(2)?.first,
            regular = null,
            firstYear = false,
            buyUrl = "https://www.gandi.net/en/domain/tld/$tld"
        )
    }
    return rows
}

/* -------------------------------- Name.com -------------------------------- */

// Name.com 会按访问地区改写链接前缀（`/domains/io` 与 `/zh-cn/domains/io` 都出现过），
// 所以语言前缀必须是可选的，否则换个地区就一条都匹配不上。
private val NAMECOM_ANCHOR = Regex(
    "href=[\"'](?:/[a-z]{2}(?:-[a-z]{2})?)?/domains/([a-z0-9.-]+)[\"'][^>]*>([\\s\\S]{0,1200}?)</a>",
    RegexOption.IGNORE_CASE
)
private val NAMECOM_LOOSE = Regex(
    "href=[\"'](?:/[a-z]{2}(?:-[a-z]{2})?)?/domains/([a-z0-9.-]+)[\"'][\\s\\S]{0,900}?([¥￥\$€£]\\s*[0-9.]+)",
    RegexOption.IGNORE_CASE
)
private val NAMECOM_PRICES = Regex("([¥￥\$€£]|USD|EUR|CNY)\\s*([0-9.]+)")

/**
 * Name.com 定价页：只有「精选后缀」带完整价格，卡片结构在不同模板下不一致，
 * 所以先试"锚点内取价"，再退到"链接后若干字符内的第一个价格"。
 * 取到的第一个价是促销价，第二个不同价是划线原价。
 */
internal fun parseNameCom(html: String, src: RegistrarSource): List<RegistrarPrice> {
    val rows = mutableListOf<RegistrarPrice>()
    val seen = mutableSetOf<String>()

    fun pricesIn(text: String): List<Pair<Double?, String?>> =
        NAMECOM_PRICES.findAll(text).map { parseMoney(it.groupValues[0]) }.toList()

    fun add(tld: String?, prices: List<Pair<Double?, String?>>) {
        val clean = cleanTld(tld) ?: return
        if (prices.isEmpty() || prices[0].first == null || !seen.add(clean)) return
        val first = prices[0]
        val second = prices.getOrNull(1)
        val discounted = second != null && second.first != null && second.first != first.first
        rows += row(
            src = src,
            tld = clean,
            currency = first.second ?: src.currency,
            register = first.first,
            renew = null,
            transfer = null,
            regular = if (discounted) second?.first else null,
            firstYear = discounted,
            buyUrl = "https://www.name.com/domains/$clean"
        )
    }

    for (match in NAMECOM_ANCHOR.findAll(html)) {
        add(match.groupValues[1], pricesIn(match.groupValues[2]))
    }
    if (rows.isEmpty()) {
        for (match in NAMECOM_LOOSE.findAll(html)) {
            add(match.groupValues[1], pricesIn(match.groupValues[2]))
        }
    }
    return rows
}

/* ---------------------------------------------------------------------- */
/* 输入解析                                                                */
/* ---------------------------------------------------------------------- */

private val KNOWN_SUFFIXES = setOf(
    "com", "net", "org", "cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
    "xyz", "io", "ai", "app", "dev", "top", "shop", "online", "site", "store",
    "tech", "cloud", "vip", "work", "pro", "link", "info", "biz", "club", "me",
    "cc", "tv", "co", "co.uk", "org.uk", "com.au", "co.jp", "com.hk", "com.tw",
    "com.sg", "com.mx", "de", "fr", "jp", "kr", "us", "uk", "eu", "ru", "in"
)

private val SLD_PATTERN = Regex("[a-z0-9-]{1,63}")
private val TLD_PATTERN = Regex("[a-z0-9.-]{1,40}")

/**
 * 把用户输入（可能是 `https://www.example.com/path`，也可能只是 `example`）解析成
 * 主体 + 后缀。没写后缀时按 `.com` 算，同时把 [DomainPriceRepository.POPULAR_TLDS]
 * 的最低价一并给出。
 */
internal fun parseDomainInput(raw: String): DomainQuery {
    var text = raw.trim().lowercase(Locale.ROOT)
    // 中文输入法下打句点会得到全角「。」，用户不会为此再切一次键盘；
    // 顺手把全角点与全角空格归一，否则会被判成"域名主体不合法"。
    text = text.replace('。', '.').replace('．', '.').replace('\u3000', ' ')
    text = text.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    text = text.split('/', '?', '#', ':', ' ', '\t').firstOrNull().orEmpty()
    text = text.trimEnd('.')
    require(text.isNotEmpty()) { "请输入要查询的域名" }

    val parts = text.split('.').filter { it.isNotEmpty() }
    var tld: String? = null
    var sld = text
    for (i in parts.indices) {
        val candidate = parts.drop(i).joinToString(".")
        if (candidate in KNOWN_SUFFIXES) {
            tld = candidate
            sld = parts.take(i).joinToString(".")
            break
        }
    }
    val explicit = parts.size > 1
    if (tld == null && explicit) {
        tld = parts.last()
        sld = parts.dropLast(1).joinToString(".")
    }
    if (tld == null) {
        tld = "com"
        sld = text
    }
    // 只输入后缀（「com」「xyz」）是合法请求：用户想看这个后缀全网的价格，
    // 此时没有域名主体，也就没有 RDAP 可查。上面那个循环会在 i == 0 命中已知后缀，
    // 把 sld 切成空串，所以「sld 为空」就是"纯后缀查询"。
    val suffixOnly = sld.isEmpty()
    require(suffixOnly || sld.matches(SLD_PATTERN)) {
        "域名主体不合法（只支持字母、数字、连字符）"
    }
    require(tld.matches(TLD_PATTERN)) { "域名后缀不合法" }
    return DomainQuery(
        host = text,
        sld = sld,
        tld = tld,
        domain = if (sld.isEmpty()) ".$tld" else "$sld.$tld",
        explicit = explicit || suffixOnly
    )
}
