package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a pending background task awaiting processing.
 *
 * Design Philosophy:
 * - All task information lives in the `content` field as plain text.
 * - No context/enrichment maps. The model must rely on `content` only.
 * No extra context maps or out-of-band identifiers are allowed.
 * If provenance is needed, include a "Source:" line inside content, for example:
 * - Source: email://<accountId>/<messageId>
 * - Source: confluence://<accountId>/<pageId>
 * - Source: gitfile://<projectId>/<commitHash>/<filePath>
 * - Source: project://<projectId>/description-update
 *
 * @property content Complete task description with all data (text format)
 * @property correlationId Unique ID that tracks entire execution flow across all services
 */
data class PendingTask(
    val id: ObjectId = ObjectId(),
    val taskType: PendingTaskTypeEnum,
    val content: String,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val createdAt: Instant = Instant.now(),
    val state: PendingTaskState = PendingTaskState.NEW,
    val correlationId: String = ObjectId.get().toHexString(), // For distributed tracing
)
