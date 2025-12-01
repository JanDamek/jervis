package com.jervis.configuration.prompts

import com.jervis.configuration.properties.YamlPropertySourceFactory
import com.jervis.domain.model.ExecutionMode
import com.jervis.domain.model.ModelProviderEnum
import com.jervis.domain.model.ProviderCapabilities
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.PropertySource
import org.springframework.stereotype.Service

/**
 * Service that provides provider capabilities configuration.
 * Loads providers section from models-config.yaml.
 */
@Service
@ConfigurationProperties(prefix = "")
@PropertySource(
    value = ["classpath:models-config.yaml"],
    factory = YamlPropertySourceFactory::class,
)
class ProviderCapabilitiesService {
    var providers: Map<ModelProviderEnum, ProviderConfig> = emptyMap()

    data class ProviderConfig(
        var endpoint: String,
        var executionMode: ExecutionMode,
        var maxConcurrentRequests: Int,
    )

    /**
     * Get all provider capabilities.
     */
    fun getAllProviderCapabilities(): List<ProviderCapabilities> =
        providers.map { (provider, config) ->
            ProviderCapabilities(
                provider = provider,
                endpoint = config.endpoint,
                maxConcurrentRequests = config.maxConcurrentRequests,
                executionMode = config.executionMode,
            )
        }

    /**
     * Get capabilities for specific provider.
     */
    fun getProviderCapabilities(provider: ModelProviderEnum): ProviderCapabilities? =
        providers[provider]?.let { config ->
            ProviderCapabilities(
                provider = provider,
                endpoint = config.endpoint,
                maxConcurrentRequests = config.maxConcurrentRequests,
                executionMode = config.executionMode,
            )
        }
}
