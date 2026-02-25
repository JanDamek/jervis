package com.jervis.service.filtering

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.filtering.FilterConditionType
import com.jervis.dto.filtering.FilterSourceType
import com.jervis.dto.filtering.FilteringRule
import com.jervis.dto.filtering.FilteringRuleRequest
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * EPIC 10: Dynamic Filtering Rules Service.
 *
 * Manages filtering rules that control which incoming items
 * are processed, ignored, or prioritized.
 *
 * Rules are evaluated by the qualifier before actionability assessment.
 * Can be managed via chat tools ("ignore emails from X") or UI.
 */
@Service
class FilteringRulesService {
    private val logger = KotlinLogging.logger {}

    // In-memory cache — will be backed by MongoDB in production
    private val rules = ConcurrentHashMap<String, FilteringRule>()

    /**
     * Create a new filtering rule.
     */
    fun createRule(request: FilteringRuleRequest): FilteringRule {
        val rule = FilteringRule(
            id = UUID.randomUUID().toString(),
            scope = request.scope,
            sourceType = request.sourceType,
            conditionType = request.conditionType,
            conditionValue = request.conditionValue,
            action = request.action,
            description = request.description,
            createdAt = Instant.now().toString(),
        )
        rules[rule.id] = rule
        logger.info {
            "FILTER_RULE_CREATED: id=${rule.id} source=${rule.sourceType} " +
                "condition=${rule.conditionType}:${rule.conditionValue} action=${rule.action}"
        }
        return rule
    }

    /**
     * Remove a filtering rule by ID.
     */
    fun removeRule(ruleId: String): Boolean {
        val removed = rules.remove(ruleId)
        if (removed != null) {
            logger.info { "FILTER_RULE_REMOVED: id=$ruleId" }
        }
        return removed != null
    }

    /**
     * List all rules, optionally filtered by scope.
     */
    fun listRules(clientId: ClientId? = null, projectId: ProjectId? = null): List<FilteringRule> {
        return rules.values
            .filter { it.enabled }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Evaluate filtering rules against incoming content.
     *
     * Called by qualifier to determine how to handle an item.
     *
     * @param sourceType Type of source (email, jira, git, etc.)
     * @param subject Subject/title of the item
     * @param from Sender/author of the item
     * @param body Body/content of the item
     * @param labels Labels/tags associated with the item
     * @return The most specific matching FilterAction, or null if no rules match
     */
    fun evaluate(
        sourceType: FilterSourceType,
        subject: String = "",
        from: String = "",
        body: String = "",
        labels: List<String> = emptyList(),
    ): FilterAction? {
        val matchingRules = rules.values
            .filter { it.enabled }
            .filter { it.sourceType == sourceType || it.sourceType == FilterSourceType.ALL }
            .filter { matchesCondition(it, subject, from, body, labels) }

        if (matchingRules.isEmpty()) return null

        // Return the highest priority action (URGENT > HIGH > NORMAL > LOW > IGNORE)
        return matchingRules
            .maxByDescending { it.action.ordinal }
            .also { rule ->
                logger.debug {
                    "FILTER_MATCH: rule=${rule?.id} action=${rule?.action} " +
                        "source=$sourceType subject=${subject.take(50)}"
                }
            }
            ?.action
    }

    private fun matchesCondition(
        rule: FilteringRule,
        subject: String,
        from: String,
        body: String,
        labels: List<String>,
    ): Boolean {
        val value = rule.conditionValue
        return when (rule.conditionType) {
            FilterConditionType.SUBJECT_CONTAINS -> subject.contains(value, ignoreCase = true)
            FilterConditionType.FROM_CONTAINS -> from.contains(value, ignoreCase = true)
            FilterConditionType.BODY_CONTAINS -> body.contains(value, ignoreCase = true)
            FilterConditionType.LABEL_EQUALS -> labels.any { it.equals(value, ignoreCase = true) }
            FilterConditionType.REGEX_MATCH -> {
                try {
                    val regex = Regex(value, RegexOption.IGNORE_CASE)
                    regex.containsMatchIn("$subject $body $from")
                } catch (e: Exception) {
                    logger.warn { "Invalid regex in filter rule ${rule.id}: $value" }
                    false
                }
            }
        }
    }
}
