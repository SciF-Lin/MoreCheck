package com.morecheck.data.cloud

import android.content.Context
import android.content.pm.PackageManager

/**
 * 微信登录的**客户端可用性网关**（v1.2.4，客户端先行）。
 *
 * ## 这一版能做什么、不能做什么
 *
 * 能做：判定当前设备上微信登录**能不能发起**（AppID 配置了吗、装微信了吗），
 * 给登录页的微信入口一个诚实的状态说明。
 *
 * 不能做：真正完成授权——这需要两件本版尚未落地的事：
 * ① [CloudConfig.WECHAT_APPID] 非空（微信开放平台移动应用审核通过）；
 * ② 服务端支持原生微信 code 换会话（见 [CloudConfig.WECHAT_SIGNIN_PATH] 的接缝说明）。
 * 在 ① 满足前，[status] 恒为 [WeChatLoginStatus.NotConfigured]，
 * 界面据此明确提示"暂未开通"，不会发起任何微信请求。
 *
 * ## 接入点（AppID 到位后要做的事）
 *
 * 在 [launchAuth] 里用微信 OpenSDK（`com.tencent.mm.opensdk`）发起
 * `SendAuth.Req`（scope = `snsapi_userinfo`），回调落在 `WXEntryActivity`，
 * 拿到 `code` 后交给 [CloudAuthClient.signInWithWechatCode] 换会话。
 * 引入 OpenSDK 依赖这件事刻意推迟到 AppID 就绪——现在引了也编译不进任何
 * 可执行路径，只会让每次构建多背一个用不上的库。
 */
object WeChatAuthGateway {

    /** 微信官方包名（判断"装没装微信"用，不与之交互）。 */
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /** 微信登录的可用状态。 */
    sealed interface Status {

        /** AppID 未配置（[CloudConfig.WECHAT_APPID] 为空）——**当前恒定状态**。 */
        data object NotConfigured : Status

        /** AppID 已配置但设备上没有微信（预置状态，当前不可达）。 */
        data object WeChatMissing : Status

        /** 可以发起授权（预置状态，当前不可达）。 */
        data object Ready : Status
    }

    /**
     * 判定当前设备上微信登录的状态。
     *
     * 顺序有意为之：**先查 AppID 再查微信**——服务端/资质没落地时，
     * 即使设备装了微信也该报"暂未开通"而不是"请安装微信"，
     * 否则用户装了微信还是用不了，提示反而变成误导。
     */
    fun status(context: Context): Status {
        if (CloudConfig.WECHAT_APPID.isBlank()) return Status.NotConfigured
        val installed = runCatching {
            context.packageManager.getPackageInfo(WECHAT_PACKAGE, 0)
        }.getOrNull() != null
        return if (installed) Status.Ready else Status.WeChatMissing
    }

    /**
     * 发起微信授权（拉起微信）。
     *
     * **当前恒定抛 [WeChatLoginUnavailableException]**：AppID 未配置时调用方
     * 根本走不到这里（界面按 [status] 拦下），这里再兜一层是防御性的——
     * 将来接入 OpenSDK 后，此方法体替换为 `SendAuth.Req` 的标准发起代码，
     * 签名与返回约定保持不变。
     */
    fun launchAuth(@Suppress("UNUSED_PARAMETER") context: Context): Nothing {
        throw WeChatLoginUnavailableException(
            "微信登录暂未开通：等待微信开放平台与服务端支持"
        )
    }
}

/** 微信登录当前不可用的异常（接缝期的诚实失败，不伪装成功）。 */
class WeChatLoginUnavailableException(message: String) : Exception(message)
