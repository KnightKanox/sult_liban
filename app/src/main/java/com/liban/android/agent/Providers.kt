package com.liban.android.agent

import com.liban.android.BuildConfig
import com.liban.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

interface LlmProvider {
    suspend fun perceive(image: ByteArray): SceneContext
    suspend fun decide(scene: SceneContext, skills: SkillResults): DecisionResult
}

interface PriceProvider {
    suspend fun compare(product: Product, pagePrice: Money): PriceComparison
}

class ProviderHttpException(val status: Int, message: String) : Exception(message)

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val response_format: ResponseFormat = ResponseFormat(),
    val temperature: Double = 0.1,
)

@Serializable
private data class ResponseFormat(val type: String = "json_object")

@Serializable
private data class ChatMessage(val role: String, val content: kotlinx.serialization.json.JsonElement)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice>)

@Serializable
private data class ChatChoice(val message: ChatResponseMessage)

@Serializable
private data class ChatResponseMessage(val content: String)

class OpenAiCompatibleLlmProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: String = BuildConfig.LLM_BASE_URL,
    private val apiKey: String = BuildConfig.LLM_API_KEY,
    private val model: String = BuildConfig.LLM_MODEL,
) : LlmProvider {
    val configured: Boolean get() = endpoint.isAllowedEndpoint() && apiKey.isNotBlank() && model.isNotBlank()

    override suspend fun perceive(image: ByteArray): SceneContext {
        check(configured) { "LLM 服务尚未配置" }
        val system = """你是消费页面识别器。只输出 JSON，金额必须为人民币分。
            格式：{"scene_type":"ecommerce_product","product":{"name":"","category":""},
            "price":{"current_cents":0,"original_cents":null,"currency":"CNY"},
            "signals":{"discount":false,"limited_time":false,"scarcity":false,"labels":[]},
            "required_skills":["budget_check"],"confidence":0.0}。
            required_skills 只允许 budget_check, goal_impact, history_check, impulse_check, price_compare。""".trimIndent()
        val content = json.parseToJsonElement(
            """[{"type":"text","text":"识别这张消费页面截图"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,${Base64.getEncoder().encodeToString(image)}"}}]"""
        )
        return decodeWithOneRetry(SceneContext.serializer(), ::validateScene) {
            call(listOf(message("system", system), ChatMessage("user", content)))
        }
    }

    override suspend fun decide(scene: SceneContext, skills: SkillResults): DecisionResult {
        check(configured) { "LLM 服务尚未配置" }
        val system = """你是谨慎的消费决策助手。只输出 JSON，不得添加 Markdown。
            格式：{"decision_id":"可留空","risk_score":0.0,"risk_level":"LOW|MEDIUM|HIGH",
            "recommendation":"BUY|DELAY|CANCEL","delay_hours":null,"factors":[],
            "display":{"title":"","summary":"","key_points":[]},"source_mode":"LIVE"}。
            HIGH 风险优先建议 DELAY，理由必须来自输入。""".trimIndent()
        val user = "SceneContext=${json.encodeToString(SceneContext.serializer(), scene)}\n" +
            "SkillResults=${json.encodeToString(SkillResults.serializer(), skills)}"
        return decodeWithOneRetry(DecisionResult.serializer(), ::validateDecision) {
            call(listOf(message("system", system), message("user", user)))
        }.copy(sourceMode = SourceMode.LIVE)
    }

    private fun message(role: String, text: String) =
        ChatMessage(role, kotlinx.serialization.json.JsonPrimitive(text))

    private suspend fun call(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages))
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "LLM HTTP ${response.code}")
            json.decodeFromString(ChatResponse.serializer(), body).choices.firstOrNull()?.message?.content
                ?: error("LLM 响应缺少 choices[0].message.content")
        }
    }

    private suspend fun <T> decodeWithOneRetry(
        serializer: kotlinx.serialization.KSerializer<T>,
        validate: (T) -> T,
        call: suspend () -> String,
    ): T {
        var last: Throwable? = null
        repeat(2) {
            try {
                return validate(json.decodeFromString(serializer, extractJson(call())))
            } catch (error: ProviderHttpException) {
                throw error
            } catch (error: Throwable) {
                last = error
            }
        }
        throw IllegalStateException("LLM JSON 解析失败", last)
    }

    private fun validateScene(scene: SceneContext): SceneContext {
        require(scene.product.name.isNotBlank()) { "商品名称为空" }
        require(scene.product.category.isNotBlank()) { "商品分类为空" }
        require(scene.price.currentCents >= 0) { "商品价格不能为负数" }
        require(scene.price.originalCents == null || scene.price.originalCents >= 0) { "原价不能为负数" }
        require(scene.price.currency == "CNY") { "仅支持人民币价格" }
        require(scene.confidence in 0.0..1.0) { "置信度必须在 0 到 1 之间" }
        return scene.copy(requiredSkills = scene.requiredSkills.distinct())
    }

    private fun validateDecision(decision: DecisionResult): DecisionResult {
        require(decision.riskScore in 0.0..1.0) { "风险分必须在 0 到 1 之间" }
        require(decision.display.title.isNotBlank()) { "决策标题为空" }
        require(decision.display.summary.isNotBlank()) { "决策摘要为空" }
        require(decision.delayHours == null || decision.delayHours >= 0) { "等待时长不能为负数" }
        return decision.copy(
            decisionId = decision.decisionId.ifBlank { UUID.randomUUID().toString() },
            factors = decision.factors.take(8),
            display = decision.display.copy(keyPoints = decision.display.keyPoints.take(4)),
        )
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "响应中没有 JSON 对象" }
        return trimmed.substring(start, end + 1)
    }
}

class ConfigurablePriceProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: String = BuildConfig.PRICE_BASE_URL,
    private val apiKey: String = BuildConfig.PRICE_API_KEY,
) : PriceProvider {
    val configured: Boolean get() = endpoint.isAllowedEndpoint() && apiKey.isNotBlank()

    override suspend fun compare(product: Product, pagePrice: Money): PriceComparison = withContext(Dispatchers.IO) {
        check(configured) { "物价服务尚未配置" }
        val separator = if ('?' in endpoint) '&' else '?'
        val query = URLEncoder.encode(product.name, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url("$endpoint${separator}q=$query&page_price_cents=${pagePrice.cents}")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "Price HTTP ${response.code}")
            val parsed = json.decodeFromString(PriceComparison.serializer(), body)
            if (parsed.availability != Availability.AVAILABLE) {
                return@use parsed.copy(
                    referenceLowCents = null,
                    referenceHighCents = null,
                    pagePriceCents = pagePrice.cents,
                    premiumRatio = null,
                    source = SourceMode.LIVE,
                )
            }
            val low = requireNotNull(parsed.referenceLowCents) { "价格结果缺少区间下限" }
            val high = requireNotNull(parsed.referenceHighCents) { "价格结果缺少区间上限" }
            require(low >= 0 && high >= low) { "价格参考区间无效" }
            parsed.copy(
                pagePriceCents = pagePrice.cents,
                premiumRatio = (pagePrice.cents - high).toDouble() / high.coerceAtLeast(1),
                source = SourceMode.LIVE,
            )
        }
    }
}

private fun String.isAllowedEndpoint(): Boolean =
    startsWith("https://") || startsWith("http://127.0.0.1") || startsWith("http://localhost")
