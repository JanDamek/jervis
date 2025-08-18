package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

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
    val timestamp: Long = System.currentTimeMillis(),
    val isDefaultBranch: Boolean = true,
    val inspirationOnly: Boolean = false,
    val createdAt: Instant = Instant.now(),
)
