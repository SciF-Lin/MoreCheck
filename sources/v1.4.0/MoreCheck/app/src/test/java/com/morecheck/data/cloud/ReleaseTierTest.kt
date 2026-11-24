package com.morecheck.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装包分级的纯逻辑回归测试（不依赖 android.* / Room）。
 *
 * 覆盖两块与「正式版免登录」直接相关的纯函数：
 * 1. [isPublicRelease]——channel / version 双判，决定某条记录能否进公开清单；
 * 2. [publicReleaseView]——未登录视图的筛选，必须只含正式版。
 *
 * 这两处一旦判错，要么把测试版泄漏给未登录用户，要么把本该免登录的正式版
 * 藏起来，所以用纯 JVM 用例把口径钉死。
 */
class ReleaseTierTest {

    /** 构造一条记录；channel 默认按版本名推导，与客户端 readManifest 一致。 */
    private fun apk(
        version: String,
        channel: ReleaseChannel = ReleaseChannel.of(version),
        versionCode: Int = 0
    ) = ReleaseApk(
        version = version,
        channel = channel,
        versionCode = versionCode,
        fileName = "MoreCheck-$version.apk",
        objectPath = "shared/2112910697389096960/apk/MoreCheck-$version.apk",
        sizeBytes = 1L,
        sha256 = null,
        uploadedAt = null,
        notes = null
    )

    @Test
    fun `正式版判定为公开`() {
        assertTrue(isPublicRelease(apk("1.2.0")))
        assertTrue(isPublicRelease(apk("1.1.90")))
    }

    @Test
    fun `beta 与 rc 版本都不公开`() {
        // version 含 beta：渠道与版本两道判断都会挡。
        assertFalse(isPublicRelease(apk("1.2.1-beta")))
        // rc 不在 version 排除词里，但属于 BETA 渠道，靠 channel 判断挡住。
        assertFalse(isPublicRelease(apk("1.3.0-rc")))
    }

    @Test
    fun `debug 版本虽是 STABLE 渠道仍不公开`() {
        // 这正是「双判」的意义：debug 不在 BETA_MARKERS，of() 会判成 STABLE，
        // 但诊断包不该出现在免登录视图里，必须由 version 检查兜住。
        val debug = apk("1.2.0-debug")
        assertEquals(ReleaseChannel.STABLE, debug.channel)
        assertFalse(isPublicRelease(debug))
    }

    @Test
    fun `未登录视图只保留正式版`() {
        val all = listOf(
            apk("1.2.0", versionCode = 49),
            apk("1.2.1-beta", versionCode = 50),
            apk("1.2.0-debug", versionCode = 49),
            apk("1.3.0-rc", versionCode = 51),
            apk("1.1.9", versionCode = 39)
        )
        val public = publicReleaseView(all)
        assertEquals(2, public.size)
        assertTrue(public.all { isPublicRelease(it) })
        assertTrue(public.none { it.version.contains("beta", true) || it.version.contains("debug", true) })
        assertEquals(setOf("1.2.0", "1.1.9"), public.map { it.version }.toSet())
    }

    @Test
    fun `公开清单为空时视图也为空`() {
        assertTrue(publicReleaseView(emptyList()).isEmpty())
    }

    @Test
    fun `版本标记大小写不敏感`() {
        assertFalse(isPublicRelease(apk("1.2.0-BETA")))
        assertFalse(isPublicRelease(apk("1.2.0-Debug")))
        assertFalse(isPublicRelease(apk("1.2.0-SNAPSHOT")))
    }
}
