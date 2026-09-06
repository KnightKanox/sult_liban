package com.liban.android.agent

import com.liban.android.config.isAllowedEndpoint
import com.liban.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

interface SearchProvider {
    suspend fun search(query: String): List<SearchHit>
    suspend fun read(url: String): PageDocument
}

interface PriceEstimator {
    suspend fun estimate(input: PriceEstimateInput): PriceEstimate
}

class ProviderHttpException(val status: Int, message: String) : Exception(message)

@Serializable
private data class ZhipuSearchRequest(
    @SerialName("search_query") val query: String,
    @SerialName("search_engine") val engine: String,
    @SerialName("search_intent") val searchIntent: Boolean = false,
    val count: Int = 10,
    @SerialName("content_size") val contentSize: String = "high",
)

@Serializable
private data class ZhipuSearchResponse(
    @SerialName("search_result") val results: List<ZhipuSearchItem> = emptyList(),
)

@Serializable
private data class ZhipuSearchItem(
    val title: String = "",
    val content: String = "",
    val link: String = "",
    val media: String? = null,
)

@Serializable
private data class ZhipuReaderRequest(
    val url: String,
    val timeout: Int = 1,
    @SerialName("no_cache") val noCache: Boolean = false,
    @SerialName("return_format") val returnFormat: String = "text",
    @SerialName("retain_images") val retainImages: Boolean = false,
)

@Serializable
private data class ZhipuReaderResponse(
    @SerialName("reader_result") val result: ZhipuReaderItem? = null,
)

@Serializable
private data class ZhipuReaderItem(
    val title: String = "",
    val content: String = "",
    val url: String = "",
)

class ZhipuSearchProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val apiKey: String,
    private val engine: String = "search_pro",
) : SearchProvider {
    init {
        require(baseUrl.isAllowedEndpoint()) { "智谱 Base URL 必须使用 HTTPS 或本机地址" }
        require(apiKey.isNotBlank()) { "智谱 API Key 为空" }
    }

    override suspend fun search(query: String): List<SearchHit> {
        val body = json.encodeToString(
            ZhipuSearchRequest.serializer(),
            ZhipuSearchRequest(query.take(70), engine.ifBlank { "search_pro" }),
        )
        val response = post("/paas/v4/web_search", body)
        return json.decodeFromString(ZhipuSearchResponse.serializer(), response).results
            .filter { it.link.startsWith("http") }
            .map { SearchHit(it.title, it.content, it.link, it.media) }
    }

    override suspend fun read(url: String): PageDocument {
        require(url.startsWith("https://") || url.startsWith("http://")) { "网页 URL 无效" }
        val body = json.encodeToString(ZhipuReaderRequest.serializer(), ZhipuReaderRequest(url))
        val parsed = json.decodeFromString(ZhipuReaderResponse.serializer(), post("/paas/v4/reader", body)).result
            ?: error("智谱网页阅读响应缺少 reader_result")
        return PageDocument(parsed.title, parsed.content, parsed.url.ifBlank { url })
    }

    private suspend fun post(path: String, payload: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "智谱 HTTP ${response.code}")
            body
        }
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
    val temperature: Double = 0.1,
)

@Serializable private data class ResponseFormat(val type: String = "json_object")
@Serializable private data class ChatMessage(val role: String, val content: String)
@Serializable private data class ChatResponse(val choices: List<ChatChoice> = emptyList())
@Serializable private data class ChatChoice(val message: ChatResponseMessage)
@Serializable private data class ChatResponseMessage(val content: String)

class OpenAiCompatiblePriceEstimator(
    private val client: OkHttpClient,
    private val json: Json,
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
) : PriceEstimator {
    init {
        require(endpoint.isAllowedEndpoint()) { "LLM Endpoint 必须使用 HTTPS 或本机地址" }
        require(apiKey.isNotBlank() && model.isNotBlank()) { "LLM 配置不完整" }
    }

    override suspend fun estimate(input: PriceEstimateInput): PriceEstimate {
        val system = """
            你是商品价格区间估算器，只输出 JSON。
            格式：{"status":"KNOWN|UNKNOWN","low_cents":整数或null,"high_cents":整数或null,
            "confidence":0到1,"rationale":"不超过60字"}。
            金额只能是人民币分。资料不足就返回 UNKNOWN。禁止输出商家、链接、当前最低价。
        """.trimIndent()
        val user = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("product", json.encodeToJsonElement(Product.serializer(), input.product))
                put("page_price_cents", kotlinx.serialization.json.JsonPrimitive(input.pagePriceCents))
                put(
                    "search_evidence",
                    kotlinx.serialization.json.buildJsonArray {
                        input.evidence.take(3).forEach { hit ->
                            // Deliberately omit URLs so the model cannot present invented merchants or links.
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("title", kotlinx.serialization.json.JsonPrimitive(hit.title.take(120)))
                                put("summary", kotlinx.serialization.json.JsonPrimitive(hit.content.take(400)))
                            })
                        }
                    },
                )
            },
        )
        var last: Throwable? = null
        repeat(2) {
            try {
                return validate(json.decodeFromString(PriceEstimate.serializer(), extractJson(call(system, user))))
            } catch (error: ProviderHttpException) {
                throw error
            } catch (error: Throwable) {
                last = error
            }
        }
        throw IllegalStateException("LLM 估价 JSON 解析失败", last)
    }

    private suspend fun call(system: String, user: String): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model, listOf(ChatMessage("system", system), ChatMessage("user", user))),
        )
        require(!payload.contains("image_url") && !payload.contains("base64", ignoreCase = true)) {
            "LLM 请求不得包含图片"
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ProviderHttpException(response.code, "LLM HTTP ${response.code}")
            json.decodeFromString(ChatResponse.serializer(), body).choices.firstOrNull()?.message?.content
                ?: error("LLM 响应缺少 choices[0].message.content")
        }
    }

    private fun validate(value: PriceEstimate): PriceEstimate {
        require(value.confidence in 0.0..1.0) { "估价置信度越界" }
        require(!value.rationale.contains("http", true) && !value.rationale.contains("www.", true)) {
            "模型估价不得包含链接"
        }
        if (value.status == PriceEstimateStatus.UNKNOWN) {
            return value.copy(lowCents = null, highCents = null)
        }
        val low = requireNotNull(value.lowCents)
        val high = requireNotNull(value.highCents)
        require(low in 100..100_000_000 && high in low..100_000_000) { "估价金额越界" }
        return value
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "响应中没有 JSON 对象" }
        return trimmed.substring(start, end + 1)
    }
}
