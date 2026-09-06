package com.liban.android.agent

import com.liban.android.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PriceSearchTest {
    private val product = Product("WH-1000XM6 耳机", "electronics", model = "WH-1000XM6")

    @Test fun retriesUnlinkedResultsWithAnotherEngine() = runTest {
        val service = PriceSearch { engine -> provider {
            if (engine == "search_pro") listOf(SearchHit("WH-1000XM6", "售价2999元", ""))
            else listOf(SearchHit("WH-1000XM6", "售价2999元", "https://shop.example/p"))
        } }
        val result = service.search(product, "search_pro")
        assertEquals(listOf("search_pro", "search_pro_quark"), result.engines)
        assertEquals(2, result.hits.size)
        assertEquals(1, result.linkedCount)
    }

    @Test fun primaryTimeoutStillAllowsFallback() = runTest {
        val service = PriceSearch { engine -> provider {
            if (engine == "search_pro") delay(5000)
            listOf(SearchHit("WH-1000XM6", "售价2999元", "https://shop.example/p"))
        } }
        assertEquals(1, service.search(product, "search_pro").linkedCount)
    }

    @Test fun verifiedPrimarySkipsFallback() = runTest {
        val service = PriceSearch { provider {
            listOf("a.example", "b.example", "c.example").map { SearchHit("WH-1000XM6", "售价2999元", "https://$it/p") }
        } }
        assertEquals(listOf("search_pro_quark"), service.search(product, "search_pro_quark").engines)
    }

    @Test fun fallbackFailurePreservesUsablePrimary() = runTest {
        val service = PriceSearch { engine -> provider {
            if (engine == "search_pro_sogou") throw ProviderHttpException(429, "限流")
            listOf(SearchHit("WH-1000XM6", "售价2999元", "https://shop.example/p"))
        } }
        assertEquals(1, service.search(product, "search_pro_quark").linkedCount)
    }

    private fun provider(block: suspend () -> List<SearchHit>) = object : SearchProvider {
        override suspend fun search(query: String) = block()
        override suspend fun read(url: String): PageDocument = error("not used")
    }
}
