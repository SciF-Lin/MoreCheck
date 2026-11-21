package com.morecheck.data.cloud

/**
 * 云端会话。
 *
 * ## 字段为什么是这五个
 *
 * 与官方 Web SDK 的会话结构逐字段对应（`accessToken` / `refreshToken` /
 * `expiresAt` / `user`），另外多带一个 [phone]：
 * 登录时用户输入了什么号码是**调用方已知**的，而登录响应里不一定回带号码，
 * 存下来供「我的」页显示「当前账号：+86 138****8000」用，不必再查一次用户信息。
 *
 * @property accessToken 访问令牌。所有数据请求的 `Authorization: Bearer` 头。
 * @property refreshToken 刷新手柄。访问令牌过期后用它换新的（见 [CloudAuthClient.refresh]）。
 * @property expiresAt 过期时刻（epoch 毫秒，[System.currentTimeMillis] 同一时钟）。
 * @property userId 云端用户 ID（登录响应里的 `sub`）。行级安全以它为准，
 *   但客户端**从不主动发送**它——`owner_id` 由数据库默认值填写。
 * @property phone 登录时使用的手机号（已规范化为 `+86 <11 位>` 形式）。
 */
data class CloudSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val phone: String?
)

/** 云端用户信息（`GET /.cloud/auth/v1/user/me`）。 */
data class CloudUser(
    val id: String,
    val phone: String?,
    val name: String?,
    val avatarUrl: String?
)

/**
 * 云端错误。
 *
 * 用 `kind` 而不是消息文本做分支——消息是给人看的，服务端可能改措辞；
 * `kind` 与官方 SDK 的错误分类一一对应，是稳定契约。
 */
data class CloudError(
    val kind: Kind,
    val message: String,
    /** 服务端返回的错误码（如 `invalid_grant`），可能为 null。 */
    val code: String?,
    /** HTTP 状态码；网络层失败（根本没发出请求）为 0。 */
    val status: Int
) {
    enum class Kind {
        /** 请求本身不合法（参数缺漏 / 格式错）。 */
        INVALID_REQUEST,

        /** 未登录 / 会话失效 / 刷新令牌被拒。界面应引导重新登录。 */
        UNAUTHENTICATED,

        /** 已登录但无权访问（RLS 拒绝）。 */
        PERMISSION_DENIED,

        /** 资源不存在。 */
        NOT_FOUND,

        /** 触发限流（短信发送过于频繁最常见）。 */
        RATE_LIMITED,

        /** 服务端未实现该能力。 */
        UNIMPLEMENTED,

        /** 服务端暂时不可用。 */
        BACKEND_UNAVAILABLE,

        /** 网络层失败（没发出请求或没收到响应）。 */
        NETWORK,

        /** 未分类。 */
        UNKNOWN
    }
}

/** 云端调用失败的异常。所有挂起的云端方法都用它报告错误。 */
class CloudException(val error: CloudError) : Exception(error.message)

/** 把 [CloudError] 包成异常抛出（调用处少写一行 `return Result.failure`）。 */
internal fun cloudFail(error: CloudError): Nothing = throw CloudException(error)

/**
 * 云端错误 → 用户可读的中文原因。
 *
 * **界面（登录 / 同步管理）与同步引擎共用这一份**：措辞只在这里出现一次，
 * 避免同一个错误在两处给出两种说法。此前只有 `CloudSyncRepository` 有一份
 * private 的实现，登录页另写一遍必然漂移，故提升到这里。
 */
internal fun friendlyCloudMessage(error: CloudError): String {
    // 服务端部分错误（gRPC 透传）的 message 尾部带着 Go 的格式化残留
    // （如「无效的验证码%!(EXTRA string=rpc error: ...）」）——那是给人排查用的
    // 原始错误，不该念给用户听。`%!(EXTRA` 是 Go fmt 的标准坏味，从这里截断。
    val userMessage = error.message.substringBefore("%!(EXTRA").trim()
    return when (error.kind) {
        CloudError.Kind.UNAUTHENTICATED -> "登录状态已失效，请重新登录"
        CloudError.Kind.NETWORK -> "网络不稳定，请稍后重试"
        CloudError.Kind.RATE_LIMITED -> "操作太频繁，请稍后再试"
        CloudError.Kind.PERMISSION_DENIED -> "没有权限执行该操作"
        CloudError.Kind.NOT_FOUND -> "云端没有找到对应数据"
        CloudError.Kind.BACKEND_UNAVAILABLE, CloudError.Kind.UNIMPLEMENTED -> "云服务暂时不可用"
        CloudError.Kind.INVALID_REQUEST -> if (userMessage.isEmpty()) {
            "请求格式有误，请重试"
        } else {
            userMessage
        }
        CloudError.Kind.UNKNOWN -> userMessage.ifEmpty { "请求失败，请稍后重试" }
    }
}
