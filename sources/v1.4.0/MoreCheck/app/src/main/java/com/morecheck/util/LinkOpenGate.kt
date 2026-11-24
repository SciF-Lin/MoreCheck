package com.morecheck.util

/**
 * 「链接在应用内打开还是交系统浏览器」的进程级缓存。
 *
 * ## 为什么要一个缓存，而不是在打开链接时读 DataStore
 *
 * [openInBrowser] 是个普通函数，会被大量非 Composable 的回调调用（热搜原文、
 * 关于页、扫码结果…），拿不到 `collectAsStateWithLifecycle`，也不该在主线程
 * 用 `runBlocking` 等一次磁盘读。因此由 `MoreCheckApp.onCreate` 起一条常驻
 * 协程把 DataStore 的值镜像到这里，打开链接时同步读一个 `@Volatile` 布尔即可。
 *
 * 默认 `false` = 系统默认浏览器，与改造前的行为完全一致；老用户升级后
 * 不拨开关就没有任何变化。
 */
object LinkOpenGate {
    @Volatile
    var inApp: Boolean = false
}

/**
 * 「应用内网页」目标的内部路由前缀。
 *
 * 复用桌面快捷方式的 `ACTION_LAB_SHORTCUT` 通道把链接送进 [MoreCheckRoot]，
 * 但值不是 `TabDestination.route` 而是带查询参数的完整路由片段，
 * 消费侧会先认这个前缀再决定怎么 navigate。
 */
const val IN_APP_LINK_ROUTE_PREFIX = "search?url="
