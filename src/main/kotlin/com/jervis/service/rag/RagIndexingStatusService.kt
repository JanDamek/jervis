package com.jervis.service.rag

import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.IndexedContentInfo
import com.jervis.entity.mongo.IndexingStatus
import com.jervis.entity.mongo.RagIndexingStatusDocument
import com.jervis.repository.mongo.RagIndexingStatusMongoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant

/**
 * Service for tracking and managing RAG indexing status.
 * Provides the foundation for maintaining RAG in good condition by tracking
 * every indexed file, what content was extracted, and version information.
 */
@Service
class RagIndexingStatusService(
    private val ragIndexingStatusRepository: RagIndexingStatusMongoRepository,
) {
    private val logger = LoggerFactory.getLogger(RagIndexingStatusService::class.java)

    /**
     * Records the start of file indexing process.
     */
    suspend fun startIndexing(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
        ragSourceType: RagSourceType,
        fileContent: ByteArray,
        language: String? = null,
        module: String? = null,
    ): RagIndexingStatusDocument {
        val fileHash = calculateFileHash(fileContent)
        val fileSize = fileContent.size.toLong()

        // Check if already exists and update or create new
        val existing =
            ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
                projectId,
                gitCommitHash,
                filePath,
            )

        val document =
            if (existing != null) {
                existing.copy(
                    status = IndexingStatus.INDEXING,
                    fileSize = fileSize,
                    fileHash = fileHash,
                    language = language,
                    module = module,
                    attemptCount = existing.attemptCount + 1,
                    lastUpdatedAt = Instant.now(),
                    indexingStartedAt = Instant.now(),
                    errorMessage = null,
                )
            } else {
                RagIndexingStatusDocument(
                    projectId = projectId,
                    filePath = filePath,
                    gitCommitHash = gitCommitHash,
                    status = IndexingStatus.INDEXING,
                    ragSourceType = ragSourceType,
                    fileSize = fileSize,
                    fileHash = fileHash,
                    language = language,
                    module = module,
                    indexingStartedAt = Instant.now(),
                )
            }

        return ragIndexingStatusRepository.save(document).also {
            logger.info("Started indexing file: $filePath for commit: $gitCommitHash")
        }
    }

    /**
     * Records successful completion of file indexing with extracted content information.
     */
    suspend fun completeIndexing(
        statusId: ObjectId,
        indexedContent: List<IndexedContentInfo>,
    ): RagIndexingStatusDocument {
        val existing =
            ragIndexingStatusRepository.findById(statusId)
                ?: throw IllegalArgumentException("Indexing status not found: $statusId")

        val updated =
            existing.copy(
                status = IndexingStatus.INDEXED,
                indexedContent = indexedContent,
                lastUpdatedAt = Instant.now(),
                indexingCompletedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated).also {
            logger.info("Completed indexing file: ${existing.filePath} with ${indexedContent.size} content pieces")
        }
    }

    /**
     * Records indexing failure with error information.
     */
    suspend fun failIndexing(
        statusId: ObjectId,
        errorMessage: String,
    ): RagIndexingStatusDocument {
        val existing =
            ragIndexingStatusRepository.findById(statusId)
                ?: throw IllegalArgumentException("Indexing status not found: $statusId")

        val updated =
            existing.copy(
                status = IndexingStatus.FAILED,
                errorMessage = errorMessage,
                lastUpdatedAt = Instant.now(),
                indexingCompletedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated).also {
            logger.warn("Failed indexing file: ${existing.filePath}, error: $errorMessage")
        }
    }

    /**
     * Adds indexed content information to existing status record.
     * Used when multiple content pieces are extracted from a single file.
     */
    suspend fun addIndexedContent(
        statusId: ObjectId,
        contentType: RagDocumentType,
        vectorStoreId: String?,
        content: String,
        description: String,
    ): RagIndexingStatusDocument {
        val existing =
            ragIndexingStatusRepository.findById(statusId)
                ?: throw IllegalArgumentException("Indexing status not found: $statusId")

        val contentInfo =
            IndexedContentInfo(
                contentType = contentType,
                vectorStoreId = vectorStoreId,
                contentHash = calculateContentHash(content),
                contentLength = content.length,
                description = description,
            )

        val updated =
            existing.copy(
                indexedContent = existing.indexedContent + contentInfo,
                lastUpdatedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated)
    }

    /**
     * Gets all indexed files for a specific project and commit.
     * Used for version-specific RAG searches.
     */
    fun getIndexedFilesForCommit(
        projectId: ObjectId,
        gitCommitHash: String,
    ): Flow<RagIndexingStatusDocument> = ragIndexingStatusRepository.findByProjectIdAndGitCommitHash(projectId, gitCommitHash)

    /**
     * Gets indexing status for a specific file.
     */
    suspend fun getFileIndexingStatus(
        projectId: ObjectId,
        gitCommitHash: String,
        filePath: String,
    ): RagIndexingStatusDocument? =
        ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
            projectId,
            gitCommitHash,
            filePath,
        )

    /**
     * Gets all commits that have been indexed for a project.
     */
    suspend fun getIndexedCommits(projectId: ObjectId): List<String> =
        ragIndexingStatusRepository.findDistinctGitCommitHashByProjectId(projectId).toList()

    /**
     * Gets indexing statistics for a project and commit.
     */
    suspend fun getIndexingStatistics(
        projectId: ObjectId,
        gitCommitHash: String,
    ): IndexingStatistics {
        val indexed =
            ragIndexingStatusRepository.countByProjectIdAndGitCommitHashAndStatus(
                projectId,
                gitCommitHash,
                IndexingStatus.INDEXED,
            )
        val indexing =
            ragIndexingStatusRepository.countByProjectIdAndGitCommitHashAndStatus(
                projectId,
                gitCommitHash,
                IndexingStatus.INDEXING,
            )
        val failed =
            ragIndexingStatusRepository.countByProjectIdAndGitCommitHashAndStatus(
                projectId,
                gitCommitHash,
                IndexingStatus.FAILED,
            )

        return IndexingStatistics(
            totalFiles = indexed + indexing + failed,
            indexedFiles = indexed,
            indexingFiles = indexing,
            failedFiles = failed,
        )
    }

    /**
     * Gets files that failed indexing for troubleshooting.
     */
    fun getFailedIndexings(projectId: ObjectId): Flow<RagIndexingStatusDocument> =
        ragIndexingStatusRepository.findByProjectIdAndStatusAndErrorMessageIsNotNull(
            projectId,
            IndexingStatus.FAILED,
        )

    /**
     * Marks a file as removed when it no longer exists in the project.
     */
    suspend fun markFileAsRemoved(
        projectId: ObjectId,
        gitCommitHash: String,
        filePath: String,
    ): RagIndexingStatusDocument? {
        val existing =
            ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
                projectId,
                gitCommitHash,
                filePath,
            ) ?: return null

        val updated =
            existing.copy(
                status = IndexingStatus.REMOVED,
                lastUpdatedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated).also {
            logger.info("Marked file as removed: $filePath for commit: $gitCommitHash")
        }
    }

    /**
     * Cleans up indexing status records for old commits.
     */
    suspend fun cleanupOldCommits(
        projectId: ObjectId,
        commitsToKeep: List<String>,
    ): Long {
        val allCommits = getIndexedCommits(projectId)
        val commitsToDelete = allCommits - commitsToKeep.toSet()

        var deletedCount = 0L
        for (commit in commitsToDelete) {
            deletedCount += ragIndexingStatusRepository.deleteByProjectIdAndGitCommitHash(projectId, commit)
        }

        logger.info("Cleaned up $deletedCount indexing status records for ${commitsToDelete.size} old commits")
        return deletedCount
    }

    private fun calculateFileHash(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks if a file should be indexed by comparing with existing status and file hash.
     * Returns true if the file should be indexed (new, changed, or failed previously).
     * Returns false if the file is already successfully indexed with the same content.
     */
    suspend fun shouldIndexFile(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
        fileContent: ByteArray,
    ): Boolean {
        val existingStatus = getFileIndexingStatus(projectId, gitCommitHash, filePath)

        return when {
            // No previous indexing record - should index
            existingStatus == null -> {
                logger.debug("File not previously indexed: $filePath")
                true
            }

            // Previously failed indexing - should retry
            existingStatus.status == IndexingStatus.FAILED -> {
                logger.debug("Previous indexing failed, retrying: $filePath")
                true
            }

            // Currently being indexed - skip to avoid conflicts
            existingStatus.status == IndexingStatus.INDEXING -> {
                logger.debug("File currently being indexed, skipping: $filePath")
                false
            }

            // File was removed - should re-index if it exists again
            existingStatus.status == IndexingStatus.REMOVED -> {
                logger.debug("File was previously removed, re-indexing: $filePath")
                true
            }

            // Successfully indexed - check if content changed
            existingStatus.status == IndexingStatus.INDEXED -> {
                val newFileHash = calculateFileHash(fileContent)
                val contentChanged = existingStatus.fileHash != newFileHash

                if (contentChanged) {
                    logger.debug(
                        "File content changed, re-indexing: $filePath (old hash: ${existingStatus.fileHash}, new hash: $newFileHash)",
                    )
                    true
                } else {
                    logger.debug("File already indexed with same content, skipping: $filePath")
                    false
                }
            }

            // Unknown status - should index to be safe
            else -> {
                logger.warn("Unknown indexing status for file: $filePath, status: ${existingStatus.status}")
                true
            }
        }
    }

    private fun calculateContentHash(content: String): String = calculateFileHash(content.toByteArray())
}

/**
 * Statistics about indexing progress for a project and commit.
 */
data class IndexingStatistics(
    val totalFiles: Long,
    val indexedFiles: Long,
    val indexingFiles: Long,
    val failedFiles: Long,
) {
    val completionPercentage: Double
        get() = if (totalFiles > 0) (indexedFiles.toDouble() / totalFiles * 100) else 0.0

    val isComplete: Boolean
        get() = indexingFiles == 0L && totalFiles > 0
}
