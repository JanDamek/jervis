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
import com.jervis.service.mcp.util.ToolResponseBuilder
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
    )

    @Serializable
    data class RagQueryParams(
        val queries: List<RagQueryRequest> = listOf(RagQueryRequest()),
        val topK: Int = -1,
        val minScore: Float = 0.7f, // Changed from 0.8f to more realistic default for standard RAG queries
        val globalSearch: Boolean = false, // Search across all clients/projects when true
    )

    @Serializable
    data class SimpleRagQuery(
        val query: String,
        val embedding: String = "text" // Only "text" or "code"
    )

    private fun parseTaskDescription(taskDescription: String): String {
        // Simply extract clean query from task description
        return taskDescription.replace("RAG_QUERY", "").trim()
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

        val query = parseTaskDescription(taskDescription)

        logger.debug { "RAG_QUERY_PARSED: query='$query'" }

        if (query.isBlank()) {
            logger.warn { "RAG_QUERY_NO_QUERY: No query provided for taskDescription='$taskDescription'" }
            return ToolResult.error("No query provided")
        }

        // Execute dual search across both text and code embeddings
        return executeDualSearch(context, query, stepContext)
    }

    private suspend fun executeDualSearch(
        context: TaskContext,
        query: String,
        stepContext: String = "",
    ): ToolResult {
        logger.debug { "RAG_DUAL_SEARCH_START: Executing dual search for query='$query'" }

        // Search parameters
        val topK = 10
        val minScore = 0.7f
        val globalSearch = false

        // Build filter for project scoping
        val filter = if (globalSearch) {
            emptyMap<String, Any>()
        } else {
            mapOf("projectId" to context.projectDocument.id.toHexString())
        }

        return coroutineScope {
            try {
                // Generate embeddings for both text and code collections simultaneously
                val textEmbeddingDeferred = async {
                    try {
                        embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, query)
                    } catch (e: Exception) {
                        logger.error(e) { "RAG_DUAL_SEARCH_TEXT_EMBEDDING_ERROR: Failed to generate text embedding" }
                        emptyList<Float>()
                    }
                }
                
                val codeEmbeddingDeferred = async {
                    try {
                        embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, query)
                    } catch (e: Exception) {
                        logger.error(e) { "RAG_DUAL_SEARCH_CODE_EMBEDDING_ERROR: Failed to generate code embedding" }
                        emptyList<Float>()
                    }
                }

                val textEmbedding = textEmbeddingDeferred.await()
                val codeEmbedding = codeEmbeddingDeferred.await()

                // Search both collections simultaneously
                val textResultsDeferred = async {
                    if (textEmbedding.isNotEmpty()) {
                        try {
                            vectorStorage.search(
                                collectionType = ModelType.EMBEDDING_TEXT,
                                query = textEmbedding,
                                limit = topK,
                                minScore = minScore,
                                filter = filter,
                            )
                        } catch (e: Exception) {
                            logger.error(e) { "RAG_DUAL_SEARCH_TEXT_SEARCH_ERROR: Text search failed" }
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }

                val codeResultsDeferred = async {
                    if (codeEmbedding.isNotEmpty()) {
                        try {
                            vectorStorage.search(
                                collectionType = ModelType.EMBEDDING_CODE,
                                query = codeEmbedding,
                                limit = topK,
                                minScore = minScore,
                                filter = filter,
                            )
                        } catch (e: Exception) {
                            logger.error(e) { "RAG_DUAL_SEARCH_CODE_SEARCH_ERROR: Code search failed" }
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }

                val textResults = textResultsDeferred.await()
                val codeResults = codeResultsDeferred.await()

                // Merge and sort results by score (descending)
                val allResults = (textResults + codeResults)
                    .sortedByDescending { doc ->
                        // Extract score for sorting using the same pattern as getStringValue
                        val scoreValue = doc["score"]
                        when {
                            scoreValue?.hasDoubleValue() == true -> scoreValue.doubleValue
                            scoreValue?.hasIntegerValue() == true -> scoreValue.integerValue.toDouble()
                            else -> 0.0
                        }
                    }
                    .take(topK) // Take top results after merging

                logger.info { "RAG_DUAL_SEARCH_RESULTS: Found ${textResults.size} text + ${codeResults.size} code = ${allResults.size} total documents" }

                if (allResults.isEmpty()) {
                    logger.warn { "RAG_DUAL_SEARCH_NO_RESULTS: No documents found for query" }
                    return@coroutineScope ToolResult.error("RAG_QUERY_FAILED - No results found with minScore >= $minScore")
                }

                val resultsContent = buildString {
                    allResults.forEachIndexed { index, doc ->
                        append("Result ${index + 1}:")
                        appendLine()

                        // Iterate through all map entries and output KEY: VALUE pairs
                        doc.forEach { (key, value) ->
                            val stringValue = getStringValue(value)
                            if (stringValue.isNotEmpty()) {
                                appendLine("$key: $stringValue")
                            }
                        }

                        if (index < allResults.size - 1) {
                            appendLine()
                            appendLine("---")
                            appendLine()
                        }
                    }
                }

                ToolResult.searchResult(
                    toolName = "RAG_QUERY",
                    resultCount = allResults.size,
                    results = resultsContent
                )

            } catch (e: Exception) {
                logger.error(e) { "RAG_DUAL_SEARCH_UNEXPECTED_ERROR: Unexpected error during dual search" }
                ToolResult.error("Dual search failed: ${e.message}")
            }
        }
    }

    private suspend fun executeSimplifiedRagQuery(
        context: TaskContext,
        simpleQuery: SimpleRagQuery,
        stepContext: String = "",
    ): ToolResult {
        logger.debug { "RAG_QUERY_EXECUTE: Starting simplified query: '${simpleQuery.query}', embedding='${simpleQuery.embedding}'" }

        val modelType =
            when (simpleQuery.embedding.lowercase()) {
                "text" -> ModelType.EMBEDDING_TEXT
                "code" -> ModelType.EMBEDDING_CODE
                else -> {
                    logger.error { "RAG_QUERY_UNSUPPORTED_EMBEDDING: ${simpleQuery.embedding}" }
                    return ToolResult.error("Unsupported embedding type: ${simpleQuery.embedding}")
                }
            }

        logger.debug { "RAG_QUERY_MODEL_TYPE: Using modelType=$modelType" }

        val embedding =
            try {
                embeddingGateway.callEmbedding(modelType, simpleQuery.query)
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_EMBEDDING_ERROR: Failed to generate embedding" }
                return ToolResult.error("Embedding failed: ${e.message}")
            }

        if (embedding.isEmpty()) {
            logger.warn { "RAG_QUERY_NO_EMBEDDING: No embedding produced" }
            return ToolResult.success(
                toolName = "RAG_QUERY",
                summary = "No embedding produced",
                content = "Skipping RAG search due to empty embedding."
            )
        }

        // Use simplified default values instead of complex parameter handling
        val topK = 10  // Simple default
        val minScore = 0.7f  // Simple default
        val globalSearch = false  // Simple default

        logger.debug { "RAG_QUERY_PARAMS: topK=$topK, minScore=$minScore, globalSearch=$globalSearch" }

        // Build simple filter for project scoping
        val filter = if (globalSearch) {
            emptyMap<String, Any>()
        } else {
            mapOf("projectId" to context.projectDocument.id.toHexString())
        }

        val results =
            try {
                vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = topK,
                    minScore = minScore,
                    filter = filter,
                )
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_SEARCH_ERROR: Vector search failed" }
                return ToolResult.error("Vector search failed: ${e.message}")
            }

        logger.info { "RAG_QUERY_RESULTS: Found ${results.size} documents" }

        if (results.isEmpty()) {
            logger.warn { "RAG_QUERY_NO_RESULTS: No documents found for query" }
            return ToolResult.error("RAG_QUERY_FAILED - No results found with minScore >= $minScore")
        }

        val resultsContent = buildString {
            results.forEachIndexed { index, doc ->
                append("Result ${index + 1}:")
                appendLine()

                // Iterate through all map entries and output KEY: VALUE pairs
                doc.forEach { (key, value) ->
                    val stringValue = getStringValue(value)
                    if (stringValue.isNotEmpty()) {
                        appendLine("$key: $stringValue")
                    }
                }

                if (index < results.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }
        
        return ToolResult.searchResult(
            toolName = "RAG_QUERY",
            resultCount = results.size,
            results = resultsContent
        )
    }

    private suspend fun executeRagQuery(
        context: TaskContext,
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
        queryIndex: Int,
        stepContext: String = "",
    ): ToolResult {
        logger.debug { "RAG_QUERY_EXECUTE: Starting query $queryIndex: '${queryRequest.query}', embedding='${queryRequest.embedding}'" }
        
        // Analyze step context for failure patterns to enable early adaptation
        val failurePatterns = analyzeStepContextForFailures(stepContext)
        val shouldUseEarlyAdaptation = failurePatterns.hasRepeatedRagFailures || failurePatterns.hasHighMinScoreFailures
        
        if (shouldUseEarlyAdaptation) {
            logger.info { "RAG_QUERY_EARLY_ADAPTATION: Detected failure patterns, applying early adaptive strategies for query $queryIndex" }
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
            return ToolResult.success(
                toolName = "RAG_QUERY",
                summary = "No embedding produced for query $queryIndex",
                content = "Skipping RAG search due to empty embedding."
            )
        }

        logger.debug { "RAG_QUERY_EMBEDDING_SUCCESS: Generated embedding with ${embedding.size} dimensions for query $queryIndex" }

        // Determine effective topK and minScore with early adaptation
        val effectiveTopK =
            when {
                queryRequest.topK != null -> queryRequest.topK
                globalParams.topK == -1 -> 10000 // Convert unlimited (-1) to high limit
                else -> globalParams.topK
            }

        val baseMinScore = queryRequest.minScore ?: globalParams.minScore
        val effectiveMinScore = if (shouldUseEarlyAdaptation) {
            // Apply early adaptation to minScore based on failure patterns
            val adaptedMinScore = when {
                failurePatterns.hasHighMinScoreFailures && failurePatterns.previousMinScores.isNotEmpty() -> {
                    // Use the lowest successful minScore from previous attempts, or go significantly lower
                    val lowestTried = failurePatterns.previousMinScores.minOrNull() ?: baseMinScore
                    maxOf(lowestTried - 0.2f, 0.4f)
                }
                failurePatterns.cycleCount >= 2 -> {
                    // Aggressive adaptation for cycle detection
                    maxOf(baseMinScore - 0.3f, 0.3f)
                }
                failurePatterns.hasRepeatedRagFailures -> {
                    // Moderate adaptation for repeated failures
                    maxOf(baseMinScore - 0.15f, 0.5f)
                }
                else -> baseMinScore
            }
            logger.info { "RAG_EARLY_ADAPTATION: Adjusted minScore from $baseMinScore to $adaptedMinScore based on failure patterns" }
            adaptedMinScore
        } else {
            baseMinScore
        }

        logger.debug { "RAG_QUERY_SEARCH_SCOPE: Query $queryIndex using globalSearch=${globalParams.globalSearch}" }

        // Build filter based on globalSearch flag
        val combinedFilter =
            buildMap<String, Any> {
                // Apply scope filtering based on globalSearch flag
                if (!globalParams.globalSearch) {
                    // Default to project scope, fallback to client scope if project not available
                    val projectId = context.projectDocument.id.toHexString()
                    val clientId = context.clientDocument.id.toHexString()

                    if (projectId.isNotEmpty()) {
                        put("projectId", projectId)
                    } else if (clientId.isNotEmpty()) {
                        put("clientId", clientId)
                    }
                }
                // If globalSearch is true, no additional scope filters are added - search everywhere
            }

        logger.debug {
            "RAG_QUERY_FILTERS: Using globalSearch=${globalParams.globalSearch}, combinedFilter=$combinedFilter, topK=$effectiveTopK, minScore=$effectiveMinScore for query $queryIndex"
        }

        val results =
            try {
                logger.debug { "RAG_QUERY_SEARCH_START: Executing vector search for query $queryIndex" }
                
                // First, let's check if there are any documents in the collection without score filtering
                val allResults = vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = effectiveTopK,
                    minScore = 0.0f, // Get all results to see what's available
                    filter = combinedFilter,
                )
                
                logger.debug { "RAG_QUERY_SEARCH_RAW: Found ${allResults.size} total documents in collection for query $queryIndex (before minScore filtering)" }
                
                // Log some score statistics for debugging
                if (allResults.isNotEmpty()) {
                    val scores = allResults.mapNotNull { doc -> 
                        doc["score"]?.let { scoreValue ->
                            when {
                                scoreValue.hasDoubleValue() -> scoreValue.doubleValue.toFloat()
                                scoreValue.hasIntegerValue() -> scoreValue.integerValue.toFloat()
                                else -> null
                            }
                        }
                    }
                    if (scores.isNotEmpty()) {
                        val maxScore = scores.maxOrNull() ?: 0.0f
                        val minScore = scores.minOrNull() ?: 0.0f
                        val avgScore = scores.average().toFloat()
                        logger.debug { "RAG_QUERY_SCORE_STATS: query $queryIndex - maxScore=$maxScore, minScore=$minScore, avgScore=$avgScore, threshold=$effectiveMinScore" }
                    }
                }
                
                // Now apply the actual minScore filtering
                val searchResults =
                    vectorStorage.search(
                        collectionType = modelType,
                        query = embedding,
                        limit = effectiveTopK,
                        minScore = effectiveMinScore,
                        filter = combinedFilter,
                    )
                logger.debug { "RAG_QUERY_SEARCH_SUCCESS: Found ${searchResults.size} results for query $queryIndex (after minScore=$effectiveMinScore filtering)" }
                searchResults
            } catch (e: Exception) {
                logger.error(e) { "RAG_QUERY_SEARCH_ERROR: Vector search failed for query $queryIndex" }
                return ToolResult.error("Vector search failed for query $queryIndex: ${e.message}")
            }

        if (results.isEmpty()) {
            // Enhanced diagnostic logging for empty results
            logger.warn { "RAG_QUERY_NO_RESULTS_DETAILED: No relevant results found for query $queryIndex: '${queryRequest.query}'" }
            logger.warn { "RAG_QUERY_DIAGNOSTIC: Query parameters - modelType=$modelType, topK=$effectiveTopK, minScore=$effectiveMinScore, embedding dimensions=${embedding.size}" }
            logger.warn { "RAG_QUERY_DIAGNOSTIC: Filter applied - $combinedFilter" }
            logger.warn { "RAG_QUERY_DIAGNOSTIC: Possible causes:" }
            logger.warn { "  1. minScore threshold ($effectiveMinScore) too high - consider lowering it" }
            logger.warn { "  2. No documents indexed in collection $modelType with current filters" }
            logger.warn { "  3. Query embedding not semantically similar to indexed content" }
            logger.warn { "  4. Project/client filters excluding all relevant documents" }
            logger.warn { "RAG_QUERY_DIAGNOSTIC: Suggestions - try lower minScore, check if project has indexed documents, or use globalSearch=true" }
            
            // Attempt adaptive retry strategies before giving up
            logger.info { "RAG_QUERY_ADAPTIVE: Attempting adaptive retry strategies for query $queryIndex" }
            val adaptiveResult = attemptAdaptiveRetry(
                context = context,
                queryRequest = queryRequest,
                globalParams = globalParams,
                queryIndex = queryIndex,
                originalMinScore = effectiveMinScore,
                modelType = modelType,
                embedding = embedding,
                combinedFilter = combinedFilter,
                effectiveTopK = effectiveTopK,
                failurePatterns = failurePatterns
            )
            
            return adaptiveResult ?: ToolResult.error("RAG_QUERY_FAILED - No results found with minScore >= $effectiveMinScore after adaptive retries. Check logs for diagnostic details.")
        }

        val resultsContent = buildString {
            results.forEachIndexed { index, doc ->
                append("Result ${index + 1}:")
                appendLine()

                // Log each document's content for debugging
                logger.debug { "RAG_QUERY_RESULT_${index + 1}: doc=$doc" }

                // Iterate through all map entries and output KEY: VALUE pairs
                doc.forEach { (key, value) ->
                    val stringValue = getStringValue(value)
                    if (stringValue.isNotEmpty()) {
                        appendLine("$key: $stringValue")
                    }
                }

                if (index < results.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }

        logger.debug { "RAG_QUERY_ENHANCED_RESULTS: query $queryIndex enhanced results length=${resultsContent.length}" }

        val initialResult = ToolResult.searchResult(
            toolName = "RAG_QUERY",
            resultCount = results.size,
            results = resultsContent
        )

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
                val totalResults = successResults.sumOf { result ->
                    // Extract result count from "RAG_QUERY_RESULT: Found X results" pattern
                    val firstLine = result.output.lines().firstOrNull() ?: ""
                    val countMatch = """Found (\d+) results?""".toRegex().find(firstLine)
                    countMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                
                val combinedResults = buildString {
                    successResults.forEachIndexed { index, result ->
                        if (index > 0) {
                            appendLine()
                            appendLine("---")
                            appendLine()
                        }
                        // Extract content after first line (remove "RAG_QUERY_RESULT: Found X results")
                        val lines = result.output.lines()
                        if (lines.size > 1) {
                            append(lines.drop(1).joinToString("\n"))
                        } else {
                            append(result.output)
                        }
                    }
                }
                
                ToolResult.searchResult(
                    toolName = "RAG_QUERY",
                    resultCount = totalResults,
                    queryInfo = "Multi-query search (${successResults.size} queries executed in parallel)",
                    results = combinedResults
                ).output
            } else {
                successResults.first().output
            }

        return ToolResult.success(
            toolName = "RAG_QUERY",
            summary = "Query results aggregated",
            content = aggregatedOutput
        )
    }

    /**
     * Data class to hold failure pattern analysis results
     */
    private data class FailurePatterns(
        val hasRepeatedRagFailures: Boolean = false,
        val hasHighMinScoreFailures: Boolean = false,
        val failedEmbeddingTypes: Set<String> = emptySet(),
        val previousMinScores: List<Float> = emptyList(),
        val cycleCount: Int = 0
    )
    
    /**
     * Analyze step context for RAG failure patterns to enable early adaptation
     */
    private fun analyzeStepContextForFailures(stepContext: String): FailurePatterns {
        if (stepContext.isBlank()) {
            return FailurePatterns()
        }
        
        logger.debug { "RAG_FAILURE_ANALYSIS: Analyzing step context for failure patterns" }
        
        // Count RAG_QUERY failures
        val ragFailureCount = stepContext.split("RAG_QUERY").size - 1
        val hasRepeatedRagFailures = ragFailureCount >= 2
        
        // Detect high minScore failures
        val minScoreFailureRegex = """No results found with minScore >= ([0-9.]+)""".toRegex()
        val minScoreMatches = minScoreFailureRegex.findAll(stepContext)
        val previousMinScores = minScoreMatches.map { it.groupValues[1].toFloatOrNull() ?: 0.7f }.toList()
        val hasHighMinScoreFailures = previousMinScores.any { it > 0.75f }
        
        // Detect failed embedding types
        val embeddingFailureRegex = """embedding='([^']+)'.*?RAG_QUERY_FAILED""".toRegex()
        val failedEmbeddingTypes = embeddingFailureRegex.findAll(stepContext)
            .map { it.groupValues[1] }
            .toSet()
        
        // Detect cycle patterns (same error repeated)
        val cyclePatterns = listOf(
            "RAG_QUERY_FAILED - No results found",
            "No relevant results found",
            "minScore threshold.*too high"
        )
        val cycleCount = cyclePatterns.maxOfOrNull { pattern ->
            pattern.toRegex().findAll(stepContext).count()
        } ?: 0
        
        val patterns = FailurePatterns(
            hasRepeatedRagFailures = hasRepeatedRagFailures,
            hasHighMinScoreFailures = hasHighMinScoreFailures,
            failedEmbeddingTypes = failedEmbeddingTypes,
            previousMinScores = previousMinScores,
            cycleCount = cycleCount
        )
        
        logger.debug { "RAG_FAILURE_ANALYSIS: Detected patterns - $patterns" }
        return patterns
    }

    /**
     * Attempt adaptive retry strategies when initial RAG query fails
     * Progressive fallback: lower minScore → switch embedding type → expand scope
     */
    private suspend fun attemptAdaptiveRetry(
        context: TaskContext,
        queryRequest: RagQueryRequest,
        globalParams: RagQueryParams,
        queryIndex: Int,
        originalMinScore: Float,
        modelType: ModelType,
        embedding: List<Float>,
        combinedFilter: Map<String, Any>,
        effectiveTopK: Int,
        failurePatterns: FailurePatterns
    ): ToolResult? {
        logger.debug { "RAG_ADAPTIVE_RETRY: Starting adaptive retry strategies for query $queryIndex with failure patterns: $failurePatterns" }
        
        // Early exit for excessive cycles to prevent infinite loops
        if (failurePatterns.cycleCount >= 3) {
            logger.warn { "RAG_ADAPTIVE_RETRY: Excessive cycle count (${failurePatterns.cycleCount}), skipping adaptive retry to prevent infinite loop" }
            return null
        }
        
        // Strategy 1: Progressive minScore reduction (optimized based on failure patterns)
        val minScoreStrategies = if (failurePatterns.previousMinScores.isNotEmpty()) {
            // Use smarter minScore selection based on previous attempts
            val lowestTried = failurePatterns.previousMinScores.minOrNull() ?: originalMinScore
            val averageTried = failurePatterns.previousMinScores.average().toFloat()
            
            listOf(
                maxOf(lowestTried - 0.1f, 0.4f),
                maxOf(averageTried - 0.2f, 0.3f),
                maxOf(lowestTried - 0.2f, 0.2f),
                0.15f // Very aggressive last resort
            ).filter { it < originalMinScore } // Only try lower values than already attempted
        } else {
            // Default strategy when no previous attempts
            listOf(
                maxOf(originalMinScore - 0.1f, 0.5f),
                maxOf(originalMinScore - 0.2f, 0.4f), 
                maxOf(originalMinScore - 0.3f, 0.3f),
                0.2f // Last resort very low threshold
            )
        }
        
        for ((strategyIndex, adaptiveMinScore) in minScoreStrategies.withIndex()) {
            if (adaptiveMinScore >= originalMinScore) continue // Skip if not actually lower
            
            logger.debug { "RAG_ADAPTIVE_RETRY: Strategy ${strategyIndex + 1} - trying minScore=$adaptiveMinScore (original=$originalMinScore)" }
            
            try {
                val results = vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = effectiveTopK,
                    minScore = adaptiveMinScore,
                    filter = combinedFilter,
                )
                
                if (results.isNotEmpty()) {
                    logger.info { "RAG_ADAPTIVE_SUCCESS: Strategy ${strategyIndex + 1} succeeded with ${results.size} results (minScore=$adaptiveMinScore)" }
                    logger.info { "RAG_ADAPTIVE_METRICS: MINSCORE_REDUCTION_SUCCESS - original=$originalMinScore, successful=$adaptiveMinScore, attempt=${strategyIndex + 1}, resultCount=${results.size}" }
                    return buildSuccessResult(results, queryIndex, "Adaptive retry succeeded with lowered minScore ($adaptiveMinScore)")
                }
            } catch (e: Exception) {
                logger.warn(e) { "RAG_ADAPTIVE_RETRY: Strategy ${strategyIndex + 1} search failed" }
            }
        }
        
        // Strategy 2: Switch embedding type (text ↔ code) with failure pattern awareness
        val alternativeModelType = when (modelType) {
            ModelType.EMBEDDING_TEXT -> ModelType.EMBEDDING_CODE
            ModelType.EMBEDDING_CODE -> ModelType.EMBEDDING_TEXT
            else -> null
        }
        
        val alternativeEmbeddingString = when (alternativeModelType) {
            ModelType.EMBEDDING_TEXT -> "text"
            ModelType.EMBEDDING_CODE -> "code"
            else -> null
        }
        
        val shouldTryAlternativeEmbedding = alternativeModelType != null && 
                alternativeEmbeddingString != null &&
                !failurePatterns.failedEmbeddingTypes.contains(alternativeEmbeddingString)
        
        if (shouldTryAlternativeEmbedding && alternativeModelType != null && alternativeEmbeddingString != null) {
            logger.debug { "RAG_ADAPTIVE_RETRY: Strategy - switching embedding type from $modelType to $alternativeModelType" }
            
            try {
                val alternativeEmbedding = embeddingGateway.callEmbedding(alternativeModelType, queryRequest.query)
                
                if (alternativeEmbedding.isNotEmpty()) {
                    // Try with reasonable minScore for alternative embedding
                    val alternativeMinScore = 0.6f
                    
                    val results = vectorStorage.search(
                        collectionType = alternativeModelType,
                        query = alternativeEmbedding,
                        limit = effectiveTopK,
                        minScore = alternativeMinScore,
                        filter = combinedFilter,
                    )
                    
                    if (results.isNotEmpty()) {
                        logger.info { "RAG_ADAPTIVE_SUCCESS: Alternative embedding type succeeded with ${results.size} results ($alternativeModelType)" }
                        logger.info { "RAG_ADAPTIVE_METRICS: EMBEDDING_SWITCH_SUCCESS - original=$modelType, successful=$alternativeModelType, minScore=$alternativeMinScore, resultCount=${results.size}" }
                        return buildSuccessResult(results, queryIndex, "Adaptive retry succeeded with alternative embedding type ($alternativeModelType)")
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "RAG_ADAPTIVE_RETRY: Alternative embedding type strategy failed" }
            }
        }
        
        // Strategy 3: Expand search scope (remove project/client filters)
        if (combinedFilter.isNotEmpty() && !globalParams.globalSearch) {
            logger.debug { "RAG_ADAPTIVE_RETRY: Strategy - expanding search scope (globalSearch=true)" }
            
            try {
                val results = vectorStorage.search(
                    collectionType = modelType,
                    query = embedding,
                    limit = effectiveTopK,
                    minScore = 0.5f, // Use reasonable minScore for global search
                    filter = emptyMap(), // Remove all filters for global search
                )
                
                if (results.isNotEmpty()) {
                    logger.info { "RAG_ADAPTIVE_SUCCESS: Global search succeeded with ${results.size} results" }
                    logger.info { "RAG_ADAPTIVE_METRICS: GLOBAL_SEARCH_SUCCESS - originalScope=project, expandedScope=global, minScore=0.5, resultCount=${results.size}" }
                    return buildSuccessResult(results, queryIndex, "Adaptive retry succeeded with global search scope")
                }
            } catch (e: Exception) {
                logger.warn(e) { "RAG_ADAPTIVE_RETRY: Global search strategy failed" }
            }
        }
        
        // Comprehensive failure reporting for debugging and metrics
        logger.warn { "RAG_ADAPTIVE_FAILURE: All adaptive strategies failed for query $queryIndex" }
        logger.warn { "RAG_ADAPTIVE_METRICS: COMPREHENSIVE_FAILURE_REPORT - query='${queryRequest.query}'" }
        logger.warn { "RAG_ADAPTIVE_METRICS: - originalMinScore=$originalMinScore, originalModelType=$modelType" }
        logger.warn { "RAG_ADAPTIVE_METRICS: - failurePatterns=$failurePatterns" }
        logger.warn { "RAG_ADAPTIVE_METRICS: - minScoreStrategiesAttempted=${minScoreStrategies.size}, values=${minScoreStrategies.joinToString(",")}" }
        
        val failureReportEmbeddingType = when (alternativeModelType) {
            ModelType.EMBEDDING_TEXT -> "text"
            ModelType.EMBEDDING_CODE -> "code"
            else -> "none"
        }
        val embeddingSkipReason = if (!failurePatterns.failedEmbeddingTypes.contains(failureReportEmbeddingType)) {
            "attempted"
        } else {
            "skipped_due_to_previous_failure"
        }
        logger.warn { "RAG_ADAPTIVE_METRICS: - embeddingTypeSwitch=$failureReportEmbeddingType, status=$embeddingSkipReason" }
        
        val globalSearchAttempted = combinedFilter.isNotEmpty() && !globalParams.globalSearch
        logger.warn { "RAG_ADAPTIVE_METRICS: - globalSearchAttempted=$globalSearchAttempted" }
        logger.warn { "RAG_ADAPTIVE_METRICS: - cyclePreventionTriggered=${failurePatterns.cycleCount >= 3}" }
        
        return null
    }
    
    /**
     * Build success result from search results with adaptive context
     */
    private fun buildSuccessResult(results: List<Map<String, JsonWithInt.Value>>, queryIndex: Int, adaptiveNote: String): ToolResult {
        val resultsContent = buildString {
            appendLine("=== ADAPTIVE RAG SUCCESS ===")
            appendLine(adaptiveNote)
            appendLine()
            
            results.forEachIndexed { index, doc ->
                append("Result ${index + 1}:")
                appendLine()
                
                doc.forEach { (key, value) ->
                    val stringValue = getStringValue(value)
                    if (stringValue.isNotEmpty()) {
                        appendLine("$key: $stringValue")
                    }
                }
                
                if (index < results.size - 1) {
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
            }
        }
        
        logger.debug { "RAG_ADAPTIVE_RESULTS: query $queryIndex adaptive results length=${resultsContent.length}" }
        
        return ToolResult.searchResult(
            toolName = "RAG_QUERY",
            resultCount = results.size,
            queryInfo = adaptiveNote,
            results = resultsContent
        )
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
