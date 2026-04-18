package com.jervis.agent

import com.jervis.agent.PythonOrchestratorClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Simple in-memory circuit breaker for Python orchestrator health checks.
 *
 * States:
 * - CLOSED: normal operation, track consecutive failures
 * - OPEN: after [failureThreshold] consecutive failures, immediately return false (no HTTP call)
 * - HALF_OPEN: after [openDurationMs] in OPEN, allow 1 probe request
 *   - Success → CLOSED; Failure → back to OPEN
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openDurationMs: Long = 30_000,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val state = AtomicReference(State.CLOSED)
    private val consecutiveFailures = AtomicInteger(0)
    private val openedAt = AtomicLong(0)

    fun currentState(): State {
        if (state.get() == State.OPEN) {
            val elapsed = System.currentTimeMillis() - openedAt.get()
            if (elapsed >= openDurationMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    logger.info { "CIRCUIT_BREAKER: OPEN → HALF_OPEN (probe allowed after ${elapsed}ms)" }
                }
            }
        }
        return state.get()
    }

    fun allowRequest(): Boolean = currentState() != State.OPEN

    fun recordSuccess() {
        val prev = state.getAndSet(State.CLOSED)
        consecutiveFailures.set(0)
        if (prev != State.CLOSED) {
            logger.info { "CIRCUIT_BREAKER: $prev → CLOSED (success)" }
        }
    }

    fun recordFailure() {
        val count = consecutiveFailures.incrementAndGet()
        if (count >= failureThreshold && state.get() == State.CLOSED) {
            state.set(State.OPEN)
            openedAt.set(System.currentTimeMillis())
            logger.warn { "CIRCUIT_BREAKER: CLOSED → OPEN (failures=$count)" }
        } else if (state.get() == State.HALF_OPEN) {
            state.set(State.OPEN)
            openedAt.set(System.currentTimeMillis())
            logger.warn { "CIRCUIT_BREAKER: HALF_OPEN → OPEN (probe failed)" }
        }
    }
}

/**
 * REST client for the Python Orchestrator service.
 *
 * The Python orchestrator (LangGraph) handles:
 * - Task decomposition into goals
 * - Coding step planning
 * - K8s Job execution (coding agents)
 * - Evaluation and git operations
 * - Approval flow (commit/push)
 *
 * This client is used by AgentOrchestratorService to delegate complex
 * coding workflows to the Python service.
 */
class PythonOrchestratorClient(
    baseUrl: String,
    private val controlGrpc: com.jervis.infrastructure.grpc.OrchestratorControlGrpcClient? = null,
    private val graphGrpc: com.jervis.infrastructure.grpc.OrchestratorGraphGrpcClient? = null,
    private val dispatchGrpc: com.jervis.infrastructure.grpc.OrchestratorDispatchGrpcClient? = null,
) {

    private val apiBaseUrl = baseUrl.trimEnd('/')
    val circuitBreaker = CircuitBreaker()

    private fun controlGrpcOrThrow(): com.jervis.infrastructure.grpc.OrchestratorControlGrpcClient =
        controlGrpc ?: error("OrchestratorControlGrpcClient not wired — see RpcClientsConfig")

    private fun graphGrpcOrThrow(): com.jervis.infrastructure.grpc.OrchestratorGraphGrpcClient =
        graphGrpc ?: error("OrchestratorGraphGrpcClient not wired — see RpcClientsConfig")

    private fun dispatchGrpcOrThrow(): com.jervis.infrastructure.grpc.OrchestratorDispatchGrpcClient =
        dispatchGrpc ?: error("OrchestratorDispatchGrpcClient not wired — see RpcClientsConfig")

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE
            connectTimeoutMillis = 30_000   // 30s connect timeout only
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }

    /**
     * Send approval response and resume graph (fire-and-forget).
     *
     * Python endpoint returns immediately with {"status": "resuming"}.
     * The graph resumes in the background – result polled via GET /status/{thread_id}.
     */
    suspend fun approve(
        threadId: String,
        approved: Boolean,
        reason: String? = null,
    ) {
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE: threadId=$threadId approved=$approved" }
        val ack = controlGrpcOrThrow().approve(threadId, approved, reason)
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE_SENT: threadId=$threadId status=${ack.status}" }
    }

    /**
     * Cancel a running orchestration.
     */
    suspend fun cancelOrchestration(threadId: String) {
        logger.info { "PYTHON_ORCHESTRATOR_CANCEL: threadId=$threadId" }
        try {
            controlGrpcOrThrow().cancel(threadId)
        } catch (e: io.grpc.StatusException) {
            // NOT_FOUND is benign — task may have already completed.
            if (e.status.code != io.grpc.Status.Code.NOT_FOUND) {
                logger.warn(e) { "PYTHON_ORCHESTRATOR_CANCEL_FAIL: threadId=$threadId: ${e.message}" }
            }
        }
    }

    /**
     * Get the status of an orchestration thread.
     * Polled by BackgroundEngine.runOrchestratorResultLoop().
     *
     * Returns a map with "status" key: running, interrupted, done, error, unknown,
     * plus additional fields (summary, branch, artifacts, interrupt_action, …).
     */
    suspend fun getStatus(threadId: String): Map<String, String> {
        return try {
            val resp = controlGrpcOrThrow().getStatus(threadId)
            val out = mutableMapOf<String, String>(
                "status" to resp.status,
                "thread_id" to resp.threadId,
            )
            if (resp.interruptAction.isNotEmpty()) out["interrupt_action"] = resp.interruptAction
            if (resp.interruptDescription.isNotEmpty()) out["interrupt_description"] = resp.interruptDescription
            if (resp.error.isNotEmpty()) out["error"] = resp.error
            if (resp.summary.isNotEmpty()) out["summary"] = resp.summary
            if (resp.branch.isNotEmpty()) out["branch"] = resp.branch
            if (resp.artifactsCount > 0) out["artifacts"] = resp.artifactsList.joinToString(",")
            if (resp.keepEnvironmentRunning) out["keep_environment_running"] = "true"
            out
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_STATUS_FAIL: threadId=$threadId ${e.message}" }
            throw e
        }
    }

    /**
     * Get the full TaskGraph for a given task ID.
     *
     * Returns the raw JSON string (Python snake_case format).
     * Caller is responsible for deserialization with @SerialName mappings.
     *
     * For master graph (taskId="master"), pass clientId to get client-filtered view.
     */
    suspend fun getTaskGraph(taskId: String, clientId: String? = null): String? =
        try {
            val resp = graphGrpcOrThrow().getTaskGraph(taskId, clientId)
            if (resp.found) resp.graphJson else null
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_GRAPH_FAIL: taskId=$taskId ${e.message}" }
            null
        }

    /**
     * Run idle maintenance on Python orchestrator.
     *
     * Phase 1 (CPU-only): memory graph cleanup, thinking graph eviction, LQM drain, affair archival.
     * Phase 2 (GPU-light): KB dedup for one client (NORMAL priority, auto-preempted by CRITICAL).
     */
    suspend fun runMaintenance(phase: Int = 1, clientId: String? = null): com.jervis.dto.maintenance.MaintenanceResultDto? =
        try {
            val proto = graphGrpcOrThrow().runMaintenance(phase, clientId)
            com.jervis.dto.maintenance.MaintenanceResultDto(
                phase = proto.phase,
                memRemoved = proto.memRemoved,
                thinkingEvicted = proto.thinkingEvicted,
                lqmDrained = proto.lqmDrained,
                affairsArchived = proto.affairsArchived,
                nextClientForPhase2 = proto.nextClientForPhase2.takeIf { it.isNotEmpty() },
                clientId = proto.clientId.takeIf { it.isNotEmpty() },
                findings = proto.findingsList,
            )
        } catch (e: Exception) {
            logger.warn { "MAINTENANCE_FAIL: phase=$phase ${e.message}" }
            null
        }

    /**
     * Interrupt a running orchestration to allow higher-priority task to run.
     *
     * Sends interrupt request to Python orchestrator, which saves checkpoint to MongoDB
     * and gracefully stops execution. The task can be resumed later from the checkpoint.
     *
     * @param threadId The LangGraph thread ID to interrupt
     * @return true if interrupt was successful, false otherwise
     */
    suspend fun interrupt(threadId: String): Boolean =
        try {
            logger.info { "PYTHON_ORCHESTRATOR_INTERRUPT: threadId=$threadId" }
            val ack = controlGrpcOrThrow().interrupt(threadId)
            if (ack.interrupted) {
                logger.info { "PYTHON_ORCHESTRATOR_INTERRUPT_SUCCESS: threadId=$threadId" }
            } else {
                logger.warn { "PYTHON_ORCHESTRATOR_INTERRUPT_FAILED: threadId=$threadId detail=${ack.detail}" }
            }
            ack.interrupted
        } catch (e: Exception) {
            logger.error(e) { "PYTHON_ORCHESTRATOR_INTERRUPT_ERROR: threadId=$threadId ${e.message}" }
            false
        }

    /**
     * Health check.
     */
    suspend fun isHealthy(): Boolean {
        if (!circuitBreaker.allowRequest()) {
            logger.debug { "PYTHON_ORCHESTRATOR_HEALTH_CIRCUIT_OPEN: fast-fail, no gRPC call" }
            return false
        }
        return try {
            val resp = controlGrpcOrThrow().health()
            val healthy = resp.status == "ok"
            if (healthy) circuitBreaker.recordSuccess() else circuitBreaker.recordFailure()
            healthy
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_HEALTH_FAIL: ${e.message}" }
            circuitBreaker.recordFailure()
            false
        }
    }

    // -----------------------------------------------------------------------
    // Qualification endpoint: LLM agent for smarter routing decisions
    // -----------------------------------------------------------------------

    /**
     * Dispatch qualification request to Python orchestrator — POST /qualify.
     *
     * Fire-and-forget: returns thread_id immediately.
     * Python runs qualification LLM agent with CORE tools, then pushes result
     * to Kotlin via POST /internal/qualification-done.
     *
     * @return QualifyResponseDto with thread_id, or null on error
     */
    suspend fun qualify(request: QualifyRequestDto): QualifyResponseDto? {
        logger.info { "PYTHON_QUALIFY_START: taskId=${request.taskId}" }
        return try {
            val grpc = dispatchGrpcOrThrow()
            val ack = grpc.qualify(request.toProto(grpc.ctx(request.clientId)))
            when (ack.status) {
                "accepted" -> {
                    circuitBreaker.recordSuccess()
                    QualifyResponseDto(threadId = ack.threadId)
                }
                else -> {
                    logger.warn { "PYTHON_QUALIFY_FAIL: status=${ack.status} detail=${ack.detail}" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "PYTHON_QUALIFY_ERROR: taskId=${request.taskId}" }
            null
        }
    }

    // -----------------------------------------------------------------------
    // Background orchestration endpoint
    // -----------------------------------------------------------------------

    /**
     * Background orchestration — POST /orchestrate.
     *
     * Fire-and-forget: returns thread_id immediately.
     * Python runs Graph Agent (vertex/edge DAG decomposition),
     * pushes status to Kotlin via POST /internal/orchestrator-status.
     *
     * @return StreamStartResponseDto with thread_id, or null if busy (429)
     */
    suspend fun orchestrate(request: OrchestrateRequestDto): StreamStartResponseDto? {
        logger.info { "PYTHON_ORCHESTRATE_START: taskId=${request.taskId}" }
        return try {
            val grpc = dispatchGrpcOrThrow()
            val ack = grpc.orchestrate(request.toProto(grpc.ctx(request.clientId)))
            when (ack.status) {
                "busy" -> {
                    logger.info { "PYTHON_ORCHESTRATE_BUSY: orchestrator rejected, skipping dispatch" }
                    null
                }
                "accepted" -> {
                    circuitBreaker.recordSuccess()
                    StreamStartResponseDto(threadId = ack.threadId)
                }
                else -> {
                    circuitBreaker.recordFailure()
                    throw RuntimeException("Orchestrate dispatch failed: ${ack.detail}")
                }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            throw e
        }
    }

}

// --- DTOs for Python Orchestrator ---

@Serializable
data class OrchestrateRequestDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("group_name") val groupName: String? = null,
    @SerialName("workspace_path") val workspacePath: String,
    val query: String,
    @SerialName("agent_preference") val agentPreference: String = "auto",
    val rules: ProjectRulesDto = ProjectRulesDto(),
    val environment: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("environment_id") val environmentId: String? = null,
    @SerialName("jervis_project_id") val jervisProjectId: String? = null,
    @SerialName("chat_history") val chatHistory: ChatHistoryPayloadDto? = null,
    @SerialName("processing_mode") val processingMode: String = "FOREGROUND",
    @SerialName("max_openrouter_tier") val maxOpenRouterTier: String = "NONE",
    @SerialName("qualifier_context") val qualifierContext: String? = null,
    @SerialName("source_urn") val sourceUrn: String? = null,
    @SerialName("task_name") val taskName: String? = null,
    // Urgency metadata forwarded to orchestrator for deadline-aware routing.
    // See KB agent://claude-code/task-routing-unified-design.
    @SerialName("deadline_iso") val deadlineIso: String? = null,
    val priority: String = "NORMAL",
    val capability: String? = null,
    val tier: String? = null,
    @SerialName("min_model_size") val minModelSize: Int = 0,
)

@Serializable
data class ProjectRulesDto(
    @SerialName("branch_naming") val branchNaming: String = "task/{taskId}",
    @SerialName("commit_prefix") val commitPrefix: String = "task({taskId}):",
    @SerialName("require_review") val requireReview: Boolean = false,
    @SerialName("require_tests") val requireTests: Boolean = false,
    @SerialName("require_approval_commit") val requireApprovalCommit: Boolean = true,
    @SerialName("require_approval_push") val requireApprovalPush: Boolean = true,
    @SerialName("allowed_branches") val allowedBranches: List<String> = listOf("task/*", "fix/*"),
    @SerialName("forbidden_files") val forbiddenFiles: List<String> = listOf("*.env", "secrets/*"),
    @SerialName("max_changed_files") val maxChangedFiles: Int = 20,
    @SerialName("auto_push") val autoPush: Boolean = false,
    @SerialName("auto_use_anthropic") val autoUseAnthropic: Boolean = false,
    @SerialName("auto_use_openai") val autoUseOpenai: Boolean = false,
    @SerialName("auto_use_gemini") val autoUseGemini: Boolean = false,
    @SerialName("max_openrouter_tier") val maxOpenRouterTier: String = "NONE",
    // Git commit config (from client/project settings)
    @SerialName("git_author_name") val gitAuthorName: String? = null,
    @SerialName("git_author_email") val gitAuthorEmail: String? = null,
    @SerialName("git_committer_name") val gitCommitterName: String? = null,
    @SerialName("git_committer_email") val gitCommitterEmail: String? = null,
    @SerialName("git_gpg_sign") val gitGpgSign: Boolean = false,
    @SerialName("git_gpg_key_id") val gitGpgKeyId: String? = null,
    @SerialName("git_message_pattern") val gitMessagePattern: String? = null,
)

@Serializable
data class OrchestrateResponseDto(
    @SerialName("task_id") val taskId: String,
    val success: Boolean = false,
    val summary: String,
    val status: String? = null,
    val branch: String? = null,
    val artifacts: List<String> = emptyList(),
    @SerialName("step_results") val stepResults: List<StepResultDto> = emptyList(),
    @SerialName("thread_id") val threadId: String? = null,
    val interrupt: JsonObject? = null,
) {
    /** True when the graph is paused at an interrupt (awaiting approval). */
    val isInterrupted: Boolean get() = status == "interrupted"
}

@Serializable
data class StepResultDto(
    @SerialName("step_index") val stepIndex: Int,
    val success: Boolean,
    val summary: String,
    @SerialName("agent_type") val agentType: String,
    @SerialName("changed_files") val changedFiles: List<String> = emptyList(),
)

@Serializable
data class StreamStartResponseDto(
    @SerialName("thread_id") val threadId: String,
)

@Serializable
data class ApprovalResponseDto(
    val approved: Boolean,
    val modification: String? = null,
    val reason: String? = null,
    @SerialName("chat_history") val chatHistory: ChatHistoryPayloadDto? = null,
)

// --- Chat History DTOs ---

@Serializable
data class ChatHistoryPayloadDto(
    @SerialName("recent_messages") val recentMessages: List<ChatHistoryMessageDto>,
    @SerialName("summary_blocks") val summaryBlocks: List<ChatSummaryBlockDto> = emptyList(),
    @SerialName("total_message_count") val totalMessageCount: Long = 0,
)

@Serializable
data class ChatHistoryMessageDto(
    val role: String,
    val content: String,
    val timestamp: String,
    val sequence: Long,
)

@Serializable
data class ChatSummaryBlockDto(
    @SerialName("sequence_range") val sequenceRange: String,
    val summary: String,
    @SerialName("key_decisions") val keyDecisions: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    @SerialName("is_checkpoint") val isCheckpoint: Boolean = false,
    @SerialName("checkpoint_reason") val checkpointReason: String? = null,
)

// --- Qualification DTOs ---

@Serializable
data class QualifyRequestDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("source_urn") val sourceUrn: String = "",
    @SerialName("max_openrouter_tier") val maxOpenRouterTier: String = "FREE",
    // KB extraction results
    val summary: String = "",
    val entities: List<String> = emptyList(),
    @SerialName("suggested_actions") val suggestedActions: List<String> = emptyList(),
    val urgency: String = "normal",
    @SerialName("action_type") val actionType: String? = null,
    @SerialName("estimated_complexity") val estimatedComplexity: String? = null,
    @SerialName("is_assigned_to_me") val isAssignedToMe: Boolean = false,
    @SerialName("has_future_deadline") val hasFutureDeadline: Boolean = false,
    @SerialName("suggested_deadline") val suggestedDeadline: String? = null,
    // Attachment metadata
    @SerialName("has_attachments") val hasAttachments: Boolean = false,
    @SerialName("attachment_count") val attachmentCount: Int = 0,
    val attachments: List<QualifyAttachmentDto> = emptyList(),
    // KB extra fields
    @SerialName("suggested_agent") val suggestedAgent: String? = null,
    @SerialName("affected_files") val affectedFiles: List<String> = emptyList(),
    @SerialName("related_kb_nodes") val relatedKbNodes: List<String> = emptyList(),
    // Original content
    val content: String = "",
    // Mention detection
    @SerialName("mentions_jervis") val mentionsJervis: Boolean = false,
)

@Serializable
data class QualifyAttachmentDto(
    val filename: String,
    @SerialName("contentType") val contentType: String = "",
    val size: Long = 0,
    val index: Int = 0,
)

@Serializable
data class QualifyResponseDto(
    @SerialName("thread_id") val threadId: String,
)

// --------------------------------------------------------------------------
// DTO → proto converters (no JSON on the wire — see dispatch.proto)
// --------------------------------------------------------------------------

private fun QualifyRequestDto.toProto(
    ctx: com.jervis.contracts.common.RequestContext,
): com.jervis.contracts.orchestrator.QualifyRequest {
    val builder = com.jervis.contracts.orchestrator.QualifyRequest.newBuilder()
        .setCtx(ctx)
        .setTaskId(taskId)
        .setClientId(clientId)
        .setProjectId(projectId ?: "")
        .setGroupId(groupId ?: "")
        .setClientName(clientName ?: "")
        .setProjectName(projectName ?: "")
        .setSourceUrn(sourceUrn)
        .setMaxOpenrouterTier(maxOpenRouterTier)
        .setSummary(summary)
        .addAllEntities(entities)
        .addAllSuggestedActions(suggestedActions)
        .setUrgency(urgency)
        .setActionType(actionType ?: "")
        .setEstimatedComplexity(estimatedComplexity ?: "")
        .setIsAssignedToMe(isAssignedToMe)
        .setHasFutureDeadline(hasFutureDeadline)
        .setSuggestedDeadline(suggestedDeadline ?: "")
        .setHasAttachments(hasAttachments)
        .setAttachmentCount(attachmentCount)
        .setSuggestedAgent(suggestedAgent ?: "")
        .addAllAffectedFiles(affectedFiles)
        .addAllRelatedKbNodes(relatedKbNodes)
        .setContent(content)
        .setMentionsJervis(mentionsJervis)
    for (a in attachments) {
        builder.addAttachments(
            com.jervis.contracts.orchestrator.QualifyAttachment.newBuilder()
                .setFilename(a.filename)
                .setContentType(a.contentType)
                .setSize(a.size)
                .setIndex(a.index)
                .build(),
        )
    }
    return builder.build()
}

private fun OrchestrateRequestDto.toProto(
    ctx: com.jervis.contracts.common.RequestContext,
): com.jervis.contracts.orchestrator.OrchestrateRequest {
    val builder = com.jervis.contracts.orchestrator.OrchestrateRequest.newBuilder()
        .setCtx(ctx)
        .setTaskId(taskId)
        .setClientId(clientId)
        .setProjectId(projectId ?: "")
        .setGroupId(groupId ?: "")
        .setClientName(clientName ?: "")
        .setProjectName(projectName ?: "")
        .setGroupName(groupName ?: "")
        .setWorkspacePath(workspacePath)
        .setQuery(query)
        .setAgentPreference(agentPreference)
        .setTaskName(taskName ?: "")
        .setRules(rules.toProto())
        .setProcessingMode(processingMode)
        .setMaxOpenrouterTier(maxOpenRouterTier)
        .setEnvironmentId(environmentId ?: "")
        .setJervisProjectId(jervisProjectId ?: "")
        .setQualifierContext(qualifierContext ?: "")
        .setSourceUrn(sourceUrn ?: "")
        .setDeadlineIso(deadlineIso ?: "")
        .setPriority(priority)
        .setCapability(capability ?: "")
        .setTier(tier ?: "")
        .setMinModelSize(minModelSize)
    environment?.let { builder.setEnvironment(it.toEnvironmentContextProto()) }
    chatHistory?.let { builder.setChatHistory(it.toProto()) }
    return builder.build()
}

private fun ProjectRulesDto.toProto(): com.jervis.contracts.orchestrator.ProjectRules =
    com.jervis.contracts.orchestrator.ProjectRules.newBuilder()
        .setBranchNaming(branchNaming)
        .setCommitPrefix(commitPrefix)
        .setRequireReview(requireReview)
        .setRequireTests(requireTests)
        .setRequireApprovalCommit(requireApprovalCommit)
        .setRequireApprovalPush(requireApprovalPush)
        .addAllAllowedBranches(allowedBranches)
        .addAllForbiddenFiles(forbiddenFiles)
        .setMaxChangedFiles(maxChangedFiles)
        .setAutoPush(autoPush)
        .setAutoUseAnthropic(autoUseAnthropic)
        .setAutoUseOpenai(autoUseOpenai)
        .setAutoUseGemini(autoUseGemini)
        .setMaxOpenrouterTier(maxOpenRouterTier)
        .setGitAuthorName(gitAuthorName ?: "")
        .setGitAuthorEmail(gitAuthorEmail ?: "")
        .setGitCommitterName(gitCommitterName ?: "")
        .setGitCommitterEmail(gitCommitterEmail ?: "")
        .setGitGpgSign(gitGpgSign)
        .setGitGpgKeyId(gitGpgKeyId ?: "")
        .setGitMessagePattern(gitMessagePattern ?: "")
        .build()

private fun ChatHistoryPayloadDto.toProto(): com.jervis.contracts.orchestrator.ChatHistoryPayload {
    val builder = com.jervis.contracts.orchestrator.ChatHistoryPayload.newBuilder()
        .setTotalMessageCount(totalMessageCount)
    recentMessages.forEach { m ->
        builder.addRecentMessages(
            com.jervis.contracts.orchestrator.ChatHistoryMessage.newBuilder()
                .setRole(m.role)
                .setContent(m.content)
                .setTimestamp(m.timestamp)
                .setSequence(m.sequence)
                .build(),
        )
    }
    summaryBlocks.forEach { b ->
        builder.addSummaryBlocks(
            com.jervis.contracts.orchestrator.ChatSummaryBlock.newBuilder()
                .setSequenceRange(b.sequenceRange)
                .setSummary(b.summary)
                .addAllKeyDecisions(b.keyDecisions)
                .addAllTopics(b.topics)
                .setIsCheckpoint(b.isCheckpoint)
                .setCheckpointReason(b.checkpointReason ?: "")
                .build(),
        )
    }
    return builder.build()
}

/**
 * Convert the environment JsonObject (produced by
 * EnvironmentMapper.toAgentContextJson) into the typed EnvironmentContext
 * proto. The consumer on the Python side unpacks this back into a dict
 * with the same keys (see grpc_server.py::_environment_from_proto).
 */
private fun kotlinx.serialization.json.JsonObject.toEnvironmentContextProto():
    com.jervis.contracts.orchestrator.EnvironmentContext {
    fun str(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    val builder = com.jervis.contracts.orchestrator.EnvironmentContext.newBuilder()
        .setId(str("id"))
        .setNamespace(str("namespace"))
        .setTier(str("tier"))
        .setState(str("state"))
        .setGroupId(str("groupId"))
        .setAgentInstructions(str("agentInstructions"))

    val components = this["components"] as? kotlinx.serialization.json.JsonArray
    components?.forEach { el ->
        val c = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
        fun cStr(key: String): String =
            (c[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        fun cBool(key: String): Boolean =
            (c[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        fun cInt(key: String): Int =
            (c[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0

        val compBuilder = com.jervis.contracts.orchestrator.EnvironmentComponent.newBuilder()
            .setId(cStr("id"))
            .setName(cStr("name"))
            .setType(cStr("type"))
            .setImage(cStr("image"))
            .setProjectId(cStr("projectId"))
            .setHost(cStr("host"))
            .setAutoStart(cBool("autoStart"))
            .setStartOrder(cInt("startOrder"))
            .setSourceRepo(cStr("sourceRepo"))
            .setSourceBranch(cStr("sourceBranch"))
            .setDockerfilePath(cStr("dockerfilePath"))
            .setComponentState(cStr("componentState"))

        (c["ports"] as? kotlinx.serialization.json.JsonArray)?.forEach { pEl ->
            val p = pEl as? kotlinx.serialization.json.JsonObject ?: return@forEach
            compBuilder.addPorts(
                com.jervis.contracts.orchestrator.ComponentPort.newBuilder()
                    .setContainer((p["container"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                    .setService((p["service"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0)
                    .setName((p["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                    .build(),
            )
        }

        (c["envVars"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) ->
            compBuilder.putEnvVars(k, (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
        }

        builder.addComponents(compBuilder.build())
    }

    val links = this["componentLinks"] as? kotlinx.serialization.json.JsonArray
    links?.forEach { el ->
        val l = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
        builder.addComponentLinks(
            com.jervis.contracts.orchestrator.EnvironmentComponentLink.newBuilder()
                .setSource((l["source"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                .setTarget((l["target"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                .setDescription((l["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                .build(),
        )
    }

    return builder.build()
}
