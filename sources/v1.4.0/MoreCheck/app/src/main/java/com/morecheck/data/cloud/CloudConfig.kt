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
    const val ENDPOINT = "https://morecheck.app.workbuddy.host"

    /** 应用标识（publicConfig.publishableKey）。无权限，仅标识应用。 */
    const val PUBLISHABLE_KEY =
        "wbpk_2KA020VX3CkCXSDkX332mn_H6ASAdRoRnjV7bIqf8XKJVxMaitI5sch"

    /** 认证模块基地址（手机号验证码登录 / 会话刷新 / 用户信息）。 */
    const val AUTH_BASE = "$ENDPOINT/.cloud/auth"

    /** 数据库模块基地址（PostgREST）。 */
    const val DB_BASE = "$ENDPOINT/.cloud/database/rest"

    /** 存储模块基地址（整包备份 JSON / 记事本图片）。 */
    const val STORAGE_BASE = "$ENDPOINT/.cloud/storage"

    /** 存储桶名。平台只暴露这一个逻辑桶（`runtime`）。 */
    const val STORAGE_BUCKET = "runtime"

    // ---- 微信登录接缝（v1.2.4，客户端先行） ---------------------------------
    //
    // ## 为什么这里有接缝
    //
    // BaaS 认证模块的微信登录目前只覆盖两种形态：网页（微信扫码 / 快捷登录，
    // OAuth 回调必须落在应用的 HTTPS 发布域名）与微信小程序（wx.login 的 code）。
    // **原生 Android App 不在支持列表**，且平台的开放契约明确不含
    // provider bind/unbind（身份绑定）——「微信身份关联手机号」没有服务端接口。
    //
    // 因此客户端先把整套流程做出来（状态检测、授权发起的落点、绑定手机号的
    // 完整界面与真实短信发码），服务端三件事落地后**零 UI 改动**即可闭环：
    // ① 平台支持原生微信接入（或自建 code 换会话接口）；
    // ② 微信开放平台移动应用审核通过，拿到 AppID 填进 [WECHAT_APPID]；
    // ③ 绑定端点定稿后对齐 [WECHAT_SIGNIN_PATH] / [WECHAT_BIND_PHONE_PATH]。
    //
    // 接缝在 [WECHAT_APPID] 非空之前全部保持"诚实不可用"：
    // 界面明确提示「暂未开通」，绝不假装成功、也不静默换登录方式。

    /**
     * 微信开放平台移动应用的 AppID。
     *
     * **空串 = 未开通**（当前状态）：登录页的微信入口会显示"暂未开通"，
     * 不会发起任何微信请求。审核通过后把 AppID 填在这里，
     * [com.morecheck.data.cloud.WeChatAuthGateway] 的授权发起即刻生效。
     */
    const val WECHAT_APPID = ""

    /**
     * 微信授权登录端点（**草案路径，服务端尚未实现**）。
     *
     * 请求体草案：`{"wechat_code": "<OpenSDK 拿到的 code>", "appid": [WECHAT_APPID]}`；
     * 响应体：会话（同 `signin`）；当该微信身份未关联手机号时，
     * 约定返回错误码 `wechat_phone_not_linked` + 一次性登录票据 `login_ticket`，
     * 客户端凭票据走 [WECHAT_BIND_PHONE_PATH]。**后端定稿后以服务端契约为准**，
     * 调整点只在 [com.morecheck.data.cloud.CloudAuthClient] 的两个微信方法里。
     */
    const val WECHAT_SIGNIN_PATH = "$AUTH_BASE/v1/wechat/signin"

    /**
     * 微信身份绑定手机号端点（**草案路径，服务端尚未实现**）。
     *
     * 请求体草案：`{"login_ticket": ..., "verification_id": ..., "verification_code": ...}`
     * ——验证码校验复用既有真实接口（`v1/verification` 系），绑定提交时把
     * 一次性票据与已通过的验证会话一起交给服务端，由服务端把微信身份挂到
     * 该手机号账号下并返回会话（手机号主码约定，2026-10-31）。
     */
    const val WECHAT_BIND_PHONE_PATH = "$AUTH_BASE/v1/wechat/bind-phone"

    /**
     * 「正式版安装包」在存储里的候选目录（本轮需求 2，按顺序尝试）。
     *
     * ## 为什么是列表而不是一个常量
     *
     * 发布目录取决于**上传账号的权限**（两者都能被登录用户读，只有 owner 能写）：
     * - `shared/_application/apk`：应用身份目录，需要管理员权限；
     * - `shared/<发布账号 uid>/apk`：普通账号目录，发布者自己就能传。
     *
     * 实测（2026-10-31，`tools/upload_release_apk.py`）走的是后者：
     * 发布账号 uid `2112910697389096960`。客户端不判断"该用哪个"，
     * 逐个试、谁有内容用谁——将来换成管理员目录上传也不用来改这里。
     */
    val RELEASE_APK_PREFIXES: List<String> = listOf(
        "shared/_application/apk",
        "shared/2112910697389096960/apk"
    )

    /**
     * 随包正式版清单的文件名（`assets/` 下），见 `ReleaseApkRepository.fetchPublicManifest`。
     *
     * 平台云存储不开放匿名读，未登录时的正式版列表只能来自随包资源；
     * 发布正式版时由 `tools/upload_release_apk.py` 重新生成这份文件。
     */
    const val PUBLIC_RELEASE_ASSET = "public_releases.json"

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
