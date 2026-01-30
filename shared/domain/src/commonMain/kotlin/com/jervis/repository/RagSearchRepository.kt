package com.jervis.repository

import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.service.IRagSearchService

/**
 * Repository for RAG Search operations
 * Wraps IRagSearchService with additional logic
 */
class RagSearchRepository(
    private val ragSearchService: IRagSearchService
) : BaseRepository() {

    /**
     * Perform RAG search
     */
    suspend fun search(request: RagSearchRequestDto): RagSearchResponseDto = safeRpcCall("ragSearch") {
        ragSearchService.search(request)
    }

    /**
     * Get overview of indexing status
     */
    suspend fun getIndexingOverview() = safeRpcCall("getIndexingOverview") {
        ragSearchService.getIndexingOverview()
    }
}
