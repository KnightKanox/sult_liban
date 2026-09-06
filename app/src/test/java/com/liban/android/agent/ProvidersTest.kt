package com.liban.android.agent

import com.liban.android.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProvidersTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().callTimeout(500, TimeUnit.MILLISECONDS).build()

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun zhipuSearchUsesDocumentedContract() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"search_result":[{"title":"Sony WH-1000XM6","content":"售价 ¥2999","link":"https://shop.example/item","media":"测试"}]}"""
        ))
        val hits = search().search("Sony WH-1000XM6 售价 价格 购买")
        val request = server.takeRequest()
        assertEquals("/paas/v4/web_search", request.path)
        assertTrue(request.body.readUtf8().contains("search_pro"))
        assertEquals(1, hits.size)
        assertEquals("https://shop.example/item", hits.first().url)
    }

    @Test
    fun zhipuReaderUsesDocumentedContract() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"reader_result":{"title":"详情","content":"售价 ¥2999","url":"https://shop.example/item"}}"""
        ))
        val page = search().read("https://shop.example/item")
        assertEquals("售价 ¥2999", page.content)
        assertEquals("/paas/v4/reader", server.takeRequest().path)
    }

    @Test
    fun zhipuHttpStatusIsPreserved() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val error = runCatching { search().search("test") }.exceptionOrNull()
        assertEquals(429, (error as ProviderHttpException).status)
    }

    @Test
    fun malformedEstimateRetriesOnce() = runTest {
        server.enqueue(chatResponse("not json"))
        server.enqueue(chatResponse("""{"status":"KNOWN","low_cents":250000,"high_cents":300000,"confidence":0.8,"rationale":"常见价格区间"}"""))
        val result = estimator().estimate(input())
        assertEquals(PriceEstimateStatus.KNOWN, result.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unknownEstimateHasNoRange() = runTest {
        server.enqueue(chatResponse("""{"status":"UNKNOWN","low_cents":1,"high_cents":2,"confidence":0.1,"rationale":"资料不足"}"""))
        val result = estimator().estimate(input())
        assertNull(result.lowCents)
        assertNull(result.highCents)
    }

    @Test
    fun llmRequestContainsNoImageOrBase64() = runTest {
        server.enqueue(chatResponse("""{"status":"UNKNOWN","confidence":0.1,"rationale":"资料不足"}"""))
        estimator().estimate(input())
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("image_url"))
        assertFalse(body.contains("base64", ignoreCase = true))
        assertTrue(body.contains("page_price_cents"))
    }

    @Test
    fun forgedLinkFailsAfterOneRetry() = runTest {
        val response = chatResponse("""{"status":"KNOWN","low_cents":250000,"high_cents":300000,"confidence":0.8,"rationale":"见 https://fake.example"}""")
        server.enqueue(response)
        server.enqueue(response.clone())
        val error = runCatching { estimator().estimate(input()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(2, server.requestCount)
    }

    private fun search() = ZhipuSearchProvider(
        client, json, server.url("/").toString().trimEnd('/'), "test-key", "search_pro"
    )

    private fun estimator() = OpenAiCompatiblePriceEstimator(
        client, json, server.url("/v1/chat/completions").toString(), "test-key", "test-model"
    )

    private fun input() = PriceEstimateInput(
        Product("Sony WH-1000XM6", "electronics", model = "WH-1000XM6"),
        2_999_00,
    )

    private fun chatResponse(content: String): MockResponse {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"$escaped"}}]}""")
    }
}
