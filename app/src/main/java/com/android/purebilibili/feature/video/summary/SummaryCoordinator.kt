package com.android.purebilibili.feature.video.summary

import android.content.Context
import com.android.purebilibili.core.subtitle.SubtitleCue
import com.android.purebilibili.data.repository.SubtitleContentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 视频总结业务调度核心控制器
 */
class SummaryCoordinator(
    private val context: Context
) {
    private val _uiState = MutableStateFlow(CustomSummaryUiState())
    val uiState: StateFlow<CustomSummaryUiState> = _uiState.asStateFlow()

    private var currentGenerationId = 0L

    fun reset() {
        currentGenerationId++
        _uiState.value = CustomSummaryUiState(
            stage = SummaryStage.IDLE,
            isKeyConfigured = AiKeyStore.hasLlmApiKey(context)
        )
    }

    /**
     * 触发生成流程
     * @param bypassCache 是否强制重新生成（绕过缓存）
     */
    suspend fun generateSummary(
        identity: VideoIdentity,
        bypassCache: Boolean = false,
        onAsrConsentRequested: () -> Unit = {}
    ) {
        val generationId = ++currentGenerationId
        val apiKey = AiKeyStore.getLlmApiKey(context)
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                stage = SummaryStage.ERROR,
                errorMessage = "尚未配置 AI API Key，请点击设置进行配置",
                canRetry = false,
                isKeyConfigured = false
            )
            return
        }

        val config = AiProviderSettings.getConfigFlow(context).first()

        fun isCurrentTaskValid(): Boolean = (generationId == currentGenerationId)

        try {
            // 1. 检查缓存
            if (!bypassCache) {
                _uiState.value = _uiState.value.copy(
                    stage = SummaryStage.CHECKING_CACHE,
                    progressMessage = "正在检索本地总结缓存..."
                )
                val cached = SummaryCacheRepository.getLatestSummaryForVideo(
                    context = context,
                    bvid = identity.bvid,
                    cid = identity.cid
                )
                if (cached != null && isCurrentTaskValid()) {
                    val adapted = SummaryValidator.adaptToAiSummaryData(cached)
                    _uiState.value = _uiState.value.copy(
                        stage = SummaryStage.SUCCESS,
                        source = cached.source,
                        summary = cached,
                        adaptedAiSummary = adapted,
                        progressMessage = ""
                    )
                    return
                }
            }

            // 2. 加载字幕
            _uiState.value = _uiState.value.copy(
                stage = SummaryStage.LOADING_TRANSCRIPT,
                progressMessage = "正在获取视频字幕信息..."
            )

            val subtitles = fetchSubtitles(identity.bvid, identity.cid)
            var chunks: List<SummaryComposer.ChunkItem>
            var source = SummarySource.LLM_SUBTITLE

            if (subtitles.isNotEmpty()) {
                chunks = SummaryComposer.composeChunksFromCues(subtitles)
            } else {
                // 3. 字幕不存在，检查 ASR 兜底
                if (!config.enableAsr) {
                    _uiState.value = _uiState.value.copy(
                        stage = SummaryStage.ERROR,
                        errorMessage = "当前视频未提供字幕，且未开启语音转写 (ASR) 功能",
                        canRetry = true
                    )
                    return
                }

                // 请求用户 ASR 上传同意
                _uiState.value = _uiState.value.copy(
                    stage = SummaryStage.AWAITING_ASR_CONSENT,
                    showAsrConsentDialog = true,
                    progressMessage = "等待确认上传音频以进行转写..."
                )
                onAsrConsentRequested()
                return // 等待用户在 UI 弹窗上点击确认后调用 continueWithAsr(...)
            }

            // 4. 输入合成与提示词构建
            executeLlmSummarization(
                identity = identity,
                chunks = chunks,
                source = source,
                config = config,
                apiKey = apiKey,
                generationId = generationId
            )

        } catch (e: CancellationException) {
            AudioPreparation.cleanTempFiles(context)
            throw e
        } catch (e: Exception) {
            if (isCurrentTaskValid()) {
                _uiState.value = _uiState.value.copy(
                    stage = SummaryStage.ERROR,
                    errorMessage = e.message ?: "生成总结失败，请重试",
                    canRetry = true
                )
            }
        }
    }

    /**
     * 用户同意 ASR 转写后继续执行
     */
    suspend fun continueWithAsr(identity: VideoIdentity) {
        val generationId = currentGenerationId
        val config = AiProviderSettings.getConfigFlow(context).first()
        val asrApiKey = AiKeyStore.getAsrApiKey(context).ifBlank {
            AiKeyStore.getLlmApiKey(context)
        }

        if (asrApiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                stage = SummaryStage.ERROR,
                showAsrConsentDialog = false,
                errorMessage = "未配置 ASR API Key",
                canRetry = false
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            stage = SummaryStage.DOWNLOADING_AUDIO,
            showAsrConsentDialog = false,
            progress = 0f,
            progressMessage = "正在提取音频流并下载..."
        )

        try {
            // 解析音频流地址
            val audioUrlResult = AudioPreparation.resolveAudioUrl(identity.bvid, identity.cid)
            val audioUrl = audioUrlResult.getOrThrow()

            // 下载临时音频
            val downloadResult = AudioPreparation.downloadAudioFile(
                context = context,
                audioUrl = audioUrl,
                bvid = identity.bvid,
                cid = identity.cid,
                onProgress = { p ->
                    if (generationId == currentGenerationId) {
                        _uiState.value = _uiState.value.copy(
                            progress = p,
                            progressMessage = "正在下载音频片段 (${(p * 100).toInt()}%)..."
                        )
                    }
                }
            )
            val audioFile = downloadResult.getOrThrow()

            // 调用 ASR 转写
            _uiState.value = _uiState.value.copy(
                stage = SummaryStage.TRANSCRIBING,
                progress = 0f,
                progressMessage = "正在调用语音识别服务转写文本..."
            )

            val asrResult = CustomAiHttpClient.transcribeAudio(
                baseUrl = config.effectiveAsrBaseUrl(),
                apiKey = asrApiKey,
                model = config.asrModel,
                audioFile = audioFile,
                language = "zh"
            )

            // 清理临时文件
            audioFile.delete()

            val segments = asrResult.getOrThrow()
            val chunks = SummaryComposer.composeChunksFromSegments(segments)

            // 继续执行 LLM 总结
            executeLlmSummarization(
                identity = identity,
                chunks = chunks,
                source = SummarySource.LLM_ASR,
                config = config,
                apiKey = AiKeyStore.getLlmApiKey(context),
                generationId = generationId
            )
        } catch (e: CancellationException) {
            AudioPreparation.cleanTempFiles(context)
            throw e
        } catch (e: Exception) {
            AudioPreparation.cleanTempFiles(context)
            if (generationId == currentGenerationId) {
                _uiState.value = _uiState.value.copy(
                    stage = SummaryStage.ERROR,
                    errorMessage = "音频转写失败: ${e.message}",
                    canRetry = true
                )
            }
        }
    }

    private suspend fun executeLlmSummarization(
        identity: VideoIdentity,
        chunks: List<SummaryComposer.ChunkItem>,
        source: SummarySource,
        config: AiProviderConfig,
        apiKey: String,
        generationId: Long
    ) {
        if (generationId != currentGenerationId) return

        _uiState.value = _uiState.value.copy(
            stage = SummaryStage.SUMMARIZING,
            progressMessage = "正在生成 AI 视频总结与大纲..."
        )

        val systemPrompt = SummaryComposer.getSystemPrompt(config.outputLanguage)
        val userPrompt = SummaryComposer.buildUserPrompt(chunks, identity)

        val llmResult = CustomAiHttpClient.chatCompletions(
            baseUrl = config.llmBaseUrl,
            apiKey = apiKey,
            model = config.llmModel,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            timeoutSeconds = config.timeoutSeconds
        )

        val rawContent = llmResult.getOrThrow()

        // 校验与映射
        _uiState.value = _uiState.value.copy(
            stage = SummaryStage.VALIDATING,
            progressMessage = "正在校验章节时间戳与完整性..."
        )

        val validateResult = SummaryValidator.parseAndValidate(
            rawContent = rawContent,
            chunks = chunks,
            identity = identity,
            modelName = config.llmModel,
            source = source
        )

        val finalSummary = validateResult.getOrThrow()
        val adaptedAiSummary = SummaryValidator.adaptToAiSummaryData(finalSummary)

        // 存入 Room 缓存
        val cacheKey = SummaryCacheRepository.buildCacheKey(
            bvid = identity.bvid,
            cid = identity.cid,
            model = config.llmModel,
            transcriptHash = finalSummary.transcriptHash
        )
        SummaryCacheRepository.saveSummary(
            context = context,
            summary = finalSummary,
            bvid = identity.bvid,
            cid = identity.cid,
            cacheKey = cacheKey
        )

        if (generationId == currentGenerationId) {
            _uiState.value = _uiState.value.copy(
                stage = SummaryStage.SUCCESS,
                source = source,
                summary = finalSummary,
                adaptedAiSummary = adaptedAiSummary,
                progressMessage = ""
            )
        }
    }

    private suspend fun fetchSubtitles(bvid: String, cid: Long): List<SubtitleCue> {
        return try {
            val playerInfoResult = SubtitleContentRepository.playerInfo(bvid, cid)
            val playerInfo = playerInfoResult.getOrNull() ?: return emptyList()
            val subtitleList = playerInfo.subtitle?.subtitles.orEmpty()
            if (subtitleList.isEmpty()) return emptyList()

            // 选轨优先级：ai_zh -> zh-CN -> zh-Hans -> 第一个
            val bestTrack = subtitleList.firstOrNull { it.lan == "ai_zh" }
                ?: subtitleList.firstOrNull { it.lan == "zh-CN" }
                ?: subtitleList.firstOrNull { it.lan.startsWith("zh") }
                ?: subtitleList.first()

            val cuesResult = SubtitleContentRepository.cues(
                subtitleUrl = bestTrack.subtitleUrl,
                bvid = bvid,
                cid = cid,
                subtitleId = bestTrack.id,
                subtitleIdStr = bestTrack.idStr,
                subtitleLan = bestTrack.lan
            )
            cuesResult.getOrDefault(emptyList())
        } catch (e: Exception) {
            emptyList()
        }
    }
}
