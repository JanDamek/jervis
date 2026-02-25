package com.jervis.dto.selfevolution

import kotlinx.serialization.Serializable

/**
 * EPIC 13: System Prompt Self-Evolution DTOs.
 *
 * Supports prompt architecture with base + dynamic sections,
 * behavior learning loop, user corrections, and version history.
 */

/**
 * Types of dynamic prompt sections.
 */
@Serializable
enum class PromptSectionType {
    /** Core identity and behavior — never auto-modified. */
    BASE,
    /** Agent-learned behavior rules (with user approval). */
    LEARNED_BEHAVIORS,
    /** User-provided corrections and preferences. */
    USER_CORRECTIONS,
}

/**
 * A single dynamic prompt section.
 */
@Serializable
data class PromptSection(
    val id: String,
    val type: PromptSectionType,
    val content: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Int = 1,
    val clientId: String,
    val reason: String? = null,
)

/**
 * A learned behavior rule proposed by the agent.
 */
@Serializable
data class LearnedBehavior(
    val id: String,
    val rule: String,
    val reason: String,
    val learnedFrom: String,
    val approvedByUser: Boolean = false,
    val timestamp: String,
    val clientId: String,
)

/**
 * A user correction stored as a permanent preference.
 */
@Serializable
data class UserCorrection(
    val id: String,
    val instruction: String,
    val context: String? = null,
    val timestamp: String,
    val clientId: String,
    val projectId: String? = null,
)

/**
 * Prompt version history entry.
 */
@Serializable
data class PromptVersionEntry(
    val version: Int,
    val timestamp: String,
    val changeType: String,
    val changeSummary: String,
    val sectionType: PromptSectionType,
)
