package com.liban.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liban.android.LibanApplication
import com.liban.android.agent.AnalysisBus
import com.liban.android.data.*
import com.liban.android.demo.DemoScenario
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
    val selectedDemo: DemoScenario = DemoScenario.A,
    val onboarded: Boolean = false,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibanApplication
    private val repository = app.graph.repository
    private val preferences = application.getSharedPreferences("onboarding", 0)
    private val selectedDemo = MutableStateFlow(app.graph.demoStore.selected)
    private val onboarded = MutableStateFlow(preferences.getBoolean("complete", false))

    val uiState: StateFlow<AppUiState> = combine(
        repository.profile,
        repository.goal,
        repository.transactions,
        repository.decisions,
        repository.diagnostics,
        AnalysisBus.state,
        selectedDemo,
        onboarded,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AppUiState(
            profile = values[0] as UserProfileEntity?,
            goal = values[1] as SavingGoalEntity?,
            transactions = values[2] as List<TransactionEntity>,
            decisions = values[3] as List<DecisionHistoryEntity>,
            diagnostics = values[4] as List<ApiDiagnosticEntity>,
            analysis = values[5] as AnalysisState,
            selectedDemo = values[6] as DemoScenario,
            onboarded = values[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    fun completeOnboarding(monthlyBudgetYuan: String, goalYuan: String) = viewModelScope.launch(Dispatchers.IO) {
        val budget = parseYuanToCents(monthlyBudgetYuan) ?: 5_000_00
        val goal = parseYuanToCents(goalYuan) ?: 10_000_00
        repository.saveProfile(budget, 0)
        repository.saveGoal("储蓄目标", goal, 0, LocalDate.now().plusMonths(6).toEpochDay())
        preferences.edit().putBoolean("complete", true).apply()
        onboarded.value = true
    }

    fun saveBudget(monthlyYuan: String, spentYuan: String) = viewModelScope.launch(Dispatchers.IO) {
        repository.saveProfile(parseYuanToCents(monthlyYuan) ?: return@launch, parseYuanToCents(spentYuan) ?: 0)
    }

    fun saveGoal(name: String, targetYuan: String, currentYuan: String, months: Int = 6) = viewModelScope.launch(Dispatchers.IO) {
        repository.saveGoal(
            name,
            parseYuanToCents(targetYuan) ?: return@launch,
            parseYuanToCents(currentYuan) ?: 0,
            LocalDate.now().plusMonths(months.toLong()).toEpochDay(),
        )
    }

    fun selectDemo(scenario: DemoScenario) = viewModelScope.launch(Dispatchers.IO) {
        app.graph.demoStore.selected = scenario
        selectedDemo.value = scenario
        when (scenario) {
            DemoScenario.A -> repository.saveProfile(180_000, 120_000)
            DemoScenario.B -> repository.saveProfile(500_000, 120_000)
            DemoScenario.C -> repository.saveProfile(900_000, 250_000)
        }
    }

    fun submitCorrection(name: String, priceYuan: String, category: String) = viewModelScope.launch(Dispatchers.IO) {
        val cents = parseYuanToCents(priceYuan) ?: run {
            AnalysisBus.update(AnalysisState.Error("请输入有效价格"))
            return@launch
        }
        AnalysisBus.update(AnalysisState.Analyzing)
        runCatching {
            app.graph.orchestrator.analyzeScene(
                SceneContext(
                    sceneType = "manual",
                    product = Product(name.ifBlank { "手动输入商品" }, category.ifBlank { "other" }),
                    price = PriceInfo(cents),
                    confidence = 1.0,
                )
            )
        }.onSuccess { (scene, decision) -> AnalysisBus.update(AnalysisState.Result(scene, decision)) }
            .onFailure { AnalysisBus.update(AnalysisState.Error(it.message ?: "分析失败")) }
    }

    fun recordFeedback(decisionId: String, action: UserAction, delayHours: Int?) = viewModelScope.launch(Dispatchers.IO) {
        repository.recordFeedback(decisionId, action, delayHours)
    }
}
