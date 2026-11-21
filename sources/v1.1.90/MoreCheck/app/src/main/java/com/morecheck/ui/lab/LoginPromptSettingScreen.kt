package com.morecheck.ui.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.ui.LocalSettingsViewModel
import com.morecheck.ui.theme.strongTextColor

/**
 * 「实验室 → 登录提示功能」。
 *
 * 需求：把「打开软件时提示登录」那个弹窗加一个开关。
 *
 * ## 它管的到底是哪一个弹窗
 *
 * 本应用的启动流程有两层，**只有第二层受本开关控制**：
 * 1. **强制同意**《用户协议》《隐私政策》（`PrivacyConsentGate`）——
 *    那是合规门闸，不同意就退出应用，**任何开关都不该也不能关掉它**；
 * 2. 同意之后弹一次「欢迎使用 MoreCheck / 去登录 / 暂不」（`LoginPromptDialog`）——
 *    这才是本页控制的「登录提示」。它本来就是**可以被用户点「暂不」关掉**的引导，
 *    给它一个持久开关只是把那件事变成「以后别再问了」。
 *
 * 这条边界写在页面上（见下方说明文案），否则用户会以为关掉它能跳过协议。
 *
 * ## 状态从哪来
 *
 * 复用 `MainActivity` 建好的那一份 [LocalSettingsViewModel]（与「设置 → 通用」里的
 * 自启动开关同一个来源），因此在这里拨开关，下次启动立刻生效，不需要重启。
 * 不自己新建 ViewModel：会多出一份状态，正是本项目历史上「开关关不掉」的成因
 * （同一份设置被两处实例各存一份）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginPromptSettingScreen(
    onBack: () -> Unit,
    viewModel: com.morecheck.ui.settings.SettingsViewModel = LocalSettingsViewModel.current
) {
    val enabled by viewModel.loginPromptEnabled.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录提示功能") },
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "打开软件时提示登录",
                            style = MaterialTheme.typography.bodyLarge,
                            // 与「权限管理」「设置」的开关行同口径（浅色模式纯黑）。
                            color = MaterialTheme.strongTextColor
                        )
                        Text(
                            text = "关闭后不再弹出「欢迎使用 MoreCheck」的登录引导",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { viewModel.setLoginPromptEnabled(it) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                // 说清「能关什么、不能关什么」，这是本页最容易被误解的一点。
                text = "关闭的只是这条登录引导；首次启动的《用户协议》《隐私政策》" +
                    "同意弹窗不受影响——那是必须同意的，不同意会退出应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
