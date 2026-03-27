package com.jervis.infrastructure.indexing

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Tracks text extraction from email/task attachments for Qualifier relevance assessment.
 *
 * FLOW:
 * 1. EmailContinuousIndexer creates records with tikaStatus=PENDING
 * 2. AttachmentExtractionService extracts text async (Tika for docs, VLM for images/PDFs)
 * 3. Qualifier reads extracted text, scores relevance (0.0–1.0)
 * 4. Attachments with relevanceScore >= 0.7 are uploaded to KB
 *
 * This decouples text extraction from KB upload, allowing Qualifier to decide
 * which attachments are worth indexing into RAG/Graph.
 */
@Document(collection = "attachment_extracts")
@CompoundIndexes(
    CompoundIndex(name = "task_status_idx", def = "{'taskId': 1, 'tikaStatus': 1}"),
    CompoundIndex(name = "task_uploaded_idx", def = "{'taskId': 1, 'kbUploaded': 1}"),
)
data class AttachmentExtractDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val taskId: String,
    val filename: String,
    val mimeType: String,
    /** Relative path from workspace root where the binary is stored. */
    val filePath: String,
    /** Extracted plain text content (null until extraction completes). */
    val extractedText: String? = null,
    /** Extraction status: PENDING, SUCCESS, FAILED. */
    val tikaStatus: ExtractionStatus = ExtractionStatus.PENDING,
    /** Extraction method used: TIKA, VLM, DIRECT. */
    val extractionMethod: String? = null,
    /** Relevance score assigned by Qualifier (0.0–1.0, null until scored). */
    val relevanceScore: Double? = null,
    /** Qualifier's reasoning for the relevance score. */
    val relevanceReason: String? = null,
    /** Whether this attachment has been uploaded to KB. */
    val kbUploaded: Boolean = false,
    /** KB document ID after successful upload (null until uploaded). */
    val kbDocId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class ExtractionStatus {
    PENDING,
    SUCCESS,
    FAILED,
}
