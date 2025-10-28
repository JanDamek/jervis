package com.jervis.service.rag.pipeline

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery

/**
 * Strategy for searching document chunks from vector storage.
 */
fun interface SearchStrategy {
    suspend fun search(
        query: RagQuery,
        plan: Plan,
    ): List<DocumentChunk>
}
