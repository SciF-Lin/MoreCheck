package com.morecheck.ui.mine

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 时钟刷新间隔：每秒一跳，保证秒位准点变化。 */
private const val CLOCK_TICK_MS = 1_000L

/**
 * 「我的」一级入口（任务3）。
 *
 * 结构自下而上：
 * 1. 顶部当前时间 `HH:mm:ss`（24 小时制，精确到秒，每秒刷新）；
 * 2. 头像区——**有头像显示头像、没有（或路径失效 / 解码失败）显示灰色人形占位**，
 *    点击进入「个人信息」；
 * 3. 功能列表——任务管理 / 打卡记录 / 设置 / 数据备份 / 关于我们。
 *
 * 「帮助与反馈」已移到「关于我们」页的标题栏右侧（扳手图标），不再出现在本列表。
 *
 * 标题栏已移入本页（不再由全局骨架绘制），避免进入二级页时骨架重排造成界面割裂。
 * 底部的「主页 / 日历 / 发现 / 我的」仍由全局骨架提供。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    onOpenPersonalInfo: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    maskedPhone: String?
) {
    // ── 头像：订阅 DataStore 里的路径 + 解码成位图 ──
    //
    // 与「个人信息」页（PersonalInfoScreen）完全同一套做法，不另造轮子：
    // 直接用 AppContainer 里的 `SettingsRepository`，免得为「读一个路径」去扩
    // SettingsViewModel / AppViewModelProvider——那会牵动本任务范围之外的文件。
    val context = LocalContext.current
    val settingsRepository = remember(context) {
        context.appContainer.settingsRepository
    }
    val avatarPath by settingsRepository.avatarPath
        .collectAsStateWithLifecycle(initialValue = null)

    // 头像**内容**的版本号：换头像写的是同一个文件、同一个路径，
    // `avatarPath` 那一侧感知不到"文件被覆盖了"，必须靠它触发重新解码。
    // 它是同包（`ui.mine`）里的内部对象，定义见 PersonalInfoScreen.kt。
    val avatarRevision by AvatarRevision.value.collectAsStateWithLifecycle()

    // 解码结果；null 一律表示「没有可显示的头像」，由 [ProfileAvatar] 回落到灰色占位。
    //
    // 为什么不就地 `remember(avatarPath) { decodeStored(path) }`：`remember` 的 lambda 跑在
    // 主线程，而 `decodeStored` 要读磁盘（还带两遍法解码），每次换头像都会卡一下首帧。
    // 放到 IO 线程后，主线程不再有磁盘 IO；同时状态只在 `avatarPath` 变化时才重算，
    // 其余重组（例如右上角时钟每秒跳一次）复用同一个 Bitmap，不会反复解码。
    //
    // ★ 本轮改造：key 里加上 [AvatarRevision]。
    //
    // 头像文件永远是**同一个路径**（`AvatarStore.avatarFile` 固定为 `avatar/avatar.jpg`），
    // 所以第二次之后的每一次换头像，写进 DataStore 的 `avatarPath` 与上一次完全相同。
    // 界面侧对这个字符串是会去重的（`collectAsState` 与 `LaunchedEffect` 的 key 都按值判等），
    // 光靠 `avatarPath` 这个 effect 根本不会重启——文件已经是新的，界面却还挂着旧 Bitmap。
    // 本页与「我的信息」页都订阅同一个版本号，于是"在任何一页换头像，两页都同步更新"。
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(avatarPath, avatarRevision) {
        val path = avatarPath
        avatarBitmap = if (path == null) {
            // 路径为空要立刻清掉上一张：否则「清空头像」后旧图会一直挂在界面上。
            null
        } else {
            // 文件被删 / 损坏时 `decodeStored` 自己返回 null（它内部已判文件存在性与文件头），
            // 这里不必再兜一层 try/catch。
            withContext(Dispatchers.IO) { AvatarStore.decodeStored(path) }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) }
    ) { padding ->
        // 2026-10-22 需求 7：「同步管理」不再挂在「我的」首页。
        // 本页因此**不再订阅**云会话 / 同步状态——那两份订阅存在的唯一理由就是
        // 给这张卡片的副标题写"上次同步 xx"，入口搬走后留着只是空转。
        // 数据备份同理（需求 8：搬进「设置」）。
        MyContent(
            modifier = Modifier.padding(padding),
            onOpenPersonalInfo = onOpenPersonalInfo,
            onOpenTasks = onOpenTasks,
            onOpenRecords = onOpenRecords,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            maskedPhone = maskedPhone,
            avatarBitmap = avatarBitmap
        )
    }
}

@Composable
private fun MyContent(
    modifier: Modifier,
    onOpenPersonalInfo: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRecords: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    maskedPhone: String?,
    avatarBitmap: Bitmap?
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        CurrentTimeHeader()
        Spacer(Modifier.height(12.dp))
        ProfileCard(
            maskedPhone = maskedPhone,
            avatarBitmap = avatarBitmap,
            onClick = onOpenPersonalInfo
        )
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column {
                val entries = listOf(
                    MineEntry("tasks", "任务管理", "新建 / 编辑 / 删除任务与每日提醒", Icons.Filled.Checklist, onOpenTasks),
                    MineEntry("records", "打卡记录", "热力图与历史时间线", Icons.Filled.History, onOpenRecords),
                    MineEntry("settings", "设置", "深色模式与主题色", Icons.Filled.Settings, onOpenSettings)
                )
                entries.forEachIndexed { index, entry ->
                    MineRow(entry)
                    if (index != entries.lastIndex) MineDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            MineRow(
                MineEntry("about", "关于我们", "版本号、用户协议与隐私政策", Icons.Filled.Info, onOpenAbout)
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 顶部当前时间。
 *
 * 用 `LaunchedEffect` + `delay(1s)` 每秒重算 `LocalTime.now()`；
 * 每到整秒会重新赋值 state 从而触发重组，时间随之跳动。
 */
@Composable
private fun CurrentTimeHeader() {
    var now by remember { mutableStateOf(LocalTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(CLOCK_TICK_MS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = now.format(ClockFormatter),
            // 等宽字体：秒位跳动时数字宽度一致，不会左右抖动。
            fontFamily = FontFamily.Monospace,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "当前时间",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 顶部头像卡片：头像 + 登录态 + 右箭头。
 *
 * 头像本身**不是**单独一行的可点区域，而是整张卡片一起响应点击进入「个人信息」，
 * 与改造前一致（需求：跳转逻辑不要动）。
 *
 * @param avatarBitmap 已解码的头像；null 表示无头像 / 解码失败，由 [ProfileAvatar] 显示灰色占位。
 */
@Composable
private fun ProfileCard(
    maskedPhone: String?,
    avatarBitmap: Bitmap?,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // v1.1.72：统一按压反馈（同 MineRow），替换裸 clickable 的默认水波纹。
                .pressScale(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProfileAvatar(bitmap = avatarBitmap)

            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = if (maskedPhone == null) "未登录" else "已登录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // 正文黑加深（仅本页与「发现」页）。
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = maskedPhone ?: "点击查看个人信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「我的」页的头像：**有图显示图，没图显示灰色人形占位**（需求原话：
 * 「有头像时显示头像，没有时采用灰色头像替代」）。
 *
 * 占位刻意用「圆底 + 人形图标」而不是文字首字母：
 * 未登录时首字母是空的、已登录时手机号又被脱敏（`138****0000`），
 * 拿它当头像文字只会显示成 `1` 或 `*`，反而像渲染出错；
 * 人形图标在「有 / 无登录态」两种情况下语义都成立。
 *
 * @param bitmap null = 从未设置过头像；也复用同一条回落路径来表示「路径失效 / 解码失败」，
 *   所以下面不需要区分这两种情况——对用户来说结果都是「没有头像」。
 */
@Composable
private fun ProfileAvatar(bitmap: Bitmap?) {
    // 位图可能已被系统回收（低内存下回收上一张，而状态还没来得及更新），
    // 此时 `asImageBitmap()` 会抛异常。兜一层：宁可回落到灰色占位，也不要让「我的」页崩掉。
    // 这一层与「个人信息」页的头像缩略图写法一致。
    val image = bitmap?.let { runCatching { it.asImageBitmap() }.getOrNull() }

    if (image == null) {
        // 灰色占位：颜色全部取自主题，没有新增颜色常量。
        // `surfaceContainerHighest` 在浅色下是浅灰、深色下自动变暗，两套主题都成立；
        // 与「个人信息」页的 `DefaultAvatar` 取的是同一个色，两处占位长得一样。
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(60.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "头像",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                // 60dp 圆内留 12dp 边距 → 图标 36dp，与改造前占位图的视觉重量一致。
                modifier = Modifier.padding(12.dp)
            )
        }
        return
    }

    // 有头像：裁成圆形。
    //
    // 用 `clip(CircleShape)` + `ContentScale.Crop` 而不是直接把图塞进圆角容器：
    // 裁切页虽然保证导出的是正方形，但文件在外部被替换 / 是历史遗留数据时可能不是，
    // Crop 能保证任何比例的图都填满圆形且不变形。
    Box(
        modifier = Modifier
            .size(60.dp)
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

private data class MineEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun MineRow(entry: MineEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v1.1.72：改用全局统一的按压反馈（缩放 0.96 + 变淡），替换原来的裸
            // `clickable`（默认水波纹）。理由见 `ui/common/PressScale.kt` 的 KDoc：
            // 水波纹在接近白色的容器上极淡，表现为"按下去没反应"。
            //
            // ⚠️ 顺序约束：`pressScale` 内含 `graphicsLayer`，若调用方要裁剪必须放在它之后
            // （缩放层内侧），否则圆角在中间帧上会退化成多边形。本行不裁剪，故无此虑。
            .pressScale(onClick = entry.onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                // 正文黑加深（仅本页与「发现」页）。
                color = MaterialTheme.strongTextColor
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MineDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
