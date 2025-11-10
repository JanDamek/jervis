package com.jervis.service.rag

import com.jervis.repository.mongo.ClientMongoRepository
import com.jervis.repository.mongo.ProjectMongoRepository
import com.jervis.service.rag.domain.DocumentChunk
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class RagDirectSearchService(
    private val clientMongoRepository: ClientMongoRepository,
    private val projectMongoRepository: ProjectMongoRepository,
    private val ragSearchService: RagSearchService,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun search(
        clientId: String,
        projectId: String?,
        searchText: String,
        filterKey: String?,
        filterValue: String?,
        maxChunks: Int = 100,
        minSimilarityThreshold: Double = 0.0,
    ): DirectSearchResult {
        val clientObjectId = ObjectId(clientId)
        val projectObjectId = projectId?.let { ObjectId(it) }

        val clientDocument =
            clientMongoRepository.findById(clientObjectId)
                ?: throw IllegalArgumentException("Client with ID $clientId not found")
        val projectDocument = projectObjectId?.let { projectMongoRepository.findById(it) }

        // Create custom search context with user-provided parameters
        val searchContext =
            SearchContext(
                clientId = clientObjectId.toString(),
                projectId = projectObjectId?.toString(),
                limit = maxChunks,
                minScore = minSimilarityThreshold.toFloat(),
                useHybridSearch = true,
                hybridAlpha = 0.75,
            )

        // Perform direct hybrid search
        val results = ragSearchService.hybridSearch(searchText, searchContext)

        // Apply optional metadata filtering if requested
        val items =
            if (filterKey.isNullOrBlank()) {
                results
            } else {
                results.filter { chunk ->
                    when {
                        filterValue == null -> chunk.metadata.containsKey(filterKey)
                        else -> chunk.metadata[filterKey] == filterValue
                    }
                }
            }

        return DirectSearchResult(
            items = items, // Already sorted by score
            queriesProcessed = 1,
            totalChunksFound = results.size,
            totalChunksFiltered = items.size,
        )
    }

    data class DirectSearchResult(
        val items: List<DocumentChunk>,
        val queriesProcessed: Int,
        val totalChunksFound: Int,
        val totalChunksFiltered: Int,
    )
}
