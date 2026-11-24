package com.morecheck.data.game

import android.webkit.JavascriptInterface
import com.morecheck.data.CheckDao
import com.morecheck.data.GameSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 游戏存档的 WebView 桥（v3 云同步）。
 *
 * ## 它在整条链路里的位置
 *
 * ```
 * 网页 localStorage ──pushState()──▶ game_snapshots（Room）
 *                                      │
 *                            CloudSyncRepository 推送 / 拉取
 *                                      │
 * 网页 localStorage ◀──pendingState()── game_snapshots（applied=false 的行）
 * ```
 *
 * 四个游戏（贪吃蛇 / 番茄钟 / 数独 / 数独2）的存档全在各自页面的
 * `localStorage` 里。WebView 的 localStorage 对原生是不透明的，因此：
 *
 * - **导出**：原生把一段采集脚本 `evaluateJavascript` 进页面，脚本把该游戏的
 *   几个键打包成 JSON 调 [pushState]；
 * - **导入**：页面 `<head>` 里注入一段水合脚本（见各 assets 的 index.html），
 *   在页面自身脚本**之前**跑，调 [pendingState] 拿到待灌入的快照写进
 *   localStorage，再调 [markApplied] 确认。
 *
 * ## `applied` 标记的语义
 *
 * `game_snapshots.applied` 是纯本地簿记（云端表没有这一列）：
 * - `false` = 这份快照是从云端拉下来的、还没灌进 WebView（下次打开页面时水合）；
 * - `true` = 已经灌过，或本来就是本机产生的。
 *
 * 拉取（`CloudSyncRepository.pullGames`）只把**更新**的云端快照写成
 * `applied = false`；水合成功后由本桥置回 `true`。
 *
 * ## 线程模型（`@JavascriptInterface` 的铁律）
 *
 * 桥方法运行在 WebView 的 **JavaBridge 线程**（不是主线程）：
 * - [pushState] / [markApplied] 只做异步投递（[scope] 里 launch），不在桥上阻塞；
 * - [pendingState] 必须**同步返回**（水合脚本在页面解析期调用，没法等异步），
 *   因此用 `runBlocking` 等一次 Room 读。这在 JavaBridge 线程上是安全的
 *   （本来就不是主线程，不存在 ANR 风险）。
 *
 * ## 为什么采集用"整包全量"而不是增量
 *
 * 每个游戏的存档总量在几百字节到几 KB 之间（最高分 / 难度 / 一局局面 /
 * 统计），整包随每次推送发出没有任何可感知的代价；而增量要维护"哪些键改过"
 * 的第二份状态，复杂度与出错面都远超收益。
 */
class GameDataBridge(
    /** 游戏键（云端 `game_data.game_key`，同时是 Room 主键），见 [GameKeys]。 */
    private val gameKey: String,
    private val dao: CheckDao,
    private val scope: CoroutineScope
) {

    /**
     * 网页 → 原生：把当前 localStorage 里该游戏的存档整包交出来。
     *
     * 由 [collectPushScript] 生成的脚本调用。空对象（`{}`，表示这个游戏
     * 在本机还没有任何存档）**不落库**：与 [CloudSyncRepository] 的
     * EMPTY_GAME_JSON 口径一致——空快照推上去会用"无数据"盖掉另一台设备的存档。
     */
    @JavascriptInterface
    fun pushState(json: String) {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == EMPTY_JSON) return
        val now = System.currentTimeMillis()
        scope.launch {
            val existing = dao.getGameSnapshot(gameKey)
            // 是否接受这次本地采集由 [GameSnapshotPolicy] 裁决（当前恒接受）。
            if (GameSnapshotPolicy.shouldOverwriteWithLocalPush(existing, now)) {
                dao.upsertGameSnapshot(
                    GameSnapshot(
                        gameKey = gameKey,
                        dataJson = trimmed,
                        updatedAt = now,
                        // 本机刚采集的存档就是 WebView 里的现状，无需再灌回去。
                        applied = true
                    )
                )
            }
        }
    }

    /**
     * 网页 → 原生：取"待灌入"的快照；没有则返回 `null`。
     *
     * 水合脚本在 `<head>` 里同步调用它。返回 `null` 的两种情况：
     * 1. 本机从没有过这个游戏的快照（新装 / 没同步过）；
     * 2. 最近一份快照已经灌过（`applied = true`）——此时 WebView 里的
     *    localStorage 就是当前事实，不能被旧快照覆盖。
     */
    @JavascriptInterface
    fun pendingState(): String? = runBlocking {
        val snapshot = dao.getGameSnapshot(gameKey) ?: return@runBlocking null
        if (snapshot.applied) null else snapshot.dataJson
    }

    /** 网页 → 原生：水合完成，把快照标记为"已灌入"。 */
    @JavascriptInterface
    fun markApplied() {
        scope.launch {
            val snapshot = dao.getGameSnapshot(gameKey) ?: return@launch
            if (!snapshot.applied) {
                dao.upsertGameSnapshot(snapshot.copy(applied = true))
            }
        }
    }

    /**
     * 各游戏的存档键表。
     *
     * **两处必须同步修改**：这里（原生采集用）与各游戏目录下 `index.html` 的
     * `<head>` 水合脚本（导入用）。水合脚本不枚举键——它原样写 native 给的
     * 快照，因此只有本表需要与素材的 localStorage 键名保持一致。
     *
     * ⚠️ 素材里改键名时这里必须跟着改，否则表现为"那个游戏的存档突然不同步了"
     * （采集不到 → 不推；水合写进的键页面又不读），且**没有任何报错**。
     *
     * ## ⚠️ 为什么它在**类体**里而不是 companion 里
     *
     * Kotlin 的规则：companion 里的**函数 / 属性**会转发到外层类名
     * （`GameDataBridge.collectPushScript(...)` 能解析就是这个原因），但 companion 里
     * 嵌套的 **object / class 不会**——那样写只能经 `GameDataBridge.Companion.GameKeys`
     * 访问，直接写 `GameDataBridge.GameKeys` 会报 `Unresolved reference 'GameKeys'`。
     * 四个游戏屏有 20 处引用，全部走类名，因此本 object 必须留在类体。
     */
    object GameKeys {
        const val SNAKE = "snake"
        const val POMODORO = "pomodoro"
        const val SUDOKU = "sudoku"
        const val SUDOKU2 = "sudoku2"

        /** gameKey → 该游戏在 localStorage 里的全部键。 */
        val BY_GAME: Map<String, List<String>> = mapOf(
            // assets/snake/game.js：CONFIG.STORAGE_KEY_SCORE / _LEVEL + STORAGE_KEY_THEME
            SNAKE to listOf("snake_high_score", "snake_difficulty", "snake_theme"),
            // assets/pomodoro/index.html：const KEY='pomodoro-state-v1'
            POMODORO to listOf("pomodoro-state-v1"),
            // assets/sudoku/index.html：KEY_SAVE / KEY_STATS / KEY_SETTINGS / KEY_LAST_DIFF
            SUDOKU to listOf(
                "sudoku.v1.save",
                "sudoku.v1.stats",
                "sudoku.v1.settings",
                "sudoku.v1.lastDifficulty"
            ),
            // assets/sudoku2/index.html：AUTO_KEY / SAVES_KEY / PREF_KEY
            SUDOKU2 to listOf("sudoku.auto.v1", "sudoku.saves.v1", "sudoku.pref.v1")
        )
    }

    companion object {
        /**
         * 桥在页面里的全局对象名。
         *
         * 双下划线 + 项目缩写：与既有退出桥 `__MORECHECK__`（贪吃蛇）同一风格，
         * 且刻意**不复用**它——那个桥的语义是"退出游戏回调"，这个是"存档同步"，
         * 混在一个对象上会让"有没有注册桥"的判断失去意义。
         */
        const val JS_NAME = "__CIM_GAME__"

        private const val EMPTY_JSON = "{}"

        /**
         * 生成"采集当前存档并推给原生"的 JS（`evaluateJavascript` 用）。
         *
         * 整段包在 IIFE + try 里：任何一步失败（localStorage 被禁用、
         * 桥没注册）都静默返回——采集不到只是少同步一次，不该让页面报错。
         *
         * 键名是代码里的固定字面量（[GameKeys.BY_GAME]），不含用户输入，
         * 因此直接拼进 JS 字符串没有注入面。
         */
        fun collectPushScript(gameKey: String): String {
            // ⚠️ 必须带 `GameKeys.` 限定：[BY_GAME] 是**类体里那个 object** 的成员，
            // 不是 companion 的成员——未限定查找不穿过嵌套 object（本文件因此编译失败过）。
            val keys = GameKeys.BY_GAME[gameKey] ?: return ""
            // ⚠️ 2026-10-22 修复：这里原来写的是
            //   keys.joinToString(",", "'", "'") { "'${...}'" }
            // ——第 4 个参数是 **transform**，于是每个元素被包成 'key'，
            // 外面再套一次 prefix/postfix，产出 `''key1','key2''`，
            // 整段脚本语法错误（模拟器实测每 15 秒一条
            // `Uncaught SyntaxError: Unexpected identifier 'sudoku'`），
            // 也就是**游戏存档从来没被采集过**（云端同步里游戏那一项一直是空的）。
            // 现在只用 transform：元素自带引号，prefix/postfix 留空。
            val keysLiteral = keys.joinToString(", ") { "'${it.replace("'", "\\'")}'" }
            // ⚠️ 不要 `javascript:` 前缀：那是 `loadUrl` 的协议头，
            // `evaluateJavascript` 收的是**脚本正文**（带上它会被当成标签语句解析）。
            val script = """
                (function(){try{var out={};var keys=[$keysLiteral];
                for(var i=0;i<keys.length;i++){var v=null;try{v=localStorage.getItem(keys[i]);}catch(e){}if(v!==null)out[keys[i]]=v;}
                if(window.$JS_NAME){window.$JS_NAME.pushState(JSON.stringify(out));}}catch(e){}})();
            """.trimIndent()
            return script
        }
    }
}

/**
 * 游戏快照写入策略的**纯判定**接缝（applied 竞态的修复点，无 Android 依赖）。
 *
 * ## 背景缺陷（评审 P1-3，2026-11-04 修复）
 *
 * 修复前 [GameDataBridge.pushState] **无条件**把本地采集的快照写入镜像表
 * （`applied = true`、`updatedAt = now`）。竞态时序：
 *
 * 1. 云端有设备 A 的新存档（`updated_at` 较大）；
 * 2. 本机拉取把新存档写进镜像表（`applied = false`，待水合）；
 * 3. 本机 WebView 采集脚本**晚于**拉取触发 pushState，上交的是本地旧快照；
 * 4. 修复前直接覆盖：`applied` 置回 `true`、时间戳变成 now（比云端还新）——
 *    待水合的云端存档被冲掉（`pendingState` 从此返回 null），下次推送
 *    又以"本地更新"胜出，把 A 的新存档从云端也盖掉。
 *
 * ## 当前策略（与 SyncConflictTest 的用例一一对应）
 *
 * - 既有快照为 null（首次落库）→ 允许；
 * - 既有快照**更新**（`updatedAt` 大于本地采集时刻）→ 拒绝，保住待水合状态；
 * - 时间戳打平且既有快照仍是待水合（`applied = false`）→ 拒绝（保守侧，
 *   与 [com.morecheck.data.cloud.lwwWinsLocally] 的同刻判云端赢一致）；
 * - 其余（本地确实更新，或既有快照本就是本机现状）→ 允许。
 */
internal object GameSnapshotPolicy {
    /**
     * 本机采集到一份存档时，是否允许它覆盖镜像表里的既有快照。
     *
     * @param existing 镜像表里该游戏当前的快照；null 表示本机从没有过。
     * @param localUpdatedAt 本次本地采集的时刻（pushState 落库时打算写的 updatedAt）。
     */
    fun shouldOverwriteWithLocalPush(existing: GameSnapshot?, localUpdatedAt: Long): Boolean {
        if (existing == null) return true
        if (existing.updatedAt > localUpdatedAt) return false
        if (existing.updatedAt == localUpdatedAt && !existing.applied) return false
        return true
    }
}
