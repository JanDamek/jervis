package com.jervis.entity.confluence

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Confluence page document with COMPLETE data for indexing.
 *
 * Architecture:
 * - CentralPoller fetches FULL page data from API and saves here as NEW
 * - ConfluenceContinuousIndexer reads from MongoDB (no API calls) and indexes to RAG
 * - MongoDB is staging area between API and RAG
 *
 * Note: connectionId refers to Connection.id (HttpConnection for Atlassian)
 */
@Document(collection = "confluence_pages")
@CompoundIndexes(
    CompoundIndex(name = "connection_page_unique", def = "{'connectionId': 1, 'pageId': 1}", unique = true),
    CompoundIndex(name = "client_idx", def = "{'clientId': 1}"),
    CompoundIndex(name = "connection_state_idx", def = "{'connectionId': 1, 'state': 1}"),
    CompoundIndex(name = "state_updated_idx", def = "{'state': 1, 'updatedAt': -1}"),
)
data class ConfluencePageIndexDocument(
    @Id
    val id: ObjectId = ObjectId.get(),

    /** Connection ID (Connection.HttpConnection) */
    @Indexed
    val connectionId: ObjectId,

    /** Client ID */
    @Indexed
    val clientId: ObjectId,

    /** Confluence page ID (e.g., "123456789") */
    @Indexed
    val pageId: String,

    /** Space key (e.g., "PROJ") */
    val spaceKey: String,

    // === FULL CONTENT (fetched by CentralPoller) ===

    /** Page title */
    val title: String,

    /** Full content in storage format (HTML/wiki markup) */
    val content: String? = null,

    /** Parent page ID (if this is a child page) */
    val parentPageId: String? = null,

    /** Page type (page, blogpost, etc.) */
    val pageType: String = "page",

    /** Page status (current, archived, deleted) */
    val status: String = "current",

    /** Creator account ID */
    val creator: String? = null,

    /** Last modifier account ID */
    val lastModifier: String? = null,

    /** Labels/tags */
    val labels: List<String> = emptyList(),

    /** Comments (full text) */
    val comments: List<ConfluenceComment> = emptyList(),

    /** Attachments metadata */
    val attachments: List<ConfluenceAttachment> = emptyList(),

    /** When page was created in Confluence */
    val createdAt: Instant,

    /** When page was last updated in Confluence */
    val confluenceUpdatedAt: Instant,

    // === STATE MANAGEMENT ===

    /** Indexing state: NEW, INDEXING, INDEXED, FAILED */
    @Indexed
    val state: String = "NEW",

    /** When document was created/updated in our DB */
    @Indexed
    val updatedAt: Instant = Instant.now(),

    /** When page was last indexed to RAG */
    val lastIndexedAt: Instant? = null,

    /** Is archived (deleted from Confluence or manually archived) */
    val archived: Boolean = false,

    // === INDEXING STATS (updated by ContinuousIndexer) ===

    /** Number of RAG chunks created */
    val totalRagChunks: Int = 0,

    /** Number of comments indexed */
    val commentChunkCount: Int = 0,

    /** Number of attachments indexed */
    val attachmentCount: Int = 0,
)

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
