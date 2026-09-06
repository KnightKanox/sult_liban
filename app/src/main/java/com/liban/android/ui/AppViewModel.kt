package com.liban.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liban.android.LibanApplication
import com.liban.android.agent.AnalysisBus
import com.liban.android.config.PublicApiConfiguration
import com.liban.android.data.*
import com.liban.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AppUiState(
    val profile: UserProfileEntity? = null,
    val goal: SavingGoalEntity? = null,
    val transactions: List<TransactionEntity> = emptyList(),
    val decisions: List<DecisionHistoryEntity> = emptyList(),
    val diagnostics: List<ApiDiagnosticEntity> = emptyList(),
    val analysis: AnalysisState = AnalysisState.Idle,
    val apiConfig: PublicApiConfiguration = PublicApiConfiguration(),
    val connectionMessages: Map<String, String> = emptyMap(),
    val onboarded: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibanApplication
    private val repository = app.graph.repository
    private val configRepository = app.graph.configRepository
    private val preferences = application.getSharedPreferences("onboarding", 0)
    private val onboarded = MutableStateFlow(preferences.getBoolean("complete", false))
    private val connectionMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    private val publicConfig = configRepository.configuration.map {
        PublicApiConfiguration(
            llmEndpoint = it.llmEndpoint,
            llmModel = it.llmModel,
            llmApiKey = it.llmApiKey,
            hasLlmKey = it.llmApiKey.isNotBlank(),
            searchBaseUrl = it.searchBaseUrl,
            searchEngine = it.searchEngine,
            readerEnabled = it.readerEnabled,
            searchApiKey = it.searchApiKey,
            hasSearchKey = it.searchApiKey.isNotBlank(),
        )
    }

    val uiState: StateFlow<AppUiState> = combine(
        repository.profile, repository.goal, repository.transactions, repository.decisions,
        repository.diagnostics, AnalysisBus.state, publicConfig, connectionMessages, onboarded,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AppUiState(
            profile = values[0] as UserProfileEntity?,
            goal = values[1] as SavingGoalEntity?,
            transactions = values[2] as List<TransactionEntity>,
            decisions = values[3] as List<DecisionHistoryEntity>,
            diagnostics = values[4] as List<ApiDiagnosticEntity>,
            analysis = values[5] as AnalysisState,
            apiConfig = values[6] as PublicApiConfiguration,
            connectionMessages = values[7] as Map<String, String>,
            onboarded = values[8] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun completeOnboarding(monthlyBudgetYuan: String, goalYuan: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.saveProfile(parseYuanToCents(monthlyBudgetYuan) ?: 500_000, 0)
        repository.saveGoal("储蓄目标", parseYuanToCents(goalYuan) ?: 1_000_000, 0, LocalDate.now().plusMonths(6).toEpochDay())
        preferences.edit().putBoolean("complete", true).apply()
        onboarded.value = true
    }

    fun saveBudget(monthlyYuan: String, spentYuan: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.saveProfile(parseYuanToCents(monthlyYuan) ?: return@launch, parseYuanToCents(spentYuan) ?: 0)
    }

    fun saveGoal(name: String, targetYuan: String, currentYuan: String, months: Int = 6) = viewModelScope.launch(Dispatchers.IO) {
        repository.saveGoal(name, parseYuanToCents(targetYuan) ?: return@launch, parseYuanToCents(currentYuan) ?: 0, LocalDate.now().plusMonths(months.toLong()).toEpochDay())
    }

    fun saveApiConfiguration(
        llmEndpoint: String, llmKey: String, llmModel: String,
        searchBaseUrl: String, searchKey: String, searchEngine: String, readerEnabled: Boolean,
    ) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            configRepository.save(
                llmEndpoint, llmKey.takeIf(String::isNotBlank), llmModel,
                searchBaseUrl, searchKey.takeIf(String::isNotBlank), searchEngine, readerEnabled,
            )
        }.onSuccess {
            connectionMessages.value = connectionMessages.value + ("SAVE" to "配置已加密保存并立即生效")
        }.onFailure {
            connectionMessages.value = connectionMessages.value + ("SAVE" to "保存失败：${it.message}")
        }
    }

    fun clearLlmConfiguration() = viewModelScope.launch(Dispatchers.IO) {
        configRepository.clearLlm()
        connectionMessages.value = connectionMessages.value - "LLM"
    }

    fun clearSearchConfiguration() = viewModelScope.launch(Dispatchers.IO) {
        configRepository.clearSearch()
        connectionMessages.value = connectionMessages.value - "SEARCH"
    }

    fun testLlm() = viewModelScope.launch(Dispatchers.IO) {
        connectionMessages.value = connectionMessages.value + ("LLM" to "测试中…")
        connectionMessages.value = connectionMessages.value + ("LLM" to app.graph.orchestrator.testLlm())
    }

    fun testSearch() = viewModelScope.launch(Dispatchers.IO) {
        connectionMessages.value = connectionMessages.value + ("SEARCH" to "测试中…")
        connectionMessages.value = connectionMessages.value + ("SEARCH" to app.graph.orchestrator.testSearch())
    }

    fun submitCorrection(name: String, priceYuan: String, category: String) = viewModelScope.launch(Dispatchers.IO) {
        val cents = parseYuanToCents(priceYuan) ?: run {
            AnalysisBus.update(AnalysisState.Error("请输入有效价格"))
            return@launch
        }
        val scene = SceneContext(
            sceneType = "manual",
            product = Product(name.ifBlank { "手动输入商品" }, category.ifBlank { "other" }),
            price = PriceInfo(cents),
            confidence = 1.0,
            productConfidence = 1.0,
            priceConfidence = 1.0,
        )
        runCatching {
            app.graph.orchestrator.analyzeScene(scene) { AnalysisBus.update(it) }
        }.onFailure { AnalysisBus.update(AnalysisState.Error(it.message ?: "分析失败")) }
    }

    fun recordFeedback(decisionId: String, action: UserAction, delayHours: Int?) = viewModelScope.launch(Dispatchers.IO) {
        repository.recordFeedback(decisionId, action, delayHours)
    }
}
