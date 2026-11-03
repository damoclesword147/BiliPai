package com.android.purebilibili.feature.video.summary

import com.android.purebilibili.data.model.response.AiSummaryData
import kotlinx.serialization.Serializable

/**
 * 总结来源类型
 */
enum class SummarySource(val label: String, val badgeText: String) {
    OFFICIAL("官方总结", "官方 ⚡"),
    LLM_SUBTITLE("字幕总结", "字幕 ✨"),
    LLM_ASR("语音转写", "语音转写 🎙");

    val isCustom: Boolean
        get() = this != OFFICIAL
}

/**
 * 字幕或语音转写片段
 */
@Serializable
data class TranscriptSegment(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val source: String = "subtitle"
)

/**
 * 视频总结中的章节大纲
 */
@Serializable
data class SummaryChapter(
    val startId: Int,
    val endId: Int? = null,
    val title: String,
    val points: List<String> = emptyList(),
    val startMs: Long = 0L
)

/**
 * 内部统一视频总结模型
 */
@Serializable
data class VideoSummary(
    val summary: String,
    val keyPoints: List<String> = emptyList(),
    val chapters: List<SummaryChapter> = emptyList(),
    val provider: String = "custom",
    val model: String = "",
    val transcriptHash: String = "",
    val promptVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val language: String = "zh-CN",
    val source: SummarySource = SummarySource.LLM_SUBTITLE
)

/**
 * 总结执行阶段
 */
enum class SummaryStage {
    IDLE,
    CHECKING_CACHE,
    LOADING_TRANSCRIPT,
    AWAITING_ASR_CONSENT,
    DOWNLOADING_AUDIO,
    TRANSCRIBING,
    COMPOSING,
    SUMMARIZING,
    VALIDATING,
    SUCCESS,
    ERROR
}

/**
 * 视频身份标识
 */
data class VideoIdentity(
    val bvid: String,
    val cid: Long,
    val title: String = "",
    val ownerName: String = "",
    val durationMs: Long = 0L,
    val upMid: Long = 0L
)

/**
 * 生成任务标识，用于防串结果
 */
data class GenerationIdentity(
    val bvid: String,
    val cid: Long,
    val generationId: Long,
    val configFingerprint: String
)

/**
 * 总结 UI 状态
 */
data class CustomSummaryUiState(
    val stage: SummaryStage = SummaryStage.IDLE,
    val source: SummarySource = SummarySource.OFFICIAL,
    val progress: Float = 0f,
    val progressMessage: String = "",
    val summary: VideoSummary? = null,
    val adaptedAiSummary: AiSummaryData? = null,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val showAsrConsentDialog: Boolean = false,
    val isKeyConfigured: Boolean = false
)
