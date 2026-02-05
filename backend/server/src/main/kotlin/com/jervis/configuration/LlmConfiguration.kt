package com.jervis.configuration

import com.jervis.configuration.prompts.ProviderCapabilitiesService
import com.jervis.configuration.properties.ModelsProperties
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.context.annotation.Configuration

/**
 * Configuration class that logs LLM provider and model setup at startup.
 * Provides visibility into which providers and models are configured.
 */
@Configuration
class LlmConfiguration(
    private val modelsProperties: ModelsProperties,
    private val providerCapabilitiesService: ProviderCapabilitiesService,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun logConfiguration() {
        val providers = providerCapabilitiesService.getAllProviderCapabilities()
        logger.info { "Configured ${providers.size} providers:" }
        providers.forEach { provider ->
            logger.info {
                "  - ${provider.provider}, " +
                    "max ${provider.maxConcurrentRequests} concurrent requests, " +
                    "endpoint=${provider.endpoint}"
            }
        }

        logger.info { "Configured ${modelsProperties.models.size} model types:" }
        modelsProperties.models.forEach { (modelType, models) ->
            logger.info { "  - $modelType: ${models.size} models" }
            models.forEach { model ->
                val contextInfo = model.contextLength?.let { " ($it tokens)" } ?: ""
                val dimensionInfo = model.dimension?.let { " [dim=$it]" } ?: ""
                logger.info { "    * ${model.provider}/${model.model}$contextInfo$dimensionInfo" }
            }
        }
    }
}
