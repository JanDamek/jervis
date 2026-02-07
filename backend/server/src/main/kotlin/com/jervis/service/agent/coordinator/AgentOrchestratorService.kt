package com.jervis.service.agent.coordinator

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.configuration.OrchestrateRequestDto
import com.jervis.configuration.OrchestrateResponseDto
import com.jervis.configuration.ProjectRulesDto
import com.jervis.configuration.PythonOrchestratorClient
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.ChatRequestContextDto
import com.jervis.dto.ChatResponseDto
import com.jervis.dto.TaskStateEnum
import com.jervis.dto.TaskTypeEnum
import com.jervis.entity.TaskDocument
import com.jervis.service.background.TaskService
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
     * Tries Python orchestrator first for coding tasks. Falls back to Koog agent
     * if Python orchestrator is unavailable or for non-coding queries.
     *
     * @param onProgress Callback for sending progress updates to client
     */
    suspend fun run(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit = { _, _ -> },
    ): ChatResponseDto {
        logger.info { "AGENT_ORCHESTRATOR_START: correlationId=${task.correlationId}" }

        // Try Python orchestrator for coding tasks
        if (shouldUsePythonOrchestrator(userInput)) {
            try {
                val pythonResponse = delegateToPythonOrchestrator(task, userInput, onProgress)
                if (pythonResponse != null) {
                    logger.info { "AGENT_ORCHESTRATOR_COMPLETE (python): correlationId=${task.correlationId}" }
                    return pythonResponse
                }
            } catch (e: Exception) {
                logger.warn(e) { "Python orchestrator failed, falling back to Koog: ${e.message}" }
                onProgress("Python orchestrator nedostupný, používám Koog agenta...", emptyMap())
            }
        }

        // Fallback: Koog agent for conversational/non-coding tasks
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
     * Delegate a coding task to the Python orchestrator.
     *
     * The Python orchestrator (LangGraph) handles:
     * 1. Task decomposition → goal planning
     * 2. Coding agent execution via K8s Jobs
     * 3. Result evaluation
     * 4. Git operations (commit/push with approval via interrupt)
     *
     * If the graph hits an interrupt (approval required), this method
     * auto-approves for now (TODO: wire to UI approval dialog).
     */
    private suspend fun delegateToPythonOrchestrator(
        task: TaskDocument,
        userInput: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): ChatResponseDto? {
        // Check if Python orchestrator is available
        if (!pythonOrchestratorClient.isHealthy()) {
            logger.info { "Python orchestrator not healthy, skipping" }
            return null
        }

        onProgress("Spouštím Python orchestrator...", mapOf("phase" to "python_orchestrator"))

        val rules = loadProjectRules(task.clientId, task.projectId)

        val request = OrchestrateRequestDto(
            taskId = task.correlationId ?: ObjectId().toString(),
            clientId = task.clientId?.toString() ?: "",
            projectId = task.projectId?.toString(),
            workspacePath = resolveWorkspacePath(task),
            query = userInput,
            rules = rules,
        )

        var response = pythonOrchestratorClient.orchestrate(request)

        // Handle interrupt loop (commit approval → push approval)
        while (response.isInterrupted) {
            val interruptAction = response.interrupt?.get("action")?.toString()?.trim('"') ?: "unknown"
            logger.info { "Orchestrator interrupted: action=$interruptAction threadId=${response.threadId}" }
            onProgress(
                "Čekám na schválení: $interruptAction",
                mapOf("phase" to "approval", "action" to interruptAction),
            )

            // TODO: Wire to UI approval dialog via notification service.
            // For now, auto-approve commit, reject push (safe default).
            val autoApprove = interruptAction == "commit"
            response = pythonOrchestratorClient.approve(
                threadId = response.threadId ?: break,
                approved = autoApprove,
                reason = if (autoApprove) "auto-approved" else "auto-push disabled",
            )
        }

        return formatOrchestratorResponse(response)
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
     * Format Python orchestrator response into a ChatResponseDto.
     */
    private fun formatOrchestratorResponse(response: OrchestrateResponseDto): ChatResponseDto {
        val resultText = buildString {
            appendLine(response.summary)
            if (response.branch != null) {
                appendLine()
                appendLine("**Branch:** `${response.branch}`")
            }
            if (response.artifacts.isNotEmpty()) {
                appendLine()
                appendLine("**Změněné soubory:**")
                response.artifacts.forEach { appendLine("- `$it`") }
            }
            if (response.stepResults.isNotEmpty()) {
                appendLine()
                appendLine("**Kroky:**")
                response.stepResults.forEach { step ->
                    val status = if (step.success) "✓" else "✗"
                    appendLine("$status ${step.summary} (${step.agentType})")
                }
            }
        }
        return ChatResponseDto(resultText)
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
