package com.morecheck.data.cloud

import com.morecheck.data.AuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云侧纯逻辑的回归测试（v3 云同步）。
 *
 * 覆盖三块**不需要 Android 运行时**的逻辑：
 * 1. [friendlyCloudMessage]——错误分类 → 用户可读文案的唯一一份映射表，
 *    登录页与同步引擎共用；改坏任何一条都会直接出现在用户界面上。
 * 2. [CloudAuthClient.requireMainlandPhone]——发送验证码前的第一道校验，
 *    口径（11 位、1 开头）与服务端一致才不会白耗短信额度。
 * 3. [AuthRepository.newUserId]——本地展示标识的形状（位数 / 字符池）。
 */
class CloudModelsTest {

    /** 形近易混的字符（KDoc 承诺不在用户 ID 里出现）。用 CharArray 避免 String.contains 的重载歧义。 */
    private val CONFUSABLE = charArrayOf('0', 'O', '1', 'I', 'l')

    // ── friendlyCloudMessage ─────────────────────────────────────────────────

    @Test
    fun `未认证错误提示重新登录`() {
        val text = friendlyCloudMessage(
            CloudError(CloudError.Kind.UNAUTHENTICATED, "token expired", null, 401)
        )
        assertEquals("登录状态已失效，请重新登录", text)
    }

    @Test
    fun `网络错误不透出服务端细节`() {
        val text = friendlyCloudMessage(
            CloudError(CloudError.Kind.NETWORK, "whatever raw", null, 0)
        )
        assertEquals("网络不稳定，请稍后重试", text)
    }

    @Test
    fun `限频与权限各有独立文案`() {
        assertEquals(
            "操作太频繁，请稍后再试",
            friendlyCloudMessage(CloudError(CloudError.Kind.RATE_LIMITED, "429", null, 429))
        )
        assertEquals(
            "没有权限执行该操作",
            friendlyCloudMessage(CloudError(CloudError.Kind.PERMISSION_DENIED, "403", null, 403))
        )
    }

    @Test
    fun `未知错误透出原文（兜底可诊断）`() {
        val text = friendlyCloudMessage(
            CloudError(CloudError.Kind.UNKNOWN, "奇怪的服务端返回", null, 500)
        )
        assertEquals("奇怪的服务端返回", text)
    }

    // ── requireMainlandPhone ─────────────────────────────────────────────────

    @Test
    fun `合规的大陆手机号不抛异常`() {
        // 11 位、1 开头——不关心号段真实性，只关心格式闸门。
        CloudAuthClient.requireMainlandPhone("13812340000")
        CloudAuthClient.requireMainlandPhone(" 19912340000 ".trim())
    }

    @Test
    fun `位数不对或非 1 开头抛 INVALID_REQUEST`() {
        for (bad in listOf("", "1381234000", "138123400001", "23812340000", "abc")) {
            try {
                CloudAuthClient.requireMainlandPhone(bad)
                throw AssertionError("应当拒绝：'$bad'")
            } catch (e: CloudException) {
                assertEquals(CloudError.Kind.INVALID_REQUEST, e.error.kind)
                assertEquals("请输入正确的 11 位手机号", e.error.message)
            }
        }
    }

    // ── AuthRepository.newUserId ─────────────────────────────────────────────

    @Test
    fun `用户 ID 是 8 位且不含易混淆字符`() {
        repeat(50) {
            val id = AuthRepository.newUserId()
            assertEquals(8, id.length)
            // 易混淆字符（0/O/1/I/l）按 KDoc 的承诺被剔除——这条断言锁的是可见效果，
            // 字符池本身是 private 实现细节，不该被测试钉死。
            assertTrue("出现易混淆字符：$id", id.none { it in CONFUSABLE })
        }
    }
}
