package com.jervis.entity.confluence

import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Confluence page index - tracking which pages have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * STATES:
 * - NEW: Full page data from Confluence API, ready for indexing
 * - INDEXED: Minimal tracking record (data in RAG/Graph via sourceUrn)
 * - FAILED: NEW state with error - full data kept for retry
 *
 * FLOW:
 * 1. CentralPoller fetches → saves as NEW with full data
 * 2. ConfluenceContinuousIndexer creates PendingTask → converts to INDEXED (minimal)
 * 3. KoogQualifierAgent stores to RAG/Graph with sourceUrn
 * 4. Future lookups use sourceUrn to find original in Confluence
 *
 * MONGODB STORAGE (single instance, no INDEXING state needed):
 * - NEW/FAILED: Full document with content, comments, attachments
 * - INDEXED: Minimal document - only id, connectionDocumentId, pageId, state, confluenceUpdatedAt
 *
 * BREAKING CHANGE - MIGRATION REQUIRED:
 * Sealed class structure requires _class discriminator field in MongoDB.
 * Old documents without _class will FAIL deserialization (fail-fast design).
 *
 * MIGRATION: Drop a collection before starting the server:
 *   db.confluence_pages.drop()
 *
 * All pages will be re-indexed from Confluence in the next polling cycle.
 */
@Document(collection = "confluence_pages")
@CompoundIndexes(
    CompoundIndex(
        name = "connection_page_version_idx",
        def = "{'connectionDocumentId': 1, 'pageId': 1, 'versionNumber': 1}",
        unique = true,
    ),
)
sealed class ConfluencePageIndexDocument {
    abstract val id: ObjectId
    abstract val connectionDocumentId: ConnectionId
    abstract val pageId: String
    abstract val versionNumber: Int
    abstract val confluenceUpdatedAt: Instant

    /**
     * NEW state - full page data from Confluence API, ready for indexing.
     */
    @TypeAlias("ConfluenceNew")
    data class New(
        @Id
        override val id: ObjectId = ObjectId(),
        val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val confluenceUpdatedAt: Instant,
        override val connectionDocumentId: ConnectionId,
        override val pageId: String,
        override val versionNumber: Int,
        val spaceKey: String?,
        val title: String?,
        val content: String?,
        val parentPageId: String?,
        val pageType: String?,
        val status: String?,
        val creator: String?,
        val lastModifier: String?,
        val labels: List<String>? = emptyList(),
        val comments: List<ConfluenceComment>? = emptyList(),
        val attachments: List<ConfluenceAttachment>? = emptyList(),
        val createdAt: Instant?,
    ) : ConfluencePageIndexDocument()

    /**
     * INDEXED state - minimal tracking record, actual data in RAG/Graph.
     * Only keeps essentials for deduplication and sourceUrn lookup.
     */
    @TypeAlias("ConfluenceIndexed")
    data class Indexed(
        @Id override val id: ObjectId,
        val clientId: ClientId,
        val projectId: ProjectId? = null,
        override var connectionDocumentId: ConnectionId,
        override var pageId: String,
        override var versionNumber: Int,
        override val confluenceUpdatedAt: Instant,
    ) : ConfluencePageIndexDocument()

    /**
     * FAILED state - same as NEW but with error, full data kept for retry.
     */
    @TypeAlias("ConfluenceFailed")
    data class Failed(
        @Id override val id: ObjectId,
        val clientId: ClientId,
        val projectId: ProjectId? = null,
        override val confluenceUpdatedAt: Instant,
        override val connectionDocumentId: ConnectionId,
        override val pageId: String,
        override val versionNumber: Int,
        val spaceKey: String?,
        val title: String,
        val content: String?,
        val parentPageId: String?,
        val pageType: String,
        val status: String,
        val creator: String?,
        val lastModifier: String?,
        val labels: List<String>,
        val comments: List<ConfluenceComment>,
        val attachments: List<ConfluenceAttachment>,
        val createdAt: Instant,
        val indexingError: String,
    ) : ConfluencePageIndexDocument()
}

/**
 * Confluence comment (fetched by CentralPoller, stored in ConfluencePageIndexDocument).
 */
data class ConfluenceComment(
    val id: String,
    val author: String,
    val body: String,
    val created: Instant,
    val updated: Instant,
)

/**
 * Confluence attachment metadata (fetched by CentralPoller).
 */
data class ConfluenceAttachment(
    val id: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    val downloadUrl: String,
    val created: Instant,
)
