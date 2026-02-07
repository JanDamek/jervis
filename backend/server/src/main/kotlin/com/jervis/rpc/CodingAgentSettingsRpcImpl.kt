package com.jervis.rpc

import com.jervis.configuration.properties.CodingToolsProperties
import com.jervis.dto.coding.CodingAgentApiKeyUpdateDto
import com.jervis.dto.coding.CodingAgentConfigDto
import com.jervis.dto.coding.CodingAgentOAuthUpdateDto
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
        val storedDocs = mutableMapOf<String, CodingAgentSettingsDocument?>()
        listOf("claude", "junie", "aider", "openhands").forEach { name ->
            storedDocs[name] = settingsRepository.findByAgentName(name)
        }

        val agents =
            listOf(
                CodingAgentConfigDto(
                    name = "claude",
                    displayName = "Claude (Anthropic)",
                    enabled = true,
                    apiKeySet = storedDocs["claude"]?.apiKey?.isNotBlank() == true ||
                        System.getenv("ANTHROPIC_API_KEY")?.isNotBlank() == true,
                    oauthConfigured = storedDocs["claude"]?.oauthCredentialsJson?.isNotBlank() == true,
                    provider = codingToolsProperties.claude.defaultProvider,
                    model = codingToolsProperties.claude.defaultModel,
                    consoleUrl = "https://console.anthropic.com/settings/keys",
                    requiresApiKey = true,
                    supportsOAuth = true,
                ),
                CodingAgentConfigDto(
                    name = "junie",
                    displayName = "Junie (JetBrains)",
                    enabled = true,
                    apiKeySet = storedDocs["junie"]?.apiKey?.isNotBlank() == true ||
                        System.getenv("JUNIE_API_KEY")?.isNotBlank() == true,
                    provider = codingToolsProperties.junie.defaultProvider,
                    model = codingToolsProperties.junie.defaultModel,
                    consoleUrl = "https://account.jetbrains.com",
                    requiresApiKey = true,
                ),
                CodingAgentConfigDto(
                    name = "aider",
                    displayName = "Aider",
                    enabled = true,
                    apiKeySet = true,
                    provider = codingToolsProperties.aider.defaultProvider,
                    model = codingToolsProperties.aider.defaultModel,
                    requiresApiKey = false,
                ),
                CodingAgentConfigDto(
                    name = "openhands",
                    displayName = "OpenHands",
                    enabled = true,
                    apiKeySet = true,
                    provider = codingToolsProperties.openhands.defaultProvider,
                    model = codingToolsProperties.openhands.defaultModel,
                    requiresApiKey = false,
                ),
            )

        return CodingAgentSettingsDto(agents = agents)
    }

    override suspend fun updateApiKey(request: CodingAgentApiKeyUpdateDto): CodingAgentSettingsDto {
        logger.info { "Updating API key for agent: ${request.agentName}" }
        upsertField(request.agentName) { it.copy(apiKey = request.apiKey) }
        return getSettings()
    }

    override suspend fun updateOAuthCredentials(request: CodingAgentOAuthUpdateDto): CodingAgentSettingsDto {
        logger.info { "Updating OAuth credentials for agent: ${request.agentName}" }
        upsertField(request.agentName) { it.copy(oauthCredentialsJson = request.credentialsJson) }
        return getSettings()
    }

    /**
     * Get stored API key for a specific agent. Used by CodingTools to inject keys into requests.
     */
    suspend fun getApiKey(agentName: String): String? {
        val doc = settingsRepository.findByAgentName(agentName)
        return doc?.apiKey?.takeIf { it.isNotBlank() }
    }

    /**
     * Get stored OAuth credentials JSON for a specific agent (Claude).
     */
    suspend fun getOAuthCredentialsJson(agentName: String): String? {
        val doc = settingsRepository.findByAgentName(agentName)
        return doc?.oauthCredentialsJson?.takeIf { it.isNotBlank() }
    }

    private suspend fun upsertField(
        agentName: String,
        updater: (CodingAgentSettingsDocument) -> CodingAgentSettingsDocument,
    ) {
        val existing = settingsRepository.findByAgentName(agentName)
        if (existing != null) {
            settingsRepository.save(updater(existing))
        } else {
            settingsRepository.save(updater(CodingAgentSettingsDocument(agentName = agentName)))
        }
    }
}
