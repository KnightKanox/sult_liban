package com.liban.android.ocr

import com.liban.android.model.*
import kotlin.math.max

object SceneParser {
    private val moneyRegex = Regex("""(?:[¥￥]\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元)""")
    private val modelRegex = Regex("""(?i)\b(?=[A-Z0-9-]{4,24}\b)(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*\d)[A-Z0-9]+(?:-[A-Z0-9]+)+|\b(?=[A-Z0-9]{5,24}\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)[A-Z0-9]+\b""")
    private val positivePriceWords = listOf("现价", "到手价", "售价", "活动价", "券后", "价格")
    private val originalPriceWords = listOf("原价", "划线价", "专柜价")
    private val titleNoise = listOf("首页", "客服", "购物车", "立即购买", "加入购物车", "月销", "评价", "详情", "优惠券")

    fun parse(document: OcrDocument): SceneContext? {
        if (document.lines.isEmpty()) return null
        val medianHeight = document.lines.map { it.box.height }.sorted().let {
            if (it.isEmpty()) 1.0 else it[it.size / 2].coerceAtLeast(1).toDouble()
        }
        val candidates = document.lines.flatMap { line ->
            moneyRegex.findAll(line.text).mapNotNull { match ->
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val cents = parseCents(raw) ?: return@mapNotNull null
                if (cents !in 100L..100_000_000L) return@mapNotNull null
                val context = line.text
                var score = 0.55 + line.confidence * 0.20
                if (positivePriceWords.any(context::contains)) score += 0.15
                if (originalPriceWords.any(context::contains)) score -= 0.30
                score += ((line.box.height / medianHeight - 1.0) * 0.08).coerceIn(-0.05, 0.18)
                if (line.box.centerY in (document.height * 0.20).toInt()..(document.height * 0.85).toInt()) score += 0.05
                PriceCandidate(cents, line, score.coerceIn(0.0, 1.0), originalPriceWords.any(context::contains))
            }.toList()
        }
        val current = candidates.filterNot { it.isOriginal }.maxByOrNull { it.score }
            ?: candidates.maxByOrNull { it.score } ?: return null
        val original = candidates
            .filter { it.isOriginal && it.cents >= current.cents }
            .maxByOrNull { it.score }
        val titleCandidates = document.lines.filter { line ->
            line.box.bottom <= current.line.box.top + max(current.line.box.height, 24) &&
                current.line.box.top - line.box.bottom <= document.height * 0.45 &&
                line.text.length in 4..80 &&
                moneyRegex.find(line.text) == null &&
                titleNoise.none(line.text::contains) &&
                line.text.any { it.isLetter() }
        }
        val title = titleCandidates.maxByOrNull { line ->
            val proximity = 1.0 - ((current.line.box.top - line.box.bottom).coerceAtLeast(0).toDouble() / document.height)
            line.confidence * 0.35 + (line.box.height / medianHeight).coerceAtMost(3.0) * 0.15 +
                proximity * 0.35 + if (modelRegex.containsMatchIn(line.text)) 0.25 else 0.0
        }
        val fallbackTitle = document.lines.firstOrNull {
            it.text.length in 4..80 && it.text.any(Char::isLetter) && moneyRegex.find(it.text) == null
        }
        val chosenTitle = title ?: fallbackTitle ?: return null
        val nearbyText = document.lines
            .filter { kotlin.math.abs(it.box.centerY - chosenTitle.box.centerY) < document.height * 0.20 }
            .joinToString(" ") { it.text }
        val model = modelRegex.find(nearbyText)?.value
        val name = listOf(chosenTitle.text, model)
            .filterNotNull().distinctBy { normalizeModel(it) }.joinToString(" ").take(100)
        val productConfidence = (
            chosenTitle.confidence * 0.55 +
                if (chosenTitle.box.top < current.line.box.top) 0.20 else 0.0 +
                if (model != null) 0.20 else 0.05
            ).coerceIn(0.0, 1.0)
        val allText = document.lines.joinToString(" ") { it.text }
        val labels = buildList {
            if (listOf("限时", "倒计时", "今日结束").any(allText::contains)) add("限时")
            if (listOf("仅剩", "最后", "库存紧张", "抢完").any(allText::contains)) add("稀缺")
            if (listOf("折", "优惠", "立减", "券").any(allText::contains)) add("折扣")
        }
        return SceneContext(
            product = Product(name, categoryFor(allText), model = model),
            price = PriceInfo(current.cents, original?.cents),
            signals = SceneSignals(
                discount = "折扣" in labels,
                limitedTime = "限时" in labels,
                scarcity = "稀缺" in labels,
                labels = labels,
            ),
            confidence = minOf(productConfidence, current.score),
            productConfidence = productConfidence,
            priceConfidence = current.score,
        )
    }

    fun normalizeModel(value: String): String =
        value.uppercase().filter { it.isLetterOrDigit() }

    private fun parseCents(raw: String): Long? {
        val normalized = raw.replace(",", "")
        val parts = normalized.split('.', limit = 2)
        val yuan = parts[0].toLongOrNull() ?: return null
        val decimals = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLongOrNull() ?: 0
        return yuan * 100 + decimals
    }

    private fun categoryFor(text: String): String = when {
        listOf("手机", "电脑", "耳机", "相机", "电视", "平板", "型号").any(text::contains) -> "electronics"
        listOf("冰箱", "空调", "洗衣机", "家电").any(text::contains) -> "appliance"
        listOf("外卖", "食品", "饮料", "餐").any(text::contains) -> "food"
        listOf("衣", "鞋", "包", "美妆").any(text::contains) -> "fashion"
        else -> "other"
    }

    private data class PriceCandidate(
        val cents: Long,
        val line: OcrLine,
        val score: Double,
        val isOriginal: Boolean,
    )
}
