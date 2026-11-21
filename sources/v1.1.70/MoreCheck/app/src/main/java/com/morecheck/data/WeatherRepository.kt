package com.morecheck.data

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.morecheck.util.AppPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.LocalDate
import java.util.Locale

/** 未来某一天的预报。 */
data class DailyForecast(
    /** 「今天 / 明天 / 周三」这类给人看的短标签。 */
    val label: String,
    /** 「10/07」这样的月日，便于核对是哪一天。 */
    val dateLabel: String,
    /** 当天的天气现象（中文）。 */
    val condition: String,
    /**
     * 当天的 WMO 天气码（原始值，未识别时为 -1）。
     *
     * 为什么把原始码一路带到界面、而不是让界面拿 [condition] 反查图标：
     * 中文串是**展示用**的产物，一旦以后想把「阵雨」改成「雷阵雨」、
     * 或者加一档「冻雨」，图标映射就会因为字符串对不上而静默退回「未知」图标——
     * 这种 bug 不会编译报错，只会让用户看到一把伞都不对的天气。
     * 携带数字码后，代码与图标的对应关系是**同一份 when 的两侧**，改名不会脱钩。
     */
    val weatherCode: Int,
    val maxTemperature: Double,
    val minTemperature: Double
)

/** 一次查询的完整结果：当前天气 + 未来 5 天预报。 */
data class WeatherInfo(
    /** 城市名；逆地理编码拿不到时是「当前位置」。手动选择城市时是「广州-荔湾」这类手拼标签。 */
    val placeName: String,
    val temperature: Double,
    val apparentTemperature: Double,
    val humidity: Int,
    val windSpeed: Double,
    val condition: String,
    /**
     * 当前天气的 WMO 码（未识别为 -1），用途同 [DailyForecast.weatherCode]。
     * 界面用它给大号温度配一个天气图标。
     */
    val weatherCode: Int,
    /** 未来 5 天预报，顺序即接口返回顺序（第 0 天是今天）。 */
    val daily: List<DailyForecast>
)

/**
 * 天气查询的结果。用 `sealed interface` 而不是「异常 + 可空值」，
 * 是为了让每种失败都**必须**被界面显式处理：
 * 未授权要引导去开权限，定位失败和网络失败要给不同的重试建议，
 * 用 `Throwable` 传递的话界面只能猜，很容易把「没授权」显示成「没网」。
 */
sealed interface WeatherResult {

    data class Success(val weather: WeatherInfo) : WeatherResult

    /** 没有定位权限（自查未授予，或平台直接抛 [SecurityException]）。 */
    data object PermissionDenied : WeatherResult

    /** 有权限但拿不到坐标（定位服务关闭、没有缓存、设备不支持）。 */
    data class LocationFailed(val reason: String) : WeatherResult

    /** 联网失败或响应不可解析。 */
    data class NetworkFailed(val reason: String) : WeatherResult
}

/**
 * 正地理编码搜到的一个候选。
 *
 * ## 为什么按**行政层级**拆字段（省 / 市 / 区），而不是只留一个 `name`
 *
 * 界面要把地名拼成「**广东-广州-荔湾**」这种**省在前、市在后**的顺序（需求），
 * 而两个数据源给的层级并不一样（见 [WeatherRepository.searchCities] 的注释）：
 * Open-Meteo 给 `admin1`(省) / `admin2`(市)，Photon 给 `state`(省) / `city`(市) / `county`(区)。
 * 把「哪一段是省、哪一段是市」在解析时就定下来，拼接处才能只做一件事：按层级排好。
 *
 * 反过来说，**靠字符串猜层级一定出错**：中文地名里「以『市』结尾就是市」看着成立，
 * 但「广州」不以「市」结尾却是个市，而「市」也可能是名字的一部分。
 *
 * @property name 地名原文，直接用作用户看到的搜索结果主标题（如「荔湾区」）。
 * @property province 省级（省 / 自治区 / 直辖市）；数据源没给就是空串。
 * @property city 地级市；数据源没给就是空串。
 * @property district 区 / 县 / 县级市；数据源没给就是空串。
 * @property country 国家，如「中国」。
 */
data class CityMatch(
    val name: String,
    val province: String,
    val city: String,
    val district: String,
    val country: String,
    val latitude: Double,
    val longitude: Double
) {
    /**
     * 天气页标题用的地名，如「广东-广州-荔湾」。
     *
     * 做成成员函数而不是让界面自己拼：界面只该关心「拿这个标签显示」，
     * 层级顺序与后缀裁剪的规则留在数据层一处，两个调用点（列表预览与天气标题）不会走样。
     */
    fun placeLabel(): String = composePlaceLabel(province, city, district)

    /**
     * 搜索结果列表的副标题：`省 · 市 · 区`，**同样省在前**。
     *
     * 三个字段都可能缺（境外地点往往只有 `state`），所以过滤空串再去重；
     * 全空时退回国家名，至少让用户知道这条结果在哪个国家。
     * 「中国」不重复拼进副标题——它是最常见的情况，写出来只是噪音。
     */
    fun regionText(): String {
        val parts = listOf(province, city, district)
            .filter { it.isNotBlank() }
            .distinct()
        if (parts.isNotEmpty()) {
            return if (country.isNotBlank() && country != DEFAULT_COUNTRY) {
                parts.joinToString(" · ") + " · " + country
            } else {
                parts.joinToString(" · ")
            }
        }
        return country
    }
}

/**
 * 城市搜索的结果。
 *
 * 「没搜到」为什么单独一个 [Empty] 而不是算作失败：空结果**不是错误**，
 * 是用户输入了不存在的地名（或写了错别字）之后的正常反馈，
 * 文案应该是「没有找到匹配的城市，换个说法试试」而不是「网络异常」——
 * 混在一起会让人去检查 WiFi，而问题其实在输入框里。
 */
sealed interface CitySearchResult {

    data class Success(val matches: List<CityMatch>) : CitySearchResult

    /** 接口通了但零结果。 */
    data object Empty : CitySearchResult

    data class Failed(val reason: String) : CitySearchResult
}

/**
 * 「实验室 → 天气」的数据来源。
 *
 * 三条硬性约束决定了这里的写法：
 * 1. **不引入任何网络库**。项目里没有 Retrofit / OkHttp，为几个只读 GET 请求
 *    拉一整套依赖（体积、R8 规则、版本对齐、传递依赖）不划算，
 *    因此直接用平台自带的 [HttpURLConnection] 与 `org.json`（两者都在 android.jar 里）。
 * 2. **异常一律不外抛**。定位与联网会碰到权限被撤销、飞行模式、服务端限流等一堆
 *    不可控情况；让异常冒到 Compose 里只会变成崩溃，而不是「一句用户看得懂的提示」。
 *    所以对外只有 [WeatherResult] / [CitySearchResult] 两种出口。
 * 3. **手动查天气不碰定位、不碰权限**。用户能用上手动选择，
 *    恰恰是因为自动定位已经失败了；如果这条路径也要求权限或开关，
 *    就等于没有兜底。
 *
 * 三类请求共用一个 [readUrl]（同样的超时、同样的「先看状态码再读流」），
 * 三个 URL 各自独立拼接，避免为了省几行把不同参数体系的接口揉进一个函数。
 */
class WeatherRepository(private val context: Context) {

    // ---- 对外 API ----------------------------------------------------------

    /**
     * 自动定位取一次天气。调用方**不需要**自己切线程，内部已经在 IO 上执行。
     *
     * 顺序是先定位再请求天气：定位很快且失败原因最需要区分（没权限 / 没信号），
     * 先做它可以避免「明明没权限却先去连网络、最后报网络错」的误导性结果。
     */
    suspend fun loadWeather(): WeatherResult = withContext(Dispatchers.IO) {
        when (val located = resolveCoordinates()) {
            CoordinatesOutcome.Denied -> WeatherResult.PermissionDenied
            is CoordinatesOutcome.Unavailable -> WeatherResult.LocationFailed(located.reason)
            is CoordinatesOutcome.Found -> fetchWeather(
                latitude = located.latitude,
                longitude = located.longitude,
                // 逆编码只在这条自动路径上做：手动选择时地名由用户选中的那条搜索结果决定，
                // 再发一次逆编码既慢又可能把「荔湾」覆盖成「广州」。
                placeName = resolvePlaceName(located.latitude, located.longitude)
            )
        }
    }

    /**
     * 查指定坐标的天气（手动选择城市走这条）。
     *
     * 刻意**不做**任何权限 / 开关判断、也不读定位：这是定位失败后的兜底路径，
     * 一旦在这里加门控，用户在「没授权」时点「手动选择城市」会立刻又看到一次同样的失败提示。
     * [placeName] 由调用方给（来自 [CityMatch]），因此这里也不做逆地理编码。
     */
    suspend fun loadWeatherAt(
        latitude: Double,
        longitude: Double,
        placeName: String
    ): WeatherResult = withContext(Dispatchers.IO) {
        fetchWeather(
            latitude = latitude,
            longitude = longitude,
            placeName = placeName.ifBlank { DEFAULT_PLACE_NAME }
        )
    }

    /**
     * 按地名正查候选城市（手动选择城市对话框里的「搜索」）。
     *
     * 同样是纯网络查询，无权限前置条件。返回 [CitySearchResult] 而不是
     * `List<CityMatch>`，是因为「零结果」和「请求失败」必须能让界面分开说话（见该接口的注释）。
     *
     * ## 为什么不是「把用户输入直接丢给一个接口」
     *
     * 用户会连着写整个地址：「广东省佛山市」「广州市荔湾区」。
     * 实测**没有任何地理库把这种拼接串当地名**——Open-Meteo 对这两个串都返回零结果，
     * Photon（拿拼接串去查）返回的是一堆同名的公司 / 医院 POI，而不是那个行政区。
     * 所以这里先把输入拆成行政段、**从最具体的一段开始逐级退让**（见 [queryCandidates]）。
     *
     * ## 为什么是两个数据源
     *
     * - **Open-Meteo** 作主力：它对单个市名很准（「佛山市」直接命中佛山），
     *   而且已经在用，换掉等于把没坏的路径也一起改。
     *   实测它有个硬限制：底库**只到市级（ADM2）**，没有区县级——
     *   「荔湾区」查不到，退一步的「荔湾」反而命中的是**广西玉林**的一个村。
     * - **Photon**（<https://photon.komoot.io>，OSM 底库，免费无 key）作兜底：
     *   它有区县级行政区，实测「荔湾区」第一条就是**广州的荔湾区**，
     *   `city=广州市` / `state=广东省` 都对得上。
     *   只在 Open-Meteo 全线零结果时才问它，正常查询的耗时不增加。
     *
     * ⚠️ Photon 的 `lang` 参数**不支持 `zh`**（传了直接 400），
     * 但不传时返回的就是 OSM 里的本地名称，中文地名回来仍是中文，所以不传。
     */
    suspend fun searchCities(query: String): CitySearchResult = withContext(Dispatchers.IO) {
        val candidates = queryCandidates(query)
        // 空串直接返回空结果而不是发请求：接口对空 name 会返回 400 / 400，
        // 那会被翻译成「网络异常」，与用户实际做错的事（没输入）完全对不上。
        if (candidates.isEmpty()) return@withContext CitySearchResult.Empty

        var lastFailure: String? = null
        // 是否**至少有一次请求是通的**（哪怕零结果）。用来区分「没搜到」与「网络坏了」：
        // 两者给用户的建议完全不同，见 [CitySearchResult] 的注释。
        var anyResponse = false

        for (candidate in candidates) {
            when (val outcome = runOpenMeteoSearch(candidate)) {
                is SearchOutcome.Found -> return@withContext CitySearchResult.Success(outcome.matches)
                SearchOutcome.ZeroResults -> anyResponse = true
                is SearchOutcome.Failure -> lastFailure = outcome.reason
            }
        }

        when (val outcome = runPhotonSearch(candidates.first())) {
            is SearchOutcome.Found -> return@withContext CitySearchResult.Success(outcome.matches)
            SearchOutcome.ZeroResults -> anyResponse = true
            is SearchOutcome.Failure -> lastFailure = lastFailure ?: outcome.reason
        }

        if (anyResponse) {
            CitySearchResult.Empty
        } else {
            CitySearchResult.Failed(lastFailure ?: "城市搜索失败，请稍后重试")
        }
    }

    // ---- 天气请求 ----------------------------------------------------------

    private fun fetchWeather(
        latitude: Double,
        longitude: Double,
        placeName: String
    ): WeatherResult {
        val forecastBody = runCatching {
            readUrl(forecastUrl(latitude, longitude))
        }.getOrElse { throwable ->
            // 网络类的异常全部在这里转成中文原因，不向上抛。
            return WeatherResult.NetworkFailed(networkReasonOf(throwable))
        }

        val weather = runCatching {
            parseWeather(forecastBody, placeName)
        }.getOrElse {
            // 字段缺失 / JSON 结构变化：同样是「这次没拿到」，不该让应用崩。
            return WeatherResult.NetworkFailed("天气数据解析失败，请稍后重试")
        }
        return WeatherResult.Success(weather)
    }

    private fun forecastUrl(latitude: Double, longitude: Double): String = buildString {
        append(FORECAST_ENDPOINT)
        append("?latitude=").append(coordinate(latitude))
        append("&longitude=").append(coordinate(longitude))
        append(
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
                "weather_code,wind_speed_10m"
        )
        append("&daily=weather_code,temperature_2m_max,temperature_2m_min")
        // timezone=auto 让 daily 的日期按**当地**时区切分，
        // 否则靠近换日线时会整体错一天。
        append("&timezone=auto&forecast_days=5")
    }

    // ---- 地名解析（逆地理编码 + 行政区后缀处理） -----------------------------

    /**
     * 反查「城市-区县」标签，拿不到就用 [DEFAULT_PLACE_NAME]。
     *
     * ⚠️ 这里换掉了最初那版实现。原写法打的是 Open-Meteo 的 geocoding 接口
     * （`/v1/search`），但它**只支持按 `name` 正查**：带 `latitude`/`longitude`
     * 过去会直接返回 HTTP 400 `No value found ... at path 'name'`（官方文档里
     * `name` 也是 Required），所以那版永远只能显示兜底文案。
     * 现在改用 BigDataCloud 的 `/data/reverse-geocode-client`：**免费、无需 API key**
     * （带 key 的版本要先注册，往一个天气小组件里塞注册流程不现实），
     * 返回里同时带 `city` / `locality` / `principalSubdivision`
     * 和 `localityInfo.administrative` 数组，正好够拼出「广州-荔湾」。
     *
     * ## `localityLanguage` 必须写 `zh-Hans`，不能写 `zh`（实测踩到过）
     *
     * 该接口的 `zh` 回的是**繁体**。实测广州坐标（23.1291, 113.2644）：
     * - `zh` / `zh-CN` → `city=广州市`、`locality=越秀區`（**同一个响应里简繁混排**）
     * - `zh-Hans` → `city=广州市`、`locality=越秀区`（统一简体）
     *
     * 混排的后果很难看：`locality` 的繁体字过不了简体后缀裁剪表，会拼出
     * 「广州-越秀區」这种一半简体一半繁体的地名——需求给的样例是「广州-荔湾」，
     * 所以这里明确要简体。改语言参数比在本地维护一张繁简对照表干净得多
     * （后者既不可能列全，也等于把第三方数据的脏话揽到自己身上）。
     *
     * 整段**不允许抛**：地名只是锦上添花，任何失败都静默退回兜底值，
     * 绝不能因为第三方地名服务抽风就让整次天气查询失败。
     *
     * ## 三级各自的来源（v1.1.64 起改为「省-市-区」，省在前）
     *
     * - **省** = `principalSubdivision`（「广东省」）
     * - **市** = `city`（「广州市」）；缺省时退回省名或 `locality`，保证市一级尽量有值
     * - **区** = `locality`（「越秀区」）；缺省时从 `localityInfo.administrative` 里
     *   倒着找最贴近用户的那一级
     *
     * **后缀裁剪交给 [composePlaceLabel]，这里只负责把原始字段送到正确的层级上**——
     * 层级判断一旦掺进裁剪逻辑就会出「广州→广」那类事故（见该函数的注释）。
     */
    private fun resolvePlaceName(latitude: Double, longitude: Double): String {
        val body = runCatching { readUrl(reverseGeocodeUrl(latitude, longitude)) }.getOrNull()
            ?: return DEFAULT_PLACE_NAME

        return runCatching {
            val root = JSONObject(body)
            val province = root.optString("principalSubdivision")
            val locality = root.optString("locality")
            val city = firstNonBlank(root.optString("city"), province, locality)
            val district = firstNonBlank(locality, deepestAdministrativeName(root))
            composePlaceLabel(province = province, city = city, district = district)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_PLACE_NAME
    }

    /**
     * 从 `localityInfo.administrative` 里挑一个当区县名。
     *
     * **倒着找**：该数组按 `adminLevel` 从小到大排（国家级在前、最细的街道/区县在后），
     * 我们想要的是**最贴近用户**的那一级，所以从末尾往前取第一个非空 name。
     * 元素结构官方不保证，因此逐个字段判类型与空值，不假设 `name` 一定存在。
     *
     * 这里**不再裁后缀**（原实现调了通用的 `cleanPart`）：层级化的裁法在
     * [composePlaceLabel] 里按「区县级」的后缀表统一处理，避免同一个名字在两个地方
     * 被两套规则裁成两个样子。
     */
    private fun deepestAdministrativeName(root: JSONObject): String {
        val entries = root.optJSONObject("localityInfo")
            ?.optJSONArray("administrative")
            ?: return ""
        for (index in entries.length() - 1 downTo 0) {
            val name = entries.optJSONObject(index)?.optString("name").orEmpty().trim()
            if (name.isNotEmpty()) return name
        }
        return ""
    }

    private fun reverseGeocodeUrl(latitude: Double, longitude: Double): String = buildString {
        append(REVERSE_GEOCODE_ENDPOINT)
        append("?latitude=").append(coordinate(latitude))
        append("&longitude=").append(coordinate(longitude))
        append("&localityLanguage=zh-Hans")
    }

    // ---- 城市搜索（正地理编码） ----------------------------------------------

    /** 一次搜索请求的三种结局。内部类型，不外泄给界面。 */
    private sealed interface SearchOutcome {
        data class Found(val matches: List<CityMatch>) : SearchOutcome

        /** 请求通了但服务端没给任何可用结果。 */
        data object ZeroResults : SearchOutcome

        data class Failure(val reason: String) : SearchOutcome
    }

    private fun runOpenMeteoSearch(keyword: String): SearchOutcome {
        val body = runCatching { readUrl(openMeteoSearchUrl(keyword)) }.getOrElse { throwable ->
            return SearchOutcome.Failure(networkReasonOf(throwable))
        }
        return runCatching { parseOpenMeteoMatches(body) }.getOrElse {
            SearchOutcome.Failure("城市搜索失败，请稍后重试")
        }
    }

    /**
     * Photon 兜底查询。见 [searchCities] 的 KDoc：
     * 它的底库含区县级行政区，用来补 Open-Meteo「只有市级」的缺口。
     */
    private fun runPhotonSearch(keyword: String): SearchOutcome {
        val body = runCatching { readUrl(photonSearchUrl(keyword)) }.getOrElse { throwable ->
            return SearchOutcome.Failure(networkReasonOf(throwable))
        }
        return runCatching { parsePhotonMatches(body) }.getOrElse {
            SearchOutcome.Failure("城市搜索失败，请稍后重试")
        }
    }

    private fun openMeteoSearchUrl(keyword: String): String = buildString {
        append(CITY_SEARCH_ENDPOINT)
        append("?name=").append(URLEncoder.encode(keyword, Charsets.UTF_8.name()))
        append("&count=").append(CITY_SEARCH_COUNT)
        append("&language=zh")
        append("&format=json")
    }

    /**
     * Photon 的查询串。
     *
     * ⚠️ **不能带 `lang=zh`**：Photon 只支持 `default / de / en / fr`，
     * 传 `zh` 会直接返回 HTTP 400（错误体里写着 `Language is not supported`）。
     * 不传时返回的是 OSM 里的本地名称，中文地名回来依旧是中文，正是我们要的。
     */
    private fun photonSearchUrl(keyword: String): String = buildString {
        append(PHOTON_ENDPOINT)
        append("?q=").append(URLEncoder.encode(keyword, Charsets.UTF_8.name()))
        append("&limit=").append(CITY_SEARCH_COUNT)
    }

    /**
     * 解析 Open-Meteo 的搜索结果。
     *
     * 字段到层级的映射：`admin1` → 省、`admin2` → 市、`name` → 命中地名本身。
     * Open-Meteo **没有区县级的 `admin3`**，所以 `district` 一律留空；
     * 命中地名的下一级就是市（例如「佛山市」），此时 `city` 与 `name` 会重名，
     * 交给 [composePlaceLabel] 去重。
     */
    private fun parseOpenMeteoMatches(body: String): SearchOutcome {
        val results = JSONObject(body).optJSONArray("results")
        val matches = buildList {
            for (index in 0 until (results?.length() ?: 0)) {
                val item = results?.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val latitude = item.optDouble("latitude", Double.NaN)
                val longitude = item.optDouble("longitude", Double.NaN)
                // 没有坐标的候选点上去也查不了天气，直接丢掉，免得用户点了没反应。
                if (name.isEmpty() || !latitude.isFinite() || !longitude.isFinite()) continue

                val province = item.optString("admin1").trim()
                // `admin2` 缺省时（直辖市、或只到省级的条目）退回用命中地名当市级，
                // 这样「上海」这类查询拼出来仍是「上海」而不是「上海-上海」——去重在拼接处做。
                val city = firstNonBlank(item.optString("admin2").trim(), name)
                add(
                    CityMatch(
                        name = name,
                        province = province,
                        city = city,
                        district = "",
                        country = item.optString("country").trim(),
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            }
        }
        return matches.take(CITY_SEARCH_COUNT).toOutcome()
    }

    /**
     * 解析 Photon 的搜索结果。
     *
     * ## 层级映射（中文数据的实测规律）
     *
     * Photon 的字段名是**英文行政级别**，对中文数据来说是：
     * - `state` → 省（「广东省」）
     * - `city` → 地级市（「广州市」）
     * - `county` → 区 / 县（「顺德区」）——注意**不是**地名里的“县”，而是区县级
     * - `district` → 街道 / 镇（「勒流街道」），**比我们要的粒度更细，不用它**
     *
     * 所以区县一级取 `county`。但当命中的**就是**区县本身（例如搜「荔湾区」，
     * 第一条是 `osm_key=place, type=district` 的荔湾区）时，没有 `county` 字段，
     * 此时用命中地名当区县——否则「荔湾区」会被拼成只剩「广东-广州」。
     *
     * ## 排序：行政区优先、中国优先
     *
     * Photon 对「广州市荔湾区」这种串返回的前几条是同名的公司 / 医院 POI。
     * 它们坐标仍在荔湾区内（查天气是准的），但拿 POI 当搜索结果很难看，
     * 所以把 `osm_key == "place"` 的行政区排到前面；再让中国的结果靠前
     * （中文输入下几乎必然是中国，这一步主要是防御）。
     */
    private fun parsePhotonMatches(body: String): SearchOutcome {
        val features = JSONObject(body).optJSONArray("features")
        val parsed = buildList {
            for (index in 0 until (features?.length() ?: 0)) {
                val feature = features?.optJSONObject(index) ?: continue
                val props = feature.optJSONObject("properties") ?: continue
                val name = props.optString("name").trim()
                if (name.isEmpty()) continue

                // geometry.coordinates 是 GeoJSON 的 [经度, 纬度] 顺序，**不能反着取**。
                val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                val longitude = coordinates?.optDouble(0, Double.NaN) ?: Double.NaN
                val latitude = coordinates?.optDouble(1, Double.NaN) ?: Double.NaN
                if (!latitude.isFinite() || !longitude.isFinite()) continue

                val isAdministrative = props.optString("osm_key") == "place"
                val isDistrictItself = isAdministrative && props.optString("type") == "district"
                val county = props.optString("county").trim()
                add(
                    PhotonHit(
                        match = CityMatch(
                            name = name,
                            province = props.optString("state").trim(),
                            city = firstNonBlank(props.optString("city").trim(), county),
                            // 只有「命中的就是区县本身」时才拿 name 当区县；
                            // 否则一条 POI 的 name（如「荔湾区口腔医院」）绝不该被当成行政区名。
                            district = if (county.isNotEmpty()) county else if (isDistrictItself) name else "",
                            country = props.optString("country").trim(),
                            latitude = latitude,
                            longitude = longitude
                        ),
                        administrative = isAdministrative,
                        domestic = props.optString("countrycode") == CHINA_COUNTRY_CODE
                    )
                )
            }
        }
        val matches = parsed
            .sortedWith(compareByDescending<PhotonHit> { it.domestic }.thenByDescending { it.administrative })
            .map { it.match }
        return matches.take(CITY_SEARCH_COUNT).toOutcome()
    }

    /** Photon 的原始命中 + 两条排序键（排序后就被丢掉，不进 [CityMatch]）。 */
    private data class PhotonHit(
        val match: CityMatch,
        val administrative: Boolean,
        val domestic: Boolean
    )

    /** 统一的收口：非空才算 [SearchOutcome.Found]，否则是 [SearchOutcome.ZeroResults]。 */
    private fun List<CityMatch>.toOutcome(): SearchOutcome =
        if (isEmpty()) SearchOutcome.ZeroResults else SearchOutcome.Found(this)

    // ---- HTTP --------------------------------------------------------------

    /**
     * 发一个 GET 并把响应体读成字符串。
     *
     * 两个超时都设：移动网络下「TCP 连上了但不回数据」很常见，
     * 只设 connect 会让协程一直卡在 read 上直到系统级超时（可能几十秒），
     * 用户看到的就是无休止转圈。10 秒是「够慢网络传完几 KB JSON」与
     * 「用户还愿意等」之间的折中。
     */
    private fun readUrl(url: String): String {
        // 用 URI.toURL() 而不是 URL(String) 构造器：后者在较新的 JDK/Android 上已被标记弃用。
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            // 先判状态码再读流：直接读 4xx/5xx 的 inputStream 会抛
            // FileNotFoundException，最后只能显示一句没有信息量的「网络异常」。
            // 转成明确的 HTTP 码后，排查时一眼能看出是限流还是参数写错。
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            // 无论成功还是抛异常都要断开：HttpURLConnection 底层是连接池，
            // 不 disconnect 的话异常路径上的连接会一直被挂着，反复查询容易耗尽。
            connection.disconnect()
        }
    }

    /** 把网络异常翻译成用户能看懂的一句话。 */
    private fun networkReasonOf(throwable: Throwable): String = when (throwable) {
        is SocketTimeoutException -> "连接服务超时，请检查网络后重试"
        is UnknownHostException -> "网络不可用，请检查网络连接后重试"
        is IOException -> "网络异常，请检查网络后重试"
        is IllegalStateException -> "服务暂时不可用，请稍后重试"
        else -> "请求失败，请稍后重试"
    }

    // ---- 定位 --------------------------------------------------------------

    /** 定位的三种结局：拿到坐标 / 没权限 / 有权但拿不到。 */
    private sealed interface CoordinatesOutcome {
        data class Found(val latitude: Double, val longitude: Double) : CoordinatesOutcome
        data object Denied : CoordinatesOutcome
        data class Unavailable(val reason: String) : CoordinatesOutcome
    }

    /**
     * 读取「最后一次已知位置」。
     *
     * 为什么不用 `requestLocationUpdates`：它要注册监听、要等回调、要在页面销毁时
     * `removeUpdates`，稍一疏漏就是长期耗电（本项目也没有 ViewModel 生命周期兜底）；
     * 而 `getLastKnownLocation` 是同步查询，拿到就返回，**不需要任何注销**。
     * 代价是缓存可能偏旧（室内或刚关机重启时可能是几小时前的），
     * 但天气本来就按城市粒度计算，几公里 / 几十分钟的偏差对结果没有影响。
     */
    private fun resolveCoordinates(): CoordinatesOutcome {
        // 权限可能在页面停留期间被用户撤销，先自查一次能直接给出「未授权」，
        // 而不是等平台抛 SecurityException。权限清单复用 [AppPermission.LOCATION]，
        // 避免这里和权限页各维护一份、改一处漏一处。
        val granted = AppPermission.LOCATION.manifestPermissions.orEmpty().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return CoordinatesOutcome.Denied

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return CoordinatesOutcome.Unavailable("此设备没有定位服务")

        for (provider in PROVIDER_PRIORITY) {
            // 每个 provider 单独 try/catch：provider 不存在（例如没有 GPS 芯片）
            // 或当前不可用时 getLastKnownLocation 都会抛，一个坏 provider
            // 不能带崩整轮尝试，继续试下一个即可。
            val attempt = runCatching { manager.getLastKnownLocation(provider) }

            // 权限刚被撤销时平台会抛 SecurityException——必须映射成「未授权」，
            // 而不是笼统的「定位失败」，否则界面会引导用户去空旷处而不是去开权限。
            if (attempt.exceptionOrNull() is SecurityException) return CoordinatesOutcome.Denied

            val location = attempt.getOrNull() ?: continue
            if (!location.latitude.isFinite() || !location.longitude.isFinite()) continue
            return CoordinatesOutcome.Found(location.latitude, location.longitude)
        }
        return CoordinatesOutcome.Unavailable("暂时取不到位置，请稍后重试")
    }

    // ---- 解析 --------------------------------------------------------------

    private fun parseWeather(
        forecastJson: String,
        placeName: String
    ): WeatherInfo {
        val root = JSONObject(forecastJson)
        val current = root.optJSONObject("current") ?: error("响应缺少 current")
        val daily = root.optJSONObject("daily") ?: error("响应缺少 daily")

        val dates = daily.optJSONArray("time")
        val codes = daily.optJSONArray("weather_code")
        val highs = daily.optJSONArray("temperature_2m_max")
        val lows = daily.optJSONArray("temperature_2m_min")

        // 取四个数组里最短的长度：接口哪天少给一天也不至于越界崩溃。
        val dayCount = listOfNotNull(dates, codes, highs, lows).minOfOrNull { it.length() } ?: 0
        val forecasts = (0 until dayCount).map { index ->
            val isoDate = dates?.optString(index).orEmpty()
            // 码缺失时给 -1（而不是 0）：0 是「晴」，猜成晴天属于编造数据，
            // -1 会走到「未知」，界面就画一个中性图标、写「未知」。
            val code = codes?.optInt(index, UNKNOWN_WEATHER_CODE) ?: UNKNOWN_WEATHER_CODE
            DailyForecast(
                label = dayLabel(index, isoDate),
                dateLabel = monthDayLabel(isoDate),
                condition = weatherConditionOf(code),
                weatherCode = code,
                maxTemperature = highs?.optDouble(index, Double.NaN) ?: Double.NaN,
                minTemperature = lows?.optDouble(index, Double.NaN) ?: Double.NaN
            )
        }

        val currentCode = current.optInt("weather_code", UNKNOWN_WEATHER_CODE)
        return WeatherInfo(
            placeName = placeName,
            temperature = current.optDouble("temperature_2m", Double.NaN),
            apparentTemperature = current.optDouble("apparent_temperature", Double.NaN),
            humidity = current.optInt("relative_humidity_2m", 0),
            windSpeed = current.optDouble("wind_speed_10m", Double.NaN),
            condition = weatherConditionOf(currentCode),
            weatherCode = currentCode,
            daily = forecasts
        )
    }

    /** 第 0/1 天说「今天 / 明天」，其余用星期；解析不出日期就退回空串。 */
    private fun dayLabel(index: Int, isoDate: String): String = when (index) {
        0 -> "今天"
        1 -> "明天"
        else -> runCatching {
            WEEKDAY_LABELS[LocalDate.parse(isoDate).dayOfWeek.value - 1]
        }.getOrDefault("")
    }

    private fun monthDayLabel(isoDate: String): String = runCatching {
        val date = LocalDate.parse(isoDate)
        "${date.monthValue}/${date.dayOfMonth}"
    }.getOrDefault(isoDate)

    /**
     * 经纬度进 URL 前统一用 [Locale.US] 格式化。
     *
     * 不能省这个 Locale：`String.format` 默认跟随系统 Locale，
     * 而部分欧洲语言的小数点是逗号，直接拼进 query 会得到 `latitude=39,9`，
     * 服务端只会回一个 400。
     */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

    private companion object {
        const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"

        /** 逆地理编码：BigDataCloud 的免 key 客户端接口。 */
        const val REVERSE_GEOCODE_ENDPOINT =
            "https://api.bigdatacloud.net/data/reverse-geocode-client"

        /** 正地理编码：Open-Meteo 的城市搜索。 */
        const val CITY_SEARCH_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"

        /** 逆地理编码不可用时的兜底地名。 */
        const val DEFAULT_PLACE_NAME = "当前位置"

        /** 搜索候选条数：5 条足够覆盖重名城市，再多对话框就得滚动。 */
        const val CITY_SEARCH_COUNT = 5

        /**
         * Photon 的搜索端点（OSM 底库，**免费且无需 key**）。
         *
         * 只作 [searchCities] 的**兜底**：Open-Meteo 的底库没有区县级行政区，
         * 「荔湾区」这类查询必须靠它。见 [searchCities] 的注释。
         */
        const val PHOTON_ENDPOINT = "https://photon.komoot.io/api/"

        /** 国家代码里的中国，用来把境外结果排到后面。 */
        const val CHINA_COUNTRY_CODE = "CN"

        /** 拿不到天气码时的哨兵值，与任何 WMO 码都不冲突。 */
        const val UNKNOWN_WEATHER_CODE = -1

        const val TIMEOUT_MS = 10_000

        /** 依次尝试的 provider：GPS 最准，网络次之，被动缓存最后兜底。 */
        val PROVIDER_PRIORITY = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}

/** 周一 ~ 周日，下标即 `DayOfWeek.value - 1`。 */
private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 「中国」的写法。
 *
 * 提到顶层是因为 [CityMatch.regionText] 要用它把最常见的国家名从副标题里省掉；
 * 那个函数是 `CityMatch` 的成员，够不到 `WeatherRepository` 的私有 companion。
 */
private const val DEFAULT_COUNTRY = "中国"

private fun firstNonBlank(vararg values: String): String =
    values.firstOrNull { it.isNotBlank() }.orEmpty()

/**
 * 把「省 / 市 / 区」拼成「**广东-广州-荔湾**」——**省在最前、市在其次**（需求）。
 *
 * ## 为什么按层级裁后缀，而不是用一张通用后缀表
 *
 * 这里最关键的一点：**裁后缀必须知道自己在裁哪一级**。
 *
 * 之前用的是一张「什么后缀都认」的表，里面含「州」——那正好是**「广州」的结尾**，
 * 所以「广州」被裁一次就变成「广」。这种 bug 只在部分城市名上出现，抽查很难碰到。
 * 现在每级只看**属于自己那一级的后缀**：
 * - 省级：`自治区` / `特别行政区` / `省` / `市`
 * - 市级：`自治州` / `地区` / `盟` / `市` —— **刻意不含 `州`**
 * - 区县级：`自治县` / `自治旗` / `新区` / `区` / `县` / `市` / `旗`
 *
 * 于是「广州」做市级时原样保留（它不以 `市` 结尾），
 * 「广东省」→「广东」、「荔湾区」→「荔湾」、「浦东新区」→「浦东」（`新区` 比 `区` 长，先匹配）。
 *
 * ## 去重
 *
 * 直辖市的省与市常常同值（`上海市` + `上海市`），命中地名的下一级也可能和市级同名
 * （搜「佛山市」时 `name` 与 `city` 都是佛山）。统一用 `distinct()` 收口：
 * 于是「上海」只显示一次、「广东-佛山」不会变成「广东-佛山-佛山」。
 *
 * ## 退化情况
 *
 * 三级都可能缺（境外地点常常只有省一级），空串直接丢掉；
 * 三级全空返回空串，由调用方回退到兜底地名。
 */
internal fun composePlaceLabel(province: String, city: String, district: String): String =
    listOf(
        normalizeProvince(province),
        stripAdminSuffix(city, CITY_LEVEL_SUFFIXES),
        stripAdminSuffix(district, DISTRICT_LEVEL_SUFFIXES)
    )
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("-")

/**
 * 省级名称归一化。
 *
 * 五个民族自治区的全名裁掉「自治区」后会剩下民族名（「广西壮族」），读着像半截话，
 * 所以**整名换成简称**；其余省份按常规后缀裁（「广东省」→「广东」、「上海市」→「上海」）。
 */
private fun normalizeProvince(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    AUTONOMOUS_REGION_SHORT_NAMES[trimmed]?.let { return it }
    return stripAdminSuffix(trimmed, PROVINCE_LEVEL_SUFFIXES)
}

/**
 * 裁掉**一个**属于该层级的后缀。
 *
 * 两点保护：
 * - 只裁一个（`removeSuffix` 只命中一次），不会把名字越裁越短；
 * - 裁完不能是空串（`trimmed.length > suffix.length`）——否则接口只回一个「市」时
 *   我们会把它裁成空，整段地名就丢了。
 */
private fun stripAdminSuffix(raw: String, suffixes: List<String>): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val hit = suffixes.firstOrNull { trimmed.endsWith(it) && trimmed.length > it.length } ?: return trimmed
    return trimmed.removeSuffix(hit).trim()
}

/**
 * 省级后缀。**长后缀必须在前**，否则「内蒙古自治区」会被「区」先咬掉一半。
 */
private val PROVINCE_LEVEL_SUFFIXES = listOf("特别行政区", "自治区", "省", "市")

/** 五个民族自治区：整名换简称，避免裁出「广西壮族」这种半截名字。 */
private val AUTONOMOUS_REGION_SHORT_NAMES = mapOf(
    "内蒙古自治区" to "内蒙古",
    "广西壮族自治区" to "广西",
    "西藏自治区" to "西藏",
    "宁夏回族自治区" to "宁夏",
    "新疆维吾尔自治区" to "新疆"
)

/**
 * 市级后缀。
 *
 * ⚠️ **刻意不含 `州`**：`广州` / `杭州` / `苏州` / `扬州` 都以「州」结尾，
 * 把它当后缀裁一次就得到「广」「杭」「苏」。只有「自治州」这个**完整词**才能裁。
 */
private val CITY_LEVEL_SUFFIXES = listOf("自治州", "地区", "盟", "市")

/**
 * 区县级后缀。「新区」必须排在「区」前面，否则「浦东新区」会变成「浦东新」。
 */
private val DISTRICT_LEVEL_SUFFIXES = listOf("自治县", "自治旗", "新区", "区", "县", "市", "旗")

/**
 * 把用户输入拆成若干个**可以直接拿去查**的候选词，从最具体到最笼统。
 *
 * 用户会连着写整个地址：「广东省佛山市」「广州市荔湾区」，而
 * **没有任何地理库把这种拼接串当地名**，所以必须先拆开。拆法是按行政区后缀切段，
 * 再把**从末尾往前累计**的几种组合依次作为候选（**少的在前**）：
 *
 * ```
 * 广东省佛山市 → [佛山市, 广东省佛山市]
 * 广州市荔湾区 → [荔湾区, 广州市荔湾区]
 * 佛山市       → [佛山市]
 * ```
 *
 * 这样「广东省佛山市」先查「佛山市」（命中），
 * 「广州市荔湾区」先查「荔湾区」（Open-Meteo 没有 → Photon 命中**荔湾区本身**）。
 *
 * ## 为什么切段表里**没有裸「州」**
 *
 * 这是本项目第三次踩同一个坑：`广州` 以「州」结尾。切段表若含「州」，
 * 「广州市荔湾区」会在第 2 个字就被切成 `["广州"]` + `["市荔湾区"]`，全盘皆错。
 * 因此只保留**完整词**「自治州」。
 *
 * ## 为什么不额外尝试「去掉后缀」的写法
 *
 * 「佛山市」去掉「市」得到「佛山」，而 Open-Meteo 查「佛山」返回的是
 * **云南 / 重庆 / 四川 / 河南的同名村子**，第一条完全不是广东佛山。
 * 这种「看起来更宽松、实际给错地方」的候选比查不到更糟，所以不生成。
 */
private fun queryCandidates(raw: String): List<String> {
    val segments = splitAdminSegments(raw)
    if (segments.isEmpty()) return emptyList()
    // 从**最具体**（只取最后一段）到最笼统（取到第一段）依次累计：
    // 「广州市荔湾区」→ [荔湾区, 广州市荔湾区]
    //
    // ⚠️ 这里的顺序**必须**是「少 → 多」。写成 `size downTo 1` 会反过来先查整串，
    // 后果不只是多一次无用请求：Photon 兜底只拿到**第一个**候选，
    // 拿整串去查会返回一堆同名的公司 / 医院 POI，而不是「荔湾区」这个行政区本身
    // （实测就是这样，列表里全是「广州市荔湾区口腔医院」这类条目）。
    val cumulative = (1..segments.size).map { count -> segments.takeLast(count).joinToString("") }
    return (cumulative + raw.trim()).filter { it.isNotBlank() }.distinct()
}

/**
 * 按行政区后缀把一整串地名切成段。
 *
 * 「广东省佛山市」→ `[广东省, 佛山市]`；「广州市荔湾区」→ `[广州市, 荔湾区]`；
 * 「荔湾」（没有后缀可切）→ `[荔湾]`（整串作为一段，不能丢）。
 */
private fun splitAdminSegments(raw: String): List<String> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    for (character in text) {
        current.append(character)
        // 命中后缀且**前面还有字**才切段：否则「市荔湾区」这种残段会把「市」单独切走。
        val suffix = ADMIN_SEGMENT_SUFFIXES.firstOrNull {
            current.endsWith(it) && current.length > it.length
        }
        if (suffix != null) {
            segments += current.toString()
            current.clear()
        }
    }
    // 结尾没有后缀的残段（如「荔湾」）也要保留，否则整串会被丢掉。
    if (current.isNotEmpty()) segments += current.toString()
    return segments
}

/**
 * 切段用的行政区后缀。
 *
 * ⚠️ **不含裸「州」**，理由见 [queryCandidates]；「自治州」是完整词，可以留。
 * 顺序即优先级（长后缀在前）。
 */
private val ADMIN_SEGMENT_SUFFIXES = listOf(
    "特别行政区",
    "自治区",
    "自治州",
    "自治县",
    "自治旗",
    "新区",
    "地区",
    "省",
    "市",
    "区",
    "县",
    "盟",
    "旗"
)

/**
 * WMO weather code → 中文现象。
 *
 * 官方码表很细（毛毛雨 / 冻雨 / 阵雪分好几档），这里按需求归成用户一眼能认的几类：
 * 过细的区分在手机上一行小字里也读不出来，反而让人怀疑「为什么今天和昨天说法不一样」。
 * 未知码统一显示「未知」，不猜成「晴」——猜错比留白更糟。
 */
private fun weatherConditionOf(code: Int): String = when (code) {
    0 -> "晴"
    in 1..3 -> "多云"
    45, 48 -> "雾"
    in 51..67 -> "雨"
    in 71..77 -> "雪"
    in 80..82 -> "阵雨"
    in 95..99 -> "雷阵雨"
    else -> "未知"
}
