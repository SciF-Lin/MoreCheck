package com.morecheck.ui.cloud

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.ui.AppViewModelProvider
import com.morecheck.ui.backup.BackupSection
import com.morecheck.ui.backup.BackupViewModel

/**
 * 「数据与同步」页（2026-11-08 需求 5：把「同步管理」与「数据备份」两个入口合并）。
 *
 * ## 两个页面为什么该合并
 *
 * 拆开时它们讲的是**同一批数据的两条轨道**，却各自带一半信息：
 *
 * - 「同步管理」列了"整包备份在数据备份页"，用户看完还得再找一遍；
 * - 「数据备份」里又有"云端备份 / 从云端恢复"，与「同步管理」的账号、开关、立即同步
 *   分处两屏——换机时最需要"先确认登录、再整包恢复"，正好被拆成两次进出。
 *
 * 合并后仍然分两块，但**同页可切**：上面两个标签，左边是「云同步」（账号 / 立即同步 /
 * 自动同步开关 / 同步内容），右边是「本机备份」（导出 / 恢复 / 云端整包 / CSV 周报 /
 * 清空）。两条轨道的关系一眼可见，不必来回跳。
 *
 * ## 为什么用标签而不是一路往下滚
 *
 * 两块内容都很长（云同步四张卡、本机备份六张卡），拼成一页会有十几屏，
 * 用户要滚很久才能到"另一个用途"的入口。标签把"我现在在办哪件事"显式化，
 * 切换成本只有一次点击，且状态保留（[rememberSaveable]）。
 *
 * ## 目录约定
 *
 * 本页是**唯一**的数据出口：设置页只有一行「同步与备份」入口指向这里；
 * 老的两条路由（同步管理 / 数据备份）继续可用，只是各自打开对应的标签，
 * 这样「我的信息」页里的历史入口不会失效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSyncScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onMessage: (String) -> Unit,
    /** 从哪一块进来：0 = 云同步（默认），1 = 本机备份。 */
    initialTab: Int = 0,
    backupViewModel: BackupViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    // 用 rememberSaveable：切标签后转屏 / 从登录页回来，停在原标签上。
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    val tabs = remember { listOf("云同步", "本机备份") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据与同步") },
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
        ) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) }
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (tab) {
                    0 -> CloudSyncSection(
                        onOpenLogin = onOpenLogin,
                        onMessage = onMessage
                    )

                    else -> BackupSection(
                        onMessage = onMessage,
                        onOpenLogin = onOpenLogin,
                        viewModel = backupViewModel
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 供旧路由复用的两个入口（标签落点固定）。 */
@Composable
fun DataSyncScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
    onMessage: (String) -> Unit,
    openBackupTab: Boolean
) = DataSyncScreen(
    onBack = onBack,
    onOpenLogin = onOpenLogin,
    onMessage = onMessage,
    initialTab = if (openBackupTab) 1 else 0
)
