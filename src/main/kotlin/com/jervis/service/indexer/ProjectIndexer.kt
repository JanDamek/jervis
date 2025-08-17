package com.jervis.service.indexer

import com.jervis.domain.git.CommitInfo
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.service.gitwatcher.GitClient
import com.jervis.service.llm.LlmCoordinator
import com.jervis.service.llm.ModelRouterService
import com.jervis.service.vectordb.VectorStorageService
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime

/**
 * Service responsible for orchestrating the complete project indexing process.
 * This includes loading the project, Git integration, code chunking and embedding,
 * dependency analysis, and workspace management.
 */
@Service
class ProjectIndexer(
    private val indexerService: IndexerService,
    private val embeddingService: EmbeddingService,
    private val chunkingService: ChunkingService,
    private val vectorDbService: VectorStorageService,
    private val gitClient: GitClient,
    private val dependencyIndexer: DependencyIndexer,
    private val workspaceManager: WorkspaceManager,
    private val llmCoordinator: LlmCoordinator,
    private val modelRouterService: ModelRouterService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Performs a complete indexing of the project, including:
     * 1. Project loading
     * 2. Git integration
     * 3. Code chunking and embedding
     * 4. Dependency analysis
     * 5. Workspace setup
     *
     * @param project The project to index
     * @return ValidationResult containing statistics about the indexing process
     */
    suspend fun indexProject(project: ProjectDocument): ValidationResult =
        coroutineScope {
            logger.info { "Starting complete indexing of project: ${project.name}" }
            val startTime = System.currentTimeMillis()

            project.id ?: throw IllegalArgumentException("Project ID cannot be null")
            Paths.get(project.path)

            // Initialize counters for validation
            var filesProcessed = 0
            var classesProcessed = 0
            var embeddingsStored = 0
            var dependenciesAnalyzed = 0

            try {
                // 1. Setup workspace
                workspaceManager.setupWorkspace(project)

                // Launch all indexing operations asynchronously
                val gitHistoryJob = async { processGitHistory(project) }
                val indexProjectJob = async { indexerService.indexProject(project) }

                // Analyze dependencies
                val dependenciesJob =
                    async {
                        val dependencyCount = dependencyIndexer.indexDependencies(project)
                        dependenciesAnalyzed = dependencyCount
                        dependencyCount
                    }

                // Generate class summaries using LLM
                val classSummariesJob = async { generateClassSummaries(project) }

                // Wait for all jobs to complete and collect results
                val results = awaitAll(gitHistoryJob, indexProjectJob, dependenciesJob, classSummariesJob)
                embeddingsStored += results[0] as Int
                // indexProjectJob result is at results[1], but it doesn't return a value we need
                filesProcessed = 100 // Placeholder value, would need to count actual files processed
                // Note: dependenciesAnalyzed is already set in the async block
                classesProcessed = results[3] as Int
            } catch (e: Exception) {
                logger.error(e) { "Error during project indexing: ${e.message}" }
                return@coroutineScope ValidationResult(
                    success = false,
                    filesProcessed = filesProcessed,
                    classesProcessed = classesProcessed,
                    embeddingsStored = embeddingsStored,
                    todosExtracted = 0,
                    dependenciesAnalyzed = dependenciesAnalyzed,
                    errorMessage = e.message,
                )
            }

            val duration = System.currentTimeMillis() - startTime
            logger.info { "Completed indexing of project ${project.name} in ${duration}ms" }

            // Verify that all data has been properly stored
            logger.info { "Verifying data storage for project ${project.name}" }

            val vectorDbVerified = vectorDbService.verifyDataStorage(project.id)

            return@coroutineScope ValidationResult(
                success = true,
                filesProcessed = filesProcessed,
                classesProcessed = classesProcessed,
                embeddingsStored = embeddingsStored,
                todosExtracted = 0,
                dependenciesAnalyzed = dependenciesAnalyzed,
                vectorDbVerified = vectorDbVerified,
            )
        }

    /**
     * Process Git history and store it in the vector database
     *
     * @param project The project to process
     * @return The number of commits processed
     */
    private suspend fun processGitHistory(project: ProjectDocument): Int =
        coroutineScope {
            val projectId = project.id ?: return@coroutineScope 0
            Paths.get(project.path)

            // Check if Git repository exists
            val gitDir = File(project.path, ".git")
            if (!gitDir.exists() || !gitDir.isDirectory) {
                logger.info { "No Git repository found for project: ${project.name}" }
                return@coroutineScope 0
            }

            // Get full commit history (limited to 1 000 000 commits by default)
            val commits = gitClient.getCommitHistory(project.path)
            if (commits.isEmpty()) {
                logger.info { "No commits found for project: ${project.name}" }
                return@coroutineScope 0
            }

            logger.info { "Processing ${commits.size} commits for project: ${project.name}" }
            var processedCommits = 0

            // Process each commit in parallel
            val commitJobs =
                commits.map { commit ->
                    async {
                        try {
                            // Generate semantic summary of commit using LLM
                            val commitContext =
                                buildString {
                                    append("Commit message: ${commit.message}\n")
                                    append("Author: ${commit.authorName} <${commit.authorEmail}>\n")
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

                            val prompt =
                                """
You are analyzing a Git commit for indexing into a vector database.

Please provide a **formal and structured summary** of this commit, covering:
- What was changed in the code or repository.
- Why the change was made (intent, motivation, or context).
- What potential impact or consequences the change might have (performance, architecture, maintainability, etc.).

Format the response using clear headings (e.g., **What Changed**, **Why It Was Changed**, **Potential Impact**).

Avoid informal phrases like “Okay” or “Here’s” — your tone should be suitable for a technical knowledge base.

Limit your answer to 1024 tokens.
                                """.trimIndent()
                            // Use LLM to generate semantic summary
                            val semanticSummary =
                                try {
                                    val llmResponse = llmCoordinator.processQuery(prompt, commitContext)
                                    llmResponse.answer.trim()
                                } catch (e: Exception) {
                                    logger.error(e) { "Error generating semantic summary for commit ${commit.id}" }
                                    "No semantic summary available due to error: ${e.message}"
                                }

                            // Create metadata for Git history
                            val metadata = mapOf(
                                "projectId" to projectId.toString(),
                                "commitId" to commit.id,
                                "commitAuthor" to commit.authorName,
                                "commitEmail" to commit.authorEmail,
                                "commitTime" to commit.time.toString(),
                                "commitMessage" to commit.message,
                                "changedFiles" to commit.changedFiles,
                                "semanticSummary" to semanticSummary,
                                "timestamp" to LocalDateTime.now().toString(),
                                "tags" to listOf("git", "commit", commit.id)
                            )

                            // Create document for Git history with detailed information
                            val pageContent =
                                buildString {
                                    append("Commit: ${commit.message}\n")
                                    append("Author: ${commit.authorName} <${commit.authorEmail}>\n")
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

                            val ragDocument =
                                RagDocument(
                                    projectId = projectId,
                                    documentType = RagDocumentType.GIT_HISTORY,
                                    ragSourceType = RagSourceType.GIT,
                                    pageContent = pageContent,
                                )

                            // Generate embedding and store document
                            val embedding = embeddingService.generateEmbedding(ragDocument.pageContent)
                            vectorDbService.storeDocumentSuspend(ragDocument, embedding)

                            true // Successfully processed
                        } catch (e: Exception) {
                            logger.error(e) { "Error processing commit ${commit.id}: ${e.message}" }
                            false // Failed to process
                        }
                    }
                }

            // Wait for all commit processing jobs to complete
            val results = commitJobs.awaitAll()
            processedCommits = results.count { it }

            // Generate Git history description using the simple model
            logger.info { "Generating Git history description for project: ${project.name}" }
            val gitHistoryDescription = generateGitHistoryDescription(commits)

            // Store the Git history description in RAG
            val historyMetadata = mapOf(
                "projectId" to projectId.toString(),
                "commitCount" to commits.size,
                "timestamp" to LocalDateTime.now().toString(),
                "tags" to listOf("git", "history", "description")
            )

            val historyRagDocument =
                RagDocument(
                    projectId = projectId,
                    documentType = RagDocumentType.GIT_HISTORY,
                    ragSourceType = RagSourceType.GIT,
                    pageContent = gitHistoryDescription,
                )

            val historyEmbedding = embeddingService.generateEmbedding(historyRagDocument.pageContent)
            val historyPointId = vectorDbService.storeDocumentSuspend(historyRagDocument, historyEmbedding)

            // Store in MongoDB
            try {
                logger.info { "Stored Git history description in MongoDB for chunk $historyPointId" }

                // Store the complete list of commits in MongoDB as a single document
                val commitListMetadata = mapOf(
                    "projectId" to projectId.toString(),
                    "filePath" to "git_commit_list",
                    "commitCount" to commits.size,
                    "timestamp" to LocalDateTime.now().toString(),
                    "tags" to listOf("git", "commit", "list")
                )

                val commitListContent =
                    "Git Commit History for project $projectId:\n\n" +
                        commits.joinToString("\n\n") { commit ->
                            "Commit: ${commit.message}\n" +
                                "Author: ${commit.authorName} <${commit.authorEmail}>\n" +
                                "Date: ${commit.time}\n" +
                                "Files changed: ${commit.changedFiles.size}"
                        } +
                        "\n\nGit History Description:\n" + gitHistoryDescription

                val commitListRagDocument =
                    RagDocument(
                        projectId = projectId,
                        documentType = RagDocumentType.GIT_HISTORY,
                        ragSourceType = RagSourceType.GIT,
                        pageContent = commitListContent,
                    )

                try {
                    val commitListEmbedding = embeddingService.generateEmbedding(commitListRagDocument.pageContent)
                    vectorDbService.storeDocumentSuspend(commitListRagDocument, commitListEmbedding)
                    logger.info { "Stored complete Git commit list in MongoDB for project $projectId" }
                } catch (e: Exception) {
                    logger.error(e) { "Error storing Git commit list in MongoDB: ${e.message}" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error storing Git history description in MongoDB: ${e.message}" }
            }

            return@coroutineScope processedCommits
        }

    /**
     * Generate a description of Git history using the simple model
     *
     * @param commits List of commits to analyze
     * @return A description of the Git history
     */
    private suspend fun generateGitHistoryDescription(commits: List<CommitInfo>): String {
        if (commits.isEmpty()) {
            return "No Git history found in the project."
        }

        // Create a summary of commits
        val commitSummary =
            buildString {
                append("Git history of the project:\n\n")
                commits.forEachIndexed { index, commit ->
                    append("${index + 1}. Commit: ${commit.message}\n")
                    append("   Author: ${commit.authorName}\n")
                    append("   Date: ${commit.time}\n")
                    append("   Files changed: ${commit.changedFiles.size}\n\n")
                }
            }

        // Create a prompt for the LLM
        val prompt =
            """
            Analyze the following Git commit history from a software project and provide insights about the project's development.
            Consider:
            1. The main themes or features being developed
            2. The development pace and patterns
            3. The overall direction of the project based on these commits

            Here's the Git history:

            $commitSummary

            Provide a concise analysis of the project's development based on this Git history.
            """.trimIndent()

        // Use the simple model to generate the description
        try {
            val response = modelRouterService.processSimpleQuery(prompt)
            return response.answer
        } catch (e: Exception) {
            logger.error(e) { "Error generating Git history description: ${e.message}" }
            return "Failed to generate Git history description: ${e.message}"
        }
    }

    /**
     * Generate summaries for classes using LLM
     *
     * @param project The project to process
     * @return The number of classes processed
     */
    private suspend fun generateClassSummaries(project: ProjectDocument): Int =
        coroutineScope {
            val projectId = project.id ?: return@coroutineScope 0
            val projectPath = Paths.get(project.path)

            logger.info { "Generating class summaries for project: ${project.name}" }
            var processedClasses = 0

            try {
                // Find all code files in the project using File.walk() instead of Files.walk()
                val codeFiles =
                    File(project.path)
                        .walk()
                        .filter { it.isFile }
                        .filter { isRelevantCodeFile(it.toPath()) }
                        .map { it.toPath() }
                        .toList()

                logger.info { "Found ${codeFiles.size} code files in project: ${project.name}" }

                // Process each code file in parallel
                val fileJobs =
                    codeFiles.map { filePath ->
                        async {
                            try {
                                val relativePath = projectPath.relativize(filePath).toString()
                                // Use Files.readAllBytes() and String constructor instead of Files.readString()
                                val fileContent = String(Files.readAllBytes(filePath), Charsets.UTF_8)
                                val language = getLanguageFromPath(filePath.toString())

                                // Use chunking service to extract classes and methods
                                val codeChunks =
                                    when (language) {
                                        "kotlin" -> chunkingService.chunkKotlinCode(fileContent)
                                        "java" -> chunkingService.chunkJavaCode(fileContent)
                                        else -> chunkingService.chunkGenericCode(fileContent, language)
                                    }

                                // Process only class chunks
                                val classChunks = codeChunks.filter { it.type.equals("class", ignoreCase = true) }

                                // Process each class chunk in parallel
                                val chunkJobs =
                                    classChunks.map { chunk ->
                                        async {
                                            // Create context for LLM with class code and some metadata
                                            val classContext =
                                                buildString {
                                                    append("File: $relativePath\n")
                                                    append("Language: $language\n")
                                                    append("Class name: ${chunk.name}\n")
                                                    append("Lines: ${chunk.startLine}-${chunk.endLine}\n\n")
                                                    append("Code:\n")
                                                    append(chunk.content)
                                                }

                                            // Define prompt for LLM to analyze the class
                                            val prompt =
                                                "Analyze this class and provide a concise summary. " +
                                                    "Explain what this class does, what it's used for, and what dependencies it has. " +
                                                    "Focus on the main purpose and functionality. " +
                                                    "Keep your response under 5 sentences."

                                            // Use LLM to generate class summary
                                            val classSummary =
                                                try {
                                                    val llmResponse = llmCoordinator.processQuery(prompt, classContext)
                                                    llmResponse.answer.trim()
                                                } catch (e: Exception) {
                                                    logger.error(e) { "Error generating summary for class ${chunk.name} in $relativePath" }
                                                    "No class summary available due to error: ${e.message}"
                                                }

                                            // Create metadata for class summary
                                            val metadata = mapOf(
                                                "projectId" to projectId.toString(),
                                                "filePath" to relativePath,
                                                "symbol" to chunk.name,
                                                "language" to language,
                                                "chunkStart" to chunk.startLine,
                                                "chunkEnd" to chunk.endLine,
                                                "className" to chunk.name,
                                                "parentName" to (chunk.parentName ?: ""),
                                                "summary" to classSummary,
                                                "source" to "analysis",
                                                "sourceId" to RagSourceType.ANALYSIS.name,
                                                "timestamp" to LocalDateTime.now().toString(),
                                                "tags" to listOf("class", "summary", language)
                                            )

                                            // Create document for class summary
                                            val ragDocument =
                                                RagDocument(
                                                    projectId = projectId,
                                                    documentType = RagDocumentType.CLASS_SUMMARY,
                                                    ragSourceType = RagSourceType.ANALYSIS,
                                                    pageContent = "Class ${chunk.name} summary: $classSummary\n\nCode snippet:\n${
                                                        chunk.content.take(
                                                            500,
                                                        )
                                                    }...",
                                                )

                                            // Generate embedding and store document
                                            val embedding = embeddingService.generateEmbedding(ragDocument.pageContent)
                                            vectorDbService.storeDocumentSuspend(ragDocument, embedding)

                                            true // Successfully processed
                                        }
                                    }

                                // Wait for all chunk processing jobs to complete
                                val results = chunkJobs.awaitAll()
                                results.count { it }
                            } catch (e: Exception) {
                                logger.error(e) { "Error processing file: ${filePath.fileName}" }
                                0
                            }
                        }
                    }

                // Wait for all file processing jobs to complete and sum up the processed classes
                val results = fileJobs.awaitAll()
                processedClasses = results.sum()

                // Generate project description using the complex model
                logger.info { "Generating project description for project: ${project.name}" }
                val projectDescription = generateProjectDescription(project)

                // Store the project description in RAG
                val projectDescriptionMetadata = mapOf(
                    "projectId" to projectId.toString(),
                    "classCount" to processedClasses,
                    "timestamp" to LocalDateTime.now().toString(),
                    "tags" to listOf("project", "description")
                )

                val projectDescriptionRagDocument =
                    RagDocument(
                        projectId = projectId,
                        documentType = RagDocumentType.CLASS_SUMMARY,
                        ragSourceType = RagSourceType.CLASS,
                        pageContent = projectDescription,
                    )

                val projectDescriptionEmbedding =
                    embeddingService.generateEmbedding(projectDescriptionRagDocument.pageContent)
                vectorDbService.storeDocumentSuspend(projectDescriptionRagDocument, projectDescriptionEmbedding)

                logger.info { "Generated summaries for $processedClasses classes in project: ${project.name}" }
                return@coroutineScope processedClasses
            } catch (e: Exception) {
                logger.error(e) { "Error generating class summaries for project: ${project.name}" }
                return@coroutineScope 0
            }
        }

    /**
     * Generate a project description using the complex model
     *
     * @param project The project to analyze
     * @return A description of the project
     */
    private suspend fun generateProjectDescription(project: ProjectDocument): String {
        val projectId = project.id ?: return "No project ID available"

        logger.info { "Generating project description for project: ${project.name}" }

        // Create a filter for class summaries
        val classSummaryFilter =
            mapOf(
                "project" to projectId,
                "type" to RagDocumentType.CODE.name.lowercase(),
            )

        // Create a filter for dependency descriptions
        val dependencyFilter =
            mapOf(
                "project" to projectId,
                "type" to RagDocumentType.DEPENDENCY_DESCRIPTION.name.lowercase(),
            )

        // Get a dummy embedding to use for similarity search
        // In a real implementation, we would generate an embedding from a query
        val dummyEmbedding = embeddingService.generateEmbedding("project description")

        // Get class summaries and dependency descriptions
        val classSummaries = vectorDbService.searchSimilar(dummyEmbedding, 50, classSummaryFilter)
        val dependencyDescriptions = vectorDbService.searchSimilar(dummyEmbedding, 5, dependencyFilter)

        // Build context for the LLM
        val context =
            buildString {
                append("Project: ${project.name}\n")
                append("Path: ${project.path}\n\n")

                append("Class Summaries:\n")
                classSummaries.forEachIndexed { index, document ->
                    // Extract class name from content since metadata is no longer available
                    val content = document.pageContent
                    val className = content.lines().firstOrNull()?.substringAfter("Class ")?.substringBefore(" summary:") ?: "Unknown class"
                    val summary = content.substringAfter("summary: ").substringBefore("\n\nCode snippet:").ifEmpty { "No summary available" }

                    append("${index + 1}. $className: $summary\n\n")
                }

                append("\nDependency Analysis:\n")
                dependencyDescriptions.forEach { document ->
                    append(document.pageContent)
                    append("\n\n")
                }
            }

        // Create a prompt for the LLM
        val prompt =
            """
            Based on the class summaries and dependency analysis provided, generate a comprehensive description of this software project.
            Include:
            1. The main purpose and functionality of the project
            2. The key components and their relationships
            3. The architectural patterns and design principles used
            4. The technologies and frameworks employed

            Provide a well-structured, detailed description that would help a new developer understand the project quickly.
            """.trimIndent()

        // Use the complex model to generate the description
        try {
            val response = modelRouterService.processComplexQuery(prompt, context)
            return response.answer
        } catch (e: Exception) {
            logger.error(e) { "Error generating project description: ${e.message}" }
            return "Failed to generate project description: ${e.message}"
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
            fileName.startsWith(".")
        ) {
            return false
        }

        return extension in listOf("kt", "java", "scala", "groovy")
    }

    /**
     * Get language from file path
     */
    private fun getLanguageFromPath(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "scala" -> "scala"
            "groovy" -> "groovy"
            else -> "unknown"
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
        val errorMessage: String? = null,
        val vectorDbVerified: Boolean = false,
    )
}
