package com.jervis.koog

import com.jervis.configuration.properties.EndpointProperties
import org.springframework.stereotype.Service

@Service
class OllamaProviderSelector(
    private val koogWorkflowAgent: KoogWorkflowAgent,
    private val endpointProperties: EndpointProperties,
) {
    // In a real scenario, this would involve checking a queue, a lock,
    // or some other mechanism to determine if the P40 is busy.
    // For now, we'll just simulate it.
    private fun isP40Busy(): Boolean {
        return koogWorkflowAgent.isProviderInUse("OLLAMA")
    }

    fun getProvider(): String {
        return if (isP40Busy()) {
            "OLLAMA_QUALIFIER"
        } else {
            "OLLAMA"
        }
    }

    /**
     * Get base URL for the selected Ollama provider.
     * Used for direct HTTP API calls (e.g., vision analysis).
     */
    fun getBaseUrl(): String {
        val provider = getProvider()
        return when (provider) {
            "OLLAMA" -> endpointProperties.ollama.primary.baseUrl.removeSuffix("/")
            "OLLAMA_QUALIFIER" -> endpointProperties.ollama.qualifier.baseUrl.removeSuffix("/")
            else -> endpointProperties.ollama.primary.baseUrl.removeSuffix("/")
        }
    }
}
