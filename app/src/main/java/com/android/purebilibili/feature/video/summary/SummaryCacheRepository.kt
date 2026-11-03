package com.android.purebilibili.feature.video.summary

import android.content.Context
import com.android.purebilibili.core.database.AppDatabase
import com.android.purebilibili.core.database.entity.AiSummaryCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 视频总结本地持久化缓存仓库
 */
object SummaryCacheRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun buildCacheKey(
        bvid: String,
        cid: Long,
        model: String,
        transcriptHash: String,
        promptVersion: Int = SummaryComposer.PROMPT_VERSION
    ): String {
        return "$bvid:$cid:$model:v$promptVersion:$transcriptHash"
    }

    suspend fun getSummary(
        context: Context,
        cacheKey: String
    ): VideoSummary? = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getDatabase(context).aiSummaryCacheDao()
            val entity = dao.getCache(cacheKey) ?: return@withContext null
            dao.updateAccessTime(cacheKey, System.currentTimeMillis())
            json.decodeFromString<VideoSummary>(entity.resultJson)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getLatestSummaryForVideo(
        context: Context,
        bvid: String,
        cid: Long
    ): VideoSummary? = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getDatabase(context).aiSummaryCacheDao()
            val entity = dao.getLatestCacheForVideo(bvid, cid) ?: return@withContext null
            json.decodeFromString<VideoSummary>(entity.resultJson)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveSummary(
        context: Context,
        summary: VideoSummary,
        bvid: String,
        cid: Long,
        cacheKey: String,
        transcriptJson: String? = null
    ) = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getDatabase(context).aiSummaryCacheDao()
            val serialized = json.encodeToString(summary)
            val entity = AiSummaryCacheEntity(
                cacheKey = cacheKey,
                bvid = bvid,
                cid = cid,
                source = summary.source.name,
                resultJson = serialized,
                transcriptJson = transcriptJson,
                createdAt = summary.createdAt,
                lastAccessAt = System.currentTimeMillis()
            )
            dao.insertCache(entity)
            dao.trimLru()
        } catch (e: Exception) {
            // 忽略写入失败
        }
    }

    suspend fun clearAllCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            AppDatabase.getDatabase(context).aiSummaryCacheDao().clearAllCache()
        } catch (e: Exception) {
            // 忽略
        }
    }

    suspend fun getCacheCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            AppDatabase.getDatabase(context).aiSummaryCacheDao().getCacheCount()
        } catch (e: Exception) {
            0
        }
    }
}
