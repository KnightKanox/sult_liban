package com.liban.android.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.liban.android.model.OcrBox
import com.liban.android.model.OcrDocument
import com.liban.android.model.OcrLine
import kotlinx.coroutines.tasks.await

interface OcrProvider {
    suspend fun recognize(bitmap: Bitmap): OcrDocument
}

class MlKitChineseOcrProvider : OcrProvider {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    override suspend fun recognize(bitmap: Bitmap): OcrDocument {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val bounds = line.boundingBox ?: return@mapNotNull null
                val confidence = line.elements
                    .map { it.confidence.toDouble() }
                    .filter { it in 0.0..1.0 }
                    .average()
                    .takeUnless(Double::isNaN) ?: 0.5
                OcrLine(
                    text = line.text.trim(),
                    box = OcrBox(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    confidence = confidence,
                )
            }
        }.filter { it.text.isNotBlank() }
        return OcrDocument(bitmap.width, bitmap.height, lines)
    }
}
