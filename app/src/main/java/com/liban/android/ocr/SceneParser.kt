package com.liban.android.ocr

import com.liban.android.model.*
import kotlin.math.max
import kotlin.math.abs
import java.text.Normalizer

object SceneParser {
    private val moneyRegex = Regex("""(?:[¥￥]\s*([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)|([0-9]{1,7}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)\s*元)""")
    private val modelRegex = Regex("""(?i)\b(?=[A-Z0-9-]{4,24}\b)(?=[A-Z0-9-]*[A-Z])(?=[A-Z0-9-]*\d)[A-Z0-9]+(?:-[A-Z0-9]+)+|\b(?=[A-Z0-9]{5,24}\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)[A-Z0-9]+\b""")
    private val positivePriceWords = listOf("现价", "到手价", "售价", "活动价", "券后", "价格", "补贴价", "大促价", "拼单价")
    private val originalPriceWords = listOf("原价", "划线价", "专柜价")
    private val nonProductPriceWords = listOf("立减", "已省", "直降", "优惠券", "定金", "订金", "月供", "每期", "运费", "配送费", "满减")
    private val titleNoise = listOf("首页", "客服", "购物车", "立即购买", "加入购物车", "月销", "评价", "详情", "优惠券", "店铺", "旗舰店", "领券", "包邮", "已售", "送至", "保障", "参数", "猜你喜欢", "为你推荐", "限时", "仅剩")

    fun parse(document: OcrDocument): SceneContext? {
        if (document.lines.isEmpty()) return null
        val lines = priceRows(OcrReadingOrder.document(document).lines.map {
            it.copy(text = Normalizer.normalize(it.text, Normalizer.Form.NFKC).trim())
        })
        val medianHeight = document.lines.map { it.box.height }.sorted().let {
            if (it.isEmpty()) 1.0 else it[it.size / 2].coerceAtLeast(1).toDouble()
        }
        val candidates = lines.flatMap { line ->
            moneyRegex.findAll(line.text).mapNotNull { match ->
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                val cents = parseCents(raw) ?: return@mapNotNull null
                if (cents !in 100L..100_000_000L) return@mapNotNull null
                // Scope labels to this amount, not another price on the same OCR line.
                val preceding = line.text.substring(0, match.range.first)
                val previousAmount = moneyRegex.findAll(preceding).lastOrNull()
                val context = preceding.substring(previousAmount?.range?.last?.plus(1) ?: 0).takeLast(12)
                val suffix = line.text.substring(match.range.last + 1).take(4)
                if (nonProductPriceWords.any(context::contains) || suffix.startsWith("/期") || suffix.startsWith("起")) return@mapNotNull null
                var score = 0.55 + line.confidence * 0.22
                if (positivePriceWords.any(context::contains)) score += 0.15
                if (originalPriceWords.any(context::contains)) score -= 0.30
                score += ((line.box.height / medianHeight - 1.0) * 0.08).coerceIn(-0.05, 0.18)
                if (line.box.centerY in (document.height * 0.15).toInt()..(document.height * 0.90).toInt()) score += 0.08
                PriceCandidate(cents, line, score.coerceIn(0.0, 1.0), originalPriceWords.any(context::contains))
            }.toList()
        }
        val current = candidates.filterNot { it.isOriginal }.maxByOrNull { it.score }
            ?: return null
        val original = candidates
            .filter { it.isOriginal && it.cents >= current.cents }
            .maxByOrNull { it.score }
        val fragments = lines.map { it.copy(text = ProductText.cleanTitle(it.text)) }.filter { line ->
            abs(line.box.centerY - current.line.box.centerY) <= document.height * 0.40 &&
                line.box.top > document.height * 0.05 && line.box.bottom < document.height * 0.93 &&
                line.text.length in 2..180 &&
                moneyRegex.find(line.text) == null &&
                titleNoise.none(line.text::contains) &&
                listOf("先用后付", "好评率", "收藏", "本店已拼", "快抢光", "拼单即将", "人已拼", "颜色款式").none(line.text::contains) &&
                line.text.any { it.isLetter() }
        }
        // Build whole-title candidates before scoring: price proximity is only one signal.
        val titleCandidates = fragments.flatMap { first ->
            val candidates = mutableListOf(first)
            var joined = first
            repeat(2) {
                val next = fragments.filter { line ->
                    line.box.top >= joined.box.bottom &&
                        line.box.top - joined.box.bottom <= max(first.box.height, 24) * 0.9 &&
                        abs(line.box.left - first.box.left) <= document.width * 0.25 &&
                        line.box.height.toDouble() / first.box.height.coerceAtLeast(1) in 0.6..1.5
                }.minByOrNull { it.box.top }
                if (next != null) {
                    joined = OcrLine(joined.text + " " + next.text,
                        OcrBox(minOf(joined.box.left, next.box.left), joined.box.top, maxOf(joined.box.right, next.box.right), next.box.bottom),
                        minOf(joined.confidence, next.confidence))
                    candidates.add(joined)
                }
            }
            candidates
        }.filter { ProductText.inferType(it.text) != null || modelRegex.containsMatchIn(it.text) || it.text.length >= 10 }
        val title = titleCandidates.maxByOrNull { line ->
            val proximity = 1.0 - (abs(current.line.box.centerY - line.box.centerY).toDouble() / (document.height * 0.40))
            line.confidence * 0.35 + proximity * 0.35 +
                (if (ProductText.inferType(line.text) != null) 1.0 else 0.0) +
                (if (modelRegex.containsMatchIn(line.text)) 0.35 else 0.0) +
                line.text.length.coerceAtMost(80) * 0.004
        }
        val chosenTitle = title ?: return null
        val rawTitle = OcrReadingOrder.rows(document).map { it.line }.filter {
            it.box.top >= chosenTitle.box.top && it.box.bottom <= chosenTitle.box.bottom
        }.joinToString(" ") { it.text }
        val brand = Regex("【([^】]{2,30})】").find(rawTitle)?.groupValues?.get(1)
        val name = chosenTitle.text.replace(Regex("【[^】]+】"), "").trim().take(100)
        val model = modelRegex.findAll(name).firstOrNull { !Regex("(?i)\\d+(?:GB|TB|ML|KG)").matches(it.value) }?.value
        val type = ProductText.inferType(name)
        val attributes = buildList {
            model?.let { add(ProductAttribute("model", "型号", it, AttributeScope.PRODUCT)) }
            Regex("(?i)S925|S999|纯棉|全棉|真皮").find(name)?.value?.let {
                add(ProductAttribute("material", "材质", it, AttributeScope.PRODUCT))
            }
            val capacities = Regex("(?i)\\d+\\s*(?:GB|TB)").findAll(name).map { it.value }.distinct().toList()
            capacities.forEach {
                add(ProductAttribute("storage_capacity", "存储容量", it,
                    if (capacities.size == 1) AttributeScope.PRODUCT else AttributeScope.MENTIONED))
            }
        }
        val cleanName = attributes.filter { it.scope == AttributeScope.MENTIONED }
            .fold(name) { result, attr -> result.replace(attr.value, "") }.replace(Regex("\\s+"), " ").trim()
        val closeToPrice = abs(chosenTitle.box.centerY - current.line.box.centerY) < document.height * 0.25
        val productConfidence = (
            chosenTitle.confidence * 0.55 +
                (if (closeToPrice) 0.20 else 0.0) +
                (if (model != null) 0.20 else 0.05)
            ).coerceIn(0.0, 1.0)
        val allText = document.lines.joinToString(" ") { it.text }
        val labels = buildList {
            if (listOf("限时", "倒计时", "今日结束").any(allText::contains)) add("限时")
            if (listOf("仅剩", "最后", "库存紧张", "抢完").any(allText::contains)) add("稀缺")
            if (listOf("折", "优惠", "立减", "券").any(allText::contains)) add("折扣")
        }
        return SceneContext(
            product = Product(cleanName, categoryFor(name), brand, model, rawTitle, type, attributes),
            price = PriceInfo(current.cents, original?.cents,
                type = if (current.line.text.contains("拼单") || current.line.text.contains("发起拼")) PagePriceType.GROUP_BUY
                    else if (current.line.text.contains("券后")) PagePriceType.COUPON else PagePriceType.DISPLAYED),
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

    private fun priceRows(lines: List<OcrLine>): List<OcrLine> {
        val used = mutableSetOf<Int>()
        val merged = mutableListOf<OcrLine>()
        for ((index, anchor) in lines.withIndex()) {
            if (index in used || moneyRegex.containsMatchIn(anchor.text)) continue
            if (!anchor.text.endsWith("¥") && !anchor.text.endsWith("￥")) continue
            var row = anchor
            val parts = mutableListOf(index)
            repeat(2) {
                val next = lines.withIndex().filter { (i, line) ->
                    i !in used && i !in parts &&
                        line.box.left >= row.box.right &&
                        line.box.left - row.box.right <= max(row.box.height, line.box.height) * 1.5 &&
                        line.box.top < row.box.bottom && line.box.bottom > row.box.top &&
                        (if (parts.size == 1) Regex("[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?")
                         else Regex("\\.[0-9]{1,2}")).matches(line.text)
                }.minByOrNull { it.value.box.left }
                if (next != null) {
                    val line = next.value
                    parts.add(next.index)
                    row = OcrLine(row.text + line.text,
                        OcrBox(row.box.left, minOf(row.box.top, line.box.top), line.box.right, max(row.box.bottom, line.box.bottom)),
                        minOf(row.confidence, line.confidence))
                }
            }
            if (parts.size > 1) { used.addAll(parts); merged.add(row) }
        }
        return lines.filterIndexed { index, _ -> index !in used } + merged
    }

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
        listOf("衣", "鞋", "包", "美妆", "戒指", "项链", "手镯", "耳环").any(text::contains) -> "fashion"
        else -> "other"
    }

    private data class PriceCandidate(
        val cents: Long,
        val line: OcrLine,
        val score: Double,
        val isOriginal: Boolean,
    )
}
