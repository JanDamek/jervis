package com.jervis.agentjob

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.dto.agentjob.AgentJobState
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

/**
 * Reconciles `AgentJobRecord`s with their live Kubernetes Job state —
 * **push-based**, no polling loop.
 *
 * Two paths bring a record to a terminal state:
 *
 *  1. **Claude signals done** (primary). The in-container agent calls
 *     MCP `report_agent_done` after commit+push. That lands in
 *     [AgentJobDispatcher.completeFromAgent] and writes DONE / ERROR
 *     directly. This is the push in "push-based communication".
 *
 *  2. **K8s Watch event** (fallback, when the agent crashed before it
 *     could signal). [start] opens a fabric8 `Watch` over Jobs labelled
 *     `managed-by=jervis-coding-agent`. Whenever a Job transitions to
 *     Complete / Failed, the watcher callback terminates the matching
 *     record — same code as the agent-signal path, just sourced from
 *     K8s instead of MCP.
 *
 * Startup reconcile ([reconcileOnStartup]) stays: if the orchestrator
 * pod restarted across a transition we would have missed the Watch
 * event, so at boot we sweep RUNNING records once and flip any whose
 * K8s Job is gone to ERROR. This is one-shot, not a loop.
 *
 * Rationale for avoiding a polling loop: project-wide rule "NEVER hard
 * timeouts — stream + heartbeat, push-based communication". A 10 s
 * reconcile poll is just a disguised hard timeout. K8s `informer`/
 * `Watch` is the native push channel; we use it.
 */
@Service
class AgentJobWatcher(
    private val agentJobRecordRepository: AgentJobRecordRepository,
    private val agentWorkspaceService: AgentWorkspaceService,
    private val projectRepository: com.jervis.project.ProjectRepository,
    private val agentJobDispatcher: AgentJobDispatcher,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private val mapper = ObjectMapper()

    @Volatile
    private var k8sClient: KubernetesClient? = null

    @Volatile
    private var watch: Watch? = null

    @PostConstruct
    fun start() {
        scope.launch {
            runCatching { reconcileOnStartup() }
                .onFailure { logger.error(it) { "startup reconcile failed" } }
            runCatching { openWatch() }
                .onFailure { logger.error(it) { "K8s Job watch open failed" } }
        }
        logger.info { "AgentJobWatcher started (push-based: K8s Watch + MCP report_agent_done)" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "AgentJobWatcher stopping" }
        runCatching { watch?.close() }
        runCatching { k8sClient?.close() }
        scope.cancel()
    }

    /**
     * One-shot startup sweep. After pod restart any RUNNING record whose
     * Job is no longer in the cluster is terminalised as ERROR so the
     * invariant "RUNNING ⇒ live K8s Job" holds before the first new
     * dispatch.
     */
    private suspend fun reconcileOnStartup() {
        val nonTerminal = agentJobRecordRepository
            .findByStateInOrderByCreatedAtAsc(
                listOf(AgentJobState.QUEUED, AgentJobState.RUNNING, AgentJobState.WAITING_USER),
            )
            .toList()

        if (nonTerminal.isEmpty()) {
            logger.info { "startup reconcile | no non-terminal records" }
            return
        }
        logger.info { "startup reconcile | ${nonTerminal.size} non-terminal records — sweeping" }

        val client = ensureClient()
        for (record in nonTerminal) {
            when (record.state) {
                AgentJobState.RUNNING -> {
                    val jobName = record.kubernetesJobName
                    val job = jobName?.let {
                        withContext(Dispatchers.IO) {
                            client.batch().v1().jobs().inNamespace(NAMESPACE).withName(it).get()
                        }
                    }
                    when {
                        job == null -> markError(
                            record,
                            reason = "Orchestrator restart lost track of K8s Job '${jobName ?: "<none>"}'",
                        )
                        isComplete(job) -> finishSucceeded(record)
                        isFailed(job) -> finishFailed(record, job)
                        // Active / Pending / Suspended — the Watch will deliver the
                        // subsequent transition; leave the record alone.
                    }
                }
                AgentJobState.QUEUED -> {
                    // QUEUED with no kubernetesJobName means dispatch crashed
                    // before it could create the K8s Job. The record is
                    // permanently stuck — no Watch event will ever fire.
                    // QUEUED with kubernetesJobName means dispatch crashed
                    // between Job create and state save; the Watch will
                    // deliver the eventual terminal transition.
                    if (record.kubernetesJobName.isNullOrBlank()) {
                        markError(
                            record,
                            reason = "Stuck QUEUED on startup — dispatch never created K8s Job",
                        )
                    }
                }
                AgentJobState.WAITING_USER -> {
                    // User must resolve via UI / approval flow. Leave alone.
                }
                else -> {
                    // Terminal states excluded by the query filter; unreachable.
                }
            }
        }
    }

    /**
     * Open a long-lived [Watch] over `Job` objects labelled as coding
     * agents. Each transition to Complete / Failed routes through
     * [onJobTerminal]. When the K8s API closes the watch (network blip,
     * apiserver restart), we re-open from the scope's coroutine so the
     * push channel heals itself without needing a client-side retry
     * timer.
     */
    private fun openWatch() {
        val client = ensureClient()
        watch = client.batch().v1().jobs()
            .inNamespace(NAMESPACE)
            .withLabel(MANAGED_BY_KEY, MANAGED_BY_VALUE)
            .watch(object : Watcher<Job> {
                override fun eventReceived(action: Watcher.Action, job: Job) {
                    val jobName = job.metadata?.name ?: return
                    if (action != Watcher.Action.MODIFIED && action != Watcher.Action.ADDED) return
                    // Only act on terminal transitions — MODIFIED events
                    // also fire on every small status delta while the
                    // Job is running. isComplete/isFailed give us a
                    // cheap idempotent edge detector.
                    if (!isComplete(job) && !isFailed(job)) return
                    scope.launch {
                        runCatching { onJobTerminal(jobName, job) }
                            .onFailure { logger.warn(it) { "watch | onJobTerminal failed for $jobName" } }
                    }
                }

                override fun onClose(cause: WatcherException?) {
                    if (cause != null) {
                        logger.warn(cause) { "K8s Job watch closed unexpectedly — reopening" }
                    } else {
                        logger.info { "K8s Job watch closed — reopening" }
                    }
                    // Reopen on the next scope tick. Don't block the
                    // watch thread here; just enqueue a re-subscribe.
                    scope.launch {
                        runCatching { openWatch() }
                            .onFailure { logger.error(it) { "K8s Job watch reopen failed" } }
                    }
                }
            })
        logger.info { "K8s Job watch open | label $MANAGED_BY_KEY=$MANAGED_BY_VALUE ns=$NAMESPACE" }
    }

    private suspend fun onJobTerminal(jobName: String, job: Job) {
        val record = agentJobRecordRepository.findByKubernetesJobName(jobName) ?: run {
            logger.debug { "watch | no record for job=$jobName — ignoring terminal event" }
            return
        }
        if (record.state.isTerminal()) {
            // Agent already signalled via MCP report_agent_done — nothing to do.
            logger.debug { "watch | record ${record.id} already terminal (${record.state})" }
            return
        }
        if (isComplete(job)) {
            finishSucceeded(record)
        } else if (isFailed(job)) {
            finishFailed(record, job)
        }
    }

    /**
     * Terminal transition for a Succeeded K8s Job. Prefers the agent's
     * own `result.json` (the agent writes success=true/false + summary
     * + changedFiles + commitSha from its side). Fallback: treat as
     * DONE with a generic summary.
     */
    private suspend fun finishSucceeded(record: AgentJobRecord) {
        val result = record.workspacePath?.let { readResultJson(Paths.get(it)) }
        val success = result?.get("success")?.asBoolean(false) ?: false
        val summary = result?.get("summary")?.asText()?.takeIf { it.isNotBlank() }
        val commitSha = result?.get("commitSha")?.asText()?.takeIf { it.isNotBlank() }
        val branch = result?.get("branch")?.asText()?.takeIf { it.isNotBlank() }
        val changedFiles = result?.get("changedFiles")?.mapNotNull { it.asText()?.takeIf { f -> f.isNotBlank() } }
            ?: emptyList()

        if (success) {
            val updated = record.copy(
                state = AgentJobState.DONE,
                resultSummary = summary ?: "Claude reported success (no summary provided)",
                gitBranch = branch ?: record.gitBranch,
                gitCommitSha = commitSha,
                artifacts = changedFiles,
                completedAt = Instant.now(),
            )
            agentJobDispatcher.saveAndEmit(updated)
            logger.info { "terminal | job=${record.id} → DONE sha=${commitSha ?: "<none>"} (${changedFiles.size} files)" }
        } else {
            markError(
                record,
                reason = summary ?: "K8s Job succeeded but agent reported failure (no summary)",
            )
        }
        postTerminalCleanup(record)
    }

    /**
     * Terminal transition for a Failed K8s Job (the agent's entrypoint
     * crashed before it could write a structured `result.json`). Pulls
     * a tail of pod logs to give the operator actionable context in
     * `errorMessage`.
     */
    private suspend fun finishFailed(record: AgentJobRecord, @Suppress("UNUSED_PARAMETER") job: Job) {
        val logTail = record.kubernetesJobName?.let { tailPodLogsForJob(it) }
        val snippet = if (logTail.isNullOrBlank()) {
            "K8s Job Failed with no captured pod logs"
        } else {
            "K8s Job Failed. Last pod log lines:\n${logTail.takeLast(LOG_TAIL_CHARS)}"
        }
        markError(record, reason = snippet)
        postTerminalCleanup(record)
    }

    private suspend fun postTerminalCleanup(record: AgentJobRecord) {
        val projectId = record.projectId ?: return
        val project = runCatching { projectRepository.getById(projectId) }.getOrNull() ?: return
        val resource = record.resourceId?.let { rid -> project.resources.firstOrNull { it.id == rid } }
        runCatching { agentWorkspaceService.releaseWorktreeForJob(project, record.id) }
            .onFailure { logger.warn(it) { "postTerminalCleanup | release worktree job=${record.id}" } }
        resource?.let {
            runCatching { agentWorkspaceService.fetchAfterJobCompletion(project, it) }
                .onFailure { logger.warn(it) { "postTerminalCleanup | fetch job=${record.id}" } }
        }
    }

    private suspend fun markError(record: AgentJobRecord, reason: String) {
        val updated = record.copy(
            state = AgentJobState.ERROR,
            errorMessage = reason,
            completedAt = Instant.now(),
        )
        agentJobDispatcher.saveAndEmit(updated)
        logger.warn { "terminal | job=${record.id} → ERROR: $reason" }
    }

    private fun readResultJson(workspacePath: Path): JsonNode? {
        val file = workspacePath.resolve(".jervis").resolve("result.json")
        return if (Files.isRegularFile(file)) {
            runCatching { mapper.readTree(Files.readAllBytes(file)) }
                .onFailure { logger.warn(it) { "readResultJson | parse failed at $file" } }
                .getOrNull()
        } else {
            null
        }
    }

    /**
     * Tail the most recently scheduled pod's logs for a given Job name.
     * Returns null when the pod has already been reaped by K8s TTL.
     */
    private suspend fun tailPodLogsForJob(jobName: String): String? =
        withContext(Dispatchers.IO) {
            val client = ensureClient()
            val pods = client.pods()
                .inNamespace(NAMESPACE)
                .withLabel("job-name", jobName)
                .list()
                .items
                .sortedByDescending { it.metadata?.creationTimestamp }
            val pod = pods.firstOrNull() ?: return@withContext null
            runCatching {
                client.pods()
                    .inNamespace(NAMESPACE)
                    .withName(pod.metadata.name)
                    .tailingLines(LOG_TAIL_LINES)
                    .log
            }.getOrNull()
        }

    private fun ensureClient(): KubernetesClient {
        val existing = k8sClient
        if (existing != null) return existing
        synchronized(this) {
            val stillExisting = k8sClient
            if (stillExisting != null) return stillExisting
            val cfg = ConfigBuilder()
                .withRequestTimeout(30_000)
                .withConnectionTimeout(10_000)
                .build()
            val created = KubernetesClientBuilder().withConfig(cfg).build()
            k8sClient = created
            return created
        }
    }

    companion object {
        private const val NAMESPACE = "jervis"
        private const val MANAGED_BY_KEY = "managed-by"
        private const val MANAGED_BY_VALUE = "jervis-coding-agent"
        private const val LOG_TAIL_LINES = 80
        private const val LOG_TAIL_CHARS = 2_000

        /**
         * Job condition detection. Kubernetes marks a Job terminal via a
         * condition object whose `status` is "True"; `phase` itself is
         * not populated for batch/v1 Jobs. Exposed as a companion helper
         * so both the startup sweep and the Watch callback use the same
         * test.
         */
        fun isComplete(job: Job): Boolean =
            job.status?.conditions.orEmpty()
                .any { it.type == "Complete" && it.status == "True" }

        fun isFailed(job: Job): Boolean =
            job.status?.conditions.orEmpty()
                .any { it.type == "Failed" && it.status == "True" }
    }
}
