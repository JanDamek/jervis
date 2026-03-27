package com.jervis.task

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * EPIC 4-S3: Persistent approval queue.
 *
 * Stores pending/completed approval requests in MongoDB so they survive
 * server restarts. Replaces the previous in-memory-only queue.
 */
@Document(collection = "approval_queue")
data class ApprovalQueueDocument(
    @Id val id: ObjectId = ObjectId(),
    @Indexed val taskId: String,
    @Indexed val clientId: String,
    val projectId: String? = null,
    val action: String,
    val preview: String,
    val context: String,
    val riskLevel: String = "MEDIUM",
    val payload: Map<String, String> = emptyMap(),
    @Indexed val status: String = "PENDING",  // PENDING, APPROVED, DENIED
    @Indexed val createdAt: Instant = Instant.now(),
    val respondedAt: Instant? = null,
)
