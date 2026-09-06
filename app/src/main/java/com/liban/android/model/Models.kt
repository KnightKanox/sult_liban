package com.liban.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Money(
    val cents: Long,
    val currency: String = "CNY",
) {
    init {
        require(cents >= 0) { "Money cannot be negative" }
    }

    fun yuanText(): String = "¥%,.2f".format(cents / 100.0)
}

@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH }

@Serializable
enum class Recommendation { BUY, DELAY, CANCEL }

@Serializable
enum class UserAction { PURCHASE, DELAY, CANCEL }

@Serializable
enum class SourceMode { LIVE, FALLBACK, MIXED }

@Serializable
enum class SkillId(val wireName: String) {
    BUDGET("budget_check"),
    GOAL("goal_impact"),
    HISTORY("history_check"),
    IMPULSE("impulse_check"),
    PRICE("price_compare");

    companion object {
        fun fromWireName(value: String): SkillId? = entries.firstOrNull { it.wireName == value }
    }
}

@Serializable
data class Product(
    val name: String,
    val category: String,
)

@Serializable
data class PriceInfo(
    @SerialName("current_cents") val currentCents: Long,
    @SerialName("original_cents") val originalCents: Long? = null,
    val currency: String = "CNY",
)

@Serializable
data class SceneSignals(
    val discount: Boolean = false,
    @SerialName("limited_time") val limitedTime: Boolean = false,
    val scarcity: Boolean = false,
    val labels: List<String> = emptyList(),
)

@Serializable
data class SceneContext(
    @SerialName("scene_type") val sceneType: String = "unknown",
    val product: Product,
    val price: PriceInfo,
    val signals: SceneSignals = SceneSignals(),
    @SerialName("required_skills") val requiredSkills: List<String> = emptyList(),
    val confidence: Double = 0.0,
) {
    fun validSkills(): Set<SkillId> = requiredSkills.mapNotNull(SkillId::fromWireName).toSet()
}

@Serializable
data class BudgetResult(val ratio: Double, val risk: RiskLevel)

@Serializable
data class GoalResult(
    val pressure: RiskLevel,
    @SerialName("delay_days") val delayDays: Int,
)

@Serializable
data class HistoryResult(
    @SerialName("similar_count_30d") val similarCount30d: Int,
    val risk: RiskLevel,
)

@Serializable
data class ImpulseResult(
    val score: Double,
    val risk: RiskLevel,
    val signals: List<String>,
)

@Serializable
enum class Availability { AVAILABLE, UNAVAILABLE, ERROR }

@Serializable
data class PriceComparison(
    val availability: Availability,
    @SerialName("reference_low_cents") val referenceLowCents: Long? = null,
    @SerialName("reference_high_cents") val referenceHighCents: Long? = null,
    @SerialName("page_price_cents") val pagePriceCents: Long,
    @SerialName("premium_ratio") val premiumRatio: Double? = null,
    val source: SourceMode = SourceMode.LIVE,
)

@Serializable
data class SkillResults(
    val budget: BudgetResult? = null,
    val goal: GoalResult? = null,
    val history: HistoryResult? = null,
    val impulse: ImpulseResult? = null,
    val price: PriceComparison? = null,
)

@Serializable
data class DecisionDisplay(
    val title: String,
    val summary: String,
    @SerialName("key_points") val keyPoints: List<String>,
)

@Serializable
data class DecisionResult(
    @SerialName("decision_id") val decisionId: String = UUID.randomUUID().toString(),
    @SerialName("risk_score") val riskScore: Double,
    @SerialName("risk_level") val riskLevel: RiskLevel,
    val recommendation: Recommendation,
    @SerialName("delay_hours") val delayHours: Int? = null,
    val factors: List<String>,
    val display: DecisionDisplay,
    @SerialName("source_mode") val sourceMode: SourceMode = SourceMode.LIVE,
)

sealed interface AnalysisState {
    data object Idle : AnalysisState
    data object Capturing : AnalysisState
    data object Analyzing : AnalysisState
    data class NeedsCorrection(val scene: SceneContext?, val message: String) : AnalysisState
    data class Result(val scene: SceneContext, val decision: DecisionResult) : AnalysisState
    data class Error(val message: String, val canRetry: Boolean = true) : AnalysisState
}

data class SkillExecution<T>(
    val availability: Availability,
    val value: T? = null,
    val message: String? = null,
)

