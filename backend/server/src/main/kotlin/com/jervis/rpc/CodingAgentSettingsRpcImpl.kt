package com.jervis.rpc

import com.jervis.configuration.properties.CodingToolsProperties
import com.jervis.dto.coding.CodingAgentApiKeyUpdateDto
import com.jervis.dto.coding.CodingAgentConfigDto
import com.jervis.dto.coding.CodingAgentSettingsDto
import com.jervis.service.ICodingAgentSettingsService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class CodingAgentSettingsRpcImpl(
    private val codingToolsProperties: CodingToolsProperties,
) : ICodingAgentSettingsService {
    private val logger = KotlinLogging.logger {}

    // In-memory store for API keys (in production, these should be persisted to a secrets store)
    private val apiKeys = mutableMapOf<String, String>()

    override suspend fun getSettings(): CodingAgentSettingsDto {
        val agents =
            listOf(
                CodingAgentConfigDto(
                    name = "claude",
                    displayName = "Claude (Anthropic)",
                    enabled = true,
                    apiKeySet = System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true || apiKeys.containsKey("claude"),
                    provider = codingToolsProperties.claude.defaultProvider,
                    model = codingToolsProperties.claude.defaultModel,
                ),
                CodingAgentConfigDto(
                    name = "junie",
                    displayName = "Junie (JetBrains)",
                    enabled = true,
                    apiKeySet = System.getenv("JUNIE_API_KEY")?.isNotBlank() == true || apiKeys.containsKey("junie"),
                    provider = codingToolsProperties.junie.defaultProvider,
                    model = codingToolsProperties.junie.defaultModel,
                ),
                CodingAgentConfigDto(
                    name = "aider",
                    displayName = "Aider",
                    enabled = true,
                    apiKeySet = true, // Aider uses Ollama by default, no API key needed
                    provider = codingToolsProperties.aider.defaultProvider,
                    model = codingToolsProperties.aider.defaultModel,
                ),
                CodingAgentConfigDto(
                    name = "openhands",
                    displayName = "OpenHands",
                    enabled = true,
                    apiKeySet = true, // OpenHands uses Ollama by default
                    provider = codingToolsProperties.openhands.defaultProvider,
                    model = codingToolsProperties.openhands.defaultModel,
                ),
            )

        return CodingAgentSettingsDto(agents = agents)
    }

    override suspend fun updateApiKey(request: CodingAgentApiKeyUpdateDto): CodingAgentSettingsDto {
        logger.info { "Updating API key for agent: ${request.agentName}" }
        apiKeys[request.agentName] = request.apiKey
        return getSettings()
    }
}
