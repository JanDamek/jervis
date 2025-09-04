package com.jervis.service.indexing

import com.jervis.domain.model.ModelType
import com.jervis.domain.rag.RagDocument
import com.jervis.domain.rag.RagDocumentType
import com.jervis.domain.rag.RagSourceType
import com.jervis.entity.mongo.ProjectDocument
import com.jervis.repository.vector.VectorStorageRepository
import com.jervis.service.gateway.EmbeddingGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for indexing Git history into RAG system.
 * Supports incremental updates to avoid re-indexing existing commits.
 */
@Service
class GitHistoryIndexingService(
    private val embeddingGateway: EmbeddingGateway,
    private val vectorStorage: VectorStorageRepository,
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

                var processedCommits = 0
                var errorCommits = 0

                for (commit in newCommits) {
                    try {
                        val success = indexGitCommit(project, commit)
                        if (success) {
                            processedCommits++
                        } else {
                            errorCommits++
                        }
                    } catch (e: Exception) {
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
     * Index a single git commit
     */
    private suspend fun indexGitCommit(
        project: ProjectDocument,
        commit: GitCommit,
    ): Boolean {
        try {
            val commitSummary =
                buildString {
                    appendLine("Git Commit: ${commit.hash}")
                    appendLine("Author: ${commit.author}")
                    appendLine("Date: ${commit.date}")
                    appendLine("Message: ${commit.message}")
                    appendLine()
                    appendLine("Changes:")
                    appendLine("- Files changed: ${commit.changedFiles.size}")
                    appendLine("- Lines added: ${commit.additions}")
                    appendLine("- Lines deleted: ${commit.deletions}")
                    appendLine()
                    if (commit.changedFiles.isNotEmpty()) {
                        appendLine("Modified files:")
                        commit.changedFiles.take(20).forEach { file ->
                            appendLine("  - $file")
                        }
                        if (commit.changedFiles.size > 20) {
                            appendLine("  ... and ${commit.changedFiles.size - 20} more files")
                        }
                    }
                }

            val embedding = embeddingGateway.callEmbedding(ModelType.EMBEDDING_TEXT, commitSummary)

            val ragDocument =
                RagDocument(
                    projectId = project.id!!,
                    documentType = RagDocumentType.GIT_HISTORY,
                    ragSourceType = RagSourceType.GIT,
                    pageContent = commitSummary,
                    source = "git://${project.name}/${commit.hash}",
                    path = "git/commits/${commit.hash}",
                    module = "git-history",
                    language = "git-commit",
                    timestamp = parseGitDate(commit.date).toEpochMilli(),
                )

            vectorStorage.store(ModelType.EMBEDDING_TEXT, ragDocument, embedding)
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
                    "documentType" to RagDocumentType.GIT_HISTORY.name,
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
                        "--pretty=format:%H|%an|%ad|%s",
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
                            line.contains("|") && line.split("|").size >= 4 -> {
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
                                currentCommit =
                                    GitCommit(
                                        hash = parts[0],
                                        author = parts[1],
                                        date = parts[2],
                                        message = parts.drop(3).joinToString("|"),
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
     * Parse git date format to Instant
     */
    private fun parseGitDate(dateStr: String): Instant =
        try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxxx")
            val localDateTime = LocalDateTime.parse(dateStr, formatter)
            localDateTime.atZone(ZoneId.systemDefault()).toInstant()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse git date: $dateStr, using current time" }
            Instant.now()
        }
}
