package com.liban.android.agent

import com.liban.android.model.*
import kotlin.math.roundToInt

object DecisionEngine {
    fun decide(
        scene: SceneContext,
        skills: SkillResults,
        decisionId: String? = null,
    ): DecisionResult {
        val priceRisk = skills.price
            ?.takeIf {
                it.availability == Availability.AVAILABLE &&
                    it.evidenceSource == PriceEvidenceSource.SEARCH_VERIFIED
            }
            ?.let {
                when {
                    (it.premiumRatio ?: 0.0) > 0.10 -> RiskLevel.HIGH
                    (it.premiumRatio ?: 0.0) > 0.0 -> RiskLevel.MEDIUM
                    else -> RiskLevel.LOW
                }
            }
        val risks = listOfNotNull(
            skills.budget?.risk,
            skills.goal?.pressure,
            skills.history?.risk,
            skills.impulse?.risk,
            priceRisk,
        )
        val scores = risks.map {
            when (it) {
                RiskLevel.LOW -> 0.2
                RiskLevel.MEDIUM -> 0.55
                RiskLevel.HIGH -> 0.9
            }
        }
        val riskScore = (scores.average().takeUnless(Double::isNaN) ?: 0.5).coerceIn(0.0, 1.0)
        val risk = when {
            risks.any { it == RiskLevel.HIGH } -> RiskLevel.HIGH
            risks.any { it == RiskLevel.MEDIUM } -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        // A model estimate is informational and can never produce a BUY recommendation.
        val modelOnly = skills.price?.evidenceSource in setOf(
            PriceEvidenceSource.MODEL_ESTIMATE,
            PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE,
        )
        val recommendation = if (risk == RiskLevel.LOW && !modelOnly) Recommendation.BUY else Recommendation.DELAY
        val points = buildList {
            skills.budget?.let { add("将占用剩余预算的 ${(it.ratio * 100).roundToInt()}%") }
            skills.goal?.let { add("预计使储蓄目标延后 ${it.delayDays} 天") }
            skills.history?.let { add("近 30 天已有 ${it.similarCount30d} 次同类消费") }
            skills.impulse?.takeIf { it.signals.isNotEmpty() }?.let {
                add("检测到促销信号：${it.signals.joinToString()}")
            }
            skills.price?.takeIf { it.availability == Availability.AVAILABLE }?.let {
                val prefix = when (it.evidenceSource) {
                    PriceEvidenceSource.SEARCH_VERIFIED -> "实时搜索"
                    PriceEvidenceSource.SEARCH_ASSISTED_ESTIMATE -> "搜索辅助估价"
                    PriceEvidenceSource.MODEL_ESTIMATE -> "模型估价"
                    PriceEvidenceSource.UNAVAILABLE -> "价格"
                }
                add(if ((it.premiumRatio ?: 0.0) > 0) "$prefix：页面价高于参考区间" else "$prefix：页面价未高于参考区间")
            }
        }.take(4)
        val title = when (recommendation) {
            Recommendation.BUY -> "可以考虑购买"
            Recommendation.DELAY -> "建议再等等"
            Recommendation.CANCEL -> "建议放弃"
        }
        val summary = when (risk) {
            RiskLevel.HIGH -> "这笔消费存在明显压力，建议冷静 24 小时后再决定。"
            RiskLevel.MEDIUM -> "这笔消费有一定风险，建议比较价格并确认真实需求。"
            RiskLevel.LOW -> if (modelOnly) "本地风险较低，但价格仅为估价，建议核实后决定。" else "当前预算与消费风险较低，仍建议按实际需求决定。"
        }
        return DecisionResult(
            decisionId = decisionId ?: java.util.UUID.randomUUID().toString(),
            riskScore = riskScore,
            riskLevel = risk,
            recommendation = recommendation,
            delayHours = if (recommendation == Recommendation.DELAY) 24 else null,
            factors = risks.map { it.name.lowercase() },
            display = DecisionDisplay(title, summary, points.ifEmpty { listOf("信息有限，请确认商品和价格。") }),
            price = skills.price,
        )
    }
}
