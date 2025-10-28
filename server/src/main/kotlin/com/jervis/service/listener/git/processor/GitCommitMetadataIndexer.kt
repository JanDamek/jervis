package com.jervis.service.listener.git.processor

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
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
 * Indexes Git commit metadata into RAG system.
 * Focuses on commit-level information: author, message, files changed, stats.
 *
 * Architecture follows email indexing pattern:
 * 1. Fetch commits from Git log
 * 2. Save commit IDs to state manager (MongoDB)
 * 3. Process only NEW commits from state manager
 * 4. Create atomic sentences for each commit
 * 5. Embed and store in Qdrant with vector store tracking
 * 6. Mark commits as INDEXED after processing
 *
 * Does NOT handle:
 * - Code diff indexing (see GitDiffCodeIndexer)
 * - Pending task creation (see GitTaskCreator)
 */
@Service
class GitCommitMetadataIndexer(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val llmGateway: LlmGateway,
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

    /**
     * Index git history following email indexing pattern.
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
                syncCommitIdsFromGit(project, projectPath, maxCommits)

                // Step 2: Process NEW commits (similar to processNewMessages)
                val result = processNewCommits(project, projectPath, branch)

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
     * Sync commit IDs from Git log to state manager.
     * Analogous to EmailIndexingOrchestrator.syncMessageIdsFromImap()
     */
    private suspend fun syncCommitIdsFromGit(
        project: ProjectDocument,
        projectPath: Path,
        maxCommits: Int,
    ) {
        val commits = fetchCommitInfoFromGit(projectPath, maxCommits)
        stateManager.saveNewCommits(project.id, commits)
    }

    /**
     * Process commits with state = NEW.
     * Analogous to EmailIndexingOrchestrator.processNewMessages()
     */
    private suspend fun processNewCommits(
        project: ProjectDocument,
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
                            stateManager.markAsIndexed(commitDoc)
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
     * Index a single git commit using atomic sentence embedding with vector store tracking
     */
    private suspend fun indexGitCommit(
        project: ProjectDocument,
        commit: GitCommit,
    ): Boolean {
        try {
            // Create atomic sentences for RAG embedding following requirement #4
            val sentences = createCommitSentences(commit)

            logger.debug { "Split commit ${commit.hash} into ${sentences.size} atomic sentences" }

            // Create individual embeddings for each sentence
            for (index in sentences.indices) {
                val sentence = sentences[index]
                val sourceId = "${commit.hash}-sentence-$index"

                // Check if content changed (skip if already indexed with same content)
                if (!vectorStoreIndexService.hasContentChanged(
                        RagSourceType.GIT_HISTORY,
                        sourceId,
                        project.id,
                        sentence,
                    )
                ) {
                    logger.debug { "Skipping sentence $index of commit ${commit.hash} - content unchanged" }
                    continue
                }

                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence)

                val ragDocument =
                    RagDocument(
                        projectId = project.id,
                        ragSourceType = RagSourceType.GIT_HISTORY,
                        summary = sentence,
                        clientId = project.clientId,
                        // Universal metadata
                        from = commit.author,
                        subject = commit.message.lines().firstOrNull() ?: "",
                        timestamp = commit.date,
                        parentRef = commit.hash,
                        indexInParent = index,
                        totalSiblings = sentences.size,
                        contentType = "git-commit",
                        // Git-specific
                        language = "git-commit",
                        gitCommitHash = commit.hash,
                        symbolName = "git-commit-${commit.hash.take(8)}",
                        branch = commit.branch,
                        chunkId = index,
                    )

                val vectorStoreId = vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)

                // Track in MongoDB what was indexed to Qdrant
                vectorStoreIndexService.trackIndexed(
                    projectId = project.id,
                    clientId = project.clientId,
                    branch = commit.branch,
                    sourceType = RagSourceType.GIT_HISTORY,
                    sourceId = sourceId,
                    vectorStoreId = vectorStoreId,
                    vectorStoreName = "git-commit-${commit.hash.take(8)}-$index",
                    content = sentence,
                    filePath = null,
                    symbolName = "git-commit-${commit.hash.take(8)}",
                    commitHash = commit.hash,
                )
            }

            logger.debug { "Successfully indexed git commit: ${commit.hash} as ${sentences.size} atomic sentences" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index git commit: ${commit.hash}" }
            return false
        }
    }

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

    /**
     * Create atomic sentences for RAG embedding from git commit data using LLM processing.
     * Following requirement #4: "každý commit se také musí rozložit na krátké popisky"
     */
    private suspend fun createCommitSentences(commit: GitCommit): List<String> {
        val atomic = mutableListOf<String>()

        // Deterministic atomic facts to guarantee RAG discoverability
        atomic += "Commit ${commit.hash} on branch ${commit.branch} was authored by ${commit.author} on ${commit.date}."
        if (commit.message.isNotBlank()) {
            atomic += "Commit ${commit.hash} message: ${commit.message}."
        }
        if (commit.changedFiles.isNotEmpty()) {
            atomic += "Commit ${commit.hash} changed ${commit.changedFiles.size} files: ${
                commit.changedFiles.joinToString(
                    ", ",
                )
            }."
            atomic += "Commit ${commit.hash} stats: +${commit.additions} additions and -${commit.deletions} deletions."
        }
        if (commit.parentHashes.size >= 2) {
            atomic += "Commit ${commit.hash} is a merge of ${commit.parentHashes.joinToString(" and ")}."
        }
        commit.tags.takeIf { it.isNotEmpty() }?.let { tags ->
            atomic += "Commit ${commit.hash} has tags: ${tags.joinToString(", ")}."
        }
        // Method-level facts for queries like 'who last changed isAuthorized'
        commit.changedMethods.forEach { (file, methods) ->
            methods.forEach { method ->
                atomic += "Method $method in $file was modified in commit ${commit.hash} by ${commit.author} on ${commit.date}."
            }
        }

        // Provide structured block to LLM to optionally expand/norm sentences
        val commitContent =
            buildString {
                appendLine("Commit: ${commit.hash}")
                appendLine("Author: ${commit.author}")
                appendLine("Date: ${commit.date}")
                appendLine("Branch: ${commit.branch}")
                appendLine("Parents: ${commit.parentHashes.joinToString(", ")}")
                appendLine("Tags: ${commit.tags.joinToString(", ")}")
                appendLine("Message: ${commit.message}")
                if (commit.changedFiles.isNotEmpty()) {
                    appendLine("Files changed (${commit.changedFiles.size}): ${commit.changedFiles.joinToString(", ")}")
                    appendLine("Statistics: +${commit.additions} additions, -${commit.deletions} deletions")
                }
                if (commit.changedMethods.isNotEmpty()) {
                    appendLine("Changed methods:")
                    commit.changedMethods.forEach { (file, methods) ->
                        appendLine("- $file: ${methods.joinToString(", ")}")
                    }
                }
            }

        val llmSentences =
            try {
                val response =
                    llmGateway.callLlm(
                        type = PromptTypeEnum.GIT_COMMIT_PROCESSING,
                        responseSchema = GitCommitProcessingResponse(),
                        quick = false,
                        backgroundMode = false,
                        mappingValue =
                            mapOf(
                                "commitHash" to commit.hash,
                                "commitAuthor" to commit.author,
                                "commitDate" to commit.date,
                                "commitBranch" to commit.branch,
                                "commitContent" to commitContent,
                            ),
                    )
                response.result.sentences
            } catch (e: Exception) {
                logger.warn(e) { "Failed to generate LLM sentences for commit ${commit.hash}, using atomic sentences only" }
                emptyList()
            }

        return (atomic + llmSentences)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length >= 10 }
            .distinct()
    }
}
