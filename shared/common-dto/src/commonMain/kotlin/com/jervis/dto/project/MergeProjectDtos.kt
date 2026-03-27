package com.jervis.dto.project

import kotlinx.serialization.Serializable

/**
 * Preview of what will happen when merging two projects.
 * Contains detected conflicts that need user resolution.
 */
@Serializable
data class MergePreviewDto(
    val sourceProject: String,
    val targetProject: String,
    /** Data that will be moved without conflict (just projectId update) */
    val autoMigrate: List<MergeMigrationDto> = emptyList(),
    /** Conflicts requiring user decision */
    val conflicts: List<MergeConflictDto> = emptyList(),
)

@Serializable
data class MergeMigrationDto(
    val collection: String,
    val count: Int,
)

@Serializable
data class MergeConflictDto(
    /** Unique key for this conflict (e.g., "guidelines", "preference:codingStyle") */
    val key: String,
    /** Human-readable label */
    val label: String,
    /** Value from source project */
    val sourceValue: String,
    /** Value from target project */
    val targetValue: String,
    /** Can both values coexist? (e.g., resources: yes, description: no) */
    val canMergeBoth: Boolean = false,
    /** Category: SETTING, TEXT, RESOURCE */
    val category: String = "SETTING",
    /** AI-suggested merged text (for TEXT category, generated via cascade LLM) */
    val aiMergedValue: String? = null,
)

/**
 * User's resolution for each conflict.
 */
@Serializable
data class MergeResolutionDto(
    val key: String,
    /** KEEP_SOURCE, KEEP_TARGET, MERGE_BOTH, CUSTOM */
    val resolution: String,
    /** Custom merged value (for TEXT conflicts resolved by user or GPU) */
    val customValue: String? = null,
)

/**
 * Request to execute merge with conflict resolutions.
 */
@Serializable
data class MergeExecuteDto(
    val sourceProjectId: String,
    val targetProjectId: String,
    val resolutions: List<MergeResolutionDto> = emptyList(),
)
