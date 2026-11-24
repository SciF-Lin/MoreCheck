package com.morecheck.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从可能被包装过的 [Context] 里取出宿主 [Activity]；取不到返回 null。
 *
 * ## 为什么需要它
 *
 * Compose 里拿到的 `LocalContext.current` 常常**不是** Activity 本身，而是一层
 * `ContextThemeWrapper` / `ContextWrapper` 链（Activity 未必是最外层，也可能是
 * `MutableContextWrapper`）。想拿 Activity 做「弹 Dialog / 取 window / 申请权限 /
 * `requestPinShortcut`」这类事情时，必须先沿 `baseContext` 链往上找。
 *
 * ## ⚠️ v1.1.75：本项目此前有**三份**这个函数，行为还不一致
 *
 * | 位置 | 末尾返回 |
 * | --- | --- |
 * | `ui/PrivacyConsent.kt` | `current as? Activity` |
 * | `ui/settings/PermissionsScreen.kt` | `current as? Activity` |
 * | `ui/scan/ScanScreen.kt` | **`null`** |
 *
 * 前两份与第三份在「遍历到链尾仍不是 Activity、但链尾本身可能是 Activity」这个
 * 边界上**行为不同**——`while` 循环的条件是 `current is ContextWrapper`，
 * 一旦 `current` 变成非 `ContextWrapper` 的 `Context` 就会退出循环，
 * 此时 `current` 仍有可能就是 Activity（取决于包装层次）。返回 `null` 的那份
 * 会漏掉这种情况。
 *
 * 三份实现里两份、一种行为，说明这本来就是一个函数；散成三份只会让它们各自漂移。
 * 现在统一到这里，口径取**更宽容的那份**（`as? Activity` 兜底）。
 *
 * ## 为什么放在 `util/` 而不是留在 `ui/`
 *
 * 它是纯 Android 平台层的类型转换，与任何 UI 组件无关；
 * 放在 `ui/` 里会让 `ui/scan` 之类去 import `ui/PrivacyConsent`，
 * 形成一条毫无道理的同层依赖。
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
