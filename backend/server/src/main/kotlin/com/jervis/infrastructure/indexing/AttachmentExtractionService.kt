package com.jervis.infrastructure.indexing

import com.jervis.infrastructure.llm.KnowledgeServiceRestClient
import com.jervis.infrastructure.indexing.AttachmentExtractDocument
import com.jervis.infrastructure.indexing.ExtractionStatus
import com.jervis.infrastructure.indexing.AttachmentExtractRepository
import com.jervis.infrastructure.storage.DirectoryStructureService
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Async text extraction from email/task attachments.
 *
 * Delegates to Python KB service which uses:
 * - VLM (qwen3-vl-tool) for images and scanned PDFs
 * - Tika for structured documents (DOCX, XLSX, structured PDFs)
 * - Direct read for plain text files
 *
 * Extracted text is stored in AttachmentExtractDocument for Qualifier
 * to assess relevance before deciding on KB upload.
 */
@Service
class AttachmentExtractionService(
    private val attachmentExtractRepo: AttachmentExtractRepository,
    private val directoryStructureService: DirectoryStructureService,
    private val knowledgeServiceRestClient: KnowledgeServiceRestClient,
) {
    companion object {
        /** Max attachment size for text extraction (20 MB). */
        const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024L

        /** MIME types eligible for text extraction. */
        val ALLOWED_MIME_TYPES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/plain",
            "text/csv",
            "image/png",
            "image/jpeg",
        )

        fun isAllowedMimeType(mimeType: String): Boolean =
            ALLOWED_MIME_TYPES.any { mimeType.startsWith(it) }
    }

    /**
     * Create extract records for task attachments and trigger async extraction.
     *
     * @return Number of extract records created
     */
    suspend fun createExtractsForAttachments(
        taskId: String,
        attachments: List<AttachmentInfo>,
    ): Int {
        val eligible = attachments.filter { att ->
            when {
                !isAllowedMimeType(att.mimeType) -> {
                    logger.debug { "Skipping unsupported MIME type: ${att.filename} (${att.mimeType})" }
                    false
                }
                att.sizeBytes > MAX_ATTACHMENT_BYTES -> {
                    logger.warn { "Skipping oversized attachment: ${att.filename} (${att.sizeBytes} bytes > $MAX_ATTACHMENT_BYTES)" }
                    false
                }
                att.storagePath == null -> {
                    logger.debug { "Skipping attachment without storage path: ${att.filename}" }
                    false
                }
                else -> true
            }
        }

        if (eligible.isEmpty()) return 0

        var created = 0
        for (att in eligible) {
            val doc = AttachmentExtractDocument(
                taskId = taskId,
                filename = att.filename,
                mimeType = att.mimeType,
                filePath = att.storagePath!!,
                tikaStatus = ExtractionStatus.PENDING,
            )
            attachmentExtractRepo.save(doc)
            created++
        }

        logger.info { "Created $created attachment extract records for task $taskId" }
        return created
    }

    /**
     * Process all PENDING extracts — read binary, call KB extract-text, update record.
     *
     * Called async after email indexing. Failures are logged and recorded
     * without blocking the overall indexing flow.
     */
    suspend fun processPendingExtracts(taskId: String) {
        val pending = attachmentExtractRepo
            .findByTaskIdAndTikaStatus(taskId, ExtractionStatus.PENDING)
            .toList()

        if (pending.isEmpty()) return

        logger.info { "Processing ${pending.size} pending attachment extracts for task $taskId" }

        for (extract in pending) {
            try {
                extractText(extract)
            } catch (e: Exception) {
                logger.warn(e) { "Text extraction failed for ${extract.filename}: ${e.message}" }
                attachmentExtractRepo.save(
                    extract.copy(
                        tikaStatus = ExtractionStatus.FAILED,
                        updatedAt = Instant.now(),
                    ),
                )
            }
        }
    }

    private suspend fun extractText(extract: AttachmentExtractDocument) {
        val fileBytes = try {
            directoryStructureService.readKbDocument(extract.filePath)
        } catch (e: Exception) {
            logger.warn { "Cannot read file ${extract.filePath}: ${e.message}" }
            attachmentExtractRepo.save(
                extract.copy(tikaStatus = ExtractionStatus.FAILED, updatedAt = Instant.now()),
            )
            return
        }

        val result = knowledgeServiceRestClient.extractText(
            filename = extract.filename,
            mimeType = extract.mimeType,
            fileBytes = fileBytes,
        )

        if (result.error != null) {
            logger.warn { "KB extract-text error for ${extract.filename}: ${result.error}" }
            attachmentExtractRepo.save(
                extract.copy(tikaStatus = ExtractionStatus.FAILED, updatedAt = Instant.now()),
            )
            return
        }

        attachmentExtractRepo.save(
            extract.copy(
                extractedText = result.extractedText,
                extractionMethod = result.method,
                tikaStatus = ExtractionStatus.SUCCESS,
                updatedAt = Instant.now(),
            ),
        )

        logger.info {
            "Extracted ${result.extractedText.length} chars from ${extract.filename} " +
                "via ${result.method} for task ${extract.taskId}"
        }
    }

    /**
     * Get all successfully extracted attachment texts for a task.
     */
    suspend fun getExtractsForTask(taskId: String): List<AttachmentExtractDocument> =
        attachmentExtractRepo.findByTaskIdAndTikaStatus(taskId, ExtractionStatus.SUCCESS).toList()

    /**
     * Update relevance score and optionally mark as KB-uploaded.
     */
    suspend fun updateRelevance(
        extractId: org.bson.types.ObjectId,
        score: Double,
        reason: String,
        kbUploaded: Boolean = false,
        kbDocId: String? = null,
    ) {
        val extract = attachmentExtractRepo.findById(extractId) ?: return
        attachmentExtractRepo.save(
            extract.copy(
                relevanceScore = score,
                relevanceReason = reason,
                kbUploaded = kbUploaded,
                kbDocId = kbDocId,
                updatedAt = Instant.now(),
            ),
        )
    }
}

/**
 * Minimal attachment info for creating extract records.
 */
data class AttachmentInfo(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String?,
)
