package com.morecheck.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 签名算法的回归测试。
 *
 * ## 期望值是怎么来的
 *
 * 按 `MakcRe/KuGouMusicApi` 的 `util/helper.js` 用 Python 独立实现一遍算出来的
 * （同一套盐、同样的"按 key 排序拼 k=v"规则）。也就是说**不是**拿 Kotlin 自己的
 * 输出回填期望值——那样只能证明代码没变，证明不了算法对。
 *
 * 另外这两组盐与拼法在 2026-11-08 已被服务端实测接受：用同样的算法请求
 * `login-user.kugou.com/v2/qrcode` 正常返回二维码，说明算法方向没错。
 *
 * ## 为什么值得单测
 *
 * 签名错的表现是接口返回 `status:0` / `error_code 35002`，与"没登录""参数不全"
 * 长得一模一样，线上很难区分。锁死向量后，改代码时至少能立刻知道"是签名先坏了"。
 */
class KugouSignTest {

    @Test
    fun Android签名与上游一致() {
        val signature = KugouSign.android(mapOf("a" to "1", "b" to "2"))
        assertEquals("b97698f1a4fbf02b349ca851b5dce224", signature)
    }

    @Test
    fun Web签名与上游一致() {
        val signature = KugouSign.web(mapOf("a" to "1", "b" to "2"))
        assertEquals("70ccbef64fdcc9271fe883d1d7f07395", signature)
    }

    @Test
    fun 取链接口的真实参数组合签名稳定() {
        // 这组参数与 KugouAuthedApi.gatewayGet 拼出来的公共参数形状一致，
        // 用来兜住"多带/少带一个参数"这类改动。
        val params = linkedMapOf(
            "module_id" to "51",
            "dfid" to "ABCDEFGHIJKLMNOPQRSTUVWX",
            "mid" to "12345678901234567890",
            "uuid" to "550e8400-e29b-41d4-a716-446655440000",
            "appid" to "1005",
            "clientver" to "20489",
            "clienttime" to "1700000000"
        )
        assertEquals("b20ccd237825e0cf8aec34b3d671f806", KugouSign.android(params))
    }

    /**
     * 取链 `key` 参数的向量。
     *
     * 这个值算错的表现是 `{"status":0,"error":"illegal key"}`，
     * 和"签名算错""没登录"又是三种不同的报错——所以单独锁死。
     * 期望值同样是按上游 `helper.js` 的 `signKey` 用 Python 独立算的。
     */
    @Test
    fun 取链key与上游一致() {
        assertEquals(
            "ddd6b1b1751e8f98abd8150264b99862",
            KugouSign.trackerKey(
                hash = "abc123",
                appid = "1005",
                mid = "20081288807199271470",
                userid = "1280386900"
            )
        )
        // hash 大小写不影响（内部会转小写），userid 为空时按上游用 "0"。
        assertEquals(
            "4ce4c050b1ab5cf666baf8ffedd43f39",
            KugouSign.trackerKey(hash = "ABCDEF", appid = "1005", mid = "111", userid = "")
        )
    }

    @Test
    fun 参数顺序不影响签名() {
        val ordered = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val shuffled = linkedMapOf("c" to "3", "a" to "1", "b" to "2")
        assertEquals(KugouSign.android(ordered), KugouSign.android(shuffled))
        assertEquals(KugouSign.web(ordered), KugouSign.web(shuffled))
    }

    @Test
    fun 设备标识的长度与字符集符合酷狗要求() {
        val dfid = KugouSign.randomAlphaNum(24)
        assertEquals(24, dfid.length)
        // 酷狗的 dfid 是大写字母 + 数字组成的 24 位串
        assertTrue(dfid.all { it in '0'..'9' || it in 'A'..'Z' })

        val mid = KugouSign.randomDigits(20)
        assertEquals(20, mid.length)
        assertTrue(mid.all { it in '0'..'9' })

        // uuid 只要求是合法的 UUID 字符串（含 4 个连字符、36 位）
        val uuid = KugouSign.randomUuid()
        assertEquals(36, uuid.length)
        assertEquals(4, uuid.count { it == '-' })
    }

    @Test
    fun 两次生成的设备标识不同() {
        // 若随机源被写坏（例如固定种子），用户之间的设备指纹会撞车，
        // 酷狗风控会把这批请求当成同一台设备。
        assertTrue(KugouSign.randomAlphaNum(24) != KugouSign.randomAlphaNum(24))
        assertTrue(KugouSign.randomDigits(20) != KugouSign.randomDigits(20))
    }
}
