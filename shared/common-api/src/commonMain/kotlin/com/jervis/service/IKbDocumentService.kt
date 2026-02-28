package com.jervis.service

import com.jervis.dto.kb.KbDocumentContentDto
import com.jervis.dto.kb.KbDocumentDeleteDto
import com.jervis.dto.kb.KbDocumentDto
import com.jervis.dto.kb.KbDocumentSummaryDto
import com.jervis.dto.kb.KbDocumentUpdateDto
import com.jervis.dto.kb.KbDocumentUploadDto
import kotlinx.rpc.annotations.Rpc

@Rpc
interface IKbDocumentService {
    /** Upload a new document to KB. Stores the file on FS and creates a metadata record. */
    suspend fun uploadDocument(request: KbDocumentUploadDto): KbDocumentDto

    /** List all documents for a client (optionally filtered by project). */
    suspend fun listDocuments(clientId: String, projectId: String?): List<KbDocumentSummaryDto>

    /** Get full document details by ID. */
    suspend fun getDocument(documentId: String): KbDocumentDto

    /** Download the original document binary (base64-encoded). */
    suspend fun downloadDocument(documentId: String): KbDocumentContentDto

    /** Update document metadata (title, description, category, tags). */
    suspend fun updateDocument(request: KbDocumentUpdateDto): KbDocumentDto

    /** Delete a document (removes file from FS, purges KB data, deletes metadata). */
    suspend fun deleteDocument(request: KbDocumentDeleteDto): Boolean

    /** Re-index a document in KB (purge old data and re-ingest). */
    suspend fun reindexDocument(documentId: String): Boolean
}
