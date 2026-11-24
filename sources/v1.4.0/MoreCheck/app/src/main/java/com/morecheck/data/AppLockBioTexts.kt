package com.morecheck.data

/**
 * 指纹解锁弹窗的文案（2026-10-26 需求；v1.1.92 扩到四行 + 支持清空）。
 *
 * 四行分别对应 `BiometricPrompt.PromptInfo` 的 title / subtitle / description /
 * negativeButtonText。其中 [description] 只有 **API 30+** 才有（`setDescription`），
 * 低版本系统弹窗不显示这一行——这是系统能力限制，不是本应用取舍。
 *
 * ## 空值语义（很重要，别按"空串 = 用默认"去理解）
 *
 * | 字段 | 缺省（从未改过） | 显式清空 |
 * | --- | --- | --- |
 * | [title] / [negative] | 用默认文案 | **不接受空**，回落默认（API 要求非空） |
 * | [subtitle] / [description] | 用默认文案 | **真的不显示**（这是 v1.1.92 的诉求） |
 *
 * 判定收在 `SettingsRepository.appLockBioTexts` 一处：读盘时 `键不存在` 才回落默认，
 * `键存在但为空` 一律尊重用户的清空意图。之前用 `ifBlank` 统一兜底，导致
 * "清空副标题"永远清不掉，改一次又变回默认。
 *
 * @param title 弹窗标题。
 * @param subtitle 副标题；空 = 不显示。
 * @param description 副标题下方那行说明（API 30+ 才显示）；空 = 不显示。
 * @param negative 取消按钮的字（点它回到手势板）。
 */
data class AppLockBioTexts(
    val title: String,
    val subtitle: String,
    val description: String,
    val negative: String
) {
    companion object {
        /** 出厂默认文案。 */
        val DEFAULT = AppLockBioTexts(
            title = SettingsRepository.DEFAULT_APP_LOCK_BIO_TITLE,
            subtitle = SettingsRepository.DEFAULT_APP_LOCK_BIO_SUBTITLE,
            description = SettingsRepository.DEFAULT_APP_LOCK_BIO_DESCRIPTION,
            negative = SettingsRepository.DEFAULT_APP_LOCK_BIO_NEGATIVE
        )
    }
}
