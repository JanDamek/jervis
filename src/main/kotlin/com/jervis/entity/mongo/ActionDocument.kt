package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document representing actions for a project.
 * This document stores metadata about actions that are indexed in the vector database.
 * The full content is stored only in the vector database, not in MongoDB.
 */
@Document(collection = "project_actions")
data class ActionDocument(
    /**
     * Unique identifier for the action, matches the ID in the vector database.
     */
    @Id
    val id: ObjectId? = null,
    /**
     * ID of the project this action belongs to.
     */
    val projectId: ObjectId,
    /**
     * Brief summary or description of the action content.
     */
    val contentSummary: String,
    /**
     * Type of the action (e.g., NOTIFY, SEND_EMAIL, OPEN_CHAT).
     */
    val actionType: String,
    /**
     * Target of the action (e.g., email, slack username).
     */
    val target: String? = null,
    /**
     * Time when the action should be triggered.
     */
    val triggerTime: Instant? = null,
    /**
     * Status of the action (e.g., SCHEDULED, COMPLETED, CANCELLED).
     */
    val status: String = "SCHEDULED",
    /**
     * User who created the action.
     */
    val createdBy: String? = null,
    /**
     * Timestamp when the action was created.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Timestamp when the action was last updated.
     */
    val updatedAt: Instant = Instant.now(),
    /**
     * Additional metadata as key-value pairs.
     */
    val metadata: Map<String, Any> = emptyMap(),
)
