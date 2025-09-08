package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Status of a document in the RAG system for historical versioning
 */
enum class DocumentStatus {
    /** Current active document that is searched by default */
    CURRENT,

    /** Historical version of a document, searchable only when specifically requested */
    HISTORICAL,

    /** Archived document that is not searchable but preserved for audit purposes */
    ARCHIVED,
}

/**
 * Represents a document with content and metadata.
 * This class is used for storing and retrieving documents from the vector database.
 */
data class RagDocument(
    val projectId: ObjectId,
    val documentType: RagDocumentType,
    val ragSourceType: RagSourceType,
    val pageContent: String,
    // Optional payload metadata (to align with advanced docs/Qdrant payload requirements)
    val clientId: ObjectId? = null,
    val source: String? = null,
    val language: String? = null,
    val module: String? = null,
    val path: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val methodName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isDefaultBranch: Boolean = true,
    val inspirationOnly: Boolean = false,
    val createdAt: Instant = Instant.now(),
    // Historical versioning and Git tracking fields
    val documentStatus: DocumentStatus = DocumentStatus.CURRENT,
    val gitCommitHash: String? = null,
    val version: Int = 1,
    val archivedAt: Instant? = null,
    val lastModified: Instant = Instant.now(),
)
