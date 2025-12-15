package com.jervis.service.coding

import com.jervis.configuration.properties.CodingToolsProperties
import org.springframework.stereotype.Service

@Service
class ModelSelectionService(
    private val codingToolsProperties: CodingToolsProperties,
) {
    fun getAiderDefaultModel(): String {
        val config = codingToolsProperties.aider
        return formatModelIdentifier(config.defaultProvider, config.defaultModel)
    }

    fun getAiderPaidModel(): String {
        val config = codingToolsProperties.aider
        return formatModelIdentifier(config.paidProvider, config.paidModel)
    }

    fun getOpenHandsDefaultModel(): String {
        val config = codingToolsProperties.openhands
        return formatModelIdentifier(config.defaultProvider, config.defaultModel)
    }

    fun getOpenHandsPaidModel(): String {
        val config = codingToolsProperties.openhands
        return formatModelIdentifier(config.paidProvider, config.paidModel)
    }

    private fun formatModelIdentifier(provider: String, model: String): String =
        when (provider.lowercase()) {
            "ollama" -> "ollama/$model"
            else -> "$provider/$model"
        }
}
