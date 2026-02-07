package com.jervis.service.agent.coordinator

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.configuration.OrchestrateRequestDto
import com.jervis.configuration.ProjectRulesDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.mapper.toAgentContextJson
import com.jervis.repository.TaskRepository
import com.jervis.service.background.TaskService
import com.jervis.service.environment.EnvironmentService
import com.jervis.service.preferences.PreferenceService
import com.jervis.service.text.CzechKeyboardNormalizer
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * AgentOrchestratorService – routes requests to either:
 * 1. Python orchestrator (LangGraph) – for coding tasks requiring K8s Jobs, KB access, git ops
 * 2. KoogWorkflowService – for conversational queries, analysis, non-coding tasks
 *
 * The Python orchestrator is the brain for coding workflows:
 * - Decomposes tasks into goals → plans steps → delegates to coding agents (K8s Jobs)
 * - Evaluates results → handles git operations (commit/push with approval)
 * - Streams progress via SSE
 */
@Service
class AgentOrchestratorService(
    private val koogWorkflowService: KoogWorkflowService,
    private val pythonOrchestratorClient: PythonOrchestratorClient,
    private val preferenceService: PreferenceService,
    private val czechKeyboardNormalizer: CzechKeyboardNormalizer,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val environmentService: EnvironmentService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Enqueue a chat message for background processing.
     *
     * NOTE: This creates a NEW TaskDocument each time.
     * For chat continuation, the RPC layer should find/reuse existing TaskDocument instead.
     */
    suspend fun enqueueChatTask(
        text: String,
        clientId: ClientId,
        projectId: ProjectId?,
        attachments: List<AttachmentMetadata> = emptyList(),
    ): TaskDocument {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)

        // Create TaskDocument - this is the single source of truth
        return taskService.createTask(
            taskType = TaskTypeEnum.USER_INPUT_PROCESSING,
            content = normalizedText,
            clientId = clientId,
            projectId = projectId,
            correlationId = ObjectId().toString(),
            sourceUrn = SourceUrn.chat(clientId),
            state = TaskStateEnum.READY_FOR_GPU,
            attachments = attachments,
        )
    }

    /**
     * Handle incoming chat request by creating a TaskContext and running appropriate workflow.
     * This is the main entry point used by controllers.
     *
     * Routes to Python orchestrator for coding tasks, or Koog agent for conversational tasks.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun handle(
        text: String,
        ctx: ChatRequestContextDto,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponseDto {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)
        if (normalizedText != text) {
            logger.info { "AGENT_INPUT_NORMALIZED: original='${text.take(100)}' normalized='${normalizedText.take(100)}'" }
        }

        val clientIdTyped =
            try {
                ClientId.fromString(ctx.clientId)
            } catch (e: Exception) {
                ClientId(ObjectId(ctx.clientId))
            }
        val projectIdTyped =
            ctx.projectId?.let {
                try {
                    ProjectId.fromString(it)
                } catch (e: Exception) {
                    ProjectId(ObjectId(it))
                }
            }

        logger.info { "AGENT_HANDLE_START: text='${normalizedText.take(100)}' clientId=$clientIdTyped projectId=$projectIdTyped" }

        val taskContext =
            TaskDocument(
                type = TaskTypeEnum.USER_INPUT_PROCESSING,
                content = normalizedText,
                projectId = projectIdTyped,
                clientId = clientIdTyped,
                correlationId = ObjectId().toString(),
                sourceUrn = SourceUrn.chat(clientIdTyped),
            )

        // Run workflow with progress callback
        val response = run(taskContext, normalizedText, onProgress)

        logger.info { "AGENT_HANDLE_COMPLETE: correlationId=${taskContext.correlationId}" }
        return response
    }

    /**
     * Run agent workflow for the given taskContext and user input.
     *
     * Three paths:
     * 1. Task has orchestratorThreadId → resume from Python orchestrator (approval flow)
     * 2. New coding task → fire-and-forget dispatch to Python orchestrator
     * 3. Non-coding / Python unavailable → Koog agent (blocking)
     *
     * For Python orchestrator path, this method dispatches asynchronously:
     * sets task.state = PYTHON_ORCHESTRATING and returns immediately.
     * BackgroundEngine.runOrchestratorResultLoop() handles results.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponseDto {
        logger.info { "AGENT_ORCHESTRATOR_START: correlationId=${task.correlationId}" }

        // Path 1: Resume from Python orchestrator (approval flow)
        if (task.orchestratorThreadId != null) {
            try {
                val dispatched = resumePythonOrchestrator(task, userInput, onProgress)
                if (dispatched) {
                    logger.info { "AGENT_ORCHESTRATOR_RESUMED (python): correlationId=${task.correlationId} threadId=${task.orchestratorThreadId}" }
                    return ChatResponseDto("")  // Empty = dispatched, result via resultLoop
                }
            } catch (e: Exception) {
                logger.warn(e) { "Python orchestrator resume failed: ${e.message}" }
            }
        }

        // Path 2: New coding task → fire-and-forget dispatch
        if (shouldUsePythonOrchestrator(userInput)) {
            try {
                val dispatched = dispatchToPythonOrchestrator(task, userInput, onProgress)
                if (dispatched) {
                    logger.info { "AGENT_ORCHESTRATOR_DISPATCHED (python): correlationId=${task.correlationId}" }
                    return ChatResponseDto("")  // Empty = dispatched, result via resultLoop
                }
            } catch (e: Exception) {
                logger.warn(e) { "Python orchestrator failed, falling back to Koog: ${e.message}" }
                onProgress("Python orchestrator nedostupný, používám Koog agenta...", emptyMap())
            }
        }

        // Path 3: Koog agent for conversational/non-coding tasks (blocking)
        val response = koogWorkflowService.run(task, userInput, onProgress)

        logger.info { "AGENT_ORCHESTRATOR_COMPLETE (koog): correlationId=${task.correlationId}" }
        return response
    }

    /**
     * Determine if the request should be routed to the Python orchestrator.
     *
     * Python orchestrator handles coding tasks that need:
     * - Code generation/modification
     * - Multi-file refactoring
     * - K8s Job execution
     * - Git operations with approval
     */
    private fun shouldUsePythonOrchestrator(userInput: String): Boolean {
        val codingKeywords = listOf(
            "implementuj", "implement", "naprogramuj", "code", "kód",
            "oprav", "fix", "bug", "refactor", "refaktor",
            "přidej", "add feature", "vytvoř", "create",
            "uprav", "modify", "změn", "change",
            "napiš", "write code", "generate",
        )
        val lowerInput = userInput.lowercase()
        return codingKeywords.any { lowerInput.contains(it) }
    }

    /**
     * Fire-and-forget dispatch to Python orchestrator.
     *
     * Calls POST /orchestrate/stream → gets thread_id immediately.
     * Stores thread_id in TaskDocument, changes state to PYTHON_ORCHESTRATING.
     * BackgroundEngine.runOrchestratorResultLoop() polls for results.
     *
     * Concurrency control (two layers):
     * 1. Kotlin: checks count of PYTHON_ORCHESTRATING tasks in DB (early guard)
     * 2. Python: asyncio.Semaphore(1) → returns HTTP 429 if busy
     *
     * @return true if dispatched successfully, false if unavailable/busy
     */
    private suspend fun dispatchToPythonOrchestrator(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): Boolean {
        // Early guard: only one task can be PYTHON_ORCHESTRATING at a time
        val orchestratingCount = taskRepository.countByState(TaskStateEnum.PYTHON_ORCHESTRATING)
        if (orchestratingCount > 0) {
            logger.info { "PYTHON_DISPATCH_SKIP: $orchestratingCount task(s) already PYTHON_ORCHESTRATING" }
            return false
        }

        if (!pythonOrchestratorClient.isHealthy()) {
            logger.info { "Python orchestrator not healthy, skipping" }
            return false
        }

        onProgress("Spouštím Python orchestrator...", mapOf("phase" to "python_orchestrator"))

        val rules = loadProjectRules(task.clientId, task.projectId)

        // Resolve environment context for the project (if any)
        val environmentJson = task.projectId?.let { pid ->
            try {
                environmentService.resolveEnvironmentForProject(pid)?.toAgentContextJson()
            } catch (e: Exception) {
                logger.warn { "Failed to resolve environment for project $pid: ${e.message}" }
                null
            }
        }

        val request = OrchestrateRequestDto(
            taskId = task.correlationId ?: ObjectId().toString(),
            clientId = task.clientId?.toString() ?: "",
            projectId = task.projectId?.toString(),
            workspacePath = resolveWorkspacePath(task),
            query = userInput,
            rules = rules,
            environment = environmentJson,
        )

        // Fire-and-forget: get thread_id immediately, Python runs in background
        // Returns null if Python is busy (HTTP 429)
        val streamResponse = pythonOrchestratorClient.orchestrateStream(request)
        if (streamResponse == null) {
            logger.info { "PYTHON_DISPATCH_BUSY: orchestrator returned 429, skipping" }
            return false
        }

        // Store thread_id and change state to PYTHON_ORCHESTRATING
        val updatedTask = task.copy(
            state = TaskStateEnum.PYTHON_ORCHESTRATING,
            orchestratorThreadId = streamResponse.threadId,
        )
        taskRepository.save(updatedTask)

        logger.info {
            "PYTHON_DISPATCHED: taskId=${task.id} threadId=${streamResponse.threadId}"
        }

        onProgress(
            "Orchestrátor zpracovává úkol na pozadí...",
            mapOf("phase" to "python_orchestrating", "threadId" to streamResponse.threadId),
        )

        return true
    }

    /**
     * Resume Python orchestrator after approval (USER_TASK → READY_FOR_GPU with answer).
     *
     * Calls POST /approve/{thread_id} with user's response (fire-and-forget).
     * Python resumes the graph in background with asyncio.Semaphore concurrency control.
     * Result handled by BackgroundEngine.runOrchestratorResultLoop() polling GET /status.
     *
     * @return true if dispatched successfully
     */
    private suspend fun resumePythonOrchestrator(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): Boolean {
        val threadId = task.orchestratorThreadId ?: return false

        if (!pythonOrchestratorClient.isHealthy()) {
            logger.info { "Python orchestrator not healthy for resume" }
            return false
        }

        onProgress("Pokračuji v orchestraci...", mapOf("phase" to "python_resume"))

        // User input contains the approval response (e.g., "ano", "approve", etc.)
        val approved = userInput.lowercase().let {
            it.contains("ano") || it.contains("yes") || it.contains("approve") || it.contains("schval")
        }

        pythonOrchestratorClient.approve(
            threadId = threadId,
            approved = approved,
            reason = userInput,
        )

        // Update state back to PYTHON_ORCHESTRATING (graph is running again)
        val updatedTask = task.copy(state = TaskStateEnum.PYTHON_ORCHESTRATING)
        taskRepository.save(updatedTask)

        logger.info { "PYTHON_RESUMED: taskId=${task.id} threadId=$threadId approved=$approved" }

        return true
    }

    /**
     * Load project rules from PreferenceService (scope: PROJECT → CLIENT → GLOBAL).
     */
    private suspend fun loadProjectRules(
        clientId: ClientId?,
        projectId: ProjectId?,
    ): ProjectRulesDto {
        val prefs = preferenceService.getAllPreferences(clientId, projectId)

        return ProjectRulesDto(
            branchNaming = prefs["orchestrator.branch_naming"] ?: "task/{taskId}",
            commitPrefix = prefs["orchestrator.commit_prefix"] ?: "task({taskId}):",
            requireReview = prefs["orchestrator.require_review"]?.toBoolean() ?: false,
            requireTests = prefs["orchestrator.require_tests"]?.toBoolean() ?: false,
            requireApprovalCommit = prefs["orchestrator.require_approval_commit"]?.toBoolean() ?: true,
            requireApprovalPush = prefs["orchestrator.require_approval_push"]?.toBoolean() ?: true,
            allowedBranches = prefs["orchestrator.allowed_branches"]
                ?.split(",")
                ?.map { it.trim() }
                ?: listOf("task/*", "fix/*"),
            forbiddenFiles = prefs["orchestrator.forbidden_files"]
                ?.split(",")
                ?.map { it.trim() }
                ?: listOf("*.env", "secrets/*"),
            maxChangedFiles = prefs["orchestrator.max_changed_files"]?.toIntOrNull() ?: 20,
            autoPush = prefs["orchestrator.auto_push"]?.toBoolean() ?: false,
        )
    }

    /**
     * Resolve workspace path for a task based on client/project.
     */
    private fun resolveWorkspacePath(task: TaskDocument): String {
        val clientId = task.clientId?.toString() ?: "default"
        val projectId = task.projectId?.toString() ?: "default"
        return "clients/$clientId/$projectId"
    }
}
