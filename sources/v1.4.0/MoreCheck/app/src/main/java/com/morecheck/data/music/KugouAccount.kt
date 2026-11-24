package com.morecheck.data.music

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.data.net.KugouSign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.kugouDataStore by preferencesDataStore(name = "kugou_session")

/**
 * 设备身份：`dfid` / `mid` / `uuid`。
 *
 * 酷狗把这三个值当作"哪台设备在请求"。它们必须**稳定**——同一次登录的请求之间换值，
 * 会被判成异常设备；换了之后 `dfid` 与登录态对不上也可能直接不给直链。
 * 所以生成一次就落盘，退出登录时**不清**（清的只是账号凭据）。
 */
data class KugouDevice(
    val dfid: String,
    val mid: String,
    val uuid: String
)

/**
 * 已登录的酷狗账号。
 *
 * [token] 是账号凭据，[auth] 是"曲库授权码"（由 token 换来的短期凭证，
 * 用于向 `tracker` 要会员曲目的直链）。两者都只存在本机，不进云备份、不上传。
 */
data class KugouAccount(
    val userid: String,
    val token: String,
    val auth: String,
    val nickname: String,
    val avatarUrl: String?,
    val vipType: Int,
    val device: KugouDevice
) {
    /** 界面上的称呼：没拿到昵称时退回"酷狗用户 + userid 尾号"。 */
    val displayName: String
        get() = nickname.ifBlank {
            "酷狗用户" + userid.takeLast(4).let { if (it.isBlank()) "" else " $it" }
        }

    /** 会员标记。酷狗 `vip_type > 0` 即视为有会员权益（具体档位不在界面上区分）。 */
    val isVip: Boolean get() = vipType > 0
}

/**
 * 酷狗账号的本地存储（独立 DataStore 文件 `kugou_session`）。
 *
 * ## 为什么单开一个文件
 *
 * 1. 这是**凭据**：`token` 等同于登录态，泄露即可操作账号。独立文件才能被
 *    `backup_rules.xml` / `data_extraction_rules.xml` 精确排除，不随系统备份离开本机；
 * 2. 与收藏分开：收藏是业务数据（要留着），登录态是可以随时作废的凭据，
 *    "退出登录"只清这个文件里的账号字段，不影响收藏。
 *
 * ## 存的到底是什么
 *
 * | 字段 | 用途 | 退出登录时 |
 * | --- | --- | --- |
 * | `token` / `userid` | 账号身份，取链时随请求带上 | 清除 |
 * | `auth` | 由 token 换来的曲库授权码 | 清除 |
 * | `nickname` / `avatar` / `vipType` | 界面显示 | 清除 |
 * | `dfid` / `mid` / `uuid` | 设备身份 | **保留**（换账号也还是这台设备） |
 */
class KugouAccountStore(private val context: Context) {

    private object Key {
        val USERID = stringPreferencesKey("userid")
        val TOKEN = stringPreferencesKey("token")
        val AUTH = stringPreferencesKey("auth")
        val NICKNAME = stringPreferencesKey("nickname")
        val AVATAR = stringPreferencesKey("avatar")
        val VIP_TYPE = intPreferencesKey("vip_type")
        val DFID = stringPreferencesKey("dfid")
        val MID = stringPreferencesKey("mid")
        val UUID = stringPreferencesKey("uuid")
    }

    /** 当前登录的账号；未登录 / 凭据缺失时为 null。 */
    val account: Flow<KugouAccount?> = context.kugouDataStore.data.map { prefs ->
        val token = prefs[Key.TOKEN].orEmpty()
        val userid = prefs[Key.USERID].orEmpty()
        if (token.isBlank() || userid.isBlank()) return@map null
        KugouAccount(
            userid = userid,
            token = token,
            auth = prefs[Key.AUTH].orEmpty(),
            nickname = prefs[Key.NICKNAME].orEmpty(),
            avatarUrl = normalizeAvatar(prefs[Key.AVATAR]),
            vipType = prefs[Key.VIP_TYPE] ?: 0,
            device = KugouDevice(
                dfid = prefs[Key.DFID].orEmpty(),
                mid = prefs[Key.MID].orEmpty(),
                uuid = prefs[Key.UUID].orEmpty()
            )
        )
    }

    /**
     * 取设备身份，缺失时**生成并落盘**。
     *
     * 三条字段都补齐后才返回，避免"只写了一半"的中间态被读到（例如进程正好在
     * 第一次 `edit` 与第二次 `edit` 之间被杀）。
     */
    suspend fun device(): KugouDevice {
        val existing = context.kugouDataStore.data.first()
        val dfid = existing[Key.DFID].orEmpty()
        val mid = existing[Key.MID].orEmpty()
        val uuid = existing[Key.UUID].orEmpty()
        if (dfid.isNotBlank() && mid.isNotBlank() && uuid.isNotBlank()) {
            return KugouDevice(dfid, mid, uuid)
        }
        val fresh = KugouDevice(
            dfid = dfid.ifBlank { KugouSign.randomAlphaNum(24) },
            mid = mid.ifBlank { KugouSign.randomDigits(20) },
            uuid = uuid.ifBlank { KugouSign.randomUuid() }
        )
        context.kugouDataStore.edit { prefs ->
            prefs[Key.DFID] = fresh.dfid
            prefs[Key.MID] = fresh.mid
            prefs[Key.UUID] = fresh.uuid
        }
        return fresh
    }

    /**
     * 把酷狗返回的头像地址从 http 升到 https。
     *
     * 酷狗给的是 `http://imge.kugou.com/...`，而本 App 默认禁止明文流量
     * （只对音频接口域名单独放行）。实测该域名 **https 同样可用且返回 200**，
     * 所以这里直接升级，**不用**为头像再加一条明文豁免。
     */
    private fun normalizeAvatar(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://")) {
            "https://" + trimmed.removePrefix("http://")
        } else {
            trimmed
        }
    }

    /** 扫码成功后保存凭据。设备身份若还没生成，这里一并补上。 */
    suspend fun saveLogin(
        userid: String,
        token: String,
        nickname: String,
        avatarUrl: String?,
        vipType: Int,
        auth: String = ""
    ) {
        val device = device()
        context.kugouDataStore.edit { prefs ->
            prefs[Key.USERID] = userid
            prefs[Key.TOKEN] = token
            prefs[Key.AUTH] = auth
            prefs[Key.NICKNAME] = nickname
            prefs[Key.AVATAR] = normalizeAvatar(avatarUrl).orEmpty()
            prefs[Key.VIP_TYPE] = vipType
            prefs[Key.DFID] = device.dfid
            prefs[Key.MID] = device.mid
            prefs[Key.UUID] = device.uuid
        }
    }

    /** 补全昵称/头像/会员档位（扫码返回里没有这些，要另外查一次个人资料）。 */
    suspend fun updateProfile(nickname: String, avatarUrl: String?, vipType: Int) {
        context.kugouDataStore.edit { prefs ->
            if (nickname.isNotBlank()) prefs[Key.NICKNAME] = nickname
            normalizeAvatar(avatarUrl)?.let { prefs[Key.AVATAR] = it }
            prefs[Key.VIP_TYPE] = vipType
        }
    }

    /** 缓存换来的曲库授权码，下次取链直接复用，省一次请求。 */
    suspend fun updateAuth(auth: String) {
        context.kugouDataStore.edit { prefs: MutablePreferences ->
            prefs[Key.AUTH] = auth
        }
    }

    /** 退出登录：清账号凭据，保留设备身份（见类 KDoc 的表格）。 */
    suspend fun clearLogin() {
        context.kugouDataStore.edit { prefs ->
            prefs.remove(Key.USERID)
            prefs.remove(Key.TOKEN)
            prefs.remove(Key.AUTH)
            prefs.remove(Key.NICKNAME)
            prefs.remove(Key.AVATAR)
            prefs.remove(Key.VIP_TYPE)
        }
    }

    /** 取一次当前账号（非 Flow 场景用，如退出登录前确认）。 */
    suspend fun current(): KugouAccount? = account.first()
}
