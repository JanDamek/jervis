package com.jervis.service.rag

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.rag.domain.RagResult
import com.jervis.service.rag.pipeline.HybridSearchStrategy
import com.jervis.service.rag.pipeline.LlmContentSynthesisStrategy
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
 * 2. Score-based filtering and limiting (server-side)
 * 3. Synthesis of final answer from all relevant information
 *
 * Designed for reusability across different tools and use cases.
 * All filtering and limiting happens server-side - client only controls maxChunks and minSimilarityThreshold.
 */
@Service
class RagService(
    private val searchStrategy: VectorSearchStrategy,
    private val hybridSearchStrategy: HybridSearchStrategy,
    private val synthesisStrategy: LlmContentSynthesisStrategy,
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

    /**
     * Execute only retrieval part and return raw filtered chunks with counters.
     */
    suspend fun executeRawSearch(
        queries: List<RagQuery>,
        plan: Plan,
    ): RawSearchResult {
        logger.info { "RAG_RAW_SEARCH_START: Processing ${queries.size} queries" }
        val results = executeParallelQueries(queries, plan)
        val items = results.flatMap { it.filteredChunks }.sortedByDescending { it.score }
        logger.info { "RAG_RAW_SEARCH_COMPLETE: items=${items.size}" }
        return RawSearchResult(
            items = items,
            queriesProcessed = results.size,
            totalChunksFound = results.sumOf { it.chunks.size },
            totalChunksFiltered = results.sumOf { it.filteredChunks.size },
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
        // WeaviateRepository already applies minScore filtering and limit
        val chunks = hybridSearchStrategy.search(query, plan)

        logger.info {
            "Query '${query.searchTerms}': ${chunks.size} results (filtered by minScore and limited)"
        }

        return QueryResult(
            query = query.searchTerms,
            chunks = chunks,
            filteredChunks = chunks,
        )
    }

    data class QueryResult(
        val query: String,
        val chunks: List<DocumentChunk>,
        val filteredChunks: List<DocumentChunk>,
    )

    data class RawSearchResult(
        val items: List<DocumentChunk>,
        val queriesProcessed: Int,
        val totalChunksFound: Int,
        val totalChunksFiltered: Int,
    )
}
