package com.jervis.service.indexing

import com.jervis.configuration.prompts.PromptTypeEnum
import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import com.jervis.service.gateway.core.LlmGateway
import com.jervis.service.indexing.dto.GitCommitProcessingResponse
import com.jervis.service.indexing.monitoring.IndexingStepType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path

/**
 * Service for indexing Git history into RAG system.
 * Supports incremental updates to avoid re-indexing existing commits.
 */
@Service
class GitHistoryIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
    private val indexingMonitorService: com.jervis.service.indexing.monitoring.IndexingMonitorService,
    private val llmGateway: LlmGateway,
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
    )

    /**
     * Index git history with incremental updates.
     * Only processes commits that haven't been indexed yet.
     */
    suspend fun indexGitHistory(
        project: ProjectDocument,
        projectPath: Path,
        maxCommits: Int = 1000,
    ): GitHistoryIndexingResult =
        withContext(Dispatchers.IO) {
            try {
                logger.info { "Starting git history indexing for project: ${project.name}" }

                // Get already indexed commit hashes to avoid duplicates
                val indexedCommits = getIndexedCommitHashes(project)
                logger.debug { "Found ${indexedCommits.size} already indexed commits" }

                // Get git commits that haven't been indexed yet
                val newCommits = getNewGitCommits(projectPath, indexedCommits, maxCommits)
                logger.info { "Found ${newCommits.size} new commits to index for project: ${project.name}" }
                indexingMonitorService.addStepLog(
                    project.id,
                    IndexingStepType.GIT_HISTORY,
                    "Found ${newCommits.size} new commits to index (${indexedCommits.size} already indexed)",
                )

                var processedCommits = 0
                var errorCommits = 0

                for ((index, commit) in newCommits.withIndex()) {
                    try {
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.GIT_HISTORY,
                            "Processing commit (${index + 1}/${newCommits.size}): ${commit.hash.take(8)} by ${commit.author}",
                        )

                        val success = indexGitCommit(project, commit)
                        if (success) {
                            processedCommits++
                            indexingMonitorService.addStepLog(
                                project.id,
                                IndexingStepType.GIT_HISTORY,
                                "✓ Successfully indexed commit: ${commit.hash.take(8)} - ${commit.message.take(50)}...",
                            )
                        } else {
                            errorCommits++
                            indexingMonitorService.addStepLog(
                                project.id,
                                IndexingStepType.GIT_HISTORY,
                                "✗ Failed to index commit: ${commit.hash.take(8)}",
                            )
                        }
                    } catch (e: Exception) {
                        indexingMonitorService.addStepLog(
                            project.id,
                            IndexingStepType.GIT_HISTORY,
                            "✗ Error indexing commit: ${commit.hash.take(8)} - ${e.message}",
                        )
                        logger.warn(e) { "Failed to index commit: ${commit.hash}" }
                        errorCommits++
                    }
                }

                val result = GitHistoryIndexingResult(processedCommits, 0, errorCommits)
                logger.info {
                    "Git history indexing completed for project: ${project.name} - " +
                        "Processed: $processedCommits, Errors: $errorCommits"
                }

                result
            } catch (e: Exception) {
                logger.error(e) { "Error during git history indexing for project: ${project.name}" }
                GitHistoryIndexingResult(0, 0, 1)
            }
        }

    /**
     * Index a single git commit using atomic sentence embedding
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
                val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, sentence)

                val ragDocument =
                    RagDocument(
                        projectId = project.id,
                        ragSourceType = RagSourceType.GIT_HISTORY,
                        summary = sentence,
                        clientId = project.clientId,
                        path = "git/commits/${commit.hash}",
                        language = "git-commit",
                        gitCommitHash = commit.hash,
                        chunkId = index,
                        symbolName = "git-commit-${commit.hash.take(8)}",
                    )

                vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
            }

            logger.debug { "Successfully indexed git commit: ${commit.hash} as ${sentences.size} atomic sentences" }
            return true
        } catch (e: Exception) {
            logger.error(e) { "Failed to index git commit: ${commit.hash}" }
            return false
        }
    }

    /**
     * Get commit hashes that have already been indexed
     */
    private suspend fun getIndexedCommitHashes(project: ProjectDocument): Set<String> =
        try {
            // Search for existing git history documents for this project
            val filter =
                mapOf(
                    "projectId" to project.id.toString(),
                )

            val existingDocs =
                vectorStorage.search(
                    collectionType = ModelType.EMBEDDING_TEXT,
                    query = listOf(), // Empty query to get all matching filters
                    limit = 10000, // High limit to get all existing commits
                    filter = filter,
                )

            existingDocs
                .mapNotNull { doc ->
                    val source = doc["source"]?.stringValue
                    source?.substringAfterLast("/") // Extract commit hash from source
                }.toSet()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to get indexed commit hashes, proceeding with empty set" }
            emptySet()
        }

    /**
     * Get new git commits that haven't been indexed yet
     */
    private suspend fun getNewGitCommits(
        projectPath: Path,
        indexedCommits: Set<String>,
        maxCommits: Int,
    ): List<GitCommit> =
        withContext(Dispatchers.IO) {
            try {
                val process =
                    ProcessBuilder(
                        "git",
                        "log",
                        "--pretty=format:%H|%an|%ad|%D|%s",
                        "--date=iso",
                        "--numstat",
                        "-n",
                        maxCommits.toString(),
                    ).directory(projectPath.toFile())
                        .start()

                val commits = mutableListOf<GitCommit>()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var currentCommit: GitCommit? = null
                val changedFiles = mutableListOf<String>()
                var additions = 0
                var deletions = 0

                reader.useLines { lines ->
                    for (line in lines) {
                        when {
                            line.contains("|") && line.split("|").size >= 5 -> {
                                // Save previous commit if exists
                                currentCommit?.let { commit ->
                                    if (!indexedCommits.contains(commit.hash)) {
                                        commits.add(
                                            commit.copy(
                                                changedFiles = changedFiles.toList(),
                                                additions = additions,
                                                deletions = deletions,
                                            ),
                                        )
                                    }
                                }

                                // Parse new commit
                                val parts = line.split("|")
                                val refNames = parts[3]
                                // Extract branch from refnames (format: "origin/main, main" -> "main")
                                val branch = extractBranchFromRefNames(refNames)

                                currentCommit =
                                    GitCommit(
                                        hash = parts[0],
                                        author = parts[1],
                                        date = parts[2],
                                        branch = branch,
                                        message = parts.drop(4).joinToString("|"),
                                        changedFiles = emptyList(),
                                        additions = 0,
                                        deletions = 0,
                                    )
                                changedFiles.clear()
                                additions = 0
                                deletions = 0
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

                // Don't forget the last commit
                currentCommit?.let { commit ->
                    if (!indexedCommits.contains(commit.hash)) {
                        commits.add(
                            commit.copy(
                                changedFiles = changedFiles.toList(),
                                additions = additions,
                                deletions = deletions,
                            ),
                        )
                    }
                }

                process.waitFor()
                commits
            } catch (e: Exception) {
                logger.error(e) { "Failed to get git commits" }
                emptyList()
            }
        }

    /**
     * Extract branch name from git refnames string
     */
    private fun extractBranchFromRefNames(refNames: String): String {
        if (refNames.isBlank()) return "main"

        // Split by comma and look for branch references
        val refs = refNames.split(",").map { it.trim() }

        // Priority order: local branches > origin branches > fallback to main
        val localBranch = refs.find { it.matches(Regex("^[^/]+$")) && !it.startsWith("tag:") }
        if (localBranch != null) return localBranch

        val originBranch = refs.find { it.startsWith("origin/") && !it.contains("HEAD") }
        if (originBranch != null) return originBranch.removePrefix("origin/")

        // If we find HEAD -> origin/something, extract that
        val headRef = refs.find { it.contains("HEAD ->") }
        if (headRef != null) {
            val branchPart = headRef.substringAfter("HEAD ->").trim()
            if (branchPart.startsWith("origin/")) {
                return branchPart.removePrefix("origin/")
            }
            return branchPart
        }

        return "main" // Fallback
    }

    /**
     * Create atomic sentences for RAG embedding from git commit data using LLM processing.
     * Following requirement #4: "každý commit se také musí rozložit na krátké popisky"
     */
    private suspend fun createCommitSentences(commit: GitCommit): List<String> {
        val commitContent =
            buildString {
                appendLine("Commit: ${commit.hash}")
                appendLine("Author: ${commit.author}")
                appendLine("Date: ${commit.date}")
                appendLine("Message: ${commit.message}")
                if (commit.changedFiles.isNotEmpty()) {
                    appendLine("Files changed (${commit.changedFiles.size}): ${commit.changedFiles.joinToString(", ")}")
                    appendLine("Statistics: +${commit.additions} additions, -${commit.deletions} deletions")
                }
            }

        val response =
            llmGateway.callLlm(
                type = PromptTypeEnum.GIT_COMMIT_PROCESSING,
                responseSchema = GitCommitProcessingResponse(),
                quick = false,
                mappingValue =
                    mapOf(
                        "commitHash" to commit.hash,
                        "commitAuthor" to commit.author,
                        "commitDate" to commit.date,
                        "commitBranch" to commit.branch,
                        "commitContent" to commitContent,
                    ),
            )

        // Filter out any empty or too short sentences
        return response.result.sentences.filter { it.trim().isNotEmpty() && it.length >= 10 }
    }
}
