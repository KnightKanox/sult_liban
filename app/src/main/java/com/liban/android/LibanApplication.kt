package com.liban.android

import android.app.Application
import com.liban.android.agent.AgentOrchestrator
import com.liban.android.agent.ConfigurablePriceProvider
import com.liban.android.agent.OpenAiCompatibleLlmProvider
import com.liban.android.data.AppDatabase
import com.liban.android.data.AppRepository
import com.liban.android.demo.DemoScenarioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class LibanApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            graph.repository.ensureDefaults()
        }
    }
}

class AppGraph(application: Application) {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
    private val database = AppDatabase.create(application)
    val repository = AppRepository(database.appDao(), json)
    val demoStore = DemoScenarioStore(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val llmProvider = OpenAiCompatibleLlmProvider(httpClient, json)
    private val priceProvider = ConfigurablePriceProvider(httpClient, json)
    val orchestrator = AgentOrchestrator(repository, llmProvider, priceProvider, demoStore)
    val serviceConfiguration = ServiceConfiguration(
        llmConfigured = llmProvider.configured,
        priceConfigured = priceProvider.configured,
    )
}

data class ServiceConfiguration(
    val llmConfigured: Boolean,
    val priceConfigured: Boolean,
)

