package com.liban.android.ocr

import com.liban.android.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SceneRefinementTest {
    private val local = SceneContext(product = Product("银戒指", "fashion"), price = PriceInfo(9900), confidence = 0.1)
    @Test fun lowConfidenceLlmResultContinuesWithoutConfirmation() = runTest {
        val extracted = local.copy(sceneType = "ocr_llm", confidence = 0.0)
        assertSame(extracted, refineScene(local, { extracted }) { success, error -> assertTrue(success); assertNull(error) })
    }
    @Test fun unknownUsesLocalWithoutChangingConfidence() = runTest {
        assertSame(local, refineScene(local, { null }) { success, error -> assertFalse(success); assertNull(error) })
    }
    @Test fun invalidResponseFallsBack() = runTest {
        assertSame(local, refineScene(local, { throw IllegalArgumentException("invalid fields") }) { success, error ->
            assertFalse(success); assertNotNull(error)
        })
    }
    @Test fun timeoutFallsBackWithinFiveSeconds() = runTest {
        assertSame(local, refineScene(local, { delay(6000); local }) { _, error -> assertTrue(error is TimeoutCancellationException) })
        assertEquals(5000L, testScheduler.currentTime)
    }
    @Test fun parentCancellationIsNotSwallowed() = runTest {
        var reported = false
        val job = launch { refineScene(local, { delay(10000); local }) { _, _ -> reported = true } }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertFalse(reported)
    }
    @Test fun missingBothResultsRemainsMissingInsteadOfInventedProduct() = runTest {
        assertNull(refineScene(null, { null }) { _, _ -> })
    }
}
