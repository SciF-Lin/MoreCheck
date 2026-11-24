package com.morecheck.data.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.morecheck.data.net.KugouAuthedApi
import com.morecheck.data.net.MusicRepository
import com.morecheck.data.net.MusicSource
import com.morecheck.data.net.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单项检测的结论。SKIP 表示条件不满足（例如未登录时跳过授权检查）。 */
enum class VipProbeState { OK, WARN, FAIL, SKIP }

/** 一行检测结果：`state` 决定徽标颜色，`detail` 是可直接给用户的解释。 */
data class VipProbeCheck(
    val name: String,
    val state: VipProbeState,
    val detail: String,
    val elapsedMs: Long
)

/**
 * 「能否正常播放会员音乐」的逐项检测（2026-11-14）。
 *
 * ## 检测什么
 *
 * 会员曲目在酷狗公开 `playInfo` 上返回**空 url 而不是错误**，只能靠登录态三跳
 * （`user_verify` → `authorization` → `tracker/url`）取链。因此按真实链路逐段测：
 * 网络 → 搜索 → 公开取链（同时挑出一首疑似会员/版权受限样本）→ 账号登录态 →
 * 曲库授权码/登录态有效性 → 会员类型 → 该样本的登录态取链 → 综合结论。
 *
 * ## 诚实边界（报告里也会写）
 *
 * - `KugouAccount` 没有到期时间字段，`get_union_vip` 只返回 `vip_type`，
 *   **服务端不提供会员有效期**，所以「账号过期」只能由 `user_verify` 拿不到授权码
 *   或三跳全失败**间接推测**，报告会明确标注为推测。
 * - 曲目没有 VIP 标记，只能用「公开接口取不到链」筛疑似样本，
 *   取链失败也可能是版权 / 接口变更 / 签名失效，不能断言一定是会员限制。
 * - 报告**不输出 token / userid / auth / Cookie**，只写状态、耗时与错误摘要。
 *
 * 检测会真实发起少量网络请求（最多 6 首样本），由调用方用 `probing` 防重入。
 */
object MusicVipDiagnostics {

    suspend fun run(
        context: Context,
        onStep: suspend (VipProbeCheck) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val checks = mutableListOf<VipProbeCheck>()
        val startedAt = System.currentTimeMillis()

        suspend fun add(
            name: String,
            state: VipProbeState,
            detail: String,
            started: Long
        ) {
            val check = VipProbeCheck(
                name = name,
                state = state,
                detail = detail,
                elapsedMs = System.currentTimeMillis() - started
            )
            checks += check
            onStep(check)
        }

        // ---- 1. 网络 ----
        var t = System.currentTimeMillis()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkOk = cm?.activeNetwork?.let { net ->
            cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: true
        add(
            "网络连接",
            if (networkOk) VipProbeState.OK else VipProbeState.FAIL,
            if (networkOk) "系统报告存在可用网络" else "系统报告无可用网络，请检查 Wi-Fi / 移动数据",
            t
        )

        // ---- 2. 搜索链路（直接复用正式音源，不另写探测接口）----
        t = System.currentTimeMillis()
        val searchResult = runCatching { MusicRepository.searchKugou("周杰伦 晴天", 8) }
        val tracks: List<MusicTrack> = searchResult.getOrDefault(emptyList())
        add(
            "音源搜索",
            if (tracks.isNotEmpty()) VipProbeState.OK else VipProbeState.FAIL,
            if (tracks.isNotEmpty()) {
                "酷狗搜索返回 ${tracks.size} 首样本曲目"
            } else {
                "搜索失败或返回空：${searchResult.exceptionOrNull()?.message ?: "接口无结果"}"
            },
            t
        )

        // ---- 3. 公开取链 + 挑一首「公开接口拿不到链」的疑似会员样本 ----
        t = System.currentTimeMillis()
        var freeUrl: String? = null
        var restricted: MusicTrack? = null
        for (track in tracks.take(6)) {
            if (track.source != MusicSource.KUGOU) continue
            val url = runCatching { MusicRepository.kugouPlayUrl(track.playKey) }.getOrNull()
            if (url.isNullOrBlank()) {
                if (restricted == null) restricted = track
            } else if (freeUrl == null) {
                freeUrl = url
            }
            if (freeUrl != null && restricted != null) break
        }
        add(
            "公开取链（免费曲目）",
            if (freeUrl != null) VipProbeState.OK else VipProbeState.WARN,
            if (freeUrl != null) {
                "公开接口能取到直链，普通歌曲可播"
            } else {
                "本次样本里没有取到公开直链（可能网络异常或接口变更）"
            },
            t
        )

        // ---- 4. 账号登录态 ----
        t = System.currentTimeMillis()
        val store = KugouAccountStore(context)
        val account = runCatching { store.current() }.getOrNull()
        val loggedIn = account != null
        add(
            "账号登录态",
            if (loggedIn) VipProbeState.OK else VipProbeState.WARN,
            if (loggedIn) {
                "已登录酷狗账号（${account?.displayName ?: "未知昵称"}）；凭据字段完整"
            } else {
                "未登录账号：会员曲目只能走公开接口，通常会取不到直链。请在上方扫码登录"
            },
            t
        )

        // ---- 5. 曲库授权码 / 登录态有效性 ----
        var authOk = false
        var vipType: Int? = null
        if (account == null) {
            add("曲库授权码", VipProbeState.SKIP, "未登录，跳过授权检查", System.currentTimeMillis())
            add("会员权益", VipProbeState.SKIP, "未登录，无法查询会员类型", System.currentTimeMillis())
        } else {
            t = System.currentTimeMillis()
            val auth = runCatching { KugouAuthedApi.userVerify(account) }.getOrNull()
            authOk = !auth.isNullOrBlank()
            add(
                "曲库授权码",
                if (authOk) VipProbeState.OK else VipProbeState.FAIL,
                if (authOk) {
                    "user_verify 返回授权码，登录凭据目前有效"
                } else {
                    "user_verify 未返回授权码：token 可能已过期 / 登录态失效（推测），建议重新扫码登录"
                },
                t
            )

            t = System.currentTimeMillis()
            vipType = runCatching { KugouAuthedApi.fetchVipType(account) }.getOrNull()
            add(
                "会员权益",
                when {
                    vipType == null -> VipProbeState.WARN
                    (vipType ?: 0) > 0 -> VipProbeState.OK
                    else -> VipProbeState.WARN
                },
                when {
                    vipType == null -> "未查到会员类型（接口未返回）；不视为非会员。服务端不提供到期时间"
                    (vipType ?: 0) > 0 -> "会员权益可用（vipType=$vipType）；接口不返回会员到期时间，无法判断是否过期"
                    else -> "账号已登录但未查到会员权益（vipType=0）：会员曲目可能无法播放"
                },
                t
            )
        }

        // ---- 6. 疑似会员曲目的完整取链 ----
        // 取一份 val 快照：下面要在 lambda 里用它，var 的智能转换会失效。
        val sample = restricted
        var restrictedOk = false
        if (sample == null) {
            add(
                "会员曲目取链",
                VipProbeState.SKIP,
                "本次样本里没有「公开接口取不到链」的曲目，未检出疑似会员/版权受限样本",
                System.currentTimeMillis()
            )
        } else {
            t = System.currentTimeMillis()
            val url = runCatching {
                MusicRepository.resolvePlayUrl(sample, account)
            }.getOrNull()
            restrictedOk = !url.isNullOrBlank()
            add(
                "会员曲目取链",
                if (restrictedOk) VipProbeState.OK else VipProbeState.FAIL,
                if (restrictedOk) {
                    "《${sample.name}》通过登录态/回落链路取到了直链"
                } else {
                    "《${sample.name}》取不到直链：可能未登录、会员权益不足、登录态失效、版权或接口变更"
                },
                t
            )
        }

        // ---- 7. 综合结论 ----
        val conclusion = when {
            !loggedIn -> "当前未登录酷狗账号，无法播放需要会员权益的曲目；免费曲目仍可正常播放。"
            !authOk -> "登录凭据疑似失效（user_verify 未返回授权码），建议退出后重新扫码登录。"
            vipType == null -> "账号已登录但会员类型未查到，不能确认会员权益；请重新登录后再检测一次。"
            (vipType ?: 0) <= 0 -> "账号已登录但未查到会员权益，会员曲目可能无法播放。"
            sample == null -> "账号与会员权益检查通过；本次未抽到疑似会员样本，建议手动点一首会员歌验证。"
            restrictedOk -> "账号、授权与会员权益均正常，疑似受限曲目也取到了直链，可正常播放。"
            else -> "账号与会员权益正常，但疑似受限曲目仍取不到直链，可能是版权或接口限制。"
        }
        add(
            "综合结论",
            when {
                !loggedIn -> VipProbeState.WARN
                !authOk -> VipProbeState.FAIL
                vipType == null || (vipType ?: 0) <= 0 -> VipProbeState.WARN
                restrictedOk -> VipProbeState.OK
                else -> VipProbeState.WARN
            },
            conclusion,
            System.currentTimeMillis()
        )

        buildReport(context, checks, startedAt, conclusion)
    }

    private fun buildReport(
        context: Context,
        checks: List<VipProbeCheck>,
        startedAt: Long,
        conclusion: String
    ): String = buildString {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(startedAt))
        appendLine("== 会员音乐播放检测报告 ==")
        appendLine("时间：$stamp")
        // 不依赖 BuildConfig.VERSION_*（AGP 8 默认不生成），从包信息里读。
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName}（${info.longVersionCode}）"
        }.getOrDefault("未知")
        appendLine("版本：$version")
        appendLine()
        appendLine("---- 检测项 ----")
        checks.forEach { c ->
            val tag = when (c.state) {
                VipProbeState.OK -> "通过"
                VipProbeState.WARN -> "注意"
                VipProbeState.FAIL -> "失败"
                VipProbeState.SKIP -> "跳过"
            }
            appendLine("[$tag] ${c.name}（${c.elapsedMs}ms）")
            appendLine("      ${c.detail}")
        }
        appendLine()
        appendLine("---- 结论 ----")
        appendLine(conclusion)
        appendLine()
        appendLine("说明：")
        appendLine("· 服务端不提供会员到期时间，「登录态失效」由接口返回间接推测，不作为定论。")
        appendLine("· 曲目没有会员标记，疑似样本来自「公开接口取不到直链」，也可能是版权或接口变更。")
        appendLine("· 本报告不含 token / userid / auth / Cookie 等凭据，可放心复制。")
    }
}
