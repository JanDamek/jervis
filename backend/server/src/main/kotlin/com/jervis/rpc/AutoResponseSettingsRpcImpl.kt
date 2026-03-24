package com.jervis.rpc

import com.jervis.dto.AutoResponseDecisionDto
import com.jervis.dto.AutoResponseSettingsDto
import com.jervis.dto.ResponseRuleDto
import com.jervis.entity.AutoResponseSettingsDocument
import com.jervis.entity.ResponseRule
import com.jervis.repository.AutoResponseSettingsRepository
import com.jervis.service.IAutoResponseSettingsService
import com.jervis.service.agent.AutoResponseEvaluator
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class AutoResponseSettingsRpcImpl(
    private val repository: AutoResponseSettingsRepository,
    private val evaluator: AutoResponseEvaluator,
) : IAutoResponseSettingsService {
    private val logger = KotlinLogging.logger {}

    override suspend fun getSettings(
        clientId: String?,
        projectId: String?,
        channelType: String?,
        channelId: String?,
    ): AutoResponseSettingsDto? {
        val doc = repository.findByClientIdAndProjectIdAndChannelTypeAndChannelId(
            clientId = clientId,
            projectId = projectId,
            channelType = channelType,
            channelId = channelId,
        )
        return doc?.toDto()
    }

    override suspend fun saveSettings(settings: AutoResponseSettingsDto): String {
        val existing = if (settings.id != null && ObjectId.isValid(settings.id)) {
            repository.findById(ObjectId(settings.id))
        } else {
            // Try to find existing by scope
            repository.findByClientIdAndProjectIdAndChannelTypeAndChannelId(
                clientId = settings.clientId,
                projectId = settings.projectId,
                channelType = settings.channelType,
                channelId = settings.channelId,
            )
        }

        val doc = if (existing != null) {
            existing.copy(
                enabled = settings.enabled,
                neverAutoResponse = settings.neverAutoResponse,
                responseRules = settings.responseRules.map { ResponseRule(trigger = it.trigger, action = it.action) },
                learningEnabled = settings.learningEnabled,
                updatedAt = Instant.now(),
            )
        } else {
            AutoResponseSettingsDocument(
                clientId = settings.clientId,
                projectId = settings.projectId,
                channelType = settings.channelType,
                channelId = settings.channelId,
                enabled = settings.enabled,
                neverAutoResponse = settings.neverAutoResponse,
                responseRules = settings.responseRules.map { ResponseRule(trigger = it.trigger, action = it.action) },
                learningEnabled = settings.learningEnabled,
            )
        }

        val saved = repository.save(doc)
        logger.info { "AUTO_RESPONSE_SETTINGS_SAVED | id=${saved.id} | clientId=${saved.clientId} | enabled=${saved.enabled}" }
        return saved.id.toString()
    }

    override suspend fun evaluate(
        clientId: String,
        projectId: String?,
        channelType: String,
        channelId: String?,
    ): AutoResponseDecisionDto {
        val decision = evaluator.evaluate(
            clientId = clientId,
            projectId = projectId,
            channelType = channelType,
            channelId = channelId,
        )
        return AutoResponseDecisionDto(
            enabled = decision.enabled,
            blocked = decision.blocked,
            resolvedLevel = decision.resolvedLevel,
            rules = decision.rules.map { ResponseRuleDto(trigger = it.trigger, action = it.action) },
        )
    }
}

private fun AutoResponseSettingsDocument.toDto(): AutoResponseSettingsDto = AutoResponseSettingsDto(
    id = id.toString(),
    clientId = clientId,
    projectId = projectId,
    channelType = channelType,
    channelId = channelId,
    enabled = enabled,
    neverAutoResponse = neverAutoResponse,
    responseRules = responseRules.map { ResponseRuleDto(trigger = it.trigger, action = it.action) },
    learningEnabled = learningEnabled,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)
