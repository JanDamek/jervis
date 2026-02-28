package com.jervis.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/**
 * Singleton MongoDB document for OpenRouter configuration.
 *
 * Stores API credentials, filters, and a prioritized list of models
 * available for routing across chat, orchestrator, and KB.
 */
@Document(collection = "openrouter_settings")
data class OpenRouterSettingsDocument(
    @Id
    val id: String = SINGLETON_ID,
    val apiKey: String = "",
    val apiBaseUrl: String = "https://openrouter.ai/api/v1",
    val enabled: Boolean = false,
    val filters: OpenRouterFilters = OpenRouterFilters(),
    val models: List<OpenRouterModelEntry> = emptyList(),
    val monthlyBudgetUsd: Double = 0.0,
    val fallbackStrategy: String = "NEXT_IN_LIST",
) {
    companion object {
        const val SINGLETON_ID = "openrouter-settings-global"
    }
}

data class OpenRouterFilters(
    val allowedProviders: List<String> = emptyList(),
    val blockedProviders: List<String> = emptyList(),
    val minContextLength: Int = 0,
    val maxInputPricePerMillion: Double = 0.0,
    val maxOutputPricePerMillion: Double = 0.0,
    val requireToolSupport: Boolean = false,
    val requireStreaming: Boolean = true,
    val modelNameFilter: String = "",
)

data class OpenRouterModelEntry(
    val modelId: String,
    val displayName: String = "",
    val enabled: Boolean = true,
    val maxContextTokens: Int = 0,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val preferredFor: List<String> = emptyList(),
    val maxOutputTokens: Int = 0,
)
