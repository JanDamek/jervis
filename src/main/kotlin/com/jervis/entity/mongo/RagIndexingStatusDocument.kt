package com.jervis.entity.mongo

import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Status of indexed content in RAG system
 */
enum class IndexingStatus {
    /** Successfully indexed */
    INDEXED,

    /** Indexing in progress */
    INDEXING,

    /** Indexing failed */
    FAILED,

    /** File was removed/deleted */
    REMOVED,
}

/**
 * Information about individual indexed content piece
 */
data class IndexedContentInfo(
    val contentType: RagDocumentType,
    val vectorStoreId: String?,
    val contentHash: String,
    val contentLength: Int,
    val description: String,
)

/**
 * MongoDB document for tracking RAG indexing status of files.
 * This provides the foundation for maintaining RAG in good condition by tracking
 * every indexed file, what content was extracted, and version information.
 */
@Document(collection = "rag_indexing_status")
@CompoundIndexes(
    CompoundIndex(
        name = "project_commit_path",
        def = "{'projectId': 1, 'gitCommitHash': 1, 'filePath': 1}",
        unique = true,
    ),
    CompoundIndex(name = "project_status", def = "{'projectId': 1, 'status': 1}"),
    CompoundIndex(name = "commit_status", def = "{'gitCommitHash': 1, 'status': 1}"),
)
data class RagIndexingStatusDocument(
    @Id
    val id: ObjectId = ObjectId.get(),
    @Indexed
    val projectId: ObjectId,
    /** Path to the indexed file relative to project root */
    @Indexed
    val filePath: String,
    /** Git commit hash when this file was indexed */
    @Indexed
    val gitCommitHash: String,
    /** Current indexing status */
    @Indexed
    val status: IndexingStatus,
    /** Source type of the RAG content */
    val ragSourceType: RagSourceType,
    /** List of all content pieces extracted from this file */
    val indexedContent: List<IndexedContentInfo> = emptyList(),
    /** File size in bytes when indexed */
    val fileSize: Long,
    /** File hash for content change detection */
    val fileHash: String,
    /** Programming language if detected */
    val language: String? = null,
    /** Module/package name if applicable */
    val module: String? = null,
    /** Error message if indexing failed */
    val errorMessage: String? = null,
    /** Number of indexing attempts */
    val attemptCount: Int = 1,
    /** When this file was first indexed */
    val firstIndexedAt: Instant = Instant.now(),
    /** When this status was last updated */
    val lastUpdatedAt: Instant = Instant.now(),
    /** When indexing started (for tracking duration) */
    val indexingStartedAt: Instant? = null,
    /** When indexing completed */
    val indexingCompletedAt: Instant? = null,
)
