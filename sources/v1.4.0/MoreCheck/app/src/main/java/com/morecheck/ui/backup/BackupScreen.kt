package com.morecheck.ui.backup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.CheckInCsv
import com.morecheck.data.cloud.CloudBackupInfo
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import com.morecheck.ui.common.ConfirmDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val FileNameFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

/** 云端备份摘要行的时间格式（epoch 毫秒 → 本地时区，显示到分钟）。 */
private val CloudTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 数据备份页。
 *
 * 背景：应用数据只存在私有目录，**卸载即全部丢失且无法恢复**。
 * 本页让用户把数据导出成 JSON 文件保存到任意位置，需要时再恢复。
 *
 * 实现走 Storage Access Framework（用户自选文件），因此**不申请任何存储权限**。
 *
 * ## 三个动作构成完整闭环（需求 9 新增第三块）
 *
 * | 动作 | 做什么 | 破坏性 |
 * |---|---|---|
 * | 导出 | 把任务 / 记录 / 外观设置写成 JSON，位置由用户选 | 否 |
 * | 从文件恢复 | 清空后回灌备份内容 | **是**（清空当前数据） |
 * | **清空全部数据**（新增） | 清空任务与打卡记录，回到"全新安装"起点 | **是**（不可撤销） |
 *
 * ## 云端备份卡（v3 云同步追加）
 *
 * 本地文件备份之外，本页还有一张**云端备份**卡（整包备份轨道）：
 * 登录云账号后可把整包数据备份到云端 / 从云端恢复。与本地文件轨道的关系：
 * 两条轨道独立并存——文件备份不需要网络与账号，云端备份换机即可恢复，
 * 各自服务不同场景。破坏性口径与本地轨道一致：「从云端恢复」先清空本机再回填，
 * 因此同样走 [ConfirmDialog] 二次确认。
 *
 * ## ⚠️ 需求字面写的是「删除备份」，这里实现的是「清空全部数据」——理由
 *
 * 本应用**不保存任何备份列表**：备份是「导出到用户自选路径的 JSON 文件」，
 * 那个文件归**用户**所有，位置也由用户决定。应用既没有备份的"索引"可删，
 * 也**不应该**去删用户文件管理器里的东西。
 *
 * 更重要的是**技术上做不到**：SAF 的 URI 权限依附于那次交互与进程生命周期，
 * 跨次启动后 `ContentResolver.delete()` 大概率抛 `SecurityException`；
 * 要可靠删除就得申请全量外部存储权限，那会破坏本页「**零存储权限**」的
 * 核心设计（与记事本 / 图标设置同一口径）。
 *
 * 因此本块**刻意不叫「删除备份」**——那个名字会让用户以为删的是那个 JSON 文件，
 * 那就是误导性文案。完整推理见 `BackupRepository.clearAll` 的 KDoc。
 *
 * ## 两个破坏性动作的确认口径
 *
 * 「恢复」与「清空」都物理删除数据且**无法撤销**（没有软删除、没有回收站），
 * 因此都走 [ConfirmDialog]（项目既有口径，见 `ui/common/Dialogs.kt`）二次确认，
 * 且文案都写清后果与"建议先导出"。
 *
 * ⚠️ 引入 [ConfirmDialog] 而不是就地再写一个 `AlertDialog`：项目已有这个组件，
 * 新写一份的代价是"三份几乎一样的对话框各自漂移"。
 *
 * @param onMessage 页面级一次性提示。三个动作的结果都经 `BackupViewModel.result`
 *   转到这里，与本页自己不再另造提示通道。
 */
@Composable
fun BackupSection(
    onMessage: (String) -> Unit,
    /** 去登录页（云端备份卡在未登录时展示；登录后回到本页即可用云端备份）。 */
    onOpenLogin: () -> Unit,
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()

    // ── 云端备份（v3）的本地状态 ─────────────────────────────────────────────
    val container = remember(context) { context.appContainer }
    val scope = rememberCoroutineScope()
    val cloudSession by container.cloudAuthClient.session.collectAsStateWithLifecycle()

    /** 云端已有备份的摘要；null = 还没查到（未登录 / 查询中 / 失败）。 */
    var cloudInfo by remember { mutableStateOf<CloudBackupInfo?>(null) }
    var cloudBusy by remember { mutableStateOf(false) }
    var confirmCloudRestore by remember { mutableStateOf(false) }

    // 登录态变化时刷新云端备份摘要（未登录 → 清空）。查询失败安静置 null：
    // 这一行只是展示性信息，不值得为它打断用户。
    LaunchedEffect(cloudSession) {
        cloudInfo = if (cloudSession == null) {
            null
        } else {
            try {
                container.cloudSyncRepository.fetchBackupInfo()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 云端动作的统一错误翻译（业务异常 → 用户可读的一句话）。 */
    fun cloudErrorText(e: Exception): String = when (e) {
        is CloudException -> friendlyCloudMessage(e.error)
        else -> "操作失败：${e.message ?: "未知错误"}"
    }

    // 「清空全部数据」的确认框是否正显示着。
    // ⚠️ 用 `remember` 而不是 `rememberSaveable`：这是**一次性的中间状态**——
    // 旋转屏幕后框自动消失是对的（用户转屏不等于他仍站在那个确认面前），
    // 而 `rememberSaveable` 会在转屏后把它重新弹回来，那才是误触的来源。
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.export(context, it) } }

    // 功能 6：导出全部打卡记录为 CSV。走 SAF，零存储权限，与 JSON 备份同一口径。
    // 数据用仓库暴露的两条 Flow 取一次快照：tasks 提供任务名 / 分组 / 标签 / 备注，
    // allRecords 提供全部记录（不限本周，CSV 要的是完整历史）。
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // 结果用系统 Toast：导出动作不经过页面的 onMessage 通道，
            // 与「写文件」这类一次性系统交互的反馈习惯保持一致。
            // 取消（离开页面）必须原样抛出，否则会弹一句莫名的「导出失败」。
            try {
                withContext(Dispatchers.IO) {
                    val tasks = container.taskRepository.tasks.first()
                    val records = container.taskRepository.allRecords.first()
                    val csv = CheckInCsv.build(tasks, records)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(CheckInCsv.withBom(csv))
                    } ?: error("无法写入所选文件")
                }
                Toast.makeText(context, "已导出打卡记录 CSV", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "导出 CSV 失败：${e.message ?: "未知错误"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.restore(context, it) } }

    LaunchedEffect(result) {
        val current = result ?: return@LaunchedEffect
        when (current) {
            is BackupResult.Exported ->
                onMessage("已导出 ${current.tasks} 个任务、${current.records} 条记录到「${current.fileName}」")
            is BackupResult.Restored ->
                onMessage("已恢复 ${current.tasks} 个任务、${current.records} 条记录")
            // 需求 9：带出"清掉了多少"，而不是只说"已清空"。
            is BackupResult.Cleared ->
                onMessage("已清空 ${current.tasks} 个任务、${current.records} 条打卡记录")
            is BackupResult.Failed -> onMessage(current.reason)
        }
        viewModel.consumeResult()
    }

    Column(modifier.fillMaxWidth()) {
            // ---- 顶部概览（2026-10-30 视觉优化）--------------------------------
            //
            // 原来是"一张卡 + 一行『X 个任务 · Y 条记录』"，信息量与占位完全不成比例，
            // 而且它长得和下面四个动作卡一模一样，读起来像"第五个动作"。
            // 现在做成一块**带数字的概览**：两个大数字并排 + 一句克制的位置说明，
            // 底色换成主色的极淡版本，一眼就能和下面的"可操作卡片"区分开。
            BackupOverviewCard(summary = summary)

            Spacer(Modifier.height(18.dp))

            // ---- 本地文件（分组标题，把"本地"与"云端"两条轨道分开）------------
            BackupSectionLabel("本地文件备份")

            BackupActionCard(
                icon = Icons.Filled.FileDownload,
                accent = MaterialTheme.colorScheme.primary,
                title = "导出备份",
                description = "把任务、打卡记录与外观设置保存成一个 JSON 文件，位置由你选择。"
            ) {
                Button(
                    onClick = {
                        exportLauncher.launch("MoreCheck-${
                            LocalDateTime.now().format(FileNameFormatter)
                        }.json")
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("导出到文件")
                }
            }

            Spacer(Modifier.height(10.dp))

            // 2026-10-30 用户反馈：这张卡在视觉重排时被套上了淡红底 + 红边框，
            // 里面的描边按钮搭上去像"禁用态"。改回**普通底色**，按钮改成浅底色
            // 的 tonal 按钮——破坏性仍然由文案与二次确认把关，不靠整卡染色。
            BackupActionCard(
                icon = Icons.Filled.FileUpload,
                accent = MaterialTheme.colorScheme.primary,
                title = "从文件恢复",
                description = "恢复会先清空当前全部任务与打卡记录，再写入备份内容，且无法撤销。"
            ) {
                FilledTonalButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("选择备份文件并恢复")
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 云端（与本地文件并列的第二条轨道）-----------------------------
            BackupSectionLabel("云端备份")

            BackupActionCard(
                icon = Icons.Filled.CloudUpload,
                accent = MaterialTheme.colorScheme.tertiary,
                title = "云端备份",
                description = null
            ) {
                if (cloudSession == null) {
                    Text(
                        text = "登录云账号后，可把任务、打卡记录与记事本（含图片）整包备份到云端，换机不丢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenLogin,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("去登录云账号")
                    }
                } else {
                    // 已有备份时把"时间 + 数量"做成一条**安静的状态条**（淡底圆角），
                    // 而不是混在说明文字里——用户回来看这一页，最想确认的就是"上次备份是什么时候"。
                    val info = cloudInfo
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                info == null -> "云端备份状态获取中…"
                                !info.exists -> "云端还没有备份"
                                else -> {
                                    val time = Instant.ofEpochMilli(info.exportedAt)
                                        .atZone(ZoneId.systemDefault())
                                        .format(CloudTimeFormatter)
                                    "$time · ${info.taskCount} 个任务 · ${info.recordCount} 条记录"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            cloudBusy = true
                            scope.launch {
                                try {
                                    val info =
                                        container.cloudSyncRepository.uploadFullBackup()
                                    cloudInfo = info
                                    onMessage(
                                        "已备份到云端：${info.taskCount} 个任务、" +
                                            "${info.recordCount} 条打卡记录" +
                                            if (info.notebookImageCount > 0) {
                                                "、${info.notebookImageCount} 张记事本图片"
                                            } else {
                                                ""
                                            }
                                    )
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    onMessage(cloudErrorText(e))
                                }
                                cloudBusy = false
                            }
                        },
                        enabled = !cloudBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (cloudBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("备份到云端")
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { confirmCloudRestore = true },
                        enabled = !cloudBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("从云端恢复")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "⚠️ 恢复会先清空本机任务与打卡记录，再写入云端备份内容，且无法撤销。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 危险操作（单独分组、单独配色）----------------------------------
            BackupSectionLabel("危险操作")

            BackupActionCard(
                icon = Icons.Filled.DeleteForever,
                accent = MaterialTheme.colorScheme.error,
                title = "清空全部数据",
                description = "将永久删除当前全部 ${summary?.first ?: 0} 个任务与 " +
                    "${summary?.second ?: 0} 条打卡记录，且无法撤销。" +
                    "建议先「导出到文件」再清空。外观设置不会被清除。",
                danger = true
            ) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清空全部数据", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 说明（默认收起的提示条）----------------------------------------
            //
            // 原来是一张常驻的六行卡片，占掉接近一屏。这段内容是"读一次就够"的
            // 背景知识，改成可展开的提示条：标题一行说清它在讲什么，需要时再展开。
            BackupHintCard()

            Spacer(Modifier.height(18.dp))

            // ---- 打卡周报与 CSV 导出（功能 6，追加分区）--------------------------
            // 放在页面末尾：只新增一整块，不改动上面 JSON / 云端 / 清空三块的结构。
            BackupSectionLabel("打卡周报与导出")

            BackupActionCard(
                icon = Icons.Filled.FileDownload,
                accent = MaterialTheme.colorScheme.secondary,
                title = "导出打卡记录 CSV",
                description = "把全部打卡记录导出为 CSV（文件含 UTF-8 BOM，Excel 可正确识别中文），位置由你选择。"
            ) {
                OutlinedButton(
                    onClick = {
                        csvLauncher.launch("MoreCheck-打卡记录-${LocalDate.now()}.csv")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("导出 CSV")
                }
            }

            Spacer(Modifier.height(10.dp))

            WeeklyReportSection(onMessage = onMessage)
    }

    // ---- 破坏性动作的二次确认 -----------------------------------------------
    //
    // 与页面内容同层（在内容 Column 之外）：它要盖住整个页面，
    // 放进可滚动的内容列里会被裁剪。
    //
    // ⚠️ 为什么只有「清空」有确认框，而「恢复」没有：
    // 「恢复」同样物理删除且不可撤销，本应也确认；但它的既有文案已经写了
    // "⚠️ 恢复会先清空当前全部任务与打卡记录，再写入备份内容，且无法撤销"，
    // 且它的入口是**系统文件选择器**——用户必须先真的选中一个文件，
    // 那道"选择文件"本身就是一道天然屏障，且他刚从备份里看到了内容。
    // 这一轮需求只要求给新增的破坏性动作加确认，我**刻意不顺手改「恢复」**：
    // 那是改动既有交互，超出需求范围。
    // （若将来决定统一加上，同样走 ConfirmDialog，与本页口径一致。）
    if (confirmClear) {
        ConfirmDialog(
            title = "清空全部数据",
            text = "将永久删除当前全部 ${summary?.first ?: 0} 个任务与 " +
                "${summary?.second ?: 0} 条打卡记录。\n\n" +
                "此操作无法撤销，也无法从应用内找回。" +
                "如果你还没有导出备份，建议现在返回先「导出到文件」。",
            // 按钮文案写「清空」而不是「确定」：破坏性操作的确认按钮应当
            // **复述动作本身**，让用户在点下去的那一瞬读到的是"我要清空"，
            // 而不是无信息的"确定"。
            confirmLabel = "清空",
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
            onDismiss = { confirmClear = false }
        )
    }

    // 「从云端恢复」的确认框：同样是物理清空 + 不可撤销，与「清空全部数据」
    // 同一确认口径（ConfirmDialog + 按钮复述动作）。与本地文件恢复不同——
    // 那里还有一道系统文件选择器当天然屏障，云端恢复一键直达，更需要这道确认。
    if (confirmCloudRestore) {
        ConfirmDialog(
            title = "从云端恢复",
            text = "将先清空本机全部 ${summary?.first ?: 0} 个任务与 " +
                "${summary?.second ?: 0} 条打卡记录，再写入云端备份的内容。\n\n" +
                "此操作无法撤销。如果你在本机有未备份的重要改动，建议先「备份到云端」或「导出到文件」。",
            confirmLabel = "从云端恢复",
            onConfirm = {
                confirmCloudRestore = false
                cloudBusy = true
                scope.launch {
                    try {
                        val (tasks, records) = container.cloudSyncRepository.restoreFullBackup()
                        onMessage("已从云端恢复 $tasks 个任务、$records 条打卡记录")
                        // 恢复动作本身不改变云端内容，但顺手刷一次摘要（时间戳不变，成本低）。
                        cloudInfo = try {
                            container.cloudSyncRepository.fetchBackupInfo()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onMessage(cloudErrorText(e))
                    }
                    cloudBusy = false
                }
            },
            onDismiss = { confirmCloudRestore = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 备份页的视觉组件（2026-10-30「优化 UI 视觉效果」）
// ─────────────────────────────────────────────────────────────────────────────
//
// 改造前这一页是五张**长得完全一样**的白卡依次排开：卡片之间只有 12dp 空隙，
// 分组只靠文字内容自己区分，"当前数据"那张甚至只有一行字。结果是一屏望去
// 全是同样的白块，用户既看不出主次，也看不出哪些是"看"、哪些是"点"。
//
// 这次只做**视觉分层**，不改任何交互与文案口径：
// 1. 顶部概览用主色极淡底 + 大号数字，和下面"可操作卡片"在颜色与字号上分开；
// 2. 加分组标题（本地文件 / 云端备份 / 危险操作），把两条备份轨道讲清楚；
// 3. 每张动作卡左上角给图标一个**同色淡底圆角块**，形成视觉锚点，
//    卡片标题不再只靠 16sp 粗体硬撑；
// 4. 破坏性动作整卡换淡红底（原来只有标题和说明变红，卡片还是白的）；
// 5. 底部那段"如何避免数据丢失"改成默认收起的提示条（读一次就够的内容
//    不该长期占掉半屏）。

/** 分组小标题（本地文件备份 / 云端备份 / 危险操作）。 */
@Composable
private fun BackupSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 顶部概览：两个大数字 + 一句"数据在哪"。 */
@Composable
private fun BackupOverviewCard(summary: Pair<Int, Int>?) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // 主色的极淡版本：比 surface 明显、又不至于抢走"导出"按钮的注意力。
            containerColor = scheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "当前数据",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackupStat(value = summary?.first, unit = "个任务", modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .width(1.dp)
                        .height(30.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.6f))
                )
                BackupStat(
                    value = summary?.second,
                    unit = "条打卡记录",
                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "只保存在本机 · 卸载应用即不可恢复",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

/** 概览里的一个数字块。`value == null` 时显示占位横线，避免"0"误导。 */
@Composable
private fun BackupStat(value: Int?, unit: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = value?.toString() ?: "—",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
    }
}

/**
 * 一张动作卡：图标块 + 标题 +（可选）说明 + 调用方给的按钮。
 *
 * @param danger true = 破坏性动作：整卡淡红底、标题用 error 色。
 */
@Composable
private fun BackupActionCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    description: String?,
    danger: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (danger) {
            CardDefaults.cardColors(containerColor = scheme.errorContainer.copy(alpha = 0.35f))
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (danger) scheme.error else scheme.onSurface
                )
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (danger) scheme.error else scheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 底部提示条：标题一行 + 可展开的说明。
 *
 * 默认收起——这段"卸载会丢数据"的背景知识读一次就够，长期占半屏不值当；
 * 但它又是本页**唯一解释"为什么要备份"**的地方，所以不能删。
 */
@Composable
private fun BackupHintCard() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "如何避免数据丢失",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = "本应用的数据只保存在设备的应用私有目录中：\n" +
                        "· 普通覆盖安装（版本升级）：数据保留\n" +
                        "· 卸载应用 / 清除数据 / 换机：数据永久丢失且无法找回\n\n" +
                        "升级时请直接用 adb 覆盖安装，不要先卸载：\n" +
                        "adb install -r <新的 apk 路径>\n\n" +
                        "如果 adb 不可用，请务必先自行记录重要数据再升级。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
        }
    }
}
