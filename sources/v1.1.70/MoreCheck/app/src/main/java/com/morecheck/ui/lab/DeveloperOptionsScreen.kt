package com.morecheck.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.MoreCheckApp
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「实验室 → 开发者选项」（原「登录测试选项」）。
 *
 * 版式仿手机系统里的「开发者选项」：最上面一张总开关卡片（本页的视觉重心），
 * 下面按板块分组，每个板块一张卡片、卡片里若干开关行。
 * 颜色一律取自 [MaterialTheme]，因此**跟随主题色与深色模式设置**，不新造任何颜色值。
 *
 * ## 为什么直接拿 `AppContainer` 里的仓库，而不是写一个 ViewModel
 *
 * 本页各子项的**生效条件**都由 [com.morecheck.data.DeveloperOptionsRepository]
 * 里的 `combine(总开关, 子开关)` 统一算好，页面不需要再做一次运算，也不持有任何
 * 自己的状态；为一个纯"读写设置"的页面新建 ViewModel + Factory 只会多两个文件、
 * 多一层转发。这与本项目的 `WeatherScreen` / `PersonalInfoScreen` 拿
 * `settingsRepository` 的做法一致（见 `PersonalInfoScreen` 的 KDoc）。
 *
 * ⚠️ 必须走 `applicationContext` 再转 [MoreCheckApp]：`LocalContext.current`
 * 在 Compose 里通常是 Activity，直接 `as MoreCheckApp` 会 `ClassCastException`；
 * 而且 `applicationContext as` 拿到的是**进程内唯一**的那份容器——验证码测试生成的
 * 六位码就存在那个单例的字段里，换成别的实例会出现"刚发的码立刻失效"。
 * 用 `remember(context)` 缓存，避免每次重组都做一次类型转换与容器查找。
 *
 * ## 为什么「朋友圈红点提示」这一项**不走**这个仓库，而走 `LocalSettingsViewModel`
 *
 * 上面那条「不新建 ViewModel」的理由只适用于**本页独占**的设置：子开关的生效条件
 * 要在仓库层与总开关「与」一次，界面只管回显。
 * 但「朋友圈红点提示」不是这样——它的读取方在 `MoreCheckApp`（底部导航栏画角标那里），
 * 那份状态必须与这里**是同一个实例**，否则会出现"在开发者选项里拨了开关、底栏却不理会"。
 * 项目里 [com.morecheck.ui.LocalSettingsViewModel] 就是为此存在的（`MainActivity` 建好、
 * 全应用共享一份），「登录提示功能」页用的也是它，这里沿用同一条路，**不是**在自创第二种写法。
 *
 * ## 子项的「原始值」与「是否生效」是两份状态，本页刻意让它们各自显示（本项目踩过的坑）
 *
 * 仓库把子项拆成了**原始值**（`codeTestSwitch` / `perfTestSwitch` / `splashHoldSwitch`）
 * 与**是否生效**（`codeTestEnabled` / `splashHoldDurationMs` 等）两条流。页面上的控件**必须**读原始值：
 * - 若拿 `codeTestEnabled` 当 `checked`，一旦关掉总开关，子开关会**立刻全部跳到"关"**，
 *   用户会以为自己的设置被清空了（其实仓库里还留着），再打开总开关时又"自己弹回来"，
 *   这是最让人困惑的一种表现；
 * - 正确做法是 `checked = 原始值` + `enabled = 总开关`：**位置照旧显示、只是点不动**，
 *   "值还在、只是暂不生效"这件事在视觉上就自解释了。
 *
 * 因此本页不使用 `codeTestEnabled` / `perfTestEnabled` 这几条已经与过总开关的流——
 * 它们更适合"业务代码判断某项功能要不要真的跑"，而不是回显开关位置。
 *
 * ## 需求边界（用户明确要求「不要额外增加功能」）
 *
 * - 「性能测试」有真实作用：它门控板块里的**开屏停留时长滑块**（0~10 秒）；
 * - 「图标测试」整体改版为**真实的图标更改**（内置图标切桌面图标 + 自定义图上传裁切），
 *   原来那个只演示动画的占位开关已按需求删除；
 * - 「图标测试」本轮新增一个「朋友圈红点提示」开关：门控底部导航栏「发现」入口的未读角标
 *   **要不要画**（默认开 = 与改造前观感一致）；它**不**改变未读状态本身，
 *   见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc；
 * - 没有重置按钮（滑块自带「恢复默认」）、没有导入导出、没有日志查看、没有"关于开发者选项"。
 *
 * ## ⚠️ 本页两个板块的「门控」口径**故意不同**，别当成不一致
 *
 * | 板块 | 子项 | 生效条件 |
 * |---|---|---|
 * | 性能测试 | 开屏时长滑块 | `DEV_MODE_ENABLED` **且** `DEV_PERF_TEST_ENABLED` |
 * | 图标测试 | 图标更改 | 只有 `DEV_MODE_ENABLED` |
 * | 图标测试 | 朋友圈红点提示 | 只有 `DEV_MODE_ENABLED` |
 *
 * 差异的来源就是需求本身：
 * - 需求原文说滑块「受性能测试总开关控制」，所以它有两个约束；
 * - 而「图标测试」原来那个子开关**已被删除**，图标更改与新增的「朋友圈红点提示」
 *   都直接挂在该板块下，按「总开关关掉后子项不生效」这条本页规矩，它们只需总开关。
 *
 * ## 「朋友圈红点提示」与「图标更改」为什么在同一个卡片里
 *
 * 需求把它归在「图标测试」分组（它是底部导航栏**图标上的红点**，确实属于"图标测试"的范畴）。
 * 因此图标测试卡片里现在是「一行入口 + 一行开关」，两种行型各一个，不再有第三个。
 *
 * ⚠️ 它与 [SplashHoldRow] 那种"值 + 生效条件"的双状态不同：
 * 它只有**一个**布尔值，门控也只是总开关，因此直接复用 [DeveloperSwitchRow] 即可，
 * 不要为它再造一个组件（本页已经出现过"三份几乎一样的 Row 各自漂移"的问题）。
 *
 * 页脚那句说明会把这个差异讲给用户听，否则「为什么这个板块没有开关」会被当成 bug。
 *
 * @param onBack 点左上角返回箭头时回调（由导航图决定回哪一页）。
 * @param onMessage 页面级一次性提示。本页只在**关闭总开关且确实保留了子项设置**时报一句，
 *   用来消除"关掉总开关是不是把子设置也重置了"的误解；其余情况不打扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 见上方 KDoc：必须 applicationContext + 进程内单例容器。
    val repository = remember(context) {
        (context.applicationContext as MoreCheckApp).container.developerOptionsRepository
    }

    // 总开关：同时决定本页各子项是否可交互。
    val masterEnabled by repository.masterEnabled.collectAsStateWithLifecycle(initialValue = false)

    // 子项的**原始值**（仅用于回显，不代表是否生效）。
    // initialValue 给 false：DataStore 首次读取要走磁盘，先渲染成"关"再被真实值覆盖，
    // 比给 true 安全——真值是"开"时顶多闪一下，而给 true 会在真值是"关"时出现
    // "开关自己弹回去"的假象。
    val codeTestSwitch by repository.codeTestSwitch.collectAsStateWithLifecycle(initialValue = false)
    val perfTestSwitch by repository.perfTestSwitch.collectAsStateWithLifecycle(initialValue = false)

    // 「朋友圈红点提示」的**原始值**（默认 true，持久化在 DataStore 的 `moments_badge_enabled`）。
    //
    // ## 为什么这里可以不传 initialValue
    //
    // 它来自 `MainActivity` 建好的那份 [com.morecheck.ui.settings.SettingsViewModel]，
    // 是一条 `StateFlow<Boolean>`（初值就是 `true`，见 `SettingsViewModel` 的字段声明），
    // 因此 `collectAsStateWithLifecycle()` 直接拿它的当前值当初始值即可——不会像上面那些
    // DataStore 流一样先渲染成 `false` 再被真值覆盖（那正是上面几行需要显式给 initialValue 的原因）。
    //
    // ## 为什么走 `LocalSettingsViewModel` 而不是仓库
    //
    // 与「登录提示功能」页同一选择：复用同一份实例，用户在这里拨动开关，
    // `MoreCheckApp` 里读的也是同一份状态，回主界面立刻生效、不用重启。
    // 自己 `viewModel(...)` 新建一份会多出一份状态，正是本项目历史上「开关关不掉」的成因。
    val settingsViewModel = LocalSettingsViewModel.current
    val momentsBadgeEnabled by settingsViewModel.momentsBadgeEnabled.collectAsStateWithLifecycle()

    // 开屏停留时长的**原始值**（用户设的那个数）。
    // initialValue 用仓库里的默认值而不是 0：给 0 会让滑块先跳到最左再弹回真实位置，
    // 而默认值就是"没调过时应该是多少"，先渲染成它最接近真实、视觉上最不突兀。
    val splashHoldMs by repository.splashHoldSwitch.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_SPLASH_HOLD_MS
    )

    // 「通知测试」要真的发通知，所以需要通知工具（与仓库同源，都是进程内单例）。
    val notificationHelper = remember(context) {
        (context.applicationContext as MoreCheckApp).container.notificationHelper
    }

    // 「通知测试」的冷却剩余秒数（需求：按钮有 10s 间隔）。
    // 归零之前按钮置灰并显示还剩几秒，避免连点刷屏。
    var notifyCooldown by remember { mutableIntStateOf(0) }
    LaunchedEffect(notifyCooldown) {
        if (notifyCooldown > 0) {
            delay(1_000)
            notifyCooldown -= 1
        }
    }

    // 「图标更改」是否正显示着。
    //
    // ## 为什么是**就地覆盖**，而不是加一条导航路由
    //
    // 走路由要在 `ui/nav/TabDef.kt` 里加一个 `TabDestination`、再到 `ui/nav/TabNavHost.kt`
    // 注册 `composable`，把一个纯 UI 的子流程提升成全局导航状态（还要处理
    // 「返回时回本页还是回上一页」）。本项目的 `PersonalInfoScreen → AvatarCropScreen`
    // 已经确立了**就地覆盖**这个做法（见那个文件的注释），这里沿用同一套，
    // 好处是「图标更改」完全不依赖导航层，也不需要改任何导航文件。
    // 系统返回键由 `IconPickerScreen` 内部的 `BackHandler` 接住。
    var showIconPicker by remember { mutableStateOf(false) }

    // ---- 图标更改：整页覆盖本页 ----
    if (showIconPicker) {
        IconPickerScreen(
            // 门控只有一个总开关（图标测试没有独立子开关了，见上方 KDoc 的门控表）。
            enabled = masterEnabled,
            onBack = { showIconPicker = false },
            onMessage = onMessage
        )
        // 这里用提前 return 而不是把下面整页包进 `else`：本函数体已经很长，
        // 再缩进一层会让本就靠注释分段的版式更难读。Compose 允许可组合函数提前返回，
        // 只要**所有分支出组合的 UI 数量稳定**即可——而这里两个分支各自是一个完整的
        // `Scaffold`，切换时是「整页换整页」，不存在"某个分支多一个可组合项"的槽位错位问题。
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发者选项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        // 整页滚动（与 PermissionsScreen 同一写法）：四个卡片在矮屏 / 大字体下会超出一屏。
        // 卡片数量固定且很少，不值得上 LazyColumn——那反而要处理滚动位置恢复。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ---- 总开关（本页视觉重心）--------------------------------------
            MasterSwitchCard(
                checked = masterEnabled,
                onCheckedChange = { enabled ->
                    scope.launch {
                        // 写仓库是 suspend（DataStore 落盘）。放进协程而不是
                        // runBlocking：后者会在主线程等磁盘 IO，点一下就能感觉出卡顿。
                        repository.setMasterEnabled(enabled)
                        // 只在"关闭总开关 + 确实有子项处于开的位置"时提示：
                        // 全关状态下说这句就是噪音。这里读的是上面那几个原始值流，
                        // 回调执行时读到的就是此刻的最新值。
                        //
                        // ⚠️ 判断条件里刻意**不含**「朋友圈红点提示」：它的默认值是 `true`，
                        // 把它算进来就等于"每次关总开关都弹这句提示"，反而成了噪音。
                        // 那句提示只在"用户自己开过某项、现在被暂时压住"时才有信息量；
                        // 而这里只需保证条件里不出现已删除的 `iconTestSwitch`（那会编译不过）。
                        if (!enabled && (codeTestSwitch || perfTestSwitch)) {
                            onMessage("开发者选项已关闭，子开关的位置已保留")
                        }
                    }
                }
            )

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 登录测试 ---------------------------------------------------
            // 唯一有真实作用的板块：它的值会被登录 / 注册的「发送验证码」流程读到
            // （`DeveloperOptionsRepository.isCodeTestEnabledNow()`）。
            DeveloperSectionTitle("登录测试")
            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    title = "验证码测试",
                    description = CODE_TEST_DESCRIPTION,
                    checked = codeTestSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setCodeTestEnabled(it) } }
                )
                // ---- 通知测试（需求：本板块新增一个按钮）------------------------
                //
                // 与上面那一行的关系：验证码测试是「开关 + 后续由登录页触发」，
                // 这个是「当场按一下、马上验证」——用来单独确认**通知权限到底有没有开**。
                //
                // 分隔线与别的设置行同宽（左侧缩进 16dp，和文字对齐）。
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )
                DeveloperActionRow(
                    title = "通知测试",
                    description = NOTIFY_TEST_DESCRIPTION,
                    // 需求（v1.1.68）：按钮**固定宽度**，不随文案长度变化。
                    //
                    // 原先文案是「发送通知」↔「Ns 后可再测」，两者宽度差很多，
                    // 冷却期间按钮会**抽一下**（宽度跳动），看着像布局坏了。
                    // 现在文案收敛成「发送通知」与「As」（A = 实际剩余秒数），
                    // 两者都短，再配合下面 `DeveloperActionRow` 的固定 `Modifier.width`，
                    // 冷却前后按钮尺寸完全一致。
                    buttonText = if (notifyCooldown > 0) {
                        "${notifyCooldown}s"
                    } else {
                        "发送通知"
                    },
                    // 总开关关着时本板块整体不生效，按钮同样置灰（与子开关一致的口径）。
                    enabled = masterEnabled && notifyCooldown == 0,
                    onAction = {
                        val posted = notificationHelper.notifyTestHelloWorld()
                        // 无论成功失败都进冷却：成功是防连点刷屏，
                        // 失败（权限没开）也进冷却，避免用户狂点却一直只看到同一句提示。
                        notifyCooldown = NOTIFY_TEST_COOLDOWN_SECONDS
                        onMessage(
                            if (posted) {
                                "已发送测试通知，请下拉通知栏查看"
                            } else {
                                "通知权限未开启，请到系统设置里允许后再试"
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 性能测试 ---------------------------------------------------
            DeveloperSectionTitle("性能测试")
            Card(Modifier.fillMaxWidth()) {
                DeveloperSwitchRow(
                    title = "启用性能测试",
                    description = PERF_TEST_DESCRIPTION,
                    checked = perfTestSwitch,
                    enabled = masterEnabled,
                    onCheckedChange = { scope.launch { repository.setPerfTestEnabled(it) } }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 开屏显示时长（需求：以滑块形式在 0~10 秒间调节，可一键恢复默认）----
                //
                // 门控：**两个开关都开才能拖**（总开关 + 性能测试），见下方 KDoc 与
                // `DeveloperOptionsRepository.splashHoldDurationMs` 的说明。
                // 不满足时滑块置灰、**但仍显示当前值**——「值还在、只是暂不生效」这条口径
                // 与别的子项完全一致，不能让用户以为设置被清空了。
                SplashHoldRow(
                    holdMs = splashHoldMs,
                    enabled = masterEnabled && perfTestSwitch,
                    onValueChange = { scope.launch { repository.setSplashHoldMs(it) } },
                    onRestoreDefault = {
                        scope.launch { repository.setSplashHoldMs(SettingsRepository.DEFAULT_SPLASH_HOLD_MS) }
                    }
                )
            }

            Spacer(Modifier.height(SECTION_SPACING))

            // ---- 图标测试（本轮整体改版为真实功能）--------------------------
            //
            // 需求：原占位开关删掉，新增「图标更改(实验性)」。
            // 这里不再是一个 `Switch`，而是一行**入口**（点开进 `IconPickerScreen`）——
            // 该功能自己就是一个有多张卡片、有裁切流程的页面，塞不进一个开关行。
            DeveloperSectionTitle("图标测试")
            Card(Modifier.fillMaxWidth()) {
                DeveloperActionRow(
                    title = "图标更改(实验性)",
                    description = ICON_CHANGE_DESCRIPTION,
                    buttonText = "进入",
                    // 门控只有总开关一个：图标测试没有独立子开关了（见上方 KDoc 的门控表）。
                    enabled = masterEnabled,
                    onAction = { showIconPicker = true }
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(start = 16.dp)
                )

                // ---- 朋友圈红点提示（本轮新增）--------------------------------
                //
                // 需求：底部导航栏「发现」入口的未读红点 / 数字可以关掉，**默认开启**。
                //
                // 门控与其他子项完全一致：只有总开关一个（`enabled = masterEnabled`），
                // 关掉总开关时这一行置灰、点不动，但**开关位置照旧显示自己的值**——
                // 这就是 [DeveloperSwitchRow] 里 `checked`（原始值）与 `enabled`（是否可交互）
                // 分开传的原因，不要在这里改成读"生效值"。
                //
                // ⚠️ 它门控的是**显示**，不是未读状态本身：进朋友圈清未读那条链路
                // （`TabNavHost` 的 `onMomentsOpened` → `TabBadgeViewModel.clear(DISCOVER)`）
                // 不受此开关影响，见 `SettingsRepository.momentsBadgeEnabled` 的 KDoc。
                DeveloperSwitchRow(
                    title = MOMENTS_BADGE_TITLE,
                    description = MOMENTS_BADGE_DESCRIPTION,
                    // 原始值：来自 DataStore（默认 true），与总开关无关。
                    checked = momentsBadgeEnabled,
                    // 与「验证码测试」「启用性能测试」同一口径：只受顶部总开关门控。
                    enabled = masterEnabled,
                    onCheckedChange = { settingsViewModel.setMomentsBadgeEnabled(it) }
                )
            }

            // ---- 页脚说明 ---------------------------------------------------
            // 只解释"总开关与子项的关系"这条最容易误解的规则，不引入任何新功能。
            // 与 PermissionsScreen / SoundSettingsScreen 一样收在页面末尾，
            // 让用户在读完开关后顺手看到，而不是一进页面就被一段说明挡住。
            Spacer(Modifier.height(SECTION_SPACING))
            Text(
                text = "子项只在总开关开启时生效；关闭总开关不会清空它们的设置，" +
                    "重新打开后按原样恢复。性能测试的开屏时长还需要「启用性能测试」" +
                    "也打开才会生效；图标更改执行时立即作用于桌面图标。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 页首的总开关卡片。
 *
 * 用 [MaterialTheme.colorScheme.primaryContainer] 作容器色，是为了让它在下面几张卡片里
 * **一眼看出是"总"**（参考图里的开发者选项也是这种高亮卡片）。这一点很关键：
 * 本页各子项在总开关关闭时都是灰的，如果总开关卡片和下面几张长得一样，
 * 用户会先去找"为什么点不动"，而不是先来开这个总开关。
 *
 * 不写死颜色：`primaryContainer` 会随主题色走，深色模式下 Material3 给的是一档很深的
 * 同色系容器色，文字用对应的 `onPrimaryContainer`，因此**不会出现深色卡片上的深色字**。
 * 卡片内的开关也用默认配色（不额外指定），它在容器色上依然有足够对比度。
 */
@Composable
private fun MasterSwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    // 与右侧开关拉开距离：标题换行时不会贴到开关上。
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "启用开发者选项",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "开启此开关后，下列功能才会生效",
                    style = MaterialTheme.typography.labelSmall,
                    // 副标题压到 75%：同一容器色内的主次关系，靠 alpha 而不是换颜色。
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * 分区小标题（「登录测试」「性能测试」「图标测试」）。
 *
 * 与 `SoundSettingsScreen.SectionTitle` 取同一套视觉语言（labelLarge + onSurface 压到 75%），
 * 但**函数名不叫 `SectionTitle`**：本文件与 `LabScreen.kt` 同属 `com.morecheck.ui.lab` 包，
 * 顶层函数一旦重名（哪怕一个是 private）都会在编译期报 Conflicting overloads；
 * 实验室那几个文件正被并行改动，起一个本页专属的名字可以彻底避开这种撞车。
 *
 * 为什么不用 `onSurfaceVariant`：它在浅色模式下偏灰（#888888），而本项目设置类页面的
 * 小标题统一走 `onSurface` 压暗，比它更黑一点，两者放一起才不会显得"两套灰"。
 */
@Composable
private fun DeveloperSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 一行开关：标题 + 说明 + 右侧 [Switch]。
 *
 * 三项子开关的结构完全同构，所以抽成一个组件，只在调用处绑各自的标题、说明与 setter；
 * 否则三份几乎一样的 Row 会各自漂移（本项目已经出现过"某一页的副标题忘了压暗"这类不一致）。
 *
 * ## `checked` 与 `enabled` 是两个不同的概念
 *
 * - [checked] 传**仓库里的原始值**——总开关关掉后，子开关仍然显示它自己的位置；
 * - [enabled] 传**总开关**——总开关关掉时这一行整体置灰、点不动。
 *
 * 两者刻意不合并成一个参数：合并之后"用生效值当 checked"这种写法会变得很顺手，
 * 而那正是会把用户设置"看起来清空"的写法（详见 [DeveloperOptionsScreen] 的 KDoc）。
 *
 * ## 置灰为什么是给文字乘 alpha，而不是换一组灰色
 *
 * [Switch] 在 `enabled = false` 时由 Material3 自己按规范调淡，所以**不能**把 alpha 套在
 * 整行上，否则开关会被双重调淡、比文字还浅。这里只对左侧文字区乘
 * [DISABLED_CONTENT_ALPHA]（与 M3 内部同一档），让"文字和开关一样灰"。
 */
@Composable
private fun DeveloperSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                // 1f = 不透明，等于不加任何效果（alpha 会触发一次图层合成，
                // 但只在总开关切换时变化，代价可以忽略）。
                .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                // 标题用项目统一的 strongTextColor（浅色模式纯黑），
                // 与「权限管理」「声音设置」的开关行同口径。
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                // 说明文字可能有两三行，行距靠 typography 自带的行高，不再手工加 Spacer。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            // 总开关关闭时由 Material3 自己置灰并屏蔽点击，
            // 不需要额外写 `clickable(enabled = false)` 之类的兜底。
            enabled = enabled
        )
    }
}

/**
 * 「带按钮」的一行：标题 + 说明 + 右侧按钮。
 *
 * 与 [DeveloperSwitchRow] 同构（同一套内边距 / 字号 / 置灰系数），
 * 区别只在右侧是**动作**而不是开关——「通知测试」按一下立刻发生，
 * 没有"开/关"这种持续状态，用 `Switch` 表达反而会让人以为它会记住什么。
 *
 * 置灰同样只乘在左侧文字上（理由见 [DISABLED_CONTENT_ALPHA]），
 * 按钮则交给 Material3 自己按 `enabled` 调淡。
 */
@Composable
private fun DeveloperActionRow(
    title: String,
    description: String,
    buttonText: String,
    enabled: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        OutlinedButton(
            onClick = onAction,
            enabled = enabled,
            // 需求（v1.1.68）：「通知测试」的按钮要**固定大小**，不随文本变化而改变。
            //
            // 之所以要显式定宽而不是只缩短文案：文案再短也仍是「三个字 vs 两个字符」，
            // 布局上宽度仍会差几十 px，冷却一进一出按钮就会抖。
            // 定宽后无论显示「发送通知」还是「9s」，按钮外框**完全不动**。
            //
            // 取 104dp：能舒服容纳「发送通知」（四个汉字 @ labelLarge 约 64dp + 默认左右内边距），
            // 又不会把左侧说明文字挤得太窄（说明文案允许换行，见上面的 `weight(1f)`）。
            modifier = Modifier.width(ACTION_BUTTON_WIDTH)
        ) {
            // 固定宽度下文字可能溢出/换行，统一单行显示并居中。
            Text(text = buttonText, maxLines = 1)
        }
    }
}

/**
 * 「开屏显示时长」滑块行：标题 + 说明 + 当前值 + 滑块 + 「恢复默认」。
 *
 * ## 档位为什么是 0.1 秒
 *
 * 需求只说「0~10 秒间调节」，档位由实现定。选 **0.1 秒**（而不是 0.5 秒或整数秒）的唯一原因，
 * 是**默认值 900ms 必须能被档位精确表示**：
 * - 本项目改造前的开屏时长就是 900ms（见 [SettingsRepository.DEFAULT_SPLASH_HOLD_MS]），
 *   「恢复默认」必须回到与改造前**逐毫秒相同**的行为；
 * - 若档距取 0.5 秒，900 不是 500 的整数倍，Material3 的 `Slider` 会把**滑块位置**吸附到
 *   最近的 1.0 秒档，于是首屏出现「滑块停在 1.0 秒、右边文字却写 0.9 秒」的自相矛盾
 *   （`SliderState.setValue` 内部会 `snapValueToTick`，核对过 material3 1.3.1 字节码）。
 *   靠注释解释这处不一致是下策——**让档距能整除 900 才是上策**，0.1 秒档下 900 正好是第 10 档。
 * - 代价是档位多（101 档），但 `Slider` 本就是连续拖动控件，档位只决定吸附点，
 *   101 档在手机宽度上每档约 3dp，拖动手感不受影响。
 *
 * ## 滑块的取值域直接用「毫秒」，不做归一化
 *
 * `Slider` 的 `value` / `valueRange` 都按**毫秒**（0f~10000f）传，不换算成 0f~1f 的比例。
 * 理由：`steps` 的含义是「在 min 与 max 之间插入几个离散点」，与单位无关，
 * 所以按原值域传就能得到正确的档位；反过来若归一化，回调里还要再乘回 10000，
 * 多一层换算就多一处取整误差（本项目在别处踩过"换算丢精度"的坑）。
 *
 * 唯一的代价是 `value` 的绝对值很大（浮点表示 10000 完全精确，无精度损失）。
 *
 * ## 置灰时的表现
 *
 * 不满足门控时（总开关关 / 性能测试没开）：滑块与「恢复默认」不可点，
 * **但当前值照旧显示**（数字文字只调暗、不清空）。
 * 理由与 [DeveloperSwitchRow] 的 `checked`/`enabled` 之分完全一致：
 * 「值还在、只是暂不生效」必须能自解释，否则用户会以为自己的设置被清空了。
 */
@Composable
private fun SplashHoldRow(
    holdMs: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onRestoreDefault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    // 只对左侧文字乘 alpha，理由见 [DISABLED_CONTENT_ALPHA]：
                    // 整行套 alpha 会让已经被 Material3 调淡的滑块被双重调淡。
                    .alpha(if (enabled) 1f else DISABLED_CONTENT_ALPHA)
            ) {
                Text(
                    text = SPLASH_HOLD_TITLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = SPLASH_HOLD_DESCRIPTION,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            // 当前值：统一显示成「N.N 秒」，与滑块的 0.1 秒档位对得上。
            //
            // 用 strongTextColor 而不是主题色：主题色在这里会与「恢复默认」按钮的文字色
            // 抢注意力，而当前值只是一个读数，不该比动作按钮更显眼。
            Text(
                text = formatHoldSeconds(holdMs),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
                    .copy(alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA)
            )
        }

        Spacer(Modifier.height(4.dp))

        Slider(
            value = holdMs.toFloat(),
            onValueChange = { raw ->
                // 吸附到 [SettingsRepository.SPLASH_HOLD_STEP_MS] 的整数倍：
                // 触摸拖动给出的是连续值（如 2371.4ms），不吸附的话
                // DataStore 里会存下"2371"这种数，看起来像随机噪声；
                // 吸附之后存储值永远是 100 的倍数，与界面显示、档位完全一致。
                //
                // `roundToInt` 而不是 `toInt`：后者是截断，会让"往上拖一点"经常没反应。
                // ⚠️ 档距必须是 100：默认值 900 要能被它整除，否则滑块位置会被
                // `Slider` 吸附到隔壁档、与右侧文字对不上（详见本行 KDoc）。
                val snapped = (raw / SettingsRepository.SPLASH_HOLD_STEP_MS).roundToInt() *
                    SettingsRepository.SPLASH_HOLD_STEP_MS
                onValueChange(
                    snapped.coerceIn(
                        SettingsRepository.MIN_SPLASH_HOLD_MS,
                        SettingsRepository.MAX_SPLASH_HOLD_MS
                    )
                )
            },
            valueRange = SettingsRepository.MIN_SPLASH_HOLD_MS.toFloat()..
                SettingsRepository.MAX_SPLASH_HOLD_MS.toFloat(),
            // steps = 档位数 - 2（两端点不算"插入"的步）。
            // 0~10 秒按 0.5 秒一档共 21 档 → steps = 19。写成算式而不是硬编码 19：
            // 以后改档距或上限时这里自动跟着变，不会留下一个"魔法数字 19"。
            steps = SPLASH_HOLD_STEPS,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )

        // 「恢复默认」放在滑块**下方靠右**，而不是与标题挤在同一行：
        // 标题行已经有当前值了，再塞一个按钮会让长中文标题换行、右边被压得很窄。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRestoreDefault, enabled = enabled) {
                Text("恢复默认")
            }
        }
    }
}

/**
 * 把毫秒格式化成「N.N 秒」。
 *
 * 一律保留一位小数（`3.0 秒` 而不是 `3 秒`）：档位是 0.5 秒，位数固定时
 * 数字宽度不会跳动——`3 秒` 与 `10 秒` 之间来回拖会让右侧文字左右抖动，很难看。
 *
 * 用整数运算（`ms / 1000` 与 `ms % 1000 / 100`）而不是浮点除法 + 格式化：
 * 浮点在这里没有任何精度收益，却要多引入一个 `String.format` 的 Locale 问题
 * （某些区域会把小数点写成逗号），这是个显示给用户看的读数，稳定比花哨重要。
 */
private fun formatHoldSeconds(ms: Int): String {
    val seconds = ms / 1000
    val tenths = (ms % 1000) / 100
    return "$seconds.$tenths 秒"
}

/**
 * 子开关失效（总开关关闭）时，左侧文字区的不透明度。
 *
 * 取 0.38f：这是 Material 规范里"禁用态内容"的标准系数，Material3 的
 * `Switch` / `Checkbox` 内部调淡用的也是同一档。跟它对齐之后，
 * 置灰的文字与右边缘已经自动置灰的开关**深浅一致**，不会出现
 * "文字比开关更淡"或"开关淡了文字还是黑的"这种割裂感。
 *
 * 只乘在主题色上（`Modifier.alpha` 是合成期系数），因此**不产生新的颜色值**，
 * 深色模式下自动变成"深色卡片上的浅灰字"。
 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 三个板块之间的垂直间距。
 *
 * 取 20.dp：必须明显大于卡片内部 14.dp 的行内边距，否则"板块标题 + 卡片"这三组
 * 会挤成一整张长表，看不出是三个独立板块；但也不能太大，否则在矮屏上要滚动才能看全
 * 三个板块，失去"一屏扫完"的直观。
 */
private val SECTION_SPACING = 20.dp

/**
 * 「验证码测试」的说明文案。
 *
 * 写得比另外两项长，是因为它是本页唯一有真实行为的一项：不把"随机生成六位数字 → 记录 →
 * 发通知 → 必须一致才能登录"这条链路说清，用户会以为它和下面两个开关一样只是动画。
 * 用「发送验证码」的全角引号，与登录 / 注册页上的按钮文案保持字面一致。
 */
private const val CODE_TEST_DESCRIPTION =
    "开启后，点击「发送验证码」会在后台随机生成六位数字并记录，同时以通知形式发给你；" +
        "只有填写与记录的验证码一致才能登录成功。"

/** 「性能测试」的说明文案：写清它现在**真的会改变开屏时长**，不再只是动画。 */
private const val PERF_TEST_DESCRIPTION =
    "开启后，下方的开屏显示时长设置才会生效"

/**
 * 「开屏显示时长」滑块的标题。
 *
 * 用「显示时长」而不是「额外停留时长」：用户看到的现象是「Hello World 停在屏幕上的时间」，
 * 而"额外"这个词在界面上没有对应物（它只是实现里相对"最短展示"的说法）。
 */
private const val SPLASH_HOLD_TITLE = "开屏「Hello World」显示时长"

/**
 * 「开屏显示时长」滑块的说明文案。
 *
 * 三个信息缺一不可：
 * 1. 它管的是**哪一屏**（开屏的 Hello World，不是主界面加载）；
 * 2. 它需要**性能测试开关也打开**（否则用户会奇怪为什么拖不动）；
 * 3. 默认值是多少（「恢复默认」会回到哪）。
 */
private const val SPLASH_HOLD_DESCRIPTION =
    "控制开屏停留时长，0~10 秒；需开启上方「启用性能测试」后才可调节"

/**
 * 滑块的 `steps` 参数。
 *
 * ## 为什么是「算出来的」而不是写死 19
 *
 * `steps` 的语义是「在 `valueRange` 两端之间**插入**几个离散点」，
 * 因此总档数 = `steps + 2`。本滑块的档数 = `(MAX - MIN) / STEP + 1 = 21`，故 `steps = 19`。
 * 写成算式后，以后改档距（比如改成 1 秒一档）或改上限时这里自动跟着变，
 * 不会留下一个与 `SettingsRepository` 里的常量对不上的魔法数字。
 *
 * 用 `Int` 运算：`(10000 - 0) / 500 = 20`，减 1 得 19，不存在取整问题。
 */
private val SPLASH_HOLD_STEPS =
    (SettingsRepository.MAX_SPLASH_HOLD_MS - SettingsRepository.MIN_SPLASH_HOLD_MS) /
        SettingsRepository.SPLASH_HOLD_STEP_MS - 1

/**
 * 「图标测试 → 图标更改」的说明文案。
 *
 * 必须写清两件事，否则用户一定会误解：
 * 1. 内置图标**真的会改桌面图标**（这是"实验性"的部分：桌面刷新时机由 launcher 决定）；
 * 2. 自定义上传的图片**只在应用内显示**（Android 限制：manifest 图标必须是资源）。
 *
 * 第二点尤其不能省——不写，用户上传完图片、回桌面发现没变，会认为功能坏了。
 */
private const val ICON_CHANGE_DESCRIPTION =
    "可切换内置桌面图标，或上传图片裁切后用于应用内显示；受 Android 限制，" +
        "上传的图片无法作为桌面图标"

/**
 * 「朋友圈红点提示」开关的标题。
 *
 * 用「红点提示」而不是「未读角标」：底部导航栏那个东西在用户嘴里就是"红点"
 * （数字也只是红点里的一行字），与本项目其他页面的口语化命名一致。
 */
private const val MOMENTS_BADGE_TITLE = "朋友圈红点提示"

/**
 * 「朋友圈红点提示」开关的说明文案（需求原文：「关闭后不再显示未读红点与数字」）。
 *
 * 刻意把「红点」与「数字」两种形态都写出来：角标在未读数为 1~99 时是**带数字的红点**，
 * 只写"不再显示红点"会让用户以为数字还会留着。
 *
 * 这里**不**解释「关掉后进朋友圈仍然会清未读」——那是实现语义，不是用户需要做的决定；
 * 真正需要防止误解的是下一个人改代码时把清除逻辑也一起门控，那句话写在 KDoc 里。
 */
private const val MOMENTS_BADGE_DESCRIPTION =
    "关闭后不再显示未读红点与数字"

/**
 * 「通知测试」按钮的说明文案。
 *
 * 说清「发一条什么内容」和「为什么要发」：用户按这个按钮的动机就是
 * 「我到底能不能收到通知」，所以文案要直接告诉他通知里会出现什么字样，
 * 好让他去通知栏核对。
 */
private const val NOTIFY_TEST_DESCRIPTION =
    "点一下立刻发一条内容为「Hello World」的通知，用来确认通知权限是否已开启"

/**
 * 「通知测试」的冷却秒数（需求：按钮有 10s 间隔）。
 *
 * 取 10s：比系统通知的默认提示音时长长得多，足够用户切到通知栏看完再回来；
 * 又短到不会让人觉得「按钮坏了」。冷却期间按钮直接显示剩余秒数，
 * 用户不用猜为什么点不动。
 */
private const val NOTIFY_TEST_COOLDOWN_SECONDS = 10

/**
 * 「动作按钮」的固定宽度（v1.1.68 需求：按钮不随文本变化而改变大小）。
 *
 * 取 104dp 的依据：`OutlinedButton` 默认左右内边距各 24dp，
 * 中文 `labelLarge`（14sp）四个字约 56dp → 24+56+24 = 104dp 刚好容纳「发送通知」；
 * 冷却态显示「9s」远短于它，于是**两种文案下外框完全相同**。
 *
 * 用固定 dp 而不是 `widthIn(min)`：后者只能保证"不窄于"，文案更长时仍会变宽，
 * 而需求要的是"不随文本变化"，必须是硬宽度。代价是文案超出时会单行截断——
 * 所以本页所有按钮文案都控制在 4 个汉字以内（见 [DeveloperActionRow] 的 `maxLines = 1`）。
 */
private val ACTION_BUTTON_WIDTH = 104.dp
