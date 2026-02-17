package com.jervis.configuration

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.Json
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
class PythonOrchestratorClient(baseUrl: String) {

    private val apiBaseUrl = baseUrl.trimEnd('/')
    val circuitBreaker = CircuitBreaker()

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
     * Start orchestration workflow (blocking – waits for completion).
     */
    suspend fun orchestrate(request: OrchestrateRequestDto): OrchestrateResponseDto {
        logger.info { "PYTHON_ORCHESTRATOR_CALL: taskId=${request.taskId} query='${request.query.take(100)}'" }
        val response: OrchestrateResponseDto = client.post("$apiBaseUrl/orchestrate") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        logger.info { "PYTHON_ORCHESTRATOR_RESULT: taskId=${request.taskId} success=${response.success}" }
        return response
    }

    /**
     * Start orchestration with SSE streaming.
     * Returns thread_id for subscribing to /stream/{thread_id}.
     *
     * Returns null if Python orchestrator is busy (HTTP 429).
     * The caller should skip dispatch and let BackgroundEngine retry later.
     */
    suspend fun orchestrateStream(request: OrchestrateRequestDto): StreamStartResponseDto? {
        logger.info { "PYTHON_ORCHESTRATOR_STREAM_START: taskId=${request.taskId}" }
        return try {
            val response = client.post("$apiBaseUrl/orchestrate/stream") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            if (response.status.value == 429) {
                logger.info { "PYTHON_ORCHESTRATOR_BUSY: orchestrator returned 429, skipping dispatch" }
                // 429 = busy, not a failure — don't trip circuit breaker
                return null
            }
            circuitBreaker.recordSuccess()
            response.body()
        } catch (e: Exception) {
            circuitBreaker.recordFailure()
            throw e
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
        chatHistory: ChatHistoryPayloadDto? = null,
    ) {
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE: threadId=$threadId approved=$approved chatHistory=${chatHistory != null}" }
        val response = client.post("$apiBaseUrl/approve/$threadId") {
            contentType(ContentType.Application.Json)
            setBody(ApprovalResponseDto(approved = approved, reason = reason, chatHistory = chatHistory))
        }
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE_SENT: threadId=$threadId status=${response.status}" }
    }

    /**
     * Cancel a running orchestration.
     */
    suspend fun cancelOrchestration(threadId: String) {
        logger.info { "PYTHON_ORCHESTRATOR_CANCEL: threadId=$threadId" }
        client.post("$apiBaseUrl/cancel/$threadId") {
            contentType(ContentType.Application.Json)
        }
    }

    /**
     * Resume a paused orchestration from checkpoint.
     */
    suspend fun resume(threadId: String): OrchestrateResponseDto {
        logger.info { "PYTHON_ORCHESTRATOR_RESUME: threadId=$threadId" }
        return client.post("$apiBaseUrl/resume/$threadId") {
            contentType(ContentType.Application.Json)
        }.body()
    }

    /**
     * Get the status of an orchestration thread.
     * Polled by BackgroundEngine.runOrchestratorResultLoop().
     *
     * Returns a map with "status" key: running, interrupted, done, error, unknown.
     */
    suspend fun getStatus(threadId: String): Map<String, String> {
        return try {
            client.get("$apiBaseUrl/status/$threadId").body()
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_STATUS_FAIL: threadId=$threadId ${e.message}" }
            throw e
        }
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
    suspend fun interrupt(threadId: String): Boolean {
        return try {
            logger.info { "PYTHON_ORCHESTRATOR_INTERRUPT: threadId=$threadId" }
            val response: JsonObject = client.post("$apiBaseUrl/interrupt/$threadId") {
                contentType(ContentType.Application.Json)
            }.body()

            val success = response["success"]?.toString()?.trim('"')?.toBoolean() ?: false

            if (success) {
                logger.info { "PYTHON_ORCHESTRATOR_INTERRUPT_SUCCESS: threadId=$threadId" }
            } else {
                val error = response["error"]?.toString()?.trim('"') ?: "unknown"
                logger.warn { "PYTHON_ORCHESTRATOR_INTERRUPT_FAILED: threadId=$threadId error=$error" }
            }

            success
        } catch (e: Exception) {
            logger.error(e) { "PYTHON_ORCHESTRATOR_INTERRUPT_ERROR: threadId=$threadId ${e.message}" }
            false
        }
    }

    /**
     * Health check.
     */
    suspend fun isHealthy(): Boolean {
        if (!circuitBreaker.allowRequest()) {
            logger.debug { "PYTHON_ORCHESTRATOR_HEALTH_CIRCUIT_OPEN: fast-fail, no HTTP call" }
            return false
        }
        return try {
            val response: JsonObject = client.get("$apiBaseUrl/health").body()
            val healthy = response["status"]?.toString()?.trim('"') == "ok"
            if (healthy) circuitBreaker.recordSuccess() else circuitBreaker.recordFailure()
            healthy
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_HEALTH_FAIL: ${e.message}" }
            circuitBreaker.recordFailure()
            false
        }
    }

    /**
     * SSE stream URL for a given thread.
     */
    fun streamUrl(threadId: String): String = "$apiBaseUrl/stream/$threadId"

    /**
     * Compress chat history into a summary.
     * Used by ChatHistoryService for incremental chat compression.
     */
    suspend fun compressChat(request: CompressChatRequestDto): CompressChatResponseDto {
        logger.info { "PYTHON_ORCHESTRATOR_COMPRESS_CHAT: taskId=${request.taskId} messages=${request.messages.size}" }
        return client.post("$apiBaseUrl/compress-chat") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

}

// --- DTOs for Python Orchestrator ---

@Serializable
data class OrchestrateRequestDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("workspace_path") val workspacePath: String,
    val query: String,
    @SerialName("agent_preference") val agentPreference: String = "auto",
    val rules: ProjectRulesDto = ProjectRulesDto(),
    val environment: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("jervis_project_id") val jervisProjectId: String? = null,
    @SerialName("chat_history") val chatHistory: ChatHistoryPayloadDto? = null,
    @SerialName("processing_mode") val processingMode: String = "FOREGROUND",
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
    @SerialName("stream_url") val streamUrl: String,
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

// --- Chat Compression DTOs ---

@Serializable
data class CompressChatRequestDto(
    val messages: List<ChatHistoryMessageDto>,
    @SerialName("previous_summary") val previousSummary: String? = null,
    @SerialName("client_id") val clientId: String,
    @SerialName("task_id") val taskId: String,
)

@Serializable
data class CompressChatResponseDto(
    val summary: String,
    @SerialName("key_decisions") val keyDecisions: List<String> = emptyList(),
    val topics: List<String> = emptyList(),
    @SerialName("is_checkpoint") val isCheckpoint: Boolean = false,
    @SerialName("checkpoint_reason") val checkpointReason: String? = null,
)
