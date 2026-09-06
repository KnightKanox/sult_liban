package com.liban.android.agent

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class PriceEvidenceAnalyzerTest {
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

    private fun hit(text: String, url: String) = SearchHit(text, text, url)
    private fun sample(cents: Long, domain: String) = PriceSample(cents, domain, PriceReference("商品", "https://$domain/p"))
}
