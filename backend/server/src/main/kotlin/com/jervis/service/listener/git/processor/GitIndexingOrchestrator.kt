package com.jervis.service.listener.git.processor

import com.jervis.entity.ProjectDocument
import com.jervis.service.git.state.GitCommitStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.nio.file.Path

/**
 * Orchestrates Git indexing workflow by coordinating all processors.
 * This is the main entry point for Git indexing after repository sync.
 *
 * Workflow:
 * 1. GitCommitMetadataIndexer - indexes commit metadata (author, message, files, stats)
 * 2. GitDiffCodeIndexer - indexes actual code changes (CODE embeddings)
 * 3. GitTaskCreator - creates pending tasks for commit analysis
 *
 * Supports both:
 * - Standalone project repositories
 * - Client mono-repositories (shared across multiple projects)
 *
 * Parallel processing strategy:
 * - Metadata indexing runs in parallel with code indexing (using coroutineScope + async)
 * - This reduces total indexing time significantly
 *
 * This follows the email indexing pattern where orchestrator coordinates
 * multiple specialized processors instead of one monolithic service.
 */
@Service
class GitIndexingOrchestrator(
    private val commitMetadataIndexer: GitCommitMetadataIndexer,
    private val diffCodeIndexer: GitDiffCodeIndexer,
    private val taskCreator: GitTaskCreator,
    private val stateManager: GitCommitStateManager,
    private val fileStructureAnalyzer: FileStructureAnalyzer,
    private val projectDescriptionUpdater: ProjectDescriptionUpdater,
    private val gitBranchAnalysisService: com.jervis.service.listener.git.branch.GitBranchAnalysisService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Complete indexing result combining all processors
     */
    data class GitIndexingResult(
        val commitMetadataResult: GitCommitMetadataIndexer.GitHistoryIndexingResult,
        val codeIndexingResult: GitDiffCodeIndexer.CodeIndexingResult,
        val tasksCreated: Int,
        val fileAnalysisTasksCreated: Int,
        val descriptionUpdateTaskCreated: Boolean,
        val errors: Int,
    )

    /**
     * Orchestrate complete Git indexing for a project.
     * This is the main entry point called by GitPollingScheduler.
     *
     * @param project Project to index
     * @param projectPath Path to Git repository
     * @param branch Current branch name
     * @param maxCommits Maximum number of commits to process (default 1000)
     */
    suspend fun orchestrateGitIndexing(
        project: ProjectDocument,
        projectPath: Path,
        branch: String,
        maxCommits: Int = 1000,
    ): GitIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info {
                "Starting Git indexing orchestration for project: ${project.name}, branch: $branch"
            }

            var totalErrors = 0

            // Step 1: Index commit metadata (TEXT embeddings)
            logger.info { "Step 1: Indexing commit metadata..." }
            val commitMetadataResult =
                try {
                    commitMetadataIndexer.indexGitHistory(project, projectPath, branch, maxCommits)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index commit metadata" }
                    totalErrors++
                    GitCommitMetadataIndexer.GitHistoryIndexingResult(0, 0, 1)
                }

            logger.info {
                "Commit metadata indexed: processed=${commitMetadataResult.processedCommits}, " +
                    "errors=${commitMetadataResult.errorCommits}"
            }

            // Step 2: Index code diffs (CODE embeddings)
            logger.info { "Step 2: Indexing code diffs..." }
            val codeIndexingResult =
                try {
                    indexCodeDiffsForNewCommits(project, projectPath, branch)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index code diffs" }
                    totalErrors++
                    GitDiffCodeIndexer.CodeIndexingResult(0, 0, 1)
                }

            logger.info {
                "Code diffs indexed: files=${codeIndexingResult.indexedFiles}, " +
                    "chunks=${codeIndexingResult.indexedChunks}, errors=${codeIndexingResult.errorFiles}"
            }

            // Step 3: Create pending tasks for commit analysis, file analysis, and description updates
            logger.info { "Step 3: Creating pending tasks for analysis..." }
            val taskResult =
                try {
                    createAnalysisTasksForNewCommits(project, projectPath, branch)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create analysis tasks" }
                    totalErrors++
                    TaskCreationResult(0, 0, false)
                }

            logger.info {
                "Created tasks: ${taskResult.commitTasks} commit analysis, " +
                    "${taskResult.fileAnalysisTasks} file structure, " +
                    "description update=${taskResult.descriptionTaskCreated}"
            }

            // Step 4: Branch-aware summaries and RAG embedding
            logger.info { "Step 4: Indexing branch summaries for ${project.name}..." }
            try {
                gitBranchAnalysisService.indexAllBranches(project, projectPath)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index branch summaries" }
                totalErrors++
            }

            val finalResult =
                GitIndexingResult(
                    commitMetadataResult = commitMetadataResult,
                    codeIndexingResult = codeIndexingResult,
                    tasksCreated = taskResult.commitTasks,
                    fileAnalysisTasksCreated = taskResult.fileAnalysisTasks,
                    descriptionUpdateTaskCreated = taskResult.descriptionTaskCreated,
                    errors = totalErrors + commitMetadataResult.errorCommits + codeIndexingResult.errorFiles,
                )

            logger.info {
                "Git indexing orchestration completed for project: ${project.name} - " +
                    "commits=${commitMetadataResult.processedCommits}, " +
                    "code_files=${codeIndexingResult.indexedFiles}, " +
                    "commit_tasks=${taskResult.commitTasks}, " +
                    "file_tasks=${taskResult.fileAnalysisTasks}, " +
                    "desc_update=${taskResult.descriptionTaskCreated}, " +
                    "errors=${finalResult.errors}"
            }

            finalResult
        }

    // ========== Mono-Repo Orchestration ==========

    /**
     * Orchestrate complete Git indexing for a client mono-repo with parallel processing.
     * Uses coroutineScope + async for parallel metadata and code indexing.
     *
     * @param clientId Client ID owning the mono-repo
     * @param monoRepoId Mono-repo identifier
     * @param monoRepoPath Path to cloned mono-repo
     * @param branch Current branch name
     * @param maxCommits Maximum number of commits to process
     */
    suspend fun orchestrateMonoRepoIndexing(
        clientId: ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        branch: String,
        maxCommits: Int = 1000,
    ): GitIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info {
                "Starting mono-repo Git indexing orchestration for mono-repo: $monoRepoId, branch: $branch"
            }

            // Use coroutineScope for parallel processing
            coroutineScope {
                var totalErrors = 0

                // Step 1 & 2: Run metadata and code indexing IN PARALLEL
                logger.info { "Step 1+2: Indexing commit metadata and code diffs in parallel..." }

                val metadataDeferred =
                    async {
                        try {
                            commitMetadataIndexer.indexMonoRepoGitHistory(
                                clientId,
                                monoRepoId,
                                monoRepoPath,
                                branch,
                                maxCommits,
                            )
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to index mono-repo commit metadata" }
                            totalErrors++
                            GitCommitMetadataIndexer.GitHistoryIndexingResult(0, 0, 1)
                        }
                    }

                val codeDeferred =
                    async {
                        try {
                            indexMonoRepoCodeDiffsForNewCommits(clientId, monoRepoId, monoRepoPath, branch)
                        } catch (e: Exception) {
                            logger.error(e) { "Failed to index mono-repo code diffs" }
                            totalErrors++
                            GitDiffCodeIndexer.CodeIndexingResult(0, 0, 1)
                        }
                    }

                // Wait for both to complete
                val commitMetadataResult = metadataDeferred.await()
                val codeIndexingResult = codeDeferred.await()

                logger.info {
                    "Parallel indexing completed - " +
                        "Metadata: processed=${commitMetadataResult.processedCommits}, errors=${commitMetadataResult.errorCommits} | " +
                        "Code: files=${codeIndexingResult.indexedFiles}, chunks=${codeIndexingResult.indexedChunks}, errors=${codeIndexingResult.errorFiles}"
                }

                // Step 3: Create pending tasks (no task creation for mono-repos currently)
                // Mono-repos are indexed for RAG queries, but tasks are project-specific
                val tasksCreated = 0
                val fileAnalysisTasksCreated = 0
                val descriptionUpdateTaskCreated = false

                val finalResult =
                    GitIndexingResult(
                        commitMetadataResult = commitMetadataResult,
                        codeIndexingResult = codeIndexingResult,
                        tasksCreated = tasksCreated,
                        fileAnalysisTasksCreated = fileAnalysisTasksCreated,
                        descriptionUpdateTaskCreated = descriptionUpdateTaskCreated,
                        errors = totalErrors + commitMetadataResult.errorCommits + codeIndexingResult.errorFiles,
                    )

                logger.info {
                    "Mono-repo Git indexing orchestration completed for: $monoRepoId - " +
                        "commits=${commitMetadataResult.processedCommits}, " +
                        "code_files=${codeIndexingResult.indexedFiles}, " +
                        "errors=${finalResult.errors}"
                }

                finalResult
            }
        }

    /**
     * Index code diffs for all NEW mono-repo commits with parallel processing.
     */
    private suspend fun indexMonoRepoCodeDiffsForNewCommits(
        clientId: ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        branch: String,
    ): GitDiffCodeIndexer.CodeIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Indexing code diffs for ALL new mono-repo commits: $monoRepoId" }

            var totalFiles = 0
            var totalChunks = 0
            var totalErrors = 0
            var processedCount = 0

            // Get ALL commits with state=NEW from MongoDB
            stateManager
                .findNewMonoRepoCommits(clientId, monoRepoId)
                .buffer(32)
                .collect { commitDoc ->
                    try {
                        logger.debug { "Indexing mono-repo code diff for commit ${commitDoc.commitHash.take(8)}" }

                        val result =
                            diffCodeIndexer.indexMonoRepoCommitCodeChanges(
                                clientId,
                                monoRepoId,
                                monoRepoPath,
                                commitDoc.commitHash,
                                branch,
                            )

                        totalFiles += result.indexedFiles
                        totalChunks += result.indexedChunks
                        totalErrors += result.errorFiles
                        processedCount++

                        if (processedCount % 10 == 0) {
                            logger.info { "Mono-repo code diff progress: $processedCount commits processed..." }
                        }

                        // Mark as indexed after successful processing
                        stateManager.markAsIndexed(commitDoc)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index mono-repo code diff for commit ${commitDoc.commitHash.take(8)}" }
                        totalErrors++
                        // Do NOT mark as indexed on failure; leave as NEW to retry on next cycle
                    }
                }

            logger.info {
                "Mono-repo code diff indexing completed: $processedCount commits, " +
                    "$totalFiles files, $totalChunks chunks, $totalErrors errors"
            }

            GitDiffCodeIndexer.CodeIndexingResult(totalFiles, totalChunks, totalErrors)
        }

    // ========== Shared Methods ==========

    /**
     * Index code diffs for all NEW commits in state manager.
     * Called after commit metadata indexing completes.
     * Processes ALL new commits, not just last 10.
     */
    private suspend fun indexCodeDiffsForNewCommits(
        project: ProjectDocument,
        projectPath: Path,
        branch: String,
    ): GitDiffCodeIndexer.CodeIndexingResult =
        withContext(Dispatchers.IO) {
            logger.info { "Indexing code diffs for ALL new commits in project: ${project.name}" }

            var totalFiles = 0
            var totalChunks = 0
            var totalErrors = 0
            var processedCount = 0

            // Get ALL commits with state=NEW from MongoDB
            stateManager
                .findNewCommits(project.id)
                .buffer(32) // Process up to 32 commits in buffer for memory safety
                .collect { commitDoc ->
                    try {
                        logger.debug { "Indexing code diff for commit ${commitDoc.commitHash.take(8)}" }

                        val result =
                            diffCodeIndexer.indexCommitCodeChanges(
                                project,
                                projectPath,
                                commitDoc.commitHash,
                                branch,
                            )

                        totalFiles += result.indexedFiles
                        totalChunks += result.indexedChunks
                        totalErrors += result.errorFiles
                        processedCount++

                        if (processedCount % 10 == 0) {
                            logger.info { "Code diff progress: $processedCount commits processed..." }
                        }

                        // Mark as indexed after successful processing
                        // NOTE: Final marking happens in createAnalysisTasksForNewCommits after task creation
                        // This intermediate marking ensures we track indexing progress separately
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index code diff for commit ${commitDoc.commitHash.take(8)}" }
                        totalErrors++
                        // Do NOT mark as indexed on failure; leave as NEW so task creation can still occur
                    }
                }

            logger.info {
                "Code diff indexing completed: $processedCount commits, " +
                    "$totalFiles files, $totalChunks chunks, $totalErrors errors"
            }

            GitDiffCodeIndexer.CodeIndexingResult(totalFiles, totalChunks, totalErrors)
        }

    /**
     * Create analysis tasks for all NEW commits.
     * Called after both metadata and code indexing complete.
     * Processes ALL new commits, not just last 10.
     * After task creation, marks commits as INDEXED.
     *
     * NEW: Also creates FILE_STRUCTURE_ANALYSIS tasks for changed files
     * and PROJECT_DESCRIPTION_UPDATE task if needed.
     */
    private suspend fun createAnalysisTasksForNewCommits(
        project: ProjectDocument,
        projectPath: Path,
        branch: String,
    ): TaskCreationResult =
        withContext(Dispatchers.IO) {
            logger.info { "Creating analysis tasks for ALL new commits in project: ${project.name}" }

            val createdTasks = mutableListOf<GitTaskCreator.CommitData>()
            var commitTaskCount = 0
            var fileAnalysisTaskCount = 0
            val recentCommitHashes = mutableListOf<String>()

            // Get ALL commits with state=NEW from MongoDB
            val newCommitDocuments =
                stateManager
                    .findNewCommits(project.id)
                    .buffer(32)
                    .toList()

            logger.info { "Found ${newCommitDocuments.size} NEW commits to create tasks for" }

            for (commitDoc in newCommitDocuments) {
                try {
                    logger.debug { "Creating tasks for commit ${commitDoc.commitHash.take(8)}" }

                    // Fetch full commit details from Git
                    val commitData = getCommitData(projectPath, commitDoc.commitHash, branch)

                    if (commitData != null) {
                        // 1. Create COMMIT_ANALYSIS tasks (one per file with diff)
                        val commitAnalysisTasks = taskCreator.createCommitAnalysisTasks(project, projectPath, listOf(commitData))
                        createdTasks.add(commitData)
                        commitTaskCount += commitAnalysisTasks.size
                        recentCommitHashes.add(commitData.commitHash)

                        // 2. Create FILE_STRUCTURE_ANALYSIS tasks for changed files
                        val fileTasksCreated =
                            fileStructureAnalyzer.analyzeCommitFiles(
                                project = project,
                                projectPath = projectPath,
                                commitHash = commitData.commitHash,
                                changedFiles = commitData.changedFiles,
                            )
                        fileAnalysisTaskCount += fileTasksCreated

                        if (commitTaskCount % 10 == 0) {
                            logger.info { "Task creation progress: $commitTaskCount commit tasks, $fileAnalysisTaskCount file tasks..." }
                        }

                        // Mark commit as INDEXED after successful task creation
                        stateManager.markAsIndexed(commitDoc)
                        logger.debug { "Marked commit ${commitDoc.commitHash.take(8)} as INDEXED" }
                    } else {
                        logger.warn { "Could not fetch commit data for ${commitDoc.commitHash.take(8)}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create tasks for commit ${commitDoc.commitHash.take(8)}" }
                }
            }

            // 3. Create PROJECT_DESCRIPTION_UPDATE task if needed
            var descriptionTaskCreated = false
            if (recentCommitHashes.isNotEmpty()) {
                try {
                    val updateTask =
                        projectDescriptionUpdater.checkAndCreateUpdateTask(
                            project = project,
                            recentCommitHashes = recentCommitHashes,
                            commitsSinceLastUpdate = recentCommitHashes.size,
                        )

                    if (updateTask != null) {
                        descriptionTaskCreated = true
                        logger.info { "Created project description update task for ${project.name}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create project description update task" }
                }
            }

            logger.info {
                "Task creation completed: $commitTaskCount commit tasks, " +
                    "$fileAnalysisTaskCount file analysis tasks, " +
                    "description update=${if (descriptionTaskCreated) "yes" else "no"} " +
                    "from ${newCommitDocuments.size} commits"
            }

            TaskCreationResult(
                commitTasks = commitTaskCount,
                fileAnalysisTasks = fileAnalysisTaskCount,
                descriptionTaskCreated = descriptionTaskCreated,
            )
        }

    private data class TaskCreationResult(
        val commitTasks: Int,
        val fileAnalysisTasks: Int,
        val descriptionTaskCreated: Boolean,
    )

    /**
     * Get detailed commit data for a specific commit hash.
     * Used for task creation after commit has been identified as NEW.
     */
    private suspend fun getCommitData(
        projectPath: Path,
        commitHash: String,
        branch: String,
    ): GitTaskCreator.CommitData? =
        withContext(Dispatchers.IO) {
            try {
                // Get commit info: author and message
                val infoProcess =
                    ProcessBuilder(
                        "git",
                        "show",
                        "--no-patch",
                        "--pretty=format:%an|%s",
                        commitHash,
                    ).directory(projectPath.toFile())
                        .start()

                val infoLine = infoProcess.inputStream.bufferedReader().readLine()
                infoProcess.waitFor()

                if (infoLine == null || !infoLine.contains("|")) {
                    logger.warn { "Could not parse commit info for $commitHash" }
                    return@withContext null
                }

                val parts = infoLine.split("|", limit = 2)
                val author = parts[0]
                val message = parts[1]

                // Get changed files with stats
                val statsProcess =
                    ProcessBuilder(
                        "git",
                        "show",
                        "--numstat",
                        "--pretty=format:",
                        commitHash,
                    ).directory(projectPath.toFile())
                        .start()

                val changedFiles = mutableListOf<String>()
                var additions = 0
                var deletions = 0

                statsProcess.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val statParts = line.split("\t")
                        if (statParts.size >= 3) {
                            additions += statParts[0].toIntOrNull() ?: 0
                            deletions += statParts[1].toIntOrNull() ?: 0
                            changedFiles.add(statParts[2])
                        }
                    }
                }

                statsProcess.waitFor()

                GitTaskCreator.CommitData(
                    commitHash = commitHash,
                    author = author,
                    message = message,
                    branch = branch,
                    changedFiles = changedFiles,
                    additions = additions,
                    deletions = deletions,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to get commit data for $commitHash" }
                null
            }
        }
}
