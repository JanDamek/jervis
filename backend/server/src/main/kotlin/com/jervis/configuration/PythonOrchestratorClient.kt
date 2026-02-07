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

private val logger = KotlinLogging.logger {}

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
            requestTimeoutMillis = 300_000  // 5 min – orchestration can take long
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
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
     */
    suspend fun orchestrateStream(request: OrchestrateRequestDto): StreamStartResponseDto {
        logger.info { "PYTHON_ORCHESTRATOR_STREAM_START: taskId=${request.taskId}" }
        return client.post("$apiBaseUrl/orchestrate/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Send approval response and resume graph.
     *
     * Returns the orchestration result, which may be another interrupt
     * (e.g., commit approved → push approval required).
     */
    suspend fun approve(threadId: String, approved: Boolean, reason: String? = null): OrchestrateResponseDto {
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE: threadId=$threadId approved=$approved" }
        return client.post("$apiBaseUrl/approve/$threadId") {
            contentType(ContentType.Application.Json)
            setBody(ApprovalResponseDto(approved = approved, reason = reason))
        }.body()
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
     * Health check.
     */
    suspend fun isHealthy(): Boolean {
        return try {
            val response: Map<String, String> = client.get("$apiBaseUrl/health").body()
            response["status"] == "ok"
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_HEALTH_FAIL: ${e.message}" }
            false
        }
    }

    /**
     * SSE stream URL for a given thread.
     */
    fun streamUrl(threadId: String): String = "$apiBaseUrl/stream/$threadId"
}

// --- DTOs for Python Orchestrator ---

@Serializable
data class OrchestrateRequestDto(
    @SerialName("task_id") val taskId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("workspace_path") val workspacePath: String,
    val query: String,
    @SerialName("agent_preference") val agentPreference: String = "auto",
    val rules: ProjectRulesDto = ProjectRulesDto(),
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
)
