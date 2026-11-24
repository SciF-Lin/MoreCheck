package com.morecheck.util

/**
 * 「关于 → 支持作者」默认打开的地址（2026-10-29 需求）。
 *
 * 需求原文：「点击支持作者后，默认打开
 * `https://space.bilibili.com/2094567655?spm_id_from=333.1007.0.0`，
 * 打开内容可在开发者选项里修改」。
 *
 * ## 为什么默认值放在这里而不是 `SettingsRepository`
 *
 * 与本项目处理 `HotSearchScreen.DEFAULT_API_URL` 的口径一致：
 * 默认值是**功能自己的常量**，仓库只负责存"用户改成了什么"（没改就是 null）。
 * 塞进通用设置仓库会让一个纯内容常量变成跨模块依赖。
 *
 * ## 为什么不在编译期做成 `BuildConfig` 字段
 *
 * 它需要能被用户在运行时覆盖，`BuildConfig` 是只读的；运行时覆盖走
 * `SettingsRepository.supportAuthorUrl`（开发者选项 → 工具与测试 → 支持作者链接）。
 *
 * 拼串时注意查询参数里的 `&`：这里只有一个参数，但用户可能在
 * 开发者选项里改成带多个参数的地址，所以 README / 提示里都要求**整串粘贴**。
 */
const val DEFAULT_SUPPORT_AUTHOR_URL: String =
    "https://space.bilibili.com/2094567655?spm_id_from=333.1007.0.0"

/**
 * 真正用来打开"支持作者"的地址：用户自定义值优先，否则回落默认。
 *
 * @param custom 开发者选项里配置的地址；null / 空白 = 没改过。
 */
fun supportAuthorUrlWith(custom: String?): String =
    custom?.takeIf { it.isNotBlank() } ?: DEFAULT_SUPPORT_AUTHOR_URL
