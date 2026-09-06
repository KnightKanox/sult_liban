package com.liban.android.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.configDataStore by preferencesDataStore("api_configuration")

data class ApiConfiguration(
    val llmEndpoint: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "",
    val searchBaseUrl: String = "https://open.bigmodel.cn/api",
    val searchApiKey: String = "",
    val searchEngine: String = "search_pro",
    val readerEnabled: Boolean = true,
) {
    val llmConfigured: Boolean
        get() = llmEndpoint.isAllowedEndpoint() && llmApiKey.isNotBlank() && llmModel.isNotBlank()
    val searchConfigured: Boolean
        get() = searchBaseUrl.isAllowedEndpoint() && searchApiKey.isNotBlank()
}

data class PublicApiConfiguration(
    val llmEndpoint: String = "",
    val llmModel: String = "",
    val llmApiKey: String = "",
    val hasLlmKey: Boolean = false,
    val searchBaseUrl: String = "https://open.bigmodel.cn/api",
    val searchEngine: String = "search_pro",
    val readerEnabled: Boolean = true,
    val searchApiKey: String = "",
    val hasSearchKey: Boolean = false,
)

class ConfigRepository(private val context: Context) {
    private val crypto = KeystoreCrypto()
    val configuration: Flow<ApiConfiguration> = context.configDataStore.data.map { values ->
        ApiConfiguration(
            llmEndpoint = values[LLM_ENDPOINT].orEmpty(),
            llmApiKey = decryptOrEmpty(values[LLM_KEY]),
            llmModel = values[LLM_MODEL].orEmpty(),
            searchBaseUrl = values[SEARCH_BASE_URL] ?: "https://open.bigmodel.cn/api",
            searchApiKey = decryptOrEmpty(values[SEARCH_KEY]),
            searchEngine = values[SEARCH_ENGINE] ?: "search_pro",
            readerEnabled = values[READER_ENABLED] ?: true,
        )
    }

    suspend fun save(
        llmEndpoint: String,
        llmApiKey: String?,
        llmModel: String,
        searchBaseUrl: String,
        searchApiKey: String?,
        searchEngine: String,
        readerEnabled: Boolean,
    ) {
        context.configDataStore.edit { values ->
            values[LLM_ENDPOINT] = llmEndpoint.trim().trimEnd('/')
            values[LLM_MODEL] = llmModel.trim()
            values[SEARCH_BASE_URL] = searchBaseUrl.trim().trimEnd('/')
            values[SEARCH_ENGINE] = searchEngine.trim().ifBlank { "search_pro" }
            values[READER_ENABLED] = readerEnabled
            llmApiKey?.takeIf { it.isNotBlank() }?.let { values[LLM_KEY] = crypto.encrypt(it.trim()) }
            searchApiKey?.takeIf { it.isNotBlank() }?.let { values[SEARCH_KEY] = crypto.encrypt(it.trim()) }
        }
    }

    suspend fun clearLlm() = context.configDataStore.edit {
        it.remove(LLM_ENDPOINT); it.remove(LLM_MODEL); it.remove(LLM_KEY)
    }

    suspend fun clearSearch() = context.configDataStore.edit {
        it.remove(SEARCH_KEY)
    }

    private fun decryptOrEmpty(value: String?): String =
        value?.let { runCatching { crypto.decrypt(it) }.getOrDefault("") }.orEmpty()

    private companion object {
        val LLM_ENDPOINT = stringPreferencesKey("llm_endpoint")
        val LLM_KEY = stringPreferencesKey("llm_api_key_encrypted")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val SEARCH_BASE_URL = stringPreferencesKey("search_base_url")
        val SEARCH_KEY = stringPreferencesKey("search_api_key_encrypted")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val READER_ENABLED = booleanPreferencesKey("reader_enabled")
    }
}

private class KeystoreCrypto {
    private val alias = "liban_api_config_aes"

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "密钥配置损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}

fun String.isAllowedEndpoint(): Boolean =
    startsWith("https://") || startsWith("http://127.0.0.1") || startsWith("http://localhost")
