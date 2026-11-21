package com.morecheck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.MoreCheckRoot
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.PrivacyConsentGate
import com.morecheck.ui.SplashGate
import com.morecheck.ui.lab.ACTION_LAB_SHORTCUT
import com.morecheck.ui.lab.EXTRA_LAB_PAGE
import com.morecheck.ui.settings.SettingsViewModel
import com.morecheck.ui.theme.AccentColor
import com.morecheck.ui.theme.MoreCheckTheme
import com.morecheck.util.ThemeMode

class MainActivity : ComponentActivity() {

    /**
     * 桌面快捷方式带来的「直达实验室某一页」目标（v1.1.68 需求 1）。
     *
     * ## 为什么用 Activity 字段而不是 `rememberSaveable`
     *
     * 它是一个**外部事件**（点桌面图标 / 点悬浮窗的「跳转」），不是界面状态：
     * - `rememberSaveable` 只能记住"上次的值"，无法表达"这次是新来的一个意图"；
     * - 而 `onNewIntent` 在 Activity 已存活时触发，此时组合早已建立，
     *   唯一能把新意图送进去的办法就是改一个被组合观察的状态。
     *
     * 用 `mutableStateOf` 当载体：`onCreate`（冷启动）与 `onNewIntent`（热启动）
     * 都只是给它赋值，组合侧靠 `LaunchedEffect` 观察并消费，两条路径共用一套逻辑。
     *
     * ## 值是**目标页的 route** 字符串（如 `"notebook"`）
     *
     * 与 `TabDestination.route` 同名，因此 `MoreCheckRoot` 侧
     * `TabDestination.fromRouteOrNull(value)` 能直接命中（`Notebook("notebook")`），
     * 不需要再维护一张「快捷方式值 → 路由」的映射表。
     * `null` = 没有待处理的目标（也是被消费后的状态）。
     *
     * ## ⚠️ 一个**已知且刻意保留**的边界：同一页连着请求两次
     *
     * 消费方是 `LaunchedEffect(selectedTabName, labShortcutTarget)`，它的 key 是
     * **值比较**（`remember` 用 `equals`）。理论上存在这样一个窗口：
     * 消费把它置回 `null` 之后，若在**同一帧**（下一帧重组之前）又来一次
     * 「去 notebook」，那么组合看到的值从 `"notebook"` 直接变回 `"notebook"`，
     * key 没变、`LaunchedEffect` 不重启，第二次请求就被吞掉了。
     *
     * **为什么本轮不修**：修它要让消费方的参数从 `String?` 换成
     * 「携带身份的事件对象」（两个不同的对象 `equals` 为 false，key 必然变化），
     * 而那要改 `ui/MoreCheckApp.kt` —— 它不在本次允许改动的文件范围内。
     * 在 `MainActivity` 单方面改成事件对象也没有意义：参数类型是 `String?`，
     * 传进去时必然被 `.route` 拍平成一个字符串，身份在校验前就丢了。
     *
     * **为什么实际影响可以忽略**：这个窗口只有一帧（约 16ms），而它需要用户
     * 在悬浮窗上连点两次「跳转」，两次点击之间隔不到一帧——物理上做不到。
     * 一旦消费被重组观察到（`null` 落地），第二次点击就必然生效。
     * 真正会让「点了没反应」的是别的环（见
     * `ui/lab/NotebookOverlayService.openNotebookPage` 那段关于
     * `FLAG_ACTIVITY_NEW_TASK` 的说明），那一处本轮已修。
     */
    private var labShortcutTarget by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Result intentionally ignored: denial must not break check-in flow.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        // 冷启动：直接从启动 Intent 里取快捷方式目标（没有 / 不是快捷方式就是 null）。
        //
        // ⚠️ 必须走同一个解析入口（`shortcutTargetOf`）：`onCreate` 与 `onNewIntent`
        // 各写一份「读哪个 extra」的逻辑，就是两处协议、迟早改一处漏一处。
        //
        // ⚠️ `savedInstanceState == null` 这个条件不能省：
        // `getIntent()` 在整个 Activity 生命周期里都会返回**最初那份** Intent
        //（`onNewIntent` 里的 `setIntent` 只把它更新成最新的一份，并不会清空它），
        // 所以「旋屏 / 深色模式切换导致 Activity 重建」时 `onCreate` 会再跑一遍、
        // 启动 Intent 里那个 `lab_page` 还在。若不判 savedInstanceState，
        // 每次重建都会**再推一次同一页**——用户在记事本里编辑到一半转个屏，
        // 回退栈里就多出一个记事本页，返回键要按两次。
        // 重建时正确的状态来源是 `rememberSaveable` 与 `NavController` 恢复出的回退栈，
        // 而不是再消费一次启动 Intent。
        labShortcutTarget =
            if (savedInstanceState == null) shortcutTargetOf(intent) else null
        setContent {
            val settingsViewModel: SettingsViewModel =
                viewModel(factory = AppViewModelProvider.Factory)
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val themeAccent by settingsViewModel.themeAccent.collectAsStateWithLifecycle()

            // 开发者选项仓库（进程内单例）。开屏时长由「性能测试」里的滑块控制，
            // 要在这里读一次——理由见下方 `splashHoldMs` 那段注释。
            //
            // 从 `applicationContext as MoreCheckApp` 取：这是 `AppContainer` 里那份
            // **进程内唯一**的实例。Activity 本身就是 Context，但 `this as MoreCheckApp`
            // 是错的（Activity 不是 Application），必须经 applicationContext 转一次。
            val developerOptions = remember {
                (applicationContext as MoreCheckApp).container.developerOptionsRepository
            }

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> systemDark
            }

            MoreCheckTheme(
                darkTheme = darkTheme,
                accent = AccentColor.fromId(themeAccent)
            ) {
                // 把同一个 SettingsViewModel 下发给设置页。
                // 之前设置页用 viewModel(...) 自建了一份实例，导致开关读到的是
                // 永远停在默认值的那一份，表现为「开关关不掉」（已实测定位）。
                CompositionLocalProvider(
                    LocalSettingsViewModel provides settingsViewModel
                ) {
                    // 隐私同意门闸：未同意则强制弹窗（不可关闭），不同意退出。
                    // 同意后 `promptLogin` 置位，主界面里再弹一个可关闭的登录引导。
                    var promptLogin by remember { mutableStateOf(false) }
                    // 开屏最短展示时长是否已到 → 门闸据此才允许弹同意窗。
                    //
                    // 需求：**先展示 Hello World 开屏，再弹同意窗口**（此前同意窗在开屏期间就弹了出来，
                    // 底部弹窗会盖住居中的 Hello World）。
                    var splashMinElapsed by rememberSaveable { mutableStateOf(false) }
                    // 用户**本次会话里**是否刚点了「同意并继续」。
                    //
                    // 用来区分两件事：① 刚点完同意 → 开屏再多留 0.75 秒；
                    // ② 进来就是已同意状态的老用户 → 不留，否则每次冷启动都白等 0.75 秒。
                    var agreedJustNow by rememberSaveable { mutableStateOf(false) }

                    val consentAgreed by settingsViewModel.privacyAgreed.collectAsStateWithLifecycle()
                    val consentLoaded by settingsViewModel.privacyLoaded.collectAsStateWithLifecycle()
                    // 同意流程尚未结束 = 开屏必须继续显示，充当同意弹窗的背景（需求 bug9：
                    // 弹窗期间背景一直是 Hello World，而不是一块空白底色）。
                    val consentPending = !consentLoaded || !consentAgreed

                    // 开屏「Hello World」的额外停留时长，由「开发者选项 → 性能测试」里的
                    // 滑块控制（需求：0~10 秒可调 + 一键恢复默认）。
                    //
                    // ## 为什么在这里读，而不是让 SplashGate 自己去读 DataStore
                    //
                    // `SplashGate` 是 `ui/` 层里一个纯展示的门闸，它不该知道 DataStore、
                    // 更不该知道「开发者选项」这个概念；把读取放在 Activity 这一层，
                    // 门闸拿到的就只是一个毫秒数，职责边界干净。
                    //
                    // ## 仓库已经算好了门控，这里不要再判一次
                    //
                    // `splashHoldDurationMs` 已经把「总开关 && 性能测试开关」的「与」运算
                    // 和「不生效时回落默认」都做完了（见那个属性的 KDoc）。
                    // 若在这里再判一次开关，就出现了第二处判断 —— 两边迟早不一致，
                    // 正是 `DeveloperOptionsRepository` 存在的意义所在。
                    //
                    // `initialValue` 用 `DEFAULT_SPLASH_HOLD_MS`（= 改造前的 900ms）：
                    // DataStore 首次读取要走磁盘，先按既有行为渲染，真实值到了再按需调整，
                    // 用户不会看到"开屏先闪一下、再变长"。
                    val splashHoldMs by developerOptions
                        .splashHoldDurationMs
                        .collectAsStateWithLifecycle(
                            initialValue = SettingsRepository.DEFAULT_SPLASH_HOLD_MS
                        )

                    SplashGate(
                        consentPending = consentPending,
                        justAgreed = agreedJustNow,
                        // `delay` 收 Long，而设置里存的是 Int（毫秒）。
                        minDurationMs = splashHoldMs.toLong(),
                        onMinElapsed = { splashMinElapsed = true },
                        // 登录引导**必须等开屏彻底退场后再弹**。
                        //
                        // 它是个 `Dialog`，一旦提前置位就会浮在开屏之上，
                        // 把需求要求「同意后再显示 0.75 秒」的 Hello World 整段盖掉。
                        onFinished = { if (agreedJustNow) promptLogin = true }
                    ) {
                        PrivacyConsentGate(
                            viewModel = settingsViewModel,
                            // 开屏最短时长没到就不弹同意窗；到了才交给门闸判断。
                            enabled = splashMinElapsed,
                            onAgreed = {
                                // 只记「用户刚同意」，先不弹登录引导（见上面的 onFinished）。
                                agreedJustNow = true
                            }
                        ) {
                            MoreCheckRoot(
                                promptLogin = promptLogin,
                                onLoginPromptDismiss = { promptLogin = false },
                                // 桌面快捷方式直达实验室某一页（见字段与 MoreCheckRoot 的说明）。
                                labShortcutTarget = labShortcutTarget,
                                // 消费后清空：避免重组时反复导航（例如旋转屏幕后重复推入一页）。
                                onLabShortcutConsumed = { labShortcutTarget = null }
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 热启动：Activity 已存活时再点桌面快捷方式（或从悬浮窗点「跳转」）不会重建，
     * 只会回调这里。
     *
     * ⚠️ 不覆写这个方法的话，「应用已在后台 → 点快捷方式」会**什么都不发生**
     * （Intent 被投递到一个已有实例，而它不会重新读 `intent`）。
     *
     * 启动模式是 `singleTop` 之外的标准模式时，系统也可能新建实例走 `onCreate`；
     * 两条路径都赋值同一个字段，因此无论哪条都能生效。
     *
     * ## `setIntent(intent)` 与赋值的先后（v1.1.69 核对）
     *
     * `setIntent` 只影响之后 `getIntent()` 的返回值（例如 Activity 重建时读回哪一份），
     * 与本次跳转**无关**——本次跳转靠的是下一行对 `mutableStateOf` 字段的赋值。
     * 顺序保持「先 setIntent、再赋值」只是为了让两条信息的更新挨在一起，便于阅读。
     *
     * ## 为什么赋值能被组合观察到
     *
     * `labShortcutTarget` 是 `mutableStateOf` 的属性代理，赋值即**快照状态写入**；
     * `MoreCheckRoot` 在参数位置读它（`labShortcutTarget = labShortcutTarget`），
     * 那次读发生在某个重组作用域内，于是这个作用域被登记为观察者，
     * 下一帧必然重组，`LaunchedEffect(key1 = selectedTabName, key2 = labShortcutTarget)`
     * 的 key 变化 → 协程重启 → 执行导航。**这条链没有任何一步依赖时序巧合**：
     * 写入（主线程）→ 快照失效 → 帧回调 → 重组 → LaunchedEffect 重启。
     *
     * ### 为什么 `onCreate` 里那份还要额外判 `savedInstanceState == null`
     *
     * 上面说的是**消费**路径（值一变就一定会被观察到）。`onCreate` 是**生产**路径，
     * 它有一个这里独有的坑：`getIntent()` 在整个 Activity 生命周期里都会返回
     * **启动时那一份** Intent（`onNewIntent` 里的 `setIntent` 只是把它更新成最新的一份，
     * 并不会在重建时清空它）。于是「旋屏 / 切深色模式导致 Activity 重建」时，
     * `onCreate` 会再跑一遍、启动 Intent 里的 `lab_page` 还在，
     * 每次重建都会**再推一次同一页**：用户在记事本里编辑到一半转个屏，
     * 回退栈里就多出一个记事本页，返回键要按两次。
     * 所以那份只在 `savedInstanceState == null`（真正的冷启动）时读，
     * 重建时正确的状态来源是 `rememberSaveable` 与 `NavController` 恢复出的回退栈。
     *
     * 唯一真正会「吞掉」这次跳转的情况，是**新旧值被判等**（见字段 KDoc 里那段
     * 「同一页连着请求两次」的说明）：key 不变则不重启，导航不会发生。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须把新 Intent 也设回去：后续 `getIntent()` 才能拿到最新的那份，
        // 否则 Activity 重建（如旋转）会读回**上一次**的 Intent、把旧目标再跳一遍。
        setIntent(intent)
        // ⚠️ 只有真的是「实验室捷径」才读 extra（见 shortcutTargetOf 的注释）：
        // 别处（例如以后有人加一条 `am start` 的调试 Intent）不带这个 action，
        // 就不该被当成一次跳转请求。
        //
        // 因此这里**只在拿到目标时才赋值**，而不是无条件 `= shortcutTargetOf(intent)`：
        // 后者会把「不是捷径」的 Intent 解析成 null 并写进去，
        // 等于顺手把一条**还没被组合消费掉的**待办跳转清掉（丢事件）。
        // 对一次无关的 `onNewIntent`，正确语义是**什么都不做**。
        shortcutTargetOf(intent)?.let { labShortcutTarget = it }
    }

    /**
     * 从 Intent 解析「直达实验室某页」的目标；不是一次捷径请求时返回 `null`。
     *
     * ## 为什么必须用 action 区分，而不能只看 extra
     *
     * `ACTION_LAB_SHORTCUT` 这个 action 的**唯一用途**就是标记「这是一个直达某页的请求」。
     * 若只读 `EXTRA_LAB_PAGE`，任何带同名 extra 的 Intent 都会被当成跳转指令，
     * 而 extra 的键名（`"lab_page"`）是个普通字符串、不构成任何保护。
     * 更现实的一点是：**没有 action 时我们根本不该清掉 `labShortcutTarget`**——
     * 例如用户在「我的」页停留时收到一条普通 Intent，若这里返回 null 并赋值，
     * 就会把「已消费」的状态又写一遍（无害但语义错），
     * 而不做 action 判断时更糟：一个恰好带空 `lab_page` 的 Intent 会清掉待办跳转。
     *
     * 所以这里的口径是：**只有** `ACTION_LAB_SHORTCUT` 才认为存在目标；
     * 其余 action 一律返回 null（既不跳转，也不干扰已有状态）。
     *
     * ## 为什么还要判空串
     *
     * `getStringExtra` 在 extra 存在但值为空串时返回 `""`，
     * 而 `TabDestination.fromRouteOrNull("")` 会返回 null（安静消费、不跳转）——
     * 行为上无害，但会在 `LaunchedEffect` 里白白跑一轮。
     * 顺手 `takeIf { it.isNotEmpty() }` 让「没有目标」只有一个表示法（`null`），
     * 与项目里 `SettingsRepository.devCustomIconPath` 的处理口径一致。
     *
     * @return 目标 route；不是捷径请求 / 没带 extra / extra 为空串时返回 null。
     */
    private fun shortcutTargetOf(intent: Intent?): String? {
        if (intent?.action != ACTION_LAB_SHORTCUT) return null
        return intent.getStringExtra(EXTRA_LAB_PAGE)?.takeIf { it.isNotEmpty() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
