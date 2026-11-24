package com.morecheck.data

import androidx.room.Entity

/**
 * 一个网页游戏的 localStorage 快照（云同步的本地镜像）。
 *
 * ## 数据从哪来、到哪去
 *
 * 四个游戏（贪吃蛇 / 番茄钟 / 数独 / 数独2）各自把状态存在 WebView 的
 * `localStorage` 里（键名清单见 `GameDataBridge`）。同步的最小单位就是
 * **一个游戏的全部键值**，因此这里一行 = 一个游戏：
 *
 * - `data_json`：`{"<键>": "<值>", ...}`，值是 localStorage 的原始字符串
 *   （游戏自己存 JSON 串或标量，这里一律原样保存，不解析、不改写）；
 * - `updated_at`：快照最后一次变化的时间，与云端 `game_data.updated_at`
 *   直接对应（都是 epoch 毫秒），LWW 裁决用；
 * - `applied`：这份快照是否已经写进过 WebView 的 localStorage。
 *   云端恢复把新快照写进本表时置 `false`，游戏页打开时据此决定
 *   "把快照灌进 localStorage"还是"把 localStorage 收进快照"。
 *   **纯本地簿记，不参与云端同步**（云端表没有这一列）。
 *
 * ## 为什么不直接从 WebView 读 localStorage 同步
 *
 * WebView 随页面离开即 `destroy()`，同步引擎需要一个**与页面生命周期无关**的
 * 本地事实来源；这一行就是那个来源。游戏页负责 localStorage ↔ 本行的搬运
 * （见 `GameDataBridge` 与四个游戏页的接线），同步引擎只认本表。
 */
@Entity(tableName = "game_snapshots")
data class GameSnapshot(
    /** 游戏标识：`snake` / `pomodoro` / `sudoku` / `sudoku2`（与云端 game_key 一致）。 */
    @androidx.room.PrimaryKey val gameKey: String,
    /** 该游戏全部受管键的当前值，JSON 对象；空游戏（从未玩过）为空对象 `{}`。 */
    val dataJson: String,
    /** 快照最后变化时间（epoch 毫秒）。 */
    val updatedAt: Long,
    /** 快照是否已写进 WebView 的 localStorage（见类注释；仅本地使用）。 */
    val applied: Boolean = true
)
