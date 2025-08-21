package com.jervis.service.rag

import com.jervis.domain.rag.RagQueryResult
import org.bson.types.ObjectId

/**
 * Service interface for RAG (Retrieval-Augmented Generation) queries.
 * Only the API surface is defined to allow the application to compile.
 */
interface RagQueryService {
    /**
     * Process a RAG query without project context.
     */
    suspend fun processRagQuery(query: String): RagQueryResult

    /**
     * Process a RAG query within the provided project context.
     */
    suspend fun processRagQuery(query: String, projectId: ObjectId): RagQueryResult
}
