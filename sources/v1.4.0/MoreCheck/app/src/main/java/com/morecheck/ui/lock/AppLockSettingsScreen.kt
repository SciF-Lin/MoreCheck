package com.morecheck.ui.lock

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.AppLockBioTexts
import com.morecheck.data.AppLockRelock
import com.morecheck.data.SettingsRepository
import com.morecheck.di.appContainer
import com.morecheck.ui.common.AppDropdownItem
import com.morecheck.ui.common.AppDropdownRow
import com.morecheck.ui.theme.strongTextColor
import com.morecheck.util.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「设置 → 安全 → 锁屏密码」二级页。
 *
 * 用词口径（2026-10-26 用户要求）：全应用统一叫**锁屏密码**，不再出现「开屏密码」。
 *
 * 六行 + 一个二级页（指纹解锁，2026-10-29 从本页拆出）：
 * 1. **锁屏密码总开关**——打开前必须先设好手势：开关被拨向开时，若还没有手势，
 *    先进入「设置手势」流程（两步：画一次 → 再画一次确认），完成后才真正启用；
 *    中途取消则开关弹回关。
 * 2. **修改手势**——重走两步流程（换一份盐 + 新哈希，旧手势随之作废）。
 * 3. **指纹解锁**（二级页入口）——指纹总开关与「指纹弹窗文字」四行一起收进
 *    [BiometricSettingsLayer]，本页只留一行入口与状态小结（2026-10-29 用户要求
 *    「把指纹解锁总开关和指纹弹窗文字一起放进二级界面」）。
 * 4. **解锁前显示 Hello World**——冷启动先播一层开屏再出手势锁屏。
 * 5. **输错时震动并变红**——画错时短震 + 提示与轨迹变红。
 * 6. **锁定时机**（2026-10-27 新增）——立即 / 离开 30 秒 / 1 分钟 / 5 分钟 / 仅冷启动，
 *    点开弹窗单选，右侧显示当前档位。默认「仅冷启动」= 改造前行为。
 *
 * ⚠️ 为什么拆页：指纹那一组（开关 + 四行文案 + 预览 + 恢复默认）占了整页一半以上，
 * 而它只对"开了锁屏密码**并且**设备有指纹"的人有用；混在同一页里，其余五个开关
 * 被挤到看不见的地方，改个锁定时机还要先滚过一大段指纹文案。
 *
 * 手势设置流程与指纹二级页都是**就地覆盖**层（与「图标更改」同一模式）：不加导航路由，
 * 返回键由各层自己的 BackHandler 接住（等同取消 / 返回上一级）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val settings = remember(context) { context.appContainer.settingsRepository }
    val scope = rememberCoroutineScope()

    val enabled by settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val patternHash by settings.appLockPatternHash.collectAsStateWithLifecycle(initialValue = "")
    val biometricEnabled by settings.appLockBiometricEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val helloSplash by settings.appLockHelloSplash.collectAsStateWithLifecycle(initialValue = true)
    val errorFeedback by settings.appLockErrorFeedback.collectAsStateWithLifecycle(initialValue = true)
    val relockMode by settings.appLockRelock.collectAsStateWithLifecycle(
        initialValue = AppLockRelock.DEFAULT
    )

    // 是否处于「设置手势」覆盖流程（首次开启 / 修改手势共用）。
    var settingPattern by remember { mutableStateOf(false) }
    // 开启开关但还没有手势时：流程结束后要不要顺势启用（见开关的 onCheckedChange）。
    var enableAfterSetup by remember { mutableStateOf(false) }
    // 是否进入「指纹解锁」二级页（指纹总开关 + 指纹弹窗文字，2026-10-29 拆出）。
    var showBiometric by remember { mutableStateOf(false) }

    // 注：本页不再自己探测生物识别（2026-10-30）。入口行的副标题改成只看开关状态，
    // "设备不支持 / 锁屏密码没开"的原因都放在二级页里说明——那边本来就要探测一次。

    // ⚠️ 两个覆盖层（设手势 / 指纹二级页）各自带 BackHandler（等同取消 / 返回上一级），
    //    本页这条必须在覆盖态下让位，否则返回键会直接退到"设置"页。
    BackHandler(enabled = !settingPattern && !showBiometric) { onBack() }

    // 两个二级层改成**覆盖 + 滑入**（2026-10-30 用户要求「点绘制时从右侧划入」）：
    //
    // 以前是 `if (layer) { Layer(); return }`——一出现就是终态，没有任何转场，
    // 观感是"啪一下换页"。改成把主页面与两个层放进同一个 Box，用 AnimatedVisibility
    // 控制可见性：进入从右侧滑入、退出向右滑回，与全应用二级页的推入口径一致。
    //
    // 覆盖层不透明且铺满整屏，下层收不到触摸，行为与原 early-return 等效；
    // 两个层的 BackHandler 仍比本页那条更内层，注册后优先生效。
    Box(Modifier.fillMaxSize()) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("锁屏密码") },
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
                    Column {
                        // ---- 总开关 ----
                        SettingSwitchRow(
                            title = "锁屏密码",
                            // 2026-10-30 用户口径：改成"开关当前状态"式的一句话。
                            subtitle = if (enabled) {
                                "已开启 · 启用应用时需要验证密码"
                            } else {
                                "已关闭 · 开启后启动应用需要验证密码"
                            },
                            checked = enabled,
                            onCheckedChange = { want ->
                                if (want && patternHash.isBlank()) {
                                    // 还没有手势：先进设置流程，完成后才启用。
                                    enableAfterSetup = true
                                    settingPattern = true
                                } else {
                                    scope.launch {
                                        // ⚠️ 顺序不能换（v1.1.92 修「开关一拨就跳回主页」）：
                                        // 先置"本次已解锁"，再写开关。反过来时 `setAppLockEnabled(true)`
                                        // 先到，门闸会立刻重组成"该锁了"，把当前设置页连同导航栈
                                        // 一起换掉；用户感受是界面突然弹回主页。
                                        if (want) AppLockSession.unlocked = true
                                        settings.setAppLockEnabled(want)
                                    }
                                }
                            }
                        )

                        RowDivider()

                        // ---- 修改手势 ----
                        //
                        // ⚠️ 总开关关着时**不允许**进绘制流程（2026-10-29 用户要求）：
                        // 没有锁屏密码，手势画了也不会被用到，进去画两遍纯属白忙；
                        // 想设手势就先把上面的开关打开——那条路径本来就会引导"设完手势才启用"。
                        ActionRow(
                            title = if (patternHash.isBlank()) "设置手势" else "修改手势",
                            subtitle = when {
                                !enabled && patternHash.isBlank() ->
                                    "先打开上面的「锁屏密码」，开启时会引导你画一次手势"
                                !enabled -> "先开启锁屏密码，才能修改手势"
                                else -> "重新绘制一个手势图案（至少 $MIN_PATTERN_POINTS 个点）"
                            },
                            enabled = enabled,
                            filled = true,
                            onClick = { settingPattern = true }
                        )

                        RowDivider()

                        // ---- 指纹解锁（二级页入口，2026-10-29 拆出）----
                        //
                        // 原来是本页的一行开关 + 一整块「指纹弹窗文字」（四行 + 预览 + 恢复默认）。
                        // 那一组只对"开了锁屏密码且设备有指纹"的人有用，却把其余五个开关
                        // 挤到看不见的地方，所以整组收进 [BiometricSettingsLayer]。
                        // 本页只留这一行：右侧「去设置」、副标题一句话交代当前状态。
                        ActionRow(
                            title = "指纹解锁",
                            // 2026-10-30 用户口径：副标题**只看开关**，写「已开启 / 已关闭」。
                            // 为什么不能开的（设备不支持 / 锁屏密码没开）留到二级页说明；
                            // 这里再压一句原因，同一行就会同时出现"状态"和"原因"两套话。
                            subtitle = if (biometricEnabled) "已开启" else "已关闭",
                            // 2026-10-30 用户要求：**锁屏密码总开关关着时这一行不可点**。
                            // 锁屏都不生效了，里面的指纹选项改了也没处体现，还会让人以为
                            // "开了指纹就等于有锁"。灰掉比点进去再解释更直接。
                            enabled = enabled,
                            // 右侧不再写「去设置」四个字，改成一个右箭头。
                            showArrow = true,
                            onClick = { showBiometric = true }
                        )

                        RowDivider()

                        // ---- 解锁前 Hello World 开屏 ----
                        SettingSwitchRow(
                            title = "解锁前显示 Hello World",
                            subtitle = "冷启动先播一层开屏，再出现手势锁屏",
                            checked = helloSplash,
                            enabled = enabled,
                            onCheckedChange = { want ->
                                scope.launch { settings.setAppLockHelloSplash(want) }
                            }
                        )

                        RowDivider()

                        // ---- 输错强反馈 ----
                        SettingSwitchRow(
                            title = "输错时震动并变红",
                            subtitle = "手势画错时手机短震一次，提示文字与轨迹变红",
                            checked = errorFeedback,
                            enabled = enabled,
                            onCheckedChange = { want ->
                                scope.launch { settings.setAppLockErrorFeedback(want) }
                            }
                        )

                        RowDivider()

                        // ---- 锁定时机（2026-10-27 需求；同日改为**下拉列表**）----
                        //
                        // 上一版是「一行 + 弹窗单选」，用户要求改成下拉列表：少一层弹窗、
                        // 选完立即生效，观感上与「日期显示格式」那类设置统一。
                        //
                        // 五档的说明**没有丢**：行副标题显示当前档的一句话解释，
                        // 下拉菜单里每一项也带着自己那句（`AppDropdownItem.hint`）——
                        // 用户在下拉里比较时看得到"选了会怎样"，不用先选再回来读。
                        AppDropdownRow(
                            title = "锁定时机",
                            subtitle = relockModeDescription(relockMode),
                            selectedLabel = relockMode.label,
                            selectedIndex = AppLockRelock.entries.indexOf(relockMode),
                            items = AppLockRelock.entries.map { mode ->
                                AppDropdownItem(label = mode.label, hint = relockModeDescription(mode))
                            },
                            enabled = enabled,
                            onSelect = { index ->
                                val mode = AppLockRelock.entries[index]
                                // 选了同一档不写盘、也不弹提示（避免"点了没变还提示一次"）。
                                if (mode != relockMode) {
                                    scope.launch {
                                        settings.setAppLockRelock(mode)
                                        // 宽限档要"从这一刻开始算"（2026-11-06 修「今天内不再锁」杀后台失效）。
                                        //
                                        // 原因：`setAppLockRelock` 会清掉旧凭据，而凭据原先只在
                                        // **解锁回调**里写。用户是在已解锁状态下进设置改档的——
                                        // 改完之后既没有新凭据、旧的又被清了，杀掉进程再进来必然
                                        // 要求重新验证。这里补一次"此刻就是解锁时刻"。
                                        if (mode.isGrace && AppLockSession.unlocked) {
                                            settings.setAppLockGrace(
                                                AppLockGrace.encode(mode, System.currentTimeMillis())
                                            )
                                        }
                                        onMessage("锁定时机：${mode.label}")
                                    }
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "手势以加密哈希保存在本机，不存储图案本身；" +
                        "指纹校验由系统完成，本应用不接触指纹数据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // ── 设手势 / 修改手势：从右侧滑入 ────────────────────────────────
        AnimatedVisibility(
            visible = settingPattern,
            enter = slideInHorizontally(animationSpec = tween(LAYER_SLIDE_MS)) { it },
            exit = slideOutHorizontally(animationSpec = tween(LAYER_SLIDE_MS)) { it }
        ) {
            PatternSetupLayer(
                onCancel = {
                    settingPattern = false
                    enableAfterSetup = false
                },
                onSaved = { salt, hash ->
                    settingPattern = false
                    scope.launch {
                        // ⚠️ 顺序不能换：**先把本次会话标记成已解锁，再写"已启用"**。
                        // 反过来的话，`setAppLockEnabled(true)` 一提交，门闸就重组成"该锁了"，
                        // 整棵 content（含当前这个设置页与它的导航栈）被换掉——用户看到的是
                        // "设完手势，界面跳回主页"。AppLockSession 是 Compose 状态，置位同步生效。
                        AppLockSession.unlocked = true
                        settings.setAppLockSalt(salt)
                        settings.setAppLockPatternHash(hash)
                        if (enableAfterSetup) {
                            settings.setAppLockEnabled(true)
                            enableAfterSetup = false
                            onMessage("锁屏密码已开启")
                        } else {
                            onMessage("手势已更新")
                        }
                    }
                }
            )
        }

        // ── 指纹解锁二级页：同一套滑入（与上一层转场保持一致）──────────────
        AnimatedVisibility(
            visible = showBiometric,
            enter = slideInHorizontally(animationSpec = tween(LAYER_SLIDE_MS)) { it },
            exit = slideOutHorizontally(animationSpec = tween(LAYER_SLIDE_MS)) { it }
        ) {
            BiometricSettingsLayer(
                onBack = { showBiometric = false },
                onMessage = onMessage
            )
        }
    }
}

/** 二级层滑入/滑出时长：与全应用二级页的 280ms 同量级，略短更利落。 */
private const val LAYER_SLIDE_MS = 240

/**
 * 「锁定时机」的一句话说明（显示在设置行副标题）。
 *
 * 每档都写清「什么条件下会重新要求解锁」，而不是只给"立即 / 30 秒"这种干巴巴的标签——
 * 用户真正要判断的是"切出去回个消息会不会被踢回锁屏"。
 */
private fun relockModeDescription(mode: AppLockRelock): String = when (mode) {
    AppLockRelock.COLD_START -> "本次使用期间只询问一次，退出应用后才重新要求"
    AppLockRelock.IMMEDIATE -> "切到后台或锁屏后，一回来就要求解锁"
    AppLockRelock.ONE_MINUTE -> "离开超过 1 分钟回来才要求解锁"
    AppLockRelock.FIVE_MINUTES -> "离开超过 5 分钟回来才要求解锁"
    AppLockRelock.ONE_HOUR_GRACE -> "解锁后 1 小时内切后台回来不再询问"
    AppLockRelock.TODAY_GRACE -> "解锁后当天内切后台回来不再询问"
}

/** 指纹文案的四个可编辑字段。 */
private enum class BioField { TITLE, SUBTITLE, DESCRIPTION, NEGATIVE }

/** 卡片内分隔线（统一缩进，避免每处都写一遍 Modifier）。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/** 本页的一行开关（与设置页同一视觉，紧凑版）。 */
@Composable
private fun SettingSwitchRow(
    title: String,
    // 2026-10-30：副标题改成可空——指纹解锁页那一行用户要求**不要副标题**，
    // 留一句"锁屏页可用系统指纹弹窗解锁"反而与开关状态重复。
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.strongTextColor
                else MaterialTheme.strongTextColor.copy(alpha = 0.38f)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.75f else 0.38f
                    )
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 本页的一行动作入口。
 *
 * @param label 右侧按钮文案；为 null 时退回「设置手势 → 去设置 / 其它 → 绘制」的旧口径。
 * @param labelColor 按钮文字颜色（「恢复」用它固定成红色）。
 * @param filled 按钮要不要**有底色**（2026-10-29：手势那一行的「绘制」改成有底色的
 *   实心浅色按钮）。默认 false 保持其它行原来的纯文字按钮，免得卡片里一排色块。
 */
@Composable
private fun ActionRow(
    title: String,
    // 2026-10-30：副标题可空（「恢复默认文案」那行按要求删掉了说明）。
    subtitle: String? = null,
    enabled: Boolean,
    label: String? = null,
    labelColor: Color = Color.Unspecified,
    filled: Boolean = false,
    /**
     * 右侧只画一个右箭头（2026-10-30 用户要求：指纹解锁入口不要「去设置」四个字）。
     * 为 true 时 [label] / [labelColor] / [filled] 都不生效。
     */
    showArrow: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 只有箭头的那一行（`showArrow`）**必须整行可点**：它没有 TextButton
            // 承担点击，只画一个 Icon 的话点了没反应（2026-10-30 首版真机就踩到了：
            // 「指纹解锁」入口点不开）。
            .then(
                if (showArrow && enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.strongTextColor
                else MaterialTheme.strongTextColor.copy(alpha = 0.38f)
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.75f else 0.38f
                    )
                )
            }
        }
        val text = label ?: if (title == "设置手势") "去设置" else "绘制"
        if (showArrow) {
            // 与「设置」页的入口行同一口径：一个右箭头，不带任何文字按钮。
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f
                )
            )
        } else if (filled) {
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        labelColor == Color.Unspecified ->
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else -> labelColor
                    }
                )
            }
        } else {
            TextButton(onClick = onClick, enabled = enabled) {
                Text(
                    text = text,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        labelColor == Color.Unspecified -> MaterialTheme.colorScheme.primary
                        else -> labelColor
                    }
                )
            }
        }
    }
}

/**
 * 一行「字段名 + 当前值」，点一下进入编辑。
 *
 * 清空过的字段显式写出「（已清空）」，而不是留一片空白——否则用户分不清
 * "这行没内容"和"这里本来就没有这一行"。
 */
@Composable
private fun BioTextRow(
    title: String,
    value: String,
    clearable: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Text(
                text = if (value.isBlank() && clearable) "（已清空，不显示）" else value,
                style = MaterialTheme.typography.labelSmall,
                color = if (value.isBlank() && clearable) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                }
            )
        }
        // 2026-10-30 用户要求：这几行的「修改」按钮统一换成右箭头（与设置页入口行同构）。
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "修改",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单行文字编辑弹窗。
 *
 * @param allowEmpty 允许保存空串（副标题 / 注释）。不允许为空时，保存空串会**静默忽略**
 *   （按钮置灰更直观，但这里用置灰 + 保留原值是更省事的一致做法：见 confirmButton）。
 */
@Composable
private fun BioTextEditDialog(
    fieldLabel: String,
    allowEmpty: Boolean,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val canSave = allowEmpty || text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fieldLabel) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        // 超出上限直接截断，而不是弹"太长"——输入时更顺。
                        text = it.take(SettingsRepository.MAX_APP_LOCK_BIO_TEXT)
                    },
                    singleLine = true,
                    placeholder = {
                        Text(if (allowEmpty) "留空则不显示这一行" else "不能为空")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${text.length} / ${SettingsRepository.MAX_APP_LOCK_BIO_TEXT}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    textAlign = TextAlign.End
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = canSave
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 「预览」的**退路**：仿系统指纹弹窗的模拟卡片。
 *
 * ## 现在是备选方案，不再是主路径（2026-10-27 用户要求）
 *
 * 设备支持生物识别时，「预览」会直接调 `previewWithRealPrompt()` 弹出**系统真实弹窗**，
 * 看到的就是真机上的排版。走到这里只有两种情况：
 * 1. 设备没有可用的指纹/面部（没录入、或模拟器）——`canAuthenticate` 不是 SUCCESS；
 * 2. 极少见的宿主异常（拿不到 FragmentActivity）。
 *
 * 之所以还保留它：**锁屏密码可以在没有指纹的设备上开启**（玩法是纯手势），
 * 这时用户仍然想看看自己写的文案长什么样，不给入口就只能盲改。
 *
 * 卡片顶部会写明"这是模拟预览"，免得被当成真弹窗的样子。
 *
 * 空字段**不画空行**，而是留一句灰色说明，明确告诉用户"这一行不会显示"——
 * 否则用户会以为是预览漏画了。
 */
@Composable
private fun BioPromptPreviewDialog(
    texts: AppLockBioTexts,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    text = "模拟预览（此设备无可用的指纹/面部，弹不出系统弹窗）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = texts.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(12.dp))
                PreviewLine(texts.subtitle, emptyHint = "副标题已清空，弹窗不显示这一行")
                Spacer(Modifier.height(6.dp))
                PreviewLine(texts.description, emptyHint = "注释已清空，弹窗不显示这一行")

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(texts.negative)
                }
            }
        }
    }
}

/** 预览里的一行文字；空则显示灰色说明。 */
@Composable
private fun PreviewLine(text: String, emptyHint: String) {
    if (text.isNotBlank()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    } else {
        Text(
            text = emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

/**
 * 手势设置覆盖层：两步（画一次 → 再画一次确认）。
 *
 * 两次一致 → 生成新盐 + 哈希回调 [onSaved]；不一致 / 任何一步少于最少点数 →
 * 提示并回到第一步重画。返回键 = 取消（等同 onCancel）。
 *
 * ⚠️ 每次状态切换都要**清空板面**（[clearSignal]）：v1.1.92 之前第 1 步画完切到第 2 步时，
 * 上一笔痕迹留在板上，用户会把旧线当成"这一遍只画了一条"，越画越乱（v1.1.92 修）。
 *
 * ## 画对之后先停留再切步（2026-11-03 需求）
 *
 * 两处都要"停一下"：第 1 步画对 → 成功色停留 [PATTERN_SUCCESS_HOLD_MS] 再进第 2 步；
 * 第 2 步确认一致 → 成功色停留后、停留结束才 `onSaved`。
 * **为什么不能立刻切换**：第 1 步画完当帧清屏切步，用户看到的是"我刚画的线闪过一下
 * 就没了、界面标题变了"，分不清是自己画错了还是程序跳步了；第 2 步更明显——
 * 画完当帧弹回设置页，等于连"对了"的确认都没有。
 *
 * ⚠️ 停留由**本层**排定时（[PatternLockView] 只负责保持着色），因为"何时切步"是
 * 业务流程；两边都以 [PATTERN_SUCCESS_HOLD_MS] 为准，避免颜色先灭、界面后切。
 *
 * ⚠️ `internal` 而不是 `private`（2026-11-01）：锁屏「忘记密码」在身份验证通过后
 * 要用同一套两步流程就地重设手势（见 `AppLockCore` 的 `resetting` 分支）。
 * 复用同一份实现而不是另写一个，是为了保证"重设"与"首次设置"的手势规则
 * （最少点数、两次一致、换新盐）永远一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PatternSetupLayer(
    onCancel: () -> Unit,
    onSaved: (salt: String, hash: String) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var first by remember { mutableStateOf<List<Int>>(emptyList()) }
    var hint by remember { mutableStateOf("绘制一个新手势（至少连接 $MIN_PATTERN_POINTS 个点）") }
    // 板面清空信号：每次换步 / 重来都 +1。
    var clearSignal by remember { mutableIntStateOf(0) }
    // 成功着色信号：画对时 +1（2026-11-03），含义见 [PATTERN_SUCCESS_HOLD_MS]。
    var successSignal by remember { mutableIntStateOf(0) }
    // 停留定时需要跨组合存活的协程作用域；随本层销毁而取消（取消即"不保存"）。
    val scope = rememberCoroutineScope()

    BackHandler { onCancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (step == 1) "设置手势 · 第 1 步" else "设置手势 · 第 2 步") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(28.dp))
            PatternLockView(
                onPattern = { pattern ->
                    if (pattern.size < MIN_PATTERN_POINTS) {
                        hint = "至少连接 $MIN_PATTERN_POINTS 个点，重新画一次"
                        clearSignal++
                        return@PatternLockView
                    }
                    // 先只判"这一遍对不对"，把状态切换统一留给停留结束后的协程：
                    // 判定与切换分开，才能保证"成功色亮够时间"与"实际切步"是同一时刻。
                    val matched = step == 1 || pattern == first
                    if (!matched) {
                        // 错误路径保持原口径：不清成功色、直接清板并回到第 1 步。
                        step = 1
                        first = emptyList()
                        hint = "两次手势不一致，请重新开始"
                        clearSignal++
                        return@PatternLockView
                    }
                    successSignal++
                    hint = if (step == 1) {
                        "第 1 步已记录，正在进入第 2 步…"
                    } else {
                        "手势已确认，正在保存…"
                    }
                    scope.launch {
                        delay(PATTERN_SUCCESS_HOLD_MS)
                        if (step == 1) {
                            first = pattern
                            step = 2
                            hint = "再画一次确认刚才的手势"
                            clearSignal++
                        } else {
                            // 一致：换一份新盐再哈希（重设手势 = 旧哈希作废）。
                            val salt = java.util.UUID.randomUUID().toString().replace("-", "")
                            onSaved(salt, hashPattern(salt, pattern))
                        }
                    }
                },
                clearSignal = clearSignal,
                successSignal = successSignal,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                    .padding(8.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "手势图案不会上传，也不会以明文保存在本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「指纹解锁」二级页（2026-10-29 从「锁屏密码」页拆出）。
 *
 * ## 为什么单独一页
 *
 * 指纹那一组（总开关 + 四行弹窗文案 + 预览 + 恢复默认）占了原页面一半以上篇幅，
 * 但它只对"既开了锁屏密码、设备又有指纹"的人有用。混在一起时，其余五个开关被挤到
 * 看不见的地方，改个锁定时机得先滚过一大段指纹文案——拆开后两边都只留自己那点东西。
 *
 * ## 两块内容
 *
 * 1. **指纹解锁总开关**：设备支持 `BiometricPrompt`（BIOMETRIC_WEAK）**且**锁屏密码
 *    已开启时才可拨；不满足时开关置灰，副标题直接说明原因。
 * 2. **指纹弹窗文字**：标题 / 副标题 / 注释 / 取消按钮四行，可编辑、可预览、可恢复默认；
 *    副标题与注释允许清空（= 系统弹窗不显示那一行）。
 *
 * 文案区沿用拆页前的口径：**锁屏密码没开就不显示**（那时是 `if (enabled)`）——
 * 页面上改为一句说明，而不是给一张改完也不会被用到的空表单。
 *
 * ## 这一页是就地覆盖层
 *
 * 与「设手势」同一模式：不加导航路由，自带 TopAppBar 与 BackHandler，
 * 返回键 = 回上一级（不是直接退到设置页）。所以父页的 BackHandler 要在本层打开时让位。
 *
 * @param onBack 返回「锁屏密码」页。
 * @param onMessage 全局提示（保存成功 / 已恢复默认 / 预览成功）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BiometricSettingsLayer(
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val settings = remember(context) { context.appContainer.settingsRepository }
    val scope = rememberCoroutineScope()

    val appLockEnabled by settings.appLockEnabled.collectAsStateWithLifecycle(initialValue = false)
    val biometricEnabled by settings.appLockBiometricEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val bioTexts by settings.appLockBioTexts.collectAsStateWithLifecycle(
        initialValue = AppLockBioTexts.DEFAULT
    )

    // 正在编辑的指纹文案字段（null = 没在编辑）。
    var editingField by remember { mutableStateOf<BioField?>(null) }
    // 「恢复默认文案」的二次确认。
    var confirmRestore by remember { mutableStateOf(false) }
    // 「预览指纹弹窗」（只在弹不出系统弹窗时用）。
    var showPreview by remember { mutableStateOf(false) }

    val canBiometric = remember(context) {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    // 系统指纹弹窗需要一个 FragmentActivity（MainActivity 已是）。
    val hostActivity = remember(context) { context.findActivity() }

    /**
     * 调起**系统真实**的指纹弹窗来预览文案（2026-10-27 用户要求「预览用真弹窗」）。
     *
     * ## 为什么能这么做
     *
     * `BiometricPrompt` 本身不区分"解锁"还是"只想看看"——它就是弹一个系统界面，
     * 由系统完成一次认证。所以这里照常 `authenticate()`，用户按取消（或按「使用密码」）
     * 就退出了。看到的是**真机上的真实排版**：字体、行距、副标题缺省时少一行、
     * `setDescription` 在 Android 10 上不显示——这些仿制卡片都模拟不出来。
     *
     * ## 回调都不改变应用状态
     *
     * 预览发生在设置页里，与"本次会话是否已解锁"无关：
     * - 成功：只报一句"显示正常"，**不做任何解锁动作**；
     * - 取消 / 使用密码 / 出错：全部静默。用户按取消是最常见的退出方式，
     *   弹一个"指纹识别出错"的提示反而像失败。
     */
    fun previewWithRealPrompt() {
        val activity = hostActivity as? FragmentActivity
        if (activity == null) {
            // 理论上不会发生（MainActivity 是 FragmentActivity）。真发生了就退到模拟预览，
            // 宁可少一层真实感，也不能让"点了没反应"。
            showPreview = true
            return
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    onMessage("系统弹窗显示正常（预览不会解锁任何东西）")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 静默：ERROR_USER_CANCELED / ERROR_NEGATIVE_BUTTON / 其他都算正常退出预览。
                }
            }
        )
        prompt.authenticate(buildBioPromptInfo(bioTexts))
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("指纹解锁") },
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
                SettingSwitchRow(
                    title = "指纹解锁",
                    // 2026-10-30：本页这一行**不要副标题**（用户要求）。开关本身就是状态，
                    // 不能开的原因由下方那段说明承担，不必再压一行小字。
                    subtitle = null,
                    checked = biometricEnabled,
                    // 两个前置条件缺一不可：设备要有可用生物识别、锁屏密码要已开启。
                    enabled = canBiometric && appLockEnabled,
                    onCheckedChange = { want ->
                        scope.launch { settings.setAppLockBiometricEnabled(want) }
                    }
                )
            }

            if (appLockEnabled) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "指纹弹窗文字",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        BioTextRow(
                            title = "标题",
                            value = bioTexts.title,
                            clearable = false,
                            onClick = { editingField = BioField.TITLE }
                        )
                        RowDivider()
                        BioTextRow(
                            title = "副标题",
                            value = bioTexts.subtitle,
                            clearable = true,
                            onClick = { editingField = BioField.SUBTITLE }
                        )
                        RowDivider()
                        BioTextRow(
                            title = "注释",
                            value = bioTexts.description,
                            clearable = true,
                            onClick = { editingField = BioField.DESCRIPTION }
                        )
                        RowDivider()
                        BioTextRow(
                            title = "取消按钮",
                            value = bioTexts.negative,
                            clearable = false,
                            onClick = { editingField = BioField.NEGATIVE }
                        )
                    }
                }

                // 「预览」与「恢复默认文案」**单独一张卡**（2026-10-30 用户要求
                // 「与上面的设置分开」）：上面四行是"改文案"，这两行是"用文案"与
                // "还原文案"，性质不同，混在一张卡里会让人以为它们也是待编辑字段。
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        ActionRow(
                            title = "预览",
                            // 只保留前半句（用户要求）；弹不出真弹窗时再说清楚为什么。
                            subtitle = if (canBiometric) {
                                "调起系统真实指纹弹窗"
                            } else {
                                "此设备没有可用的指纹/面部，只能模拟预览"
                            },
                            enabled = true,
                            label = "查看",
                            onClick = {
                                // 能弹就弹真的（看得见真排版），弹不了才退回模拟卡片。
                                if (canBiometric) previewWithRealPrompt() else showPreview = true
                            }
                        )
                        RowDivider()
                        ActionRow(
                            title = "恢复默认文案",
                            // 副标题删除（用户要求）：标题本身已经把事说清了。
                            subtitle = null,
                            enabled = true,
                            label = "恢复",
                            // 用户要求：恢复按钮文字**固定红色**（破坏性/还原类操作）。
                            labelColor = MaterialTheme.colorScheme.error,
                            onClick = { confirmRestore = true }
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "锁屏密码还没开启：指纹只是锁屏页的一条快捷解锁路径，" +
                        "没有锁屏密码时不显示。回上一页打开「锁屏密码」后，这里会出现" +
                        "弹窗文字的设置项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "指纹校验由系统完成，本应用不接触指纹数据；" +
                    "这里改的只是系统弹窗上的文字。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    // ---- 弹窗们（挂在本层最外层）----

    editingField?.let { field ->
        val initial = when (field) {
            BioField.TITLE -> bioTexts.title
            BioField.SUBTITLE -> bioTexts.subtitle
            BioField.DESCRIPTION -> bioTexts.description
            BioField.NEGATIVE -> bioTexts.negative
        }
        BioTextEditDialog(
            fieldLabel = when (field) {
                BioField.TITLE -> "指纹弹窗标题"
                BioField.SUBTITLE -> "指纹弹窗副标题"
                BioField.DESCRIPTION -> "指纹弹窗注释"
                BioField.NEGATIVE -> "取消按钮文字"
            },
            // 副标题 / 注释可清空；标题与按钮文案不能为空（系统 API 要求）。
            allowEmpty = field == BioField.SUBTITLE || field == BioField.DESCRIPTION,
            initial = initial,
            onDismiss = { editingField = null },
            onConfirm = { text ->
                editingField = null
                val next = when (field) {
                    BioField.TITLE -> bioTexts.copy(title = text)
                    BioField.SUBTITLE -> bioTexts.copy(subtitle = text)
                    BioField.DESCRIPTION -> bioTexts.copy(description = text)
                    BioField.NEGATIVE -> bioTexts.copy(negative = text)
                }
                scope.launch {
                    settings.setAppLockBioTexts(next)
                    onMessage(if (text.isEmpty()) "已清空，弹窗不再显示这一行" else "指纹弹窗文字已更新")
                }
            }
        )
    }

    if (confirmRestore) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("恢复默认文案？") },
            text = { Text("将把标题、副标题、注释与取消按钮文字都改回默认，已改过的内容会被覆盖。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    scope.launch {
                        settings.setAppLockBioTexts(AppLockBioTexts.DEFAULT)
                        onMessage("已恢复默认文案")
                    }
                }) {
                    Text("恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("取消") }
            }
        )
    }

    if (showPreview) {
        BioPromptPreviewDialog(
            texts = bioTexts,
            onDismiss = { showPreview = false }
        )
    }
}
