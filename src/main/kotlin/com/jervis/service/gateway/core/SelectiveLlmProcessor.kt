package com.jervis.service.gateway.core

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.service.gateway.processing.TokenEstimationService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Service for handling token limit overflow by processing large prompts in chunks.
 * This service ensures that when model token limits are exceeded, prompts are split
 * and processed selectively without causing infinite recursion with LlmGateway.
 *
 * Key design principles:
 * - NO direct calls to LlmGateway.callLlm to prevent circular dependency
 * - Uses provided executor function to avoid infinite recursion
 * - Intelligent chunking based on content structure
 * - Proper error handling for chunked processing
 */
@Service
class SelectiveLlmProcessor(
    private val tokenEstimationService: TokenEstimationService,
) {
    private val logger = KotlinLogging.logger {}

    data class ChunkResult<T>(
        val result: T?,
        val chunkIndex: Int,
        val success: Boolean,
        val errorMessage: String? = null,
    )

    data class SelectiveProcessingResult<T>(
        val combinedResult: T?,
        val processedChunks: Int,
        val failedChunks: Int,
        val success: Boolean,
    )

    /**
     * Process a large prompt by chunking it when token limits are exceeded.
     * Uses an executor function to avoid direct dependency on LlmGateway.
     *
     * @param type The prompt type for context
     * @param systemPrompt The system prompt (usually smaller, kept intact)
     * @param userPrompt The user prompts to chunk if needed
     * @param maxTokensPerChunk Maximum tokens per chunk
     * @param executor Function to execute LLM calls (avoids circular dependency)
     * @return The combined result from all chunks
     */
    suspend fun <T : Any> processWithTokenLimitHandling(
        type: PromptTypeEnum,
        systemPrompt: String,
        userPrompt: String,
        maxTokensPerChunk: Int = 16000, // Conservative limit for most models
        executor: suspend (systemPrompt: String, userPrompt: String) -> T,
    ): SelectiveProcessingResult<T> {
        val totalTokens = tokenEstimationService.estimateTotalTokensNeeded(systemPrompt, userPrompt)

        logger.debug { "Processing prompt with $totalTokens tokens for type: $type" }

        // If within limits, process normally
        if (totalTokens <= maxTokensPerChunk) {
            logger.debug { "Prompt within token limits, processing normally" }
            return try {
                val result = executor(systemPrompt, userPrompt)
                SelectiveProcessingResult(
                    combinedResult = result,
                    processedChunks = 1,
                    failedChunks = 0,
                    success = true,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to process single prompt for type: $type" }
                SelectiveProcessingResult(
                    combinedResult = null,
                    processedChunks = 0,
                    failedChunks = 1,
                    success = false,
                )
            }
        }

        // Token limit exceeded, process in chunks
        logger.info { "Token limit exceeded ($totalTokens > $maxTokensPerChunk), processing in chunks for type: $type" }

        val chunks = chunkUserPrompt(userPrompt, maxTokensPerChunk, systemPrompt)
        logger.info { "Split prompt into ${chunks.size} chunks for processing" }

        return processChunks(systemPrompt, chunks, type, executor)
    }

    /**
     * Chunk the user prompt into smaller pieces while preserving context.
     */
    private fun chunkUserPrompt(
        userPrompt: String,
        maxTokensPerChunk: Int,
        systemPrompt: String,
    ): List<String> {
        val systemTokens = tokenEstimationService.estimateTokens(systemPrompt)
        val availableTokensPerChunk = maxTokensPerChunk - systemTokens - 200 // Buffer for response

        if (availableTokensPerChunk <= 0) {
            logger.warn { "System prompt is too large, using minimal chunking" }
            return listOf(userPrompt) // Fallback to a single chunk
        }

        val chunks = mutableListOf<String>()
        val lines = userPrompt.lines()
        var currentChunk = StringBuilder()

        for (line in lines) {
            val lineTokens = tokenEstimationService.estimateTokens(line)
            val potentialTokens = tokenEstimationService.estimateTokens(currentChunk.toString()) + lineTokens

            if (potentialTokens > availableTokensPerChunk && currentChunk.isNotEmpty()) {
                // Finalize current chunk
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder(line)
            } else {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append("\n")
                }
                currentChunk.append(line)
            }
        }

        // Add remaining content
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString().trim())
        }

        // Ensure we have at least one chunk
        if (chunks.isEmpty()) {
            chunks.add(userPrompt)
        }

        return chunks
    }

    /**
     * Process chunks using the provided executor function.
     */
    private suspend fun <T : Any> processChunks(
        systemPrompt: String,
        chunks: List<String>,
        type: PromptTypeEnum,
        executor: suspend (systemPrompt: String, userPrompt: String) -> T,
    ): SelectiveProcessingResult<T> =
        coroutineScope {
            val chunkResults =
                chunks.mapIndexed { index, chunk ->
                    async {
                        try {
                            logger.debug { "Processing chunk ${index + 1}/${chunks.size} for type: $type" }
                            val result = executor(systemPrompt, chunk)
                            ChunkResult(
                                result = result,
                                chunkIndex = index,
                                success = true,
                            )
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to process chunk ${index + 1}/${chunks.size} for type: $type" }
                            ChunkResult<T>(
                                result = null,
                                chunkIndex = index,
                                success = false,
                                errorMessage = e.message,
                            )
                        }
                    }
                }

            val results = chunkResults.awaitAll()
            val successfulResults = results.filter { it.success && it.result != null }
            val failedCount = results.count { !it.success }

            logger.info {
                "Chunk processing completed for type: $type - " +
                    "Successful: ${successfulResults.size}, Failed: $failedCount"
            }

            // For now, return the first successful result
            // In the future; this could be enhanced to combine results intelligently
            val combinedResult = successfulResults.firstOrNull()?.result

            SelectiveProcessingResult(
                combinedResult = combinedResult,
                processedChunks = successfulResults.size,
                failedChunks = failedCount,
                success = successfulResults.isNotEmpty(),
            )
        }
}
