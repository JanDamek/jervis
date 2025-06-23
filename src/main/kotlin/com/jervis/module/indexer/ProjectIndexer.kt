package com.jervis.module.indexer

import com.jervis.entity.Project
import com.jervis.module.gitwatcher.GitClient
import com.jervis.module.llmcoordinator.LlmCoordinator
import com.jervis.module.vectordb.VectorDbService
import com.jervis.rag.Document
import com.jervis.rag.DocumentType
import com.jervis.rag.RagMetadata
import com.jervis.rag.SourceType
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Service responsible for orchestrating the complete project indexing process.
 * This includes loading the project, Git integration, code chunking and embedding,
 * dependency analysis, TODO extraction, and workspace management.
 */
@Service
class ProjectIndexer(
    private val indexerService: IndexerService,
    private val embeddingService: EmbeddingService,
    private val chunkingService: ChunkingService,
    private val vectorDbService: VectorDbService,
    private val gitClient: GitClient,
    private val dependencyAnalyzer: DependencyAnalyzer,
    private val todoExtractor: TodoExtractor,
    private val workspaceManager: WorkspaceManager,
    private val llmCoordinator: LlmCoordinator
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Performs a complete indexing of the project, including:
     * 1. Project loading
     * 2. Git integration
     * 3. Code chunking and embedding
     * 4. Dependency analysis
     * 5. TODO extraction
     * 6. Workspace setup
     *
     * @param project The project to index
     * @return ValidationResult containing statistics about the indexing process
     */
    fun indexProject(project: Project): ValidationResult {
        logger.info { "Starting complete indexing of project: ${project.name}" }
        val startTime = System.currentTimeMillis()

        val projectId = project.id ?: throw IllegalArgumentException("Project ID cannot be null")
        val projectPath = Paths.get(project.path)

        // Initialize counters for validation
        var filesProcessed = 0
        var classesProcessed = 0
        var embeddingsStored = 0
        var todosExtracted = 0
        var dependenciesAnalyzed = 0

        try {
            // 1. Setup workspace
            workspaceManager.setupWorkspace(project)

            // 2. Process Git history
            val gitHistory = processGitHistory(project)
            embeddingsStored += gitHistory

            // 3. Index code files (already handled by indexerService)
            indexerService.indexProject(project)
            filesProcessed = 100 // Placeholder value, would need to count actual files processed

            // 4. Analyze dependencies
            val dependencies = dependencyAnalyzer.analyzeDependencies(project)
            dependenciesAnalyzed = dependencies.size
            storeDependencies(project, dependencies)

            // 5. Extract TODOs
            val todos = todoExtractor.extractTodos(project)
            todosExtracted = todos.size
            storeTodos(project, todos)

            // 6. Generate class summaries using LLM
            classesProcessed = generateClassSummaries(project)

        } catch (e: Exception) {
            logger.error(e) { "Error during project indexing: ${e.message}" }
            return ValidationResult(
                success = false,
                filesProcessed = filesProcessed,
                classesProcessed = classesProcessed,
                embeddingsStored = embeddingsStored,
                todosExtracted = todosExtracted,
                dependenciesAnalyzed = dependenciesAnalyzed,
                errorMessage = e.message
            )
        }

        val duration = System.currentTimeMillis() - startTime
        logger.info { "Completed indexing of project ${project.name} in ${duration}ms" }

        return ValidationResult(
            success = true,
            filesProcessed = filesProcessed,
            classesProcessed = classesProcessed,
            embeddingsStored = embeddingsStored,
            todosExtracted = todosExtracted,
            dependenciesAnalyzed = dependenciesAnalyzed
        )
    }

    /**
     * Process Git history and store it in the vector database
     * 
     * @param project The project to process
     * @return The number of commits processed
     */
    private fun processGitHistory(project: Project): Int {
        val projectId = project.id ?: return 0
        val projectPath = Paths.get(project.path)

        // Check if Git repository exists
        val gitDir = File(project.path, ".git")
        if (!gitDir.exists() || !gitDir.isDirectory) {
            logger.info { "No Git repository found for project: ${project.name}" }
            return 0
        }

        // Get full commit history (limited to 100 commits by default)
        val commits = gitClient.getCommitHistory(project.path)
        if (commits.isEmpty()) {
            logger.info { "No commits found for project: ${project.name}" }
            return 0
        }

        logger.info { "Processing ${commits.size} commits for project: ${project.name}" }
        var processedCommits = 0

        // Process each commit
        commits.forEach { commit ->
            try {
                // Generate semantic summary of commit using LLM
                val commitContext = buildString {
                    append("Commit message: ${commit.message}\n")
                    append("Author: ${commit.author}\n")
                    append("Date: ${commit.time}\n")
                    append("Files changed: ${commit.changedFiles.size}\n")
                    if (commit.changedFiles.isNotEmpty()) {
                        append("Changed files:\n")
                        commit.changedFiles.take(10).forEach { file ->
                            append("- $file\n")
                        }
                        if (commit.changedFiles.size > 10) {
                            append("- ... and ${commit.changedFiles.size - 10} more files\n")
                        }
                    }
                }

                val prompt = "Analyze this Git commit and explain what was the intention of this change. " +
                        "Focus on what was changed, why it was changed, and what impact it might have. " +
                        "Keep your response concise (max 3 sentences)."

                // Use LLM to generate semantic summary
                val semanticSummary = try {
                    val llmResponse = llmCoordinator.processQueryBlocking(prompt, commitContext)
                    llmResponse.answer.trim()
                } catch (e: Exception) {
                    logger.error(e) { "Error generating semantic summary for commit ${commit.id}" }
                    "No semantic summary available due to error: ${e.message}"
                }

                // Create metadata for Git history
                val metadata = RagMetadata(
                    type = DocumentType.GIT_HISTORY,
                    project = projectId.toInt(),
                    source = "git",
                    sourceId = SourceType.GIT,
                    filePath = ".git/COMMIT_EDITMSG",
                    extra = mapOf(
                        "commit_id" to commit.id,
                        "commit_author" to commit.author,
                        "commit_time" to commit.time.toString(),
                        "commit_message" to commit.message,
                        "changed_files" to commit.changedFiles,
                        "changed_files_count" to commit.changedFiles.size,
                        "semantic_summary" to semanticSummary
                    )
                )

                // Create document for Git history with detailed information
                val pageContent = buildString {
                    append("Commit: ${commit.message}\n")
                    append("Author: ${commit.author}\n")
                    append("Date: ${commit.time}\n")
                    append("Files changed: ${commit.changedFiles.size}\n")
                    if (commit.changedFiles.isNotEmpty()) {
                        append("Changed files:\n")
                        commit.changedFiles.take(10).forEach { file ->
                            append("- $file\n")
                        }
                        if (commit.changedFiles.size > 10) {
                            append("- ... and ${commit.changedFiles.size - 10} more files\n")
                        }
                    }
                    append("\nSemantic summary: $semanticSummary")
                }

                val document = Document(
                    pageContent = pageContent,
                    metadata = metadata
                )

                // Generate embedding and store document
                val embedding = embeddingService.generateEmbedding(document.pageContent)
                vectorDbService.storeDocument(document, embedding)
                processedCommits++
            } catch (e: Exception) {
                logger.error(e) { "Error processing commit ${commit.id}: ${e.message}" }
            }
        }

        return processedCommits
    }

    /**
     * Store dependencies in the vector database
     */
    private fun storeDependencies(project: Project, dependencies: List<Dependency>) {
        val projectId = project.id ?: return

        dependencies.forEach { dependency ->
            val metadata = RagMetadata(
                type = DocumentType.DEPENDENCY,
                project = projectId.toInt(),
                source = "analysis",
                sourceId = SourceType.ANALYSIS,
                filePath = dependency.sourceClass,
                extra = mapOf(
                    "class_name" to dependency.sourceClass.substringAfterLast('.'),
                    "target_class" to dependency.targetClass,
                    "dependency_type" to dependency.type.name
                )
            )

            val content = "Class ${dependency.sourceClass} depends on ${dependency.targetClass}"
            val document = Document(
                pageContent = content,
                metadata = metadata
            )

            val embedding = embeddingService.generateEmbedding(document.pageContent)
            vectorDbService.storeDocument(document, embedding)
        }
    }

    /**
     * Store TODOs in the vector database
     */
    private fun storeTodos(project: Project, todos: List<Todo>) {
        val projectId = project.id ?: return

        todos.forEach { todo ->
            val metadata = RagMetadata(
                type = DocumentType.TODO,
                project = projectId.toInt(),
                source = "analysis",
                sourceId = SourceType.ANALYSIS,
                filePath = todo.filePath,
                extra = mapOf(
                    "file_name" to todo.filePath.substringAfterLast('/'),
                    "file_extension" to todo.filePath.substringAfterLast('.', ""),
                    "line_number" to todo.lineNumber,
                    "todo_type" to todo.type.name
                )
            )

            val document = Document(
                pageContent = todo.content,
                metadata = metadata
            )

            val embedding = embeddingService.generateEmbedding(document.pageContent)
            vectorDbService.storeDocument(document, embedding)
        }
    }

    /**
     * Generate summaries for classes using LLM
     * 
     * @param project The project to process
     * @return The number of classes processed
     */
    private fun generateClassSummaries(project: Project): Int {
        val projectId = project.id ?: return 0
        val projectPath = Paths.get(project.path)

        logger.info { "Generating class summaries for project: ${project.name}" }
        var processedClasses = 0

        try {
            // Find all code files in the project using File.walk() instead of Files.walk()
            val codeFiles = File(project.path).walk()
                .filter { it.isFile }
                .filter { isRelevantCodeFile(it.toPath()) }
                .map { it.toPath() }
                .toList()

            logger.info { "Found ${codeFiles.size} code files in project: ${project.name}" }

            // Process each code file
            codeFiles.forEach { filePath ->
                try {
                    val relativePath = projectPath.relativize(filePath).toString()
                    // Use Files.readAllBytes() and String constructor instead of Files.readString()
                    val fileContent = String(Files.readAllBytes(filePath), Charsets.UTF_8)
                    val language = getLanguageFromPath(filePath.toString())

                    // Use chunking service to extract classes and methods
                    val codeChunks = when (language) {
                        "kotlin" -> chunkingService.chunkKotlinCode(fileContent)
                        "java" -> chunkingService.chunkJavaCode(fileContent)
                        else -> chunkingService.chunkGenericCode(fileContent, language)
                    }

                    // Process only class chunks
                    val classChunks = codeChunks.filter { it.type.equals("class", ignoreCase = true) }

                    classChunks.forEach { chunk ->
                        // Create context for LLM with class code and some metadata
                        val classContext = buildString {
                            append("File: $relativePath\n")
                            append("Language: $language\n")
                            append("Class name: ${chunk.name}\n")
                            append("Lines: ${chunk.startLine}-${chunk.endLine}\n\n")
                            append("Code:\n")
                            append(chunk.content)
                        }

                        // Define prompt for LLM to analyze the class
                        val prompt = "Analyze this class and provide a concise summary. " +
                                "Explain what this class does, what it's used for, and what dependencies it has. " +
                                "Focus on the main purpose and functionality. " +
                                "Keep your response under 5 sentences."

                        // Use LLM to generate class summary
                        val classSummary = try {
                            val llmResponse = llmCoordinator.processQueryBlocking(prompt, classContext)
                            llmResponse.answer.trim()
                        } catch (e: Exception) {
                            logger.error(e) { "Error generating summary for class ${chunk.name} in $relativePath" }
                            "No class summary available due to error: ${e.message}"
                        }

                        // Create metadata for class summary
                        val metadata = RagMetadata(
                            type = DocumentType.CODE,
                            project = projectId.toInt(),
                            source = "analysis",
                            sourceId = SourceType.ANALYSIS,
                            filePath = relativePath,
                            symbol = chunk.name,
                            language = language,
                            chunkStart = chunk.startLine,
                            chunkEnd = chunk.endLine,
                            extra = mapOf(
                                "class_name" to chunk.name,
                                "parent_name" to (chunk.parentName ?: ""),
                                "summary" to classSummary
                            )
                        )

                        // Create document for class summary
                        val document = Document(
                            pageContent = "Class ${chunk.name} summary: $classSummary\n\nCode snippet:\n${chunk.content.take(500)}...",
                            metadata = metadata
                        )

                        // Generate embedding and store document
                        val embedding = embeddingService.generateEmbedding(document.pageContent)
                        vectorDbService.storeDocument(document, embedding)
                        processedClasses++
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Error processing file: ${filePath.fileName}" }
                }
            }

            logger.info { "Generated summaries for $processedClasses classes in project: ${project.name}" }
            return processedClasses
        } catch (e: Exception) {
            logger.error(e) { "Error generating class summaries for project: ${project.name}" }
            return 0
        }
    }

    /**
     * Check if a file is a relevant code file for analysis
     */
    private fun isRelevantCodeFile(path: Path): Boolean {
        val fileName = path.fileName.toString().lowercase()
        val extension = fileName.substringAfterLast('.', "")

        // Skip test files, generated files, and non-code files
        if (path.toString().contains("/test/") || 
            path.toString().contains("/generated/") ||
            fileName.contains("test") ||
            fileName.startsWith(".")) {
            return false
        }

        return extension in listOf("kt", "java", "scala", "groovy")
    }

    /**
     * Get language from file path
     */
    private fun getLanguageFromPath(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "scala" -> "scala"
            "groovy" -> "groovy"
            else -> "unknown"
        }
    }

    /**
     * Result of the validation process after indexing
     */
    data class ValidationResult(
        val success: Boolean,
        val filesProcessed: Int,
        val classesProcessed: Int,
        val embeddingsStored: Int,
        val todosExtracted: Int,
        val dependenciesAnalyzed: Int,
        val errorMessage: String? = null
    )
}
