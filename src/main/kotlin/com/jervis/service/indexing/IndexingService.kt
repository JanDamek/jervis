package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.project.IndexingRules
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.analysis.JoernAnalysisService
import com.jervis.service.embedding.CodeEmbeddingService
import com.jervis.service.embedding.TextEmbeddingService
import com.jervis.service.gateway.EmbeddingGateway
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
    private val meetingIndexingService: MeetingIndexingService,
    private val gitHistoryIndexingService: GitHistoryIndexingService,
    private val dependencyIndexingService: DependencyIndexingService,
    private val classSummaryIndexingService: ClassSummaryIndexingService,
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

            try {
                val codeContents = mutableMapOf<Path, String>()

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
                                    codeContents[filePath] = content
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error reading code file: ${filePath.pathString}" }
                            }
                        } else {
                            skippedFiles++
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

            try {
                val textContents = mutableMapOf<Path, String>()

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
                                    textContents[filePath] = content
                                    processedFiles++
                                }
                            } catch (e: Exception) {
                                logger.warn(e) { "Error reading text file: ${filePath.pathString}" }
                            }
                        } else {
                            skippedFiles++
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
     * Index a single project with parallel execution of CODE indexing, TEXT indexing, and Joern analysis
     */
    suspend fun indexProject(project: ProjectDocument) =
        withContext(Dispatchers.IO) {
            logger.info { "Starting parallel indexing for project: ${project.name}" }

            if (project.path.isEmpty()) {
                logger.warn { "Project has no path configured: ${project.name}" }
                return@withContext
            }

            val pathString = project.path
            val projectPath = Paths.get(pathString)
            if (!Files.exists(projectPath)) {
                logger.warn { "Project path does not exist: $pathString" }
                return@withContext
            }

            logger.info { "Starting parallel indexing operations for project: ${project.name} at path: $pathString" }

            try {
                // Create .joern directory
                val joernDir = joernAnalysisService.setupJoernDirectory(projectPath)

                // Execute three indexing operations in parallel
                val indexingOperations =
                    listOf(
                        async { indexCodeFiles(project, projectPath) },
                        async { indexTextContent(project, projectPath) },
                        async {
                            val analysisResult =
                                joernAnalysisService.performJoernAnalysis(project, projectPath, joernDir)

                            // If Joern analysis was successful, index the results into RAG system
                            val indexingResult =
                                if (analysisResult.isAvailable && analysisResult.operationsFailed == 0) {
                                    try {
                                        indexJoernAnalysisResults(project, projectPath, joernDir)
                                    } catch (e: Exception) {
                                        logger.warn(e) { "Failed to index Joern analysis results for project: ${project.name}" }
                                        IndexingResult(0, 0, 1, "JOERN_INDEX")
                                    }
                                } else {
                                    logger.info { "Skipping Joern result indexing due to analysis failure for project: ${project.name}" }
                                    IndexingResult(0, 0, 0, "JOERN_INDEX")
                                }

                            // Combine analysis and indexing results
                            IndexingResult(
                                processedFiles = analysisResult.operationsCompleted + indexingResult.processedFiles,
                                skippedFiles = indexingResult.skippedFiles,
                                errorFiles = analysisResult.operationsFailed + indexingResult.errorFiles,
                                operationType = "JOERN+INDEX",
                            )
                        },
                    )

                val results = indexingOperations.awaitAll()

                val codeResult = results[0]
                val textResult = results[1]
                val joernResult = results[2]

                logger.info {
                    "Parallel indexing completed for project: ${project.name}\n" +
                        "CODE indexing - Processed: ${codeResult.processedFiles}, Skipped: ${codeResult.skippedFiles}, Errors: ${codeResult.errorFiles}\n" +
                        "TEXT indexing - Processed: ${textResult.processedFiles}, Skipped: ${textResult.skippedFiles}, Errors: ${textResult.errorFiles}\n" +
                        "Joern analysis - Status: ${if (joernResult.errorFiles == 0) "SUCCESS" else "FAILED (${joernResult.errorFiles} errors)"}"
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during parallel indexing for project: ${project.name}" }
            }
        }

    /**
     * Index all projects in the system
     */
    suspend fun indexAllProjects(projects: List<ProjectDocument>) {
        logger.info { "Starting indexing for all ${projects.size} projects" }

        var successCount = 0
        var errorCount = 0

        for (project in projects) {
            try {
                if (!project.isDisabled) {
                    indexProject(project)
                    successCount++
                } else {
                    logger.info { "Skipping disabled project: ${project.name}" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to index project: ${project.name}" }
                errorCount++
            }
        }

        logger.info { "All projects indexing completed. Success: $successCount, Errors: $errorCount" }
    }

    /**
     * Index projects for a specific client
     */
    suspend fun indexProjectsForClient(
        projects: List<ProjectDocument>,
        clientName: String,
    ) {
        logger.info { "Starting indexing for client '$clientName' with ${projects.size} projects" }

        var successCount = 0
        var errorCount = 0

        for (project in projects) {
            try {
                if (!project.isDisabled) {
                    indexProject(project)
                    successCount++
                } else {
                    logger.info { "Skipping disabled project: ${project.name}" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to index project: ${project.name}" }
                errorCount++
            }
        }

        logger.info { "Client '$clientName' projects indexing completed. Success: $successCount, Errors: $errorCount" }
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
                                    projectId = project.id!!,
                                    documentType = RagDocumentType.JOERN_ANALYSIS,
                                    ragSourceType = RagSourceType.ANALYSIS,
                                    pageContent = summary,
                                    source = "joern://${project.name}/${resultFile.fileName}",
                                    path = projectPath.relativize(resultFile).pathString,
                                    module = "joern-analysis",
                                    language = "analysis-report",
                                )

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
                val result = classSummaryIndexingService.indexClassSummaries(project, projectPath, joernDir)
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

    /**
     * Index meeting transcript from Whisper service
     */
    suspend fun indexMeetingTranscript(
        project: ProjectDocument,
        meetingId: String,
        transcript: String,
        meetingTitle: String,
        participantList: List<String> = emptyList(),
    ): Boolean =
        try {
            meetingIndexingService.indexMeetingFromWhisper(
                project,
                meetingId,
                transcript,
                meetingTitle,
                participantList,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to index meeting transcript: $meetingTitle" }
            false
        }

    /**
     * Index meeting notes manually
     */
    suspend fun indexMeetingNotes(
        project: ProjectDocument,
        meetingTitle: String,
        notes: String,
    ): Boolean =
        try {
            meetingIndexingService.indexMeetingNotes(project, meetingTitle, notes)
        } catch (e: Exception) {
            logger.error(e) { "Failed to index meeting notes: $meetingTitle" }
            false
        }

    /**
     * Comprehensive project indexing that includes all document types
     */
    suspend fun indexProjectComprehensive(project: ProjectDocument): IndexingResult =
        withContext(Dispatchers.Default) {
            try {
                logger.info { "Starting comprehensive indexing for project: ${project.name}" }
                val projectPath = Paths.get(project.path)

                if (!Files.exists(projectPath)) {
                    logger.error { "Project path does not exist: ${project.path}" }
                    return@withContext IndexingResult(0, 0, 1, "COMPREHENSIVE_INDEX")
                }

                val results = mutableListOf<IndexingResult>()

                // 1. Index code files
                logger.info { "Indexing code files for project: ${project.name}" }
                results.add(indexCodeFiles(project, projectPath))

                // 2. Index text content
                logger.info { "Indexing text content for project: ${project.name}" }
                results.add(indexTextContent(project, projectPath))

                // 3. Index git history
                logger.info { "Indexing git history for project: ${project.name}" }
                results.add(indexGitHistory(project, projectPath))

                // 4. Run Joern analysis and index results
                logger.info { "Running Joern analysis and indexing results for project: ${project.name}" }
                try {
                    val joernDir = joernAnalysisService.setupJoernDirectory(projectPath)
                    if (joernDir != null) {
                        // Perform Joern analysis
                        joernAnalysisService.performJoernAnalysis(project, projectPath, joernDir)

                        if (Files.exists(joernDir)) {
                            // Index Joern analysis results
                            results.add(indexJoernAnalysisResults(project, projectPath, joernDir))

                            // Index dependencies from Joern
                            results.add(indexDependencies(project, projectPath, joernDir))

                            // Index class summaries
                            results.add(indexClassSummaries(project, projectPath, joernDir))
                        }
                    } else {
                        logger.warn { "Joern analysis setup failed for project: ${project.name}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Joern analysis failed for project: ${project.name}" }
                }

                // Aggregate results
                val totalProcessed = results.sumOf { it.processedFiles }
                val totalSkipped = results.sumOf { it.skippedFiles }
                val totalErrors = results.sumOf { it.errorFiles }

                logger.info {
                    "Comprehensive indexing completed for project: ${project.name} - " +
                        "Total processed: $totalProcessed, Total skipped: $totalSkipped, Total errors: $totalErrors"
                }

                IndexingResult(totalProcessed, totalSkipped, totalErrors, "COMPREHENSIVE_INDEX")
            } catch (e: Exception) {
                logger.error(e) { "Comprehensive indexing failed for project: ${project.name}" }
                IndexingResult(0, 0, 1, "COMPREHENSIVE_INDEX")
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
