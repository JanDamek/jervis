package com.jervis.dto.coding

import kotlinx.serialization.Serializable

@Serializable
data class CodingAgentSettingsDto(
    val agents: List<CodingAgentConfigDto> = emptyList(),
)

@Serializable
data class CodingAgentConfigDto(
    val name: String,
    val displayName: String,
    val enabled: Boolean = true,
    val apiKeySet: Boolean = false,
    /** Whether a setup token (Max/Pro subscription) is configured. */
    val setupTokenConfigured: Boolean = false,
    val provider: String = "",
    val model: String = "",
    /** URL to the provider's console where user can create/manage API keys. */
    val consoleUrl: String = "",
    /** Whether this agent requires an API key (false for agents using local Ollama). */
    val requiresApiKey: Boolean = true,
    /** Whether this agent supports `claude setup-token` for Max/Pro subscription auth. */
    val supportsSetupToken: Boolean = false,
)

@Serializable
data class CodingAgentApiKeyUpdateDto(
    val agentName: String,
    val apiKey: String,
)

@Serializable
data class CodingAgentSetupTokenUpdateDto(
    val agentName: String,
    /** Long-lived OAuth token from `claude setup-token` (sk-ant-oat01-...). */
    val token: String,
)
