package com.jervis.koog.vision

import com.jervis.types.ClientId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Cache for vision analysis results to avoid re-analyzing the same attachments.
 *
 * Key: (storagePath, clientId) - unique per attachment file
 * When a process crashes during vision analysis, already analyzed attachments
 * can be reused from this cache.
 *
 * @property storagePath Path to the attachment file in storage
 * @property clientId Client that owns the attachment
 * @property filename Original filename for logging/debugging
 * @property mimeType MIME type of the attachment
 * @property description Complete visual description from vision model
 * @property extractedText Extracted text content (currently in description)
 * @property entities Identified entities (currently in description)
 * @property metadata Additional metadata (currently in description)
 * @property analyzedAt When this attachment was analyzed
 */
@Document(collection = "attachment_analysis_cache")
@CompoundIndexes(
    CompoundIndex(name = "storage_path_client_idx", def = "{'storagePath': 1, 'clientId': 1}", unique = true),
    CompoundIndex(name = "analyzed_at_idx", def = "{'analyzedAt': -1}"),
)
data class AttachmentAnalysisCacheDocument(
    @Id val id: ObjectId = ObjectId.get(),
    val storagePath: String,
    val clientId: ClientId,
    val filename: String,
    val mimeType: String,
    val description: String,
    val extractedText: String,
    val entities: List<String>,
    val metadata: List<String>,
    val analyzedAt: Instant = Instant.now(),
)
