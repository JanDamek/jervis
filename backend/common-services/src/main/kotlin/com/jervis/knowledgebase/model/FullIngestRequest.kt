package com.jervis.knowledgebase.model

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import java.time.Instant

/**
 * Request for full document ingestion with attachments.
 * Used for emails, wiki pages, issues with attached files.
 *
 * [sourceType] is the wire-format string identifier matching the KB Python
 * SourceType enum (see backend/service-knowledgebase/app/api/models.py).
 * Use [com.jervis.common.types.SourceUrn.kbSourceType] to derive it from a
 * SourceUrn.
 */
data class FullIngestRequest(
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val groupId: String? = null,
    val sourceUrn: String,
    val sourceType: String,
    val subject: String? = null, // Email subject, page title, issue key
    val content: String, // Main text content
    val metadata: Map<String, String> = emptyMap(),
    val observedAt: Instant = Instant.now(),
    val attachments: List<Attachment> = emptyList(),
)

/**
 * Attachment to be processed (vision for images, Tika for docs).
 */
data class Attachment(
    val filename: String,
    val contentType: String? = null,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Attachment
        return filename == other.filename && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
