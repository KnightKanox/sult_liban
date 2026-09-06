package com.liban.android.demo

import android.content.Context
import com.liban.android.model.*

enum class DemoScenario { A, B, C }

class DemoScenarioStore(context: Context) {
    private val preferences = context.getSharedPreferences("demo", Context.MODE_PRIVATE)

    var selected: DemoScenario
        get() = runCatching {
            DemoScenario.valueOf(preferences.getString("scenario", DemoScenario.A.name)!!)
        }.getOrDefault(DemoScenario.A)
        set(value) { preferences.edit().putString("scenario", value.name).apply() }
}

object DemoFixtures {
    fun scene(scenario: DemoScenario): SceneContext = when (scenario) {
        DemoScenario.A -> SceneContext(
            sceneType = "ecommerce_product",
            product = Product("限时潮流单品", "entertainment"),
            price = PriceInfo(89_900, 109_900),
            signals = SceneSignals(discount = true, limitedTime = true, scarcity = true, labels = listOf("仅剩2件")),
            requiredSkills = listOf("budget_check", "history_check", "impulse_check"),
            confidence = 0.97,
        )
        DemoScenario.B -> SceneContext(
            sceneType = "ecommerce_product",
            product = Product("Sony WH-1000XM6", "electronics"),
            price = PriceInfo(289_900, 319_900),
            signals = SceneSignals(discount = true),
            requiredSkills = listOf("budget_check", "price_compare"),
            confidence = 0.96,
        )
        DemoScenario.C -> SceneContext(
            sceneType = "ecommerce_product",
            product = Product("高性能笔记本电脑", "electronics"),
            price = PriceInfo(799_900),
            signals = SceneSignals(),
            requiredSkills = listOf("budget_check", "goal_impact", "history_check", "price_compare"),
            confidence = 0.95,
        )
    }

    fun price(product: Product, pagePrice: Money): PriceComparison? = when {
        product.name.contains("WH-1000XM6", ignoreCase = true) -> PriceComparison(
            Availability.AVAILABLE, 249_900, 269_900, pagePrice.cents,
            premiumRatio = (pagePrice.cents - 269_900).toDouble() / 269_900,
            source = SourceMode.FALLBACK,
        )
        product.name.contains("笔记本") -> PriceComparison(
            Availability.AVAILABLE, 719_900, 769_900, pagePrice.cents,
            premiumRatio = (pagePrice.cents - 769_900).toDouble() / 769_900,
            source = SourceMode.FALLBACK,
        )
        else -> null
    }
}

