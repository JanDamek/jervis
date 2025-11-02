package com.jervis.service.rag

import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.VectorStoreIndexDocument
import com.jervis.repository.mongo.VectorStoreIndexMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

/**
 * Service for tracking what is indexed in vector store (Qdrant).
 * Provides operations for creating, updating, and querying vector store index records.
 */
@Service
class VectorStoreIndexService(
    private val repository: VectorStoreIndexMongoRepository,
) {
    private val logger = KotlinLogging.logger {}

    // ========== Standalone Project Methods ==========

    /**
     * Track a new document in vector store for standalone project.
     * Creates MongoDB record linking source to Qdrant vector.
     */
    suspend fun trackIndexed(
        projectId: ObjectId,
        clientId: ObjectId,
        branch: String,
        sourceType: RagSourceType,
        sourceId: String,
        vectorStoreId: String,
        vectorStoreName: String,
        content: String,
        filePath: String? = null,
        symbolName: String? = null,
        commitHash: String? = null,
    ): VectorStoreIndexDocument {
        val contentHash = calculateContentHash(content)

        val document =
            VectorStoreIndexDocument(
                clientId = clientId,
                projectId = projectId,
                monoRepoId = null,
                branch = branch,
                sourceType = sourceType,
                sourceId = sourceId,
                vectorStoreId = vectorStoreId,
                vectorStoreName = vectorStoreName,
                contentHash = contentHash,
                filePath = filePath,
                symbolName = symbolName,
                commitHash = commitHash,
            )

        val saved = repository.save(document)
        logger.debug {
            "Tracked indexed document: project=$projectId, branch=$branch, " +
                "sourceType=$sourceType, sourceId=$sourceId, vectorStoreId=$vectorStoreId"
        }
        return saved
    }

    /**
     * Check if content has changed since last indexing for standalone project.
     * Returns true if content hash differs or document doesn't exist.
     */
    suspend fun hasContentChanged(
        sourceType: RagSourceType,
        sourceId: String,
        projectId: ObjectId,
        content: String,
    ): Boolean {
        val existing =
            repository.findBySourceTypeAndSourceIdAndProjectIdAndIsActive(
                sourceType,
                sourceId,
                projectId,
                true,
            )

        if (existing == null) {
            return true // Not indexed yet
        }

        val newHash = calculateContentHash(content)
        return existing.contentHash != newHash
    }

    // ========== Mono-Repo Methods ==========

    /**
     * Track a new document in vector store for mono-repo.
     * Creates MongoDB record with monoRepoId (projectId = null).
     */
    suspend fun trackIndexedForMonoRepo(
        clientId: ObjectId,
        monoRepoId: String,
        branch: String,
        sourceType: RagSourceType,
        sourceId: String,
        vectorStoreId: String,
        vectorStoreName: String,
        content: String,
        filePath: String? = null,
        symbolName: String? = null,
        commitHash: String? = null,
    ): VectorStoreIndexDocument {
        val contentHash = calculateContentHash(content)

        val document =
            VectorStoreIndexDocument(
                clientId = clientId,
                projectId = null,
                monoRepoId = monoRepoId,
                branch = branch,
                sourceType = sourceType,
                sourceId = sourceId,
                vectorStoreId = vectorStoreId,
                vectorStoreName = vectorStoreName,
                contentHash = contentHash,
                filePath = filePath,
                symbolName = symbolName,
                commitHash = commitHash,
            )

        val saved = repository.save(document)
        logger.debug {
            "Tracked indexed mono-repo document: monoRepo=$monoRepoId, branch=$branch, " +
                "sourceType=$sourceType, sourceId=$sourceId, vectorStoreId=$vectorStoreId"
        }
        return saved
    }

    /**
     * Check if content has changed since last indexing for mono-repo.
     * Returns true if content hash differs or document doesn't exist.
     */
    suspend fun hasContentChangedForMonoRepo(
        sourceType: RagSourceType,
        sourceId: String,
        clientId: ObjectId,
        monoRepoId: String,
        content: String,
    ): Boolean {
        val existing =
            repository.findBySourceTypeAndSourceIdAndClientIdAndMonoRepoIdAndIsActive(
                sourceType,
                sourceId,
                clientId,
                monoRepoId,
                true,
            )

        if (existing == null) {
            return true // Not indexed yet
        }

        val newHash = calculateContentHash(content)
        return existing.contentHash != newHash
    }

    /**
     * Mark document as inactive (soft delete).
     * Used when source is deleted or moved to different branch.
     */
    suspend fun markInactive(
        sourceType: RagSourceType,
        sourceId: String,
        projectId: ObjectId,
    ) {
        val existing =
            repository.findBySourceTypeAndSourceIdAndProjectIdAndIsActive(
                sourceType,
                sourceId,
                projectId,
                true,
            ) ?: return

        val updated =
            existing.copy(
                isActive = false,
                lastUpdatedAt = Instant.now(),
            )
        repository.save(updated)

        logger.debug {
            "Marked inactive: sourceType=$sourceType, sourceId=$sourceId, " +
                "vectorStoreId=${existing.vectorStoreId}"
        }
    }

    /**
     * Check if branch is fully indexed.
     * Returns true if there are any active indexed documents for this branch.
     */
    suspend fun isBranchIndexed(
        projectId: ObjectId,
        branch: String,
    ): Boolean {
        val count =
            repository.countByProjectIdAndBranchAndIsActive(
                projectId,
                branch,
                true,
            )
        return count > 0
    }

    /**
     * Get all indexed documents for a specific branch.
     * Used during branch switching to query only relevant data.
     */
    fun getIndexedForBranch(
        projectId: ObjectId,
        branch: String,
    ): Flow<VectorStoreIndexDocument> =
        repository.findByProjectIdAndBranchAndIsActive(
            projectId,
            branch,
            true,
        )

    /**
     * Get indexed document by file path in specific branch.
     * Used to check if file needs reindexing.
     */
    suspend fun getIndexedForFile(
        projectId: ObjectId,
        branch: String,
        filePath: String,
    ): VectorStoreIndexDocument? {
        val results =
            repository
                .findByProjectIdAndBranchAndFilePathAndIsActiveOrderByLastUpdatedAtDesc(
                    projectId,
                    branch,
                    filePath,
                    true,
                ).toList()

        if (results.size > 1) {
            logger.warn {
                "Found ${results.size} duplicate active index records for file $filePath " +
                    "(project=$projectId, branch=$branch). Using latest. " +
                    "Consider cleanup of duplicates."
            }
        }

        return results.firstOrNull()
    }

    /**
     * Get all indexed documents for a commit.
     * Used to reindex when commit changes.
     */
    fun getIndexedForCommit(
        projectId: ObjectId,
        commitHash: String,
    ): Flow<VectorStoreIndexDocument> =
        repository.findByProjectIdAndCommitHashAndIsActive(
            projectId,
            commitHash,
            true,
        )

    /**
     * Cleanup old inactive records.
     * Should be called periodically (e.g., daily) to remove soft-deleted records older than retention period.
     */
    suspend fun cleanupOldInactiveRecords(retentionDays: Int = 30): Int {
        val cutoffDate = Instant.now().minusSeconds((retentionDays * 24 * 60 * 60).toLong())
        val toDelete =
            repository
                .findByIsActiveAndLastUpdatedAtBefore(
                    false,
                    cutoffDate,
                ).toList()

        toDelete.forEach { doc ->
            repository.delete(doc)
            logger.debug { "Deleted old inactive record: vectorStoreId=${doc.vectorStoreId}" }
        }

        logger.info { "Cleaned up ${toDelete.size} old inactive vector store index records" }
        return toDelete.size
    }

    /**
     * Get statistics for indexed documents.
     * Returns map with counts per source type.
     */
    suspend fun getIndexingStats(
        projectId: ObjectId,
        branch: String,
    ): Map<RagSourceType, Long> {
        val stats = mutableMapOf<RagSourceType, Long>()

        RagSourceType.entries.forEach { sourceType ->
            val count =
                repository
                    .findByProjectIdAndSourceTypeAndIsActive(
                        projectId,
                        sourceType,
                        true,
                    ).toList()
                    .count { it.branch == branch }
            if (count > 0) {
                stats[sourceType] = count.toLong()
            }
        }

        return stats
    }

    /**
     * Calculate SHA-256 hash of content for change detection.
     */
    private fun calculateContentHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reindex file: mark old version inactive and return true if reindexing needed.
     */
    suspend fun prepareFileReindexing(
        projectId: ObjectId,
        branch: String,
        filePath: String,
        newContent: String,
    ): Boolean {
        val existing = getIndexedForFile(projectId, branch, filePath)

        if (existing == null) {
            return true // Not indexed yet, needs indexing
        }

        val newHash = calculateContentHash(newContent)
        if (existing.contentHash == newHash) {
            return false // Content unchanged, no reindexing needed
        }

        // Mark old version inactive
        markInactive(existing.sourceType, existing.sourceId, projectId)
        return true // Content changed, needs reindexing
    }

    /**
     * Get vector store IDs for a specific branch (for Qdrant queries).
     */
    suspend fun getVectorStoreIdsForBranch(
        projectId: ObjectId,
        branch: String,
    ): List<String> =
        getIndexedForBranch(projectId, branch)
            .toList()
            .map { it.vectorStoreId }
}
