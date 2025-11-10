package com.jervis.service.rag.pipeline

import com.jervis.domain.plan.Plan
import com.jervis.service.rag.RagSearchService
import com.jervis.service.rag.SearchContext
import com.jervis.service.rag.domain.DocumentChunk
import com.jervis.service.rag.domain.RagQuery
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Vector-based search strategy that queries both text and code embeddings in parallel.
 * Modernized to use the new RagSearchService with Flow-based API.
 *
 * Note: This is a compatibility layer for existing code using VectorSearchStrategy.
 * New code should use RagSearchService directly.
 */
@Component
class VectorSearchStrategy(
    private val ragSearchService: RagSearchService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    suspend fun search(
        query: RagQuery,
        plan: Plan,
    ): List<DocumentChunk> {
        val context = SearchContext.fromPlan(plan)

        logger.info {
            "VECTOR_SEARCH: Starting for '${query.searchTerms}' with clientId=${context.clientId}, projectId=${context.projectId}"
        }

        // Use new RagSearchService for hybrid search
        val results = ragSearchService.hybridSearch(query.searchTerms, context)

        logger.info {
            "VECTOR_SEARCH: Returned ${results.size} results for clientId=${context.clientId}, projectId=${context.projectId}"
        }

        return results
    }
}
