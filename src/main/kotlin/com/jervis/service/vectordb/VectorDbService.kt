package com.jervis.service.vectordb

import com.jervis.domain.rag.RagDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.mcp.McpAction
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service layer for vector database operations.
 * This service delegates to VectorStorageRepository and provides a clean interface for other services.
 * It follows the onion architecture pattern where services use repositories.
 */
@Service
class VectorDbService(
    private val vectorStorageRepository: VectorStorageRepository,
) {
    /**
     * Store a document with its embedding in the vector database
     *
     * @param ragDocument The document to store
     * @param embedding The embedding vector for the document
     * @return The ID of the stored document
     */
    fun storeDocument(
        ragDocument: RagDocument,
        embedding: List<Float>,
    ): String = vectorStorageRepository.storeDocument(ragDocument, embedding)

    /**
     * Store a document with its embedding in the vector database (suspend a version)
     *
     * @param ragDocument The document to store
     * @param embedding The embedding vector for the document
     * @return The ID of the stored document
     */
    suspend fun storeDocumentSuspend(
        ragDocument: RagDocument,
        embedding: List<Float>,
    ) {
        vectorStorageRepository.storeDocumentSuspend(ragDocument, embedding)
    }

    /**
     * Store an MCP action and its result in the vector database
     *
     * @param action The MCP action
     * @param result The result of the action
     * @param query The original query
     * @param embedding The embedding vector for the action
     * @param projectId The ID of the project
     * @return The ID of the stored action
     */
    fun storeMcpAction(
        action: McpAction,
        result: String,
        query: String,
        embedding: List<Float>,
        projectId: ObjectId,
    ): String = vectorStorageRepository.storeMcpAction(action, result, query, embedding, projectId)

    /**
     * Search for similar documents in the vector database
     *
     * @param query The query embedding
     * @param limit The maximum number of results to return
     * @param filter Optional filter to apply to the search
     * @return A list of documents similar to the query
     */
    suspend fun searchSimilar(
        query: List<Float>,
        limit: Int = 5,
        filter: Map<String, Any>? = null,
    ): List<RagDocument> = vectorStorageRepository.searchSimilar(query, limit, filter)

    /**
     * Verify that all data for a project has been properly stored in vectordb
     *
     * @param projectId The ID of the project
     * @return A pair of booleans indicating whether MongoDB and vectordb verification passed
     */
    suspend fun verifyDataStorage(projectId: ObjectId): Boolean = vectorStorageRepository.verifyDataStorage(projectId)
}
