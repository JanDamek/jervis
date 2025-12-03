package com.jervis.service.listener.git

import com.jervis.dto.PendingTaskTypeEnum
import com.jervis.entity.ProjectDocument
import com.jervis.repository.ProjectMongoRepository
import com.jervis.service.background.PendingTaskService
import com.jervis.service.git.state.GitCommitDocument
import com.jervis.service.git.state.GitCommitStateManager
import com.jervis.service.indexing.AbstractContinuousIndexer
import com.jervis.service.listener.git.processor.GitCommitMetadataIndexer
import com.jervis.service.listener.git.processor.GitDiffCodeIndexer
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Git commits.
 *
 * NEW ARCHITECTURE (Graph-Based Routing):
 * 1. GitContinuousPoller discovers commits → saves to DB as NEW
 * 2. This indexer picks up NEW commits → creates DATA_PROCESSING PendingTask
 * 3. Task goes to KoogQualifierAgent (CPU) for structuring:
 *    - Chunks large commits with overlap (message + file diffs)
 *    - Creates Graph nodes (commit metadata, author, files changed)
 *    - Creates RAG chunks (searchable commit content)
 *    - Links Graph ↔ RAG bi-directionally
 * 4. After qualification: task marked DONE or READY_FOR_GPU
 *
 * Pure ETL: MongoDB → PendingTask → Qualifier → Graph + RAG
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class GitContinuousIndexer(
    private val projectRepository: ProjectMongoRepository,
    private val stateManager: GitCommitStateManager,
    private val metadataIndexer: GitCommitMetadataIndexer,
    private val codeIndexer: GitDiffCodeIndexer,
    private val directoryStructureService: com.jervis.service.storage.DirectoryStructureService,
    private val gitCommitRepository: com.jervis.service.git.state.GitCommitRepository,
    private val pendingTaskService: PendingTaskService,
) : AbstractContinuousIndexer<ProjectDocument, GitCommitDocument>() {
    override val indexerName: String = "GitContinuousIndexer"
    override val bufferSize: Int = 128

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $indexerName (single instance for all projects)..." }
        scope.launch {
            startContinuousIndexing()
        }
    }

    override fun newItemsFlow(): Flow<GitCommitDocument> = 
        gitCommitRepository.findByStateOrderByCommitDateDesc(com.jervis.service.git.state.GitCommitState.NEW)

    override suspend fun getAccountForItem(item: com.jervis.service.git.state.GitCommitDocument): ProjectDocument? {
        val projectId = item.projectId ?: return null
        return projectRepository.findById(projectId)
    }

    override fun itemLogLabel(item: GitCommitDocument) = "Commit:${item.commitHash.take(8)} by ${item.author}"

    override suspend fun fetchContentIO(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
    ): Any? {
        // Ensure Git repository is cloned before indexing
        val gitDir = getProjectGitDir(account)
        if (gitDir == null || !gitDir.resolve(".git").toFile().exists()) {
            logger.warn { "Git repository not found for project ${account.name}, skipping indexing (gitRepositoryService temporarily disabled)" }
            // Return null - commit stays in NEW state
            return null
        }

        // For Git, we don't fetch from external API - we have local Git repo
        // Return the commit document itself
        return item
    }

    override suspend fun processAndIndex(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        content: Any,
    ): IndexingResult {
        val commit = content as com.jervis.service.git.state.GitCommitDocument

        // Get project Git directory (should exist because we checked in fetchContentIO)
        val gitDir =
            getProjectGitDir(account) ?: run {
                logger.error { "Git directory disappeared after fetch check - this should not happen" }
                return IndexingResult(success = false)
            }

        try {
            // Build commit content for task (instead of auto-indexing)
            val commitContent = buildString {
                append("# Git Commit: ${commit.commitHash.take(8)}\n\n")
                append("**Branch:** ${commit.branch}\n")
                append("**Author:** ${commit.author}\n")
                append("**Date:** ${commit.commitDate}\n")
                append("**Project:** ${account.name}\n\n")

                append("## Message\n\n")
                append(commit.message)
                append("\n\n")

                // Get commit diff for context
                append("## Changes\n\n")
                val diff = getCommitDiff(gitDir, commit.commitHash)
                if (diff.isNotBlank()) {
                    append("```diff\n")
                    append(diff.take(10000)) // Limit diff size for task content
                    if (diff.length > 10000) {
                        append("\n... (diff truncated, full diff will be processed)\n")
                    }
                    append("\n```\n\n")
                }

                // Add metadata for qualifier
                append("## Document Metadata\n")
                append("- **Source:** Git Commit\n")
                append("- **Document ID:** git:${commit.commitHash}\n")
                append("- **Project ID:** ${account.id.toHexString()}\n")
                append("- **Client ID:** ${account.clientId?.toHexString() ?: "none"}\n")
            }

            // Don't auto-index anymore - just return success to proceed to task creation
            return IndexingResult(
                success = true,
                plainText = commitContent,
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to build commit content ${commit.commitHash}" }
            return IndexingResult(success = false)
        }
    }

    override fun shouldCreateTask(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean {
        // Always create DATA_PROCESSING task for Git commits
        return result.success
    }

    override suspend fun createTask(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        content: Any,
        result: IndexingResult,
    ) {
        val commit = content as com.jervis.service.git.state.GitCommitDocument

        // Create DATA_PROCESSING task instead of auto-indexing
        val clientId = account.clientId ?: run {
            logger.warn { "Project ${account.name} has no clientId, cannot create task" }
            return
        }

        pendingTaskService.createTask(
            taskType = PendingTaskTypeEnum.DATA_PROCESSING,
            content = result.plainText ?: "No content",
            projectId = account.id,
            clientId = clientId,
            correlationId = "git:${commit.commitHash}",
        )

        logger.info { "Created DATA_PROCESSING task for Git commit: ${commit.commitHash.take(8)}" }
    }

    /**
     * Get unified diff for a commit.
     */
    private fun getCommitDiff(gitDir: Path, commitHash: String): String {
        return try {
            Git.open(gitDir.toFile()).use { git ->
                val repository = git.repository
                val commit = repository.resolve(commitHash)

                if (commit == null) {
                    logger.warn { "Commit $commitHash not found in repository" }
                    return ""
                }

                RevWalk(repository).use { revWalk ->
                    val revCommit = revWalk.parseCommit(commit)
                    val parent = if (revCommit.parentCount > 0) revCommit.getParent(0) else null

                    DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                        formatter.setRepository(repository)
                        val diffs = if (parent != null) {
                            formatter.scan(parent.tree, revCommit.tree)
                        } else {
                            // Initial commit - compare against empty tree
                            formatter.scan(null, revCommit.tree)
                        }

                        buildString {
                            diffs.forEach { diff ->
                                append("${diff.oldPath} -> ${diff.newPath} (${diff.changeType})\n")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get diff for commit $commitHash" }
            ""
        }
    }

    override suspend fun markAsIndexed(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
    ) {
        stateManager.markAsIndexed(item)
        logger.info { "Successfully indexed Git commit: ${item.commitHash.take(8)}" }
    }

    override suspend fun markAsFailed(
        account: ProjectDocument?,
        item: com.jervis.service.git.state.GitCommitDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

    /**
     * Get project Git directory from workspace.
     */
    private fun getProjectGitDir(project: ProjectDocument): Path? {
        val gitDir = directoryStructureService.projectGitDir(project)
        return if (gitDir.toFile().exists()) gitDir else null
    }
}
