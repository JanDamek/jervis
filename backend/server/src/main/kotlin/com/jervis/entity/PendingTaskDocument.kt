package com.jervis.entity

import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Pending task entity - used directly in service layer (no domain mapping needed).
 *
 * Design Philosophy:
 * - All task information lives in the `content` field as plain text.
 * - No context/enrichment maps. The model must rely on `content` only.
 * - If provenance is needed, include a "Source:" line inside content, for example:
 *   - Source: email://<accountId>/<messageId>
 *   - Source: confluence://<accountId>/<pageId>
 *   - Source: gitfile://<projectId>/<commitHash>/<filePath>
 *
 * @property content Complete task description with all data (text format)
 * @property correlationId Unique ID that tracks entire execution flow across all services
 */
@Document(collection = "pending_tasks")
data class PendingTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val type: PendingTaskTypeEnum,
    val content: String,
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    @Indexed
    val createdAt: Instant = Instant.now(),
    @Indexed
    val state: PendingTaskStateEnum = PendingTaskStateEnum.NEW,
    val correlationId: String = id.toHexString(),
)
