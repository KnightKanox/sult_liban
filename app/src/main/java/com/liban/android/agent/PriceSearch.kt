package com.liban.android.agent

import com.liban.android.model.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

data class PriceSearchResult(val hits: List<SearchHit>, val engines: List<String>) {
    val linkedCount: Int get() = hits.count { it.url.isNotBlank() }
}

class PriceSearch(private val provider: (String) -> SearchProvider) {
    suspend fun search(product: Product, preferredEngine: String): PriceSearchResult {
        val query = PriceEvidenceAnalyzer.buildQuery(product)
        val engines = mutableListOf(preferredEngine)
        val primary = withTimeoutOrNull(1_750) { provider(preferredEngine).search(query) }.orEmpty()
        val samples = PriceEvidenceAnalyzer.extract(product, primary)
        if (PriceEvidenceAnalyzer.verifiedComparison(0, samples, System.currentTimeMillis()) != null) return PriceSearchResult(primary, engines)
        val fallback = if (preferredEngine == "search_pro_quark") "search_pro_sogou" else "search_pro_quark"
        engines.add(fallback)
        val fallbackQuery = if (product.model == null && PriceEvidenceAnalyzer.relevantHits(product, primary).isEmpty())
            PriceEvidenceAnalyzer.buildQuery(product, broad = true) else query
        val extra = try { withTimeoutOrNull(1_200) { provider(fallback).search(fallbackQuery) }.orEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (primary.isEmpty()) throw error else emptyList() }
        return PriceSearchResult((primary + extra).distinctBy { it.url.ifBlank { it.title + it.content.take(100) } }, engines)
    }
}
