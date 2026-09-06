package com.liban.android.agent

import com.liban.android.demo.DemoFixtures
import com.liban.android.demo.DemoScenario
import com.liban.android.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {
    @Test
    fun demoAProducesHighDelayFor24Hours() {
        val scene = DemoFixtures.scene(DemoScenario.A)
        val result = DecisionEngine.decide(
            scene,
            SkillResults(
                budget = BudgetResult(1.49, RiskLevel.HIGH),
                history = HistoryResult(0, RiskLevel.LOW),
                impulse = ImpulseResult(1.0, RiskLevel.HIGH, listOf("limited_time", "scarcity", "discount")),
            ),
            SourceMode.FALLBACK,
        )
        assertEquals(RiskLevel.HIGH, result.riskLevel)
        assertEquals(Recommendation.DELAY, result.recommendation)
        assertEquals(24, result.delayHours)
        assertEquals(SourceMode.FALLBACK, result.sourceMode)
    }

    @Test
    fun demoBMentionsReferenceRange() {
        val scene = DemoFixtures.scene(DemoScenario.B)
        val price = DemoFixtures.price(scene.product, Money(scene.price.currentCents))!!
        val result = DecisionEngine.decide(scene, SkillResults(price = price), SourceMode.FALLBACK)
        assertTrue(result.display.keyPoints.any { it.contains("高于参考区间") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeMoneyIsRejected() {
        Money(-1)
    }
}

