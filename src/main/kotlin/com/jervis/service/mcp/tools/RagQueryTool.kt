package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
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
    private val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "rag-query"
    override val description: String
        get() = promptRepository.getMcpToolDescription(PromptTypeEnum.RAG_QUERY)

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
    ): RagQueryParams {
        val userPrompt = promptRepository.getMcpToolUserPrompt(PromptTypeEnum.RAG_QUERY)
        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.RAG_QUERY,
                userPrompt = userPrompt.replace("{userPrompt}", taskDescription),
                outputLanguage = "en",
                quick = context.quick,
                mappingValue = emptyMap(),
                exampleInstance = RagQueryParams(),
            )

        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        logger.debug {
            "RAG_QUERY_START: Executing RAG query for taskDescription='$taskDescription', contextId='${context.id}', planId='${plan.id}'"
        }

        val queryParams =
            runCatching {
                parseTaskDescription(taskDescription, context)
            }.getOrElse {
                logger.error { "RAG_QUERY_PARSE_ERROR: Failed to parse task description: ${it.message}" }
                return ToolResult.error("Invalid RAG query parameters: ${it.message}")
            }

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

        // Build combined filter: mandatory projectId + global filters + query-specific filters
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
