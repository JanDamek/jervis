package com.jervis.service.listener.git.processor

import com.jervis.domain.model.ModelTypeEnum
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.git.state.GitCommitInfo
import com.jervis.service.git.state.GitCommitStateManager
import com.jervis.service.rag.VectorStoreIndexService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Indexes Git commit metadata into RAG system WITHOUT LLM calls.
 * Focuses on commit-level information: author, message, files changed, stats.
 *
 * Architecture follows email indexing pattern:
 * 1. Fetch commits from Git log
 * 2. Save commit IDs to state manager (MongoDB)
 * 3. Process only NEW commits from state manager
 * 4. Create plain text summary (NO LLM)
 * 5. Embed and store in Qdrant with vector store tracking
 * 6. Mark commits as INDEXED after processing
 *
 * Detailed commit analysis happens later via PendingTask system with qualification.
 *
 * Does NOT handle:
 * - Code diff indexing (see GitDiffCodeIndexer)
 * - LLM-based analysis (moved to background tasks)
 */
@Service
class GitCommitMetadataIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val stateManager: GitCommitStateManager,
    private val vectorStoreIndexService: VectorStoreIndexService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Result of git history indexing operation
     */
    data class GitHistoryIndexingResult(
        val processedCommits: Int,
        val skippedCommits: Int,
        val errorCommits: Int,
    )

    /**
     * Git commit information
     */
    data class GitCommit(
        val hash: String,
        val author: String,
        val date: String,
        val message: String,
        val branch: String,
        val changedFiles: List<String>,
        val additions: Int,
        val deletions: Int,
        val parentHashes: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val changedMethods: Map<String, Set<String>> = emptyMap(),
    )

    // ========== Standalone Project Methods ==========

    /**
     * Index git history for a standalone project following email indexing pattern.
     * 1. Sync commit IDs from Git log
     * 2. Process NEW commits from state manager
     *
     * @param branch current branch name for vector store tracking
     */
    suspend fun indexGitHistory(
        project: ProjectDocument,
        projectPath: Path,
        branch: String,
        maxCommits: Int = 1000,
    ): GitHistoryIndexingResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Starting git history indexing for project: ${project.name}, branch: $branch" }

                // Step 1: Sync commit IDs from Git (similar to syncMessageIdsFromImap)
                syncCommitIdsFromGit(project, projectPath, maxCommits, branch)

                // Step 2: Process NEW commits (similar to processNewMessages)
                val result = processNewCommits(project, null, projectPath, branch)

                logger.info {
                    "Git history indexing completed for project: ${project.name} - " +
                        "Processed: ${result.processedCommits}, Errors: ${result.errorCommits}"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during git history indexing for project: ${project.name}" }
                GitHistoryIndexingResult(0, 0, 1)
            }
        }

    /**
     * Sync commit IDs from Git log to state manager for standalone project.
     * Analogous to EmailIndexingOrchestrator.syncMessageIdsFromImap()
     */
    private suspend fun syncCommitIdsFromGit(
        project: ProjectDocument,
        projectPath: Path,
        maxCommits: Int,
        branch: String,
    ) {
        val commits = fetchCommitInfoFromGit(projectPath, maxCommits)
        stateManager.saveNewCommits(project.clientId, project.id, commits, branch)
    }

    // ========== Mono-Repo Methods ==========

    /**
     * Index git history for a client mono-repo.
     * Uses clientId + monoRepoId for RAG queries (no projectId).
     */
    suspend fun indexMonoRepoGitHistory(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        branch: String,
        maxCommits: Int = 1000,
    ): GitHistoryIndexingResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Starting git history indexing for mono-repo: $monoRepoId, branch: $branch" }

                // Step 1: Sync commit IDs from Git
                syncMonoRepoCommitIdsFromGit(clientId, monoRepoId, monoRepoPath, maxCommits, branch)

                // Step 2: Process NEW commits
                val result = processNewMonoRepoCommits(clientId, monoRepoId, monoRepoPath, branch)

                logger.info {
                    "Git history indexing completed for mono-repo: $monoRepoId - " +
                        "Processed: ${result.processedCommits}, Errors: ${result.errorCommits}"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during git history indexing for mono-repo: $monoRepoId" }
                GitHistoryIndexingResult(0, 0, 1)
            }
        }

    /**
     * Sync commit IDs from Git log to state manager for mono-repo.
     */
    private suspend fun syncMonoRepoCommitIdsFromGit(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        maxCommits: Int,
        branch: String,
    ) {
        val commits = fetchCommitInfoFromGit(monoRepoPath, maxCommits)
        stateManager.saveNewMonoRepoCommits(clientId, monoRepoId, commits, branch)
    }

    /**
     * Process commits with state = NEW for mono-repo.
     * Indexes basic metadata WITHOUT marking as INDEXED.
     * Marking as INDEXED happens later after PendingTask creation (if needed for mono-repos).
     */
    private suspend fun processNewMonoRepoCommits(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        monoRepoPath: Path,
        branch: String,
    ): GitHistoryIndexingResult {
        var processedCommits = 0
        var errorCommits = 0

        stateManager
            .findNewMonoRepoCommits(clientId, monoRepoId)
            .buffer(10)
            .collect { commitDoc ->
                try {
                    logger.info { "Processing mono-repo commit ${commitDoc.commitHash.take(8)} by ${commitDoc.author}" }

                    val fullCommit = fetchFullCommitDetails(monoRepoPath, commitDoc.commitHash, branch)

                    if (fullCommit != null) {
                        val success = indexMonoRepoGitCommit(clientId, monoRepoId, fullCommit)
                        if (success) {
                            // For mono-repos, mark as indexed immediately (no project-specific tasks)
                            stateManager.markAsIndexed(commitDoc)
                            processedCommits++
                        } else {
                            errorCommits++
                        }
                    } else {
                        errorCommits++
                        logger.warn { "Could not fetch full details for mono-repo commit ${commitDoc.commitHash}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process mono-repo commit ${commitDoc.commitHash}" }
                    errorCommits++
                }
            }

        return GitHistoryIndexingResult(processedCommits, 0, errorCommits)
    }

    /**
     * Index a single git commit from mono-repo using plain text summary (NO LLM calls).
     * Creates single embedding from commit metadata for basic searchability.
     * Detailed analysis happens later in background via PendingTask.
     */
    private suspend fun indexMonoRepoGitCommit(
        clientId: org.bson.types.ObjectId,
        monoRepoId: String,
        commit: GitCommit,
    ): Boolean {
        try {
            // Create plain text summary WITHOUT LLM
            val summary = createPlainTextSummary(commit)

            val sourceId = "${commit.hash}-metadata"

            // Check if content changed (skip if already indexed with same content)
            if (!vectorStoreIndexService.hasContentChangedForMonoRepo(
                    RagSourceType.GIT_HISTORY,
                    sourceId,
                    clientId,
                    monoRepoId,
                    summary,
                )
            ) {
                logger.debug { "Skipping mono-repo commit ${commit.hash} - content unchanged" }
                return true
            }

            // Create single embedding for commit metadata
            val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, summary)

            val ragDocument =
                RagDocument(
                    projectId = null, // No projectId for mono-repo commits
                    ragSourceType = RagSourceType.GIT_HISTORY,
                    summary = summary,
                    clientId = clientId,
                    // Universal metadata
                    from = commit.author,
                    subject = commit.message.lines().firstOrNull() ?: "",
                    timestamp = commit.date,
                    parentRef = commit.hash,
                    indexInParent = 0,
                    totalSiblings = 1,
                    contentType = "git-commit",
                    // Git-specific
                    language = "git-commit",
                    gitCommitHash = commit.hash,
                    symbolName = "git-commit-${commit.hash.take(8)}",
                    branch = commit.branch,
                    chunkId = 0,
                )

            val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, ragDocument, embedding)

            // Track in MongoDB with monoRepoId (projectId = null)
            vectorStoreIndexService.trackIndexedForMonoRepo(
                clientId = clientId,
                monoRepoId = monoRepoId,
                branch = commit.branch,
                sourceType = RagSourceType.GIT_HISTORY,
                sourceId = sourceId,
                vectorStoreId = vectorStoreId,
                vectorStoreName = "git-commit-${commit.hash.take(8)}",
                content = summary,
                filePath = null,
                symbolName = "git-commit-${commit.hash.take(8)}",
                commitHash = commit.hash,
            )

            logger.debug { "Successfully indexed mono-repo git commit metadata: ${commit.hash}" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index mono-repo git commit: ${commit.hash}" }
            return false
        }
    }

    // ========== Shared Methods ==========

    /**
     * Process commits with state = NEW (works for both standalone and mono-repo).
     * Indexes basic metadata WITHOUT marking as INDEXED.
     * Marking as INDEXED happens later after PendingTask creation.
     */
    private suspend fun processNewCommits(
        project: ProjectDocument,
        monoRepoId: String?,
        projectPath: Path,
        branch: String,
    ): GitHistoryIndexingResult {
        var processedCommits = 0
        var errorCommits = 0

        stateManager
            .findNewCommits(project.id)
            .buffer(10)
            .collect { commitDoc ->
                try {
                    logger.info { "Processing commit ${commitDoc.commitHash.take(8)} by ${commitDoc.author}" }

                    // Fetch full commit details
                    val fullCommit = fetchFullCommitDetails(projectPath, commitDoc.commitHash, branch)

                    if (fullCommit != null) {
                        val success = indexGitCommit(project, fullCommit)
                        if (success) {
                            // DO NOT mark as indexed yet - this happens after PendingTask creation
                            processedCommits++
                        } else {
                            errorCommits++
                        }
                    } else {
                        errorCommits++
                        logger.warn { "Could not fetch full details for commit ${commitDoc.commitHash}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process commit ${commitDoc.commitHash}" }
                    errorCommits++
                }
            }

        return GitHistoryIndexingResult(processedCommits, 0, errorCommits)
    }

    /**
     * Index a single git commit using plain text summary (NO LLM calls).
     * Creates single embedding from commit metadata for basic searchability.
     * Detailed analysis happens later in background via PendingTask.
     */
    private suspend fun indexGitCommit(
        project: ProjectDocument,
        commit: GitCommit,
    ): Boolean {
        try {
            // Create plain text summary WITHOUT LLM
            val summary = createPlainTextSummary(commit)

            val sourceId = "${commit.hash}-metadata"

            // Check if content changed (skip if already indexed with same content)
            if (!vectorStoreIndexService.hasContentChanged(
                    RagSourceType.GIT_HISTORY,
                    sourceId,
                    project.id,
                    summary,
                )
            ) {
                logger.debug { "Skipping commit ${commit.hash} - content unchanged" }
                return true
            }

            // Create single embedding for commit metadata
            val embedding = embeddingGateway.callEmbedding(ModelTypeEnum.EMBEDDING_TEXT, summary)

            val ragDocument =
                RagDocument(
                    projectId = project.id,
                    ragSourceType = RagSourceType.GIT_HISTORY,
                    summary = summary,
                    clientId = project.clientId,
                    // Universal metadata
                    from = commit.author,
                    subject = commit.message.lines().firstOrNull() ?: "",
                    timestamp = commit.date,
                    parentRef = commit.hash,
                    indexInParent = 0,
                    totalSiblings = 1,
                    contentType = "git-commit",
                    // Git-specific
                    language = "git-commit",
                    gitCommitHash = commit.hash,
                    symbolName = "git-commit-${commit.hash.take(8)}",
                    branch = commit.branch,
                    chunkId = 0,
                )

            val vectorStoreId = vectorStorage.store(ModelTypeEnum.EMBEDDING_TEXT, ragDocument, embedding)

            // Track in MongoDB what was indexed to Qdrant
            vectorStoreIndexService.trackIndexed(
                projectId = project.id,
                clientId = project.clientId,
                branch = commit.branch,
                sourceType = RagSourceType.GIT_HISTORY,
                sourceId = sourceId,
                vectorStoreId = vectorStoreId,
                vectorStoreName = "git-commit-${commit.hash.take(8)}",
                content = summary,
                filePath = null,
                symbolName = "git-commit-${commit.hash.take(8)}",
                commitHash = commit.hash,
            )

            logger.debug { "Successfully indexed git commit metadata: ${commit.hash}" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index git commit: ${commit.hash}" }
            return false
        }
    }

    /**
     * Create plain text summary of commit without LLM calls.
     * This provides basic searchability for commit metadata.
     */
    private fun createPlainTextSummary(commit: GitCommit): String =
        buildString {
            appendLine("Commit: ${commit.hash}")
            appendLine("Author: ${commit.author}")
            appendLine("Date: ${commit.date}")
            appendLine("Branch: ${commit.branch}")
            appendLine()
            appendLine("Message:")
            appendLine(commit.message)
            appendLine()

            if (commit.changedFiles.isNotEmpty()) {
                appendLine("Files changed (${commit.changedFiles.size}):")
                commit.changedFiles.take(20).forEach { file ->
                    appendLine("  - $file")
                }
                if (commit.changedFiles.size > 20) {
                    appendLine("  ... and ${commit.changedFiles.size - 20} more files")
                }
                appendLine()
                appendLine("Statistics: +${commit.additions} additions, -${commit.deletions} deletions")
            }

            if (commit.parentHashes.size >= 2) {
                appendLine()
                appendLine("Merge commit from: ${commit.parentHashes.joinToString(", ")}")
            }

            if (commit.tags.isNotEmpty()) {
                appendLine()
                appendLine("Tags: ${commit.tags.joinToString(", ")}")
            }
        }.trim()

    /**
     * Fetch basic commit info from Git log (hash, author, message, date).
     * Returns lightweight GitCommitInfo for state manager.
     */
    private suspend fun fetchCommitInfoFromGit(
        projectPath: Path,
        maxCommits: Int,
    ): List<GitCommitInfo> =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(
                        "git",
                        "log",
                        "--pretty=format:%H|%an|%ad|%s",
                        "--date=iso-strict",
                        "-n",
                        maxCommits.toString(),
                    ).directory(projectPath.toFile())
                        .start()

                val commits = mutableListOf<GitCommitInfo>()
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                reader.useLines { lines ->
                    for (line in lines) {
                        val parts = line.split("|", limit = 4)
                        if (parts.size >= 4) {
                            val hash = parts[0]
                            val author = parts[1]
                            val dateStr = parts[2]
                            val message = parts[3]

                            val commitDate =
                                try {
                                    Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(dateStr))
                                } catch (_: Exception) {
                                    Instant.now()
                                }

                            commits.add(
                                GitCommitInfo(
                                    commitHash = hash,
                                    author = author,
                                    message = message,
                                    commitDate = commitDate,
                                ),
                            )
                        }
                    }
                }

                process.waitFor()
                logger.debug { "Fetched ${commits.size} commits from Git log" }
                commits
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch commit info from Git" }
                emptyList()
            }
        }

    /**
     * Fetch full commit details for indexing (includes changed files, methods, etc.)
     */
    private suspend fun fetchFullCommitDetails(
        projectPath: Path,
        commitHash: String,
        currentBranch: String,
    ): GitCommit? =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(
                        "git",
                        "log",
                        "--pretty=format:%H|%P|%an|%ad|%D|%s",
                        "--date=iso",
                        "--numstat",
                        "-n",
                        "1",
                        commitHash,
                    ).directory(projectPath.toFile())
                        .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var commit: GitCommit? = null
                val changedFiles = mutableListOf<String>()
                var additions = 0
                var deletions = 0

                reader.useLines { lines ->
                    for (line in lines) {
                        when {
                            line.contains("|") && line.split("|").size >= 6 -> {
                                // Parse commit header
                                val parts = line.split("|")
                                val hash = parts[0]
                                val parentsRaw = parts[1]
                                val parents = parentsRaw.split(" ").filter { it.isNotBlank() }
                                val refNames = parts[4]
                                val tags = extractTagsFromRefNames(refNames)
                                // Use current branch from parameter (accurate) instead of parsing refNames
                                val branch = currentBranch

                                commit =
                                    GitCommit(
                                        hash = hash,
                                        author = parts[2],
                                        date = parts[3],
                                        branch = branch,
                                        message = parts.drop(5).joinToString("|"),
                                        changedFiles = emptyList(),
                                        additions = 0,
                                        deletions = 0,
                                        parentHashes = parents,
                                        tags = tags,
                                    )
                            }

                            line.contains("\t") -> {
                                // Parse file changes (numstat output)
                                val parts = line.split("\t")
                                if (parts.size >= 3) {
                                    val added = parts[0].toIntOrNull() ?: 0
                                    val deleted = parts[1].toIntOrNull() ?: 0
                                    val fileName = parts[2]

                                    additions += added
                                    deletions += deleted
                                    changedFiles.add(fileName)
                                }
                            }
                        }
                    }
                }

                process.waitFor()

                // Add changed files and methods
                commit?.let {
                    val methods = getChangedMethodsForCommit(projectPath, commitHash)
                    it.copy(
                        changedFiles = changedFiles.toList(),
                        additions = additions,
                        deletions = deletions,
                        changedMethods = methods,
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch full commit details for $commitHash" }
                null
            }
        }

    /**
     * Extract branch name from git refnames string
     */
    private fun extractBranchFromRefNames(refNames: String): String {
        if (refNames.isBlank()) return "main"

        val refs = refNames.split(",").map { it.trim() }

        val localBranch = refs.find { it.matches(Regex("^[^/]+$")) && !it.startsWith("tag:") }
        if (localBranch != null) return localBranch

        val originBranch = refs.find { it.startsWith("origin/") && !it.contains("HEAD") }
        if (originBranch != null) return originBranch.removePrefix("origin/")

        val headRef = refs.find { it.contains("HEAD ->") }
        if (headRef != null) {
            val branchPart = headRef.substringAfter("HEAD ->").trim()
            if (branchPart.startsWith("origin/")) return branchPart.removePrefix("origin/")
            return branchPart
        }

        return "main"
    }

    /** Extract tags from refnames string (e.g., "tag: v1.19, origin/main") */
    private fun extractTagsFromRefNames(refNames: String): List<String> =
        refNames
            .split(",")
            .map { it.trim() }
            .filter { it.startsWith("tag:") }
            .map { it.removePrefix("tag:").trim() }

    /**
     * Parse changed method/function names for a commit using unified=0 hunks and simple heuristics.
     * Map key is file path, value is set of method identifiers touched in that file.
     */
    private fun getChangedMethodsForCommit(
        projectPath: Path,
        commitHash: String,
    ): Map<String, Set<String>> {
        return try {
            val process =
                ProcessBuilder("git", "show", "-p", "--unified=0", "--no-color", commitHash)
                    .directory(projectPath.toFile())
                    .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = mutableMapOf<String, MutableSet<String>>()
            var currentFile: String? = null

            fun addMethod(name: String) {
                val file = currentFile ?: return
                val set = result.getOrPut(file) { mutableSetOf() }
                set.add(name)
            }

            reader.useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("diff --git ") -> {
                            // diff --git a/path b/path
                            val parts = line.split(" ")
                            if (parts.size >= 4) {
                                val b = parts[3]
                                currentFile = b.removePrefix("b/")
                            }
                        }

                        line.startsWith("+++ b/") -> {
                            currentFile = line.removePrefix("+++ b/").trim()
                        }

                        line.startsWith("@@ ") -> {
                            // Try to extract signature after the second @@ if present, e.g., @@ -12,0 +13,0 @@ fun isAuthorized(...)
                            val after = line.substringAfterLast("@@").trim()
                            val sig = after.takeIf { it.isNotBlank() } ?: ""
                            val methodFromSig =
                                Regex("(fun|def|void|public|private|protected|static)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                                    .find(sig)
                                    ?.groupValues
                                    ?.getOrNull(2)
                            if (!methodFromSig.isNullOrBlank()) addMethod(methodFromSig)
                        }

                        line.startsWith("+") || line.startsWith("-") -> {
                            val content = line.drop(1)
                            // Kotlin/Java simple detectors
                            Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                                .find(content)
                                ?.let { addMethod(it.groupValues[1]) }
                            Regex("\\b(?:public|private|protected|static\\s+)?[A-Za-z0-9_<>\\[\\]]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                                .find(content)
                                ?.let { addMethod(it.groupValues[1]) }
                        }
                    }
                }
            }

            process.waitFor()
            result.mapValues { it.value.toSet() }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse changed methods for commit $commitHash" }
            emptyMap()
        }
    }
}
