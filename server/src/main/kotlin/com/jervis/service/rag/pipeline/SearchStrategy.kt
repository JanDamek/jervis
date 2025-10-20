package com.jervis.service.rag.pipeline

import com.jervis.domain.context.TaskContext
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
