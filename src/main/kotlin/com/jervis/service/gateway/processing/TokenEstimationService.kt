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
     * Estimates token count for given text using character-based approximation.
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
}
