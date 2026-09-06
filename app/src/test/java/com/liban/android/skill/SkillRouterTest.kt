package com.liban.android.skill

import com.liban.android.data.UserProfileEntity
import com.liban.android.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRouterTest {
    private val profile = UserProfileEntity(monthlyBudgetCents = 500_000, currentSpentCents = 100_000)
    private val router = SkillRouter()

    @Test
    fun validRequestedSkillsWin() {
        val scene = scene(required = listOf("budget_check", "unknown", "price_compare"))
        assertEquals(setOf(SkillId.BUDGET, SkillId.PRICE), router.route(scene, profile))
    }

    @Test
    fun unknownOnlyFallsBackToRules() {
        val result = router.route(scene(required = listOf("made_up")), profile)
        assertEquals(setOf(SkillId.BUDGET, SkillId.HISTORY), result)
    }

    @Test
    fun durableGoodsUseAllSkills() {
        val result = router.route(scene(category = "electronics"), profile)
        assertTrue(result.containsAll(SkillId.entries))
    }

    @Test
    fun promotionUsesImpulseRoute() {
        val result = router.route(scene(signals = SceneSignals(limitedTime = true)), profile)
        assertEquals(setOf(SkillId.BUDGET, SkillId.HISTORY, SkillId.IMPULSE), result)
    }

    @Test
    fun expensivePurchaseUsesBudgetGoalHistoryAndPrice() {
        val result = router.route(scene(priceCents = 200_000), profile)
        assertEquals(setOf(SkillId.BUDGET, SkillId.GOAL, SkillId.HISTORY, SkillId.PRICE), result)
    }

    private fun scene(
        category: String = "daily",
        required: List<String> = emptyList(),
        signals: SceneSignals = SceneSignals(),
        priceCents: Long = 10_000,
    ) = SceneContext(
        product = Product("测试商品", category),
        price = PriceInfo(priceCents),
        signals = signals,
        requiredSkills = required,
        confidence = 1.0,
    )
}
