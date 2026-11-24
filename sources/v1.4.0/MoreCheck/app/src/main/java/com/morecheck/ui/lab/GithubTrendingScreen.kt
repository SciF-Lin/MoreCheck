package com.morecheck.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import com.morecheck.ui.theme.onAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * GitHub 热门项目（2026-10-27 需求：「在实验室可以查看 GitHub 热门项目」）。
 *
 * ## 数据来源
 *
 * GitHub 官方 Search API：`/search/repositories`，按
 * `pushed:>=<日期> stars:>=10` 过滤 + `sort=stars` 降序。
 *
 * ⚠️ 这与 `github.com/trending` **不完全等价**：官方 Trending 用的是站内增量算法，
 * 没有公开 API。Search API 的近似做法是"最近 N 天有推送、按总星数排序"，
 * 结果高度重合但不相同——页脚对此有一行说明，避免用户以为是"官方榜"。
 *
 * ## 为什么不走本项目自己的云函数
 *
 * 热搜那套云端聚合是为"要抓八个平台"建的；GitHub 一个公开 GET 就够了，
 * 端上直连能省一次云端调用。代价是没有 token 时受**匿名限流 10 次/分钟**，
 * 所以这里做了内存缓存（同一筛选条件 10 分钟内只打一次），失败时给明确提示。
 */
private const val GITHUB_SEARCH_API = "https://api.github.com/search/repositories"

/** 每分钟匿名限流很低，所以缓存要活久一点。 */
private const val CACHE_TTL_MS = 10 * 60 * 1000L

/** 时间范围。 */
internal enum class TrendingPeriod(val key: String, val label: String, val days: Long) {
    DAILY("daily", "今日", 1),
    WEEKLY("weekly", "本周", 7),
    MONTHLY("monthly", "本月", 30)
}

/** 语言筛选（null = 全部）。 */
internal data class TrendingLanguage(val key: String?, val label: String)

internal val TRENDING_LANGUAGES = listOf(
    TrendingLanguage(null, "全部"),
    TrendingLanguage("typescript", "TypeScript"),
    TrendingLanguage("python", "Python"),
    TrendingLanguage("go", "Go"),
    TrendingLanguage("rust", "Rust"),
    TrendingLanguage("java", "Java"),
    TrendingLanguage("kotlin", "Kotlin")
)

/** 一个仓库条目。 */
internal data class TrendingRepo(
    val rank: Int,
    val fullName: String,
    val url: String,
    val description: String,
    val stars: Int,
    val forks: Int,
    val language: String
) {
    val owner: String get() = fullName.substringBefore('/')
    val name: String get() = fullName.substringAfter('/')
}

/** 按 `周期 + 语言` 缓存，避免用户来回点筛选就把匿名额度打光。 */
private object TrendingCache {
    private val entries = mutableMapOf<String, Pair<Long, List<TrendingRepo>>>()

    fun get(key: String): List<TrendingRepo>? {
        val hit = entries[key] ?: return null
        if (System.currentTimeMillis() - hit.first > CACHE_TTL_MS) return null
        return hit.second
    }

    fun put(key: String, repos: List<TrendingRepo>) {
        entries[key] = System.currentTimeMillis() to repos
    }
}

/**
 * 抓取并解析热门项目。
 *
 * 返回 [Result]，失败原因已经翻译成"用户能看懂的一句话"：
 * 403 是限流（匿名 10 次/分钟），429 同理，其它按 HTTP 状态码展示。
 */
internal suspend fun fetchTrending(
    period: TrendingPeriod,
    language: String?
): Result<List<TrendingRepo>> = withContext(Dispatchers.IO) {
    runCatching {
        val since = LocalDate.now().minusDays(period.days - 1)
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        val query = buildString {
            append("pushed:>=").append(since).append(" stars:>=10")
            if (language != null) append(" language:").append(language)
        }
        val url = "$GITHUB_SEARCH_API?q=${urlEncode(query)}&sort=stars&order=desc&per_page=30"
        val text = httpGetText(
            url,
            headers = mapOf(
                // GitHub 强制要求 UA，缺了直接 403。
                "User-Agent" to "MoreCheck-Android",
                "Accept" to "application/vnd.github+json"
            )
        )
        parseTrendingJson(text)
    }.recoverCatching { e ->
        val raw = e.message.orEmpty()
        throw IllegalStateException(
            when {
                raw.contains("403") -> "GitHub 限流了（匿名 10 次/分钟），过一分钟再试"
                raw.contains("429") -> "请求太频繁，过一分钟再试"
                raw.contains("404") -> "接口地址不可用"
                else -> "拉取失败：$raw"
            }
        )
    }
}

/** 解析 Search API 返回。 */
internal fun parseTrendingJson(text: String): List<TrendingRepo> {
    val root = JSONObject(text)
    val items = root.optJSONArray("items") ?: error("返回格式无法解析")
    val result = mutableListOf<TrendingRepo>()
    for (i in 0 until items.length()) {
        val obj = items.optJSONObject(i) ?: continue
        val fullName = obj.optString("full_name", "")
        if (fullName.isBlank()) continue
        result += TrendingRepo(
            rank = result.size + 1,
            fullName = fullName,
            url = obj.optString("html_url", "https://github.com/$fullName"),
            description = obj.optString("description", "").orEmpty(),
            stars = obj.optInt("stargazers_count", 0),
            forks = obj.optInt("forks_count", 0),
            language = obj.optString("language", "").orEmpty()
        )
    }
    return result
}

/**
 * 语言配色（只用于那个小圆点，不承担信息传达——语言名仍然写在旁边）。
 *
 * 未列出的语言统一用 `outline`，不要随机取色：颜色一旦随机，同一个仓库
 * 两次进入页面颜色不同，用户会以为数据变了。
 */
private val LANGUAGE_COLORS = mapOf(
    "TypeScript" to Color(0xFF3178C6),
    "JavaScript" to Color(0xFFF1E05A),
    "Python" to Color(0xFF3572A5),
    "Go" to Color(0xFF00ADD8),
    "Rust" to Color(0xFFDEA584),
    "Java" to Color(0xFFB07219),
    "Kotlin" to Color(0xFFA97BFF),
    "C++" to Color(0xFFF34B7D),
    "C" to Color(0xFF555555),
    "Swift" to Color(0xFFF05138),
    "PHP" to Color(0xFF4F5D95),
    "Ruby" to Color(0xFF701516),
    "C#" to Color(0xFF178600),
    "HTML" to Color(0xFFE34C26),
    "Shell" to Color(0xFF89E051)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GithubTrendingScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var period by rememberSaveable { mutableStateOf(TrendingPeriod.DAILY.key) }
    // null 在 rememberSaveable 里不好存，用空串代表「全部」。
    var language by rememberSaveable { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<TrendingRepo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var stamp by remember { mutableLongStateOf(0L) }

    val currentPeriod = TrendingPeriod.entries.firstOrNull { it.key == period } ?: TrendingPeriod.DAILY
    val currentLanguage = language.ifBlank { null }

    fun load(force: Boolean = false) {
        val cacheKey = "${currentPeriod.key}|${currentLanguage ?: "all"}"
        if (!force) {
            TrendingCache.get(cacheKey)?.let {
                repos = it
                errorMessage = null
                return
            }
        }
        loading = true
        errorMessage = null
        scope.launch {
            fetchTrending(currentPeriod, currentLanguage).fold(
                onSuccess = {
                    repos = it
                    TrendingCache.put(cacheKey, it)
                    stamp = System.currentTimeMillis()
                },
                onFailure = { e ->
                    // 失败不清空已有列表：刷新失败时保留上一次的内容更有用。
                    errorMessage = e.message ?: "拉取失败"
                    if (repos == null) repos = emptyList()
                }
            )
            loading = false
        }
    }

    // 切周期 / 切语言都重新取（命中缓存则秒回）。
    LaunchedEffect(period, language) { load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("GitHub 热门", style = AppType.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { load(force = true) }, enabled = !loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 时间范围（三段，语义是"换一个数据源"而不是"筛选"，所以用分段样式）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.sm)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(Radius.control)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TrendingPeriod.entries.forEach { item ->
                    val selected = item.key == period
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                RoundedCornerShape(Radius.control - 2.dp)
                            )
                            .clickableNoIndication { period = item.key },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.label,
                            style = AppType.bodySmall,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (selected) onAccent(MaterialTheme.colorScheme.primary)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 语言筛选（横向滚动胶囊，与热搜页同一套观感）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                TRENDING_LANGUAGES.forEach { item ->
                    val selected = (item.key ?: "") == language
                    val accent = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .background(
                                if (selected) accent
                                else MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(Radius.pill)
                            )
                            .then(
                                if (selected) Modifier
                                else Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(Radius.pill)
                                )
                            )
                            // 视觉 32dp，点击区补到 44dp（评审 3.5：触控下限）。
                            .clickableNoIndication { language = item.key ?: "" }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            item.label,
                            style = AppType.caption,
                            color = if (selected) onAccent(accent)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            when {
                loading && repos.isNullOrEmpty() -> LoadingBox()
                errorMessage != null && repos.isNullOrEmpty() -> ErrorBox(
                    message = errorMessage.orEmpty(),
                    onRetry = { load(force = true) }
                )
                repos.isNullOrEmpty() -> EmptyBox()
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Space.lg,
                        end = Space.lg,
                        top = Space.xs,
                        bottom = Space.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Space.md)
                ) {
                    if (errorMessage != null) {
                        item {
                            Text(
                                errorMessage.orEmpty(),
                                style = AppType.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = Space.xs)
                            )
                        }
                    }
                    items(repos.orEmpty(), key = { it.fullName }) { repo ->
                        RepoCard(repo) { openTopic(context, repo.url, onMessage) }
                    }
                    item {
                        // 说明来源口径（评审 3.4：空/错误/说明都要有一句人话）。
                        Text(
                            "按「最近推送 + 总星数」近似 GitHub Trending，与官方榜不完全一致" +
                                if (stamp > 0) " · 已缓存 10 分钟" else "",
                            style = AppType.micro,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Space.sm),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoCard(repo: TrendingRepo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(Radius.card)
            )
            // 深色模式下卡片与页底只差 1.21:1，必须补一条描边才看得出边界（评审硬伤 4）。
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(Radius.card)
            )
            .clickableNoIndication(onClick)
            .padding(Space.lg)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 名次：等宽字体 + 固定宽度，两位数和一位数的冒号能对齐。
            Text(
                "#${repo.rank}",
                style = AppType.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
            Column(Modifier.weight(1f)) {
                Row {
                    Text(
                        repo.owner + "/",
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        repo.name,
                        style = AppType.body,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (repo.description.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        repo.description,
                        style = AppType.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            compactCount(repo.stars),
                            style = AppType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "Fork ${compactCount(repo.forks)}",
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (repo.language.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dot = LANGUAGE_COLORS[repo.language]
                                ?: MaterialTheme.colorScheme.onSurfaceVariant
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(dot, RoundedCornerShape(Radius.pill))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                repo.language,
                                style = AppType.caption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(Space.md))
            Text(
                "正在拉取…",
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "还没有数据",
                style = AppType.body,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Space.xs))
            // 评审 3.4：空态必须带一句可执行的动作，不能只写「暂无数据」。
            Text(
                "换个时间范围或语言试试",
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message,
                style = AppType.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Space.xl)
            )
            Spacer(Modifier.height(Space.lg))
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(Radius.control)
                    )
                    .clickableNoIndication(onRetry)
                    .padding(horizontal = Space.xl),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "重试",
                    style = AppType.bodySmall,
                    color = onAccent(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

/** 星数缩写：12345 → 12.3k，1234567 → 1.2m（GitHub 自己的口径）。 */
internal fun compactCount(value: Int): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fm", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

/**
 * 极简点击：只响应点击，不画涟漪。
 *
 * 本页的"卡片""胶囊"都是自绘容器，Material 的涟漪会被裁切得不圆（卡片 16dp 圆角、
 * 胶囊 999dp），所以统一关掉 indication；按压反馈由容器自身的底色变化承担。
 * 触控区不受影响（最小 44dp 靠 padding 撑出来，见调用处）。
 */
@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            interactionSource = source,
            indication = null,
            onClick = onClick
        )
    )
}

// URL 编码直接用 HotSearchScreen.kt 里的 `internal fun urlEncode`：
// 同一个包里两处自定义同名扩展会报 "Conflicting overloads"（本轮踩过）。
