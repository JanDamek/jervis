package com.jervis.entity

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * State tracking for Confluence pages - similar to EmailMessageDocument and GitCommitDocument.
 *
 * Change Detection Strategy:
 * - Confluence API provides 'version' number that increments on each edit
 * - Compare lastKnownVersion with API version to detect changes
 * - If version changed → mark as NEW for reindexing
 * - If version same → skip (already indexed)
 *
 * Processing States:
 * - NEW: Discovered or version changed, needs indexing
 * - INDEXED: Successfully indexed into RAG
 * - FAILED: Indexing failed (network error, parsing error, etc.)
 *
 * Indexing Scope:
 * - Only pages within configured spaceKeys
 * - Only internal links (same confluence domain)
 * - External links logged but not followed
 */
@Document(collection = "confluence_pages")
@CompoundIndexes(
    CompoundIndex(name = "account_state_idx", def = "{'accountId': 1, 'state': 1}"),
    CompoundIndex(name = "account_page_idx", def = "{'accountId': 1, 'pageId': 1}", unique = true),
    CompoundIndex(name = "client_space_idx", def = "{'clientId': 1, 'spaceKey': 1}"),
    CompoundIndex(name = "project_space_idx", def = "{'projectId': 1, 'spaceKey': 1}"),
)
data class ConfluencePageDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    val accountId: ObjectId,
    val clientId: ObjectId,
    val projectId: ObjectId? = null,
    @Indexed
    val pageId: String,
    val spaceKey: String,
    val title: String,
    val url: String,
    val lastKnownVersion: Int,
    val contentHash: String? = null,
    val state: ConfluencePageState = ConfluencePageState.NEW,
    val parentPageId: String? = null,
    val childPageIds: List<String> = emptyList(),
    val internalLinks: List<String> = emptyList(),
    val externalLinks: List<String> = emptyList(),
    val lastModifiedBy: String? = null,
    val lastModifiedAt: Instant? = null,
    val lastIndexedAt: Instant? = null,
    val errorMessage: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

enum class ConfluencePageState {
    NEW,
    INDEXED,
    FAILED,
}
