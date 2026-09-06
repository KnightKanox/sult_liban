package com.liban.android.data

import com.liban.android.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AppRepository(private val dao: AppDao, private val json: Json) {
    val profile: Flow<UserProfileEntity?> = dao.observeProfile()
    val goal: Flow<SavingGoalEntity?> = dao.observeGoal()
    val transactions: Flow<List<TransactionEntity>> = dao.observeTransactions()
    val decisions: Flow<List<DecisionHistoryEntity>> = dao.observeDecisions()
    val diagnostics: Flow<List<ApiDiagnosticEntity>> = dao.observeDiagnostics()

    suspend fun ensureDefaults() {
        if (dao.getProfile() == null) {
            dao.upsertProfile(UserProfileEntity(monthlyBudgetCents = 500_000, currentSpentCents = 120_000))
        }
        if (dao.getGoal() == null) {
            dao.upsertGoal(
                SavingGoalEntity(
                    name = "旅行基金",
                    targetAmountCents = 1_000_000,
                    currentAmountCents = 350_000,
                    deadlineEpochDay = LocalDate.now().plusMonths(4).toEpochDay(),
                )
            )
        }
    }

    suspend fun getProfile(): UserProfileEntity =
        dao.getProfile() ?: UserProfileEntity(monthlyBudgetCents = 500_000, currentSpentCents = 0)
    suspend fun getGoal(): SavingGoalEntity? = dao.getGoal()

    suspend fun saveProfile(monthlyBudgetCents: Long, currentSpentCents: Long) {
        dao.upsertProfile(UserProfileEntity(monthlyBudgetCents = monthlyBudgetCents.coerceAtLeast(0), currentSpentCents = currentSpentCents.coerceAtLeast(0)))
    }

    suspend fun saveGoal(name: String, targetCents: Long, currentCents: Long, deadlineEpochDay: Long) {
        dao.upsertGoal(
            SavingGoalEntity(
                name = name.ifBlank { "储蓄目标" },
                targetAmountCents = targetCents.coerceAtLeast(0),
                currentAmountCents = currentCents.coerceAtLeast(0),
                deadlineEpochDay = deadlineEpochDay,
            )
        )
    }

    suspend fun countRecent(category: String, sinceMillis: Long): Int =
        dao.transactionsSince(category, sinceMillis).size

    suspend fun saveDecision(scene: SceneContext, decision: DecisionResult) {
        dao.insertDecision(
            DecisionHistoryEntity(
                decisionId = decision.decisionId,
                productName = scene.product.name,
                category = scene.product.category,
                priceCents = scene.price.currentCents,
                sceneJson = json.encodeToString(SceneContext.serializer(), scene),
                resultJson = json.encodeToString(DecisionResult.serializer(), decision),
                riskLevel = decision.riskLevel.name,
                recommendation = decision.recommendation.name,
                sourceMode = "OCR_LOCAL",
                priceSource = decision.price?.evidenceSource?.name ?: PriceEvidenceSource.UNAVAILABLE.name,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateDecision(decision: DecisionResult) {
        dao.updateDecisionResult(
            decisionId = decision.decisionId,
            resultJson = json.encodeToString(DecisionResult.serializer(), decision),
            riskLevel = decision.riskLevel.name,
            recommendation = decision.recommendation.name,
            priceSource = decision.price?.evidenceSource?.name ?: PriceEvidenceSource.UNAVAILABLE.name,
        )
    }

    suspend fun getCachedPrice(product: Product, pagePriceCents: Long): PriceComparison? {
        val entity = dao.getValidPriceCache(cacheKey(product, pagePriceCents), System.currentTimeMillis()) ?: return null
        return runCatching {
            json.decodeFromString(PriceComparison.serializer(), entity.comparisonJson).copy(cached = true)
        }.getOrNull()
    }

    suspend fun cachePrice(product: Product, comparison: PriceComparison) {
        val ttl = when (comparison.evidenceSource) {
            PriceEvidenceSource.SEARCH_VERIFIED -> TimeUnit.HOURS.toMillis(2)
            PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE,
            PriceEvidenceSource.MODEL_ESTIMATE -> TimeUnit.HOURS.toMillis(24)
            PriceEvidenceSource.UNAVAILABLE -> return
        }
        val now = System.currentTimeMillis()
        dao.deleteExpiredPriceCache(now)
        dao.upsertPriceCache(
            PriceCacheEntity(
                cacheKey = cacheKey(product, comparison.pagePriceCents),
                productName = product.name,
                pagePriceCents = comparison.pagePriceCents,
                comparisonJson = json.encodeToString(PriceComparison.serializer(), comparison.copy(cached = false)),
                priceSource = comparison.evidenceSource.name,
                createdAt = now,
                expiresAt = now + ttl,
            )
        )
    }

    private fun cacheKey(product: Product, pagePriceCents: Long): String =
        product.name.lowercase().filter { it.isLetterOrDigit() }.take(80) + ":" + pagePriceCents

    suspend fun recordFeedback(decisionId: String, action: UserAction, delayHours: Int?): Boolean {
        val now = System.currentTimeMillis()
        return when (action) {
            UserAction.PURCHASE -> dao.recordPurchase(decisionId, now)
            UserAction.DELAY -> dao.recordNonPurchase(decisionId, action.name, delayHours, now)
            UserAction.CANCEL -> dao.recordNonPurchase(decisionId, action.name, null, now)
        }
    }

    suspend fun recordDiagnostic(
        provider: String,
        configured: Boolean,
        success: Boolean,
        latencyMs: Long?,
        httpStatus: Int?,
        error: String?,
    ) {
        val previous = dao.getDiagnostic(provider)
        dao.upsertDiagnostic(
            ApiDiagnosticEntity(
                provider = provider,
                configured = configured,
                lastSuccessAt = if (success) System.currentTimeMillis() else previous?.lastSuccessAt,
                lastLatencyMs = latencyMs,
                lastHttpStatus = httpStatus,
                lastError = error?.take(200),
            )
        )
    }
}
