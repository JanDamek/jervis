package com.jervis.rpc

import com.jervis.configuration.properties.CodingToolsProperties
import com.jervis.dto.coding.CodingAgentApiKeyUpdateDto
import com.jervis.dto.coding.CodingAgentConfigDto
import com.jervis.dto.coding.CodingAgentSettingsDto
import com.jervis.entity.CodingAgentSettingsDocument
import com.jervis.repository.CodingAgentSettingsRepository
import com.jervis.service.ICodingAgentSettingsService
import mu.KotlinLogging
import org.springframework.stereotype.Component

@Component
class CodingAgentSettingsRpcImpl(
    private val codingToolsProperties: CodingToolsProperties,
    private val settingsRepository: CodingAgentSettingsRepository,
) : ICodingAgentSettingsService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSettings(): CodingAgentSettingsDto {
        val storedKeys = mutableMapOf<String, Boolean>()
        listOf("claude", "junie", "aider", "openhands").forEach { name ->
            val doc = settingsRepository.findByAgentName(name)
            storedKeys[name] = doc?.apiKey?.isNotBlank() == true
        }

        val agents =
            listOf(
                CodingAgentConfigDto(
                    name = "claude",
                    displayName = "Claude (Anthropic)",
                    enabled = true,
                    apiKeySet = storedKeys["claude"] == true || System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true,
                    provider = codingToolsProperties.claude.defaultProvider,
                    model = codingToolsProperties.claude.defaultModel,
                ),
                CodingAgentConfigDto(
                    name = "junie",
                    displayName = "Junie (JetBrains)",
                    enabled = true,
                    apiKeySet = storedKeys["junie"] == true || System.getenv("JUNIE_API_KEY")?.isNotBlank() == true,
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

        val existing = settingsRepository.findByAgentName(request.agentName)
        if (existing != null) {
            settingsRepository.save(existing.copy(apiKey = request.apiKey))
        } else {
            settingsRepository.save(
                CodingAgentSettingsDocument(
                    agentName = request.agentName,
                    apiKey = request.apiKey,
                ),
            )
        }

        return getSettings()
    }

    /**
     * Get stored API key for a specific agent. Used by CodingTools to inject keys into requests.
     */
    suspend fun getApiKey(agentName: String): String? {
        val doc = settingsRepository.findByAgentName(agentName)
        return doc?.apiKey?.takeIf { it.isNotBlank() }
    }
}
