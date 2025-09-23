package com.jervis.service.indexing

import com.jervis.common.Constants.GLOBAL_ID
import com.jervis.domain.model.ModelType
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.analysis.ProjectDescriptionService
import com.jervis.service.embedding.CodeEmbeddingService
import com.jervis.service.embedding.TextEmbeddingService
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.indexing.JoernChunkingService
import com.jervis.service.indexing.monitoring.IndexingMonitorService
import com.jervis.service.indexing.pipeline.IndexingPipelineService
import com.jervis.service.rag.RagIndexingStatusService
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
    private val joernAnalysisService: JoernAnalysisService,
    private val codeEmbeddingService: CodeEmbeddingService,
    private val textEmbeddingService: TextEmbeddingService,
    private val vectorStorage: VectorStorageRepository,
    private val embeddingGateway: EmbeddingGateway,
    private val gitHistoryIndexingService: GitHistoryIndexingService,
    private val dependencyIndexingService: DependencyIndexingService,
    private val classSummaryIndexingService: ClassSummaryIndexingService,
    private val comprehensiveFileIndexingService: ComprehensiveFileIndexingService,
    private val extensiveJoernAnalysisService: ExtensiveJoernAnalysisService,
    private val projectDescriptionService: ProjectDescriptionService,
    private val clientIndexingService: ClientIndexingService,
    private val historicalVersioningService: HistoricalVersioningService,
    private val ragIndexingStatusService: RagIndexingStatusService,
    private val documentationIndexingService: DocumentationIndexingService,
    private val meetingTranscriptIndexingService: MeetingTranscriptIndexingService,
    private val joernChunkingService: JoernChunkingService,
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
     * Index code files and store embeddings in CODE vector store
     */
    private suspend fun indexCodeFiles(
        project: ProjectDocument,
        projectPath: Path,
    ): IndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting CODE indexing for project: ${project.name}" }

            val indexingRules = project.indexingRules
            val maxFileSize = indexingRules.maxFileSizeMB * 1024 * 1024L
            var skippedFiles = 0

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(projectPath)
                    ?: run {
                        logger.warn { "Could not get git commit hash for project: ${project.name}" }
                        return@withContext IndexingResult(0, 0, 1, "CODE")
                    }

            try {
                val codeContents = mutableMapOf<Path, String>()
                val candidateFiles = mutableMapOf<Path, String>()

                // First pass: collect all potential code files
                Files
                    .walk(projectPath)
                    .filter { it.isRegularFile() }
                    .forEach { filePath ->
                        if (shouldProcessFile(filePath, projectPath, indexingRules, maxFileSize) &&
                            codeEmbeddingService.isCodeFile(filePath)
                        ) {
                            try {
                                val content = Files.readString(filePath)
                                if (content.isNotBlank()) {
                                    candidateFiles[filePath] = content
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error reading code file: ${filePath.pathString}" }
                            }
                        } else {
                            skippedFiles++
                        }
                    }

                // Second pass: filter out already indexed files to prevent duplicates
                for ((filePath, content) in candidateFiles) {
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        val shouldIndex =
                            ragIndexingStatusService.shouldIndexFile(
                                projectId = project.id,
                                filePath = relativePath,
                                gitCommitHash = gitCommitHash,
                                fileContent = content.toByteArray(),
                            )

                        if (shouldIndex) {
                            codeContents[filePath] = content
                            logger.debug { "Adding code file for indexing: $relativePath" }
                        } else {
                            skippedFiles++
                            logger.debug { "Skipping already indexed code file: $relativePath" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error checking duplicate status for code file: ${filePath.pathString}" }
                        // On error, include the file to be safe
                        codeContents[filePath] = content
                    }
                }

                // Track indexing status for each file
                for ((filePath, content) in codeContents) {
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        ragIndexingStatusService.startIndexing(
                            projectId = project.id,
                            filePath = relativePath,
                            gitCommitHash = gitCommitHash,
                            ragSourceType = RagSourceType.FILE,
                            fileContent = content.toByteArray(),
                            language =
                                filePath
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .takeIf { it.isNotEmpty() },
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Error tracking indexing status for file: ${filePath.pathString}" }
                    }
                }

                // Process all code files using the dedicated service
                val result = codeEmbeddingService.processCodeFiles(project, codeContents, projectPath)

                logger.info {
                    "CODE indexing completed - Processed: ${result.processedFiles}, " +
                        "Skipped: ${skippedFiles + result.skippedFiles}, Errors: ${result.errorFiles}"
                }

                IndexingResult(result.processedFiles, skippedFiles + result.skippedFiles, result.errorFiles, "CODE")
            } catch (e: Exception) {
                logger.error(e) { "Error during CODE indexing for project: ${project.name}" }
                IndexingResult(0, skippedFiles, 1, "CODE")
            }
        }

    /**
     * Index text content for semantic search and store embeddings in TEXT vector store
     */
    private suspend fun indexTextContent(
        project: ProjectDocument,
        projectPath: Path,
    ): IndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Starting TEXT indexing (semantic) for project: ${project.name}" }

            val indexingRules = project.indexingRules
            val maxFileSize = indexingRules.maxFileSizeMB * 1024 * 1024L
            var skippedFiles = 0
            var processedFiles = 0

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(projectPath)
                    ?: run {
                        logger.warn { "Could not get git commit hash for project: ${project.name}" }
                        return@withContext IndexingResult(0, 0, 1, "TEXT")
                    }

            try {
                val textContents = mutableMapOf<Path, String>()
                val candidateFiles = mutableMapOf<Path, String>()

                // First pass: collect all potential text files
                Files
                    .walk(projectPath)
                    .filter { it.isRegularFile() }
                    .forEach { filePath ->
                        if (shouldProcessFile(filePath, projectPath, indexingRules, maxFileSize) &&
                            textEmbeddingService.isTextContent(filePath)
                        ) {
                            try {
                                val content = Files.readString(filePath)
                                if (content.isNotBlank()) {
                                    candidateFiles[filePath] = content
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error reading text file: ${filePath.pathString}" }
                            }
                        } else {
                            skippedFiles++
                        }
                    }

                // Second pass: filter out already indexed files to prevent duplicates
                for ((filePath, content) in candidateFiles) {
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        val shouldIndex =
                            ragIndexingStatusService.shouldIndexFile(
                                projectId = project.id,
                                filePath = relativePath,
                                gitCommitHash = gitCommitHash,
                                fileContent = content.toByteArray(),
                            )

                        if (shouldIndex) {
                            textContents[filePath] = content
                            processedFiles++
                            logger.debug { "Adding text file for indexing: $relativePath" }
                        } else {
                            skippedFiles++
                            logger.debug { "Skipping already indexed text file: $relativePath" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Error checking duplicate status for text file: ${filePath.pathString}" }
                        // On error, include the file to be safe
                        textContents[filePath] = content
                        processedFiles++
                    }
                }

                // Track indexing status for each file
                for ((filePath, content) in textContents) {
                    try {
                        val relativePath = projectPath.relativize(filePath).toString()
                        ragIndexingStatusService.startIndexing(
                            projectId = project.id,
                            filePath = relativePath,
                            gitCommitHash = gitCommitHash,
                            ragSourceType = RagSourceType.FILE,
                            fileContent = content.toByteArray(),
                            language =
                                filePath
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .takeIf { it.isNotEmpty() },
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Error tracking indexing status for file: ${filePath.pathString}" }
                    }
                }

                // Process all text files using the dedicated service
                val result = textEmbeddingService.processTextContents(project, textContents, projectPath)

                logger.info {
                    "TEXT indexing completed - Files processed: $processedFiles, " +
                        "Sentences processed: ${result.processedSentences}, " +
                        "Sentences skipped: ${result.skippedSentences}, " +
                        "Sentence errors: ${result.errorSentences}, " +
                        "Files skipped: $skippedFiles"
                }

                // Return file-level statistics for consistency with other methods
                val errorFiles = if (result.errorSentences > 0) 1 else 0
                IndexingResult(processedFiles, skippedFiles, errorFiles, "TEXT")
            } catch (e: Exception) {
                logger.error(e) { "Error during TEXT indexing for project: ${project.name}" }
                IndexingResult(processedFiles, skippedFiles, 1, "TEXT")
            }
        }

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
                    indexingMonitorService.failProjectIndexing(project.id, "Project path does not exist: ${project.path}")
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

                // USE PIPELINE INSTEAD OF PARALLEL OPERATIONS
                logger.info { "Starting pipeline-based comprehensive indexing for project: ${project.name}" }
                val pipelineResult = indexingPipelineService.indexProjectWithPipeline(project, projectPath)
                
                logger.info {
                    "Pipeline indexing completed for project: ${project.name} - " +
                        "Processed: ${pipelineResult.totalProcessed}, " +
                        "Errors: ${pipelineResult.totalErrors}, " +
                        "Time: ${pipelineResult.processingTimeMs}ms, " +
                        "Throughput: ${"%.2f".format(pipelineResult.throughput)} items/sec"
                }

                // Complete project indexing monitoring
                indexingMonitorService.completeProjectIndexing(project.id)

                // Convert pipeline result to IndexingResult
                IndexingResult(
                    processedFiles = pipelineResult.totalProcessed,
                    skippedFiles = 0,
                    errorFiles = pipelineResult.totalErrors,
                    operationType = "PIPELINE_COMPREHENSIVE"
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
                    project.clientId?.let { clientId ->
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
                            clientProject.id, "client_update",
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                            message = "Updating client descriptions for client ID: $clientId",
                            logs = listOf("Starting client description update for client ID: $clientId")
                        )
                    }
                    
                    val result = clientIndexingService.updateClientDescriptions(clientId)
                    
                    if (clientProject != null) {
                        indexingMonitorService.updateStepProgress(
                            clientProject.id, "client_update",
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                            message = "Updated client descriptions (${result.projectCount} projects processed)",
                            logs = listOf(
                                "Successfully updated client descriptions",
                                "Projects processed: ${result.projectCount}",
                                "Generated short description: ${result.shortDescription.take(100)}...",
                                "Generated full description length: ${result.fullDescription.length} characters"
                            )
                        )
                    }
                    
                    logger.info { "Updated client descriptions for clientId: $clientId" }
                } catch (e: Exception) {
                    // Find a project from this client to report the failure
                    val clientProject = enabledProjects.find { it.clientId == clientId }
                    if (clientProject != null) {
                        indexingMonitorService.updateStepProgress(
                            clientProject.id, "client_update",
                            com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                            errorMessage = "Failed to update client descriptions: ${e.message}",
                            logs = listOf("Error updating client descriptions: ${e.message}")
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
                        clientProject.id, "client_update",
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.RUNNING,
                        message = "Updating client descriptions for '$clientName'",
                        logs = listOf("Starting client description update for client: $clientName")
                    )
                }
                
                val result = clientIndexingService.updateClientDescriptions(id)
                
                if (clientProject != null) {
                    indexingMonitorService.updateStepProgress(
                        clientProject.id, "client_update",
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.COMPLETED,
                        message = "Updated descriptions for '$clientName' (${result.projectCount} projects)",
                        logs = listOf(
                            "Successfully updated client descriptions for: $clientName",
                            "Projects processed: ${result.projectCount}",
                            "Generated short description: ${result.shortDescription.take(100)}...",
                            "Generated full description length: ${result.fullDescription.length} characters"
                        )
                    )
                }
                
                logger.info { "Updated client descriptions for client: $clientName" }
            } catch (e: Exception) {
                // Find a representative project from this client to report the failure
                val clientProject = enabledProjects.find { it.clientId == id }
                if (clientProject != null) {
                    indexingMonitorService.updateStepProgress(
                        clientProject.id, "client_update",
                        com.jervis.service.indexing.monitoring.IndexingStepStatus.FAILED,
                        errorMessage = "Failed to update client descriptions for '$clientName': ${e.message}",
                        logs = listOf("Error updating client descriptions for $clientName: ${e.message}")
                    )
                }
                
                logger.warn(e) { "Failed to update client descriptions for client: $clientName" }
            }
        }

        logger.info {
            "Sequential client '$clientName' projects indexing completed. Success: $successCount, Errors: $errorCount, Skipped: ${projects.size - enabledProjects.size}"
        }
    }

    /**
     * Index Joern analysis results into RAG system for later retrieval by Planner
     */
    suspend fun indexJoernAnalysisResults(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): IndexingResult =
        withContext(Dispatchers.Default) {
            logger.info { "Starting Joern analysis results indexing for project: ${project.name}" }

            var processedFiles = 0
            var errorFiles = 0

            // Get current git commit hash for tracking
            val gitCommitHash =
                historicalVersioningService.getCurrentGitCommitHash(projectPath)
                    ?: run {
                        logger.warn { "Could not get git commit hash for project: ${project.name}" }
                        return@withContext IndexingResult(0, 0, 1, "JOERN_INDEX")
                    }

            try {
                // Find all Joern analysis result files in the .joern directory
                val resultFiles = mutableListOf<Path>()
                Files
                    .walk(joernDir)
                    .filter { it.isRegularFile() }
                    .filter { path ->
                        val fileName = path.fileName.toString().lowercase()
                        fileName.endsWith(".json") &&
                            (fileName.contains("scan") || fileName.contains("results") || fileName.contains("cpg"))
                    }.forEach { resultFiles.add(it) }

                logger.debug { "Found ${resultFiles.size} Joern result files to index" }

                for (resultFile in resultFiles) {
                    try {
                        val content = Files.readString(resultFile)
                        if (content.isNotBlank()) {
                            // Create human-readable summary of the analysis results
                            val analysisType =
                                when {
                                    resultFile.fileName.toString().contains("scan") -> "Security Scan"
                                    resultFile.fileName.toString().contains("cpg") -> "Code Property Graph"
                                    else -> "Static Analysis"
                                }

                            val summary =
                                buildString {
                                    appendLine("Joern $analysisType Results")
                                    appendLine("=".repeat(50))
                                    appendLine("Project: ${project.name}")
                                    appendLine("Analysis File: ${resultFile.fileName}")
                                    appendLine("Generated: ${java.time.Instant.now()}")
                                    appendLine()
                                    appendLine("Results Summary:")
                                    appendLine(content)
                                    appendLine()
                                    appendLine("This analysis was performed by Joern static analysis tool.")
                                    appendLine(
                                        "The results can help identify security vulnerabilities, code patterns, and structural information.",
                                    )
                                }

                            // Generate embedding for the analysis summary
                            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, summary)

                            // Create RAG document for the analysis results
                            val ragDocument =
                                RagDocument(
                                    projectId = project.id,
                                    clientId = project.clientId,
                                    documentType = RagDocumentType.JOERN_ANALYSIS,
                                    ragSourceType = RagSourceType.ANALYSIS,
                                    pageContent = summary,
                                    source = "joern://${project.name}/${resultFile.fileName}",
                                    path = projectPath.relativize(resultFile).pathString,
                                    module = "joern-analysis",
                                    language = "analysis-report",
                                    gitCommitHash = gitCommitHash,
                                )

                            // Track indexing status for this analysis file
                            try {
                                val relativePath = projectPath.relativize(resultFile).toString()
                                ragIndexingStatusService.startIndexing(
                                    projectId = project.id,
                                    filePath = relativePath,
                                    gitCommitHash = gitCommitHash,
                                    ragSourceType = RagSourceType.ANALYSIS,
                                    fileContent = content.toByteArray(),
                                    language = "analysis-report",
                                    module = "joern-analysis",
                                )
                            } catch (e: Exception) {
                                logger.warn(e) { "Error tracking indexing status for analysis file: ${resultFile.fileName}" }
                            }

                            // Store in TEXT vector store for semantic search
                            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
                            processedFiles++

                            logger.debug { "Indexed Joern analysis result: ${resultFile.fileName}" }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to index Joern result file: ${resultFile.fileName}" }
                        errorFiles++
                    }
                }

                logger.info {
                    "Joern analysis results indexing completed for project: ${project.name} - " +
                        "Processed: $processedFiles, Errors: $errorFiles"
                }

                IndexingResult(processedFiles, 0, errorFiles, "JOERN_INDEX")
            } catch (e: Exception) {
                logger.error(e) { "Error during Joern analysis results indexing for project: ${project.name}" }
                IndexingResult(0, 0, 1, "JOERN_INDEX")
            }
        }

    /**
     * Index git history with incremental updates
     */
    suspend fun indexGitHistory(
        project: ProjectDocument,
        projectPath: Path,
    ): IndexingResult =
        withContext(Dispatchers.Default) {
            try {
                val result = gitHistoryIndexingService.indexGitHistory(project, projectPath)
                IndexingResult(
                    result.processedCommits,
                    result.skippedCommits,
                    result.errorCommits,
                    "GIT_HISTORY_INDEX",
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to index git history for project: ${project.name}" }
                IndexingResult(0, 0, 1, "GIT_HISTORY_INDEX")
            }
        }

    /**
     * Index dependencies from Joern analysis
     */
    suspend fun indexDependencies(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): IndexingResult =
        withContext(Dispatchers.Default) {
            try {
                val result = dependencyIndexingService.indexDependenciesFromJoern(project, projectPath, joernDir)
                IndexingResult(
                    result.processedDependencies,
                    result.skippedDependencies,
                    result.errorDependencies,
                    "DEPENDENCY_INDEX",
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to index dependencies for project: ${project.name}" }
                IndexingResult(0, 0, 1, "DEPENDENCY_INDEX")
            }
        }

    /**
     * Index class summaries using LLM analysis
     */
    suspend fun indexClassSummaries(
        project: ProjectDocument,
        projectPath: Path,
        joernDir: Path,
    ): IndexingResult =
        withContext(Dispatchers.Default) {
            try {
                // Get current git commit hash for tracking
                val gitCommitHash =
                    historicalVersioningService.getCurrentGitCommitHash(projectPath)
                        ?: run {
                            logger.warn { "Could not get git commit hash for project: ${project.name}" }
                            return@withContext IndexingResult(0, 0, 1, "CLASS_SUMMARY_INDEX")
                        }

                val result =
                    classSummaryIndexingService.indexClassSummaries(project, projectPath, joernDir, gitCommitHash)
                IndexingResult(
                    result.processedClasses,
                    result.skippedClasses,
                    result.errorClasses,
                    "CLASS_SUMMARY_INDEX",
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to index class summaries for project: ${project.name}" }
                IndexingResult(0, 0, 1, "CLASS_SUMMARY_INDEX")
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
}
