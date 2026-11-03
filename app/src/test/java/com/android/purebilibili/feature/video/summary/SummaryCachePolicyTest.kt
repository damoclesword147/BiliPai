package com.android.purebilibili.feature.video.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SummaryCachePolicyTest {

    @Test
    fun buildCacheKey_incorporatesIdentityModelAndHash() {
        val key1 = SummaryCacheRepository.buildCacheKey(
            bvid = "BV1xx411c7mD",
            cid = 12345L,
            model = "deepseek-chat",
            transcriptHash = "hash_abc",
            promptVersion = 1
        )
        val key2 = SummaryCacheRepository.buildCacheKey(
            bvid = "BV1xx411c7mD",
            cid = 12345L,
            model = "deepseek-chat",
            transcriptHash = "hash_abc",
            promptVersion = 1
        )
        assertEquals(key1, key2)
        assertEquals("BV1xx411c7mD:12345:deepseek-chat:v1:hash_abc", key1)

        val keyDiffModel = SummaryCacheRepository.buildCacheKey(
            bvid = "BV1xx411c7mD",
            cid = 12345L,
            model = "gpt-4o",
            transcriptHash = "hash_abc",
            promptVersion = 1
        )
        assertNotEquals(key1, keyDiffModel)

        val keyDiffPrompt = SummaryCacheRepository.buildCacheKey(
            bvid = "BV1xx411c7mD",
            cid = 12345L,
            model = "deepseek-chat",
            transcriptHash = "hash_abc",
            promptVersion = 2
        )
        assertNotEquals(key1, keyDiffPrompt)
    }
}
