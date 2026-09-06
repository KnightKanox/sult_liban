package com.liban.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyParsingTest {
    @Test
    fun convertsYuanExactlyToCents() {
        assertEquals(123L, parseYuanToCents("1.23"))
        assertEquals(500_000L, parseYuanToCents("5000"))
        assertEquals(0L, parseYuanToCents("0.00"))
    }

    @Test
    fun rejectsFractionsSmallerThanOneCentAndInvalidValues() {
        assertNull(parseYuanToCents("1.234"))
        assertNull(parseYuanToCents("-1"))
        assertNull(parseYuanToCents("not money"))
    }
}
