package com.jervis.rpc

import com.jervis.service.error.ErrorLogService
import mu.KotlinLogging

import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.rag.KnowledgeService
import com.jervis.rag.SearchRequest
import com.jervis.service.IRagSearchService
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.stereotype.Component

@Component
class RagSearchRpcImpl(
    private val knowledgeService: KnowledgeService,
) : IRagSearchService {
    override suspend fun search(request: RagSearchRequestDto): RagSearchResponseDto {
        val searchRequest =
            SearchRequest(
                query = request.searchText,
                clientId = ClientId(ObjectId(request.clientId)),
                projectId = request.projectId?.let { ProjectId(ObjectId(it)) },
                maxResults = request.maxChunks,
                minScore = request.minSimilarityThreshold,
            )

        val searchResult = knowledgeService.search(searchRequest)

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
