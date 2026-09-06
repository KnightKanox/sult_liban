package com.liban.android

import android.app.Application
import com.liban.android.agent.AgentOrchestrator
import com.liban.android.config.ConfigRepository
import com.liban.android.data.AppDatabase
import com.liban.android.data.AppRepository
import com.liban.android.ocr.MlKitChineseOcrProvider
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
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { graph.repository.ensureDefaults() }
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
    val configRepository = ConfigRepository(application)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    val orchestrator = AgentOrchestrator(
        repository = repository,
        configRepository = configRepository,
        ocrProvider = MlKitChineseOcrProvider(),
        httpClient = httpClient,
        json = json,
    )
}
