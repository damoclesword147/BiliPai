package com.android.purebilibili.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.android.purebilibili.core.database.entity.AiSummaryCacheEntity

@Dao
interface AiSummaryCacheDao {
    @Query("SELECT * FROM ai_summary_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getCache(cacheKey: String): AiSummaryCacheEntity?

    @Query("SELECT * FROM ai_summary_cache WHERE bvid = :bvid AND cid = :cid ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestCacheForVideo(bvid: String, cid: Long): AiSummaryCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(cache: AiSummaryCacheEntity)

    @Query("UPDATE ai_summary_cache SET lastAccessAt = :accessTime WHERE cacheKey = :cacheKey")
    suspend fun updateAccessTime(cacheKey: String, accessTime: Long)

    @Query("DELETE FROM ai_summary_cache WHERE cacheKey = :cacheKey")
    suspend fun deleteCache(cacheKey: String)

    @Query("DELETE FROM ai_summary_cache")
    suspend fun clearAllCache()

    @Query("SELECT COUNT(*) FROM ai_summary_cache")
    suspend fun getCacheCount(): Int

    // 保留最近 200 条，修剪超出部分
    @Query("DELETE FROM ai_summary_cache WHERE cacheKey NOT IN (SELECT cacheKey FROM ai_summary_cache ORDER BY lastAccessAt DESC LIMIT 200)")
    suspend fun trimLru()
}
