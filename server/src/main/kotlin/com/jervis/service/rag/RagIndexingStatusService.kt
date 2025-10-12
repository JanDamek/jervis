package com.jervis.service.rag

import com.jervis.entity.mongo.RagIndexingStatusDocument
import com.jervis.repository.mongo.RagIndexingStatusMongoRepository
import kotlinx.coroutines.async
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
    private val vectorStorage: com.jervis.repository.vector.VectorStorageRepository,
) {
    private val logger = LoggerFactory.getLogger(RagIndexingStatusService::class.java)

    /**
     * Records the start of a file indexing process.
     */
    suspend fun startIndexing(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
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
            existing?.copy(
                status = RagIndexingStatusDocument.IndexingStatus.INDEXING,
                fileSize = fileSize,
                fileHash = fileHash,
                language = language,
                module = module,
                attemptCount = existing.attemptCount + 1,
                lastUpdatedAt = Instant.now(),
                indexingStartedAt = Instant.now(),
                errorMessage = null,
            ) ?: RagIndexingStatusDocument(
                projectId = projectId,
                filePath = filePath,
                gitCommitHash = gitCommitHash,
                status = RagIndexingStatusDocument.IndexingStatus.INDEXING,
                fileSize = fileSize,
                fileHash = fileHash,
                language = language,
                module = module,
                indexingStartedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(document).also {
            logger.info("Started indexing file: $filePath for commit: $gitCommitHash")
        }
    }

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
            existingStatus.status == RagIndexingStatusDocument.IndexingStatus.FAILED -> {
                logger.debug("Previous indexing failed, retrying: $filePath")
                true
            }

            // Currently being indexed - skip to avoid conflicts
            existingStatus.status == RagIndexingStatusDocument.IndexingStatus.INDEXING -> {
                logger.debug("File currently being indexed, skipping: $filePath")
                false
            }

            // File was removed - should re-index if it exists again
            existingStatus.status == RagIndexingStatusDocument.IndexingStatus.REMOVED -> {
                logger.debug("File was previously removed, re-indexing: $filePath")
                true
            }

            // Successfully indexed - check if content changed
            existingStatus.status == RagIndexingStatusDocument.IndexingStatus.INDEXED -> {
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

    /**
     * Marks file indexing as completed and stores vector IDs.
     */
    suspend fun completeIndexing(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
        vectorStoreIds: List<RagIndexingStatusDocument.IndexedContentInfo>,
    ): RagIndexingStatusDocument {
        val existing =
            ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
                projectId,
                gitCommitHash,
                filePath,
            ) ?: throw IllegalStateException("No indexing record found for $filePath")

        val updated =
            existing.copy(
                status = RagIndexingStatusDocument.IndexingStatus.INDEXED,
                indexedContent = vectorStoreIds,
                indexingCompletedAt = Instant.now(),
                lastUpdatedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated).also {
            logger.info("Completed indexing: $filePath with ${vectorStoreIds.size} vectors")
        }
    }

    /**
     * Marks file indexing as failed.
     */
    suspend fun failIndexing(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
        errorMessage: String,
    ): RagIndexingStatusDocument {
        val existing =
            ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
                projectId,
                gitCommitHash,
                filePath,
            ) ?: throw IllegalStateException("No indexing record found for $filePath")

        val updated =
            existing.copy(
                status = RagIndexingStatusDocument.IndexingStatus.FAILED,
                errorMessage = errorMessage,
                lastUpdatedAt = Instant.now(),
            )

        return ragIndexingStatusRepository.save(updated)
    }

    /**
     * Marks file as removed.
     */
    suspend fun markAsRemoved(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
    ): RagIndexingStatusDocument? {
        val existing =
            ragIndexingStatusRepository.findByProjectIdAndGitCommitHashAndFilePath(
                projectId,
                gitCommitHash,
                filePath,
            ) ?: return null

        val updated =
            existing.copy(
                status = RagIndexingStatusDocument.IndexingStatus.REMOVED,
                lastUpdatedAt = Instant.now(),
            )
        return ragIndexingStatusRepository.save(updated)
    }

    /**
     * Gets all indexed files for a project at a specific commit.
     */
    suspend fun getAllIndexedFilesForCommit(
        projectId: ObjectId,
        gitCommitHash: String,
    ): List<RagIndexingStatusDocument> = ragIndexingStatusRepository.findAllByProjectIdAndGitCommitHash(projectId, gitCommitHash).toList()

    /**
     * Deletes old embeddings for a file before re-indexing. Can run in parallel with new indexing.
     */
    suspend fun deleteOldEmbeddings(
        projectId: ObjectId,
        filePath: String,
        gitCommitHash: String,
    ): Int =
        kotlinx.coroutines.coroutineScope {
            val existingStatus = getFileIndexingStatus(projectId, gitCommitHash, filePath) ?: return@coroutineScope 0

            val ids = existingStatus.indexedContent.mapNotNull { it.vectorStoreId }.distinct()
            if (ids.isEmpty()) {
                logger.debug("No embeddings to delete for $filePath")
                return@coroutineScope 0
            }

            logger.info("Deleting ${ids.size} old embeddings by ID for $filePath")

            val textDeletions = async {
                vectorStorage.deleteByIds(
                    com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                    ids,
                )
            }
            val codeDeletions = async {
                vectorStorage.deleteByIds(
                    com.jervis.domain.model.ModelType.EMBEDDING_CODE,
                    ids,
                )
            }

            val totalDeleted = textDeletions.await() + codeDeletions.await()
            logger.info("Deleted $totalDeleted embeddings for $filePath using ID-based deletion")
            totalDeleted
        }
}
