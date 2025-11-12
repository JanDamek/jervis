package com.jervis.domain.task

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a pending background task awaiting processing.
 *
 * Design Philosophy:
 * - All task information in `content` field as plain text
 * - No complex context maps - everything model needs is in content
 * - sourceUri only for idempotency/deduplication
 *
 * @property content Complete task description with all data (text format)
 * @property sourceUri Canonical URI for deduplication (e.g., "email://account/msgid", "git://project/commit")
 */
data class PendingTask(
    val id: ObjectId = ObjectId(),
    val taskType: PendingTaskTypeEnum,
    val content: String? = null,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val sourceUri: String? = null,
    val createdAt: Instant = Instant.now(),
    val state: PendingTaskState = PendingTaskState.NEW,
)
