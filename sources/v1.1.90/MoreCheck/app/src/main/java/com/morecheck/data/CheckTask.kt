package com.morecheck.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A user-defined check-in task (e.g. "早起", "喝水"). */
@Entity(tableName = "check_tasks")
data class CheckTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val icon: String,
    val color: Int,
    /** Daily reminder time as "HH:mm" (24h, device time zone), or null when disabled. */
    val reminderTime: String?,
    /** 备注：自由文本，可为空。新建 / 编辑任务页可填写，打卡选择列表里会显示。 */
    val note: String? = null,
    val createdAt: Long,
    /**
     * 本行最后一次被修改的时间（epoch 毫秒）——云同步的**冲突裁决依据**（LWW）。
     *
     * 每一次新增 / 编辑 / 恢复写入都必须刷新它（由 `TaskRepository` 与同步引擎负责），
     * 任何一处漏掉都会让该行在同步时被判为"旧数据"而被云端版本覆盖。
     *
     * 默认 0 = "从未同步过"：v3 迁移回填的是 [createdAt]（见 `MIGRATION_2_3`），
     * 而备份 JSON 解析时用 `exportedAt` 兜底，两者都不会是 0。
     * 唯一需要担心的路径是**新代码忘记赋值**——那种行会在下次同步时被推上去
     * （updated_at = 0 小于水位线时反而不会被推，属于安全侧的失败）。
     */
    val updatedAt: Long = 0L
)
