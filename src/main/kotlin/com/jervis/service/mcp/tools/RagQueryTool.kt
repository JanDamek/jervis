package com.jervis.service.mcp.tools

import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.LlmGateway
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.mcp.util.McpFinalPromptProcessor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service

@Service
class RagQueryTool(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
    private val mcpFinalPromptProcessor: McpFinalPromptProcessor,
) : McpTool {
    override val name: String = "rag.query"
    override val description: String =
        "Performs semantic search across code repositories and documentation using vector embeddings. Use for finding existing implementations, APIs, documentation, and understanding codebase structure. Specify search scope: 'find API documentation for authentication', 'search code examples for file handling', or 'look for similar implementations of user management'."

    @Serializable
    data class RagQueryRequest(
        val query: String,
        val embedding: String,
        val topK: Int? = null,
        val finalPrompt: String? = null,
    )

    @Serializable
    data class RagQueryParams(
        val queries: List<RagQueryRequest>,
        val topK: Int = 5,
        val finalPrompt: String? = null,
    )

    private suspend fun parseTaskDescription(taskDescription: String): RagQueryParams {
        val systemPrompt =
            """
            You are the RAG Query Tool parameter resolver. Your task is to convert a natural language task description into proper parameters for the RAG Query Tool.        
            The RAG Query Tool provides:
            - Semantic search across code repositories and documentation using vector embeddings
            - Ability to search both text embeddings (documentation, comments) and code embeddings (source code)
            - Multiple parallel queries for comprehensive coverage
            - LLM processing of search results for actionable insights
            - Project-specific filtering and context awareness
            
            Available embedding types: "text" (for documentation/comments), "code" (for source code)
            
            Return ONLY a valid JSON object with this exact structure:
            {
              "queries": [
                {
                  "query": "<search query 1>",
                  "embedding": "<text or code>",
                  "topK": <number of results, optional>,
                  "finalPrompt": "<LLM prompt to process results, optional>"
                }
              ],
              "topK": <global number of results, default 5>,
              "finalPrompt": "<global LLM prompt to process all results, optional>"
            }
            
            Examples:
            - "find API documentation for authentication" → {"queries": [{"query": "authentication API documentation", "embedding": "text"}], "topK": 5}
            - "search code examples for file handling" → {"queries": [{"query": "file handling examples", "embedding": "code"}], "topK": 7}
            - "look for similar implementations of user management" → {"queries": [{"query": "user management", "embedding": "text"}, {"query": "user management implementation", "embedding": "code"}], "topK": 5}
            
            Rules:
            - Create 1-3 queries that best cover the search intent
            - Use "text" embedding for documentation/API/concept searches
            - Use "code" embedding for implementation/pattern searches
            - For broad searches, create both text and code queries
            - topK should be 3-10 depending on search scope (default 5)
            - Add finalPrompt only if results need specific processing
            - Return only valid JSON, no explanations or markdown
            """.trimIndent()

        return try {
            val llmResponse =
                llmGateway.callLlm(
                    type = ModelType.INTERNAL,
                    systemPrompt = systemPrompt,
                    userPrompt = "Task: $taskDescription",
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
            // Enhanced fallback logic based on keywords
            val isCodeFocused =
                taskDescription.contains("implementation", ignoreCase = true) ||
                    taskDescription.contains("code", ignoreCase = true) ||
                    taskDescription.contains("function", ignoreCase = true) ||
                    taskDescription.contains("method", ignoreCase = true) ||
                    taskDescription.contains("class", ignoreCase = true)

            val isDocFocused =
                taskDescription.contains("documentation", ignoreCase = true) ||
                    taskDescription.contains("API", ignoreCase = true) ||
                    taskDescription.contains("guide", ignoreCase = true) ||
                    taskDescription.contains("tutorial", ignoreCase = true)

            val queries =
                when {
                    isCodeFocused && !isDocFocused ->
                        listOf(
                            RagQueryRequest(
                                query = taskDescription,
                                embedding = "code",
                                topK = null,
                                finalPrompt = null,
                            ),
                        )

                    isDocFocused && !isCodeFocused ->
                        listOf(
                            RagQueryRequest(
                                query = taskDescription,
                                embedding = "text",
                                topK = null,
                                finalPrompt = null,
                            ),
                        )

                    else ->
                        listOf(
                            RagQueryRequest(
                                query = taskDescription,
                                embedding = "text",
                                topK = null,
                                finalPrompt = null,
                            ),
                            RagQueryRequest(
                                query = taskDescription,
                                embedding = "code",
                                topK = null,
                                finalPrompt = null,
                            ),
                        )
                }

            RagQueryParams(
                queries = queries,
                topK = 5,
                finalPrompt = null,
            )
        }
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
    ): ToolResult {
        val queryParams =
            runCatching {
                parseTaskDescription(taskDescription)
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

            // Process individual query results and then apply global final prompt if needed
            val aggregatedResult = aggregateResults(queryResults, queryParams.queries.size > 1)

            // Apply global final prompt processing if specified
            queryParams.finalPrompt?.let { globalFinalPrompt ->
                mcpFinalPromptProcessor.processFinalPrompt(
                    finalPrompt = globalFinalPrompt,
                    systemPrompt = mcpFinalPromptProcessor.createRagSystemPrompt(),
                    originalResult = aggregatedResult,
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

        val results =
            try {
                vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = effectiveTopK,
                    filter = mapOf("projectId" to context.projectDocument.id.toHexString()),
                )
            } catch (e: Exception) {
                return ToolResult.error("Vector search failed for query $queryIndex: ${e.message}")
            }

        if (results.isEmpty()) {
            return ToolResult.ok("No relevant results found for query $queryIndex: '${queryRequest.query}'")
        }

        val enhancedResults = StringBuilder()
        enhancedResults.appendLine("🔍 Query $queryIndex Results: '${queryRequest.query}'")
        enhancedResults.appendLine("Found ${results.size} relevant result(s)")
        enhancedResults.appendLine()

        results.forEachIndexed { index, doc ->
            val content = doc.pageContent.trim()
            enhancedResults.appendLine(
                "📄 Result ${index + 1}: ${doc.documentType.name.lowercase()} from ${doc.ragSourceType.name.lowercase()}",
            )
            enhancedResults.appendLine("🆔 Project: ${doc.projectId}")

            if (content.isEmpty()) {
                enhancedResults.appendLine("⚠️  [No content available - this may indicate a data storage issue]")
            } else {
                val displayContent =
                    when {
                        doc.documentType.name
                            .lowercase()
                            .contains("code") && content.length <= 2000 -> content

                        content.length > 1000 -> content.take(1000) + "\n... [truncated - showing first 1000 characters]"
                        else -> content
                    }

                enhancedResults.appendLine("```")
                enhancedResults.appendLine(displayContent)
                enhancedResults.appendLine("```")

                enhancedResults.appendLine(
                    "💡 This ${doc.documentType.name.lowercase()} content comes from ${doc.ragSourceType.name.lowercase()} source.",
                )
            }

            if (index < results.size - 1) {
                enhancedResults.appendLine()
                enhancedResults.appendLine("---")
                enhancedResults.appendLine()
            }
        }

        val initialResult = ToolResult.ok(enhancedResults.toString())

        // Apply query-specific final prompt if provided
        return queryRequest.finalPrompt?.let { queryFinalPrompt ->
            mcpFinalPromptProcessor.processFinalPrompt(
                finalPrompt = queryFinalPrompt,
                systemPrompt = mcpFinalPromptProcessor.createRagSystemPrompt(),
                originalResult = initialResult,
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
                    appendLine("🔍 Multi-Query RAG Search Results (${successResults.size} queries executed in parallel)")
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
}
