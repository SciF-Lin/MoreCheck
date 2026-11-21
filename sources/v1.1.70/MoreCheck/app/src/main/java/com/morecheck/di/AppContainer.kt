package com.morecheck.di

import android.content.Context
import com.morecheck.data.AiChatRepository
import com.morecheck.data.AiChatSettingsStore
import com.morecheck.data.AuthRepository
import com.morecheck.data.BackupRepository
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.CheckDatabase
import com.morecheck.data.DeveloperOptionsRepository
import com.morecheck.data.SettingsRepository
import com.morecheck.data.TaskRepository
import com.morecheck.MoreCheckApp
import com.morecheck.reminder.NotificationHelper
import com.morecheck.reminder.ReminderScheduler

/** Manual dependency container (no reflection framework needed). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: CheckDatabase = CheckDatabase.get(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val authRepository = AuthRepository(settingsRepository)
    val taskRepository = TaskRepository(database.checkDao(), settingsRepository)
    val backupRepository = BackupRepository(database.checkDao(), settingsRepository)
    val notificationHelper = NotificationHelper(appContext)
    val reminderScheduler = ReminderScheduler(appContext, taskRepository, notificationHelper)

    /**
     * 「开发者选项」。
     *
     * 放在 `AppContainer` 里 = **进程内单例**：验证码测试生成的六位码就存在它的字段里，
     * 登录 / 注册界面与「发送验证码」按钮拿到的是同一个实例，因此比对才有意义。
     * 若改成每次新建实例，验证码会「刚发就失效」。
     */
    val developerOptionsRepository =
        DeveloperOptionsRepository(settingsRepository, notificationHelper)

    /**
     * 「智能体」（AI 对话）。
     *
     * 设置存储走独立的 DataStore 文件（`ai_chat_settings`），与 [settingsRepository] 的
     * `settings` 分开：AI 的 Key / 模型 / 提示词是成组读写、整包覆盖保存的低频配置，
     * 与打卡设置那类「单项开关」的读写模式不同，放同一份 DataStore 里容易互相踩。
     */
    val aiChatSettingsStore = AiChatSettingsStore(appContext)
    val aiChatRepository = AiChatRepository(aiChatSettingsStore)

    /**
     * 云端会话（手机号验证码 / 密码登录）。
     *
     * 进程内单例：登录页、密码登录页、注册页拿到的必须是同一个实例——
     * 会话状态、刷新单飞（mutex）都挂在它身上，多实例会让「登录了但另一页还是未登录」。
     */
    val cloudAuthClient = CloudAuthClient(appContext)
}

/**
 * 从任意 Context 取容器。
 *
 * 必须走 `applicationContext` 再转型：Compose 里 `LocalContext` 通常是 Activity，
 * 直接 `as MoreCheckApp` 会抛 ClassCastException。
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as MoreCheckApp).container
