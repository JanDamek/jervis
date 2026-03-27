package com.jervis.agent

import com.jervis.agent.AutoResponseSettingsDocument
import com.jervis.agent.AutoResponseSettingsRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service

/**
 * AutoResponseEvaluator — determines whether auto-response is enabled for a given context.
 *
 * Cascading resolution order (most specific wins):
 * 1. Channel-level: clientId + projectId + channelType + channelId
 * 2. Project-level: clientId + projectId + channelType (no channelId)
 * 3. Client-level: clientId only (no project, no channel)
 * 4. Default: OFF (no auto-response)
 *
 * If `neverAutoResponse` is set at any level, the result is always BLOCKED.
 */
@Service
class AutoResponseEvaluator(
    private val repository: AutoResponseSettingsRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Evaluate auto-response settings for the given context.
     * Returns a decision with the resolved settings and the level that matched.
     */
    suspend fun evaluate(
        clientId: String,
        projectId: String? = null,
        channelType: String,
        channelId: String? = null,
    ): AutoResponseDecision {
        // 1. Most specific: channel-level
        if (channelId != null && projectId != null) {
            val channelSettings = repository.findByClientIdAndProjectIdAndChannelTypeAndChannelId(
                clientId = clientId,
                projectId = projectId,
                channelType = channelType,
                channelId = channelId,
            )
            if (channelSettings != null) {
                return resolveDecision(channelSettings, "channel")
            }
        }

        // 2. Project-level (channelType but no channelId)
        if (projectId != null) {
            val projectSettings = repository.findByClientIdAndProjectIdAndChannelTypeAndChannelId(
                clientId = clientId,
                projectId = projectId,
                channelType = channelType,
                channelId = null,
            )
            if (projectSettings != null) {
                return resolveDecision(projectSettings, "project")
            }
        }

        // 3. Client-level (no project, no channel)
        val clientSettings = repository.findByClientIdAndProjectIdAndChannelTypeAndChannelId(
            clientId = clientId,
            projectId = null,
            channelType = null,
            channelId = null,
        )
        if (clientSettings != null) {
            return resolveDecision(clientSettings, "client")
        }

        // 4. Default: OFF
        logger.debug { "AUTO_RESPONSE_DEFAULT_OFF | clientId=$clientId | channelType=$channelType" }
        return AutoResponseDecision(
            enabled = false,
            blocked = false,
            resolvedLevel = "default",
            rules = emptyList(),
        )
    }

    private fun resolveDecision(
        settings: AutoResponseSettingsDocument,
        level: String,
    ): AutoResponseDecision {
        if (settings.neverAutoResponse) {
            logger.info { "AUTO_RESPONSE_BLOCKED | level=$level | settingsId=${settings.id}" }
            return AutoResponseDecision(
                enabled = false,
                blocked = true,
                resolvedLevel = level,
                rules = settings.responseRules.map { AutoResponseRuleDto(trigger = it.trigger, action = it.action) },
            )
        }
        return AutoResponseDecision(
            enabled = settings.enabled,
            blocked = false,
            resolvedLevel = level,
            rules = settings.responseRules.map { AutoResponseRuleDto(trigger = it.trigger, action = it.action) },
        )
    }
}

/**
 * Result of auto-response evaluation.
 */
data class AutoResponseDecision(
    val enabled: Boolean,
    val blocked: Boolean,
    val resolvedLevel: String,  // channel, project, client, default
    val rules: List<AutoResponseRuleDto>,
)

data class AutoResponseRuleDto(
    val trigger: String,
    val action: String,
)
