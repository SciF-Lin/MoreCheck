package com.morecheck.ui.mine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.R
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.auth.AuthViewModel
import com.morecheck.ui.common.ConfirmDialog
import com.morecheck.ui.common.pressScale
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个人信息页（我的 → 头像卡片）。
 *
 * 版式照参考图：标题栏 + 「基础资料」分组 + 一张资料卡片 + 底部一个长条按钮。
 * 配色全部取自 [MaterialTheme]，因此**跟随「深色模式」与「主题色」设置**，无需额外分支。
 *
 * 按需求定下的行为：
 * - 「头像」行**可点**：点击先弹一个**来源选择弹窗**（[AvatarSourceDialog]）——
 *   「使用默认头像」进内置头像网格（[DefaultAvatarPickerDialog]），
 *   「从相册选择」走系统图片选择器 → 1:1 裁切页 → 保存后立刻替换头像，再次点击即可更换。
 *   没有保存过头像时回落到灰色占位（[DefaultAvatar]）。
 * - 「昵称 / 手机号 / 性别」三行**保持不变**：仍是只读，没有右箭头。
 * - 底部按钮是**双态**的：未登录（[maskedPhone] 为 null）时是「注册 / 登录」，
 *   已登录时变成「退出登录」并清除登录态。
 *
 * ## 为什么「选择默认头像」不再单独占一行（本轮改动）
 *
 * 旧版把「选择默认头像」做成了与「头像」并列的**第二行**，代价是这一小片区域里出现两个
 * 语义相近的入口：用户看到「头像」和「选择默认头像」并排，很难判断两者是互斥的两条路，
 * 还是"先点上面再点下面"的两步；而且真正的动作（换头像）被拆到两处，谁也说不清
 * 「上传之后还能不能换回默认」。现在收敛成**一个入口、一次询问、两条分支**：
 * 点头像 → 选来源 → 各自完成，与系统相册「选图片」的交互一致，没有歧义。
 *
 * 头像路径取自 [SettingsRepository][com.morecheck.data.SettingsRepository] 的
 * `avatarPath` 键；这里沿用本项目 `WeatherScreen` 的既有做法，
 * 直接用 `applicationContext as MoreCheckApp` 拿容器里的仓库——
 * 这样不必给 `SettingsViewModel` / `AppContainer` 新增任何成员。
 *
 * ## 为什么默认头像也要走 [AvatarStore] 落盘（而不是记「当前用第几个内置头像」）
 *
 * 设想过另一套方案：DataStore 里只存一个 `builtinAvatarIndex`，显示时按索引取 drawable。
 * 放弃了，理由是**它会把「头像」劈成两个来源**：
 *
 * 1. `avatarPath`（用户自己裁的图）与 `builtinAvatarIndex`（内置图序号）**并存**，
 *    于是每处读头像的地方都要先判「用户用的是哪种」——而本页、
 *    以及任何将来要显示头像的地方（例如顶栏、评论气泡）都会重复这个判断，
 *    漏一处就出现「存了头像却不显示」这种最难查的 bug。
 * 2. 两条来源之间还要定义**优先级与互斥**：用户先选内置 3 号、再上传自拍，
 *    索引要不要清掉？留着就是死数据，清掉就得额外写一次 DataStore；
 *    反过来从自拍切回内置，同样要写。规则全靠人记，迟早与实现不一致。
 * 3. 换头像的**代价不对称**：自拍要解码、裁切、压缩、写盘；内置图只需记个数字。
 *    一旦某天想给内置图也加「裁切 / 滤镜」，落盘方案天然支持，枚举方案得回头改数据层。
 *
 * 落盘方案只有一条路径：**选中 → 解码 drawable → [AvatarStore.saveAvatar] → setAvatarPath**。
 * 之后全应用仍只有 `avatarPath` 一个事实来源，「当前头像是什么」永远只有一个答案。
 * 代价是第一次选内置图时要写一次 512×512 的 JPEG（约几十 KB），
 * 而这个动作与「选图裁切后」共用同一条代码路径，不需要任何额外分支。
 *
 * ## 内置头像素材
 *
 * 放在 `res/drawable-nodpi/`（`ic_default_avatar_1..8.jpg`）而不是各密度 mipmap：
 * 这些图**只在对话框里以约 96dp 显示**，且选中后会立刻被重采样成 512×512 落盘，
 * 屏幕密度无关紧要；`nodpi` 还能避免系统按密度做无意义的二次缩放、徒增包体。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalInfoScreen(
    maskedPhone: String?,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    /**
     * 打开留空二级页，参数是 `PlaceholderEntry.value`。
     *
     * 昵称 / 手机号 / 性别三行按需求保持只读，本页不再调用它；形参保留是为了
     * 不破坏调用方契约（导航图仍在传），也便于以后某个字段重新需要二级页时直接接上。
     */
    onOpenPlaceholder: (String) -> Unit = {},
    /**
     * 进入「同步管理」页（2026-10-22 需求 7：「同步管理」从「我的」搬到这里）。
     *
     * 默认空实现：调用方（导航图）已经接线；给默认值只是让预览 / 测试不必构造它。
     */
    onOpenSyncManage: () -> Unit = {},
    /** 进入「登录密码」页（设置 / 修改密码，2026-11-03 密码登录配套）。仅登录后可点。 */
    onOpenSetPassword: () -> Unit = {},
    /** 页面级一次性提示（退出登录、头像保存结果）。默认空实现，方便调用方先不接线。 */
    onMessage: (String) -> Unit = {},
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 直接用 AppContainer 里的仓库：头像路径是这一页独有的状态，
    // 为它去扩 SettingsViewModel 会牵动 ui/settings 与 AppViewModelProvider 两个文件。
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }

    // 头像位图：与「我的」页共用同一份实现（2026-10-26 统一，根因见
    // `rememberAvatarBitmap` 的 KDoc——原来两页各一份，且都在首帧把缓存抹掉）。
    val avatarBitmap = rememberAvatarBitmap()

    // 注册时填写的资料。三者都来自 AuthRepository（注册成功时一次性写入），
    // 未注册（走验证码登录）时均为 null，界面回落为「未填写 / 未知」。
    val userId by authViewModel.userId.collectAsStateWithLifecycle()
    val nickname by authViewModel.nickname.collectAsStateWithLifecycle()
    val gender by authViewModel.gender.collectAsStateWithLifecycle()

    // 会话被服务端吊销（其它设备登录挤掉）时为真（需求：登录过期 → 页面清空 + 灰头像 + 提示）。
    // 过期时所有资料不再可信：头像置灰、字段全部「未填写」、底部回到「注册 / 登录」。
    val sessionExpired by authViewModel.sessionExpired.collectAsStateWithLifecycle()
    val expired = sessionExpired
    val viewMaskedPhone = if (expired) null else maskedPhone
    val viewNickname = if (expired) null else nickname
    val viewUserId = if (expired) null else userId
    val viewGender = if (expired) null else gender
    val viewAvatar = if (expired) null else avatarBitmap

    // 解码后的头像位图：见上面的 rememberAvatarBitmap()。
    // （历史注释保留在这里没有意义了——为什么不能在组合里就地解码、
    //  为什么只订阅 avatarPath 不够，都已并入 rememberAvatarBitmap 与 AvatarRevision 的 KDoc。）

    // 用户在系统选择器里选中的图，等待裁切。非 null 即表示要显示裁切页。
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }

    // 选图用 PickVisualMedia（Android 13+ 原生照片选择器）：
    // · 它不需要任何存储权限，低版本会自动回退到 ACTION_OPEN_DOCUMENT，全平台一致；
    // · 对比 GetContent("image/*")，它在 13+ 上不给应用任何相册访问权，隐私上更干净。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // 用户取消时 uri 为 null，什么都不做（保持原头像）。
        if (uri != null) pendingCropUri = uri
    }

    // 「退出登录」的二次确认开关（需求：退出是破坏性操作，要先确认）。
    // 状态放在 [PersonalInfoScreen]（而不是 MainContent 里）：裁切页会整体替换 MainContent，
    // 状态留在这里才不会因为进出裁切页而被重置。
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 「头像来源」选择弹窗的开关（本轮新增）。
    //
    // 点「头像」行不再直接拉系统选择器，而是先问一句「用内置头像，还是从相册挑」。
    // 这个状态与下面的网格对话框一样放在**本函数**而不是 MainContent 里：
    // MainContent 会因为显示裁切页而被整体换掉（见下面的 if/else），
    // 开关若留在它里面，进出一次裁切页就丢了。
    var showAvatarSourcePicker by remember { mutableStateOf(false) }

    // 「选择默认头像」网格对话框开关。由上面那个弹窗的「使用默认头像」打开。
    var showDefaultAvatarPicker by remember { mutableStateOf(false) }

    // 正在写入磁盘的默认头像序号；非 null 时对话框里的那一格显示「保存中」。
    var savingDefaultAvatar by remember { mutableStateOf<Int?>(null) }

    // 选默认头像 → 落盘 → 刷新。**与「选图裁切后」共用同一条代码路径**
    // （decodeStored 只认 avatarPath，因此写完路径界面自动刷新，无需手动通知 Image）。
    //
    // 为什么 decode + compress 都要放 IO：`BitmapFactory.decodeResource` 读的是打包进 APK 的
    // 资源，虽然比读相册快，但仍是磁盘/内存操作；`compress` 更是同步阻塞的 JPEG 编码。
    // 两者都不该出现在主线程上——头像缩略图只有 96dp，用户点下去到界面刷新有几十毫秒延迟
    // 完全无感，但卡住一帧就会掉帧。
    val onPickDefaultAvatar: (Int) -> Unit = { resId ->
        savingDefaultAvatar = resId
        scope.launch {
            // 先解码再落盘。用 [AvatarStore.EXPORT_SIZE] 做采样上限：内置图最大 700px，
            // 与导出尺寸同量级，采样后不会损失可见细节；真按 512 硬缩一次反而多一次重采样。
            val bitmap = withContext(Dispatchers.IO) {
                decodeDefaultAvatarBitmap(context, resId, AvatarStore.EXPORT_SIZE)
            }
            val ok = if (bitmap != null) {
                withContext(Dispatchers.IO) { AvatarStore.saveAvatar(context, bitmap) }
            } else {
                false
            }
            if (ok) {
                settingsRepository.setAvatarPath(AvatarStore.storedPath(context))
                // ⚠️ 头像文件被**同一个路径**覆盖了，必须再发一次"内容变了"的信号，
                // 否则「我的」页与「我的信息」页都收不到刷新（原因见 [AvatarRevision]）。
                AvatarRevision.bump()
                onMessage("头像已更新")
                showDefaultAvatarPicker = false
            } else {
                // 解码或写盘失败都要让用户知道，不能静默地"以为换了"。
                onMessage("默认头像保存失败，请重试")
            }
            savingDefaultAvatar = null
        }
    }

    // ---- 裁切页：整页覆盖本页 ----
    //
    // 不走导航路由，而是就地覆盖：裁切页只服务于这一页的头像，
    // 为它在 TabNavHost 里加一条 route 会把一个纯 UI 的临时流程提升成全局导航状态
    // （还要处理"返回时是回本页还是回上一页"），得不偿失。
    // 系统返回键由 AvatarCropScreen 内部的 BackHandler 接住。
    //
    // 用 if/else 而不是提前 `return`：可组合函数里提前返回虽然合法，
    // 但会让"这一帧到底组成了什么"变得难读，二分结构更直白。
    val cropUri = pendingCropUri
    if (cropUri != null) {
        AvatarCropScreen(
            sourceUri = cropUri,
            onCancel = { pendingCropUri = null },
            onSaved = { path ->
                pendingCropUri = null
                // 落盘路径进 DataStore；avatarPath 流一发射，上面的 LaunchedEffect
                // 就会重新解码，头像无需手动刷新。
                //
                // 仍然要 bump 一次：`AvatarStore.saveAvatar` 写的是**固定文件名**，
                // 如果用户之前存过头像，这次写进去的路径与上一次完全相同，
                // 光靠 path 这一侧是感知不到内容变化的（详见 [AvatarRevision]）。
                // 第一次设置时 path 确实变了，多一次 bump 只是让解码 effect 重启一次，无副作用。
                scope.launch {
                    settingsRepository.setAvatarPath(path)
                    AvatarRevision.bump()
                    onMessage("头像已更新")
                }
            }
        )
    } else {
        MainContent(
            sessionExpired = expired,
            maskedPhone = viewMaskedPhone,
            nickname = viewNickname,
            userId = viewUserId,
            genderLabel = genderLabelOf(viewGender),
            onBack = onBack,
            onOpenLogin = onOpenLogin,
            onOpenSyncManage = onOpenSyncManage,
            onOpenSetPassword = onOpenSetPassword,
            avatarBitmap = viewAvatar,
            onPickAvatar = { showAvatarSourcePicker = true },
            showLogoutConfirm = showLogoutConfirm,
            onRequestLogout = { showLogoutConfirm = true },
            onDismissLogout = { showLogoutConfirm = false },
            onConfirmLogout = {
                showLogoutConfirm = false
                authViewModel.logout()
                onMessage("已退出登录")
            }
        )
    }

    // ── 头像相关的两层对话框 ──────────────────────────────────────────────
    //
    // 两层都挂在页面最外层（与「退出登录」二次确认同一层）：它们是独立窗口，
    // 放在 MainContent 的卡片里虽然也能显示，但语义上服务的是整个头像功能、
    // 不只属于那张卡片里的一行。
    //
    // ⚠️ 两层**不能同时**为 true：Compose 的 Dialog 虽然是独立窗口，
    // 但同屏两个 AlertDialog 会互相压住、返回键归谁都说不清。
    // 所以「使用默认头像」这一支里是**先关来源弹窗、再开网格弹窗**（顺序执行，不会重叠）。
    if (showAvatarSourcePicker) {
        AvatarSourceDialog(
            onPickDefault = {
                // 顺序很重要：先关掉自己再开下一个。
                showAvatarSourcePicker = false
                showDefaultAvatarPicker = true
            },
            onPickGallery = {
                showAvatarSourcePicker = false
                // 既有链路，行为不变：系统照片选择器 → 裁切 → 保存。
                pickImage.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onDismiss = { showAvatarSourcePicker = false }
        )
    }

    if (showDefaultAvatarPicker) {
        DefaultAvatarPickerDialog(
            savingResId = savingDefaultAvatar,
            onPick = onPickDefaultAvatar,
            onDismiss = { showDefaultAvatarPicker = false }
        )
    }
}

/**
 * 头像来源选择弹窗（本轮新增）：点「头像」行后先问要用哪种头像。
 *
 * 两个选项**各占一整行**（自绘的可点 Row，见 [AvatarSourceOption]），而不是塞进
 * `confirmButton` / `dismissButton` 那两个槽位：那两个槽位是"确认 / 取消"的语义
 * （右对齐、带轻重之分、且宽度只有一半），而这里两条路是**平级**的、谁也不比谁更"确定"。
 * 放进 `text` 槽位后两行都是通栏可点，点哪行走哪条路。
 *
 * 底部保留一个「取消」：与 [DefaultAvatarPickerDialog] 的处理**完全一致**——
 * 两个弹窗是前后脚出现的（选了「使用默认头像」就弹起后者），底部的按钮样式若一个有一个没有，
 * 用户会觉得第二个弹窗"多了个东西"。放一个显式的取消也比只靠点空白处更好发现。
 */
@Composable
private fun AvatarSourceDialog(
    onPickDefault: () -> Unit,
    onPickGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改头像") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 每行：图标 + 文案，通栏可点，与「我的」页那些功能行的排法一致。
                AvatarSourceOption(
                    icon = Icons.Filled.Face,
                    label = "使用默认头像",
                    description = "从内置头像里挑一个，不用上传照片",
                    onClick = onPickDefault
                )
                AvatarSourceOption(
                    icon = Icons.Filled.PhotoLibrary,
                    label = "从相册选择",
                    description = "选一张照片并裁成正方形",
                    onClick = onPickGallery
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 来源弹窗里的一行。
 *
 * 左图标 + 右两行文字（标题 / 说明），整行可点。说明文字不是装饰：
 * 「默认头像」与「从相册选择」的区别恰恰在于"要不要上传照片"，
 * 只给两个词的话用户得点进去才知道差别。
 */
@Composable
private fun AvatarSourceOption(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable。
            // `pressScale` 在前、`clip` 在后：缩放层内侧裁剪，圆角不在中间帧上劣化。
            .pressScale(onClick = onClick)
            // 圆角 + 足够大的行高：弹窗里的可点目标不宜贴边，点起来才不费劲。
            .clip(RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 本页的主体内容。
 *
 * 从 [PersonalInfoScreen] 里拆出来，是为了让「显示裁切页 / 显示本页」在顶层就是
 * 一个干净的二分（见上面的 if/else），而不必把整页内容都缩进一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(
    sessionExpired: Boolean,
    maskedPhone: String?,
    nickname: String?,
    userId: String?,
    genderLabel: String?,
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    /** 进入「同步管理」页（需求 7：从「我的」首页搬来）。 */
    onOpenSyncManage: () -> Unit,
    /** 进入「登录密码」页（2026-11-03 密码登录配套），与外层同名形参透传。 */
    onOpenSetPassword: () -> Unit,
    avatarBitmap: Bitmap?,
    /** 点「头像」行：打开来源选择弹窗（默认头像 / 相册），见 [AvatarSourceDialog]。 */
    onPickAvatar: () -> Unit,
    showLogoutConfirm: Boolean,
    onRequestLogout: () -> Unit,
    onDismissLogout: () -> Unit,
    onConfirmLogout: () -> Unit
) {
    val loggedIn = maskedPhone != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的信息") },
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
        ) {
            // ---- 分组标题 ----
            if (sessionExpired) {
                Text(
                    text = "登录已过期，请重新登录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp)
                )
            }
            Text(
                text = "基础资料",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column {
                    // 头像：**唯一**可点的一行，点击弹来源选择（默认头像 / 相册）。
                    //
                    // 上一版这里下面还跟着一行「选择默认头像」，两行并排让"换头像"这件事
                    // 被拆成两个互不知情的入口；现在合并成一个入口，取舍见文件头 KDoc。
                    InfoRow(
                        label = "头像",
                        // 过期态下资料不可信，头像也不可换：先重新登录。
                        onClick = if (sessionExpired) null else onPickAvatar,
                        showArrow = !sessionExpired,
                        trailing = {
                            AvatarThumb(bitmap = avatarBitmap)
                        }
                    )
                    // 昵称 / 用户ID / 手机号 / 性别：只读、无箭头、不进任何二级页。
                    InfoRow(
                        label = "昵称",
                        value = nickname ?: "未填写"
                    )
                    // 用户 ID：注册时生成的 8 位数字。按需求**不可点击**，因此没有箭头。
                    InfoRow(
                        label = "用户ID",
                        value = userId ?: "未填写"
                    )
                    InfoRow(
                        label = "手机号",
                        // 注册或登录后写入登录态，这里显示脱敏号码。
                        value = maskedPhone ?: "未填写"
                    )
                    InfoRow(
                        label = "性别",
                        value = genderLabel ?: "未知"
                    )
                }
            }

            // ---- 账号与同步（2026-10-22 需求 7）------------------------------
            //
            // 「同步管理」原先挂在「我的」首页；用户要求它同时出现在
            // **个人信息页**与**设置页**，于是这里放一份入口（两处指向同一个二级页，
            // 状态仍只有一份，不存在"两个同步入口各管一套"的问题）。
            // 位置紧跟「基础资料」，因为它管的是这个账号的数据。
            Spacer(Modifier.height(12.dp))
            Text(
                text = "账号与同步",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp)
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                InfoRow(
                    label = "同步管理",
                    value = if (loggedIn) "云端备份与多端同步" else "登录后可用",
                    onClick = onOpenSyncManage,
                    showArrow = true
                )
                // 登录密码（2026-11-03 密码登录配套）：设置后可用手机号+密码登录。
                // 未登录时只展示「登录后可用」、不可点——设密码需要会话鉴权。
                InfoRow(
                    label = "登录密码",
                    value = if (loggedIn) "设置或修改密码" else "登录后可用",
                    onClick = if (loggedIn) onOpenSetPassword else null,
                    showArrow = true
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- 底部按钮：未登录「注册 / 登录」，已登录「退出登录」----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                // 已登录时「退出登录」是**小按钮**：宽度由内容决定、整体居中；
                // 未登录的「注册 / 登录」仍是通栏主按钮（它是这一页的主要动作）。
                contentAlignment = Alignment.Center
            ) {
                if (loggedIn) {
                    // 退出是**破坏性 / 次要操作**，所以用低饱和的实底 + onSurfaceVariant 文字、
                    // 且尺寸收成小按钮，与「注册 / 登录」的主题色通栏主按钮拉开层级，避免误触。
                    Button(
                        // 需求：退出登录要先二次确认。误触退出会清掉登录态，属于不可逆操作。
                        onClick = onRequestLogout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("退出登录", fontSize = 14.sp)
                    }
                } else {
                    Button(
                        onClick = onOpenLogin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text("注册 / 登录", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    // 二次确认放在页面最外层（不在上面的 Box 里）：
    // 弹窗是独立窗口，放在 Box 里虽然也能显示，但会跟着 `Spacer(weight(1f))` 的布局一起被裁进
    // 那个居中的小容器，语义上也不属于「底部按钮区」。
    if (showLogoutConfirm) {
        ConfirmDialog(
            title = "退出登录",
            text = "退出后需要重新登录才能使用账号相关功能。确定要退出吗？",
            confirmLabel = "退出登录",
            dismissLabel = "取消",
            onConfirm = onConfirmLogout,
            onDismiss = onDismissLogout
        )
    }
}

/**
 * 性别标识 → 界面文案。
 *
 * 存的是 `male` / `female` 这类**与界面无关的标识**（数据层不该反向依赖界面），
 * 所以翻译放在这里；未知值一律回落到 null，由调用方显示「未知」。
 */
private fun genderLabelOf(gender: String?): String? = when (gender) {
    "male" -> "男"
    "female" -> "女"
    else -> null
}

/**
 * 行内资料项：左字段名 + 右取值。
 *
 * [onClick] 为 null 时这一行是**只读**的：不挂 `clickable`、也没有右箭头，
 * 不给用户"看起来能点"的错觉。只有头像行传了 [onClick]。
 *
 * 各字段的默认值（灰色头像 / 未填写 / 未知）都由调用方传入，本组件只负责排版。
 */
@Composable
private fun InfoRow(
    label: String,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    // v1.1.72：统一按压反馈（缩放 0.96 + 变淡），替换裸 clickable。
                    Modifier.pressScale(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(
                text = value,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showArrow) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * 头像缩略图：有图显示图，没图回落到灰色占位。
 *
 * 用 `clip(CircleShape)` + `ContentScale.Crop` 保证任何比例的图都填满圆形、不变形
 * （裁切页已经保证存下来的是正方形，这里再兜一层是为了防止文件被外部替换）。
 */
@Composable
private fun AvatarThumb(bitmap: Bitmap?) {
    // 位图若已被回收（例如系统在低内存下回收了上一张，而状态还没更新），
    // `asImageBitmap()` 会抛异常。这里兜一层，宁可回落到灰色占位也不要让页面崩掉。
    val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }
    if (image == null) {
        DefaultAvatar()
        return
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
    ) {
        Image(
            bitmap = image,
            contentDescription = "头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** 默认头像：中性灰圆底 + 人形图标（跟随深浅色，深色下自动变暗）。 */
@Composable
private fun DefaultAvatar() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = "头像",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(7.dp)
        )
    }
}

/**
 * 「选择默认头像」对话框：内置头像的网格选择器（v1.1.69）。
 *
 * ## 为什么默认头像走「落盘」而不是记一个枚举
 *
 * 内置图选中后经 [decodeDefaultAvatarBitmap] → [AvatarStore.saveAvatar] 写成**用户自己的
 * 头像文件**，而不是在设置里记「当前用第几个内置头像」。这样做的关键好处是
 * **单一数据源**：界面只认 `avatarPath` 一个事实来源，「当前头像是什么」永远有且只有
 * 一个答案。若改成枚举，就会出现两条并存的路径（文件 + 序号），任何按其中一条
 * 读取的界面都可能读到过时的那条——例如"上传了照片但枚举还指向 3 号头像"。
 * 代价是每次选默认头像都要重写一次文件（几百 KB），可接受。
 *
 * ## 交互细节
 *
 * - 网格 3 列：8 张图两行多，3 列在竖屏上的单元格约 96dp，够看清又不至于一屏放不下；
 * - 选中态用主题色描边圆环，不用填充——填充会盖住图片本身；
 * - [savingResId] 对应的那格显示「保存中」：落盘是 IO 操作，用户点完到界面刷新有
 *   几十毫秒，给个就地反馈避免连点；
 * - 每格都是 `CircleShape` + Crop：与界面上的头像显示形态一致，避免"选的时候是方的、
 *   存完变成圆的"的预期落差。
 *
 * ## 为什么用「Column + chunked」而不是 LazyVerticalGrid
 *
 * 只有 8 张固定图，**根本不需要懒加载**：全部常驻组合的成本是 8 个 96dp 的 Image，
 * 一次性解码完就结束，没有滚动性能问题。而懒网格在 `AlertDialog` 里反而要处理
 * 嵌套滚动的高度测量（Dialog 的 text 槽位高度是包住内容的，懒布局的无限高度约束
 * 在这里会触发测量异常）。与 `YearOverviewScreen` 对 12 个月块的处理同一口径：
 * 少量固定块用普通布局，别为"将来可能很多"提前引入懒加载的复杂度。
 */
@Composable
private fun DefaultAvatarPickerDialog(
    savingResId: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认头像") },
        text = {
            // chunked(3) 逐行铺开：8 张 = 3 + 3 + 2，最后一行两个居左，
            // 与宫格（`LabScreen`）的补空位逻辑一致，不需要额外处理。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DefaultAvatarResIds.chunked(AvatarGridColumns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { resId ->
                            DefaultAvatarCell(
                                resId = resId,
                                saving = savingResId == resId,
                                onClick = { onPick(resId) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 补空位：最后一行不足 3 个时用等宽占位，保证三列宽度一致。
                        repeat(AvatarGridColumns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}



/**
 * 头像**内容**的进程内版本号（本轮新增）。
 *
 * ## 为什么光订阅 `avatarPath` 不够
 *
 * 头像图片永远写在同一个文件（`filesDir/avatar/avatar.jpg`，见 [AvatarStore.avatarFile]），
 * 因此**除了第一次设置**，之后每一次换头像（换内置头像、重新裁一张相册图）写进 DataStore 的
 * `avatarPath` 都是**同一个字符串**。而界面侧读到它时还会被逐层去重：`collectAsState` 写进的是
 * 结构相等即不触发重组的快照状态，`LaunchedEffect(avatarPath)` 的 key 也一样判等——
 * 于是两页上的解码 effect 根本不会重启：图片文件已经是新的，界面却还挂着上一张 Bitmap，
 * 用户看到的就是「换完头像界面没反应」。
 *
 * 修法有两条：把版本号塞进 DataStore（要改 `SettingsRepository` 与它的键表，
 * 超出本次允许改动的文件范围），或者在进程内记一个"内容变过"的信号。
 * 这里选后者：它是**纯界面层的刷新通知**，不参与任何持久化语义——
 * 进程重建后各页会重新订阅并重新解码，本来也不需要这个数字活过进程。
 *
 * 用法：写盘成功后 [bump] 一次；读取方把它和 `avatarPath` 一起放进 `LaunchedEffect` 的 key：
 * [PersonalInfoScreen] 与 [MyScreen] 两页都是这么做的，因此换头像后两页会同步刷新。
 */
internal object AvatarRevision {
    /** 当前版本号；每次头像文件被覆盖就自增。 */
    val value: MutableStateFlow<Int> = MutableStateFlow(0)

    /** 头像文件已被覆盖，通知所有正在显示头像的页面重新解码。 */
    fun bump() {
        value.value++
    }
}
