package com.morecheck.data.cloud

import com.morecheck.data.GameSnapshot
import com.morecheck.data.game.GameSnapshotPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云同步冲突判定的测试。
 *
 * 覆盖两块：
 * 1. LWW 纯判定（[lwwWinsLocally]）——当前实现的既有语义，防回归的绿测；
 * 2. 游戏存档 applied 竞态（缺陷二）——**2026-11-04 已修复并转绿**。
 *    这四个用例先于修复写成（当时钉住期望行为、验证为红），修复落在
 *    GameDataBridge.kt 的 GameSnapshotPolicy.shouldOverwriteWithLocalPush，
 *    规则见其 KDoc；留作防回归。
 *
 * 缺陷二依据：docs/reviews/架构与UI评审-2026-10-26.md P1-3「GameDataBridge.pushState()
 * 无条件 upsert applied=true：云端新存档可能被本地旧快照覆盖」。
 *
 * 代码位置（修复前）：GameDataBridge.pushState 无条件
 * `upsertGameSnapshot(GameSnapshot(..., updatedAt = now, applied = true))`；
 * 现经由 GameDataBridge.kt 的 GameSnapshotPolicy 裁决。
 *
 * 触发时序：本机刚拉到设备 A 的新存档（applied=false，待水合）→ 本机 WebView
 * 的采集脚本晚于拉取触发 pushState，上交本地旧快照 → 当前实现直接覆盖：
 * applied 置回 true、时间戳变成 now（比云端还新）→ 待水合的云端存档被冲掉
 * （pendingState 从此返回 null），下次推送又以"本地更新"胜出，云端也被盖掉。
 */
class SyncConflictTest {

    // ── LWW 纯判定（既有语义，防回归）────────────────────────────────────────

    @Test
    fun `LWW 远端没有此行时本地可推`() {
        assertTrue(lwwWinsLocally(localUpdatedAt = 1000L, remoteUpdatedAt = null))
    }

    @Test
    fun `LWW 远端不早于本地时本地不推`() {
        // 远端更新：交给拉取阶段处理；同刻也判云端赢（保守侧）。
        assertFalse(lwwWinsLocally(localUpdatedAt = 1000L, remoteUpdatedAt = 2000L))
        assertFalse(lwwWinsLocally(localUpdatedAt = 2000L, remoteUpdatedAt = 2000L))
    }

    @Test
    fun `LWW 远端严格更旧时本地可推`() {
        assertTrue(lwwWinsLocally(localUpdatedAt = 3000L, remoteUpdatedAt = 2000L))
    }

    // ── 游戏存档 applied 竞态（缺陷二，修复后防回归）────────────────────────

    /** 云端拉下来的待水合快照：比本地采集到的旧快照更新。 */
    private fun cloudSnapshot(cloudUpdatedAt: Long) = GameSnapshot(
        gameKey = "snake",
        dataJson = """{"snake_high_score":"999"}""",
        updatedAt = cloudUpdatedAt,
        applied = false
    )

    @Test
    fun `本地旧快照推送不得覆盖云端拉下的待水合快照`() {
        // 场景：拉取在 T2 写入云端新存档（applied=false），本地采集脚本在
        // T1（早于 T2）才把 WebView 里的旧状态交上来。期望：不接受这次覆盖，
        // 让水合脚本下次打开页面时把云端新存档灌进 localStorage。
        val existing = cloudSnapshot(cloudUpdatedAt = 2000L)
        val accepted = GameSnapshotPolicy.shouldOverwriteWithLocalPush(
            existing = existing,
            localUpdatedAt = 1000L
        )
        assertFalse("本地旧快照覆盖了云端新存档（applied 竞态）", accepted)
    }

    @Test
    fun `applied 为 false 时同刻的本地推送也不得覆盖`() {
        // 即使时间戳打平（本地采集与拉取同一毫秒），只要既有快照还是
        // 待水合状态（applied=false），就不能被本地推送冲掉——否则水合
        // 机会永久丢失。同刻判云端赢，与 lwwWinsLocally 的保守侧一致。
        val existing = cloudSnapshot(cloudUpdatedAt = 2000L)
        val accepted = GameSnapshotPolicy.shouldOverwriteWithLocalPush(
            existing = existing,
            localUpdatedAt = 2000L
        )
        assertFalse("同刻推送冲掉了待水合的云端快照", accepted)
    }

    @Test
    fun `本地确实更新的快照可以覆盖`() {
        // 防回归的绿测：用户在本机真玩出了新进度（采集时刻晚于云端版本），
        // 覆盖必须被允许，否则本地进度推不上去。
        val existing = cloudSnapshot(cloudUpdatedAt = 1000L)
        val accepted = GameSnapshotPolicy.shouldOverwriteWithLocalPush(
            existing = existing,
            localUpdatedAt = 3000L
        )
        assertTrue("本地新进度被拒绝落库", accepted)
    }

    @Test
    fun `本机从没有快照时本地采集可以落库`() {
        // 防回归的绿测：新装设备第一次玩，没有任何既有快照可比较。
        assertTrue(GameSnapshotPolicy.shouldOverwriteWithLocalPush(existing = null, localUpdatedAt = 1000L))
    }
}
