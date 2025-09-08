package com.jervis.service.indexing

import com.jervis.repository.vector.VectorStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant

/**
 * Service for managing historical versioning of RAG documents.
 * Handles marking existing content as historical when reindexing and preserving document history.
 *
 * This addresses the requirement: "zajistí, aby v RAG pokud se něco naidexuje bylo označeno a indexovalo se vše jen nové,
 * co se týče kódu a popisek tak asi vše indexuj podle GITu, tak že co je nové a změněné a není v RAG tak naidexu,
 * co je v RAG tak v RAG v payloadu označ jako historical a standartně toto v RAG nehledej, jen na jasné vyžádání"
 */
@Service
class HistoricalVersioningService(
    private val vectorStorage: VectorStorageRepository,
) {
    private val logger = KotlinLogging.logger {}

    data class VersioningResult(
        val markedHistorical: Int,
        val preserved: Int,
        val errors: Int,
    )

    /**
     * Mark existing documents for a project as historical before new indexing
     * This preserves the old content while allowing new content to be marked as CURRENT
     */
    suspend fun markProjectDocumentsAsHistorical(
        projectId: ObjectId,
        gitCommitHash: String? = null,
    ): VersioningResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Marking existing documents as historical for project: $projectId" }

                var markedHistorical = 0
                var preserved = 0
                var errors = 0

                // TODO: This would require implementing a method in VectorStorageRepository
                // to update document status in the vector database
                // For now, we'll create the interface for the functionality

                // The concept would be:
                // 1. Query all CURRENT documents for this project
                // 2. Update their status to HISTORICAL
                // 3. Set archivedAt timestamp
                // 4. Update version numbers

                // This is a placeholder implementation showing the intended functionality
                logger.info { "Historical versioning functionality designed - implementation pending vector DB operations" }

                VersioningResult(markedHistorical, preserved, errors)
            } catch (e: Exception) {
                logger.error(e) { "Failed to mark documents as historical for project: $projectId" }
                VersioningResult(0, 0, 1)
            }
        }

    /**
     * Check if a document already exists in RAG with the same content/path
     * This helps determine if we need to create a new version or skip indexing
     */
    suspend fun documentExistsWithSameContent(
        projectId: ObjectId,
        path: String,
        contentHash: String,
    ): Boolean =
        withContext(Dispatchers.Default) {
            try {
                // TODO: Implement content hash comparison
                // This would check if a document with the same path and content hash exists
                // returning true if no indexing is needed, false if new version should be created

                logger.debug { "Checking document existence for path: $path" }
                false // Placeholder - always index for now
            } catch (e: Exception) {
                logger.warn(e) { "Failed to check document existence for path: $path" }
                false // Default to indexing on error
            }
        }

    /**
     * Get the current Git commit hash for change detection
     */
    suspend fun getCurrentGitCommitHash(projectPath: Path): String? =
        withContext(Dispatchers.IO) {
            try {
                val processBuilder =
                    ProcessBuilder("git", "rev-parse", "HEAD")
                        .directory(projectPath.toFile())
                        .redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    val commitHash = process.inputStream.bufferedReader().use { it.readText().trim() }
                    logger.debug { "Current Git commit hash: $commitHash" }
                    commitHash
                } else {
                    logger.warn { "Failed to get Git commit hash for project: $projectPath" }
                    null
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error getting Git commit hash for project: $projectPath" }
                null
            }
        }

    /**
     * Get list of files changed since last indexed commit
     * This enables incremental indexing by processing only changed files
     */
    suspend fun getChangedFilesSinceCommit(
        projectPath: Path,
        lastCommitHash: String?,
    ): List<String> =
        withContext(Dispatchers.IO) {
            try {
                if (lastCommitHash == null) {
                    logger.info { "No last commit hash provided - treating as full reindex" }
                    return@withContext emptyList()
                }

                val processBuilder =
                    ProcessBuilder("git", "diff", "--name-only", lastCommitHash, "HEAD")
                        .directory(projectPath.toFile())
                        .redirectErrorStream(true)

                val process = processBuilder.start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    val changedFiles =
                        process.inputStream.bufferedReader().use {
                            it.readLines().filter { line -> line.isNotBlank() }
                        }
                    logger.info { "Found ${changedFiles.size} changed files since commit $lastCommitHash" }
                    changedFiles
                } else {
                    logger.warn { "Failed to get changed files since commit $lastCommitHash" }
                    emptyList()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Error getting changed files since commit $lastCommitHash" }
                emptyList()
            }
        }

    /**
     * Create a hash of file content for change detection
     */
    fun createContentHash(content: String): String = content.hashCode().toString()

    /**
     * Archive old historical documents that are beyond retention period
     * This helps manage storage by moving very old versions to archived status
     */
    suspend fun archiveOldHistoricalDocuments(
        projectId: ObjectId,
        retentionDays: Int = 90,
    ): VersioningResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Archiving historical documents older than $retentionDays days for project: $projectId" }

                Instant.now().minusSeconds(retentionDays * 24 * 60 * 60L)

                // TODO: Implement archiving logic
                // 1. Find HISTORICAL documents older than cutoffDate
                // 2. Update their status to ARCHIVED
                // 3. These should not be searchable in normal RAG queries

                VersioningResult(0, 0, 0) // Placeholder
            } catch (e: Exception) {
                logger.error(e) { "Failed to archive old historical documents for project: $projectId" }
                VersioningResult(0, 0, 1)
            }
        }

    /**
     * Clean up archived documents beyond a certain age
     * This provides final cleanup for very old documents that are no longer needed
     */
    suspend fun cleanupArchivedDocuments(
        projectId: ObjectId,
        maxArchiveAgeDays: Int = 365,
    ): Int =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Cleaning up archived documents older than $maxArchiveAgeDays days for project: $projectId" }

                // TODO: Implement cleanup logic
                // 1. Find ARCHIVED documents older than maxArchiveAgeDays
                // 2. Permanently delete them from vector storage
                // 3. This should be done carefully with proper logging

                0 // Placeholder
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup archived documents for project: $projectId" }
                0
            }
        }
}
