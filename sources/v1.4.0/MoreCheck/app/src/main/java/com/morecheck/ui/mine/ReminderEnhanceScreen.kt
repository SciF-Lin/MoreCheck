package com.morecheck.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「打卡提醒增强」二级页（2026-11-08 需求 5）。
 *
 * 内容仍是 [ReminderEnhanceSection]（静默时段 / 漏打卡补推），一行不改；
 * 变的只是**位置**：原来它作为一张可折叠卡挤在设置首页的中段，用户要划两屏才看到，
 * 而且与「个性化」两张卡并排、彼此抢注意力。现在设置页只留一行入口（副标题汇总
 * 当前状态），细节进这一页看。
 *
 * 卡片在这里**默认展开**（[ExpandableSectionCard] 的初值就是 true）：既然用户是
 * 专门点进来调这一项的，再让他点一次「展开」是多余的一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderEnhanceScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("打卡提醒增强") },
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
            ReminderEnhanceSection(Modifier.fillMaxWidth(), collapsible = false)
            Spacer(Modifier.height(24.dp))
        }
    }
}
