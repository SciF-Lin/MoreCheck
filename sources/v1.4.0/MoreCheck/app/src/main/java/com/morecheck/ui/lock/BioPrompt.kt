package com.morecheck.ui.lock

import android.os.Build
import androidx.biometric.BiometricPrompt
import com.morecheck.data.AppLockBioTexts

/**
 * 用当前文案构建一个 `PromptInfo`。
 *
 * ## 为什么要抽成公共函数（2026-10-27）
 *
 * 原来这段构建代码只存在于锁屏页里。设置页要做「预览」时，如果各写一份，
 * 就会出现"预览看到的样子和真正解锁时不一样"——比如哪天给某行加了截断、
 * 或忘了 `setDescription` 只在 API 30+ 生效，两边就会飘。
 * 项目里已经为这类飘移返工过（`ifBlank` 兜底、ActionRow 的文案硬判），
 * 所以文案构建收在**一处**，锁屏页与预览都从这里取。
 *
 * ## 空值口径
 *
 * - `title` / `negativeButtonText`：系统 API 要求非空，仓库层已保证（`ifBlank` 回落默认）。
 * - `subtitle` / `description`：**允许为空**，空则干脆不调用对应 setter，
 *   系统弹窗就不显示那一行（这是 v1.1.92「副标题可清空」的实现方式）。
 * - `description`（`setDescription`）是 **API 30 起**才有的方法；Android 10 上系统弹窗
 *   本来就没有这一行，传了也不会显示，所以低版本直接跳过。
 */
internal fun buildBioPromptInfo(texts: AppLockBioTexts): BiometricPrompt.PromptInfo {
    val builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(texts.title)
        .setNegativeButtonText(texts.negative)
    if (texts.subtitle.isNotBlank()) builder.setSubtitle(texts.subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && texts.description.isNotBlank()) {
        builder.setDescription(texts.description)
    }
    return builder.build()
}
