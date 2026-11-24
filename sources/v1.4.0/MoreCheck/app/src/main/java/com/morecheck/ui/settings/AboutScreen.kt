package com.morecheck.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.ui.common.pressScale
import com.morecheck.util.AppInfo

/**
 * 关于我们。
 *
 * 排版自下而上分两簇，中间用弹性留白隔开（参考图就是这个结构）：
 * 1. 标题栏：左侧返回箭头，**右侧扳手图标 →「帮助与反馈」**（本页标题栏不随内容改动）；
 * 2. 上簇（居页面中部）：应用图标 + 软件名称 → 版本号 → `检查更新` → **`更新日志 ›`**
 *    （后者仅在 [showChangelogLink] 为真时出现，见该参数的说明）；
 * 3. 下簇（贴页面底部）：**`关于我们`**（跳应用内官网）/ **`意见反馈`**（应用内网页打开问卷）
 *    → 用户协议 / 隐私政策 → 更新日期。
 *
 * ⚠️ 2026-11-12：原「支持作者 / 关于作者」二维码入口整块移除；它原来的位置换成
 * 「关于我们」，原「关于我们」的位置换成「意见反馈」。两行仍与协议行逐个对齐。
 *
 * 版本号与更新日期都取自已安装包的真实信息，随构建自动同步，无需手工维护。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    onOpenChangelog: () -> Unit = {},
    /**
     * 是否在本页显示「更新日志 ›」入口（2026-11-02 需求）。
     *
     * 默认 `true` 保持既有行为；当「检查更新」的去处被设成**云端安装包下载页**时，
     * 调用方传 `false` ——那个页面本身就是"版本"这一簇内容的去处，
     * 日志入口跟着搬过去，避免同一件事在两个地方各开一个口。
     */
    showChangelogLink: Boolean = true,
    onOpenFeedback: () -> Unit = {},
    /**
     * 下簇「关于我们」链接的点击：跳「发现 → 官网」（2026-11-11 需求 4）。
     *
     * 为什么是"跳官网"而不是外链：官网是应用内可浏览的那一页（默认
     * `https://www.scif.top/`），用户在这里能看到站点内容、也能再用页面右上角的
     * 「用系统浏览器打开」出去，比直接甩给浏览器少一步、也不受链接开关影响。
     */
    onOpenOfficialSite: () -> Unit = {},
    /**
     * 「意见反馈」问卷入口（2026-11-12）：由调用方走应用内网页路由打开腾讯问卷，
     * 这样页面外壳、返回键、深浅色都跟随 App，不会甩到系统浏览器里换一套观感。
     */
    onOpenSurvey: () -> Unit = {},
    /**
     * 「链接打开位置」当前值（true = 应用内网页），由调用方订阅
     * `SettingsRepository.linkOpenInApp` 传入——**与开发者选项里那一行同一个数据源**，
     * 两边改任一处，另一处立刻同步（2026-11-11 需求 4）。
     */
    linkOpenInApp: Boolean = false,
    /** 切换「链接打开位置」；调用方负责落盘并同步进程级缓存 `LinkOpenGate`。 */
    onLinkOpenInAppChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val versionName = remember { AppInfo.versionName(context) }
    val updateDate = remember { AppInfo.lastUpdateDate(context) }
    // 右上角「链接打开位置」二选一弹窗（2026-11-11 需求 4）。
    var linkDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于我们") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 2026-11-11 需求 4：右上角新增「链接打开位置」（应用内 / 外部浏览器）。
                    //
                    // 放在扳手**左边**，让原有的「帮助与反馈」仍停在最右侧、位置不变——
                    // 那是一个已经被用熟的入口，不该因为新增一项就被挤走。
                    // 图标用齿轮（2026-11-11 需求 4）：这里放的是**设置项**本身
                    // （链接打开位置），齿轮是全站「设置」的既有语义，比地球更好认。
                    IconButton(onClick = { linkDialogVisible = true }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "链接打开位置：" +
                                if (linkOpenInApp) "应用内网页" else "系统浏览器"
                        )
                    }
                    // 扳手样式的「帮助与反馈」入口。
                    IconButton(onClick = onOpenFeedback) {
                        Icon(Icons.Filled.Build, contentDescription = "帮助与反馈")
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
            // 上方弹性留白：把上簇推到页面中部。
            Spacer(Modifier.weight(1f))

            // ---- 图标 + 软件名称 ----
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_mimo_logo),
                    contentDescription = "应用图标",
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(24.dp))
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "MoreCheck",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // ---- 版本号 ----
            Spacer(Modifier.height(14.dp))
            Text(
                text = "版本：v$versionName",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            // ---- 检查更新 ----
            // 2026-10-28 需求：「检查更新」与「更新日志」整体往下挪一点。
            // 2026-10-29 需求 3：再往下挪一点 → 44dp → 64dp（又下移 20dp，实机 ≈ 60px @3x）。
            //
            // 两行是同一簇（日志紧贴按钮下方），所以只改**这一处**的间距即可一起下移；
            // 间距仍然明显小于「上方弹性留白推开的那一段」，不会被看成另一簇内容。
            Spacer(Modifier.height(64.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onCheckUpdate,
                    modifier = Modifier.width(160.dp)
                ) {
                    Text("检查更新")
                }
            }

            // ---- 更新日志（紧贴检查更新下方）----
            //
            // 2026-11-02 需求：当「检查更新」的去处是**云端安装包下载页**时，
            // 这个入口搬到那一页去（两处都留着等于同一件事有两个门）。
            // 竖间距一起放进条件里：不留"按钮下面空一段"的孤儿留白。
            if (showChangelogLink) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextLink("更新日志", onOpenChangelog)
                    // 一个右向箭头，提示「会进入下一级页面」。
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 下方弹性留白：让下簇落到页面底部。
            Spacer(Modifier.weight(1f))

            // 需求：「关于作者」相对原位**再下移半个字的高度**。
            // 加在它**上面**（而不是改它与下面那行的间距）才能做到「只挪这一个」：
            // 上面是 `weight(1f)` 弹性留白，会自己让出这 7dp，
            // 因此它下移、而下面的「用户协议 / 隐私政策 / 更新日期」原地不动。
            Spacer(Modifier.height(AUTHOR_LINK_OFFSET))

            // ---- 关于作者 / 关于我们（2026-11-11 需求 4：右侧新增「关于我们」）----
            //
            // 与下一行「用户协议 / 隐私政策」**逐个对齐**：两行都是"4 字链接 + 28dp + 4 字链接"，
            // 且同样用 `Arrangement.Center` 居中、`TextLink` 的内边距也相同，
            // 因此两行左右两端会自然落在同一条竖线上，不需要额外给固定宽度。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextLink("关于我们", onOpenOfficialSite)
                Spacer(Modifier.width(28.dp))
                TextLink("意见反馈", onOpenSurvey)
            }

            // ---- 用户协议 / 隐私政策（紧邻更新日期）----
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextLink("用户协议", onOpenAgreement)
                Spacer(Modifier.width(28.dp))
                TextLink("隐私政策", onOpenPrivacy)
            }

            // ---- 更新日期（页面最底部）----
            Spacer(Modifier.height(14.dp))
            Text(
                text = "更新日期：$updateDate",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            // ---- 「支持作者」已移除（2026-11-12）----
            // 位置让给「关于我们」，右侧的「关于我们」让给「意见反馈」；
            // 收款二维码弹窗（SupportQrDialog）连同它的浏览器跳转一并删除。
            Spacer(Modifier.height(16.dp))
        }
    }

    if (linkDialogVisible) {
        LinkOpenModeDialog(
            inApp = linkOpenInApp,
            onPick = { inApp ->
                linkDialogVisible = false
                onLinkOpenInAppChange(inApp)
            },
            onDismiss = { linkDialogVisible = false }
        )
    }
}

/**
 * 「链接打开位置」二选一弹窗（2026-11-11 需求 4）。
 *
 * 两档互斥，选中即生效并关闭（不设"确定"按钮）——只有两个选项、语义直白，
 * 多一步确认只会让人多点一下。文案与开发者选项里那一行保持一致，
 * 用户在两处看到的是同一组词。
 */
@Composable
private fun LinkOpenModeDialog(
    inApp: Boolean,
    onPick: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("链接打开位置") },
        text = {
            Column {
                LinkModeOption(
                    label = "应用内网页",
                    hint = "在 App 的「官网」页里打开",
                    selected = inApp,
                    onClick = { onPick(true) }
                )
                LinkModeOption(
                    label = "系统浏览器",
                    hint = "交给默认浏览器打开",
                    selected = !inApp,
                    onClick = { onPick(false) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun LinkModeOption(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/**
 * 「关于作者」相对原位置**下移**的距离（需求：把按钮向下移动「半个字的高度」）。
 *
 * 「半个字」按链接文字的字号算：[TextLink] 是 **14sp**，半个字高 = **7dp**
 * （默认字体缩放下 1sp = 1dp，所以 14sp 的半个字就是 7dp）。
 *
 * 加在它**上面**而不是改它下面的间距，是为了「只挪这一个」：
 * 上面是 `weight(1f)` 弹性留白，会自己让出这 7dp，
 * 所以它下移、而下面的「用户协议 / 隐私政策 / 更新日期」原地不动。
 *
 * **实测**（1280×2772 截图，按字形像素行测量）：它下移后与「用户协议」那一行的
 * 空隙由约 35dp 收到 **28dp**，正好少 7dp（21px @3x）。
 * 注意两行文字之间的实际空隙远大于「内边距 + 间距」的算术和——
 * 里面还包含 `TextLink` 各自的 4dp 垂直内边距与 14sp 行框的上下留白，
 * 所以这里是**实测值**而不是按 dp 相加算出来的。
 */
private val AUTHOR_LINK_OFFSET = 7.dp

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            // v1.1.72：统一按压反馈。文字链也可以缩小+变淡，
            // 让"这是个可点的东西"在按下的瞬间就有回应（原来只有水波纹，
            // 在 4dp 内边距的小文字上几乎看不出来）。
            .pressScale(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    )
}
