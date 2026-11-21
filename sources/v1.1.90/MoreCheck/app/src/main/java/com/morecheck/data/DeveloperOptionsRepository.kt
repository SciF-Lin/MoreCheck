package com.morecheck.data

import com.morecheck.reminder.NotificationHelper
import com.morecheck.ui.lab.GameChromeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
     * 「游戏设置」总开关（本轮需求 3）。**原始值**（界面回显用，不代表是否生效）。
     *
     * 它是「游戏标题栏显示」「游戏开屏显示」「游戏颜色模式」三批子项的**上一级**总门。
     * 生效合成不在本属性：它作为中间一级参与下方各 `game*Enabled` 流
     * （`总开关 && 游戏总开关 && 子开关`）。
     */
    val gameSettingsSwitch: Flow<Boolean> = settings.devGameSettingsEnabled

    /**
     * 「游戏设置」总开关是否**真正生效**（本轮需求 3）。
     *
     * 规则与本文件其它受门控项一致：`开发者选项总开关 && 游戏设置总开关`。
     * 关掉任一级，游戏子设置一律回落默认行为（标题栏显示、开屏显示、深色经典）。
     */
    val gameSettingsEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devGameSettingsEnabled) { master, game ->
            master && game
        }

    /** 原始子开关状态，仅用于界面回显（**不代表是否生效**）。 */
    val codeTestSwitch: Flow<Boolean> = settings.devCodeTestEnabled
    val perfTestSwitch: Flow<Boolean> = settings.devPerfTestEnabled

    /** 「验证码测试」是否**真正生效**（总开关与子开关都开）。 */
    val codeTestEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devCodeTestEnabled) { master, sub -> master && sub }

    /** 「性能测试」是否生效。 */
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
     * 它是一级页入口行右侧那个开关：关掉后 [gameSplashSnakeEnabled] 与
     * [gameSplashSudokuEnabled] 都回落"显示"（= 改造前行为），
     * 子开关的位置仍保留在二级页里。
     */
    val gameSplashSwitch: Flow<Boolean> = settings.devGameSplash

    suspend fun setGameSplash(enabled: Boolean) = settings.setDevGameSplash(enabled)

    /**
     * 「贪吃蛇开屏显示」开关的**原始值**（界面回显用，需求 2）。
     *
     * 只作用于贪吃蛇页。数独页由 [gameSplashSudokuSwitch] 控制。
     */
    val gameSplashSnakeSwitch: Flow<Boolean> = settings.devGameSplashSnake

    /**
     * 「贪吃蛇开屏显示」是否**真正生效**（需求 2；本轮需求 3 起同时受「游戏设置」总开关门控）。
     *
     * 合成规则：`开发者选项总开关 && 游戏设置总开关` 都开才按子开关，
     * 否则回落**开**（开屏照常显示 = 改造前行为）。回落"关"会让没开过开发者选项的用户
     * 升级后突然看不到开屏——那是用默认关闭的开关改掉了所有人的既有观感。
     */
    val gameSplashSnakeEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameSplash,
            settings.devGameSplashSnake
        ) { master, game, total, sub ->
            if (master && game && total) sub else true
        }

    /**
     * 「数独开屏显示」开关的**原始值**（界面回显用，需求 2）。
     *
     * 只作用于数独页。贪吃蛇页由 [gameSplashSnakeSwitch] 控制。
     */
    val gameSplashSudokuSwitch: Flow<Boolean> = settings.devGameSplashSudoku

    /**
     * 「数独开屏显示」是否**真正生效**（需求 2；本轮需求 3 起同时受「游戏设置」总开关门控）。
     * 合成规则同上。
     */
    val gameSplashSudokuEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameSplash,
            settings.devGameSplashSudoku
        ) { master, game, total, sub ->
            if (master && game && total) sub else true
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
     * 本轮需求 3 起同时受「游戏设置」总开关门控）。
     *
     * 回落规则同上：任一级总开关关 → 恒显示标题栏（= 改造前行为）。
     */
    val gameTitleBarEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameTitleBar
        ) { master, game, sub ->
            if (master && game) sub else true
        }

    // ---- 「游戏标题栏显示」二级页（本轮需求 5）------------------------------
    //
    // 需求原文：「游戏标题栏显示增加二级界面，可单独设置标题栏显隐，原总开关保留，
    // 右侧增加向右箭头」。与「游戏开屏显示」（需求 2）同一条演进路径：
    // 一级页的开关变入口、原总开关保留、两个子开关进二级页。
    //
    // 生效规则（与 `gameSplashSnakeEnabled` / `gameSplashSudokuEnabled` 逐字同构）：
    // **总开关 && 游戏总开关 && 游戏子开关** 三者都开才显示该页标题栏；
    // 任何一级关掉都回落"显示"（= 改造前行为，回落"关"会让没动过设置的用户
    // 升级后突然看不到标题栏）。

    /**
     * 「贪吃蛇标题栏」的**原始值**（界面回显用，不代表是否生效）。
     */
    val gameTitleBarSnakeSwitch: Flow<Boolean> = settings.devGameTitleBarSnake

    /**
     * 「贪吃蛇标题栏」是否**真正生效**（本轮需求 5；本轮需求 3 起再叠加「游戏设置」总开关）。
     *
     * `开发者选项总开关 && 游戏设置总开关 && 游戏标题栏总开关 && 子开关`
     * ——任何一级关闭都回落 true（= 改造前行为）。
     */
    val gameTitleBarSnakeEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSnake
        ) { master, game, total, sub ->
            if (master && game && total) sub else true
        }

    /**
     * 「数独标题栏」的**原始值**（界面回显用，不代表是否生效）。
     */
    val gameTitleBarSudokuSwitch: Flow<Boolean> = settings.devGameTitleBarSudoku

    /**
     * 「数独标题栏」是否**真正生效**（本轮需求 5；本轮需求 3 起再叠加「游戏设置」总开关）。
     * 合成规则同上。
     */
    val gameTitleBarSudokuEnabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSudoku
        ) { master, game, total, sub ->
            if (master && game && total) sub else true
        }

    /**
     * 「数独2 标题栏」的**原始值**（界面回显用，不代表是否生效，2026-10-22 新增）。
     */
    val gameTitleBarSudoku2Switch: Flow<Boolean> = settings.devGameTitleBarSudoku2

    /**
     * 「数独2 标题栏」是否**真正生效**（2026-10-22 新增）。
     *
     * 与 [gameTitleBarSnakeEnabled] / [gameTitleBarSudokuEnabled] 逐字同构：
     * `开发者选项总开关 && 游戏设置总开关 && 游戏标题栏总开关 && 子开关`，
     * 任何一级关闭都回落 true（= 改造前行为）。
     *
     * 这个子开关是补上的——此前数独2 只受"两页共用"的总开关控制，
     * 而那个总开关在界面上已经变成二级页入口，用户无处可关（详见 `SettingsRepository` 的键注释）。
     */
    val gameTitleBarSudoku2Enabled: Flow<Boolean> =
        combine(
            settings.devModeEnabled,
            settings.devGameSettingsEnabled,
            settings.devGameTitleBar,
            settings.devGameTitleBarSudoku2
        ) { master, game, total, sub ->
            if (master && game && total) sub else true
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
            settings.devGameSettingsEnabled,
            gameChromeModeSwitch
        ) { master, game, chosen ->
            if (master && game) chosen else GameChromeMode.DARK_CLASSIC
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
     * 语义：勾上后每次开屏时长 = 设定值 ± [SettingsRepository.SPLASH_JITTER_SPREAD_MS]（0.3 秒）。
     */
    val splashJitterEnabledSwitch: Flow<Boolean> = settings.splashJitterEnabled

    suspend fun setSplashJitterEnabled(enabled: Boolean) =
        settings.setSplashJitterEnabled(enabled)

    /**
     * 开屏时长**当前真正生效**的值（毫秒），含需求 12 的随机浮动。
     *
     * ## 合成规则
     *
     * 门控（总开关 && 性能测试开关）的口径不变；门控通过后：
     * `基准 ± 0.3 秒`（2026-10-23 需求 8 明确改成"正负"），结果夹在
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
            settings.devPerfTestEnabled,
            settings.splashHoldMs,
            settings.splashJitterEnabled
        ) { master, perf, hold, jitterOn ->
            if (!master || !perf) {
                SettingsRepository.DEFAULT_SPLASH_HOLD_MS
            } else {
                val base = hold.coerceIn(
                    SettingsRepository.MIN_SPLASH_HOLD_MS,
                    SettingsRepository.MAX_SPLASH_HOLD_MS
                )
                // 2026-10-23 需求 8：勾上「随机浮动」后是**上下各 0.3 秒**（可正可负）。
                // 下限夹到 0：负向抖动不该把开屏时长压成负数（`delay` 收到负值会立刻返回，
                // 那会让开屏在没有最短展示时间的情况下被跳过）。
                if (!jitterOn) {
                    base
                } else {
                    val spread = SettingsRepository.SPLASH_JITTER_SPREAD_MS
                    (base + Random.nextInt(-spread, spread + 1)).coerceIn(
                        SettingsRepository.MIN_SPLASH_HOLD_MS,
                        SettingsRepository.MAX_SPLASH_HOLD_MS
                    )
                }
            }
        }

    suspend fun setMasterEnabled(enabled: Boolean) = settings.setDevModeEnabled(enabled)

    /** 写入「游戏设置」总开关（本轮需求 3）。 */
    suspend fun setGameSettingsEnabled(enabled: Boolean) =
        settings.setDevGameSettingsEnabled(enabled)

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

    /** 「页面转场动画」是否**真正生效**（门控 `TabNavHost` 里那四个转场函数）。 */
    val pageTransitionAnimEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devPageTransitionAnim) { master, sub ->
            master && sub
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

    // ---- 样式设置（2026-10-22 需求 8）-------------------------------------
    //
    // 与「界面测试」那批开关同一套成对约定：`...Switch` 是原始值（界面回显），
    // `...Enabled` 是生效值（业务侧判断）。
    //
    // 生效规则统一为：**总开关关 → 回落默认值**（而不是一律 false）。
    // 理由与 `gameSplashSnakeEnabled` 那条完全一致：这些都是"改造前本来就有 / 本来没有"
    // 的观感项，回落 false 会让没开过开发者选项的用户突然丢掉渐变按钮或小米滑块。

    /** 「渐变边框按钮」原始值（默认 true）。 */
    val styleGradientButtonSwitch: Flow<Boolean> = settings.devStyleGradientButton

    val styleGradientButtonEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devStyleGradientButton) { master, sub ->
            if (master) sub else true
        }

    suspend fun setStyleGradientButton(enabled: Boolean) =
        settings.setDevStyleGradientButton(enabled)

    /** 「药丸底栏」原始值（默认 true）。 */
    val stylePillTabBarSwitch: Flow<Boolean> = settings.devStylePillTabBar

    val stylePillTabBarEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devStylePillTabBar) { master, sub ->
            if (master) sub else true
        }

    suspend fun setStylePillTabBar(enabled: Boolean) = settings.setDevStylePillTabBar(enabled)

    /** 「深色今日」原始值（默认 true）。 */
    val styleSolidTodaySwitch: Flow<Boolean> = settings.devStyleSolidToday

    val styleSolidTodayEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devStyleSolidToday) { master, sub ->
            if (master) sub else true
        }

    suspend fun setStyleSolidToday(enabled: Boolean) = settings.setDevStyleSolidToday(enabled)

    /** 「日历默认周视图」原始值（默认 false = 默认展开整月）。 */
    val styleCalendarWeekDefaultSwitch: Flow<Boolean> = settings.devStyleCalendarWeekDefault

    val styleCalendarWeekDefaultEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devStyleCalendarWeekDefault) { master, sub ->
            if (master) sub else false
        }

    suspend fun setStyleCalendarWeekDefault(enabled: Boolean) =
        settings.setDevStyleCalendarWeekDefault(enabled)

    /** 「小米风格滑块」原始值（默认 true）。 */
    val styleXiaomiSliderSwitch: Flow<Boolean> = settings.devStyleXiaomiSlider

    val styleXiaomiSliderEnabled: Flow<Boolean> =
        combine(settings.devModeEnabled, settings.devStyleXiaomiSlider) { master, sub ->
            if (master) sub else true
        }

    suspend fun setStyleXiaomiSlider(enabled: Boolean) = settings.setDevStyleXiaomiSlider(enabled)

    /** 写入滑块的值。夹取与「默认值是什么」都在仓库层（见 `SettingsRepository` 的常量）。 */
    suspend fun setSplashHoldMs(ms: Int) = settings.setSplashHoldMs(ms)

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
