package com.jervis.agentjob

import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.agentjob.AgentJobState
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectRepository
import com.jervis.project.ProjectResource
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    companion object {
        // K8s deployment constants — mirror reference-k8s-deployment.md.
        private const val NAMESPACE = "jervis"
        private const val CODING_AGENT_IMAGE = "registry.damek-soft.eu/jandamek/jervis-coding-agent:latest"
        private const val MANAGED_BY = "jervis-coding-agent"
        private const val DATA_PVC = "jervis-data-pvc"
        private const val DATA_MOUNT = "/opt/jervis/data"
        private const val DATA_VOLUME = "jervis-data"
        private const val TTL_AFTER_FINISHED = 300

        fun codingJobName(agentJobId: AgentJobId): String = "jervis-coding-agent-$agentJobId"
    }

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

        val worktreePath = try {
            agentWorkspaceService.prepareWorktreeForJob(
                project = project,
                resource = resource,
                agentJobId = record.id,
                branchName = requestedBranch,
            )
        } catch (e: Exception) {
            logger.error(e) { "dispatchCoding | workspace prep failed for job=${record.id}" }
            return fail(record, "workspace prep failed: ${e.message ?: e::class.simpleName}")
        }

        val afterWorktree = record.copy(
            workspacePath = worktreePath.toString(),
            gitBranch = requestedBranch,
        )
        agentJobRecordRepository.save(afterWorktree)

        return try {
            val jobName = createKubernetesJob(afterWorktree)
            val running = afterWorktree.copy(
                state = AgentJobState.RUNNING,
                kubernetesJobName = jobName,
                startedAt = Instant.now(),
            )
            agentJobRecordRepository.save(running)
        } catch (e: Exception) {
            logger.error(e) { "dispatchCoding | K8s Job create failed for job=${record.id}" }
            // Leave the worktree in place for debug; the (upcoming) abort
            // path removes it cleanly and the watcher will detect the
            // ERROR state on next cycle.
            fail(afterWorktree, "K8s Job create failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Build and create the K8s Job manifest for a CODING-flavor record.
     * Base env only (job metadata, workspace path, branch) — secrets
     * (CLAUDE_CODE_OAUTH_TOKEN, git identity, GPG) land in the next
     * commit. Until then the Job image will pull-fail (the
     * jervis-coding-agent image is built in follow-up 3b.2), but the
     * manifest itself is valid Kotlin + fabric8 output.
     */
    private suspend fun createKubernetesJob(record: AgentJobRecord): String {
        val jobName = codingJobName(record.id)

        val envVars = buildBaseEnv(record)
        val volumeMount = VolumeMountBuilder()
            .withName(DATA_VOLUME)
            .withMountPath(DATA_MOUNT)
            .build()
        val volume = VolumeBuilder()
            .withName(DATA_VOLUME)
            .withNewPersistentVolumeClaim()
                .withClaimName(DATA_PVC)
            .endPersistentVolumeClaim()
            .build()

        val labels = buildLabels(record, jobName)

        val job: Job = JobBuilder()
            .withNewMetadata()
                .withName(jobName)
                .withNamespace(NAMESPACE)
                .addToLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withTtlSecondsAfterFinished(TTL_AFTER_FINISHED)
                .withBackoffLimit(0)
                .withNewTemplate()
                    .withNewMetadata()
                        .addToLabels(labels)
                    .endMetadata()
                    .withNewSpec()
                        .withRestartPolicy("Never")
                        .addNewContainer()
                            .withName("coding-agent")
                            .withImage(CODING_AGENT_IMAGE)
                            .withImagePullPolicy("Always")
                            .withEnv(envVars)
                            .withVolumeMounts(volumeMount)
                            .withNewResources()
                                .addToRequests("memory", Quantity("512Mi"))
                                .addToRequests("cpu", Quantity("250m"))
                                .addToLimits("memory", Quantity("2Gi"))
                                .addToLimits("cpu", Quantity("2000m"))
                            .endResources()
                        .endContainer()
                        .withVolumes(volume)
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()

        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                client.batch().v1().jobs()
                    .inNamespace(NAMESPACE)
                    .resource(job)
                    .create()
            }
        }
        logger.info { "createKubernetesJob | created K8s Job $jobName for agent-job=${record.id}" }
        return jobName
    }

    private fun buildBaseEnv(record: AgentJobRecord): List<EnvVar> = listOf(
        env("AGENT_JOB_ID", record.id.toString()),
        env("AGENT_FLAVOR", record.flavor.name),
        env("WORKSPACE", record.workspacePath.orEmpty()),
        env("EXPECTED_BRANCH", record.gitBranch.orEmpty()),
        env("CLIENT_ID", record.clientId?.toString().orEmpty()),
        env("PROJECT_ID", record.projectId?.toString().orEmpty()),
        env("RESOURCE_ID", record.resourceId.orEmpty()),
        env("DISPATCHED_BY", record.dispatchedBy),
    )

    private fun env(name: String, value: String): EnvVar =
        EnvVarBuilder().withName(name).withValue(value).build()

    private fun buildLabels(record: AgentJobRecord, jobName: String): Map<String, String> {
        val labels: LinkedHashMap<String, String> = linkedMapOf(
            "app" to MANAGED_BY,
            "managed-by" to MANAGED_BY,
            "job-name" to jobName,
            "agent-job-id" to record.id.toString(),
            "flavor" to record.flavor.name.lowercase(),
        )
        record.clientId?.let { labels["client-id"] = it.toString() }
        record.projectId?.let { labels["project-id"] = it.toString() }
        record.resourceId?.let { labels["resource-id"] = it }
        return labels
    }

    private fun buildK8sClient(): KubernetesClient {
        val config = ConfigBuilder()
            .withRequestTimeout(60_000)
            .withConnectionTimeout(15_000)
            .build()
        return KubernetesClientBuilder().withConfig(config).build()
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
