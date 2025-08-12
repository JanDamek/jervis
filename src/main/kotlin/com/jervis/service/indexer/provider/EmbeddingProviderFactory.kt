package com.jervis.service.indexer.provider

import com.jervis.domain.model.ModelProvider
import com.jervis.service.setting.SettingService
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * Factory for creating embedding providers based on settings.
 */
@Component
class EmbeddingProviderFactory(
    private val settingService: SettingService,
) {
    private val restTemplate = RestTemplate()
    private val logger = KotlinLogging.logger {}

    /**
     * Create an embedding provider based on the current settings.
     *
     * @return The appropriate embedding provider
     */
    suspend fun createProvider(): EmbeddingProvider {
        val modelType = settingService.embeddingModelType

        logger.info { "Creating embedding provider for model type: $modelType" }

        val modelTypeEnum = settingService.embeddingModelType

        return when (modelTypeEnum) {
            ModelProvider.DJL -> {
                // Return null for internal provider as it's handled by DJL in EmbeddingService
                throw IllegalStateException("Internal embedding provider should be handled by DJL in EmbeddingService")
            }

            ModelProvider.OPENAI -> {
                logger.info { "Creating OpenAI embedding provider" }
                OpenAIEmbeddingProvider(settingService, restTemplate)
            }

            ModelProvider.OLLAMA -> {
                logger.info { "Creating OLLama embedding provider" }
                OLLamaEmbeddingProvider(settingService, restTemplate)
            }

            ModelProvider.LM_STUDIO -> {
                logger.info { "Creating LM Studio embedding provider" }
                LMStudioEmbeddingProvider(settingService, restTemplate)
            }

            ModelProvider.ANTHROPIC -> error("Anthropic embedding provider is not supported")
        }
    }
}
