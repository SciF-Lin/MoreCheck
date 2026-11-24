package com.morecheck.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Ignore
import org.junit.Test

/**
 * 云同步 ID 生成策略的测试（缺陷一：local_id 撞号）。
 *
 * 缺陷依据：docs/规划与待办.md 2.1「云端主键是 owner_id + local_id，两端各自
 * 生成会撞」；docs/reviews/架构与UI评审-2026-10-26.md P1-3。
 *
 * 代码位置（当前实现）：CloudSyncRepository.taskRow 里 `put("local_id", task.id)`
 * ——云端 local_id 直接透传本机 Room 自增 id，抽取的纯策略见 [SyncIdPlan]。
 *
 * 触发时序：设备 A、B 各新建一个任务，Room 自增 id 都是 1；两台设备并发推送时
 * 云端冲突键都是 `(owner, 1)`，后推者把先推者的行整个覆盖（推送侧 LWW 只比
 * updated_at，挡不住"不同的行共占一个键"）；反向拉取时 A 也会用 B 的
 * local_id=1 覆盖本机 id=1 的任务（insertTasksWithIds 的 REPLACE 策略）。
 *
 * 这三个带 @Ignore 的测试**已验证为红**（2026-11-04，失败输出摘录在
 * docs/reviews/优化建议-2026-11-04.md）：失败原因确实是被测策略不区分设备，
 * 而非编译错误。修复需要服务端配合（推荐路线 B：云端加 uuid 列，见同文档
 * 修复设计），落地前先 @Ignore 挂起，避免基线常红；路线 B 实施时去掉
 * @Ignore 并替换 SyncIdPlan 函数体，让它们转绿。
 */
class SyncIdPlanTest {

    // ── cloudLocalId：本机行 → 云端 local_id ─────────────────────────────────

    @Test
    @Ignore("缺陷确认测试（已验证为红）：local_id 撞号需服务端路线 B（云端 uuid 列）落地后启用")
    fun `不同设备的同一自增 id 必须映射到不同的云端 local_id`() {
        // 两台设备各自的第一个任务（本机自增 id 都是 1）。
        // 当前策略透传本机 id → 两台设备得到同一个云端 local_id = 撞号。
        val deviceA = SyncIdPlan.cloudLocalId(deviceId = "device-A", localRowId = 1L)
        val deviceB = SyncIdPlan.cloudLocalId(deviceId = "device-B", localRowId = 1L)
        assertNotEquals("不同设备的同一自增 id 撞了同一个云端主键", deviceA, deviceB)
    }

    @Test
    fun `同一设备的映射必须保持确定（自增 id 决定）`() {
        // 防回归的绿测：策略无论怎么修，同一设备内 id=42 永远映射到同一云端键，
        // 否则同设备内的更新会失去冲突目标。
        assertEquals(42L, SyncIdPlan.cloudLocalId("device-A", 42L))
        assertEquals(SyncIdPlan.cloudLocalId("device-A", 42L), SyncIdPlan.cloudLocalId("device-A", 42L))
    }

    // ── taskConflictKey：任务行在云端的冲突键 ────────────────────────────────

    @Test
    @Ignore("缺陷确认测试（已验证为红）：任务冲突键加设备维度需服务端路线 B 落地后启用")
    fun `任务冲突键必须包含设备维度`() {
        // 云端表 CONFLICT_TASKS = "owner_id,local_id"：键里只有 local_id，
        // 两台设备同 id 的任务共占一行。修复后键必须能区分设备。
        val keyA = SyncIdPlan.taskConflictKey(deviceId = "device-A", localRowId = 1L)
        val keyB = SyncIdPlan.taskConflictKey(deviceId = "device-B", localRowId = 1L)
        assertNotEquals("任务冲突键不含设备维度：双设备互覆", keyA, keyB)
    }

    // ── recordConflictKey：记录行在云端的冲突键 ─────────────────────────────

    @Test
    @Ignore("缺陷确认测试（已验证为红）：记录冲突键加设备维度需服务端路线 B 落地后启用")
    fun `记录冲突键同样必须包含设备维度`() {
        // 记录的键是 task_local_id|date：task_local_id 撞号时，设备 B 打在
        // "自己的任务 1" 上的卡会被记到设备 A 的"任务 1" 头上（错位合并）。
        val keyA = SyncIdPlan.recordConflictKey(deviceId = "device-A", taskLocalId = 1L, date = "2026-11-04")
        val keyB = SyncIdPlan.recordConflictKey(deviceId = "device-B", taskLocalId = 1L, date = "2026-11-04")
        assertNotEquals("记录冲突键不含设备维度：打卡错位合并", keyA, keyB)
    }
}
