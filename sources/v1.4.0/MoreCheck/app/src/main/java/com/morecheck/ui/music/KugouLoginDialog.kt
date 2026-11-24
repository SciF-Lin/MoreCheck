package com.morecheck.ui.music

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.morecheck.data.music.KugouAccount
import com.morecheck.data.music.KugouAccountStore
import com.morecheck.data.net.KugouAuthedApi
import com.morecheck.data.net.KugouLoginRepository
import com.morecheck.data.net.KugouQr
import com.morecheck.data.net.KugouQrState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 扫码轮询间隔。2 秒是"用户点了确认后基本立刻有反馈"与"别把接口打爆"之间的取值。 */
private const val POLL_INTERVAL_MS = 2_000L

/** 二维码有效期约 2 分钟，轮询上限给到 3 分钟，超时就当过期处理。 */
private const val POLL_MAX_ATTEMPTS = 90

/**
 * 「登录酷狗账号」对话框。
 *
 * ## 为什么是扫码
 *
 * 不接触用户密码：二维码由酷狗接口生成，用户在自己手机的酷狗 App 里确认授权，
 * 我们只拿到一个 token（见 [KugouLoginRepository] 的说明）。
 *
 * ## 登录之后能得到什么
 *
 * 只有"用**你自己的**账号权益去换播放地址"这一件事：会员曲目、部分高音质曲目
 * 在未登录时拿不到直链，登录后能拿到。**不做**收藏同步、不读听歌历史、不写任何数据。
 *
 * ## 状态机
 *
 * `生成二维码 → 等待扫码(1) → 已扫码待确认(2) → 成功(4)`；
 * `0` 是过期（给"刷新二维码"按钮），网络失败给"重试"。
 */
@Composable
fun KugouLoginDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { KugouAccountStore(context) }
    val account by store.account.collectAsStateWithLifecycle(initialValue = null)

    var qr by remember { mutableStateOf<KugouQr?>(null) }
    var state by remember { mutableStateOf<KugouQrState?>(null) }
    var loading by remember { mutableStateOf(false) }

    /**
     * 是否刚在本框里完成扫码登录。
     *
     * 登录成功**不再自动关框**，而是就地切成"完成提示"：只留一个「确定」。
     * 理由是"扫码成功"是个需要被确认的结果，直接消失会让人怀疑到底成没成；
     * 而「退出登录」不该出现在刚登录成功的那一刻——那时用户根本不想退出。
     *
     * 从右上角头像点进来的情况 [justLoggedIn] 恒为 false，看到的是完整账号管理
     * （含退出登录），符合"点右上角显示不变"的要求。
     */
    var justLoggedIn by remember { mutableStateOf(false) }

    /** 申请新二维码。失败只改 [state]，不抛给界面。 */
    fun loadQr() {
        if (loading) return
        loading = true
        state = null
        scope.launch {
            runCatching {
                KugouLoginRepository.requestQr(store.device())
            }.onSuccess { fresh ->
                qr = fresh
                state = KugouQrState.Waiting
            }.onFailure { error ->
                state = KugouQrState.Failed(error.message ?: "二维码获取失败")
            }
            loading = false
        }
    }

    // 打开即出码：多一次点击没有意义，用户点进这个框就是要登录。
    LaunchedEffect(account == null) {
        if (account == null) loadQr()
    }

    // 轮询：key 变了重新开一轮（点"刷新"会换 key）。
    LaunchedEffect(qr?.key) {
        val key = qr?.key ?: return@LaunchedEffect
        val device = store.device()
        repeat(POLL_MAX_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            when (val result = KugouLoginRepository.pollQr(key, device)) {
                is KugouQrState.Success -> {
                    store.saveLogin(
                        userid = result.userid,
                        token = result.token,
                        nickname = result.nickname,
                        avatarUrl = result.avatarUrl,
                        vipType = result.vipType
                    )
                    // 登录后补两件事（都失败也不影响登录本身）：
                    // 1. 把"曲库授权码"换好并缓存，第一首歌不用白等一次往返；
                    // 2. 查一次会员状态——扫码返回里的 vip_type 不可靠，
                    //    查不到就保持中性文案，不要冤枉会员用户。
                    runCatching {
                        val fresh = store.current()
                        if (fresh != null) {
                            KugouAuthedApi.userVerify(fresh)?.let { auth ->
                                store.updateAuth(auth)
                            }
                            val vip = KugouAuthedApi.fetchVipType(fresh)
                            if (vip != null) {
                                store.updateProfile(
                                    nickname = "",
                                    avatarUrl = null,
                                    vipType = vip
                                )
                            }
                        }
                    }
                    // 不关框：切成"登录成功"提示，等用户点「确定」。
                    justLoggedIn = true
                    return@LaunchedEffect
                }
                is KugouQrState.Expired -> {
                    state = result
                    return@LaunchedEffect
                }
                is KugouQrState.Failed -> {
                    state = result
                    return@LaunchedEffect
                }
                else -> state = result
            }
        }
        state = KugouQrState.Expired
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val current = account
                when {
                    // 刚扫码成功：只给一个「确定」，不提供退出。
                    justLoggedIn && current != null -> LoginSuccessContent(
                        account = current,
                        onConfirm = onDismiss
                    )
                    // 从右上角头像点进来：完整账号管理（含带底色的「退出登录」）。
                    current != null -> LoggedInContent(
                        account = current,
                        onLogout = {
                            scope.launch {
                                store.clearLogin()
                                onDismiss()
                            }
                        },
                        onDismiss = onDismiss
                    )
                    else -> LoginContent(
                        qr = qr,
                        state = state,
                        loading = loading,
                        onRefresh = { loadQr() },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/** 未登录：说明 + 二维码 + 状态提示。 */
@Composable
private fun LoginContent(
    qr: KugouQr?,
    state: KugouQrState?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = "登录酷狗账号",
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "用手机酷狗 App 扫码确认。登录只用于用你自己的账号权益换取播放地址，" +
            "凭据仅存本机，不会上传、不会同步。",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(16.dp))

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bytes = remember(qr?.imageBase64) {
            KugouLoginRepository.decodeQrImage(qr?.imageBase64)
        }
        val bitmap = remember(bytes) {
            bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        when {
            loading || (qr == null && state !is KugouQrState.Failed) -> {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "酷狗登录二维码",
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            else -> {
                Text(
                    text = "二维码加载失败",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    val statusText = when (state) {
        is KugouQrState.Waiting -> "等待扫码…"
        is KugouQrState.Scanned -> "已扫码，请在手机上点击「确认登录」"
        is KugouQrState.Expired -> "二维码已过期"
        is KugouQrState.Failed -> "出错了：${state.message}"
        else -> "正在获取二维码…"
    }
    Text(
        text = statusText,
        fontSize = 12.sp,
        color = when (state) {
            is KugouQrState.Expired, is KugouQrState.Failed -> MaterialTheme.colorScheme.error
            is KugouQrState.Scanned -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        },
        textAlign = TextAlign.Center
    )

    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val needRefresh = state is KugouQrState.Expired || state is KugouQrState.Failed
        if (needRefresh) {
            OutlinedButton(onClick = onRefresh, enabled = !loading) {
                Text("刷新二维码")
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onDismiss) {
            Text("关闭")
        }
    }
}

/** 已登录：头像 + 昵称 + 会员标记 + 退出登录。 */
@Composable
private fun LoggedInContent(
    account: KugouAccount,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = "酷狗账号",
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KugouAvatar(account = account, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account.displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                // 只做**肯定**判断：查到会员才写"可用"，查不到就说"已登录"。
                // 写"未检测到会员权益"会在接口没返回 vip_type 时冤枉会员用户。
                text = if (account.isVip) "会员权益：可用" else "账号已登录",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "登录后，会员曲目会用你自己账号的权益换取播放地址。" +
            "收藏仍只存本机，与酷狗账号无关。",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 「退出登录」用**带底色**的按钮（2026-11-08 用户要求）：这是有副作用、
        // 需要被看见的动作，不该和旁边的「关闭」长得一样。
        // 底色用 errorContainer 而不是纯红：红底白字在暗色主题下太刺眼，
        // errorContainer 在明暗两套主题里都能保证对比度。
        Button(
            onClick = onLogout,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text("退出登录")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) {
            Text("关闭")
        }
    }
}

/**
 * 「登录成功」提示：只有一个「确定」。
 *
 * 刻意**不放**「退出登录」：刚扫码成功的那一刻，用户要的是确认结果，
 * 不是退出。需要退出时点右上角头像，那里才是账号管理。
 */
@Composable
private fun LoginSuccessContent(
    account: KugouAccount,
    onConfirm: () -> Unit
) {
    Text(
        text = "登录成功",
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KugouAvatar(account = account, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account.displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (account.isVip) "会员权益：可用" else "账号已登录",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        text = "现在会优先用你自己的账号权益取播放地址。能听哪些歌取决于该账号的权益，" +
            "收藏仍然只存本机。",
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Button(onClick = onConfirm) {
            Text("确定")
        }
    }
}

/**
 * 账号头像（右上角入口与对话框共用）。
 *
 * 没有头像时用昵称首字兜底，**不留空圆圈**——空圆圈会让人以为是加载失败。
 * 尺寸由调用方给：顶栏 32dp、对话框 48dp。
 */
@Composable
fun KugouAvatar(
    account: KugouAccount?,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    // 头像本身没有文字，读屏会什么都读不到；这里补一个语义标签。
    val label = account?.let { "酷狗账号：${it.displayName}" } ?: "酷狗账号"
    val avatar = account?.avatarUrl
    // 图片**加载失败也要兜底成首字**：只判"地址为空"是不够的——
    // 域名失效、被拦、图被删都会让 AsyncImage 停在空白，看起来像界面坏了。
    var loadFailed by remember(avatar) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (avatar.isNullOrBlank() || loadFailed) {
            Text(
                text = account?.displayName?.trim()?.take(1)?.ifBlank { "♪" } ?: "♪",
                fontSize = (size.value * 0.42f).sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        } else {
            AsyncImage(
                model = avatar,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) loadFailed = true
                }
            )
        }
    }
}
