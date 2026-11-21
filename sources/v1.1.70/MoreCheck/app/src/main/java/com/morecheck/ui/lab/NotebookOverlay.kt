package com.morecheck.ui.lab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 「记事本 → 右上角三个点 → 悬浮窗」的启动入口（v1.1.68 需求 2）。
 *
 * 这个对象只做两件事：**检查权限**、**把内容装进 Intent 并启动 Service**。
 * 真正的窗口逻辑全在 [NotebookOverlayService] 里——这里保持无状态，
 * 因为界面只是"点了菜单里的一项"，不该持有任何跟窗口有关的引用。
 *
 * ## 为什么权限检查不能像相机/定位那样"弹个系统对话框"
 *
 * `SYSTEM_ALERT_WINDOW`（显示在其他应用上层）是**特殊权限**，与运行时权限完全不是一回事：
 * - 它**没有**任何 `ActivityResultContracts.RequestPermission` 能申请它，
 *   调 `requestPermissions` 会被系统直接忽略（这是平台设计，不是用法问题）；
 * - 唯一的申请方式是跳到系统专门设置页（`ACTION_MANAGE_OVERLAY_PERMISSION`），
 *   由用户手动打开；
 * - 应用**只能查询**结果（`Settings.canDrawOverlays`），**查不到用户是否真的开了**：
 *   用户在设置页按返回时我们收不到任何回调。所以只能"跳过去 + 提示用户再点一次"。
 *
 * ## 为什么内容用 Intent extra 传，而不是让 Service 自己去读 DataStore
 *
 * 这是本功能一个**关键且不显眼**的取舍，直接影响用户看到的内容对不对：
 *
 * 记事本的正文有 600ms 的防抖落盘（`NotebookScreen.TEXT_DEBOUNCE_MS`），也就是说
 * **用户刚敲的最后几个字可能还没有写进 DataStore**。若 Service 自己去
 * `NotebookStore.read` 取内容，它拿到的很可能是"少最后一句"的旧版本——
 * 而这恰恰是用户最容易察觉、也最不能接受的一种错误（"我明明写了，小窗里没有"）。
 *
 * 反过来，本函数的调用点在 `NotebookScreen` 的菜单回调里，**此刻界面上的
 * `text` 就是用户看到的正文**，把它直接当参数传下去，得到的快照必然准确，
 * 且没有任何"先补写一次、再等磁盘"的竞态。
 *
 * 代价：`Intent` 的 extra 走 Binder，有约 1MB 的事务上限
 *（超出抛 `TransactionTooLargeException`）。对"一条手敲的笔记"来说这个上限
 * 极难触及（1MB 约等于 50 万个汉字），因此这个取舍是划算的。
 *
 * @param text 当前正文（界面上的实时值，**不是**从盘上读回来的）。
 * @param imagePaths 当前图片绝对路径；图片在添加时就已即时落盘，这里传的是内存中的最新列表。
 * @return 启动成功返回 null；否则返回一句直接给用户看的原因（由界面显示）。
 */
internal object NotebookOverlay {

    fun open(context: Context, text: String, imagePaths: List<String>): String? {
        if (!Settings.canDrawOverlays(context)) {
            // 跳系统设置页。**必须带 `package:` 参数**：不带的话部分 ROM 会跳到
            // 「所有应用的悬浮窗权限列表」，用户还得自己再找一遍本应用；
            // 带上就直接停在「允许显示在其他应用上层」这一项上。
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                // `Uri.fromParts` 等价于 `Uri.parse("package:$pkg")`，但不用手工拼字符串，
                // 包名里有特殊字符时也不会解析出错。
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // 极少数 ROM 裁掉了这个设置页，跳转可能失败。失败就如实反馈，
            // 否则用户会对着一个毫无反应的菜单项发呆。
            val started = runCatching { context.startActivity(intent) }.isSuccess
            return if (started) {
                "请先在系统设置里允许「显示在其他应用上层」，再点一次「悬浮窗」"
            } else {
                "无法打开悬浮窗权限设置，请在系统设置里手动允许「显示在其他应用上层」"
            }
        }

        val intent = Intent(context, NotebookOverlayService::class.java)
            .setAction(NotebookOverlayService.ACTION_SHOW)
            .putExtra(NotebookOverlayService.EXTRA_TEXT, text)
            // 用 `putStringArrayListExtra` 而不是把 List 直接塞进去：
            // `List<String>` 不是 `Serializable`（`listOf` 返回的实现类没实现该接口），
            // 直接 `putExtra(..., imagePaths)` 会在运行时抛
            // `RuntimeException: Parcel: unable to marshal value`。
            // `ArrayList` 才是有序且可序列化的那个具体类型。
            .putStringArrayListExtra(
                NotebookOverlayService.EXTRA_PATHS,
                ArrayList(imagePaths)
            )

        // 用 `startForegroundService`：Android 8+ 不允许应用在后台随意起普通 Service，
        // 而本入口是用户在前台点菜单触发的，起前台 Service 是明确合规的路径。
        // ⚠️ 它要求 5 秒内必须调 `startForeground`，否则系统判 ANR / 抛
        // `ForegroundServiceDidNotStartInTimeException`——Service 侧已在
        // `onStartCommand` 的第一时间满足这个要求（见那边的注释）。
        return runCatching { ContextCompat.startForegroundService(context, intent) }
            .fold(
                onSuccess = { null },
                onFailure = { "悬浮窗启动失败：${it::class.java.simpleName}" }
            )
    }
}
