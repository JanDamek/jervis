package com.jervis.entity

import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.PendingTaskStateEnum
import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.types.ClientId
import com.jervis.types.ProjectId
import com.jervis.types.SourceUrn
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Pending task entity - used directly in the service layer (no domain mapping needed).
 *
 * Design Philosophy:
 * - All task information lives in the `content` field as plain text.
 * - No context/enrichment maps. The model must rely on `content` only.
 * - If provenance is needed, include a "Source:" line inside content, for example,
 *   - Source: email://<accountId>/<messageId>
 *   - Source: confluence://<accountId>/<pageId>
 *   - Source: git file://<projectId>/<commitHash>/<filePath>
 *
 * Vision Augmentation:
 * - `attachments` contains binary references for vision model processing
 * - Qualifier Agent loads attachments on-demand and augments content with vision descriptions
 *
 * @property content Complete task description with all data (text format)
 * @property correlationId Unique ID that tracks the entire execution flow across all services
 * @property attachments List of attachments for vision analysis (images, PDFs)
 */
@Document(collection = "pending_tasks")
data class PendingTaskDocument(
    @Id
    val id: ObjectId = ObjectId(),
    @Indexed
    val type: PendingTaskTypeEnum,
    val content: String,
    val projectId: ProjectId? = null,
    val clientId: ClientId,
    @Indexed
    val createdAt: Instant = Instant.now(),
    @Indexed
    var state: PendingTaskStateEnum = PendingTaskStateEnum.NEW,
    val correlationId: String = id.toString(),
    val sourceUrn: SourceUrn,
    val errorMessage: String? = null,
    val qualificationRetries: Int = 0,
    /** Attachments for vision analysis (lazy loaded by Qualifier Agent) */
    val attachments: List<AttachmentMetadata> = emptyList(),
)
