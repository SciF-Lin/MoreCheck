package com.morecheck.di

import android.content.Context
import com.morecheck.MoreCheckApp
import com.morecheck.data.AiChatRepository
import com.morecheck.data.AiChatSettingsStore
import com.morecheck.data.AppIconRepository
import com.morecheck.data.AuthRepository
import com.morecheck.data.BackupRepository
import com.morecheck.data.CheckDatabase
import com.morecheck.data.DeveloperOptionsRepository
import com.morecheck.data.SettingsRepository
import kotlinx.coroutines.launch
import com.morecheck.data.TimetableRepository
import com.morecheck.data.TaskRepository
import com.morecheck.data.WeatherRepository
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudDatabaseClient
import com.morecheck.data.cloud.CloudHttp
import com.morecheck.data.cloud.CloudStorageClient
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.data.cloud.ReleaseApkRepository
import com.morecheck.data.cloud.SyncStateStore
import com.morecheck.reminder.CourseReminderScheduler
import com.morecheck.reminder.NotificationHelper
import com.morecheck.reminder.ReminderScheduler

/** Manual dependency container (no reflection framework needed). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /**
     * 进程级协程作用域（v3 云同步的异步写入口）。
     *
     * 用途：游戏数据桥（`GameDataBridge`）的 `@JavascriptInterface` 方法运行在
     * WebView 的 JavaBridge 线程上，不能直接挂 suspend 函数，需要一个长寿命的
     * 作用域把存档写投递到 IO 线程。
     *
     * 与 `MoreCheckApp` 里那个私有 `appScope` 的关系：那个管启动任务
     * （种子任务 / 重排提醒 / 会话恢复），这个管运行期写入口。刻意分开——
     * 启动任务跑完就安静了，而这里的写入口在页面存活期间随时会来。
     * 两者都**永不取消**（进程生命周期），崩溃由 SupervisorJob 隔离。
     */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    val database: CheckDatabase = CheckDatabase.get(appContext)
    val settingsRepository = SettingsRepository(appContext)
    val authRepository = AuthRepository(settingsRepository)
    val taskRepository = TaskRepository(database.checkDao(), database.timerDao(), settingsRepository)
    val backupRepository = BackupRepository(database.checkDao(), settingsRepository, appContext)
    val notificationHelper = NotificationHelper(appContext)
    val reminderScheduler = ReminderScheduler(appContext, taskRepository, notificationHelper)

    /**
     * 课程提醒排程器（v1.1.79 需求 2）。
     *
     * 依赖 [timetableRepository]（读内置课表）与 [settingsRepository]（开关 / 提前量）。
     * 因为它要读课表，所以**必须放在 `timetableRepository` 之后**声明——
     * Kotlin 属性按声明顺序初始化，提前引用会拿到 null（NPE 在启动期很难查）。
     */
    val courseReminderScheduler by lazy {
        CourseReminderScheduler(
            appContext,
            timetableRepository,
            settingsRepository,
            notificationHelper
        )
    }

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
     * 「课程表」（v1.1.75 新增）。
     *
     * 读的是内置的 `assets/timetable/curriculum.ics`，**不联网、不持久化**——
     * 与 [aiChatRepository] 那种每次都要联网的仓库不同，它只在首次访问时读一次
     * assets 并在内存里缓存（见 `TimetableRepository` 的 KDoc）。
     *
     * 放进容器而不是由界面自建：`TimetableScreen` 可能被多次进入，
     * 容器保证拿到的是**同一份缓存**，不必每次重新解析那 220 个上课实例。
     */
    val timetableRepository = TimetableRepository(appContext)

    /**
     * 「天气」（v1.1.75 收进容器）。
     *
     * ## 为什么从「页面自建」改成「容器单例」
     *
     * 此前 `WeatherScreen` 里是 `remember { WeatherRepository(context.applicationContext) }`
     * ——每次进入天气页新建一个。它当时**行为上没出错**（见下），但与
     * [timetableRepository] 的口径不一致：同样是「无用户状态的静态数据源」，
     * 一个在容器里、一个在页面里，读代码的人无法从结构上判断哪个才是规矩。
     *
     * ## 它确实无状态吗（这是能收进容器的前提）
     *
     * 是。它只持有 `context`（用于 `HttpURLConnection` 与 `org.json`），
     * 不缓存任何请求结果、不持有可变字段——所以**共用一份是安全的**。
     * 反过来说，若有页面级短命状态（比如"上一次查询的城市"），
     * 就不能这样收，那时应留在页面自己的 `remember` 里。
     */
    val weatherRepository = WeatherRepository(appContext)

    /**
     * 「应用图标」（v1.1.75 收进容器）。
     *
     * `AppIconRepository` 自己的 KDoc 早就写明「它不持有任何内存状态……因此每个页面
     * 各自 `AppIconRepository(context, settingsRepository)` 与共用一份**行为完全一致**」。
     * 既然行为一致，就没有理由让调用方每次自己 new——收到容器里之后，
     * 调用点少一行构造、也少一次"要不要 `remember`"的判断。
     *
     * 目前唯一的使用点是 `IconPickerScreen`。
     */
    val appIconRepository = AppIconRepository(appContext, settingsRepository)

    // ---- 云同步（v3）---------------------------------------------------------
    //
    // 四个云端客户端 + 同步引擎。声明顺序即依赖顺序：
    // CloudAuthClient 自己持有一个 CloudHttp（它同时是 SessionProvider），
    // 数据库 / 存储客户端复用**同一个** authClient 作为会话来源——
    // 三处必须拿到同一个实例，否则刷新令牌后数据库客户端还在用旧令牌。

    /** 云认证（手机号验证码登录、会话刷新）。界面「我的」页订阅它的 [session]。 */
    val cloudAuthClient = CloudAuthClient(appContext)

    /** HTTP 骨架（重试、错误映射、二进制收发）。无状态，可多实例；数据库 / 存储客户端共用这一个。 */
    private val cloudHttp = CloudHttp(cloudAuthClient)

    init {
        // 会话被服务端吊销（其它设备登录挤掉）时的落地链：
        // CloudHttp 401 → CloudAuthClient 清会话置过期态 → AuthRepository 清界面登录态。
        cloudHttp.onUnauthenticated = { cloudAuthClient.markSessionExpired() }
        cloudAuthClient.onExpired = {
            appScope.launch { authRepository.markSessionExpired() }
        }
    }

    /** 云数据库（结构化同步轨道的服务端）。 */
    val cloudDatabaseClient = CloudDatabaseClient(cloudHttp)

    /** 云存储（整包备份轨道的服务端）。 */
    val cloudStorageClient = CloudStorageClient(cloudHttp)

    /**
     * 正式版安装包仓库（本轮需求 2）：读云端清单 + 下载到「下载」目录 + 拉起安装器。
     *
     * 与 [cloudStorageClient] 共用同一个 `cloudHttp`，因此"登录后自动带令牌、
     * 401 自动刷新重试"这条规则对它同样成立，不必再写一遍。
     */
    val releaseApkRepository = ReleaseApkRepository(appContext, cloudStorageClient)

    /** 同步簿记（水位线 / 开关 / 上次结果）。 */
    val syncStateStore = SyncStateStore(appContext)

    /** 双轨同步引擎。登录链路、备份页、同步设置页、每日 Worker 都走它。 */
    val cloudSyncRepository = CloudSyncRepository(
        appContext = appContext,
        dao = database.checkDao(),
        auth = cloudAuthClient,
        authRepository = authRepository,
        db = cloudDatabaseClient,
        storage = cloudStorageClient,
        settingsRepository = settingsRepository,
        backupRepository = backupRepository,
        syncState = syncStateStore
    )
}

/**
 * 取本应用的依赖容器 —— **全项目唯一的取用入口**（v1.1.75）。
 *
 * ## 为什么需要这个扩展函数
 *
 * `AppContainer` 挂在 `MoreCheckApp`（Application）上，所以任何 Context 想拿到它
 * 都必须先转成 Application。此前这个转换在项目里**被手写了 30 多处**，有四种写法：
 *
 * ```kotlin
 * context.appContainer        // 16 处
 * appContext.appContainer                        // 5 处
 * applicationContext.appContainer                // 1 处（Activity 内）
 * (localContext.applicationContext as MoreCheckApp).container   // 若干
 * ```
 *
 * 四处重复的问题不只是啰嗦，而是**每条调用点都要重新犯一次同样的错**：
 * 直接写 `context as MoreCheckApp` 会抛 `ClassCastException`（Compose 里拿到的
 * `LocalContext` 通常是 Activity，不是 Application）。项目里为此在
 * `HomeScreen` / `DeveloperOptionsScreen` / `GameActivityHost` / `PersonalInfoScreen`
 * 四处各写了一段长长的"必须走 applicationContext"的注释——同一个坑解释了四遍。
 *
 * 收敛成一个扩展之后：
 * - 转换**只在这里发生一次**，调用点不可能写错（写 `context.appContainer` 就对了）；
 * - 那些解释性注释可以被本 KDoc 替代，各调用点不必再各留一份；
 * - 将来若容器换成 Hilt/Koin，只改这一个函数体。
 *
 * ## 为什么内部仍走 `applicationContext`
 *
 * 这是本函数存在的**全部意义**：调用方传进来的是 Activity / ContextWrapper 都行，
 * 这里统一 normalize 到 Application 再转，于是"Activity 不是 Application"这个坑
 * 在结构上被消除了，而不是靠每个调用点的纪律。
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as MoreCheckApp).container
