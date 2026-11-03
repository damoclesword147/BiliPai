package com.android.purebilibili.feature.video.summary

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android KeyStore 的安全凭证存储服务
 * 使用 AES/GCM/NoPadding 算法进行硬件/Keystore 级加密保护
 */
object AiKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "bilipai_ai_credentials_key"
    private const val PREFS_NAME = "bilipai_ai_secure_store"
    private const val KEY_LLM_API_KEY = "encrypted_llm_api_key"
    private const val KEY_LLM_IV = "llm_api_key_iv"
    private const val KEY_ASR_API_KEY = "encrypted_asr_api_key"
    private const val KEY_ASR_IV = "asr_api_key_iv"

    private const val GCM_TAG_LENGTH = 128
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun encrypt(plainText: String): Pair<String, String>? {
        if (plainText.isEmpty()) return null
        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val cipherBase64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            Pair(cipherBase64, ivBase64)
        } catch (e: Exception) {
            null
        }
    }

    private fun decrypt(cipherBase64: String?, ivBase64: String?): String {
        if (cipherBase64.isNullOrEmpty() || ivBase64.isNullOrEmpty()) return ""
        return try {
            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val cipherText = Base64.decode(cipherBase64, Base64.NO_WRAP)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun saveLlmApiKey(context: Context, apiKey: String) {
        val prefs = getPrefs(context)
        if (apiKey.isBlank()) {
            prefs.edit().remove(KEY_LLM_API_KEY).remove(KEY_LLM_IV).apply()
            return
        }
        val pair = encrypt(apiKey) ?: return
        prefs.edit()
            .putString(KEY_LLM_API_KEY, pair.first)
            .putString(KEY_LLM_IV, pair.second)
            .apply()
    }

    fun getLlmApiKey(context: Context): String {
        val prefs = getPrefs(context)
        val cipher = prefs.getString(KEY_LLM_API_KEY, null)
        val iv = prefs.getString(KEY_LLM_IV, null)
        return decrypt(cipher, iv)
    }

    fun saveAsrApiKey(context: Context, apiKey: String) {
        val prefs = getPrefs(context)
        if (apiKey.isBlank()) {
            prefs.edit().remove(KEY_ASR_API_KEY).remove(KEY_ASR_IV).apply()
            return
        }
        val pair = encrypt(apiKey) ?: return
        prefs.edit()
            .putString(KEY_ASR_API_KEY, pair.first)
            .putString(KEY_ASR_IV, pair.second)
            .apply()
    }

    fun getAsrApiKey(context: Context): String {
        val prefs = getPrefs(context)
        val cipher = prefs.getString(KEY_ASR_API_KEY, null)
        val iv = prefs.getString(KEY_ASR_IV, null)
        return decrypt(cipher, iv)
    }

    fun hasLlmApiKey(context: Context): Boolean {
        return getLlmApiKey(context).isNotBlank()
    }
}
