package com.morecheck.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「下载前先查本机是否已有同版本安装包」的判定逻辑（2026-11-10 需求 4）。
 *
 * 只测纯函数 [apkBytesMatch]：读文件 / 查 MediaStore 那部分依赖 Android 运行时，
 * 由编译冒烟覆盖；真正容易写错的是"同名算不算同一版"这个口径。
 */
class ReleaseApkLocalTest {

    private fun apk(size: Long = 1024L, hash: String? = "abc123"): ReleaseApk = ReleaseApk(
        version = "1.3.6-beta",
        channel = ReleaseChannel.BETA,
        versionCode = 65,
        fileName = "MoreCheck-1.3.6-beta.apk",
        objectPath = "shared/1/apk/MoreCheck-1.3.6-beta.apk",
        sizeBytes = size,
        sha256 = hash,
        uploadedAt = null,
        notes = null
    )

    @Test
    fun `大小与哈希都一致才算同一包`() {
        assertTrue(apkBytesMatch(1024L, "abc123", apk()))
        assertTrue(apkBytesMatch(1024L, "ABC123", apk()))
    }

    @Test
    fun `同版本重出换了包 哈希不同必须重新下载`() {
        assertFalse(apkBytesMatch(1024L, "new-hash", apk()))
    }

    @Test
    fun `半截文件或旧包 大小不同必须重新下载`() {
        assertFalse(apkBytesMatch(512L, "abc123", apk()))
    }

    @Test
    fun `清单没给大小或哈希时按能拿到的信息判断`() {
        assertTrue(apkBytesMatch(999L, "whatever", apk(size = 0L, hash = null)))
        assertTrue(apkBytesMatch(1024L, "abc123", apk(hash = null)))
        assertFalse(apkBytesMatch(1024L, null, apk()))
    }
}
