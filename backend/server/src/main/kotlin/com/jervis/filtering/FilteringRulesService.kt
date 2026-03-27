package com.jervis.filtering

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.filtering.FilterAction
import com.jervis.dto.filtering.FilterConditionType
import com.jervis.dto.filtering.FilterSourceType
import com.jervis.dto.filtering.FilterScope
import com.jervis.dto.filtering.FilteringRule
import com.jervis.dto.filtering.FilteringRuleRequest
import com.jervis.filtering.FilteringRuleDocument
import com.jervis.filtering.FilteringRuleRepository
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * EPIC 10: Dynamic Filtering Rules Service.
 *
 * Manages filtering rules that control which incoming items
 * are processed, ignored, or prioritized.
 *
 * Rules are evaluated by the qualifier before actionability assessment.
 * Can be managed via chat tools ("ignore emails from X") or UI.
 *
 * Storage: MongoDB collection `filtering_rules`.
 * Filtering/ordering: DB-level (derived queries), never app-level.
 */
@Service
class FilteringRulesService(
    private val repository: FilteringRuleRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a new filtering rule.
     */
    suspend fun createRule(request: FilteringRuleRequest): FilteringRule {
        val doc = FilteringRuleDocument(
            scope = request.scope.name,
            sourceType = request.sourceType.name,
            conditionType = request.conditionType.name,
            conditionValue = request.conditionValue,
            action = request.action.name,
            description = request.description,
            clientId = request.clientId,
            projectId = request.projectId,
        )
        val saved = repository.save(doc)
        logger.info {
            "FILTER_RULE_CREATED: id=${saved.id} source=${saved.sourceType} " +
                "condition=${saved.conditionType}:${saved.conditionValue} action=${saved.action}"
        }
        return saved.toDto()
    }

    /**
     * Remove a filtering rule by ID.
     */
    suspend fun removeRule(ruleId: String): Boolean {
        return try {
            val id = ObjectId(ruleId)
            val exists = repository.findById(id)
            if (exists != null) {
                repository.deleteById(id)
                logger.info { "FILTER_RULE_REMOVED: id=$ruleId" }
                true
            } else {
                false
            }
        } catch (e: IllegalArgumentException) {
            logger.warn { "Invalid rule ID format: $ruleId" }
            false
        }
    }

    /**
     * List rules filtered by client/project scope.
     * Filtering done in DB query.
     */
    suspend fun listRules(clientId: ClientId? = null, projectId: ProjectId? = null): List<FilteringRule> {
        val docs = when {
            clientId != null && projectId != null ->
                repository.findByEnabledTrueAndClientIdAndProjectIdOrderByCreatedAtDesc(
                    clientId.toString(), projectId.toString(),
                )
            clientId != null ->
                repository.findByEnabledTrueAndClientIdOrderByCreatedAtDesc(clientId.toString())
            else ->
                repository.findByEnabledTrueOrderByCreatedAtDesc()
        }
        return docs.toList().map { it.toDto() }
    }

    /**
     * Evaluate filtering rules against incoming content.
     *
     * Called by qualifier to determine how to handle an item.
     * DB query fetches only enabled rules for the matching source types,
     * ordered by action DESC (highest priority first).
     *
     * @param sourceType Type of source (email, jira, git, etc.)
     * @param subject Subject/title of the item
     * @param from Sender/author of the item
     * @param body Body/content of the item
     * @param labels Labels/tags associated with the item
     * @return The highest priority matching FilterAction, or null if no rules match
     */
    suspend fun evaluate(
        sourceType: FilterSourceType,
        subject: String = "",
        from: String = "",
        body: String = "",
        labels: List<String> = emptyList(),
    ): FilterAction? {
        // DB query: only enabled rules for this source type (or ALL), ordered by action DESC
        val candidates = repository.findByEnabledTrueAndSourceTypeInOrderByActionDesc(
            listOf(sourceType.name, FilterSourceType.ALL.name),
        ).toList()

        if (candidates.isEmpty()) return null

        // App-level condition matching (conditions require content inspection — not in DB)
        val winner = candidates
            .filter { matchesCondition(it, subject, from, body, labels) }
            .firstOrNull() // Already ordered by action DESC from DB

        if (winner != null) {
            logger.debug {
                "FILTER_MATCH: rule=${winner.id} action=${winner.action} " +
                    "source=$sourceType subject=${subject.take(50)}"
            }
            return FilterAction.valueOf(winner.action)
        }
        return null
    }

    private fun matchesCondition(
        doc: FilteringRuleDocument,
        subject: String,
        from: String,
        body: String,
        labels: List<String>,
    ): Boolean {
        val value = doc.conditionValue
        val type = try {
            FilterConditionType.valueOf(doc.conditionType)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return when (type) {
            FilterConditionType.SUBJECT_CONTAINS -> subject.contains(value, ignoreCase = true)
            FilterConditionType.FROM_CONTAINS -> from.contains(value, ignoreCase = true)
            FilterConditionType.BODY_CONTAINS -> body.contains(value, ignoreCase = true)
            FilterConditionType.LABEL_EQUALS -> labels.any { it.equals(value, ignoreCase = true) }
            FilterConditionType.REGEX_MATCH -> {
                try {
                    val regex = Regex(value, RegexOption.IGNORE_CASE)
                    regex.containsMatchIn("$subject $body $from")
                } catch (e: Exception) {
                    logger.warn { "Invalid regex in filter rule ${doc.id}: $value" }
                    false
                }
            }
        }
    }
}

/**
 * Convert MongoDB document to DTO.
 */
private fun FilteringRuleDocument.toDto(): FilteringRule = FilteringRule(
    id = id.toHexString(),
    scope = try { FilterScope.valueOf(scope) } catch (_: Exception) { FilterScope.CLIENT },
    sourceType = FilterSourceType.valueOf(sourceType),
    conditionType = FilterConditionType.valueOf(conditionType),
    conditionValue = conditionValue,
    action = FilterAction.valueOf(action),
    description = description,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    enabled = enabled,
)
