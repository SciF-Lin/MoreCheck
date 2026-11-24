package com.morecheck.data

/**
 * 开屏文字的**字体档位**（2026-10-27 需求：自定义开屏显示文字，可调字体）。
 *
 * 只做三档系统字体族，**不引入字体资源文件**：
 *
 * | 档位 | 实际字体族 | 观感 |
 * | --- | --- | --- |
 * | [DEFAULT] | 系统无衬线（`FontFamily.SansSerif`） | 与改造前一致 |
 * | [SERIF] | 衬线（`FontFamily.Serif`） | 有点像印刷品，中文回落宋体 |
 * | [MONOSPACE] | 等宽（`FontFamily.Monospace`） | 像终端 / 代码 |
 *
 * 为什么不打包 ttf：一个中文字体动辄 5~10MB，为了三个字往 APK 里塞字体不值得；
 * 而系统字体族在 Android 上**自带中文回退**（中文字形由系统的 CJK 回退字体补上），
 * 所以「支持中文、英文和数字」这条不需要额外做任何事。
 *
 * [id] 是**持久化用的稳定字符串**（绝不存枚举序号）：以后增删档位时，
 * 已存在用户盘上的值不会因为序号漂移而跳到别的档。
 */
enum class SplashTextFont(val id: String) {
    DEFAULT("default"),
    SERIF("serif"),
    MONOSPACE("monospace");

    companion object {
        /** 解析持久值；认不出的值一律回落 [DEFAULT]（宁可回到默认观感，也不崩）。 */
        fun fromId(id: String?): SplashTextFont =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 「开屏显示文字」的完整设置（2026-10-27 需求）。
 *
 * 需求原文：「支持自定义开屏显示文字（默认为现在的 hello world），可以调整文字大小、
 * 字体，支持中文、英文和数字，支持单行显示与多行显示，也可以一键恢复默认」。
 *
 * ## 一份设置，四处开屏共用
 *
 * 本应用有四处开屏文案：应用启动（`ui/nav/SplashScreen.kt`）、实验室功能页
 * （`LabSplashHost`）、锁屏解锁前那一层、三个网页游戏页。用户看到的是**同一句
 * Hello World**，所以这里只存**一份**设置——否则"改了启动开屏，进实验室还是旧字"
 * 会被当成 bug 报上来。
 *
 * ## 字号为什么是绝对 sp 而不是百分比
 *
 * 四处原本各自写死（启动页用 `displaySmall`、实验室层用 34sp、游戏页用 `displaySmall`）。
 * 换成同一条绝对 sp 后，四处**第一次真正一致**；用户在一个输入框里看到 34，
 * 就知道自己在调什么。百分比方案要维护四套基准值，改一处基准就全乱。
 *
 * ## 默认值必须等于"改造前的观感"
 *
 * [DEFAULT_TEXT] 就是原来的 `Hello World`、[DEFAULT_SIZE_SP] 就是原来实验室层的字号、
 * [DEFAULT] 字体与单行显示也都是原样——**没动过设置的用户升级后看不出任何变化**，
 * 这是本项目对"新增可调项"的一贯要求（见 `DeveloperOptionsRepository` 里
 * 各 `*Enabled` 的回落值口径）。
 *
 * @property text 显示的文字，允许换行符（多行模式下生效）；超长由
 *   [SettingsRepository] 在写入时截断到 [MAX_TEXT_LENGTH] 个字符。
 * @property sizeSp 字号（sp），夹在 [[MIN_SIZE_SP], [MAX_SIZE_SP]] 之间。
 * @property font 字体档位，见 [SplashTextFont]。
 * @property multiline 是否多行显示。`false` = 单行（换行符按空格处理、不自动换行）。
 */
data class SplashTextStyle(
    val text: String = DEFAULT_TEXT,
    val sizeSp: Int = DEFAULT_SIZE_SP,
    val font: SplashTextFont = SplashTextFont.DEFAULT,
    val multiline: Boolean = false
) {
    /** 是否与出厂设置完全一致（「恢复默认」按钮据此决定要不要置灰）。 */
    val isDefault: Boolean
        get() = this == DEFAULT

    companion object {
        /** 默认文案 = 改造前那四个字（一字不差，别改）。 */
        const val DEFAULT_TEXT = "Hello World"

        /** 默认字号（sp）。与改造前 `HelloWorldSplashLayer` 写死的 34sp 同值。 */
        const val DEFAULT_SIZE_SP = 34

        /** 字号下限：再小就只是一行注释，不构成开屏。 */
        const val MIN_SIZE_SP = 16

        /** 字号上限：再大单行中文在窄屏上会顶到边（多行模式下也够用）。 */
        const val MAX_SIZE_SP = 72

        /** 字号滑块步长（1sp 一档，拖起来连续但不至于跳动）。 */
        const val SIZE_STEP_SP = 1

        /**
         * 文案最大长度（字符数）。
         *
         * 取 60 而不是无限：开屏层是**无滚动、居中单块**的排版，几百字的文案会被裁掉，
         * 反而让人以为"设置没生效"。60 字在 72sp 多行模式下仍可能超出屏幕，
         * 那种情况靠 [SettingsRepository] 之外的渲染层按 `ellipsis` 兜住，不在这里卡死。
         */
        const val MAX_TEXT_LENGTH = 60

        /** 出厂设置（见类 KDoc「默认值必须等于改造前的观感」）。 */
        val DEFAULT = SplashTextStyle()
    }
}
