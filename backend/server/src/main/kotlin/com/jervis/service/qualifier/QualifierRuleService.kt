package com.jervis.service.qualifier

import com.jervis.domain.qualifier.QualifierRule
import com.jervis.domain.qualifier.QualifierRuleRepository
import com.jervis.dto.PendingTaskTypeEnum
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * Service for managing qualifier rules that control task pre-filtering.
 * Rules are written by the agent in natural language and injected into qualifier prompts.
 */
@Service
class QualifierRuleService(
    private val qualifierRuleRepository: QualifierRuleRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Adds a new qualifier rule for the specified task type.
     */
    suspend fun addRule(
        qualifierType: PendingTaskTypeEnum,
        ruleText: String,
    ): QualifierRule {
        val rule =
            QualifierRule(
                qualifierType = qualifierType,
                ruleText = ruleText.trim(),
            )
        val saved = qualifierRuleRepository.save(rule)
        logger.info { "Added qualifier rule for $qualifierType: ${saved.id}" }
        return saved
    }

    /**
     * Lists all qualifier rules for a specific task type.
     */
    suspend fun listRules(qualifierType: PendingTaskTypeEnum): List<QualifierRule> =
        qualifierRuleRepository.findByQualifierType(qualifierType)

    /**
     * Lists all qualifier rules across all types.
     */
    suspend fun listAllRules(): List<QualifierRule> = qualifierRuleRepository.findAll().toList()

    /**
     * Deletes a specific qualifier rule by ID.
     */
    suspend fun deleteRule(ruleId: String): Boolean {
        val objectId =
            try {
                ObjectId(ruleId)
            } catch (_: IllegalArgumentException) {
                logger.warn { "Invalid rule ID format: $ruleId" }
                return false
            }

        val exists = qualifierRuleRepository.existsById(objectId)
        if (exists) {
            qualifierRuleRepository.deleteById(objectId)
            logger.info { "Deleted qualifier rule: $ruleId" }
            return true
        }
        logger.warn { "Qualifier rule not found: $ruleId" }
        return false
    }

    /**
     * Updates an existing qualifier rule's text.
     */
    suspend fun updateRule(
        ruleId: String,
        newRuleText: String,
    ): QualifierRule? {
        val objectId =
            try {
                ObjectId(ruleId)
            } catch (_: IllegalArgumentException) {
                logger.warn { "Invalid rule ID format: $ruleId" }
                return null
            }

        val existing = qualifierRuleRepository.findById(objectId) ?: return null
        val updated = existing.copy(ruleText = newRuleText.trim())
        val saved = qualifierRuleRepository.save(updated)
        logger.info { "Updated qualifier rule: $ruleId" }
        return saved
    }

    /**
     * Returns formatted text of all active rules for a specific qualifier type.
     * This text is injected into the qualifier prompt as {activeQualifierRules} placeholder.
     */
    suspend fun getRulesText(qualifierType: PendingTaskTypeEnum): String {
        val rules = qualifierRuleRepository.findByQualifierType(qualifierType)

        if (rules.isEmpty()) {
            return "No active qualifier rules configured."
        }

        return buildString {
            appendLine("ACTIVE QUALIFIER RULES:")
            rules.forEachIndexed { index, rule ->
                appendLine("${index + 1}. ${rule.ruleText}")
            }
        }.trim()
    }
}
