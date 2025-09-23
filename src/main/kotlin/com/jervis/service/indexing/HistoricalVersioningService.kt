package com.jervis.service.indexing

import com.jervis.repository.vector.VectorStorageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

                // Search for all CURRENT documents for this project across both collection types
                val modelTypes = listOf(
                    com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                    com.jervis.domain.model.ModelType.EMBEDDING_CODE
                )

                for (modelType in modelTypes) {
                    try {
                        val filter = buildMap {
                            put("projectId", projectId.toString())
                            put("documentStatus", "CURRENT")
                            gitCommitHash?.let { put("gitCommitHash", it) }
                        }

                        val existingDocuments = vectorStorage.search(
                            collectionType = modelType,
                            query = emptyList(), // Empty query to get all matching filters
                            limit = 10000,
                            filter = filter,
                        )

                        logger.debug { "Found ${existingDocuments.size} CURRENT documents to mark as historical in $modelType collection" }

                        // Note: Due to vector storage limitations, we cannot update documents in place.
                        // Instead, we mark them for cleanup during next indexing cycle.
                        // The actual implementation would require either:
                        // 1. Delete and re-insert with updated status (not ideal for vector similarity)
                        // 2. Add update capability to VectorStorageRepository
                        // 3. Use a separate metadata store for document status tracking

                        // For now, we'll count the documents that would be marked
                        markedHistorical += existingDocuments.size

                        logger.debug { "Would mark ${existingDocuments.size} documents as historical in $modelType collection" }

                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process $modelType collection for historical marking" }
                        errors++
                    }
                }

                logger.info { "Historical versioning completed for project: $projectId - Would mark $markedHistorical documents" }
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
                logger.debug { "Checking document existence for path: $path, contentHash: $contentHash" }

                // Search across both collection types for documents with matching path and content hash
                val modelTypes = listOf(
                    com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                    com.jervis.domain.model.ModelType.EMBEDDING_CODE
                )

                for (modelType in modelTypes) {
                    val filter = mapOf(
                        "projectId" to projectId.toString(),
                        "path" to path,
                        "documentStatus" to "CURRENT" // Only check current documents
                    )

                    val existingDocuments = vectorStorage.search(
                        collectionType = modelType,
                        query = emptyList(), // Empty query to get all matching filters
                        limit = 100, // Should be enough for same path matches
                        filter = filter,
                    )

                    // Check if any document has the same content hash
                    for (document in existingDocuments) {
                        val docContentHash = document["pageContent"]?.stringValue?.let { content ->
                            createContentHash(content)
                        }

                        if (docContentHash == contentHash) {
                            logger.debug { "Found existing document with same content hash for path: $path" }
                            return@withContext true
                        }
                    }
                }

                logger.debug { "No existing document with same content hash found for path: $path" }
                false

            } catch (e: Exception) {
                logger.warn(e) { "Failed to check document existence for path: $path" }
                false // Default to indexing on error to be safe
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
                    val hash = process.inputStream.bufferedReader().use { it.readText().trim() }
                    logger.debug { "Retrieved Git commit hash for $projectPath: $hash" }
                    hash
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

                val cutoffDate = Instant.now().minusSeconds(retentionDays * 24 * 60 * 60L)
                var archivedDocuments = 0
                var preservedDocuments = 0
                var errorDocuments = 0

                // Process both collection types
                val modelTypes = listOf(
                    com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                    com.jervis.domain.model.ModelType.EMBEDDING_CODE
                )

                for (modelType in modelTypes) {
                    try {
                        val filter = mapOf(
                            "projectId" to projectId.toString(),
                            "documentStatus" to "HISTORICAL"
                        )

                        val historicalDocuments = vectorStorage.search(
                            collectionType = modelType,
                            query = emptyList(), // Empty query to get all matching filters
                            limit = 10000,
                            filter = filter,
                        )

                        logger.debug { "Found ${historicalDocuments.size} HISTORICAL documents in $modelType collection" }

                        for (document in historicalDocuments) {
                            try {
                                // Check document age - use lastModified if available, otherwise createdAt
                                val documentTimestamp = document["lastModified"]?.integerValue?.toLong()
                                    ?: document["createdAt"]?.integerValue?.toLong()
                                    ?: document["timestamp"]?.integerValue?.toLong()
                                    ?: continue // Skip if no timestamp available

                                val documentDate = Instant.ofEpochMilli(documentTimestamp)
                                
                                if (documentDate.isBefore(cutoffDate)) {
                                    // Document is old enough to archive
                                    // Note: Due to vector database limitations, we cannot update documents in place
                                    // This would require:
                                    // 1. Extract the document data
                                    // 2. Delete the old document
                                    // 3. Re-insert with ARCHIVED status
                                    // For now, we count what would be archived
                                    
                                    archivedDocuments++
                                    logger.debug { "Would archive document from ${document["source"]?.stringValue} (age: ${java.time.Duration.between(documentDate, Instant.now()).toDays()} days)" }
                                } else {
                                    preservedDocuments++
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error processing document for archiving: ${document["source"]?.stringValue}" }
                                errorDocuments++
                            }
                        }

                        logger.info { "Archiving analysis for $modelType: would archive $archivedDocuments, preserve $preservedDocuments, errors $errorDocuments" }

                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process $modelType collection for archiving" }
                        errorDocuments++
                    }
                }

                logger.info { "Historical document archiving completed for project: $projectId - Would archive: $archivedDocuments, Preserve: $preservedDocuments, Errors: $errorDocuments" }
                VersioningResult(archivedDocuments, preservedDocuments, errorDocuments)
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

                val cutoffDate = Instant.now().minusSeconds(maxArchiveAgeDays * 24 * 60 * 60L)
                var documentsToDelete = 0

                // Process both collection types
                val modelTypes = listOf(
                    com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                    com.jervis.domain.model.ModelType.EMBEDDING_CODE
                )

                for (modelType in modelTypes) {
                    try {
                        val filter = mapOf(
                            "projectId" to projectId.toString(),
                            "documentStatus" to "ARCHIVED"
                        )

                        val archivedDocuments = vectorStorage.search(
                            collectionType = modelType,
                            query = emptyList(), // Empty query to get all matching filters
                            limit = 10000,
                            filter = filter,
                        )

                        logger.debug { "Found ${archivedDocuments.size} ARCHIVED documents in $modelType collection" }

                        for (document in archivedDocuments) {
                            try {
                                // Check document age - use archivedAt if available, otherwise lastModified/createdAt
                                val documentTimestamp = document["archivedAt"]?.integerValue?.toLong()
                                    ?: document["lastModified"]?.integerValue?.toLong()
                                    ?: document["createdAt"]?.integerValue?.toLong()
                                    ?: document["timestamp"]?.integerValue?.toLong()
                                    ?: continue // Skip if no timestamp available

                                val documentDate = Instant.ofEpochMilli(documentTimestamp)
                                
                                if (documentDate.isBefore(cutoffDate)) {
                                    // Document is old enough to delete
                                    // Note: VectorStorageRepository doesn't currently have delete functionality
                                    // This would require implementing a delete method that:
                                    // 1. Extracts the document ID from the search results
                                    // 2. Calls Qdrant delete API to permanently remove the document
                                    // For now, we count what would be deleted
                                    
                                    documentsToDelete++
                                    logger.debug { 
                                        "Would delete archived document from ${document["source"]?.stringValue} " +
                                        "(archived age: ${java.time.Duration.between(documentDate, Instant.now()).toDays()} days)" 
                                    }
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error processing archived document for cleanup: ${document["source"]?.stringValue}" }
                            }
                        }

                        logger.info { "Cleanup analysis for $modelType: would delete $documentsToDelete archived documents" }

                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process $modelType collection for cleanup" }
                    }
                }

                logger.info { 
                    "Archived document cleanup completed for project: $projectId - Would delete: $documentsToDelete documents " +
                    "(actual deletion requires implementing delete functionality in VectorStorageRepository)" 
                }
                
                documentsToDelete
            } catch (e: Exception) {
                logger.error(e) { "Failed to cleanup archived documents for project: $projectId" }
                0
            }
        }
}
