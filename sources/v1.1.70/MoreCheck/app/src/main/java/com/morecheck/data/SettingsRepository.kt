package com.morecheck.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.util.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

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
        val DEV_CODE_TEST_ENABLED = booleanPreferencesKey("dev_code_test_enabled")
        val DEV_PERF_TEST_ENABLED = booleanPreferencesKey("dev_perf_test_enabled")

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
        }

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

    // ---- 登录态（本地模拟，任务2）------------------------------------------

    suspend fun setLoggedIn(phone: String, mode: String) {
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = true
            it[Keys.PHONE] = phone
            it[Keys.LOGIN_MODE] = mode
        }
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
        context.dataStore.edit {
            it[Keys.LOGGED_IN] = false
            it.remove(Keys.PHONE)
            it.remove(Keys.LOGIN_MODE)
            it.remove(Keys.USER_ID)
            it.remove(Keys.NICKNAME)
            it.remove(Keys.GENDER)
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

    suspend fun setDevCodeTestEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_CODE_TEST_ENABLED] = enabled }
    }

    suspend fun setDevPerfTestEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEV_PERF_TEST_ENABLED] = enabled }
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

    companion object {
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
         * 滑块上限：10 秒 = 10000ms（需求原文「支持 0~10 秒间调节」）。
         *
         * 设上限不只是为了照需求做：这个值直接乘进开屏的 `delay`，
         * 没有上限时一个被改坏的 DataStore 就能让应用启动后「黑屏」几分钟，
         * 用户会以为应用坏了。10 秒已经是「明显很久」的量级，足够覆盖调试需要。
         */
        const val MAX_SPLASH_HOLD_MS = 10_000

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
    }
}
