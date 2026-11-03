package com.android.purebilibili.feature.video.summary

import android.content.Context
import com.android.purebilibili.data.model.response.getBestAudio
import com.android.purebilibili.data.repository.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 负责解析 DASH 音频流、下载临时音频以供 ASR 转写
 */
object AudioPreparation {
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 解析普通 AAC 音轨地址
     */
    suspend fun resolveAudioUrl(bvid: String, cid: Long): Result<String> = withContext(Dispatchers.IO) {
        try {
            val playData = VideoRepository.getPlayUrlData(bvid = bvid, cid = cid, qn = 64)
                ?: return@withContext Result.failure(Exception("无法获取视频播放流信息"))

            val dash = playData.dash
                ?: return@withContext Result.failure(Exception("当前视频无 DASH 流，无法提取音轨"))

            val audioUrl = dash.getBestAudio()?.getValidUrl()
            if (audioUrl.isNullOrBlank()) {
                return@withContext Result.failure(Exception("音频流下载地址为空"))
            }

            Result.success(audioUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 下载音频到应用私有临时目录并上报进度 (0.0f - 1.0f)
     */
    suspend fun downloadAudioFile(
        context: Context,
        audioUrl: String,
        bvid: String,
        cid: Long,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val asrDir = File(context.cacheDir, "asr").apply { if (!exists()) mkdirs() }
        val targetFile = File(asrDir, "${bvid}_${cid}_${System.currentTimeMillis()}.m4a")

        try {
            val request = Request.Builder()
                .url(audioUrl)
                .header("Referer", "https://www.bilibili.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("音频下载失败 (HTTP ${response.code})"))
                }

                val body = response.body
                val contentLength = body.contentLength()
                var downloadedBytes = 0L

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (contentLength > 0) {
                                val progress = (downloadedBytes.toFloat() / contentLength).coerceIn(0f, 1f)
                                onProgress(progress)
                            }
                        }
                    }
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                targetFile.delete()
                return@withContext Result.failure(Exception("下载的音频文件为空"))
            }

            Result.success(targetFile)
        } catch (e: CancellationException) {
            targetFile.delete()
            throw e
        } catch (e: Exception) {
            targetFile.delete()
            Result.failure(e)
        }
    }

    /**
     * 清理所有临时 ASR 音频文件
     */
    fun cleanTempFiles(context: Context) {
        try {
            val asrDir = File(context.cacheDir, "asr")
            if (asrDir.exists() && asrDir.isDirectory) {
                asrDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            // 忽略
        }
    }
}
