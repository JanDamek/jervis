package com.jervis.entity

import com.jervis.domain.rag.RagSourceType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * MongoDB document tracking what is indexed in vector store (Qdrant).
 * Enables:
 * - Branch-aware queries (only show data for current branch)
 * - Reindexing only changed parts (delete old, index new)
 * - Cleanup of stale data
 * - Audit trail of what was indexed when
 *
 * RAG Strategy for mono-repos:
 * - Mono-repo commits: clientId + monoRepoId (projectId = null)
 * - Standalone project commits: clientId + projectId (monoRepoId = null)
 * This enables cross-project code discovery within mono-repos.
 */
@Document(collection = "vector_store_index")
@CompoundIndexes(
    CompoundIndex(
        name = "project_branch_source_idx",
        def = "{'projectId': 1, 'branch': 1, 'sourceType': 1, 'isActive': 1}",
    ),
    CompoundIndex(
        name = "client_monorepo_branch_source_idx",
        def = "{'clientId': 1, 'monoRepoId': 1, 'branch': 1, 'sourceType': 1, 'isActive': 1}",
    ),
    CompoundIndex(
        name = "source_idx",
        def = "{'sourceType': 1, 'sourceId': 1, 'projectId': 1}",
    ),
    CompoundIndex(
        name = "file_idx",
        def = "{'projectId': 1, 'branch': 1, 'filePath': 1, 'isActive': 1}",
    ),
    CompoundIndex(
        name = "commit_idx",
        def = "{'projectId': 1, 'commitHash': 1, 'isActive': 1}",
    ),
    CompoundIndex(
        name = "monorepo_commit_idx",
        def = "{'clientId': 1, 'monoRepoId': 1, 'commitHash': 1, 'isActive': 1}",
    ),
)
data class VectorStoreIndexDocument(
    @Id
    val id: ObjectId = ObjectId(),
    // Context - either project-specific OR client mono-repo
    val clientId: ObjectId,
    val projectId: ObjectId? = null, // null for mono-repo commits
    val monoRepoId: String? = null, // null for standalone project commits
    val branch: String, // CRITICAL - enables branch switching
    // Source tracking
    val sourceType: RagSourceType, // GIT_HISTORY, CODE_CHANGE, EMAIL, etc.
    val sourceId: String, // e.g. commit hash, file path, email message ID
    // Vector store reference
    val vectorStoreId: String, // ID in Qdrant (UUID)
    val vectorStoreName: String, // Collection name in Qdrant
    // Content hash (for change detection)
    val contentHash: String, // SHA-256 hash of content
    // Metadata for reindexing
    val filePath: String? = null, // For CODE_CHANGE
    val symbolName: String? = null, // For METHOD changes
    val commitHash: String? = null, // For GIT_HISTORY/CODE_CHANGE
    // Timestamps
    val indexedAt: Instant = Instant.now(),
    val lastUpdatedAt: Instant = Instant.now(),
    // Flags
    val isActive: Boolean = true, // false when deleted from Qdrant
)
