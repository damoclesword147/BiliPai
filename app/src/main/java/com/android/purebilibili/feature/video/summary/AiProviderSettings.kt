package com.android.purebilibili.feature.video.summary

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.android.purebilibili.core.store.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * AI 提供商配置模型与存储
 */
data class AiProviderConfig(
    val llmBaseUrl: String = DEFAULT_LLM_BASE_URL,
    val llmModel: String = DEFAULT_LLM_MODEL,
    val outputLanguage: String = DEFAULT_OUTPUT_LANGUAGE,
    val preferOfficialSummary: Boolean = true,
    val autoGenerateWhenOfficialUnavailable: Boolean = false,
    val enableAsr: Boolean = false,
    val asrWifiOnly: Boolean = true,
    val asrBaseUrl: String = "",
    val asrModel: String = DEFAULT_ASR_MODEL,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
) {
    fun effectiveAsrBaseUrl(): String = asrBaseUrl.ifBlank { llmBaseUrl }

    companion object {
        const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_LLM_MODEL = "deepseek-chat"
        const val DEFAULT_OUTPUT_LANGUAGE = "zh-CN"
        const val DEFAULT_ASR_MODEL = "whisper-1"
        const val DEFAULT_TIMEOUT_SECONDS = 120
    }
}

object AiProviderSettings {
    private val KEY_LLM_BASE_URL = stringPreferencesKey("custom_ai_llm_base_url")
    private val KEY_LLM_MODEL = stringPreferencesKey("custom_ai_llm_model")
    private val KEY_OUTPUT_LANGUAGE = stringPreferencesKey("custom_ai_output_language")
    private val KEY_PREFER_OFFICIAL = booleanPreferencesKey("custom_ai_prefer_official")
    private val KEY_AUTO_GENERATE_OFFICIAL_UNAVAILABLE = booleanPreferencesKey("custom_ai_auto_gen_official_unavail")
    private val KEY_ENABLE_ASR = booleanPreferencesKey("custom_ai_enable_asr")
    private val KEY_ASR_WIFI_ONLY = booleanPreferencesKey("custom_ai_asr_wifi_only")
    private val KEY_ASR_BASE_URL = stringPreferencesKey("custom_ai_asr_base_url")
    private val KEY_ASR_MODEL = stringPreferencesKey("custom_ai_asr_model")
    private val KEY_TIMEOUT_SECONDS = intPreferencesKey("custom_ai_timeout_seconds")

    fun getConfigFlow(context: Context): Flow<AiProviderConfig> =
        context.settingsDataStore.data.map { preferences ->
            AiProviderConfig(
                llmBaseUrl = preferences[KEY_LLM_BASE_URL] ?: AiProviderConfig.DEFAULT_LLM_BASE_URL,
                llmModel = preferences[KEY_LLM_MODEL] ?: AiProviderConfig.DEFAULT_LLM_MODEL,
                outputLanguage = preferences[KEY_OUTPUT_LANGUAGE] ?: AiProviderConfig.DEFAULT_OUTPUT_LANGUAGE,
                preferOfficialSummary = preferences[KEY_PREFER_OFFICIAL] ?: true,
                autoGenerateWhenOfficialUnavailable = preferences[KEY_AUTO_GENERATE_OFFICIAL_UNAVAILABLE] ?: false,
                enableAsr = preferences[KEY_ENABLE_ASR] ?: false,
                asrWifiOnly = preferences[KEY_ASR_WIFI_ONLY] ?: true,
                asrBaseUrl = preferences[KEY_ASR_BASE_URL] ?: "",
                asrModel = preferences[KEY_ASR_MODEL] ?: AiProviderConfig.DEFAULT_ASR_MODEL,
                timeoutSeconds = preferences[KEY_TIMEOUT_SECONDS] ?: AiProviderConfig.DEFAULT_TIMEOUT_SECONDS
            )
        }

    suspend fun updateConfig(context: Context, config: AiProviderConfig) {
        context.settingsDataStore.edit { preferences ->
            preferences[KEY_LLM_BASE_URL] = config.llmBaseUrl.trim()
            preferences[KEY_LLM_MODEL] = config.llmModel.trim()
            preferences[KEY_OUTPUT_LANGUAGE] = config.outputLanguage.trim()
            preferences[KEY_PREFER_OFFICIAL] = config.preferOfficialSummary
            preferences[KEY_AUTO_GENERATE_OFFICIAL_UNAVAILABLE] = config.autoGenerateWhenOfficialUnavailable
            preferences[KEY_ENABLE_ASR] = config.enableAsr
            preferences[KEY_ASR_WIFI_ONLY] = config.asrWifiOnly
            preferences[KEY_ASR_BASE_URL] = config.asrBaseUrl.trim()
            preferences[KEY_ASR_MODEL] = config.asrModel.trim()
            preferences[KEY_TIMEOUT_SECONDS] = config.timeoutSeconds
        }
    }
}
