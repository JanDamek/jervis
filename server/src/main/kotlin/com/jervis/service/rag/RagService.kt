package com.jervis.service.rag

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.rag.domain.RagResult
import com.jervis.service.rag.pipeline.LlmContentSynthesisStrategy
import com.jervis.service.rag.pipeline.MetadataBasedDeduplicationStrategy
import com.jervis.service.rag.pipeline.VectorSearchStrategy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Retrieval Augmented Generation (RAG) Service.
 *
 * Orchestrates the complete RAG pipeline:
 * 1. Multi-query parallel search across vector storage
 * 2. Deduplication of retrieved chunks
 * 3. Score-based filtering (top N chunks)
 * 4. Synthesis of final answer from all relevant information
 *
 * Designed for reusability across different tools and use cases.
 */
@Service
class RagService(
    private val searchStrategy: VectorSearchStrategy,
    private val synthesisStrategy: LlmContentSynthesisStrategy,
    private val deduplicationStrategy: MetadataBasedDeduplicationStrategy,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Execute a complete RAG pipeline for multiple queries.
     * Returns a synthesized answer combining all relevant information.
     */
    suspend fun executeRagPipeline(
        queries: List<RagQuery>,
        originalQuery: String,
        plan: Plan,
    ): RagResult {
        logger.info { "RAG_PIPELINE_START: Processing ${queries.size} queries" }

        val queryResults = executeParallelQueries(queries, plan)
        val synthesizedAnswer = synthesisStrategy.synthesize(queryResults, originalQuery, plan)

        logger.info { "RAG_PIPELINE_COMPLETE: Processed ${queryResults.size} queries" }

        return RagResult(
            answer = synthesizedAnswer,
            queriesProcessed = queryResults.size,
            totalChunksFound = queryResults.sumOf { it.chunks.size },
            totalChunksFiltered = queryResults.sumOf { it.filteredChunks.size },
        )
    }

    private suspend fun executeParallelQueries(
        queries: List<RagQuery>,
        plan: Plan,
    ): List<QueryResult> =
        coroutineScope {
            queries
                .map { query ->
                    async {
                        logger.debug { "Processing query: '${query.searchTerms}'" }
                        executeSingleQuery(query, plan)
                    }
                }.awaitAll()
        }

    private suspend fun executeSingleQuery(
        query: RagQuery,
        plan: Plan,
    ): QueryResult {
        val chunks = searchStrategy.search(query, plan)
        val deduplicatedChunks = deduplicationStrategy.deduplicate(chunks)

        val topScores = deduplicatedChunks.take(10).map { "%.3f".format(it.score) }.joinToString(", ")
        val belowThreshold = deduplicatedChunks.count { it.score < query.minSimilarityThreshold }

        val filteredChunks =
            deduplicatedChunks
                .filter { it.score >= query.minSimilarityThreshold }
                .sortedByDescending { it.score }
                .take(query.maxChunks)

        logger.info {
            "Query '${query.searchTerms}': ${chunks.size} raw → ${deduplicatedChunks.size} dedup → ${filteredChunks.size} " +
                "filtered (threshold=${query.minSimilarityThreshold}). " +
                "Top scores: [$topScores]. Filtered out: $belowThreshold chunks"
        }

        return QueryResult(
            query = query.searchTerms,
            chunks = chunks,
            filteredChunks = filteredChunks,
        )
    }

    data class QueryResult(
        val query: String,
        val chunks: List<DocumentChunk>,
        val filteredChunks: List<DocumentChunk>,
    )
}
