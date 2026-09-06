package com.liban.android.agent

import android.graphics.Bitmap
import com.liban.android.config.ApiConfiguration
import com.liban.android.config.ConfigRepository
import com.liban.android.data.AppRepository
import com.liban.android.model.*
import com.liban.android.ocr.OcrProvider
import com.liban.android.ocr.SceneParser
import com.liban.android.skill.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentOrchestrator(
    private val repository: AppRepository,
    private val configRepository: ConfigRepository,
    private val ocrProvider: OcrProvider,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val router: SkillRouter = SkillRouter(),
) {
    suspend fun analyze(
        bitmap: Bitmap,
        onOcrFinished: suspend () -> Unit = {},
        onUpdate: suspend (AnalysisState) -> Unit = {},
    ): Pair<SceneContext, DecisionResult> {
        onUpdate(AnalysisState.Recognizing)
        val document = ocrProvider.recognize(bitmap)
        onOcrFinished()
        val scene = SceneParser.parse(document)
            ?: throw LowConfidenceException(null, "没有识别到商品名称和人民币价格，请手动修正")
        if (scene.productConfidence < 0.65 || scene.priceConfidence < 0.80) {
            throw LowConfidenceException(scene, "商品名或价格识别置信度不足，请确认后继续")
        }
        return analyzeScene(scene, onUpdate)
    }

    suspend fun analyzeScene(
        scene: SceneContext,
        onUpdate: suspend (AnalysisState) -> Unit = {},
    ): Pair<SceneContext, DecisionResult> = coroutineScope {
        val profile = repository.getProfile()
        val goal = repository.getGoal()
        val unknownSkillCount = scene.requiredSkills.count { SkillId.fromWireName(it) == null }
        if (unknownSkillCount > 0) {
            repository.recordDiagnostic("ROUTER", true, false, null, null, "已忽略 $unknownSkillCount 个未知 Skill")
        }
        val selected = router.route(scene, profile) - SkillId.PRICE
        val recent = async(Dispatchers.IO) {
            repository.countRecent(scene.product.category, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        }
        val budget = async { if (SkillId.BUDGET in selected) BudgetSkill().execute(BudgetInput(scene.price.currentCents, profile)).value else null }
        val goalResult = async { if (SkillId.GOAL in selected) GoalSkill().execute(GoalInput(scene.price.currentCents, goal)).value else null }
        val history = async { if (SkillId.HISTORY in selected) HistorySkill().execute(recent.await()).value else null }
        val impulse = async { if (SkillId.IMPULSE in selected) ImpulseSkill().execute(scene.signals).value else null }
        val localResults = SkillResults(
            budget = budget.await(),
            goal = goalResult.await(),
            history = history.await(),
            impulse = impulse.await(),
        )
        val preliminary = DecisionEngine.decide(scene, localResults)
        repository.saveDecision(scene, preliminary)
        onUpdate(AnalysisState.PreliminaryResult(scene, preliminary))
        onUpdate(AnalysisState.EnrichingPrice(scene, preliminary))

        val comparison = repository.getCachedPrice(scene.product, scene.price.currentCents)
            ?: withTimeoutOrNull(5_000) { enrichPrice(scene, configRepository.configuration.first()) }
            ?: unavailable(scene.price.currentCents, "外部查询超时")
        if (comparison.evidenceSource != PriceEvidenceSource.UNAVAILABLE && !comparison.cached) {
            repository.cachePrice(scene.product, comparison)
        }
        val finalDecision = DecisionEngine.decide(
            scene,
            localResults.copy(price = comparison),
            decisionId = preliminary.decisionId,
        )
        repository.updateDecision(finalDecision)
        onUpdate(AnalysisState.Result(scene, finalDecision))
        scene to finalDecision
    }

    suspend fun testSearch(): String {
        val config = configRepository.configuration.first()
        if (!config.searchConfigured) return "智谱搜索配置不完整"
        val start = System.currentTimeMillis()
        return try {
            val count = withTimeout(3_000) { searchProvider(config).search("Sony WH-1000XM6 售价 价格 购买").size }
            val elapsed = System.currentTimeMillis() - start
            repository.recordDiagnostic("ZHIPU_SEARCH", true, true, elapsed, 200, null)
            "连接成功，返回 $count 条结果（${elapsed}ms）"
        } catch (error: Throwable) {
            recordFailure("ZHIPU_SEARCH", true, start, error)
            "连接失败：${error.message ?: "未知错误"}"
        }
    }

    suspend fun testLlm(): String {
        val config = configRepository.configuration.first()
        if (!config.llmConfigured) return "LLM 配置不完整"
        val start = System.currentTimeMillis()
        return try {
            val value = withTimeout(5_000) {
                estimator(config).estimate(
                    PriceEstimateInput(Product("Sony WH-1000XM6", "electronics", model = "WH-1000XM6"), 2_999_00)
                )
            }
            val elapsed = System.currentTimeMillis() - start
            repository.recordDiagnostic("LLM_ESTIMATE", true, true, elapsed, 200, null)
            "连接成功，响应 ${value.status}（${elapsed}ms）"
        } catch (error: Throwable) {
            recordFailure("LLM_ESTIMATE", true, start, error)
            "连接失败：${error.message ?: "未知错误"}"
        }
    }

    private suspend fun enrichPrice(scene: SceneContext, config: ApiConfiguration): PriceComparison {
        var hits = emptyList<SearchHit>()
        var samples = emptyList<PriceSample>()
        if (config.searchConfigured) {
            val start = System.currentTimeMillis()
            try {
                val provider = searchProvider(config)
                withTimeout(3_000) {
                    hits = provider.search(PriceEvidenceAnalyzer.buildQuery(scene.product))
                    samples = PriceEvidenceAnalyzer.extract(scene.product, hits)
                    if (samples.map { it.domain }.distinct().size < 3 && config.readerEnabled) {
                        val pages = withTimeoutOrNull(1_200) {
                            hits.take(2).map { hit ->
                                async { runCatching { provider.read(hit.url) }.getOrNull() }
                            }.awaitAll().filterNotNull()
                        }.orEmpty()
                        val pageHits = pages.map { SearchHit(it.title, it.content, it.url) }
                        samples = (samples + PriceEvidenceAnalyzer.extract(scene.product, pageHits))
                            .distinctBy { it.domain to it.cents }
                    }
                }
                repository.recordDiagnostic("ZHIPU_SEARCH", true, true, System.currentTimeMillis() - start, 200, null)
                PriceEvidenceAnalyzer.verifiedComparison(scene.price.currentCents, samples, System.currentTimeMillis())
                    ?.let { return it }
            } catch (error: Throwable) {
                recordFailure("ZHIPU_SEARCH", true, start, error)
            }
        } else {
            repository.recordDiagnostic("ZHIPU_SEARCH", false, false, null, null, "未配置")
        }

        if (!config.llmConfigured) {
            repository.recordDiagnostic("LLM_ESTIMATE", false, false, null, null, "未配置")
            return unavailable(scene.price.currentCents, if (samples.isEmpty()) "没有可靠价格证据" else "搜索样本不足 3 个独立来源")
        }
        val start = System.currentTimeMillis()
        return try {
            val estimate = withTimeout(5_000) {
                estimator(config).estimate(
                    PriceEstimateInput(
                        product = scene.product,
                        pagePriceCents = scene.price.currentCents,
                        evidence = if (samples.isEmpty()) emptyList() else hits.take(3),
                    )
                )
            }
            repository.recordDiagnostic("LLM_ESTIMATE", true, true, System.currentTimeMillis() - start, 200, null)
            if (estimate.status == PriceEstimateStatus.UNKNOWN) {
                unavailable(scene.price.currentCents, estimate.rationale.ifBlank { "模型无法可靠估价" })
            } else {
                val low = requireNotNull(estimate.lowCents)
                val high = requireNotNull(estimate.highCents)
                PriceComparison(
                    availability = Availability.AVAILABLE,
                    referenceLowCents = low,
                    referenceHighCents = high,
                    pagePriceCents = scene.price.currentCents,
                    premiumRatio = (scene.price.currentCents - high).toDouble() / high.coerceAtLeast(1),
                    evidenceSource = if (samples.isEmpty()) PriceEvidenceSource.MODEL_ESTIMATE else PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE,
                    confidence = estimate.confidence,
                    sampleCount = samples.size,
                    references = samples.distinctBy { it.domain }.take(3).map { it.reference },
                    queriedAt = System.currentTimeMillis(),
                    rationale = estimate.rationale,
                )
            }
        } catch (error: Throwable) {
            recordFailure("LLM_ESTIMATE", true, start, error)
            unavailable(scene.price.currentCents, error.message ?: "模型估价失败")
        }
    }

    private fun searchProvider(config: ApiConfiguration) =
        ZhipuSearchProvider(httpClient, json, config.searchBaseUrl, config.searchApiKey, config.searchEngine)

    private fun estimator(config: ApiConfiguration) =
        OpenAiCompatiblePriceEstimator(httpClient, json, config.llmEndpoint, config.llmApiKey, config.llmModel)

    private suspend fun recordFailure(provider: String, configured: Boolean, start: Long, error: Throwable) {
        repository.recordDiagnostic(
            provider,
            configured,
            false,
            System.currentTimeMillis() - start,
            (error as? ProviderHttpException)?.status,
            error.message,
        )
    }

    private fun unavailable(pagePriceCents: Long, reason: String) = PriceComparison(
        availability = Availability.UNAVAILABLE,
        pagePriceCents = pagePriceCents,
        evidenceSource = PriceEvidenceSource.UNAVAILABLE,
        queriedAt = System.currentTimeMillis(),
        rationale = reason,
    )
}

class LowConfidenceException(
    val scene: SceneContext?,
    message: String,
) : Exception(message)
