package com.morecheck.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
 * 2. 上簇（居页面中部）：应用图标 + 软件名称 → 版本号 → `检查更新` → **`更新日志 ›`**；
 * 3. 下簇（贴页面底部）：**`关于作者`**（点开显示收款二维码，二维码下方带「支持作者」文字链）
 *    → 用户协议 / 隐私政策 → 更新日期。
 *
 * ⚠️ 「支持作者」本轮从本页左下角**移入**「关于作者」弹窗内，因此下簇不再有独立的那一行。
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
    onOpenFeedback: () -> Unit = {},
    /** 「支持作者」占位入口：位于「关于作者」二维码弹窗内，点了只弹提示，没有真实功能。 */
    onOpenSupport: () -> Unit = {}
) {
    val context = LocalContext.current
    val versionName = remember { AppInfo.versionName(context) }
    val updateDate = remember { AppInfo.lastUpdateDate(context) }
    // 「关于作者」改为就地弹出收款二维码，不再走全局提示。
    var qrVisible by remember { mutableStateOf(false) }

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
            Spacer(Modifier.height(28.dp))
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

            // ---- 更新日志（新增，紧贴检查更新下方）----
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

            // 下方弹性留白：让下簇落到页面底部。
            Spacer(Modifier.weight(1f))

            // 需求：「关于作者」相对原位**再下移半个字的高度**。
            // 加在它**上面**（而不是改它与下面那行的间距）才能做到「只挪这一个」：
            // 上面是 `weight(1f)` 弹性留白，会自己让出这 7dp，
            // 因此它下移、而下面的「用户协议 / 隐私政策 / 更新日期」原地不动。
            Spacer(Modifier.height(AUTHOR_LINK_OFFSET))

            // ---- 关于作者（点开显示收款二维码）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextLink("关于作者") { qrVisible = true }
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

            // ---- 「支持作者」已移入「关于作者」二维码弹窗 ----
            //
            // v1.1.69 曾把它作为本页左下角的独立一行（弱化为占位）。本轮按需求**移进弹窗**：
            // 语义上它原本就是「关于作者」这簇内容的一部分，放在页面底部贴边反而与上面
            // 三行居中排列的元素割裂；收进弹窗后，「看作者 → 想支持」这条动线在同一处完成。
            // 因此这里**只保留页面自身的底部留白**，不再有那一行。
            Spacer(Modifier.height(16.dp))
        }
    }

    if (qrVisible) {
        SupportQrDialog(
            onDismiss = { qrVisible = false },
            onOpenSupport = onOpenSupport
        )
    }
}

/**
 * 「关于作者」收款二维码弹窗。
 *
 * 二维码是张位图，**刻意不加任何自绘白底或描边**：图片本身就是白底，
 * 任何叠加层都可能改变模块边缘的对比度而被扫码器拒绝。
 *
 * ## 「支持作者」为什么放在这里（本轮改动）
 *
 * 它原先在「关于我们」页面左下角独立成行。移进本弹窗的理由：
 * 1. **语义归属**——「支持作者」本来就是「关于作者」这簇内容的一部分，
 *    贴在页面底部反而与上面居中排列的「关于作者 / 协议 / 更新日期」割裂；
 * 2. **动线连贯**——用户在这里刚看完二维码与说明，紧接着就是"想支持"的决定点，
 *    不需要退出去再找一个入口。
 *
 * ## 位置：与「关闭」按钮**左右对称**（v1.1.69 需求 1）
 *
 * 上一版把它放在内容区末尾、靠左对齐，结果是"贴着二维码、离按钮行很远"，
 * 在一屏里显得孤立（用户截图圈出的正是这个位置）。
 * 现在改为**按钮行内的左侧**：与右侧的「关闭」同处一行、各占一端，
 * 中间由 `Spacer(weight(1f))` 撑开。这样两件"离开这个弹窗"的动作在同一条基线
 * 上对称排布——左「支持作者」（正向反馈）、右「关闭」（中性收起），
 * 视觉重心稳定，也符合对话框按钮区"一左一右"的通用版式。
 *
 * ⚠️ 它仍然**不是** `confirmButton` 槽位里的按钮：`AlertDialog` 的按钮槽位
 * 由 Material 统一排布，塞两个不同语义的控件进去会破坏它的既定间距与对齐。
 * 所以这里把两样东西都放进**同一个自定义 Row** 里、交给 `confirmButton` 承载整行，
 * 由我们自己控制对称关系。
 *
 * @param onDismiss 关闭弹窗。
 * @param onOpenSupport 点「支持作者」。仍是**仅占位**：由调用方（`TabNavHost`）弹一句提示，
 *   本组件不自己弹、也不新增路由（本轮改动只搬位置，不搬行为）。
 */
@Composable
private fun SupportQrDialog(
    onDismiss: () -> Unit,
    onOpenSupport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于作者") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_support_qr),
                    contentDescription = "关于作者二维码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "扫码请作者喝杯水，感谢支持",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            // 整行按钮区：左「支持作者」、右「关闭」，中间弹性撑开（见 KDoc 的对称说明）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextLink("支持作者") { onOpenSupport() }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
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
