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
    val provider: String = "",
    val model: String = "",
)

@Serializable
data class CodingAgentApiKeyUpdateDto(
    val agentName: String,
    val apiKey: String,
)
