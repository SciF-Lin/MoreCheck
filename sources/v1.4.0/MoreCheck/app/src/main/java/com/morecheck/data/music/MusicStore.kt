package com.morecheck.data.music

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * 音乐模块共用的 DataStore（文件 `music.preferences_pb`）。
 *
 * ## 为什么必须只有这一处声明
 *
 * `preferencesDataStore` 是**按文件**的单例：同一个文件名声明两次，就会创建两个
 * `DataStore` 实例指向同一个文件，运行时直接抛
 *
 * ```
 * IllegalStateException: There are multiple DataStores active for the same file
 * ```
 *
 * 这不是警告，是**启动即崩**——2026-11-08 引入「最近播放」时就踩了：
 * `MusicFavorites` 与 `MusicHistory` 各写了一份 `by preferencesDataStore(name = "music")`。
 * 现在两者都从这里取，只有这一份委托。
 *
 * ## 里面存什么
 *
 * - `favorites_json`（收藏）—— [MusicFavorites]
 * - `history_json`（最近播放）—— [MusicHistory]
 *
 * 都是**本机数据，不上云**（隐私政策里如实写了两处）。
 * 独立成文件而不是塞进 `settings.preferences_pb`：业务数据会增长，
 * 而设置文件每次 `edit` 都是整文件重写，混在一起会让开关越用越慢。
 */
internal val Context.musicDataStore by preferencesDataStore(name = "music")
