package com.jervis.service.rag.pipeline

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.context.TaskContext
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.gateway.processing.TokenEstimationService
import com.jervis.service.rag.RagService
import com.jervis.service.rag.domain.AnswerResponse
import com.jervis.service.rag.domain.DocumentChunk
import mu.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Content synthesis strategy that uses LLM to generate a final answer.
 * Combines all filtered chunks from multiple queries into a comprehensive response.
 */
@Component
class LlmContentSynthesisStrategy(
    private val llmGateway: LlmGateway,
    private val tokenEstimationService: TokenEstimationService,
) : ContentSynthesisStrategy {
    companion object {
        private val logger = KotlinLogging.logger {}
        private const val MAX_TOKENS_LIMIT = 128000
        private const val RESPONSE_RESERVE_TOKENS = 10000
    }

    override suspend fun synthesize(
        queryResults: List<RagService.QueryResult>,
        originalQuery: String,
        context: TaskContext,
    ): String {
        logger.info { "SYNTHESIS: Synthesizing ${queryResults.size} query results" }

        val allFilteredChunks = queryResults.flatMap { it.filteredChunks }
        if (allFilteredChunks.isEmpty()) {
            logger.info { "SYNTHESIS: No relevant information found across all queries" }
            return "No relevant information found in the knowledge base for this query."
        }

        val content = buildContentWithinTokenLimit(allFilteredChunks, originalQuery)
        return requestSynthesis(content, originalQuery, context)
    }

    private fun buildContentWithinTokenLimit(
        chunks: List<DocumentChunk>,
        query: String,
    ): String {
        val availableTokens = MAX_TOKENS_LIMIT - estimateTokens(query) - RESPONSE_RESERVE_TOKENS

        data class ChunkAccumulator(
            val content: List<String>,
            val tokens: Int,
            val shouldStop: Boolean,
        )

        val result =
            chunks
                .withIndex()
                .fold(ChunkAccumulator(emptyList(), 0, false)) { acc, (index, chunk) ->
                    acc.shouldStop.takeIf { it }?.let { acc } ?: run {
                        val chunkContent = formatChunk(chunk, index)
                        val chunkTokens = estimateTokens(chunkContent)
                        val newTokens = acc.tokens + chunkTokens

                        (newTokens > availableTokens).takeIf { it }?.let {
                            logger.warn { "Token limit reached: including ${acc.content.size}/${chunks.size} chunks" }
                            acc.copy(shouldStop = true)
                        } ?: acc.copy(
                            content = acc.content + chunkContent,
                            tokens = newTokens,
                        )
                    }
                }

        require(result.content.isNotEmpty()) { "No chunks fit within token limit" }
        return result.content.joinToString("\n\n${"=".repeat(50)}\n\n")
    }

    private fun formatChunk(
        chunk: DocumentChunk,
        index: Int,
    ): String =
        "Result ${index + 1}:\n" +
            "Content: ${chunk.content}\n" +
            "Score: ${String.format("%.3f", chunk.score)}\n" +
            "Source: ${chunk.metadata["path"] ?: "unknown"}"

    private suspend fun requestSynthesis(
        content: String,
        query: String,
        context: TaskContext,
    ): String {
        val mappingValue =
            mapOf(
                "originalQuery" to query,
                "clusteredContent" to content,
                "contextInfo" to "${context.clientDocument.name} - ${context.projectDocument.name}",
                "answerInstruction" to
                    "Based on the retrieved information, provide a comprehensive, accurate answer. " +
                    "Focus on completeness and technical precision.",
            )

        val response =
            llmGateway.callLlm(
                type = PromptTypeEnum.PLANNING_CREATE_PLAN_TOOL,
                responseSchema = AnswerResponse(),
                mappingValue = mappingValue,
                quick = false,
            )

        return response.result.answer
    }

    private fun estimateTokens(text: String): Int = tokenEstimationService.estimateTokens(text)
}
