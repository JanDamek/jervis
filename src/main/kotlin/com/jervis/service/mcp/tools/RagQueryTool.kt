package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.McpToolType
import com.jervis.configuration.prompts.PromptType
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import com.jervis.service.prompts.PromptRepository
import io.qdrant.client.grpc.JsonWithInt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class RagQueryTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
    private val promptRepository: PromptRepository,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override val name: String = "rag.query"
    override val description: String
        get() = promptRepository.getMcpToolDescription(McpToolType.RAG_QUERY)

    @Serializable
    data class RagQueryRequest(
        val query: String,
        val embedding: String,
        val topK: Int? = null,
        val finalPrompt: String? = null,
        val filters: Map<String, String>? = null,
    )

    @Serializable
    data class RagQueryParams(
        val queries: List<RagQueryRequest>,
        val topK: Int = 5,
        val finalPrompt: String? = null,
        val globalFilters: Map<String, String>? = null,
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
    ): RagQueryParams {
        val systemPrompt = promptRepository.getSystemPrompt(PromptType.RAG_QUERY_SYSTEM)
        val modelParams = promptRepository.getEffectiveModelParams(PromptType.RAG_QUERY_SYSTEM)

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = ModelType.INTERNAL,
                    systemPrompt = systemPrompt,
                    userPrompt = taskDescription,
                    outputLanguage = "en",
                    quick = context.quick,
                    modelParams = modelParams,
                )

            val cleanedResponse =
                llmResponse.answer
                    .trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

            Json.decodeFromString<RagQueryParams>(cleanedResponse)
        } catch (e: Exception) {
            logger.error(e) { "Error while calling RagQueryTool" }
            throw e
        }
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val queryParams =
            runCatching {
                parseTaskDescription(taskDescription, context)
            }.getOrElse { return ToolResult.error("Invalid RAG query parameters: ${it.message}") }

        if (queryParams.queries.isEmpty()) {
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

            // Apply global final prompt processing if specified
            queryParams.finalPrompt?.let { globalFinalPrompt ->
                mcpFinalPromptProcessor.processFinalPrompt(
                    finalPrompt = globalFinalPrompt,
                    systemPrompt = mcpFinalPromptProcessor.createRagSystemPrompt(),
                    originalResult = aggregatedResult,
                    context = context,
                )
            } ?: aggregatedResult
        }
    }

    private suspend fun executeRagQuery(
        context: TaskContext,
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
        queryIndex: Int,
    ): ToolResult {
        val modelType =
            when (queryRequest.embedding.lowercase()) {
                "text" -> ModelType.EMBEDDING_TEXT
                "code" -> ModelType.EMBEDDING_CODE
                else -> return ToolResult.error("Unsupported embedding type: ${queryRequest.embedding}")
            }

        val embedding =
            try {
                embeddingGateway.callEmbedding(modelType, queryRequest.query)
            } catch (e: Exception) {
                return ToolResult.error("Embedding failed for query $queryIndex: ${e.message}")
            }

        if (embedding.isEmpty()) {
            return ToolResult.ok("No embedding produced for query $queryIndex. Skipping RAG search.")
        }

        // Use query-specific topK if provided, otherwise use global topK
        val effectiveTopK = queryRequest.topK ?: globalParams.topK

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

        val results =
            try {
                vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = effectiveTopK,
                    filter = combinedFilter,
                )
            } catch (e: Exception) {
                return ToolResult.error("Vector search failed for query $queryIndex: ${e.message}")
            }

        if (results.isEmpty()) {
            return ToolResult.ok("No relevant results found for query $queryIndex: '${queryRequest.query}'")
        }

        val enhancedResults = StringBuilder()
        results.forEachIndexed { index, doc ->
            enhancedResults.appendLine("Result ${index + 1}:")

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

        val initialResult = ToolResult.ok(enhancedResults.toString())

        return queryRequest.finalPrompt?.let { queryFinalPrompt ->
            mcpFinalPromptProcessor.processFinalPrompt(
                finalPrompt = queryFinalPrompt,
                systemPrompt = mcpFinalPromptProcessor.createRagSystemPrompt(),
                originalResult = initialResult,
                context = context,
            )
        } ?: initialResult
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
