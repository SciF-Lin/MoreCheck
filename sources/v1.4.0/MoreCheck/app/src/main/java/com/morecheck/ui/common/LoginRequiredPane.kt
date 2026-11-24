package com.morecheck.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 「登录后可用」门禁面板（2026-11-03，v1.2.5：热搜改为登录后可用）。
 *
 * 给**登录后才允许使用**的功能页当占位内容：锁定图标 + 一句话说明 + 「去登录」。
 * 登录成功后宿主路由回退（登录页的既有行为），本面板随登录态翻转被替换成真页面。
 *
 * 为什么做成公共组件：热搜是第一个登录门禁页，但不会是最后一个
 * （云备份、同步管理各自有更复杂的引导卡，不适合合并），后续再有
 * "登录后可用"的页面直接挂它，文案风格与跳转口径保持一致。
 *
 * @param featureName 功能名，拼进说明文案（如「热搜」）。
 * @param onLogin 跳转登录页。
 */
@Composable
fun LoginRequiredPane(
    featureName: String,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "$featureName 需登录后使用",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "登录云账号即可使用$featureName，数据也会同步保存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onLogin) { Text("去登录") }
        }
    }
}
