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
import com.jervis.entity.CloudModelPolicy
import com.jervis.mapper.toAgentContextJson
import com.jervis.repository.TaskRepository
import com.jervis.service.client.ClientService
import com.jervis.service.project.ProjectService
import com.jervis.service.background.TaskService
import com.jervis.service.environment.EnvironmentService
import com.jervis.service.chat.ChatHistoryService
import com.jervis.service.preferences.PreferenceService
import com.jervis.service.text.CzechKeyboardNormalizer
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/**
 * AgentOrchestratorService – routes ALL requests to the Python orchestrator (LangGraph).
 *
 * The Python orchestrator handles everything:
 * - Coding tasks (K8s Jobs, git operations, approval flow)
 * - Conversational queries, analysis, advice
 * - Multi-goal decomposition and execution
 *
 * Kotlin server is a thin proxy: enqueue tasks, dispatch to Python, poll for results.
 */
@Service
class AgentOrchestratorService(
    private val pythonOrchestratorClient: PythonOrchestratorClient,
    private val preferenceService: PreferenceService,
    private val czechKeyboardNormalizer: CzechKeyboardNormalizer,
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val environmentService: EnvironmentService,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val chatHistoryService: ChatHistoryService,
    private val gitRepositoryService: com.jervis.service.indexing.git.GitRepositoryService,
    private val directoryStructureService: com.jervis.service.storage.DirectoryStructureService,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Enqueue a chat message for background processing.
     */
    suspend fun enqueueChatTask(
        text: String,
        clientId: ClientId,
        projectId: ProjectId?,
        attachments: List<AttachmentMetadata> = emptyList(),
    ): TaskDocument {
        val normalizedText = czechKeyboardNormalizer.convertIfMistyped(text)

        return taskService.createTask(
            taskType = TaskTypeEnum.USER_INPUT_PROCESSING,
            content = normalizedText,
            clientId = clientId,
            projectId = projectId,
            correlationId = ObjectId().toString(),
            sourceUrn = SourceUrn.chat(clientId),
            state = TaskStateEnum.READY_FOR_GPU,
            attachments = attachments,
            taskName = normalizedText.take(80).lines().first(),
        )
    }

    /**
     * Handle incoming chat request by dispatching to Python orchestrator.
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

        val response = run(taskContext, normalizedText, onProgress)

        logger.info { "AGENT_HANDLE_COMPLETE: correlationId=${taskContext.correlationId}" }
        return response
    }

    /**
     * Run agent workflow for the given task and user input.
     *
     * All requests go to the Python orchestrator (LangGraph).
     * Three paths:
     * 1. Task has orchestratorThreadId → resume from Python orchestrator (approval flow)
     * 2. New task → fire-and-forget dispatch to Python orchestrator
     * 3. Python unavailable → return error (no local fallback)
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

        // Path 2: Dispatch to Python orchestrator
        try {
            val dispatched = dispatchToPythonOrchestrator(task, userInput, onProgress)
            if (dispatched) {
                logger.info { "AGENT_ORCHESTRATOR_DISPATCHED (python): correlationId=${task.correlationId}" }
                return ChatResponseDto("")  // Empty = dispatched, result via resultLoop
            }
        } catch (e: Exception) {
            logger.error(e) { "Python orchestrator dispatch failed: ${e.message}" }
        }

        // Path 3: Python orchestrator unavailable → return error
        logger.error { "ORCHESTRATOR_UNAVAILABLE: correlationId=${task.correlationId}" }
        return ChatResponseDto("Orchestrátor není momentálně dostupný. Zkuste to prosím později.")
    }

    /**
     * Fire-and-forget dispatch to Python orchestrator.
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

        // Validate workspace is ready (for projects with git resources)
        if (task.projectId != null) {
            val project = projectService.getProjectByIdOrNull(task.projectId)
            if (project != null) {
                val repoResources = project.resources.filter {
                    it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                }

                for (resource in repoResources) {
                    val workspacePath = gitRepositoryService.ensureAgentWorkspaceReady(project, resource)
                    if (workspacePath == null) {
                        logger.warn { "Workspace not ready for project ${project.name}, resource ${resource.resourceIdentifier}" }
                        onProgress(
                            "Workspace se připravuje, zkuste to prosím za chvíli...",
                            mapOf("phase" to "workspace_preparing")
                        )
                        return false
                    }
                }
            }
        }

        onProgress("Spouštím orchestrátor...", mapOf("phase" to "python_orchestrator"))

        val rules = loadProjectRules(task.clientId, task.projectId)

        val environmentJson = task.projectId?.let { pid ->
            try {
                environmentService.resolveEnvironmentForProject(pid)?.toAgentContextJson()
            } catch (e: Exception) {
                logger.warn { "Failed to resolve environment for project $pid: ${e.message}" }
                null
            }
        }

        // Resolve JERVIS internal project for orchestrator planning
        val jervisProjectId = try {
            projectService.getOrCreateJervisProject(task.clientId).id.toString()
        } catch (e: Exception) {
            logger.warn { "Failed to resolve JERVIS internal project: ${e.message}" }
            null
        }

        // Resolve client/project names for orchestrator context
        val clientName = try {
            clientService.getClientByIdOrNull(task.clientId)?.name
        } catch (e: Exception) { null }
        val projectName = task.projectId?.let { pid ->
            try {
                projectService.getProjectByIdOrNull(pid)?.name
            } catch (e: Exception) { null }
        }

        // Load chat history for conversation context
        val chatHistory = try {
            chatHistoryService.prepareChatHistoryPayload(task.id)
        } catch (e: Exception) {
            logger.warn { "Failed to load chat history for task ${task.id}: ${e.message}" }
            null
        }

        val request = OrchestrateRequestDto(
            taskId = task.id.toString(),
            clientId = task.clientId.toString(),
            projectId = task.projectId?.toString(),
            clientName = clientName,
            projectName = projectName,
            workspacePath = resolveWorkspacePath(task),
            query = userInput,
            rules = rules,
            environment = environmentJson,
            jervisProjectId = jervisProjectId,
            chatHistory = chatHistory,
        )

        val streamResponse = pythonOrchestratorClient.orchestrateStream(request)
        if (streamResponse == null) {
            logger.info { "PYTHON_DISPATCH_BUSY: orchestrator returned 429, skipping" }
            return false
        }

        val updatedTask = task.copy(
            state = TaskStateEnum.PYTHON_ORCHESTRATING,
            orchestratorThreadId = streamResponse.threadId,
            orchestrationStartedAt = java.time.Instant.now(),
        )
        taskRepository.save(updatedTask)

        logger.info { "PYTHON_DISPATCHED: taskId=${task.id} threadId=${streamResponse.threadId}" }

        onProgress(
            "Orchestrátor zpracovává úkol...",
            mapOf("phase" to "python_orchestrating", "threadId" to streamResponse.threadId),
        )

        return true
    }

    /**
     * Resume Python orchestrator after user interaction (USER_TASK → PYTHON_ORCHESTRATING).
     *
     * Distinguishes between two interrupt types:
     * - **Clarification**: Pre-planning questions (no "Schválení:" prefix).
     *   Always approved=true, user's full input is the answer passed as reason.
     * - **Approval**: Commit/push approval (has "Schválení:" prefix).
     *   Parses yes/no intent from user input.
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

        // Clarification questions don't have "Schválení:" prefix
        val wasClarification = task.pendingUserQuestion?.startsWith("Schválení:") != true

        if (wasClarification) {
            // Clarification: always approved, user's answer is the resume value
            onProgress("Pokračuji v orchestraci s upřesněním...", mapOf("phase" to "python_resume", "type" to "clarification"))

            pythonOrchestratorClient.approve(
                threadId = threadId,
                approved = true,
                reason = userInput,
            )
        } else {
            // Approval: parse yes/no intent
            onProgress("Pokračuji v orchestraci...", mapOf("phase" to "python_resume", "type" to "approval"))

            val approved = userInput.lowercase().let {
                it.contains("ano") || it.contains("yes") || it.contains("approve") || it.contains("schval")
            }

            pythonOrchestratorClient.approve(
                threadId = threadId,
                approved = approved,
                reason = userInput,
            )
        }

        val updatedTask = task.copy(
            state = TaskStateEnum.PYTHON_ORCHESTRATING,
            orchestrationStartedAt = java.time.Instant.now(),
        )
        taskRepository.save(updatedTask)

        logger.info { "PYTHON_RESUMED: taskId=${task.id} threadId=$threadId clarification=$wasClarification" }

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

        // Load cloud model policy: project overrides client
        val client = clientId?.let { clientService.getClientByIdOrNull(it) }
        val project = projectId?.let { projectService.getProjectByIdOrNull(it) }
        val clientPolicy = client?.cloudModelPolicy ?: CloudModelPolicy()
        val effectivePolicy = project?.cloudModelPolicy ?: clientPolicy

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
            autoUseAnthropic = effectivePolicy.autoUseAnthropic,
            autoUseOpenai = effectivePolicy.autoUseOpenai,
            autoUseGemini = effectivePolicy.autoUseGemini,
        )
    }

    /**
     * Resolve workspace path for a task based on client/project.
     * Returns absolute path to the project's git workspace directory.
     */
    private fun resolveWorkspacePath(task: TaskDocument): String {
        val projectId = task.projectId ?: return directoryStructureService.workspaceRoot().toString()
        return directoryStructureService.projectGitDir(task.clientId, projectId).toString()
    }
}
