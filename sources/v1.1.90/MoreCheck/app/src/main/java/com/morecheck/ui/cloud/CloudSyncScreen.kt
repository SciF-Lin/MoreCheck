package com.morecheck.ui.cloud

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.CloudSyncRepository
import com.morecheck.data.cloud.SyncState
import com.morecheck.di.appContainer
import com.morecheck.ui.common.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 「上次同步」的时间格式（显示到分钟足够，同步是低频事件）。 */
private val SyncTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 同步管理页（v3 云同步）。
 *
 * 云账号在当前设备上的**同步控制台**，**按登录态二态显示**：
 *
 * - **未登录**：一句说明 + 「去登录」引导（→ [onOpenLogin]）。登录入口统一收敛到
 *   登录页（真实短信验证码），本页不出现第二个登录表单——两个入口两个表单只会让
 *   「到底在哪登录」变得含糊。
 * - **已登录**：
 *   1. **账号卡**：云账号 + 退出登录（二次确认）。退出只清云会话，**不删任何本地数据**；
 *      同时重置"首次登录已问过"标志，下次登录会重新弹合并策略窗（换账号时云端可能是
 *      另一份数据）。
 *   2. **同步内容卡**：结构化轨道覆盖的数据与整包备份轨道的说明——用户有权利知道
 *      "同步"到底同步了什么，这比把范围藏起来更可信。
 *   3. **自动同步卡**：两个开关——每日自动同步（总开关，门控每日 Worker）、
 *      打卡后轻量推送（可单独关）。都是即拨即存（DataStore），无需保存按钮。
 *   4. **手动同步卡**：「立即同步」按钮 + 上次同步结果（时间、成败、统计/原因）。
 *      手动同步走完整双轨（先推后拉），与每日 Worker 是同一段代码
 *      （[CloudSyncRepository.syncNow]）。
 *
 * 反馈走全局 Snackbar（[onMessage]），与设置页同一通道，不再自造 toast。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    onBack: () -> Unit,
    /** 「去登录」：未登录时引导卡上的按钮（→ 登录页）。 */
    onOpenLogin: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val container = remember(context) { context.appContainer }
    val scope = rememberCoroutineScope()

    val session by container.cloudAuthClient.session.collectAsStateWithLifecycle()
    val syncState by container.cloudSyncRepository.state.collectAsStateWithLifecycle(
        initialValue = null
    )

    // 退出登录的确认框（一次性中间状态，转屏消失是对的——理由同 BackupScreen 的 confirmClear）。
    var confirmSignOut by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步管理") },
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
            if (session == null) {
                // ---- 未登录：引导卡 ------------------------------------------------
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "还没登录云账号",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "登录后，任务、打卡记录、记事本文字、应用设置与游戏存档会" +
                                "自动与云端同步，换机不丢。",
                            style = MaterialTheme.typography.bodySmall,
                            // 2026-10-22 需求：本页正文/说明文字整体加深一档（原来是 onSurfaceVariant，
                // 浅色主题下偏灰、看着像禁用态）。取 onSurface 的 82% 仍保留主次关系。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onOpenLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("去登录")
                        }
                    }
                }
            } else {
                // ---- 账号卡 --------------------------------------------------------
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "云账号",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = session?.phone ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            // 2026-10-22 需求：本页正文/说明文字整体加深一档（原来是 onSurfaceVariant，
                // 浅色主题下偏灰、看着像禁用态）。取 onSurface 的 82% 仍保留主次关系。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = { confirmSignOut = true },
                            enabled = !signingOut
                        ) {
                            if (signingOut) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(Icons.Filled.Logout, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("退出登录")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 同步内容 ------------------------------------------------------
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "同步内容",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        SyncItemRow(Icons.Filled.EventNote, "任务与打卡记录", "自动双向同步，按修改时间合并")
                        SyncItemRow(Icons.Filled.Tune, "记事本文字与应用设置", "自动双向同步，本机未上传的改动不会被云端覆盖")
                        SyncItemRow(Icons.Filled.VideogameAsset, "游戏存档", "贪吃蛇、番茄时钟与两个数独的成绩与进度")
                        SyncItemRow(Icons.Filled.CloudDone, "整包备份", "在「数据备份」页手动备份 / 恢复（含记事本图片）")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 自动同步开关 --------------------------------------------------
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "自动同步",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        val state = syncState
                        SyncSwitchRow(
                            title = "每日自动同步",
                            subtitle = "每天自动把数据与云端双向同步一次",
                            checked = state?.autoSyncEnabled ?: true,
                            enabled = state != null,
                            onCheckedChange = { enabled ->
                                scope.launch { container.syncStateStore.setAutoSyncEnabled(enabled) }
                            }
                        )
                        SyncSwitchRow(
                            title = "打卡后推送",
                            subtitle = "每次打卡成功后把变更轻量推送到云端（不拉取，可单独关闭）",
                            checked = state?.syncAfterCheckIn ?: true,
                            enabled = state != null,
                            onCheckedChange = { enabled ->
                                scope.launch { container.syncStateStore.setSyncAfterCheckIn(enabled) }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- 手动同步 ------------------------------------------------------
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "手动同步",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = lastSyncLine(syncState),
                            style = MaterialTheme.typography.bodySmall,
                            // 2026-10-22 需求：本页正文/说明文字整体加深一档（原来是 onSurfaceVariant，
                // 浅色主题下偏灰、看着像禁用态）。取 onSurface 的 82% 仍保留主次关系。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                syncing = true
                                scope.launch {
                                    // syncNow 自己把一切失败翻译成 SyncReport（不抛业务异常），
                                    // 这里只兜真正的编程错误；结果同时已写入同步状态，
                                    // 上面的「上次同步」行会自动刷新。
                                    val report = try {
                                        container.cloudSyncRepository.syncNow()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        null
                                    }
                                    onMessage(report?.message ?: "同步出现问题，请稍后重试")
                                    syncing = false
                                }
                            },
                            enabled = !syncing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (syncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("立即同步")
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = "退出云账号",
            text = "退出后本机数据全部保留，云端数据也不会删除；只是停止自动同步。" +
                "下次登录云账号时会重新询问数据合并方式。",
            // 确认按钮复述动作本身（与备份页清空同一口径）。
            confirmLabel = "退出登录",
            onConfirm = {
                confirmSignOut = false
                signingOut = true
                scope.launch {
                    try {
                        container.cloudAuthClient.signOut()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CloudException) {
                        // 服务端登出失败不影响本地退出：会话已在 signOut 内部清掉，
                        // 提醒一句即可，不必让用户卡在这里。
                        onMessage("已退出登录（云端会话注销失败：${e.message ?: "未知错误"}）")
                    }
                    // 重置「首次登录已问过」：换账号登录时云端可能是另一份数据。
                    container.syncStateStore.setFirstLoginHandled(false)
                    signingOut = false
                    onMessage("已退出云账号")
                }
            },
            onDismiss = { confirmSignOut = false }
        )
    }
}

/** 「上次同步」一行的文案。 */
private fun lastSyncLine(state: SyncState?): String {
    if (state == null) return "读取同步状态中…"
    val at = state.lastSyncAt ?: return "本设备还没有同步过。"
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(SyncTimeFormatter)
    val mark = if (state.lastSyncOk == true) "✓" else "✗"
    return "上次同步：$time $mark ${state.lastSyncMessage ?: ""}"
}

/** 同步内容卡里的一行（图标 + 名称 + 说明）。 */
@Composable
private fun SyncItemRow(icon: ImageVector, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                // 2026-10-22 需求：本页正文/说明文字整体加深一档（原来是 onSurfaceVariant，
                // 浅色主题下偏灰、看着像禁用态）。取 onSurface 的 82% 仍保留主次关系。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
        }
    }
}

/** 自动同步卡里的一行开关（标题 + 说明 + Switch）。 */
@Composable
private fun SyncSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                // 2026-10-22 需求：本页正文/说明文字整体加深一档（原来是 onSurfaceVariant，
                // 浅色主题下偏灰、看着像禁用态）。取 onSurface 的 82% 仍保留主次关系。
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
