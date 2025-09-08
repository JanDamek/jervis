package com.jervis.repository.mongo

import com.jervis.entity.mongo.IndexingStatus
import com.jervis.entity.mongo.RagIndexingStatusDocument
import kotlinx.coroutines.flow.Flow
import org.bson.types.ObjectId
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

/**
 * MongoDB repository for RAG indexing status documents.
 * Provides methods for tracking and querying indexed files by commit, project, and status.
 */
@Repository
interface RagIndexingStatusMongoRepository : CoroutineCrudRepository<RagIndexingStatusDocument, ObjectId> {
    /**
     * Finds all indexed files for a specific project and commit hash.
     * Used for version-specific RAG searches.
     */
    fun findByProjectIdAndGitCommitHash(
        projectId: ObjectId,
        gitCommitHash: String,
    ): Flow<RagIndexingStatusDocument>

    /**
     * Finds all indexed files for a project with specific status.
     */
    fun findByProjectIdAndStatus(
        projectId: ObjectId,
        status: IndexingStatus,
    ): Flow<RagIndexingStatusDocument>

    /**
     * Finds all files with specific status across all projects.
     * Useful for maintenance and monitoring.
     */
    fun findByStatus(status: IndexingStatus): Flow<RagIndexingStatusDocument>

    /**
     * Finds indexing status for a specific file in a project at a commit.
     */
    suspend fun findByProjectIdAndGitCommitHashAndFilePath(
        projectId: ObjectId,
        gitCommitHash: String,
        filePath: String,
    ): RagIndexingStatusDocument?

    /**
     * Finds all commits that have been indexed for a project.
     */
    fun findDistinctGitCommitHashByProjectId(projectId: ObjectId): Flow<String>

    /**
     * Finds all indexed files for a project (across all commits).
     */
    fun findByProjectId(projectId: ObjectId): Flow<RagIndexingStatusDocument>

    /**
     * Counts successfully indexed files for a project and commit.
     */
    suspend fun countByProjectIdAndGitCommitHashAndStatus(
        projectId: ObjectId,
        gitCommitHash: String,
        status: IndexingStatus,
    ): Long

    /**
     * Finds files that failed indexing for troubleshooting.
     */
    fun findByProjectIdAndStatusAndErrorMessageIsNotNull(
        projectId: ObjectId,
        status: IndexingStatus,
    ): Flow<RagIndexingStatusDocument>

    /**
     * Deletes indexing status records for a specific project and commit.
     * Used when cleaning up old versions.
     */
    suspend fun deleteByProjectIdAndGitCommitHash(
        projectId: ObjectId,
        gitCommitHash: String,
    ): Long
}
