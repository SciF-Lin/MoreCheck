package com.morecheck.ui.lab.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.strongTextColor

/**
 * 「实验室 → 工具」四个页面（随机数 / 摩斯电码 / 图片压缩 / 做个决定）共用的页面骨架。
 *
 * ## 为什么抽出来
 *
 * 四个页面是同一类东西：独立二级页 + 可滚动的内容区 + 若干卡片。若各自照抄一份
 * `Scaffold` + `TopAppBar`，返回箭头、滚动、键盘让位这三件事就有了四份实现——
 * 以后改其中一处（比如统一标题字号）必然漏掉另外三处。
 * 这与本项目「一屏一个自洽实现」的取舍不同：那是针对**结构差异大**的游戏页，
 * 而这里是四个结构几乎相同的表单页，共用骨架反而更安全。
 *
 * ## 键盘让位为什么放在骨架里
 *
 * 四个页面都有输入框（最小值/最大值、摩斯输入、候选项），键盘弹起时都必须整体上移。
 * `imePadding()` 放在 `innerPadding` 之后、`verticalScroll` 之前：
 * 先让出标题栏与系统栏，再把可用高度减去键盘高度，最后滚动区在这个已经缩小的
 * 视口里滚动——这样「输入框滚到键盘上方」是自动的，页面自己不用再算一次。
 *
 * @param title 标题栏文字。
 * @param onBack 返回箭头回调；系统返回键由各页自己注册（本骨架不接管，避免
 *   与宿主页的返回逻辑重复注册）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            content()
            // 底部留白：内容贴底时最后一张卡片会与系统手势条挤在一起。
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 工具页里的分组卡片。
 *
 * 与项目其它二级页一致：`Card` 承载一个逻辑分组，标题用 `strongTextColor`
 * （浅色模式纯黑、深色模式沿用 onSurface），而不是自造一个颜色常量。
 *
 * @param title 卡片标题；传 null 表示这块内容不需要标题（如结果展示卡）。
 */
@Composable
internal fun ToolCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.strongTextColor
                )
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/**
 * 次要说明文字：统一用 `onSurface` 的 75% 而不是 `onSurfaceVariant`。
 *
 * 理由见 `SettingsPage` 的同类注释：variant 在浅色下偏灰、深色下偏灰白，
 * 两种模式下都比 75% 的 onSurface 更模糊；而这四个工具页的说明文字承担
 * 「参数含义」的解释职责，必须读得清。
 */
@Composable
internal fun ToolHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = modifier
    )
}
