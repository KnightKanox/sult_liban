package com.liban.android.config

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigRepositoryTest {
    @Test
    fun presetsCanBeRestoredAndClearingSurvivesRepositoryRecreation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preset = ApiConfiguration(
            llmEndpoint = "https://preset.example/v1/chat/completions", llmApiKey = "preset-llm-test",
            llmModel = "test-model", searchApiKey = "preset-search-test", searchEngine = "search_pro_quark",
        )
        val repository = ConfigRepository(context, preset)
        repository.restorePresets()
        assertEquals(preset.llmApiKey, repository.configuration.first().llmApiKey)
        assertEquals("search_pro_quark", repository.configuration.first().searchEngine)
        repository.clearLlm()
        repository.clearSearch()
        val recreated = ConfigRepository(context, preset).configuration.first()
        assertFalse(recreated.llmConfigured)
        assertFalse(recreated.searchConfigured)
    }

    @Test
    fun keysRoundTripEncryptedAndCanBeCleared() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repository = ConfigRepository(context, ApiConfiguration())
        val llmSecret = "llm-test-secret-that-must-not-be-plaintext"
        val searchSecret = "search-test-secret-that-must-not-be-plaintext"
        repository.save(
            llmEndpoint = "https://llm.example/v1/chat/completions",
            llmApiKey = llmSecret,
            llmModel = "test-model",
            searchBaseUrl = "https://open.bigmodel.cn/api",
            searchApiKey = searchSecret,
            searchEngine = "search_pro",
            readerEnabled = true,
        )
        val saved = repository.configuration.first()
        assertEquals(llmSecret, saved.llmApiKey)
        assertEquals(searchSecret, saved.searchApiKey)
        val persisted = context.filesDir.resolve("datastore/api_configuration.preferences_pb")
            .readBytes().toString(Charsets.UTF_8)
        assertFalse(persisted.contains(llmSecret))
        assertFalse(persisted.contains(searchSecret))

        repository.clearLlm()
        repository.clearSearch()
        val cleared = repository.configuration.first()
        assertFalse(cleared.llmConfigured)
        assertFalse(cleared.searchConfigured)
    }
}
