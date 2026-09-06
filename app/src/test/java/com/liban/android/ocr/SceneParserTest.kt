package com.liban.android.ocr

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class SceneParserTest {
    @Test fun ringFallbackUsesWholeTitleNotBrandOrDiscount() {
        val scene = SceneParser.parse(ShoppingOcrFixtures.ring)!!
        assertTrue(scene.product.name.contains("莫比乌斯"))
        assertTrue(scene.product.name.contains("戒指"))
        assertFalse(scene.product.name.contains("送女友"))
        assertNotEquals("中国黄金", scene.product.name)
        assertEquals("中国黄金", scene.product.brand)
        assertEquals("fashion", scene.product.category)
        assertEquals(9900L, scene.price.currentCents)
    }

    @Test fun pureBrandCannotBecomeProductName() {
        assertNull(SceneParser.parse(document(line("中国黄金", 100, 800, 40), line("￥99", 100, 900, 60))))
    }

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

    @Test fun findsTitleBelowPriceAndRejectsStoreName() {
        val scene = SceneParser.parse(document(
            line("Sony 官方旗舰店", 100, 200, 40),
            line("到手价 ¥2999", 100, 900, 64),
            line("Sony WH-1000XM6 无线降噪耳机", 100, 1010, 42, 0.98),
            line("已售10万件 包邮", 100, 1100, 28),
        ))!!
        assertEquals("Sony WH-1000XM6 无线降噪耳机", scene.product.name)
        assertEquals(299900L, scene.price.currentCents)
        assertTrue(scene.productConfidence >= 0.65)
    }

    @Test fun joinsCurrencyIntegerAndDecimalBoxes() {
        val scene = SceneParser.parse(document(
            OcrLine("￥", OcrBox(80, 550, 105, 580), 0.96),
            OcrLine("2999", OcrBox(110, 510, 320, 580), 0.99),
            OcrLine(".50", OcrBox(325, 550, 380, 580), 0.97),
            line("Sony WH-1000XM6 无线降噪耳机", 80, 650, 42),
        ))!!
        assertEquals(299950L, scene.price.currentCents)
    }

    @Test fun discountsInstallmentsAndShippingAreNotProductPrices() {
        val scene = SceneParser.parse(document(
            line("Sony WH-1000XM6 无线降噪耳机", 100, 800, 42),
            line("¥2999", 100, 900, 60),
            line("立减 ¥500", 100, 1100, 90),
            line("月供 ¥249", 100, 1200, 90),
            line("运费 ¥10", 100, 1300, 90),
        ))!!
        assertEquals(299900L, scene.price.currentCents)
    }

    @Test fun distinguishesOriginalAndCurrentPriceOnSameLine() {
        val scene = SceneParser.parse(document(
            line("Sony WH-1000XM6 无线降噪耳机", 100, 800, 42),
            line("原价 ¥3499 到手价 ¥2999", 100, 900, 60),
        ))!!
        assertEquals(299900L, scene.price.currentCents)
        assertEquals(349900L, scene.price.originalCents)
    }

    @Test fun joinsAdjacentTitleLinesAndDoesNotRepeatModel() {
        val scene = SceneParser.parse(document(
            line("¥2999", 100, 800, 60),
            line("Sony WH-1000XM6", 100, 900, 40),
            line("无线蓝牙降噪耳机 头戴式", 100, 948, 40),
        ))!!
        assertEquals("Sony WH-1000XM6 无线蓝牙降噪耳机 头戴式", scene.product.name)
    }

    @Test fun doesNotInventTitleFromNavigationOrUseOnlyOriginalPrice() {
        assertNull(SceneParser.parse(document(line("购物车立即购买", 100, 800, 40), line("¥2999", 100, 900, 60))))
        assertNull(SceneParser.parse(document(line("无线降噪耳机", 100, 800, 40), line("原价 ¥3499", 100, 900, 60))))
    }

    private fun document(vararg lines: OcrLine) = OcrDocument(1080, 2400, lines.toList())
    private fun line(text: String, left: Int, top: Int, height: Int, confidence: Double = 0.95) =
        OcrLine(text, OcrBox(left, top, 900, top + height), confidence)
}
