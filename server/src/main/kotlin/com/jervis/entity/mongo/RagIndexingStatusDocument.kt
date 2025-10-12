package com.jervis.entity.mongo

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

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
    @Indexed
    val filePath: String,
    @Indexed
    val gitCommitHash: String,
    @Indexed
    val status: IndexingStatus,
    val indexedContent: List<IndexedContentInfo> = emptyList(),
    val fileSize: Long,
    val fileHash: String,
    val language: String? = null,
    val module: String? = null,
    val errorMessage: String? = null,
    val attemptCount: Int = 1,
    val firstIndexedAt: Instant = Instant.now(),
    val lastUpdatedAt: Instant = Instant.now(),
    val indexingStartedAt: Instant? = null,
    val indexingCompletedAt: Instant? = null,
) {
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
     * Information about an individual indexed content piece
     */
    data class IndexedContentInfo(
        val vectorStoreId: String?,
        val contentHash: String,
        val contentLength: Int,
        val description: String,
    )
}
