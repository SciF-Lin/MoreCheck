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

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            taskId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("该打卡啦 $icon")
            .setContentText("「$taskName」等你打卡，坚持就是胜利！")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(taskId.toInt(), notification)
        } catch (_: SecurityException) {
            // Notification permission denied — silently skip.
        }
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
     * [showReminder] 与 [notifyTestHelloWorld] 仍留在 [CHANNEL_REMINDER]，
     * 因此本次改动不影响打卡提醒。
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
     * 不带 `contentIntent`：它只是一条测试消息，点开没有该去的地方；
     * `autoCancel` 仍然打开，点一下或划掉即可消失。
     *
     * @return 通知是否真的发出去了；`false` = 系统没授予通知权限，被拦下了。
     */
    fun notifyTestHelloWorld(): Boolean {
        if (!isPostNotificationGranted()) return false

        val notification = NotificationCompat.Builder(appContext, CHANNEL_REMINDER)
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
}
