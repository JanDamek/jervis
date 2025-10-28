package com.jervis.service.listener.git.processor

import com.jervis.entity.ProjectDocument
import com.jervis.service.git.state.GitCommitStateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import mu.KotlinLogging
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
 * This follows the email indexing pattern where orchestrator coordinates
 * multiple specialized processors instead of one monolithic service.
 */
@Service
class GitIndexingOrchestrator(
    private val commitMetadataIndexer: GitCommitMetadataIndexer,
    private val diffCodeIndexer: GitDiffCodeIndexer,
    private val taskCreator: GitTaskCreator,
    private val stateManager: GitCommitStateManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Complete indexing result combining all processors
     */
    data class GitIndexingResult(
        val commitMetadataResult: GitCommitMetadataIndexer.GitHistoryIndexingResult,
        val codeIndexingResult: GitDiffCodeIndexer.CodeIndexingResult,
        val tasksCreated: Int,
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

            // Step 3: Create pending tasks for commit analysis
            logger.info { "Step 3: Creating pending tasks for commit analysis..." }
            val tasksCreated =
                try {
                    createAnalysisTasksForNewCommits(project, projectPath, branch)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create analysis tasks" }
                    totalErrors++
                    0
                }

            logger.info { "Created $tasksCreated pending tasks for commit analysis" }

            val finalResult =
                GitIndexingResult(
                    commitMetadataResult = commitMetadataResult,
                    codeIndexingResult = codeIndexingResult,
                    tasksCreated = tasksCreated,
                    errors = totalErrors + commitMetadataResult.errorCommits + codeIndexingResult.errorFiles,
                )

            logger.info {
                "Git indexing orchestration completed for project: ${project.name} - " +
                    "commits=${commitMetadataResult.processedCommits}, " +
                    "code_files=${codeIndexingResult.indexedFiles}, " +
                    "tasks=$tasksCreated, " +
                    "errors=${finalResult.errors}"
            }

            finalResult
        }

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
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to index code diff for commit ${commitDoc.commitHash.take(8)}" }
                        totalErrors++
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
     */
    private suspend fun createAnalysisTasksForNewCommits(
        project: ProjectDocument,
        projectPath: Path,
        branch: String,
    ): Int =
        withContext(Dispatchers.IO) {
            logger.info { "Creating analysis tasks for ALL new commits in project: ${project.name}" }

            val createdTasks = mutableListOf<GitTaskCreator.CommitData>()
            var taskCount = 0

            // Get ALL commits with state=NEW from MongoDB
            val newCommitDocuments =
                stateManager
                    .findNewCommits(project.id)
                    .buffer(32)
                    .toList()

            logger.info { "Found ${newCommitDocuments.size} NEW commits to create tasks for" }

            for (commitDoc in newCommitDocuments) {
                try {
                    logger.debug { "Creating task for commit ${commitDoc.commitHash.take(8)}" }

                    // Fetch full commit details from Git
                    val commitData = getCommitData(projectPath, commitDoc.commitHash, branch)

                    if (commitData != null) {
                        // Create pending task with qualification
                        taskCreator.createCommitAnalysisTask(project, projectPath, commitData)
                        createdTasks.add(commitData)
                        taskCount++

                        if (taskCount % 10 == 0) {
                            logger.info { "Task creation progress: $taskCount tasks created..." }
                        }

                        // Mark commit as INDEXED after successful task creation
                        stateManager.markAsIndexed(commitDoc)
                        logger.debug { "Marked commit ${commitDoc.commitHash.take(8)} as INDEXED" }
                    } else {
                        logger.warn { "Could not fetch commit data for ${commitDoc.commitHash.take(8)}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create task for commit ${commitDoc.commitHash.take(8)}" }
                }
            }

            logger.info {
                "Task creation completed: $taskCount tasks created from ${newCommitDocuments.size} commits"
            }

            taskCount
        }

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
