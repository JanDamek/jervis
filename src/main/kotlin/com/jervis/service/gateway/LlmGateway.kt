package com.jervis.service.gateway

import com.jervis.configuration.prompts.EffectiveModelParams
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelType

/**
 * Gateway for text generation models. Reads candidates from application properties.
 */
interface LlmGateway {
    suspend fun callLlm(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String,
        outputLanguage: String,
        quick: Boolean,
        modelParams: EffectiveModelParams?,
    ): LlmResponse

    // Backward compatibility method
    suspend fun callLlm(
        type: ModelType,
        userPrompt: String,
        systemPrompt: String,
        outputLanguage: String,
        quick: Boolean,
    ): LlmResponse = callLlm(type, userPrompt, systemPrompt, outputLanguage, quick, null)
}
