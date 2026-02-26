package com.jervis.entity

import com.jervis.dto.selfevolution.PromptSectionType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * EPIC 13: System Prompt Self-Evolution.
 *
 * Stores dynamic system prompt sections (base, learned behaviors, user corrections).
 * Each section is versioned — updates increment the version and preserve history.
 */
@Document(collection = "system_prompt_sections")
@CompoundIndex(name = "idx_client_type", def = "{'clientId': 1, 'type': 1}")
data class PromptSectionDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String,
    val type: PromptSectionType,
    var content: String,
    var version: Int = 1,
    var reason: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

/**
 * Learned behavior proposed by agent (pending user approval).
 */
@Document(collection = "learned_behaviors")
@CompoundIndex(name = "idx_client_approved", def = "{'clientId': 1, 'approvedByUser': 1}")
data class LearnedBehaviorDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String,
    val rule: String,
    val reason: String,
    val learnedFrom: String,
    var approvedByUser: Boolean = false,
    val timestamp: Instant = Instant.now(),
)

/**
 * User correction stored as a permanent preference.
 */
@Document(collection = "user_corrections")
@CompoundIndex(name = "idx_client_project", def = "{'clientId': 1, 'projectId': 1}")
data class UserCorrectionDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String,
    val instruction: String,
    val context: String? = null,
    val projectId: String? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * Prompt version history entry (audit trail).
 */
@Document(collection = "prompt_version_history")
@CompoundIndex(name = "idx_client_section", def = "{'clientId': 1, 'sectionType': 1}")
data class PromptVersionHistoryDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val clientId: String,
    val sectionType: PromptSectionType,
    val version: Int,
    val changeType: String,
    val changeSummary: String,
    val previousContent: String? = null,
    val newContent: String,
    val timestamp: Instant = Instant.now(),
)
