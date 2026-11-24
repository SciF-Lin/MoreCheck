// 「实验室 → 域名比价」：**原生页面**，价格由端上直接抓注册商官网（见 DomainPriceRepository）。
//
// ## v1.3.7 为什么重写
//
// v1.3.6 这一格是 WebView 加载一个**临时沙箱地址**（用户自建的 Node 服务）。
// 沙箱实例回收后再打开就只剩一片白/403，等于功能消失。这一版把采集搬到端上：
// 没有中间服务、没有会过期的域名，代价是页面结构一改就得跟着改解析器。
//
// ## 界面口径
//
// - 金额一律**同时**给「人民币折算」和「原币标价」，因为跨币种比价必须能对账；
// - 列表按**首年注册价（人民币）**升序，第 1 名单独高亮；
// - 首年促销价必须打标（否则用户会以为第二年也这个价）；
// - 抓不到的厂商**如实列出原因**，绝不用"常识价"填空。

package com.morecheck.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.data.DomainPriceRepository
import com.morecheck.data.PriceComparison
import com.morecheck.data.PriceRow
import com.morecheck.data.RegistrarPrice
import com.morecheck.data.SourceOutcome
import com.morecheck.ui.theme.AppType
import com.morecheck.ui.theme.Radius
import com.morecheck.ui.theme.Space
import com.morecheck.util.openInBrowser
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** 页面状态：一次查询的四种结局。 */
private sealed interface PriceUiState {
    data object Idle : PriceUiState
    data class Loading(val step: String) : PriceUiState
    data class Ready(val result: PriceComparison) : PriceUiState
    data class Failed(val message: String) : PriceUiState
}

/** 输入框下方的后缀快选。 */
private val SUFFIX_SHORTCUTS = listOf("com", "cn", "net", "org", "xyz", "io", "ai", "top")

/** 「实验」入口的后缀名（点一下立刻查这个后缀）。 */
private const val DEFAULT_INPUT = "com"

/**
 * 「实验室 → 域名比价」。
 *
 * 进去先自动查一次 `.com`（6 家官网抓完约 5~8 秒，缓存 15 分钟），
 * 用户再点后缀快选或输入自己的域名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainPriceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf(DEFAULT_INPUT) }
    var state by remember { mutableStateOf<PriceUiState>(PriceUiState.Idle) }

    fun runQuery(text: String, force: Boolean) {
        val target = text.trim()
        if (target.isEmpty()) {
            scope.launch { snackbar.showSnackbar("先输入域名或后缀") }
            return
        }
        state = PriceUiState.Loading("准备中…")
        scope.launch {
            val result = DomainPriceRepository.query(target, force) { step ->
                // 回调发生在 IO 线程；快照状态是线程安全的，直接写即可。
                state = PriceUiState.Loading(step)
            }
            state = result.fold(
                onSuccess = { PriceUiState.Ready(it) },
                onFailure = { PriceUiState.Failed(it.message ?: "查询失败") }
            )
        }
    }

    LaunchedEffect(Unit) {
        if (state is PriceUiState.Idle) runQuery(input, force = false)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("域名比价") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { runQuery(input, force = true) }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "重新抓取")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = Space.lg,
                end = Space.lg,
                top = Space.md,
                bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item {
                SearchCard(
                    input = input,
                    onInputChange = { input = it },
                    onQuery = { runQuery(input, force = false) },
                    onSuffixClick = { suffix ->
                        input = suffix
                        runQuery(suffix, force = false)
                    }
                )
            }

            when (val current = state) {
                PriceUiState.Idle -> item { HintCard() }
                is PriceUiState.Loading -> item { LoadingCard(current.step) }
                is PriceUiState.Failed -> item {
                    ErrorCard(
                        message = current.message,
                        onRetry = { runQuery(input, force = true) }
                    )
                }
                is PriceUiState.Ready -> renderResult(
                    result = current.result,
                    onRetry = { runQuery(input, force = true) },
                    onSuffixClick = { suffix ->
                        input = suffix
                        runQuery(suffix, force = false)
                    },
                    onOpen = { url ->
                        openInBrowser(context, url) { message ->
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    }
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------- */
/* 结果区                                                                  */
/* ---------------------------------------------------------------------- */

private fun androidx.compose.foundation.lazy.LazyListScope.renderResult(
    result: PriceComparison,
    onRetry: () -> Unit,
    onSuffixClick: (String) -> Unit,
    onOpen: (String) -> Unit
) {
    item { SummaryCard(result) }

    if (result.rows.isEmpty()) {
        item {
            EmptyResultCard(
                tld = result.query.tld,
                failures = result.sources.filter { !it.ok },
                onRetry = onRetry
            )
        }
    } else {
        item { SectionTitle("注册商比价 · 按首年价升序", trailing = "${result.rows.size} 家") }
        itemsIndexed(result.rows, key = { _, row -> row.price.vendorId }) { index, row ->
            RegistrarRowCard(
                index = index,
                row = row,
                isBest = index == 0,
                onOpen = { onOpen(row.price.buyUrl) }
            )
        }
        if (result.unconverted.isNotEmpty()) {
            item {
                NoteText(
                    "另有 ${result.unconverted.size} 家抓到了价格但换算不出人民币（汇率不可用或币种未知），" +
                        "未参与排序：${result.unconverted.joinToString("、") { it.price.short }}。"
                )
            }
        }
    }

    if (result.overview.isNotEmpty()) {
        item { SectionTitle("常见后缀最低价", trailing = "点一下看该后缀明细") }
        itemsIndexed(result.overview, key = { _, item -> item.tld }) { _, item ->
            val best = item.registerCny
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuffixClick(item.tld) },
                shape = RoundedCornerShape(Radius.field),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ".${item.tld}",
                        style = AppType.body,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(96.dp)
                    )
                    Text(
                        text = formatCny(best),
                        style = AppType.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = item.vendor,
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    item { SourceStatusCard(result.sources) }

    item {
        Text(
            text = "价格来自各家官网当次公示（抓取于 ${formatClock(result.fetchedAt)}）。" +
                "跨币种按 1 USD = ${formatRate(result.fx.usdCny)} 元折算" +
                (result.fx.eurCny?.let { "，1 EUR = ${formatRate(it)} 元" } ?: "") +
                if (result.fx.ok) "。" else "（汇率获取失败，未做换算）。",
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    item {
        Text(
            text = "首年促销价只适用于第一年，次年起按续费价执行；溢价域名与结算价以厂商下单页为准。",
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (result.sources.any { !it.ok } || DomainPriceRepository.UNLISTED_VENDORS.isNotEmpty()) {
        item { UnlistedCard(result.sources) }
    }
}

/* ---------------------------------------------------------------------- */
/* 组件                                                                    */
/* ---------------------------------------------------------------------- */

@Composable
private fun SearchCard(
    input: String,
    onInputChange: (String) -> Unit,
    onQuery: () -> Unit,
    onSuffixClick: (String) -> Unit
) {
    CardBox {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("域名或后缀") },
            placeholder = { Text("example.com / example / xyz") },
            singleLine = true,
            shape = RoundedCornerShape(Radius.field),
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onQuery() })
        )
        Spacer(Modifier.height(Space.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            SUFFIX_SHORTCUTS.take(4).forEach { suffix ->
                SuffixPill(suffix, onSuffixClick, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(Space.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            SUFFIX_SHORTCUTS.drop(4).forEach { suffix ->
                SuffixPill(suffix, onSuffixClick, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(Space.md))
        Button(
            onClick = onQuery,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.control)
        ) {
            Text("开始比价")
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            text = "6 家注册商官网实时价 · 结果缓存 15 分钟",
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SuffixPill(suffix: String, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable { onClick(suffix) },
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = Space.sm)) {
            Text(text = ".$suffix", style = AppType.caption, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HintCard() {
    CardBox {
        Text("查什么", style = AppType.heading)
        Spacer(Modifier.height(Space.sm))
        Text(
            text = "输入完整域名（example.com）看该后缀的跨注册商报价；" +
                "只输主体（example）时会按 .com 算，并列出常见后缀的最低价。",
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingCard(step: String) {
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(Space.md))
            Text("正在抓取官网价格", style = AppType.heading)
        }
        Spacer(Modifier.height(Space.md))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.md))
        Text(
            text = step,
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = "首次抓取要下载 6 个官网页面（最大的近 800KB），通常 5~8 秒。",
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    CardBox {
        Text("查询没能完成", style = AppType.heading)
        Spacer(Modifier.height(Space.sm))
        Text(
            text = message,
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun EmptyResultCard(tld: String, failures: List<SourceOutcome>, onRetry: () -> Unit) {
    CardBox {
        Text("没有拿到 .$tld 的实时报价", style = AppType.heading)
        Spacer(Modifier.height(Space.sm))
        Text(
            text = if (failures.isEmpty()) {
                "6 家官网都抓取成功，但没有任何一家公示了 .$tld 的价格（可能是小众后缀）。"
            } else {
                "抓取失败：" + failures.joinToString("；") { "${it.name}（${it.error}）" }
            },
            style = AppType.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Space.sm))
        TextButton(onClick = onRetry) { Text("重新抓取") }
    }
}

@Composable
private fun SummaryCard(result: PriceComparison) {
    val best = result.bestRegister
    CardBox(border = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = result.query.domain,
                    style = AppType.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 纯后缀查询没有具体域名，RDAP 查不了；这时不摆一个「未查询」的灰标占位。
                    if (result.rdap.status == "registered" || result.rdap.status == "available") {
                        RdapChip(result)
                        Spacer(Modifier.width(Space.sm))
                    }
                    Text(
                        text = "${result.okSourceCount}/${result.sources.size} 家抓取成功",
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (best != null) {
            Spacer(Modifier.height(Space.md))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatCny(best.registerCny),
                    style = AppType.display.copy(fontSize = 28.sp, lineHeight = 32.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Space.sm))
                Text(
                    text = "首年 · ${best.price.short}",
                    style = AppType.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(Space.xs))
            Text(
                text = buildString {
                    append("原币 ${originalMoney(best.price.register, best.price.currency)}")
                    result.spread?.let { append(" · 与最贵一家相差 ¥${formatNumber(it)}") }
                    result.bestRenew?.let {
                        append("\n续费最低 ${it.price.short} ${formatCny(it.renewCny)}")
                    }
                    result.bestTransfer?.let {
                        append(" · 转入最低 ${it.price.short} ${formatCny(it.transferCny)}")
                    }
                },
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RdapChip(result: PriceComparison) {
    val (background, foreground) = when (result.rdap.status) {
        "registered" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "available" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(Radius.pill), color = background) {
        Text(
            text = result.rdap.label,
            style = AppType.caption,
            color = foreground,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 2.dp)
        )
    }
}

@Composable
private fun RegistrarRowCard(
    index: Int,
    row: PriceRow,
    isBest: Boolean,
    onOpen: () -> Unit
) {
    val borderColor = if (isBest) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RankBadge(index + 1, isBest)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.price.short,
                            style = AppType.body,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(Space.sm))
                        Tag(row.price.region, subtle = true)
                        if (row.price.firstYear) {
                            Spacer(Modifier.width(Space.xs))
                            Tag("首年促销", subtle = false)
                        }
                    }
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        text = row.price.note,
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatCny(row.registerCny),
                        style = AppType.heading,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = originalMoney(row.price.register, row.price.currency),
                        style = AppType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceFact("续费", row.renewCny, row.price.renew, row.price.currency)
                Spacer(Modifier.width(Space.lg))
                PriceFact("转入", row.transferCny, row.price.transfer, row.price.currency)
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = "去官网",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PriceFact(label: String, cny: Double?, original: Double?, currency: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = AppType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Space.xs))
        Text(
            text = if (cny != null) formatCny(cny) else originalMoney(original, currency),
            style = AppType.caption,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RankBadge(rank: Int, isBest: Boolean) {
    val background = if (isBest) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (isBest) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "$rank",
            style = AppType.caption,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
    }
}

@Composable
private fun Tag(text: String, subtle: Boolean) {
    val background = if (subtle) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = if (subtle) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(shape = RoundedCornerShape(Radius.pill), color = background) {
        Text(
            text = text,
            style = AppType.caption,
            color = foreground,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 1.dp)
        )
    }
}

@Composable
private fun SourceStatusCard(sources: List<SourceOutcome>) {
    CardBox {
        Text("数据源状态", style = AppType.body, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(Space.sm))
        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (source.ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape
                        )
                )
                Spacer(Modifier.width(Space.sm))
                Text(source.name, style = AppType.caption, modifier = Modifier.weight(1f))
                Text(
                    text = if (source.ok) {
                        "${source.rowCount} 个后缀" + if (source.fromCache) " · 缓存" else ""
                    } else {
                        source.error.orEmpty()
                    },
                    style = AppType.caption,
                    color = if (source.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UnlistedCard(sources: List<SourceOutcome>) {
    CardBox {
        Text("没有可机读实时报价的厂商", style = AppType.body, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(Space.sm))
        DomainPriceRepository.UNLISTED_VENDORS.forEach { vendor ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Text(
                    text = "${vendor.name}（${vendor.region}）",
                    style = AppType.caption,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = vendor.reason,
                    style = AppType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val failed = sources.filter { !it.ok }
        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(Space.sm))
            Text(
                text = "本次抓取失败：" + failed.joinToString("；") { "${it.name}（${it.error}）" },
                style = AppType.caption,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.sm),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, style = AppType.heading, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = AppType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = AppType.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 统一的卡片底：1dp 描边 + 底色，不用投影（软件渲染下阴影会糊成一片灰）。 */
@Composable
private fun CardBox(
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.card),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(Space.lg), content = content)
    }
}

/* ---------------------------------------------------------------------- */
/* 格式化                                                                  */
/* ---------------------------------------------------------------------- */

/** 人民币金额：两位小数；拿不到时给「—」（不是 0，避免误读成免费）。 */
private fun formatCny(value: Double?): String =
    if (value == null) "—" else "¥" + formatNumber(value)

/** 数字：整数不带小数，其余两位（官网价目表里 95 和 95.00 是同一个价）。 */
private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.ROOT, "%.0f", value)
    } else {
        String.format(Locale.ROOT, "%.2f", value)
    }

/** 原币标价：`USD 11.81`；金额缺失时给「—」。 */
private fun originalMoney(value: Double?, currency: String): String =
    if (value == null) "—" else "$currency ${formatNumber(value)}"

private fun formatRate(value: Double?): String =
    if (value == null) "—" else String.format(Locale.ROOT, "%.3f", value)

private fun formatClock(millis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

/** 表格里偶尔要显示完整报价对象（保留给将来的"展开证据"用）。 */
@Suppress("unused")
private fun RegistrarPrice.brief(): String =
    "${short}.${tld} ${originalMoney(register, currency)}"
