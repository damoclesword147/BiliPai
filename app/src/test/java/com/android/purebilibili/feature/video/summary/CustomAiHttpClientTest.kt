package com.android.purebilibili.feature.video.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAiHttpClientTest {

    @Test
    fun normalizeBaseUrl_addsHttpsAndV1() {
        assertEquals("https://api.deepseek.com/v1", CustomAiHttpClient.normalizeBaseUrl("api.deepseek.com"))
        assertEquals("https://api.deepseek.com/v1", CustomAiHttpClient.normalizeBaseUrl("https://api.deepseek.com/"))
        assertEquals("https://api.openai.com/v1", CustomAiHttpClient.normalizeBaseUrl("https://api.openai.com/v1/"))
        assertEquals("http://localhost:11434/v1", CustomAiHttpClient.normalizeBaseUrl("http://localhost:11434/v1"))
    }

    @Test
    fun parseTranscriptionSegments_parsesVerboseJsonFormat() {
        val sampleVerboseJson = """
            {
              "task": "transcribe",
              "language": "chinese",
              "duration": 12.5,
              "text": "你好世界。这是语音转写测试。",
              "segments": [
                {
                  "id": 0,
                  "seek": 0,
                  "start": 0.0,
                  "end": 4.5,
                  "text": "你好世界。",
                  "tokens": [1, 2, 3]
                },
                {
                  "id": 1,
                  "seek": 450,
                  "start": 4.5,
                  "end": 9.8,
                  "text": "这是语音转写测试。",
                  "tokens": [4, 5, 6]
                }
              ]
            }
        """.trimIndent()

        val segments = CustomAiHttpClient.parseTranscriptionSegments(sampleVerboseJson)
        assertEquals(2, segments.size)
        assertEquals(1, segments[0].id)
        assertEquals(0L, segments[0].startMs)
        assertEquals(4500L, segments[0].endMs)
        assertEquals("你好世界。", segments[0].text)

        assertEquals(2, segments[1].id)
        assertEquals(4500L, segments[1].startMs)
        assertEquals(9800L, segments[1].endMs)
        assertEquals("这是语音转写测试。", segments[1].text)
    }
}
