package com.morecheck.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer
import com.morecheck.data.CityMatch
import com.morecheck.data.CitySearchResult
import com.morecheck.data.DailyForecast
import com.morecheck.data.WeatherInfo
import com.morecheck.data.WeatherResult
import com.morecheck.util.AppPermission
import com.morecheck.util.AppPermissions
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 实验室 → 天气。
 *
 * 门控（重要）：权限判定必须是「设置页开关 **且** 系统已授权」（[AppPermissions.isUsable]）。
 * 只看开关，会出现「开关开着但系统没给权限」→ 请求被平台拒绝；
 * 只看系统权限，又会出现「用户早就把开关关了，应用却还在偷偷定位」——
 * 后者是隐私问题，不是体验问题，所以两个条件缺一不可。
 *
 * **唯一的例外是手动选择城市**：那条路是纯网络查询（[WeatherRepository.searchCities] /
 * [WeatherRepository.loadWeatherAt]），不读定位、不查权限、也不看 DataStore 开关。
 * 理由很直接——用户会用到它，恰恰是因为自动定位失败了；
 * 如果这里也套一遍门控，点「手动选择城市」只会立刻再看到一次同样的失败。
 *
 * 组合上不新建 ViewModel：本页只有「一次请求 + 一个重试计数器 + 一段手动选择状态」，
 * ViewModel 带来的生命周期与工厂样板大于收益。定位与请求全在
 * [WeatherRepository] 内部切到 IO，这里只消费结果。
 *
 * @param onOpenPermissionSettings 开关关闭时引导用户去处理权限（由调用方决定跳到权限管理页还是系统设置页）。
 * @param onMessage 未授权 / 失败时把原因交给全局 Snackbar，与其它页面同一套提示出口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    onBack: () -> Unit,
    onOpenPermissionSettings: () -> Unit = {},
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    // v1.1.75：两个仓库都从 `AppContainer` 取（统一入口 `Context.appContainer`）。
    //
    // `WeatherRepository` 此前是本页 `remember { new }` 自建的，v1.1.75 收进了容器——
    // 它无状态（只持有 context 做 HTTP + JSON），共用一份安全，且与
    // `TimetableRepository` 的口径统一。见 `AppContainer.weatherRepository` 的 KDoc。
    val container = context.appContainer
    val settingsRepository = container.settingsRepository
    val repository = container.weatherRepository
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 开关在 DataStore 里，一次订阅即可拿到并随设置页改动实时刷新。
    val toggleEnabled by settingsRepository.locationEnabled
        .collectAsStateWithLifecycle(initialValue = false)

    // 系统授权状态**不是** Flow：用户随时可能在系统设置里改，应用收不到任何回调。
    // 只在进入页面时查一次是不够的——用户点「去设置」授权后返回，
    // Composable 还在组合里、不会重建，界面会一直卡在「未授权」上，
    // 必须等用户手动退出再进来才恢复（实测就是这样）。
    // 所以这里订阅 ON_RESUME：从系统设置回来时重新查一次，自动接上后续请求。
    var systemGranted by remember {
        mutableStateOf(AppPermissions.isGranted(context, AppPermission.LOCATION))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemGranted = AppPermissions.isGranted(context, AppPermission.LOCATION)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val usable = AppPermissions.isUsable(context, AppPermission.LOCATION, toggleEnabled)

    // 重试计数：加一即触发 produceState 重新执行一次请求。
    var retryTick by remember { mutableIntStateOf(0) }

    // 只有「开关 && 权限」都满足时才把 tick 带进 key 并发请求；
    // 不满足时 key 里带上 tick 也没有意义（下面的分支会直接短路），
    // 因此这里刻意不为未授权场景保留一次多余的定位尝试。
    // 三个参数全走位置传参：这个重载的参数名（key1/key2）属于实现细节，
    // 写成具名反而可能在 Compose 版本升级时因参数改名而编译失败。
    val result by produceState<WeatherResult?>(null, usable, retryTick) {
        if (!usable) {
            // 双重兜底：即使被误触发，这里也不发请求。
            value = null
            return@produceState
        }
        value = null
        value = repository.loadWeather()
    }

    // ---- 手动选择城市的状态 ---------------------------------------------------
    // 手动天气单独存一份，**不塞回 result**：result 由 produceState 管着，
    // 它会在开关/权限变化或重试时整体重置，把用户手动选的天气写进去会被下一次刷新冲掉。
    var manualWeather by remember { mutableStateOf<WeatherInfo?>(null) }
    var manualFailure by remember { mutableStateOf<String?>(null) }
    var manualLoading by remember { mutableStateOf(false) }

    // 对话框自身的状态（打开、输入、搜索结果）也留在页面层：
    // 关闭对话框时结果自然丢弃，不需要额外的清理逻辑。
    var cityDialogOpen by remember { mutableStateOf(false) }
    var cityQuery by remember { mutableStateOf("") }
    var citySearching by remember { mutableStateOf(false) }
    var cityOutcome by remember { mutableStateOf<CitySearchResult?>(null) }
    var cityMessage by remember { mutableStateOf<String?>(null) }

    // 打开对话框时清空上一轮的输入与结果：留着旧关键词很容易让人以为
    // 「刚才搜过的城市」是本次定位得到的结果。
    LaunchedEffect(cityDialogOpen) {
        if (cityDialogOpen) {
            cityQuery = ""
            cityOutcome = null
            cityMessage = null
        }
    }

    // 失败时把原因交给全局 Snackbar，让「提示」这件事只有一个出口。
    // 只挂自动查询的 result；手动查询的失败写进 manualFailure 就地提示（见下方卡片），
    // 因为它同时决定了卡片上要不要显示一句「查询失败」。
    LaunchedEffect(result) {
        val message = when (val outcome = result) {
            is WeatherResult.LocationFailed -> outcome.reason
            is WeatherResult.NetworkFailed -> outcome.reason
            else -> null
        }
        if (message != null) onMessage(message)
    }

    /** 手动选中的城市：关掉对话框并查这座城市的天气。 */
    fun requestManualWeather(match: CityMatch) {
        cityDialogOpen = false
        manualLoading = true
        manualFailure = null
        scope.launch {
            when (val outcome = repository.loadWeatherAt(
                latitude = match.latitude,
                longitude = match.longitude,
                placeName = manualPlaceLabel(match)
            )) {
                is WeatherResult.Success -> {
                    manualWeather = outcome.weather
                    manualFailure = null
                }
                // 手动路径理论上不会返回未授权/定位失败（压根没碰定位），
                // 但 sealed interface 要求穷尽，而且万一将来接口变了，
                // 这里也只是把话说清楚，不会崩。
                WeatherResult.PermissionDenied -> {
                    manualWeather = null
                    manualFailure = "手动查询不需要定位权限，请重试"
                }
                is WeatherResult.LocationFailed -> {
                    manualWeather = null
                    manualFailure = outcome.reason
                }
                is WeatherResult.NetworkFailed -> {
                    manualWeather = null
                    manualFailure = outcome.reason
                }
            }
            manualLoading = false
        }
    }

    /** 搜索城市：纯网络请求，与定位权限完全无关。 */
    fun submitCitySearch() {
        val keyword = cityQuery.trim()
        if (keyword.isEmpty() || citySearching) return
        citySearching = true
        cityMessage = null
        cityOutcome = null
        scope.launch {
            when (val outcome = repository.searchCities(keyword)) {
                is CitySearchResult.Success -> cityOutcome = outcome
                CitySearchResult.Empty -> cityMessage = "没有找到「$keyword」，换个说法试试"
                is CitySearchResult.Failed -> cityMessage = outcome.reason
            }
            citySearching = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("天气") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val manual = manualWeather
            when {
                // ---- 1. 加载中：优先于一切 ----
                // 手动查询没有自动 result 可用（result 这时多半是失败的旧值），
                // 所以它的「转圈」必须排在最前面判，否则点完城市会先看到失败卡片再跳出天气。
                manualLoading -> LoadingCard()

                // ---- 2. 手动选择的结果 ----
                // 提到门控之前判断：手动选的城市就是「用户此刻想看的东西」，
                // 定位开关关着、权限没给，都不该把它顶掉。
                manual != null -> ManualWeatherContent(
                    weather = manual,
                    onUseCurrentLocation = {
                        // 回到自动模式：清掉手动结果，若此刻自动查询其实是可用的，
                        // 顺手加一次 tick 让它立刻重新取一次；
                        // 不可用时（没开关 / 没权限）下面的门控会把用户送回引导卡片。
                        manualWeather = null
                        manualFailure = null
                        if (usable) retryTick++
                    }
                )

                // ---- 3. 手动选中的城市查询失败：说明原因 + 重开选择器 ----
                // 不把 manualFailure 交给 Snackbar：它要一直留在卡片上，
                // 用户才知道「刚才点的那个城市没查到」，而不是莫名其妙回到了失败页。
                // 主副按钮都重新打开选择器：失败的是网络不是选择，
                // 让用户在同一份候选里重点一次（或换一个城市）就够了，
                // 单独做「重发上一次请求」需要额外记住上次选的 CityMatch，收益不值这个状态。
                manualFailure != null -> ErrorCard(
                    title = "天气获取失败",
                    description = manualFailure.orEmpty(),
                    actionText = "重试",
                    onAction = { cityDialogOpen = true },
                    secondaryText = "手动选择城市",
                    onSecondary = { cityDialogOpen = true }
                )

                // ---- 4. 开关没打开：连请求都不发 ----
                !toggleEnabled -> GateCard(
                    icon = Icons.Filled.LocationOn,
                    title = "定位权限未开启",
                    description = "天气需要先获取你的位置。请到「设置 → 通用 → 权限管理」打开「定位」开关；" +
                        "也可以直接手动选择城市。",
                    actionText = "去开启",
                    onAction = onOpenPermissionSettings,
                    secondaryText = "手动选择城市",
                    onSecondary = { cityDialogOpen = true }
                )

                // ---- 5. 开关开着但系统没授权：请求一定会被拒，同样不发 ----
                // 这一支直接调 [AppPermissions.openAppSettings]，不绕 onOpenPermissionSettings：
                // 「系统权限没给」只有系统设置页能解决，走死这一个出口，
                // 即使调用方没接 onOpenPermissionSettings（默认空实现）按钮也不会变成死键。
                !systemGranted -> GateCard(
                    icon = Icons.Filled.CloudOff,
                    title = "系统未授予定位权限",
                    description = "「定位」开关已打开，但系统还没有授予定位权限，请到系统设置里允许；" +
                        "也可以直接手动选择城市。",
                    actionText = "去设置",
                    onAction = { AppPermissions.openAppSettings(context) },
                    secondaryText = "手动选择城市",
                    onSecondary = { cityDialogOpen = true }
                )

                // ---- 6. 自动查询加载中 ----
                result == null -> LoadingCard()

                // ---- 7. 有结果：成功 / 未授权 / 定位失败 / 网络失败 ----
                else -> when (val outcome = result) {
                    is WeatherResult.Success -> WeatherContent(outcome.weather)

                    // 未授权 / 定位失败 / 网络失败：主按钮仍是原来的补救动作，
                    // 副按钮统一是「手动选择城市」——它是这几种失败下**唯一一定能走通**的路。
                    WeatherResult.PermissionDenied -> ErrorCard(
                        title = "定位权限已失效",
                        description = "系统拒绝了本次定位请求，请检查系统设置里的定位权限。",
                        actionText = "去设置",
                        onAction = { AppPermissions.openAppSettings(context) },
                        secondaryText = "手动选择城市",
                        onSecondary = { cityDialogOpen = true }
                    )

                    is WeatherResult.LocationFailed -> ErrorCard(
                        title = "无法获取当前位置",
                        description = outcome.reason,
                        actionText = "重试",
                        onAction = { retryTick++ },
                        secondaryText = "手动选择城市",
                        onSecondary = { cityDialogOpen = true }
                    )

                    is WeatherResult.NetworkFailed -> ErrorCard(
                        title = "天气获取失败",
                        description = outcome.reason,
                        actionText = "重试",
                        onAction = { retryTick++ },
                        secondaryText = "手动选择城市",
                        onSecondary = { cityDialogOpen = true }
                    )

                    null -> LoadingCard()
                }
            }

            Spacer(Modifier.height(8.dp))
        }

        if (cityDialogOpen) {
            CitySearchDialog(
                query = cityQuery,
                onQueryChange = { cityQuery = it },
                searching = citySearching,
                outcome = cityOutcome,
                message = cityMessage,
                onSearch = { submitCitySearch() },
                onPick = { requestManualWeather(it) },
                onDismiss = { cityDialogOpen = false }
            )
        }
    }
}

/**
 * 「手动选择城市」对话框：输入地名 → 搜索 → 点一条候选。
 *
 * 为什么用 [AlertDialog] 而不是 `ModalBottomSheet`：本项目的其它页面已经在用 AlertDialog
 * （模态、自带遮罩、返回键取消都是白送的），而 BottomSheet 需要额外的
 * `rememberModalBottomSheetState` + `sheetState.hide()` 收尾协程，
 * 在这个只有「输入 + 列表」的场景里收益为零、出错面反而更大。
 *
 * `properties` 里关掉 `dismissOnClickOutside`：输入法和候选列表会占掉大半屏，
 * 手指点到列表外面很容易误触把刚输入的城市名一起关掉，这点挫折感不值当。
 */
@Composable
private fun CitySearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    outcome: CitySearchResult?,
    message: String?,
    onSearch: () -> Unit,
    onPick: (CityMatch) -> Unit,
    onDismiss: () -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("手动选择城市") },
        text = {
            Column {
                Text(
                    text = "查天气不需要定位权限，输入城市名即可。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("城市名") },
                    placeholder = { Text("如：荔湾、广州、佛山") },
                    // 输入法上的「搜索」键等价于点按钮：输完直接回车是手机上最顺手的路径，
                    // 逼用户把手指从键盘移到按钮上纯属多余。
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            onSearch()
                        }
                    ),
                    // 边框用主题色而不是硬编码灰色，深色模式与换主题都能自动跟随。
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            keyboard?.hide()
                            onSearch()
                        },
                        // 输入为空或正在搜索时点不动：空关键词请求只会换回一句
                        // 「没找到」，不如让按钮直接置灰把原因摆在明面上。
                        enabled = query.isNotBlank() && !searching
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("搜索")
                    }
                    if (searching) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 失败/空结果的说明就地显示，不打断对话框（关掉再弹 Snackbar 会让人丢失输入）。
                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val matches = (outcome as? CitySearchResult.Success)?.matches.orEmpty()
                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        matches.forEach { match ->
                            // 用 OutlinedButton 而不是自定义可点行：它就是「一行文字 + 点击」，
                            // 复用 M3 的点击涟漪、最小高度与无障碍语义，省掉一堆手写代码。
                            OutlinedButton(
                                onClick = { onPick(match) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = match.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val region = match.regionText()
                                    if (region.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = region,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun LoadingCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "正在获取天气…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 权限门控卡片：开关没开 / 系统没授权，都用它引导用户去处理。
 *
 * [secondaryText] 是新增的可选副按钮（当前只有一个用途：手动选择城市）。
 * 做成可空参数而不是再复制一个「带两个按钮的门控卡片」：两张卡片除了按钮数量完全相同，
 * 复制一份就意味着以后改文案、改间距要改两处，迟早不一致。
 */
@Composable
private fun GateCard(
    icon: ImageVector,
    title: String,
    description: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {}
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionText) }
            if (secondaryText != null) {
                Spacer(Modifier.height(4.dp))
                // 副动作刻意用 TextButton（无边框、更弱）：主按钮是「把权限问题解决掉」，
                // 手动选城市只是绕过它，两者视觉权重不该一样。
                TextButton(onClick = onSecondary) { Text(secondaryText) }
            }
        }
    }
}

/** 失败卡片：提示 + 重试（或去设置）+ 可选的「手动选择城市」。 */
@Composable
private fun ErrorCard(
    title: String,
    description: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {}
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionText) }
            if (secondaryText != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onSecondary) { Text(secondaryText) }
            }
        }
    }
}

@Composable
private fun WeatherContent(weather: WeatherInfo) {
    // ---- 当前天气 ----
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = weather.placeName,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // 大字温度 + 天气图标：温度数字本身没有「什么天气」的信息，
            // 并排放一个图标，扫一眼就知道今天要不要带伞，比读一行小字快得多。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = weather.temperature.temperatureText(),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = weatherIconOf(weather.weatherCode),
                    contentDescription = weather.condition,
                    // 跟着温度大字用主题色，深色模式 / 换主题自动适配，不引入新色值。
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CURRENT_WEATHER_ICON_SIZE)
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = weather.condition,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricCell("体感", weather.apparentTemperature.temperatureText())
                MetricCell("湿度", "${weather.humidity}%")
                MetricCell("风速", weather.windSpeed.speedText())
            }
        }
    }

    // ---- 未来 5 天 ----
    if (weather.daily.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = "未来 ${weather.daily.size} 天",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    weather.daily.forEach { day -> DailyColumn(day) }
                }
            }
        }
    }

    Text(
        text = "天气数据来自 ${weather.dataSource}",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
}

/**
 * 手动选中城市后的天气内容。
 *
 * 与自动模式的 [WeatherContent] 共用同一套「当前天气 + 未来 5 天」版式，
 * 只多一条「使用当前位置」入口：手动选择是把定位失败绕过去了，
 * 但用户可能只是临时查一下别的城市，得有一条明显的回头路，
 * 否则他只能退出页面再进来（那时才会重新走一次自动定位）。
 */
@Composable
private fun ManualWeatherContent(
    weather: WeatherInfo,
    onUseCurrentLocation: () -> Unit
) {
    WeatherContent(weather)

    TextButton(
        onClick = onUseCurrentLocation,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Filled.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text("使用当前位置", fontSize = 14.sp)
    }

    Text(
        text = "数据来自 ${weather.dataSource}；地名来自城市搜索",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
}

/** 当前天气下面那三个小指标。 */
@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 未来 5 天的单列：星期 / 月日 / 图标 / 现象 / 最低~最高。 */
@Composable
private fun DailyColumn(day: DailyForecast) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = day.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        // 光有「周三」看不出是哪个星期，补一个月日做锚点。
        Text(
            text = day.dateLabel,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Icon(
            imageVector = weatherIconOf(day.weatherCode),
            contentDescription = day.condition,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(DAILY_WEATHER_ICON_SIZE)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = day.condition,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${day.maxTemperature.temperatureText()} / ${day.minTemperature.temperatureText()}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

// ---- 天气码 → 图标 ---------------------------------------------------------

/** 当前天气大图标的尺寸：与 64sp 的温度数字视觉高度相当，但不抢温度的主体地位。 */
private val CURRENT_WEATHER_ICON_SIZE = 52.dp

/** 未来 5 天里的小图标：一列只有 ~1/5 屏宽，再大就会把日期挤到换行。 */
private val DAILY_WEATHER_ICON_SIZE = 26.dp

/**
 * WMO 天气码 → 图标。
 *
 * **分档必须与 [WeatherRepository] 里 `weatherConditionOf` 的那份完全一致**：
 * 两边一旦不一致，就会出现「写着晴、画着雨」这种自相矛盾的界面。
 * 之所以不把这个映射放到 data 层，是因为 [ImageVector] 属于 Compose 的 UI 资产，
 * 让数据层依赖 `material.icons` 会把「查数据」和「怎么画」绑死，
 * 以后换图标库（或给平板用另一套图标）就得改数据层。
 *
 * 图标名全部实际核对过 `material-icons-extended`（BOM 2025.03.00 → 1.7.8）里
 * `androidx.compose.material.icons` 下真实存在的类，不存在猜名字的情况；
 * 其中「雾」没有 `Foggy`（该名字在 Material Symbols 有、在 Compose 图标库里没有），
 * 用 [Icons.Filled.Dehaze]（三条横线代表霾/雾）代替，比拿云朵硬凑更贴切。
 *
 * 未知码给 [Icons.AutoMirrored.Filled.HelpOutline] 而不是随便挑一个天气图标：
 * 猜错天气比明说「不知道」更糟，也让码表变化时的问题一眼可见。
 * 用 AutoMirrored 版本是为了与本项目其它图标一致（`Icons.Filled.HelpOutline` 已被官方弃用），
 * 顺带消掉一条编译期弃用告警。
 */
private fun weatherIconOf(weatherCode: Int): ImageVector = when (weatherCode) {
    0 -> Icons.Filled.WbSunny
    in 1..3 -> Icons.Filled.Cloud
    45, 48 -> Icons.Filled.Dehaze
    in 51..67 -> Icons.Filled.WaterDrop
    in 71..77 -> Icons.Filled.AcUnit
    in 80..82 -> Icons.Filled.Grain
    in 95..99 -> Icons.Filled.Thunderstorm
    // 其余（含 parseWeather 给的 -1 哨兵）统一用问号图标：
    // 这一档必须与 data 层 weatherConditionOf 的 else 分支一一对应，
    // 保证「写着未知、画着未知」，不会出现文案与图标互相打脸的情况。
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

/**
 * 手动选中城市的地名标签，交给数据层算（[CityMatch.placeLabel]）。
 *
 * ⚠️ 这里**刻意不再自己拼**：地名要按「**省-市-区**」排（需求：省在前、市在后），
 * 还要按层级裁掉「省 / 市 / 区」这些后缀——这套规则必须与自动定位那条路**共用同一份实现**，
 * 否则两处迟早一个显示「广东-广州-荔湾」、另一个显示「广州-荔湾」。
 * data 层把「哪一段是省、哪一段是市」在解析时就定好了，界面只负责显示。
 */
private fun manualPlaceLabel(match: CityMatch): String = match.placeLabel()

/**
 * 温度文案：NaN 说明服务端没给这个字段，显示「--」而不是「NaN°」。
 *
 * 取整显示（`%.0f`）是因为接口给的是 18.9 这样的原始值，
 * 小数点后一位对「今天冷不冷」这个判断没有任何增量信息。
 *
 * 格式化一律带 [Locale.US]：`String.format` 默认跟随系统 Locale，
 * 部分语言下 `%.0f` 会得到「19,0」这种带逗号的结果，出现在温度上就是错字。
 */
private fun Double.temperatureText(): String =
    if (isFinite()) String.format(Locale.US, "%.0f°", this) else "--"

private fun Double.speedText(): String =
    if (isFinite()) String.format(Locale.US, "%.0f km/h", this) else "--"
