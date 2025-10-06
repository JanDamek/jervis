package com.jervis.service.rag.pipeline

import com.jervis.domain.context.TaskContext
import com.jervis.service.rag.RagService
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery

/**
 * Strategy for searching document chunks from vector storage.
 */
fun interface SearchStrategy {
    suspend fun search(
        query: RagQuery,
        context: TaskContext,
    ): List<DocumentChunk>
}

/**
 * Strategy for deduplicating document chunks.
 */
fun interface ChunkDeduplicationStrategy {
    fun deduplicate(chunks: List<DocumentChunk>): List<DocumentChunk>
}

/**
 * Strategy for synthesizing final answer from query results.
 */
fun interface ContentSynthesisStrategy {
    suspend fun synthesize(
        queryResults: List<RagService.QueryResult>,
        originalQuery: String,
        context: TaskContext,
    ): String
}
