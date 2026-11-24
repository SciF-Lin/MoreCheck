package com.morecheck.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.util.AvatarStore
import com.morecheck.util.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 「关于 → 检查更新」按钮点下去去哪（本轮需求 2）。
 *
 * ## 为什么做成枚举而不是再加一个布尔开关
 *
 * 需求原文：「可在开发者选项切换检查更新按钮的功能」。原来的开关
 * `devCheckUpdateInApp` 只能表达"浏览器 ↔ 应用内网页"二选一，
 * 本轮新增了第三个去处（云端安装包下载页），再叠一个布尔就会出现
 * "两个开关都为 true 时算哪个"这类没法自洽的组合。
 * 一个三值枚举把"按钮只有一个行为"这件事写进类型里，不存在非法状态。
 */
enum class CheckUpdateTarget(val value: String, val title: String) {
    /** 云端安装包下载页（默认）：正式版 APK 托管在打卡的云存储上。 */
    APK_DOWNLOAD("apk", "安装包下载页"),

    /** 应用内「官网」加载 GitHub Releases（既有行为，国内网络常失败）。 */
    WEB_IN_APP("web", "应用内网页"),

    /** 交给系统浏览器打开 GitHub Releases（最原始的行为）。 */
    BROWSER("browser", "系统浏览器");

    companion object {
        /** 脏值 / 缺省一律回落默认值 [APK_DOWNLOAD]。 */
        fun fromValue(raw: String?): CheckUpdateTarget =
            entries.firstOrNull { it.value == raw } ?: APK_DOWNLOAD
    }
}

/**
 * 「更新日志」入口放在哪（2026-11-02 需求）。
 *
 * 原来这个位置是**跟着「检查更新」的去处自动决定**的（去下载页就跟过去）。
 * 但那是实现者的推断，不是用户的选择：有人就是想在关于页看到它。
 * 所以拆成一个独立设置项，两条路都留给用户。
 */
enum class ChangelogEntryPlacement(val value: String, val title: String) {
    /**
     * 放在**安装包下载页**，固定在屏幕底部（默认）。
     *
     * 默认它而不是「关于页」的原因：v1.2.2 起下载页已经承接了这个入口，
     * 升级上来的用户不该因为新增了一个设置项就看到入口"跳回"关于页。
     */
    INSIDE("inside", "安装包下载页（屏幕底部）"),

    /** 放在**关于我们**页，「检查更新」按钮下方（历史位置）。 */
    OUTSIDE("outside", "关于我们页");

    companion object {
        /** 脏值 / 缺省一律回落默认值 [INSIDE]。 */
        fun fromValue(raw: String?): ChangelogEntryPlacement =
            entries.firstOrNull { it.value == raw } ?: INSIDE
    }
}

/** App-level settings persisted with Jetpack DataStore (Preferences). */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_TASK_SEEDED = booleanPreferencesKey("default_task_seeded")
        val LAST_ROLLOVER_DATE = stringPreferencesKey("last_rollover_date")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_ACCENT = stringPreferencesKey("theme_accent")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")

        /**
         * 「打开软件时提示登录」的开关。
         *
         * 与 `privacy_agreed` 是两件事：那个是「必须同意协议才能用」的强制门闸，
         * 这个是同意之后那个**可关的引导弹窗**（需求：把它加一个开关）。
         * 因此这里默认 `true`，且用户关掉它不会绕过协议门闸。
         */
        val LOGIN_PROMPT_ENABLED = booleanPreferencesKey("login_prompt_enabled")

        /**
         * 「朋友圈红点提示」的开关（界面位置：实验室 → 开发者选项 → 图标测试）。
         *
         * ## 它管的是「显不显示」，不是「有没有未读」
         *
         * 本项目里"未读"这件事由 `TabBadgeViewModel` 持有（内存态），本键只决定
         * **要不要把那份未读画到底部导航栏的「发现」角标上**。
         * 因此它与「进入朋友圈后清除未读」（`MoreCheckApp` 里
         * `onMomentsOpened` → `TabBadgeViewModel.clear(DISCOVER)`）是**两件独立的事**：
         * 关掉本键之后，进朋友圈该清的未读照清，只是用户再也看不到那个红点。
         * ⚠️ 以后若有人想把清除逻辑也一起门控，先想清楚：那等于"关掉提示"顺手
         * 改掉了未读状态语义，是行为越界。
         *
         * ## 为什么默认 `true`
         *
         * 默认开 = **与改造前的行为完全一致**：改造前「发现」入口一直显示朋友圈未读数，
         * 加这个键只是让用户有办法把它关掉。若默认 `false`，等于所有老用户升级后
         * 底部角标凭空消失——那是用"新增一个设置项"悄悄改掉了所有人都熟悉的观感，
         * 与 [LOGIN_PROMPT_ENABLED] 默认 `true` 是同一条口径。
         */
        val MOMENTS_BADGE_ENABLED = booleanPreferencesKey("moments_badge_enabled")
        val PERM_CAMERA = booleanPreferencesKey("perm_camera_enabled")
        val PERM_LOCATION = booleanPreferencesKey("perm_location_enabled")
        val PERM_NOTIFICATION = booleanPreferencesKey("perm_notification_enabled")
        val TIMER_SOUND_ENABLED = booleanPreferencesKey("timer_sound_enabled")
        val TIMER_SOUND_URI = stringPreferencesKey("timer_sound_uri")

        // ---- 「样式设置」（2026-10-22 需求 8）--------------------------------
        //
        // 一个键对应一个**视觉项**的开关。默认值 = 本文档交付时的观感
        // （渐变按钮 / 药丸底栏 / 深色今日 / 小米滑块 默认开，周视图默认关）。
        // 所有键都只有「样式设置」二级页读写，因此命名统一带 DEV_STYLE_ 前缀。

        /** 实验室「新增功能」按钮的渐变边框（默认开）。 */
        val DEV_STYLE_GRADIENT_BUTTON = booleanPreferencesKey("dev_style_gradient_button")

        /** 热搜底部药丸导航栏（默认开；关掉回落到普通等宽文字栏）。 */
        val DEV_STYLE_PILL_TABBAR = booleanPreferencesKey("dev_style_pill_tabbar")

        /** 日历「今天」用实心主色块标出（默认开；关掉回落到淡色底）。 */
        val DEV_STYLE_SOLID_TODAY = booleanPreferencesKey("dev_style_solid_today")

        /** 日历进页时默认收起为周视图（默认关 = 默认展开整月）。 */
        val DEV_STYLE_CALENDAR_WEEK_DEFAULT = booleanPreferencesKey("dev_style_calendar_week_default")

        /** 滑块的「小米风格」外观（默认开；关掉回落 Material 默认细轨道）。 */
        val DEV_STYLE_XIAOMI_SLIDER = booleanPreferencesKey("dev_style_xiaomi_slider")

        /**
         * 「页面自定义」总开关（2026-10-24 需求 4，默认**关**）。
         *
         * 它是下一批 `DEV_CUSTOMIZE_*` 子项的**总门**：关掉时全部子项回落默认观感
         * （与「游戏设置」总开关的门控语义同构——子开关保存的值不清空，
         * 重新打开后按原样恢复）。这样普通用户不动开发者选项时，
         * 文字/按钮/图标/文本内容全部保持原样；想折腾的人在总门里一次关掉。
         */
        val DEV_CUSTOMIZE_ENABLED = booleanPreferencesKey("dev_customize_enabled")

        /** 页面自定义 · 文字显示（默认开；仅在 [DEV_CUSTOMIZE_ENABLED] 开时生效）。 */
        val DEV_CUSTOMIZE_TEXT_STYLE = booleanPreferencesKey("dev_customize_text_style")

        /** 页面自定义 · 按钮样式（默认开；仅在 [DEV_CUSTOMIZE_ENABLED] 开时生效）。 */
        val DEV_CUSTOMIZE_BUTTON_STYLE = booleanPreferencesKey("dev_customize_button_style")

        /** 页面自定义 · 图标样式（默认开；仅在 [DEV_CUSTOMIZE_ENABLED] 开时生效）。 */
        val DEV_CUSTOMIZE_ICON_STYLE = booleanPreferencesKey("dev_customize_icon_style")

        /** 页面自定义 · 文本内容（默认开；仅在 [DEV_CUSTOMIZE_ENABLED] 开时生效）。 */
        val DEV_CUSTOMIZE_TEXT_CONTENT = booleanPreferencesKey("dev_customize_text_content")

        // ---- 2026-10-25 本轮新增（界面测试 / 登录测试 / 撤回流程 / 开屏密码）----
        //
        // 这一批全部带「默认值 = 本轮改造前的既有行为」的默认值，
        // 不迁移、不回滚，老用户升级后无感。

        /**
         * 二级页推入 / 返回的转场时长（毫秒，默认 [DEFAULT_NAV_ANIM_MS] = 280）。
         *
         * 对应 `TabNavHost` 的 `NAV_DURATION_MS`（原为 file-private 常量，本轮改为可调）。
         */
        val DEV_NAV_ANIM_MS = intPreferencesKey("dev_nav_anim_ms")

        /**
         * 底栏入口切换动画时长（毫秒，默认 [DEFAULT_TAB_SWITCH_MS] = 260）。
         *
         * 对应 `MoreCheckApp` 的 `TAB_SWITCH_MS`（原为常量，本轮改为可调）。
         */
        val DEV_TAB_SWITCH_MS = intPreferencesKey("dev_tab_switch_ms")

        /**
         * 「实验室功能开屏」开关（默认**关**）：开启后进入实验室的每个功能页时
         * 播一次 Hello World 开屏层（进程内每页各一次，见 `TabNavHost.LabSplashHost`）。
         * 默认关因为它是对既有观感的**增量**（ unlike 标题栏那种"本来就有"的回落项）。
         */
        val DEV_LAB_FEATURE_SPLASH = booleanPreferencesKey("dev_lab_feature_splash")

        /**
         * 「实验室功能开屏」的**总开关**（默认**开**，2026-10-27 新增）。
         *
         * ## 为什么默认开（与 [DEV_LAB_FEATURE_SPLASH] 默认关相反）
         *
         * 它管的是「进入实验室功能页 / 游戏时是否显示 Hello World 开屏」这一整组。
         * 组里原有的「游戏开屏」本来就是默认显示（`DEV_GAME_SPLASH` 默认 true），
         * 若总开关默认关，升级后三个游戏的开屏会集体消失——那是**用新增开关
         * 悄悄改掉了既有观感**，正是本项目反复避开的那类改动。
         *
         * 老键 [DEV_LAB_FEATURE_SPLASH] 保留原语义（只管"其他实验室页面"，
         * 默认关），因此升级后行为逐项不变：
         * 游戏照常播、其他实验室页照常不播。
         */
        val DEV_LAB_SPLASH_ALL = booleanPreferencesKey("dev_lab_splash_all")

        /**
         * 「热搜页开屏」子开关（默认**关**，= 改造前的实际行为）。
         *
         * 改造前热搜开屏只在 `DEV_LAB_FEATURE_SPLASH` 打开时才播，而那个键默认关。
         * 现在热搜从"其他实验室页面"里独立出来，默认值必须对齐改造前的实际观感，
         * 所以是关。用户想要就自己打开（二级页里有单独一行）。
         */
        val DEV_LAB_SPLASH_HOTSEARCH = booleanPreferencesKey("dev_lab_splash_hotsearch")

        /**
         * 「数独2 开屏」子开关（默认**开**，2026-10-27 新增）。
         *
         * 数独2 原先**没有开屏层**（`Sudoku2Screen` 不接受 showSplash）。本轮给它补上，
         * 默认值与另外两个游戏对齐（都开），文案里会说明"这是本版新增"。
         */
        val DEV_LAB_SPLASH_SUDOKU2 = booleanPreferencesKey("dev_lab_splash_sudoku2")

        /**
         * 实验室功能开屏的停留时长（毫秒，默认 [DEFAULT_LAB_SPLASH_HOLD_MS]）。
         *
         * ## 为什么与 App 开屏时长分开（2026-10-27 用户要求）
         *
         * 原来 `LabSplashHost` 直接复用 `dev_splash_hold_ms`——那个值是**应用启动**
         * 时 Hello World 的停留时长，调它是为了看"冷启动多久"，和"进实验室某个页面
         * 闪一下"完全不是同一件事。共用会导致：把冷启动时长调到 3 秒，进热搜也要
         * 等 3 秒。分开后两者互不影响。
         */
        val DEV_LAB_SPLASH_HOLD_MS = intPreferencesKey("dev_lab_splash_hold_ms")

        /**
         * 「撤回隐私同意 → 确认退出」按钮的转圈 + 0.2 秒延迟动画（默认**开**）。
         * 本键是该项的**回退开关**：关掉后确认立即退出（= 改造前的旧行为）。
         */
        val DEV_REVOKE_EXIT_SPINNER = booleanPreferencesKey("dev_revoke_exit_spinner")

        /**
         * 注册页昵称检测的转圈时长（毫秒，默认 [DEFAULT_NICKNAME_CHECK_MS] = 200）。
         * 原为写死的 1000ms，本轮默认收紧到 200ms 并允许在开发者选项里调回。
         */
        val DEV_NICKNAME_CHECK_MS = intPreferencesKey("dev_nickname_check_ms")

        /**
         * 「随机浮动」**首次勾选**时的介绍弹窗是否已经看过（默认 false）。
         * 只用于「第一次弹 AlertDialog、之后改用工具提示」这一条交互。
         */
        val SPLASH_JITTER_INTRO_SHOWN = booleanPreferencesKey("splash_jitter_intro_shown")

        /**
         * 「检查更新」在应用内打开（默认**关** = 走系统浏览器，既有行为）。
         * 开启后「关于 → 检查更新」改为进「官网」内嵌网页打开 Releases 页。
         */
        val DEV_CHECK_UPDATE_IN_APP = booleanPreferencesKey("dev_check_update_in_app")

        /**
         * 「检查更新」按钮的**行为**（本轮需求 2）。
         *
         * 取值为 [CheckUpdateTarget] 的 `value` 字符串；缺省 = `apk`（云端安装包页），
         * 这是本轮新增的默认行为——正式版安装包托管在云存储上，比跳 GitHub 更适合国内网络。
         */
        val CHECK_UPDATE_TARGET = stringPreferencesKey("check_update_target")

        /** 「更新日志」入口的位置（`inside` = 下载页底部 / `outside` = 关于页）。 */
        val CHANGELOG_ENTRY_PLACEMENT = stringPreferencesKey("changelog_entry_placement")

        // ---- 开屏密码（2026-10-25 需求：设置 → 安全 → 开屏密码）----
        //
        // 手势为自绘 3x3 图案；存的是 SHA-256(盐 + 图案序列) 的十六进制串，
        // **不存明文图案**。指纹走 androidx.biometric 的 BiometricPrompt，
         // 不在这里持久化任何生物特征（系统侧保管）。

        /** 开屏密码总开关（默认关）。 */
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")

        /** 手势图案哈希；空串 / 缺省 = 尚未设置手势。 */
        val APP_LOCK_PATTERN_HASH = stringPreferencesKey("app_lock_pattern_hash")

        /** 「指纹解锁」开关（默认关；需设备支持 BiometricPrompt 才可开）。 */
        val APP_LOCK_BIOMETRIC_ENABLED = booleanPreferencesKey("app_lock_biometric_enabled")

        /**
         * 手势哈希的**盐**（首次设置手势时随机生成一次并保存）。
         * 每台安装各自不同，防止「已知本应用哈希算法的人」预计算对照表
         * 反推常见图案；盐与哈希同存 DataStore（限制见 `ui/lock/AppLockCore.kt` 的 KDoc）。
         */
        val APP_LOCK_SALT = stringPreferencesKey("app_lock_salt")

        /**
         * 解锁前先播一层 Hello World 开屏（默认**开**）。
         *
         * 口径与「实验室功能开屏」一致：全屏 `background` 底 + `primary` 色文字，
         * 停留后淡出，**只在锁屏页出现前播一次**（进进程一次）。
         * 之所以默认开：用户 2026-10-26 的要求就是「开屏先显示 Hello World，再弹密码」。
         */
        val APP_LOCK_HELLO_SPLASH = booleanPreferencesKey("app_lock_hello_splash")

        /**
         * 手势输错时的强反馈（震动 + 提示文案变红，默认**开**）。
         *
         * 关掉后只剩一句普通颜色的"手势不正确"，不震、不红。
         * 震动走系统 `Vibrator`，需要 `android.permission.VIBRATE`（普通权限，不弹窗）。
         */
        val APP_LOCK_ERROR_FEEDBACK = booleanPreferencesKey("app_lock_error_feedback")

        /**
         * 重新锁定的时机（2026-10-27 需求；缺省 = [AppLockRelock.COLD_START]，即改造前行为）。
         *
         * 存的是 `AppLockRelock.key` 字符串而不是序号：将来增删枚举项时，
         * 老用户的设置不会因为序号位移而变成另一个档位。
         */
        val APP_LOCK_RELOCK = stringPreferencesKey("app_lock_relock")

        /**
         * 宽限档（1 小时 / 今天内不再锁）的「解锁时刻 + 档位」凭据，见 `ui/lock/AppLockGrace`。
         *
         * 空串 = 没有可用凭据。`AppLockGate` 冷启动时读它来决定能否跳过本次验证；
         * 非宽限档或非当前档位的旧值一律作废，解码逻辑不放在仓库里是为了让
         * data 层不反向依赖 ui 层。
         */
        val APP_LOCK_GRACE = stringPreferencesKey("app_lock_grace")

        /** 指纹弹窗的**标题**文案（空 = 用 [DEFAULT_APP_LOCK_BIO_TITLE]）。 */
        val APP_LOCK_BIO_TITLE = stringPreferencesKey("app_lock_bio_title")

        /** 指纹弹窗的**副标题**文案（空 = 用 [DEFAULT_APP_LOCK_BIO_SUBTITLE]）。 */
        val APP_LOCK_BIO_SUBTITLE = stringPreferencesKey("app_lock_bio_subtitle")

        /**
         * 指纹弹窗「取消」按钮文案（空 = 用 [DEFAULT_APP_LOCK_BIO_NEGATIVE]）。
         *
         * v1.1.92 起默认文案改为「使用密码」（用户口径：统一叫"密码"，即使实际回到手势）。
         */
        val APP_LOCK_BIO_NEGATIVE = stringPreferencesKey("app_lock_bio_negative")

        /**
         * 指纹弹窗**副标题下方那行说明**（`PromptInfo.setDescription`，仅 API 30+ 显示）。
         *
         * 与副标题一样支持"显式清空"：键不存在 → 用默认值；键存在且为空 → 不显示。
         */
        val APP_LOCK_BIO_DESCRIPTION = stringPreferencesKey("app_lock_bio_description")

        /**
         * 热搜页的**自定义接口地址**（2026-10-22 热搜设置页）。
         *
         * 空 / 缺省 = 用内置的 `DEFAULT_API_URL`。用户改成自己的云函数地址后，
         * 刷新与手动抓取都走新地址；「恢复默认」= 删除这个键。
         */
        val HOT_SEARCH_API_URL = stringPreferencesKey("hot_search_api_url")
        /**
         * 「关于 → 支持作者」要打开的链接（2026-10-29）。
         *
         * 空 / 缺省 = 用内置默认值（作者的 B 站主页）；可在「开发者选项 → 工具与测试」
         * 里改成一事一议的地址（例如某次赞助活动的页面）。
         */
        val SUPPORT_AUTHOR_URL = stringPreferencesKey("support_author_url")
        /**
         * 「登录后引导设置头像」是否已经提示过（2026-10-29）。
         *
         * false / 缺省 = 本次登录还没问过 → 已登录且仍是灰色头像时弹一次引导。
         * 退出登录（[setLoggedOut]）会把它清掉，下次登录仍可再引导一次。
         */
        val AVATAR_PROMPT_SHOWN = booleanPreferencesKey("avatar_prompt_shown")
        val TIMER_SOUND_TITLE = stringPreferencesKey("timer_sound_title")
        val TIMER_RECENT_DURATIONS = stringPreferencesKey("timer_recent_durations")
        val LOGGED_IN = booleanPreferencesKey("auth_logged_in")
        val PHONE = stringPreferencesKey("auth_phone")
        val LOGIN_MODE = stringPreferencesKey("auth_login_mode")
        // 注册时填写的资料。三者与登录态同属 `auth_` 前缀，退出登录时一并清除。
        val USER_ID = stringPreferencesKey("auth_user_id")
        val NICKNAME = stringPreferencesKey("auth_nickname")
        val GENDER = stringPreferencesKey("auth_gender")
        val PRIVACY_AGREED = booleanPreferencesKey("privacy_agreed")
        val AVATAR_PATH = stringPreferencesKey("avatar_path")
        // 开发者选项（实验室 → 开发者选项）。
        // 设计上仿手机开发者选项：一个总开关 + 各子项开关，总开关关掉时子项一律不生效。
        val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")

        /**
         * 「链接打开方式」全局开关（开发者选项 → 工具与测试）。
         * 默认 false = 交系统默认浏览器，与改造前行为一致。
         * 实际读取走 `util.LinkOpenGate` 的进程级缓存，见那个文件的 KDoc。
         */
        val LINK_OPEN_IN_APP = booleanPreferencesKey("link_open_in_app")

        /**
         * 「音乐 → 后台播放」开关（2026-11-08 需求）。
         *
         * 默认 **true**：用户明确要求做后台播放，做到之后默认就是"离开页面继续听"，
         * 与任何一个音乐 App 的预期一致。关掉则退回上一版的"页内播放、离页即停"，
         * 给不想让它在后台占着通知栏 / 耗电的用户一个出口。
         */
        val MUSIC_BACKGROUND_PLAYBACK = booleanPreferencesKey("music_background_playback")
        /**
         * 实验室条目的自定义名称（2026-11-09 需求 3）。
         *
         * 存一条 JSON：`{"weather":"看天气","de_ai":"改文案"}`，键是条目 key
         * （与 `lab_grid_order` 里用的是同一套 key）。**只存被改过名的**，
         * 没改过的条目不在表里，读的时候回落到内置标题。
         */
        val LAB_CUSTOM_NAMES = stringPreferencesKey("lab_custom_names")

        /** 歌词逐字高亮（KTV 效果），默认开（2026-11-09 需求 5）。 */
        val MUSIC_LYRIC_KARAOKE = booleanPreferencesKey("music_lyric_karaoke")

        /** 桌面歌词悬浮窗，默认关（要在别处看，属于主动开启的功能）。 */
        val MUSIC_DESKTOP_LYRIC = booleanPreferencesKey("music_desktop_lyric")

        /**
         * 音乐页「温馨提示」是否已被勾选「不再提示」（2026-11-11 需求 6）。
         *
         * 默认 false = 每次进音乐页都弹一次，直到用户勾了「不再提示」。
         */
        val MUSIC_UI_TIP_DISMISSED = booleanPreferencesKey("music_ui_tip_dismissed")

        /** B站进入提示「不再提示」标记，语义同音乐页。 */
        val BILI_UI_TIP_DISMISSED = booleanPreferencesKey("bili_ui_tip_dismissed")

        /**
         * 上一次**已经看过更新介绍弹窗**的版本号（2026-11-14 需求 1）。
         *
         * 空串 = 从没记过（全新安装），此时不弹介绍，只把当前版本写进来；
         * 与当前 `BuildConfig.VERSION_NAME` 不一致 = 刚更新过，启动后弹一次。
         */
        val LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
        val DEV_CODE_TEST_ENABLED = booleanPreferencesKey("dev_code_test_enabled")
        val DEV_PERF_TEST_ENABLED = booleanPreferencesKey("dev_perf_test_enabled")

        /**
         * 「性能测试 → 左右滑动动画」开关（本轮需求 11a）。
         *
         * 门控的是**一级入口切换时新页面滑入动画**（`MoreCheckApp` 内容层的
         * `Animatable` 位移 + 底栏点击的同款动画）。默认**开**（= 与改造前一致）。
         */
        val DEV_TAB_SLIDE_ANIM = booleanPreferencesKey("dev_tab_slide_anim")

        /**
         * 「图标测试 → 贪吃蛇标题栏显示」开关（本轮需求 11b）。
         *
         * 门控贪吃蛇页顶部的 `TopAppBar`。默认**开**（= 与改造前一致）。
         * 关掉后整页只有 WebView 游戏本体（见 `SnakeScreen` 的处理）。
         */
        val DEV_SNAKE_TITLE_BAR = booleanPreferencesKey("dev_snake_title_bar")

        /**
         * 「游戏开屏显示」开关（本轮需求 23：贪吃蛇与数独的 Hello World
         * 启动动画可在开发者选项设置）。
         *
         * 门控两个网页游戏页（`SnakeScreen` / `SudokuScreen`）进入时那层
         * 整屏 `Hello World` 开屏层。默认**开**（= 与改造前一致）。
         *
         * 为什么一个开关管两个游戏页，而不是各一个：需求 23 原文把两者并列在
         * 同一句里（「贪吃蛇与数独的 helloworld 启动动画」），且两页的开屏层
         * 本来就是同一套视觉（同文案、同字号、同动效、同时长，见两个 Screen 的 KDoc）。
         * 分成两个开关会让用户为了同一件事拨两次。
         *
         * 与 [DEV_SNAKE_TITLE_BAR] 是**两个独立的开关**：标题栏与开屏层
         * 是两个可以单独关掉的东西（有人想留标题栏但跳过开屏，反之亦然）。
         */
        val DEV_GAME_SPLASH = booleanPreferencesKey("dev_game_splash")

        /**
         * 「游戏标题栏显示」开关（本轮需求 23 扩展）。
         *
         * 门控两个网页游戏页顶部的 `TopAppBar`。与 [DEV_SNAKE_TITLE_BAR]
         * 的关系：后者是需求 11b 为贪吃蛇单独加的，本轮加数独时不能让它
         * 只影响一个游戏——否则会出现「关掉标题栏，贪吃蛇没了、数独还在」的
         * 割裂。因此本键是**两个游戏页共用**的新开关，
         * 而 [DEV_SNAKE_TITLE_BAR] 保留为贪吃蛇的**单独**覆盖。
         *
         * 合成规则见 `DeveloperOptionsRepository.gameTitleBarEnabled`：
         * 数独页读本键；贪吃蛇页读 `snakeTitleBarEnabled`（它只认旧键，
         * 保证需求 11b 的行为一个字都没变）。
         */
        val DEV_GAME_TITLE_BAR = booleanPreferencesKey("dev_game_title_bar")

        /**
         * 「游戏设置」总开关（本轮需求 3）。
         *
         * 它是「游戏标题栏显示」（[DEV_GAME_TITLE_BAR]）与「游戏开屏显示」两批子项的
         * **上一级**总门：关掉它，下面所有游戏子开关一律回落"与改造前一致"的默认行为
         * （标题栏显示、开屏显示），但各子开关**保存的值不清空**——重新打开总开关后按原样恢复
         * （与本页其它受门控子项的既定口径完全一致）。
         *
         * 默认**开**（= 与改造前一致：游戏子设置默认生效）。
         */
        val DEV_GAME_SETTINGS_ENABLED = booleanPreferencesKey("dev_game_settings_enabled")

        /**
         * 「贪吃蛇开屏显示」开关（本轮需求 2：游戏开屏改为可分别设置）。
         *
         * 默认**开**（= 与改造前一致：贪吃蛇进入时显示 Hello World）。
         *
         * 与 [DEV_GAME_SPLASH]（旧的「一个开关管两页」）的关系：那个键在本轮之后
         * **没有任何代码再读它**，数据仍留在 DataStore 里（不清空：保留历史值，
         * 将来若做数据导出仍可追溯）。选择新增两个键而不是复用旧键的理由见
         * `DeveloperOptionsScreen.GameSplashScreen` 的 KDoc。
         */
        val DEV_GAME_SPLASH_SNAKE = booleanPreferencesKey("dev_game_splash_snake")

        /**
         * 「数独开屏显示」开关（本轮需求 2，与 [DEV_GAME_SPLASH_SNAKE] 同源）。
         *
         * 默认**开**（= 与改造前一致）。
         */
        val DEV_GAME_SPLASH_SUDOKU = booleanPreferencesKey("dev_game_splash_sudoku")

        /**
         * 「贪吃蛇标题栏显示」开关（本轮需求 5：标题栏改为可分别设置）。
         *
         * 默认**开**（= 与改造前一致：贪吃蛇显示标题栏）。
         *
         * 与 [DEV_GAME_TITLE_BAR]（总开关）的关系：与开屏那对键同一条演进路径——
         * 总开关保留（关掉它 = 两页都不显示标题栏），本键是贪吃蛇的**单独**子开关，
         * 生效规则见 `DeveloperOptionsRepository.gameTitleBarSnakeEnabled`。
         */
        val DEV_GAME_TITLE_BAR_SNAKE = booleanPreferencesKey("dev_game_title_bar_snake")

        /**
         * 「数独标题栏显示」开关（本轮需求 5，与 [DEV_GAME_TITLE_BAR_SNAKE] 同源）。
         *
         * 默认**开**（= 与改造前一致）。
         */
        val DEV_GAME_TITLE_BAR_SUDOKU = booleanPreferencesKey("dev_game_title_bar_sudoku")

        /**
         * 「数独2 标题栏显示」开关（2026-10-22 补）。
         *
         * ## 为什么现在才补
         *
         * 「游戏标题栏显示」从开关改成二级页入口后，二级页里只放了贪吃蛇与数独两个子开关；
         * 数独2 仍读两页共用的 [DEV_GAME_TITLE_BAR]，于是用户**没有任何入口**能把
         * 数独2 的标题栏关掉（表现就是"数独2 标题栏无法关闭"）。
         * 补一个与另两个同构的子开关即修复。
         *
         * 默认**开**（= 与改造前一致）。
         */
        val DEV_GAME_TITLE_BAR_SUDOKU2 = booleanPreferencesKey("dev_game_title_bar_sudoku2")

        /**
         * 「游戏标题栏 / 状态栏颜色」三档模式（本轮需求 3）。
         *
         * 存的是 `com.morecheck.ui.lab.GameChromeMode.value` 那种**稳定字符串**
         * （`system` / `dark_classic` / `dark_adaptive`），不是枚举序号——理由见
         * 那个枚举的 KDoc：将来调整声明顺序时序号会整体错位。
         *
         * 默认 `dark_classic`：它等于改造后的既有观感（标题栏与固定深色的游戏区同色、
         * 系统栏浅色图标）。**不能**默认 `system`——浅色主题下那会变成"浅色标题栏 +
         * 深色游戏区"的分界观感，等于让没配过的用户升级后被改掉观感。
         *
         * 它作用的是**两个**网页游戏页（贪吃蛇 / 数独），将来番茄时钟也读同一个值：
         * 这三页共享同一个"游戏 chrome"概念，分成三个键只会让用户为同一件事拨三次。
         */
        val DEV_GAME_CHROME_MODE = stringPreferencesKey("dev_game_chrome_mode")

        /**
         * 实验室页背景模式（本轮需求 11b：「开发者选项背景设置选项」）。
         *
         * 存稳定字符串而不是枚举序号（理由同 `DEV_ICON_STYLE`）：
         * `random`（默认，按深浅色分池轮换）/ `classic`（跟随系统深浅色的纯色）/
         * `custom`（用户自选的固定颜色，色值存 [DEV_LAB_BG_CUSTOM_COLOR]）。
         */
        val DEV_LAB_BG_MODE = stringPreferencesKey("dev_lab_bg_mode")

        /**
         * `custom` 模式下用户挑选的背景色（ARGB 整数的十进制字符串）。
         *
         * DataStore 没有 Int 首选类型之外的便捷方案时，最常见的做法就是存字符串再解析；
         * 解析失败（脏值）由读取侧回落到 `classic`，不抛异常。
         */
        val DEV_LAB_BG_CUSTOM_COLOR = stringPreferencesKey("dev_lab_bg_custom_color")

        /**
         * ⚠️ 已删除：`dev_icon_test_enabled`（原「图标测试」占位开关）。
         *
         * 需求本轮明确「原占位开关删掉」，该开关没有任何真实作用，
         * 因此连键一起删掉，避免留下一个「看起来还能配、但没人读」的僵尸设置。
         * DataStore 文件里可能残留旧值——没有任何代码读它，故不需要迁移。
         */

        /**
         * 「图标更改」当前选中的**内置**图标套（存 alias 短名，见 `data/AppIconRepository`）。
         *
         * 为什么存字符串而不是存枚举序号 / 存一个 Boolean：
         * - **序号**会随枚举增删而错位，老用户升级后可能被指到另一套图标（隐性数据损坏）；
         * - **Boolean** 表达不了「三套以上」这件事，而需求要的就是多个可选套。
         * 存稳定短名（`default` / `blue` / `purple` / `orange`）后，增删套数都不会影响已有取值。
         *
         * 不存/无法识别的值一律回落到默认套，见 `AppIconRepository` 的 `iconBackgroundResOf`。
         */
        val DEV_ICON_STYLE = stringPreferencesKey("dev_icon_style")

        /**
         * 「图标更改 → 自定义上传图片」在应用私有目录里的绝对路径。
         *
         * 与 `AVATAR_PATH` 同一套路：DataStore 只存**路径**，图片本体写在
         * `filesDir/app_icon/custom_icon.jpg`（见同包的 `AppIconRepository.AppIconStore`）。
         *
         * ⚠️ 这张图**只用于应用内显示，永远不会成为桌面图标**——Android 的 manifest 图标
         * 必须是资源引用，运行期无法被换成用户上传的图片。这条限制在需求里已与用户确认过。
         * 传 null / 空串会**移除**该键，让「没有自定义图标」只有一个表示法。
         */
        val DEV_CUSTOM_ICON_PATH = stringPreferencesKey("dev_custom_icon_path")

        /**
         * 开屏「Hello World」的**额外停留时长**（毫秒），由「性能测试」里的滑块控制。
         *
         * ## 默认值 = 改造前的既有行为
         *
         * 取 [SettingsRepository.DEFAULT_SPLASH_HOLD_MS]，它等于
         * `ui/MoreCheckApp.kt` 里既有的 `SPLASH_DURATION_MS = 900L`。
         * 刻意**不**凭空拍一个数：改造前开屏就是固定停 900ms，
         * 新键在「用户从没调过滑块」时必须让开屏**还是 900ms**，
         * 否则等于顺手改掉了所有人都熟悉的首启观感。
         * 「恢复默认」按钮写回的就是这个常量。
         *
         * ## 为什么用 Int 而不是 Long
         *
         * DataStore 只有 `intPreferencesKey` / `longPreferencesKey` 等有限类型，
         * `Int` 足够表达 0~10000ms（上限 10 秒），且与界面层的滑块取值域一致，
         * 少一次 Long↔Int 转换就少一处溢出/取整的机会。
         *
         * 取值范围由使用方夹取（见 `SplashGate` 调用处 coerceIn），
         * 仓库层**不做**夹取也不抛异常：读到脏数据（比如手工改过 DataStore）时
         * 回落到默认值比崩溃更符合这个应用的一贯口径。
         */
        val SPLASH_HOLD_MS = intPreferencesKey("splash_hold_ms")

        /**
         * 开屏「Hello World」时长的**随机浮动范围**（毫秒，需求 12）**已删除**。
         *
         * 2026-10-23 需求 8 把它换成了下面的布尔开关（固定 ±0.3 秒）。
         * 旧的 `splash_jitter_ms` 值若已写入用户磁盘会成为孤儿数据，DataStore 允许这样；
         * 真要回滚时按键名直接取原始 `intPreferencesKey` 即可，无需保留类型化入口。
         */
        //
        // （原 `SPLASH_JITTER_MS` 键定义、连同 MAX_SPLASH_JITTER_MS / SPLASH_JITTER_STEP_MS
        //  与 `splashJitterMs` / `setSplashJitterMs` 读写入口，已整条删除。）

        /**
         * 「随机浮动」勾选框（2026-10-23 需求 8）。**默认 false** = 不浮动（与改造前一致）。
         *
         * 语义：勾上后每次开屏时长 = [SPLASH_HOLD_MS] **± 0.3 秒**（上下都抖）。
         * 它取代了上一版那条「浮动范围」滑块（键名 `splash_jitter_ms`）：那个键的语义是
         * "只往上浮动 0..N"，用户这一轮要求改成固定 ±0.3 秒的开关，因此新开一个布尔键，
         * 旧键的数据保留但不再读取（回滚时还能用）。
         */
        val SPLASH_JITTER_ENABLED = booleanPreferencesKey("splash_jitter_enabled")

        //
        // ---- 开屏显示文字（2026-10-27 需求：可自定义文字 / 字号 / 字体 / 单多行）----
        //
        // 四个键共同构成一份 [SplashTextStyle]，四处开屏（应用启动 / 实验室页面 /
        // 锁屏解锁前 / 三个网页游戏）共用同一份设置。默认值一律等于改造前的观感：
        // 文案 `Hello World`、字号 34sp、系统默认字体、单行。

        /** 开屏显示的文字（默认 `Hello World`）。空串在写入时会被拒（回落默认）。 */
        val DEV_SPLASH_TEXT = stringPreferencesKey("dev_splash_text")

        /** 开屏字号（sp，默认 34，范围见 [SplashTextStyle.MIN_SIZE_SP] / [MAX_SIZE_SP]）。 */
        val DEV_SPLASH_TEXT_SIZE = intPreferencesKey("dev_splash_text_size")

        /** 开屏字体档位（存 [SplashTextFont.id] 字符串，默认 `default`）。 */
        val DEV_SPLASH_TEXT_FONT = stringPreferencesKey("dev_splash_text_font")

        /**
         * 是否多行显示（默认 **false** = 单行，与改造前一致）。
         *
         * 单行时换行符按空格处理、且不自动换行（超出部分省略）；
         * 多行时换行符生效、长文本自动折行。
         */
        val DEV_SPLASH_TEXT_MULTILINE = booleanPreferencesKey("dev_splash_text_multiline")

        /**
         * 「实验室」宫格的功能顺序（v1.1.69 需求：宫格支持长按排序 / 置顶）。
         *
         * 存成**逗号分隔的条目 key 串**（`weather,notebook,agent,...`），与
         * [TIMER_RECENT_DURATIONS] 同一套路，理由也一样：DataStore 的 `stringSet`
         * **无序**，而"顺序"正是这个键要表达的东西。
         *
         * ## 为什么存 key 而不是存下标
         *
         * 存下标（如 `"2,0,1,3"`）在**以后往宫格中间插入新功能**时会全体错位——
         * 用户升级后会发现自己的排序被莫名打乱，甚至某个功能被"顶"到不存在的位置。
         * 存稳定 key 后，新增功能只需追加到列表尾部（见 [labGridOrder] 的合并逻辑），
         * 已有顺序完全不受影响。这与 `BUILT_IN_ICON_IDS` 存短名而非序号的取舍同源。
         */
        val LAB_GRID_ORDER = stringPreferencesKey("lab_grid_order")

        /**
         * 已成功请求固定到桌面的实验室快捷方式 key 集合（v1.1.72 后续轮需求：
         * 「添加到桌面已有提示框，不再二次询问」）。
         *
         * 用 **stringSet**（不像 [LAB_GRID_ORDER] 那样存逗号串）：这里只表达
         * 「在不在」，没有顺序语义——集合天然去重，读写都不必做分割与合并。
         * key 与 [LAB_GRID_ORDER] 同源（`shortcutKeyOf` 的稳定标识），
         * 因此宫格增删条目不会让已记录的集合错位。
         */
        val LAB_PINNED_SHORTCUTS = stringSetPreferencesKey("lab_pinned_shortcuts")

        // ---- 「界面测试」分区（v1.1.74 需求 8）----------------------------
        //
        // 四项都是**全局界面行为**，不属于实验室也不属于图标，因此单独一个分区。
        // 键名前缀沿用 `dev_`：它们都由开发者选项总开关门控，与上面那批同族。
        //
        // 三个开关一律**默认 true**，理由与本文件里既有那批一致：
        // 默认值必须是"用户从没调过这个新开关时也看不出变化"的那一档，
        // 否则等于用"新增一个设置项"悄悄改掉了所有人熟悉的观感。

        /**
         * 「打卡完成动画」开关。
         *
         * 门控主页打卡后的彩带动画（`ui/common/Confetti.kt` 的 `ConfettiEffect`）。
         *
         * **默认 true = 与改造前完全一致**：改造前打卡就撒花。
         */
        val DEV_CHECKIN_CELEBRATION = booleanPreferencesKey("dev_checkin_celebration")

        /**
         * 「页面转场动画」开关。
         *
         * 门控应用内页面推入 / 弹出的转场（`TabNavHost` 里的 `pushEnter` / `pushExit` /
         * `popExit` / `popEnter`，以及贪吃蛇与番茄钟那条纵向的
         * `snakeEnter` / `snakePopExit`）。
         *
         * **默认 true = 与改造前完全一致**。
         */
        val DEV_PAGE_TRANSITION_ANIM = booleanPreferencesKey("dev_page_transition_anim")

        /**
         * 「按压反馈」开关。
         *
         * 门控 `ui/common/PressScale.kt` 那个**全应用统一**的按压缩放反馈
         * （v1.1.72 起所有可点元素都接了它，本项目共 40 余处调用点）。
         *
         * **默认 true = 与改造前完全一致**。
         */
        val DEV_PRESS_FEEDBACK = booleanPreferencesKey("dev_press_feedback")

        /**
         * 「退出音乐时二次确认」开关（2026-11-09 需求）。
         *
         * 门控音乐页离开（返回箭头 / 系统返回）时是否弹一个确认框。
         * **默认 false**：多加一次确认是"更慢"的体验，只有明确想要的人才打开它；
         * 默认 true 会让所有人每次退出都多点一下。
         */
        val DEV_MUSIC_EXIT_CONFIRM = booleanPreferencesKey("dev_music_exit_confirm")

        /**
         * 「音乐入口显示实时歌词」开关（2026-11-09 需求）。
         *
         * 门控音乐主页"正在播放"主卡里那一行跟随进度的歌词。
         * **默认 true**：这是本版权衡后的正式观感（主卡本来就有一行副标题，
         * 换成实时歌词信息量更大），留成开关只是给"不想在主卡看到歌词"的人一个出口。
         */
        val DEV_MUSIC_ENTRY_LYRICS = booleanPreferencesKey("dev_music_entry_lyrics")

        /**
         * 「发现页官网位」显示模式（2026-11-14）：false = 官网（默认 www.scif.top），
         * true = 搜一搜（放大镜图标 + 百度首页）。这是内容配置，不受总开关门控。
         */
        val DEV_DISCOVER_SEARCH_MODE = booleanPreferencesKey("dev_discover_search_mode")

        /** 被逐页关闭「实验室开屏」的 routeKey 集合（逗号分隔；空 = 全部按总开关）。 */
        val DEV_LAB_SPLASH_DISABLED_PAGES =
            stringPreferencesKey("dev_lab_splash_disabled_pages")

        /** 「发现页 ↔ 实验室」入口归属覆盖表，格式见 `EntryCatalog.encodeEntryPlacement`。 */
        val ENTRY_PLACEMENT_OVERRIDES = stringPreferencesKey("entry_placement_overrides")

        /**
         * 「日期显示格式」——存**稳定字符串**，**绝不存枚举 ordinal**。
         *
         * 取值见 [DateDisplayFormat]（`cn_full` / `iso` / `cn_short`）。
         *
         * 为什么必须是字符串而不是序号：枚举增删会让序号整体错位，老用户升级后
         * "原来选的是第 0 项"变成"现在指第 2 项"，而且**不报任何错**，
         * 只是日期悄悄变了格式——典型的隐性数据损坏。这与 `DEV_ICON_STYLE` /
         * `LAB_GRID_ORDER` / `DEV_GAME_CHROME_MODE` 那批键的取舍完全同源。
         *
         * 默认 [DateDisplayFormat.DEFAULT_ID]（`cn_full`）= 改造前观感。
         */
        val DEV_DATE_FORMAT = stringPreferencesKey("dev_date_format")

        /**
         * 「课程提醒」总开关（v1.1.79 需求 2）。默认**关**。
         *
         * 默认关的理由：提醒会**主动发通知**（可能响铃），属于"不能替用户决定"的行为。
         * 新增功能默认开启只在"默认值 = 改造前的既有行为"时才成立，
         * 而提醒是全新功能，此前不存在，因此默认关、由用户在课程表页自己打开。
         */
        val TIMETABLE_REMINDER_ENABLED = booleanPreferencesKey("timetable_reminder_enabled")

        // ---- 打卡提醒增强（v5：静默时段 / 漏打卡补推）--------------------------
        //
        // 存「HH:mm」字符串而不是分钟数：与 CheckTask.reminderTime 同一口径，
        // 时区切换时按设备本地时间解释，不需要额外记录偏移量。

        /** 夜间静默时段总开关，默认关（新增行为不能替用户决定）。 */
        val REMINDER_QUIET_ENABLED = booleanPreferencesKey("reminder_quiet_enabled")
        val REMINDER_QUIET_START = stringPreferencesKey("reminder_quiet_start")
        val REMINDER_QUIET_END = stringPreferencesKey("reminder_quiet_end")

        /** 漏打卡后补推一次；默认关，分钟数存字符串便于加档位。 */
        val REMINDER_FOLLOWUP_ENABLED = booleanPreferencesKey("reminder_followup_enabled")
        val REMINDER_FOLLOWUP_MINUTES = stringPreferencesKey("reminder_followup_minutes")

        /**
         * 「课程提醒」提前量（v1.1.79 需求 2）：存**稳定字符串** `0` / `10` / `60`（分钟）。
         *
         * 与 [DEV_DATE_FORMAT] 同一条纪律：存字符串而不是 ordinal，
         * 将来增删档位不会让老用户的选择错位。默认 `10`（10 分钟前），
         * 这是三档里最通用的一个——"现在开始"只剩 0 秒反应时间，
         * "1 小时前"对临时调整作息的人又太早。
         */
        val TIMETABLE_REMINDER_LEAD = stringPreferencesKey("timetable_reminder_lead")
    }

    /**
     * 「日期显示格式」的三个合法取值（v1.1.74 需求 8 第 3 项）。
     *
     * ## 为什么默认是 [CN_FULL]
     *
     * 改造前 `com.morecheck.util.DateFormatters.fullLabel()` 就是
     * `yyyy年M月d日 周一` 这一档，而「新增设置项的默认值必须等于改造前行为」是
     * 本项目的硬纪律——用户从没调过时必须让观感**一模一样**。
     *
     * ## 为什么这个枚举放 `data` 层、而不是 `ui` 层
     *
     * 它是**被持久化的数据**，与主题模式、实验室背景模式那一批同族；
     * 而"某一档在界面上怎么描述"是文案，留在调用处。
     * 这样数据层不认识界面层，解析规则也只有一处（[parse]）。
     *
     * ## 三个档位都带星期
     *
     * 改造前 `fullLabel()` 是「日期 + 星期」拼在一起的（见 `DateFormatters`）。
     * 三档都保留星期，用户切换格式时变化只在日期那一段，不会出现
     * "换了格式之后星期突然没了"这种看起来像 bug 的结果。
     */
    enum class DateDisplayFormat(val id: String) {
        /** `2026年10月19日 周一` —— 中文完整日期。**默认**，= 改造前观感。 */
        CN_FULL("cn_full"),

        /** `2026-10-19 周一` —— ISO 横杠式。 */
        ISO("iso"),

        /** `10月19日 周一` —— 中文省略年份。 */
        CN_SHORT("cn_short");

        companion object {
            /** 默认档。= 改造前 `fullLabel()` 的观感，见本枚举 KDoc。 */
            const val DEFAULT_ID: String = "cn_full"

            /**
             * 按 id 取档位；**无法识别时回落 [CN_FULL] 而不是抛异常**。
             *
             * 这是本项目处理脏值的统一口径：读到脏数据（手工改过 DataStore、
             * 或将来枚举改名）时回落到默认值，比让设置页崩掉更符合这个应用
             * 的一贯做法。
             */
            fun parse(id: String?): DateDisplayFormat =
                entries.firstOrNull { it.id == id } ?: CN_FULL
        }
    }

    /**
     * 原始 DataStore 数据流。仅用于让同层的仓库（如 [AuthRepository]）
     * 复用同一个 DataStore 实例，避免同一文件被打开两次。
     */
    internal val rawData: Flow<Preferences> get() = context.dataStore.data

    val defaultTaskSeeded: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEFAULT_TASK_SEEDED] ?: false }

    val lastRolloverDate: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_ROLLOVER_DATE] }

    /** 深色模式：light / dark / system。 */
    val themeMode: Flow<String> =
        context.dataStore.data.map { it[Keys.THEME_MODE] ?: ThemeMode.SYSTEM }

    /**
     * 主题色（强调色）标识，取值见 `ui/theme/AccentColor` 的 id。
     * 默认 `green`，即 MoreCheck 一直以来的微信绿。
     *
     * 这里刻意只存字符串、不引用界面层的枚举，避免数据层反向依赖 UI 层；
     * 无法识别的值由界面层回退到默认绿色。
     */
    val themeAccent: Flow<String> =
        context.dataStore.data.map { it[Keys.THEME_ACCENT] ?: DEFAULT_THEME_ACCENT }

    /**
     * 自启动：是否允许应用在**开机后自动恢复每日提醒**。
     *
     * 关闭后开机不再自动重排提醒（提醒要等应用被打开、或每日兜底同步时才会恢复）；
     * 应用在前台运行时的零点换日逻辑不受此开关影响。
     */
    val autoStartOnBoot: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUTO_START_ON_BOOT] ?: true }

    // ---- 登录提示开关（实验室 → 登录提示功能）--------------------------------
    //
    // 门控的是「刚同意隐私政策后弹一次『欢迎使用 MoreCheck / 去登录 / 暂不』」这个引导弹窗
    // （见 MoreCheckApp 里的 LoginPromptDialog）。
    //
    // 默认**开**：这是应用原本就有的行为，加开关只是让用户能关掉它，
    // 若默认关掉，等于悄悄改掉了老用户熟悉的首启流程。

    /** 是否在打开软件时提示登录。默认开。 */
    val loginPromptEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LOGIN_PROMPT_ENABLED] ?: true }

    suspend fun setLoginPromptEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LOGIN_PROMPT_ENABLED] = enabled }
    }

    // ---- 朋友圈红点提示开关（实验室 → 开发者选项 → 图标测试）------------------
    //
    // 只门控「底部导航栏『发现』入口上的未读角标要不要画」（见 MoreCheckApp 的
    // BadgedTabIcon 调用处），**不**门控"未读状态"本身：
    // 进朋友圈清未读那条链路（onMomentsOpened → TabBadgeViewModel.clear）保持原样。
    // 键的默认值与取舍见 Keys.MOMENTS_BADGE_ENABLED 上的说明。

    /** 是否在底部导航栏显示「发现」入口的朋友圈未读红点与数字。默认开。 */
    val momentsBadgeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MOMENTS_BADGE_ENABLED] ?: true }

    suspend fun setMomentsBadgeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MOMENTS_BADGE_ENABLED] = enabled }
    }

    suspend fun setDefaultTaskSeeded() {
        context.dataStore.edit { it[Keys.DEFAULT_TASK_SEEDED] = true }
    }

    suspend fun setLastRolloverDate(date: String) {
        context.dataStore.edit { it[Keys.LAST_ROLLOVER_DATE] = date }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    suspend fun setThemeAccent(accentId: String) {
        context.dataStore.edit { it[Keys.THEME_ACCENT] = accentId }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START_ON_BOOT] = enabled }
    }

    // ---- 权限管理开关（设置 → 通用 → 权限管理）--------------------------------
    //
    // 这里只存**用户意图**（默认全关），不反映系统实际授权状态：
    // 系统权限用户随时可能在系统设置里改动，而应用收不到通知。
    // 因此「能不能真的用」一律判定为 `开关打开 && AppPermissions.isGranted()`。
    //
    // id 取值见 `util/AppPermissions.kt` 的 `AppPermission.id`，两边必须一致。

    val cameraEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PERM_CAMERA] ?: false }

    val locationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PERM_LOCATION] ?: false }

    val notificationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PERM_NOTIFICATION] ?: false }

    suspend fun setPermissionEnabled(id: String, enabled: Boolean) {
        val key = permissionKeyOf(id) ?: return
        context.dataStore.edit { it[key] = enabled }
    }

    /**
     * 一次性读取某个权限开关。
     *
     * 给 `BroadcastReceiver` / `Worker` 这类没有协程作用域、又需要立即决策的地方用
     * （`ReminderReceiver` 决定要不要弹通知时就靠它）。
     */
    suspend fun isPermissionEnabled(id: String): Boolean {
        val key = permissionKeyOf(id) ?: return false
        return context.dataStore.data.map { it[key] ?: false }.first()
    }

    private fun permissionKeyOf(id: String) = when (id) {
        "camera" -> Keys.PERM_CAMERA
        "location" -> Keys.PERM_LOCATION
        "notification" -> Keys.PERM_NOTIFICATION
        else -> null
    }

    // ---- 计时器声音（设置 → 通用 → 声音设置）--------------------------------

    /** 倒计时结束时是否响铃。默认开启。 */
    val timerSoundEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TIMER_SOUND_ENABLED] ?: true }

    /** 选中的铃声 URI（字符串形式）；null 表示用系统默认通知音。 */
    val timerSoundUri: Flow<String?> =
        context.dataStore.data.map { it[Keys.TIMER_SOUND_URI] }

    // ---- 样式设置（2026-10-22 需求 8）-------------------------------------
    //
    // 与「界面测试」那批开关同构：`devStyleXxx` 是**原始值**（界面回显用），
    // 生效合成放在 `DeveloperOptionsRepository`（总开关 && 子开关，回落默认值）。
    // 这里只提供读写，不在数据层做门控——数据层不该知道"开发者选项总开关"这件事。

    val devStyleGradientButton: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_STYLE_GRADIENT_BUTTON] ?: true }
    val devStylePillTabBar: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_STYLE_PILL_TABBAR] ?: true }
    val devStyleSolidToday: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_STYLE_SOLID_TODAY] ?: true }
    val devStyleCalendarWeekDefault: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_STYLE_CALENDAR_WEEK_DEFAULT] ?: false }
    val devStyleXiaomiSlider: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_STYLE_XIAOMI_SLIDER] ?: true }

    suspend fun setDevStyleGradientButton(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_STYLE_GRADIENT_BUTTON] = enabled }
    }

    suspend fun setDevStylePillTabBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_STYLE_PILL_TABBAR] = enabled }
    }

    suspend fun setDevStyleSolidToday(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_STYLE_SOLID_TODAY] = enabled }
    }

    suspend fun setDevStyleCalendarWeekDefault(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_STYLE_CALENDAR_WEEK_DEFAULT] = enabled }
    }

    suspend fun setDevStyleXiaomiSlider(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_STYLE_XIAOMI_SLIDER] = enabled }
    }

    // ---- 「页面自定义」（2026-10-24 需求 4）--------------------------------
    //
    // 一个总门 + 四个子开关。原始值读写只有「页面自定义」二级页这一处
    // （生效合成在 DeveloperOptionsRepository，与游戏/界面测试那批同构）。

    /** 「页面自定义」总开关的**原始值**（默认 false）。 */
    val devCustomizeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOMIZE_ENABLED] ?: false }

    val devCustomizeTextStyle: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOMIZE_TEXT_STYLE] ?: true }

    val devCustomizeButtonStyle: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOMIZE_BUTTON_STYLE] ?: true }

    val devCustomizeIconStyle: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOMIZE_ICON_STYLE] ?: true }

    val devCustomizeTextContent: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOMIZE_TEXT_CONTENT] ?: true }

    suspend fun setDevCustomizeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CUSTOMIZE_ENABLED] = enabled }
    }

    suspend fun setDevCustomizeTextStyle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CUSTOMIZE_TEXT_STYLE] = enabled }
    }

    suspend fun setDevCustomizeButtonStyle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CUSTOMIZE_BUTTON_STYLE] = enabled }
    }

    suspend fun setDevCustomizeIconStyle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CUSTOMIZE_ICON_STYLE] = enabled }
    }

    suspend fun setDevCustomizeTextContent(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CUSTOMIZE_TEXT_CONTENT] = enabled }
    }

    /**
     * 热搜页自定义接口地址（2026-10-22）。
     *
     * null = 未自定义，调用方应回落到 `HotSearchScreen.DEFAULT_API_URL`
     * （两者是"用户设置"与"内置默认值"的关系，不在仓库层内联默认值——
     * 那个常量属于热搜功能，塞进通用设置仓库会让它成为跨模块的耦合）。
     */
    val hotSearchApiUrl: Flow<String?> =
        context.dataStore.data.map { it[Keys.HOT_SEARCH_API_URL]?.takeIf { u -> u.isNotBlank() } }

    /** 写入自定义接口地址；传 null / 空串 = 恢复默认。 */
    suspend fun setHotSearchApiUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.HOT_SEARCH_API_URL)
            else prefs[Keys.HOT_SEARCH_API_URL] = url.trim()
        }
    }

    /**
     * 「关于 → 支持作者」要打开的链接（2026-10-29 需求：默认打开作者 B 站主页，
     * 内容可在开发者选项里修改）。
     *
     * null = 未自定义，调用方回落到内置默认地址
     * （`TabNavHost.DEFAULT_SUPPORT_AUTHOR_URL`）——与本文件里 `hotSearchApiUrl`
     * 同一口径：默认值属于功能本身，不塞进通用设置仓库。
     */
    val supportAuthorUrl: Flow<String?> =
        context.dataStore.data.map { it[Keys.SUPPORT_AUTHOR_URL]?.takeIf { u -> u.isNotBlank() } }

    /** 写入「支持作者」链接；传 null / 空串 = 恢复默认。 */
    suspend fun setSupportAuthorUrl(url: String?) {
        context.dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(Keys.SUPPORT_AUTHOR_URL)
            else prefs[Keys.SUPPORT_AUTHOR_URL] = url.trim()
        }
    }

    /** 选中铃声的显示名，仅用于设置页展示（URI 本身不适合给人读）。 */
    val timerSoundTitle: Flow<String?> =
        context.dataStore.data.map { it[Keys.TIMER_SOUND_TITLE] }

    suspend fun setTimerSoundEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TIMER_SOUND_ENABLED] = enabled }
    }

    /**
     * 保存选中的铃声。
     *
     * 只存 URI 字符串 + 显示名，**不复制音频文件**：如果是通过 SAF 导入的，
     * 会先由调用方拿到持久化读权限（`takePersistableUriPermission`），
     * 否则重启后这个 URI 就打不开了。
     */
    suspend fun setTimerSound(uri: String?, title: String?) {
        context.dataStore.edit {
            if (uri == null) {
                it.remove(Keys.TIMER_SOUND_URI)
                it.remove(Keys.TIMER_SOUND_TITLE)
            } else {
                it[Keys.TIMER_SOUND_URI] = uri
                it[Keys.TIMER_SOUND_TITLE] = title.orEmpty()
            }
        }
    }

    // ---- 计时器最近使用的时长 -------------------------------------------------

    /**
     * 最近使用的时长（秒），最新在前，最多 [MAX_RECENT_DURATIONS] 个。
     *
     * 存成一个逗号分隔的字符串而不是 DataStore 的 stringSet：
     * `stringSet` 无序，而这排芯片的语义就是「按使用时间倒序」，顺序必须稳定。
     */
    val recentTimerDurations: Flow<List<Int>> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.TIMER_RECENT_DURATIONS]
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?.take(MAX_RECENT_DURATIONS)
                ?: emptyList()
        }

    /**
     * 记录一次使用过的时长：已存在则提到最前，超出上限的从尾部丢弃。
     *
     * 由仓库层做去重与截断（而不是调用方），保证无论从哪调用都不会写进重复项。
     */
    suspend fun rememberTimerDuration(seconds: Int) {
        if (seconds <= 0) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.TIMER_RECENT_DURATIONS]
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
            val updated = (listOf(seconds) + current.filter { it != seconds })
                .take(MAX_RECENT_DURATIONS)
            prefs[Keys.TIMER_RECENT_DURATIONS] = updated.joinToString(",")
        }
    }

    /** 长按删除某条历史时长。 */
    suspend fun forgetTimerDuration(seconds: Int) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.TIMER_RECENT_DURATIONS]
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?: emptyList()
            val updated = current.filter { it != seconds }
            if (updated.isEmpty()) {
                prefs.remove(Keys.TIMER_RECENT_DURATIONS)
            } else {
                prefs[Keys.TIMER_RECENT_DURATIONS] = updated.joinToString(",")
            }
        }
    }

    // ---- 实验室宫格顺序（v1.1.69）--------------------------------------------

    /**
     * 用户自定义的实验室宫格顺序（条目 key 列表，已置顶的排在最前）。
     *
     * ## 合并口径：以「代码里的静态顺序」为基准，叠加用户的顺序
     *
     * 返回值**不是**直接读盘的结果，而是**合并**后的完整顺序：
     * 1. 先按用户存的顺序取出仍然存在的条目（**过滤掉已被删除的功能 key**）；
     * 2. 再把**代码里有、但用户没存过的**条目按静态顺序追加到尾部。
     *
     * 这样才能同时满足两件事：用户的排序被保留；**将来新增的功能自动出现在宫格里**
     * （否则它永远不会被显示——因为用户存的顺序里没有它，而界面只认存下来的顺序）。
     * 这是"排序类设置"最容易踩的坑，因此把合并放在仓库层，界面拿到就可直接用。
     *
     * 空 / 无记录时返回空列表，由界面回落到静态顺序。
     */
    val labGridOrder: Flow<List<String>> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.LAB_GRID_ORDER]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }.onEach { labGridOrderCache = it }

    /**
     * [labGridOrder] 的**进程级快照**（还没读到过盘时为 null）。
     *
     * ## 为什么需要它（v1.3.1 beta 需求 8：进入实验室卡片会"瞬间移动排序"）
     *
     * `DataStore.data` 是冷流，界面只能给它一个「初值」，读到盘之前先拿初值渲染。
     * 初值若写死成空列表，用户自定义过顺序时就会先按**默认顺序**画一帧，
     * 真实顺序到达后再用 `.animateItem()` 平滑挪位——用户看到的就是卡片"瞬间移动"。
     *
     * 两种解法里选了「快照 + 启动预热」（另一个是进入页面先不画、等首帧数据）：
     * - 快照由 [MoreCheckApp] 在 `onCreate` 里持续镜像，进程一起来就已经就位，
     *   绝大多数「回到实验室」的路径上首帧就是正确顺序，没有空白期；
     * - 冷启动直接落在实验室（快捷方式）时快照可能还是 null，此时由界面门控兜底
     *   （见 `LabScreen` 的 `orderReady`），宁可空白一两帧也不画错顺序再挪。
     *
     * ⚠️ 只镜像不改写：真实来源永远是 DataStore，快照只用于"首帧显示"，
     * 落地写入走 [setLabGridOrder]。
     */
    @Volatile
    private var labGridOrderCache: List<String>? = null

    /** [devDiscoverSearchMode] 的进程级快照（启动预热，见那个属性的 KDoc）。 */
    @Volatile
    private var discoverSearchModeCache: Boolean? = null

    /** [entryPlacementOverrides] 的进程级快照（启动预热，见那个属性的 KDoc）。 */
    @Volatile
    private var entryPlacementCache: String? = null

    /** 见 [labGridOrderCache]；`null` = 本进程还没读到过盘。 */
    val labGridOrderSnapshot: List<String>?
        get() = labGridOrderCache

    /**
     * 写回用户自定义的宫格顺序（传**完整**列表，含未置顶的项）。
     *
     * 传空列表 = 移除该键 = 恢复代码里的静态顺序（「恢复默认排序」用）。
     */
    suspend fun setLabGridOrder(keys: List<String>) {
        context.dataStore.edit { prefs ->
            val cleaned = keys.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.LAB_GRID_ORDER)
            } else {
                prefs[Keys.LAB_GRID_ORDER] = cleaned.joinToString(",")
            }
        }
    }

    // ---- 实验室快捷方式固定记录 ----------------------------------------------
    //
    // ⚠️ v1.1.75：`labPinnedShortcuts` 与 `addLabPinnedShortcut` **已删除**。
    //
    // 它们服务的是 v1.1.72 那版「已有提示框，不再二次询问」——当时应用内自己弹一次
    // 确认框，把"用户确认过"记进 DataStore，下次长按就不再问。
    // v1.1.74 需求 7 把**应用内那层确认框整个删掉**（改由系统 `requestPinShortcut`
    // 自己弹），于是这两个方法失去了唯一的调用方，成为无引用死代码。
    //
    // **刻意保留 [Keys.LAB_PINNED_SHORTCUTS] 这个键不删**：
    // 老用户设备上可能已经存着一份 key 集合（历史版本写入的）。删掉键定义不会删掉
    // 磁盘里的数据，只会让它变成"孤儿键"——留在 DataStore 里占几个字节、永远读不到。
    // 但删掉键定义之后，将来若有人重新需要这份数据，就得凭记忆猜回键名。
    // 保留定义的成本是零（一个 `stringSetPreferencesKey` 常量不产生任何读写），
    // 收益是这份历史数据始终有一个具名入口。故留键、删方法。

    // ---- 登录态（本地模拟，任务2）------------------------------------------

    suspend fun setLoggedIn(phone: String, mode: String) {
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = true
            it[Keys.PHONE] = phone
            it[Keys.LOGIN_MODE] = mode
        }
    }

    /**
     * 写入本地用户 ID（8 位展示标识，见 [com.morecheck.data.AuthRepository.onCloudLoginSucceeded]）。
     *
     * 与注册路径生成 ID 的逻辑分开：那边由界面收集完整资料后一次性落盘，
     * 这边只补一个"登录了但没填过资料"的展示 ID。
     */
    suspend fun setUserId(userId: String) {
        context.dataStore.edit { it[Keys.USER_ID] = userId }
    }

    /**
     * 注册成功后写入登录态 **以及注册时填写的资料**。
     *
     * 与 [setLoggedIn] 分开而不是合并：登录页（验证码登录）不收集昵称 / 性别，
     * 若合并还得让登录路径传一堆空值，语义反而含糊。
     * 这里一次性写入，避免「登录态已置位、资料还没落盘」的中间窗口被「我的信息」读到。
     */
    suspend fun setRegisteredProfile(
        phone: String,
        userId: String,
        nickname: String,
        gender: String,
        mode: String
    ) {
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = true
            it[Keys.PHONE] = phone
            it[Keys.LOGIN_MODE] = mode
            it[Keys.USER_ID] = userId
            it[Keys.NICKNAME] = nickname
            it[Keys.GENDER] = gender
        }
    }

    /**
     * 退出登录：清登录态**与注册资料**。
     *
     * 资料要一起清：它们展示在「我的信息」页，属于该账号的内容；
     * 若留着，下一个登录进来的账号会看到上一个人的昵称与性别。
     */
    suspend fun setLoggedOut() {
        // 头像属于"这个账号"的本地资料（2026-11-03 需求 3）：退出登录一并清除——
        // 路径键移除让界面立即回落灰色默认头像，磁盘上的文件也删掉，
        // 否则换人登录后旧头像仍留在应用私有目录里。
        runCatching { AvatarStore.avatarFile(context).delete() }
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = false
            it.remove(Keys.PHONE)
            it.remove(Keys.LOGIN_MODE)
            it.remove(Keys.USER_ID)
            it.remove(Keys.NICKNAME)
            it.remove(Keys.GENDER)
            it.remove(Keys.AVATAR_PATH)
            // 头像引导只对"当前这个人这次登录"有意义：退出即重置，
            // 否则换人登录后即使还是灰色头像，也再不会被引导（见 AVATAR_PROMPT_SHOWN）。
            it.remove(Keys.AVATAR_PROMPT_SHOWN)
        }
    }

    /**
     * 云同步下拉时写入昵称（v3 新增）。
     *
     * 键与 [AuthRepository] 读的 `auth_nickname` 是**同一个**（同一个 DataStore 文件），
     * 因此写这里、`AuthRepository.nickname` 立刻就能看到，不需要额外转发。
     * 空串会移除该键，让"未设置"只有一种表示法（与 `setAvatarPath(null)` 同口径）。
     */
    suspend fun setNickname(nickname: String) {
        context.dataStore.edit {
            if (nickname.isBlank()) it.remove(Keys.NICKNAME) else it[Keys.NICKNAME] = nickname
        }
    }

    /** 云同步下拉时写入性别（`male` / `female`），口径同 [setNickname]。 */
    suspend fun setGender(gender: String) {
        context.dataStore.edit {
            if (gender.isBlank()) it.remove(Keys.GENDER) else it[Keys.GENDER] = gender
        }
    }

    // ---- 隐私同意（首次启动强制同意 / 撤回后重新同意）--------------------
    //
    // 默认 false = 未同意。首次启动时由启动门闸拦截，用户不同意则退出应用；
    // 「权限管理 → 撤回隐私同意」会把它写回 false，下次启动重新要求同意。

    /** 是否已同意《用户协议》/《隐私政策》。默认 false。 */
    val privacyAgreed: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PRIVACY_AGREED] ?: false }

    suspend fun setPrivacyAgreed(agreed: Boolean) {
        context.dataStore.edit { it[Keys.PRIVACY_AGREED] = agreed }
    }

    // ---- 头像（我的信息 → 头像）------------------------------------------
    //
    // 只存**文件绝对路径**，图片本身写在 `filesDir/avatar/avatar.jpg`（见 `util/AvatarStore`）。
    // 不把图片字节塞进 DataStore：Preferences DataStore 适合存小标量，
    // 塞几百 KB 的二进制会让它每次读写都整体序列化，得不偿失。
    //
    // null 表示用户从没设置过头像，界面据此回落到灰色占位头像。

    /** 已保存头像的绝对路径；null = 从未设置。 */
    val avatarPath: Flow<String?> =
        context.dataStore.data.map { it[Keys.AVATAR_PATH] }

    /**
     * 记录头像路径。
     *
     * 传 null 会**移除**该键（而不是写入空串），让"未设置"只有一个表示法，
     * 界面判断不用同时兼容 null 和 ""。
     */
    suspend fun setAvatarPath(path: String?) {
        context.dataStore.edit {
            if (path.isNullOrEmpty()) {
                it.remove(Keys.AVATAR_PATH)
            } else {
                it[Keys.AVATAR_PATH] = path
            }
        }
    }

    /**
     * 「登录后引导设置头像」是否已提示过（2026-10-29 需求：
     * 「登录时，如果还是灰色头像，引导用户选择默认头像，或自行上传」）。
     *
     * 只在**登录后**有意义：退出登录时会被清掉（见 [setLoggedOut]），
     * 因此换人登录 / 重新登录仍会再引导一次，而不是"这辈子只问一遍"。
     */
    val avatarPromptShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AVATAR_PROMPT_SHOWN] ?: false }

    /** 标记「头像引导已问过」。传 false 可重置（退出登录时由本文件内部重置）。 */
    suspend fun setAvatarPromptShown(shown: Boolean) {
        context.dataStore.edit { prefs ->
            if (shown) prefs[Keys.AVATAR_PROMPT_SHOWN] = true
            else prefs.remove(Keys.AVATAR_PROMPT_SHOWN)
        }
    }

    // ---- 开发者选项（实验室 → 开发者选项）--------------------------------
    //
    // 仿手机开发者选项：`DEV_MODE_ENABLED` 是总开关，各子项**只在总开关打开时生效**
    // （由读取方 [com.morecheck.data.DeveloperOptionsRepository] 统一做「与」运算，
    // 而不是在每个使用点各写一遍判断——那样迟早漏一处）。
    //
    // ⚠️ 本轮起「图标测试」板块**没有**自己的子开关：它原来那个只作动画演示的占位开关
    // 已按需求删除，`DEV_ICON_TEST_ENABLED` 这个键与对应的读取器/写入器一并删掉了。
    // 这只是删掉一个没人读的死键：DataStore 文件里若残留旧值，也不影响任何行为
    // （没有任何代码去读它），不需要写迁移逻辑。

    /** 开发者选项总开关。默认关。 */
    val devModeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_MODE_ENABLED] ?: false }

    /** 「登录测试 → 验证码测试」开关。默认关。 */
    val devCodeTestEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CODE_TEST_ENABLED] ?: false }

    /**
     * 「性能测试」开关。**默认关**。
     *
     * 本轮起它有了真实作用：门控「性能测试」板块里的**开屏停留时长滑块**
     * （见 [com.morecheck.data.DeveloperOptionsRepository.splashHoldDurationMs]）。
     * 默认值刻意保持 `false` 不变——需求本轮明确要求「性能测试开关默认关闭」，
     * 若改成 true，等于让所有用户的开屏时长都变成由这个开关决定，是行为越界。
     */
    val devPerfTestEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_PERF_TEST_ENABLED] ?: false }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_MODE_ENABLED] = enabled }
    }

    /**
     * 所有走 `util.openInBrowser` 的链接统一按这个开关分流：
     * 打开 = 应用内「官网」加载，关闭 = 系统默认浏览器。
     *
     * 放在 SettingsRepository 而不是开发者选项仓库，是因为 Application 启动时
     * 就要读它来初始化进程级缓存，不该依赖开发者选项页是否组合过。
     */
    val linkOpenInApp: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.LINK_OPEN_IN_APP] ?: false }

    suspend fun setLinkOpenInApp(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LINK_OPEN_IN_APP] = enabled }
    }

    /**
     * 音乐后台播放开关。默认开（见 [Keys.MUSIC_BACKGROUND_PLAYBACK] 的说明）。
     *
     * 读取方是 `data/music/MusicPlayback.init` 里的一条收集——
     * 播放控制器不依赖设置页是否打开过，进程启动就会跟上这个值。
     */
    val musicBackgroundPlayback: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MUSIC_BACKGROUND_PLAYBACK] ?: true }

    suspend fun setMusicBackgroundPlayback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MUSIC_BACKGROUND_PLAYBACK] = enabled }
    }

    /** 实验室自定义名称表（key → 名称）；空表表示全部用内置名。 */
    val labCustomNames: Flow<Map<String, String>> =
        context.dataStore.data.map { prefs -> decodeLabNames(prefs[Keys.LAB_CUSTOM_NAMES]) }

    /** 改一个实验室条目的名字；[name] 传空 = 恢复内置名。 */
    suspend fun setLabCustomName(key: String, name: String) {
        context.dataStore.edit { prefs ->
            val current = decodeLabNames(prefs[Keys.LAB_CUSTOM_NAMES]).toMutableMap()
            if (name.isBlank()) current.remove(key) else current[key] = name.trim()
            prefs[Keys.LAB_CUSTOM_NAMES] = encodeLabNames(current)
        }
    }

    private fun decodeLabNames(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            json.keys().forEach { key ->
                val value = json.optString(key)
                if (value.isNotBlank()) map[key] = value
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun encodeLabNames(names: Map<String, String>): String {
        val json = org.json.JSONObject()
        names.forEach { (key, value) -> runCatching { json.put(key, value) } }
        return json.toString()
    }

    /** 歌词逐字高亮（默认开）。 */
    val musicLyricKaraoke: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MUSIC_LYRIC_KARAOKE] ?: true }

    suspend fun setMusicLyricKaraoke(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MUSIC_LYRIC_KARAOKE] = enabled }
    }

    /**
     * 桌面歌词悬浮窗（默认关）。
     *
     * 打开时要先拿到 `SYSTEM_ALERT_WINDOW`，UI 层负责引导授权；
     * 这里只存意图值，避免数据层依赖 Activity。
     */
    val musicDesktopLyric: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MUSIC_DESKTOP_LYRIC] ?: false }

    suspend fun setMusicDesktopLyric(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MUSIC_DESKTOP_LYRIC] = enabled }
    }

    /**
     * 音乐页「温馨提示」是否不再弹（勾过「不再提示」）。
     *
     * 只影响这一处提示；改动立刻生效，不需要重启。
     */
    val musicUiTipDismissed: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.MUSIC_UI_TIP_DISMISSED] ?: false }

    suspend fun setMusicUiTipDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[Keys.MUSIC_UI_TIP_DISMISSED] = dismissed }
    }

    /** B站进入提示「不再提示」标记（语义同音乐页）。 */
    val biliUiTipDismissed: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BILI_UI_TIP_DISMISSED] ?: false }

    suspend fun setBiliUiTipDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[Keys.BILI_UI_TIP_DISMISSED] = dismissed }
    }

    /**
     * 上一次看过更新介绍弹窗的版本号（空串 = 没记过）。
     *
     * 用途只有一处：`TabNavHost` 判断"这次启动该不该弹更新介绍"。
     * 写回发生在用户关掉弹窗（或首次启动自动补记）之后，见 [setLastSeenVersion]。
     */
    val lastSeenVersion: Flow<String> =
        context.dataStore.data.map { it[Keys.LAST_SEEN_VERSION] ?: "" }

    suspend fun setLastSeenVersion(version: String) {
        context.dataStore.edit { it[Keys.LAST_SEEN_VERSION] = version }
    }

    suspend fun setDevCodeTestEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CODE_TEST_ENABLED] = enabled }
    }

    suspend fun setDevPerfTestEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_PERF_TEST_ENABLED] = enabled }
    }

    // ---- 本轮新增的开发者选项子项（需求 11a / 11b / 12）------------------------

    /**
     * 「性能测试 → 左右滑动动画」开关。**默认开**（= 与改造前一致：
     * 一级入口切换本来就带滑入动画，默认关闭等于悄悄改掉所有用户的既有观感）。
     */
    val devTabSlideAnim: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_TAB_SLIDE_ANIM] ?: true }

    suspend fun setDevTabSlideAnim(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_TAB_SLIDE_ANIM] = enabled }
    }

    /**
     * 「图标测试 → 贪吃蛇标题栏显示」开关。**默认开**（= 与改造前一致）。
     */
    val devSnakeTitleBar: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_SNAKE_TITLE_BAR] ?: true }

    suspend fun setDevSnakeTitleBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_SNAKE_TITLE_BAR] = enabled }
    }

    /**
     * 「游戏开屏显示」开关（需求 23）。**默认开**（= 与改造前一致）。
     *
     * 管的是两个网页游戏页（贪吃蛇 / 数独）进入时那层 Hello World 开屏层。
     */
    val devGameSplash: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_SPLASH] ?: true }

    suspend fun setDevGameSplash(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_SPLASH] = enabled }
    }

    /**
     * 「游戏标题栏显示」开关（需求 2：两个游戏页共用）。**默认开**。
     *
     * 与 [devSnakeTitleBar] 的分工见 [Keys.DEV_GAME_TITLE_BAR] 的注释：
     * 贪吃蛇页的最终生效值由 `DeveloperOptionsRepository.snakeTitleBarEnabled`
     * 合成（只认旧键，需求 11b 行为不变）；数独页直接读本键。
     */
    val devGameTitleBar: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_TITLE_BAR] ?: true }

    suspend fun setDevGameTitleBar(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_TITLE_BAR] = enabled }
    }

    /**
     * 「游戏设置」中间门（2026-10-23 需求 3）。
     *
     * ## ⚠️ 2026-10-30 起**已停用**（用户要求移除这个开关）
     *
     * 它曾经门控「游戏标题栏显示」「游戏开屏显示」「游戏颜色模式」三批子项，
     * 界面上是「实验室与游戏」分区顶部那一行「启用游戏设置」。用户认为这层中间门
     * 只是让三行一起变灰、徒增"为什么点了没反应"，于是从界面删除；
     * 子项现在只受开发者选项总开关门控（`总开关 && 子开关`）。
     *
     * 键 `dev_game_settings_enabled` 的数据仍留在 DataStore 里，但**无人读取**
     * （与 `dev_perf_test_enabled` / `dev_game_splash` 同一处理：不留兼容层，
     * 只留这条注释说明它曾经是什么）。保留读写入口是因为删掉它没有任何收益，
     * 而旧数据将来若要做"迁移回滚"时还能看到用户当年的选择。
     */
    @Deprecated("2026-10-30 起界面已移除该开关，且它不再门控任何项；仅为留档保留")
    val devGameSettingsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_SETTINGS_ENABLED] ?: true }

    @Deprecated("2026-10-30 起界面已移除该开关，且它不再门控任何项；仅为留档保留")
    suspend fun setDevGameSettingsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_SETTINGS_ENABLED] = enabled }
    }

    /**
     * 「贪吃蛇开屏显示」开关（本轮需求 2）。**默认开**。
     *
     * 只作用于贪吃蛇页；数独页由 [devGameSplashSudoku] 控制，两者互不影响。
     */
    val devGameSplashSnake: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_SPLASH_SNAKE] ?: true }

    suspend fun setDevGameSplashSnake(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_SPLASH_SNAKE] = enabled }
    }

    /**
     * 「数独开屏显示」开关（本轮需求 2）。**默认开**。
     *
     * 只作用于数独页；贪吃蛇页由 [devGameSplashSnake] 控制，两者互不影响。
     */
    val devGameSplashSudoku: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_SPLASH_SUDOKU] ?: true }

    suspend fun setDevGameSplashSudoku(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_SPLASH_SUDOKU] = enabled }
    }

    /**
     * 「贪吃蛇标题栏显示」开关（本轮需求 5）。**默认开**。
     *
     * 只作用于贪吃蛇页；数独页由 [devGameTitleBarSudoku] 控制，两者互不影响。
     * 总开关 [devGameTitleBar] 保留（它仍门控两页：见仓库层的合成规则）。
     */
    val devGameTitleBarSnake: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_TITLE_BAR_SNAKE] ?: true }

    suspend fun setDevGameTitleBarSnake(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_TITLE_BAR_SNAKE] = enabled }
    }

    /**
     * 「数独标题栏显示」开关（本轮需求 5）。**默认开**。
     *
     * 只作用于数独页；贪吃蛇页由 [devGameTitleBarSnake] 控制，两者互不影响。
     */
    val devGameTitleBarSudoku: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_TITLE_BAR_SUDOKU] ?: true }

    suspend fun setDevGameTitleBarSudoku(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_TITLE_BAR_SUDOKU] = enabled }
    }

    /**
     * 「数独2 标题栏显示」子开关（2026-10-22）。规则与 [devGameTitleBarSnake] /
     * [devGameTitleBarSudoku] 完全一致，只是作用于数独2 页。
     */
    val devGameTitleBarSudoku2: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_GAME_TITLE_BAR_SUDOKU2] ?: true }

    suspend fun setDevGameTitleBarSudoku2(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_GAME_TITLE_BAR_SUDOKU2] = enabled }
    }

    /**
     * 「游戏标题栏 / 状态栏颜色」三档模式（本轮需求 3）。**默认
     * [DEFAULT_GAME_CHROME_MODE]（`dark_classic`）**。
     *
     * 暴露的是**原始字符串**而不是枚举：`data` 层不认识 `GameChromeMode`
     * （那是 `ui/lab` 的界面策略枚举）。还原成枚举由
     * `DeveloperOptionsRepository.gameChromeMode` 完成，界面与游戏页拿到的
     * 都是已经解析过的枚举，不会在三个使用点各写一次 `firstOrNull`。
     *
     * 读失败 / 键不存在都回落默认值：配色偏好读不出来不该让游戏页打不开。
     */
    val devGameChromeMode: Flow<String> =
        context.dataStore.data.map {
            it[Keys.DEV_GAME_CHROME_MODE] ?: DEFAULT_GAME_CHROME_MODE
        }

    suspend fun setDevGameChromeMode(value: String) {
        context.dataStore.edit { it[Keys.DEV_GAME_CHROME_MODE] = value }
    }

    /**
     * 实验室页背景模式。默认 `random`（= 与改造前一致：按深浅色分池轮换渐变）。
     */
    val devLabBackgroundMode: Flow<String> =
        context.dataStore.data.map { it[Keys.DEV_LAB_BG_MODE] ?: LAB_BG_MODE_RANDOM }

    suspend fun setDevLabBackgroundMode(mode: String) {
        context.dataStore.edit { it[Keys.DEV_LAB_BG_MODE] = mode }
    }

    /**
     * `custom` 模式的背景色（ARGB Int）；null = 未设置过（读取侧回落 `classic`）。
     */
    val devLabBackgroundCustomColor: Flow<Int?> =
        context.dataStore.data.map { prefs ->
            prefs[Keys.DEV_LAB_BG_CUSTOM_COLOR]?.toIntOrNull()?.takeIf { it != 0 }
        }

    suspend fun setDevLabBackgroundCustomColor(color: Int) {
        context.dataStore.edit { it[Keys.DEV_LAB_BG_CUSTOM_COLOR] = color.toString() }
    }

    // ---- 图标更改（开发者选项 → 图标测试 → 图标更改，实验性）------------------
    //
    // 见 `Keys.DEV_ICON_STYLE` / `Keys.DEV_CUSTOM_ICON_PATH` 上的说明。
    //
    // ⚠️ 这里**只负责持久化**，不在这里做「切换桌面图标」这件事：
    // `setComponentEnabledSetting` 是平台调用、需要 Context 与包管理器，
    // 属于 `data/AppIconRepository` 的职责。仓库只管「用户选了什么」。

    /**
     * 「图标更改」选中的内置图标套短名（如 `default` / `blue`）。
     *
     * 默认 [SettingsRepository.DEFAULT_ICON_STYLE]，与 manifest 里
     * `.icon.Default` 是唯一 `enabled="true"` 那套**必须一致**，
     * 否则首次进页面会出现「界面显示选了蓝色、桌面却是默认图标」的矛盾回显。
     */
    val devIconStyle: Flow<String> =
        context.dataStore.data.map { it[Keys.DEV_ICON_STYLE] ?: DEFAULT_ICON_STYLE }

    /**
     * 自定义图标的绝对路径；null = 用户从没上传过（界面回落为「未设置」）。
     *
     * 用 `?: null` 而不是 `?: ""`：让「没有」只有一个表示法，
     * 调用方不必同时兼容 null 与空串（与 `avatarPath` 同一口径）。
     */
    val devCustomIconPath: Flow<String?> =
        context.dataStore.data.map { it[Keys.DEV_CUSTOM_ICON_PATH]?.takeIf { it.isNotEmpty() } }

    suspend fun setDevIconStyle(style: String) {
        context.dataStore.edit { it[Keys.DEV_ICON_STYLE] = style }
    }

    /**
     * 记录自定义图标路径；传 null / 空串会**移除**该键（见 [Keys.DEV_CUSTOM_ICON_PATH]）。
     *
     * 只清 DataStore 里的路径、**不删磁盘上的图片文件**：删文件这一步交给
     * `AppIconRepository.clearCustomIcon()`，因为「恢复默认」既要清路径又要删文件、
     * 而单独调 [setDevCustomIconPath] 的调用方不一定希望动文件。
     * 两者分开后，语义各自单一，不会出现「以为清了路径，其实文件还在占空间」的混淆。
     */
    suspend fun setDevCustomIconPath(path: String?) {
        context.dataStore.edit {
            if (path.isNullOrEmpty()) {
                it.remove(Keys.DEV_CUSTOM_ICON_PATH)
            } else {
                it[Keys.DEV_CUSTOM_ICON_PATH] = path
            }
        }
    }

    // ---- 开屏停留时长（开发者选项 → 性能测试）--------------------------------
    //
    // 见 `Keys.SPLASH_HOLD_MS` 上的说明（默认值 = 改造前的既有行为）。

    /**
     * 开屏「Hello World」的额外停留时长（毫秒）。
     *
     * 读脏数据（负值 / 超过上限）时**夹取**而不是原样返回：这个值会被直接喂给
     * `delay()`，一个负值会让开屏瞬间闪退，一个超大值会让应用像卡死。
     * 夹取是「保证界面拿到的永远是个能用的数」，比抛异常或静默回落更可预期。
     */
    val splashHoldMs: Flow<Int> =
        context.dataStore.data.map {
            (it[Keys.SPLASH_HOLD_MS] ?: DEFAULT_SPLASH_HOLD_MS)
                .coerceIn(MIN_SPLASH_HOLD_MS, MAX_SPLASH_HOLD_MS)
        }

    suspend fun setSplashHoldMs(ms: Int) {
        context.dataStore.edit {
            it[Keys.SPLASH_HOLD_MS] = ms.coerceIn(MIN_SPLASH_HOLD_MS, MAX_SPLASH_HOLD_MS)
        }
    }

    //
    // ⚠️ 已删除（2026-10-23 需求 8）：`splashJitterMs` 读写入口 + `SPLASH_JITTER_MS` 键定义
    // + `MAX_SPLASH_JITTER_MS` / `SPLASH_JITTER_STEP_MS` 两个上限常量。
    // 那条「浮动范围」滑块不再存在（改成下面这个布尔开关），这条链从此零引用。
    // 旧值若已写在用户磁盘上会成为孤儿数据，按键名取原始值即可回滚，无需保留类型化入口。

    /**
     * 「随机浮动」是否开启（2026-10-23 需求 8）。默认 false。
     *
     * 只做存取，与「总开关 && 性能测试」的合并在
     * [com.morecheck.data.DeveloperOptionsRepository.splashHoldDurationMs] 里。
     */
    val splashJitterEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SPLASH_JITTER_ENABLED] ?: false }

    suspend fun setSplashJitterEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SPLASH_JITTER_ENABLED] = enabled }
    }

    // ---- 开屏显示文字（2026-10-27）------------------------------------------
    //
    // 读侧一个流读出四个键拼成 [SplashTextStyle]（四个键总是一起用，拆成四条流
    // 会让渲染层要写四次 collect + 自己拼装，且首帧会出现"文字是新的、字号还是旧的"）；
    // 写侧按字段分开，改字号不必把整份设置读出来再写回。

    /**
     * 开屏文字的完整设置（默认 [SplashTextStyle.DEFAULT]）。
     *
     * **脏数据一律就地纠正而不是抛异常**（与 [splashHoldMs] 夹取同一条口径）：
     * 空文案回落默认文字、字号夹进 `[MIN_SIZE_SP, MAX_SIZE_SP]`、
     * 认不出的字体 id 回落默认档。开屏这一层是**启动路径上的东西**，
     * 它比任何设置都更不能因为一条脏数据而崩。
     */
    val splashTextStyle: Flow<SplashTextStyle> =
        context.dataStore.data.map { prefs ->
            val rawText = prefs[Keys.DEV_SPLASH_TEXT] ?: SplashTextStyle.DEFAULT_TEXT
            SplashTextStyle(
                // 只留空白字符的文案等于没有文案，回落默认（否则开屏会是一片底色）。
                text = rawText.takeIf { it.isNotBlank() } ?: SplashTextStyle.DEFAULT_TEXT,
                sizeSp = (prefs[Keys.DEV_SPLASH_TEXT_SIZE] ?: SplashTextStyle.DEFAULT_SIZE_SP)
                    .coerceIn(SplashTextStyle.MIN_SIZE_SP, SplashTextStyle.MAX_SIZE_SP),
                font = SplashTextFont.fromId(prefs[Keys.DEV_SPLASH_TEXT_FONT]),
                multiline = prefs[Keys.DEV_SPLASH_TEXT_MULTILINE] ?: false
            )
        }

    /**
     * 写开屏文字。
     *
     * 两处纠正在写入侧就做掉（而不是只靠读侧兜）：空白串**不写**（用户清空输入框后
     * 点确认，期望是"没改"而不是"开屏变空白"）、超长截到 [SplashTextStyle.MAX_TEXT_LENGTH]。
     * 换行符保留（多行模式要用），只在存储层不做任何替换。
     */
    suspend fun setSplashText(text: String) {
        if (text.isBlank()) return
        context.dataStore.edit {
            it[Keys.DEV_SPLASH_TEXT] = text.take(SplashTextStyle.MAX_TEXT_LENGTH)
        }
    }

    suspend fun setSplashTextSize(sizeSp: Int) {
        context.dataStore.edit {
            it[Keys.DEV_SPLASH_TEXT_SIZE] =
                sizeSp.coerceIn(SplashTextStyle.MIN_SIZE_SP, SplashTextStyle.MAX_SIZE_SP)
        }
    }

    suspend fun setSplashTextFont(font: SplashTextFont) {
        context.dataStore.edit { it[Keys.DEV_SPLASH_TEXT_FONT] = font.id }
    }

    suspend fun setSplashTextMultiline(multiline: Boolean) {
        context.dataStore.edit { it[Keys.DEV_SPLASH_TEXT_MULTILINE] = multiline }
    }

    /**
     * 一键恢复默认：四个键一起写回 [SplashTextStyle.DEFAULT]。
     *
     * 必须在**同一个** `edit {}` 里写完：分成四次 edit 的话，界面上会先看到
     * "字号回到 34 但文案还是自定义的"这种中间态闪一下。
     */
    suspend fun restoreDefaultSplashText() {
        context.dataStore.edit {
            it[Keys.DEV_SPLASH_TEXT] = SplashTextStyle.DEFAULT_TEXT
            it[Keys.DEV_SPLASH_TEXT_SIZE] = SplashTextStyle.DEFAULT_SIZE_SP
            it[Keys.DEV_SPLASH_TEXT_FONT] = SplashTextStyle.DEFAULT.font.id
            it[Keys.DEV_SPLASH_TEXT_MULTILINE] = SplashTextStyle.DEFAULT.multiline
        }
    }

    /** 「随机浮动」首次介绍弹窗是否已看过（见 [Keys.SPLASH_JITTER_INTRO_SHOWN]）。 */
    val splashJitterIntroShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.SPLASH_JITTER_INTRO_SHOWN] ?: false }

    suspend fun setSplashJitterIntroShown(shown: Boolean) {
        context.dataStore.edit { it[Keys.SPLASH_JITTER_INTRO_SHOWN] = shown }
    }

    // ---- 2026-10-25 本轮新增的读写入口 ------------------------------------

    /**
     * 二级页转场时长（毫秒）。读取时夹取到合法区间，防脏数据直接喂给动画。
     */
    val devNavAnimMs: Flow<Int> =
        context.dataStore.data.map {
            (it[Keys.DEV_NAV_ANIM_MS] ?: DEFAULT_NAV_ANIM_MS)
                .coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)
        }

    suspend fun setDevNavAnimMs(ms: Int) {
        context.dataStore.edit { it[Keys.DEV_NAV_ANIM_MS] = ms.coerceIn(MIN_ANIM_MS, MAX_ANIM_MS) }
    }

    /** 底栏入口切换动画时长（毫秒）。读取时夹取。 */
    val devTabSwitchMs: Flow<Int> =
        context.dataStore.data.map {
            (it[Keys.DEV_TAB_SWITCH_MS] ?: DEFAULT_TAB_SWITCH_MS)
                .coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)
        }

    suspend fun setDevTabSwitchMs(ms: Int) {
        context.dataStore.edit { it[Keys.DEV_TAB_SWITCH_MS] = ms.coerceIn(MIN_ANIM_MS, MAX_ANIM_MS) }
    }

    /** 「实验室功能开屏 · 其他页面」开关（默认关，口径见键上的 KDoc）。 */
    val devLabFeatureSplash: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_LAB_FEATURE_SPLASH] ?: false }

    suspend fun setDevLabFeatureSplash(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_LAB_FEATURE_SPLASH] = enabled }
    }

    /** 实验室功能开屏的**总开关**（默认开）。 */
    val devLabSplashAll: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_LAB_SPLASH_ALL] ?: true }

    suspend fun setDevLabSplashAll(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_LAB_SPLASH_ALL] = enabled }
    }

    /** 热搜页开屏（默认关）。 */
    val devLabSplashHotSearch: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_LAB_SPLASH_HOTSEARCH] ?: false }

    suspend fun setDevLabSplashHotSearch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_LAB_SPLASH_HOTSEARCH] = enabled }
    }

    /** 数独2 开屏（默认开）。 */
    val devLabSplashSudoku2: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_LAB_SPLASH_SUDOKU2] ?: true }

    suspend fun setDevLabSplashSudoku2(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_LAB_SPLASH_SUDOKU2] = enabled }
    }

    /** 实验室功能开屏的停留时长（毫秒，默认 800；读取时夹取到合理区间）。 */
    val devLabSplashHoldMs: Flow<Int> =
        context.dataStore.data.map {
            (it[Keys.DEV_LAB_SPLASH_HOLD_MS] ?: DEFAULT_LAB_SPLASH_HOLD_MS)
                .coerceIn(MIN_LAB_SPLASH_HOLD_MS, MAX_LAB_SPLASH_HOLD_MS)
        }

    suspend fun setDevLabSplashHoldMs(ms: Int) {
        context.dataStore.edit {
            it[Keys.DEV_LAB_SPLASH_HOLD_MS] = ms.coerceIn(MIN_LAB_SPLASH_HOLD_MS, MAX_LAB_SPLASH_HOLD_MS)
        }
    }

    /** 「撤回退出转圈」回退开关（默认开）。 */
    val devRevokeExitSpinner: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_REVOKE_EXIT_SPINNER] ?: true }

    suspend fun setDevRevokeExitSpinner(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_REVOKE_EXIT_SPINNER] = enabled }
    }

    /** 注册页昵称检测转圈时长（毫秒，默认 200）。读取时夹取。 */
    val devNicknameCheckMs: Flow<Int> =
        context.dataStore.data.map {
            (it[Keys.DEV_NICKNAME_CHECK_MS] ?: DEFAULT_NICKNAME_CHECK_MS)
                .coerceIn(MIN_NICKNAME_CHECK_MS, MAX_NICKNAME_CHECK_MS)
        }

    suspend fun setDevNicknameCheckMs(ms: Int) {
        context.dataStore.edit {
            it[Keys.DEV_NICKNAME_CHECK_MS] = ms.coerceIn(MIN_NICKNAME_CHECK_MS, MAX_NICKNAME_CHECK_MS)
        }
    }

    /** 「检查更新」是否在应用内打开（默认关 = 系统浏览器）。 */
    val devCheckUpdateInApp: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CHECK_UPDATE_IN_APP] ?: false }

    suspend fun setDevCheckUpdateInApp(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CHECK_UPDATE_IN_APP] = enabled }
    }

    /**
     * 「检查更新」按钮点下去去哪（本轮需求 2，默认 [CheckUpdateTarget.APK_DOWNLOAD]）。
     *
     * 三种取值见 [CheckUpdateTarget]。读不到 / 脏值时按默认处理，
     * 不受信任的存储内容不应该把按钮变成一个打不开的东西。
     */
    val checkUpdateTarget: Flow<CheckUpdateTarget> =
        context.dataStore.data.map { prefs ->
            CheckUpdateTarget.fromValue(prefs[Keys.CHECK_UPDATE_TARGET])
        }

    suspend fun setCheckUpdateTarget(target: CheckUpdateTarget) {
        context.dataStore.edit { it[Keys.CHECK_UPDATE_TARGET] = target.value }
    }

    /**
     * 「更新日志」入口放在哪（2026-11-02 需求，默认 [ChangelogEntryPlacement.INSIDE]）。
     *
     * 与「检查更新」的去处**解耦**：这两件事相关但并不等价——
     * 去处决定点按钮之后去哪，入口位置决定"日志的门口开在哪个房间"。
     * 早前是前者推后者，现在各自独立，任一组合都合法。
     */
    val changelogEntryPlacement: Flow<ChangelogEntryPlacement> =
        context.dataStore.data.map { prefs ->
            ChangelogEntryPlacement.fromValue(prefs[Keys.CHANGELOG_ENTRY_PLACEMENT])
        }

    suspend fun setChangelogEntryPlacement(placement: ChangelogEntryPlacement) {
        context.dataStore.edit { it[Keys.CHANGELOG_ENTRY_PLACEMENT] = placement.value }
    }

    // ---- 开屏密码（设置 → 安全）--------------------------------------------

    /** 开屏密码总开关（默认关）。 */
    val appLockEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.APP_LOCK_ENABLED] ?: false }

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_LOCK_ENABLED] = enabled
            // 关掉锁屏时作废旧宽限凭据：否则「关 → 再开」会被上一次解锁的时间戳
            // 直接放行，用户会看到刚打开的锁屏一次都不弹。
            if (!enabled) prefs.remove(Keys.APP_LOCK_GRACE)
        }
    }

    /** 手势图案哈希；空 = 未设置。 */
    val appLockPatternHash: Flow<String> =
        context.dataStore.data.map { it[Keys.APP_LOCK_PATTERN_HASH] ?: "" }

    suspend fun setAppLockPatternHash(hash: String) {
        context.dataStore.edit { it[Keys.APP_LOCK_PATTERN_HASH] = hash }
    }

    /** 「指纹解锁」开关（默认关）。 */
    val appLockBiometricEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.APP_LOCK_BIOMETRIC_ENABLED] ?: false }

    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_BIOMETRIC_ENABLED] = enabled }
    }

    /** 手势哈希的盐；空 = 尚未生成（首次设置手势时生成）。 */
    val appLockSalt: Flow<String> =
        context.dataStore.data.map { it[Keys.APP_LOCK_SALT] ?: "" }

    suspend fun setAppLockSalt(salt: String) {
        context.dataStore.edit { it[Keys.APP_LOCK_SALT] = salt }
    }

    /**
     * 生成并保存一个新盐，返回它（设置手势时调用）。
     * 已有盐时**覆盖**（重设手势 = 换一份盐，旧哈希随之作废）。
     */
    suspend fun regenerateAppLockSalt(): String {
        val salt = java.util.UUID.randomUUID().toString().replace("-", "")
        setAppLockSalt(salt)
        return salt
    }

    /** 解锁前先播 Hello World 开屏（默认开，见 `Keys.APP_LOCK_HELLO_SPLASH`）。 */
    val appLockHelloSplash: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.APP_LOCK_HELLO_SPLASH] ?: true }

    suspend fun setAppLockHelloSplash(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_HELLO_SPLASH] = enabled }
    }

    /** 手势输错时震动 + 变红（默认开，见 `Keys.APP_LOCK_ERROR_FEEDBACK`）。 */
    val appLockErrorFeedback: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.APP_LOCK_ERROR_FEEDBACK] ?: true }

    suspend fun setAppLockErrorFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK_ERROR_FEEDBACK] = enabled }
    }

    /**
     * 重新锁定的时机（默认 [AppLockRelock.COLD_START]，见 `Keys.APP_LOCK_RELOCK`）。
     *
     * 未知键值一律回落默认：降级安装（比如退回旧版本再升回来）读到的可能是
     * 未来版本写入的新枚举名，此时不该崩，也不该当成"立即锁"吓人一跳。
     */
    val appLockRelock: Flow<AppLockRelock> =
        context.dataStore.data.map { AppLockRelock.fromKey(it[Keys.APP_LOCK_RELOCK]) }

    suspend fun setAppLockRelock(mode: AppLockRelock) {
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_LOCK_RELOCK] = mode.key
            // 换档即作废旧凭据：新档位要求一次真实解锁，避免来回切档把旧时间戳复活。
            prefs.remove(Keys.APP_LOCK_GRACE)
        }
    }

    /**
     * 宽限档解锁凭据。空串表示盘上没有值，与「还没读到盘」的 null 哨兵区分开：
     * `AppLockGate` 在读盘完成前停在空页，避免先闪一下锁屏再放行。
     */
    val appLockGrace: Flow<String> =
        context.dataStore.data.map { it[Keys.APP_LOCK_GRACE].orEmpty() }

    /** 写入凭据；传空串等价于清除。 */
    suspend fun setAppLockGrace(raw: String) {
        context.dataStore.edit { prefs ->
            if (raw.isEmpty()) prefs.remove(Keys.APP_LOCK_GRACE)
            else prefs[Keys.APP_LOCK_GRACE] = raw
        }
    }

    /**
     * 指纹弹窗三行文案（标题 / 副标题 / 取消按钮）。
     *
     * 空串一律**当作未自定义**、回落到默认值——判定放在这里做一次，
     * 免得锁屏页与设置页各写一遍 `ifBlank`（两处口径漂移过一次的教训见 HANDOVER）。
     */
    val appLockBioTexts: Flow<AppLockBioTexts> =
        context.dataStore.data.map { prefs ->
            AppLockBioTexts(
                // 标题与按钮文案**必须是非空**（BiometricPrompt 要求），空则回落默认。
                title = prefs[Keys.APP_LOCK_BIO_TITLE]?.ifBlank { DEFAULT_APP_LOCK_BIO_TITLE }
                    ?: DEFAULT_APP_LOCK_BIO_TITLE,
                negative = prefs[Keys.APP_LOCK_BIO_NEGATIVE]?.ifBlank { DEFAULT_APP_LOCK_BIO_NEGATIVE }
                    ?: DEFAULT_APP_LOCK_BIO_NEGATIVE,
                // 副标题与说明**允许为空**（空 = 不显示）：键存在即以盘上的值为准，
                // 只有键不存在（从没改过 / 老版本）才用默认值。
                subtitle = prefs[Keys.APP_LOCK_BIO_SUBTITLE] ?: DEFAULT_APP_LOCK_BIO_SUBTITLE,
                description = prefs[Keys.APP_LOCK_BIO_DESCRIPTION] ?: DEFAULT_APP_LOCK_BIO_DESCRIPTION
            ).let(::migrateLegacyBioTexts)
        }

    /**
     * 老版本默认文案的归一（v1.1.92 用词统一为「锁屏密码」）。
     *
     * 只替换**与老默认值逐字相同**的两项：这代表用户从没改过、或者只是点过
     * 「恢复默认」（那时写进去的就是老默认值）。凡是自己改过的文案一律不动。
     */
    private fun migrateLegacyBioTexts(texts: AppLockBioTexts): AppLockBioTexts = texts.copy(
        title = if (texts.title == LEGACY_APP_LOCK_BIO_TITLE) DEFAULT_APP_LOCK_BIO_TITLE
        else texts.title,
        negative = if (texts.negative == LEGACY_APP_LOCK_BIO_NEGATIVE) DEFAULT_APP_LOCK_BIO_NEGATIVE
        else texts.negative
    )

    suspend fun setAppLockBioTexts(texts: AppLockBioTexts) {
        context.dataStore.edit {
            it[Keys.APP_LOCK_BIO_TITLE] = texts.title
            it[Keys.APP_LOCK_BIO_SUBTITLE] = texts.subtitle
            it[Keys.APP_LOCK_BIO_DESCRIPTION] = texts.description
            it[Keys.APP_LOCK_BIO_NEGATIVE] = texts.negative
        }
    }

    // ---- 「界面测试」分区（v1.1.74 需求 8）------------------------------
    //
    // 四项都是**全局**界面行为：撒花、转场、日期格式、按压反馈。
    // 三个开关的默认一律 true（= 改造前行为），理由见 Keys 上那段说明。
    //
    // 这里全部只做**存取**；"总开关 && 子开关"的与运算在
    // `DeveloperOptionsRepository` 里统一做（见那个文件的类 KDoc），
    // 页面只管回显原始值。

    /** 「打卡完成动画」**原始值**（默认 true）。 */
    val devCheckinCelebrationEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_CHECKIN_CELEBRATION] ?: true }

    suspend fun setDevCheckinCelebrationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CHECKIN_CELEBRATION] = enabled }
    }

    /** 「页面转场动画」**原始值**（默认 true）。 */
    val devPageTransitionAnim: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_PAGE_TRANSITION_ANIM] ?: true }

    suspend fun setDevPageTransitionAnim(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_PAGE_TRANSITION_ANIM] = enabled }
    }

    /** 「按压反馈」**原始值**（默认 true）。 */
    val devPressFeedback: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_PRESS_FEEDBACK] ?: true }

    suspend fun setDevPressFeedback(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_PRESS_FEEDBACK] = enabled }
    }

    /** 「退出音乐时二次确认」**原始值**（默认 false，见键的 KDoc）。 */
    val devMusicExitConfirm: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_MUSIC_EXIT_CONFIRM] ?: false }

    suspend fun setDevMusicExitConfirm(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_MUSIC_EXIT_CONFIRM] = enabled }
    }

    /** 「音乐入口显示实时歌词」**原始值**（默认 true，见键的 KDoc）。 */
    val devMusicEntryLyrics: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEV_MUSIC_ENTRY_LYRICS] ?: true }

    suspend fun setDevMusicEntryLyrics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_MUSIC_ENTRY_LYRICS] = enabled }
    }

    /**
     * 「发现页官网位」模式：false = 官网，true = 搜一搜。默认保持现有观感。
     *
     * `onEach` 把每次读到的值镜像到 [discoverSearchModeSnapshot]：
     * 发现页每次切回来都是**重新组合**的，只给 `initialValue = false` 会让它
     * 先按「官网」画一帧、再跳成「搜一搜」——用户看到的就是标题与图标闪一下。
     */
    val devDiscoverSearchMode: Flow<Boolean> =
        context.dataStore.data
            .map { it[Keys.DEV_DISCOVER_SEARCH_MODE] ?: false }
            .onEach { discoverSearchModeCache = it }

    /** 见 [devDiscoverSearchMode]；`null` = 本进程还没读到过盘。 */
    val discoverSearchModeSnapshot: Boolean?
        get() = discoverSearchModeCache

    suspend fun setDevDiscoverSearchMode(searchMode: Boolean) {
        context.dataStore.edit { it[Keys.DEV_DISCOVER_SEARCH_MODE] = searchMode }
    }

    /** 逐页关闭开屏的 routeKey 原始串（逗号分隔）。 */
    val devLabSplashDisabledPages: Flow<String> =
        context.dataStore.data.map { it[Keys.DEV_LAB_SPLASH_DISABLED_PAGES].orEmpty() }

    suspend fun setDevLabSplashDisabledPages(raw: String) {
        context.dataStore.edit { it[Keys.DEV_LAB_SPLASH_DISABLED_PAGES] = raw }
    }

    /**
     * 入口归属覆盖表原始串；空串 = 全部按默认归属。
     *
     * 同 [devDiscoverSearchMode]：带快照，避免「把入口搬到发现页后，切回发现页
     * 先按没有搬入项画一帧、再整块长出来」的闪动；实验室宫格也靠它避免
     * 搬走一项后先按默认位置排一帧再挪。
     */
    val entryPlacementOverrides: Flow<String> =
        context.dataStore.data
            .map { it[Keys.ENTRY_PLACEMENT_OVERRIDES].orEmpty() }
            .onEach { entryPlacementCache = it }

    /** 见 [entryPlacementOverrides]；`null` = 本进程还没读到过盘。 */
    val entryPlacementSnapshot: String?
        get() = entryPlacementCache

    suspend fun setEntryPlacementOverrides(raw: String) {
        context.dataStore.edit { it[Keys.ENTRY_PLACEMENT_OVERRIDES] = raw }
    }

    /**
     * 「日期显示格式」的**原始字符串**（默认 [DateDisplayFormat.DEFAULT_ID]）。
     *
     * 暴露字符串而不是枚举，与本文件里 `devGameChromeMode` / `devLabBackgroundMode`
     * 那一批**同口径**：数据层只负责存取，字符串 → 枚举的解析统一放在
     * `DeveloperOptionsRepository` 里做一次，免得三个使用点各写一遍
     * `firstOrNull`（那条键的 KDoc 已记录过这个理由）。
     */
    val devDateFormat: Flow<String> =
        context.dataStore.data.map { it[Keys.DEV_DATE_FORMAT] ?: DateDisplayFormat.DEFAULT_ID }

    suspend fun setDevDateFormat(formatId: String) {
        context.dataStore.edit { it[Keys.DEV_DATE_FORMAT] = formatId }
    }

    // ── 课程提醒（v1.1.79 需求 2）─────────────────────────────────────────

    /** 「课程提醒」开关。**默认关**（理由见 [Keys.TIMETABLE_REMINDER_ENABLED]）。 */
    val timetableReminderEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.TIMETABLE_REMINDER_ENABLED] ?: false }

    suspend fun setTimetableReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TIMETABLE_REMINDER_ENABLED] = enabled }
    }

    /**
     * 提醒提前量（分钟）。默认 10。
     *
     * 只接受 0 / 10 / 60 三个值（调用方传合法档位）；存的是字符串，
     * 与 [Keys.TIMETABLE_REMINDER_LEAD] 的说明一致。
     */
    val timetableReminderLeadMinutes: Flow<Int> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.TIMETABLE_REMINDER_LEAD]?.toIntOrNull() ?: DEFAULT_TIMETABLE_LEAD)
                .takeIf { it in TIMETABLE_LEAD_OPTIONS } ?: DEFAULT_TIMETABLE_LEAD
        }

    suspend fun setTimetableReminderLeadMinutes(minutes: Int) {
        require(minutes in TIMETABLE_LEAD_OPTIONS) { "unsupported lead minutes: $minutes" }
        context.dataStore.edit { it[Keys.TIMETABLE_REMINDER_LEAD] = minutes.toString() }
    }

    // ── 打卡提醒增强（v5）──────────────────────────────────────────────────

    /** 静默时段开关，默认关。 */
    val reminderQuietEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMINDER_QUIET_ENABLED] ?: false }

    suspend fun setReminderQuietEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMINDER_QUIET_ENABLED] = enabled }
    }

    /** 静默开始时刻 "HH:mm"，默认 22:00。 */
    val reminderQuietStart: Flow<String> =
        context.dataStore.data.map { it[Keys.REMINDER_QUIET_START] ?: DEFAULT_QUIET_START }

    suspend fun setReminderQuietStart(value: String) {
        context.dataStore.edit { it[Keys.REMINDER_QUIET_START] = value }
    }

    /** 静默结束时刻 "HH:mm"，默认 07:00（跨午夜，判定见 ReminderPolicy）。 */
    val reminderQuietEnd: Flow<String> =
        context.dataStore.data.map { it[Keys.REMINDER_QUIET_END] ?: DEFAULT_QUIET_END }

    suspend fun setReminderQuietEnd(value: String) {
        context.dataStore.edit { it[Keys.REMINDER_QUIET_END] = value }
    }

    /** 漏打卡补推开关，默认关。 */
    val reminderFollowUpEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.REMINDER_FOLLOWUP_ENABLED] ?: false }

    suspend fun setReminderFollowUpEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMINDER_FOLLOWUP_ENABLED] = enabled }
    }

    /** 补推延迟（分钟），默认 30；非法值回落默认档。 */
    val reminderFollowUpMinutes: Flow<Int> =
        context.dataStore.data.map { prefs ->
            (prefs[Keys.REMINDER_FOLLOWUP_MINUTES]?.toIntOrNull() ?: DEFAULT_FOLLOWUP_MINUTES)
                .takeIf { it in FOLLOWUP_OPTIONS } ?: DEFAULT_FOLLOWUP_MINUTES
        }

    suspend fun setReminderFollowUpMinutes(minutes: Int) {
        require(minutes in FOLLOWUP_OPTIONS) { "unsupported follow-up minutes: $minutes" }
        context.dataStore.edit { it[Keys.REMINDER_FOLLOWUP_MINUTES] = minutes.toString() }
    }

    companion object {
        const val DEFAULT_QUIET_START = "22:00"
        const val DEFAULT_QUIET_END = "07:00"
        const val DEFAULT_FOLLOWUP_MINUTES = 30
        val FOLLOWUP_OPTIONS = listOf(15, 30, 60, 120)
        /** 默认主题色标识（绿色，与历史配色一致）。 */
        const val DEFAULT_THEME_ACCENT = "green"

        /** 计时器「最近使用时长」最多保留几个。 */
        const val MAX_RECENT_DURATIONS = 6

        /**
         * 默认内置图标套短名（`default`）。
         *
         * 与 `AndroidManifest.xml` 里唯一 `enabled="true"` 的 `.icon.Default` 对应，
         * 也与 `AppIconRepository.DEFAULT_ICON_ID` 同值——三处必须是同一个字面量，
         * 否则「默认」这件事就有了三个来源。
         * 放在 `SettingsRepository` 的 companion 里是因为**默认值属于仓库层**，
         * `AppIconRepository` 与界面都从这里取，不再各写一遍。
         */
        const val DEFAULT_ICON_STYLE = "default"

        /**
         * 开屏额外停留时长的**默认值 = 改造前的既有行为**。
         *
         * 900 = `ui/MoreCheckApp.kt` 里 `SPLASH_DURATION_MS = 900L`（改造前开屏的固定最短展示时长）。
         * 取「当前既有行为」而不是自己拍一个数，是为了让**没碰过滑块的用户完全无感**：
         * 升级后开屏还是 900ms。「恢复默认」按钮写回的也是这个值。
         *
         * ⚠️ 它与 `SPLASH_AGREE_HOLD_MS`（750ms）**不是一回事**，不要混：
         * 那个是「用户点了同意之后再多留 0.75 秒」，是本项目上一轮明确要求的行为，
         * 与开屏的基础停留时长相互独立、各自计一次。
         */
        const val DEFAULT_SPLASH_HOLD_MS = 900

        /** 滑块下限：0 秒（不等，开屏一满足条件就退场）。 */
        const val MIN_SPLASH_HOLD_MS = 0

        /**
         * 滑块上限（2026-10-25 需求：从 10 秒收紧为 **5 秒** = 5000ms）。
         *
         * 旧版本存的 5000~10000ms 值会在读取侧被 [splashHoldMs] 的夹取压到 5000，
         * 属一次性回落、无迁移需求。设上限的理由不变：这个值直接乘进开屏的 `delay`，
         * 没有上限时一个被改坏的 DataStore 就能让应用启动后「黑屏」很久。
         */
        const val MAX_SPLASH_HOLD_MS = 5_000

        /**
         * 实验室功能开屏的默认停留时长（毫秒）。
         *
         * 800ms 而不是沿用 App 开屏的 900ms：进实验室某个页面是"顺手点开"，
         * 停留感应当比冷启动更轻。它与游戏开屏原本的固定值（贪吃蛇/数独 1000ms）
         * 也不冲突——那两处仍用自己的常量，本值只作用于 `LabSplashHost` 这条路。
         */
        const val DEFAULT_LAB_SPLASH_HOLD_MS = 800

        /** 实验室开屏时长下限：0 = 不等待（相当于不播）。 */
        const val MIN_LAB_SPLASH_HOLD_MS = 0

        /** 实验室开屏时长上限：3 秒。比 App 开屏的 5 秒更紧——它只是"过渡一下"。 */
        const val MAX_LAB_SPLASH_HOLD_MS = 3_000

        /**
         * 滑块每档的步长（毫秒）= 0.1 秒一档。
         *
         * ⚠️ 这个值**必须能整除 [DEFAULT_SPLASH_HOLD_MS]（900）**，否则会出现
         * 「滑块位置停在一档、右侧文字写另一档」的自相矛盾：Material3 的 `Slider`
         * 会把喂进去的 `value` 吸附到最近的档位（`snapValueToTick`）。
         * 100 能整除 900（900 正好是第 10 档）；曾经的 500 不行（900 ÷ 500 = 1.8），
         * 会把位置吸到 1.0 秒而文字仍写 0.9 秒。
         */
        const val SPLASH_HOLD_STEP_MS = 100

        /**
         * 「随机浮动」的幅度（**比例**，不是毫秒）：勾选后每次开屏时长 =
         * 设定值 × [1 - RATIO, 1 + RATIO] 之间随机取一个。
         *
         * 2026-10-30 用户口径：「开屏时长随机浮动改为 设定时长 ±15%，即在该范围内随机取值」。
         * 之前是固定 ±300ms——那对 1 秒的开屏是 ±30%、对 5 秒只有 ±6%，
         * 同一句"随机"在不同设定下差别很大；改成比例之后"浮动幅度"是恒定的。
         */
        const val SPLASH_JITTER_RATIO = 0.15f

        // ---- 2026-10-25 本轮新增常量 ----------------------------------------

        /**
         * 两档动画时长（二级页转场 / 底栏切换）的公共区间：
         * 100ms 起步（再快肉眼看不出差别，接近瞬切）、600ms 封顶（再慢就显得卡）。
         * 步进 20ms 与滑块的档位手感一致。
         */
        const val MIN_ANIM_MS = 100
        const val MAX_ANIM_MS = 600
        const val ANIM_STEP_MS = 20

        /** 二级页转场时长的默认值 = 改造前的固定值（`TabNavHost.NAV_DURATION_MS`）。 */
        const val DEFAULT_NAV_ANIM_MS = 280

        /** 底栏切换动画时长的默认值 = 改造前的固定值（`MoreCheckApp.TAB_SWITCH_MS`）。 */
        const val DEFAULT_TAB_SWITCH_MS = 260

        /** 昵称检测转圈默认 200ms（本轮从写死的 1000ms 收紧，见需求）。 */
        const val DEFAULT_NICKNAME_CHECK_MS = 200
        const val MIN_NICKNAME_CHECK_MS = 100
        const val MAX_NICKNAME_CHECK_MS = 2000
        const val NICKNAME_CHECK_STEP_MS = 100

        // ---- 开屏密码：解锁前 Hello World 开屏 / 指纹弹窗文案 ----------------

        /**
         * 解锁前 Hello World 的停留时长（毫秒）。
         *
         * 与「开发者选项 → 开屏时长」**刻意分开**：那个管的是应用启动开屏，
         * 这里管的是"锁屏页出现前那一下"。用户如果把应用开屏设成 0 表示不要开屏，
         * 不应该连带把锁屏前的这层也关掉（反之亦然）。
         */
        const val APP_LOCK_SPLASH_HOLD_MS = 800

        /** v1.1.92 及更早的标题默认值；读盘时遇到它就归一到新用词。 */
        private const val LEGACY_APP_LOCK_BIO_TITLE = "开屏密码"

        /** v1.1.92 及更早的取消按钮默认值；同上。 */
        private const val LEGACY_APP_LOCK_BIO_NEGATIVE = "使用手势"

        /** 指纹弹窗标题的默认文案（v1.1.92：用词统一为「锁屏密码」）。 */
        const val DEFAULT_APP_LOCK_BIO_TITLE = "锁屏密码"

        /** 指纹弹窗副标题的默认文案。 */
        const val DEFAULT_APP_LOCK_BIO_SUBTITLE = "使用指纹或面部解锁"

        /**
         * 指纹弹窗那行说明的默认文案（**仅 API 30+ 显示**）。
         *
         * 默认给一句"为什么需要你验证"，比留空更像系统锁屏的观感；
         * 用户可以在设置里清空它（v1.1.92 需求）。
         */
        const val DEFAULT_APP_LOCK_BIO_DESCRIPTION = "验证身份后即可进入应用"

        /**
         * 指纹弹窗「取消」按钮的默认文案。
         *
         * 用户 2026-10-26 明确要求改成「使用密码」——本应用的解锁方式仍是**手势**，
         * 点它回到手势板。文案与实际行为存在一点错位，是用户拍板的取舍：
         * 对家人/朋友而言"密码"比"手势"更好理解。要改回去只动这一行。
         */
        const val DEFAULT_APP_LOCK_BIO_NEGATIVE = "使用密码"

        /** 指纹弹窗文案的长度上限（超长会被系统弹窗截断，拦在输入侧）。 */
        const val MAX_APP_LOCK_BIO_TEXT = 40

        // ---- 实验室背景模式（需求 11b）----------------------------------------

        /** 随机轮换：按深浅色模式分池，每次进入往前走一条（= 改造前的既有行为）。 */
        const val LAB_BG_MODE_RANDOM = "random"

        /** 经典：跟随系统深浅色模式的纯色（浅色 `#EDEDED` / 深色 `#111111`）。 */
        const val LAB_BG_MODE_CLASSIC = "classic"

        /** 自定义：用户挑选的固定颜色（值在 `DEV_LAB_BG_CUSTOM_COLOR`）。 */
        const val LAB_BG_MODE_CUSTOM = "custom"

        /**
         * 网页游戏页「标题栏 / 状态栏颜色」的默认档位 = `dark_classic`（需求 3）。
         *
         * 字面量必须与 `com.morecheck.ui.lab.GameChromeMode.DARK_CLASSIC.value` 一致。
         * 这里**不用**枚举引用：`SettingsRepository` 是纯持久化层，只认字符串，
         * 枚举的解析统一在 `DeveloperOptionsRepository` 里做一次（见那边的
         * `gameChromeMode`）。用 `const val` 而不是把字面量散在默认值表达式里，
         * 是为了让"两处必须同步"有一个明确的锚点。
         *
         * 为什么默认经典而非跟随系统：见 `Keys.DEV_GAME_CHROME_MODE` 的说明——
         * 默认值必须是"没配过的人也看不出变化"的那一档。
         */
        const val DEFAULT_GAME_CHROME_MODE = "dark_classic"

        // ⚠️ 原 `MAX_SPLASH_JITTER_MS` / `SPLASH_JITTER_STEP_MS` 随需求 8 一并删除
        // （说明见 `Keys` 里那条注释）。

        // ---- 课程提醒（v1.1.79 需求 2）----------------------------------------

        /**
         * 课程提醒的三个提前量（分钟）：**开始时 / 10 分钟前 / 1 小时前**。
         *
         * 需求原文就这三档，因此这里用**白名单集合**而不是任意整数：
         * 读取侧会用它做合法值校验，遇到脏数据（手改 DataStore / 旧版本残留）
         * 自动回落默认值，而不是把 17 分钟这种值一路传进 AlarmManager。
         */
        val TIMETABLE_LEAD_OPTIONS = listOf(0, 10, 60)

        /** 课程提醒默认提前量：10 分钟（理由见 [Keys.TIMETABLE_REMINDER_LEAD]）。 */
        const val DEFAULT_TIMETABLE_LEAD = 10
    }
}
