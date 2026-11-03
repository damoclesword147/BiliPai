package com.android.purebilibili.feature.video.summary

import com.android.purebilibili.core.subtitle.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryComposerTest {

    @Test
    fun composeChunksFromCues_aggregatesIntoWindows() {
        val cues = listOf(
            SubtitleCue(startMs = 0L, endMs = 5_000L, content = "大家好"),
            SubtitleCue(startMs = 6_000L, endMs = 12_000L, content = "欢迎来到本期视频"),
            SubtitleCue(startMs = 30_000L, endMs = 35_000L, content = "今天我们来聊聊架构设计"),
            SubtitleCue(startMs = 36_000L, endMs = 42_000L, content = "首先是第一部分")
        )

        val chunks = SummaryComposer.composeChunksFromCues(cues)
        assertEquals(2, chunks.size)
        assertEquals(1, chunks[0].blockId)
        assertEquals(0L, chunks[0].startMs)
        assertTrue(chunks[0].text.contains("大家好 欢迎来到本期视频"))

        assertEquals(2, chunks[1].blockId)
        assertEquals(30_000L, chunks[1].startMs)
        assertTrue(chunks[1].text.contains("今天我们来聊聊架构设计 首先是第一部分"))
    }

    @Test
    fun formatDuration_formatsProperly() {
        assertEquals("00:00", SummaryComposer.formatDuration(0L))
        assertEquals("01:30", SummaryComposer.formatDuration(90_000L))
        assertEquals("65:05", SummaryComposer.formatDuration(3_905_000L))
    }

    @Test
    fun computeTranscriptHash_producesStableHash() {
        val chunks1 = listOf(
            SummaryComposer.ChunkItem(1, 0L, 5000L, "Hello"),
            SummaryComposer.ChunkItem(2, 6000L, 12000L, "World")
        )
        val chunks2 = listOf(
            SummaryComposer.ChunkItem(1, 0L, 5000L, "Hello"),
            SummaryComposer.ChunkItem(2, 6000L, 12000L, "World")
        )

        val hash1 = SummaryComposer.computeTranscriptHash(chunks1)
        val hash2 = SummaryComposer.computeTranscriptHash(chunks2)
        assertEquals(hash1, hash2)
        assertTrue(hash1.isNotEmpty())
    }
}
