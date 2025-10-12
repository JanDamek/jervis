package com.jervis.service.gateway.processing

import org.springframework.stereotype.Service

/**
 * Service responsible for estimating token counts for LLM requests.
 * Uses rough approximation algorithms to estimate tokens before making actual LLM calls.
 */
@Service
class TokenEstimationService {
    companion object {
        private const val CHARACTERS_PER_TOKEN = 4.0
        private const val SAFETY_BUFFER = 10
        private const val RESPONSE_TOKEN_BUFFER = 500
    }

    /**
     * Estimates token count for a given text using character-based approximation.
     * Rule of thumb: 1 token ≈ 4 characters
     */
    fun estimateTokens(text: String): Int = (text.length / CHARACTERS_PER_TOKEN).toInt() + SAFETY_BUFFER

    /**
     * Estimates total tokens needed for a complete LLM request including system prompt,
     * user prompt, and response buffer.
     */
    fun estimateTotalTokensNeeded(
        systemPrompt: String?,
        userPrompt: String,
    ): Int {
        val systemTokens = systemPrompt?.let { estimateTokens(it) } ?: 0
        val userTokens = estimateTokens(userPrompt)
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
        val tokenCount = estimateTokens(llmOutput)

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
            val sentenceTokens = estimateTokens(sentenceWithPeriod)

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
                        val wordTokenCount = estimateTokens("$word ")
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
}
