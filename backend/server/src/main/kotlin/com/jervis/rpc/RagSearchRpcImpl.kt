package com.jervis.rpc

import com.jervis.dto.indexing.IndexingOverviewDto
import com.jervis.dto.rag.RagSearchItemDto
import com.jervis.dto.rag.RagSearchRequestDto
import com.jervis.dto.rag.RagSearchResponseDto
import com.jervis.rag.KnowledgeService
import com.jervis.rag.RetrievalRequest
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
        val retrievalRequest =
            RetrievalRequest(
                query = request.searchText,
                clientId = ClientId(ObjectId(request.clientId)),
                projectId = request.projectId?.let { ProjectId(ObjectId(it)) },
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

    override suspend fun getIndexingOverview(): IndexingOverviewDto {
        TODO("Not yet implemented")
    }
}
