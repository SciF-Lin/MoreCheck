package com.morecheck.ui.tasks

import com.morecheck.data.CheckTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务组织纯判定（TaskOrganization.kt）的回归测试。
 *
 * 重点钉住两件最容易在迭代里悄悄坏掉的事：
 * 1. 旧数据兼容——Room v4 及以前的行 groupName / tags 是 null、archived / paused
 *    是 false，筛选与分组不能因为空值抛异常或把任务弄丢；
 * 2. 标签切分同源——输入框预览（previewTags）和列表展示（CheckTask.tagList）
 *    必须是同一套规则，不能各写一份逗号解析。
 *
 * 这些函数不碰 Android / Compose，所以能直接在纯 JVM 跑，不需要模拟器。
 */
class TaskOrganizationTest {

    private fun task(
        id: Long,
        name: String = "任务$id",
        groupName: String? = null,
        tags: String? = null,
        archived: Boolean = false,
        paused: Boolean = false
    ) = CheckTask(
        id = id,
        name = name,
        icon = "✅",
        color = 0,
        reminderTime = null,
        createdAt = 0L,
        groupName = groupName,
        tags = tags,
        archived = archived,
        paused = paused
    )

    // ── 标签切分 ────────────────────────────────────────────────────────────

    @Test
    fun `旧数据 tags 为 null 时标签列表为空`() {
        assertTrue(task(1, tags = null).tagList().isEmpty())
    }

    @Test
    fun `tagList 兼容中英文逗号 去空白 去重 且保持顺序`() {
        val t = task(2, tags = " 习惯,晨间，习惯 , , 重要 ，")
        assertEquals(listOf("习惯", "晨间", "重要"), t.tagList())
    }

    @Test
    fun `previewTags 与 tagList 同源`() {
        val raw = "a，b, a , c"
        assertEquals(
            task(3, tags = raw).tagList(),
            previewTags(raw)
        )
    }

    @Test
    fun `previewTags 对空白与全分隔符输入都不炸`() {
        assertTrue(previewTags("").isEmpty())
        assertTrue(previewTags("   ").isEmpty())
        assertTrue(previewTags(",,,，，,").isEmpty())
    }

    // ── 筛选：旧数据（全部默认 false）────────────────────────────────────────

    @Test
    fun `旧数据全部落在全部与进行中 已暂停已归档为空`() {
        val tasks = listOf(task(1), task(2))
        assertEquals(tasks, tasks.filterTasks(TaskFilter.ALL))
        assertEquals(tasks, tasks.filterTasks(TaskFilter.ACTIVE))
        assertTrue(tasks.filterTasks(TaskFilter.PAUSED).isEmpty())
        assertTrue(tasks.filterTasks(TaskFilter.ARCHIVED).isEmpty())
    }

    // ── 筛选：混合状态 ───────────────────────────────────────────────────────

    @Test
    fun `筛选按归档与暂停正确分桶`() {
        val active = task(1)
        val paused = task(2, paused = true)
        val archived = task(3, archived = true)
        val both = task(4, archived = true, paused = true)
        val tasks = listOf(active, paused, archived, both)

        assertEquals(tasks, tasks.filterTasks(TaskFilter.ALL))
        assertEquals(listOf(active), tasks.filterTasks(TaskFilter.ACTIVE))
        assertEquals(listOf(paused), tasks.filterTasks(TaskFilter.PAUSED))
        assertEquals(listOf(archived, both), tasks.filterTasks(TaskFilter.ARCHIVED))
    }

    @Test
    fun `已归档且已暂停的任务不重复出现在已暂停里`() {
        // 归档的任务统一由「已归档」承载，避免同一行同时命中两个筛选。
        val tasks = listOf(task(1, archived = true, paused = true))
        assertTrue(tasks.filterTasks(TaskFilter.PAUSED).isEmpty())
        assertEquals(tasks, tasks.filterTasks(TaskFilter.ARCHIVED))
    }

    // ── 分组 ────────────────────────────────────────────────────────────────

    @Test
    fun `没有命名分组时只有未分组一组且 hasNamedGroups 为 false`() {
        val tasks = listOf(task(1), task(2, groupName = "   "), task(3, groupName = null))
        assertFalse(tasks.hasNamedGroups())
        val groups = tasks.groupTasks()
        assertEquals(1, groups.size)
        assertEquals(UNGROUPED_TITLE, groups[0].title)
        assertEquals(tasks, groups[0].tasks)
    }

    @Test
    fun `groupName 为空白或 null 都归入未分组`() {
        val tasks = listOf(
            task(1, groupName = "健康"),
            task(2, groupName = "  "),
            task(3, groupName = null)
        )
        val groups = tasks.groupTasks().associateBy { it.title }
        assertEquals(listOf(tasks[0]), groups["健康"]?.tasks)
        assertEquals(listOf(tasks[1], tasks[2]), groups[UNGROUPED_TITLE]?.tasks)
    }

    @Test
    fun `分组保持首次出现顺序 同名聚在一起`() {
        val tasks = listOf(
            task(1, groupName = "工作"),
            task(2, groupName = "健康"),
            task(3, groupName = "工作"),
            task(4, groupName = "健康")
        )
        val groups = tasks.groupTasks()
        assertEquals(listOf("工作", "健康"), groups.map { it.title })
        assertEquals(listOf(tasks[0], tasks[2]), groups[0].tasks)
        assertEquals(listOf(tasks[1], tasks[3]), groups[1].tasks)
        assertTrue(tasks.hasNamedGroups())
    }

    @Test
    fun `切换筛选后分组仍基于各自子集`() {
        val tasks = listOf(
            task(1, groupName = "健康"),
            task(2, groupName = "健康", archived = true),
            task(3, groupName = "工作", paused = true)
        )
        val activeGroups = tasks.filterTasks(TaskFilter.ACTIVE).groupTasks()
        assertEquals(1, activeGroups.size)
        assertEquals("健康", activeGroups[0].title)
        assertEquals(listOf(tasks[0]), activeGroups[0].tasks)
    }
}
