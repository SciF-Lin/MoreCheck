package com.morecheck.ui.lab

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 今日待办页的原生桥（WebView → 宿主）。
 *
 * ## 它解决什么
 *
 * 待办页是一张网页（`assets/todo/index.html`），装在 WebView 里。网页自己管
 * 登录、云端存取、待办增删改查，但有几件事**网页做不了**、必须由宿主做：
 *
 * | 能力 | 为什么网页做不了 | 本桥的方法 |
 * | --- | --- | --- |
 * | 选图（图片备注 / 头像） | WebView 的 `<input type=file>` 需要宿主实现 `onShowFileChooser`，否则点了没反应；且原生 PhotoPicker 体验更好 | [pickImage] |
 * | 会话（2026-10-29 起是**同一个账号**） | 网页的会话在 localStorage，清缓存就丢；而且**一个账号要三处通用**，只能由原生托管 | [loadSession] / [saveSession] / [signOut] |
 * | 把 App 自己的昵称/头像带给网页 | 网页只认云端账号，拿不到 App 侧资料 | [getUserInfo] |
 * | 退出页面 / 提示 | 宿主才有这些能力 | [exit] / [toast] |
 *
 * ## 与页面约定的协议
 *
 * 页面侧认这个全局名（见 `index.html` 里的 `Native` 对象）：
 * `window.__CIM_TODO__`，带一个 `version` 字段作为"桥在不在"的判据。
 *
 * 选图是**异步回调**式的：网页调 [pickImage]（带一个自己生成的 `callbackId`），
 * 宿主选完图后通过 `evaluateJavascript` 调回 `window.__cimTodoImage(callbackId, dataUrl)`。
 * 页面在没有桥时会退回 `<input type=file>`，因此**桥是可选增强，不是硬依赖**。
 *
 * ## 线程模型（`@JavascriptInterface` 的铁律）
 *
 * 桥方法运行在 WebView 的 **JavaBridge 线程**，不是主线程：
 * - 需要碰 UI 的（[pickImage] / [toast] / [exit]）一律用 [scope] 投到主线程；
 * - 需要做 IO 的（图片解码、压缩）用 `withContext(Dispatchers.IO)`；
 * - [loadSession] / [saveSession] / [signOut] 都要摸原生会话（DataStore + 网络），
 *   一律用 `scope.launch` 投出去；[loadSession] 是同步返回的，由宿主用 `runBlocking`
 *   在 **JavaBridge 线程**上取（不是主线程，没有 ANR 风险，与 `profileProvider` 同一口径）。
 *
 * ## ⚠️ 凭据落在哪（2026-10-29 账号合并后）
 *
 * 会话**只有一份**，就在 `CloudAuthClient` 的 DataStore（`cloud_session`）里，
 * 与 App 其它云数据同一保护级别。本桥不再自己存副本：
 * [saveSession] 只是把网页拿到的会话交给原生采纳，[loadSession] 只是把原生会话
 * 读给网页。历史上那个 SharedPreferences 副本（`todo_web_session`）已停用，
 * 只留一条删除逻辑，避免把旧应用（`today-todo-50052`）的令牌误当成有效会话。
 */
class TodoBridge(
    private val context: Context,
    /** 主线程 scope（宿主页面的 composition scope 即可）。 */
    private val scope: CoroutineScope,
    /** 发起选图。**必须**在主线程调用（要起 Activity）。 */
    private val onPickImage: (callbackId: String) -> Unit,
    /** 供网页读取的 App 侧资料（昵称 / 头像路径 / 用户 ID）。 */
    private val profileProvider: () -> TodoNativeProfile,
    /**
     * 取**原生云会话**的 JSON（`CloudAuthClient` 那一份）；未登录返回 null。
     *
     * 2026-10-29 账号合并后，原生与待办页是同一个云应用的同一个账号，
     * 所以网页拿这份会话就等于"已登录"，不需要再自己输验证码。
     */
    private val sessionProvider: () -> String?,
    /** 网页登录成功 → 把会话交给原生采纳（见 [saveSession]）。 */
    private val adoptSession: (String) -> Unit,
    /** 网页点退出登录 → 原生一并退出（见 [signOut]）。 */
    private val onSignOut: () -> Unit,
    /** 交给宿主做一次原生提示。 */
    private val onToast: (String) -> Unit,
    /** 退出待办页。 */
    private val onExit: () -> Unit,
    /**
     * 网页 → 宿主：把灯箱里那张图存进系统相册（2026-10-30 需求 1）。
     *
     * 网页侧写着「长按可保存图片」却什么都发生不了：WebView 里的 blob / http 图片
     * 没有"另存为"这条路，浏览器默认的长按菜单在本 WebView 里也是关掉的。
     * 所以只能由宿主下载并写进 MediaStore。
     */
    private val onSaveImage: (url: String) -> Unit = {},
    /**
     * 网页 → 宿主：分享图片（本轮需求 4）。
     *
     * 与 [onSaveImage] 分开：保存是"落进相册"，分享是"交给别的 App"，
     * 两条路径的临时文件与失败原因都不同（分享要 FileProvider 授权）。
     */
    private val onShareImage: (url: String) -> Unit = {},
    /**
     * 网页 → 宿主：图片灯箱开了 / 关了（2026-10-30 需求 2）。
     *
     * 宿主拿它来决定**返回键归谁**：灯箱开着时返回键只关灯箱，不退出整页。
     * 页面只在 openViewer / closeViewer 两处各调一次，宿主只做一次布尔赋值。
     */
    private val onViewerState: (Boolean) -> Unit = {}
) {

    /**
     * 历史版本的会话副本（2026-10-28 那版用 SharedPreferences 存了一份）。
     *
     * 账号合并后不再需要它（原生 `CloudAuthClient` 就是唯一事实来源），
     * 但**不能直接无视**：老版本写进去的是"今日待办那个云应用"的令牌，
     * 拿到新应用下只会 401。这里只为 [clearSession] 把它删掉。
     */
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 协议版本号——**页面判断"桥在不在"的唯一依据**。
     *
     * 页面侧写的是 `!!(window.__CIM_TODO__ && window.__CIM_TODO__.version)`
     * （见 `assets/todo/index.html` 的 `Native.available()`）。
     *
     * ⚠️ 必须是**方法** `version()`，不能写成 Kotlin 属性 `val version = 1`：
     * `addJavascriptInterface` 只把**方法**按原名挂到 JS 对象上，属性会被拆成
     * getter `getVersion()`——于是 `window.__CIM_TODO__.version` 是 `undefined`，
     * 页面的 `available()` 恒为 false，选图 / 会话存档 / 退出 / 提示全部静默降级
     * （功能看着不报错，其实一个都没生效）。
     *
     * 这个坑是接入后真机 CDP 读 `Object.getOwnPropertyNames(window.__CIM_TODO__)`
     * 才看出来的：`["getVersion","loadSession",…]` 里根本没有 `version`。
     *
     * 页面侧同时对 `getVersion` 做了兼容（见 `Native.available()`），
     * 两边都改是为了将来任一侧回退成属性写法也不会再静默失效。
     */
    @JavascriptInterface
    fun version(): Int = 2

    /**
     * 网页 → 宿主：请求选一张图。
     *
     * 只把 `callbackId` 带过去，图片本身走回调（见 [deliverImage]）：
     * 宿主选完后由页面侧约定的 `window.__cimTodoImage(callbackId, dataUrl)` 接收。
     *
     * 立即返回，不阻塞 JavaBridge 线程。
     */
    @JavascriptInterface
    fun pickImage(callbackId: String) {
        // 这一行是排障锚点：页面点了"选图"却毫无反应时，先看 logcat 有没有它——
        // 没有 = 页面压根没调到桥（探测判据 / 调用点的问题）；
        // 有、但没有后续 [TodoTodayScreen] 里那条 "[pick] launch" = 主线程副作用没跑。
        Log.i(LOG_TAG, "[pickImage] callbackId=$callbackId")
        scope.launch { onPickImage(callbackId) }
    }

    /**
     * 网页 → 宿主：取 App 侧资料。
     *
     * 返回 JSON 字符串（`@JavascriptInterface` 只能返回基本类型）：
     * `{"nickname":"…","avatarPath":"…","userId":"…"}`。
     * 网页拿不到时返回空串，页面会自己用云端账号的资料兜底。
     */
    @JavascriptInterface
    fun getUserInfo(): String = runCatching {
        val profile = profileProvider()
        JSONObject().apply {
            put("nickname", profile.nickname)
            put("avatarPath", profile.avatarPath)
            put("userId", profile.userId)
        }.toString()
    }.getOrDefault("")

    /**
     * 网页 → 宿主：保存待办模块的登录会话（JSON）。
     *
     * ⚠️ **2026-10-29 起语义变了**（账号合并）：这里不再只是"存一份备份"，
     * 而是把网页登录拿到的会话**交给原生采纳**——打卡与待办现在是同一个云应用的
     * 同一个账号，网页登录成功就等于整个应用登录成功，原生侧要立即可见
     * （否则会出现"待办已登录、打卡还显示未登录"，下次进待办又把旧会话救回来）。
     *
     * 典型内容：`{"accessToken":"…","refreshToken":"…","expiresAt":…,"user":{…}}`。
     */
    @JavascriptInterface
    fun saveSession(json: String) {
        if (json.isBlank()) return
        adoptSession(json)
    }

    /**
     * 网页 → 宿主：取当前会话；原生未登录时返回空串。
     *
     * 返回的是**原生云会话**（`CloudAuthClient` 那一份），不是历史上存在
     * SharedPreferences 里的副本——账号合并后原生才是唯一事实来源，
     * 留着旧副本只会把过期/换账号前的令牌又救回来。
     */
    @JavascriptInterface
    fun loadSession(): String = sessionProvider() ?: ""

    /**
     * 网页 → 宿主：清掉宿主侧的会话副本。
     *
     * ⚠️ 只清"副本"，**不退出原生登录**：页面在"会话被服务端判失效"时也会调它，
     * 那种情况不该顺手把整个应用退了。真正的退出走 [signOut]。
     */
    @JavascriptInterface
    fun clearSession() {
        // 历史版本把会话副本存在 SharedPreferences（KEY_SESSION）。合并后不再写，
        // 但老版本写进去的那份可能还在，这里顺手删掉，避免以后被误读。
        runCatching { legacyPrefs.edit().remove(LEGACY_KEY_SESSION).apply() }
    }

    /**
     * 网页 → 宿主：退出登录（2026-10-29 账号合并新增）。
     *
     * 一个账号 = 一处退出、处处退出：网页点「退出登录」后，原生登录态也要清掉，
     * 否则下次进待办会从原生会话恢复成"刚退出的那个账号"。
     */
    @JavascriptInterface
    fun signOut() {
        onSignOut()
    }

    /** 网页 → 宿主：弹一个原生 Toast。 */
    @JavascriptInterface
    fun toast(message: String) {
        scope.launch { onToast(message) }
    }

    /**
     * 网页 → 宿主：数据变化通知。
     *
     * 页面在增删改待办 / 图片 / 资料后调用，宿主可据此刷新自己这边的
     * 小组件、角标或本地缓存。载荷形如
     * `{"type":"todo_added","at":1750000000000,"id":42}`。
     */
    @JavascriptInterface
    fun notify(payload: String) {
        Log.i(LOG_TAG, "[notify] $payload")
        scope.launch { onNotify?.invoke(payload) }
    }

    /** 网页 → 宿主：退出待办页。 */
    @JavascriptInterface
    fun exit() {
        scope.launch { onExit() }
    }

    /**
     * 网页 → 宿主：保存图片（2026-10-30 需求 1）。
     *
     * 传的是灯箱里那张图的 **src**：可能是云端签名 URL（http/https），
     * 也可能已经是 `data:image/...`（本地刚加、还没上传）。两种都由
     * [TodoImageSaver] 处理，页面不用关心。
     *
     * 下载 + 写相册放后台线程，结果用原生 Toast 回话（"已保存到相册" / 失败原因）。
     */
    @JavascriptInterface
    fun saveImage(url: String) {
        Log.i(LOG_TAG, "[saveImage] ${url.take(64)}")
        if (url.isBlank()) return
        scope.launch { onSaveImage(url) }
    }

    /**
     * 网页 → 宿主：分享图片（本轮需求 4）。
     *
     * 长按图片现在弹「保存到相册 / 分享」两个选项。分享必须由原生做：
     * WebView 里没有 `navigator.share` 的文件能力，也拿不到系统分享面板。
     *
     * 宿主把图下到 `cacheDir`（FileProvider 只映射这一层，见 `res/xml/file_paths`），
     * 再 `ACTION_SEND` 出去；成功与否由原生 Toast 回话。
     */
    @JavascriptInterface
    fun shareImage(url: String) {
        Log.i(LOG_TAG, "[shareImage] ${url.take(64)}")
        if (url.isBlank()) return
        scope.launch { onShareImage(url) }
    }

    /**
     * 网页 → 宿主：灯箱开 / 关（2026-10-30 需求 2）。
     *
     * `open = true` 时宿主把返回键收过来，只关灯箱；`false` 时还给页面
     * （返回键 = 退出待办页）。
     */
    @JavascriptInterface
    fun setViewerOpen(open: Boolean) {
        scope.launch { onViewerState(open) }
    }

    /** 宿主侧对 [notify] 的额外处理（可选）。 */
    var onNotify: ((String) -> Unit)? = null

    /**
     * 把选好的图片交给网页。
     *
     * 走 [javaScriptCallback] 注入的回调：网页侧约定的
     * `window.__cimTodoImage(callbackId, dataUrl)`。
     *
     * @param callbackId 网页传进来的回调 id，必须原样回传。
     * @param dataUrl `data:image/jpeg;base64,…`。
     */
    fun deliverImage(callbackId: String, dataUrl: String) {
        val script = buildString {
            append("try{window.__cimTodoImage&&window.__cimTodoImage(")
            append(JSONObject.quote(callbackId))
            append(",")
            append(JSONObject.quote(dataUrl))
            append(");}catch(e){}")
        }
        javaScriptCallback?.invoke(script)
    }

    /**
     * 把一段 JS 注入页面（由宿主页提供 WebView 引用）。
     *
     * 没有 WebView 时静默忽略——页面可能已经被销毁了，这时注入没有意义。
     */
    var javaScriptCallback: ((String) -> Unit)? = null

    companion object {
        /**
         * 桥在页面里的全局对象名。
         *
         * 双下划线 + 项目缩写，与既有桥 `__CIM_GAME__`（游戏存档）同一风格，
         * 但**不复用**它——语义不同，混在一个对象上会让"桥在不在"的判断失去意义。
         */
        const val JS_NAME = "__CIM_TODO__"

        private const val LOG_TAG = "TodoBridge"

        /** 历史版本的会话副本文件名（账号合并后已停用，见 [legacyPrefs]）。 */
        private const val LEGACY_PREFS_NAME = "todo_web_session"

        /** 历史副本里的键。 */
        private const val LEGACY_KEY_SESSION = "session_json"

        /**
         * 交给网页的图片最长边（像素）——**仅用于回退路径**。
         *
         * 2026-10-31 起优先走 [encodeOriginalPicked]（原图字节直传，不重编码）；
         * 只有原图超过 [MAX_ORIGINAL_BYTES] 时才退回压缩。压缩的尺寸与质量因此
         * 从 1600/q85 提到 2048/q90——回退那一档也不该一看就糊。
         */
        const val MAX_IMAGE_EDGE = 2048

        /** 原生侧 JPEG 质量（只在回退路径使用）。 */
        const val JPEG_QUALITY = 90

        /**
         * 原图直传的体积上限。
         *
         * 为什么要有上限：图片是 base64 穿过 JS 桥送进 WebView 的，体积会再涨 33%。
         * 8MB 原文件 → 约 10.7MB 字符串，这是本 WebView（模拟器上尤其明显）还能
         * 一次性吃下的量级；再大就退回 [MAX_IMAGE_EDGE] 压缩，否则页面会卡死。
         * 手机直出的 JPEG 大多 2~5MB，正常照片都能走原图。
         */
        const val MAX_ORIGINAL_BYTES = 8 * 1024 * 1024

        /**
         * 读**原始字节**并编成 data URL（不重编码，画质零损失）。
         *
         * 返回 null 表示走不了原图：类型不是图片、读取失败、或超过
         * [MAX_ORIGINAL_BYTES]。调用方应退回 [decodePicked] + [encodeDataUrl]。
         */
        suspend fun encodeOriginalPicked(context: Context, uri: android.net.Uri): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri)?.lowercase()
                        ?.takeIf { it.startsWith("image/") }
                        ?: return@runCatching null
                    // HEIC/HEIF 在部分 WebView 里渲染不出来（浏览器解码器差异），
                    // 这类一律走解码 + JPEG，宁可压一次也别出现"图片显示不出来"。
                    if (mime.contains("heic") || mime.contains("heif")) return@runCatching null

                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    var tooBig = false
                    resolver.openInputStream(uri)?.use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            total += n
                            if (total > MAX_ORIGINAL_BYTES) { tooBig = true; break }
                            out.write(buffer, 0, n)
                        }
                    } ?: return@runCatching null
                    if (tooBig || total == 0) return@runCatching null
                    "data:$mime;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }.getOrNull()
            }

        /**
         * 把位图编成 `data:image/jpeg;base64,…`。
         *
         * 用 [Base64.NO_WRAP]：不插换行，网页侧 `atob` / `Image` 都能直接用。
         */
        fun encodeDataUrl(bitmap: Bitmap, quality: Int = JPEG_QUALITY): String {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }

        /**
         * 解码用户选中的图片并压到 [MAX_IMAGE_EDGE] 以内。
         *
         * 复用 [AvatarStore.decodeForCrop]（它已经处理了采样与超大图 OOM 兜底），
         * 不另写一套解码逻辑——两处解码走同一套口径，避免"头像能出图、备注图出不来"。
         *
         * @return 解码失败返回 null。
         */
        suspend fun decodePicked(context: Context, uri: android.net.Uri): Bitmap? =
            withContext(Dispatchers.IO) {
                AvatarStore.decodeForCrop(context, uri, MAX_IMAGE_EDGE)
            }
    }
}

/**
 * 宿主侧提供给网页的资料快照。
 *
 * @param nickname App 里的昵称（可能为空）。
 * @param avatarPath App 头像的本地绝对路径（可能为空）。
 * @param userId App 的展示用用户 ID（可能为空）。
 */
data class TodoNativeProfile(
    val nickname: String = "",
    val avatarPath: String = "",
    val userId: String = ""
)
