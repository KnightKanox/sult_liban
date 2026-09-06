package com.liban.android.agent

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class DecisionEngineTest {
    private val scene = SceneContext(
        product = Product("Sony WH-1000XM6", "electronics", model = "WH-1000XM6"),
        price = PriceInfo(2_999_00),
        confidence = 1.0,
    )

    @Test
    fun verifiedPremiumCanRaiseRisk() {
        val price = PriceComparison(
            availability = Availability.AVAILABLE,
            referenceLowCents = 2_300_00,
            referenceHighCents = 2_500_00,
            pagePriceCents = 2_999_00,
            premiumRatio = 0.1996,
            evidenceSource = PriceEvidenceSource.SEARCH_VERIFIED,
        )
        val result = DecisionEngine.decide(scene, SkillResults(price = price))
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertEquals(Recommendation.DELAY, result.recommendation)
        assertTrue(result.display.keyPoints.any { it.contains("高于参考区间") })
    }

    @Test
    fun modelEstimateNeverCreatesBuyRecommendation() {
        val price = PriceComparison(
            availability = Availability.AVAILABLE,
            referenceLowCents = 2_800_00,
            referenceHighCents = 3_200_00,
            pagePriceCents = 2_999_00,
            premiumRatio = -0.06,
            evidenceSource = PriceEvidenceSource.MODEL_ESTIMATE,
        )
        val result = DecisionEngine.decide(
            scene,
            SkillResults(budget = BudgetResult(0.05, RiskLevel.LOW), price = price),
        )
        assertEquals(RiskLevel.LOW, result.riskLevel)
        assertEquals(Recommendation.DELAY, result.recommendation)
    }

    @Test
    fun finalDecisionKeepsPreliminaryId() {
        val result = DecisionEngine.decide(scene, SkillResults(), decisionId = "same-id")
        assertEquals("same-id", result.decisionId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMoneyIsRejected() {
        Money(-1)
    }
}
