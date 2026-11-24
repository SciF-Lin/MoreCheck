package com.morecheck.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.SettingsRepository
import com.morecheck.di.appContainer
import com.morecheck.ui.common.pressScale
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.launch

/** 静默开始时段的常见档位；默认 22:00 必须在其中，用户才能一眼选回出厂值。 */
private val QUIET_START_OPTIONS = listOf("20:00", "21:00", "22:00", "23:00", "00:00", "01:00")

/** 静默结束时段的常见档位；默认 07:00 必须在其中（跨午夜）。 */
private val QUIET_END_OPTIONS = listOf("05:00", "06:00", "07:00", "08:00", "09:00", "10:00")

/**
 * 「打卡提醒增强」分区：静默时段与漏打卡补推。
 *
 * 全部读写直接走 [SettingsRepository] 的同一批 DataStore 键，与提醒接收器
 * （ReminderReceiver / ReminderPolicy）读的是同一份数据；默认值取仓库常量，
 * 保证没调过开关时行为与改造前一致。起止时刻用可选档位列表而不是 TimePicker，
 * 是为了不引入额外的 Material3 时间选择器依赖，同时 22:00 到 07:00
 * 这类常见跨午夜区间能直接选出来。
 */
@Composable
fun ReminderEnhanceSection(
    modifier: Modifier = Modifier,
    /** false = 独立页面用法：不画卡头、内容常显（2026-11-08 需求 5）。 */
    collapsible: Boolean = true
) {
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.settingsRepository }
    val scope = rememberCoroutineScope()

    val quietEnabled by repository.reminderQuietEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val quietStart by repository.reminderQuietStart
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_QUIET_START)
    val quietEnd by repository.reminderQuietEnd
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_QUIET_END)
    val followUpEnabled by repository.reminderFollowUpEnabled
        .collectAsStateWithLifecycle(initialValue = false)
    val followUpMinutes by repository.reminderFollowUpMinutes
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_FOLLOWUP_MINUTES)

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showMinutesPicker by remember { mutableStateOf(false) }

    ExpandableSectionCard(
        title = "打卡提醒增强",
        subtitle = "夜间静默时段与漏打卡补推",
        modifier = modifier,
        collapsible = collapsible
    ) {
        SettingsSwitchRow(
            title = "夜间静默时段",
            subtitle = "区间内不弹打卡提醒，但次日提醒照常续排",
            checked = quietEnabled,
            onCheckedChange = { scope.launch { repository.setReminderQuietEnabled(it) } }
        )
        SettingsSectionDivider()
        SettingsValueRow(
            title = "静默开始",
            subtitle = "默认 22:00",
            valueText = quietStart,
            onClick = { showStartPicker = true }
        )
        SettingsSectionDivider()
        SettingsValueRow(
            title = "静默结束",
            subtitle = "默认 07:00，支持跨午夜区间",
            valueText = quietEnd,
            onClick = { showEndPicker = true }
        )
        SettingsSectionDivider()
        SettingsSwitchRow(
            title = "漏打卡补推",
            subtitle = "主提醒后若今天仍未打卡，到点再提醒一次",
            checked = followUpEnabled,
            onCheckedChange = { scope.launch { repository.setReminderFollowUpEnabled(it) } }
        )
        SettingsSectionDivider()
        SettingsValueRow(
            title = "补推延迟",
            subtitle = "主提醒发出后多久检查是否打卡",
            valueText = "$followUpMinutes 分钟",
            onClick = { showMinutesPicker = true }
        )
    }

    if (showStartPicker) {
        SingleChoiceDialog(
            title = "静默开始",
            // 把当前值也并进档位：万一磁盘上存着档位表以外的值，仍能选回它、不会卡死。
            options = (QUIET_START_OPTIONS + quietStart).distinct().map { it to it },
            selectedValue = quietStart,
            onSelect = { value ->
                showStartPicker = false
                scope.launch { repository.setReminderQuietStart(value) }
            },
            onDismiss = { showStartPicker = false }
        )
    }
    if (showEndPicker) {
        SingleChoiceDialog(
            title = "静默结束",
            options = (QUIET_END_OPTIONS + quietEnd).distinct().map { it to it },
            selectedValue = quietEnd,
            onSelect = { value ->
                showEndPicker = false
                scope.launch { repository.setReminderQuietEnd(value) }
            },
            onDismiss = { showEndPicker = false }
        )
    }
    if (showMinutesPicker) {
        SingleChoiceDialog(
            title = "补推延迟",
            options = SettingsRepository.FOLLOWUP_OPTIONS.map { it.toString() to "$it 分钟" },
            selectedValue = followUpMinutes.toString(),
            onSelect = { value ->
                showMinutesPicker = false
                value.toIntOrNull()?.let { minutes ->
                    scope.launch { repository.setReminderFollowUpMinutes(minutes) }
                }
            },
            onDismiss = { showMinutesPicker = false }
        )
    }
}

/**
 * 分区 Card。
 *
 * 提醒增强与个性化原本是设置页里的**页内折叠分区**：标题行点一下展开或收起，
 * 内容留在本页的滚动列表里，默认展开。
 *
 * 2026-11-08 需求 5 把这两块搬进各自二级页后，[collapsible] 传 false：
 * 页面的 TopAppBar 已经写着同名标题，卡里再顶一条「标题 + 收起」就是同义反复，
 * 而且让用户白点一次才能看到内容（他本来就是专门进来调这个的）。
 * 折叠能力保留在 [collapsible] = true 的旧用法上，随时可以切回。
 */
@Composable
internal fun ExpandableSectionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    /** false = 不显示标题行、内容常显（独立页面用法）。 */
    collapsible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    Card(modifier) {
        Column(Modifier.fillMaxWidth()) {
            if (collapsible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(onClick = { expanded = !expanded })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.strongTextColor
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(if (collapsible) 4.dp else 12.dp))
                content()
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 一行「标题 + 说明 + 当前值」的可点设置项。 */
@Composable
internal fun SettingsValueRow(
    title: String,
    subtitle: String,
    valueText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 一行「标题 + 说明 + 开关」的设置项。 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 分区内左侧缩进的分隔线，口径与设置页其它 Card 一致。 */
@Composable
internal fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * 通用单选弹层。
 *
 * options 每项是「持久化值 到 界面文案」的 Pair：像日期格式、检查更新去处这类
 * 枚举项，落盘的是稳定字符串，界面显示中文说明，两者不该混在一起。
 */
@Composable
internal fun SingleChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selectedValue, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 通用单行文本编辑弹层（支持作者链接一类的自由文本）。
 *
 * 清空输入框后保存 = 恢复默认，这一语义交给调用方据业务决定，
 * 本弹层只负责把用户输入的原样文本回传。
 */
@Composable
internal fun SettingsTextFieldDialog(
    title: String,
    label: String,
    initial: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (hint.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
