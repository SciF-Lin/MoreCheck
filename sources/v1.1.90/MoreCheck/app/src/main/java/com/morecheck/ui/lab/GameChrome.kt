package com.morecheck.ui.lab

/**
 * 网页游戏页（贪吃蛇 / 数独 / 将来的番茄时钟）的「标题栏 + 系统栏」配色档位。
 *
 * ## 为什么需要三档，而不是「跟随主题」一个布尔
 *
 * 两个游戏页承载的是**永远深色**的网页内容（贪吃蛇 `--bg-0 = #080B14`、
 * 数独 `--c-bg = #12151C`），而应用本身有浅色 / 深色两套主题。历史上这两页
 * 直接吃 `TopAppBar` 的默认配色，于是浅色主题下出现「白底 + 白图标」的状态栏
 * （系统栏图标因为游戏固定深底被设成浅色，标题栏却是浅底深字）——见 README
 * 「修复：数独与贪吃蛇页面的状态栏、导航栏图标看不清」那条。
 *
 * 修成「固定深色」之后又带来另一种不满：浅色主题下整页一条深色标题栏显得突兀。
 * 两种诉求都合理，但它们不可能用同一个布尔表达，所以做成**三档显式选择**：
 *
 * - [SYSTEM]：chrome 完全跟随主题（浅色主题 = 浅底深字 + 深色系统栏图标）。
 *   代价是标题栏与下方的深色游戏区之间会出现一条明显分界——这是这一档的
 *   语义本身（"我要它跟应用其它页面一样"），不是缺陷；
 * - [DARK_CLASSIC]：chrome 用页面自己的固定深色常量（与 WebView 兜底色、
 *   与页面 `--bg-0 / --c-bg` 同值），标题栏与游戏区连成一片，系统栏用浅色图标。
 *   这是改造前的行为，也是默认值；
 * - [DARK_ADAPTIVE]：容器与内容层同 [DARK_CLASSIC]（固定深色），但 `TopAppBar`
 *   的容器色取透明——标题栏不再画一块自己的底色，视觉上"没有标题栏那块板"，
 *   只有标题文字与返回箭头浮在深色游戏区上沿。适合"想要返回入口但嫌标题栏占地方"。
 *
 * ## 为什么枚举放在 `ui/lab` 而不是 `data`
 *
 * 它描述的是**界面渲染策略**，三个使用方都是 Composable；真正的持久化仍然只有
 * 一个字符串键（见 `SettingsRepository.Keys.DEV_GAME_CHROME_MODE`），
 * `data` 层只负责把字符串读出来 / 写进去。`DeveloperOptionsRepository` 需要把这个
 * 枚举暴露成流，因此它会 `import com.morecheck.ui.lab.GameChromeMode`——
 * 这是本模块内唯一一处 data → ui 的依赖，换来的好处是"三档的取值字符串"只在
 * 本文件里定义一次；若为了分层干净而在 data 里再抄一份枚举，两处的 value 迟早漂移，
 * 而那种漂移**没有任何编译错误**。
 *
 * ## `value` 为什么是稳定字符串而不是枚举序号
 *
 * 与项目里 `DEV_ICON_STYLE` / `DEV_LAB_BG_MODE` 同一口径：存进 DataStore 的是
 * [value] 而不是 `ordinal`。枚举声明顺序一旦调整（例如把 SYSTEM 挪到最前），
 * 序号会整体错位，用户已选的档位会被静默换成另一档；字符串则不会。
 *
 * @property value 持久化到 DataStore 的稳定标识，**不要改**已发布的值。
 * @property label 界面上显示的中文名。
 */
enum class GameChromeMode(val value: String, val label: String) {
    /** 跟随系统：标题栏 / 容器走主题语义色，系统栏图标按主题深浅取深色或浅色。 */
    SYSTEM("system", "跟随系统"),

    /** 深色经典：整页固定深色（页面自己的常量），标题栏一块深色板 + 浅色图标。默认档。 */
    DARK_CLASSIC("dark_classic", "深色经典"),

    /** 深色适配：容器与内容固定深色，但标题栏容器透明，与游戏区无缝。 */
    DARK_ADAPTIVE("dark_adaptive", "深色适配");

    companion object {
        /**
         * 把 DataStore 里的原始字符串还原成枚举。
         *
         * 解析失败（脏值 / 老版本写入的未知值 / 键还不存在时的 `null`）一律回落
         * [DARK_CLASSIC]：它是改造前的既有观感，也是"没配过"时最不意外的结果。
         * 刻意不抛异常——一个配色偏好读失败不该让游戏页打不开。
         */
        fun fromValue(raw: String?): GameChromeMode =
            // 显式写全 `GameChromeMode.entries`（而非 companion 里裸写 `entries`）：
            // 两者当前解析到同一份枚举值，但裸写依赖"companion 能看见外部静态成员"
            // 这条隐式规则；显式写全没有任何歧义。
            GameChromeMode.entries.firstOrNull { it.value == raw } ?: DARK_CLASSIC
    }
}
