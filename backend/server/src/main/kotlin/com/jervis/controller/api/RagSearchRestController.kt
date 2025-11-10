package com.jervis.controller.api

import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.service.IRagSearchService
import com.jervis.service.rag.RagDirectSearchService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rag")
class RagSearchRestController(
    private val searchService: RagDirectSearchService,
) : IRagSearchService {
    @PostMapping("/search")
    override suspend fun search(@RequestBody request: RagSearchRequestDto): RagSearchResponseDto {
        val result =
            searchService.search(
                clientId = request.clientId,
                projectId = request.projectId,
                searchText = request.searchText,
                filterKey = request.filterKey,
                filterValue = request.filterValue,
                maxChunks = request.maxChunks,
                minSimilarityThreshold = request.minSimilarityThreshold,
            )

        val items =
            result.items.map { RagSearchItemDto(content = it.content, score = it.score, metadata = it.metadata) }

        return RagSearchResponseDto(
            items = items,
            queriesProcessed = result.queriesProcessed,
            totalChunksFound = result.totalChunksFound,
            totalChunksFiltered = result.totalChunksFiltered,
        )
    }
}
