package com.morecheck.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.ActivityCompat
import com.morecheck.ui.AppBottomSheet
import com.morecheck.ui.LegalDoc
import com.morecheck.ui.LegalDocLayer
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.LegalParagraph
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.di.appContainer
import com.morecheck.util.AppPermission
import com.morecheck.util.findActivity
import com.morecheck.util.AppPermissions
import kotlinx.coroutines.delay

/**
 * 「设置 → 通用 → 权限管理」。
 *
 * 三项开关（摄像头 / 定位 / 通知）**默认全关**，每项都附带使用场景说明，
 * 让用户知道「打开它换来了什么」。
 *
 * ## 开关与系统权限是两件事
 *
 * - **开关**是用户在本应用里的意图（存在 DataStore）；
 * - **系统权限**是操作系统的事实状态，用户随时可能在系统设置里改动。
 *
 * 因此本页的交互刻意是「先查系统、再动开关」：
 * 拨到**开**时如果系统还没授权，开关**保持关**并跳到系统设置页，
 * 避免出现「开关开着但功能依然用不了」的假象；
 * 拨到**关**时只关应用开关（应用无权撤销系统权限，也做不到）。
 *
 * ## 与 `MainActivity.requestNotificationPermissionIfNeeded()` 的关系
 *
 * `MainActivity.onCreate` 里还有一条「首次启动自动申请通知权限」的路径，**保留不动**：
 * 那是系统权限的申请（弹系统对话框），而本页的开关是应用意图标记，两者互不冲突。
 * 也就是说，用户可能在系统层面已经允许通知，但本页的通知开关仍然是关的——
 * 这正是「默认关」的预期表现，需要用户自己来打开。
 *
 * ## 回前台自动复核
 *
 * 用户被跳到系统设置页后，回来时 Activity 会重新 `ON_RESUME`。
 * 这时如果**曾经因为未授权而拨过某个开关**（[pendingPermission]），
 * 就复查一次系统权限，拿到了才自动补开开关。
 *
 * ⚠️ 只用 [pendingPermission] 触发自动开启，**不做全量同步**：
 * 否则「系统已授权、但用户主动把开关关掉」的项会在下次回前台时被无脑重新打开，
 * 用户永远关不掉开关（这正是本项目历史上踩过的「开关关不掉」那类坑）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {},
    // 复用 MainActivity 创建的那一份，保证与设置页 / 主题使用同一处状态。
    viewModel: SettingsViewModel = LocalSettingsViewModel.current,
) {
    val context = LocalContext.current
    val cameraEnabled by viewModel.cameraEnabled.collectAsStateWithLifecycle()
    val locationEnabled by viewModel.locationEnabled.collectAsStateWithLifecycle()
    val notificationEnabled by viewModel.notificationEnabled.collectAsStateWithLifecycle()

    // 撤回隐私同意的倒计时弹窗状态：null = 不显示；非 null = 显示且已在倒计时。
    var showRevokeDialog by remember { mutableStateOf(false) }

    // 只在「用户拨开关时系统未授权」这一种情况下被设置；
    // 它是自动补开开关的唯一依据，也是「用户主动关闭不被打扰」的保险。
    var pendingPermission by remember { mutableStateOf<AppPermission?>(null) }

    // 用 rememberUpdatedState 持有最新的回调：否则调用方每次重组传入的新 lambda
    // 会让下面的 DisposableEffect 反复重注册观察者。
    val currentOnMessage by rememberUpdatedState(onMessage)

    /** 把开关状态写回 ViewModel（三处同构，集中在这里避免散落）。 */
    fun setEnabled(permission: AppPermission, enabled: Boolean) {
        when (permission) {
            AppPermission.CAMERA -> viewModel.setCameraEnabled(enabled)
            AppPermission.LOCATION -> viewModel.setLocationEnabled(enabled)
            AppPermission.NOTIFICATION -> viewModel.setNotificationEnabled(enabled)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val pending = pendingPermission ?: return@LifecycleEventObserver
            if (AppPermissions.isGranted(context, pending)) {
                setEnabled(pending, true)
                currentOnMessage("「${pending.title}」已开启")
            } else {
                currentOnMessage("未获得「${pending.title}」权限，仍保持关闭")
            }
            // 无论成功与否都清掉：一次拨动只复核一次，不反复弹提示。
            pendingPermission = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 运行时权限申请 ──
    //
    // launcher **必须在组合期创建一次**（每次重组都必须是同一实例），所以放在这里而不是
    // 塞进 onToggle。回调 `(Boolean)` 里拿不到「刚才申请的是哪个权限」，
    // 因此配一个 pendingRequest 记住目标，**回调后立刻清空**。
    var pendingRequest by remember { mutableStateOf<AppPermission?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingRequest
        pendingRequest = null
        if (target == null) return@rememberLauncherForActivityResult

        if (granted) {
            setEnabled(target, true)
            currentOnMessage("「${target.title}」已开启")
        } else {
            setEnabled(target, false)
            // 区分「这次拒绝了」与「已被永久拒绝」：
            // 只有后者才值得把用户送去系统设置，否则一拒绝就被弹走会让人烦。
            if (isPermanentlyDenied(context, target)) {
                AppPermissions.openAppSettings(context)
                pendingPermission = target
                currentOnMessage("请在系统设置中允许「${target.title}」权限，返回后会自动开启")
            } else {
                currentOnMessage("未获得「${target.title}」权限，仍保持关闭")
            }
        }
    }

    /** 用户拨动开关的统一入口。 */
    fun onToggle(permission: AppPermission, enable: Boolean) {
        if (!enable) {
            // 只关应用开关：系统权限归手机设置管，应用没有撤销它的能力。
            setEnabled(permission, false)
            return
        }
        if (AppPermissions.isGranted(context, permission)) {
            // 已授权（含 API<33 通知这种「本就不需要申请」的情况）→ 直接开。
            setEnabled(permission, true)
            return
        }
        // 系统未授权：**先向系统申请**，拿到就开；拿不到再看是不是被永久拒绝。
        // 开关先保持关，等回调决定。
        setEnabled(permission, false)
        val manifest = permission.manifestPermissions?.firstOrNull()
        if (manifest == null) {
            // 理论上到不了这里（null 会被 isGranted 当成已授权），
            // 留着是为了绝不对着 null 调 launch 而崩溃。
            currentOnMessage("当前系统版本无需申请「${permission.title}」权限")
        } else {
            pendingRequest = permission
            permissionLauncher.launch(manifest)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    PermissionRow(
                        permission = AppPermission.CAMERA,
                        checked = cameraEnabled,
                        onToggle = { onToggle(AppPermission.CAMERA, it) }
                    )
                    PermissionDivider()
                    PermissionRow(
                        permission = AppPermission.LOCATION,
                        checked = locationEnabled,
                        onToggle = { onToggle(AppPermission.LOCATION, it) }
                    )
                    PermissionDivider()
                    PermissionRow(
                        permission = AppPermission.NOTIFICATION,
                        checked = notificationEnabled,
                        onToggle = { onToggle(AppPermission.NOTIFICATION, it) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = "开关只表示你是否允许本应用使用该能力；" +
                    "系统的授权状态由手机设置决定，两者都打开才能使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ---- 撤回隐私同意 ----
            //
            // 这里没有用 Switch（撤回不是「开关」语义，而是一个不可逆动作）：
            // 点击行本身 → 弹 5 秒倒计时确认 → 再次确认才退出。
            // 用行而不是开关，是为了避免用户误触「看起来像开关」的控件就直接触发危险动作。
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable。
                        .pressScale(onClick = { showRevokeDialog = true })
                        // 2026-10-25 需求：这一行比普通入口行更高一点（危险动作，
                        // 多留出的高度让它比普通入口更醒目、也更不容易误触）。
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "撤回隐私同意",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        // 2026-10-22 需求 5：删掉这行备注小字（「撤回后需重新同意协议才能继续使用」）。
                        // 这一行的语义在标题里已经完整（撤回同意 = 要重新同意），
                        // 多一行小字反而让这个危险动作看着"不需要认真对待"。
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRevokeDialog) {
        RevokePrivacyDialog(
            onConfirm = {
                viewModel.revokePrivacy()
                // 撤回后退出应用；下次启动会重新要求同意协议。
                context.findActivity()?.finish()
            },
            onDismiss = { showRevokeDialog = false }
        )
    }
}

/**
 * 「撤回隐私同意」的确认**底部弹窗**：**5 秒倒计时**期间不可确认，倒计时结束才允许。
 *
 * 为什么倒计时：这是一个不可逆、且会导致应用退出的动作，给用户一个冷静期，
 * 避免误触后直接退出。倒计时期间确认按钮置灰并显示剩余秒数。
 *
 * 为什么用底部弹窗：与首次启动的同意弹窗保持同一套视觉语言（需求要求「同理」）。
 * 两者共用 [AppBottomSheet] 骨架，不各写一份，否则样式迟早走样。
 * 与同意弹窗的唯一区别：这个是**可以关掉**的（`dismissible = true`），
 * 因为用户可能只是想看看、并不真要撤回。
 *
 * 正文里的《用户协议》《隐私政策》是**可点链接**（与同意弹窗共用 [legalLinkSentence]）：
 * 用户在这里正要做「撤回」的决定，必须能就地读回原文（需求 bug2 就是这里点了没反应）。
 * 阅读层用 [LegalDocLayer]，与同意弹窗一样**画在同一个 Dialog 窗口内**从右滑入，
 * 不再单独开窗口（那会导致两个窗口时序不同步、产生残影）。
 *
 * ## 正文的两层结构（本轮改造）
 *
 * 与同意弹窗同一套层次：**主句**（会发生什么 + 想继续用该怎么办，含两个可点链接）
 * → **附注块**（本机数据的事实、怎么彻底删除、法律例外）。附注同样收进一个浅色圆角容器，
 * 与同意弹窗的写法一致，两处弹窗读起来才是一家人。
 *
 * ⚠️ **本轮只改文案与排布，功能一个字都没动**：倒计时仍是 5 秒、
 * `confirmEnabled` 仍是「到 0 才可点」、`onConfirm` 仍是调 ViewModel 的 `revokePrivacy()`。
 */
@Composable
private fun RevokePrivacyDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 正文与附注取色统一走这里：与 [AppBottomSheet] / 同意弹窗同一口径。
    val cs = MaterialTheme.colorScheme
    var secondsLeft by remember { mutableStateOf(5) }
    // 正在查看的协议；`docVisible` 单独控制进出场（`viewing` 关闭后不清空，见 [LegalDocLayer]）。
    var viewing by remember { mutableStateOf<LegalDoc?>(null) }
    var docVisible by remember { mutableStateOf(false) }

    // ── 2026-10-25 需求：点「确认退出」后先转圈 0.2 秒再真正退出 ──────────────
    //
    // `exiting` 为 true 时确认按钮显示「小圈 + 确认退出」并置灰；
    // 0.2 秒后才调用真正的 [onConfirm]（撤回 + finish，App 退出）。
    // 由开发者选项「撤回退出转圈」回退开关门控（默认开；关 = 立即退出旧行为）。
    val context = LocalContext.current
    val devOptions = remember(context) {
        context.appContainer.developerOptionsRepository
    }
    val spinnerEnabled by devOptions.revokeExitSpinnerEnabled
        .collectAsStateWithLifecycle(initialValue = true)
    var exiting by remember { mutableStateOf(false) }
    LaunchedEffect(exiting) {
        if (exiting) {
            delay(REVOKE_EXIT_DELAY_MS)
            onConfirm()
        }
    }

    // 倒计时：每秒减一，到 0 停止。
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    AppBottomSheet(
        title = "撤回隐私同意",
        // 倒计时期间按钮显示剩余秒数并置灰；到 0 才可点。
        //
        // ⚠️ **只有这个按钮文案**跟 `secondsLeft` 有关，下面整块正文（主句 + 附注）里
        // 一个 `secondsLeft` 都没有。这是刻意的：一旦正文随倒计时变字（哪怕只多一句
        // 「确认撤回并退出应用？」），倒数到 0 的瞬间整段文字就会跳一下，看起来像页面重排。
        // 这条有历史记录，改文案时必须继续守住——加句子可以，**不能加条件分支**。
        confirmLabel = if (secondsLeft > 0) "确认（${secondsLeft}s）" else "确认退出",
        confirmEnabled = secondsLeft == 0,
        // 2026-10-25：确认点击后进入转圈态（按钮变灰、小圈 + 文案），延迟后再退出。
        confirmLoading = exiting,
        // ⚠️ 转圈时会关闭「点确认即收起弹窗」的默认行为（2026-10-27 修）。
        // 默认的 runAfterHide 会先播弹窗下滑退场、再回调，转圈所在的组合当场被销毁，
        // 用户只看到弹窗滑走——这就是"撤回退出转圈没有效果"的根因。
        // 关掉转圈（旧行为）时仍走默认的「先收起再退出」，与改造前逐帧一致。
        confirmHidesSheet = !spinnerEnabled,
        onConfirm = {
            if (spinnerEnabled) {
                exiting = true
            } else {
                // 回退开关关闭：保持旧行为，点确认立即执行（AppBottomSheet 内部
                // 仍会先播 sheet 的下滑退场动画再回调，与改造前一致）。
                onConfirm()
            }
        },
        onCancel = onDismiss,
        cancelLabel = "取消",
        onDismissRequest = onDismiss,
        // 需求 v1.1.66：撤回弹窗**不能再靠向下滑动消失**（也不能点遮罩关掉）。
        // `dismissible = false` 会让 `confirmValueChange` 拒绝 `Hidden` 状态，
        // 于是无论下滑手势还是遮罩点击都关不掉它；两个按钮才是唯一出口。
        //
        // ⚠️ 这条改动**是有意收紧**的：用户明确要求「改为弹窗不能通过向下滑动消失」。
        // 之前 `dismissible = true`（「用户可能只是想看看」）现在已经不适用。
        dismissible = false,
        // 需求 v1.1.66：正文**禁止上下滑动**（详见 [AppBottomSheet.bodyScrollable]）。
        bodyScrollable = false,
        // 阅读层的圆角/底色已结构性恒定，骨架不再需要任何「覆盖层是否可见」的入参
        // （原先这里传的 `overlayVisible` 已随 v1.1.69 的圆角恒定修法删除）。
        // ⚠️ 只给「撤回」弹窗加高（见 [REVOKE_SHEET_HEIGHT_FRACTION] 的说明）：
        // 首次同意弹窗**不传**这个参数，仍用骨架的默认值——需求要求「不改同意页」。
        heightFraction = REVOKE_SHEET_HEIGHT_FRACTION,
        overlay = {
            LegalDocLayer(
                doc = viewing,
                visible = docVisible,
                onBack = { docVisible = false }
            )
        }
    ) {
        // 正文：**单段、单一字号、单一颜色**（需求 v1.1.66）。
        //
        // 原先是一个浅灰圆角容器装两段 `bodySmall` 附注，用户看后明确要求
        // 「同意与撤回协议界面不要有文本备注的样子，就是文字都是一个样式」——
        // 「缩进 + 浅底 + 更小字号」这套排版读起来就是「正文下面的备注」，
        // 于是这里合并成**一段**：同一个 bodyMedium、同一个颜色、同一档行高，
        // 分段只靠句号与语义，不再靠底色/字号做层次。
        //
        // ## 链接为什么用 [LegalParagraph] 而不是 `legalLinkSentence`
        //
        // 需求 bug：「撤回隐私同意界面用户协议无法点击」。实机逐点扫描确认：
        // 这一段里 **《隐私政策》点得动、《用户协议》整行都是死区**，而同意弹窗里
        // 同样的两个链接都点得动。唯一差别是断行位置——这段里《用户协议》正好落在
        // 第 1 行**行尾**，`LinkAnnotation` 按行盒算出的命中矩形与字形错位。
        // [LegalParagraph] 自己用 `getOffsetForPosition` 算「点到了哪个字」，不受此影响。
        //
        // ⚠️ 这段文案**不随倒计时变化**。需求明确要求「倒计时时与倒计时结束后的文字一致」：
        // 原先结束时会在前面多插一句「确认撤回并退出应用？」，导致倒数到 0 的瞬间
        // 整段文字跳一下，看起来像页面重排。加句子可以，**不能加条件分支**。
        LegalParagraph(
            // 收尾说明**接在同一段里**。措辞刻意说清**本应用的真实行为**，而不是照搬
            // 「停止收集您的个人信息」这类模板话术：本应用是纯本地打卡应用，
            // 数据只写在本机 DataStore / Room、全程不上传，所以「撤回」的真实含义是
            // 「不再同意我们继续提供本地服务」，而不是「删掉服务器上的资料」——后者根本没发生过。
            //
            // 同时刻意**不承诺「撤回即自动删除本机数据」**：`revokePrivacy()` 只把同意标记
            // 置回 false（见 SettingsViewModel），并没有清库动作。承诺做不到的删除，
            // 才是真正的合规问题；所以如实告知「不会自动删除」并给出彻底删除的路径。
            fullText = "撤回同意后，本应用会立即退出，并停止为你提供打卡服务。" +
                "如需继续使用，请在下次启动时重新同意《用户协议》与《隐私政策》。" +
                "本应用默认只在本机保存你的打卡数据与资料；" +
                "若你此前主动开启过「云同步」或登录过「今日待办」，" +
                "对应数据已上传到云端（不随撤回同意自动删除，删法见《隐私政策》第七条）；" +
                "撤回同意不会自动删除手机上已有的内容，你可以卸载应用或在系统设置中" +
                "「清除数据」来彻底删除。法律法规对信息保存期限另有规定的，从其规定。",
            linkTexts = listOf("《用户协议》", "《隐私政策》"),
            onLinkClick = { index ->
                viewing = if (index == 0) LegalDoc.Agreement else LegalDoc.Privacy
                docVisible = true
            }
        )
    }
}

/**
 * 「撤回隐私同意」弹窗的高度比例（占屏高），比 [AppBottomSheet] 的共用默认值高一档。
 *
 * 为什么需要单独给一个值：本轮按要求把正文写丰富（主句 + 两层附注块），
 * 需要的版面比首次同意弹窗（一句主句 + 一句附注）大。取 **0.46**：
 * 比骨架默认的 0.41 多约 12%，正文不至于一上来就要滚动，
 * 又不至于高到让「取消 / 确认退出」两个按钮离正文太远。
 *
 * ⚠️ 只作用于撤回弹窗：这个值通过 `heightFraction` 参数传给 [AppBottomSheet]，
 * 而**首次同意弹窗不传该参数**，仍用骨架里的默认常量——需求明确要求「不改同意页」，
 * 所以骨架里的默认常量一个字都没有动。
 *
 * 注意正文区本身仍带 `verticalScroll`：小屏 / 大字体下正文放不下时照旧能滚，
 * 这个比例只决定「正常情况下留多大的版面」，不是硬性截断。
 */
private const val REVOKE_SHEET_HEIGHT_FRACTION = 0.46f

/**
 * 「确认退出」点击后转圈的停留时长。
 *
 * 2026-10-25 需求原文是 0.2 秒；2026-10-27 用户反馈「撤回退出转圈没有效果」，
 * 排查出两个真 bug（弹窗抢先收起 + 白圈压灰底看不见，见 [AppBottomSheet.confirmHidesSheet]），
 * 修完后 200ms 只剩"闪一下"，仍然谈不上"有效果"，因此把停留时间提到 700ms：
 * 一个 16dp 的圈转两圈多，肉眼能明确看出"正在退出"。
 * 若还要更短/更长，改这一个常量即可（开发者选项的描述文案与它保持同一口径）。
 *
 * 由开发者选项「撤回退出转圈」回退开关门控；开关关闭时该延迟不生效（立即退出）。
 */
private const val REVOKE_EXIT_DELAY_MS = 700L

/** 权限行的分隔线：与卡片内容的左内边距对齐。 */
@Composable
private fun PermissionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 单个权限行：标题 + 使用场景说明 + 右侧开关。
 *
 * 三项逻辑完全同构，因此抽成一个行组件，只在调用处绑定各自的 [AppPermission]。
 */
@Composable
private fun PermissionRow(
    permission: AppPermission,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                permission.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Text(
                permission.scene,
                style = MaterialTheme.typography.labelSmall,
                // 副标题用 onSurface 压暗（不是 onSurfaceVariant）：后者偏灰，
                // 在浅色模式下「不够黑」。与设置页保持同一口径。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

/**
 * 判断该权限是否已被**永久拒绝**（「不再询问」）。
 *
 * 判据：`shouldShowRequestPermissionRationale` 返回 false，且当前确实未授权。
 * 之所以要区分它和「这次普通拒绝」，是因为两者的正确引导完全不同：
 * - 普通拒绝：还能再弹系统框，继续解释即可，**不该**把用户甩去系统设置；
 * - 永久拒绝：系统框不会再出现了，只能引导去系统设置，否则用户会觉得「点了没反应」。
 *
 * `manifestPermissions` 是列表，而该 API 只能逐个权限判断，因此取第一个
 * （CAMERA / ACCESS_FINE_LOCATION / POST_NOTIFICATIONS，与申请时用的同一个）。
 */
private fun isPermanentlyDenied(context: Context, permission: AppPermission): Boolean {
    val activity = context.findActivity() ?: return false
    val manifest = permission.manifestPermissions?.firstOrNull() ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, manifest)
}

