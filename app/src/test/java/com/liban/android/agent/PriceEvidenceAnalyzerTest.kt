package com.liban.android.agent

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class PriceEvidenceAnalyzerTest {
    @Test fun structuralQueryUsesSelectedAttributesNotMarketingOrUnselectedCapacity() {
        val phone = Product("手机送女友生日礼物", "electronics", "苹果", "iPhone16", productType = "手机",
            attributes = listOf(
                ProductAttribute("storage_capacity", "容量", "256GB", AttributeScope.SELECTED),
                ProductAttribute("storage_capacity", "容量", "128GB", AttributeScope.MENTIONED)))
        val query = PriceEvidenceAnalyzer.buildQuery(phone)
        assertTrue(query.contains("iPhone16") && query.contains("256GB"))
        assertFalse(query.contains("128GB") || query.contains("送女友"))
    }

    @Test fun nonModelRingResultsRemainSimilarEvenWithMatchingAttributes() {
        val ring = Product("莫比乌斯银戒指", "fashion", "中国黄金", productType = "戒指",
            attributes = listOf(ProductAttribute("style", "款式", "莫比乌斯", AttributeScope.PRODUCT),
                ProductAttribute("material", "材质", "S925", AttributeScope.PRODUCT)))
        val hits = listOf(hit("中国黄金 莫比乌斯 S925 戒指 ¥99", "https://a.example/p"))
        assertEquals(SearchMatchKind.SIMILAR, PriceEvidenceAnalyzer.classifyHits(ring, hits).single().matchKind)
        assertTrue(PriceEvidenceAnalyzer.extract(ring, hits).isEmpty())
        assertTrue(PriceEvidenceAnalyzer.buildQuery(ring).contains("莫比乌斯"))
    }

    @Test fun conflictingAndUnboundSpecificationsCannotBeVerified() {
        val phone = Product("ABC X1000 手机", "electronics", model = "X1000", productType = "手机",
            attributes = listOf(ProductAttribute("storage_capacity", "容量", "256GB", AttributeScope.SELECTED)))
        val other = listOf(hit("ABC X1000 手机 128GB 售价 ¥999", "https://a.example/p"))
        val right = listOf(hit("ABC X1000 手机 256GB 售价 ¥1999", "https://b.example/p"))
        assertTrue(PriceEvidenceAnalyzer.relevantHits(phone, other).isEmpty())
        assertEquals(1, PriceEvidenceAnalyzer.relevantHits(phone, right).size)
        assertTrue(PriceEvidenceAnalyzer.extract(phone, right, pagePrice = PriceInfo(199900)).isEmpty())
        assertEquals(1, PriceEvidenceAnalyzer.extract(phone, right,
            pagePrice = PriceInfo(199900, variantBinding = AttributeScope.SELECTED)).size)
        assertTrue(PriceEvidenceAnalyzer.extract(phone.copy(attributes = phone.attributes.map { it.copy(scope = AttributeScope.UNKNOWN) }), right).isEmpty())
    }

    @Test fun capacitySubstringCannotMatchAnotherCapacity() {
        val phone = Product("X1000 手机", "electronics", model = "X1000",
            attributes = listOf(ProductAttribute("storage_capacity", "容量", "128GB", AttributeScope.SELECTED)))
        assertTrue(PriceEvidenceAnalyzer.relevantHits(phone, listOf(hit("X1000 1128GB 售价 ¥99", "https://a.example/p"))).isEmpty())
    }

    private val product = Product("Sony WH-1000XM6 无线耳机", "electronics", model = "WH-1000XM6")

    @Test
    fun filtersAccessoriesSecondhandAndWrongModel() {
        val hits = listOf(
            hit("新品 Sony WH-1000XM6 售价 ¥2999", "https://a.example/p"),
            hit("Sony WH-1000XM6 保护壳 ¥99", "https://b.example/p"),
            hit("二手 Sony WH-1000XM6 ¥1800", "https://c.example/p"),
            hit("Sony WH-1000XM5 售价 ¥1999", "https://d.example/p"),
        )
        val values = PriceEvidenceAnalyzer.extract(product, hits)
        assertEquals(listOf(299_900L), values.map { it.cents })
    }

    @Test
    fun requiresThreeIndependentDomainsAndRemovesOutlier() {
        val samples = listOf(
            sample(280_000, "a.example"),
            sample(290_000, "b.example"),
            sample(300_000, "c.example"),
            sample(990_000, "outlier.example"),
        )
        val result = PriceEvidenceAnalyzer.verifiedComparison(320_000, samples, 10)!!
        assertEquals(PriceEvidenceSource.SEARCH_VERIFIED, result.evidenceSource)
        assertTrue(result.referenceHighCents!! < 500_000)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun duplicateDomainDoesNotMeetVerificationThreshold() {
        val samples = listOf(sample(280_000, "a.example"), sample(290_000, "a.example"), sample(300_000, "b.example"))
        assertNull(PriceEvidenceAnalyzer.verifiedComparison(320_000, samples, 10))
    }

    @Test fun queryIsBoundedToSeventyCharacters() {
        assertTrue(PriceEvidenceAnalyzer.buildQuery(product.copy(name = "很长商品名".repeat(30))).length <= 70)
    }

    @Test fun promotionPageKeepsCurrentPriceButRejectsOriginalAndDiscount() {
        val samples = PriceEvidenceAnalyzer.extract(product, listOf(
            SearchHit("Sony WH-1000XM6 限时优惠", "原价3999元，立减500元，券后到手价2499元", "https://shop.example/p")
        ))
        assertEquals(listOf(249900L), samples.map { it.cents })
    }

    @Test fun subdomainsCountAsOneIndependentSource() {
        val samples = PriceEvidenceAnalyzer.extract(product, listOf(
            hit("WH-1000XM6 售价2499元", "https://m.zol.com.cn/a"),
            hit("WH-1000XM6 售价2399元", "https://dcdv.zol.com.cn/b"),
            hit("WH-1000XM6 售价2299元", "https://news.zol.com.cn/c"),
        ))
        assertEquals(setOf("zol.com.cn"), samples.map { it.domain }.toSet())
        assertNull(PriceEvidenceAnalyzer.verifiedComparison(299900, samples, 10))
    }

    @Test fun identicalPricesFromThreeSitesCanBeVerified() {
        assertNotNull(PriceEvidenceAnalyzer.verifiedComparison(299900,
            listOf(sample(249900, "a.example"), sample(249900, "b.example"), sample(249900, "c.example")), 10))
    }

    @Test fun similarModelAndStalePricesAreRejected() {
        val now = java.time.Instant.parse("2026-09-06T00:00:00Z").toEpochMilli()
        val hits = listOf(
            hit("Sony WH-1000XM60 售价2999元", "https://wrong.example/p"),
            hit("Sony WF-1000XM6 售价2999元", "https://wrong2.example/p"),
            SearchHit("WH-1000XM6", "售价2999元", "https://old.example/p", publishDate = "2025-09-06"),
        )
        assertTrue(PriceEvidenceAnalyzer.extract(product, hits, now).isEmpty())
    }

    private fun hit(text: String, url: String) = SearchHit(text, text, url)
    private fun sample(cents: Long, domain: String) = PriceSample(cents, domain, PriceReference("商品", "https://$domain/p"))
}
