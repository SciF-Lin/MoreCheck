package com.morecheck.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

/**
 * 本地模拟的登录态仓库。
 *
 * 任务2 只做前端与本地模拟：不接真实短信、不接后端。
 *
 * 键名前缀统一为 `auth_`，与 [SettingsRepository] 的键共用同一个 DataStore 文件，
 * 因此这里通过 [SettingsRepository.rawData] 读取，避免同一文件被打开两次。
 */
class AuthRepository(private val settings: SettingsRepository) {

    private object Keys {
        val LOGGED_IN = booleanPreferencesKey("auth_logged_in")
        val PHONE = stringPreferencesKey("auth_phone")
        val LOGIN_MODE = stringPreferencesKey("auth_login_mode")
        // 注册时填写的资料。键名必须与 `SettingsRepository.Keys` 里的那几个**完全一致** ——
        // 两边操作的是同一个 DataStore 文件，键名不一致就会各写一份、互相看不见。
        val USER_ID = stringPreferencesKey("auth_user_id")
        val NICKNAME = stringPreferencesKey("auth_nickname")
        val GENDER = stringPreferencesKey("auth_gender")
    }

    /** 是否已登录。 */
    val isLoggedIn: Flow<Boolean> =
        settings.rawData.map { it[Keys.LOGGED_IN] ?: false }

    /** 已登录手机号；未登录为 null。 */
    val phone: Flow<String?> =
        settings.rawData.map { it[Keys.PHONE] }

    /** 最近一次使用的登录方式：sms / password。 */
    val loginMode: Flow<String?> =
        settings.rawData.map { it[Keys.LOGIN_MODE] }

    /**
     * 用户 ID：注册时随机生成的 8 位数字。
     *
     * 未注册（走验证码登录）时为 null，界面对应显示「未填写」。
     * 之所以不给登录用户也补一个：那样「用户 ID」就不再是「注册账号的标识」，
     * 与它在注册页生成、展示在我的信息页的语义不符。
     */
    val userId: Flow<String?> =
        settings.rawData.map { it[Keys.USER_ID] }

    /** 注册时填写的昵称；未注册为 null。 */
    val nickname: Flow<String?> =
        settings.rawData.map { it[Keys.NICKNAME] }

    /** 注册时选择的性别（`male` / `female`）；未注册为 null。 */
    val gender: Flow<String?> =
        settings.rawData.map { it[Keys.GENDER] }

    suspend fun login(phone: String, mode: String) {
        settings.setLoggedIn(phone = phone, mode = mode)
    }

    /** 注册成功：写入登录态与注册资料（见 [SettingsRepository.setRegisteredProfile]）。 */
    suspend fun register(
        phone: String,
        userId: String,
        nickname: String,
        gender: String
    ) {
        settings.setRegisteredProfile(
            phone = phone,
            userId = userId,
            nickname = nickname,
            gender = gender,
            mode = "register"
        )
    }

    suspend fun logout() {
        settings.setLoggedOut()
    }

    /**
     * 云登录成功后的本地落地（v3 新增）。
     *
     * 分工：**会话**由 `CloudAuthClient` 管（令牌、刷新、持久化都在那边），
     * 本方法只把"这个人登录了"这件事写进界面可见的本地状态，并把该补的
     * 本地资料补上：
     *
     * - `auth_user_id`：云端账号没有"注册时填的 8 位 ID"这个概念，但「我的信息」
     *   页要展示一个用户 ID。本地没有就生成一个并存下来——它是**本地展示标识**，
     *   不参与云端任何身份判断（云端身份看 `CloudSession.userId`）。
     * - `auth_nickname`：云端资料带了昵称（`name`）且本地还没有时回填；
     *   本地已有则不动——用户在本机改过的昵称优先于云端旧值。
     *
     * @param cloudName 云端账号的昵称（`GET /v1/user/me` 的 `name`），可为 null。
     */
    suspend fun onCloudLoginSucceeded(phone: String, cloudName: String?) {
        settings.setLoggedIn(phone = phone, mode = "sms")
        if (settings.rawData.first()[Keys.USER_ID].isNullOrBlank()) {
            settings.setUserId(newUserId())
        }
        val localNickname = settings.rawData.first()[Keys.NICKNAME]
        if (localNickname.isNullOrBlank() && !cloudName.isNullOrBlank()) {
            settings.setNickname(cloudName)
        }
    }

    companion object {
        /**
         * 用户 ID 的字符池：数字 + 大小写字母。
         *
         * 刻意**去掉容易认错的 `0/O`、`1/I/l`**：这串 ID 会显示给用户看，
         * 甚至可能被念出来或手抄，形近字符会带来无谓的沟通成本。
         * 去掉这 5 个后仍有 57 个可选，8 位空间约 1.1×10^14，本地展示绰绰有余。
         */
        private const val USER_ID_ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /**
         * 生成 8 位用户 ID（数字与大小写字母随机组合）。
         *
         * 用 `kotlin.random.Random` 而非 `SecureRandom`：它是本地演示用的展示标识，
         * 不是凭证，不需要密码学强度。
         */
        fun newUserId(): String =
            buildString {
                repeat(USER_ID_LENGTH) {
                    append(USER_ID_ALPHABET[Random.nextInt(USER_ID_ALPHABET.length)])
                }
            }

        /** 用户 ID 位数。 */
        const val USER_ID_LENGTH = 8
    }
}
