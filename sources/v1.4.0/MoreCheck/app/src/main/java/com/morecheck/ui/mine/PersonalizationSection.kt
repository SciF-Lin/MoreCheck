package com.morecheck.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.morecheck.data.ChangelogEntryPlacement
import com.morecheck.data.CheckUpdateTarget
import com.morecheck.data.SettingsRepository.DateDisplayFormat
import com.morecheck.data.SplashTextFont
import com.morecheck.data.SplashTextStyle
import com.morecheck.di.appContainer
import com.morecheck.ui.common.displayLabel
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.launch

/** 日期格式三档的界面说明（带示例，比只给枚举 id 更直观）。 */
private val DATE_FORMAT_LABELS = mapOf(
    DateDisplayFormat.CN_FULL to "中文完整（2026年10月19日 周一）",
    DateDisplayFormat.ISO to "ISO 横杠（2026-10-19 周一）",
    DateDisplayFormat.CN_SHORT to "中文短日期（10月19日 周一）"
)

/**
 * 「个性化」分区：把开发者选项里六项外观 / 内容偏好**复制入口**到普通设置页。
 *
 * 六项全部直接读写 [com.morecheck.data.SettingsRepository] 的同一批 DataStore 键，与开发者选项
 * （经 DeveloperOptionsRepository 代理）落到同一处，因此改一处两边都生效。
 * 全部用页内分区与弹层呈现，不新增任何 TabDestination 路由、不碰导航文件。
 * 各订阅的初始值取仓库默认，保证没调过时观感与改造前一致。
 *
 * 第六项「换桌面图标」之所以只放说明、不放按钮：它目前是开发者选项页内的
 * 就地覆盖层（IconPickerScreen 由布尔状态驱动），没有独立导航路由；要在这儿
 * 打开就得新增路由或复制整套页内状态，超出「不改 TabDef / TabNavHost」的约束，
 * 因此保留在开发者选项，仅给出前往路径。
 */
@Composable
fun PersonalizationSection(
    modifier: Modifier = Modifier,
    /** false = 独立页面用法：不画卡头、内容常显（2026-11-08 需求 5）。 */
    collapsible: Boolean = true
) {
    val context = LocalContext.current
    val repository = remember(context) { context.appContainer.settingsRepository }
    val scope = rememberCoroutineScope()

    val checkUpdateTarget by repository.checkUpdateTarget
        .collectAsStateWithLifecycle(initialValue = CheckUpdateTarget.APK_DOWNLOAD)
    val changelogPlacement by repository.changelogEntryPlacement
        .collectAsStateWithLifecycle(initialValue = ChangelogEntryPlacement.INSIDE)
    val supportAuthorUrl by repository.supportAuthorUrl
        .collectAsStateWithLifecycle(initialValue = null)
    val dateFormatRaw by repository.devDateFormat
        .collectAsStateWithLifecycle(initialValue = DateDisplayFormat.DEFAULT_ID)
    val splashTextStyle by repository.splashTextStyle
        .collectAsStateWithLifecycle(initialValue = SplashTextStyle.DEFAULT)

    val dateFormat = DateDisplayFormat.parse(dateFormatRaw)

    var showUpdateTarget by remember { mutableStateOf(false) }
    var showChangelog by remember { mutableStateOf(false) }
    var showSupportUrl by remember { mutableStateOf(false) }
    var showDateFormat by remember { mutableStateOf(false) }
    var showSplashText by remember { mutableStateOf(false) }

    ExpandableSectionCard(
        title = "个性化",
        subtitle = "检查更新、日志入口、日期与开屏等偏好",
        modifier = modifier,
        collapsible = collapsible
    ) {
        // 1. 检查更新按钮去处
        SettingsValueRow(
            title = "检查更新按钮去处",
            subtitle = "点「检查更新」后打开哪里",
            valueText = checkUpdateTarget.title,
            onClick = { showUpdateTarget = true }
        )
        SettingsSectionDivider()

        // 2. 更新日志入口位置
        SettingsValueRow(
            title = "更新日志入口位置",
            subtitle = "日志入口放在下载页还是关于页",
            valueText = if (changelogPlacement == ChangelogEntryPlacement.INSIDE) {
                "下载页底部"
            } else {
                "关于页"
            },
            onClick = { showChangelog = true }
        )
        SettingsSectionDivider()

        // 3. 支持作者链接
        SettingsValueRow(
            title = "支持作者链接",
            subtitle = "点「支持作者」跳转的地址",
            valueText = if (supportAuthorUrl == null) "默认" else "自定义",
            onClick = { showSupportUrl = true }
        )
        SettingsSectionDivider()

        // 4. 日期显示格式
        SettingsValueRow(
            title = "日期显示格式",
            subtitle = "日历与打卡记录里的日期写法",
            valueText = DATE_FORMAT_LABELS[dateFormat].orEmpty().substringBefore("（"),
            onClick = { showDateFormat = true }
        )
        SettingsSectionDivider()

        // 5. 开屏显示文字
        SettingsValueRow(
            title = "开屏显示文字",
            // 2026-11-11 需求：把「字体」补回副标题——上一轮从开发者选项迁过来时
            // 只搬了文案 / 字号 / 单多行，字体档位在界面上丢了（值仍在 DataStore 里）。
            subtitle = "文案、字号、字体与单行 / 多行",
            valueText = splashTextStyle.text.lineSequence().firstOrNull()?.take(10).orEmpty(),
            onClick = { showSplashText = true }
        )
        SettingsSectionDivider()

        // 6. 换桌面图标：不新增路由，只给说明与前往路径。
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "换桌面图标",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.strongTextColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "该功能目前是开发者选项内的页内覆盖层，没有独立导航路由；" +
                    "按「不改 TabDef / TabNavHost」的约束，普通设置页不放跳转按钮，仍保留在原处。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "前往：开发者选项 → 图标更改（实验性）",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showUpdateTarget) {
        SingleChoiceDialog(
            title = "检查更新按钮去处",
            options = CheckUpdateTarget.entries.map { it.value to it.title },
            selectedValue = checkUpdateTarget.value,
            onSelect = { value ->
                showUpdateTarget = false
                scope.launch { repository.setCheckUpdateTarget(CheckUpdateTarget.fromValue(value)) }
            },
            onDismiss = { showUpdateTarget = false }
        )
    }
    if (showChangelog) {
        SingleChoiceDialog(
            title = "更新日志入口位置",
            options = ChangelogEntryPlacement.entries.map { it.value to it.title },
            selectedValue = changelogPlacement.value,
            onSelect = { value ->
                showChangelog = false
                scope.launch {
                    repository.setChangelogEntryPlacement(ChangelogEntryPlacement.fromValue(value))
                }
            },
            onDismiss = { showChangelog = false }
        )
    }
    if (showSupportUrl) {
        SettingsTextFieldDialog(
            title = "支持作者链接",
            label = "跳转地址",
            initial = supportAuthorUrl.orEmpty(),
            hint = "留空保存即恢复内置默认地址",
            onConfirm = { input ->
                showSupportUrl = false
                // takeIf 返回可空串：空白 = 未自定义，setSupportAuthorUrl 会清掉偏好回落默认。
                val normalized = input.trim().takeIf { it.isNotEmpty() }
                scope.launch { repository.setSupportAuthorUrl(normalized) }
            },
            onDismiss = { showSupportUrl = false }
        )
    }
    if (showDateFormat) {
        SingleChoiceDialog(
            title = "日期显示格式",
            options = DateDisplayFormat.entries.map { it.id to DATE_FORMAT_LABELS[it].orEmpty() },
            selectedValue = dateFormat.id,
            onSelect = { value ->
                showDateFormat = false
                scope.launch { repository.setDevDateFormat(DateDisplayFormat.parse(value).id) }
            },
            onDismiss = { showDateFormat = false }
        )
    }
    if (showSplashText) {
        SplashTextDialog(
            style = splashTextStyle,
            onText = { text -> scope.launch { repository.setSplashText(text) } },
            onSize = { size -> scope.launch { repository.setSplashTextSize(size) } },
            onFont = { font -> scope.launch { repository.setSplashTextFont(font) } },
            onMultiline = { multiline ->
                scope.launch { repository.setSplashTextMultiline(multiline) }
            },
            onRestoreDefault = { scope.launch { repository.restoreDefaultSplashText() } },
            onDismiss = { showSplashText = false }
        )
    }
}

/**
 * 「开屏显示文字」编辑弹层：文案 / 字号 / 单多行 / 恢复默认。
 *
 * 字号用 -/+ 按钮而不是 Slider，避免引入 Material3 时间 / 滑块实验性 API 的
 * 额外依赖；步长 2sp，边界由 SplashTextStyle 的 MIN / MAX 常量夹住。
 * 字号、多行、恢复默认即时写入，文案则点「保存」才落盘——与仓库里
 * setSplashText 拒空串的语义一致（清空不该让开屏变空白）。
 */
@Composable
private fun SplashTextDialog(
    style: SplashTextStyle,
    onText: (String) -> Unit,
    onSize: (Int) -> Unit,
    /** 字体档位（2026-11-11 恢复）：默认 / 衬线 / 等宽，即时生效。 */
    onFont: (SplashTextFont) -> Unit,
    onMultiline: (Boolean) -> Unit,
    onRestoreDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(style.text) { mutableStateOf(style.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开屏显示文字") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("显示文字") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "字号",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onSize(
                                (style.sizeSp - 2).coerceIn(
                                    SplashTextStyle.MIN_SIZE_SP,
                                    SplashTextStyle.MAX_SIZE_SP
                                )
                            )
                        },
                        enabled = style.sizeSp > SplashTextStyle.MIN_SIZE_SP
                    ) { Text("-") }
                    Text(text = "${style.sizeSp} sp", style = MaterialTheme.typography.bodyMedium)
                    TextButton(
                        onClick = {
                            onSize(
                                (style.sizeSp + 2).coerceIn(
                                    SplashTextStyle.MIN_SIZE_SP,
                                    SplashTextStyle.MAX_SIZE_SP
                                )
                            )
                        },
                        enabled = style.sizeSp < SplashTextStyle.MAX_SIZE_SP
                    ) { Text("+") }
                }
                // ---- 字体（2026-11-11 恢复）----
                //
                // 用三个 FilterChip 而不是下拉：只有三档、每档两个字的标签，
                // 平铺出来用户一眼就能比较，也不必再套一层弹窗。
                // 写入是**即时**的（与字号 / 多行同一口径），不用等「保存」。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "字体",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    SplashTextFont.entries.forEach { font ->
                        FilterChip(
                            selected = style.font == font,
                            onClick = { onFont(font) },
                            label = { Text(font.displayLabel()) },
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "多行显示",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = style.multiline, onCheckedChange = onMultiline)
                }
                TextButton(onClick = onRestoreDefault, enabled = !style.isDefault) {
                    Text("恢复默认")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onText(text)
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
