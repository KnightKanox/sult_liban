package com.liban.android.ocr

import com.liban.android.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class LlmSceneExtractorTest {
    private val document = OcrDocument(1080, 2400, listOf(
        line("索尼 WH-1000XM6 无线降噪耳机", 100, 800, 950, 845),
        line("到手价 ￥", 100, 900, 220, 950),
        line("2999.50", 225, 875, 500, 950),
        line("原价 ￥3499", 100, 1000, 600, 1040),
        line("立减 ￥500", 100, 1100, 600, 1140),
    ))
    private val extracted = ExtractedProduct(
        status = PriceEstimateStatus.KNOWN, name = "索尼 WH-1000XM6 无线降噪耳机",
        rawTitle = "索尼 WH-1000XM6 无线降噪耳机", productType = "耳机", brand = "索尼",
        category = "electronics", titleLineIds = listOf(0),
        attributes = listOf(ExtractedAttribute("model", "型号", "WH-1000XM6", AttributeScope.PRODUCT, listOf(0))),
        price = ExtractedPrice(299950, type = PagePriceType.DISPLAYED, lineIds = listOf(1, 2)),
        productConfidence = 0.95, priceConfidence = 0.95,
    )

    @Test fun acceptsGroundedTitleAndSplitPrice() {
        val scene = LlmSceneExtractor.validate(extracted, document)!!
        assertEquals(299950L, scene.price.currentCents)
        assertEquals("WH-1000XM6", scene.product.model)
        assertEquals("ocr_llm", scene.sceneType)
    }

    @Test fun acceptsGroundedFieldsEvenWithZeroConfidenceWithoutInflatingIt() {
        val scene = LlmSceneExtractor.validate(extracted.copy(productConfidence = 0.0, priceConfidence = 0.0), document)!!
        assertEquals(extracted.name, scene.product.name)
        assertEquals(0.0, scene.productConfidence, 0.0)
        assertEquals(0.0, scene.priceConfidence, 0.0)
    }

    @Test fun rejectsHallucinatedNameModelAndPrice() {
        assertInvalid(extracted.copy(name = "苹果手机"))
        assertInvalid(extracted.copy(attributes = listOf(extracted.attributes.first().copy(value = "WH-1000XM7"))))
        assertInvalid(extracted.copy(price = extracted.price!!.copy(amountCents = 199950)))
        assertInvalid(extracted.copy(price = extracted.price!!.copy(lineIds = listOf(99))))
    }

    @Test fun rejectsDiscountAndOriginalAsCurrentPrice() {
        assertInvalid(extracted.copy(price = ExtractedPrice(50000, lineIds = listOf(4))))
        assertInvalid(extracted.copy(price = ExtractedPrice(349900, lineIds = listOf(3))))
    }

    @Test fun preservesGroundedOriginalPrice() {
        assertEquals(349900L, LlmSceneExtractor.validate(extracted.copy(
            price = extracted.price!!.copy(originalCents = 349900, originalLineIds = listOf(3))), document)!!.price.originalCents)
    }

    @Test fun unknownDoesNotGuessAPrice() {
        assertNull(LlmSceneExtractor.validate(ExtractedProduct(PriceEstimateStatus.UNKNOWN), document))
    }

    @Test fun ringNameCanReorderSeparateEvidencedAttributes() {
        val scene = LlmSceneExtractor.validate(ShoppingOcrFixtures.ringExtraction, ShoppingOcrFixtures.ring)!!
        assertEquals("莫比乌斯 S925银戒指", scene.product.name)
        assertEquals("中国黄金", scene.product.brand)
        assertEquals("戒指", scene.product.productType)
        assertEquals(9900L, scene.price.currentCents)
        assertEquals(PagePriceType.GROUP_BUY, scene.price.type)
        assertEquals(listOf("发起拼单"), scene.price.conditions)
        assertEquals(AttributeScope.UNKNOWN, scene.price.variantBinding)
    }

    @Test fun ringCannotBeBrandOnlyOrUseDiscountAsPrice() {
        assertInvalid(ShoppingOcrFixtures.ringExtraction.copy(name = "中国黄金"), ShoppingOcrFixtures.ring)
        assertInvalid(ShoppingOcrFixtures.ringExtraction.copy(price = ExtractedPrice(14000, lineIds = listOf(3))), ShoppingOcrFixtures.ring)
        assertInvalid(ShoppingOcrFixtures.ringExtraction.copy(attributes = listOf(
            ExtractedAttribute("material", "材质", "铂金", AttributeScope.PRODUCT, listOf(5)))), ShoppingOcrFixtures.ring)
    }

    @Test fun repeatedPriceEvidenceDoesNotJoinAmountsAcrossDistantRows() {
        val value = ShoppingOcrFixtures.ringExtraction
        val scene = LlmSceneExtractor.validate(value.copy(price = value.price!!.copy(lineIds = listOf(2, 8))), ShoppingOcrFixtures.ring)!!
        assertEquals(9900L, scene.price.currentCents)
        assertInvalid(value.copy(price = value.price!!.copy(amountCents = 999900, lineIds = listOf(2, 8))), ShoppingOcrFixtures.ring)
    }

    @Test fun nameMayInterleaveGroundedMaterialPartsAndStyle() {
        val value = ShoppingOcrFixtures.ringExtraction.copy(name = "S925 莫比乌斯 托帕石 银戒指")
        assertEquals(value.name, LlmSceneExtractor.validate(value, ShoppingOcrFixtures.ring)!!.product.name)
    }

    @Test fun incompleteMaterialQuoteCanUseWordInSameEvidenceButCannotInventIt() {
        val original = ShoppingOcrFixtures.ringExtraction
        val value = original.copy(attributes = original.attributes.map {
            if (it.key == "material") it.copy(sourceParts = listOf("S925")) else it
        })
        assertEquals("S925银", LlmSceneExtractor.validate(value, ShoppingOcrFixtures.ring)!!.product.attributes[1].value)
        val noSilver = ShoppingOcrFixtures.ring.copy(lines = ShoppingOcrFixtures.ring.lines.map {
            it.copy(text = it.text.replace("珍尚银", "珍尚"))
        })
        assertInvalid(value.copy(rawTitle = value.rawTitle!!.replace("珍尚银", "珍尚")), noSilver)
        assertInvalid(original.copy(attributes = listOf(
            ExtractedAttribute("material", "材质", "黄金", AttributeScope.PRODUCT, listOf(4)))), ShoppingOcrFixtures.ring)
    }

    @Test fun multipleCapacitiesAreMentionedNotSelectedOrAddedToName() {
        val doc = OcrDocument(1080, 2400, listOf(
            line("苹果 iPhone 16 手机", 100, 900, 950, 950),
            line("可选容量 128GB 256GB", 100, 1000, 950, 1050),
            line("售价 ￥4999", 100, 1100, 600, 1160),
        ))
        val result = ExtractedProduct(PriceEstimateStatus.KNOWN, name = "苹果 iPhone 16 手机",
            rawTitle = doc.lines[0].text, productType = "手机", brand = "苹果", titleLineIds = listOf(0),
            attributes = listOf("128GB", "256GB").map { ExtractedAttribute("storage", "容量", it, AttributeScope.SELECTED, listOf(1)) },
            price = ExtractedPrice(499900, lineIds = listOf(2), variantBinding = AttributeScope.SELECTED))
        val scene = LlmSceneExtractor.validate(result, doc)!!
        assertTrue(scene.product.attributes.all { it.scope == AttributeScope.MENTIONED && it.key == "storage_capacity" })
        assertEquals(AttributeScope.UNKNOWN, scene.price.variantBinding)
        assertInvalid(result.copy(name = "苹果 iPhone 16 256GB 手机"), doc)
    }

    @Test fun selectedAttributeNeedsActualSelectionEvidenceAndPriceBinding() {
        val doc = OcrDocument(1080, 2400, listOf(
            line("苹果 iPhone 16 手机", 100, 900, 950, 950),
            line("已选 256GB ￥4999", 100, 1000, 950, 1050),
        ))
        val result = ExtractedProduct(PriceEstimateStatus.KNOWN, name = "苹果 iPhone 16 手机",
            rawTitle = doc.lines[0].text, productType = "手机", brand = "苹果", titleLineIds = listOf(0),
            attributes = listOf(ExtractedAttribute("storage_capacity", "容量", "256GB", AttributeScope.SELECTED, listOf(1))),
            price = ExtractedPrice(499900, lineIds = listOf(1), variantBinding = AttributeScope.SELECTED))
        val scene = LlmSceneExtractor.validate(result, doc)!!
        assertEquals(AttributeScope.SELECTED, scene.product.attributes.first().scope)
        assertEquals(AttributeScope.SELECTED, scene.price.variantBinding)
    }

    @Test fun foodClothingAndServiceUseDifferentOptionalAttributes() {
        val fixtures = listOf(
            Triple("纯牛奶 250ml 12盒", "牛奶", listOf("net_content" to "250ml", "pack_count" to "12盒")),
            Triple("纯棉衬衫 蓝色", "衬衫", listOf("material" to "纯棉", "color" to "蓝色")),
            Triple("视频会员 12个月 单人", "会员", listOf("duration" to "12个月", "user_count" to "单人")),
        )
        fixtures.forEach { (title, type, attrs) ->
            val doc = OcrDocument(1080, 2400, listOf(line(title, 100, 900, 950, 950), line("售价 ￥99", 100, 1000, 500, 1060)))
            val value = ExtractedProduct(PriceEstimateStatus.KNOWN, name = title, rawTitle = title,
                productType = type, titleLineIds = listOf(0),
                attributes = attrs.map { ExtractedAttribute(it.first, "属性", it.second, AttributeScope.PRODUCT, listOf(0)) },
                price = ExtractedPrice(9900, lineIds = listOf(1)))
            val product = LlmSceneExtractor.validate(value, doc)!!.product
            assertEquals(attrs.map { it.first }, product.attributes.map { it.key })
        }
    }

    @Test fun noAttributesAreRequiredAndCustomAttributesStayOutOfMatchingVocabulary() {
        val noAttrs = LlmSceneExtractor.validate(extracted.copy(attributes = emptyList()), document)!!
        assertTrue(noAttrs.product.attributes.isEmpty())
        val custom = ExtractedAttribute("noise_mode", "降噪方式", "无线降噪", AttributeScope.PRODUCT, listOf(0))
        val product = LlmSceneExtractor.validate(extracted.copy(attributes = listOf(custom)), document)!!.product
        assertEquals("custom_noise_mode", product.attributes.first().key)
        assertTrue(ProductText.searchAttributes(product).isEmpty())
    }

    @Test fun rejectsForgedEvidenceMarketingAndConditions() {
        assertInvalid(extracted.copy(attributes = listOf(ExtractedAttribute("material", "材质", "纯金",
            AttributeScope.PRODUCT, listOf(0), listOf("纯", "金")))))
        assertInvalid(extracted.copy(price = extracted.price!!.copy(conditions = listOf("首单"), conditionLineIds = listOf(0))))
        assertInvalid(ShoppingOcrFixtures.ringExtraction.copy(name = "莫比乌斯戒指送女友"), ShoppingOcrFixtures.ring)
    }

    @Test fun sendsReadingOrderWithStableIdsAndOneTextOnlyRequest() = runTest {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val server = MockWebServer()
        server.start()
        try {
            val response = buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject {
                    put("message", buildJsonObject { put("content", json.encodeToString(ExtractedProduct.serializer(), extracted)) })
                }) })
            }
            server.enqueue(MockResponse().setBody(response.toString()))
            val scene = LlmSceneExtractor(OkHttpClient(), json, server.url("/v1/chat/completions").toString(), "test-key", "test-model").extract(document)
            assertEquals(299950L, scene!!.price.currentCents)
            val body = server.takeRequest().body.readUtf8()
            val payload = json.parseToJsonElement(body).jsonObject
            val user = json.parseToJsonElement(payload.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content).jsonObject
            assertEquals(5, user.getValue("ocr_lines").jsonArray.size)
            val reading = user.getValue("reading_text").jsonPrimitive.content
            assertTrue(reading.contains("[1] 到手价 ￥ [2] 2999.50"))
            assertEquals(listOf(0, 1, 2, 3, 4), user.getValue("ocr_lines").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.int })
            assertFalse(body.contains("image_url"))
            assertFalse(body.contains("base64"))
            assertFalse(payload.containsKey("thinking"))
            assertEquals(1800, payload.getValue("max_tokens").jsonPrimitive.int)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun assertInvalid(value: ExtractedProduct, doc: OcrDocument = document) {
        assertTrue(runCatching { LlmSceneExtractor.validate(value, doc) }.exceptionOrNull() is IllegalArgumentException)
    }
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) = OcrLine(text, OcrBox(left, top, right, bottom), 0.98)
}
