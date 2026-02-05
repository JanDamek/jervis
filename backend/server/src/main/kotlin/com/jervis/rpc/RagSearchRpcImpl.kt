package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.knowledgebase.KnowledgeService
import com.jervis.knowledgebase.model.RetrievalRequest
import com.jervis.service.IRagSearchService
import org.springframework.stereotype.Component

@Component
class RagSearchRpcImpl(
    private val knowledgeService: KnowledgeService,
) : IRagSearchService {
    override suspend fun search(request: RagSearchRequestDto): RagSearchResponseDto {
        val retrievalRequest =
            RetrievalRequest(
                query = request.searchText,
                clientId = ClientId.fromString(request.clientId),
                projectId = request.projectId?.let { ProjectId.fromString(it) },
                minConfidence = request.minSimilarityThreshold,
                maxResults = request.maxChunks,
            )
        val evidencePack = knowledgeService.retrieve(retrievalRequest)

        val items =
            evidencePack.items.map { item ->
                RagSearchItemDto(
                    content = item.content,
                    score = item.confidence,
                    metadata = item.metadata,
                )
            }

        return RagSearchResponseDto(
            items = items,
            queriesProcessed = 1,
            totalChunksFound = items.size,
            totalChunksFiltered = 0,
        )
    }
}
