package com.jervis.infrastructure.grpc

import com.jervis.contracts.common.RequestContext
import com.jervis.contracts.common.Scope
import com.jervis.contracts.knowledgebase.Document
import com.jervis.contracts.knowledgebase.DocumentAck
import com.jervis.contracts.knowledgebase.DocumentCategory
import com.jervis.contracts.knowledgebase.DocumentId
import com.jervis.contracts.knowledgebase.DocumentList
import com.jervis.contracts.knowledgebase.DocumentListRequest
import com.jervis.contracts.knowledgebase.DocumentRegisterRequest
import com.jervis.contracts.knowledgebase.DocumentUpdateRequest
import com.jervis.contracts.knowledgebase.KnowledgeDocumentServiceGrpcKt
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.util.UUID

// Kotlin-side client for jervis.knowledgebase.KnowledgeDocumentService.
// Covers metadata-only RPCs (Register, List, Get, Update, Delete, Reindex);
// Upload (inline bytes) and ExtractText (file upload) stay on REST until
// the blob-side-channel slice lands.
@Component
class KbDocumentGrpcClient(
    @Qualifier(GrpcChannels.KNOWLEDGEBASE_CHANNEL) channel: ManagedChannel,
) {
    private val stub = KnowledgeDocumentServiceGrpcKt.KnowledgeDocumentServiceCoroutineStub(channel)

    private fun ctx(clientId: String = ""): RequestContext =
        RequestContext.newBuilder()
            .setScope(Scope.newBuilder().setClientId(clientId).build())
            .setRequestId(UUID.randomUUID().toString())
            .setIssuedAtUnixMs(System.currentTimeMillis())
            .build()

    private fun categoryFromString(value: String?): DocumentCategory = when (value?.uppercase()) {
        "TECHNICAL" -> DocumentCategory.DOCUMENT_CATEGORY_TECHNICAL
        "BUSINESS" -> DocumentCategory.DOCUMENT_CATEGORY_BUSINESS
        "LEGAL" -> DocumentCategory.DOCUMENT_CATEGORY_LEGAL
        "PROCESS" -> DocumentCategory.DOCUMENT_CATEGORY_PROCESS
        "MEETING_NOTES" -> DocumentCategory.DOCUMENT_CATEGORY_MEETING_NOTES
        "REPORT" -> DocumentCategory.DOCUMENT_CATEGORY_REPORT
        "SPECIFICATION" -> DocumentCategory.DOCUMENT_CATEGORY_SPECIFICATION
        "OTHER", null, "" -> DocumentCategory.DOCUMENT_CATEGORY_OTHER
        else -> DocumentCategory.DOCUMENT_CATEGORY_OTHER
    }

    suspend fun register(
        clientId: String,
        projectId: String?,
        filename: String,
        mimeType: String,
        sizeBytes: Long,
        storagePath: String,
        title: String?,
        description: String?,
        category: String,
        tags: List<String>,
        contentHash: String?,
    ): Document =
        stub.register(
            DocumentRegisterRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .setFilename(filename)
                .setMimeType(mimeType)
                .setSizeBytes(sizeBytes)
                .setStoragePath(storagePath)
                .setTitle(title ?: "")
                .setDescription(description ?: "")
                .setCategory(categoryFromString(category))
                .addAllTags(tags)
                .setContentHash(contentHash ?: "")
                .build(),
        )

    suspend fun list(clientId: String, projectId: String?): DocumentList =
        stub.list(
            DocumentListRequest.newBuilder()
                .setCtx(ctx(clientId))
                .setClientId(clientId)
                .setProjectId(projectId ?: "")
                .build(),
        )

    suspend fun get(docId: String): Document =
        stub.get(DocumentId.newBuilder().setCtx(ctx()).setId(docId).build())

    suspend fun update(
        docId: String,
        title: String?,
        description: String?,
        category: String?,
        tags: List<String>?,
    ): Document {
        val mask = mutableListOf<String>()
        val builder = DocumentUpdateRequest.newBuilder()
            .setCtx(ctx())
            .setId(docId)
        title?.let { builder.setTitle(it); mask += "title" }
        description?.let { builder.setDescription(it); mask += "description" }
        category?.let { builder.setCategory(categoryFromString(it)); mask += "category" }
        tags?.let { builder.addAllTags(it); mask += "tags" }
        builder.addAllFieldMask(mask)
        return stub.update(builder.build())
    }

    suspend fun delete(docId: String): DocumentAck =
        stub.delete(DocumentId.newBuilder().setCtx(ctx()).setId(docId).build())

    suspend fun reindex(docId: String): DocumentAck =
        stub.reindex(DocumentId.newBuilder().setCtx(ctx()).setId(docId).build())
}
