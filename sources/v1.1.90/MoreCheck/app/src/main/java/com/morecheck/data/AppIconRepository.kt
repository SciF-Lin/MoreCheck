package com.morecheck.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.DrawableRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 「开发者选项 → 图标测试 → 图标更改（实验性）」。
 *
 * 这个仓库管两件**性质完全不同**的事，放在一起是因为它们同属一个功能、由同一个页面操作：
 *
 * 1. **内置图标真实切换桌面图标**（`activity-alias` + `setComponentEnabledSetting`）；
 * 2. **自定义上传图片的持久化**（存进应用私有目录，只用于应用内显示）。
 *
 * ---
 * ## 一、为什么「换桌面图标」只能这么做
 *
 * 桌面图标是 `AndroidManifest.xml` 里 `android:icon` 指向的**资源引用**，
 * 由系统 PackageManager 在安装 / 组件状态变更时解析并缓存。
 * 应用在运行期**没有任何 API** 能把某个组件的图标换成一张别的图——这是 Android 的硬限制。
 *
 * 所以唯一可行的官方做法是：**把每套图标都预先声明成一个 `activity-alias`**，
 * 运行时只改「哪一个是启用的」，系统再按当前启用的组件重新解析桌面图标。
 * 本文件做的就是这件事。
 *
 * ## 二、为什么自定义图片永远当不了桌面图标（需求口径）
 *
 * 用户自传的图片不是资源、没有资源 id，因此**不可能**成为桌面图标。
 * 需求里「自行上传图片并裁切，替代 app 原先图标」这一句，能与 Android 限制共存的解释是：
 * - 「替代桌面图标」→ 由内置图标（alias 切换）真正做到；
 * - 「上传并裁切」→ 做好、存好、**在应用内显示**（见 [saveCustomIcon] / [decodeCustomIcon]）。
 *
 * 这条口径已与用户确认过，不要在后续版本里试图「让自传图也变成桌面图标」——
 * 那是做不到的，只会写出一个在真机上不生效的功能。
 *
 * ## 三、`DONT_KILL_APP` 不能省
 *
 * 切组件启用状态默认会**杀掉本应用进程**。若不传 [PackageManager.DONT_KILL_APP]，
 * 用户点一下「换成蓝色」，应用会在切换的瞬间被系统杀掉、桌面回到主屏，
 * 观感就是「点一下应用就闪退了」。
 *
 * ## 四、`DONT_KILL_APP` 也治不好的那部分（这就是「实验性」的原因）
 *
 * 进程虽然不会被杀，但**桌面图标什么时候刷新由 launcher 决定**，应用无权干预：
 * 部分 ROM（尤其国产定制桌面）会缓存图标，切换后要几秒、甚至要退出重进桌面才变；
 * 少数 ROM 会短暂出现两个图标或旧图标残留。这是平台层面的行为，
 * 应用侧无法修，只能把功能标注为「实验性」并在界面上告知用户。
 *
 * ## 五、为什么本类可以每次 new 一个（不必进 AppContainer）
 *
 * 它**不持有任何内存状态**：读写在 [SettingsRepository]（DataStore，进程内唯一实例）
 * 与 PackageManager（系统服务）里，两者都是进程级共享的。
 * 因此每个页面各自 `AppIconRepository(context, settingsRepository)` 与共用一份**行为完全一致**，
 * 不存在本项目历史上踩过的「同一个设置被两处实例各存一份，于是开关关不掉」那类问题。
 * （`AppContainer` 里那些必须单例的仓库——比如存着验证码的 `DeveloperOptionsRepository`——
 * 正是因为它们**有**内存状态，才必须唯一。）
 *
 * ## 六、失败必须可诊断：为什么 [applyBuiltInIcon] 返回的不是 `Boolean`
 *
 * 这是 v1.1.69 修的**真问题**，不是洁癖。原实现只返回布尔值，而它内部两次平台调用
 * （`getPackageInfo` / `setComponentEnabledSetting`）的异常都被 `runCatching` 吃成了
 * `false`。结果就是用户看到「图标切换失败，请重试」——**重试一百次也一样**，
 * 因为界面根本不知道自己败在哪一步：是查不到 alias？还是组件名拼错？
 * 还是 ROM 拒绝了这次调用？三种情况的修法完全不同，而日志与界面给的信息量是零。
 *
 * 所以现在返回 [IconApplyResult]：失败时带一句**已经拼好的中文原因**
 * （含异常类名与 message），界面直接把它显示出来即可，不必再"加日志 → 重新打包 → 复现"。
 * 三类结果的语义差别（尤其 [IconApplyResult.Partial] 为什么要写回设置）见那个接口的 KDoc。
 *
 * ## 七、若要把「一个木函」的 PNG 做成图标前景：扩展步骤（**当前未启用**）
 *
 * 工程外的素材目录 `E:\download\app\A_Tree\exported_images\` 里有一批「一个木函」
 * Logo 家族的 PNG（如 `icon_03_432x432.png`）。**本轮刻意没有引用其中任何一张**，
 * 理由是：那些图我没有能力确认其内容（尺寸、是否带底色、图形是否居中、
 * 安全区留白够不够），而图标是**会被系统长期缓存**的美术资源——
 * 盲选一张塞进去，最坏结果是用户桌面上出现一个被遮罩切掉半截的图标，
 * 而且要清掉它得让用户重新选一次图标（缓存由 launcher 决定）。
 *
 * 因此这里只留**扩展点**，等到能确认某张 PNG 的内容（或产品明确指定了哪一张）时，
 * 按下面四步做，**不需要改本类的任何逻辑**：
 *
 * ```
 * // 第 1 步：把 PNG 拷进 app/src/main/res/drawable-xxxhdpi/
 * //         命名与既有前景一致：ic_fore_<id>.png
 * //         （必须自己先确认：图形在 432×432 里大致居中、四周留白 >= 1/3 边长，
 * //           否则桌面遮罩会切到图形本体。）
 * //
 * // 第 2 步：新建 app/src/main/res/mipmap-anydpi-v26/ic_launcher_<id>.xml
 * //         <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
 * //             <background android:drawable="@color/ic_launcher_<id>_background" />
 * //             <foreground android:drawable="@drawable/ic_fore_<id>" />
 * //         </adaptive-icon>
 * //         背景色记得同时加进 res/values/colors.xml（**不要**加进 values-night）。
 * //
 * // 第 3 步：本文件 BUILT_IN_ICON_IDS 末尾追加 "<id>"（首项必须仍是 "default"）。
 * //
 * // 第 4 步：AndroidManifest.xml 里仿照 .icon.Check 再加一个
 * //         <activity-alias android:name=".icon.<Id>" ... enabled="false"
 * //                         android:icon="@mipmap/ic_launcher_<id>"
 * //                         android:roundIcon="@mipmap/ic_launcher_<id>" ... />
 * //         带 LAUNCHER intent-filter；alias 类名首字母大写（见 [aliasComponentOf]）。
 * ```
 *
 * 四处都要动的原因是它们各自承担一件事、无法互相推导：资源名（R 索引）、
 * id 列表（界面遍历）、manifest（系统解析）、别名（平台调用）。
 * 这四者的对应关系是本功能**唯一**需要人工维持的约束，[BUILT_IN_ICON_IDS] 的注释里
 * 也再点了一遍。
 */

/**
 * 一次「切换内置图标」的结果。
 *
 * ## 为什么不用 `Boolean`
 *
 * 见 [AppIconRepository] 类注释第六节。一句话：布尔值把三种**修法完全不同**的失败
 * 压成同一个 `false`，界面上只能显示「失败，请重试」——而其中一种（组件没启用成功）
 * 重试多少次都一样。带上 [Failed.reason] 之后，用户看到的是一句能直接去搜索的
 * 「为什么」，这也是本项目处理「无法自愈的平台失败」的既定口径。
 *
 * ## 为什么「清理其余 alias 失败」不算失败
 *
 * 目标 alias 启用成功 = **桌面图标已经是用户选的那一个**，这一次切换的可见效果已达成。
 * 清理失败只会让某些桌面**额外**多显示出一两个旧图标（桌面按"所有启用的 LAUNCHER"画），
 * 属于需要如实告知、但不该回滚的状态——回滚反而会把用户已经选中的图标又切走。
 * 所以它是独立的 [Partial]，语义是「成功了，但可能需要再切一次来收尾」。
 */
sealed interface IconApplyResult {

    /** 用户选的 id 无法识别，实际切到了默认套（桌面已是默认图标，不是失败）。 */
    data object FellBackToDefault : IconApplyResult

    /** 完全成功：目标已启用、其余已禁用、设置已写回。 */
    data object Success : IconApplyResult

    /**
     * **目标已启用成功**，但有一到多个旧 alias 没禁用掉。
     *
     * 与 [Success] 一样会写回 DataStore（见 [AppIconRepository.applyBuiltInIcon]），
     * 因为「用户选的那套已经生效」是既定事实，不写回反而制造
     * 「桌面是橙色、设置里显示蓝色」的新矛盾。
     */
    data class Partial(val reason: String) : IconApplyResult

    /** 失败。[reason] 是给用户看的中文原因（内含平台异常类名与 message，便于排查）。 */
    data class Failed(val reason: String) : IconApplyResult
}

/**
 * 一次 `setComponentEnabledSetting` 的结果（[AppIconRepository] 内部使用）。
 *
 * 与 [IconApplyResult] 分开是因为两者回答的问题不同：这一个只回答
 * 「平台调用成功了吗、没成功是什么异常」，而 [IconApplyResult] 回答
 * 「这一次切换对用户而言到底是什么结果」——后者还要考虑「目标成功但清理失败」这类组合。
 *
 * 声明在**文件顶层 private**（而不是放进类的 companion）：它只是这个文件内部的传值类型，
 * 不构成对外契约，放顶层也更贴近使用它的那两处。
 */
private sealed interface EnableOutcome {

    /** 平台调用已返回，组件状态已按请求修改。 */
    data object Done : EnableOutcome

    /** 平台调用抛了异常。[reason] = `异常简单类名(message)`。 */
    data class Failed(val reason: String) : EnableOutcome
}

class AppIconRepository(
    private val context: Context,
    private val settings: SettingsRepository
) {

    /**
     * 当前选中的内置图标 id。
     *
     * 直接转发 DataStore 的值，**不做校验**：无法识别的值由 [applyBuiltInIcon] 回落到默认套，
     * 这样「读」这一侧永远有一个合法结果，「写」那一侧也就不必担心自己写坏。
     */
    val selectedIconId: Flow<String> = settings.devIconStyle

    /** 自定义图标的绝对路径；null = 从没上传过。 */
    val customIconPath: Flow<String?> = settings.devCustomIconPath

    /**
     * 裁切导出边长（像素），暴露给界面显示「保存为 512×512」这句文案用。
     *
     * 让界面从仓库取、而不是自己写一个 `512`：导出的实际尺寸只有一个来源
     * （[AppIconStore.EXPORT_SIZE]），界面文案跟着它走，改尺寸时不会出现
     * 「文案说 512、实际导出 256」这种对不上的情况。
     */
    val exportSize: Int = AppIconStore.EXPORT_SIZE

    // ── 内置图标：id 与资源 ────────────────────────────────────────────────

    /**
     * 全部内置图标 id，**顺序即界面上的排列顺序**（第一项是默认那套）。
     *
     * ⚠️ 这个列表必须与 `AndroidManifest.xml` 里的 `activity-alias` 一一对应，
     * 且首个元素必须等于 [SettingsRepository.DEFAULT_ICON_STYLE]。
     * 三处（本列表 / manifest / 默认值常量）任何一处改动都要同步另外两处——
     * 这是本功能唯一需要人工保持一致的约束，已尽量收敛到这三个位置。
     */
    val builtInIconIds: List<String> = BUILT_IN_ICON_IDS

    /** 默认那套的 id（= `manifest` 里唯一 `enabled="true"` 的 alias）。 */
    val defaultIconId: String = SettingsRepository.DEFAULT_ICON_STYLE

    // ── 内置图标：切换 ────────────────────────────────────────────────────

    /**
     * 切到指定的内置图标套。
     *
     * ## 执行顺序：**先启用目标、再禁用其余**（顺序不能反）
     *
     * 若先禁用、后启用，中间会存在一个「所有 LAUNCHER alias 都是禁用」的窗口。
     * 那个窗口里应用**在桌面上没有任何图标**，只能从系统设置或 adb 拉起来，
     * 对普通用户等于「应用不见了」。反过来先启用目标就没有这个窗口。
     *
     * ## 启用失败时**立刻放弃**，绝不再去禁用其余
     *
     * 这是上面那条的兜底：只有确认目标已被启用，才允许动别人。
     * 否则一次失败就会把用户推到「零个图标」的状态——这是本功能**最严重的失败模式**。
     *
     * ## 返回值：三种状态，不是布尔（v1.1.69 修）
     *
     * 失败一律带**原因**（见 [IconApplyResult]）。原因字符串里包含
     * `ComponentName` 与平台异常的类名 + message，因为它们正是排查所需的全部信息：
     * 组件名能立刻看出「alias 是否与 manifest 同步」，
     * 异常类是 `IllegalArgumentException`（名字不存在）还是 `SecurityException`
     * （被平台/ROM 拒绝）则决定了下一步该改代码还是只能改界面提示。
     *
     * ## 写回 DataStore 的时机
     *
     * 只有确认真实生效（[IconApplyResult.Success] / [IconApplyResult.Partial] /
     * [IconApplyResult.FellBackToDefault]）才写。失败不写，
     * 从而杜绝「界面说换了、桌面没换」的假成功。
     *
     * @return 见 [IconApplyResult]。调用方**必须**依据返回值提示，
     *   不要把它当成 `Unit` 丢弃。
     */
    suspend fun applyBuiltInIcon(id: String): IconApplyResult = withContext(Dispatchers.IO) {
        // 无法识别的 id 一律落到默认套：宁可切到默认，也不要因为一个脏字符串就什么都不做
        //（那样用户会以为按钮坏了）。落回默认**之后**依然如实告诉界面
        //「你给的那个 id 我不认识，已经切到默认」，而不是冒充成用户要的那一套。
        //
        // 判空也算「不认识」：DataStore 读出来可能是空串（历史脏值），
        // 而 `"" !in builtInIconIds` 本来也成立，这里写出来只是让意图更明确。
        val unknown = id.isBlank() || id !in builtInIconIds
        val targetId = if (unknown) defaultIconId else id
        val target = aliasComponentOf(targetId)
            ?: return@withContext IconApplyResult.Failed(
                mismatchReason(targetId, detail = null)
            )

        when (val enableOutcome = setComponentEnabled(target, enabled = true)) {
            is EnableOutcome.Failed -> {
                Log.w(TAG, "启用 icon alias 失败：$targetId —— ${enableOutcome.reason}")
                return@withContext IconApplyResult.Failed(
                    mismatchReason(targetId, detail = enableOutcome.reason)
                )
            }

            EnableOutcome.Done -> Unit
        }

        // 目标已确认启用，现在才能安全地禁用其余的。
        // 收集失败项而不是见错就抛：目标已生效，此时中止只会让界面误以为没换成。
        val stuck = builtInIconIds
            .filter { it != targetId }
            .mapNotNull { otherId ->
                val component = aliasComponentOf(otherId) ?: return@mapNotNull otherId
                when (val outcome = setComponentEnabled(component, enabled = false)) {
                    is EnableOutcome.Failed -> {
                        Log.w(TAG, "禁用 icon alias 失败：$otherId —— ${outcome.reason}")
                        otherId
                    }

                    EnableOutcome.Done -> null
                }
            }

        // 平台调用全部走完才写设置（顺序反过来会造成 DataStore 与桌面状态不一致）。
        settings.setDevIconStyle(targetId)

        when {
            // 目标生效了，只是有旧 alias 没关掉：如实报 Partial，但设置照写。
            stuck.isNotEmpty() -> IconApplyResult.Partial(
                "已切到「$targetId」，但有 ${stuck.size} 个旧图标没能关闭" +
                    "（${stuck.joinToString("、")}），桌面上可能短暂多出一个图标；" +
                    "再切一次通常即可清理。"
            )

            unknown -> IconApplyResult.FellBackToDefault

            else -> IconApplyResult.Success
        }
    }

    /**
     * 一键恢复默认。
     *
     * 需求要的两件事一起做（界面上是一个按钮，所以必须原子地完成）：
     * 1. 把内置图标切回**默认**那套（alias 层面真正生效）；
     * 2. **清除自定义图标**（DataStore 里的路径 + 磁盘上的文件）。
     *
     * 两步都做了才算「默认」：只切 alias 不清自传图，用户会看到「内置是默认、下面预览还挂着我上传的图」；
     * 只清自传图不切 alias，桌面上还是蓝色。因此这个函数**不提供只做一半的入口**。
     *
     * @return 内置图标的切换结果（语义见 [IconApplyResult]）。自定义图标的清理
     *   即使失败也不影响返回值（见 [clearCustomIcon]：那里失败只留一个孤儿文件，
     *   不影响任何行为）。
     */
    suspend fun restoreDefault(): IconApplyResult {
        val switched = applyBuiltInIcon(defaultIconId)
        clearCustomIcon()
        return switched
    }

    // ── 自定义图标：读写 ──────────────────────────────────────────────────

    /**
     * 保存裁切好的自定义图标（512×512 JPEG，写在应用私有目录）。
     *
     * @return 落盘后的绝对路径；失败返回 null（界面要给出提示，不能静默地"以为存上了"）。
     *   成功时**同时**把路径写进 DataStore，因此调用方拿到非 null 就代表"已生效"，
     *   不需要自己再写一次设置。
     */
    suspend fun saveCustomIcon(bitmap: android.graphics.Bitmap): String? =
        withContext(Dispatchers.IO) {
            if (!AppIconStore.saveCustomIcon(context, bitmap)) return@withContext null
            val path = AppIconStore.storedPath(context)
            settings.setDevCustomIconPath(path)
            path
        }

    /**
     * 清除自定义图标：DataStore 里的路径与磁盘上的文件一起清。
     *
     * **只清自定义图标，不动内置图标的选择**——「清掉我上传的图」与
     * 「桌面图标切回默认」是两件事，只是「一键恢复默认」那个按钮会同时做。
     * 分开之后，两件事实现在一处、语义各自单一。
     */
    suspend fun clearCustomIcon() = withContext(Dispatchers.IO) {
        settings.setDevCustomIconPath(null)
        AppIconStore.deleteCustomIcon(context)
    }

    /**
     * 解码自定义图标供界面预览。
     *
     * @param path DataStore 里的路径；文件不存在 / 已损坏返回 null，
     *   调用方据此回落到「未设置」的占位显示。
     *   注意不能只看「路径非 null」就显示图片：数据在、文件被清掉的情况是存在的。
     */
    suspend fun decodeCustomIcon(path: String): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) { AppIconStore.decodeStored(path) }

    /**
     * 解码用户选中的原图，供裁切页使用（已按 EXIF 摆正方向、已降采样）。
     *
     * 转发给 `AvatarStore` 的实现：那里已经把「ImageDecoder 自动摆正方向」
     * 与「强制软件位图（硬件位图不能画进软件 Canvas）」两个坑踩平了，没必要重写。
     */
    suspend fun decodeForCrop(uri: android.net.Uri): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) { AppIconStore.decodeForCrop(context, uri) }

    /**
     * 把一套内置图标渲染成位图，供界面预览。
     *
     * ## 为什么不能直接用 `painterResource(R.mipmap.ic_launcher_check)`
     *
     * `painterResource` 走的解码路径只认**位图**与**矢量图**两类资源；
     * 而这里几个 `@mipmap/ic_launcher_*` 是 `<adaptive-icon>` XML，
     * 系统把它解析成 `AdaptiveIconDrawable`，既不是 `BitmapDrawable` 也不是 `VectorDrawable`，
     * 交给它解会失败（解码异常 / 空白）。所以这里自己把它画进一张软件位图再显示。
     *
     * ## 画出来的是「系统自带的圆角方形遮罩」后的样子
     *
     * 看过平台源码（`AdaptiveIconDrawable.draw()`）后确认：它并不是把背景图层原样铺满输出，
     * 而是先把背景 + 前景画进一张 108×108 的内部图层位图，再用 **mask 路径**去裁
     * （`canvas.drawPath(mMaskScaleOnly, mPaint)`）。所以渲染出来的是
     * 「带系统标准圆角方遮罩、遮罩之外透明」的图标，与桌面上的观感一致——
     * 这正是我们想要的，**不需要**自己在外面再叠一层 `clip` 或圆形遮罩。
     *
     * 也因此**不要**用 `ContentScale.Crop` 之类去二次裁切，那会把已经裁好的边缘再切一刀。
     * 输出位图遮罩外是透明的，所以界面必须给它垫一个背景色（用
     * `MaterialTheme.colorScheme.surfaceContainerHigh` 这类），否则透明区在深色 / 浅色下
     * 会透出页面底色，看着像图标缺了一块。
     *
     * @return 渲染失败（资源缺失等）返回 null。
     */
    suspend fun renderBuiltInIcon(@DrawableRes resId: Int, sizePx: Int): android.graphics.Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.getDrawable(resId) ?: return@runCatching null
                val bitmap = android.graphics.Bitmap.createBitmap(
                    sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
                bitmap
            }.getOrNull()
        }

    // ── 内部：alias 查找与启用状态 ────────────────────────────────────────

    /**
     * 取某个内置图标对应的 alias [ComponentName]。
     *
     * 两条路径，**优先系统给出的类名，其次才是自己推导**。
     *
     * ## 为什么不能直接 `ComponentName(context, "com.morecheck.icon.Blue")`
     *
     * `ComponentName` 的两段含义不同，而这里两段**恰好不是同一个值**：
     * - **包名**那一段必须是**实际安装包名**（= `context.packageName`），
     *   因为 PackageManager 是按安装包名（applicationId）索引组件的；
     * - **类名**那一段的前缀是 manifest 的 **namespace**（本工程 `com.morecheck`），
     *   与 applicationId 无关。
     *
     * debug 变体开了 `applicationIdSuffix = ".debug"`，于是两者分叉：
     * 包名是 `com.morecheck.debug`，而类名仍然是 `com.morecheck.icon.Blue`。
     * 若用 `context.packageName` 去拼类名，会得到
     * `com.morecheck.debug.icon.Blue` —— 一个**根本不存在的类**，切换静默失败，
     * 而且**只在 debug 包上复现**，release 包完全看不出来（最难查的一类问题）。
     *
     * ## 路径一（首选）：问系统
     *
     * 从已安装包的组件表里按后缀找到 alias，直接用 `ActivityInfo.name` ——
     * 那是**系统解析出来的全限定类名**，不受 namespace、变体、混淆影响，最可靠。
     * 包名那一段仍然用 `context.packageName`（不能用 `ActivityInfo.packageName`，
     * 那是 namespace，在 debug 包上与安装包名不同）。
     *
     * ## 路径二（兜底）：由 Application 类名反推 namespace
     *
     * 唯一的风险是「某些 ROM 的 `GET_ACTIVITIES` 不返回被禁用的组件」——
     * 而本功能**同时有三个 alias 是 `enabled="false"`**，正是要靠这条查询找到它们。
     * AOSP 的实现不按 enabled 过滤（见 [activityInfos] 的说明），但各家 ROM 未必一致。
     * 所以这里留一条不依赖组件表的推导：manifest 里写 `android:name=".MoreCheckApp"`，
     * 系统已展开成 `com.morecheck.MoreCheckApp`，砍掉最后一段即 namespace 前缀。
     *
     * 两条都失败才返回 null，由 [applyBuiltInIcon] 如实报失败（绝不静默）。
     *
     * @return 取不到时返回 null。
     */
    private fun aliasComponentOf(id: String): ComponentName? {
        // ⚠️ 匹配必须**忽略大小写**。
        //
        // 实测踩过的坑（v1.1.66 实机）：id 用小写（`purple`，它是 DataStore 里存的稳定标识），
        // 而 manifest 里的 alias 类名是**首字母大写**的 `.icon.Purple`
        // （Java 类名惯例，且 `ActivityInfo.name` 给的正是这个大小写敏感的类名）。
        // 于是 `endsWith(".icon.purple")` 永远匹配不上，代码掉进下面的「路径二」，
        // 拼出一个**根本不存在的类** `com.morecheck.icon.purple`，
        // `setComponentEnabledSetting` 抛 `IllegalArgumentException`，
        // 又被 `runCatching` 吞成 `false` —— 表现就是「点紫色图标毫无反应」，
        // 日志只有一句「启用 icon alias 失败：purple」。
        //
        // 为什么不把 `BUILT_IN_ICON_IDS` 直接写成大写：那个 id 是**持久化标识**
        // （存在 DataStore 里，且与 `R.mipmap.ic_launcher_*` 的命名意图一致），
        // 改名会让老用户已存的 `icon_style` 值失效；也避免 id 与「界面显示名」耦合。
        // 大小写差异只在这一处匹配时抹平即可。
        val suffix = "$ALIAS_PACKAGE_SUFFIX.$id"

        // 路径一：系统给出的全限定类名（忽略大小写比较）。
        val info = activityInfos().firstOrNull {
            it.name?.endsWith(suffix, ignoreCase = true) == true
        }
        if (info?.name != null) {
            // ⚠️ 必须用**系统给的原名**（`info.name`），不能自己拼小写版本：
            // ComponentName 的类名是大小写敏感的，拼错一位就是一个不存在的组件。
            return ComponentName(context.packageName, info.name)
        }

        // 路径二：由 Application 类的全限定名反推 namespace 前缀再拼。
        // 本工程必然有自定义 Application，所以 className 正常不为 null；
        // 万一为 null 或没有包前缀，就放弃（返回 null），不猜。
        //
        // ⚠️ 拼的时候要把 id 的**首字母大写**，与 manifest 里的类名保持一致：
        // 这条兜底路径正是「路径一失败」时才会走到，若这里再拼成小写，
        // 等于把同一个错误又犯一次（原先就是这个问题）。
        val namespacePrefix = context.applicationInfo?.className?.substringBeforeLast('.')
        if (!namespacePrefix.isNullOrEmpty() && namespacePrefix.contains('.')) {
            val className = namespacePrefix + ALIAS_PACKAGE_SUFFIX + "." +
                id.replaceFirstChar { it.uppercaseChar() }
            return ComponentName(context.packageName, className)
        }

        return null
    }

    /**
     * 已安装包里声明的全部 Activity（**包含** `activity-alias`，也**包含被禁用的**）。
     *
     * `activity-alias` 在 PackageManager 眼里就是一个 Activity 条目，
     * 所以用 `GET_ACTIVITIES` 就能一并取到，不需要别的查询接口。
     *
     * ## `MATCH_DISABLED_COMPONENTS` 为什么必须带上
     *
     * 本功能**同时存在三个 `enabled="false"` 的 alias**（只有默认那套是启用的），
     * 而这里正是要找到那几个被禁用的，才能把它们启用起来。
     * AOSP 的 `PackageInfoCommonUtils` 在收集 `activities` 时只按
     * `MATCH_SYSTEM_ONLY` / `MATCH_DIRECT_BOOT_*` 过滤（见其 `isMatch`），
     * **不看 enabled**，所以禁用组件本来就会出现；
     * 但把 `MATCH_DISABLED_COMPONENTS` 显式写上，是把「我要禁用的组件」这个意图
     * 交给平台去满足，而不是依赖某个实现细节——换 ROM 也不会变。
     *
     * 注意这两个常量的类型：`GET_ACTIVITIES` 与 `MATCH_DISABLED_COMPONENTS` 都是 `int`，
     * 而 API 33+ 的 `PackageInfoFlags.of` 收 `long`，所以先 `toLong()` 再或，
     * 保持与 flag 类型一致的写法（避免有人日后加上高位 flag 时 `int` 溢出）。
     */
    private fun activityInfos(): List<ActivityInfo> {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES.toLong() or
            PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33 起旧的 int flags 重载被废弃，改用 PackageInfoFlags。
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags.toInt())
        }
        return info.activities?.toList().orEmpty()
    }

    /**
     * 改一个组件的启用状态。
     *
     * [PackageManager.DONT_KILL_APP] 的作用与必要性见类注释「三」——
     * 少了它，切换的瞬间应用会被系统杀掉。
     *
     * ## 为什么返回 [EnableOutcome] 而不是 `Boolean`（v1.1.69 修）
     *
     * 原先这里只返回 `isSuccess`，而异常体在 `runCatching` 里被打进日志。
     * 问题在于**日志和界面是两条路**：用户看到的是「图标切换失败，请重试」，
     * 而唯一有诊断价值的那句 `Log.w` 躺在他的设备上、不 root 取不出来。
     * 改成把异常 message 一并**带回去**，界面就能把「为什么」直接显示给用户，
     * 也就能直接贴给我排查，不必再走一轮"加日志 → 打包 → 复现"。
     *
     * @return [EnableOutcome.Done] 表示平台调用返回了；[EnableOutcome.Failed]
     *   携带异常类名与 message（[reason]），由 [applyBuiltInIcon] 决定是否中止整次切换。
     */
    private fun setComponentEnabled(component: ComponentName, enabled: Boolean): EnableOutcome =
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component,
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
        }.fold(
            onSuccess = { EnableOutcome.Done },
            onFailure = { error ->
                // ⚠️ **原因必须同时进日志与返回值**，不能只 `isSuccess`。
                //
                // 这里原先只返回布尔值，异常被 `runCatching` 吃掉：
                // 实机出错时日志只显示「启用 icon alias 失败：purple」，
                // 既不知道是 SecurityException 还是 IllegalArgumentException，
                // 也无从判断是组件名拼错还是平台限制——排查成本立刻翻几倍。
                // 失败路径本来就罕见，多打一行不影响性能，却能省掉一轮「加日志再打包」。
                //
                // 取 `javaClass.simpleName` 而不是 `toString()`：后者只给 message，
                // 而「哪一种异常」往往比 message 本身更能定位问题。
                val reason = "${error.javaClass.simpleName}(${error.message})"
                Log.w(TAG, "setComponentEnabled 失败：$component enabled=$enabled —— $reason", error)
                EnableOutcome.Failed(reason)
            }
        )

    /**
     * 当前**实际启用**的那个内置图标 id（以系统里的组件状态为准，不是 DataStore 里的值）。
     *
     * 存在两个正当用途，都不是「界面回显」：
     * 1. 诊断 / 自测：确认 DataStore 与系统状态是否一致；
     * 2. 兜底自检：万一出现「一个 alias 都没启用」（应用在桌面上彻底消失），
     *    可以据此发现并修回默认套。
     *
     * 界面上的选中态一律读 DataStore（[selectedIconId]）：那是用户的意图，
     * 且切换失败时不会写回，两者语义不同。
     */
    fun currentEnabledIconId(): String? {
        val pm = context.packageManager
        return builtInIconIds.firstOrNull { id ->
            val component = aliasComponentOf(id) ?: return@firstOrNull false
            isEnabledNow(pm, component)
        }
    }

    /**
     * 某个组件此刻是否处于「启用」。
     *
     * ⚠️ `getComponentEnabledSetting` 在**从未被显式设置过**时返回 `COMPONENT_ENABLED_STATE_DEFAULT`，
     * 此时真正的启用状态由 **manifest 里的 `android:enabled`** 决定。
     * 只判 `== COMPONENT_ENABLED_STATE_ENABLED` 会把「manifest 里写着 enabled=true、
     * 但从没被运行期设置过」的 alias 误判成未启用——首次安装后正是这个状态。
     */
    private fun isEnabledNow(pm: PackageManager, component: ComponentName): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> {
                // DEFAULT（或未知取值）：回落到 manifest 声明的 enabled。
                val info = activityInfos().firstOrNull { it.name == component.className }
                info?.enabled == true
            }
        }

    /**
     * 兜底自检：若一个内置图标 alias 都没启用，把默认那套重新启用。
     *
     * 正常路径永远不会走到这里（[applyBuiltInIcon] 先启用后禁用，且启用失败即中止）。
     * 但「零个图标」是本功能唯一会让应用**在桌面上彻底消失**的失败模式，
     * 代价太大，所以宁可多写一个自检：只要有一次机会能把它救回来，就值得。
     *
     * ## 为什么整个函数体都在 IO 线程
     *
     * 它要连着调好几次 PackageManager（`getPackageInfo` / `getComponentEnabledSetting`
     * 都是跨进程调用）。这些调用在真机上通常很快，但**都不该发生在主线程**：
     * 本页是在组合期由 `LaunchedEffect` 触发的，一旦遇上系统繁忙，
     * 主线程阻塞就直接表现为掉帧或 ANR。放进 `withContext(Dispatchers.IO)` 成本为零，
     * 而 `applyBuiltInIcon` 早就是这么写的，两边保持一致。
     *
     * @return 是否执行了修复。
     */
    suspend fun repairIfNoIconEnabled(): Boolean = withContext(Dispatchers.IO) {
        if (currentEnabledIconId() != null) return@withContext false
        Log.w(TAG, "检测到没有任何内置图标 alias 处于启用状态，正在恢复默认套")
        aliasComponentOf(defaultIconId)?.let { setComponentEnabled(it, enabled = true) }
        true
    }

    /**
     * 拼一句**给用户看**的失败原因。
     *
     * 带 `detail`（平台异常）时说明组件名是查到了、但平台调用被拒——
     * 这时要把「组件名」和「平台异常」都给出来，否则无法区分
     * 「alias 没同步」与「ROM 不允许改组件状态」这两种完全不同的情况：
     * 前者要更新应用，后者只能刷新桌面缓存。
     */
    private fun mismatchReason(targetId: String, detail: String?): String =
        if (detail == null) {
            "找不到内置图标「$targetId」对应的桌面组件（activity-alias）。" +
                "这通常意味着应用的图标声明与代码不同步，请更新到最新版本后重试。"
        } else {
            "系统拒绝为该图标启用桌面组件（$targetId）：$detail。" +
                "常见原因是桌面的图标缓存需要刷新，可重启桌面或重装应用后再试。"
        }

    private companion object {
        const val TAG = "AppIconRepository"

        /**
         * alias 的包前缀：`.icon.<id>` 对应 manifest 里 `android:name=".icon.Blue"` 这样的写法。
         *
         * 只用于**按后缀匹配**（见 [aliasComponentOf]），不用于拼完整类名，
         * 因为完整类名的包前缀在 debug 变体下与安装包名不同。
         */
        const val ALIAS_PACKAGE_SUFFIX = ".icon"

        /**
         * 全部内置图标 id。**必须与 `AndroidManifest.xml` 的 `activity-alias` 一一对应**，
         * 且首项等于 [SettingsRepository.DEFAULT_ICON_STYLE]。
         *
         * ## 本轮（需求 8/11）：蓝 / 紫 / 橙三套**已删除**，新增 pine / snow / mint 三套
         *
         * - 蓝 / 紫 / 橙曾是「同一前景 + 换背景色」的实现，需求要求删除——
         *   manifest 的三个 alias、`colors.xml` 的三个背景色与 `IconPickerScreen`
         *   的映射**四处一起删净**（完整理由见 manifest 注释）。
         *   ⚠️ 若有用户停在这三套之一，其 DataStore 里存的是 `blue` / `purple` /
         *   `orange` 这样的脏 id：[applyBuiltInIcon] 对不认识的 id 会**自动回落默认套**，
         *   因此不需要任何迁移代码——这正是"脏 id 落默认"这条既有口径买到的兼容性。
         * - pine / snow / mint 是「一个木函」Logo 家族的三张新前景
         *   （来源、裁切与授权口径见各 mipmap XML 的注释），
         *   背景色按「先量前景亮度再配色」的方法取（见 `values/colors.xml`）。
         *
         * ## 中间四套（v1.1.69 需求 2b）：四张手绘矢量前景 + 四个新背景色
         *
         * `check` / `star` / `flame` / `leaf` 四套各自配一个
         * `res/drawable/ic_icon_fore_*.xml`（108×108 viewport、纯白填充、
         * 图形收在中心直径 66 的安全区内），背景是白前景专用的四个深色
         * （`#00ACC1` / `#37474F` / `#E53935` / `#43A047`，取值理由见 `values/colors.xml`）。
         * 为什么这四套不用同一张前景：需求要的就是**四个不同的图案**，
         * 而继续复用那张 PNG 只能得到"八个换汤不换药的颜色"。
         *
         * ## `tree`：复用「一个木函」的品牌 Logo 位图
         *
         * 前景是 `@drawable/ic_fore_tree`（432×432 透明底 PNG），
         * 背景 `#004D40`。**它为什么不能配白底**（这样做过一次，实测几乎看不见）、
         * 以及第三方素材的授权边界，见 `res/mipmap-anydpi-v26/ic_launcher_tree.xml`
         * 与 `res/values/colors.xml` 里的说明。
         * 这一套与其它各套一样走 alias，**没有**任何特殊路径。
         *
         * ## 顺序即界面顺序，**首项必须是 `"default"`**
         *
         * - 界面（`IconPickerScreen.BuiltInIconRow`）按本列表顺序铺开，且**依赖它推导选中态**；
         * - `"default"` 排第一还有一个语义理由：它是所有失败路径的归宿
         *   （[applyBuiltInIcon] 遇到无法识别的 id 就落到它），排在最前符合
         *   「默认 = 第一选项」的直觉。
         *
         * ⚠️ 加 / 删一套图标要同时改四处，步骤见类注释第七节（那里给了可照抄的模板）。
         * 漏改任何一处都不会崩，而是表现为「某一格空白 / 点了没反应」，
         * 所以这四处的一致性必须靠人守住——这是本功能已知的唯一维护成本。
         */
        val BUILT_IN_ICON_IDS = listOf(
            "default",
            "check", "star", "flame", "leaf", "tree",
            "pine", "snow", "mint",
            // 与 1.1.70 对齐新增两套（沿用同一批设计稿）：
            // - checklist：曜石黑底 + 米白四宫格（其中一格打勾）；
            // - calendar ：青绿底 + 白色日历镂空对勾。
            // 均为「渐变背景 drawable + 透明前景 PNG」，各自一套自适应图标 XML。
            "checklist", "calendar"
        )
    }
}

/**
 * 自定义图标的文件读写（`filesDir/app_icon/custom_icon.jpg`）。
 *
 * ## 为什么是 `object`（顶层单例）而不是 `AppIconRepository` 的成员
 *
 * 这些函数的输入只有 `Context` + 一个路径，**与任何用户设置无关**，
 * 不需要 DataStore、也不需要知道「当前选中的是哪一套」。
 * 拆成纯函数式的小工具后，[AppIconRepository] 负责「业务顺序」，
 * 这里只负责「把文件写对 / 读对」，两边都能各自读懂。
 *
 * ## 为什么写在 filesDir 而不是外部存储 / MediaStore
 *
 * 与 `AvatarStore` 同一取舍：图标只给本应用用，写进应用私有目录就**完全不需要任何存储权限**
 * （不用 `READ_MEDIA_IMAGES`，也不用处理分区存储在各版本上的差异）。
 * 代价是用户没法从相册里看到这张图，而这本来就不是需求要的。
 */
internal object AppIconStore {

    private const val DIR_NAME = "app_icon"
    private const val FILE_NAME = "custom_icon.jpg"

    /**
     * 导出边长（像素）。
     *
     * 复用 `AvatarStore.EXPORT_SIZE`（512）而不是另写一个数：两处都是「小尺寸方图」，
     * 512 在 3x 屏上已经远清晰于任何显示位（本页预览约 72dp），没有理由不一样。
     */
    const val EXPORT_SIZE = com.morecheck.util.AvatarStore.EXPORT_SIZE

    /** 裁切页解码上限，复用头像那套（原图动辄数千万像素，整张读进内存几乎必然 OOM）。 */
    private const val CROP_DECODE_MAX_PX = com.morecheck.util.AvatarStore.CROP_DECODE_MAX_PX

    /**
     * 自定义图标文件。**固定文件名**：重新上传时直接覆盖，不留一堆历史文件；
     * 「恢复默认」要清理的东西也因此是确定的（删这一个文件即可）。
     */
    private fun customIconFile(context: Context) =
        java.io.File(java.io.File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 存进 DataStore 的绝对路径。 */
    fun storedPath(context: Context): String = customIconFile(context).absolutePath

    /**
     * 把裁好的正方形位图写成 JPEG。
     *
     * @return 是否成功。写失败（磁盘满 / IO 异常）时界面要能给出提示，
     *   而不是静默地"以为保存了"。
     */
    fun saveCustomIcon(context: Context, bitmap: android.graphics.Bitmap): Boolean = runCatching {
        val file = customIconFile(context)
        // 目录可能不存在（首次安装后从没设过图标），必须先建。
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            // `compress` 自己会返回 false（例如编码器拒绝该配置），不能只看有没有抛异常。
            bitmap.compress(
                android.graphics.Bitmap.CompressFormat.JPEG,
                com.morecheck.util.AvatarStore.JPEG_QUALITY,
                out
            )
        }
    }.getOrDefault(false)

    /** 解码已保存的自定义图标（预览用）。文件不存在 / 已损坏返回 null。 */
    fun decodeStored(path: String): android.graphics.Bitmap? =
        com.morecheck.util.AvatarStore.decodeStored(path, EXPORT_SIZE)

    /** 解码用户选中的原图供裁切（转发头像那套：EXIF 摆正 + 强制软件位图）。 */
    fun decodeForCrop(context: Context, uri: android.net.Uri): android.graphics.Bitmap? =
        com.morecheck.util.AvatarStore.decodeForCrop(context, uri, CROP_DECODE_MAX_PX)

    /**
     * 删除自定义图标文件（「一键恢复默认」）。
     *
     * 用 `runCatching` 吞掉异常：文件本来就不存在、或删不掉，都不该让「恢复默认」失败——
     * **数据层已经清干净了**，残留一个几 KB 的孤儿文件不影响任何行为
     * （下次上传会直接覆盖同名文件）。
     */
    fun deleteCustomIcon(context: Context) {
        runCatching {
            val file = customIconFile(context)
            if (file.exists()) file.delete()
        }
    }
}
