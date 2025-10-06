package com.jervis.repository.mongo

import com.jervis.entity.mongo.RagIndexingStatusDocument
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
     * Finds indexing status for a specific file in a project at a commit.
     */
    suspend fun findByProjectIdAndGitCommitHashAndFilePath(
        projectId: ObjectId,
        gitCommitHash: String,
        filePath: String,
    ): RagIndexingStatusDocument?
}
