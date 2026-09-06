package com.liban.android.agent

import com.liban.android.model.*
import com.liban.android.ocr.SceneParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.floor

object PriceEvidenceAnalyzer {
    private val priceRegex = Regex("""(?:[¥￥]\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元)""")
    private val wrongProductWords = listOf("保护壳", "耳机套", "维修", "二手", "回收", "配件", "租赁")
    private val rejectedPriceWords = listOf("立减", "已省", "差价", "定金", "月供", "原价", "首发价", "指导价", "运费", "优惠券", "减")
    private val currentPriceWords = listOf("到手价", "券后", "现价", "售价", "成交价", "活动价")
    private val modelPattern = Regex("""(?i)\b(?=[A-Z0-9-]{4,24}\b)(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*\d)[A-Z0-9-]+\b""")

    fun buildQuery(product: Product, broad: Boolean = false): String {
        val model = product.model?.takeIf(String::isNotBlank)
        val attributes = ProductText.searchAttributes(product)
        val terms = if (broad && model == null) {
            listOfNotNull(product.productType) + attributes.filter { it.key == "material" }.map { it.value }
        } else {
            listOfNotNull(model?.let { "\"$it\"" }, product.brand, product.productType) +
                attributes.filter { it.key != "model" }.sortedBy { if (it.scope == AttributeScope.SELECTED) 0 else 1 }.map { it.value }
        }
        val identity = terms.filter(String::isNotBlank).distinct().ifEmpty { listOf(ProductText.cleanTitle(product.name)) }
            .joinToString(" ").replace(Regex("\\s+"), " ").trim().take(58)
        return "$identity 到手价 人民币".take(70)
    }

    private fun mentionsModel(text: String, model: String): Boolean {
        val pattern = model.map { Regex.escape(it.toString()) }.joinToString("[\\s_-]*")
        return Regex("(?i)(?<![A-Z0-9])$pattern(?![A-Z0-9])").containsMatchIn(text)
    }

    fun relevantHits(product: Product, hits: List<SearchHit>): List<SearchHit> {
        val model = normalizedModel(product)
        return hits.filter { hit ->
            if (wrongProductWords.any { hit.title.contains(it) && !product.name.contains(it) }) return@filter false
            val text = "${hit.title} ${hit.content}"
            if (!ProductText.searchAttributes(product).filter { it.key != "model" }.all { mentionsAttribute(text, it) }) return@filter false
            if (model != null) {
                val titleModels = modelPattern.findAll(hit.title).map { SceneParser.normalizeModel(it.value) }.toList()
                (titleModels.isEmpty() || mentionsModel(hit.title, model)) && mentionsModel("${hit.title} ${hit.content}", model)
            } else {
                val type = product.productType
                if (type != null) ProductText.normalize(text).contains(ProductText.normalize(type)) &&
                    (product.brand == null || ProductText.normalize(text).contains(ProductText.normalize(product.brand)))
                else {
                    val name = SceneParser.normalizeModel(product.name)
                    name.length >= 4 && SceneParser.normalizeModel(text).contains(name)
                }
            }
        }.distinctBy { it.url.ifBlank { it.title + it.content.take(100) } }
    }

    private fun mentionsAttribute(text: String, attr: ProductAttribute): Boolean {
        val value = ProductText.normalize(attr.value)
        if (value.firstOrNull()?.isDigit() == true) {
            return Regex("(?i)(?<![0-9])" + Regex.escape(value) + "(?![0-9])").containsMatchIn(ProductText.normalize(text))
        }
        return ProductText.normalize(text).contains(value)
    }

    fun classifyHits(product: Product, hits: List<SearchHit>): List<SearchHit> {
        val related = relevantHits(product, hits).toSet()
        return hits.mapNotNull { hit ->
            when {
                hit in related -> hit.copy(matchKind = if (normalizedModel(product) != null) SearchMatchKind.EXACT else SearchMatchKind.SIMILAR)
                product.model == null && product.productType != null &&
                    ProductText.normalize(hit.title).contains(ProductText.normalize(product.productType)) &&
                    wrongProductWords.none { hit.title.contains(it) && !product.name.contains(it) } -> hit.copy(matchKind = SearchMatchKind.SIMILAR)
                else -> null
            }
        }.distinctBy { it.url.ifBlank { it.title + it.content.take(100) } }
    }

    fun extract(product: Product, hits: List<SearchHit>, now: Long = System.currentTimeMillis(), pagePrice: PriceInfo? = null): List<PriceSample> {
        val model = normalizedModel(product) ?: return emptyList()
        if (product.attributes.any { it.key in ProductText.matchingKeys && it.scope in setOf(AttributeScope.MENTIONED, AttributeScope.UNKNOWN) }) return emptyList()
        if (pagePrice != null && product.attributes.any { it.scope == AttributeScope.SELECTED } && pagePrice.variantBinding != AttributeScope.SELECTED) return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        return relevantHits(product, hits).flatMap { hit ->
            val url = hit.url.toHttpUrlOrNull() ?: return@flatMap emptyList()
            val date = hit.publishDate?.take(10)?.replace('/', '-')?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (date != null && (date.isBefore(today.minusDays(30)) || date.isAfter(today.plusDays(1)))) return@flatMap emptyList()
            val combined = "${hit.title}。${hit.content}"
            var previousEnd = 0
            priceRegex.findAll(combined).mapNotNull { match ->
                val prefix = combined.substring(previousEnd, match.range.first).takeLast(24)
                previousEnd = match.range.last + 1
                val suffix = combined.substring(previousEnd).take(4)
                val rejectedAt = rejectedPriceWords.maxOf { prefix.lastIndexOf(it) }
                val currentAt = currentPriceWords.maxOf { prefix.lastIndexOf(it) }
                if (rejectedAt >= 0 && rejectedAt >= currentAt || suffix.startsWith("/期")) return@mapNotNull null
                val sentenceStart = combined.lastIndexOfAny(charArrayOf('。', '；', '\n'), match.range.first).coerceAtLeast(0)
                val nearby = combined.substring(sentenceStart, match.range.last + 1)
                val specs = ProductText.searchAttributes(product).filter { it.key != "model" }
                if (!specs.all { mentionsAttribute(nearby, it) || mentionsAttribute(hit.title, it) }) return@mapNotNull null
                val capacities = Regex("(?i)\\d+\\s*(?:GB|TB)").findAll(hit.title + " " + nearby).map { ProductText.normalize(it.value) }.toSet()
                if (specs.any { it.key == "storage_capacity" } && capacities.any { size -> specs.none { ProductText.normalize(it.value) == size } }) return@mapNotNull null
                val otherModel = modelPattern.findAll(nearby)
                    .filterNot { Regex("(?i)\\d+(?:GB|TB|ML|KG)").matches(it.value) }
                    .map { SceneParser.normalizeModel(it.value) }.any { it != model }
                if (otherModel || (!mentionsModel(hit.title, model) && !mentionsModel(nearby, model))) return@mapNotNull null
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val cents = parseYuanToCents(raw.replace(",", "")) ?: return@mapNotNull null
                if (cents !in 1_000L..100_000_000L) return@mapNotNull null
                PriceSample(cents, url.topPrivateDomain() ?: url.host.removePrefix("www."), PriceReference(hit.title.take(100), hit.url, hit.media))
            }.toList()
        }.distinctBy { it.domain to it.cents }
    }

    fun verifiedComparison(pagePriceCents: Long, samples: List<PriceSample>, now: Long): PriceComparison? {
        // Each registrable domain gets one vote, including when several sites quote the same price.
        val perDomain = samples.groupBy { it.domain }.map { (_, group) ->
            group.minBy { kotlin.math.abs(it.cents - percentile(group.map { it.cents }.sorted(), 0.5)) }
        }
        if (perDomain.size < 3) return null
        val median = percentile(perDomain.map { it.cents }.sorted(), 0.5)
        val filtered = perDomain.filter { it.cents >= median * 0.6 && it.cents <= median * 1.6 }
        if (filtered.size < 3) return null
        val values = filtered.map { it.cents }.sorted()
        val low = percentile(values, 0.25)
        val high = percentile(values, 0.75).coerceAtLeast(low)
        return PriceComparison(
            availability = Availability.AVAILABLE, referenceLowCents = low, referenceHighCents = high,
            pagePriceCents = pagePriceCents, premiumRatio = (pagePriceCents - high).toDouble() / high.coerceAtLeast(1),
            evidenceSource = PriceEvidenceSource.SEARCH_VERIFIED,
            confidence = (0.65 + filtered.size * 0.06).coerceAtMost(0.95), sampleCount = filtered.size,
            references = filtered.take(3).map { it.reference }, queriedAt = now,
            rationale = "跨站报价参考，促销资格和最终结算价以商家页面为准",
        )
    }

    fun normalizedModel(product: Product): String? =
        product.model?.let(SceneParser::normalizeModel)?.takeIf { it.length >= 4 }

    private fun percentile(values: List<Long>, percentile: Double): Long {
        val index = (values.lastIndex * percentile).coerceIn(0.0, values.lastIndex.toDouble())
        val lower = floor(index).toInt()
        val upper = ceil(index).toInt()
        return (values[lower] * (1 - (index - lower)) + values[upper] * (index - lower)).toLong()
    }
}
