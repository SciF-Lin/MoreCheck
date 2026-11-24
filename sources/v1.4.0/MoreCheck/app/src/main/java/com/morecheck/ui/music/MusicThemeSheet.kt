package com.morecheck.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.data.music.MusicThemeMode
import com.morecheck.data.music.MusicThemeStore
import com.morecheck.data.music.MusicThemeStyle

/**
 * 主题选择器（2026-11-08 设计稿需求 3）。
 *
 * 三套风格 × 明暗三档，点一下立刻生效——[MusicThemeStore] 先更新 StateFlow 再落盘，
 * 所以没有「点了要等一下才变色」的迟滞。
 *
 * [MusicThemeControls] 同时被播放页的底部弹层和「音乐账号」页的设置卡复用，
 * 两处入口改的是同一份设置，不会出现「这边换了那边没换」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicThemeSheet(onDismiss: () -> Unit) {
    val palette = rememberMusicPalette()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "音乐主题",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = palette.text
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "三套独立风格，每套都支持浅色与深色；只影响音乐界面。",
                fontSize = 11.sp,
                color = palette.textMuted
            )
            Spacer(Modifier.height(18.dp))
            MusicThemeControls()
        }
    }
}

/** 风格卡 + 明暗分段；底部弹层与账号页共用。 */
@Composable
fun MusicThemeControls(modifier: Modifier = Modifier) {
    val palette = rememberMusicPalette()
    val spec by MusicThemeStore.spec.collectAsStateWithLifecycle()

    Column(modifier.fillMaxWidth()) {
        Text(
            text = "风格",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textSub
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MusicThemeStyle.entries.forEach { style ->
                ThemeStyleCard(
                    style = style,
                    selected = spec.style == style,
                    modifier = Modifier.weight(1f),
                    onClick = { MusicThemeStore.setStyle(style) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "明暗",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textSub
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(palette.glass)
                .border(1.dp, palette.glassBorder, RoundedCornerShape(50))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MusicThemeMode.entries.forEach { mode ->
                val selected = spec.mode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) {
                                Brush.horizontalGradient(
                                    listOf(palette.accentLight, palette.accentDark)
                                )
                            } else {
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                            }
                        )
                        .bounceClick(onClick = { MusicThemeStore.setMode(mode) })
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.onAccent else palette.textSub
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "跟随系统会随手机夜间模式自动切换；也可以固定浅色或深色。",
            fontSize = 10.sp,
            color = palette.textMuted
        )
    }
}

/** 一张风格卡：渐变色球 + 名称，选中时描主题色亮边。 */
@Composable
private fun ThemeStyleCard(
    style: MusicThemeStyle,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberMusicPalette()
    val accent = musicStyleAccent(style)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.glass)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) palette.accent else palette.glassBorder,
                shape = RoundedCornerShape(18.dp)
            )
            .bounceClick(onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.55f),
                                accent,
                                accent.copy(alpha = 0.72f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = style.label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) palette.accent else palette.text
            )
            Text(
                text = style.english,
                fontSize = 9.sp,
                color = palette.textMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}
