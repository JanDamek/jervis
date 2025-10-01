package com.jervis.service.mcp.tools

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.domain.model.ModelType
import com.jervis.domain.plan.Plan
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.mcp.McpTool
import com.jervis.service.mcp.domain.ToolResult
import com.jervis.service.prompts.PromptRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.springframework.stereotype.Service
import kotlin.math.sqrt

/**
 * Knowledge search tool implementing threshold-first RAG strategy.
 *
 * Pipeline:
 * 1. Qdrant recall with score threshold ≥ 0.7 (get hundreds of chunks for maximum coverage)
 * 2. Deduplication + Clustering (reduce to 20-30 unique clusters)
 * 3. Query-driven filtering using Qwen 7B (select 5-15 relevant clusters)
 * 4. Answer drafting using Qwen 14B (generate final answer)
 * 5. Token management to stay within the 128k limit
 */
@Service
class KnowledgeSearchTool(
    private val llmGateway: LlmGateway,
    private val vectorStorage: VectorStorageRepository,
    private val embeddingGateway: EmbeddingGateway,
    override val promptRepository: PromptRepository,
    private val tokenEstimationService: TokenEstimationService,
) : McpTool {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val THRESHOLD_SCORE = 0.7f
        private const val MAX_INITIAL_RESULTS = 10000 // Get everything above a threshold
        private const val CLUSTERING_SIMILARITY_THRESHOLD = 0.95f
        private const val MAX_CLUSTERS_AFTER_DEDUP = 30
        private const val MAX_FILTERED_CLUSTERS = 15
        private const val MAX_HIT_CONTENT_LENGTH = 2000
        private const val MAX_TOKENS_LIMIT = 128000 // 128k tokens including response
    }

    override val name: PromptTypeEnum = PromptTypeEnum.KNOWLEDGE_SEARCH

    @Serializable
    data class KnowledgeSearchParams(
        val searchTerms: String = "",
        val scoreThreshold: Float = THRESHOLD_SCORE,
        val global: Boolean = false,
    )

    @Serializable
    data class DocumentChunk(
        val content: String,
        val score: Double,
        val metadata: Map<String, String>,
        val embedding: List<Float> = emptyList(),
    )

    @Serializable
    data class DocumentCluster(
        val representativeChunk: DocumentChunk,
        val allChunks: List<DocumentChunk>,
        val clusterSummary: String = "",
        val averageScore: Double = 0.0,
    )

    @Serializable
    data class ClusterSummary(
        val summary: String,
        val averageScore: Double,
        val chunkCount: Int,
    )

    @Serializable
    data class FilterResponse(
        val relevantClusters: List<Int> = emptyList(),
    )

    private suspend fun parseTaskDescription(
        taskDescription: String,
        context: TaskContext,
        stepContext: String = "",
    ): KnowledgeSearchParams {
        val mappingValue =
            mapOf(
                "taskDescription" to taskDescription,
                "stepContext" to stepContext,
                "clientName" to context.clientDocument.name,
                "projectName" to context.projectDocument.name,
            )

        val llmResponse =
            llmGateway.callLlm(
                type = PromptTypeEnum.KNOWLEDGE_SEARCH,
                responseSchema = KnowledgeSearchParams(),
                quick = context.quick,
                mappingValue = mappingValue,
            )
        return llmResponse
    }

    override suspend fun execute(
        context: TaskContext,
        plan: Plan,
        taskDescription: String,
        stepContext: String,
    ): ToolResult {
        val parsed = parseTaskDescription(taskDescription, context, stepContext)

        return executeKnowledgeSearchOperation(parsed, context, taskDescription)
    }

    private suspend fun executeKnowledgeSearchOperation(
        params: KnowledgeSearchParams,
        context: TaskContext,
        originalQuery: String = "",
    ): ToolResult {
        return try {
            // Validate search terms
            if (params.searchTerms.isBlank()) {
                return ToolResult.success(
                    toolName = "KNOWLEDGE_SEARCH",
                    summary = "No search terms provided",
                    content = "Search terms were empty or blank. Please provide valid search terms.",
                )
            }

            logger.info { "KNOWLEDGE_SEARCH_START: Processing query '${params.searchTerms}' with threshold-first RAG" }

            // Stage 1: Threshold-first recall
            val chunks = thresholdFirstRecall(params, context)
            if (chunks.isEmpty()) {
                return ToolResult.success(
                    toolName = "KNOWLEDGE_SEARCH",
                    summary = "No results found above threshold",
                    content =
                        "No documents found with score >= ${params.scoreThreshold}. " +
                            "This indicates no highly relevant information is available in the knowledge base.",
                )
            }

            // Stage 2: Deduplication + Clustering
            val clusters = deduplicateAndCluster(chunks)

            // Stage 3: Query-driven filtering
            val filteredClusters = filterRelevantClusters(clusters, originalQuery, context)

            // Stage 4: Answer drafting with token management
            val finalAnswer = draftAnswerWithTokenManagement(filteredClusters, originalQuery, context)

            logger.info {
                "KNOWLEDGE_SEARCH_COMPLETE: Processed ${chunks.size} chunks → ${clusters.size} clusters → ${filteredClusters.size} filtered"
            }

            ToolResult.success(
                toolName = "KNOWLEDGE_SEARCH",
                summary = "Threshold-first RAG completed with ${filteredClusters.size} relevant clusters",
                content = finalAnswer,
            )
        } catch (e: Exception) {
            logger.error(e) { "KNOWLEDGE_SEARCH_ERROR: Search operation failed" }
            ToolResult.error(
                output = "Search failed: ${e.message}",
                message = e.toString(),
            )
        }
    }

    /**
     * Stage 1: Threshold-first Qdrant recall
     * Returns all chunks with score >= 0.7 for maximum coverage
     */
    private suspend fun thresholdFirstRecall(
        params: KnowledgeSearchParams,
        context: TaskContext,
    ): List<DocumentChunk> {
        logger.info { "THRESHOLD_RECALL_START: Starting threshold-first recall for '${params.searchTerms}'" }

        val (projectId, clientId) =
            when {
                params.global -> null to null
                else -> context.projectDocument.id.toString() to context.clientDocument.id.toString()
            }

        return coroutineScope {
            val textResultsDeferred =
                async {
                    searchWithThresholdFirst(ModelType.EMBEDDING_TEXT, params, projectId, clientId)
                }
            val codeResultsDeferred =
                async {
                    searchWithThresholdFirst(ModelType.EMBEDDING_CODE, params, projectId, clientId)
                }

            val textResults = textResultsDeferred.await()
            val codeResults = codeResultsDeferred.await()

            val allChunks =
                (textResults + codeResults)
                    .sortedByDescending { it.score }

            logger.info { "THRESHOLD_RECALL_COMPLETE: Retrieved ${allChunks.size} chunks above threshold ${params.scoreThreshold}" }
            allChunks
        }
    }

    private suspend fun searchWithThresholdFirst(
        modelType: ModelType,
        params: KnowledgeSearchParams,
        projectId: String?,
        clientId: String?,
    ): List<DocumentChunk> {
        val embedding = embeddingGateway.callEmbedding(modelType, params.searchTerms)

        return vectorStorage
            .search(
                collectionType = modelType,
                query = embedding,
                limit = MAX_INITIAL_RESULTS,
                minScore = params.scoreThreshold,
                projectId = projectId,
                clientId = clientId,
                filter = null,
            ).map { result ->
                val score = (result["_score"]?.doubleValue ?: 0.0)
                val content = result["summary"]?.stringValue ?: result["content"]?.stringValue ?: ""
                val metadata =
                    result.mapValues { (_, value) ->
                        when {
                            value.hasStringValue() -> value.stringValue
                            value.hasIntegerValue() -> value.integerValue.toString()
                            value.hasDoubleValue() -> value.doubleValue.toString()
                            value.hasBoolValue() -> value.boolValue.toString()
                            else -> value.toString()
                        }
                    }

                DocumentChunk(
                    content = content.take(MAX_HIT_CONTENT_LENGTH),
                    score = score,
                    metadata = metadata,
                    embedding = embedding, // Store for clustering
                )
            }
    }

    /**
     * Stage 2: Deduplication + Clustering
     * Groups similar chunks and creates cluster summaries
     */
    private suspend fun deduplicateAndCluster(chunks: List<DocumentChunk>): List<DocumentCluster> {
        if (chunks.isEmpty()) return emptyList()

        logger.info { "CLUSTERING_START: Starting deduplication and clustering of ${chunks.size} chunks" }

        // First, deduplicate by content similarity
        val deduplicatedChunks = deduplicateByContent(chunks)
        logger.debug { "After deduplication: ${deduplicatedChunks.size} unique chunks" }

        // Then cluster by semantic similarity
        val clusters = clusterBySimilarity(deduplicatedChunks)
        logger.info { "CLUSTERING_COMPLETE: Created ${clusters.size} clusters" }

        return clusters.take(MAX_CLUSTERS_AFTER_DEDUP)
    }

    private fun deduplicateByContent(chunks: List<DocumentChunk>): List<DocumentChunk> {
        val uniqueChunks = mutableListOf<DocumentChunk>()
        val seenContent = mutableSetOf<String>()

        for (chunk in chunks) {
            val contentKey = "${chunk.metadata["projectId"]}-${chunk.metadata["path"]}-${chunk.metadata["lineRange"]}"
            if (contentKey !in seenContent) {
                seenContent.add(contentKey)
                uniqueChunks.add(chunk)
            }
        }

        return uniqueChunks
    }

    private fun clusterBySimilarity(chunks: List<DocumentChunk>): List<DocumentCluster> {
        if (chunks.isEmpty()) return emptyList()

        val clusters = mutableListOf<DocumentCluster>()
        val processed = mutableSetOf<Int>()

        for (i in chunks.indices) {
            if (i in processed) continue

            val currentChunk = chunks[i]
            val similarChunks = mutableListOf(currentChunk)
            processed.add(i)

            // Find similar chunks based on embedding similarity
            for (j in i + 1 until chunks.size) {
                if (j in processed) continue

                val similarity = cosineSimilarity(currentChunk.embedding, chunks[j].embedding)
                if (similarity > CLUSTERING_SIMILARITY_THRESHOLD) {
                    similarChunks.add(chunks[j])
                    processed.add(j)
                }
            }

            // Create cluster with the best chunk as representative
            val representative = similarChunks.maxByOrNull { it.score } ?: currentChunk
            val averageScore = similarChunks.map { it.score }.average()

            clusters.add(
                DocumentCluster(
                    representativeChunk = representative,
                    allChunks = similarChunks,
                    averageScore = averageScore,
                ),
            )
        }

        return clusters.sortedByDescending { it.averageScore }
    }

    private fun cosineSimilarity(
        a: List<Float>,
        b: List<Float>,
    ): Double {
        if (a.size != b.size) return 0.0

        val dotProduct = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val normA = sqrt(a.sumOf { (it * it).toDouble() })
        val normB = sqrt(b.sumOf { (it * it).toDouble() })

        return if (normA == 0.0 || normB == 0.0) 0.0 else dotProduct / (normA * normB)
    }

    /**
     * Stage 3: Query-driven filtering using Qwen 7B
     * Selects only clusters relevant to the query
     */
    private suspend fun filterRelevantClusters(
        clusters: List<DocumentCluster>,
        originalQuery: String,
        context: TaskContext,
    ): List<DocumentCluster> {
        if (clusters.isEmpty()) return emptyList()

        logger.info { "FILTERING_START: Filtering ${clusters.size} clusters using Qwen 7B" }

        // Create cluster summaries for filtering
        val clusterSummaries =
            clusters.map { cluster ->
                ClusterSummary(
                    summary = cluster.representativeChunk.content.take(500),
                    averageScore = cluster.averageScore,
                    chunkCount = cluster.allChunks.size,
                )
            }

        val mappingValue =
            mapOf(
                "originalQuery" to originalQuery,
                "clusters" to
                    clusterSummaries
                        .mapIndexed { index, summary ->
                            "Cluster $index (score: ${
                                String.format(
                                    "%.3f",
                                    summary.averageScore,
                                )
                            }, chunks: ${summary.chunkCount}):\n${summary.summary}"
                        }.joinToString("\n\n---\n\n"),
            )

        return try {
            val filterResponse =
                llmGateway.callLlm(
                    type = PromptTypeEnum.RAG_RESULTS_SYNTHESIS, // Using RAG model (Qwen 7B with 128k tokens)
                    responseSchema = FilterResponse(),
                    mappingValue =
                        mappingValue +
                            mapOf(
                                "filterInstruction" to
                                    "From the following information clusters, select only those that are truly relevant to the query. " +
                                    "Return the indices of relevant clusters (0-based) as a comma-separated list.",
                            ),
                    quick = context.quick,
                )

            val relevantIndices = filterResponse.relevantClusters.take(MAX_FILTERED_CLUSTERS)
            val filteredClusters =
                relevantIndices.mapNotNull { index ->
                    clusters.getOrNull(index)
                }

            logger.info { "FILTERING_COMPLETE: Selected ${filteredClusters.size} relevant clusters" }
            filteredClusters
        } catch (e: Exception) {
            logger.warn(e) { "Cluster filtering failed, returning top clusters by score" }
            clusters.take(MAX_FILTERED_CLUSTERS)
        }
    }

    /**
     * Stage 4: Answer drafting with token management using Qwen 14B
     * Generates the final answer from selected clusters with 128k token limit
     */
    private suspend fun draftAnswerWithTokenManagement(
        filteredClusters: List<DocumentCluster>,
        originalQuery: String,
        context: TaskContext,
    ): String {
        if (filteredClusters.isEmpty()) {
            return "No relevant information found in the knowledge base."
        }

        logger.info { "ANSWER_DRAFTING_START: Drafting answer using Qwen 14B with ${filteredClusters.size} clusters" }

        // Build content within token limits
        val clusterContents = buildContentWithinTokenLimit(filteredClusters, originalQuery)

        val mappingValue =
            mapOf(
                "originalQuery" to originalQuery,
                "clusteredContent" to clusterContents,
                "contextInfo" to "${context.clientDocument.name} - ${context.projectDocument.name}",
            )

        return try {
            val answer =
                llmGateway.callLlm(
                    type = PromptTypeEnum.PLANNING_CREATE_PLAN, // Using PLANNER model (Qwen 14B)
                    responseSchema = "",
                    mappingValue =
                        mappingValue +
                            mapOf(
                                "answerInstruction" to
                                    "Based on the following clustered information, provide a comprehensive answer to the query. " +
                                    "Focus on accuracy and completeness.",
                            ),
                    quick = false, // Use full model capabilities
                )

            logger.info { "ANSWER_DRAFTING_COMPLETE: Answer drafted successfully" }
            answer
        } catch (e: Exception) {
            logger.error(e) { "Answer drafting failed" }
            "Error generating answer: ${e.message}"
        }
    }

    /**
     * Builds cluster content while respecting the 128k token limit
     */
    private fun buildContentWithinTokenLimit(
        filteredClusters: List<DocumentCluster>,
        originalQuery: String,
    ): String {
        val queryTokens = estimateTokens(originalQuery)
        val responseReserve = 10000 // Reserve tokens for response
        val availableTokens = MAX_TOKENS_LIMIT - queryTokens - responseReserve

        var currentTokens = 0
        val includedClusters = mutableListOf<String>()

        for ((index, cluster) in filteredClusters.withIndex()) {
            val allContent =
                cluster.allChunks.joinToString("\n") { chunk ->
                    "Content: ${chunk.content}\nScore: ${
                        String.format(
                            "%.3f",
                            chunk.score,
                        )
                    }\nSource: ${chunk.metadata["path"] ?: "unknown"}"
                }
            val clusterText = "Cluster ${index + 1}:\n$allContent"
            val clusterTokens = estimateTokens(clusterText)

            if (currentTokens + clusterTokens <= availableTokens) {
                includedClusters.add(clusterText)
                currentTokens += clusterTokens
            } else {
                logger.warn { "Token limit reached, including ${includedClusters.size}/${filteredClusters.size} clusters" }
                break
            }
        }

        return includedClusters.joinToString("\n\n" + "=".repeat(50) + "\n\n")
    }

    /**
     * Estimates token count using the TokenEstimationService
     */
    private fun estimateTokens(text: String): Int = tokenEstimationService.estimateTokens(text)
}
