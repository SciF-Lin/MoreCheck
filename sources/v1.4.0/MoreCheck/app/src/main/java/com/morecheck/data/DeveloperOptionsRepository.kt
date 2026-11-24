package com.morecheck.data

import com.morecheck.reminder.NotificationHelper
import com.morecheck.ui.lab.GameChromeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 「实验室 → 开发者选项」的状态与行为。
 *
 * ## 为什么单独一个仓库，而不是塞进 `SettingsRepository`
 *
 * 三个子开关的**生效条件**都依赖总开关（`总开关 && 子开关`）。这个「与」运算如果散落在
 * 每个使用点，迟早会漏掉一处，表现为「总开关关了但某项还在生效」。
 * 集中在这里之后，调用方拿到的一律是**已经与过总开关的结果**，不可能忘。
 *
 * ## 验证码测试的「后端」在哪
 *
 * 本项目**没有后端**。需求说「在后台随机生成六位数字并记录在后端」，
 * 这里对应为**进程内的一个字段**（[issuedCode]）：
 * - 发送时写入，登录 / 注册时比对；
 * - 它是 `AppContainer` 里的单例，因此在整个应用进程内共享；
 * - **进程被杀（或应用被划掉）就清空**——这与「一次性验证码」的真实语义一致
 *   （真实短信验证码也有有效期，过期即失效）。
 *
 * 刻意**不**写进 DataStore：那会让验证码在重启后仍然生效，反而不像真实的短信验证码，
 * 也存在「上次的码被翻出来复用」的隐患。
 */
class DeveloperOptionsRepository(
    private val settings: SettingsRepository,
    private val notificationHelper: NotificationHelper
) {

    /** 总开关本身（界面上的那一个）。 */
    val masterEnabled: Flow<Boolean> = settings.devModeEnabled

    /**
     * 「链接打开方式」全局开关。与其他开发者选项不同，它**不**受 [masterEnabled] 门控：
     * 用户关掉开发者选项总开关后，已选的打开方式仍应继续生效，否则拨一个开关
     * 会把浏览行为悄悄改回去。这里只做转发，存储仍在 SettingsRepository。
     */
    val linkOpenInApp: Flow<Boolean> = settings.linkOpenInApp

    suspend fun setLinkOpenInApp(enabled: Boolean) = settings.setLinkOpenInApp(enabled)

    // ---- 2026-11-14：发现页官网位 / 开屏分类 / 入口归属 ----
    //
    // 这三项都是「内容配置」（页面长什么样、入口在哪儿），和 linkOpenInApp 同口径：
    // **不受 masterEnabled 门控**——用户关掉开发者选项总开关后，已选的布局仍应生效。

    val discoverSearchMode: Flow<Boolean> = settings.devDiscoverSearchMode

    suspend fun setDiscoverSearchMode(searchMode: Boolean) =
        settings.setDevDiscoverSearchMode(searchMode)

    val labSplashDisabledPages: Flow<String> = settings.devLabSplashDisabledPages

    /** 逐页开/关开屏：按 routeKey 增删，避免整份集合被并发写覆盖。 */
    suspend fun setLabSplashPageEnabled(routeKey: String, enabled: Boolean) {
        val current = settings.devLabSplashDisabledPages.first()
        val disabled = current.split(",").map { it.trim() }
            .filter { it.isNotEmpty() }.toMutableSet()
        if (enabled) disabled.remove(routeKey) else disabled.add(routeKey)
        settings.setDevLabSplashDisabledPages(disabled.sorted().joinToString(","))
    }

    val entryPlacementOverrides: Flow<String> = settings.entryPlacementOverrides

    suspend fun setEntryPlacementOverrides(raw: String) =
        settings.setEntryPlacementOverrides(raw)

    /** 原始子开关状态，仅用于界面回显（**不代表是否生效**）。 */
    val codeTestSwitch: Flow<Boolean> = settings.devCodeTestEnabled

    /**
     * 「启用性能测试」子开关的原始值。
     *
     * ## ⚠️ 2026-10-27 起**已停用**（用户要求移除这个分开关）
     *
     * 它原本门控「开屏显示时长」；那条滑块迁到「开屏」分区后已放宽为只受总开关，
     * 于是这个开关**再没有任何消费方**——留着就是一个"拨了什么都不发生"的开关，
     * 因此从界面上删掉。键 `dev_perf_test_enabled` 的数据仍留在 DataStore 里，但无人读取
     * （与 `dev_game_splash` / `dev_snake_title_bar` 同一处理：**不留兼容层**，
     * 只留一条注释说明它曾经是什么）。
     */
    @Deprecated("2026-10-27 起界面已移除该开关，且它不再门控任何项；仅为留档保留")
    val perfTestSwitch: Flow<Boolean> = settings.devPerfTestEnabled

    /** 「验证码测试」是否**真正生效**（总开关与子开关都开）。 */
    val codeTestEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devCodeTestEnabled) { master, sub -> master && sub }

    /** 「性能测试」是否生效（2026-10-27 起同样已停用，见 [perfTestSwitch]）。 */
    @Deprecated("2026-10-27 起界面已移除该开关，且它不再门控任何项；仅为留档保留")
    val perfTestEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devPerfTestEnabled) { master, sub -> master && sub }

    /**
     * 「左右滑动动画」开关的**原始值**（界面回显用，不代表是否生效）。
     *
     * 生效规则（见 [tabSlideAnimEnabled]）：**总开关与「性能测试」都开**时才按用户设置，
     * 否则恒为开（滑入动画的默认值本来就是"开"）。
     *
     * ⚠️ 为什么关掉总开关时回落**开**而不是"关"：回落"关"会让"没开过开发者选项的用户"
     * 升级后失去动画，正是本项目反复避开的那类"默认关闭的开关悄悄改掉全局行为"。
     */
    val tabSlideAnimSwitch: Flow<Boolean> = settings.devTabSlideAnim

    /**
     * 「底部入口切换动画」是否**真正生效**（原「左右滑动动画」，2026-10-22 迁到界面测试）。
     *
     * 合成规则：只看**开发者选项总开关**——总开关关时回落 true（= 改造前一直有动画）。
     * 迁移前这里还要求「启用性能测试」也打开（它当时挂在那张卡片里）；
     * 归到「界面测试」后与本分区其它三项同一口径（只受总开关门控）。
     */
    val tabSlideAnimEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devTabSlideAnim
        ) { master, sub ->
            if (master) sub else true
        }

    /**
     * 「贪吃蛇标题栏显示」开关**已被需求 2 删除**（与「游戏标题栏显示」合并）。
     *
     * 这里**刻意不保留**一个转发到 `devGameTitleBar` 的同名属性：
     * 保留会让「两个开关合并」这件事在代码里看不出来（调用方还以为有独立开关），
     * 而本项目的历史教训正是"留着看似无害的兼容层，久而久之没人敢删"。
     * 旧键 `dev_snake_title_bar` 的数据仍留在 DataStore 里，但无人读取。
     */

    /**
     * 「游戏开屏显示」的**总开关原始值**（2026-10-22 需求 10）。
     *
     * ## ⚠️ 2026-10-27 起**已停用**（键保留，不再参与任何生效合成）
     *
     * 用户要求「实验室功能开屏可单独设置包括热搜和三个游戏，有一个总开关」，
     * 于是三个游戏的开屏改由 [labSplashAllEnabled]（实验室开屏总开关）统管，
     * 一级页那一行入口也随之并进新的「实验室功能开屏」二级页。
     *
     * 键 `dev_game_splash` 的数据仍留在 DataStore 里，但**没有任何读取方**——
     * 与 `dev_snake_title_bar` 的处理方式一致（见上方注释里那条教训：
     * 留着兼容层反而让人不敢删）。此处保留属性只为让"它曾经是什么"有据可查。
     */
    @Deprecated("2026-10-27 起由 labSplashAllEnabled 取代，仅为留档保留")
    val gameSplashSwitch: Flow<Boolean> = settings.devGameSplash

    @Deprecated("2026-10-27 起由 setLabSplashAll 取代，仅为留档保留")
    suspend fun setGameSplash(enabled: Boolean) = settings.setDevGameSplash(enabled)

    /**
     * 「贪吃蛇开屏显示」开关的**原始值**（界面回显用，需求 2）。
     *
     * 只作用于贪吃蛇页。数独页由 [gameSplashSudokuSwitch] 控制。
     */
    val gameSplashSnakeSwitch: Flow<Boolean> = settings.devGameSplashSnake

    /**
     * 「贪吃蛇开屏显示」是否**真正生效**（需求 2；2026-10-30 起只受开发者选项总开关门控）。
     *
     * 合成规则（2026-10-24 起改为「默认开启」语义）：`开发者选项总开关` 关闭时
     * 直接回落**开**（开屏照常显示——总开关是调试入口，不该拿走普通用户的既有观感）；
     * 总开关打开后按 `游戏开屏总开关 && 子开关` 走，
     * 任一级关掉仍回落开。回落"关"会让没开过开发者选项的用户升级后突然看不到开屏
     * ——那是用默认关闭的开关改掉了所有人的既有观感。
     */
    val gameSplashSnakeEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devGameSplashSnake
        ) { master, all, sub ->
            if (master && all) sub else true
        }

    /**
     * 「数独开屏显示」开关的**原始值**（界面回显用，需求 2）。
     *
     * 只作用于数独页。贪吃蛇页由 [gameSplashSnakeSwitch] 控制。
     */
    val gameSplashSudokuSwitch: Flow<Boolean> = settings.devGameSplashSudoku

    /**
     * 「数独开屏显示」是否**真正生效**（需求 2；2026-10-30 起只受开发者选项总开关门控）。
     * 合成规则同上。
     */
    val gameSplashSudokuEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devGameSplashSudoku
        ) { master, all, sub ->
            if (master && all) sub else true
        }

    /**
     * 「数独2 开屏」开关的**原始值**（2026-10-27 新增；默认开）。
     *
     * 数独2 原先没有开屏层，本轮补上（见 `Sudoku2Screen` 的 `showSplash` 形参）。
     */
    val labSplashSudoku2Switch: Flow<Boolean> = settings.devLabSplashSudoku2

    suspend fun setLabSplashSudoku2(enabled: Boolean) = settings.setDevLabSplashSudoku2(enabled)

    /** 「数独2 开屏」是否**真正生效**（合成规则与另外两个游戏逐字同构）。 */
    val gameSplashSudoku2Enabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devLabSplashSudoku2
        ) { master, all, sub ->
            if (master && all) sub else true
        }

    /**
     * 「游戏标题栏显示」开关的**原始值**（界面回显用，需求 2 起两页共用）。
     *
     * ⚠️ 它现在**同时作用于贪吃蛇与数独两页**（需求 2：原贪吃蛇专属开关已删除）。
     * 需求 11b 那个旧键 `dev_snake_title_bar` 已无人读取，数据仅留在 DataStore 里。
     */
    val gameTitleBarSwitch: Flow<Boolean> = settings.devGameTitleBar

    /**
     * 「游戏标题栏显示」是否**真正生效**（需求 2，作用于贪吃蛇与数独两页；
     * 2026-10-30 起只受开发者选项总开关门控）。
     *
     * 回落规则同上：任一级总开关关 → 恒显示标题栏（= 改造前行为）。
     */
    val gameTitleBarEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameTitleBar
        ) { master, sub ->
            if (master) sub else true
        }

    // ---- 「游戏标题栏显示」二级页（本轮需求 5）------------------------------
    //
    // 需求原文：「游戏标题栏显示增加二级界面，可单独设置标题栏显隐，原总开关保留，
    // 右侧增加向右箭头」。与「游戏开屏显示」（需求 2）同一条演进路径：
    // 一级页的开关变入口、原总开关保留、两个子开关进二级页。
    //
    // 生效规则（与 `gameSplashSnakeEnabled` / `gameSplashSudokuEnabled` 逐字同构）：
    // **开发者选项总开关 && 游戏子开关** 都开才显示该页标题栏；
    // 任何一级关掉都回落"显示"（= 改造前行为，回落"关"会让没动过设置的用户
    // 升级后突然看不到标题栏）。

    /**
     * 「贪吃蛇标题栏」的**原始值**（界面回显用，不代表是否生效）。
     */
    val gameTitleBarSnakeSwitch: Flow<Boolean> = settings.devGameTitleBarSnake

    /**
     * 「贪吃蛇标题栏」是否**真正生效**（2026-10-30 起只受开发者选项总开关门控）。
     *
     * `开发者选项总开关 && 游戏标题栏总开关 && 子开关`
     * ——任何一级关闭都回落 true（= 改造前行为）。
     */
    val gameTitleBarSnakeEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSnake
        ) { master, total, sub ->
            if (master && total) sub else true
        }

    /**
     * 「数独标题栏」的**原始值**（界面回显用，不代表是否生效）。
     */
    val gameTitleBarSudokuSwitch: Flow<Boolean> = settings.devGameTitleBarSudoku

    /**
     * 「数独标题栏」是否**真正生效**（2026-10-30 起只受开发者选项总开关门控）。
     * 合成规则同上。
     */
    val gameTitleBarSudokuEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSudoku
        ) { master, total, sub ->
            if (master && total) sub else true
        }

    /**
     * 「数独2 标题栏」的**原始值**（界面回显用，不代表是否生效，2026-10-22 新增）。
     */
    val gameTitleBarSudoku2Switch: Flow<Boolean> = settings.devGameTitleBarSudoku2

    /**
     * 「数独2 标题栏」是否**真正生效**（2026-10-22 新增）。
     *
     * 与 [gameTitleBarSnakeEnabled] / [gameTitleBarSudokuEnabled] 逐字同构：
     * `开发者选项总开关 && 游戏标题栏总开关 && 子开关`，
     * 任何一级关闭都回落 true（= 改造前行为）。
     *
     * 这个子开关是补上的——此前数独2 只受"两页共用"的总开关控制，
     * 而那个总开关在界面上已经变成二级页入口，用户无处可关（详见 `SettingsRepository` 的键注释）。
     */
    val gameTitleBarSudoku2Enabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSudoku2
        ) { master, total, sub ->
            if (master && total) sub else true
        }

    /**
     * 「游戏标题栏 / 状态栏颜色」三档模式的**用户选择**（原始值，界面回显用）。
     *
     * ⚠️ 它**不代表当前真正生效的档位**——生效值见 [gameChromeMode]。
     * 与 `codeTestSwitch` / `gameSplashSnakeSwitch` 那批属性是同一个分工：
     * 界面上的单选项读**本属性**，这样关掉总开关时单选框仍停在用户选的那一档
     * （只是点不动），而不是自己跳回默认档、让用户以为设置被清空了。
     *
     * 解析失败 / 键不存在都回落 `DARK_CLASSIC`（解析在 `GameChromeMode.fromValue`，
     * 默认值在 `SettingsRepository.DEFAULT_GAME_CHROME_MODE`，两处同值）。
     */
    val gameChromeModeSwitch: Flow<GameChromeMode> =
        settings.devGameChromeMode.map { GameChromeMode.fromValue(it) }

    /**
     * 「游戏标题栏 / 状态栏颜色」**真正生效**的档位（需求 3，贪吃蛇 / 数独共用一个值）。
     *
     * ## 合成规则为什么是「回落经典」而不是「回落跟随系统」
     *
     * 与其它子项同一条纪律：**回落值必须是"没开过开发者选项的人也看不出变化"的那一档**。
     * 三档里只有 `DARK_CLASSIC` 是改造前的既有观感（标题栏与固定深色的游戏区同色、
     * 系统栏浅色图标），因此总开关关掉时回落它；回落 `SYSTEM` 会让浅色主题下的用户
     * 突然看到一条浅色标题栏压在深色游戏区上——那是**默认关闭的开关改掉了既有观感**，
     * 正是本项目反复避开的那类改动（同 `gameSplashSnakeEnabled` 回落 true 的理由）。
     *
     * ## 为什么用 `combine(总开关, 用户值)` 而不是 `map`
     *
     * 生效值 = `if (总开关) 用户值 else 默认档`，写成 `combine` 与本文件里其它受总开关
     * 门控的子项（`gameTitleBarEnabled` / `gameSplashSnakeEnabled`）**逐字同构**，
     * 让"凡是受总开关门控的项都长这个样子"一眼可辨；`map` 也能算出来，但会多一种写法。
     */
    val gameChromeMode: Flow<GameChromeMode> =
        combine(
            settings.devModeEnabled,
            gameChromeModeSwitch
        ) { master, chosen ->
            if (master) chosen else GameChromeMode.DARK_CLASSIC
        }

    /**
     * 开屏「Hello World」额外停留时长（毫秒）的**原始值**，仅用于界面回显。
     *
     * 与 [splashHoldDurationMs] 的关系，就是上面 `perfTestSwitch` 与 `perfTestEnabled` 的关系：
     * 这个是用户设的值（永远可读），那个是「现在到底按哪个数走」。
     */
    val splashHoldSwitch: Flow<Int> = settings.splashHoldMs

    //
    // ⚠️ 已删除（2026-10-23 需求 8）：`splashJitterSwitch`（旧「浮动范围」滑块的原始值）
    // 与 `setSplashJitterMs` 转发——那条滑块连同 `SettingsRepository` 的读写入口整条去掉了，
    // 这里再留一层转发就是纯粹的死代码。

    /**
     * 「随机浮动」勾选框的原始值（2026-10-23 需求 8）。默认 false。
     *
     * 语义：勾上后每次开屏时长 = 设定值 ±15%（见 [SettingsRepository.SPLASH_JITTER_RATIO]）。
     */
    val splashJitterEnabledSwitch: Flow<Boolean> = settings.splashJitterEnabled

    suspend fun setSplashJitterEnabled(enabled: Boolean) =
        settings.setSplashJitterEnabled(enabled)

    /**
     * 「随机浮动」介绍弹窗是否已看过（2026-10-25）。
     * 只服务于「首次勾选弹 AlertDialog、之后改用工具提示」这条交互；
     * 转发到 [SettingsRepository.splashJitterIntroShown]，页面统一从本仓库取。
     */
    val splashJitterIntroShown: Flow<Boolean> = settings.splashJitterIntroShown

    suspend fun setSplashJitterIntroShown(shown: Boolean) =
        settings.setSplashJitterIntroShown(shown)

    /**
     * 「游戏开屏显示」**总开关的生效值**（2026-10-25 需求 9）。
     *
     * 入口行右侧那个开关原先读原始值，会出现「上一级总门已关（= 实际恒显示开屏）、
     * 行开关却显示关」的自相矛盾。本流给它一个与实际观感一致的值：
     * 开发者选项总开关开着才按 [gameSplashSwitch]，否则回落 true（= 恒显示）。
     * 与 [gameTitleBarEnabled]（标题栏那条）同一口径——那条本就是"master+game+sub"的生效流。
     */
    val gameSplashTotalEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSplash
        ) { master, total ->
            if (master) total else true
        }

    /**
     * 开屏时长**当前真正生效**的值（毫秒），含需求 12 的随机浮动。
     *
     * ## 合成规则
     *
     * 门控只有**开发者选项总开关**一个（2026-10-27 起）。
     *
     * 改造前这里还要求「启用性能测试」也开着——那是因为这个滑块当时摆在
     * 「性能测试」卡片里（历史原因：它最初是用来量"开屏拖慢了多少启动"的）。
     * 用户 2026-10-27 要求把分类理顺后，滑块已移到独立的「开屏」分区，
     * 再让性能测试开关卡着它就成了"跨分区的隐形门"：用户在「开屏」里拖滑块，
     * 却因为另一个分区里某个开关是关的而毫无效果。
     *
     * 门控通过后：
     * `基准 ± 15%`（2026-10-30 用户口径；2026-10-23 那版是固定 ±0.3 秒），结果夹在
     * `[SettingsRepository.MIN_SPLASH_HOLD_MS, MAX_SPLASH_HOLD_MS]` 之间——
     * 负向抖动不能把时长压到 0 以下（那会让开屏被跳过），正向也不该超过滑块上限。
     *
     * ⚠️ 早先版本是"只向上浮动 0..jitter"，理由是担心"开屏在同意弹窗弹出前就退场"；
     * 现在的下限夹取已经保证了最短时长 ≥ 0，而 `SplashGate` 的最短展示本身
     * 也有独立兜底，因此"正负"是安全的。
     */
    val splashHoldDurationMs: Flow<Int> =
        combine(
            settings.devModeEnabled,
            settings.splashHoldMs,
            settings.splashJitterEnabled
        ) { master, hold, jitterOn ->
            if (!master) {
                SettingsRepository.DEFAULT_SPLASH_HOLD_MS
            } else {
                val base = hold.coerceIn(
                    SettingsRepository.MIN_SPLASH_HOLD_MS,
                    SettingsRepository.MAX_SPLASH_HOLD_MS
                )
                // 勾上「随机浮动」后是**上下各 15%**（可正可负，2026-10-30 起）。
                // 下限夹到 0：负向抖动不该把开屏时长压成负数（`delay` 收到负值会立刻返回，
                // 那会让开屏在没有最短展示时间的情况下被跳过）。
                if (!jitterOn) {
                    base
                } else {
                    // 2026-10-30：浮动幅度改成**设定值的 ±15%**（原来是固定 ±300ms）。
                    // 用比例的好处是"幅度感"恒定：1 秒±150ms、5 秒±750ms，都是 15%。
                    val spread = (base * SettingsRepository.SPLASH_JITTER_RATIO).roundToInt()
                    (base + Random.nextInt(-spread, spread + 1)).coerceIn(
                        SettingsRepository.MIN_SPLASH_HOLD_MS,
                        SettingsRepository.MAX_SPLASH_HOLD_MS
                    )
                }
            }
        }

    suspend fun setMasterEnabled(enabled: Boolean) = settings.setDevModeEnabled(enabled)

    suspend fun setCodeTestEnabled(enabled: Boolean) = settings.setDevCodeTestEnabled(enabled)

    suspend fun setPerfTestEnabled(enabled: Boolean) = settings.setDevPerfTestEnabled(enabled)

    // ---- 「界面测试」分区（v1.1.74 需求 8）--------------------------------
    //
    // 四项都是**全局**界面行为，按本文件的既有纪律成对提供：
    // `...Switch` 是**原始值**（界面回显用，"值还在、只是暂不生效"这件事自解释）、
    // `...Enabled` 是**生效值**（总开关 && 子开关，业务侧判断用）。

    // ---- 1. 打卡完成动画（主页撒花）----

    /** 「打卡完成动画」的**原始值**（默认 true），仅供界面回显。 */
    val checkinCelebrationSwitch: Flow<Boolean> = settings.devCheckinCelebrationEnabled

    /**
     * 「打卡完成动画」是否**真正生效**。
     *
     * 与 `codeTestEnabled` 同一口径（`总开关 && 子开关`），但**不加回落分支**：
     * 它是纯开关，没有"总开关关掉时该回落成什么"那层语义（对比 `tabSlideAnimEnabled`
     * 那种 `else true` 的回落——那是给"关掉等于拿走本来就有的动画"设计的，
     * 而本项关掉就是用户明确不要反馈，回落成 true 才是错的）。
     */
    val checkinCelebrationEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devCheckinCelebrationEnabled) { master, sub ->
            master && sub
        }

    suspend fun setCheckinCelebrationEnabled(enabled: Boolean) =
        settings.setDevCheckinCelebrationEnabled(enabled)

    // ---- 2. 页面转场动画 ----

    /** 「页面转场动画」的**原始值**（默认 true），仅供界面回显。 */
    val pageTransitionAnimSwitch: Flow<Boolean> = settings.devPageTransitionAnim

    /**
     * 「页面转场动画」是否**真正生效**（门控 `TabNavHost` 里那四个转场函数）。
     *
     * 2026-10-24 需求：**默认开启**——与「样式设置」批同一条回落语义
     * （`if (master) sub else true`）：开发者选项总开关关闭时按"开"处理
     * （转场是本来就有的观感，不该被一个默认关闭的总开关拿走）；
     * 总开关打开后才按子开关的值走。
     */
    val pageTransitionAnimEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devPageTransitionAnim) { master, sub ->
            if (master) sub else true
        }

    suspend fun setPageTransitionAnim(enabled: Boolean) =
        settings.setDevPageTransitionAnim(enabled)

    // ---- 3. 日期显示格式（三选一，做成二级页）----

    /**
     * 「日期显示格式」的**原始选择**（已解析成枚举，供界面回显单选框）。
     *
     * 刻意**不**提供 `dateFormatEnabled` 之类的生效值：三选一不是布尔开关，
     * "关掉总开关时它该显示哪一档"没有唯一正确答案。若回落默认档，
     * 用户拨好的选择会在关总开关的瞬间"弹回"，那正是本文件反复要消除的
     * 那种"看起来设置被清空"的困惑。
     *
     * 因此这里的取舍与 `gameChromeModeSwitch` 完全一致：**原始值永远可读**，
     * 二级页读它做回显即可（关掉总开关时单选框仍停在用户选的那一档、只是点不动）。
     *
     * ⚠️ 日期格式化本身（`util/DateFormatters.fullLabel`）**不读**这个值——
     * 它是纯函数、不知道开发者选项的存在。接线在调用侧：
     * 需要格式化的页面各自把这一档传进去（见 `DateFormatters.fullLabelWith`）。
     */
    val dateFormatSwitch: Flow<SettingsRepository.DateDisplayFormat> =
        settings.devDateFormat.map { SettingsRepository.DateDisplayFormat.parse(it) }

    suspend fun setDateFormat(format: SettingsRepository.DateDisplayFormat) =
        settings.setDevDateFormat(format.id)

    // ---- 4. 按压反馈 ----

    /** 「按压反馈」的**原始值**（默认 true），仅供界面回显。 */
    val pressFeedbackSwitch: Flow<Boolean> = settings.devPressFeedback

    /**
     * 「按压反馈」是否**真正生效**（门控 `ui/common/PressScale.kt` 那个全应用
     * 统一的按压缩放）。
     *
     * ## 为什么这一项**必须**由 `pressScale` 自己读，而不是让 40 多个调用点各传一次
     *
     * `Modifier.pressScale` 在本项目有 40 余处调用（v1.1.72 起所有可点元素都接了）。
     * 若做成形参，就得改 40 多个文件、且**漏一处就有**一个元素仍带反馈**——
     * 那是最坏的一种不一致：用户按下去发现"这个按钮还有动画、那个没有了"，
     * 而且它不报错、只在真机上偶尔出现一次，极难排查。
     *
     * `pressScale` 本身是 `@Composable`，因此它可以直接读一个 CompositionLocal，
     * **一处修改即全站生效**。接线在 `MainActivity` 那一层提供，
     * 详见 `PressScale.kt` 里 `LocalPressFeedbackEnabled` 的 KDoc。
     */
    val pressFeedbackEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devPressFeedback) { master, sub ->
            master && sub
        }

    suspend fun setPressFeedbackEnabled(enabled: Boolean) =
        settings.setDevPressFeedback(enabled)

    // ---- 5. 音乐（2026-11-09 需求：两个开发者开关）-----------------------------
    //
    // 与上面几项同一套成对约定：`...Switch` 是原始值（界面回显），
    // `...Enabled` 是生效值（业务侧判断）。生效规则取 `总开关 && 子开关`：
    // 这两项都是**行为开关**而不是观感回落项（没有"关掉总开关时该回落成什么"的语义），
    // 所以不存在 `else true` 的回落分支。

    /** 「退出音乐时二次确认」的**原始值**（默认 false），仅供界面回显。 */
    val musicExitConfirmSwitch: Flow<Boolean> = settings.devMusicExitConfirm

    /** 离开音乐页时是否要先确认（`总开关 && 子开关`）。 */
    val musicExitConfirmEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devMusicExitConfirm) { master, sub ->
            master && sub
        }

    suspend fun setMusicExitConfirm(enabled: Boolean) =
        settings.setDevMusicExitConfirm(enabled)

    /** 「音乐入口显示实时歌词」的**原始值**（默认 true），仅供界面回显。 */
    val musicEntryLyricsSwitch: Flow<Boolean> = settings.devMusicEntryLyrics

    /** 音乐主页「正在播放」主卡是否显示跟随进度的歌词（`总开关 && 子开关`）。 */
    val musicEntryLyricsEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devMusicEntryLyrics) { master, sub ->
            master && sub
        }

    suspend fun setMusicEntryLyrics(enabled: Boolean) =
        settings.setDevMusicEntryLyrics(enabled)

    // ---- 样式设置（2026-10-22 需求 8）-------------------------------------
    //
    // 与「界面测试」那批开关同一套成对约定：`...Switch` 是原始值（界面回显），
    // `...Enabled` 是生效值（业务侧判断）。
    //
    // 生效规则统一为：**总开关关 → 回落默认值**（而不是一律 false）。
    // 理由与 `gameSplashSnakeEnabled` 那条完全一致：这些都是"改造前本来就有 / 本来没有"
    // 的观感项，回落 false 会让没开过开发者选项的用户突然丢掉渐变按钮或小米滑块。

    // ---- 2026-11-11：两个二级页已删除，观感固定为默认值 -----------------------
    //
    // 用户要求「删除开发者选项里的样式设置和页面自定义功能和入口，保持默认值」。
    // UI 与二级页已整体删除，这里把**生效流**改成常量：各消费点（实验室按钮 / 日历 /
    // 滑块 / MainActivity 的 PageCustomize）读到的一律是"没动过设置"时的观感，
    // 与盘上残留的旧开关值无关。原始值开关（`...Switch`）保留，仅为不破坏
    // `SettingsRepository` 的键与已有单测；它们已不再有任何界面入口。

    /** 「渐变边框按钮」原始值（已无界面入口，仅留档）。 */
    val styleGradientButtonSwitch: Flow<Boolean> = settings.devStyleGradientButton

    /** 生效值：固定为默认（开启渐变描边）。 */
    val styleGradientButtonEnabled: Flow<Boolean> = flowOf(true)

    suspend fun setStyleGradientButton(enabled: Boolean) =
        settings.setDevStyleGradientButton(enabled)

    /** 「药丸底栏」原始值（已无界面入口，仅留档）。 */
    val stylePillTabBarSwitch: Flow<Boolean> = settings.devStylePillTabBar

    /** 生效值：固定为默认（药丸底栏）。 */
    val stylePillTabBarEnabled: Flow<Boolean> = flowOf(true)

    suspend fun setStylePillTabBar(enabled: Boolean) = settings.setDevStylePillTabBar(enabled)

    /** 「深色今日」原始值（已无界面入口，仅留档）。 */
    val styleSolidTodaySwitch: Flow<Boolean> = settings.devStyleSolidToday

    /** 生效值：固定为默认（实心主色块）。 */
    val styleSolidTodayEnabled: Flow<Boolean> = flowOf(true)

    suspend fun setStyleSolidToday(enabled: Boolean) = settings.setDevStyleSolidToday(enabled)

    /** 「日历默认周视图」原始值（已无界面入口，仅留档）。 */
    val styleCalendarWeekDefaultSwitch: Flow<Boolean> = settings.devStyleCalendarWeekDefault

    /** 生效值：固定为默认（展开整月，不收起成一周）。 */
    val styleCalendarWeekDefaultEnabled: Flow<Boolean> = flowOf(false)

    suspend fun setStyleCalendarWeekDefault(enabled: Boolean) =
        settings.setDevStyleCalendarWeekDefault(enabled)

    /** 「小米风格滑块」原始值（已无界面入口，仅留档）。 */
    val styleXiaomiSliderSwitch: Flow<Boolean> = settings.devStyleXiaomiSlider

    /** 生效值：固定为默认（小米风格滑块）。 */
    val styleXiaomiSliderEnabled: Flow<Boolean> = flowOf(true)

    suspend fun setStyleXiaomiSlider(enabled: Boolean) = settings.setDevStyleXiaomiSlider(enabled)

    // ---- 「页面自定义」（2026-10-24 需求 4）--------------------------------
    //
    // 总门 + 四个子开关，成对约定与上面所有批次一致：
    // `...Switch` 是原始值（二级页回显），`...Enabled` 是生效值（业务侧判断）。
    //
    // 生效规则：**总门关 → 全部回落默认**。四个子项都是"本来就有的观感/内容"
    // （默认文字显示、默认按钮样式、默认图标、原样文本），因此回落值全是 true——
    // 总门关掉时页面与没动过设置时完全一样。
    // 注意这里的总门是 [SettingsRepository.Keys.DEV_CUSTOMIZE_ENABLED]（页面自定义自己的
    // 总门）**叠加**开发者选项总开关，两级都要开才按子开关走。
    // （2026-10-30 前这里写的是"与游戏批两级门的口径同构"——游戏批的「启用游戏设置」
    // 中间门已按用户要求移除，游戏子项现在是"开发者选项总开关 + 子开关"两级，
    // 与本批同样是"总门 && 子开关"，只是总门不同，别再照抄那句比较。）

    /** 「页面自定义」总开关的**原始值**（界面回显用）。 */
    val customizeEnabledSwitch: Flow<Boolean> = settings.devCustomizeEnabled

    /**
     * 「页面自定义」总门是否**真正生效**。
     *
     * 2026-11-11 起恒为 `false`：该功能（含二级页与一级入口）已按用户要求整体删除，
     * 页面一律按默认观感呈现。原始值 `devCustomizeEnabled` 仍留在 DataStore 里，
     * 但不再有任何读取方参与渲染。
     */
    val customizeEnabled: Flow<Boolean> = flowOf(false)

    /** 「文字显示」原始值（已无界面入口，仅留档）。 */
    val customizeTextStyleSwitch: Flow<Boolean> = settings.devCustomizeTextStyle

    /** 「文字显示」生效值：功能已下线，固定为"按默认排版"（false = 不套自定义口径）。 */
    val customizeTextStyleEnabled: Flow<Boolean> = flowOf(false)

    /** 「按钮样式」原始值（已无界面入口，仅留档）。 */
    val customizeButtonStyleSwitch: Flow<Boolean> = settings.devCustomizeButtonStyle

    /** 「按钮样式」生效值：固定默认。 */
    val customizeButtonStyleEnabled: Flow<Boolean> = flowOf(false)

    /** 「图标样式」原始值（已无界面入口，仅留档）。 */
    val customizeIconStyleSwitch: Flow<Boolean> = settings.devCustomizeIconStyle

    /** 「图标样式」生效值：固定默认。 */
    val customizeIconStyleEnabled: Flow<Boolean> = flowOf(false)

    /** 「文本内容」原始值（已无界面入口，仅留档）。 */
    val customizeTextContentSwitch: Flow<Boolean> = settings.devCustomizeTextContent

    /** 「文本内容」生效值：固定默认（内置原文）。 */
    val customizeTextContentEnabled: Flow<Boolean> = flowOf(false)

    suspend fun setCustomizeEnabled(enabled: Boolean) = settings.setDevCustomizeEnabled(enabled)

    suspend fun setCustomizeTextStyle(enabled: Boolean) = settings.setDevCustomizeTextStyle(enabled)

    suspend fun setCustomizeButtonStyle(enabled: Boolean) =
        settings.setDevCustomizeButtonStyle(enabled)

    suspend fun setCustomizeIconStyle(enabled: Boolean) = settings.setDevCustomizeIconStyle(enabled)

    suspend fun setCustomizeTextContent(enabled: Boolean) =
        settings.setDevCustomizeTextContent(enabled)

    /** 写入滑块的值。夹取与「默认值是什么」都在仓库层（见 `SettingsRepository` 的常量）。 */
    suspend fun setSplashHoldMs(ms: Int) = settings.setSplashHoldMs(ms)

    // ---- 2026-10-25 本轮新增：动画时长 / 功能开屏 / 撤回转圈 / 昵称检测 / 检查更新 ----
    //
    // 成对约定与本文件其余批次一致：`...Switch` 原始值（界面回显），
    // `...Enabled` / `...Ms` 生效值（业务侧判断）。
    // 生效规则：凡是"本来就有"的观感（转场时长、撤回转圈）总开关关时回落默认值；
    // 增量能力（功能开屏、应用内检查更新）直接 `总开关 && 子开关`。

    /** 二级页转场时长的**原始值**（默认 [SettingsRepository.DEFAULT_NAV_ANIM_MS]）。 */
    val navAnimDurationSwitch: Flow<Int> = settings.devNavAnimMs

    /**
     * 二级页转场时长**生效值**：门控 `TabNavHost` 的推入 / 返回动画。
     * 总开关关 → 回落默认 280ms（= 改造前固定值，不能让默认关闭的总开关改变观感）。
     */
    val navAnimDurationMs: Flow<Int> =
        combine(settings.devModeEnabled, settings.devNavAnimMs) { master, value ->
            if (master) value else SettingsRepository.DEFAULT_NAV_ANIM_MS
        }

    suspend fun setNavAnimMs(ms: Int) = settings.setDevNavAnimMs(ms)

    /** 底栏切换动画时长的**原始值**（默认 [SettingsRepository.DEFAULT_TAB_SWITCH_MS]）。 */
    val tabSwitchDurationSwitch: Flow<Int> = settings.devTabSwitchMs

    /** 底栏切换动画时长**生效值**：门控 `MoreCheckApp` 的切页滑入动画。 */
    val tabSwitchDurationMs: Flow<Int> =
        combine(settings.devModeEnabled, settings.devTabSwitchMs) { master, value ->
            if (master) value else SettingsRepository.DEFAULT_TAB_SWITCH_MS
        }

    suspend fun setTabSwitchMs(ms: Int) = settings.setDevTabSwitchMs(ms)

    // ── 实验室功能开屏（2026-10-27 改造成一组：总开关 + 逐项 + 独立时长）──────

    /**
     * 「实验室功能开屏」的**总开关原始值**（默认开）。
     *
     * 它统管这一整组：其他实验室页面、热搜、三个游戏。
     * 关掉后**所有**实验室相关开屏都不显示（用户 2026-10-27 的明确要求）。
     */
    val labSplashAllSwitch: Flow<Boolean> = settings.devLabSplashAll

    /**
     * 总开关是否**真正生效**。
     *
     * 开发者选项总开关关闭时回落 `true`：总开关是调试入口，关掉它不该顺手
     * 把"游戏开屏"这项既有观感也拿走（与本仓库其它"回落既有行为"的子项同口径）。
     */
    val labSplashAllEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devLabSplashAll) { master, all ->
            if (master) all else true
        }

    suspend fun setLabSplashAll(enabled: Boolean) = settings.setDevLabSplashAll(enabled)

    /** 「其他实验室页面开屏」的**原始值**（默认关；旧键 `dev_lab_feature_splash`）。 */
    val labFeatureSplashSwitch: Flow<Boolean> = settings.devLabFeatureSplash

    /**
     * 「其他实验室页面开屏」是否**真正生效**。
     *
     * 与改造前的差别只有一处：多了一道 [labSplashAllEnabled] 总门。
     * 其余合成规则（`开发者选项总开关 && 子开关`，无回落）逐字保留。
     */
    val labFeatureSplashEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devLabFeatureSplash
        ) { master, all, sub ->
            master && all && sub
        }

    suspend fun setLabFeatureSplash(enabled: Boolean) = settings.setDevLabFeatureSplash(enabled)

    /** 「热搜页开屏」的**原始值**（默认关 = 改造前的实际观感）。 */
    val labSplashHotSearchSwitch: Flow<Boolean> = settings.devLabSplashHotSearch

    /**
     * 「热搜页开屏」是否**真正生效**（`总开关 && 总门 && 子开关`，无回落——
     * 它是纯增量能力，默认本来就不播）。
     *
     * 热搜从"其他实验室页面"里独立出来单独开一个开关，是因为用户在需求里点名了它：
     * 热搜是他最常进的页面，想单独控制。
     */
    val labSplashHotSearchEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devLabSplashHotSearch
        ) { master, all, sub ->
            master && all && sub
        }

    suspend fun setLabSplashHotSearch(enabled: Boolean) =
        settings.setDevLabSplashHotSearch(enabled)

    /** 实验室开屏时长的**原始值**（默认 [SettingsRepository.DEFAULT_LAB_SPLASH_HOLD_MS]）。 */
    val labSplashHoldSwitch: Flow<Int> = settings.devLabSplashHoldMs

    /**
     * 实验室开屏时长的**生效值**：总门关掉时给 `0`。
     *
     * `LabSplashHost` 把 `<= 0` 当作"不播"，所以这一个流就同时表达了两件事
     * （"总开关关"与"时长设为 0"），调用方不必再判一次开关。
     */
    val labSplashHoldMs: Flow<Int> =
        combine(
            settings.devModeEnabled,
            settings.devLabSplashAll,
            settings.devLabSplashHoldMs
        ) { master, all, ms ->
            if (master && all) ms else 0
        }

    suspend fun setLabSplashHoldMs(ms: Int) = settings.setDevLabSplashHoldMs(ms)

    /** 「撤回退出转圈」回退开关的**原始值**（默认开）。 */
    val revokeExitSpinnerSwitch: Flow<Boolean> = settings.devRevokeExitSpinner

    /**
     * 「撤回退出转圈」是否**真正生效**（`if (master) sub else true`）：
     * 转圈 + 0.2 秒延迟是本轮的**默认新行为**，总开关关 / 回退开关关时才回到
     * 「点确认立即退出」的旧行为。
     */
    val revokeExitSpinnerEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devRevokeExitSpinner) { master, sub ->
            if (master) sub else true
        }

    suspend fun setRevokeExitSpinner(enabled: Boolean) = settings.setDevRevokeExitSpinner(enabled)

    /** 注册页昵称检测转圈时长的**原始值**（默认 200ms）。 */
    val nicknameCheckMsSwitch: Flow<Int> = settings.devNicknameCheckMs

    /**
     * 昵称检测转圈时长**生效值**：总开关关时回落默认 200ms
     * （新默认 = 200，只有开发者主动调才会变）。
     */
    val nicknameCheckDurationMs: Flow<Int> =
        combine(settings.devModeEnabled, settings.devNicknameCheckMs) { master, value ->
            if (master) value else SettingsRepository.DEFAULT_NICKNAME_CHECK_MS
        }

    suspend fun setNicknameCheckMs(ms: Int) = settings.setDevNicknameCheckMs(ms)

    /** 「检查更新」应用内打开的**原始值**（默认关 = 系统浏览器）。 */
    val checkUpdateInAppSwitch: Flow<Boolean> = settings.devCheckUpdateInApp

    /** 「检查更新」应用内打开是否生效（`总开关 && 子开关`，增量能力）。 */
    val checkUpdateInAppEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devCheckUpdateInApp) { master, sub ->
            master && sub
        }

    suspend fun setCheckUpdateInApp(enabled: Boolean) = settings.setDevCheckUpdateInApp(enabled)

    /**
     * 「检查更新」按钮的行为（本轮需求 2）。
     *
     * 与上面的布尔开关不同，这里是一个三值枚举（见 [CheckUpdateTarget]）：
     * 新增了"云端安装包下载页"这个去处之后，两个布尔开关已经表达不清，
     * 再叠一个就会出现"都为 true 时算哪个"的非法状态。
     *
     * 不参与总开关的「与」运算：它是**内容配置**（按钮去哪），
     * 而不是"某个额外能力是否启用"；总开关关掉时这个按钮本身还在「关于」页上。
     */
    val checkUpdateTarget: Flow<CheckUpdateTarget> = settings.checkUpdateTarget

    suspend fun setCheckUpdateTarget(target: CheckUpdateTarget) =
        settings.setCheckUpdateTarget(target)

    /**
     * 「更新日志」入口放在哪（2026-11-02 需求）。
     *
     * 与 [checkUpdateTarget] 一样是**内容配置**，不参与总开关的「与」运算：
     * 总开关只是"开发者选项里的实验性开关是否生效"，
     * 而"日志门口开在哪"不依赖任何实验能力——关掉总开关它也应该照旧按用户选的放。
     */
    val changelogEntryPlacement: Flow<ChangelogEntryPlacement> = settings.changelogEntryPlacement

    suspend fun setChangelogEntryPlacement(placement: ChangelogEntryPlacement) =
        settings.setChangelogEntryPlacement(placement)

    /**
     * 「支持作者」链接的**自定义值**（2026-10-29 需求：
     * 「点击支持作者后，默认打开 B 站主页，打开内容可在开发者选项里修改」）。
     *
     * null = 未自定义 → 调用方回落到内置默认地址。
     *
     * 与上面那些布尔开关不同，这里**不参与总开关的「与」运算**：
     * 它是一个"内容配置"而不是"是否生效"的开关——总开关关掉时它本来就不该被用上，
     * 但值本身要原样读得回来（下次打开开发者选项时输入框里仍是用户上次填的地址）。
     * 界面侧仍按本页口径把编辑入口的 `enabled` 绑在总开关上。
     */
    val supportAuthorUrl: Flow<String?> = settings.supportAuthorUrl

    /** 写入「支持作者」链接；null / 空串 = 恢复默认。 */
    suspend fun setSupportAuthorUrl(url: String?) = settings.setSupportAuthorUrl(url)

    // ⚠️ 原 `setSplashJitterMs`（"浮动范围"滑块写入口）随需求 8 一并删除。

    /** 写入「左右滑动动画」开关（需求 11a）。 */
    suspend fun setTabSlideAnim(enabled: Boolean) = settings.setDevTabSlideAnim(enabled)

    /** 写入「贪吃蛇开屏显示」开关（需求 2）。 */
    suspend fun setGameSplashSnake(enabled: Boolean) =
        settings.setDevGameSplashSnake(enabled)

    /** 写入「数独开屏显示」开关（需求 2）。 */
    suspend fun setGameSplashSudoku(enabled: Boolean) =
        settings.setDevGameSplashSudoku(enabled)

    /** 写入「游戏标题栏显示」开关（需求 2，作用于贪吃蛇与数独两页）。 */
    suspend fun setGameTitleBar(enabled: Boolean) = settings.setDevGameTitleBar(enabled)

    /** 写入「贪吃蛇标题栏显示」子开关（本轮需求 5，二级页里拨）。 */
    suspend fun setGameTitleBarSnake(enabled: Boolean) =
        settings.setDevGameTitleBarSnake(enabled)

    /** 写入「数独标题栏显示」子开关（本轮需求 5，二级页里拨）。 */
    suspend fun setGameTitleBarSudoku(enabled: Boolean) =
        settings.setDevGameTitleBarSudoku(enabled)

    /** 写入「数独2 标题栏显示」子开关（2026-10-22）。 */
    suspend fun setGameTitleBarSudoku2(enabled: Boolean) =
        settings.setDevGameTitleBarSudoku2(enabled)

    /** 写入「游戏标题栏 / 状态栏颜色」档位（需求 3）。存的是 [GameChromeMode.value]。 */
    suspend fun setGameChromeMode(mode: GameChromeMode) =
        settings.setDevGameChromeMode(mode.value)

    // ---- 验证码测试 --------------------------------------------------------

    /**
     * 当前有效的验证码；null = 还没发过、或已被消费 / 清除。
     *
     * `@Volatile`：写入发生在「点发送验证码」那一侧（可能不在主线程），
     * 读取发生在登录校验那一侧；虽然本项目两侧都在主线程调度，
     * 但加上它成本为零，且避免以后把校验挪到 IO 线程时踩内存可见性的坑。
     */
    @Volatile
    private var issuedCode: String? = null

    /** 是否已经发过验证码（用于界面提示与校验顺序）。 */
    fun hasIssuedCode(): Boolean = issuedCode != null

    /** 仅供调试展示：当前验证码（界面上不展示，避免把"答案"直接给用户）。 */
    fun peekCode(): String? = issuedCode

    /**
     * 生成一个六位验证码、记为「后端记录」，并给用户发一条通知。
     *
     * 返回生成的验证码，便于调用方做诊断输出（正式包不展示）。
     *
     * 六位数字首位允许为 0：真实短信验证码就是 000000~999999 的等概率六位，
     * 若强制首位非 0 会变成 100000~999999，与真实场景不符（也少了一成空间）。
     */
    fun issueCode(): String {
        val code = buildString {
            repeat(CODE_LENGTH) { append(Random.nextInt(0, 10)) }
        }
        issuedCode = code
        // 通知是「模拟收到短信」的关键一步：用户要从通知里读到这六位数字再填回输入框。
        notificationHelper.notifyDevVerificationCode(code)
        return code
    }

    /**
     * 校验用户填写的验证码。
     *
     * 规则（需求：只有与后端记录一致才能成功）：
     * - 还没发过码 → 一律失败（否则「随便填」也能过）；
     * - 与记录不一致 → 失败；
     * - 一致 → **消费掉**（置空），保证一个码只能用一次。
     */
    fun verifyAndConsume(input: String): Boolean {
        val expected = issuedCode ?: return false
        if (input.trim() != expected) return false
        issuedCode = null
        return true
    }

    /** 清除当前验证码（例如点「发送验证码」后重新进入页面、或退出登录时）。 */
    fun clearCode() {
        issuedCode = null
    }

    /**
     * 同步读取「验证码测试是否生效」。
     *
     * 给**非 Composable 的流程**（登录 / 注册按钮的点击回调）用：
     * 那里拿不到 `StateFlow`，但需要立刻决定「是走真实校验还是走原来的任意验证码」。
     * 用 `first()` 取 DataStore 的当前值——DataStore 首次读取有磁盘 IO，
     * 因此调用点要在协程里（点击回调本来就在 `viewModelScope` 里）。
     */
    suspend fun isCodeTestEnabledNow(): Boolean =
        settings.devModeEnabled.first() && settings.devCodeTestEnabled.first()

    internal companion object {
        /** 验证码位数。 */
        const val CODE_LENGTH = 6
    }
}
