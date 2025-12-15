package com.jervis.controller.api

import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.service.IRagSearchService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
                clientId = ClientId(ObjectId(request.clientId)),
                projectId = request.projectId?.let { ProjectId(ObjectId(it)) },
                maxResults = request.maxChunks,
                minScore = request.minSimilarityThreshold,
            )

        val searchResult = knowledgeService.search(searchRequest)

        // Parse the text result back into items for backward compatibility
        // In a new system, we return plain text, but the old API expects structured items
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
