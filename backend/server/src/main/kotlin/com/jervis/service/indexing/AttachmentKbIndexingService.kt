package com.jervis.service.indexing

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.configuration.KnowledgeServiceRestClient
import com.jervis.service.storage.DirectoryStructureService
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Service for indexing attachments from external sources (email, Jira, Confluence)
 * as KB documents. Stores the original file and registers it with the Knowledge Base
 * for text extraction, RAG indexing, and graph integration.
 *
 * This enables attachments to be searchable directly in the KB rather than only
 * being accessible through their original source (email, issue tracker, wiki).
 */
@Service
class AttachmentKbIndexingService(
    private val directoryStructureService: DirectoryStructureService,
    private val knowledgeServiceRestClient: KnowledgeServiceRestClient,
) {
    /**
     * Index an attachment as a KB document.
     *
     * Stores the binary data as a KB document on the shared filesystem,
     * then registers it with the Python KB service for text extraction and RAG indexing.
     *
     * @param clientId Client owning this attachment
     * @param projectId Optional project association
     * @param filename Original filename of the attachment
     * @param mimeType MIME type (e.g., "application/pdf", "image/png")
     * @param binaryData Raw binary content of the attachment
     * @param sourceUrn Source URN identifying the attachment's origin
     * @param title Human-readable title for the KB document
     * @param description Description providing context about the attachment
     * @param tags Tags for categorization
     * @return true if successfully indexed, false on error
     */
    suspend fun indexAttachmentAsKbDocument(
        clientId: ClientId,
        projectId: ProjectId?,
        filename: String,
        mimeType: String,
        binaryData: ByteArray,
        sourceUrn: SourceUrn,
        title: String,
        description: String,
        tags: List<String> = emptyList(),
    ): Boolean {
        if (binaryData.isEmpty()) {
            logger.debug { "Skipping empty attachment: $filename" }
            return false
        }

        return try {
            val contentHash = computeSha256(binaryData)

            // Store binary data as KB document on shared filesystem
            val storagePath = directoryStructureService.storeKbDocument(
                clientId = clientId,
                filename = filename,
                binaryData = binaryData,
            )

            logger.info {
                "Stored attachment as KB document: filename=$filename storagePath=$storagePath " +
                    "size=${binaryData.size} clientId=$clientId"
            }

            // Register with Python KB service for extraction + RAG indexing
            val category = categorizeByMimeType(mimeType)
            knowledgeServiceRestClient.registerKbDocument(
                clientId = clientId.value.toHexString(),
                projectId = projectId?.value?.toHexString(),
                filename = filename,
                mimeType = mimeType,
                sizeBytes = binaryData.size.toLong(),
                storagePath = storagePath,
                title = title,
                description = description,
                category = category,
                tags = tags,
                contentHash = contentHash,
            )

            logger.info {
                "Registered attachment as KB document: filename=$filename sourceUrn=${sourceUrn.value}"
            }
            true
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to index attachment as KB document: filename=$filename clientId=$clientId error=${e.message}"
            }
            false
        }
    }

    /**
     * Index an already-stored attachment as a KB document.
     *
     * For attachments that are already on the shared filesystem (e.g., Jira/Confluence
     * attachments stored via DirectoryStructureService.storeAttachment()), this method
     * copies the file to kb-documents and registers it with the KB service.
     *
     * @param clientId Client owning this attachment
     * @param projectId Optional project association
     * @param filename Original filename
     * @param mimeType MIME type
     * @param sizeBytes File size in bytes
     * @param existingStoragePath Existing relative path in attachments/ directory
     * @param sourceUrn Source URN identifying the attachment's origin
     * @param title Human-readable title for the KB document
     * @param description Description providing context
     * @param tags Tags for categorization
     * @return true if successfully indexed, false on error
     */
    suspend fun indexStoredAttachmentAsKbDocument(
        clientId: ClientId,
        projectId: ProjectId?,
        filename: String,
        mimeType: String,
        sizeBytes: Long,
        existingStoragePath: String,
        sourceUrn: SourceUrn,
        title: String,
        description: String,
        tags: List<String> = emptyList(),
    ): Boolean {
        return try {
            // Read binary from existing attachment storage
            val binaryData = directoryStructureService.readAttachment(existingStoragePath)
            val contentHash = computeSha256(binaryData)

            // Store a copy as KB document (separate lifecycle from task attachments)
            val kbStoragePath = directoryStructureService.storeKbDocument(
                clientId = clientId,
                filename = filename,
                binaryData = binaryData,
            )

            logger.info {
                "Copied attachment to KB documents: filename=$filename " +
                    "from=$existingStoragePath to=$kbStoragePath clientId=$clientId"
            }

            // Register with Python KB service for extraction + RAG indexing
            val category = categorizeByMimeType(mimeType)
            knowledgeServiceRestClient.registerKbDocument(
                clientId = clientId.value.toHexString(),
                projectId = projectId?.value?.toHexString(),
                filename = filename,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                storagePath = kbStoragePath,
                title = title,
                description = description,
                category = category,
                tags = tags,
                contentHash = contentHash,
            )

            logger.info {
                "Registered stored attachment as KB document: filename=$filename sourceUrn=${sourceUrn.value}"
            }
            true
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to index stored attachment as KB document: filename=$filename " +
                    "existingPath=$existingStoragePath error=${e.message}"
            }
            false
        }
    }

    /**
     * Register an attachment already stored in kb-documents/ directory with the KB service.
     *
     * For attachments whose binary was already stored during polling (e.g., email attachments
     * stored by EmailPollingHandlerBase), this method only registers with the KB service
     * without re-storing the file.
     *
     * @param clientId Client owning this attachment
     * @param projectId Optional project association
     * @param filename Original filename
     * @param mimeType MIME type
     * @param sizeBytes File size in bytes
     * @param kbDocumentStoragePath Relative path in kb-documents/ directory
     * @param sourceUrn Source URN identifying the attachment's origin
     * @param title Human-readable title for the KB document
     * @param description Description providing context
     * @param tags Tags for categorization
     * @return true if successfully registered, false on error
     */
    suspend fun registerPreStoredAttachment(
        clientId: ClientId,
        projectId: ProjectId?,
        filename: String,
        mimeType: String,
        sizeBytes: Long,
        kbDocumentStoragePath: String,
        sourceUrn: SourceUrn,
        title: String,
        description: String,
        tags: List<String> = emptyList(),
    ): Boolean {
        return try {
            // Read binary to compute content hash for deduplication
            val binaryData = directoryStructureService.readKbDocument(kbDocumentStoragePath)
            val contentHash = computeSha256(binaryData)

            // Register with Python KB service for extraction + RAG indexing
            val category = categorizeByMimeType(mimeType)
            knowledgeServiceRestClient.registerKbDocument(
                clientId = clientId.value.toHexString(),
                projectId = projectId?.value?.toHexString(),
                filename = filename,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                storagePath = kbDocumentStoragePath,
                title = title,
                description = description,
                category = category,
                tags = tags,
                contentHash = contentHash,
            )

            logger.info {
                "Registered pre-stored attachment as KB document: filename=$filename " +
                    "storagePath=$kbDocumentStoragePath sourceUrn=${sourceUrn.value}"
            }
            true
        } catch (e: Exception) {
            logger.error(e) {
                "Failed to register pre-stored attachment: filename=$filename " +
                    "storagePath=$kbDocumentStoragePath error=${e.message}"
            }
            false
        }
    }

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun categorizeByMimeType(mimeType: String): String =
        when {
            mimeType.startsWith("image/") -> "OTHER"
            mimeType == "application/pdf" -> "REPORT"
            mimeType.contains("word") || mimeType.contains("document") -> "REPORT"
            mimeType.contains("excel") || mimeType.contains("spreadsheet") -> "REPORT"
            mimeType.contains("powerpoint") || mimeType.contains("presentation") -> "REPORT"
            mimeType.startsWith("text/") -> "TECHNICAL"
            else -> "OTHER"
        }
}
