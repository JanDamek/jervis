package com.jervis.service.indexing.git

import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.dto.connection.ConnectionCapability
import com.jervis.dto.connection.ProviderEnum
import com.jervis.entity.ProjectDocument
import com.jervis.entity.ProjectResource
import com.jervis.entity.connection.ConnectionDocument
import com.jervis.repository.ConnectionRepository
import com.jervis.repository.ProjectRepository
import com.jervis.service.background.TaskService
import com.jervis.service.github.GitHubClient
import com.jervis.service.gitlab.GitLabClient
import com.jervis.service.indexing.git.state.MergeRequestDocument
import com.jervis.service.indexing.git.state.MergeRequestRepository
import com.jervis.service.indexing.git.state.MergeRequestState
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Continuous indexer for merge requests / pull requests.
 *
 * Polls GitLab/GitHub for open MRs/PRs on all projects with REPOSITORY resources.
 * New MRs are saved to MongoDB and a SCHEDULED_TASK is created in QUEUED state
 * that goes directly to the orchestrator for code review dispatch.
 *
 * Filters applied:
 * - Skips draft MRs/PRs (not ready for review)
 * - Skips Jervis-created branches (jervis/ prefix — handled by AgentTaskWatcher)
 *
 * Two coroutine loops:
 * 1. pollAllProjects() — discover new MRs from GitLab/GitHub APIs (every 2 minutes)
 * 2. processNewMergeRequests() — create review tasks for NEW MRs (every 15 seconds)
 */
@Service
@Order(12)
class MergeRequestContinuousIndexer(
    private val mergeRequestRepository: MergeRequestRepository,
    private val projectRepository: ProjectRepository,
    private val connectionRepository: ConnectionRepository,
    private val taskService: TaskService,
    private val gitLabClient: GitLabClient,
    private val gitHubClient: GitHubClient,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)

    companion object {
        /** Branches created by Jervis coding agent — skip to avoid review loops */
        private val JERVIS_BRANCH_PREFIXES = listOf("jervis/", "jervis-")

        /** Max MRs to create review tasks for in a single cycle */
        private const val MAX_TASKS_PER_CYCLE = 10
    }

    @PostConstruct
    fun start() {
        logger.info { "Starting MergeRequestContinuousIndexer..." }
        scope.launch {
            delay(30_000) // Let other indexers start first
            while (isActive) {
                try {
                    pollAllProjects()
                } catch (e: Exception) {
                    logger.error(e) { "MergeRequestContinuousIndexer cycle error" }
                }
                delay(120_000) // Poll every 2 minutes
            }
        }
        scope.launch {
            delay(35_000)
            while (isActive) {
                try {
                    processNewMergeRequests()
                } catch (e: Exception) {
                    logger.error(e) { "MergeRequestContinuousIndexer task creation error" }
                }
                delay(15_000) // Check for new MRs every 15 seconds
            }
        }
    }

    // -- Polling: discover new MRs from providers ---------------------

    private suspend fun pollAllProjects() {
        val projects = projectRepository.findByActiveTrue().toList()

        for (project in projects) {
            val repoResources = project.resources.filter { it.capability == ConnectionCapability.REPOSITORY }
            for (resource in repoResources) {
                try {
                    pollProjectResource(project, resource)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to poll MRs for ${project.name} / ${resource.resourceIdentifier}" }
                }
            }
        }
    }

    private suspend fun pollProjectResource(project: ProjectDocument, resource: ProjectResource) {
        val connection = connectionRepository.getById(ConnectionId(resource.connectionId)) ?: return

        when (connection.provider) {
            ProviderEnum.GITLAB -> pollGitLabMergeRequests(project, resource, connection)
            ProviderEnum.GITHUB -> pollGitHubPullRequests(project, resource, connection)
            else -> {} // No MR support for other providers
        }
    }

    private suspend fun pollGitLabMergeRequests(
        project: ProjectDocument,
        resource: ProjectResource,
        connection: ConnectionDocument,
    ) {
        val mrs = gitLabClient.listOpenMergeRequests(connection, resource.resourceIdentifier)
        logger.debug { "GitLab: ${mrs.size} open MRs for ${resource.resourceIdentifier}" }

        for (mr in mrs) {
            // Skip draft MRs — not ready for review
            if (mr.draft) {
                logger.debug { "Skipping draft GitLab MR !${mr.iid}: ${mr.title}" }
                continue
            }

            // Skip Jervis-created branches — AgentTaskWatcher handles those
            if (isJervisBranch(mr.source_branch)) {
                logger.debug { "Skipping Jervis branch MR !${mr.iid}: ${mr.source_branch}" }
                continue
            }

            val mrId = mr.iid.toString()
            val existing = mergeRequestRepository.findByProjectIdAndMergeRequestIdAndProvider(
                project.id.value, mrId, "gitlab",
            )
            if (existing != null) continue

            val doc = MergeRequestDocument(
                clientId = project.clientId.value,
                projectId = project.id.value,
                connectionId = connection.id.value,
                provider = "gitlab",
                mergeRequestId = mrId,
                title = mr.title,
                description = mr.description?.take(4000),
                author = mr.author?.name ?: mr.author?.username,
                sourceBranch = mr.source_branch,
                targetBranch = mr.target_branch,
                url = mr.web_url,
            )
            mergeRequestRepository.save(doc)
            logger.info { "Discovered new GitLab MR !${mr.iid}: ${mr.title}" }
        }
    }

    private suspend fun pollGitHubPullRequests(
        project: ProjectDocument,
        resource: ProjectResource,
        connection: ConnectionDocument,
    ) {
        // resourceIdentifier = "owner/repo"
        val parts = resource.resourceIdentifier.split("/", limit = 2)
        if (parts.size != 2) {
            logger.warn { "Invalid GitHub resource identifier: ${resource.resourceIdentifier}" }
            return
        }
        val (owner, repo) = parts

        val prs = gitHubClient.listOpenPullRequests(connection, owner, repo)
        logger.debug { "GitHub: ${prs.size} open PRs for $owner/$repo" }

        for (pr in prs) {
            // Skip draft PRs — not ready for review
            if (pr.draft) {
                logger.debug { "Skipping draft GitHub PR #${pr.number}: ${pr.title}" }
                continue
            }

            // Skip Jervis-created branches — AgentTaskWatcher handles those
            if (isJervisBranch(pr.head.ref)) {
                logger.debug { "Skipping Jervis branch PR #${pr.number}: ${pr.head.ref}" }
                continue
            }

            val prId = pr.number.toString()
            val existing = mergeRequestRepository.findByProjectIdAndMergeRequestIdAndProvider(
                project.id.value, prId, "github",
            )
            if (existing != null) continue

            val doc = MergeRequestDocument(
                clientId = project.clientId.value,
                projectId = project.id.value,
                connectionId = connection.id.value,
                provider = "github",
                mergeRequestId = prId,
                title = pr.title,
                description = pr.body?.take(4000),
                author = pr.user?.login,
                sourceBranch = pr.head.ref,
                targetBranch = pr.base.ref,
                url = pr.html_url,
            )
            mergeRequestRepository.save(doc)
            logger.info { "Discovered new GitHub PR #${pr.number}: ${pr.title}" }
        }
    }

    // -- Task creation: dispatch code review for NEW MRs ──────────────

    private suspend fun processNewMergeRequests() {
        val newMrs = mergeRequestRepository.findByStateOrderByCreatedAtAsc(MergeRequestState.NEW).toList()
        if (newMrs.isEmpty()) return

        logger.info { "Creating review tasks for ${newMrs.size} new merge requests" }

        for (mr in newMrs.take(MAX_TASKS_PER_CYCLE)) {
            try {
                createReviewTask(mr)
            } catch (e: Exception) {
                logger.error(e) { "Failed to create review task for MR ${mr.mergeRequestId}" }
                mergeRequestRepository.save(mr.copy(state = MergeRequestState.FAILED))
            }
        }
    }

    /**
     * Create a code review task for a discovered MR/PR.
     *
     * The task is created as SCHEDULED_TASK in QUEUED state, bypassing KB indexation.
     * The MR content IS the review task — no extraction needed.
     * The task enters the orchestrator directly, which dispatches a code review agent.
     */
    private suspend fun createReviewTask(mr: MergeRequestDocument) {
        val mrPrefix = if (mr.provider == "gitlab") "!" else "#"

        val content = buildString {
            appendLine("# Code Review: ${mr.title}")
            appendLine()
            appendLine("**Provider:** ${mr.provider}")
            appendLine("**MR/PR:** $mrPrefix${mr.mergeRequestId}")
            appendLine("**Source branch:** ${mr.sourceBranch}")
            appendLine("**Target branch:** ${mr.targetBranch}")
            appendLine("**URL:** ${mr.url}")
            mr.author?.let { appendLine("**Author:** $it") }
            appendLine("**Connection:** ${mr.connectionId}")
            appendLine()

            // Include MR description if available
            if (!mr.description.isNullOrBlank()) {
                appendLine("## MR Description")
                appendLine(mr.description)
                appendLine()
            }

            appendLine("## Review Instructions")
            appendLine("Review this merge request: check code quality, potential bugs, security issues,")
            appendLine("and adherence to project conventions. Post review comments directly on the MR/PR.")
        }

        val task = taskService.createTask(
            taskType = TaskTypeEnum.SCHEDULED_TASK,
            content = content,
            clientId = ClientId(mr.clientId),
            projectId = ProjectId(mr.projectId),
            correlationId = "merge-request:${mr.provider}:${mr.projectId}:${mr.mergeRequestId}",
            sourceUrn = SourceUrn.mergeRequest(
                projectId = ProjectId(mr.projectId),
                provider = mr.provider,
                mrId = mr.mergeRequestId,
            ),
            taskName = "Code review: ${mr.title}",
            state = TaskStateEnum.QUEUED, // Bypass KB indexation — go straight to orchestrator
        )

        mergeRequestRepository.save(
            mr.copy(
                state = MergeRequestState.REVIEW_DISPATCHED,
                reviewTaskId = task.id.value,
            ),
        )

        logger.info { "Created code review task ${task.id} for ${mr.provider} MR ${mr.mergeRequestId}" }
    }

    private fun isJervisBranch(branchName: String): Boolean =
        JERVIS_BRANCH_PREFIXES.any { branchName.startsWith(it, ignoreCase = true) }
}
