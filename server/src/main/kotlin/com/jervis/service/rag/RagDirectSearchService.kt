package com.jervis.service.rag

import com.jervis.domain.plan.Plan
import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import com.jervis.service.text.TextChunkingService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class RagDirectSearchService(
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val ragService: RagService,
    private val textChunkingService: TextChunkingService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun search(
        clientId: String,
        projectId: String?,
        searchText: String,
        filterKey: String?,
        filterValue: String?,
    ): DirectSearchResult {
        val clientObjectId = ObjectId(clientId)
        val projectObjectId = projectId?.let { ObjectId(it) }

        val clientDocument =
            clientMongoRepository.findById(clientObjectId)
                ?: throw IllegalArgumentException("Client with ID $clientId not found")
        val projectDocument = projectObjectId?.let { projectMongoRepository.findById(it) }

        val plan =
            Plan(
                id = ObjectId.get(),
                taskInstruction = searchText,
                originalLanguage = "",
                englishInstruction = searchText,
                clientDocument = clientDocument,
                projectDocument = projectDocument,
                quick = true,
                backgroundMode = false,
            )

        val queries = buildQueries(searchText)

        val raw = ragService.executeRawSearch(queries, plan)

        val filteredItems =
            raw.items.filter { chunk ->
                when {
                    filterKey.isNullOrBlank() -> true
                    filterValue == null -> chunk.metadata.containsKey(filterKey)
                    else -> chunk.metadata[filterKey] == filterValue
                }
            }

        val items = filteredItems.sortedByDescending { it.score }

        return DirectSearchResult(
            items = items,
            queriesProcessed = raw.queriesProcessed,
            totalChunksFound = raw.totalChunksFound,
            totalChunksFiltered = raw.totalChunksFiltered,
        )
    }

    private suspend fun buildQueries(text: String): List<RagQuery> =
        textChunkingService.splitText(text).map { RagQuery(searchTerms = it.text()) }

    data class DirectSearchResult(
        val items: List<DocumentChunk>,
        val queriesProcessed: Int,
        val totalChunksFound: Int,
        val totalChunksFiltered: Int,
    )
}
