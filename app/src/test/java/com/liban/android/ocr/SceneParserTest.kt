package com.liban.android.ocr

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class SceneParserTest {
    @Test
    fun picksCurrentPriceAndTitleAboveIt() {
        val scene = SceneParser.parse(document(
            line("Sony WH-1000XM6 无线降噪耳机", 100, 260, 42, 0.98),
            line("原价 ¥3499", 100, 500, 28, 0.95),
            line("到手价 ¥2999", 100, 560, 64, 0.99),
        ))!!
        assertEquals(299_900, scene.price.currentCents)
        assertEquals(349_900L, scene.price.originalCents)
        assertTrue(scene.product.name.contains("WH-1000XM6"))
        assertEquals("WH-1000XM6", scene.product.model)
        assertTrue(scene.priceConfidence >= 0.80)
    }

    @Test
    fun detectsPromotionSignals() {
        val scene = SceneParser.parse(document(
            line("ABC X100 智能设备", 100, 200, 40),
            line("限时优惠 仅剩2件", 100, 400, 30),
            line("现价 ￥899", 100, 500, 60),
        ))!!
        assertTrue(scene.signals.discount)
        assertTrue(scene.signals.limitedTime)
        assertTrue(scene.signals.scarcity)
    }

    @Test fun rejectsDocumentWithoutExplicitCnyPrice() {
        assertNull(SceneParser.parse(document(line("Sony WH-1000XM6", 10, 100, 40), line("2999", 10, 200, 50))))
    }

    private fun document(vararg lines: OcrLine) = OcrDocument(1080, 2400, lines.toList())
    private fun line(text: String, left: Int, top: Int, height: Int, confidence: Double = 0.95) =
        OcrLine(text, OcrBox(left, top, 900, top + height), confidence)
}
