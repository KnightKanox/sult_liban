package com.liban.android.agent

import android.graphics.Bitmap
import com.liban.android.config.ApiConfiguration
import com.liban.android.config.ConfigRepository
import com.liban.android.data.AppRepository
import com.liban.android.model.*
import com.liban.android.ocr.OcrProvider
import com.liban.android.ocr.SceneParser
import com.liban.android.ocr.LlmSceneExtractor
import com.liban.android.ocr.refineScene
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
        val localScene = SceneParser.parse(document)
        val config = configRepository.configuration.first()
        var scene = localScene
        if (config.llmOcrEnabled && config.llmConfigured && document.lines.isNotEmpty()) {
            onUpdate(AnalysisState.ExtractingProduct)
            val start = System.currentTimeMillis()
            scene = refineScene(localScene, extract = {
                    LlmSceneExtractor(httpClient, json, config.llmEndpoint, config.llmApiKey, config.llmModel).extract(document, localScene)
            }) { success, error ->
                if (error != null) recordFailure("LLM_OCR", true, start, error)
                else repository.recordDiagnostic("LLM_OCR", true, success, System.currentTimeMillis() - start, 200,
                    if (!success) "模型未提取到明确商品，已尝试本地识别结果" else null)
            }
        }
        scene ?: throw LowConfidenceException(null, "没有识别到商品名称和人民币价格，请手动修正")
        // Confidence is metadata, not a confirmation gate for a complete scene.
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

        val comparison = repository.getCachedPrice(scene.product, scene.price)
            ?: withTimeoutOrNull(5_000) { enrichPrice(scene, configRepository.configuration.first()) }
            ?: unavailable(scene.price.currentCents, "外部查询超时")
        if (comparison.evidenceSource != PriceEvidenceSource.UNAVAILABLE && !comparison.cached) {
            repository.cachePrice(scene.product, scene.price, comparison)
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
            val product = Product("索尼 WH-1000XM6 耳机", "electronics", model = "WH-1000XM6")
            val result = withTimeout(3_000) { priceSearch(config).search(product, config.searchEngine) }
            val relevant = PriceEvidenceAnalyzer.relevantHits(product, result.hits)
            val samples = PriceEvidenceAnalyzer.extract(product, relevant)
            val elapsed = System.currentTimeMillis() - start
            val warning = if (result.linkedCount == 0) "搜索返回内容但缺少来源链接，不能验证价格" else null
            repository.recordDiagnostic("ZHIPU_SEARCH", true, result.linkedCount > 0, elapsed, 200, warning)
            "返回 ${result.hits.size} 条 · 带链接 ${result.linkedCount} 条 · 型号匹配 ${relevant.size} 条 · 价格来源 ${samples.map { it.domain }.distinct().size} 个（${elapsed}ms；${result.engines.joinToString(" → ")}）${warning?.let { "；$it" }.orEmpty()}"
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            recordFailure("ZHIPU_SEARCH", true, start, error)
            "连接失败：${failureMessage(error)}"
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
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            recordFailure("LLM_ESTIMATE", true, start, error)
            "连接失败：${failureMessage(error)}"
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
                    val result = priceSearch(config).search(scene.product, config.searchEngine)
                    hits = PriceEvidenceAnalyzer.classifyHits(scene.product, result.hits)
                    samples = PriceEvidenceAnalyzer.extract(scene.product, hits, pagePrice = scene.price)
                    if (samples.map { it.domain }.distinct().size < 3 && config.readerEnabled) {
                        val pages = withTimeoutOrNull(1_200) {
                            hits.filter { it.url.isNotBlank() }.take(2).map { hit ->
                                async {
                                    try { provider.read(hit.url).let { SearchHit(it.title, it.content, hit.url, hit.media, hit.publishDate) } }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                }
                            }.awaitAll().filterNotNull()
                        }.orEmpty()
                        samples = (samples + PriceEvidenceAnalyzer.extract(scene.product, pages, pagePrice = scene.price))
                            .distinctBy { it.domain to it.cents }
                    }
                }
                repository.recordDiagnostic("ZHIPU_SEARCH", true, hits.isNotEmpty(), System.currentTimeMillis() - start, 200,
                    if (hits.isEmpty()) "没有商品相关结果" else if (hits.none { it.url.isNotBlank() }) "摘要缺少来源链接，不能验证价格" else null)
                PriceEvidenceAnalyzer.verifiedComparison(scene.price.currentCents, samples, System.currentTimeMillis())
                    ?.let { return it }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
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
                        evidence = hits.sortedByDescending { it.url.isNotBlank() }.take(3),
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
                    evidenceSource = if (hits.isEmpty()) PriceEvidenceSource.MODEL_ESTIMATE else PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE,
                    confidence = estimate.confidence,
                    sampleCount = samples.size,
                    references = samples.distinctBy { it.domain }.take(3).map { it.reference },
                    queriedAt = System.currentTimeMillis(),
                    rationale = "${estimate.rationale}（${if (scene.product.model == null || hits.any { it.matchKind == SearchMatchKind.SIMILAR }) "同类商品参考，非同款验证；" else ""}模型估算，未经实时成交价验证）",
                )
            }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            recordFailure("LLM_ESTIMATE", true, start, error)
            unavailable(scene.price.currentCents, failureMessage(error))
        }
    }

    private fun searchProvider(config: ApiConfiguration) =
        ZhipuSearchProvider(httpClient, json, config.searchBaseUrl, config.searchApiKey, config.searchEngine)

    private fun priceSearch(config: ApiConfiguration) = PriceSearch { engine ->
        ZhipuSearchProvider(httpClient, json, config.searchBaseUrl, config.searchApiKey, engine)
    }

    private fun estimator(config: ApiConfiguration) =
        OpenAiCompatiblePriceEstimator(httpClient, json, config.llmEndpoint, config.llmApiKey, config.llmModel)

    private suspend fun recordFailure(provider: String, configured: Boolean, start: Long, error: Throwable) {
        repository.recordDiagnostic(
            provider,
            configured,
            false,
            System.currentTimeMillis() - start,
            when (error) {
                is ProviderHttpException -> error.status
                is ProviderResponseException -> 200
                else -> null
            },
            failureMessage(error),
        )
    }

    private fun failureMessage(error: Throwable): String = when (error) {
        is TimeoutCancellationException -> "接口请求超时，未在限定时间内完成"
        is ProviderHttpException, is ProviderResponseException, is ProviderTransportException -> error.message ?: "接口失败"
        else -> "接口处理失败，请检查配置后重试"
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
