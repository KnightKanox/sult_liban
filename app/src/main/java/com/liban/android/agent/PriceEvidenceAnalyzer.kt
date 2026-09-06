package com.liban.android.agent

import com.liban.android.model.*
import com.liban.android.ocr.SceneParser
import java.net.URI
import kotlin.math.ceil
import kotlin.math.floor

object PriceEvidenceAnalyzer {
    private val priceRegex = Regex("""(?:[¥￥]\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元)""")
    private val rejectedWords = listOf("优惠", "立减", "省", "差价", "定金", "月供", "配件", "保护壳", "维修", "二手", "回收")
    private val modelPattern = Regex("""(?i)\b(?=[A-Z0-9-]{4,24}\b)(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*\d)[A-Z0-9-]+\b""")

    fun buildQuery(product: Product): String {
        val identity = listOfNotNull(product.brand, product.name, product.model)
            .joinToString(" ").replace(Regex("""\s+"""), " ").trim()
        return "$identity 售价 价格 购买".take(70)
    }

    fun extract(product: Product, hits: List<SearchHit>): List<PriceSample> {
        val model = normalizedModel(product) ?: return emptyList()
        return hits.flatMap { hit ->
            val combined = "${hit.title} ${hit.content}"
            if (!SceneParser.normalizeModel(combined).contains(model)) return@flatMap emptyList()
            if (rejectedWords.any(combined::contains)) return@flatMap emptyList()
            priceRegex.findAll(combined).mapNotNull { match ->
                val nearbyStart = (match.range.first - 18).coerceAtLeast(0)
                val nearbyEnd = (match.range.last + 18).coerceAtMost(combined.lastIndex)
                val nearby = combined.substring(nearbyStart, nearbyEnd + 1)
                if (rejectedWords.any(nearby::contains)) return@mapNotNull null
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val cents = parseCents(raw) ?: return@mapNotNull null
                if (cents !in 1_000L..100_000_000L) return@mapNotNull null
                val domain = runCatching { URI(hit.url).host.removePrefix("www.") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PriceSample(cents, domain, PriceReference(hit.title.take(100), hit.url, hit.media))
            }.toList()
        }.distinctBy { it.domain to it.cents }
    }

    fun verifiedComparison(pagePriceCents: Long, samples: List<PriceSample>, now: Long): PriceComparison? {
        if (samples.map { it.domain }.distinct().size < 3) return null
        val sorted = samples.map { it.cents }.distinct().sorted()
        if (sorted.size < 3) return null
        val median = percentile(sorted, 0.5)
        val filteredValues = sorted.filter { it >= median * 0.6 && it <= median * 1.6 }
        val filtered = samples.filter { it.cents in filteredValues }
        if (filtered.map { it.domain }.distinct().size < 3 || filteredValues.size < 3) return null
        val low = percentile(filteredValues, 0.25)
        val high = percentile(filteredValues, 0.75).coerceAtLeast(low)
        return PriceComparison(
            availability = Availability.AVAILABLE,
            referenceLowCents = low,
            referenceHighCents = high,
            pagePriceCents = pagePriceCents,
            premiumRatio = (pagePriceCents - high).toDouble() / high.coerceAtLeast(1),
            evidenceSource = PriceEvidenceSource.SEARCH_VERIFIED,
            confidence = (0.65 + filtered.map { it.domain }.distinct().size * 0.06).coerceAtMost(0.95),
            sampleCount = filtered.size,
            references = filtered.distinctBy { it.domain }.take(3).map { it.reference },
            queriedAt = now,
        )
    }

    fun normalizedModel(product: Product): String? {
        product.model?.let { value ->
            SceneParser.normalizeModel(value).takeIf { it.length >= 4 }?.let { return it }
        }
        return modelPattern.find(product.name)?.value
            ?.let(SceneParser::normalizeModel)
            ?.takeIf { it.length >= 4 }
    }

    private fun percentile(values: List<Long>, percentile: Double): Long {
        if (values.size == 1) return values.first()
        val index = (values.lastIndex * percentile).coerceIn(0.0, values.lastIndex.toDouble())
        val lower = floor(index).toInt()
        val upper = ceil(index).toInt()
        if (lower == upper) return values[lower]
        val fraction = index - lower
        return (values[lower] * (1 - fraction) + values[upper] * fraction).toLong()
    }

    private fun parseCents(raw: String): Long? {
        val parts = raw.replace(",", "").split('.', limit = 2)
        val yuan = parts[0].toLongOrNull() ?: return null
        val decimals = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLongOrNull() ?: 0
        return yuan * 100 + decimals
    }
}
