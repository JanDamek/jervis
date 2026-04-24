package com.jervis.agentjob

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jervis.common.types.AgentJobId
import com.jervis.dto.agentjob.AgentJobState
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
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
 * Reconciles `AgentJobRecord`s in non-terminal states against their live
 * Kubernetes Job status. Runs:
 *
 *  - Once at startup (`@PostConstruct`): sweep every QUEUED/RUNNING/
 *    WAITING_USER record. Any RUNNING record whose K8s Job is no longer
 *    reachable is immediately transitioned to ERROR — the orchestrator
 *    must have crashed between creating the Job and observing its
 *    terminal state. The per-scope invariant "RUNNING implies live K8s
 *    Job" is restored synchronously before the first dispatch cycle.
 *
 *  - Continuously thereafter (polling loop, 10s cadence): for every
 *    RUNNING record, look up the Job's phase and either keep it
 *    RUNNING (Active / Pending / Suspended) or flip it to DONE / ERROR
 *    with an authoritative reason.
 *
 * Terminal transitions read the agent-authored `$WORKSPACE/.jervis/
 * result.json` when present (agent signalled its own outcome); fall
 * back to pod logs when the Job Failed before the entrypoint could
 * write a result. Worktree cleanup + base-clone fetch happen after
 * every terminal transition so the next CODING Job sees fresh remote
 * state.
 *
 * Writing is serialised per-record via Mongo version (optimistic update
 * through `save`). The watcher never mutates records in terminal states,
 * so a concurrent `abort` wins if it lands first.
 */
@Service
class AgentJobWatcher(
    private val agentJobRecordRepository: AgentJobRecordRepository,
    private val agentWorkspaceService: AgentWorkspaceService,
    private val agentJobDispatcher: AgentJobDispatcher,
    private val projectRepository: com.jervis.project.ProjectRepository,
) {
    private val logger = KotlinLogging.logger {}
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private val mapper = ObjectMapper()

    @Volatile
    private var pollJob: kotlinx.coroutines.Job? = null

    @PostConstruct
    fun start() {
        scope.launch {
            runCatching { reconcileOnStartup() }
                .onFailure { logger.error(it) { "startup reconcile failed" } }
            pollJob = launch { pollLoop() }
        }
        logger.info { "AgentJobWatcher started (startup reconcile scheduled, poll cadence=${POLL_INTERVAL_MS}ms)" }
    }

    @PreDestroy
    fun stop() {
        logger.info { "AgentJobWatcher stopping" }
        runBlocking {
            runCatching { pollJob?.cancelAndJoin() }
        }
        scope.cancel()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            runCatching { reconcileOnce() }
                .onFailure { logger.warn(it) { "reconcile cycle failed (continuing)" } }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Startup sweep — any RUNNING record whose K8s Job we cannot locate
     * in the cluster is terminalised as ERROR. Without this, a record
     * stuck from a crashed previous pod would block any dispatcher that
     * counts RUNNING records for concurrency gating.
     *
     * QUEUED records are left alone: either their K8s Job was never
     * created (workspace prep in flight) or will be rescheduled by the
     * caller. WAITING_USER records are also untouched — they don't
     * depend on a live K8s Job.
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

        nonTerminal.filter { it.state == AgentJobState.RUNNING }.forEach { record ->
            val jobName = record.kubernetesJobName
            val phase = jobName?.let { agentJobDispatcher.queryK8sJobPhase(it) }
            if (phase == null) {
                markError(
                    record,
                    reason = "Orchestrator restart lost track of K8s Job '${jobName ?: "<none>"}'",
                )
            }
        }
    }

    /**
     * Reconcile every RUNNING record. Called every [POLL_INTERVAL_MS]
     * milliseconds by [pollLoop]. Any single record's failure is logged
     * and skipped so one stuck job can't stall the whole watcher.
     */
    private suspend fun reconcileOnce() {
        val running = agentJobRecordRepository
            .findByStateInOrderByCreatedAtAsc(listOf(AgentJobState.RUNNING))
            .toList()
        if (running.isEmpty()) return

        running.forEach { record ->
            runCatching { reconcileRecord(record) }
                .onFailure { logger.warn(it) { "reconcile job=${record.id} failed" } }
        }
    }

    private suspend fun reconcileRecord(record: AgentJobRecord) {
        val jobName = record.kubernetesJobName
        if (jobName.isNullOrBlank()) {
            // RUNNING without a k8s job name is an impossible state — dispatcher
            // always stores the name before flipping to RUNNING. Treat as ERROR.
            markError(record, reason = "Invariant violation: RUNNING with no kubernetesJobName")
            return
        }
        when (val phase = agentJobDispatcher.queryK8sJobPhase(jobName)) {
            "Succeeded" -> finishSucceeded(record)
            "Failed" -> finishFailed(record)
            null -> markError(
                record,
                reason = "K8s Job '$jobName' not found (likely evicted or TTL-deleted)",
            )
            "Active", "Pending", "Suspended" -> Unit // still running, no-op
            else -> logger.debug { "reconcile | job=${record.id} unknown phase=$phase — no transition" }
        }
    }

    private suspend fun finishSucceeded(record: AgentJobRecord) {
        val result = record.workspacePath?.let { readResultJson(Paths.get(it)) }
        val success = result?.get("success")?.asBoolean(false) ?: false
        val summary = result?.get("summary")?.asText()?.takeIf { it.isNotBlank() }
        val branch = result?.get("branch")?.asText()?.takeIf { it.isNotBlank() }
        val changedFiles = result?.get("changedFiles")?.mapNotNull { it.asText()?.takeIf { f -> f.isNotBlank() } }
            ?: emptyList()

        if (success) {
            val updated = record.copy(
                state = AgentJobState.DONE,
                resultSummary = summary ?: "Claude reported success (no summary provided)",
                gitCommitSha = branch, // branch ref, not sha — filled properly once the git query is added in a later step
                artifacts = changedFiles,
                completedAt = Instant.now(),
            )
            agentJobRecordRepository.save(updated)
            logger.info { "reconcile | job=${record.id} → DONE (${changedFiles.size} files)" }
        } else {
            markError(
                record,
                reason = summary ?: "K8s Job succeeded but agent reported failure (no summary)",
            )
        }
        postTerminalCleanup(record)
    }

    private suspend fun finishFailed(record: AgentJobRecord) {
        // K8s Job failed without the agent writing a result — read pod logs
        // (tail) to give the operator actionable context.
        val logTail = tailPodLogsForJob(record.kubernetesJobName!!)
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
        agentJobRecordRepository.save(updated)
        logger.warn { "reconcile | job=${record.id} → ERROR: $reason" }
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
     * Tail the logs of the most recently scheduled pod belonging to [jobName].
     * Returns null when no pod is found (TTL reaped already).
     */
    private suspend fun tailPodLogsForJob(jobName: String): String? =
        withContext(Dispatchers.IO) {
            buildK8sClient().use { client ->
                val pods = client.pods()
                    .inNamespace(NAMESPACE)
                    .withLabel("job-name", jobName)
                    .list()
                    .items
                    .sortedByDescending { it.metadata?.creationTimestamp }
                val pod = pods.firstOrNull() ?: return@use null
                runCatching {
                    client.pods()
                        .inNamespace(NAMESPACE)
                        .withName(pod.metadata.name)
                        .tailingLines(LOG_TAIL_LINES)
                        .log
                }.getOrNull()
            }
        }

    private fun buildK8sClient(): KubernetesClient {
        val config = ConfigBuilder()
            .withRequestTimeout(30_000)
            .withConnectionTimeout(10_000)
            .build()
        return KubernetesClientBuilder().withConfig(config).build()
    }

    companion object {
        private const val NAMESPACE = "jervis"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val LOG_TAIL_LINES = 80
        private const val LOG_TAIL_CHARS = 2_000
    }
}
