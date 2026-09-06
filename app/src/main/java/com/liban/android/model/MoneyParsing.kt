package com.liban.android.model

/** Converts a yuan input to integer cents without rounding or floating-point loss. */
fun parseYuanToCents(value: String): Long? = runCatching {
    value.trim().toBigDecimal().movePointRight(2).longValueExact()
}.getOrNull()?.takeIf { it >= 0 }
