package com.jervis.controller.api

import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.rag.EmbeddingType
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.service.IRagSearchService
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rag")
class RagSearchRestController(
    private val knowledgeService: KnowledgeService,
) : IRagSearchService {
    @PostMapping("/search")
    override suspend fun search(
        @RequestBody request: RagSearchRequestDto,
    ): RagSearchResponseDto {
        val searchRequest =
            SearchRequest(
                query = request.searchText,
                clientId = ObjectId(request.clientId),
                projectId = request.projectId?.let { ObjectId(it) },
                maxResults = request.maxChunks ?: 20,
                minScore = request.minSimilarityThreshold ?: 0.15,
                embeddingType = EmbeddingType.TEXT,
                knowledgeTypes = null, // Search all types
            )

        val searchResult = knowledgeService.search(searchRequest)

        // Parse the text result back into items for backward compatibility
        // In new system, we return plain text, but old API expects structured items
        val items =
            listOf(
                RagSearchItemDto(
                    content = searchResult.text,
                    score = 1.0,
                    metadata = emptyMap(),
                ),
            )

        return RagSearchResponseDto(
            items = items,
            queriesProcessed = 1,
            totalChunksFound = 1,
            totalChunksFiltered = 1,
        )
    }
}
