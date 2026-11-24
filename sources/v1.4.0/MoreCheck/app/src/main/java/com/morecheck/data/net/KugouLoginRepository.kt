package com.morecheck.data.net

import android.util.Base64
import com.morecheck.data.music.KugouDevice
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 酷狗扫码登录的中间结果。
 *
 * [imageBase64] 是酷狗接口**直接返回的二维码 PNG**（`qrcode_img` 字段，
 * `data:image/png;base64,...`）。有它就不需要引二维码生成库——省掉一个 600KB 的依赖，
 * 也不用担心自绘的二维码扫不出来（图案是官方生成的）。
 */
data class KugouQr(val key: String, val imageBase64: String?)

/** 轮询二维码状态的结果。状态码含义见 [KugouLoginRepository.pollQr]。 */
sealed interface KugouQrState {
    /** 1：还没人扫。 */
    data object Waiting : KugouQrState

    /** 2：已扫码，等手机上点"确认登录"。 */
    data object Scanned : KugouQrState

    /** 4：授权成功，带回凭据。 */
    data class Success(
        val userid: String,
        val token: String,
        val nickname: String,
        val avatarUrl: String?,
        val vipType: Int
    ) : KugouQrState

    /** 0：二维码过期，需要重新生成。 */
    data object Expired : KugouQrState

    /** 网络或接口异常。 */
    data class Failed(val message: String) : KugouQrState
}

/**
 * 酷狗账号登录（扫码）。
 *
 * ## 为什么选扫码，而不是手机号 + 密码 / 验证码
 *
 * 复刻 EchoMusic 服务端（`MakcRe/KuGouMusicApi`）时对比过三条路：
 *
 * | 方式 | 需要什么 | 结论 |
 * | --- | --- | --- |
 * | 扫码 | 两个 GET，web 签名 | ✅ 采用 |
 * | 手机验证码 | AES + RSA 加密、`t1/t2/t3` 设备密文、`signParamsKey` | 参数太多且随版本变 |
 * | 账号密码 | 同上 + 账号密码要经过用户输入 | 风险最高，不采用 |
 *
 * 扫码这条路**不接触用户密码**，凭据由用户在手机酷狗 App 里授权产生，
 * 对用户和对我们都更安全。这也是微信/QQ 系第三方的常规做法。
 *
 * ## 凭据落点
 *
 * 登录成功只拿到 `token` + `userid`，交给 [com.morecheck.data.music.KugouAccountStore]
 * 存到独立 DataStore（已被备份规则排除）。**不写入日志、不写进任何文档。**
 */
object KugouLoginRepository {

    private const val LOGIN_HOST = "https://login-user.kugou.com"

    /** 二维码里编码的目标地址（酷狗 H5 登录页，扫码后由它唤起 App 授权）。 */
    private const val QR_CONTENT = "https://h5.kugou.com/apps/loginQRCode/html/index.html"

    /**
     * 申请一个二维码。
     *
     * 对应上游 `module/login_qr_key.js`：`GET /v2/qrcode`。
     * 注意 `appid` 这里是 **1001**（登录域自己的 appid），不是取链用的 1005。
     */
    suspend fun requestQr(device: KugouDevice): KugouQr = withContext(Dispatchers.IO) {
        val params = webParams(
            device,
            linkedMapOf(
                "appid" to "1001",
                "type" to "1",
                "plat" to "4",
                "qrcode_txt" to "$QR_CONTENT?appid=${KugouSign.APPID}&",
                "srcappid" to KugouSign.SRCAPPID
            )
        )
        val text = AppHttp.getText("$LOGIN_HOST/v2/qrcode?${query(params)}", webHeaders())
        val json = JSONObject(text)
        val data = json.optJSONObject("data")
            ?: throw IllegalStateException("酷狗未返回二维码（status=${json.optInt("status")}）")
        val key = data.optString("qrcode")
        if (key.isBlank()) throw IllegalStateException("二维码 key 为空")
        // `qrcode_img` 形如 `data:image/png;base64,xxxx`，去掉前缀只留数据段。
        val image = data.optString("qrcode_img")
            .substringAfter("base64,", "")
            .takeIf { it.isNotBlank() }
        KugouQr(key = key, imageBase64 = image)
    }

    /**
     * 查一次扫码状态。
     *
     * 对应上游 `module/login_qr_check.js`：`GET /v2/get_userinfo_qrcode`。
     * 状态码：**0 过期 / 1 等待扫码 / 2 待确认 / 4 授权成功**（4 时 data 里带
     * `token` 与 `userid`）。
     *
     * 这里不自己轮询：轮询节奏归界面管（用户切走页面就该停），
     * repository 只负责"问一次、翻译一次"。
     */
    suspend fun pollQr(key: String, device: KugouDevice): KugouQrState =
        withContext(Dispatchers.IO) {
            runCatching {
                val params = webParams(
                    device,
                    linkedMapOf(
                        "plat" to "4",
                        "appid" to KugouSign.APPID,
                        "srcappid" to KugouSign.SRCAPPID,
                        "qrcode" to key
                    )
                )
                val text = AppHttp.getText(
                    "$LOGIN_HOST/v2/get_userinfo_qrcode?${query(params)}",
                    webHeaders()
                )
                val json = JSONObject(text)
                val data = json.optJSONObject("data") ?: JSONObject()
                when (data.optInt("status", json.optInt("status", -1))) {
                    0 -> KugouQrState.Expired
                    1 -> KugouQrState.Waiting
                    2 -> KugouQrState.Scanned
                    4 -> {
                        val token = data.optString("token")
                        val userid = data.optString("userid")
                        if (token.isBlank() || userid.isBlank()) {
                            KugouQrState.Failed("授权成功但没拿到凭据，请重试")
                        } else {
                            KugouQrState.Success(
                                userid = userid,
                                token = token,
                                nickname = data.optString("nickname")
                                    .ifBlank { data.optString("username") },
                                avatarUrl = data.optString("pic").takeIf { it.isNotBlank() },
                                vipType = data.optInt("vip_type", 0)
                            )
                        }
                    }
                    else -> KugouQrState.Failed(
                        json.optString("error").ifBlank { "未知状态" }
                    )
                }
            }.getOrElse { error ->
                KugouQrState.Failed(error.message ?: "网络异常")
            }
        }

    /** 把 base64 的二维码 PNG 解成字节，交给界面转 Bitmap。失败返回 null。 */
    fun decodeQrImage(base64: String?): ByteArray? {
        if (base64.isNullOrBlank()) return null
        return runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
    }

    // ---- 公共拼装 ------------------------------------------------------------

    /**
     * Web 请求的公共参数 + 签名。
     *
     * 顺序无关（签名内部会排序），但**参数集合必须完整**：少 `dfid` 或 `clienttime`
     * 会被服务端判 `error_code 35002 parameter incomplete`。
     */
    private fun webParams(
        device: KugouDevice,
        business: LinkedHashMap<String, String>
    ): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        params.putAll(business)
        params["dfid"] = device.dfid
        params["mid"] = device.mid
        params["uuid"] = device.uuid
        params["clientver"] = KugouSign.CLIENTVER
        params["clienttime"] = KugouSign.nowSeconds().toString()
        params["signature"] = KugouSign.web(params)
        return params
    }

    private fun webHeaders(): Map<String, String> = mapOf(
        "User-Agent" to KugouSign.WEB_UA,
        "Referer" to "https://www.kugou.com/"
    )

    /** 把参数拼成 query。`qrcode_txt` 里带 `?`/`&`/`:`，必须编码。 */
    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
}
