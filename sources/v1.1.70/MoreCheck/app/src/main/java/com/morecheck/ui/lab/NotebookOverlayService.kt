package com.morecheck.ui.lab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.morecheck.R
import com.morecheck.data.NotebookStore
import com.morecheck.ui.nav.TabDestination
import com.morecheck.ui.theme.AccentColor
import com.morecheck.ui.theme.MoreCheckSplashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 把「当前这条笔记」显示成浮在其他应用之上的小窗，可拖动、可关闭。
 *
 * ## 平台限制：为什么必须是**前台** Service（本文件最关键的取舍）
 *
 * 1. **Android 8+（API 26）起，应用在后台时不能启动普通后台 Service**：
 *    `startService()` 会抛 `IllegalStateException: Not allowed to start service ...:
 *    app is in background`。本功能的入口在 Activity（应用在前台），冷启动时
 *    起普通 Service 理论可行；但悬浮窗的**用途**恰恰是"用户切到别的应用之后还要一直在"，
 *    而后台进程里的普通 Service 随时可能被系统回收——它与需求天然矛盾。
 *    前台 Service 带常驻通知，被回收的优先级低得多，是唯一与需求相符的选择。
 *
 * 2. **Android 12+（API 31）起，从后台启动前台 Service 也受限**
 *    （`ForegroundServiceStartNotAllowedException`）。本实现规避该限制的方式是：
 *    启动动作**只发生在用户点击菜单项的那一刻**（应用必然在前台），
 *    没有任何"后台自动拉起悬浮窗"的路径。这是**有意**的设计，不是遗漏。
 *
 * 3. **Android 14+（API 34）起，前台 Service 必须声明类型**
 *    （manifest 的 `android:foregroundServiceType` + 对应 `FOREGROUND_SERVICE_*` 权限），
 *    否则 `startForeground` 抛 `MissingForegroundServiceTypeException`。
 *
 *    ⚠️ **类型选择的取舍**：本功能不属于任何平台预置类型——它不是媒体播放、不是定位、
 *    不是数据同步，就是"一个用户主动打开的小工具窗口"。平台给这种情形的出口是
 *    `specialUse`（配 manifest 里的 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途）。
 *    `specialUse` 在 Google Play 上架时需要提交用途说明并接受审核；本项目是
 *    **自用 / 内部分发**（见 `dist/install.ps1`，不走 Play 审核），因此用它最贴切。
 *    若日后要上架且被拒，替代方案只有语义牵强的 `dataSync`（同样要审核），
 *    或退回"悬浮窗与 Activity 同生命周期"的降级实现——都不如现在干净。
 *
 * 4. **`TYPE_APPLICATION_OVERLAY`（API 26+）**是唯一还能用的悬浮窗类型：
 *    更早的 `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 自 8.0 起对普通应用已失效，
 *    继续用会直接拿不到窗口。
 *
 * ## 为什么这个 Service 要自己实现三个 `ViewTree*Owner`
 *
 * `ComposeView` 挂在 `WindowManager` 上时**父链里没有 Activity**，
 * 因此 `ViewTreeLifecycleOwner` / `ViewTreeSavedStateRegistryOwner` /
 * `ViewTreeViewModelStoreOwner` 全都取不到。已核对本项目解析到的 `ui` 1.7.8 字节码
 *（`AndroidComposeView` 在 `viewTreeOwners` 失效时主动抛异常）：
 * 缺 LifecycleOwner 会抛
 * `IllegalStateException: Composed into the View which doesn't propagate ViewTreeLifecycleOwner!`，
 * 缺 SavedStateRegistryOwner 也会抛对应那一句。所以这里手工提供三者。
 *
 * 组合策略用 `DisposeOnDetachedFromWindow` 而**不是**默认的
 * `DisposeOnViewTreeLifecycleDestroyed`：后者的语义是"去父链上找 LifecycleOwner 并在它
 * 销毁时释放组合"，而我们的 Owner 由 Service 自己管理，用"从窗口分离即释放"
 * 最直观，也正好对应 `onDestroy` 里 `removeView` 的时机。
 *
 * ## 内容会不会跟着笔记更新
 *
 * **不会：打开时取一次快照**（内容由界面通过 Intent extra 传入，见 [NotebookOverlay.open]）。
 * 这是有意的取舍：
 * - 需求只要求"把当前这条笔记显示成小窗"；
 * - 实时同步意味着 Service 要长期 `collect` DataStore 的流，于是
 *   "用户在记事本里删掉的图，小窗里还显示着"这类一致性问题全都要处理，
 *   还要额外解决"小窗自己是否也在改数据"的回环；
 * - 用户随时可以关掉小窗重开（关掉再点一次菜单），成本远低于维护一套同步机制。
 *   若将来确实要实时，正确做法是让 Service 订阅 `NotebookStore` 的流并用
 *   状态驱动 `ComposeView` 重组——但那是另一轮需求，本轮**不引入**。
 */
class NotebookOverlayService : Service() {

    /**
     * 组合所需的 Owner 三件套。
     *
     * 用一个内部类把三者绑在一起，是因为它们**必须同生共死**：`ComposeView` 会同时读取
     * 三者，任何一个是 null 都会抛异常；分成三个独立字段维护反而容易出现
     * "某个分支下只设了两个"这种漏设。
     */
    private inner class OverlayOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry

        override val viewModelStore: ViewModelStore = ViewModelStore()

        private val savedStateController = SavedStateRegistryController.create(this)

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        /**
         * 调用顺序是**平台强制**的，不能颠倒：已解包本项目解析到的 `savedstate` 1.2.1
         * 字节码确认，`performRestore` 内部有
         * `check(attached) { "You must call performAttach() before calling performRestore(Bundle)." }`。
         * 因此必须先 `performAttach()`（把自己挂到 lifecycle 上）再 `performRestore(...)`。
         *
         * 传 `null` 表示**没有需要恢复的状态**：悬浮窗是瞬时 UI，
         * 进程被杀后"重建一个旧内容的小窗"本身就没有意义（快照也早已过期）。
         */
        fun create() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            // 直接推到 RESUMED：窗口马上就要显示，中间的 STARTED 态没有意义。
            // `LifecycleRegistry` 允许一步跨到目标态，内部会补齐中间事件。
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        /** Service 销毁时同步走完 DOWN，让订阅者（含 Compose）收到销毁通知。 */
        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    /**
     * 悬浮窗的根布局：**拖动逻辑的落点**。
     *
     * ## 为什么不能在 `ComposeView` 上挂 `OnTouchListener`
     *
     * 这是本文件踩过的一个真实平台坑：`ComposeView`（即 `AndroidComposeView`）
     * **自己重写了 `dispatchTouchEvent`**，并且不调用 `super.dispatchTouchEvent`——
     * 而 `View.OnTouchListener` 正是在 `View.dispatchTouchEvent` 里被回调的
     *（已用 `javap -c` 反编译确认：`AndroidComposeView.dispatchTouchEvent` 全程
     * 走自己的 `handleMotionEvent`，没有任何 `super` 调用）。
     * 结论：挂在 `ComposeView` 上的 `setOnTouchListener` **永远不会被调用**，
     * 拖动会表现为"完全没反应"且**没有任何报错**，非常难查。
     *
     * 因此这里用一层普通 `FrameLayout` 作为窗口根视图，在**它的**
     * `dispatchTouchEvent` 里统一处理拖动，`ComposeView` 只当子视图。
     *
     * ## 为什么在 `dispatchTouchEvent` 里做，而不是 `onInterceptTouchEvent`
     *
     * `onInterceptTouchEvent` 只能"拦截"，拦下来之后就再也无法把事件交还给子视图；
     * 而本窗口需要**两种手势并存**：拖动窗口、点关闭按钮。
     * 所以在 `dispatchTouchEvent` 里按"有没有移动超过 touchSlop"来区分：
     * - 移动超过 slop → 判定为拖动，移动窗口，并吞掉后续事件；
     * - 始终没超过 slop → 一路交给 `super`（即 Compose 子树），点击照常生效。
     */
    private inner class OverlayRootLayout(context: Context) : FrameLayout(context) {

        private var dragging = false
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // ⚠️ 这里读的是 **Service 的 `windowParams`**，不是本类从 `View` 继承来的
            // `layoutParams`（那是 `ViewGroup.LayoutParams` 类型，没有 x/y）。
            // 若不加区分地写 `layoutParams`，命中的是**继承来的那一个**：
            // 轻则 `params.x` 编译不过，重则改到子视图的布局参数、窗口纹丝不动。
            // 这是"内部类 + 同名属性"最容易踩的坑，所以 Service 那边特意用了不同的名字。
            val params = windowParams ?: return super.dispatchTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startX = params.x
                    startY = params.y
                    // 用 `rawX/rawY`（屏幕坐标）记录起点，而不是 `ev.x/ev.y`：
                    // 后者是**相对窗口自身**的坐标，窗口一动它就跟着变，
                    // 会算出"越拖越快 / 越拖越往回跳"的经典 bug。
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        // 拖动已成立，通知子树"这次按下作废"。
                        // 子树必须先收到 ACTION_DOWN（否则点击永远无法开始），
                        // 所以这里补发一个 CANCEL 而不是干脆不发。
                        cancelChildGesture(ev)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        // `updateViewLayout` 是移动窗口的唯一方式
                        //（只改 LayoutParams 不会自动生效）。
                        // 个别 ROM 在窗口已被系统回收后会抛异常，吞掉即可：
                        // 拖动失败不该让整个应用崩掉。
                        runCatching { windowManager.updateViewLayout(this, params) }
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        // 吸附到最近的一侧（v1.1.69 需求 1）。
                        //
                        // 判据用**窗口中心点**相对屏幕中线的位置，而不是窗口左上角：
                        // 左上角在中线左侧但窗口大半在右侧时，按左上角判会吸到左边，
                        // 用户看到的是"拖到右边却弹回左边"。中心点判据与视觉一致。
                        //
                        // `snapToSide` 的 runCatching：个别 ROM 在窗口已被系统回收后
                        // 调 `updateViewLayout` 会抛，吸附失败不该让应用崩掉
                        // （与拖动本身的口径一致）。
                        snapToSide()
                        // 拖动结束的这一下不交给子树，否则会被当成一次"点击"，
                        // 而用户的本意只是把窗口挪个位置。
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        /**
         * 给子树补发一个 `ACTION_CANCEL`，通知它"这次按下作废了"。
         *
         * 不补发的后果：Compose 的 `clickable` 会一直停在"按下未抬起"的状态，
         * 表现为关闭按钮残留按下态高亮、后续点击的按下判定错乱。
         *
         * 用 `MotionEvent.obtain(...)` **直接构造**带 CANCEL 的事件，而不是
         * `obtain(ev)` 之后 `setAction`：`setAction` 改的是同一个事件对象，
         * 而 `ev` 是系统交给我们的事件，**改它没有意义**（上游已经派发过 DOWN 了），
         * 直接构造一个新事件意图更清楚。
         *
         * ⚠️ 这里刻意**不**把它写成内联 lambda（例如 `run { super.dispatchTouchEvent(...) }`）：
         * Kotlin **禁止在 lambda 中调用 `super`**，那样写会直接编译不过。
         * 抽成一个私有方法，`super` 就能正常出现在方法体里。
         */
        private fun cancelChildGesture(source: MotionEvent) {
            val cancel = MotionEvent.obtain(
                source.downTime,
                source.eventTime,
                MotionEvent.ACTION_CANCEL,
                source.x,
                source.y,
                source.metaState
            )
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }

        /**
         * 把窗口吸到左侧或右侧边缘（留 [BALL_EDGE_MARGIN_PX] 的呼吸间距）。
         *
         * 只改 x，**不动 y**：垂直方向用户拖到哪就停在哪，强行吸附到某个水平位置
         * 会让"想把窗子放到屏幕中间"变成不可能。
         *
         * 窗口当前宽度从 `windowParams.width` 读而不是写死：小球与展开态宽度不同，
         * 吸右边的算法是 `屏幕宽 - 窗口宽 - 间距`，用错宽度会吸出屏幕外
         * （表现为"拖到右边窗口消失一半"）。
         *
         * ⚠️ **调用顺序约束**：正因为宽度是**现读**的，任何"刚改过尺寸就要吸附"的调用点
         * 都必须**先**让尺寸生效（[applyWindowSize]）、**再**调本方法。
         * 顺序反了会按**旧宽度**算出落点，最典型的后果是"点收成小球，小球被吸到屏幕外"
         *（展开态 720px 算出的右侧 x，配 168px 的小球就出界了）。
         * 本类里两处调用点都遵守这条：
         * - 拖动结束（`ACTION_UP`）：当时并没有改尺寸，天然满足；
         * - `onCollapsedChange` 的收起分支：紧跟在 `applyWindowSize(false)` 之后。
         *
         * ⚠️ 可见性是**故意**不写 `private` 的：v1.1.69 需求 1 的补丁要求
         * 「点标题栏收成小球」也走同一次吸附，而那个调用点在**外层** `NotebookOverlayService`
         * 的 `onCollapsedChange` 里。Kotlin 与 Java 不同：外层类**不能**访问内部类
         * （`inner class`）的 `private` 成员——已用本项目的 Kotlin 编译器实测该规则，
         * 报错原文是 `cannot access 'fun snap(): Unit': it is private in '/Outer.Inner'`
         * （Java 的"外层可访问内部类私有成员"在这里**不成立**）。
         * 本类是 `private inner class` 且只在本文件出现，
         * 去掉 `private` 不会对外暴露任何东西，是最小的改动。
         */
        fun snapToSide() {
            val params = windowParams ?: return
            val width = if (params.width > 0) params.width else BALL_SIZE_PX
            val centerX = params.x + width / 2
            params.x = if (centerX < screenWidthPx / 2) {
                BALL_EDGE_MARGIN_PX
            } else {
                (screenWidthPx - width - BALL_EDGE_MARGIN_PX).coerceAtLeast(BALL_EDGE_MARGIN_PX)
            }
            runCatching { windowManager.updateViewLayout(this, params) }
        }
    }

    private lateinit var windowManager: WindowManager

    private var rootView: OverlayRootLayout? = null
    private var owner: OverlayOwner? = null

    /**
     * 当前挂上去的**窗口**参数。拖动时改它的 `x` / `y` 再 `updateViewLayout`。
     *
     * ⚠️ 名字刻意叫 `windowParams` 而不是 `layoutParams`：内部类 `OverlayRootLayout`
     * 继承自 `FrameLayout`，从 `View` 带来了一个同名的 `layoutParams`。
     * 换个名字让两类参数从命名上就分得开，避免"改错对象、窗口不动"的隐蔽 bug。
     */
    private var windowParams: WindowManager.LayoutParams? = null

    /**
     * 当前是否收起为「侧边小球」（v1.1.69 需求 1）。
     *
     * 展开 / 收起的切换是**状态驱动**的：小球与完整小窗是同一个 Compose 内容的两种形态，
     * 而不是两个窗口 / 两个 View。这样切换时不需要重建 `OverlayOwner` 与窗口参数，
     * 也不会出现"小球还残留一个关不掉的窗口"这类泄漏。
     */
    private var collapsed = false

    /**
     * 屏幕宽度（像素）：拖动结束时用来决定吸附到左侧还是右侧。
     * 在 [onCreate] 取一次（窗口存活期间屏幕尺寸不会变；旋屏时 Activity 重建，
     * 悬浮窗本就随进程销毁，不需要处理）。
     */
    private var screenWidthPx = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 用 `currentWindowMetrics`（API 30+）而不是已废弃的 `defaultDisplay.getSize`：
        // 后者在 API 30+ 会返回**应用工作区**尺寸（不含系统栏），而我们要的是整个屏幕宽，
        // 否则吸附判定会把"右侧"误判成"左侧"。本项目 minSdk 29，故做版本分派。
        screenWidthPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also { windowManager.defaultDisplay.getSize(it) }.x
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知与 `startForeground` 必须**第一时间**做：由 `startForegroundService` 启动的
        // Service 若 5 秒内没调 `startForeground`，系统会抛
        // `ForegroundServiceDidNotStartInTimeException` 并判 ANR。
        // 所以这两行放在任何窗口操作之前。
        //
        // 顺带一个好处：本方法里**没有任何挂起操作**（内容由 extra 传来，
        // 不必在这里读 DataStore），因此这两行与挂窗口都在同一个主线程回合内完成，
        // 连"5 秒内来不及"的窗口期都不存在。
        startForegroundWithNotification()

        // 已经有一个窗口在显示时，重复点菜单不应再挂一个：那会出现两个小窗互相压着，
        // 而只有最后那个能被关闭（前一个的引用已被覆盖，等于泄漏一个关不掉的窗口）。
        // 由于本方法全程同步，两次连续的 `startForegroundService` 会**串行**执行，
        // 第一次已经把 `rootView` 设好，第二次进不来——不需要额外的"正在挂"标志。
        if (rootView == null) {
            runCatching { attachOverlay(intent) }
                .onFailure {
                    // 挂窗口失败（例如用户在我们检查权限之后、真正 `addView` 之前
                    // 又把权限关掉了，此时会抛 `BadTokenException` / `SecurityException`）。
                    // 必须让 Service 干净退出，否则会留下一个
                    // "通知栏里挂着、屏幕上什么都没有"的前台 Service。
                    //
                    // 这里原先也只 `stopSelf()`、不打日志，于是"悬浮窗没出来"与
                    // "悬浮窗出来了又被关掉"在 logcat 上完全一样（都是没有输出）。
                    // 补上带异常的 `Log.w` 后，`SecurityException`（权限被撤）
                    // 与 `BadTokenException`（窗口类型/权限不匹配）能一眼区分开。
                    Log.w(TAG, "挂载悬浮窗失败，已停止前台服务", it)
                    stopSelf()
                }
        }

        // `START_NOT_STICKY`：**不要**让系统在进程被杀后自动重建本 Service。
        // 悬浮窗内容是打开那一刻的快照，重启后再挂一个"旧内容的小窗"只会让用户莫名；
        // 而且系统重建时 `intent` 为 null（拿不到内容），我们也无从知道该显示什么。
        // 宁可让用户自己再点一次菜单。
        return START_NOT_STICKY
    }

    /**
     * 建通知并转前台。
     *
     * ## 频道选择：**在 Service 内自建频道**，不复用 `NotificationHelper.CHANNEL_REMINDER`
     *
     * 本项目已有的 `reminder_channel`（「打卡提醒」）是 `IMPORTANCE_HIGH`，
     * 会**响铃 + 弹横幅**。而"悬浮窗正在运行"是用户刚主动打开、屏幕上已经看得见的东西：
     * 再用高优先级频道弹一次横幅属于打扰；更糟的是语义会被污染——
     * 用户若嫌烦去关掉这个通知，会连带把「打卡提醒」一起关掉。
     *
     * 因此这里自建 `notebook_overlay_channel`，用 `IMPORTANCE_LOW`
     *（只在通知栏静默显示，不响铃、不弹横幅、不唤醒屏幕）。
     * **这样用户可以在系统里单独关掉它而不影响打卡提醒**——这正是 Android 8+
     * 引入通知频道的意义。
     *
     * ⚠️ 任务约束是"不要改 `NotificationHelper`"，这里严格遵守：频道与常量都定义在本类内，
     * 与 `NotificationHelper` 零耦合（唯一共用的图标资源 `ic_stat_check`
     * 本来就是全应用统一的通知图标）。
     */
    private fun startForegroundWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 重复创建同名频道是安全的：平台会保留用户对该频道做过的修改（例如手动静音），
        // 不会把用户的设置重置掉。这也是官方推荐的写法。
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                "记事本悬浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "记事本悬浮窗运行时的常驻通知"
                // 关掉角标：这不是"有新消息"，只是个运行状态提示。
                setShowBadge(false)
            }
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setContentTitle("记事本悬浮窗运行中")
            .setContentText("点悬浮窗上的 ✕ 可关闭")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            // 常驻、不可划掉：它代表"屏幕上还有个窗口"。
            // 若允许划掉，用户会以为划掉通知就等于关掉了小窗，而小窗其实还在——
            // 这是悬浮窗类功能最常见的困惑来源。
            .setOngoing(true)
            // 刻意**不设** `contentIntent`：点通知去"哪里"没有答案（小窗不是页面），
            // 随便跳一个页面反而会打断用户当前的操作。
            .build()

        // 用 `ServiceCompat.startForeground` 而不是 `Service.startForeground(id, notification)`：
        // 后者在 Android 14+ 会抛 `MissingForegroundServiceTypeException`（见类注释第 3 点）。
        // `ServiceCompat` 按版本分派（已解包本项目解析到的 core 1.15.0 字节码确认：
        // SDK>=34 走 `Api34Impl`、SDK>=29 走 `Api29Impl`，两者都会带上 type 参数）。
        //
        // type 在 34+ 传 `SPECIAL_USE`，与 manifest 里声明的
        // `android:foregroundServiceType="specialUse"` **必须一致**，
        // 否则抛 `InvalidForegroundServiceTypeException`。
        // 低版本传 0：API 33 及以下不存在类型校验，
        // 而不传任何位可避免把"specialUse"这个 34 才有的标志交给不认识它的旧平台。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    /**
     * 建根布局与 `ComposeView`、挂三个 Owner、加到 `WindowManager`。
     *
     * 内容从 `intent` 的 extra 取（见 [NotebookOverlay.open] 关于"为什么不读 DataStore"）。
     */
    private fun attachOverlay(intent: Intent?) {
        // `START_NOT_STICKY` 下系统不会用 null intent 重建本 Service，
        // 但为万一起见仍按"空内容"兜底，而不是让 `getStringExtra` 抛空指针。
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        val imagePaths = intent?.getStringArrayListExtra(EXTRA_PATHS).orEmpty()

        val root = OverlayRootLayout(this)
        val composeView = ComposeView(this)
        val newOwner = OverlayOwner().apply { create() }

        // ⚠️ 顺序要求：三个 Owner 必须在 `addView`（会触发 `onAttachedToWindow` → 组合）
        // **之前**设好。否则 Compose 在附着的一瞬间取不到 Owner，会抛
        // `Composed into the View which doesn't propagate ViewTreeLifecycleOwner!`。
        //
        // ## 为什么**两层都要设**（根布局 + ComposeView），而不是只设一层
        //
        // 这是解包本项目解析到的 `ui` 1.7.8 字节码后确认的一处细节，不设会踩坑：
        // - `findViewTreeLifecycleOwner` 之类的查找**只沿父链向上走**，不会向下看子视图；
        // - `ComposeView.setContent` → `AbstractComposeView.resolveParentCompositionContext`
        //   在父链上找不到组合上下文时，会退到 `getWindowRecomposer`，
        //   而后者先做 `getContentChild(view)`：**一路向上找到最顶层的那个 View**
        //   （这里就是我们的根 `FrameLayout`，因为 `FrameLayout` 的父是 `ViewRootImpl`、
        //   不是 `View`，循环到此为止），然后**在那一层**创建并安装 recomposer；
        // - 随后 `createLifecycleAwareWindowRecomposer` 又**从那一层向上**找 LifecycleOwner
        //   来决定组合是否随生命周期暂停/恢复。
        //
        // 也就是说：recomposer 挂在**根布局**上、并**从根布局向上**找 Owner，
        // 而 `AndroidComposeView` 自己的断言是**从 `ComposeView` 向上**找。
        // 两处起点不同，只设一层必然有一处取不到：
        // 只设 `ComposeView` → recomposer 找不到 LifecycleOwner（组合不受生命周期管控）；
        // 只设根布局 → `ComposeView` 侧虽然能向上找到，但语义不直观。
        // 因此这里两层都设，让"从哪一层问都能问到同一个 Owner"。
        root.setViewTreeLifecycleOwner(newOwner)
        root.setViewTreeViewModelStoreOwner(newOwner)
        root.setViewTreeSavedStateRegistryOwner(newOwner)
        composeView.setViewTreeLifecycleOwner(newOwner)
        composeView.setViewTreeViewModelStoreOwner(newOwner)
        composeView.setViewTreeSavedStateRegistryOwner(newOwner)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow
        )
        composeView.setContent {
            // 主题：用 `MoreCheckSplashTheme`（局部主题，只要 accent + dark）而不是
            // `MoreCheckTheme`。**这是一处明确的取舍**：
            //
            // `dark` 可以由 `isSystemInDarkTheme()` 直接得到；但 `accent`（用户选的主题色）
            // 存在 DataStore 里，Service 要多读一次 DataStore 才能拿到，而且主题色在别处
            // 被改动时小窗**不会**跟着变（它只在这次组合时读一次）——那反而制造了
            // "应用里是紫色、小窗还是绿色"的新不一致。
            //
            // 因此这里**刻意不接主题色**，用默认绿色基准 + 跟随系统深浅色。
            // 悬浮窗是浮在**别的应用**之上的独立窗口，本来就与"应用内主题色"不必一致；
            // 相比为此引入一次额外读盘与一处不同步状态，行为可预测更重要。
            // 若 Lead 认为必须跟随主题色，正确改法是启动时由界面把 accent 一并
            // 作为 Intent extra 传进来（而不是让 Service 自己去读），
            // 这样只多一个参数、不引入新的数据源。
            MoreCheckSplashTheme(
                accent = AccentColor.DEFAULT,
                dark = isSystemInDarkTheme()
            ) {
                // collapsed 是**Compose 状态**（不是 Service 字段）：小球 ↔ 小窗的切换
                // 要走重组，状态放在组合里最直接。Service 侧只在 attach 时读初值
                // （新开的小窗一律展开态）。
                var isCollapsed by remember { mutableStateOf(collapsed) }
                NotebookOverlayHost(
                    collapsed = isCollapsed,
                    text = text,
                    imagePaths = imagePaths,
                    onCollapsedChange = { next ->
                        isCollapsed = next
                        collapsed = next
                        // 形态切换必须同步改窗口尺寸：小球是方形小窗，展开态是
                        // "固定宽 + 内容高"。只改内容不改 params 会留下巨大透明区
                        // （挡住背后的应用却什么也不显示）。
                        applyWindowSize(expanded = !next)
                        // ---- v1.1.69 需求 1 的补丁：收起后立即贴边 ----
                        //
                        // 原先只有"拖动结束"会吸附（见 `OverlayRootLayout.dispatchTouchEvent`
                        // 的 ACTION_UP），而点标题栏「收成小球」走的是这条回调——
                        // 于是小球**停在屏幕中间**，既不像"侧边小球"，也会挡住背后应用
                        // 最中间的交互区。
                        //
                        // ⚠️ **顺序约束（本补丁的关键）**：必须**先** `applyWindowSize(false)`
                        // **再** `snapToSide()`，两句不能交换、也不能合并。
                        // `snapToSide()` 算右侧落点时要用 `windowParams.width` 求
                        // `屏幕宽 - 窗口宽 - 间距`；若在展开态（宽 720px）下先吸，
                        // 算出来的是"按展开宽度摆好的位置"，等宽度变成小球 168px 后，
                        // 那个 x 会把小球整个推到屏幕外（表现为"一点收起，小球就没了"）。
                        // 这里先改尺寸、再用新尺寸算位置，顺序天然正确。
                        //
                        // 只在**收起**时吸（`next == true`）：展开态是"用户要看内容"的形态，
                        // 它本来就该待在用户放的位置上；把展开的小窗也吸到边上毫无收益。
                        if (next) rootView?.snapToSide()
                    },
                    onClose = { stopSelf() },
                    onOpenNotebook = { openNotebookPage() }
                )
            }
        }

        // ComposeView 铺满根布局。宽度由窗口固定，高度跟随内容。
        root.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val params = WindowManager.LayoutParams(
            // 宽度固定一个值而不是 `WRAP_CONTENT`：`WRAP_CONTENT` 会让长文本把窗口
            // 撑到接近满屏宽，"小窗"就名存实亡了。固定宽度 + 内部滚动/截断，行为最稳定。
            WINDOW_WIDTH_PX,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // API 26+ 唯一可用的悬浮窗类型（见类注释第 4 点）。
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // `FLAG_NOT_FOCUSABLE`：**必须**。没有它，这个窗口会抢走输入焦点——
            // 用户切到别的应用后键盘会一直被小窗占着。悬浮窗是"看"的东西，
            // 本内容里也没有任何输入框，不该拿焦点。
            //
            // `FLAG_LAYOUT_NO_LIMITS`：允许拖过屏幕边缘而不被系统挤回来。
            // 不加的话，拖到状态栏/导航栏附近时窗口会被弹回，表现为"拖不过去"。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            // 半透明：圆角外部要透明，否则会露出四块黑角。
            PixelFormat.TRANSLUCENT
        ).apply {
            // `gravity` 决定 `x` / `y` 的参照点。用 TOP|START 后 x/y 就是
            // "距左边缘 / 上边缘多少像素"，拖动计算最直观。
            gravity = Gravity.TOP or Gravity.START
            x = INITIAL_X_PX
            // 避开状态栏：直接放 y=0 会被状态栏盖住标题行，
            // 用户既看不到"记事本"字样，也点不到关闭按钮。
            y = INITIAL_Y_PX
        }

        // 先记状态、再 `addView`：`addView` 会同步触发 `onAttachedToWindow` → 组合，
        // 而拖动回调要读 `windowParams`。把状态准备好再对外发布，
        // 可以彻底排除"顺序颠倒就偶发空指针"的隐患。
        this.rootView = root
        this.owner = newOwner
        this.windowParams = params

        // 初始尺寸：新开的小窗一律展开态（小球态只在用户主动收起后出现）。
        params.width = if (collapsed) BALL_SIZE_PX else WINDOW_WIDTH_PX
        params.height = if (collapsed) BALL_SIZE_PX else WindowManager.LayoutParams.WRAP_CONTENT

        windowManager.addView(root, params)
    }

    /**
     * 切换「小球 ↔ 展开」时同步改窗口尺寸。
     *
     * 小球态是**正方形小窗**（宽高都是 [BALL_SIZE_PX]），展开态是「固定宽 + 内容高」。
     * 只改 Compose 内容而不改 `LayoutParams` 的话，窗口会保持展开态的宽度，
     * 小球周围留一大块透明区域——那块区域会挡住背后的应用却什么也不显示，
     * 表现为"小球点不动别的应用"，是一个非常容易漏掉的坑。
     *
     * `updateViewLayout` 后 `windowParams` 就是新值，后续拖动以新尺寸为基准，
     * 不需要额外同步字段（它是同一个对象的引用）。
     */
    private fun applyWindowSize(expanded: Boolean) {
        val params = windowParams ?: return
        val view = rootView ?: return
        params.width = if (expanded) WINDOW_WIDTH_PX else BALL_SIZE_PX
        params.height =
            if (expanded) WindowManager.LayoutParams.WRAP_CONTENT else BALL_SIZE_PX
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 「跳回记事本页」：把应用拉到前台，并直接落在记事本页面。
     *
     * 走的是既有的**桌面快捷方式深链**通路（`ACTION_LAB_SHORTCUT` + `EXTRA_LAB_PAGE`，
     * 见 `LabScreen.pinLabShortcut` 与 `MainActivity.labShortcutTarget`）——
     * `MainActivity` 里已经有一段"解析 lab_page → 切到发现入口 → 推入对应页面"的逻辑，
     * 传 `notebook` 这个 route 即可复用。**不新写一条 Intent 协议**：
     * 多一种协议就多一处要维护的映射，而这条现成链路的语义正是"直达实验室某页"。
     *
     * 目标 route 用 [TabDestination.Notebook] 的 `route` 常量而不是字面量 `"notebook"`：
     * 两者当前相等，但引用常量后，"这个字符串必须能被
     * `TabDestination.fromRouteOrNull` 命中"这件事由编译器盯着，
     * 而不是靠注释里的一句约定。
     *
     * ## ⚠️ 为什么**必须**同时带 `FLAG_ACTIVITY_NEW_TASK`（v1.1.69 修，这是原先失效的真因）
     *
     * 本类是 `Service`，不是 `Activity`。从**非 Activity 上下文**调 `startActivity` 时，
     * 平台要求 Intent 必须带 `FLAG_ACTIVITY_NEW_TASK`，否则
     * `ActivityTaskManagerService` 会抛
     * `AndroidRuntimeException: Calling startActivity() from outside of an Activity
     * context requires the FLAG_ACTIVITY_NEW_TASK flag`。
     *
     * 原实现只带 `FLAG_ACTIVITY_SINGLE_TOP`，且把整个调用包在 `runCatching` 里**没有任何
     * 失败处理**——于是这个异常被静默吞掉：点了没反应，日志里一行都没有，
     * 排查时连"到底有没有调用到 startActivity"都看不出来。
     *
     * 补上 NEW_TASK 之后，`SINGLE_TOP` 仍然有意义、并不重复，两者管的是不同的事：
     * - `NEW_TASK`：**允许从 Service 启动**（平台前置条件，缺了就抛异常）；
     * - `SINGLE_TOP`：新任务里若栈顶已是 `MainActivity`，**不新建实例**，
     *   改为回调它的 `onNewIntent`（`MainActivity` 已覆写）。
     *
     * 两者并存正好是我们要的：应用在后台时把已有的那个实例拉到前台收货，
     * 而不是又开一个实例（那会出现两个界面、两次跳转）。
     *
     * ## 失败必须可见
     *
     * `startActivity` 仍可能因别的平台原因失败（例如悬浮窗开了、但应用已被系统
     * 限制了后台启动 Activity 的权限）。这类失败**不能**再静默：
     * 这里打一条 `Log.w` 并带上异常，同时**不关闭悬浮窗**——
     * 用户至少能看到窗口还在原地，而不是"点了一下、窗口没了、也没跳到应用"。
     *
     * ## 成功则关闭悬浮窗（v1.1.69 需求 2）
     *
     * 跳转成功意味着用户已经回到应用里的记事本页，浮窗的使命结束：留着它，
     * 屏幕上会同时有一份"能看不能编辑"的小窗，与用户"我已经回应用了"的认知冲突，
     * 还会挡着记事本页的内容。
     *
     * ⚠️ **顺序是硬约束**：`startActivity` 成功之后才 `stopSelf()`（见方法体的
     * `onSuccess`）。窗口是 `startActivity` 的上下文载体，先关窗会让跳转本身
     * 也变得不可靠；而失败时执行关闭则等于"没跳成还把浮窗弄没了"。
     * 把 `stopSelf()` 放进 `onSuccess` 分支，这条顺序在结构上就无法写反。
     */
    private fun openNotebookPage() {
        val intent = Intent(this, com.morecheck.MainActivity::class.java).apply {
            // 本类与 `LabScreen` 同在 `com.morecheck.ui.lab` 包，这两个常量直接引用即可，
            // 不需要写全限定名（它们是跨模块共享的契约，见 `LabScreen` 上的说明）。
            action = ACTION_LAB_SHORTCUT
            putExtra(EXTRA_LAB_PAGE, TabDestination.Notebook.route)
            // 两个 flag 都要，理由见方法注释：NEW_TASK 是平台前置条件，
            // SINGLE_TOP 保证复用已有实例并回调 onNewIntent。
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        runCatching { startActivity(intent) }
            .onSuccess {
                // ---- v1.1.69 需求 2：跳转成功后关闭悬浮窗 ----
                //
                // ⚠️ **顺序约束**：必须**先** `startActivity` 成功、**再** `stopSelf()`。
                // 反过来的话窗口先被移除：`startActivity` 可能因"上下文已随窗口失效"
                // 而失败，用户会看到"点了一下，浮窗没了，应用也没起来"——比不关更糟。
                // 用 `onSuccess` 而不是 try 之后的平铺调用，正是为了把这条顺序
                // 变成**结构上不可能写反**的约束（失败分支根本没有机会执行它）。
                //
                // `stopSelf()` 走 `onDestroy`：移除窗口 + 停前台通知，是既有逻辑，未改动。
                // 之所以要关：用户点这个按钮的意图就是"回应用里看"，浮窗若还挂着，
                // 回到记事本页后屏幕上会同时有一个能看不能编辑的小窗，
                // 既挡内容又与"我已经回应用了"的认知矛盾。
                stopSelf()
            }
            .onFailure {
                // ⚠️ 这里**刻意不吞**：静默失败正是"点了没反应、还查不出原因"的来源。
                // 不改用 `Toast` 提示用户：本窗口是 `FLAG_NOT_FOCUSABLE` 的悬浮窗，
                // 且用户此刻可能已经切到别的应用，弹 Toast 既不合时宜也容易被漏看；
                // 一条带堆栈的日志才是排查所需的最小信息。
                //
                // ⚠️ 失败时**不关闭**悬浮窗：用户此刻仍停在浮窗上，跳转没成功还把窗口关掉，
                // 等于"点了没反应、东西还弄没了"。保持原样至少让他能再点一次或手动关闭。
                Log.w(TAG, "跳转记事本页失败：startActivity 抛出异常（悬浮窗保持打开）", it)
            }
    }

    /**
     * 关闭路径：**唯一**的关闭方式是悬浮窗标题栏上的 ✕（它调 `stopSelf()`），
     * 加上这里的兜底清理。刻意不做"点通知关闭"：通知上没有 action，
     * 避免用户以为划掉通知就等于关掉小窗（见通知 `setOngoing` 的注释）。
     *
     * 系统在内存紧张时可能直接杀进程，此时 `onDestroy` 不保证被调用；
     * 但那种情况下窗口随进程一起消失，不会留下"看不见的残留窗口"。
     */
    override fun onDestroy() {
        // 先移除窗口、再销毁 Owner：
        // `removeView` 会同步触发 `onDetachedFromWindow`，由
        // `DisposeOnDetachedFromWindow` 策略销毁组合，而**销毁组合的过程中仍需 Owner 有效**
        //（`LifecycleRegistry` 要派发 ON_DESTROY）。顺序颠倒会让销毁阶段读到一个空所有者。
        rootView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootView = null
        windowParams = null

        owner?.destroy()
        owner = null

        // 清掉常驻通知。`STOP_FOREGROUND_REMOVE` = 停前台并移除通知；
        // 不传这个标志会留下一条已经没意义的通知栏常驻项。
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }

        super.onDestroy()
    }

    companion object {
        /**
         * 日志 tag。
         *
         * 与类名同名（`adb logcat -s NotebookOverlayService`）：
         * 本类此前**完全不打任何日志**——「点跳转没反应」这件事因此既无 logcat 线索、
         * 也没有任何界面反馈，只能靠通读代码猜。v1.1.69 起至少有两处日志
         * （[openNotebookPage] 的失败、[onStartCommand] 的挂窗口失败），
         * 用类名当 tag 便于按组件过滤。
         */
        private const val TAG = "NotebookOverlayService"

        /**
         * 菜单项触发的动作名。目前只有一个动作，留着是为了将来扩展时不必改启动方签名。
         */
        const val ACTION_SHOW = "com.morecheck.action.NOTEBOOK_OVERLAY_SHOW"

        /** 正文快照。见 [NotebookOverlay.open] 关于"为什么用 extra 而不是读 DataStore"。 */
        const val EXTRA_TEXT = "com.morecheck.extra.NOTEBOOK_OVERLAY_TEXT"

        /** 图片路径快照（`ArrayList<String>`，必须用 `putStringArrayListExtra` 装入）。 */
        const val EXTRA_PATHS = "com.morecheck.extra.NOTEBOOK_OVERLAY_PATHS"

        /** 悬浮窗频道 id。**自建、不复用提醒频道**，理由见 [startForegroundWithNotification]。 */
        private const val CHANNEL_OVERLAY = "notebook_overlay_channel"

        /**
         * 通知 id。与 `NotificationHelper` 里的 id（0x7E57 / 0x7E58）**刻意错开**，
         * 避免同 id 相互覆盖。
         */
        private const val NOTIFICATION_ID = 0x7E59

        // ---- 窗口尺寸与初始位置 ----
        //
        // 用**像素常量**而不是 dp：`WindowManager.LayoutParams` 的 width/height/x/y
        // 一律是像素。写 dp 还得自己用 `displayMetrics.density` 换算，
        // 多一处容易算错的地方，而悬浮窗的具体尺寸不影响功能正确性。
        // 取值按常见 1080×2400 屏估算。
        private const val WINDOW_WIDTH_PX = 720
        private const val INITIAL_X_PX = 60
        private const val INITIAL_Y_PX = 240

        /**
         * 小球边长（像素）。56dp 按常见 2.75~3.0 密度屏约 154~168px，
         * 这里取 168：比 48dp 的最小可点击尺寸大，又不至于盖住太多背后内容。
         */
        private const val BALL_SIZE_PX = 168

        /** 吸附侧边时距屏幕边缘的间距（像素），约 8dp。 */
        private const val BALL_EDGE_MARGIN_PX = 24
    }
}

/**
 * 悬浮窗宿主：按 [collapsed] 在「侧边小球」与「完整小窗」两种形态间切换。
 *
 * ## 为什么小球与展开态是同一个内容的两种形态，而不是两个窗口
 *
 * 两个窗口意味着两套 `OverlayOwner`、两套 `WindowManager.LayoutParams`、
 * 以及"切形态时先 addView 再 removeView"的时序——中间态会闪、旧窗口可能残留。
 * 同一内容 + `AnimatedContent` 切换则没有这些问题：Owner 与窗口参数只有一个，
 * Service 只需在切换时同步改一次窗口尺寸（见 `applyWindowSize`）。
 *
 * ## 小球的交互（v1.1.69 需求 1）
 *
 * - **点击小球 → 展开**为完整小窗（用户要看内容）；
 * - **展开态点标题栏的「跳转」图标 → 跳回记事本页**（见 `onOpenNotebook`）；
 * - **长按小球 → 收起提示之外也支持再长按展开**：展开态没有"收起成小球"的按钮，
 *   用户靠**拖动窗口到侧边 + 松手**自然进入吸附态；而从展开态回到小球，
 *   走的是标题栏左边的收起按钮（见下方 [NotebookOverlayContent]）。
 *
 * 这里刻意**不给小球加"关闭"按钮**：小球只有 56dp 大小，塞两个可点目标必然误触；
 * 关闭走展开态标题栏的 ✕（那才是"决定不要这个浮窗"的明确动作）。
 */
@Composable
private fun NotebookOverlayHost(
    collapsed: Boolean,
    text: String,
    imagePaths: List<String>,
    onCollapsedChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onOpenNotebook: () -> Unit
) {
    // AnimatedContent 让形态切换有个 180ms 的缩放过渡：直接硬切会有"内容瞬移"的突兀感，
    // 而悬浮窗浮在别的应用之上，突兀感比应用内页面更明显。
    // 用 ScaleIn/ScaleOut 而不是滑动：两种形态是"大变小"，缩放才是它的自然隐喻。
    AnimatedContent(
        targetState = collapsed,
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.7f)) togetherWith
                fadeOut(tween(120))
        },
        label = "overlay-collapse"
    ) { isCollapsed ->
        if (isCollapsed) {
            NotebookOverlayBall(onClick = { onCollapsedChange(false) })
        } else {
            NotebookOverlayContent(
                text = text,
                imagePaths = imagePaths,
                onClose = onClose,
                onCollapse = { onCollapsedChange(true) },
                onOpenNotebook = onOpenNotebook
            )
        }
    }
}

/**
 * 侧边小球：圆形记事本图标。
 *
 * 点击展开（手势在 [NotebookOverlayHost] 的调用方，即根布局的 dispatchTouchEvent 里
 * 统一判定：未超过 touchSlop 的交给了子树，这里的 `clickable` 因此能正常收到点击）。
 *
 * 颜色用 `primary`（默认绿色）+ 白前景：小球浮在别的应用之上，需要一个
 * **高饱和**的颜色才能从背后五花八门的界面里跳出来；而 `surface` 白底小球
 * 在浅色应用上会"糊进去"。这是与展开态用 `surface` 不同的取舍。
 */
@Composable
private fun NotebookOverlayBall(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(BALL_SIZE_DP)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "记事本悬浮窗，点击展开",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** 悬浮窗内容：标题栏（标题 + 收起 + 跳转记事本 + 关闭按钮）+ 正文 + 图片缩略图。
 *
 * ## 为什么不用 `Scaffold` / `TopAppBar`
 *
 * 那些组件自带 `WindowInsets` 状态栏避让与最小高度，是为**全屏页面**设计的。
 * 悬浮窗是浮在别人之上的小方块，没有 Activity 的 window insets 可避让，
 * 用它们只会引入一堆"顶部莫名留白"。这里用普通 `Surface` + `Row` 手搭，
 * 高度完全由内容决定。
 *
 * ## 颜色
 *
 * 全部取自 [MaterialTheme]（由外层 `MoreCheckSplashTheme` 提供），因此跟随系统深浅色，
 * **不新增任何颜色常量**。
 */
@Composable
private fun NotebookOverlayContent(
    text: String,
    imagePaths: List<String>,
    onClose: () -> Unit,
    onCollapse: () -> Unit,
    onOpenNotebook: () -> Unit
) {
    // 缩略图与记事本页同一写法：IO 线程解码后用状态持有，
    // 而不是在组合里同步解码（后者会卡住首帧）。
    var thumbs by remember { mutableStateOf<List<Bitmap?>>(emptyList()) }
    LaunchedEffect(imagePaths) {
        val paths = imagePaths
        thumbs = withContext(Dispatchers.IO) { paths.map { NotebookStore.decodeThumb(it) } }
    }

    Surface(
        // 圆角 + 阴影：悬浮窗要一眼看出"这是个浮起来的窗口"，
        // 否则会和下面的应用界面糊在一起。
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---- 标题栏（同时也是拖动区；拖动由根布局统一处理）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "记事本",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                // 「收成小球」：把展开态收回侧边小球（v1.1.69 需求 1）。
                // 放在最左：收起是"降低干扰"的动作，跳转/关闭是"离开"的动作，
                // 干扰性从低到高排列更符合操作预期。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onCollapse),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Minimize,
                        contentDescription = "收起为侧边小球",
                        modifier = Modifier.size(16.dp)
                    )
                }
                // 「跳回记事本页」：把应用拉到前台并落在记事本页（v1.1.69 需求）。
                //
                // 语义是"回应用里看完整内容"：悬浮窗只看个摘要，编辑 / 看图 / 删除
                // 都要回应用。放在关闭按钮左边，与「退出浮窗」相邻但方向相反
                // （一个是离开浮窗进应用、一个是彻底关掉浮窗）。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenNotebook),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "跳转到记事本页面",
                        modifier = Modifier.size(16.dp)
                    )
                }
                // 关闭按钮：**始终存在**的关闭入口。
                //
                // 用 `Box` + `clickable` 而不是 `IconButton`：`IconButton` 的最小交互尺寸是
                // 48dp×48dp，会把小窗的标题栏撑得过高。32dp 稍小于无障碍建议值，
                // 但悬浮窗可以拖动、误触成本低（没点到就再点一次），这个取舍是划算的。
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭悬浮窗",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---- 正文 ----
            if (text.isNotBlank()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 限高 + 可滚动：悬浮窗不能无限长（否则会盖住整屏）。
                        // 用 `heightIn(max=)` 而不是固定高度，短笔记的小窗能自动变矮。
                        .heightIn(max = OVERLAY_TEXT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else if (imagePaths.isEmpty()) {
                // 文字与图片都空：给一句说明，而不是一个只有标题栏的空框。
                Text(
                    text = "笔记是空的",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }

            // ---- 图片缩略图 ----
            //
            // 这里**只展示**，不提供点击预览与长按删除：悬浮窗是"瞄一眼"的场景，
            // 且 `FLAG_NOT_FOCUSABLE` 下它连键盘都拿不到，在里面做破坏性交互（删除）
            // 风险高、收益低。要看大图回记事本页面看。
            if (imagePaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 只展示前几张：悬浮窗宽度有限，铺满九张会让小窗变得很高，
                    // 而"有没有图、大概是哪几张"看一眼就够，细节回应用里看。
                    thumbs.take(OVERLAY_MAX_THUMBS).forEachIndexed { index, bitmap ->
                        val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
                        if (image != null) {
                            Image(
                                bitmap = image,
                                contentDescription = "笔记图片 ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(OVERLAY_THUMB_SIZE)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                        } else {
                            // 还没解码完 / 文件损坏：占位灰块，保持排版稳定。
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(OVERLAY_THUMB_SIZE)
                            ) {}
                        }
                    }
                    // 超出展示上限时用「+N」提示，而不是静默丢掉——
                    // 用户看到"只有 4 张"会以为图片丢了。
                    if (imagePaths.size > OVERLAY_MAX_THUMBS) {
                        Text(
                            text = "+${imagePaths.size - OVERLAY_MAX_THUMBS}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 悬浮窗正文的最大高度；超出后在窗内滚动，避免小窗长到盖住整屏。 */
private val OVERLAY_TEXT_MAX_HEIGHT = 180.dp

/** 小球边长（dp）。与 companion 里的 [BALL_SIZE_PX] 是同一个语义的两种单位：
 *  Compose 侧用 dp（自动适配密度），窗口参数侧用 px（WindowManager 只认像素）。 */
private val BALL_SIZE_DP = 56.dp

/** 悬浮窗里单张缩略图的边长（比记事本页的 72dp 小，为的是在小窗里一行放下更多张）。 */
private val OVERLAY_THUMB_SIZE = 44.dp

/** 悬浮窗里最多展示几张缩略图；超出的用「+N」表示。 */
private const val OVERLAY_MAX_THUMBS = 4
