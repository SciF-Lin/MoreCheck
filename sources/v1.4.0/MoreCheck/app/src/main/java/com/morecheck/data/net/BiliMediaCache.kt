package com.morecheck.data.net

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * B 站视频播放缓存（2026-11-18 P0 改造新增，参考 Cheers 的 exocache 实现）。
 *
 * ## 为什么缓存键必须去掉 query
 *
 * B 站直链每次取流都带不同的签名 / deadline 参数，同一视频同一档位的两次 URL
 * **只有 query 不同**。用完整 URL 做缓存键会永远 miss；参考实现用
 * `scheme://host/path` 做键，这里照搬。
 *
 * ## 容量与清理
 *
 * 200MB LRU，落在 `cacheDir/bili_video_cache`（系统可随时回收，不属于用户数据）。
 * 「设置 → 清除缓存」把它单列一行（[CacheBreakdown.biliVideoBytes]）；
 * [com.morecheck.util.CacheCleaner.clearAll] 删文件前必须先调 [releaseForClear]，
 * 否则 SimpleCache 的索引会与磁盘不一致。
 */
@UnstableApi
object BiliMediaCache {

    private const val DIR_NAME = "bili_video_cache"
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val FRAGMENT_BYTES = 2L * 1024 * 1024

    @Volatile
    private var instance: SimpleCache? = null

    fun dirOf(context: Context): File = File(context.cacheDir, DIR_NAME)

    private fun cache(context: Context): SimpleCache? {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: runCatching {
                SimpleCache(
                    dirOf(context),
                    LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext)
                )
            }.getOrNull()?.also { instance = it }
        }
    }

    /**
     * 包一层缓存的 DataSource 工厂；缓存建不起来（磁盘异常等）时原样退回上游，
     * 播放不受影响。读写错误也不阻断播放（FLAG_IGNORE_CACHE_ON_ERROR）。
     */
    fun dataSourceFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val c = cache(context) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(c).setFragmentSize(FRAGMENT_BYTES)
            )
            .setCacheKeyFactory { spec ->
                spec.key ?: "${spec.uri.scheme}://${spec.uri.host}${spec.uri.path.orEmpty()}"
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 清缓存前释放索引并丢弃单例；下次播放会重建。活跃播放器会退回上游直读。 */
    fun releaseForClear() {
        synchronized(this) {
            runCatching { instance?.release() }
            instance = null
        }
    }
}