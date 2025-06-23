package com.jervis.module.indexer.provider

import com.jervis.entity.EmbeddingModelType
import com.jervis.service.SettingService
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
    fun createProvider(): EmbeddingProvider {
        val modelType = settingService.getEmbeddingModelTypeEnum()

        logger.info { "Creating embedding provider for model type: $modelType" }

        val modelTypeEnum = settingService.getEmbeddingModelTypeEnum()

        return when (modelTypeEnum) {
            EmbeddingModelType.INTERNAL -> {
                // Return null for internal provider as it's handled by DJL in EmbeddingService
                throw IllegalStateException("Internal embedding provider should be handled by DJL in EmbeddingService")
            }

            EmbeddingModelType.OPENAI -> {
                logger.info { "Creating OpenAI embedding provider" }
                OpenAIEmbeddingProvider(settingService, restTemplate)
            }

            EmbeddingModelType.OLLAMA -> {
                logger.info { "Creating OLLama embedding provider" }
                OLLamaEmbeddingProvider(settingService, restTemplate)
            }

            EmbeddingModelType.LM_STUDIO -> {
                logger.info { "Creating LM Studio embedding provider" }
                LMStudioEmbeddingProvider(settingService, restTemplate)
            }
        }
    }
}
