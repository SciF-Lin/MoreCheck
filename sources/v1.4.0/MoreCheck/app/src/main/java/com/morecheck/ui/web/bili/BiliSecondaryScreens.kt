package com.morecheck.ui.web.bili

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morecheck.R
import com.morecheck.data.net.BiliLibraryStore
import com.morecheck.data.net.BilibiliRepository.BiliVideoItem
import com.morecheck.di.appContainer
import kotlinx.coroutines.launch

/**
 * B 站二级页集合（2026-11-18 界面改造新增）：
 * - [BiliLibraryPage]：观看历史 / 稍后再看 / 我的收藏，从「我的」点行进入的独立页；
 * - [BiliSettingsPage]：B 站设置（自选主题色 + 赞助跳过开关）。
 *
 * 为什么独立成页而不是在「我的」里内联展开：内联展开会把列表塞进个人中心的滚动流，
 * 越往下越难回到功能入口，也没有独立的返回/标题语义。二级页与播放页/下载页同一套
 * 路由语汇（侧向推入 + 返回），层级更清楚。
 */

// ---------------------------------------------------------------------------
// 观看历史 / 稍后再看 / 我的收藏
// ---------------------------------------------------------------------------

@Composable
internal fun BiliLibraryPage(
    initialTab: Int,
    onBack: () -> Unit,
    onOpenVideo: (BiliVideoItem) -> Unit
) {
    BiliTheme {
        val c = biliColors()
        val context = LocalContext.current
        var tab by remember { mutableIntStateOf(initialTab) }
        val history by BiliLibraryStore.historyOf(context).collectAsState(initial = emptyList())
        val watchLater by BiliLibraryStore.watchLaterOf(context).collectAsState(initial = emptyList())
        val favorites by BiliLibraryStore.favoritesOf(context).collectAsState(initial = emptyList())

        BackHandler { onBack() }

        Column(Modifier.fillMaxSize().background(c.page).statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.bili_ic_back),
                        "返回",
                        tint = c.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    "我的列表",
                    color = c.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("观看历史", "稍后再看", "我的收藏").forEachIndexed { i, label ->
                    val on = tab == i
                    Text(
                        label,
                        color = if (on) Color.White else c.chipText,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (on) c.pink else c.chipBg)
                            .clickable { tab = i }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            val list = when (tab) {
                0 -> history
                1 -> watchLater
                else -> favorites
            }
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这里还是空的", color = c.textHint, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.bvid }) { item ->
                        BiliVideoCard(item = item, onClick = { onOpenVideo(item) })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// B 站设置
// ---------------------------------------------------------------------------

@Composable
internal fun BiliSettingsPage(
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    BiliTheme {
        val c = biliColors()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val accent by BiliLibraryStore.biliAccentOf(context).collectAsState(initial = 0)
        val sponsorSkip by BiliLibraryStore.sponsorSkipOf(context).collectAsState(initial = true)
        // 进入B站温馨提示的「不再提示」标记（2026-11-19 需求 3）：
        // 界面用正向表述「进入B站时确认」，checked = 取反，与音乐设置同口径。
        val settingsRepository = remember(context) {
            context.applicationContext.appContainer.settingsRepository
        }
        val biliTipDismissed by settingsRepository.biliUiTipDismissed
            .collectAsState(initial = false)

        BackHandler { onBack() }

        Column(
            Modifier
                .fillMaxSize()
                .background(c.page)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(start = 4.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painterResource(R.drawable.bili_ic_back),
                        "返回",
                        tint = c.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    "B站设置",
                    color = c.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))

            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.height(14.dp))
                Text("自选主题色", color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "只影响 B 站模块的品牌色（顶栏 / 选中态 / 强调图标），不影响应用其它部分",
                    color = c.textHint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(8.dp))
                BILI_ACCENT_PALETTE.chunked(4).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { (label, argb) ->
                            val on = accent == argb
                            val dot = if (argb == 0) Color(0xFFFB7299) else Color(argb)
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (on) c.pinkBg else c.surface)
                                    .border(
                                        width = if (on) 2.dp else 1.dp,
                                        color = if (on) c.pink else c.divider,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        scope.launch { BiliLibraryStore.setBiliAccent(context, argb) }
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape).background(dot)
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    label,
                                    color = if (on) c.pink else c.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            }
                        }
                        // 末行不足 4 个时补空位，保持网格对齐。
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
                Spacer(Modifier.height(6.dp))

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("赞助片段跳过", color = c.textPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "自动跳过片头 / 赞助等片段（数据来自公共镜像 bsbsb.top）",
                            color = c.textHint,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Switch(
                        checked = sponsorSkip,
                        onCheckedChange = { next ->
                            scope.launch { BiliLibraryStore.setSponsorSkip(context, next) }
                            onMessage(if (next) "已开启赞助跳过" else "已关闭赞助跳过")
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.pink)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("进入B站时确认", color = c.textPrimary, fontSize = 15.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "开启后每次进入B站视频先弹温馨提示（权限 / 联网 / 测试中说明）",
                            color = c.textHint,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Switch(
                        checked = !biliTipDismissed,
                        onCheckedChange = { next ->
                            scope.launch { settingsRepository.setBiliUiTipDismissed(!next) }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.pink)
                    )
                }
            }
        }
    }
}