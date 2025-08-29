package com.jervis.service.gateway

import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelType

/**
 * Gateway for text generation models. Reads candidates from application properties.
 */
interface LlmGateway {
    suspend fun callLlm(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String? = null,
        outputLanguage: String? = null,
        quick: Boolean = false,
    ): LlmResponse
}
