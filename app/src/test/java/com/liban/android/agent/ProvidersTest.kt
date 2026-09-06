package com.liban.android.agent

import com.liban.android.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProvidersTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun malformedPerceptionIsRetriedOnce() = runTest {
        server.enqueue(chatResponse("not json"))
        server.enqueue(chatResponse(validSceneJson()))
        val provider = llm()

        val scene = provider.perceive(byteArrayOf(1, 2, 3))

        assertEquals("测试商品", scene.product.name)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun invalidPerceptionSchemaIsRetriedOnce() = runTest {
        server.enqueue(chatResponse(validSceneJson().replace("19900", "-1")))
        server.enqueue(chatResponse(validSceneJson()))

        assertEquals(19_900, llm().perceive(byteArrayOf()).price.currentCents)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun httpErrorIsReportedWithoutParsingRetry() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val error = runCatching { llm().perceive(byteArrayOf()) }.exceptionOrNull()

        assertTrue(error is ProviderHttpException)
        assertEquals(401, (error as ProviderHttpException).status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rateLimitStatusIsPreserved() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

        val error = runCatching { llm().perceive(byteArrayOf()) }.exceptionOrNull()

        assertEquals(429, (error as ProviderHttpException).status)
    }

    @Test
    fun blankDecisionIdIsReplacedAndDisplayIsBounded() = runTest {
        server.enqueue(
            chatResponse(
                """{"decision_id":"","risk_score":0.8,"risk_level":"HIGH","recommendation":"DELAY","delay_hours":24,"factors":["a"],"display":{"title":"等等","summary":"有压力","key_points":["1","2","3","4","5"]},"source_mode":"LIVE"}"""
            )
        )

        val decision = llm().decide(scene(), SkillResults())

        assertTrue(decision.decisionId.isNotBlank())
        assertEquals(4, decision.display.keyPoints.size)
    }

    @Test
    fun invalidDecisionFailsAfterExactlyOneRetry() = runTest {
        val invalid = """{"risk_score":2.0,"risk_level":"HIGH","recommendation":"DELAY","factors":[],"display":{"title":"等等","summary":"有压力","key_points":[]}}"""
        server.enqueue(chatResponse(invalid))
        server.enqueue(chatResponse(invalid))

        val error = runCatching { llm().decide(scene(), SkillResults()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun pricePremiumIsRecomputedFromTrustedPagePrice() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"availability":"AVAILABLE","reference_low_cents":15000,"reference_high_cents":18000,"page_price_cents":1,"premium_ratio":-9.0,"source":"FALLBACK"}"""
            )
        )

        val result = price().compare(Product("测试商品", "other"), Money(19_800))

        assertEquals(19_800, result.pagePriceCents)
        assertEquals(0.1, result.premiumRatio!!, 0.0001)
        assertEquals(SourceMode.LIVE, result.source)
    }

    @Test
    fun unavailablePriceDoesNotPretendToHaveARange() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"availability":"UNAVAILABLE","reference_low_cents":1,"reference_high_cents":2,"page_price_cents":1,"premium_ratio":1.0}"""
            )
        )

        val result = price().compare(Product("无结果", "other"), Money(500))

        assertNull(result.referenceLowCents)
        assertNull(result.referenceHighCents)
        assertNull(result.premiumRatio)
    }

    @Test
    fun timeoutIsSurfacedToTheOrchestrator() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = runCatching { llm().perceive(byteArrayOf()) }.exceptionOrNull()

        assertNotNull(error)
    }

    private fun llm() = OpenAiCompatibleLlmProvider(
        client = client,
        json = json,
        endpoint = server.url("/v1/chat/completions").toString(),
        apiKey = "test-key",
        model = "test-model",
    )

    private fun price() = ConfigurablePriceProvider(
        client = client,
        json = json,
        endpoint = server.url("/price").toString(),
        apiKey = "test-key",
    )

    private fun scene() = SceneContext(
        product = Product("测试商品", "other"),
        price = PriceInfo(19_900),
        confidence = 0.9,
    )

    private fun validSceneJson() =
        """{"scene_type":"ecommerce_product","product":{"name":"测试商品","category":"other"},"price":{"current_cents":19900,"currency":"CNY"},"signals":{},"required_skills":["budget_check"],"confidence":0.9}"""

    private fun chatResponse(content: String): MockResponse {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
        return MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"$escaped"}}]}""")
    }
}
