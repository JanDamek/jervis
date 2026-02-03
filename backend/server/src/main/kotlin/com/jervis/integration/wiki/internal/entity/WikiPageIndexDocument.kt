package com.jervis.integration.wiki.internal.entity

import com.jervis.domain.PollingStatusEnum
import com.jervis.types.ClientId
import com.jervis.types.ConnectionId
import com.jervis.types.ProjectId
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Wiki page index - tracking which pages have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * STATES:
 * - NEW: Minimal page metadata for change detection, ready for indexing
 * - INDEXED: Minimal tracking record (data in RAG/Graph via sourceUrn)
 * - FAILED: Same as NEW but with error message for retry
 *
 * FLOW:
 * 1. CentralPoller fetches minimal data → saves as NEW (pageId, versionNumber, title, updatedAt)
 * 2. WikiContinuousIndexer:
 *    - Fetches FULL page details from Wiki API
 *    - Creates PendingTask with complete content
 *    - Converts to INDEXED (minimal)
 * 3. KoogQualifierAgent stores to RAG/Graph with sourceUrn
 * 4. Future lookups use sourceUrn to find original in Wiki
 *
 * MONGODB STORAGE (minimal for all states):
 * - NEW: pageId, versionNumber, title, updatedAt (enough to fetch full details)
 * - INDEXED: pageId, versionNumber, updatedAt (for deduplication)
 * - FAILED: same as NEW + indexingError
 *
 * BREAKING CHANGE - MIGRATION REQUIRED:
 * Sealed class structure requires _class discriminator field in MongoDB.
 * Old documents without _class will FAIL deserialization (fail-fast design).
 *
 * MIGRATION: Drop a collection before starting the server:
 *   db.wiki_pages.drop()
 *
 * All pages will be re-indexed from Wiki in the next polling cycle.
 */
@Document(collection = "wiki_pages")
@CompoundIndexes(
    CompoundIndex(
        name = "connection_page_version_idx",
        def = "{'connectionDocumentId': 1, 'pageId': 1, 'versionNumber': 1}",
        unique = true,
    ),
)
data class WikiPageIndexDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val connectionDocumentId: ConnectionId,
    val pageId: String,
    val versionNumber: Int?,
    val wikiUpdatedAt: Instant,
    val clientId: ClientId,
    val projectId: ProjectId? = null,
    val title: String,
    val indexingError: String?,
    val status: PollingStatusEnum,
)
