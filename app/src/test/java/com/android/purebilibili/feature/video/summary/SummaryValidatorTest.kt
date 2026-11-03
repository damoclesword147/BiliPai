package com.android.purebilibili.feature.video.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryValidatorTest {

    private val sampleChunks = listOf(
        SummaryComposer.ChunkItem(1, 0L, 20_000L, "开场介绍与背景"),
        SummaryComposer.ChunkItem(2, 25_000L, 50_000L, "第一部分：核心原理"),
        SummaryComposer.ChunkItem(3, 55_000L, 80_000L, "第二部分：实践案例"),
        SummaryComposer.ChunkItem(4, 85_000L, 110_000L, "总结与展望")
    )

    private val identity = VideoIdentity(
        bvid = "BV1xx411c7mD",
        cid = 123456L,
        title = "测试视频",
        ownerName = "测试UP主"
    )

    @Test
    fun cleanJsonString_stripsMarkdownFencesAndPreamble() {
        val markdownJson = """
            这是分析结果：
            ```json
            {
              "summary": "全片核心总结",
              "keyPoints": ["要点1"],
              "chapters": []
            }
            ```
            以上为全部内容。
        """.trimIndent()

        val cleaned = SummaryValidator.cleanJsonString(markdownJson)
        assertTrue(cleaned.startsWith("{"))
        assertTrue(cleaned.endsWith("}"))
        assertTrue(cleaned.contains("\"summary\": \"全片核心总结\""))
    }

    @Test
    fun parseAndValidate_mapsStartIdToStartMsCorrectly() {
        val rawResponse = """
            {
              "summary": "这是一段视频总览摘要",
              "keyPoints": ["要点一", "要点二"],
              "chapters": [
                {
                  "startId": 1,
                  "endId": 1,
                  "title": "背景介绍",
                  "points": ["介绍背景"]
                },
                {
                  "startId": 2,
                  "endId": 2,
                  "title": "核心原理剖析",
                  "points": ["原理分析"]
                },
                {
                  "startId": 999,
                  "endId": 999,
                  "title": "非法编号章节",
                  "points": ["该章节不存在"]
                }
              ]
            }
        """.trimIndent()

        val result = SummaryValidator.parseAndValidate(
            rawContent = rawResponse,
            chunks = sampleChunks,
            identity = identity,
            modelName = "deepseek-chat",
            source = SummarySource.LLM_SUBTITLE
        )

        assertTrue(result.isSuccess)
        val summary = result.getOrThrow()
        assertEquals("这是一段视频总览摘要", summary.summary)
        assertEquals(2, summary.keyPoints.size)
        // 应该只有 2 个合法章节，非法 startId=999 被丢弃
        assertEquals(2, summary.chapters.size)
        assertEquals(0L, summary.chapters[0].startMs)
        assertEquals(25_000L, summary.chapters[1].startMs)
    }

    @Test
    fun adaptToAiSummaryData_convertsTimestampToSecondsForCard() {
        val summary = VideoSummary(
            summary = "视频总结",
            keyPoints = listOf("重点"),
            chapters = listOf(
                SummaryChapter(
                    startId = 1,
                    title = "第一章",
                    points = listOf("要点A"),
                    startMs = 30_000L // 30 秒
                ),
                SummaryChapter(
                    startId = 2,
                    title = "第二章",
                    points = listOf("要点B"),
                    startMs = 95_000L // 95 秒
                )
            ),
            source = SummarySource.LLM_SUBTITLE
        )

        val aiSummaryData = SummaryValidator.adaptToAiSummaryData(summary)
        assertNotNull(aiSummaryData.modelResult)
        val outlines = aiSummaryData.modelResult!!.outline
        assertEquals(2, outlines.size)
        // AiSummaryCard 在显示时会乘 1000L，所以此处必须是秒数 (30L, 95L)
        assertEquals(30L, outlines[0].timestamp)
        assertEquals(95L, outlines[1].timestamp)
        assertEquals(30L, outlines[0].partOutline[0].timestamp)
    }
}
