package com.morecheck.data

import androidx.room.ColumnInfo
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
    val updatedAt: Long = 0L,

    // ---- v5：任务组织（分组 / 标签 / 归档 / 暂停）-----------------------------
    //
    // 这四个字段都是**本机组织信息**，刻意不进云同步（见 CloudSyncRepository.taskRow
    // 的注释）：云端表结构未变，推未知列会被 PostgREST 拒绝；而 local_id 撞号尚未
    // 修复之前，扩散更多字段只会放大冲突面。备份 JSON 会带上它们（可选键），
    // 因此换机 / 本地导出恢复不丢。

    /** 分组名（自由文本，null / 空白 = 未分组）。 */
    val groupName: String? = null,
    /** 标签，逗号分隔的单个字符串（避免为此新建关联表），null / 空白 = 无标签。 */
    val tags: String? = null,
    /**
     * 归档：从主页与提醒中隐藏，仅在任务管理页「已归档」筛选里可见。
     *
     * `@ColumnInfo(defaultValue = "0")` 是**迁移正确性**要求，不是业务需要：
     * SQLite 给既有表加非空列必须带 DEFAULT，而 Room 校验升级后的表结构时
     * 会把编译期 schema 的默认值与实际表逐列比对。不写这个注解，编译期 schema
     * 认为该列无默认值，升级就会抛 "Migration didn't properly handle check_tasks"。
     */
    @ColumnInfo(defaultValue = "0")
    val archived: Boolean = false,
    /** 暂停：不提醒、不计入今日目标，但任务卡仍在主页（灰显）且可手动打卡。 */
    @ColumnInfo(defaultValue = "0")
    val paused: Boolean = false
) {
    /** 标签列表（去空白、去重、保持输入顺序）。 */
    fun tagList(): List<String> = tags.orEmpty()
        .split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}
