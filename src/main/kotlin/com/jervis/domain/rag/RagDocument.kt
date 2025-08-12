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
    val createdAt: Instant = Instant.now(),
)
