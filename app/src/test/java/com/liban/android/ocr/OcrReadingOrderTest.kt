package com.liban.android.ocr

import com.liban.android.model.*
import org.junit.Assert.*
import org.junit.Test

class OcrReadingOrderTest {
    @Test fun overlapsDifferentFontSizesAndKeepsOriginalIds() {
        val doc = OcrDocument(1000, 2000, listOf(
            line("下一行", 10, 240, 300, 280),
            line("99", 160, 100, 260, 180),
            line("￥", 100, 145, 150, 180),
            line("左标题", 10, 50, 160, 90),
            line("右标题", 180, 52, 400, 88),
        ))
        val rows = OcrReadingOrder.rows(doc)
        assertEquals(listOf(listOf(3, 4), listOf(2, 1), listOf(0)), rows.map { it.ids })
        assertEquals("￥99", rows[1].line.text)
        assertEquals(5, rows.flatMap { it.ids }.distinct().size)
    }

    @Test fun doesNotMergeAdjacentRowsOrReorderAcrossLines() {
        val doc = OcrDocument(1000, 2000, listOf(line("下", 0, 140, 300, 180), line("上", 0, 100, 300, 135)))
        assertEquals(listOf("上", "下"), OcrReadingOrder.rows(doc).map { it.line.text })
    }

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, OcrBox(l, t, r, b), 0.9)
}

