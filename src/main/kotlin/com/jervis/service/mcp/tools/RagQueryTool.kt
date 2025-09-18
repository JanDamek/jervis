package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import io.qdrant.client.grpc.JsonWithInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class RagQueryTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    override val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: PromptTypeEnum = PromptTypeEnum.RAG_QUERY

    @Serializable
    data class RagQueryRequest(
        val query: String = "",
        val embedding: String = "",
        val topK: Int? = null,
        val minScore: Float? = null,
        val finalPrompt: String? = null,
        val filters: Map<String, List<String>>? = null,
    )

    @Serializable
    data class RagQueryParams(
        val queries: List<RagQueryRequest> = listOf(RagQueryRequest()),
        val topK: Int = -1,
        val minScore: Float = 0.8f,
        val finalPrompt: String? = null,
        val globalFilters: Map<String, List<String>>? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): RagQueryParams {
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.RAG_QUERY,
                userPrompt = taskDescription,
                quick = context.quick,
                responseSchema = RagQueryParams(),
                stepContext = stepContext,
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        logger.debug {
            "RAG_QUERY_START: Executing RAG query for taskDescription='$taskDescription', contextId='${context.id}', planId='${plan.id}'"
        }

        val queryParams = parseTaskDescription(taskDescription, context, stepContext)

        logger.debug { "RAG_QUERY_PARSED: queryParams=$queryParams" }

        if (queryParams.queries.isEmpty()) {
            logger.warn { "RAG_QUERY_NO_QUERIES: No query parameters provided for taskDescription='$taskDescription'" }
            return ToolResult.error("No query parameters provided")
        }

        // Execute all queries in parallel
        return coroutineScope {
            val queryResults =
                queryParams.queries
                    .mapIndexed { index, queryRequest ->
                        async {
                            executeRagQuery(context, queryRequest, queryParams, index + 1)
                        }
                    }.awaitAll()

            val aggregatedResult = aggregateResults(queryResults, queryParams.queries.size > 1)

            logger.debug { "RAG_QUERY_AGGREGATED: result=${aggregatedResult.output}" }

            aggregatedResult
        }
    }

    private suspend fun executeRagQuery(
        context: TaskContext,
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
        queryIndex: Int,
    ): ToolResult {
        logger.debug { "RAG_QUERY_EXECUTE: Starting query $queryIndex: '${queryRequest.query}', embedding='${queryRequest.embedding}'" }

        // Check for GIT_HISTORY document type early to avoid unnecessary embedding generation
        val isGitHistoryQuery = isGitHistoryDocumentType(queryRequest, globalParams)
        if (isGitHistoryQuery) {
            logger.debug { "RAG_QUERY_GIT_HISTORY: Detected GIT_HISTORY query, using text-based search without embeddings" }
            return executeGitHistoryQuery(context, queryRequest, globalParams, queryIndex)
        }

        val modelType =
            when (queryRequest.embedding.lowercase()) {
                "text" -> ModelType.EMBEDDING_TEXT
                "code" -> ModelType.EMBEDDING_CODE
                else -> {
                    logger.error { "RAG_QUERY_UNSUPPORTED_EMBEDDING: ${queryRequest.embedding}" }
                    return ToolResult.error("Unsupported embedding type: ${queryRequest.embedding}")
                }
            }

        logger.debug { "RAG_QUERY_MODEL_TYPE: Using modelType=$modelType for query $queryIndex" }

        val embedding =
            try {
                embeddingGateway.callEmbedding(modelType, queryRequest.query)
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_EMBEDDING_ERROR: Failed to generate embedding for query $queryIndex" }
                return ToolResult.error("Embedding failed for query $queryIndex: ${e.message}")
            }

        if (embedding.isEmpty()) {
            logger.warn { "RAG_QUERY_NO_EMBEDDING: No embedding produced for query $queryIndex" }
            return ToolResult.ok("No embedding produced for query $queryIndex. Skipping RAG search.")
        }

        logger.debug { "RAG_QUERY_EMBEDDING_SUCCESS: Generated embedding with ${embedding.size} dimensions for query $queryIndex" }

        // Determine effective topK and minScore
        val effectiveTopK =
            when {
                queryRequest.topK != null -> queryRequest.topK
                globalParams.topK == -1 -> 10000 // Convert unlimited (-1) to high limit
                else -> globalParams.topK
            }

        val effectiveMinScore = queryRequest.minScore ?: globalParams.minScore

        // Build simplified filter: only client and project filters with dynamic removal
        val combinedFilter =
            buildMap<String, Any> {
                // Check if query mentions cross-client or cross-project search
                val queryText = queryRequest.query.lowercase()
                val shouldSkipClientFilter =
                    queryText.contains("napříč klientem") ||
                        queryText.contains("across client") ||
                        queryText.contains("cross client") ||
                        queryText.contains("napříč vším") ||
                        queryText.contains("across everything") ||
                        queryText.contains("everywhere")

                val shouldSkipProjectFilter =
                    queryText.contains("napříč projekty") ||
                        queryText.contains("across projects") ||
                        queryText.contains("cross project") ||
                        queryText.contains("napříč vším") ||
                        queryText.contains("across everything") ||
                        queryText.contains("everywhere")

                // Add project filter unless explicitly excluded
            if (!shouldSkipProjectFilter) {
                    put("projectId", context.projectDocument.id.toHexString())
                }

                // Add client filter unless explicitly excluded
                if (!shouldSkipClientFilter) {
                put("clientId", context.clientDocument.id.toHexString())
            }
        }

        logger.debug {
            "RAG_QUERY_FILTERS: Using combinedFilter=$combinedFilter, topK=$effectiveTopK, minScore=$effectiveMinScore for query $queryIndex"
        }

        val results =
            try {
                logger.debug { "RAG_QUERY_SEARCH_START: Executing vector search for query $queryIndex" }
                val searchResults =
                    vectorStorage.search(
                        collectionType = modelType,
                        query = embedding,
                        limit = effectiveTopK,
                        minScore = effectiveMinScore,
                        filter = combinedFilter,
                    )
                logger.debug { "RAG_QUERY_SEARCH_SUCCESS: Found ${searchResults.size} results for query $queryIndex" }
                searchResults
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_SEARCH_ERROR: Vector search failed for query $queryIndex" }
                return ToolResult.error("Vector search failed for query $queryIndex: ${e.message}")
            }

        if (results.isEmpty()) {
            logger.warn { "RAG_QUERY_NO_RESULTS: No relevant results found for query $queryIndex: '${queryRequest.query}'" }
            return ToolResult.ok("No relevant results found for query $queryIndex: '${queryRequest.query}'")
        }

        val enhancedResults = StringBuilder()
        results.forEachIndexed { index, doc ->
            enhancedResults.appendLine("Result ${index + 1}:")

            // Log each document's content for debugging
            logger.debug { "RAG_QUERY_RESULT_${index + 1}: doc=$doc" }

            // Iterate through all map entries and output KEY: VALUE pairs
            doc.forEach { (key, value) ->
                val stringValue = getStringValue(value)
                if (stringValue.isNotEmpty()) {
                    enhancedResults.appendLine("$key: $stringValue")
                }
            }

            if (index < results.size - 1) {
                enhancedResults.appendLine()
                enhancedResults.appendLine("---")
                enhancedResults.appendLine()
            }
        }

        logger.debug { "RAG_QUERY_ENHANCED_RESULTS: query $queryIndex enhanced results length=${enhancedResults.length}" }

        val initialResult = ToolResult.ok(enhancedResults.toString())

        return initialResult
    }

    private fun isGitHistoryDocumentType(
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
    ): Boolean {
        // Check global filters first
        globalParams.globalFilters?.get("documentType")?.let { docType ->
            if (docType.contains("GIT_HISTORY")) return true
        }

        // Check query-specific filters
        queryRequest.filters?.get("documentType")?.let { docType ->
            if (docType.contains("GIT_HISTORY")) return true
        }

        return false
    }

    private suspend fun executeGitHistoryQuery(
        context: TaskContext,
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
        queryIndex: Int,
    ): ToolResult {
        logger.debug { "RAG_QUERY_GIT_HISTORY_EXECUTE: Starting GIT_HISTORY query $queryIndex: '${queryRequest.query}'" }

        // Determine effective topK
        val effectiveTopK =
            when {
                queryRequest.topK != null -> queryRequest.topK
                globalParams.topK == -1 -> 10000 // Convert unlimited (-1) to high limit
                else -> globalParams.topK
            }

        // Build combined filter for GIT_HISTORY queries
        val combinedFilter =
            buildMap<String, Any> {
                // Always include projectId for security
                put("projectId", context.projectDocument.id.toHexString())

                // Add global filters if present
                globalParams.globalFilters?.forEach { (key, value) ->
                    put(key, value)
                }

                // Add query-specific filters if present (these override global filters for same keys)
                queryRequest.filters?.forEach { (key, value) ->
                    put(key, value)
                }
            }

        logger.debug {
            "RAG_QUERY_GIT_HISTORY_FILTERS: Using combinedFilter=$combinedFilter, topK=$effectiveTopK for git history query $queryIndex"
        }

        // For GIT_HISTORY, use text-based search without embeddings by providing empty embedding vector
        val results =
            try {
                logger.debug { "RAG_QUERY_GIT_HISTORY_SEARCH_START: Executing text-based search for git history query $queryIndex" }
                val searchResults =
                    vectorStorage.search(
                        collectionType = ModelType.EMBEDDING_TEXT, // Use TEXT collection for git history
                        query = listOf(), // Empty embedding vector for text-based filtering
                        limit = effectiveTopK,
                        minScore = 0.0f, // No similarity threshold for text-based search
                        filter = combinedFilter,
                    )
                logger.debug {
                    "RAG_QUERY_GIT_HISTORY_SEARCH_SUCCESS: Found ${searchResults.size} git history results for query $queryIndex"
                }
                searchResults
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_GIT_HISTORY_SEARCH_ERROR: Git history search failed for query $queryIndex" }
                return ToolResult.error("Git history search failed for query $queryIndex: ${e.message}")
            }

        if (results.isEmpty()) {
            logger.warn { "RAG_QUERY_GIT_HISTORY_NO_RESULTS: No git history results found for query $queryIndex: '${queryRequest.query}'" }
            return ToolResult.ok("No git history results found for query $queryIndex: '${queryRequest.query}'")
        }

        // Filter results by query text if needed (simple text matching for git commits)
        val filteredResults =
            if (queryRequest.query.isNotBlank()) {
                val queryWords =
                    queryRequest.query
                        .lowercase()
                        .split("\\s+".toRegex())
                        .filter { it.length > 2 }
                results.filter { doc ->
                    val pageContent = getStringValue(doc["pageContent"])?.lowercase() ?: ""
                    queryWords.any { word -> pageContent.contains(word) }
                }
            } else {
                results
            }

        logger.debug { "RAG_QUERY_GIT_HISTORY_FILTERED: Filtered to ${filteredResults.size} relevant git history results" }

        val enhancedResults = StringBuilder()
        filteredResults.forEachIndexed { index, doc ->
            enhancedResults.appendLine("Git History Result ${index + 1}:")

            // Log each document's content for debugging
            logger.debug { "RAG_QUERY_GIT_HISTORY_RESULT_${index + 1}: doc=$doc" }

            // Iterate through all map entries and output KEY: VALUE pairs
            doc.forEach { (key, value) ->
                val stringValue = getStringValue(value)
                if (stringValue.isNotEmpty()) {
                    enhancedResults.appendLine("$key: $stringValue")
                }
            }

            if (index < filteredResults.size - 1) {
                enhancedResults.appendLine()
                enhancedResults.appendLine("---")
                enhancedResults.appendLine()
            }
        }

        logger.debug {
            "RAG_QUERY_GIT_HISTORY_ENHANCED_RESULTS: git history query $queryIndex enhanced results length=${enhancedResults.length}"
        }

        return ToolResult.ok(enhancedResults.toString())
    }

    private fun aggregateResults(
        results: List<ToolResult>,
        isMultipleQueries: Boolean,
    ): ToolResult {
        val errors = results.filterIsInstance<ToolResult.Error>()
        if (errors.isNotEmpty()) {
            val errorMessages =
                errors.mapIndexed { index, error ->
                    "Query ${index + 1}: ${error.errorMessage ?: error.output}"
                }
            return ToolResult.error("Some queries failed:\n${errorMessages.joinToString("\n")}")
        }

        val successResults = results.filterIsInstance<ToolResult.Ok>()
        if (successResults.isEmpty()) {
            return ToolResult.error("All queries failed or returned no results")
        }

        val aggregatedOutput =
            if (isMultipleQueries) {
                buildString {
                    appendLine("Multi-Query RAG Search Results (${successResults.size} queries executed in parallel)")
                    appendLine("=".repeat(80))
                    appendLine()
                    successResults.forEachIndexed { index, result ->
                        if (index > 0) {
                            appendLine()
                            appendLine("=".repeat(80))
                            appendLine()
                        }
                        append(result.output)
                    }
                }
            } else {
                successResults.first().output
            }

        return ToolResult.ok(aggregatedOutput)
    }

    /**
     * Helper function to extract string value from JsonWithInt.Value
     */
    private fun getStringValue(value: JsonWithInt.Value?): String =
        when {
            value == null -> ""
            value.hasStringValue() -> value.stringValue
            value.hasIntegerValue() -> value.integerValue.toString()
            value.hasDoubleValue() -> value.doubleValue.toString()
            value.hasBoolValue() -> value.boolValue.toString()
            else -> ""
        }
}
