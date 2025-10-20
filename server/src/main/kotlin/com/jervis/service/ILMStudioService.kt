package com.jervis.service

import com.jervis.dto.completion.ChatCompletionRequest
import com.jervis.dto.completion.ChatCompletionResponse
import com.jervis.dto.completion.CompletionRequest
import com.jervis.dto.completion.CompletionResponse
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/**
 * HTTP Exchange interface for LMStudio API compatibility.
 * Provides OpenAI-compatible endpoints for text and chat completions.
 */
@HttpExchange("/api/v0")
interface ILMStudioService {
    /**
     * Get available models.
     * Returns a list of projects formatted as model endpoints.
     *
     * @return Map containing models list and metadata
     */
    @GetExchange("/models")
    suspend fun getModels(): Map<String, Any>

    /**
     * Text completion endpoint (OpenAI-compatible).
     * Processes a completion request and returns generated text.
     *
     * @param request Completion request with prompt and parameters
     * @return Completion response with generated text
     */
    @PostExchange("/completions")
    suspend fun getCompletion(
        @RequestBody request: CompletionRequest,
    ): CompletionResponse

    /**
     * Chat completion endpoint (OpenAI-compatible).
     * Processes a chat request with message history and returns assistant response.
     *
     * @param chatRequest Chat completion request with messages
     * @return Chat completion response with assistant message
     */
    @PostExchange("/chat/completions")
    suspend fun getChatCompletion(
        @RequestBody chatRequest: ChatCompletionRequest,
    ): ChatCompletionResponse
}
