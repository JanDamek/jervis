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
        val response = client.post("$apiBaseUrl/orchestrate/stream") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (response.status.value == 429) {
            logger.info { "PYTHON_ORCHESTRATOR_BUSY: orchestrator returned 429, skipping dispatch" }
            return null
        }
        return response.body()
    }

    /**
     * Send approval response and resume graph (fire-and-forget).
     *
     * Python endpoint returns immediately with {"status": "resuming"}.
     * The graph resumes in the background – result polled via GET /status/{thread_id}.
     */
    suspend fun approve(threadId: String, approved: Boolean, reason: String? = null) {
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE: threadId=$threadId approved=$approved" }
        val response = client.post("$apiBaseUrl/approve/$threadId") {
            contentType(ContentType.Application.Json)
            setBody(ApprovalResponseDto(approved = approved, reason = reason))
        }
        logger.info { "PYTHON_ORCHESTRATOR_APPROVE_SENT: threadId=$threadId status=${response.status}" }
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
            val response: JsonObject = client.get("$apiBaseUrl/health").body()
            response["status"]?.toString()?.trim('"') == "ok"
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_HEALTH_FAIL: ${e.message}" }
            false
        }
    }

    /**
     * Check if the Python orchestrator is currently busy (running an orchestration).
     * Uses the "busy" field from /health endpoint.
     */
    suspend fun isBusy(): Boolean {
        return try {
            val response: JsonObject = client.get("$apiBaseUrl/health").body()
            response["busy"]?.toString() == "true"
        } catch (e: Exception) {
            logger.warn { "PYTHON_ORCHESTRATOR_BUSY_CHECK_FAIL: ${e.message}" }
            false  // Can't reach → not busy (health check will catch this)
        }
    }

    /**
     * SSE stream URL for a given thread.
     */
    fun streamUrl(threadId: String): String = "$apiBaseUrl/stream/$threadId"

    // --- Transcript Correction Agent ---

    /**
     * Submit a correction rule to the correction agent.
     * Stores it in KB as a chunk with kind="transcript_correction".
     */
    suspend fun submitCorrection(request: CorrectionSubmitRequestDto): CorrectionSubmitResultDto {
        logger.info { "CORRECTION_SUBMIT: '${request.original}' -> '${request.corrected}'" }
        return client.post("$apiBaseUrl/correction/submit") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Correct transcript segments using KB-stored corrections + Ollama GPU.
     */
    suspend fun correctTranscript(request: CorrectionRequestDto): CorrectionResultDto {
        logger.info { "CORRECTION_CORRECT: ${request.segments.size} segments" }
        return client.post("$apiBaseUrl/correction/correct") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * List all stored corrections for a client/project.
     */
    suspend fun listCorrections(request: CorrectionListRequestDto): CorrectionListResultDto {
        logger.info { "CORRECTION_LIST: clientId=${request.clientId}" }
        return client.post("$apiBaseUrl/correction/list") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Submit user answers to correction questions as new KB correction rules.
     */
    suspend fun answerCorrectionQuestions(request: CorrectionAnswerRequestDto): Boolean {
        logger.info { "CORRECTION_ANSWER: ${request.answers.size} answers" }
        return try {
            client.post("$apiBaseUrl/correction/answer") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            true
        } catch (e: Exception) {
            logger.error { "CORRECTION_ANSWER_FAIL: ${e.message}" }
            false
        }
    }

    /**
     * Re-correct transcript based on user's natural language instruction.
     */
    suspend fun correctWithInstruction(request: CorrectionInstructRequestDto): CorrectionInstructResultDto {
        logger.info { "CORRECTION_INSTRUCT: ${request.segments.size} segments, instruction='${request.instruction.take(80)}'" }
        return client.post("$apiBaseUrl/correction/instruct") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    /**
     * Delete a correction rule from KB.
     */
    suspend fun deleteCorrection(sourceUrn: String): Boolean {
        logger.info { "CORRECTION_DELETE: $sourceUrn" }
        return try {
            client.post("$apiBaseUrl/correction/delete") {
                contentType(ContentType.Application.Json)
                setBody(CorrectionDeleteRequestDto(sourceUrn))
            }
            true
        } catch (e: Exception) {
            logger.error { "CORRECTION_DELETE_FAIL: $sourceUrn ${e.message}" }
            false
        }
    }
}

// --- Correction Agent DTOs ---

@Serializable
data class CorrectionSubmitRequestDto(
    @SerialName("clientId") val clientId: String,
    @SerialName("projectId") val projectId: String? = null,
    val original: String,
    val corrected: String,
    val category: String = "general",
    val context: String? = null,
)

@Serializable
data class CorrectionSubmitResultDto(
    @SerialName("correctionId") val correctionId: String,
    @SerialName("sourceUrn") val sourceUrn: String,
    val status: String,
)

@Serializable
data class CorrectionRequestDto(
    @SerialName("clientId") val clientId: String,
    @SerialName("projectId") val projectId: String? = null,
    @SerialName("meetingId") val meetingId: String? = null,
    val segments: List<CorrectionSegmentDto>,
    @SerialName("chunkSize") val chunkSize: Int = 20,
)

@Serializable
data class CorrectionSegmentDto(
    val i: Int,
    @SerialName("startSec") val startSec: Double,
    @SerialName("endSec") val endSec: Double,
    val text: String,
    val speaker: String? = null,
)

@Serializable
data class CorrectionResultDto(
    val segments: List<CorrectionSegmentDto>,
    val questions: List<CorrectionQuestionPythonDto> = emptyList(),
    val status: String,
)

@Serializable
data class CorrectionQuestionPythonDto(
    val id: String,
    val i: Int,
    val original: String,
    val question: String,
    val options: List<String> = emptyList(),
    val context: String? = null,
)

@Serializable
data class CorrectionAnswerRequestDto(
    @SerialName("clientId") val clientId: String,
    @SerialName("projectId") val projectId: String? = null,
    val answers: List<CorrectionAnswerItemDto>,
)

@Serializable
data class CorrectionAnswerItemDto(
    val original: String,
    val corrected: String,
    val category: String = "general",
    val context: String? = null,
)

@Serializable
data class CorrectionListRequestDto(
    @SerialName("clientId") val clientId: String,
    @SerialName("projectId") val projectId: String? = null,
    @SerialName("maxResults") val maxResults: Int = 100,
)

@Serializable
data class CorrectionListResultDto(
    val corrections: List<CorrectionChunkDto> = emptyList(),
)

@Serializable
data class CorrectionChunkDto(
    val content: String = "",
    @SerialName("sourceUrn") val sourceUrn: String = "",
    val metadata: CorrectionChunkMetadataDto = CorrectionChunkMetadataDto(),
)

@Serializable
data class CorrectionChunkMetadataDto(
    val original: String = "",
    val corrected: String = "",
    val category: String = "general",
    val context: String = "",
    @SerialName("correctionId") val correctionId: String = "",
)

@Serializable
data class CorrectionDeleteRequestDto(
    @SerialName("sourceUrn") val sourceUrn: String,
)

@Serializable
data class CorrectionInstructRequestDto(
    @SerialName("clientId") val clientId: String,
    @SerialName("projectId") val projectId: String? = null,
    val segments: List<CorrectionSegmentDto>,
    val instruction: String,
)

@Serializable
data class CorrectionInstructResultDto(
    val segments: List<CorrectionSegmentDto> = emptyList(),
    @SerialName("newRules") val newRules: List<CorrectionInstructRuleDto> = emptyList(),
    val status: String,
)

@Serializable
data class CorrectionInstructRuleDto(
    @SerialName("correctionId") val correctionId: String = "",
    @SerialName("sourceUrn") val sourceUrn: String = "",
    val status: String = "",
)

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
    val environment: kotlinx.serialization.json.JsonObject? = null,
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
