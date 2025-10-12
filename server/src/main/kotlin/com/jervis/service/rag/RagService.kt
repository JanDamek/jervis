package com.jervis.service.rag

import com.jervis.domain.context.TaskContext
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.rag.domain.RagResult
import com.jervis.service.rag.pipeline.ChunkDeduplicationStrategy
import com.jervis.service.rag.pipeline.ContentSynthesisStrategy
import com.jervis.service.rag.pipeline.SearchStrategy
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
    private val searchStrategy: SearchStrategy,
    private val synthesisStrategy: ContentSynthesisStrategy,
    private val deduplicationStrategy: ChunkDeduplicationStrategy,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_CHUNKS_PER_QUERY = 15
    }

    /**
     * Execute a complete RAG pipeline for multiple queries.
     * Returns synthesized answer combining all relevant information.
     */
    suspend fun executeRagPipeline(
        queries: List<RagQuery>,
        originalQuery: String,
        context: TaskContext,
    ): RagResult {
        logger.info { "RAG_PIPELINE_START: Processing ${queries.size} queries" }

        val queryResults = executeParallelQueries(queries, context)
        val synthesizedAnswer = synthesisStrategy.synthesize(queryResults, originalQuery, context)

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
        context: TaskContext,
    ): List<QueryResult> =
        coroutineScope {
            queries
                .map { query ->
                    async {
                        logger.debug { "Processing query: '${query.searchTerms}'" }
                        executeSingleQuery(query, context)
                    }
                }.awaitAll()
        }

    private suspend fun executeSingleQuery(
        query: RagQuery,
        context: TaskContext,
    ): QueryResult {
        val chunks = searchStrategy.search(query, context)
        val deduplicatedChunks = deduplicationStrategy.deduplicate(chunks)
        val filteredChunks =
            deduplicatedChunks
                .sortedByDescending { it.score }
                .take(MAX_CHUNKS_PER_QUERY)

        logger.debug {
            "Query '${query.searchTerms}': ${chunks.size} → ${deduplicatedChunks.size} → ${filteredChunks.size} chunks"
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
