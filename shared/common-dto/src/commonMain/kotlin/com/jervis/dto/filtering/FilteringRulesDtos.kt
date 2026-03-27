package com.jervis.dto.filtering

import kotlinx.serialization.Serializable

/**
 * EPIC 10: Dynamic Filtering Rules DTOs.
 *
 * Supports chat-driven filter creation, source filtering,
 * and qualifier integration for priority adjustment.
 */

/**
 * Source types that filtering rules can apply to.
 */
@Serializable
enum class FilterSourceType {
    EMAIL, JIRA, GIT, WIKI, CHAT, MEETING, ALL,
}

/**
 * Action to take when a filter rule matches.
 */
@Serializable
enum class FilterAction {
    IGNORE,
    LOW_PRIORITY,
    NORMAL,
    HIGH_PRIORITY,
    URGENT,
}

/**
 * A single filtering rule.
 */
@Serializable
data class FilteringRule(
    val id: String,
    val scope: FilterScope = FilterScope.CLIENT,
    val sourceType: FilterSourceType,
    val conditionType: FilterConditionType,
    val conditionValue: String,
    val action: FilterAction,
    val description: String? = null,
    val createdAt: String,
    val createdBy: String = "user",
    val enabled: Boolean = true,
)

@Serializable
enum class FilterScope {
    GLOBAL, CLIENT, PROJECT,
}

@Serializable
enum class FilterConditionType {
    /** Match on subject/title. */
    SUBJECT_CONTAINS,
    /** Match on sender/author. */
    FROM_CONTAINS,
    /** Match on body/content. */
    BODY_CONTAINS,
    /** Match on labels/tags. */
    LABEL_EQUALS,
    /** Regex match on full content. */
    REGEX_MATCH,
}

/**
 * Request to create/update a filtering rule.
 */
@Serializable
data class FilteringRuleRequest(
    val scope: FilterScope = FilterScope.CLIENT,
    val sourceType: FilterSourceType,
    val conditionType: FilterConditionType,
    val conditionValue: String,
    val action: FilterAction,
    val description: String? = null,
    val clientId: String? = null,
    val projectId: String? = null,
)
