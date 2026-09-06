package com.liban.android.agent

import com.liban.android.model.*
import com.liban.android.ocr.LlmSceneExtractor
import com.liban.android.ocr.ShoppingOcrFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in tests: environment keys only, synthetic OCR only, no credentials in reports. */
class LiveServicesTest {
    @Test fun extractsRingFromScreenshotTranscription() = runBlocking {
        val key = System.getenv("LIBAN_LIVE_LLM_KEY").orEmpty()
        assumeTrue("Live tests require explicit environment credentials", key.isNotBlank())
        val start = System.currentTimeMillis()
        val scene = withTimeout(5_000) {
            LlmSceneExtractor(client, json, "https://api.deepseek.com/v1/chat/completions", key, "deepseek-v4-flash")
                .extract(ShoppingOcrFixtures.ring)
        }!!
        assertTrue(scene.product.name.contains("莫比乌斯") && scene.product.name.contains("戒指"))
        assertEquals("中国黄金", scene.product.brand)
        assertEquals(9900L, scene.price.currentCents)
        assertEquals(PagePriceType.GROUP_BUY, scene.price.type)
        println("LIVE_RING: grounded name, attributes and price accepted; elapsed_ms=${System.currentTimeMillis() - start}")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Test fun extractsProductFromSyntheticOcr() = runBlocking {
        val key = System.getenv("LIBAN_LIVE_LLM_KEY").orEmpty()
        assumeTrue("Live tests require explicit environment credentials", key.isNotBlank())
        val document = OcrDocument(1080, 2400, listOf(
            OcrLine("首页 客服 购物车", OcrBox(100, 100, 900, 150), 0.99),
            OcrLine("原价 ￥3499", OcrBox(100, 700, 600, 740), 0.99),
            OcrLine("到手价 ￥2999", OcrBox(100, 800, 650, 870), 0.99),
            OcrLine("索尼 WH-1000XM6 无线降噪耳机", OcrBox(100, 930, 950, 980), 0.99),
            OcrLine("立减 ￥500", OcrBox(100, 1100, 600, 1150), 0.99),
        ))
        val start = System.currentTimeMillis()
        val scene = withTimeout(5_000) {
            LlmSceneExtractor(client, json, "https://api.deepseek.com/v1/chat/completions", key, "deepseek-v4-flash").extract(document)
        }
        assertNotNull(scene)
        println("LIVE_OCR_FIELDS: ${scene!!.product}")
        assertEquals(299900L, scene.price.currentCents)
        assertEquals("WH-1000XM6", scene.product.model)
        assertTrue(scene.product.name.contains("WH-1000XM6"))
        println("LIVE_OCR: grounded product and price accepted; elapsed_ms=${System.currentTimeMillis() - start}")
    }

    @Test fun searchProducesTraceableProductEvidence() = runBlocking {
        val key = System.getenv("LIBAN_LIVE_SEARCH_KEY").orEmpty()
        assumeTrue("Live tests require explicit environment credentials", key.isNotBlank())
        val product = Product("索尼 WH-1000XM6 耳机", "electronics", model = "WH-1000XM6")
        val service = PriceSearch { engine -> ZhipuSearchProvider(client, json, "https://open.bigmodel.cn/api", key, engine) }
        val start = System.currentTimeMillis()
        val result = withTimeout(3_000) { service.search(product, "search_pro_quark") }
        val relevant = PriceEvidenceAnalyzer.relevantHits(product, result.hits)
        val samples = PriceEvidenceAnalyzer.extract(product, relevant)
        assertTrue(result.linkedCount > 0)
        assertTrue(relevant.isNotEmpty())
        println("LIVE_SEARCH: raw=${result.hits.size}, linked=${result.linkedCount}, relevant=${relevant.size}, price_domains=${samples.map { it.domain }.distinct().size}, elapsed_ms=${System.currentTimeMillis() - start}")
    }
}
