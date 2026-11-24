package com.morecheck.data.net

import java.security.MessageDigest
import java.util.Locale
import kotlin.random.Random

/**
 * 酷狗接口的请求签名与设备标识生成。
 *
 * ## 来源
 *
 * 全部按 `MakcRe/KuGouMusicApi`（EchoMusic 的服务端 submodule）的
 * `util/helper.js`、`util/request.js`、`util/util.js` 一比一复刻：
 *
 * - **Android 签名**：`md5(盐 + 按 key 字典序拼好的 k=v 串 + 请求体 + 盐)`，
 *   盐是 `OIlwieks28dk2k092lksi2UIkp`；
 * - **Web 签名**：同样的拼法，盐换成 `NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt`；
 * - **公共参数**：`dfid / mid / uuid / appid / clientver / clienttime` 必须一起带上，
 *   `clienttime` 是**秒级**时间戳（不是毫秒，写错会被判签名失效）；
 * - **请求头**：`dfid / mid / clienttime` 三个头要与 query 里的值一致。
 *
 * ## 为什么这些函数是 internal
 *
 * 签名算法是纯函数，单测里可以对着 Node 版算好的固定向量断言，
 * 不需要网络也不需要 Context。真正的请求拼装放在 [KugouAuthedApi]。
 *
 * ⚠️ 这里的常量是**逆向第三方客户端**得到的，属于非官方接口。上游一旦改动，
 * 表现是签名参数/接口直接返回 `status:0`。届时只能跟着改，别指望它稳定。
 */
internal object KugouSign {

    /** Android 端签名盐（标准版，概念版 lite 是另一个盐，本项目不用）。 */
    private const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"

    /** Web / H5 端签名盐（扫码登录那几个接口用）。 */
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"

    /** 取链 `key` 参数的盐（上游 saveKey / signKey 用的常量）。 */
    private const val KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"

    /** 标准版 appid。概念版是 3116，本项目不用。 */
    const val APPID = "1005"

    /** 登录域（login-user.kugou.com）要求的来源 appid。 */
    const val SRCAPPID = "2919"

    /** 客户端版本号。取值的意义在于"装成哪个版本的客户端"，与功能无直接关系。 */
    const val CLIENTVER = "20489"

    /** 取链接口单独用的版本号（复刻 `song_url_auth.js` 里的 11561）。 */
    const val TRACKER_CLIENTVER = "11561"

    /**
     * 浏览器型 UA。扫码登录的 web 接口用它；
     * 网关的 android 接口要用 [ANDROID_UA]。
     */
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /** Android 客户端 UA（网关侧的签名校验会看它）。 */
    const val ANDROID_UA = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"

    fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) out.append(String.format(Locale.US, "%02x", byte))
        return out.toString()
    }

    /**
     * Android 签名。
     *
     * 参数按 **key 的字典序** 排序后拼成 `k=vk=v...`（无分隔符），
     * 两侧各包一次盐再取 MD5。顺序错、漏参数、多加参数都会导致签名不匹配。
     */
    fun android(params: Map<String, String>): String {
        val joined = params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        return md5("$ANDROID_SALT$joined$ANDROID_SALT")
    }

    /** Web 签名。拼法与 [android] 相同，只是盐不同。 */
    fun web(params: Map<String, String>): String {
        val joined = params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        return md5("$WEB_SALT$joined$WEB_SALT")
    }

    /**
     * 取链接口的 `key` 参数（上游 `signKey`）。
     *
     * 少了它 `/tracker/v5/url` 直接返回 `{"status":0,"error":"illegal key"}`，
     * 而且**报错文案与签名无关**，极易误判成签名算错。算法见 helper.js 的 signKey：
     * `md5(hash + 盐 + appid + mid + userid)`。
     */
    fun trackerKey(hash: String, appid: String, mid: String, userid: String): String =
        md5("${hash.lowercase()}${KEY_SALT}$appid$mid${userid.ifBlank { "0" }}")

    /** 秒级时间戳（签名与请求头都用它，**不要**传毫秒）。 */
    fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /**
     * 随机大写字母 + 数字串，用于 `dfid`。
     *
     * 字符池与上游一致（`1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ`），长度 24。
     * 这个值代表"设备指纹"，登录与取链之间要**保持一致**，所以只能生成一次后落盘。
     */
    fun randomAlphaNum(len: Int): String {
        val pool = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString(len) { repeat(len) { append(pool[Random.nextInt(pool.length)]) } }
    }

    /** 纯数字随机串，用于 `mid`（酷狗把它当设备号，20 位即可）。 */
    fun randomDigits(len: Int): String {
        val pool = "0123456789"
        return buildString(len) { repeat(len) { append(pool[Random.nextInt(pool.length)]) } }
    }

    /** UUID v4 形式的设备 uuid。 */
    fun randomUuid(): String = java.util.UUID.randomUUID().toString()
}
