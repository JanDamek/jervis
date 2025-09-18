package com.jervis.service.gateway.clients

import com.jervis.configuration.ModelsProperties
import com.jervis.configuration.prompts.PromptConfig
import com.jervis.domain.llm.LlmResponse
import com.jervis.domain.model.ModelProvider

interface ProviderClient {
    val provider: ModelProvider

    suspend fun call(
        model: String,
        systemPrompt: String?,
        userPrompt: String,
        config: ModelsProperties.ModelDetail,
        prompt: PromptConfig,
    ): LlmResponse
}
