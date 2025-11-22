package com.jervis.service.listener.git

import com.jervis.entity.ProjectDocument
import com.jervis.repository.ProjectMongoRepository
import com.jervis.service.git.state.GitCommitStateManager
import com.jervis.service.indexing.AbstractContinuousIndexer
import com.jervis.service.listener.git.processor.GitCommitMetadataIndexer
import com.jervis.service.listener.git.processor.GitDiffCodeIndexer
// import com.jervis.service.listener.git.processor.GitTaskCreator
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Git commits.
 * Polls DB for NEW commits, fetches full details, indexes to RAG.
 *
 * Pattern (same as Email/Jira/Confluence):
 * 1. GitContinuousPoller discovers commits → saves to DB as NEW
 * 2. This indexer picks up NEW commits → indexes metadata + code → marks INDEXED → creates pending task
 *
 * This separates discovery (fast, bulk) from indexing (slow, detailed).
 */
@Service
@Order(10) // Start after WeaviateSchemaInitializer
class GitContinuousIndexer(
    private val projectRepository: ProjectMongoRepository,
    private val stateManager: GitCommitStateManager,
    private val metadataIndexer: GitCommitMetadataIndexer,
    private val codeIndexer: GitDiffCodeIndexer,
    // private val taskCreator: GitTaskCreator,
    private val flowProps: com.jervis.configuration.properties.IndexingFlowProperties,
) : AbstractContinuousIndexer<ProjectDocument, com.jervis.service.git.state.GitCommitDocument>() {
    override val indexerName: String = "GitContinuousIndexer"
    override val bufferSize: Int get() = flowProps.bufferSize

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting $indexerName for all projects..." }
        scope.launch {
            // Start indexer for each project
            val projects = projectRepository.findAll().toList()
            projects.forEach { project ->
                launch {
                    logger.info { "Starting continuous Git indexing for project: ${project.name}" }
                    startContinuousIndexing(project)
                }
            }
        }
    }

    override fun newItemsFlow(account: ProjectDocument): Flow<com.jervis.service.git.state.GitCommitDocument> =
        stateManager.continuousNewCommits(account.id)

    override fun itemLogLabel(item: com.jervis.service.git.state.GitCommitDocument) =
        "Commit:${item.commitHash.take(8)} by ${item.author}"

    override suspend fun fetchContentIO(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
    ): Any? {
        // Mark as INDEXING to prevent concurrent processing
        stateManager.markAsIndexing(item)

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

        // Get project Git directory
        val gitDir = getProjectGitDir(account) ?: return IndexingResult(
            success = false,
        )

        try {
            // Step 1: Index commit metadata (message, author, files changed)
            val metadataResult = metadataIndexer.indexGitHistory(
                project = account,
                projectPath = gitDir,
                branch = commit.branch,
                maxCommits = 1, // Just this one commit
            )

            // Step 2: Index code changes (diffs)
            val codeResult = codeIndexer.indexCommitCodeChanges(
                project = account,
                projectPath = gitDir,
                commitHash = commit.commitHash,
                branch = commit.branch,
            )

            val success = metadataResult.processedCommits > 0 || codeResult.indexedFiles > 0

            return IndexingResult(
                success = success,
                plainText = "${commit.message} (${codeResult.indexedFiles} files changed)",
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to index commit ${commit.commitHash}" }
            return IndexingResult(success = false)
        }
    }

    override fun shouldCreateTask(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        content: Any,
        result: IndexingResult,
    ): Boolean {
        // Always create pending task for Git commits for qualification
        return result.success
    }

    override suspend fun createTask(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        content: Any,
        result: IndexingResult,
    ) {
        val commit = content as com.jervis.service.git.state.GitCommitDocument

        // TODO: Create pending task for commit analysis
    }
    override suspend fun markAsIndexed(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
    ) {
        stateManager.markAsIndexed(item)
        logger.info { "Successfully indexed Git commit: ${item.commitHash.take(8)}" }
    }

    override suspend fun markAsFailed(
        account: ProjectDocument,
        item: com.jervis.service.git.state.GitCommitDocument,
        reason: String,
    ) {
        stateManager.markAsFailed(item, reason)
    }

    /**
     * Get project Git directory from workspace.
     * TODO: Implement proper workspace management
     */
    private fun getProjectGitDir(project: ProjectDocument): Path? {
        // This should come from workspace service that manages cloned repos
        // For now, return null - GitContinuousPoller handles cloning
        return null
    }
}
