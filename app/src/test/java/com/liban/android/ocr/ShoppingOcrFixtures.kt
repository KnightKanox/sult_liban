package com.liban.android.ocr

import com.liban.android.model.*

/** Manually transcribed synthetic OCR from the supplied ring screenshot, not a real OCR capture. */
object ShoppingOcrFixtures {
    val ring = OcrDocument(922, 2048, listOf(
        line("中国黄金", 30, 130, 160, 170),
        line("莫比乌斯", 250, 640, 720, 750),
        line("大促价 ￥99", 30, 1110, 305, 1170),
        line("大促直降140元", 310, 1120, 580, 1160),
        line("【中国黄金】", 180, 1350, 390, 1395),
        line("珍尚银莫比乌斯S925托帕石", 395, 1350, 870, 1395),
        line("戒指七夕生日礼物送女友", 30, 1405, 455, 1450),
        line("退货包运费", 460, 1405, 660, 1450),
        line("￥99 快要抢光 发起拼单", 550, 1840, 910, 1930),
        line("本店已拼39.7万+件", 600, 1120, 900, 1160),
        line("先用后付 支持0元下单 确认收货后再付款", 30, 1250, 895, 1310),
        line("好评率超99%同款 3年老店 623人收藏", 30, 1470, 890, 1520),
    ))
    val ringExtraction = ExtractedProduct(
        status = PriceEstimateStatus.KNOWN, name = "莫比乌斯 S925银戒指",
        rawTitle = "【中国黄金】珍尚银莫比乌斯S925托帕石戒指七夕生日礼物送女友",
        productType = "戒指", brand = "中国黄金", category = "fashion", titleLineIds = listOf(4, 5, 6),
        attributes = listOf(
            ExtractedAttribute("style", "款式", "莫比乌斯", AttributeScope.PRODUCT, listOf(5)),
            ExtractedAttribute("material", "材质", "S925银", AttributeScope.PRODUCT, listOf(5), listOf("S925", "银")),
            ExtractedAttribute("gemstone", "镶嵌", "托帕石", AttributeScope.PRODUCT, listOf(5)),
        ),
        price = ExtractedPrice(9900, type = PagePriceType.GROUP_BUY, conditions = listOf("发起拼单"),
            lineIds = listOf(2), conditionLineIds = listOf(8)),
    )
    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, OcrBox(left, top, right, bottom), 0.95)
}

