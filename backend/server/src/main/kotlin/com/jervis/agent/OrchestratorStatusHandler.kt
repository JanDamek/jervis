package com.jervis.agent

import com.jervis.common.types.EnvironmentId
import com.jervis.common.types.TaskId
import com.jervis.dto.task.TaskStateEnum
import com.jervis.task.TaskDocument
import com.jervis.task.TaskHistoryDocument
import com.jervis.chat.ChatMessageRepository
import com.jervis.client.ClientRepository
import com.jervis.task.TaskHistoryRepository
import com.jervis.task.TaskRepository
import com.jervis.agent.AgentOrchestratorRpcImpl
import com.jervis.task.TaskService
import com.jervis.project.ProjectService
import com.jervis.task.UserTaskService
import mu.KotlinLogging
import org.bson.types.ObjectId
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

/**
 * Handles orchestrator task status changes — both push-based callbacks
 * from Python and safety-net polling from BackgroundEngine.
 *
 * Push-based (primary): Python → POST /internal/orchestrator-status → this handler
 * Polling (safety net, 60s): BackgroundEngine → getStatus() → this handler
 *
 * State transitions:
 * - "done" → DONE (or re-queue if inline messages arrived)
 * - "cancelled" → DONE (user-initiated cancel, clean terminal state)
 * - "interrupted" → USER_TASK (clarification or approval)
 * - "error" → ERROR + escalate
 */
@Service
class OrchestratorStatusHandler(
    private val taskRepository: TaskRepository,
    private val taskHistoryRepository: TaskHistoryRepository,
    private val taskService: TaskService,
    private val userTaskService: UserTaskService,
    private val agentOrchestratorRpc: AgentOrchestratorRpcImpl,
    private val chatMessageRepository: ChatMessageRepository,
    private val workflowTracker: OrchestratorWorkflowTracker,
    private val projectService: ProjectService,
    private val clientRepository: ClientRepository,
    private val environmentK8sService: com.jervis.environment.EnvironmentK8sService,
    private val environmentService: com.jervis.environment.EnvironmentService,
    @Lazy private val chatRpcImpl: com.jervis.chat.ChatRpcImpl,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Handle a status change for a Python-orchestrated task.
     *
     * Called from:
     * - KtorRpcServer /internal/orchestrator-status (push-based, real-time)
     * - BackgroundEngine.checkOrchestratorTaskStatus (polling safety net, 60s)
     *
     * @param taskId MongoDB ObjectId string of the task
     * @param status "done", "error", "interrupted", "running"
     * @param summary Final result summary (for "done")
     * @param error Error message (for "error")
     * @param interruptAction Interrupt type (for "interrupted"): "clarify", "commit", "push"
     * @param interruptDescription Human-readable interrupt description
     * @param branch Git branch name (for "done")
     * @param artifacts List of changed files (for "done")
     */
    suspend fun handleStatusChange(
        taskId: String,
        status: String,
        summary: String? = null,
        error: String? = null,
        interruptAction: String? = null,
        interruptDescription: String? = null,
        branch: String? = null,
        artifacts: List<String> = emptyList(),
        keepEnvironmentRunning: Boolean = false,
    ) {
        val task = try {
            taskRepository.getById(TaskId(ObjectId(taskId)))
        } catch (e: Exception) {
            logger.warn { "ORCHESTRATOR_STATUS_HANDLER: invalid taskId=$taskId" }
            return
        }

        if (task == null) {
            logger.warn { "ORCHESTRATOR_STATUS_HANDLER: task not found taskId=$taskId" }
            return
        }

        // Only process tasks in PROCESSING state
        if (task.state != TaskStateEnum.PROCESSING) {
            logger.debug { "ORCHESTRATOR_STATUS_HANDLER: task $taskId not in PROCESSING (state=${task.state}), skipping" }
            return
        }

        when (status) {
            "running" -> {
                // Still working — no state change needed (timestamp-based stuck detection in BackgroundEngine)
            }
            "interrupted" -> handleInterrupted(task, interruptAction, interruptDescription)
            "done" -> handleDone(task, summary, keepEnvironmentRunning)
            "cancelled" -> handleCancelled(task, summary)
            "error" -> handleError(task, error)
            else -> {
                logger.debug { "ORCHESTRATOR_STATUS_HANDLER: unknown status=$status for task $taskId" }
            }
        }
    }

    private suspend fun handleInterrupted(
        task: TaskDocument,
        interruptAction: String?,
        interruptDescription: String?,
    ) {
        val action = interruptAction ?: "unknown"
        val description = interruptDescription ?: "Schválení vyžadováno"

        // Clarification vs approval: different question format for the user
        val (pendingQuestion, questionContext, phase) = when (action) {
            "clarify" -> Triple(
                description,  // Questions directly, no "Schválení:" prefix
                "Orchestrátor potřebuje upřesnění před zahájením práce",
                "clarification",
            )
            else -> Triple(
                "Schválení: $action – $description",
                "Python orchestrátor potřebuje schválení pro: $action",
                "approval",
            )
        }

        // FOREGROUND tasks: persist clarification and set state for resume.
        // User responds in chat → task gets reused → resumePythonOrchestrator() via orchestratorThreadId.
        if (task.processingMode == com.jervis.task.ProcessingMode.FOREGROUND) {
            // Persist as assistant message so it survives reconnects
            try {
                val sequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                chatMessageRepository.save(
                    com.jervis.chat.ChatMessageDocument(
                        conversationId = task.id.value,
                        correlationId = task.correlationId,
                        role = com.jervis.chat.MessageRole.ASSISTANT,
                        content = description,
                        sequence = sequence,
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString(),
                    ),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save clarification message for task ${task.id}" }
            }

            // Set to DONE — keeps orchestratorThreadId for resume.
            // When user types next message in chat, existing task gets reused → resume.
            val updatedTask = task.copy(
                state = TaskStateEnum.DONE,
                pendingUserQuestion = pendingQuestion,
            )
            taskRepository.save(updatedTask)

            logger.info { "ORCHESTRATOR_INTERRUPTED_CHAT: taskId=${task.id} action=$action phase=$phase → DONE (chat clarification)" }
            return
        }

        // BACKGROUND tasks: escalate to USER_TASK (existing behavior)
        userTaskService.failAndEscalateToUserTask(
            task = task,
            reason = "ORCHESTRATOR_INTERRUPT",
            pendingQuestion = pendingQuestion,
            questionContext = questionContext,
            interruptAction = action,
            isApproval = action != "clarify",
        )

        // BACKGROUND: agent is now truly idle (task escalated to user)
        emitQueueIdle(task)

        logger.info { "ORCHESTRATOR_INTERRUPTED: taskId=${task.id} action=$action phase=$phase → USER_TASK" }
    }

    private suspend fun handleDone(task: TaskDocument, summary: String?, keepEnvironmentRunning: Boolean = false) {
        // Persist final response for FOREGROUND tasks
        if (task.processingMode == com.jervis.task.ProcessingMode.FOREGROUND) {
            val resultSummary = summary ?: "Orchestrace dokončena"

            // Persist assistant response to DB so it survives reconnects
            // Attach workflow steps to final message for UI display
            try {
                val sequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                val workflowStepsJson = workflowTracker.getStepsAsJson(task.id.value.toString())
                val metadata = if (workflowStepsJson != null) {
                    mapOf("workflowSteps" to workflowStepsJson)
                } else emptyMap()

                chatMessageRepository.save(
                    com.jervis.chat.ChatMessageDocument(
                        conversationId = task.id.value,
                        correlationId = task.correlationId,
                        role = com.jervis.chat.MessageRole.ASSISTANT,
                        content = resultSummary,
                        sequence = sequence,
                        metadata = metadata,
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString(),
                    ),
                )
                logger.info { "ASSISTANT_MESSAGE_SAVED | taskId=${task.id} | sequence=$sequence | workflowSteps=${workflowStepsJson != null}" }

                // Clear workflow steps after saving
                workflowTracker.clearSteps(task.id.value.toString())
            } catch (e: Exception) {
                logger.error(e) { "Failed to save assistant message for task ${task.id}" }
            }
        }

        // Check if new user messages arrived during orchestration (inline messages)
        val hasInlineMessages = task.orchestrationStartedAt?.let { startedAt ->
            try {
                chatMessageRepository.countByConversationIdAndRoleAndTimestampAfter(
                    conversationId = task.id.value,
                    role = com.jervis.chat.MessageRole.USER,
                    timestamp = startedAt,
                ) > 0
            } catch (e: Exception) {
                logger.warn(e) { "Failed to check for inline messages for task ${task.id}" }
                false
            }
        } ?: false

        if (hasInlineMessages && task.processingMode == com.jervis.task.ProcessingMode.FOREGROUND) {
            // New messages arrived during orchestration — auto-requeue for processing
            val requeuedTask = task.copy(
                state = TaskStateEnum.QUEUED,
                orchestratorThreadId = null,
                orchestrationStartedAt = null,
            )
            taskRepository.save(requeuedTask)
            logger.info { "INLINE_REQUEUE: taskId=${task.id} - new user messages arrived during orchestration, re-queuing" }
        } else {
            // Normal completion
            val updatedTask = task.copy(
                state = TaskStateEnum.DONE,
                orchestratorThreadId = null,
                orchestrationStartedAt = null,
            )
            taskRepository.save(updatedTask)

            // Push background result to chat if user is online (skip IDLE — internal system tasks)
            if (task.processingMode == com.jervis.task.ProcessingMode.BACKGROUND) {
                if (chatRpcImpl.isUserOnline()) {
                    try {
                        chatRpcImpl.pushBackgroundResult(
                            taskTitle = task.taskName,
                            summary = summary ?: "Completed",
                            success = true,
                            taskId = task.id.toString(),
                            clientId = task.clientId?.toString(),
                            projectId = task.projectId?.toString(),
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to push background result to chat for task ${task.id}" }
                    }
                }
                // BACKGROUND tasks stay as DONE — no deletion, results must be traceable
            } else if (task.processingMode == com.jervis.task.ProcessingMode.IDLE) {
                // IDLE tasks stay as DONE — no deletion
            }
        }

        // Emit idle queue status — orchestration finished
        if (!hasInlineMessages) {
            emitQueueIdle(task)
        }

        // Chat compression: handled by Python orchestrator after completion

        // Save to task history for UI display
        saveTaskHistory(task, "done")

        // Auto-stop environment if not requested to keep running
        // Python finalize already attempts stop; this is a safety net
        if (!keepEnvironmentRunning) {
            autoStopEnvironment(task)
        } else {
            logger.info { "ENV_KEEP_RUNNING: taskId=${task.id} — user requested to keep environment running" }
        }

        logger.info { "ORCHESTRATOR_COMPLETE: taskId=${task.id} hasInlineMessages=$hasInlineMessages keepEnvRunning=$keepEnvironmentRunning" }
    }

    private suspend fun handleCancelled(task: TaskDocument, summary: String?) {
        val cancelMsg = summary ?: "Úkol zrušen uživatelem"
        logger.info { "ORCHESTRATOR_CANCELLED: taskId=${task.id} summary=$cancelMsg" }

        // Persist cancel message for FOREGROUND tasks
        if (task.processingMode == com.jervis.task.ProcessingMode.FOREGROUND) {
            try {
                val sequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                chatMessageRepository.save(
                    com.jervis.chat.ChatMessageDocument(
                        conversationId = task.id.value,
                        correlationId = task.correlationId,
                        role = com.jervis.chat.MessageRole.ASSISTANT,
                        content = cancelMsg,
                        sequence = sequence,
                        metadata = mapOf("status" to "cancelled"),
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString(),
                    ),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save cancel message for task ${task.id}" }
            }
        }

        // Transition to terminal DONE state and clean up orchestrator references
        val updatedTask = task.copy(
            state = TaskStateEnum.DONE,
            orchestratorThreadId = null,
            orchestrationStartedAt = null,
        )
        taskRepository.save(updatedTask)

        // BACKGROUND/IDLE tasks stay as DONE — no deletion, results must be traceable

        // Save to task history
        saveTaskHistory(task, "cancelled")

        // Auto-stop environment
        autoStopEnvironment(task)

        // Emit idle queue status
        emitQueueIdle(task)
    }

    private suspend fun handleError(task: TaskDocument, error: String?) {
        val errorMsg = error ?: "Unknown orchestrator error"
        logger.error { "ORCHESTRATOR_ERROR: taskId=${task.id} error=$errorMsg" }

        // Persist error for FOREGROUND tasks
        if (task.processingMode == com.jervis.task.ProcessingMode.FOREGROUND) {
            val errorContent = "Chyba orchestrátoru: $errorMsg"
            try {
                val sequence = chatMessageRepository.countByConversationId(task.id.value) + 1
                chatMessageRepository.save(
                    com.jervis.chat.ChatMessageDocument(
                        conversationId = task.id.value,
                        correlationId = task.correlationId,
                        role = com.jervis.chat.MessageRole.ASSISTANT,
                        content = errorContent,
                        sequence = sequence,
                        metadata = mapOf("status" to "error"),
                        clientId = task.clientId.toString(),
                        projectId = task.projectId?.toString(),
                    ),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to save error message for task ${task.id}" }
            }
        }

        // Push error to chat if user is online
        if (task.processingMode != com.jervis.task.ProcessingMode.FOREGROUND && chatRpcImpl.isUserOnline()) {
            try {
                chatRpcImpl.pushBackgroundResult(
                    taskTitle = task.taskName,
                    summary = errorMsg,
                    success = false,
                    taskId = task.id.toString(),
                    metadata = mapOf("needsReaction" to "true"),
                    clientId = task.clientId?.toString(),
                    projectId = task.projectId?.toString(),
                )
            } catch (e: Exception) {
                logger.warn(e) { "Failed to push background error to chat for task ${task.id}" }
            }
        }

        userTaskService.failAndEscalateToUserTask(
            task = task,
            reason = "PYTHON_ORCHESTRATOR_ERROR",
            errorMessage = errorMsg,
        )
        taskService.updateState(task, TaskStateEnum.ERROR)

        // Save to task history for UI display
        saveTaskHistory(task, "error")

        // Auto-stop environment on error (same as done — don't waste cluster resources)
        // Python finalize is the primary stop mechanism; this is the safety net.
        // If user needs the environment for debugging, they can re-provision via UI/chat.
        autoStopEnvironment(task)

        // Emit idle queue status — orchestration errored out
        emitQueueIdle(task)
    }

    /**
     * Auto-stop environment after task completion (non-blocking).
     *
     * Resolves the environment for the task's project and deprovisions it.
     * This is a safety net — the Python finalize node also attempts to stop.
     * Only stops RUNNING environments; ignores other states.
     */
    private suspend fun autoStopEnvironment(task: TaskDocument) {
        val projectId = task.projectId ?: return
        try {
            val env = environmentService.resolveEnvironmentForProject(projectId)
            if (env != null && env.state == com.jervis.environment.EnvironmentState.RUNNING) {
                logger.info { "ENV_AUTO_STOP: taskId=${task.id} envId=${env.id} name=${env.name}" }
                environmentK8sService.deprovisionEnvironment(env.id)
            }
        } catch (e: Exception) {
            // Non-blocking — task is already done, environment stop failure shouldn't affect user
            logger.warn(e) { "ENV_AUTO_STOP_FAILED: taskId=${task.id} projectId=$projectId" }
        }
    }

    /**
     * Emit idle queue status to UI — orchestration is no longer running.
     * Simplified emission (no pending item details); next BackgroundEngine
     * queue status will provide full info.
     */
    private suspend fun emitQueueIdle(task: TaskDocument) {
        try {
            agentOrchestratorRpc.emitGlobalQueueStatus()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit idle queue status for task ${task.id}" }
        }
    }

    /**
     * Save a completed task to the task_history collection for UI display.
     * Includes orchestrator step durations computed from persisted orchestratorSteps.
     */
    private suspend fun saveTaskHistory(task: TaskDocument, status: String) {
        try {
            val projectName = task.projectId?.let { pid ->
                try { projectService.getProjectById(pid).name } catch (_: Exception) { null }
            }
            val clientName = try {
                clientRepository.getById(task.clientId)?.name
            } catch (_: Exception) { null }

            val workflowStepsJson = workflowTracker.getStepsAsJson(task.id.value.toString())

            // Reload task to get latest orchestratorSteps (they were $push'd during processing)
            val freshTask = taskRepository.getById(task.id) ?: task
            val orchestratorStepsJson = if (freshTask.orchestratorSteps.isNotEmpty()) {
                try {
                    val now = java.time.Instant.now()
                    val steps = freshTask.orchestratorSteps
                    val jsonArray = kotlinx.serialization.json.buildJsonArray {
                        steps.forEachIndexed { index, step ->
                            val nextTimestamp = if (index < steps.lastIndex) steps[index + 1].timestamp else now
                            val durationMs = java.time.Duration.between(step.timestamp, nextTimestamp).toMillis()
                            add(kotlinx.serialization.json.buildJsonObject {
                                put("node", kotlinx.serialization.json.JsonPrimitive(step.node))
                                put("label", kotlinx.serialization.json.JsonPrimitive(nodeLabels[step.node] ?: step.node))
                                put("durationMs", kotlinx.serialization.json.JsonPrimitive(durationMs))
                            })
                        }
                    }
                    jsonArray.toString()
                } catch (e: Exception) {
                    logger.debug(e) { "Failed to serialize orchestrator steps for history" }
                    null
                }
            } else null

            val preview = task.content.take(100).let {
                if (task.content.length > 100) "$it..." else it
            }

            taskHistoryRepository.save(
                TaskHistoryDocument(
                    taskId = task.id.value.toString(),
                    taskPreview = preview,
                    projectName = projectName,
                    clientName = clientName,
                    startedAt = task.orchestrationStartedAt,
                    status = status,
                    processingMode = task.processingMode.name,
                    workflowStepsJson = workflowStepsJson,
                    orchestratorStepsJson = orchestratorStepsJson,
                ),
            )
            logger.info { "TASK_HISTORY_SAVED | taskId=${task.id} | status=$status | orchestratorSteps=${freshTask.orchestratorSteps.size}" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to save task history for task ${task.id}" }
        }
    }

    /** Node name → Czech label (shared with UI). */
    private val nodeLabels = mapOf(
        "intake" to "Analýza úlohy",
        "evidence" to "Shromažďování kontextu",
        "evidence_pack" to "Shromažďování kontextu",
        "plan" to "Plánování",
        "plan_steps" to "Plánování kroků",
        "execute" to "Provádění",
        "execute_step" to "Provádění kroku",
        "evaluate" to "Vyhodnocení",
        "finalize" to "Dokončení",
        "respond" to "Generování odpovědi",
        "clarify" to "Upřesnění",
        "decompose" to "Dekompozice na cíle",
        "select_goal" to "Výběr cíle",
        "advance_step" to "Další krok",
        "advance_goal" to "Další cíl",
        "git_operations" to "Git operace",
        "report" to "Generování reportu",
    )
}
