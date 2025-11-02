package com.jervis.domain.rag

import org.bson.types.ObjectId
import java.time.Instant

/**
 * Represents a document with content and metadata.
 * This class is used for storing and retrieving documents from the vector database.
 */
data class RagDocument(
    val projectId: ObjectId? = null,
    val clientId: ObjectId,
    val summary: String = "",
    val ragSourceType: RagSourceType,
    val language: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val parentClass: String? = null,
    val methodName: String? = null,
    val createdAt: Instant = Instant.now(),
    val gitCommitHash: String? = null,
    val archivedAt: Instant? = null,
    // Enhanced metadata fields for Joern-based indexing
    val lineStart: Int? = null,
    val lineEnd: Int? = null,
    /** Symbol name (method/class name from Joern) */
    val symbolName: String? = null,
    /** Joern CPG node ID for traceability */
    val joernNodeId: String? = null,
    val safetyTags: List<String> = listOf(),
    val chunkId: Int? = null,
    val chunkOf: Int? = null,
    val branch: String = "main",
    /** Backlink to original content for retrieval in backend (e.g., email://<accountId>/<messageId> or url) */
    val sourceUri: String? = null,
    // ========== UNIVERSAL METADATA FIELDS (for all source types) ==========
    /** Universal author/sender/creator (email sender, commit author, meeting organizer, message author) */
    val from: String? = null,
    /** Universal title/subject/topic (email subject, meeting title, commit message, document title) */
    val subject: String? = null,
    /** Universal temporal marker - ISO 8601 date string (email received, commit date, meeting date, last modified) */
    val timestamp: String? = null,
    /** Universal parent reference ID for grouping related chunks (emailMessageId, commitHash, meetingId) */
    val parentRef: String? = null,
    /** Universal index within parent (attachment index, chunk index, section index) */
    val indexInParent: Int? = null,
    /** Total count of siblings (attachments count, methods in class, sections in document) */
    val totalSiblings: Int? = null,
    /** Universal content/file type (MIME type, file extension, language, format) */
    val contentType: String? = null,
    /** Universal filename/path (attachment filename, source file, document path, transcript file) */
    val fileName: String? = null,
    /**
     * List of vector store IDs where this document is indexed.
     * Used for tracking and cleanup when the source document is deleted.
     * Each entry corresponds to an embedding stored in the vector database.
     */
    val vectorStoreIds: List<String> = emptyList(),
    // ========== CONFLUENCE-SPECIFIC METADATA ==========
    /** Confluence page ID (used for RagSourceType.CONFLUENCE_PAGE) */
    val confluencePageId: String? = null,
    /** Confluence space key (used for RagSourceType.CONFLUENCE_PAGE) */
    val confluenceSpaceKey: String? = null,
)
