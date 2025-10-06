package com.jervis.service.indexing

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.domain.model.ModelType
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.pipeline.IndexingPipelineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString

/**
 * Service for indexing project files and generating embeddings with parallel Joern analysis
 */
@Service
class IndexingService(
    private val vectorStorage: VectorStorageRepository,
    private val embeddingGateway: EmbeddingGateway,
    private val gitHistoryIndexingService: GitHistoryIndexingService,
    private val clientIndexingService: ClientIndexingService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val documentIndexingService: DocumentIndexingService,
    private val indexingMonitorService: IndexingMonitorService,
    private val indexingPipelineService: IndexingPipelineService,
) {
    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * Data class to track indexing operation results
     */
    data class IndexingResult(
        val processedFiles: Int,
        val skippedFiles: Int,
        val errorFiles: Int,
        val operationType: String,
    )

    /**
     * Comprehensive project indexing using streaming pipeline architecture
     */
    suspend fun indexProject(project: ProjectDocument): IndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting PIPELINE indexing for project: ${project.name}" }

                // Start project indexing monitoring
                indexingMonitorService.startProjectIndexing(project.id, project.name)

                val projectPath = Paths.get(project.path)

                if (!Files.exists(projectPath)) {
                    logger.error { "Project path does not exist: ${project.path}" }
                    indexingMonitorService.failProjectIndexing(
                        project.id,
                        "Project path does not exist: ${project.path}",
                    )
                    return@withContext IndexingResult(0, 0, 1, "PIPELINE_COMPREHENSIVE")
                }

                // Mark existing documents as historical to prevent duplication in RAG
                logger.info { "Marking existing documents as historical for project: ${project.name}" }
                try {
                    val gitCommitHash = historicalVersioningService.getCurrentGitCommitHash(projectPath)
                    val versioningResult =
                        historicalVersioningService.markProjectDocumentsAsHistorical(project.id, gitCommitHash)
                    logger.info {
                        "Historical versioning completed for project: ${project.name} - " +
                            "Marked historical: ${versioningResult.markedHistorical}, " +
                            "Preserved: ${versioningResult.preserved}, " +
                            "Errors: ${versioningResult.errors}"
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to mark documents as historical for project: ${project.name}, continuing with indexing" }
                }

                // PHASE 1: Primary Joern Pipeline (sequential)
                logger.info { "PHASE 1: Starting Joern pipeline for project: ${project.name}" }
                val joernResult = indexingPipelineService.indexProjectWithPipeline(project, projectPath)

                // PHASE 2: Fallback Pipeline for files not processed by Joern (sequential)
                logger.info { "PHASE 2: Starting fallback pipeline for non-Joern files in project: ${project.name}" }
                val joernProcessedFiles = getJoernProcessedFiles(project, projectPath)
                val fallbackResult = processFallbackFiles(project, projectPath, joernProcessedFiles)

                // PHASE 3: Parallel pipelines for additional content (parallel)
                logger.info { "PHASE 3: Starting parallel pipelines for additional content in project: ${project.name}" }
                val parallelTasks = mutableListOf<kotlinx.coroutines.Deferred<Any>>()

                // Git history pipeline
                parallelTasks +=
                    async {
                        try {
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.GIT_HISTORY,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                                message = "Indexing git history",
                            )
                            val result = gitHistoryIndexingService.indexGitHistory(project, projectPath)
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.GIT_HISTORY,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                                message =
                                    "Git history indexed (processed=${result.processedCommits}, " +
                                        "skipped=${result.skippedCommits}, errors=${result.errorCommits})",
                            )
                            result
                        } catch (e: Exception) {
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.GIT_HISTORY,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                                errorMessage = "Git history failed: ${e.message}",
                            )
                            throw e
                        }
                    }

                // Unified document indexing (documentation and meeting transcripts)
                parallelTasks +=
                    async {
                        try {
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.DOCUMENTATION,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                                message = "Indexing all project documents (documentation and meetings)",
                            )
                            val result = documentIndexingService.indexProjectDocuments(project)
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.DOCUMENTATION,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                                message =
                                    "All documents indexed (processed=${result.processedDocuments}, " +
                                        "skipped=${result.skippedDocuments}, errors=${result.errorDocuments})",
                            )
                            result
                        } catch (e: Exception) {
                            indexingMonitorService.updateStepProgress(
                                project.id,
                                com.jervis.service.indexing.monitoring.IndexingStepType.DOCUMENTATION,
                                com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                                errorMessage = "Unified document indexing failed: ${e.message}",
                            )
                            throw e
                        }
                    }

                // Wait for parallel tasks
                val parallelResults = parallelTasks.awaitAll()
                val results = listOf(joernResult, fallbackResult) + parallelResults

                // Complete project indexing monitoring
                indexingMonitorService.completeProjectIndexing(project.id)

                // Convert main pipeline result to IndexingResult for return value
                val pipelineResult =
                    results
                        .filterIsInstance<com.jervis.service.indexing.pipeline.IndexingPipelineResult>()
                        .firstOrNull()
                val processed = pipelineResult?.totalProcessed ?: 0
                val errors = pipelineResult?.totalErrors ?: 0

                IndexingResult(
                    processedFiles = processed,
                    skippedFiles = 0,
                    errorFiles = errors,
                    operationType = "PIPELINE_COMPREHENSIVE",
                )
            } catch (e: Exception) {
                logger.error(e) { "Pipeline indexing failed for project: ${project.name}" }

                // Fail project indexing monitoring
                indexingMonitorService.failProjectIndexing(project.id, "Pipeline indexing failed: ${e.message}")

                IndexingResult(0, 0, 1, "PIPELINE_COMPREHENSIVE")
            }
        }

    /**
     * Index all projects in the system with sequential execution
     * Projects are processed one by one to avoid resource contention and ensure stable processing
     */
    suspend fun indexAllProjects(projects: List<ProjectDocument>) =
        withContext(Dispatchers.Default) {
            logger.info { "Starting sequential indexing for all ${projects.size} projects" }

            val enabledProjects = projects.filter { !it.isDisabled }
            logger.info {
                "Processing ${enabledProjects.size} enabled projects sequentially (${projects.size - enabledProjects.size} disabled projects skipped)"
            }

            var successCount = 0
            var errorCount = 0
            val processedClientIds = mutableSetOf<org.bson.types.ObjectId>()

            // Process projects sequentially, one by one
            for (project in enabledProjects) {
                try {
                    logger.info { "Starting comprehensive indexing for project: ${project.name}" }
                    indexProject(project)

                    // Collect client IDs for later batch update (don't update individually)
                    project.clientId.let { clientId ->
                        processedClientIds.add(clientId)
                    }

                    logger.info { "Successfully indexed project: ${project.name}" }
                    successCount++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index project: ${project.name}" }
                    errorCount++
                }
            }

            // Update client descriptions once after all projects are indexed
            for (clientId in processedClientIds) {
                try {
                    // Find a project from this client to get project context for monitoring
                    val clientProject = enabledProjects.find { it.clientId == clientId }
                    if (clientProject != null) {
                        indexingMonitorService.updateStepProgress(
                            clientProject.id,
                            com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                            message = "Updating client descriptions for client ID: $clientId",
                            logs = listOf("Starting client description update for client ID: $clientId"),
                        )
                    }

                    val result = clientIndexingService.updateClientDescriptions(clientId)

                    if (clientProject != null) {
                        indexingMonitorService.updateStepProgress(
                            clientProject.id,
                            com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                            message = "Updated client descriptions (${result.projectCount} projects processed)",
                            logs =
                                listOf(
                                    "Successfully updated client descriptions",
                                    "Projects processed: ${result.projectCount}",
                                    "Generated short description: ${result.shortDescription.take(100)}...",
                                    "Generated full description length: ${result.fullDescription.length} characters",
                                ),
                        )
                    }

                    logger.info { "Updated client descriptions for clientId: $clientId" }
                } catch (e: Exception) {
                    // Find a project from this client to report the failure
                    val clientProject = enabledProjects.find { it.clientId == clientId }
                    if (clientProject != null) {
                        indexingMonitorService.updateStepProgress(
                            clientProject.id,
                            com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                            errorMessage = "Failed to update client descriptions: ${e.message}",
                            logs = listOf("Error updating client descriptions: ${e.message}"),
                        )
                    }

                    logger.warn(e) { "Failed to update client descriptions for clientId: $clientId" }
                }
            }

            logger.info {
                "Sequential projects indexing completed. Success: $successCount, Errors: $errorCount, Skipped: ${projects.size - enabledProjects.size}"
            }
        }

    /**
     * Index projects for a specific client with sequential execution
     * Projects are processed one by one to avoid resource contention and ensure stable processing
     */
    suspend fun indexProjectsForClient(
        projects: List<ProjectDocument>,
        clientName: String,
    ) = withContext(Dispatchers.Default) {
        logger.info { "Starting sequential indexing for client '$clientName' with ${projects.size} projects" }

        val enabledProjects = projects.filter { !it.isDisabled }
        logger.info {
            "Processing ${enabledProjects.size} enabled projects sequentially for client '$clientName' (${projects.size - enabledProjects.size} disabled projects skipped)"
        }

        var clientId: org.bson.types.ObjectId? = null
        var successCount = 0
        var errorCount = 0

        // Process projects sequentially, one by one
        for (project in enabledProjects) {
            try {
                logger.info { "Starting comprehensive indexing for project: ${project.name} (client: $clientName)" }
                indexProject(project)

                // Store clientId for later client description update
                if (clientId == null && project.clientId != GLOBAL_ID) {
                    clientId = project.clientId
                }

                logger.info { "Successfully indexed project: ${project.name} (client: $clientName)" }
                successCount++
            } catch (e: Exception) {
                logger.error(e) { "Failed to index project: ${project.name} (client: $clientName)" }
                errorCount++
            }
        }

        // Update client descriptions once after all projects are indexed
        clientId?.let { id ->
            try {
                // Find a representative project from this client for monitoring context
                val clientProject = enabledProjects.find { it.clientId == id }
                if (clientProject != null) {
                    indexingMonitorService.updateStepProgress(
                        clientProject.id,
                        com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                        message = "Updating client descriptions for '$clientName'",
                        logs = listOf("Starting client description update for client: $clientName"),
                    )
                }

                val result = clientIndexingService.updateClientDescriptions(id)

                if (clientProject != null) {
                    indexingMonitorService.updateStepProgress(
                        clientProject.id,
                        com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                        message = "Updated descriptions for '$clientName' (${result.projectCount} projects)",
                        logs =
                            listOf(
                                "Successfully updated client descriptions for: $clientName",
                                "Projects processed: ${result.projectCount}",
                                "Generated short description: ${result.shortDescription.take(100)}...",
                                "Generated full description length: ${result.fullDescription.length} characters",
                            ),
                    )
                }

                logger.info { "Updated client descriptions for client: $clientName" }
            } catch (e: Exception) {
                // Find a representative project from this client to report the failure
                val clientProject = enabledProjects.find { it.clientId == id }
                if (clientProject != null) {
                    indexingMonitorService.updateStepProgress(
                        clientProject.id,
                        com.jervis.service.indexing.monitoring.IndexingStepType.CLIENT_UPDATE,
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                        errorMessage = "Failed to update client descriptions for '$clientName': ${e.message}",
                        logs = listOf("Error updating client descriptions for $clientName: ${e.message}"),
                    )
                }

                logger.warn(e) { "Failed to update client descriptions for client: $clientName" }
            }
        }

        logger.info {
            "Sequential client '$clientName' projects indexing completed. Success: $successCount, Errors: $errorCount, Skipped: ${projects.size - enabledProjects.size}"
        }
    }

    private fun shouldProcessFile(
        filePath: Path,
        projectPath: Path,
        indexingRules: IndexingRules,
        maxFileSize: Long,
    ): Boolean {
        val relativePath = projectPath.relativize(filePath).pathString.replace('\\', '/')

        // Check file size
        if (Files.size(filePath) > maxFileSize) {
            return false
        }

        // Check exclude patterns first
        for (excludeGlob in indexingRules.excludeGlobs) {
            if (matchesGlob(relativePath, excludeGlob.trim())) {
                return false
            }
        }

        // Check include patterns
        for (includeGlob in indexingRules.includeGlobs) {
            if (matchesGlob(relativePath, includeGlob.trim())) {
                return true
            }
        }

        return false
    }

    private fun matchesGlob(
        path: String,
        globPattern: String,
    ): Boolean {
        // Simple glob pattern matching - converts glob to regex
        val regex =
            globPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")

        return path.matches(Regex(regex, RegexOption.IGNORE_CASE))
    }

    /**
     * Check if a file is supported by Joern for analysis
     */
    private fun isJoernSupportedFile(filePath: Path): Boolean {
        val extension = filePath.toString().substringAfterLast('.', "").lowercase()
        return when (extension) {
            "java", "kt", "scala", "groovy", "js", "ts", "cpp", "c", "h", "hpp", "py", "go" -> true
            else -> false
        }
    }

    /**
     * Get list of files that were successfully processed by Joern
     */
    private suspend fun getJoernProcessedFiles(
        project: ProjectDocument,
        projectPath: Path,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            // This would ideally query the vector storage for files with ragSourceType = JOERN
            // For now, return files that are Joern-supported based on extension
            try {
                Files.walk(projectPath).use { stream ->
                    stream
                        .filter { it.isRegularFile() }
                        .filter {
                            shouldProcessFile(
                                it,
                                projectPath,
                                project.indexingRules,
                                project.indexingRules.maxFileSizeMB * 1024 * 1024L,
                            )
                        }.filter { isJoernSupportedFile(it) }
                        .map { projectPath.relativize(it).toString() }
                        .toList()
                        .toSet()
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to get Joern processed files, returning empty set" }
                emptySet()
            }
        }

    /**
     * Process files that were not handled by Joern using fallback pipeline
     */
    private suspend fun processFallbackFiles(
        project: ProjectDocument,
        projectPath: Path,
        joernProcessedFiles: Set<String>,
    ): IndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting fallback processing for non-Joern files in project: ${project.name}" }

            var processedFiles = 0
            var errorFiles = 0
            var skippedFiles = 0

            try {
                val indexingRules = project.indexingRules
                val maxFileSize = indexingRules.maxFileSizeMB * 1024 * 1024L

                // Get git commit hash for tracking
                val gitCommitHash = historicalVersioningService.getCurrentGitCommitHash(projectPath)

                // Collect files first, then process them with suspension support
                val filesToProcess =
                    Files.walk(projectPath).use { stream ->
                        stream
                            .filter { it.isRegularFile() }
                            .filter { shouldProcessFile(it, projectPath, indexingRules, maxFileSize) }
                            .toList()
                    }

                for (filePath in filesToProcess) {
                    val relativePath = projectPath.relativize(filePath).toString()

                    // Only process files not handled by Joern
                    if (!joernProcessedFiles.contains(relativePath)) {
                        try {
                            val extension = filePath.toString().substringAfterLast('.', "").lowercase()

                            when {
                                // YAML, JSON, XML configuration files
                                extension in
                                    setOf(
                                        "yaml",
                                        "yml",
                                        "json",
                                        "xml",
                                        "toml",
                                        "ini",
                                        "properties",
                                        "conf",
                                    )
                                -> {
                                    processConfigurationFile(project, filePath, projectPath, gitCommitHash)
                                    processedFiles++
                                }
                                // Documentation files
                                extension in setOf("md", "txt", "rst", "adoc") -> {
                                    processDocumentationFile(project, filePath, projectPath, gitCommitHash)
                                    processedFiles++
                                }
                                // SQL files
                                extension == "sql" -> {
                                    processSqlFile(project, filePath, projectPath, gitCommitHash)
                                    processedFiles++
                                }

                                else -> {
                                    logger.debug { "Skipping unsupported fallback file: $relativePath" }
                                    skippedFiles++
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to process fallback file: $relativePath" }
                            errorFiles++
                        }
                    } else {
                        skippedFiles++ // Already processed by Joern
                    }
                }

                logger.info { "Fallback processing completed - processed: $processedFiles, skipped: $skippedFiles, errors: $errorFiles" }
                IndexingResult(processedFiles, skippedFiles, errorFiles, "FALLBACK")
            } catch (e: Exception) {
                logger.error(e) { "Fallback processing failed for project: ${project.name}" }
                IndexingResult(processedFiles, skippedFiles, errorFiles + 1, "FALLBACK")
            }
        }

    /**
     * Process configuration files (YAML, JSON, XML, etc.)
     */
    private suspend fun processConfigurationFile(
        project: ProjectDocument,
        filePath: Path,
        projectPath: Path,
        gitCommitHash: String?,
    ) {
        val content = Files.readString(filePath)
        val relativePath = projectPath.relativize(filePath).toString()

        val ragDocument =
            RagDocument(
                projectId = project.id,
                clientId = project.clientId,
                ragSourceType = RagSourceType.TEXT_CONTENT,
                summary = "Configuration file: ${filePath.fileName}",
                path = relativePath,
                language = filePath.toString().substringAfterLast('.', ""),
                gitCommitHash = gitCommitHash,
            )

        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, content)
        vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
    }

    /**
     * Process documentation files (Markdown, text files, etc.)
     */
    private suspend fun processDocumentationFile(
        project: ProjectDocument,
        filePath: Path,
        projectPath: Path,
        gitCommitHash: String?,
    ) {
        val content = Files.readString(filePath)
        val relativePath = projectPath.relativize(filePath).toString()

        val ragDocument =
            RagDocument(
                projectId = project.id,
                clientId = project.clientId,
                ragSourceType = RagSourceType.DOCUMENTATION,
                summary = "Documentation: ${filePath.fileName}",
                path = relativePath,
                language = filePath.toString().substringAfterLast('.', ""),
                gitCommitHash = gitCommitHash,
            )

        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, content)
        vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
    }

    /**
     * Process SQL files
     */
    private suspend fun processSqlFile(
        project: ProjectDocument,
        filePath: Path,
        projectPath: Path,
        gitCommitHash: String?,
    ) {
        val content = Files.readString(filePath)
        val relativePath = projectPath.relativize(filePath).toString()

        val ragDocument =
            RagDocument(
                projectId = project.id,
                clientId = project.clientId,
                ragSourceType = RagSourceType.CODE_FALLBACK,
                summary = "SQL file: ${filePath.fileName}",
                path = relativePath,
                language = "sql",
                gitCommitHash = gitCommitHash,
            )

        val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_CODE, content)
        vectorStorage.store(ModelType.EMBEDDING_CODE, ragDocument, embedding)
    }
}
