package com.morecheck.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 「设置 → 通用 → 权限管理」里的两个开关，及其对应的系统权限。
 *
 * 这里刻意把**开关**与**系统权限**当成两件事：
 * - 开关是应用自己的意图标记（存在 DataStore，默认关）；
 * - 系统权限是操作系统的事实状态（用户可以随时在系统设置里改，应用无法监听）。
 *
 * 因此「能不能用」的判定一律是 `开关打开 && 系统已授权`（见 [AppPermissions.isUsable]），
 * 只看其中一个都会出现「开关开着但实际用不了」或「权限给了但用户没开开关」的错觉。
 *
 * @param id 存进 DataStore 的标识，**不要改动**（改了会让老用户的开关状态丢失）。
 * @param scene 「使用场景」说明，直接显示在设置页开关下方。
 * @param manifestPermissions 需要的系统权限；**任一被授予即视为已授权**。
 *   `null` 表示该系统版本上不存在这个权限，按「已授权」处理。
 */
enum class AppPermission(
    val id: String,
    val title: String,
    val scene: String,
    val manifestPermissions: List<String>?
) {
    CAMERA(
        id = "camera",
        title = "摄像头",
        scene = "用于「扫一扫」实时扫码",
        manifestPermissions = listOf(Manifest.permission.CAMERA)
    ),
    NOTIFICATION(
        id = "notification",
        title = "通知",
        scene = "用于任务的每日提醒推送",
        // Android 13 起才有通知运行时权限；更低版本创建通知不需要授权。
        manifestPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }
    );

    companion object {
        /** 按 id 反查；无法识别时返回 null（而不是回退到某一项，避免静默错配）。 */
        fun fromId(id: String?): AppPermission? = entries.firstOrNull { it.id == id }
    }
}

/** 权限相关的平台工具。 */
object AppPermissions {

    /** 系统层面是否已经授予该权限。 */
    fun isGranted(context: Context, permission: AppPermission): Boolean {
        val names = permission.manifestPermissions ?: return true
        return names.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 全部权限是否都已授予。 */
    fun allGranted(context: Context): Boolean = AppPermission.entries.all { isGranted(context, it) }

    /**
     * 「能不能真的用」——开关打开 **且** 系统已授权。
     *
     * @param toggleEnabled 该权限在设置页里的开关状态。
     */
    fun isUsable(context: Context, permission: AppPermission, toggleEnabled: Boolean): Boolean =
        toggleEnabled && isGranted(context, permission)

    /**
     * 打开本应用的系统设置页（应用详情）。
     *
     * 按需求：设置页里把开关从关打到开时，先检查系统权限，
     * **没有就去这里**，让用户在系统设置里逐项授权；授权后回到应用会自动同步开关。
     *
     * @return 是否真的把界面切出去了（`startActivity` 没抛异常）。
     *   调用方通常忽略返回值；`openAutoStartSettings` 用它决定要不要再兜底一次。
     */
    fun openAppSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    // ---- 自启动（开机自启）引导 -----------------------------------------------
    //
    // ⚠️ **关键事实**：Android 从 AOSP 到最新的 targetSdk 都**没有**任何标准 API 能查询
    // 「本应用是否被允许自启动 / 后台自启」。各家 ROM（MIUI、EMUI、ColorOS、OriginOS、
    // OneUI…）都是私有实现，既没有统一的读取接口，也没有统一的状态广播。
    //
    // 因此本模块**无法判断「有没有自启动权限」**，只能：
    //   1. 始终给出提示，让用户自己去系统里放行；
    //   2. 尽量把用户直接送到该 ROM 的自启动管理页，省得他在设置里翻半天。
    //
    // 反过来说，任何「已获取自启动权限」的绿色勾/开关状态都是**假的**——
    // 我们查不到，所以界面上一律不显示这类状态，只做引导。

    private const val TAG = "AppPermissions"

    /**
     * 一个厂商自启动管理页候选。
     *
     * @param rom 人类可读的 ROM 名，仅用于日志与排错。
     * @param packageName 厂商设置 / 安全中心的应用包名。
     * @param activityName 自启动管理页的**完整**类名。**不允许写成 `.Suffix` 简写**：
     *   简写要靠 `ComponentName(context, "...")` 的包名补全，各家写法不一致，
     *   写全名最不容易出错。
     */
    private data class AutoStartTarget(
        val rom: String,
        val packageName: String,
        val activityName: String
    )

    /**
     * 各 ROM 的自启动管理页。
     *
     * **顺序有意义**：同一家可能随版本换过多次类名（如 OPPO 新旧安全中心是两个包），
     * 从前到后逐个试，第一个能拉起来的就跳过去。
     *
     * 这些类名全部来自社区长期维护的公开清单（dontkillmyapp 等），**不是**公开 API：
     * 厂商随时可能在某个版本里改掉。写错不会崩（见 [tryStartAutoStartPage] 的 try/catch），
     * 只会静默跳到下一条，最坏退到应用详情页。
     */
    private val autoStartTargets = listOf(
        // 小米 / 红米，MIUI 与 HyperOS 通用。
        AutoStartTarget(
            rom = "MIUI / HyperOS",
            packageName = "com.miui.securitycenter",
            activityName = "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        // 华为 / 荣耀，EMUI 与 HarmonyOS 的「应用启动管理」。
        AutoStartTarget(
            rom = "EMUI / HarmonyOS",
            packageName = "com.huawei.systemmanager",
            activityName = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        // 华为较新版本换成了 appcontrol 下的入口。
        AutoStartTarget(
            rom = "EMUI (appcontrol)",
            packageName = "com.huawei.systemmanager",
            activityName = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
        ),
        // OPPO / realme / 一加，ColorOS 新版安全中心。
        AutoStartTarget(
            rom = "ColorOS (safecenter)",
            packageName = "com.coloros.safecenter",
            activityName = "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        // ColorOS 另一处常见入口（部分机型只有这个）。
        AutoStartTarget(
            rom = "ColorOS (startupapp)",
            packageName = "com.coloros.safecenter",
            activityName = "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        // OPPO 老机型（ColorOS 3.x 及更早）的独立安全中心。
        AutoStartTarget(
            rom = "ColorOS (legacy)",
            packageName = "com.oppo.safe",
            activityName = "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        // vivo / iQOO，OriginOS 与 Funtouch OS 的「自启动」列表。
        AutoStartTarget(
            rom = "OriginOS / Funtouch OS",
            packageName = "com.vivo.permissionmanager",
            activityName = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        // vivo 旧版把入口放在 iQOO 安全助手里。
        AutoStartTarget(
            rom = "Funtouch OS (legacy)",
            packageName = "com.iqoo.secure",
            activityName = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        // 三星 OneUI：没有独立「自启动」页，开放的是电池 / 后台使用限制页，
        // 用户在这里把应用设为「不受限制」即等价于允许自启。
        AutoStartTarget(
            rom = "OneUI",
            packageName = "com.samsung.android.lool",
            activityName = "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        // 魅族 Flyme 的「后台管理」。
        AutoStartTarget(
            rom = "Flyme",
            packageName = "com.meizu.safe",
            activityName = "com.meizu.safe.permission.SmartBGActivity"
        )
    )

    /**
     * 尝试拉起某一条厂商自启动页，用 **try/catch** 兼作「存在性判断」。
     *
     * ## 为什么不用 `resolveActivity` / `getActivityInfo` 预判存在
     *
     * 这两个 API 都受 **Android 11（API 30）起的软件包可见性过滤**约束：
     * 本应用只声明了 `queries` 之外的东西（我们没有、也不该为这些厂商包加 `queries`），
     * 于是查任何第三方包都会抛 `NameNotFoundException` / 返回 null——
     * 哪怕组件**真实存在**。这会造成**假阴性**：所有厂商入口全被判为「不存在」，
     * 功能直接退化成永远只跳应用详情页，等于白做。
     *
     * 而 `startActivity` 走**显式 ComponentName** 时不受可见性过滤限制：
     * 组件在就正常打开，不在才抛 `ActivityNotFoundException`。
     * 所以「试着拉、拉不动就换下一条」反而是这里**唯一可靠**的存在性判断，
     * 也正好是任务允许的两种方式之一。
     *
     * 除 `ActivityNotFoundException` 外，部分 ROM 给这些页面加了签名级权限，
     * `startActivity` 会抛 `SecurityException`——同样被 `runCatching` 接住并跳过。
     *
     * @return true 表示已经成功把界面切到该厂商页面。
     */
    private fun tryStartAutoStartPage(context: Context, target: AutoStartTarget): Boolean {
        val intent = Intent().apply {
            component = ComponentName(target.packageName, target.activityName)
            // 从非 Activity 上下文（如 Application）启动时必须有这个 flag。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }
            .onSuccess { Log.d(TAG, "已跳转自启动管理页：${target.rom} (${target.activityName})") }
            .onFailure {
                // 只记 debug 日志：类名写错 / 机型没有这一页都是**预期内**的正常路径，
                // 不是错误，不该打 error 污染线上日志。
                Log.d(TAG, "跳过 ${target.rom}（${it::class.java.simpleName}），尝试下一条")
            }
            .isSuccess
    }

    /**
     * 跳「自启动 / 后台运行」管理页，尽量直达厂商页面，全部失败则回退应用详情页。
     *
     * 调用前**不需要**（也无法）判断「有没有自启动权限」——见本文件上方关于
     * 「Android 没有查询自启动权限的 API」的说明。
     *
     * @return 是否跳走了。兜底的应用详情页是系统内置页面，正常情况下一定成功；
     *   返回值只为让调用方在极端情况下能给出提示，不必依赖它做分支。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        for (target in autoStartTargets) {
            if (tryStartAutoStartPage(context, target)) return true
        }

        // 兜底：应用详情页。用户在「权限 / 电池 / 自启动」里手动找本应用。
        Log.d(TAG, "未命中任何厂商自启动页，回退应用详情页")
        return openAppSettings(context)
    }
}
