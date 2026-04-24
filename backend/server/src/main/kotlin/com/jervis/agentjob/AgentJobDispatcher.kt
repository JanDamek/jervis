package com.jervis.agentjob

import com.jervis.client.ClientDocument
import com.jervis.client.ClientRepository
import com.jervis.common.types.AgentJobId
import com.jervis.common.types.ClientId
import com.jervis.common.types.ConnectionId
import com.jervis.common.types.ProjectId
import com.jervis.connection.ConnectionDocument
import com.jervis.connection.ConnectionRepository
import com.jervis.dto.agentjob.AgentJobFlavor
import com.jervis.dto.agentjob.AgentJobState
import com.jervis.dto.connection.AuthTypeEnum
import com.jervis.git.persistence.GpgCertificateDocument
import com.jervis.git.persistence.GpgCertificateRepository
import com.jervis.project.GitCommitConfig
import com.jervis.project.ProjectDocument
import com.jervis.project.ProjectRepository
import com.jervis.project.ProjectResource
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.EnvVarBuilder
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.SecretKeySelectorBuilder
import io.fabric8.kubernetes.api.model.VolumeBuilder
import io.fabric8.kubernetes.api.model.VolumeMountBuilder
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.api.model.DeletionPropagation
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Entry point for turning a Claude-chat-manager request ("run this as
 * a background Job") into an `AgentJobRecord` + prepared workspace +
 * live K8s Job running the `jervis-coding-agent` image against that
 * workspace.
 *
 * Responsibilities:
 *  1. Persist an `AgentJobRecord` in `QUEUED` state.
 *  2. For CODING flavor, materialise the per-agent `git worktree` via
 *     [AgentWorkspaceService]. Record `workspacePath` + `gitBranch`
 *     back onto the record.
 *  3. Write `$WORKSPACE/.jervis/brief.md` + `CLAUDE.md` so the
 *     in-container entrypoint has full task context on filesystem
 *     (bypasses ARG_MAX — descriptions can be kilobytes long).
 *  4. Create a fabric8 Job manifest injecting:
 *       - plain env from per-dispatch config (git identity, GPG,
 *         connection credentials, MCP URL)
 *       - secretKeyRef env from `jervis-secrets` (Claude auth,
 *         MCP bearer token)
 *  5. Flip record to RUNNING with the K8s Job name recorded.
 *
 * Explicitly NOT here yet:
 *  - `abort(jobId)` + status queries + K8s watcher reconciliation (3b.4)
 *  - Non-CODING flavors (analysis / research etc. delegate later)
 */
@Service
class AgentJobDispatcher(
    private val agentJobRecordRepository: AgentJobRecordRepository,
    private val agentWorkspaceService: AgentWorkspaceService,
    private val projectRepository: ProjectRepository,
    private val clientRepository: ClientRepository,
    private val connectionRepository: ConnectionRepository,
    private val gpgCertificateRepository: GpgCertificateRepository,
    @Value("\${jervis.mcp.url:http://jervis-mcp:8100}")
    private val mcpServerUrl: String,
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

        // Secret / ConfigMap references. Keys must match k8s/secret-manifest.sh
        // and k8s/configmap.yaml — changes here must land there too.
        private const val SECRETS_NAME = "jervis-secrets"
        private const val SECRET_KEY_CLAUDE_OAUTH = "CLAUDE_CODE_OAUTH_TOKEN"
        private const val SECRET_KEY_ANTHROPIC_API = "ANTHROPIC_API_KEY"
        // The secret key is pluralised (`MCP_API_TOKENS`) — value may be a
        // comma-separated list. Entrypoint picks the first one at runtime.
        private const val SECRET_KEY_MCP_TOKENS = "MCP_API_TOKENS"

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
     * Abort a running (or queued) agent job.
     *
     *  1. Delete the K8s Job (propagation=Background so the Pod is
     *     terminated and cleaned asynchronously — we don't need to wait).
     *  2. Release the per-job worktree (silently no-op if the path is
     *     already gone, e.g. Job never materialised).
     *  3. Transition the record to CANCELLED with `errorMessage=reason`.
     *
     * Idempotent: calling abort on a terminal record is a no-op returning
     * the record unchanged.
     *
     * @throws NoSuchElementException if the job id is unknown.
     */
    suspend fun abort(jobId: AgentJobId, reason: String): AgentJobRecord {
        require(reason.isNotBlank()) { "reason must not be blank" }
        val record = agentJobRecordRepository.getById(jobId)
            ?: throw NoSuchElementException("Agent job $jobId not found")

        if (record.state.isTerminal()) {
            logger.info { "abort | job=${record.id} already terminal state=${record.state} — no-op" }
            return record
        }

        // Best-effort K8s Job delete — cluster may have already reaped it,
        // that's fine. A failure here should not block the state transition.
        record.kubernetesJobName?.let { jobName ->
            runCatching { deleteK8sJob(jobName) }.onFailure { e ->
                logger.warn(e) { "abort | K8s delete of $jobName failed (continuing)" }
            }
        }

        // Workspace release — needs the project to locate the worktree.
        record.projectId?.let { pid ->
            runCatching {
                val project = projectRepository.getById(pid)
                    ?: error("Project $pid not found")
                agentWorkspaceService.releaseWorktreeForJob(project, record.id)
            }.onFailure { e ->
                logger.warn(e) { "abort | workspace release failed for job=${record.id} (continuing)" }
            }
        }

        val updated = record.copy(
            state = AgentJobState.CANCELLED,
            errorMessage = reason,
            completedAt = Instant.now(),
        )
        logger.info { "abort | job=${record.id} → CANCELLED reason='$reason'" }
        return agentJobRecordRepository.save(updated)
    }

    /**
     * Snapshot of a record + current K8s Job phase. Does not mutate state —
     * pure read path for MCP `get_agent_job_status` and debug tools.
     */
    suspend fun getStatus(jobId: AgentJobId): AgentJobStatusSnapshot {
        val record = agentJobRecordRepository.getById(jobId)
            ?: throw NoSuchElementException("Agent job $jobId not found")
        val phase = record.kubernetesJobName?.let { queryK8sJobPhase(it) }
        return AgentJobStatusSnapshot(record = record, kubernetesJobPhase = phase)
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

        // Resolve per-dispatch settings (client/project git config, GPG, push creds)
        // before writing workspace prep + K8s Job manifest. Each lookup may return
        // null — entrypoint handles missing values gracefully (e.g. commits remain
        // unsigned, workspace retains whatever credentials.helper already holds).
        val client = record.clientId?.let { clientRepository.getById(it) }
        val gitConfig = resolveEffectiveGitConfig(client, project)
        val gpgCert = gitConfig?.takeIf { it.gpgSign }?.let { cfg ->
            loadGpgCertificate(cfg.gpgKeyId, record.clientId)
        }
        val pushConnection = runCatching {
            connectionRepository.getById(ConnectionId(resource.connectionId))
        }.onFailure { e ->
            logger.warn(e) { "dispatchCoding | connection lookup failed for resource=${resource.id}" }
        }.getOrNull()

        return try {
            writeWorkspacePrep(afterWorktree, worktreePath)
            val jobName = createKubernetesJob(afterWorktree, gitConfig, gpgCert, pushConnection)
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
     * Git commit config resolution matches the orchestrator's existing
     * rule (see `AgentOrchestratorService.buildProjectRules`): project
     * overrides client. `null` means neither layer supplied one — in
     * that case the entrypoint skips `git config --global user.*` and
     * git will fall back to whatever the worktree already has.
     */
    private fun resolveEffectiveGitConfig(
        client: ClientDocument?,
        project: ProjectDocument,
    ): GitCommitConfig? = project.gitCommitConfig ?: client?.gitCommitConfig

    /**
     * GPG lookup hierarchy (only consulted when `gitConfig.gpgSign=true`):
     *   1. Explicit `gpgKeyId` from GitCommitConfig → `findFirstByKeyId`
     *   2. Any key belonging to the client → `findFirstByClientId`
     *   3. Any key in the DB, most recent first → `findAllByOrderByCreatedAtDesc`
     *
     * Matches `GpgCertificateRpcImpl.getActiveKey` precedence so server
     * and agent agree on which key is "active" for a given job.
     */
    private suspend fun loadGpgCertificate(
        preferredKeyId: String?,
        clientId: ClientId?,
    ): GpgCertificateDocument? {
        if (!preferredKeyId.isNullOrBlank()) {
            gpgCertificateRepository.findFirstByKeyId(preferredKeyId)?.let { return it }
            logger.warn { "loadGpgCertificate | gpgKeyId=$preferredKeyId not found — falling back" }
        }
        if (clientId != null) {
            gpgCertificateRepository.findFirstByClientId(clientId.toString())?.let { return it }
        }
        // Last-resort fallback — pick whatever key exists. For single-tenant
        // deployments this is usually the right one. For multi-tenant this
        // logs a warning so operators notice missing client-scoped keys.
        return runCatching {
            var first: GpgCertificateDocument? = null
            gpgCertificateRepository.findAllByOrderByCreatedAtDesc().collect { doc ->
                if (first == null) first = doc
            }
            first
        }.getOrNull()
    }

    /**
     * Write `.jervis/brief.md` (full task description) and `.jervis/CLAUDE.md`
     * (system prompt) into the agent's workspace. The entrypoint then passes
     * these to `claude --print --append-system-prompt <CLAUDE.md> <brief.md>`.
     *
     * Global gitignore in the agent container (see entrypoint-coding.sh)
     * excludes `.jervis/`, so these files never land in commits.
     */
    private suspend fun writeWorkspacePrep(record: AgentJobRecord, worktreePath: Path) {
        withContext(Dispatchers.IO) {
            val jervisDir = worktreePath.resolve(".jervis")
            jervisDir.createDirectories()

            jervisDir.resolve("brief.md").writeText(record.description)
            jervisDir.resolve("CLAUDE.md").writeText(buildSystemPrompt(record))
            // result.json intentionally NOT pre-created — entrypoint owns
            // that file, and its existence is a signal to the watcher that
            // the agent already produced a terminal result.
            if (Files.notExists(jervisDir.resolve(".gitignore"))) {
                jervisDir.resolve(".gitignore").writeText("*\n")
            }
        }
    }

    private fun buildSystemPrompt(record: AgentJobRecord): String = buildString {
        appendLine("# Jervis coding agent brief")
        appendLine()
        appendLine("You are running as a background coding agent for agent-job `${record.id}`.")
        appendLine("Your workspace is a per-job git worktree; you have full tool access.")
        appendLine()
        appendLine("Branch: `${record.gitBranch.orEmpty()}` (already checked out).")
        record.clientId?.let { appendLine("Client: `$it`") }
        record.projectId?.let { appendLine("Project: `$it`") }
        record.resourceId?.let { appendLine("Resource: `$it`") }
        appendLine()
        appendLine("## Workflow")
        appendLine()
        appendLine("1. Read `.jervis/brief.md` for the full task description.")
        appendLine("2. Implement the change directly in the workspace.")
        appendLine("3. Commit on this branch (signed if GPG is configured).")
        appendLine("4. `git push` to the remote of this branch.")
        appendLine("5. Call MCP tool `mcp__jervis-mcp__report_done` with a short summary.")
        appendLine()
        appendLine("## Rules")
        appendLine()
        appendLine("- Do NOT open a pull request — the user reviews the pushed branch manually.")
        appendLine("- Do NOT commit files under `.jervis/` (global gitignore).")
        appendLine("- Do NOT switch branches; all work happens on `${record.gitBranch.orEmpty()}`.")
        appendLine("- If the task is impossible, call `report_done` with `success=false` and an explanation.")
    }

    /**
     * Build and create the K8s Job manifest for a CODING-flavor record.
     * Injects plain env (git identity, GPG, connection credentials, MCP
     * URL) plus secretKeyRef env from `jervis-secrets` (Claude auth +
     * MCP bearer token).
     */
    private suspend fun createKubernetesJob(
        record: AgentJobRecord,
        gitConfig: GitCommitConfig?,
        gpgCert: GpgCertificateDocument?,
        pushConnection: ConnectionDocument?,
    ): String {
        val jobName = codingJobName(record.id)

        val envVars = buildAllEnv(record, gitConfig, gpgCert, pushConnection)
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

    private fun buildAllEnv(
        record: AgentJobRecord,
        gitConfig: GitCommitConfig?,
        gpgCert: GpgCertificateDocument?,
        pushConnection: ConnectionDocument?,
    ): List<EnvVar> {
        val result = mutableListOf<EnvVar>()

        // --- Job metadata ---
        result += plain("AGENT_JOB_ID", record.id.toString())
        result += plain("AGENT_FLAVOR", record.flavor.name)
        result += plain("WORKSPACE", record.workspacePath.orEmpty())
        result += plain("EXPECTED_BRANCH", record.gitBranch.orEmpty())
        result += plain("CLIENT_ID", record.clientId?.toString().orEmpty())
        result += plain("PROJECT_ID", record.projectId?.toString().orEmpty())
        result += plain("RESOURCE_ID", record.resourceId.orEmpty())
        result += plain("DISPATCHED_BY", record.dispatchedBy)

        // --- Git identity (committerX > authorX; null if neither config level set it) ---
        gitConfig?.let { cfg ->
            val name = cfg.committerName ?: cfg.authorName
            val email = cfg.committerEmail ?: cfg.authorEmail
            if (!name.isNullOrBlank()) result += plain("GIT_USER_NAME", name)
            if (!email.isNullOrBlank()) result += plain("GIT_USER_EMAIL", email)
        }

        // --- GPG (only when gitConfig.gpgSign=true AND a key was resolved) ---
        gpgCert?.let { cert ->
            result += plain("GPG_KEY_ID", cert.keyId)
            result += plain("GPG_PRIVATE_KEY", cert.privateKeyArmored)
            cert.passphrase?.takeIf { it.isNotBlank() }?.let {
                result += plain("GPG_PASSPHRASE", it)
            }
        }

        // --- Git push credentials (entrypoint writes ~/.git-credentials from these) ---
        pushConnection?.let { conn ->
            when (conn.authType) {
                AuthTypeEnum.BASIC -> {
                    conn.username?.let { result += plain("GIT_CREDENTIALS_USERNAME", it) }
                    conn.password?.let { result += plain("GIT_CREDENTIALS_PASSWORD", it) }
                }
                AuthTypeEnum.BEARER, AuthTypeEnum.OAUTH2 -> {
                    conn.bearerToken?.let {
                        // GitHub / Gitea / most hosts accept `x-access-token:<PAT>`
                        // for HTTPS push. Username is a placeholder — the PAT is
                        // what actually authenticates.
                        result += plain("GIT_CREDENTIALS_USERNAME", conn.username ?: "x-access-token")
                        result += plain("GIT_CREDENTIALS_PASSWORD", it)
                    }
                }
                AuthTypeEnum.NONE -> Unit
            }
            conn.gitRemoteUrl?.takeIf { it.isNotBlank() }?.let {
                result += plain("GIT_REMOTE_URL", it)
            }
        }

        // --- MCP client config (entrypoint renders /tmp/mcp.json from these) ---
        result += plain("MCP_SERVER_URL", mcpServerUrl)

        // --- Secrets (pulled from jervis-secrets at Pod scheduling time) ---
        result += secret("CLAUDE_CODE_OAUTH_TOKEN", SECRET_KEY_CLAUDE_OAUTH)
        result += secret("ANTHROPIC_API_KEY", SECRET_KEY_ANTHROPIC_API)
        // Exposed to the container as MCP_API_TOKEN (singular) for clarity —
        // the secret key remains `MCP_API_TOKENS` because that is what the
        // MCP server itself reads. Entrypoint handles comma-split if needed.
        result += secret("MCP_API_TOKEN", SECRET_KEY_MCP_TOKENS)

        return result
    }

    private fun plain(name: String, value: String): EnvVar =
        EnvVarBuilder().withName(name).withValue(value).build()

    private fun secret(envName: String, secretKey: String): EnvVar =
        EnvVarBuilder()
            .withName(envName)
            .withValueFrom(
                EnvVarSourceBuilder()
                    .withSecretKeyRef(
                        SecretKeySelectorBuilder()
                            .withName(SECRETS_NAME)
                            .withKey(secretKey)
                            .build(),
                    )
                    .build(),
            )
            .build()

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
     * Delete a K8s Job by name. Uses `BACKGROUND` propagation — the
     * API call returns immediately, and the garbage collector removes
     * the owned Pods asynchronously. Callers should not block on Pod
     * termination (the watcher handles any late state).
     */
    private suspend fun deleteK8sJob(jobName: String) {
        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                client.batch().v1().jobs()
                    .inNamespace(NAMESPACE)
                    .withName(jobName)
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                    .delete()
            }
        }
        logger.info { "deleteK8sJob | $jobName deleted (propagation=background)" }
    }

    /**
     * Map a K8s Job's observed status to a human phase string.
     * Returns null when the Job object itself is not found.
     *
     * K8s `Job.status` is condition-based, not a single enum:
     *  - `status.conditions[].type == Complete` → "Succeeded"
     *  - `status.conditions[].type == Failed`   → "Failed"
     *  - `status.conditions[].type == Suspended` → "Suspended"
     *  - otherwise, if `active > 0`              → "Active"
     *  - else                                    → "Pending"
     */
    internal suspend fun queryK8sJobPhase(jobName: String): String? =
        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                val job = client.batch().v1().jobs()
                    .inNamespace(NAMESPACE)
                    .withName(jobName)
                    .get() ?: return@use null
                val conditions = job.status?.conditions.orEmpty()
                val completed = conditions.any { it.type == "Complete" && it.status == "True" }
                val failed = conditions.any { it.type == "Failed" && it.status == "True" }
                val suspended = conditions.any { it.type == "Suspended" && it.status == "True" }
                when {
                    completed -> "Succeeded"
                    failed -> "Failed"
                    suspended -> "Suspended"
                    (job.status?.active ?: 0) > 0 -> "Active"
                    else -> "Pending"
                }
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
