package com.jervis.service.indexing.git

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskTypeEnum
import com.jervis.service.background.TaskService
import com.jervis.service.indexing.git.state.GitCommitDocument
import com.jervis.service.indexing.git.state.GitCommitRepository
import com.jervis.service.indexing.git.state.GitCommitState
import com.jervis.service.indexing.git.state.GitCommitStateManager
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for Git commits.
 *
 * ETL Flow: MongoDB (GitCommitDocument NEW) -> GIT_PROCESSING Task -> Qualifier Agent -> Graph + KB (Joern)
 */
@Service
@Order(11)
class GitContinuousIndexer(
    private val stateManager: GitCommitStateManager,
    private val gitCommitRepository: GitCommitRepository,
    private val taskService: TaskService,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    @PostConstruct
    fun start() {
        logger.info { "Starting GitContinuousIndexer (MongoDB -> Knowledgebase)..." }
        scope.launch {
            runCatching { indexContinuously() }
                .onFailure { e -> logger.error(e) { "GitContinuousIndexer crashed" } }
        }
    }

    private suspend fun indexContinuously() {
        // Collect all NEW commits across all projects
        gitCommitRepository.findByStateOrderByCommitDateDesc(GitCommitState.NEW).collect { doc ->
            try {
                processCommit(doc)
            } catch (e: Exception) {
                logger.error(e) { "Failed to process Git commit ${doc.commitHash}" }
                stateManager.markAsFailed(doc, e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun processCommit(doc: GitCommitDocument) {
        logger.debug { "Processing Git commit ${doc.commitHash} for project ${doc.projectId}" }

        val projectId = doc.projectId ?: return // Should not happen for standalone projects

        // Create GIT_PROCESSING task
        // The Qualification Agent will take this task and:
        // 1. Fetch the commit diff
        // 2. Perform Joern analysis
        // 3. Store into GraphDB and Knowledgebase
        taskService.createTask(
            taskType = TaskTypeEnum.GIT_PROCESSING,
            content =
                """
                # Git Commit Processing
                - Commit: ${doc.commitHash}
                - Author: ${doc.author}
                - Message: ${doc.message}
                - Branch: ${doc.branch}
                
                Please perform Joern analysis and index this change into Knowledgebase and GraphDB.
                """.trimIndent(),
            projectId = ProjectId(projectId),
            clientId = ClientId(doc.clientId),
            correlationId = "git:${doc.commitHash}",
            sourceUrn =
                SourceUrn.git(
                    projectId = ProjectId(projectId),
                    commitHash = doc.commitHash,
                ),
        )

        stateManager.markAsIndexed(doc)
        logger.info { "Created GIT_PROCESSING task for commit: ${doc.commitHash.take(8)}" }
    }
}
