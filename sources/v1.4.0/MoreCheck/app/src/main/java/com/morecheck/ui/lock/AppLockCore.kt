package com.morecheck.ui.lock

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.AppLockBioTexts
import com.morecheck.data.AppLockRelock
import com.morecheck.data.SettingsRepository
import com.morecheck.ui.common.HelloWorldSplashLayer
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.ui.common.pressScale
import com.morecheck.di.appContainer
import com.morecheck.util.findActivity
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「锁屏密码」的**运行时门闸**与锁屏页（2026-10-25 需求：设置 → 安全；用词统一见 v1.1.92）。
 *
 * ## 安全口径（如实记录，别承诺过头）
 *
 * - 手势图案**不存明文**：存 SHA-256(盐 + 图案序列) 的十六进制串，盐为每次设置
 *   手势时随机生成、与哈希同存 DataStore。它防的是"顺手拿起来划两下就进去"，
 *   **不防**能读取应用数据目录的人（root / adb backup 场景下盐与哈希同在）；
 *   要抗那种威胁需要 Android Keystore 硬件密钥，对一个本地自用打卡应用是过度设计。
 * - 指纹不经过本应用：走系统 [BiometricPrompt]（指纹模板只存在于系统安全区，
 *   应用只拿到"认证成功/失败"的回调）。
 *
 * ## 解锁的存活口径：可配置，默认「进程内一次」
 *
 * 解锁后 [AppLockSession.unlocked] 置位。改造前后它的清零时机是固定的——
 * 只到进程被杀（划掉最近任务 / 系统回收）为止，退到后台再回来不再询问。
 *
 * **2026-10-27 起**用户可在「设置 → 安全 → 锁屏密码 → 锁定时机」里改：
 * 立即 / 离开 1 分钟 / 5 分钟 / 1 小时内不再锁 / 今天内不再锁 / 仅冷启动（默认）。
 * 判据是"离开前台多久"，实现见 [AppLockRelock] 的 KDoc 与下方的 lifecycle 观察者。
 *
 * ## 为什么游戏独立 Activity **不加**锁
 *
 * 三个游戏宿主是 `taskAffinity` 独立的 Activity，从最近任务可以直接点回游戏卡
 * （不经 MainActivity）。给它们也加锁要复制一份门闸到三个 Activity；
 * 而游戏的桌面快捷方式本来就要过 MainActivity（v1.1.91 起统一分流在
 * 同意门闸之后），侧向入口只有"最近任务里的游戏卡"。权衡后本版只锁主入口，
 * 游戏卡不加锁（游戏内无个人数据，锁屏的目标是打卡数据与设置）。
 *
 * ## ⚠️ `content()` 只能有**一个**调用点（v1.1.92 修，2026-10-27 换动画后仍需守住）
 *
 * 「打开/关闭总开关时界面突然跳回主页」的根因就在这里。原实现把 `content()`
 * 写在了两处（「未启用 → 放行」与「本次已解锁 → 放行」），Compose 会把它们
 * 当成两个不同的调用点：**开关一关，条件切到另一个分支，整棵 content 子树被
 * 销毁重建**，`NavHostController` 跟着重建、回退栈清零 → 界面弹回主页。
 *
 * 现在 content() 只出现在 [AnimatedContent] 的 `stillLocked == false` 分支里，
 * 仍然是一个调用点；但 AnimatedContent 换 targetState 时同样会销毁旧子组合，
 * 所以外面套了 `SaveableStateHolder`（`CONTENT_STATE_KEY`）来保住回退栈。
 * 加新的分支时请确认这两件事都没被破坏：**调用点唯一** + **包在 SaveableStateProvider 内**。
 *
 * @param content 解锁后（或未启用时）显示的应用本体。
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { context.appContainer.settingsRepository }

    // ⚠️ 三者一律用 **null 表示「盘上还没读到」**，不能用 false / "" 兜底：
    //
    // 2026-10-26 修复「设置里的开屏密码不起作用」。原实现给 DataStore 流喂了
    // `initialValue = false / ""`，而 `collectAsStateWithLifecycle` 在**第一次发射到达之前**
    // 会先用初值渲染一帧；那一帧走进下面的「未启用 → 直接放行」分支，顺手把
    // `AppLockSession.unlocked` 永久置为 true。等真正的 `enabled = true` 到达时，
    // 门闸看到的已经是"本次已解锁"，于是**每次冷启动都直接进主页、锁屏从不出现**。
    // 换句话说：开关是真的打开了、哈希也真的存了，问题全在"读盘中的那一帧"。
    //
    // 用 null 当哨兵后，未知状态走 [AppLockLoading]，既不渲染 content（不泄露隐私），
    // 也不写解锁位。同理，解锁位不能在组合期间赋值——组合是可重入的，副作用会重复发生。
    val enabledState by settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = null)
    val patternHashState by settings.appLockPatternHash.collectAsStateWithLifecycle(initialValue = null)
    // 盐与哈希是同一次设置里成对写入的，这里一起等到位再渲染锁屏页：
    // 否则"锁屏页已出现、盐还在读"的那几毫秒里画手势，会用空盐算哈希 → 判成"手势不正确"。
    val saltState by settings.appLockSalt.collectAsStateWithLifecycle(initialValue = null)
    val biometricEnabled by settings.appLockBiometricEnabled.collectAsStateWithLifecycle(initialValue = false)
    // 同样是 null 哨兵：还没读到就**不播**开屏，避免"先闪一下锁屏、再插一层开屏"。
    val helloState by settings.appLockHelloSplash.collectAsStateWithLifecycle(initialValue = null)
    val errorFeedback by settings.appLockErrorFeedback.collectAsStateWithLifecycle(initialValue = true)
    // 重新锁定的时机（2026-10-27 需求）。默认 COLD_START = 改造前行为，升级后无感。
    val relockMode by settings.appLockRelock.collectAsStateWithLifecycle(
        initialValue = AppLockRelock.DEFAULT
    )
    val bioTexts by settings.appLockBioTexts.collectAsStateWithLifecycle(
        initialValue = AppLockBioTexts.DEFAULT
    )
    // 宽限档凭据：null = 盘上还没读到，"" = 没有可用凭据。同样用 null 哨兵，
    // 免得读盘中的那一帧先按"无凭据"渲染出锁屏页再突然放行。
    val graceRaw by settings.appLockGrace.collectAsStateWithLifecycle(initialValue = null)
    val gateScope = rememberCoroutineScope()

    // 冷启动恢复（2026-11-05 修「今天内不再锁」杀后台失效）：
    // 宽限档的解锁状态原先只活在 AppLockSession 的进程内变量里，划掉最近任务
    // 或系统回收进程后就归零，冷启动必然再弹一次验证。现在凭据落盘，读到盘后
    // 校验「当时档位 + 是否仍在宽限期内」，通过才恢复解锁位。
    // 用 LaunchedEffect 而不是在组合里直接赋值：组合可重入，副作用会重复发生。
    // 「宽限凭据已经判定过一次」的门闩（2026-11-11 修「今天内不再锁仍弹指纹」）。
    //
    // 只置 true、不回退：判定本身不挂起，第一次跑完就有了结论；之后再变化
    // （解锁时写入新凭据等）不需要再挡一帧，否则每次写盘都会闪一下空页。
    var graceChecked by remember { mutableStateOf(false) }
    LaunchedEffect(graceRaw, relockMode) {
        val unlockedAt = AppLockGrace.decode(graceRaw, relockMode, System.currentTimeMillis())
        if (unlockedAt != null && !AppLockSession.unlocked) {
            AppLockSession.lastUnlockAtWall = unlockedAt
            AppLockSession.unlocked = true
        }
        graceChecked = true
    }

    // 盘上还没读到、或宽限凭据还没判定完 → 停在同色空页（时间极短，但绝不能闪出主页内容）。
    //
    // ⚠️ `!graceChecked` 这一条是「今天内不再锁」那个 bug 的关键：
    // 首帧 `AppLockSession.unlocked` 恒为 false（进程级变量，冷启动归零），
    // 若此时就渲染锁屏页，`LockScreen` 里「进页自动弹指纹」的 `LaunchedEffect`
    // 会在同一帧把系统 BiometricPrompt 拉起来；下一帧 grace 才把 unlocked 置真、
    // 锁屏页才退出组合——但系统弹窗已经弹出来了，用户看到的就是
    // 「明明设了今天内不再锁，启动还是弹指纹」。先等判定完，首帧就已是正确状态。
    if (enabledState == null || patternHashState == null || saltState == null ||
        graceRaw == null || !graceChecked
    ) {
        AppLockLoading()
        return
    }

    val enabled = enabledState == true
    val patternHash = patternHashState.orEmpty()

    // ---- 重新锁定：按「离开前台多久」判定（见 AppLockRelock 的 KDoc）----
    //
    // 计时只在进程内（`SystemClock.elapsedRealtime()`，不受改时间影响），
    // 不在后台挂定时器：系统冻结后台进程时定时器本来就不准，而"回来时比一下差值"
    // 既省电也不会漏。ON_STOP 记录离开时刻，ON_START 比较；未开启该功能（COLD_START）
    // 时这两个分支什么都不做，开销为零。
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostActivity = remember(context) { context.findActivity() }
    DisposableEffect(lifecycleOwner, enabled, relockMode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (enabled && relockMode.watchesBackground) {
                        // ⚠️ 配置变更（旋转 / 深浅色切换导致 Activity 重建）不算"用户离开"：
                        // 不排除的话，开着「立即」时转一下屏幕就被要求重新解锁。
                        val isReconfiguring = hostActivity?.isChangingConfigurations == true
                        if (!isReconfiguring) {
                            AppLockSession.leftAt = SystemClock.elapsedRealtime()
                        }
                    }
                }

                Lifecycle.Event.ON_START -> {
                    val leftAt = AppLockSession.leftAt
                    AppLockSession.leftAt = null
                    // 只在"本次已解锁"时才有必要重新上锁；已经在锁屏页就不用管。
                    if (leftAt != null && enabled && relockMode.watchesBackground &&
                        AppLockSession.unlocked
                    ) {
                        val shouldLock = if (relockMode.isGrace) {
                            // 宽限档（需求）：判据是「距上次解锁多久」，与离开时长无关——
                            // 解锁后 1 小时内（或当天内）切后台再久回来也不锁；
                            // 宽限期一过，回前台立即要求解锁。
                            AppLockSession.graceExpired(relockMode)
                        } else {
                            SystemClock.elapsedRealtime() - leftAt >= relockMode.timeoutMs
                        }
                        if (shouldLock) {
                            AppLockSession.unlocked = false
                        }
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 一行算清「现在该不该挡」：
    // 未启用 / 尚未设置手势（开关开着但没有手势属异常态，按未启用处理）/ 本次已解锁
    // 三种情况都放行。放行是"当前配置与本次会话决定的"，这里**不写任何状态**
    // （组合可重入，副作用会重复发生）——置位只发生在设置页与解锁回调里。
    val locked = enabled && patternHash.isNotBlank() && !AppLockSession.unlocked

    // ── 「忘记密码」通过身份验证后：就地重设手势（2026-11-01）──────────────────
    //
    // ⚠️ 这里**不改** `patternHash`、也**不**置解锁位：锁仍然存在，只是把界面
    // 换成"画一个新手势"。用户按返回取消就回到锁屏，应用依然锁着——
    // 不会出现"重设失败 → 锁没了"的中间态（这正是安全边界里最怕的那种态）。
    //
    // 复用设置页的 [PatternSetupLayer]（同一份实现），所以最少点数、两次一致、
    // 换新盐这三条规则与首次设置永远一致。
    val scope = rememberCoroutineScope()
    var resetting by remember { mutableStateOf(false) }
    if (resetting) {
        PatternSetupLayer(
            onCancel = { resetting = false },
            onSaved = { newSalt, newHash ->
                scope.launch {
                    // 先写盐再写哈希：门闸要等这两个流都到位才渲染锁屏，
                    // 顺序反了会有一帧"新哈希 + 旧盐"，那一帧画什么都是错的。
                    settings.setAppLockSalt(newSalt)
                    settings.setAppLockPatternHash(newHash)
                    resetting = false
                }
            }
        )
        return
    }

    // ⚠️ 外层 SaveableStateHolder 是为了「重新锁定后解锁，回到原来那一页」。
    //
    // 换用 [AnimatedContent] 之后（2026-10-27 需求「解锁后进主页加动画」），
    // `locked` 翻转会让 `content()` 所在的子组合被销毁重建——这正是 v1.1.92
    // 「拨开关跳回主页」的成因（当时是 if/else 两个调用点，现在仍是"两个 targetState"）。
    // 差别在于：现在把 content 包进 `SaveableStateProvider`，`rememberNavController`
    // 的 saveable 状态（回退栈）会存在 holder 里，重建后原样恢复。
    // 不用它的话，「立即锁定」一开启，每次切后台回来都会落在主页。
    val contentStateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = locked,
        // 只在"解锁"这一个方向做有存在感的动画：锁屏层向后散开（放大 + 淡出），
        // 内容层从略小（0.94）弹到 1.0 并淡入，形成"门开了一层"的层次。
        // 反向（重新上锁）用更短更平的淡入淡出——那一刻用户的注意力在"我要解锁"，
        // 不该再花 300ms 看动画。
        transitionSpec = {
            if (initialState && !targetState) {
                (fadeIn(tween(durationMillis = 280, delayMillis = 70, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        animationSpec = tween(
                            durationMillis = 380,
                            delayMillis = 70,
                            easing = FastOutSlowInEasing
                        ),
                        initialScale = 0.94f
                    )) togetherWith
                    (fadeOut(tween(durationMillis = 200)) +
                        scaleOut(tween(durationMillis = 240), targetScale = 1.06f))
            } else {
                fadeIn(tween(durationMillis = 160)) togetherWith
                    fadeOut(tween(durationMillis = 160))
            }
        },
        label = "app-lock-gate"
    ) { stillLocked ->
        if (stillLocked) {
            // 解锁前先播一层 Hello World（用户 2026-10-26 需求，设置 → 锁屏密码 可关）。
            //
            // ⚠️ 与**应用自身开屏**的关系：`SplashGate` 画的也是 Hello World（同一套配色），
            // 所以默认情况下用户看到的是"两次 Hello World 连在一起"，视觉上是一次连续开屏、
            // 没有闪烁。它的价值在于**兜底**：开发者选项里的「开屏时长」可以被设成 0，
            // 那一刻应用开屏只剩一帧，本层就成了唯一一次 Hello World。
            //
            // 「本进程只播一次」：解锁后再锁（理论上不会发生）或重组都不重播。
            if (helloState == true && !AppLockHelloShown.shown) {
                HelloWorldSplashLayer(
                    durationMs = SettingsRepository.APP_LOCK_SPLASH_HOLD_MS,
                    onFinished = { AppLockHelloShown.shown = true }
                )
            } else {
                LockScreen(
                    patternHash = patternHash,
                    salt = saltState.orEmpty(),
                    biometricEnabled = biometricEnabled,
                    bioTexts = bioTexts,
                    errorFeedback = errorFeedback,
                    onUnlocked = {
                        AppLockSession.onUnlockedNow()
                        // 宽限档必须把「解锁时刻 + 档位」落盘：进程内标志杀后台就没了，
                        // 下次冷启动要靠它恢复「今天内不再锁」。非宽限档写空串清掉旧凭据，
                        // 避免用户切档后再切回来时被一个过期时间戳放行。
                        // ⚠️ 这次写入必须**不可取消 + 走 IO 线程**（2026-11-06 修）：
                        // 解锁成功的一瞬间门闸会立刻切到内容区，用户随后可能马上划掉后台；
                        // 若这次 `edit` 还挂在可取消的作用域上，进程被回收前写盘就被取消，
                        // 凭据根本没落地 —— 表现就是"今天内不再锁"杀后台又失效。
                        gateScope.launch(Dispatchers.IO + NonCancellable) {
                            settings.setAppLockGrace(
                                if (relockMode.isGrace) {
                                    AppLockGrace.encode(relockMode, System.currentTimeMillis())
                                } else {
                                    ""
                                }
                            )
                        }
                    },
                    // 只是"允许重设"，不是解锁（见 LockScreen 该参数的说明）。
                    onResetVerified = { resetting = true }
                )
            }
        } else {
            // ⚠️ 全文件唯一的 content() 调用点，别再加第二个（见类 KDoc 的说明）。
            contentStateHolder.SaveableStateProvider(CONTENT_STATE_KEY) {
                content()
            }
        }
    }
}

/**
 * [AppLockGate] 里内容区的 saveable 状态 key。
 *
 * 固定字符串即可：全应用只有这一个门闸、内容区只有一份。
 */
private const val CONTENT_STATE_KEY = "app-lock-content"

/**
 * 读盘期间的占位：只铺一层主题底色。
 *
 * 为什么不是"什么都不画"：`MoreCheckRoot` 之上还有 `SplashGate` 的开屏层，
 * 这里留白会露出尚未铺好背景的窗口底（白/黑闪一帧）。铺同色底与二级页进入动画
 * 的"垫一层同色底"是同一手法（见 HANDOVER 第 1 节折叠块 ④）。
 */
@Composable
private fun AppLockLoading() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    )
}

/**
 * 进程级「本次已解锁」标志（口径见 [AppLockGate] 的 KDoc）。
 *
 * ⚠️ 必须是 **Compose 状态**，不能是普通 `var`：
 * 锁屏页画对手势后由 `onUnlocked` 置位，若它是普通变量的写入不会触发重组，
 * 界面就永远停在锁屏页（用户感受是"手势密码不起作用"——画对了也进不去）。
 * 同理，它也不能在读盘期间被门闸顺手置 true（见 [AppLockGate] 里的长注释）。
 */
internal object AppLockSession {
    var unlocked by mutableStateOf(false)

    /** 最近一次解锁成功墙钟时刻（毫秒），宽限档（1 小时 / 今天内）的判据。 */
    var lastUnlockAtWall: Long = 0L

    fun onUnlockedNow() {
        unlocked = true
        lastUnlockAtWall = System.currentTimeMillis()
    }

    /** 宽限档判定：宽限期内返回 false（不锁），过期返回 true（立即要求解锁）。 */
    fun graceExpired(mode: AppLockRelock): Boolean {
        if (lastUnlockAtWall <= 0L) return false
        val now = System.currentTimeMillis()
        return when (mode) {
            AppLockRelock.ONE_HOUR_GRACE -> now - lastUnlockAtWall >= 3_600_000L
            AppLockRelock.TODAY_GRACE -> !isSameLocalDay(lastUnlockAtWall, now)
            else -> false
        }
    }

    private fun isSameLocalDay(a: Long, b: Long): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = a
        val dayA = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        cal.timeInMillis = b
        val dayB = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        return dayA == dayB
    }

    /**
     * 离开前台的时刻（`SystemClock.elapsedRealtime()` 毫秒），用于「重新锁定时机」。
     *
     * 只在 [AppLockRelock.watchesBackground] 为真时被写入；[Lifecycle.Event.ON_START]
     * 读到后**立即清零**，避免下一次 ON_START 用上一次的旧时刻误判。
     *
     * 用普通 `var` 即可：它不参与 UI 展示，写它不需要触发重组。
     */
    var leftAt: Long? = null
}

/**
 * 进程级「解锁前 Hello World 已播过」标志。
 *
 * 用 Compose 状态而不是普通布尔：`onFinished` 置位后要让 [AppLockGate] 重组一次，
 * 才会从开屏层换成锁屏页。
 */
internal object AppLockHelloShown {
    var shown by mutableStateOf(false)
}

/**
 * 手势图案 → 十六进制 SHA-256。图案序列用 "0-3-7" 这样的串表示。
 * 纯函数（也在设置页保存新手势时复用）。
 */
internal fun hashPattern(salt: String, pattern: List<Int>): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest((salt + ":" + pattern.joinToString("-")).toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** 手势最少连接的点数（少于它视为误触，不作为有效手势）。 */
internal const val MIN_PATTERN_POINTS = 4

/**
 * 锁屏页顶部那句引导该写什么（2026-10-30 需求）。
 *
 * - 系统指纹弹窗在前台 → 「使用指纹解锁」：那一刻用户唯一要做的事就是按指纹，
 *   背景却写着"绘制手势图案解锁"，等于在教人做一件等会儿才轮得到的事；
 * - 其余情况沿用页面自己的提示（默认引导画手势 / 画错了提示重试）。
 *
 * 抽成纯函数是为了能单测：指纹弹窗需要真机有可用指纹才能弹出，模拟器上验不到，
 * 而这条映射本身是纯字符串逻辑，没必要依赖生物识别硬件。
 */
internal fun lockHintText(awaitingBiometric: Boolean, patternHint: String): String =
    if (awaitingBiometric) "使用指纹解锁" else patternHint

/**
 * 输错时震一下（2026-10-26 需求「输错密码时震动变红提示」）。
 *
 * 50ms 单次、系统默认强度：够让人知道"错了"，又不至于变成噪音。
 * `VibratorManager` 是 API 31 起的新入口（多振动器设备要指定用哪个），
 * 29~30 仍用旧 `Vibrator`——两个分支都判 `hasVibrator()`，模拟器上直接静默放过。
 *
 * ⚠️ 需要清单里的 `android.permission.VIBRATE`（普通权限，安装即授予，不弹窗）。
 */
private fun vibrateOnError(context: Context) {
    val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    if (vibrator == null || !vibrator.hasVibrator()) return
    vibrator.vibrate(
        VibrationEffect.createOneShot(
            VIBRATE_ON_ERROR_MS,
            VibrationEffect.DEFAULT_AMPLITUDE
        )
    )
}

/** 输错时的震动时长（毫秒）。 */
private const val VIBRATE_ON_ERROR_MS = 50L

/**
 * 锁屏页：提示文案 + 带描边的小按钮「指纹登录」+ 手势绘制板。
 *
 * 布局自 2026-10-27 起是「提示 → 指纹登录（手势框**上方**）→ 手势板」：
 * 指纹按钮原来在手势框下方、是一个全宽实心 Button，既抢视觉又是最后才看到的东西；
 * 改成小按钮并上移后，手势板顺势下移，两条解锁路径的先后关系才对上
 * （指纹是自动弹出、最快的路，手势是兜底）。2026-10-29 又给它加了一圈描边：
 * 纯文字按钮在浅色底上几乎没有"这是个按钮"的暗示。
 *
 * 系统指纹弹窗在前台期间（[bioPromptShowing]），手势板与指纹按钮整块不画，
 * 只留一句"正在等待指纹验证…"——弹窗是半透明的，底下露着点位与痕迹既碍眼也容易被误触。
 *
 * @param patternHash 盘上的手势哈希（校验输入用）。
 * @param salt 哈希盐。正常情况下由 [AppLockGate] 一并读完再传进来（保证"锁屏页出现时
 *   盐已就位"，避免空盐算哈希误判）；传空串时本页会自己从仓库补读一次，
 *   只为兼容历史数据里缺盐的异常情况。
 * @param biometricEnabled 指纹开关的当前值；true 且设备支持时显示指纹按钮并自动弹一次。
 * @param bioTexts 指纹弹窗的文案（设置 → 锁屏密码 可改，见 [AppLockBioTexts]）。
 * @param errorFeedback 输错时是否震动 + 提示变红（用户在设置里可关）。
 * @param onUnlocked 校验通过（手势或指纹）时回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockScreen(
    patternHash: String,
    salt: String,
    biometricEnabled: Boolean,
    bioTexts: AppLockBioTexts,
    errorFeedback: Boolean,
    onUnlocked: () -> Unit,
    /**
     * 身份验证通过、允许重设手势（2026-11-01「忘记密码」）。
     *
     * ⚠️ 它不是"解锁"：调用方收到之后要**就地打开重设手势的界面**，
     * 在新的手势落盘之前**锁始终存在**（`patternHash` 一个字节都没变）。
     * 这样即使重设被取消，应用仍然是锁着的，不会出现"锁没了"的中间态。
     */
    onResetVerified: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { context.appContainer.settingsRepository }

    // 提示文案：默认引导画手势；错了提示重试（不提示错在哪几个点——那等于泄露一半）。
    var hint by remember { mutableStateOf("绘制手势图案解锁") }
    // 当前这次失败要不要用错误色：成功或开始新一次尝试后复位。
    var failed by remember { mutableStateOf(false) }
    // 每输错一次 +1，驱动手势板「短暂变红 → 清空痕迹」。
    var errorSignal by remember { mutableIntStateOf(0) }
    // 每画对一次 +1，驱动手势板「成功色停留」（2026-11-03 需求）。
    var successSignal by remember { mutableIntStateOf(0) }
    /** 成功停留窗口内为 true：用来挡住同一时刻的第二次解锁入口（见 onPattern 与「忘记密码」按钮）。 */
    var successHold by remember { mutableStateOf(false) }
    // 停留定时需要跨重组存活的协程作用域；锁屏退出组合时自动取消（不会在后台改解锁位）。
    val scope = rememberCoroutineScope()
    /** 连续输错次数（只用于"连错两次后提示可以走忘记密码"，不含点数不足的误触）。 */
    var failCount by remember { mutableIntStateOf(0) }
    // 「忘记密码」弹窗是否打开（2026-11-01）。
    var showForgot by remember { mutableStateOf(false) }

    // 读取盐（哈希校验要）。LaunchedEffect 读一次存进状态即可。
    var saltValue by remember { mutableStateOf(salt) }
    LaunchedEffect(Unit) {
        if (saltValue.isBlank()) {
            saltValue = settings.appLockSalt.first()
        }
    }

    // 设备是否支持生物识别（BIOMETRIC_WEAK 即可，指纹/面部任一）。
    val canBiometric = remember(context) {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    var biometricHint by remember { mutableStateOf<String?>(null) }
    // 系统指纹弹窗是否正在前台（2026-10-29 用户要求：弹窗期间把背景的手势界面藏起来）。
    // 系统弹窗本身没有"已弹出"回调，只能在 `authenticate()` 前后自己记账：
    // 调之前置 true，错误/取消分支（含「使用密码」负按钮）置回 false。
    var bioPromptShowing by remember { mutableStateOf(false) }

    /**
     * 拉起系统指纹弹窗（按钮点按 / 进页自动各走这里）。
     *
     * @param onSuccess 验证通过后的动作。默认 = 解锁进主页；
     *   「忘记密码」时传另一个回调——指纹在那里只用来**证明身份**，
     *   成功后应该去重设手势，而不是把用户放进去（2026-11-01）。
     */
    fun authenticateBiometric(onSuccess: () -> Unit = onUnlocked) {
        // BiometricPrompt 的系统弹窗是一个 Fragment，宿主必须是 FragmentActivity
        // （MainActivity 已为此改继承 FragmentActivity，见其 KDoc）。
        // 这里仍做安全转换：万一将来本页被别的宿主组合，宁可按钮无反应也不崩。
        val activity = context.findActivity() as? androidx.fragment.app.FragmentActivity
            ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    bioPromptShowing = false
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 用户取消（errorCode 5 / 13）不当作失败提示，只在真错误时提示。
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        biometricHint = "指纹识别出错：$errString"
                    }
                    // 弹窗已关（取消 / 使用密码 / 出错）→ 把手势界面放回来。
                    bioPromptShowing = false
                }
            }
        )
        // 文案取自设置。构建逻辑收在 `buildBioPromptInfo`（见 BioPrompt.kt）：
        // 设置页的「预览」用的是**同一个函数**，所以预览看到的就是解锁时看到的。
        val info = buildBioPromptInfo(bioTexts)
        // 先记账再弹：`authenticate()` 是异步的，界面要在同一帧就切走。
        bioPromptShowing = true
        prompt.authenticate(info)
    }

    // 进入锁屏页时若指纹可用且已开启，自动弹一次（最常见的解锁路径零点击）。
    //
    // ⚠️ 必须再判一次 `!AppLockSession.unlocked`：本页可能因为「宽限凭据刚被写入」
    // 之类的状态变化而在同一帧进、下一帧退。门闸那边已经用 `graceChecked` 挡住了
    // 冷启动那一帧，这里再加一道防线，避免任何路径上"已经算解锁了还弹指纹"。
    LaunchedEffect(canBiometric, biometricEnabled) {
        if (canBiometric && biometricEnabled && !AppLockSession.unlocked) authenticateBiometric()
    }

    Scaffold(
        // 锁屏页不挂 TopAppBar（2026-11-02 UI 优化）。
        //
        // 原来那条工具栏只有一个标题「应用已锁定」，却实打实占掉 64dp，
        // 把指纹按钮和手势板一起压到页面下半区；而且"有工具栏"本身就在暗示
        // "这是一个可以上下浏览的页面"，与"门"的语义相反。
        // 标题改由页面内的**标识块**承担（圆形锁图标 + 标题），
        // 位置更居中，视觉上更像一道门而不是一页内容。
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        // 外层只负责"上半部分居中 + 底部一行固定"，中间的内容块不跟着底行使唤。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // ---- 标识块：圆形淡底 + 锁图标 + 标题 ----
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "应用已锁定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // ---- 提示：固定两行高度（2026-11-02）----
                //
                // 为什么要写死高度：默认提示只有一行，但"连错两次 → 忘记手势"那句
                // 要占两行，文案一变长，下面的指纹按钮与手势板整体被往下推约 20dp。
                // 用户画错时界面自己在动，观感很像"卡了一下"。
                // 现在槽位恒为 46dp（≈两行 21sp），一行时上下居中，两行时正好放下，
                // 更长的文案省略——高度永远不变，手势板永远不挪位。
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        // 指纹弹窗在前台 → 「使用指纹解锁」（见 [lockHintText]）；
                        // 指纹出错时优先显示那句错误，不再同时压一行手势引导。
                        text = biometricHint
                            ?: lockHintText(awaitingBiometric = bioPromptShowing, patternHint = hint),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (biometricHint != null || (failed && errorFeedback)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // ---- 指纹：锁屏页的第二条路（自动弹出，最快）----
                // 外观是带描边的小按钮：比实心按钮轻，又比纯文字清楚"这里能点"。
                if (bioPromptShowing) {
                    // ⚠️ 系统指纹弹窗在前台时，手势板整块收起来（2026-10-29 用户要求）：
                    // 系统弹窗是半透明的，底下露着手势点位和上一笔的痕迹既碍眼也容易被误触。
                    // 这里用与手势板**等高的空位**顶住，弹窗关掉后页面不跳动。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在等待指纹验证…",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (biometricEnabled && canBiometric) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { authenticateBiometric() },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Fingerprint,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("指纹登录", style = MaterialTheme.typography.labelLarge)
                    }
                } else if (biometricEnabled && !canBiometric) {
                    // 开了指纹但本机没有可用生物识别：**同一槽位**给禁用态，
                    // 避免这一行随设备能力上下跳动。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "此设备不支持指纹登录",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "可在 设置 → 锁屏密码 → 指纹解锁 里关闭指纹选项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                // ---- 手势板 ----
                // 有指纹行时收窄到 16dp，没开指纹时保持 22dp。
                // 容器从"半透明灰底"改成"实心 surfaceContainer + 1dp 描边"（2026-11-02）：
                // 半透明叠在背景上会随主题变出一块说不清颜色的灰块，
                // 描边 + 实心底在任何主题下都边界清楚，也不需要用投影。
                if (!bioPromptShowing) {
                    Spacer(Modifier.height(if (biometricEnabled) 14.dp else 20.dp))

                    PatternLockView(
                        onPattern = { pattern ->
                            if (pattern.size < MIN_PATTERN_POINTS) {
                                hint = "至少连接 $MIN_PATTERN_POINTS 个点，请重试"
                                failed = false
                                // 点数不够也算一次失败尝试：痕迹要收掉，但不必震动（属误触）。
                                errorSignal++
                                return@PatternLockView
                            }
                            if (hashPattern(saltValue, pattern) == patternHash) {
                                // 画对之后**先停一下**（2026-11-03 需求）：让轨迹用主题色
                                // 亮 [PATTERN_SUCCESS_HOLD_MS] 再走解锁动画。原来当帧就
                                // onUnlocked，锁屏立刻散开，用户根本来不及确认"刚才那条
                                // 轨迹是不是我画的"，观感上像碰巧进去的。
                                // 停留期间手势板自身拒绝输入（successSignal 的含义），
                                // 这里再用 successHold 挡住「忘记密码」这个旁路入口。
                                failed = false
                                successSignal++
                                successHold = true
                                hint = "手势正确"
                                scope.launch {
                                    delay(PATTERN_SUCCESS_HOLD_MS)
                                    onUnlocked()
                                }
                            } else {
                                hint = "手势不正确，请重试"
                                failed = true
                                // 连错两次之后把「忘记密码」这条路显性化（2026-11-01）：
                                // 只在错误文案里提一句，不弹窗、不改布局——用户画错时
                                // 最需要的是一句"还有别的办法"，而不是又一个遮挡层。
                                failCount += 1
                                if (failCount >= 2) {
                                    hint = if (canBiometric) {
                                        "手势不正确。忘记手势？可点下方「忘记密码」用指纹验证"
                                    } else {
                                        "手势不正确。忘记手势可点下方「忘记密码」"
                                    }
                                }
                                // 痕迹清空是**行为修正**（原来画错后线一直挂到下一次按下），
                                // 与"要不要震动/变红"这个用户偏好无关：信号照发，颜色由参数决定。
                                errorSignal++
                                if (errorFeedback) vibrateOnError(context)
                            }
                        },
                        errorSignal = errorSignal,
                        errorColor = if (errorFeedback) MaterialTheme.colorScheme.error else null,
                        // 成功色跟主题走（不写死绿色）：与全应用强调色同一口径。
                        // 传 `MaterialTheme.colorScheme.primary` 与默认值等价，
                        // 但写成显式参数是为了让"成功色=主题色"这个决定在调用点可读。
                        successSignal = successSignal,
                        successColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(10.dp)
                    )
                }
            }

            // ---- 忘记密码（2026-11-01 起不再是占位）--------------------------------
            //
            // 2026-11-02 改成"淡主色胶囊"：原来是无底色的下划线文字，
            // 混在提示语里像一个普通超链接，手指不容易瞄准（14sp 文字高度只有 20dp）。
            // 胶囊给到 38dp 高、水平 18dp 内距，热区与视觉都明确"这是一颗按钮"，
            // 但用 8% 主色而不是实心色，仍然比手势板轻，不抢主路径。
            //
            // 点开后按"先给不丢数据的路"排序：能指纹就走指纹（设备已录指纹即可，
            // 不要求应用内开了指纹解锁开关——这是**身份验证**，不是快捷解锁），
            // 否则用云账号收验证码。两条都不可用时只显示说明，不假装能点。
            //
            // 验证通过的语义见 [onResetVerified]：只是"允许重设手势"，
            // 在新手势落盘前锁依然存在。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        // 成功停留期间不响应：这时解锁已是既定事实，再弹「忘记密码」
                        // 会在 400ms 后被解锁动画连人带弹窗一起掀掉（2026-11-03）。
                        .pressScale(onClick = { if (!successHold) showForgot = true })
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "忘记密码",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (showForgot) {
                ForgotPatternDialog(
                    onDismiss = { showForgot = false },
                    // null = 本机没有可用的生物识别，不显示那个按钮。
                    onUseBiometric = if (canBiometric) {
                        {
                            // 指纹成功 = 身份已验证，直接进入重设（不解锁进主页）。
                            authenticateBiometric(onSuccess = {
                                showForgot = false
                                onResetVerified()
                            })
                        }
                    } else {
                        null
                    },
                    onVerified = {
                        showForgot = false
                        onResetVerified()
                    }
                )
            }
        }
    }
}


/**
 * 「忘记密码」弹窗（2026-11-01）。
 *
 * ## 它做的是"验证身份"，不是"找回密码"
 *
 * 手势只存了加盐 SHA-256（[hashPattern]），**原文不可逆**——没有任何办法告诉用户
 * 他原来画的是哪几个点。所以这里的语义只能是「证明你是机主 → 换一个新手势」。
 *
 * ## 两条路，按"先给不丢数据的路"排
 *
 * 1. **指纹**（[onUseBiometric] 非空时显示）：设备已录指纹即可，**不要求**应用内
 *    开了「指纹解锁」开关——那里的语义是"快捷解锁"，而这里要的是**身份证明**。
 * 2. **云账号短信**：手机号输入框可手动填写（2026-11-03 修）。
 *
 * ## 2026-11-03 修：为什么短信必须能手动填手机号
 *
 * 原实现只对本机**已登录**账号发码（`currentSession()?.phone`），未登录就写
 * 「本机未登录云账号，短信验证不可用」且没有输入框。这等于把"被锁在门外"的
 * 用户唯一那条不丢数据的路堵死：他进不去应用 → 登录不了 → 永远等不到那个"已登录"。
 * 现在改成**可编辑的手机号输入框**（已登录时预填该账号号码），未登录也能用。
 *
 * 另外原实现即使已登录也**发不出码**：会话里的 `phone` 是规范化形式
 * `+86 13800138000`（见 [CloudSession][com.morecheck.data.cloud.CloudSession] 的
 * `phone` 字段），而 `sendCode → requireMainlandPhone` 只认 11 位裸号，会直接抛
 * 「请输入正确的 11 位手机号」。所以这里先用
 * [CloudAuthClient.phoneDigits] 还原成 11 位再预填，发码也用同一个 11 位裸号。
 *
 * ## 契约选择（为什么不 signin / 为什么用普通登录 usage）
 *
 * - 发码走 `sendCode(phone)`（**普通登录用途**，不带 `PASSWORD_RESET`）：这条路径
 *   服务端已验证可用，且它不需要用户先知道"这个号有没有设过密码"；
 * - 验证走 [com.morecheck.data.cloud.CloudAuthClient.verifyCodeOnly]——**不 signin**。
 *   云端是单会话制（`docs/pitfalls/06` 的 5.37），为了解锁而登录会把用户
 *   其它设备全部踢下线，代价与"解锁"不成比例；
 * - 手机号必须对应**已注册**的云账号（`challenge.isExistingUser`）：验证码只能证明
 *   "你能收到这个号的短信"，不证明它与应用里的数据有关联；未注册号如实提示，
 *   不假装可以解锁。这里如实报"未注册"是有意的——用户本人就在设备前，
 *   这不是外部账号枚举场景，说清楚才能让他换对方号码或改走指纹。
 *
 * ## 安全边界（如实记录）
 *
 * 这条路的强度 = "能收到某个已注册手机号的短信"。它防的是"顺手拿起来划两下"，
 * 不防拿到设备并能接收任意短信的人；能用指纹时指纹优先。
 *
 * @param onUseBiometric null = 本机没有可用生物识别，不显示指纹按钮。
 * @param onVerified 身份已验证，调用方应进入「重设手势」而**不是**直接放行。
 */
@Composable
private fun ForgotPatternDialog(
    onDismiss: () -> Unit,
    onUseBiometric: (() -> Unit)?,
    onVerified: () -> Unit
) {
    val context = LocalContext.current
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    /** 本机已登录手机号（11 位裸号）；null = 未登录（或还在读盘）。只用于预填。 */
    var knownPhone by remember { mutableStateOf<String?>(null) }
    /** 用户可编辑的手机号（11 位裸号）。未登录时从空开始，已登录时预填本机账号。 */
    var phoneInput by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    /** 重发冷却秒数，与登录页同口径（约 1 分钟限频）。 */
    var cooldown by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // 读不到（未登录 / 已过期）当"没有可预填的号"，不抛给用户看。
        // ⚠️ 会话里存的是规范化形式 `+86 13800138000`，必须还原成 11 位裸号再预填：
        // 原实现直接把会话里的号码送去 sendCode，被 requireMainlandPhone 判成
        // 格式错误——这是"已登录也发不出验证码"的隐藏成因（2026-11-03 修）。
        val raw = runCatching { authClient.currentSession()?.phone }.getOrNull()
        knownPhone = raw?.let { CloudAuthClient.phoneDigits(it) }
        if (phoneInput.isBlank()) phoneInput = knownPhone.orEmpty()
    }
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    fun sendCode() {
        if (busy || cooldown > 0) return
        // 本地先校验格式：不合法**不发请求、不进冷却**（与登录页同一口径）。
        val formatError = runCatching {
            CloudAuthClient.requireMainlandPhone(phoneInput)
        }.exceptionOrNull()
        if (formatError != null) {
            failed = true
            message = formatError.message ?: "请输入正确的 11 位手机号"
            return
        }
        busy = true
        failed = false
        message = null
        scope.launch {
            try {
                val issued = authClient.sendCode(phoneInput)
                if (!issued.isExistingUser) {
                    // 验证码只证明"能收到这个号的短信"，不证明它与应用里的账号有关联。
                    // 未注册号拦在这里；冷却照样启动（短信已经发出去了，防连点重发）。
                    challenge = null
                    cooldown = 60
                    failed = true
                    message = buildString {
                        append("该手机号尚未注册云账号，无法用它验证身份。")
                        append(
                            if (onUseBiometric != null) {
                                "请换用注册过的号码，或改用上面的指纹验证。"
                            } else {
                                "请换用注册过的号码。"
                            }
                        )
                    }
                } else {
                    challenge = issued
                    cooldown = 60
                    message = "验证码已发送，请查看短信"
                }
            } catch (e: CloudException) {
                failed = true
                message = friendlyCloudMessage(e.error)
            } catch (e: Exception) {
                failed = true
                message = e.message ?: "发送失败，请稍后重试"
            } finally {
                busy = false
            }
        }
    }

    fun verify() {
        val current = challenge ?: return
        if (busy) return
        busy = true
        failed = false
        message = null
        scope.launch {
            try {
                if (authClient.verifyCodeOnly(current, code.trim())) {
                    onVerified()
                } else {
                    failed = true
                    message = "验证码不正确，请重试"
                }
            } catch (e: CloudException) {
                failed = true
                message = friendlyCloudMessage(e.error)
            } catch (e: Exception) {
                failed = true
                message = e.message ?: "验证失败，请稍后重试"
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth()) {
            // 弹窗内容比原来多了一个手机号输入框，小屏 + 键盘弹出时必须能滚动，
            // 否则「取消」会被挤到键盘下面点不到。
            Column(
                Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "忘记手势",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "验证身份后可以重设手势。这不是「找回」——原手势只保存了不可逆的哈希，" +
                        "应用无法还原它。验证不会删除你的打卡数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                if (onUseBiometric != null) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onUseBiometric, modifier = Modifier.fillMaxWidth()) {
                        Text("用指纹验证身份")
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "或者用云账号接收短信验证码",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ---- 手机号（2026-11-03：可手填，不再要求本机已登录）----
                Spacer(Modifier.height(8.dp))
                val prefilled = knownPhone
                Text(
                    text = if (prefilled != null) {
                        "本机已登录：${maskedPhoneOrRaw(prefilled)}；也可改成其它已注册号码"
                    } else {
                        "输入一个已注册云账号的手机号，用短信验证码证明身份"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { input ->
                        // 用宽松规范化而不是 `filter(...).take(11)`：粘贴 "+86 134..." 时
                        // 先截断会得到 "86134331873" 这种非法号（见 [phoneInputDigits]）。
                        val next = phoneInputDigits(input)
                        if (next != phoneInput) {
                            // 换号 = 之前的验证会话作废：否则会用 A 号的验证码去验证 B 号。
                            challenge = null
                            code = ""
                            failed = false
                            message = null
                        }
                        phoneInput = next
                    },
                    label = { Text("手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.filter { it.isDigit() }.take(6)
                        failed = false
                    },
                    label = { Text("6 位短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = failed,
                    enabled = !busy && challenge != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { sendCode() },
                        enabled = !busy && cooldown == 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            when {
                                cooldown > 0 -> "${cooldown}s 后重发"
                                challenge == null -> "发送验证码"
                                else -> "重新发送"
                            }
                        )
                    }
                    Button(
                        onClick = { verify() },
                        // 4 位起就允许点：个别运营商短信里位数会少一位，
                        // 让服务端去判而不是把按钮卡死（错误会以文案回来）。
                        enabled = !busy && challenge != null && code.length >= 4,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (busy) "请稍候" else "验证")
                    }
                }

                val tip = message
                if (tip != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("取消")
                }
            }
        }
    }
}

/**
 * 11 位手机号打码成 `138****8000`。
 *
 * 长度不是 11 就原样返回——不猜格式、也不截断：这里只是"让用户认出是自己的号"，
 * 格式异常时原样显示比猜错更安全。
 */
private fun maskedPhoneOrRaw(phone: String): String =
    if (phone.length == 11) phone.replaceRange(3, 7, "****") else phone

/**
 * 手机号输入框的**宽松规范化**（输入过程中允许不足 11 位）。
 *
 * 为什么不能直接用 `filter(Char::isDigit).take(11)`：从剪贴板粘贴
 * `+86 13433187381` 时数字串是 `8613433187381`（13 位），先 `take(11)` 会截成
 * `86134331873` —— 一个首两位是 86、长度 11 的非法号，用户看着"填满了"却一直
 * 被校验打回。所以必须**先按区号规则去前缀、再截 11 位**，规则与
 * [CloudAuthClient.phoneDigits] 一致（那一个是严格版：只认最终 11 位）。
 */
private fun phoneInputDigits(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.startsWith("0086") -> digits.substring(4)
        digits.startsWith("86") && digits.length > 11 -> digits.substring(2)
        else -> digits
    }.take(11)
}
