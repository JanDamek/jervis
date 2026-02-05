package com.jervis.integration.bugtracker.internal.entity

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.domain.PollingStatusEnum
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * BugTracker issue index - tracking which issues have been processed.
 *
 * STATE MACHINE: NEW -> INDEXED (or FAILED)
 *
 * STATES:
 * - NEW: Minimal issue metadata for change detection, ready for indexing
 * - INDEXED: Minimal tracking record (data in RAG/Graph via sourceUrn)
 * - FAILED: Same as NEW but with error message for retry
 *
 * FLOW:
 * 1. CentralPoller fetches minimal data → saves as NEW (issueKey, summary, updated, status)
 * 2. BugTrackerContinuousIndexer:
 *    - Fetches FULL issue details from BugTracker API
 *    - Creates PendingTask with complete content
 *    - Converts to INDEXED (minimal)
 * 3. KoogQualifierAgent stores to RAG/Graph with sourceUrn
 * 4. Future lookups use sourceUrn to find original in BugTracker
 *
 * MONGODB STORAGE (minimal for all states):
 * - NEW: issueKey, summary, updated, status (enough to fetch full details)
 * - INDEXED: issueKey, latestChangelogId, updated (for deduplication)
 * - FAILED: same as NEW + indexingError
 *
 * BREAKING CHANGE - MIGRATION REQUIRED:
 * Sealed class structure requires _class discriminator field in MongoDB.
 * Old documents without _class will FAIL deserialization (fail-fast design).
 *
 * MIGRATION: Drop a collection before starting the server:
 *   db.bugtracker_issues.drop()
 *
 * All issues will be re-indexed from BugTracker in the next polling cycle.
 */
@Document(collection = "bugtracker_issues")
@CompoundIndexes(
    CompoundIndex(name = "connection_state_idx", def = "{'connectionDocumentId': 1, 'state': 1}"),
    CompoundIndex(
        name = "connection_issue_changelog_idx",
        def = "{'connectionId': 1, 'issueKey': 1, 'latestChangelogId': 1}",
        unique = true,
    ),
    CompoundIndex(name = "client_state_idx", def = "{'clientId': 1, 'state': 1}"),
)
data class BugTrackerIssueIndexDocument(
    @Id
    val id: ObjectId = ObjectId(),
    val connectionId: ConnectionId,
    val issueKey: String,
    val latestChangelogId: String,
    val bugtrackerUpdatedAt: Instant,
    val clientId: ClientId,
    val projectId: ProjectId?,
    val summary: String? = null,
    val indexingError: String? = null,
    val status: PollingStatusEnum,
)
