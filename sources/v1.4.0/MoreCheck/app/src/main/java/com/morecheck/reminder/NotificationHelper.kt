package com.morecheck.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morecheck.MoreCheckApp
import com.morecheck.MainActivity
import com.morecheck.R
import com.morecheck.ui.nav.AppTab
import com.morecheck.util.AppPermission
import com.morecheck.util.AppPermissions

/**
 * 通知构建与发送。
 *
 * **通知总开关**在「设置 → 通用 → 权限管理」（默认关）：
 * [showReminder] 自己会先过一遍 `开关打开 && 系统已授权` 门控，
 * 这样即便以后多出别的调用方（不只 [ReminderReceiver]），也不会绕过用户的开关——
 * 门控放在最后一环，而不是依赖每个调用方自觉。
 *
 * 因此 [showReminder] 是 `suspend`：读开关要走 DataStore。
 */
class NotificationHelper(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 应用级容器。**延迟到调用时**才取，不能在构造函数里取：
     * 本对象正是由 `AppContainer` 构造出来的，构造期间 `MoreCheckApp.container` 还没赋值。
     */
    private val appContainer get() = (appContext as? MoreCheckApp)?.container

    companion object {
        /**
         * 「打卡提醒」频道 id。
         *
         * ⚠️ **绝对不要改这个值**。频道 id（连同它所属包名）是系统设置里那条通知偏好的主键：
         * 一旦改动，系统会把它当成一个**全新频道**，用户此前为「打卡提醒」调过的
         * 重要级别 / 声音 / 免打扰设置会**全部丢失并回到默认**，而旧频道会作为一条
         * 无主的僵尸项残留在系统设置里。改显示名同理有风险（用户认的就是那个名字）。
         */
        const val CHANNEL_REMINDER = "reminder_channel"

        /**
         * 「验证码（测试）」频道 id（需求：通知类别增加「验证码（测试）」）。
         *
         * ## 为什么要**单开**一个频道，而不是继续复用 [CHANNEL_REMINDER]
         *
         * 此前 [notifyDevVerificationCode] 走的就是「打卡提醒」频道，于是系统设置里
         * 只出现一项，用户没法单独关掉验证码测试的提示音——而验证码属于
         * 「用户当下正在等、希望立刻看到」的强打扰通知，打卡提醒则是每日例行，
         * 两者该由用户各自决定。分频道正是 Android 8.0 引入通知频道的目的。
         *
         * id 用 `dev_code_test` 而不是 `reminder_channel` 的变体：两者语义上毫不相干，
         * 用前缀看不出关系的名字，将来才不会有人误以为是同一类而去合并。
         */
        const val CHANNEL_DEV_CODE = "dev_code_test"

        /**
         * 「测试通知」频道 id（需求：通知分类按消息类型归类）。
         *
         * ## 为什么把「通知测试」从 [CHANNEL_REMINDER] 挪到独立频道
         *
         * 此前 [notifyTestHelloWorld]（开发者选项 → 登录测试 → 通知测试）借用了
         * 「打卡提醒」频道，于是用户在系统设置里会看到：一条明明是**调试用**的
         * Hello World 通知，被归在「打卡提醒」这个**日常提醒**类别下面——
         * 两者消息类型完全不同（一个是每日例行提醒，一个是用户当场主动触发的测试），
         * 混在一类里既让用户在系统设置里难以理解，也没法单独调它的响铃级别。
         *
         * 本轮按消息类型重整类别：**提醒类**（[CHANNEL_REMINDER]）、
         * **凭据类**（[CHANNEL_DEV_CODE]，验证码）、**测试类**（本频道）各归各位。
         *
         * ⚠️ id 用 `test_notifications` 这个新值：频道 id 是系统偏好的主键
         * （见 [CHANNEL_REMINDER] 的警告），**不要**复用或改名既有频道；
         * 新建独立频道后，旧通知继续走旧频道，互不影响。
         */
        const val CHANNEL_TEST = "test_notifications"

        /**
         * 开发者选项「验证码测试」通知的固定 id。
         *
         * 用**固定 id**（而不是自增值）是有意的：连点两次「发送验证码」时，
         * 后一条会**覆盖**前一条，通知栏里不会堆一排验证码。
         */
        const val DEV_CODE_NOTIFICATION_ID = 0x7E57

        /**
         * 开发者选项「通知测试」通知的固定 id。
         *
         * 与 [DEV_CODE_NOTIFICATION_ID] 取不同的值：两者可能同时存在
         * （先做验证码测试、再做通知测试），共用一个 id 会让后发的把前一条顶掉。
         */
        const val TEST_NOTIFICATION_ID = 0x7E58

        /** 「通知测试」的标题：说明这条通知是谁发的。 */
        const val TEST_NOTIFICATION_TITLE = "通知测试"

        /** 「通知测试」的正文（需求指定：内容为 `Hello World`）。 */
        const val TEST_NOTIFICATION_TEXT = "Hello World"

        /**
         * 「计时器」频道的 id（需求 15：启动计时后通知栏显示计时器）。
         *
         * 单开频道（不复用 [CHANNEL_REMINDER]）的理由与 [CHANNEL_TEST] 相同：
         * 计时是**用户主动发起的临时会话状态**，与每日例行提醒是两类消息；
         * 且计时通知要求 `IMPORTANCE_LOW`（静默常驻、每秒更新，响铃/横幅会发疯），
         * 而打卡提醒是 `IMPORTANCE_HIGH`——级别不同也必须分频道。
         */
        const val CHANNEL_TIMER = "timer_channel"

        /**
         * 「计时器」常驻通知的固定 id（需求 15）。
         *
         * 剩余时间每秒变化，更新 = 用同一 id 重发覆盖；固定 id 保证
         * 「开始 / 更新 / 取消」操作的是通知栏里同一条。
         */
        const val TIMER_NOTIFICATION_ID = 0x7E59

        /**
         * 「课程提醒」频道 id（v1.1.79 需求 2）。
         *
         * 单开频道的理由与 [CHANNEL_TEST] / [CHANNEL_TIMER] 相同：课程提醒是
         * **按课表定时**的教学类提醒，与「每日打卡提醒」是两类消息，
         * 用户很可能想保留上课提醒、关掉打卡提醒（或反之）。
         * 频道 id 是系统偏好的主键，**不要复用或改名既有频道**。
         */
        const val CHANNEL_COURSE = "course_reminder_channel"

        /**
         * 课程提醒通知的 id 基数。
         *
         * 每节课的提醒用 `基数 + 课程开始时间的小时*60+分钟` 做 id：
         * 同一时刻的课（极少见）会互相覆盖，但那正是用户想看到的效果
         * （同一时间只提示一次），不会堆一排通知。
         */
        const val COURSE_NOTIFICATION_ID_BASE = 0x7C00

        /**
         * 「漏打卡补推」通知的 id 基数。
         *
         * 与 [showReminder] 直接拿 taskId 当 id 的那批错开：主提醒那条可能还挂在
         * 通知栏，补推必须换一个 id 才是「再发一条」，否则会静默把「该打卡啦」
         * 顶掉，用户反而看不到「还没打卡」这层递进。基数取远大于任务 id 与
         * 课程提醒基数的常量，保证两套通知 id 不会撞号。
         */
        const val FOLLOWUP_NOTIFICATION_ID_BASE = 1_000_000
    }

    init {
        createChannels()
    }

    /**
     * 建立本应用的全部通知频道。
     *
     * ## 为什么必须在这里（构造期）就把**所有**频道建出来
     *
     * Android 8.0+ 要求频道先被 `createNotificationChannel` **显式注册**，
     * 之后用该 id 发通知才会出现在系统设置的通知类别列表里。
     * 若某个频道只在真正发通知时才顺手创建，那么在用户**从没触发过**那个功能之前
     * （例如从没点过开发者选项里的「发送验证码」），系统设置里就看不到这一项——
     * 需求要求的「通知类别增加『验证码（测试）』」也就无从谈起。
     * 本对象由 `AppContainer` 在进程启动时构造，构造期建频道等于**应用一装上就注册好**，
     * 与用户是否用过该功能无关。
     *
     * ## 重复调用是安全的
     *
     * `createNotificationChannel` 同 id 重复调用属幂等操作：系统只更新
     * 「名称 / 描述」这类元信息，**不会**覆盖用户已经改过的重要级别、提示音等偏好。
     * 所以本函数每次进程启动都全量跑一遍没有问题，不需要额外的「是否已建过」判断。
     */
    private fun createChannels() {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 「打卡提醒」：id / 名称 / 重要级别三样都**保持原样不动**（理由见 CHANNEL_REMINDER 的注释）。
        val reminderChannel = NotificationChannel(
            CHANNEL_REMINDER,
            "打卡提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "每日打卡提醒"
        }
        manager.createNotificationChannel(reminderChannel)

        // 「验证码（测试）」：与提醒频道并列的第二项通知类别。
        //
        // 重要级别取 IMPORTANCE_HIGH 而不是 DEFAULT：验证码是**有时效、当场要用**的凭据，
        // 用户点完「发送验证码」就在等它。DEFAULT 只在通知栏静默出现、不弹横幅也不响，
        // 用户很可能要下拉通知栏才发现，与「模拟真实短信验证码」的意图不符。
        // 注意这个级别同样只在**首次创建**时生效——用户之后在系统设置里调低，系统会尊重用户的选择。
        val devCodeChannel = NotificationChannel(
            CHANNEL_DEV_CODE,
            "验证码（测试）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // 描述写清来源与用途：用户是在**系统设置**里看到它的，
            // 那个场景下「验证码测试」四个字很容易被误读成真实业务（比如以为应用在收短信），
            // 必须说明这是开发者选项里的调试功能，关了它不影响任何真实功能。
            description = "开发者选项「验证码测试」发送的六位验证码，仅用于本机调试，可随时关闭"
        }
        manager.createNotificationChannel(devCodeChannel)

        // 「测试通知」：与上面两类并列的第三个通知类别（按消息类型归类，本轮需求）。
        //
        // 重要级别取 IMPORTANCE_HIGH：测试通知的用途就是让用户**当场**确认
        // 「本机能不能收到通知」，若用 DEFAULT 静默出现，用户还得去下拉通知栏找，
        // 与「点了按钮马上看到」的意图不符。同样只在首次创建时生效，
        // 用户之后在系统设置里调低，系统会尊重用户的选择。
        val testChannel = NotificationChannel(
            CHANNEL_TEST,
            "测试通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            // 与「验证码（测试）」的描述同一口径：讲清来源是开发者选项里的调试功能，
            // 免得用户在系统设置里把它误读成真实业务。
            description = "开发者选项「通知测试」发送的 Hello World 通知，仅用于本机调试，可随时关闭"
        }
        manager.createNotificationChannel(testChannel)

        // 「计时器」：与上面三类并列的第四个通知类别（需求 15）。
        //
        // 重要级别刻意取 **IMPORTANCE_LOW**：这条通知是计时期间**每秒更新**的常驻条目，
        // HIGH 会在每次更新时发声/横幅——通知栏每秒响一次是灾难；LOW 静默出现、
        // 不打扰，只把状态留在通知栏（以及 MIUI 的超级岛/灵动岛式展示）里。
        val timerChannel = NotificationChannel(
            CHANNEL_TIMER,
            "计时器",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "计时进行中在通知栏显示剩余时间；计时结束或手动停止后自动移除"
        }
        manager.createNotificationChannel(timerChannel)

        // 「课程提醒」：与上面四类并列的第五个通知类别（v1.1.79 需求 2）。
        //
        // 重要级别取 IMPORTANCE_DEFAULT 而不是 HIGH：上课提醒通常用户在手机旁，
        // 通知栏可见 + 提示音即可；HIGH 会在部分 ROM 上全屏横幅打断正在做的事，
        // 一节课提醒没必要强打扰。用户想更响可在系统设置里自己调高。
        val courseChannel = NotificationChannel(
            CHANNEL_COURSE,
            "课程提醒",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "按课程表在上课前提醒（开始时 / 10 分钟前 / 1 小时前三档，可在课程表页关闭）"
        }
        manager.createNotificationChannel(courseChannel)
    }

    /**
     * 是否允许发通知：**应用开关打开 且 系统已授权**。
     *
     * 只看其中一个都不行——开关开了但系统没给（Android 13+），发出去也是被静默丢掉；
     * 系统给了但用户把开关关了，那是用户明确的「不要推送」意图，必须尊重。
     * 取不到容器时按「不允许」处理：宁可少发一条，也不要违反用户意图。
     */
    suspend fun isNotificationAllowed(): Boolean {
        val repository = appContainer?.settingsRepository ?: return false
        val toggleEnabled = repository.isPermissionEnabled(AppPermission.NOTIFICATION.id)
        return AppPermissions.isUsable(appContext, AppPermission.NOTIFICATION, toggleEnabled)
    }

    /**
     * 弹出提醒通知。**调用方无需自己判断开关**，本函数内部已做门控。
     *
     * 注意：只是「不弹通知」并不会停掉提醒链条，
     * 续排次日闹钟由 [ReminderReceiver] 无条件负责（见其注释）。
     */
    suspend fun showReminder(taskId: Long, taskName: String, icon: String) {
        if (!isNotificationAllowed()) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("该打卡啦 $icon")
            .setContentText("「$taskName」等你打卡，坚持就是胜利！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(reminderContentIntent(taskId))
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(taskId.toInt(), notification)
        } catch (_: SecurityException) {
            // 通知权限在门控与发送之间被撤销——静默跳过，提醒链条由接收器续排。
        }
    }

    /**
     * 「漏打卡补推」通知：主提醒发出后若到点仍未打卡，再发这一条。
     *
     * 文案刻意点明「还没打卡」，与 [showReminder] 的「该打卡啦」区分开——
     * 用户隔了几十分钟看到第二条时，需要立刻明白这是补一次提醒而不是重复推送。
     * 点击行为与 [showReminder] 完全一致（见 [reminderContentIntent]），
     * 都直达打卡首页，免得两条通知把用户带去两个不同地方。
     *
     * 同样走 [isNotificationAllowed] 门控：用户在主提醒之后关掉了通知总开关，
     * 补推也必须尊重，不能绕过开关硬发。
     */
    suspend fun showFollowUpReminder(taskId: Long, taskName: String, icon: String) {
        if (!isNotificationAllowed()) return

        val notification = NotificationCompat.Builder(appContext, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("还没打卡哦 $icon")
            .setContentText("「$taskName」今天还没打卡，现在补上还来得及！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(reminderContentIntent(taskId))
            .build()

        try {
            NotificationManagerCompat.from(appContext)
                .notify(FOLLOWUP_NOTIFICATION_ID_BASE + taskId.toInt(), notification)
        } catch (_: SecurityException) {
            // 通知权限在门控与发送之间被撤销——静默跳过。
        }
    }

    /**
     * 两条打卡提醒共用的点击 Intent：拉起 MainActivity 并切到 [AppTab.HOME]。
     *
     * 用户点通知的目的是赶紧打卡，而不是回到上次离开的页面；由 MainActivity 的
     * ACTION_NAV_TAB 通道统一消费（前后台都生效，见 tabTargetOf 的接线）。
     * 保留 NEW_TASK 与 CLEAR_TOP、以及 taskId 做 requestCode：前者防止把主页叠成
     * 多实例，后者保证不同任务的通知互不覆盖。
     */
    private fun reminderContentIntent(taskId: Long): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_NAV_TAB
            putExtra(MainActivity.EXTRA_TAB_TARGET, AppTab.HOME.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            appContext,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 开发者选项「验证码测试」：把随机生成的六位验证码用通知发给用户。
     *
     * **不走 `isNotificationAllowed()` 门控**：那道门控读的是「设置 → 权限管理 → 通知」开关，
     * 属于**用户日常提醒**的偏好；而这里是用户主动打开开发者选项、主动点了「发送验证码」
     * 才触发的调试反馈。若还要先去过那道开关，会出现「我明明开了验证码测试却收不到码」
     * 的困惑——调试功能的行为必须**可预期**。
     *
     * 但系统级授权仍然要检查（Android 13+ 未授权时 `notify` 会被静默丢弃），
     * 因此这里在发送前查一次 `POST_NOTIFICATIONS`，未授权就直接返回。
     * 界面侧的「发送验证码」会据 [isPostNotificationGranted] 提示用户去授权。
     *
     * ## 频道用 [CHANNEL_DEV_CODE]，不再借用「打卡提醒」
     *
     * 需求：通知类别里要能看到「验证码（测试）」这一项。
     * 频道是做在**发送处**的（`NotificationCompat.Builder` 的第二个参数），
     * 只有这条通知改走新频道，系统设置里才会出现对应的通知类别；
     * 频道本身已在构造期由 [createChannels] 注册好。
     * [showReminder] 仍留在 [CHANNEL_REMINDER]、[notifyTestHelloWorld] 走 [CHANNEL_TEST]，
     * 因此本次改动不影响打卡提醒与通知测试。
     */
    fun notifyDevVerificationCode(code: String) {
        if (!isPostNotificationGranted()) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            DEV_CODE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_DEV_CODE)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("验证码测试")
            // 需求：模拟真实短信场景，因此把六位数字直接写在通知里，
            // 用户从通知栏读到再填回输入框。
            .setContentText("验证码：$code")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText("验证码：$code（开发者选项 · 验证码测试）")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(DEV_CODE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // 权限在检查与发送之间被撤销——忽略即可，调用方仍能从界面上的提示得知。
        }
    }

    /**
     * 开发者选项「登录测试 → 通知测试」：立刻发一条**内容为 `Hello World`** 的通知。
     *
     * 这个按钮的用途就是让用户当场确认「本机到底能不能收到本应用的通知」，
     * 所以它的行为刻意与别的通知不同：
     * - **不走**应用内的「通知」总开关（那道开关管的是每日提醒偏好，见 [showReminder]）；
     * - 但**必须**检查系统级 `POST_NOTIFICATIONS`，且**不能静默失败**——
     *   未授权时返回 false，由界面明说「通知权限没开」，
     *   否则用户只会以为应用坏了，而不是去系统设置里打开权限。
     *
     * ## 频道用 [CHANNEL_TEST]，不再借用「打卡提醒」
     *
     * 需求：通知分类按消息类型归类。这条通知是**测试消息**，与每日打卡提醒
     * 是两类东西，此前借用 [CHANNEL_REMINDER] 会让系统设置里「打卡提醒」类别下
     * 混进一条调试通知（详见 [CHANNEL_TEST] 的 KDoc）。频道已在构造期注册好。
     *
     * 不带 `contentIntent`：它只是一条测试消息，点开没有该去的地方；
     * `autoCancel` 仍然打开，点一下或划掉即可消失。
     *
     * @return 通知是否真的发出去了；`false` = 系统没授予通知权限，被拦下了。
     */
    fun notifyTestHelloWorld(): Boolean {
        if (!isPostNotificationGranted()) return false

        val notification = NotificationCompat.Builder(appContext, CHANNEL_TEST)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(TEST_NOTIFICATION_TITLE)
            .setContentText(TEST_NOTIFICATION_TEXT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(TEST_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // 权限在检查与发送之间被撤销——按「没发出去」如实返回，界面会给出提示。
            false
        }
    }

    /**
     * 系统是否已授予通知权限（Android 13+ 的 `POST_NOTIFICATIONS`）。
     *
     * API < 33 时该权限不存在，`AppPermissions` 把 null 视为已授权，这里保持一致。
     */
    fun isPostNotificationGranted(): Boolean =
        AppPermissions.isUsable(
            appContext,
            AppPermission.NOTIFICATION,
            toggleEnabled = true
        )

    // ── 计时器常驻通知（需求 15）────────────────────────────────────────────

    /**
     * 显示 / 更新「计时进行中」的常驻通知（需求 15：启动计时后通知栏显示计时器）。
     *
     * ## 为什么静默走系统授权、不走应用内总开关
     *
     * 与 [notifyTestHelloWorld] 同一口径：这是用户**主动按下「开始」**触发的状态条，
     * 不是应用发起的推送；若还要先过「权限管理 → 通知」开关，会出现
     * 「我明明开始了计时、锁屏却看不到还剩多久」的困惑。系统级授权仍要查
     * （Android 13+ 未授权时 `notify` 被静默丢弃，本函数如实返回 false 供界面提示）。
     *
     * ## 每秒重发的成本
     *
     * `notify` 同 id 重发是**原地更新**（系统不重播声音、不重新弹出），
     * 一次本地通知构建 + Binder 调用，每秒一次的成本可忽略；
     * 用 `setOnlyAlertOnce(true)` 双保险（部分 ROM 对重发的首响有自己的脾气）。
     *
     * ## 小米超级岛（焦点通知）适配
     *
     * HyperOS 的"焦点通知 / 超级岛"会自动识别带**计时时长语义**的常驻通知
     * （`setUsesChronometer(true)` + `setWhen(结束时刻)` 是标准做法：
     * 系统据此把通知渲染成倒计时胶囊）。这里显式设置：
     * - `setUsesChronometer(true)` + `setWhen(endAtMillis)` + `setChronometerCountDown(true)`
     *   （API 24+ 支持倒计时方向）：系统自己每秒跳字，**不需要**调用方每秒重发——
     *   每秒重发反而会让部分 ROM 重置计时器动画；
     * - `setCategory(NotificationCompat.CATEGORY_STOPWATCH)`：超级岛的识别依据之一；
     * - `setOngoing(true)`：计时中的条目不可划掉（划掉 ≠ 停止计时，必须防误删）。
     *
     * 因此调用方**只需在"开始 / 继续"时调一次**本函数；暂停 / 停止 / 结束时
     * 调 [cancelTimerNotification]。
     *
     * @param endAtMillis 计时结束时刻（`SystemClock.elapsedRealtime()` 基准）。
     *   系统计时器按这个时刻自动倒数。
     * @param totalMillis 本轮总时长（毫秒）。保留给将来加进度条时使用；
     *   当前通知不显示进度（超级岛的倒计时胶囊已足够）。
     * @return 通知是否真的发出去了；false = 系统未授予通知权限。
     */
    fun showTimerNotification(endAtMillis: Long, totalMillis: Long): Boolean {
        if (!isPostNotificationGranted()) return false

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            TIMER_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ⚠️ `setWhen` 期望的是**墙钟毫秒**（`System.currentTimeMillis()` 基准），
        // 而计时器内部用的是单调时钟（`elapsedRealtime()`，见 `TimerState` 的注释）。
        // 两者不能混用：直接把 elapsedRealtime 传给 setWhen 会显示成 1970 年附近。
        // 换算：墙钟结束时刻 = 现在的墙钟 + 单调剩余。两者基准不同但"差值"同为剩余时长，
        // 这一步换算是唯一的正确写法。
        val remainingMillis = (endAtMillis - android.os.SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        val wallClockEnd = System.currentTimeMillis() + remainingMillis

        val notification = NotificationCompat.Builder(appContext, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("计时进行中")
            // 文本行给一个静态说明；跳秒由系统计时器（chronometer）负责，
            // 不要在这里拼剩余时间——那需要每秒重发，与"发一次就够"的设计相反。
            .setContentText("剩余时间见倒计时；点按返回应用")
            .setWhen(wallClockEnd)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(TIMER_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * 取消「计时器」常驻通知（暂停 / 停止 / 到点 / 恢复默认时调用）。
     *
     * **无条件调用**（不查权限、不抛异常）：通知本来就不存在时 `cancel` 是幂等空操作；
     * 若这里加条件判断，调用方就要在四个时机各判一遍——必然有一处会漏。
     */
    fun cancelTimerNotification() {
        runCatching {
            NotificationManagerCompat.from(appContext).cancel(TIMER_NOTIFICATION_ID)
        }
    }

    // ── 课程提醒（v1.1.79 需求 2）───────────────────────────────────────────

    /**
     * 发一条「要上课了」的通知。
     *
     * @param courseName 课程名（如「数据结构与算法设计（C++）」）。
     * @param location 上课地点，空串时不显示。
     * @param startText 开始时刻（`HH:mm`），拼进正文让用户一眼看到几点上课。
     * @param leadMinutes 提前量：0 = 现在开始；10 / 60 = 还有多久。
     *
     * ## 门控
     *
     * 与 [showTimerNotification] / [notifyTestHelloWorld] 同一口径：**只查系统级
     * `POST_NOTIFICATIONS`**，不走「权限管理 → 通知」那个应用内总开关。
     *
     * 理由：课程表页已经有自己的「上课提醒」开关（用户打开它就是明确表态），
     * 若再叠一道全局开关，会出现"我明明开了上课提醒却收不到"的困惑。
     * 全局开关管的是打卡提醒那类**应用主动推送**的偏好；课程提醒是用户按课表
     * 亲手开启的功能，门控放在它自己的开关上更合理（排程时已判，见
     * `CourseReminderScheduler.rescheduleAll`）。
     */
    fun showCourseReminder(
        courseName: String,
        location: String,
        startText: String,
        leadMinutes: Int
    ) {
        if (!isPostNotificationGranted()) return

        val title = if (leadMinutes <= 0) {
            "现在上课：$courseName"
        } else {
            "还有 $leadMinutes 分钟上课：$courseName"
        }
        val text = buildString {
            append("${startText} 开始")
            if (location.isNotBlank()) append(" · $location")
        }

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            COURSE_NOTIFICATION_ID_BASE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // id 用「基数 + 开始时刻的分钟序号」：同一时刻下课只留一条（见常量注释）。
        val hourMinute = startText.replace(":", "").toIntOrNull() ?: 0
        val notificationId = COURSE_NOTIFICATION_ID_BASE + (hourMinute % 0x200)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$text"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // 权限在检查与发送之间被撤销——忽略。
        }
    }
}
