package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a document with content and metadata for Weaviate vector storage.
 * Optimized for hybrid search (BM25 + vector) with flattened structure.
 */
data class RagDocument(
    /** Main text content for embedding and BM25 search */
    val text: String,
    /** Client ID for data isolation */
    val clientId: ObjectId,
    /** Source type (CODE, EMAIL, CONFLUENCE, etc.) */
    val ragSourceType: RagSourceType,
    /** Project ID for project-level filtering */
    val projectId: ObjectId? = null,
    /** Git branch name */
    val branch: String = "main",
    /** Universal author/sender/creator */
    val from: String? = null,
    /** Universal recipient (for emails, messages) */
    val to: String? = null,
    /** Universal title/subject/topic */
    val subject: String? = null,
    /** Universal temporal marker - ISO 8601 */
    val timestamp: String? = null,
    /** Backlink to original source (email://, http://, file://) */
    val sourceUri: String? = null,
    /** Filename or path */
    val fileName: String? = null,
    /** Confluence page ID */
    val confluencePageId: String? = null,
    /** Confluence space key */
    val confluenceSpaceKey: String? = null,
    /** Chunk index in parent document */
    val chunkId: Int? = null,
    /** Total chunks in parent document */
    val chunkOf: Int? = null,
    /** Parent reference ID for grouping */
    val parentRef: String? = null,
    val createdAt: Instant = Instant.now(),
    val archivedAt: Instant? = null,
    /** Correlation ID for end-to-end tracing across indexing pipeline */
    val correlationId: String? = null,
)
