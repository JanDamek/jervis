package com.jervis.service.rag.pipeline

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Hybrid search strategy combining:
 * 1. Semantic search via embeddings (existing VectorSearchStrategy)
 * 2. Keyword-based boosting for exact matches in content/metadata
 *
 * This improves search accuracy when:
 * - Searching for specific terms (e.g., "adventní kalendář" should match "adventi_kalendar" in URL)
 * - Short queries have low semantic similarity with long embedded chunks
 * - Need both semantic understanding AND exact keyword matches
 */
@Component
class HybridSearchStrategy(
    private val vectorSearchStrategy: VectorSearchStrategy,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val KEYWORD_BOOST_MULTIPLIER = 1.5 // Boost score by 50% if keyword found
    }

    /**
     * Performs hybrid search:
     * 1. Execute semantic vector search
     * 2. Extract keywords from query
     * 3. Boost scores of results containing query keywords
     * 4. Re-sort by boosted scores
     */
    suspend fun search(
        query: RagQuery,
        plan: Plan,
    ): List<DocumentChunk> {
        // Step 1: Get semantic search results
        val semanticResults = vectorSearchStrategy.search(query, plan)

        // Step 2: Extract keywords from query (alphanumeric words, 3+ chars)
        val keywords = extractKeywords(query.searchTerms)

        if (keywords.isEmpty()) {
            logger.debug { "HYBRID_SEARCH: No keywords extracted, using pure semantic results" }
            return semanticResults
        }

        logger.info { "HYBRID_SEARCH: Extracted ${keywords.size} keywords: $keywords" }

        // Step 3: Boost scores for keyword matches
        val boostedResults =
            semanticResults.map { chunk ->
                val keywordMatchCount = countKeywordMatches(chunk, keywords)
                val boostFactor =
                    if (keywordMatchCount > 0) {
                        1.0 + (KEYWORD_BOOST_MULTIPLIER - 1.0) * (keywordMatchCount.toDouble() / keywords.size)
                    } else {
                        1.0
                    }

                if (boostFactor > 1.0) {
                    logger.debug {
                        "HYBRID_BOOST: Chunk matched $keywordMatchCount/${keywords.size} keywords, " +
                            "boosting score ${chunk.score} -> ${chunk.score * boostFactor}"
                    }
                }

                chunk.copy(score = chunk.score * boostFactor)
            }

        // Step 4: Re-sort by boosted scores
        return boostedResults.sortedByDescending { it.score }
    }

    /**
     * Extract meaningful keywords from query text.
     * - Converts to lowercase
     * - Removes diacritics/special chars
     * - Splits on whitespace/punctuation
     * - Keeps only words with 3+ chars
     */
    private fun extractKeywords(text: String): List<String> =
        text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ") // Remove special chars
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .distinct()

    /**
     * Count how many query keywords appear in chunk content or metadata.
     * Case-insensitive, partial matches allowed (e.g., "kalendar" matches "adventi_kalendar")
     */
    private fun countKeywordMatches(
        chunk: DocumentChunk,
        keywords: List<String>,
    ): Int {
        val contentLower = chunk.content.lowercase()
        val metadataText =
            chunk.metadata.values
                .joinToString(" ")
                .lowercase()
        val searchableText = "$contentLower $metadataText"

        return keywords.count { keyword ->
            searchableText.contains(keyword)
        }
    }
}
