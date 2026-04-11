package com.jervis.agent

import com.jervis.common.types.ClientId
import com.jervis.common.types.ProjectId
import com.jervis.common.types.SourceUrn
import com.jervis.agent.OrchestrateRequestDto
import com.jervis.agent.ProjectRulesDto
import com.jervis.agent.PythonOrchestratorClient
import com.jervis.domain.atlassian.AttachmentMetadata
import com.jervis.dto.chat.ChatRequestContextDto
import com.jervis.dto.chat.ChatResponseDto
import com.jervis.dto.task.TaskStateEnum
import com.jervis.dto.task.TaskTypeEnum
import com.jervis.task.TaskDocument
import com.jervis.infrastructure.llm.CloudModelPolicy
import com.jervis.environment.toAgentContextJson
import com.jervis.task.TaskRepository
import com.jervis.infrastructure.llm.CloudModelPolicyResolver
import com.jervis.client.ClientService
import com.jervis.project.ProjectService
import com.jervis.task.TaskService
import com.jervis.environment.EnvironmentService
import com.jervis.preferences.PreferenceService
import com.jervis.infrastructure.text.CzechKeyboardNormalizer
import kotlinx.coroutines.flow.toList
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
    private val environmentK8sService: com.jervis.environment.EnvironmentK8sService,
    private val clientService: ClientService,
    private val projectService: ProjectService,
    private val cloudModelPolicyResolver: CloudModelPolicyResolver,
    private val gitRepositoryService: com.jervis.git.service.GitRepositoryService,
    private val directoryStructureService: com.jervis.infrastructure.storage.DirectoryStructureService,
    private val projectGroupRepository: com.jervis.projectgroup.ProjectGroupRepository,
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
            taskType = TaskTypeEnum.INSTANT,
            content = normalizedText,
            clientId = clientId,
            projectId = projectId,
            correlationId = ObjectId().toString(),
            sourceUrn = SourceUrn.chat(clientId),
            state = TaskStateEnum.QUEUED,
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
                type = TaskTypeEnum.INSTANT,
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
        // Skip for coding agent / code-review tasks — they run as K8s Jobs without LangGraph
        // checkpoints, so /approve/{thread_id} would always fail with "no valid checkpoint".
        val isK8sJobTask = task.sourceUrn?.value?.let {
            it.contains("coding-agent") || it.startsWith("code-review:") || it.startsWith("code-review-fix:")
        } == true
        if (task.orchestratorThreadId != null && !isK8sJobTask) {
            try {
                val dispatched = resumePythonOrchestrator(task, userInput, onProgress)
                if (dispatched) {
                    logger.info { "AGENT_ORCHESTRATOR_RESUMED (python): correlationId=${task.correlationId} threadId=${task.orchestratorThreadId}" }
                    return ChatResponseDto("")  // Empty = dispatched, result via resultLoop
                }
            } catch (e: Exception) {
                logger.warn(e) { "Python orchestrator resume failed: ${e.message}" }
            }
        } else if (isK8sJobTask && task.orchestratorThreadId != null) {
            logger.info { "SKIP_RESUME: K8s job task has stale orchestratorThreadId=${task.orchestratorThreadId}, clearing and dispatching fresh" }
            // Clear stale thread ID so Path 2 dispatches fresh
            val cleared = task.copy(orchestratorThreadId = null)
            taskRepository.save(cleared)
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
        // Guard: limit concurrent orchestrations (resource-based scheduling)
        val maxConcurrent = 4  // TODO: move to config
        val orchestratingCount = taskRepository.countByState(TaskStateEnum.PROCESSING)
        if (orchestratingCount >= maxConcurrent) {
            logger.info { "PYTHON_DISPATCH_SKIP: $orchestratingCount/$maxConcurrent tasks already PROCESSING" }
            return false
        }

        if (!pythonOrchestratorClient.isHealthy()) {
            logger.info { "Python orchestrator not healthy, skipping" }
            return false
        }

        // Validate workspace is ready — only BLOCK coding tasks (sourceUrn=chat:coding-agent).
        // Non-coding tasks (graph agent, reminders, alerts) proceed regardless of workspace status.
        val isCodingTask = task.sourceUrn?.value?.contains("coding-agent") == true
        if (isCodingTask && task.projectId != null) {
            val project = projectService.getProjectByIdOrNull(task.projectId)
            if (project != null) {
                val hasGitResources = project.resources.any {
                    it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                }

                if (hasGitResources) {
                    val gitResources = project.resources.filter {
                        it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
                    }
                    when (project.workspaceStatus) {
                        com.jervis.project.WorkspaceStatus.CLONING, null -> {
                            logger.info { "WORKSPACE_CLONING: project=${project.name} projectId=${project.id} status=${project.workspaceStatus} resources=${gitResources.size}" }
                            onProgress(
                                "Prostředí se připravuje, počkejte prosím...",
                                mapOf("phase" to "workspace_preparing", "status" to (project.workspaceStatus?.name ?: "null"))
                            )
                            return false
                        }
                        com.jervis.project.WorkspaceStatus.CLONE_FAILED_AUTH -> {
                            logger.warn { "WORKSPACE_CLONE_FAILED_AUTH: project=${project.name} projectId=${project.id}" }
                            onProgress(
                                "Přihlášení k repozitáři selhalo. Zkontrolujte přístupové údaje v nastavení připojení.",
                                mapOf("phase" to "workspace_failed", "reason" to "auth")
                            )
                            return false
                        }
                        com.jervis.project.WorkspaceStatus.CLONE_FAILED_NOT_FOUND -> {
                            logger.warn { "WORKSPACE_CLONE_FAILED_NOT_FOUND: project=${project.name} projectId=${project.id}" }
                            onProgress(
                                "Repozitář nebyl nalezen. Zkontrolujte URL repozitáře v nastavení připojení.",
                                mapOf("phase" to "workspace_failed", "reason" to "not_found")
                            )
                            return false
                        }
                        com.jervis.project.WorkspaceStatus.CLONE_FAILED_NETWORK -> {
                            logger.warn { "WORKSPACE_CLONE_FAILED_NETWORK: project=${project.name} projectId=${project.id}" }
                            onProgress(
                                "Síťová chyba při přístupu k repozitáři. Systém to zkusí znovu automaticky.",
                                mapOf("phase" to "workspace_failed", "reason" to "network")
                            )
                            return false
                        }
                        com.jervis.project.WorkspaceStatus.CLONE_FAILED_OTHER -> {
                            logger.warn { "WORKSPACE_CLONE_FAILED_OTHER: project=${project.name} projectId=${project.id} error=${project.lastWorkspaceError}" }
                            onProgress(
                                "Příprava prostředí selhala. Systém to zkusí znovu automaticky.",
                                mapOf("phase" to "workspace_failed", "reason" to "other")
                            )
                            return false
                        }
                        com.jervis.project.WorkspaceStatus.READY -> {
                            logger.debug { "WORKSPACE_READY: project=${project.name} resources=${gitResources.size}" }
                            // Continue - workspace is ready
                        }
                        com.jervis.project.WorkspaceStatus.NOT_NEEDED -> {
                            logger.debug { "WORKSPACE_NOT_NEEDED: project=${project.name}" }
                            // Continue
                        }
                    }
                }
            }
        }

        val rules = loadProjectRules(task.clientId, task.projectId)

        // Resolve client/project names for orchestrator context
        val clientName = try {
            clientService.getClientByIdOrNull(task.clientId)?.name
        } catch (e: Exception) { null }
        val projectDoc = task.projectId?.let { pid ->
            try {
                projectService.getProjectByIdOrNull(pid)
            } catch (e: Exception) { null }
        }
        val projectName = projectDoc?.name
        val groupId = projectDoc?.groupId?.toString()
        val groupName = groupId?.let { gid ->
            try {
                projectGroupRepository.getById(com.jervis.common.types.ProjectGroupId(org.bson.types.ObjectId(gid)))?.name
            } catch (_: Exception) { null }
        }

        val workspacePath = resolveWorkspacePath(task)

        return dispatchBackground(task, userInput, rules, clientName, projectName, groupId, groupName, workspacePath, onProgress)
    }

    /**
     * Background dispatch — POST /orchestrate.
     *
     * Fire-and-forget: returns thread_id immediately.
     * Python runs Graph Agent and pushes status to Kotlin via /internal/orchestrator-status.
     */
    private suspend fun dispatchBackground(
        task: TaskDocument,
        userInput: String,
        rules: ProjectRulesDto,
        clientName: String?,
        projectName: String?,
        groupId: String?,
        groupName: String?,
        workspacePath: String,
        onProgress: suspend (message: String, metadata: Map<String, String>) -> Unit,
    ): Boolean {
        onProgress("Spouštím orchestrátor...", mapOf("phase" to "python_orchestrate"))

        // Resolve environment and auto-provision if PENDING/STOPPED
        var environmentId: String? = null
        val environmentJson = task.projectId?.let { pid ->
            try {
                val env = environmentService.resolveEnvironmentForProject(pid)
                if (env != null) {
                    environmentId = env.id.toString()
                    // Auto-provision if not running
                    val state = env.state
                    if (state == com.jervis.environment.EnvironmentState.PENDING ||
                        state == com.jervis.environment.EnvironmentState.STOPPED
                    ) {
                        logger.info { "AUTO_PROVISION: environment ${env.name} (ns=${env.namespace}) is $state, provisioning..." }
                        onProgress("Spouštím prostředí ${env.name}...", mapOf("phase" to "environment_provision"))
                        try {
                            val provisioned = environmentK8sService.provisionEnvironment(env.id)
                            provisioned.toAgentContextJson()
                        } catch (provisionError: Exception) {
                            logger.warn(provisionError) { "Auto-provision failed for ${env.name}, passing current state" }
                            env.toAgentContextJson()
                        }
                    } else {
                        env.toAgentContextJson()
                    }
                } else null
            } catch (e: Exception) {
                logger.warn { "Failed to resolve environment for project $pid: ${e.message}" }
                null
            }
        }

        val jervisProjectId = try {
            projectService.getOrCreateJervisProject(task.clientId).id.toString()
        } catch (e: Exception) {
            logger.warn { "Failed to resolve JERVIS internal project: ${e.message}" }
            null
        }

        val request = OrchestrateRequestDto(
            taskId = task.id.toString(),
            clientId = task.clientId.toString(),
            projectId = task.projectId?.toString(),
            groupId = groupId,
            clientName = clientName,
            projectName = projectName,
            groupName = groupName,
            workspacePath = workspacePath,
            query = userInput,
            rules = rules,
            environment = environmentJson,
            environmentId = environmentId,
            jervisProjectId = jervisProjectId,
            processingMode = "BACKGROUND",
            maxOpenRouterTier = rules.maxOpenRouterTier,
            qualifierContext = task.qualifierPreparedContext,
            sourceUrn = task.sourceUrn.value,
            taskName = task.taskName,
        )

        try {
            val streamResponse = pythonOrchestratorClient.orchestrate(request)
            if (streamResponse == null) {
                logger.info { "ORCHESTRATE_BUSY: returned 429, task will retry later" }
                return false
            }

            val updatedTask = task.copy(
                state = TaskStateEnum.PROCESSING,
                orchestratorThreadId = streamResponse.threadId,
                orchestrationStartedAt = java.time.Instant.now(),
                orchestratorSteps = emptyList(),
            )
            taskRepository.save(updatedTask)

            logger.info { "ORCHESTRATE_DISPATCHED: taskId=${task.id} threadId=${streamResponse.threadId}" }
            onProgress(
                "Orchestrátor zpracovává úkol...",
                mapOf("phase" to "python_orchestrating", "threadId" to streamResponse.threadId),
            )
            return true
        } catch (e: Exception) {
            logger.error(e) { "ORCHESTRATE_FAILED: taskId=${task.id}" }
            throw e
        }
    }

    /**
     * Resume Python orchestrator after user interaction (USER_TASK → PROCESSING).
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

        // Chat history: Python loads directly from MongoDB (no Kotlin payload)

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
            state = TaskStateEnum.PROCESSING,
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

        // Resolve cloud model policy via hierarchy: project → group → client → default
        val effectivePolicy = cloudModelPolicyResolver.resolve(clientId, projectId)

        // Git commit config: project overrides client
        val client = clientId?.let { clientService.getClientByIdOrNull(it) }
        val project = projectId?.let { projectService.getProjectByIdOrNull(it) }
        val clientGitConfig = client?.gitCommitConfig
        val effectiveGitConfig = project?.gitCommitConfig ?: clientGitConfig

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
            maxOpenRouterTier = effectivePolicy.maxOpenRouterTier.name,
            gitAuthorName = effectiveGitConfig?.authorName,
            gitAuthorEmail = effectiveGitConfig?.authorEmail,
            gitCommitterName = effectiveGitConfig?.committerName,
            gitCommitterEmail = effectiveGitConfig?.committerEmail,
            gitGpgSign = effectiveGitConfig?.gpgSign ?: false,
            gitGpgKeyId = effectiveGitConfig?.gpgKeyId,
            gitMessagePattern = effectiveGitConfig?.messagePattern ?: effectiveGitConfig?.messageFormat,
        )
    }

    /**
     * Resolve workspace path for a task based on client/project.
     * Returns absolute path to the project's git repository workspace directory.
     *
     * For projects with a REPOSITORY resource, returns the full path including
     * resource subdirectory: `.../git/{resourceId}/`
     *
     * For projects without a repository, returns the workspace root.
     */
    private suspend fun resolveWorkspacePath(task: TaskDocument): String {
        val projectId = task.projectId ?: return directoryStructureService.workspaceRoot().toString()

        // Get project document to access resources
        val project = projectService.getProjectByIdOrNull(projectId)
            ?: return directoryStructureService.workspaceRoot().toString()

        // Find first REPOSITORY resource
        val repoResource = project.resources.firstOrNull {
            it.capability == com.jervis.dto.connection.ConnectionCapability.REPOSITORY
        }

        if (repoResource != null) {
            // Return full path including resource subdirectory
            return gitRepositoryService.getRepoDir(
                project = project,
                resource = repoResource,
                workspaceType = com.jervis.git.service.WorkspaceType.AGENT,
            ).toAbsolutePath().toString()
        }

        // Fallback: project has no repository resource
        return directoryStructureService.projectGitDir(task.clientId, projectId).toString()
    }
}
