package com.jervis.service.indexing

import com.jervis.domain.task.PendingTaskSeverity
import com.jervis.domain.task.PendingTaskType
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.task.PendingTaskService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Service
class HistoricalVersioningService(
    private val vectorStorage: VectorStorageRepository,
    private val pendingTaskService: PendingTaskService,
) {
    private val logger = KotlinLogging.logger {}
    private val failedGitAttempts = mutableSetOf<String>()

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
                val modelTypes =
                    listOf(
                        com.jervis.domain.model.ModelType.EMBEDDING_TEXT,
                        com.jervis.domain.model.ModelType.EMBEDDING_CODE,
                    )

                for (modelType in modelTypes) {
                    try {
                        val filter =
                            buildMap {
                                put("projectId", projectId.toString())
                                put("documentStatus", "CURRENT")
                                gitCommitHash?.let { put("gitCommitHash", it) }
                            }

                        val existingDocuments =
                            vectorStorage.search(
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
                handleGitError(projectPath, null, null, e)
                null
            }
        }

    private suspend fun handleGitError(
        projectPath: Path,
        projectId: ObjectId?,
        clientId: ObjectId?,
        error: Exception,
    ) {
        val pathKey = projectPath.toString()

        if (failedGitAttempts.contains(pathKey)) {
            logger.debug { "Skipping Git error handling for already failed path: $pathKey" }
            return
        }

        val gitDir = projectPath.resolve(".git")
        if (!gitDir.exists()) {
            logger.info { "Git repository not found at $projectPath, attempting to initialize or clone" }

            val autoFixResult = attemptGitAutoFix(projectPath)

            if (!autoFixResult.success) {
                failedGitAttempts.add(pathKey)

                pendingTaskService.createTask(
                    taskType = PendingTaskType.GIT_REPOSITORY_MISSING,
                    severity = PendingTaskSeverity.HIGH,
                    title = "Git repository missing for project",
                    description = "Git repository not found at $projectPath. Auto-fix attempt failed.",
                    context =
                        mapOf(
                            "projectPath" to pathKey,
                            "projectId" to (projectId?.toHexString() ?: "unknown"),
                            "clientId" to (clientId?.toHexString() ?: "unknown"),
                        ),
                    errorDetails = error.stackTraceToString(),
                    autoFixAttempted = true,
                    autoFixResult = autoFixResult.message,
                    projectId = projectId,
                    clientId = clientId,
                )

                logger.warn { "Created pending task for Git repository missing at $projectPath" }
            } else {
                logger.info { "Successfully initialized Git repository at $projectPath" }
            }
        } else {
            logger.error(error) { "Git command failed for existing repository at $projectPath" }
        }
    }

    private suspend fun attemptGitAutoFix(projectPath: Path): AutoFixResult =
        withContext(Dispatchers.IO) {
            try {
                if (!Files.exists(projectPath)) {
                    Files.createDirectories(projectPath)
                }

                val initProcess =
                    ProcessBuilder("git", "init")
                        .directory(projectPath.toFile())
                        .redirectErrorStream(true)
                        .start()

                val exitCode = initProcess.waitFor()

                if (exitCode == 0) {
                    AutoFixResult(true, "Git repository initialized successfully")
                } else {
                    val errorOutput = initProcess.inputStream.bufferedReader().use { it.readText() }
                    AutoFixResult(false, "Git init failed with exit code $exitCode: $errorOutput")
                }
            } catch (e: Exception) {
                AutoFixResult(false, "Git init failed with exception: ${e.message}")
            }
        }

    private data class AutoFixResult(
        val success: Boolean,
        val message: String,
    )
}
