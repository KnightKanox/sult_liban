package com.liban.android.agent

import com.liban.android.data.AppRepository
import com.liban.android.demo.DemoFixtures
import com.liban.android.demo.DemoScenarioStore
import com.liban.android.model.*
import com.liban.android.skill.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

data class ProviderValue<T>(val value: T, val source: SourceMode)

class AgentOrchestrator(
    private val repository: AppRepository,
    private val llm: OpenAiCompatibleLlmProvider,
    private val priceProvider: ConfigurablePriceProvider,
    private val demoStore: DemoScenarioStore,
    private val router: SkillRouter = SkillRouter(),
) {
    suspend fun analyze(image: ByteArray): Pair<SceneContext, DecisionResult> {
        val perceived = callPerception(image)
        if (perceived.value.confidence < 0.55) throw LowConfidenceException(perceived.value)
        return analyzeScene(perceived.value, perceived.source)
    }

    suspend fun analyzeScene(scene: SceneContext, sceneSource: SourceMode = SourceMode.FALLBACK): Pair<SceneContext, DecisionResult> = coroutineScope {
        val profile = repository.getProfile()
        val goal = repository.getGoal()
        val unknownSkillCount = scene.requiredSkills.count { SkillId.fromWireName(it) == null }
        if (unknownSkillCount > 0) {
            repository.recordDiagnostic(
                provider = "ROUTER",
                configured = true,
                success = false,
                latencyMs = null,
                httpStatus = null,
                error = "已忽略 $unknownSkillCount 个未知 Skill",
            )
        }
        val skills = router.route(scene, profile)
        val recentDeferred = async {
            repository.countRecent(scene.product.category, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        }
        val budget = async { if (SkillId.BUDGET in skills) BudgetSkill().execute(BudgetInput(scene.price.currentCents, profile)).value else null }
        val goalResult = async { if (SkillId.GOAL in skills) GoalSkill().execute(GoalInput(scene.price.currentCents, goal)).value else null }
        val history = async { if (SkillId.HISTORY in skills) HistorySkill().execute(recentDeferred.await()).value else null }
        val impulse = async { if (SkillId.IMPULSE in skills) ImpulseSkill().execute(scene.signals).value else null }
        val price = async { if (SkillId.PRICE in skills) callPrice(scene.product, Money(scene.price.currentCents)) else null }

        val priceValue = price.await()
        val results = SkillResults(
            budget = budget.await(),
            goal = goalResult.await(),
            history = history.await(),
            impulse = impulse.await(),
            price = priceValue?.value,
        )
        val mixedSource = combineSources(sceneSource, priceValue?.source)
        val decision = callDecision(scene, results, mixedSource)
        repository.saveDecision(scene, decision)
        scene to decision
    }

    private suspend fun callPerception(image: ByteArray): ProviderValue<SceneContext> {
        if (!llm.configured) return ProviderValue(DemoFixtures.scene(demoStore.selected), SourceMode.FALLBACK)
        val start = System.currentTimeMillis()
        return try {
            val value = llm.perceive(image)
            repository.recordDiagnostic("LLM", true, true, System.currentTimeMillis() - start, 200, null)
            ProviderValue(value, SourceMode.LIVE)
        } catch (error: Throwable) {
            repository.recordDiagnostic("LLM", true, false, System.currentTimeMillis() - start, (error as? ProviderHttpException)?.status, error.message)
            ProviderValue(DemoFixtures.scene(demoStore.selected), SourceMode.FALLBACK)
        }
    }

    private suspend fun callPrice(product: Product, pagePrice: Money): ProviderValue<PriceComparison> {
        val fixture = DemoFixtures.price(product, pagePrice)
        if (!priceProvider.configured) {
            return ProviderValue(
                fixture ?: PriceComparison(Availability.UNAVAILABLE, pagePriceCents = pagePrice.cents, source = SourceMode.FALLBACK),
                SourceMode.FALLBACK,
            )
        }
        val start = System.currentTimeMillis()
        return try {
            val value = priceProvider.compare(product, pagePrice)
            repository.recordDiagnostic("PRICE", true, true, System.currentTimeMillis() - start, 200, null)
            ProviderValue(value, SourceMode.LIVE)
        } catch (error: Throwable) {
            repository.recordDiagnostic("PRICE", true, false, System.currentTimeMillis() - start, (error as? ProviderHttpException)?.status, error.message)
            ProviderValue(
                fixture ?: PriceComparison(Availability.UNAVAILABLE, pagePriceCents = pagePrice.cents, source = SourceMode.FALLBACK),
                SourceMode.FALLBACK,
            )
        }
    }

    private suspend fun callDecision(scene: SceneContext, results: SkillResults, existingSource: SourceMode): DecisionResult {
        if (!llm.configured) return DecisionEngine.decide(scene, results, existingSource)
        val start = System.currentTimeMillis()
        return try {
            val live = llm.decide(scene, results)
            repository.recordDiagnostic("LLM", true, true, System.currentTimeMillis() - start, 200, null)
            live.copy(sourceMode = if (existingSource == SourceMode.LIVE) SourceMode.LIVE else SourceMode.MIXED)
        } catch (error: Throwable) {
            repository.recordDiagnostic("LLM", true, false, System.currentTimeMillis() - start, (error as? ProviderHttpException)?.status, error.message)
            DecisionEngine.decide(scene, results, if (existingSource == SourceMode.FALLBACK) SourceMode.FALLBACK else SourceMode.MIXED)
        }
    }

    private fun combineSources(first: SourceMode, second: SourceMode?): SourceMode {
        if (second == null) return first
        return if (first == second) first else SourceMode.MIXED
    }
}

class LowConfidenceException(val scene: SceneContext) : Exception("商品或价格识别置信度过低")
