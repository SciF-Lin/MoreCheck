package com.morecheck.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 「页面自定义」（2026-10-24 需求 4）的消费侧契约。
 *
 * ## 它是什么
 *
 * 四个布尔位的集合，分别表示"**允许**按自定义口径呈现该类元素"：
 *
 * | 字段 | 生效时 | 回落（false）时 |
 * | --- | --- | --- |
 * | [textStyle] | 文字可按自定义口径调整字重 / 字号 | 全局默认排版 |
 * | [buttonStyle] | 按钮可按自定义口径调整底色 / 形状 | Material 默认按钮 |
 * | [iconStyle] | 图标可按自定义口径调整线宽 / 填充 | 默认图标外观 |
 * | [textContent] | 固定文案可被自定义替换 | 内置原文 |
 *
 * ## 为什么走 CompositionLocal
 *
 * 与 `LocalPressFeedbackEnabled`（`PressScale.kt`）完全同一条理由：
 * 自定义口径的**判断点**分散在全站各页面（文字、按钮、图标、文案四类元素
 * 遍布每个页面），逐个传参意味着几十处调用点、且漏一处就表现为"这一页自定义不生效"
 * 的静默不一致。因此在 [com.morecheck.MainActivity] 组合根**一处 provide**
 * （值来自 `DeveloperOptionsRepository.customize*Enabled` 的生效流），
 * 各页面从这里读取即可。
 *
 * ## 默认值
 *
 * `staticCompositionLocalOf` 的默认实例四个字段全为 `true`（= 允许自定义）：
 * 未被 provider 包裹的组合子树（预览、诊断壳等）按"允许"处理，与开关的
 * DataStore 默认值一致。真正的"回落默认观感"由 provide 侧的生效流决定
 * （两级门任一关掉 → 四个字段全 false），本对象只承载结果。
 */
data class PageCustomize(
    val textStyle: Boolean = true,
    val buttonStyle: Boolean = true,
    val iconStyle: Boolean = true,
    val textContent: Boolean = true
)

val LocalPageCustomize = staticCompositionLocalOf { PageCustomize() }
