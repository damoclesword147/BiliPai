package com.android.purebilibili.feature.video.summary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 独立的 AI API HTTP 客户端
 * 严格与 B 站业务 OkHttpClient 隔离，不包含任何 B 站 Cookie 或认证拦截器
 */
object CustomAiHttpClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false) // 避免跨域重定向泄漏 Authorization
        .build()

    fun normalizeBaseUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.dropLast(1)
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        // 如果端点既没有 /v1 也没有其它路径，且是标准 OpenAI 兼容服务，补充 /v1
        val isDomainOnly = !trimmed.substringAfter("://").contains("/")
        return if (isDomainOnly) "$trimmed/v1" else trimmed
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }
        })
    }

    /**
     * 发送 OpenAI 兼容 Chat Completions 请求
     */
    suspend fun chatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        timeoutSeconds: Int = 120,
        temperature: Double = 0.3
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = normalizeBaseUrl(baseUrl)
            val endpoint = if (normalizedBase.endsWith("/chat/completions")) {
                normalizedBase
            } else {
                "$normalizedBase/chat/completions"
            }

            val client = baseHttpClient.newBuilder()
                .readTimeout(timeoutSeconds.toLong().coerceAtLeast(15L), TimeUnit.SECONDS)
                .build()

            val requestBodyJson = buildChatCompletionPayload(
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = temperature
            )

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = client.newCall(request).await()
            val responseBody = response.body.string()

            if (!response.isSuccessful) {
                val errorMessage = extractErrorMessage(response.code, responseBody)
                return@withContext Result.failure(Exception(errorMessage))
            }

            val content = extractChatCompletionContent(responseBody)
            if (content.isNullOrBlank()) {
                return@withContext Result.failure(Exception("AI 模型返回内容为空"))
            }

            Result.success(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 测试连接：发送最小请求验证 API Key 与模型可用性
     */
    suspend fun testConnection(
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<Long> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            val normalizedBase = normalizeBaseUrl(baseUrl)
            val endpoint = if (normalizedBase.endsWith("/chat/completions")) {
                normalizedBase
            } else {
                "$normalizedBase/chat/completions"
            }

            val client = baseHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val payload = """
                {
                  "model": "$model",
                  "messages": [{"role": "user", "content": "hi"}],
                  "max_tokens": 5
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = client.newCall(request).await()
            val latency = System.currentTimeMillis() - startTime
            val body = response.body.string()

            if (!response.isSuccessful) {
                val err = extractErrorMessage(response.code, body)
                return@withContext Result.failure(Exception(err))
            }

            Result.success(latency)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * OpenAI 兼容音频转写 POST {base}/audio/transcriptions
     * 要求返回 verbose_json 格式的时间戳分段
     */
    suspend fun transcribeAudio(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioFile: File,
        language: String = "zh"
    ): Result<List<TranscriptSegment>> = withContext(Dispatchers.IO) {
        try {
            val normalizedBase = normalizeBaseUrl(baseUrl)
            val endpoint = if (normalizedBase.endsWith("/audio/transcriptions")) {
                normalizedBase
            } else {
                "$normalizedBase/audio/transcriptions"
            }

            val client = baseHttpClient.newBuilder()
                .readTimeout(180, TimeUnit.SECONDS)
                .build()

            val fileBody = audioFile.asRequestBody("audio/mp4".toMediaType())
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")

            if (language.isNotBlank()) {
                multipartBuilder.addFormDataPart("language", language)
            }

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(multipartBuilder.build())
                .build()

            val response = client.newCall(request).await()
            val body = response.body.string()

            if (!response.isSuccessful) {
                val err = extractErrorMessage(response.code, body)
                return@withContext Result.failure(Exception(err))
            }

            val segments = parseTranscriptionSegments(body)
            if (segments.isEmpty()) {
                return@withContext Result.failure(Exception("音频中未识别到有效语音文字"))
            }

            Result.success(segments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildChatCompletionPayload(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double
    ): String {
        val root = kotlinx.serialization.json.buildJsonObject {
            put("model", kotlinx.serialization.json.JsonPrimitive(model))
            put("temperature", kotlinx.serialization.json.JsonPrimitive(temperature))
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", kotlinx.serialization.json.JsonPrimitive("system"))
                    put("content", kotlinx.serialization.json.JsonPrimitive(systemPrompt))
                })
                add(kotlinx.serialization.json.buildJsonObject {
                    put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                    put("content", kotlinx.serialization.json.JsonPrimitive(userPrompt))
                })
            })
        }
        return root.toString()
    }

    private fun extractChatCompletionContent(responseBody: String): String? {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val choices = root["choices"]?.jsonArray
            val firstChoice = choices?.firstOrNull()?.jsonObject
            val message = firstChoice?.get("message")?.jsonObject
            message?.get("content")?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }

    private fun extractErrorMessage(statusCode: Int, responseBody: String): String {
        val parsedError = try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val errorObj = root["error"]?.jsonObject
            errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }

        return when (statusCode) {
            401 -> "API Key 无效或未授权 (401)${parsedError?.let { ": $it" } ?: ""}"
            403 -> "无权限访问该模型或服务 (403)${parsedError?.let { ": $it" } ?: ""}"
            404 -> "API 地址不存在或模型名错误 (404)${parsedError?.let { ": $it" } ?: ""}"
            429 -> "请求过于频繁或配额不足 (429)${parsedError?.let { ": $it" } ?: ""}"
            500, 502, 503, 504 -> "服务提供商服务器异常 ($statusCode)${parsedError?.let { ": $it" } ?: ""}"
            else -> parsedError ?: "请求失败 (HTTP $statusCode)"
        }
    }

    fun parseTranscriptionSegments(responseBody: String): List<TranscriptSegment> {
        val result = mutableListOf<TranscriptSegment>()
        try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val segmentsArray = root["segments"]?.jsonArray
            if (segmentsArray != null) {
                var segmentIndex = 1
                for (item in segmentsArray) {
                    val obj = item.jsonObject
                    val text = obj["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                    if (text.isBlank()) continue
                    val startSec = obj["start"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                    val endSec = obj["end"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: (startSec + 2.0)
                    result.add(
                        TranscriptSegment(
                            id = segmentIndex++,
                            startMs = (startSec * 1000).toLong(),
                            endMs = (endSec * 1000).toLong(),
                            text = text,
                            source = "asr"
                        )
                    )
                }
            } else {
                // 如果没有 segments 字段但有完整 text，构建单条作为兜底
                val fullText = root["text"]?.jsonPrimitive?.contentOrNull?.trim()
                if (!fullText.isNullOrBlank()) {
                    result.add(
                        TranscriptSegment(
                            id = 1,
                            startMs = 0L,
                            endMs = 10_000L,
                            text = fullText,
                            source = "asr"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // 解析失败返回空列表
        }
        return result
    }
}
