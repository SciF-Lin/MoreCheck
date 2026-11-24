package com.morecheck.ui.music

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.morecheck.data.music.MusicVipDiagnostics
import com.morecheck.data.music.VipProbeCheck
import com.morecheck.data.music.VipProbeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「音乐设置 → 会员音乐播放检测」（2026-11-14）。
 *
 * 点击后按真实链路逐项检测（网络 / 搜索 / 公开取链 / 账号 / 授权码 / 会员类型 /
 * 受限曲目取链），逐条回显结果并生成**可查看、可复制**的纯文本报告日志。
 * 报告不含 token / userid / auth；「账号过期」是依据接口返回的推测，不会写成定论
 * （服务端不提供会员到期时间，详见 [MusicVipDiagnostics]）。
 *
 * 样式沿用音乐页的 [GlassCard] + [rememberMusicPalette]，不引入 Material 卡片，
 * 因为「我的」段与「设置 → 音乐」独立路由共用 [MusicAccountContent]，
 * 两处都会看到这张卡。
 */
@Composable
fun MusicVipProbeCard(onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    val palette = rememberMusicPalette()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<VipProbeCheck>>(emptyList()) }
    var report by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }

    fun startProbe() {
        if (probing) return
        probing = true
        checks = emptyList()
        report = ""
        scope.launch {
            val text = runCatching {
                MusicVipDiagnostics.run(context) { check ->
                    withContext(Dispatchers.Main) { checks = checks + check }
                }
            }.getOrElse { e ->
                "检测过程异常：${e.message ?: "未知错误"}\n\n请检查网络后重试。"
            }
            report = text
            probing = false
            val pass = checks.count { it.state == VipProbeState.OK }
            onMessage("检测完成：通过 $pass/${checks.size} 项，详见报告")
        }
    }

    fun copyReport() {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            onMessage("复制失败：剪贴板不可用")
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText("会员音乐检测报告", report))
        onMessage("检测报告已复制")
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "会员音乐播放检测",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = palette.text
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "逐项检查网络、音源、账号登录态、会员权益与会员曲目取链，" +
                    "生成可复制的报告日志。会发起少量真实请求，请勿连续点击。",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSub
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProbeActionButton(
                    text = if (probing) "检测中…（${checks.size}）" else "开始检测",
                    primary = true,
                    enabled = !probing,
                    onClick = { startProbe() }
                )
                if (report.isNotEmpty()) {
                    ProbeActionButton(
                        text = "查看报告",
                        primary = false,
                        enabled = true,
                        onClick = { showReport = true }
                    )
                    ProbeActionButton(
                        text = "复制",
                        primary = false,
                        enabled = true,
                        onClick = { copyReport() }
                    )
                }
            }

            if (checks.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                checks.forEach { check ->
                    ProbeResultRow(check)
                    Spacer(Modifier.height(6.dp))
                }
                val pass = checks.count { it.state == VipProbeState.OK }
                Text(
                    text = "通过 $pass/${checks.size} 项 · 「注意」表示条件不足或接口未返回，" +
                        "不代表一定能/不能播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textMuted
                )
            }
        }
    }

    if (showReport && report.isNotEmpty()) {
        Dialog(onDismissRequest = { showReport = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.surface)
                    .padding(18.dp)
            ) {
                Text(
                    text = "检测报告",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.text
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = report,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.textSub
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProbeActionButton("复制报告", primary = false, enabled = true) {
                        copyReport()
                    }
                    ProbeActionButton("关闭", primary = true, enabled = true) {
                        showReport = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeActionButton(
    text: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = rememberMusicPalette()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) palette.accent else palette.glass)
            .border(1.dp, if (primary) palette.accent else palette.glassBorder, RoundedCornerShape(10.dp))
            .bounceClick(onClick = onClick, enabled = enabled)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> palette.textMuted
                primary -> palette.onAccent
                else -> palette.text
            }
        )
    }
}

@Composable
private fun ProbeResultRow(check: VipProbeCheck) {
    val palette = rememberMusicPalette()
    val (label, color) = when (check.state) {
        VipProbeState.OK -> "通过" to Color(0xFF16A34A)
        VipProbeState.WARN -> "注意" to Color(0xFFEA580C)
        VipProbeState.FAIL -> "失败" to Color(0xFFEF4444)
        VipProbeState.SKIP -> "跳过" to Color(0xFF94A3B8)
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(label, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "${check.name} · ${check.elapsedMs}ms",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.text
            )
            Text(
                text = check.detail,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = palette.textSub
            )
        }
    }
}
