package com.jervis.repository.mongo

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.VectorStoreIndexDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * Repository for tracking what is indexed in vector store (Qdrant).
 * Enables branch-aware queries, reindexing only changed parts, and cleanup of stale data.
 */
@Repository
interface VectorStoreIndexMongoRepository : CoroutineCrudRepository<VectorStoreIndexDocument, ObjectId> {
    /**
     * Find all indexed documents for a specific project and branch.
     * Used during branch switching to check what's already indexed.
     */
    fun findByProjectIdAndBranchAndIsActive(
        projectId: ObjectId,
        branch: String,
        isActive: Boolean,
    ): Flow<VectorStoreIndexDocument>

    /**
     * Find indexed document by file path in a specific branch.
     * Used to detect if file needs reindexing after changes.
     */
    suspend fun findByProjectIdAndBranchAndFilePathAndIsActive(
        projectId: ObjectId,
        branch: String,
        filePath: String,
        isActive: Boolean,
    ): VectorStoreIndexDocument?

    /**
     * Find all indexed documents for a specific commit.
     * Used to reindex or cleanup when commit changes.
     */
    fun findByProjectIdAndCommitHashAndIsActive(
        projectId: ObjectId,
        commitHash: String,
        isActive: Boolean,
    ): Flow<VectorStoreIndexDocument>

    /**
     * Find indexed document by source type and source ID.
     * Generic lookup for any source type (GIT_HISTORY, CODE_CHANGE, EMAIL, etc.)
     */
    suspend fun findBySourceTypeAndSourceIdAndProjectIdAndIsActive(
        sourceType: RagSourceType,
        sourceId: String,
        projectId: ObjectId,
        isActive: Boolean,
    ): VectorStoreIndexDocument?

    /**
     * Find all indexed documents by source type for a project.
     * Used for bulk operations or statistics.
     */
    fun findByProjectIdAndSourceTypeAndIsActive(
        projectId: ObjectId,
        sourceType: RagSourceType,
        isActive: Boolean,
    ): Flow<VectorStoreIndexDocument>

    /**
     * Find all inactive records older than specified time.
     * Used for cleanup of soft-deleted records.
     */
    fun findByIsActiveAndLastUpdatedAtBefore(
        isActive: Boolean,
        before: java.time.Instant,
    ): Flow<VectorStoreIndexDocument>

    /**
     * Count indexed documents for a specific project and branch.
     * Used for progress tracking and statistics.
     */
    suspend fun countByProjectIdAndBranchAndIsActive(
        projectId: ObjectId,
        branch: String,
        isActive: Boolean,
    ): Long

    /**
     * Find by vector store ID (Qdrant UUID).
     * Used for reverse lookup from Qdrant to MongoDB.
     */
    suspend fun findByVectorStoreId(vectorStoreId: String): VectorStoreIndexDocument?
}
