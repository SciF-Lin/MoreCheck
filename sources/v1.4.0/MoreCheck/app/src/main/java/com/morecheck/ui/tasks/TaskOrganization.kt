package com.morecheck.ui.tasks

import com.morecheck.data.CheckTask

/**
 * 任务组织（分组 / 标签 / 归档 / 暂停）的纯判定逻辑。
 *
 * 这里刻意不引用任何 Android / Compose 类型：筛选、分组、标签切分都是
 * List 进、List 出，能直接放进 src/test 做纯 JVM 回归，不需要模拟器。
 * 界面层只负责把结果画出来，不含任何业务判定，避免同一套规则在两处漂移。
 *
 * 所有对归档 / 暂停 / 分组 / 标签的读取都走 CheckTask 的默认值与可空类型，
 * 旧数据（Room v4 及以前）这些字段是 null / false，天然落在「未分组、进行中」。
 */

/** 任务管理页顶部的筛选条件。 */
enum class TaskFilter(val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    PAUSED("已暂停"),
    ARCHIVED("已归档")
}

/** 未分组任务归入的固定组名。 */
const val UNGROUPED_TITLE = "未分组"

/**
 * 按筛选条件取子集。
 *
 * 「进行中」严格等于未归档且未暂停；「已暂停」只收未归档的暂停任务，
 * 归档的任务统一由「已归档」承载，避免同一行同时出现在两个筛选里。
 * 归档 / 暂停为 Boolean 默认值 false，旧数据不会因空值漏进异常分支。
 */
fun List<CheckTask>.filterTasks(filter: TaskFilter): List<CheckTask> = when (filter) {
    TaskFilter.ALL -> this
    TaskFilter.ACTIVE -> filter { !it.archived && !it.paused }
    TaskFilter.PAUSED -> filter { !it.archived && it.paused }
    TaskFilter.ARCHIVED -> filter { it.archived }
}

/** 分组展示单元：一个小标题 + 该组任务，保持原列表顺序。 */
data class TaskGroup(val title: String, val tasks: List<CheckTask>)

/**
 * 是否存在用户命名的分组。
 *
 * 全都没分组时列表按扁平显示，不硬塞一个「未分组」标题占一行。
 */
fun List<CheckTask>.hasNamedGroups(): Boolean = any { !it.groupName.isNullOrBlank() }

/**
 * 按 groupName 分组（null / 空白视为未分组）。
 *
 * 用 LinkedHashMap 保留分组首次出现的顺序，组间不打乱用户原始排列，
 * 这样从「全部」切到某个筛选时，任务不会在各组之间跳动。
 */
fun List<CheckTask>.groupTasks(): List<TaskGroup> {
    val buckets = LinkedHashMap<String, MutableList<CheckTask>>()
    for (task in this) {
        val title = task.groupName?.trim()?.takeIf { it.isNotEmpty() } ?: UNGROUPED_TITLE
        buckets.getOrPut(title) { mutableListOf() }.add(task)
    }
    return buckets.map { (title, tasks) -> TaskGroup(title, tasks) }
}

/**
 * 标签输入框的 chip 预览。
 *
 * 借 CheckTask.tagList 做切分，而不是另写一套逗号分隔规则：输入框预览和
 * 列表里展示的标签必须完全同源，否则用户「看到两个、存进去三个」会很难查。
 * 这里用占位字段拼一个只用来调 tagList 的实例，其余字段不参与判定。
 */
fun previewTags(raw: String): List<String> = CheckTask(
    name = "",
    icon = "",
    color = 0,
    reminderTime = null,
    createdAt = 0L,
    tags = raw
).tagList()
