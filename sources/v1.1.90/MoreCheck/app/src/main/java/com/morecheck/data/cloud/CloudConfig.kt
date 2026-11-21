package com.morecheck.data.cloud

/**
 * 云服务的公开配置。
 *
 * ## 这两个常量为什么可以、而且必须写在源码里
 *
 * 它们来自 WorkBuddy 云服务激活时返回的 `publicConfig`：
 * - `endpoint`：本应用数据平面的基地址；
 * - `publishableKey`：标识**哪个应用**，本身不带任何权限（真正的权限来自登录后的
 *   会话令牌，服务端对每个请求做行级安全校验）。
 *
 * 官方约定里 `publishableKey` 明确「可以放在应用源码中」（它出现在浏览器里本就是公开的），
 * 但**不要**把它写进日志。`endpoint` 对原生应用没有「运行时配置」这一说——Web 应用可以
 * 从 `publicConfig` 动态取，而 APK 里的唯一来源就是编译期常量，因此集中到本对象，
 * 将来若云端环境变更（重新激活 / 换域名），只改这两个字面量。
 *
 * ## 为什么路径也在这里拼好
 *
 * 数据平面路径是平台固定的（见云服务文档）：Auth 在 `/.cloud/auth/v1` 之下，
 * Database 是 PostgREST 风格、在 `/.cloud/database/rest` 之下，
 * Storage 在 `/.cloud/storage` 之下。三套客户端都从本对象取基地址，
 * 避免同一路径字面量散落在四个文件里。
 *
 * ⚠️ **注释里不要写「斜杠紧跟两个星」结尾的路径**（形如在某路径后再接两个星）：
 * Kotlin 的块注释是**可嵌套**的——那种斜杠加星的序列会被词法分析器当成嵌套注释的
 * 开头，于是整段 KDoc 永远闭合不了，报错还落在文件末尾的下一行（"Unclosed
 * comment" 指向一个并不存在的行号），极难定位。本文件因此一律用"在某路径之下"
 * 的说法；`AUTH_BASE` / `DB_BASE` / `STORAGE_BASE` 三个常量本身就是权威路径。
 */
object CloudConfig {

    /** 数据平面基地址（publicConfig.endpoint）。 */
    const val ENDPOINT = "https://morecheck-sync.app.workbuddy.host"

    /** 应用标识（publicConfig.publishableKey）。无权限，仅标识应用。 */
    const val PUBLISHABLE_KEY =
        "wbpk_pQB4MT3Hax2MFbWqH3Xj3t_t00dgUklIh0YkkkzDq1LaCh7jib8AhAn"

    /** 认证模块基地址（手机号验证码登录 / 会话刷新 / 用户信息）。 */
    const val AUTH_BASE = "$ENDPOINT/.cloud/auth"

    /** 数据库模块基地址（PostgREST）。 */
    const val DB_BASE = "$ENDPOINT/.cloud/database/rest"

    /** 存储模块基地址（整包备份 JSON / 记事本图片）。 */
    const val STORAGE_BASE = "$ENDPOINT/.cloud/storage"

    /** 存储桶名。平台只暴露这一个逻辑桶（`runtime`）。 */
    const val STORAGE_BUCKET = "runtime"

    /**
     * 连接超时（毫秒）。
     *
     * 与 `WeatherRepository` 的 10 秒不同：这里要传的可能是一整包备份 JSON
     * （含记事本图片时可达数 MB），弱网下建连后传数据的时间更久，
     * 因此连接阶段给 15 秒、读取阶段给 30 秒（见 [READ_TIMEOUT_MS]）。
     */
    const val CONNECT_TIMEOUT_MS = 15_000

    /** 读取超时（毫秒）。上传 / 下载大文件时服务器可能长时间不回字节。 */
    const val READ_TIMEOUT_MS = 30_000

    /**
     * 会话自动刷新窗口（毫秒）。
     *
     * 距过期不足 90 秒时就提前刷新，而不是等令牌真的过期：
     * 否则一个「刚好在过期边界上发起」的同步请求会用旧令牌被服务端拒绝，
     * 虽然 401 重试能兜住，但那次请求已经白跑一趟。90 秒与官方 SDK 的
     * `SessionManager` 刷新窗口一致。
     */
    const val REFRESH_WINDOW_MS = 90_000L
}
