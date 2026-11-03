package com.android.purebilibili.feature.video.summary

import com.android.purebilibili.data.model.response.AiModelResult
import com.android.purebilibili.data.model.response.AiOutline
import com.android.purebilibili.data.model.response.AiPartOutline
import com.android.purebilibili.data.model.response.AiSummaryData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 校验器与数据适配器（纯函数）
 */
object SummaryValidator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    private data class RawSummaryResponse(
        val summary: String = "",
        val keyPoints: List<String> = emptyList(),
        val chapters: List<RawChapterItem> = emptyList()
    )

    @Serializable
    private data class RawChapterItem(
        val startId: Int = 0,
        val endId: Int? = null,
        val title: String = "",
        val points: List<String> = emptyList()
    )

    /**
     * 清洗和容错提取 JSON 字符串
     */
    fun cleanJsonString(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```json")) {
            text = text.removePrefix("```json").trim()
        } else if (text.startsWith("```")) {
            text = text.removePrefix("```").trim()
        }
        if (text.endsWith("```")) {
            text = text.removeSuffix("```").trim()
        }

        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            text = text.substring(firstBrace, lastBrace + 1).trim()
        }
        return text
    }

    /**
     * 解析并校验模型输出
     */
    fun parseAndValidate(
        rawContent: String,
        chunks: List<SummaryComposer.ChunkItem>,
        identity: VideoIdentity,
        modelName: String,
        source: SummarySource
    ): Result<VideoSummary> {
        val cleanedJson = cleanJsonString(rawContent)
        val rawResponse = try {
            json.decodeFromString<RawSummaryResponse>(cleanedJson)
        } catch (e: Exception) {
            return Result.failure(Exception("无法解析模型输出为有效 JSON 格式: ${e.message}"))
        }

        if (rawResponse.summary.isBlank() && rawResponse.chapters.isEmpty()) {
            return Result.failure(Exception("模型返回的内容缺少摘要和大纲"))
        }

        val chunkMap = chunks.associateBy { it.blockId }
        val validChapters = mutableListOf<SummaryChapter>()

        for (rawChapter in rawResponse.chapters) {
            val chunk = chunkMap[rawChapter.startId] ?: continue
            val safeTitle = rawChapter.title.trim().take(24)
            if (safeTitle.isBlank()) continue

            val safePoints = rawChapter.points
                .map { it.trim().take(60) }
                .filter { it.isNotBlank() }

            validChapters.add(
                SummaryChapter(
                    startId = rawChapter.startId,
                    endId = rawChapter.endId,
                    title = safeTitle,
                    points = safePoints,
                    startMs = chunk.startMs
                )
            )
        }

        // 按时间排序
        val sortedChapters = validChapters.sortedBy { it.startMs }

        // 去重合并：相邻开始时间差小于 5 秒的章节进行合并
        val deduplicatedChapters = mutableListOf<SummaryChapter>()
        for (chapter in sortedChapters) {
            if (deduplicatedChapters.isEmpty()) {
                deduplicatedChapters.add(chapter)
            } else {
                val last = deduplicatedChapters.last()
                if (chapter.startMs - last.startMs < 5_000L) {
                    val mergedPoints = (last.points + chapter.points).distinct().take(3)
                    deduplicatedChapters[deduplicatedChapters.lastIndex] = last.copy(
                        points = mergedPoints
                    )
                } else {
                    deduplicatedChapters.add(chapter)
                }
            }
        }

        val finalSummary = VideoSummary(
            summary = rawResponse.summary.trim(),
            keyPoints = rawResponse.keyPoints.map { it.trim() }.filter { it.isNotBlank() },
            chapters = deduplicatedChapters,
            provider = "custom",
            model = modelName,
            transcriptHash = SummaryComposer.computeTranscriptHash(chunks),
            promptVersion = SummaryComposer.PROMPT_VERSION,
            createdAt = System.currentTimeMillis(),
            language = "zh-CN",
            source = source
        )

        return Result.success(finalSummary)
    }

    /**
     * 将内部统一的 VideoSummary 转换为已有的 AiSummaryData
     * 注意：AiSummaryCard 内部对 timestamp 会执行 * 1000L，因此 AiOutline.timestamp 必须是秒数！
     */
    fun adaptToAiSummaryData(summary: VideoSummary): AiSummaryData {
        val outlines = summary.chapters.map { chapter ->
            val timestampSec = (chapter.startMs / 1000L).coerceAtLeast(0L)
            AiOutline(
                title = chapter.title,
                partOutline = chapter.points.map { point ->
                    AiPartOutline(
                        timestamp = timestampSec, // 要点统一使用所属章节的秒数
                        content = point
                    )
                },
                timestamp = timestampSec
            )
        }

        val modelResult = AiModelResult(
            resultType = 2, // 2: 摘要+大纲
            summary = summary.summary,
            outline = outlines,
            subtitle = emptyList()
        )

        return AiSummaryData(
            code = 0,
            modelResult = modelResult,
            stid = "custom_${summary.source.name.lowercase()}",
            status = 0
        )
    }
}
