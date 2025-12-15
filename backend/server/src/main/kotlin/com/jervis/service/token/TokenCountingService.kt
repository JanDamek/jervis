package com.jervis.service.token

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import mu.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Token counting service using jtokkit (OpenAI tiktoken port) for accurate BPE token counting.
 * Uses cl100k_base encoding (GPT-4, GPT-3.5-turbo, text-embedding-ada-002).
 */
@Service
class TokenCountingService {
    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()
    private val encoding: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    companion object {
        private const val RESPONSE_TOKEN_BUFFER = 500
    }

    /**
     * Count tokens in a single text string using BPE encoding.
     */
    fun countTokens(text: String): Int =
        runCatching {
            encoding.countTokens(text)
        }.getOrElse { e ->
            logger.warn(e) { "Failed to count tokens, falling back to approximate calculation" }
            approximateTokenCount(text)
        }

    /**
     * Count tokens across multiple text strings.
     */
    fun countTokens(texts: List<String>): Int = texts.sumOf { countTokens(it) }

    /**
     * Estimate tokens for a single text (alias for countTokens).
     */
    fun estimateTokens(text: String): Int = countTokens(text)

    /**
     * Estimate total tokens needed for a complete LLM request including system prompt,
     * user prompt, and response buffer.
     */
    fun estimateTotalTokensNeeded(
        systemPrompt: String?,
        userPrompt: String,
    ): Int {
        val systemTokens = systemPrompt?.let { countTokens(it) } ?: 0
        val userTokens = countTokens(userPrompt)
        return systemTokens + userTokens + RESPONSE_TOKEN_BUFFER
    }

    /**
     * Process LLM output with token limit checking and chunking.
     * Chunks text to stay within specified token limit using sentence boundaries.
     */
    fun processLlmOutputWithTokenLimit(
        llmOutput: String,
        contextName: String,
        maxTokens: Int,
    ): String {
        val tokenCount = countTokens(llmOutput)

        if (tokenCount <= maxTokens) {
            return llmOutput
        }

        // Simple chunking by sentences to stay within token limit
        val sentences = llmOutput.split(". ", "! ", "? ").filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        var currentTokens = 0

        for (sentence in sentences) {
            val sentenceWithPeriod =
                if (sentence.endsWith(".") || sentence.endsWith("!") || sentence.endsWith("?")) {
                    sentence
                } else {
                    "$sentence."
                }
            val sentenceTokens = countTokens(sentenceWithPeriod)

            if (currentTokens + sentenceTokens <= maxTokens) {
                if (currentChunk.isNotEmpty()) currentChunk.append(" ")
                currentChunk.append(sentenceWithPeriod)
                currentTokens += sentenceTokens
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk.toString())
                    currentChunk = StringBuilder(sentenceWithPeriod)
                    currentTokens = sentenceTokens
                } else {
                    // Single sentence too long, truncate it
                    val words = sentenceWithPeriod.split(" ")
                    var truncated = StringBuilder()
                    var wordTokens = 0

                    for (word in words) {
                        val wordTokenCount = countTokens("$word ")
                        if (wordTokens + wordTokenCount <= maxTokens - 10) { // Leave buffer
                            truncated.append("$word ")
                            wordTokens += wordTokenCount
                        } else {
                            break
                        }
                    }

                    chunks.add(truncated.toString().trim() + "...")
                    break
                }
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        // Return first chunk as the main summary
        return chunks.firstOrNull() ?: llmOutput.take(1000) + "..."
    }

    private fun approximateTokenCount(text: String): Int = (text.length / 4.0).toInt()
}
