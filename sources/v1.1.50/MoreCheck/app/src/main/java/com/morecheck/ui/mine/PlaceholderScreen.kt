package com.morecheck.ui.mine

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 通用空占位子页（任务3）。
 *
 * 用于「数据备份」「帮助与反馈」这类占位条目：
 * **点开无实际内容**，但层级完整（有返回箭头、可回退），
 * 且结构与设置类子页保持一致——同为「TopAppBar + 16dp 边距卡片」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmptyPlaceholderScreen(
    title: String,
    description: String,
    onBack: () -> Unit
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Construction,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "内容暂未实现",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * 纯文本条款页（用户协议 / 隐私政策）。
 *
 * 内容为本地静态文案，**不涉及真实账号与联网**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalTextScreen(
    title: String,
    body: String,
    onBack: () -> Unit
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 用户协议静态文案。 */
const val AGREEMENT_TEXT: String = """
MoreCheck 用户协议（本地模拟）

1. 本应用为设备本地打卡工具，不提供账号体系、云端同步或任何联网服务。
2. 全部数据（任务、打卡记录、设置项）仅保存在本机，卸载应用即会清除。
3. 登录功能为本地模拟演示，不发送真实短信、不上传手机号、不连接后端。
4. 您在本机产生的数据由您自行负责，建议定期自行备份。
5. 本协议内容仅用于界面演示，不构成任何法律意义上的约定。
"""

/** 隐私政策静态文案。 */
const val PRIVACY_TEXT: String = """
MoreCheck 隐私政策（本地模拟）

1. 本应用不收集、不上传、不共享任何个人信息。
2. 不申请存储、位置、通讯录等无关权限；仅使用通知权限以推送本地提醒。
3. 登录页输入的手机号仅用于本地模拟校验，不会离开本机。
4. 应用不含任何第三方 SDK、统计或广告组件。
5. 本政策内容仅用于界面演示，不构成任何法律意义上的约定。
"""
