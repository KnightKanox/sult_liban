package com.liban.android.ocr

import com.liban.android.model.*
import kotlin.math.min

data class OcrReadingRow(val ids: List<Int>, val line: OcrLine)

/** Preserve original IDs for evidence; use geometric overlap rather than raw y/x sorting. */
object OcrReadingOrder {
    fun rows(document: OcrDocument): List<OcrReadingRow> {
        val groups = mutableListOf<MutableList<IndexedValue<OcrLine>>>()
        document.lines.withIndex().sortedWith(compareBy({ it.value.box.top }, { it.value.box.left })).forEach { entry ->
            val box = entry.value.box
            val group = groups.filter { row ->
                val anchor = row.first().value.box
                val overlap = min(box.bottom, anchor.bottom) - maxOf(box.top, anchor.top)
                overlap > 0 && overlap.toDouble() / min(box.height, anchor.height).coerceAtLeast(1) >= 0.5
            }.minByOrNull { kotlin.math.abs(it.first().value.box.centerY - box.centerY) }
            if (group == null) groups.add(mutableListOf(entry)) else group.add(entry)
        }
        return groups.map { group ->
            val ordered = group.sortedBy { it.value.box.left }
            val lines = ordered.map { it.value }
            var text = ""
            lines.forEach { line ->
                val next = line.text.trim()
                val numericContinuation = (text.endsWith("￥") || text.endsWith("¥")) && next.firstOrNull()?.isDigit() == true ||
                    text.lastOrNull()?.isDigit() == true && Regex("\\.[0-9]{1,2}").matches(next)
                text += (if (text.isEmpty() || numericContinuation) "" else " ") + next
            }
            OcrReadingRow(ordered.map { it.index }, OcrLine(text,
                OcrBox(lines.minOf { it.box.left }, lines.minOf { it.box.top }, lines.maxOf { it.box.right }, lines.maxOf { it.box.bottom }),
                lines.map { it.confidence }.average()))
        }.sortedWith(compareBy({ it.line.box.top }, { it.line.box.left }))
    }

    fun document(document: OcrDocument): OcrDocument = document.copy(lines = rows(document).map { it.line })
}

