package com.android.purebilibili.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_summary_cache")
data class AiSummaryCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val bvid: String,
    val cid: Long,
    val source: String,          // OFFICIAL / LLM_SUBTITLE / LLM_ASR
    val resultJson: String,      // 序列化的 VideoSummary
    val transcriptJson: String?, // 中间字幕（含 ASR），用于重试 / 导出
    val createdAt: Long,
    val lastAccessAt: Long
)
