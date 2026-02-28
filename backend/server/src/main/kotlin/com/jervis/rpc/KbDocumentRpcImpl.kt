package com.jervis.rpc

import com.jervis.common.types.ClientId
import com.jervis.configuration.KnowledgeServiceRestClient
import com.jervis.configuration.PythonKbDocumentDto
import com.jervis.dto.kb.KbDocumentCategoryEnum
import com.jervis.dto.kb.KbDocumentContentDto
import com.jervis.dto.kb.KbDocumentDeleteDto
import com.jervis.dto.kb.KbDocumentDto
import com.jervis.dto.kb.KbDocumentStateEnum
import com.jervis.dto.kb.KbDocumentSummaryDto
import com.jervis.dto.kb.KbDocumentUpdateDto
import com.jervis.dto.kb.KbDocumentUploadDto
import com.jervis.service.IKbDocumentService
import com.jervis.service.storage.DirectoryStructureService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Base64

@Component
class KbDocumentRpcImpl(
    private val directoryStructureService: DirectoryStructureService,
    private val knowledgeServiceRestClient: KnowledgeServiceRestClient,
) : IKbDocumentService {
    private val logger = KotlinLogging.logger {}

    override suspend fun uploadDocument(request: KbDocumentUploadDto): KbDocumentDto {
        logger.info { "Uploading KB document: filename=${request.filename} client=${request.clientId}" }

        val clientId = ClientId(ObjectId(request.clientId))
        val fileBytes = Base64.getDecoder().decode(request.dataBase64)

        // 1. Compute content hash for deduplication
        val contentHash = sha256(fileBytes)

        // 2. Store the file on shared FS
        val storagePath = directoryStructureService.storeKbDocument(
            clientId = clientId,
            filename = request.filename,
            binaryData = fileBytes,
        )
        logger.info { "KB document stored: storagePath=$storagePath (${fileBytes.size} bytes)" }

        // 3. Send to KB service (creates graph node + extracts text + ingests to RAG)
        val result = knowledgeServiceRestClient.uploadKbDocument(
            clientId = request.clientId,
            projectId = request.projectId,
            filename = request.filename,
            mimeType = request.mimeType,
            storagePath = storagePath,
            fileBytes = fileBytes,
            title = request.title,
            description = request.description,
            category = request.category.name,
            tags = request.tags,
            contentHash = contentHash,
        )

        return result.toDto()
    }

    override suspend fun listDocuments(clientId: String, projectId: String?): List<KbDocumentSummaryDto> {
        val docs = knowledgeServiceRestClient.listKbDocuments(clientId, projectId)
        return docs.map { it.toSummaryDto() }
    }

    override suspend fun getDocument(documentId: String): KbDocumentDto {
        val doc = knowledgeServiceRestClient.getKbDocument(documentId)
            ?: throw IllegalArgumentException("Document not found: $documentId")
        return doc.toDto()
    }

    override suspend fun downloadDocument(documentId: String): KbDocumentContentDto {
        val doc = knowledgeServiceRestClient.getKbDocument(documentId)
            ?: throw IllegalArgumentException("Document not found: $documentId")

        val fileBytes = directoryStructureService.readKbDocument(doc.storagePath)
        return KbDocumentContentDto(
            id = doc.id,
            filename = doc.filename,
            mimeType = doc.mimeType,
            dataBase64 = Base64.getEncoder().encodeToString(fileBytes),
        )
    }

    override suspend fun updateDocument(request: KbDocumentUpdateDto): KbDocumentDto {
        val result = knowledgeServiceRestClient.updateKbDocument(
            docId = request.id,
            title = request.title,
            description = request.description,
            category = request.category?.name,
            tags = request.tags,
        ) ?: throw IllegalArgumentException("Document not found: ${request.id}")
        return result.toDto()
    }

    override suspend fun deleteDocument(request: KbDocumentDeleteDto): Boolean {
        val doc = knowledgeServiceRestClient.getKbDocument(request.id)

        // Delete from KB (graph + RAG)
        val kbDeleted = knowledgeServiceRestClient.deleteKbDocument(request.id)

        // Delete file from shared FS
        if (doc != null && doc.storagePath.isNotBlank()) {
            try {
                directoryStructureService.deleteKbDocument(doc.storagePath)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to delete KB document file: ${doc.storagePath}" }
            }
        }

        return kbDeleted
    }

    override suspend fun reindexDocument(documentId: String): Boolean {
        return knowledgeServiceRestClient.reindexKbDocument(documentId)
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    private fun PythonKbDocumentDto.toDto(): KbDocumentDto = KbDocumentDto(
        id = id,
        clientId = clientId,
        projectId = projectId,
        filename = filename,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        state = try { KbDocumentStateEnum.valueOf(state) } catch (_: Exception) { KbDocumentStateEnum.UPLOADED },
        category = try { KbDocumentCategoryEnum.valueOf(category) } catch (_: Exception) { KbDocumentCategoryEnum.OTHER },
        title = title,
        description = description,
        tags = tags,
        extractedTextPreview = extractedTextPreview,
        pageCount = pageCount,
        contentHash = contentHash,
        sourceUrn = sourceUrn,
        errorMessage = errorMessage,
        uploadedAt = uploadedAt,
        indexedAt = indexedAt,
    )

    private fun PythonKbDocumentDto.toSummaryDto(): KbDocumentSummaryDto = KbDocumentSummaryDto(
        id = id,
        filename = filename,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        state = try { KbDocumentStateEnum.valueOf(state) } catch (_: Exception) { KbDocumentStateEnum.UPLOADED },
        category = try { KbDocumentCategoryEnum.valueOf(category) } catch (_: Exception) { KbDocumentCategoryEnum.OTHER },
        title = title,
        tags = tags,
        uploadedAt = uploadedAt,
        errorMessage = errorMessage,
    )
}
