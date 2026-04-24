package com.jervis.agentjob

import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.agentjob.AgentJobState
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectRepository
import com.jervis.project.ProjectResource
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Entry point for turning a Claude-chat-manager request ("run this as
 * a background Job") into an `AgentJobRecord` + prepared workspace.
 *
 * Stage 3a — pure Kotlin-side workflow. Actually creating the K8s Job
 * (fabric8 `batch().jobs().resource(job).create()`) and reconciling
 * its lifecycle are added in follow-up commits. Splitting the commit
 * keeps the record / workspace contract reviewable on its own.
 *
 * Responsibilities:
 *  1. Persist an `AgentJobRecord` in `QUEUED` state.
 *  2. For CODING flavor, materialise the per-agent `git worktree` via
 *     [AgentWorkspaceService]. Record `workspacePath` + `gitBranch`
 *     back onto the record.
 *  3. Leave the record in QUEUED — the (upcoming) K8s Job creation
 *     step will flip it to RUNNING.
 *
 * Explicitly NOT here yet:
 *  - Kubernetes Job manifest build & dispatch (3b)
 *  - `abort(jobId)` + status queries + K8s watcher reconciliation (3c)
 *  - Non-CODING flavors (analysis / research etc. delegate later)
 */
@Service
class AgentJobDispatcher(
    private val agentJobRecordRepository: AgentJobRecordRepository,
    private val agentWorkspaceService: AgentWorkspaceService,
    private val projectRepository: ProjectRepository,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Dispatch a single agent Job. Returns the persisted record with
     * `workspacePath` / `gitBranch` populated when applicable.
     *
     * The caller supplies `branchName` for CODING flavor — typically
     * rendered by a higher layer from `ProjectRules.branchNaming`
     * (e.g. "task/<agentJobId>"). Null branch on CODING means the
     * caller picked "use default branch directly" which is rejected —
     * agents always operate on their own branch.
     *
     * @throws IllegalArgumentException if CODING without a projectId or
     *         without a resourceId, or if the specified resource does
     *         not belong to the project.
     */
    suspend fun dispatch(
        flavor: AgentJobFlavor,
        title: String,
        description: String,
        clientId: ClientId?,
        projectId: ProjectId?,
        resourceId: String?,
        dispatchedBy: String,
        branchName: String?,
    ): AgentJobRecord {
        require(title.isNotBlank()) { "title must not be blank" }
        require(description.isNotBlank()) { "description must not be blank" }
        require(dispatchedBy.isNotBlank()) { "dispatchedBy must not be blank" }

        val jobId = AgentJobId.generate()

        // Persist the QUEUED record first, independent of workspace outcome.
        // This way any failure in workspace setup leaves an auditable record
        // (which the watcher later transitions to ERROR) instead of losing
        // the dispatch request silently.
        val initial = AgentJobRecord(
            id = jobId,
            flavor = flavor,
            clientId = clientId,
            projectId = projectId,
            title = title,
            description = description,
            dispatchedBy = dispatchedBy,
            state = AgentJobState.QUEUED,
            resourceId = resourceId,
            gitBranch = branchName,
            createdAt = Instant.now(),
        )
        val saved = agentJobRecordRepository.save(initial)
        logger.info {
            "dispatch | job=$jobId flavor=$flavor client=$clientId project=$projectId " +
                "resource=$resourceId branch=$branchName dispatchedBy=$dispatchedBy"
        }

        return when (flavor) {
            AgentJobFlavor.CODING -> dispatchCoding(saved, branchName)

            AgentJobFlavor.ANALYSIS,
            AgentJobFlavor.RESEARCH,
            AgentJobFlavor.MEETING_ATTENDANT,
            AgentJobFlavor.MEETING_SUBSTITUTE,
            AgentJobFlavor.SCHEDULED,
            -> {
                logger.warn {
                    "dispatch | flavor=$flavor record persisted but not yet routed — implementation pending"
                }
                saved
            }
        }
    }

    /**
     * CODING-flavor prep: clone/fetch the base project workspace and
     * carve out a per-job `git worktree` on a fresh branch. The record
     * is updated with workspacePath + gitBranch, still in QUEUED state
     * (the K8s Job will be created by step 3b, flipping to RUNNING).
     *
     * If workspace prep fails, the record is transitioned to ERROR so
     * the failure is auditable from the MCP status query.
     */
    private suspend fun dispatchCoding(
        record: AgentJobRecord,
        branchName: String?,
    ): AgentJobRecord {
        val projectId = record.projectId
            ?: return fail(record, "CODING flavor requires projectId")
        val resourceId = record.resourceId
            ?: return fail(record, "CODING flavor requires resourceId")
        val requestedBranch = branchName?.takeIf { it.isNotBlank() }
            ?: return fail(record, "CODING flavor requires a non-blank branchName")

        val project = projectRepository.getById(projectId)
            ?: return fail(record, "Project $projectId not found")

        val resource = resolveResource(project, resourceId)
            ?: return fail(
                record,
                "Resource $resourceId not found on project ${project.name} (${project.id})",
            )

        return try {
            val worktreePath = agentWorkspaceService.prepareWorktreeForJob(
                project = project,
                resource = resource,
                agentJobId = record.id,
                branchName = requestedBranch,
            )
            val updated = record.copy(
                workspacePath = worktreePath.toString(),
                gitBranch = requestedBranch,
            )
            agentJobRecordRepository.save(updated)
        } catch (e: Exception) {
            logger.error(e) { "dispatchCoding | workspace prep failed for job=${record.id}" }
            fail(record, "workspace prep failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Resource lookup by id — matches ProjectResource.id (a free-form
     * string embedded in ProjectDocument.resources, NOT an ObjectId).
     */
    private fun resolveResource(project: ProjectDocument, resourceId: String): ProjectResource? =
        project.resources.firstOrNull { it.id == resourceId }

    private suspend fun fail(record: AgentJobRecord, reason: String): AgentJobRecord {
        val updated = record.copy(
            state = AgentJobState.ERROR,
            errorMessage = reason,
            completedAt = Instant.now(),
        )
        logger.warn { "dispatch rejected | job=${record.id} reason=$reason" }
        return agentJobRecordRepository.save(updated)
    }
}
